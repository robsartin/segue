# What Wikidata lacks: batch claims from the coverage instruments — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** close issue #342. `ownClaim` gains two batch shapes so the owner stops hand-typing one
claim per row: `mint --review <file> --mapping <file>` over the seed tool's review file, and
`assert --file <claims>` over a three-column file the owner writes. The shared shape those files
are written in moves out of `seed` into `support` first, because the fence forbids `own` from
reading it where it is.

**Spec:** `docs/superpowers/specs/2026-09-18-claims-from-the-coverage-instruments-design.md`. Read
it first; it is the requirements. Where this plan departs from it, the departure is written out
under **Rulings and departures** below and this plan is the authority.

---

## The section-1 ruling, resolved before anything is planned

**May `support` depend on `domain.NodeKind` under the existing layering rules? YES.**

The rules read, all in `src/test/java/com/robsartin/segue/arch/ArchitectureTest.java`:

- `domainHasNoThirdPartyDependencies` — `classes().that().resideInAPackage("..domain..").should()
  .onlyDependOnClassesThat().resideInAnyPackage("..domain..", "java..", "javax..")`. It constrains
  `domain`'s outbound edges, not anyone's inbound ones.
- `portDependsOnlyOnDomain` — the same shape over `..port..`.
- **Those two are the only rules in the file that constrain a package's own dependencies by naming
  that package as the origin.** There is no rule whose origin is `..support..`.
- `noPackageCycles` — `SlicesRuleDefinition.slices().matching("com.robsartin.segue.(*)..").should()
  .beFreeOfCycles()`. `support → domain` cannot close a cycle, because `domainHasNoThirdPartyDependencies`
  already forbids `domain` from depending on anything outside `domain`, `java` and `javax`.
- The one support-facing rule, `theClaimToolsTakeTheirDatabaseFromTheFlagAlone`, points the other
  way: it forbids `retract` and `own` from *taking a `java.nio.file.Path` out of* `support` — and
  only as **a method's return type or a field's type**, per the `A_PATH_TAKEN_OUT_OF_SUPPORT`
  predicate, which switches on `CodeUnitAccessTarget::getRawReturnType` and
  `FieldAccessTarget::getRawType`. A `Path` **parameter** is untouched by it.

**So the kind table lives in `support`, as `support.ListKinds`, and it may name `NodeKind`.** No new
rule is needed and none is added.

**Two consequences that bind every task below.**

1. **No class this plan adds to `support` may return a `Path` from a method or expose a `Path`
   field.** `own` reads three of them; any such member would red
   `theClaimToolsTakeTheirDatabaseFromTheFlagAlone`. Every reader planned here takes its `Path` as a
   parameter and hands back a `List`, a `Set` or nothing.
2. **This is the first edge from `support` into `com.robsartin.segue` at all.** `grep -h "^import
   com.robsartin" src/main/java/com/robsartin/segue/support/*.java` prints nothing today. The
   sentence in `CLAUDE.md` and in the guide's package table that calls these "helpers with no
   project dependencies of their own" is prose that nothing enforces, and Task 4 corrects it rather
   than leaving it to mislead. It was never a rule: `QidList`'s own Javadoc states support's real
   criterion, which is that more than one dev-side tool reads the same thing.

---

**Architecture:** one move, two batch shapes, one reader, four documents.

- **The move (Task 1).** `seed.Outcome` and `seed.ResolutionRow` become `support.Outcome` (with a
  fourth value, `MINTED`) and `support.ResolutionRow`. The mapping/review half of `seed.SeedFiles`
  becomes `support.ResolutionFiles`; the RFC 4180 parser underneath both readers becomes
  `support.CsvFile`. `seed.Names.fold` becomes `support.NameFold.fold`. The list-kind → node-kind
  table leaves `seed.Expectations` for `support.ListKinds`, which `Expectations` then reads.
- **The batch shapes (Tasks 2 and 3).** `OwnCli.Options` gains two sub-interfaces — `Single`
  (`Mint`, `Assert`, `Merge`) and `Batch` (`MintBatch`, `AssertFile`) — so `OwnRun.run` stays a
  total switch over the three single operations with its report untouched, and a second entry point
  `OwnRun.runBatch` returns `List<LoggedAssertion>`.
- **The reader (Task 3).** `own.ClaimFile` reads `from,to,type`. It lives in `own` because nothing
  else reads that shape.
- **The records (Task 4).** Two developer-guide sections, one runbook chapter, a dated amendment on
  ADR 59 and one on ADR 40.

**Tech Stack:** Java 25, Gradle (plain `./gradlew`), JUnit 5, AssertJ, SQLite, ArchUnit.

---

## Rulings and departures

Each of these is a place the spec is silent, or a place this plan does something else. Where the
spec gave wording, the wording below is the spec's verbatim.

1. **`readList` and `SeedRow` stay in `seed`; the CSV parser moves.** The spec says "the
   reader/appender/`alreadyResolved` half of `SeedFiles`" moves. `readList` returns `SeedRow`,
   which only `resolveNames` reads, so moving it would put an input-list shape in `support` that no
   second tool touches. But both readers need the same quote-aware parser, and the smallest move
   that leaves **one** copy of it is to give the parser its own home: `support.CsvFile`, with
   `seed.SeedFiles.readList` and `support.ResolutionFiles.readRows` both reading through it. Two
   readers in two packages sharing one parser is `support`'s own stated criterion.
2. **`ResolutionRow.of(SeedRow, Decision)` does not move.** It is package-private in `seed` with
   exactly one caller, `SeedRun`, and it names two `seed` types. It becomes a private static method
   on `SeedRun`, and `support.ResolutionRow` is a plain record.
3. **`OwnRun.run`'s parameter type narrows from `Options` to `Single`.** The spec says `run` "is
   unchanged"; its body, its report and its return type are. The parameter type has to narrow, or
   the `switch` inside it stops being exhaustive the moment `Options` permits a batch, and the only
   ways back are a `default` arm or two `throw` arms — both of which put "which of these can I
   actually run?" back inside `OwnRun` where the sealed hierarchy answers it for free.
4. **The runbook chapter is `## What Wikidata lacks`, placed after the whole of `## Expanding every
   promotion`.** The spec says "after `### Adding what your list names that the graph has never
   held: --known --add`". Taken literally, a `## ` heading there would end `## Expanding every
   promotion` early and orphan its closing `### What to file from what you saw` section into the
   new chapter — `GuideExamples.chapterRange` ends a chapter at the next `## ` line wherever it is.
   So the new chapter goes after that closing section and before `## How to read an ADR against the
   code`, which is the same position "after the `--known --add` material" means in practice.
5. **Wording the spec does not give.** The mint report's per-row line, the closing `appended.` /
   `dry run: nothing was appended` sentences and the assert report's two lines are the spec's and
   are reproduced verbatim below. The **skip** lines, the **totals** line and the three **refusal**
   sentences are this plan's, written out in full in the tasks so no implementer invents one.
6. **A refused row refuses the whole run in both batches, before any append** — the spec's ruling
   for `assert`, applied to `mint`'s unregistered-kind case too, which the spec already states.

---

## Global Constraints

These bind every task. An implementer who sees only one task brief still gets all of them.

- **Work in `/Users/sartin/code/segue/wt-342`, on branch `342-ready`. Run every command from there,
  blocking. Never `cd` elsewhere. Never touch `~/.segue` or any file under `/Users/sartin` outside
  the worktree.**
- **Pure TDD / red first.** Every behaviour: write the test, **run it, observe a real assertion
  failure**, then the minimum code. **A compile error is never a red.** Where a type must exist
  before a test can compile, the step is split: add the type or the component with a
  deliberately-wrong-but-compiling body so the whole suite still builds, then write the failing
  test, observe the assertion failure, then the real body. **Quote the actual failure text in the
  task report** — not "it failed".
- **A pure move is a refactor, and its control is the existing suite.** Task 1's moves are verified
  by the `seed` tests passing with nothing changed but their imports. The two behaviours that are
  genuinely new in Task 1 — the `MINTED` round-trip and the kind table — are red first like
  anything else.
- **Every guard gets a planted positive control: plant the defect, run the check, observe it fire,
  remove the plant.** Each control this plan requires is written out as its own steps. The report
  says what the planted run printed.
- Test names `should<Expected>When<Condition>`, with `@DisplayName` on every test.
- **Mikado: green at every committed step.** Each task ends green and is independently reviewable.
  Never a big-bang change guarded only by a final run.
- **Per-task loops are `./gradlew test --tests '…'`, run BLOCKING, never backgrounded.** A
  `--tests` filter that matches nothing is a failure mode: check the reported test count is
  non-zero every time. Each task also runs `./gradlew spotlessApply` and then `./gradlew
  compileJava compileTestJava`, blocking. **The full gate is the controller's job and is not a step
  in any task.**
- **Only JDK 25 is installed and Gradle launches on it.** Plain `./gradlew`; never
  `/usr/libexec/java_home -v 21`, which silently returns 25.
- **Never run a writing dev task** — `ownClaim`, `retractEntity`, `rate`, `evaluate`,
  `resolveNames`, `expandPromotions`. **Never read, write, copy or create `~/.segue/segue.db`.**
  Every database in this plan is `SqliteAssertionLog.inMemory()` or a `@TempDir` file, and every
  CSV file is written by the test that reads it.
- **No test in this plan reaches the network.** Nothing here resolves a name; the batch mint reads
  a file the seed tool already wrote.
- **Invented ids only.** A stand-in carries the project's leading zero (`Q09…`); an id the owner minted
  carries two (`Q00…`). **Never a real Wikidata id.** The ids this plan introduces are, in full:

  | id | what it stands for |
  | --- | --- |
  | `Q0903301` | a sourced entity the projection holds — an owner edge's `to` |
  | `Q0903302` | a second sourced entity the projection holds |
  | `Q0903303` | an id nothing in the log has ever named, so an endpoint refusal has a subject |
  | `Q0903304` | a third sourced entity, so a duplicate skip can sit beside a claim that is kept |
  | `Q00903301` | a minted id the developer guide's examples name |

  `grep -rn 'Q09033' src docs` and `grep -rn 'Q009033' src docs` each find nothing today. **Run
  both in Task 1 Step 1 and stop if either prints anything.** Ids the tool *allocates* in a test are
  whatever `Q00` + the smallest free number comes to (`Q001`, `Q002`, …) and are asserted, not
  invented.
- **No `support` class this plan adds may return a `Path` or hold a `Path` field.** See the
  section-1 ruling.
- **No entity from anybody's real graph is named anywhere**, in code, in a fixture, in a document
  or in a commit message — taste, and anything derived from it, is personal data and this
  repository is public. Every name below is invented.
- **`{@code X.y}` must sit on one source line after `./gradlew spotlessApply`.** google-java-format
  reflows Javadoc and will break inside an inline tag. After every `spotlessApply`, run
  `grep -nE '\{@(code|link)[^}]*$'` over **every Java file the task touched**; the output must be
  empty. To put a span on one line, shorten the clause *before* it.
- **Markdown links whole and on one line**, `[text](target)`, no title, no raw HTML —
  `DocumentationLinksTest` refuses every other shape and scans `docs/**` including this plan. An ADR
  links a sibling as `00NN-….md`; the developer guide links one as `adr/00NN-….md`.
- **No commit hash and no backticked qid in any file under `docs/adr`.** `AdrCitationsTest` reds on
  a backticked run of 7–40 hex characters, so never put a bare digit run of seven or more inside
  backticks in ADR prose.
- **Never cite a `.superpowers/` path from a committed file.**
- **Stage by explicit path, git stderr visible — never `git add -A`, never `2>/dev/null` on `git
  add`.** Read `git status` before every commit. Other sessions share this repository's stash
  stack and may hold other worktrees; stage only the paths the step names. Every commit message
  ends, after a blank line, with exactly:

  ```
  Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
  ```

- **The only issue numbers this plan may cite are #342, #344, #311, #315, #326, #328, #319, #92,
  #179, #224 and #37, and the only ADR numbers are 19, 24, 33, 40, 44, 59, 60, 63 and 66.** Invent
  no others; every one of these names a file in `docs/adr/` or an issue on the tracker today. The
  `(ADR 24)` inside the closing `appended.` sentence is a string literal the tool already prints
  and which the spec requires the batches to reproduce **verbatim** — copy it character for
  character rather than rewording it.
- **Never restate a count or a figure in prose.** Cite where it lives.
- **YAGNI.** No parameter, overload or helper ahead of a real need.

---

## Task 1 — the observation, then the move to `support`

The prerequisite. `own` may not read `seed` (`ArchitectureTest.theOwnerClaimToolOpensNothingElse`),
so the shape both tools need moves to a package both may read. Step 1 **observes** the break the
spec predicts rather than assuming it; step 2 reverts it; the rest is the move.

**Files:**

*Create:* `src/main/java/com/robsartin/segue/support/Outcome.java`,
`src/main/java/com/robsartin/segue/support/ResolutionRow.java`,
`src/main/java/com/robsartin/segue/support/CsvFile.java`,
`src/main/java/com/robsartin/segue/support/ResolutionFiles.java`,
`src/main/java/com/robsartin/segue/support/NameFold.java`,
`src/main/java/com/robsartin/segue/support/ListKinds.java`,
`src/test/java/com/robsartin/segue/support/ResolutionFilesTest.java`,
`src/test/java/com/robsartin/segue/support/NameFoldTest.java`,
`src/test/java/com/robsartin/segue/support/ListKindsTest.java`.

*Delete:* `src/main/java/com/robsartin/segue/seed/Outcome.java`,
`src/main/java/com/robsartin/segue/seed/ResolutionRow.java`.

*Modify:* `src/main/java/com/robsartin/segue/own/OwnRun.java` (planted, then reverted — no net
change), `src/main/java/com/robsartin/segue/seed/SeedFiles.java`,
`src/main/java/com/robsartin/segue/seed/Names.java`,
`src/main/java/com/robsartin/segue/seed/Expectations.java`,
`src/main/java/com/robsartin/segue/seed/Adjudicator.java`,
`src/main/java/com/robsartin/segue/seed/NameGroup.java`,
`src/main/java/com/robsartin/segue/seed/Decision.java`,
`src/main/java/com/robsartin/segue/seed/SeedRun.java`.

*Test:* `src/test/java/com/robsartin/segue/seed/SeedFilesTest.java`,
`src/test/java/com/robsartin/segue/seed/NamesTest.java`,
`src/test/java/com/robsartin/segue/seed/AdjudicatorTest.java`,
`src/test/java/com/robsartin/segue/seed/SeedRunTest.java`,
`src/test/java/com/robsartin/segue/seed/ExpectationsTest.java`,
`src/test/java/com/robsartin/segue/seed/SeedResolverTest.java` (imports only, wherever they name a
moved type — the grep in Step 3 is the authority on which).

**Interfaces.**

*Consumes:* `com.robsartin.segue.domain.NodeKind`.

*Produces:*

