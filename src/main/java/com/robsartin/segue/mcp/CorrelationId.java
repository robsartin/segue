package com.robsartin.segue.mcp;

import com.robsartin.segue.support.UuidV7;
import org.slf4j.MDC;

/**
 * A time-ordered identifier for one tool call, carried in MDC so every log line for that call can
 * be found from one string.
 *
 * <p>The identifier is also included in the text of failed tool results, so an error a user sees in
 * a conversation can be pasted straight into a log search. That is the difference between
 * debuggable and not (ADR 29).
 *
 * <p>Note the stdio transport has no header layer at all — per-request metadata travels in the
 * JSON-RPC body — so there is nothing to propagate a trace context from. This identifier is the
 * only correlation available there.
 */
public final class CorrelationId {

  /** MDC key, and the field name in the structured log. */
  public static final String KEY = "segue.request.id";

  private CorrelationId() {}

  /** Mint an identifier for this request and publish it to MDC. */
  public static String begin() {
    String id = UuidV7.generate().toString();
    MDC.put(KEY, id);
    return id;
  }

  /** The current request's identifier, or empty outside a request. */
  public static String current() {
    String id = MDC.get(KEY);
    return id == null ? "" : id;
  }

  /** Remove it. Always call this when the request ends, or it leaks into the next one. */
  public static void clear() {
    MDC.remove(KEY);
  }
}
