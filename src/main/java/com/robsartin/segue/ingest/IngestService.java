package com.robsartin.segue.ingest;

import com.robsartin.segue.domain.AssertionRecord;
import com.robsartin.segue.domain.Equivalences;
import com.robsartin.segue.domain.LocalEntity;
import com.robsartin.segue.domain.LoggedAssertion;
import com.robsartin.segue.domain.NodeAssertion;
import com.robsartin.segue.domain.NodeRecord;
import com.robsartin.segue.domain.OwnerEdge;
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

  /**
   * Append one claim to the log, then apply it to the graph.
   *
   * <p><b>The equivalences are {@link Equivalences#NONE} here, and that is a stated limitation
   * rather than an oversight</b> (#178). {@link #apply} now folds an edge's endpoints through the
   * merges the log holds, and this path has no such view: it sees one claim, not the log, and
   * reading the whole log back on every single write is not a trade this method can make. So a
   * {@link SameAs} arriving here appends and creates its canonical node, and the edges already
   * recorded against the local id stay where they are until the next boot moves them.
   *
   * <p><b>Refusing it was considered and is not what this does.</b> Nothing in production sends one
   * — {@code OwnRun} appends a merge through {@link #claim}, which has no graph half at all — so a
   * refusal would fence a path nobody walks, and it would take the live rating carry with it
   * ({@code MergeCarriesEverythingTest} asserts that half deliberately against the live stores,
   * because affinity is durable and is rebuilt by nothing). Staleness until the next boot is the
   * contract {@link #retract} already gives for exactly the same reason (ADR 24): {@link
   * GraphStore} cannot remove or rewrite an edge, so the running graph catches up when it is
   * rebuilt, not before. The same limitation applies to any edge claimed here against an id the log
   * has already merged; {@code OwnRun.labelOrRefuse} refuses to make one, and spec ruling 2 says
   * the fold must not depend on that refusal — it does not, because replay folds it anyway.
   */
  public void record(LoggedAssertion assertion) {
    Objects.requireNonNull(assertion, "assertion");
    if (assertion instanceof Retraction) {
      // Refused before the append, not after: this method's whole contract is log-then-graph,
      // and a retraction has no graph half. Appending one here and then failing would leave a
      // retraction in the log that the caller had been told did not happen.
      throw new IllegalArgumentException("a retraction is appended by retract(), not record()");
    }
    log.append(assertion);
    apply(graph, merges, Equivalences.NONE, assertion);
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

  /**
   * Append one of the owner's own claims (#92). The fourth write path, and the second with no graph
   * half at the moment of writing.
   *
   * <p><b>Static, and taking the log, for {@link #retract}'s reason exactly.</b> A minted entity,
   * an owner edge and a merge all <em>do</em> project to the graph - {@link #apply} has a case for
   * each - but the thing that makes them is a dev-side tool with no running graph to apply them to.
   * Requiring an {@code IngestService} instance would mean handing that tool a {@link GraphStore}
   * it must never touch, purely so a constructor could be satisfied, which is the opposite of the
   * fence it needs. The graph catches up the way ADR 24 already says it does: rebuilt from the log
   * at the next boot, through {@link GraphProjector}, through this class's own {@code apply}.
   *
   * <p>The append still happens inside {@code ingest}, so {@code onlyIngestAppliesClaimsToTheGraph}
   * holds unchanged and the tool can be forbidden a graph outright.
   *
   * <p><b>Only the owner's three.</b> A sourced claim has a graph half that this path cannot
   * perform, so appending one here would put a row in the log that never reached the running graph
   * - {@link #record} is the path with both halves. A retraction belongs to {@link #retract}. The
   * switch is over the sealed interface rather than an {@code instanceof} chain, so a fourth claim
   * type cannot be added without deciding which of the three paths writes it.
   */
  public static void claim(AssertionLog log, LoggedAssertion claim) {
    Objects.requireNonNull(log, "log");
    Objects.requireNonNull(claim, "claim");
    switch (claim) {
      case LocalEntity ignored -> {}
      case OwnerEdge ignored -> {}
      case SameAs ignored -> {}
      case NodeAssertion ignored ->
          throw new IllegalArgumentException(
              "a sourced claim is appended and applied by record(), not claim()");
      case AssertionRecord ignored ->
          throw new IllegalArgumentException(
              "a sourced claim is appended and applied by record(), not claim()");
      case Retraction ignored ->
          throw new IllegalArgumentException("a retraction is appended by retract(), not claim()");
    }
    log.append(claim);
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
   *
   * <p><b>Every endpoint is read through the equivalences before anything is applied</b> (#178).
   * That is the whole of the graph half of a merge: an edge claimed against a merged local id is
   * applied to the canonical id, once, instead of being applied to the local id and copied. The
   * rule itself is {@link Equivalences#foldEndpoints}, in {@code domain}, so that {@code
   * LogProjection} folds with the same code rather than with the same idea — {@code
   * BothFoldsAgreeTest} is the test that the two hold one graph, and a rule written twice is a rule
   * that can be corrected once.
   *
   * @param equivalences the merges the log holds. {@link Equivalences#NONE} from {@link #record},
   *     which sees a claim rather than a log - see that method for the limitation that states
   */
  static void apply(
      GraphStore graph,
      IdentityMerge merges,
      Equivalences equivalences,
      LoggedAssertion assertion) {
    Objects.requireNonNull(equivalences, "equivalences");
    Optional<LoggedAssertion> folded = equivalences.foldEndpoints(assertion);
    if (folded.isEmpty()) {
      // The fold collapsed both endpoints onto one id, so there is no edge left to apply. See
      // Equivalences.foldEndpoints: an equivalence does not make a thing relate to itself.
      return;
    }
    switch (folded.get()) {
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
      // A merge is an asserted equivalence, never an edit (ADR 19, ADR 44). Its edge half is no
      // longer here at all: the fold above put every edge on the canonical id as it was applied,
      // so there is nothing left to copy and the local id is left exactly where it was, node and
      // all, so every earlier log entry keeps meaning what it meant (ADR 59, amended by #178).
      case SameAs merge -> {
        standIn(graph, merge);
        // The taste half, and it runs on replay too - see standIn()'s last paragraph and
        // IdentityMerge, which together say why that is a repair rather than a hazard.
        merges.follow(merge.localQid(), merge.canonicalQid());
      }
    }
  }

  /**
   * Give the id Wikidata turned out to have a node, so that an edge folded onto it has somewhere to
   * land.
   *
   * <p><b>The edge half used to be here and is gone</b> (#178). It <em>copied</em> every edge off
   * the local id onto the canonical one and left the originals, so two nodes carried one entity's
   * edges and every neighbour of a merged entity had one more incident edge than the world
   * justified — measured in {@code docs/superpowers/specs/2026-09-02-merge-degree-design.md}, up to
   * 12.5 % off a candidate's score, enough to unseat rank 1. {@link Equivalences#foldEndpoints} now
   * puts each edge on the canonical id as it is applied, so there is nothing to copy afterwards.
   * What is left is this: the node.
   *
   * <p><b>Nothing is removed.</b> The local node stays, exactly as ADR 59's merge bullet says — a
   * route or a rating recorded last month still names the local id, and a projection that deleted
   * it would make those entries unreadable while the log still holds them. It is its <em>edges</em>
   * that the amendment moves, and it is drawn thereafter as the orphan it has become (spec ruling
   * 3).
   *
   * <p><b>The canonical id gets a node only when nothing has claimed one.</b> A merge is usually
   * declared before any source has expanded the real item, and {@code TinkerGraphStore.record}
   * requires both endpoints to exist. When a source HAS named the entity, that claim wins: {@code
   * upsertNode} is last-writer-wins, and overwriting a source's label with the owner's working
   * title would be the merge editing the world rather than recording an identity. The stand-in
   * carries no {@code instanceOf}: it copies a kind, and putting the local side's classes on the
   * canonical id would report as that entity's what no source has stated about it - the same reason
   * {@link LocalEntity#toNode()} carries none. This used to read "because the owner stated no
   * classes", which was true only of the path the owner's tool takes: on the bypass path (#222) the
   * local side is a {@link NodeAssertion} that DID state classes, and they stay where they were
   * claimed, on the local node.
   *
   * <p><b>On replay this is a second helping of a job already done, deliberately.</b> {@link
   * Equivalences#standIns} builds the same node from the same rule before the fold begins, and it
   * has to, because a folded edge can be claimed <em>earlier</em> in the log than the merge that
   * names its endpoint. This one is the live path's copy, where there is no pre-pass and no whole
   * log to read: it is what keeps {@code record(SameAs)} from leaving a canonical id the running
   * graph has never heard of. The two agree by construction — same guard, same fields — and {@code
   * MergeCarriesEverythingTest} holds them to it.
   *
   * <p><b>Order is log order, deliberately.</b> This reads the graph as it stands at the moment the
   * merge is applied. That matches {@link com.robsartin.segue.domain.Retractions}, which also asks
   * what had already been said when the decision was made.
   *
   * <p><b>Replay carries the rating, and the first version of this task said otherwise on an
   * argument that measurement contradicts.</b> That argument was "a rating carried again at every
   * boot would overwrite whatever the owner has said since". It would not: {@code
   * IdentityMerge.carryingRatings} refuses to overwrite a rating whose {@code updatedAt} is newer,
   * so a replayed carry over a canonical id the owner has re-rated changes nothing. The only case a
   * replayed carry alters is a local id re-rated <em>after</em> the merge, where it moves the
   * owner's most recent word onto the canonical id - which is the repair, not the loss. Keeping it
   * out of replay had a real cost instead: a merge logged by a build that could not carry, or wired
   * to {@link IdentityMerge#NONE}, would strand its rating forever, because affinity is the one
   * thing replay does not rebuild.
   */
  private static void standIn(GraphStore graph, SameAs merge) {
    String local = merge.localQid();
    String canonical = merge.canonicalQid();
    Optional<NodeRecord> minted = graph.node(local);
    if (minted.isEmpty()) {
      // Nothing has been claimed under the local id, so there is nothing to stand in for. Not an
      // error: the log is append-only and a merge may legitimately be replayed before the claim it
      // resolves has been re-applied - Retractions can also have dropped that claim and kept this
      // row, when the retraction lies between them. Equivalences.localsOfMerges asks the same
      // question of the log, in the same words, for both folds' pre-pass.
      return;
    }
    if (graph.node(canonical).isEmpty()) {
      graph.upsertNode(
          new NodeRecord(canonical, minted.get().kind(), minted.get().label(), List.of()));
    }
  }
}
