package com.robsartin.segue.export;

import static com.robsartin.segue.export.InventedGraph.FORFEIT;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Issue #228: a merge appended <em>after</em> its local side was retracted, and what the two folds
 * make of an edge that then names either end of it.
 *
 * <p><b>Two shapes, and only one of them has a fold rule.</b> Where the second merge names the same
 * canonical id the retraction emptied, an edge naming the local id folds onto that emptied id and
 * is withdrawn — the rule ADR 44's 2026-09-03 amendment already states, applied to the case its
 * implementation missed, because {@code Equivalences.namesARetractedStandIn} read the claim's raw
 * endpoints before the fold resolved them. Where the second merge names a <em>different</em>
 * canonical id, nothing retracted that id and no fold rule can honestly reach it: the merge has no
 * local side, so no stand-in is built, and an edge naming it is a live claim about an entity
 * nothing describes. That log is refused before the append and, if a log already holds one, named
 * at boot — see the tests at the bottom of this file and ADR 59's 2026-09-04 amendment.
 *
 * <p>Every entity here is invented (ADR 40, issue #37).
 */
class MergeAfterARetractionTest {

  /**
   * Minted, merged, retracted, then merged again onto the <b>same</b> canonical id, with an owner
   * edge naming the retracted local id afterwards. The edge survives on its own terms — neither
   * {@code WREN} nor {@code LAPSE} is retracted at its position — and folds onto {@code FORFEIT},
   * which {@code Equivalences.retractedStandIns} has already emptied.
   *
   * <p>Measured on {@code a7c3455}: {@code retractedStandIns} names {@code Q10000900112}, {@code
   * standIns} is empty, the exporter's fold reports {@code danglingEdges 1} and {@code
   * withdrawnEdges 0}, and {@code GraphProjector.project} throws {@code replay failed at sequence
   * 6}, {@code assertion references unknown entity Q10000900112 - upsert the node first}.
   */
  private static FakeAssertionLog remergedOntoTheEmptiedIdLog() {
    return new FakeAssertionLog()
        .with(
            node(WREN, NodeKind.PERSON, "Wren Alderman"),
            minted(LAPSE, NodeKind.WORK, "a working title he took back"),
            merged(LAPSE, FORFEIT),
            retract(LAPSE),
            merged(LAPSE, FORFEIT),
            owned(WREN, LAPSE, "INFLUENCED_BY"));
  }

  @Test
  @DisplayName("the exporter withdraws an edge that folds onto a canonical id a retraction emptied")
  void shouldWithdrawAnEdgeThatFoldsOntoAnEmptiedCanonicalIdRatherThanDangle() {
    LogProjection folded = LogProjection.of(remergedOntoTheEmptiedIdLog());

    assertThat(folded.nodes())
        .as("the retraction took the only node the canonical id ever had")
        .doesNotContainKey(FORFEIT);
    assertThat(folded.edges())
        .as("and the edge that reaches it through the merge goes with it")
        .isEmpty();
    assertThat(folded.withdrawnEdges())
        .as("counted as a withdrawal, which is what the export says out loud (#224)")
        .isEqualTo(1);
    assertThat(folded.danglingEdges())
        .as(
            "and NOT as dangling - that count is the alarm for a log that cannot boot, and it read"
                + " 1 before this fix")
        .isZero();
  }

  @Test
  @DisplayName(
      "the boot replay survives an edge that folds onto a canonical id a retraction emptied")
  void shouldReplayWithoutThrowingWhenAnEdgeFoldsOntoAnEmptiedCanonicalId() {
    try (TinkerGraphStore replayed = new TinkerGraphStore()) {
      GraphProjector.project(remergedOntoTheEmptiedIdLog(), replayed, IdentityMerge.NONE);

      assertThat(replayed.node(FORFEIT)).isEmpty();
      assertThat(replayed.edgeCount())
          .as("the edge has no endpoint to be applied against, so the graph holds none")
          .isZero();
      assertThat(replayed.node(WREN))
          .as("and the rest of the log is untouched, so this is not an empty graph agreeing")
          .isPresent();
    }
  }

  /**
   * The same log with nothing retracted: the merge stands and the edge lands on its canonical id.
   */
  private static FakeAssertionLog mergedAndNotRetractedLog() {
    return new FakeAssertionLog()
        .with(
            node(WREN, NodeKind.PERSON, "Wren Alderman"),
            minted(LAPSE, NodeKind.WORK, "a working title he took back"),
            merged(LAPSE, FORFEIT),
            owned(WREN, LAPSE, "INFLUENCED_BY"));
  }

  @Test
  @DisplayName(
      "an edge naming a merged local id still folds onto the canonical id when nothing is retracted")
  void shouldFoldTheEdgeOntoTheCanonicalIdWhenNothingIsRetracted() {
    LogProjection folded = LogProjection.of(mergedAndNotRetractedLog());

    assertThat(folded.nodes())
        .as("without this the absences above would hold over a fixture that never had them")
        .containsKey(FORFEIT);
    assertThat(folded.edges().stream().map(MergeAfterARetractionTest::key))
        .containsExactly(WREN + " INFLUENCED_BY " + FORFEIT);
    assertThat(folded.withdrawnEdges())
        .as("so the count above reports the withdrawal and is not a non-zero constant")
        .isZero();
  }

  private static String key(EdgeRecord edge) {
    return edge.fromQid() + " " + edge.typeCode() + " " + edge.toQid();
  }
}
