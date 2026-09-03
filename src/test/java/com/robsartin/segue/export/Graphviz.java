package com.robsartin.segue.export;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;

/**
 * Whether a real Graphviz {@code dot} is on this machine, and what the tests that need one do when
 * it is not.
 *
 * <p>Two tests render through the real binary — {@link WhatAHoverShowsTest}, because {@code
 * <title>} is written by Graphviz and not by anything this repository controls (ADR 41, issue #81),
 * and {@link ImagemapRecipeTest}, because the guide's imagemap recipe is a claim until it is run
 * (issue #99). Both skipped themselves where {@code dot} was absent, each over its own private copy
 * of the same probe, and that is the hole issue #164 closes: CI installs Graphviz precisely so
 * those two run, and a degraded install made the whole suite report success by never having
 * rendered anything.
 *
 * <p>So the skip is conditional, exactly as {@code segue.requireBrowser} makes the missing-Chrome
 * skip conditional in {@code DeckBehaviourTest}. Unset, a developer without Graphviz still gets a
 * green {@code ./gradlew check} and a visible skip in the report. Set — CI sets it — a missing
 * {@code dot} is an {@link AssertionError} naming the binary and the flag, because a check that
 * cannot run is not a check that passed.
 *
 * <p>The property is read here and never the environment variable: {@code build.gradle.kts} reads
 * {@code SEGUE_REQUIRE_GRAPHVIZ} and forwards it to the test JVM as {@code segue.requireGraphviz},
 * the same route the browser flag takes.
 */
final class Graphviz {

  private Graphviz() {}

  /** True where {@code dot -V} runs and exits zero, which is the only thing either test needs. */
  static boolean installed() {
    try {
      return new ProcessBuilder("dot", "-V").redirectErrorStream(true).start().waitFor() == 0;
    } catch (IOException | InterruptedException e) {
      return false;
    }
  }

  /**
   * Skips the calling class where Graphviz is absent — unless {@code segue.requireGraphviz} is set,
   * in which case a missing {@code dot} fails the build instead.
   *
   * @param whySkipped what this test cannot do without a render, reported as the skip reason
   */
  static void requireOrSkip(String whySkipped) {
    if (Boolean.getBoolean("segue.requireGraphviz") && !installed()) {
      // Not a skip. CI asks for this property precisely so that the checks which run DOT through
      // the real renderer cannot report success by never having run.
      throw new AssertionError(
          "segue.requireGraphviz is set and no Graphviz `dot` was found on the PATH — install"
              + " graphviz, or unset SEGUE_REQUIRE_GRAPHVIZ to let these tests skip");
    }
    assumeTrue(installed(), whySkipped);
  }
}
