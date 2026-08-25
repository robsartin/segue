package com.robsartin.segue.mcp;

import java.util.List;

/** One entity plus its neighbours, grouped by the edge type that connects them. */
public record EntityView(NodeView node, List<NeighborGroup> neighborsByType) {}
