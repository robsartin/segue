package com.robsartin.segue.ratings;

import com.robsartin.segue.port.AssertionLog;
import java.util.Map;
import java.util.Set;

/**
 * Reaches {@code Labels.forQids}, which is package-private in a package-private class, from the
 * stand-in guard in another test package (issue #220).
 *
 * <p>A probe rather than a widening: {@code Labels} needs no production change to be reachable from
 * its own package, and this class is the whole of the reach.
 */
public final class LabelsProbe {

  private LabelsProbe() {}

  /** {@code ratings/Labels.forQids}, the fourth home of the stand-in rule (ADR 59's residual). */
  public static Map<String, String> forQids(AssertionLog log, Set<String> qids) {
    return Labels.forQids(log, qids);
  }
}
