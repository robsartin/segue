package com.robsartin.segue.expand;

import com.robsartin.segue.domain.LocalEntity;
import com.robsartin.segue.expansion.EntityExpansion;
import com.robsartin.segue.port.GraphStore;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * The run: for each promotion, expand it and tally what happened (#284).
 *
 * <p><b>{@code dryRun} touches neither {@code expansion} nor a network</b> — it reads {@link
 * #graph} directly, the same read {@link EntityExpansion#expand} would make before ever reaching an
 * adapter, so the count is exact without paying for a single HTTP round trip.
 */
public final class ExpandRun {

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
}
