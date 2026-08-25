package com.robsartin.segue.mcp;

import com.robsartin.segue.domain.NodeKind;

/**
 * The wire shape of {@link com.robsartin.segue.domain.NodeRecord}.
 *
 * <p>A separate type rather than serialising the domain record directly: {@code NodeRecord} has no
 * nullable components today, but the MCP tool surface is a published protocol contract and the
 * domain is not (ADR 26 amendment) — this is the seam that keeps a future domain change from being,
 * by construction, a breaking change to every client that has learned this schema.
 */
public record NodeView(String qid, NodeKind kind, String label) {}
