package com.robsartin.segue.domain;

import java.util.Comparator;
import java.util.List;

/**
 * ADR 31: rank paths by weakest confidence, not by hop count.
 *
 * <p>The adapters return every route they found up to {@code maxHops}; this orders and limits them
 * once, above the port, so both engines get identical results and neither can drift. A path is only
 * as trustworthy as its shakiest hop, so the order is {@link PathResult#weakestConfidence()}
 * descending, with {@link PathResult#length()} ascending as the tiebreak - a fully sourced long
 * route outranks a short guess, which is the whole point of the payoff feature.
 *
 * <p>Static policy, not a value type: it holds no state and only shapes results the port returns.
 */
public final class PathRanking {

  /**
   * Bounds the returned list so a dense neighbourhood cannot produce an unbounded result. Personal
   * scale; raise it deliberately if an explanation ever needs more than this many candidate routes.
   */
  public static final int MAX_PATHS = 50;

  private static final Comparator<PathResult> MOST_TRUSTWORTHY_FIRST =
      Comparator.comparingDouble(PathResult::weakestConfidence)
          .reversed()
          .thenComparingInt(PathResult::length);

  private PathRanking() {}

  /** Order most-trustworthy first, then cap. The input is left untouched. */
  public static List<PathResult> rank(List<PathResult> paths) {
    return paths.stream().sorted(MOST_TRUSTWORTHY_FIRST).limit(MAX_PATHS).toList();
  }
}
