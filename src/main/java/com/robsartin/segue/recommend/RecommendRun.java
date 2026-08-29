package com.robsartin.segue.recommend;

import com.robsartin.segue.domain.KnownList;
import com.robsartin.segue.domain.Recommendation;
import com.robsartin.segue.domain.Recommendations;
import com.robsartin.segue.port.GraphStore;
import com.robsartin.segue.recommend.RecommendCli.Options;
import com.robsartin.segue.support.QidList;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;

/**
 * Warn, sweep, rank, explain, report, write — in that order, and the order is the design (ADR 45).
 *
 * <p><b>The warning comes first, before a candidate exists and long before the file does.</b>
 * Borrowed from {@code ExportRun} and {@code RatingsRun} for the same reason: what the operator
 * does next is decide where to put the output, and a warning that arrives after the write is a
 * warning about something that has already happened.
 *
 * <p><b>The counts arrive before the write too.</b> A recommender is a stack of filters and every
 * one of them can fail into a plausible-looking list — a known-list that resolved against a
 * different graph produces recommendations from nothing at all, and reads exactly like a short run.
 * So how many of your entities were found, how many were not, and how many hub intermediates were
 * refused all reach the operator while the output file still does not exist, which is what {@code
 * RecommendRunTest} asserts rather than trusting the reading order of this method.
 *
 * <p><b>Nothing personal goes through the notes.</b> Every note is a count or a path. The
 * recommendations themselves — which entities, reached from which of yours — go to the file and
 * only to the file, because {@code RecommendCli} routes these notes into SLF4J and this list is
 * derived from the known-list ADR 33 governs. {@code RecommendationsAreNeverLoggedTest} drives the
 * real {@code main} and asserts no log line anywhere names an entity.
 *
 * <p><b>The two halves are deliberately separate.</b> {@link CandidateSweep} scores every candidate
 * and {@link Routes} explains only the ones that will be read; this class is the only place that
 * knows both exist, and it is thin on purpose.
 */
public final class RecommendRun {

  /** How many routes to show under each candidate. */
  private static final int ROUTES_PER_CANDIDATE = 3;

  /**
   * Said before anything else, every time. Names the decision and the issue rather than saying
   * "careful", because the operator's next action — where this file goes — is what depends on it.
   */
  public static final String PERSONAL_DATA_WARNING =
      "these recommendations are derived from your known-list: the output is personal data under"
          + " ADR 33 and issue #37. Keep it outside the working tree and out of version control —"
          + " this repository is public.";

  private final GraphStore graph;
  private final Predicate<String> recognitionInstitutionClass;
  private final ToDoubleFunction<String> regard;
  private final Map<String, Integer> ratings;

  /**
   * @param regard what one known entity's connections count for. Still a function and not a store
   *     (ADR 45): issue #85 lets the recommender read ratings, and this class reads none of them —
   *     {@code RecommendCli} turns the note-free bulk read into this argument, and everything below
   *     here sees arithmetic. {@code Recommendations.EQUAL_REGARD} is what an empty {@code
   *     affinity} table produces, and what every test that is not about affinity passes
   * @param ratings the same note-free bulk read {@code regard} was built from, held here too and
   *     for a different reason: issue #106's {@link KnownList#promoted} needs the raw map to decide
   *     which qids join the known-list, which a {@code ToDoubleFunction} cannot enumerate. A rating
   *     of 4 or 5 for an entity absent from the file makes it known, so {@code CandidateSweep}
   *     stops offering it back
   */
  public RecommendRun(
      GraphStore graph,
      Predicate<String> recognitionInstitutionClass,
      ToDoubleFunction<String> regard,
      Map<String, Integer> ratings) {
    this.graph = Objects.requireNonNull(graph, "graph");
    this.recognitionInstitutionClass =
        Objects.requireNonNull(recognitionInstitutionClass, "recognitionInstitutionClass");
    this.regard = Objects.requireNonNull(regard, "regard");
    this.ratings = Objects.requireNonNull(ratings, "ratings");
  }

  /**
   * Run one recommendation pass.
   *
   * @return the candidates that were written, with their routes, so a caller can assert on them
   *     without re-reading the file
   */
  public List<Explained> run(Options options, Consumer<String> notes) throws IOException {
    Objects.requireNonNull(options, "options");
    Objects.requireNonNull(notes, "notes");

    notes.accept(PERSONAL_DATA_WARNING);

    List<String> known = KnownList.promoted(QidList.read(options.known()), ratings);
    // The count is the COMPOSED list, so it does not belong to the file path alone: issue #106
    // adds everything rated at or above KnownList.PROMOTION_RATING that the file does not name,
    // and this line read "N entity(ies) on the list at <path>" while N exceeded the file's rows.
    notes.accept(
        known.size() + " entity(ies) known — the list at " + options.known() + " plus promotions");

    Sweep sweep =
        new CandidateSweep(graph, recognitionInstitutionClass)
            .over(
                known,
                KnownList.suppressed(ratings),
                options.scorer(),
                options.minDegree(),
                regard);

    notes.accept(
        sweep.candidates().size()
            + " candidate(s) at or above "
            + options.minDegree()
            + " edge(s), from "
            + sweep.knownFound()
            + " entity(ies) you already know");
    if (sweep.knownMissing() > 0) {
      notes.accept(
          sweep.knownMissing()
              + " entity(ies) on your list are not in this graph, and reach nothing");
    }
    notes.accept(
        sweep.hubIntermediatesExcluded()
            + " hub intermediate(s) excluded rather than discounted (issues #52 and #66)");

    List<Recommendation> ranked = Recommendations.rank(sweep.candidates(), options.top());
    Routes routes = new Routes(graph, recognitionInstitutionClass);
    List<Explained> explained = new ArrayList<>();
    for (Recommendation candidate : ranked) {
      explained.add(new Explained(candidate, routes.bestFor(candidate, ROUTES_PER_CANDIDATE)));
    }

    try (Writer out = Files.newBufferedWriter(options.out(), StandardCharsets.UTF_8)) {
      RecommendationReport.write(sweep, explained, options.scorer(), options.minDegree(), out);
    }
    notes.accept("wrote " + options.out());
    return List.copyOf(explained);
  }
}
