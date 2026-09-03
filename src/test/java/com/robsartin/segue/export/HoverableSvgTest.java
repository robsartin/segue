package com.robsartin.segue.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link HoverableSvg} as a pure function, on SVG written by hand.
 *
 * <p>The outcome — what a browser actually resolves on hover — is asserted in {@code
 * WhatAHoverShowsTest}, against a real Graphviz render, because that is the only place the question
 * can honestly be asked. What is left for here is the handful of behaviours that render nothing:
 * running it twice, an SVG with no tooltips in it, and the one way a scanner that carries state
 * across elements can be wrong without any test of the happy path noticing.
 *
 * <p>Every fixture here is invented. ADR 51 and issue #37.
 */
class HoverableSvgTest {

  /** One node with a tooltip, shaped the way Graphviz shapes it. */
  private static final String ONE_NODE =
      """
      <svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink">
      <g id="graph0" class="graph">
      <title>a made&#45;up view</title>
      <g id="node1" class="node">
      <title>Q0901</title>
      <g id="a_node1"><a xlink:title="human">
      <ellipse cx="70" cy="-106" rx="70" ry="18"/>
      <text x="70" y="-101">Wren Alderman</text>
      </a>
      </g>
      </g>
      </g>
      </svg>
      """;

  @Test
  @DisplayName("rewriting an already-rewritten SVG changes nothing")
  void shouldChangeNothingWhenTheSvgHasAlreadyBeenRewritten() {
    String once = HoverableSvg.rewrite(ONE_NODE);

    assertThat(HoverableSvg.rewrite(once)).isEqualTo(once);
  }

  @Test
  @DisplayName("an SVG carrying no tooltip comes back byte for byte")
  void shouldReturnTheInputWhenNothingCarriesATooltip() {
    String noTooltips = ONE_NODE.replace(" xlink:title=\"human\"", "");

    assertThat(HoverableSvg.rewrite(noTooltips)).isEqualTo(noTooltips);
  }

  /**
   * The scanner holds one object's tooltip while it looks for that object's label. If it fails to
   * put it down at the end of the object, the next object's label inherits it — a tooltip that is
   * present, plausible and about the wrong thing, which is worse than none. Nothing in the happy
   * path can see that, because there every object has its own.
   */
  @Test
  @DisplayName("an object with no tooltip of its own does not inherit the previous object's")
  void shouldNotInheritATooltipWhenTheObjectCarriesNoneOfItsOwn() {
    String second =
        """
        <g id="edge1" class="edge">
        <title>Q0901&#45;&gt;Q0902</title>
        <path d="M70,-88C70,-76 70,-61 70,-47"/>
        <text x="112" y="-57">MEMBER_OF</text>
        </g>
        """;
    String both = ONE_NODE.replace("</svg>", second + "</svg>");

    String rewritten = HoverableSvg.rewrite(both);

    assertThat(rewritten).contains("<text x=\"112\" y=\"-57\">MEMBER_OF</text>");
    assertThat(rewritten).doesNotContain("<text x=\"112\" y=\"-57\"><title>");
  }

  @Test
  @DisplayName("the tool writes a rewritten copy and leaves the render it read alone")
  void shouldWriteACopyWhenRunAsATool(@TempDir Path directory) throws IOException {
    Path rendered = Files.writeString(directory.resolve("view.svg"), ONE_NODE);
    Path hoverable = directory.resolve("view-hoverable.svg");

    HoverableSvg.main(new String[] {"--in", rendered.toString(), "--out", hoverable.toString()});

    assertThat(Files.readString(hoverable)).isEqualTo(HoverableSvg.rewrite(ONE_NODE));
    assertThat(Files.readString(rendered)).as("the render it read").isEqualTo(ONE_NODE);
  }

  @Test
  @DisplayName("the tool refuses a render that is not there rather than writing an empty one")
  void shouldRefuseWhenThereIsNoRenderToRead(@TempDir Path directory) {
    Path absent = directory.resolve("never-rendered.svg");
    Path hoverable = directory.resolve("out.svg");
    String[] args = {"--in", absent.toString(), "--out", hoverable.toString()};

    assertThatThrownBy(() -> HoverableSvg.main(args))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("never-rendered.svg");
    assertThat(hoverable).doesNotExist();
  }

  @Test
  @DisplayName("the tool refuses to run without being told what to read and where to write")
  void shouldRefuseWhenAPathIsMissing() {
    assertThatThrownBy(() -> HoverableSvg.main(new String[] {"--in", "somewhere.svg"}))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("--out");
  }
}
