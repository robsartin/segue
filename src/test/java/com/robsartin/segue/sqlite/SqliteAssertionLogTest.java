package com.robsartin.segue.sqlite;

import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.segue.domain.AssertionRecord;
import com.robsartin.segue.domain.LoggedAssertion;
import com.robsartin.segue.domain.NodeAssertion;
import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.domain.Provenance;
import com.robsartin.segue.domain.Retraction;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteAssertionLogTest {

  private static final Provenance WIKIDATA =
      new Provenance("wikidata", "Q5593", Instant.parse("2026-08-24T10:15:30.123456Z"), 1.0);
  // No sourceRef, and a fractional-second instant, to exercise the null and precision paths.
  private static final Provenance LLM =
      new Provenance("llm:claude", null, Instant.parse("2026-08-22T00:00:00Z"), 0.30);

  @Test
  @DisplayName("an empty log reads back empty")
  void emptyReadsEmpty() {
    try (SqliteAssertionLog log = SqliteAssertionLog.inMemory()) {
      assertThat(log.readAll()).isEmpty();
    }
  }

  @Test
  @DisplayName("both claim kinds round-trip exactly, in append order")
  void roundTripsBothKindsInOrder() {
    NodeAssertion node = new NodeAssertion("Q5593", NodeKind.PERSON, "Pablo Picasso", WIKIDATA);
    AssertionRecord datedEdge =
        new AssertionRecord(
            "Q900001",
            "Q900002",
            "MEMBER_OF",
            LocalDate.of(1983, 1, 1),
            LocalDate.of(2003, 6, 30),
            WIKIDATA);
    AssertionRecord openEdge =
        new AssertionRecord("Q900001", "Q900013", "INFLUENCED_BY", null, null, LLM);

    try (SqliteAssertionLog log = SqliteAssertionLog.inMemory()) {
      log.append(node);
      log.append(datedEdge);
      log.append(openEdge);

      // Exact equality proves every field, both validity dates, the null sourceRef, the
      // sub-second instant, and the confidence all survive the round trip.
      assertThat(log.readAll()).containsExactly(node, datedEdge, openEdge);
    }
  }

  @Test
  @DisplayName("a node claim's P31 classes round-trip, in the order the source stated them")
  void roundTripsInstanceOfClasses() {
    // Issue #60: without this the derived kind is the only thing kept, and every KindMapper
    // improvement needs the entity fetched from Wikidata again to take effect.
    NodeAssertion node =
        new NodeAssertion(
            "Q16473", NodeKind.PERSON, "Steve Martin", List.of("Q5", "Q177220"), WIKIDATA);

    try (SqliteAssertionLog log = SqliteAssertionLog.inMemory()) {
      log.append(node);

      assertThat(log.readAll())
          .singleElement()
          .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.type(NodeAssertion.class))
          .extracting(NodeAssertion::instanceOf)
          .isEqualTo(List.of("Q5", "Q177220"));
    }
  }

  @Test
  @DisplayName("a source that states no classes reads back with an empty list, not a null")
  void roundTripsAbsentInstanceOf() {
    NodeAssertion node = new NodeAssertion("Q5593", NodeKind.PERSON, "Pablo Picasso", WIKIDATA);

    try (SqliteAssertionLog log = SqliteAssertionLog.inMemory()) {
      log.append(node);

      assertThat(log.readAll()).containsExactly(node);
    }
  }

  @Test
  @DisplayName("the log persists to its file and replays across reopen")
  void persistsAcrossReopen(@TempDir Path dir) {
    Path db = dir.resolve("segue.db");
    NodeAssertion node = new NodeAssertion("Q5593", NodeKind.PERSON, "Pablo Picasso", WIKIDATA);

    try (SqliteAssertionLog log = new SqliteAssertionLog(db)) {
      log.append(node);
    }
    try (SqliteAssertionLog reopened = new SqliteAssertionLog(db)) {
      assertThat(reopened.readAll()).containsExactly((LoggedAssertion) node);
    }
  }

  @Test
  @DisplayName("a retraction round-trips like any other row")
  void roundTripsARetraction() {
    // ADR 44: a retraction is appended, never applied to the rows it retracts. The log after
    // one still holds every original claim - that is the whole decision, seen at the storage
    // layer.
    NodeAssertion node = new NodeAssertion("Q900101", NodeKind.PERSON, "Wren Alderman", WIKIDATA);
    AssertionRecord edge =
        new AssertionRecord("Q900101", "Q900102", "MEMBER_OF", null, null, WIKIDATA);
    Retraction retraction =
        new Retraction(
            "Q900101",
            "resolved to the wrong entity",
            Instant.parse("2026-08-27T11:22:33.456789Z"));

    try (SqliteAssertionLog log = SqliteAssertionLog.inMemory()) {
      log.append(node);
      log.append(edge);
      log.append(retraction);

      assertThat(log.readAll()).containsExactly(node, edge, retraction);
    }
  }

  @Test
  @DisplayName(
      "an existing database written before ADR 44 gains the reason column and keeps its rows")
  void migratesADatabaseWrittenBeforeRetractionsExisted(@TempDir Path dir) throws Exception {
    // The real database holds tens of thousands of world facts and, unlike ADR 42's change,
    // deleting and re-seeding is no longer automatically available: ADR 42's own note says the
    // next schema change gets a migration, because affinity cannot be regenerated. This is that
    // migration, driven from a file created with the OLD schema rather than from a mock.
    Path db = dir.resolve("segue.db");
    writeSchemaWithoutReason(db);

    NodeAssertion existing =
        new NodeAssertion("Q900101", NodeKind.PERSON, "Wren Alderman", WIKIDATA);
    Retraction retraction =
        new Retraction("Q900101", "invented", Instant.parse("2026-08-27T11:22:33Z"));

    try (SqliteAssertionLog log = new SqliteAssertionLog(db)) {
      log.append(existing);
      log.append(retraction);

      assertThat(log.readAll()).containsExactly(existing, retraction);
    }
  }

  @Test
  @DisplayName("opening an already-migrated database twice does not try to add the column again")
  void theMigrationIsIdempotent(@TempDir Path dir) throws Exception {
    Path db = dir.resolve("segue.db");
    writeSchemaWithoutReason(db);

    try (SqliteAssertionLog log = new SqliteAssertionLog(db)) {
      log.append(new Retraction("Q900101", "invented", Instant.parse("2026-08-27T11:22:33Z")));
    }
    try (SqliteAssertionLog reopened = new SqliteAssertionLog(db)) {
      assertThat(reopened.readAll()).hasSize(1);
    }
  }

  /** The assertion table exactly as it stood before ADR 44 - no {@code reason} column. */
  private static void writeSchemaWithoutReason(Path db) throws Exception {
    try (java.sql.Connection conn = java.sql.DriverManager.getConnection("jdbc:sqlite:" + db);
        java.sql.Statement st = conn.createStatement()) {
      st.execute(
          """
          CREATE TABLE assertion (
            seq         INTEGER PRIMARY KEY AUTOINCREMENT,
            kind        TEXT NOT NULL,
            qid         TEXT NOT NULL,
            to_qid      TEXT,
            type_code   TEXT,
            node_kind   TEXT,
            instance_of TEXT,
            label       TEXT,
            valid_from  TEXT,
            valid_to    TEXT,
            source_id   TEXT NOT NULL,
            source_ref  TEXT,
            asserted_at TEXT NOT NULL,
            confidence  REAL NOT NULL
          )
          """);
    }
  }

  @Test
  @DisplayName("a first run creates missing parent directories, matching the class's own claim")
  void createsMissingParentDirectories(@TempDir Path dir) {
    Path db = dir.resolve(".segue").resolve("segue.db");

    try (SqliteAssertionLog log = new SqliteAssertionLog(db)) {
      log.append(new NodeAssertion("Q5593", NodeKind.PERSON, "Pablo Picasso", WIKIDATA));
    }

    assertThat(db).exists();
  }
}
