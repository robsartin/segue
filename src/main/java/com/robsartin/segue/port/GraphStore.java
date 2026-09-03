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
 * <p>Source adapters emit {@link AssertionRecord}s; this port turns them into a queryable graph.
 * Because the assertion log is the source of truth, any implementation can be rebuilt by replay -
 * so choosing wrong costs an afternoon, not a rewrite.
 *
 * <p>The four query methods below are not an arbitrary API. They are the bake-off: each one is
 * chosen because the two candidate engines differ sharply on it, and together they cover what an
 * affinity graph actually has to do.
 */
public interface GraphStore extends AutoCloseable {

  /** Short name for reports: "tinkergraph" or "jena". */
  String id();

  // ---- writes -----------------------------------------------------------

  void upsertNode(NodeRecord node);

  /**
   * Record one source's claim. Implementations must MERGE: a second source asserting the same
   * (from, type, to) adds provenance to the existing edge rather than creating a duplicate.
   * Different types between the same pair remain distinct edges.
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
   * Q1 - EXPLANATION. Every route between two entities up to {@code maxHops}, each hop citing its
   * sources. The payoff feature, and the query that decides the bake-off: property graphs answer it
   * in one traversal, RDF makes you hand-roll BFS because SPARQL property paths test connectivity
   * without returning the path.
   *
   * <p>Adapters return all routes they found, in no particular order and untruncated. Ordering and
   * bounding are {@link com.robsartin.segue.domain.PathRanking}'s job, applied once above the port
   * so both engines rank identically and neither can drift (ADR 31).
   */
  List<PathResult> paths(String fromQid, String toQid, int maxHops);

  /**
   * Q2 - AUDIT. Everything a given source told you after a given time. The query you run when a
   * source turns out to be wrong and you need to find the blast radius.
   */
  List<EdgeRecord> assertedBy(String sourceId, Instant since);

  /**
   * Q3 - TIME TRAVEL. Relationships that held on a given date. "Who was in the Bad Seeds in 1984"
   * is the test case; the answer must exclude members who joined later and include ones who have
   * since left.
   */
  List<EdgeRecord> validAt(String qid, LocalDate asOf);

  /**
   * Q4 - CORROBORATION. Edges backed by at least N distinct sources. This is what keeps
   * model-generated hypotheses from silently becoming facts, and it is where RDF's named graphs pay
   * for themselves.
   *
   * <p><b>N = 0 returns every edge, the owner's standalone claims included.</b> {@link
   * EdgeRecord#corroboration()} counts distinct NON-owner sources, so an edge the owner claimed and
   * no source ever asserted has corroboration 0 - a count, not a missing answer. ADR 59 makes owner
   * claims a third layer, projected into the graph and exempt from the corroboration count; exempt
   * from the count is not absent from the query, and "at least 0" admits it. An implementation that
   * drops owner-only edges before grouping answers 0 as though it meant "at least one real source",
   * which is what 1 means - and that is the divergence #176 closed.
   *
   * <p>Both implementations have to agree, and agreeing at one N is not agreeing. {@code
   * com.robsartin.segue.tinker.TinkerGraphStoreContractTest#enginesAgreeOnEdgeSets} compares their
   * edge-key sets across the whole range the fixture makes meaningful, and {@code
   * com.robsartin.segue.tinker.TinkerGraphStoreContractTest#shouldPlaceOwnerClaimsByCorroborationWhenEitherEngineAnswersTheRange}
   * pins the shape per engine so that comparison cannot pass on two identical mistakes. {@code
   * com.robsartin.segue.port.GraphStoreContract#shouldReturnTheOwnerOnlyEdgeWhenTheCorroborationFloorIsZero}
   * pins it on whichever engine runs the contract.
   *
   * <p><b>Those three references are {@code @code} and not {@code @link}</b> because the test
   * source set is not on {@code :javadoc}'s classpath - a {@code @link} to any of them fails the
   * task with {@code reference not found}, measured. They are checked all the same: {@code
   * JavadocCitationsTest} resolves every test a main javadoc names against the files under {@code
   * src/test}, member included, so renaming one of these methods and not following it here reds the
   * build. They stay written fully qualified with a {@code #} so a rename's grep finds them too.
   */
  List<EdgeRecord> corroborated(int minDistinctSources);

  @Override
  void close();
}
