package com.robsartin.segue.port;

import com.robsartin.segue.domain.AssertionRecord;
import com.robsartin.segue.domain.EdgeRecord;
import com.robsartin.segue.domain.NodeRecord;
import com.robsartin.segue.domain.PathResult;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * The seam that makes the graph-engine decision reversible.
 *
 * <p>Source adapters emit {@link AssertionRecord}s; this port turns them into a
 * queryable graph. Because the assertion log is the source of truth, any
 * implementation can be rebuilt by replay - so choosing wrong costs an afternoon,
 * not a rewrite.
 *
 * <p>The four query methods below are not an arbitrary API. They are the
 * bake-off: each one is chosen because the two candidate engines differ sharply
 * on it, and together they cover what an affinity graph actually has to do.
 */
public interface GraphStore extends AutoCloseable {

    /** Short name for reports: "tinkergraph" or "jena". */
    String id();

    // ---- writes -----------------------------------------------------------

    void upsertNode(NodeRecord node);

    /**
     * Record one source's claim. Implementations must MERGE: a second source
     * asserting the same (from, type, to) adds provenance to the existing edge
     * rather than creating a duplicate. Different types between the same pair
     * remain distinct edges.
     */
    void record(AssertionRecord assertion);

    // ---- reads ------------------------------------------------------------

    Optional<NodeRecord> node(String qid);

    /** Every edge incident to a node, in either direction. */
    List<EdgeRecord> edges(String qid);

    /** Total edge count, for sanity-checking that both stores ingested the same graph. */
    int edgeCount();

    // ---- the four bake-off queries ---------------------------------------

    /**
     * Q1 - EXPLANATION. Shortest paths between two entities, each hop citing its
     * sources. The payoff feature, and the query that decides the bake-off:
     * property graphs answer it in one traversal, RDF makes you hand-roll BFS
     * because SPARQL property paths test connectivity without returning the path.
     */
    List<PathResult> shortestPaths(String fromQid, String toQid, int maxHops, int limit);

    /**
     * Q2 - AUDIT. Everything a given source told you after a given time. The
     * query you run when a source turns out to be wrong and you need to find the
     * blast radius.
     */
    List<EdgeRecord> assertedBy(String sourceId, Instant since);

    /**
     * Q3 - TIME TRAVEL. Relationships that held on a given date. "Who was in the
     * Bad Seeds in 1984" is the test case; the answer must exclude members who
     * joined later and include ones who have since left.
     */
    List<EdgeRecord> validAt(String qid, LocalDate asOf);

    /**
     * Q4 - CORROBORATION. Edges backed by at least N distinct sources. This is
     * what keeps model-generated hypotheses from silently becoming facts, and
     * it is where RDF's named graphs pay for themselves.
     */
    List<EdgeRecord> corroborated(int minDistinctSources);

    @Override
    void close();
}
