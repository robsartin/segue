package com.robsartin.segue.expand;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.robsartin.segue.domain.AffinityRecord;
import com.robsartin.segue.domain.Candidate;
import com.robsartin.segue.domain.KnownList;
import com.robsartin.segue.domain.NodeAssertion;
import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.domain.NodeRecord;
import com.robsartin.segue.domain.Provenance;
import com.robsartin.segue.expansion.EntityExpansion;
import com.robsartin.segue.ingest.IngestService;
import com.robsartin.segue.port.EntityResolver;
import com.robsartin.segue.port.ExpandContext;
import com.robsartin.segue.port.ExpandResult;
import com.robsartin.segue.port.IdentityMerge;
import com.robsartin.segue.port.SourceAdapter;
import com.robsartin.segue.port.SourceAdapters;
import com.robsartin.segue.sqlite.SqliteAffinityStore;
import com.robsartin.segue.sqlite.SqliteAssertionLog;
import com.robsartin.segue.tinker.TinkerGraphStore;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ADR 51's line, held by a test rather than by review, on the second artefact where it can be —
 * {@code CensusIsSafeToPasteTest} and {@code EvaluationIsSafeToPasteTest} are the first two, and
 * this class is their shape (#284, ADR 66).
 *
 * <p><b>What is safe to paste is the block and every line this tool writes.</b> There is no framing
 * to judge, because every value {@code ExpansionReport} emits is an integer and every label is a
 * literal in that file; and there is nothing to look up, because the assertion is over the shape of
 * the text rather than over what any name means. The fixture carries all three of the things that
 * must not appear — a label, a note, and a {@code Q} id inside that note — and the capture is at
 * TRACE so that sqlite-jdbc's own statement logging is included, which is how the sibling {@code
 * RatingsAreNeverLoggedTest} found the driver logging SQL.
 *
 * <p><b>The carve-out is one logger wide, and it is a real carve-out rather than a weakening.</b>
 * {@code com.robsartin.segue.expansion.EntityExpansion} emits two {@code warn} lines that name an
 * entity — a neighbour it could not fetch, and an edge endpoint {@code IngestService} refused — and
 * the MCP server emits those same two lines, from the same class, on every {@code expand_entity}
 * call. They are not this tool's lines: they are the shared expansion's, they predate this tool,
 * and nothing about running the expansion in a batch changes what they say. So the guard exempts
 * that one logger name by exact match and asserts the property over everything else. <b>Moving
 * those two warnings out to their callers would close the carve-out entirely</b> — each caller
 * would decide whether to name the entity, and this tool would decide not to. That is a change to
 * the shared class with the MCP server's diagnostics on the other end of it, so it is a separate
 * piece of work; ADR 66's consequences name it.
 *
 * <p><b>A leaked RATING has no clause here, for {@code EvaluationIsSafeToPasteTest}'s reason.</b> A
 * bare digit is indistinguishable from a count the block legitimately prints — every row in the
 * report is a number — so an assertion of the form "no line contains 4" would either be vacuous or
 * red on the honest output. What keeps a rating out is not this test but {@link ExpansionTally}'s
 * type-level fence: every component is an {@code int} or a map keyed by a source id or an {@code
 * ExpansionOutcome.Reason}, so there is nowhere in the signature {@code ExpansionReport} reads to
 * put one, which is a stronger guarantee than a body that merely happens not to print one.
 */
class ExpansionIsSafeToPasteTest {

  /** Anything qid-shaped at all, wherever it appears. */
  private static final Pattern A_QID = Pattern.compile("\\bQ\\d+\\b");

  /** The one logger the carve-out covers, matched exactly and never by substring. */
  private static final String THE_SHARED_EXPANSION =
      "com.robsartin.segue.expansion.EntityExpansion";

  private static final String LABEL = "A Label Unlike Anything Real";
  private static final String NOTE = "an invented note that names Q0900901 and nothing else";

  private static final String RATED = "Q0900901";

  /** Rated at the threshold, so it is a promotion, and deliberately given no node. */
  private static final String NEVER_SEEN = "Q0900902";

