package com.robsartin.segue.export;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Moves a Graphviz SVG's tooltips to where a browser looks for them.
 *
 * <p>Issue #81 established the "cannot" and {@code WhatAHoverShowsTest} pins it: Graphviz writes a
 * {@code tooltip} into {@code xlink:title}, which no browser displays, and writes the {@code
 * <title>} <em>element</em> — the mechanism they do implement — from the object's name. So a reader
 * hovering an exported node gets {@code Q16473} and hovering an edge gets {@code Q16473-&gt;
 * Q1415017}. That is a property of DOT and nothing in {@link DotWriter} can change it.
 *
 * <p><b>It is not a property of the rendered file.</b> Once Graphviz has written the SVG, the class
 * and the relationship are both in it, one attribute away from the element a browser reads. This
 * runs after the render and moves them.
 *
 * <p>Run from Gradle, a third step after the export and the render: {@code ./gradlew hoverableSvg
 * --args="--in view.svg --out view-hoverable.svg"}. It is a separate step rather than something
 * {@code exportGraph} does because the exporter never shells out to Graphviz — an export is a pure
 * function of one database file (ADR 41), and the SVG this reads does not exist until the operator
 * has run {@code dot} themselves.
 */
public final class HoverableSvg {

  private static final Logger log = LoggerFactory.getLogger(HoverableSvg.class);

  private static final String USAGE = "usage: --in <rendered.svg> --out <hoverable.svg>";

  /**
   * The tags this walks. {@code \b} is doing real work in each alternative: it is what stops {@code
   * a} matching {@code <area>} and {@code text} matching {@code <textPath>}.
   */
  private static final Pattern TAG = Pattern.compile("<(/?)(g|a|text)\\b([^>]*)>");

  private static final Pattern TOOLTIP = Pattern.compile("\\bxlink:title=\"([^\"]*)\"");

  private static final Pattern OBJECT = Pattern.compile("\\bclass=\"(node|edge)\"");

  private HoverableSvg() {}

  /**
   * The same SVG, with every {@code xlink:title} also present as a {@code <title>} element on the
   * anchor that carried it — and on the edge label that anchor does not contain.
   *
   * <p><b>Why the second half exists.</b> Graphviz puts a node's label inside the anchor and an
   * edge's label outside it, as a sibling of the whole {@code <g class="edge">}. Rewriting only the
   * anchors therefore leaves the one thing a reader is most likely to point at — the visible
   * relationship label, drawn on every view under {@code DotWriter.LABEL_BUDGET} edges — still
   * resolving to the group's own {@code <title>}, which is the two QIDs. Measured in Chrome by
   * hit-testing the rendered label, and pinned by {@code WhatAHoverShowsTest}.
   *
   * <p>Nothing is deleted, nothing is re-escaped and nothing is re-serialised: the input is copied
   * through verbatim and elements are inserted into it. The attribute's value is already XML
   * escaped, so it is valid element content exactly as it stands, and the {@code xlink:title} it
   * came from stays where it was — a tool that reads it keeps working, and so does the outer {@code
   * <title>} that names the node, which stays the QID a reader may still want.
   */
  public static String rewrite(String svg) {
    StringBuilder out = new StringBuilder();
    Matcher tags = TAG.matcher(svg);
    int copied = 0;
    int depth = 0;
    int objectDepth = -1;
    String tooltip = null;
    boolean insideAnchor = false;

    while (tags.find()) {
      out.append(svg, copied, tags.end());
      copied = tags.end();
      boolean closing = !tags.group(1).isEmpty();
      String name = tags.group(2);
      String attributes = tags.group(3);
      boolean selfClosing = attributes.endsWith("/");

      if (closing) {
        switch (name) {
          case "g" -> {
            if (depth == objectDepth) {
              objectDepth = -1;
              tooltip = null;
            }
            depth--;
          }
          case "a" -> insideAnchor = false;
          default -> {
            // A closing </text> ends nothing this tracks.
          }
        }
        continue;
      }
      if (selfClosing) {
        continue;
      }

      switch (name) {
        case "g" -> {
          depth++;
          if (objectDepth < 0 && OBJECT.matcher(attributes).find()) {
            objectDepth = depth;
          }
        }
        case "a" -> {
          insideAnchor = true;
          Matcher carried = TOOLTIP.matcher(attributes);
          if (carried.find()) {
            tooltip = carried.group(1);
            if (!alreadyTitled(svg, copied)) {
              out.append("<title>").append(tooltip).append("</title>");
            }
          }
        }
        case "text" -> {
          if (!insideAnchor && tooltip != null && !alreadyTitled(svg, copied)) {
            out.append("<title>").append(tooltip).append("</title>");
          }
        }
        default -> throw new IllegalStateException("unreachable tag name: " + name);
      }
    }
    out.append(svg, copied, svg.length());
    return out.toString();
  }

  /**
   * Whether this element already opens with a {@code <title>}, so a second run over the same file
   * adds nothing. Only the first {@code <title>} child is displayed, so a duplicate would not
   * change what a reader sees — which is exactly why it needs a test rather than a reader.
   */
  private static boolean alreadyTitled(String svg, int afterStartTag) {
    return svg.startsWith("<title>", afterStartTag);
  }

  /**
   * Reads one rendered SVG and writes a hoverable copy of it.
   *
   * <p>It writes a copy rather than editing in place, and {@code --out} has no default, for the
   * same reason {@code ExportCli}'s has none: the output of a dev tool belongs where the operator
   * put the render, and a tool that picks a path picks one inside the repository.
   *
   * <p>The count is logged because zero is the interesting number. A run that matches nothing
   * finishes just as quietly as one that moves 276 tooltips, and this project has already shipped
   * one tooltip nobody could see (issue #81) and one CI test that passed by never running (issue
   * #93). A file written is not evidence that anything happened to it.
   */
  public static void main(String[] args) throws IOException {
    Path in = null;
    Path out = null;
    for (int i = 0; i < args.length; i++) {
      switch (args[i]) {
        case "--in" -> in = Path.of(argument(args, ++i, "--in"));
        case "--out" -> out = Path.of(argument(args, ++i, "--out"));
        default -> throw new IllegalArgumentException("unknown option " + args[i] + ". " + USAGE);
      }
    }
    if (in == null || out == null) {
      throw new IllegalArgumentException("both --in and --out are required. " + USAGE);
    }
    if (!Files.exists(in)) {
      throw new IllegalArgumentException("no rendered SVG at " + in + " — nothing to rewrite");
    }

    String rendered = Files.readString(in);
    String hoverable = rewrite(rendered);
    Files.writeString(out, hoverable);
    log.info("moved {} tooltip(s) from {} into {}", titles(hoverable) - titles(rendered), in, out);
  }

  private static String argument(String[] args, int at, String flag) {
    if (at >= args.length) {
      throw new IllegalArgumentException(flag + " needs a path. " + USAGE);
    }
    return args[at];
  }

  /** How many {@code <title>} elements a document opens, so the difference is what this moved. */
  private static int titles(String svg) {
    int count = 0;
    for (int at = svg.indexOf("<title>"); at >= 0; at = svg.indexOf("<title>", at + 1)) {
      count++;
    }
    return count;
  }
}
