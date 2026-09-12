package com.robsartin.segue.expand;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.robsartin.segue.domain.AffinityRecord;
import com.robsartin.segue.domain.KnownList;
import com.robsartin.segue.domain.NodeAssertion;
import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.domain.Provenance;
import com.robsartin.segue.sqlite.SqliteAffinityStore;
import com.robsartin.segue.sqlite.SqliteAssertionLog;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The parser's refusals and defaults, and the whole tool driven once end to end over a
 * {@code @TempDir} database. Every path, id, label and rating here is either invented or the test's
 * own {@code @TempDir} (ADR 33, issue #37).
 *
 * <p><b>The end-to-end test is a DRY run, and that is what keeps it offline.</b> A real run asks
 * Wikidata and MusicBrainz; {@code ExpandRun.dryRun} reads the projection alone, so {@code
 * ExpandCli.run} can be driven through its whole wiring — the two sqlite stores, the replay, the
 * merge-resolved ratings, {@code KnownList.promoted} and the report — without a socket.
 */
class ExpandCliTest {

  private static final Instant WHEN = Instant.parse("2026-01-01T00:00:00Z");

  /** Rated at PROMOTION_RATING, so KnownList.promoted returns it and the dry run counts it. */
  private static final String PROMOTED = "Q0900701";

  /** Rated below it, so it is not a promotion at all. */
  private static final String SHRUGGED_AT = "Q0900702";

  /** A promotion whose rating was last written before {@link #THE_INSTANT}. */
  private static final String RATED_LONG_AGO = "Q0900703";

  /** A promotion whose rating was last written after {@link #THE_INSTANT}. */
  private static final String RATED_RECENTLY = "Q0900705";

  /** A promotion rated at {@link #WHEN} and rated again at {@link #AGAIN}. */
  private static final String RE_RATED = "Q0900706";

  /** The boundary the filtering tests give to {@code --rated-since}. */
  private static final String THE_INSTANT = "2026-06-01T00:00:00Z";

  /** After {@link #THE_INSTANT} — when the two later writes happened. */
  private static final Instant AGAIN = Instant.parse("2026-07-01T00:00:00Z");

  @TempDir private Path home;

  private Logger rootLogger;
  private Level originalLevel;
  private ListAppender<ILoggingEvent> captured;

  @BeforeEach
  void setUp() {
    captured = new ListAppender<>();
    captured.start();
    rootLogger = (Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    originalLevel = rootLogger.getLevel();
    rootLogger.setLevel(Level.INFO);
    rootLogger.addAppender(captured);
  }

  @AfterEach
  void tearDown() {
    rootLogger.detachAppender(captured);
    rootLogger.setLevel(originalLevel);
  }

  @Test
  @DisplayName("--db is required, and the refusal quotes the default it would have resolved to")
  void shouldRefuseWhenNoDatabaseIsNamed() {
    assertThatThrownBy(() -> ExpandCli.parse(new String[] {}, null, home.toString()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("--db is required")
        .hasMessageContaining(home.resolve(".segue").resolve("segue.db").toString());
    assertThat(home.resolve(".segue")).doesNotExist();
  }

  @Test
  @DisplayName("SEGUE_DB does not satisfy --db, and the refusal quotes the path it would have used")
  void shouldRefuseWhenOnlySegueDbNamesTheDatabase() {
    String envDatabase = home.resolve("elsewhere.db").toString();

    assertThatThrownBy(() -> ExpandCli.parse(new String[] {}, envDatabase, home.toString()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("--db is required")
        .hasMessageContaining(envDatabase);
  }

  @Test
  @DisplayName("--db given twice is refused, because last-wins is worst on the flag read back")
  void shouldRefuseWhenTheDatabaseIsNamedTwice() {
    assertThatThrownBy(
            () -> ExpandCli.parse(new String[] {"--db", "a", "--db", "b"}, null, home.toString()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("was given twice");
  }

  @Test
  @DisplayName("--max-new-edges defaults to the shared expansion bound when none is given")
  void shouldDefaultToTheSharedBoundWhenNoMaxNewEdgesIsGiven() {
    assertThat(
            ExpandCli.parse(new String[] {"--db", "db.sqlite"}, null, home.toString())
                .maxNewEdges())
        .as(
            "the literal, not ExpandContext.defaults().maxNewEdges() — reading the same constant"
                + " the parser reads makes this assertion true by construction and blind to the"
                + " one thing worth seeing. The default bound is stated in THREE places that"
                + " nothing reconciles: ExpandContext.defaults(), SegueProperties' fallback and"
                + " application.yaml's segue.max-new-edges, the last of which is the MCP server's"
                + " actual runtime default. Set the yaml to 50 and expand_entity moves while this"
                + " tool does not; with the literal here, that drift is at least visible")
        .isEqualTo(200);
  }

  @Test
  @DisplayName("--max-new-edges is honoured when one is given")
  void shouldHonourTheBoundWhenOneIsGiven() {
    assertThat(
            ExpandCli.parse(
                    new String[] {"--db", "db.sqlite", "--max-new-edges", "25"},
                    null,
                    home.toString())
                .maxNewEdges())
        .isEqualTo(25);
  }

  @Test
  @DisplayName("--max-new-edges at or below zero is refused before any entity is expanded")
  void shouldRefuseTheBoundWhenItIsNotPositive() {
    assertThatThrownBy(
            () ->
                ExpandCli.parse(
                    new String[] {"--db", "db.sqlite", "--max-new-edges", "0"},
                    null,
                    home.toString()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("--max-new-edges must be positive");
  }

  @Test
  @DisplayName("--max-new-edges that is not a number is refused with a usage error")
  void shouldRefuseTheBoundWhenItIsNotANumber() {
    assertThatThrownBy(
            () ->
                ExpandCli.parse(
                    new String[] {"--db", "db.sqlite", "--max-new-edges", "lots"},
                    null,
                    home.toString()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("--max-new-edges");
  }

  @Test
  @DisplayName("a run is not a dry run unless the flag is given")
  void shouldNotBeADryRunWhenTheFlagIsAbsent() {
    assertThat(ExpandCli.parse(new String[] {"--db", "db.sqlite"}, null, home.toString()).dryRun())
        .isFalse();
  }

  @Test
  @DisplayName("a run is a dry run when the flag is given")
  void shouldBeADryRunWhenTheFlagIsGiven() {
    assertThat(
            ExpandCli.parse(new String[] {"--db", "db.sqlite", "--dry-run"}, null, home.toString())
                .dryRun())
        .isTrue();
  }

  @Test
  @DisplayName("a database that is not there is refused before anything is created")
  void shouldRefuseWhenTheDatabaseDoesNotExist() {
    // Both sqlite constructors create the file and its schema if absent, so without this refusal a
    // mistyped path would replay an empty log, find no promotions and report a clean run over
    // nothing — which reads as "you have no promotions" rather than as an error.
    Path absent = home.resolve("nothing.db");

    assertThatThrownBy(
            () -> ExpandCli.run(new String[] {"--db", absent.toString()}, null, "/home/invented"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("no segue database at")
        .hasMessageContaining("nothing to expand")
        .hasMessageNotContaining("--db is required");

    assertThat(absent).as("the database was not created").doesNotExist();
  }

  @Test
  @DisplayName("a missing --db is refused before a missing database, so the operator is told which")
  void shouldRefuseTheMissingFlagBeforeTheMissingFileWhenNeitherIsGiven() {
    // The property is an ORDER, and it is only visible through run(): parse() has to refuse first,
    // or the operator is told "no segue database at ..." about a path they never typed. Asserting
    // the sentence that must NOT appear is what makes this a test of the order.
    assertThatThrownBy(() -> ExpandCli.run(new String[] {}, null, home.toString()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("--db is required")
        .hasMessageNotContaining("no segue database");

    assertThat(home.resolve(".segue"))
        .as("no database was opened, so none was created")
        .doesNotExist();
  }

  @Test
  @DisplayName("a dry run over a real scratch database prints the block and writes nothing")
  void shouldPrintTheDryRunBlockWhenTheDatabaseHoldsRatedEntities() {
    Path db = scratchDatabase();
    int rowsBefore = rows(db);
    captured.list.clear();

    ExpandCli.main(new String[] {"--db", db.toString(), "--dry-run"});

    List<String> everyLine =
        List.copyOf(captured.list).stream().map(ILoggingEvent::getFormattedMessage).toList();
    assertThat(everyLine)
        .as("the dry-run block reached the log")
        .contains(ExpansionReport.DRY_RUN_HEADER)
        .anyMatch(line -> line.startsWith("  considered"));
    assertThat(everyLine)
        .as(
            "one of the two ratings is at PROMOTION_RATING and one is below it, so exactly one"
                + " promotion is considered and the graph holds its node")
        .anyMatch(line -> line.matches("^  considered\\s+1$"))
        .anyMatch(line -> line.matches("^  in the graph\\s+1$"));
    assertThat(rows(db)).as("a dry run appends nothing").isEqualTo(rowsBefore);
  }

  /** Two invented entities, both nodes; one rated at the promotion threshold and one below it. */
  private Path scratchDatabase() {
    Path db = home.resolve("scratch.db");
    Provenance sourced = new Provenance("invented", "invented:1", WHEN, 1.0);
    try (SqliteAssertionLog log = new SqliteAssertionLog(db);
        SqliteAffinityStore affinity = new SqliteAffinityStore(db)) {
      log.append(new NodeAssertion(PROMOTED, NodeKind.GROUP, "an invented act", sourced));
      log.append(new NodeAssertion(SHRUGGED_AT, NodeKind.GROUP, "another invented act", sourced));
      affinity.put(new AffinityRecord(PROMOTED, KnownList.PROMOTION_RATING, null, WHEN));
      affinity.put(new AffinityRecord(SHRUGGED_AT, KnownList.PROMOTION_RATING - 1, null, WHEN));
    }
    return db;
  }

  private static int rows(Path db) {
    try (SqliteAssertionLog log = new SqliteAssertionLog(db)) {
      return log.readAll().size();
    }
  }

  @Test
  @DisplayName("--rated-since is carried as the parsed instant when one is given")
  void shouldCarryTheInstantWhenRatedSinceIsGiven() {
    assertThat(
            ExpandCli.parse(
                    new String[] {"--db", "db.sqlite", "--rated-since", "2026-09-08T00:00:00Z"},
                    null,
                    home.toString())
                .ratedSince())
        .contains(Instant.parse("2026-09-08T00:00:00Z"));
  }

  @Test
  @DisplayName("no instant is carried when the flag is absent, which is every run before this one")
  void shouldCarryNoInstantWhenRatedSinceIsAbsent() {
    assertThat(
            ExpandCli.parse(new String[] {"--db", "db.sqlite"}, null, home.toString()).ratedSince())
        .isEmpty();
  }

  @Test
  @DisplayName("--known is carried as the path when one is given, and the file is not read here")
  void shouldCarryTheKnownFileWhenOneIsGiven() {
    assertThat(
            ExpandCli.parse(
                    new String[] {"--db", "db.sqlite", "--known", "known.csv"},
                    null,
                    home.toString())
                .known())
        .as("parse opens nothing: the guide's examples are parsed against an invented home")
        .contains(Path.of("known.csv"));
  }

  @Test
  @DisplayName("no file is carried when the flag is absent, which is every run before this one")
  void shouldCarryNoKnownFileWhenTheFlagIsAbsent() {
    assertThat(ExpandCli.parse(new String[] {"--db", "db.sqlite"}, null, home.toString()).known())
        .isEmpty();
  }

  @Test
  @DisplayName("--known and --rated-since together are refused: they name different populations")
  void shouldRefuseBothFlagsWhenAKnownFileAndAnInstantAreGiven() {
    assertThatThrownBy(
            () ->
                ExpandCli.parse(
                    new String[] {
                      "--db", "db.sqlite",
                      "--known", "known.csv",
                      "--rated-since", "2026-09-08T00:00:00Z"
                    },
                    null,
                    home.toString()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("--known and --rated-since name different populations")
        .hasMessageContaining("--db <segue.db>");
  }

  @Test
  @DisplayName("--rated-since that is not an instant is refused with this tool's usage error")
  void shouldRefuseTheInstantWhenItIsNotAnInstant() {
    assertThatThrownBy(
            () ->
                ExpandCli.parse(
                    new String[] {"--db", "db.sqlite", "--rated-since", "last Tuesday"},
                    null,
                    home.toString()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(
            "--rated-since takes an ISO-8601 instant like 2026-09-06T15:00:00Z, got last Tuesday")
        .hasMessageContaining("[--rated-since");
  }

  @Test
  @DisplayName("--rated-since given twice is refused, because last-wins is worst on a filter")
  void shouldRefuseTheInstantWhenItIsGivenTwice() {
    assertThatThrownBy(
            () ->
                ExpandCli.parse(
                    new String[] {
                      "--db",
                      "db.sqlite",
                      "--rated-since",
                      "2026-09-06T15:00:00Z",
                      "--rated-since",
                      "2026-09-08T00:00:00Z"
                    },
                    null,
                    home.toString()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("was given twice");
  }

  @Test
  @DisplayName("an unknown option is refused with a usage error")
  void shouldRefuseAnUnknownOptionWhenOneIsGiven() {
    assertThatThrownBy(
            () ->
                ExpandCli.parse(
                    new String[] {"--db", "db.sqlite", "--frobnicate", "x"}, null, home.toString()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unknown option --frobnicate");
  }

  @Test
  @DisplayName("only the promotions rated since the instant are considered, and the rest drop out")
  void shouldConsiderOnlyTheRecentPromotionsWhenAnInstantIsGiven() {
    Path db = threePromotions();

    captured.list.clear();
    ExpandCli.main(new String[] {"--db", db.toString(), "--dry-run"});
    int withoutTheInstant = countOn(lines(), "considered");

    captured.list.clear();
    ExpandCli.main(new String[] {"--db", db.toString(), "--dry-run", "--rated-since", THE_INSTANT});
    int withTheInstant = countOn(lines(), "considered");

    assertThat(withoutTheInstant)
        .as("all three promotions, or the drop below is a drop from nothing")
        .isEqualTo(3);
    assertThat(withTheInstant)
        .as("the one rated before the instant is gone; the recent one and the re-rated one stay")
        .isEqualTo(2);
  }

  @Test
  @DisplayName("a promotion rated long ago and re-rated after the instant is expanded again")
  void shouldConsiderThePromotionWhenItWasRatedAgainAfterTheInstant() {
    Path db = reRatedBesideOneThatNeverWas();

    captured.list.clear();
    ExpandCli.main(new String[] {"--db", db.toString(), "--dry-run"});
    int withoutTheInstant = countOn(lines(), "considered");

    captured.list.clear();
    ExpandCli.main(new String[] {"--db", db.toString(), "--dry-run", "--rated-since", THE_INSTANT});
    int withTheInstant = countOn(lines(), "considered");

    assertThat(withoutTheInstant)
        .as("both promotions, or the drop below is a drop from nothing")
        .isEqualTo(2);
    assertThat(withTheInstant)
        .as("the re-rated promotion is the only one left; the one never re-rated is gone")
        .isEqualTo(1);
  }

  /**
   * Three invented entities, all nodes, all rated at the promotion threshold: one whose rating was
   * last written before the instant, one after it, and one written twice.
   */
  private Path threePromotions() {
    Path db = home.resolve("three.db");
    Provenance sourced = new Provenance("invented", "invented:3", WHEN, 1.0);
    try (SqliteAssertionLog log = new SqliteAssertionLog(db);
        SqliteAffinityStore affinity = new SqliteAffinityStore(db)) {
      log.append(new NodeAssertion(RATED_LONG_AGO, NodeKind.GROUP, "an invented act", sourced));
      log.append(
          new NodeAssertion(RATED_RECENTLY, NodeKind.GROUP, "another invented act", sourced));
      log.append(new NodeAssertion(RE_RATED, NodeKind.GROUP, "a third invented act", sourced));
      affinity.put(new AffinityRecord(RATED_LONG_AGO, KnownList.PROMOTION_RATING, null, WHEN));
      affinity.put(new AffinityRecord(RATED_RECENTLY, KnownList.PROMOTION_RATING, null, AGAIN));
      reRate(affinity);
    }
    return db;
  }

  /**
   * The re-rated promotion beside one that was never re-rated.
   *
   * <p>The second promotion is what makes the test capable of failing: a database holding the
   * re-rated entity alone reports one promotion considered whether the filter ran or not.
   */
  private Path reRatedBesideOneThatNeverWas() {
    Path db = home.resolve("re-rated.db");
    Provenance sourced = new Provenance("invented", "invented:4", WHEN, 1.0);
    try (SqliteAssertionLog log = new SqliteAssertionLog(db);
        SqliteAffinityStore affinity = new SqliteAffinityStore(db)) {
      log.append(new NodeAssertion(RE_RATED, NodeKind.GROUP, "a third invented act", sourced));
      log.append(new NodeAssertion(RATED_LONG_AGO, NodeKind.GROUP, "an invented act", sourced));
      affinity.put(new AffinityRecord(RATED_LONG_AGO, KnownList.PROMOTION_RATING, null, WHEN));
      reRate(affinity);
    }
    return db;
  }

  /**
   * Written at {@link #WHEN} and then written again at {@link #AGAIN}, on purpose.
   *
   * <p>{@code SqliteAffinityStore.put} upserts one row per entity (ADR 39), so the second write is
   * what {@code updated_at} holds — and a fixture that only ever wrote the later value would pass
   * without the re-rating being what makes it pass.
   */
  private static void reRate(SqliteAffinityStore affinity) {
    affinity.put(new AffinityRecord(RE_RATED, KnownList.PROMOTION_RATING, null, WHEN));
    affinity.put(new AffinityRecord(RE_RATED, KnownList.PROMOTION_RATING, null, AGAIN));
  }

  private List<String> lines() {
    return List.copyOf(captured.list).stream().map(ILoggingEvent::getFormattedMessage).toList();
  }

  /** The number on the block's row for this label — the padding is not the subject here. */
  private static int countOn(List<String> everyLine, String label) {
    String row =
        everyLine.stream()
            .filter(line -> line.strip().startsWith(label))
            .findFirst()
            .orElseThrow(
                () -> new AssertionError("no '" + label + "' row in the block: " + everyLine));
    return Integer.parseInt(row.strip().substring(label.length()).strip());
  }
}
