package com.robsartin.segue.recommend;

import com.robsartin.segue.domain.Recommendations;
import com.robsartin.segue.domain.Scorer;
import com.robsartin.segue.ingest.GraphProjector;
import com.robsartin.segue.sqlite.SqliteAffinityStore;
import com.robsartin.segue.sqlite.SqliteAssertionLog;
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
 * The entry point, run from Gradle: {@code ./gradlew recommend --args="--known … --out …"}.
 *
 * <p><b>The fifth dev-side tool, and the one with the best case for being a seventh MCP tool —
 * which it is still not.</b> ADR 26 pins the surface at six, and ADR 40, ADR 41, ADR 43 and ADR 44
 * each declined to add to it for reasons that do not all apply here: "what should I explore next?"
 * is genuinely a question one would want to ask in conversation, which was never true of seeding,
 * exporting, listing ratings or retracting. ADR 45 decides it the same way anyway, and the argument
 * is short: this tool's input is a file of everything you already know, and its output is a list
 * derived from it. Handing a model a path to that file is exactly what ADR 40 refused for the
 * seeding list; deriving the same list from the {@code affinity} table instead would need the bulk
 * read ADR 39 refused and ADR 43 reserved to the owner's own machine. Either shape of a seventh
 * tool needs something the surface has already turned down twice.
 *
 * <p><b>It reads and never writes.</b> {@code ArchitectureTest.theRecommenderOnlyReads} forbids
 * this package from calling {@code GraphStore.record}, {@code GraphStore.upsertNode}, {@code
 * AssertionLog.append} or {@code AffinityStore.put}, and from depending on {@code IngestService} at
 * all. The graph it walks is rebuilt in memory by {@link GraphProjector} — the same replay the
 * application performs at boot, so a recommendation's routes are the routes {@code find_paths}
 * would return — and it is thrown away when the JVM exits.
 *
 * <p><b>It reads ratings and cannot read notes</b>, and the fence is narrower than it was. ADR 45
 * banned {@code AffinityStore} outright as a type, because ADR 33 made the whole taste layer
 * personal data; issue #85 split that — a rating is the known-list at higher resolution, a note is
 * the owner's own words — so {@code theRecommenderReadsRatingsAndNeverNotes} now bans {@code
 * AffinityRecord} and the two methods that return one, leaving {@code readRatings}. This class is
 * the only one in the package that touches the store at all: everything below it takes regard as a
 * function (ADR 45's seam, now wired to {@code Recommendations.regardFor}).
 *
 * <p>This paragraph used to call that shape "the one fence no sibling tool has". Issue #101 (ADR
 * 46) made it false: the rating deck reads the same {@code readRatings} — {@code
 * onlyTheRecommenderReadsEveryRating} now names {@code ..recommend..} and {@code ..rate..} — and is
 * held off the note by {@code theRatingDeckNeverReadsANote}. What is still particular to this tool
 * is the shape rather than the effect: {@code recommend} may not call {@code AffinityStore.find}
 * and may not name {@code AffinityRecord} at all, where {@code rate} has no {@code find} ban and
 * lets {@code RateServer} name the record it has to construct.
 *
 * <p><b>No {@code System.out}.</b> ADR 30 makes SLF4J the only logging API and an ArchUnit rule
 * enforces it project-wide, so the recommendations go to the operator's chosen file and the log
 * lines carry counts and paths alone.
 */
public final class RecommendCli {

  private static final Logger log = LoggerFactory.getLogger(RecommendCli.class);

  /** As many as a person will read in one sitting, and enough to see where the tail begins. */
  public static final int DEFAULT_TOP = 25;

  /**
   * Below this a normalised score is meaningless: a candidate with one edge would divide by one.
   * The real default is measured — see {@code Recommendations.MIN_CANDIDATE_DEGREE} — and this is
   * only the point at which the argument stops being an argument.
   */
  private static final int LOWEST_USEFUL_FLOOR = 2;

  private static final String USAGE =
      "usage: --known <file of QIDs> --out <file>"
          + " [--scorer <"
          + Scorer.names()
          + ">, default lift]"
          + " [--min-degree <n>, default "
          + Recommendations.MIN_CANDIDATE_DEGREE
          + "] [--top <n>, default "
          + DEFAULT_TOP
          + "] [--db <segue.db>]";

  private RecommendCli() {}

