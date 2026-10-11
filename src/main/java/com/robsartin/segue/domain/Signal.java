package com.robsartin.segue.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * What a listening source says the owner does with one entity: a raw observation, not a rating
 * (#350).
 *
 * <p><b>A claim about the owner, not about the world.</b> Like an {@link AffinityRecord} it carries
 * no {@link Provenance}, is never corroborated and never reaches the graph or the log (ADR 33); it
 * is stored in its own table behind its own port. It is also <b>not</b> a rating and is never
 * written back as one — turning counts into a confidence is a separate decision (#351), and this
 * record holds only what the source reported.
 *
 * <p>One row per entity and source: {@code source} names where the counts came from ({@code
 * apple-music}), so a second source adds rows rather than overwriting these, and re-running one
 * source replaces only its own.
 *
 * <p>The counts are personal data (ADR 16, ADR 33): nothing that holds one logs it.
 *
 * @param qid the entity the observation resolved to
 * @param source the listening source's id
 * @param playCount total plays, summed over the source's tracks for the entity; never negative
 * @param loved the source's "loved" flag, true if any track carries it
 * @param favorited the source's "favorited" flag, true if any track carries it
 * @param observedAt when the source was read, which is not when anything was played
 */
public record Signal(
    String qid,
    String source,
    long playCount,
    boolean loved,
    boolean favorited,
    Instant observedAt) {

  public Signal {
    Qid.check(qid);
    Objects.requireNonNull(source, "source");
    if (source.isBlank()) {
      throw new IllegalArgumentException("a signal needs a source");
    }
    if (playCount < 0) {
      throw new IllegalArgumentException("a play count cannot be negative");
    }
    Objects.requireNonNull(observedAt, "observedAt");
  }

  /** The same entity from the same source, seen twice in one read: counts add, flags either-or. */
  public Signal plus(Signal other) {
    if (!qid.equals(other.qid) || !source.equals(other.source)) {
      throw new IllegalArgumentException("only observations of one entity and source combine");
    }
    return new Signal(
        qid,
        source,
        playCount + other.playCount,
        loved || other.loved,
        favorited || other.favorited,
        observedAt.isAfter(other.observedAt) ? observedAt : other.observedAt);
  }
}
