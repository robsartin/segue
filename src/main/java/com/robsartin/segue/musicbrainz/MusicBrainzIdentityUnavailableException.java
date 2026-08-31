package com.robsartin.segue.musicbrainz;

/**
 * The MBID-to-QID bridge could not answer, as distinct from answering that it holds no mapping.
 *
 * <p><b>Why this is not {@link MusicBrainzUnavailableException}.</b> That one means MusicBrainz did
 * not answer. This one means whatever is behind {@link MusicBrainzIdentity} did not — and in the
 * shipped wiring that is Wikidata's Query Service, not MusicBrainz at all. Collapsing the two would
 * put a source's name on an outage in a different source. {@code musicbrainz} may not name the
 * service actually behind the seam (ADR 32 forbids it importing another adapter), so this type is
 * named for the seam rather than for whoever implements it.
 *
 * <p><b>Why the seam needs a failure channel at all</b> (<a
 * href="https://github.com/robsartin/segue/issues/148">issue #148</a>). Without one, a bridge that
 * cannot reach its backing service has only the empty answer to return, and the empty answer
 * already means something else: ADR 22 clause 2 declining to reach a neighbour, which is normal
 * operation for 49% of them. So an outage read downstream as "this artist has no members" — a
 * failure that looks like a successful empty result — and {@code ExpandResult.sourceUnavailable}
 * stayed false while it did. ADR 54 records that as an established consequence; this type closes
 * it.
 *
 * <p><b>It is thrown across the seam and caught immediately</b>, by {@code
 * MusicBrainzSourceAdapter.expand}, which turns it into the flagged empty result {@link
 * com.robsartin.segue.port.SourceAdapter#expand} requires. Nothing above the adapter ever sees it,
 * so the SPI's "failures degrade rather than propagate" contract is unchanged — what changes is
 * that the adapter now has something to degrade *from*.
 */
public final class MusicBrainzIdentityUnavailableException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public MusicBrainzIdentityUnavailableException(String message) {
    super(message);
  }

  public MusicBrainzIdentityUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
