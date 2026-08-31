package com.robsartin.segue.export;

import com.robsartin.segue.domain.AssertionRecord;
import com.robsartin.segue.domain.EdgeRecord;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
 * picture, and DOT colours and shapes every node by its kind.
 *
 * <p><b>Retractions are honoured through the same shared rule</b>, {@link Retractions} (ADR 44,
 * issue #68), and for a stronger version of the same argument: a picture still showing edges the
 * graph has dropped is not a stale detail, it is a false record of what is in the graph - and an
 * export is the artefact somebody keeps, mails or opens in Gephi weeks later. {@code
 * GraphProjector} asks the identical question of the identical log.
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
 */
public record LogProjection(
    Map<String, NodeRecord> nodes, List<EdgeRecord> edges, int danglingEdges) {

  public LogProjection {
    nodes = Map.copyOf(nodes);
    edges = List.copyOf(edges);
  }

  /** Read the log once and fold it. */
  public static LogProjection of(AssertionLog log) {
    Map<String, NodeRecord> nodes = new LinkedHashMap<>();
    Map<String, List<AssertionRecord>> byEdge = new LinkedHashMap<>();

    List<LoggedAssertion> logged = log.readAll();
    Retractions retractions = Retractions.in(logged);
    for (int i = 0; i < logged.size(); i++) {
      LoggedAssertion assertion = logged.get(i);
      if (!retractions.survives(i, assertion)) {
        continue;
      }
      switch (assertion) {
        case NodeAssertion claim -> nodes.put(claim.qid(), KindMapper.rederive(claim).toNode());
        case AssertionRecord claim ->
            byEdge.computeIfAbsent(claim.edgeKey(), key -> new ArrayList<>()).add(claim);
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
        case OwnerEdge owned -> {
          AssertionRecord claim = owned.toAssertion();
          byEdge.computeIfAbsent(claim.edgeKey(), key -> new ArrayList<>()).add(claim);
        }
        // A merge has no picture of its own: it is a statement about identity, not a node or an
        // edge, and #92 Task 4 resolves it by moving the claims that DO have one onto the
        // canonical id. Drawing it as an edge would put a relationship in the export that
        // find_paths cannot route along, which this class's last paragraph forbids.
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
    return new LogProjection(nodes, edges, dangling);
  }
}
