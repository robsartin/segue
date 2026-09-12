package com.robsartin.segue.census;

import com.robsartin.segue.domain.EdgeRecord;
import com.robsartin.segue.export.LogProjection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Who is next to whom, in the fold. */
final class Neighbours {

  private Neighbours() {}

  /**
   * Who each node shares a folded edge with — one home, because the known-list walk is the only
   * thing that asks and it asks it once per population.
   *
   * <p><b>Every node is a key, isolated ones with an empty set.</b> A node nothing reaches is
   * exactly the finding the walk exists to report, and a map that omitted it would answer the
   * question by losing it. {@code Degrees} seeds itself the same way for the same reason.
   *
   * <p><b>Undirected.</b> "Is anything I know within two hops of this" is a question about the
   * graph, not about which end of a relationship Wikidata states it on.
   *
   * <p>{@code LogProjection.edges()} has already dropped the dangling and the withdrawn and applied
   * retraction and merge (ADR 44), so nothing here filters.
   */
  static Map<String, Set<String>> in(LogProjection projection) {
    Objects.requireNonNull(projection, "projection");
    Map<String, Set<String>> adjacency = new LinkedHashMap<>();
    for (String qid : projection.nodes().keySet()) {
      adjacency.put(qid, new LinkedHashSet<>());
    }
    for (EdgeRecord edge : projection.edges()) {
      link(adjacency, edge.fromQid(), edge.toQid());
      link(adjacency, edge.toQid(), edge.fromQid());
    }
    Map<String, Set<String>> copy = new LinkedHashMap<>();
    adjacency.forEach((qid, of) -> copy.put(qid, Collections.unmodifiableSet(of)));
    return Collections.unmodifiableMap(copy);
  }

  /** Both ends have to be nodes: {@code Degrees} uses {@code computeIfPresent} for this. */
  private static void link(Map<String, Set<String>> adjacency, String from, String to) {
    Set<String> of = adjacency.get(from);
    if (of != null && adjacency.containsKey(to)) {
      of.add(to);
    }
  }

  /**
   * Whether any member of {@code population} other than {@code from} sits within {@code hops}.
   *
   * <p>Breadth-first and short-circuiting: the question is whether anything is there, not how many
   * or how far, so the walk stops at the first hit and at the first exhausted frontier.
   *
   * <p><b>{@code from} is marked seen before the first hop</b>, so an entity is never its own known
   * neighbour — including where the log holds a self-loop, which {@code Degrees} counts twice and
   * this deliberately does not count at all.
   */
  static boolean reaches(
      Map<String, Set<String>> adjacency, String from, Set<String> population, int hops) {
    Objects.requireNonNull(adjacency, "adjacency");
    Objects.requireNonNull(from, "from");
    Objects.requireNonNull(population, "population");
    Set<String> seen = new LinkedHashSet<>();
    seen.add(from);
    Set<String> frontier = Set.of(from);
    for (int hop = 0; hop < hops; hop++) {
      Set<String> next = new LinkedHashSet<>();
      for (String at : frontier) {
        for (String neighbour : adjacency.getOrDefault(at, Set.of())) {
          if (!seen.add(neighbour)) {
            continue;
          }
          if (population.contains(neighbour)) {
            return true;
          }
          next.add(neighbour);
        }
      }
      if (next.isEmpty()) {
        return false;
      }
      frontier = next;
    }
    return false;
  }
}
