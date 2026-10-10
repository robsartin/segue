package com.robsartin.segue.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The blend, on invented signals only: a real play count is the owner's (ADR 33, issue #37). */
class InterestTest {

  private static final Instant AT = Instant.parse("2026-05-01T09:00:00Z");

  private static Signal plays(long count) {
    return new Signal("Q0900001", "invented-source", count, false, false, AT);
  }

  private static Signal loved() {
    return new Signal("Q0900001", "invented-source", 0, true, false, AT);
  }

  private static Signal favorited() {
    return new Signal("Q0900001", "invented-source", 0, false, true, AT);
  }

  @Test
  @DisplayName("a rating beats any signal, for every rating and every signal")
  void shouldLetARatingBeatAnySignal() {
    List<Signal> everySignal =
        List.of(plays(0), plays(Interest.LIGHT_PLAYS), plays(1_000_000), loved(), favorited());

    IntStream.rangeClosed(1, 5)
        .forEach(
            rating ->
                assertThat(Interest.of(rating, everySignal))
                    .as("rating %d", rating)
                    .isEqualTo(Interest.rated(rating)));
  }

  @Test
  @DisplayName("a rating of 1 or 2 is never raised by a signal")
  void shouldNeverRaiseALowRating() {
    for (int rating : new int[] {1, 2}) {
      assertThat(Interest.of(rating, List.of(loved(), plays(1_000_000))).confidence())
          .isEqualTo(Interest.rated(rating).confidence())
          .isLessThan(Interest.NEUTRAL);
    }
  }

  @Test
  @DisplayName("signals raise an unrated entity, and only that")
  void shouldOnlyRaiseAnUnratedEntity() {
    assertThat(Interest.of(null, List.of())).isEqualTo(Interest.none());
    assertThat(Interest.of(null, List.of(plays(Interest.LIGHT_PLAYS - 1))))
        .isEqualTo(Interest.none());
    assertThat(Interest.of(null, List.of(plays(Interest.LIGHT_PLAYS))).confidence())
        .isEqualTo(Interest.LIGHT);
    assertThat(Interest.of(null, List.of(plays(Interest.HEAVY_PLAYS))).confidence())
        .isEqualTo(Interest.HEAVY);
    assertThat(Interest.of(null, List.of(loved())).confidence()).isEqualTo(Interest.LOVED);
    assertThat(Interest.of(null, List.of(favorited())).confidence()).isEqualTo(Interest.LOVED);
    assertThat(Interest.of(null, List.of(loved())).evidence()).isEqualTo(Evidence.INFERRED);
  }

  @Test
  @DisplayName("an inferred confidence is always below a rating of 4")
  void shouldKeepInferenceBelowAFour() {
    assertThat(Interest.LOVED).isLessThan(Interest.rated(4).confidence());
    assertThat(new Interest(Interest.LOVED, Evidence.INFERRED).regard())
        .isLessThan(Interest.rated(4).regard());
  }

  @Test
  @DisplayName("several signals take the strongest bucket and do not add")
  void shouldTakeTheStrongestSignal() {
    assertThat(Interest.of(null, List.of(plays(Interest.LIGHT_PLAYS), plays(Interest.LIGHT_PLAYS))))
        .isEqualTo(Interest.of(null, List.of(plays(Interest.LIGHT_PLAYS))));
    assertThat(Interest.of(null, List.of(plays(Interest.HEAVY_PLAYS), loved())).confidence())
        .isEqualTo(Interest.LOVED);
  }

  @Test
  @DisplayName("the same inputs give the same output, in either order")
  void shouldBeDeterministic() {
    List<Signal> forwards = List.of(plays(7), loved(), plays(30));

    assertThat(Interest.of(null, forwards))
        .isEqualTo(Interest.of(null, List.of(plays(30), loved(), plays(7))))
        .isEqualTo(Interest.of(null, forwards));
  }

  @Test
  @DisplayName("regard is exactly rating over three for a rated entity, and 1.0 for neutral")
  void shouldReproduceTheRatingWeights() {
    IntStream.rangeClosed(1, 5)
        .forEach(
            rating ->
                assertThat(Interest.rated(rating).regard())
                    .as("rating %d", rating)
                    .isEqualTo(rating / (double) Recommendations.NEUTRAL_RATING));
    assertThat(Interest.none().regard()).isEqualTo(1.0);
  }

  @Test
  @DisplayName("a confidence outside 0 to 1 is refused")
  void shouldRefuseAnOutOfRangeConfidence() {
    assertThatThrownBy(() -> new Interest(1.5, Evidence.RATED))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("byQid blends ratings and signals, resolves merged ids and omits the unknown")
  void shouldBlendByEntity() {
    Map<String, Interest> byQid =
        Interests.byQid(
            Map.of("Q0900001", 5),
            List.of(
                new Signal("Q0900001", "s", 1_000, false, false, AT),
                new Signal("Q00900042", "s", 30, false, false, AT),
                new Signal("Q0900003", "s", 1, false, false, AT)),
            qid -> qid.equals("Q00900042") ? "Q10000900042" : qid);

    assertThat(byQid)
        .containsOnlyKeys("Q0900001", "Q10000900042")
        .containsEntry("Q0900001", Interest.rated(5))
        .containsEntry("Q10000900042", new Interest(Interest.HEAVY, Evidence.INFERRED));
  }

  @Test
  @DisplayName("regardForInterest matches regardFor on ratings alone, and 1.0 for the unknown")
  void shouldMatchRatingOnlyWeightingWhenThereAreNoSignals() {
    Map<String, Integer> ratings = Map.of("Q0900101", 5, "Q0900102", 3, "Q0900103", 1);
    Map<String, Interest> interests = Interests.byQid(ratings, List.of(), qid -> qid);

    var blended = Recommendations.regardForInterest(interests);
    var rated = Recommendations.regardFor(ratings);

    for (String qid : List.of("Q0900101", "Q0900102", "Q0900103", "Q0900199")) {
      assertThat(blended.applyAsDouble(qid)).as(qid).isEqualTo(rated.applyAsDouble(qid));
    }
    assertThat(Recommendations.regardForInterest(Map.of())).isSameAs(Recommendations.EQUAL_REGARD);
  }
}
