package com.robsartin.segue.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.segue.domain.AffinityRecord;
import com.robsartin.segue.domain.AssertionRecord;
import com.robsartin.segue.domain.Candidate;
import com.robsartin.segue.domain.ExpansionBounds;
import com.robsartin.segue.domain.LocalEntity;
import com.robsartin.segue.domain.LoggedAssertion;
import com.robsartin.segue.domain.NodeAssertion;
import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.domain.NodeRecord;
import com.robsartin.segue.domain.PathRanking;
import com.robsartin.segue.domain.Provenance;
import com.robsartin.segue.fixture.Fixture;
import com.robsartin.segue.fixture.FixtureSourceAdapter;
import com.robsartin.segue.ingest.IngestService;
import com.robsartin.segue.port.AffinityStore;
import com.robsartin.segue.port.AssertionLog;
import com.robsartin.segue.port.EntityResolver;
import com.robsartin.segue.port.ExpandContext;
import com.robsartin.segue.port.ExpandResult;
import com.robsartin.segue.port.GraphStore;
import com.robsartin.segue.port.IdentityMerge;
import com.robsartin.segue.port.SourceAdapter;
import com.robsartin.segue.port.SourceAdapters;
import com.robsartin.segue.sqlite.SqliteAffinityStore;
import com.robsartin.segue.sqlite.SqliteAssertionLog;
import com.robsartin.segue.tinker.TinkerGraphStore;
import com.robsartin.segue.wikidata.WikidataUnavailableException;
import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The facade every MCP tool calls. Runs entirely against a fixture-backed adapter and an in-memory
 * SQLite log — no network reaches these tests.
 *
 * <p>Every rating and note in the affinity section below is invented. ADR 33 (as amended by issue
 * #37) names a fixture written from real ratings as one of the few ways this public repository
 * could leak the only personal data segue holds.
 */
class SegueServiceTest {

  /**
   * Wikibase's item-id grammar, read reflectively from {@code FixtureQidsDenoteNothingTest} - the
   * one place this repository writes it down (issue #171). Three loops below mint qids at runtime
   * from a bare {@code "Q"} and a loop counter, sites no source scan can see, so this is the only
   * oracle available to confirm the minted ids are unallocatable.
   */
  private static final Pattern WIKIBASE_ITEM_ID = wikibaseItemIdGrammar();

  private static Pattern wikibaseItemIdGrammar() {
    try {
      Field field =
          Class.forName("com.robsartin.segue.fixture.FixtureQidsDenoteNothingTest")
              .getDeclaredField("WIKIBASE_ITEM_ID");
      field.setAccessible(true);
      return (Pattern) field.get(null);
    } catch (ReflectiveOperationException e) {
      throw new AssertionError("cannot read FixtureQidsDenoteNothingTest.WIKIBASE_ITEM_ID", e);
    }
  }

  private static void assertMintedUnallocatable(String qid) {
    assertThat(WIKIBASE_ITEM_ID.matcher(qid).matches())
        .as("%s must be unallocatable (issue #171)", qid)
        .isFalse();
  }

  private static final Provenance WIKIDATA =
      new Provenance("wikidata", "S-1", Instant.parse("2026-08-24T09:00:00Z"), 1.0);

  /** Invented, like every rating and note in this file — see the class Javadoc. */
  private static final Instant RATED_AT = Instant.parse("2026-08-25T12:00:00Z");

  private static final Instant RE_RATED_AT = Instant.parse("2026-09-01T08:30:00Z");

  /** A local entity of the owner's, in the two-leading-zero band issue #141 settled. */
  private static final String MINTED = "Q00900042";

  private static final Instant MINTED_AT = Instant.parse("2026-08-31T10:00:00Z");

  private AssertionLog log;
  private GraphStore graph;
  private IngestService ingest;
  private StubEntityResolver resolver;
  private AffinityStore affinity;
  private SettableClock clock;

  @BeforeEach
  void setUp() {
    log = SqliteAssertionLog.inMemory();
    graph = new TinkerGraphStore();
    ingest = new IngestService(log, graph, IdentityMerge.NONE);
    resolver = new StubEntityResolver();
    affinity = SqliteAffinityStore.inMemory();
    clock = new SettableClock(RATED_AT);
  }

  @AfterEach
  void tearDown() {
    affinity.close();
    graph.close();
    log.close();
  }

  private SegueService service(SourceAdapter... adapters) {
    return new SegueService(
        resolver, graph, ingest, new SourceAdapters(List.of(adapters)), affinity, clock);
  }

  // ---- search -------------------------------------------------------------

  @Test
  @DisplayName("search delegates to the resolver and writes nothing")
  void searchWritesNothing() {
    Candidate candidate = new Candidate("Q01", "Nick Cave", "musician", NodeKind.PERSON);
    resolver.withSearchResults(List.of(candidate));

    ToolResult<List<CandidateView>> result = service().search("nick cave", null, 5);

    assertThat(result.outcome()).isEqualTo(ToolResult.Outcome.OK);
    assertThat(result.payload())
        .containsExactly(new CandidateView("Q01", "Nick Cave", "musician", NodeKind.PERSON));
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
    NodeAssertion assertion = new NodeAssertion("Q01", NodeKind.PERSON, "Nick Cave", WIKIDATA);
    resolver.withEntity(assertion);

    ToolResult<NodeView> first = service().addEntity("Q01");
    ToolResult<NodeView> second = service().addEntity("Q01");

    assertThat(first.outcome()).isEqualTo(ToolResult.Outcome.OK);
    assertThat(second.outcome()).isEqualTo(ToolResult.Outcome.OK);
    NodeView expected = new NodeView("Q01", NodeKind.PERSON, "Nick Cave");
    assertThat(first.payload()).isEqualTo(expected);
    assertThat(second.payload()).isEqualTo(expected);
    assertThat(graph.node("Q01")).contains(assertion.toNode());
  }

  @Test
  @DisplayName("addEntity on an unknown qid returns an error naming the qid, not an exception")
  void addEntityUnknownQidReturnsError() {
    ToolResult<NodeView> result = service().addEntity("Q0404");

    assertThat(result.outcome()).isEqualTo(ToolResult.Outcome.ERROR);
    assertThat(result.detail()).contains("Q0404");
    assertThat(graph.node("Q0404")).isEmpty();
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

    ToolResult<NodeView> result = service().addEntity("Q01");

    assertThat(result.outcome()).isEqualTo(ToolResult.Outcome.ERROR);
    assertThat(result.detail()).containsIgnoringCase("unavailable");
  }

  // ---- expandEntity -----------------------------------------------------

  @Test
  @DisplayName("expandEntity on an unknown seed returns an error, not an exception")
  void expandEntityUnknownSeedReturnsError() {
    ToolResult<SegueService.ExpansionSummary> result = service().expandEntity("Q0999999", 10);

    assertThat(result.outcome()).isEqualTo(ToolResult.Outcome.ERROR);
    assertThat(result.detail()).contains("Q0999999");
  }

  @Test
  @DisplayName("should refuse with no source to expand from when the seed is a local entity")
  void shouldRefuseWithNoSourceToExpandFromWhenTheSeedIsALocalEntity() {
    // #92: no source knows a local entity, and ADR 56 has just finished separating the two
    // meanings an empty ExpandResult already carries — "found nothing" and "the source was
    // unavailable". A third meaning would rebuild the defect ADR 56 fixed, so the refusal is
    // said out loud instead. The adapter here returns the "found nothing" shape: without the
    // refusal this call succeeds with a truthful-looking "0 edge(s), 0 new node(s)".
    ingest.record(LocalEntity.minted(MINTED, NodeKind.WORK, "a book no source indexes", MINTED_AT));
    SourceAdapter findsNothing = new StubSourceAdapter("wikidata", ExpandResult.of(List.of()));

    ToolResult<SegueService.ExpansionSummary> result =
        service(findsNothing).expandEntity(MINTED, 10);

    assertThat(result.outcome()).isEqualTo(ToolResult.Outcome.ERROR);
    assertThat(result.detail()).contains(MINTED).contains("no source to expand from");
    // Distinctly: not the shortfall vocabulary the other two meanings arrive under.
    assertThat(result.detail()).doesNotContainIgnoringCase("unavailable");
    assertThat(result.payload()).isNull();
  }

  @Test
  @DisplayName("expandEntity reports partial when a source is unavailable")
  void expandEntityReportsPartialWhenSourceUnavailable() {
    ingest.record(new NodeAssertion("Q01", NodeKind.PERSON, "Nick Cave", WIKIDATA));
    SourceAdapter unavailable = new StubSourceAdapter("flaky", ExpandResult.unavailable());

    ToolResult<SegueService.ExpansionSummary> result = service(unavailable).expandEntity("Q01", 10);

    assertThat(result.outcome()).isEqualTo(ToolResult.Outcome.PARTIAL);
    assertThat(result.detail()).containsIgnoringCase("unavailable");
    assertThat(result.payload().sourceUnavailable()).isTrue();
  }

  @Test
  @DisplayName(
      "should name the unavailable source and not the healthy one when wikidata is down"
          + " (issue #148)")
  void shouldNameTheUnavailableSourceAndNotTheHealthyOneWhenWikidataIsDown() {
    ingest.record(new NodeAssertion("Q01", NodeKind.PERSON, "Nick Cave", WIKIDATA));
    ingest.record(new NodeAssertion("Q02", NodeKind.GROUP, "Bad Seeds", WIKIDATA));
    AssertionRecord edge = new AssertionRecord("Q01", "Q02", "MEMBER_OF", null, null, WIKIDATA);

    ToolResult<SegueService.ExpansionSummary> result =
        service(
                new StubSourceAdapter("wikidata", ExpandResult.unavailable()),
                new StubSourceAdapter("musicbrainz", ExpandResult.of(List.of(edge))))
            .expandEntity("Q01", 10);

    assertThat(result.outcome()).isEqualTo(ToolResult.Outcome.PARTIAL);
    assertThat(result.detail()).contains("wikidata").doesNotContain("musicbrainz");
  }

  @Test
  @DisplayName(
      "should name the unavailable source and not the healthy one when musicbrainz is down"
          + " (issue #148)")
  void shouldNameTheUnavailableSourceAndNotTheHealthyOneWhenMusicbrainzIsDown() {
    ingest.record(new NodeAssertion("Q01", NodeKind.PERSON, "Nick Cave", WIKIDATA));
    ingest.record(new NodeAssertion("Q02", NodeKind.GROUP, "Bad Seeds", WIKIDATA));
    AssertionRecord edge = new AssertionRecord("Q01", "Q02", "MEMBER_OF", null, null, WIKIDATA);

    ToolResult<SegueService.ExpansionSummary> result =
        service(
                new StubSourceAdapter("wikidata", ExpandResult.of(List.of(edge))),
                new StubSourceAdapter("musicbrainz", ExpandResult.unavailable()))
            .expandEntity("Q01", 10);

    assertThat(result.outcome()).isEqualTo(ToolResult.Outcome.PARTIAL);
    assertThat(result.detail()).contains("musicbrainz").doesNotContain("wikidata");
  }

  @Test
  @DisplayName("expandEntity reports partial when the result was truncated")
  void expandEntityReportsPartialWhenTruncated() {
    ingest.record(new NodeAssertion("Q01", NodeKind.PERSON, "Nick Cave", WIKIDATA));
    ingest.record(new NodeAssertion("Q02", NodeKind.GROUP, "Bad Seeds", WIKIDATA));
    AssertionRecord edge = new AssertionRecord("Q01", "Q02", "MEMBER_OF", null, null, WIKIDATA);
    SourceAdapter truncating =
        new StubSourceAdapter("cut-short", new ExpandResult(List.of(edge), false, true));

    ToolResult<SegueService.ExpansionSummary> result = service(truncating).expandEntity("Q01", 10);

    assertThat(result.outcome()).isEqualTo(ToolResult.Outcome.PARTIAL);
    assertThat(result.detail()).containsIgnoringCase("truncat");
    assertThat(result.payload().truncated()).isTrue();
  }

  @Test
  @DisplayName("should name the source that truncated and not the one that did not (issue #148)")
  void shouldNameTheSourceThatTruncatedAndNotTheOneThatDidNot() {
    ingest.record(new NodeAssertion("Q01", NodeKind.PERSON, "Nick Cave", WIKIDATA));
    ingest.record(new NodeAssertion("Q02", NodeKind.GROUP, "Bad Seeds", WIKIDATA));
    AssertionRecord edge = new AssertionRecord("Q01", "Q02", "MEMBER_OF", null, null, WIKIDATA);

    ToolResult<SegueService.ExpansionSummary> result =
        service(
                new StubSourceAdapter("wikidata", ExpandResult.of(List.of(edge))),
                new StubSourceAdapter("musicbrainz", new ExpandResult(List.of(edge), false, true)))
            .expandEntity("Q01", 10);

    assertThat(result.outcome()).isEqualTo(ToolResult.Outcome.PARTIAL);
    assertThat(result.detail()).containsIgnoringCase("truncat");
    assertThat(result.detail()).contains("musicbrainz").doesNotContain("wikidata");
  }

  @Test
  @DisplayName(
      "should blame no source when it was the shared bound that cut the concatenation"
          + " (issue #148, GAP 3)")
  void shouldBlameNoSourceWhenItWasTheSharedBoundThatCutTheConcatenation() {
    ingest.record(new NodeAssertion("Q01", NodeKind.PERSON, "Nick Cave", WIKIDATA));
    List<AssertionRecord> assertions = new ArrayList<>();
    List<NodeAssertion> neighbors = new ArrayList<>();
    for (int i = 0; i < 3; i++) {
      String qid = "Q0" + (700 + i);
      assertMintedUnallocatable(qid);
      assertions.add(new AssertionRecord("Q01", qid, "MEMBER_OF", null, null, WIKIDATA));
      neighbors.add(new NodeAssertion(qid, NodeKind.GROUP, "Group " + i, WIKIDATA));
    }
    // Neither adapter reports truncating: each returned everything it had. What overran the bound
    // is the concatenation, and no single adapter made that cut.
    ExpandResult whole = new ExpandResult(assertions, neighbors, false, false);

    ToolResult<SegueService.ExpansionSummary> result =
        service(
                new StubSourceAdapter("wikidata", whole),
                new StubSourceAdapter("musicbrainz", whole))
            .expandEntity("Q01", 4);

    assertThat(result.outcome()).isEqualTo(ToolResult.Outcome.PARTIAL);
    assertThat(result.payload().truncated()).isTrue();
    // Asserting the phrase and not only the absence of a name: "names no source" was already true
    // of the aggregate message this issue replaces, so on its own it would have passed before the
    // change and proved nothing.
    assertThat(result.detail())
        .contains("the combined result was truncated at the bound of 4")
        .doesNotContain("wikidata")
        .doesNotContain("musicbrainz");
  }

  @Test
  @DisplayName(
      "a CONCEPT expansion past the ceiling is capped and reported partial, naming the ceiling"
          + " (issue #112)")
  void expandEntityCapsAConceptAtTheCeiling() {
    ingest.record(new NodeAssertion("Q01", NodeKind.CONCEPT, "Broad Concept", WIKIDATA));
    int flood = ExpansionBounds.CONCEPT_CEILING + 10;
    List<AssertionRecord> assertions = new ArrayList<>();
    List<NodeAssertion> neighbors = new ArrayList<>();
    for (int i = 0; i < flood; i++) {
      String qid = "Q0" + (100 + i);
      assertMintedUnallocatable(qid);
      assertions.add(new AssertionRecord("Q01", qid, "ABOUTNESS", null, null, WIKIDATA));
      neighbors.add(new NodeAssertion(qid, NodeKind.WORK, "Work " + i, WIKIDATA));
    }
    SourceAdapter flooding =
        new StubSourceAdapter("flood", new ExpandResult(assertions, neighbors, false, false));

    // The request asks for far more than the ceiling — exactly the call this rule exists to stop.
    ToolResult<SegueService.ExpansionSummary> result = service(flooding).expandEntity("Q01", 200);

    assertThat(result.outcome()).isEqualTo(ToolResult.Outcome.PARTIAL);
    assertThat(result.detail()).containsIgnoringCase("truncat");
    assertThat(result.detail()).contains(String.valueOf(ExpansionBounds.CONCEPT_CEILING));
    assertThat(result.payload().truncated()).isTrue();
    assertThat(result.payload().edgesAdded()).isEqualTo(ExpansionBounds.CONCEPT_CEILING);
    assertThat(graph.edges("Q01")).hasSize(ExpansionBounds.CONCEPT_CEILING);
  }

  @Test
  @DisplayName(
      "a PERSON expansion past the CONCEPT ceiling is not capped, so the legitimate path is"
          + " unchanged (issue #112)")
  void expandEntityDoesNotCapAPerson() {
    // The guard reads the SEED's kind. Nothing else on this branch would notice if it stopped
    // doing that: hard-code SegueService's lookup to NodeKind.CONCEPT and every other test still
    // passes, while every PERSON expansion silently drops to 25.
    ingest.record(new NodeAssertion("Q01", NodeKind.PERSON, "Well Connected Person", WIKIDATA));
    int pastTheCeiling = ExpansionBounds.CONCEPT_CEILING + 10;
    List<AssertionRecord> assertions = new ArrayList<>();
    List<NodeAssertion> neighbors = new ArrayList<>();
    for (int i = 0; i < pastTheCeiling; i++) {
      String qid = "Q0" + (100 + i);
      assertMintedUnallocatable(qid);
      assertions.add(new AssertionRecord("Q01", qid, "DIRECTED", null, null, WIKIDATA));
      neighbors.add(new NodeAssertion(qid, NodeKind.WORK, "Work " + i, WIKIDATA));
    }
    SourceAdapter generous =
        new StubSourceAdapter("generous", new ExpandResult(assertions, neighbors, false, false));

    ToolResult<SegueService.ExpansionSummary> result = service(generous).expandEntity("Q01", 200);

    assertThat(result.outcome()).isEqualTo(ToolResult.Outcome.OK);
    assertThat(result.payload().truncated()).isFalse();
    assertThat(result.payload().edgesAdded()).isEqualTo(pastTheCeiling);
    assertThat(graph.edges("Q01")).hasSize(pastTheCeiling);
  }

  @Test
  @DisplayName("expandEntity reports partial and skips an edge whose neighbour cannot be resolved")
  void expandEntitySkipsUnreachableNeighbour() {
    ingest.record(new NodeAssertion("Q01", NodeKind.PERSON, "Nick Cave", WIKIDATA));
    resolver.withEntity(new NodeAssertion("Q02", NodeKind.GROUP, "Bad Seeds", WIKIDATA));
    // Q03 is deliberately left unresolvable.
    AssertionRecord resolvable =
        new AssertionRecord("Q01", "Q02", "MEMBER_OF", null, null, WIKIDATA);
    AssertionRecord unresolvable =
        new AssertionRecord("Q01", "Q03", "MEMBER_OF", null, null, WIKIDATA);
    SourceAdapter adapter =
        new StubSourceAdapter(
            "mixed", new ExpandResult(List.of(resolvable, unresolvable), false, false));

    ToolResult<SegueService.ExpansionSummary> result = service(adapter).expandEntity("Q01", 10);

    assertThat(result.outcome()).isEqualTo(ToolResult.Outcome.PARTIAL);
    assertThat(result.detail()).containsIgnoringCase("skip");
    assertThat(result.payload().skippedNeighbors()).isEqualTo(1);
    assertThat(result.payload().edgesAdded()).isEqualTo(1);
    assertThat(graph.node("Q03")).isEmpty();
    assertThat(graph.node("Q02")).isPresent();
  }

  @Test
  @DisplayName(
      "expandEntity treats a neighbour fetch that throws like one that resolved to nothing, and"
          + " completes as partial rather than aborting the whole expansion")
  void expandEntitySkipsNeighbourWhoseResolutionThrows() {
    ingest.record(new NodeAssertion("Q01", NodeKind.PERSON, "Nick Cave", WIKIDATA));
    resolver.withEntity(new NodeAssertion("Q02", NodeKind.GROUP, "Bad Seeds", WIKIDATA));
    resolver.fetchThrowsFor("Q03", new WikidataUnavailableException("timed out"));
    AssertionRecord resolvable =
        new AssertionRecord("Q01", "Q02", "MEMBER_OF", null, null, WIKIDATA);
    AssertionRecord flaky = new AssertionRecord("Q01", "Q03", "MEMBER_OF", null, null, WIKIDATA);
    SourceAdapter adapter =
        new StubSourceAdapter("mixed", new ExpandResult(List.of(resolvable, flaky), false, false));

    ToolResult<SegueService.ExpansionSummary> result = service(adapter).expandEntity("Q01", 10);

    assertThat(result.outcome()).isEqualTo(ToolResult.Outcome.PARTIAL);
    assertThat(result.payload().skippedNeighbors()).isEqualTo(1);
    assertThat(result.payload().edgesAdded()).isEqualTo(1);
    assertThat(graph.node("Q03")).isEmpty();
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
    ingest.record(new NodeAssertion("Q01", NodeKind.PERSON, "Nick Cave", WIKIDATA));
    // Q02 is deliberately left unresolvable, and is the far end of both assertions.
    AssertionRecord wrote =
        new AssertionRecord("Q01", "Q02", "WROTE_SCREENPLAY_FOR", null, null, WIKIDATA);
    AssertionRecord scored =
        new AssertionRecord("Q01", "Q02", "COMPOSED_FOR", null, null, WIKIDATA);
    SourceAdapter adapter =
        new StubSourceAdapter("multigraph", new ExpandResult(List.of(wrote, scored), false, false));

    ToolResult<SegueService.ExpansionSummary> result = service(adapter).expandEntity("Q01", 10);

    assertThat(result.outcome()).isEqualTo(ToolResult.Outcome.PARTIAL);
    assertThat(result.payload().skippedNeighbors()).isEqualTo(1);
    assertThat(result.detail()).contains("1 neighbour(s) could not be resolved");
    // Both assertions were still skipped — only the counting changes, never which edges land.
    assertThat(result.payload().edgesAdded()).isZero();
    assertThat(graph.node("Q02")).isEmpty();
    // One failed resolution, not one per assertion: the failure is remembered for the call.
    assertThat(resolver.fetchCallCount()).isEqualTo(1);
  }

  @Test
  @DisplayName("nodesAdded counts the pair's node once while edgesAdded counts both assertions")
  void expandEntityCountsNodesOnceAndAssertionsSeparately() {
    // The other half of #34: whether nodesAdded and edgesAdded share the conflation
    // skippedNeighbors had. They do not, and for different reasons. nodesAdded is guarded by the
    // graph.node(neighbor).isEmpty() re-read — the first assertion records Q02, so the second one
    // finds it already present and cannot increment again. edgesAdded is per assertion on
    // purpose: two claims about one pair of nodes are two claims, and merging them into one edge
    // is GraphStore.record's job downstream, not a number this summary should pre-empt.
    ingest.record(new NodeAssertion("Q01", NodeKind.PERSON, "Nick Cave", WIKIDATA));
    resolver.withEntity(new NodeAssertion("Q02", NodeKind.WORK, "The Proposition", WIKIDATA));
    AssertionRecord wrote =
        new AssertionRecord("Q01", "Q02", "WROTE_SCREENPLAY_FOR", null, null, WIKIDATA);
    AssertionRecord scored =
        new AssertionRecord("Q01", "Q02", "COMPOSED_FOR", null, null, WIKIDATA);
    SourceAdapter adapter =
        new StubSourceAdapter("multigraph", new ExpandResult(List.of(wrote, scored), false, false));

    ToolResult<SegueService.ExpansionSummary> result = service(adapter).expandEntity("Q01", 10);

    assertThat(result.outcome()).isEqualTo(ToolResult.Outcome.OK);
    assertThat(result.payload().nodesAdded()).isEqualTo(1);
    assertThat(result.payload().edgesAdded()).isEqualTo(2);
    assertThat(result.payload().skippedNeighbors()).isZero();
    assertThat(resolver.fetchCallCount()).isEqualTo(1);
    assertThat(graph.edges("Q01")).hasSize(2);
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
    ingest.record(new NodeAssertion("Q01", NodeKind.PERSON, "Nick Cave", WIKIDATA));
    NodeAssertion inline = new NodeAssertion("Q02", NodeKind.WORK, "The Proposition", WIKIDATA);
    AssertionRecord edge = new AssertionRecord("Q01", "Q02", "COMPOSED_FOR", null, null, WIKIDATA);
    SourceAdapter adapter =
        new StubSourceAdapter(
            "inline", new ExpandResult(List.of(edge), List.of(inline), false, false));

    ToolResult<SegueService.ExpansionSummary> result = service(adapter).expandEntity("Q01", 10);

    assertThat(result.outcome()).isEqualTo(ToolResult.Outcome.OK);
    assertThat(resolver.fetchCallCount()).isZero();
    assertThat(result.payload().nodesAdded()).isEqualTo(1);
    assertThat(result.payload().edgesAdded()).isEqualTo(1);
    assertThat(graph.node("Q02")).contains(inline.toNode());
  }

  @Test
  @DisplayName("a neighbour the adapter did not describe still falls back to a fetch")
  void expandEntityFallsBackToFetchForUndescribedNeighbours() {
    // The port does not oblige an adapter to know anything about the far end (see
    // ExpandResult), so the inline map is an optimisation, never a replacement. An adapter that
    // describes some of its neighbours and not others must not silently lose the rest.
    ingest.record(new NodeAssertion("Q01", NodeKind.PERSON, "Nick Cave", WIKIDATA));
    NodeAssertion inline = new NodeAssertion("Q02", NodeKind.WORK, "The Proposition", WIKIDATA);
    resolver.withEntity(new NodeAssertion("Q03", NodeKind.GROUP, "Bad Seeds", WIKIDATA));
    AssertionRecord described =
        new AssertionRecord("Q01", "Q02", "COMPOSED_FOR", null, null, WIKIDATA);
    AssertionRecord undescribed =
        new AssertionRecord("Q01", "Q03", "MEMBER_OF", null, null, WIKIDATA);
    SourceAdapter adapter =
        new StubSourceAdapter(
            "partly-inline",
            new ExpandResult(List.of(described, undescribed), List.of(inline), false, false));

    ToolResult<SegueService.ExpansionSummary> result = service(adapter).expandEntity("Q01", 10);

    assertThat(result.outcome()).isEqualTo(ToolResult.Outcome.OK);
    assertThat(resolver.fetchCallCount()).isEqualTo(1);
    assertThat(result.payload().nodesAdded()).isEqualTo(2);
    assertThat(graph.node("Q02")).isPresent();
    assertThat(graph.node("Q03")).isPresent();
  }

  @Test
  @DisplayName("a later expansion corrects an existing neighbour's stale kind")
  void expandEntityRefreshesExistingNeighbourIdentity() {
    // Issue #55. KindMapper's whitelist grows as it is measured against real data (issues #49
    // and #52), so a P31 that mapped to CONCEPT on the run that discovered an entity maps to
    // WORK today. Identity used to be recorded only for a neighbour the graph did not already
    // have, which froze every old node's kind at whatever the mapper said the first time and
    // left one class of entity holding two kinds depending on when it arrived. PathRanking's
    // hub rule then vetoed routes through a work stored as a concept — the opposite of what
    // issue #52 built it for.
    ingest.record(new NodeAssertion("Q01", NodeKind.PERSON, "Nick Cave", WIKIDATA));
    ingest.record(new NodeAssertion("Q02", NodeKind.CONCEPT, "The Proposition", WIKIDATA));
    NodeAssertion corrected = new NodeAssertion("Q02", NodeKind.WORK, "The Proposition", WIKIDATA);
    AssertionRecord edge = new AssertionRecord("Q01", "Q02", "COMPOSED_FOR", null, null, WIKIDATA);
    SourceAdapter adapter =
        new StubSourceAdapter(
            "inline", new ExpandResult(List.of(edge), List.of(corrected), false, false));

    ToolResult<SegueService.ExpansionSummary> result = service(adapter).expandEntity("Q01", 10);

    assertThat(result.outcome()).isEqualTo(ToolResult.Outcome.OK);
    assertThat(graph.node("Q02")).contains(corrected.toNode());
    // ADR 19: the correction is a new claim appended to the log, not an edit of the old one, so
    // a replay rebuilds the corrected graph rather than the stale one.
    assertThat(log.readAll()).contains(corrected);
    // The refresh is only free because the source already handed the identity over. An existing
    // neighbour must never cost a round trip of its own — an expansion finds seventy-odd of them.
    assertThat(resolver.fetchCallCount()).isZero();
  }

  @Test
  @DisplayName("an inline neighbour the graph already holds is refreshed but not counted as added")
  void expandEntityDoesNotCountRefreshedInlineNeighboursAsAdded() {
    // The other half of issue #55. The refresh above re-records an entity the graph already
    // had, and nodesAdded must not follow it: the number answers "how much did this expansion
    // grow the graph by", and a caller told 1 would read a corrected node as a discovered one.
    ingest.record(new NodeAssertion("Q01", NodeKind.PERSON, "Nick Cave", WIKIDATA));
    ingest.record(new NodeAssertion("Q02", NodeKind.CONCEPT, "The Proposition", WIKIDATA));
    NodeAssertion corrected = new NodeAssertion("Q02", NodeKind.WORK, "The Proposition", WIKIDATA);
    AssertionRecord edge = new AssertionRecord("Q01", "Q02", "COMPOSED_FOR", null, null, WIKIDATA);
    SourceAdapter adapter =
        new StubSourceAdapter(
            "inline", new ExpandResult(List.of(edge), List.of(corrected), false, false));

    ToolResult<SegueService.ExpansionSummary> result = service(adapter).expandEntity("Q01", 10);

    assertThat(result.payload().nodesAdded()).isZero();
    assertThat(result.payload().edgesAdded()).isEqualTo(1);
    assertThat(result.detail()).contains("0 new node(s)");
    // Refreshed all the same — the count is what stays put, not the kind.
    assertThat(graph.node("Q02").orElseThrow().kind()).isEqualTo(NodeKind.WORK);
  }

  @Test
  @DisplayName("one neighbour named by two assertions is refreshed once, not once per assertion")
  void expandEntityRefreshesEachNeighbourOnce() {
    // The multigraph shape again: Nick Cave both wrote and scored The Proposition. The graph
    // re-read used to make this impossible for free, because a neighbour recorded on the first
    // assertion was present by the second; now that the refresh fires whether or not the node
    // exists, the same identity claim would otherwise be appended to the log once per assertion
    // and replayed that many times at boot.
    ingest.record(new NodeAssertion("Q01", NodeKind.PERSON, "Nick Cave", WIKIDATA));
    ingest.record(new NodeAssertion("Q02", NodeKind.CONCEPT, "The Proposition", WIKIDATA));
    NodeAssertion corrected = new NodeAssertion("Q02", NodeKind.WORK, "The Proposition", WIKIDATA);
    AssertionRecord wrote =
        new AssertionRecord("Q01", "Q02", "WROTE_SCREENPLAY_FOR", null, null, WIKIDATA);
    AssertionRecord scored =
        new AssertionRecord("Q01", "Q02", "COMPOSED_FOR", null, null, WIKIDATA);
    SourceAdapter adapter =
        new StubSourceAdapter(
            "multigraph",
            new ExpandResult(List.of(wrote, scored), List.of(corrected), false, false));

    ToolResult<SegueService.ExpansionSummary> result = service(adapter).expandEntity("Q01", 10);

    assertThat(result.payload().edgesAdded()).isEqualTo(2);
    assertThat(graph.node("Q02").orElseThrow().kind()).isEqualTo(NodeKind.WORK);
    assertThat(log.readAll()).filteredOn(corrected::equals).hasSize(1);
  }

  @Test
  @DisplayName("an existing neighbour the adapter did not describe is left exactly as it was")
  void expandEntityLeavesUndescribedExistingNeighboursAlone() {
    // The bound on the refresh, and the reason it needs no flag: it happens only where a source
    // already volunteered the identity in the same response. Fetching identity for an existing
    // neighbour would turn every expansion into hundreds of extra round trips to correct nodes
    // nobody asked about, which is a different and much more expensive decision.
    ingest.record(new NodeAssertion("Q01", NodeKind.PERSON, "Nick Cave", WIKIDATA));
    ingest.record(new NodeAssertion("Q02", NodeKind.CONCEPT, "The Proposition", WIKIDATA));
    AssertionRecord edge = new AssertionRecord("Q01", "Q02", "COMPOSED_FOR", null, null, WIKIDATA);
    SourceAdapter adapter =
        new StubSourceAdapter("undescribed", new ExpandResult(List.of(edge), false, false));

    ToolResult<SegueService.ExpansionSummary> result = service(adapter).expandEntity("Q01", 10);

    assertThat(result.outcome()).isEqualTo(ToolResult.Outcome.OK);
    assertThat(resolver.fetchCallCount()).isZero();
    assertThat(graph.node("Q02").orElseThrow().kind()).isEqualTo(NodeKind.CONCEPT);
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
    ToolResult<EntityView> result = service().getEntity("Q0404");

    assertThat(result.outcome()).isEqualTo(ToolResult.Outcome.ERROR);
    assertThat(result.detail()).contains("Q0404");
  }

  @Test
  @DisplayName("getEntity reports no affinity for an entity that has never been rated")
  void getEntityWithoutAffinityReportsNone() {
    ingest.record(new NodeAssertion("Q01", NodeKind.PERSON, "Unrated", WIKIDATA));

    ToolResult<EntityView> result = service().getEntity("Q01");

    assertThat(result.outcome()).isEqualTo(ToolResult.Outcome.OK);
    // Null, not a zero or a default rating: "I have never said" and "I rated it lowest" are
    // different answers and a recommendation that confused them would be wrong in both
    // directions.
    assertThat(result.payload().affinity()).isNull();
  }

  @Test
  @DisplayName("getEntity surfaces the rating once the entity has been rated (ADR 39's read)")
  void getEntitySurfacesAffinity() {
    ingest.record(new NodeAssertion("Q01", NodeKind.PERSON, "Rated", WIKIDATA));
    service().noteAffinity("Q01", 4, "an invented note");

    ToolResult<EntityView> result = service().getEntity("Q01");

    assertThat(result.outcome()).isEqualTo(ToolResult.Outcome.OK);
    // The rating, and nothing else the user wrote: issue #85 moved ADR 33's line to run between
    // the score and the note rather than around the pair. NoteNeverLeavesThroughAToolTest proves
    // the same thing of the whole surface; this one records what the read path returns.
    assertThat(result.payload().affinity()).isEqualTo(new AffinityView(4, RATED_AT));
  }

  // ---- noteAffinity -------------------------------------------------------

  @Test
  @DisplayName("noteAffinity records a rating and a note against an entity in the graph")
  void noteAffinityRecordsRatingAndNote() {
    ingest.record(new NodeAssertion("Q01", NodeKind.PERSON, "Rated", WIKIDATA));

    ToolResult<AffinityView> result = service().noteAffinity("Q01", 5, "an invented note");

    assertThat(result.outcome()).isEqualTo(ToolResult.Outcome.OK);
    // Stored in full; returned as the rating alone (issue #85).
    assertThat(result.payload()).isEqualTo(new AffinityView(5, RATED_AT));
    assertThat(affinity.find("Q01"))
        .contains(new AffinityRecord("Q01", 5, "an invented note", RATED_AT));
  }

  @Test
  @DisplayName("the note is optional; a rating on its own is a complete entry")
  void noteAffinityAcceptsARatingWithNoNote() {
    ingest.record(new NodeAssertion("Q01", NodeKind.PERSON, "Rated", WIKIDATA));

    ToolResult<AffinityView> result = service().noteAffinity("Q01", 2, null);

    assertThat(result.outcome()).isEqualTo(ToolResult.Outcome.OK);
    assertThat(result.payload()).isEqualTo(new AffinityView(2, RATED_AT));
    assertThat(affinity.find("Q01")).contains(new AffinityRecord("Q01", 2, null, RATED_AT));
  }

  @Test
  @DisplayName("a blank note is stored as no note at all, not as whitespace")
  void noteAffinityTreatsABlankNoteAsAbsent() {
    ingest.record(new NodeAssertion("Q01", NodeKind.PERSON, "Rated", WIKIDATA));

    ToolResult<AffinityView> result = service().noteAffinity("Q01", 3, "   ");

    assertThat(result.outcome()).isEqualTo(ToolResult.Outcome.OK);
    // Asserted on the stored row rather than on the result: since issue #85 the wire view has no
    // note field to inspect, and "blank means absent" is a fact about what was written down.
    assertThat(affinity.find("Q01")).contains(new AffinityRecord("Q01", 3, null, RATED_AT));
  }

  @Test
  @DisplayName("re-rating overwrites in place and moves updated-at (ADR 39: no history)")
  void reRatingOverwritesAndMovesUpdatedAt() {
    ingest.record(new NodeAssertion("Q01", NodeKind.PERSON, "Rated", WIKIDATA));
    service().noteAffinity("Q01", 2, "an invented first impression");

    clock.set(RE_RATED_AT);
    ToolResult<AffinityView> second = service().noteAffinity("Q01", 5, "an invented second look");

    assertThat(second.outcome()).isEqualTo(ToolResult.Outcome.OK);
    assertThat(affinity.find("Q01"))
        .contains(new AffinityRecord("Q01", 5, "an invented second look", RE_RATED_AT));
  }

  @Test
  @DisplayName("rating an entity the graph has never seen is a readable error, not an exception")
  void noteAffinityOnAnUnknownEntityIsAnError() {
    ToolResult<AffinityView> result = service().noteAffinity("Q0404", 4, null);

    assertThat(result.outcome()).isEqualTo(ToolResult.Outcome.ERROR);
    assertThat(result.detail()).contains("Q0404").containsIgnoringCase("add it");
    assertThat(result.payload()).isNull();
    assertThat(affinity.find("Q0404")).isEmpty();
  }

  @Test
  @DisplayName("something that is not a QID is rejected before the graph is consulted (ADR 22)")
  void noteAffinityRejectsANonQid() {
    ToolResult<AffinityView> result = service().noteAffinity("that-band-from-the-radio", 4, null);

    assertThat(result.outcome()).isEqualTo(ToolResult.Outcome.ERROR);
    assertThat(result.detail()).containsIgnoringCase("not a qid");
  }

  @Test
  @DisplayName("a rating outside 1-5 is rejected at both ends, and nothing is stored")
  void noteAffinityRejectsRatingsOutsideTheScale() {
    ingest.record(new NodeAssertion("Q01", NodeKind.PERSON, "Rated", WIKIDATA));

    ToolResult<AffinityView> tooLow = service().noteAffinity("Q01", 0, null);
    ToolResult<AffinityView> tooHigh = service().noteAffinity("Q01", 6, null);

    assertThat(tooLow.outcome()).isEqualTo(ToolResult.Outcome.ERROR);
    assertThat(tooLow.detail()).contains("1 to 5");
    assertThat(tooHigh.outcome()).isEqualTo(ToolResult.Outcome.ERROR);
    assertThat(affinity.find("Q01")).isEmpty();
  }

  @Test
  @DisplayName("the rejection never echoes the rating back, because affinity is personal data")
  void noteAffinityRejectionDoesNotEchoTheRating() {
    ingest.record(new NodeAssertion("Q01", NodeKind.PERSON, "Rated", WIKIDATA));

    ToolResult<AffinityView> result = service().noteAffinity("Q01", 9, "an invented note");

    assertThat(result.detail()).doesNotContain("9").doesNotContain("an invented note");
  }

  @Test
  @DisplayName("affinity never reaches the graph or the assertion log — the ADR 33 invariant")
  void affinityNeverReachesTheGraphOrTheLog() {
    NodeAssertion node = new NodeAssertion("Q01", NodeKind.PERSON, "Rated", WIKIDATA);
    ingest.record(node);
    int logSizeBefore = log.readAll().size();

    service().noteAffinity("Q01", 5, "an invented note");

    // The log is byte-for-byte what it was: no rating assertion, no "me" source, no llm: prefix.
    assertThat(log.readAll()).containsExactly((LoggedAssertion) node);
    assertThat(log.readAll()).hasSize(logSizeBefore);
    // And the graph gained neither an edge nor a "me" node to hang one off.
    assertThat(graph.edgeCount()).isZero();
    assertThat(graph.edges("Q01")).isEmpty();
    assertThat(graph.node("Q01")).contains(node.toNode());
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
  @DisplayName("findPaths demotes a route through a hub, using degrees only the graph knows")
  void findPathsDemotesHubRoutes() {
    // Issue #52: PathRanking judges specificity from the in-graph degree of a CONCEPT
    // intermediate, and only this class can see the graph. Everything below is invented —
    // an award half the cast has collected, and one film two of them actually made together.
    ingest.record(new NodeAssertion("Q0900301", NodeKind.PERSON, "Ada Vance", WIKIDATA));
    ingest.record(new NodeAssertion("Q0900302", NodeKind.PERSON, "Bruno Kell", WIKIDATA));
    ingest.record(new NodeAssertion("Q0900303", NodeKind.CONCEPT, "Boulevard Plaque", WIKIDATA));
    ingest.record(new NodeAssertion("Q0900304", NodeKind.WORK, "The Quiet Ferry", WIKIDATA));
    // The plaque is a hub: enough other people hold one to clear HUB_DEGREE.
    for (int i = 0; i < PathRanking.HUB_DEGREE; i++) {
      String holder = "Q09004" + (10 + i);
      ingest.record(new NodeAssertion(holder, NodeKind.PERSON, "Holder " + i, WIKIDATA));
      ingest.record(edge(holder, "RECEIVED_AWARD", "Q0900303", 1.00));
    }
    ingest.record(edge("Q0900301", "RECEIVED_AWARD", "Q0900303", 1.00));
    ingest.record(edge("Q0900302", "RECEIVED_AWARD", "Q0900303", 1.00));
    // The specific route is less well evidenced, and under ADR 31 alone it lost because of it.
    ingest.record(edge("Q0900301", "ACTED_IN", "Q0900304", 0.80));
    ingest.record(edge("Q0900302", "ACTED_IN", "Q0900304", 0.80));

    ToolResult<List<PathView>> result = service().findPaths("Q0900301", "Q0900302", 2);

    assertThat(result.outcome()).isEqualTo(ToolResult.Outcome.OK);
    assertThat(result.payload()).hasSize(2);
    assertThat(result.payload().get(0).hops().get(0).to().qid()).isEqualTo("Q0900304");
  }

  @Test
  @DisplayName("findPaths demotes a route through an academy, and never one through a band")
  void findPathsDemotesRoutesThroughABodyOneIsElectedTo() {
    // Issue #66. Only this class knows the Wikidata class vocabulary the rule reads, so this is
    // where the wiring is pinned. The people and the two bodies are invented; the CLASSES are
    // real, because they are the thing under test — Q955824 is "learned society" and Q5741069
    // is "rock band", both confirmed by label and description.
    //
    // The academy is deliberately the QUIETER of the two: three members against the band's
    // eleven, which is above HUB_DEGREE. On the real graph the two populations interleave
    // exactly like this, so a rule reading degree would have demoted the band and kept the
    // academy — the wrong way round.
    ingest.record(new NodeAssertion("Q0900601", NodeKind.PERSON, "Ada Vance", WIKIDATA));
    ingest.record(new NodeAssertion("Q0900602", NodeKind.PERSON, "Bruno Kell", WIKIDATA));
    ingest.record(
        new NodeAssertion(
            "Q0900603", NodeKind.GROUP, "Northwood Academy", List.of("Q955824"), WIKIDATA));
    ingest.record(
        new NodeAssertion(
            "Q0900604", NodeKind.GROUP, "The Quiet Ferry", List.of("Q5741069"), WIKIDATA));
    ingest.record(new NodeAssertion("Q0900605", NodeKind.PERSON, "Cyd Rowe", WIKIDATA));
    ingest.record(edge("Q0900605", "MEMBER_OF", "Q0900603", 1.00));
    for (int i = 0; i < PathRanking.HUB_DEGREE; i++) {
      String player = "Q09006" + (10 + i);
      ingest.record(new NodeAssertion(player, NodeKind.PERSON, "Player " + i, WIKIDATA));
      ingest.record(edge(player, "MEMBER_OF", "Q0900604", 1.00));
    }
    // Election is the better-evidenced claim, exactly as a referenced award statement is, and
    // under confidence alone it therefore won.
    ingest.record(edge("Q0900601", "MEMBER_OF", "Q0900603", 1.00));
    ingest.record(edge("Q0900602", "MEMBER_OF", "Q0900603", 1.00));
    ingest.record(edge("Q0900601", "MEMBER_OF", "Q0900604", 0.80));
    ingest.record(edge("Q0900602", "MEMBER_OF", "Q0900604", 0.80));

    ToolResult<List<PathView>> result = service().findPaths("Q0900601", "Q0900602", 2);

    assertThat(result.outcome()).isEqualTo(ToolResult.Outcome.OK);
    assertThat(result.payload()).hasSize(2);
    assertThat(result.payload().get(0).hops().get(0).to().qid()).isEqualTo("Q0900604");
  }

  private static AssertionRecord edge(String from, String type, String to, double confidence) {
    return new AssertionRecord(
        from,
        to,
        type,
        null,
        null,
        new Provenance("wikidata", "S-" + from + "-" + to, WIKIDATA.assertedAt(), confidence));
  }

  @Test
  @DisplayName("findPaths reports partial when more routes exist than the cap returns")
  void findPathsOverTheCapReportsTruncation() {
    // Issue #65. Every entity below is invented. One pair joined by MAX_PATHS + 1 distinct
    // two-hop routes, which is one more than the ranking is allowed to return.
    int routes = PathRanking.MAX_PATHS + 1;
    ingest.record(new NodeAssertion("Q0900501", NodeKind.PERSON, "Cleo Marsh", WIKIDATA));
    ingest.record(new NodeAssertion("Q0900502", NodeKind.PERSON, "Dov Ellery", WIKIDATA));
    for (int i = 0; i < routes; i++) {
      String middle = "Q09005" + (10 + i);
      ingest.record(new NodeAssertion(middle, NodeKind.WORK, "Reel " + i, WIKIDATA));
      ingest.record(edge("Q0900501", "ACTED_IN", middle, 1.00));
      ingest.record(edge("Q0900502", "ACTED_IN", middle, 1.00));
    }

    ToolResult<List<PathView>> result = service().findPaths("Q0900501", "Q0900502", 2);

    // The cap still applies; what changes is that the caller is told it did.
    assertThat(result.payload()).hasSize(PathRanking.MAX_PATHS);
    assertThat(result.outcome()).isEqualTo(ToolResult.Outcome.PARTIAL);
    // Legible to a model: how many exist, how many came back, how many were dropped, and
    // that the kept ones are the best-ranked rather than an arbitrary slice (ADR 31).
    assertThat(result.detail())
        .isEqualTo(
            routes
                + " route(s) from Q0900501 to Q0900502, more than the cap of "
                + PathRanking.MAX_PATHS
                + ": the "
                + PathRanking.MAX_PATHS
                + " best-ranked are returned and 1 omitted");
  }

  @Test
  @DisplayName("findPaths at exactly the cap is ok, worded as before — nothing was omitted")
  void findPathsAtTheCapIsUnchanged() {
    // The boundary the truncation report must not claim: MAX_PATHS routes is a complete
    // answer that happens to fill the cap. Invented entities, as above.
    ingest.record(new NodeAssertion("Q0900601", NodeKind.PERSON, "Esme Faro", WIKIDATA));
    ingest.record(new NodeAssertion("Q0900602", NodeKind.PERSON, "Fitz Loew", WIKIDATA));
    for (int i = 0; i < PathRanking.MAX_PATHS; i++) {
      String middle = "Q09006" + (10 + i);
      ingest.record(new NodeAssertion(middle, NodeKind.WORK, "Take " + i, WIKIDATA));
      ingest.record(edge("Q0900601", "ACTED_IN", middle, 1.00));
      ingest.record(edge("Q0900602", "ACTED_IN", middle, 1.00));
    }

    ToolResult<List<PathView>> result = service().findPaths("Q0900601", "Q0900602", 2);

    assertThat(result.outcome()).isEqualTo(ToolResult.Outcome.OK);
    assertThat(result.payload()).hasSize(PathRanking.MAX_PATHS);
    assertThat(result.detail()).isEqualTo("50 route(s) from Q0900601 to Q0900602");
  }

  @Test
  @DisplayName("findPaths on a pair with no route returns ok with an empty payload")
  void findPathsNoRouteReturnsEmptyOk() {
    ingest.record(new NodeAssertion("Q01", NodeKind.PERSON, "Alone", WIKIDATA));
    ingest.record(new NodeAssertion("Q02", NodeKind.PERSON, "Also Alone", WIKIDATA));

    ToolResult<List<PathView>> result = service().findPaths("Q01", "Q02", 3);

    assertThat(result.outcome()).isEqualTo(ToolResult.Outcome.OK);
    assertThat(result.payload()).isEmpty();
  }

  @Test
  @DisplayName("findPaths on an unadded 'from' entity returns an error, not ok-with-nothing")
  void findPathsUnknownFromReturnsError() {
    ingest.record(new NodeAssertion("Q02", NodeKind.PERSON, "Known", WIKIDATA));

    ToolResult<List<PathView>> result = service().findPaths("Q01", "Q02", 3);

    assertThat(result.outcome()).isEqualTo(ToolResult.Outcome.ERROR);
    assertThat(result.detail()).contains("Q01");
    assertThat(result.payload()).isNull();
  }

  @Test
  @DisplayName("findPaths on an unadded 'to' entity returns an error, not ok-with-nothing")
  void findPathsUnknownToReturnsError() {
    ingest.record(new NodeAssertion("Q01", NodeKind.PERSON, "Known", WIKIDATA));

    ToolResult<List<PathView>> result = service().findPaths("Q01", "Q02", 3);

    assertThat(result.outcome()).isEqualTo(ToolResult.Outcome.ERROR);
    assertThat(result.detail()).contains("Q02");
  }

  // ---- test doubles ---------------------------------------------------------

  /**
   * A clock whose instant the test moves by hand, so "updated-at moved" is an assertion about a
   * value rather than about wall-clock time passing between two fast method calls.
   */
  private static final class SettableClock extends Clock {
    private Instant instant;

    private SettableClock(Instant instant) {
      this.instant = instant;
    }

    private void set(Instant instant) {
      this.instant = instant;
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return instant;
    }
  }

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
