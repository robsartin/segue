package com.robsartin.segue.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ADR 24: nodes are logged as assertions too, so replay can reconstruct them. A sealed {@link
 * LoggedAssertion} permits the node and edge claims and, since ADR 44, the retraction that takes
 * some of them back out of the projection; replay dispatches on the pattern.
 */
class LoggedAssertionTest {

  private static final Provenance WIKIDATA =
      new Provenance("wikidata", "Q5593", Instant.EPOCH, 1.0);

  @Test
  @DisplayName("both claim kinds are LoggedAssertions")
  void bothKindsAreLogged() {
    LoggedAssertion node = new NodeAssertion("Q5593", NodeKind.PERSON, "Pablo Picasso", WIKIDATA);
    LoggedAssertion edge =
        new AssertionRecord("Q5593", "Q999", "INFLUENCED_BY", null, null, WIKIDATA);

    assertThat(node).isInstanceOf(LoggedAssertion.class);
    assertThat(edge).isInstanceOf(LoggedAssertion.class);
  }

  @Test
  @DisplayName("the sealed hierarchy permits the two claim kinds and the retraction")
  void permitsExactlyThree() {
    assertThat(LoggedAssertion.class.getPermittedSubclasses())
        .containsExactlyInAnyOrder(NodeAssertion.class, AssertionRecord.class, Retraction.class);
  }

  @Test
  @DisplayName("a retraction is logged like any other row")
  void aRetractionIsLogged() {
    LoggedAssertion retraction =
        new Retraction("Q5593", "wrong entity", Instant.parse("2026-08-27T10:00:00Z"));

    assertThat(retraction).isInstanceOf(LoggedAssertion.class);
  }

  @Test
  @DisplayName("a NodeAssertion carries its own provenance and rejects nulls")
  void nodeAssertionValidates() {
    NodeAssertion node = new NodeAssertion("Q5593", NodeKind.PERSON, "Pablo Picasso", WIKIDATA);
    assertThat(node.provenance()).isEqualTo(WIKIDATA);

    assertThatNullPointerException()
        .isThrownBy(() -> new NodeAssertion(null, NodeKind.PERSON, "x", WIKIDATA));
    assertThatNullPointerException().isThrownBy(() -> new NodeAssertion("Q1", null, "x", WIKIDATA));
    assertThatNullPointerException()
        .isThrownBy(() -> new NodeAssertion("Q1", NodeKind.WORK, "x", null));
  }

  @Test
  @DisplayName("a node claim carries the P31 values it was classified from, into the projection")
  void nodeAssertionCarriesInstanceOf() {
    // Issue #60: the claim keeps the raw fact beside the derived classification, so a later
    // projection can re-derive the kind without going back to Wikidata.
    NodeAssertion claim =
        new NodeAssertion("Q5593", NodeKind.PERSON, "Pablo Picasso", List.of("Q5"), WIKIDATA);

    assertThat(claim.instanceOf()).containsExactly("Q5");
    assertThat(claim.toNode().instanceOf()).containsExactly("Q5");
  }
}
