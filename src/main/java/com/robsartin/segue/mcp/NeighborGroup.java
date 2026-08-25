package com.robsartin.segue.mcp;

import java.util.List;

/**
 * Every neighbour connected by one edge type, together (FIX 3 of the increment-4a final review).
 *
 * <p>Replaces a {@code Map<String, List<NodeView>>} on {@link EntityView}, which a JSON Schema can
 * only describe as an opaque {@code {"type":"object"}} — additionalProperties with no fixed key set
 * is not expressible as a typed schema. A list of {@code (typeCode, neighbours)} pairs carries
 * exactly the same information as real, inspectable structure.
 */
public record NeighborGroup(String typeCode, List<NodeView> neighbors) {}
