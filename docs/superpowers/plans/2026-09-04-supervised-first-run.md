# A supervised first run — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to
> implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `docs/developer-guide.md` gains a `## A supervised first run` chapter — a numbered runbook
the **owner** executes end to end, from a census before to a census after — checked by a new
chapter-scoped test that is red on `main`'s guide today, and whose command lines are proven to reach
the three real CLI parsers by planting.

**Architecture:** No production code. `arch.GuideExamples` (test support) gains two statics that
scope its existing extraction to one `## ` chapter. A new
`arch.DeveloperGuideSupervisedRunExamplesTest` asserts what the three whole-file
`DeveloperGuide*ExamplesTest` classes cannot: that the chapter exists, that its nine `./gradlew`
lines are the right commands in the right order, that none is unreadable or tilde'd, and that it
cites the decisions it leans on. The chapter is written to make that green. The link to the real
parsers is not re-implemented — those three classes already scan the whole guide file — it is
**proven by plant**, in Task 2.

**Tech Stack:** Java (toolchain 25, `release 21`), JUnit 5, AssertJ; the guide is Markdown.

**Spec:** `docs/superpowers/specs/2026-09-04-supervised-first-run-design.md` — it holds the verified
fact table, the two corrections to the issue's framing (`withdrawn`/`taste` do **not** move; one test
class cannot reach three package-private parsers), the step table, and the rejected alternatives.
Cite it; do not restate its reasoning.

## Global Constraints

- **The owner runs every writing step in the chapter. The implementer runs none of them.**
  **NEVER** run `./gradlew ownClaim`, `./gradlew own` (Gradle resolves it to `:ownClaim`),
  `./gradlew retractEntity`, `./gradlew graphCensus`, `./gradlew rate`, `./gradlew seed`, the server,
  or any other writing dev task. **NEVER** read, write, copy or create `~/.segue/segue.db` — not
  once, not to "check a label". The word `~/.segue` appears in this plan only inside quoted refusal
  text.
- **No production code.** `src/main/java` is not edited. No ADR is added or amended: the spec says
  why (nothing is decided; the chapter cites ADR 24, 44, 59, 60, 63 where each rule lives).
- Pure TDD. The failing test is written and **run**, and a real assertion failure is observed and
  quoted, before the chapter exists. A compile error is not a red.
- **Every guard gets a positive control**: plant the defect, watch the named assertion fire, quote
  it, revert the plant, confirm `git status` is clean. Five plants in this plan, all written out.
- Test names `should<Expected>When<Condition>` with `@DisplayName`.
- Mikado: green at every committed step. Two commits, one per task.
- **Never `git add -A`.** Stage every file by explicit path, git stderr visible (never `2>/dev/null`),
  and read `git status` before committing. You are the sole committer in this worktree.
- Gate, **blocking** (never backgrounded):
  `SEGUE_REQUIRE_BROWSER=true SEGUE_REQUIRE_GRAPHVIZ=true ./gradlew check --rerun-tasks`.
  Plain `./gradlew`; JDK 25 is the only JDK installed (`java_home -v 21` silently returns it).
- Commit trailer: `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`.
- Never cite a `.superpowers/` path from a committed file.
- **No numbers about the owner's graph anywhere in the chapter.** Step 8 names lines and directions.
- Machine is loaded: no wall-clock assertions.

---

### Task 1: the chapter, and the chapter-scoped check that is red without it

**Files:**
- Modify: `src/test/java/com/robsartin/segue/arch/GuideExamples.java` (two new statics, one private
  scan extraction, javadoc)
- Create: `src/test/java/com/robsartin/segue/arch/DeveloperGuideSupervisedRunExamplesTest.java`
- Modify: `docs/developer-guide.md` (one `Contents` entry; one new `## ` chapter after
  `## Claiming something no source has` and before `## How to read an ADR against the code`)
