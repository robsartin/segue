package com.robsartin.segue.evaluate;

import com.robsartin.segue.domain.KnownList;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;

/**
 * A deterministic slice of what the owner has rated highly, hidden — and the taste layer as it
 * would have looked before those ratings were written (issue #239, ADR 65).
 *
 * <p><b>Only a promotion can be held out, and that is arithmetic rather than a preference.</b>
 * {@link KnownList#promoted} composes the known-list as the {@code --known} file <em>plus</em>
 * everything rated at or above {@link KnownList#PROMOTION_RATING} the file does not name. Withdraw
 * a rating from an entity the file names and the file puts it straight back, so the sweep was never
 * blind to it and a hit against it would measure nothing. The eligible population is therefore
 * rated highly, absent from the file, and offerable as a candidate — the third condition asked of
 * {@code CandidateSweep.couldBeExplored} itself rather than restated here.
 *
 * <p><b>The order is the qid's and there is no randomness at all.</b> Two runs over one unchanged
 * database hold out the same entities and produce byte-identical output, which is the contract ADR
 * 43 gives the ratings listing and {@code Recommendations.rank} gives the recommender's tiebreak. A
 * random split would be reproducible only against a seed somebody remembered to record, and ADR
 * 57's finding is that a number nobody re-derives stops being re-derived.
 *
 * <p><b>A rating at or below {@link KnownList#SUPPRESSION_RATING} is never held out</b>: it is the
 * negative signal the harness reads separately, and it stays in {@link #ratingsWithout} so {@code
 * KnownList.suppressed} can still name it.
 *
 * @param heldOut the hidden entities, ascending by qid
 * @param ratingsWithout the ratings map with those entries removed — the one map the known-list,
 *     the regard function and the suppressed set are all built from, so no two of them can disagree
 * @param eligible how many entities could have been held out, which is the denominator the report
 *     states its split against
 */
public record HeldOut(List<String> heldOut, Map<String, Integer> ratingsWithout, int eligible) {

  /** Hold out one entity in five. A fifth is enough to measure and little enough to still rank. */
  public static final int EVERY = 5;

  public HeldOut {
    heldOut = List.copyOf(Objects.requireNonNull(heldOut, "heldOut"));
    ratingsWithout = Map.copyOf(Objects.requireNonNull(ratingsWithout, "ratingsWithout"));
  }

  /**
   * Split one ratings map.
   *
   * @param interval hold out every {@code interval}-th eligible entity, counting from the first
   * @param ratings the note-free bulk read, already resolved through {@code Equivalences.resolve}
   * @param onFile the qids the {@code --known} file names
   * @param couldBeOffered whether the sweep could return this entity as a candidate at all
   */
  public static HeldOut every(
      int interval,
      Map<String, Integer> ratings,
      Set<String> onFile,
      Predicate<String> couldBeOffered) {
    Objects.requireNonNull(ratings, "ratings");
    Objects.requireNonNull(onFile, "onFile");
    Objects.requireNonNull(couldBeOffered, "couldBeOffered");
    if (interval < 2) {
      throw new IllegalArgumentException(
          "holding out every "
              + interval
              + " would leave nothing to recommend from: the interval must be at least 2");
    }

    List<String> eligible = new ArrayList<>();
    for (String qid : new TreeSet<>(ratings.keySet())) {
      if (ratings.get(qid) >= KnownList.PROMOTION_RATING
          && !onFile.contains(qid)
          && couldBeOffered.test(qid)) {
        eligible.add(qid);
      }
    }

    List<String> heldOut = new ArrayList<>();
    for (int i = 0; i < eligible.size(); i += interval) {
      heldOut.add(eligible.get(i));
    }

    Map<String, Integer> without = new LinkedHashMap<>(ratings);
    heldOut.forEach(without::remove);
    return new HeldOut(heldOut, without, eligible.size());
  }
}
