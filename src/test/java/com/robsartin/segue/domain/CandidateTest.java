package com.robsartin.segue.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNoException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A search hit, before anything is written. The description is what makes disambiguation possible —
 * "Q11571, Spanish painter" is answerable by a human, "Q11571" is not.
 */
class CandidateTest {

  @Test
  @DisplayName("a candidate validates its qid the same way a node does")
  void rejectsNonWikidataQid() {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () -> new Candidate("picasso", "Pablo Picasso", "Spanish painter", NodeKind.PERSON));
  }

  @Test
  @DisplayName("a missing description is allowed — Wikidata does not always have one")
  void allowsNullDescription() {
    assertThatNoException()
        .isThrownBy(() -> new Candidate("Q5593", "Pablo Picasso", null, NodeKind.PERSON));
  }

  @Test
  @DisplayName("it renders for disambiguation, description first when present")
  void rendersForDisambiguation() {
    assertThat(
            new Candidate("Q5593", "Pablo Picasso", "Spanish painter", NodeKind.PERSON).describe())
        .isEqualTo("Q5593 — Pablo Picasso (Spanish painter) [PERSON]");
    assertThat(new Candidate("Q5593", "Pablo Picasso", null, NodeKind.PERSON).describe())
        .isEqualTo("Q5593 — Pablo Picasso [PERSON]");
  }
}
