package com.robsartin.segue.ingest;

import com.robsartin.segue.domain.AssertionRecord;
import com.robsartin.segue.domain.LoggedAssertion;
import com.robsartin.segue.domain.NodeAssertion;
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
    log.append(assertion);
    apply(graph, assertion);
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
    }
  }
}
