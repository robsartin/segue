package com.robsartin.segue.ingest;

import com.robsartin.segue.domain.AssertionRecord;
import com.robsartin.segue.domain.LocalEntity;
import com.robsartin.segue.domain.LoggedAssertion;
import com.robsartin.segue.domain.NodeAssertion;
import com.robsartin.segue.domain.OwnerEdge;
import com.robsartin.segue.domain.Retraction;
import com.robsartin.segue.domain.SameAs;
import com.robsartin.segue.port.AssertionLog;
import com.robsartin.segue.port.GraphStore;
import java.util.List;
import java.util.Objects;

/**
 * The only thing in the system that writes.
 *
 * <p>Source adapters and, later, MCP tools hand claims to this and never touch a store. ArchUnit
 * enforces that (rule {@code onlyIngestAppliesClaimsToTheGraph}), which turns ADR 19's invariant
 * from a convention into a build failure.
 *
 * <p><b>Order matters and is not an accident.</b> The log is appended first, then the graph is
 * updated, and the two are deliberately not atomic. If the graph update fails, the log is ahead —
 * the recoverable direction, because a restart replays it. The reverse ordering would lose the
 * claim permanently and leave the log authoritative in name only.
 */
public final class IngestService {

  private final AssertionLog log;
  private final GraphStore graph;

  public IngestService(AssertionLog log, GraphStore graph) {
    this.log = Objects.requireNonNull(log, "log");
    this.graph = Objects.requireNonNull(graph, "graph");
  }

  /** Append one claim to the log, then apply it to the graph. */
  public void record(LoggedAssertion assertion) {
    Objects.requireNonNull(assertion, "assertion");
    if (assertion instanceof Retraction) {
      // Refused before the append, not after: this method's whole contract is log-then-graph,
      // and a retraction has no graph half. Appending one here and then failing would leave a
      // retraction in the log that the caller had been told did not happen.
      throw new IllegalArgumentException("a retraction is appended by retract(), not record()");
    }
    log.append(assertion);
    apply(graph, assertion);
  }

  /**
   * Append a retraction (ADR 44). The third write path, and the only one that touches no graph.
   *
   * <p><b>Static, and taking the log, deliberately.</b> Every other write here is log-then-graph,
   * and a retraction has no graph half: {@link GraphStore} cannot remove anything, and widening the
   * port that exists to keep the engine choice reversible (ADR 18) so that a dev-side tool can is
   * what ADR 41 already refused. So the running graph is stale until the next boot rebuilds it from
   * the log, which is exactly the contract ADR 24 already gives replay.
   *
   * <p>Requiring an {@code IngestService} instance would mean handing the retraction tool a {@code
   * GraphStore} it must never touch, purely so a constructor could be satisfied - the opposite of
   * the fence that tool needs. This way the append still happens inside {@code ingest}, so {@code
   * onlyIngestAppliesClaimsToTheGraph} holds unchanged and the tool can be forbidden a graph
   * outright.
   */
  public static void retract(AssertionLog log, Retraction retraction) {
    Objects.requireNonNull(log, "log");
    Objects.requireNonNull(retraction, "retraction");
    log.append(retraction);
  }

  /** Record a batch in order; each claim is logged and applied before the next is considered. */
  public void recordAll(List<LoggedAssertion> assertions) {
    Objects.requireNonNull(assertions, "assertions");
    assertions.forEach(this::record);
  }

  /**
   * Apply a claim to a graph.
   *
   * <p>Shared with {@link GraphProjector} so replay and live ingest cannot drift. Two copies of
   * this switch would be free to disagree, and a rebuilt graph that silently differs from the one
   * it replaced defeats the point of having a log at all.
   */
  static void apply(GraphStore graph, LoggedAssertion assertion) {
    switch (assertion) {
      case NodeAssertion node -> graph.upsertNode(node.toNode());
      case AssertionRecord edge -> graph.record(edge);
      // Unreachable, and a guard rather than a path. A retraction is honoured by the FOLD - both
      // projections drop it and everything it retracts before they get here (ADR 44) - so
      // reaching this line means a caller replayed the log without applying that rule, which
      // would produce a graph still holding the edges somebody took back out. Silently ignoring
      // it is the one response that would hide exactly that.
      case Retraction retraction ->
          throw new IllegalStateException(
              "a retraction is honoured by the projection's fold, never applied to a graph: "
                  + retraction.qid());
      // The owner's own claims (#92) project exactly like the sourced ones - a minted entity is
      // a node, an asserted relationship is an edge - and differ only in the provenance they
      // carry, which each record decides for itself (LocalEntity.toNode, OwnerEdge.toAssertion)
      // so replay and this switch cannot attribute the same claim differently.
      case LocalEntity local -> graph.upsertNode(local.toNode());
      case OwnerEdge edge -> graph.record(edge.toAssertion());
      // #92 Task 4 gives a merge its graph effect: resolving edges and the rating onto the
      // canonical id. Until then it is logged and does nothing here, which is the honest state
      // rather than a half-merge - ADR 19 makes the log the source of truth, so a merge recorded
      // now is applied by the replay that follows Task 4, with nothing lost in between.
      case SameAs ignored -> {}
    }
  }
}
