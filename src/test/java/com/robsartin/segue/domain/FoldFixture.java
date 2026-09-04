package com.robsartin.segue.domain;

import java.time.Instant;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * The one log both {@code EquivalencesTest} and {@code FoldTest} fold, and the invented ids it is
 * built from.
 *
 * <p><b>One home, because two copies of a fixture is two fixtures.</b> {@code FoldTest} held a
 * verbatim duplicate of this log and of the ids it names, and a fixture the two files can edit
 * separately is one they can disagree about - which is precisely what {@code FoldTest} exists to
 * rule out, since its whole assertion is that {@link Fold} answers what the log-taking rules answer
 * <em>on this log</em>. Nothing here decides anything: it is data plus the operator the two files
 * fold it under.
 */
final class FoldFixture {

  private FoldFixture() {}

  static final String MINTED = "Q00900042";
  static final String OTHER_MINTED = "Q00900043";

  /**
   * The two shapes these files need, and they are not the same one. A merge's canonical side takes
   * ADR 62's eleven digits, which {@code SameAs} admits there and nowhere else; {@link #NEIGHBOUR}
   * is only the far end of an edge, so it is an ordinary stand-in and takes ADR 58's single leading
   * zero. Each is the id {@code EquivalencesTest} used before issue #171 carried into its shape.
   */
  static final String CANONICAL = "Q10000000900";

  static final String OTHER_CANONICAL = "Q10000000901";

  /**
   * A minted id for {@link #foldedLog()}'s surviving-superseded merge (fix round 1, #238 task 2
   * findings): merged once, corrected onto a different canonical id, and never retracted. Two
   * leading zeros (ADR 59).
   */
  static final String SUPERSEDED_MINTED = "Q00900045";

  /**
   * The surviving-superseded merge's first, later-corrected canonical id. ADR 62's eleven digits,
   * as above.
   */
  static final String SUPERSEDED_FIRST_CANONICAL = "Q10000000903";

  /**
   * The surviving-superseded merge's final canonical id - the one {@link #SUPERSEDED_MINTED}
   * resolves to today. ADR 62's eleven digits, as above.
   */
  static final String SUPERSEDED_SECOND_CANONICAL = "Q10000000904";

  static final String NEIGHBOUR = "Q0902";

  static final Instant WHEN = Instant.parse("2026-08-31T09:00:00Z");

  /**
   * Kinds as the claim stated them. Every fixture in the two files that fold this log states no
   * classes, so {@code KindMapper.rederive} would be the identity on all of them (ADR 42) - naming
   * it here says the choice was made rather than defaulted, and keeps {@code wikidata} out of a
   * {@code domain} test.
   *
   * <p><b>It is honest only while that stays true.</b> A fixture added there that DOES state
   * classes would go un-re-derived under this operator with nothing in either file to say so, and
   * would then assert the claimed kind as though it were the answer both folds give. Nothing here
   * can catch that - identity is a legitimate answer for a caller to hand in - so the guard is
   * elsewhere: {@code StandInAgreesInEveryHomeTest} feeds a class-bearing claim through the real
   * {@code KindMapper.rederive} in every home the rule has.
   */
  static final UnaryOperator<NodeAssertion> AS_CLAIMED = UnaryOperator.identity();

  static AssertionRecord edge(String from, String to) {
    return new AssertionRecord(
        from, to, "INFLUENCED_BY", null, null, new Provenance("invented", "invented:1", WHEN, 1.0));
  }

  /**
   * A log the fixed point actually runs on: a minted local side, a merge onto it, a retraction of
   * that local side (which empties the canonical id), a re-merge onto the same canonical id, and an
   * edge naming the local id - which folds onto the emptied canonical id and is withdrawn (#224,
   * #228). An overload handed the wrong emptied set answers differently here, which is what makes
   * the comparisons in {@code EquivalencesTest} able to fail.
   *
   * <p>A second, untouched merge - {@link #OTHER_MINTED} onto {@link #OTHER_CANONICAL}, never
   * retracted - is there so {@code standIns(log, AS_CLAIMED)} actually builds a stand-in: the
   * retraction above reaches {@link #MINTED}'s own node claim as well as its merge (retraction is
   * per-entity, not per-claim), so without this second pair the log-taking form answers an empty
   * map and every comparison that uses it would be vacuous.
   *
   * <p>A third addition - {@link #SUPERSEDED_MINTED} merged onto {@link
   * #SUPERSEDED_FIRST_CANONICAL} and then, later and without any retraction, re-merged onto {@link
   * #SUPERSEDED_SECOND_CANONICAL} - is there so the {@code standIns(list, rederive, merges)} pin
   * actually discriminates on its {@code merges} argument (fix round 1, #238 task 2 findings).
   * Under the real {@code Equivalences.in(log)}, {@link Equivalences#last} answers false for the
   * first merge (its local id now resolves to the second canonical id) and no kept edge names
   * {@link #SUPERSEDED_FIRST_CANONICAL}, so {@link Equivalences#stands} answers false and it gets
   * no stand-in. Handed {@link Equivalences#NONE} instead - which has never heard of {@link
   * #SUPERSEDED_MINTED} - {@link Equivalences#last} answers true unconditionally and a stand-in is
   * built for {@link #SUPERSEDED_FIRST_CANONICAL} that should not exist, which is exactly the
   * difference the pin is supposed to catch. Neither of the first two additions can show this: the
   * only merge either one reaches inside {@code standIns} is a local id {@link Equivalences#NONE}
   * has also never heard of, so {@link Equivalences#last} answers true either way and the wrong
   * argument is never felt.
   *
   * <p>It also carries exactly two stand-ins, which is what lets {@code
   * FoldTest.shouldKeepLogOrderWhenTheFoldCarriesTheStandIns} mean anything: a one-entry map has
   * only one order.
   */
  static List<LoggedAssertion> foldedLog() {
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
