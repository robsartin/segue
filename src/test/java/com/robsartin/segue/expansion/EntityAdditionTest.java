package com.robsartin.segue.expansion;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.ingest.IngestService;
import com.robsartin.segue.port.AssertionLog;
import com.robsartin.segue.port.GraphStore;
import com.robsartin.segue.port.IdentityMerge;
import com.robsartin.segue.sqlite.SqliteAssertionLog;
import com.robsartin.segue.tinker.TinkerGraphStore;
import com.robsartin.segue.wikidata.StubWikidataServer;
import com.robsartin.segue.wikidata.WikidataClient;
import com.robsartin.segue.wikidata.WikidataEntityResolver;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The add rule, against an in-process stand-in for the Wikidata API — {@code SeedResolverTest}'s
 * method (#328). Every id here carries ADR 58's leading zero, or ADR 59's two; nothing here comes
 * from anybody's graph (ADR 33, issue #37), and no test in this class reaches a network.
 */
class EntityAdditionTest {

  /** Anything qid-shaped at all, wherever it appears — the standing dev-tool rule. */
  private static final Pattern A_QID = Pattern.compile("\\bQ\\d+\\b");

  /** The stub answers for this one. */
  private static final String ANSWERED_FOR = "Q0901501";

  /** Wikidata has no entity at this one. */
  private static final String NO_SUCH_ENTITY = "Q0901502";

  /** The fetch for this one cannot be answered at all. */
  private static final String UNREACHABLE = "Q0901503";

  /** Minted by the owner, so no source has it and none ever will (ADR 59). */
  private static final String MINTED = "Q00901501";

  private static final Clock FIXED =
      Clock.fixed(Instant.parse("2026-09-15T09:00:00Z"), ZoneOffset.UTC);

  @TempDir private Path dir;

  private AssertionLog log;
  private GraphStore graph;
  private IngestService ingest;

  @BeforeEach
  void setUp() {
    log = new SqliteAssertionLog(dir.resolve("scratch.db"));
    graph = new TinkerGraphStore();
    ingest = new IngestService(log, graph, IdentityMerge.NONE);
  }

  @AfterEach
  void tearDown() {
    graph.close();
    log.close();
  }

  private EntityAddition against(StubWikidataServer stub) {
    return new EntityAddition(
        new WikidataEntityResolver(new WikidataClient(stub.baseUri()), FIXED), ingest);
  }

  /** One entity, with no {@code P31} at all, so the kind derives to {@code CONCEPT}. */
  private static String entity(String qid, String label) {
    return "{\"entities\":{\""
        + qid
        + "\":{\"id\":\""
        + qid
        + "\",\"labels\":{\"en\":{\"value\":\""
        + label
        + "\"}}}}}";
  }

  @Test
  @DisplayName("the rule records the node claim and reports what it recorded")
  void shouldRecordTheNodeClaimWhenTheSourceAnswers() {
    try (StubWikidataServer stub = new StubWikidataServer()) {
      stub.enqueueBody(entity(ANSWERED_FOR, "an act nobody signed"));

      AdditionOutcome outcome = against(stub).add(ANSWERED_FOR);

      assertThat(outcome)
          .isInstanceOfSatisfying(
              AdditionOutcome.Added.class,
              added -> {
                assertThat(added.qid()).isEqualTo(ANSWERED_FOR);
                assertThat(added.node().label()).isEqualTo("an act nobody signed");
                assertThat(added.node().kind()).isEqualTo(NodeKind.CONCEPT);
              });
      assertThat(graph.node(ANSWERED_FOR)).isPresent();
    }
  }

  @Test
  @DisplayName(
      "a second call refreshes the node rather than duplicating it, because record upserts")
  void shouldRefreshTheNodeWhenTheSameEntityIsAddedTwice() {
    try (StubWikidataServer stub = new StubWikidataServer()) {
      stub.enqueueBody(entity(ANSWERED_FOR, "an act nobody signed"));
      stub.enqueueBody(entity(ANSWERED_FOR, "an act nobody booked"));
      EntityAddition addition = against(stub);

      addition.add(ANSWERED_FOR);
      AdditionOutcome second = addition.add(ANSWERED_FOR);

      assertThat(second).isInstanceOf(AdditionOutcome.Added.class);
      assertThat(graph.node(ANSWERED_FOR).orElseThrow().label())
          .as("the later reading is the better one — GraphStore.upsertNode is last-writer-wins")
          .isEqualTo("an act nobody booked");
      assertThat(log.readAll())
          .as("two claims, because a changed belief is a new claim")
          .hasSize(2);
    }
  }

  @Test
  @DisplayName("an id the source has no entity for is refused, and nothing is recorded")
  void shouldRefuseAndRecordNothingWhenTheSourceHasNoSuchEntity() {
    try (StubWikidataServer stub = new StubWikidataServer()) {
      stub.enqueueBody("{\"entities\":{\"" + NO_SUCH_ENTITY + "\":{\"missing\":\"\"}}}");

      AdditionOutcome outcome = against(stub).add(NO_SUCH_ENTITY);

      assertThat(outcome)
          .isEqualTo(
              new AdditionOutcome.Refused(NO_SUCH_ENTITY, AdditionOutcome.Reason.NO_SUCH_ENTITY));
      assertThat(log.readAll()).isEmpty();
    }
  }

  @Test
  @DisplayName("a source that cannot be reached is a refusal carrying its own words, not a throw")
  void shouldRefuseWithTheSourcesWordsWhenTheSourceCannotBeReached() {
    try (StubWikidataServer stub = new StubWikidataServer()) {
      // 404, not 5xx: WikidataClient.isTransient refuses it outright, so nothing here sleeps.
      stub.enqueueStatus(404);

      AdditionOutcome outcome = against(stub).add(UNREACHABLE);

      assertThat(outcome)
          .isInstanceOfSatisfying(
              AdditionOutcome.Refused.class,
              refused -> {
                assertThat(refused.reason()).isEqualTo(AdditionOutcome.Reason.SOURCE_UNAVAILABLE);
                assertThat(refused.detail()).contains("404");
              });
      assertThat(log.readAll()).isEmpty();
    }
  }

  @Test
  @DisplayName("a source outage on the add is logged without the entity id")
  void shouldNameNoEntityInTheLogWhenTheSourceIsUnavailable() {
    Logger entityAdditionLogger = (Logger) org.slf4j.LoggerFactory.getLogger(EntityAddition.class);
    Level originalLevel = entityAdditionLogger.getLevel();
    ListAppender<ILoggingEvent> captured = new ListAppender<>();
    captured.start();
    entityAdditionLogger.setLevel(Level.TRACE);
    entityAdditionLogger.addAppender(captured);
    try (StubWikidataServer stub = new StubWikidataServer()) {
      // 404, not 5xx: WikidataClient.isTransient refuses it outright, so nothing here sleeps.
      stub.enqueueStatus(404);

      against(stub).add(UNREACHABLE);

      assertThat(captured.list)
          .as("the outage really was logged, or the assertion below is vacuous")
          .isNotEmpty();
      assertThat(List.copyOf(captured.list))
          .extracting(ILoggingEvent::getFormattedMessage)
          .as("no logged message names the entity — a dev tool never names one on the terminal")
          .noneMatch(message -> A_QID.matcher(message).find());
    } finally {
      entityAdditionLogger.detachAppender(captured);
      entityAdditionLogger.setLevel(originalLevel);
    }
  }

  @Test
  @DisplayName("a value that is not a qid is refused before the source is asked")
  void shouldRefuseBeforeAskingWhenTheValueIsNotAQid() {
    try (StubWikidataServer stub = new StubWikidataServer()) {
      AdditionOutcome outcome = against(stub).add("an act nobody signed");

      assertThat(outcome)
          .isEqualTo(
              new AdditionOutcome.Refused(
                  "an act nobody signed", AdditionOutcome.Reason.NOT_A_QID));
      assertThat(stub.requestCount()).as("the source was never asked").isZero();
    }
  }

  @Test
  @DisplayName("a minted id is refused before the source is asked, because no source can have it")
  void shouldRefuseBeforeAskingWhenTheIdWasMintedByTheOwner() {
    try (StubWikidataServer stub = new StubWikidataServer()) {
      AdditionOutcome outcome = against(stub).add(MINTED);

      assertThat(outcome)
          .isEqualTo(new AdditionOutcome.Refused(MINTED, AdditionOutcome.Reason.LOCAL_ENTITY));
      assertThat(stub.requestCount()).as("the source was never asked").isZero();
      assertThat(log.readAll()).isEmpty();
    }
  }
}
