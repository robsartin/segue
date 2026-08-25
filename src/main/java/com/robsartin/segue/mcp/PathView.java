package com.robsartin.segue.mcp;

import java.util.List;

/**
 * The wire shape of {@link com.robsartin.segue.domain.PathResult} — a citable route, hop by hop.
 */
public record PathView(List<HopView> hops) {}