```java
// support/Outcome.java
public enum Outcome { ACCEPTED, REVIEW, UNRESOLVED, MINTED }

// support/ResolutionRow.java
public record ResolutionRow(
    String name, String kind, String status,
    String qid, String label, Outcome confidence, String reason) {}

// support/CsvFile.java
public static List<List<String>> read(Path path)
public static String quote(String value)

// support/ResolutionFiles.java
public static List<ResolutionRow> readRows(Path path)
public static Set<String> alreadyResolved(Collection<Path> paths)
public static void append(Path path, List<ResolutionRow> rows)

// support/NameFold.java
public static String fold(String name)

// support/ListKinds.java
public static Set<String> registered()
public static Set<NodeKind> nodeKinds(String kind)   // empty when the kind is not registered

// seed/SeedFiles.java — what is left
public static List<SeedRow> readList(Path path)

// seed/Names.java — what is left
public static List<String> spellings(String name)
```

None of these returns a `Path`. See the section-1 ruling.

---

- [ ] **Step 1 — prove the id block is free, record the call sites, then PLANT the fence break.**
      Run all four greps, blocking, and paste the output into the task report. **Stop and report if
      either of the first two prints anything.**

  ```
  grep -rn 'Q09033' src docs
  grep -rn 'Q009033' src docs
  grep -rn 'Names\.fold\|ResolutionRow\|Outcome\.\|SeedFiles' src/main src/test
  grep -h '^import com.robsartin' src/main/java/com/robsartin/segue/support/*.java
  ```

  Expected: nothing from the first two; the third is the authority on every import this task
  rewrites; **nothing at all** from the fourth — that absence is the second consequence of the
  section-1 ruling.

  Now plant the break in `src/main/java/com/robsartin/segue/own/OwnRun.java`. Add the import
  beside the others:

  ```java
  import com.robsartin.segue.seed.SeedFiles;
  ```

  and, immediately above the closing brace of the class, a private method nothing calls — so the
  reference is real bytecode rather than a javadoc mention, which leaves no edge at all:

  ```java
    /** PLANTED for the Task 1 Mikado observation. Reverted in Step 2. */
    @SuppressWarnings("unused")
    private static java.util.List<com.robsartin.segue.seed.SeedRow> planted(java.nio.file.Path p) {
      return SeedFiles.readList(p);
    }
  ```

  Then run, blocking:

  ```
  ./gradlew test --tests '*ArchitectureTest*'
  ```

  **Expected red, and the report quotes it:** `theOwnerClaimToolOpensNothingElse` fails with an
  `Architecture Violation` naming `com.robsartin.segue.own.OwnRun` and
  `com.robsartin.segue.seed.SeedFiles`, in the shape

  ```
  Rule 'no classes that reside in a package '..own..' should depend on classes that …' was violated (…):
  Method <com.robsartin.segue.own.OwnRun.planted(java.nio.file.Path)> calls method
  <com.robsartin.segue.seed.SeedFiles.readList(java.nio.file.Path)> in (OwnRun.java:…)
  ```

  If the run is green, **stop and report**: the fence the spec's whole prerequisite rests on is not
  the fence it is described as, and the plan's premise is wrong. If it reds on
  `noPackageCycles` as well, that is expected and is not a second finding.

- [ ] **Step 2 — revert the plant, and prove the revert.** Remove the import and the `planted`
      method. Run `git diff -- src/main/java/com/robsartin/segue/own/OwnRun.java`; it must print
      **nothing**. Then run, blocking:

  ```
  ./gradlew test --tests '*ArchitectureTest*'
  ```

  Expected green. Nothing is committed in Steps 1 or 2.

- [ ] **Step 3 — move `Outcome` and `ResolutionRow`, with `MINTED` still absent.** A pure move: the
      existing `seed` tests are the control.

  Create `src/main/java/com/robsartin/segue/support/Outcome.java`, the three constants and their
  javadoc carried over verbatim from `seed/Outcome.java` with only the package line changed:

  ```java
  package com.robsartin.segue.support;

  /** What a tool concluded about one name. */
  public enum Outcome {
    /** Independent signals agreed. Goes in the mapping file. */
    ACCEPTED,
    /** Something found, nothing convincing. Goes in the review file with the reason. */
    REVIEW,
    /** Wikidata returned no candidate at all under any spelling tried. */
    UNRESOLVED
  }
  ```

  Create `src/main/java/com/robsartin/segue/support/ResolutionRow.java` with the record and its
  javadoc carried over, **and without the `of(SeedRow, Decision)` factory** (Ruling 2):

  ```java
  package com.robsartin.segue.support;

  import java.util.Objects;

  /**
   * One output line of the seven-column mapping and review shape: what the list said, and what was
   * concluded about it.
   *
   * <p>One row per input line rather than one per resolved act, so the mapping can be joined
   * straight back onto the list — including the several spellings that folded onto one answer.
   *
   * <p><b>In {@code support} because two tools read it</b> (#342). The seed tool writes it; the
   * owner-claim tool reads a review file to mint from and appends its own rows to the mapping. The
   * two may not depend on each other — each carries its own ArchUnit fence — so a shape neither
   * owns is the only way they read one file by one rule, exactly as {@code QidList} and {@code
   * KnownListInput} already do.
   */
  public record ResolutionRow(
      String name,
      String kind,
      String status,
      String qid,
      String label,
      Outcome confidence,
      String reason) {

    public ResolutionRow {
      Objects.requireNonNull(name, "name");
      Objects.requireNonNull(kind, "kind");
      Objects.requireNonNull(status, "status");
      Objects.requireNonNull(confidence, "confidence");
      Objects.requireNonNull(reason, "reason");
    }
  }
  ```

  Delete `src/main/java/com/robsartin/segue/seed/Outcome.java` and
  `src/main/java/com/robsartin/segue/seed/ResolutionRow.java`.

  In `src/main/java/com/robsartin/segue/seed/SeedRun.java`, replace the call
  `ResolutionRow.of(row, decision)` with `rowFor(row, decision)` and add, as a private static
  method on `SeedRun`:

  ```java
    /**
     * One output row from one input row and what was decided about it.
     *
     * <p>Here rather than on {@link ResolutionRow}, which moved to {@code support} in #342: this
     * names {@code SeedRow} and {@code Decision}, which are this tool's and stay here.
     */
    private static ResolutionRow rowFor(SeedRow row, Decision decision) {
      return new ResolutionRow(
          row.name(),
          row.kind(),
          row.status(),
          decision.qid(),
          decision.label(),
          decision.outcome(),
          decision.reason());
    }
  ```

  Add `import com.robsartin.segue.support.Outcome;` / `import
  com.robsartin.segue.support.ResolutionRow;` wherever the Step 1 grep found a use — in `src/main`
  that is `SeedFiles`, `SeedRun`, `Adjudicator`, `Decision`; in `src/test` it is `SeedFilesTest`,
  `AdjudicatorTest`, `SeedRunTest` and any other file the grep named. **Change nothing else.**

  Run, blocking:

  ```
  ./gradlew spotlessApply
  ./gradlew compileJava compileTestJava
  ./gradlew test --tests '*seed*' --tests '*ArchitectureTest*'
  ```

  Expected: green, with a non-zero test count. **The control for this step is that no test changed
  but its imports** — `git diff --stat -- src/test` must show only import lines.

  Commit:

  ```
  git status
  git add src/main/java/com/robsartin/segue/support/Outcome.java src/main/java/com/robsartin/segue/support/ResolutionRow.java src/main/java/com/robsartin/segue/seed/Outcome.java src/main/java/com/robsartin/segue/seed/ResolutionRow.java src/main/java/com/robsartin/segue/seed/SeedRun.java src/main/java/com/robsartin/segue/seed/SeedFiles.java src/main/java/com/robsartin/segue/seed/Adjudicator.java src/main/java/com/robsartin/segue/seed/Decision.java src/test/java/com/robsartin/segue/seed/SeedFilesTest.java src/test/java/com/robsartin/segue/seed/AdjudicatorTest.java src/test/java/com/robsartin/segue/seed/SeedRunTest.java
  ```

  (Add any further path the Step 1 grep named, by explicit path. Never `git add -A`.) Message:

  ```
  Move Outcome and ResolutionRow to support for #342

  The owner-claim tool may not read seed (theOwnerClaimToolOpensNothingElse,
  observed red against a planted import and reverted), so the shape both tools
  need moves to the package both may read. A pure move: the seed tests are the
  control and nothing changed but their imports.

  Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
  ```

- [ ] **Step 4 — move the reader, the appender and `alreadyResolved`, with the parser under both.**
      Still a pure move.

  Create `src/main/java/com/robsartin/segue/support/CsvFile.java`. The body is `SeedFiles`'s
  private `read`, `addRow` and `quote` carried over verbatim, with `read` and `quote` made public:

  ```java
  package com.robsartin.segue.support;

  import java.io.IOException;
  import java.io.UncheckedIOException;
  import java.nio.charset.StandardCharsets;
  import java.nio.file.Files;
  import java.nio.file.Path;
  import java.util.ArrayList;
  import java.util.List;
  import java.util.Objects;

  /**
   * RFC 4180, in both directions, for the two readers that share it.
   *
   * <p><b>Its own class because two packages read the same rule</b> (#342). {@code
   * seed.SeedFiles.readList} reads the input list and {@link ResolutionFiles#readRows} reads the
   * seven-column mapping and review shape; a name with a comma in it is quoted in both, so a
   * second copy of this parser is a second place for that to be got wrong. The house rule is that
   * the second copy of a rule is the one a future editor misses.
   */
  public final class CsvFile {

    private CsvFile() {}

    /** Every non-blank line of a CSV file, split into fields. An absent file reads as empty. */
    public static List<List<String>> read(Path path) {
      // … the body of SeedFiles.read, verbatim …
    }

    private static void addRow(List<List<String>> rows, List<String> fields) {
      // … verbatim …
    }

    /** RFC 4180 quoting, applied only where it is needed so the files stay readable. */
    public static String quote(String value) {
      // … verbatim …
    }
  }
  ```

  Create `src/main/java/com/robsartin/segue/support/ResolutionFiles.java`, carrying
  `OUTPUT_HEADER`, `readRows`, `alreadyResolved` and `append` over verbatim, with `read(...)`
  becoming `CsvFile.read(...)`, `quote(...)` becoming `CsvFile.quote(...)` and `Names.fold(...)`
  becoming `NameFold.fold(...)`:

  ```java
  package com.robsartin.segue.support;

  /**
   * The mapping and review files: the seven-column shape
   * {@code name,kind,status,qid,label,confidence,reason}, read and appended.
   *
   * <p><b>None of these files is in this repository, and none of them may be.</b> A list of who
   * someone listens to, reads and watches is exactly the personal data this project treats as the
   * owner's, and the protection is the filesystem rather than repository visibility — this
   * repository is public. The tools are committed; their input and their output are not, {@code
   * *.csv} is gitignored alongside {@code *.db}, and every name in a test, a fixture, a document
   * or a commit message in this project is invented.
   *
   * <p>The output files are also the seed tool's resume ledger. There is no third file recording
   * progress, because a progress file that can disagree with the results is a bug waiting to
   * happen: a name is done when an answer for it has been written down. Since #342 the owner-claim
   * tool appends here too, under {@link Outcome#MINTED}, and {@link #alreadyResolved} reads one of
   * its rows as resolved like any other — so a second batch mint over the same review file mints
   * nothing twice.
   */
  public final class ResolutionFiles { … }
  ```

  In `src/main/java/com/robsartin/segue/seed/SeedFiles.java`, delete `OUTPUT_HEADER`, `readRows`,
  `alreadyResolved`, `append`, `read`, `addRow` and `quote`; keep `INPUT_HEADER` and `readList`,
  with its one call changed to `CsvFile.read(path)`. Trim the class javadoc to the input list, and
  point at `ResolutionFiles` for the output half.

  Update every caller the Step 1 grep named. Run, blocking:

  ```
  ./gradlew spotlessApply
  ./gradlew compileJava compileTestJava
  ./gradlew test --tests '*seed*' --tests '*ArchitectureTest*'
  ```

  Expected green, non-zero count, and again `git diff --stat -- src/test` showing import lines
  only.

  Commit the paths named above, by explicit path, with the subject
  `Move the mapping and review reader to support for #342` and the trailer.

- [ ] **Step 5 — move `Names.fold`.** Create `src/main/java/com/robsartin/segue/support/NameFold.java`
      carrying `STROKE_LETTERS` and `fold` over verbatim, with the class javadoc rewritten to the
      one operation it now holds:

  ```java
  package com.robsartin.segue.support;

  /**
   * The key two spellings of one act share.
   *
   * <p>A pure function on purpose. It is where the judgement lives, and judgement that lives in a
   * pure function can be asserted without a network.
   *
   * <p><b>In {@code support} because two tools fold the same names</b> (#342). The seed tool folds
   * so a re-run does not resolve a name twice; the owner-claim tool folds a review row's name
   * against the mapping so a second batch mint does not mint it twice. Two spellings that fold to
   * one key in one tool and to two in the other would mint a duplicate of something the mapping
   * already carries.
   *
   * <p>{@code seed.Names.spellings} stays where it is: which spellings are worth asking Wikidata
   * about is resolver knowledge, and nothing else asks.
   */
  public final class NameFold { … }
  ```

  Delete `fold` and `STROKE_LETTERS` from `seed/Names.java`, leaving `spellings`,
  `DISAMBIGUATOR_SUFFIX`, `HONORIFICS` and `stripHonorific`, and trim its class javadoc to the one
  operation. Rewrite the callers the Step 1 grep named — `seed.NameGroup`, `seed.Adjudicator`,
  `support.ResolutionFiles`.

  Move the fold half of `src/test/java/com/robsartin/segue/seed/NamesTest.java` **verbatim** into a
  new `src/test/java/com/robsartin/segue/support/NameFoldTest.java` (every test whose body names
  `fold`), leaving the `spellings` tests in `NamesTest`. Do the same for the resolution half of
  `SeedFilesTest` → `src/test/java/com/robsartin/segue/support/ResolutionFilesTest.java`, leaving
  the `readList` tests behind. **Move the methods unchanged — not one assertion is edited** — so
  the moved tests are still the control for the move.

  Run, blocking:

  ```
  ./gradlew spotlessApply
  ./gradlew compileJava compileTestJava
  ./gradlew test --tests '*seed*' --tests '*support*' --tests '*ArchitectureTest*'
  ```

  Expected green, non-zero count. Commit by explicit path, subject
  `Move the name fold to support for #342`, with the trailer.

