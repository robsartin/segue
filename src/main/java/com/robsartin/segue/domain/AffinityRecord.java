package com.robsartin.segue.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * What the user thinks of one entity: a rating, optionally a note, and when it last changed.
 *
 * <p><b>This is not an assertion, and deliberately does not look like one.</b> ADR 33 keeps the
 * taste layer separate from the world-facts layer, so this record carries no {@link Provenance}, no
 * corroboration count and no {@code llm:} prefix, and it does not implement {@link LoggedAssertion}
 * - it can never be appended to the log or projected into the graph even by accident. "Blixa
 * Bargeld was a Bad Seed from 1983 to 2003" is a claim a source makes about the world and another
 * source can contradict; "I like this" is a first-person statement with nobody to corroborate it
 * and no way to be wrong.
 *
 * <p>There is no validity window either, and that is the same distinction seen from ADR 20's angle:
 * the two time dimensions of a world fact are "true in the world from/to" and "we learned it at". A
 * rating has neither. {@code updatedAt} is a third thing - when the user last changed their mind -
 * and ADR 39 keeps exactly one row per entity, so it is the only trace of taste drift there is.
 *
 * @param qid the entity rated, on the one identity spine (ADR 22); it must already be in the graph,
 *     which {@code SegueService} checks because this record cannot see the graph
 * @param rating 1 to 5, required. Negative affinity is 1-2 rather than a separate concept (ADR 39)
 * @param note free text, or null. Optional by decision, not by omission: the rating is the part a
 *     future recommendation filters on, and forcing prose to accompany it would make rating forty
 *     things a writing exercise
 * @param updatedAt when this rating was last written
 */
public record AffinityRecord(String qid, int rating, String note, Instant updatedAt) {

  /** The inclusive bounds of the scale (ADR 39). */
  public static final int MIN_RATING = 1;

  public static final int MAX_RATING = 5;

  public AffinityRecord {
    Objects.requireNonNull(qid, "qid");
    Objects.requireNonNull(updatedAt, "updatedAt");
    if (!qid.matches("Q\\d+")) {
      throw new IllegalArgumentException("qid must look like Q12345, got: " + qid);
    }
    if (rating < MIN_RATING || rating > MAX_RATING) {
      // Deliberately does NOT name the rejected value, unlike every other message in this
      // package. Affinity is personal data that ADR 33 keeps out of every log line, and an
      // exception message is the one string in this class most likely to end up in one.
      // The caller knows what it sent; the log does not need to.
      throw new IllegalArgumentException("rating must be an integer from 1 to 5");
    }
  }
}
