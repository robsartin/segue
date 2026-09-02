package com.robsartin.segue.port;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.robsartin.segue.domain.AssertionRecord;
import com.robsartin.segue.domain.EdgeRecord;
import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.domain.NodeRecord;
import com.robsartin.segue.domain.PathRanking;
import com.robsartin.segue.domain.PathResult;
import com.robsartin.segue.domain.Provenance;
import com.robsartin.segue.fixture.Fixture;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Every GraphStore must satisfy this, whichever engine backs it.
 *
 * <p>This was the BakeOff program in slice 0. Making it a contract test is the point: the
 * cross-engine comparison stops being something you remember to run and becomes something CI
 * refuses to merge without. See docs/adr/0018-graph-engine-gremlin.md, which commits to keeping the
 * Jena reference implementation working.
 *
 * <p>Note the assertions compare full result SETS. Comparing only the shortest path is exactly what
 * let the multigraph bug pass in slice 0: the RDF adapter's neighbour query walked nodes rather
 * than edges, so {@code SELECT DISTINCT ?other} collapsed the two different relationships between
 * Cave and The Proposition into one, and a whole route silently disappeared. Gremlin's {@code
 * bothE().otherV()} cannot have that bug.
 */
public abstract class GraphStoreContract {

  private GraphStore store;

  /** Supply a fresh, empty store. Called before each test. */
  protected abstract GraphStore createStore();

  @BeforeEach
  void seed() {
    store = createStore();
    Fixture.seed(store);
  }

  @AfterEach
  void tearDown() {
    if (store != null) {
      store.close();
    }
  }

  @Test
  @DisplayName("27 assertions fold into exactly 23 edges")
  void ingestCollapsesAssertions() {
    // 27 assertions: 3 pairs sharing a (from, type, to) triple from two real sources
    // (corroboration()), plus the owner's claim folding onto a fourth triple a real source
    // already asserted (#92) — four folds, 27 assertions fold to 23 edges. The 27th is the
    // owner's standalone claim over two local entities (#176), which folds onto nothing: it is
    // an edge in its own right, and its corroboration is 0 rather than absent.
    assertThat(store.edgeCount()).isEqualTo(23);
  }

  @Test
  @DisplayName("every seeded node is retrievable")
  void nodesAreRetrievable() {
    assertThat(store.node(Fixture.CAVE)).isPresent();
    assertThat(store.node(Fixture.CAVE).orElseThrow().label()).isEqualTo("Nick Cave");
    assertThat(store.node("Q999999")).isEmpty();
  }

  @Test
  @DisplayName("the classes a node was classified from survive the round trip, in order")
  void instanceOfSurvivesTheRoundTrip() {
    // Issue #60 puts the raw P31 on the node beside the derived kind. A store that dropped it
    // would leave a record field that only ever reads back empty - and both engines have to
    // keep the ORDER, because the field records what the source said and a source's answer has
    // an order. The kind no longer depends on it (issue #87 ranks the kinds instead); a store
    // that reordered the list would still be editing the claim on its way through.
    store.upsertNode(
        new NodeRecord("Q0100003", NodeKind.WORK, "Probe Song", List.of("Q134556", "Q7366")));

    assertThat(store.node("Q0100003").orElseThrow().instanceOf())
        .containsExactly("Q134556", "Q7366");
  }

  @Test
  @DisplayName("a node stating no classes reads back with an empty list, not a null")
  void absentInstanceOfReadsBackEmpty() {
    store.upsertNode(new NodeRecord("Q0100004", NodeKind.PERSON, "Classless Probe"));

    assertThat(store.node("Q0100004").orElseThrow().instanceOf()).isEmpty();
  }

  @Test
  @DisplayName("Q1: Cave reaches Hillcoat in two hops, through a film")
  void pathsCrossFromMusicIntoFilm() {
    List<PathResult> ranked = PathRanking.rank(store.paths(Fixture.CAVE, Fixture.HILLCOAT, 4));

    assertThat(ranked).isNotEmpty();
    // Every Cave-Hillcoat route is wikidata-sourced at 1.00, so ranking ties on confidence and the
    // hop-count tiebreak surfaces the two-hop route first.
    assertThat(ranked.get(0).length()).isEqualTo(2);
  }

