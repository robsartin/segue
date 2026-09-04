package com.robsartin.segue.census;

import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.segue.domain.Recommendations;
import com.robsartin.segue.export.LogProjection;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Counted by hand off {@code InventedCensus.log()}. The thirteen nodes' degrees, sorted, are {@code
 * [0, 0, 0, 0, 1, 1, 1, 2, 2, 2, 2, 5, 6]}: four isolated — three minted ids whose owner edges
 * folded onto their canonical sides, and the stand-in nothing names — three stand-ins at one, four
 * entities at two, one work at exactly ADR 57's floor and one person above it.
 *
 * <p>That last pair is the fixture's whole reason for its two extra people: with no node above the
 * floor, "at or below" and "below" would give the same answer and the test would pass on the wrong
 * comparison.
 */
class DegreeCensusTest {

  private static final DegreeCensus CENSUS =
      DegreeCensus.of(
          LogProjection.of(new InventedCensus.FakeAssertionLog().with(InventedCensus.log())));

  @Test
  @DisplayName("the floor is the recommender's own constant, not a second copy of the number")
  void shouldReportTheRecommendersFloorWhenAReadingIsTaken() {
    assertThat(CENSUS.floor()).isEqualTo(Recommendations.MIN_CANDIDATE_DEGREE);
  }

  @Test
  @DisplayName("each quantile is a degree some node actually has")
  void shouldReportADegreeSomeNodeHasWhenTheQuantilesAreRead() {
    assertThat(CENSUS.p50()).isEqualTo(1);
    assertThat(CENSUS.p90()).isEqualTo(5);
    assertThat(CENSUS.p99()).isEqualTo(6);
    assertThat(CENSUS.max()).isEqualTo(6);
  }

  @Test
  @DisplayName("a node sitting exactly on the floor is counted at or below it")
  void shouldCountTheNodeOnTheFloorWhenTheFloorsBiteIsMeasured() {
    assertThat(CENSUS.atOrBelowTheFloor())
        .as("twelve of the thirteen; only the degree-6 node is above the floor of 5")
        .isEqualTo(12);
  }
}
