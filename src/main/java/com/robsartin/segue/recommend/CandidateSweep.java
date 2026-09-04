package com.robsartin.segue.recommend;

import com.robsartin.segue.domain.EdgeRecord;
import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.domain.NodeRecord;
import com.robsartin.segue.domain.PathRanking;
import com.robsartin.segue.domain.Recommendation;
import com.robsartin.segue.domain.RecommendationWeights;
import com.robsartin.segue.domain.Scorer;
import com.robsartin.segue.domain.SharedIntermediate;
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
import java.util.function.ToDoubleFunction;
import java.util.function.ToIntFunction;

/**
 * Two hops out from everything you already know, and everything that is not allowed to come back
 * (ADR 45).
 *
 * <p>The walk is short and the filters are the design. In order:
 *
 * <ol>
 *   <li><b>Out one hop from every known entity</b>, keeping the best-weighted edge type per
 *       neighbour. Parallel edges between one pair are one relationship stated twice, not two
 *       reasons.
 *   <li><b>Refuse hub intermediates outright</b> — {@link PathRanking#isHub}, the same judgement
 *       routing uses, borrowed rather than reimplemented. Not down-weighted: a candidate reached
 *       through a hall of fame is not a weak recommendation, it is not a recommendation.
 *   <li><b>Out one hop from each surviving intermediate</b>, once per intermediate however many
 *       known entities reached it, which is what keeps this affordable on a real graph. <b>This is
 *       the hop whose direction is read</b> — a hop the candidate states about itself is worth a
 *       fifth of the same hop stated about it (issue #84, {@code
 *       RecommendationWeights.asEvidenceAbout}). The hop out of your own entities deliberately is
 *       not: see {@link Weighing}.
 *   <li><b>Keep the candidates that could be things to explore</b>: a {@code PERSON} or a {@code
 *       GROUP}, absent from the known-list, not an institution, not held back by {@code
 *       KnownList.notOffered} — already looked at and rejected (issue #106), or merged into an id
 *       the owner already has (#92) — and carrying at least {@code minDegree} edges.
 * </ol>
 *
 * <p><b>Why the second pass is keyed on the intermediate.</b> Written the obvious way — for each
 * known entity, for each neighbour, for each of ITS neighbours — a famous intermediate is expanded
 * once per known entity that cites it, and on the real graph the busiest are cited by two dozen.
 * Collecting the reachers per intermediate first turns that into one traversal each.
 *
 * <p><b>It reads, and it holds no taste.</b> The regard for each known entity arrives as a
 * function, never as an {@code AffinityStore}. Since issue #85 that function is usually {@code
 * Recommendations.regardFor} over the owner's ratings, built once in {@code RecommendCli} — the
 * store is opened at the entry point and nowhere else, so nothing in the middle of the sweep can
 * reach a rating it was not handed, or a note at all.
 */
public final class CandidateSweep {

  private final GraphStore graph;
  private final java.util.function.Predicate<String> recognitionInstitutionClass;

  /** Memoised per sweep, for the same reason {@code ViewSelector} memoises it: it is hot. */
  private final Map<String, Integer> degrees = new HashMap<>();

  public CandidateSweep(
      GraphStore graph, java.util.function.Predicate<String> recognitionInstitutionClass) {
    this.graph = Objects.requireNonNull(graph, "graph");
    this.recognitionInstitutionClass =
        Objects.requireNonNull(recognitionInstitutionClass, "recognitionInstitutionClass");
  }

