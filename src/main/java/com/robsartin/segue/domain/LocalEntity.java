package com.robsartin.segue.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "This exists, and Wikidata does not model it." An indie author's book, a self-pressed record -
 * anything #92 exists for. A first-person {@link LoggedAssertion}, on {@link Retraction}'s
 * precedent: its own validation, in {@code domain}, and no {@link Provenance} - there is no source
 * to attribute this to, because the owner minting it is the source.
 *
 * <p><b>Identity reuses ADR 58's unallocatable-QID mechanism (issue #141).</b> Wikibase's {@code
 * ItemId} grammar is {@code Q[1-9]\d{0,9}}: the first digit after {@code Q} may never be zero, so a
 * qid that starts {@code Q0} can never be allocated by Wikidata, now or in the future. That is what
 * lets a local entity carry a real qid - the shape {@link NodeRecord} and every store already
 * expect - without a second identity type reaching {@code port}, {@code tinker} or {@code jena}
 * (design doc, "Identity").
 *
 * <p><b>The local-entity band.</b> ADR 58 claimed the leading-zero space for {@code Fixture}'s test
 * stand-ins ({@code Q0900001}-{@code Q0900015}). Reusing the mechanism without a further rule would
 * leave a stand-in and one of the owner's own books looking identical - both just "some
 * leading-zero qid" - which is the convention-splitting outcome ADR 58 stopped short of, arriving
 * from the other side. So a local entity's trailing digits must additionally be {@value
 * #LOCAL_ENTITY_MIN} or greater: {@code Q0900020} and up. The gap between Fixture's current ceiling
 * (15) and this floor is deliberate headroom, not a number chosen because it looked free - ADR 58's
 * own account of how the {@code Q9000xx} range came to collide is the lesson this gap is sized
 * against.
 *
 * @param qid the local entity's own identifier - leading-zero, and in the local-entity band
 * @param kind what it is, same as any other node
 * @param label how the owner refers to it
 * @param mintedAt when the owner minted it
 */
public record LocalEntity(String qid, NodeKind kind, String label, Instant mintedAt)
    implements LoggedAssertion {

  /**
   * The floor of the local-entity band, read out of a leading-zero qid's trailing digits as one
   * integer - {@code Q0900001} reads as {@code 900001}, not {@code 1}. Fixture's constants (ADR 58)
   * run {@code Q0900001}-{@code Q0900015}, i.e. 900001-900015; this sits far enough above that
   * ceiling that Fixture growing by a few more entries does not collide with it.
   */
  static final long LOCAL_ENTITY_MIN = 900_020L;

  private static final Pattern UNALLOCATABLE_TAIL = Pattern.compile("Q0(\\d*)");

  public LocalEntity {
    Objects.requireNonNull(qid, "qid");
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(label, "label");
    Objects.requireNonNull(mintedAt, "mintedAt");
    checkLocalBand(qid);
  }

  /** The projection's view of this claim - no {@code instanceOf}, because no source stated any. */
  public NodeRecord toNode() {
    return new NodeRecord(qid, kind, label, List.of());
  }

  /**
   * Refuse anything Wikidata could allocate, and anything it could not that still falls outside the
   * local-entity band (most likely one of ADR 58's fixture stand-ins). Shared with {@link SameAs},
   * whose local side is the same claim by a different name.
   */
  static void checkLocalBand(String qid) {
    Qid.check(qid);
    if (Qid.isAllocatable(qid)) {
      throw new IllegalArgumentException(
          "a local entity's qid must not be allocatable by Wikidata, got: " + qid);
    }
    Matcher tail = UNALLOCATABLE_TAIL.matcher(qid);
    if (!tail.matches()
        || tail.group(1).isEmpty()
        || Long.parseLong(tail.group(1)) < LOCAL_ENTITY_MIN) {
      throw new IllegalArgumentException(
          "a local entity's qid must be in the local-entity band (Q0900020 and up), got: " + qid);
    }
  }
}
