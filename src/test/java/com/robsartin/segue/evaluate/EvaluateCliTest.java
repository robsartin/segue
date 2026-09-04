package com.robsartin.segue.evaluate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.robsartin.segue.domain.AffinityRecord;
import com.robsartin.segue.domain.AssertionRecord;
import com.robsartin.segue.domain.EdgeTypes;
import com.robsartin.segue.domain.NodeAssertion;
import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.recommend.RecommendCli;
import com.robsartin.segue.sqlite.SqliteAffinityStore;
import com.robsartin.segue.sqlite.SqliteAssertionLog;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The parser's refusals, and the whole tool driven once end to end over a {@code @TempDir}
 * database. Every id, label, note and rating is invented (ADR 33, issue #37).
 */
class EvaluateCliTest {

  private static final String INVENTED_HOME = "/home/invented";

  @TempDir private Path dir;

  @Test
  @DisplayName("--db is required, and SEGUE_DB does not satisfy it")
  void shouldRefuseTheRunWhenTheDatabaseFlagIsAbsent() {
    assertThatThrownBy(
            () ->
                EvaluateCli.run(
                    new String[] {"--known", "/nowhere/known.csv"},
                    "/somewhere/else/segue.db",
                    INVENTED_HOME))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("--db");
  }

  @Test
  @DisplayName("a missing --db is refused before a missing file is, so the message names the flag")
  void shouldNameTheFlagRatherThanAPathWhenNeitherWasGiven() {
    assertThatThrownBy(() -> EvaluateCli.run(new String[] {}, null, INVENTED_HOME))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageNotContaining("no segue database at");
  }

  @Test
  @DisplayName("--known is required, because a held-out run needs a list to hold out of")
  void shouldRefuseTheRunWhenTheKnownListIsAbsent() {
    assertThatThrownBy(
            () ->
                EvaluateCli.run(
                    new String[] {"--db", dir.resolve("scratch.db").toString()},
                    null,
                    INVENTED_HOME))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("--known");
  }

  @Test
  @DisplayName("a database that is not there is refused rather than created empty")
  void shouldRefuseTheRunWhenTheDatabaseDoesNotExist() throws IOException {
    Path known = dir.resolve("known.csv");
    Files.writeString(known, InventedEvaluation.KNOWN_ONE + "\n");

    assertThatThrownBy(
            () ->
                EvaluateCli.run(
                    new String[] {
                      "--db", dir.resolve("absent.db").toString(), "--known", known.toString()
                    },
                    null,
                    INVENTED_HOME))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("nothing to evaluate");
    assertThat(dir.resolve("absent.db")).doesNotExist();
  }

  @Test
  @DisplayName("--top defaults to the recommender's own, and a number below one is refused")
  void shouldDefaultToTheRecommendersTopWhenNoneIsGiven() {
    Path db = dir.resolve("scratch.db");

    assertThat(
            EvaluateCli.parse(
                    new String[] {"--db", db.toString(), "--known", "/nowhere/known.csv"},
                    null,
                    INVENTED_HOME)
                .top())
        .isEqualTo(RecommendCli.DEFAULT_TOP);
    assertThatThrownBy(
            () ->
                EvaluateCli.parse(
                    new String[] {
                      "--db", db.toString(), "--known", "/nowhere/known.csv", "--top", "0"
                    },
                    null,
                    INVENTED_HOME))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("--top");
  }

  @Test
  @DisplayName("the whole tool runs against a real database and prints the whole table")
  void shouldPrintTheWholeTableWhenTheToolIsRunEndToEnd() throws IOException {
    Path db = graphOnDisk();
    Path known = dir.resolve("known.csv");
    Files.writeString(
        known, InventedEvaluation.KNOWN_ONE + "\n" + InventedEvaluation.KNOWN_TWO + "\n");

    // No assertion on the output here beyond "it did not throw": the report's content is
    // EvaluationReportTest's, and what nothing else covers is that main() wires a real log, a real
    // affinity table and a real replay together.
    EvaluateCli.main(new String[] {"--db", db.toString(), "--known", known.toString()});
  }

  /** The same neighbourhood {@code InventedEvaluation} builds, written to a log instead. */
  private Path graphOnDisk() {
    Path db = dir.resolve("scratch.db");
    Instant when = Instant.parse("2026-01-01T00:00:00Z");
    try (SqliteAssertionLog log = new SqliteAssertionLog(db);
        SqliteAffinityStore affinity = new SqliteAffinityStore(db)) {
      for (String qid :
          java.util.List.of(
              InventedEvaluation.KNOWN_ONE,
              InventedEvaluation.KNOWN_TWO,
              InventedEvaluation.VIA_ONE,
              InventedEvaluation.STRANGER,
              InventedEvaluation.HIDDEN)) {
        log.append(
            new NodeAssertion(
                qid, NodeKind.GROUP, "an invented act", InventedEvaluation.sourced()));
      }
      edge(log, InventedEvaluation.KNOWN_ONE, InventedEvaluation.VIA_ONE);
      edge(log, InventedEvaluation.KNOWN_TWO, InventedEvaluation.VIA_ONE);
      edge(log, InventedEvaluation.STRANGER, InventedEvaluation.VIA_ONE);
      edge(log, InventedEvaluation.HIDDEN, InventedEvaluation.VIA_ONE);
      affinity.put(new AffinityRecord(InventedEvaluation.HIDDEN, 5, "an invented note", when));
    }
    return db;
  }

  private static void edge(SqliteAssertionLog log, String from, String to) {
    log.append(
        new AssertionRecord(
            from, to, EdgeTypes.INFLUENCED_BY.code(), null, null, InventedEvaluation.sourced()));
  }
}
