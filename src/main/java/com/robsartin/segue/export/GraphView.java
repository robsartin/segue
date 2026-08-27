package com.robsartin.segue.export;

import java.util.List;
import java.util.Objects;

/**
 * A picture, before anything has decided what a picture is written as.
 *
 * <p><b>This type is the whole point of the exporter's shape.</b> Choosing which nodes and edges
 * belong in a view is the durable logic — it is what a future interactive UI would reuse.
 * Serialisation is a swappable tail: DOT today, GraphML today, JSON when there is a UI to feed. So
 * {@link ViewSelector} produces one of these and knows nothing about any format, and every {@link
 * ViewWriter} consumes one of these and knows nothing about the graph. Neither imports the other.
 *
 * <p>{@code description} is what this view is, in a line — "neighbourhood of Q1 (depth 2)". It
 * reaches the console report, a DOT graph name and a GraphML {@code <desc>}, which is the only
 * thing in this record any writer is free to render however it likes.
 *
 * <p>Invariant: every edge endpoint is present in {@code nodes}. Both formats reference nodes by
 * id, and a dangling reference makes GraphML invalid rather than merely incomplete, so the selector
 * drops such an edge and counts it rather than emitting it.
 */
public record GraphView(String description, List<ViewNode> nodes, List<ViewEdge> edges) {

  public GraphView {
    Objects.requireNonNull(description, "description");
    nodes = List.copyOf(Objects.requireNonNull(nodes, "nodes"));
    edges = List.copyOf(Objects.requireNonNull(edges, "edges"));
  }

  public boolean isEmpty() {
    return nodes.isEmpty();
  }

  /** Counts, for the line printed before anything is written. */
  public String describeSize() {
    return nodes.size() + " node(s), " + edges.size() + " edge(s)";
  }

  /** True when any node carries a rating — which makes the whole output personal data (ADR 33). */
  public boolean carriesAffinity() {
    return nodes.stream().anyMatch(n -> n.affinity() != null);
  }
}
