package com.robsartin.segue.recommend;

import com.robsartin.segue.domain.Recommendation;
import java.util.List;
import java.util.Objects;

/**
 * What one pass over the graph found, and what it declined to look at (ADR 45).
 *
 * <p>The three counts are not decoration. A recommender is a filter with several stages, and every
 * one of them can be wrong in a way that produces a plausible list: a known-list that resolves to
 * nothing produces recommendations from nothing, and a hub rule that excluded everything or nothing
 * would look identical in the ranking. Reporting them is the same discipline the exporter's size
 * line and {@code find_paths}'s {@code partial} flag follow — a bound or a filter that can bite is
 * reported by the result that hit it.
 *
 * @param candidates every entity that survived the filters, unranked and unbounded — {@code
 *     Recommendations.rank} is what orders and caps them
 * @param knownFound how many of the supplied known entities the graph actually holds
 * @param knownMissing how many it does not. A list resolved against a different graph, or a
 *     retracted entity (ADR 44), lands here rather than failing the run
 * @param hubIntermediatesExcluded how many distinct intermediates were refused as hubs (issues #52
 *     and #66). Distinct nodes, not routes: "the hall of fame was excluded once" is the fact worth
 *     reporting, and counting routes through it would report the popularity of the hub instead
 * @param heldOutByFloor how many distinct entities the degree floor discarded — entities that
 *     passed every other candidate test and failed only on how many edges they carry (issue #135).
 *     Until this field existed the floor was the one filter here whose bite was reported by
 *     nothing, while being the filter the tool's own default most often re-decides
 * @param heldOutAtDegreeOne how many of those carry exactly one edge: what expansion has discovered
 *     and nothing has reached a second time (issue #134). Counted apart from the rest because a run
 *     that says nothing about it is a run in which the graph's growth is invisible
 */
public record Sweep(
    List<Recommendation> candidates,
    int knownFound,
    int knownMissing,
    int hubIntermediatesExcluded,
    int heldOutByFloor,
    int heldOutAtDegreeOne) {

  public Sweep {
    candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates"));
  }
}
