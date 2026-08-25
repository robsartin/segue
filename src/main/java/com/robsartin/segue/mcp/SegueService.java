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
import com.robsartin.segue.wikidata.WikidataUnavailableException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
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
 *       null argument. Every other shortfall — an unknown qid, a malformed one, an unreachable
 *       source, a result cut short by its own bound — comes back as a {@link ToolResult} the
 *       calling model can read and act on (ADR 27), with {@link CorrelationId#current()} folded
 *       into the detail of every non-ok result so a user-visible error can be pasted into a log
 *       search (ADR 29). {@link WikidataUnavailableException} in particular is caught at every call
 *       site that can throw it — {@code resolver.search}, {@code resolver.fetch}, and the unwrapped
 *       neighbour fetch inside {@link #expandEntity} — rather than left to escape.
 * </ul>
 *
 * <p>Every method returns view types from {@code mcp/} (translated by {@link ViewMapper}), never
 * the domain records themselves — see {@link ViewMapper}'s Javadoc for why.
 */
public final class SegueService {

  private static final Logger log = LoggerFactory.getLogger(SegueService.class);

  /** ADR 26/ADR 22: identity is a Wikidata QID, always of this shape. */
  private static final Pattern QID = Pattern.compile("Q\\d+");

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
  public ToolResult<List<CandidateView>> search(String query, NodeKind kind, int limit) {
    Objects.requireNonNull(query, "query");
    List<Candidate> candidates;
    try {
      candidates = resolver.search(query, kind, limit);
    } catch (WikidataUnavailableException e) {
      log.warn("search(\"{}\") source unavailable: {}", query, e.getMessage());
      return error("wikidata unavailable: " + e.getMessage());
    }
    return ToolResult.ok(
        candidates.size() + " candidate(s) for \"" + query + "\"",
        ViewMapper.toCandidateViews(candidates));
  }

  /**
   * Fetch one entity's identity from the resolver and record it. Recording is an upsert, so a
   * second call with the same qid is idempotent — it refreshes the node rather than duplicating it.
   */
  public ToolResult<NodeView> addEntity(String qid) {
    Objects.requireNonNull(qid, "qid");
    if (!QID.matcher(qid).matches()) {
      return error("not a QID: " + qid);
    }
    Optional<NodeAssertion> fetched;
    try {
      fetched = resolver.fetch(qid);
    } catch (WikidataUnavailableException e) {
      log.warn("addEntity({}) source unavailable: {}", qid, e.getMessage());
      return error("wikidata unavailable: " + e.getMessage());
    }
    if (fetched.isEmpty()) {
      return error("no such entity: " + qid);
    }
    NodeAssertion assertion = fetched.get();
    ingest.record(assertion);
    return ToolResult.ok(
        "added " + qid + " (" + assertion.label() + ")", ViewMapper.toNodeView(assertion.toNode()));
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
   * {@code maxNewEdges} bounding the assertions considered at all. A neighbour fetch that throws
   * {@link WikidataUnavailableException} is treated exactly like one that returned empty: counted
   * as skipped and the call continues, rather than aborting a 30-round-trip expansion after some
   * assertions are already committed.
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
          Optional<NodeAssertion> resolved;
          try {
            resolved = resolver.fetch(neighbor);
          } catch (WikidataUnavailableException e) {
            log.warn(
                "expandEntity({}) neighbour {} unavailable: {}", qid, neighbor, e.getMessage());
            resolved = Optional.empty();
          }
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

    ExpansionSummary summary =
        new ExpansionSummary(qid, nodesAdded, edgesAdded, skipped, truncated, sourceUnavailable);
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
    TreeMap<String, List<NodeRecord>> byType = new TreeMap<>();
    for (EdgeRecord edge : edges) {
      String neighborQid = edge.fromQid().equals(qid) ? edge.toQid() : edge.fromQid();
      graph
          .node(neighborQid)
          .ifPresent(
              neighbor ->
                  byType.computeIfAbsent(edge.typeCode(), t -> new ArrayList<>()).add(neighbor));
    }
    List<NeighborGroup> groups =
        byType.entrySet().stream()
            .map(
                e ->
                    new NeighborGroup(
                        e.getKey(), e.getValue().stream().map(ViewMapper::toNodeView).toList()))
            .toList();
    EntityView view = new EntityView(ViewMapper.toNodeView(node.get()), groups);
    return ToolResult.ok(
        node.get().label() + ": " + edges.size() + " edge(s), " + groups.size() + " type(s)", view);
  }

  /**
   * Every route between two entities up to {@code maxHops}, ranked most-trustworthy-first (ADR 31).
   * Never the raw order {@link GraphStore#paths} returns — shortest is not most trustworthy.
   *
   * <p>Both endpoints must already be in the graph. Without this check an entity nobody ever {@code
   * add_entity}'d reads identically to two entities the graph knows are unrelated — both return
   * {@code ok} with zero routes — and a model that forgot to add one would report "these things are
   * unrelated" rather than the actual problem.
   */
  public ToolResult<List<PathView>> findPaths(String fromQid, String toQid, int maxHops) {
    Objects.requireNonNull(fromQid, "fromQid");
    Objects.requireNonNull(toQid, "toQid");
    if (maxHops <= 0) {
      return error("maxHops must be positive, got " + maxHops);
    }
    if (graph.node(fromQid).isEmpty()) {
      return error("unknown entity: " + fromQid + " — add it before searching for routes");
    }
    if (graph.node(toQid).isEmpty()) {
      return error("unknown entity: " + toQid + " — add it before searching for routes");
    }
    List<PathResult> raw = graph.paths(fromQid, toQid, maxHops);
    List<PathResult> ranked = PathRanking.rank(raw);
    return ToolResult.ok(
        ranked.size() + " route(s) from " + fromQid + " to " + toQid,
        ViewMapper.toPathViews(ranked));
  }

  private static <T> ToolResult<T> error(String reason) {
    return ToolResult.error(withCorrelation(reason));
  }

  private static String withCorrelation(String reason) {
    String correlation = CorrelationId.current();
    return correlation.isEmpty() ? reason : reason + " (correlation " + correlation + ")";
  }

  /**
   * What one expansion produced, and how much of it it could not resolve.
   *
   * @param truncated the adapter or the {@code maxNewEdges} bound cut the result short
   * @param sourceUnavailable at least one source could not be reached at all
   */
  public record ExpansionSummary(
      String qid,
      int nodesAdded,
      int edgesAdded,
      int skippedNeighbors,
      boolean truncated,
      boolean sourceUnavailable) {}
}