  /**
   * What to recommend against, how to score it, and where the answer goes.
   *
   * @param known the entities you already have. Its own file rather than the taste layer, and that
   *     is the placement decision in one field: this is the list ADR 40's seeding tool resolved, it
   *     lives outside this repository, and nothing on the MCP surface can see it
   * @param out no default, on purpose — a tool that picks a path for you is a tool that quietly
   *     writes personal data into the repository (ADR 41's argument, ADR 43's second use of it)
   * @param database the assertion log to replay. Defaults exactly as the server's does: {@code
   *     SEGUE_DB} if set, otherwise {@code ${user.home}/.segue/segue.db}. Stated here as well as in
   *     {@code application.yaml} because this tool is plain Java and ADR 32 keeps Spring out of
   *     every package but {@code app} and {@code mcp}
   */
  public record Options(
      Path database, Path known, Path out, Scorer scorer, int minDegree, int top) {

    public Options {
      Objects.requireNonNull(database, "database");
      Objects.requireNonNull(known, "known");
      Objects.requireNonNull(out, "out");
      Objects.requireNonNull(scorer, "scorer");
    }
  }

  /** Parse and validate, refusing anything that could not work before a store is opened. */
  static Options parse(String[] args, String envDatabase, String userHome) {
    Path database = null;
    Path known = null;
    Path out = null;
    Scorer scorer = Scorer.LIFT;
    int minDegree = Recommendations.MIN_CANDIDATE_DEGREE;
    int top = DEFAULT_TOP;

    for (int i = 0; i < args.length; i++) {
      String flag = args[i];
      String value = valueOf(args, i, flag);
      i++;
      switch (flag) {
        case "--db" -> database = Path.of(value);
        case "--known" -> known = Path.of(value);
        case "--out" -> out = Path.of(value);
        case "--scorer" -> scorer = Scorer.parse(value);
        case "--min-degree" -> minDegree = number(flag, value);
        case "--top" -> top = number(flag, value);
        default -> throw usage("unknown option " + flag);
      }
    }

    if (known == null) {
      throw usage("--known is required: a recommendation is something absent from what you know");
    }
    if (out == null) {
      throw usage("--out is required");
    }
    if (minDegree < LOWEST_USEFUL_FLOOR) {
      throw usage(
          "--min-degree must be at least "
              + LOWEST_USEFUL_FLOOR
              + ": without a floor a normalised score puts the thinnest node in the graph first");
    }
    if (top < 1) {
      throw usage("--top must be at least 1");
    }
    return new Options(
        database != null ? database : defaultDatabase(envDatabase, userHome),
        known,
        out,
        scorer,
        minDegree,
        top);
  }

  private static int number(String flag, String value) {
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException e) {
      throw usage(flag + " takes a whole number, got " + value);
    }
  }

  private static Path defaultDatabase(String envDatabase, String userHome) {
    return envDatabase != null && !envDatabase.isBlank()
        ? Path.of(envDatabase)
        : Path.of(userHome, ".segue", "segue.db");
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

  /**
   * Where {@link RecommendRun}'s notes go.
   *
   * <p>The warning gets {@code warn} and everything else {@code info}, the same split {@code
   * ExportCli} and {@code RatingsCli} make: one of these lines changes what the operator should do
   * next with the file.
   */
  private static void note(String line) {
    if (RecommendRun.PERSONAL_DATA_WARNING.equals(line)) {
      log.warn(line);
    } else {
      log.info(line);
    }
  }

  public static void main(String[] args) {
    Options options = parse(args, System.getenv("SEGUE_DB"), System.getProperty("user.home"));

    // Refuse a database that is not there rather than creating an empty one and recommending
    // nothing: SqliteAssertionLog's constructor creates the file and its schema if absent, which
    // is right for a server starting fresh and wrong for a tool whose whole job is to read.
    if (!Files.exists(options.database())) {
      throw new IllegalArgumentException(
          "no graph at " + options.database() + " — nothing to recommend from");
    }

    try (SqliteAssertionLog assertions = new SqliteAssertionLog(options.database());
        SqliteAffinityStore affinity = new SqliteAffinityStore(options.database());
        TinkerGraphStore graph = new TinkerGraphStore()) {
      long applied = GraphProjector.project(assertions, graph);
      log.info("replayed {} assertion(s) from {}", applied, options.database());

      // The one line in this tool that reads the taste layer, and it reads half of it (issue #85).
      // A count, never a qid and never a score: how much somebody has rated is a fact about them,
      // and ADR 33 keeps all of it out of every log line.
      Map<String, Integer> ratings = affinity.readRatings();
      log.info("weighting by {} rating(s)", ratings.size());

      new RecommendRun(
              graph,
              RecognitionInstitutions::isRecognitionInstitution,
              Recommendations.regardFor(ratings))
          .run(options, RecommendCli::note);
    } catch (IOException e) {
      throw new UncheckedIOException("could not write " + options.out(), e);
    }
  }
}
