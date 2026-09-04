package com.robsartin.segue.evaluate;

import com.robsartin.segue.domain.Equivalences;
import com.robsartin.segue.domain.KnownList;
import com.robsartin.segue.domain.Recommendations;
import com.robsartin.segue.port.GraphStore;
import com.robsartin.segue.recommend.CandidateSweep;
import com.robsartin.segue.recommend.Sweep;
import com.robsartin.segue.support.QidList;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;

/**
 * Split, sweep the grid, report — in that order, and one sweep per setting (ADR 65).
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
 * <p><b>The graph is booted once and one {@link CandidateSweep} is reused across the grid</b>, so
 * the replay is paid for once and the sweep's memoised degrees are paid for once. ADR 45's
 * consequences record what a single recommendation run costs against the real graph; do not
 * re-project per setting.
 *
 * <p><b>It reads and cannot write.</b> {@code ArchitectureTest.theEvaluationHarnessOnlyReads}
 * forbids this package the three world-fact writes, both taste-layer writes and {@code
 * IngestService}.
 */
public final class EvaluateRun {

  private final GraphStore graph;
  private final Predicate<String> recognitionInstitutionClass;
  private final Map<String, Integer> ratings;
  private final Equivalences merges;

  /**
   * @param ratings the note-free bulk read, already resolved through {@code Equivalences.resolve}
   * @param merges what the owner has merged — passed to the sweep as the only exclusion, because
   *     withholding {@code KnownList.suppressed} is the whole point and a retired local id is not a
   *     judgement the harness is measuring
   */
  public EvaluateRun(
      GraphStore graph,
      Predicate<String> recognitionInstitutionClass,
      Map<String, Integer> ratings,
      Equivalences merges) {
    this.graph = Objects.requireNonNull(graph, "graph");
    this.recognitionInstitutionClass =
        Objects.requireNonNull(recognitionInstitutionClass, "recognitionInstitutionClass");
    this.ratings = Objects.requireNonNull(ratings, "ratings");
    this.merges = Objects.requireNonNull(merges, "merges");
  }

  /**
   * Run the whole grid.
   *
   * @return the readings that were printed, so a caller can assert on the numbers without parsing
   *     the text back
   */
  public List<Reading> run(Path known, int top, Consumer<String> lines) throws IOException {
    Objects.requireNonNull(known, "known");
    Objects.requireNonNull(lines, "lines");

    List<String> fromFile = QidList.read(known);
    CandidateSweep sweep = new CandidateSweep(graph, recognitionInstitutionClass);
    HeldOut split =
        HeldOut.every(
            HeldOut.EVERY, ratings, new LinkedHashSet<>(fromFile), sweep::couldBeExplored);

    List<String> knownList = KnownList.promoted(fromFile, split.ratingsWithout());
    ToDoubleFunction<String> regard = Recommendations.regardFor(split.ratingsWithout());
    Set<String> negatives = KnownList.suppressed(split.ratingsWithout());
    Set<String> heldOut = Set.copyOf(split.heldOut());

    List<Reading> readings = new ArrayList<>();
    for (Setting setting : Setting.GRID) {
      // Suppression withheld on purpose: merges.merged() and nothing else, so the rated-down
      // entities are in the pool and can be ranked. Scoring filters them back out for the
      // held-out reading.
      Sweep swept =
          sweep.over(knownList, merges.merged(), setting.scorer(), setting.floor(), regard);
      readings.add(Scoring.read(swept, setting, heldOut, negatives, top));
    }

    EvaluationReport.lines(split.eligible(), split.heldOut().size(), top, readings).forEach(lines);
    return List.copyOf(readings);
  }
}
