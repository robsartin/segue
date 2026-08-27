package com.robsartin.segue.export;

import com.robsartin.segue.domain.NodeKind;
import java.io.IOException;
import java.io.Writer;

/**
 * Graphviz DOT: the format you look at, as opposed to the one you work in.
 *
 * <p>Shape <b>and</b> fill carry {@link NodeKind}, so the six kinds read apart without a legend,
 * and an edge is labelled with its type code. That is all — confidence and source id are on the
 * edge in {@link GraphMlWriter}, where a tool can filter on them; in DOT they would be decoration
 * on a picture that is already crowded.
 *
 * <p><b>Why both.</b> Shape survives greyscale printing and colour-blind viewing where colour does
 * not; colour survives being scaled down, where shape does not — at the 132 nodes of a real depth-1
 * neighbourhood an octagon and an ellipse are the same blob. Encoding one thing twice is the point.
 * GraphML deliberately gets neither: it carries {@code kind} as an attribute and Gephi colours on
 * it natively, so presentation stays the reader's. DOT has nowhere to put it but the file.
 *
 * <p><b>Layout engine.</b> Use {@code sfdp} or {@code neato} for anything above a few hundred
 * nodes. {@code dot} is a hierarchical layout for directed acyclic structures and degrades badly on
 * a dense multigraph — a real personal graph here is tens of thousands of nodes, and issue #50 is
 * explicit that the whole graph is not a picture. This writer emits nothing engine-specific, so the
 * choice stays the reader's.
 *
 * <p>A pure function of the view: no store, no query, no clock.
 */
public final class DotWriter implements ViewWriter {

  private static final String NL = "\n";

  @Override
  public String extension() {
    return "dot";
  }

  @Override
  public void write(GraphView view, Writer out) throws IOException {
    out.write("digraph \"" + escape(view.description()) + "\" {" + NL);
    out.write("  graph [overlap=false, splines=true];" + NL);
    out.write("  node [style=filled, fillcolor=white, fontcolor=black];" + NL);
    for (ViewNode node : view.nodes()) {
      out.write(
          "  \""
              + escape(node.qid())
              + "\" [label=\""
              + escape(label(node))
              + "\", shape="
              + shape(node.kind())
              + ", fillcolor=\""
              + fill(node.kind())
              + "\"];"
              + NL);
    }
    for (ViewEdge edge : view.edges()) {
      out.write(
          "  \""
              + escape(edge.fromQid())
              + "\" -> \""
              + escape(edge.toQid())
              + "\" [label=\""
              + escape(edge.typeCode())
              + "\"];"
              + NL);
    }
    out.write("}" + NL);
  }

  /**
   * DOT has no attribute namespace of its own to hang a rating on, so a rating rides in the label.
   * It is only ever present when the operator passed {@code --include-affinity} and was warned that
   * the file is personal data under ADR 33.
   */
  private static String label(ViewNode node) {
    return node.affinity() == null ? node.label() : node.label() + " (" + node.affinity() + "/5)";
  }

  /**
   * A shape per kind, exhaustively — a switch over the enum rather than a map with a default, so
   * adding a seventh kind is a compile error rather than a node that silently renders as a box.
   * (ADR 21 says there will not be a seventh. This is the cheap way to keep that honest.)
   */
  private static String shape(NodeKind kind) {
    return switch (kind) {
      case PERSON -> "ellipse";
      case GROUP -> "box";
      case WORK -> "note";
      case PLACE -> "house";
      case EVENT -> "diamond";
      case CONCEPT -> "octagon";
    };
  }

  /**
   * A fill per kind, exhaustively, for the same reason {@link #shape(NodeKind)} is.
   *
   * <p><b>Palette: Okabe &amp; Ito</b> (Okabe and Ito 2008, "Color Universal Design"), the
   * established eight-colour set built to stay distinguishable under protanopia, deuteranopia and
   * tritanopia. Six of its seven chromatic colours are used, each mixed 85% with white in linear
   * light so that black text sits on it at 7.8:1 or better — WCAG AAA — because {@code
   * style=filled} puts the label on top of the fill. Okabe-Ito blue is the one dropped: tinted, it
   * collides with tinted sky blue.
   *
   * <p>The assignment is not by eye. Every candidate six-of-seven subset was scored by simulating
   * all three deficiencies (Machado et al. 2009 matrices, severity 1.0) and taking the worst CIELAB
   * distance over every pair; this set wins, with a worst case of ΔE 12 between PLACE and CONCEPT —
   * which are also a house and an octagon. <b>PERSON and GROUP are the pair that most needs telling
   * apart, and they get the most separated pair in the set</b>: sky blue against orange, at ΔE 67
   * in the worst of the three deficiencies. Blue against orange, never red against green.
   */
  private static String fill(NodeKind kind) {
    return switch (kind) {
      case PERSON -> "#84C2EC"; // Okabe-Ito sky blue
      case GROUP -> "#EAB26C"; // Okabe-Ito orange
      case WORK -> "#F2E87A"; // Okabe-Ito yellow
      case PLACE -> "#6CB194"; // Okabe-Ito bluish green
      case EVENT -> "#DC886C"; // Okabe-Ito vermillion
      case CONCEPT -> "#D598B8"; // Okabe-Ito reddish purple
    };
  }

  /** Backslash first, or the escaping escapes its own escapes. */
  private static String escape(String value) {
    return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
  }
}
