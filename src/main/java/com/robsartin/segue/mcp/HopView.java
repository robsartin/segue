package com.robsartin.segue.mcp;

/** The wire shape of {@link com.robsartin.segue.domain.Hop}. No nullable components. */
public record HopView(NodeView from, EdgeView edge, NodeView to, boolean traversedBackwards) {}