- [ ] **Step 6 — RED: a `MINTED` row reads back.** In
      `src/test/java/com/robsartin/segue/support/ResolutionFilesTest.java`, add — **and note that
      this test does not name the constant**, which is exactly what makes its failure a runtime
      assertion rather than a compile error:

  ```java
    @Test
    @DisplayName("should read a row the claim tool wrote when its outcome is MINTED")
    void shouldReadAMintedRowWhenTheClaimToolWroteOne() throws Exception {
      Path mapping = dir.resolve("mapping.csv");
      Files.writeString(
          mapping,
          "name,kind,status,qid,label,confidence,reason\n"
              + "Velvet Ossuary,musician,ACCEPTED,Q0903301,Velvet Ossuary,ACCEPTED,agreed\n"
              + "Ashgrove Rounders,musician,,Q001,Ashgrove Rounders,MINTED,minted by the owner\n");

      List<ResolutionRow> rows = ResolutionFiles.readRows(mapping);

      assertThat(rows).hasSize(2);
      assertThat(rows.get(1).confidence().name()).isEqualTo("MINTED");
      assertThat(ResolutionFiles.alreadyResolved(List.of(mapping)))
          .contains(NameFold.fold("Ashgrove Rounders"));
    }
  ```

  Run, blocking:

  ```
  ./gradlew test --tests '*ResolutionFilesTest*'
  ```

  **Expected red, quoted in the report:** `java.lang.IllegalArgumentException: No enum constant
  com.robsartin.segue.support.Outcome.MINTED`, thrown from `ResolutionFiles.readRows` at the
  `Outcome.valueOf` line. If the red is a **compile** error instead, the test named the constant —
  fix the test, not the enum.

- [ ] **Step 7 — GREEN: the fourth outcome.** Add to `support/Outcome.java`:

  ```java
    /**
     * A row the owner minted under ADR 59, written by the claim tool and never by the seed tool.
     *
     * <p>{@code ResolutionFiles.alreadyResolved} treats it as resolved, as it treats every row, so
     * a second batch mint over the same review file mints nothing twice (#342).
     */
    MINTED
  ```

  Re-run `./gradlew test --tests '*ResolutionFilesTest*'`, blocking. Expected green.

- [ ] **Step 8 — the round-trip, with a planted positive control.** Add:

  ```java
    @Test
    @DisplayName("should round-trip a minted row when it is appended and read back")
    void shouldRoundTripAMintedRowWhenItIsAppendedAndReadBack() {
      Path mapping = dir.resolve("mapping.csv");
      ResolutionRow minted =
          new ResolutionRow(
              "Ashgrove Rounders",
              "musician",
              "",
              "Q001",
              "Ashgrove Rounders",
              Outcome.MINTED,
              "minted by the owner — no Wikidata candidate under any spelling (ADR 59)");

      ResolutionFiles.append(mapping, List.of(minted));

      assertThat(ResolutionFiles.readRows(mapping)).containsExactly(minted);
    }
  ```

  It is green the moment it is written, because `append` writes `confidence().name()` and
  `readRows` reads it back through `Outcome.valueOf` — there is no new production code for it to
  drive. **So it is verified by a planted control instead, and the report says so out loud.**
  Plant: in `ResolutionFiles.append`, replace `CsvFile.quote(row.confidence().name())` with
  `CsvFile.quote(Outcome.ACCEPTED.name())`. Run
  `./gradlew test --tests '*ResolutionFilesTest*'`, blocking. **Expected: the round-trip test
  fires**, on `expected: MINTED but was: ACCEPTED` inside the row comparison. Remove the plant,
  re-run, expect green. Quote both runs in the report.

  Commit by explicit path (`support/Outcome.java`, `support/ResolutionFilesTest.java`), subject
  `Add the MINTED outcome for #342`, with the trailer.

- [ ] **Step 9 — the kind table, stub first so the red is an assertion.** Create
      `src/main/java/com/robsartin/segue/support/ListKinds.java` with an **empty** table:

  ```java
  package com.robsartin.segue.support;

  import com.robsartin.segue.domain.NodeKind;
  import java.util.LinkedHashMap;
  import java.util.Locale;
  import java.util.Map;
  import java.util.Objects;
  import java.util.Set;

  /**
   * Which {@link NodeKind}s a list's {@code kind} column value may turn out to be.
   *
   * <p><b>One home, two readers</b> (#342). {@code seed.Expectations} builds each kind's
   * expectation on top of this, and the owner-claim tool's batch mint reads it to decide a minted
   * entity's node kind from the review row's list kind. A second copy would let the two disagree
   * about what a {@code musician} is, in the one place where disagreeing means minting an entity
   * under the wrong kind — into a log that is append-only and is never edited.
   *
   * <p><b>The occupation and class sets stay in {@code seed}.</b> They are resolver knowledge: they
   * exist to tell six same-named humans apart against Wikidata, and nothing outside that tool has
   * anything to ask them.
   *
   * <p><b>A kind that is not registered folds to nothing</b>, rather than to every kind. The two
   * callers read that differently on purpose, and each says why where it reads it:
   * {@code Expectations} treats its own gap as constraining nothing, and the batch mint refuses a
   * file carrying a kind this table has never seen, because such a file is not one the seed tool
   * wrote.
   */
  public final class ListKinds {

    private static final Map<String, Set<NodeKind>> BY_KIND = new LinkedHashMap<>();

    private ListKinds() {}

    private static void put(String kind, NodeKind... kinds) {
      if (BY_KIND.put(kind, Set.of(kinds)) != null) {
        throw new IllegalStateException("two registrations claim the list kind " + kind);
      }
    }

    /** Every list kind this table registers, in registration order. */
    public static Set<String> registered() {
      return Set.copyOf(BY_KIND.keySet());
    }

    /** The node kinds one list kind may be; empty where the table does not register it. */
    public static Set<NodeKind> nodeKinds(String kind) {
      Objects.requireNonNull(kind, "kind");
      return BY_KIND.getOrDefault(kind.trim().toLowerCase(Locale.ROOT), Set.of());
    }
  }
  ```

  Nothing reads it yet, so the suite stays green. **Do not commit here.**

- [ ] **Step 10 — RED: the table's own tests.** New file
      `src/test/java/com/robsartin/segue/support/ListKindsTest.java`:

  ```java
  package com.robsartin.segue.support;

  import static org.assertj.core.api.Assertions.assertThat;

  import com.robsartin.segue.domain.NodeKind;
  import org.junit.jupiter.api.DisplayName;
  import org.junit.jupiter.api.Test;

  /**
   * The list-kind table, which is the authority on which kinds a list may use and what each one
   * may turn out to be. The kinds named here are named because a test that asserts the table
   * against itself asserts nothing; the table is the thing under test.
   */
  class ListKindsTest {

    @Test
    @DisplayName("should register every kind the seed tool's lists use when the table is read")
    void shouldRegisterEveryListKindWhenTheTableIsRead() {
      assertThat(ListKinds.registered())
          .contains(
              "musician", "composer", "conductor", "comedian", "author", "actor", "director",
              "broadcaster", "a-cappella", "tribute", "orchestra", "choir", "ensemble", "org",
              "tv-show", "film", "book", "character", "public-figure", "puppeteer");
    }

    @Test
    @DisplayName("should fold to one node kind when the list kind names exactly one")
    void shouldFoldToOneNodeKindWhenTheListKindNamesExactlyOne() {
      assertThat(ListKinds.nodeKinds("author")).containsExactly(NodeKind.PERSON);
      assertThat(ListKinds.nodeKinds("orchestra")).containsExactly(NodeKind.GROUP);
      assertThat(ListKinds.nodeKinds("book")).containsExactly(NodeKind.WORK);
      assertThat(ListKinds.nodeKinds("character")).containsExactly(NodeKind.CONCEPT);
    }

    @Test
    @DisplayName("should name both node kinds when the list kind is a musician or a comedian")
    void shouldNameBothNodeKindsWhenTheListKindIsAMusicianOrAComedian() {
      assertThat(ListKinds.nodeKinds("musician"))
          .containsExactlyInAnyOrder(NodeKind.PERSON, NodeKind.GROUP);
      assertThat(ListKinds.nodeKinds("comedian"))
          .containsExactlyInAnyOrder(NodeKind.PERSON, NodeKind.GROUP);
    }

    @Test
    @DisplayName("should name exactly two kinds when the kinds that fold to more than one are asked for")
    void shouldNameExactlyTwoKindsWhenTheOnesFoldingToMoreThanOneAreAskedFor() {
      assertThat(ListKinds.registered())
          .filteredOn(kind -> ListKinds.nodeKinds(kind).size() > 1)
          .containsExactlyInAnyOrder("musician", "comedian");
    }

    @Test
    @DisplayName("should fold to nothing when the list kind is not registered")
    void shouldFoldToNothingWhenTheListKindIsNotRegistered() {
      assertThat(ListKinds.nodeKinds("luthier")).isEmpty();
    }

    @Test
    @DisplayName("should ignore case and surrounding space when a list kind is looked up")
    void shouldIgnoreCaseAndSpaceWhenAListKindIsLookedUp() {
      assertThat(ListKinds.nodeKinds("  Musician ")).isEqualTo(ListKinds.nodeKinds("musician"));
    }
  }
  ```

  Run, blocking: `./gradlew test --tests '*ListKindsTest*'`. **Expected red on five of the six**,
  quoted in the report — the first fails on `Expecting … to contain: ["musician", …] but could not
  find … ` against an empty set, and the lookups fail on `Expecting actual not to be empty` /
  `Expecting actual: [] to contain exactly: [PERSON]`. The unregistered-kind test passes
  vacuously; that is expected and is why it is not the only one.

- [ ] **Step 11 — GREEN: fill the table from `Expectations`.** Add the static block to
      `ListKinds`, transcribing the node-kind argument of each `put` in
      `seed/Expectations.java`'s static block **in the same order**, with the comments that explain
      a choice carried across:

  ```java
    static {
      // A musician on this list is as often a band as a person, so both kinds are allowed and the
      // seed tool's occupation check only bites on the ones that turn out to be human.
      put("musician", NodeKind.PERSON, NodeKind.GROUP);
      put("composer", NodeKind.PERSON);
      put("conductor", NodeKind.PERSON);
      put("comedian", NodeKind.PERSON, NodeKind.GROUP);
      put("author", NodeKind.PERSON);
      put("actor", NodeKind.PERSON);
      put("director", NodeKind.PERSON);
      put("broadcaster", NodeKind.PERSON);
      put("a-cappella", NodeKind.GROUP);
      put("tribute", NodeKind.GROUP);
      put("orchestra", NodeKind.GROUP);
      put("choir", NodeKind.GROUP);
      put("ensemble", NodeKind.GROUP);
      put("org", NodeKind.GROUP);
      put("tv-show", NodeKind.WORK);
      put("film", NodeKind.WORK);
      put("book", NodeKind.WORK);
      // Carry across, verbatim, the comment `Expectations` already writes above this kind.
      put("character", NodeKind.CONCEPT);
      put("public-figure", NodeKind.PERSON);
      put("puppeteer", NodeKind.PERSON);
    }
  ```

  Re-run `./gradlew test --tests '*ListKindsTest*'`, blocking. Expected green, six tests.

- [ ] **Step 12 — `Expectations` reads the table, so there is one copy.** In
      `seed/Expectations.java`: drop the `Set<NodeKind>` argument from `put`, and read it from
      `ListKinds` instead —

  ```java
    private static void put(String kind, Set<String> occupations, Set<String> classes) {
      Set<NodeKind> kinds = ListKinds.nodeKinds(kind);
      if (kinds.isEmpty()) {
        throw new IllegalStateException(
            "the list kind " + kind + " is not registered in support.ListKinds");
      }
      Expectation prior = BY_KIND.put(kind, new Expectation(kinds, occupations, classes));
      if (prior != null) {
        throw new IllegalStateException("two expectations claim the kind " + kind);
      }
    }
  ```

  — rewrite each of the twenty `put` calls to drop its `EnumSet.of(...)` argument, and close the
  static block with the agreement check that makes the two tables one:

  ```java
      // One copy of "which list kinds exist", in support.ListKinds (#342). This fails class
      // initialisation rather than letting a kind registered in one table and not the other reach
      // a run: the batch mint refuses a kind ListKinds does not hold, and an expectation for a
      // kind this table alone knows would never be consulted.
      if (!BY_KIND.keySet().equals(ListKinds.registered())) {
        throw new IllegalStateException(
            "the expectations and support.ListKinds disagree about which list kinds exist: "
                + BY_KIND.keySet()
                + " against "
                + ListKinds.registered());
      }
  ```

  Add the import and remove `EnumSet` if nothing else in the file uses it (`ANY_KIND` and
  `forKinds` still do — check before deleting). Run, blocking:

  ```
  ./gradlew spotlessApply
  ./gradlew compileJava compileTestJava
  ./gradlew test --tests '*seed*' --tests '*support*' --tests '*ArchitectureTest*'
  ```

  Expected green. **`ExpectationsTest`, unedited, is the control for this refactor** — it asserts
  each kind's node kinds through `Expectations.forKind`, and it still does.

- [ ] **Step 13 — plant the positive control for the agreement check.** In `ListKinds`'s static
      block, comment out `put("choir", NodeKind.GROUP);`. Run
      `./gradlew test --tests '*ExpectationsTest*'`, blocking. **Expected: every test in the class
      errors on class initialisation**, with
      `IllegalStateException: the list kind choir is not registered in support.ListKinds` (the
      `put` guard fires before the set comparison does). Restore the line, re-run, expect green.
      Quote both runs.

      Then plant it the other way: add `put("luthier", NodeKind.PERSON);` to `ListKinds` and
      nothing to `Expectations`. Expected:
      `IllegalStateException: the expectations and support.ListKinds disagree about which list
      kinds exist: …`. Remove the plant, re-run, expect green. Quote both runs. **Both directions,
      because the two guards are different guards.**

- [ ] **Step 14 — one-line check and commit.** Run, blocking:

  ```
  ./gradlew spotlessApply
  grep -nE '\{@(code|link)[^}]*$' src/main/java/com/robsartin/segue/support/*.java src/main/java/com/robsartin/segue/seed/*.java
  ./gradlew compileJava compileTestJava
  ./gradlew test --tests '*seed*' --tests '*support*' --tests '*ArchitectureTest*'
  ```

  The `grep` must print nothing. Commit by explicit path (`support/ListKinds.java`,
  `support/ListKindsTest.java`, `seed/Expectations.java`), subject
  `Move the list-kind table to support for #342`, with the trailer.

---

## Task 2 — `mint --review <review> --mapping <mapping>`

**Files:**

*Modify:* `src/main/java/com/robsartin/segue/own/OwnCli.java`,
`src/main/java/com/robsartin/segue/own/OwnRun.java`.

*Test:* `src/test/java/com/robsartin/segue/own/OwnCliTest.java`,
`src/test/java/com/robsartin/segue/own/OwnRunTest.java`.

**Interfaces.**

*Consumes:* `support.ResolutionFiles.readRows`, `support.ResolutionFiles.alreadyResolved`,
`support.ResolutionFiles.append`, `support.ResolutionRow`, `support.Outcome`,
`support.NameFold.fold`, `support.ListKinds.nodeKinds`, `domain.LocalEntity.minted`,
`ingest.IngestService.claim`.

*Produces:*

```java
// OwnCli
public sealed interface Options permits Single, Batch { Path database(); boolean dryRun(); }
public sealed interface Single extends Options permits Mint, Assert, Merge {}
public sealed interface Batch extends Options permits MintBatch {}          // Task 3 widens this
public record MintBatch(Path database, Path review, Path mapping, boolean dryRun) implements Batch {}

// OwnRun
public LoggedAssertion run(Single options, Consumer<String> notes)          // body unchanged
public List<LoggedAssertion> runBatch(Batch batch, Consumer<String> notes)  // new
```

