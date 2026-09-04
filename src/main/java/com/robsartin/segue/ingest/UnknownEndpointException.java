package com.robsartin.segue.ingest;

import com.robsartin.segue.domain.AssertionRecord;

/**
 * A claim refused BEFORE the append, because the projection it is about holds no node for one of
 * its endpoints (#233).
 *
 * <p><b>Named rather than a plain {@link IllegalStateException}, for one caller's sake.</b> {@code
 * SegueService.expandEntity} has to tell this condition apart from a genuine store failure, a log
 * that cannot be written and a programmer error; catching {@code IllegalStateException} around
 * {@code IngestService.record} would swallow all three and report them as a refused edge. It
 * extends {@code IllegalStateException} anyway so that a caller which does not know about it — and
 * every existing one — sees exactly what {@code TinkerGraphStore.requireVertex} used to throw.
 *
 * <p><b>The repair it names is the one that is correct AT THIS MOMENT, and only at this moment.</b>
 * Before the append, recording the node claim first fixes it. After the append it does not: replay
 * is positional, so a node claim appended later than the edge still leaves the boot failing at the
 * edge's own sequence number — measured for #233. The repair for a log that already carries such a
 * row is to retract the endpoint, which withdraws the edge under ADR 44 without deleting anything,
 * and that sentence belongs to the boot diagnosis rather than here.
 */
public final class UnknownEndpointException extends IllegalStateException {

  private static final long serialVersionUID = 1L;

  private final String endpoint;

  UnknownEndpointException(String endpoint, AssertionRecord edge) {
    super(
        "refusing to append an edge naming an entity the graph holds no node for: "
            + endpoint
            + " in "
            + edge.edgeKey()
            + " - record the node claim first");
    this.endpoint = endpoint;
  }

  /** The endpoint nothing has claimed, so a caller can name it without parsing the message. */
  public String endpoint() {
    return endpoint;
  }
}
