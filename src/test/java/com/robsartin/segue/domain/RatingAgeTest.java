package com.robsartin.segue.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Which promotions are new, over invented ratings. Nothing here comes from anybody's taste layer
 * (ADR 33, issue #37) and every id carries ADR 58's leading zero.
 */
class RatingAgeTest {

  private static final Instant SINCE = Instant.parse("2026-09-06T15:00:00Z");
  private static final String OLD = "Q0900451";
  private static final String NEW = "Q0900452";
  private static final String EXACT = "Q0900453";

  @Test
  @DisplayName("a rating written before the instant is old and one written after it is new")
  void shouldSplitThePopulationWhenTheInstantFallsBetweenTwoRatings() {
    RatingAge age =
        RatingAge.of(
            SINCE,
            Map.of(
                OLD, Instant.parse("2026-09-05T23:59:59Z"),
                NEW, Instant.parse("2026-09-06T15:00:01Z")),
            Set.of(OLD, NEW));

    assertThat(age.isNew(NEW)).isTrue();
    assertThat(age.isNew(OLD)).isFalse();
    assertThat(age.newer()).containsExactly(NEW);
  }

  @Test
  @DisplayName("a rating written at the instant itself is new, because the half is on or after it")
  void shouldCountTheRatingAsNewWhenItsTimestampIsTheInstantItself() {
    RatingAge age = RatingAge.of(SINCE, Map.of(EXACT, SINCE), Set.of(EXACT));

    assertThat(age.isNew(EXACT)).isTrue();
  }

  @Test
  @DisplayName("a rated entity with no timestamp is refused, rather than counted as old")
  void shouldRefuseThePopulationWhenARatedEntityHasNoTimestamp() {
    // A lenient read feeding a guard turns "cannot tell" into "old", and the cell that results
    // looks exactly like a real one. The message names no qid and no count (ADR 33).
    assertThatThrownBy(() -> RatingAge.of(SINCE, Map.of(OLD, SINCE), Set.of(OLD, NEW)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no rating timestamp")
        .hasMessageNotContaining(NEW);
  }
}
