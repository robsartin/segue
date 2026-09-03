package com.robsartin.segue.export;

import static com.robsartin.segue.export.InventedGraph.BYPASS;
import static com.robsartin.segue.export.InventedGraph.STANDING;
import static com.robsartin.segue.export.InventedGraph.UNKNOWN_CLASS;
import static com.robsartin.segue.export.InventedGraph.merged;
import static com.robsartin.segue.export.InventedGraph.node;
import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.export.InventedGraph.FakeAssertionLog;
import com.robsartin.segue.ingest.GraphProjector;
import com.robsartin.segue.port.IdentityMerge;
import com.robsartin.segue.tinker.TinkerGraphStore;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A merge's stand-in node and the node it stands in for are one entity, so they cannot be two kinds
 * (#222, ADR 59's first residual).
 *
 * <p><b>Deliberately not a case in {@code BothFoldsAgreeTest}.</b> That test compares the two folds
 * to <em>each other</em>, and this defect is invisible there because both folds are wrong in the
 * same direction: each re-derives the local node's kind through {@code KindMapper.rederive} (ADR
 * 42) and each read the stand-in's kind off the claim as stated. What is compared here is the
 * stand-in against the node beside it, <em>within</em> one fold - asked twice, once of each fold,
 * because a fix that reached only one of them would put the two folds back where #178 found them.
 *
 * <p><b>The control assertion is not decoration.</b> Two nodes that were both left un-re-derived
 * would agree perfectly, so each test first says that the fold did re-derive the local node. That
 * is {@code BothFoldsAgreeTest}'s own "two empty sets agree about nothing" argument, applied to a
 * comparison of two kinds.
 *
 * <p><b>{@code BYPASS} is the path this is about.</b> Spec ruling 2 refuses to assume that a claim
 * naming a merged local id came through {@code OwnCli}, so a plain {@link
 * com.robsartin.segue.domain.NodeAssertion} can name one - and unlike a minted entity it can carry
 * classes, which is what gives the fold something to re-derive from.
 */
class StandInKindMatchesTheLocalNodeTest {

  /** A bypass claim stating a kind, and one class that contradicts it. */
  private static FakeAssertionLog bypassLog() {
    return new FakeAssertionLog()
        .with(
            node(BYPASS, NodeKind.WORK, "a local-shaped id a source named", List.of(UNKNOWN_CLASS)),
            merged(BYPASS, STANDING));
  }

  @Test
  @DisplayName("the exporter's stand-in takes the kind the fold re-derived for the local node")
  void shouldGiveTheStandInTheRederivedKindWhenTheExporterFoldsABypassClaimStatingClasses() {
    LogProjection folded = LogProjection.of(bypassLog());

    assertThat(folded.nodes().get(BYPASS).kind())
        .as("control: the fold re-derived the local node, so there is a disagreement to find")
        .isEqualTo(NodeKind.CONCEPT);
    assertThat(folded.nodes().get(STANDING).kind())
        .as("a stand-in is the same entity as the node it stands in for, so it is the same kind")
        .isEqualTo(folded.nodes().get(BYPASS).kind());
  }

  @Test
  @DisplayName("the boot replay's stand-in takes the kind the fold re-derived for the local node")
  void shouldGiveTheStandInTheRederivedKindWhenTheBootReplaySeesABypassClaimStatingClasses() {
    FakeAssertionLog log = bypassLog();

    try (TinkerGraphStore replayed = new TinkerGraphStore()) {
      GraphProjector.project(log, replayed, IdentityMerge.NONE);

      assertThat(replayed.node(BYPASS).orElseThrow().kind())
          .as("control: replay re-derived the local node, so there is a disagreement to find")
          .isEqualTo(NodeKind.CONCEPT);
      assertThat(replayed.node(STANDING).orElseThrow().kind())
          .as("a stand-in is the same entity as the node it stands in for, so it is the same kind")
          .isEqualTo(replayed.node(BYPASS).orElseThrow().kind());
    }
  }
}
