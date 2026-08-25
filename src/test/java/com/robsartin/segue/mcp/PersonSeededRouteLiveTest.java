package com.robsartin.segue.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.segue.ingest.IngestService;
import com.robsartin.segue.port.AssertionLog;
import com.robsartin.segue.port.GraphStore;
import com.robsartin.segue.port.SourceAdapters;
import com.robsartin.segue.sqlite.SqliteAssertionLog;
import com.robsartin.segue.tinker.TinkerGraphStore;
import com.robsartin.segue.wikidata.WikidataClient;
import com.robsartin.segue.wikidata.WikidataEntityResolver;
import com.robsartin.segue.wikidata.WikidataSourceAdapter;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Issue #20's last acceptance criterion, end to end against the real API: a route between two
 * people, seeded only as people.
 *
 * <p>This is the criterion the other tests cannot stand in for. Every hop here has to be
 * <em>discovered</em>: nobody types the film's QID, and before ADR 36 nobody could have, because
 * expanding either person found nothing but their band memberships. The graph dead-ended at depth 1
 * and {@code find_paths} had nothing to route through — which made the project's payoff feature
 * work only for a user who already knew which works to add, and that is knowledge they would have
 * had to be told.
 *
 * <p>Tagged {@code live}: it needs the network and is excluded from {@code check}. Run it with
 * {@code ./gradlew liveTest}.
 */
@Tag("live")
class PersonSeededRouteLiveTest {

  /** Nick Cave — wrote the screenplay for and scored The Proposition. */
  private static final String CAVE = "Q192668";

  /**
   * John Hillcoat — directed it. Chosen because the two men share no band, no label and no
   * membership: the only route between them runs through a WORK that neither of their Wikidata
   * items mentions, since P57 and P58 are both stated on the film.
   */
  private static final String HILLCOAT = "Q552814";

  private AssertionLog log;
  private GraphStore graph;
  private SegueService service;

  @BeforeEach
  void setUp() {
    log = SqliteAssertionLog.inMemory();
    graph = new TinkerGraphStore();
    WikidataEntityResolver resolver = new WikidataEntityResolver(new WikidataClient());
    service =
        new SegueService(
            resolver,
            graph,
            new IngestService(log, graph),
            new SourceAdapters(
                List.of(
                    new WikidataSourceAdapter(
                        resolver, WikidataClient.queryService(), Clock.systemUTC()))));
  }

  @AfterEach
  void tearDown() {
    graph.close();
    log.close();
  }

  @Test
  @DisplayName("two people, seeded as people, are connected by a route nobody had to seed by hand")
  void personSeedsAloneProduceARoute() {
    assertThat(service.addEntity(CAVE).outcome()).isEqualTo(ToolResult.Outcome.OK);
    assertThat(service.addEntity(HILLCOAT).outcome()).isEqualTo(ToolResult.Outcome.OK);

    // PARTIAL is a legitimate outcome: at this bound Wikidata may still have more to say, and
    // some neighbours have no English label to resolve. What must not happen is ERROR.
    assertThat(service.expandEntity(CAVE, 100).outcome()).isNotEqualTo(ToolResult.Outcome.ERROR);
    assertThat(service.expandEntity(HILLCOAT, 100).outcome())
        .isNotEqualTo(ToolResult.Outcome.ERROR);

    ToolResult<List<PathView>> routes = service.findPaths(CAVE, HILLCOAT, 3);

    assertThat(routes.outcome()).isEqualTo(ToolResult.Outcome.OK);
    assertThat(routes.payload()).isNotEmpty();
  }
}
