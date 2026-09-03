package com.robsartin.segue.own;

import com.robsartin.segue.domain.Equivalences;
import com.robsartin.segue.domain.LoggedAssertion;
import java.util.List;
import java.util.Map;

/**
 * Reaches {@link OwnRun#labelsInTheProjection}, which is package-private for this reason, from the
 * stand-in guard in another test package (issue #220).
 */
public final class ProjectionLabelsProbe {

  private ProjectionLabelsProbe() {}

  /** {@code OwnRun.labelsInTheProjection}, the third home of the stand-in rule. */
  public static Map<String, String> labelsInTheProjection(
      List<LoggedAssertion> logged, Equivalences merges) {
    return OwnRun.labelsInTheProjection(logged, merges);
  }
}
