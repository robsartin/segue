package com.robsartin.segue.domain;

import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/**
 * ADR 31, as amended by issues #52 and #66: rank paths by specificity first, then by weakest
 * confidence.
 *
 * <p>The adapters return every route they found up to {@code maxHops}; this orders and limits them
 * once, above the port, so both engines get identical results and neither can drift.
 *
 * <p>Two dimensions, and they answer different questions.
 *
 * <ul>
 *   <li><b>Confidence</b> asks whether a route is TRUE. A path is only as trustworthy as its
 *       shakiest hop, which is what {@link PathResult#weakestConfidence()} reports.
 *   <li><b>Specificity</b> asks whether it MEANS anything. A route through a node half the graph
 *       touches — a Walk of Fame star, a hall of fame — is perfectly true and explains nothing, and
 *       so is a route through a body one is elected to rather than works in (issue #66).
 * </ul>
 *
 * <p><b>Specificity leads, and that is the amendment.</b> Every hop through a career-recognition
 * award is a referenced Wikidata statement at 1.00, so under confidence alone the empty routes
 * ranked above the informative ones and no tiebreak could reach them. Confidence still decides
 * everything within one specificity class, which is almost every comparison the ranking makes.
 *
 * <p><b>One exception, and it is the decision this amends.</b> Specificity never promotes a path
 * that rests on a model guess above one a real source stands behind (ADR 23). ADR 31 exists so a
 * plausible wrong answer cannot be the first thing a conversation sees, and inverting that silently
 * would be a regression dressed as an improvement.
 *
 * <p>Static policy, not a value type: it holds no state and only shapes results the port returns.
 */
public final class PathRanking {

  /**
   * Bounds the returned list so a dense neighbourhood cannot produce an unbounded result. Personal
   * scale; raise it deliberately if an explanation ever needs more than this many candidate routes.
   *
   * <p>Hitting it is reported rather than hidden (issue #65): {@code SegueService.findPaths}
   * compares the ranked size against the raw one and returns {@code partial} when they differ, so
   * "50 route(s)" can no longer stand for two hundred. The {@code find_paths} tool description
   * states the number too — as prose, because an annotation needs a constant — and {@code
   * ToolSurfaceTest} asserts the two agree, so raising this fails the build rather than leaving a
   * stale promise in the schema.
   */
  public static final int MAX_PATHS = 50;

  /**
   * The in-graph degree at which a {@code CONCEPT} intermediate stops being a fact about the two
   * entities either side of it and becomes a hub.
   *
   * <p>Measured, not chosen. On a real 25,815-node graph the CONCEPT population is 9,495 nodes of
   * degree 1 and 1,329 of degree 2-4; only fifteen reach ten, which is 0.06% of them. Every one of
   * those fifteen is a career-recognition award — a Walk of Fame star at 76, a hall of fame at 64,
   * a lifetime achievement award at 39 — save a fictional character and one award category. The
   * number names the tail of a distribution rather than expressing a preference.
   *
   * <p>It is an absolute degree on a personal-scale graph, so it will drift as the graph grows.
   * Re-measure the distribution before changing it; a threshold nobody re-measures is a blocklist
   * with extra steps.
   */
  public static final int HUB_DEGREE = 10;

  /** No degrees known, so nothing is a hub and the order is ADR 31's original. */
  private static final ToIntFunction<String> NO_DEGREES = qid -> 0;

  /** No class vocabulary known, so no node states itself to be an institution. */
  private static final Predicate<String> NO_INSTITUTIONS = classQid -> false;

  private PathRanking() {}

  /**
   * Order most-trustworthy-first, then cap, with no view of the graph's shape.
   *
   * <p>Kept so callers that cannot supply degrees — and the contract tests, which compare two
   * engines rather than judge a route — are not forced to care. Equivalent to ADR 31 as first
   * written.
   */
  public static List<PathResult> rank(List<PathResult> paths) {
    return rank(paths, NO_DEGREES, NO_INSTITUTIONS);
  }

  /**
   * Order most-explanatory-first, then cap. The input is left untouched.
   *
   * @param degreeByQid how many edges the graph holds against one entity. A {@code
   *     java.util.function} over a qid rather than the store itself: {@code domain} carries no
   *     third-party dependencies and no graph access (ADR 18, enforced by ArchUnit), and this is
   *     the shape that lets it use the graph's shape without learning what a graph is.
   * @param recognitionInstitutionClass whether membership of a body in this Wikidata class is
   *     recognition rather than collaboration - an academy, a learned society, a guild (issue #66).
   *     Passed in for the same reason and in the same shape as the degree: which classes those are
   *     is a source's vocabulary, and {@code domain} does not hold one. There is deliberately no
   *     degrees-only overload; a caller that judged specificity by half the rule would silently
   *     rank an academy above the film two people actually made.
   */
  public static List<PathResult> rank(
      List<PathResult> paths,
      ToIntFunction<String> degreeByQid,
      Predicate<String> recognitionInstitutionClass) {
    return paths.stream()
        .sorted(mostExplanatoryFirst(degreeByQid, recognitionInstitutionClass))
        .limit(MAX_PATHS)
        .toList();
  }