  /**
   * Every entity worth considering, scored but unranked, with what has been rejected held out.
   *
   * @param known the entities you already have — the membership oracle, and the whole reason a
   *     well-connected node absent from it means something
   * @param notOffered entities to exclude from the candidate pool outright — {@code
   *     KnownList.notOffered}, which is issue #106's suppressed set plus the local ids #92's merges
   *     have retired. <b>Deliberately a separate set from {@code known}, never unioned into it</b>:
   *     {@code knownFound} and {@code knownMissing} describe the known-list alone, and folding
   *     either population in would corrupt what those two counts report. Nothing is reported over
   *     <em>this</em> set, which is why the two may share it. Excluded at the same point the
   *     known-list check already is, so an excluded entity is invisible as a final candidate; it is
   *     not filtered out of the walk as an intermediate, and it is not filtered out of {@code
   *     known} itself — those are two separate questions this parameter does not answer
   * @param scorer where on the raw-to-lift spectrum to sit
   * @param minDegree the floor below which a candidate is not ranked. Required under a normalised
   *     scorer; see {@code Recommendations.MIN_CANDIDATE_DEGREE}
   * @param regard what one known entity's connections are worth — {@code Recommendations.regardFor}
   *     over the ratings, which is {@code Recommendations.EQUAL_REGARD} when nothing has been rated
   */
  public Sweep over(
      Collection<String> known,
      Set<String> notOffered,
      Scorer scorer,
      int minDegree,
      ToDoubleFunction<String> regard) {
    Objects.requireNonNull(known, "known");
    Objects.requireNonNull(notOffered, "notOffered");
    Objects.requireNonNull(scorer, "scorer");
    Objects.requireNonNull(regard, "regard");
    Set<String> knownSet = new LinkedHashSet<>(known);

    // via qid -> (known qid -> what the hop from that entity to this intermediate is worth)
    Map<String, Map<String, Double>> reachers = new LinkedHashMap<>();
    int found = 0;
    int missing = 0;
    int hubs = 0;
    Set<String> hubsSeen = new LinkedHashSet<>();

    for (String seed : knownSet) {
      Optional<NodeRecord> node = graph.node(seed);
      if (node.isEmpty()) {
        missing++;
        continue;
      }
      found++;
      for (Map.Entry<String, Double> neighbour :
          bestPerNeighbour(seed, Weighing.OF_THE_RELATIONSHIP).entrySet()) {
        String via = neighbour.getKey();
        // A known entity is allowed to BE an intermediate: "one you know cites one you know, who
        // cites this" is a route, and the known-list filter applies to the candidate at the end.
        if (isHub(via)) {
          if (hubsSeen.add(via)) {
            hubs++;
          }
          continue;
        }
        reachers
            .computeIfAbsent(via, key -> new LinkedHashMap<>())
            .merge(seed, neighbour.getValue() * regard.applyAsDouble(seed), Math::max);
      }
    }

    Map<String, List<SharedIntermediate>> evidence = new LinkedHashMap<>();
    // Keyed by qid rather than counted, because one entity is reached once per intermediate and
    // "how many entities the floor held out" is a count of entities (issue #135).
    Map<String, Integer> heldOutByFloor = new LinkedHashMap<>();
    for (Map.Entry<String, Map<String, Double>> entry : reachers.entrySet()) {
      String via = entry.getKey();
      int viaDegree = degree(via);
      for (Map.Entry<String, Double> candidate :
          bestPerNeighbour(via, Weighing.AS_EVIDENCE_ABOUT_THE_NEIGHBOUR).entrySet()) {
        String qid = candidate.getKey();
        if (knownSet.contains(qid) || notOffered.contains(qid) || !couldBeExplored(qid)) {
          continue;
        }
        // Separated from the tests above so that what the FLOOR held out is countable apart from
        // what the kind rules refused. A record and a learned society are not candidates at any
        // floor; a thin band is a candidate at a lower one, and only the second is drift.
        int candidateDegree = degree(qid);
        if (candidateDegree < minDegree) {
          heldOutByFloor.put(qid, candidateDegree);
          continue;
        }
        for (Map.Entry<String, Double> reacher : entry.getValue().entrySet()) {
          if (reacher.getKey().equals(qid)) {
            continue;
          }
          evidence
              .computeIfAbsent(qid, key -> new ArrayList<>())
              .add(
                  new SharedIntermediate(
                      reacher.getKey(), via, viaDegree, reacher.getValue() * candidate.getValue()));
        }
      }
    }

    List<Recommendation> candidates = new ArrayList<>();
    for (Map.Entry<String, List<SharedIntermediate>> entry : evidence.entrySet()) {
      NodeRecord entity = graph.node(entry.getKey()).orElseThrow();
      int degree = degree(entry.getKey());
      candidates.add(
          new Recommendation(
              entity, scorer.score(entry.getValue(), degree), degree, entry.getValue()));
    }
    return new Sweep(
        candidates,
        found,
        missing,
        hubs,
        heldOutByFloor.size(),
        (int) heldOutByFloor.values().stream().filter(degree -> degree == 1).count());
  }