**Report wording.** The per-row line and the two closing sentences are the spec's, verbatim and
byte-identical to what a single `mint` prints today. The skip lines, the totals line and the
refusals are this plan's (Ruling 5) and are written out below so nobody invents one.

---

- [ ] **Step 1 — split the sealed hierarchy, green, no behaviour.** In `OwnCli.java`:

  - `Options` becomes `public sealed interface Options permits Single, Batch`, keeping
    `database()` and `dryRun()` and its existing javadoc, with a paragraph added saying why the two
    sub-interfaces exist:

    ```java
     * <p><b>{@link Single} and {@link Batch}, because one run claims one thing or many</b> (#342).
     * {@code OwnRun.run} answers a single operation and returns the one claim it appended;
     * {@code OwnRun.runBatch} answers a file and returns a list. Splitting the hierarchy is what
     * keeps both switches total with no {@code default} arm: a fourth single operation or a third
     * batch shape fails to compile until it is decided what it does, which is the reason {@code
     * Options} was sealed in the first place.
    ```

  - `Single` and `Batch` are added as sealed marker interfaces extending `Options`, each with one
    javadoc sentence.
  - `Mint`, `Assert` and `Merge` change `implements Options` to `implements Single`. Nothing else
    about them changes.
  - `MintBatch` is added:

    ```java
    /**
     * "Everything in this review file that Wikidata had nothing for, minted in one run."
     *
     * <p>The two files together, never one: the review file says what to mint and the mapping file
     * is both the skip list and where the minted ids go, so a run given one of them either mints
     * what it has already minted or mints into nowhere.
     */
    public record MintBatch(Path database, Path review, Path mapping, boolean dryRun)
        implements Batch {

      public MintBatch {
        Objects.requireNonNull(database, "database");
        Objects.requireNonNull(review, "review");
        Objects.requireNonNull(mapping, "mapping");
      }
    }
    ```

  - `OwnRun.run`'s parameter narrows to `Single` (Ruling 3). Its body, its switch and its report are
    untouched.
  - `OwnCli.run`'s tail becomes:

    ```java
        try (AssertionLog assertions = new SqliteAssertionLog(options.database())) {
          OwnRun runner = new OwnRun(assertions, Clock.systemUTC());
          switch (options) {
            case Single single -> runner.run(single, log::info);
            case Batch batch -> runner.runBatch(batch, log::info);
          }
        }
    ```

  - `OwnRun.runBatch` is added with a body that compiles and is deliberately wrong — it returns an
    empty list and reports nothing — so Step 3's reds are assertion failures rather than compile
    errors:

    ```java
      /**
       * Make every claim one file asks for, of one kind.
       *
       * @return the claims that were appended - or, on a dry run, the ones that would have been
       */
      public List<LoggedAssertion> runBatch(Batch batch, Consumer<String> notes) {
        Objects.requireNonNull(batch, "batch");
        Objects.requireNonNull(notes, "notes");
        return switch (batch) {
          case MintBatch mint -> List.of();
        };
      }
    ```

  - `USAGE` gains the batch shape, between the two `mint` forms:

    ```java
      private static final String USAGE =
          "usage: mint --kind <"
              + kinds()
              + "> --label \"<name>\""
              + " | mint --review <review.csv> --mapping <mapping.csv>"
              + " | assert --from <Q…> --to <Q…> --type <CODE>"
              + " | merge --local <Q00…> --canonical <Q…>"
              + " --db <segue.db> [--dry-run]";
    ```

  Nothing parses into a `MintBatch` yet. Run, blocking:

  ```
  ./gradlew spotlessApply
  ./gradlew compileJava compileTestJava
  ./gradlew test --tests '*own*' --tests '*ArchitectureTest*'
  ```

  Expected green, non-zero count. **`OwnCliTest` and `OwnRunTest` unedited are the control that the
  three single operations are untouched.** No commit.

- [ ] **Step 2 — RED: the parse.** Add to `OwnCliTest`:

  ```java
    @Test
    @DisplayName("should read both files when minting from a review file")
    void shouldReadBothFilesWhenMintingFromAReviewFile() {
      OwnCli.MintBatch batch =
          (OwnCli.MintBatch)
              parse("mint", "--review", "/lists/review.csv", "--mapping", "/lists/qids.csv");

      assertThat(batch.review()).isEqualTo(Path.of("/lists/review.csv"));
      assertThat(batch.mapping()).isEqualTo(Path.of("/lists/qids.csv"));
      assertThat(batch.dryRun()).isFalse();
      assertThat(batch.database()).isEqualTo(Path.of(DATABASE));
    }

    @Test
    @DisplayName("should refuse naming both flags when only the review file is named")
    void shouldRefuseNamingBothFlagsWhenOnlyTheReviewFileIsNamed() {
      assertThatIllegalArgumentException()
          .isThrownBy(() -> parse("mint", "--review", "/lists/review.csv"))
          .withMessageContaining("--review and --mapping")
          .withMessageContaining("--mapping was not given");
    }

    @Test
    @DisplayName("should refuse naming both flags when only the mapping file is named")
    void shouldRefuseNamingBothFlagsWhenOnlyTheMappingFileIsNamed() {
      assertThatIllegalArgumentException()
          .isThrownBy(() -> parse("mint", "--mapping", "/lists/qids.csv"))
          .withMessageContaining("--review and --mapping")
          .withMessageContaining("--review was not given");
    }

    @Test
    @DisplayName("should refuse when a single mint's kind is given with a review file")
    void shouldRefuseWhenASingleMintsKindIsGivenWithAReviewFile() {
      assertThatIllegalArgumentException()
          .isThrownBy(
              () ->
                  parse(
                      "mint", "--kind", "WORK", "--review", "/lists/review.csv", "--mapping",
                      "/lists/qids.csv"))
          .withMessageContaining("--kind")
          .withMessageContaining("--review and --mapping");
    }

    @Test
    @DisplayName("should refuse when a single mint's label is given with a review file")
    void shouldRefuseWhenASingleMintsLabelIsGivenWithAReviewFile() {
      assertThatIllegalArgumentException()
          .isThrownBy(
              () ->
                  parse(
                      "mint", "--label", "x", "--review", "/lists/review.csv", "--mapping",
                      "/lists/qids.csv"))
          .withMessageContaining("--label")
          .withMessageContaining("--review and --mapping");
    }

    @Test
    @DisplayName("should parse when the named files do not exist, because parse opens nothing")
    void shouldParseWhenTheNamedFilesDoNotExist() {
      Path absent = dir.resolve("nothing-here.csv");

      OwnCli.MintBatch batch =
          (OwnCli.MintBatch)
              parse("mint", "--review", absent.toString(), "--mapping", absent.toString());

      assertThat(batch.review()).isEqualTo(absent);
      assertThat(Files.exists(absent)).isFalse();
    }
  ```

  Run `./gradlew test --tests '*OwnCliTest*'`, blocking. **Expected red, quoted in the report:** the
  first and last fail with `class com.robsartin.segue.own.OwnCli$Mint cannot be cast to class
  com.robsartin.segue.own.OwnCli$MintBatch`… — no. They fail earlier, with
  `java.lang.IllegalArgumentException: --kind is required.`, because `mint` still requires `--kind`.
  The three refusal tests fail on the message: `--kind is required.` does not contain
  `--review and --mapping`. **Read the actual text and quote it**; if any of the six passes, the
  test is not testing what it says.

- [ ] **Step 3 — GREEN: the parse branch.** In `OwnCli.java`, `mint` becomes a branch and two
      helpers are added:

  ```java
    private static Options mint(Path database, Map<String, String> values, boolean dryRun) {
      // The batch is recognised by either of its two flags rather than by both, so naming one
      // alone is refused as "you meant the batch and forgot a file" and never as "--kind is
      // required" - which is the refusal a single mint would give, naming a flag that belongs to
      // the other shape entirely.
      if (values.containsKey("--review") || values.containsKey("--mapping")) {
        return mintBatch(database, values, dryRun);
      }
      NodeKind kind = kind(required(values, "--kind"));
      String label = required(values, "--label");
      if (label.isBlank()) {
        throw usage("--label must say what the entity is called");
      }
      refuseTheRest(values);
      return new Mint(database, kind, label, dryRun);
    }

    private static MintBatch mintBatch(Path database, Map<String, String> values, boolean dryRun) {
      refuseTheOtherShape(values, "a single mint, not to --review and --mapping", "--kind", "--label");
      String review = values.remove("--review");
      String mapping = values.remove("--mapping");
      if (review == null || mapping == null) {
        throw usage(
            "--review and --mapping are required together — "
                + (review == null ? "--review" : "--mapping")
                + " was not given");
      }
      refuseTheRest(values);
      return new MintBatch(database, Path.of(review), Path.of(mapping), dryRun);
    }

    /**
     * Refuse a flag belonging to the other shape of the same operation.
     *
     * <p>{@link #refuseTheRest} would refuse these too, as "unknown option --kind for this
     * operation" - and that sentence is wrong here, because {@code --kind} is an option for this
     * operation, in its other shape. Naming both shapes is what tells the operator which of the
     * two they typed half of.
     */
    private static void refuseTheOtherShape(
        Map<String, String> values, String instead, String... flags) {
      for (String flag : flags) {
        if (values.containsKey(flag)) {
          throw usage(flag + " belongs to " + instead);
        }
      }
    }
  ```

  `parse`'s switch arm for `"mint"` already calls `mint(...)`; its return type widens from `Mint` to
  `Options`, which the arm already accepts.

  Re-run `./gradlew test --tests '*OwnCliTest*'`, blocking. Expected green, non-zero count.

- [ ] **Step 4 — RED: the batch itself.** Add to `OwnRunTest`: `@TempDir Path dir;`, a helper that
      writes a review file, and the eight tests. The helper:

  ```java
    private static final String REVIEW_HEADER = "name,kind,status,qid,label,confidence,reason";

    private Path reviewFile(String... rows) throws Exception {
      Path path = dir.resolve("review.csv");
      Files.writeString(path, REVIEW_HEADER + "\n" + String.join("\n", rows) + "\n");
      return path;
    }

    private OwnCli.MintBatch batch(Path review, Path mapping, boolean dryRun) {
      return new OwnCli.MintBatch(UNUSED, review, mapping, dryRun);
    }
  ```

  The tests:

  ```java
    @Test
    @DisplayName("should mint only the unresolved rows when minting from a review file")
    void shouldMintOnlyTheUnresolvedRowsWhenMintingFromAReviewFile() throws Exception {
      Path review =
          reviewFile(
              "Ashgrove Rounders,author,,,,UNRESOLVED,no Wikidata candidate under any spelling",
              "Velvet Ossuary,author,,Q0903301,Velvet Ossuary,REVIEW,two plausible candidates",
              "Bramble Sons,author,,Q0903302,Bramble Sons,ACCEPTED,agreed");
      Path mapping = dir.resolve("mapping.csv");

      List<LoggedAssertion> claims = run.runBatch(batch(review, mapping, true), notes::add);

      assertThat(claims).singleElement().isInstanceOf(LocalEntity.class);
      assertThat(((LocalEntity) claims.get(0)).label()).isEqualTo("Ashgrove Rounders");
      assertThat(((LocalEntity) claims.get(0)).kind()).isEqualTo(NodeKind.PERSON);
      assertThat(notes)
          .anyMatch(
              note ->
                  note.contains("Ashgrove Rounders")
                      && note.contains("(PERSON) — no source claims this entity; you are the source"));
      assertThat(notes).noneMatch(note -> note.startsWith("minting") && note.contains("Velvet"));
    }

    @Test
    @DisplayName("should skip a name the mapping already carries when minting from a review file")
    void shouldSkipANameTheMappingAlreadyCarriesWhenMintingFromAReviewFile() throws Exception {
      Path review =
          reviewFile("Ashgrove Rounders,author,,,,UNRESOLVED,no Wikidata candidate under any spelling");
      Path mapping = dir.resolve("mapping.csv");
      // The mapping's spelling differs; the fold is what makes it the same act.
      Files.writeString(
          mapping,
          REVIEW_HEADER + "\nThe Ashgrove Rounders,author,,Q001,The Ashgrove Rounders,MINTED,minted\n");

      List<LoggedAssertion> claims = run.runBatch(batch(review, mapping, true), notes::add);

      assertThat(claims).isEmpty();
      assertThat(notes)
          .contains("skipping \"Ashgrove Rounders\" — the mapping already carries a row for it");
    }

    @Test
    @DisplayName("should print the single-mint command when the list kind folds to more than one node kind")
    void shouldPrintTheSingleMintCommandWhenTheListKindFoldsToMoreThanOneNodeKind()
        throws Exception {
      Path review =
          reviewFile("Velvet Ossuary,musician,,,,UNRESOLVED,no Wikidata candidate under any spelling");
      Path mapping = dir.resolve("mapping.csv");

      List<LoggedAssertion> claims = run.runBatch(batch(review, mapping, true), notes::add);

      assertThat(claims).isEmpty();
      assertThat(notes)
          .anyMatch(
              note ->
                  note.startsWith("skipping \"Velvet Ossuary\"")
                      && note.contains("--kind <GROUP|PERSON>")
                      && note.contains("--label 'Velvet Ossuary'"));
    }

    @Test
    @DisplayName("should refuse the whole run before any append when a list kind is not registered")
    void shouldRefuseTheWholeRunBeforeAnyAppendWhenAListKindIsNotRegistered() throws Exception {
      Path review =
          reviewFile(
              "Ashgrove Rounders,author,,,,UNRESOLVED,no Wikidata candidate under any spelling",
              "A Luthier,luthier,,,,UNRESOLVED,no Wikidata candidate under any spelling");
      Path mapping = dir.resolve("mapping.csv");

      assertThatThrownBy(() -> run.runBatch(batch(review, mapping, false), notes::add))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("A Luthier")
          .hasMessageContaining("luthier")
          .hasMessageContaining("nothing was appended");
      assertThat(log.readAll()).isEmpty();
      assertThat(Files.exists(mapping)).isFalse();
    }

    @Test
    @DisplayName("should allocate ids in sequence when several rows are minted")
    void shouldAllocateIdsInSequenceWhenSeveralRowsAreMinted() throws Exception {
      Path review =
          reviewFile(
              "Ashgrove Rounders,author,,,,UNRESOLVED,none",
              "Bramble Sons,author,,,,UNRESOLVED,none",
              "Halcyon Press,org,,,,UNRESOLVED,none");
      Path mapping = dir.resolve("mapping.csv");

      List<LoggedAssertion> claims = run.runBatch(batch(review, mapping, false), notes::add);

      assertThat(claims).hasSize(3);
      assertThat(claims.stream().map(c -> ((LocalEntity) c).qid()).toList())
          .containsExactly("Q001", "Q002", "Q003");
    }

    @Test
    @DisplayName("should write a MINTED mapping row for each mint when the run is not a dry run")
    void shouldWriteAMintedMappingRowForEachMintWhenTheRunIsNotADryRun() throws Exception {
      Path review = reviewFile("Ashgrove Rounders,author,ACTIVE,,,UNRESOLVED,none");
      Path mapping = dir.resolve("mapping.csv");

      run.runBatch(batch(review, mapping, false), notes::add);

      assertThat(ResolutionFiles.readRows(mapping))
          .containsExactly(
              new ResolutionRow(
                  "Ashgrove Rounders",
                  "author",
                  "ACTIVE",
                  "Q001",
                  "Ashgrove Rounders",
                  Outcome.MINTED,
                  "minted by the owner — no Wikidata candidate under any spelling (ADR 59)"));
      assertThat(notes).contains("appended Q001");
    }

    @Test
    @DisplayName("should append nothing when the batch mint is a dry run")
    void shouldAppendNothingWhenTheBatchMintIsADryRun() throws Exception {
      Path review = reviewFile("Ashgrove Rounders,author,,,,UNRESOLVED,none");
      Path mapping = dir.resolve("mapping.csv");

      run.runBatch(batch(review, mapping, true), notes::add);

      assertThat(log.readAll()).isEmpty();
      assertThat(Files.exists(mapping)).isFalse();
      assertThat(notes).contains("dry run: nothing was appended");
    }

    @Test
    @DisplayName("should refuse when the review file is not there")
    void shouldRefuseWhenTheReviewFileIsNotThere() {
      Path absent = dir.resolve("no-such-review.csv");

      assertThatThrownBy(() -> run.runBatch(batch(absent, dir.resolve("m.csv"), true), notes::add))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("no review file at");
    }
  ```

  Run `./gradlew test --tests '*OwnRunTest*'`, blocking. **Expected red on all eight**, against the
  stub that returns `List.of()` and reports nothing: `Expecting actual not to be empty`,
  `Expecting … to contain: ["skipping …"] but could not find …`, and
  `Expecting code to raise a throwable`. **Quote the actual lines.**

