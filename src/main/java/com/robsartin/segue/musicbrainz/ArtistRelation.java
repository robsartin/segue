package com.robsartin.segue.musicbrainz;

/**
 * One MusicBrainz {@code artist-rels} relation between the artist requested and another artist.
 *
 * <p>MusicBrainz states a relation once, on the pair, with an explicit {@code direction} — see
 * {@code docs/design/2026-08-30-three-source-adapters.md}'s MusicBrainz section. {@code
 * targetMbid}, {@code type} and {@code direction} carry that relation as MusicBrainz stated it;
 * this client does not normalise direction away, and does not decide which end is the "real"
 * source.
 *
 * <p><b>{@code begin} and {@code end} are raw, unparsed strings, deliberately.</b> MusicBrainz
 * dates are variable-precision — {@code "1960"}, {@code "1968-08"} or {@code "1960-08-12"} — and
 * only some relations reach day precision. {@code ClaimMapper.qualifierDate} (in {@code wikidata})
 * already set this project's precedent for the same problem: a date read at less than day precision
 * must not become a {@link java.time.LocalDate}, because that would feed false day-level precision
 * into {@code validAt()} time-travel queries. Applying that precedent — deciding which of {@code
 * begin}/{@code end} survives as a real date — is the adapter's job (Task 4), not this client's;
 * this record only carries what MusicBrainz sent, unmodified. {@code null} means the field was
 * absent from the response.
 */
public record ArtistRelation(
    String targetMbid,
    String type,
    String direction,
    String targetName,
    String begin,
    String end,
    Boolean ended) {}
