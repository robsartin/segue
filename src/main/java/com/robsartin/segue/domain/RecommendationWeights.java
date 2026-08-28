package com.robsartin.segue.domain;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What a hop of each edge type is worth to a <em>recommendation</em> (ADR 45).
 *
 * <p>Every type in {@link EdgeTypes} is legitimate for ROUTING — {@code find_paths} will happily
 * explain a pair through any of them, and ADR 38 admitted {@code RECEIVED_AWARD} precisely because
 * literature had no other relation at all. They are not equal for RECOMMENDING, and measured on the
 * real graph the edge type turned out to carry more of the signal than any further tuning of the
 * degree arithmetic:
 *
 * <pre>
 *   The Beatles  -INFLUENCED_BY-  Little Richard      -INFLUENCED_BY-  Sister Rosetta Tharpe
 *   Rage Against the Machine -RECEIVED_AWARD- Rock and Roll Hall of Fame -RECEIVED_AWARD- ...
 * </pre>
 *
 * <p>The first says something about the music. The second says both were recognised.
 *
 * <p><b>Three tiers, and the ordering is what the measurement supports.</b>
 *
 * <ul>
 *   <li><b>{@link #INFLUENCE}</b> — {@code INFLUENCED_BY} is the only relation in the vocabulary
 *       that states an artistic debt rather than an employment or a prize, and it is the one
 *       relation stated ABOUT the pair. It is also where the degree arithmetic has the most work to
 *       do: measured over the first hop out of 815 known entities, an influence intermediate has a
 *       median degree of 51 against 1 to 5 for every other type, because the thing artists cite is
 *       a famous artist.
 *   <li><b>{@link #COLLABORATION}</b> — {@code MEMBER_OF}, {@code PERFORMED}, {@code ACTED_IN} and
 *       the rest say two entities worked on the same thing. Real evidence, and the bulk of the
 *       graph. Halving it is also what dissolved the co-membership artefact: with every type equal,
 *       the top of the ranking was a band member reached through 28 separate songs by one group —
 *       one fact about that group, counted 28 times. At half a unit per hop, and so a quarter per
 *       two-hop route, he leaves the top twenty entirely.
 *   <li><b>{@link #RECOGNITION}</b> — {@code RECEIVED_AWARD} says both were recognised by the same
 *       body, which is a fact about institutions rather than about either entity. It is not zero:
 *       the specific awards are exactly what ADR 38 built for the literature side of the graph,
 *       where a novel has one author and there is no collaboration to find. It is a fifth, because
 *       "we both won this" is the weakest reason to listen to somebody in the vocabulary.
 * </ul>
 *
 * <p><b>And one dimension that is not a tier at all: which way the relation points (issue #84).</b>
 * A tier says what a relation is worth; the direction says whether this hop is a claim about the
 * candidate or a claim by it. Measured on the real graph, undirected scoring put a small band that
 * lists ten famous influences at rank 1, above every ancestor those influences actually have —
 * because to a walk that ignores arrows, citing and being cited are the same edge, and the small
 * band divides by a smaller degree. See {@link #asEvidenceAbout} and {@link #SELF_STATED}.
 *
 * <p><b>The weight is not the hub rule and does not replace it.</b> Hub intermediates are EXCLUDED
 * before any weight applies ({@link PathRanking#isHub}): a route through the Rock and Roll Hall of
 * Fame is not down-weighted, it is not a route. Both mechanisms have work left after the other has
 * run — measured, hub exclusion removes 38% of the award hops out of the known-list, and the
 * surviving 62% are specific awards which this weighs.
 *
 * <p><b>The numbers are one significant figure, and deliberately so.</b> What is measured is the
 * ORDER; 1.0, 0.5 and 0.2 are the coarsest numbers that express it. Anything more precise would be
 * a tuning claim this project has no way to evaluate — there is no held-out set of recommendations
 * a person has agreed with.
 *
 * <p>It lives in {@code domain} beside {@link EdgeTypes} because the code it keys on is this
 * vocabulary's own, unlike {@code RecognitionInstitutions}, which keys on Wikidata classes and
 * therefore belongs to the adapter that knows them (ADR 42).
 */
public final class RecommendationWeights {

  /** An artistic debt: this artist cites that one. The unit everything else is measured against. */
  public static final double INFLUENCE = 1.0;

  /** Two entities worked on the same thing. Real, plentiful, and worth half an influence. */
  public static final double COLLABORATION = 0.5;

  /** Both were recognised by the same body. A fact about institutions; worth a fifth. */
  public static final double RECOGNITION = 0.2;

  /**
   * What an esteem-directional hop is worth when the entity being judged is the one <em>making</em>
   * the claim rather than the one it is made about. A multiplier on the tier above (issue #84).
   *
   * <p>A fifth, the same figure {@link #RECOGNITION} carries and for a related reason: what is left
   * after the direction is taken away is a fact about somebody else's paperwork. "This band says it
   * was influenced by yours" is a real, cited fact, and it is a fact the band wrote about itself.
   * It is not zero, because "who came from the things you like" is a segue too — it is just the one
   * that says least about whether to go and listen.
   */
  public static final double SELF_STATED = 0.2;

  /**
   * One row of the table: what a hop of this type is worth, and whether its direction means
   * anything.
   *
   * <p>Two dimensions in one row, because neither is derivable from the other and both have to be
   * decided when a relation joins the vocabulary. {@code BASED_ON} and {@code MEMBER_OF} are both
   * collaborations and only one of them states a debt; {@code INFLUENCED_BY} and {@code BASED_ON}
   * are both debts and sit in different tiers.
   *
   * @param weight the tier — what the relation is worth at all
   * @param esteemDirectional whether the subject of the claim is deferring to its object. True only
   *     where the relation states a DEBT; false where the direction is Wikidata's convention for
   *     which end is the person and which is the work, or the prize, or the band
   */
  private record Weighing(double weight, boolean esteemDirectional) {}

  /** A relation that states a debt: the subject is deferring to the object. */
  private static final boolean A_DEBT = true;

  /** A relation whose direction says which end is which kind, and nothing about regard. */
  private static final boolean NO_ESTEEM = false;

  private static final Map<String, Weighing> BY_CODE = new LinkedHashMap<>();

  static {
    // The one relation in the vocabulary that is both an artistic debt and stated between two
    // entities either of which could be a recommendation. The whole of issue #84 lands here.
    put(EdgeTypes.INFLUENCED_BY, INFLUENCE, A_DEBT);

    // Which end is the person and which is the group is a fact about kinds, not about regard: a
    // band does not defer to its drummer, nor a drummer to the band.
    put(EdgeTypes.MEMBER_OF, COLLABORATION, NO_ESTEEM);
    put(EdgeTypes.HAS_PART, COLLABORATION, NO_ESTEEM);
    // Every one of these is inverted at ingest so it reads person-to-work (ADR 22). The direction
    // is that convention and nothing else; two people credited on one film are symmetric.
    put(EdgeTypes.PERFORMED, COLLABORATION, NO_ESTEEM);
    put(EdgeTypes.AUTHORED, COLLABORATION, NO_ESTEEM);
    put(EdgeTypes.DIRECTED, COLLABORATION, NO_ESTEEM);
    put(EdgeTypes.WROTE_SCREENPLAY_FOR, COLLABORATION, NO_ESTEEM);
    put(EdgeTypes.COMPOSED_FOR, COLLABORATION, NO_ESTEEM);
    put(EdgeTypes.ACTED_IN, COLLABORATION, NO_ESTEEM);
    // A debt, and stated the same way round as an influence: the later work defers to the earlier
    // one. It is marked as one for the same reason INFLUENCED_BY is, and it currently changes
    // nothing — a WORK is never a candidate (ADR 45 ranks people and groups), so this stance is
    // waiting for the day one is, rather than being an unstated exception nobody would notice.
    put(EdgeTypes.BASED_ON, COLLABORATION, A_DEBT);
    // Containment. A song is not deferring to the album it is on.
    put(EdgeTypes.PART_OF, COLLABORATION, NO_ESTEEM);
    // Neither is in any graph yet, and both are collaborations by their own definitions - one
    // derived from co-credits, one asserted by a similarity source. A hypothesis is handled where
    // hypotheses belong: PathRanking sorts a route resting on a model guess below every sourced
    // one (ADR 23), which is the receipts' problem rather than the arithmetic's. Both are declared
    // SYMMETRIC by the vocabulary itself, so a direction of esteem could not be read off them.
    put(EdgeTypes.COLLABORATED_WITH, COLLABORATION, NO_ESTEEM);
    put(EdgeTypes.SIMILAR_TO, COLLABORATION, NO_ESTEEM);

    // The direction here separates a person from a prize, which the hub rule has already dealt
    // with. Nobody is flattered by being an award.
    put(EdgeTypes.RECEIVED_AWARD, RECOGNITION, NO_ESTEEM);
  }

  private RecommendationWeights() {}

  private static void put(EdgeType type, double weight, boolean esteemDirectional) {
    Weighing prior = BY_CODE.put(type.code(), new Weighing(weight, esteemDirectional));
    if (prior != null) {
      throw new IllegalStateException("two weights claim " + type.code());
    }
  }

  /**
   * What one hop of this type is worth.
   *
   * <p>A code this table does not name falls back to {@link #COLLABORATION} rather than failing.
   * That path is for a type the vocabulary once registered and no longer does — the log keeps every
   * claim ever made, so an old code can still arrive from a real graph, and refusing to score it
   * would take the whole run down. It is deliberately NOT the growth path: {@code
   * RecommendationWeightsTest.everyRegisteredTypeIsNamed} fails the build if a registered type is
   * missing here, so a new relation costs a decision rather than inheriting one.
   */
  public static double of(String typeCode) {
    Weighing weighing = BY_CODE.get(typeCode);
    return weighing == null ? COLLABORATION : weighing.weight();
  }

  /**
   * What one hop of this type is worth as evidence <em>about</em> the entity at one end of it.
   *
   * <p><b>The direction is a separate dimension from the tier, and it is the whole of issue
   * #84.</b> Undirected, a small band that lists twelve famous influences and an ancestor twelve
   * famous bands cite are the same shape — both "share intermediates with things you like" — and
   * the small band wins, because lift divides by a smaller degree. They are not the same claim.
   * Being cited by something is a fact somebody else stated about you; citing something is a fact
   * you stated about yourself, and an entity whose entire presence in the graph is its own
   * influence list has said nothing anybody can check.
   *
   * <p><b>Ask this only about the entity being recommended.</b> The hop between one of your own
   * entities and the intermediate is not a claim about either of them that matters here — it is
   * what makes the intermediate shared, and both readings are real segues: "who the things I like
   * came from" and "who came from them" (ADR 45). Applying the discount at that end too would
   * demote exactly the ancestors this exists to keep, because the entities that cite your list are
   * the same entities that cite its ancestors.
   *
   * @param typeCode the relation
   * @param statedByIt whether the entity being judged is the SUBJECT of the claim — the one doing
   *     the citing — rather than its object
   */
  public static double asEvidenceAbout(String typeCode, boolean statedByIt) {
    double weight = of(typeCode);
    return statedByIt && carriesEsteemDirection(typeCode) ? weight * SELF_STATED : weight;
  }

  /**
   * Whether this relation's direction states a debt, so that the two ends of it mean different
   * things.
   *
   * <p>False for a code this table does not name, which is the same conservative fallback {@link
   * #of} makes and for the same reason: a retired code arriving from the log scores as it always
   * did rather than being demoted by a rule written after it.
   */
  public static boolean carriesEsteemDirection(String typeCode) {
    Weighing weighing = BY_CODE.get(typeCode);
    return weighing != null && weighing.esteemDirectional();
  }

  /** Whether this table names the type, as opposed to falling back for it. */
  public static boolean isWeighed(String typeCode) {
    return BY_CODE.containsKey(typeCode);
  }
}
