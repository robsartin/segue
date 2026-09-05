package com.robsartin.segue.census;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import com.robsartin.segue.domain.LoggedAssertion;
import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.domain.Recommendations;
import com.robsartin.segue.export.LogProjection;
import java.util.List;
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

  @Test
  @DisplayName("each kind's distribution is read over that kind's own nodes")
  void shouldReadEachKindsOwnDistributionWhenTheKindsDifferFromTheWhole() {
    assertThat(CENSUS.byKind())
        .as(
            "PERSON is [2, 2, 6] and WORK is [0, 0, 0, 0, 1, 1, 1, 5]; the whole graph's p50 of 1 is"
                + " a degree neither kind's median has")
        .containsExactly(
            entry(NodeKind.PERSON, new DegreeCensus.KindDegrees(2, 6, 6, 6, 2, 67)),
            entry(NodeKind.GROUP, new DegreeCensus.KindDegrees(2, 2, 2, 2, 1, 100)),
            entry(NodeKind.WORK, new DegreeCensus.KindDegrees(0, 5, 5, 5, 8, 100)),
            entry(NodeKind.PLACE, new DegreeCensus.KindDegrees(0, 0, 0, 0, 0, 0)),
            entry(NodeKind.EVENT, new DegreeCensus.KindDegrees(0, 0, 0, 0, 0, 0)),
            entry(NodeKind.CONCEPT, new DegreeCensus.KindDegrees(2, 2, 2, 2, 1, 100)));
  }

  @Test
  @DisplayName("the floor's bite is reported as a whole percent of the population it is applied to")
  void shouldReportTheFloorsBiteAsAWholePercentWhenAPopulationIsRead() {
    assertThat(CENSUS.atOrBelowTheFloorPercent())
        .as("twelve of thirteen is 92.3%, and a whole percent of it is 92")
        .isEqualTo(92);
    assertThat(CENSUS.byKind().get(NodeKind.PERSON).atOrBelowTheFloorPercent())
        .as("two of three is 66.7%, and an exact-two-thirds share rounds up rather than truncating")
        .isEqualTo(67);
    assertThat(CENSUS.byKind().get(NodeKind.PLACE).atOrBelowTheFloorPercent())
        .as("an empty population reads zero rather than dividing by nothing")
        .isEqualTo(0);
  }

  @Test
  @DisplayName("the kinds partition the graph, so their counts sum to the whole graph's")
  void shouldSumEachKindsCountToTheWholeGraphsWhenBothAreRead() {
    int summed =
        CENSUS.byKind().values().stream()
            .mapToInt(DegreeCensus.KindDegrees::atOrBelowTheFloor)
            .sum();

    assertThat(summed)
        .as("every node has exactly one kind, so no node may be counted twice or dropped")
        .isEqualTo(CENSUS.atOrBelowTheFloor());
  }

  /**
   * Ten nodes: five isolated at degree 0, and a five-node cycle (C1-C2-C3-C4-C5-C1, one edge each
   * side) putting the other five at degree 2. Sorted, {@code [0, 0, 0, 0, 0, 2, 2, 2, 2, 2]}.
   *
   * <p>{@code p50 * size = 5}, an exact integer — the one case where ADR 55's nearest-rank ({@code
   * sorted.get(ceil(p * size) - 1)}, index 4, the last zero) and the naive {@code
   * sorted.get(min(size - 1, floor(p * size)))} (index 5, the first two) disagree. Every fraction
   * in {@code DegreeCensusTest}'s thirteen-node fixture lands on a non-integer position, which is
   * why that fixture alone cannot tell the two rules apart.
   */
  private static final List<LoggedAssertion> TEN_NODE_LOG =
      List.of(
          InventedCensus.node("Q0900503", NodeKind.WORK, "An Isolated Work, One of Five"),
          InventedCensus.node("Q0900504", NodeKind.WORK, "An Isolated Work, Two of Five"),
          InventedCensus.node("Q0900505", NodeKind.WORK, "An Isolated Work, Three of Five"),
          InventedCensus.node("Q0900506", NodeKind.WORK, "An Isolated Work, Four of Five"),
          InventedCensus.node("Q0900507", NodeKind.WORK, "An Isolated Work, Five of Five"),
          InventedCensus.node("Q0900513", NodeKind.PERSON, "A Cycle Member, One of Five"),
          InventedCensus.node("Q0900514", NodeKind.PERSON, "A Cycle Member, Two of Five"),
          InventedCensus.node("Q0900515", NodeKind.PERSON, "A Cycle Member, Three of Five"),
          InventedCensus.node("Q0900516", NodeKind.PERSON, "A Cycle Member, Four of Five"),
          InventedCensus.node("Q0900517", NodeKind.PERSON, "A Cycle Member, Five of Five"),
          InventedCensus.edge("Q0900513", "Q0900514", "MEMBER_OF", InventedCensus.sourced()),
          InventedCensus.edge("Q0900514", "Q0900515", "MEMBER_OF", InventedCensus.sourced()),
          InventedCensus.edge("Q0900515", "Q0900516", "MEMBER_OF", InventedCensus.sourced()),
          InventedCensus.edge("Q0900516", "Q0900517", "MEMBER_OF", InventedCensus.sourced()),
          InventedCensus.edge("Q0900517", "Q0900513", "MEMBER_OF", InventedCensus.sourced()));

  @Test
  @DisplayName(
      "the nearest-rank quantile follows ADR 55 when the sample size makes p times n an integer")
  void shouldFollowAdr55SNearestRankWhenSampleSizeMakesPTimesNAnInteger() {
    DegreeCensus census =
        DegreeCensus.of(LogProjection.of(new InventedCensus.FakeAssertionLog().with(TEN_NODE_LOG)));
    assertThat(census.p50())
        .as("ADR 55's ceil(0.5 * 10) - 1 = index 4, the last of the five zeros")
        .isEqualTo(0);
  }

  @Test
  @DisplayName("every figure reads as zero when the projection is empty")
  void shouldReadEveryFigureAsZeroWhenTheProjectionIsEmpty() {
    DegreeCensus census = DegreeCensus.of(LogProjection.of(new InventedCensus.FakeAssertionLog()));
    assertThat(census.p50()).isEqualTo(0);
    assertThat(census.p90()).isEqualTo(0);
    assertThat(census.p99()).isEqualTo(0);
    assertThat(census.max()).isEqualTo(0);
    assertThat(census.atOrBelowTheFloor()).isEqualTo(0);
    assertThat(census.byKind())
        .as("all six kinds are present at zero, never absent — NodeCensus's rule")
        .containsExactly(
            entry(NodeKind.PERSON, new DegreeCensus.KindDegrees(0, 0, 0, 0, 0, 0)),
            entry(NodeKind.GROUP, new DegreeCensus.KindDegrees(0, 0, 0, 0, 0, 0)),
            entry(NodeKind.WORK, new DegreeCensus.KindDegrees(0, 0, 0, 0, 0, 0)),
            entry(NodeKind.PLACE, new DegreeCensus.KindDegrees(0, 0, 0, 0, 0, 0)),
            entry(NodeKind.EVENT, new DegreeCensus.KindDegrees(0, 0, 0, 0, 0, 0)),
            entry(NodeKind.CONCEPT, new DegreeCensus.KindDegrees(0, 0, 0, 0, 0, 0)));
  }
}
