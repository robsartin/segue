package com.robsartin.segue.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.robsartin.segue.domain.AssertionRecord;
import com.robsartin.segue.domain.LocalEntity;
import com.robsartin.segue.domain.LoggedAssertion;
import com.robsartin.segue.domain.NodeAssertion;
import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.domain.OwnerEdge;
import com.robsartin.segue.domain.Provenance;
import com.robsartin.segue.domain.Retraction;
import com.robsartin.segue.domain.SameAs;
import com.robsartin.segue.port.AssertionLog;
import com.robsartin.segue.port.GraphStore;
import com.robsartin.segue.port.IdentityMerge;
import com.robsartin.segue.sqlite.SqliteAssertionLog;
import com.robsartin.segue.tinker.TinkerGraphStore;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The live write path is one-way and ordered: log first, then graph.
 *
 * <p>The ordering is the whole point (ADR 19). It is deliberately not atomic, and the direction of
 * that non-atomicity is chosen: if the graph write fails, the log is ahead and a restart replays
 * it. The reverse would lose the claim for good. That argument assumes the graph write can
 * eventually succeed; see {@link IngestService#record}'s caveat (#233) for the row where it cannot,
 * exercised by {@code shouldRefuseASourcedEdgeItCannotApplyWhenRecordIsCalled} below.
 */
class IngestServiceTest {

  private static final Provenance WIKIDATA =
      new Provenance("wikidata", "S-1", Instant.parse("2026-08-24T09:00:00Z"), 1.0);

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
  @DisplayName("a node claim lands in the log and the graph")
  void recordsNodeInBoth() {
    NodeAssertion node = new NodeAssertion("Q5593", NodeKind.PERSON, "Pablo Picasso", WIKIDATA);

    ingest.record(node);

    assertThat(log.readAll()).containsExactly(node);
    assertThat(graph.node("Q5593")).isPresent();
  }

  @Test
  @DisplayName("a batch is recorded in order")
  void recordAllPreservesOrder() {
    List<LoggedAssertion> batch =
        List.of(
            new NodeAssertion("Q01", NodeKind.PERSON, "A", WIKIDATA),
            new NodeAssertion("Q02", NodeKind.GROUP, "B", WIKIDATA),
            new AssertionRecord("Q01", "Q02", "MEMBER_OF", null, null, WIKIDATA));

    ingest.recordAll(batch);

    assertThat(log.readAll()).containsExactlyElementsOf(batch);
    assertThat(graph.edgeCount()).isEqualTo(1);
  }

  @Test
  @DisplayName(
      "should refuse a sourced edge it cannot apply when record is called, rather than appending"
          + " one it must keep")
  void shouldRefuseASourcedEdgeItCannotApplyWhenRecordIsCalled() {
    // #233. This method used to be logLeadsTheGraph and asserted the opposite: that the log had
    // already kept a claim the caller was told had failed. The ORDERING that name described is
    // unchanged and is still asserted by liveAndReplayAgree and retractAppendsAndTouchesNoGraph;
    // what changed is that a claim which cannot survive the ordering never enters it.
    AssertionRecord dangling =
        new AssertionRecord("Q0404", "Q0405", "MEMBER_OF", null, null, WIKIDATA);

    assertThatThrownBy(() -> ingest.record(dangling))
        .isInstanceOf(UnknownEndpointException.class)
        .hasMessageContaining("Q0404");

    assertThat(log.readAll()).isEmpty();
  }

  @Test
  @DisplayName("should name both endpoints when a sourced edge names two unknown entities")
  void shouldNameBothEndpointsWhenASourcedEdgeNamesTwoUnknownEntities() {
    // #233 final review, minor 2. requireEndpoint used to throw on the FIRST missing endpoint it
    // checked (fromQid), so an edge with two unknown endpoints named and counted only that one -
    // SegueService.expandEntity's "N endpoint(s)" reason is built from exactly what this exception
    // reports, so undercounting here undercounts there too.
    AssertionRecord bothUnknown =
        new AssertionRecord("Q0406", "Q0407", "MEMBER_OF", null, null, WIKIDATA);

    UnknownEndpointException thrown =
        catchThrowableOfType(UnknownEndpointException.class, () -> ingest.record(bothUnknown));

    assertThat(thrown.getMessage()).contains("Q0406").contains("Q0407");
    assertThat(thrown.endpoints()).containsExactly("Q0406", "Q0407");
    assertThat(log.readAll()).isEmpty();
  }

  @Test
  @DisplayName("should name one endpoint once when a sourced self-loop names one unknown entity")
  void shouldNameOneEndpointOnceWhenASourcedSelfLoopNamesOneUnknownEntity() {
    // requireBothEndpoints guards the self-loop case (fromQid equals toQid) so it is checked
    // once, not reported twice - the javadoc on that method says so and nothing tested it (#233).
    AssertionRecord selfLoop =
        new AssertionRecord("Q0900301", "Q0900301", "MEMBER_OF", null, null, WIKIDATA);

    UnknownEndpointException thrown =
        catchThrowableOfType(UnknownEndpointException.class, () -> ingest.record(selfLoop));

    assertThat(thrown.endpoints()).containsExactly("Q0900301");
    assertThat(log.readAll()).isEmpty();
  }

  @Test
  @DisplayName("should record a self-loop edge when both endpoints name a known entity")
  void shouldRecordASelfLoopEdgeWhenBothEndpointsNameAKnownEntity() {
    ingest.record(new NodeAssertion("Q0900302", NodeKind.PERSON, "Idris Vance", WIKIDATA));

    ingest.record(
        new AssertionRecord("Q0900302", "Q0900302", "INFLUENCED_BY", null, null, WIKIDATA));

    assertThat(graph.edgeCount()).isEqualTo(1);
  }

  @Test
  @DisplayName("should refuse an owner edge it cannot apply when record is called")
  void shouldRefuseAnOwnerEdgeItCannotApplyWhenRecordIsCalled() {
    // #233 review. requireEveryEndpointIsInTheGraph's OwnerEdge arm (record() accepts one today -
    // nothing in production sends it, but MergeWiringTest's sibling path does) had no test of its
    // own; the AssertionRecord case above does not exercise it.
    OwnerEdge dangling =
        OwnerEdge.claimed("Q0404", "Q0405", "MEMBER_OF", Instant.parse("2026-08-31T20:00:00Z"));

    assertThatThrownBy(() -> ingest.record(dangling))
        .isInstanceOf(UnknownEndpointException.class)
        .hasMessageContaining("Q0404");

    assertThat(log.readAll()).isEmpty();
  }

  @Test
  @DisplayName("live ingest and replay produce the same graph")
  void liveAndReplayAgree() {
    // Both go through IngestService.apply. If they ever diverged, a rebuilt graph would
    // silently differ from the one it replaced — the failure ADR 19 exists to prevent.
    ingest.record(new NodeAssertion("Q01", NodeKind.PERSON, "A", WIKIDATA));
    ingest.record(new NodeAssertion("Q02", NodeKind.GROUP, "B", WIKIDATA));
    ingest.record(new AssertionRecord("Q01", "Q02", "MEMBER_OF", null, null, WIKIDATA));

    try (GraphStore rebuilt = new TinkerGraphStore()) {
      GraphProjector.project(log, rebuilt, IdentityMerge.NONE);

      assertThat(rebuilt.edgeCount()).isEqualTo(graph.edgeCount());
      assertThat(rebuilt.node("Q01")).isEqualTo(graph.node("Q01"));
      assertThat(rebuilt.edges("Q01")).hasSameSizeAs(graph.edges("Q01"));
    }
  }

  @Test
  @DisplayName("a retraction is appended to the log and applied to no graph at all")
  void retractAppendsAndTouchesNoGraph() {
    // ADR 44. A retraction has no graph half: GraphStore has no way to remove anything, and
    // widening the port to give it one - for a dev tool, on the port that exists to keep the
    // engine choice reversible - is what ADR 41 already refused. The graph catches up the way
    // ADR 24 says it always does, by being rebuilt from the log.
    ingest.record(new NodeAssertion("Q0900101", NodeKind.PERSON, "Wren Alderman", WIKIDATA));
    Retraction retraction =
        new Retraction("Q0900101", "wrong entity", Instant.parse("2026-08-27T12:00:00Z"));

    IngestService.retract(log, retraction);

    assertThat(log.readAll()).element(1).isEqualTo(retraction);
    assertThat(graph.node("Q0900101"))
        .as("the running graph is stale until the next boot")
        .isPresent();
  }

  @Test
  @DisplayName("record refuses a retraction rather than appending one it cannot apply")
  void recordRefusesARetraction() {
    // record()'s contract is log-then-graph, and there is no graph step for a retraction. It
    // refuses BEFORE appending: a half-done write here would leave a retraction in the log that
    // the caller was told had failed.
    Retraction retraction =
        new Retraction("Q0900101", "wrong entity", Instant.parse("2026-08-27T12:00:00Z"));

    assertThatThrownBy(() -> ingest.record(retraction))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("retract");

    assertThat(log.readAll()).isEmpty();
  }

  @Test
  @DisplayName("should append an owner claim and touch no graph when claim is called")
  void shouldAppendAnOwnerClaimAndTouchNoGraphWhenClaimIsCalled() {
    // The fourth write path, and the second that has no graph half at the moment of writing.
    // The dev-side tool that makes owner claims (#92) must be able to append one without holding
    // a GraphStore it would never legitimately touch - the argument IngestService.retract makes
    // for being static, made again for the same reason. The graph catches up at the next boot
    // (ADR 24), where GraphProjector applies exactly this row through apply().
    LocalEntity minted =
        LocalEntity.minted(
            "Q00900042",
            NodeKind.WORK,
            "a self-pressed record",
            Instant.parse("2026-08-31T20:00:00Z"));

    IngestService.claim(log, minted);

    assertThat(log.readAll()).containsExactly(minted);
    assertThat(graph.node("Q00900042"))
        .as("no graph half: the tool that appends this holds no graph at all")
        .isEmpty();
  }

  @Test
  @DisplayName(
      "should refuse a sourced claim when claim is called, rather than skip its graph half")
  void shouldRefuseASourcedClaimWhenClaimIsCalledRatherThanSkipItsGraphHalf() {
    // A NodeAssertion appended here would be a claim that never reached the running graph, on a
    // path whose caller cannot apply it. record() is the path with both halves.
    NodeAssertion sourced = new NodeAssertion("Q0900101", NodeKind.PERSON, "Ines Marlow", WIKIDATA);

    assertThatThrownBy(() -> IngestService.claim(log, sourced))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("record");

    assertThat(log.readAll()).isEmpty();
  }

  @Test
  @DisplayName("should refuse a sourced edge when claim is called, rather than skip its graph half")
  void shouldRefuseASourcedEdgeWhenClaimIsCalledRatherThanSkipItsGraphHalf() {
    // The AssertionRecord arm of the same guard the NodeAssertion test covers, and it needs its
    // own test rather than the node's: an edge is the shape a caller reaching for claim() is
    // most likely to be holding, because OwnerEdge.toAssertion() produces exactly this type.
    AssertionRecord sourced =
        new AssertionRecord("Q0900101", "Q0900102", "INFLUENCED_BY", null, null, WIKIDATA);

    assertThatThrownBy(() -> IngestService.claim(log, sourced))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("record");

    assertThat(log.readAll()).isEmpty();
  }

  @Test
  @DisplayName("should refuse a retraction when claim is called, because retract already owns it")
  void shouldRefuseARetractionWhenClaimIsCalledBecauseRetractAlreadyOwnsIt() {
    Retraction retraction =
        new Retraction("Q0900101", "wrong entity", Instant.parse("2026-08-31T20:00:00Z"));

    assertThatThrownBy(() -> IngestService.claim(log, retraction))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("retract");

    assertThat(log.readAll()).isEmpty();
  }

  private static final Instant CLAIMED_AT = Instant.parse("2026-08-31T20:00:00Z");

  @Test
  @DisplayName("should refuse a merge when the projection holds no node for its local side")
  void shouldRefuseAMergeWhenTheProjectionHoldsNoNodeForItsLocalSide() {
    // The bypass path #228 measures: OwnRun.declareMerge already refuses this - it reads what the
    // projection has MINTED and still survives - so a log can only carry it if something appended
    // through this method directly. The merge itself boots; what it leaves behind is a canonical id
    // with no stand-in, and the first edge naming that id stops the boot replay on a row ADR 19
    // forbids deleting.
    LocalEntity minted =
        LocalEntity.minted("Q00900042", NodeKind.WORK, "a working title he took back", CLAIMED_AT);
    IngestService.claim(log, minted);
    IngestService.retract(log, new Retraction("Q00900042", "the wrong thing", CLAIMED_AT));

    assertThatThrownBy(
            () ->
                IngestService.claim(log, SameAs.declared("Q00900042", "Q10000900120", CLAIMED_AT)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Q00900042")
        .hasMessageContaining("Q10000900120")
        .hasMessageContaining("holds no node");

    assertThat(log.readAll())
        .as("validated BEFORE the append, so the log never carries a row that cannot boot")
        .hasSize(2);
  }

  @Test
  @DisplayName("should append a merge when the projection does hold a node for its local side")
  void shouldAppendAMergeWhenTheProjectionHoldsANodeForItsLocalSide() {
    // Without this the refusal above would be satisfied by refusing every merge.
    LocalEntity minted =
        LocalEntity.minted("Q00900042", NodeKind.WORK, "a self-pressed record", CLAIMED_AT);
    IngestService.claim(log, minted);
    SameAs merge = SameAs.declared("Q00900042", "Q10000900120", CLAIMED_AT);

    IngestService.claim(log, merge);

    assertThat(log.readAll()).containsExactly(minted, merge);
  }

  @Test
  @DisplayName(
      "should refuse a second merge when the projection holds no node for the local side, the"
          + " issue's literal shape")
  void shouldRefuseASecondMergeWhenTheProjectionHoldsNoNodeForTheLocalSideTheIssuesLiteralShape() {
    // Break 1's exact shape: minted(L), merged(L->A), retract(L), merged(L->B). Task 4 tested the
    // mechanically equivalent mint/retract/merge; this is the sequence the issue actually names -
    // a first merge that succeeds while L still holds a node, then a retraction of L, then a
    // second merge that must not, because L's node no longer survives.
    LocalEntity minted =
        LocalEntity.minted("Q00900042", NodeKind.WORK, "a working title he took back", CLAIMED_AT);
    IngestService.claim(log, minted);
    IngestService.claim(log, SameAs.declared("Q00900042", "Q10000900120", CLAIMED_AT));
    IngestService.retract(log, new Retraction("Q00900042", "the wrong thing", CLAIMED_AT));

    assertThatThrownBy(
            () ->
                IngestService.claim(log, SameAs.declared("Q00900042", "Q10000900121", CLAIMED_AT)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Q00900042")
        .hasMessageContaining("Q10000900121")
        .hasMessageContaining("holds no node");

    assertThat(log.readAll())
        .as("validated BEFORE the append, so the log never carries a row that cannot boot")
        .hasSize(3);
  }

  @Test
  @DisplayName("should refuse an owner edge when the fold would hold no node for an endpoint")
  void shouldRefuseAnOwnerEdgeWhenTheFoldWouldHoldNoNodeForAnEndpoint() {
    LocalEntity minted =
        LocalEntity.minted("Q00900042", NodeKind.WORK, "a self-pressed record", CLAIMED_AT);
    IngestService.claim(log, minted);

    assertThatThrownBy(
            () ->
                IngestService.claim(
                    log, OwnerEdge.claimed("Q00900042", "Q0900199", "INFLUENCED_BY", CLAIMED_AT)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Q0900199")
        .hasMessageContaining("holds no node");

    assertThat(log.readAll()).hasSize(1);
  }

  @Test
  @DisplayName("should append an owner edge naming a merged local id, which the fold resolves")
  void shouldAppendAnOwnerEdgeNamingAMergedLocalIdWhichTheFoldResolves() {
    // Spec ruling 2: "a later claim naming the local id, by a path that bypasses the tool, folds
    // onto the canonical id like any other". OwnRun refuses this by name, as a courtesy; the gate
    // must not, because the fold resolves it onto an id that HAS a stand-in and the log boots.
    // A gate that asked about the raw endpoint would refuse a claim both folds can project.
    ingest.record(new NodeAssertion("Q0900101", NodeKind.PERSON, "Ines Marlow", WIKIDATA));
    IngestService.claim(
        log, LocalEntity.minted("Q00900042", NodeKind.WORK, "a self-pressed record", CLAIMED_AT));
    IngestService.claim(log, SameAs.declared("Q00900042", "Q10000900120", CLAIMED_AT));
    OwnerEdge edge = OwnerEdge.claimed("Q0900101", "Q00900042", "INFLUENCED_BY", CLAIMED_AT);

    IngestService.claim(log, edge);

    assertThat(log.readAll()).hasSize(4).endsWith(edge);
  }

  @Test
  @DisplayName(
      "should append an owner edge whose folded endpoint is held only by a legacy bypass merge"
          + " the gate never saw")
  void shouldAppendAnOwnerEdgeWhoseFoldedEndpointIsHeldOnlyThroughALegacyBypassMerge() {
    // Fix round 1, review finding 1 (#228). A plant that asks the RAW claimed endpoint instead of
    // the folded one passes every other test in the suite, because in every other test the raw and
    // folded ids happen to agree on whether the fold holds them. This shape forces them to
    // disagree: Q00900042 (raw) is never held; Q10000900120 (folded) is, but only because a
    // SEPARATE
    // legitimate merge (Q00900043 -> Q10000900120) already gave the canonical id a stand-in before
    // this bypass row was appended.
    ingest.record(new NodeAssertion("Q0900101", NodeKind.PERSON, "Ines Marlow", WIKIDATA));
    IngestService.claim(
        log,
        LocalEntity.minted("Q00900043", NodeKind.WORK, "a properly-claimed record", CLAIMED_AT));
    IngestService.claim(log, SameAs.declared("Q00900043", "Q10000900120", CLAIMED_AT));
    // Appended directly through the log, bypassing claim()'s own gate - the gate would refuse this
    // merge outright, because Q00900042 was never claimed a node. A legacy log written before #228
    // existed could hold exactly this row anyway.
    log.append(SameAs.declared("Q00900042", "Q10000900120", CLAIMED_AT));
    OwnerEdge edge = OwnerEdge.claimed("Q0900101", "Q00900042", "INFLUENCED_BY", CLAIMED_AT);

    IngestService.claim(log, edge);

    assertThat(log.readAll()).hasSize(5).endsWith(edge);
  }

  @Test
  @DisplayName("should name every endpoint the fold holds no node for, not just the first")
  void shouldNameEveryMissingEndpointWhenAnOwnerEdgeNamesTwo() {
    OwnerEdge edge = OwnerEdge.claimed("Q0900301", "Q0900302", "INFLUENCED_BY", CLAIMED_AT);

    assertThatThrownBy(() -> IngestService.claim(log, edge))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Q0900301")
        .hasMessageContaining("Q0900302");

    assertThat(log.readAll()).isEmpty();
  }

  @Test
  @DisplayName(
      "should advise merging onto a canonical-shaped endpoint rather than minting or seeding it")
  void shouldAdviseMergingOntoACanonicalShapedEndpointRatherThanMintingOrSeedingIt() {
    IngestService.claim(
        log, LocalEntity.minted("Q00900050", NodeKind.PERSON, "someone claimed", CLAIMED_AT));
    OwnerEdge edge = OwnerEdge.claimed("Q00900050", "Q10000900199", "INFLUENCED_BY", CLAIMED_AT);

    assertThatThrownBy(() -> IngestService.claim(log, edge))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Q10000900199")
        .hasMessageContaining("merged onto")
        .hasMessageNotContaining("mint or seed");

    assertThat(log.readAll()).hasSize(1);
  }
}