  @Test
  @DisplayName("Q1: the multigraph survives — three distinct two-hop routes")
  void multigraphProducesThreeTwoHopRoutes() {
    List<PathResult> paths = store.paths(Fixture.CAVE, Fixture.HILLCOAT, 4);

    assertThat(paths.stream().filter(p -> p.length() == 2)).hasSize(3);
  }

  @Test
  @DisplayName("Q1b: ranking surfaces the trustworthy route over the model's shorter guess")
  void rankingSurfacesTrustOverBrevity() {
    List<PathResult> ranked = PathRanking.rank(store.paths(Fixture.CAVE, Fixture.MCCARTHY, 4));

    PathResult shortest =
        ranked.stream().min(Comparator.comparingInt(PathResult::length)).orElseThrow();
    // The shortest Cave-McCarthy route is the model's unverified 1-hop guess (the bug ADR 23
    // records).
    assertThat(shortest.length()).isEqualTo(1);
    assertThat(shortest.weakestConfidence()).isLessThanOrEqualTo(0.30);

    // ADR 31 is the fix: the fully-sourced, longer route ranks first instead of the short guess.
    PathResult top = ranked.get(0);
    assertThat(top).isNotEqualTo(shortest);
    assertThat(top.weakestConfidence()).isGreaterThan(shortest.weakestConfidence());
    assertThat(top.length()).isGreaterThan(shortest.length());
  }

  @Test
  @DisplayName("Q2: last.fm contributed exactly one edge after 15 August")
  void auditBySourceAndTime() {
    List<EdgeRecord> edges = store.assertedBy("lastfm", Instant.parse("2026-08-15T00:00:00Z"));

    assertThat(edges).hasSize(1);
  }

  @Test
  @DisplayName("Q2: every model-asserted edge is still an uncorroborated hypothesis")
  void modelAssertionsRemainHypotheses() {
    List<EdgeRecord> edges = store.assertedBy("llm:claude", Instant.EPOCH);

    assertThat(edges).isNotEmpty();
    assertThat(edges).allMatch(EdgeRecord::isUncorroboratedHypothesis);
  }

  @Test
  @DisplayName("Q3: the Bad Seeds lineup in June 1984 has three members")
  void timeTravelTo1984() {
    List<EdgeRecord> lineup = store.validAt(Fixture.BAD_SEEDS, LocalDate.of(1984, 6, 1));

    assertThat(lineup).hasSize(3);
    assertThat(lineup).noneMatch(e -> e.fromQid().equals(Fixture.ELLIS));
    assertThat(lineup).anyMatch(e -> e.fromQid().equals(Fixture.BLIXA));
  }

  @Test
  @DisplayName("Q3: Mick Harvey has dropped out of the 2010 lineup")
  void timeTravelTo2010() {
    List<EdgeRecord> lineup = store.validAt(Fixture.BAD_SEEDS, LocalDate.of(2010, 6, 1));

    assertThat(lineup).isNotEmpty();
    assertThat(lineup).anyMatch(e -> e.fromQid().equals(Fixture.ELLIS));
    assertThat(lineup).noneMatch(e -> e.fromQid().equals(Fixture.HARVEY_MICK));
  }

  @Test
  @DisplayName("Q4: three edges have two independent sources, none of them model-only")
  void corroboration() {
    List<EdgeRecord> corroborated = store.corroborated(2);

    assertThat(corroborated).hasSize(3);
    assertThat(corroborated).noneMatch(EdgeRecord::isUncorroboratedHypothesis);
    // #92: the owner also claimed Cave authored the novel wikidata already asserts — that must
    // NOT make this triple's second edge in the corroborated(2) set. Both GraphStore
    // implementations answer Q4 with a query of their own (a Java stream here, SPARQL's COUNT
    // DISTINCT in JenaGraphStore); this pins the owner-exclusion on whichever engine runs this.
    assertThat(corroborated)
        .noneMatch(
            e -> e.fromQid().equals(Fixture.CAVE) && e.toQid().equals(Fixture.ASS_SAW_ANGEL));
  }

