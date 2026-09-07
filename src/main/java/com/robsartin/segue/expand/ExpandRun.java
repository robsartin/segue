package com.robsartin.segue.expand;

import com.robsartin.segue.domain.LocalEntity;
import com.robsartin.segue.expansion.EntityExpansion;
import com.robsartin.segue.expansion.ExpansionOutcome;
import com.robsartin.segue.port.GraphStore;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The run: for each promotion, expand it and tally what happened (#284).
 *
 * <p><b>{@code dryRun} touches neither {@code expansion} nor a network</b> — it reads {@link
 * #graph} directly, the same read {@link EntityExpansion#expand} would make before ever reaching an
 * adapter, so the count is exact without paying for a single HTTP round trip.
 *
 * <p><b>{@code run} catches a {@code RuntimeException} out of one entity's expansion, and that
 * catch belongs here rather than in {@link EntityExpansion}.</b> {@code SegueService.expandEntity}
 * wraps {@code adapter.expand} in no {@code try} — right for one interactive call, where the MCP
 * layer turns a throw into a protocol error, and wrong for a batch that has already written most of
 * what it came for by the time one bad row throws. Nothing is retried: a refused endpoint, an
 * unreachable source and a truncation are all reported outcomes of an expansion that completed,
 * exactly as {@link EntityExpansion#expand} already treats them one level down, and retrying would
 * need a policy this tool does not own — the adapters' own clients already retry with backoff.
 *
 * <p><b>No progress line and no log line this class writes ever carries a qid.</b> A line per
 * promotion, over every promotion, in qid order, is the owner's whole promoted population
 * enumerated down a terminal — the bulk read ADR 39 refused, by another route. {@code log.warn} on
 * a caught throw is deliberately position-only ("expansion 2 of 431 threw"), never qid-bearing.
 */
public final class ExpandRun {

  private static final Logger log = LoggerFactory.getLogger(ExpandRun.class);

  private final EntityExpansion expansion;
  private final GraphStore graph;

  public ExpandRun(EntityExpansion expansion, GraphStore graph) {
    this.expansion = Objects.requireNonNull(expansion, "expansion");
    this.graph = Objects.requireNonNull(graph, "graph");
  }

  /**
   * Count what a real run would visit, without visiting it.
   *
   * <p>A local entity always holds a node too — minting one records it — so counting graph presence
   * first would double it into {@code inTheGraph}. Checked in the same order {@link
   * EntityExpansion#expand} refuses in doesn't apply here: the two buckets are kept disjoint by
   * checking {@link LocalEntity#isLocal} first, so a minted entity lands in {@code minted} and
   * nowhere else, matching the two distinct refusals a real run would give it — never both.
   */
  public Preflight dryRun(List<String> promotions, Consumer<String> lines) {
    Objects.requireNonNull(promotions, "promotions");
    Objects.requireNonNull(lines, "lines");
    int inTheGraph = 0;
    int minted = 0;
    for (String qid : promotions) {
      if (LocalEntity.isLocal(qid)) {
        minted++;
      } else if (graph.node(qid).isPresent()) {
        inTheGraph++;
      }
    }
    // Task 9 replaces this with ExpansionReport.dryRunLines(preflight), which does not exist
    // yet; this is a placeholder line so the "dry run" contract has something to assert on until
    // then.
    lines.accept("dry run: nothing was written");
    return new Preflight(promotions.size(), inTheGraph, minted);
  }

  /**
   * Expand every promotion, one at a time, in the order given, and tally what happened.
   *
   * <p>The aggregate report is Task 9's — until that renderer exists, this emits nothing but
   * progress lines and returns the tally.
   */
  public ExpansionTally run(List<String> promotions, int maxNewEdges, Consumer<String> lines) {
    Objects.requireNonNull(promotions, "promotions");
    Objects.requireNonNull(lines, "lines");

    int expanded = 0;
    int addedNothing = 0;
    int failed = 0;
    int nodesAdded = 0;
    int edgesAdded = 0;
    int skippedNeighbors = 0;
    int refusedEndpoints = 0;
    int boundCut = 0;
    Map<String, Integer> edgesBySource = new LinkedHashMap<>();
    Map<String, Integer> unavailableBySource = new LinkedHashMap<>();
    Map<String, Integer> truncatedBySource = new LinkedHashMap<>();
    Map<ExpansionOutcome.Reason, Integer> refusalsByReason = new LinkedHashMap<>();

    for (int i = 0; i < promotions.size(); i++) {
      String qid = promotions.get(i);
      ExpansionOutcome outcome;
      try {
        outcome = expansion.expand(qid, maxNewEdges);
      } catch (RuntimeException thrown) {
        // #284. One entity is not the run. SegueService.expandEntity wraps adapter.expand in no
        // try — right for one interactive call, where the MCP layer turns a throw into a
        // protocol error, and wrong for a batch that has already written most of what it came
        // for. Named without the qid: a line per entity naming the entity would enumerate the
        // owner's promotions down a terminal, which is the bulk read ADR 39 refused.
        failed++;
        log.warn("expansion {} of {} threw: {}", i + 1, promotions.size(), thrown.getMessage());
        lines.accept(progress(i, promotions.size(), "failed"));
        continue;
      }
      switch (outcome) {
        case ExpansionOutcome.Refused refused -> {
          refusalsByReason.merge(refused.reason(), 1, Integer::sum);
          lines.accept(progress(i, promotions.size(), "refused: " + refused.reason()));
        }
        case ExpansionOutcome.Expanded one -> {
          expanded++;
          nodesAdded += one.nodesAdded();
          edgesAdded += one.edgesAdded();
          skippedNeighbors += one.skippedNeighbors();
          refusedEndpoints += one.refusedEndpoints().size();
          if (one.nodesAdded() == 0 && one.edgesAdded() == 0) {
            addedNothing++;
          }
          one.edgesBySource().forEach((source, n) -> edgesBySource.merge(source, n, Integer::sum));
          one.unavailableSources()
              .forEach(source -> unavailableBySource.merge(source, 1, Integer::sum));
          one.truncatingSources()
              .forEach(source -> truncatedBySource.merge(source, 1, Integer::sum));
          if (one.boundCutTheConcatenation()) {
            boundCut++;
          }
          lines.accept(
              progress(
                  i,
                  promotions.size(),
                  one.edgesAdded() + " edge(s), " + one.nodesAdded() + " new node(s)"));
        }
      }
    }

    return new ExpansionTally(
        promotions.size(),
        expanded,
        addedNothing,
        failed,
        nodesAdded,
        edgesAdded,
        skippedNeighbors,
        refusedEndpoints,
        boundCut,
        edgesBySource,
        unavailableBySource,
        truncatedBySource,
        refusalsByReason);
  }

  /** A position and a detail — never a qid or a label. See the class javadoc. */
  private static String progress(int index, int total, String detail) {
    return "[" + (index + 1) + "/" + total + "] " + detail;
  }
}
