package com.robsartin.segue.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.segue.app.SourceAdapters;
import com.robsartin.segue.domain.AssertionRecord;
import com.robsartin.segue.domain.Candidate;
import com.robsartin.segue.domain.NodeAssertion;
import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.domain.NodeRecord;
import com.robsartin.segue.domain.PathResult;
import com.robsartin.segue.domain.Provenance;
import com.robsartin.segue.fixture.Fixture;
import com.robsartin.segue.fixture.FixtureSourceAdapter;
import com.robsartin.segue.ingest.IngestService;
import com.robsartin.segue.port.AssertionLog;
import com.robsartin.segue.port.EntityResolver;
import com.robsartin.segue.port.ExpandContext;
import com.robsartin.segue.port.ExpandResult;
import com.robsartin.segue.port.GraphStore;
import com.robsartin.segue.port.SourceAdapter;
import com.robsartin.segue.sqlite.SqliteAssertionLog;
import com.robsartin.segue.tinker.TinkerGraphStore;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The facade every MCP tool calls. Runs entirely against a fixture-backed adapter and an in-memory
 * SQLite log — no network reaches these tests.
 */
class SegueServiceTest {

  private static final Provenance WIKIDATA =
      new Provenance("wikidata", "S-1", Instant.parse("2026-08-24T09:00:00Z"), 1.0);

  private AssertionLog log;
  private GraphStore graph;
  private IngestService ingest;
  private StubEntityResolver resolver;

  @BeforeEach
  void setUp() {
    log = SqliteAssertionLog.inMemory();
    graph = new TinkerGraphStore();
    ingest = new IngestService(log, graph);
    resolver = new StubEntityResolver();
  }

  @AfterEach
  void tearDown() {
    graph.close();
    log.close();
  }

  private SegueService service(SourceAdapter... adapters) {
    return new SegueService(resolver, graph, ingest, new SourceAdapters(List.of(adapters)));
  }

  // ---- search -------------------------------------------------------------

  @Test
  @DisplayName("search delegates to the resolver and writes nothing")
  void searchWritesNothing() {
    Candidate candidate = new Candidate("Q1", "Nick Cave", "musician", NodeKind.PERSON);
    resolver.withSearchResults(List.of(candidate));

    ToolResult<List<Candidate>> result = service().search("nick cave", null, 5);

    assertThat(result.outcome()).isEqualTo("ok");
    assertThat(result.payload()).containsExactly(candidate);
    assertThat(log.readAll()).isEmpty();
    assertThat(graph.edgeCount()).isZero();
  }

  // ---- addEntity ------------------------------------------------------------

  @Test
  @DisplayName("addEntity fetches and records, and is idempotent on a second call")
  void addEntityRecordsAndIsIdempotent() {
    NodeAssertion assertion = new NodeAssertion("Q1", NodeKind.PERSON, "Nick Cave", WIKIDATA);
    resolver.withEntity(assertion);

    ToolResult<NodeRecord> first = service().addEntity("Q1");
    ToolResult<NodeRecord> second = service().addEntity("Q1");

    assertThat(first.outcome()).isEqualTo("ok");
    assertThat(second.outcome()).isEqualTo("ok");
    assertThat(first.payload()).isEqualTo(assertion.toNode());
    assertThat(second.payload()).isEqualTo(assertion.toNode());
    assertThat(graph.node("Q1")).contains(assertion.toNode());
  }

  @Test
  @DisplayName("addEntity on an unknown qid returns an error naming the qid, not an exception")
  void addEntityUnknownQidReturnsError() {
    ToolResult<NodeRecord> result = service().addEntity("Q404");

    assertThat(result.outcome()).isEqualTo("error");
    assertThat(result.detail()).contains("Q404");
    assertThat(graph.node("Q404")).isEmpty();
    assertThat(log.readAll()).isEmpty();
  }

  // ---- expandEntity -----------------------------------------------------

  @Test
  @DisplayName("expandEntity on an unknown seed returns an error, not an exception")
  void expandEntityUnknownSeedReturnsError() {
    ToolResult<SegueService.ExpansionSummary> result = service().expandEntity("Q999999", 10);

    assertThat(result.outcome()).isEqualTo("error");
    assertThat(result.detail()).contains("Q999999");
  }

  @Test
  @DisplayName("expandEntity reports partial when a source is unavailable")
  void expandEntityReportsPartialWhenSourceUnavailable() {
    ingest.record(new NodeAssertion("Q1", NodeKind.PERSON, "Nick Cave", WIKIDATA));
    SourceAdapter unavailable = new StubSourceAdapter("flaky", ExpandResult.unavailable());

    ToolResult<SegueService.ExpansionSummary> result = service(unavailable).expandEntity("Q1", 10);

    assertThat(result.outcome()).isEqualTo("partial");
    assertThat(result.detail()).containsIgnoringCase("unavailable");
  }

