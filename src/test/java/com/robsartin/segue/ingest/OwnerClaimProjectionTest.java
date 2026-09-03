package com.robsartin.segue.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.segue.domain.EdgeRecord;
import com.robsartin.segue.domain.LocalEntity;
import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.domain.OwnerEdge;
import com.robsartin.segue.domain.Provenance;
import com.robsartin.segue.domain.Retraction;
import com.robsartin.segue.domain.SameAs;
import com.robsartin.segue.export.LogProjection;
import com.robsartin.segue.port.AssertionLog;
import com.robsartin.segue.port.GraphStore;
import com.robsartin.segue.port.IdentityMerge;
import com.robsartin.segue.retract.RetractCli;
import com.robsartin.segue.retract.RetractRun;
import com.robsartin.segue.sqlite.SqliteAssertionLog;
import com.robsartin.segue.tinker.TinkerGraphStore;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The owner's three claims make the whole round trip: appended to the real SQLite log, read back
 * out of it, folded by the shared retraction rule, and applied to the graph.
 *
 * <p><b>The real log, deliberately, not a test double.</b> An in-memory {@code AssertionLog} double
 * would pass every assertion here while {@code SqliteAssertionLog.append} still threw, which is the
 * one gap this task exists to close: a claim the write half accepts and the read half cannot decode
 * makes every tool that reads the log fail, on a log ADR 19 forbids deleting a row from.
 */
class OwnerClaimProjectionTest {

  private static final Instant NOW = Instant.parse("2026-08-31T09:00:00Z");

  /**
   * Two leading zeros: the local-entity shape (ADR 59, issue #92), distinct from a
   * single-leading-zero stand-in, which is ADR 58's. The plan's own literals predate both and are
   * refused by {@code LocalEntity} today.
   */
  private static final String MINTED = "Q00900042";

  private static final String OTHER_MINTED = "Q00900043";

  /**
   * A merge's canonical side in ADR 62's eleven-digit shape - unallocatable by Wikibase's grammar,
   * and the only stand-in {@code SameAs} admits on that side. It was {@code Q42}, Douglas Adams,
   * which made six fabricated merges assert that a real Wikidata entity is the owner's own minted
   * work - the exact claim ADR 58 exists to stop, and the allowlist entry that let it stand here is
   * verbatim the alternative ADR 62 rejects.
   */
  private static final String CANONICAL = "Q10000000042";

  private AssertionLog log;
  private GraphStore graph;
  private IngestService ingest;

  @BeforeEach
  void setUp() {
    log = SqliteAssertionLog.inMemory();
    graph = new TinkerGraphStore();
    ingest = new IngestService(log, graph, IdentityMerge.NONE);
  }

  @AfterEach
  void tearDown() {
    graph.close();
    log.close();
  }

  @Test
  @DisplayName("should put a minted entity in the graph with no classes")
  void shouldPutAMintedEntityInTheGraphWithNoClasses() {
    ingest.record(LocalEntity.minted(MINTED, NodeKind.PERSON, "a minted person", NOW));

    assertThat(graph.node(MINTED)).isPresent();
    assertThat(graph.node(MINTED).orElseThrow().instanceOf()).isEmpty();
  }

  @Test
  @DisplayName("should record an owner edge as the owner's claim, not a model's guess")
  void shouldRecordAnOwnerEdgeAsTheOwnersClaimNotAModelsGuess() {
    ingest.record(LocalEntity.minted(MINTED, NodeKind.PERSON, "a minted person", NOW));
    ingest.record(LocalEntity.minted(OTHER_MINTED, NodeKind.PERSON, "another minted person", NOW));
    ingest.record(OwnerEdge.claimed(MINTED, OTHER_MINTED, "INFLUENCED_BY", NOW));

    EdgeRecord edge = onlyEdgeFrom(MINTED);
    assertThat(edge.sources()).singleElement().matches(Provenance::isOwner);
    assertThat(edge.isUncorroboratedHypothesis())
        .as("an owner claim is not a model guess, so PathRanking must not demote it")
        .isFalse();
  }