- Read only: `src/test/java/com/robsartin/segue/{own,retract,census}/DeveloperGuide*ExamplesTest.java`,
  `src/main/java/com/robsartin/segue/census/CensusReport.java`,
  `src/main/java/com/robsartin/segue/census/ClaimCensus.java`,
  `src/main/java/com/robsartin/segue/retract/RetractRun.java`,
  `src/main/java/com/robsartin/segue/ingest/GraphProjector.java`,
  `src/main/java/com/robsartin/segue/domain/EdgeRecord.java`,
  `src/main/java/com/robsartin/segue/export/LogProjection.java`,
  `docs/adr/0019-*.md`, `0024-*.md`, `0044-*.md`, `0054-*.md`, `0059-*.md`, `0060-*.md`,
  `0061-*.md`, `0063-*.md`

**Interfaces:**
- Consumes: `GuideExamples.of`, `GuideExamples.Example`, `RepositoryTree.root()/read()`.
- Produces: `GuideExamples.chapterText(String)`, `GuideExamples.inChapter(String, String)` — used by
  the new test class and by nothing else.

- [ ] **Step 1 (RED) — the chapter-scoped check, written against a guide that has no such chapter.**

  First add the two statics to `GuideExamples`, by extracting the existing scan. Replace the body of
  `of` and add below it:

  ```java
  /** Every example the guide shows for one Gradle task, in the order it shows them. */
  public static GuideExamples of(String taskName) {
    String[] lines = guideLines();
    return scan(lines, taskName, 0, lines.length);
  }

  /**
   * Every example one {@code ## } chapter shows for one Gradle task — {@link #of} restricted to
   * that chapter's lines, with the guide's own line numbers kept so a failure still opens.
   *
   * <p><b>Empty when the chapter is absent, rather than throwing</b>, so a guide missing the
   * chapter reds on one named assertion rather than erroring in four. The loud guard is {@link
   * #chapterText}, which the caller asserts is present before it reads anything else — see {@code
   * DeveloperGuideSupervisedRunExamplesTest}.
   */
  public static GuideExamples inChapter(String heading, String taskName) {
    String[] lines = guideLines();
    int[] range = chapterRange(lines, heading);
    return range == null
        ? new GuideExamples(List.of(), List.of())
        : scan(lines, taskName, range[0], range[1]);
  }

  /** One {@code ## } chapter's lines, joined; empty when the guide has no such chapter. */
  public static Optional<String> chapterText(String heading) {
    String[] lines = guideLines();
    int[] range = chapterRange(lines, heading);
    return range == null
        ? Optional.empty()
        : Optional.of(String.join("\n", Arrays.asList(lines).subList(range[0], range[1])));
  }

  private static String[] guideLines() {
    return RepositoryTree.read(RepositoryTree.root().resolve(GUIDE)).split("\n", -1);
  }

  /** {@code [from, to)} over {@code lines} for {@code ## heading}, or null when it is absent. */
  private static int[] chapterRange(String[] lines, String heading) {
    for (int i = 0; i < lines.length; i++) {
      if (!lines[i].equals("## " + heading)) {
        continue;
      }
      for (int j = i + 1; j < lines.length; j++) {
        if (lines[j].startsWith("## ")) {
          return new int[] {i, j};
        }
      }
      return new int[] {i, lines.length};
    }
    return null;
  }
  ```

  Then rename the current body of `of` to
  `private static GuideExamples scan(String[] lines, String taskName, int from, int to)`, delete its
  first three statements (the two `Pattern` locals stay; the `String[] lines = ...` line goes), and
  change its loop header to `for (int i = from; i < to; i++)`. Nothing else in it changes — line
  numbers stay absolute (`i + 1`) and the backslash-continuation join stays bounded by
  `lines.length`, so a wrapped example on a chapter's last line is still joined. Add
  `java.util.Arrays` and `java.util.Optional` imports. Add one paragraph to the class javadoc:

  ```java
   * <p><b>Chapter scoping was added for the supervised-run runbook (#249), and it exists because
   * {@link #of} is deliberately whole-file.</b> That is what makes the three tool tests reach every
   * example wherever it is written — including a chapter added later, with no new code — but it
   * also means a whole chapter can be deleted with every one of them still green on the other
   * chapters' examples. {@link #inChapter} is what can say "this chapter, these commands, in this
   * order".
  ```

  Now create `DeveloperGuideSupervisedRunExamplesTest`:

  ```java
  package com.robsartin.segue.arch;

  import static org.assertj.core.api.Assertions.assertThat;

  import com.robsartin.segue.arch.GuideExamples.Example;
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
   * ownClaim}, {@code retractEntity} and {@code graphCensus} lines are already being handed to
   * {@code OwnCli.parse}, {@code RetractCli.parse} and {@code CensusCli.parse} by {@code
   * own.DeveloperGuideOwnClaimExamplesTest}, {@code retract.DeveloperGuideRetractionExamplesTest}
   * and {@code census.DeveloperGuideCensusExamplesTest} — three classes in three packages, because
   * all three parsers are package-private and widening one to suit a test was refused. Dropping
   * {@code --db} from a line in this chapter reds those tests by name; it was planted and measured.
   *
   * <p><b>What nothing else could say is chapter-scoped</b>: this chapter could be deleted whole and
   * all three would stay green on the other chapters' examples. So the assertions here are that the
   * chapter is there, that its commands are the right commands in the right order — a census before,
   * a dry run before every write, a census after — that no line of it is unreadable or writes a
   * tilde, and that it cites the decisions it leans on.
   *
   * <p><b>Order is the substance.</b> A runbook that writes before it takes the census it will be
   * compared against, or that appends without the dry run first, is wrong in a way no parser can
   * see.
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
    @DisplayName("the chapter takes a census, writes twice with a dry run each, retracts, and takes a census again")
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
     * order the guide writes them, each reduced to what identifies the step: the task, the
     * subcommand where there is one, and whether it is a dry run.
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
  ```

  **Run it, blocking:**
  `./gradlew test --tests '*DeveloperGuideSupervisedRunExamplesTest'`

  **Observe and quote a real assertion failure**, not a compile error. Expect three red methods:
  `shouldShowTheChapterWhenTheGuideDocumentsASupervisedRun`
  (`Expecting Optional to contain a value but it was empty`),
  `shouldRunEveryStepInOrderWhenTheChapterIsRead` (`Expecting actual: [] to contain exactly …`), and
  `shouldCiteTheDecisionsWhenTheChapterTellsTheOwnerWhatToType` (`Expecting ArrayList: [] to contain
  …` — `contains` on the empty list of an absent chapter fails too). The other two pass vacuously on
  an absent chapter, which is exactly why the first one exists. Paste all three messages into the
  task report verbatim. If the run fails to compile instead, fix the compilation and run again — the
  compile error is not the red.

