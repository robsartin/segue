package com.robsartin.segue.rate;

import com.robsartin.segue.domain.AffinityRecord;
import com.robsartin.segue.ingest.GraphProjector;
import com.robsartin.segue.sqlite.SqliteAffinityStore;
import com.robsartin.segue.sqlite.SqliteAssertionLog;
import com.robsartin.segue.support.QidList;
import com.robsartin.segue.tinker.TinkerGraphStore;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The sixth dev-side tool: {@code ./gradlew rate --args="--known …"}. See ADR 46. */
public final class RateCli {

  private static final Logger log = LoggerFactory.getLogger(RateCli.class);

  /**
   * Not 8080: the MCP server may be running, and nothing addressed to one should reach the other.
   */
  public static final int DEFAULT_PORT = 8090;

  /** Enough to keep the stream mixed without spending the whole sweep on one sitting. */
  private static final int DEFAULT_CANDIDATES = 200;

  private static final String USAGE =
      "usage: --known <file of QIDs> [--db <segue.db>] [--port <n>, default "
          + DEFAULT_PORT
          + "] [--revise <"
          + AffinityRecord.MIN_RATING
          + "-"
          + AffinityRecord.MAX_RATING
          + ">]";

  private RateCli() {}

  /**
   * What to deal, and where to serve it.
   *
   * @param database the assertion log to replay. Defaults exactly as {@code RecommendCli}'s does:
   *     {@code SEGUE_DB} if set, otherwise {@code ${user.home}/.segue/segue.db} — stated here as
   *     well as in {@code application.yaml} because this tool is plain Java and ADR 32 keeps Spring
   *     out of every package but {@code app} and {@code mcp}
   * @param known the entities you already have. ADR 40's list, the same shape {@code RecommendCli}
   *     reads
   * @param port loopback only; 0 asks the OS to pick one, which the running server reports back
   * @param revise absent by default; when present, the deck deals already-rated entities holding
   *     exactly this rating instead of unrated ones (issue #109) — a candidate sweep has nothing to
   *     offer a revision pass, since a candidate is by definition unrated
   */
  public record Options(Path database, Path known, int port, OptionalInt revise) {

    public Options {
      Objects.requireNonNull(database, "database");
      Objects.requireNonNull(known, "known");
      Objects.requireNonNull(revise, "revise");
    }
  }

  /** Parse and validate, refusing anything that could not work before a store is opened. */
  static Options parse(String[] args, String envDatabase, String userHome) {
    Path database = null;
    Path known = null;
    int port = DEFAULT_PORT;
    OptionalInt revise = OptionalInt.empty();

    for (int i = 0; i < args.length; i++) {
      String flag = args[i];
      String value = valueOf(args, i, flag);
      i++;
      switch (flag) {
        case "--db" -> database = Path.of(value);
        case "--known" -> known = Path.of(value);
        case "--port" -> port = number(flag, value);
        case "--revise" -> revise = OptionalInt.of(revise(value));
        default -> throw usage("unknown option " + flag);
      }
    }

    if (known == null) {
      throw usage("--known is required: the deck is a statement about entities you have");
    }
    return new Options(
        database != null ? database : defaultDatabase(envDatabase, userHome), known, port, revise);
  }

  private static int revise(String value) {
    int rating = number("--revise", value);
    if (rating < AffinityRecord.MIN_RATING || rating > AffinityRecord.MAX_RATING) {
      throw usage(
          "--revise must be from "
              + AffinityRecord.MIN_RATING
              + " to "
              + AffinityRecord.MAX_RATING
              + ": that is the whole of the scale");
    }
    return rating;
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

  public static void main(String[] args) throws IOException {
    Options options = parse(args, System.getenv("SEGUE_DB"), System.getProperty("user.home"));

    // Refuse a database that is not there rather than creating an empty one and dealing nothing:
    // SqliteAssertionLog's constructor creates the file and its schema if absent, which is right
    // for a server starting fresh and wrong for a tool whose whole job is to read.
    if (!Files.exists(options.database())) {
      throw new IllegalArgumentException(
          "no graph at " + options.database() + " — nothing to rate");
    }

    try (SqliteAssertionLog assertions = new SqliteAssertionLog(options.database());
        SqliteAffinityStore affinity = new SqliteAffinityStore(options.database());
        TinkerGraphStore graph = new TinkerGraphStore()) {
      long applied = GraphProjector.project(assertions, graph);
      log.info("replayed {} assertion(s) from {}", applied, options.database());

      // A count, never a qid and never a score (ADR 33).
      Map<String, Integer> rated = affinity.readRatings();
      log.info("{} entity(ies) already rated", rated.size());

      List<Card> deck =
          RateRun.buildDeck(
              graph,
              QidList.read(options.known()),
              rated,
              DEFAULT_CANDIDATES,
              options.revise(),
              RateCli::note);

      RateServer server = new RateServer(deck, affinity, options.port());
      server.start();
      log.info("open http://127.0.0.1:{} — press ctrl-c to stop", server.port());
      Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
      Thread.currentThread().join();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (IOException e) {
      throw new UncheckedIOException("could not serve the deck", e);
    }
  }

  private static void note(String message) {
    log.info("{}", message);
  }
}
