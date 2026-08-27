package com.robsartin.segue.export;

import com.robsartin.segue.domain.NodeKind;
import java.io.IOException;
import java.io.Writer;

/**
 * Graphviz DOT: the format you look at, as opposed to the one you work in.
 *
 * <p>Shape carries {@link NodeKind}, so the six kinds read apart without a legend, and an edge is
 * labelled with its type code. That is all — confidence and source id are on the edge in {@link
 * GraphMlWriter}, where a tool can filter on them; in DOT they would be decoration on a picture
 * that is already crowded.
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
    out.write("  node [style=filled, fillcolor=white];" + NL);
    for (ViewNode node : view.nodes()) {
      out.write(
          "  \""
              + escape(node.qid())
              + "\" [label=\""
              + escape(label(node))
              + "\", shape="
              + shape(node.kind())
              + "];"
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

  /** Backslash first, or the escaping escapes its own escapes. */
  private static String escape(String value) {
    return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
  }
}
