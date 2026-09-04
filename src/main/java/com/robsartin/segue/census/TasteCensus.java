package com.robsartin.segue.census;

import com.robsartin.segue.domain.Equivalences;
import com.robsartin.segue.domain.LocalEntity;
import com.robsartin.segue.domain.LoggedAssertion;
import com.robsartin.segue.domain.RatingScale;
import com.robsartin.segue.domain.Retractions;
import com.robsartin.segue.export.LogProjection;
import com.robsartin.segue.wikidata.KindMapper;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * The taste layer by score, and the three kinds of id a rating can end up sitting on.
 *
 * <p><b>Scores and nothing else.</b> This class is handed {@code AffinityStore.readRatings}, whose
 * {@code Map<String, Integer>} has nowhere to put a note, so the census structurally cannot see one
 * — the same fence the recommender's rule turns on (issue #85). {@code
 * ArchitectureTest.onlyTheRecommenderReadsEveryRating} is what admits this package to that read,
 * and ADR 63 is the decision behind it.
 *
 * <p><b>A histogram of scores is an aggregate, and ADR 51 says an aggregate is publishable.</b> No
 * row here names an entity, so no row can attribute a rating to one, which is what "a rating is
 * personal data" actually means — the same line {@code RatingsAreNeverLoggedTest} draws for the
 * listing tool's log lines.
 *
 * @param byScore how many ratings sit at each value, all five emitted and zeros included. A value
 *     outside 1 to 5 would appear as its own row rather than be discarded: {@code
 *     AffinityStore.updateRating} validates through {@link RatingScale}, so one in the table is a
 *     finding, and a census that swallowed it would be the parser that drops what it cannot read
 * @param total every rating there is
 * @param onALocalId ratings on an id the owner minted, by {@link LocalEntity#isLocal} — the one
 *     home for what a local id looks like, asked of the shape rather than of the log, on that
 *     method's own argument that the shape <em>is</em> the identity decision
 * @param onAStandIn ratings on a canonical id a merge named a node for, which is where {@code
 *     IdentityMerge.carryingRatings} moves a rating to
 * @param onARetractedId ratings on an entity a retraction names <b>and</b> the fold no longer
 *     holds. The second half is load-bearing: the log is append-only, so a retracted entity that
 *     was later re-added still has its retraction row forever, and "named by a retraction" alone
 *     would go on counting a rating whose entity is back in the graph
 */
public record TasteCensus(
    Map<Integer, Integer> byScore, int total, int onALocalId, int onAStandIn, int onARetractedId) {

  public TasteCensus {
    byScore =
        Collections.unmodifiableMap(new TreeMap<>(Objects.requireNonNull(byScore, "byScore")));
  }

  public static TasteCensus of(
      Map<String, Integer> ratings, List<LoggedAssertion> logged, LogProjection projection) {
    Objects.requireNonNull(ratings, "ratings");
    Objects.requireNonNull(logged, "logged");
    Objects.requireNonNull(projection, "projection");
    Map<Integer, Integer> byScore = new TreeMap<>();
    for (int score = RatingScale.MIN; score <= RatingScale.MAX; score++) {
      byScore.put(score, 0);
    }

    Set<String> standIns = Equivalences.standIns(logged, KindMapper::rederive).keySet();
    Set<String> retracted = Retractions.in(logged).lastRetraction().keySet();

    int onALocalId = 0;
    int onAStandIn = 0;
    int onARetractedId = 0;
    for (Map.Entry<String, Integer> rated : ratings.entrySet()) {
      byScore.merge(rated.getValue(), 1, Integer::sum);
      String qid = rated.getKey();
      if (LocalEntity.isLocal(qid)) {
        onALocalId++;
      }
      if (standIns.contains(qid)) {
        onAStandIn++;
      }
      if (retracted.contains(qid) && !projection.nodes().containsKey(qid)) {
        onARetractedId++;
      }
    }
    return new TasteCensus(byScore, ratings.size(), onALocalId, onAStandIn, onARetractedId);
  }
}
