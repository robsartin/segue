package com.robsartin.segue.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ADR 44: a retraction is a first-person act, not a sourced claim, so it carries what such an act
 * can honestly carry - which entity, why, and when - and no {@link Provenance}.
 */
class RetractionTest {

  private static final Instant WHEN = Instant.parse("2026-08-27T10:00:00Z");

  @Test
  @DisplayName("a retraction names an entity, a reason and the moment it was decided")
  void carriesQidReasonAndInstant() {
    Retraction retraction =
        new Retraction("Q0900101", "resolved to the painters, not the band", WHEN);

    assertThat(retraction.qid()).isEqualTo("Q0900101");
    assertThat(retraction.reason()).isEqualTo("resolved to the painters, not the band");
    assertThat(retraction.retractedAt()).isEqualTo(WHEN);
  }

  @Test
  @DisplayName("every field is required")
  void rejectsNulls() {
    assertThatNullPointerException().isThrownBy(() -> new Retraction(null, "why", WHEN));
    assertThatNullPointerException().isThrownBy(() -> new Retraction("Q0900101", null, WHEN));
    assertThatNullPointerException().isThrownBy(() -> new Retraction("Q0900101", "why", null));
  }

  @Test
  @DisplayName("a blank reason is refused: the log has to say why, or it records nothing useful")
  void rejectsABlankReason() {
    // The whole value of keeping a retraction in an append-only log is that it says we later
    // concluded something was wrong AND what the conclusion was. A retraction with no reason
    // leaves the second half to whoever reads the log next.
    assertThatIllegalArgumentException().isThrownBy(() -> new Retraction("Q0900101", "  ", WHEN));
  }

  @Test
  @DisplayName("the qid is validated, so a typo cannot retract nothing in silence")
  void rejectsSomethingThatIsNotAQid() {
    // A retraction is not corrected by editing the log, so a mistyped target is a row that sits
    // there forever retracting an entity that does not exist. NodeRecord validates its qids for
    // a lesser reason than this one.
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new Retraction("the-highwaymen", "why", WHEN));
  }
}
