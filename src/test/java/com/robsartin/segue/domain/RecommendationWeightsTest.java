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
}
