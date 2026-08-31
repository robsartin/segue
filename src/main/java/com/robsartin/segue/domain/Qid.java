package com.robsartin.segue.domain;

import java.util.regex.Pattern;

/**
 * What a Wikidata QID looks like, and the one check that says so (ADR 22).
 *
 * <p><b>Split out for the same reason {@link RatingScale} was, and by the same bug.</b> A record's
 * compact constructor is a fine place to keep an invariant right up until something writes the same
 * table without building the record. {@code AffinityStore.updateRating} is exactly that: it exists
 * so the rating deck can change a rating without touching the note column, and when it replaced
 * {@code put(new AffinityRecord(...))} it carried the rating half of that constructor's validation
 * across and left the qid half behind. The deck answered 204 to {@code {"qid":"junk"}} and wrote
 * the row.
 *
 * <p>That row is worse than itself. {@code affinity} has no {@code CHECK} constraint, so SQLite
 * accepts it, and every later read that reconstructs an {@code AffinityRecord} throws — past a
 * {@code catch (SQLException)} that cannot see an {@code IllegalArgumentException}. The listing
 * tool and the export overlay break permanently, on the one table with no source to regenerate
 * from.
 *
 * <p><b>Scope of this class.</b> The three {@code domain} records that carry a qid delegate here,
 * so the rule and its message have one definition for the types that own it. Several classes
 * outside {@code domain} — in {@code wikidata}, {@code seed}, {@code mcp}, {@code support} and
 * {@code retract} — still spell the same regex themselves; they are validating arriving external
 * input rather than a domain type's invariant, and sweeping them is a change of its own.
 */
public final class Qid {

  private static final Pattern PATTERN = Pattern.compile("Q\\d+");

  /**
   * Wikibase's own {@code ItemId} grammar (ADR 58): {@code Q[1-9]\d{0,9}}. The first digit after
   * {@code Q} may never be zero, so this is narrower than {@link #PATTERN} - every allocatable qid
   * matches it, and a leading-zero stand-in never can.
   */
  private static final Pattern ALLOCATABLE = Pattern.compile("Q[1-9]\\d*");

  private Qid() {}

  /** Whether this string is a QID, for callers that check rather than refuse. */
  public static boolean looksLikeAQid(String qid) {
    return qid != null && PATTERN.matcher(qid).matches();
  }

  /** Refuse anything that is not a QID, with the message every caller of this rule has used. */
  public static void check(String qid) {
    if (!looksLikeAQid(qid)) {
      throw new IllegalArgumentException("qid must look like Q12345, got: " + qid);
    }
  }

  /**
   * Whether Wikidata could allocate this id, now or ever - ADR 58's grammar fact, not a lookup. A
   * leading-zero qid such as {@code Q0900042} is well-formed ({@link #looksLikeAQid} is true) but
   * never allocatable, which is what lets {@link LocalEntity} and {@link SameAs} reuse the shape
   * without borrowing an identifier that belongs, or could ever belong, to something else.
   */
  public static boolean isAllocatable(String qid) {
    return qid != null && ALLOCATABLE.matcher(qid).matches();
  }

  /** Refuse anything Wikidata could not allocate - the other half of ADR 58's grammar fact. */
  public static void checkAllocatable(String qid) {
    if (!isAllocatable(qid)) {
      throw new IllegalArgumentException(
          "qid must be allocatable by Wikidata (no leading zero), like Q12345, got: " + qid);
    }
  }
}
