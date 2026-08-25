package com.robsartin.segue.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.segue.domain.NodeKind;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers the serialisation boundary itself — the one layer the increment-4a tests stepped over.
 *
 * <p>{@link ToolSurfaceTest} reflects on each tool method's RETURN TYPE, and {@link
 * SegueServiceTest} stops at the service, which hands back a {@link ToolResult} record before
 * anything is written as JSON. Neither one ever calls {@link ToolResults#of}, so a payload Jackson
 * cannot serialise reached a green build and failed only in a live client (issue #18).
 *
 * <p>{@code assertedAt} is the whole reason this test exists: it is the only {@code java.time}
 * value anywhere on the tool surface, and a route's timestamp is a citation, so it has to arrive as
 * an ISO-8601 string rather than an epoch number.
 */
class ToolResultsTest {

  private static final Instant ASSERTED_AT = Instant.parse("2026-08-25T18:39:16.850523Z");

  @Test
  @DisplayName("a route's provenance timestamp reaches the wire as an ISO-8601 string")
  void provenanceTimestampSerialisesAsIso8601() {
    CallToolResult wire = ToolResults.of(ToolResult.ok("1 route(s)", List.of(oneHopRoute())));

    String json = ((TextContent) wire.content().get(0)).text();
    assertThat(json).contains("\"assertedAt\":\"" + ASSERTED_AT + "\"");
  }

  /** The shape that broke: a path whose hop carries an edge whose source carries a timestamp. */
  private static PathView oneHopRoute() {
    NodeView cave = new NodeView("Q192668", NodeKind.PERSON, "Nick Cave");
    NodeView film = new NodeView("Q180337", NodeKind.WORK, "The Proposition");
    EdgeView edge =
        new EdgeView(
            "Q192668",
            "Q180337",
            "COMPOSED_FOR",
            null,
            null,
            List.of(new ProvenanceView("wikidata", "Q180337$77708F66", ASSERTED_AT, 0.8)));
    return new PathView(List.of(new HopView(cave, edge, film, false)));
  }
}
