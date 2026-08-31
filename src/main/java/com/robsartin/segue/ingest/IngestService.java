package com.robsartin.segue.ingest;

import com.robsartin.segue.domain.AssertionRecord;
import com.robsartin.segue.domain.EdgeRecord;
import com.robsartin.segue.domain.LocalEntity;
import com.robsartin.segue.domain.LoggedAssertion;
import com.robsartin.segue.domain.NodeAssertion;
import com.robsartin.segue.domain.NodeRecord;
import com.robsartin.segue.domain.OwnerEdge;
import com.robsartin.segue.domain.Provenance;
import com.robsartin.segue.domain.Retraction;
import com.robsartin.segue.domain.SameAs;
import com.robsartin.segue.port.AssertionLog;
import com.robsartin.segue.port.GraphStore;
import com.robsartin.segue.port.IdentityMerge;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The only thing in the system that writes.
 *
 * <p>Source adapters and, later, MCP tools hand claims to this and never touch a store. ArchUnit
 * enforces that (rule {@code onlyIngestAppliesClaimsToTheGraph}), which turns ADR 19's invariant
 * from a convention into a build failure.
 *
 * <p><b>Order matters and is not an accident.</b> The log is appended first, then the graph is
 * updated, and the two are deliberately not atomic. If the graph update fails, the log is ahead —
 * the recoverable direction, because a restart replays it. The reverse ordering would lose the
 * claim permanently and leave the log authoritative in name only.
 */
public final class IngestService {

  private final AssertionLog log;
  private final GraphStore graph;
  private final IdentityMerge merges;

  /**
   * @param merges what follows a merge outside the graph. Required rather than defaulted, and
   *     {@link IdentityMerge#NONE} says so out loud where there is nothing to follow - see that
   *     constant for why a silent default is the wrong shape here
   */
  public IngestService(AssertionLog log, GraphStore graph, IdentityMerge merges) {
    this.log = Objects.requireNonNull(log, "log");
    this.graph = Objects.requireNonNull(graph, "graph");
    this.merges = Objects.requireNonNull(merges, "merges");
  }

  /** Append one claim to the log, then apply it to the graph. */
  public void record(LoggedAssertion assertion) {
    Objects.requireNonNull(assertion, "assertion");
    if (assertion instanceof Retraction) {
      // Refused before the append, not after: this method's whole contract is log-then-graph,
      // and a retraction has no graph half. Appending one here and then failing would leave a
      // retraction in the log that the caller had been told did not happen.
      throw new IllegalArgumentException("a retraction is appended by retract(), not record()");
    }
    log.append(assertion);
    apply(graph, merges, assertion);
  }

  /**
   * Append a retraction (ADR 44). The third write path, and the only one that touches no graph.
   *
   * <p><b>Static, and taking the log, deliberately.</b> Every other write here is log-then-graph,
   * and a retraction has no graph half: {@link GraphStore} cannot remove anything, and widening the
   * port that exists to keep the engine choice reversible (ADR 18) so that a dev-side tool can is
   * what ADR 41 already refused. So the running graph is stale until the next boot rebuilds it from
   * the log, which is exactly the contract ADR 24 already gives replay.
   *
   * <p>Requiring an {@code IngestService} instance would mean handing the retraction tool a {@code
   * GraphStore} it must never touch, purely so a constructor could be satisfied - the opposite of
   * the fence that tool needs. This way the append still happens inside {@code ingest}, so {@code
   * onlyIngestAppliesClaimsToTheGraph} holds unchanged and the tool can be forbidden a graph
   * outright.
   */
  public static void retract(AssertionLog log, Retraction retraction) {
    Objects.requireNonNull(log, "log");
    Objects.requireNonNull(retraction, "retraction");
    log.append(retraction);
  }

  /** Record a batch in order; each claim is logged and applied before the next is considered. */
  public void recordAll(List<LoggedAssertion> assertions) {
    Objects.requireNonNull(assertions, "assertions");
    assertions.forEach(this::record);
  }

