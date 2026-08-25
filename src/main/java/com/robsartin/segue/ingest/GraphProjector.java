package com.robsartin.segue.ingest;

import com.robsartin.segue.domain.LoggedAssertion;
import com.robsartin.segue.port.AssertionLog;
import com.robsartin.segue.port.GraphStore;
import java.util.List;

/**
 * Rebuilds the derived graph from the append-only log (ADR 19, ADR 24): replays every assertion in
 * sequence order into a {@link GraphStore}. This is the only place that applies logged claims to
 * the graph, which is what keeps the graph a projection rather than a second source of truth.
 *
 * <p>Replay is fatal on the first failure, naming the sequence number: a log that will not project
 * is a corruption to surface at boot, not to limp past.
 */
public final class GraphProjector {

  private GraphProjector() {}

  /**
   * Replay {@code log} into {@code store}. An empty log leaves an empty graph.
   *
   * @return how many assertions were applied
   */
  public static long project(AssertionLog log, GraphStore store) {
    List<LoggedAssertion> assertions = log.readAll();
    long applied = 0;
    for (int i = 0; i < assertions.size(); i++) {
      LoggedAssertion assertion = assertions.get(i);
      try {
        IngestService.apply(store, assertion);
        applied++;
      } catch (RuntimeException e) {
        // Sequence is 1-based, matching the log's own autoincrement.
        throw new IllegalStateException("replay failed at sequence " + (i + 1), e);
      }
    }
    return applied;
  }
}
