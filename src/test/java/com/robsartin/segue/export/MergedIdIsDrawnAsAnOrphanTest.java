package com.robsartin.segue.export;

import static com.robsartin.segue.export.InventedGraph.ALMANAC;
import static com.robsartin.segue.export.InventedGraph.HOLLOW_TIDE;
import static com.robsartin.segue.export.InventedGraph.PRESSING;
import static com.robsartin.segue.export.InventedGraph.PRIZE;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Issue #178, controller ruling 3: <b>a merged local id that has lost its edges is drawn as it
 * is</b> — a node with no edges, visible in a {@code full} or {@code subgraph} export like any
 * orphan, on the retraction chapter's precedent. Nothing hides it.
 *
 * <p><b>Why this file exists at all.</b> The Mikado probe went looking for the export expectation
 * the fold would break and found none: outside {@code BothFoldsAgreeTest} and {@code
 * InventedGraph}, nothing in {@code export} mentioned a merge, so the DOT and GraphML writers had
 * no merged fixture to break. That is a coverage gap rather than an absence of risk — the ruling
 * above has been made and, once the fold lands, it is the visible behaviour of the artefact
 * somebody keeps and opens in Gephi weeks later.
 *
 * <p><b>The first test below was committed {@code @Disabled}, red for the honest opposite reason:
 * the local id still had its edges.</b> {@code IngestService.carry} and {@code LogProjection.carry}
 * <em>copied</em> them onto the canonical id rather than moving them, so the merged entity was
 * drawn twice over. Issue #178 and {@code docs/superpowers/specs/2026-09-02-merge-degree-design.md}
 * carry the measurement and the ruling. Both folds now read every endpoint through {@code
 * Equivalences.foldEndpoints}, and the annotation came off with the fold.
 *
 * <p>It was the second of <b>two</b> guards parked red for exactly as long as the defect stood —
 * the other is {@code MergeDoesNotInflateDegreeTest} — and the gate is what says both came off:
 * <b>2 skipped</b> while they were parked, <b>0</b> now. A skip count above zero would have meant
 * the fold shipped with its own guards switched off.
 *
 * <p>The second test is not disabled and is green in both worlds. It is the half of ruling 3 that
 * does not depend on the fold — that an isolated node is drawn rather than filtered out — so that
 * "drawn as an orphan" is a claim about this exporter and not a hope.
 *
 * <p>Every entity here is invented (ADR 40, issue #37).
 */
class MergedIdIsDrawnAsAnOrphanTest {

  /** A minted entity with an owner edge out of it and one in to it, then merged. */
  private static FakeAssertionLog mergedLog() {
    return new FakeAssertionLog()
        .with(
            node(WREN, NodeKind.PERSON, "Wren Alderman"),
            node(HOLLOW_TIDE, NodeKind.GROUP, "Hollow Tide"),
            node(PRIZE, NodeKind.CONCEPT, "The Invented Prize"),
            minted(ALMANAC, NodeKind.WORK, "The Salt Almanac"),
            owned(ALMANAC, WREN, "INFLUENCED_BY"),
            owned(HOLLOW_TIDE, ALMANAC, "INFLUENCED_BY"),
            merged(ALMANAC, PRESSING));
  }

  @Test
  @DisplayName(
      "a merged local id keeps its node and loses its edges, which all sit on the canonical id")
  void shouldDrawTheMergedLocalIdAsANodeWithNoEdges() {
    LogProjection folded = LogProjection.of(mergedLog());

    assertThat(folded.nodes())
        .as("the node stays: a route or a log entry recorded last month still names it (ADR 59)")
        .containsKey(ALMANAC);
    assertThat(folded.edges().stream().map(MergedIdIsDrawnAsAnOrphanTest::key))
        .as("an equivalence is not new evidence, so one entity's edges belong to one node")
        .containsExactlyInAnyOrder(
            PRESSING + " INFLUENCED_BY " + WREN, HOLLOW_TIDE + " INFLUENCED_BY " + PRESSING);
    assertThat(folded.danglingEdges())
        .as("moving an endpoint must not lose an edge to a node the fold never made")
        .isZero();
  }

  @Test
  @DisplayName("a node with no edges is drawn in a full export, so nothing hides an orphan")
  void shouldDrawANodeThatHasNoEdgesAtAll() throws IOException {
    FakeAssertionLog log = mergedLog();
    try (TinkerGraphStore graph = new TinkerGraphStore()) {
      GraphProjector.project(log, graph, IdentityMerge.NONE);
      StringWriter out = new StringWriter();

      new DotWriter().write(new ViewSelector(graph, log).full(), out);

      assertThat(out.toString())
          .as(
              "PRIZE is claimed by a source and reached by nothing; if the exporter filtered"
                  + " isolated nodes, ruling 3's \"drawn as an orphan\" would be a hope rather than"
                  + " a description")
          .contains(PRIZE);
    }
  }

  private static String key(EdgeRecord edge) {
    return edge.fromQid() + " " + edge.typeCode() + " " + edge.toQid();
  }
}
