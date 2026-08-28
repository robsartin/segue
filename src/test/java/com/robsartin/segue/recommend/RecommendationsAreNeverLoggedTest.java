package com.robsartin.segue.recommend;

import static com.robsartin.segue.recommend.InventedWorld.ANCESTOR;
import static com.robsartin.segue.recommend.InventedWorld.KNOWN_ONE;
import static com.robsartin.segue.recommend.InventedWorld.KNOWN_TWO;
import static com.robsartin.segue.recommend.InventedWorld.SHARED_ARTIST;
import static com.robsartin.segue.recommend.InventedWorld.sourced;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.robsartin.segue.domain.AssertionRecord;
import com.robsartin.segue.domain.EdgeTypes;
import com.robsartin.segue.domain.NodeAssertion;
import com.robsartin.segue.domain.NodeKind;
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
 * The whole tool, driven for real — and the personal data staying in the file (ADR 45).
 *
 * <p>The sibling of {@code RatingsAreNeverLoggedTest}, and it exists for the same reason with a
 * different subject. A recommendation list is derived from the known-list, which is exactly the
 * personal data ADR 33 governs and ADR 40 keeps out of this public repository, so the invariant is
 * drawn where that one draws it: <b>every log line is a count or a path, and no log line anywhere
 * carries a label or a qid</b>. Since no line names an entity, no line can say what anybody listens
 * to.
 *
 * <p>It drives {@link RecommendCli#main} rather than {@link RecommendRun}, deliberately: the run
 * reports through a {@code Consumer} and would pass by never reaching a logger at all. It also puts
 * the real {@code sqlite} log and the real replay on the path, which is how the sibling test found
 * sqlite-jdbc logging its SQL at TRACE.
 *
 * <p>Every name, QID and edge below is invented.
 */
class RecommendationsAreNeverLoggedTest {

  private static final String KNOWN_LABEL = "the umber ferryman quartet";
  private static final String CANDIDATE_LABEL = "the sudden barrow-wights";

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

  /** Two things you know, both citing one artist, who cites the candidate. */
  private Path graphOnDisk() {
    Path db = dir.resolve("scratch.db");
    try (SqliteAssertionLog log = new SqliteAssertionLog(db)) {
      log.append(new NodeAssertion(KNOWN_ONE, NodeKind.GROUP, KNOWN_LABEL, sourced()));
      log.append(new NodeAssertion(KNOWN_TWO, NodeKind.GROUP, "another you know", sourced()));
      log.append(new NodeAssertion(SHARED_ARTIST, NodeKind.PERSON, "the cited artist", sourced()));
      log.append(new NodeAssertion(ANCESTOR, NodeKind.GROUP, CANDIDATE_LABEL, sourced()));
      log.append(edge(KNOWN_ONE, SHARED_ARTIST, EdgeTypes.INFLUENCED_BY.code()));
      log.append(edge(KNOWN_TWO, SHARED_ARTIST, EdgeTypes.INFLUENCED_BY.code()));
      log.append(edge(SHARED_ARTIST, ANCESTOR, EdgeTypes.INFLUENCED_BY.code()));
      // A second edge, so the candidate clears the lowest floor the command line will accept.
      log.append(new NodeAssertion("Q900901", NodeKind.WORK, "an invented record", sourced()));
      log.append(edge(ANCESTOR, "Q900901", EdgeTypes.PERFORMED.code()));
    }
    return db;
  }

  private static AssertionRecord edge(String from, String to, String type) {
    return new AssertionRecord(from, to, type, null, null, sourced());
  }

  private Path knownList() throws IOException {
    Path list = dir.resolve("known.csv");
    Files.writeString(list, KNOWN_ONE + "\n" + KNOWN_TWO + "\n");
    return list;
  }

  @Test
  @DisplayName("the recommendations reach the file; not one entity reaches a log")
  void recommendsToTheFileAndNeverToTheLog() throws IOException {
    Path db = graphOnDisk();
    Path out = dir.resolve("recommendations.txt");
    captured.list.clear();

    RecommendCli.main(
        new String[] {
          "--db", db.toString(),
          "--known", knownList().toString(),
          "--out", out.toString(),
          "--min-degree", "2"
        });

    assertThat(Files.readString(out)).contains(CANDIDATE_LABEL).contains(KNOWN_LABEL);

    List<String> everyLine =
        List.copyOf(captured.list).stream().map(ILoggingEvent::getFormattedMessage).toList();
    assertThat(everyLine)
        .as("no log line may carry the name of something you are being pointed at (ADR 33)")
        .noneMatch(line -> line.contains(CANDIDATE_LABEL));
    assertThat(everyLine)
        .as("no log line may carry the name of something on your list (ADR 33)")
        .noneMatch(line -> line.contains(KNOWN_LABEL));
    assertThat(everyLine)
        .as("no log line names an entity, so no line can say what anybody listens to")
        .noneMatch(line -> line.contains(ANCESTOR) || line.contains(KNOWN_ONE));
  }

  @Test
  @DisplayName("the warning is said out loud, because where the file goes now depends on it")
  void warnsThatTheOutputIsPersonalData() throws IOException {
    Path db = graphOnDisk();
    captured.list.clear();

    RecommendCli.main(
        new String[] {
          "--db", db.toString(),
          "--known", knownList().toString(),
          "--out", dir.resolve("r.txt").toString(),
          "--min-degree", "2"
        });

    assertThat(captured.list)
        .filteredOn(event -> event.getLevel() == Level.WARN)
        .extracting(ILoggingEvent::getFormattedMessage)
        .contains(RecommendRun.PERSONAL_DATA_WARNING);
  }

  @Test
  @DisplayName("a database that is not there is refused rather than created and read as empty")
  void anAbsentDatabaseIsRefused() throws IOException {
    Path known = knownList();

    assertThatThrownBy(
            () ->
                RecommendCli.main(
                    new String[] {
                      "--db", dir.resolve("nothing.db").toString(),
                      "--known", known.toString(),
                      "--out", dir.resolve("r.txt").toString()
                    }))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("nothing to recommend from");
  }
}
