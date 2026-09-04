package com.robsartin.segue.ingest;

import com.robsartin.segue.domain.AssertionRecord;
import com.robsartin.segue.domain.Equivalences;
import com.robsartin.segue.domain.LoggedAssertion;
import com.robsartin.segue.domain.NodeAssertion;
import com.robsartin.segue.domain.NodeRecord;
import com.robsartin.segue.domain.OwnerEdge;
import com.robsartin.segue.domain.Retractions;
import com.robsartin.segue.port.AssertionLog;
import com.robsartin.segue.port.GraphStore;
import com.robsartin.segue.port.IdentityMerge;
import com.robsartin.segue.wikidata.KindMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Rebuilds the derived graph from the append-only log (ADR 19, ADR 24): replays every assertion in
 * sequence order into a {@link GraphStore}. Nothing else reads the log to rebuild the graph, which
 * is what keeps the graph a projection rather than a second source of truth.
 *
 * <p><b>The apply step itself is shared, deliberately.</b> This class owns replay, not the switch
 * over assertion kinds: it calls the same package-private {@link IngestService#apply} that live
 * ingest calls, so a rebuilt graph cannot silently differ from the one it replaced. Both callers
 * live in this package, which is what {@code onlyIngestAppliesClaimsToTheGraph} fences — replay
 * needs no exemption from that rule, because it is not outside it.
 *
 * <p>Replay is fatal on the first failure, naming the sequence number: a log that will not project
 * is a corruption to surface at boot, not to limp past.
 *
 * <p><b>One family of failure is refused before the loop begins, by name</b> (#228). An edge the
 * fold keeps, naming an entity no node in the log stands for, cannot be applied by any store, and
 * the message a store gives for it names the id and no cause. So the log is checked against {@link
 * Equivalences#nodesTheFoldHolds} first, every offending row is listed with its sequence number,
 * and the repair is named. The loop below still catches everything else on the first row that
 * fails.
 *
 * <p><b>Node kinds are re-derived here, always, from the {@code P31} classes the claim carries</b>
 * (ADR 42, issue #60). Replay is the moment the derived state is rebuilt, so it is the moment to
 * rebuild it with today's rules rather than the rules that happened to be compiled in when the
 * claim was first written down. The log is not touched: it keeps saying what the source said and
 * what was made of it at the time, which is what ADR 19 means by append-only. A merge's stand-in
 * node goes through the same rule, because it stands in for a node this fold re-derived (#222).
 *
 * <p><b>Always on, deliberately, and not a flag.</b> An opt-in correction is one every future
 * caller has to remember, and the cost of forgetting is invisible - a graph that looks right and
 * quietly holds a stale classification. That was already the shape of issue #55. The rule itself
 * lives in {@link KindMapper#rederive}, which is also what {@code LogProjection} calls.
 *
 * <p><b>A merge's canonical node is created before replay begins, not at the merge's own row</b>
 * (#178). {@link Equivalences#standIns} says why - an edge whose endpoint has been folded onto the
 * canonical id can be claimed earlier in the log than the merge that names it, and {@code
 * TinkerGraphStore.record} refuses an endpoint it has never seen. The exporter's fold seeds the
 * same map from the same method, so the two cannot disagree about which entities exist.
 *
 * <p><b>Retractions are honoured here too</b> (ADR 44, issue #68), through the same shared-rule
 * shape: {@link Retractions} decides which rows reach the graph, and {@code LogProjection} asks it
 * the same question about the same log. A retraction is a claim like any other and is never applied
 * to a store - it changes what the fold produces, not what the log holds - so the claims it reaches
 * are skipped before {@link IngestService#apply} ever sees them.
 */
public final class GraphProjector {

  private GraphProjector() {}

  /**
   * Replay {@code log} into {@code store}. An empty log leaves an empty graph.
   *
   * <p><b>{@code merges} is required rather than defaulted, and it is not always the real one.</b>
   * A merge has an effect outside the graph - the owner's rating follows the equivalence - and
   * replay is what repairs a merge whose rating was never carried, because affinity is the one
   * thing here that is durable and is therefore rebuilt by nothing. But three of this method's four
   * production callers are read-only dev tools that replay into a throwaway graph ({@code
   * ExportCli}, {@code RecommendCli}, {@code RateCli}); an exporter that wrote a rating would be
   * exactly what {@code ArchitectureTest.theExporterOnlyReads} exists to prevent, and no ArchUnit
   * rule would catch it, because the write would happen inside {@code port}. They pass {@link
   * IdentityMerge#NONE} and say so. The application's boot replay passes the real one.
   *
   * @param merges what follows a merge outside the graph; {@link IdentityMerge#NONE} for a caller
   *     that must not write the taste layer
   * @return how many assertions were applied. Not the number that survived the retractions: an edge
   *     the fold yields nothing for reached the graph with nothing, and is not counted (#224 for a
   *     withdrawn edge, #178 for a self-loop the fold collapsed)
   */
  public static long project(AssertionLog log, GraphStore store, IdentityMerge merges) {
    List<LoggedAssertion> assertions = log.readAll();
    Retractions retractions = Retractions.in(assertions);
    // The graph half of a merge (#178), built from the same log and beside the same log's
    // retractions, because they are the same kind of rule: neither edits a row, and both decide
    // what the fold makes of one. Equivalences.in already asks Retractions.survives itself, so a
    // merge a retraction reaches folds nothing. folding() rather than in(): the fold is also where
    // an edge naming a stand-in a retraction took away stops projecting (#224).
    Equivalences equivalences = Equivalences.folding(assertions);
    refuseRowsNamingAnEntityNoNodeStandsFor(assertions, retractions, equivalences);
    // Every merged entity's canonical id gets its node before anything is applied (#178). See
    // Equivalences.standIns for why this cannot wait for the merge's own row: an edge whose
    // endpoint the fold below moves onto the canonical id can be claimed EARLIER in the log than
    // the merge that names it, and TinkerGraphStore.record refuses an endpoint it has never seen.
    // A real claim about the canonical id, wherever it sits in the log, lands on top of the
    // stand-in and wins by upsertNode's last-writer-wins.
    for (NodeRecord standIn : Equivalences.standIns(assertions, KindMapper::rederive).values()) {
      store.upsertNode(standIn);
    }
    long applied = 0;
    for (int i = 0; i < assertions.size(); i++) {
      LoggedAssertion assertion = assertions.get(i);
      if (!retractions.survives(i, assertion)) {
        continue;
      }
      try {
        if (IngestService.apply(store, merges, equivalences, rederived(assertion))) {
          applied++;
        }
      } catch (RuntimeException e) {
        // Sequence is 1-based, matching the log's own autoincrement.
        throw new IllegalStateException("replay failed at sequence " + (i + 1), e);
      }
    }
    return applied;
  }

  /**
   * Refuse the whole log, by name, where an edge the fold keeps names an entity no node in the log
   * stands for (#228).
   *
   * <p><b>What this replaces is not a silence, it is an unhelpful noise.</b> {@code
   * TinkerGraphStore.record} refuses such an edge with {@code assertion references unknown entity …
   * - upsert the node first}, wrapped as {@code replay failed at sequence N}, which names the id
   * and nothing about why the id has no node or what to do next. The two logs issue #228 measured
   * both arrive that way, and both are permanent: ADR 19 forbids removing the row, so the message
   * is what the operator has.
   *
   * <p><b>The repair it names is a retraction, and it is deliberately not the advice the store
   * gives.</b> "Upsert the node first" is right while the edge is still unwritten — that is what
   * {@code IngestService.claim}'s own gate says, in its own words, before the append. Once the edge
   * is IN the log it is wrong: replay is positional, so a node claim appended after the edge leaves
   * the boot failing at the edge's own sequence number — measured for #233 on the sourced path, and
   * pinned here by the case in {@code MergeAfterARetractionTest} that appends a node claim after
   * the edge and watches the boot fail at that same row. Retracting the endpoint withdraws the edge
   * under ADR 44 and deletes nothing. A merge is the other repair that works, and works for a
   * reason worth stating: its stand-in is built before the replay loop starts, so it reaches a row
   * earlier in the log than the merge itself.
   *
   * <p><b>Before anything is applied, and reporting every row rather than the first.</b> The store
   * is untouched when this throws, so a refused boot leaves no half-built graph; and an operator
   * repairing a log wants the whole list, not one row per restart. That is a departure from the
   * replay loop's own fail-on-the-first-row rule, and it is deliberate: this checks a decidable
   * property of the log, where the loop catches whatever a store happens to object to.
   *
   * <p><b>{@code LogProjection} deliberately does not do this.</b> The exporter has to produce a
   * picture and reports the same shortfall as {@code danglingEdges}. ADR 44 argues why the boot's
   * answer is the opposite one, and {@code MergeAfterARetractionTest} pins both.
   */
  private static void refuseRowsNamingAnEntityNoNodeStandsFor(
      List<LoggedAssertion> assertions, Retractions retractions, Equivalences equivalences) {
    Set<String> held = Equivalences.nodesTheFoldHolds(assertions);
    List<String> rows = new ArrayList<>();
    for (int i = 0; i < assertions.size(); i++) {
      LoggedAssertion assertion = assertions.get(i);
      if (!retractions.survives(i, assertion)) {
        continue;
      }
      Optional<AssertionRecord> claimed = asEdge(assertion);
      if (claimed.isEmpty()) {
        // A node claim, a minted entity, a merge and a retraction name no endpoint to be missing.
        continue;
      }
      Optional<LoggedAssertion> kept = equivalences.foldEndpoints(assertion);
      if (kept.isEmpty()) {
        // Withdrawn (#224) or collapsed (#178). Nothing reaches the graph, so nothing can be
        // missing an endpoint.
        continue;
      }
      describe(rows, i + 1, claimed.get(), asEdge(kept.get()).orElseThrow(), held);
    }
    if (rows.isEmpty()) {
      return;
    }
    throw new IllegalStateException(
        "replay refused: "
            + rows.size()
            + " row(s) name an entity no node stands for.\n"
            + String.join("\n", rows)
            + "\nNothing is deleted (ADR 19). To repair: retract the endpoint, which withdraws the"
            + " edge under ADR 44 without deleting anything. Appending a node claim for the named"
            + " id does NOT repair it — replay is positional, so a claim later than the row leaves"
            + " the boot failing at that same sequence. A merge whose local side the projection"
            + " does hold repairs it too, because the stand-in it builds is created before replay"
            + " begins. See ADR 44, ADR 59 and issue #228.");
  }

  /**
   * The two claims that name endpoints, as the one shape that carries {@link
   * AssertionRecord#edgeKey}; empty for the four that name none.
   */
  private static Optional<AssertionRecord> asEdge(LoggedAssertion assertion) {
    return switch (assertion) {
      case AssertionRecord edge -> Optional.of(edge);
      case OwnerEdge edge -> Optional.of(edge.toAssertion());
      default -> Optional.empty();
    };
  }

  /**
   * One line per endpoint the fold holds no node for, naming the claim as the log wrote it and the
   * endpoint as the fold resolved it — so an operator can see whether the id he typed is the id the
   * boot complained about.
   *
   * <p>How many entities the folded edge names is {@link AssertionRecord#endpoints()}, in {@code
   * domain} since #228's reconciliation: a self-loop is one thing to repair, not two, and both of
   * {@code IngestService}'s gates read that same rule rather than a copy of it.
   */
  private static void describe(
      List<String> rows,
      int sequence,
      AssertionRecord claimed,
      AssertionRecord folded,
      Set<String> held) {
    for (String endpoint : folded.endpoints()) {
      if (!held.contains(endpoint)) {
        rows.add(
            "  sequence "
                + sequence
                + ": "
                + claimed.edgeKey()
                + " names "
                + endpoint
                + ", which no node stands for");
      }
    }
  }

  /** A node claim with today's kind; anything else unchanged. */
  private static LoggedAssertion rederived(LoggedAssertion assertion) {
    return assertion instanceof NodeAssertion node ? KindMapper.rederive(node) : assertion;
  }
}
