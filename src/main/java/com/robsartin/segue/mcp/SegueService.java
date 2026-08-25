package com.robsartin.segue.mcp;

import com.robsartin.segue.domain.AssertionRecord;
import com.robsartin.segue.domain.Candidate;
import com.robsartin.segue.domain.EdgeRecord;
import com.robsartin.segue.domain.NodeAssertion;
import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.domain.NodeRecord;
import com.robsartin.segue.domain.PathRanking;
import com.robsartin.segue.domain.PathResult;
import com.robsartin.segue.ingest.IngestService;
import com.robsartin.segue.port.EntityResolver;
import com.robsartin.segue.port.ExpandContext;
import com.robsartin.segue.port.ExpandResult;
import com.robsartin.segue.port.GraphStore;
import com.robsartin.segue.port.SourceAdapter;
import com.robsartin.segue.port.SourceAdapters;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The facade every MCP tool calls. It owns the ports; the tools own nothing.
 *
 * <p>Two invariants matter more than the rest of the class:
 *
 * <ul>
 *   <li>only {@link IngestService} ever applies a claim to the graph. This class calls it for every
 *       write and never touches {@link GraphStore#record}, {@link GraphStore#upsertNode} or {@code
 *       AssertionLog.append} directly — ArchUnit rule {@code onlyIngestAppliesClaimsToTheGraph}
 *       would fail it otherwise, and it is the right rule (ADR 19).
 *   <li>nothing thrown by a port escapes a public method here except a programmer error, such as a
 *       null argument. Every other shortfall — an unknown qid, an unreachable source, a result cut
 *       short by its own bound — comes back as a {@link ToolResult} the calling model can read and
 *       act on (ADR 27), with {@link CorrelationId#current()} folded into the detail of every
 *       non-ok result so a user-visible error can be pasted into a log search (ADR 29).
 * </ul>
 */
public final class SegueService {

  private static final Logger log = LoggerFactory.getLogger(SegueService.class);

  private final EntityResolver resolver;
  private final GraphStore graph;
  private final IngestService ingest;
  private final SourceAdapters adapters;

  public SegueService(
      EntityResolver resolver, GraphStore graph, IngestService ingest, SourceAdapters adapters) {
    this.resolver = Objects.requireNonNull(resolver, "resolver");
    this.graph = Objects.requireNonNull(graph, "graph");
    this.ingest = Objects.requireNonNull(ingest, "ingest");
    this.adapters = Objects.requireNonNull(adapters, "adapters");
  }

  /** Candidates for a free-text query, best match first. Writes nothing. */
  public ToolResult<List<Candidate>> search(String query, NodeKind kind, int limit) {
    Objects.requireNonNull(query, "query");
    List<Candidate> candidates = resolver.search(query, kind, limit);
    return ToolResult.ok(candidates.size() + " candidate(s) for \"" + query + "\"", candidates);
  }

  /**
   * Fetch one entity's identity from the resolver and record it. Recording is an upsert, so a
   * second call with the same qid is idempotent — it refreshes the node rather than duplicating it.
   */
  public ToolResult<NodeRecord> addEntity(String qid) {
    Objects.requireNonNull(qid, "qid");
    Optional<NodeAssertion> fetched = resolver.fetch(qid);
    if (fetched.isEmpty()) {
      return error("no such entity: " + qid);
    }
    NodeAssertion assertion = fetched.get();
    ingest.record(assertion);
    return ToolResult.ok("added " + qid + " (" + assertion.label() + ")", assertion.toNode());
  }

  /**
   * Expand a known entity through every source that supports its kind.
   *
   * <p>An expansion can reference neighbour entities the graph has never seen, and {@link
   * GraphStore#record} throws on an unknown endpoint. So each unknown neighbour is resolved via
   * {@link EntityResolver#fetch} — sequentially, one HTTP round trip per new neighbour — and
   * recorded before the edge that references it. This is deliberately the slow, correct choice over
   * synthesising a placeholder node: a graph full of {@code Q12345}-labelled stubs is worse than an
   * expansion that takes a few seconds. (Follow-up: a bounded virtual-thread fan-out for the
   * neighbour fetches, not built in this increment.)
   *
   * <p>Neighbour fetches that fail once are not retried for the same neighbour within this call —
   * that is the bound on how many calls a dense, partly-unreachable entity can trigger, alongside
   * {@code maxNewEdges} bounding the assertions considered at all.
   */
  public ToolResult<ExpansionSummary> expandEntity(String qid, int maxNewEdges) {
    Objects.requireNonNull(qid, "qid");
    Optional<NodeRecord> seed = graph.node(qid);
    if (seed.isEmpty()) {
      return error("unknown entity: " + qid + " — add it before expanding");
    }
    if (maxNewEdges <= 0) {
      return error("maxNewEdges must be positive, got " + maxNewEdges);
    }
    NodeRecord node = seed.get();
    ExpandContext ctx = new ExpandContext(maxNewEdges);

    boolean sourceUnavailable = false;
    boolean adapterTruncated = false;
    List<AssertionRecord> collected = new ArrayList<>();
    for (SourceAdapter adapter : adapters.all()) {
      if (!adapter.supports(node.kind())) {
        continue;
      }
      ExpandResult result = adapter.expand(node, ctx);
      sourceUnavailable |= result.sourceUnavailable();
      adapterTruncated |= result.truncated();
      collected.addAll(result.assertions());
    }

    boolean truncated = adapterTruncated || collected.size() > maxNewEdges;
    List<AssertionRecord> bounded =
        collected.size() > maxNewEdges ? collected.stream().limit(maxNewEdges).toList() : collected;

    int nodesAdded = 0;
    int edgesAdded = 0;
    int skipped = 0;
    Set<String> unresolvableNeighbors = new HashSet<>();
    for (AssertionRecord assertion : bounded) {
      String neighbor = neighborOf(assertion, qid);
      if (neighbor != null) {
        if (unresolvableNeighbors.contains(neighbor)) {
          skipped++;
          continue;
        }
        if (graph.node(neighbor).isEmpty()) {
          Optional<NodeAssertion> resolved = resolver.fetch(neighbor);
          if (resolved.isEmpty()) {
            unresolvableNeighbors.add(neighbor);
            skipped++;
            continue;
          }
          ingest.record(resolved.get());
          nodesAdded++;
        }
      }
      ingest.record(assertion);
      edgesAdded++;
    }

    ExpansionSummary summary = new ExpansionSummary(qid, nodesAdded, edgesAdded, skipped);
    List<String> reasons = new ArrayList<>();
    if (sourceUnavailable) {
      reasons.add("a source was unavailable and could not be reached");
    }
    if (truncated) {
      reasons.add("the result was truncated at the bound of " + maxNewEdges);
    }
    if (skipped > 0) {
      reasons.add(skipped + " neighbour(s) could not be resolved and were skipped");
    }
    if (reasons.isEmpty()) {
      return ToolResult.ok(
          "expanded " + qid + ": " + edgesAdded + " edge(s), " + nodesAdded + " new node(s)",
          summary);
    }
    log.warn("expandEntity({}) partial: {}", qid, reasons);
    return ToolResult.partial(withCorrelation(String.join("; ", reasons)), summary);
  }

  /** The other end of an assertion from the seed's point of view, or null if both ends are it. */
  private static String neighborOf(AssertionRecord assertion, String seedQid) {
    if (!assertion.fromQid().equals(seedQid)) {
      return assertion.fromQid();
    }
    if (!assertion.toQid().equals(seedQid)) {
      return assertion.toQid();
    }
    return null;
  }

  /** One entity plus its neighbours, grouped by the relationship type that connects them. */
  public ToolResult<EntityView> getEntity(String qid) {
    Objects.requireNonNull(qid, "qid");
    Optional<NodeRecord> node = graph.node(qid);
    if (node.isEmpty()) {
      return error("unknown entity: " + qid);
    }
    List<EdgeRecord> edges = graph.edges(qid);
    Map<String, List<NodeRecord>> byType = new TreeMap<>();
    for (EdgeRecord edge : edges) {
      String neighborQid = edge.fromQid().equals(qid) ? edge.toQid() : edge.fromQid();
      graph
          .node(neighborQid)
          .ifPresent(
              neighbor ->
                  byType.computeIfAbsent(edge.typeCode(), t -> new ArrayList<>()).add(neighbor));
    }
    Map<String, List<NodeRecord>> immutable = new TreeMap<>();
    byType.forEach((type, neighbors) -> immutable.put(type, List.copyOf(neighbors)));
    EntityView view = new EntityView(node.get(), Collections.unmodifiableMap(immutable));
    return ToolResult.ok(
        node.get().label() + ": " + edges.size() + " edge(s), " + immutable.size() + " type(s)",
        view);
  }

  /**
   * Every route between two entities up to {@code maxHops}, ranked most-trustworthy-first (ADR 31).
   * Never the raw order {@link GraphStore#paths} returns — shortest is not most trustworthy.
   */
  public ToolResult<List<PathResult>> findPaths(String fromQid, String toQid, int maxHops) {
    Objects.requireNonNull(fromQid, "fromQid");
    Objects.requireNonNull(toQid, "toQid");
    if (maxHops <= 0) {
      return ToolResult.error(withCorrelation("maxHops must be positive, got " + maxHops));
    }
    List<PathResult> raw = graph.paths(fromQid, toQid, maxHops);
    List<PathResult> ranked = PathRanking.rank(raw);
    return ToolResult.ok(ranked.size() + " route(s) from " + fromQid + " to " + toQid, ranked);
  }

  private static <T> ToolResult<T> error(String reason) {
    return new ToolResult<>("error", withCorrelation(reason), null);
  }

  private static String withCorrelation(String reason) {
    String correlation = CorrelationId.current();
    return correlation.isEmpty() ? reason : reason + " (correlation " + correlation + ")";
  }

  /** What one expansion produced, and how much of it it could not resolve. */
  public record ExpansionSummary(
      String qid, int nodesAdded, int edgesAdded, int skippedNeighbors) {}

  /** One entity plus its neighbours, grouped by the edge type that connects them. */
  public record EntityView(NodeRecord node, Map<String, List<NodeRecord>> neighborsByType) {}
}
