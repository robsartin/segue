package com.robsartin.segue.evaluate;

import java.util.Objects;
import java.util.OptionalDouble;

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
 * @param meanHitRank the mean 1-based rank of those, absent when there are none. Absent rather than
 *     zero, because zero is a rank a reader would compare against
 * @param negativesOffered how many entities rated at or below {@code KnownList.SUPPRESSION_RATING}
 *     the ranking would have offered in the top N with suppression off (ADR 50)
 * @param meanNegativeRank the mean 1-based rank of those, absent when there are none
 */
public record Reading(
    Setting setting,
    int pool,
    int heldOutInPool,
    int hits,
    OptionalDouble meanHitRank,
    int negativesOffered,
    OptionalDouble meanNegativeRank) {

  public Reading {
    Objects.requireNonNull(setting, "setting");
    Objects.requireNonNull(meanHitRank, "meanHitRank");
    Objects.requireNonNull(meanNegativeRank, "meanNegativeRank");
  }
}
