package com.robsartin.segue.export;

import static com.robsartin.segue.export.InventedGraph.CORRECTED;
import static com.robsartin.segue.export.InventedGraph.MISHEARD;
import static com.robsartin.segue.export.InventedGraph.WATERMARK;
import static com.robsartin.segue.export.InventedGraph.WREN;
import static com.robsartin.segue.export.InventedGraph.merged;
import static com.robsartin.segue.export.InventedGraph.minted;
import static com.robsartin.segue.export.InventedGraph.node;
import static com.robsartin.segue.export.InventedGraph.owned;
import static com.robsartin.segue.export.InventedGraph.retract;
import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.segue.domain.EdgeRecord;
import com.robsartin.segue.domain.Equivalences;
import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.export.InventedGraph.FakeAssertionLog;
import com.robsartin.segue.ingest.GraphProjector;
import com.robsartin.segue.port.IdentityMerge;
import com.robsartin.segue.tinker.TinkerGraphStore;
import com.robsartin.segue.wikidata.KindMapper;
import java.io.IOException;
import java.io.StringWriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Issue #221: a local id merged onto one canonical id and then onto another retires the first
 * canonical id's stand-in — <b>unless a surviving edge still names it</b> (fix round 1 widened the
 * original last-wins-only rule; see {@code Equivalences#stands}).
 *
 * <p><b>Why this is not a case inside {@code BothFoldsAgreeTest}.</b> That test compares the two
 * folds with each other, and until #221 they agreed about the orphan — the exporter's fold built it
 * from {@code Equivalences.standIns} and the boot replay built it a second time from {@code
 * IngestService.standIn}. Two folds that agree about a wrong answer is the one failure comparing
 * them cannot see, so this file looks at the thing itself: it asserts the absence where nothing
 * survives to claim the node, and the survival where something does, on both folds separately and
 * on the DOT artefact, and {@code BothFoldsAgreeTest} gains the twice-merged local id as well so
 * that a half-fix reds there too.
 *
 * <p><b>Three of these were committed {@code @Disabled}, red for the honest reason: the orphan was
 * there.</b> Measured on {@code 2e01341}, the exported fold held {@code MISHEARD} carrying the
 * merged entity's label and no edges, the replayed graph held it too, and the {@code full} DOT drew
 * three nodes under one label for one entity — {@code
 * shouldHoldNoNodeForTheFirstCanonicalIdWhenALaterMergeCorrectedIt}, {@code
 * shouldReplayNoNodeForTheFirstCanonicalIdWhenALaterMergeCorrectedIt} and {@code
 * shouldDrawNoNodeForTheFirstCanonicalIdWhenALaterMergeCorrectedIt} below. {@code
 * shouldKeepTheLabelAndTheEdgesOnTheCorrectedCanonicalIdWhenAMergeIsRedone} was green in both
 * worlds from the start and stayed enabled throughout: it is what says the two folds hold the
 * corrected merge rather than holding nothing, so the absences above mean something.
 *
 * <p><b>Fix round 1 added the surviving-edge case</b> — {@code
 * shouldReplayWithoutThrowingWhenASurvivingEdgeNamesACorrectedCanonicalId} and {@code
 * shouldKeepTheSupersededStandInInTheExportersFoldWhenASurvivingEdgeNamesItToo} — where a canonical
 * id a later merge corrected keeps its stand-in precisely because a surviving edge still names it,
 * so the absence above and the survival here are two faces of one rule rather than a contradiction.
 * {@code shouldKeepNoSupersededStandInAliveWhenTheOnlyNamingEdgeIsRetracted} closes the gap between
 * them: retracting that edge's own endpoint returns the case to the plain absence.
 *
 * <p>Every entity here is invented (ADR 40, issue #37).
 */
class TwiceMergedIdLeavesNoOrphanTest {

  /** Minted, given one owner edge, merged onto the wrong item and then onto the right one. */
  private static FakeAssertionLog correctedLog() {
    return new FakeAssertionLog()
        .with(
            node(WREN, NodeKind.PERSON, "Wren Alderman"),
            minted(CORRECTED, NodeKind.WORK, "A Self-Pressed Record"),
            owned(CORRECTED, WREN, "INFLUENCED_BY"),
            merged(CORRECTED, MISHEARD),
            merged(CORRECTED, WATERMARK));
  }

  @Test
  @DisplayName("the exporter's fold holds no node for a canonical id a later merge corrected")
  void shouldHoldNoNodeForTheFirstCanonicalIdWhenALaterMergeCorrectedIt() {
    assertThat(LogProjection.of(correctedLog()).nodes())
        .as(
            "the edges went to the corrected id, so the first keeps a node with the merged"
                + " entity's label and nothing else - a correction's leftover, not a claim")
        .doesNotContainKey(MISHEARD);
  }

  @Test
  @DisplayName("the boot replay holds no node for a canonical id a later merge corrected")
  void shouldReplayNoNodeForTheFirstCanonicalIdWhenALaterMergeCorrectedIt() {
    try (TinkerGraphStore replayed = new TinkerGraphStore()) {
      GraphProjector.project(correctedLog(), replayed, IdentityMerge.NONE);

      assertThat(replayed.node(MISHEARD))
          .as(
              "the replay builds the stand-in twice - once from Equivalences.standIns before the"
                  + " loop and once from IngestService.standIn at the merge's own row - so fixing"
                  + " the first alone leaves the two folds holding different graphs")
          .isEmpty();
    }
  }

  @Test
  @DisplayName("a full export draws no node for a canonical id a later merge corrected")
  void shouldDrawNoNodeForTheFirstCanonicalIdWhenALaterMergeCorrectedIt() throws IOException {
    FakeAssertionLog log = correctedLog();
    try (TinkerGraphStore graph = new TinkerGraphStore()) {
      GraphProjector.project(log, graph, IdentityMerge.NONE);
      StringWriter out = new StringWriter();

      new DotWriter().write(new ViewSelector(graph, log).full(), out);

      assertThat(out.toString())
          .as(
              "asserted on the artefact somebody keeps and opens in Gephi: one entity drew THREE"
                  + " nodes under one label before #221, and only two of them were claimed")
          .doesNotContain("\"" + MISHEARD + "\"");
    }
  }

  /**
   * Minted, merged onto the wrong item, given an edge naming that wrong item DIRECTLY while it
   * stood as the canonical id, and only then corrected onto the right one (#221 fix round 1).
   * {@code OwnRun} would have offered {@link InventedGraph#MISHEARD} as an endpoint the moment the
   * first merge's stand-in existed, so an owner edge naming it directly — not through {@link
   * InventedGraph#CORRECTED} — is a claim the supported flow can produce, on rows ADR 19 forbids
   * deleting.
   */
  private static FakeAssertionLog correctedLogWithASurvivingEdgeOnTheFirstCanonical() {
    return new FakeAssertionLog()
        .with(
            node(WREN, NodeKind.PERSON, "Wren Alderman"),
            minted(CORRECTED, NodeKind.WORK, "A Self-Pressed Record"),
            merged(CORRECTED, MISHEARD),
            owned(WREN, MISHEARD, "INFLUENCED_BY"),
            merged(CORRECTED, WATERMARK));
  }

  @Test
  @DisplayName(
      "replay does not throw, and agrees with the exporter, when a surviving edge names a"
          + " canonical id a later merge corrected")
  void shouldReplayWithoutThrowingWhenASurvivingEdgeNamesACorrectedCanonicalId() {
    FakeAssertionLog log = correctedLogWithASurvivingEdgeOnTheFirstCanonical();

    // The replay exception this reproduces (before the fix): TinkerGraphStore refuses the
    // WREN -> MISHEARD edge because Equivalences.standIns dropped MISHEARD's node entirely —
    // "assertion references unknown entity Q10000900109 - upsert the node first", wrapped as
    // "replay failed at sequence 4" by GraphProjector. A legal, supported-flow append must not
    // make the log unbootable.
    try (TinkerGraphStore replayed = new TinkerGraphStore()) {
      GraphProjector.project(log, replayed, IdentityMerge.NONE);

      assertThat(replayed.node(MISHEARD))
          .as(
              "a surviving edge names MISHEARD directly, so its stand-in is not an orphan and"
                  + " dropping it would leave the edge dangling")
          .hasValueSatisfying(
              node -> {
                assertThat(node.kind()).isEqualTo(NodeKind.WORK);
                assertThat(node.label()).isEqualTo("A Self-Pressed Record");
              });
      assertThat(replayed.edges(MISHEARD))
          .as("and the edge the owner claimed against it replays too")
          .singleElement()
          .extracting(EdgeRecord::fromQid, EdgeRecord::toQid)
          .containsExactly(WREN, MISHEARD);
    }
  }

  @Test
  @DisplayName(
      "a superseded canonical id's stand-in survives the exporter's fold too, where a surviving"
          + " edge names it, and the two folds agree")
  void shouldKeepTheSupersededStandInInTheExportersFoldWhenASurvivingEdgeNamesItToo() {
    FakeAssertionLog log = correctedLogWithASurvivingEdgeOnTheFirstCanonical();

    LogProjection folded = LogProjection.of(log);
    assertThat(folded.nodes())
        .as(
            "the exporter's fold: a surviving edge names MISHEARD directly, so its stand-in is"
                + " not an orphan and dropping it would leave the edge dangling")
        .containsKey(MISHEARD);
    assertThat(folded.nodes().get(MISHEARD).kind()).isEqualTo(NodeKind.WORK);
    assertThat(folded.nodes().get(MISHEARD).label()).isEqualTo("A Self-Pressed Record");
    assertThat(folded.edges().stream().map(TwiceMergedIdLeavesNoOrphanTest::key))
        .as(
            "the edge the owner claimed directly against the then-canonical id, agreeing with the"
                + " boot replay")
        .contains(WREN + " INFLUENCED_BY " + MISHEARD);
    assertThat(folded.danglingEdges())
        .as("retiring the node while keeping the edge would be the defect this test catches")
        .isZero();
  }

  /**
   * The surviving-edge fixture above, but with the edge's own endpoint retracted afterwards (#221
   * fix round 1, LOW finding 4). {@link Equivalences#in} only counts a SURVIVING edge as keeping a
   * superseded stand-in alive — {@code Retractions.survives} drops an edge naming a retracted
   * entity at either end (ADR 44) — so once WREN is gone, nothing claims MISHEARD any more and its
   * stand-in goes back to being retired by the last-wins rule alone.
   */
  private static FakeAssertionLog correctedLogWithARetractedSurvivingEdge() {
    return new FakeAssertionLog()
        .with(
            node(WREN, NodeKind.PERSON, "Wren Alderman"),
            minted(CORRECTED, NodeKind.WORK, "A Self-Pressed Record"),
            merged(CORRECTED, MISHEARD),
            owned(WREN, MISHEARD, "INFLUENCED_BY"),
            merged(CORRECTED, WATERMARK),
            retract(WREN));
  }

  @Test
  @DisplayName("a retracted edge keeps no superseded stand-in alive")
  void shouldKeepNoSupersededStandInAliveWhenTheOnlyNamingEdgeIsRetracted() {
    assertThat(
            Equivalences.standIns(
                correctedLogWithARetractedSurvivingEdge().readAll(), KindMapper::rederive))
        .as(
            "the WREN -> MISHEARD edge no longer survives, so nothing keeps MISHEARD's stand-in"
                + " alive and the last-wins rule alone decides it")
        .containsOnlyKeys(WATERMARK);
  }

  @Test
  @DisplayName("the corrected canonical id keeps the label and every edge when a merge is redone")
  void shouldKeepTheLabelAndTheEdgesOnTheCorrectedCanonicalIdWhenAMergeIsRedone() {
    LogProjection folded = LogProjection.of(correctedLog());

    // Two folds holding nothing would satisfy the three absences above. This is what says the
    // correction landed: the last canonical id is the one with the node and the edge on it, and
    // the local id keeps its own node (ADR 59), drawn as the orphan #178's ruling 3 made it.
    assertThat(folded.nodes()).containsKeys(WATERMARK, CORRECTED, WREN);
    assertThat(folded.nodes().get(WATERMARK).label()).isEqualTo("A Self-Pressed Record");
    assertThat(folded.edges().stream().map(TwiceMergedIdLeavesNoOrphanTest::key))
        .containsExactly(WATERMARK + " INFLUENCED_BY " + WREN);
    assertThat(folded.danglingEdges())
        .as("retiring a stand-in must not leave an edge pointing at a node the fold never made")
        .isZero();
  }

  private static String key(EdgeRecord edge) {
    return edge.fromQid() + " " + edge.typeCode() + " " + edge.toQid();
  }
}
