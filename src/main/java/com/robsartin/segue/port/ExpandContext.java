package com.robsartin.segue.port;

/**
 * Bounds on a single expansion.
 *
 * <p>Deliberately one field. The MCP specification requires servers to rate-limit tool invocations,
 * and an unbounded expansion of a well-connected entity is the obvious way to violate that by
 * accident. More knobs arrive when something needs them, not before.
 *
 * @param maxNewEdges the most assertions an adapter may return from one call
 */
public record ExpandContext(int maxNewEdges) {

  public ExpandContext {
    if (maxNewEdges <= 0) {
      throw new IllegalArgumentException("maxNewEdges must be positive, got: " + maxNewEdges);
    }
  }

  /** A sensible ceiling for an interactive expansion. */
  public static ExpandContext defaults() {
    return new ExpandContext(200);
  }
}
