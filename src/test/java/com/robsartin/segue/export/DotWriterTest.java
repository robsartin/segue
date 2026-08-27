package com.robsartin.segue.export;

import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.segue.domain.NodeKind;
import java.io.IOException;
import java.io.StringWriter;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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

    assertThat(dot).contains("\"Q901\" [label=\"Wren Alderman\", shape=ellipse,");
    assertThat(dot).contains("\"Q902\" [label=\"The Paper Kettles\", shape=box,");
    assertThat(dot).contains("\"Q903\" [label=\"Hollow Tide\", shape=note,");
    assertThat(dot).contains("\"Q904\" [label=\"Bracken Hall\", shape=house,");
    assertThat(dot).contains("\"Q905\" [label=\"The Bracken Sessions\", shape=diamond,");
    assertThat(dot).contains("\"Q906\" [label=\"Invented Prize\", shape=octagon,");
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
  @DisplayName("node fill is chosen by NodeKind, so kind survives being scaled down to a thumbnail")
  void fillsNodesByKind() throws IOException {
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

    assertThat(dot).contains("shape=ellipse, fillcolor=\"#84C2EC\"");
    assertThat(dot).contains("shape=box, fillcolor=\"#EAB26C\"");
    assertThat(dot).contains("shape=note, fillcolor=\"#F2E87A\"");
    assertThat(dot).contains("shape=house, fillcolor=\"#6CB194\"");
    assertThat(dot).contains("shape=diamond, fillcolor=\"#DC886C\"");
    assertThat(dot).contains("shape=octagon, fillcolor=\"#D598B8\"");
  }

  @Test
  @DisplayName("every NodeKind has a fill, and no two kinds share one")
  void hasADistinctFillForEveryKind() throws IOException {
    Set<String> fills = new LinkedHashSet<>();
    for (NodeKind kind : NodeKind.values()) {
      String dot = render(view(List.of(new ViewNode("Q901", kind, "x")), List.of()));
      Matcher matcher = Pattern.compile("fillcolor=\"(#[0-9A-Fa-f]{6})\"").matcher(dot);
      assertThat(matcher.find()).as("a fill for %s", kind).isTrue();
      fills.add(matcher.group(1));
    }
    assertThat(fills).as("one fill per kind, none shared").hasSize(NodeKind.values().length);
  }

  @Test
  @DisplayName("WORK is shaded by its four commonest classes, on one yellow ladder")
  void shadesWorkByItsClass() throws IOException {
    String dot =
        render(
            view(
                List.of(
                    new ViewNode("Q901", NodeKind.WORK, "Hollow Tide", List.of("Q482994")),
                    new ViewNode("Q902", NodeKind.WORK, "Bracken Air", List.of("Q105543609")),
                    new ViewNode("Q903", NodeKind.WORK, "Kettle Song", List.of("Q134556")),
                    new ViewNode("Q904", NodeKind.WORK, "The Long Marsh", List.of("Q11424"))),
                List.of()));

    assertThat(dot).contains("\"Q901\" [label=\"Hollow Tide\", shape=note, fillcolor=\"#F8F3C6\"");
    assertThat(dot).contains("\"Q902\" [label=\"Bracken Air\", shape=note, fillcolor=\"#D9CF3B\"");
    assertThat(dot).contains("\"Q903\" [label=\"Kettle Song\", shape=note, fillcolor=\"#BFB633\"");
    assertThat(dot)
        .contains("\"Q904\" [label=\"The Long Marsh\", shape=note, fillcolor=\"#A69E2B\"");
  }

  @Test
  @DisplayName("any other WORK keeps plain WORK yellow — the shades are four classes, not a scheme")
  void leavesEveryOtherWorkPlain() throws IOException {
    String dot =
        render(
            view(
                List.of(
                    new ViewNode("Q901", NodeKind.WORK, "A Novel", List.of("Q7725634")),
                    new ViewNode("Q902", NodeKind.WORK, "Something", List.of("Q900901")),
                    new ViewNode("Q903", NodeKind.WORK, "Unclassified", List.of())),
                List.of()));

    assertThat(dot.lines().filter(line -> line.contains("fillcolor=\"#F2E87A\"")).count())
        .isEqualTo(3);
  }

  @Test
  @DisplayName("shading is WORK's alone: the same class on another kind changes nothing")
  void shadesNoKindButWork() throws IOException {
    String dot =
        render(
            view(
                List.of(
                    new ViewNode("Q901", NodeKind.PERSON, "Wren Alderman", List.of("Q482994")),
                    new ViewNode("Q902", NodeKind.CONCEPT, "Invented Prize", List.of("Q11424"))),
                List.of()));

    assertThat(dot).contains("shape=ellipse, fillcolor=\"#84C2EC\"");
    assertThat(dot).contains("shape=octagon, fillcolor=\"#D598B8\"");
  }

  @Test
  @DisplayName("the first class with a shade wins, as the first recognised class chooses the kind")
  void takesTheFirstShadedClass() throws IOException {
    String dot =
        render(
            view(
                List.of(
                    new ViewNode(
                        "Q901", NodeKind.WORK, "Kettle Song", List.of("Q900901", "Q134556"))),
                List.of()));

    assertThat(dot).contains("fillcolor=\"#BFB633\"");
  }

  @Test
  @DisplayName("a node's tooltip names the class it is an instance of, not just its kind")
  void tooltipsNodesWithTheirClass() throws IOException {
    String dot =
        render(
            view(
                List.of(new ViewNode("Q901", NodeKind.WORK, "Hollow Tide", List.of("Q482994"))),
                List.of()));

    assertThat(dot).contains("tooltip=\"album\"");
  }

  @Test
  @DisplayName("every class the claim stated is named, in the order it stated them")
  void tooltipsEveryStatedClass() throws IOException {
    String dot =
        render(
            view(
                List.of(
                    new ViewNode(
                        "Q901", NodeKind.WORK, "Hollow Tide", List.of("Q482994", "Q105543609"))),
                List.of()));

    assertThat(dot).contains("tooltip=\"album, musical work/composition\"");
  }

  @Test
  @DisplayName("a class the offline table has never heard of is named by its QID, not guessed at")
  void tooltipsAnUnknownClassByItsQid() throws IOException {
    String dot =
        render(
            view(
                List.of(new ViewNode("Q901", NodeKind.CONCEPT, "Something", List.of("Q900901"))),
                List.of()));

    assertThat(dot).contains("tooltip=\"Q900901\"");
  }

  @Test
  @DisplayName("a node whose source stated no class says exactly that")
  void tooltipsAClasslessNodeHonestly() throws IOException {
    String dot =
        render(view(List.of(new ViewNode("Q901", NodeKind.PERSON, "Wren Alderman")), List.of()));

    assertThat(dot).contains("tooltip=\"no stated class\"");
  }

  @Test
  @DisplayName("every node carries a tooltip, whatever it is and whatever it knows")
  void tooltipsEveryNode() throws IOException {
    String dot =
        render(
            view(
                List.of(
                    new ViewNode("Q901", NodeKind.PERSON, "Wren Alderman", List.of("Q5")),
                    new ViewNode("Q902", NodeKind.GROUP, "The Paper Kettles"),
                    new ViewNode("Q903", NodeKind.WORK, "Hollow Tide", List.of("Q900901")),
                    new ViewNode(
                        "Q904", NodeKind.CONCEPT, "The Invented Prize", List.of("Q618779"))),
                List.of()));

    assertThat(dot.lines().filter(line -> line.contains("tooltip=")).count()).isEqualTo(4);
  }

  @Test
  @DisplayName("the graph-level default states the dark text the fills are chosen against")
  void declaresDarkText() throws IOException {
    String dot = render(view(List.of(), List.of()));

    assertThat(dot).contains("fontcolor=black");
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
