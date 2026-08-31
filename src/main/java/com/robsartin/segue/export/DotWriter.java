package com.robsartin.segue.export;

import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.support.ClassLabels;
import java.io.IOException;
import java.io.Writer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Graphviz DOT: the format you look at, as opposed to the one you work in.
 *
 * <p>Shape <b>and</b> fill carry {@link NodeKind}, so the six kinds read apart without a legend,
 * and an edge says its type. That is all — confidence and source id are on the edge in {@link
 * GraphMlWriter}, where a tool can filter on them; in DOT they would be decoration on a picture
 * that is already crowded.
 *
 * <p><b>Every edge carries a {@code tooltip} too</b>, naming the relationship and both its ends —
 * {@code Wren Alderman -MEMBER_OF-> The Paper Kettles} — and above {@link #LABEL_BUDGET} edges the
 * visible label is dropped and the tooltip is all there is. See {@link #labelEdges} for the
 * measurements; {@link #note} is what says so out loud.
 *
 * <p><b>Every node also carries a {@code tooltip}: what it is an instance of.</b> "Concert tour" or
 * "television special" where the fill can only say CONCEPT or WORK — the channel with no budget,
 * since six fills cannot describe 861 classes. The names come from {@link ClassLabels}, an offline
 * table, and fall back to the bare class QID; the exporter does not go looking one up (ADR 41, and
 * {@code ArchitectureTest.theExporterNeverSpeaksToANetwork}).
 *
 * <p><b>A browser opening the SVG will not show either tooltip, and DOT cannot make it.</b> Issue
 * #81. Graphviz puts a {@code tooltip} in {@code xlink:title}, which browsers ignore; the mechanism
 * they do implement is the {@code <title>} <em>element</em>, and Graphviz writes that from the
 * object's <b>name</b>, unconditionally — no attribute redirects it, {@code id} included. A node's
 * name is its identity and has to stay unique, so the class cannot be it (two nodes named {@code
 * human} silently merge into one), and an edge has no name at all: its {@code <title>} is
 * mechanically {@code tail-&gt;head}, so a relationship type cannot appear there however the nodes
 * are named. Hovering therefore shows {@code Q16473} and {@code Q16473-&gt;Q1415017}.
 *
 * <p><b>The attribute stays anyway</b>, because it is not inert and because above {@link
 * #LABEL_BUDGET} it is the only thing carrying an edge's type: {@code dot -Tcmapx} renders the same
 * {@code tooltip} as an HTML {@code title} on an {@code <area>}, which every browser shows. {@code
 * WhatAHoverShowsTest} renders through the real binary and pins both halves, so neither the
 * "cannot" nor the "does" can quietly stop being true.
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

  /**
   * The most edges this format will draw a label on. Above it, every edge label is dropped and the
   * type survives in the tooltip alone.
   *
   * <p><b>Measured, on slices of one real depth-1 neighbourhood laid out with {@code sfdp}</b>
   * (issue #70): at 26 edges every label is legible; at 38 a couple of pairs touch; at 51 labels
   * begin overprinting each other and the node labels underneath them; at 144 — an ordinary depth-1
   * neighbourhood — the hub is a solid block of text and even the entity at its centre cannot be
   * read. 40 is the last count at which the picture still reads.
   *
   * <p><b>Edges, not nodes, because a label is drawn per edge.</b> That is also why this needs no
   * {@link ViewKind}: a route keeps its labels because a route is four edges, not because it is a
   * route, and the same rule keeps them on a small subgraph and drops them from a large one. One
   * rule about the picture beats two rules about where the picture came from.
   */
  static final int LABEL_BUDGET = 40;

  @Override
  public String extension() {
    return "dot";
  }

  @Override
  public void write(GraphView view, Writer out) throws IOException {
    boolean labelEdges = labelEdges(view);
    Map<String, String> labels = new HashMap<>();
    for (ViewNode node : view.nodes()) {
      labels.put(node.qid(), label(node));
    }

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
              + fill(node)
              + "\", tooltip=\""
              + escape(ClassLabels.describe(node.instanceOf()))
              + "\"];"
              + NL);
    }
    for (ViewEdge edge : view.edges()) {
      out.write(
          "  \""
              + escape(edge.fromQid())
              + "\" -> \""
              + escape(edge.toQid())
              + "\" ["
              + (labelEdges ? "label=\"" + escape(edge.typeCode()) + "\", " : "")
              + "tooltip=\""
              + escape(describe(edge, labels))
              + "\"];"
              + NL);
    }
    out.write("}" + NL);
  }

  /**
   * The sentence the operator gets when this writer has taken something out of the picture, and
   * nothing at all when it has not.
   *
   * <p>It names both counts so the reader can see the rule rather than only its verdict, and it
   * says where the type went — and says it accurately, which it did not until issue #81. It used to
   * read "render with -Tsvg and hover", which is the one thing that does not work: Graphviz puts
   * the tooltip in {@code xlink:title}, and the {@code <title>} element a browser actually shows
   * holds the QIDs.
   *
   * <p><b>It names the SVG again, and issue #99 is why that is not a regression.</b> The render is
   * still unreadable as Graphviz writes it; what changed is that {@link HoverableSvg} rewrites it
   * afterwards. So the note names the two together and never the render alone — {@code
   * DotWriterTest} pins the pair. {@code typeCode} in GraphML remains the answer that needs no
   * second step, and the developer guide carries the imagemap route for a PNG workflow.
   */
  @Override
  public Optional<String> note(GraphView view) {
    if (labelEdges(view)) {
      return Optional.empty();
    }
    return Optional.of(
        view.edges().size()
            + " edge(s) is past the "
            + LABEL_BUDGET
            + " this picture can label legibly, so the DOT edge labels are dropped. Each edge"
            + " keeps its type in a tooltip, but Graphviz puts that in xlink:title and a browser"
            + " hovering the SVG shows the QIDs instead (issue #81): render -Tsvg and run"
            + " hoverableSvg over it, which moves each tooltip to where a browser looks (issue"
            + " #99), or read the types from GraphML, which carries typeCode on every edge"
            + " whatever the size");
  }

  /** True while the picture can still carry a label on every edge. */
  private static boolean labelEdges(GraphView view) {
    return view.edges().size() <= LABEL_BUDGET;
  }

  /**
   * What an edge says on hover: the relationship and both of the things it joins.
   *
   * <p>The type alone would do while the labels are visible. It does not once they are gone — the
   * edges that most need identifying are the ones fanning out of a hub, drawn on top of each other,
   * where "which of these did I just point at" is the whole question. The endpoints are named the
   * way the picture names them, so the tooltip and the two nodes agree; a QID that somehow has no
   * node stands for itself rather than for a guess (the view's own invariant says there is none).
   */
  private static String describe(ViewEdge edge, Map<String, String> labels) {
    return labels.getOrDefault(edge.fromQid(), edge.fromQid())
        + " -"
        + edge.typeCode()
        + "-> "
        + labels.getOrDefault(edge.toQid(), edge.toQid());
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

  /**
   * Four shades of the WORK yellow, one per class, and plain yellow for every other class.
   *
   * <p><b>Only WORK, and only four.</b> Measured on a real 54,448-node graph: WORK is 81% of it and
   * 106 classes wide, and its top four — album 31%, musical work/composition 21%, single 14%, film
   * 10% — are genuinely different things that a picture full of identical yellow notes cannot tell
   * apart. No other kind earns it. PERSON is one class at 100%; GROUP is 75% "musical group";
   * CONCEPT's 458 classes are too flat for four shades to be anything but a lie about which four
   * matter. The tooltip is what reaches those tails, at no colour cost.
   *
   * <p><b>The ladder is lightness only.</b> Every shade is the same Okabe-Ito yellow, mixed with
   * white or scaled down in linear light; no shade changes hue, so none can drift toward GROUP
   * orange. Re-scored under the method {@link #fill(NodeKind)} describes — Machado et al. matrices
   * at severity 1.0 for all three deficiencies, worst CIELAB distance over every pair:
   *
   * <ul>
   *   <li>The nearest any shade comes to another kind's fill is <b>ΔE 17.3</b> (film against GROUP,
   *       deuteranopia) — <em>further</em> than plain WORK yellow already sits from GROUP (15.9),
   *       because the tinted orange is light and darkening the yellow moves away from it. The
   *       palette's worst pair is unchanged at ΔE 11.9, PLACE against CONCEPT.
   *   <li>The five yellows are ΔE 8.9 apart at worst (single against film, tritanopia).
   *   <li>Black labels stay AAA on all of them: 7.55:1 at worst, on film.
   * </ul>
   *
   * <p>{@code PaletteSeparationTest} re-runs all three of those checks rather than trusting this
   * comment. And shape still carries the kind alone, so a shade a reader cannot place costs them
   * nothing: a yellow note is a WORK whichever yellow it is.
   */
  private static String fill(ViewNode node) {
    if (node.kind() != NodeKind.WORK) {
      return fill(node.kind());
    }
    return SHADES.stream()
        .filter(shade -> node.instanceOf().contains(shade.classQid()))
        .findFirst()
        .map(Shade::fill)
        .orElseGet(() -> fill(NodeKind.WORK));
  }

  /** One shade of the WORK yellow and the class that earns it. */
  private record Shade(String classQid, String fill) {}

  /**
   * The four shaded classes, <b>most decisive first — the order of this list is the rule</b>, and
   * the shade each one gets. A WORK stating two of them takes the shade of whichever ranks higher
   * here, never of whichever arrived first.
   *
   * <p>Reordering these lines changes what the picture draws. That is deliberate: it is the same
   * shape {@code KindMapper.PRECEDENCE} takes, for the same reason (issue #87). {@code
   * ReverseClaims} collects an entity's classes into a set keyed on whatever order SPARQL bound the
   * rows, and the entity JSON lists statements oldest first; neither is a claim about which class
   * matters most, so neither may decide. Before issue #98 the first stated class with a shade won,
   * which meant two exports of the same entity could legitimately disagree.
   *
   * <p><b>Not {@code PRECEDENCE} itself, and not a reference to it: that list ranks the six kinds,
   * and all four of these classes are the same kind.</b> WORK wins there as one block, which
   * settles nothing about the four ways of being a WORK. This is the ranking that layer does not
   * have, so it is written here rather than borrowed.
   *
   * <p>The order is argued, weakly, because this is decoration:
   *
   * <ul>
   *   <li><b>Musical work/composition last.</b> It is the broadest of the four and the one that
   *       tells a reader least — of the works that a real graph's whitelist could not place, it
   *       alone was 667 of 1,058 ({@code KindMapper}). An entity stating it <em>and</em> album or
   *       single is a record the reader can see; the composition is the abstraction behind it.
   *   <li><b>The other three by measured share</b> — album 31%, single 14%, film 10% of a real
   *       54,448-node graph's WORK, the same measurement that chose the four in the first place
   *       (ADR 41). They rarely co-occur, so this is a tie-break rather than a rule.
   * </ul>
   *
   * <p>Being sure of the order matters less than the order being fixed. Shape carries the kind
   * alone, and a shade a reader cannot place costs them nothing; two runs disagreeing about the
   * same entity cost them trust in the picture.
   */
  private static final List<Shade> SHADES =
      List.of(
          new Shade("Q482994", "#F8F3C6"), // album
          new Shade("Q134556", "#BFB633"), // single
          new Shade("Q11424", "#A69E2B"), // film
          new Shade("Q105543609", "#D9CF3B")); // musical work/composition

  /** Backslash first, or the escaping escapes its own escapes. */
  private static String escape(String value) {
    return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
  }
}
