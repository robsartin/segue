package com.robsartin.segue.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The adjacency the known-list walk runs on, and the bound it stops at.
 *
 * <p>A line of five: A — B — C — D — E. It is the one shape that can tell "within two hops" from
 * "within three" and from "within one", and every assertion here reads off it.
 */
class NeighboursTest {

  private static final String A = "Q0901001";
  private static final String B = "Q0901002";
  private static final String C = "Q0901003";
  private static final String D = "Q0901004";
  private static final String E = "Q0901005";

  /**
   * Named as a node claim and never an edge endpoint — the isolated node this class was missing.
   */
  private static final String F = "Q0901006";

  /** Named as an edge endpoint and never claimed as a node — {@code Neighbours.link}'s guard. */
  private static final String UNCLAIMED = "Q0901099";

  private static Map<String, NodeRecord> nodes(String... qids) {
    Map<String, NodeRecord> nodes = new LinkedHashMap<>();
    for (String qid : qids) {
      nodes.put(qid, new NodeRecord(qid, NodeKind.PERSON, qid));
    }
    return nodes;
  }

  private static EdgeRecord edge(String from, String to) {
    return new EdgeRecord(from, to, "MEMBER_OF", null, null, List.of());
  }

  /** A — B — C — D — E, and F with no edge at all. */
  private static Map<String, Set<String>> line() {
    return Neighbours.in(
        nodes(A, B, C, D, E, F), List.of(edge(A, B), edge(B, C), edge(C, D), edge(D, E)));
  }

  @Test
  @DisplayName("every node is a key and an edge is read from both ends")
  void shouldHoldBothEndsOfEveryEdgeWhenTheFoldIsRead() {
    Map<String, Set<String>> adjacency = line();

    assertThat(adjacency).containsOnlyKeys(A, B, C, D, E, F);
    assertThat(adjacency.get(A)).containsExactly(B);
    assertThat(adjacency.get(B)).containsExactlyInAnyOrder(A, C);
  }

  @Test
  @DisplayName("an isolated node is a key with an empty set, not omitted")
  void shouldKeyAnIsolatedNodeToAnEmptySetWhenNothingReachesIt() {
    Map<String, Set<String>> adjacency = line();

    assertThat(adjacency).containsKey(F);
    assertThat(adjacency.get(F))
        .as("F has no edge in the fixture; a node nothing reaches is the finding, not an omission")
        .isEmpty();
  }

  @Test
  @DisplayName("an edge naming an endpoint that is not a node is linked from neither end")
  void shouldLinkFromNeitherEndWhenAnEdgesEndpointIsNotANode() {
    Map<String, Set<String>> adjacency =
        Neighbours.in(
            Map.of(A, new NodeRecord(A, NodeKind.PERSON, "A")), List.of(edge(A, UNCLAIMED)));

    assertThat(adjacency).containsOnlyKeys(A);
    assertThat(adjacency.get(A))
        .as("the guard refuses an endpoint that never claimed a node")
        .isEmpty();
  }

  @Test
  @DisplayName("a member of the population two hops away is reached and one three hops away is not")
  void shouldReachAtTwoHopsAndMissAtThreeWhenTheWalkIsBounded() {
    Map<String, Set<String>> adjacency = line();

    assertThat(Neighbours.reaches(adjacency, A, Set.of(A, C), 2))
        .as("C is exactly two hops from A")
        .isTrue();
    assertThat(Neighbours.reaches(adjacency, A, Set.of(A, D), 2))
        .as("D is three hops from A, which is past the bound")
        .isFalse();
  }

  @Test
  @DisplayName("a neighbour outside the population is walked through and never counted")
  void shouldIgnoreANeighbourWhenItIsNotInThePopulation() {
    Map<String, Set<String>> adjacency = line();

    assertThat(Neighbours.reaches(adjacency, A, Set.of(A), 2))
        .as("B and C are there, and neither is in the population")
        .isFalse();
    assertThat(Neighbours.reaches(adjacency, A, Set.of(A, B), 2))
        .as("B is, at one hop — within two")
        .isTrue();
  }

  @Test
  @DisplayName("the entity itself is never its own known neighbour")
  void shouldNotReachItselfWhenItIsTheOnlyMemberOfThePopulation() {
    Map<String, Set<String>> adjacency = line();

    assertThat(Neighbours.reaches(adjacency, E, Set.of(E), 2)).isFalse();
  }

  @Test
  @DisplayName("a self-loop edge on the start node still does not make it its own neighbour")
  void shouldNotReachItselfWhenASelfLoopEdgeExists() {
    // Unlike the test above, A has no OTHER edge at all here — the only thing adjacency holds for
    // it is the self-loop — so a `reaches` that forgot to mark `from` seen before walking would
    // find A one hop from itself and answer true (#319 review, minor 14: "deliberately does not
    // count at all").
    Map<String, Set<String>> adjacency = Neighbours.in(nodes(A), List.of(edge(A, A)));

    assertThat(adjacency.get(A)).as("the self-loop is recorded in the adjacency").contains(A);
    assertThat(Neighbours.reaches(adjacency, A, Set.of(A), 2)).isFalse();
  }
}
