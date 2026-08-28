package com.robsartin.segue.domain;

import java.util.Comparator;
import java.util.List;
import java.util.function.ToDoubleFunction;

/**
 * ADR 45's ordering rule, and the two policy decisions that sit beside it. The sibling of {@link
 * PathRanking}: a static policy that shapes a result somebody else fetched, holding no state and
 * touching no graph.
 */
public final class Recommendations {

  /**
   * The number of edges below which a candidate is not ranked at all.
   *
   * <p><b>A floor is not optional under a normalised score, and that was measured.</b> Dividing by
   * the candidate's own degree is what turns fame into surprise, and it does so by rewarding a
   * small denominator — so without a floor the top of the ranking is whatever is smallest, and the
   * experiment that produced this design put a degree-2 node first. Twelve is the value that
   * experiment settled on, and re-measuring it on the 123,752-node graph agreed: at a floor of 50
   * the list drifts back towards the famous names the normalisation exists to escape, and below
   * about twelve it fills with entities whose entire presence in the graph is a list of influences.
   *
   * <p>Twelve is not a natural constant. It is a default on a personal-scale music-heavy graph,
   * which is why {@code --min-degree} exists: a domain whose entities are thinner needs a lower
   * one, and the honest way to choose is to run both and read the two lists.
   */
  public static final int MIN_CANDIDATE_DEGREE = 12;

  /**
   * How much weight one known entity's connections carry. <b>The affinity seam, deliberately
   * flat.</b>
   *
   * <p>ADR 33's payoff is "traverse the world graph and filter through affinity", and the second
   * half of that is not built here: the {@code affinity} table is empty, so every known entity
   * counts for one. What this constant does is put the shape of the eventual answer in the code —
   * {@code CandidateSweep} multiplies every connection by this function of the seed's qid, so a
   * candidate reached by three things rated 5 outranking one reached by six rated 2 is a matter of
   * supplying a different function, not of changing the arithmetic.
   *
   * <p><b>It is a function and not a store, and that is the point of it.</b> Reading the taste
   * layer here would mean the bulk read ADR 39 refused and ADR 43 reserved to one dev tool, and
   * {@code ArchitectureTest.theRecommenderNeverReadsTheTasteLayer} makes the recommender's
   * inability to do so a build failure. When a real weighting arrives it will be a deliberate
   * change to that rule and to ADR 39, argued rather than inherited — which is exactly what a seam
   * is for.
   */
  public static final ToDoubleFunction<String> EQUAL_REGARD = qid -> 1.0;

  private Recommendations() {}

  /**
   * Best first, then bounded.
   *
   * <p>The tiebreak is the qid rather than the label, so that two runs over an unchanged graph
   * produce byte-identical output and two runs a month apart can be diffed — the same argument ADR
   * 43 makes for the ratings tool's comparators, and it matters more here, because a scored list is
   * exactly the kind of output whose changes are the interesting part.
   */
  public static List<Recommendation> rank(List<Recommendation> candidates, int limit) {
    return candidates.stream()
        .sorted(
            Comparator.comparingDouble(Recommendation::score)
                .reversed()
                .thenComparing(recommendation -> recommendation.entity().qid()))
        .limit(limit)
        .toList();
  }
}
