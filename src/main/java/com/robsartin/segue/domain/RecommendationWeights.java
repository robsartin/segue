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

  private static final Map<String, Double> BY_CODE = new LinkedHashMap<>();

  static {
    put(EdgeTypes.INFLUENCED_BY, INFLUENCE);

    put(EdgeTypes.MEMBER_OF, COLLABORATION);
    put(EdgeTypes.HAS_PART, COLLABORATION);
    put(EdgeTypes.PERFORMED, COLLABORATION);
    put(EdgeTypes.AUTHORED, COLLABORATION);
    put(EdgeTypes.DIRECTED, COLLABORATION);
    put(EdgeTypes.WROTE_SCREENPLAY_FOR, COLLABORATION);
    put(EdgeTypes.COMPOSED_FOR, COLLABORATION);
    put(EdgeTypes.ACTED_IN, COLLABORATION);
    put(EdgeTypes.BASED_ON, COLLABORATION);
    put(EdgeTypes.PART_OF, COLLABORATION);
    // Neither is in any graph yet, and both are collaborations by their own definitions - one
    // derived from co-credits, one asserted by a similarity source. A hypothesis is handled where
    // hypotheses belong: PathRanking sorts a route resting on a model guess below every sourced
    // one (ADR 23), which is the receipts' problem rather than the arithmetic's.
    put(EdgeTypes.COLLABORATED_WITH, COLLABORATION);
    put(EdgeTypes.SIMILAR_TO, COLLABORATION);

    put(EdgeTypes.RECEIVED_AWARD, RECOGNITION);
  }

  private RecommendationWeights() {}

  private static void put(EdgeType type, double weight) {
    Double prior = BY_CODE.put(type.code(), weight);
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
    return BY_CODE.getOrDefault(typeCode, COLLABORATION);
  }

  /** Whether this table names the type, as opposed to falling back for it. */
  public static boolean isWeighed(String typeCode) {
    return BY_CODE.containsKey(typeCode);
  }
}
