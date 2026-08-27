package com.robsartin.segue.export;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Which serialiser to hand the selected view to.
 *
 * <p>This enum is the <em>only</em> place in the exporter where a view and a format meet, and it
 * meets them at composition time: {@link ViewSelector} never sees one of these, and a {@link
 * ViewWriter} never sees a {@link ViewKind}. Adding JSON for a future UI is a third constant and a
 * third writer, and nothing else.
 *
 * <p>Each constant also owns the file extensions that <em>name</em> it, because an {@code --out}
 * ending in {@code .dot} states the caller's intent as plainly as {@code --format dot} does. See
 * {@link #forPath} and issue #57.
 */
public enum OutputFormat {

  /** Graphviz. Use {@code sfdp} or {@code neato} above a few hundred nodes, not {@code dot}. */
  DOT("dot", "gv") {
    @Override
    public ViewWriter writer() {
      return new DotWriter();
    }
  },

  /** Gephi and Cytoscape. The one that survives scale, and the one that carries attributes. */
  GRAPHML("graphml", "xml") {
    @Override
    public ViewWriter writer() {
      return new GraphMlWriter();
    }
  };

  /**
   * What to write when nothing says otherwise: neither {@code --format} nor the {@code --out}
   * extension.
   *
   * <p>DOT, because it is the format that renders in one command — {@code dot -Tsvg} is already
   * installed on the machine that asks this question — where GraphML needs Gephi before it shows
   * anything. It is the residual case only: an {@code --out} that names a format is honoured, so
   * this decides {@code --out /tmp/graph} and little else.
   */
  static final OutputFormat DEFAULT = DOT;

  private final Set<String> extensions;

  OutputFormat(String... extensions) {
    this.extensions = Set.of(extensions);
  }

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

  /**
   * The format an output path names by its extension, or empty if the extension names none.
   *
   * <p>Only the file name is inspected, so a dot in a directory name is not mistaken for an
   * extension, and a leading dot is a hidden file rather than an extension of its own. Matching is
   * case-insensitive: {@code .DOT} is the same statement as {@code .dot}.
   */
  static Optional<OutputFormat> forPath(Path out) {
    String extension = extensionOf(out);
    if (extension.isEmpty()) {
      return Optional.empty();
    }
    String suffix = extension.substring(1).toLowerCase(Locale.ROOT);
    return Arrays.stream(values()).filter(format -> format.extensions.contains(suffix)).findFirst();
  }

  /**
   * The extension of an output path exactly as it was typed, dot included; empty if it has none.
   *
   * <p>Echoed back rather than normalised, so a refusal quotes the argument the operator can see in
   * their own shell history.
   */
  static String extensionOf(Path out) {
    Path name = out.getFileName();
    if (name == null) {
      return "";
    }
    String fileName = name.toString();
    int dot = fileName.lastIndexOf('.');
    return dot <= 0 ? "" : fileName.substring(dot);
  }

  /** The lower-case name, as it is spelled on the command line and in an error message. */
  String spelling() {
    return name().toLowerCase(Locale.ROOT);
  }

  static String names() {
    return Arrays.stream(values()).map(OutputFormat::spelling).collect(Collectors.joining(", "));
  }
}
