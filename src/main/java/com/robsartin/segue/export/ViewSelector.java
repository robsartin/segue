package com.robsartin.segue.export;

import com.robsartin.segue.domain.EdgeRecord;
import com.robsartin.segue.domain.Hop;
import com.robsartin.segue.domain.NodeRecord;
import com.robsartin.segue.domain.PathRanking;
import com.robsartin.segue.domain.PathResult;
import com.robsartin.segue.domain.Provenance;
import com.robsartin.segue.port.AssertionLog;
import com.robsartin.segue.port.GraphStore;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.ToIntFunction;
import java.util.stream.Collectors;

/**
 * Chooses what belongs in a picture. It has no idea what a picture is written as.
 *
 * <p><b>This separation is the requirement issue #50 cares about most.</b> The stated destination
 * is an interactive app, so view selection is the durable logic and serialisation is a swappable
 * tail: a future UI adds a JSON {@link ViewWriter} against exactly these four methods and changes
 * nothing here. Nothing in this class mentions DOT, GraphML, XML, a file or a {@code Writer}, and
 * if that ever stops being true the work is thrown away.
 *
 * <p><b>Four views, because the whole graph is not a picture.</b> A real personal graph here is
 * tens of thousands of nodes and edges; Graphviz degrades in the low thousands and a hairball
 * answers nobody's question. So the tool emits bounded views:
 *
 * <ul>
 *   <li>{@link #route} — one {@code find_paths} result, hop by hop. 2-5 nodes.
 *   <li>{@link #neighbourhood} — one entity and its edges, optionally to depth 2. Tens.
 *   <li>{@link #subgraph} — only the entities on a supplied list, and the edges <em>between</em>
 *       them. Fed the seeding tool's mapping file (ADR 40) it shows the acts actually on the list
 *       and how they connect, with every discovered intermediate stripped out.
 *   <li>{@link #full} — everything, behind an explicit flag and a size report.
 * </ul>
 *
 * <p><b>Two readers, deliberately.</b> The bounded views go through {@link GraphStore}, so an
 * exported route is the route {@code find_paths} would return — same traversal, same {@link
 * PathRanking}, no second implementation to drift. {@link #full} and {@link #subgraph} go through
 * {@link LogProjection} instead, because {@code GraphStore} has no enumerate-all method and adding
 * one would widen the port that exists to make the engine choice reversible. ADR 19 makes that not
 * merely acceptable but correct: the log is the source of truth and the graph is its projection.
 *
 * <p><b>It reads and it does not write.</b> No method here calls {@code GraphStore.record}, {@code
 * GraphStore.upsertNode} or {@code AssertionLog.append}, and no class in this package touches
 * {@code IngestService}. {@code ArchitectureTest.theExporterOnlyReads} is what makes that a fact
 * rather than an intention.
 */
public final class ViewSelector {

  private final GraphStore graph;
  private final AssertionLog log;

  /** Folded on first use: two of the four views never need it, and it reads the whole log. */
  private LogProjection projection;

  public ViewSelector(GraphStore graph, AssertionLog log) {
    this.graph = Objects.requireNonNull(graph, "graph");
    this.log = Objects.requireNonNull(log, "log");
  }

  // ---- route ------------------------------------------------------------

  /**
   * The best route between two entities, as {@code find_paths} would rank it.
   *
   * <p>One route, not every route: a picture of forty overlapping paths explains less than the
   * single best-evidenced one, and which one is best is a question ADR 31 has already answered.
   * That ordering is applied here through the shared {@link PathRanking}, degree lookup included,
   * so the exported route cannot disagree with the one the MCP tool returns.
   */
  public GraphView route(String fromQid, String toQid, int maxHops) {
    NodeRecord from = require(fromQid);
    NodeRecord to = require(toQid);
    List<PathResult> ranked = PathRanking.rank(graph.paths(fromQid, toQid, maxHops), degrees());
    String what = "route " + describe(from) + " to " + describe(to);
    if (ranked.isEmpty()) {
      return new GraphView(what + ": no route within " + maxHops + " hop(s)", List.of(), List.of());
    }
    PathResult best = ranked.get(0);

    Map<String, NodeRecord> nodes = new LinkedHashMap<>();
    List<EdgeRecord> edges = new ArrayList<>();
    for (Hop hop : best.hops()) {
      nodes.putIfAbsent(hop.from().qid(), hop.from());
      nodes.putIfAbsent(hop.to().qid(), hop.to());
      edges.add(hop.edge());
    }
    return view(
        what + ", " + best.length() + " hop(s) at confidence " + best.weakestConfidence(),
        nodes.values(),
        edges);
  }

  // ---- neighbourhood ----------------------------------------------------

