package com.robsartin.segue.mcp;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * What a tool returns.
 *
 * <p>Carries a machine-readable payload AND a note about what did not happen, because three
 * different outcomes otherwise look identical: the source was unreachable, the entity genuinely had
 * nothing, or the result was cut short by the caller's own bound. The MCP specification expects
 * execution errors to come back as readable text the model can act on rather than as protocol
 * errors, so the shortfall belongs in the result (ADR 27).
 *
 * @param outcome one of {@code ok}, {@code partial}, {@code error} — the model reads this first
 * @param detail human-readable, and the only place a correlation id appears on failure
 * @param payload null on {@code error}; present (possibly empty) otherwise
 */
public record ToolResult<T>(Outcome outcome, String detail, @Nullable T payload) {

  public static <T> ToolResult<T> ok(String detail, T payload) {
    return new ToolResult<>(Outcome.OK, detail, payload);
  }

  public static <T> ToolResult<T> partial(String detail, T payload) {
    return new ToolResult<>(Outcome.PARTIAL, detail, payload);
  }

  /** The one error shape — {@code payload} is always null, never an empty collection. */
  public static <T> ToolResult<T> error(String detail) {
    return new ToolResult<>(Outcome.ERROR, detail, null);
  }

  /** The three outcomes a tool call can report. */
  public enum Outcome {
    OK,
    PARTIAL,
    ERROR;

    /** Lower-case on the wire — {@code "ok"}, {@code "partial"}, {@code "error"} — per ADR 26. */
    @JsonValue
    public String wireValue() {
      return name().toLowerCase(Locale.ROOT);
    }
  }
}