  private static Comparator<PathResult> mostExplanatoryFirst(
      ToIntFunction<String> degreeByQid, Predicate<String> recognitionInstitutionClass) {
    return Comparator.comparing(PathRanking::restsOnAModelGuess)
        .thenComparingInt(path -> hubIntermediates(path, degreeByQid, recognitionInstitutionClass))
        .thenComparing(Comparator.comparingDouble(PathResult::weakestConfidence).reversed())
        .thenComparingInt(PathResult::length);
  }

  /**
   * How many of the nodes this route passes THROUGH are hubs.
   *
   * <p>Endpoints are excluded: "what connects me to the Rock and Roll Hall of Fame" is a fair
   * question, and the answer must not be demoted for ending where it was asked to end.
   *
   * <p>Kind is half the test, and the half that makes it work. Raw degree is the wrong signal on
   * its own, because the busiest nodes in a real graph are the expanded seeds themselves — a band
   * at 200 edges is a legitimate connector, and demoting routes through it would gut the feature.
   * Every hub measured was a {@code CONCEPT}; every busy legitimate node was a {@code PERSON}, a
   * {@code GROUP} or a {@code WORK}.
   *
   * <p><b>Two ways to be a hub, and the second needs no degree at all (issue #66).</b> A node that
   * SAYS what it is has already answered the question degree was standing in for. An academy or a
   * guild is a body one is elected to, so a route through it reports recognition rather than
   * collaboration however few members the graph happens to hold. A degree threshold could not have
   * done this job at all: measured, the guilds sit at 6 to 11 edges and the bands that must keep
   * working sit at 11 to 19, interleaved rather than separated. Which classes count is the source's
   * vocabulary, not the domain's, so it arrives as a predicate over a class qid the way the degree
   * arrives as a function over a node's.
   */
  private static int hubIntermediates(
      PathResult path,
      ToIntFunction<String> degreeByQid,
      Predicate<String> recognitionInstitutionClass) {
    List<Hop> hops = path.hops();
    int hubs = 0;
    for (int i = 0; i + 1 < hops.size(); i++) {
      if (isHub(hops.get(i).to(), degreeByQid, recognitionInstitutionClass)) {
        hubs++;
      }
    }
    return hubs;
  }

  /**
   * Whether this node, standing between two others, is a hub rather than a fact about them.
   *
   * <p>Public because a second caller needs the same judgement and must not own a second copy of
   * it. The recommender (ADR 45) asks it of every candidate intermediate <em>before</em> a route
   * exists, and EXCLUDES the ones that answer yes rather than demoting them: ranking has something
   * left to say about a hub route, since "what connects me to the Rock and Roll Hall of Fame" is a
   * question with an answer, while a recommendation derived from one is nothing but the observation
   * that both parties are famous. Two readings of one rule, one implementation - if this ever
   * changes, both change together, which is the whole reason it is exposed instead of copied.
   *
   * <p>The two ways to be a hub are described on {@link #hubIntermediates}: a busy {@code CONCEPT}
   * (issue #52), or a node stating a class that makes it a body one is ELECTED to (issue #66).
   */
  public static boolean isHub(
      NodeRecord node,
      ToIntFunction<String> degreeByQid,
      Predicate<String> recognitionInstitutionClass) {
    return isBusyConcept(node, degreeByQid)
        || isRecognitionInstitution(node, recognitionInstitutionClass);
  }

  private static boolean isBusyConcept(NodeRecord node, ToIntFunction<String> degreeByQid) {
    return node.kind() == NodeKind.CONCEPT && degreeByQid.applyAsInt(node.qid()) >= HUB_DEGREE;
  }

  /**
   * Whether the classes this node states make it a body one is ELECTED to.
   *
   * <p>Any stated class is enough, and position means nothing: a real institution wears several,
   * and the recognition class is not reliably at the front — the American Academy of Arts and
   * Sciences states learned society, academic publisher, nonprofit organization, in that order. The
   * kind derivation reaches the same conclusion about order by a different route (issue #87): it
   * reads every recognised class and ranks the KINDS, where this reads every class and asks one
   * yes-or-no question. Neither is allowed to care which class came first.
   *
   * <p>No kind test, deliberately. "High-degree {@code CONCEPT}" means "we could not place this and
   * half the graph touches it"; a stated class means something on its own, so a learned society the
   * mapping never placed is judged the same as one it did.
   */
  private static boolean isRecognitionInstitution(
      NodeRecord node, Predicate<String> recognitionInstitutionClass) {
    return node.instanceOf().stream().anyMatch(recognitionInstitutionClass);
  }

  /** ADR 23's quarantine line, as a path-level question: does any hop rest on a model guess? */
  private static boolean restsOnAModelGuess(PathResult path) {
    return path.hops().stream().anyMatch(hop -> hop.edge().isUncorroboratedHypothesis());
  }
}
