package com.robsartin.segue.export;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Which of the four bounded views to select. See {@link ViewSelector} for what each one contains
 * and why the list stops at four.
 */
public enum ViewKind {

  /** One {@code find_paths} result, hop by hop. Reads the graph. */
  ROUTE(false),

  /** One entity and its edges, to a depth. Reads the graph. */
  NEIGHBOURHOOD(false),

  /** Only the entities on a supplied list, and the edges between them. Reads the log. */
  SUBGRAPH(true),

  /** Everything, behind {@code --all}. Reads the log. */
  FULL(true);

  private final boolean readsTheWholeLog;

  ViewKind(boolean readsTheWholeLog) {
    this.readsTheWholeLog = readsTheWholeLog;
  }

  /**
   * True for the two views that enumerate the graph and therefore go through {@link LogProjection}
   * rather than {@link com.robsartin.segue.port.GraphStore} — the port has no enumerate-all method,
   * and ADR 19 makes the log the right place to ask.
   */
  public boolean readsTheWholeLog() {
    return readsTheWholeLog;
  }

  /** The command-line spelling: lower case, and refused by name rather than by stack trace. */
  public static ViewKind parse(String value) {
    try {
      return valueOf(value.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("unknown view " + value + ". Expected one of " + names());
    }
  }

  static String names() {
    return Arrays.stream(values())
        .map(v -> v.name().toLowerCase(Locale.ROOT))
        .collect(Collectors.joining(", "));
  }
}
