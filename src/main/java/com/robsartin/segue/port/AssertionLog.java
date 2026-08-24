package com.robsartin.segue.port;

import com.robsartin.segue.domain.LoggedAssertion;
import java.util.List;

/**
 * The append-only source of truth (ADR 19), behind a port so its store stays as replaceable as the
 * graph engine (ADR 24). The access pattern is deliberately narrow: appends during ingest and one
 * full ordered read at boot, with exactly one writer.
 *
 * <p>ADR 24: an assertion is appended here <em>before</em> it is applied to the graph, and the two
 * are not atomic - a crash between them leaves the log ahead, which a restart replays correct.
 */
public interface AssertionLog extends AutoCloseable {

  /** Append one claim to the end of the log. */
  void append(LoggedAssertion assertion);

  /** Every logged assertion in sequence order - what {@code GraphProjector} replays at boot. */
  List<LoggedAssertion> readAll();

  @Override
  void close();
}
