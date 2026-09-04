package com.robsartin.segue.evaluate;

import com.robsartin.segue.domain.Equivalences;
import com.robsartin.segue.ingest.GraphProjector;
import com.robsartin.segue.port.IdentityMerge;
import com.robsartin.segue.recommend.RecommendCli;
import com.robsartin.segue.sqlite.SqliteAffinityStore;
import com.robsartin.segue.sqlite.SqliteAssertionLog;
import com.robsartin.segue.support.RequiredDatabase;
import com.robsartin.segue.tinker.TinkerGraphStore;
import com.robsartin.segue.wikidata.RecognitionInstitutions;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The entry point, run from Gradle: {@code ./gradlew evaluate --args="--db … --known …"} (ADR 65).
 *
 * <p><b>The second dev-side tool whose whole output is aggregates</b>, and it is held to that
 * mechanically for the reason ADR 63 gives: every value the report emits is an integer or a fixed
 * decimal, and every label is a literal, so {@code EvaluationIsSafeToPasteTest} can assert the
 * property over the shape of the text rather than over what any name means. The claim is over the
 * report and not over a failed run — a refusal below names the path it was given, and an exception
 * out of an adapter prints a stack trace like any other tool's.
 *
 * <p><b>{@code --db} is required, and {@code SEGUE_DB} does not satisfy it.</b> ADR 60's central
 * clause, reached from ADR 63's direction: an agent's shell is initialised from the owner's profile
 * and inherits the variable, and this output is a reading of the owner's whole taste layer.
 * Producing it is the owner's decision per invocation, and the number that comes out is evidence —
 * it gets pasted into an issue and quoted in an ADR, where a wrong export is discarded and a wrong
 * measurement becomes the record.
 *
 * <p><b>No {@code --out}, and no {@code System.out}.</b> {@code
 * ArchitectureTest.nothingWritesToStandardOut} bans stdout project-wide (ADR 28, ADR 30), so the
 * table goes through SLF4J at {@code info}, one call per line — and there is nothing here a log
 * line may not carry. Nor does it say which database it read.
 *
 * <p><b>It reads ratings and cannot read a note</b>, exactly as {@code RecommendCli} does and under
 * a fence of the same shape: {@code
 * ArchitectureTest.theEvaluationHarnessReadsRatingsAndNeverNotes}. This is the only class in the
 * package that touches the store.
 */
public final class EvaluateCli {

  private static final Logger log = LoggerFactory.getLogger(EvaluateCli.class);

  private static final String USAGE =
      "usage: --db <segue.db> --known <file of QIDs> [--top <n>, default "
          + RecommendCli.DEFAULT_TOP
          + "]";

  private EvaluateCli() {}

  /**
   * What to measure against, and how deep to read.
   *
   * @param database no default, on purpose — see this class's javadoc, and {@code
   *     support.RequiredDatabase}, which owns the refusal sentence
   * @param known the entities the owner already has, the same file {@code recommend} takes
   * @param top how many candidates each setting is read over. Defaults to {@code
   *     RecommendCli.DEFAULT_TOP} by reference, so the harness measures the list length the tool
   *     actually shows
   */
  public record Options(Path database, Path known, int top) {

    public Options {
      Objects.requireNonNull(database, "database");
      Objects.requireNonNull(known, "known");
    }
  }

  /** Parse and validate, refusing anything that could not work before a store is opened. */
  static Options parse(String[] args, String envDatabase, String userHome) {
    Path database = null;
    Path known = null;
    int top = RecommendCli.DEFAULT_TOP;

    for (int i = 0; i < args.length; i++) {
      String flag = args[i];
      String value = valueOf(args, i, flag);
      i++;
      switch (flag) {
        case "--db" -> database = Path.of(value);
        case "--known" -> known = Path.of(value);
        case "--top" -> top = number(flag, value);
        default -> throw usage("unknown option " + flag);
      }
    }

    if (database == null) {
      throw usage(RequiredDatabase.refusal(envDatabase, userHome));
    }
    if (known == null) {
      throw usage("--known is required: a held-out run needs the list it is holding out of");
    }
    if (top < 1) {
      throw usage("--top must be at least 1");
    }
    return new Options(database, known, top);
  }

  private static int number(String flag, String value) {
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException e) {
      throw usage(flag + " takes a whole number, got " + value);
    }
  }

  private static String valueOf(String[] args, int i, String flag) {
    if (i + 1 >= args.length) {
      throw usage(flag + " needs a value");
    }
    return args[i + 1];
  }

  private static IllegalArgumentException usage(String problem) {
    String sentence = problem.endsWith(".") ? problem : problem + ".";
    return new IllegalArgumentException(sentence + " " + USAGE);
  }

  public static void main(String[] args) {
    run(args, System.getenv("SEGUE_DB"), System.getProperty("user.home"));
  }

  /**
   * {@code main}, with the two environment reads passed in.
   *
   * <p>A seam for {@code CensusCli.run}'s reason: the order of the two refusals is the behaviour. A
   * missing {@code --db} has to be refused by {@link #parse} before {@code Files.exists} is
   * reached, or the operator is told "no segue database at …" — which reads as a missing file
   * rather than a missing flag, and names a path they never typed.
   */
  static void run(String[] args, String envDatabase, String userHome) {
    Options options = parse(args, envDatabase, userHome);

    // Refuse a database that is not there rather than creating an empty one and measuring nothing:
    // both sqlite constructors create the file and its schema if absent, which is right for a
    // server starting fresh and wrong for a tool whose whole job is to read.
    if (!Files.exists(options.database())) {
      throw new IllegalArgumentException(
          "no segue database at " + options.database() + " — nothing to evaluate");
    }

    try (SqliteAssertionLog assertions = new SqliteAssertionLog(options.database());
        SqliteAffinityStore affinity = new SqliteAffinityStore(options.database());
        TinkerGraphStore graph = new TinkerGraphStore()) {
      long applied = GraphProjector.project(assertions, graph, IdentityMerge.NONE);
      log.info("replayed {} assertion(s)", applied);

      // Resolved through the merges before anything downstream sees it, exactly as RecommendCli
      // does and for the same reason: a merge leaves two affinity rows naming one thing, and a
      // split that counted both would hold out one id and leave the other in the known-list.
      Equivalences merges = Equivalences.in(assertions.readAll());
      Map<String, Integer> ratings = merges.resolve(affinity.readRatings());
      // A count, never a qid and never a score.
      log.info("read {} rating(s)", ratings.size());

      new EvaluateRun(graph, RecognitionInstitutions::isRecognitionInstitution, ratings, merges)
          .run(options.known(), options.top(), log::info);
    } catch (IOException e) {
      throw new UncheckedIOException("could not read " + options.known(), e);
    }
  }
}