- [ ] **Step 4a — Ruling: the review file must exist.** Recorded here because the spec does not say
      it. `ResolutionFiles.readRows` reads an absent file as **empty**, inherited from the seed
      tool, where absent legitimately means "no answers yet". Here it would turn a mistyped
      `--review` into a run that reports nothing and mints nothing, with exit 0. So `runBatch`
      refuses an absent review file by name. The **mapping** file may legitimately be absent —
      `ResolutionFiles.append` writes the header when it creates one — and is not checked.

- [ ] **Step 5 — GREEN: the batch mint.** In `OwnRun.java`, replace `runBatch`'s stub arm with
      `case MintBatch mint -> mintFromReview(mint, notes);` and add:

  ```java
    /**
     * Mint every row of a review file Wikidata had nothing for.
     *
     * <p><b>Selection is {@code UNRESOLVED} and nothing else.</b> A {@code REVIEW} row carries a
     * plausible candidate the adjudicator could not choose, and minting one would put a second
     * entity in the graph for something that is already in Wikidata - which is the one mistake
     * this tool cannot take back, since the log is append-only and never edited and the repair is
     * a retraction plus a merge.
     *
     * <p><b>The report is whole before the first append</b>, {@code run}'s rule for {@code run}'s
     * reason, and the appends are then interleaved per row: the claim, then its mapping row. A
     * failure between the two leaves at most one mint without its mapping row, and the note naming
     * the id it appended is what lets the owner write that row by hand.
     */
    private List<LoggedAssertion> mintFromReview(MintBatch batch, Consumer<String> notes) {
      if (!Files.exists(batch.review())) {
        throw new IllegalArgumentException(
            "no review file at " + batch.review() + " — nothing to mint from");
      }
      List<ResolutionRow> review = ResolutionFiles.readRows(batch.review());
      refuseAnUnregisteredKind(batch, review);

      Set<String> resolved = ResolutionFiles.alreadyResolved(List.of(batch.mapping()));
      Set<String> named = everNamed(log.readAll());

      List<ResolutionRow> rows = new ArrayList<>();
      List<String> ids = new ArrayList<>();
      List<String> skipped = new ArrayList<>();
      int review$ = 0;
      for (ResolutionRow row : review) {
        if (row.confidence() == Outcome.REVIEW) {
          review$++;
          continue;
        }
        if (row.confidence() != Outcome.UNRESOLVED) {
          continue;
        }
        if (resolved.contains(NameFold.fold(row.name()))) {
          skipped.add(
              "skipping \"" + row.name() + "\" — the mapping already carries a row for it");
          continue;
        }
        Set<NodeKind> kinds = ListKinds.nodeKinds(row.kind());
        if (kinds.size() > 1) {
          skipped.add(
              "skipping \""
                  + row.name()
                  + "\" — the list kind "
                  + row.kind()
                  + " folds to more than one node kind, so this row is yours to type:"
                  + " ./gradlew ownClaim --args=\"mint --db "
                  + batch.database()
                  + " --kind <"
                  + kinds.stream().map(Enum::name).sorted().collect(Collectors.joining("|"))
                  + "> --label '"
                  + row.name()
                  + "'\"");
          continue;
        }
        String qid = anIdNothingHasNamed(named);
        named.add(qid);
        rows.add(row);
        ids.add(qid);
        notes.accept(
            "minting "
                + qid
                + " \""
                + row.name()
                + "\" ("
                + kinds.iterator().next()
                + ") — no source claims this entity; you are the source");
      }
      skipped.forEach(notes);
      notes.accept(
          rows.size()
              + " to mint, "
              + skipped.size()
              + " to skip; "
              + review$
              + " REVIEW rows are not minted — each carries a plausible candidate, and minting one"
              + " would duplicate a real item");

      List<LoggedAssertion> claims = new ArrayList<>();
      for (int i = 0; i < rows.size(); i++) {
        claims.add(
            LocalEntity.minted(
                ids.get(i),
                ListKinds.nodeKinds(rows.get(i).kind()).iterator().next(),
                rows.get(i).name(),
                clock.instant()));
      }
      if (batch.dryRun()) {
        notes.accept("dry run: nothing was appended");
        return List.copyOf(claims);
      }
      for (int i = 0; i < claims.size(); i++) {
        IngestService.claim(log, claims.get(i));
        notes.accept("appended " + ids.get(i));
        ResolutionFiles.append(batch.mapping(), List.of(mappingRow(rows.get(i), ids.get(i))));
      }
      notes.accept(
          "appended. The running graph is rebuilt from the log at the next boot (ADR 24), so a"
              + " server that is up does not see this claim until it restarts");
      return List.copyOf(claims);
    }

    /**
     * Refuse the whole file, before any report and any append, for a kind the table has never seen.
     *
     * <p>Not a skip: {@code support.ListKinds} holds every kind the seed tool writes, so a row
     * carrying another one means this is not a file {@code resolveNames} wrote, and nothing else
     * in the file can be trusted to be what it looks like either.
     */
    private static void refuseAnUnregisteredKind(MintBatch batch, List<ResolutionRow> review) {
      for (ResolutionRow row : review) {
        if (ListKinds.nodeKinds(row.kind()).isEmpty()) {
          throw new IllegalArgumentException(
              batch.review()
                  + " has the row \""
                  + row.name()
                  + "\" with the list kind "
                  + row.kind()
                  + ", which support.ListKinds does not register — this is not a file resolveNames"
                  + " wrote; nothing was appended");
        }
      }
    }

    /** The mapping row one mint writes: the review row, plus the id and ADR 59's reason. */
    private static ResolutionRow mappingRow(ResolutionRow row, String qid) {
      return new ResolutionRow(
          row.name(),
          row.kind(),
          row.status(),
          qid,
          row.name(),
          Outcome.MINTED,
          "minted by the owner — no Wikidata candidate under any spelling (ADR 59)");
    }
  ```

  Rename the local `review$` to `reviewRows` when writing it — it is written here only to keep the
  two names apart in this excerpt.

  Change `anIdNothingHasNamed` to take the set, so one read of the log serves the whole batch, and
  leave `mintEntity` calling it with a freshly derived set:

  ```java
    private static String anIdNothingHasNamed(Set<String> named) {
      int n = 1;
      while (named.contains("Q00" + n)) {
        n++;
      }
      return "Q00" + n;
    }
  ```

  and in `mintEntity`, `String qid = anIdNothingHasNamed(everNamed(logged));`. Carry the existing
  javadoc across unchanged and add one sentence: *"A batch passes the same set through every row,
  adding each id as it allocates it, so two mints in one run cannot be handed the same number."*

  Re-run `./gradlew test --tests '*OwnRunTest*'`, blocking. Expected green, non-zero count.

- [ ] **Step 6 — four planted positive controls, each observed and removed.** Run
      `./gradlew test --tests '*OwnRunTest*'` blocking after each plant and after each removal, and
      quote both runs for each.

  | plant | expected to fire |
  | --- | --- |
  | in `refuseAnUnregisteredKind`, change the `if` to `if (false)` | `shouldRefuseTheWholeRunBeforeAnyAppendWhenAListKindIsNotRegistered` — `Expecting code to raise a throwable` |
  | replace `ResolutionFiles.alreadyResolved(List.of(batch.mapping()))` with `Set.<String>of()` | `shouldSkipANameTheMappingAlreadyCarriesWhenMintingFromAReviewFile` |
  | change `if (kinds.size() > 1)` to `if (false)` | `shouldPrintTheSingleMintCommandWhenTheListKindFoldsToMoreThanOneNodeKind` |
  | change `if (batch.dryRun())` to `if (false)` | `shouldAppendNothingWhenTheBatchMintIsADryRun` |

  A plant that does **not** fire is a finding: the test is not testing what it says, and the report
  says so rather than moving on.

- [ ] **Step 7 — one-line check and commit.** Run, blocking:

  ```
  ./gradlew spotlessApply
  grep -nE '\{@(code|link)[^}]*$' src/main/java/com/robsartin/segue/own/*.java src/test/java/com/robsartin/segue/own/*.java
  ./gradlew compileJava compileTestJava
  ./gradlew test --tests '*own*' --tests '*ArchitectureTest*'
  ```

  The `grep` must print nothing. Commit:

  ```
  git status
  git add src/main/java/com/robsartin/segue/own/OwnCli.java src/main/java/com/robsartin/segue/own/OwnRun.java src/test/java/com/robsartin/segue/own/OwnCliTest.java src/test/java/com/robsartin/segue/own/OwnRunTest.java
  ```

  Message:

  ```
  Mint a review file's unresolved rows in one run for #342

  ownClaim mint --review <file> --mapping <file> mints every row Wikidata had
  nothing for, reports before it appends, skips a name the mapping already
  carries and a list kind that folds to two node kinds, and refuses a kind the
  table does not register before anything is written.

  Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
  ```

---

## Task 3 — `assert --file <claims>`

**Files:**

*Create:* `src/main/java/com/robsartin/segue/own/ClaimFile.java`,
`src/test/java/com/robsartin/segue/own/ClaimFileTest.java`.

*Modify:* `src/main/java/com/robsartin/segue/own/OwnCli.java`,
`src/main/java/com/robsartin/segue/own/OwnRun.java`.

*Test:* `src/test/java/com/robsartin/segue/own/OwnCliTest.java`,
`src/test/java/com/robsartin/segue/own/OwnRunTest.java`.

**Interfaces.**

*Consumes:* `domain.Qid.looksLikeAQid`, `domain.EdgeTypes.byCode`, `domain.OwnerEdge.claimed`,
`domain.Equivalences`, `domain.Retractions`, `ingest.IngestService.claim`.

*Produces:*

```java
// own/ClaimFile.java
public record Row(int line, String fromQid, String toQid, String typeCode) {}
public static List<Row> read(Path path)

// OwnCli
public sealed interface Batch extends Options permits MintBatch, AssertFile {}
public record AssertFile(Path database, Path file, boolean dryRun) implements Batch {}
```

**Rulings recorded here.**

- **The reader lives in `own`.** Nothing else reads `from,to,type` — `QidList`'s own javadoc is the
  precedent for the opposite case, a shape more than one tool reads.
- **No quoting, and the parser is `String.split`.** Every field is a qid or an `EdgeTypes` code, so
  there is nothing a comma could be inside. Reading the file line by line rather than through
  `support.CsvFile` is what keeps the **line number**, which every refusal names.
- **A duplicate is an owner edge the log already carries, and not a sourced one.** The spec says
  "a row whose edge the log already carries surviving". Restricting it to `OwnerEdge` is the
  narrower reading and the defensible one: a sourced `AssertionRecord` and an owner edge carry
  different provenance and are different rows — ADR 59's whole point is that the owner's claim is a
  third layer — so skipping a row because a *source* said the same thing would silently decline to
  record that the owner says it too. The duplicate the spec means is the owner's own claim repeated.

---

- [ ] **Step 1 — RED: the reader.** New file
      `src/test/java/com/robsartin/segue/own/ClaimFileTest.java`. Write it **before** `ClaimFile`
      exists is a compile error, so create `ClaimFile` first with a stub that compiles and is
      deliberately wrong — it returns `List.of()` for every file:

  ```java
  package com.robsartin.segue.own;

  import java.nio.file.Path;
  import java.util.List;
  import java.util.Objects;

  /** The owner's claims file: {@code from,to,type}, one edge per row. */
  public final class ClaimFile {

    /** One edge the owner wrote down, and the line it is on so a refusal can be opened. */
    public record Row(int line, String fromQid, String toQid, String typeCode) {}

    private ClaimFile() {}

    public static List<Row> read(Path path) {
      Objects.requireNonNull(path, "path");
      return List.of();
    }
  }
  ```

  Then the test:

  ```java
  package com.robsartin.segue.own;

  import static org.assertj.core.api.Assertions.assertThat;
  import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

  import java.nio.file.Files;
  import java.nio.file.Path;
  import java.util.List;
  import org.junit.jupiter.api.DisplayName;
  import org.junit.jupiter.api.Test;
  import org.junit.jupiter.api.io.TempDir;

  /**
   * The claims file, read and refused. The ids here are stand-ins with the project's leading zero
   * and an id the owner minted with two; no file in this class is anybody's real list.
   */
  class ClaimFileTest {

    @TempDir Path dir;

    private Path file(String contents) throws Exception {
      Path path = dir.resolve("claims.csv");
      Files.writeString(path, contents);
      return path;
    }

    @Test
    @DisplayName("should read every edge when the file is well formed")
    void shouldReadEveryEdgeWhenTheFileIsWellFormed() throws Exception {
      Path path =
          file(
              "from,to,type\n"
                  + "# the book's author, which nothing in the graph states\n"
                  + "Q00903301,Q0903301,AUTHORED\n"
                  + "\n"
                  + "Q0903302,Q0903301,INFLUENCED_BY\n");

      List<ClaimFile.Row> rows = ClaimFile.read(path);

      assertThat(rows)
          .containsExactly(
              new ClaimFile.Row(3, "Q00903301", "Q0903301", "AUTHORED"),
              new ClaimFile.Row(5, "Q0903302", "Q0903301", "INFLUENCED_BY"));
    }

    @Test
    @DisplayName("should refuse naming the row when a row has fewer than three fields")
    void shouldRefuseNamingTheRowWhenARowHasFewerThanThreeFields() throws Exception {
      Path path = file("from,to,type\nQ0903301,Q0903302\n");

      assertThatIllegalArgumentException()
          .isThrownBy(() -> ClaimFile.read(path))
          .withMessageContaining("line 2")
          .withMessageContaining("three fields")
          .withMessageContaining("nothing was appended");
    }

    @Test
    @DisplayName("should refuse naming the row when an id is not qid-shaped")
    void shouldRefuseNamingTheRowWhenAnIdIsNotQidShaped() throws Exception {
      Path path = file("from,to,type\nthe-highwaymen,Q0903301,AUTHORED\n");

      assertThatIllegalArgumentException()
          .isThrownBy(() -> ClaimFile.read(path))
          .withMessageContaining("line 2")
          .withMessageContaining("the-highwaymen");
    }

    @Test
    @DisplayName("should refuse naming the row when the code is not in the vocabulary")
    void shouldRefuseNamingTheRowWhenTheCodeIsNotInTheVocabulary() throws Exception {
      Path path = file("from,to,type\nQ0903301,Q0903302,ADMIRES\n");

      assertThatIllegalArgumentException()
          .isThrownBy(() -> ClaimFile.read(path))
          .withMessageContaining("line 2")
          .withMessageContaining("no registered edge type for code: ADMIRES");
    }

    @Test
    @DisplayName("should refuse when the file does not start with the header")
    void shouldRefuseWhenTheFileDoesNotStartWithTheHeader() throws Exception {
      Path path = file("Q0903301,Q0903302,AUTHORED\n");

      assertThatIllegalArgumentException()
          .isThrownBy(() -> ClaimFile.read(path))
          .withMessageContaining("from,to,type");
    }

    @Test
    @DisplayName("should refuse when the file is not there")
    void shouldRefuseWhenTheFileIsNotThere() {
      assertThatIllegalArgumentException()
          .isThrownBy(() -> ClaimFile.read(dir.resolve("absent.csv")))
          .withMessageContaining("no claims file at");
    }
  }
  ```

  Run `./gradlew test --tests '*ClaimFileTest*'`, blocking. **Expected red on all six**, against the
  stub: the first on `Expecting actual: [] to contain exactly: [Row[line=3, …]]`, the other five on
  `Expecting code to raise a throwable`. **Quote them.**

