package com.robsartin.segue.census;

import com.robsartin.segue.port.AffinityStore;
import com.robsartin.segue.port.AssertionLog;
import com.robsartin.segue.sqlite.SqliteAffinityStore;
import com.robsartin.segue.sqlite.SqliteAssertionLog;
import com.robsartin.segue.support.RequiredDatabase;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The entry point, run from Gradle: {@code ./gradlew graphCensus --args="--db <segue.db>"}.
 *
 * <p><b>The sixth dev-side tool, and the only one whose whole output is aggregates.</b> ADR 51
 * draws the line — a count over the owner's data may be published, an entity presented as the
 * owner's may not — and says in as many words that the line is held by review and nothing else.
 * That is true in general and false for one artefact: this tool emits no free text from the data at
 * all, so {@code CensusIsSafeToPasteTest} can hold it mechanically. See ADR 63.
 *
 * <p><b>{@code --db} is required, and {@code SEGUE_DB} does not satisfy it.</b> Not ADR 60's
 * consequence — nothing here writes, and a wrong count costs a re-run — but ADR 60's central
 * clause: an agent's shell is initialised from the owner's profile and inherits the variable, and
 * this tool's output is the shape of the owner's whole graph and taste layer. Whether to produce
 * that is the owner's decision per invocation. The second half is that a census is evidence: it is
 * pasted into an issue and quoted in an ADR, where a wrong export is discarded and a wrong count
 * becomes the record.
 *
 * <p><b>No {@code --out}, and no {@code System.out}.</b> {@code
 * ArchitectureTest.nothingWritesToStandardOut} bans stdout project-wide (ADR 28, ADR 30), so the
 * table goes through SLF4J at {@code info}, one call per line — the route {@code ExportCli} and
 * {@code RatingsCli} already use for their notes. {@code RatingsCli} writes a file because ADR 33
 * keeps affinity out of every log line and its output is the whole taste layer; this output is
 * counts alone, so there is nothing a log line may not carry and nothing left on disk afterwards.
 */
public final class CensusCli {

  private static final Logger log = LoggerFactory.getLogger(CensusCli.class);

  private static final String USAGE = "usage: --db <segue.db>";

  private CensusCli() {}

  /**
   * The database to count.
   *
   * @param database no default, on purpose — see this class's Javadoc, and {@code
   *     support.RequiredDatabase}, which owns the refusal sentence
   */
  public record Options(Path database) {

    public Options {
      Objects.requireNonNull(database, "database");
    }
  }

  /** Parse and validate, refusing anything that could not work before a store is opened. */
  static Options parse(String[] args, String envDatabase, String userHome) {
    Path database = null;

    for (int i = 0; i < args.length; i++) {
      String flag = args[i];
      String value = valueOf(args, i, flag);
      i++;
      if ("--db".equals(flag)) {
        database = Path.of(value);
      } else {
        throw usage("unknown option " + flag);
      }
    }

    if (database == null) {
      throw usage(RequiredDatabase.refusal(envDatabase, userHome));
    }
    return new Options(database);
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
   * <p>A seam for the same reason {@code RetractCli.run} is one: the order of the two refusals is
   * the behaviour. A missing {@code --db} has to be refused by {@link #parse} before {@code
   * Files.exists} is reached, or the operator is told "no segue database at …" — which reads as a
   * missing file rather than a missing flag, and names a path they never typed. A test can only
   * hold that order if it can supply a home directory of its own.
   */
  static void run(String[] args, String envDatabase, String userHome) {
    Options options = parse(args, envDatabase, userHome);

    // Refuse a database that is not there rather than creating an empty one and counting nothing:
    // both sqlite constructors create the file and its schema if absent, which is right for a
    // server starting fresh and wrong for a tool whose whole job is to read. ExportCli, RatingsCli
    // and RetractCli check the same thing for the same reason.
    if (!Files.exists(options.database())) {
      throw new IllegalArgumentException(
          "no segue database at " + options.database() + " — nothing to count");
    }

    try (AssertionLog assertions = new SqliteAssertionLog(options.database());
        AffinityStore ratings = new SqliteAffinityStore(options.database())) {
      new CensusRun(assertions, ratings).run(log::info);
    }
  }
}