  /**
   * Apply a claim to a graph.
   *
   * <p>Shared with {@link GraphProjector} so replay and live ingest cannot drift. Two copies of
   * this switch would be free to disagree, and a rebuilt graph that silently differs from the one
   * it replaced defeats the point of having a log at all.
   */
  static void apply(GraphStore graph, IdentityMerge merges, LoggedAssertion assertion) {
    switch (assertion) {
      case NodeAssertion node -> graph.upsertNode(node.toNode());
      case AssertionRecord edge -> graph.record(edge);
      // Unreachable, and a guard rather than a path. A retraction is honoured by the FOLD - both
      // projections drop it and everything it retracts before they get here (ADR 44) - so
      // reaching this line means a caller replayed the log without applying that rule, which
      // would produce a graph still holding the edges somebody took back out. Silently ignoring
      // it is the one response that would hide exactly that.
      case Retraction retraction ->
          throw new IllegalStateException(
              "a retraction is honoured by the projection's fold, never applied to a graph: "
                  + retraction.qid());
      // The owner's own claims (#92) project exactly like the sourced ones - a minted entity is
      // a node, an asserted relationship is an edge - and differ only in the provenance they
      // carry, which each record decides for itself (LocalEntity.toNode, OwnerEdge.toAssertion)
      // so replay and this switch cannot attribute the same claim differently.
      case LocalEntity local -> graph.upsertNode(local.toNode());
      case OwnerEdge edge -> graph.record(edge.toAssertion());
      // A merge is an asserted equivalence, never an edit (ADR 19, ADR 44): the claims already
      // made against the local id are carried onto the canonical one, and the local id is left
      // exactly where it was so every earlier log entry keeps meaning what it meant.
      case SameAs merge -> {
        carry(graph, merge);
        // The taste half, and it runs on replay too - see carry()'s last paragraph and
        // IdentityMerge, which together say why that is a repair rather than a hazard.
        merges.follow(merge.localQid(), merge.canonicalQid());
      }
    }
  }

  /**
   * Carry what the owner claimed about a local id onto the id Wikidata turned out to have.
   *
   * <p><b>Nothing is removed.</b> The local node stays, its edges stay, and the canonical id gains
   * copies. That is what "a merged local id stays resolvable" means in practice - a route or a
   * rating recorded last month still names the local id, and a projection that deleted it would
   * make those entries unreadable while the log still holds them.
   *
   * <p><b>The canonical id gets a node only when nothing has claimed one.</b> A merge is usually
   * declared before any source has expanded the real item, and {@code TinkerGraphStore.record}
   * requires both endpoints to exist - so without this, a carried edge would throw, and it would
   * throw again at every boot on a log row ADR 19 forbids deleting. When a source HAS named the
   * entity, that claim wins: {@code upsertNode} is last-writer-wins, and overwriting a source's
   * label with the owner's working title would be the merge editing the world rather than recording
   * an identity. The stand-in carries no {@code instanceOf}, because the owner stated no classes -
   * the same reason {@link LocalEntity#toNode()} carries none.
   *
   * <p><b>Order is log order, deliberately.</b> This reads the graph as it stands at the moment the
   * merge is applied, so claims appended <em>after</em> a merge stay on the id they were made
   * against. That matches {@link com.robsartin.segue.domain.Retractions}, which also asks what had
   * already been said when the decision was made, and it keeps live ingest and replay identical.
   *
   * <p><b>Replay carries the rating as well as the edges, and the first version of this task said
   * otherwise on an argument that measurement contradicts.</b> That argument was "a rating carried
   * again at every boot would overwrite whatever the owner has said since". It would not: {@code
   * IdentityMerge.carryingRatings} refuses to overwrite a rating whose {@code updatedAt} is newer,
   * so a replayed carry over a canonical id the owner has re-rated changes nothing. The only case a
   * replayed carry alters is a local id re-rated <em>after</em> the merge, where it moves the
   * owner's most recent word onto the canonical id - which is the repair, not the loss. Keeping it
   * out of replay had a real cost instead: a merge logged by a build that could not carry, or wired
   * to {@link IdentityMerge#NONE}, would strand its rating forever, because affinity is the one
   * thing replay does not rebuild.
   */
  private static void carry(GraphStore graph, SameAs merge) {
    String local = merge.localQid();
    String canonical = merge.canonicalQid();
    Optional<NodeRecord> minted = graph.node(local);
    if (minted.isEmpty()) {
      // Nothing has been claimed under the local id, so there is nothing to carry. Not an error:
      // the log is append-only and a merge may legitimately be replayed before the claim it
      // resolves has been re-applied - Retractions can also have dropped that claim and kept this
      // row, when the retraction lies between them.
      return;
    }
    if (graph.node(canonical).isEmpty()) {
      graph.upsertNode(
          new NodeRecord(canonical, minted.get().kind(), minted.get().label(), List.of()));
    }
    for (EdgeRecord edge : graph.edges(local)) {
      String from = edge.fromQid().equals(local) ? canonical : edge.fromQid();
      String to = edge.toQid().equals(local) ? canonical : edge.toQid();
      // Every supporting assertion, not one: an edge holds the provenance of everyone who claimed
      // it, and re-recording it as a single assertion would drop the others and change what
      // EdgeRecord.corroboration() counts on the canonical id.
      for (Provenance source : edge.sources()) {
        graph.record(
            new AssertionRecord(
                from, to, edge.typeCode(), edge.validFrom(), edge.validTo(), source));
      }
    }
  }
}
