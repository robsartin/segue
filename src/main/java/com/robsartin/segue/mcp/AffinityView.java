package com.robsartin.segue.mcp;

import java.time.Instant;

/**
 * The wire shape of {@link com.robsartin.segue.domain.AffinityRecord} — what {@code note_affinity}
 * returns, and what {@code get_entity} carries when the entity has been rated (ADR 39, amended by
 * issue #85).
 *
 * <p><b>There is no note field, and its absence is the decision.</b> ADR 33 used to treat the whole
 * taste layer as personal data; issue #85 split it, because a rating is the known-list at higher
 * resolution — the list a model is handed already says these are the things the owner likes — while
 * a note is free text that no schema constrains. A tool result becomes a model's context, and
 * context leaves the machine, so the score crosses that line and the words do not. The note is
 * written by {@code note_affinity}, stored, and read back only by {@code ./gradlew listRatings} on
 * the owner's own machine (ADR 43).
 *
 * <p>That applies to {@code note_affinity}'s own result too, which used to echo the note straight
 * back. The caller supplied those words, so echoing them leaks nothing it did not already have —
 * and a surface that returns a note in one place is a surface where "the note is never returned" is
 * a claim with an exception in it, which is the kind of claim that grows a second one.
 *
 * <p>The qid is deliberately absent, unlike on the domain record. On {@code note_affinity} the
 * caller supplied it, and on {@code get_entity} it is already on the {@link NodeView} beside this
 * one; repeating it would be the third copy of the same identifier in one response. This also keeps
 * the view from reading like a standalone record of "who likes what", which it is not - there is
 * exactly one user.
 *
 * @param rating 1 to 5 (ADR 39). 1-2 is negative affinity, not a separate concept
 * @param updatedAt when this rating was last written — the only trace of taste drift there is,
 *     because ADR 39 chose overwrite over a history table
 */
public record AffinityView(int rating, Instant updatedAt) {}
