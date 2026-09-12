package com.robsartin.segue.expand;

import com.robsartin.segue.expansion.ExpansionOutcome;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * What a run over every promotion did, summed. Aggregate on purpose — see the class javadoc on
 * {@code ExpansionReport} for the reason nothing in this signature can carry an identifier (ADR 51,
 * ADR 63, issue #284): every field is an {@code int} or a map keyed by {@code SourceAdapter#id()}
 * or {@link ExpansionOutcome.Reason}, neither of which is an entity id.
 *
 * @param considered every entity the run was handed — the promotions, or the known-list population
 *     when {@code --known} was given (#313)
 * @param expanded entities {@link ExpansionOutcome.Expanded}, whether or not they added anything
 * @param addedNothing of those, the ones where {@code nodesAdded == 0 && edgesAdded == 0}
 * @param failed entities whose adapter call threw, counted and logged without a qid — see {@link
 *     ExpandRun#run}
 * @param nodesAdded summed across every expanded entity
 * @param edgesAdded edge assertions recorded, summed across every expanded entity — {@link
 *     ExpansionOutcome.Expanded#edgesAdded()} is the authority on what one of them counts: one
 *     increment per assertion appended, so an edge the graph already held is counted again (#293)
 * @param skippedNeighbors summed across every expanded entity
 * @param refusedEndpoints summed sizes of {@link ExpansionOutcome.Expanded#refusedEndpoints()}
 *     across every expanded entity
 * @param boundCut entities whose shared budget cut the concatenation — a count of entities, not of
 *     sources, because the cut is attributable to none of them
 * @param edgesBySource summed {@link ExpansionOutcome.Expanded#edgesBySource()} across every
 *     expanded entity
 * @param unavailableBySource one increment per source id named in {@link
 *     ExpansionOutcome.Expanded#unavailableSources()}, across every expanded entity
 * @param truncatedBySource one increment per source id named in {@link
 *     ExpansionOutcome.Expanded#truncatingSources()}, across every expanded entity
 * @param refusalsByReason one increment per {@link ExpansionOutcome.Refused#reason()}
 */
public record ExpansionTally(
    int considered,
    int expanded,
    int addedNothing,
    int failed,
    int nodesAdded,
    int edgesAdded,
    int skippedNeighbors,
    int refusedEndpoints,
    int boundCut,
    Map<String, Integer> edgesBySource,
    Map<String, Integer> unavailableBySource,
    Map<String, Integer> truncatedBySource,
    Map<ExpansionOutcome.Reason, Integer> refusalsByReason) {

  public ExpansionTally {
    edgesBySource =
        Collections.unmodifiableMap(
            new LinkedHashMap<>(Objects.requireNonNull(edgesBySource, "edgesBySource")));
    unavailableBySource =
        Collections.unmodifiableMap(
            new LinkedHashMap<>(
                Objects.requireNonNull(unavailableBySource, "unavailableBySource")));
    truncatedBySource =
        Collections.unmodifiableMap(
            new LinkedHashMap<>(Objects.requireNonNull(truncatedBySource, "truncatedBySource")));
    refusalsByReason =
        Collections.unmodifiableMap(
            new LinkedHashMap<>(Objects.requireNonNull(refusalsByReason, "refusalsByReason")));
  }
}
