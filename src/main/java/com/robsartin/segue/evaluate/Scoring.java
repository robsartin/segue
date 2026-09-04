package com.robsartin.segue.evaluate;

import com.robsartin.segue.domain.Recommendation;
import com.robsartin.segue.domain.Recommendations;
import com.robsartin.segue.recommend.Sweep;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.Set;

/**
 * One sweep in, one row of the table out. A pure function of its arguments (ADR 65).
 *
 * <p>The sweep it reads was run with suppression <b>withheld</b> — {@code CandidateSweep.over} was
 * given the merges alone — so the entities the owner rated down are in the pool and can be ranked.
 * That is the only way to answer "where would the ranking have offered them", which is a question
 * about a ranking ADR 50 makes it impossible to see.
 */
public final class Scoring {

  private Scoring() {}

  /**
   * Read one setting.
   *
   * @param sweep the candidates that setting produced, suppression withheld
   * @param heldOut the entities hidden from the known-list for this run
   * @param negatives the entities rated at or below {@code KnownList.SUPPRESSION_RATING}
   * @param top how many candidates a run would have shown
   */
  public static Reading read(
      Sweep sweep, Setting setting, Set<String> heldOut, Set<String> negatives, int top) {
    Objects.requireNonNull(sweep, "sweep");
    Objects.requireNonNull(setting, "setting");
    Objects.requireNonNull(heldOut, "heldOut");
    Objects.requireNonNull(negatives, "negatives");

    List<Recommendation> ranked = Recommendations.rank(sweep.candidates(), top);
    List<Integer> hitRanks = ranksOf(ranked, heldOut);
    List<Integer> negativeRanks = ranksOf(ranked, negatives);

    return new Reading(
        setting,
        sweep.candidates().size(),
        (int) sweep.candidates().stream().filter(in(heldOut)).count(),
        hitRanks.size(),
        mean(hitRanks),
        negativeRanks.size(),
        mean(negativeRanks));
  }

  private static java.util.function.Predicate<Recommendation> in(Set<String> wanted) {
    return candidate -> wanted.contains(candidate.entity().qid());
  }

  private static List<Integer> ranksOf(List<Recommendation> ranked, Set<String> wanted) {
    List<Integer> ranks = new ArrayList<>();
    for (int i = 0; i < ranked.size(); i++) {
      if (wanted.contains(ranked.get(i).entity().qid())) {
        ranks.add(i + 1);
      }
    }
    return List.copyOf(ranks);
  }

  private static OptionalDouble mean(List<Integer> ranks) {
    return ranks.stream().mapToInt(Integer::intValue).average();
  }
}
