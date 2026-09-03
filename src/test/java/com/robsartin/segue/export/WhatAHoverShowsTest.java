package com.robsartin.segue.export;

import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.segue.domain.NodeKind;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;

/**
 * What a reader gets on hover, as opposed to what is in the file.
 *
 * <p>Issues #63 and #70 put a {@code tooltip} on every node and every edge, and the check that they
 * worked grepped the rendered SVG for the attribute and found it — 276 of them on a real 132-node
 * view. <b>Presence was verified; the outcome was not.</b> Browsers do not read {@code
 * xlink:title}; they read the {@code <title>} element, which Graphviz writes from the object's
 * name. So every one of those tooltips was in the file and none of them reached a reader (issue
 * #81).
 *
 * <p>This test renders through the <b>real Graphviz binary</b> and asserts on the {@code <title>}
 * content a browser would act on, which is the assertion that was missing. It pins the "cannot" —
 * so nobody re-reads {@code DotWriter} and concludes the SVG hover works — and it pins the render
 * that <em>does</em> reach a browser, so that half cannot regress either. If a future Graphviz ever
 * writes the tooltip into {@code <title>}, the first two tests fail and ADR 41 wants revisiting.
 *
 * <p>Skipped where Graphviz is not installed: there is no rendered file to read, and a build
 * machine without it should not fail for that.
 *
 * <p>Every fixture here is invented. ADR 40 and issue #37.
 */
class WhatAHoverShowsTest {

  /** A person in a band: one class with a known name, one edge with two named ends. */
  private static GraphView view() {
    return new GraphView(
        "a made-up view",
        List.of(
            new ViewNode("Q0901", NodeKind.PERSON, "Wren Alderman", List.of("Q5")),
            new ViewNode("Q0902", NodeKind.GROUP, "The Paper Kettles", List.of("Q215380"))),
        List.of(new ViewEdge("Q0901", "Q0902", "MEMBER_OF", 1.0, "invented")));
  }

  @BeforeAll
  static void requireGraphviz() {
    Graphviz.requireOrSkip("graphviz is not installed, so there is no rendered file to read");
  }

  /** Renders this writer's DOT through the real Graphviz binary and returns what it wrote. */
  private static String graphviz(String format) throws IOException {
    StringWriter dot = new StringWriter();
    new DotWriter().write(view(), dot);
    Process process = new ProcessBuilder("dot", "-T" + format).start();
    process.getOutputStream().write(dot.toString().getBytes(StandardCharsets.UTF_8));
    process.getOutputStream().close();
    String rendered = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    try {
      process.waitFor();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new UncheckedIOException(new IOException(e));
    }
    return rendered;
  }

  /** The text of every {@code <title>} element — the only tooltip mechanism browsers implement. */
  private static List<String> hoverTexts() throws IOException {
    Matcher matcher =
        Pattern.compile("<title>(.*?)</title>", Pattern.DOTALL).matcher(graphviz("svg"));
    return matcher.results().map(result -> unescape(result.group(1))).toList();
  }

  /** Graphviz escapes {@code -} and {@code >} as character references in both outputs. */
  private static String unescape(String text) {
    return text.replace("&#45;", "-").replace("&gt;", ">").replace("&amp;", "&");
  }

  @Test
  @DisplayName("hovering a node in a rendered SVG shows its QID, because DOT cannot say more")
  void hoveringANodeShowsItsQid() throws IOException {
    List<String> hovers = hoverTexts();

    assertThat(hovers).contains("Q0901");
    assertThat(hovers).doesNotContain("human");
  }

  @Test
  @DisplayName("hovering an edge in a rendered SVG shows two QIDs, not the relationship")
  void hoveringAnEdgeShowsTwoQids() throws IOException {
    List<String> hovers = hoverTexts();

    assertThat(hovers).contains("Q0901->Q0902");
    assertThat(hovers).noneMatch(hover -> hover.contains("MEMBER_OF"));
  }

  /**
   * What a browser shows on hovering the element {@code xpath} selects: the text of the nearest
   * ancestor-or-self carrying a {@code <title>} child, which is the lookup SVG user agents
   * implement. Asserting on that rather than on a substring is the point — a {@code <title>}
   * present in the file but shadowed by an outer one is exactly the shape of defect issue #81
   * found, and {@code contains} cannot see it.
   *
   * <p>Namespace-unaware on purpose: it keeps the expressions readable, and this document has one
   * element namespace.
   */
  private static String hoverOver(String svg, String xpath) throws Exception {
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    // The Graphviz DOCTYPE names svg11.dtd on w3.org. Without these the parse fetches it, so the
    // test needs the network and W3C's rate limiter gets a vote on whether the build passes.
    factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
    Document document = factory.newDocumentBuilder().parse(new InputSource(new StringReader(svg)));
    Node found =
        (Node) XPathFactory.newInstance().newXPath().evaluate(xpath, document, XPathConstants.NODE);
    assertThat(found).as("no element matched %s", xpath).isNotNull();
    for (Node up = found;
        up != null && up.getNodeType() == Node.ELEMENT_NODE;
        up = up.getParentNode()) {
      for (Node child = up.getFirstChild(); child != null; child = child.getNextSibling()) {
        if (child.getNodeType() == Node.ELEMENT_NODE && "title".equals(child.getNodeName())) {
          return child.getTextContent();
        }
      }
    }
    return null;
  }

  @Test
  @DisplayName("a rewritten SVG shows a node's class where a browser looks, not its QID")
  void shouldShowTheClassWhenTheRenderedSvgHasBeenRewritten() throws Exception {
    String hoverable = HoverableSvg.rewrite(graphviz("svg"));

    assertThat(hoverOver(hoverable, "//*[@id='node1']//ellipse")).isEqualTo("human");
    assertThat(hoverOver(hoverable, "//*[@id='node1']//text")).isEqualTo("human");
  }

  @Test
  @DisplayName("a rewritten SVG shows an edge's relationship on the line a reader points at")
  void shouldShowTheRelationshipWhenTheReaderPointsAtTheEdgeLine() throws Exception {
    String hoverable = HoverableSvg.rewrite(graphviz("svg"));

    assertThat(hoverOver(hoverable, "//*[@id='edge1']//path"))
        .isEqualTo("Wren Alderman -MEMBER_OF-> The Paper Kettles");
  }

  @Test
  @DisplayName("a rewritten SVG shows the relationship on the edge's visible label too")
  void shouldShowTheRelationshipWhenTheReaderPointsAtTheEdgeLabel() throws Exception {
    String hoverable = HoverableSvg.rewrite(graphviz("svg"));

    assertThat(hoverOver(hoverable, "//*[@id='edge1']/text"))
        .isEqualTo("Wren Alderman -MEMBER_OF-> The Paper Kettles");
  }

  @Test
  @DisplayName("an imagemap render puts the node's class where a browser will show it")
  void anImagemapShowsTheClass() throws IOException {
    assertThat(unescape(graphviz("cmapx"))).contains("title=\"human\"");
  }

  @Test
  @DisplayName("an imagemap render keeps the relationship and both of its ends, as #70 established")
  void anImagemapShowsTheWholeRelationship() throws IOException {
    assertThat(unescape(graphviz("cmapx")))
        .contains("title=\"Wren Alderman -MEMBER_OF-> The Paper Kettles\"");
  }
}
