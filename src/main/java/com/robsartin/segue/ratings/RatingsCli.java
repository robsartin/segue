package com.robsartin.segue.ratings;

import com.robsartin.segue.port.AffinityStore;
import com.robsartin.segue.port.AssertionLog;
import com.robsartin.segue.sqlite.SqliteAffinityStore;
import com.robsartin.segue.sqlite.SqliteAssertionLog;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
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
 * their own machine and writes to a path they name. Third of three dev-side tools, after {@code
 * seed} (ADR 40) and {@code export} (ADR 41).
 *
 * <p><b>It reads two stores and writes neither.</b> See {@link RatingsRun} for the fence, and note
 * what it covers that no other rule does: {@code AffinityStore.put}. The exporter's rule did not
 * need that clause, because the exporter looks up one rating at a time; this tool holds the whole
 * table.
 *
 * <p><b>The output is a file, not console output.</b> Two reasons, and they point the same way. ADR
 * 30 makes SLF4J the only logging API and ArchUnit forbids {@code System.out} project-wide, so
 * "print it to the terminal" means "log it" - and ADR 33 says affinity is never logged. So the
 * ratings go to the operator's chosen path and the log lines carry counts alone. The {@code --out}
 * path has no default for the same reason {@code exportGraph}'s does not: a tool that picks a path
 * for you is a tool that quietly writes personal data into the repository.
 */
public final class RatingsCli {

  private static final Logger log = LoggerFactory.getLogger(RatingsCli.class);

  private static final String USAGE =
      "usage: --out <file> [--sort <" + SortOrder.names() + ">, default rating] [--db <segue.db>]";

  private RatingsCli() {}

  /**
   * Where the listing goes, and in what order.
   *
   * @param database the taste layer and the log to read - the same file, per ADR 33's rejection of
   *     a second database. Defaults exactly as the server's does: {@code SEGUE_DB} if set,
   *     otherwise {@code ${user.home}/.segue/segue.db}. Stated here as well as in {@code
   *     application.yaml} because this tool is plain Java and ADR 32 keeps Spring out of every
   *     package but {@code app} and {@code mcp}
   * @param out no default, on purpose - see this class's Javadoc
   */
  public record Options(Path database, Path out, SortOrder sort) {

    public Options {
      Objects.requireNonNull(database, "database");
      Objects.requireNonNull(out, "out");
      Objects.requireNonNull(sort, "sort");
    }
  }

  /** Parse and validate, refusing anything that could not work before a store is opened. */
  static Options parse(String[] args, String envDatabase, String userHome) {
    Path database = null;
    Path out = null;
    SortOrder sort = SortOrder.RATING;

    for (int i = 0; i < args.length; i++) {
      String flag = args[i];
      String value = valueOf(args, i, flag);
      i++;
      switch (flag) {
        case "--db" -> database = Path.of(value);
        case "--out" -> out = Path.of(value);
        case "--sort" -> sort = SortOrder.parse(value);
        default -> throw usage("unknown option " + flag);
      }
    }

    if (out == null) {
      throw usage("--out is required");
    }
    return new Options(
        database != null ? database : defaultDatabase(envDatabase, userHome), out, sort);
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
      throw new UncheckedIOException("could not write " + options.out(), e);
    }
  }
}
