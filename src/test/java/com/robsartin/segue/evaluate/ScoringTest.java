package com.robsartin.segue.evaluate;

import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.domain.NodeRecord;
import com.robsartin.segue.domain.Recommendation;
import com.robsartin.segue.domain.Scorer;
import com.robsartin.segue.recommend.Sweep;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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

    Reading reading =
        Scoring.read(sweep, SETTING, Set.of("Q0900402"), Set.of(), 4, Optional.empty());

    assertThat(reading.pool()).isEqualTo(4);
    assertThat(reading.heldOutInPool()).isEqualTo(1);
    assertThat(reading.hits()).isEqualTo(1);
    assertThat(reading.hitRankSum()).as("one hit, at rank 2").isEqualTo(2);
  }

  @Test
  @DisplayName("a held-out entity below the cut is in the pool and is not a hit")
  void shouldCountItInThePoolAndNotAsAHitWhenAHeldOutEntityFallsOutsideTheTop() {
    Sweep sweep = pool(List.of("Q0900401", "Q0900402", "Q0900403", "Q0900404"));

    Reading reading =
        Scoring.read(sweep, SETTING, Set.of("Q0900404"), Set.of(), 2, Optional.empty());

    assertThat(reading.heldOutInPool()).isEqualTo(1);
    assertThat(reading.hits()).isZero();
    assertThat(reading.hitRankSum())
        .as("a sum over nothing is zero, and it is the hit count beside it that prints the dash")
        .isZero();
  }

  @Test
  @DisplayName("the mean rank of two hits is their arithmetic mean")
  void shouldAverageTheRanksWhenMoreThanOneHeldOutEntityIsAHit() {
    Sweep sweep = pool(List.of("Q0900401", "Q0900402", "Q0900403", "Q0900404"));

    Reading reading =
        Scoring.read(sweep, SETTING, Set.of("Q0900401", "Q0900404"), Set.of(), 4, Optional.empty());

    assertThat(reading.hits()).isEqualTo(2);
    assertThat(reading.hitRankSum())
        .as("ranks 1 and 4, which the report means to 2.5")
        .isEqualTo(5);
  }

  @Test
  @DisplayName("a rated-down entity in the top N is reported with its rank")
  void shouldReportTheNegativeAndItsRankWhenTheRankingWouldHaveOfferedIt() {
    Sweep sweep = pool(List.of("Q0900401", "Q0900402", "Q0900403", "Q0900404"));

    Reading reading =
        Scoring.read(sweep, SETTING, Set.of(), Set.of("Q0900403"), 4, Optional.empty());

    assertThat(reading.negativesOffered()).isEqualTo(1);
    assertThat(reading.negativeRankSum()).isEqualTo(3);
  }

  @Test
  @DisplayName("the pool is the sweep with the rated-down entities removed, not the whole sweep")
  void shouldReportThePoolWithNegativesRemovedWhenTheSweepIncludesARatedDownEntity() {
    Sweep sweep = pool(List.of("Q0900401", "Q0900402", "Q0900403", "Q0900404"));

    Reading reading =
        Scoring.read(sweep, SETTING, Set.of(), Set.of("Q0900403"), 4, Optional.empty());

    assertThat(reading.pool()).isEqualTo(3);
  }

  @Test
  @DisplayName("the hits and the pool split into halves when the run is given an age")
  void shouldCountEachHalfSeparatelyWhenTheRunIsSplitByRatingAge() {
    Sweep sweep = pool(List.of("Q0900401", "Q0900402", "Q0900403", "Q0900404"));
    RatingAge age =
        new RatingAge(Instant.parse("2026-09-06T15:00:00Z"), Set.of("Q0900402", "Q0900404"));

    Reading reading =
        Scoring.read(
            sweep,
            SETTING,
            Set.of("Q0900401", "Q0900402", "Q0900404"),
            Set.of(),
            2,
            Optional.of(age));

    // Ranks 1 and 2 are inside the top 2; rank 4 is in the pool and is not a hit.
    assertThat(reading.halves()).isEqualTo(new Halves(true, 1, 1, 2, 1));
    assertThat(reading.halves().oldInPool() + reading.halves().newInPool())
        .isEqualTo(reading.heldOutInPool());
    assertThat(reading.halves().oldHits() + reading.halves().newHits()).isEqualTo(reading.hits());
  }

  @Test
  @DisplayName("a run with no age carries no halves at all, not four zeroes it could render")
  void shouldCarryNoHalvesWhenTheRunIsNotSplitByRatingAge() {
    Sweep sweep = pool(List.of("Q0900401", "Q0900402"));

    Reading reading =
        Scoring.read(sweep, SETTING, Set.of("Q0900401"), Set.of(), 2, Optional.empty());

    assertThat(reading.halves()).isEqualTo(Halves.UNSPLIT);
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
