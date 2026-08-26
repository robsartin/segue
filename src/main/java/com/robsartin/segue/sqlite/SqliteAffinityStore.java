package com.robsartin.segue.sqlite;

import com.robsartin.segue.domain.AffinityRecord;
import com.robsartin.segue.port.AffinityStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.Optional;

/**
 * The taste layer in the same SQLite file as the assertion log, in its own table (ADR 33, ADR 39).
 *
 * <p><b>Same file, different table, different connection.</b> ADR 33 considered a separate database
 * file and rejected it: separate tables behind a separate port already express the boundary, and a
 * second file buys two connection lifecycles and no transactional relationship. A second connection
 * to the one file is cheap by comparison, and it keeps this class independent of {@link
 * SqliteAssertionLog} - neither opens, migrates or closes the other, and this store can be
 * constructed in a test with no log in sight.
 *
 * <p><b>One row per entity, enforced by the schema.</b> {@code qid} is the primary key and the
 * write is an {@code ON CONFLICT DO UPDATE} upsert, so ADR 39's overwrite decision is a property of
 * the table rather than of the code path that happens to write it. There is no history table and no
 * soft-deleted previous row: a second rating replaces the first in place, which is what makes ADR
 * 33's "affinity can be deleted wholesale" a one-line {@code DELETE} whenever it is wanted.
 *
 * <p><b>Nothing here logs.</b> Not the rating, not the note, not on the error paths. Affinity is
 * personal data (ADR 16, ADR 33) and this class is where all of it passes through, so the rule is
 * kept the simple way: no logger field exists to misuse, and the exception messages name the qid
 * and the operation only.
 *
 * <p>Like {@link SqliteAssertionLog}, this touches only {@code java.sql} and stores the instant as
 * an ISO-8601 string so sub-second precision survives.
 */
public final class SqliteAffinityStore implements AffinityStore {

  private static final String SCHEMA =
      """
      CREATE TABLE IF NOT EXISTS affinity (
        qid        TEXT PRIMARY KEY,
        rating     INTEGER NOT NULL,
        note       TEXT,
        updated_at TEXT NOT NULL
      )
      """;

  /**
   * The upsert ADR 39's overwrite decision needs. {@code excluded} is SQLite's name for the row
   * that would have been inserted, so every column - including a note being cleared back to null -
   * takes the new value.
   */
  private static final String UPSERT =
      "INSERT INTO affinity (qid, rating, note, updated_at) VALUES (?, ?, ?, ?)"
          + " ON CONFLICT(qid) DO UPDATE SET rating = excluded.rating, note = excluded.note,"
          + " updated_at = excluded.updated_at";

  private static final String SELECT_ONE =
      "SELECT qid, rating, note, updated_at FROM affinity WHERE qid = ?";

  private final Connection conn;

  /**
   * Open (creating if absent) the taste layer stored in {@code dbFile}, and its parent directory.
   */
  public SqliteAffinityStore(Path dbFile) {
    this(createParentDirectories(dbFile));
  }

  private static String createParentDirectories(Path dbFile) {
    Path parent = dbFile.toAbsolutePath().getParent();
    if (parent != null) {
      try {
        Files.createDirectories(parent);
      } catch (IOException e) {
        throw new IllegalStateException("cannot create directory " + parent, e);
      }
    }
    return "jdbc:sqlite:" + dbFile;
  }

  /** A throwaway in-memory store, for tests. Its data lives only as long as this instance. */
  public static SqliteAffinityStore inMemory() {
    return new SqliteAffinityStore("jdbc:sqlite::memory:");
  }

  private SqliteAffinityStore(String jdbcUrl) {
    try {
      this.conn = DriverManager.getConnection(jdbcUrl);
      try (Statement st = conn.createStatement()) {
        st.execute(SCHEMA);
      }
    } catch (SQLException e) {
      throw new IllegalStateException("cannot open affinity store at " + jdbcUrl, e);
    }
  }

  @Override
  public void put(AffinityRecord affinity) {
    try (PreparedStatement ps = conn.prepareStatement(UPSERT)) {
      ps.setString(1, affinity.qid());
      ps.setInt(2, affinity.rating());
      ps.setString(3, affinity.note());
      ps.setString(4, affinity.updatedAt().toString());
      ps.executeUpdate();
    } catch (SQLException e) {
      // The qid, and nothing else. A message carrying the rating or the note would put personal
      // data into whatever logs this exception (ADR 33).
      throw new IllegalStateException("cannot store affinity for " + affinity.qid(), e);
    }
  }

  @Override
  public Optional<AffinityRecord> find(String qid) {
    try (PreparedStatement ps = conn.prepareStatement(SELECT_ONE)) {
      ps.setString(1, qid);
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) {
          return Optional.empty();
        }
        return Optional.of(
            new AffinityRecord(
                rs.getString("qid"),
                rs.getInt("rating"),
                rs.getString("note"),
                Instant.parse(rs.getString("updated_at"))));
      }
    } catch (SQLException e) {
      throw new IllegalStateException("cannot read affinity for " + qid, e);
    }
  }

  @Override
  public void close() {
    try {
      conn.close();
    } catch (SQLException e) {
      throw new IllegalStateException("cannot close affinity store", e);
    }
  }
}
