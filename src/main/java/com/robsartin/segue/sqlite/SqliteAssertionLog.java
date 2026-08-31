package com.robsartin.segue.sqlite;

import com.robsartin.segue.domain.AssertionRecord;
import com.robsartin.segue.domain.LocalEntity;
import com.robsartin.segue.domain.LoggedAssertion;
import com.robsartin.segue.domain.NodeAssertion;
import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.domain.OwnerEdge;
import com.robsartin.segue.domain.Provenance;
import com.robsartin.segue.domain.Retraction;
import com.robsartin.segue.domain.SameAs;
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
 * the validity dates, and a retraction (ADR 44) fills {@code qid} and {@code reason} alone. The
 * owner's three first-person claims (#92) reuse those same columns rather than adding any: {@code
 * LOCAL} fills a node claim's, while {@code OWNER_EDGE} and {@code SAME_AS} put their two ends in
 * {@code qid} and {@code to_qid}. The two sourced claims carry their provenance; the retraction has
 * none, and {@code asserted_at} carries the one time dimension it does have. Sequence order is the
 * autoincrement primary key, which is exactly the replay order {@code GraphProjector} needs, and it
 * is also what orders a retraction against the claims it reaches. The instant is stored as an
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
        confidence  REAL NOT NULL,
        reason      TEXT
      )
      """;

  /**
   * The one migration this file has needed (ADR 44).
   *
   * <p>{@code CREATE TABLE IF NOT EXISTS} is a no-op against an existing table, so a database
   * written before retractions existed would silently keep a schema with no {@code reason} column
   * and fail on the first retraction. ADR 42 shipped a schema change with no migration and said in
   * as many words that the next one gets a real path, because the world facts are regenerable and
   * the ratings beside them are not.
   *
   * <p>It is one {@code ALTER TABLE ADD COLUMN}, which SQLite performs by rewriting the schema
   * rather than the rows, so it costs the same on an empty file and on a hundred thousand of them.
   * Guarded by reading the table's own columns rather than by a version table: the question "does
   * this column exist" has an exact answer here, where a version number is a second source of truth
   * that a hand-edited file can contradict.
   */
  private static final String ADD_REASON = "ALTER TABLE assertion ADD COLUMN reason TEXT";

  private static final String INSERT =
      "INSERT INTO assertion (kind, qid, to_qid, type_code, node_kind, instance_of, label,"
          + " valid_from, valid_to, source_id, source_ref, asserted_at, confidence, reason) VALUES"
          + " (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

  private static final String SELECT_ALL =
      "SELECT kind, qid, to_qid, type_code, node_kind, instance_of, label, valid_from, valid_to,"
          + " source_id, source_ref, asserted_at, confidence, reason FROM assertion ORDER BY seq";

  /**
   * What goes in {@code source_id} and {@code confidence} for a retraction row.
   *
   * <p>ADR 44 gives a retraction no {@link Provenance}: it is the owner's own act, not a sourced
   * claim, so there is no source and belief is not the question. Those two columns are {@code NOT
   * NULL} and predate this row type, and removing a constraint in SQLite means rebuilding the table
   * - a real rewrite of the whole log, to relax a constraint on rows that simply have nothing to
   * put there. So they are filled with fixed values and {@link #readRow} never reads them back for
   * a {@code RETRACT} row. The literal is named rather than "operator" or "" so that anyone reading
   * the table in a SQL client sees a discriminator and not a source they might go looking for.
   */
  private static final String RETRACTION_SOURCE = "(retraction)";

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

  private static final double RETRACTION_CONFIDENCE = 1.0;

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
        if (!hasReasonColumn()) {
          st.execute(ADD_REASON);
        }
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
        case Retraction r -> {
          ps.setString(1, "RETRACT");
          ps.setString(2, r.qid());
          ps.setString(3, null);
          ps.setString(4, null);
          ps.setString(5, null);
          ps.setString(6, null);
          ps.setString(7, null);
          ps.setString(8, null);
          ps.setString(9, null);
          // No provenance (ADR 44). asserted_at carries the one time dimension a retraction
          // has; the other two columns are the NOT NULL padding described above.
          ps.setString(10, RETRACTION_SOURCE);
          ps.setString(11, null);
          ps.setString(12, r.retractedAt().toString());
          ps.setDouble(13, RETRACTION_CONFIDENCE);
          ps.setString(14, r.reason());
        }
        // The owner's three first-person claims (#92). Each reuses the columns whose meaning
        // already matches - a minted entity is a node's shape, an owner edge and a merge are a
        // pair of qids - rather than adding columns, so no migration is needed and a row stays
        // readable in a SQL client. They are given the reserved owner provenance rather than
        // RETRACTION_SOURCE's padding: source_id and confidence are NOT NULL, and "owner" is the
        // honest answer to who said this, not a placeholder somebody might go looking for.
        case LocalEntity local -> {
          ps.setString(1, "LOCAL");
          ps.setString(2, local.qid());
          ps.setString(3, null);
          ps.setString(4, null);
          ps.setString(5, local.kind().name());
          // No instance_of: the owner stated a kind directly and no source stated any classes,
          // which is the same emptiness LocalEntity.toNode() carries into the graph.
          ps.setString(6, null);
          ps.setString(7, local.label());
          ps.setString(8, null);
          ps.setString(9, null);
          bindProvenance(ps, Provenance.owner(local.mintedAt()));
        }
        case OwnerEdge edge -> {
          ps.setString(1, "OWNER_EDGE");
          ps.setString(2, edge.fromQid());
          ps.setString(3, edge.toQid());
          ps.setString(4, edge.typeCode());
          ps.setString(5, null);
          ps.setString(6, null);
          ps.setString(7, null);
          // No validity dates, matching OwnerEdge.toAssertion(): the owner asserted that the
          // relationship holds, not when it began or ended.
          ps.setString(8, null);
          ps.setString(9, null);
          bindProvenance(ps, Provenance.owner(edge.assertedAt()));
        }
        case SameAs sameAs -> {
          ps.setString(1, "SAME_AS");
          // The local side goes in qid and the canonical side in to_qid, and the order is not
          // arbitrary: SameAs refuses to be built the other way round (its local side must be
          // Q00..., its canonical side allocatable), so readRow reconstructing it in this order
          // is checked by the record's own constructor on the way back in.
          ps.setString(2, sameAs.localQid());
          ps.setString(3, sameAs.canonicalQid());
          ps.setString(4, null);
          ps.setString(5, null);
          ps.setString(6, null);
          ps.setString(7, null);
          ps.setString(8, null);
          ps.setString(9, null);
          bindProvenance(ps, Provenance.owner(sameAs.assertedAt()));
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

  /**
   * One row back into the claim that wrote it.
   *
   * <p>Provenance is built only for the two sourced kinds, by {@link #provenanceOf}. The
   * first-person rows - a retraction (ADR 44) and the owner's three (#92) - do not reconstruct one
   * from the columns: a retraction's are {@link #RETRACTION_SOURCE} padding, and an owner claim's
   * are derived from what the record already carries, so reading them back would be a second source
   * of truth for a value {@link Provenance#owner} already defines.
   */
  private static LoggedAssertion readRow(ResultSet rs) throws SQLException {
    return switch (rs.getString("kind")) {
      case "RETRACT" ->
          new Retraction(
              rs.getString("qid"),
              rs.getString("reason"),
              Instant.parse(rs.getString("asserted_at")));
      case "LOCAL" ->
          new LocalEntity(
              rs.getString("qid"),
              NodeKind.valueOf(rs.getString("node_kind")),
              rs.getString("label"),
              Instant.parse(rs.getString("asserted_at")));
      case "OWNER_EDGE" ->
          new OwnerEdge(
              rs.getString("qid"),
              rs.getString("to_qid"),
              rs.getString("type_code"),
              Instant.parse(rs.getString("asserted_at")));
      case "SAME_AS" ->
          new SameAs(
              rs.getString("qid"),
              rs.getString("to_qid"),
              Instant.parse(rs.getString("asserted_at")));
      case "NODE" ->
          new NodeAssertion(
              rs.getString("qid"),
              NodeKind.valueOf(rs.getString("node_kind")),
              rs.getString("label"),
              decodeClasses(rs.getString("instance_of")),
              provenanceOf(rs));
      case "EDGE" ->
          new AssertionRecord(
              rs.getString("qid"),
              rs.getString("to_qid"),
              rs.getString("type_code"),
              dateOrNull(rs.getString("valid_from")),
              dateOrNull(rs.getString("valid_to")),
              provenanceOf(rs));
      default -> throw new IllegalStateException("unknown assertion kind: " + rs.getString("kind"));
    };
  }

  /** The provenance columns of a sourced claim, read back. */
  private static Provenance provenanceOf(ResultSet rs) throws SQLException {
    return new Provenance(
        rs.getString("source_id"),
        rs.getString("source_ref"),
        Instant.parse(rs.getString("asserted_at")),
        rs.getDouble("confidence"));
  }

  /** Binds the provenance columns of a sourced claim, and the {@code reason} it does not have. */
  private static void bindProvenance(PreparedStatement ps, Provenance p) throws SQLException {
    ps.setString(10, p.sourceId());
    ps.setString(11, p.sourceRef());
    ps.setString(12, p.assertedAt().toString());
    ps.setDouble(13, p.confidence());
    ps.setString(14, null);
  }

  /** Whether this file already has the column ADR 44 added. */
  private boolean hasReasonColumn() throws SQLException {
    try (Statement st = conn.createStatement();
        ResultSet rs = st.executeQuery("PRAGMA table_info(assertion)")) {
      while (rs.next()) {
        if ("reason".equals(rs.getString("name"))) {
          return true;
        }
      }
    }
    return false;
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