  /**
   * Which question a hop's weight is answering, and so whether its direction is read (issue #84).
   */
  private enum Weighing {

    /**
     * What the relationship is worth, whichever way round it was stated. The hop out of one of your
     * own entities: it is what makes the intermediate shared, and "who the things I like came from"
     * and "who came from them" are both segues (ADR 45).
     */
    OF_THE_RELATIONSHIP,

    /**
     * What the relationship is worth <em>as evidence about the entity at the far end</em>. The hop
     * into a candidate, and the only place direction is read — being cited is a fact somebody else
     * stated, citing is a fact the candidate stated about itself.
     */
    AS_EVIDENCE_ABOUT_THE_NEIGHBOUR
  }

  /**
   * Every neighbour of one entity, with the best weight among the edges reaching it.
   *
   * <p>The best rather than the sum, because parallel edges between one pair are one relationship
   * the source stated twice — a membership and a "has part" over the same two entities is the case
   * ADR 36's issue-#33 amendment already had to fix once in ingest. Taking the best is also what
   * makes the direction rule right in the awkward case: an entity that both cites its neighbour and
   * is cited by it keeps the full weight of being cited.
   *
   * @param weighing whether the neighbour is the one being judged, and so whether the direction of
   *     an esteem-directional edge is read off it
   */
  private Map<String, Double> bestPerNeighbour(String qid, Weighing weighing) {
    List<EdgeRecord> edges = graph.edges(qid);
    degrees.putIfAbsent(qid, edges.size());
    Map<String, Double> best = new LinkedHashMap<>();
    for (EdgeRecord edge : edges) {
      boolean statedByThisEntity = edge.fromQid().equals(qid);
      String other = statedByThisEntity ? edge.toQid() : edge.fromQid();
      if (other.equals(qid)) {
        continue;
      }
      double weight =
          weighing == Weighing.OF_THE_RELATIONSHIP
              ? RecommendationWeights.of(edge.typeCode())
              // The neighbour states the claim exactly when this entity does not.
              : RecommendationWeights.asEvidenceAbout(edge.typeCode(), !statedByThisEntity);
      best.merge(other, weight, Math::max);
    }
    return best;
  }

  /** ADR 31's hub judgement, asked of an intermediate before any route through it exists. */
  private boolean isHub(String qid) {
    ToIntFunction<String> lookup = this::degree;
    return graph
        .node(qid)
        .map(node -> PathRanking.isHub(node, lookup, recognitionInstitutionClass))
        .orElse(true);
  }

  /**
   * Whether this entity is a thing one could go and explore.
   *
   * <p>A {@code PERSON} or a {@code GROUP}: a record, a prize or a city is a fact about a
   * connection rather than something to listen to next. Not an institution, for the reason the hub
   * rule excludes one as an intermediate — a recommender without this filter suggests joining a
   * learned society.
   *
   * <p><b>The degree floor is deliberately not asked here.</b> It used to be, and it is now applied
   * by the caller, so that an entity refused for its kind and an entity refused for its size are
   * distinguishable — {@code Sweep.heldOutByFloor} counts only the second. The floor is the one
   * filter whose number has been re-decided twice (ADR 45 and its 2026-08-29 amendment), and a
   * count that mixed in records and learned societies would not be a reading of it.
   *
   * <p><b>Public since issue #239, and for the reason {@code PathRanking.isHub} is.</b> The
   * evaluation harness holds out entities it must be able to offer back, so its eligibility rule
   * and this one have to be the same sentence. One implementation, two readings — a second copy
   * would agree until the day somebody changed one of them.
   */
  public boolean couldBeExplored(String qid) {
    Optional<NodeRecord> node = graph.node(qid);
    if (node.isEmpty()) {
      return false;
    }
    NodeKind kind = node.get().kind();
    if (kind != NodeKind.PERSON && kind != NodeKind.GROUP) {
      return false;
    }
    return node.get().instanceOf().stream().noneMatch(recognitionInstitutionClass);
  }

  private int degree(String qid) {
    return degrees.computeIfAbsent(qid, key -> graph.edges(key).size());
  }
}
