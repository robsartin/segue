package com.robsartin.segue.mcp;

import java.util.List;

/**
 * One entity plus its neighbours, grouped by the edge type that connects them — and, since ADR 39,
 * whatever the user has said about it.
 *
 * <p><b>The affinity field is the taste layer's whole read path, and it lives here rather than in a
 * seventh tool.</b> ADR 26 pins the surface at six tools; {@code note_affinity} is the sixth, so a
 * separate {@code get_affinity} would have been an ADR-level change to buy a lookup the model
 * already has a reason to make. Composing the two layers in this one view is the join ADR 33
 * anticipated ("a recommendation query has to join across the two rather than reading one
 * structure") — done in {@code SegueService}, above both ports, so neither layer learns about the
 * other. See ADR 39 for the alternatives weighed.
 *
 * <p>{@code affinity} is null for an entity the user has never rated. Null rather than a default
 * rating, because "never said" and "rated lowest" are different answers and a filter that confused
 * them would be wrong in both directions.
 */
public record EntityView(
    NodeView node, List<NeighborGroup> neighborsByType, AffinityView affinity) {}
