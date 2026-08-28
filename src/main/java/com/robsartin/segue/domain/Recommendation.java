package com.robsartin.segue.domain;

import java.util.List;
import java.util.Objects;

/**
 * Something you do not already know, the score that put it in front of you, and the evidence behind
 * that score (ADR 45).
 *
 * <p>It carries the arithmetic, not the receipts. The routes are fetched afterwards, from the real
 * traversal, for the few candidates a person is actually going to read — building a {@code
 * find_paths} explanation for all eleven hundred candidates would cost thousands of traversals to
 * throw away, and the evidence here is enough to rank and to say who reached it.
 *
 * @param entity the candidate itself: a {@code PERSON} or {@code GROUP} the known-list does not
 *     name
 * @param score what {@link Scorer} made of {@code shared}. Comparable within one run and
 *     meaningless between two: the scale depends on the scorer, the weights and the size of the
 *     known-list
 * @param degree how many edges the candidate carries in the graph. Kept beside the score because it
 *     is the number that makes a lift score readable — 0.66 over 80 edges and 0.66 over 12 are very
 *     different claims
 * @param shared every route from a known entity to this candidate through one non-hub intermediate
 */
public record Recommendation(
    NodeRecord entity, double score, int degree, List<SharedIntermediate> shared) {

  public Recommendation {
    Objects.requireNonNull(entity, "entity");
    shared = List.copyOf(Objects.requireNonNull(shared, "shared"));
  }

  /**
   * How many of the things you already know reach this candidate.
   *
   * <p>Distinct entities, not routes, and the distinction is the one the weights were introduced
   * for: one group sharing its whole discography with a session player is one reason to listen to
   * them, and counting the songs would make it thirty.
   */
  public int knownReached() {
    return (int) shared.stream().map(SharedIntermediate::seedQid).distinct().count();
  }

  /** How many distinct things the known-list and this candidate have in common. */
  public int intermediates() {
    return (int) shared.stream().map(SharedIntermediate::viaQid).distinct().count();
  }
}
