package com.robsartin.segue.census;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.robsartin.segue.domain.AffinityRecord;
import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.sqlite.SqliteAffinityStore;
import com.robsartin.segue.sqlite.SqliteAssertionLog;
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
 * ADR 51's line, held by a test rather than by review, for the one artefact where it can be.
 *
 * <p>ADR 51 says plainly that no test can hold its rule, and gives two reasons: the framing decides
 * whether a QID is a citation or a disclosure, and a test would have to read the private store to
 * know which entities are the owner's. <b>Neither reason reaches this output.</b> There is no
 * framing to judge, because every value the census emits is an integer and every label is a literal
 * in {@code CensusReport}; and there is nothing to look up, because the assertion is over the shape
 * of the text rather than over what any name means.
 *
 * <p>The fixture carries all three of the things that must not appear — a label, a note, and a
 * {@code Q} id inside that note — and the capture is at TRACE so that sqlite-jdbc's own statement
 * logging is included, which is how the sibling {@code RatingsAreNeverLoggedTest} found the driver
 * logging SQL.
 */
class CensusIsSafeToPasteTest {

  /** Anything qid-shaped at all, wherever it appears. */
  private static final Pattern A_QID = Pattern.compile("\\bQ\\d+\\b");

  private static final String LABEL = "A Label Unlike Anything Real";
  private static final String NOTE = "an invented note that names Q0900901 and nothing else";

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
    rootLogger.setLevel(Level.TRACE);
    rootLogger.addAppender(captured);
  }

  @AfterEach
  void tearDown() {
    rootLogger.detachAppender(captured);
    rootLogger.setLevel(originalLevel);
  }

  @Test
  @DisplayName("the whole census reaches the log, and no label, note or id reaches it with them")
  void shouldEmitCountsAndNothingElseWhenTheGraphHoldsALabelANoteAndAnId() {
    Path db = home.resolve("scratch.db");
    try (SqliteAssertionLog log = new SqliteAssertionLog(db);
        SqliteAffinityStore affinity = new SqliteAffinityStore(db)) {
      log.append(InventedCensus.node("Q0900901", NodeKind.WORK, LABEL));
      affinity.put(new AffinityRecord("Q0900901", 5, NOTE, Instant.parse("2026-02-01T08:00:00Z")));
    }
    captured.list.clear();

    CensusCli.main(new String[] {"--db", db.toString()});

    List<String> everyLine =
        List.copyOf(captured.list).stream().map(ILoggingEvent::getFormattedMessage).toList();

    assertThat(everyLine)
        .as("the census was actually printed — without this the assertions below are vacuous")
        .contains(CensusReport.HEADER)
        .anyMatch(line -> line.startsWith("  ratings"));
    assertThat(everyLine)
        .as("no line carries a label (ADR 51, ADR 63)")
        .noneMatch(line -> line.contains(LABEL));
    assertThat(everyLine)
        .as("no line carries a note (ADR 33, ADR 51)")
        .noneMatch(line -> line.contains(NOTE));
    assertThat(everyLine)
        .as(
            "no line carries anything qid-shaped, wherever it came from — a label, a note, a source"
                + " id or an edge type code that turned out to look like an entity")
        .noneMatch(line -> A_QID.matcher(line).find());
  }
}
