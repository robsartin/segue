package com.robsartin.segue.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The dial, and the reason it is a dial (ADR 45).
 *
 * <p>Every number below is invented. What is measured is the SHAPE the tests pin: on the real
 * graph, raw counting rediscovered fame and only dividing by the candidate's own degree turned it
 * into surprise. {@link #theDialIsTheDifferenceBetweenFameAndSurprise()} is that finding written as
 * two candidates whose order reverses across the spectrum.
 */
class ScorerTest {

  private static final String SEED = "Q900101";
  private static final String OTHER_SEED = "Q900102";

  /** One seed, one intermediate of degree {@code viaDegree}, weight 1. */
  private static SharedIntermediate via(String seed, String qid, int viaDegree) {
    return new SharedIntermediate(seed, qid, viaDegree, 1.0);
  }

  @Test
  @DisplayName("raw counts every connection equally, however busy the intermediate")
  void rawCountsEveryConnectionEqually() {
    List<SharedIntermediate> shared =
        List.of(via(SEED, "Q900201", 2), via(OTHER_SEED, "Q900202", 100));

    assertThat(Scorer.RAW.score(shared, 40)).isEqualTo(2.0);
  }

  @Test
  @DisplayName("Adamic-Adar discounts the busy intermediate by the log of its degree")
  void adamicAdarDiscountsTheBusyIntermediate() {
    List<SharedIntermediate> shared =
        List.of(via(SEED, "Q900201", 2), via(OTHER_SEED, "Q900202", 100));

    assertThat(Scorer.ADAMIC_ADAR.score(shared, 40))
        .isCloseTo(1 / Math.log(2) + 1 / Math.log(100), within(1e-9));
  }

  @Test
  @DisplayName("resource allocation discounts it by the degree itself, which is far harsher")
  void resourceAllocationDiscountsItHarder() {
    List<SharedIntermediate> shared =
        List.of(via(SEED, "Q900201", 2), via(OTHER_SEED, "Q900202", 100));

    assertThat(Scorer.RESOURCE_ALLOCATION.score(shared, 40))
        .isCloseTo(1.0 / 2 + 1.0 / 100, within(1e-9));
  }

  @Test
  @DisplayName("lift is Adamic-Adar over the candidate's own degree")
  void liftNormalisesByTheCandidatesOwnDegree() {
    List<SharedIntermediate> shared =
        List.of(via(SEED, "Q900201", 2), via(OTHER_SEED, "Q900202", 100));

    assertThat(Scorer.LIFT.score(shared, 40))
        .isCloseTo((1 / Math.log(2) + 1 / Math.log(100)) / 40, within(1e-9));
  }

  @Test
  @DisplayName("the weight on a connection multiplies it, so edge type reaches the score")
  void theWeightOnAConnectionMultipliesIt() {
    List<SharedIntermediate> weak = List.of(new SharedIntermediate(SEED, "Q900201", 2, 0.2));

    assertThat(Scorer.RAW.score(weak, 10)).isEqualTo(0.2);
  }

  @Test
  @DisplayName("nothing shared scores zero rather than failing")
  void nothingSharedScoresZero() {
    assertThat(Scorer.LIFT.score(List.of(), 10)).isEqualTo(0.0);
  }

  @Test
  @DisplayName("the dial is the difference between a fame ranking and a surprise ranking")
  void theDialIsTheDifferenceBetweenFameAndSurprise() {
    // Famous: touched by three of your things, and by three hundred edges in all.
    List<SharedIntermediate> famous =
        List.of(via(SEED, "Q900201", 30), via(OTHER_SEED, "Q900202", 30), via(SEED, "Q900203", 30));
    int famousDegree = 300;
    // Surprising: touched by two of your things out of a total of fifteen edges.
    List<SharedIntermediate> surprising =
        List.of(via(SEED, "Q900201", 30), via(OTHER_SEED, "Q900202", 30));
    int surprisingDegree = 15;

    assertThat(Scorer.RAW.score(famous, famousDegree))
        .isGreaterThan(Scorer.RAW.score(surprising, surprisingDegree));
    assertThat(Scorer.ADAMIC_ADAR.score(famous, famousDegree))
        .isGreaterThan(Scorer.ADAMIC_ADAR.score(surprising, surprisingDegree));
    assertThat(Scorer.LIFT.score(surprising, surprisingDegree))
        .isGreaterThan(Scorer.LIFT.score(famous, famousDegree));
  }

  @Test
  @DisplayName("a candidate with no edges at all cannot be normalised, and says so")
  void aCandidateWithNoEdgesIsRefused() {
    assertThatThrownBy(() -> Scorer.LIFT.score(List.of(via(SEED, "Q900201", 2)), 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("degree");
  }

  @Test
  @DisplayName("the accepted words are the four points on the spectrum")
  void theAcceptedWordsAreTheFourPoints() {
    assertThat(Scorer.names()).isEqualTo("raw|adamic-adar|resource-allocation|lift");
    assertThat(Scorer.parse("LIFT")).isEqualTo(Scorer.LIFT);
    assertThat(Scorer.parse("resource-allocation")).isEqualTo(Scorer.RESOURCE_ALLOCATION);
  }

  @Test
  @DisplayName("an unknown scorer is refused by name")
  void anUnknownScorerIsRefused() {
    assertThatThrownBy(() -> Scorer.parse("pagerank"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("pagerank")
        .hasMessageContaining("lift");
  }

  @Test
  @DisplayName("every point on the dial describes itself, for the report header")
  void everyPointDescribesItself() {
    for (Scorer scorer : Scorer.values()) {
      assertThat(scorer.describe()).isNotBlank();
    }
  }
}
