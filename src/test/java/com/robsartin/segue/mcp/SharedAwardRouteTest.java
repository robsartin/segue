package com.robsartin.segue.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.segue.ingest.IngestService;
import com.robsartin.segue.port.AffinityStore;
import com.robsartin.segue.port.AssertionLog;
import com.robsartin.segue.port.GraphStore;
import com.robsartin.segue.port.SourceAdapters;
import com.robsartin.segue.sqlite.SqliteAffinityStore;
import com.robsartin.segue.sqlite.SqliteAssertionLog;
import com.robsartin.segue.tinker.TinkerGraphStore;
import com.robsartin.segue.wikidata.StubWikidataServer;
import com.robsartin.segue.wikidata.WikidataClient;
import com.robsartin.segue.wikidata.WikidataEntityResolver;
import com.robsartin.segue.wikidata.WikidataSourceAdapter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Issue #32's acceptance criterion, offline: two people who share an award are connected by a
 * route, and a person who shares no award with them is not.
 *
 * <p><b>Why this failure needed its own fix.</b> Before P166 was registered, two novelists who had
 * never collaborated were unreachable from each other however far the graph was expanded. Every
 * relation in the vocabulary was a <em>collaboration</em> — co-credits on one work, membership of
 * one group — so two writers whose only connection is that the field honoured them both had no hop
 * to travel along. That is the common case in literature, where the unit of work has exactly one
 * author, and it made {@code find_paths} silently useless for a whole domain.
 *
 * <p><b>Why an award and not a genre.</b> An award node is small: measured live against the Query
 * Service, 127 items name the Hugo Award for Best Novel through P166, against 16,552 that name
 * science fiction through P136 (genre). A route through a 127-item node is a fact about these two
 * people; a route through a 16,552-item node connects every pair of SF writers alive at two
 * perfectly-confident hops and explains nothing. ADR 38 records the measurements and what stays
 * open.
 *
 * <p>The live counterpart is {@code SharedAwardRouteLiveTest}, which runs the same criterion
 * against the real API with nothing seeded by hand. This one pins the mechanism deterministically
 * and is part of {@code check}; that one is the only thing that can tell us the data still says
 * what we think it does.
 */
class SharedAwardRouteTest {

  private static final Clock FIXED =
      Clock.fixed(Instant.parse("2026-08-25T09:00:00Z"), ZoneOffset.UTC);

  private static final String GIBSON = "Q188987";
  private static final String SCALZI = "Q277308";

  /** Steve Hofstetter — a comedian, and the control. His Wikidata item states no P166 at all. */
  private static final String HOFSTETTER = "Q7612859";

  private static final String HUGO_BEST_NOVEL = "Q255032";

  private AssertionLog log;
  private GraphStore graph;
  // Required by SegueService since increment 5, and unused by these tests: a route query
  // reads the world-fact layer only. In-memory so it dies with the test (ADR 33).
  private AffinityStore affinity;
  private SegueService service;
  private StubWikidataServer actionApi;
  private StubWikidataServer queryService;

