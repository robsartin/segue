package com.robsartin.segue.export;

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
import org.junit.jupiter.api.Disabled;
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
  @Disabled("#224: red until the fold rule lands - see this class's javadoc")
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
  @Disabled("#224: red until the fold rule lands - see this class's javadoc")
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
  @Disabled("#224: red until the fold rule lands - see this class's javadoc")
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
