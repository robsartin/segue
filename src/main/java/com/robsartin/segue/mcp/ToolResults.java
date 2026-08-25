package com.robsartin.segue.mcp;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.util.Map;
import java.util.Objects;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Builds the {@link CallToolResult} every {@code @McpTool} method returns (FIX 1 of the
 * increment-4a final review).
 *
 * <p>{@code generateOutputSchema = true} put every tool on Spring AI's STRUCTURED-mode path: {@code
 * SyncMcpToolMethodCallback} converts whatever the method returns into a {@code CallToolResult}
 * carrying only {@code structuredContent}, never {@code isError} and never a text block — {@code
 * content} came back {@code []} on every call, success or failure alike, which inverted ADR 27's
 * error convention and would render blank in any client that only shows {@code content}. Returning
 * {@code CallToolResult} directly opts out of that path entirely — {@code SyncMcpToolProvider}
 * skips schema generation and content conversion for a method whose return type already is the
 * protocol's own result type — so this class does by hand what ADR 26 actually asks for: the JSON
 * as {@code structuredContent}, the same JSON again as a text block for clients that render only
 * {@code content}, and {@code isError} set from the result's own outcome.
 */
final class ToolResults {

  /**
   * Stock Jackson 3, deliberately unconfigured. Jackson 3 registers java.time support itself and
   * writes an {@code Instant} as ISO-8601 by default, so {@code ProvenanceView.assertedAt} — the
   * one java.time value on the tool surface — needs no module and no feature flag here. The Jackson
   * 2 mapper this replaces needed both, and shipped without them (issue #18).
   */
  private static final ObjectMapper MAPPER = JsonMapper.builder().build();

  private ToolResults() {}

  static CallToolResult of(ToolResult<?> result) {
    Objects.requireNonNull(result, "result");
    String json;
    try {
      json = MAPPER.writeValueAsString(result);
    } catch (JacksonException e) {
      // A ToolResult payload is built from this project's own records, enums, primitives,
      // Strings and the java.time values Jackson 3 handles natively. If this throws, the payload
      // grew a type the mapper has no handler for — a programmer error the caller should see,
      // not a shortfall to degrade gracefully from. It is not hypothetical: this is exactly how
      // find_paths shipped broken in increment 4a (issue #18), which is why ToolResultsTest now
      // puts a provenance-bearing payload through this method.
      throw new IllegalStateException("tool result is not serializable: " + result, e);
    }
    @SuppressWarnings("unchecked")
    Map<String, Object> structured = MAPPER.convertValue(result, Map.class);
    return CallToolResult.builder()
        .addTextContent(json)
        .structuredContent(structured)
        .isError(result.outcome() == ToolResult.Outcome.ERROR)
        .build();
  }
}
