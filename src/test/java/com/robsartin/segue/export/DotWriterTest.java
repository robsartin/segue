package com.robsartin.segue.export;

import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.segue.domain.NodeKind;
import java.io.IOException;
import java.io.StringWriter;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Every fixture here is invented. ADR 40 and issue #37: nothing derived from a real graph, a real
 * list or a real rating enters this repository.
 */
class DotWriterTest {

  private static String render(GraphView view) throws IOException {
    StringWriter out = new StringWriter();
    new DotWriter().write(view, out);
    return out.toString();
  }

  private static GraphView view(List<ViewNode> nodes, List<ViewEdge> edges) {
    return new GraphView("a made-up view", nodes, edges);
  }

  @Test
  @DisplayName("emits a directed graph carrying the view's own description")
  void emitsADigraph() throws IOException {
    String dot =
        render(view(List.of(new ViewNode("Q901", NodeKind.PERSON, "Wren Alderman")), List.of()));

    assertThat(dot).startsWith("digraph");
    assertThat(dot).contains("a made-up view");
    assertThat(dot.trim()).endsWith("}");
  }

  @Test
  @DisplayName("node shape is chosen by NodeKind, so six kinds read apart at a glance")
  void shapesNodesByKind() throws IOException {
    String dot =
        render(
            view(
                List.of(
                    new ViewNode("Q901", NodeKind.PERSON, "Wren Alderman"),
                    new ViewNode("Q902", NodeKind.GROUP, "The Paper Kettles"),
                    new ViewNode("Q903", NodeKind.WORK, "Hollow Tide"),
                    new ViewNode("Q904", NodeKind.PLACE, "Bracken Hall"),
                    new ViewNode("Q905", NodeKind.EVENT, "The Bracken Sessions"),
                    new ViewNode("Q906", NodeKind.CONCEPT, "Invented Prize")),
                List.of()));

    assertThat(dot).contains("\"Q901\" [label=\"Wren Alderman\", shape=ellipse]");
    assertThat(dot).contains("\"Q902\" [label=\"The Paper Kettles\", shape=box]");
    assertThat(dot).contains("\"Q903\" [label=\"Hollow Tide\", shape=note]");
    assertThat(dot).contains("\"Q904\" [label=\"Bracken Hall\", shape=house]");
    assertThat(dot).contains("\"Q905\" [label=\"The Bracken Sessions\", shape=diamond]");
    assertThat(dot).contains("\"Q906\" [label=\"Invented Prize\", shape=octagon]");
  }

  @Test
  @DisplayName("every NodeKind has a shape, so a seventh kind cannot render as nothing")
  void hasAShapeForEveryKind() throws IOException {
    for (NodeKind kind : NodeKind.values()) {
      String dot = render(view(List.of(new ViewNode("Q901", kind, "x")), List.of()));
      assertThat(dot).as("shape for %s", kind).containsPattern("shape=\\w+");
    }
  }

  @Test
  @DisplayName("edge label is the type code")
  void labelsEdgesWithTheTypeCode() throws IOException {
    String dot =
        render(
            view(
                List.of(
                    new ViewNode("Q901", NodeKind.PERSON, "Wren Alderman"),
                    new ViewNode("Q902", NodeKind.GROUP, "The Paper Kettles")),
                List.of(new ViewEdge("Q901", "Q902", "MEMBER_OF", 1.0, "invented"))));

    assertThat(dot).contains("\"Q901\" -> \"Q902\" [label=\"MEMBER_OF\"]");
  }

  @Test
  @DisplayName("quotes and backslashes in a label are escaped, not emitted raw")
  void escapesLabels() throws IOException {
    String dot =
        render(
            view(
                List.of(new ViewNode("Q901", NodeKind.WORK, "The \"Quiet\" Back\\Room")),
                List.of()));

    assertThat(dot).contains("label=\"The \\\"Quiet\\\" Back\\\\Room\"");
  }

  @Test
  @DisplayName("a rating rides in the node label, because DOT has nowhere else to put it")
  void rendersAffinityInTheLabel() throws IOException {
    String dot =
        render(
            view(
                List.of(new ViewNode("Q901", NodeKind.PERSON, "Wren Alderman").withAffinity(4)),
                List.of()));

    assertThat(dot).contains("label=\"Wren Alderman (4/5)\"");
  }

  @Test
  @DisplayName("an empty view is still a valid graph, not an empty file")
  void writesAnEmptyGraph() throws IOException {
    String dot = render(view(List.of(), List.of()));

    assertThat(dot).startsWith("digraph");
    assertThat(dot.trim()).endsWith("}");
  }

  @Test
  @DisplayName("the extension names the format")
  void namesItsExtension() {
    assertThat(new DotWriter().extension()).isEqualTo("dot");
  }
}
