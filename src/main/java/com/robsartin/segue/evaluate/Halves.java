package com.robsartin.segue.evaluate;

import java.util.Objects;

/**
 * The four cells one row contributes to the age split, and the flag that says whether a split was
 * asked for at all (issue #276).
 *
 * <p><b>The flag is not avoidable, whatever shape these take.</b> A run given no instant and a run
 * whose halves happen to be all zeroes must render differently — four columns or none — and four
 * zeroes cannot say which. What a record buys over four loose fields on {@link Reading} is that the
 * flag and the counts cannot drift apart: the compact constructor refuses a count on an unsplit
 * value and {@link #plus} refuses to add across the two, so a table assembled from two views of one
 * run is a build failure rather than a plausible block of numbers.
 *
 * <p><b>Four plain integers, and none of them can ever be the dash.</b> Every one is a count rather
 * than a mean, which is what keeps {@code EvaluationReport}'s contract exactly as ADR 65 fixed it.
 *
 * <p><b>They are entity counts, on {@link Reading#hits}' scale rather than {@link
 * Reading#pool}'s.</b> The folds of one split partition the held-out set, so a held-out entity is
 * counted in exactly one fold and a summed value is still a count of distinct entities. In a summed
 * row {@code oldHits + newHits} equals {@link Reading#hits} and {@code oldInPool + newInPool}
 * equals {@link Reading#heldOutInPool}, which is how a reader checks the table against itself.
 *
 * @param split whether this reading was taken with an instant at all
 * @param oldInPool held-out entities rated before the instant that are in the pool at all
 * @param oldHits held-out entities rated before the instant that the top N names
 * @param newInPool held-out entities rated on or after the instant that are in the pool at all
 * @param newHits held-out entities rated on or after the instant that the top N names
 */
public record Halves(boolean split, int oldInPool, int oldHits, int newInPool, int newHits) {

  /** A reading of a run that was given no instant: no halves, and four cells that never render. */
  public static final Halves UNSPLIT = new Halves(false, 0, 0, 0, 0);

  public Halves {
    if (!split && (oldInPool != 0 || oldHits != 0 || newInPool != 0 || newHits != 0)) {
      throw new IllegalArgumentException(
          "a reading with no age split cannot carry a count in either half: the flag and the four"
              + " cells are one fact, and a row assembled from two of them says what neither does");
    }
  }

  /** Add one fold's cells to another's. */
  public Halves plus(Halves other) {
    Objects.requireNonNull(other, "other");
    if (split != other.split()) {
      throw new IllegalArgumentException(
          "a split reading and an unsplit one cannot be added: one run is split by rating age or"
              + " it is not, and every fold of it is read the same way");
    }
    return new Halves(
        split,
        oldInPool + other.oldInPool(),
        oldHits + other.oldHits(),
        newInPool + other.newInPool(),
        newHits + other.newHits());
  }
}
