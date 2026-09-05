package com.robsartin.segue.census;

import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.domain.Recommendations;
import com.robsartin.segue.export.LogProjection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
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
 * beside an integer floor. The rule is ADR 55's nearest-rank — {@code sorted.get(max(1, ceil(p *
 * size)) - 1)}, the same one the MusicBrainz probe uses — not the naive {@code floor(p * size)}
 * index, which agrees with it everywhere except where {@code p * size} lands on an exact integer.
 * An empty graph reads as zero, distinguishable from every real reading because every floor is at
 * least one.
 *
 * <p><b>{@code p50} and {@code FloorReading.medianDegree} are not the same statistic, and must not
 * be compared as one number.</b> This is ADR 55's nearest rank; {@code FloorReading} takes {@code
 * degrees.get(size / 2)}, the <em>upper</em> of the two middles, and keeps it — that decision was
 * made for the candidate population and this one may not reach into it and change the recommender's
 * reported figure. On an even population the two rules differ by one position: on {@code
 * DegreeCensusTest}'s ten-node fixture, five zeros and five twos, this reads 0 and {@code
 * FloorReading}'s rule would read 2. They describe different populations anyway — every node here,
 * the surviving candidates there — so the two figures answer different questions and their
 * difference is not a drift to reconcile.
 *
 * <p><b>Isolated nodes are in the population</b>, at degree zero. "At or below the floor" against a
 * denominator that had already dropped what nothing reaches would be a different question.
 *
 * <p><b>The same figures are read again per {@link com.robsartin.segue.domain.NodeKind}</b>, all
 * six kinds and zeros included, because the floor is applied to two of them and nothing else:
 * {@code CandidateSweep.couldBeExplored} refuses every kind but {@code PERSON} and {@code GROUP}
 * before the degree test is reached, so the whole-graph reading above is a true statement about the
 * graph and a misleading one about the floor (issue #247). The whole-graph reading stays because
 * issue #135's question is about the graph the floor was measured against. One rule reads both —
 * {@code read} — so a kind's quantile and the graph's cannot come to disagree about what a quantile
 * is.
 *
 * @param floor {@code Recommendations.MIN_CANDIDATE_DEGREE}, by reference and never by a second
 *     copy of the number — a reading has to say which floor it is a reading of
 * @param atOrBelowTheFloor nodes whose degree is at most {@code floor}. <b>At or below, where
 *     {@code CandidateSweep} excludes below</b> ({@code candidateDegree < minDegree}), so this is
 *     the sweep's exclusions plus the nodes sitting exactly on the floor — the population {@code
 *     FloorReading.headOnTheFloor} says one expansion moves first
 * @param byKind the same reading taken over each kind's own nodes, in {@code NodeKind} declaration
 *     order. An {@code EnumMap} rather than {@code Map.copyOf}, on {@code NodeCensus}'s reason:
 *     that factory's iteration order is unspecified and salted per JVM, and ADR 43's byte-identical
 *     contract is what the order serves
 */
public record DegreeCensus(
    int floor,
    int p50,
    int p90,
    int p99,
    int max,
    int atOrBelowTheFloor,
    Map<NodeKind, KindDegrees> byKind) {

  public DegreeCensus {
    Objects.requireNonNull(byKind, "byKind");
    // new EnumMap<>(map) refuses an empty map it cannot infer the key type from; the class
    // constructor plus putAll takes one, and no caller has to know that.
    Map<NodeKind, KindDegrees> copy = new EnumMap<>(NodeKind.class);
    copy.putAll(byKind);
    byKind = Collections.unmodifiableMap(copy);
  }

  /** One kind's population, read by the rules the whole graph is read by. */
  public record KindDegrees(int p50, int p90, int p99, int max, int atOrBelowTheFloor) {}

  public static DegreeCensus of(LogProjection projection) {
    Objects.requireNonNull(projection, "projection");
    Map<String, Integer> degrees = Degrees.in(projection);
    int floor = Recommendations.MIN_CANDIDATE_DEGREE;
    Map<NodeKind, List<Integer>> collected = new EnumMap<>(NodeKind.class);
    for (NodeKind kind : NodeKind.values()) {
      collected.put(kind, new ArrayList<>());
    }
    // Every key came from projection.nodes(), which Degrees.in seeds itself from, so there is no
    // absent node to defend against here.
    degrees.forEach((qid, degree) -> collected.get(projection.nodes().get(qid).kind()).add(degree));
    Map<NodeKind, KindDegrees> byKind = new EnumMap<>(NodeKind.class);
    collected.forEach((kind, population) -> byKind.put(kind, read(population, floor)));
    KindDegrees whole = read(List.copyOf(degrees.values()), floor);
    return new DegreeCensus(
        floor,
        whole.p50(),
        whole.p90(),
        whole.p99(),
        whole.max(),
        whole.atOrBelowTheFloor(),
        byKind);
  }

  /** One population's figures — the whole graph's and every kind's, by one rule rather than two. */
  private static KindDegrees read(List<Integer> population, int floor) {
    List<Integer> sorted = population.stream().sorted().toList();
    return new KindDegrees(
        quantile(sorted, 0.50),
        quantile(sorted, 0.90),
        quantile(sorted, 0.99),
        sorted.isEmpty() ? 0 : sorted.getLast(),
        (int) sorted.stream().filter(degree -> degree <= floor).count());
  }

  /** ADR 55's nearest-rank convention: {@code sorted.get(max(1, ceil(p * size)) - 1)}. */
  private static int quantile(List<Integer> sorted, double proportion) {
    if (sorted.isEmpty()) {
      return 0;
    }
    int rank = (int) Math.ceil(proportion * sorted.size());
    return sorted.get(Math.max(1, rank) - 1);
  }
}
