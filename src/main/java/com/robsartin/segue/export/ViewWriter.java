package com.robsartin.segue.export;

import java.io.IOException;
import java.io.Writer;
import java.util.Optional;

/**
 * Turns a {@link GraphView} into one file format, and does nothing else.
 *
 * <p>An implementation is a pure function of the view: same view in, same bytes out, no store, no
 * query, no clock. That is what makes both writers unit-testable against invented fixtures with no
 * database and no network, and it is what lets a future UI add a JSON writer here without touching
 * a line of selection logic.
 *
 * <p>It streams rather than returning a {@code String} because the {@code full} view of a real
 * personal graph is tens of thousands of nodes; purity is about the absence of hidden state, not
 * about the return type.
 */
public interface ViewWriter {

  /** The file extension this writer produces, without a dot: {@code dot}, {@code graphml}. */
  String extension();

  /** Write {@code view} to {@code out}. The writer never closes what it was handed. */
  void write(GraphView view, Writer out) throws IOException;

  /**
   * Anything the operator should be told about what this format will do to this view, or empty when
   * the answer is "nothing worth saying".
   *
   * <p><b>A writer that drops something says so here.</b> DOT stops labelling edges above a density
   * it cannot draw them at (issue #70), and a tool that quietly loses information invites someone
   * to wonder where it went — the same principle as the dangling-edge count. {@link ExportRun}
   * reports it before the file exists, beside the counts, without asking which format it holds:
   * suppression is the writer's decision, so the sentence describing it is the writer's too.
   *
   * <p>It is asked of the same view that is about to be written, so it is a pure function of the
   * view like {@link #write}, and a writer with nothing to report needs no code at all.
   */
  default Optional<String> note(GraphView view) {
    return Optional.empty();
  }
}