- [ ] **Step 2 — GREEN: the reader.** Replace `ClaimFile`'s body:

  ```java
  package com.robsartin.segue.own;

  import com.robsartin.segue.domain.EdgeTypes;
  import com.robsartin.segue.domain.Qid;
  import java.io.IOException;
  import java.io.UncheckedIOException;
  import java.nio.charset.StandardCharsets;
  import java.nio.file.Files;
  import java.nio.file.Path;
  import java.util.ArrayList;
  import java.util.List;
  import java.util.Objects;

  /**
   * The owner's claims file: the header {@code from,to,type}, then one edge per row.
   *
   * <p><b>In {@code own} because nothing else reads this shape.</b> {@code support.QidList} and
   * {@code support.ResolutionFiles} live where they do because two tools read one file; this one
   * has a single reader, and moving it would put a shape in {@code support} that nothing there
   * justifies.
   *
   * <p><b>No quoting, and the parser is {@code String.split}.</b> Every field is a qid or an {@code
   * EdgeTypes} code, so there is nothing a comma could be inside - which is why this does not go
   * through {@code support.CsvFile}. Reading the file a line at a time is also what keeps the line
   * NUMBER, and every refusal below names it: a file the owner typed by hand is a file they have to
   * open again.
   *
   * <p><b>Every refusal is the whole file's.</b> A malformed row, an id that is not qid-shaped and
   * a code outside the vocabulary each throw here, before {@code OwnRun} has appended anything -
   * there is no edge-level retraction, so a wrong edge is undone only by retracting one of its
   * endpoints, which takes that entity's other edges with it (ADR 59).
   *
   * <p><b>Lines beginning {@code #} are comments</b>, so the owner can annotate a file that is
   * personal data. Blank lines are skipped too.
   *
   * <p><b>The file is personal data and never enters this repository</b> (ADR 40's rule). It
   * lives outside the working tree and this class reads it where it is.
   */
  public final class ClaimFile {

    private static final String HEADER = "from,to,type";

    /** One edge the owner wrote down, and the line it is on so a refusal can be opened. */
    public record Row(int line, String fromQid, String toQid, String typeCode) {

      public Row {
        Objects.requireNonNull(fromQid, "fromQid");
        Objects.requireNonNull(toQid, "toQid");
        Objects.requireNonNull(typeCode, "typeCode");
      }
    }

    private ClaimFile() {}

    /** Every edge the file names, in file order. */
    public static List<Row> read(Path path) {
      Objects.requireNonNull(path, "path");
      if (!Files.exists(path)) {
        throw new IllegalArgumentException("no claims file at " + path + " — nothing to claim");
      }
      List<String> lines;
      try {
        lines = Files.readAllLines(path, StandardCharsets.UTF_8);
      } catch (IOException e) {
        throw new UncheckedIOException("could not read " + path, e);
      }

      List<Row> rows = new ArrayList<>();
      boolean headerSeen = false;
      for (int i = 0; i < lines.size(); i++) {
        String line = lines.get(i).trim();
        int number = i + 1;
        if (line.isEmpty() || line.startsWith("#")) {
          continue;
        }
        if (!headerSeen) {
          if (!line.replace(" ", "").equalsIgnoreCase(HEADER)) {
            throw refusal(path, number, "the first row must be the header " + HEADER + ", got: " + line);
          }
          headerSeen = true;
          continue;
        }
        String[] fields = line.split(",", -1);
        if (fields.length < 3) {
          throw refusal(path, number, "a claim needs three fields, " + HEADER);
        }
        String from = fields[0].trim();
        String to = fields[1].trim();
        String code = fields[2].trim();
        if (!Qid.looksLikeAQid(from)) {
          throw refusal(path, number, "from must look like Q12345, got: " + from);
        }
        if (!Qid.looksLikeAQid(to)) {
          throw refusal(path, number, "to must look like Q12345, got: " + to);
        }
        if (EdgeTypes.byCode(code).isEmpty()) {
          throw refusal(path, number, "no registered edge type for code: " + code);
        }
        rows.add(new Row(number, from, to, code));
      }
      if (!headerSeen) {
        throw new IllegalArgumentException(path + " does not start with the header " + HEADER);
      }
      return List.copyOf(rows);
    }

    private static IllegalArgumentException refusal(Path path, int line, String problem) {
      return new IllegalArgumentException(
          path + " line " + line + ": " + problem + " — nothing was appended");
    }
  }
  ```

  Re-run `./gradlew test --tests '*ClaimFileTest*'`, blocking. Expected green, six tests.

- [ ] **Step 3 — three planted positive controls on the reader.** After each plant, run
      `./gradlew test --tests '*ClaimFileTest*'` blocking, observe the named test fire, remove the
      plant and re-run. Quote both runs each time.

  | plant | expected to fire |
  | --- | --- |
  | change `if (fields.length < 3)` to `if (false)` | `shouldRefuseNamingTheRowWhenARowHasFewerThanThreeFields` (it then fails on `ArrayIndexOutOfBoundsException` rather than passing — that counts as firing, and the report says which) |
  | change `if (!Qid.looksLikeAQid(from))` to `if (false)` | `shouldRefuseNamingTheRowWhenAnIdIsNotQidShaped` |
  | change `if (EdgeTypes.byCode(code).isEmpty())` to `if (false)` | `shouldRefuseNamingTheRowWhenTheCodeIsNotInTheVocabulary` |

- [ ] **Step 4 — RED: the parse.** In `OwnCli.java`, widen `Batch` to
      `permits MintBatch, AssertFile` and add the record:

  ```java
  /**
   * "Every edge in this file, claimed in one run."
   *
   * <p>One kind of claim per run still: this is many owner edges, never a mint and an edge
   * together. ADR 59's "one operation per run" is about the kind of claim, not the number of rows.
   */
  public record AssertFile(Path database, Path file, boolean dryRun) implements Batch {

    public AssertFile {
      Objects.requireNonNull(database, "database");
      Objects.requireNonNull(file, "file");
    }
  }
  ```

  Add to `OwnCliTest`:

  ```java
    @Test
    @DisplayName("should read the claims file when asserting from a file")
    void shouldReadTheClaimsFileWhenAssertingFromAFile() {
      OwnCli.AssertFile batch =
          (OwnCli.AssertFile) parse("assert", "--file", "/lists/claims.csv", "--dry-run");

      assertThat(batch.file()).isEqualTo(Path.of("/lists/claims.csv"));
      assertThat(batch.dryRun()).isTrue();
      assertThat(batch.database()).isEqualTo(Path.of(DATABASE));
    }

    @Test
    @DisplayName("should refuse when a single assert's endpoint is given with a claims file")
    void shouldRefuseWhenASingleAssertsEndpointIsGivenWithAClaimsFile() {
      assertThatIllegalArgumentException()
          .isThrownBy(() -> parse("assert", "--file", "/lists/claims.csv", "--from", "Q0903301"))
          .withMessageContaining("--from")
          .withMessageContaining("--file");
    }

    @Test
    @DisplayName("should refuse when a single assert's type is given with a claims file")
    void shouldRefuseWhenASingleAssertsTypeIsGivenWithAClaimsFile() {
      assertThatIllegalArgumentException()
          .isThrownBy(() -> parse("assert", "--file", "/lists/claims.csv", "--type", "AUTHORED"))
          .withMessageContaining("--type")
          .withMessageContaining("--file");
    }

    @Test
    @DisplayName("should parse when the claims file does not exist, because parse opens nothing")
    void shouldParseWhenTheClaimsFileDoesNotExist() {
      Path absent = dir.resolve("no-claims-here.csv");

      OwnCli.AssertFile batch = (OwnCli.AssertFile) parse("assert", "--file", absent.toString());

      assertThat(batch.file()).isEqualTo(absent);
      assertThat(Files.exists(absent)).isFalse();
    }
  ```

  Run `./gradlew test --tests '*OwnCliTest*'`, blocking. **Expected red on all four**, with
  `--from is required.` for the first and the fourth and the wrong message for the middle two.
  Quote the text.

- [ ] **Step 5 — GREEN: the parse branch.** In `OwnCli.java`:

  ```java
    private static Options assertion(Path database, Map<String, String> values, boolean dryRun) {
      if (values.containsKey("--file")) {
        return assertFile(database, values, dryRun);
      }
      String from = qid(values, "--from");
      String to = qid(values, "--to");
      String type = required(values, "--type");
      refuseTheRest(values);
      return new Assert(database, from, to, type, dryRun);
    }

    private static AssertFile assertFile(Path database, Map<String, String> values, boolean dryRun) {
      refuseTheOtherShape(
          values, "a single assert, not to --file", "--from", "--to", "--type");
      String file = required(values, "--file");
      refuseTheRest(values);
      return new AssertFile(database, Path.of(file), dryRun);
    }
  ```

  `USAGE` gains `" | assert --file <claims.csv>"` after the single `assert` form.

  Re-run `./gradlew test --tests '*OwnCliTest*'`, blocking. Expected green.

- [ ] **Step 6 — RED: the batch assert.** Add the `AssertFile` arm to `runBatch` with a
      deliberately-wrong stub — `case AssertFile file -> List.of();` — so the suite compiles, then
      add to `OwnRunTest`:

  ```java
    private Path claimsFile(String... rows) throws Exception {
      Path path = dir.resolve("claims.csv");
      Files.writeString(path, "from,to,type\n" + String.join("\n", rows) + "\n");
      return path;
    }

    private OwnCli.AssertFile claims(Path file, boolean dryRun) {
      return new OwnCli.AssertFile(UNUSED, file, dryRun);
    }
  ```

  and the seven tests. Each seeds the projection first — the class's existing `log.append(...)`
  helpers for a sourced `NodeAssertion` are what the single-`assert` tests already use.

  ```java
    @Test
    @DisplayName("should claim every row in file order when asserting from a file")
    void shouldClaimEveryRowInFileOrderWhenAssertingFromAFile() throws Exception {
      seedSourcedNodes(SOURCED, OTHER_SOURCED, THIRD_SOURCED);
      Path file =
          claimsFile(
              SOURCED + "," + OTHER_SOURCED + ",INFLUENCED_BY",
              OTHER_SOURCED + "," + THIRD_SOURCED + ",INFLUENCED_BY");

      List<LoggedAssertion> claims = run.runBatch(claims(file, false), notes::add);

      assertThat(claims).hasSize(2);
      assertThat(((OwnerEdge) claims.get(0)).fromQid()).isEqualTo(SOURCED);
      assertThat(((OwnerEdge) claims.get(1)).fromQid()).isEqualTo(OTHER_SOURCED);
      assertThat(log.readAll()).filteredOn(OwnerEdge.class::isInstance).hasSize(2);
    }

    @Test
    @DisplayName("should report both labels on every line when asserting from a file")
    void shouldReportBothLabelsOnEveryLineWhenAssertingFromAFile() throws Exception {
      seedSourcedNodes(SOURCED, OTHER_SOURCED);
      Path file = claimsFile(SOURCED + "," + OTHER_SOURCED + ",INFLUENCED_BY");

      run.runBatch(claims(file, true), notes::add);

      assertThat(notes)
          .anyMatch(note -> note.startsWith("claiming " + SOURCED + " \""))
          .anyMatch(
              note ->
                  note.equals(
                      "this is your own claim, not a source's: it is exempt from the corroboration"
                          + " count, so it routes but never vouches for anything (#92)"));
    }

    @Test
    @DisplayName("should refuse the whole run before any append when an endpoint is not in the projection")
    void shouldRefuseTheWholeRunBeforeAnyAppendWhenAnEndpointIsNotInTheProjection()
        throws Exception {
      seedSourcedNodes(SOURCED, OTHER_SOURCED);
      Path file =
          claimsFile(
              SOURCED + "," + OTHER_SOURCED + ",INFLUENCED_BY",
              SOURCED + "," + NEVER_CLAIMED + ",INFLUENCED_BY");

      assertThatThrownBy(() -> run.runBatch(claims(file, false), notes::add))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("nothing in the projection is " + NEVER_CLAIMED);
      assertThat(log.readAll()).noneMatch(OwnerEdge.class::isInstance);
    }

    @Test
    @DisplayName("should skip a row the log already carries as an owner edge")
    void shouldSkipARowTheLogAlreadyCarriesAsAnOwnerEdge() throws Exception {
      seedSourcedNodes(SOURCED, OTHER_SOURCED, THIRD_SOURCED);
      run.run(claim(SOURCED, OTHER_SOURCED, false), notes::add);
      notes.clear();
      Path file =
          claimsFile(
              SOURCED + "," + OTHER_SOURCED + ",INFLUENCED_BY",
              SOURCED + "," + THIRD_SOURCED + ",INFLUENCED_BY");

      List<LoggedAssertion> claimed = run.runBatch(claims(file, false), notes::add);

      assertThat(claimed).hasSize(1);
      assertThat(((OwnerEdge) claimed.get(0)).toQid()).isEqualTo(THIRD_SOURCED);
      assertThat(notes)
          .anyMatch(
              note ->
                  note.startsWith("skipping " + SOURCED + " INFLUENCED_BY " + OTHER_SOURCED)
                      && note.contains("the log already carries this edge"));
    }

    @Test
    @DisplayName("should append nothing when the batch assert is a dry run")
    void shouldAppendNothingWhenTheBatchAssertIsADryRun() throws Exception {
      seedSourcedNodes(SOURCED, OTHER_SOURCED);
      Path file = claimsFile(SOURCED + "," + OTHER_SOURCED + ",INFLUENCED_BY");

      run.runBatch(claims(file, true), notes::add);

      assertThat(log.readAll()).noneMatch(OwnerEdge.class::isInstance);
      assertThat(notes).contains("dry run: nothing was appended");
    }

    @Test
    @DisplayName("should refuse the whole run before any append when a row names a merged-away id")
    void shouldRefuseTheWholeRunBeforeAnyAppendWhenARowNamesAMergedAwayId() throws Exception {
      // Reuses whatever this class already does to mint and merge a local id - the single-assert
      // test for the same refusal is the shape to copy, sentence for sentence.
      …
      assertThat(log.readAll()).noneMatch(OwnerEdge.class::isInstance);
    }

    @Test
    @DisplayName("should refuse the whole file before any append when a row is malformed")
    void shouldRefuseTheWholeFileBeforeAnyAppendWhenARowIsMalformed() throws Exception {
      seedSourcedNodes(SOURCED, OTHER_SOURCED);
      Path file =
          claimsFile(SOURCED + "," + OTHER_SOURCED + ",INFLUENCED_BY", SOURCED + ",ADMIRES");

      assertThatThrownBy(() -> run.runBatch(claims(file, false), notes::add))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("line 3");
      assertThat(log.readAll()).noneMatch(OwnerEdge.class::isInstance);
    }
  ```

  Add `private static final String THIRD_SOURCED = "Q0903304";` and a `seedSourcedNodes(String…)`
  helper if the class does not already have one — the sourced-node seeding the existing
  single-`assert` tests do is the shape to lift, not a new one.

  Run `./gradlew test --tests '*OwnRunTest*'`, blocking. **Expected red on all seven**, against
  the stub. Quote them.

