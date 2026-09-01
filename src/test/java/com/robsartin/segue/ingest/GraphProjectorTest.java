package com.robsartin.segue.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.robsartin.segue.domain.AssertionRecord;
import com.robsartin.segue.domain.NodeAssertion;
import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.domain.Provenance;
import com.robsartin.segue.domain.Retraction;
import com.robsartin.segue.port.AssertionLog;
import com.robsartin.segue.port.IdentityMerge;
import com.robsartin.segue.sqlite.SqliteAssertionLog;
import com.robsartin.segue.tinker.TinkerGraphStore;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ADR 24: the graph is rebuilt at boot by replaying the log in sequence order. */
class GraphProjectorTest {

  private static final Provenance WIKIDATA = new Provenance("wikidata", "ref", Instant.EPOCH, 1.0);

  private static final Instant RETRACTED_AT = Instant.parse("2026-08-27T12:00:00Z");

  @Test
  @DisplayName("replaying node then edge claims rebuilds the projected graph")
  void replayRebuildsTheGraph() {
    try (AssertionLog log = SqliteAssertionLog.inMemory();
        TinkerGraphStore store = new TinkerGraphStore()) {
      log.append(new NodeAssertion("Q1", NodeKind.PERSON, "Nick Cave", WIKIDATA));
      log.append(new NodeAssertion("Q2", NodeKind.GROUP, "The Bad Seeds", WIKIDATA));
      log.append(new AssertionRecord("Q1", "Q2", "MEMBER_OF", null, null, WIKIDATA));

      GraphProjector.project(log, store, IdentityMerge.NONE);

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

      GraphProjector.project(log, store, IdentityMerge.NONE);

      assertThat(store.edges("Q1"))
          .singleElement()
          .extracting(e -> e.sources().get(0).assertedAt())
          .isEqualTo(nanosecondPrecision);
    }
  }

