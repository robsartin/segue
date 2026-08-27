package com.robsartin.segue.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ADR 44: the one rule both projections apply, so a graph and a picture of that graph cannot
 * disagree about what has been taken back out. The same argument ADR 42 makes for {@code
 * KindMapper.rederive}.
 */
class RetractionsTest {

  private static final Provenance SOURCE =
      new Provenance("invented", "invented:1", Instant.parse("2026-01-01T00:00:00Z"), 1.0);

  private static final Instant LATER = Instant.parse("2026-02-01T00:00:00Z");

  private static NodeAssertion node(String qid) {
    return new NodeAssertion(qid, NodeKind.PERSON, "invented", SOURCE);
  }

  private static AssertionRecord edge(String from, String to) {
    return new AssertionRecord(from, to, "MEMBER_OF", null, null, SOURCE);
  }

  private static Retraction retract(String qid) {
    return new Retraction(qid, "invented", LATER);
  }

  /** Which of these rows the projection keeps, in order. */
  private static List<LoggedAssertion> surviving(List<LoggedAssertion> log) {
    Retractions retractions = Retractions.in(log);
    return java.util.stream.IntStream.range(0, log.size())
        .filter(i -> retractions.survives(i, log.get(i)))
        .mapToObj(log::get)
        .toList();
  }

  @Test
  @DisplayName("with no retraction in the log, every claim survives")
  void nothingRetractedKeepsEverything() {
    List<LoggedAssertion> log =
        List.of(node("Q900101"), node("Q900102"), edge("Q900101", "Q900102"));

    assertThat(surviving(log)).isEqualTo(log);
  }

  @Test
  @DisplayName("a retraction removes the entity's own node claim and every edge touching it")
  void retractsTheEntityAndItsEdges() {
    // The granularity decision: the unit is the entity, not one edge and not one expansion.
    // The motivating case is a wrongly-RESOLVED entity, so the node claim has to go too -
    // retracting only the expansion would leave a wrong identity in the graph, still findable
    // and still rateable.
    NodeAssertion wrong = node("Q900101");
    NodeAssertion right = node("Q900102");
    AssertionRecord out = edge("Q900101", "Q900102");
    AssertionRecord in = edge("Q900102", "Q900101");

    List<LoggedAssertion> log = List.of(wrong, right, out, in, retract("Q900101"));

    assertThat(surviving(log)).containsExactly(right);
  }

  @Test
  @DisplayName("a retraction reaches backwards only: later claims about the entity stand")
  void laterClaimsStand() {
    // This is what makes re-adding an entity the natural un-retraction (ADR 44's fourth
    // question). Nothing special happens on the way back in: the claim is simply newer than
    // the retraction, so nothing retracts it.
    NodeAssertion before = node("Q900101");
    NodeAssertion after = node("Q900101");
    AssertionRecord afterEdge = edge("Q900101", "Q900102");

    List<LoggedAssertion> log =
        List.of(before, node("Q900102"), retract("Q900101"), after, afterEdge);

    assertThat(surviving(log)).containsExactly(node("Q900102"), after, afterEdge);
  }

  @Test
  @DisplayName(
      "the retraction row itself is not projected - it describes the fold, it is not in it")
  void theRetractionRowIsNotProjected() {
    Retraction retraction = retract("Q900101");

    assertThat(surviving(List.of(retraction))).isEmpty();
  }

  @Test
  @DisplayName(
      "a second retraction of the same entity reaches everything before it, including a re-add")
  void aSecondRetractionMovesTheCutForward() {
    NodeAssertion first = node("Q900101");
    NodeAssertion readded = node("Q900101");

    List<LoggedAssertion> log = List.of(first, retract("Q900101"), readded, retract("Q900101"));

    assertThat(surviving(log)).isEmpty();
  }

  @Test
  @DisplayName("retracting one entity leaves an unrelated entity's claims alone")
  void retractionIsScopedToItsEntity() {
    NodeAssertion other = node("Q900103");
    AssertionRecord unrelated = edge("Q900102", "Q900103");

    List<LoggedAssertion> log =
        List.of(node("Q900101"), node("Q900102"), other, unrelated, retract("Q900101"));

    assertThat(surviving(log)).containsExactly(node("Q900102"), other, unrelated);
  }

  @Test
  @DisplayName("an empty log has nothing to retract")
  void emptyLog() {
    assertThat(surviving(List.of())).isEmpty();
  }
}
