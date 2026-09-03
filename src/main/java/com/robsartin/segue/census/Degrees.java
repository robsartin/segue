package com.robsartin.segue.census;

import com.robsartin.segue.domain.EdgeRecord;
import com.robsartin.segue.export.LogProjection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * How many folded edges name each node — one home, because two sections read it.
 *
 * <p>{@link DegreeCensus} reports the quantiles and {@link ClaimCensus} asks which stand-ins ended
 * with no edge, and a second incidence count would be free to disagree with the first about what a
 * degree is.
 *
 * <p><b>Every node is in the map, isolated ones at zero.</b> "At or below the floor" is meaningless
 * against a denominator that has already dropped what nothing reaches.
 *
 * <p><b>A self-loop counts twice</b>, once at each end, which is what "how many edges name this
 * node" means. {@code Equivalences.foldEndpoints} drops the self-loop a merge would create, so one
 * here is a self-loop the log itself holds — and that record's Javadoc says such a claim is left
 * exactly where it is.
 */
final class Degrees {

  private Degrees() {}

  static Map<String, Integer> in(LogProjection projection) {
    Objects.requireNonNull(projection, "projection");
    Map<String, Integer> degrees = new LinkedHashMap<>();
    for (String qid : projection.nodes().keySet()) {
      degrees.put(qid, 0);
    }
    for (EdgeRecord edge : projection.edges()) {
      // computeIfPresent, not merge: an endpoint outside the node set is a dangling edge, which
      // LogProjection has already excluded from edges() and counted separately.
      degrees.computeIfPresent(edge.fromQid(), (qid, degree) -> degree + 1);
      degrees.computeIfPresent(edge.toQid(), (qid, degree) -> degree + 1);
    }
    return Collections.unmodifiableMap(degrees);
  }
}
