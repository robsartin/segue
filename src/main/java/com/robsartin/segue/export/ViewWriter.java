package com.robsartin.segue.export;

import java.io.IOException;
import java.io.Writer;

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
}
