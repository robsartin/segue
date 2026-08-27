package com.robsartin.segue.export;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Which serialiser to hand the selected view to.
 *
 * <p>This enum is the <em>only</em> place in the exporter where a view and a format meet, and it
 * meets them at composition time: {@link ViewSelector} never sees one of these, and a {@link
 * ViewWriter} never sees a {@link ViewKind}. Adding JSON for a future UI is a third constant and a
 * third writer, and nothing else.
 */
public enum OutputFormat {

  /** Graphviz. Use {@code sfdp} or {@code neato} above a few hundred nodes, not {@code dot}. */
  DOT {
    @Override
    public ViewWriter writer() {
      return new DotWriter();
    }
  },

  /** Gephi and Cytoscape. The one that survives scale, and the one that carries attributes. */
  GRAPHML {
    @Override
    public ViewWriter writer() {
      return new GraphMlWriter();
    }
  };

  public abstract ViewWriter writer();

  /** The command-line spelling: lower case, and refused by name rather than by stack trace. */
  public static OutputFormat parse(String value) {
    try {
      return valueOf(value.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          "unknown format " + value + ". Expected one of " + names());
    }
  }

  static String names() {
    return Arrays.stream(values())
        .map(v -> v.name().toLowerCase(Locale.ROOT))
        .collect(Collectors.joining(", "));
  }
}
