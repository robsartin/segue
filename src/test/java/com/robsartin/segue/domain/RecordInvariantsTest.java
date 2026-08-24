package com.robsartin.segue.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNoException;

import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The domain records validate at construction. These were the first section of the old
 * DomainSelfTest; they are the guard rails every adapter relies on.
 */
class RecordInvariantsTest {

  private static final Instant WHEN = Instant.parse("2026-08-01T09:00:00Z");

  @Test
  @DisplayName("a qid that is not a Wikidata identifier is rejected")
  void rejectsNonWikidataQid() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new NodeRecord("nick-cave", NodeKind.PERSON, "Nick Cave"))
        .withMessageContaining("qid must look like");
  }

  @Test
  @DisplayName("a well-formed qid is accepted")
  void acceptsWikidataQid() {
    assertThatNoException()
        .isThrownBy(() -> new NodeRecord("Q5593", NodeKind.PERSON, "Pablo Picasso"));
  }

  @Test
  @DisplayName("confidence outside [0,1] is rejected at both ends")
  void rejectsConfidenceOutOfRange() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new Provenance("wikidata", "ref", WHEN, 1.5));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new Provenance("wikidata", "ref", WHEN, -0.1));
  }

  @Test
  @DisplayName("codec separators in provenance are rejected, because the codec does not escape")
  void rejectsCodecSeparators() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new Provenance("wikidata", "a\tb", WHEN, 1.0));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new Provenance("wikidata", "a\nb", WHEN, 1.0));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new Provenance("wiki\tdata", "ref", WHEN, 1.0));
  }

  @Test
  @DisplayName("a validity window that ends before it starts is rejected")
  void rejectsInvertedValidityWindow() {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                new AssertionRecord(
                    "Q1",
                    "Q2",
                    "MEMBER_OF",
                    LocalDate.of(2000, 1, 1),
                    LocalDate.of(1990, 1, 1),
                    new Provenance("wikidata", null, WHEN, 1.0)));
  }

  @Test
  @DisplayName("an open-ended validity window is allowed on either side")
  void allowsOpenEndedWindows() {
    assertThatNoException()
        .isThrownBy(
            () ->
                new AssertionRecord(
                    "Q1",
                    "Q2",
                    "MEMBER_OF",
                    LocalDate.of(1983, 1, 1),
                    null,
                    new Provenance("wikidata", null, WHEN, 1.0)));
  }

  @Test
  @DisplayName("the llm: prefix is what marks an assertion as a hypothesis")
  void hypothesisIsIdentifiedBySourcePrefix() {
    assertThat(new Provenance("llm:claude", "turn-1", WHEN, 0.30).isHypothesis()).isTrue();
    assertThat(new Provenance("wikidata", "S-1", WHEN, 1.00).isHypothesis()).isFalse();
  }

  @Test
  @DisplayName("NodeKind has exactly six constants")
  void nodeKindHasSixConstants() {
    // docs/adr/0021-six-kind-ontology.md. Wanting a seventh means the model is being
    // used wrong; this test is the guard on that decision.
    assertThat(NodeKind.values()).hasSize(6);
    assertThat(NodeKind.values())
        .containsExactly(
            NodeKind.PERSON,
            NodeKind.GROUP,
            NodeKind.WORK,
            NodeKind.PLACE,
            NodeKind.EVENT,
            NodeKind.CONCEPT);
  }
}
