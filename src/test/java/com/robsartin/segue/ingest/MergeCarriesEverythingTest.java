package com.robsartin.segue.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.segue.domain.AffinityRecord;
import com.robsartin.segue.domain.EdgeRecord;
import com.robsartin.segue.domain.LocalEntity;
import com.robsartin.segue.domain.NodeAssertion;
import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.domain.OwnerEdge;
import com.robsartin.segue.domain.Provenance;
import com.robsartin.segue.domain.SameAs;
import com.robsartin.segue.port.AffinityStore;
import com.robsartin.segue.port.AssertionLog;
import com.robsartin.segue.port.GraphStore;
import com.robsartin.segue.port.IdentityMerge;
import com.robsartin.segue.sqlite.SqliteAffinityStore;
import com.robsartin.segue.sqlite.SqliteAssertionLog;
import com.robsartin.segue.tinker.TinkerGraphStore;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What a merge carries, and what it must not disturb.
 *
 * <p>Every store here is a real one - the SQLite log and the TinkerGraph engine - for the reason
 * {@code OwnerClaimProjectionTest} gives: a double would pass while the thing the owner actually
 * runs still lost the claim.
 *
 * <p><b>The edge half is asserted against the replayed graph, not the live one</b> (#178). It used
 * to be asserted against the graph {@code ingest.record} had just written, and that graph is not
 * the one the owner ever looks at: {@code OwnCli} appends through {@code IngestService.claim} and
 * the graph is rebuilt from the log at the next boot (ADR 24), so <b>no {@code SameAs} reaches a
 * live graph in production</b>. Asserting the live path pinned a code path nothing runs, and it
 * would have gone on passing while the path that matters broke. The rating half below still reads
 * the live stores, because affinity is durable and is rebuilt by nothing - it is the one half where
 * "immediately after {@code record}" is the real question.
 */
class MergeCarriesEverythingTest {

  private static final Instant NOW = Instant.parse("2026-08-31T09:00:00Z");

  /** Two leading zeros: a local entity's shape, not a stand-in's (ADR 58, issue #141). */
  private static final String MINTED = "Q00900042";

  /** Allocatable, because a merge's canonical side is what Wikidata actually caught up with. */
  private static final String CANONICAL = "Q900";

  /** A second allocatable id, standing for something a source already told us about. */
  private static final String NEIGHBOUR = "Q901";

  private static final Provenance SOURCE =
      new Provenance("wikidata", "S-1", Instant.parse("2026-01-01T00:00:00Z"), 1.0);

  private static final Instant LATER = Instant.parse("2026-09-01T09:00:00Z");

  private AssertionLog log;
  private GraphStore graph;
  private AffinityStore affinity;
  private IngestService ingest;

  @BeforeEach
  void setUp() {
    log = SqliteAssertionLog.inMemory();
    graph = new TinkerGraphStore();
    affinity = SqliteAffinityStore.inMemory();
    ingest = new IngestService(log, graph, IdentityMerge.carryingRatings(affinity));
  }

  @AfterEach
  void tearDown() {
    affinity.close();
    graph.close();
    log.close();
  }

  @Test
  @DisplayName("should carry an owner edge to the canonical id when the merged log is replayed")
  void shouldCarryAnOwnerEdgeToTheCanonicalIdWhenTheMergedLogIsReplayed() {
    mintAndClaimAnEdge();
    ingest.record(SameAs.declared(MINTED, CANONICAL, NOW));

    try (GraphStore rebuilt = new TinkerGraphStore()) {
      GraphProjector.project(log, rebuilt, IdentityMerge.NONE);

      assertThat(rebuilt.edges(CANONICAL))
          .singleElement()
          .extracting(EdgeRecord::fromQid, EdgeRecord::toQid)
          .as("the from-side of an edge out of the local id is what the canonical id inherits")
          .containsExactly(CANONICAL, NEIGHBOUR);
    }
  }

  @Test
  @DisplayName("should keep the local id resolvable after a merge, because the log still names it")
  void shouldKeepTheLocalIdResolvableAfterAMergeBecauseTheLogStillNamesIt() {
    ingest.record(LocalEntity.minted(MINTED, NodeKind.PERSON, "a minted person", NOW));

    ingest.record(SameAs.declared(MINTED, CANONICAL, NOW));

    assertThat(graph.node(MINTED)).isPresent();
  }

  @Test
  @DisplayName("should carry the owner's own provenance, so the merge invents no second witness")
  void shouldCarryTheOwnersOwnProvenanceSoTheMergeInventsNoSecondWitness() {
    mintAndClaimAnEdge();
    ingest.record(SameAs.declared(MINTED, CANONICAL, NOW));

    try (GraphStore rebuilt = new TinkerGraphStore()) {
      GraphProjector.project(log, rebuilt, IdentityMerge.NONE);

      assertThat(rebuilt.edges(CANONICAL))
          .singleElement()
          .satisfies(
              carried -> {
                assertThat(carried.sources()).singleElement().matches(Provenance::isOwner);
                assertThat(carried.corroboration())
                    .as("the owner does not vouch, and a merge must not turn one claim into two")
                    .isZero();
              });
    }
  }

  @Test
  @DisplayName("should leave a canonical entity a source already named with the source's label")
  void shouldLeaveACanonicalEntityASourceAlreadyNamedWithTheSourcesLabel() {
    ingest.record(
        new NodeAssertion(CANONICAL, NodeKind.PERSON, "what the source calls it", SOURCE));
    ingest.record(LocalEntity.minted(MINTED, NodeKind.PERSON, "what the owner called it", NOW));

    ingest.record(SameAs.declared(MINTED, CANONICAL, NOW));

    assertThat(graph.node(CANONICAL).orElseThrow().label()).isEqualTo("what the source calls it");
  }

  @Test
  @DisplayName("should keep the label of a source that named the canonical id BEFORE the merge")
  void shouldKeepTheLabelOfASourceThatNamedTheCanonicalIdBeforeTheMerge() {
    ingest.record(
        new NodeAssertion(CANONICAL, NodeKind.PERSON, "what the source calls it", SOURCE));
    ingest.record(LocalEntity.minted(MINTED, NodeKind.PERSON, "what the owner called it", NOW));
    ingest.record(SameAs.declared(MINTED, CANONICAL, NOW));

    try (GraphStore rebuilt = new TinkerGraphStore()) {
      GraphProjector.project(log, rebuilt, IdentityMerge.NONE);

      assertThat(rebuilt.node(CANONICAL).orElseThrow().label())
          .as(
              "the stand-in is a placeholder for an entity no source has expanded yet; where one"
                  + " HAS, overwriting its label with the owner's working title would be the merge"
                  + " editing the world rather than recording an identity")
          .isEqualTo("what the source calls it");
    }
  }

  @Test
  @DisplayName("should keep the label of a source that named the canonical id AFTER the merge")
  void shouldKeepTheLabelOfASourceThatNamedTheCanonicalIdAfterTheMerge() {
    ingest.record(LocalEntity.minted(MINTED, NodeKind.PERSON, "what the owner called it", NOW));
    ingest.record(SameAs.declared(MINTED, CANONICAL, NOW));
    ingest.record(
        new NodeAssertion(CANONICAL, NodeKind.PERSON, "what the source calls it", SOURCE));

    try (GraphStore rebuilt = new TinkerGraphStore()) {
      GraphProjector.project(log, rebuilt, IdentityMerge.NONE);

      assertThat(rebuilt.node(CANONICAL).orElseThrow().label())
          .as("upsertNode is last-writer-wins, and the source is the later writer here")
          .isEqualTo("what the source calls it");
    }
  }

  @Test
  @DisplayName("should stand in with the owner's own label where no source has named the entity")
  void shouldStandInWithTheOwnersLabelWhereNoSourceHasNamedTheCanonicalEntity() {
    ingest.record(LocalEntity.minted(MINTED, NodeKind.PERSON, "what the owner called it", NOW));
    ingest.record(SameAs.declared(MINTED, CANONICAL, NOW));

    try (GraphStore rebuilt = new TinkerGraphStore()) {
      GraphProjector.project(log, rebuilt, IdentityMerge.NONE);

      assertThat(rebuilt.node(CANONICAL).orElseThrow().label())
          .as("without a stand-in a folded edge would have an endpoint the store has never seen")
          .isEqualTo("what the owner called it");
    }
  }

  @Test
  @DisplayName("should rebuild the carried edge when the log is replayed at boot")
  void shouldRebuildTheCarriedEdgeWhenTheLogIsReplayedAtBoot() {
    mintAndClaimAnEdge();
    ingest.record(SameAs.declared(MINTED, CANONICAL, NOW));

    try (GraphStore rebuilt = new TinkerGraphStore()) {
      GraphProjector.project(log, rebuilt, IdentityMerge.NONE);

      assertThat(rebuilt.edges(CANONICAL))
          .as("a merge applied live and not on replay is a graph that changes at every boot")
          .hasSize(1);
    }
  }

  @Test
  @DisplayName("should carry a rating to the canonical id when a merge is asserted")
  void shouldCarryARatingToTheCanonicalIdWhenAMergeIsAsserted() {
    ingest.record(LocalEntity.minted(MINTED, NodeKind.PERSON, "a minted person", NOW));
    affinity.put(new AffinityRecord(MINTED, 5, null, NOW));

    ingest.record(SameAs.declared(MINTED, CANONICAL, NOW));

    assertThat(affinity.find(CANONICAL)).isPresent();
    assertThat(affinity.find(CANONICAL).orElseThrow().rating()).isEqualTo(5);
  }

  @Test
  @DisplayName(
      "should leave the rating on the local id, which nothing deletes and nothing rebuilds")
  void shouldLeaveTheRatingOnTheLocalIdWhichNothingDeletesAndNothingRebuilds() {
    ingest.record(LocalEntity.minted(MINTED, NodeKind.PERSON, "a minted person", NOW));
    affinity.put(new AffinityRecord(MINTED, 5, null, NOW));

    ingest.record(SameAs.declared(MINTED, CANONICAL, NOW));

    assertThat(affinity.find(MINTED))
        .as("there is no un-rate and no history table (ADR 39, ADR 46) - a carry is not a move")
        .isPresent();
  }

  @Test
  @DisplayName("should not overwrite a newer rating already made against the canonical id")
  void shouldNotOverwriteANewerRatingAlreadyMadeAgainstTheCanonicalId() {
    ingest.record(new NodeAssertion(CANONICAL, NodeKind.PERSON, "a sourced person", SOURCE));
    ingest.record(LocalEntity.minted(MINTED, NodeKind.PERSON, "a minted person", NOW));
    affinity.put(new AffinityRecord(MINTED, 5, null, NOW));
    affinity.put(new AffinityRecord(CANONICAL, 2, null, LATER));

    ingest.record(SameAs.declared(MINTED, CANONICAL, NOW));

    assertThat(affinity.find(CANONICAL).orElseThrow().rating())
        .as("ADR 39 lets the later rating win, and a merge is not a licence to undo one")
        .isEqualTo(2);
  }

  @Test
  @DisplayName("should carry an edge that points AT the local id, not only one that starts there")
  void shouldCarryAnEdgeThatPointsAtTheLocalIdNotOnlyOneThatStartsThere() {
    ingest.record(new NodeAssertion(NEIGHBOUR, NodeKind.PERSON, "a sourced person", SOURCE));
    ingest.record(LocalEntity.minted(MINTED, NodeKind.PERSON, "a minted person", NOW));
    ingest.record(OwnerEdge.claimed(NEIGHBOUR, MINTED, "INFLUENCED_BY", NOW));
    ingest.record(SameAs.declared(MINTED, CANONICAL, NOW));

    try (GraphStore rebuilt = new TinkerGraphStore()) {
      GraphProjector.project(log, rebuilt, IdentityMerge.NONE);

      assertThat(rebuilt.edges(CANONICAL))
          .singleElement()
          .extracting(EdgeRecord::fromQid, EdgeRecord::toQid)
          .as("half a carry is a half-merge - the to-side has to be rewritten as well as the from")
          .containsExactly(NEIGHBOUR, CANONICAL);
    }
  }

  @Test
  @DisplayName(
      "should carry the rating on replay, repairing a merge logged when nothing carried it")
  void shouldCarryTheRatingOnReplayRepairingAMergeLoggedWhenNothingCarriedIt() {
    IngestService blind = new IngestService(log, graph, IdentityMerge.NONE);
    blind.record(LocalEntity.minted(MINTED, NodeKind.PERSON, "a minted person", NOW));
    affinity.put(new AffinityRecord(MINTED, 5, null, NOW));
    blind.record(SameAs.declared(MINTED, CANONICAL, NOW));
    assertThat(affinity.find(CANONICAL)).as("the precondition: nothing carried it").isEmpty();

    try (GraphStore rebuilt = new TinkerGraphStore()) {
      GraphProjector.project(log, rebuilt, IdentityMerge.carryingRatings(affinity));
    }

    assertThat(affinity.find(CANONICAL).orElseThrow().rating())
        .as("the graph half self-heals on replay; without this the taste half never would")
        .isEqualTo(5);
  }

  @Test
  @DisplayName(
      "should leave a rating the owner made against the canonical id after the merge alone")
  void shouldLeaveARatingTheOwnerMadeAgainstTheCanonicalIdAfterTheMergeAlone() {
    ingest.record(LocalEntity.minted(MINTED, NodeKind.PERSON, "a minted person", NOW));
    affinity.put(new AffinityRecord(MINTED, 5, null, NOW));
    ingest.record(SameAs.declared(MINTED, CANONICAL, NOW));
    affinity.put(new AffinityRecord(CANONICAL, 2, null, LATER));

    try (GraphStore rebuilt = new TinkerGraphStore()) {
      GraphProjector.project(log, rebuilt, IdentityMerge.carryingRatings(affinity));
    }

    assertThat(affinity.find(CANONICAL).orElseThrow().rating())
        .as("a replayed carry must not undo what the owner said after the merge")
        .isEqualTo(2);
  }

  private void mintAndClaimAnEdge() {
    ingest.record(new NodeAssertion(NEIGHBOUR, NodeKind.PERSON, "a sourced person", SOURCE));
    ingest.record(LocalEntity.minted(MINTED, NodeKind.PERSON, "a minted person", NOW));
    ingest.record(OwnerEdge.claimed(MINTED, NEIGHBOUR, "INFLUENCED_BY", NOW));
  }
}
