package com.robsartin.segue.ingest;

import com.robsartin.segue.domain.Equivalences;
import com.robsartin.segue.domain.LoggedAssertion;
import com.robsartin.segue.domain.NodeAssertion;
import com.robsartin.segue.domain.NodeRecord;
import com.robsartin.segue.domain.Retractions;
import com.robsartin.segue.port.AssertionLog;
import com.robsartin.segue.port.GraphStore;
import com.robsartin.segue.port.IdentityMerge;
import com.robsartin.segue.wikidata.KindMapper;
import java.util.List;

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

  /** A node claim with today's kind; anything else unchanged. */
  private static LoggedAssertion rederived(LoggedAssertion assertion) {
    return assertion instanceof NodeAssertion node ? KindMapper.rederive(node) : assertion;
  }
}
