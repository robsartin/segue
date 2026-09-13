package com.robsartin.segue.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The rule, on hand-built graphs small enough to read off the page.
 *
 * <p>Every id is invented and carries ADR 58's leading zero, so none of them denotes anything.
 */
class SecondHopTest {

  /** On the list, in the graph, and nothing else on the list is within MAX_HOPS of it. */
  private static final String ACT = "Q0901401";

  /** On the list, one hop from {@link #ACT} — the control that isolation is not universal. */
  private static final String PLACED = "Q0901402";

  /** A PERSON beside {@link #ACT}, not on the list, and no row cites it as a seed. */
  private static final String BANDMATE = "Q0901403";

  /** A PERSON beside {@link #ACT} that a row does cite as a seed. */
  private static final String PRODUCER = "Q0901404";

  /** A WORK beside {@link #ACT} — a kind WORTH_EXPANDING does not name. */
  private static final String RECORD = "Q0901405";

  /** A second isolated act on the list, far apart from {@link #ACT} — no path connects them. */
  private static final String SECOND_ACT = "Q0901406";

  /** An unexpanded GROUP beside {@link #SECOND_ACT}. */
  private static final String OTHER_BAND = "Q0901407";

  /** Named by the list and never claimed as a node. */
  private static final String ABSENT = "Q0901408";

  private static Map<String, NodeRecord> nodes(Map<String, NodeKind> kinds) {
    Map<String, NodeRecord> nodes = new LinkedHashMap<>();
    kinds.forEach((qid, kind) -> nodes.put(qid, new NodeRecord(qid, kind, "a label for " + qid)));
    return nodes;
  }

  private static EdgeRecord edge(String from, String to) {
    return new EdgeRecord(from, to, "MEMBER_OF", null, null, List.of());
  }

  @Test
  @DisplayName(
      "an act with no member within the hop limit is isolated, and its unexpanded"
          + " person is to expand")
  void shouldReportTheUnexpandedPersonWhenAnIsolatedActHasOneBesideIt() {
    SecondHop rule =
        SecondHop.of(
            nodes(new LinkedHashMap<>(Map.of(ACT, NodeKind.GROUP, BANDMATE, NodeKind.PERSON))),
            List.of(edge(ACT, BANDMATE)),
            List.of(ACT),
            new Expanded(Set.of()));

    assertThat(rule.isolated()).containsExactly(ACT);
    assertThat(rule.toExpandBeside(ACT)).containsExactly(BANDMATE);
    assertThat(rule.toExpand()).containsExactly(BANDMATE);
  }

  @Test
  @DisplayName("an isolated act whose only neighbour is already expanded has no one beside it")
  void shouldReportNoOneToExpandWhenTheOnlyNeighbourIsAlreadyExpanded() {
    SecondHop rule =
        SecondHop.of(
            nodes(new LinkedHashMap<>(Map.of(ACT, NodeKind.GROUP, PRODUCER, NodeKind.PERSON))),
            List.of(edge(ACT, PRODUCER)),
            List.of(ACT),
            new Expanded(Set.of(PRODUCER)));

    assertThat(rule.isolated())
        .as("still isolated — expansion is not placement")
        .containsExactly(ACT);
    assertThat(rule.toExpandBeside(ACT)).isEmpty();
    assertThat(rule.toExpand()).isEmpty();
  }

  @Test
  @DisplayName("an act with a member within the hop limit is not isolated and contributes nothing")
  void shouldContributeNothingWhenTheActHasAMemberWithinTheHopLimit() {
    // The planted control for "isolated first": PLACED is one hop from ACT and BOTH have an
    // unexpanded person beside them. A rule that read "unexpanded neighbours" without reading
    // isolation would return BANDMATE here.
    SecondHop rule =
        SecondHop.of(
            nodes(
                new LinkedHashMap<>(
                    Map.of(
                        ACT, NodeKind.GROUP,
                        PLACED, NodeKind.GROUP,
                        BANDMATE, NodeKind.PERSON))),
            List.of(edge(ACT, PLACED), edge(ACT, BANDMATE)),
            List.of(ACT, PLACED),
            new Expanded(Set.of()));

    assertThat(rule.isolated()).isEmpty();
    assertThat(rule.toExpand()).as("nothing is isolated, so there is nothing to expand").isEmpty();
  }

