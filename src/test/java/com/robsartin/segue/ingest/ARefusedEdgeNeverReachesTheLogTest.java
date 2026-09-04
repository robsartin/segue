package com.robsartin.segue.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.robsartin.segue.domain.AssertionRecord;
import com.robsartin.segue.domain.NodeAssertion;
import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.domain.Provenance;
import com.robsartin.segue.port.AssertionLog;
import com.robsartin.segue.port.GraphStore;
import com.robsartin.segue.port.IdentityMerge;
import com.robsartin.segue.sqlite.SqliteAssertionLog;
import com.robsartin.segue.tinker.TinkerGraphStore;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Issue #233: a sourced edge the graph refuses is not written down.
 *
 * <p><b>The second test is the issue.</b> The first says the failed call left nothing behind, which
 * reads as tidiness; the second says what happens if it did — {@code GraphProjector.project} is
 * fatal on the first failure (ADR 24), so a row the graph refused once is a row it refuses at every
 * boot, and ADR 19 forbids removing it. Measured before the fix: the live call threw {@code
 * assertion references unknown entity Q0900102 - upsert the node first}, the log held two rows, and
 * two consecutive boots over that file both threw {@code replay failed at sequence 2} with the same
 * cause.
 *
 * <p><b>Not a case inside {@code IngestServiceTest}.</b> That file's subject is the ORDERING — log
 * first, then graph — and it holds the test this one replaces ({@code logLeadsTheGraph}, which
 * asserted the defect as the contract). The ordering is unchanged by this work and its test should
 * go on saying so; what belongs here is the precondition asked before the ordering begins.
 *
 * <p><b>Both were committed {@code @Disabled}, red for the honest reason: the log kept the row.</b>
 * The annotations came off in the commit that made them pass.
 */
class ARefusedEdgeNeverReachesTheLogTest {

  /** Invented, ADR 58's leading zero — no Wikibase allocation can ever give it a referent. */
  private static final String WREN = "Q0900101";

  /** The endpoint nothing describes. Same shape, same reason. */
  private static final String KETTLES = "Q0900102";

  private static final Provenance WIKIDATA =
      new Provenance("wikidata", "S-1", Instant.parse("2026-09-04T09:00:00Z"), 0.80);

  @Test
  @DisplayName("should leave the log untouched when the graph holds no node for an endpoint")
  void shouldLeaveTheLogUntouchedWhenTheGraphHoldsNoNodeForAnEndpoint() {
    NodeAssertion seed = new NodeAssertion(WREN, NodeKind.PERSON, "Wren Alderman", WIKIDATA);
    AssertionRecord edge =
        new AssertionRecord(WREN, KETTLES, "INFLUENCED_BY", null, null, WIKIDATA);

    try (AssertionLog log = SqliteAssertionLog.inMemory();
        GraphStore graph = new TinkerGraphStore()) {
      IngestService ingest = new IngestService(log, graph, IdentityMerge.NONE);
      ingest.record(seed);

      assertThatThrownBy(() -> ingest.record(edge))
          .isInstanceOf(UnknownEndpointException.class)
          .hasMessageContaining(KETTLES);

      assertThat(log.readAll())
          .as("a claim the caller was told failed must not be in the log")
          .containsExactly(seed);
      assertThat(graph.edgeCount()).isZero();
    }
  }

  @Test
  @DisplayName("should leave a log that still boots when record refuses the edge")
  void shouldLeaveALogThatStillBootsWhenRecordRefusesTheEdge(@TempDir Path dir) {
    Path db = dir.resolve("segue.db");
    AssertionRecord edge =
        new AssertionRecord(WREN, KETTLES, "INFLUENCED_BY", null, null, WIKIDATA);

    try (AssertionLog log = new SqliteAssertionLog(db);
        GraphStore graph = new TinkerGraphStore()) {
      IngestService ingest = new IngestService(log, graph, IdentityMerge.NONE);
      ingest.record(new NodeAssertion(WREN, NodeKind.PERSON, "Wren Alderman", WIKIDATA));
      assertThatThrownBy(() -> ingest.record(edge)).isInstanceOf(UnknownEndpointException.class);
    }

    // The next boot, over the file that failed call left behind. A real file rather than
    // inMemory(): the whole point is that the row survives the process that wrote it.
    try (AssertionLog reopened = new SqliteAssertionLog(db);
        GraphStore rebuilt = new TinkerGraphStore()) {
      assertThatCode(() -> GraphProjector.project(reopened, rebuilt, IdentityMerge.NONE))
          .as("every boot after a refused edge must still project the log")
          .doesNotThrowAnyException();
      assertThat(rebuilt.node(WREN)).isPresent();
      assertThat(rebuilt.edgeCount()).isZero();
    }
  }
}
