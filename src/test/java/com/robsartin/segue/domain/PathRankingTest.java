package com.robsartin.segue.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ADR 31: the shared ranking that both adapters are held to. A path is only as trustworthy as its
 * shakiest hop, so the order is weakest confidence descending, with hop count breaking ties.
 */
class PathRankingTest {

  @Test
  @DisplayName("weakest confidence descending is the primary order")
  void ordersByWeakestConfidenceDescending() {
    PathResult weak = path(0.30);
    PathResult strong = path(1.00);
    PathResult middle = path(0.80);

    List<PathResult> ranked = PathRanking.rank(List.of(weak, strong, middle));

    assertThat(ranked).extracting(PathResult::weakestConfidence).containsExactly(1.00, 0.80, 0.30);
  }

  @Test
  @DisplayName("a path is only as trustworthy as its shakiest hop")
  void weakestHopDecidesTheRank() {
    // Two strong hops and one weak hop: the weak hop sets the path's confidence.
    PathResult mixed = path(1.00, 0.20, 0.90);
    PathResult steady = path(0.50, 0.50);

    List<PathResult> ranked = PathRanking.rank(List.of(mixed, steady));

    assertThat(ranked).containsExactly(steady, mixed);
  }

  @Test
  @DisplayName("hop count ascending breaks ties on equal confidence")
  void hopCountBreaksTies() {
    PathResult threeHops = path(1.00, 1.00, 1.00);
    PathResult oneHop = path(1.00);
    PathResult twoHops = path(1.00, 1.00);

    List<PathResult> ranked = PathRanking.rank(List.of(threeHops, oneHop, twoHops));

    assertThat(ranked).containsExactly(oneHop, twoHops, threeHops);
  }

  @Test
  @DisplayName("the internal cap bounds a dense result, keeping the best")
  void capBoundsTheResultKeepingTheBest() {
    // One clearly-best strong path, then many weak ones well over the cap.
    PathResult best = path(1.00);
    List<PathResult> many =
        IntStream.range(0, PathRanking.MAX_PATHS + 25).mapToObj(i -> path(0.10)).toList();
    List<PathResult> input =
        java.util.stream.Stream.concat(many.stream(), java.util.stream.Stream.of(best)).toList();

    List<PathResult> ranked = PathRanking.rank(input);

    assertThat(ranked).hasSize(PathRanking.MAX_PATHS);
    assertThat(ranked.get(0)).isEqualTo(best);
  }

  @Test
  @DisplayName("an empty input ranks to an empty list")
  void emptyStaysEmpty() {
    assertThat(PathRanking.rank(List.of())).isEmpty();
  }

  /** A path whose hops carry the given confidences, one edge per hop. */
  private static PathResult path(double... hopConfidences) {
    List<Hop> hops =
        IntStream.range(0, hopConfidences.length)
            .mapToObj(
                i -> {
                  NodeRecord from = new NodeRecord("Q" + i, NodeKind.CONCEPT, "n" + i);
                  NodeRecord to = new NodeRecord("Q" + (i + 1), NodeKind.CONCEPT, "n" + (i + 1));
                  EdgeRecord edge =
                      new EdgeRecord(
                          from.qid(),
                          to.qid(),
                          "REL",
                          null,
                          null,
                          List.of(
                              new Provenance(
                                  "src" + i, "ref" + i, Instant.EPOCH, hopConfidences[i])));
                  return new Hop(from, edge, to, false);
                })
            .toList();
    return new PathResult(hops);
  }
}
