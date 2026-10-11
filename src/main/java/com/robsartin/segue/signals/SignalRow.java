package com.robsartin.segue.signals;

import java.util.Objects;

/**
 * One artist as the export step wrote them: a name and what the source says about it (#350).
 *
 * <p>The name is the Music app's spelling, which is why it is resolved rather than trusted:
 * spelling differences against Wikidata are the likely loss, and the first real run measures them.
 */
public record SignalRow(String artist, long playCount, boolean loved, boolean favorited) {

  public SignalRow {
    Objects.requireNonNull(artist, "artist");
    if (artist.isBlank()) {
      throw new IllegalArgumentException("an artist needs a name");
    }
    if (playCount < 0) {
      throw new IllegalArgumentException("a play count cannot be negative");
    }
  }
}
