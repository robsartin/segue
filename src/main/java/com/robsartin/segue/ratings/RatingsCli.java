package com.robsartin.segue.ratings;

import com.robsartin.segue.port.AffinityStore;
import com.robsartin.segue.port.AssertionLog;
import com.robsartin.segue.sqlite.SqliteAffinityStore;
import com.robsartin.segue.sqlite.SqliteAssertionLog;
import com.robsartin.segue.support.DefaultDatabase;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The entry point, run from Gradle: {@code ./gradlew listRatings --args="--out …"}.
 *
 * <p><b>Deliberately not a seventh MCP tool.</b> ADR 26 pins the surface at six and ADR 39 declined
 * a bulk {@code list_affinity} on ADR 16's data minimisation: it is the single call that would put
 * the entire taste layer in front of a model. That reasoning stands. What it left unsolved is that
 * the <em>owner</em> could not see their own ratings either - and affinity is the one part of segue
 * that cannot be regenerated from a source. ADR 43 separates the two audiences: a model still
 * cannot enumerate the taste layer, and the person who owns it can, through a tool that runs on
 * their own machine and writes to a path they name. One of the dev-side tools {@code
 * ArchitectureTest.DEV_TOOL_PACKAGES} lists, after {@code seed} (ADR 40) and {@code export} (ADR
 * 41).
 *
 * <p><b>It reads two stores and writes neither.</b> See {@link RatingsRun} for the fence, and note
 * what it covers that no other rule does: the taste-layer writes, {@code AffinityStore.put} and
 * {@code updateRating}. The exporter's rule did not need those clauses, because the exporter looks
 * up one rating at a time; this tool holds the whole table.
 *
 * <p><b>The output is a file, not console output.</b> Two reasons, and they point the same way. ADR
 * 30 makes SLF4J the only logging API and ArchUnit forbids {@code System.out} project-wide, so
 * "print it to the terminal" means "log it" - and ADR 33 says affinity is never logged. So the
 * ratings go to the operator's chosen path and the log lines carry counts alone. The {@code --out}
 * path has no default for the same reason {@code exportGraph}'s does not: a tool that picks a path
 * for you is a tool that quietly writes personal data into the repository.
 *
 * <p><b>A second output, and the same discipline (#285).</b> {@code --promotions-off <known.csv>
 * --names <out.txt>} writes the entities rated at or above {@code KnownList.PROMOTION_RATING} that
 * the known-list file does not name — the promotions ADR 48 defines and the population {@code
 * evaluate} holds out — as one label per line, for upload to Setlist Scout. The set is derived
 * through {@code KnownList.promoted} so that this tool, {@code recommend} and {@code rate} cannot
 * disagree about who is promoted. The file carries labels and nothing else: the upload wants names,
 * and a qid column would make it something else. Like {@code --out} it has no default, it names
 * itself as personal data on its first line, and the log lines about it are counts. It is never
 * pasted anywhere, so {@code CensusIsSafeToPasteTest}'s property is not one it has or wants.
 */
public final class RatingsCli {

  private static final Logger log = LoggerFactory.getLogger(RatingsCli.class);

  private static final String USAGE =
      "usage: --out <file> [--sort <"
          + SortOrder.names()
          + ">, default rating] [--db <segue.db>]"
          + " [--promotions-off <known.csv> --names <file>]";

  private RatingsCli() {}

  /**
   * Where the listing goes, in what order, and — since #285 — which promotions to write as names.
   *
   * @param database the taste layer and the log to read - the same file, per ADR 33's rejection of
   *     a second database. Defaults per {@link DefaultDatabase#resolve} - one rule shared with
   *     {@code ExportCli}, {@code RateCli} and {@code RecommendCli} (issue #179) - rather than a
   *     copy of it stated here.
   * @param out no default, on purpose - see this class's Javadoc. Null when only the names export
   *     was asked for, which is the one case that does not write a listing
   * @param promotionsOff the {@code --known} file to measure the promotions against, read the way
   *     {@code recommend} and {@code evaluate} read it ({@code QidList}); null with {@code names}
   * @param names where the promotions off that file go, one name per line; null with {@code
   *     promotionsOff}
   */
  public record Options(Path database, Path out, SortOrder sort, Path promotionsOff, Path names) {

