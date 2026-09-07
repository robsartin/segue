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
  @DisplayName("an unknown option is refused with a usage error")
  void shouldRefuseAnUnknownOptionWhenOneIsGiven() {
    assertThatThrownBy(
            () ->
                ExpandCli.parse(
                    new String[] {"--db", "db.sqlite", "--frobnicate", "x"}, null, home.toString()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unknown option --frobnicate");
  }
}
