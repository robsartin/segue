# The second hop — naming the isolated known-list acts and expanding the ring beside them — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** close issue #319. One rule in `domain` — `SecondHop` — owns "isolated" and "worth
expanding next". The census reads it and prints three rows under each population's
`no known neighbour within N hops` row, and on `--isolated <file>` writes the isolated acts of the
population with promotions to a file. The expander gains a third population, `--second-hop <file>`,
which visits the unexpanded people and groups beside those acts and nothing else. The terminal
census block is byte-identical with and without `--isolated`; the expander's no-flag block and its
two existing flagged blocks are byte-identical to what they print today.

**Architecture:** one move, one new domain type, three readers, two flags, three documents.

- **The move:** `export.LogProjection` → `ingest.LogProjection`. `ArchitectureTest
  .theExpanderOpensNothingElse` forbids `expand → export` outright, and the expander now needs the
  fold's nodes and edges. `ingest` is where the boot's fold already lives.
- **The new type:** `domain.SecondHop`, built from `Map<String, NodeRecord>`, `List<EdgeRecord>`, a
  known population and `Expanded`. `isolated()`, `toExpandBeside(String)`, `toExpand()`. The
  undirected bounded walk moves out of `census.Neighbours` into a package-private `domain.Neighbours`
  that `SecondHop` is the only reader of.
- **The census:** three integers on `KnownListCensus.Population`, three rows on `CensusReport`, and
  `--isolated <file>` on `CensusCli`, refused without `--known`.
- **The expander:** `expand.SecondHopNeighbours(String file, int isolated)` as the third shape of
  the sealed `expand.Population`, `--second-hop <file>` on `ExpandCli`, and a third branch in
  `ExpandCli.run` composing the population once, at the start.
- **The records:** dated amendments to ADR 63 and ADR 66, and a developer-guide chapter after
  *Only what your own list says nothing has expanded: `--known`*.

**Tech Stack:** Java 25, Gradle (plain `./gradlew`), JUnit 5, AssertJ, SQLite, ArchUnit.

**Spec:** `docs/superpowers/specs/2026-09-13-second-hop-design.md`. Read it first. **Four of its
claims the code contradicts are corrected in this plan, and each is named at the task that handles
it** — see *Premise corrections* immediately below. Where this plan and the spec differ, this plan
is the authority and the difference is stated out loud.

### Premise corrections

1. **`census` may NOT open `ingest` today.** The spec calls `ingest` "the package that every reader
   of the projection (`export`, `census`, and now `expand`) may open". `ArchitectureTest
   .theCensusOpensNothingElse` bans `..ingest..` for `census` outright, with the reason "no replay".
   Eleven `census` classes import `LogProjection`, so the move breaks that rule. **Task 1 gives the
   rule a named exemption for `LogProjection` alone** — the shape `theBootFoldsOnce` already uses for
   `IngestService` and `nothingWritesToStandardOut` for `SegueApplication` — with a planted positive
   control proving `GraphProjector`, `Replay` and `IngestService` stay banned.
2. **The move makes `census → export` a dead permitted exception.** Every one of the eleven `census`
   imports from `export` is `LogProjection` and nothing else (verified by grep in Task 1 Step 1).
   After the move `census` depends on no sibling dev tool at all, so `theCensusOnlyReads`'s
   `List.of("census", "export")` narrows to `List.of("census")` and the three Javadoc sites, the
   guide's ArchUnit-table row, the guide's package table and the guide's *It counts the export's
   fold* section follow. Leaving the exception in place would document a decision the code no longer
   makes. Task 1 does it, with its own positive control.
3. **The spec's list of import edits is wrong in both directions.** It names "the tests in
   `recommend`, `retract` and `ingest` that build one": `recommend/MergeDoesNotInflateDegreeTest` and
   `retract/RetractRunTest` name `LogProjection` **only inside `//` and Javadoc comments** and have
   no import to change. It omits the twelve files in the `export` package itself — three in
   `src/main`, nine in `src/test` — which use the type with no import today because it is their
   package sibling, and which each need one added. Task 1 Step 1 derives the true set by grep before
   any edit.
4. **Moving `LogProjectionTest` reds `StandInQidsDenoteNothingTest` twice.** That class's `ALLOWED`
   entry for `Q5741069` names `src/test/java/com/robsartin/segue/export/LogProjectionTest.java` as a
   site, and its own Javadoc says moving a test file "reds twice — once on the new path as an
   undeclared site, once on the old path as a dead one". The spec does not mention it. **That red,
   and `DeveloperGuideEnumerationsTest`'s layering-diagram red, are Task 1's two real assertion
   failures** — which is what keeps a pure move from being a task with no observed red at all.

Two smaller deviations, each argued at its task: `NeighboursTest`'s retraction case is restated in
`census` where the fold is visible, because the walk no longer takes a `LogProjection` (Task 2
Step 6); and `KnownListCensusScaleTest`'s three planted-fact assertions are re-expressed through
`SecondHop.isolated()`, because the walk becomes package-private in `domain` (Task 3 Step 8).

---

## Global Constraints

These bind every task. An implementer who sees only one task brief still gets all of them.

- **Pure TDD / red first.** Every behaviour: write the test, **run it, observe a real assertion
  failure**, then the minimum code. **A compile error is never a red.** Where a new type or accessor
  is needed before a test can compile, the step is split: add the **stub** (the signature, returning
  the empty/false/wrong-but-compiling answer), then the test, then observe the assertion failure,
  then the body. **Quote the actual failure text in the task report** — not "it failed".
- **Every guard gets a positive control, written out as its own steps: plant the defect, run the
  check, observe it fire, remove the plant.** The controls this plan requires by name are listed in
  each task; none may be skipped, and the report says what the planted run printed.
- Test names `should<Expected>When<Condition>` with `@DisplayName` on every test.
- **Mikado: the gate is green before every commit**, and each task ends green and is independently
  reviewable. Never a big-bang change guarded only by a final run.
- **Stage by explicit path, git stderr visible — never `git add -A`, never `2>/dev/null` on
  `git add`.** Read `git status` before every commit. Commit subjects name issue #319. Commits end,
  after a blank line, `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`.
- **Never cite a `.superpowers/` path from a committed file.**
- Gate, **blocking, never backgrounded**, run from `/Users/sartin/code/segue/wt-319`:
  `SEGUE_REQUIRE_BROWSER=true SEGUE_REQUIRE_GRAPHVIZ=true ./gradlew check --rerun-tasks`. Run
  `./gradlew spotlessApply` before each gate. **The final task counts the tests ONCE, from
  `build/test-results/test/*.xml`**, and reports that number.
- Per-task loops are `./gradlew test --tests '…'`, **run BLOCKING**. A `--tests` filter that matches
  nothing is a failure mode: check the reported test count is non-zero every time.
- **Only JDK 25 is installed and Gradle launches on it.** Plain `./gradlew`; never
  `/usr/libexec/java_home -v 21`, which silently returns 25.
- **Never run a writing dev task** — `expandPromotions`, `own`, `ownClaim`, `retractEntity`, `rate`,
  `evaluate`, `resolveNames`. **Never read, write, copy or create `~/.segue/segue.db`.** Every
  database in this plan is a `@TempDir` file and every known-list and output file is written by the
  test that reads it.
- **No test in this plan may reach the network.** A non-dry `ExpandCli.main` really expands anything
  the graph holds a node for. Every non-dry test here names **only refusable ids** — an id the graph
  holds no node for. If a step's fixture would hand an expandable node to a non-dry run, stop and
  report.
- **Invented ids only.** Stand-ins carry ADR 58's leading zero (`Q09…`); an id the owner minted
  carries two (`Q00…`); a merge's canonical side is ADR 62's eleven-digit reserved shape and is the
  one place a leading zero is refused. **Never a real Wikidata id**, except through
  `StandInQidsDenoteNothingTest`'s `ALLOWED` map with the reason it is real. The ids this plan
  introduces are `Q0901401`–`Q0901412`; `grep -rn 'Q09014' src docs` finds none of them today.
- **No commit hash, no `.superpowers/` path, no figure from the owner's graph, and no qid enters any
  file under `docs/adr`.** `AdrCitationsTest` reds on a backticked run of 7–40 hex characters, so
  **never put a bare digit run of seven or more inside backticks in ADR prose**; an id written as
  `` `Q0900299` `` does not match, because `Q` is not hex, but the safe habit is to write no
  backticked id in an ADR at all. The census figures on #317 are cited as "the census on #317
  (2026-09-13)" and never restated.
- **Markdown links whole and on one line**, `[text](target)`, no title, no raw HTML —
  `DocumentationLinksTest` refuses every other shape. Date-stamp anything time-bound.
- **Never restate a figure that lives somewhere else.** Cite the issue, the constant or the class.
- **`docs/` and `README.md` are already declared inputs of `tasks.test`** (`build.gradle.kts`, the
  `inputs.dir("docs")` and `inputs.file("README.md")` block) — **verify that before claiming a new
  declaration is needed; none is.** It does mean a document edit re-runs the suite, so run per-task
  loops **without** `--rerun-tasks`.
- **After `./gradlew spotlessApply`, re-read any Javadoc this plan writes** and confirm every
  `{@code …}` span is intact and **on one source line**. google-java-format reflows Javadoc and will
  break inside an inline tag. To put a span on one line, shorten the clause *before* it.
- **No wall-clock assertion anywhere.** The machine is loaded. `KnownListCensusScaleTest` prints a
  timing observation and asserts nothing about it; keep it that way.
