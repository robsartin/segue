package com.robsartin.segue.export;

import static com.robsartin.segue.export.InventedGraph.CORRECTED;
import static com.robsartin.segue.export.InventedGraph.MISHEARD;
import static com.robsartin.segue.export.InventedGraph.WATERMARK;
import static com.robsartin.segue.export.InventedGraph.WREN;
import static com.robsartin.segue.export.InventedGraph.merged;
import static com.robsartin.segue.export.InventedGraph.minted;
import static com.robsartin.segue.export.InventedGraph.node;
import static com.robsartin.segue.export.InventedGraph.owned;
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
 * Issue #221: a local id merged onto one canonical id and then onto another leaves nothing behind
 * under the first.
 *
 * <p><b>Why this is not a case inside {@code BothFoldsAgreeTest}.</b> That test compares the two
 * folds with each other, and until #221 they agreed about the orphan — the exporter's fold built it
 * from {@code Equivalences.standIns} and the boot replay built it a second time from {@code
 * IngestService.standIn}. Two folds that agree about a wrong answer is the one failure comparing
 * them cannot see, so this file looks at the thing itself: it asserts the absence, on both folds
 * separately and on the DOT artefact, and {@code BothFoldsAgreeTest} gains the twice-merged local
 * id as well so that a half-fix reds there too.
 *
 * <p><b>Three of these were committed {@code @Disabled}, red for the honest reason: the orphan was
 * there.</b> Measured on {@code 2e01341}, the exported fold held {@code MISHEARD} carrying the
 * merged entity's label and no edges, the replayed graph held it too, and the {@code full} DOT drew
 * three nodes under one label for one entity. The fourth test below is green in both worlds and
 * stays enabled: it is what says the two folds hold the corrected merge rather than holding
 * nothing, so the absences above mean something.
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
  @Disabled("red until #221 retires the superseded stand-in — see this class's javadoc")
  @DisplayName("the exporter's fold holds no node for a canonical id a later merge corrected")
  void shouldHoldNoNodeForTheFirstCanonicalIdWhenALaterMergeCorrectedIt() {
    assertThat(LogProjection.of(correctedLog()).nodes())
        .as(
            "the edges went to the corrected id, so the first keeps a node with the merged"
                + " entity's label and nothing else - a correction's leftover, not a claim")
        .doesNotContainKey(MISHEARD);
  }

  @Test
  @Disabled("red until #221 retires the superseded stand-in — see this class's javadoc")
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
  @Disabled("red until #221 retires the superseded stand-in — see this class's javadoc")
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
