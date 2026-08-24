package com.robsartin.segue.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.robsartin.segue.domain.AssertionRecord;
import com.robsartin.segue.domain.NodeAssertion;
import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.domain.Provenance;
import com.robsartin.segue.port.AssertionLog;
import com.robsartin.segue.sqlite.SqliteAssertionLog;
import com.robsartin.segue.tinker.TinkerGraphStore;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ADR 24: the graph is rebuilt at boot by replaying the log in sequence order. */
class GraphProjectorTest {

  private static final Provenance WIKIDATA = new Provenance("wikidata", "ref", Instant.EPOCH, 1.0);

  @Test
  @DisplayName("replaying node then edge claims rebuilds the projected graph")
  void replayRebuildsTheGraph() {
    try (AssertionLog log = SqliteAssertionLog.inMemory();
        TinkerGraphStore store = new TinkerGraphStore()) {
      log.append(new NodeAssertion("Q1", NodeKind.PERSON, "Nick Cave", WIKIDATA));
      log.append(new NodeAssertion("Q2", NodeKind.GROUP, "The Bad Seeds", WIKIDATA));
      log.append(new AssertionRecord("Q1", "Q2", "MEMBER_OF", null, null, WIKIDATA));

      GraphProjector.project(log, store);

      assertThat(store.node("Q1")).isPresent();
      assertThat(store.node("Q1").orElseThrow().label()).isEqualTo("Nick Cave");
      assertThat(store.edgeCount()).isEqualTo(1);
      assertThat(store.edges("Q1"))
          .singleElement()
          .extracting(e -> e.typeCode())
          .isEqualTo("MEMBER_OF");
    }
  }

  @Test
  @DisplayName("a replayed graph matches the log exactly, including sub-millisecond timestamps")
  void replayPreservesFullPrecisionTimestamps() {
    // Issue #6 / ADR 19: replaying the log must reproduce the log exactly. Provenance.assertedAt
    // is stored with nanosecond precision, so a projection that truncates it (e.g. to epoch
    // millis) silently disagrees with the log it was replayed from.
    Instant nanosecondPrecision = Instant.parse("2026-08-24T09:15:30.123456789Z");
    Provenance precise = new Provenance("wikidata", "ref", nanosecondPrecision, 1.0);

    try (AssertionLog log = SqliteAssertionLog.inMemory();
        TinkerGraphStore store = new TinkerGraphStore()) {
      log.append(new NodeAssertion("Q1", NodeKind.PERSON, "Nick Cave", WIKIDATA));
      log.append(new NodeAssertion("Q2", NodeKind.GROUP, "The Bad Seeds", WIKIDATA));
      log.append(new AssertionRecord("Q1", "Q2", "MEMBER_OF", null, null, precise));

      GraphProjector.project(log, store);

      assertThat(store.edges("Q1"))
          .singleElement()
          .extracting(e -> e.sources().get(0).assertedAt())
          .isEqualTo(nanosecondPrecision);
    }
  }

  @Test
  @DisplayName("an empty log projects an empty graph")
  void emptyLogEmptyGraph() {
    try (AssertionLog log = SqliteAssertionLog.inMemory();
        TinkerGraphStore store = new TinkerGraphStore()) {
      GraphProjector.project(log, store);
      assertThat(store.edgeCount()).isZero();
    }
  }

  @Test
  @DisplayName("replay failure is fatal and names the sequence number")
  void replayFailureNamesSequence() {
    try (AssertionLog log = SqliteAssertionLog.inMemory();
        TinkerGraphStore store = new TinkerGraphStore()) {
      log.append(new NodeAssertion("Q1", NodeKind.PERSON, "Nick Cave", WIKIDATA));
      // An edge whose target node was never asserted: applying it must fail...
      log.append(new AssertionRecord("Q1", "Q404", "MEMBER_OF", null, null, WIKIDATA));

      // ...fatally, naming the offending position (the second assertion).
      assertThatThrownBy(() -> GraphProjector.project(log, store))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("sequence 2");
    }
  }
}