- [ ] **Step 7 — GREEN: the batch assert.** In `OwnRun.java`, replace the stub arm with
      `case AssertFile file -> claimFromFile(file, notes);` and add:

  ```java
    /**
     * Claim every edge one file names.
     *
     * <p><b>All or nothing.</b> Any row the projection refuses - an endpoint it does not hold, a
     * local id merged away, a canonical id a later merge corrected - refuses the whole run, before
     * the report is printed and long before anything is appended. There is no edge-level
     * retraction: a wrong edge is undone only by retracting one of its endpoints, which takes
     * that entity's other edges with it. Half a file is the one outcome worth refusing
     * outright.
     *
     * <p><b>A duplicate is skipped rather than refused</b>, and it is the owner's own claim
     * repeated: both projections fold two identical owner edges to one, so the second row would
     * add noise to a log that is never edited and nothing to the graph. Endpoints are folded
     * through the shared {@link Equivalences} first, so a row naming a local id and a row naming
     * the canonical id it was merged into are the same edge.
     *
     * <p><b>The corroboration sentence is said once, at the end</b>, rather than after each line:
     * it is one fact about every owner edge in the run, and repeating it per row would bury the
     * labels the report exists to show.
     */
    private List<LoggedAssertion> claimFromFile(AssertFile batch, Consumer<String> notes) {
      List<ClaimFile.Row> rows = ClaimFile.read(batch.file());
      List<LoggedAssertion> logged = log.readAll();
      Equivalences merges = Equivalences.in(logged);
      Map<String, String> present = labelsInTheProjection(logged, merges);
      Set<String> held = ownerEdgesTheProjectionKeeps(logged, merges);

      List<String> claiming = new ArrayList<>();
      List<String> skipped = new ArrayList<>();
      List<LoggedAssertion> claims = new ArrayList<>();
      for (ClaimFile.Row row : rows) {
        String from = labelOrRefuse(logged, present, merges, row.fromQid());
        String to = labelOrRefuse(logged, present, merges, row.toQid());
        if (held.contains(edgeKey(merges, row.fromQid(), row.typeCode(), row.toQid()))) {
          skipped.add(
              "skipping "
                  + row.fromQid()
                  + " "
                  + row.typeCode()
                  + " "
                  + row.toQid()
                  + " — the log already carries this edge, and the projection folds a duplicate to"
                  + " one");
          continue;
        }
        claiming.add(
            "claiming "
                + row.fromQid()
                + " \""
                + from
                + "\" "
                + row.typeCode()
                + " "
                + row.toQid()
                + " \""
                + to
                + "\"");
        claims.add(
            OwnerEdge.claimed(row.fromQid(), row.toQid(), row.typeCode(), clock.instant()));
      }
      claiming.forEach(notes);
      skipped.forEach(notes);
      notes.accept(claims.size() + " to claim, " + skipped.size() + " to skip");
      notes.accept(
          "this is your own claim, not a source's: it is exempt from the corroboration count, so it"
              + " routes but never vouches for anything (#92)");

      if (batch.dryRun()) {
        notes.accept("dry run: nothing was appended");
        return List.copyOf(claims);
      }
      for (LoggedAssertion claim : claims) {
        IngestService.claim(log, claim);
      }
      notes.accept(
          "appended. The running graph is rebuilt from the log at the next boot (ADR 24), so a"
              + " server that is up does not see this claim until it restarts");
      return List.copyOf(claims);
    }

    /** Every owner edge the projection still keeps, keyed on its folded endpoints and its code. */
    private static Set<String> ownerEdgesTheProjectionKeeps(
        List<LoggedAssertion> logged, Equivalences merges) {
      Retractions retractions = Retractions.in(logged);
      Set<String> held = new LinkedHashSet<>();
      for (int i = 0; i < logged.size(); i++) {
        LoggedAssertion assertion = logged.get(i);
        if (retractions.survives(i, assertion) && assertion instanceof OwnerEdge edge) {
          held.add(edgeKey(merges, edge.fromQid(), edge.typeCode(), edge.toQid()));
        }
      }
      return held;
    }

    private static String edgeKey(Equivalences merges, String from, String code, String to) {
      return canonical(merges, from) + "|" + code + "|" + canonical(merges, to);
    }

    private static String canonical(Equivalences merges, String qid) {
      return merges.canonicalByLocal().getOrDefault(qid, qid);
    }
  ```

  Re-run `./gradlew test --tests '*OwnRunTest*'`, blocking. Expected green, non-zero count.

- [ ] **Step 8 — three planted positive controls.** After each, run
      `./gradlew test --tests '*OwnRunTest*'` blocking, observe, remove, re-run. Quote both runs.

  | plant | expected to fire |
  | --- | --- |
  | in `claimFromFile`, replace `held` with `Set.<String>of()` | `shouldSkipARowTheLogAlreadyCarriesAsAnOwnerEdge` |
  | move the two `IngestService.claim` appends **above** the `labelOrRefuse` loop (append first, then validate) | `shouldRefuseTheWholeRunBeforeAnyAppendWhenAnEndpointIsNotInTheProjection` and `…WhenARowIsMalformed` — this is the all-or-nothing guard, and it is the one worth planting because "before any append" is ordering rather than a condition |
  | change `if (batch.dryRun())` to `if (false)` | `shouldAppendNothingWhenTheBatchAssertIsADryRun` |

- [ ] **Step 9 — one-line check and commit.** Run, blocking:

  ```
  ./gradlew spotlessApply
  grep -nE '\{@(code|link)[^}]*$' src/main/java/com/robsartin/segue/own/*.java src/test/java/com/robsartin/segue/own/*.java
  ./gradlew compileJava compileTestJava
  ./gradlew test --tests '*own*' --tests '*ArchitectureTest*'
  ```

  The `grep` must print nothing. Commit:

  ```
  git status
  git add src/main/java/com/robsartin/segue/own/ClaimFile.java src/main/java/com/robsartin/segue/own/OwnCli.java src/main/java/com/robsartin/segue/own/OwnRun.java src/test/java/com/robsartin/segue/own/ClaimFileTest.java src/test/java/com/robsartin/segue/own/OwnCliTest.java src/test/java/com/robsartin/segue/own/OwnRunTest.java
  ```

  Message:

  ```
  Claim every edge in a file in one run for #342

  ownClaim assert --file <claims> reads from,to,type, refuses a malformed row,
  an id that is not qid-shaped, a code outside the vocabulary and an endpoint
  the projection does not hold - each for the whole file, before any append -
  skips an owner edge the log already carries, and appends the rest in file
  order.

  Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
  ```

---

## Task 4 — the chapter, the runbook and the two amendments

**Files:**

*Modify:* `docs/developer-guide.md`, `docs/adr/0059-owner-claims-as-a-third-layer.md`,
`docs/adr/0040-bulk-seeding-as-a-dev-tool.md`, `CLAUDE.md`.

*Test:* none new. The checks are `DeveloperGuideOwnClaimExamplesTest`, `DocumentationLinksTest`,
`AdrCitationsTest`, `AdrIndexTest` and `DeveloperGuideEnumerationsTest`, all already in the gate.

### How `DeveloperGuideOwnClaimExamplesTest` finds an example — read this before writing one

The test holds `GuideExamples.of("ownClaim")`, which is **whole-file**, not chapter-scoped: an
example anywhere in `docs/developer-guide.md` is found, including in the new runbook chapter, with
no new code. `GuideExamples.scan` then works like this, and every clause below is a way to write an
example that this test silently ignores or loudly refuses:

1. **A line is a *mention*** when it contains `./gradlew` **and** the token `ownClaim` as a whole
   word — the pattern is `(?<![A-Za-z0-9_])ownClaim(?![A-Za-z0-9_])`. Nothing about `--args` is in
   that test. **The only way an invocation escapes the check is by not naming the task.**
2. **A trailing backslash joins the next line first**, indentation and all, before anything is
   matched.
3. **A mention is a *complete example*** when the joined text matches
   `\./gradlew :?ownClaim --args="(.*)"` — double quotes, no space around the equals, closed on the
   line (or on the joined line).
4. **A mention that is not complete and carries no `--args` token at all is prose**, and is
   allowed: the guide genuinely writes "run as `./gradlew ownClaim`".
5. **A mention that is not complete and *does* carry an `--args` token in any spelling** lands in
   `unreadableExamples()` and reds `shouldNameTheLineWhenAnOwnClaimExampleCannotBeRead`. A
   single-quoted `--args='…'` is refused on purpose, because `$HOME` does not expand inside single
   quotes.
6. **The captured group is split the way a shell would**: whitespace-separated words, except that a
   single-quoted run is one argument however many spaces are inside it. `$HOME` is replaced with
   `GuideExamples.INVENTED_HOME` first, so no real home directory is read.
7. **Every complete example's arguments go through `OwnCli.parse(args, null, INVENTED_HOME)`**, and
   any refusal reds `shouldParseEveryExampleWhenTheGuideShowsAnOwnClaimCommand`. **`--db` is
   enforced there**, so an example that forgets it fails.
8. **Any example containing a tilde reds** `shouldWriteHomeRatherThanATildeWhenAnExampleNamesADatabase`.
9. **The vacuity guard** requires the set of first arguments to contain `mint`, `assert` and
   `merge`. The new examples are `mint` and `assert`, so it keeps passing with no change.

**So every example this task writes is: one line; `./gradlew ownClaim --args="…"`; `--db
$HOME/.segue/segue.db`; never a tilde; single quotes around any value with a space in it.**

---

