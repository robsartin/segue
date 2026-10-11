package com.robsartin.segue.port;

import com.robsartin.segue.domain.Signal;
import java.util.List;

/**
 * Where a listening source's observations of the owner are kept (#350).
 *
 * <p>A seam beside {@link AffinityStore}, not a method on it: a rating is something the owner said
 * and a signal is something a source reported, and the two are weighed differently by whatever
 * blends them (#351). Neither is an assertion, so this port has no connection to {@link
 * AssertionLog} or {@link GraphStore}.
 *
 * <p>There is no read that returns one entity: nothing needs it yet, and a lookup per qid is a bulk
 * read spelled slowly.
 */
public interface SignalStore extends AutoCloseable {

  /**
   * Store one observation, replacing any earlier row for the same entity and source. Re-running a
   * source therefore overwrites its own rows and leaves every other source's alone.
   */
  void put(Signal signal);

  /** Every observation, ordered by qid then source. */
  List<Signal> readAll();

  @Override
  void close();
}
