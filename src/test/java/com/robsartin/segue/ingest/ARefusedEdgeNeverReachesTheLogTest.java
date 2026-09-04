package com.robsartin.segue.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.robsartin.segue.domain.AssertionRecord;
import com.robsartin.segue.domain.LocalEntity;
import com.robsartin.segue.domain.NodeAssertion;
import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.domain.OwnerEdge;
import com.robsartin.segue.domain.Provenance;
import com.robsartin.segue.domain.Retraction;
import com.robsartin.segue.domain.SameAs;
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
 *
 * <p><b>Issue #228's owner claims are cases here, not a second file</b> (the reconciliation note in
 * that issue's spec asks for exactly this). {@code IngestService.claim} refuses the same shape on
 * the owner's side, asking the LOG'S FOLD rather than the running graph, and throws the same {@link
 * UnknownEndpointException}. What makes those cases belong here rather than in {@code
 * IngestServiceTest} is the second half: the log the refusal leaves behind still boots. {@code
 * IngestServiceTest} keeps the cases about what the refusal SAYS - which endpoints it names, and
 * which repair fits which id shape - because that is a message contract rather than a log one.
 *
 * <p><b>The file's name says "edge" and one of those cases is a merge.</b> That is not a
 * mis-filing: a merge whose local side the fold holds no node for is refused precisely because the
 * first EDGE naming the canonical id it would leave behind is the row that cannot boot. The merge
 * row itself is inert - issue #228 measured a log holding one and booting - so the claim being
 * refused here is still about an edge, one row early.
 */
class ARefusedEdgeNeverReachesTheLogTest {

  /** Invented, ADR 58's leading zero — no Wikibase allocation can ever give it a referent. */
  private static final String WREN = "Q0900101";

  /** The endpoint nothing describes. Same shape, same reason. */
  private static final String KETTLES = "Q0900102";

  /** A local the owner minted: ADR 59's two leading zeros. Invented. */
  private static final String LAPSE = "Q00900042";

  /** A merge's canonical side: ADR 62's eleven digits, no leading zero. Invented. */
  private static final String FORFEIT = "Q10000900112";

  private static final Instant CLAIMED_AT = Instant.parse("2026-09-04T09:00:00Z");

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

  @Test
  @DisplayName("should leave a log that still boots when claim refuses a merge with no local side")
  void shouldLeaveALogThatStillBootsWhenClaimRefusesAMergeWithNoLocalSide(@TempDir Path dir) {
    // Issue #228's break 1, at the producer. The merge is legal-looking and the projection holds
    // no node for its local side, so it would build no stand-in for FORFEIT and the first edge
    // naming FORFEIT would stop every later boot on a row ADR 19 forbids deleting.
    Path db = dir.resolve("segue.db");

    try (AssertionLog log = new SqliteAssertionLog(db)) {
      IngestService.claim(
          log,
          LocalEntity.minted(LAPSE, NodeKind.WORK, "a working title he took back", CLAIMED_AT));
      IngestService.retract(log, new Retraction(LAPSE, "the wrong thing", CLAIMED_AT));

      assertThatThrownBy(
              () -> IngestService.claim(log, SameAs.declared(LAPSE, FORFEIT, CLAIMED_AT)))
          .isInstanceOf(UnknownEndpointException.class)
          .hasMessageContaining(LAPSE)
          .hasMessageContaining(FORFEIT);

      assertThat(log.readAll())
          .as("a claim the caller was told failed must not be in the log")
          .hasSize(2);
    }

    try (AssertionLog reopened = new SqliteAssertionLog(db);
        GraphStore rebuilt = new TinkerGraphStore()) {
      assertThatCode(() -> GraphProjector.project(reopened, rebuilt, IdentityMerge.NONE))
          .as("every boot after a refused merge must still project the log")
          .doesNotThrowAnyException();
      assertThat(rebuilt.node(FORFEIT)).isEmpty();
    }
  }

  @Test
  @DisplayName("should leave a log that still boots when claim refuses an owner edge")
  void shouldLeaveALogThatStillBootsWhenClaimRefusesAnOwnerEdge(@TempDir Path dir) {
    // The same refusal on the other arm: the fold holds a node for LAPSE and none for KETTLES, so
    // the edge names an endpoint nothing stands for. The witness is the log's fold rather than the
    // running graph - claim() has no graph at all - and the type is the same.
    Path db = dir.resolve("segue.db");

    try (AssertionLog log = new SqliteAssertionLog(db)) {
      IngestService.claim(
          log, LocalEntity.minted(LAPSE, NodeKind.WORK, "a self-pressed record", CLAIMED_AT));

      assertThatThrownBy(
              () ->
                  IngestService.claim(
                      log, OwnerEdge.claimed(LAPSE, KETTLES, "INFLUENCED_BY", CLAIMED_AT)))
          .isInstanceOf(UnknownEndpointException.class)
          .hasMessageContaining(KETTLES);

      assertThat(log.readAll())
          .as("a claim the caller was told failed must not be in the log")
          .hasSize(1);
    }

    try (AssertionLog reopened = new SqliteAssertionLog(db);
        GraphStore rebuilt = new TinkerGraphStore()) {
      assertThatCode(() -> GraphProjector.project(reopened, rebuilt, IdentityMerge.NONE))
          .as("every boot after a refused owner edge must still project the log")
          .doesNotThrowAnyException();
      assertThat(rebuilt.node(LAPSE)).isPresent();
      assertThat(rebuilt.edgeCount()).isZero();
    }
  }
}
