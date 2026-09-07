package com.robsartin.segue.ratings;

import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.segue.arch.GuideExamples;
import com.robsartin.segue.arch.GuideExamples.Example;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * "Looking at what you have rated" shows commands the owner is meant to paste, and this runs every
 * one of them through {@link RatingsCli#parse} — the runbook this check was missing, added with the
 * names export it has to cover (#285, on #183's pattern).
 *
 * <p><b>Why {@code parse} and not the tool.</b> Running an example end to end would need a
 * database, and the only one on this machine is the owner's. What a runbook has to get right is the
 * command line, and {@code parse} is what enforces it before any file is opened.
 *
 * <p>In {@code ratings} rather than beside the document tests in {@code arch}, because {@link
 * RatingsCli#parse} is package-private, exactly as every sibling's is.
 */
class DeveloperGuideRatingsExamplesTest {

  private static final GuideExamples RUNBOOK = GuideExamples.of("listRatings");

  @Test
  @DisplayName("the guide shows the names export, not only the listing")
  void shouldShowANamesExportExampleWhenTheGuideDocumentsTheRatingsTool() {
    assertThat(RUNBOOK.examples())
        .as(
            "docs/developer-guide.md, 'Looking at what you have rated' — one ./gradlew listRatings"
                + " --args=\"…\" line carrying --promotions-off and --names. Without it the owner"
                + " has no pasteable command for the output issue #285 exists to produce")
        .anyMatch(
            example ->
                example.arguments().contains("--promotions-off")
                    && example.arguments().contains("--names"));
  }

  @Test
  @DisplayName("no listRatings example writes a tilde where $HOME belongs")
  void shouldWriteHomeRatherThanATildeWhenAnExampleNamesAPath() {
    assertThat(RUNBOOK.withATilde())
        .as(
            "docs/developer-guide.md — a tilde does not expand inside the double quotes of"
                + " --args=\"…\", so the example arrives at the tool as a literal ~ and dies with"
                + " \"no segue database at ~/.segue/segue.db\". RatingsCli.parse cannot see this,"
                + " because a tilde is a valid path character")
        .isEmpty();
  }

  @Test
  @DisplayName("every line naming listRatings is read as a command, or is prose with no --args")
  void shouldNameTheLineWhenAnExampleCannotBeRead() {
    assertThat(RUNBOOK.unreadableExamples())
        .as(
            "docs/developer-guide.md — a line naming listRatings that this test cannot read is a"
                + " line nothing checks, and skipping it silently is the hole this assertion exists"
                + " to close. A line with no --args at all is prose and is allowed")
        .isEmpty();
  }

  @Test
  @DisplayName("every listRatings example parses through the tool's own parser")
  void shouldParseEveryExampleWhenTheGuideShowsACommand() {
    List<String> refused = new ArrayList<>();
    for (Example example : RUNBOOK.examples()) {
      try {
        RatingsCli.parse(
            example.arguments().toArray(String[]::new), null, GuideExamples.INVENTED_HOME);
      } catch (RuntimeException refusal) {
        refused.add(
            "line " + example.line() + ": " + example.text() + "\n    " + refusal.getMessage());
      }
    }

    assertThat(refused)
        .as(
            "docs/developer-guide.md — every listRatings example is run through RatingsCli.parse,"
                + " the boundary that decides whether a line is correct to type. The flag pairing"
                + " is enforced there, so an example giving --names without --promotions-off fails"
                + " here")
        .isEmpty();
  }
}
