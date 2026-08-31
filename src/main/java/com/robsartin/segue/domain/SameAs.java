package com.robsartin.segue.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * "This local entity turned out to be that Wikidata item." An asserted equivalence, never an edit
 * (design doc, "The merge, when Wikidata catches up"): ADR 19 makes the log append-only and ADR 44
 * makes retraction a new claim, so "replaced when Wikidata catches up" cannot rewrite the entry
 * that recorded what the owner knew before they knew the qid. A first-person {@link
 * LoggedAssertion}, on {@link Retraction}'s precedent - its own validation, no {@link Provenance}.
 *
 * <p><b>The two sides are not interchangeable.</b> {@code localQid} must be shaped like one of
 * {@link LocalEntity}'s own ids - two leading zeros, {@code Q00...} (ADR 58, issue #141) - or a
 * merge could point at another local id and build an equivalence chain nothing resolves, which is
 * the failure mode this record exists to rule out at construction rather than at projection time.
 * {@code canonicalQid} must be a real, allocatable Wikidata id, or the merge is not "Wikidata
 * caught up" at all - it is one stand-in pointing at another (including a single-leading-zero
 * stand-in, which is unallocatable but not canonical either).
 *
 * @param localQid the id the owner minted, before Wikidata had one
 * @param canonicalQid the real Wikidata id it turned out to be
 * @param assertedAt when the owner made the match. Matches are declared manually (design doc) -
 *     this is not a source's timestamp, it is the owner's own act
 */
public record SameAs(String localQid, String canonicalQid, Instant assertedAt)
    implements LoggedAssertion {

  public SameAs {
    Objects.requireNonNull(localQid, "localQid");
    Objects.requireNonNull(canonicalQid, "canonicalQid");
    Objects.requireNonNull(assertedAt, "assertedAt");
    LocalEntity.checkLocalBand(localQid);
    Qid.checkAllocatable(canonicalQid);
  }
}
