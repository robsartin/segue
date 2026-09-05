# Fold the log once per tool — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to
> implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** every dev tool that reads the assertion log derives its fold **once per run**, in one
place, and hands it to every reader — with no fold rule changed, no answer changed and no output
changed, pinned per tool by an ArchUnit fence that is proven able to fail.

**Architecture:** three mechanisms, chosen per tool by what that tool may know. `export` and
`census` build one `Fold` and thread it. `recommend`, `rate` and `evaluate` take the boot's `Fold`
back from a new `GraphProjector.replay`, so the second read of the log goes with it. `retract`
threads the emptied set it already holds into the caller-trusting overloads, and keeps its two folds
because it asks two questions about two lists. `own` and `ratings` are not touched: each already
folds once. Mikado throughout — parallel field where a signature moves, gate green at every commit.

**Tech Stack:** Java (toolchain 25, `release 21`), JUnit 5, AssertJ, ArchUnit, JaCoCo.

**Spec:** `docs/superpowers/specs/2026-09-04-fold-once-per-tool-design.md` — it holds the per-tool
fixed-point counts derived from the code, the three corrections to the issue's premise, the reason
`retract` may not build a `Fold`, the rejected alternatives and the residuals. Cite it; do not
restate its reasoning.

## Global Constraints

- **No fold rule and no fixed point changes.** `emptiedCanonicalIds`, `emptiedGiven`,
  `referencedEndpoints`, `reference`, `nodesHeld`, `standInCanonicalIds`, `mergesIn`,
  `localsOfMerges`, `foldEndpoints`, `Retractions.survives` and `Retractions.reaches` keep their
  bodies exactly. This change threads an already-computed value; it does not decide anything new.
- **No answer changes.** `BothFoldsAgreeTest`, `StandInAgreesInEveryHomeTest` and
  `CensusIsSafeToPasteTest` are **not edited at all**. Every tool's own tests keep every assertion
  they have; where a seam's signature moves, only the construction line moves with it, never an
  expectation.
- **`ArchitectureTest.theBootFoldsOnce` is not edited**, and neither are
  `LogProjection.of(AssertionLog)`, `GraphProjector.project(AssertionLog, GraphStore, IdentityMerge)`
  or any log-taking static's signature.
- **`own` and `ratings` are out of scope and must not be edited** — each already folds once per run
  (spec §1.3(b)). Their counts are read and recorded in Task 8, not changed.
- Pure TDD. Failing test first, **run it and observe a real assertion failure** — a compile error is
  not a red. Where the change is a guard, the positive control is the red: the guard passes on the
  real code, a plant makes it red on the *named* assertion, the plant is reverted.
- Test names `should<Expected>When<Condition>` with `@DisplayName`.
- **Never `git add -A`.** Stage every file by explicit path, with git's stderr visible (never
  `2>/dev/null`), and read `git status` before committing. One committer in this worktree.
- Commit messages end with a blank line then
  `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`.
- Gate, run **BLOCKING** (never backgrounded), after every task:
  `SEGUE_REQUIRE_BROWSER=true SEGUE_REQUIRE_GRAPHVIZ=true ./gradlew check --rerun-tasks`
  Plain `./gradlew`; JDK 25 is the only JDK. If `spotlessCheck` fails run `./gradlew spotlessApply`
  and re-run the gate. `check` includes `javadoc` with `-Werror`, so a broken `{@link}` fails the
  build.
- **Never run a writing dev task** (`own`, `ownClaim`, `retractEntity`, `rate`, or any other).
  `~/.segue/segue.db` is never read, written, copied or created. Test data goes to a JUnit
  `@TempDir` and nowhere else.
