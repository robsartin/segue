package com.robsartin.segue.port;

import com.robsartin.segue.domain.AffinityRecord;
import java.util.Objects;
import java.util.Optional;

/**
 * What follows a merge outside the graph (#92).
 *
 * <p>A merge is an asserted equivalence between an id the owner minted and the id Wikidata turned
 * out to have. The graph half of that is {@code IngestService}'s own: it carries the node and the
 * edges. This port is the other half — <b>everything keyed by qid that is not a world fact and is
 * therefore not rebuilt by replay</b>. Today that is exactly one thing, the owner's ratings, and
 * losing one is unrecoverable: affinity is one row per qid with no history table and no un-rate
 * (ADR 39, ADR 46).
 *
 * <p><b>Two qids and nothing else, deliberately.</b> ADR 33's fence — {@code
 * ArchitectureTest.theWorldFactLayerNeverTouchesAffinity} — forbids {@code ingest} to depend on the
 * taste layer's types at all, and that rule stays exactly as it was. What crosses the fence here is
 * an identity, not a preference: {@code ingest} cannot obtain an {@link AffinityRecord}, cannot
 * read a score and cannot read a note through this interface, so "IngestService never sees a
 * rating" remains literally true. ADR 33's amendment (#92 Task 6) is where the widening — that a
 * world-fact claim may now <em>trigger</em> an effect in the taste layer without knowing what it is
 * — belongs on the record.
 *
 * <p><b>Replay applies it too, and the first version of this port claimed the opposite.</b> That
 * claim was "carrying a rating again at every boot would replay an old rating over whatever the
 * owner has said since", and measurement contradicts it: {@link #carryingRatings} refuses to
 * overwrite a rating with a newer {@code updatedAt}, so a replayed carry over a canonical id the
 * owner has re-rated changes nothing at all. The only case it alters is a local id re-rated
 * <em>after</em> the merge, where it moves the owner's most recent word onto the canonical id.
 *
 * <p>Keeping it out of replay had a real cost the other way. Affinity is the one thing here that
 * replay does <b>not</b> rebuild, so a merge logged when nothing could carry it - by an earlier
 * build, or through a wiring that passed {@link #NONE} - would strand its rating permanently. Boot
 * replay is the only repair path there is, which is why {@code GraphProjector.project} takes one of
 * these. Its three dev-tool callers pass {@link #NONE}: an exporter that wrote a rating is what
 * {@code ArchitectureTest.theExporterOnlyReads} exists to prevent, and no rule would catch it,
 * because the write would happen in this package.
 */
@FunctionalInterface
public interface IdentityMerge {

  /**
   * A merge has been declared: carry what is keyed by {@code localQid} onto {@code canonicalQid}.
   *
   * <p>Called after the claim is in the log and in the graph, on {@code IngestService.record}'s own
   * ordering argument: the recoverable direction is for the log to be ahead. Called again by every
   * replay of that log, which is what makes an uncarried merge repairable rather than permanent.
   */
  void follow(String localQid, String canonicalQid);

  /**
   * A merge with nothing to follow it, for a caller that holds no taste layer.
   *
   * <p>Named at every call site rather than supplied by a two-argument constructor: a default would
   * make "the ratings were silently orphaned" the outcome of forgetting to wire something, and this
   * is the one part of segue that cannot be regenerated.
   */
  IdentityMerge NONE = (localQid, canonicalQid) -> {};

  /**
   * Carry the owner's rating through the equivalence.
   *
   * <p><b>The rating, and not the note.</b> {@link AffinityStore#updateRating} is the write with
   * nowhere to put free text, which is what {@code ArchitectureTest.onlyTheRatingsToolReadsANote}
   * requires of every package but {@code ratings} and {@code sqlite}. The note stays on the local
   * row, which stays where it is — a merged local id remains resolvable, so nothing is lost.
   *
   * <p><b>It never overwrites a newer rating.</b> ADR 39 keeps one row per entity and lets the
   * later rating win; a merge is a statement about identity and is not a licence to undo the
   * owner's most recent word on the canonical id. Absent, or older, and the local rating is
   * carried; newer, and it stands.
   *
   * <p>Idempotent, so re-declaring the same merge changes nothing: the second pass finds the
   * canonical rating carrying the local one's own {@code updatedAt}, which is not after itself.
   */
  static IdentityMerge carryingRatings(AffinityStore affinity) {
    Objects.requireNonNull(affinity, "affinity");
    return (localQid, canonicalQid) -> {
      Optional<AffinityRecord> minted = affinity.find(localQid);
      if (minted.isEmpty()) {
        return;
      }
      AffinityRecord carried = minted.get();
      Optional<AffinityRecord> standing = affinity.find(canonicalQid);
      if (standing.isPresent() && !carried.updatedAt().isAfter(standing.get().updatedAt())) {
        return;
      }
      affinity.updateRating(canonicalQid, carried.rating(), carried.updatedAt());
    };
  }
}
