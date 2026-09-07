package com.robsartin.segue.evaluate;

import com.robsartin.segue.domain.Recommendation;
import com.robsartin.segue.domain.Recommendations;
import com.robsartin.segue.recommend.Sweep;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * One sweep in, one row of the table out. A pure function of its arguments (ADR 65).
 *
 * <p>The sweep it reads was run with suppression <b>withheld</b> — {@code CandidateSweep.over} was
 * given the merges alone — so the entities the owner rated down are in the pool and can be ranked.
 * That is the only way to answer "where would the ranking have offered them", which is a question
 * about a ranking ADR 50 makes it impossible to see.
 *
 * <p><b>Two rankings out of one sweep, and the second is a claim with a test behind it.</b> The
 * rated-down entities are ranked over the whole pool — the ranking the owner would have been shown
 * had ADR 50 never been written. The held-out entities are ranked over the pool with those removed,
 * which reproduces the shipped ranking exactly, because excluding a candidate from the pool is
 * purely subtractive: {@code CandidateSweep.over} skips an excluded qid before it accumulates any
 * evidence, and no other candidate's evidence is built from it, so no survivor's score or relative
 * order moves. That is what ADR 50 measured on the real graph, and {@code
 * SuppressionIsPurelySubtractiveTest} pins it here against a real second sweep — without which this
 * paragraph would be reasoning rather than a guarantee, and every sweep the run makes — one per
 * setting per fold — would have to be made twice.
 *
 * <p><b>It reports rank sums rather than rank means</b> (issue #268). One row of the report is a
 * sum over the folds of the split, and sums add exactly where means do not: combining per-fold
 * means would divide once per fold and multiply back, and a value a hair either side of a rounding
 * boundary would render a different tenth. {@code EvaluationReport} divides, once, over every hit
 * in the run.
 *
 * <p><b>The age split's four cells are tallied in the two passes this class already makes</b>
 * (issue #276): the pool pass counts a held-out entity's half the same membership test already
 * counts it in the pool with, and the ranked pass counts a hit's half the same walk already ranks
 * it with. Nothing is swept, ranked or walked a second time to learn it. The negatives are read
 * unsplit, because a rated-down entity is never held out and so never belongs to either half.
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
   * @param age the instant to split the held-out population by, or empty for no split
   */
  public static Reading read(
      Sweep sweep,
      Setting setting,
      Set<String> heldOut,
      Set<String> negatives,
      int top,
      Optional<RatingAge> age) {
    Objects.requireNonNull(sweep, "sweep");
    Objects.requireNonNull(setting, "setting");
    Objects.requireNonNull(heldOut, "heldOut");
    Objects.requireNonNull(negatives, "negatives");
    Objects.requireNonNull(age, "age");

    // One pass, where there were two streams: the pool is built, the held-out entities in it are
    // counted, and each one's half is tallied in the same membership test. The half is a property
    // of the entity, so nothing is swept, ranked or walked a second time to learn it (issue #276).
    List<Recommendation> shipped = new ArrayList<>();
    int heldOutInPool = 0;
    int oldInPool = 0;
    int newInPool = 0;
    for (Recommendation candidate : sweep.candidates()) {
      String qid = candidate.entity().qid();
      if (negatives.contains(qid)) {
        continue;
      }
      shipped.add(candidate);
      if (heldOut.contains(qid)) {
        heldOutInPool++;
        if (age.isPresent()) {
          if (age.get().isNew(qid)) {
            newInPool++;
          } else {
            oldInPool++;
          }
        }
      }
    }

    // The same again over the ranked top: one walk, the ranks and the halves out of it.
    List<Recommendation> shippedTop = Recommendations.rank(shipped, top);
    int hits = 0;
    int hitRankSum = 0;
    int oldHits = 0;
    int newHits = 0;
    for (int rank = 1; rank <= shippedTop.size(); rank++) {
      String qid = shippedTop.get(rank - 1).entity().qid();
      if (!heldOut.contains(qid)) {
        continue;
      }
      hits++;
      hitRankSum += rank;
      if (age.isPresent()) {
        if (age.get().isNew(qid)) {
          newHits++;
        } else {
          oldHits++;
        }
      }
    }

    List<Recommendation> withheldTop = Recommendations.rank(sweep.candidates(), top);
    List<Integer> negativeRanks = ranksOf(withheldTop, negatives);

    return new Reading(
        setting,
        shipped.size(),
        heldOutInPool,
        hits,
        hitRankSum,
        negativeRanks.size(),
        sum(negativeRanks),
        age.isPresent()
            ? new Halves(true, oldInPool, oldHits, newInPool, newHits)
            : Halves.UNSPLIT);
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

  private static int sum(List<Integer> ranks) {
    return ranks.stream().mapToInt(Integer::intValue).sum();
  }
}
