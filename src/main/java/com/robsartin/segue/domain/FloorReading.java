package com.robsartin.segue.domain;

import java.util.List;
import java.util.Objects;

/**
 * What the degree floor admitted, what it held out, and where the ranked head sits relative to it
 * (issue #135).
 *
 * <p><b>The floor is a measured default and the graph it was measured against changes under it.</b>
 * {@code Recommendations.MIN_CANDIDATE_DEGREE} was chosen twice — twelve by ADR 45, five by that
 * ADR's 2026-08-29 amendment — each time by running two floors on the graph of the day and reading
 * the two lists. That is a good method and a manual one, and nothing prompts it. This record is
 * what makes a run that has drifted <em>look</em> different rather than merely be wrong: the same
 * figures the amendment argued from, emitted by every run, so a later reading is a comparison
 * against a recorded number instead of a fresh judgement.
 *
 * <p><b>It changes no score and reorders nothing.</b> Every field is counted from candidates that
 * have already been scored and ranked, plus two counts of what {@code CandidateSweep} discarded on
 * degree. A reading is an observation of the filter, not a second filter.
 *
 * <p><b>Every field is an aggregate, and that is deliberate</b> — ADR 51 permits a count over the
 * owner's data and forbids naming an entity as his. Nothing here carries a qid or a label, so a
 * reading may be quoted in a tracked file where the ranking it describes may not.
 *
 * @param floor the run's {@code --min-degree}, so a reading says which floor it is a reading of
 * @param pool how many candidates cleared the floor — the population the ranking chose from
 * @param poolMedianDegree the pool's median degree. <b>The drift number.</b> The floor is an
 *     absolute count and degree grows with ingest, so the distance between this and {@code floor}
 *     is how far the population has moved away from the cut
 * @param heldOut how many distinct entities the floor discarded on degree alone — entities that
 *     passed every other candidate test. The one filter in {@code CandidateSweep} whose bite was
 *     reported by nothing
 * @param heldOutAtDegreeOne how many of those carry exactly one edge: the nodes expansion has
 *     discovered and not yet reached a second time (issue #134). Counted apart because it is a
 *     different question from the rest of what the floor holds out — that population grows with
 *     every expansion, and a run that says nothing about it is a run in which growth is invisible
 * @param head how many candidates were actually ranked and written, which {@code --top} bounds
 * @param headMedianDegree the ranked head's median degree — the figure ADR 45's amendment reported
 *     as falling from 27 to 6 when the floor moved
 * @param headOnTheFloor how many ranked entries carry exactly {@code floor} edges. <b>The head of
 *     the list is what moves first</b>, so this is the fraction of what is read that a change in
 *     the floor, or one expansion, would move
 * @param headEveryEdgeCounted how many ranked entries have as many distinct intermediates as they
 *     have edges at all — every edge they carry is already being counted as evidence, which is
 *     another way of saying the graph knows nothing else about them
 */
public record FloorReading(
    int floor,
    int pool,
    int poolMedianDegree,
    int heldOut,
    int heldOutAtDegreeOne,
    int head,
    int headMedianDegree,
    int headOnTheFloor,
    int headEveryEdgeCounted) {

  /**
   * Take a reading of one run.
   *
   * @param pool every candidate that cleared the floor, ranked or not
   * @param head the candidates that were ranked and written — a sublist of {@code pool} by score,
   *     read separately because it is the only part anybody sees
   * @param floor the floor this run applied
   * @param heldOut distinct entities discarded on degree alone, counted by the sweep that discarded
   *     them
   * @param heldOutAtDegreeOne how many of those carry exactly one edge
   */
  public static FloorReading of(
      List<Recommendation> pool,
      List<Recommendation> head,
      int floor,
      int heldOut,
      int heldOutAtDegreeOne) {
    Objects.requireNonNull(pool, "pool");
    Objects.requireNonNull(head, "head");
    return new FloorReading(
        floor,
        pool.size(),
        medianDegree(pool),
        heldOut,
        heldOutAtDegreeOne,
        head.size(),
        medianDegree(head),
        (int) head.stream().filter(candidate -> candidate.degree() == floor).count(),
        (int)
            head.stream()
                .filter(candidate -> candidate.intermediates() == candidate.degree())
                .count());
  }

  /**
   * The degree at the middle of the list, and <b>a degree some candidate actually has</b>.
   *
   * <p>An even count has two middles and this takes the upper one rather than averaging them: a
   * median of 6.5 edges describes nothing in the graph, and the figure is read beside {@code floor}
   * — an integer — so a half-edge would be noise in the one comparison this record exists for.
   *
   * <p>An empty list reads as zero. A run that found no candidates has no median, and zero is
   * distinguishable from every real reading because {@code Recommendations.MIN_CANDIDATE_DEGREE}
   * and every floor a caller may pass are at least one.
   */
  private static int medianDegree(List<Recommendation> candidates) {
    if (candidates.isEmpty()) {
      return 0;
    }
    List<Integer> degrees = candidates.stream().map(Recommendation::degree).sorted().toList();
    return degrees.get(degrees.size() / 2);
  }
}