  @Test
  @DisplayName("expandEntity reports partial when the result was truncated")
  void expandEntityReportsPartialWhenTruncated() {
    ingest.record(new NodeAssertion("Q1", NodeKind.PERSON, "Nick Cave", WIKIDATA));
    ingest.record(new NodeAssertion("Q2", NodeKind.GROUP, "Bad Seeds", WIKIDATA));
    AssertionRecord edge = new AssertionRecord("Q1", "Q2", "MEMBER_OF", null, null, WIKIDATA);
    SourceAdapter truncating =
        new StubSourceAdapter("cut-short", new ExpandResult(List.of(edge), false, true));

    ToolResult<SegueService.ExpansionSummary> result = service(truncating).expandEntity("Q1", 10);

    assertThat(result.outcome()).isEqualTo("partial");
    assertThat(result.detail()).containsIgnoringCase("truncat");
  }

  @Test
  @DisplayName("expandEntity reports partial and skips an edge whose neighbour cannot be resolved")
  void expandEntitySkipsUnreachableNeighbour() {
    ingest.record(new NodeAssertion("Q1", NodeKind.PERSON, "Nick Cave", WIKIDATA));
    resolver.withEntity(new NodeAssertion("Q2", NodeKind.GROUP, "Bad Seeds", WIKIDATA));
    // Q3 is deliberately left unresolvable.
    AssertionRecord resolvable = new AssertionRecord("Q1", "Q2", "MEMBER_OF", null, null, WIKIDATA);
    AssertionRecord unresolvable =
        new AssertionRecord("Q1", "Q3", "MEMBER_OF", null, null, WIKIDATA);
    SourceAdapter adapter =
        new StubSourceAdapter(
            "mixed", new ExpandResult(List.of(resolvable, unresolvable), false, false));

    ToolResult<SegueService.ExpansionSummary> result = service(adapter).expandEntity("Q1", 10);

    assertThat(result.outcome()).isEqualTo("partial");
    assertThat(result.detail()).containsIgnoringCase("skip");
    assertThat(result.payload().skippedNeighbors()).isEqualTo(1);
    assertThat(result.payload().edgesAdded()).isEqualTo(1);
    assertThat(graph.node("Q3")).isEmpty();
    assertThat(graph.node("Q2")).isPresent();
  }

  @Test
  @DisplayName("expandEntity resolves unknown neighbours and creates their nodes before the edges")
  void expandEntityCreatesNeighbourNodesBeforeEdges() {
    ingest.record(new NodeAssertion(Fixture.CAVE, NodeKind.PERSON, "Nick Cave", WIKIDATA));
    List<AssertionRecord> matching =
        Fixture.assertions().stream()
            .filter(a -> a.fromQid().equals(Fixture.CAVE) || a.toQid().equals(Fixture.CAVE))
            .toList();
    for (var node : Fixture.nodes()) {
      if (!node.qid().equals(Fixture.CAVE)) {
        resolver.withEntity(new NodeAssertion(node.qid(), node.kind(), node.label(), WIKIDATA));
      }
    }

    ToolResult<SegueService.ExpansionSummary> result =
        service(new FixtureSourceAdapter()).expandEntity(Fixture.CAVE, 200);

    assertThat(result.outcome()).isEqualTo("ok");
    assertThat(result.payload().edgesAdded()).isEqualTo(matching.size());
    assertThat(result.payload().skippedNeighbors()).isZero();
    // Every neighbour referenced by a matching assertion now has a node, created via the
    // resolver's fetch — GraphStore.record would have thrown otherwise.
    for (AssertionRecord a : matching) {
      String neighbour = a.fromQid().equals(Fixture.CAVE) ? a.toQid() : a.fromQid();
      assertThat(graph.node(neighbour)).isPresent();
    }
    // Two of the matching assertions are a second source corroborating an edge another
    // assertion already created (Bad Seeds membership, The Proposition's score), so distinct
    // EDGES are fewer than assertions recorded — that merge is GraphStore.record's job, not
    // evidence expandEntity dropped anything.
    long distinctEdges = matching.stream().map(AssertionRecord::edgeKey).distinct().count();
    assertThat(graph.edges(Fixture.CAVE)).hasSize((int) distinctEdges);
  }

  @Test
  @DisplayName("expandEntity does not call the resolver when every neighbour is already known")
  void expandEntityDoesNotResolveKnownNeighbours() {
    Fixture.seed(graph);

    ToolResult<SegueService.ExpansionSummary> result =
        service(new FixtureSourceAdapter()).expandEntity(Fixture.CAVE, 200);

    assertThat(result.outcome()).isEqualTo("ok");
    assertThat(resolver.fetchCallCount()).isZero();
    assertThat(result.payload().nodesAdded()).isZero();
  }

  // ---- getEntity ----------------------------------------------------------

