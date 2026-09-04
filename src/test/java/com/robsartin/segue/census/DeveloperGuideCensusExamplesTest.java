package com.robsartin.segue.census;

import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.segue.arch.GuideExamples;
import com.robsartin.segue.arch.GuideExamples.Example;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * "Looking at the shape of your graph" shows commands the owner is meant to paste, and this runs
 * every one of them through {@link CensusCli#parse} — the third tool that requires {@code --db},
 * after the two claim tools (#183, #227).
 *
 * <p><b>Why {@code parse} and not the tool.</b> Running an example end to end would need a
 * database, and the only one on this machine is the owner's. What a runbook has to get right is the
 * command line, and {@code parse} is what enforces it before any file is opened.
 *
 * <p>In {@code census} rather than beside the document tests in {@code arch}, because {@link
 * CensusCli#parse} is package-private, exactly as both of its siblings are.
 */
class DeveloperGuideCensusExamplesTest {

  private static final GuideExamples RUNBOOK = GuideExamples.of("graphCensus");

  @Test
  @DisplayName("the guide shows at least one graphCensus example")
  void shouldShowAnExampleWhenTheGuideDocumentsTheCensus() {
    assertThat(RUNBOOK.examples())
        .as(
            "docs/developer-guide.md, 'Looking at the shape of your graph' — at least one"
                + " ./gradlew graphCensus --args=\"…\" line. Without this the other checks pass"
                + " vacuously on a chapter that shows nothing")
        .isNotEmpty();
  }

  @Test
  @DisplayName("no graphCensus example writes a tilde where $HOME belongs")
  void shouldWriteHomeRatherThanATildeWhenACensusExampleNamesADatabase() {
    assertThat(RUNBOOK.withATilde())
        .as(
            "docs/developer-guide.md — a tilde does not expand inside the double quotes of"
                + " --args=\"…\", so the example arrives at the tool as a literal ~ and dies with"
                + " \"no segue database at ~/.segue/segue.db\". CensusCli.parse cannot see this,"
                + " because a tilde is a valid path character")
        .isEmpty();
  }

  @Test
  @DisplayName("every line naming graphCensus is read as a command, or is prose with no --args")
  void shouldNameTheLineWhenACensusExampleCannotBeRead() {
    assertThat(RUNBOOK.unreadableExamples())
        .as(
            "docs/developer-guide.md — a line naming graphCensus that this test cannot read is a"
                + " line nothing checks, and skipping it silently is the hole this assertion exists"
                + " to close. A line with no --args at all is prose and is allowed")
        .isEmpty();
  }

  @Test
  @DisplayName("every graphCensus example parses through the tool's own parser")
  void shouldParseEveryExampleWhenTheGuideShowsACensusCommand() {
    List<String> refused = new ArrayList<>();
    for (Example example : RUNBOOK.examples()) {
      try {
        CensusCli.parse(
            example.arguments().toArray(String[]::new), null, GuideExamples.INVENTED_HOME);
      } catch (RuntimeException refusal) {
        refused.add(
            "line " + example.line() + ": " + example.text() + "\n    " + refusal.getMessage());
      }
    }

    assertThat(refused)
        .as(
            "docs/developer-guide.md — every graphCensus example is run through CensusCli.parse,"
                + " the boundary that decides whether a line is correct to type. --db is enforced"
                + " there, so an example that forgot it fails here")
        .isEmpty();
  }
}
