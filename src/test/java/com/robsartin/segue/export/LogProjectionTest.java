package com.robsartin.segue.export;

import static com.robsartin.segue.export.InventedGraph.HOLLOW_TIDE;
import static com.robsartin.segue.export.InventedGraph.KETTLES;
import static com.robsartin.segue.export.InventedGraph.WREN;
import static com.robsartin.segue.export.InventedGraph.edge;
import static com.robsartin.segue.export.InventedGraph.node;
import static com.robsartin.segue.export.InventedGraph.secondSource;
import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.segue.domain.EdgeRecord;
import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.export.InventedGraph.FakeAssertionLog;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LogProjectionTest {

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
}
