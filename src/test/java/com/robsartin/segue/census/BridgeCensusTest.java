package com.robsartin.segue.census;

import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.segue.export.LogProjection;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Counted by hand off {@code InventedCensus.log()}. One edge carries a {@code musicbrainz}
 * provenance — row 13 — and it names two entities. One of the two states a class and the other
 * states none, which is the whole distinction the ADR 55 residual turns on.
 */
class BridgeCensusTest {

  private static final BridgeCensus CENSUS =
      BridgeCensus.of(
          LogProjection.of(new InventedCensus.FakeAssertionLog().with(InventedCensus.log())));

  @Test
  @DisplayName("both endpoints of a MusicBrainz-sourced edge count as reached")
  void shouldCountBothEndpointsWhenOneEdgeCarriesAMusicBrainzProvenance() {
    assertThat(CENSUS.entitiesReached()).isEqualTo(2);
  }

  @Test
  @DisplayName("an entity the fold cannot describe is reached and not counted as described")
  void shouldCountOnlyTheDescribedEntityWhenOneOfTwoStatesNoClasses() {
    assertThat(CENSUS.entitiesReachedWithClasses()).isEqualTo(1);
  }
}
