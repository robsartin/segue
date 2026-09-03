package com.robsartin.segue.export;

import static com.robsartin.segue.export.InventedGraph.ALMANAC;
import static com.robsartin.segue.export.InventedGraph.HOLLOW_TIDE;
import static com.robsartin.segue.export.InventedGraph.KETTLES;
import static com.robsartin.segue.export.InventedGraph.PRESSING;
import static com.robsartin.segue.export.InventedGraph.WREN;
import static com.robsartin.segue.export.InventedGraph.edge;
import static com.robsartin.segue.export.InventedGraph.merged;
import static com.robsartin.segue.export.InventedGraph.minted;
import static com.robsartin.segue.export.InventedGraph.node;
import static com.robsartin.segue.export.InventedGraph.secondSource;
import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.segue.domain.EdgeRecord;
import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.domain.Retraction;
import com.robsartin.segue.export.InventedGraph.FakeAssertionLog;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LogProjectionTest {

  private static final Instant WHEN_RETRACTED = Instant.parse("2026-02-01T00:00:00Z");

  @Test
  @DisplayName("node claims become nodes, and the last claim about an entity wins")
  void foldsNodeClaims() {
    LogProjection projection =
        LogProjection.of(
            new FakeAssertionLog()
                .with(
                    node(WREN, NodeKind.CONCEPT, "Wren Alderman"),
                    node(WREN, NodeKind.PERSON, "Wren Alderman")));

    assertThat(projection.nodes()).containsOnlyKeys(WREN);
    assertThat(projection.nodes().get(WREN).kind()).isEqualTo(NodeKind.PERSON);
  }

  @Test
  @DisplayName("the exported fold re-derives a node's kind from its stored classes too")
  void rederivesKindFromStoredClasses() {
    // The same correction the boot projection makes (issue #60), because an exported picture
    // that disagreed with the running graph about what a node IS would be worse than no
    // picture: DOT colours and shapes nodes by kind.
    LogProjection projection =
        LogProjection.of(
            new FakeAssertionLog()
                .with(node(KETTLES, NodeKind.CONCEPT, "The Paper Kettles", List.of("Q5741069"))));

    assertThat(projection.nodes().get(KETTLES).kind()).isEqualTo(NodeKind.GROUP);
  }

  @Test
  @DisplayName("two sources asserting one relationship fold into one edge with two provenances")
  void mergesAssertionsIntoOneEdge() {
    LogProjection projection =
        LogProjection.of(
            new FakeAssertionLog()
                .with(
                    node(WREN, NodeKind.PERSON, "Wren Alderman"),
                    node(KETTLES, NodeKind.GROUP, "The Paper Kettles"),
                    edge(WREN, KETTLES, "MEMBER_OF"),
                    edge(WREN, KETTLES, "MEMBER_OF", secondSource())));

    assertThat(projection.edges()).hasSize(1);
    EdgeRecord merged = projection.edges().get(0);
    assertThat(merged.corroboration()).isEqualTo(2);
    assertThat(merged.bestConfidence()).isEqualTo(1.0);
  }

  @Test
  @DisplayName("different relationship types between one pair stay separate edges")
  void keepsTypesApart() {
    LogProjection projection =
        LogProjection.of(
            new FakeAssertionLog()
                .with(
                    node(WREN, NodeKind.PERSON, "Wren Alderman"),
                    node(HOLLOW_TIDE, NodeKind.WORK, "Hollow Tide"),
                    edge(WREN, HOLLOW_TIDE, "AUTHORED"),
                    edge(WREN, HOLLOW_TIDE, "PERFORMED")));

    assertThat(projection.edges()).hasSize(2);
  }

  @Test
  @DisplayName("an edge whose endpoint was never claimed as a node is counted, not emitted")
  void countsDanglingEdges() {
    LogProjection projection =
        LogProjection.of(
            new FakeAssertionLog()
                .with(
                    node(WREN, NodeKind.PERSON, "Wren Alderman"),
                    edge(WREN, KETTLES, "MEMBER_OF")));

    assertThat(projection.edges()).isEmpty();
    assertThat(projection.danglingEdges()).isEqualTo(1);
  }

  @Test
  @DisplayName("an empty log projects to an empty graph rather than to an error")
  void handlesAnEmptyLog() {
    LogProjection projection = LogProjection.of(new FakeAssertionLog());

    assertThat(projection.nodes()).isEmpty();
    assertThat(projection.edges()).isEmpty();
    assertThat(projection.danglingEdges()).isZero();
  }

  @Test
  @DisplayName("a retracted entity leaves the exported fold, edges and all")
  void honoursARetraction() {
    // ADR 44, and the half of it that matters most: the exporter's fold and the boot replay
    // apply the same rule, so a picture of the graph cannot still show edges the graph has
    // dropped. GraphProjectorTest.replayHonoursARetraction is the other half.
    LogProjection projection =
        LogProjection.of(
            new FakeAssertionLog()
                .with(
                    node(WREN, NodeKind.PERSON, "Wren Alderman"),
                    node(KETTLES, NodeKind.GROUP, "The Paper Kettles"),
                    node(HOLLOW_TIDE, NodeKind.GROUP, "Hollow Tide"),
                    edge(WREN, KETTLES, "MEMBER_OF"),
                    edge(WREN, HOLLOW_TIDE, "MEMBER_OF"),
                    new Retraction(WREN, "resolved to the wrong Wren", WHEN_RETRACTED)));

    assertThat(projection.nodes()).containsOnlyKeys(KETTLES, HOLLOW_TIDE);
    assertThat(projection.edges()).isEmpty();
  }

  @Test
  @DisplayName("claims made after a retraction are projected: re-adding is how you come back")
  void honoursClaimsMadeAfterARetraction() {
    LogProjection projection =
        LogProjection.of(
            new FakeAssertionLog()
                .with(
                    node(WREN, NodeKind.CONCEPT, "Wren Alderman"),
                    new Retraction(WREN, "wrong entity", WHEN_RETRACTED),
                    node(WREN, NodeKind.PERSON, "Wren Alderman"),
                    node(KETTLES, NodeKind.GROUP, "The Paper Kettles"),
                    edge(WREN, KETTLES, "MEMBER_OF")));

    assertThat(projection.nodes().get(WREN).kind()).isEqualTo(NodeKind.PERSON);
    assertThat(projection.edges()).hasSize(1);
  }

  @Test
  @DisplayName("a retraction does not make the edges it removed look dangling")
  void aRetractedEdgeIsNotCountedAsDangling() {
    // danglingEdges means "the log names an entity nobody ever claimed as a node", which is a
    // corruption worth reporting. An edge a retraction removed is not that, and counting it
    // there would put a frightening number in front of an operator who did nothing wrong.
    LogProjection projection =
        LogProjection.of(
            new FakeAssertionLog()
                .with(
                    node(WREN, NodeKind.PERSON, "Wren Alderman"),
                    node(KETTLES, NodeKind.GROUP, "The Paper Kettles"),
                    edge(WREN, KETTLES, "MEMBER_OF"),
                    new Retraction(WREN, "wrong entity", WHEN_RETRACTED)));

    assertThat(projection.danglingEdges()).isZero();
  }

  @Test
  @DisplayName("the exported fold keeps the label of a source that named the canonical id first")
  void shouldKeepTheSourcesLabelWhenTheSourceNamedTheCanonicalIdBeforeTheMerge() {
    LogProjection projection =
        LogProjection.of(
            new FakeAssertionLog()
                .with(
                    node(PRESSING, NodeKind.WORK, "what the source calls it"),
                    minted(ALMANAC, NodeKind.WORK, "what the owner called it"),
                    merged(ALMANAC, PRESSING)));

    assertThat(projection.nodes().get(PRESSING).label())
        .as(
            "a stand-in is a placeholder for an entity no source has expanded; overwriting a"
                + " source's label with the owner's working title would be the merge editing the"
                + " world rather than recording an identity")
        .isEqualTo("what the source calls it");
  }

  @Test
  @DisplayName("the exported fold keeps the label of a source that named the canonical id later")
  void shouldKeepTheSourcesLabelWhenTheSourceNamedTheCanonicalIdAfterTheMerge() {
    LogProjection projection =
        LogProjection.of(
            new FakeAssertionLog()
                .with(
                    minted(ALMANAC, NodeKind.WORK, "what the owner called it"),
                    merged(ALMANAC, PRESSING),
                    node(PRESSING, NodeKind.WORK, "what the source calls it")));

    assertThat(projection.nodes().get(PRESSING).label())
        .as("a later node claim overwrites an earlier one, matching upsertNode")
        .isEqualTo("what the source calls it");
  }

  @Test
  @DisplayName("the exported fold stands in for a canonical id no source has named")
  void shouldStandInForTheCanonicalIdWhenNoSourceHasNamedIt() {
    LogProjection projection =
        LogProjection.of(
            new FakeAssertionLog()
                .with(
                    minted(ALMANAC, NodeKind.WORK, "what the owner called it"),
                    merged(ALMANAC, PRESSING)));

    assertThat(projection.nodes().get(PRESSING))
        .as("without a stand-in the canonical id would have no node for a folded edge to land on")
        .isNotNull()
        .extracting("kind", "label")
        .containsExactly(NodeKind.WORK, "what the owner called it");
  }
}
