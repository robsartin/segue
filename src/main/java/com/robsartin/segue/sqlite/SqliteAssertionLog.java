package com.robsartin.segue.sqlite;

import com.robsartin.segue.domain.AssertionRecord;
import com.robsartin.segue.domain.LoggedAssertion;
import com.robsartin.segue.domain.NodeAssertion;
import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.domain.Provenance;
import com.robsartin.segue.port.AssertionLog;
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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * The append-only log in a single SQLite file (ADR 24). One long-lived connection, one writer.
 *
 * <p>A {@link LoggedAssertion} is stored as one row discriminated by {@code kind}: a node claim
 * fills {@code node_kind}/{@code label}, an edge claim fills {@code to_qid}/{@code type_code} and
 * the validity dates; both carry their provenance. Sequence order is the autoincrement primary key,
 * which is exactly the replay order {@code GraphProjector} needs. The instant is stored as an
 * ISO-8601 string so sub-second precision survives - the truncation the Gremlin {@code
 * ProvenanceCodec} suffers is deliberately not repeated here.
 *
 * <p>This adapter touches only {@code java.sql}, not the driver, so the engine stays swappable.
 */
public final class SqliteAssertionLog implements AssertionLog {

  private static final String SCHEMA =
      """
      CREATE TABLE IF NOT EXISTS assertion (
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
      """;

  private static final String INSERT =
      "INSERT INTO assertion (kind, qid, to_qid, type_code, node_kind, instance_of, label,"
          + " valid_from, valid_to, source_id, source_ref, asserted_at, confidence) VALUES"
          + " (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

  private static final String SELECT_ALL =
      "SELECT kind, qid, to_qid, type_code, node_kind, instance_of, label, valid_from, valid_to,"
          + " source_id, source_ref, asserted_at, confidence FROM assertion ORDER BY seq";

  /**
   * The separator for the packed {@code instance_of} list: a space, with no escaping. Every value
   * is a QID, which {@link com.robsartin.segue.domain.NodeRecord} validates at construction, so no
   * value can contain the separator. That is {@code ProvenanceCodec}'s argument reached from the
   * other end - it forbids its separators in the free text it packs, where here the whole value is
   * constrained and nothing has to be forbidden. It also keeps the column readable in a SQL client
   * ("Q5 Q177220"), which a JSON array would not, and needs no library in an adapter that
   * deliberately touches only {@code java.sql}.
   */
  private static final String CLASS_SEP = " ";

  private final Connection conn;

  /** Open (creating if absent) a log stored in {@code dbFile}, and its parent directory. */
  public SqliteAssertionLog(Path dbFile) {
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

  /** A throwaway in-memory log, for tests. Its data lives only as long as this instance. */
  public static SqliteAssertionLog inMemory() {
    return new SqliteAssertionLog("jdbc:sqlite::memory:");
  }

  private SqliteAssertionLog(String jdbcUrl) {
    try {
      this.conn = DriverManager.getConnection(jdbcUrl);
      try (Statement st = conn.createStatement()) {
        st.execute(SCHEMA);
      }
    } catch (SQLException e) {
      throw new IllegalStateException("cannot open assertion log at " + jdbcUrl, e);
    }
  }

  @Override
  public void append(LoggedAssertion assertion) {
    try (PreparedStatement ps = conn.prepareStatement(INSERT)) {
      switch (assertion) {
        case NodeAssertion n -> {
          ps.setString(1, "NODE");
          ps.setString(2, n.qid());
          ps.setString(3, null);
          ps.setString(4, null);
          ps.setString(5, n.kind().name());
          ps.setString(6, encodeClasses(n.instanceOf()));
          ps.setString(7, n.label());
          ps.setString(8, null);
          ps.setString(9, null);
          bindProvenance(ps, n.provenance());
        }
        case AssertionRecord e -> {
          ps.setString(1, "EDGE");
          ps.setString(2, e.fromQid());
          ps.setString(3, e.toQid());
          ps.setString(4, e.typeCode());
          ps.setString(5, null);
          ps.setString(6, null);
          ps.setString(7, null);
          ps.setString(8, isoOrNull(e.validFrom()));
          ps.setString(9, isoOrNull(e.validTo()));
          bindProvenance(ps, e.provenance());
        }
      }
      ps.executeUpdate();
    } catch (SQLException ex) {
      throw new IllegalStateException("cannot append assertion", ex);
    }
  }

  @Override
  public List<LoggedAssertion> readAll() {
    List<LoggedAssertion> out = new ArrayList<>();
    try (PreparedStatement ps = conn.prepareStatement(SELECT_ALL);
        ResultSet rs = ps.executeQuery()) {
      while (rs.next()) {
        out.add(readRow(rs));
      }
    } catch (SQLException e) {
      throw new IllegalStateException("cannot read assertion log", e);
    }
    return out;
  }

  private static LoggedAssertion readRow(ResultSet rs) throws SQLException {
    Provenance provenance =
        new Provenance(
            rs.getString("source_id"),
            rs.getString("source_ref"),
            Instant.parse(rs.getString("asserted_at")),
            rs.getDouble("confidence"));
    return switch (rs.getString("kind")) {
      case "NODE" ->
          new NodeAssertion(
              rs.getString("qid"),
              NodeKind.valueOf(rs.getString("node_kind")),
              rs.getString("label"),
              decodeClasses(rs.getString("instance_of")),
              provenance);
      case "EDGE" ->
          new AssertionRecord(
              rs.getString("qid"),
              rs.getString("to_qid"),
              rs.getString("type_code"),
              dateOrNull(rs.getString("valid_from")),
              dateOrNull(rs.getString("valid_to")),
              provenance);
      default -> throw new IllegalStateException("unknown assertion kind: " + rs.getString("kind"));
    };
  }

  private static void bindProvenance(PreparedStatement ps, Provenance p) throws SQLException {
    ps.setString(10, p.sourceId());
    ps.setString(11, p.sourceRef());
    ps.setString(12, p.assertedAt().toString());
    ps.setDouble(13, p.confidence());
  }

  private static String encodeClasses(List<String> classes) {
    // Null rather than "" when a source states none, so the column reads as absent in a SQL
    // client and matches every edge row, which has no classes to state.
    return classes.isEmpty() ? null : String.join(CLASS_SEP, classes);
  }

  private static List<String> decodeClasses(String packed) {
    return packed == null || packed.isBlank() ? List.of() : List.of(packed.split(CLASS_SEP));
  }

  private static String isoOrNull(LocalDate date) {
    return date == null ? null : date.toString();
  }

  private static LocalDate dateOrNull(String iso) {
    return iso == null ? null : LocalDate.parse(iso);
  }

  @Override
  public void close() {
    try {
      conn.close();
    } catch (SQLException e) {
      throw new IllegalStateException("cannot close assertion log", e);
    }
  }
}
