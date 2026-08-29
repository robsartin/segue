package com.robsartin.segue.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The tiers, and the rule that stops the table going stale (ADR 45).
 *
 * <p>Only the ORDER is asserted, plus the two numbers the ADR argues for by name. The exact ratios
 * are one significant figure and are not evidence of anything; a test that pinned them would turn a
 * judgement into a regression whenever it was revisited.
 */
class RecommendationWeightsTest {

  @Test
  @DisplayName("influence outranks collaboration outranks recognition")
  void influenceOutranksCollaborationOutranksRecognition() {
    double influence = RecommendationWeights.of(EdgeTypes.INFLUENCED_BY.code());
    double collaboration = RecommendationWeights.of(EdgeTypes.MEMBER_OF.code());
    double recognition = RecommendationWeights.of(EdgeTypes.RECEIVED_AWARD.code());

    assertThat(influence).isGreaterThan(collaboration);
    assertThat(collaboration).isGreaterThan(recognition);
  }

  @Test
  @DisplayName("an influence hop is worth its full weight; everything is measured against it")
  void anInfluenceHopIsTheUnit() {
    assertThat(RecommendationWeights.of(EdgeTypes.INFLUENCED_BY.code())).isEqualTo(1.0);
  }

  @Test
  @DisplayName("sharing a subject is weaker evidence than sharing a recognition (issue #111)")
  void recognitionOutranksAboutness() {
    // Two books about software engineering say much less about a connection than two people who
    // won the same award: an award is chosen by a body that compared candidates, a subject is
    // just a topic both authors happened to write about. ADR 38 put RECEIVED_AWARD at RECOGNITION
    // because single-authored work has no collaboration to find — the same reasoning admits
    // ABOUT, but does not lift it to the same tier as a fact about being chosen.
    double recognition = RecommendationWeights.of(EdgeTypes.RECEIVED_AWARD.code());
    double aboutness = RecommendationWeights.of(EdgeTypes.ABOUT.code());

    assertThat(recognition).isGreaterThan(aboutness);
    assertThat(aboutness).isGreaterThan(0.0);
  }

  @Test
  @DisplayName("working together is worth the same whichever kind of work it was")
  void everyCollaborationWeighsTheSame() {
    double member = RecommendationWeights.of(EdgeTypes.MEMBER_OF.code());

    assertThat(RecommendationWeights.of(EdgeTypes.PERFORMED.code())).isEqualTo(member);
    assertThat(RecommendationWeights.of(EdgeTypes.ACTED_IN.code())).isEqualTo(member);
    assertThat(RecommendationWeights.of(EdgeTypes.AUTHORED.code())).isEqualTo(member);
  }

  @Test
  @DisplayName("every type in the vocabulary is weighed deliberately, not by default")
  void everyRegisteredTypeIsNamed() {
    for (EdgeType type : EdgeTypes.all()) {
      assertThat(RecommendationWeights.isWeighed(type.code()))
          .describedAs(
              "%s is in the vocabulary and not in the weight table — decide what it is worth"
                  + " for a recommendation, in the ADR, not by inheriting the default",
              type.code())
          .isTrue();
    }
  }

  @Test
  @DisplayName("a type the vocabulary no longer registers still weighs something")
  void aRetiredTypeFallsBackRatherThanFailing() {
    assertThat(RecommendationWeights.of("RETIRED_LONG_AGO"))
        .isEqualTo(RecommendationWeights.COLLABORATION);
    assertThat(RecommendationWeights.isWeighed("RETIRED_LONG_AGO")).isFalse();
  }

  @Test
  @DisplayName("being cited is evidence; citing is a self-description, and worth less (issue #84)")
  void citingIsWorthLessThanBeingCited() {
    String influence = EdgeTypes.INFLUENCED_BY.code();

    double cited = RecommendationWeights.asEvidenceAbout(influence, false);
    double citing = RecommendationWeights.asEvidenceAbout(influence, true);

    assertThat(cited).isEqualTo(RecommendationWeights.of(influence));
    assertThat(citing).isLessThan(cited);
  }

  @Test
  @DisplayName(
      "a collaboration or a prize reads the same from either end: no esteem flows along it")
  void onlyEsteemDirectionalTypesReadDifferentlyFromEachEnd() {
    for (EdgeType type : EdgeTypes.all()) {
      if (RecommendationWeights.carriesEsteemDirection(type.code())) {
        continue;
      }
      assertThat(RecommendationWeights.asEvidenceAbout(type.code(), true))
          .describedAs(
              "%s is not esteem-directional, so which end states it must not change what it is"
                  + " worth",
              type.code())
          .isEqualTo(RecommendationWeights.asEvidenceAbout(type.code(), false));
    }
  }

  @Test
  @DisplayName("exactly two relations state a debt, and the rest are symmetric in regard")
  void theVocabularysDebtRelationsAreTheOnlyDirectionalOnes() {
    assertThat(EdgeTypes.all().stream().map(EdgeType::code))
        .filteredOn(RecommendationWeights::carriesEsteemDirection)
        .containsExactlyInAnyOrder(EdgeTypes.INFLUENCED_BY.code(), EdgeTypes.BASED_ON.code());
  }

  @Test
  @DisplayName("a type the vocabulary no longer registers is read as symmetric, not as a debt")
  void aRetiredTypeCarriesNoDirection() {
    assertThat(RecommendationWeights.carriesEsteemDirection("RETIRED_LONG_AGO")).isFalse();
    assertThat(RecommendationWeights.asEvidenceAbout("RETIRED_LONG_AGO", true))
        .isEqualTo(RecommendationWeights.of("RETIRED_LONG_AGO"));
  }
}
