package com.robsartin.segue.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The ordering, the bound, and the seam that is present and unused (ADR 45). */
class RecommendationsTest {

  private static Recommendation candidate(String qid, double score) {
    return new Recommendation(
        new NodeRecord(qid, NodeKind.GROUP, "invented " + qid),
        score,
        20,
        List.of(new SharedIntermediate("Q900101", "Q0900201", 4, 1.0)));
  }

  @Test
  @DisplayName("the best-scoring candidate comes first")
  void bestScoringFirst() {
    List<Recommendation> ranked =
        Recommendations.rank(
            List.of(
                candidate("Q0900301", 0.2), candidate("Q0900302", 0.9), candidate("Q0900303", 0.5)),
            10);

    assertThat(ranked)
        .extracting(r -> r.entity().qid())
        .containsExactly("Q0900302", "Q0900303", "Q0900301");
  }

  @Test
  @DisplayName("a tie is broken by qid, so two runs over one graph produce the same file")
  void tiesAreBrokenByQid() {
    List<Recommendation> ranked =
        Recommendations.rank(List.of(candidate("Q0900399", 0.5), candidate("Q0900301", 0.5)), 10);

    assertThat(ranked).extracting(r -> r.entity().qid()).containsExactly("Q0900301", "Q0900399");
  }

  @Test
  @DisplayName("the list is bounded by what was asked for")
  void theListIsBounded() {
    List<Recommendation> ranked =
        Recommendations.rank(
            List.of(
                candidate("Q0900301", 0.9), candidate("Q0900302", 0.8), candidate("Q0900303", 0.7)),
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

  @Test
  @DisplayName("an empty taste layer weighs exactly as equal regard does, entity for entity")
  void anEmptyTasteLayerIsEqualRegard() {
    // The property that makes this safe to wire in unconditionally: 815 known entities and zero
    // ratings must produce the ranking ADR 45 measured, not a differently-scaled one.
    java.util.function.ToDoubleFunction<String> regard = Recommendations.regardFor(Map.of());

    assertThat(regard.applyAsDouble("Q900101")).isEqualTo(1.0);
    assertThat(regard.applyAsDouble("Q900102")).isEqualTo(1.0);
  }

  @Test
  @DisplayName("an unrated entity counts as the middle of the scale, not as nothing")
  void anUnratedEntityCountsAsTheMiddle() {
    // "Not said" is not "not for me" (ADR 39 chose absence over a default), and a known-list
    // entity is on the list because the owner likes it — counting it as zero would delete most
    // of the graph the moment the first rating was written.
    java.util.function.ToDoubleFunction<String> regard =
        Recommendations.regardFor(Map.of("Q900101", 5));

    assertThat(regard.applyAsDouble("Q900199")).isEqualTo(1.0);
  }

  @Test
  @DisplayName("the scale runs either side of one: a 5 counts up, a 1 counts down")
  void theScaleRunsEitherSideOfOne() {
    java.util.function.ToDoubleFunction<String> regard =
        Recommendations.regardFor(Map.of("Q900101", 5, "Q900102", 3, "Q900103", 1));

    assertThat(regard.applyAsDouble("Q900101")).isEqualTo(5.0 / 3.0);
    assertThat(regard.applyAsDouble("Q900102")).isEqualTo(1.0);
    assertThat(regard.applyAsDouble("Q900103")).isEqualTo(1.0 / 3.0);
  }

  @Test
  @DisplayName("three things rated 5 outweigh six things rated 2 — issue #85's own example")
  void threeFivesOutweighSixTwos() {
    java.util.function.ToDoubleFunction<String> regard =
        Recommendations.regardFor(Map.of("Q900101", 5, "Q900102", 2));

    double threeFives = 3 * regard.applyAsDouble("Q900101");
    double sixTwos = 6 * regard.applyAsDouble("Q900102");

    assertThat(threeFives).isGreaterThan(sixTwos);
  }
}
