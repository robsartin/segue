package com.robsartin.segue.evaluate;

import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.domain.NodeRecord;
import com.robsartin.segue.domain.Recommendation;
import com.robsartin.segue.domain.Scorer;
import com.robsartin.segue.recommend.Sweep;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The metrics, over a hand-built pool. No graph, no store, no traversal: {@code Scoring} is a pure
 * function of a {@code Sweep}, and this is what says so. Every id, label and score is invented.
 */
class ScoringTest {

  private static final Setting SETTING = new Setting(Scorer.LIFT, 5);

  @Test
  @DisplayName("a held-out entity in the top N is a hit, and its rank is 1-based")
  void shouldReportTheHitAndItsRankWhenAHeldOutEntityIsRankedHighly() {
    Sweep sweep = pool(List.of("Q0900401", "Q0900402", "Q0900403", "Q0900404"));

    Reading reading = Scoring.read(sweep, SETTING, Set.of("Q0900402"), Set.of(), 4);

    assertThat(reading.pool()).isEqualTo(4);
    assertThat(reading.heldOutInPool()).isEqualTo(1);
    assertThat(reading.hits()).isEqualTo(1);
    assertThat(reading.meanHitRank()).hasValue(2.0);
  }

  @Test
  @DisplayName("a held-out entity below the cut is in the pool and is not a hit")
  void shouldCountItInThePoolAndNotAsAHitWhenAHeldOutEntityFallsOutsideTheTop() {
    Sweep sweep = pool(List.of("Q0900401", "Q0900402", "Q0900403", "Q0900404"));

    Reading reading = Scoring.read(sweep, SETTING, Set.of("Q0900404"), Set.of(), 2);

    assertThat(reading.heldOutInPool()).isEqualTo(1);
    assertThat(reading.hits()).isZero();
    assertThat(reading.meanHitRank())
        .as("a mean over nothing is absent, not zero — zero is a rank")
        .isEmpty();
  }

  @Test
  @DisplayName("the mean rank of two hits is their arithmetic mean")
  void shouldAverageTheRanksWhenMoreThanOneHeldOutEntityIsAHit() {
    Sweep sweep = pool(List.of("Q0900401", "Q0900402", "Q0900403", "Q0900404"));

    Reading reading = Scoring.read(sweep, SETTING, Set.of("Q0900401", "Q0900404"), Set.of(), 4);

    assertThat(reading.hits()).isEqualTo(2);
    assertThat(reading.meanHitRank()).hasValue(2.5);
  }

  @Test
  @DisplayName("a rated-down entity in the top N is reported with its rank")
  void shouldReportTheNegativeAndItsRankWhenTheRankingWouldHaveOfferedIt() {
    Sweep sweep = pool(List.of("Q0900401", "Q0900402", "Q0900403", "Q0900404"));

    Reading reading = Scoring.read(sweep, SETTING, Set.of(), Set.of("Q0900403"), 4);

    assertThat(reading.negativesOffered()).isEqualTo(1);
    assertThat(reading.meanNegativeRank()).hasValue(3.0);
  }

  @Test
  @DisplayName("the pool is the sweep with the rated-down entities removed, not the whole sweep")
  void shouldReportThePoolWithNegativesRemovedWhenTheSweepIncludesARatedDownEntity() {
    Sweep sweep = pool(List.of("Q0900401", "Q0900402", "Q0900403", "Q0900404"));

    Reading reading = Scoring.read(sweep, SETTING, Set.of(), Set.of("Q0900403"), 4);

    assertThat(reading.pool()).isEqualTo(3);
  }

  /** Descending scores, so the qid order below is the ranked order. */
  private static Sweep pool(List<String> qids) {
    List<Recommendation> candidates = new ArrayList<>();
    double score = qids.size();
    for (String qid : qids) {
      candidates.add(
          new Recommendation(
              new NodeRecord(qid, NodeKind.GROUP, "an invented act " + qid, List.of()),
              score--,
              12,
              List.of()));
    }
    return new Sweep(candidates, qids.size(), 0, 0, 0, 0);
  }
}
