package com.robsartin.segue.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** What a recommendation can say about itself before anybody draws a route (ADR 45). */
class RecommendationTest {

  private static final NodeRecord CANDIDATE =
      new NodeRecord("Q0900301", NodeKind.GROUP, "invented band");

  @Test
  @DisplayName("the count of things you know is distinct entities, not distinct routes")
  void knownReachedCountsEntitiesNotRoutes() {
    // One known entity sharing four intermediates with a candidate is one reason to listen to
    // it, not four - which is exactly the co-membership shape a band's whole discography makes.
    Recommendation reachedOnce =
        new Recommendation(
            CANDIDATE,
            1.0,
            30,
            List.of(
                new SharedIntermediate("Q0900101", "Q0900201", 4, 1.0),
                new SharedIntermediate("Q0900101", "Q0900202", 4, 1.0),
                new SharedIntermediate("Q0900101", "Q0900203", 4, 1.0)));

    assertThat(reachedOnce.knownReached()).isEqualTo(1);
    assertThat(reachedOnce.intermediates()).isEqualTo(3);
  }

  @Test
  @DisplayName("two of your entities through one intermediate is two, through one")
  void twoKnownEntitiesThroughOneIntermediate() {
    Recommendation shared =
        new Recommendation(
            CANDIDATE,
            1.0,
            30,
            List.of(
                new SharedIntermediate("Q0900101", "Q0900201", 4, 1.0),
                new SharedIntermediate("Q0900102", "Q0900201", 4, 1.0)));

    assertThat(shared.knownReached()).isEqualTo(2);
    assertThat(shared.intermediates()).isEqualTo(1);
  }
}
