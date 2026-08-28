package com.robsartin.segue.domain;

import java.util.Objects;

/**
 * One thing you already know, one thing that connects it to a candidate, and what that connection
 * is worth. The unit {@link Scorer} adds up, and the unit a recommendation is explained by.
 *
 * <p>It is deliberately not a {@link PathResult}. A route is an explanation, hop by hop, with a
 * citation on every edge; this is the arithmetic behind one. Keeping them separate is what lets the
 * scoring stay a pure function of four numbers while the receipts come from the real traversal and
 * the shared {@link PathRanking} — one notion of a good route in the project, not two (ADR 45).
 *
 * @param seedQid the entity you already know, from the supplied known-list
 * @param viaQid the intermediate the two have in common. Never a hub: {@link PathRanking#isHub} has
 *     already excluded busy concepts and bodies one is elected to, so a shared Walk of Fame star or
 *     a shared academy never reaches this record at all (issues #52 and #66)
 * @param viaDegree how many edges that intermediate carries in the graph. <b>At least two</b>, and
 *     that is a fact about what an intermediate IS rather than a defensive check: a node joining
 *     two entities has an edge to each. {@link Scorer} divides by its logarithm and relies on it
 * @param weight what this connection is worth, before any discounting — the edge types of the two
 *     hops (see {@code RecommendationWeights}), multiplied by the regard held for the seed. Above
 *     zero, because a connection worth nothing is not a connection and would only widen the
 *     denominator of nothing
 */
public record SharedIntermediate(String seedQid, String viaQid, int viaDegree, double weight) {

  public SharedIntermediate {
    Objects.requireNonNull(seedQid, "seedQid");
    Objects.requireNonNull(viaQid, "viaQid");
    if (viaDegree < 2) {
      throw new IllegalArgumentException(
          "an intermediate joins two entities, so it carries at least two edges, got: "
              + viaDegree);
    }
    if (!(weight > 0)) {
      throw new IllegalArgumentException("weight must be above zero, got: " + weight);
    }
  }
}