  /**
   * One entity, its edges, and — at depth 2 — its neighbours' edges too.
   *
   * <p>Depth 1 is the honest picture of what the graph knows about one thing. Depth 2 is where a
   * well-expanded seed starts to be worth looking at and where the size grows fastest, which is why
   * it is a number rather than a boolean and why the caller is told the counts before anything is
   * written.
   */
  public GraphView neighbourhood(String qid, int depth) {
    NodeRecord centre = require(qid);
    if (depth < 1) {
      throw new IllegalArgumentException("depth must be at least 1, got " + depth);
    }

    Map<String, NodeRecord> nodes = new LinkedHashMap<>();
    Map<String, EdgeRecord> edges = new LinkedHashMap<>();
    nodes.put(centre.qid(), centre);

    // Breadth-first, one level per unit of depth. A node enters `nodes` exactly once, which is
    // also what keeps it out of a later frontier: an entity two hops away by one route and three
    // by another is expanded at the shorter distance and never again.
    List<String> frontier = List.of(qid);
    for (int level = 0; level < depth && !frontier.isEmpty(); level++) {
      List<String> next = new ArrayList<>();
      for (String current : frontier) {
        for (EdgeRecord edge : graph.edges(current)) {
          String other = edge.fromQid().equals(current) ? edge.toQid() : edge.fromQid();
          Optional<NodeRecord> node = graph.node(other);
          if (node.isEmpty()) {
            continue;
          }
          // Keyed by the relationship, so an edge reached from both of its ends appears once.
          edges.putIfAbsent(edge.key(), edge);
          if (nodes.putIfAbsent(other, node.get()) == null) {
            next.add(other);
          }
        }
      }
      frontier = next;
    }

    return view(
        "neighbourhood of " + describe(centre) + " to depth " + depth,
        nodes.values(),
        edges.values());
  }

  // ---- subgraph ---------------------------------------------------------

  /**
   * Only the entities on the list, and only the edges whose <em>both</em> ends are on it.
   *
   * <p>This is the interesting one. Fed the seeding tool's mapping file it answers "of the things I
   * actually put on the list, which ones connect, and how" — the discovered intermediates that make
   * the full graph a hairball are exactly what it strips.
   *
   * <p>Entities the graph has never heard of are reported in the description rather than silently
   * dropped: a list of nine hundred QIDs of which two hundred are missing is a fact about the graph
   * worth knowing before drawing conclusions from the picture.
   */
  public GraphView subgraph(Collection<String> qids) {
    Objects.requireNonNull(qids, "qids");
    Set<String> wanted = new LinkedHashSet<>(qids);
    LogProjection folded = projection();

    Map<String, NodeRecord> nodes = new LinkedHashMap<>();
    for (String qid : wanted) {
      NodeRecord node = folded.nodes().get(qid);
      if (node != null) {
        nodes.put(qid, node);
      }
    }
    List<EdgeRecord> edges =
        folded.edges().stream()
            .filter(e -> nodes.containsKey(e.fromQid()) && nodes.containsKey(e.toQid()))
            .toList();

    return view(
        "subgraph of " + wanted.size() + " requested entities, " + nodes.size() + " found",
        nodes.values(),
        edges);
  }

  // ---- full -------------------------------------------------------------

  /** Everything in the log. Tens of thousands of nodes on a real graph — see the CLI's guard. */
  public GraphView full() {
    LogProjection folded = projection();
    return view("the whole graph", folded.nodes().values(), folded.edges());
  }

  /** Edges the log holds that no node claim supports. Always zero on a graph that replays. */
  public int danglingEdges() {
    return projection().danglingEdges();
  }

  // ---- shared -----------------------------------------------------------

  private LogProjection projection() {
    if (projection == null) {
      projection = LogProjection.of(log);
    }
    return projection;
  }

  private NodeRecord require(String qid) {
    Objects.requireNonNull(qid, "qid");
    return graph
        .node(qid)
        .orElseThrow(
            () ->
                new IllegalArgumentException(
                    "unknown entity: " + qid + " — it is not in this graph"));
  }

  private static String describe(NodeRecord node) {
    return node.label() + " (" + node.qid() + ")";
  }

  /**
   * The graph's shape, for ADR 31's specificity dimension. The same memoised-per-call lookup {@code
   * SegueService} builds, for the same reason: {@code PathRanking} lives in {@code domain}, which
   * has no graph access at all, so the degree arrives as a plain function over a qid.
   */
  private ToIntFunction<String> degrees() {
    Map<String, Integer> cache = new HashMap<>();
    return qid -> cache.computeIfAbsent(qid, q -> graph.edges(q).size());
  }

  private static GraphView view(
      String description, Collection<NodeRecord> nodes, Collection<EdgeRecord> edges) {
    return new GraphView(
        description,
        nodes.stream().map(ViewSelector::toViewNode).toList(),
        edges.stream().map(ViewSelector::toViewEdge).toList());
  }

  /**
   * One projected node, flattened. {@code instanceOf} travels as the claim recorded it (ADR 42) —
   * raw QIDs, in order, unresolved — because what a picture does with a class is the writer's
   * question, not this class's.
   */
  private static ViewNode toViewNode(NodeRecord node) {
    return new ViewNode(node.qid(), node.kind(), node.label(), node.instanceOf());
  }

  /**
   * One projected edge, flattened.
   *
   * <p>{@code confidence} is the edge's best supporting provenance, matching what {@link
   * PathRanking} reasons about. {@code sourceId} is every distinct source, joined — one source is
   * the common case and two is corroboration (ADR 19), which is worth being able to select on.
   */
  private static ViewEdge toViewEdge(EdgeRecord edge) {
    String sources =
        edge.sources().stream()
            .map(Provenance::sourceId)
            .distinct()
            .collect(Collectors.joining("|"));
    return new ViewEdge(
        edge.fromQid(), edge.toQid(), edge.typeCode(), edge.bestConfidence(), sources);
  }
}
