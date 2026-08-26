package com.robsartin.segue.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNoException;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The taste layer's one value type, and the invariants ADR 39 settles: a required 1-5 rating, an
 * optional note, and a timestamp saying when the rating last changed.
 *
 * <p>Every rating and note in this file is invented. The repository is public and affinity is
 * personal data (ADR 33, as amended by issue #37), so a fixture is exactly one of the leak paths
 * that amendment names - the qids here are the same Q9000xx placeholders the graph fixture uses,
 * and the numbers beside them are made up.
 */
class AffinityRecordTest {

  private static final Instant WHEN = Instant.parse("2026-08-25T12:00:00Z");

  @Test
  @DisplayName("a rating below 1 is rejected")
  void rejectsRatingBelowOne() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new AffinityRecord("Q900001", 0, null, WHEN))
        .withMessageContaining("1 to 5");
  }

  @Test
  @DisplayName("a rating above 5 is rejected")
  void rejectsRatingAboveFive() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new AffinityRecord("Q900001", 6, null, WHEN));
  }

  @Test
  @DisplayName("the rejection message never echoes the rating it rejected")
  void rejectionMessageDoesNotEchoTheValue() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new AffinityRecord("Q900001", 9, null, WHEN))
        .withMessageNotContaining("9");
  }

  @Test
  @DisplayName("both ends of the scale are accepted, so negative affinity is expressible")
  void acceptsBothEndsOfTheScale() {
    assertThatNoException().isThrownBy(() -> new AffinityRecord("Q900001", 1, null, WHEN));
    assertThatNoException().isThrownBy(() -> new AffinityRecord("Q900001", 5, null, WHEN));
  }

  @Test
  @DisplayName("the note is optional; the rating is not")
  void noteIsOptional() {
    AffinityRecord withoutNote = new AffinityRecord("Q900001", 4, null, WHEN);

    assertThat(withoutNote.note()).isNull();
    assertThat(withoutNote.rating()).isEqualTo(4);
  }

  @Test
  @DisplayName("a qid that is not a Wikidata identifier is rejected, as everywhere else (ADR 22)")
  void rejectsNonWikidataQid() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new AffinityRecord("that-band-from-the-radio", 3, null, WHEN))
        .withMessageContaining("qid must look like");
  }

  @Test
  @DisplayName("the updated-at timestamp is required")
  void requiresUpdatedAtAndKeepsIt() {
    assertThat(new AffinityRecord("Q900001", 3, "an invented note", WHEN).updatedAt())
        .isEqualTo(WHEN);
  }
}