- [ ] **Step 2 (GREEN) — write the chapter.**

  Add one `Contents` entry, immediately after the `Claiming something no source has` line:

  ```markdown
  - [A supervised first run](#a-supervised-first-run)
  ```

  Then insert the chapter into `docs/developer-guide.md` between the end of
  `## Claiming something no source has` and the line `## How to read an ADR against the code`. Write
  it exactly as follows.

  ````markdown
  ## A supervised first run

  The census taken on 2026-09-04 says the owner tools have never touched real data: no merges, no
  retractions, one local entity minted, nothing MusicBrainz reached. Every merge, retraction and
  stand-in path in the fold is fixture-only on the owner's graph, and the first real one should be a
  supervised run rather than a surprise. This chapter is that run.

  **The owner types every command here.** Nothing in this repository runs against the real database,
  and an agent reading this chapter is reading a description of what the owner will do, not a script
  it may execute — `--db` is required and `SEGUE_DB` does not satisfy it precisely so that an agent
  inheriting the owner's shell cannot stand in for him
  ([ADR 60](adr/0060-the-claim-tools-require-an-explicit-database.md)).

  Read [Claiming something no source has](#claiming-something-no-source-has),
  [Taking something back out](#taking-something-back-out) and
  [Looking at the shape of your graph](#looking-at-the-shape-of-your-graph) first. This chapter puts
  them in an order and says what to expect between them; it does not restate what they say.

  ### 0. Quit the client, and confirm nothing is holding the database

  [ADR 24](adr/0024-sqlite-assertion-log.md) assumes a single writer, and **nothing detects a second
  one** — that is [convention, not a check](#which-rules-are-only-convention). A server left running
  through this run is the one way to open the window issue #234 measured: its graph still holds a
  node for an entity you have retracted, so a claim naming that id passes the ingest gate, is
  appended, and the next boot cannot get past that row.

  Quit the MCP client, which is what starts and stops segue as a subprocess on the stdio transport.
  Then confirm no JVM is left holding the file:

  ```bash
  pgrep -fl segue
  ```

  Nothing printed means nothing is running. If something is, stop it and look again before going on.
  That command reads a process list and writes nothing.

  ### 1. The census before

  ```bash
  ./gradlew graphCensus --args="--db $HOME/.segue/segue.db"
  ```

  Paste the whole block somewhere you can put the second one beside it. It is aggregates only, which
  is why it is safe to paste at all — [ADR 63](adr/0063-a-read-only-census-of-the-graph.md).

  ### 2. Mint one entity, dry run first

  Choose the label yourself, and choose it knowing two things. `mint` allocates `Q00` and the
  smallest number **no row has ever named**, and ids are never handed back: retracting this entity
  later does not free its id, because the log is append-only
  ([ADR 19](adr/0019-assertion-log-source-of-truth.md)) and a retraction is a claim rather than a
  deletion ([ADR 44](adr/0044-retraction-as-a-new-claim.md)). And the label goes into that same log,
  so it stays there after the retraction, readable, permanently. Pick something that says what it
  is. A shape that works: `segue supervised run 2026-09-04`.

  ```bash
  # which id would this take, and what would it say? Nothing is written.
  ./gradlew ownClaim --args="mint --db $HOME/.segue/segue.db --kind WORK --label 'segue supervised run 2026-09-04' --dry-run"

  # do it — the tool answers with the id it allocated
  ./gradlew ownClaim --args="mint --db $HOME/.segue/segue.db --kind WORK --label 'segue supervised run 2026-09-04'"
  ```

  Write down the id the second command printed. Every `Q00900042` below means that id.

  ### 3. Join it to something real, dry run first

  Pick an entity your graph already holds — `Q12345` below stands for whichever you pick. An owner
  edge joins two ids that are **already** in the projection, so an endpoint that is not there is
  refused rather than created. It routes and never vouches: `Provenance.owner` is filtered out
  before distinct sources are counted, so this edge corroborates nothing
  ([ADR 59](adr/0059-owner-claims-as-a-third-layer.md)).

  ```bash
  ./gradlew ownClaim --args="assert --db $HOME/.segue/segue.db --from Q00900042 --to Q12345 --type INFLUENCED_BY --dry-run"
  ./gradlew ownClaim --args="assert --db $HOME/.segue/segue.db --from Q00900042 --to Q12345 --type INFLUENCED_BY"
  ```

  ### 4. Boot once, and look at what you claimed

  Start the MCP client again, so it launches segue and the boot folds the log. Ask it for
  `get_entity` on the id you minted: you should get back the label you chose, the kind you gave it,
  and one neighbour — the entity you picked in step 3.

  Then **quit the client again.** Step 5 is a second writer otherwise, which is what step 0 is about.

  ### 5. Retract it, dry run first

  ```bash
  ./gradlew retractEntity --args="--db $HOME/.segue/segue.db --qid Q00900042 --reason 'supervised first run 2026-09-04' --dry-run"
  ./gradlew retractEntity --args="--db $HOME/.segue/segue.db --qid Q00900042 --reason 'supervised first run 2026-09-04'"
  ```

  The dry run names what would stop projecting — the node claim and the edge claim — and appends
  nothing. **Read the real run's closing note to the end.** It tells you to restart before anything
  else is ingested and names the consequence of not doing so; that note is ADR 24's 2026-09-04
  amendment in one sentence, and you have already satisfied it by quitting in step 4.

  ### 6. Boot again, and let the pre-flight speak

  Start the client once more. **The boot must succeed.** Before it applies anything, `GraphProjector`
  refuses the whole log if any surviving row names an entity no node stands for, listing each
  offending row by sequence number, the id nothing stands for, and the repair.

  If you see `replay refused:`, that is the run's finding and its first move at once: do what the
  message says — retract the endpoint it names, which withdraws the edge without deleting anything —
  rather than anything else, and file the block you saw. Appending a node claim for the named id does
  not repair it, and the message says so.

  ### 7. The census after

  ```bash
  ./gradlew graphCensus --args="--db $HOME/.segue/segue.db"
  ```

  ### 8. What should have moved, and what should not

  No figures are written here. The run is what produces them, and a table of expected counts would be
  a second source of truth about your graph, going stale on its own. What is written is **which
  lines** move, and in which direction. `CensusReport` is the authority on the labels.

  Between the census in step 1 and the census in step 7, the log grew and the projection did not:

  | line | direction | why |
  | --- | --- | --- |
  | `claims / log rows` | up, by three | the mint, the owner edge, the retraction — the raw size, the one figure here that is not a derivation |
  | `claims / retractions` | up, by one | rows that are a retraction |
  | `claims / rows they removed` | up, by two | the node claim and the edge claim the retraction reaches. Never the same figure as the line above, and the gap between them is the blast radius ADR 44 talks about |
  | `claims / entities they name` | up, by one | distinct entities a retraction names |
  | `claims / local entities minted` | **back where it started** | it counts *surviving* rows, and the retraction reaches the mint |
  | everything under `nodes`, `edges` and `degree` | **back where it started** | the whole point: the log remembers, the projection does not |
  | `edges / withdrawn` | **unchanged** | that count is a merge's canonical side emptied by a retraction, and this run makes no merge. A movement here is a finding |
  | everything under `taste` | **unchanged** | nothing was rated. A movement here is a finding |
  | everything under `bridge` | **unchanged** | until step 9 |

  If you also take a census between step 3 and step 5 — and it is worth taking — that one is the
  interesting one. Against step 1: `nodes / total` and the `WORK` line up by one, `edges / total` and
  the `of type INFLUENCED_BY` line up by one, a `backed by owner` line appearing or rising, and the
  new edge landing on `corroborated by 0` rather than `corroborated by 1`, because an owner claim is
  filtered out before distinct sources are counted. Under `degree`, the minted node arrives at zero
  and leaves at one, so `at or below the floor` moves twice and the percentiles may move with it.

  ### 9. Optional: reach MusicBrainz once

  **There is no dev-side bridge tool, deliberately.** MusicBrainz is reached only by `expand_entity`
  running inside the server, and the adapter describes `PERSON` and `GROUP` and nothing else
  ([ADR 54](adr/0054-musicbrainz-as-the-second-source.md),
  [ADR 61](adr/0061-the-bridge-returns-classes.md)). So this step is one you do through the client;
  there is no command for it in this chapter.

  Start the client, pick one person or one band already in your graph, and call `expand_entity` on
  it. Then quit — step 0's rule has not stopped applying — and take a third census:

  ```bash
  ./gradlew graphCensus --args="--db $HOME/.segue/segue.db"
  ```

  `bridge / entities MusicBrainz reached` should be up, and `edges / backed by musicbrainz` with it;
  if neither moved, nothing was reached and the run has said so. If the first moved and
  `bridge / of those, carrying classes` did not, you have measured the residual ADR 55 and issue #167
  left open, for the first time on real data, and it is worth an issue of its own.

  ### What to file from what you saw

  This run changes no code. What it produces is issues, and these are the ones to watch for:

  - **A boot that refused.** File the `replay refused:` block verbatim, sequence numbers and all,
    with what you did about it. That is the first real exercise of the boot pre-flight.
  - **A line that moved when the table above says it should not** — `taste`, `edges / withdrawn`, or
    `bridge` before step 9. Any of those means a rule reaches further than this chapter says.
  - **A line that did not move when the table says it should**, especially `claims / rows they
    removed`: if the retraction reached fewer rows than the dry run reported, the report and the fold
    disagree, and that is the more serious of the two.
  - **Anything a tool printed that you had to stop and think about.** A refusal that did not tell you
    what to type next is a defect in the sentence, not in you.
  - **Anything this chapter got wrong.** It was written against the code and checked against the
    parsers, and it has never been run. The first run is what makes it true.
  ````

  **Run, blocking:**
  `./gradlew test --tests '*DeveloperGuideSupervisedRunExamplesTest' --tests '*DeveloperGuideOwnClaimExamplesTest' --tests '*DeveloperGuideRetractionExamplesTest' --tests '*DeveloperGuideCensusExamplesTest' --tests '*DocumentationLinksTest' --tests '*DeveloperGuideEnumerationsTest'`

  All six green. `DocumentationLinksTest` is the one that proves the chapter's eleven relative links
  and four in-guide anchors resolve; quote its result. If a link reds, fix the link — do not weaken
  the check.

- [ ] **Step 3 (positive control) — plant a chapter that is not there.** Change the chapter heading
  to `## A supervised first run (draft)`. Run the new test class, blocking. Both
  `shouldShowTheChapterWhenTheGuideDocumentsASupervisedRun` and
  `shouldRunEveryStepInOrderWhenTheChapterIsRead` must red, the first naming the chapter. Quote both.
  The chapter is not yet committed at this point, so `git checkout -- docs/developer-guide.md` would
  delete it rather than revert the plant; restore the authored heading by hand and re-run to green.

- [ ] **Step 4 (positive control) — plant a step out of order.** Move step 1's `graphCensus` block to
  sit *after* step 2's two `mint` lines. Run the class. `shouldRunEveryStepInOrderWhenTheChapterIsRead`
  must red showing the census third rather than first. Quote it. Revert; re-run to green.

- [ ] **Step 5 (positive control) — plant a missing dry run.** Delete the `--dry-run` mint line (the
  first of step 2's two). Run the class. The same assertion must red, showing eight commands with
  `ownClaim mint --dry-run` absent. Quote it. Revert; re-run to green.

- [ ] **Step 6 (positive control) — plant a tilde.** Change step 7's `graphCensus` line to
  `--db ~/.segue/segue.db`. Run the class. `shouldWriteHomeRatherThanATildeWhenTheChapterNamesADatabase`
  must red naming that line. Quote it. Revert; re-run to green.

- [ ] **Step 7 (positive control) — plant a missing citation.** Change step 0's ADR 24 link target to
  `adr/9924-sqlite-assertion-log.md`. Run the class **and** `DocumentationLinksTest`.
  `shouldCiteTheDecisionsWhenTheChapterTellsTheOwnerWhatToType` must red for the missing `0024`, and
  `DocumentationLinksTest` must red for the file that does not exist. Quote both — they are two
  different guarantees and it is worth seeing both fire. Revert; re-run to green.

- [ ] **Step 8 — gate and commit.** Run the full gate, **blocking**:
  `SEGUE_REQUIRE_BROWSER=true SEGUE_REQUIRE_GRAPHVIZ=true ./gradlew check --rerun-tasks`. Record
  `BUILD SUCCESSFUL` and the test count. Then `git status`, read it, and stage by explicit path:

  ```
  git add docs/developer-guide.md \
          src/test/java/com/robsartin/segue/arch/GuideExamples.java \
          src/test/java/com/robsartin/segue/arch/DeveloperGuideSupervisedRunExamplesTest.java
  ```

  (No `2>/dev/null`; read git's stderr.) Confirm `git status` shows nothing else staged. Commit with
  the trailer from the Global Constraints.

---

### Task 2: prove the chapter's own lines reach the three real parsers, and say so in the chapter

**Why this is a task and not a footnote.** The claim in Task 1's javadoc — that the chapter's command
lines are already being handed to `OwnCli.parse`, `RetractCli.parse` and `CensusCli.parse` — is
inherited from `GuideExamples.of` being whole-file. Inherited guarantees are the ones that quietly
stop holding. Each of the three is planted here, once, and observed.

**Files:**
- Modify: `docs/developer-guide.md` (one paragraph at the end of the new chapter's preamble)
- Read only: the three `DeveloperGuide*ExamplesTest` classes

- [ ] **Step 1 (RED, plant 1 of 3) — `ownClaim`.** In the chapter's step 3, delete `--db
  $HOME/.segue/segue.db ` from the **real** `assert` line (leave the dry run alone). Run, blocking:
  `./gradlew test --tests '*DeveloperGuideOwnClaimExamplesTest'`. It must red on
  `shouldParseEveryExampleWhenTheGuideShowsAnOwnClaimCommand`, naming the guide line number inside
  the new chapter and carrying `RequiredDatabase`'s refusal sentence. Quote the message with the line
  number in it — that number is the proof the chapter is in scope. Revert; re-run to green.

- [ ] **Step 2 (RED, plant 2 of 3) — `retractEntity`.** Same edit on the **real** `retractEntity` line
  in step 5. Run `./gradlew test --tests '*DeveloperGuideRetractionExamplesTest'`, blocking. Red on
  `shouldParseEveryExampleWhenTheGuideShowsARetractionCommand`, naming a line in the new chapter.
  Quote; revert; re-run to green.

- [ ] **Step 3 (RED, plant 3 of 3) — `graphCensus`.** Same edit on step 7's `graphCensus` line. Run
  `./gradlew test --tests '*DeveloperGuideCensusExamplesTest'`, blocking. Red on
  `shouldParseEveryExampleWhenTheGuideShowsACensusCommand`, naming a line in the new chapter. Quote;
  revert; re-run to green. After all three, `git diff --stat` must be empty for
  `docs/developer-guide.md`.

- [ ] **Step 4 (GREEN) — say it in the chapter.** Add this paragraph immediately after the preamble's
  third paragraph, the one beginning `Read`, in the shape the `ownClaim` chapter already uses for the
  same statement:

  ```markdown
  Every `./gradlew` line below is executed by a test before you ever paste it.
  `DeveloperGuideOwnClaimExamplesTest`, `DeveloperGuideRetractionExamplesTest` and
  `DeveloperGuideCensusExamplesTest` split each `--args` string the way a shell would and hand it to
  that tool's own parser, wherever in this guide it is written; `DeveloperGuideSupervisedRunExamplesTest`
  checks this chapter in particular — that it is here, that its commands are these commands in this
  order, and that it cites the decisions it leans on. A flag renamed in a tool reds this chapter, and
  so does a step written out of order.
  ```

  This paragraph is prose and has no unit-testable behaviour of its own. It is verified by two other
  methods, said out loud rather than implied: the three class names are checked to exist by the
  `check` gate compiling them, and the three plants above are what make the sentence true rather than
  hopeful. Re-run the four guide tests plus `DocumentationLinksTest`, blocking; all green.

- [ ] **Step 5 — gate and commit.** Full gate, **blocking**:
  `SEGUE_REQUIRE_BROWSER=true SEGUE_REQUIRE_GRAPHVIZ=true ./gradlew check --rerun-tasks`. Record
  `BUILD SUCCESSFUL`. `git status`, read it, then `git add docs/developer-guide.md` and commit with
  the trailer. Nothing else is staged.

---

## Self-Review

**Spec coverage.** Chapter, its ten steps and its placement → Task 1 Step 2 (spec §2.1). The
chapter-scoped check and its five assertions → Task 1 Step 1 (spec §2.2). The two `GuideExamples`
statics, empty-not-throwing → Task 1 Step 1 (spec §2.2 and the rejected alternative that goes with
it). The parser link proven by plant rather than re-implemented → Task 2 (spec §1, second
correction). `withdrawn` and `taste` written as expectations of **no** movement → Task 1 Step 2, the
step 8 table (spec §1, first correction). No ADR, no production code, nothing run against the real
database → Global Constraints (spec §2.3).

**Placeholders:** none. Every code block and every line of the chapter is written out in full.

**Type consistency:** `GuideExamples.of(String)` keeps its signature and its answer; `inChapter`
returns `GuideExamples`; `chapterText` returns `Optional<String>`; `Example.line()` stays the guide's
absolute 1-based line, which is what makes Task 2's plants quotable. The private constructor
`GuideExamples(List<Example>, List<String>)` already exists and is what `inChapter` calls for the
absent-chapter case.

**Counts that must agree:** nine `./gradlew` lines in the chapter, nine entries in
`containsExactly`. Task 1 Step 5's plant is what proves that pairing can fail.
