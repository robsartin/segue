package com.robsartin.segue.census;

import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.export.LogProjection;
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

  private static LogProjection line() {
    return LogProjection.of(
        new InventedCensus.FakeAssertionLog()
            .with(
                InventedCensus.node(A, NodeKind.PERSON, "A"),
                InventedCensus.node(B, NodeKind.PERSON, "B"),
                InventedCensus.node(C, NodeKind.PERSON, "C"),
                InventedCensus.node(D, NodeKind.PERSON, "D"),
                InventedCensus.node(E, NodeKind.PERSON, "E"),
                InventedCensus.edge(A, B, "MEMBER_OF", InventedCensus.sourced()),
                InventedCensus.edge(B, C, "MEMBER_OF", InventedCensus.sourced()),
                InventedCensus.edge(C, D, "MEMBER_OF", InventedCensus.sourced()),
                InventedCensus.edge(D, E, "MEMBER_OF", InventedCensus.sourced())));
  }

  @Test
  @DisplayName("every node is a key and an edge is read from both ends")
  void shouldHoldBothEndsOfEveryEdgeWhenTheFoldIsRead() {
    Map<String, Set<String>> adjacency = Neighbours.in(line());

    assertThat(adjacency).containsOnlyKeys(A, B, C, D, E);
    assertThat(adjacency.get(A)).containsExactly(B);
    assertThat(adjacency.get(B)).containsExactlyInAnyOrder(A, C);
  }

  @Test
  @DisplayName("a member of the population two hops away is reached and one three hops away is not")
  void shouldReachAtTwoHopsAndMissAtThreeWhenTheWalkIsBounded() {
    Map<String, Set<String>> adjacency = Neighbours.in(line());

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
    Map<String, Set<String>> adjacency = Neighbours.in(line());

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
    Map<String, Set<String>> adjacency = Neighbours.in(line());

    assertThat(Neighbours.reaches(adjacency, E, Set.of(E), 2)).isFalse();
  }
}
