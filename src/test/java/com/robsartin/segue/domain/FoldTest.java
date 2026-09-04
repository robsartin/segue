package com.robsartin.segue.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Issue #238: {@link Fold} answers exactly what {@link Equivalences} and {@link Retractions}
 * already answer separately, computed once. This file does not re-argue any fold rule - that
 * belongs to {@code EquivalencesTest} and {@code RetractionsTest} - it only pins that {@link
 * Fold#of} wires the four log-taking rules together correctly.
 */
class FoldTest {

  private static final String MINTED = "Q00900042";
  private static final String OTHER_MINTED = "Q00900043";
  private static final String CANONICAL = "Q10000000900";
  private static final String OTHER_CANONICAL = "Q10000000901";
  private static final String NEIGHBOUR = "Q0902";
  private static final Instant WHEN = Instant.parse("2026-08-31T09:00:00Z");

  /**
   * A fourth minted id, for {@link #foldedLog()}'s surviving-superseded merge: merged once,
   * corrected onto a different canonical id, and never retracted. Two leading zeros (ADR 59), the
   * next free id after {@link #OTHER_MINTED} in this file's own numbering.
   */
  private static final String SUPERSEDED_MINTED = "Q00900045";

  /**
   * The surviving-superseded merge's first, later-corrected canonical id. ADR 62's eleven digits.
   */
  private static final String SUPERSEDED_FIRST_CANONICAL = "Q10000000903";

  /**
   * The surviving-superseded merge's final canonical id - the one {@link #SUPERSEDED_MINTED}
   * resolves to today. ADR 62's eleven digits.
   */
  private static final String SUPERSEDED_SECOND_CANONICAL = "Q10000000904";

  /**
   * Kinds as the claim stated them - {@code EquivalencesTest}'s own {@code AS_CLAIMED}, mirrored
   * here for the same reason: every fixture in this file states no classes, so {@code
   * KindMapper.rederive} would be the identity on all of them (ADR 42), and naming that here says
   * the choice was made rather than defaulted.
   */
  private static final UnaryOperator<NodeAssertion> AS_CLAIMED = UnaryOperator.identity();

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
  @DisplayName("the stand-in a Fold carries takes the kind the caller's re-derivation gives")
  void shouldCarryTheRederivedKindWhenTheFoldIsBuiltWithARederivation() {
    List<LoggedAssertion> log = claimedLocalSideLog();

    assertThat(Fold.of(log, claim -> claim.withKind(NodeKind.PERSON)).standIns().get(CANONICAL))
        .as(
            "the operator is required for localsOfMerges' reason (#222); if it were ignored a"
                + " third fold would arrive with the kind lag and nothing would say so")
        .isNotEqualTo(Fold.of(log, AS_CLAIMED).standIns().get(CANONICAL));
  }

  private static AssertionRecord edge(String from, String to) {
    return new AssertionRecord(
        from, to, "INFLUENCED_BY", null, null, new Provenance("invented", "invented:1", WHEN, 1.0));
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

  /**
   * Mirrors {@code EquivalencesTest.foldedLog()} exactly, as of that file's fix-round-1 fixture
   * (task 2 findings) rather than this task's brief's older sketch, which the brief itself asks
   * for: a minted local side, a merge onto it, a retraction of that local side (which empties the
   * canonical id), a re-merge onto the same canonical id, and an edge naming the local id - which
   * folds onto the emptied canonical id and is withdrawn (#224, #228). A second, untouched merge -
   * {@code OTHER_MINTED} onto {@code OTHER_CANONICAL} - is there so {@code standIns} actually
   * builds a stand-in; without it the log-taking form answers an empty map and {@code
   * shouldAnswerWhatEveryLogTakingRuleAnswersWhenOneFoldIsBuilt}'s {@code standIns()} comparison
   * would be vacuous. A third addition - {@code SUPERSEDED_MINTED} merged onto {@code
   * SUPERSEDED_FIRST_CANONICAL} and then, later and without any retraction, re-merged onto {@code
   * SUPERSEDED_SECOND_CANONICAL} - exercises {@code Equivalences.standIns(List, UnaryOperator,
   * Equivalences)}'s own merges argument the way {@code Fold.of} hands it in, for the reason that
   * overload's own fix-round finding gives.
   */
  private static List<LoggedAssertion> foldedLog() {
    return List.of(
        new NodeAssertion(
            NEIGHBOUR,
            NodeKind.PERSON,
            "an invented neighbour",
            new Provenance("invented", "invented:1", WHEN, 1.0)),
        LocalEntity.minted(MINTED, NodeKind.WORK, "an invented local work", WHEN),
        SameAs.declared(MINTED, CANONICAL, WHEN),
        new Retraction(MINTED, "the local side was wrong", WHEN),
        SameAs.declared(MINTED, CANONICAL, WHEN),
        LocalEntity.minted(OTHER_MINTED, NodeKind.WORK, "an invented other local work", WHEN),
        SameAs.declared(OTHER_MINTED, OTHER_CANONICAL, WHEN),
        edge(NEIGHBOUR, MINTED),
        LocalEntity.minted(SUPERSEDED_MINTED, NodeKind.WORK, "a corrected local work", WHEN),
        SameAs.declared(SUPERSEDED_MINTED, SUPERSEDED_FIRST_CANONICAL, WHEN),
        SameAs.declared(SUPERSEDED_MINTED, SUPERSEDED_SECOND_CANONICAL, WHEN));
  }
}
