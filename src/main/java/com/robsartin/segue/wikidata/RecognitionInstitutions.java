package com.robsartin.segue.wikidata;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The Wikidata classes whose members were <em>recognised</em> rather than <em>collaborating</em>.
 *
 * <p>ADR 31's specificity rule (issue #52) demotes a route through a busy {@code CONCEPT}, which
 * catches career-recognition awards because an award node is a {@code CONCEPT} (ADR 38). It cannot
 * see an academy: the American Academy of Arts and Sciences connects 33 of 815 seeds, every one of
 * them by {@code MEMBER_OF}, and it is a {@code GROUP}. "Both were elected to the same academy"
 * explains as little as "both have a Walk of Fame star", and issue #66 is that gap.
 *
 * <p><b>Why a class table and not a degree threshold.</b> Most busy {@code GROUP}s are bands, and
 * they are exactly the connectors the feature runs on, so the {@code CONCEPT} rule cannot simply be
 * widened. Measured over the 80 groups shared by five or more seeds, degree does not separate the
 * two populations at all — it interleaves them:
 *
 * <pre>
 *   Writers Guild of America West   11 edges     Mötley Crüe        11 edges
 *   Writers Guild of America, East  10           The Clash          15
 *   American Academy of Arts &amp; Letters 8         Guns N' Roses      19
 *   SAG-AFTRA                        6           Van Halen          16
 * </pre>
 *
 * <p>Any threshold catching SAG-AFTRA or the Writers Guild also catches all three bands issue #66
 * names as the ones that must keep working. The class does separate them, cleanly and with no
 * overlap, and it is a property of the node rather than of the graph's shape — so it does not drift
 * as the graph grows, which the degree threshold beside it explicitly does.
 *
 * <p><b>The broad organization classes are deliberately absent.</b> Every institution measured also
 * states {@code Q163740} (nonprofit organization) or {@code Q43229} (organization), so a table
 * built from what they have in common would have been larger and wrong: ABBA states {@code Q43229}
 * at 498 edges and the Vienna Philharmonic states {@code Q163740}. Only the classes that say what
 * the body IS are listed.
 *
 * <p>It lives here beside {@link KindMapper} because deciding what a Wikidata class MEANS is this
 * adapter's job (ADR 42). {@code PathRanking} takes it as a {@code Predicate} over a class qid, the
 * way it takes the degree lookup, so {@code domain} stays free of any source's vocabulary.
 */
public final class RecognitionInstitutions {

  private static final Map<String, String> BY_QID = new LinkedHashMap<>();

  static {
    // Every entry was measured on the real graph and confirmed against Wikidata by label AND
    // description. The counts are seeds connected, out of 815.
    put("Q955824", "learned society"); // American Academy of Arts and Sciences, 33
    put("Q414147", "academy of sciences"); // American Academy of Arts and Letters, 8
    put("Q178790", "labor union"); // Writers Guild of America West, 11; SAG-AFTRA, 6
  }

  private RecognitionInstitutions() {}

  private static void put(String classQid, String name) {
    String prior = BY_QID.put(classQid, name);
    if (prior != null) {
      // Two registrations for one class is a table bug: one of them would silently vanish.
      throw new IllegalStateException(
          "two names claim " + classQid + ": " + prior + " and " + name);
    }
  }

  /**
   * Whether membership of a body in this class is recognition rather than collaboration.
   *
   * <p>Deliberately answered per class rather than per node: a node states several classes and the
   * caller decides how to combine them, which is the same shape {@link
   * KindMapper#fromInstanceOf(java.util.List)} works in.
   */
  public static boolean isRecognitionInstitution(String classQid) {
    return BY_QID.containsKey(classQid);
  }

  /** The table itself, for the test that checks it is well formed. */
  static Map<String, String> all() {
    return Map.copyOf(BY_QID);
  }
}
