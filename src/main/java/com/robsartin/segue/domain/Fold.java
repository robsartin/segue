package com.robsartin.segue.domain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * One log's fold, computed once and carried - the value the boot builds a single time and hands to
 * every reader that would otherwise recompute it (issue #238).
 *
 * <p><b>This type decides nothing.</b> {@link Equivalences} and {@link Retractions} own every fold
 * rule there is, and {@link #of} does no more than call each of them in the order they already
 * require of each other. There is deliberately no second home here for a rule to drift into - a
 * {@code Fold} is a bag of answers, not a place that could give a different one.
 *
 * <p><b>{@code rederive} is required rather than defaulted</b>, on {@code
 * Equivalences.localsOfMerges}'s own stated reason: an overload that quietly restored {@link
 * UnaryOperator#identity()} is how a third fold would arrive carrying the kind lag ADR 42 exists to
 * close, with nothing at the call site saying so.
 *
 * <p><b>The saving is the emptied set, computed once and threaded.</b> {@code
 * Equivalences.retractedStandIns} is a fixed point over the whole log, and {@link #of} computes it
 * exactly once and hands it on - to {@link Equivalences#in(List, Set)} and {@link
 * Equivalences#folding(Equivalences, Set)} - rather than letting {@link Equivalences#folding(List)}
 * and {@link Equivalences#standIns(List, UnaryOperator)} each pay for it again from a bare {@code
 * List<LoggedAssertion>}. Every one of the four accessors below answers exactly what the
 * corresponding log-taking rule answers on this log; {@code FoldTest} pins that equivalence.
 *
 * <p>The architecture rule that keeps the boot replaying through exactly this type, rather than
 * through the log-taking statics it wraps, is {@code ArchitectureTest.theBootFoldsOnce}. It fences
 * the whole {@code ingest} package rather than {@code GraphProjector} alone - a package-private
 * helper that folds and is called from the replay is a second boot fold a one-class rule cannot see
 * - and exempts {@code IngestService}, whose live path has no boot fold to reuse. The measured
 * saving is a dated figure that belongs in its own ADR rather than restated here: ADR 64, {@code
 * docs/adr/0064-fold-the-log-once-per-boot.md}, holds the before and after taken 2026-09-04, what
 * they do and do not cover, and the alternatives this decision rejected.
 *
 * @param retractions {@link Retractions#in}'s own answer for this log
 * @param equivalences {@link Equivalences#folding(List)}'s own answer for this log, built here from
 *     the same merges and emptied set the other three accessors share rather than recomputed
 * @param standIns {@link Equivalences#standIns(List, UnaryOperator)}'s own answer for this log,
 *     under this fold's {@code rederive} - in that method's own log order, which the copy this
 *     record takes preserves
 * @param nodesHeld {@link Equivalences#nodesTheFoldHolds(List)}'s own answer for this log
 */
public record Fold(
    Retractions retractions,
    Equivalences equivalences,
    Map<String, NodeRecord> standIns,
    Set<String> nodesHeld) {

  public Fold {
    Objects.requireNonNull(retractions, "retractions");
    Objects.requireNonNull(equivalences, "equivalences");
    // LinkedHashMap and LinkedHashSet rather than Map.copyOf and Set.copyOf, and that is the whole
    // of the difference: the immutable factories randomise iteration order per JVM, so a defensive
    // copy taken with them would hand GraphProjector its stand-ins in a different order on every
    // boot and make the "in log order" this record promises below false. Equivalences copies its
    // own fields exactly this way, for exactly this reason.
    standIns =
        Collections.unmodifiableMap(
            new LinkedHashMap<>(Objects.requireNonNull(standIns, "standIns")));
    nodesHeld =
        Collections.unmodifiableSet(
            new LinkedHashSet<>(Objects.requireNonNull(nodesHeld, "nodesHeld")));
  }

  /**
   * Folds {@code log} once: the merges, the emptied canonical ids, the stand-ins and the held node
   * set, plus the retractions - each read off {@code log} exactly once and shared between the
   * accessors that need it, rather than each of the four log-taking rules re-walking the log on its
   * own.
   *
   * @param log the assertions to fold, in {@code AssertionLog.readAll} order
   * @param rederive how this fold derives a node claim's kind - {@code KindMapper::rederive} in
   *     production, handed in because {@code domain} may not name it
   */
  public static Fold of(List<LoggedAssertion> log, UnaryOperator<NodeAssertion> rederive) {
    Objects.requireNonNull(log, "log");
    Objects.requireNonNull(rederive, "rederive");
    Set<String> emptied = Equivalences.retractedStandIns(log);
    Equivalences merges = Equivalences.in(log, emptied);
    Map<String, NodeRecord> standIns = Equivalences.standIns(log, rederive, merges);
    return new Fold(
        Retractions.in(log),
        Equivalences.folding(merges, emptied),
        standIns,
        Equivalences.nodesTheFoldHolds(log, standIns.keySet()));
  }
}
