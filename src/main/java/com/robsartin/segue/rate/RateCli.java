package com.robsartin.segue.rate;

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

  private RateCli() {}

  public static void main(String[] args) throws IOException {
    Path database =
        Path.of(
            System.getenv()
                .getOrDefault("SEGUE_DB", System.getProperty("user.home") + "/.segue/segue.db"));
    Path known = null;
    int port = DEFAULT_PORT;

    for (int i = 0; i < args.length - 1; i += 2) {
      String value = args[i + 1];
      switch (args[i]) {
        case "--known" -> known = Path.of(value);
        case "--db" -> database = Path.of(value);
        case "--port" -> port = Integer.parseInt(value);
        default -> throw new IllegalArgumentException("unknown flag: " + args[i]);
      }
    }
    if (known == null) {
      throw new IllegalArgumentException(
          "--known is required: the deck is a statement about entities you have");
    }
    // Refuse a database that is not there rather than creating an empty one and dealing nothing:
    // SqliteAssertionLog's constructor creates the file and its schema if absent, which is right
    // for a server starting fresh and wrong for a tool whose whole job is to read.
    if (!Files.exists(database)) {
      throw new IllegalArgumentException("no graph at " + database + " — nothing to rate");
    }

    try (SqliteAssertionLog assertions = new SqliteAssertionLog(database);
        SqliteAffinityStore affinity = new SqliteAffinityStore(database);
        TinkerGraphStore graph = new TinkerGraphStore()) {
      long applied = GraphProjector.project(assertions, graph);
      log.info("replayed {} assertion(s) from {}", applied, database);

      // A count, never a qid and never a score (ADR 33).
      Map<String, Integer> rated = affinity.readRatings();
      log.info("{} entity(ies) already rated", rated.size());

      List<Card> deck =
          RateRun.buildDeck(
              graph, QidList.read(known), rated.keySet(), DEFAULT_CANDIDATES, RateCli::note);

      RateServer server = new RateServer(deck, affinity, port);
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
