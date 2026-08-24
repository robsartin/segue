package com.robsartin.segue.tinker;

import com.robsartin.segue.domain.AssertionRecord;
import com.robsartin.segue.domain.EdgeRecord;
import com.robsartin.segue.domain.Hop;
import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.domain.NodeRecord;
import com.robsartin.segue.domain.PathResult;
import com.robsartin.segue.domain.Provenance;
import com.robsartin.segue.port.GraphStore;
import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.process.traversal.Path;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.apache.tinkerpop.gremlin.structure.Edge;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.tinkergraph.structure.TinkerGraph;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Property-graph implementation on Apache TinkerPop's in-memory reference
 * engine. Zero infrastructure: no server, no container, no config. Swapping to
 * JanusGraph later changes how {@code graph} is opened and nothing else, because
 * every query below is written against the Gremlin traversal API rather than the
 * store.
 *
 * <p>Modelling notes:
 * <ul>
 *   <li>Vertices carry {@code qid}, {@code kind}, {@code name}. The property is
 *       {@code name} and not {@code label} because {@code label} is reserved in
 *       TinkerPop - it is the element label, and using it as a property key is an
 *       error that only surfaces at runtime.</li>
 *   <li>Edge label IS the relationship type code, which is what makes multiple
 *       relationship types between the same pair natural.</li>
 *   <li>Provenance is packed into one edge property. See {@link ProvenanceCodec}
 *       for why, and what it costs.</li>
 * </ul>
 */
public final class TinkerGraphStore implements GraphStore {

    private static final String ENTITY = "Entity";
    private static final String P_QID = "qid";
    private static final String P_KIND = "kind";
    private static final String P_NAME = "name";
    private static final String P_SOURCES = "sources";
    private static final String P_VALID_FROM = "validFrom";
    private static final String P_VALID_TO = "validTo";

    private final TinkerGraph graph;
    private final GraphTraversalSource g;

    public TinkerGraphStore() {
        this.graph = TinkerGraph.open();
        // Without this every lookup by qid is a full vertex scan.
        this.graph.createIndex(P_QID, Vertex.class);
        this.g = graph.traversal();
    }

    @Override
    public String id() {
        return "tinkergraph";
    }

    // ---- writes -----------------------------------------------------------

    @Override
    public void upsertNode(NodeRecord node) {
        g.V().has(ENTITY, P_QID, node.qid()).fold()
                .coalesce(__.unfold(), __.addV(ENTITY).property(P_QID, node.qid()))
                .property(P_KIND, node.kind().name())
                .property(P_NAME, node.label())
                .iterate();
    }

    @Override
    public void record(AssertionRecord a) {
        Edge edge = findEdge(a.fromQid(), a.typeCode(), a.toQid())
                .orElseGet(() -> requireVertex(a.fromQid()).addEdge(a.typeCode(), requireVertex(a.toQid())));

        String merged = ProvenanceCodec.append(
                edge.<String>property(P_SOURCES).orElse(""), a.provenance());
        edge.property(P_SOURCES, merged);

        // Validity conflict policy for the spike: first source to supply dates wins.
        // A real system resolves by source precedence or keeps per-assertion dates
        // and reconciles at read time - deliberately deferred, not solved here.
        if (a.validFrom() != null && !edge.property(P_VALID_FROM).isPresent()) {
            edge.property(P_VALID_FROM, a.validFrom().toString());
        }
        if (a.validTo() != null && !edge.property(P_VALID_TO).isPresent()) {
            edge.property(P_VALID_TO, a.validTo().toString());
        }
    }

    private Optional<Edge> findEdge(String fromQid, String typeCode, String toQid) {
        return g.V().has(ENTITY, P_QID, fromQid)
                .outE(typeCode)
                .where(__.inV().has(P_QID, toQid))
                .tryNext();
    }

    private Vertex requireVertex(String qid) {
        return g.V().has(ENTITY, P_QID, qid).tryNext()
                .orElseThrow(() -> new IllegalStateException(
                        "assertion references unknown entity " + qid + " - upsert the node first"));
    }

    // ---- reads ------------------------------------------------------------

    @Override
    public Optional<NodeRecord> node(String qid) {
        return g.V().has(ENTITY, P_QID, qid).tryNext().map(this::toNode);
    }

    @Override
    public List<EdgeRecord> edges(String qid) {
        return g.V().has(ENTITY, P_QID, qid).bothE().dedup().toList()
                .stream().map(this::toEdge).toList();
    }

