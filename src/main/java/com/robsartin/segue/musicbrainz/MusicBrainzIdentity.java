package com.robsartin.segue.musicbrainz;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/**
 * The bridge from a MusicBrainz MBID to the Wikidata QID that {@code NodeRecord} requires, and
 * back.
 *
 * <p><b>Declared here, in {@code musicbrainz}, and implemented elsewhere.</b> {@code NodeRecord} is
 * {@code (qid, kind, label, instanceOf)} — it carries no MBID — so an adapter handed a seed QID
 * must obtain the MBID through some seam, and an adapter that discovers a neighbour by MBID must
 * resolve it back to a QID before it can become a {@code NodeRecord} at all (ADR 22 clause 2:
 * source-local identifiers, MBIDs named explicitly among them, resolve to a QID in the ingest layer
 * and never appear in the domain). Two routes can carry that mapping — MusicBrainz's own {@code
 * url-rels}, and Wikidata's {@code P434} — and this package deliberately names neither: either one
 * has to be <i>supplied</i> from outside without this package importing another adapter, or the
 * second adapter is welded to the first (ADR 25; ADR 32's adapters-are-siblings fences). So {@code
 * musicbrainz} declares the interface it needs and something outside supplies the implementation.
 * What shipped is Wikidata-backed, through {@code P434}, in {@code app} — see {@code
 * docs/design/2026-08-30-three-source-adapters.md}'s "Identity, and the bridge to a QID" section
 * and its 2026-08-30 correction, which records why that superseded the {@code url-rels} route the
 * note originally recommended, and what it cost. This type is the seam, not the bridge, and nothing
 * here is entitled to assume which one is behind it.
 *
 * <p>{@link #qidsFor} is batched rather than one call per neighbour: the alternative is a round
 * trip per neighbour, and the measured neighbourhood was 387 across 40 seeds (#91's 2026-08-29
 * comment).
 */
public interface MusicBrainzIdentity {

  /** The MBID for a seed QID, or empty if this source has no bridge for it. */
  Optional<String> mbidFor(String qid);

  /**
   * The QID for each MBID in {@code mbids} that this source can bridge. An MBID absent from the
   * result carries no QID — silently dropped, not reported as an error.
   *
   * <p><b>That dropping is ADR 22 clause 2 working as designed, not a gap.</b> Measured over
   * artist-relation neighbours, 49% (190 of 387) carry no QID at all, and a 30-entity sample of
   * that 49% was tribute bands, pseudonyms, billing variants and relatives — material the identity
   * spine is declining to reach, on purpose, rather than failing to reach.
   */
  Map<String, String> qidsFor(Collection<String> mbids);
}
