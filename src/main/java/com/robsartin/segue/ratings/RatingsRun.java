package com.robsartin.segue.ratings;

import com.robsartin.segue.domain.AffinityRecord;
import com.robsartin.segue.port.AffinityStore;
import com.robsartin.segue.port.AssertionLog;
import com.robsartin.segue.ratings.RatingsCli.Options;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Warn, join, report, write - in that order, and the order is the design.
 *
 * <p>This class is the one place the taste layer and the world-fact layer are in the same method,
 * which is exactly where ADR 33 says a cross-layer question belongs: above both ports, never below
 * them. {@code SegueService.getEntity} performs the same join for one entity; this performs it for
 * every rating there is, on the dev side, for the owner.
 *
 * <p><b>Nothing personal goes through the notes.</b> Every note is a count or a file path, and
 * {@code RatingsRunTest} asserts it: labels, ratings and notes reach the file and nothing else.
 * That is not fastidiousness - {@link RatingsCli} routes these notes into SLF4J, and ADR 33 keeps
 * affinity out of every log line. The whole listing is personal data; the fact that a listing was
 * produced is not.
 *
 * <p><b>The warning comes first, before a row exists and long before the file does.</b> Borrowed
 * from {@code ExportRun}, for the same reason: what the operator does next is decide where to put
 * the output, and a warning that arrives after the write is a warning about something that has
 * already happened.
 *
 * <p><b>It reads and cannot write.</b> {@code ArchitectureTest.theRatingsToolOnlyReads} forbids
 * this package from calling {@code AffinityStore.put}, {@code AffinityStore.updateRating}, {@code
 * AssertionLog.append}, {@code GraphStore.record} or {@code GraphStore.upsertNode} - both affinity
 * writes included, which no other rule in the project covers. Affinity is the one part of segue
 * that cannot be regenerated, so the tool that reads all of it must be unable to touch any of it.
 *
 * <p>Notes go to a {@link Consumer} rather than to a logger of this class's own, so the ordering is
 * observable from a test and so this class has no logger to misuse - the same discipline {@code
 * SqliteAffinityStore} keeps.
 */
public final class RatingsRun {

  /**
   * Said before anything else, every time. Names the decision and the issue rather than saying
   * "careful", because the operator's next action - where this file goes - is what depends on it.
   */
  public static final String PERSONAL_DATA_WARNING =
      "this listing is your whole taste layer: it is personal data under ADR 33 and issue #37."
          + " Keep it outside the working tree and out of version control — this repository is"
          + " public.";

  private final AffinityStore ratings;
  private final AssertionLog log;

  public RatingsRun(AffinityStore ratings, AssertionLog log) {
    this.ratings = Objects.requireNonNull(ratings, "ratings");
    this.log = Objects.requireNonNull(log, "log");
  }

  /**
   * List every rating.
   *
   * @return the rows that were written, so a caller can assert on them without re-reading the file
   */
  public List<AffinityRow> run(Options options, Consumer<String> notes) throws IOException {
    Objects.requireNonNull(options, "options");
    Objects.requireNonNull(notes, "notes");

    notes.accept(PERSONAL_DATA_WARNING);

    List<AffinityRecord> recorded = ratings.readAll();
    // Skipped entirely when nothing is rated: a real log is a quarter of a million assertions, and
    // there is no name to look up.
    Map<String, String> labels =
        Labels.forQids(log, recorded.stream().map(AffinityRecord::qid).collect(Collectors.toSet()));

    List<AffinityRow> rows =
        recorded.stream()
            .map(
                rating ->
                    new AffinityRow(
                        rating.qid(),
                        labels.get(rating.qid()),
                        rating.rating(),
                        rating.note(),
                        rating.updatedAt()))
            .toList();

    notes.accept(rows.size() + " rating(s), sorted by " + options.sort().describe());
    long unlabelled = rows.stream().filter(row -> row.label() == null).count();
    if (unlabelled > 0) {
      notes.accept(
          unlabelled
              + " rating(s) name an entity the graph has no claim about, and are listed as \""
              + AffinityRow.NO_LABEL
              + "\" — a rating outlives the graph it was made against");
    }

    try (Writer out = Files.newBufferedWriter(options.out(), StandardCharsets.UTF_8)) {
      RatingsTable.write(rows, options.sort(), out);
    }
    notes.accept("wrote " + options.out());
    return rows;
  }
}