- **Never cite a `.superpowers/` path from a committed file.**
- **No timing claim anywhere** — not in a javadoc, not in an ADR, not in a commit message. This
  issue's figures are fixed-point counts and whole-log reads. Where a comment being rewritten
  carries an old timing figure (`RecommendCli`'s "0.127 ratio" paragraph), the figure is **deleted
  with the argument it supported**, never re-cited.
- **`JavadocCitationsTest` joins a wrapped code span with a space**, so a citation that wraps at the
  dot becomes `FoldTest .shouldX` and is reported as an unsupported citation shape. Keep every
  `{@code SomeTest.member}` citation on one source line.
- **Invented identifiers only** (ADR 58, ADR 51). A stand-in qid carries a leading zero
  (`Q0900042`), a minted local id two (`Q00900042`), a merge's canonical side is the eleven-digit
  shape with no leading zero (`Q10000000900`). No real entity name, no real rating, nothing derived
  from the owner's data.
- Adding an `@ArchTest` rule **requires a row in the developer guide's ArchUnit table**
  (`docs/developer-guide.md`, "Which rules a machine enforces") —
  `DeveloperGuideEnumerationsTest.shouldNameEveryArchUnitRuleWhenTheGuideTabulatesThem` fails
  otherwise.
- ArchUnit imports `src/main` only (`ImportOption.DoNotIncludeTests`), so no fence below constrains
  a test.

---

### Task 1: the Mikado exploration, recorded

**No commit. Nothing is kept.** This task exists so the prerequisites below are confirmed against
the build rather than taken on the plan's word. If the build disagrees with the list at the end,
**say so in the report and stop** — the plan is wrong and needs revising before Task 2.

**Files:** read only. Everything written here is reverted before the task ends.

- [ ] **Step 1 — establish green.** Run the gate, blocking. Record BUILD SUCCESSFUL. If it is
      already red, stop and report; nothing below is meaningful on a red tree.

- [ ] **Step 2 — attempt the target change directly, in one tool.** In
      `src/main/java/com/robsartin/segue/census/Census.java`, replace the body of `of` with the
      shape the goal asks for:

      ```java
      List<LoggedAssertion> logged = log.readAll();
      Fold fold = Fold.of(logged, KindMapper::rederive);
      LogProjection projection = LogProjection.of(logged, fold);
      return new Census(
          NodeCensus.of(projection),
          EdgeCensus.of(projection),
          ClaimCensus.of(logged, projection, fold),
          TasteCensus.of(ratings.readRatings(), fold, projection),
          DegreeCensus.of(projection),
          BridgeCensus.of(projection));
      ```

- [ ] **Step 3 — observe what breaks.** Run `./gradlew compileJava` (blocking). Record the
      compiler's actual errors verbatim in the report. Expected: no `LogProjection.of(List, Fold)`,
      no three-argument `ClaimCensus.of`, no `TasteCensus.of(Map, Fold, LogProjection)`, and
      `KindMapper`/`Fold` not imported.

- [ ] **Step 4 — write down the prerequisites, confirmed by reading.** Confirm each of these
      against the source, and record which ones the build agreed with:
      1. `src/main/java/com/robsartin/segue/export/LogProjection.java` — `of(AssertionLog)` calls
         `Retractions.in(logged)`, `Equivalences.standIns(logged, KindMapper::rederive)` and
         `Equivalences.folding(logged)`, which are `Fold`'s `retractions()`, `standIns()` and
         `equivalences()` exactly. So the prerequisite is an overload taking the rows and the fold.
      2. `src/main/java/com/robsartin/segue/census/ClaimCensus.java` — it calls
         `Retractions.in(logged)`, `Equivalences.in(logged)` and
         `Equivalences.standIns(logged, KindMapper::rederive).keySet()`, and asks the second only
         `last(merge)` and `stands(merge)`.
      3. `src/main/java/com/robsartin/segue/census/TasteCensus.java` — it calls
         `Equivalences.standIns(...).keySet()` and `Retractions.in(logged).lastRetraction()`, and
         reads no other log row, so its `logged` parameter disappears with the migration.
      4. `src/main/java/com/robsartin/segue/domain/Equivalences.java` — `folding(merges, emptied)`
         differs from `in(log)` in the `retractedStandIns` component alone, and that component is
         read only by `namesARetractedStandIn` (and `foldEndpoints`, which delegates). Confirm by
         reading that `stands`, `last`, `canonical`, `merged` and `resolve` never name it.
      5. `grep -rn "foldEndpoints\|namesARetractedStandIn" src/main/java` names only `ingest`,
         `export/LogProjection` and `retract/RetractRun` — **not** `census`, `recommend`, `rate` or
         `evaluate`. Run it and paste the result.
      The prerequisite order is therefore: the agreement pin (Task 2), then `LogProjection`'s
      overload (Task 3), then `census` (Task 4) — leaf-first, each green.

- [ ] **Step 5 — revert to green.** `git checkout -- src/main/java/com/robsartin/segue/census/Census.java`,
      then `git status --short` and confirm the working tree is **clean**. Run `./gradlew compileJava`
      once more and confirm it succeeds. Report: the compiler errors from step 3 quoted, the grep
      output from step 4.5, and whether the prerequisite list matched this plan.

---

### Task 2: pin that a folding `Equivalences` answers what a merges-only one answers

The migration hands `fold.equivalences()` — built by `Equivalences.folding` — to readers that today
hold `Equivalences.in(log)`. **This is the one place an answer could change**, so it is pinned
before anything moves.

**Files:**
- Modify: `src/test/java/com/robsartin/segue/domain/EquivalencesTest.java`
- Read only: `src/main/java/com/robsartin/segue/domain/Equivalences.java`,
  `src/test/java/com/robsartin/segue/domain/FoldFixture.java`

**Interfaces:** produces nothing. It is a guard, and its red is a planted control.

- [ ] **Step 1 — write the guard and run it green.** Add to `EquivalencesTest`, which already
      statically imports `FoldFixture.foldedLog`, `CANONICAL`, `MINTED`, `OTHER_MINTED` and
      `OTHER_CANONICAL`:

      ```java
      @Test
      @DisplayName("a folding Equivalences answers every non-edge question the merges one answers")
      void shouldAnswerAsTheMergesDoWhenTheFoldingFormIsAskedTheToolsQuestions() {
        List<LoggedAssertion> log = foldedLog();
        Equivalences merges = Equivalences.in(log);
        Equivalences folding = Equivalences.folding(log);

        assertThat(folding.canonicalByLocal())
            .as(
                "issue #246 hands folding() to census, recommend, rate and evaluate where they held"
                    + " in(); the two differ in retractedStandIns alone, which only"
                    + " namesARetractedStandIn reads, and none of those tools calls it")
            .isEqualTo(merges.canonicalByLocal());
        assertThat(folding.referencedEndpoints()).isEqualTo(merges.referencedEndpoints());
        assertThat(folding.merged()).isEqualTo(merges.merged());
        assertThat(folding.canonical(MINTED)).isEqualTo(merges.canonical(MINTED));
        assertThat(folding.resolve(Map.of(MINTED, 5, OTHER_MINTED, 4)))
            .isEqualTo(merges.resolve(Map.of(MINTED, 5, OTHER_MINTED, 4)));
        for (LoggedAssertion assertion : log) {
          if (assertion instanceof SameAs merge) {
            assertThat(folding.stands(merge)).isEqualTo(merges.stands(merge));
            assertThat(folding.last(merge)).isEqualTo(merges.last(merge));
          }
        }
        assertThat(folding.retractedStandIns())
            .as("and the two really do differ somewhere, so this is not comparing a value to itself")
            .containsExactly(CANONICAL)
            .isNotEqualTo(merges.retractedStandIns());
      }
      ```

      Run `./gradlew test --tests '*EquivalencesTest'` (blocking) and record BUILD SUCCESSFUL. It
      passes on the real code; that is what a guard does.

- [ ] **Step 2 (the RED) — plant the defect and watch the guard fire.** In `Equivalences.folding`,
      temporarily drop the merges' kept-edge set:

      ```java
      public static Equivalences folding(Equivalences merges, Set<String> retractedStandIns) {
        Objects.requireNonNull(merges, "merges");
        Objects.requireNonNull(retractedStandIns, "retractedStandIns");
        return new Equivalences(merges.canonicalByLocal(), Set.of(), retractedStandIns);
      }
      ```

      Run the same command. **Quote the assertion failure in the report** — it must name
      `referencedEndpoints` or a `stands` comparison, not a compile error and not the closing
      not-vacuous assertion. Then revert the plant with
      `git checkout -- src/main/java/com/robsartin/segue/domain/Equivalences.java` and re-run to
      confirm green.

- [ ] **Step 3 — gate and commit.** Run the full gate, blocking. Stage
      `src/test/java/com/robsartin/segue/domain/EquivalencesTest.java` by explicit path, read
      `git status`, commit.

---

### Task 3: `export` — `LogProjection` folds once, and is the only class in the package that folds

**Files:**
- Modify: `src/main/java/com/robsartin/segue/export/LogProjection.java`
- Modify: `src/test/java/com/robsartin/segue/arch/ArchitectureTest.java`
- Modify: `docs/developer-guide.md`
- Read only: `src/main/java/com/robsartin/segue/domain/Fold.java`,
  `src/main/java/com/robsartin/segue/export/ViewSelector.java`

**Interfaces:**
- Produces: `LogProjection.of(List<LoggedAssertion>, Fold)`, consumed by `Census.of` in Task 4;
  `ArchitectureTest.theExportFoldsOnce`.
- `LogProjection.of(AssertionLog)` keeps its signature — roughly fifty test call sites and
  `ViewSelector.projection()` use it and are untouched.

- [ ] **Step 1 (RED) — the fence, written before the migration.** Add to `ArchitectureTest`, beside
      `theBootFoldsOnce`:

      ```java
      /**
       * Issue #246: the export folds the log once, in LogProjection, and hands it on.
       *
       * <p>Fold.of is in the forbidden list as well as the seven log-taking statics, which
       * theBootFoldsOnce does not need: for the boot, Fold.of IS the sanctioned route, and here it
       * is the thing a second class would use to build a second fold. A statics-only rule would be
       * green while ViewSelector folded the whole log again through the type this issue introduced
       * to stop exactly that.
       */
      @ArchTest
      static final ArchRule theExportFoldsOnce =
          noClasses()
              .that()
              .resideInAPackage("com.robsartin.segue.export..")
              .and()
              .doNotBelongToAnyOf(LogProjection.class)
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
                  "issue #246: LogProjection is the export's one fold — every other class in the"
                      + " package takes what it holds, and may not build a second one through"
                      + " Fold.of either");
      ```

      Run `./gradlew test --tests '*ArchitectureTest'` (blocking). **It must pass** — nothing else
      in `export` folds today. Record that, then go straight to step 2 for the control.

- [ ] **Step 2 (the RED) — plant a second fold in `export` and watch it fire.** In
      `src/main/java/com/robsartin/segue/export/ViewSelector.java`, inside `projection()`, add
      `Equivalences.in(log.readAll());` as a statement (import `Equivalences`). Run the same
      command. **Quote the ArchUnit violation, including the rule name and the offending line.**
      Revert with `git checkout -- src/main/java/com/robsartin/segue/export/ViewSelector.java` and
      re-run green.

- [ ] **Step 3 (RED) — the overload, stubbed wrong.** Add to `LogProjection`:

      ```java
      public static LogProjection of(List<LoggedAssertion> logged, Fold fold) {
        return new LogProjection(Map.of(), List.of(), 0, 0);
      }
      ```

      and a test in `src/test/java/com/robsartin/segue/export/LogProjectionTest.java`:

      ```java
      @Test
      @DisplayName("the projection built from a prebuilt fold is the one of(log) builds itself")
      void shouldGiveTheSameProjectionWhenHandedTheFoldOfWouldCompute() {
        FakeAssertionLog log = new FakeAssertionLog().with(mergedAndRetractedLog());
        List<LoggedAssertion> logged = log.readAll();

        assertThat(LogProjection.of(logged, Fold.of(logged, KindMapper::rederive)))
            .as(
                "issue #246: of(log) reads Retractions.in, standIns and folding off the log, which"
                    + " are exactly what a Fold carries — the same answer or it is a second fold")
            .isEqualTo(LogProjection.of(log));
        assertThat(LogProjection.of(log).nodes())
            .as("and the fixture projects something, so the comparison is not two empty projections")
            .isNotEmpty();
      }
      ```

      where `mergedAndRetractedLog()` is a private static fixture in that file carrying a minted
      local entity, a merge onto an eleven-digit canonical id, an edge naming the local id, a
      retraction of a *second* minted id also merged, and a node claim on the far end — invented ids
      only, so the fold's stand-ins, withdrawals and retractions are all exercised. If
      `LogProjectionTest` already holds a log of that shape, reuse it rather than adding a second.

      Run `./gradlew test --tests '*LogProjectionTest'` (blocking). **Quote the assertion failure**;
      it must be the `isEqualTo` comparison.

- [ ] **Step 4 (GREEN) — move the body.** Make the two-argument form the real one and the
      one-argument form a two-line delegation:

      ```java
      /** Read the log once and fold it. */
      public static LogProjection of(AssertionLog log) {
        List<LoggedAssertion> logged = log.readAll();
        return of(logged, Fold.of(logged, KindMapper::rederive));
      }

      /**
       * This fold, over rows and a {@link Fold} the caller has already built (#246).
       *
       * <p>Every log-taking rule this method used to call — {@code Retractions.in}, {@code
       * Equivalences.standIns} and {@code Equivalences.folding} — is one of the four answers a
       * {@link Fold} carries, so a caller that already holds one was paying for the same three
       * walks twice. {@code census} is that caller: it reads the log for its own row counts and
       * then asked for this projection, which read the log again and folded it again.
       *
       * <p><b>Trusts the caller</b>, as the {@code Equivalences} overloads {@code Fold.of} uses do:
       * {@code fold} must be {@code Fold.of(logged, KindMapper::rederive)} for these exact rows, or
       * this answers a different question. {@code LogProjectionTest.shouldGiveTheSameProjectionWhenHandedTheFoldOfWouldCompute}
       * pins the two forms to one answer, and {@code ArchitectureTest.theExportFoldsOnce} is what
       * keeps this class the export's only fold.
       */
      public static LogProjection of(List<LoggedAssertion> logged, Fold fold) {
        Objects.requireNonNull(logged, "logged");
        Objects.requireNonNull(fold, "fold");
        Retractions retractions = fold.retractions();
        Map<String, NodeRecord> nodes = new LinkedHashMap<>(fold.standIns());
        Equivalences equivalences = fold.equivalences();
        … the existing body from `Map<String, List<AssertionRecord>> byEdge = …` onwards,
          with every `logged` reference unchanged …
      }
      ```

      Keep the three explanatory comments that stood over the three removed calls, edited to say the
      value now arrives from the `Fold` rather than being read here. Leave the class javadoc's
      `{@link Equivalences#standIns}` and `{@link Equivalences#foldEndpoints}` references alone —
      they are still the rules this fold applies.

      Run the tests, observe green. Run the **full gate**, blocking.

- [ ] **Step 5 — the guide row, then commit.** Add to `docs/developer-guide.md`, in the "Which rules
      a machine enforces" table, immediately after the `theBootFoldsOnce` row:

      ```
      | `theExportFoldsOnce` | any `export` class but `LogProjection` calling the seven log-taking fold statics or `Fold.of` — the export folds in one place and every other class takes what it holds. `Fold.of` is forbidden too, unlike in `theBootFoldsOnce`, because here it is the second class's route to a second fold rather than the sanctioned one | [ADR 64](adr/0064-fold-the-log-once-per-boot.md) |
      ```

      Run the full gate blocking (it runs `DeveloperGuideEnumerationsTest`). Stage
      `src/main/java/com/robsartin/segue/export/LogProjection.java`,
      `src/test/java/com/robsartin/segue/export/LogProjectionTest.java`,
      `src/test/java/com/robsartin/segue/arch/ArchitectureTest.java` and
      `docs/developer-guide.md` by explicit path, read `git status`, commit.

---

### Task 4: `census` — one fold and one read for all six sections

**Files:**
- Modify: `src/main/java/com/robsartin/segue/census/Census.java`,
  `src/main/java/com/robsartin/segue/census/ClaimCensus.java`,
  `src/main/java/com/robsartin/segue/census/TasteCensus.java`
- Modify: `src/test/java/com/robsartin/segue/census/ClaimCensusTest.java`,
  `src/test/java/com/robsartin/segue/census/TasteCensusTest.java`,
  `src/test/java/com/robsartin/segue/census/CensusReportTest.java` — **construction lines only, no
  assertion touched**
- Modify: `src/test/java/com/robsartin/segue/arch/ArchitectureTest.java`, `docs/developer-guide.md`
- **Do not touch** `src/test/java/com/robsartin/segue/census/CensusIsSafeToPasteTest.java`

**Interfaces:**
- Produces: `ClaimCensus.of(List<LoggedAssertion>, LogProjection, Fold)`,
  `TasteCensus.of(Map<String, Integer>, Fold, LogProjection)`,
  `ArchitectureTest.theCensusFoldsOnce`.

- [ ] **Step 1 (RED) — the fence first, and it fails for the right reason.** Add to
      `ArchitectureTest`, beside `theExportFoldsOnce`, the same rule shape with
      `resideInAPackage("com.robsartin.segue.census..")`, `doNotBelongToAnyOf(Census.class)` and:

      ```java
              .because(
                  "issue #246: Census.of builds the census's one fold and hands it to every"
                      + " section — ClaimCensus and TasteCensus took the raw rows and folded them"
                      + " again, which is three extra whole-log fixed points and a second read");
      ```

      Run `./gradlew test --tests '*ArchitectureTest'` (blocking). **It must FAIL**, naming
      `ClaimCensus`'s `Retractions.in`, `Equivalences.in` and `Equivalences.standIns` calls and
      `TasteCensus`'s `Equivalences.standIns` and `Retractions.in` calls. **Quote the violation list
      in the report** — that is this task's red, and it is an assertion failure rather than a
      compile error.

- [ ] **Step 2 (GREEN, leaf) — `TasteCensus` takes the fold.** Add the overload, drop the rows:

      ```java
      /**
       * @param fold this census's one fold (#246) — its stand-in key set and its retractions are
       *     the only two things this section ever read off the log, so the rows themselves are no
       *     longer a parameter
       */
      public static TasteCensus of(
          Map<String, Integer> ratings, Fold fold, LogProjection projection) {
        Objects.requireNonNull(ratings, "ratings");
        Objects.requireNonNull(fold, "fold");
        Objects.requireNonNull(projection, "projection");
        …
        Set<String> standIns = fold.standIns().keySet();
        Set<String> retracted = fold.retractions().lastRetraction().keySet();
        … the existing loop, unchanged …
      }
      ```

      Delete the three-argument `of(Map, List, LogProjection)` form — it has three call sites, all
      named in **Files** above — and remove the now-unused `KindMapper`, `Equivalences`,
      `Retractions`, `LoggedAssertion` and `List` imports. Update the three call sites to
      `TasteCensus.of(ratings, fold, projection)`, building the fold in the test with
      `Fold.of(InventedCensus.log(), KindMapper::rederive)`. **No assertion in those tests changes.**
      Run `./gradlew test --tests '*TasteCensusTest' --tests '*CensusReportTest'` (blocking) and
      observe green.

- [ ] **Step 3 (GREEN, leaf) — `ClaimCensus` takes the fold.** Same shape:

      ```java
      public static ClaimCensus of(
          List<LoggedAssertion> logged, LogProjection projection, Fold fold) {
        Objects.requireNonNull(logged, "logged");
        Objects.requireNonNull(projection, "projection");
        Objects.requireNonNull(fold, "fold");

        Retractions retractions = fold.retractions();
        // The folding Equivalences answers last() and stands() exactly as the merges-only one this
        // section used to build did — the two differ in retractedStandIns alone, which only
        // namesARetractedStandIn reads and nothing in census calls (#246). Pinned by
        // EquivalencesTest.shouldAnswerAsTheMergesDoWhenTheFoldingFormIsAskedTheToolsQuestions.
        Equivalences equivalences = fold.equivalences();
        …
        Set<String> standIns = fold.standIns().keySet();
        … the existing body, unchanged …
      }
      ```

      `logged` stays: `logged.size()` is the `rows` count and the loop reads every row. Delete the
      two-argument form, drop the `KindMapper` import, update the two call sites in
      `ClaimCensusTest` and `CensusReportTest`. Run
      `./gradlew test --tests '*ClaimCensusTest' --tests '*CensusReportTest'` (blocking), green.

- [ ] **Step 4 (GREEN) — `Census.of` builds the one fold.** Replace the body with the shape Task 1
      step 2 attempted, and correct the class javadoc paragraph that begins "**The log is read
      twice**" — it records the rejected alternative this issue now takes:

      ```java
      /**
       * <p><b>The log is read once, and folded once</b> (#246). It used to be read twice — once for
       * the raw rows and once inside {@link LogProjection#of(AssertionLog)} — and folded five times,
       * because each of {@code LogProjection}, {@link ClaimCensus} and {@link TasteCensus} derived
       * the retractions, the merges and the stand-ins from the rows on its own account. The
       * overload on {@code LogProjection} that this class's earlier note rejected as "widening
       * another package's public API for a dev tool's convenience" is now taken, because it is what
       * carries the {@link Fold} as well as the rows; the second read went with it.
       * {@code ArchitectureTest.theCensusFoldsOnce} is what keeps this method the only fold here.
       */
      ```

      Run `./gradlew test --tests '*ArchitectureTest'` and observe the fence **green**. Then run the
      **full gate**, blocking — `CensusIsSafeToPasteTest`, `CensusRunTest`,
      `DeveloperGuideCensusExamplesTest` and every section test must pass unedited.

- [ ] **Step 5 — the positive control, planted where it now bites.** In `ClaimCensus.of`, add
      `Equivalences.in(logged);` as a statement (the import is still there). Run
      `./gradlew test --tests '*ArchitectureTest'` and **quote the `theCensusFoldsOnce` violation**.
      Remove the planted line **by hand** — never `git checkout` this file, which would discard
      step 3's migration — then re-run, confirm green, and confirm `git diff` still shows the
      migration.

- [ ] **Step 6 — the guide row and the count sentence, then commit.** Add the `theCensusFoldsOnce`
      row to the ArchUnit table, and check the guide's census sections — the "one fold, not two"
      diagram edge and the "It counts the export's fold, not a second one" section — still read
      true; where either says the census reads or folds twice, correct it in this commit. Full gate,
      blocking. Stage the three main files, the three test files, `ArchitectureTest.java` and
      `docs/developer-guide.md` by explicit path, read `git status`, commit.

---

### Task 5: `retract` — thread the emptied set, keep the two questions

**No fence** (spec §2.4): `RetractRun` folds twice, legitimately, for the log as it stands and the
log the retraction would produce. What comes out is the third fold, which re-derives a set the
method is already holding.

**Files:**
- Modify: `src/main/java/com/robsartin/segue/retract/RetractRun.java`
- Modify: `src/main/java/com/robsartin/segue/domain/Equivalences.java` — javadoc only
- Read only: `src/test/java/com/robsartin/segue/retract/RetractRunTest.java`

**Interfaces:** produces nothing. No signature moves.

**Verification method, said out loud.** This step changes no answer and adds no behaviour, so there
is no new assertion to write and no honest red available from a new test. Its verification is
therefore two named things rather than one: the whole of `RetractRunTest`,
`DeveloperGuideRetractionExamplesTest` and `RetractCliTest` green **unedited** before and after, and
a **planted-difference control** proving those tests actually reach this code path and would catch a
wrongly-threaded set.

- [ ] **Step 1 — record the baseline.** Run
      `./gradlew test --tests '*RetractRunTest' --tests '*RetractCliTest' --tests '*DeveloperGuideRetractionExamplesTest'`
      (blocking). Record BUILD SUCCESSFUL and the test count.

- [ ] **Step 2 — the change.** In `strandedByThisRetraction`, hold the set and hand it on:

      ```java
      // The emptied set for the log this retraction would produce, computed once and threaded
      // (#246). Equivalences.folding(after) below would recompute it — that is a whole-log fixed
      // point, a loop of whole-log walks, paid for an answer this method is already holding. The
      // two-argument forms are the caller-trusting overloads ADR 64 added, and their contract is
      // honoured exactly: emptiedAfter IS retractedStandIns of this same list.
      //
      // The before/after pair stays two folds, and that is the design rather than a shortfall:
      // they are two different questions about two different lists, and the difference between
      // them is what "newly emptied" means.
      Set<String> emptiedAfter = Equivalences.retractedStandIns(after);
      Set<String> newlyEmptied = new LinkedHashSet<>(emptiedAfter);
      newlyEmptied.removeAll(Equivalences.retractedStandIns(before));
      if (newlyEmptied.isEmpty()) {
        return List.of();
      }

      Retractions retractions = Retractions.in(after);
      Equivalences equivalences =
          Equivalences.folding(Equivalences.in(after, emptiedAfter), emptiedAfter);
      ```

      Everything below is unchanged. Run the three test classes from step 1, blocking, observe the
      same green.

- [ ] **Step 3 (the control) — plant a wrong set and watch a named assertion fail.** Change the
      `folding` line to `Equivalences.folding(Equivalences.in(after, emptiedAfter), Set.of())`. Run
      `./gradlew test --tests '*RetractRunTest'` (blocking). **Quote the assertion failure** — it
      must be a stranded-edge note test, which proves those tests see this path. Restore the correct
      line by hand and re-run green.

- [ ] **Step 4 — correct the javadoc this makes false.** In `Equivalences`, the four caller-trusting
      overloads each say they exist for `Fold.of` and are "fenced to that one caller". Two of them —
      `in(List, Set)` and `folding(Equivalences, Set)` — now have a second caller. Amend both to
      name it and say why it is legitimate, e.g. on `in(List, Set)`:

      ```java
       * <p>It exists for {@code Fold.of} — the single per-boot fold that computes {@code
       * retractedStandIns} once and hands it to every reader — and {@code
       * ArchitectureTest.theBootFoldsOnce} is what keeps the boot going through it. Since #246
       * {@code RetractRun.strandedByThisRetraction} calls it too: that method holds {@code
       * retractedStandIns} of the log a retraction would produce, for its own report, and
       * {@code folding(List)} would have recomputed it. Same contract, honoured the same way — the
       * set is this exact list's own.
      ```

      Leave `standIns(List, UnaryOperator, Equivalences)` and `nodesTheFoldHolds(List, Set)` alone;
      `Fold.of` is still their only caller. Also correct `folding(List)`'s caller list — it names
      "`OwnRun`, `RateCli`, `ratings/Labels` and `RecommendCli`" as the callers of `in(List)` and
      omits `EvaluateCli`, which has called it since #242. Add it now; Task 7 removes three of the
      four.

- [ ] **Step 5 — gate and commit.** Full gate, blocking. Stage
      `src/main/java/com/robsartin/segue/retract/RetractRun.java` and
      `src/main/java/com/robsartin/segue/domain/Equivalences.java` by explicit path, read
      `git status`, commit.

---

### Task 6: `GraphProjector.replay` — the boot hands its fold back

The leaf for Task 7. Nothing consumes it yet.

**Files:**
- Create: `src/main/java/com/robsartin/segue/ingest/Replay.java`
- Modify: `src/main/java/com/robsartin/segue/ingest/GraphProjector.java`
- Modify: `src/test/java/com/robsartin/segue/ingest/GraphProjectorTest.java`

**Interfaces:**
- Produces: `Replay(long applied, Fold fold)` and
  `GraphProjector.replay(AssertionLog, GraphStore, IdentityMerge)`. Consumed in Task 7.
- `GraphProjector.project` keeps its signature, its javadoc and all ~60 call sites.

- [ ] **Step 1 (RED) — the test, against a deliberately wrong stub.** Add to `GraphProjectorTest`:

      ```java
      @Test
      @DisplayName("a replay hands back the fold it applied, and the count project() returns")
      void shouldReturnTheFoldItAppliedWhenTheLogIsReplayed() {
        FakeAssertionLog log = … the file's existing merge-and-retraction fixture …;
        TinkerGraphStore store = new TinkerGraphStore();

        Replay replay = GraphProjector.replay(log, store, IdentityMerge.NONE);

        assertThat(replay.fold())
            .as(
                "issue #246: recommend, rate and evaluate read the log a second time and folded it"
                    + " again for merges this replay had already derived — handing the fold back is"
                    + " the whole change, so it has to BE the fold, not a fresh one")
            .isEqualTo(Fold.of(log.readAll(), KindMapper::rederive));
        assertThat(replay.applied())
            .as("and the count is the one project() has always returned")
            .isEqualTo(GraphProjector.project(log, new TinkerGraphStore(), IdentityMerge.NONE));
        assertThat(replay.applied()).as("on a fixture that applies something").isPositive();
      }
      ```

      Create `Replay` and add `replay` with a body that returns the wrong value, so the assertion
      and not the compiler fails:

      ```java
      public static Replay replay(AssertionLog log, GraphStore store, IdentityMerge merges) {
        return new Replay(0, Fold.of(List.of(), UnaryOperator.identity()));
      }
      ```

      Run `./gradlew test --tests '*GraphProjectorTest'` (blocking). **Quote the assertion
      failure**; it must be the `isEqualTo(Fold.of(…))` comparison.

- [ ] **Step 2 (GREEN) — move `project`'s body into `replay`.** `Replay`:

      ```java
      package com.robsartin.segue.ingest;

      import com.robsartin.segue.domain.Fold;
      import java.util.Objects;

      /**
       * What one replay produced: how many assertions reached the store, and the {@link Fold} it
       * applied them under (#246).
       *
       * <p><b>It exists so a tool that replays does not fold the same log twice.</b> {@code
       * recommend}, {@code rate} and {@code evaluate} each replay into a throwaway graph and then
       * read the log a second time for the merges, because a merge is deliberately not drawn in the
       * graph as an edge and {@code project} answered with a count. The fold they re-derived is the
       * one {@code project} had just built. This record hands it back.
       *
       * <p><b>A value rather than a {@code Consumer<Fold>} callback</b>, which was the alternative:
       * a consumer cannot return, so every caller would invent a mutable holder whose only purpose
       * is to defeat the callback, and the ordering of the callback against the replay would be a
       * convention rather than a type. See the design note ADR 64 points at.
       *
       * <p>{@code GraphProjector.project} is unchanged and returns {@link #applied} alone — sixty
       * call sites keep the signature they have, which is what makes this a parallel field rather
       * than a big-bang change to a return type (ADR 4).
       */
      public record Replay(long applied, Fold fold) {

        public Replay {
          Objects.requireNonNull(fold, "fold");
        }
      }
      ```

      In `GraphProjector`, rename the existing method's body to `replay`, returning
      `new Replay(applied, fold)`, and make `project` a delegation that keeps its whole javadoc:

      ```java
      public static long project(AssertionLog log, GraphStore store, IdentityMerge merges) {
        return replay(log, store, merges).applied();
      }
      ```

      Give `replay` a short javadoc pointing at `project` for the `merges` contract rather than
      restating it. Run the tests, green. **Full gate, blocking** — `theBootFoldsOnce` must stay
      green (`Replay` calls nothing) and every `project` call site must still compile.

- [ ] **Step 3 — commit.** Stage `src/main/java/com/robsartin/segue/ingest/Replay.java`,
      `src/main/java/com/robsartin/segue/ingest/GraphProjector.java` and
      `src/test/java/com/robsartin/segue/ingest/GraphProjectorTest.java` by explicit path, read
      `git status`, commit.

---

### Task 7: `recommend`, `rate` and `evaluate` take the boot's fold

Three call sites of one shape, one fence over all three. Grouped because the fence is meaningless
until the last of them has moved.

**Files:**
- Modify: `src/main/java/com/robsartin/segue/recommend/RecommendCli.java`,
  `src/main/java/com/robsartin/segue/rate/RateCli.java`,
  `src/main/java/com/robsartin/segue/evaluate/EvaluateCli.java`
- Modify: `src/test/java/com/robsartin/segue/rate/MergedIdIsNotDealtTest.java` — the one caller of
  the `RateCli.deck` seam, **construction line only**
- Modify: `src/main/java/com/robsartin/segue/domain/Equivalences.java` — javadoc only
- Modify: `src/test/java/com/robsartin/segue/arch/ArchitectureTest.java`, `docs/developer-guide.md`

- [ ] **Step 1 (RED) — the fence over the three packages, before any of them moves.**

      ```java
      /**
       * Issue #246: a tool that replays the log does not fold it again.
       *
       * <p>One rule over three packages because it states one property. Each of these tools calls
       * {@code GraphProjector.project}, which builds the whole fold, and each then read the log a
       * second time and rebuilt the merges from it. Since #246 the fold comes back from {@code
       * GraphProjector.replay}, so no class in any of the three has any business folding — there is
       * no exempt class here, unlike theBootFoldsOnce and theExportFoldsOnce, because the one home
       * of these tools' fold is not in these packages at all.
       *
       * <p>{@code evaluate} is in the list although issue #246 does not name it: it grew the same
       * shape in #242, after ADR 64 was written, and a fence that skipped it would be green over a
       * third copy of the defect.
       */
      @ArchTest
      static final ArchRule theReplayingToolsTakeTheBootsFold =
          noClasses()
              .that()
              .resideInAnyPackage(
                  "com.robsartin.segue.recommend..",
                  "com.robsartin.segue.rate..",
                  "com.robsartin.segue.evaluate..")
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
                  "issue #246: these tools replay the log through GraphProjector, which folds it —"
                      + " they take that fold back from Replay rather than reading the log a second"
                      + " time and folding it again");
      ```

      Run `./gradlew test --tests '*ArchitectureTest'` (blocking). **It must FAIL**, naming
      `RecommendCli`, `RateCli` and `EvaluateCli`. **Quote all three violations.** This is the task's
      red; it stays red until step 4.

- [ ] **Step 2 (GREEN) — `recommend`.** In `RecommendCli.main`:

      ```java
      Replay replay = GraphProjector.replay(assertions, graph, IdentityMerge.NONE);
      log.info("replayed {} assertion(s) from {}", replay.applied(), options.database());

      // The merges the replay already derived, handed back rather than read out of the log a
      // second time (#246). They are not on the graph — a merge is deliberately not drawn there
      // as an edge — which is why this tool had to fold at all; project() answering with a count
      // was the other half of the reason, and Replay is what answers with both.
      //
      // fold.equivalences() is the folding form where this held Equivalences.in's merges-only
      // one. The two differ in retractedStandIns alone, which only namesARetractedStandIn reads
      // and nothing in this package calls; pinned by
      // EquivalencesTest.shouldAnswerAsTheMergesDoWhenTheFoldingFormIsAskedTheToolsQuestions.
      Equivalences merges = replay.fold().equivalences();
      ```

      **Delete** the paragraph beginning "A second pass over the log, and the cheaper shapes are
      worse (#92)" in full, including its measured millisecond figures: the argument it makes ("the
      merges could be returned by `project()` instead, but three of its four callers have no use for
      them and the return type is a count today") is the one this issue overturns, and its numbers
      go with it rather than being re-cited under a claim they no longer support.

      Run `./gradlew test --tests '*Recommend*'` (blocking), green. **Full gate**, blocking. Stage
      `RecommendCli.java` by explicit path, commit.

- [ ] **Step 3 (GREEN) — `rate`.** `RateCli.deck` takes the fold's merges instead of the rows:

      ```java
      /**
       * @param merges the merges this run folds by — {@code Replay.fold().equivalences()} from the
       *     replay {@code main} has already performed (#246). It used to be the raw log rows, which
       *     this method folded itself: a second whole-log read and a second fixed point for a value
       *     the replay had just built
       */
      static List<Card> deck(
          GraphStore graph,
          Equivalences merges,
          Map<String, Integer> asStored,
          Options options,
          Consumer<String> notes) {
        … the existing body from `Map<String, Integer> rated = merges.resolve(asStored);` on …
      }
      ```

      and in `main`:

      ```java
      Replay replay = GraphProjector.replay(assertions, graph, IdentityMerge.NONE);
      log.info("replayed {} assertion(s) from {}", replay.applied(), options.database());

      List<Card> deck =
          deck(graph, replay.fold().equivalences(), affinity.readRatings(), options, RateCli::note);
      ```

      replacing the "A second pass over the log, for the merges (#92)" comment with one saying the
      fold now comes back from the replay. Update the single caller in
      `MergedIdIsNotDealtTest:96` from `assertions.readAll()` to
      `GraphProjector.replay(assertions, graph, IdentityMerge.NONE).fold().equivalences()` — or to
      whatever that test already holds if it has a `Replay` in scope. **No assertion in that test
      changes.** Note `theRatingDeckLogsNoRating` still holds: nothing here names `AffinityRecord`.

      Run `./gradlew test --tests '*Rate*' --tests '*MergedIdIsNotDealtTest'` (blocking), green.
      Full gate, blocking. Stage `RateCli.java` and `MergedIdIsNotDealtTest.java`, commit.

- [ ] **Step 4 (GREEN) — `evaluate`, and the fence goes green.** In `EvaluateCli.run`, the same
      substitution:

      ```java
      Replay replay = GraphProjector.replay(assertions, graph, IdentityMerge.NONE);
      log.info("replayed {} assertion(s)", replay.applied());

      // Resolved through the merges the replay already derived (#246), exactly as RecommendCli
      // does and for the same reason: a merge leaves two affinity rows naming one thing, and a
      // split that counted both would hold out one id and leave the other in the known-list.
      Equivalences merges = replay.fold().equivalences();
      ```

      Run `./gradlew test --tests '*ArchitectureTest'` and observe `theReplayingToolsTakeTheBootsFold`
      **green** — the first time it has been. Then the full gate, blocking.

- [ ] **Step 5 — the positive control.** Add `Equivalences.in(assertions.readAll());` back into
      `EvaluateCli.run` as a statement. Run `./gradlew test --tests '*ArchitectureTest'`, **quote the
      violation naming `theReplayingToolsTakeTheBootsFold` and `EvaluateCli`**, then remove the
      planted line by hand (not with `git checkout`, which would discard step 4) and confirm green.

- [ ] **Step 6 — the javadoc and the guide row, then commit.** In `Equivalences.folding(List)`,
      correct the caller list again: after this task the direct callers of `in(List)` are `OwnRun`
      and `ratings/Labels` only, and `RetractRun` reaches `in(List, Set)`. Add the guide's ArchUnit
      row:

      ```
      | `theReplayingToolsTakeTheBootsFold` | any class in `recommend`, `rate` or `evaluate` calling the seven log-taking fold statics or `Fold.of` — each replays through `GraphProjector`, which folds the log, so each takes that fold back from `Replay` rather than reading the log a second time. No exempt class, because the one home of their fold is not in these packages | [ADR 64](adr/0064-fold-the-log-once-per-boot.md) |
      ```

      Full gate, blocking. Stage `EvaluateCli.java`, `Equivalences.java`, `ArchitectureTest.java`
      and `docs/developer-guide.md` by explicit path, read `git status`, commit.

---

### Task 8: the ADR 64 amendment, and the guide's prose

**Files:**
- Modify: `docs/adr/0064-fold-the-log-once-per-boot.md` — a dated amendment appended, **nothing
  above it edited**
- Modify: `docs/developer-guide.md`
- Read only: every call site touched by Tasks 3–7, plus
  `src/main/java/com/robsartin/segue/own/OwnRun.java` and
  `src/main/java/com/robsartin/segue/ratings/Labels.java`

- [ ] **Step 1 — derive the after-counts by reading, not by subtracting.** For each of the nine
      tools, open the post-change call sites and count invocations of the entries in the spec's §1.1
      table on one run. **Record the derivation per tool in the report**, naming the file and the
      lines. Do the same for `own` and `ratings`, which nothing in this branch edited — the count
      has to be read off `OwnRun.assertEdge`, `OwnRun.declareMerge` and `Labels.forQids` rather than
      carried over from ADR 64's before-figures, which are what this amendment exists partly to
      correct. **If any count disagrees with the spec's §5 table, stop and report** — the branch is
      wrong, not the reading.

- [ ] **Step 2 — the amendment.** Append to `docs/adr/0064-fold-the-log-once-per-boot.md`, after the
      existing consequences and above nothing:

      ```markdown
      ## Amendment, 2026-09-04 — the tool side, and a correction to this ADR's census figure

      Issue [#246](https://github.com/robsartin/segue/issues/246) took the first residual named
      above: the dev tools that read the log directly. **No fold rule changed, no reader's answer
      changed and no tool's output changed** — each tool now derives its fold once per run and hands
      it on.

      **This ADR's own count for `census` was wrong, and this says so rather than editing it.** The
      consequences above read "`census`, and `export`'s whole-log views, fold through
      `LogProjection.of` — three to two". That is `LogProjection.of`'s count. `census` also ran
      `ClaimCensus.of` and `TasteCensus.of`, which folded the same rows three further times, and it
      read the whole log twice. The tool paid five whole-log fixed points per run, not three.

      Counted the same way — invocations of the fixed point `Equivalences.retractedStandIns`
      computes — and read off the call sites after #246 rather than subtracted from the figures
      above:

      | tool | before | after | how |
      | --- | --- | --- | --- |
      | `census` | 5 folds, 2 log reads | 1, 1 | `Census.of` builds one `Fold` and one row list for all six sections |
      | `export`, whole-log views | 2, 1 | 1, 1 | `LogProjection.of(List, Fold)` |
      | `export`, bounded views | 1, 1 | unchanged | already the boot's single fold |
      | `retract` | 3, 2 | 2, 2 | the emptied set threaded into `in(List, Set)` and `folding(Equivalences, Set)`; two folds kept, for two questions about two lists |
      | `recommend` | 2, 2 | 1, 1 | `GraphProjector.replay` hands the fold back |
      | `rate` | 2, 2 | 1, 1 | as above |
      | `evaluate` | 2, 2 | 1, 1 | as above. Not named by #246 — it grew this shape in #242, after this ADR |
      | `own` | 1 (0 on `mint`), 1 | unchanged | it already folded once; there was never a second to remove |
      | `ratings` | 1, 1 | unchanged | as above |

      **No timing figure is claimed for any of this and none was taken.** The dated measurement
      above is the boot's, and it stands as the only one; these tools run once and exit.

      **What pins it is three more fences, and one tool deliberately without one.**
      `theExportFoldsOnce`, `theCensusFoldsOnce` and `theReplayingToolsTakeTheBootsFold` each forbid
      the seven log-taking statics **and `Fold.of`** outside the tool's one home — `Fold.of` too,
      unlike `theBootFoldsOnce`, because outside the boot it is the route a second class would take
      to a second fold. `retract` gets none: it folds twice by design, so a fence would have to
      exempt `RetractRun`, and a rule whose only clause is "`RetractRun` may fold" is green while
      `RetractRun` folds five times. `own` and `ratings` get none because #246 changed no code in
      either.

      **`Equivalences.in(List, Set)` and `folding(Equivalences, Set)` have a second caller.** Their
      javadoc said they were fenced to `Fold.of`; `RetractRun.strandedByThisRetraction` calls both,
      honouring the same contract — the set is `retractedStandIns` of that exact list. `retract`
      does not build a `Fold` and must not: `Fold.of` requires a `rederive`, and
      `Equivalences.retractedStandIns` carries no such parameter precisely so that `retract` can
      call it without learning Wikidata's vocabulary (ADR 44).

      **The residuals this leaves.** `Retractions.in(log)` is still re-derived inside
      `Equivalences.mergesIn`, `referencedEndpoints`, `nodesHeld`, `emptiedGiven` and
      `localsOfMerges` — untouched, as above. `retract` still reads the whole log twice, and
      `OwnRun` still calls `Retractions.in` twice on one list: those are read savings rather than
      fold savings and were left out of scope deliberately.
      ```

- [ ] **Step 3 — the guide.** In "The boot folds the log once", the sentence "Every fold rule stays
      where it was and every log-taking static keeps its signature, so the dev tools still fold per
      run and are deliberately out of scope" is now false in its second half. Replace the clause and
      add a short following section — **"Each tool folds once too"** — saying which mechanism each
      tool uses and pointing at ADR 64's amendment for the counts, without restating a single
      number (it lives once, in the ADR). Check the three ArchUnit rows added in Tasks 3, 4 and 7
      are present and their wording matches the rules' `because` clauses.

- [ ] **Step 4 — gate and commit.** Full gate, blocking — `AdrIndexTest` (ADR 64's index row is
      unchanged: the title, number and status did not move), `DeveloperGuideEnumerationsTest` and
      every documentation test must pass. Stage
      `docs/adr/0064-fold-the-log-once-per-boot.md` and `docs/developer-guide.md` by explicit path,
      read `git status`, commit.

---

## Done when

- Every task's gate ran blocking and reported BUILD SUCCESSFUL.
- `BothFoldsAgreeTest`, `StandInAgreesInEveryHomeTest` and `CensusIsSafeToPasteTest` are byte-for-byte
  unchanged on the branch (`git diff main --stat` names none of them).
- Three new `@ArchTest` rules exist, each was seen red — `theCensusFoldsOnce` and
  `theReplayingToolsTakeTheBootsFold` on the real code before their migration,
  `theExportFoldsOnce` under a planted second fold — and each violation is quoted in the report.
- `own/OwnRun.java` and `ratings/Labels.java` appear in no commit on this branch.
- `git log --oneline main..HEAD` shows one commit per task from Task 2 onward, plus the extra
  commits Task 7 takes per tool.
