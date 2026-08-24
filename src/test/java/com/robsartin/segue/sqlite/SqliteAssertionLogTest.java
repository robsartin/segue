package com.robsartin.segue.sqlite;

import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.segue.domain.AssertionRecord;
import com.robsartin.segue.domain.LoggedAssertion;
import com.robsartin.segue.domain.NodeAssertion;
import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.domain.Provenance;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
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
}
