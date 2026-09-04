package com.robsartin.segue.census;

import com.robsartin.segue.domain.Recommendations;
import com.robsartin.segue.export.LogProjection;
import java.util.List;
import java.util.Objects;

/**
 * How connected the graph is, read beside the floor that decides what gets recommended.
 *
 * <p><b>This is the number ADR 57 left open.</b> That decision made the floor report itself on
 * every recommender run, through {@code FloorReading}, and the figures it emits describe the
 * candidate population. What nobody has is the same reading over the <em>whole</em> graph, which is
 * what says how far the population has moved away from a floor that was measured once, on a graph
 * that grows under it (issue #135).
 *
 * <p><b>A quantile is a degree some node actually has</b>, on {@code FloorReading.medianDegree}'s
 * stated reason: a median of 6.5 edges describes nothing in the graph, and the figure is read
 * beside an integer floor. The rule is nearest-rank — {@code sorted.get(min(size - 1, (int) (size *
 * p)))} — which at {@code p = 0.5} is exactly that method's upper middle. An empty graph reads as
 * zero, distinguishable from every real reading because every floor is at least one.
 *
 * <p><b>Isolated nodes are in the population</b>, at degree zero. "At or below the floor" against a
 * denominator that had already dropped what nothing reaches would be a different question.
 *
 * @param floor {@code Recommendations.MIN_CANDIDATE_DEGREE}, by reference and never by a second
 *     copy of the number — a reading has to say which floor it is a reading of
 * @param atOrBelowTheFloor nodes whose degree is at most {@code floor}. <b>At or below, where
 *     {@code CandidateSweep} excludes below</b> ({@code candidateDegree < minDegree}), so this is
 *     the sweep's exclusions plus the nodes sitting exactly on the floor — the population {@code
 *     FloorReading.headOnTheFloor} says one expansion moves first
 */
public record DegreeCensus(int floor, int p50, int p90, int p99, int max, int atOrBelowTheFloor) {

  public static DegreeCensus of(LogProjection projection) {
    Objects.requireNonNull(projection, "projection");
    List<Integer> sorted = Degrees.in(projection).values().stream().sorted().toList();
    int floor = Recommendations.MIN_CANDIDATE_DEGREE;
    return new DegreeCensus(
        floor,
        quantile(sorted, 0.50),
        quantile(sorted, 0.90),
        quantile(sorted, 0.99),
        sorted.isEmpty() ? 0 : sorted.getLast(),
        (int) sorted.stream().filter(degree -> degree <= floor).count());
  }

  private static int quantile(List<Integer> sorted, double proportion) {
    return sorted.isEmpty()
        ? 0
        : sorted.get(Math.min(sorted.size() - 1, (int) (sorted.size() * proportion)));
  }
}