  @Test
  @DisplayName("a neighbour of a kind the constant does not name is not to expand")
  void shouldLeaveOutTheNeighbourWhenItsKindIsNotWorthExpanding() {
    SecondHop rule =
        SecondHop.of(
            nodes(new LinkedHashMap<>(Map.of(ACT, NodeKind.GROUP, RECORD, NodeKind.WORK))),
            List.of(edge(ACT, RECORD)),
            List.of(ACT),
            new Expanded(Set.of()));

    assertThat(rule.isolated()).containsExactly(ACT);
    assertThat(rule.toExpandBeside(ACT))
        .as("SecondHop.WORTH_EXPANDING names PERSON and GROUP, and a WORK is neither")
        .isEmpty();
  }

  @Test
  @DisplayName(
      "two isolated acts far apart each contribute their own neighbours, in population order")
  void shouldListEachActsNeighboursInIsolatedOrderWhenTwoIsolatedActsAreApart() {
    // Population given SECOND_ACT before ACT — the reverse of the adjacency map's own insertion
    // order below — so the assertion can only pass by reading the population's order and not the
    // map's (#319 review, minor 10).
    SecondHop rule =
        SecondHop.of(
            nodes(
                new LinkedHashMap<>(
                    Map.of(
                        ACT, NodeKind.GROUP,
                        BANDMATE, NodeKind.PERSON,
                        SECOND_ACT, NodeKind.GROUP,
                        OTHER_BAND, NodeKind.GROUP))),
            List.of(edge(ACT, BANDMATE), edge(SECOND_ACT, OTHER_BAND)),
            List.of(SECOND_ACT, ACT),
            new Expanded(Set.of()));

    assertThat(rule.isolated())
        .as("no path connects the two acts, so each is isolated from the other")
        .containsExactly(SECOND_ACT, ACT);
    assertThat(rule.toExpandBeside(ACT)).containsExactly(BANDMATE);
    assertThat(rule.toExpandBeside(SECOND_ACT)).containsExactly(OTHER_BAND);
    assertThat(rule.toExpand())
        .as("each act's own neighbour, in population order — not the adjacency map's own order")
        .containsExactly(OTHER_BAND, BANDMATE);
  }

  @Test
  @DisplayName("the direction the edge was stated in does not decide who is beside whom")
  void shouldReadTheEdgeFromBothEndsWhenTheNeighbourIsTheSubject() {
    SecondHop rule =
        SecondHop.of(
            nodes(new LinkedHashMap<>(Map.of(ACT, NodeKind.GROUP, BANDMATE, NodeKind.PERSON))),
            List.of(edge(BANDMATE, ACT)),
            List.of(ACT),
            new Expanded(Set.of()));

    assertThat(rule.toExpandBeside(ACT)).containsExactly(BANDMATE);
  }

  @Test
  @DisplayName("a member the fold holds no node for is neither isolated nor an error")
  void shouldPassOverTheMemberWhenTheFoldHoldsNoNodeForIt() {
    SecondHop rule =
        SecondHop.of(
            nodes(new LinkedHashMap<>(Map.of(ACT, NodeKind.GROUP, BANDMATE, NodeKind.PERSON))),
            List.of(edge(ACT, BANDMATE)),
            List.of(ACT, ABSENT),
            new Expanded(Set.of()));

    assertThat(rule.isolated()).containsExactly(ACT);
  }

  @Test
  @DisplayName("asking what to expand beside an id that is not isolated is refused")
  void shouldThrowWhenAskedWhatToExpandBesideAnIdThatIsNotIsolated() {
    SecondHop rule =
        SecondHop.of(
            nodes(
                new LinkedHashMap<>(
                    Map.of(
                        ACT, NodeKind.GROUP, PLACED, NodeKind.GROUP, BANDMATE, NodeKind.PERSON))),
            List.of(edge(ACT, PLACED), edge(ACT, BANDMATE)),
            List.of(ACT, PLACED),
            new Expanded(Set.of()));

    assertThatThrownBy(() -> rule.toExpandBeside(PLACED))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(PLACED);
  }
}
