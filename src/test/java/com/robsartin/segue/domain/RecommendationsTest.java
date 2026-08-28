package com.robsartin.segue.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The ordering, the bound, and the seam that is present and unused (ADR 45). */
class RecommendationsTest {

  private static Recommendation candidate(String qid, double score) {
    return new Recommendation(
        new NodeRecord(qid, NodeKind.GROUP, "invented " + qid),
        score,
        20,
        List.of(new SharedIntermediate("Q900101", "Q900201", 4, 1.0)));
  }

  @Test
  @DisplayName("the best-scoring candidate comes first")
  void bestScoringFirst() {
    List<Recommendation> ranked =
        Recommendations.rank(
            List.of(
                candidate("Q900301", 0.2), candidate("Q900302", 0.9), candidate("Q900303", 0.5)),
            10);

    assertThat(ranked)
        .extracting(r -> r.entity().qid())
        .containsExactly("Q900302", "Q900303", "Q900301");
  }

  @Test
  @DisplayName("a tie is broken by qid, so two runs over one graph produce the same file")
  void tiesAreBrokenByQid() {
    List<Recommendation> ranked =
        Recommendations.rank(List.of(candidate("Q900399", 0.5), candidate("Q900301", 0.5)), 10);

    assertThat(ranked).extracting(r -> r.entity().qid()).containsExactly("Q900301", "Q900399");
  }

  @Test
  @DisplayName("the list is bounded by what was asked for")
  void theListIsBounded() {
    List<Recommendation> ranked =
        Recommendations.rank(
            List.of(
                candidate("Q900301", 0.9), candidate("Q900302", 0.8), candidate("Q900303", 0.7)),
            2);

    assertThat(ranked).hasSize(2);
  }

  @Test
  @DisplayName(
      "the floor is a real bound, and it is above the degree that let a thin node top the"
          + " ranking")
  void theFloorIsAboveTheThinNodeThatToppedTheRanking() {
    assertThat(Recommendations.MIN_CANDIDATE_DEGREE).isGreaterThan(2);
  }

  @Test
  @DisplayName("regard for every known entity is equal until something rates them")
  void everyKnownEntityIsRegardedEqually() {
    assertThat(Recommendations.EQUAL_REGARD.applyAsDouble("Q900101")).isEqualTo(1.0);
    assertThat(Recommendations.EQUAL_REGARD.applyAsDouble("Q900102")).isEqualTo(1.0);
  }
}