- **No entity is ever named on the terminal.** The census block and the expansion block carry
  integers and, at most, a file's **basename** — `support.KnownListInput` is the one home of that
  rule. The `--isolated` file is the one artefact in this issue that names entities, it is personal
  data, and **`CensusIsSafeToPasteTest`'s discipline does not apply to it and must not be added by
  analogy** (`ratings.NamesFile`'s own note gives the reason).

---

## Task 1 — Move `LogProjection` into `ingest`, and follow the two fences it moves

**Files:** `src/main/java/com/robsartin/segue/ingest/LogProjection.java` (moved),
`src/test/java/com/robsartin/segue/ingest/LogProjectionTest.java` (moved), every file the grep in
Step 1 names, `src/test/java/com/robsartin/segue/arch/ArchitectureTest.java`,
`src/test/java/com/robsartin/segue/arch/StandInQidsDenoteNothingTest.java`,
`docs/developer-guide.md`.

**This task changes no behaviour**: the same class, the same fold, a different package. It has two
real reds all the same, and they are the point of doing the move first — the whole suite is the
control that nothing moved with it.

- [ ] **Step 1 — derive the true call-site set before touching anything.** Run all four, blocking,
      and paste the output into the task report:

  ```
  grep -rln '^import com.robsartin.segue.export.LogProjection;' src
  grep -rln 'LogProjection' src/main/java/com/robsartin/segue/export src/test/java/com/robsartin/segue/export
  grep -rn 'LogProjection' src --include=*.java -l | wc -l
  grep -rn 'export/LogProjectionTest' src/test/java/com/robsartin/segue/arch/StandInQidsDenoteNothingTest.java
  ```

  Expected, and **stop and report if it differs**:
  - 25 files carry the explicit import — eleven in `census/main`, thirteen tests in
    `census`/`arch`, and `ingest/OwnerClaimProjectionTest`.
  - Inside `export`, twelve files name the type with no import: `ExportRun` and `ViewKind` (in
    prose only), `ViewSelector` (in code), `LogProjection` itself, and eight tests —
    `BothFoldsAgreeTest`, `ExportOrderIsLogOrderTest` (prose only), `MergeAfterARetractionTest`,
    `MergedIdIsDrawnAsAnOrphanTest`, `RetractedStandInTakesItsEdgesTest`,
    `StandInAgreesInEveryHomeTest`, `StandInKindMatchesTheLocalNodeTest`,
    `TwiceMergedIdLeavesNoOrphanTest`.
  - `recommend/MergeDoesNotInflateDegreeTest`, `retract/RetractRunTest` and `domain/EquivalencesTest`
    name it **only inside comments** — they need no edit. The spec says otherwise; it is wrong.
  - `StandInQidsDenoteNothingTest` names `src/test/java/com/robsartin/segue/export/LogProjectionTest.java`
    as an allowed site for `Q5741069`.

- [ ] **Step 2 — move the two files with `git mv`, so history follows.**

  ```
  git mv src/main/java/com/robsartin/segue/export/LogProjection.java src/main/java/com/robsartin/segue/ingest/LogProjection.java
  git mv src/test/java/com/robsartin/segue/export/LogProjectionTest.java src/test/java/com/robsartin/segue/ingest/LogProjectionTest.java
  ```

  In both files change `package com.robsartin.segue.export;` to `package com.robsartin.segue.ingest;`.
  In `LogProjectionTest`, `InventedGraph` is `export`-package-private — if the test uses it, add
  `import` is not possible, so **stop and report**; the expected shape is that it builds its own
  claims (check before editing).

- [ ] **Step 3 — say in `LogProjection`'s Javadoc why it lives here now.** Insert one paragraph
      immediately after the class's opening paragraph, keeping every existing paragraph:

  ```java
   * <p><b>In {@code ingest} because three readers need it and only one of them may open a dev-tool
   * package (#319).</b> The exporter draws it, the census counts it, and the promotion expander
   * hands its nodes and edges to {@code domain.SecondHop};
   * {@code ArchitectureTest.theExpanderOpensNothingElse} forbids the expander every sibling dev
   * tool with no exception, so a fold that lived in {@code export} could not be the one the
   * expander reads. It cannot go to {@code domain} either — it names {@code port.AssertionLog} and
   * {@code wikidata.KindMapper}, and {@code domain} names neither. It lives beside {@code Replay}
   * and {@code GraphProjector}, which is where the boot's own fold already is, and it is the same
   * move {@code KnownListInput} made out of {@code census} for #313.
  ```

  And correct the sentence at the foot of `of(List, Fold)`'s Javadoc, which says
  `ArchitectureTest.theExportFoldsOnce` "is what keeps this class the export's only fold". Replace
  that clause with:

  ```java
   * <p>{@code LogProjectionTest.shouldGiveTheSameProjectionWhenHandedTheFoldOfWouldCompute} pins
   * the two forms to one answer. {@code ArchitectureTest.theExportFoldsOnce} keeps the export from
   * building a second fold of its own, and since #319 it has no exempt class, because the one fold
   * the export reads is no longer in that package.
  ```

- [ ] **Step 4 — update every import the grep found.** In the 25 files carrying the explicit import,
      change `import com.robsartin.segue.export.LogProjection;` to
      `import com.robsartin.segue.ingest.LogProjection;` and re-sort the import block —
      `com.robsartin.segue.ingest.…` sorts after `com.robsartin.segue.export.…` and after
      `com.robsartin.segue.domain.…`. In `ingest/OwnerClaimProjectionTest` **delete** the import
      outright: it is now a package sibling. In the ten `export` files that use the type in **code**
      (`ViewSelector` plus the eight tests, minus `ExportOrderIsLogOrderTest` and `ExportRun` and
      `ViewKind`, which name it in prose only — take the exact set from Step 1), **add**

  ```java
  import com.robsartin.segue.ingest.LogProjection;
  ```

  `ViewKind`'s `{@link LogProjection}` in Javadoc resolves only with an import, so add one there too
  if `javadoc -Werror` complains; `ExportRun`'s mention is inside a `//` comment and needs nothing.

- [ ] **Step 5 — the first red: the stale allowlist site.** Before editing
      `StandInQidsDenoteNothingTest`, run it and **observe the failure**:

  ```
  ./gradlew test --tests '*StandInQidsDenoteNothingTest'
  ```

  Expect **two** failing tests. `shouldCarryNoDeadSiteWhenTheAllowlistIsCheckedAgainstTheTree` fails
  with a list containing
  `Q5741069 @ src/test/java/com/robsartin/segue/export/LogProjectionTest.java (in code)` and the
  message "sites named by ALLOWED that no longer carry the id they were written about"; and
  `shouldUseAnIdWikidataCannotAllocateWhenATestNamesAnEntityItInvented` fails naming
  `src/test/java/com/robsartin/segue/ingest/LogProjectionTest.java` as an undeclared site. **Quote
  both messages in the report.** Then fix it: in the `Q5741069` entry, change

  ```java
                  code("src/test/java/com/robsartin/segue/export/LogProjectionTest.java"),
  ```

  to

  ```java
                  code("src/test/java/com/robsartin/segue/ingest/LogProjectionTest.java"),
  ```

  keeping the entry's sites in their existing order, and re-run: both green.

- [ ] **Step 6 — the exemption `theCensusOpensNothingElse` needs, and the red that proves it needs
      one.** Run the architecture suite first and **observe the failure**:

  ```
  ./gradlew test --tests '*ArchitectureTest'
  ```

  Expect `theCensusOpensNothingElse` to fail, naming eleven `census` classes depending on
  `com.robsartin.segue.ingest.LogProjection`. **Quote it.** Then, in `ArchitectureTest`, change the
  import to `import com.robsartin.segue.ingest.LogProjection;` and narrow the rule's package clause:

  ```java
  @ArchTest
  static final ArchRule theCensusOpensNothingElse =
      noClasses()
          .that()
          .resideInAPackage("..census..")
          .should()
          .dependOnClassesThat(
              JavaClass.Predicates.resideInAnyPackage(
                      "..tinker..",
                      "..jena..",
                      "..ingest..",
                      "..mcp..",
                      "..app..",
                      "..musicbrainz..")
                  .and(DescribedPredicate.not(JavaClass.Predicates.equivalentTo(LogProjection.class)))
                  .or(ON_A_NETWORK_API)
                  .or(REACHES_A_NETWORK))
          .because(
              "ADR 63: the census folds the log and counts what comes out — it needs no engine, no"
                  + " replay and no network, and cannot become an MCP tool by accident. The one"
                  + " ingest class it may name is LogProjection, which is the fold it counts and"
                  + " not a replay (#319): GraphProjector, Replay and IngestService stay banned");
  ```

  Add this paragraph to the rule's Javadoc, above the existing one:

  ```java
   * <p><b>{@code ingest} is banned as a package with exactly one class carved out of it.</b>
   * {@code LogProjection} moved there in #319 so the promotion expander could read the fold
   * without opening a dev-tool package, and the census counts that fold rather than writing a
   * third one. The carve-out is by class rather than by package precisely so that the clause this
   * rule exists for — no replay — still holds: {@code GraphProjector}, {@code Replay} and
   * {@code IngestService} are each still refused, and the positive control for #319 planted a
   * {@code GraphProjector} dependency in {@code CensusRun} and watched this rule fire.
  ```

  If `DescribedPredicate.not` or the `and`/`or` composition does not compile against this ArchUnit
  version, **stop and report** rather than weakening the rule.

- [ ] **Step 7 — the positive control for Step 6, and it is required.** Plant, in `CensusRun.java`,

  ```java
  import com.robsartin.segue.ingest.GraphProjector;
  ```

  and one unused field referencing it (`private static final Class<?> PLANT = GraphProjector.class;`)
  so the dependency is real. Run `./gradlew test --tests '*ArchitectureTest'` and **observe
  `theCensusOpensNothingElse` fire**, naming `GraphProjector`. Quote the message. **Remove the plant
  and re-run: green.** A control that did not fire means the carve-out is wider than one class —
  stop and report.

- [ ] **Step 8 — narrow `theCensusOnlyReads`, because `census → export` is now dead.** Confirm first:

  ```
  grep -rn '^import com.robsartin.segue.export' src/main/java/com/robsartin/segue/census src/test/java/com/robsartin/segue/census
  ```

  Expect **no output**. Then in `ArchitectureTest` change

  ```java
                              otherDevToolsAnd(List.of("census", "export"))))))
  ```

  to

  ```java
                              otherDevToolsAnd(List.of("census"))))))
  ```

  and the rule's `because` to

  ```java
          .because(
              "ADR 63: counting is a read — the census never appends to the log, never writes the"
                  + " graph, never writes a rating, and since #319 reaches no sibling dev tool at"
                  + " all: the fold it counts moved to ingest, so the one permitted sibling this"
                  + " rule used to name is gone rather than merely unused");
  ```

  Rewrite the rule's `<p><b>{@code export} is the one sibling this tool may reach…</b>` paragraph to
  say that the fold moved and no sibling is permitted now, keeping the `BothFoldsAgreeTest` argument
  for why there is one fold rather than three. Then correct the three Javadoc sites that count the
  open dev-tool pairs — in `theRatingDeckOpensNothingElse`, `theEvaluationHarnessOpensNothingElse`
  and `theExpanderOpensNothingElse` — so that `census → export` is no longer listed among them;
  each now names `rate → recommend` (ADR 46) and `evaluate → recommend` (ADR 65) alone, with
  "since #319" against the removal.

- [ ] **Step 9 — the positive control for Step 8.** Plant, in `census/CensusRun.java`, an import of
      `com.robsartin.segue.export.ExportRun` and a field referencing it. Run
      `./gradlew test --tests '*ArchitectureTest'`, **observe `theCensusOnlyReads` fire**, quote it,
      remove the plant, re-run green.

- [ ] **Step 10 — `theExportFoldsOnce` loses its exemption, and says why.** Its
      `.doNotBelongToAnyOf(LogProjection.class)` now names a class outside the package it fences, so
      the exemption is vacuous and the rule reads more strongly without it. Change the rule to

  ```java
  @ArchTest
  static final ArchRule theExportFoldsOnce =
      noClasses()
          .that()
          .resideInAPackage("com.robsartin.segue.export..")
          .should()
          .accessTargetWhere(
              callTo("in", Equivalences.class)
                  .or(callTo("folding", Equivalences.class))
                  .or(callTo("standIns", Equivalences.class))
                  .or(callTo("nodesTheFoldHolds", Equivalences.class))
                  .or(callTo("retractedStandIns", Equivalences.class))
                  .or(callTo("localsOfMerges", Equivalences.class))
                  .or(callTo("in", Retractions.class))
                  .or(callTo("of", Fold.class)))
          .because(
              "issue #246, and #319: the export reads one fold and builds none. LogProjection is"
                  + " that fold and is no longer in this package, so no class here is exempt —"
                  + " every one of them takes what LogProjection.of holds, and may not reach"
                  + " Fold.of either");
  ```

  and rewrite its Javadoc to say the exempt class left the package in #319. **`theBootFoldsOnce`
  needs no word and must not be edited**: its forbidden list is the seven log-taking statics and
  does **not** include `Fold.of`, `LogProjection` makes none of those seven calls (it reads
  `fold.retractions()`, `fold.standIns()` and `fold.equivalences()` off the `Fold` it is handed),
  and `LogProjection.of(AssertionLog)`'s own `Fold.of` call is not what that rule forbids. Say this
  in the task report in these words, so the decision is on the record.

- [ ] **Step 11 — the positive control for Step 10.** Plant `Fold.of(log.readAll(), KindMapper::rederive)`
      inside `export/ViewSelector.projection()`, assigned to an unused local. Run
      `./gradlew test --tests '*ArchitectureTest'`, **observe `theExportFoldsOnce` fire**, quote it,
      remove the plant, re-run green.

- [ ] **Step 12 — the second red: the guide's layering diagram.** Run

  ```
  ./gradlew test --tests '*DeveloperGuideEnumerationsTest'
  ```

  and **observe** `shouldDrawExactlyTheCrossPackageImports…` fail, reporting `census --> export`
  drawn but absent and `census --> ingest` present but undrawn. **Quote it.** Then, in
  `docs/developer-guide.md`'s mermaid block under `## The layering`:

  - change the `ingest` node label to
    `ingest["ingest<br/>IngestService, GraphProjector, LogProjection"]`;
  - delete the line `  census ==>|"one fold, not two"| export`;
  - add, in the `census` group of edges, `  census --> ingest`.

  Leave `export --> ingest`, which is already drawn. Re-run: green.

- [ ] **Step 13 — the guide's prose follows the code.** In `docs/developer-guide.md`:

  - **the package table**: `ingest`'s *Contents* cell gains `LogProjection` — "`IngestService` (the
    only write path), `GraphProjector` (boot replay) and `LogProjection`, the fold of the whole log
    that `export`, `census` and `expand` all read (#319)"; `census`'s *Depends on* cell replaces
    `export` with `ingest`; `export`'s *Depends on* cell already names `ingest` and is unchanged.
  - **the ArchUnit rules table** (`### Which rules a machine enforces`): rewrite the
    `theCensusOnlyReads` cell so it no longer says `export` is permitted, the
    `theCensusOpensNothingElse` cell so it names the `LogProjection` carve-out, and the
    `theExportFoldsOnce` cell so it no longer says "any `export` class but `LogProjection`".
  - **`### Each tool folds once too`**: the bullet reading "`export` and `census` build a `Fold` and
    thread it" still holds; add a clause saying `LogProjection` lives in `ingest` since #319 so that
    the expander can read it too.
  - **`### It counts the export's fold, not a second one`** (in the census chapter): rename the
    heading to `### It counts the one fold, not a second one`, and rewrite the closing sentence
    "That is why `census` depends on `export`, the second of the two dependencies between dev tools"
    to say that the fold moved to `ingest` in #319, so the census now reaches no sibling dev tool at
    all and `theCensusOpensNothingElse` names `LogProjection` as the one class it may open there.
    **If the heading is linked from anywhere, `DocumentationLinksTest` will red on the anchor** —
    run it and follow what it says rather than guessing.
  - **`### Three things this is not allowed to do`** (census chapter): the first bullet becomes
    "**Write, or reach a sibling at all.** `theCensusOnlyReads` forbids the three world-fact writes,
    both taste-layer writes, `IngestService` — and every dev tool, with no exception since #319";
    the third bullet names the `LogProjection` carve-out.

- [ ] **Step 14 — format, gate, commit.**

  ```
  ./gradlew spotlessApply
  SEGUE_REQUIRE_BROWSER=true SEGUE_REQUIRE_GRAPHVIZ=true ./gradlew check --rerun-tasks
  ```

  Re-read every Javadoc paragraph this task wrote and confirm each `{@code …}` span is intact on one
  source line. **The whole suite is this task's control**: a pure move is proven by everything that
  read the old class still passing. Record the test count. Then `git status`, stage by explicit path,
  commit:

  > Move LogProjection into ingest, where all three of its readers may open it (#319)

---

## Task 2 — `domain.SecondHop`: the one rule, with the walk moved in

**Files:** `src/main/java/com/robsartin/segue/domain/SecondHop.java` (new),
`src/main/java/com/robsartin/segue/domain/Neighbours.java` (moved from `census`),
`src/test/java/com/robsartin/segue/domain/NeighboursTest.java` (moved from `census`),
`src/test/java/com/robsartin/segue/domain/SecondHopTest.java` (new).

`census.Neighbours` and `census.NeighboursTest` are **not deleted in this task** — `KnownListCensus`
still calls them, and the build must stay green. Task 3 removes them once the census reads the rule.

The ids this task introduces, all ADR 58 stand-ins: `ACT` `Q0901401`, `PLACED` `Q0901402`,
`BANDMATE` `Q0901403`, `PRODUCER` `Q0901404`, `RECORD` `Q0901405`, `SECOND_ACT` `Q0901406`,
`SHARED` `Q0901407`, `ABSENT` `Q0901408`.

- [ ] **Step 1 — move the walk into `domain`, with its signature changed.**

  ```
  git mv src/test/java/com/robsartin/segue/census/NeighboursTest.java src/test/java/com/robsartin/segue/domain/NeighboursTest.java
  ```

  Create `src/main/java/com/robsartin/segue/domain/Neighbours.java` by copying
  `census/Neighbours.java` verbatim, changing the package, and changing `in`'s parameter from a
  `LogProjection` to the two things it actually reads — `domain` may not name `ingest`:

  ```java
  package com.robsartin.segue.domain;

  import java.util.Collections;
  import java.util.LinkedHashMap;
  import java.util.LinkedHashSet;
  import java.util.List;
  import java.util.Map;
  import java.util.Objects;
  import java.util.Set;

  /**
   * Who is next to whom, in the fold.
   *
   * <p><b>Package-private, and {@link SecondHop} is its only reader.</b> The walk moved here from
   * {@code census} with the rule it serves (#319), so that the census and the promotion expander
   * ask one question rather than two that agree today. The compiler is the fence: nothing outside
   * {@code domain} can call either method, and nothing inside it but {@code SecondHop} does.
   *
   * <p><b>It takes the fold's nodes and edges rather than the fold.</b> {@code domain} may not name
   * {@code ingest.LogProjection}, and the two maps are the whole of what this reads anyway.
   */
  final class Neighbours {

    private Neighbours() {}

    /**
     * Who each node shares a folded edge with — one home, because the known-list walk is the only
     * thing that asks and it asks it once per population.
     *
     * <p><b>Every node is a key, isolated ones with an empty set.</b> A node nothing reaches is
     * exactly the finding the walk exists to report, and a map that omitted it would answer the
     * question by losing it. {@code Degrees} seeds itself the same way for the same reason.
     *
     * <p><b>Undirected.</b> "Is anything I know within the walk's bound of this" is a question
     * about the graph, not about which end of a relationship Wikidata states it on.
     *
     * <p>{@code LogProjection.edges()} has already dropped the dangling and the withdrawn and
     * applied retraction and merge (ADR 44), so nothing here filters.
     */
    static Map<String, Set<String>> in(Map<String, NodeRecord> nodes, List<EdgeRecord> edges) {
      Objects.requireNonNull(nodes, "nodes");
      Objects.requireNonNull(edges, "edges");
      Map<String, Set<String>> adjacency = new LinkedHashMap<>();
      for (String qid : nodes.keySet()) {
        adjacency.put(qid, new LinkedHashSet<>());
      }
      for (EdgeRecord edge : edges) {
        link(adjacency, edge.fromQid(), edge.toQid());
        link(adjacency, edge.toQid(), edge.fromQid());
      }
      Map<String, Set<String>> copy = new LinkedHashMap<>();
      adjacency.forEach((qid, of) -> copy.put(qid, Collections.unmodifiableSet(of)));
      return Collections.unmodifiableMap(copy);
    }

    /** Both ends have to be nodes: {@code Degrees} uses {@code computeIfPresent} for this. */
    private static void link(Map<String, Set<String>> adjacency, String from, String to) {
      Set<String> of = adjacency.get(from);
      if (of != null && adjacency.containsKey(to)) {
        of.add(to);
      }
    }

    /**
     * Whether any member of {@code population} other than {@code from} sits within {@code hops}.
     *
     * <p>Breadth-first and short-circuiting: the question is whether anything is there, not how
     * many or how far, so the walk stops at the first hit and at the first exhausted frontier.
     *
     * <p><b>{@code from} is marked seen before the first hop</b>, so an entity is never its own
     * known neighbour — including where the log holds a self-loop, which {@code Degrees} counts
     * twice and this deliberately does not count at all.
     */
    static boolean reaches(
        Map<String, Set<String>> adjacency, String from, Set<String> population, int hops) {
      Objects.requireNonNull(adjacency, "adjacency");
      Objects.requireNonNull(from, "from");
      Objects.requireNonNull(population, "population");
      Set<String> seen = new LinkedHashSet<>();
      seen.add(from);
      Set<String> frontier = Set.of(from);
      for (int hop = 0; hop < hops; hop++) {
        Set<String> next = new LinkedHashSet<>();
        for (String at : frontier) {
          for (String neighbour : adjacency.getOrDefault(at, Set.of())) {
            if (!seen.add(neighbour)) {
              continue;
            }
            if (population.contains(neighbour)) {
              return true;
            }
            next.add(neighbour);
          }
        }
        if (next.isEmpty()) {
          return false;
        }
        frontier = next;
      }
      return false;
    }
  }
  ```

- [ ] **Step 2 — rewrite `domain/NeighboursTest` to the new signature, keeping every case.** Change
      the package to `com.robsartin.segue.domain`, drop the `LogProjection`, `InventedCensus` and
      `NodeKind` import churn, and build each fixture by hand. The line-of-five shape and the two
      extra nodes stay, with the same ids the file already declares (`Q0901001`–`Q0901006`,
      `Q0901099`). The helper becomes:

  ```java
    private static Map<String, NodeRecord> nodes(String... qids) {
      Map<String, NodeRecord> nodes = new LinkedHashMap<>();
      for (String qid : qids) {
        nodes.put(qid, new NodeRecord(qid, NodeKind.PERSON, qid));
      }
      return nodes;
    }

    private static EdgeRecord edge(String from, String to) {
      return new EdgeRecord(from, to, "MEMBER_OF", null, null, List.of());
    }

    /** A — B — C — D — E, and F with no edge at all. */
    private static Map<String, Set<String>> line() {
      return Neighbours.in(
          nodes(A, B, C, D, E, F), List.of(edge(A, B), edge(B, C), edge(C, D), edge(D, E)));
    }
  ```

  Five of the six existing cases carry over with `Neighbours.in(line())` replaced by `line()`:
  `shouldHoldBothEndsOfEveryEdgeWhenTheFoldIsRead`,
  `shouldKeyAnIsolatedNodeToAnEmptySetWhenNothingReachesIt`,
  `shouldLinkFromNeitherEndWhenAnEdgesEndpointIsNotANode` (already hand-built — now
  `Neighbours.in(Map.of(A, new NodeRecord(A, NodeKind.PERSON, "A")), List.of(edge(A, UNCLAIMED)))`),
  `shouldReachAtTwoHopsAndMissAtThreeWhenTheWalkIsBounded`,
  `shouldIgnoreANeighbourWhenItIsNotInThePopulation` and
  `shouldNotReachItselfWhenItIsTheOnlyMemberOfThePopulation`. **Keep every `as()` clause verbatim.**

  The sixth, `shouldExcludeARetractedNodesEdgesWhenTheFoldReflectsARetraction`, asserts a property
  of the **fold**, not of the walk, and the walk no longer takes a fold. **Delete it here and
  restate it in `census` at Step 6 of this task**, where a `LogProjection` is in scope and the
  assertion can be made about the rule's own answer rather than about an adjacency map. Say so in
  the task report; this is a deliberate deviation from the spec's "every case kept", and it keeps the
  case rather than dropping it.

- [ ] **Step 3 — run the moved test and observe it green.**

  ```
  ./gradlew test --tests '*domain.NeighboursTest' --tests '*census.NeighboursTest'
  ```

  Both green, non-zero counts in each. This step has no red and that is honest: it is a move.

- [ ] **Step 4 — the stub, so `SecondHopTest` can compile.** Create
      `src/main/java/com/robsartin/segue/domain/SecondHop.java` with the full Javadoc below and
      **deliberately wrong bodies**: `isolated()` returns `List.of()`, `toExpandBeside` returns
      `Set.of()`, `toExpand()` returns `List.of()`.

  ```java
  package com.robsartin.segue.domain;

  import java.util.Collections;
  import java.util.LinkedHashSet;
  import java.util.List;
  import java.util.Map;
  import java.util.Objects;
  import java.util.Set;

  /**
   * The known-list acts the graph cannot place, and the entities worth fetching next (#319).
   *
   * <p><b>One rule, two readers.</b> {@code graphCensus --known} counts by it and
   * {@code expandPromotions --second-hop} chooses its population by it, on the shape
   * {@link Expanded} and {@link Retractions} already set: one home per question, whoever asks. A
   * second copy of "isolated" in the tool that spends money on it is how the diagnosis and the run
   * come to disagree.
   *
   * <p><b>A pure function of four inputs.</b> The fold's nodes and edges, a known population on its
   * canonical side, and {@link Expanded} on the same side. It reads no rating, no timestamp and no
   * label, holds no store, opens nothing and makes no network call — so it takes ordinary unit
   * tests on invented graphs.
   *
   * <p><b>The bound is {@link Recommendations#MAX_HOPS}, by reference.</b> It is the recommender's
   * own route limit, read here rather than restated, so that moving the constant moves this rule
   * with it — the same reason the census row is named after the constant rather than after its
   * current value.
   *
   * <p><b>{@link #WORTH_EXPANDING} is the one statement of which kinds are worth fetching.</b> The
   * census's rows, the expander's population and ADR 63's and ADR 66's amendments all cite it
   * rather than naming the kinds again.
   */
  public final class SecondHop {

    /**
     * The kinds a second hop fetches: a person or a group beside an act the graph cannot place is
     * a bandmate, a producer or a label-mate, and its own neighbourhood is what could connect that
     * act to something known. A work, a place, an event or a concept beside it is a catalogue
     * entry or a category, and expanding one buys the ring nothing.
     */
    public static final Set<NodeKind> WORTH_EXPANDING = Set.of(NodeKind.PERSON, NodeKind.GROUP);

    private final Map<String, NodeRecord> nodes;
    private final Map<String, Set<String>> adjacency;
    private final Expanded expanded;

    /** In the population's own order, distinct — a {@code LinkedHashSet} so both hold at once. */
    private final Set<String> isolated;

    private SecondHop(
        Map<String, NodeRecord> nodes,
        Map<String, Set<String>> adjacency,
        Expanded expanded,
        Set<String> isolated) {
      this.nodes = nodes;
      this.adjacency = adjacency;
      this.expanded = expanded;
      this.isolated = isolated;
    }

    /**
     * Read the rule for one population.
     *
     * @param nodes the fold's nodes — {@code LogProjection.nodes()}
     * @param edges the fold's edges — {@code LogProjection.edges()}, which has already dropped the
     *     dangling and the withdrawn and applied retraction and merge (ADR 44)
     * @param population the known population, on its canonical side, in its own order
     * @param expanded who some row cites as an expansion's seed, on the same side
     */
    public static SecondHop of(
        Map<String, NodeRecord> nodes,
        List<EdgeRecord> edges,
        List<String> population,
        Expanded expanded) {
      throw new UnsupportedOperationException("stub");
    }

    /**
     * The members the graph holds a node for that have no <i>other</i> member within
     * {@link Recommendations#MAX_HOPS} hops, in the population's own order.
     *
     * <p>A member the fold holds no node for is neither isolated nor an error: it is the first
     * coverage gap there is, and the census counts it under {@code named} and not under
     * {@code in the graph}.
     */
    public List<String> isolated() {
      return List.of();
    }

    /**
     * The nodes one folded edge from an isolated member whose kind is in {@link #WORTH_EXPANDING}
     * and that {@link Expanded} does not cover.
     *
     * @throws IllegalArgumentException if {@code isolated} is not one of {@link #isolated()} — the
     *     question is about an act the graph cannot place, and asking it about any other id is a
     *     caller error rather than an empty answer
     */
    public Set<String> toExpandBeside(String isolated) {
      return Set.of();
    }

    /**
     * The union of {@link #toExpandBeside} over every isolated member, distinct, in first-seen
     * order over {@link #isolated()} — so a neighbour beside two isolated acts is in it once.
     */
    public List<String> toExpand() {
      return List.of();
    }
  }
  ```

- [ ] **Step 5 — write `SecondHopTest`, run it, and observe real assertion failures.** Create
      `src/test/java/com/robsartin/segue/domain/SecondHopTest.java`. Every id is declared as a
      constant with a comment saying what it is for.

  ```java
  package com.robsartin.segue.domain;

  import static org.assertj.core.api.Assertions.assertThat;
  import static org.assertj.core.api.Assertions.assertThatThrownBy;

  import java.util.LinkedHashMap;
  import java.util.List;
  import java.util.Map;
  import java.util.Set;
  import org.junit.jupiter.api.DisplayName;
  import org.junit.jupiter.api.Test;

  /**
   * The rule, on hand-built graphs small enough to read off the page.
   *
   * <p>Every id is invented and carries ADR 58's leading zero, so none of them denotes anything.
   */
  class SecondHopTest {

    /** On the list, in the graph, and nothing else on the list is within MAX_HOPS of it. */
    private static final String ACT = "Q0901401";

    /** On the list, one hop from {@link #ACT} — the control that isolation is not universal. */
    private static final String PLACED = "Q0901402";

    /** A PERSON beside {@link #ACT}, not on the list, and no row cites it as a seed. */
    private static final String BANDMATE = "Q0901403";

    /** A PERSON beside {@link #ACT} that a row does cite as a seed. */
    private static final String PRODUCER = "Q0901404";

    /** A WORK beside {@link #ACT} — a kind WORTH_EXPANDING does not name. */
    private static final String RECORD = "Q0901405";

    /** A second isolated act on the list, sharing {@link #SHARED} with the first. */
    private static final String SECOND_ACT = "Q0901406";

    /** An unexpanded PERSON beside both isolated acts. */
    private static final String SHARED = "Q0901407";

    /** Named by the list and never claimed as a node. */
    private static final String ABSENT = "Q0901408";

    private static Map<String, NodeRecord> nodes(Map<String, NodeKind> kinds) {
      Map<String, NodeRecord> nodes = new LinkedHashMap<>();
      kinds.forEach((qid, kind) -> nodes.put(qid, new NodeRecord(qid, kind, "a label for " + qid)));
      return nodes;
    }

    private static EdgeRecord edge(String from, String to) {
      return new EdgeRecord(from, to, "MEMBER_OF", null, null, List.of());
    }

    @Test
    @DisplayName("an act with no member within the hop limit is isolated, and its unexpanded"
        + " person is to expand")
    void shouldReportTheUnexpandedPersonWhenAnIsolatedActHasOneBesideIt() {
      SecondHop rule =
          SecondHop.of(
              nodes(
                  new LinkedHashMap<>(
                      Map.of(ACT, NodeKind.GROUP, BANDMATE, NodeKind.PERSON))),
              List.of(edge(ACT, BANDMATE)),
              List.of(ACT),
              new Expanded(Set.of()));

      assertThat(rule.isolated()).containsExactly(ACT);
      assertThat(rule.toExpandBeside(ACT)).containsExactly(BANDMATE);
      assertThat(rule.toExpand()).containsExactly(BANDMATE);
    }

    @Test
    @DisplayName("an isolated act whose only neighbour is already expanded has no one beside it")
    void shouldReportNoOneToExpandWhenTheOnlyNeighbourIsAlreadyExpanded() {
      SecondHop rule =
          SecondHop.of(
              nodes(new LinkedHashMap<>(Map.of(ACT, NodeKind.GROUP, PRODUCER, NodeKind.PERSON))),
              List.of(edge(ACT, PRODUCER)),
              List.of(ACT),
              new Expanded(Set.of(PRODUCER)));

      assertThat(rule.isolated()).as("still isolated — expansion is not placement").containsExactly(ACT);
      assertThat(rule.toExpandBeside(ACT)).isEmpty();
      assertThat(rule.toExpand()).isEmpty();
    }

    @Test
    @DisplayName("an act with a member within the hop limit is not isolated and contributes nothing")
    void shouldContributeNothingWhenTheActHasAMemberWithinTheHopLimit() {
      // The planted control for "isolated first": PLACED is one hop from ACT and BOTH have an
      // unexpanded person beside them. A rule that read "unexpanded neighbours" without reading
      // isolation would return BANDMATE here.
      SecondHop rule =
          SecondHop.of(
              nodes(
                  new LinkedHashMap<>(
                      Map.of(
                          ACT, NodeKind.GROUP,
                          PLACED, NodeKind.GROUP,
                          BANDMATE, NodeKind.PERSON))),
              List.of(edge(ACT, PLACED), edge(ACT, BANDMATE)),
              List.of(ACT, PLACED),
              new Expanded(Set.of()));

      assertThat(rule.isolated()).isEmpty();
      assertThat(rule.toExpand()).as("nothing is isolated, so there is nothing to expand").isEmpty();
    }

    @Test
    @DisplayName("a neighbour of a kind the constant does not name is not to expand")
    void shouldLeaveOutTheNeighbourWhenItsKindIsNotWorthExpanding() {
      SecondHop rule =
          SecondHop.of(
              nodes(new LinkedHashMap<>(Map.of(ACT, NodeKind.GROUP, RECORD, NodeKind.WORK))),
              List.of(edge(ACT, RECORD)),
              List.of(ACT),
              new Expanded(Set.of()));

      assertThat(rule.isolated()).containsExactly(ACT);
      assertThat(rule.toExpandBeside(ACT))
          .as("SecondHop.WORTH_EXPANDING names PERSON and GROUP, and a WORK is neither")
          .isEmpty();
    }

    @Test
    @DisplayName("a neighbour beside two isolated acts is in the population once")
    void shouldCountTheSharedNeighbourOnceWhenTwoIsolatedActsBothTouchIt() {
      SecondHop rule =
          SecondHop.of(
              nodes(
                  new LinkedHashMap<>(
                      Map.of(
                          ACT, NodeKind.GROUP,
                          SECOND_ACT, NodeKind.GROUP,
                          SHARED, NodeKind.PERSON))),
              List.of(edge(ACT, SHARED), edge(SECOND_ACT, SHARED)),
              List.of(ACT, SECOND_ACT),
              new Expanded(Set.of()));

      assertThat(rule.isolated())
          .as("two hops apart through SHARED, which is not on the list, so neither places the other")
          .containsExactly(ACT, SECOND_ACT);
      assertThat(rule.toExpandBeside(ACT)).containsExactly(SHARED);
      assertThat(rule.toExpandBeside(SECOND_ACT)).containsExactly(SHARED);
      assertThat(rule.toExpand()).as("distinct, in first-seen order").containsExactly(SHARED);
    }

    @Test
    @DisplayName("the direction the edge was stated in does not decide who is beside whom")
    void shouldReadTheEdgeFromBothEndsWhenTheNeighbourIsTheSubject() {
      SecondHop rule =
          SecondHop.of(
              nodes(new LinkedHashMap<>(Map.of(ACT, NodeKind.GROUP, BANDMATE, NodeKind.PERSON))),
              List.of(edge(BANDMATE, ACT)),
              List.of(ACT),
              new Expanded(Set.of()));

      assertThat(rule.toExpandBeside(ACT)).containsExactly(BANDMATE);
    }

    @Test
    @DisplayName("a member the fold holds no node for is neither isolated nor an error")
    void shouldPassOverTheMemberWhenTheFoldHoldsNoNodeForIt() {
      SecondHop rule =
          SecondHop.of(
              nodes(new LinkedHashMap<>(Map.of(ACT, NodeKind.GROUP, BANDMATE, NodeKind.PERSON))),
              List.of(edge(ACT, BANDMATE)),
              List.of(ACT, ABSENT),
              new Expanded(Set.of()));

      assertThat(rule.isolated()).containsExactly(ACT);
    }

    @Test
    @DisplayName("asking what to expand beside an id that is not isolated is refused")
    void shouldThrowWhenAskedWhatToExpandBesideAnIdThatIsNotIsolated() {
      SecondHop rule =
          SecondHop.of(
              nodes(
                  new LinkedHashMap<>(
                      Map.of(ACT, NodeKind.GROUP, PLACED, NodeKind.GROUP, BANDMATE, NodeKind.PERSON))),
              List.of(edge(ACT, PLACED), edge(ACT, BANDMATE)),
              List.of(ACT, PLACED),
              new Expanded(Set.of()));

      assertThatThrownBy(() -> rule.toExpandBeside(PLACED))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining(PLACED);
    }
  }
  ```

  Run, **blocking**:

  ```
  ./gradlew test --tests '*SecondHopTest'
  ```

  Expect eight failures. The first is `UnsupportedOperationException: stub` out of `of` — **that is
  not an assertion failure**, so before going further replace `of`'s body with the minimum that
  returns an instance carrying empty state, re-run, and **observe the real assertion failures**
  (`Expecting actual: [] to contain exactly: ["Q0901401"]` and the like). **Quote two of them in the
  report.** Then write the real bodies:

  ```java
    public static SecondHop of(
        Map<String, NodeRecord> nodes,
        List<EdgeRecord> edges,
        List<String> population,
        Expanded expanded) {
      Objects.requireNonNull(nodes, "nodes");
      Objects.requireNonNull(edges, "edges");
      Objects.requireNonNull(population, "population");
      Objects.requireNonNull(expanded, "expanded");
      Map<String, NodeRecord> heldNodes = Map.copyOf(nodes);
      Map<String, Set<String>> adjacency = Neighbours.in(heldNodes, edges);
      Set<String> members = Set.copyOf(population);
      Set<String> isolated = new LinkedHashSet<>();
      for (String qid : population) {
        if (heldNodes.containsKey(qid)
            && !Neighbours.reaches(adjacency, qid, members, Recommendations.MAX_HOPS)) {
          isolated.add(qid);
        }
      }
      return new SecondHop(
          heldNodes, adjacency, expanded, Collections.unmodifiableSet(isolated));
    }

    public List<String> isolated() {
      return List.copyOf(isolated);
    }

    public Set<String> toExpandBeside(String isolated) {
      Objects.requireNonNull(isolated, "isolated");
      if (!this.isolated.contains(isolated)) {
        throw new IllegalArgumentException(
            "not an isolated member of this population: " + isolated);
      }
      Set<String> beside = new LinkedHashSet<>();
      for (String neighbour : adjacency.getOrDefault(isolated, Set.of())) {
        NodeRecord node = nodes.get(neighbour);
        if (node != null && WORTH_EXPANDING.contains(node.kind()) && !expanded.covers(neighbour)) {
          beside.add(neighbour);
        }
      }
      return Collections.unmodifiableSet(beside);
    }

    public List<String> toExpand() {
      Set<String> union = new LinkedHashSet<>();
      for (String member : isolated) {
        union.addAll(toExpandBeside(member));
      }
      return List.copyOf(union);
    }
  ```

  `isolated` is held as a `LinkedHashSet` rather than a `List` so that the membership check
  `toExpandBeside` makes is constant-time and the population's order survives; a duplicate in
  `population` therefore produces one row rather than two. Re-run: green.

- [ ] **Step 6 — restate the retraction case where the fold is visible.** In
      `src/test/java/com/robsartin/segue/census/KnownListCensusTest.java`, add a case that folds a
      log holding a retraction and asks the rule, not an adjacency map. It replaces the case deleted
      at Step 2 and asserts something stronger — that the retraction changes the rule's own answer:

  ```java
    @Test
    @DisplayName("an act whose only known neighbour was retracted is isolated")
    void shouldReportTheActAsIsolatedWhenARetractionTookAwayItsOnlyKnownNeighbour() {
      // Restated here from NeighboursTest (#319): the walk no longer takes a LogProjection, so the
      // property "the fold's retraction reaches the walk" is asserted where a fold is in scope, and
      // against the rule's own answer rather than against an adjacency map.
      List<LoggedAssertion> log =
          List.of(
              InventedCensus.node(SEEN, NodeKind.PERSON, "An Invented Performer"),
              InventedCensus.node(BAND, NodeKind.GROUP, "An Invented Band"),
              InventedCensus.edge(SEEN, BAND, "MEMBER_OF", InventedCensus.sourced()),
              InventedCensus.retract(BAND));
      Fold fold = Fold.of(log, KindMapper::rederive);
      LogProjection projection = LogProjection.of(log, fold);

      SecondHop rule =
          SecondHop.of(
              projection.nodes(), projection.edges(), List.of(SEEN, BAND), Expanded.in(log));

      assertThat(rule.isolated())
          .as("the retraction removed BAND's node claim and every edge touching it")
          .containsExactly(SEEN);
    }
  ```

  Run `./gradlew test --tests '*KnownListCensusTest'` and observe it green. (It reads `SecondHop`
  directly; `KnownListCensus` does not yet.)

- [ ] **Step 7 — format, gate, commit.** `./gradlew spotlessApply`, then the full gate, blocking.
      Re-read `SecondHop`'s and `Neighbours`' Javadoc after formatting and confirm every
      `{@code …}` and `{@link …}` span is intact on one source line. `git status`, stage by explicit
      path, commit:

  > Add domain.SecondHop, with the known-list walk moved in from census (#319)

---

## Task 3 — The census reads the rule: three rows under each population

**Files:** `src/main/java/com/robsartin/segue/census/KnownListCensus.java`,
`src/main/java/com/robsartin/segue/census/CensusReport.java`,
`src/main/java/com/robsartin/segue/census/Neighbours.java` (deleted),
`src/test/java/com/robsartin/segue/census/NeighboursTest.java` (deleted),
`src/test/java/com/robsartin/segue/census/KnownListCensusTest.java`,
`src/test/java/com/robsartin/segue/census/KnownListCensusScaleTest.java`,
`src/test/java/com/robsartin/segue/census/CensusReportTest.java`.

- [ ] **Step 1 — the stub, so the tests compile.** Add three components to
      `KnownListCensus.Population`, between `noKnownNeighbourWithinMaxHops` and the two maps, each
      with its own `@param`:

  ```java
    /**
     * ...
     * @param isolatedWithSomeoneToExpand of those, the ones with at least one person or group
     *     beside them that no expansion has covered — {@code SecondHop.WORTH_EXPANDING} is the one
     *     statement of which kinds those are, cited rather than restated
     * @param isolatedWithNoOne of those, the ones with none. These two partition the row above
     *     them, so a reader can add them and check
     * @param distinctToExpand the distinct people and groups to expand across every isolated
     *     member of this population — the spend a {@code --second-hop} run would make, before any
     *     run (#319)
     */
    public record Population(
        int named,
        int inTheGraph,
        int neverExpanded,
        int noKnownNeighbourWithinMaxHops,
        int isolatedWithSomeoneToExpand,
        int isolatedWithNoOne,
        int distinctToExpand,
        Map<NodeKind, Integer> inTheGraphByKind,
        Map<NodeKind, Integer> neverExpandedByKind) {
  ```

  and have `read` pass `0, 0, 0` for the three, so the build compiles and every existing census test
  is green except the ones that name the new rows. Run
  `./gradlew test --tests 'com.robsartin.segue.census.*'` and confirm green.

- [ ] **Step 2 — write the failing tests in `KnownListCensusTest`.** Add three cases. The existing
      fixture's shape is `QUIET — BAND* — SEEN* — LINK_A — LINK_B — FAR`, and the cases pick
      populations off it:

  ```java
    @Test
    @DisplayName("an isolated act with an unexpanded person beside it is counted under both rows")
    void shouldCountTheIsolatedActUnderWithSomeoneWhenAnUnexpandedPersonIsBesideIt() {
      // FAR is three hops from SEEN, so both are isolated. LINK_B is a PERSON beside FAR that no
      // row cites as a seed; BAND is a GROUP beside SEEN that a row DOES cite.
      KnownListCensus.Population population = census(List.of(SEEN, FAR)).fromFile();

      assertThat(population.noKnownNeighbourWithinMaxHops()).isEqualTo(2);
      assertThat(population.isolatedWithSomeoneToExpand())
          .as("SEEN has LINK_A beside it and FAR has LINK_B, and neither is cited as a seed")
          .isEqualTo(2);
      assertThat(population.isolatedWithNoOne()).isZero();
      assertThat(population.distinctToExpand())
          .as("LINK_A and LINK_B — BAND is a seed, and QUIET is a WORK two hops out")
          .isEqualTo(2);
    }

    @Test
    @DisplayName("the two nested rows partition the isolated row, whatever the population")
    void shouldPartitionTheIsolatedRowWhenEitherPopulationIsRead() {
      KnownListCensus census = census(List.of(SEEN, FAR, QUIET));

      for (KnownListCensus.Population population :
          List.of(census.fromFile(), census.withPromotions())) {
        assertThat(population.isolatedWithSomeoneToExpand() + population.isolatedWithNoOne())
            .as("with someone plus with no one is the isolated row itself")
            .isEqualTo(population.noKnownNeighbourWithinMaxHops());
      }
    }

    @Test
    @DisplayName("a promotion that places an act moves the three rows for the second population")
    void shouldMoveTheThreeRowsWhenAPromotionPlacesAnIsolatedAct() {
      // The file names SEEN and FAR, three hops apart, so both are isolated. Promoting LINK_B puts
      // a member one hop from FAR and two from LINK_A — so in the second population FAR is placed,
      // LINK_B is placed, and only SEEN is left isolated.
      KnownListCensus census =
          census(List.of(SEEN, FAR), Map.of(LINK_B, KnownList.PROMOTION_RATING));

      assertThat(census.fromFile().noKnownNeighbourWithinMaxHops()).isEqualTo(2);
      assertThat(census.withPromotions().noKnownNeighbourWithinMaxHops())
          .as("the promotion places FAR and itself, leaving SEEN alone")
          .isEqualTo(1);
      assertThat(census.withPromotions().distinctToExpand())
          .as("only what is beside SEEN: LINK_A, since BAND is already a seed")
          .isEqualTo(1);
    }
  ```

  **The numbers above are derived from the fixture by hand and may be wrong. Run the tests, read the
  actual values out of the failure, and only then decide whether the expectation or the
  implementation is wrong** — the fixture's kinds are `SEEN` PERSON, `BAND` GROUP, `QUIET` WORK,
  `FAR` PERSON, `LINK_A` PERSON, `LINK_B` PERSON, and `WORTH_EXPANDING` is PERSON and GROUP. If a
  hand-derived number disagrees with a correct implementation, fix the number in the test and say so
  in the report; if the implementation is what is wrong, fix the implementation.

  ```
  ./gradlew test --tests '*KnownListCensusTest'
  ```

  **Observe real assertion failures** (`expected: 2 but was: 0`). Quote them.

- [ ] **Step 3 — make them pass: `KnownListCensus` reads the rule.** Replace the `Neighbours` calls
      with `SecondHop`. `of` becomes:

  ```java
      Equivalences merges = fold.equivalences();
      List<String> fromFile = merges.canonical(known.qids());
      List<String> withPromotions = KnownList.promoted(fromFile, merges.resolve(ratings));
      Expanded seeds = expanded.onTheCanonicalSide(merges);
      return new KnownListCensus(
          known.name(),
          read(fromFile, seeds, projection, secondHop(projection, fromFile, seeds)),
          read(withPromotions, seeds, projection, secondHop(projection, withPromotions, seeds)));
  ```

  with

  ```java
    /** One population's answer to "which acts can the graph not place, and what is beside them". */
    private static SecondHop secondHop(
        LogProjection projection, List<String> population, Expanded seeds) {
      return SecondHop.of(projection.nodes(), projection.edges(), population, seeds);
    }
  ```

  and `read` taking the rule instead of the adjacency map:

  ```java
    private static Population read(
        List<String> population,
        Expanded expanded,
        LogProjection projection,
        SecondHop secondHop) {
      Map<NodeKind, Integer> inTheGraphByKind = zeroed();
      Map<NodeKind, Integer> neverExpandedByKind = zeroed();
      int inTheGraph = 0;
      int neverExpanded = 0;
      for (String qid : population) {
        NodeRecord node = projection.nodes().get(qid);
        if (node == null) {
          continue;
        }
        inTheGraph++;
        inTheGraphByKind.merge(node.kind(), 1, Integer::sum);
        if (!expanded.covers(qid)) {
          neverExpanded++;
          neverExpandedByKind.merge(node.kind(), 1, Integer::sum);
        }
      }
      List<String> isolated = secondHop.isolated();
      int withSomeone = 0;
      for (String qid : isolated) {
        if (!secondHop.toExpandBeside(qid).isEmpty()) {
          withSomeone++;
        }
      }
      return new Population(
          population.size(),
          inTheGraph,
          neverExpanded,
          isolated.size(),
          withSomeone,
          isolated.size() - withSomeone,
          secondHop.toExpand().size(),
          inTheGraphByKind,
          neverExpandedByKind);
    }
  ```

  Note `noKnownNeighbourWithinMaxHops` is now `isolated().size()` rather than a counter in the loop:
  the rule dedupes the population, where the loop counted a repeated id twice. Both populations are
  distinct by construction (`Equivalences.canonical` and `KnownList.promoted`), so no figure moves —
  say so in the report. Add a Javadoc paragraph to `KnownListCensus` saying the section reads
  `domain.SecondHop` and does not walk the graph itself, on `Expanded`'s precedent. Re-run: green.

- [ ] **Step 4 — delete `census.Neighbours` and its test.**

  ```
  git rm src/main/java/com/robsartin/segue/census/Neighbours.java src/test/java/com/robsartin/segue/census/NeighboursTest.java
  ```

  The build will not compile until Step 8 fixes `KnownListCensusScaleTest`, which still calls them;
  do Steps 5–8 before running anything.

- [ ] **Step 5 — the report rows: write the failing golden first.** In
      `CensusReportTest`'s pinned block, add three lines under **each** population's
      `no known neighbour within 2 hops` row. `CensusReport`'s padding rule gives `labelWidth = 38`
      (the widest counted label is `  merges superseded but edge-referenced`) and `countWidth = 4`,
      and **none of the three new labels is wider than 38**, so every existing line in the block is
      unchanged. The three lines, exactly, at the block's own indentation:

  ```
                with someone to expand beside        0
                with no one                          1
                distinct to expand                   0
  ```

  Both populations take the same three values for this fixture: the only isolated member is
  `REROUTED`, the stand-in `DOUBLE`'s second merge gives the canonical side, and nothing in the
  fixture ever claims an edge touching it. **That makes this golden a pin on the labels, the nesting
  and the alignment and not on the arithmetic** — say so in the report, and note that
  `KnownListCensusTest` is where the three rows are pinned non-vacuously. Run

  ```
  ./gradlew test --tests '*CensusReportTest'
  ```

  and **observe the assertion failure** showing the rows missing from the actual block. Quote it.

- [ ] **Step 6 — render the rows.** In `CensusReport`, add

  ```java
    /** A row nested one level under {@link #nested} — the three that break the isolated row down. */
    private static Line deeper(String label, int value) {
      return new Line("      " + label, value);
    }
  ```

  and, in `rows`, immediately after the `no known neighbour within …` row:

  ```java
      // Nested one level under the row they break down: the first two partition it and the third
      // is what a --second-hop run would visit. The labels name no kind — SecondHop.WORTH_EXPANDING
      // is the one statement of which kinds those are, and ADR 63's amendment cites it (#319).
      body.add(deeper("with someone to expand beside", population.isolatedWithSomeoneToExpand()));
      body.add(deeper("with no one", population.isolatedWithNoOne()));
      body.add(deeper("distinct to expand", population.distinctToExpand()));
  ```

  Add a sentence to `CensusReport`'s class Javadoc noting that all three are integers, so
  *Every value is an integer* holds and `CensusIsSafeToPasteTest`'s existing two cases cover them by
  running over the new rows. Re-run: green.

- [ ] **Step 7 — the positive control for the nesting.** Temporarily change `deeper`'s indent from
      six spaces to four. Run `./gradlew test --tests '*CensusReportTest'` and **observe the golden
      fail on the indentation**, proving the pin reads the nesting rather than only the labels. Quote
      it. Restore the six spaces and re-run green.

- [ ] **Step 8 — `KnownListCensusScaleTest` stays the control, through the rule.** Its last three
      assertions call `Neighbours.in` and `Neighbours.reaches`, which are now package-private in
      `domain`. Replace that block with the same three planted facts asked of `SecondHop`:

  ```java
      // The planted facts, through the rule rather than through the walk it moved into (#319):
      // an isolated member is reported and one exactly two hops from another is not.
      SecondHop rule =
          SecondHop.of(
              projection.nodes(), projection.edges(), file, Expanded.in(log));

      assertThat(rule.isolated()).as("ISOLATED carries no edge at all").contains(ISOLATED);
      assertThat(rule.isolated())
          .as("TWO_HOP_A reaches TWO_HOP_B through BRIDGE, exactly two hops away, and back")
          .doesNotContain(TWO_HOP_A, TWO_HOP_B);
  ```

  Add three assertions over the census's own new rows, in the shape the class already uses:

  ```java
      assertThat(population.isolatedWithSomeoneToExpand() + population.isolatedWithNoOne())
          .as("the two nested rows partition the isolated row")
          .isEqualTo(population.noKnownNeighbourWithinMaxHops());
      assertThat(population.distinctToExpand())
          .as("at most the whole graph, and non-negative")
          .isNotNegative();
      assertThat(promoted.isolatedWithSomeoneToExpand() + promoted.isolatedWithNoOne())
          .as("and for the second population too")
          .isEqualTo(promoted.noKnownNeighbourWithinMaxHops());
  ```

  Add the `SecondHop` and `Expanded` imports and update the class Javadoc's paragraph about the
  planted facts to say the rule is what is asked. **Assert nothing about time** — the printed timing
  line stays an observation. Then run, blocking:

  ```
  ./gradlew test --tests 'com.robsartin.segue.census.*'
  ```

  Green, and record the scale test's printed `flag_ms` in the task report as the observation the
  runbook's "seconds rather than minutes" claim rests on.

- [ ] **Step 9 — format, gate, commit.** `./gradlew spotlessApply`, full gate blocking, re-read the
      Javadoc for broken `{@code …}` spans, `git status`, stage by explicit path, commit:

  > The census reads SecondHop and breaks the isolated row into three (#319)

---

## Task 4 — `graphCensus --isolated <file>`: naming the acts the graph cannot place

**Files:** `src/main/java/com/robsartin/segue/census/IsolatedFile.java` (new),
`src/main/java/com/robsartin/segue/census/Census.java`,
`src/main/java/com/robsartin/segue/census/CensusRun.java`,
`src/main/java/com/robsartin/segue/census/CensusCli.java`,
`src/test/java/com/robsartin/segue/census/IsolatedFileTest.java` (new),
`src/test/java/com/robsartin/segue/census/CensusCliTest.java`,
`src/test/java/com/robsartin/segue/census/CensusRunTest.java`,
`src/test/java/com/robsartin/segue/census/CensusIsSafeToPasteTest.java`.

**The file is personal data.** It carries entity ids and labels off the owner's own list — exactly
what the census block exists never to print. `CensusIsSafeToPasteTest`'s discipline applies to the
terminal report and **not** to this file, and must not be added to it by analogy;
`ratings.NamesFile`'s own note is the precedent and the reason.

The ids this task introduces: `ALONE` `Q0901409`, `NAMELESS` `Q0901410`, `NEXT_TO` `Q0901411`.

- [ ] **Step 1 — the carrier, so `CensusRun` can reach the rule without folding twice.**
      `theCensusFoldsOnce` exempts `Census` alone, so nothing else in the package may call
      `Fold.of`; `Census.of` therefore hands the rule back rather than letting `CensusRun` rebuild
      it. Add to `Census`:

  ```java
    /**
     * One run's answers: the block, the fold it counted, and — when a known-list file was named —
     * the rule the isolated file is written from (#319).
     *
     * <p><b>A second type rather than a component of {@link Census}.</b> This record carries a
     * {@code SecondHop}, which holds entity ids, and {@code Census}'s own guarantee is that every
     * component of it is an integer or a map of integers but for the class qids ADR 63's
     * 2026-09-04 amendment allows. Widening that guarantee to let one dev-tool file be written
     * would be paying for the file with the property the block's paste rule rests on.
     *
     * @param census what {@code CensusReport} renders
     * @param projection the fold, for the labels and kinds the file's rows carry
     * @param isolation the rule read over the population WITH promotions, or empty where no
     *     {@code --known} file was given
     */
    public record Reading(
        Census census, LogProjection projection, Optional<SecondHop> isolation) {

      public Reading {
        Objects.requireNonNull(census, "census");
        Objects.requireNonNull(projection, "projection");
        Objects.requireNonNull(isolation, "isolation");
      }
    }
  ```

  Add `Census.reading(log, ratings, known)` carrying the body `of` has today plus the rule, and make
  `of` a one-line delegate so every existing caller keeps its signature:

  ```java
    public static Census of(AssertionLog log, AffinityStore ratings, Optional<KnownListInput> known) {
      return reading(log, ratings, known).census();
    }
  ```

  `reading` composes the with-promotions population exactly as `KnownListCensus.of` does — through
  `Equivalences.canonical` and `KnownList.promoted` over `merges.resolve(scores)` — so the two
  cannot answer different questions. **If that means the composition would be written twice, expose
  it from `KnownListCensus` as a package-private static that both call, and say so in the report.**
  Run `./gradlew test --tests 'com.robsartin.segue.census.*'` and confirm green: nothing has changed
  behaviour yet.

- [ ] **Step 2 — the stub and the failing test for the file's format.** Create `IsolatedFile` with
      the header constant and `write` returning `0` without writing anything:

  ```java
  package com.robsartin.segue.census;

  /**
   * The isolated acts, as a file the owner can read (#319).
   *
   * <p><b>This is personal data and the census block is not.</b> It holds entity ids and labels off
   * the owner's own list, which is exactly what {@code CensusReport} exists never to print — so
   * {@code CensusIsSafeToPasteTest}'s discipline applies to the terminal report and <b>not</b> to
   * this file, and must not be added here by analogy. {@code ratings.NamesFile} carries the same
   * note for the same reason.
   *
   * <p><b>Tab-separated, not CSV.</b> Labels contain commas, and this is a listing to read rather
   * than a table to load — the call {@code RatingsTable} already made in <i>Plain text rather than
   * CSV</i>.
   *
   * <p><b>The population's own order, and no sort.</b> The file's order and then the promotions
   * ascending by qid, as {@code KnownList.promoted} gives it. A second ordering rule would be a
   * second thing to keep in step with that one (#313's reasoning), and this file is for reading
   * rather than for diffing.
   *
   * <p><b>An act the graph holds no label for is written as its qid</b>, not skipped and not
   * written as a phrase. {@code NamesFile}'s reasoning exactly: the qid identifies the row and is
   * wrong in an obvious way rather than a quiet one.
   */
  final class IsolatedFile {

    /** Said on the first line of every file this writes, followed by the count on the same line. */
    static final String PERSONAL_DATA_HEADER =
        "# segue known-list acts the graph cannot place — personal data under ADR 33 and issue #37."
            + " Keep this file outside the working tree and out of version control: this repository"
            + " is public.";

    private IsolatedFile() {}

    /**
     * Write the header and one act per line: qid, label, kind, and how many unexpanded people and
     * groups are beside it, tab-separated.
     *
     * @return how many of them the graph holds no label for, and so were written as their qid
     */
    static int write(SecondHop rule, Map<String, NodeRecord> nodes, Writer out) throws IOException {
      return 0;
    }
  }
  ```

  Then write `IsolatedFileTest`:

  ```java
  package com.robsartin.segue.census;

  import static org.assertj.core.api.Assertions.assertThat;

  import com.robsartin.segue.domain.EdgeRecord;
  import com.robsartin.segue.domain.Expanded;
  import com.robsartin.segue.domain.NodeKind;
  import com.robsartin.segue.domain.NodeRecord;
  import com.robsartin.segue.domain.SecondHop;
  import java.io.StringWriter;
  import java.util.LinkedHashMap;
  import java.util.List;
  import java.util.Map;
  import java.util.Set;
  import org.junit.jupiter.api.DisplayName;
  import org.junit.jupiter.api.Test;

  /** The four fields, the header, the fallback and the order. Every id and label is invented. */
  class IsolatedFileTest {

    /** Isolated, labelled, with one unexpanded PERSON beside it. */
    private static final String ALONE = "Q0901409";

    /** Isolated, and the graph holds a blank label for it. */
    private static final String NAMELESS = "Q0901410";

    /** The unexpanded PERSON beside {@link #ALONE}. */
    private static final String NEXT_TO = "Q0901411";

    private static final String LABEL = "An Act Unlike Anything Real";

    private static Map<String, NodeRecord> nodes() {
      Map<String, NodeRecord> nodes = new LinkedHashMap<>();
      nodes.put(ALONE, new NodeRecord(ALONE, NodeKind.GROUP, LABEL));
      nodes.put(NAMELESS, new NodeRecord(NAMELESS, NodeKind.PERSON, ""));
      nodes.put(NEXT_TO, new NodeRecord(NEXT_TO, NodeKind.PERSON, "A Neighbour"));
      return nodes;
    }

    private static SecondHop rule() {
      return SecondHop.of(
          nodes(),
          List.of(new EdgeRecord(ALONE, NEXT_TO, "MEMBER_OF", null, null, List.of())),
          List.of(ALONE, NAMELESS),
          new Expanded(Set.of()));
    }

    private static List<String> written() throws Exception {
      StringWriter out = new StringWriter();
      IsolatedFile.write(rule(), nodes(), out);
      return List.of(out.toString().split("\n", -1));
    }

    @Test
    @DisplayName("the first line names the file as personal data to keep outside the tree")
    void shouldOpenWithThePersonalDataHeaderWhenTheFileIsWritten() throws Exception {
      assertThat(written().get(0))
          .startsWith(IsolatedFile.PERSONAL_DATA_HEADER)
          .contains("2 act(s)");
    }

    @Test
    @DisplayName("each act is four tab-separated fields: qid, label, kind and the count beside it")
    void shouldWriteFourTabSeparatedFieldsWhenAnActIsIsolated() {
      assertThat(written().get(1))
          .isEqualTo(ALONE + "\t" + LABEL + "\t" + NodeKind.GROUP.name() + "\t1");
    }

    @Test
    @DisplayName("an act the graph holds no label for is written as its qid, never dropped")
    void shouldWriteTheQidAsTheLabelWhenTheGraphHoldsNoneForTheAct() throws Exception {
      assertThat(written().get(2))
          .as("NamesFile's reasoning: wrong in an obvious way rather than a quiet one")
          .isEqualTo(NAMELESS + "\t" + NAMELESS + "\t" + NodeKind.PERSON.name() + "\t0");
      assertThat(IsolatedFile.write(rule(), nodes(), new StringWriter()))
          .as("and the caller is told how many rows that was")
          .isEqualTo(1);
    }

    @Test
    @DisplayName("the acts come out in the population's own order, with no sort of their own")
    void shouldKeepThePopulationsOrderWhenTheActsAreWritten() throws Exception {
      assertThat(written().subList(1, 3))
          .as("ALONE before NAMELESS because the population names them that way")
          .allSatisfy(line -> assertThat(line).isNotBlank());
      assertThat(written().get(1)).startsWith(ALONE);
      assertThat(written().get(2)).startsWith(NAMELESS);
    }
  }
  ```

  Run `./gradlew test --tests '*IsolatedFileTest'` and **observe real assertion failures**
  (`IndexOutOfBounds` is not one — if the split yields fewer lines than the test indexes, make the
  stub write the header alone first so the failures are assertions). Quote two of them.

- [ ] **Step 3 — write the body.**

  ```java
    static int write(SecondHop rule, Map<String, NodeRecord> nodes, Writer out) throws IOException {
      Objects.requireNonNull(rule, "rule");
      Objects.requireNonNull(nodes, "nodes");
      Objects.requireNonNull(out, "out");
      List<String> isolated = rule.isolated();
      out.write(PERSONAL_DATA_HEADER);
      out.write(" " + isolated.size() + " act(s), one per line: qid, label, kind, and how many"
          + " unexpanded people and groups are beside it.\n");
      int unnamed = 0;
      for (String qid : isolated) {
        NodeRecord node = nodes.get(qid);
        String label = node.label();
        if (label.isBlank()) {
          label = qid;
          unnamed++;
        }
        out.write(qid);
        out.write('\t');
        out.write(label);
        out.write('\t');
        out.write(node.kind().name());
        out.write('\t');
        out.write(String.valueOf(rule.toExpandBeside(qid).size()));
        out.write('\n');
      }
      return unnamed;
    }
  ```

  Re-run: green.

- [ ] **Step 4 — the parser: `--isolated`, refused without `--known`.** Write the failing tests in
      `CensusCliTest` first:

  ```java
    @Test
    @DisplayName("--isolated without --known is refused: there is no population to name")
    void shouldRefuseTheIsolatedFileWhenNoKnownListWasNamed() throws Exception {
      Path named = Files.createFile(home.resolve("named.db"));

      assertThatThrownBy(
              () ->
                  CensusCli.parse(
                      new String[] {"--db", named.toString(), "--isolated", "out.txt"},
                      null,
                      home.toString()))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("--isolated needs --known")
          .hasMessageContaining("--db <segue.db>");
    }

    @Test
    @DisplayName("--isolated is carried as given beside --known, and no file is opened to parse it")
    void shouldCarryTheIsolatedFileWhenBothFlagsAreGiven() throws Exception {
      Path named = Files.createFile(home.resolve("named.db"));
      Path known = home.resolve("never-written.csv");
      Path out = home.resolve("never-written.txt");

      CensusCli.Options options =
          CensusCli.parse(
              new String[] {
                "--db", named.toString(), "--known", known.toString(), "--isolated", out.toString()
              },
              null,
              home.toString());

      assertThat(options.isolated()).contains(out);
      assertThat(out).as("parse opens nothing, so the guide's examples can name a file").doesNotExist();
    }

    @Test
    @DisplayName("--known alone still parses, so the refusal is about the pair and not the flag")
    void shouldStillCarryTheKnownListWhenOnlyThatFlagIsGiven() throws Exception {
      Path named = Files.createFile(home.resolve("named.db"));

      CensusCli.Options options =
          CensusCli.parse(
              new String[] {"--db", named.toString(), "--known", "known.csv"},
              null,
              home.toString());

      assertThat(options.known()).isPresent();
      assertThat(options.isolated()).isEmpty();
    }
  ```

  The third is the **positive control** for the second: without it, a parser that refused
  `--known` outright would pass the refusal test. Add `Optional<Path> isolated` to `Options` with a
  `@param` saying it is refused without `--known` on `RatingsCli`'s `--promotions-off`/`--names`
  precedent, parse `--isolated`, and refuse:

  ```java
      if (isolated != null && known == null) {
        // One output in two flags, so half of it is a usage error rather than a silent no-op —
        // RatingsCli's --promotions-off/--names rule. The file names the isolated members of the
        // population WITH promotions, and there is no population at all without a file.
        throw usage("--isolated needs --known <file of QIDs> to name a population");
      }
  ```

  and extend `USAGE` to
  `"usage: --db <segue.db> [--known <file of QIDs> [--isolated <file>]]"`. Run
  `./gradlew test --tests '*CensusCliTest'`, observe the reds first, then green.

- [ ] **Step 5 — `CensusRun` writes it, after the report.** Change the signature to
      `run(Consumer<String> lines, Optional<Path> known, Optional<Path> isolated)` and write the
      file last:

  ```java
    public Census run(Consumer<String> lines, Optional<Path> known, Optional<Path> isolated) {
      Objects.requireNonNull(lines, "lines");
      Objects.requireNonNull(known, "known");
      Objects.requireNonNull(isolated, "isolated");
      Census.Reading reading = Census.reading(log, ratings, known.map(KnownListInput::read));
      CensusReport.lines(reading.census()).forEach(lines);
      // After the report, so a run that could not produce one writes nothing — and an existing
      // file is overwritten, as NamesFile overwrites. CensusCli.parse has already refused
      // --isolated without --known, so the rule is present whenever the path is.
      isolated.ifPresent(file -> write(file, reading, lines));
      return reading.census();
    }

    /**
     * <b>The note is counts and never the path.</b> ADR 63's whole point is that nothing this tool
     * emits locates the owner's file; {@code RatingsRun} says "wrote &lt;path&gt;" because its own
     * block already warns about a file of personal data, and this block does not.
     */
    private void write(Path file, Census.Reading reading, Consumer<String> lines) {
      SecondHop rule =
          reading
              .isolation()
              .orElseThrow(() -> new IllegalStateException("--isolated without --known"));
      int unnamed;
      try (Writer out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
        unnamed = IsolatedFile.write(rule, reading.projection().nodes(), out);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
      lines.accept(
          "wrote " + rule.isolated().size() + " isolated act(s), " + unnamed
              + " of them named only by qid");
    }
  ```

  and in `CensusCli.run`, `new CensusRun(assertions, ratings).run(log::info, options.known(),
  options.isolated())`. Update `CensusRunTest`'s existing calls to the new arity, passing
  `Optional.empty()`, and add one case there driving a real `@TempDir` database with both flags and
  asserting the file exists, its first line is the header, and a second run over the same path
  leaves exactly one header line in it (the overwrite). Run `./gradlew test --tests '*CensusRunTest'`
  — red first on the new case, then green.

- [ ] **Step 6 — the byte-identical case, in `CensusIsSafeToPasteTest`.** Add:

  ```java
    @Test
    @DisplayName("the report on the terminal is byte-identical with and without --isolated")
    void shouldPrintTheSameReportWhenTheIsolatedFileIsAlsoWritten() throws Exception {
      // INFO rather than this class's TRACE: at TRACE the capture also holds sqlite-jdbc's own
      // statement lines, and the two runs open two connections — so a comparison at TRACE would be
      // about the driver rather than about the report. @AfterEach restores the level either way.
      rootLogger.setLevel(Level.INFO);
      Path db = home.resolve("identical.db");
      try (SqliteAssertionLog log = new SqliteAssertionLog(db)) {
        log.append(InventedCensus.node("Q0900901", NodeKind.WORK, LABEL));
      }
      Path known = Files.writeString(home.resolve("known.csv"), "Q0900901\n");
      Path out = home.resolve("isolated.txt");

      captured.list.clear();
      CensusCli.main(new String[] {"--db", db.toString(), "--known", known.toString()});
      List<String> withoutFlag = lines();

      captured.list.clear();
      CensusCli.main(
          new String[] {
            "--db", db.toString(), "--known", known.toString(), "--isolated", out.toString()
          });
      List<String> withFlag = lines();

      assertThat(withoutFlag)
          .as("the report was actually printed — without this the comparison below is vacuous")
          .contains(CensusReport.HEADER)
          .anyMatch(line -> line.startsWith("known list — "));
      assertThat(withFlag.subList(0, withoutFlag.size()))
          .as("every line of the report is the line the run without the flag printed")
          .isEqualTo(withoutFlag);
      assertThat(withFlag)
          .as("and the flag adds exactly one line, which is a count and names no path")
          .hasSize(withoutFlag.size() + 1);
      assertThat(withFlag.get(withFlag.size() - 1))
          .startsWith("wrote ")
          .doesNotContain(out.toString());
      assertThat(out).as("and the file really was written").exists();
    }

    private List<String> lines() {
      return List.copyOf(captured.list).stream().map(ILoggingEvent::getFormattedMessage).toList();
    }
  ```

  Run `./gradlew test --tests '*CensusIsSafeToPasteTest'` — **observe the red first** (the method
  does not exist / the flag is unparsed), then green.

- [ ] **Step 7 — the positive control for the byte-identical case.** Temporarily move the
      `lines.accept("wrote …")` call in `CensusRun.write` **above** the
      `CensusReport.lines(...).forEach(lines)` call. Run the test and **observe it fail** on the
      sublist comparison, proving it reads the order and not merely the size. Quote it. Restore and
      re-run green.

- [ ] **Step 8 — format, gate, commit.** `./gradlew spotlessApply`, full gate blocking, re-read
      Javadoc spans, `git status`, stage by explicit path, commit:

  > graphCensus --isolated: write the acts the graph cannot place to a file (#319)

---

## Task 5 — `SecondHopNeighbours`, the third population shape, and the three-way refusal

**Files:** `src/main/java/com/robsartin/segue/expand/SecondHopNeighbours.java` (new),
`src/main/java/com/robsartin/segue/expand/Population.java`,
`src/main/java/com/robsartin/segue/expand/ExpansionReport.java`,
`src/main/java/com/robsartin/segue/expand/ExpandCli.java`,
`src/test/java/com/robsartin/segue/expand/ExpansionReportTest.java`,
`src/test/java/com/robsartin/segue/expand/ExpandCliTest.java`.

- [ ] **Step 1 — the record.**

  ```java
  package com.robsartin.segue.expand;

  import java.util.Objects;

  /**
   * The second-hop population a run covered, and how many acts it was read beside (#319).
   *
   * @param file the file's <b>basename</b>, never its path — {@code support.KnownListInput} is the
   *     one home of that rule, and the block is meant to be pasted
   * @param isolated how many entities of that file, composed with the owner's promotions and folded
   *     onto their canonical side, the graph holds a node for and cannot place: no other member of
   *     that population is within the recommender's hop limit of them
   */
  public record SecondHopNeighbours(String file, int isolated) implements Population {

    public SecondHopNeighbours {
      Objects.requireNonNull(file, "file");
      if (isolated < 0) {
        throw new IllegalArgumentException("isolated cannot be negative, got " + isolated);
      }
    }
  }
  ```

  Add it to the sealed interface: `permits RatedSince, KnownNeverExpanded, SecondHopNeighbours`, and
  extend `Population`'s Javadoc paragraph about which shapes can carry text — this one carries a
  basename, like `KnownNeverExpanded`, and an `int`.

- [ ] **Step 2 — the red: `ExpansionReport` no longer compiles, and that is a compile error, not a
      red.** `clause`'s switch is exhaustive with no `default`, which is the whole point of the
      sealed interface. Add the arm returning a **deliberately wrong** sentence — the empty string —
      so the build compiles, then write the failing pin in `ExpansionReportTest`:

  ```java
    @Test
    @DisplayName("a second-hop run says which file and how many acts it was read beside")
    void shouldNameTheFileAndTheIsolatedCountWhenTheRunCoveredTheSecondHop() {
      List<String> lines =
          ExpansionReport.dryRunLines(
              new Preflight(4, 4, 0), Optional.of(new SecondHopNeighbours("known.csv", 3)));

      assertThat(lines.get(0)).isEqualTo(ExpansionReport.DRY_RUN_HEADER);
      assertThat(lines.get(1))
          .isEqualTo(
              "# only the unexpanded people and groups beside the acts your own list names that"
                  + " the graph cannot place, from known.csv: 3 act(s) — an act is one no other"
                  + " entity on that list, with your promotions, is within the recommender's hop"
                  + " limit of.");
    }
  ```

  Run `./gradlew test --tests '*ExpansionReportTest'` and **observe the assertion failure** showing
  the empty clause. Quote it. Then write the arm:

  ```java
    /**
     * Said under the header when a second-hop file was given, and not at all when none was —
     * {@link #sinceLine}'s argument, which carries the reasoning for a clause rather than a row.
     *
     * <p>The hop clause is here rather than in the guide alone because the count beside it is
     * misread without it: the acts are counted over the population <i>with promotions</i>, which is
     * the recommender's own notion of known, so an act one hop from a promotion is not one of them.
     */
    private static String secondHopLine(SecondHopNeighbours beside) {
      return "# only the unexpanded people and groups beside the acts your own list names that the"
          + " graph cannot place, from "
          + beside.file()
          + ": "
          + beside.isolated()
          + " act(s) — an act is one no other entity on that list, with your promotions, is within"
          + " the recommender's hop limit of.";
    }
  ```

  and `case SecondHopNeighbours beside -> secondHopLine(beside);` in `clause`. Re-run: green.

- [ ] **Step 3 — the positive control that the two existing blocks did not move.**
      `ExpansionReportTest` already pins the no-population block and the `RatedSince` and
      `KnownNeverExpanded` blocks. Run `./gradlew test --tests '*ExpansionReportTest'` and confirm
      **every one of those pins is still green and unedited** — `git diff` on that file must show
      only the new test. Say so in the report: an edit to an existing golden here would mean a block
      already pasted into an issue stopped being comparable.

- [ ] **Step 4 — the parser, red first.** Add to `ExpandCliTest`:

  ```java
    @Test
    @DisplayName("--second-hop is carried as the path when one is given, and the file is not read")
    void shouldCarryTheSecondHopFileWhenTheFlagIsGiven() {
      assertThat(
              ExpandCli.parse(
                      new String[] {"--db", "db.sqlite", "--second-hop", "known.csv"},
                      null,
                      home.toString())
                  .secondHop())
          .contains(Path.of("known.csv"));
    }

    @Test
    @DisplayName("no second-hop file is carried when the flag is absent, which is every run so far")
    void shouldCarryNoSecondHopFileWhenTheFlagIsAbsent() {
      assertThat(
              ExpandCli.parse(new String[] {"--db", "db.sqlite"}, null, home.toString()).secondHop())
          .isEmpty();
    }

    @Test
    @DisplayName("--second-hop and --known together are refused: they name different populations")
    void shouldRefuseBothFlagsWhenASecondHopFileAndAKnownFileAreGiven() {
      assertThatThrownBy(
              () ->
                  ExpandCli.parse(
                      new String[] {
                        "--db", "db.sqlite", "--known", "known.csv", "--second-hop", "known.csv"
                      },
                      null,
                      home.toString()))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("--second-hop and --known name different populations")
          .hasMessageContaining("--db <segue.db>");
    }

    @Test
    @DisplayName("--second-hop and --rated-since together are refused for the same reason")
    void shouldRefuseBothFlagsWhenASecondHopFileAndAnInstantAreGiven() {
      assertThatThrownBy(
              () ->
                  ExpandCli.parse(
                      new String[] {
                        "--db", "db.sqlite",
                        "--second-hop", "known.csv",
                        "--rated-since", "2026-09-13T00:00:00Z"
                      },
                      null,
                      home.toString()))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("--second-hop and --rated-since name different populations");
    }
  ```

  The existing `shouldRefuseBothFlagsWhenAKnownFileAndAnInstantAreGiven` is the **positive control**
  that the existing pair's sentence did not move: its assertion on
  `"--known and --rated-since name different populations"` must stay green **unedited**. Run
  `./gradlew test --tests '*ExpandCliTest'` and observe the reds. Quote one.

- [ ] **Step 5 — the parser.** Add `Optional<Path> secondHop` to `Options` with a `@param`, parse
      `--second-hop`, and add the two refusals **after** the existing one, each in that refusal's own
      words so that a reader meets one sentence shape and not three:

  ```java
      if (known != null && ratedSince != null) {
        // unchanged (#313)
        throw usage("--known and --rated-since name different populations — give one or neither");
      }
      if (secondHop != null && known != null) {
        throw usage("--second-hop and --known name different populations — give one or neither");
      }
      if (secondHop != null && ratedSince != null) {
        throw usage("--second-hop and --rated-since name different populations — give one or neither");
      }
  ```

  A run naming all three meets the first refusal, which is the one already on record; pin that with
  one more case asserting the all-three run names `--known and --rated-since`. Extend `USAGE` with
  `[--second-hop <file of QIDs>]` and add a Javadoc paragraph to `ExpandCli` describing the third
  population: it composes the promotions, reads the rule, and visits the ring — and, unlike a
  `--known` run, **it does read ratings**, because the population with promotions is the
  recommender's own notion of known. Re-run: green.

- [ ] **Step 6 — format, gate, commit.** `./gradlew spotlessApply`, full gate blocking, re-read
      Javadoc spans, `git status`, stage by explicit path, commit:

  > expandPromotions --second-hop: the third population shape and its refusals (#319)

---

## Task 6 — The expander's third population: composed once, at the start

**Files:** `src/main/java/com/robsartin/segue/expand/ExpandCli.java`,
`src/test/java/com/robsartin/segue/expand/ExpandCliTest.java`.

The ids this task introduces: `ISOLATED_ACT` `Q0901412` and the three already declared in
`ExpandCliTest` for the `--known` cases. **Every run in this task is a dry run**, so nothing reaches
a network.

- [ ] **Step 1 — the failing end-to-end test.** Add to `ExpandCliTest`, beside the `--known` cases
      it already has:

  ```java
    /** On the file, in the graph, and nothing else on the file is within the hop limit of it. */
    private static final String ISOLATED_ACT = "Q0901412";

    /**
     * Three invented entities and one edge: an act the file names, an unexpanded PERSON beside it,
     * and a second act on the file three hops away so that neither places the other.
     */
    private Path secondHopGraph(String name) {
      Path db = home.resolve(name);
      Provenance plain = new Provenance("invented", "invented:7", WHEN, 1.0);
      try (SqliteAssertionLog log = new SqliteAssertionLog(db)) {
        log.append(new NodeAssertion(ISOLATED_ACT, NodeKind.GROUP, "an invented act", plain));
        log.append(new NodeAssertion(NEVER_EXPANDED, NodeKind.PERSON, "a bandmate", plain));
        log.append(
            new AssertionRecord(
                ISOLATED_ACT, NEVER_EXPANDED, "MEMBER_OF", null, null, plain));
      }
      return db;
    }

    @Test
    @DisplayName("a second-hop run considers the unexpanded people beside the isolated acts")
    void shouldConsiderTheRingWhenTheFileNamesAnActTheGraphCannotPlace() throws Exception {
      Path db = secondHopGraph("second-hop.db");
      Path file = Files.writeString(home.resolve("second-hop.csv"), ISOLATED_ACT + "\n");
      captured.list.clear();

      ExpandCli.main(
          new String[] {"--db", db.toString(), "--dry-run", "--second-hop", file.toString()});

      assertThat(countOn(lines(), "considered"))
          .as("one isolated act, one unexpanded PERSON beside it")
          .isEqualTo(1);
      assertThat(lines())
          .as("and the block says which population it covered, naming the basename and the count")
          .anyMatch(
              line ->
                  line.startsWith("# only the unexpanded people and groups beside")
                      && line.contains("second-hop.csv")
                      && line.contains("1 act(s)"));
      assertThat(lines())
          .as("the ratings read count is logged, and no qid and no score with it")
          .anyMatch(line -> line.matches("^read \\d+ rating\\(s\\)$"));
    }

    @Test
    @DisplayName("an act the graph can place contributes nothing to a second-hop run")
    void shouldConsiderNothingWhenEveryActOnTheFileHasAKnownNeighbour() throws Exception {
      // The planted control for the test above: the same graph and the same rule, with BOTH ids on
      // the file — so each is one hop from the other, neither is isolated, and the ring beside them
      // is not the question. If this reported 1 as well, the count above would not be the rule
      // firing.
      Path db = secondHopGraph("placed.db");
      Path file =
          Files.writeString(
              home.resolve("placed.csv"), ISOLATED_ACT + "\n" + NEVER_EXPANDED + "\n");
      captured.list.clear();

      ExpandCli.main(
          new String[] {"--db", db.toString(), "--dry-run", "--second-hop", file.toString()});

      assertThat(countOn(lines(), "considered")).isZero();
      assertThat(lines()).anyMatch(line -> line.contains("0 act(s)"));
    }
  ```

  Run `./gradlew test --tests '*ExpandCliTest'` and **observe the reds** — the flag parses but
  composes nothing, so `considered` reads whatever the promotions branch produced. Quote them.

- [ ] **Step 2 — the third branch in `ExpandCli.run`.** Insert between the `--known` branch and the
      promotions `else`:

  ```java
        } else if (options.secondHop().isPresent()) {
          // The population is composed ONCE, here, and nothing in the run re-reads it: a run that
          // expands its first entity must not shrink its own list mid-way. The NEXT run is smaller
          // by the rule alone — every entity this run expanded is covered by Expanded, and an
          // isolated act the new edges connected to something known is no longer isolated. No
          // state, no ledger, no --limit: the dry run's `considered` is the bound (#319).
          KnownListInput named = KnownListInput.read(options.secondHop().get());
          // Resolved before the threshold is applied, exactly as the promotions branch does it: a
          // merge leaves two affinity rows naming one thing. A count, never a qid and never a
          // score (ADR 33).
          Map<String, Integer> ratings = merges.resolve(affinity.readRatings());
          log.info("read {} rating(s)", ratings.size());
          // The population with promotions, because that is the recommender's own notion of known
          // (KnownList.promoted is what recommend seeds from) and an act one hop from a promotion
          // is not one the graph cannot place.
          List<String> promoted =
              KnownList.promoted(merges.canonical(named.qids()), ratings);
          // The log read once, and both answers taken from it — the fold is the boot's own
          // (theReplayingToolsTakeTheBootsFold), never rebuilt through Fold.of.
          List<LoggedAssertion> logged = assertions.readAll();
          Expanded expanded = Expanded.in(logged).onTheCanonicalSide(merges);
          LogProjection projection = LogProjection.of(logged, replay.fold());
          SecondHop rule =
              SecondHop.of(projection.nodes(), projection.edges(), promoted, expanded);
          population = rule.toExpand();
          covered = Optional.of(new SecondHopNeighbours(named.name(), rule.isolated().size()));
          log.info(
              "{} entity(s) to visit beside {} act(s) the graph cannot place",
              population.size(),
              rule.isolated().size());
        } else {
  ```

  Add the imports: `com.robsartin.segue.domain.LoggedAssertion`,
  `com.robsartin.segue.domain.SecondHop`, `com.robsartin.segue.ingest.LogProjection`. Re-run:
  green.

  `considered` is `toExpand().size()` because `ExpandRun.dryRun` and `run` are handed that list and
  `Preflight.considered` is its size — `ExpandRun` still never filters. An entity in it is by
  construction a node the graph holds, so this population cannot produce `refused, unknown entity`;
  the preflight's other refusals apply as they do to every population.

- [ ] **Step 3 — the paste guard reaches the new clause.** Add one case to
      `ExpansionIsSafeToPasteTest` in the shape of its existing `--known` case: a dry run over a
      second-hop file **whose basename is itself qid-shaped**, asserting the clause is printed and
      that the guard fires — i.e. that the qid-shaped basename is the only qid-shaped token on any
      line, exactly as the existing case establishes for `--known`. Run
      `./gradlew test --tests '*ExpansionIsSafeToPasteTest'`, red first, then green. **Follow the
      existing case's structure exactly**; if it asserts the guard fires rather than that the line is
      clean, do the same here and say so.

- [ ] **Step 4 — the `DeveloperGuideExpandPromotionsExamplesTest` check runs on nothing yet**, since
      the guide chapter lands in Task 7. Confirm it is green now and note that Task 7 is what makes
      it non-vacuous for `--second-hop`.

- [ ] **Step 5 — format, gate, commit.** `./gradlew spotlessApply`, full gate blocking, re-read
      Javadoc spans, `git status`, stage by explicit path, commit:

  > expandPromotions --second-hop: visit the ring beside the acts the graph cannot place (#319)

---

## Task 7 — The records: ADR 63, ADR 66, and the runbook chapter

**Files:** `docs/adr/0063-a-read-only-census-of-the-graph.md`,
`docs/adr/0066-expand-every-promotion-from-a-dev-tool.md`, `docs/developer-guide.md`.

**Rules for this task, restated because they bite hardest here.** An ADR is immutable: nothing above
is edited, the status stays `Accepted`, and each amendment is appended at the end opening with the
bold form the file already uses — `**Amendment (2026-09-13, issue #319): …**`. **No commit hash, no
`.superpowers/` path, no qid, and no figure from the owner's graph anywhere under `docs/adr`**: the
census reading is cited as "the census on #317 (2026-09-13)". **Never put a bare run of seven or
more digits inside backticks in ADR prose** — `AdrCitationsTest` reads it as a commit citation.
Every markdown link is `[text](target)`, whole, on one line.

- [ ] **Step 1 — confirm the test inputs are already declared, before claiming otherwise.**

  ```
  grep -n 'inputs.dir("docs")' build.gradle.kts
  grep -n 'inputs' build.gradle.kts | grep -i readme
  ```

  Both must be present. `docs` and `README.md` are declared inputs of `tasks.test` already, so **no
  new declaration is needed** and none is added; record that in the report rather than leaving it
  implied. It also means a document edit re-runs the suite, so run the per-task loops in this task
  **without** `--rerun-tasks`.

- [ ] **Step 2 — ADR 63's amendment.** Append at the end of the file, nothing above edited:

  > **Amendment (2026-09-13, issue #319): the `known list` section breaks its `no known neighbour`
  > row into three, takes an optional `--isolated <file>`, and the walk and the fold it runs on both
  > leave `census`.**

  It records, in prose, in this order:
  - **The three rows.** Nested one level under `no known neighbour within N hops`: how many of those
    acts have at least one person or group beside them that no expansion has covered, how many have
    none, and how many distinct such entities there are across all of them. The first two partition
    the row above them; the third is the spend a `--second-hop` run would make, before any run. All
    three are counts, so *Every value is an integer* is untouched and
    `CensusIsSafeToPasteTest`'s two existing cases cover them by running over the new rows. **The
    labels name no kind: `domain.SecondHop.WORTH_EXPANDING` is the one statement of which kinds
    those are, and this amendment cites it rather than restating it.**
  - **Why the rows are worth printing.** The census on #317 (2026-09-13) showed that the isolated
    population and the never-expanded population barely overlap — nearly every isolated act has been
    expanded — so the census could not tell an unfetched ring from one that really touches nothing
    known. **No figure from that reading is restated here.**
  - **The file.** `--isolated <out>` writes the isolated members of the population **with
    promotions**, one per line, in the population's own order, four tab-separated fields — qid,
    label, kind, count beside it — with a `#` first line naming it as personal data under ADR 33 and
    telling the owner to keep it outside the working tree. It is written after the report, so a
    report that could not be produced writes nothing; an existing file is overwritten.
    `--isolated` without `--known` is refused with the usage message, on `--names` needing
    `--promotions-off`.
  - **Why the file is not paste-safe, and why that is not a contradiction.** ADR 63's guarantee is
    about the census **block**, and it is unchanged — the flag adds one line to the terminal, a
    count, naming no path. The file holds entity ids and labels off the owner's list, which is what
    the block exists never to print. **`CensusIsSafeToPasteTest`'s discipline does not apply to it
    and must not be added by analogy**, the note `ratings.NamesFile` already carries. What that test
    gains is one case: the report on the terminal is byte-identical with and without the flag.
  - **The walk left `census`.** It is `domain.SecondHop`'s now, with the rule it serves, so the
    census and `expandPromotions --second-hop` ask one question — the shape `Expanded` set for
    #311/#313 and `KindMapper.rederive` for ADR 42.
  - **The fold left `export`.** `LogProjection` is in `ingest`. This **overtakes the section *It
    counts the exporter's fold, so `census` depends on `export`***: the argument in it — there are
    two ways to have a fold, read the one there is or write a third, and a census disagreeing with
    the picture is the defect `BothFoldsAgreeTest` exists to catch — is unchanged and is what the
    move serves. What is no longer true is the dependency: the expander may not open a dev-tool
    package at all, so the fold could not stay in one. `census → export` was the second dependency
    between two dev tools and there are two left, `rate → recommend` and `evaluate → recommend`.
    `theCensusOnlyReads` now permits no sibling, and `theCensusOpensNothingElse` names
    `LogProjection` as the one `ingest` class the census may open — the clause that rule exists for,
    no replay, is intact: `GraphProjector`, `Replay` and `IngestService` stay banned.
  - **The alternatives rejected that belong to the census**, each with the reason it lost: printing
    the isolated acts on the terminal (the block is pasted into public issues and holds no id and no
    label; a file the owner asks for by flag is the ratings tool's answer to the same need, #285);
    rows only, with no file (the count says how much, only the names say which); sorting the file by
    id (a second ordering rule, and a lexical sort of qids is not numeric anyway); moving
    `LogProjection` to `domain` (it reads the port and the kind mapper, and `domain` reads neither)
    or to `support` (which depends on nothing, the reason this ADR already gives for not moving it
    there).
  - **The closing paragraph** in the file's own established form: what is unit-testable landed with
    its own tests, and the verification of the *document* is the full gate — `AdrIndexTest`,
    `AdrCitationsTest`, `DocumentationLinksTest` for the relative links, and `javadoc -Werror`
    inside `./gradlew check`.

- [ ] **Step 3 — ADR 66's amendment.** Append at the end, nothing above edited:

  > **Amendment (2026-09-13, issue #319): the tool takes `--second-hop`, and expands a third
  > population — the unexpanded people and groups beside the known-list acts the graph cannot
  > place.**

  It records:
  - **The population**, and that it is `domain.SecondHop`'s answer rather than a second copy of one:
    the file composed with the owner's promotions through `KnownList.promoted`, folded onto its
    canonical side, the members of it the graph holds a node for and cannot place, and the nodes one
    folded edge from those whose kind is in `SecondHop.WORTH_EXPANDING` and that `Expanded` does not
    cover — distinct, in first-seen order. `considered` is that list's size.
  - **Why the population with promotions.** It is the recommender's own notion of known, and an act
    one hop from a promotion is not one the graph cannot place. The consequence is that **this run
    reads ratings where a `--known` run does not** — a count logged and nothing else about them
    (ADR 33), exactly as the promotions runs do.
  - **The three-way refusal.** `--second-hop`, `--known` and `--rated-since` name three populations,
    and any two together are refused with the usage message in the existing refusal's words. The
    pair already on record keeps its sentence unchanged, so a block or a script written against it
    still reads.
  - **Fixed at the start, and why re-runs need no state.** The population is composed once, before
    the first expansion, so a run that expands its first entity does not shrink its own list mid-way.
    The next run is smaller by the rule alone: an entity this run expanded is covered by `Expanded`,
    and an isolated act the new edges connected to something known is no longer isolated. **No
    `--limit`**: it would need an order to be meaningful and an order is a second rule; the dry run's
    `considered` is the bound and a smaller run is a later run, after the census moves. This is the
    same refusal the 2026-09-12 amendment for #315 made about a log-side marker, reached from the
    other side, and neither is overtaken.
  - **What cannot happen on this population**: an entity in it is by construction a node the graph
    holds, so it cannot produce `refused, unknown entity`. The preflight's other refusals apply as
    they do to every population, and `ExpandRun` still never filters.
  - **The alternatives rejected that belong to the expander**: expanding every unexpanded neighbour
    of every known act (the ring of the whole list, most of it beside acts the graph already places
    — spend goes where the diagnosis points); putting the rule in `census` and reading it from here
    (`expand` may not open `census`, which is why `Expanded` and `KnownListInput` left it);
    letting `expand` open `export` (a dev-tool package, and the fence is the point); rebuilding
    nodes and edges from the graph store (`GraphStore` lists no nodes, and a second projection is
    the fold done twice — the thing #246 removed); deciding isolation on the file alone.
  - **The closing paragraph** in this file's own form.

- [ ] **Step 4 — the runbook chapter.** In `docs/developer-guide.md`, insert a new `###` chapter
      **immediately after** *Only what your own list says nothing has expanded: `--known`* and
      **before** *What to file from what you saw*, titled

  > ### The ring beside what your list cannot place: `--second-hop`

  In the same shape as the chapter above it, in this order:
  - one paragraph saying what the population is and why it is different from `--known`: `--known`
    covers acts the log says were never fetched; this covers acts that **have** been fetched, whose
    own ring is in the graph, and nothing in that ring is known and nothing one hop beyond it has
    been fetched. The census on #317 (2026-09-13) is cited as where the reading lives; **no figure
    from it is restated**.
  - **Step 0 applies unchanged**, and so does everything the chapter above says about a single
    writer.
  - the census first, with the file and the flag, as a fenced `bash` block:

    ```bash
    ./gradlew graphCensus --args="--db $HOME/.segue/segue.db --known $HOME/known.csv --isolated $HOME/isolated.txt"
    ```

  - how to read the three rows: `with someone to expand beside` plus `with no one` is the
    `no known neighbour within N hops` row itself; `distinct to expand` is what a run would visit,
    before the run; and an act under `with no one` is one nothing here can help — its ring is fully
    fetched, or its ring is works and places rather than people and groups.
  - **the file is personal data.** Write it outside the working tree, never attach it to an issue,
    and note that `*.txt` is gitignored beside `*.csv` and `*.db` but that the protection is where
    the file lives, not what git ignores (ADR 33, issue #37).
  - the dry run and the run:

    ```bash
    ./gradlew expandPromotions --args="--db $HOME/.segue/segue.db --dry-run --second-hop $HOME/known.csv"
    ```

    ```bash
    ./gradlew expandPromotions --args="--db $HOME/.segue/segue.db --second-hop $HOME/known.csv"
    ```

  - **what `considered` means here**, and that this run **does** read ratings where a `--known` run
    does not, with the reason (the population with promotions is the recommender's own notion of
    known).
  - the census again, with the same file, and what should have moved: `no known neighbour` down,
    `distinct to expand` down, nodes and edges up. **A smaller run is a later run** — the dry run is
    the only bound, and there is no `--limit`.
  - a closing paragraph pointing at the next reading on the standing rule, linking
    `docs/superpowers/specs/2026-09-04-second-reading-rule-design.md` **if and only if that file
    exists in the tree** — check first, and if it does not, name the rule in prose with no link
    rather than writing a link `DocumentationLinksTest` will refuse.

  **Write `$HOME`, never `~`** — a tilde does not expand inside `--args="…"` and
  `DeveloperGuideCensusExamplesTest.shouldWriteHomeRatherThanATildeWhenACensusExampleNamesADatabase`
  reds on it.

- [ ] **Step 5 — the census chapter follows.** In *Looking at the shape of your graph*:
  - the opening `bash` block gains a third example showing `--known` with `--isolated`;
  - `### What the two sub-sections mean` gains a short paragraph on the three nested rows, saying the
    first two partition the row above them and that the labels name no kind because
    `SecondHop.WORTH_EXPANDING` is the authority on which kinds those are;
  - a new short `###` section on the `--isolated` file: what it holds, that it is personal data, that
    it is written after the report and overwrites, and that the terminal block is byte-identical with
    and without it, **so the paste guarantee is unchanged**;
  - `### Why the output is safe to paste` gains one sentence: `--isolated` adds one line to the
    terminal, which is two counts and names no path, and the file itself is outside that guarantee
    on purpose.

- [ ] **Step 6 — the document guards, run and read.** Run, blocking:

  ```
  ./gradlew test --tests '*DocumentationLinksTest' --tests '*AdrCitationsTest' --tests '*AdrIndexTest' --tests '*DeveloperGuideEnumerationsTest' --tests '*DeveloperGuideCensusExamplesTest' --tests '*DeveloperGuideExpandPromotionsExamplesTest'
  ```

  Every one green, each with a non-zero test count. `DeveloperGuideCensusExamplesTest` now parses the
  new `--isolated` example through `CensusCli.parse`, and
  `DeveloperGuideExpandPromotionsExamplesTest` parses the two `--second-hop` examples through
  `ExpandCli.parse` — **that is what makes the chapter's commands checked rather than merely
  written.** Say so in the report.

- [ ] **Step 7 — the positive control for the guarded examples.** Temporarily change the
      `--second-hop` dry-run example in the new chapter to also carry `--known $HOME/known.csv`. Run
      `./gradlew test --tests '*DeveloperGuideExpandPromotionsExamplesTest'` and **observe it fail**,
      naming the line and the refusal `--second-hop and --known name different populations`. Quote
      it. Restore the example and re-run green. Do the same once for the census example, planting a
      `--isolated` with no `--known` and watching `DeveloperGuideCensusExamplesTest` fire.

- [ ] **Step 8 — the final gate, counted once.** Run `./gradlew spotlessApply`, then, blocking:

  ```
  SEGUE_REQUIRE_BROWSER=true SEGUE_REQUIRE_GRAPHVIZ=true ./gradlew check --rerun-tasks
  ```

  Then count the tests **once**, from the XML rather than from the console:

  ```
  python3 - <<'PY'
  import glob, xml.etree.ElementTree as ET
  tests = failures = errors = skipped = 0
  for f in glob.glob('build/test-results/test/*.xml'):
      r = ET.parse(f).getroot()
      tests += int(r.get('tests', 0)); failures += int(r.get('failures', 0))
      errors += int(r.get('errors', 0)); skipped += int(r.get('skipped', 0))
  print(f'tests={tests} failures={failures} errors={errors} skipped={skipped}')
  PY
  ```

  Report that one line. `failures` and `errors` must be 0.

- [ ] **Step 9 — commit.** `git status`, stage by explicit path, commit:

  > Record the second hop: ADR 63 and ADR 66 amendments and the runbook chapter (#319)

---

## Done when

- `domain.SecondHop` is the only statement of "isolated" and "worth expanding next", and `census`
  and `expand` both read it; `census.Neighbours` and its test are gone.
- `ingest.LogProjection` is the one fold `export`, `census` and `expand` read;
  `theCensusOpensNothingElse` names it as the one `ingest` class the census may open;
  `theCensusOnlyReads` permits no sibling dev tool; `theExportFoldsOnce` has no exempt class. Each of
  those three has a planted positive control in a task report.
- `graphCensus --known` prints three rows under each population's `no known neighbour` row, and
  `--isolated <file>` — refused without `--known` — writes the isolated acts of the population with
  promotions, after the report, overwriting, with a `#` personal-data header and four tab-separated
  fields. The terminal report is byte-identical with and without the flag.
- `expandPromotions --second-hop <file>` visits `SecondHop.toExpand()` for the population with
  promotions and nothing else; it is refused beside `--known` and beside `--rated-since` in the
  existing refusal's words; it prints the `#` clause on the dry run and the run; it logs the count of
  ratings it read and nothing else about them.
- ADR 63 and ADR 66 each carry a dated 2026-09-13 amendment for #319, appended, with nothing above
  edited, no commit hash, no qid, no figure from the owner's graph.
- The developer guide carries the new chapter after the `--known` one, and its commands are parsed
  by the two guide-example tests.
- `SEGUE_REQUIRE_BROWSER=true SEGUE_REQUIRE_GRAPHVIZ=true ./gradlew check --rerun-tasks` is green,
  and the test count is reported once, from `build/test-results/test/*.xml`.
