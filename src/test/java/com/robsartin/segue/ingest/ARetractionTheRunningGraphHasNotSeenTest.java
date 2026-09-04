package com.robsartin.segue.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.robsartin.segue.domain.AssertionRecord;
import com.robsartin.segue.domain.NodeAssertion;
import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.domain.Provenance;
import com.robsartin.segue.domain.Retraction;
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
 * Issue #234: the gate #233 added asks the RUNNING graph, and ADR 44 leaves the running graph
 * holding a retracted entity until the next boot rebuilds it from the log. So an edge naming a
 * just-retracted id passes the gate, is appended, and the boot cannot get past it.
 *
 * <p><b>The first test looks like it asserts the defect, and it asserts a decision.</b> ADR 24's
 * 2026-09-04 amendment for this issue is the ruling: the witness stays the running graph, because
 * the witness that would see the retraction is the log's fold and asking it costs a whole {@code
 * readAll} per claim on a path that records hundreds of claims per expansion. What closes the case
 * is the other three tests — the boot names the row, and one more retraction repairs it without
 * deleting anything. If a future change makes the gate ask the log, this file and that amendment
 * are what have to be revisited together.
 *
 * <p><b>The fourth test is the reason the cheap version of that gate was rejected.</b> A retraction
 * reaches backwards only (ADR 44, question 4), so adding an entity back is how it returns, and an
 * edge claimed after the re-add is legal. A gate keyed on "the log holds a retraction naming this
 * id" would refuse it. Whatever asks the log next has to pass this test.
 *
 * <p><b>Not a case inside {@code ARefusedEdgeNeverReachesTheLogTest}.</b> That file's subject is an
 * edge the gate REFUSES; this one's is an edge it accepts.
 */
class ARetractionTheRunningGraphHasNotSeenTest {

  /** Invented, ADR 58's leading zero — no Wikibase allocation can ever give it a referent. */
  private static final String WREN = "Q0900101";

  /** The endpoint that is retracted while the graph goes on holding a node for it. */
  private static final String KETTLES = "Q0900102";

  private static final Provenance WIKIDATA =
      new Provenance("wikidata", "S-1", Instant.parse("2026-09-04T09:00:00Z"), 0.80);
  private static final Instant RETRACTED_AT = Instant.parse("2026-09-04T10:00:00Z");
  private static final Instant REPAIRED_AT = Instant.parse("2026-09-04T11:00:00Z");

  private static final AssertionRecord EDGE =
      new AssertionRecord(WREN, KETTLES, "INFLUENCED_BY", null, null, WIKIDATA);

  @Test
  @DisplayName(
      "the gate asks the running graph, which a retraction has not reached, so the edge is appended")
  void shouldAppendAnEdgeNamingARetractedEndpointWhenOnlyTheRunningGraphIsAsked(@TempDir Path dir) {
    Path db = dir.resolve("segue.db");
    try (AssertionLog log = new SqliteAssertionLog(db);
        GraphStore graph = new TinkerGraphStore()) {
      IngestService ingest = new IngestService(log, graph, IdentityMerge.NONE);
      ingest.record(new NodeAssertion(WREN, NodeKind.PERSON, "Wren Alderman", WIKIDATA));
      ingest.record(new NodeAssertion(KETTLES, NodeKind.PERSON, "Kettles Nye", WIKIDATA));
      retract(log, "resolved to the wrong entity", RETRACTED_AT);

      assertThatCode(() -> ingest.record(EDGE))
          .as("the gate asks GraphStore.node, and the retraction has not reached the graph")
          .doesNotThrowAnyException();

      assertThat(graph.node(KETTLES))
          .as("ADR 44: GraphStore cannot remove anything, so the node is still there")
          .isPresent();
      assertThat(log.readAll()).hasSize(4).endsWith(EDGE);
    }
  }

  @Test
  @DisplayName("every boot stops at the edge when it names an endpoint a retraction took away")
  void shouldStopEveryBootWhenAnEdgeNamesARetractedEndpoint(@TempDir Path dir) {
    Path db = dir.resolve("segue.db");
    poison(db);

    // Twice, deliberately: a row that stops one boot stops every later one, and ADR 19 forbids
    // removing it. That is what makes this a poison pill rather than a bad error message.
    assertThatThrownBy(() -> boot(db))
        .hasMessageContaining("sequence 4")
        .hasMessageContaining(KETTLES)
        .hasMessageContaining("retract the endpoint");
    assertThatThrownBy(() -> boot(db))
        .hasMessageContaining("sequence 4")
        .hasMessageContaining(KETTLES)
        .hasMessageContaining("retract the endpoint");
  }