- [ ] **Step 1 — the two guide sections.** Insert into `docs/developer-guide.md` immediately after
      the section `### An owner edge routes, and never vouches` and before `### A merge is said, not
      done — and it lands in two places at two times`. The text, verbatim (fenced here at four
      backticks because it carries markdown links, which `DocumentationLinksTest` scans in this
      plan file too):

  ````markdown
  ### Minting a whole review file: `mint --review … --mapping …`

  The seed tool's review file carries the rows Wikidata had nothing for: an entity the graph lacks
  entirely, under any spelling that was tried. Minting those one at a time is the typing this
  project exists to remove, so `mint` takes the pair of files instead.

  ```bash
  # what would this mint, and under which ids? Nothing is written.
  ./gradlew ownClaim --args="mint --db $HOME/.segue/segue.db --review $HOME/lists/reading-review.csv --mapping $HOME/lists/reading-qids.csv --dry-run"

  # do it — the mapping gains one MINTED row per mint, carrying the id it allocated
  ./gradlew ownClaim --args="mint --db $HOME/.segue/segue.db --review $HOME/lists/reading-review.csv --mapping $HOME/lists/reading-qids.csv"
  ```

  **Only `UNRESOLVED` rows are minted.** A `REVIEW` row carries a plausible candidate the
  adjudicator could not choose between, and minting one would put a second entity in the graph for
  something Wikidata already has — the one mistake this tool cannot take back, because the log is
  append-only (ADR 19) and never edited and the repair is a retraction plus a merge. The report says how many
  `REVIEW` rows it passed over, so the number is never silent.

  **A name the mapping already carries is skipped, folded rather than literal.** `support.NameFold`
  is the same fold the seed tool uses, so a row the first run minted under one spelling is not
  minted again under another. That is what makes a second run over the same review file safe.

  **A list kind that folds to two node kinds is yours to type.** `musician` and `comedian` are as
  often a band as a person — `support.ListKinds` is the authority on which kinds those are — and
  nothing in a review file says which. Those rows are skipped with the single-`mint` command
  printed out, `--kind` left as a choice for you to fill in.

  **A list kind the table does not register refuses the whole run**, before anything is appended and
  before anything is reported, naming the row. `support.ListKinds` holds every kind `resolveNames`
  writes, so a row carrying another one means the file is not one the seed tool wrote, and nothing
  else in it can be trusted to be what it looks like.

  **Ids are allocated in sequence from one read of the log**, each one the smallest `Q00…` number no
  row has ever named once this run's earlier mints are counted as named — the same membership rule
  [a single mint uses](#a-mint-costs-an-id-and-the-id-is-never-handed-back), applied across a batch.

  **The report is whole before the first append; the two appends are then interleaved per row** —
  the claim, then its mapping row. A failure between the two leaves at most one mint without its
  mapping row, and the report names the id it appended so you can write that row by hand. The
  mapping row is the seven-column shape with `MINTED` in the confidence column and the reason
  `minted by the owner — no Wikidata candidate under any spelling (ADR 59)`.

  **The mapping is where a local id lives for `--known`.** `support.QidList` reads the first
  comma-separated field on a line that is exactly a QID, and a local `Q00…` id is one — so a minted
  entity joins the `--known` population the moment its mapping row is written, with nothing else to
  do. `graphCensus --known <mapping>` counts it under `in the graph`, and, until issue #344 lands,
  under `never expanded` too, for the reason that issue states.

  ### Claiming a file of edges: `assert --file …`

  An entity nothing else connects to is a node and not a segue. The `--isolated` file from
  `graphCensus` is a list of exactly those, and joining each one up by hand is a command per edge,
  so `assert` takes a file.

  ```bash
  # both labels on every line, read from the projection. Nothing is written.
  ./gradlew ownClaim --args="assert --db $HOME/.segue/segue.db --file $HOME/lists/claims.csv --dry-run"

  # do it — every row, in file order
  ./gradlew ownClaim --args="assert --db $HOME/.segue/segue.db --file $HOME/lists/claims.csv"
  ```

  The file is a header and one edge per row, with `#` comments so you can annotate a file that is
  personal data ([ADR 33](adr/0033-taste-layer-separation.md), issue #37):

  ```
  from,to,type
  # the book's author, which nothing in the graph states
  Q00903301,Q0903301,AUTHORED
  ```

  Two ids that look like qids — a local `Q00…` id is allowed on either side — and one `EdgeTypes`
  code. There is no quoting, because every field is an id or a code.

  **Any refused row refuses the whole file, before any append.** A row with fewer than three fields,
  an id that is not qid-shaped, a code outside the vocabulary, an endpoint the projection does not
  hold and a local id you have already merged away are all whole-file refusals, each naming the line
  number. The reason is that there is no edge-level retraction
  ([ADR 44](adr/0044-retraction-as-a-new-claim.md)): a wrong edge is undone only by
  retracting one of its endpoints, which takes that entity's other edges with it. Half a file is the one outcome worth refusing outright.

  **A row the log already carries as an owner edge is skipped**, with endpoints folded through the
  same `Equivalences` rule the projections use — so a row naming a local id and a row naming the
  canonical id it was merged into are the same edge. Both folds collapse two identical owner edges
  to one, so the second row would add noise to a log nobody may edit and nothing to the graph.

  **The corroboration sentence is said once, at the end.** It is one fact about every owner edge in
  the run, and repeating it per row would bury the labels the report exists to show.

  **One kind of claim per run still holds.** This is many owner edges, never a mint and an edge
  together: [ADR 59](adr/0059-owner-claims-as-a-third-layer.md)'s rule is about the kind of claim,
  not the number of rows. Minting something and then joining it up is still two commands, and the
  second sees the first because it replays the log.
  ````

  **After inserting, check the anchor.** The section links
  `#a-mint-costs-an-id-and-the-id-is-never-handed-back`. `DocumentationLinksTest` resolves it
  against the guide's own headings by GitHub's slug rule; if it reds, read the heading it is
  pointing at and fix the anchor, never the test.

- [ ] **Step 2 — the runbook chapter.** Insert `## What Wikidata lacks` **after the whole of
      `## Expanding every promotion`** — that is, after its closing `### What to file from what you
      saw` section and before `## How to read an ADR against the code` (Ruling 4). Add its row to
      the guide's `## Contents` list in the same edit, in document order.

  ````markdown
  ## What Wikidata lacks

  Two populations the coverage instruments name, claimed end to end. The first is the seed tool's
  review file: rows that resolved to nothing in Wikidata, so the graph lacks the entity entirely and
  both a mint and at least one edge are needed. The second is `graphCensus --isolated`'s `with no
  one` acts: in the graph, every neighbour expanded, connected to nothing you know — an edge and no
  mint.

  **0. Quit the client, and confirm nothing is holding the database.** Every writing run starts here,
  for the reason [the supervised first run](#0-quit-the-client-and-confirm-nothing-is-holding-the-database)
  gives: two writers on one SQLite file is not a configuration this project supports.

  **1. Mint the review file's unresolved rows.** Dry run first, and read the labels — the failure
  being guarded is a name that is not the entity you think it is.

  ```bash
  ./gradlew ownClaim --args="mint --db $HOME/.segue/segue.db --review $HOME/lists/reading-review.csv --mapping $HOME/lists/reading-qids.csv --dry-run"
  ```

  Then the run, the same line without `--dry-run`. The mapping now carries the local ids, and
  `graphCensus --known <mapping>` counts them.

  **2. Write the claims file.** One row per edge: from a minted id, or from a `with no one` act's
  qid — the `--isolated` file puts each qid beside its label — to something the graph already holds,
  with a code from `EdgeTypes`. Comment the rows you want to remember the reason for.

  **3. Claim them.** Dry run first, read **both** labels on every line, then the run.

  ```bash
  ./gradlew ownClaim --args="assert --db $HOME/.segue/segue.db --file $HOME/lists/claims.csv --dry-run"
  ```

  **4. The second population is step 3 again**, over the isolated file's acts. No mint: they are
  already in the graph.

  **5. A deck session with the mapping as its own `--known`.** A minted entity is dealt like any
  other in-graph unrated one, and a rating at or above `KnownList.PROMOTION_RATING` promotes it.

  **6. The census after**, and the reading that follows on the normal rule.

  **7. When Wikidata catches up**, `merge --local Q00… --canonical Q…`, as
  [A merge is said, not done](#a-merge-is-said-not-done--and-it-lands-in-two-places-at-two-times)
  already describes. The mapping keeps the local id and the fold resolves it.

  **8. Undoing.** `retractEntity` on the local id takes its node, its edges and its mapping row's
  meaning with it. The mapping row itself is yours to delete: nothing in this project edits a file
  the owner wrote.
  ````

  **The anchors in this chapter point at existing headings and are the likeliest thing to red.**
  `DocumentationLinksTest` resolves each against the guide's own headings; read the heading and fix
  the anchor if it reds. The em-dash heading in step 7 leaves a **doubled** hyphen in its slug,
  which GitHub does not collapse and neither does that test.

- [ ] **Step 3 — run the document checks, blocking.**

  ```
  ./gradlew test --tests '*DeveloperGuideOwnClaimExamplesTest*' --tests '*DocumentationLinksTest*' --tests '*DeveloperGuideEnumerationsTest*'
  ```

  Expected green, non-zero count. Commit `docs/developer-guide.md` by explicit path, subject
  `Document the two batch claims for #342`, with the trailer.

- [ ] **Step 4 — plant the positive control for the examples test.** In the first new example,
      change `--args="` to `--args='` and change the closing `"` to `'`. Run
      `./gradlew test --tests '*DeveloperGuideOwnClaimExamplesTest*'`, blocking. **Expected:**
      `shouldNameTheLineWhenAnOwnClaimExampleCannotBeRead` fires, naming the line and saying
      `Single quotes are refused: $HOME does not expand inside them`. Revert, re-run, expect green.
      Quote both runs. Then plant a second: delete `--db $HOME/.segue/segue.db ` from that same
      example. **Expected:** `shouldParseEveryExampleWhenTheGuideShowsAnOwnClaimCommand` fires with
      `--db is required`. Revert, re-run, expect green. **Two plants, because they are two different
      guards.**

- [ ] **Step 5 — ADR 59's amendment.** Append at the **very end** of
      `docs/adr/0059-owner-claims-as-a-third-layer.md`, after its last paragraph, with one blank
      line before it. Bold-opener form, no heading. No commit hash, and no qid inside backticks —
      `AdrCitationsTest` reds on a backticked run of 7–40 hex characters.

  ````markdown
  **Amendment (2026-09-18, issue #342): two batch shapes, and the mapping file as where a local id
  lives.** `ownClaim mint --review <review> --mapping <mapping>` mints every row of the seed tool's
  review file that resolved to nothing, and `ownClaim assert --file <claims>` claims every edge of a
  three-column file the owner writes. Both were built for the populations the coverage instruments
  name — the review file's unresolved rows, and `graphCensus --isolated`'s `with no one` acts — and
  the alternative in each case was a hand-typed command per row, which is the typing this project
  exists to remove.

  **"One operation per run" still holds, and it always meant one *kind* of claim.** A run is a mint
  or an edge or a merge, never two of them; the report is whole before the first append; and a
  refusal refuses the whole run rather than half of it. What changed is the number of rows, not the
  number of decisions. Minting an entity and then joining it up is still two commands, and the
  second sees the first because it replays the log.

  **All-or-nothing is a consequence of there being no edge-level retraction**
  ([ADR 44](0044-retraction-as-a-new-claim.md)). A wrong edge is undone
  only by retracting one of its endpoints, which takes that entity's other edges with it — so a file half-applied is strictly worse than one refused. The
  batch mint refuses a list kind the table does not register for the same reason: such a file is not
  one the seed tool wrote, and nothing else in it can be trusted either.

  **`MINTED` is a fourth outcome on the seven-column shape** ([ADR 40](0040-bulk-seeding-as-a-dev-tool.md),
  amended the same day). The batch mint appends one mapping row per mint, carrying the allocated id
  and that outcome. **This is where a local id lives for `--known`**: the QID-file reader takes the
  first comma-separated field that is exactly a QID, and a local id is one, so a minted entity joins
  that population the moment its row is written. The census consequence — a local id counting under
  `never expanded` — is issue #344 and not this decision.

  **Alternatives rejected.**

  - **A hand-typed `mint` per row, plus a hand-edit of the mapping.** It works, and it is the
    typing this project exists to remove. It also puts the id the tool allocated into the mapping by
    hand, which is one transcription per entity into a file the graph is read against.
  - **Minting inside `resolveNames`.** The seed tool is fenced off every store, deliberately
    ([ADR 40](0040-bulk-seeding-as-a-dev-tool.md)). Minting there would make a tool that needs no
    database into one that writes the log.
  - **One `assert` per run for the isolated population.** It is dozens of acts, each a separate
    invocation that replays the whole log.
  - **The tool proposing a neighbour to claim.** It covers one case — a book's author — and guesses
    where the owner knows. An owner claim is exempt from the corroboration count by design, so a
    guessed one is the one kind of structure that must never be laundered into that tier.
  - **Minting `REVIEW` rows too.** Each carries a plausible candidate; minting one puts a second
    entity in the graph for something Wikidata already has, and the repair is a retraction plus a
    merge on an append-only log (ADR 19).
  - **Refusing, rather than skipping, a row the mapping already carries.** A re-run over the same
    review file is the ordinary case after a partial run, and refusing it would make the safe thing
    the awkward one.
  - **Guessing the node kind for a list kind that folds to two.** `musician` and `comedian` are as
    often a band as a person and nothing in a review file says which. The run prints the
    single-`mint` command instead, with the kind left blank.
  - **An MCP tool for either batch.** Unchanged from this ADR's own reasoning: the caller of an MCP
    tool is a language model, and an owner claim skips quarantine.
  ````

- [ ] **Step 6 — ADR 40's amendment.** Append at the **very end** of
      `docs/adr/0040-bulk-seeding-as-a-dev-tool.md`, same form, same rules.

  ````markdown
  **Amendment (2026-09-18, issue #342): the mapping and review shape is shared, and it lives in
  `support`.** The seven-column shape `name,kind,status,qid,label,confidence,reason`, its reader,
  its appender, the "already resolved" fold and the name fold underneath it have left this tool for
  `support`, alongside the list-kind table that says which node kinds a list's `kind` column may
  turn out to be. The occupation and class sets stay here: they exist to tell six same-named humans
  apart against Wikidata, and nothing outside this tool has anything to ask them.

  **The reason is a fence, not tidiness.** The owner-claim tool
  ([ADR 59](0059-owner-claims-as-a-third-layer.md)) now reads a review file to mint from and
  appends to the mapping, and it may not depend on this tool — each carries its own ArchUnit fence,
  and a dependency on a sibling would let one inherit the other's. A shape neither owns is the only
  way the two read one file by one rule, which is the move the QID-file reader and the known-list
  reader each made before it.

  **A fourth outcome, `MINTED`, which this tool never writes.** It marks a row the owner minted
  under [ADR 59](0059-owner-claims-as-a-third-layer.md), written by the claim tool alone. The
  "already resolved" fold reads it as resolved like every other row, so a second run of either tool
  over the same files does nothing twice. Nothing else about this tool changes: it still never opens
  a store, still reports rather than decides, and its tests changed only their imports.

  **Alternatives rejected.**

  - **Copying the shape into the claim tool.** Two readers of one file, and the second copy of a
    rule is the one a future editor misses — the mapping is read as a known-list by three other
    tools already.
  - **Letting the claim tool depend on this one.** The fence forbids it, and the fence is the
    decision: a tool that may not open a store and a tool whose whole job is appending to one have
    different fences for different reasons.
  - **Moving the input-list reader and its row type too.** Only this tool reads the input list.
    What moved is what two tools read; the RFC 4180 parser underneath both readers moved with it,
    because a second copy of *that* is a second place for a name with a comma in it to be got wrong.
  - **A separate outcome file for minted rows.** The output files are already the resume ledger,
    and a second file that can disagree with them is the bug this ADR's own reasoning rejects.
  ````

- [ ] **Step 7 — correct the two prose claims about `support`.** The section-1 ruling's second
      consequence: `support` now depends on `domain`, and two documents say it does not.

  - `docs/developer-guide.md`, `### What each package is for`: the `support` row's "no project
    dependencies of their own" clause becomes a statement of the real criterion — more than one
    dev-side tool reads the same thing — and names the classes this issue added. Read the row as it
    stands before editing; do not restate anything it already cites.
  - `CLAUDE.md`, the `support/` entry in the **Architecture** block: the same correction, in one
    clause, naming `ListKinds` as the one class there that depends on `domain` and why.

  Neither is a count or a figure, so neither goes stale. Run, blocking:

  ```
  ./gradlew test --tests '*DocumentationLinksTest*' --tests '*AdrCitationsTest*' --tests '*AdrIndexTest*' --tests '*DeveloperGuide*'
  ```

  Expected green, non-zero count.

- [ ] **Step 8 — commit.**

  ```
  git status
  git add docs/developer-guide.md docs/adr/0059-owner-claims-as-a-third-layer.md docs/adr/0040-bulk-seeding-as-a-dev-tool.md CLAUDE.md
  ```

  Message:

  ```
  Record the two batch claims for #342

  Two developer-guide sections and a "What Wikidata lacks" runbook chapter; a
  dated amendment on ADR 59 for the two batch shapes and why one operation per
  run still holds, and one on ADR 40 for the shape moving to support and the
  MINTED outcome the seed tool never writes. Corrects the two places that said
  support has no project dependencies of its own.

  Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
  ```

---

## What this plan does not do

- **The stack trace beneath a refusal.** The spec records that the owner's run on 2026-09-18 showed
  `ownClaim` printing a stack trace under its usage sentence. Noted there for a follow-up, and
  deliberately out of scope here.
- **The census consequence of a local id.** A minted entity counts under `never expanded` and in the
  second-hop rows. That is issue #344, not this change.
- **ADR 60 needs nothing.** Both batches take `--db` from the flag exactly as before, and the two
  ArchUnit rules that ADR names are unchanged — which Task 2 and Task 3 each verify by running
  `*ArchitectureTest*` in their own loop.
- **The MCP surface is untouched.** Neither batch is a tool, for ADR 59's reason.
- **`merge`, `retractEntity`, the single `mint` and `assert`, and `OwnRun.run`'s report** are
  unchanged. `OwnCliTest` and `OwnRunTest`, unedited through Task 2 Step 1, are the control.
