package com.robsartin.segue.mcp;

import java.time.Instant;

/**
 * The wire shape of {@link com.robsartin.segue.domain.AffinityRecord} — what {@code note_affinity}
 * returns, and what {@code get_entity} carries when the entity has been rated (ADR 39).
 *
 * <p>The qid is deliberately absent, unlike on the domain record. On {@code note_affinity} the
 * caller supplied it, and on {@code get_entity} it is already on the {@link NodeView} beside this
 * one; repeating it would be the third copy of the same identifier in one response. This also keeps
 * the view from reading like a standalone record of "who likes what", which it is not - there is
 * exactly one user.
 *
 * @param rating 1 to 5 (ADR 39). 1-2 is negative affinity, not a separate concept
 * @param note the user's own words, or null when they gave none
 * @param updatedAt when this rating was last written — the only trace of taste drift there is,
 *     because ADR 39 chose overwrite over a history table
 */
public record AffinityView(int rating, String note, Instant updatedAt) {}