  @Test
  @DisplayName("should read every owner claim back out of the real log unchanged")
  void shouldReadEveryOwnerClaimBackOutOfTheRealLogUnchanged() {
    LocalEntity minted = LocalEntity.minted(MINTED, NodeKind.WORK, "a minted work", NOW);
    LocalEntity other = LocalEntity.minted(OTHER_MINTED, NodeKind.PERSON, "another minted", NOW);
    OwnerEdge owned = OwnerEdge.claimed(MINTED, OTHER_MINTED, "INFLUENCED_BY", NOW);
    SameAs merge = SameAs.declared(MINTED, CANONICAL, NOW);

    ingest.recordAll(List.of(minted, other, owned, merge));

    assertThat(log.readAll()).containsExactly(minted, other, owned, merge);
  }

  @Test
  @DisplayName("should rebuild the owner's claims into the graph when the log is replayed at boot")
  void shouldRebuildTheOwnersClaimsIntoTheGraphWhenTheLogIsReplayedAtBoot() {
    ingest.recordAll(
        List.of(
            LocalEntity.minted(MINTED, NodeKind.PERSON, "a minted person", NOW),
            LocalEntity.minted(OTHER_MINTED, NodeKind.PERSON, "another minted person", NOW),
            OwnerEdge.claimed(MINTED, OTHER_MINTED, "INFLUENCED_BY", NOW)));

    try (GraphStore rebuilt = new TinkerGraphStore()) {
      assertThat(GraphProjector.project(log, rebuilt, IdentityMerge.NONE)).isEqualTo(3);
      assertThat(rebuilt.node(MINTED)).isPresent();
      assertThat(rebuilt.edges(MINTED)).hasSize(1);
    }
  }

  @Test
  @DisplayName("should stop projecting the owner's claims a retraction reaches")
  void shouldStopProjectingTheOwnersClaimsARetractionReaches() {
    ingest.recordAll(
        List.of(
            LocalEntity.minted(MINTED, NodeKind.PERSON, "a minted person", NOW),
            LocalEntity.minted(OTHER_MINTED, NodeKind.PERSON, "another minted person", NOW),
            OwnerEdge.claimed(MINTED, OTHER_MINTED, "INFLUENCED_BY", NOW)));
    IngestService.retract(log, new Retraction(MINTED, "minted the wrong thing", NOW));

    try (GraphStore rebuilt = new TinkerGraphStore()) {
      GraphProjector.project(log, rebuilt, IdentityMerge.NONE);

      assertThat(rebuilt.node(MINTED)).isEmpty();
      assertThat(rebuilt.node(OTHER_MINTED)).isPresent();
      assertThat(rebuilt.edgeCount()).isZero();
    }
  }

  @Test
  @DisplayName("should fold the owner's claims into the export projection")
  void shouldFoldTheOwnersClaimsIntoTheExportProjection() {
    ingest.recordAll(
        List.of(
            LocalEntity.minted(MINTED, NodeKind.PERSON, "a minted person", NOW),
            LocalEntity.minted(OTHER_MINTED, NodeKind.PERSON, "another minted person", NOW),
            OwnerEdge.claimed(MINTED, OTHER_MINTED, "INFLUENCED_BY", NOW)));

    LogProjection projection = LogProjection.of(log);

    assertThat(projection.nodes()).containsOnlyKeys(MINTED, OTHER_MINTED);
    assertThat(projection.danglingEdges()).isZero();
    assertThat(projection.edges())
        .singleElement()
        .satisfies(
            edge -> {
              assertThat(edge.fromQid()).isEqualTo(MINTED);
              assertThat(edge.sources()).singleElement().matches(Provenance::isOwner);
            });
  }

  @Test
  @DisplayName("should count the owner's claims in the effect a retraction reports")
  void shouldCountTheOwnersClaimsInTheEffectARetractionReports() {
    ingest.recordAll(
        List.of(
            LocalEntity.minted(MINTED, NodeKind.PERSON, "a minted person", NOW),
            LocalEntity.minted(OTHER_MINTED, NodeKind.PERSON, "another minted person", NOW),
            OwnerEdge.claimed(MINTED, OTHER_MINTED, "INFLUENCED_BY", NOW)));

    RetractRun.Effect effect = measureEffectOn(MINTED);

    assertThat(effect.label()).isEqualTo("a minted person");
    assertThat(effect.nodeClaims()).isEqualTo(1);
    assertThat(effect.edgeClaims()).isEqualTo(1);
  }

