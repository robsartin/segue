package com.robsartin.segue.domain;

/**
 * The 1-5 scale itself (ADR 39) — the bounds, and the one range check that reads them.
 *
 * <p><b>Split out of {@link AffinityRecord} because a scale is not a rating.</b> The constants used
 * to live on the record, which made every class that needed to say "1 to 5" — a usage string, a
 * flag validator — depend on the type that <em>carries a rating value</em>. Two ArchUnit rules
 * exist to stop that type spreading through {@code rate}, and {@code RateCli} was slipping past
 * them only because javac inlines a compile-time {@code int} constant: the reference vanishes from
 * the bytecode ArchUnit reads, so the fence saw no dependency while the source plainly had one. A
 * fence that passes for a reason nobody can see from the source is a fence the next person will
 * misread.
 *
 * <p>So the bounds live here instead, in a class that holds no rating, no qid and no note, and that
 * anything may legitimately depend on. {@link AffinityRecord} reads them for its own compact
 * constructor, and is still the only type that carries a value on this scale.
 */
public final class RatingScale {

  /** The inclusive bounds of the scale (ADR 39). Negative affinity is 1-2, not a separate axis. */
  public static final int MIN = 1;

  public static final int MAX = 5;

  private RatingScale() {}

  /**
   * Refuse anything off the scale.
   *
   * <p><b>The message deliberately does not name the rejected value</b>, unlike every other
   * validation message in this package. Affinity is personal data that ADR 33 keeps out of every
   * log line, and an exception message is the string on this path most likely to end up in one. The
   * caller knows what it sent; the log does not need to.
   */
  public static void check(int rating) {
    if (rating < MIN || rating > MAX) {
      throw new IllegalArgumentException("rating must be an integer from 1 to 5");
    }
  }
}
