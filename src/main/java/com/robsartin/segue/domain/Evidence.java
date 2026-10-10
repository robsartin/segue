package com.robsartin.segue.domain;

/** Which kind of evidence an {@link Interest} rests on (#351). */
public enum Evidence {
  /** The owner rated the entity. An explicit rating always wins over anything inferred. */
  RATED,
  /** No rating, but a listening source reports enough to say "probably yes". */
  INFERRED,
  /** Nothing is known: neutral, and weighs exactly what an unrated entity always has. */
  NONE
}
