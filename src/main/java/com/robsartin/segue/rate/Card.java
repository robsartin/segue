package com.robsartin.segue.rate;

import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.domain.NodeRecord;
import com.robsartin.segue.support.ClassLabels;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;

/**
 * One dealt entity, in the two shapes the page renders.
 *
 * <p><b>Two shapes, because "why is this here" only has an answer for one of them.</b> A known
 * entity is on the owner's list already, so the useful thing to show is how much the graph hangs
 * off it — which is also the number the deck sorted by, so the card explains its own position. A
 * candidate is something the owner may never have heard of, so the useful thing is the routes tying
 * it to what they know.
 *
 * <p><b>There is no note field here and there never should be.</b> Issue #85 made a rating ordinary
 * data and a note protected; the deck writes ratings, and a type with nowhere to put a note cannot
 * leak one.
 */
public record Card(
    String qid,
    String label,
    NodeKind kind,
    String classes,
    OptionalInt degree,
    List<String> routes) {

  public Card {
    Objects.requireNonNull(qid, "qid");
    Objects.requireNonNull(label, "label");
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(classes, "classes");
    Objects.requireNonNull(degree, "degree");
    routes = List.copyOf(Objects.requireNonNull(routes, "routes"));
  }

  /** An entity already on the owner's list, showing the degree the deck ordered it by. */
  public static Card known(NodeRecord node, int degree) {
    Objects.requireNonNull(node, "node");
    return new Card(
        node.qid(),
        node.label(),
        node.kind(),
        ClassLabels.describe(node.instanceOf()),
        OptionalInt.of(degree),
        List.of());
  }

  /** Something the owner does not have, shown with the routes that reached it. */
  public static Card candidate(NodeRecord node, List<String> routeLines) {
    Objects.requireNonNull(node, "node");
    return new Card(
        node.qid(),
        node.label(),
        node.kind(),
        ClassLabels.describe(node.instanceOf()),
        OptionalInt.empty(),
        routeLines);
  }
}
