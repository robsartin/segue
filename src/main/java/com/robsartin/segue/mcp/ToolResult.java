package com.robsartin.segue.mcp;

import java.util.List;

/**
 * What a tool returns.
 *
 * <p>Carries a machine-readable payload AND a note about what did not happen, because three
 * different outcomes otherwise look identical: the source was unreachable, the entity genuinely had
 * nothing, or the result was cut short by the caller's own bound. The MCP specification expects
 * execution errors to come back as readable text the model can act on rather than as protocol
 * errors, so the shortfall belongs in the result (ADR 27).
 *
 * @param outcome one of "ok", "partial", "error" — the model reads this first
 * @param detail human-readable, and the only place a correlation id appears on failure
 */
public record ToolResult<T>(String outcome, String detail, T payload) {

  public static <T> ToolResult<T> ok(String detail, T payload) {
    return new ToolResult<>("ok", detail, payload);
  }

  public static <T> ToolResult<T> partial(String detail, T payload) {
    return new ToolResult<>("partial", detail, payload);
  }

  public static <T> ToolResult<List<T>> error(String detail) {
    return new ToolResult<>("error", detail, List.of());
  }
}
