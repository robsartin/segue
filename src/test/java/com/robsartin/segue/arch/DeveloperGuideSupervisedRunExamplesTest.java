package com.robsartin.segue.arch;

import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.segue.arch.GuideExamples.Example;
import com.robsartin.segue.domain.LocalEntity;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * "A supervised first run" is a runbook the owner executes, and this is what checks it — issue
 * #249.
 *
 * <p><b>It does not run the examples through the parsers, and that is not a gap.</b> {@link
 * GuideExamples#of} scans the whole guide file, so the moment this chapter exists its {@code
 * ownClaim}, {@code retractEntity} and {@code graphCensus} lines are already being handed to {@code
 * OwnCli.parse}, {@code RetractCli.parse} and {@code CensusCli.parse} by {@code
 * own.DeveloperGuideOwnClaimExamplesTest}, {@code retract.DeveloperGuideRetractionExamplesTest} and
 * {@code census.DeveloperGuideCensusExamplesTest} — three classes in three packages, because all
 * three parsers are package-private and widening one to suit a test was refused. Dropping {@code
 * --db} from a line in this chapter reds those tests by name; it was planted and measured.
 *
 * <p><b>What nothing else could say is chapter-scoped</b>: this chapter could be deleted whole and
 * all three would stay green on the other chapters' examples. So the assertions here are that the
 * chapter is there, that its commands are the right commands in the right order — a census before,
 * a dry run before every write, a census after — that no line of it is unreadable or writes a
 * tilde, and that it cites the decisions it leans on.
 *
 * <p><b>Order is the substance.</b> A runbook that writes before it takes the census it will be
 * compared against, or that appends without the dry run first, is wrong in a way no parser can see.
 */
class DeveloperGuideSupervisedRunExamplesTest {

  private static final String CHAPTER = "A supervised first run";

  /** Every task the chapter shows a command for. */
  private static final List<String> TASKS = List.of("graphCensus", "ownClaim", "retractEntity");

  /** {@code adr/0044-} — the number is what is asserted, so a renamed file is not a false red. */
  private static final Pattern ADR_LINK = Pattern.compile("adr/(\\d{4})-");

  @Test
  @DisplayName("the guide holds the supervised-run chapter")
  void shouldShowTheChapterWhenTheGuideDocumentsASupervisedRun() {
    assertThat(GuideExamples.chapterText(CHAPTER))
        .as(
            "docs/developer-guide.md — a '## %s' chapter. Every other assertion in this class"
                + " reads an empty chapter rather than throwing, so this one is what says the"
                + " chapter is gone rather than silent",
            CHAPTER)
        .isPresent();
  }

  @Test
  @DisplayName(
      "the chapter takes a census, writes twice with a dry run each, retracts, and takes a census"
          + " again")
  void shouldRunEveryStepInOrderWhenTheChapterIsRead() {
    assertThat(commandsInOrder())
        .as(
            "docs/developer-guide.md, '%s' — the runbook's whole substance is this sequence: the"
                + " census that the census in step 7 is compared against comes first, every"
                + " writing command is shown as a --dry-run before it is shown for real, and the"
                + " optional bridge step takes a third census. A parser cannot see any of that",
            CHAPTER)
        .containsExactly(
            "graphCensus",
            "ownClaim mint --dry-run",
            "ownClaim mint",
            "ownClaim assert --dry-run",
            "ownClaim assert",
            "retractEntity --dry-run",
            "retractEntity",
            "graphCensus",
            "graphCensus");
  }

  @Test
  @DisplayName("every line the chapter shows is read as a command")
  void shouldNameTheLineWhenAChapterExampleCannotBeRead() {
    List<String> unreadable = new ArrayList<>();
    for (String task : TASKS) {
      unreadable.addAll(GuideExamples.inChapter(CHAPTER, task).unreadableExamples());
    }

    assertThat(unreadable)
        .as(
            "docs/developer-guide.md, '%s' — a line naming one of these tasks that cannot be read"
                + " is a line nothing checks, here or in the three parser tests",
            CHAPTER)
        .isEmpty();
  }

