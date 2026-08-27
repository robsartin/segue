package com.robsartin.segue.sqlite;

import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.segue.domain.AffinityRecord;
import com.robsartin.segue.domain.NodeAssertion;
import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.domain.Provenance;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The taste layer's store: the same SQLite file as the assertion log, its own table (ADR 33 rejects
 * a second database file), one row per entity (ADR 39 chose overwrite over a history table).
 *
 * <p>Every rating and note here is invented, and the qids are the Q9000xx placeholders the graph
 * fixture uses. ADR 33 (as amended by issue #37) names a test fixture written from real ratings as
 * one of the ways this public repository could leak the only personal data segue holds.
 */
class SqliteAffinityStoreTest {

  private static final Instant FIRST = Instant.parse("2026-08-25T09:00:00.123456Z");
  private static final Instant LATER = Instant.parse("2026-08-26T21:30:00Z");

  @Test
  @DisplayName("an entity with no affinity reads back empty, not a default rating")
  void unratedReadsEmpty() {
    try (SqliteAffinityStore store = SqliteAffinityStore.inMemory()) {
      assertThat(store.find("Q900001")).isEmpty();
    }
  }

  @Test
  @DisplayName("a rating with a note round-trips exactly, sub-second timestamp included")
  void roundTripsWithNote() {
    AffinityRecord affinity =
        new AffinityRecord("Q900001", 4, "invented note for the test suite", FIRST);

    try (SqliteAffinityStore store = SqliteAffinityStore.inMemory()) {
      store.put(affinity);

      assertThat(store.find("Q900001")).contains(affinity);
    }
  }

  @Test
  @DisplayName("a rating with no note round-trips with the note still null")
  void roundTripsWithoutNote() {
    AffinityRecord affinity = new AffinityRecord("Q900002", 2, null, FIRST);

    try (SqliteAffinityStore store = SqliteAffinityStore.inMemory()) {
      store.put(affinity);

      assertThat(store.find("Q900002")).contains(affinity);
    }
  }

  @Test
  @DisplayName("re-rating overwrites in place: one row, the later rating, the later timestamp")
  void reRatingOverwritesInPlace(@TempDir Path dir) throws SQLException {
    Path db = dir.resolve("segue.db");
    AffinityRecord first = new AffinityRecord("Q900001", 2, "first impression, invented", FIRST);
    AffinityRecord second = new AffinityRecord("Q900001", 5, "grew on me, also invented", LATER);

    try (SqliteAffinityStore store = new SqliteAffinityStore(db)) {
      store.put(first);
      store.put(second);

      assertThat(store.find("Q900001")).contains(second);
    }
    // Read the table directly, because "latest wins" and "one row per entity" are different
    // claims and only the second one rules out a history table accumulating behind the port.
    assertThat(rowCount(db, "affinity")).isEqualTo(1);
  }

  @Test
  @DisplayName("re-rating can clear a note the earlier rating carried")
  void reRatingCanClearTheNote() {
    try (SqliteAffinityStore store = SqliteAffinityStore.inMemory()) {
      store.put(new AffinityRecord("Q900001", 3, "an invented note", FIRST));
      store.put(new AffinityRecord("Q900001", 3, null, LATER));

      assertThat(store.find("Q900001")).contains(new AffinityRecord("Q900001", 3, null, LATER));
    }
  }

  @Test
  @DisplayName("affinity survives a restart, because it is in SQLite like everything else")
  void persistsAcrossReopen(@TempDir Path dir) {
    Path db = dir.resolve("segue.db");
    AffinityRecord affinity = new AffinityRecord("Q900001", 5, "invented note", FIRST);

    try (SqliteAffinityStore store = new SqliteAffinityStore(db)) {
      store.put(affinity);
    }
    try (SqliteAffinityStore reopened = new SqliteAffinityStore(db)) {
      assertThat(reopened.find("Q900001")).contains(affinity);
    }
  }

  @Test
  @DisplayName("the two layers share one file and never share a table (ADR 33)")
  void sharesTheFileButNotTheTable(@TempDir Path dir) throws SQLException {
    Path db = dir.resolve("segue.db");

    try (SqliteAssertionLog log = new SqliteAssertionLog(db);
        SqliteAffinityStore affinity = new SqliteAffinityStore(db)) {
      log.append(
          new NodeAssertion(
              "Q900001",
              NodeKind.PERSON,
              "A Placeholder Person",
              new Provenance("wikidata", "Q900001", FIRST, 1.0)));
      affinity.put(new AffinityRecord("Q900001", 4, "invented note", FIRST));

      // Neither store can see the other's rows: the log replays one assertion and no rating,
      // and the affinity table holds one rating and no assertion.
      assertThat(log.readAll()).hasSize(1);
      assertThat(affinity.find("Q900001")).isPresent();
    }
    assertThat(rowCount(db, "assertion")).isEqualTo(1);
    assertThat(rowCount(db, "affinity")).isEqualTo(1);
  }

  @Test
  @DisplayName("readAll returns every rating, in qid order, so the caller decides the ordering")
  void readsEveryRatingInQidOrder() {
    try (SqliteAffinityStore store = SqliteAffinityStore.inMemory()) {
      store.put(new AffinityRecord("Q900003", 5, "an invented note", LATER));
      store.put(new AffinityRecord("Q900001", 2, null, FIRST));
      store.put(new AffinityRecord("Q900002", 4, "another invented note", FIRST));

      assertThat(store.readAll())
          .extracting(AffinityRecord::qid)
          .containsExactly("Q900001", "Q900002", "Q900003");
    }
  }

  @Test
  @DisplayName("readAll carries the whole row, note and timestamp included")
  void readsTheWholeRow() {
    AffinityRecord affinity = new AffinityRecord("Q900001", 3, "an invented note", FIRST);

    try (SqliteAffinityStore store = SqliteAffinityStore.inMemory()) {
      store.put(affinity);

      assertThat(store.readAll()).containsExactly(affinity);
    }
  }

  @Test
  @DisplayName("readAll on an unrated store is empty, not an error")
  void readsNothingWhenNothingIsRated() {
    try (SqliteAffinityStore store = SqliteAffinityStore.inMemory()) {
      assertThat(store.readAll()).isEmpty();
    }
  }

  private static int rowCount(Path db, String table) throws SQLException {
    try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + db);
        Statement st = conn.createStatement();
        ResultSet rs = st.executeQuery("SELECT count(*) FROM " + table)) {
      rs.next();
      return rs.getInt(1);
    }
  }
}