  @Test
  @DisplayName("a KindMapper improvement corrects an existing node on re-projection alone")
  void replayRederivesKindFromStoredClasses() {
    // The point of issue #60, and the whole reason P31 is stored.
    //
    // This is the log a run BEFORE the issue-#52 sweep would have written: the mapper of the
    // day did not know Q56816954 (heavy metal band), so it derived CONCEPT and that was the
    // only thing kept. Under ADR 19 the log is append-only, so the claim cannot be edited -
    // and before this change the node stayed CONCEPT until something fetched the entity from
    // Wikidata again, which is what cost two full re-seeds (issue #55).
    //
    // Today's mapper knows the class. Nothing here touches the network, nothing re-records the
    // claim, and nothing rewrites the log: replaying it is enough.
    try (AssertionLog log = SqliteAssertionLog.inMemory();
        TinkerGraphStore store = new TinkerGraphStore()) {
      log.append(
          new NodeAssertion(
              "Q0900001", NodeKind.CONCEPT, "Ninebark Sermon", List.of("Q56816954"), WIKIDATA));

      GraphProjector.project(log, store, IdentityMerge.NONE);

      assertThat(store.node("Q0900001").orElseThrow().kind()).isEqualTo(NodeKind.GROUP);
      // The log itself is untouched: it still says what the source said and what we made of
      // it at the time. Re-derivation is the projection's job, not a rewrite of history.
      assertThat(log.readAll())
          .singleElement()
          .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.type(NodeAssertion.class))
          .extracting(NodeAssertion::kind)
          .isEqualTo(NodeKind.CONCEPT);
    }
  }

  @Test
  @DisplayName("a class the mapper still does not know projects as CONCEPT, not as it was logged")
  void replayDoesNotInventAKindForAnUnknownClass() {
    // The counterfactual for the test above: the correction comes from the mapper knowing the
    // class, not from re-projection flattering the data. It is also the reverse direction -
    // if a class is ever REMOVED from the whitelist because it was wrong, the removal has to
    // reach existing nodes the same way the addition does.
    try (AssertionLog log = SqliteAssertionLog.inMemory();
        TinkerGraphStore store = new TinkerGraphStore()) {
      log.append(
          new NodeAssertion(
              "Q0900002", NodeKind.PERSON, "Marisol Kettleby", List.of("Q999999999"), WIKIDATA));

      GraphProjector.project(log, store, IdentityMerge.NONE);

      assertThat(store.node("Q0900002").orElseThrow().kind()).isEqualTo(NodeKind.CONCEPT);
    }
  }

  @Test
  @DisplayName("a source that states no classes keeps the kind it recorded")
  void replayKeepsTheRecordedKindWhenThereIsNothingToRederiveFrom() {
    // Not every source is Wikidata. One that classifies without stating classes - the fixture
    // adapter, or any future similarity source - has nothing to re-derive from, and its claim
    // is the best answer available rather than a gap to fill with CONCEPT.
    try (AssertionLog log = SqliteAssertionLog.inMemory();
        TinkerGraphStore store = new TinkerGraphStore()) {
      log.append(new NodeAssertion("Q0900003", NodeKind.PERSON, "Marisol Kettleby", WIKIDATA));

      GraphProjector.project(log, store, IdentityMerge.NONE);

      assertThat(store.node("Q0900003").orElseThrow().kind()).isEqualTo(NodeKind.PERSON);
    }
  }

  @Test
  @DisplayName("an empty log projects an empty graph")
  void emptyLogEmptyGraph() {
    try (AssertionLog log = SqliteAssertionLog.inMemory();
        TinkerGraphStore store = new TinkerGraphStore()) {
      GraphProjector.project(log, store, IdentityMerge.NONE);
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
      assertThatThrownBy(() -> GraphProjector.project(log, store, IdentityMerge.NONE))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("sequence 2");
    }
  }

  @Test
  @DisplayName("replay honours a retraction: the entity and its edges are not in the rebuilt graph")
  void replayHonoursARetraction() {
    // ADR 44 and the motivating case for it: an entity resolved to the wrong QID and then
    // expanded leaves its edges in the graph with no way out. The log is not edited - see
    // theLogStillHoldsEveryOriginalClaim below, which is the same log read straight back.
    try (AssertionLog log = SqliteAssertionLog.inMemory();
        TinkerGraphStore store = new TinkerGraphStore()) {
      log.append(new NodeAssertion("Q900101", NodeKind.GROUP, "The Wrong Ones", WIKIDATA));
      log.append(new NodeAssertion("Q900102", NodeKind.WORK, "A Painting", WIKIDATA));
      log.append(new AssertionRecord("Q900101", "Q900102", "PERFORMED", null, null, WIKIDATA));
      log.append(new Retraction("Q900101", "resolved to the painters, not the band", RETRACTED_AT));

      GraphProjector.project(log, store, IdentityMerge.NONE);

      assertThat(store.node("Q900101")).isEmpty();
      assertThat(store.edgeCount()).isZero();
      assertThat(store.node("Q900102")).isPresent();
    }
  }

  @Test
  @DisplayName("the log still holds every original claim after a retraction has been replayed")
  void theLogStillHoldsEveryOriginalClaim() {
    // The acceptance criterion of issue #68 stated as a test. Retraction is a new claim, not a
    // deletion: everything ADR 19 rests on - replay reproducing the graph, the audit trail,
    // ADR 42's offline re-derivation - would become conditional on nobody having deleted
    // anything if this were false.
    NodeAssertion wrong = new NodeAssertion("Q900101", NodeKind.GROUP, "The Wrong Ones", WIKIDATA);
    AssertionRecord edge =
        new AssertionRecord("Q900101", "Q900102", "PERFORMED", null, null, WIKIDATA);
    Retraction retraction = new Retraction("Q900101", "wrong entity", RETRACTED_AT);

    try (AssertionLog log = SqliteAssertionLog.inMemory();
        TinkerGraphStore store = new TinkerGraphStore()) {
      log.append(wrong);
      log.append(new NodeAssertion("Q900102", NodeKind.WORK, "A Painting", WIKIDATA));
      log.append(edge);
      log.append(retraction);

      GraphProjector.project(log, store, IdentityMerge.NONE);

      assertThat(log.readAll()).contains(wrong, edge, retraction);
    }
  }

  @Test
  @DisplayName("a claim recorded after a retraction is replayed: re-adding is the way back")
  void replayHonoursClaimsMadeAfterARetraction() {
    try (AssertionLog log = SqliteAssertionLog.inMemory();
        TinkerGraphStore store = new TinkerGraphStore()) {
      log.append(new NodeAssertion("Q900101", NodeKind.GROUP, "The Wrong Ones", WIKIDATA));
      log.append(new Retraction("Q900101", "wrong entity", RETRACTED_AT));
      log.append(new NodeAssertion("Q900101", NodeKind.GROUP, "The Right Ones", WIKIDATA));
      log.append(new NodeAssertion("Q900102", NodeKind.WORK, "A Song", WIKIDATA));
      log.append(new AssertionRecord("Q900101", "Q900102", "PERFORMED", null, null, WIKIDATA));

      GraphProjector.project(log, store, IdentityMerge.NONE);

      assertThat(store.node("Q900101").orElseThrow().label()).isEqualTo("The Right Ones");
      assertThat(store.edgeCount()).isEqualTo(1);
    }
  }

  @Test
  @DisplayName("a retraction is never applied to a store, and the applier says so if asked")
  void aRetractionIsNeverAppliedToAStore() {
    // The guard in IngestService.apply. Unreachable through either projection - both drop
    // retractions in the fold - so this is what proves the guard is a refusal rather than a
    // silent no-op that would leave a graph holding edges somebody took back out.
    try (TinkerGraphStore store = new TinkerGraphStore()) {
      assertThatThrownBy(
              () ->
                  IngestService.apply(
                      store,
                      IdentityMerge.NONE,
                      new Retraction("Q900101", "wrong entity", RETRACTED_AT)))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("Q900101");
    }
  }
}
