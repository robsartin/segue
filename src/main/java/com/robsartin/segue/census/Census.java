package com.robsartin.segue.census;

import com.robsartin.segue.domain.Expanded;
import com.robsartin.segue.domain.Fold;
import com.robsartin.segue.domain.LoggedAssertion;
import com.robsartin.segue.domain.SecondHop;
import com.robsartin.segue.ingest.LogProjection;
import com.robsartin.segue.port.AffinityStore;
import com.robsartin.segue.port.AssertionLog;
import com.robsartin.segue.support.KnownListInput;
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
 * public API for a dev tool's convenience" is now taken, since it also carries the {@link Fold};
 * the second read went with it.
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
    return reading(log, ratings, known).census();
  }

  /**
   * One run's answers: the block, the fold it counted, and — when a known-list file was named — the
   * rule the isolated file is written from (#319).
   *
   * <p><b>A second type rather than a component of {@link Census}.</b>
   *
   * <p>This record carries a {@code SecondHop}, which holds entity ids, and {@code Census}'s own
   * guarantee is that every component of it is an integer or a map of integers but for the class
   * qids ADR 63's 2026-09-04 amendment allows. Widening that guarantee to let one dev-tool file be
   * written would be paying for the file with the property the block's paste rule rests on.
   *
   * @param census what {@code CensusReport} renders
   * @param projection the fold, for the labels and kinds the file's rows carry
   * @param isolation the rule over the WITH-promotions population, or empty when no {@code --known}
   *     file was given
   */
  public record Reading(Census census, LogProjection projection, Optional<SecondHop> isolation) {

    public Reading {
      Objects.requireNonNull(census, "census");
      Objects.requireNonNull(projection, "projection");
      Objects.requireNonNull(isolation, "isolation");
    }
  }

  /**
   * {@link #of}'s full answer, carrying the fold and the isolation rule alongside the block.
   *
   * <p><b>Composes the with-promotions population exactly as {@link KnownListCensus#of} does</b>:
   * canonicalised, then promoted over the resolved ratings, through one shared static that both
   * call — {@link KnownListCensus#populationsOf} — so the two cannot answer different questions
   * about who is promoted.
   *
   * @param known the known-list file, or empty where {@code --known} was not given
   */
  public static Reading reading(
      AssertionLog log, AffinityStore ratings, Optional<KnownListInput> known) {
    Objects.requireNonNull(log, "log");
    Objects.requireNonNull(ratings, "ratings");
    Objects.requireNonNull(known, "known");
    List<LoggedAssertion> logged = log.readAll();
    Fold fold = Fold.of(logged, KindMapper::rederive);
    LogProjection projection = LogProjection.of(logged, fold);
    // Read once and shared, where TasteCensus used to take it inline: the same answer, so the two
    // sections that read it cannot disagree about what the taste layer says.
    Map<String, Integer> scores = ratings.readRatings();
    // Expanded.in is built inside the map, so a run without the flag never walks the rows
    // for it at all.
    Optional<Expanded> seeds = known.map(file -> Expanded.in(logged));
    Optional<KnownListCensus> knownList =
        known.map(file -> KnownListCensus.of(file, seeds.orElseThrow(), projection, fold, scores));
    Optional<SecondHop> isolation =
        known.map(
            file -> {
              KnownListCensus.Populations populations =
                  KnownListCensus.populationsOf(file, fold, scores);
              Expanded onCanonicalSide =
                  seeds.orElseThrow().onTheCanonicalSide(fold.equivalences());
              return SecondHop.of(
                  projection.nodes(),
                  projection.edges(),
                  populations.withPromotions(),
                  onCanonicalSide);
            });
    Census census =
        new Census(
            NodeCensus.of(projection),
            EdgeCensus.of(projection),
            ClaimCensus.of(logged, projection, fold),
            TasteCensus.of(scores, fold, projection),
            DegreeCensus.of(projection),
            BridgeCensus.of(projection),
            ConceptClassCensus.of(projection),
            knownList);
    return new Reading(census, projection, isolation);
  }
}
