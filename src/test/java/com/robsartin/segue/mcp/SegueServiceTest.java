package com.robsartin.segue.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.segue.domain.AssertionRecord;
import com.robsartin.segue.domain.Candidate;
import com.robsartin.segue.domain.NodeAssertion;
import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.domain.NodeRecord;
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
import com.robsartin.segue.port.SourceAdapters;
import com.robsartin.segue.sqlite.SqliteAssertionLog;
import com.robsartin.segue.tinker.TinkerGraphStore;
import com.robsartin.segue.wikidata.WikidataUnavailableException;
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

    ToolResult<List<CandidateView>> result = service().search("nick cave", null, 5);

    assertThat(result.outcome()).isEqualTo(ToolResult.Outcome.OK);
    assertThat(result.payload())
        .containsExactly(new CandidateView("Q1", "Nick Cave", "musician", NodeKind.PERSON));
    assertThat(log.readAll()).isEmpty();
    assertThat(graph.edgeCount()).isZero();
  }

  @Test
  @DisplayName("search returns an error, not an exception, when the source is unavailable")
  void searchSourceUnavailableReturnsError() {
    resolver.searchThrows(new WikidataUnavailableException("timed out"));

    ToolResult<List<CandidateView>> result = service().search("nick cave", null, 5);

    assertThat(result.outcome()).isEqualTo(ToolResult.Outcome.ERROR);
    assertThat(result.detail()).containsIgnoringCase("unavailable");
    assertThat(result.payload()).isNull();
  }

  // ---- addEntity ------------------------------------------------------------

  @Test
  @DisplayName("addEntity fetches and records, and is idempotent on a second call")
  void addEntityRecordsAndIsIdempotent() {
    NodeAssertion assertion = new NodeAssertion("Q1", NodeKind.PERSON, "Nick Cave", WIKIDATA);
    resolver.withEntity(assertion);

    ToolResult<NodeView> first = service().addEntity("Q1");
    ToolResult<NodeView> second = service().addEntity("Q1");

    assertThat(first.outcome()).isEqualTo(ToolResult.Outcome.OK);
    assertThat(second.outcome()).isEqualTo(ToolResult.Outcome.OK);
    NodeView expected = new NodeView("Q1", NodeKind.PERSON, "Nick Cave");
    assertThat(first.payload()).isEqualTo(expected);
    assertThat(second.payload()).isEqualTo(expected);
    assertThat(graph.node("Q1")).contains(assertion.toNode());
  }

  @Test
  @DisplayName("addEntity on an unknown qid returns an error naming the qid, not an exception")
  void addEntityUnknownQidReturnsError() {
    ToolResult<NodeView> result = service().addEntity("Q404");

    assertThat(result.outcome()).isEqualTo(ToolResult.Outcome.ERROR);
    assertThat(result.detail()).contains("Q404");
    assertThat(graph.node("Q404")).isEmpty();
    assertThat(log.readAll()).isEmpty();
  }

  @Test
  @DisplayName("addEntity on a malformed qid returns an error, not an exception (ADR 26)")
  void addEntityMalformedQidReturnsError() {
    ToolResult<NodeView> result = service().addEntity("nick cave");

    assertThat(result.outcome()).isEqualTo(ToolResult.Outcome.ERROR);
    assertThat(result.detail()).contains("nick cave");
    assertThat(resolver.fetchCallCount()).isZero();
  }

  @Test
  @DisplayName("addEntity returns an error, not an exception, when the source is unavailable")
  void addEntitySourceUnavailableReturnsError() {
    resolver.fetchThrows(new WikidataUnavailableException("timed out"));

    ToolResult<NodeView> result = service().addEntity("Q1");

    assertThat(result.outcome()).isEqualTo(ToolResult.Outcome.ERROR);
    assertThat(result.detail()).containsIgnoringCase("unavailable");
  }

  // ---- expandEntity -----------------------------------------------------

  @Test
  @DisplayName("expandEntity on an unknown seed returns an error, not an exception")
  void expandEntityUnknownSeedReturnsError() {
    ToolResult<SegueService.ExpansionSummary> result = service().expandEntity("Q999999", 10);

    assertThat(result.outcome()).isEqualTo(ToolResult.Outcome.ERROR);
    assertThat(result.detail()).contains("Q999999");
  }

  @Test
  @DisplayName("expandEntity reports partial when a source is unavailable")
  void expandEntityReportsPartialWhenSourceUnavailable() {
    ingest.record(new NodeAssertion("Q1", NodeKind.PERSON, "Nick Cave", WIKIDATA));
    SourceAdapter unavailable = new StubSourceAdapter("flaky", ExpandResult.unavailable());

    ToolResult<SegueService.ExpansionSummary> result = service(unavailable).expandEntity("Q1", 10);

    assertThat(result.outcome()).isEqualTo(ToolResult.Outcome.PARTIAL);
    assertThat(result.detail()).containsIgnoringCase("unavailable");
    assertThat(result.payload().sourceUnavailable()).isTrue();
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

    assertThat(result.outcome()).isEqualTo(ToolResult.Outcome.PARTIAL);
    assertThat(result.detail()).containsIgnoringCase("truncat");
    assertThat(result.payload().truncated()).isTrue();
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

    assertThat(result.outcome()).isEqualTo(ToolResult.Outcome.PARTIAL);
    assertThat(result.detail()).containsIgnoringCase("skip");
    assertThat(result.payload().skippedNeighbors()).isEqualTo(1);
    assertThat(result.payload().edgesAdded()).isEqualTo(1);
    assertThat(graph.node("Q3")).isEmpty();
    assertThat(graph.node("Q2")).isPresent();
  }

  @Test
  @DisplayName(
      "expandEntity treats a neighbour fetch that throws like one that resolved to nothing, and"
          + " completes as partial rather than aborting the whole expansion")
  void expandEntitySkipsNeighbourWhoseResolutionThrows() {
    ingest.record(new NodeAssertion("Q1", NodeKind.PERSON, "Nick Cave", WIKIDATA));
    resolver.withEntity(new NodeAssertion("Q2", NodeKind.GROUP, "Bad Seeds", WIKIDATA));
    resolver.fetchThrowsFor("Q3", new WikidataUnavailableException("timed out"));
    AssertionRecord resolvable = new AssertionRecord("Q1", "Q2", "MEMBER_OF", null, null, WIKIDATA);
    AssertionRecord flaky = new AssertionRecord("Q1", "Q3", "MEMBER_OF", null, null, WIKIDATA);
    SourceAdapter adapter =
        new StubSourceAdapter("mixed", new ExpandResult(List.of(resolvable, flaky), false, false));

    ToolResult<SegueService.ExpansionSummary> result = service(adapter).expandEntity("Q1", 10);

    assertThat(result.outcome()).isEqualTo(ToolResult.Outcome.PARTIAL);
    assertThat(result.payload().skippedNeighbors()).isEqualTo(1);
    assertThat(result.payload().edgesAdded()).isEqualTo(1);
    assertThat(graph.node("Q3")).isEmpty();
  }

  @Test
  @DisplayName(
      "one unresolvable neighbour named by two assertions is one skipped neighbour, not two")
  void expandEntityCountsDistinctUnresolvableNeighbours() {
    // The multigraph shape this project treats as a design property rather than an edge case:
    // Nick Cave both wrote and scored The Proposition, so two assertions name one pair of nodes.
    // When that far end cannot be resolved, exactly one entity was lost. `skippedNeighbors` and
    // the sentence it is rendered into both promise a count of neighbours, so counting the
    // assertions instead would tell a calling model that twice as much went missing as did.
    ingest.record(new NodeAssertion("Q1", NodeKind.PERSON, "Nick Cave", WIKIDATA));
    // Q2 is deliberately left unresolvable, and is the far end of both assertions.
    AssertionRecord wrote =
        new AssertionRecord("Q1", "Q2", "WROTE_SCREENPLAY_FOR", null, null, WIKIDATA);
    AssertionRecord scored = new AssertionRecord("Q1", "Q2", "COMPOSED_FOR", null, null, WIKIDATA);
    SourceAdapter adapter =
        new StubSourceAdapter("multigraph", new ExpandResult(List.of(wrote, scored), false, false));

    ToolResult<SegueService.ExpansionSummary> result = service(adapter).expandEntity("Q1", 10);

    assertThat(result.outcome()).isEqualTo(ToolResult.Outcome.PARTIAL);
    assertThat(result.payload().skippedNeighbors()).isEqualTo(1);
    assertThat(result.detail()).contains("1 neighbour(s) could not be resolved");
    // Both assertions were still skipped — only the counting changes, never which edges land.
    assertThat(result.payload().edgesAdded()).isZero();
    assertThat(graph.node("Q2")).isEmpty();
    // One failed resolution, not one per assertion: the failure is remembered for the call.
    assertThat(resolver.fetchCallCount()).isEqualTo(1);
  }

  @Test
  @DisplayName("nodesAdded counts the pair's node once while edgesAdded counts both assertions")
  void expandEntityCountsNodesOnceAndAssertionsSeparately() {
    // The other half of #34: whether nodesAdded and edgesAdded share the conflation
    // skippedNeighbors had. They do not, and for different reasons. nodesAdded is guarded by the
    // graph.node(neighbor).isEmpty() re-read — the first assertion records Q2, so the second one
    // finds it already present and cannot increment again. edgesAdded is per assertion on
    // purpose: two claims about one pair of nodes are two claims, and merging them into one edge
    // is GraphStore.record's job downstream, not a number this summary should pre-empt.
    ingest.record(new NodeAssertion("Q1", NodeKind.PERSON, "Nick Cave", WIKIDATA));
    resolver.withEntity(new NodeAssertion("Q2", NodeKind.WORK, "The Proposition", WIKIDATA));
    AssertionRecord wrote =
        new AssertionRecord("Q1", "Q2", "WROTE_SCREENPLAY_FOR", null, null, WIKIDATA);
    AssertionRecord scored = new AssertionRecord("Q1", "Q2", "COMPOSED_FOR", null, null, WIKIDATA);
    SourceAdapter adapter =
        new StubSourceAdapter("multigraph", new ExpandResult(List.of(wrote, scored), false, false));

    ToolResult<SegueService.ExpansionSummary> result = service(adapter).expandEntity("Q1", 10);

    assertThat(result.outcome()).isEqualTo(ToolResult.Outcome.OK);
    assertThat(result.payload().nodesAdded()).isEqualTo(1);
    assertThat(result.payload().edgesAdded()).isEqualTo(2);
    assertThat(result.payload().skippedNeighbors()).isZero();
    assertThat(resolver.fetchCallCount()).isEqualTo(1);
    assertThat(graph.edges("Q1")).hasSize(2);
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

    assertThat(result.outcome()).isEqualTo(ToolResult.Outcome.OK);
    assertThat(result.payload().edgesAdded()).isEqualTo(matching.size());
    assertThat(result.payload().skippedNeighbors()).isZero();
    assertThat(result.payload().truncated()).isFalse();
    assertThat(result.payload().sourceUnavailable()).isFalse();
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
  @DisplayName("expandEntity uses the identity an adapter supplied instead of fetching it again")
  void expandEntityUsesInlineNeighbours() {
    // ADR 36. Wikidata's reverse lookup already knows each neighbour's label and kind, because
    // one SPARQL query returns them alongside the backlinks. Fetching them again would undo the
    // saving: expanding Nick Cave finds seventy-odd works, and a round trip each is the cost
    // that made the reverse lookup look unaffordable in the first place.
    ingest.record(new NodeAssertion("Q1", NodeKind.PERSON, "Nick Cave", WIKIDATA));
    NodeAssertion inline = new NodeAssertion("Q2", NodeKind.WORK, "The Proposition", WIKIDATA);
    AssertionRecord edge = new AssertionRecord("Q1", "Q2", "COMPOSED_FOR", null, null, WIKIDATA);
    SourceAdapter adapter =
        new StubSourceAdapter(
            "inline", new ExpandResult(List.of(edge), List.of(inline), false, false));

    ToolResult<SegueService.ExpansionSummary> result = service(adapter).expandEntity("Q1", 10);

    assertThat(result.outcome()).isEqualTo(ToolResult.Outcome.OK);
    assertThat(resolver.fetchCallCount()).isZero();
    assertThat(result.payload().nodesAdded()).isEqualTo(1);
    assertThat(result.payload().edgesAdded()).isEqualTo(1);
    assertThat(graph.node("Q2")).contains(inline.toNode());
  }

  @Test
  @DisplayName("a neighbour the adapter did not describe still falls back to a fetch")
  void expandEntityFallsBackToFetchForUndescribedNeighbours() {
    // The port does not oblige an adapter to know anything about the far end (see
    // ExpandResult), so the inline map is an optimisation, never a replacement. An adapter that
    // describes some of its neighbours and not others must not silently lose the rest.
    ingest.record(new NodeAssertion("Q1", NodeKind.PERSON, "Nick Cave", WIKIDATA));
    NodeAssertion inline = new NodeAssertion("Q2", NodeKind.WORK, "The Proposition", WIKIDATA);
    resolver.withEntity(new NodeAssertion("Q3", NodeKind.GROUP, "Bad Seeds", WIKIDATA));
    AssertionRecord described =
        new AssertionRecord("Q1", "Q2", "COMPOSED_FOR", null, null, WIKIDATA);
    AssertionRecord undescribed =
        new AssertionRecord("Q1", "Q3", "MEMBER_OF", null, null, WIKIDATA);
    SourceAdapter adapter =
        new StubSourceAdapter(
            "partly-inline",
            new ExpandResult(List.of(described, undescribed), List.of(inline), false, false));

    ToolResult<SegueService.ExpansionSummary> result = service(adapter).expandEntity("Q1", 10);

    assertThat(result.outcome()).isEqualTo(ToolResult.Outcome.OK);
    assertThat(resolver.fetchCallCount()).isEqualTo(1);
    assertThat(result.payload().nodesAdded()).isEqualTo(2);
    assertThat(graph.node("Q2")).isPresent();
    assertThat(graph.node("Q3")).isPresent();
  }

  @Test
  @DisplayName("an inline neighbour the graph already holds is not recorded a second time")
  void expandEntityDoesNotRerecordKnownInlineNeighbours() {
    ingest.record(new NodeAssertion("Q1", NodeKind.PERSON, "Nick Cave", WIKIDATA));
    ingest.record(new NodeAssertion("Q2", NodeKind.WORK, "The Proposition", WIKIDATA));
    NodeAssertion inline = new NodeAssertion("Q2", NodeKind.WORK, "The Proposition", WIKIDATA);
    AssertionRecord edge = new AssertionRecord("Q1", "Q2", "COMPOSED_FOR", null, null, WIKIDATA);
    SourceAdapter adapter =
        new StubSourceAdapter(
            "inline", new ExpandResult(List.of(edge), List.of(inline), false, false));

    ToolResult<SegueService.ExpansionSummary> result = service(adapter).expandEntity("Q1", 10);

    assertThat(result.payload().nodesAdded()).isZero();
    assertThat(result.payload().edgesAdded()).isEqualTo(1);
  }

  @Test
  @DisplayName("expandEntity does not call the resolver when every neighbour is already known")
  void expandEntityDoesNotResolveKnownNeighbours() {
    Fixture.seed(graph);

    ToolResult<SegueService.ExpansionSummary> result =
        service(new FixtureSourceAdapter()).expandEntity(Fixture.CAVE, 200);

    assertThat(result.outcome()).isEqualTo(ToolResult.Outcome.OK);
    assertThat(resolver.fetchCallCount()).isZero();
    assertThat(result.payload().nodesAdded()).isZero();
  }

  // ---- getEntity ----------------------------------------------------------

  @Test
  @DisplayName("getEntity groups neighbours by edge type")
  void getEntityGroupsByType() {
    Fixture.seed(graph);

    ToolResult<EntityView> result = service().getEntity(Fixture.CAVE);

    assertThat(result.outcome()).isEqualTo(ToolResult.Outcome.OK);
    EntityView view = result.payload();
    assertThat(view.node().qid()).isEqualTo(Fixture.CAVE);
    Map<String, List<String>> byQid = new HashMap<>();
    view.neighborsByType()
        .forEach(
            group ->
                byQid.put(
                    group.typeCode(), group.neighbors().stream().map(NodeView::qid).toList()));

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
    ToolResult<EntityView> result = service().getEntity("Q404");

    assertThat(result.outcome()).isEqualTo(ToolResult.Outcome.ERROR);
    assertThat(result.detail()).contains("Q404");
  }

  // ---- findPaths ----------------------------------------------------------

  @Test
  @DisplayName("findPaths ranks routes most-trustworthy-first, never raw")
  void findPathsReturnsRankedOrder() {
    Fixture.seed(graph);

    ToolResult<List<PathView>> result = service().findPaths(Fixture.CAVE, Fixture.MCCARTHY, 4);

    assertThat(result.outcome()).isEqualTo(ToolResult.Outcome.OK);
    List<PathView> ranked = result.payload();
    assertThat(ranked).isNotEmpty();
    // ADR 31: the fully-sourced three-hop route through The Road outranks the one-hop
    // model-generated shortcut, even though the shortcut is shorter.
    PathView top = ranked.get(0);
    assertThat(top.hops()).hasSize(3);
    double weakest =
        top.hops().stream().mapToDouble(h -> maxConfidence(h.edge())).min().orElseThrow();
    assertThat(weakest).isEqualTo(1.0);
    boolean hasShortcut = ranked.stream().anyMatch(p -> p.hops().size() == 1);
    assertThat(hasShortcut).isTrue();
    assertThat(ranked.indexOf(top))
        .isLessThan(
            ranked.stream()
                .filter(p -> p.hops().size() == 1)
                .findFirst()
                .map(ranked::indexOf)
                .orElseThrow());
  }

  private static double maxConfidence(EdgeView edge) {
    return edge.sources().stream().mapToDouble(ProvenanceView::confidence).max().orElse(0.0);
  }

  @Test
  @DisplayName("findPaths on a pair with no route returns ok with an empty payload")
  void findPathsNoRouteReturnsEmptyOk() {
    ingest.record(new NodeAssertion("Q1", NodeKind.PERSON, "Alone", WIKIDATA));
    ingest.record(new NodeAssertion("Q2", NodeKind.PERSON, "Also Alone", WIKIDATA));

    ToolResult<List<PathView>> result = service().findPaths("Q1", "Q2", 3);

    assertThat(result.outcome()).isEqualTo(ToolResult.Outcome.OK);
    assertThat(result.payload()).isEmpty();
  }

  @Test
  @DisplayName("findPaths on an unadded 'from' entity returns an error, not ok-with-nothing")
  void findPathsUnknownFromReturnsError() {
    ingest.record(new NodeAssertion("Q2", NodeKind.PERSON, "Known", WIKIDATA));

    ToolResult<List<PathView>> result = service().findPaths("Q1", "Q2", 3);

    assertThat(result.outcome()).isEqualTo(ToolResult.Outcome.ERROR);
    assertThat(result.detail()).contains("Q1");
    assertThat(result.payload()).isNull();
  }

  @Test
  @DisplayName("findPaths on an unadded 'to' entity returns an error, not ok-with-nothing")
  void findPathsUnknownToReturnsError() {
    ingest.record(new NodeAssertion("Q1", NodeKind.PERSON, "Known", WIKIDATA));

    ToolResult<List<PathView>> result = service().findPaths("Q1", "Q2", 3);

    assertThat(result.outcome()).isEqualTo(ToolResult.Outcome.ERROR);
    assertThat(result.detail()).contains("Q2");
  }

  // ---- test doubles ---------------------------------------------------------

  private static final class StubEntityResolver implements EntityResolver {
    private final Map<String, NodeAssertion> byQid = new HashMap<>();
    private final Map<String, RuntimeException> fetchFailuresByQid = new HashMap<>();
    private List<Candidate> searchResults = List.of();
    private RuntimeException searchFailure;
    private RuntimeException fetchFailure;
    private int fetchCalls;

    @Override
    public String id() {
      return "stub";
    }

    @Override
    public List<Candidate> search(String query, NodeKind kind, int limit) {
      if (searchFailure != null) {
        throw searchFailure;
      }
      return searchResults;
    }

    @Override
    public Optional<NodeAssertion> fetch(String qid) {
      fetchCalls++;
      RuntimeException perQidFailure = fetchFailuresByQid.get(qid);
      if (perQidFailure != null) {
        throw perQidFailure;
      }
      if (fetchFailure != null) {
        throw fetchFailure;
      }
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

    StubEntityResolver searchThrows(RuntimeException failure) {
      this.searchFailure = failure;
      return this;
    }

    StubEntityResolver fetchThrows(RuntimeException failure) {
      this.fetchFailure = failure;
      return this;
    }

    StubEntityResolver fetchThrowsFor(String qid, RuntimeException failure) {
      fetchFailuresByQid.put(qid, failure);
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
