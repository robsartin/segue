package com.robsartin.segue.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * What the owner has, which is the file plus what they have rated highly.
 *
 * <p><b>The known-list file means "acts I have seen live", and that is not what {@code --known} is
 * for.</b> It was produced from a concert history (ADR 40), so it omits everything liked but never
 * attended. Measured on the real graph: of 167 rated entities absent from the file, <b>87 are rated
 * 4 or 5</b>: bands and performers plainly liked but never attended. The recommender treated those
 * as strangers and could recommend them back.
 *
 * <p><b>Promotion only, and the distribution is why.</b> The same 167 hold exactly two ratings
 * below neutral, so a suppression rule would ship against two data points. Issue #106 records that
 * as deliberately not built rather than overlooked.
 */
public final class KnownList {

  /**
   * The rating at or above which an entity counts as known. A judgement rather than a measurement
   * (issue #106) — the ADR that follows says so.
   */
  public static final int PROMOTION_RATING = 4;

  private KnownList() {}

  /**
   * The file's entities, in the file's order, followed by everything rated {@link
   * #PROMOTION_RATING} or higher that the file does not already name.
   *
   * <p>The promoted portion is sorted, deliberately: {@code Map} iteration order is not guaranteed,
   * and this list feeds {@code CandidateSweep}'s known-list filter, so two runs over the same
   * ratings must produce the same list. {@code Recommendations.rank} makes the same argument for
   * its own qid tiebreak, crediting ADR 43, whose comparators end in {@code qid} so that two runs
   * over an unchanged table produce byte-identical files. (This comment cited ADR 45 until the
   * review of issue #106; ADR 45 makes no such argument.)
   */
  public static List<String> promoted(List<String> fromFile, Map<String, Integer> ratings) {
    Objects.requireNonNull(fromFile, "fromFile");
    Objects.requireNonNull(ratings, "ratings");

    Set<String> onFile = new LinkedHashSet<>(fromFile);

    List<String> promotions = new ArrayList<>();
    for (Map.Entry<String, Integer> rating : ratings.entrySet()) {
      if (rating.getValue() >= PROMOTION_RATING && !onFile.contains(rating.getKey())) {
        promotions.add(rating.getKey());
      }
    }
    promotions.sort(Comparator.naturalOrder());

    List<String> known = new ArrayList<>(fromFile);
    known.addAll(promotions);
    return List.copyOf(known);
  }
}
