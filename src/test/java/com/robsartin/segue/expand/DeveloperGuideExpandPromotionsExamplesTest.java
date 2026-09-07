package com.robsartin.segue.expand;

import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.segue.arch.GuideExamples;
import com.robsartin.segue.arch.GuideExamples.Example;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * "Expanding every promotion" is a runbook the owner executes, and this is what checks it (#284,
 * ADR 66) — {@code DeveloperGuideSupervisedRunExamplesTest}'s method, one chapter over, joined with
 * {@code DeveloperGuideCensusExamplesTest}'s parse check.
 *
 * <p><b>Why {@code parse} and not the tool.</b> Running an example end to end would need a database
 * — the only one on this machine is the owner's — and this is the one tool in the project that
 * would also write it and reach a network. What a runbook has to get right is the command line, and
 * {@link ExpandCli#parse} is what enforces it before any file is opened.
 *
 * <p>In {@code expand} rather than beside the document tests in {@code arch}, because {@link
 * ExpandCli#parse} is package-private, exactly as every sibling tool's is.
 *
 * <p><b>Order is the substance.</b> A runbook that expands before it takes the census it will be
 * compared against, or that appends without the dry run first, is wrong in a way no parser can see
 * — and this tool writes the log and calls a public API, which is the sharpest case of it in the
 * project.
 */
class DeveloperGuideExpandPromotionsExamplesTest {

  private static final String CHAPTER = "Expanding every promotion";

  private static final GuideExamples RUNBOOK = GuideExamples.of("expandPromotions");

  @Test
  @DisplayName("the guide holds the promotion-expansion chapter")
  void shouldShowTheChapterWhenTheGuideDocumentsAPromotionExpansion() {
    assertThat(GuideExamples.chapterText(CHAPTER))
        .as(
            "docs/developer-guide.md — a '## %s' chapter. Every other assertion in this class"
                + " reads an empty chapter rather than throwing, so this one is what says the"
                + " chapter is gone rather than silent",
            CHAPTER)
        .isPresent();
  }

  @Test
  @DisplayName("every expandPromotions example in the guide parses through this tool's own parser")
  void shouldParseEveryExampleWhenTheGuideShowsTheTool() {
    assertThat(RUNBOOK.examples())
        .as(
            "docs/developer-guide.md — at least one ./gradlew expandPromotions --args=\"…\" line."
                + " Without this the check below passes vacuously on a guide that shows nothing")
        .isNotEmpty();

    List<String> refused = new ArrayList<>();
    for (Example example : RUNBOOK.examples()) {
      try {
        ExpandCli.parse(
            example.arguments().toArray(String[]::new), null, GuideExamples.INVENTED_HOME);
      } catch (RuntimeException refusal) {
        refused.add(
            "line " + example.line() + ": " + example.text() + "\n    " + refusal.getMessage());
      }
    }

    assertThat(refused)
        .as(
            "docs/developer-guide.md — every expandPromotions example is run through"
                + " ExpandCli.parse, the boundary that decides whether a line is correct to type."
                + " --db is enforced there, so an example that forgot it fails here")
        .isEmpty();
  }

  @Test
  @DisplayName("no example writes a tilde where $HOME belongs")
  void shouldWriteHomeRatherThanATildeWhenAnExampleNamesADatabase() {
    assertThat(RUNBOOK.withATilde())
        .as(
            "docs/developer-guide.md — a tilde does not expand inside the double quotes of"
                + " --args=\"…\", so the example arrives at the tool as a literal ~ and dies with"
                + " \"no segue database at ~/.segue/segue.db\". ExpandCli.parse cannot see this,"
                + " because a tilde is a valid path character")
        .isEmpty();
  }

  @Test
  @DisplayName("an example that cannot be read is named rather than skipped")
  void shouldNameTheLineWhenAnExampleCannotBeRead() {
    assertThat(RUNBOOK.unreadableExamples())
        .as(
            "docs/developer-guide.md — a line naming expandPromotions that this test cannot read is"
                + " a line nothing checks, and skipping it silently is the hole this assertion"
                + " exists to close. A line with no --args at all is prose and is allowed")
        .isEmpty();
  }

  @Test
  @DisplayName("the chapter shows the census, the dry run, the run and the census, in that order")
  void shouldRunEveryStepInOrderWhenTheChapterIsRead() {
    assertThat(steps())
        .as(
            "docs/developer-guide.md, '%s' — the runbook's whole substance is this sequence: the"
                + " census the census at the end is compared against comes first, the dry run"
                + " comes before the only writing command in the chapter, and a census after is"
                + " what makes the run readable. A parser cannot see any of that",
            CHAPTER)
        .containsExactly(
            "graphCensus", "expandPromotions --dry-run", "expandPromotions", "graphCensus");
  }

  /**
   * The chapter's {@code ./gradlew} lines, merged across the two tasks and put back into the order
   * the guide writes them, each reduced to its task name plus {@code " --dry-run"} where that flag
   * is among its arguments.
   */
  private static List<String> steps() {
    record Numbered(int line, String command) {}
    List<Numbered> found = new ArrayList<>();
    for (String task : List.of("graphCensus", "expandPromotions")) {
      for (Example example : GuideExamples.inChapter(CHAPTER, task).examples()) {
        found.add(
            new Numbered(
                example.line(),
                example.arguments().contains("--dry-run") ? task + " --dry-run" : task));
      }
    }
    found.sort(Comparator.comparingInt(Numbered::line));
    return found.stream().map(Numbered::command).toList();
  }
}
