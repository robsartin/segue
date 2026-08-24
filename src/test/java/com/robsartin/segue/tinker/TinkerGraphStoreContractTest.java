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
                  tinker.shortestPaths(
                      com.robsartin.segue.fixture.Fixture.CAVE,
                      com.robsartin.segue.fixture.Fixture.HILLCOAT,
                      4,
                      50)))
          .isEqualTo(
              GraphStoreContract.signatures(
                  jena.shortestPaths(
                      com.robsartin.segue.fixture.Fixture.CAVE,
                      com.robsartin.segue.fixture.Fixture.HILLCOAT,
                      4,
                      50)));
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

      assertThat(keys(tinker.corroborated(2))).isEqualTo(keys(jena.corroborated(2)));
    }
  }

  /** Sorted edge keys, so two engines can be compared as sets rather than by cardinality. */
  private static java.util.List<String> keys(
      java.util.List<com.robsartin.segue.domain.EdgeRecord> edges) {
    return edges.stream().map(com.robsartin.segue.domain.EdgeRecord::key).sorted().toList();
  }
}
