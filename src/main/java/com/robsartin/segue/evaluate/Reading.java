package com.robsartin.segue.evaluate;

import java.util.Objects;

/**
 * What one setting's sweep said about the held-out entities and the rated-down ones (ADR 65).
 *
 * <p>Every field is a count or a mean of ranks. Nothing here names an entity, which is what makes
 * the report over it safe to paste (ADR 51, ADR 63).
 *
 * @param setting which scorer and which floor produced it
 * @param pool how many candidates cleared the floor, the rated-down ones removed — the pool the
 *     recommender would actually have ranked
 * @param heldOutInPool how many held-out entities are in that pool at all, whatever their rank. A
 *     hit count with no denominator says nothing: an entity below the floor and an entity ranked
 *     900th are different failures
 * @param hits how many held-out entities the top N names
 * @param hitRankSum the sum of those 1-based ranks — a sum rather than a mean, so that folds add
 *     exactly (issue #268). The report divides it by {@link #hits} once, over every hit in the run.
 *     Zero when there are none, which is why the report reads the count and not this field to
 *     decide on the dash: no hits and a mean rank of zero are still different facts
 * @param negativesOffered how many entities rated at or below {@code KnownList.SUPPRESSION_RATING}
 *     the ranking would have offered in the top N with suppression off (ADR 50)
 * @param negativeRankSum the sum of those 1-based ranks, on the same terms
 */
public record Reading(
    Setting setting,
    int pool,
    int heldOutInPool,
    int hits,
    int hitRankSum,
    int negativesOffered,
    int negativeRankSum) {

  public Reading {
    Objects.requireNonNull(setting, "setting");
  }
}