    @Override
    public int edgeCount() {
        return Math.toIntExact(g.E().count().next());
    }

    // ---- Q1: explanation --------------------------------------------------

    /**
     * One traversal. This is the whole method body, and it is the strongest
     * argument for a property graph: "walk outward until you hit the target,
     * never revisiting a node, and hand me the paths" is a direct statement of
     * the intent.
     *
     * <p>TinkerPop's repeat/until is not breadth-first, so shortest-first
     * ordering is imposed afterwards by sorting - cheap at this scale, and worth
     * replacing with a proper shortest-path algorithm (or Gremlin's
     * {@code shortestPath()} step on engines that support it) if it ever matters.
     */
    @Override
    public List<PathResult> shortestPaths(String fromQid, String toQid, int maxHops, int limit) {
        List<Path> raw = g.V().has(ENTITY, P_QID, fromQid)
                .repeat(__.bothE().otherV().simplePath())
                .until(__.or(__.has(P_QID, toQid), __.loops().is(P.gte(maxHops))))
                .has(P_QID, toQid)
                .path()
                .toList();

        return raw.stream()
                .sorted(Comparator.comparingInt(Path::size))
                .map(this::toPathResult)
                .filter(p -> p.length() > 0 && p.length() <= maxHops)
                .limit(limit)
                .toList();
    }

    private PathResult toPathResult(Path path) {
        List<Object> objects = path.objects();
        List<Hop> hops = new ArrayList<>();
        for (int i = 0; i + 2 < objects.size(); i += 2) {
            Vertex from = (Vertex) objects.get(i);
            Edge via = (Edge) objects.get(i + 1);
            Vertex to = (Vertex) objects.get(i + 2);
            boolean backwards = !via.outVertex().id().equals(from.id());
            hops.add(new Hop(toNode(from), toEdge(via), toNode(to), backwards));
        }
        return new PathResult(hops);
    }

    // ---- Q2: audit --------------------------------------------------------

    /**
     * Full edge scan, filtered in Java. There is no way around it: the encoded
     * provenance blob is opaque to Gremlin, so the engine cannot help. Compare
     * with the Jena adapter, where this is a four-line SPARQL query the engine
     * can index.
     */
    @Override
    public List<EdgeRecord> assertedBy(String sourceId, Instant since) {
        return g.E().toList().stream()
                .map(this::toEdge)
                .filter(e -> e.sources().stream().anyMatch(p ->
                        p.sourceId().equals(sourceId) && !p.assertedAt().isBefore(since)))
                .toList();
    }

    // ---- Q3: time travel --------------------------------------------------

    @Override
    public List<EdgeRecord> validAt(String qid, LocalDate asOf) {
        return edges(qid).stream().filter(e -> e.validAt(asOf)).toList();
    }

    // ---- Q4: corroboration ------------------------------------------------

    /** Another full scan, for the same reason as Q2. */
    @Override
    public List<EdgeRecord> corroborated(int minDistinctSources) {
        return g.E().toList().stream()
                .map(this::toEdge)
                .filter(e -> e.corroboration() >= minDistinctSources)
                .toList();
    }

    // ---- mapping ----------------------------------------------------------

    private NodeRecord toNode(Vertex v) {
        return new NodeRecord(
                v.value(P_QID),
                NodeKind.valueOf(v.value(P_KIND)),
                v.value(P_NAME));
    }

    private EdgeRecord toEdge(Edge e) {
        List<Provenance> sources = ProvenanceCodec.decode(e.<String>property(P_SOURCES).orElse(""));
        return new EdgeRecord(
                e.outVertex().value(P_QID),
                e.inVertex().value(P_QID),
                e.label(),
                dateProperty(e, P_VALID_FROM),
                dateProperty(e, P_VALID_TO),
                sources);
    }

    /**
     * TinkerPop's Property is NOT a java.util.Optional - it has orElse and
     * ifPresent but no map, so the parse has to happen after unwrapping.
     */
    private static LocalDate dateProperty(Edge e, String key) {
        String iso = e.<String>property(key).orElse(null);
        return iso == null ? null : LocalDate.parse(iso);
    }

    @Override
    public void close() {
        try {
            graph.close();
        } catch (Exception ex) {
            throw new IllegalStateException("failed to close TinkerGraph", ex);
        }
    }
}
