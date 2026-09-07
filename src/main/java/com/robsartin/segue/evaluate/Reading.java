package com.robsartin.segue.evaluate;

import java.util.List;
import java.util.Objects;

/**
 * What one setting's sweep said about the held-out entities and the rated-down ones (ADR 65).
 *
 * <p>Every field is a count or a mean of ranks. Nothing here names an entity, which is what makes
 * the report over it safe to paste (ADR 51, ADR 63).
 *
 * <p><b>A row summed over folds counts two different things at two different scales</b> (issue
 * #268). {@link #hits} and {@link #heldOutInPool} count entities: the folds of one split partition
 * the eligible population, so a held-out entity is counted in exactly one fold and the summed value
 * is still a count of distinct entities. {@link #pool} and {@link #negativesOffered} count
 * entity-fold offers instead, because a rated-down entity is never held out and so is offered again
 * in every fold: one such entity summed over {@code HeldOut.EVERY} folds reads as {@code
 * HeldOut.EVERY}, not one. The two pairs are not on the same scale, and a mean taken over one is
 * not the same kind of mean as one taken over the other.
 *
 * @param setting which scorer and which floor produced it
 * @param pool how many candidates cleared the floor, the rated-down ones removed — the pool the
 *     recommender would actually have ranked, in one fold. Summed over folds this is an entity-fold
 *     count rather than a pool anybody ranked (see above)
 * @param heldOutInPool how many held-out entities are in that pool at all, whatever their rank. A
 *     hit count with no denominator says nothing: an entity below the floor and an entity ranked
 *     900th are different failures. Summed over folds this stays an entity count (see above)
 * @param hits how many held-out entities the top N names. Summed over folds this stays an entity
 *     count too: the folds partition the held-out set, so each entity is counted once
 * @param hitRankSum the sum of those 1-based ranks — a sum rather than a mean, so that folds add
 *     exactly (issue #268). The report divides it by {@link #hits} once, over every hit in the run.
 *     Zero when there are none, which is why the report reads the count and not this field to
 *     decide on the dash: no hits and a mean rank of zero are still different facts
 * @param negativesOffered how many entities rated at or below {@code KnownList.SUPPRESSION_RATING}
 *     the ranking would have offered in the top N with suppression off (ADR 50), in one fold.
 *     Summed over folds this is an entity-fold count (see above): a rated-down entity is never held
 *     out, so it is offered once in every fold
 * @param negativeRankSum the sum of those 1-based ranks, on the same terms
 * @param halves the age split's four cells, or {@link Halves#UNSPLIT} when no instant was given.
 *     {@link Halves} is the authority on what these cells mean and how they add; this record only
 *     carries one
 */
public record Reading(
    Setting setting,
    int pool,
    int heldOutInPool,
    int hits,
    int hitRankSum,
    int negativesOffered,
    int negativeRankSum,
    Halves halves) {

  public Reading {
    Objects.requireNonNull(setting, "setting");
    Objects.requireNonNull(halves, "halves");
  }

  /**
   * One row of the report from one reading per fold (issue #268).
   *
   * <p><b>Counts add, rank sums add, and nothing here divides.</b> The report takes the single
   * division, over every hit in the run — see {@link #hitRankSum}. The folds of one split are
   * disjoint, so a held-out entity is counted in exactly one of them and a total is a total rather
   * than an overlap.
   *
   * <p><b>It refuses folds of two different settings</b>, which is the one transposition this
   * arithmetic is exposed to: summing down the grid instead of across the folds would produce a
   * table that looks entirely plausible and means nothing.
   *
   * @param folds one reading per fold, all of one setting, at least one
   */
  public static Reading summed(List<Reading> folds) {
    Objects.requireNonNull(folds, "folds");
    if (folds.isEmpty()) {
      throw new IllegalArgumentException(
          "no folds to sum: a row of the report is one setting over at least one fold");
    }

    Setting setting = folds.get(0).setting();
    Halves halves = new Halves(folds.get(0).halves().split(), 0, 0, 0, 0);
    int pool = 0;
    int heldOutInPool = 0;
    int hits = 0;
    int hitRankSum = 0;
    int negativesOffered = 0;
    int negativeRankSum = 0;
    for (Reading fold : folds) {
      if (!setting.equals(fold.setting())) {
        throw new IllegalArgumentException(
            "readings of two different settings cannot be summed: one row of the report is one"
                + " setting read over every fold of the split");
      }
      pool += fold.pool();
      heldOutInPool += fold.heldOutInPool();
      hits += fold.hits();
      hitRankSum += fold.hitRankSum();
      negativesOffered += fold.negativesOffered();
      negativeRankSum += fold.negativeRankSum();
      halves = halves.plus(fold.halves());
    }
    return new Reading(
        setting, pool, heldOutInPool, hits, hitRankSum, negativesOffered, negativeRankSum, halves);
  }
}
