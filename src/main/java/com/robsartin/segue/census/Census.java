package com.robsartin.segue.census;

import com.robsartin.segue.domain.Expanded;
import com.robsartin.segue.domain.Fold;
import com.robsartin.segue.domain.LoggedAssertion;
import com.robsartin.segue.export.LogProjection;
import com.robsartin.segue.port.AffinityStore;
import com.robsartin.segue.port.AssertionLog;
import com.robsartin.segue.wikidata.KindMapper;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Every number the census reports, in the seven sections it prints them in.
 *
 * <p><b>Aggregates, and one identifier.</b> Every component is an integer or a map of integers,
 * with a single exception ruled on by ADR 63's 2026-09-04 amendment: {@link ConceptClassCensus}
 * carries the class qids that {@code CONCEPT} nodes state. A class id is vocabulary rather than an
 * entity — the standing the edge type codes and source ids already have — and it is the only value
 * here that is not a number. No label and no note reaches this type at all, which is what still
 * lets {@code CensusIsSafeToPasteTest} assert over the whole output rather than a filtered part of
 * it.
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
    BridgeCensus bridge,
    ConceptClassCensus conceptClasses,
    Optional<KnownListCensus> knownList) {

  public Census {
    Objects.requireNonNull(nodes, "nodes");
    Objects.requireNonNull(edges, "edges");
    Objects.requireNonNull(claims, "claims");
    Objects.requireNonNull(taste, "taste");
    Objects.requireNonNull(degree, "degree");
    Objects.requireNonNull(bridge, "bridge");
    Objects.requireNonNull(conceptClasses, "conceptClasses");
    Objects.requireNonNull(knownList, "knownList");
  }

  /**
   * Fold once, read once, count seven ways — eight when a known-list file was named.
   *
   * <p><b>The eighth section is optional because the flag is</b>, and that is what keeps the
   * no-flag block byte-identical to the one printed before issue #311: {@link CensusReport} adds no
   * line for an empty one, so neither column width moves.
   *
   * @param known the known-list file, or empty where {@code --known} was not given
   */
  public static Census of(AssertionLog log, AffinityStore ratings, Optional<KnownListInput> known) {
    Objects.requireNonNull(log, "log");
    Objects.requireNonNull(ratings, "ratings");
    Objects.requireNonNull(known, "known");
    List<LoggedAssertion> logged = log.readAll();
    Fold fold = Fold.of(logged, KindMapper::rederive);
    LogProjection projection = LogProjection.of(logged, fold);
    // Read once and shared, where TasteCensus used to take it inline: the same answer, so the two
    // sections that read it cannot disagree about what the taste layer says.
    Map<String, Integer> scores = ratings.readRatings();
    return new Census(
        NodeCensus.of(projection),
        EdgeCensus.of(projection),
        ClaimCensus.of(logged, projection, fold),
        TasteCensus.of(scores, fold, projection),
        DegreeCensus.of(projection),
        BridgeCensus.of(projection),
        ConceptClassCensus.of(projection),
        // Expanded.in is built inside the map, so a run without the flag never walks the rows
        // for it at all.
        known.map(file -> KnownListCensus.of(file, Expanded.in(logged), projection, fold, scores)));
  }
}