  /**
   * The seed an adapter's own exception message names. Upstream throws exactly this shape — {@code
   * ReverseClaims} and {@code WikidataEntityResolver} both build "not a QID: " plus the id they
   * were handed, and that id is the promotion itself.
   */
  private static final String THROWN_ABOUT = "Q0900903";

  private static final Instant WHEN = Instant.parse("2026-02-01T08:00:00Z");

  private static boolean carriesAnIdItMayNot(ILoggingEvent event) {
    return !THE_SHARED_EXPANSION.equals(event.getLoggerName())
        && A_QID.matcher(event.getFormattedMessage()).find();
  }

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
  @DisplayName("a dry run reaches the block, and no label, note or id reaches the log with it")
  void shouldEmitCountsAndNothingElseWhenTheDatabaseHoldsALabelANoteAndAnId() {
    Path db = home.resolve("scratch.db");
    Provenance sourced = new Provenance("invented", "invented:1", WHEN, 1.0);
    try (SqliteAssertionLog log = new SqliteAssertionLog(db);
        SqliteAffinityStore affinity = new SqliteAffinityStore(db)) {
      log.append(new NodeAssertion(RATED, NodeKind.GROUP, LABEL, sourced));
      affinity.put(new AffinityRecord(RATED, KnownList.PROMOTION_RATING, NOTE, WHEN));
    }
    captured.list.clear();

    // The dry run is what this drives: it reaches the report and the promotions, and it is the
    // only path through this tool that asks no source anything.
    ExpandCli.main(new String[] {"--db", db.toString(), "--dry-run"});

    assertEverySafe(ExpansionReport.DRY_RUN_HEADER);
  }

  @Test
  @DisplayName("a real run over a promotion the graph never saw reaches the whole block, offline")
  void shouldEmitCountsAndNothingElseWhenTheOnlyPromotionIsRefused() {
    Path db = home.resolve("refused.db");
    try (SqliteAssertionLog log = new SqliteAssertionLog(db);
        SqliteAffinityStore affinity = new SqliteAffinityStore(db)) {
      // No node claim for NEVER_SEEN at all, so EntityExpansion refuses it with UNKNOWN_ENTITY
      // before an adapter is asked anything — which is how a NON-dry run reaches the refusal
      // tally, the "refused, by reason" section and the full block without a network.
      affinity.put(new AffinityRecord(NEVER_SEEN, KnownList.PROMOTION_RATING, NOTE, WHEN));
      assertThat(log.readAll()).as("the log is deliberately empty").isEmpty();
    }
    captured.list.clear();

    ExpandCli.main(new String[] {"--db", db.toString()});

    assertEverySafe(ExpansionReport.HEADER);
    assertThat(lines())
        .as("the run really did reach the refusal tally, or the block above is a run over nothing")
        .anyMatch(line -> line.startsWith("  unknown entity"));
  }

  @Test
  @DisplayName("an adapter throws naming an entity, and no line this tool writes repeats it")
  void shouldNameNoEntityWhenAnAdaptersExceptionMessageCarriesOne() {
    Path db = home.resolve("threw.db");
    Provenance sourced = new Provenance("invented", "invented:2", WHEN, 1.0);
    try (SqliteAssertionLog assertions = new SqliteAssertionLog(db);
        TinkerGraphStore graph = new TinkerGraphStore()) {
      IngestService ingest = new IngestService(assertions, graph, IdentityMerge.NONE);
      ingest.record(new NodeAssertion(THROWN_ABOUT, NodeKind.PERSON, LABEL, sourced));
      EntityExpansion expansion =
          new EntityExpansion(
              new NeverCalledResolver(),
              graph,
              ingest,
              new SourceAdapters(List.of(new ThrowsNamingTheSeed())));
      captured.list.clear();

      ExpansionTally tally =
          new ExpandRun(expansion, graph).run(List.of(THROWN_ABOUT), 10, l -> {});

      assertThat(tally.failed())
          .as("the run really did reach the catch, or every assertion below is vacuous")
          .isEqualTo(1);
      assertThat(lines())
          .as("no line carries a label, not even one an adapter put in an exception message")
          .noneMatch(line -> line.contains(LABEL));
      assertThat(List.copyOf(captured.list))
          .as(
              "no line this tool writes carries anything qid-shaped, not even one an adapter put in"
                  + " an exception message — see the class javadoc")
          .noneMatch(ExpansionIsSafeToPasteTest::carriesAnIdItMayNot);
    }
  }

