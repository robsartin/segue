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
   *
   * <p><b>The upper bound is part of the grammar, not decoration.</b> This pattern read {@code
   * Q[1-9]\d*} until ADR 62, unbounded above, while the javadoc beside it already quoted the
   * grammar correctly - so segue accepted an eleven-digit qid Wikibase's own {@code ItemId} cannot
   * express. ADR 62 closed that and reserves the shape it opens up.
   */
  private static final Pattern ALLOCATABLE = Pattern.compile("Q[1-9]\\d{0,9}");

  /**
   * The second shape Wikibase's grammar can never allocate, reserved by ADR 62 for a merge's
   * canonical side: no leading zero, and <b>more than ten digits</b>, so it fails {@link
   * #ALLOCATABLE}'s upper bound rather than its first-digit rule. ADR 58 reserved the leading zero
   * for a stand-in <em>generally</em>, which is exactly why a merge's canonical side could not use
   * it - {@link SameAs} exists to say the local id turned out to be a real Wikidata item, so its
   * canonical side may not be shaped like a stand-in for one.
   *
   * <p><b>Not the option ADR 58 rejected.</b> That one was {@code Q2147483648}, which the grammar
   * accepts and only {@code Int32EntityId::MAX} refuses - a storage width Wikibase could migrate.
   * This shape is refused by the grammar itself, on the same footing as the leading zero.
   */
  private static final Pattern CANONICAL_STAND_IN = Pattern.compile("Q[1-9]\\d{10,}");

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
   *
   * <p>Neither side of a merge asks this question directly - both go through {@link
   * #isCanonicalSide}, which is this plus ADR 62's reserved shape. Asking here instead would put an
   * id in ADR 62's shape on <em>both</em> sides at once.
   */
  public static boolean isAllocatable(String qid) {
    return qid != null && ALLOCATABLE.matcher(qid).matches();
  }

  /**
   * Whether this id may stand on a merge's canonical side (ADR 62): allocatable, or the reserved
   * eleven-digit stand-in shape. Equivalently, {@code Q} followed by any number of digits with no
   * leading zero - which is what {@link #ALLOCATABLE} said before ADR 62 gave it its upper bound,
   * and is why nothing about a merge's behaviour changed when it got one.
   *
   * <p><b>This predicate is the exact complement of {@link LocalEntity#checkUnallocatable} over
   * {@code Q\d+}</b>, and {@link Equivalences} argues from that complement that a canonical id can
   * never be the local side of another merge. Adding a shape here without removing it there would
   * open a merge chain nothing resolves, so the two read the same predicate.
   */
  public static boolean isCanonicalSide(String qid) {
    return isAllocatable(qid) || (qid != null && CANONICAL_STAND_IN.matcher(qid).matches());
  }

  /** Refuse anything that may not stand on a merge's canonical side (ADR 62). */
  public static void checkCanonicalSide(String qid) {
    if (!isCanonicalSide(qid)) {
      throw new IllegalArgumentException(
          "a merge's canonical qid must be one Wikidata could allocate, or the eleven-digit"
              + " stand-in shape reserved for one (ADR 62) - never a leading-zero stand-in, got: "
              + qid);
    }
  }
}