  @Test
  @DisplayName("Q4 at zero: the owner's standalone claim is an edge whose corroboration is 0")
  void shouldReturnTheOwnerOnlyEdgeWhenTheCorroborationFloorIsZero() {
    // ADR 59's third layer, uncorroborated: an owner claim over two local entities no source has
    // ever mentioned. Its corroboration is 0 - a count, not an absence - so corroborated(0) has to
    // return it, and corroborated(1), which asks for one real witness, must not (#176).
    //
    // This sits beside the N = 2 case above for the same reason that one exists: it pins the rule
    // on WHICHEVER engine runs the contract, so a third adapter would inherit it. The differential
    // guard in TinkerGraphStoreContractTest hardcodes Tinker and Jena and could not.
    assertThat(store.corroborated(0)).anyMatch(Fixture::isOwnerOnlyEdge);
    assertThat(store.corroborated(1)).noneMatch(Fixture::isOwnerOnlyEdge);
  }

  @Test
  @DisplayName("an assertedAt instant survives the round trip at full precision")
  void provenanceTimestampSurvivesFullPrecision() {
    // Issue #6: ProvenanceCodec truncated to epoch millis while Jena kept ISO precision,
    // so the two engines disagreed on any Instant finer than a millisecond. Invisible
    // until the SQLite log started storing real ingest timestamps.
    Instant precise = Instant.parse("2026-08-24T09:15:30.123456789Z");
    store.upsertNode(new NodeRecord("Q0100001", NodeKind.PERSON, "Precision Probe"));
    store.upsertNode(new NodeRecord("Q0100002", NodeKind.WORK, "Probe Work"));
    store.record(
        new AssertionRecord(
            "Q0100001",
            "Q0100002",
            "AUTHORED",
            null,
            null,
            new Provenance("wikidata", "S-precision", precise, 1.0)));

    List<EdgeRecord> edges = store.edges("Q0100001");
    assertThat(edges).isNotEmpty();
    assertThat(edges)
        .anySatisfy(
            e ->
                assertThat(e.sources())
                    .anySatisfy(p -> assertThat(p.assertedAt()).isEqualTo(precise)));
  }

  @Test
  @DisplayName(
      "recording an assertion against an unknown entity is rejected, not silently materialised")
  void recordingAgainstAnUnknownEntityIsRejected() {
    // An edge to an entity nothing has claimed is a claim about nothing, not a node to invent
    // on the fly. TinkerGraphStore.requireVertex already throws; this pins that as the agreed
    // cross-engine behaviour rather than an accident of one adapter — increment 4's neighbour
    // fan-out will hit this constantly, and the two engines must fail the same way.
    AssertionRecord toNowhere =
        new AssertionRecord(
            "Q0999999997",
            "Q0999999996",
            "AUTHORED",
            null,
            null,
            new Provenance("wikidata", "S-unknown", Instant.EPOCH, 1.0));

    assertThatThrownBy(() -> store.record(toNowhere)).isInstanceOf(IllegalStateException.class);
  }

  /**
   * Canonical rendering of a route set, so two engines can be compared exactly. Used by the
   * cross-engine agreement test, which lives in the Tinker subclass because it needs both stores at
   * once.
   */
  public static List<String> signatures(List<PathResult> paths) {
    return paths.stream()
        .map(
            p ->
                p.hops().stream()
                    .map(
                        h ->
                            h.from().qid()
                                + (h.traversedBackwards() ? "<-" : "-")
                                + h.edge().typeCode()
                                + (h.traversedBackwards() ? "-" : "->")
                                + h.to().qid())
                    .collect(Collectors.joining(" | ")))
        .sorted()
        .toList();
  }
}