  @Test
  @DisplayName("the carve-out is one logger wide, matched exactly and never by substring")
  void shouldFireWhenAQidComesFromAnyLoggerButTheSharedExpansion() {
    assertThat(carriesAnIdItMayNot(from(ExpandRun.class.getName(), "[1/3] refused: " + RATED)))
        .as("a line this tool writes, carrying a qid")
        .isTrue();
    assertThat(carriesAnIdItMayNot(from(THE_SHARED_EXPANSION, "expandEntity(" + RATED + ") …")))
        .as("the shared expansion's own diagnostic, which the MCP server emits identically")
        .isFalse();
    assertThat(carriesAnIdItMayNot(from(THE_SHARED_EXPANSION + "Helper", "saw " + RATED)))
        .as("a logger whose name merely contains the exempt one — exact match, not substring")
        .isTrue();
    assertThat(carriesAnIdItMayNot(from("com.example.EntityExpansion", "saw " + RATED)))
        .as("a logger whose simple name is the exempt one in another package")
        .isTrue();
    assertThat(carriesAnIdItMayNot(from(ExpandRun.class.getName(), "[1/3] 4 edge(s)")))
        .as("an ordinary progress line, which is what the guard must not red on")
        .isFalse();
  }

  private static ILoggingEvent from(String logger, String message) {
    LoggingEvent event = new LoggingEvent();
    event.setLoggerName(logger);
    event.setLevel(Level.INFO);
    event.setMessage(message);
    return event;
  }

  private List<String> lines() {
    return List.copyOf(captured.list).stream().map(ILoggingEvent::getFormattedMessage).toList();
  }

  private void assertEverySafe(String header) {
    List<String> everyLine = lines();
    assertThat(everyLine)
        .as("the block was actually printed — without this the assertions below are vacuous")
        .contains(header)
        .anyMatch(line -> line.startsWith("  considered"));
    assertThat(everyLine)
        .as("no line carries a label (ADR 51, ADR 63, ADR 66)")
        .noneMatch(line -> line.contains(LABEL));
    assertThat(everyLine)
        .as("no line carries a note (ADR 33, ADR 51)")
        .noneMatch(line -> line.contains(NOTE));
    assertThat(List.copyOf(captured.list))
        .as(
            "no line this tool writes carries anything qid-shaped, wherever it came from. The one"
                + " exception is a diagnostic from the shared expansion, which the MCP server emits"
                + " identically — see the class javadoc")
        .noneMatch(ExpansionIsSafeToPasteTest::carriesAnIdItMayNot);
  }

  /** Throws the way the two upstream classes do: a message naming the seed, and its label. */
  private static final class ThrowsNamingTheSeed implements SourceAdapter {

    @Override
    public String id() {
      return "throws-naming-the-seed";
    }

    @Override
    public boolean supports(NodeKind kind) {
      return true;
    }

    @Override
    public ExpandResult expand(NodeRecord seed, ExpandContext ctx) {
      throw new IllegalArgumentException("not a QID: " + THROWN_ABOUT + " (" + LABEL + ")");
    }
  }

  /** Answers for nothing — this run throws before any neighbour needs identifying. */
  private static final class NeverCalledResolver implements EntityResolver {

    @Override
    public String id() {
      return "never-called";
    }

    @Override
    public List<Candidate> search(String query, NodeKind kind, int limit) {
      throw new AssertionError("this run must not ask the resolver anything");
    }

    @Override
    public Optional<NodeAssertion> fetch(String qid) {
      throw new AssertionError("this run must not ask the resolver anything");
    }
  }
}
