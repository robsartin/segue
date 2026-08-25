package com.robsartin.segue.wikidata;

/** Wikidata could not be reached, or refused, after retries. Callers degrade; they do not crash. */
public final class WikidataUnavailableException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public WikidataUnavailableException(String message) {
    super(message);
  }

  public WikidataUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
