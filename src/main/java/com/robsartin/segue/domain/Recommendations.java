package com.robsartin.segue.domain;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
   * experiment that produced this design put a degree-2 node first. That experiment's upper bound
   * still holds too: at a floor of 50 the list drifts back towards the famous names the
   * normalisation exists to escape.
   *
   * <p><b>Five, and no longer the twelve ADR 45 shipped, because in-graph degree is partly a
   * measure of what segue has FETCHED.</b> An entity at this degree is commonly thinly connected
   * here rather than obscure in the world, so the higher floor excluded recognisable things for a
   * reason that was about ingest history rather than about them. The move was measured before it
   * was decided, and the cost of it is real — a lower floor makes the ranking depend more on what
   * has been expanded. ADR 45's amendment of 2026-08-29 (issues #117, #118) is the authority on the
   * figures, the cost, and the alternatives that were measured and lost; nothing here restates
   * them.
   *
   * <p><b>It is a default on this graph, not a natural constant</b>, which is why {@code
   * --min-degree} exists: a domain whose entities are thinner needs a lower one, and the honest way
   * to choose is to run two floors and read the two lists.
   */
  public static final int MIN_CANDIDATE_DEGREE = 5;

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
   * <p><b>It is a function and not a store, and that is the point of it.</b> The recommender is
   * handed regard rather than a way of looking taste up, so what it can see of the taste layer is
   * decided by whoever builds the function — today {@link #regardFor(Map)}, from the note-free bulk
   * read (issue #85). This constant is what that function degenerates to when nothing has been
   * rated, and it is still what the tests that are not about affinity use.
   */
  public static final ToDoubleFunction<String> EQUAL_REGARD = qid -> 1.0;

  /**
   * The rating that counts for exactly one: the middle of ADR 39's 1-5 scale.
   *
   * <p><b>The scale is centred rather than absolute, and that is the decision in this constant.</b>
   * A weighting of {@code rating} itself would make an unrated entity — which is most of the
   * known-list, because that list came from ADR 40's file and not from the taste layer — either
   * count for one against a rated entity's five, or need an arbitrary default. Dividing by the
   * middle makes a 3 neutral, a 5 worth {@code 5/3} and a 1 worth {@code 1/3}, so rating something
   * moves it relative to everything else rather than moving everything unrated to the bottom.
   */
  public static final int NEUTRAL_RATING = 3;

  private Recommendations() {}

  /**
   * How much each known entity counts for, given what the owner has said about some of them.
   *
   * <p><b>This is the seam being wired, and issue #85 is the argument that let it be.</b> ADR 33
   * treated the whole taste layer as personal data, so the recommender could not see a rating at
   * all; the boundary now runs between the score and the note, and the score is of a piece with the
   * known-list this tool already reads. A candidate reached by three things rated 5 outranks one
   * reached by six rated 2, which is the behaviour ADR 45 named and could not build.
   *
   * <p><b>Ratings, never notes.</b> The parameter is a {@code Map<String, Integer>} rather than
   * anything richer for exactly that reason: there is nowhere in it for free text to sit, so the
   * domain cannot hold a note however this is called. See {@code AffinityStore.readRatings}.
   *
   * <p><b>An empty map returns equal regard, entity for entity.</b> Not a special case for its own
   * sake: it means an empty {@code affinity} table produces the ranking ADR 45 measured, so wiring
   * this in changes nothing until somebody rates something.
   *
   * @param ratings qid to a rating from 1 to 5, for the entities that have one. Copied, so a later
   *     write to the caller's map cannot change a run halfway through
   */
  public static ToDoubleFunction<String> regardFor(Map<String, Integer> ratings) {
    Map<String, Integer> settled = Map.copyOf(Objects.requireNonNull(ratings, "ratings"));
    if (settled.isEmpty()) {
      return EQUAL_REGARD;
    }
    return qid -> settled.getOrDefault(qid, NEUTRAL_RATING) / (double) NEUTRAL_RATING;
  }

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