  @Test
  @DisplayName("should read back a row whose shape the local-entity convention no longer accepts")
  void shouldReadBackARowWhoseShapeTheLocalEntityConventionNoLongerAccepts() {
    // A row written before c837265 tightened the local shape from a numeric floor to two leading
    // zeros - one leading zero was a valid local id that week. The log is append-only (ADR 19),
    // so the row is still there and every reader still has to decode it.
    LocalEntity legacy =
        new LocalEntity("Q0900042", NodeKind.PERSON, "minted before the shape moved", NOW);

    log.append(legacy);

    assertThat(log.readAll()).containsExactly(legacy);
  }

  @Test
  @DisplayName("should read back a row whose edge type the vocabulary no longer registers")
  void shouldReadBackARowWhoseEdgeTypeTheVocabularyNoLongerRegisters() {
    // The same hazard from the other direction: EdgeTypes is a mutable vocabulary, and retiring
    // or renaming a code must not make the rows that used it undecodable.
    OwnerEdge legacy = new OwnerEdge(MINTED, OTHER_MINTED, "RETIRED_TYPE", NOW);

    log.append(legacy);

    assertThat(log.readAll()).containsExactly(legacy);
  }

  @Test
  @DisplayName("should count a merge among the claims a retraction will reach")
  void shouldCountAMergeAmongTheClaimsARetractionWillReach() {
    ingest.recordAll(
        List.of(
            LocalEntity.minted(MINTED, NodeKind.PERSON, "a minted person", NOW),
            SameAs.declared(MINTED, CANONICAL, NOW)));

    RetractRun.Effect effect = measureEffectOn(MINTED);

    assertThat(effect.nodeClaims()).isEqualTo(1);
    assertThat(effect.edgeClaims())
        .as("an entity known only through a merge has to be retractable")
        .isEqualTo(1);
  }

  @Test
  @DisplayName("should stop counting a merge whose canonical end was retracted after it")
  void shouldStopCountingAMergeWhoseCanonicalEndWasRetractedAfterIt() {
    ingest.recordAll(
        List.of(
            LocalEntity.minted(MINTED, NodeKind.PERSON, "a minted person", NOW),
            SameAs.declared(MINTED, CANONICAL, NOW)));
    IngestService.retract(log, new Retraction(CANONICAL, "resolved to the wrong item", NOW));

    RetractRun.Effect effect = measureEffectOn(MINTED);

    assertThat(effect.nodeClaims()).as("the minted entity itself was not retracted").isEqualTo(1);
    assertThat(effect.edgeClaims())
        .as("a merge is dropped when either end is retracted, on the edge rule")
        .isZero();
  }

  @Test
  @DisplayName("should fold a log holding a merge without drawing the merge as an edge")
  void shouldFoldALogHoldingAMergeWithoutDrawingTheMergeAsAnEdge() {
    ingest.recordAll(
        List.of(
            LocalEntity.minted(MINTED, NodeKind.PERSON, "a minted person", NOW),
            SameAs.declared(MINTED, CANONICAL, NOW)));

    LogProjection projection = LogProjection.of(log);

    assertThat(projection.nodes())
        .as("the canonical node is stood in for here as Equivalences.standIns does it in the graph")
        .containsOnlyKeys(MINTED, CANONICAL);
    assertThat(projection.edges())
        .as("a merge is a statement about identity; find_paths cannot route along it")
        .isEmpty();
    assertThat(projection.danglingEdges()).isZero();
  }

  @Test
  @DisplayName("should count a merge among the rows replay reports as applied")
  void shouldCountAMergeAmongTheRowsReplayReportsAsApplied() {
    ingest.recordAll(
        List.of(
            LocalEntity.minted(MINTED, NodeKind.PERSON, "a minted person", NOW),
            SameAs.declared(MINTED, CANONICAL, NOW)));

    try (GraphStore rebuilt = new TinkerGraphStore()) {
      assertThat(GraphProjector.project(log, rebuilt, IdentityMerge.NONE))
          .as("the count is rows the projection consumed, and #92 Task 4 gives a merge an effect")
          .isEqualTo(2);
    }
  }

  /** Report what a retraction of {@code qid} would reach, without appending one. */
  private RetractRun.Effect measureEffectOn(String qid) {
    return new RetractRun(log, Clock.fixed(NOW, ZoneOffset.UTC))
        .run(
            new RetractCli.Options(Path.of("unused"), qid, "a reason", true),
            new ArrayList<String>()::add);
  }

  private EdgeRecord onlyEdgeFrom(String qid) {
    List<EdgeRecord> edges = graph.edges(qid);
    assertThat(edges).hasSize(1);
    return edges.get(0);
  }
}
