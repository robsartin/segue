package com.robsartin.segue.evaluate;

import com.robsartin.segue.domain.Equivalences;
import com.robsartin.segue.domain.KnownList;
import com.robsartin.segue.domain.Recommendations;
import com.robsartin.segue.port.GraphStore;
import com.robsartin.segue.recommend.CandidateSweep;
import com.robsartin.segue.recommend.Sweep;
import com.robsartin.segue.support.QidList;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;

/**
 * Split, sweep the grid, report — in that order, every fold of the split, and one sweep per setting
 * per fold (ADR 65, issue #268).
 *
 * <p><b>Lines go to a {@link Consumer} rather than to a logger of this class's own</b>, so the
 * whole report is observable from a test and this class has no logger to misuse — {@code
 * CensusRun}'s discipline, and {@code RatingsRun}'s and {@code SqliteAffinityStore}'s before it.
 *
 * <p><b>There is no warning to say first.</b> {@code RecommendRun} warns because what the operator
 * does next is decide where to put a file of personal data. This produces no file and no personal
 * data: the header says what the output is, and that is the whole of it.
 *
 * <p><b>One map, three consumers.</b> {@link HeldOut} hands back the ratings with the held-out
 * entries removed, and the known-list, the regard function and the suppressed set are all built
 * from that one map — the same discipline {@code RecommendCli} keeps when it resolves the merges
 * once and hands the result to both {@code regardFor} and {@code KnownList.promoted}. Two views of
 * the taste layer inside one run is how a split stops meaning what it says.
 *
 * <p><b>Every fold, and the counts are totals over them.</b> The split has {@link HeldOut#EVERY}
 * folds and this runs all of them, so every eligible entity is held out exactly once and no fold's
 * known-list is any smaller than a single fold's was (issue #268). One row per setting reaches the
 * report — {@link Reading#summed} over that setting's folds — so the counts are totals and the
 * means are over every hit in the run. It costs {@code HeldOut.EVERY} times the sweeps; the boot,
 * the projection and the sweep's memoised degrees are still paid once, because none of them depends
 * on the known-list.
 *
 * <p><b>The graph is booted once and one {@link CandidateSweep} is reused across the grid</b>, so
 * the replay is paid for once and the sweep's memoised degrees are paid for once. ADR 45's
 * consequences record what a single recommendation run costs against the real graph; do not
 * re-project per setting.
 *
 * <p><b>It reads and cannot write.</b> {@code ArchitectureTest.theEvaluationHarnessOnlyReads}
 * forbids this package the three world-fact writes, both taste-layer writes and {@code
 * IngestService}.
 *
 * <p><b>The rating-age halves are an observation, not a second split.</b> {@link RatingAge} never
 * reaches {@link EvaluationReport} — this class tallies {@code oldHeldOut}/{@code newHeldOut} over
 * the same fold loop that already builds {@code heldOut}, and passes {@code age} through to {@link
 * Scoring#read} so each setting's row tallies its own halves in the passes it already makes (issue
 * #276). A run given no instant is unchanged: {@code age} is empty, the two counts stay zero, and
 * {@link EvaluationReport#lines} renders exactly as it did before this issue.
 */
public final class EvaluateRun {

  private final GraphStore graph;
  private final Predicate<String> recognitionInstitutionClass;
  private final Map<String, Integer> ratings;
  private final Equivalences merges;
  private final Optional<RatingAge> age;

  /**
   * @param ratings the note-free bulk read, already resolved through {@code Equivalences.resolve}
   * @param merges what the owner has merged — passed to the sweep as the only exclusion, because
   *     withholding {@code KnownList.suppressed} is the whole point and a retired local id is not a
   *     judgement the harness is measuring
   * @param age the instant to split the held-out population by, or empty for no split
   */
  public EvaluateRun(
      GraphStore graph,
      Predicate<String> recognitionInstitutionClass,
      Map<String, Integer> ratings,
      Equivalences merges,
      Optional<RatingAge> age) {
    this.graph = Objects.requireNonNull(graph, "graph");
    this.recognitionInstitutionClass =
        Objects.requireNonNull(recognitionInstitutionClass, "recognitionInstitutionClass");
    this.ratings = Objects.requireNonNull(ratings, "ratings");
    this.merges = Objects.requireNonNull(merges, "merges");
    this.age = Objects.requireNonNull(age, "age");
  }

  /**
   * Run the whole grid.
   *
   * @return the readings that were printed, so a caller can assert on the numbers without parsing
   *     the text back
   */
  public List<Reading> run(Path known, int top, Consumer<String> lines) {
    Objects.requireNonNull(known, "known");
    Objects.requireNonNull(lines, "lines");

    List<String> fromFile = QidList.read(known);
    Set<String> onFile = new LinkedHashSet<>(fromFile);
    CandidateSweep sweep = new CandidateSweep(graph, recognitionInstitutionClass);

    List<List<Reading>> bySetting = new ArrayList<>();
    for (int settingIndex = 0; settingIndex < Setting.GRID.size(); settingIndex++) {
      bySetting.add(new ArrayList<>());
    }

    int eligible = 0;
    int heldOutTotal = 0;
    int leastLeft = 0;
    int oldHeldOut = 0;
    int newHeldOut = 0;
    for (int fold = 0; fold < HeldOut.EVERY; fold++) {
      HeldOut split = HeldOut.every(HeldOut.EVERY, fold, ratings, onFile, sweep::couldBeExplored);
      List<String> knownList = KnownList.promoted(fromFile, split.ratingsWithout());
      ToDoubleFunction<String> regard = Recommendations.regardFor(split.ratingsWithout());
      Set<String> negatives = KnownList.suppressed(split.ratingsWithout());
      Set<String> heldOut = Set.copyOf(split.heldOut());

      // The same in every fold: the eligibility rule does not read the offset.
      eligible = split.eligible();
      heldOutTotal += split.heldOut().size();
      int left = split.eligible() - split.heldOut().size();
      leastLeft = fold == 0 ? left : Math.min(leastLeft, left);

      // The folds partition the eligible population, so summing each fold's held-out entities by
      // half over the run IS the eligible population's two halves — the same identity the header
      // already shows by printing "held out over all folds" beside "eligible" (issue #276). It
      // needs no second accessor on HeldOut, and HeldOut.every keeps the signature it has.
      if (age.isPresent()) {
        for (String qid : split.heldOut()) {
          if (age.get().isNew(qid)) {
            newHeldOut++;
          } else {
            oldHeldOut++;
          }
        }
      }

      for (int i = 0; i < Setting.GRID.size(); i++) {
        Setting setting = Setting.GRID.get(i);
        // Suppression withheld on purpose: merges.merged() and nothing else, so the rated-down
        // entities are in the pool and can be ranked. Scoring filters them back out for the
        // held-out reading.
        Sweep swept =
            sweep.over(knownList, merges.merged(), setting.scorer(), setting.floor(), regard);
        bySetting.get(i).add(Scoring.read(swept, setting, heldOut, negatives, top, age));
      }
    }

    List<Reading> readings = bySetting.stream().map(Reading::summed).toList();
    EvaluationReport.lines(
            eligible,
            HeldOut.EVERY,
            heldOutTotal,
            leastLeft,
            top,
            age.map(RatingAge::since),
            oldHeldOut,
            newHeldOut,
            readings)
        .forEach(lines);
    return List.copyOf(readings);
  }
}
