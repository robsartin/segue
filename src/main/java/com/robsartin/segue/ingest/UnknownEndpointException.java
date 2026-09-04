package com.robsartin.segue.ingest;

import com.robsartin.segue.domain.AssertionRecord;
import java.util.List;

/**
 * A claim refused BEFORE the append, because the projection it is about holds no node for one or
 * both of its endpoints (#233).
 *
 * <p><b>Named rather than a plain {@link IllegalStateException}, for one caller's sake.</b> {@code
 * SegueService.expandEntity} has to tell this condition apart from a genuine store failure, a log
 * that cannot be written and a programmer error; catching {@code IllegalStateException} around
 * {@code IngestService.record} would swallow all three and report them as a refused edge. It
 * extends {@code IllegalStateException} anyway so that a caller which does not know about it — and
 * every existing one — sees exactly what {@code TinkerGraphStore.requireVertex} used to throw.
 *
 * <p><b>Both endpoints are checked before this is thrown, and both are named</b> (#233 final
 * review, minor 2). The first version stopped at the first missing endpoint, so an edge naming two
 * unknown entities counted as one — and {@code SegueService.expandEntity}'s "N endpoint(s)" reason,
 * which is built entirely from what this exception reports, undercounted right along with it.
 * {@link #endpoints()} is a list rather than a repeat of {@code endpoint()} so a caller cannot read
 * only the first and silently repeat the same undercount; there is no singular accessor left to
 * reach for by habit.
 *
 * <p><b>The message text — "the graph holds no node for" — names {@code record}'s own precondition,
 * not a general one, and that is a real constraint on reusing this type.</b> {@code record} asks
 * the RUNNING graph, through {@code GraphStore.node}; issue #228's `claim` gate asks a different
 * projection — the LOG's fold ({@code Equivalences.nodesTheFoldHolds}), because {@code claim} has a
 * log and no graph view at all. Adopting this type for that refusal (the spec's reconciliation note
 * says it should) cannot mean reusing this constructor as-is: a message that says "the graph" while
 * the check asked the log would misdescribe what was asked, the exact class of bug ADR 27 exists to
 * keep out of a caller-facing string. The shape that avoids it is a second constructor — or a
 * parameterised witness phrase ("the graph holds no node for" / "the log holds no claim for")
 * passed in by the caller — not a second exception type; {@code ARefusedEdgeNeverReachesTheLogTest}
 * is the test file #228's own refusal test should extend, the way this issue's tests extend it
 * rather than duplicating {@code IngestServiceTest}'s ordering assertions.
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

  private final List<String> endpoints;

  UnknownEndpointException(List<String> endpoints, AssertionRecord edge) {
    super(
        "refusing to append an edge naming "
            + (endpoints.size() == 1 ? "an entity" : "entities")
            + " the graph holds no node for: "
            + String.join(", ", endpoints)
            + " in "
            + edge.edgeKey()
            + " - record the node claim first");
    this.endpoints = List.copyOf(endpoints);
  }

  /**
   * Every endpoint nothing has claimed, in the order checked ({@code fromQid} then {@code toQid}),
   * so a caller can name all of them without parsing the message.
   */
  public List<String> endpoints() {
    return endpoints;
  }
}