    public Options {
      Objects.requireNonNull(database, "database");
      Objects.requireNonNull(sort, "sort");
      // The pair is one output and the record cannot hold half of it: parse() produces the
      // readable message, and this is what stops a caller assembling a state the run has no
      // behaviour for.
      if ((promotionsOff == null) != (names == null)) {
        throw new IllegalArgumentException("--promotions-off and --names are given together");
      }
      if (out == null && names == null) {
        throw new IllegalArgumentException("nothing to write: give --out, --names, or both");
      }
    }
  }

  /** Parse and validate, refusing anything that could not work before a store is opened. */
  static Options parse(String[] args, String envDatabase, String userHome) {
    String db = null;
    Path out = null;
    Path promotionsOff = null;
    Path names = null;
    SortOrder sort = SortOrder.RATING;

    for (int i = 0; i < args.length; i++) {
      String flag = args[i];
      String value = valueOf(args, i, flag);
      i++;
      switch (flag) {
        case "--db" -> db = value;
        case "--out" -> out = Path.of(value);
        case "--sort" -> sort = SortOrder.parse(value);
        case "--promotions-off" -> promotionsOff = Path.of(value);
        case "--names" -> names = Path.of(value);
        default -> throw usage("unknown option " + flag);
      }
    }

    // One output in two flags, so half of it is a usage error rather than a silent no-op.
    if (promotionsOff != null && names == null) {
      throw usage("--promotions-off needs --names <file> to write to");
    }
    if (names != null && promotionsOff == null) {
      throw usage("--names needs --promotions-off <known.csv> to measure against");
    }
    // --out stays required when it is the only output there is: a listing must never go to a path
    // nobody chose (ADR 43). The names export names its own path, so it satisfies the same rule.
    if (out == null && names == null) {
      throw usage("--out is required");
    }
    return new Options(
        DefaultDatabase.resolve(db, envDatabase, userHome), out, sort, promotionsOff, names);
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
   * Where {@link RatingsRun}'s notes go.
   *
   * <p>The warning gets {@code warn} and everything else {@code info}, the same split {@code
   * ExportCli} makes: one of these lines changes what the operator should do next with the file.
   * None of them carries a rating, a note or a label - {@code RatingsRunTest} pins that, and it is
   * ADR 33's "never logged" reaching the one tool whose whole subject is affinity.
   */
  private static void note(String line) {
    if (RatingsRun.PERSONAL_DATA_WARNING.equals(line)) {
      log.warn(line);
    } else {
      log.info(line);
    }
  }

  /** Every path this run was asked to write, for the one message that can name none of them. */
  private static String outputs(Options options) {
    return Stream.of(options.out(), options.names())
        .filter(Objects::nonNull)
        .map(Path::toString)
        .collect(Collectors.joining(" and "));
  }

  public static void main(String[] args) {
    Options options = parse(args, System.getenv("SEGUE_DB"), System.getProperty("user.home"));

    // Refuse a database that is not there rather than creating an empty one and listing nothing:
    // both sqlite constructors create the file and its schema if absent, which is right for a
    // server starting fresh and wrong for a tool whose whole job is to read. It matters more here
    // than in the exporter - "you have rated nothing" is a believable answer, and a wrong one.
    if (!Files.exists(options.database())) {
      throw new IllegalArgumentException(
          "no segue database at " + options.database() + " — nothing to list");
    }

    try (AffinityStore ratings = new SqliteAffinityStore(options.database());
        AssertionLog assertions = new SqliteAssertionLog(options.database())) {
      new RatingsRun(ratings, assertions).run(options, RatingsCli::note);
    } catch (IOException e) {
      throw new UncheckedIOException("could not write " + outputs(options), e);
    }
  }
}