  private static String resource(String name) throws IOException {
    try (InputStream in = SharedAwardRouteTest.class.getResourceAsStream(name)) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  @BeforeEach
  void setUp() {
    log = SqliteAssertionLog.inMemory();
    graph = new TinkerGraphStore();
    affinity = SqliteAffinityStore.inMemory();
    actionApi = new StubWikidataServer();
    queryService = new StubWikidataServer();
    WikidataEntityResolver resolver =
        new WikidataEntityResolver(new WikidataClient(actionApi.baseUri()), FIXED);
    service =
        new SegueService(
            resolver,
            graph,
            new IngestService(log, graph),
            new SourceAdapters(
                List.of(
                    new WikidataSourceAdapter(
                        resolver, new WikidataClient(queryService.baseUri()), FIXED))),
            affinity,
            FIXED);
  }

  @AfterEach
  void tearDown() {
    queryService.close();
    actionApi.close();
    affinity.close();
    graph.close();
    log.close();
  }

  /**
   * Both people, added and expanded, in the order the stub's single queue expects.
   *
   * <p>Nothing is enqueued on the Query Service stub: its default {@code &#123;&#125;} parses as
   * "no backlinks", which is what the real reverse lookup returns for a person seed here anyway —
   * P166 is stated on the recipient, so the award arrives on the FORWARD pass and no item points at
   * a person through an award property.
   */
  private void seedTheTwoAuthors() throws IOException {
    // addEntity fetches identity; expandEntity fetches the same entity again for its claims;
    // then the award, which the forward pass discovered without an identity for it, is fetched
    // once. Gibson's expansion is what puts the award in the graph, so Scalzi's does not refetch.
    actionApi.enqueueBody(resource("/wikidata/gibson-claims.json"));
    actionApi.enqueueBody(resource("/wikidata/gibson-claims.json"));
    actionApi.enqueueBody(resource("/wikidata/hugo-best-novel.json"));
    actionApi.enqueueBody(resource("/wikidata/scalzi-claims.json"));
    actionApi.enqueueBody(resource("/wikidata/scalzi-claims.json"));

    assertThat(service.addEntity(GIBSON).outcome()).isEqualTo(ToolResult.Outcome.OK);
    assertThat(service.expandEntity(GIBSON, 200).outcome()).isEqualTo(ToolResult.Outcome.OK);
    assertThat(service.addEntity(SCALZI).outcome()).isEqualTo(ToolResult.Outcome.OK);
    assertThat(service.expandEntity(SCALZI, 200).outcome()).isEqualTo(ToolResult.Outcome.OK);
  }

  @Test
  @DisplayName("two authors who never collaborated are connected through the award they share")
  void sharedAwardConnectsTwoAuthors() throws IOException {
    seedTheTwoAuthors();

    ToolResult<List<PathView>> routes = service.findPaths(GIBSON, SCALZI, 3);

    assertThat(routes.outcome()).isEqualTo(ToolResult.Outcome.OK);
    assertThat(routes.payload()).isNotEmpty();

    // Two hops, and the middle node is the award. Asserting the shape rather than only that
    // something came back is what makes this a test of the award edge: a route of any other
    // shape between two people who share nothing else would mean something unintended got in.
    PathView route = routes.payload().getFirst();
    assertThat(route.hops()).hasSize(2);
    assertThat(route.hops())
        .extracting(hop -> hop.edge().typeCode())
        .containsExactly("RECEIVED_AWARD", "RECEIVED_AWARD");
    assertThat(route.hops().getFirst().to().qid()).isEqualTo(HUGO_BEST_NOVEL);
  }

  @Test
  @DisplayName("the award edge is stored pointing from the recipient at the award")
  void theAwardEdgeReadsFromTheRecipient() throws IOException {
    // Direction, end to end rather than only in ClaimMapper. Wikidata states P166 on the
    // recipient, so RECEIVED_AWARD is DIRECT and both stored edges run person -> award. Getting
    // it backwards still produces a working two-hop route, which is exactly why the route test
    // above cannot catch it: every citation would then read "the Hugo Award received John
    // Scalzi", and the payoff feature is the citation.
    seedTheTwoAuthors();

    ToolResult<List<PathView>> routes = service.findPaths(GIBSON, SCALZI, 3);
    List<HopView> hops = routes.payload().getFirst().hops();

    assertThat(hops.getFirst().edge().fromQid()).isEqualTo(GIBSON);
    assertThat(hops.getFirst().edge().toQid()).isEqualTo(HUGO_BEST_NOVEL);
    assertThat(hops.get(1).edge().fromQid()).isEqualTo(SCALZI);
    assertThat(hops.get(1).edge().toQid()).isEqualTo(HUGO_BEST_NOVEL);
    // The walk reaches Scalzi against that second edge's stored direction, and the renderer
    // says so — "Hugo Award for Best Novel <-[RECEIVED_AWARD]- John Scalzi".
    assertThat(hops.getFirst().traversedBackwards()).isFalse();
    assertThat(hops.get(1).traversedBackwards()).isTrue();
  }

  @Test
  @DisplayName("get_entity groups the award under RECEIVED_AWARD, with the award as the neighbour")
  void getEntityGroupsTheAward() throws IOException {
    seedTheTwoAuthors();

    ToolResult<EntityView> gibson = service.getEntity(GIBSON);

    assertThat(gibson.outcome()).isEqualTo(ToolResult.Outcome.OK);
    assertThat(gibson.payload().neighborsByType())
        .filteredOn(group -> group.typeCode().equals("RECEIVED_AWARD"))
        .singleElement()
        .satisfies(
            group ->
                assertThat(group.neighbors())
                    .extracting(NodeView::label)
                    .containsExactly("Hugo Award for Best Novel"));
  }

  @Test
  @DisplayName("someone who shares no award is left unconnected, which is the honest answer")
  void someoneWithNoSharedAwardStaysUnconnected() throws IOException {
    // The control. Registering a hub property instead — genre, occupation — would connect these
    // two as readily as it connects the novelists, because "American" and "writer" and "science
    // fiction" are nodes tens of thousands of items point at. A vocabulary that connects
    // everything to everything has stopped saying anything, so the negative case is as much the
    // acceptance criterion as the positive one.
    seedTheTwoAuthors();
    actionApi.enqueueBody(resource("/wikidata/hofstetter-claims.json"));
    actionApi.enqueueBody(resource("/wikidata/hofstetter-claims.json"));
    assertThat(service.addEntity(HOFSTETTER).outcome()).isEqualTo(ToolResult.Outcome.OK);
    service.expandEntity(HOFSTETTER, 200);

    ToolResult<List<PathView>> routes = service.findPaths(GIBSON, HOFSTETTER, 3);

    assertThat(routes.outcome()).isEqualTo(ToolResult.Outcome.OK);
    assertThat(routes.payload()).isEmpty();
  }
}
