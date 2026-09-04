package com.robsartin.segue.evaluate;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.robsartin.segue.domain.AffinityRecord;
import com.robsartin.segue.domain.AssertionRecord;
import com.robsartin.segue.domain.EdgeTypes;
import com.robsartin.segue.domain.NodeAssertion;
import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.sqlite.SqliteAffinityStore;
import com.robsartin.segue.sqlite.SqliteAssertionLog;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ADR 51's line, held by a test rather than by review, for the second artefact where it can be.
 *
 * <p>The sibling of {@code CensusIsSafeToPasteTest}, and it exists for the same reason with a
 * different subject and one more thing to hide. ADR 51 says no test can hold its rule and gives two
 * reasons — the framing decides whether a QID is a citation or a disclosure, and a test would have
 * to read the private store to know which entities are the owner's. Neither reaches this output:
 * there is no framing to judge, because every value is an integer, a fixed decimal or a literal in
 * {@code EvaluationReport}; and there is nothing to look up, because the assertion is over the
 * shape of the text.
 *
 * <p>The fixture carries a label, a note, a {@code Q} id inside that note and a <b>rating</b> — the
 * fourth is this tool's own hazard, since a harness over the taste layer is the one tool with a
 * reason to print a score. The capture is at TRACE so sqlite-jdbc's own statement logging is
 * included, which is how {@code RatingsAreNeverLoggedTest} found the driver logging SQL.
 *
 * <p><b>The rating has no clause of its own, and that is an honest limit rather than an
 * oversight.</b> A leaked rating would show up as a bare digit — {@code "5"} on its own — and
 * nothing distinguishes that from a floor, a hit count or a pool size the legitimate table already
 * prints on every row; a pattern narrow enough to catch a leaked rating and broad enough to survive
 * the report's own numbers does not exist. Planting {@code lines.accept("rating 5")} confirmed it:
 * none of the three assertions below fired. What actually keeps a rating out of the log is upstream
 * of this test — {@link EvaluationReport} takes only a {@link Reading} built from aggregates, never
 * a bare score, and {@link EvaluateRun} never logs a value read straight off an affinity record —
 * and this guard cannot substitute for that. It still plants the rating in the fixture, because the
 * label and the note it also carries must reach the log through the exact same read path a rating
 * would.
 *
 * <p>It is a guard rather than a behaviour, so its evidence is a planted leak seen to fire.
 */
class EvaluationIsSafeToPasteTest {

  /** Anything qid-shaped at all, wherever it appears. */
  private static final Pattern A_QID = Pattern.compile("\\bQ\\d+\\b");

  private static final String LABEL = "A Label Unlike Anything Real";
  private static final String NOTE = "an invented note that names Q0900901 and nothing else";

  @TempDir private Path dir;

  private Logger rootLogger;
  private Level originalLevel;
  private ListAppender<ILoggingEvent> captured;

  @BeforeEach
  void setUp() {
    captured = new ListAppender<>();
    captured.start();
    rootLogger = (Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    originalLevel = rootLogger.getLevel();
    rootLogger.setLevel(Level.TRACE);
    rootLogger.addAppender(captured);
  }

  @AfterEach
  void tearDown() {
    rootLogger.detachAppender(captured);
    rootLogger.setLevel(originalLevel);
  }

  @Test
  @DisplayName(
      "the whole table reaches the log, and no label, note or id reaches it with them — the"
          + " rating has no clause of its own; see the class javadoc for why")
  void shouldEmitCountsAndNothingElseWhenTheGraphHoldsALabelANoteAnIdAndARating()
      throws IOException {
    Path db = dir.resolve("scratch.db");
    Path known = dir.resolve("known.csv");
    Files.writeString(known, InventedEvaluation.KNOWN_ONE + "\n");
    try (SqliteAssertionLog log = new SqliteAssertionLog(db);
        SqliteAffinityStore affinity = new SqliteAffinityStore(db)) {
      log.append(
          new NodeAssertion(
              InventedEvaluation.KNOWN_ONE, NodeKind.GROUP, LABEL, InventedEvaluation.sourced()));
      log.append(
          new NodeAssertion(
              InventedEvaluation.VIA_ONE, NodeKind.GROUP, LABEL, InventedEvaluation.sourced()));
      log.append(
          new NodeAssertion(
              InventedEvaluation.HIDDEN, NodeKind.GROUP, LABEL, InventedEvaluation.sourced()));
      log.append(
          new AssertionRecord(
              InventedEvaluation.KNOWN_ONE,
              InventedEvaluation.VIA_ONE,
              EdgeTypes.INFLUENCED_BY.code(),
              null,
              null,
              InventedEvaluation.sourced()));
      log.append(
          new AssertionRecord(
              InventedEvaluation.HIDDEN,
              InventedEvaluation.VIA_ONE,
              EdgeTypes.INFLUENCED_BY.code(),
              null,
              null,
              InventedEvaluation.sourced()));
      affinity.put(
          new AffinityRecord(
              InventedEvaluation.HIDDEN, 5, NOTE, Instant.parse("2026-02-01T08:00:00Z")));
    }
    captured.list.clear();

    EvaluateCli.main(new String[] {"--db", db.toString(), "--known", known.toString()});

    List<String> everyLine =
        List.copyOf(captured.list).stream().map(ILoggingEvent::getFormattedMessage).toList();

    assertThat(everyLine)
        .as("the table was actually printed — without this the assertions below are vacuous")
        .contains(EvaluationReport.HEADER)
        .anyMatch(line -> line.startsWith("raw"));
    assertThat(everyLine)
        .as("no line carries a label (ADR 51, ADR 63, ADR 65)")
        .noneMatch(line -> line.contains(LABEL));
    assertThat(everyLine)
        .as("no line carries a note (ADR 33, ADR 51)")
        .noneMatch(line -> line.contains(NOTE));
    assertThat(everyLine)
        .as(
            "no line carries anything qid-shaped, wherever it came from — a label, a note, or an"
                + " edge type code that turned out to look like an entity")
        .noneMatch(line -> A_QID.matcher(line).find());
  }
}
