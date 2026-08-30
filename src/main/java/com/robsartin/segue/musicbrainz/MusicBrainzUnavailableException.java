package com.robsartin.segue.musicbrainz;

/**
 * MusicBrainz could not be reached, or refused, after retries — or a fixture read for a test could
 * not be read or parsed. Callers degrade; they do not crash. Modelled on {@code
 * wikidata.WikidataUnavailableException}: one failure type from this package, so callers are not
 * asked to catch several.
 */
public final class MusicBrainzUnavailableException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public MusicBrainzUnavailableException(String message) {
    super(message);
  }

  public MusicBrainzUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
