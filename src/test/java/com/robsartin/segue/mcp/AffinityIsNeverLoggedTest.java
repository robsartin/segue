package com.robsartin.segue.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.robsartin.segue.domain.Candidate;
import com.robsartin.segue.domain.NodeAssertion;
import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.domain.Provenance;
import com.robsartin.segue.ingest.IngestService;
import com.robsartin.segue.port.AffinityStore;
import com.robsartin.segue.port.AssertionLog;
import com.robsartin.segue.port.EntityResolver;
import com.robsartin.segue.port.GraphStore;
import com.robsartin.segue.port.SourceAdapters;
import com.robsartin.segue.sqlite.SqliteAffinityStore;
import com.robsartin.segue.sqlite.SqliteAssertionLog;
import com.robsartin.segue.tinker.TinkerGraphStore;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * ADR 33's privacy invariant, as a test: no rating and no note ever reaches a log line.
 *
 * <p>This is the invariant most likely to be broken by accident rather than by decision. ADR 30
 * puts a structured logger in every service class and this project uses it freely — {@link
 * SegueService} logs a warning on every other shortfall it models — so the reflex that makes the
 * rest of that class good makes this one method wrong. Review catches that on the day the method is
 * written, not on the day someone adds a debug line six months later; this test catches both.
 *
 * <p><b>Why this attaches its own appender rather than asserting on where logs go.</b> CLAUDE.md
 * records that a plain JUnit test asserting on log OUTPUT is validating Logback's factory default
 * rather than this project's configuration — that is {@code LoggingTargetsStderrTest}'s job, and it
 * needs a Spring context to do it honestly. This test asks a different question: given whatever
 * appenders happen to exist, does this code path emit an event carrying personal data at all? A
 * {@link ListAppender} attached to the root logger answers that without caring where the real
 * appenders point, which is why it correctly needs no context.
 *
 * <p><b>This project's loggers must be silent; everyone's must be free of affinity values.</b>
 * Silence is stricter than ADR 33's literal words, deliberately: that the user rated this entity is
 * itself the personal fact, and a line reading "noted affinity for Q192668" discloses it without
 * quoting a single value. The wider assertion exists because the first run of this test found
 * sqlite-jdbc logging the upsert at TRACE — see {@link #assertNothingWasLogged()}.
 *
 * <p>Every rating and note below is invented, and the note is deliberately a phrase that appears
 * nowhere else in the codebase, so a match in the captured log is unambiguous.
 */
class AffinityIsNeverLoggedTest {

  private static final Provenance WIKIDATA =
      new Provenance("wikidata", "S-1", Instant.parse("2026-08-24T09:00:00Z"), 1.0);

  /** Invented, and deliberately unlike anything else in the repository. */
  private static final String NOTE = "purple metronome in a rented hallway";

  private AssertionLog log;
  private GraphStore graph;
  private AffinityStore affinity;
  private SegueService service;
  private Logger rootLogger;
  private Level originalLevel;
  private ListAppender<ILoggingEvent> captured;

  @BeforeEach
  void setUp() {
    log = SqliteAssertionLog.inMemory();
    graph = new TinkerGraphStore();
    affinity = SqliteAffinityStore.inMemory();
    service =
        new SegueService(
            new NoOpEntityResolver(),
            graph,
            new IngestService(log, graph),
            new SourceAdapters(List.of()),
            affinity,
            Clock.systemUTC());
    graph.upsertNode(
        new NodeAssertion("Q900001", NodeKind.WORK, "A Placeholder Work", WIKIDATA).toNode());

    captured = new ListAppender<>();
    captured.start();
    rootLogger = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    originalLevel = rootLogger.getLevel();
    // TRACE, so a debug line added later fails this test as loudly as a warning would.
    rootLogger.setLevel(Level.TRACE);
    rootLogger.addAppender(captured);
  }

  @AfterEach
  void tearDown() {
    rootLogger.detachAppender(captured);
    rootLogger.setLevel(originalLevel);
    affinity.close();
    graph.close();
    log.close();
  }

  @Test
  @DisplayName("a successful note_affinity emits no log event of any kind")
  void successfulRatingIsNotLogged() {
    service.noteAffinity("Q900001", 5, NOTE);

    assertNothingWasLogged();
  }

  @Test
  @DisplayName("every refusal path is silent too — an error string is the likelier leak")
  void refusalsAreNotLogged() {
    // The three refusals noteAffinity models: not a QID, not in the graph, off the scale.
    service.noteAffinity("not-a-qid", 5, NOTE);
    service.noteAffinity("Q900404", 5, NOTE);
    service.noteAffinity("Q900001", 9, NOTE);

    assertNothingWasLogged();
  }

  @Test
  @DisplayName("reading affinity back through get_entity is silent about it as well")
  void readingAffinityBackIsNotLogged() {
    service.noteAffinity("Q900001", 4, NOTE);
    captured.list.clear();

    service.getEntity("Q900001");

    assertNothingWasLogged();
  }

  /**
   * Two assertions, because the first run of this test found a third party logging on this path and
   * the difference between them is the whole lesson.
   *
   * <p>sqlite-jdbc logs every statement it executes through SLF4J at TRACE — including the affinity
   * upsert. It logs the SQL <em>text</em> and never the bound parameters, so the rating and the
   * note do not appear, and they cannot start appearing as long as this project's SQL keeps using
   * {@code ?} placeholders rather than concatenating values into the statement. Building that SQL
   * by string concatenation would put a rating into a driver log line without anyone in this
   * repository writing a logging call at all.
   *
   * <p>So: this project's own loggers must be silent, and <em>every</em> logger, this project's or
   * anyone's, must be free of affinity values.
   */
  private void assertNothingWasLogged() {
    List<ILoggingEvent> events = List.copyOf(captured.list);
    List<String> ourLines =
        events.stream()
            .filter(event -> event.getLoggerName().startsWith("com.robsartin.segue"))
            .map(ILoggingEvent::getFormattedMessage)
            .toList();
    List<String> everyLine = events.stream().map(ILoggingEvent::getFormattedMessage).toList();

    assertThat(ourLines)
        .as("the taste layer's own code paths must emit no log event at all (ADR 33)")
        .isEmpty();
    assertThat(everyLine)
        .as("no log line from anywhere may carry an affinity note (ADR 33)")
        .noneMatch(line -> line.contains(NOTE));
    assertThat(everyLine)
        .as("no log line from anywhere may carry a rating, accepted or rejected (ADR 33)")
        .noneMatch(line -> line.matches(".*\\b[1-9]\\b.*"));
  }

  private static final class NoOpEntityResolver implements EntityResolver {
    @Override
    public String id() {
      return "noop";
    }

    @Override
    public List<Candidate> search(String query, NodeKind kind, int limit) {
      return List.of();
    }

    @Override
    public Optional<NodeAssertion> fetch(String qid) {
      return Optional.empty();
    }
  }
}
