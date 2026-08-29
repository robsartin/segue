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
 * <p><b>Promotion is one half of issue #106; suppression is the other.</b> The distribution that
 * held suppression back has since changed: a later pass rated <b>72 of 177</b> candidates below
 * neutral in one sitting, which is not two data points. {@link #suppressed} is the rest of the
 * issue.
 */
public final class KnownList {

  /**
   * The rating at or above which an entity counts as known. A judgement rather than a measurement
   * (issue #106) — the ADR that follows says so.
   */
  public static final int PROMOTION_RATING = 4;

  /**
   * The rating at or below which an entity is suppressed — excluded from recommendations rather
   * than merely left unweighted. 2, not 3: {@code Recommendations.NEUTRAL_RATING} is 3, so a 3
   * scores identically to an unrated entity under {@code regardFor} and is not a rejection.
   * Suppressing it would silently remove every neutral rating from future recommendations, not just
   * the 72 the owner actually rated down — measured against a real 177-rating pass, that would have
   * been 117 entities gone for no reason.
   */
  public static final int SUPPRESSION_RATING = 2;

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

  /**
   * Every entity rated at or below {@link #SUPPRESSION_RATING} — "stop offering this back", not a
   * weight.
   *
   * <p><b>Suppression, not negative weighting.</b> {@code Recommendations.regardFor} centres on
   * {@code NEUTRAL_RATING} (3), so its lowest weight — a rating of 1 — is {@code 1/3}: still
   * positive. Admitting a disliked entity to the known-list would make it multiply, and so
   * <em>boost</em>, whatever it connects to, which is backwards. A genuine negative signal would
   * need weights below zero and would rewrite ADR 45's arithmetic; excluding the entity from the
   * candidate pool entirely says "not this" without touching that arithmetic at all.
   *
   * <p><b>Deliberately not part of the known-list.</b> {@code CandidateSweep.over} takes this as
   * its own parameter rather than folding it into {@code known} — the sweep reports {@code
   * knownFound} and {@code knownMissing}, and a rejected entity is not "known"; unioning it in
   * would corrupt what those two counts describe.
   *
   * <p>The result is a {@code Set}: nothing about suppression is ordered, unlike {@link #promoted},
   * whose list order feeds a deterministic downstream sweep.
   *
   * @param ratings qid to a rating from 1 to 5, for the entities that have one
   */
  public static Set<String> suppressed(Map<String, Integer> ratings) {
    Objects.requireNonNull(ratings, "ratings");

    Set<String> rejected = new LinkedHashSet<>();
    for (Map.Entry<String, Integer> rating : ratings.entrySet()) {
      if (rating.getValue() <= SUPPRESSION_RATING) {
        rejected.add(rating.getKey());
      }
    }
    return Set.copyOf(rejected);
  }

  /**
   * Every qid a revision pass may deal at some rating: the known-list plus {@link #suppressed}.
   *
   * <p><b>Lives here, not in {@code Deck} or {@code RateRun}, for the reason this class exists at
   * all</b> — issue #106 already split "what you have" into two populations computed from the same
   * {@code ratings} map, and writing their union at each caller is the same mistake one layer down:
   * two independent copies that agree only because nobody has changed either one yet. {@code
   * Deck.dealRevision} needs this set to select revisable cards; {@code RateRun.buildDeck} needs
   * the identical set to count them before dealing, and a third contributing set added to one copy
   * and not the other is exactly how "121 up for reconsideration, 84 cards to rate" happened the
   * first time (issue #109) — this method is what keeps that from happening a second way.
   *
   * <p>A suppressed entity is never itself known — {@link #suppressed}'s own javadoc explains why
   * the two sets stay separate everywhere else — but a revision pass is the one place that
   * distinction does not matter: both a known entity and a suppressed one are simply "rated, and so
   * revisable", and this is the union of the only two ways to be rated and reachable.
   *
   * @param known the composed known-list, {@link #promoted}'s result — not the raw {@code --known}
   *     file, so a promoted entity is revisable too
   * @param ratings the same note-free bulk read {@link #suppressed} reads
   */
  public static Set<String> revisitable(List<String> known, Map<String, Integer> ratings) {
    Objects.requireNonNull(known, "known");
    Objects.requireNonNull(ratings, "ratings");

    Set<String> revisitable = new LinkedHashSet<>(known);
    revisitable.addAll(suppressed(ratings));
    return Set.copyOf(revisitable);
  }
}
