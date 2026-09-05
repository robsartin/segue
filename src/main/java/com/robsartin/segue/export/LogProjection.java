package com.robsartin.segue.export;

import com.robsartin.segue.domain.AssertionRecord;
import com.robsartin.segue.domain.EdgeRecord;
import com.robsartin.segue.domain.Equivalences;
import com.robsartin.segue.domain.Fold;
import com.robsartin.segue.domain.LocalEntity;
import com.robsartin.segue.domain.LoggedAssertion;
import com.robsartin.segue.domain.NodeAssertion;
import com.robsartin.segue.domain.NodeRecord;
import com.robsartin.segue.domain.OwnerEdge;
import com.robsartin.segue.domain.Provenance;
import com.robsartin.segue.domain.Retraction;
import com.robsartin.segue.domain.Retractions;
import com.robsartin.segue.domain.SameAs;
import com.robsartin.segue.port.AssertionLog;
import com.robsartin.segue.wikidata.KindMapper;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The whole graph, folded out of the append-only log.
 *
 * <p><b>Why the log and not the graph.</b> {@link com.robsartin.segue.port.GraphStore} has no
 * enumerate-all method, and the {@code full} and {@code subgraph} views need one. Adding it would
 * widen the port that exists to make the engine choice reversible (ADR 18), for the benefit of a
 * dev-side tool — and it is unnecessary, because ADR 19 makes the log the source of truth and the
 * graph a projection of it. Reading the log is the correct answer as well as the cheap one.
 *
 * <p>This is deliberately the same fold the graph performs, not a second model of it: assertions
 * about one {@code (from, type, to)} collapse into one {@link EdgeRecord} carrying every supporting
 * {@link Provenance}, which is what makes {@code corroboration()} countable (ADR 19); different
 * types between one pair stay separate edges, because the store is a multigraph. A later node claim
 * about an entity overwrites an earlier one, matching {@code upsertNode}.
 *
 * <p><b>Node kinds are re-derived from the classes the claim recorded</b>, through the same {@link
 * KindMapper#rederive} the boot projection uses (issue #60, ADR 42). Not a second rule: an exported
 * picture that disagreed with the running graph about what a node IS would be worse than no
 * picture, and DOT colours and shapes every node by its kind. A merge's stand-in node goes through
 * the same rule, because it stands in for a node this fold re-derived (#222).
 *
 * <p><b>Retractions are honoured through the same shared rule</b>, {@link Retractions} (ADR 44,
 * issue #68), and for a stronger version of the same argument: a picture still showing edges the
 * graph has dropped is not a stale detail, it is a false record of what is in the graph - and an
 * export is the artefact somebody keeps, mails or opens in Gephi weeks later. {@code
 * GraphProjector} asks the identical question of the identical log.
 *
 * <p><b>A merge is applied here too, in full</b> (#92), because a merge the export ignored would
 * show an entity hanging off a retired local id with no canonical node at all while {@code
 * get_entity} showed the opposite - the divergence the paragraph above forbids, in its worst form.
 * {@code BothFoldsAgreeTest} covers the third layer as well as retraction, which is what stops the
 * two from drifting apart again. The canonical node comes from {@link Equivalences#standIns}, which
 * the boot replay also seeds itself with, before either fold begins, and every edge's endpoints are
 * read through {@link Equivalences#foldEndpoints} as the edge is folded, so a merged entity's edges
 * exist once rather than twice (#178). That is the same method {@code IngestService.apply} calls -
 * one rule, not two agreeing ideas.
 *
 * <p><b>The local id keeps its node and loses its edges</b>, which is ADR 59's merge bullet as #178
 * amends it: an equivalence is not new evidence, so a merged entity is one node carrying one set of
 * edges, and the id the owner retired is drawn as the orphan it now is (spec ruling 3). Nothing
 * hides it, on the retraction chapter's precedent.
 *
 * <p><b>Nodes and edges come out in log order</b> - the position of the first surviving claim that
 * names them - and the map holding them keeps it, which {@code Map.copyOf} did not (issue #207).
 * Its iteration order is unspecified and salted per JVM, so two exports of one unchanged log came
 * out in two orders, a diff between them was noise and a real change hid in it. Log order is what
 * {@link Equivalences#canonicalByLocal} already keeps, under ADR 43's contract that two runs over
 * one unchanged input agree byte for byte - the contract {@code KnownList.promoted} serves by
 * sorting instead, which is the honest comparison. A fold has to pick one, and log order is a fact
 * of the data rather than a choice: sorting by qid would have been stable too, and would have
 * reordered the entire picture every time an id changed shape, as issue #171 changed a hundred of
 * them. {@code ExportOrderIsLogOrderTest#shouldDrawNodesAndEdgesInTheOrderTheLogClaimsThem} pins
 * the order to the log rather than to any fixed sequence, by reversing the fixture's claims and
 * expecting the picture to reverse with them.
 *
 * <p><b>A stand-in node has no claim of its own, so it comes first.</b> {@link
 * Equivalences#standIns} is a pre-pass that completes before this fold begins (#178), and its nodes
 * are seeded in the order of the merges that named them, ahead of everything the log claims -
 * including a later real claim about the same canonical id, which replaces the value and leaves the
 * position where the seed put it. Emitting them at their merge's own row instead would read more
 * literally as log order, and is rejected for #178's own reason: the {@code SameAs} arm below does
 * nothing at its own row any more, and handing it work again is how the two folds drift.
 *
 * <p>It is not a {@code GraphStore} and must not become one. It answers "what is in the log",
 * nothing else; anything that needs a traversal uses the real engine, so that an exported route is
 * the route {@code find_paths} would give.
 *
 * @param danglingEdges edges dropped because an endpoint was never claimed as a node. This should
 *     always be zero — {@code TinkerGraphStore.record} requires both vertices, so a log holding one
 *     would fail replay at boot — and it is counted rather than ignored because the alternative is
 *     an output that silently loses edges, or a GraphML file with a dangling reference that no tool
 *     will open.
 * @param withdrawnEdges distinct edges dropped because they named a canonical id a retraction
 *     emptied (#224) — {@code Equivalences.retractedStandIns}. <b>Counted by edge key, exactly as
 *     {@code danglingEdges} is and for the same reason:</b> that count runs over the grouped
 *     claims, so two sources corroborating one withdrawn relationship are one withdrawal, because
 *     one relationship is what the pair of them claimed. <b>A sibling of {@code danglingEdges}, and
 *     deliberately not folded into it:</b> that count is the alarm for a log that cannot boot and
 *     has to stay zero, while this one is an ordinary, expected consequence of the owner retracting
 *     something he had merged. A withdrawn edge never reaches the missing-endpoint check at all —
 *     {@link Equivalences#foldEndpoints} yields nothing for it — so without this count the export
 *     would simply come out smaller with nothing in the projection saying why. Issue #227's census
 *     reads it rather than re-deriving the rule.
 */
public record LogProjection(
    Map<String, NodeRecord> nodes, List<EdgeRecord> edges, int danglingEdges, int withdrawnEdges) {

  public LogProjection {
    // Not Map.copyOf: its iteration order is unspecified and salted per JVM, so two exports of one
    // unchanged log came out in two orders and a DOT or GraphML diff between them was noise (issue
    // #207). This is the same copy Equivalences.canonicalByLocal makes for the same reason, and
    // ADR 43's byte-identical contract is what both of them serve.
    nodes = Collections.unmodifiableMap(new LinkedHashMap<>(nodes));
    edges = List.copyOf(edges);
  }

  /** Read the log once and fold it. */
  public static LogProjection of(AssertionLog log) {
    List<LoggedAssertion> logged = log.readAll();
    return of(logged, Fold.of(logged, KindMapper::rederive));
  }

  /**
   * This fold, over rows and a {@link Fold} the caller has already built (#246).
   *
   * <p>Every log-taking rule this method used to call — {@code Retractions.in}, {@code
   * Equivalences.standIns} and {@code Equivalences.folding} — is one of the four answers a {@link
   * Fold} carries, so a caller that already holds one was paying for the same three walks twice.
   * {@code census} is that caller: it reads the log for its own row counts and then asked for this
   * projection, which read the log again and folded it again.
   *
   * <p><b>Trusts the caller</b>, as the {@code Equivalences} overloads {@code Fold.of} uses do:
   * {@code fold} must be {@code Fold.of(logged, KindMapper::rederive)} for these exact rows, or
   * this answers a different question.
   *
   * <p>{@code LogProjectionTest.shouldGiveTheSameProjectionWhenHandedTheFoldOfWouldCompute} pins
   * the two forms to one answer, and {@code ArchitectureTest.theExportFoldsOnce} is what keeps this
   * class the export's only fold.
   */
  public static LogProjection of(List<LoggedAssertion> logged, Fold fold) {
    Objects.requireNonNull(logged, "logged");
    Objects.requireNonNull(fold, "fold");
    Retractions retractions = fold.retractions();
    // Every merged entity's canonical id has its node before the fold begins (#178), from the same
    // stand-ins the boot replay seeds itself with — arriving here on the Fold rather than being
    // computed afresh. A real node claim about the canonical id, wherever it sits in the log,
    // lands on top of the stand-in below and wins - which is the guarantee that used to come
    // from asking whether the id had been claimed yet at the merge's own row.
    Map<String, NodeRecord> nodes = new LinkedHashMap<>(fold.standIns());
    // The graph half of a merge, over the whole log and from the same type the boot replay uses
    // (#178) — fold.equivalences() rather than a fresh Equivalences.folding(logged) call. Every
    // edge below has both of its endpoints read through this, so an edge claimed against a merged
    // local id is folded onto the canonical id once - which is why there is no copy at the
    // merge's own row any more, and no accumulator here deciding whether to make one. It is the
    // folding() form rather than the merges-only in(): the fold is also where an edge naming a
    // stand-in a retraction took away stops projecting (#224).
    Equivalences equivalences = fold.equivalences();
    Map<String, List<AssertionRecord>> byEdge = new LinkedHashMap<>();
    // Keys, not rows, so that this really is danglingEdges' sibling: that count runs over byEdge
    // AFTER corroborating claims are grouped, so two sources asserting one withdrawn relationship
    // have to read 1 here as they would read 1 there (#224, fix round 2).
    Set<String> withdrawn = new LinkedHashSet<>();

    for (int i = 0; i < logged.size(); i++) {
      LoggedAssertion assertion = logged.get(i);
      if (!retractions.survives(i, assertion)) {
        continue;
      }
      switch (assertion) {
        case NodeAssertion claim -> nodes.put(claim.qid(), KindMapper.rederive(claim).toNode());
        case AssertionRecord claim -> fold(equivalences, byEdge, withdrawn, claim);
        // Retractions never survive the rule above; they describe the fold rather than appear
        // in it. Reaching this arm would mean Retractions.survives had changed its mind.
        case Retraction retraction ->
            throw new IllegalStateException("a retraction is not projected: " + retraction.qid());
        // The owner's claims (#92) enter this fold through the same conversions the graph uses,
        // for this class's own stated reason: an exported picture that disagreed with the running
        // graph about what is in it would be worse than no picture. No KindMapper.rederive for a
        // minted entity - re-derivation reads the P31 classes a source stated, and the owner
        // stated a kind directly and no classes at all, so there is nothing to re-derive from.
        case LocalEntity minted -> nodes.put(minted.qid(), minted.toNode());
        case OwnerEdge owned -> fold(equivalences, byEdge, withdrawn, owned.toAssertion());
        // A merge is not drawn - it is a statement about identity, not a node or an edge, and an
        // edge for it would put a relationship in the export that find_paths cannot route along,
        // which this class's last paragraph forbids. Nothing happens at its own row any more
        // either (#178): its node half was seeded from Equivalences.standIns before this loop
        // began, because a folded edge can arrive before the merge that names its endpoint, and
        // its edge half is the fold above, which resolves every endpoint over the whole log.
        // Skipping a merge outright is still what this class forbids itself - it left the export
        // showing an entity hanging off a retired local id with no canonical node at all, while
        // get_entity showed the opposite - and skipping it is not what this arm does.
        case SameAs ignored -> {}
      }
    }

    List<EdgeRecord> edges = new ArrayList<>();
    int dangling = 0;
    for (List<AssertionRecord> claims : byEdge.values()) {
      AssertionRecord first = claims.get(0);
      if (!nodes.containsKey(first.fromQid()) || !nodes.containsKey(first.toQid())) {
        dangling++;
        continue;
      }
      List<Provenance> sources = claims.stream().map(AssertionRecord::provenance).toList();
      edges.add(
          new EdgeRecord(
              first.fromQid(),
              first.toQid(),
              first.typeCode(),
              first.validFrom(),
              first.validTo(),
              sources));
    }
    return new LogProjection(nodes, edges, dangling, withdrawn.size());
  }

  /**
   * Fold one edge claim into {@code byEdge}, or record the key it was withdrawn under (#224).
   *
   * <p>Both edge arms above go through this, so the sourced and the owner's edges are folded and
   * counted by one rule rather than two. The reason for the withdrawal is {@code
   * Equivalences.namesARetractedStandIn}'s to give — the fold yields nothing for a self-loop as
   * well, and only one of the two is this count's business. That predicate is therefore asked a
   * second time on the empty branch, having already been asked inside {@code foldEndpoints}: the
   * double read is the price of keeping "what withdrawal means" in one place, and it is cheaper
   * than the drift a second copy of the set lookup here would invite. Do not inline it back.
   *
   * <p><b>{@code withdrawn} collects edge keys rather than counting rows</b>, so two sources
   * corroborating one withdrawn relationship are one withdrawal — the same grouping {@code
   * danglingEdges} gets for free by running over {@code byEdge}.
   */
  private static void fold(
      Equivalences equivalences,
      Map<String, List<AssertionRecord>> byEdge,
      Set<String> withdrawn,
      AssertionRecord claim) {
    Optional<AssertionRecord> folded = equivalences.foldEndpoints(claim);
    if (folded.isPresent()) {
      collect(byEdge, folded.get());
      return;
    }
    if (equivalences.namesARetractedStandIn(claim)) {
      withdrawn.add(claim.edgeKey());
    }
  }

  /** One folded edge claim, filed under the pair it now names. */
  private static void collect(Map<String, List<AssertionRecord>> byEdge, AssertionRecord claim) {
    byEdge.computeIfAbsent(claim.edgeKey(), key -> new ArrayList<>()).add(claim);
  }
}
