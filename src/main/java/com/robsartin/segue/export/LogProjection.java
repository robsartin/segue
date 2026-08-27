package com.robsartin.segue.export;

import com.robsartin.segue.domain.AssertionRecord;
import com.robsartin.segue.domain.EdgeRecord;
import com.robsartin.segue.domain.LoggedAssertion;
import com.robsartin.segue.domain.NodeAssertion;
import com.robsartin.segue.domain.NodeRecord;
import com.robsartin.segue.domain.Provenance;
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

    for (LoggedAssertion assertion : log.readAll()) {
      switch (assertion) {
        case NodeAssertion claim -> nodes.put(claim.qid(), KindMapper.rederive(claim).toNode());
        case AssertionRecord claim ->
            byEdge.computeIfAbsent(claim.edgeKey(), key -> new ArrayList<>()).add(claim);
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