  @Test
  @DisplayName("no chapter example writes a tilde where $HOME belongs")
  void shouldWriteHomeRatherThanATildeWhenTheChapterNamesADatabase() {
    List<String> tilded = new ArrayList<>();
    for (String task : TASKS) {
      tilded.addAll(GuideExamples.inChapter(CHAPTER, task).withATilde());
    }

    assertThat(tilded)
        .as(
            "docs/developer-guide.md, '%s' — a tilde does not expand inside the double quotes of"
                + " --args=\"…\", so the owner would paste a line that dies. This is a runbook;"
                + " every line in it is meant to be pasted exactly as written",
            CHAPTER)
        .isEmpty();
  }

  @Test
  @DisplayName("retractEntity is only ever shown against a local entity, never a Wikidata id")
  void shouldNameALocalEntityWhenTheChapterRetracts() {
    List<String> notLocal = new ArrayList<>();
    for (Example example : GuideExamples.inChapter(CHAPTER, "retractEntity").examples()) {
      List<String> arguments = example.arguments();
      int qidIndex = arguments.indexOf("--qid");
      String qid =
          qidIndex >= 0 && qidIndex + 1 < arguments.size() ? arguments.get(qidIndex + 1) : null;
      if (!LocalEntity.isLocal(qid)) {
        notLocal.add("line " + example.line() + ": --qid " + qid);
      }
    }

    assertThat(notLocal)
        .as(
            "docs/developer-guide.md, '%s' — retractEntity is the one destructive command this"
                + " chapter shows, and every --qid in it must be shaped like LocalEntity.isLocal"
                + " (Q00\\\\d+, ADR 59): a Wikidata-shaped id here would retract a real entity from"
                + " the owner's graph instead of the one this run minted",
            CHAPTER)
        .isEmpty();
  }

  @Test
  @DisplayName("the chapter cites the decision behind every rule it asks the owner to follow")
  void shouldCiteTheDecisionsWhenTheChapterTellsTheOwnerWhatToType() {
    Matcher matcher = ADR_LINK.matcher(GuideExamples.chapterText(CHAPTER).orElse(""));
    List<String> cited = new ArrayList<>();
    while (matcher.find()) {
      cited.add(matcher.group(1));
    }

    assertThat(cited)
        .as(
            "docs/developer-guide.md, '%s' — this chapter decides nothing, which is why it has no"
                + " ADR of its own; every rule it asks the owner to follow is somebody else's"
                + " decision and has to be linked where it is leaned on. 24 the single writer,"
                + " 44 retraction as a claim, 59 owner claims as a third layer, 60 the required"
                + " --db, 63 why a census is safe to paste",
            CHAPTER)
        .contains("0024", "0044", "0059", "0060", "0063");
  }

  /**
   * The chapter's {@code ./gradlew} lines, merged across the three tasks and put back into the
   * order the guide writes them, each reduced to what identifies the step: the task, the subcommand
   * where there is one, and whether it is a dry run.
   */
  private static List<String> commandsInOrder() {
    record Numbered(int line, String command) {}
    List<Numbered> found = new ArrayList<>();
    for (String task : TASKS) {
      for (Example example : GuideExamples.inChapter(CHAPTER, task).examples()) {
        List<String> arguments = example.arguments();
        StringBuilder command = new StringBuilder(task);
        if (task.equals("ownClaim") && !arguments.isEmpty()) {
          command.append(' ').append(arguments.get(0));
        }
        if (arguments.contains("--dry-run")) {
          command.append(" --dry-run");
        }
        found.add(new Numbered(example.line(), command.toString()));
      }
    }
    found.sort(Comparator.comparingInt(Numbered::line));
    return found.stream().map(Numbered::command).toList();
  }
}
