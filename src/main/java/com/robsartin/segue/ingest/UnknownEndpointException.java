package com.robsartin.segue.ingest;

import com.robsartin.segue.domain.AssertionRecord;
import java.util.List;

/**
 * A claim refused BEFORE the append, because the projection it is about holds no node for an id the
 * row names (#233).
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
 * <p><b>Two constructors, because there are two witnesses, and the message has to name the right
 * one</b> (#228). {@code record} asks the RUNNING graph, through {@code GraphStore.node}, and the
 * first constructor below writes its message: <i>"the graph holds no node for"</i>. {@code
 * IngestService.claim} asks a different projection — the LOG's fold ({@code
 * Equivalences.nodesTheFoldHolds}), because {@code claim} holds a log and no graph view at all —
 * and says <i>"the fold holds no node for"</i>. Reusing the first constructor there would say "the
 * graph" about a check that asked the log, the exact class of caller-facing misdescription ADR 27
 * exists to keep out. What is shared is the TYPE and {@link #endpoints()}, which is the part a
 * caller reads programmatically; the sentence is the gate's own.
 *
 * <p><b>The second constructor takes a built message rather than a witness phrase, and that is a
 * judgement worth stating.</b> A phrase parameter would have forced {@code claim}'s two refusals
 * into this one's skeleton, and they do not fit it: #228's reviews settled per-endpoint repair
 * advice on that path (an id shaped like a merge's canonical side can only be merged onto, never
 * minted or seeded), and the merge arm refuses a claim that names no edge at all. One skeleton for
 * three shapes would have to lose one of them. So the type carries the contract and the caller
 * carries the prose — and this class is still the only refusal type either gate throws.
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
   * The log-fold witness (#228): {@code IngestService.claim} has already written the sentence,
   * because its repair advice is per-id-shape and its merge arm refuses a claim that names no edge.
   *
   * @param endpoints the ids the fold holds no node for, for {@link #endpoints()}
   * @param message the refusal, in the fold's own words rather than the graph's
   */
  UnknownEndpointException(List<String> endpoints, String message) {
    super(message);
    this.endpoints = List.copyOf(endpoints);
  }

  /**
   * Every id the witnessing projection holds no node for, in the order checked ({@code fromQid}
   * then {@code toQid} for an edge), so a caller can name all of them without parsing the message.
   *
   * <p>On {@code IngestService.claim}'s merge arm (#228) the one id here is the merge's LOCAL side
   * — the id the fold holds no node for, which is why the canonical side would end up an endpoint
   * no later edge could land on.
   */
  public List<String> endpoints() {
    return endpoints;
  }
}
