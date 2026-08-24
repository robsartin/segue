package com.robsartin.segue.jena;

import com.robsartin.segue.domain.AssertionRecord;
import com.robsartin.segue.domain.EdgeRecord;
import com.robsartin.segue.domain.Hop;
import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.domain.NodeRecord;
import com.robsartin.segue.domain.PathResult;
import com.robsartin.segue.domain.Provenance;
import com.robsartin.segue.port.GraphStore;
import org.apache.jena.datatypes.xsd.XSDDatatype;
import org.apache.jena.query.Dataset;
import org.apache.jena.query.DatasetFactory;
import org.apache.jena.query.ParameterizedSparqlString;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QueryExecutionFactory;
import org.apache.jena.query.QuerySolution;
import org.apache.jena.query.ResultSet;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.system.Txn;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * RDF quad-store implementation on Apache Jena.
 *
 * <p>The central modelling choice: EVERY ASSERTION GETS ITS OWN NAMED GRAPH,
 * containing exactly one triple. Metadata about who said it, when, with what
 * confidence, and over what validity interval attaches to the graph IRI in the
 * default graph. Three consequences fall out for free:
 *
 * <ol>
 *   <li>No merge logic. Two sources claiming the same relationship are simply two
 *       named graphs containing the same triple. Compare the TinkerGraph adapter,
 *       which needs explicit find-then-append code for this.</li>
 *   <li>Corroboration is a GROUP BY. "Which relationships do at least two distinct
 *       sources agree on" is one query the engine can optimise, not an application
 *       scan.</li>
 *   <li>Retraction is graph-level. Dropping everything a bad source ever said is
 *       DELETE on the graphs it owns, with no risk of removing a claim another
 *       source also happens to make.</li>
 * </ol>
 *
 * <p>The cost lands squarely on paths - see {@link #shortestPaths}.
 */
public final class JenaGraphStore implements GraphStore {

    private final Dataset ds = DatasetFactory.createTxnMem();

    @Override
    public String id() {
        return "jena";
    }

    // ---- writes -----------------------------------------------------------

    @Override
    public void upsertNode(NodeRecord node) {
        Txn.executeWrite(ds, () -> {
            Model def = ds.getDefaultModel();
            Resource subject = def.createResource(Vocab.entity(node.qid()));
            def.removeAll(subject, def.createProperty(Vocab.RDFS + "label"), null);
            def.removeAll(subject, def.createProperty(Vocab.P_KIND), null);
            def.add(subject, def.createProperty(Vocab.RDFS + "label"), node.label());
            def.add(subject, def.createProperty(Vocab.P_KIND), node.kind().name());
        });
    }

    @Override
    public void record(AssertionRecord a) {
        Txn.executeWrite(ds, () -> {
            String graphIri = "urn:assertion:" + UUID.randomUUID();

            // The claim itself: one triple, alone in its own graph.
            Model claim = ModelFactory.createDefaultModel();
            claim.add(
                    claim.createResource(Vocab.entity(a.fromQid())),
                    claim.createProperty(Vocab.predicate(a.typeCode())),
                    claim.createResource(Vocab.entity(a.toQid())));
            // Jena 5 also offers addNamedModel(Resource, Model); the String
            // overload is deprecated but present.
            ds.addNamedModel(graphIri, claim);

            // Everything about the claim: attached to the graph IRI, in the default graph.
            Model def = ds.getDefaultModel();
            Resource g = def.createResource(graphIri);
            Provenance p = a.provenance();
            def.add(g, def.createProperty(Vocab.P_SOURCE), p.sourceId());
            if (p.sourceRef() != null) {
                def.add(g, def.createProperty(Vocab.P_SOURCE_REF), p.sourceRef());
            }
            def.add(g, def.createProperty(Vocab.P_ASSERTED_AT),
                    def.createTypedLiteral(p.assertedAt().toString(), XSDDatatype.XSDdateTime));
            def.add(g, def.createProperty(Vocab.P_CONFIDENCE),
                    def.createTypedLiteral(Double.toString(p.confidence()), XSDDatatype.XSDdecimal));
            if (a.validFrom() != null) {
                def.add(g, def.createProperty(Vocab.P_VALID_FROM),
                        def.createTypedLiteral(a.validFrom().toString(), XSDDatatype.XSDdate));
            }
            if (a.validTo() != null) {
                def.add(g, def.createProperty(Vocab.P_VALID_TO),
                        def.createTypedLiteral(a.validTo().toString(), XSDDatatype.XSDdate));
            }
        });
    }

    // ---- reads ------------------------------------------------------------

    @Override
    public Optional<NodeRecord> node(String qid) {
        ParameterizedSparqlString pss = new ParameterizedSparqlString(Vocab.PREFIXES + """
                SELECT ?label ?kind WHERE {
                  ?e rdfs:label ?label ; sg:kind ?kind .
                }
                """);
        pss.setIri("e", Vocab.entity(qid));
        return Txn.calculateRead(ds, () -> {
            try (QueryExecution qe = QueryExecutionFactory.create(pss.asQuery(), ds)) {
                ResultSet rs = qe.execSelect();
                if (!rs.hasNext()) return Optional.<NodeRecord>empty();
                QuerySolution s = rs.next();
                return Optional.of(new NodeRecord(
                        qid,
                        NodeKind.valueOf(s.getLiteral("kind").getString()),
                        s.getLiteral("label").getString()));
            }
        });
    }

    @Override
    public List<EdgeRecord> edges(String qid) {
        String iri = Vocab.entity(qid);
        return allEdges().values().stream()
                .filter(e -> Vocab.entity(e.fromQid()).equals(iri) || Vocab.entity(e.toQid()).equals(iri))
                .toList();
    }

    @Override
    public int edgeCount() {
        return allEdges().size();
    }

    /**
     * One query pulls every assertion row; grouping by (subject, predicate,
     * object) in Java collapses them into edges. This is the hydration step the
     * other three queries share - SPARQL finds the KEYS elegantly, then the full
     * edge with all its sources has to be reassembled.
     */
    private Map<String, EdgeRecord> allEdges() {
        String sparql = Vocab.PREFIXES + """
                SELECT ?f ?p ?t ?src ?ref ?at ?conf ?vf ?vt WHERE {
                  GRAPH ?g { ?f ?p ?t }
                  ?g sg:source ?src ; sg:assertedAt ?at ; sg:confidence ?conf .
                  OPTIONAL { ?g sg:sourceRef ?ref }
                  OPTIONAL { ?g sg:validFrom ?vf }
                  OPTIONAL { ?g sg:validTo ?vt }
                }
                """;
        return Txn.calculateRead(ds, () -> {
            Map<String, EdgeRecord> byKey = new LinkedHashMap<>();
            try (QueryExecution qe = QueryExecutionFactory.create(sparql, ds)) {
                ResultSet rs = qe.execSelect();
                while (rs.hasNext()) {
                    QuerySolution s = rs.next();
                    String fromQid = Vocab.qidOf(s.getResource("f").getURI());
                    String toQid = Vocab.qidOf(s.getResource("t").getURI());
                    String typeCode = Vocab.typeCodeOf(s.getResource("p").getURI());

                    Provenance prov = new Provenance(
                            s.getLiteral("src").getString(),
                            s.contains("ref") ? s.getLiteral("ref").getString() : null,
                            Instant.parse(s.getLiteral("at").getString()),
                            Double.parseDouble(s.getLiteral("conf").getLexicalForm()));
                    LocalDate vf = s.contains("vf") ? LocalDate.parse(s.getLiteral("vf").getString()) : null;
                    LocalDate vt = s.contains("vt") ? LocalDate.parse(s.getLiteral("vt").getString()) : null;

                    String key = fromQid + " " + typeCode + " " + toQid;
                    EdgeRecord existing = byKey.get(key);
                    if (existing == null) {
                        byKey.put(key, new EdgeRecord(fromQid, toQid, typeCode, vf, vt, List.of(prov)));
                    } else {
                        List<Provenance> merged = new ArrayList<>(existing.sources());
                        merged.add(prov);
                        // Same first-writer-wins validity policy as the TinkerGraph adapter,
                        // so the two stores can be compared like for like.
                        byKey.put(key, new EdgeRecord(
                                fromQid, toQid, typeCode,
                                existing.validFrom() != null ? existing.validFrom() : vf,
                                existing.validTo() != null ? existing.validTo() : vt,
                                merged));
                    }
                }
            }
            return byKey;
        });
    }
    // ---- Q1: explanation --------------------------------------------------

    /**
     * The expensive answer, and the single most important output of this bake-off.
     *
     * <p>SPARQL 1.1 property paths can tell you THAT two entities are connected -
     * {@code ?a (sgp:X|^sgp:X)* ?b} - but they cannot tell you HOW. There is no
     * standard construct that returns the intermediate nodes, so a citable
     * explanation has to be assembled by hand: depth-first enumeration of simple
     * paths, one SPARQL round trip per node expanded, a neighbour cache to stop
     * that being quadratic, then a reconstruction pass.
     *
     * <p><b>Traversal has to be over EDGES, not nodes.</b> The obvious neighbour
     * query - {@code SELECT DISTINCT ?other} - silently destroys the multigraph:
     * Nick Cave both scored and wrote The Proposition, and a node-level walk
     * collapses those into one neighbour, so one of the two routes disappears and
     * the reconstruction has to guess which relationship it walked. The query
     * below therefore returns (predicate, other, direction) triples, and the
     * enumeration carries the predicate the whole way.
     *
     * <p>Gremlin gets this for free: {@code bothE().otherV()} steps through edges
     * by construction, so parallel edges are distinct paths without anyone having
     * to think about it. Everything from here to the end of {@link #neighbours} is
     * machinery the Gremlin adapter replaces with one
     * {@code repeat().until().path()}.
     */
    @Override
    public List<PathResult> shortestPaths(String fromQid, String toQid, int maxHops, int limit) {
        if (fromQid.equals(toQid)) return List.of();

        Map<String, List<Step>> neighbourCache = new HashMap<>();
        List<List<Step>> routes = new ArrayList<>();
        Set<String> onPath = new HashSet<>();
        onPath.add(fromQid);

        enumerate(fromQid, toQid, maxHops, new ArrayList<>(), onPath, routes, neighbourCache);
        routes.sort(Comparator.comparingInt(List::size));

        Map<String, EdgeRecord> edges = allEdges();
        Map<String, NodeRecord> nodeCache = new HashMap<>();
        List<PathResult> results = new ArrayList<>();
        for (List<Step> route : routes.stream().limit(limit).toList()) {
            List<Hop> hops = new ArrayList<>();
            String current = fromQid;
            for (Step step : route) {
                String key = step.backwards()
                        ? step.otherQid() + " " + step.typeCode() + " " + current
                        : current + " " + step.typeCode() + " " + step.otherQid();
                EdgeRecord edge = edges.get(key);
                if (edge == null) throw new IllegalStateException("no edge for step " + key);
                hops.add(new Hop(
                        nodeCache.computeIfAbsent(current, q -> node(q).orElseThrow()),
                        edge,
                        nodeCache.computeIfAbsent(step.otherQid(), q -> node(q).orElseThrow()),
                        step.backwards()));
                current = step.otherQid();
            }
            results.add(new PathResult(hops));
        }
        return results;
    }

    /** One traversable edge: which relationship, to which entity, in which direction. */
    private record Step(String typeCode, String otherQid, boolean backwards) {
    }

    /** Depth-first enumeration of simple paths, mirroring Gremlin's simplePath(). */
    private void enumerate(String current, String goal, int hopsLeft,
                           List<Step> path, Set<String> onPath,
                           List<List<Step>> out, Map<String, List<Step>> cache) {
        if (hopsLeft == 0) return;
        for (Step step : cache.computeIfAbsent(current, this::neighbours)) {
            if (onPath.contains(step.otherQid())) continue;
            path.add(step);
            onPath.add(step.otherQid());
            if (step.otherQid().equals(goal)) {
                out.add(new ArrayList<>(path));
            } else {
                enumerate(step.otherQid(), goal, hopsLeft - 1, path, onPath, out, cache);
            }
            path.remove(path.size() - 1);
            onPath.remove(step.otherQid());
        }
    }

    /**
     * One SPARQL round trip per node expanded. Returns edges rather than nodes -
     * see the class-level note on why DISTINCT ?other is a trap here.
     */
    private List<Step> neighbours(String qid) {
        ParameterizedSparqlString pss = new ParameterizedSparqlString(Vocab.PREFIXES + """
                SELECT DISTINCT ?p ?other ?back WHERE {
                  { GRAPH ?g { ?e ?p ?other } BIND(false AS ?back) }
                  UNION
                  { GRAPH ?g { ?other ?p ?e } BIND(true AS ?back) }
                }
                """);
        pss.setIri("e", Vocab.entity(qid));
        return Txn.calculateRead(ds, () -> {
            List<Step> out = new ArrayList<>();
            try (QueryExecution qe = QueryExecutionFactory.create(pss.asQuery(), ds)) {
                ResultSet rs = qe.execSelect();
                while (rs.hasNext()) {
                    QuerySolution s = rs.next();
                    Resource other = s.getResource("other");
                    Resource predicate = s.getResource("p");
                    if (other == null || other.getURI() == null
                            || !other.getURI().startsWith(Vocab.WD)) continue;
                    if (predicate == null || predicate.getURI() == null
                            || !predicate.getURI().startsWith(Vocab.SGP)) continue;
                    out.add(new Step(
                            Vocab.typeCodeOf(predicate.getURI()),
                            Vocab.qidOf(other.getURI()),
                            s.getLiteral("back").getBoolean()));
                }
            }
            return out;
        });
    }

    // ---- Q2: audit --------------------------------------------------------

    /**
     * Where the named-graph model earns its keep. The engine does the filtering,
     * and it can index it - contrast the TinkerGraph adapter's full edge scan.
     */
    @Override
    public List<EdgeRecord> assertedBy(String sourceId, Instant since) {
        ParameterizedSparqlString pss = new ParameterizedSparqlString(Vocab.PREFIXES + """
                SELECT DISTINCT ?f ?p ?t WHERE {
                  GRAPH ?g { ?f ?p ?t }
                  ?g sg:source ?src ; sg:assertedAt ?at .
                  FILTER (?src = ?sourceId && ?at >= ?since)
                }
                """);
        pss.setLiteral("sourceId", sourceId);
        pss.setLiteral("since", since.toString(), XSDDatatype.XSDdateTime);
        return selectKeysThenHydrate(pss);
    }

    // ---- Q3: time travel --------------------------------------------------

    /**
     * Open-ended intervals are handled with BOUND checks rather than sentinel
     * dates, which reads more honestly than the 9999-12-31 trick.
     */
    @Override
    public List<EdgeRecord> validAt(String qid, LocalDate asOf) {
        ParameterizedSparqlString pss = new ParameterizedSparqlString(Vocab.PREFIXES + """
                SELECT DISTINCT ?f ?p ?t WHERE {
                  GRAPH ?g { ?f ?p ?t }
                  ?g sg:source ?src .
                  OPTIONAL { ?g sg:validFrom ?vf }
                  OPTIONAL { ?g sg:validTo ?vt }
                  FILTER (?f = ?entity || ?t = ?entity)
                  FILTER (!BOUND(?vf) || ?vf <= ?asOf)
                  FILTER (!BOUND(?vt) || ?vt >= ?asOf)
                }
                """);
        pss.setIri("entity", Vocab.entity(qid));
        pss.setLiteral("asOf", asOf.toString(), XSDDatatype.XSDdate);
        return selectKeysThenHydrate(pss);
    }

    // ---- Q4: corroboration ------------------------------------------------

    /**
     * The query that would be painful in a property graph and is trivial here:
     * one GROUP BY over named graphs, with the engine counting distinct sources.
     */
    @Override
    public List<EdgeRecord> corroborated(int minDistinctSources) {
        ParameterizedSparqlString pss = new ParameterizedSparqlString(Vocab.PREFIXES + """
                SELECT ?f ?p ?t (COUNT(DISTINCT ?src) AS ?n) WHERE {
                  GRAPH ?g { ?f ?p ?t }
                  ?g sg:source ?src .
                }
                GROUP BY ?f ?p ?t
                HAVING (COUNT(DISTINCT ?src) >= ?minSources)
                """);
        pss.setLiteral("minSources", minDistinctSources);
        return selectKeysThenHydrate(pss);
    }

    // ---- shared plumbing --------------------------------------------------

    /**
     * SPARQL selects the matching (subject, predicate, object) keys; the full
     * edges - with every source, not just the matching one - are reassembled
     * afterwards. A production version would push the key set back in as a VALUES
     * clause instead of hydrating everything.
     */
    private List<EdgeRecord> selectKeysThenHydrate(ParameterizedSparqlString pss) {
        Set<String> keys = Txn.calculateRead(ds, () -> {
            Set<String> found = new LinkedHashSet<>();
            try (QueryExecution qe = QueryExecutionFactory.create(pss.asQuery(), ds)) {
                ResultSet rs = qe.execSelect();
                while (rs.hasNext()) {
                    QuerySolution s = rs.next();
                    found.add(Vocab.qidOf(s.getResource("f").getURI())
                            + " " + Vocab.typeCodeOf(s.getResource("p").getURI())
                            + " " + Vocab.qidOf(s.getResource("t").getURI()));
                }
            }
            return found;
        });
        Map<String, EdgeRecord> all = allEdges();
        return keys.stream().map(all::get).filter(java.util.Objects::nonNull).toList();
    }

    @Override
    public void close() {
        ds.close();
    }
}