  @Test
  @DisplayName(
      "the boot succeeds again when the endpoint is retracted a second time, and the log keeps every row")
  void shouldBootAgainWhenTheEndpointIsRetractedASecondTime(@TempDir Path dir) {
    Path db = dir.resolve("segue.db");
    poison(db);

    try (AssertionLog log = new SqliteAssertionLog(db)) {
      retract(log, "repairing the log after #234", REPAIRED_AT);
    }

    try (AssertionLog reopened = new SqliteAssertionLog(db);
        GraphStore rebuilt = new TinkerGraphStore()) {
      assertThatCode(() -> GraphProjector.project(reopened, rebuilt, IdentityMerge.NONE))
          .as("the second retraction lies after the edge, so the edge stops projecting")
          .doesNotThrowAnyException();
      assertThat(rebuilt.node(WREN)).isPresent();
      assertThat(rebuilt.node(KETTLES)).isEmpty();
      assertThat(rebuilt.edgeCount()).isZero();
      assertThat(reopened.readAll())
          .as("nothing is deleted: the repair is one more claim (ADR 19, ADR 44)")
          .hasSize(5);
    }
  }

  @Test
  @DisplayName("an edge naming an entity added back after its retraction still boots")
  void shouldStillBootWhenAnEdgeNamesAnEntityAddedBackAfterItsRetraction(@TempDir Path dir) {
    Path db = dir.resolve("segue.db");
    try (AssertionLog log = new SqliteAssertionLog(db);
        GraphStore graph = new TinkerGraphStore()) {
      IngestService ingest = new IngestService(log, graph, IdentityMerge.NONE);
      ingest.record(new NodeAssertion(WREN, NodeKind.PERSON, "Wren Alderman", WIKIDATA));
      ingest.record(new NodeAssertion(KETTLES, NodeKind.PERSON, "Kettles Nye", WIKIDATA));
      retract(log, "resolved to the wrong entity", RETRACTED_AT);
      // ADR 44 question 4: an entity comes back by being added again, and nothing special
      // happens on the way — the new claim is simply newer than the retraction.
      ingest.record(new NodeAssertion(KETTLES, NodeKind.PERSON, "Kettles Nye", WIKIDATA));
      ingest.record(EDGE);
    }

    try (AssertionLog reopened = new SqliteAssertionLog(db);
        GraphStore rebuilt = new TinkerGraphStore()) {
      assertThatCode(() -> GraphProjector.project(reopened, rebuilt, IdentityMerge.NONE))
          .doesNotThrowAnyException();
      assertThat(rebuilt.node(KETTLES)).isPresent();
      assertThat(rebuilt.edgeCount()).isOne();
    }
  }

  /** The log the issue describes, written through the live path exactly as a server would. */
  private static void poison(Path db) {
    try (AssertionLog log = new SqliteAssertionLog(db);
        GraphStore graph = new TinkerGraphStore()) {
      IngestService ingest = new IngestService(log, graph, IdentityMerge.NONE);
      ingest.record(new NodeAssertion(WREN, NodeKind.PERSON, "Wren Alderman", WIKIDATA));
      ingest.record(new NodeAssertion(KETTLES, NodeKind.PERSON, "Kettles Nye", WIKIDATA));
      retract(log, "resolved to the wrong entity", RETRACTED_AT);
      ingest.record(EDGE);
    }
  }

  /**
   * A retraction, appended the way the dev tool appends one. In production it is a different
   * process (ADR 60), which is the whole reason the running graph can be stale about it.
   */
  private static void retract(AssertionLog log, String reason, Instant at) {
    IngestService.retract(log, new Retraction(KETTLES, reason, at));
  }

  private static long boot(Path db) {
    try (AssertionLog reopened = new SqliteAssertionLog(db);
        GraphStore rebuilt = new TinkerGraphStore()) {
      return GraphProjector.project(reopened, rebuilt, IdentityMerge.NONE);
    }
  }
}
