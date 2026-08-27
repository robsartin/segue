package com.robsartin.segue.ratings;

import static com.robsartin.segue.ratings.InventedRatings.EARLY;
import static com.robsartin.segue.ratings.InventedRatings.QUARTET;
import static com.robsartin.segue.ratings.InventedRatings.QUARTET_LABEL;
import static com.robsartin.segue.ratings.InventedRatings.QUARTET_NOTE;
import static com.robsartin.segue.ratings.InventedRatings.node;
import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.robsartin.segue.domain.AffinityRecord;
import com.robsartin.segue.sqlite.SqliteAffinityStore;
import com.robsartin.segue.sqlite.SqliteAssertionLog;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ADR 33's "never logged" reaching the one tool whose entire subject is affinity.
 *
 * <p>{@code AffinityIsNeverLoggedTest} asks this of the write path and can demand total silence,
 * because {@code note_affinity} has nothing to say. This tool must say something - it is a command
 * a person runs and watches - so the invariant is drawn one line further in: <b>every log line is a
 * count or a path, and no log line anywhere contains a label, a note, or a qid</b>. Since no line
 * names an entity, no line can attribute a rating to one, which is what "a rating is personal data"
 * actually means. The listing itself, which is all of it, goes to the file and only to the file.
 *
 * <p>This drives {@link RatingsCli#main}, not {@link RatingsRun}, deliberately: the run reports
 * through a {@link java.util.function.Consumer} and would pass this test by never reaching a logger
 * at all. The logger is in the CLI, so the CLI is what has to be run. It also puts the real {@code
 * sqlite} stores on the path, which is how the sibling test found sqlite-jdbc logging its SQL at
 * TRACE - the bulk read is a {@code PreparedStatement} with no parameters, and the SQL text it logs
 * must stay free of values.
 *
 * <p>The label and note below are invented and deliberately unlike anything else in the repository,
 * so a match in the captured log is unambiguous.
 */
class RatingsAreNeverLoggedTest {

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
    // TRACE, so the driver's own statement logging is captured too, and so a debug line added
    // later fails this test as loudly as a warning would.
    rootLogger.setLevel(Level.TRACE);
    rootLogger.addAppender(captured);
  }

  @AfterEach
  void tearDown() {
    rootLogger.detachAppender(captured);
    rootLogger.setLevel(originalLevel);
  }

  @Test
  @DisplayName("a full listing reaches the file; not one line of it reaches a log")
  void listsToTheFileAndNeverToTheLog() throws IOException {
    Path db = dir.resolve("scratch.db");
    Path out = dir.resolve("ratings.txt");
    try (SqliteAssertionLog log = new SqliteAssertionLog(db);
        SqliteAffinityStore affinity = new SqliteAffinityStore(db)) {
      log.append(node(QUARTET, QUARTET_LABEL));
      affinity.put(new AffinityRecord(QUARTET, 5, QUARTET_NOTE, EARLY));
    }
    captured.list.clear();

    RatingsCli.main(new String[] {"--db", db.toString(), "--out", out.toString()});

    // The listing is in the file, whole.
    assertThat(Files.readString(out)).contains(QUARTET_LABEL).contains(QUARTET_NOTE);

    List<String> everyLine =
        List.copyOf(captured.list).stream().map(ILoggingEvent::getFormattedMessage).toList();
    assertThat(everyLine)
        .as("no log line from anywhere may carry a rating's note (ADR 33)")
        .noneMatch(line -> line.contains(QUARTET_NOTE));
    assertThat(everyLine)
        .as("no log line from anywhere may carry a label a rating is attached to (ADR 33)")
        .noneMatch(line -> line.contains(QUARTET_LABEL));
    assertThat(everyLine)
        .as("no log line names the entity, so no line can attribute a rating to one (ADR 33)")
        .noneMatch(line -> line.contains(QUARTET));
  }

  @Test
  @DisplayName("the warning is said out loud, because where the file goes now depends on it")
  void warnsThatTheOutputIsPersonalData() throws IOException {
    Path db = dir.resolve("scratch.db");
    try (SqliteAssertionLog log = new SqliteAssertionLog(db);
        SqliteAffinityStore affinity = new SqliteAffinityStore(db)) {
      log.append(node(QUARTET, QUARTET_LABEL));
      affinity.put(new AffinityRecord(QUARTET, 4, null, EARLY));
    }
    captured.list.clear();

    RatingsCli.main(new String[] {"--db", db.toString(), "--out", dir.resolve("r.txt").toString()});

    assertThat(captured.list)
        .filteredOn(event -> event.getLevel() == Level.WARN)
        .extracting(ILoggingEvent::getFormattedMessage)
        .contains(RatingsRun.PERSONAL_DATA_WARNING);
  }
}
