package com.robsartin.segue.export;

import java.util.Objects;

/**
 * One relationship in a {@link GraphView}.
 *
 * <p>The three attributes beyond the endpoints are the ones a person actually filters and colours
 * on in Gephi or Cytoscape: what kind of relationship it is, how much the graph believes it, and
 * who said so. {@code confidence} is the edge's best supporting provenance — ADR 31 ranks a route
 * by its weakest hop and demotes hub intermediates, so being able to select the weak and
 * hub-adjacent edges by eye is the whole reason this attribute travels.
 *
 * <p>{@code sourceId} is every distinct source that asserted the relationship, joined by {@code
 * "|"}. Usually there is exactly one; more than one is corroboration, which is worth seeing.
 */
public record ViewEdge(
    String fromQid, String toQid, String typeCode, double confidence, String sourceId) {

  public ViewEdge {
    Objects.requireNonNull(fromQid, "fromQid");
    Objects.requireNonNull(toQid, "toQid");
    Objects.requireNonNull(typeCode, "typeCode");
    Objects.requireNonNull(sourceId, "sourceId");
  }
}
