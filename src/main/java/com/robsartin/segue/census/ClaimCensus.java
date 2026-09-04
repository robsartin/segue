package com.robsartin.segue.census;

import com.robsartin.segue.domain.Equivalences;
import com.robsartin.segue.domain.LocalEntity;
import com.robsartin.segue.domain.LoggedAssertion;
import com.robsartin.segue.domain.Retraction;
import com.robsartin.segue.domain.Retractions;
import com.robsartin.segue.domain.SameAs;
import com.robsartin.segue.export.LogProjection;
import com.robsartin.segue.wikidata.KindMapper;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * What the log holds, and what retraction and merge did to it.
 *
 * <p><b>Every rule here is read from {@code domain}, not restated.</b> {@link Retractions#survives}
 * is the same question both folds ask, {@link Equivalences#last} is the rating carry's predicate
 * and {@link Equivalences#stands} is the stand-in's, and {@link Equivalences#standIns} is the
 * pre-pass both folds seed themselves from. A census with its own idea of what a merge reaches
 * would be a fourth home for a rule that already has four (issue #220).
 *
 * @param rows every row in the log, retractions and superseded merges included — the raw size,
 *     which is the one figure here that is not a derivation
 * @param retractions rows that are a {@link Retraction}
 * @param rowsRetracted rows a retraction reaches: not a retraction themselves, and refused by
 *     {@link Retractions#survives}. <b>Not the same as {@code retractions}</b>, and the gap between
 *     the two is the blast radius ADR 44 talks about
 * @param entitiesRetracted distinct entities a retraction names
 * @param localEntitiesMinted surviving {@code LocalEntity} rows. <b>Rows, not entities:</b> nothing
 *     forbids one qid appearing on two of them, and a count that deduplicated would hide it
 * @param mergesStanding surviving merges that resolve their local id today
 * @param mergesSuperseded surviving merges a later merge of the same local id has corrected
 * @param mergesSupersededButEdgeReferenced the subset of those whose canonical id an edge the fold
 *     keeps still names, so its stand-in stands anyway (#221, narrowed by #228). A subset, never a
 *     third bucket
 * @param standIns canonical ids a surviving merge names a node for
 * @param standInsWithNoEdge how many of those ended with degree zero — a node standing for an
 *     entity the fold knows nothing else about
 */
public record ClaimCensus(
    int rows,
    int retractions,
    int rowsRetracted,
    int entitiesRetracted,
    int localEntitiesMinted,
    int mergesStanding,
    int mergesSuperseded,
    int mergesSupersededButEdgeReferenced,
    int standIns,
    int standInsWithNoEdge) {

  public static ClaimCensus of(List<LoggedAssertion> logged, LogProjection projection) {
    Objects.requireNonNull(logged, "logged");
    Objects.requireNonNull(projection, "projection");

    Retractions retractions = Retractions.in(logged);
    Equivalences equivalences = Equivalences.in(logged);
    Map<String, Integer> degrees = Degrees.in(projection);

    int retractionRows = 0;
    int rowsRetracted = 0;
    int minted = 0;
    int standing = 0;
    int superseded = 0;
    int supersededButReferenced = 0;
    for (int i = 0; i < logged.size(); i++) {
      LoggedAssertion assertion = logged.get(i);
      if (assertion instanceof Retraction) {
        // A retraction never survives its own rule — it describes the fold rather than appearing
        // in it — so it is counted here and not as a row something retracted.
        retractionRows++;
      } else if (!retractions.survives(i, assertion)) {
        rowsRetracted++;
      } else if (assertion instanceof LocalEntity) {
        minted++;
      } else if (assertion instanceof SameAs merge) {
        if (equivalences.last(merge)) {
          standing++;
        } else {
          superseded++;
          if (equivalences.stands(merge)) {
            supersededButReferenced++;
          }
        }
      }
    }

    Set<String> standIns = Equivalences.standIns(logged, KindMapper::rederive).keySet();
    int withNoEdge =
        (int) standIns.stream().filter(qid -> degrees.getOrDefault(qid, 0) == 0).count();

    return new ClaimCensus(
        logged.size(),
        retractionRows,
        rowsRetracted,
        retractions.lastRetraction().size(),
        minted,
        standing,
        superseded,
        supersededButReferenced,
        standIns.size(),
        withNoEdge);
  }
}
