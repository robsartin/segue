package com.robsartin.segue.export;

import static com.robsartin.segue.export.InventedGraph.ALMANAC;
import static com.robsartin.segue.export.InventedGraph.FORFEIT;
import static com.robsartin.segue.export.InventedGraph.HOLLOW_TIDE;
import static com.robsartin.segue.export.InventedGraph.LAPSE;
import static com.robsartin.segue.export.InventedGraph.WREN;
import static com.robsartin.segue.export.InventedGraph.merged;
import static com.robsartin.segue.export.InventedGraph.minted;
import static com.robsartin.segue.export.InventedGraph.node;
import static com.robsartin.segue.export.InventedGraph.owned;
import static com.robsartin.segue.export.InventedGraph.retract;
import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.segue.domain.EdgeRecord;
import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.export.InventedGraph.FakeAssertionLog;
import com.robsartin.segue.ingest.GraphProjector;
import com.robsartin.segue.port.IdentityMerge;
import com.robsartin.segue.tinker.TinkerGraphStore;
import java.io.IOException;
import java.io.StringWriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Issue #224: retracting a local id the owner had already merged takes the edges that name the
 * stand-in that merge created — and only those.
 *
 * <p><b>Why this is not a case inside {@code BothFoldsAgreeTest}.</b> That test compares the two
 * folds with each other, and here one of them <em>throws</em>: {@code GraphProjector} dies inside
 * the projection before anything is compared, so the comparison never runs and the failure it
 * reports is an exception rather than a difference. A blind spot of that shape needs a test that
 * looks at the thing itself, which is what this file does — each fold on its own, and the DOT
 * artefact beside them. {@code BothFoldsAgreeTest} gains the shape as well, so that a half-fix reds
 * there too.
 *
 * <p><b>Three of these were committed {@code @Disabled}, red for the honest reason: the log would
 * not boot.</b> Measured on {@code 0783492} — the commit that landed #221, so the surviving-edge
 * widening was already in place and does not reach this case:
 *
 * <pre>
 *   Equivalences.standIns(log, KindMapper::rederive)  {}
 *   LogProjection.of(log).nodes()                     [Q0900101]
 *   LogProjection.of(log).edges()                     []
 *   LogProjection.of(log).danglingEdges()              1
 *   GraphProjector.project(log, …)                    IllegalStateException:
 *       replay failed at sequence 5
 *       caused by: assertion references unknown entity Q10000900112 - upsert the node first
 * </pre>
 *
 * <p>{@code shouldKeepTheStandInAndItsEdgeWhenNothingIsRetracted} was green in both worlds and
 * stayed enabled throughout: it is what says the fixture holds the merge and the edge in the first
 * place, so the absences above mean something.
 *
 * <p><b>The two keeping tests are the rule's boundary.</b> A canonical id a source has claimed as a
 * node, and one a surviving merge still stands in for, both keep their node and their edge:
 * retracting the local id reaches what the merge created and nothing else, which is the developer
 * guide's own promise that "what a source claimed about the canonical id is untouched". Both were
 * measured green before the fix and both were seen red against the rule without its exclusions.
 *
 * <p>Every entity here is invented (ADR 40, issue #37).
 */
class RetractedStandInTakesItsEdgesTest {

  /**
   * Minted, merged onto a canonical id no source has claimed, given an owner edge naming that
   * canonical id DIRECTLY — which {@code OwnRun} offers as an endpoint the moment the merge's
   * stand-in exists — and then retracted. The {@code WREN → HOLLOW_TIDE} edge is here so that the
   * graph the fix leaves is not simply an empty one.
   */
  private static FakeAssertionLog retractedAfterMergingLog() {
    return new FakeAssertionLog()
        .with(
            node(WREN, NodeKind.PERSON, "Wren Alderman"),
            node(HOLLOW_TIDE, NodeKind.GROUP, "Hollow Tide"),
            minted(LAPSE, NodeKind.WORK, "a working title he took back"),
            merged(LAPSE, FORFEIT),
            owned(WREN, FORFEIT, "INFLUENCED_BY"),
            owned(WREN, HOLLOW_TIDE, "INFLUENCED_BY"),
            retract(LAPSE));
  }

  @Test
  @DisplayName("the exporter's fold drops the edge naming a stand-in a retraction took away")
  void shouldFoldNoEdgeOntoACanonicalIdWhenTheMergedLocalWasRetracted() {
    LogProjection folded = LogProjection.of(retractedAfterMergingLog());

    assertThat(folded.nodes())
        .as("the merge stopped projecting, so nothing holds a node for the canonical id")
        .doesNotContainKey(FORFEIT);
    assertThat(folded.edges().stream().map(RetractedStandInTakesItsEdgesTest::key))
        .as("the edge that named it goes with it, rather than being counted as a dangling edge")
        .containsExactly(WREN + " INFLUENCED_BY " + HOLLOW_TIDE);
    assertThat(folded.danglingEdges())
        .as(
            "danglingEdges is the count whose own javadoc says it should always be zero, because a"
                + " log holding one fails replay at boot - it read 1 before this fix")
        .isZero();
  }

  @Test
  @DisplayName("the boot replay survives a log that retracts a merged local id")
  void shouldReplayWithoutThrowingWhenAMergedLocalIdIsRetracted() {
    try (TinkerGraphStore replayed = new TinkerGraphStore()) {
      GraphProjector.project(retractedAfterMergingLog(), replayed, IdentityMerge.NONE);

      assertThat(replayed.node(FORFEIT))
          .as("no node under the canonical id, so the edge naming it cannot be applied")
          .isEmpty();
      assertThat(replayed.node(LAPSE)).as("and none under the retracted local id").isEmpty();
      assertThat(replayed.edgeCount())
          .as("the owner's other edge is untouched, so this is not an empty graph agreeing")
          .isEqualTo(1);
    }
  }

  @Test
  @DisplayName("a full export draws no node for a canonical id whose merge was retracted")
  void shouldDrawNoNodeForACanonicalIdWhenItsMergeWasRetracted() throws IOException {
    FakeAssertionLog log = retractedAfterMergingLog();
    try (TinkerGraphStore graph = new TinkerGraphStore()) {
      GraphProjector.project(log, graph, IdentityMerge.NONE);
      StringWriter out = new StringWriter();

      new DotWriter().write(new ViewSelector(graph, log).full(), out);

      assertThat(out.toString())
          .as("asserted on the artefact somebody keeps and opens in Gephi weeks later")
          .doesNotContain("\"" + FORFEIT + "\"");
    }
  }

  /**
   * The same shape with a source claiming the canonical id as a node of its own, before the merge.
   * Retracting the local id must leave that claim and every edge naming it exactly where they are.
   */
  private static FakeAssertionLog retractedAfterMergingOntoAClaimedIdLog() {
    return new FakeAssertionLog()
        .with(
            node(WREN, NodeKind.PERSON, "Wren Alderman"),
            node(FORFEIT, NodeKind.GROUP, "the name the source already had"),
            minted(LAPSE, NodeKind.WORK, "a working title he took back"),
            merged(LAPSE, FORFEIT),
            owned(WREN, FORFEIT, "INFLUENCED_BY"),
            retract(LAPSE));
  }

  @Test
  @DisplayName("an edge naming a canonical id a source claimed survives the local id's retraction")
  void shouldKeepAnEdgeNamingACanonicalIdASourceClaimedWhenTheMergedLocalIsRetracted() {
    LogProjection folded = LogProjection.of(retractedAfterMergingOntoAClaimedIdLog());

    assertThat(folded.nodes())
        .as("the source's node claim is untouched by a retraction of the owner's local id")
        .containsKey(FORFEIT);
    assertThat(folded.edges().stream().map(RetractedStandInTakesItsEdgesTest::key))
        .as("so the edge naming it is untouched too - the guide's own promise")
        .containsExactly(WREN + " INFLUENCED_BY " + FORFEIT);
  }

  /**
   * Two local ids merged onto ONE canonical id, and only one of them retracted. The other merge
   * still names the stand-in, so the id is not emptied and the edge naming it stays.
   */
  private static FakeAssertionLog oneOfTwoMergesRetractedLog() {
    return new FakeAssertionLog()
        .with(
            node(WREN, NodeKind.PERSON, "Wren Alderman"),
            minted(LAPSE, NodeKind.WORK, "a working title he took back"),
            minted(ALMANAC, NodeKind.WORK, "The Salt Almanac"),
            merged(LAPSE, FORFEIT),
            merged(ALMANAC, FORFEIT),
            owned(WREN, FORFEIT, "INFLUENCED_BY"),
            retract(LAPSE));
  }

  @Test
  @DisplayName("an edge naming a canonical id a surviving merge still stands in for is kept")
  void shouldKeepAnEdgeNamingACanonicalIdWhenAnotherMergeStillStandsInForIt() {
    LogProjection folded = LogProjection.of(oneOfTwoMergesRetractedLog());

    assertThat(folded.nodes())
        .as("the second merge's stand-in is what the id has now, and it is not the retracted one")
        .containsKey(FORFEIT);
    assertThat(folded.nodes().get(FORFEIT).label()).isEqualTo("The Salt Almanac");
    assertThat(folded.edges().stream().map(RetractedStandInTakesItsEdgesTest::key))
        .containsExactly(WREN + " INFLUENCED_BY " + FORFEIT);
    assertThat(folded.danglingEdges()).isZero();
  }

  /**
   * The edge appended <b>after</b> the retraction, naming the emptied canonical id. The rule is
   * position-blind and withdraws it too — see {@code Equivalences.retractedStandIns}, and the
   * design spec's 2026-09-03 amendment for why a backwards-only rule was refused: it would leave
   * exactly this log unbootable, which is the defect the whole issue exists to close.
   */
  private static FakeAssertionLog edgeClaimedAfterTheRetractionLog() {
    return new FakeAssertionLog()
        .with(
            node(WREN, NodeKind.PERSON, "Wren Alderman"),
            minted(LAPSE, NodeKind.WORK, "a working title he took back"),
            merged(LAPSE, FORFEIT),
            retract(LAPSE),
            owned(WREN, FORFEIT, "INFLUENCED_BY"));
  }

  @Test
  @DisplayName("the exporter withdraws an edge naming an emptied id even when it came later")
  void shouldFoldNoEdgeOntoAnEmptiedCanonicalIdWhenTheEdgeWasClaimedAfterTheRetraction() {
    LogProjection folded = LogProjection.of(edgeClaimedAfterTheRetractionLog());

    assertThat(folded.nodes())
        .as("nothing holds a node for the canonical id, whatever order the rows arrived in")
        .doesNotContainKey(FORFEIT);
    assertThat(folded.edges()).as("and the edge naming it goes with it").isEmpty();
    assertThat(folded.danglingEdges())
        .as("a backwards-only rule would leave this at 1, which is a log that cannot boot")
        .isZero();
  }

  @Test
  @DisplayName("the boot replay survives an edge claimed after the retraction that emptied its id")
  void shouldReplayWithoutThrowingWhenTheEdgeWasClaimedAfterTheRetraction() {
    try (TinkerGraphStore replayed = new TinkerGraphStore()) {
      GraphProjector.project(edgeClaimedAfterTheRetractionLog(), replayed, IdentityMerge.NONE);

      assertThat(replayed.node(FORFEIT)).isEmpty();
      assertThat(replayed.edgeCount())
          .as("the edge has no endpoint to be applied against, so the graph holds none")
          .isZero();
      assertThat(replayed.node(WREN))
          .as("and the rest of the log is untouched, so this is not an empty graph agreeing")
          .isPresent();
    }
  }

  /** The same fixture with nothing retracted: the merge stands and the edge is on the graph. */
  private static FakeAssertionLog mergedAndNotRetractedLog() {
    return new FakeAssertionLog()
        .with(
            node(WREN, NodeKind.PERSON, "Wren Alderman"),
            node(HOLLOW_TIDE, NodeKind.GROUP, "Hollow Tide"),
            minted(LAPSE, NodeKind.WORK, "a working title he took back"),
            merged(LAPSE, FORFEIT),
            owned(WREN, FORFEIT, "INFLUENCED_BY"),
            owned(WREN, HOLLOW_TIDE, "INFLUENCED_BY"));
  }

  @Test
  @DisplayName("the stand-in and its edge are both there while nothing is retracted")
  void shouldKeepTheStandInAndItsEdgeWhenNothingIsRetracted() {
    LogProjection folded = LogProjection.of(mergedAndNotRetractedLog());

    assertThat(folded.nodes())
        .as("without this the absences above would hold over a fixture that never had them")
        .containsKey(FORFEIT);
    assertThat(folded.nodes().get(FORFEIT).label()).isEqualTo("a working title he took back");
    assertThat(folded.edges().stream().map(RetractedStandInTakesItsEdgesTest::key))
        .contains(WREN + " INFLUENCED_BY " + FORFEIT);
    assertThat(folded.danglingEdges()).isZero();
  }

  private static String key(EdgeRecord edge) {
    return edge.fromQid() + " " + edge.typeCode() + " " + edge.toQid();
  }
}
