package com.robsartin.segue.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.robsartin.segue.domain.AssertionRecord;
import com.robsartin.segue.domain.LoggedAssertion;
import com.robsartin.segue.domain.NodeAssertion;
import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.domain.Provenance;
import com.robsartin.segue.port.AssertionLog;
import com.robsartin.segue.port.GraphStore;
import com.robsartin.segue.sqlite.SqliteAssertionLog;
import com.robsartin.segue.tinker.TinkerGraphStore;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The live write path is one-way and ordered: log first, then graph.
 *
 * <p>The ordering is the whole point (ADR 19). It is deliberately not atomic, and the direction of
 * that non-atomicity is chosen: if the graph write fails, the log is ahead and a restart replays
 * it. The reverse would lose the claim for good.
 */
class IngestServiceTest {

  private static final Provenance WIKIDATA =
      new Provenance("wikidata", "S-1", Instant.parse("2026-08-24T09:00:00Z"), 1.0);

  private AssertionLog log;
  private GraphStore graph;
  private IngestService ingest;

  @BeforeEach
  void setUp() {
    log = SqliteAssertionLog.inMemory();
    graph = new TinkerGraphStore();
    ingest = new IngestService(log, graph);
  }

  @AfterEach
  void tearDown() {
    graph.close();
    log.close();
  }

  @Test
  @DisplayName("a node claim lands in the log and the graph")
  void recordsNodeInBoth() {
    NodeAssertion node = new NodeAssertion("Q5593", NodeKind.PERSON, "Pablo Picasso", WIKIDATA);

    ingest.record(node);

    assertThat(log.readAll()).containsExactly(node);
    assertThat(graph.node("Q5593")).isPresent();
  }

  @Test
  @DisplayName("a batch is recorded in order")
  void recordAllPreservesOrder() {
    List<LoggedAssertion> batch =
        List.of(
            new NodeAssertion("Q1", NodeKind.PERSON, "A", WIKIDATA),
            new NodeAssertion("Q2", NodeKind.GROUP, "B", WIKIDATA),
            new AssertionRecord("Q1", "Q2", "MEMBER_OF", null, null, WIKIDATA));

    ingest.recordAll(batch);

    assertThat(log.readAll()).containsExactlyElementsOf(batch);
    assertThat(graph.edgeCount()).isEqualTo(1);
  }

  @Test
  @DisplayName("when the graph rejects a claim the log has already kept it")
  void logLeadsTheGraph() {
    // TinkerGraphStore.record calls requireVertex, which throws when an endpoint is unknown.
    AssertionRecord dangling =
        new AssertionRecord("Q404", "Q405", "MEMBER_OF", null, null, WIKIDATA);

    assertThatThrownBy(() -> ingest.record(dangling)).isInstanceOf(IllegalStateException.class);

    assertThat(log.readAll()).containsExactly(dangling);
  }

  @Test
  @DisplayName("live ingest and replay produce the same graph")
  void liveAndReplayAgree() {
    // Both go through IngestService.apply. If they ever diverged, a rebuilt graph would
    // silently differ from the one it replaced — the failure ADR 19 exists to prevent.
    ingest.record(new NodeAssertion("Q1", NodeKind.PERSON, "A", WIKIDATA));
    ingest.record(new NodeAssertion("Q2", NodeKind.GROUP, "B", WIKIDATA));
    ingest.record(new AssertionRecord("Q1", "Q2", "MEMBER_OF", null, null, WIKIDATA));

    try (GraphStore rebuilt = new TinkerGraphStore()) {
      GraphProjector.project(log, rebuilt);

      assertThat(rebuilt.edgeCount()).isEqualTo(graph.edgeCount());
      assertThat(rebuilt.node("Q1")).isEqualTo(graph.node("Q1"));
      assertThat(rebuilt.edges("Q1")).hasSameSizeAs(graph.edges("Q1"));
    }
  }
}
