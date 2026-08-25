package com.robsartin.segue.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.util.Map;
import java.util.Objects;

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

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private ToolResults() {}

  static CallToolResult of(ToolResult<?> result) {
    Objects.requireNonNull(result, "result");
    String json;
    try {
      json = MAPPER.writeValueAsString(result);
    } catch (JsonProcessingException e) {
      // Every field on every ToolResult payload is one of this project's own records, enums,
      // primitives or Strings — there is nothing here Jackson cannot serialise. If this ever
      // throws, the payload grew a type that shouldn't be on the wire, which is a programmer
      // error the caller should see, not a shortfall to degrade gracefully from.
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
