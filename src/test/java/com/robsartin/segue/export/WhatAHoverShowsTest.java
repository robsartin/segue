package com.robsartin.segue.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.robsartin.segue.domain.NodeKind;
import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
            new ViewNode("Q901", NodeKind.PERSON, "Wren Alderman", List.of("Q5")),
            new ViewNode("Q902", NodeKind.GROUP, "The Paper Kettles", List.of("Q215380"))),
        List.of(new ViewEdge("Q901", "Q902", "MEMBER_OF", 1.0, "invented")));
  }

  @BeforeAll
  static void requireGraphviz() {
    assumeTrue(installed(), "graphviz is not installed, so there is no rendered file to read");
  }

  private static boolean installed() {
    try {
      return new ProcessBuilder("dot", "-V").redirectErrorStream(true).start().waitFor() == 0;
    } catch (IOException | InterruptedException e) {
      return false;
    }
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

    assertThat(hovers).contains("Q901");
    assertThat(hovers).doesNotContain("human");
  }

  @Test
  @DisplayName("hovering an edge in a rendered SVG shows two QIDs, not the relationship")
  void hoveringAnEdgeShowsTwoQids() throws IOException {
    List<String> hovers = hoverTexts();

    assertThat(hovers).contains("Q901->Q902");
    assertThat(hovers).noneMatch(hover -> hover.contains("MEMBER_OF"));
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
