package com.robsartin.segue.evaluate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.robsartin.segue.arch.GuideExamples;
import com.robsartin.segue.arch.GuideExamples.Example;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * "Calibrating the recommender" shows commands the owner is meant to paste, and this runs every one
 * of them through {@link EvaluateCli#parse} — the fourth tool that requires {@code --db}, after the
 * two claim tools and the census.
 *
 * <p><b>Why {@code parse} and not the tool.</b> Running an example end to end would need a
 * database, and the only one on this machine is the owner's. What a runbook has to get right is the
 * command line, and {@code parse} is what enforces it before any file is opened.
 *
 * <p>In {@code evaluate} rather than beside the document tests in {@code arch}, because {@link
 * EvaluateCli#parse} is package-private, exactly as its siblings' are.
 */
class DeveloperGuideEvaluateExamplesTest {

  private static final GuideExamples RUNBOOK = GuideExamples.of("evaluate");

  @Test
  @DisplayName("the guide shows at least one evaluate example")
  void shouldShowAnExampleWhenTheGuideDocumentsTheHarness() {
    assertThat(RUNBOOK.examples())
        .as(
            "docs/developer-guide.md, 'Calibrating the recommender' — at least one ./gradlew"
                + " evaluate --args=\"…\" line. Without this the other checks pass vacuously on a"
                + " chapter that shows nothing")
        .isNotEmpty();
  }

  @Test
  @DisplayName("no evaluate example writes a tilde where $HOME belongs")
  void shouldWriteHomeRatherThanATildeWhenAnExampleNamesADatabase() {
    assertThat(RUNBOOK.withATilde())
        .as(
            "docs/developer-guide.md — a tilde does not expand inside the double quotes of"
                + " --args=\"…\", so the example arrives at the tool as a literal ~. EvaluateCli"
                + " cannot see this, because a tilde is a valid path character")
        .isEmpty();
  }

  @Test
  @DisplayName("every line naming evaluate is read as a command, or is prose with no --args")
  void shouldNameTheLineWhenAnExampleCannotBeRead() {
    assertThat(RUNBOOK.unreadableExamples())
        .as(
            "docs/developer-guide.md — a line naming evaluate that this test cannot read is a line"
                + " nothing checks, and skipping it silently is the hole this assertion exists to"
                + " close. A line with no --args at all is prose and is allowed")
        .isEmpty();
  }

  @Test
  @DisplayName("every evaluate example the guide shows parses")
  void shouldParseEveryExampleWhenTheGuideShowsACommand() {
    for (Example example : RUNBOOK.examples()) {
      assertThatCode(
              () ->
                  EvaluateCli.parse(
                      example.arguments().toArray(new String[0]),
                      null,
                      GuideExamples.INVENTED_HOME))
          .as("docs/developer-guide.md line %d: %s", example.line(), example.text())
          .doesNotThrowAnyException();
    }
  }
}
