package com.robsartin.segue.census;

import com.robsartin.segue.domain.Fold;
import com.robsartin.segue.domain.LoggedAssertion;
import com.robsartin.segue.export.LogProjection;
import com.robsartin.segue.port.AffinityStore;
import com.robsartin.segue.port.AssertionLog;
import com.robsartin.segue.wikidata.KindMapper;
import java.util.List;
import java.util.Objects;

/**
 * Every number the census reports, in the six sections it prints them in.
 *
 * <p><b>Aggregates and nothing else.</b> Every component is an integer or a map of integers; no
 * qid, label or note reaches this type, which is what lets {@code CensusIsSafeToPasteTest} assert
 * over the whole output rather than over a filtered part of it. That is {@code FloorReading}'s own
 * design — "every field is an aggregate, and that is deliberate" — applied to the whole graph.
 *
 * <p><b>The log is read once, and folded once</b> (#246). It used to be read twice — once for the
 * raw rows and once inside {@link LogProjection#of(AssertionLog)} — and folded five times, because
 * each of {@code LogProjection}, {@link ClaimCensus} and {@link TasteCensus} derived the
 * retractions, the merges and the stand-ins from the rows on its own account. The overload on
 * {@code LogProjection} that this class's earlier note rejected as "widening another package's
 * public API for a dev tool's convenience" is now taken, because it is what carries the {@link
 * Fold} as well as the rows; the second read went with it.
 *
 * <p>{@code ArchitectureTest.theCensusFoldsOnce} is what keeps this method the only fold here.
 */
public record Census(
    NodeCensus nodes,
    EdgeCensus edges,
    ClaimCensus claims,
    TasteCensus taste,
    DegreeCensus degree,
    BridgeCensus bridge) {

  public Census {
    Objects.requireNonNull(nodes, "nodes");
    Objects.requireNonNull(edges, "edges");
    Objects.requireNonNull(claims, "claims");
    Objects.requireNonNull(taste, "taste");
    Objects.requireNonNull(degree, "degree");
    Objects.requireNonNull(bridge, "bridge");
  }

  /** Fold once, read once, count six ways. */
  public static Census of(AssertionLog log, AffinityStore ratings) {
    Objects.requireNonNull(log, "log");
    Objects.requireNonNull(ratings, "ratings");
    List<LoggedAssertion> logged = log.readAll();
    Fold fold = Fold.of(logged, KindMapper::rederive);
    LogProjection projection = LogProjection.of(logged, fold);
    return new Census(
        NodeCensus.of(projection),
        EdgeCensus.of(projection),
        ClaimCensus.of(logged, projection, fold),
        TasteCensus.of(ratings.readRatings(), fold, projection),
        DegreeCensus.of(projection),
        BridgeCensus.of(projection));
  }
}
