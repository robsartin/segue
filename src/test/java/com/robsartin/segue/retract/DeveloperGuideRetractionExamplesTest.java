package com.robsartin.segue.retract;

import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.segue.arch.GuideExamples;
import com.robsartin.segue.arch.GuideExamples.Example;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * "Taking something back out" shows commands the owner is meant to paste, and this runs every one
 * of them through {@link RetractCli#parse} — the twin of {@code
 * own.DeveloperGuideOwnClaimExamplesTest}, for the other tool that requires {@code --db} (#183).
 *
 * <p><b>Why {@code parse} and not the tool.</b> Running an example end to end would append a
 * retraction to a database on every {@code check}. What a runbook has to get right is the command
 * line — the qid, the reason and {@code --db} — and {@code parse} is what enforces that, before any
 * file is opened. {@code --dry-run} examples parse like any other.
 *
 * <p><b>In {@code retract} rather than beside the other document tests in {@code arch}</b>, because
 * {@link RetractCli#parse} is package-private, exactly as its sibling is. That is why there are two
 * classes rather than one: neither parser is reachable from the other's package, and widening one
 * in production code to suit a documentation check was refused. {@link GuideExamples} is what they
 * share instead.
 *
 * <p>No subcommand assertion here — {@code retractEntity} has one operation — so the vacuity guard
 * is simply that the chapter still shows an example at all.
 */
class DeveloperGuideRetractionExamplesTest {

  private static final GuideExamples RUNBOOK = GuideExamples.of("retractEntity");

  @Test
  @DisplayName("the guide shows at least one retractEntity example")
  void shouldShowAnExampleWhenTheGuideDocumentsRetraction() {
    assertThat(RUNBOOK.examples())
        .as(
            "docs/developer-guide.md, 'Taking something back out' — at least one ./gradlew"
                + " retractEntity --args=\"…\" line. Without this the other checks pass vacuously"
                + " on a chapter that shows nothing")
        .isNotEmpty();
  }

  @Test
  @DisplayName("no retractEntity example writes a tilde where $HOME belongs")
  void shouldWriteHomeRatherThanATildeWhenARetractionExampleNamesADatabase() {
    assertThat(RUNBOOK.withATilde())
        .as(
            "docs/developer-guide.md — a tilde does not expand inside the double quotes of"
                + " --args=\"…\", so the example arrives at the tool as a literal ~ and dies with"
                + " \"no segue database at ~/.segue/segue.db\". RetractCli.parse cannot see this,"
                + " because a tilde is a valid path character")
        .isEmpty();
  }

  @Test
  @DisplayName("no retractEntity example opens an argument string it never closes")
  void shouldNameTheLineWhenARetractionExampleIsNeverFinished() {
    assertThat(RUNBOOK.unfinishedOpenings())
        .as(
            "docs/developer-guide.md — an example whose --args=\"…\" is never closed, even after"
                + " joining backslash-continued lines, is one this test cannot run, and skipping it"
                + " silently is the hole this assertion exists to close")
        .isEmpty();
  }

  @Test
  @DisplayName("every retractEntity example parses through the tool's own parser")
  void shouldParseEveryExampleWhenTheGuideShowsARetractionCommand() {
    List<String> refused = new ArrayList<>();
    for (Example example : RUNBOOK.examples()) {
      try {
        RetractCli.parse(
            example.arguments().toArray(String[]::new), null, GuideExamples.INVENTED_HOME);
      } catch (RuntimeException refusal) {
        refused.add(
            "line " + example.line() + ": " + example.text() + "\n    " + refusal.getMessage());
      }
    }

    assertThat(refused)
        .as(
            "docs/developer-guide.md — every retractEntity example is run through RetractCli.parse,"
                + " the boundary that decides whether a line is correct to type. --db is enforced"
                + " there, so an example that forgot it fails here")
        .isEmpty();
  }
}
