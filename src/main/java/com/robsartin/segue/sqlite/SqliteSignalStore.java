package com.robsartin.segue.sqlite;

import com.robsartin.segue.domain.Signal;
import com.robsartin.segue.port.SignalStore;
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
import java.util.ArrayList;
import java.util.List;

/**
 * Listening-source observations in the same SQLite file as the log and the taste layer, in their
 * own table (#350, ADR 33's reasoning for affinity applied again).
 *
 * <p>One row per entity and source, enforced by the primary key: the write is an upsert, so
 * re-running a source overwrites its own rows. A new table, created if absent, so there is nothing
 * to migrate (ADR 44's lesson that a schema change to first-person data needs a path does not bite
 * here: {@code CREATE TABLE IF NOT EXISTS} touches no existing row).
 *
 * <p><b>Nothing here logs.</b> Not a count, not a flag, not a name: the exception messages name the
 * qid and the operation only, and the write is a {@code PreparedStatement} so no value is ever
 * concatenated into SQL that sqlite-jdbc would then log at TRACE (see {@link SqliteAffinityStore}).
 * Instants are stored as ISO-8601 strings, as everywhere else.
 */
public final class SqliteSignalStore implements SignalStore {

  private static final String SCHEMA =
      """
      CREATE TABLE IF NOT EXISTS signal (
        qid         TEXT NOT NULL,
        source      TEXT NOT NULL,
        play_count  INTEGER NOT NULL,
        loved       INTEGER NOT NULL,
        favorited   INTEGER NOT NULL,
        observed_at TEXT NOT NULL,
        PRIMARY KEY (qid, source)
      )
      """;

  private static final String UPSERT =
      "INSERT INTO signal (qid, source, play_count, loved, favorited, observed_at)"
          + " VALUES (?, ?, ?, ?, ?, ?)"
          + " ON CONFLICT(qid, source) DO UPDATE SET play_count = excluded.play_count,"
          + " loved = excluded.loved, favorited = excluded.favorited,"
          + " observed_at = excluded.observed_at";

  private static final String SELECT_ALL =
      "SELECT qid, source, play_count, loved, favorited, observed_at FROM signal"
          + " ORDER BY qid, source";

  private final Connection conn;

  /** Open (creating if absent) the signal table stored in {@code dbFile}, and its parent. */
  public SqliteSignalStore(Path dbFile) {
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

  /** A throwaway in-memory store, for tests. */
  public static SqliteSignalStore inMemory() {
    return new SqliteSignalStore("jdbc:sqlite::memory:");
  }

  private SqliteSignalStore(String jdbcUrl) {
    try {
      this.conn = DriverManager.getConnection(jdbcUrl);
      try (Statement st = conn.createStatement()) {
        st.execute(SCHEMA);
      }
    } catch (SQLException e) {
      throw new IllegalStateException("cannot open signal store at " + jdbcUrl, e);
    }
  }

  @Override
  public void put(Signal signal) {
    try (PreparedStatement ps = conn.prepareStatement(UPSERT)) {
      ps.setString(1, signal.qid());
      ps.setString(2, signal.source());
      ps.setLong(3, signal.playCount());
      ps.setInt(4, signal.loved() ? 1 : 0);
      ps.setInt(5, signal.favorited() ? 1 : 0);
      ps.setString(6, signal.observedAt().toString());
      ps.executeUpdate();
    } catch (SQLException e) {
      // The qid and the operation, and nothing else: a count in this message would put personal
      // data into whatever logs the exception (ADR 33).
      throw new IllegalStateException("cannot store a signal for " + signal.qid(), e);
    }
  }

  @Override
  public List<Signal> readAll() {
    List<Signal> signals = new ArrayList<>();
    try (PreparedStatement ps = conn.prepareStatement(SELECT_ALL);
        ResultSet rs = ps.executeQuery()) {
      while (rs.next()) {
        signals.add(
            new Signal(
                rs.getString("qid"),
                rs.getString("source"),
                rs.getLong("play_count"),
                rs.getInt("loved") != 0,
                rs.getInt("favorited") != 0,
                Instant.parse(rs.getString("observed_at"))));
      }
      return List.copyOf(signals);
    } catch (SQLException e) {
      // No qid and no count: how much the owner has listened to is itself a fact about him.
      throw new IllegalStateException("cannot read the signal table", e);
    }
  }

  @Override
  public void close() {
    try {
      conn.close();
    } catch (SQLException e) {
      throw new IllegalStateException("cannot close signal store", e);
    }
  }
}