  @Test
  @DisplayName("getEntity groups neighbours by edge type")
  void getEntityGroupsByType() {
    Fixture.seed(graph);

    ToolResult<SegueService.EntityView> result = service().getEntity(Fixture.CAVE);

    assertThat(result.outcome()).isEqualTo("ok");
    SegueService.EntityView view = result.payload();
    assertThat(view.node().qid()).isEqualTo(Fixture.CAVE);
    Map<String, List<String>> byQid = new HashMap<>();
    view.neighborsByType()
        .forEach((type, nodes) -> byQid.put(type, nodes.stream().map(NodeRecord::qid).toList()));

    assertThat(byQid.get("MEMBER_OF"))
        .containsExactlyInAnyOrder(Fixture.BAD_SEEDS, Fixture.BIRTHDAY_PARTY, Fixture.GRINDERMAN);
    assertThat(byQid.get("WROTE_SCREENPLAY_FOR")).containsExactly(Fixture.PROPOSITION);
    assertThat(byQid.get("COMPOSED_FOR"))
        .containsExactlyInAnyOrder(Fixture.PROPOSITION, Fixture.ROAD_FILM);
    assertThat(byQid.get("AUTHORED")).containsExactly(Fixture.ASS_SAW_ANGEL);
    assertThat(byQid.get("SIMILAR_TO")).containsExactly(Fixture.PJ_HARVEY);
    assertThat(byQid.get("COLLABORATED_WITH")).containsExactly(Fixture.PJ_HARVEY);
    assertThat(byQid.get("INFLUENCED_BY")).containsExactly(Fixture.MCCARTHY);
  }

  @Test
  @DisplayName("getEntity on an unknown qid returns an error")
  void getEntityUnknownQidReturnsError() {
    ToolResult<SegueService.EntityView> result = service().getEntity("Q404");

    assertThat(result.outcome()).isEqualTo("error");
    assertThat(result.detail()).contains("Q404");
  }

  // ---- findPaths ----------------------------------------------------------

  @Test
  @DisplayName("findPaths ranks routes most-trustworthy-first, never raw")
  void findPathsReturnsRankedOrder() {
    Fixture.seed(graph);

    ToolResult<List<PathResult>> result = service().findPaths(Fixture.CAVE, Fixture.MCCARTHY, 4);

    assertThat(result.outcome()).isEqualTo("ok");
    List<PathResult> ranked = result.payload();
    assertThat(ranked).isNotEmpty();
    // ADR 31: the fully-sourced three-hop route through The Road outranks the one-hop
    // model-generated shortcut, even though the shortcut is shorter.
    PathResult top = ranked.get(0);
    assertThat(top.length()).isEqualTo(3);
    assertThat(top.weakestConfidence()).isEqualTo(1.0);
    boolean hasShortcut = ranked.stream().anyMatch(p -> p.length() == 1);
    assertThat(hasShortcut).isTrue();
    assertThat(ranked.indexOf(top))
        .isLessThan(
            ranked.stream()
                .filter(p -> p.length() == 1)
                .findFirst()
                .map(ranked::indexOf)
                .orElseThrow());
  }

  @Test
  @DisplayName("findPaths on a pair with no route returns ok with an empty payload")
  void findPathsNoRouteReturnsEmptyOk() {
    ingest.record(new NodeAssertion("Q1", NodeKind.PERSON, "Alone", WIKIDATA));
    ingest.record(new NodeAssertion("Q2", NodeKind.PERSON, "Also Alone", WIKIDATA));

    ToolResult<List<PathResult>> result = service().findPaths("Q1", "Q2", 3);

    assertThat(result.outcome()).isEqualTo("ok");
    assertThat(result.payload()).isEmpty();
  }

  // ---- test doubles ---------------------------------------------------------

  private static final class StubEntityResolver implements EntityResolver {
    private final Map<String, NodeAssertion> byQid = new HashMap<>();
    private List<Candidate> searchResults = List.of();
    private int fetchCalls;

    @Override
    public String id() {
      return "stub";
    }

    @Override
    public List<Candidate> search(String query, NodeKind kind, int limit) {
      return searchResults;
    }

    @Override
    public Optional<NodeAssertion> fetch(String qid) {
      fetchCalls++;
      return Optional.ofNullable(byQid.get(qid));
    }

    StubEntityResolver withSearchResults(List<Candidate> results) {
      this.searchResults = results;
      return this;
    }

    StubEntityResolver withEntity(NodeAssertion assertion) {
      byQid.put(assertion.qid(), assertion);
      return this;
    }

    int fetchCallCount() {
      return fetchCalls;
    }
  }

  private static final class StubSourceAdapter implements SourceAdapter {
    private final String id;
    private final ExpandResult result;

    StubSourceAdapter(String id, ExpandResult result) {
      this.id = id;
      this.result = result;
    }

    @Override
    public String id() {
      return id;
    }

    @Override
    public boolean supports(NodeKind kind) {
      return true;
    }

    @Override
    public ExpandResult expand(NodeRecord seed, ExpandContext ctx) {
      return result;
    }
  }
}
