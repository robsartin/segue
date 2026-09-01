package com.robsartin.segue.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.segue.ingest.IngestService;
import com.robsartin.segue.port.AffinityStore;
import com.robsartin.segue.port.AssertionLog;
import com.robsartin.segue.port.GraphStore;
import com.robsartin.segue.port.IdentityMerge;
import com.robsartin.segue.port.SourceAdapters;
import com.robsartin.segue.sqlite.SqliteAffinityStore;
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
 * Issue #32's acceptance criteria, against the real API, with nothing seeded by hand.
 *
 * <p>Three novelists are added as people and expanded. Nobody types an award's QID and nobody types
 * a novel's: if a route appears between two of them, every node on it was discovered. That is the
 * whole criterion — the failure this fixes was that expanding two novelists produced two
 * neighbourhoods with nothing in common, because every relation in the vocabulary described a
 * <em>collaboration</em> and single-authored novels have none.
 *
 * <p><b>The fourth seed is the control, and it is the more important half.</b> Steve Hofstetter is
 * a comedian; he shares no award with any of the three. If registering award-received had connected
 * him too, the property would be behaving like the hub properties this change deliberately did not
 * register — genre and occupation, where 16,552 and 35,977 items respectively hang off one node and
 * every pair of writers is two perfectly-confident, meaningless hops apart. A test that only
 * checked the positive cases could not tell the two outcomes apart. See ADR 38.
 *
 * <p><b>This is also the only thing that can notice the data moving.</b> The fixture-backed {@code
 * SharedAwardRouteTest} asserts whatever its author captured; a Hugo statement removed from
 * Wikidata tomorrow would leave it green forever. Tagged {@code live} and excluded from {@code
 * check} — run it with {@code ./gradlew liveTest}.
 */
@Tag("live")
class SharedAwardRouteLiveTest {

  /** William Gibson — Hugo, Nebula and Seiun winner. */
  private static final String GIBSON = "Q188987";

  /** John Scalzi — Hugo, Locus, Seiun and Bob Morane winner. */
  private static final String SCALZI = "Q277308";

  /** Martha Wells — Hugo, Nebula, Locus and Bob Morane winner. */
  private static final String WELLS = "Q6774606";

  /** Steve Hofstetter — a comedian, whose Wikidata item states no award at all. */
  private static final String HOFSTETTER = "Q7612859";

  private AssertionLog log;
  private GraphStore graph;
  // Required by SegueService since increment 5, and unused by these tests: a route query
  // reads the world-fact layer only. In-memory so it dies with the test (ADR 33).
  private AffinityStore affinity;
  private SegueService service;

  @BeforeEach
  void setUp() {
    log = SqliteAssertionLog.inMemory();
    graph = new TinkerGraphStore();
    affinity = SqliteAffinityStore.inMemory();
    WikidataEntityResolver resolver = new WikidataEntityResolver(new WikidataClient());
    service =
        new SegueService(
            resolver,
            graph,
            new IngestService(log, graph, IdentityMerge.NONE),
            new SourceAdapters(
                List.of(
                    new WikidataSourceAdapter(
                        resolver, WikidataClient.queryService(), Clock.systemUTC()))),
            affinity,
            Clock.systemUTC());
  }

  @AfterEach
  void tearDown() {
    affinity.close();
    graph.close();
    log.close();
  }

  private void addAndExpand(String qid) {
    assertThat(service.addEntity(qid).outcome()).isEqualTo(ToolResult.Outcome.OK);
    // PARTIAL is legitimate: at this bound Wikidata may have more to say, and some neighbours
    // have no English label to resolve. What must not happen is ERROR.
    assertThat(service.expandEntity(qid, 200).outcome()).isNotEqualTo(ToolResult.Outcome.ERROR);
  }

  private void assertConnectedThroughAnAward(String from, String to) {
    ToolResult<List<PathView>> routes = service.findPaths(from, to, 2);

    assertThat(routes.outcome()).isEqualTo(ToolResult.Outcome.OK);
    assertThat(routes.payload())
        .as("a route from %s to %s", from, to)
        .anySatisfy(
            route ->
                assertThat(route.hops())
                    .as("every hop of a two-hop award route")
                    .hasSize(2)
                    .allSatisfy(
                        hop -> assertThat(hop.edge().typeCode()).isEqualTo("RECEIVED_AWARD")));
  }

  @Test
  @DisplayName("three novelists, seeded only as people, all connect through awards they share")
  void novelistsConnectThroughSharedAwards() {
    addAndExpand(GIBSON);
    addAndExpand(SCALZI);
    addAndExpand(WELLS);

    assertConnectedThroughAnAward(GIBSON, SCALZI);
    assertConnectedThroughAnAward(WELLS, SCALZI);
    assertConnectedThroughAnAward(GIBSON, WELLS);
  }

  @Test
  @DisplayName("a comedian who shares no award with them connects to none of them")
  void anUnrelatedPersonStaysUnconnected() {
    addAndExpand(GIBSON);
    addAndExpand(SCALZI);
    addAndExpand(WELLS);
    addAndExpand(HOFSTETTER);

    for (String novelist : List.of(GIBSON, SCALZI, WELLS)) {
      ToolResult<List<PathView>> routes = service.findPaths(HOFSTETTER, novelist, 3);

      assertThat(routes.outcome()).isEqualTo(ToolResult.Outcome.OK);
      assertThat(routes.payload()).as("routes from Hofstetter to %s", novelist).isEmpty();
    }
  }
}
