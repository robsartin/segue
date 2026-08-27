package com.robsartin.segue.export;

import java.io.IOException;
import java.io.Writer;
import java.util.List;

/**
 * GraphML: the format that survives the scale problem.
 *
 * <p>DOT is for looking at; this is for working in. Gephi and Cytoscape read it, and both can lay
 * out, filter and colour graphs far larger than Graphviz will render, which is the whole reason
 * issue #50 asks for two formats rather than one.
 *
 * <p><b>The attributes are the point.</b> A GraphML file whose nodes and edges carry nothing is a
 * picture in a heavier syntax. Nodes carry {@code kind}, {@code label} and {@code instanceOf} — the
 * raw {@code P31} QIDs, space-separated, so "every album" and "everything typed as a concert tour"
 * are one Gephi filter away; edges carry {@code typeCode}, {@code confidence} and {@code sourceId}.
 * Confidence is the one that earns its place: ADR 31 ranks a route by its weakest hop and demotes
 * hub intermediates, so "show me every edge under 1.00" and "show me what a model proposed" are
 * exactly the questions a person opens one of these files to ask.
 *
 * <p><b>And no presentation at all</b> — no fill, no shade, no tooltip. Gephi colours on an
 * attribute natively and shows attributes on hover, so a class that has a shade of its own in DOT
 * is here just another value of {@code instanceOf}, which is the version a reader can re-colour.
 *
 * <p>{@code affinity} is declared and written <em>only</em> when a node carries one — which happens
 * only behind {@code --include-affinity}, and makes the file personal data under ADR 33. A file
 * that never mentions the word is one less thing to have to check.
 *
 * <p>A pure function of the view: no store, no query, no clock. Written by hand rather than through
 * a DOM: the {@code full} view is tens of thousands of nodes, and a streamed write costs no memory
 * and no dependency.
 */
public final class GraphMlWriter implements ViewWriter {

  private static final String NL = "\n";
  private static final String NS = "http://graphml.graphdrawing.org/xmlns";

  /** Declared in {@code <key>} elements, and referenced by {@code <data key=...>}. */
  private record Key(String id, String forElement, String type) {}

  private static final List<Key> NODE_KEYS =
      List.of(
          new Key("kind", "node", "string"),
          new Key("label", "node", "string"),
          new Key("instanceOf", "node", "string"));

  private static final Key AFFINITY_KEY = new Key("affinity", "node", "int");

  private static final List<Key> EDGE_KEYS =
      List.of(
          new Key("typeCode", "edge", "string"),
          new Key("confidence", "edge", "double"),
          new Key("sourceId", "edge", "string"));

  @Override
  public String extension() {
    return "graphml";
  }

  @Override
  public void write(GraphView view, Writer out) throws IOException {
    boolean withAffinity = view.carriesAffinity();

    out.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>" + NL);
    out.write("<graphml xmlns=\"" + NS + "\"" + NL);
    out.write("         xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"" + NL);
    out.write("         xsi:schemaLocation=\"" + NS + " " + NS + "/1.0/graphml.xsd\">" + NL);
    out.write("  <desc>" + escape(view.description()) + "</desc>" + NL);

    for (Key key : NODE_KEYS) {
      writeKey(out, key);
    }
    if (withAffinity) {
      writeKey(out, AFFINITY_KEY);
    }
    for (Key key : EDGE_KEYS) {
      writeKey(out, key);
    }

    out.write("  <graph id=\"segue\" edgedefault=\"directed\">" + NL);
    for (ViewNode node : view.nodes()) {
      out.write("    <node id=\"" + escape(node.qid()) + "\">" + NL);
      writeData(out, "kind", node.kind().name());
      writeData(out, "label", node.label());
      // Space-separated, the same packing the log column and the graph vertex use (ADR 42), and
      // for the same reason: NodeRecord validates every value as a QID, so no value can contain
      // the separator and nothing here needs escaping. Raw QIDs, not names - Gephi filters on
      // what is in the file, and a display name is DOT's problem.
      if (!node.instanceOf().isEmpty()) {
        writeData(out, "instanceOf", String.join(" ", node.instanceOf()));
      }
      if (node.affinity() != null) {
        writeData(out, "affinity", String.valueOf(node.affinity()));
      }
      out.write("    </node>" + NL);
    }

    // An explicit, unique edge id per edge: the graph is a multigraph, so two relationships of
    // different types between one pair are two edges, and a reader that keys on (source, target)
    // would silently collapse them - the same bug ADR 18 rejected the RDF adapter's neighbour
    // query for.
    int index = 0;
    for (ViewEdge edge : view.edges()) {
      out.write(
          "    <edge id=\"e"
              + index++
              + "\" source=\""
              + escape(edge.fromQid())
              + "\" target=\""
              + escape(edge.toQid())
              + "\">"
              + NL);
      writeData(out, "typeCode", edge.typeCode());
      // Double.toString, not a formatter: locale-independent by construction, where a
      // NumberFormat would write "0,8" wherever the default locale says so and produce a file
      // that parses as XML and imports as garbage.
      writeData(out, "confidence", Double.toString(edge.confidence()));
      writeData(out, "sourceId", edge.sourceId());
      out.write("    </edge>" + NL);
    }

    out.write("  </graph>" + NL);
    out.write("</graphml>" + NL);
  }

  private static void writeKey(Writer out, Key key) throws IOException {
    out.write(
        "  <key id=\""
            + key.id()
            + "\" for=\""
            + key.forElement()
            + "\" attr.name=\""
            + key.id()
            + "\" attr.type=\""
            + key.type()
            + "\"/>"
            + NL);
  }

  private static void writeData(Writer out, String key, String value) throws IOException {
    out.write("      <data key=\"" + key + "\">" + escape(value) + "</data>" + NL);
  }

  /** Ampersand first, or the escaping escapes its own escapes. */
  private static String escape(String value) {
    return value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;");
  }
}
