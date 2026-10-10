package com.robsartin.segue.domain;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * Every entity's {@link Interest}, from the ratings map and the signal table (#351).
 *
 * <p>Pure, and the one place the two are joined, so a caller never re-implements "which wins". An
 * entity appears here only if something is known about it: unrated and unobserved entities have no
 * entry, and {@code Recommendations.regardFor} reads that absence as neutral.
 */
public final class Interests {

  private Interests() {}

  /**
   * @param ratings qid to rating, already resolved through the owner's merges
   * @param signals every stored observation
   * @param canonical what the merges say an observation's qid is today; the ratings are resolved
   *     already and the signals are not, for the reason {@code RecommendCli} resolves the ratings
   *     before anything downstream sees them
   */
  public static Map<String, Interest> byQid(
      Map<String, Integer> ratings, List<Signal> signals, UnaryOperator<String> canonical) {
    Map<String, List<Signal>> grouped = new HashMap<>();
    for (Signal signal : signals) {
      grouped.computeIfAbsent(canonical.apply(signal.qid()), key -> new ArrayList<>()).add(signal);
    }
    Set<String> qids = new HashSet<>(ratings.keySet());
    qids.addAll(grouped.keySet());
    Map<String, Interest> byQid = new HashMap<>();
    for (String qid : qids) {
      Interest interest = Interest.of(ratings.get(qid), grouped.getOrDefault(qid, List.of()));
      if (interest.evidence() != Evidence.NONE) {
        byQid.put(qid, interest);
      }
    }
    return Map.copyOf(byQid);
  }
}
