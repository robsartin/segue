package com.robsartin.segue.tinker;

import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.segue.port.GraphStore;
import com.robsartin.segue.port.GraphStoreContract;

class TinkerGraphStoreContractTest extends GraphStoreContract {

  @Override
  protected GraphStore createStore() {
    return new TinkerGraphStore();
  }

  @org.junit.jupiter.api.Test
  @org.junit.jupiter.api.DisplayName("both engines enumerate exactly the same route set")
  void enginesAgreeOnFullRouteSet() {
    try (GraphStore tinker = new TinkerGraphStore();
        GraphStore jena = new com.robsartin.segue.jena.JenaGraphStore()) {
      com.robsartin.segue.fixture.Fixture.seed(tinker);
      com.robsartin.segue.fixture.Fixture.seed(jena);

      assertThat(tinker.edgeCount()).isEqualTo(jena.edgeCount());
      assertThat(
              GraphStoreContract.signatures(
                  tinker.paths(
                      com.robsartin.segue.fixture.Fixture.CAVE,
                      com.robsartin.segue.fixture.Fixture.HILLCOAT,
                      4)))
          .isEqualTo(
              GraphStoreContract.signatures(
                  jena.paths(
                      com.robsartin.segue.fixture.Fixture.CAVE,
                      com.robsartin.segue.fixture.Fixture.HILLCOAT,
                      4)));
    }
  }

  @org.junit.jupiter.api.Test
  @org.junit.jupiter.api.DisplayName(
      "both engines agree on the edge sets for the audit, time-travel and corroboration queries")
  void enginesAgreeOnEdgeSets() {
    try (GraphStore tinker = new TinkerGraphStore();
        GraphStore jena = new com.robsartin.segue.jena.JenaGraphStore()) {
      com.robsartin.segue.fixture.Fixture.seed(tinker);
      com.robsartin.segue.fixture.Fixture.seed(jena);

      java.time.Instant since = java.time.Instant.parse("2026-08-15T00:00:00Z");
      assertThat(keys(tinker.assertedBy("lastfm", since)))
          .isEqualTo(keys(jena.assertedBy("lastfm", since)));
      assertThat(keys(tinker.assertedBy("llm:claude", java.time.Instant.EPOCH)))
          .isEqualTo(keys(jena.assertedBy("llm:claude", java.time.Instant.EPOCH)));

      java.time.LocalDate asOf = java.time.LocalDate.of(1984, 6, 1);
      assertThat(keys(tinker.validAt(com.robsartin.segue.fixture.Fixture.BAD_SEEDS, asOf)))
          .isEqualTo(keys(jena.validAt(com.robsartin.segue.fixture.Fixture.BAD_SEEDS, asOf)));

      // #176: compare the engines at every N the fixture makes meaningful - 0 through one past
      // its maximum corroboration - rather than at the single value a divergence hid beside.
      for (int n = 0; n <= 3; n++) {
        assertThat(keys(tinker.corroborated(n)))
            .as("engines disagree on corroborated(%d)", n)
            .isEqualTo(keys(jena.corroborated(n)));
      }
    }
  }

  @org.junit.jupiter.api.Test
  @org.junit.jupiter.api.DisplayName(
      "both engines place the owner-only and the layered owner claim where the fixture says")
  void shouldPlaceOwnerClaimsByCorroborationWhenEitherEngineAnswersTheRange() {
    try (GraphStore tinker = new TinkerGraphStore();
        GraphStore jena = new com.robsartin.segue.jena.JenaGraphStore()) {
      com.robsartin.segue.fixture.Fixture.seed(tinker);
      com.robsartin.segue.fixture.Fixture.seed(jena);

      // Equal key sets alone would be satisfied by two engines making the same mistake. These pin
      // the shape the fixture guarantees, per engine, so the range loop cannot pass vacuously.
      assertCorroborationShape("tinker", tinker);
      assertCorroborationShape("jena", jena);
    }
  }

  private static void assertCorroborationShape(String engine, GraphStore store) {
    java.util.function.Predicate<com.robsartin.segue.domain.EdgeRecord> ownerOnly =
        e ->
            e.fromQid().equals(com.robsartin.segue.fixture.Fixture.LOCAL_NOVELIST)
                && e.toQid().equals(com.robsartin.segue.fixture.Fixture.LOCAL_NOVEL);
    java.util.function.Predicate<com.robsartin.segue.domain.EdgeRecord> layered =
        e ->
            e.fromQid().equals(com.robsartin.segue.fixture.Fixture.CAVE)
                && e.toQid().equals(com.robsartin.segue.fixture.Fixture.ASS_SAW_ANGEL);

    // The owner standing alone (#176): corroboration 0, which is an answer and not an absence.
    assertThat(store.corroborated(0))
        .as("%s: the owner-only edge is what corroborated(0) exists to return", engine)
        .anyMatch(ownerOnly);
    assertThat(store.corroborated(1))
        .as("%s: the owner is not a witness, so the owner-only edge stops at 0", engine)
        .noneMatch(ownerOnly);

    // The owner layered onto a source's claim (#92): one real source, so it survives 1 and the
    // owner does not push it to 2.
    assertThat(store.corroborated(0))
        .as("%s: the layered edge is corroborated once, so it is in the 0 set", engine)
        .anyMatch(layered);
    assertThat(store.corroborated(1))
        .as("%s: the layered edge has a real source, so it survives 1", engine)
        .anyMatch(layered);
    assertThat(store.corroborated(2))
        .as("%s: the owner must not push the layered edge to 2", engine)
        .noneMatch(layered);

    assertThat(store.corroborated(3))
        .as("%s: nothing in the fixture has three distinct sources", engine)
        .isEmpty();
  }

  /** Sorted edge keys, so two engines can be compared as sets rather than by cardinality. */
  private static java.util.List<String> keys(
      java.util.List<com.robsartin.segue.domain.EdgeRecord> edges) {
    return edges.stream().map(com.robsartin.segue.domain.EdgeRecord::key).sorted().toList();
  }
}
