package com.robsartin.segue.expansion;

import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.segue.domain.AssertionRecord;
import com.robsartin.segue.domain.Candidate;
import com.robsartin.segue.domain.EdgeTypes;
import com.robsartin.segue.domain.ExpansionBounds;
import com.robsartin.segue.domain.LocalEntity;
import com.robsartin.segue.domain.NodeAssertion;
import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.domain.NodeRecord;
import com.robsartin.segue.domain.Provenance;
import com.robsartin.segue.ingest.IngestService;
import com.robsartin.segue.port.AssertionLog;
import com.robsartin.segue.port.EntityResolver;
import com.robsartin.segue.port.ExpandContext;
import com.robsartin.segue.port.ExpandResult;
import com.robsartin.segue.port.GraphStore;
import com.robsartin.segue.port.IdentityMerge;
import com.robsartin.segue.port.SourceAdapter;
import com.robsartin.segue.port.SourceAdapters;
import com.robsartin.segue.sqlite.SqliteAssertionLog;
import com.robsartin.segue.tinker.TinkerGraphStore;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The expansion's own contract, driven without a {@code ToolResult} in sight. Every id is invented
 * and carries ADR 58's leading zero; nothing here comes from anybody's graph (ADR 33, issue #37).
 */
class EntityExpansionTest {

  private static final String SEED = "Q0900601";
  private static final String NEIGHBOUR = "Q0900602";
  private static final String MINTED = "Q00900603";
  private static final String OTHER_NEIGHBOUR = "Q0900604";
  private static final String THIRD_PARTY = "Q0900605";
  private static final String NOBODY_DESCRIBED = "Q0900606";

  /** Invented, like every label in this file — see the class Javadoc. */
  private static final Provenance WIKIDATA =
      new Provenance("wikidata", "S-1", Instant.parse("2026-09-07T09:00:00Z"), 1.0);

  private static final Provenance MUSICBRAINZ =
      new Provenance("musicbrainz", "S-2", Instant.parse("2026-09-07T09:00:00Z"), 1.0);

  private static final Instant MINTED_AT = Instant.parse("2026-09-07T10:00:00Z");

  @TempDir private Path dir;

  private AssertionLog log;
  private GraphStore graph;
  private IngestService ingest;
  private StubResolver resolver;

  @BeforeEach
  void setUp() {
    log = new SqliteAssertionLog(dir.resolve("scratch.db"));
    graph = new TinkerGraphStore();
    ingest = new IngestService(log, graph, IdentityMerge.NONE);
    resolver = new StubResolver();
  }

  @AfterEach
  void tearDown() {
    graph.close();
    log.close();
  }

  private EntityExpansion expansion(SourceAdapter... adapters) {
    return new EntityExpansion(resolver, graph, ingest, new SourceAdapters(List.of(adapters)));
  }

  private static AssertionRecord memberOf(String from, String to) {
    return memberOf(from, to, WIKIDATA);
  }

  private static AssertionRecord memberOf(String from, String to, Provenance provenance) {
    return new AssertionRecord(from, to, EdgeTypes.MEMBER_OF.code(), null, null, provenance);
  }

  @Test
  @DisplayName("an entity the graph holds no node for is refused before any adapter runs")
  void shouldRefuseAsUnknownWhenTheGraphHoldsNoNodeForTheEntity() {
    EntityExpansion expansion =
        expansion(new StubAdapter("wikidata", ExpandResult.of(List.of(memberOf(SEED, NEIGHBOUR)))));

    assertThat(expansion.expand(SEED, 10))
        .isEqualTo(new ExpansionOutcome.Refused(SEED, ExpansionOutcome.Reason.UNKNOWN_ENTITY));
  }

  @Test
  @DisplayName("an entity the owner minted is refused, because no source can ever have it")
  void shouldRefuseAsLocalWhenTheOwnerMintedTheEntity() {
    // ADR 59's two-leading-zero shape: an id Wikidata's grammar can never allocate, so no source
    // has it and none ever will. The adapter here would happily answer; it is never asked.
    ingest.record(
        LocalEntity.minted(MINTED, NodeKind.WORK, "a pamphlet no source indexes", MINTED_AT));
    EntityExpansion expansion = expansion(new StubAdapter("wikidata", ExpandResult.of(List.of())));

    assertThat(expansion.expand(MINTED, 10))
        .isEqualTo(new ExpansionOutcome.Refused(MINTED, ExpansionOutcome.Reason.LOCAL_ENTITY));
  }

  @Test
  @DisplayName("a bound of zero or less is refused before the ceiling is even applied")
  void shouldRefuseTheBoundWhenItIsNotPositive() {
    ingest.record(new NodeAssertion(SEED, NodeKind.CONCEPT, "a genre nobody named", WIKIDATA));
    EntityExpansion expansion = expansion(new StubAdapter("wikidata", ExpandResult.of(List.of())));

    assertThat(expansion.expand(SEED, 0))
        .isEqualTo(new ExpansionOutcome.Refused(SEED, ExpansionOutcome.Reason.BOUND_NOT_POSITIVE));
  }

  @Test
  @DisplayName("what an adapter returns is recorded, and counted as edges and new nodes")
  void shouldRecordWhatTheAdaptersReturnWhenTheSeedIsInTheGraph() {
    ingest.record(new NodeAssertion(SEED, NodeKind.PERSON, "a singer nobody signed", WIKIDATA));
    resolver.withEntity(
        new NodeAssertion(NEIGHBOUR, NodeKind.GROUP, "a band nobody booked", WIKIDATA));
    EntityExpansion expansion =
        expansion(new StubAdapter("wikidata", ExpandResult.of(List.of(memberOf(SEED, NEIGHBOUR)))));

    assertThat(expansion.expand(SEED, 10))
        .isInstanceOfSatisfying(
            ExpansionOutcome.Expanded.class,
            expanded -> {
              assertThat(expanded.qid()).isEqualTo(SEED);
              assertThat(expanded.edgesAdded()).isEqualTo(1);
              assertThat(expanded.nodesAdded()).isEqualTo(1);
              assertThat(expanded.skippedNeighbors()).isZero();
              assertThat(expanded.effectiveMax()).isEqualTo(10);
              assertThat(expanded.refusedEndpoints()).isEmpty();
              assertThat(expanded.truncated()).isFalse();
              assertThat(expanded.sourceUnavailable()).isFalse();
            });
    assertThat(graph.node(NEIGHBOUR)).isPresent();
    assertThat(graph.edgeCount()).isEqualTo(1);
  }

  @Test
  @DisplayName("a source that could not be reached is named on the outcome, not thrown")
  void shouldNameTheSourceWhenAnAdapterCouldNotReachIt() {
    ingest.record(new NodeAssertion(SEED, NodeKind.PERSON, "a singer nobody signed", WIKIDATA));
    EntityExpansion expansion = expansion(new StubAdapter("flaky", ExpandResult.unavailable()));

    assertThat(expansion.expand(SEED, 10))
        .isInstanceOfSatisfying(
            ExpansionOutcome.Expanded.class,
            expanded -> {
              assertThat(expanded.unavailableSources()).containsExactly("flaky");
              assertThat(expanded.sourceUnavailable()).isTrue();
              assertThat(expanded.truncatingSources()).isEmpty();
            });
  }

  @Test
  @DisplayName("a CONCEPT seed is bounded below whatever the caller asked for")
  void shouldApplyTheCeilingWhenTheSeedIsAConcept() {
    ingest.record(new NodeAssertion(SEED, NodeKind.CONCEPT, "a genre nobody named", WIKIDATA));
    EntityExpansion expansion = expansion(new StubAdapter("wikidata", ExpandResult.of(List.of())));

    assertThat(expansion.expand(SEED, 200))
        .isInstanceOfSatisfying(
            ExpansionOutcome.Expanded.class,
            expanded ->
                assertThat(expanded.effectiveMax())
                    .as("issue #112: the ceiling is applied before the bound reaches an adapter")
                    .isEqualTo(ExpansionBounds.CONCEPT_CEILING));
  }

  @Test
  @DisplayName("recorded edges are tallied by the source their provenance names")
  void shouldTallyEdgesBySourceWhenTwoAdaptersEachRecordSome() {
    // Two adapters, two assertions each, one of the four naming an endpoint the graph holds no
    // node for — so the tally counts what was RECORDED and not what was returned.
    ingest.record(new NodeAssertion(SEED, NodeKind.PERSON, "a singer nobody signed", WIKIDATA));
    resolver
        .withEntity(new NodeAssertion(NEIGHBOUR, NodeKind.GROUP, "a band nobody booked", WIKIDATA))
        .withEntity(
            new NodeAssertion(OTHER_NEIGHBOUR, NodeKind.WORK, "a song nobody covered", WIKIDATA))
        .withEntity(
            new NodeAssertion(THIRD_PARTY, NodeKind.PERSON, "a drummer nobody hired", MUSICBRAINZ));
    EntityExpansion expansion =
        expansion(
            new StubAdapter(
                "wikidata",
                ExpandResult.of(
                    List.of(memberOf(SEED, NEIGHBOUR), memberOf(SEED, OTHER_NEIGHBOUR)))),
            new StubAdapter(
                "musicbrainz",
                ExpandResult.of(
                    List.of(
                        memberOf(THIRD_PARTY, SEED, MUSICBRAINZ),
                        // Names the seed at neither end, so its far endpoint is resolved by
                        // nobody and IngestService refuses the edge (#233).
                        memberOf(THIRD_PARTY, NOBODY_DESCRIBED, MUSICBRAINZ)))));

    ExpansionOutcome.Expanded expanded = (ExpansionOutcome.Expanded) expansion.expand(SEED, 10);

    assertThat(expanded.edgesBySource())
        .as("recorded assertions, keyed by Provenance.sourceId")
        .containsExactly(Map.entry("wikidata", 2), Map.entry("musicbrainz", 1));
    assertThat(expanded.edgesAdded())
        .as("the tally sums to edgesAdded, or one of the two numbers is lying")
        .isEqualTo(3);
    assertThat(expanded.refusedEndpoints()).containsExactly(NOBODY_DESCRIBED);
  }

  /** Answers for whatever it has been handed, and nothing else. Opens no socket. */
  private static final class StubResolver implements EntityResolver {
    private final Map<String, NodeAssertion> byQid = new HashMap<>();

    @Override
    public String id() {
      return "stub";
    }

    @Override
    public List<Candidate> search(String query, NodeKind kind, int limit) {
      return List.of();
    }

    @Override
    public Optional<NodeAssertion> fetch(String qid) {
      return Optional.ofNullable(byQid.get(qid));
    }

    StubResolver withEntity(NodeAssertion assertion) {
      byQid.put(assertion.qid(), assertion);
      return this;
    }
  }

  /** Supports every kind and returns one canned result. */
  private record StubAdapter(String id, ExpandResult result) implements SourceAdapter {

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
