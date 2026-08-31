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
   * Two leading zeros: the local-entity shape, distinct from a single-leading-zero stand-in (ADR
   * 58, issue #141). The plan's own literals predate that fix and are refused by {@code
   * LocalEntity} today.
   */
  private static final String MINTED = "Q00900042";

  private static final String OTHER_MINTED = "Q00900043";

  private AssertionLog log;
  private GraphStore graph;
  private IngestService ingest;

  @BeforeEach
  void setUp() {
    log = SqliteAssertionLog.inMemory();
    graph = new TinkerGraphStore();
    ingest = new IngestService(log, graph);
  }

  @AfterEach
  void tearDown() {
    graph.close();
    log.close();
  }

  @Test
  @DisplayName("should put a minted entity in the graph with no classes")
  void shouldPutAMintedEntityInTheGraphWithNoClasses() {
    ingest.record(new LocalEntity(MINTED, NodeKind.PERSON, "a minted person", NOW));

    assertThat(graph.node(MINTED)).isPresent();
    assertThat(graph.node(MINTED).orElseThrow().instanceOf()).isEmpty();
  }

  @Test
  @DisplayName("should record an owner edge as the owner's claim, not a model's guess")
  void shouldRecordAnOwnerEdgeAsTheOwnersClaimNotAModelsGuess() {
    ingest.record(new LocalEntity(MINTED, NodeKind.PERSON, "a minted person", NOW));
    ingest.record(new LocalEntity(OTHER_MINTED, NodeKind.PERSON, "another minted person", NOW));
    ingest.record(new OwnerEdge(MINTED, OTHER_MINTED, "INFLUENCED_BY", NOW));

    EdgeRecord edge = onlyEdgeFrom(MINTED);
    assertThat(edge.sources()).singleElement().matches(Provenance::isOwner);
    assertThat(edge.isUncorroboratedHypothesis())
        .as("an owner claim is not a model guess, so PathRanking must not demote it")
        .isFalse();
  }

  @Test
  @DisplayName("should read every owner claim back out of the real log unchanged")
  void shouldReadEveryOwnerClaimBackOutOfTheRealLogUnchanged() {
    LocalEntity minted = new LocalEntity(MINTED, NodeKind.WORK, "a minted work", NOW);
    LocalEntity other = new LocalEntity(OTHER_MINTED, NodeKind.PERSON, "another minted", NOW);
    OwnerEdge owned = new OwnerEdge(MINTED, OTHER_MINTED, "INFLUENCED_BY", NOW);
    SameAs merge = new SameAs(MINTED, "Q42", NOW);

    ingest.recordAll(List.of(minted, other, owned, merge));

    assertThat(log.readAll()).containsExactly(minted, other, owned, merge);
  }

  @Test
  @DisplayName("should rebuild the owner's claims into the graph when the log is replayed at boot")
  void shouldRebuildTheOwnersClaimsIntoTheGraphWhenTheLogIsReplayedAtBoot() {
    ingest.recordAll(
        List.of(
            new LocalEntity(MINTED, NodeKind.PERSON, "a minted person", NOW),
            new LocalEntity(OTHER_MINTED, NodeKind.PERSON, "another minted person", NOW),
            new OwnerEdge(MINTED, OTHER_MINTED, "INFLUENCED_BY", NOW)));

    try (GraphStore rebuilt = new TinkerGraphStore()) {
      assertThat(GraphProjector.project(log, rebuilt)).isEqualTo(3);
      assertThat(rebuilt.node(MINTED)).isPresent();
      assertThat(rebuilt.edges(MINTED)).hasSize(1);
    }
  }

  @Test
  @DisplayName("should stop projecting the owner's claims a retraction reaches")
  void shouldStopProjectingTheOwnersClaimsARetractionReaches() {
    ingest.recordAll(
        List.of(
            new LocalEntity(MINTED, NodeKind.PERSON, "a minted person", NOW),
            new LocalEntity(OTHER_MINTED, NodeKind.PERSON, "another minted person", NOW),
            new OwnerEdge(MINTED, OTHER_MINTED, "INFLUENCED_BY", NOW)));
    IngestService.retract(log, new Retraction(MINTED, "minted the wrong thing", NOW));

    try (GraphStore rebuilt = new TinkerGraphStore()) {
      GraphProjector.project(log, rebuilt);

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
            new LocalEntity(MINTED, NodeKind.PERSON, "a minted person", NOW),
            new LocalEntity(OTHER_MINTED, NodeKind.PERSON, "another minted person", NOW),
            new OwnerEdge(MINTED, OTHER_MINTED, "INFLUENCED_BY", NOW)));

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
            new LocalEntity(MINTED, NodeKind.PERSON, "a minted person", NOW),
            new LocalEntity(OTHER_MINTED, NodeKind.PERSON, "another minted person", NOW),
            new OwnerEdge(MINTED, OTHER_MINTED, "INFLUENCED_BY", NOW)));

    RetractRun.Effect effect =
        new RetractRun(log, Clock.fixed(NOW, ZoneOffset.UTC))
            .run(
                new RetractCli.Options(Path.of("unused"), MINTED, "minted the wrong thing", true),
                new ArrayList<String>()::add);

    assertThat(effect.label()).isEqualTo("a minted person");
    assertThat(effect.nodeClaims()).isEqualTo(1);
    assertThat(effect.edgeClaims()).isEqualTo(1);
  }

  private EdgeRecord onlyEdgeFrom(String qid) {
    List<EdgeRecord> edges = graph.edges(qid);
    assertThat(edges).hasSize(1);
    return edges.get(0);
  }
}
