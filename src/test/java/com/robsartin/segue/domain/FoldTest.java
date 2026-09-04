package com.robsartin.segue.domain;

import static com.robsartin.segue.domain.FoldFixture.AS_CLAIMED;
import static com.robsartin.segue.domain.FoldFixture.CANONICAL;
import static com.robsartin.segue.domain.FoldFixture.MINTED;
import static com.robsartin.segue.domain.FoldFixture.WHEN;
import static com.robsartin.segue.domain.FoldFixture.foldedLog;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Issue #238: {@link Fold} answers exactly what {@link Equivalences} and {@link Retractions}
 * already answer separately, computed once. This file does not re-argue any fold rule - that
 * belongs to {@code EquivalencesTest} and {@code RetractionsTest} - it only pins that {@link
 * Fold#of} wires the four log-taking rules together correctly.
 *
 * <p>The log it folds is {@code FoldFixture.foldedLog()}, the one {@code EquivalencesTest} folds
 * too: this file used to hold a verbatim copy of it, and a copy is a fixture the two files can edit
 * apart, which would leave the equivalence below asserted on two different logs.
 */
class FoldTest {

  @Test
  @DisplayName("one Fold answers exactly what the four log-taking rules answer separately")
  void shouldAnswerWhatEveryLogTakingRuleAnswersWhenOneFoldIsBuilt() {
    List<LoggedAssertion> log = foldedLog();

    Fold fold = Fold.of(log, AS_CLAIMED);

    assertThat(fold.equivalences()).isEqualTo(Equivalences.folding(log));
    assertThat(fold.standIns()).isEqualTo(Equivalences.standIns(log, AS_CLAIMED));
    assertThat(fold.nodesHeld()).isEqualTo(Equivalences.nodesTheFoldHolds(log));
    assertThat(fold.retractions()).isEqualTo(Retractions.in(log));
  }

  @Test
  @DisplayName("the stand-ins a Fold carries are in the log order the rule answers in")
  void shouldKeepLogOrderWhenTheFoldCarriesTheStandIns() {
    List<LoggedAssertion> log = foldedLog();

    Fold fold = Fold.of(log, AS_CLAIMED);

    assertThat(fold.standIns())
        .as("or the order below is one element and cannot differ")
        .hasSize(2);
    assertThat(fold.standIns().keySet())
        .as(
            "Equivalences.standIns answers in log order and GraphProjector upserts the stand-ins"
                + " in the order this key set iterates in, so a Fold that re-orders them upserts"
                + " a canonical node in a different order on every boot")
        .containsExactlyElementsOf(Equivalences.standIns(log, AS_CLAIMED).keySet());
  }

  @Test
  @DisplayName("the stand-in a Fold carries takes the kind the caller's re-derivation gives")
  void shouldCarryTheRederivedKindWhenTheFoldIsBuiltWithARederivation() {
    List<LoggedAssertion> log = claimedLocalSideLog();

    assertThat(Fold.of(log, claim -> claim.withKind(NodeKind.PERSON)).standIns().get(CANONICAL))
        .as(
            "the operator is required for localsOfMerges' reason (#222); if it were ignored a"
                + " third fold would arrive with the kind lag and nothing would say so")
        .isNotEqualTo(Fold.of(log, AS_CLAIMED).standIns().get(CANONICAL));
  }

  /**
   * A {@link NodeAssertion} on a local-shaped id plus a merge, as {@code
   * EquivalencesTest.shouldNameTheSameCanonicalIdsWhateverKindTheFoldDerives} builds it. A {@link
   * NodeAssertion}, not a {@link LocalEntity}: {@code Equivalences.localsOfMerges} applies the
   * re-derivation to a source's node claim alone - {@code LocalEntity.toNode()} carries the owner's
   * stated kind, and no operator touches it - so a minted local side would make the two Folds below
   * agree by construction and the control in {@link
   * #shouldCarryTheRederivedKindWhenTheFoldIsBuiltWithARederivation} could never fire.
   */
  private static List<LoggedAssertion> claimedLocalSideLog() {
    return List.of(
        new NodeAssertion(
            MINTED,
            NodeKind.WORK,
            "a local-shaped id a source named",
            new Provenance("invented", "invented:1", WHEN, 1.0)),
        SameAs.declared(MINTED, CANONICAL, WHEN));
  }
}
