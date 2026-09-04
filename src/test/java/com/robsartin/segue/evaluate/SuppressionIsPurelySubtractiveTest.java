package com.robsartin.segue.evaluate;

import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.segue.domain.Recommendations;
import com.robsartin.segue.domain.Scorer;
import com.robsartin.segue.recommend.CandidateSweep;
import com.robsartin.segue.recommend.Sweep;
import com.robsartin.segue.tinker.TinkerGraphStore;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The licence for running <b>one</b> sweep per setting instead of two (ADR 65).
 *
 * <p>The harness withholds suppression so the rated-down entities can be ranked, then filters them
 * out to read the held-out ones. That is only the shipped ranking if excluding a candidate from the
 * pool is purely subtractive — no surviving candidate's score or relative order moves. ADR 50
 * measured exactly that on the real graph; this holds it here, against a real second sweep, so the
 * claim is a test rather than a paragraph.
 *
 * <p>Every id, label and edge is invented.
 */
class SuppressionIsPurelySubtractiveTest {

  private static final Setting SETTING = new Setting(Scorer.LIFT, 5);
  private static final int TOP = 25;

  @Test
  @DisplayName(
      "filtering the rated-down out after the sweep ranks the rest exactly as suppressing them before it")
  void shouldRankTheSurvivorsIdenticallyWhenSuppressionIsAppliedAfterTheSweepRatherThanBefore() {
    try (TinkerGraphStore graph = InventedEvaluation.graph()) {
      CandidateSweep sweep = new CandidateSweep(graph, qid -> false);
      List<String> known = List.of(InventedEvaluation.KNOWN_ONE, InventedEvaluation.KNOWN_TWO);
      Set<String> rejected = Set.of(InventedEvaluation.REJECTED);

      Sweep withheld =
          sweep.over(
              known, Set.of(), SETTING.scorer(), SETTING.floor(), Recommendations.EQUAL_REGARD);
      Sweep suppressed =
          sweep.over(
              known, rejected, SETTING.scorer(), SETTING.floor(), Recommendations.EQUAL_REGARD);

      assertThat(qidsOf(withheld, rejected))
          .as("the pool the harness filters, against the pool the recommender would have ranked")
          .isEqualTo(
              Recommendations.rank(suppressed.candidates(), TOP).stream()
                  .map(candidate -> candidate.entity().qid())
                  .toList());
    }
  }

  @Test
  @DisplayName(
      "the held-out entity's rank is read over the shipped pool, not the pool with the rated-down in it")
  void shouldRankTheHeldOutEntityOverTheShippedPoolWhenARatedDownEntityOutranksIt() {
    try (TinkerGraphStore graph = InventedEvaluation.graph()) {
      Sweep withheld =
          new CandidateSweep(graph, qid -> false)
              .over(
                  List.of(InventedEvaluation.KNOWN_ONE, InventedEvaluation.KNOWN_TWO),
                  Set.of(),
                  SETTING.scorer(),
                  SETTING.floor(),
                  Recommendations.EQUAL_REGARD);

      Reading reading =
          Scoring.read(
              withheld,
              SETTING,
              Set.of(InventedEvaluation.HIDDEN),
              Set.of(InventedEvaluation.REJECTED),
              TOP);

      assertThat(reading.pool())
          .as("the pool the report states is the one the recommender would have ranked")
          .isEqualTo(2);
      assertThat(reading.meanHitRank())
          .as("rank 2 of the two survivors, not rank 3 of the three the sweep returned")
          .hasValue(2.0);
      assertThat(reading.meanNegativeRank())
          .as("the rated-down entity is still read over the whole pool — that is its whole point")
          .hasValue(1.0);
    }
  }

  private static List<String> qidsOf(Sweep sweep, Set<String> removed) {
    return Recommendations.rank(
            sweep.candidates().stream()
                .filter(candidate -> !removed.contains(candidate.entity().qid()))
                .toList(),
            TOP)
        .stream()
        .map(candidate -> candidate.entity().qid())
        .toList();
  }
}
