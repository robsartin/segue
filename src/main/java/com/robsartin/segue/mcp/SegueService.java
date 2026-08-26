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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
   * GraphStore#record} throws on an unknown endpoint. Each unknown neighbour therefore has to be
   * identified before the edge that names it can be recorded, and there are two ways to get that:
   *
   * <ul>
   *   <li><b>The adapter already knew.</b> {@link ExpandResult#neighbors()} carries identity the
   *       source learned while discovering the edge, and it is used in preference to anything else.
   *       Wikidata's reverse lookup returns label and kind alongside each backlink in one query
   *       (ADR 36), and after that change an expansion routinely finds seventy-odd neighbours —
   *       enough that fetching them individually would cost more than the entire expansion did
   *       before.
   *   <li><b>Otherwise, fetch it.</b> {@link EntityResolver#fetch}, sequentially, one HTTP round
   *       trip per remaining neighbour. Deliberately the slow, correct choice over synthesising a
   *       placeholder node: a graph full of {@code Q12345}-labelled stubs is worse than an
   *       expansion that takes a few seconds. (Follow-up: a bounded virtual-thread fan-out for the
   *       neighbour fetches, not built in this increment.)
   * </ul>
   *
   * <p>Neighbour fetches that fail once are not retried for the same neighbour within this call —
   * that is the bound on how many calls a dense, partly-unreachable entity can trigger, alongside
   * {@code maxNewEdges} bounding the assertions considered at all. A neighbour fetch that throws
   * {@link WikidataUnavailableException} is treated exactly like one that returned empty: the
   * neighbour is skipped and the call continues, rather than aborting a 30-round-trip expansion
   * after some assertions are already committed.
   *
   * <p>What is reported as skipped is the count of <em>distinct</em> neighbours, not of the
   * assertions dropped along with them. Both are defensible numbers; only one matches the name
   * {@link ExpansionSummary#skippedNeighbors()} and the sentence it is rendered into, and this
   * graph is a multigraph by design — Nick Cave both wrote and scored The Proposition, so two
   * assertions can name one pair of nodes. Counting per assertion told a calling model that two
   * entities were lost when one was.
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
    // Identity an adapter already knew, keyed by qid. First writer wins, matching the way the
    // graph resolves a conflict everywhere else: two sources describing one entity differently
    // is a real possibility, and silently preferring the later one would hide it.
    Map<String, NodeAssertion> described = new HashMap<>();
    for (SourceAdapter adapter : adapters.all()) {
      if (!adapter.supports(node.kind())) {
        continue;
      }
      ExpandResult result = adapter.expand(node, ctx);
      sourceUnavailable |= result.sourceUnavailable();
      adapterTruncated |= result.truncated();
      collected.addAll(result.assertions());
      for (NodeAssertion neighbor : result.neighbors()) {
        described.putIfAbsent(neighbor.qid(), neighbor);
      }
    }

    boolean truncated = adapterTruncated || collected.size() > maxNewEdges;
    List<AssertionRecord> bounded =
        collected.size() > maxNewEdges ? collected.stream().limit(maxNewEdges).toList() : collected;

    int nodesAdded = 0;
    int edgesAdded = 0;
    // Does double duty, deliberately: it is the memo that stops one neighbour being fetched twice
    // in a single call, and it is also the number reported as skippedNeighbors. Keeping a separate
    // counter is what made the two disagree — the counter incremented per dropped assertion while
    // the field, and the sentence built from it, both said "neighbour".
    Set<String> unresolvableNeighbors = new HashSet<>();
    for (AssertionRecord assertion : bounded) {
      String neighbor = neighborOf(assertion, qid);
      if (neighbor != null) {
        if (unresolvableNeighbors.contains(neighbor)) {
          continue;
        }
        // Note what this re-read buys beyond skipping a round trip: it is also why nodesAdded
        // does not have the counting bug skippedNeighbors had. A neighbour recorded on the first
        // assertion that names it is in the graph by the time the second one is examined, so the
        // increment below cannot fire twice for one entity. edgesAdded needs no such guard —
        // assertions ARE what it counts, and two of them between one pair are two claims.
        if (graph.node(neighbor).isEmpty()) {
          // An adapter that already knows this entity spares a round trip. That is not a
          // micro-optimisation since ADR 36: expanding a person now discovers seventy-odd
          // works in one query, and fetching each of them one at a time afterwards would cost
          // more than the whole expansion used to.
          Optional<NodeAssertion> resolved = Optional.ofNullable(described.get(neighbor));
          if (resolved.isEmpty()) {
            try {
              resolved = resolver.fetch(neighbor);
            } catch (WikidataUnavailableException e) {
              log.warn(
                  "expandEntity({}) neighbour {} unavailable: {}", qid, neighbor, e.getMessage());
              resolved = Optional.empty();
            }
          }
          if (resolved.isEmpty()) {
            unresolvableNeighbors.add(neighbor);
            continue;
          }
          ingest.record(resolved.get());
          nodesAdded++;
        }
      }
      ingest.record(assertion);
      edgesAdded++;
    }

    int skippedNeighbors = unresolvableNeighbors.size();
    ExpansionSummary summary =
        new ExpansionSummary(
            qid, nodesAdded, edgesAdded, skippedNeighbors, truncated, sourceUnavailable);
    List<String> reasons = new ArrayList<>();
    if (sourceUnavailable) {
      reasons.add("a source was unavailable and could not be reached");
    }
    if (truncated) {
      reasons.add("the result was truncated at the bound of " + maxNewEdges);
    }
    if (skippedNeighbors > 0) {
      reasons.add(skippedNeighbors + " neighbour(s) could not be resolved and were skipped");
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
   * @param nodesAdded entities newly recorded by this call, counted once each
   * @param edgesAdded assertions recorded by this call — per assertion, not per pair of nodes, so
   *     two sources claiming the same relationship count twice and are merged downstream by {@code
   *     GraphStore.record}
   * @param skippedNeighbors distinct entities this call could not identify, counted once each
   *     however many assertions named them
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
