package com.robsartin.segue.recommend;

import com.robsartin.segue.domain.PathRanking;
import com.robsartin.segue.domain.PathResult;
import com.robsartin.segue.domain.Recommendation;
import com.robsartin.segue.domain.SharedIntermediate;
import com.robsartin.segue.port.GraphStore;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/**
 * The receipts. Why this candidate, said as routes a person can check (ADR 45).
 *
 * <p><b>This is the half of the feature that makes it segue's rather than anybody's.</b> A score
 * ranks; only a route explains, and the whole premise of the project is that "you like this
 * because" is citable. So the explanation is not derived from the scoring evidence, which knows the
 * arithmetic but has thrown away the edges: it is fetched from the real traversal, ranked by the
 * shared {@link PathRanking}, and rendered by {@code PathResult} — the same three things {@code
 * find_paths} does, in the same order. There is one notion of a good route in this project, and a
 * recommendation cannot quietly acquire a second.
 *
 * <p><b>Only for the candidates somebody will read.</b> A sweep of the real graph produces around a
 * thousand candidates; building a ranked explanation for each would be a thousand traversals thrown
 * away. This runs over the ranked and bounded list, at a few routes each.
 */
public final class Routes {

  /**
   * The sweep looks two hops out, so an explanation is at most two hops long.
   *
   * <p>The traversal is still allowed to return a ONE-hop route and to rank it first, and it
   * should: if you already have a direct edge to the candidate, "it is cited by something you know"
   * is a better answer than the two-hop route the scoring happened to count.
   */
  public static final int MAX_HOPS = 2;

  private final GraphStore graph;
  private final Predicate<String> recognitionInstitutionClass;
  private final Map<String, Integer> degrees = new HashMap<>();

  public Routes(GraphStore graph, Predicate<String> recognitionInstitutionClass) {
    this.graph = Objects.requireNonNull(graph, "graph");
    this.recognitionInstitutionClass =
        Objects.requireNonNull(recognitionInstitutionClass, "recognitionInstitutionClass");
  }

  /**
   * The best route from each of the known entities that contributed most to this candidate's score,
   * strongest contributor first.
   *
   * <p>By contribution rather than by name, because the question a reader has is "which of my
   * things is this coming from" and the answer they want is the biggest reason first. Ties break on
   * the qid, so the file is stable between runs over an unchanged graph.
   */
  public List<PathResult> bestFor(Recommendation candidate, int howMany) {
    Objects.requireNonNull(candidate, "candidate");
    ToIntFunction<String> lookup = this::degree;
    List<PathResult> routes = new ArrayList<>();
    for (String seed : strongestReachers(candidate)) {
      if (routes.size() == howMany) {
        break;
      }
      List<PathResult> ranked =
          PathRanking.rank(
              graph.paths(seed, candidate.entity().qid(), MAX_HOPS),
              lookup,
              recognitionInstitutionClass);
      if (!ranked.isEmpty()) {
        routes.add(ranked.get(0));
      }
    }
    return List.copyOf(routes);
  }

  private List<String> strongestReachers(Recommendation candidate) {
    Map<String, Double> byReacher = new LinkedHashMap<>();
    for (SharedIntermediate connection : candidate.shared()) {
      byReacher.merge(connection.seedQid(), connection.weight(), Double::sum);
    }
    return byReacher.entrySet().stream()
        .sorted(
            Map.Entry.<String, Double>comparingByValue()
                .reversed()
                .thenComparing(Map.Entry::getKey))
        .map(Map.Entry::getKey)
        .toList();
  }

  private int degree(String qid) {
    return degrees.computeIfAbsent(qid, key -> graph.edges(key).size());
  }
}
