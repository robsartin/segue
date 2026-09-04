package com.robsartin.segue.census;

import com.robsartin.segue.domain.LoggedAssertion;
import com.robsartin.segue.export.LogProjection;
import com.robsartin.segue.port.AffinityStore;
import com.robsartin.segue.port.AssertionLog;
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
 * <p><b>The log is read twice</b>, once for the raw rows and once by {@link LogProjection#of}. The
 * alternative is an overload on {@code LogProjection} taking an already-read list, which widens
 * another package's public API for a dev tool's convenience; this tool is run by hand, and a second
 * pass costs seconds.
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

  /** Fold the log once, count it six ways. */
  public static Census of(AssertionLog log, AffinityStore ratings) {
    Objects.requireNonNull(log, "log");
    Objects.requireNonNull(ratings, "ratings");
    List<LoggedAssertion> logged = log.readAll();
    LogProjection projection = LogProjection.of(log);
    return new Census(
        NodeCensus.of(projection),
        EdgeCensus.of(projection),
        ClaimCensus.of(logged, projection),
        TasteCensus.of(ratings.readRatings(), logged, projection),
        DegreeCensus.of(projection),
        BridgeCensus.of(projection));
  }
}
