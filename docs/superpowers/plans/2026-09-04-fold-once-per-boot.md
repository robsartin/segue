# Fold the log once per boot — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to
> implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `GraphProjector.project` derives the assertion-log fold **once** per boot and hands it to
every reader, with no reader's answer changed, pinned by an ArchUnit fence that is proven able to
fail, and with the saving measured rather than claimed.

**Architecture:** one new record, `Fold`, in `domain` — a carrier, no behaviour beyond accessors.
Four new overloads on `Equivalences`, each the existing method with the part the caller already
holds taken as a parameter; every existing log-taking static keeps its signature and becomes a
one-line delegation, so every dev tool is untouched. `GraphProjector` then migrates onto `Fold` one
call site at a time. Mikado throughout: the overloads are the prerequisites, they land leaf-first,
and the gate is green at every commit.

**Tech Stack:** Java (toolchain 25, `release 21`), JUnit 5, AssertJ, ArchUnit, JaCoCo.

**Spec:** `docs/superpowers/specs/2026-09-04-fold-once-per-boot-design.md` — it holds the measured
call table, the correction to the issue's framing (the pre-flight is a private method of
`GraphProjector`, not a separate class, which is why the fence names one class and not the `ingest`
package), the four overload signatures, the rejected alternatives, and the residual. Cite it; do not
restate its reasoning.

## Global Constraints

- **No fold rule and no fixed point changes.** `emptiedCanonicalIds`, `emptiedGiven`,
  `referencedEndpoints`, `reference`, `nodesHeld`, `standInCanonicalIds`, `mergesIn`,
  `localsOfMerges`, `foldEndpoints`, `Retractions.survives` and `Retractions.reaches` keep their
  bodies exactly. This change threads an already-computed value; it does not decide anything new.
- **`BothFoldsAgreeTest`'s applied count stays at 30**, and its assertion message is not edited.
- **`StandInAgreesInEveryHomeTest` is not touched at all.**
- **Every existing log-taking static keeps its signature and its answer.** The dev tools
  (`census`, `export`, `own`, `rate`, `ratings`, `recommend`, `retract`) are out of scope and must
  not be edited.
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
- **`JavadocCitationsTest` joins a wrapped code span with a space**, so a citation that wraps at the
  dot — `{@code FoldTest` newline `* .shouldX}` — becomes `FoldTest .shouldX` and is reported as an
  unsupported citation shape. Keep every `{@code SomeTest.member}` citation on one source line.
- **Invented identifiers only** (ADR 58, ADR 51). A stand-in qid carries a leading zero
  (`Q0900042`), a minted local id two (`Q00900042`), a merge's canonical side is the eleven-digit
  shape with no leading zero (`Q10000000900`). No real entity name, no real rating, nothing derived
  from the owner's data.
- Adding an `@ArchTest` rule **requires a row in the developer guide's ArchUnit table**
  (`docs/developer-guide.md`, "Which rules a machine enforces") —
  `DeveloperGuideEnumerationsTest.shouldNameEveryArchUnitRuleWhenTheGuideTabulatesThem` fails
  otherwise.
- Adding an ADR **requires a row in `docs/adr/README.md`** — `AdrIndexTest` checks number, title,
  status, file existence and ascending order within the section.

---

### Task 1: the Mikado exploration, recorded

**No commit. Nothing is kept.** This task exists so the prerequisites below are confirmed against
the build rather than taken on the plan's word. If the build disagrees with the list at the end,
**say so in the report and stop** — the plan is wrong and needs revising before Task 2.

**Files:** read only. Everything written here is reverted before the task ends.

- [ ] **Step 1 — establish green.** Run the gate, blocking. Record BUILD SUCCESSFUL. If it is
      already red, stop and report; nothing below is meaningful on a red tree.

- [ ] **Step 2 — attempt the target change directly.** In
      `src/main/java/com/robsartin/segue/ingest/GraphProjector.java`, replace lines 89–104 of
      `project` with the shape the goal asks for, referring to a `Fold` that does not exist:

      ```java
      List<LoggedAssertion> assertions = log.readAll();
      Fold fold = Fold.of(assertions, KindMapper::rederive);
      refuseRowsNamingAnEntityNoNodeStandsFor(assertions, fold);
      for (NodeRecord standIn : fold.standIns().values()) {
        store.upsertNode(standIn);
      }
      ```

      and in `refuseRowsNamingAnEntityNoNodeStandsFor` replace the
      `(List, Retractions, Equivalences)` parameters with `(List<LoggedAssertion>, Fold)` and
      `Set<String> held = Equivalences.nodesTheFoldHolds(assertions);` with
      `Set<String> held = fold.nodesHeld();`.

- [ ] **Step 3 — observe what breaks.** Run `./gradlew compileJava` (blocking). Record the
      compiler's actual errors verbatim in the report. Expected: `Fold` cannot be resolved, and the
      later uses of the now-deleted `retractions` and `equivalences` locals in the replay loop.

- [ ] **Step 4 — write down the prerequisites.** For each thing `Fold.of` would have to do, name the
      `Equivalences` method it would have to call and whether that method can be called without
      re-deriving something `Fold.of` already has. Confirm, by reading
      `src/main/java/com/robsartin/segue/domain/Equivalences.java`, that:
      1. `Equivalences.in(List)` calls `emptiedCanonicalIds(log)` itself — so a prebuilt emptied set
         cannot be reused without a new overload;
      2. `Equivalences.folding(List)` calls both `Equivalences.in(log)` and `retractedStandIns(log)`
         — two fixed points in one call;
      3. `Equivalences.standIns(List, UnaryOperator)` opens with `Equivalences.in(log)`;
      4. `Equivalences.nodesTheFoldHolds(List)` calls `standIns(log, identity)`, and the private
         `nodesHeld(log, standInIds)` beside it is already the shape a caller with a stand-in set
         would want.
      The prerequisite list is therefore the four overloads in Task 2, then `Fold` in Task 3, then
      the migration in Task 4 — leaf-first, each green.

- [ ] **Step 5 — revert to green.** `git checkout -- src/main/java/com/robsartin/segue/ingest/GraphProjector.java`,
      then `git status --short` and confirm the working tree is **clean**. Run
      `./gradlew compileJava` once more and confirm it succeeds. Report: the compiler errors from
      step 3 quoted, the confirmed prerequisite list, and whether it matched this plan.

---

### Task 2: four overloads that take what the caller already has

Four RED → GREEN → commit cycles, in this order. **Run the full gate and commit after each step.**
Each overload's javadoc must say that it trusts the caller — it is correct only for values derived
from the same log — and name `Fold.of` as the caller it exists for.

**Files:**
- Modify: `src/main/java/com/robsartin/segue/domain/Equivalences.java`
- Modify: `src/test/java/com/robsartin/segue/domain/EquivalencesTest.java`
- Read only: `src/main/java/com/robsartin/segue/domain/Retractions.java`,
  `src/main/java/com/robsartin/segue/ingest/GraphProjector.java`

**Interfaces:**
- Produces: `Equivalences.in(List, Set)`, `Equivalences.folding(Equivalences, Set)`,
  `Equivalences.standIns(List, UnaryOperator, Equivalences)`,
  `Equivalences.nodesTheFoldHolds(List, Set)`. Consumed by `Fold.of` in Task 3 and by nothing else.
- Changes nothing about `in(List)`, `folding(List)`, `standIns(List, UnaryOperator)`,
  `nodesTheFoldHolds(List)`, which keep their signatures and their answers.

**The shared fixture.** Every test below uses one log, added once as a private static method in
`EquivalencesTest` beside the existing `edge(...)` helper. It carries a merge whose local side is
retracted, so the emptied-canonical-id fixed point actually bites, plus an edge naming the emptied
id, so a wrong `emptied` argument changes the answer:

```java
  /**
   * A log the fixed point actually runs on: a minted local side, a merge onto it, a retraction of
   * that local side (which empties the canonical id), a re-merge onto the same canonical id, and an
   * edge naming the local id — which folds onto the emptied canonical id and is withdrawn (#224,
   * #228). An overload handed the wrong emptied set answers differently here, which is what makes
   * the comparisons below able to fail.
   */
  private static List<LoggedAssertion> foldedLog() {
    return List.of(
        new NodeAssertion(
            NEIGHBOUR,
            NodeKind.PERSON,
            "an invented neighbour",
            new Provenance("invented", "invented:1", WHEN, 1.0)),
        LocalEntity.minted(MINTED, NodeKind.WORK, "an invented local work", WHEN),
        SameAs.declared(MINTED, CANONICAL, WHEN),
        new Retraction(MINTED, "the local side was wrong", WHEN),
        SameAs.declared(MINTED, CANONICAL, WHEN),
        edge(NEIGHBOUR, MINTED));
  }
```

- [ ] **Step 1 (RED) — `in(List, Set)`.** Add to `EquivalencesTest`:

      ```java
      @Test
      @DisplayName("the merges built from a prebuilt emptied set are the ones in() builds itself")
      void shouldGiveTheSameMergesWhenHandedTheEmptiedSetInWouldCompute() {
        List<LoggedAssertion> log = foldedLog();

        assertThat(Equivalences.in(log, Equivalences.retractedStandIns(log)))
            .as(
                "in(log, emptied) exists so a caller that already paid for the fixed point does not"
                    + " pay for it again; it is the same answer or it is a second fold")
            .isEqualTo(Equivalences.in(log));
        assertThat(Equivalences.retractedStandIns(log))
            .as("and the emptied set is not empty, so the comparison above is not vacuous")
            .containsExactly(CANONICAL);
      }
      ```

      Add the overload with a body that is deliberately wrong, so the assertion — not the compiler —
      fails:

      ```java
      public static Equivalences in(List<LoggedAssertion> log, Set<String> emptied) {
        return NONE;
      }
      ```

      Run `./gradlew test --tests '*EquivalencesTest'` (blocking). **Quote the assertion failure in
      the report.** It must be the `isEqualTo` comparison, not a compile error and not the second
      assertion.

- [ ] **Step 2 (GREEN) — `in(List, Set)`.** Give the overload the real body and make the log-taking
      form delegate:

      ```java
      public static Equivalences in(List<LoggedAssertion> log) {
        Objects.requireNonNull(log, "log");
        return in(log, emptiedCanonicalIds(log));
      }

      public static Equivalences in(List<LoggedAssertion> log, Set<String> emptied) {
        Objects.requireNonNull(log, "log");
        Objects.requireNonNull(emptied, "emptied");
        return new Equivalences(mergesIn(log), referencedEndpoints(log, emptied));
      }
      ```

      Javadoc on the overload: it is `in`'s own answer for a caller that has already computed
      `retractedStandIns(log)` for **this** log; handing it any other set answers a different
      question, which is why it exists for `Fold.of` and is fenced there. Cite the test by name in a
      `{@code …}` span on one line. Run the tests, observe green, run the full gate, commit.

- [ ] **Step 3 (RED) — `folding(Equivalences, Set)`.** Test:

      ```java
      @Test
      @DisplayName("a fold built from prebuilt merges and emptied set is the one folding() builds")
      void shouldGiveTheSameFoldWhenHandedTheMergesAndEmptiedSetFoldingWouldCompute() {
        List<LoggedAssertion> log = foldedLog();
        Set<String> emptied = Equivalences.retractedStandIns(log);

        assertThat(Equivalences.folding(Equivalences.in(log, emptied), emptied))
            .as(
                "folding(merges, emptied) is where the boot's Equivalences is constructed; a"
                    + " different answer here is the two folds drifting")
            .isEqualTo(Equivalences.folding(log));
        assertThat(Equivalences.folding(log).retractedStandIns())
            .as("and the fold names a retracted stand-in, so the comparison is not vacuous")
            .containsExactly(CANONICAL);
      }
      ```

      Stub `folding(Equivalences, Set)` as `return NONE;`, run the test, quote the real assertion
      failure.

- [ ] **Step 4 (GREEN) — `folding(Equivalences, Set)`:**

      ```java
      public static Equivalences folding(List<LoggedAssertion> log) {
        Objects.requireNonNull(log, "log");
        Set<String> emptied = retractedStandIns(log);
        return folding(in(log, emptied), emptied);
      }

      public static Equivalences folding(Equivalences merges, Set<String> retractedStandIns) {
        Objects.requireNonNull(merges, "merges");
        Objects.requireNonNull(retractedStandIns, "retractedStandIns");
        return new Equivalences(
            merges.canonicalByLocal(), merges.referencedEndpoints(), retractedStandIns);
      }
      ```

      Note in the report that `folding(List)` now pays the fixed point **once** rather than twice,
      which is a saving every caller of it gets for free. Javadoc: this stays the one construction
      site for a fold's `Equivalences`, for the reason the existing `folding` javadoc gives — an
      overload that quietly gives a fold the edge-blind answer is how the two folds drift. Gate,
      commit.

- [ ] **Step 5 (RED) — `standIns(List, UnaryOperator, Equivalences)`.** Test:

      ```java
      @Test
      @DisplayName("stand-ins built from prebuilt merges are the ones standIns() builds itself")
      void shouldGiveTheSameStandInsWhenHandedTheMergesStandInsWouldCompute() {
        List<LoggedAssertion> log = foldedLog();

        assertThat(Equivalences.standIns(log, AS_CLAIMED, Equivalences.in(log)))
            .as(
                "standIns opens with Equivalences.in(log); handing it the same merges must not"
                    + " change which canonical ids get a node or what those nodes say")
            .isEqualTo(Equivalences.standIns(log, AS_CLAIMED));
      }
      ```

      Stub the overload as `return Map.of();`, run, quote the failure. **If the log-taking form also
      returns an empty map on this fixture the test is vacuous** — check, and if so extend
      `foldedLog()` with a second merge whose local side survives so a stand-in really is built,
      then re-observe the red.

- [ ] **Step 6 (GREEN) — `standIns(List, UnaryOperator, Equivalences)`.** Move today's body into the
      three-argument form unchanged except that `Equivalences merges` becomes the parameter, and:

      ```java
      public static Map<String, NodeRecord> standIns(
          List<LoggedAssertion> log, UnaryOperator<NodeAssertion> rederive) {
        return standIns(log, rederive, Equivalences.in(log));
      }
      ```

      Keep the existing javadoc on the two-argument form and give the three-argument form its own
      short one saying whose merges it expects. Gate, commit.

- [ ] **Step 7 (RED) — `nodesTheFoldHolds(List, Set)`.** Test:

      ```java
      @Test
      @DisplayName("the nodes the fold holds are the same when the stand-in ids are handed in")
      void shouldNameTheSameNodesWhenHandedTheStandInIdsItWouldCompute() {
        List<LoggedAssertion> log = foldedLog();

        assertThat(
                Equivalences.nodesTheFoldHolds(
                    log, Equivalences.standIns(log, AS_CLAIMED).keySet()))
            .as(
                "nodesTheFoldHolds(log) computes that key set itself; the overload lets a caller"
                    + " that already has it skip a second standIns walk, and must answer the same")
            .isEqualTo(Equivalences.nodesTheFoldHolds(log));
        assertThat(Equivalences.nodesTheFoldHolds(log))
            .as("and it names something, so the comparison above is not comparing two empty sets")
            .isNotEmpty();
      }
      ```

      Stub as `return Set.of();`, run, quote the failure.

- [ ] **Step 8 (GREEN) — `nodesTheFoldHolds(List, Set)`:**

      ```java
      public static Set<String> nodesTheFoldHolds(List<LoggedAssertion> log) {
        Objects.requireNonNull(log, "log");
        return nodesTheFoldHolds(log, standIns(log, UnaryOperator.identity()).keySet());
      }

      public static Set<String> nodesTheFoldHolds(
          List<LoggedAssertion> log, Set<String> standInIds) {
        Objects.requireNonNull(log, "log");
        Objects.requireNonNull(standInIds, "standInIds");
        return Collections.unmodifiableSet(nodesHeld(log, standInIds));
      }
      ```

      Its javadoc must say why a stand-in key set built under the *real* re-derivation is the same
      set the log-taking form computes under `identity()`, and cite the existing test that pins it —
      on one line: `{@code EquivalencesTest.shouldNameTheSameCanonicalIdsWhateverKindTheFoldDerives}`.
      Gate, commit.

---

### Task 3: `Fold`, the value the boot builds once

**Files:**
- Create: `src/main/java/com/robsartin/segue/domain/Fold.java`
- Create: `src/test/java/com/robsartin/segue/domain/FoldTest.java`
- Read only: `src/main/java/com/robsartin/segue/domain/Equivalences.java`,
  `src/main/java/com/robsartin/segue/domain/Retractions.java`,
  `src/test/java/com/robsartin/segue/domain/EquivalencesTest.java`

**Interfaces:**
- Produces: `Fold`, `Fold.of(List<LoggedAssertion>, UnaryOperator<NodeAssertion>)`, and the four
  accessors `retractions()`, `equivalences()`, `standIns()`, `nodesHeld()`. Consumed by
  `GraphProjector` in Task 4 and by nothing else.
- Consumes: the four overloads from Task 2, plus `Retractions.in`.

- [ ] **Step 1 (RED) — the agreement test.** Create `FoldTest` with the same constants and the same
      `foldedLog()` fixture shape `EquivalencesTest` uses (invented ids, leading zeros), and:

      ```java
      @Test
      @DisplayName("one Fold answers exactly what the four log-taking rules answer separately")
      void shouldAnswerWhatEveryLogTakingRuleAnswersWhenOneFoldIsBuilt() {
        List<LoggedAssertion> log = foldedLog();

        Fold fold = Fold.of(log, AS_CLAIMED);

        assertThat(fold.equivalences()).isEqualTo(Equivalences.folding(log));
        assertThat(fold.standIns()).isEqualTo(Equivalences.standIns(log, AS_CLAIMED));
        assertThat(fold.nodesHeld()).isEqualTo(Equivalences.nodesTheFoldHolds(log));
        assertThat(fold.retractions()).isEqualTo(Retractions.in(log));
      }
      ```

      plus a second test that the re-derivation reaches the stand-ins — the one thing an
      `identity()`-only test cannot see:

      ```java
      @Test
      @DisplayName("the stand-in a Fold carries takes the kind the caller's re-derivation gives")
      void shouldCarryTheRederivedKindWhenTheFoldIsBuiltWithARederivation() {
        List<LoggedAssertion> log = claimedLocalSideLog();

        assertThat(Fold.of(log, claim -> claim.withKind(NodeKind.PERSON)).standIns().get(CANONICAL))
            .as("the operator is required for localsOfMerges' reason (#222); if it were ignored a"
                + " third fold would arrive with the kind lag and nothing would say so")
            .isNotEqualTo(Fold.of(log, AS_CLAIMED).standIns().get(CANONICAL));
      }
      ```

      where `claimedLocalSideLog()` is a `NodeAssertion` on a local-shaped id plus a merge, as
      `EquivalencesTest.shouldNameTheSameCanonicalIdsWhateverKindTheFoldDerives` builds it —
      a `LocalEntity` local side would make the two agree by construction and this control could
      never fire.

      Create `Fold` with a body that returns a fold built from the *wrong* pieces, so the assertions
      fail rather than the compiler:

      ```java
      public static Fold of(List<LoggedAssertion> log, UnaryOperator<NodeAssertion> rederive) {
        return new Fold(new Retractions(Map.of()), Equivalences.NONE, Map.of(), Set.of());
      }
      ```

      Run `./gradlew test --tests '*FoldTest'` (blocking). **Quote both assertion failures.**

- [ ] **Step 2 (GREEN) — the real `Fold`.** Give it the record components, a compact constructor
      that null-checks and defensively copies the two collections the way `Equivalences`' does, and:

      ```java
      public static Fold of(List<LoggedAssertion> log, UnaryOperator<NodeAssertion> rederive) {
        Objects.requireNonNull(log, "log");
        Objects.requireNonNull(rederive, "rederive");
        Set<String> emptied = Equivalences.retractedStandIns(log);
        Equivalences merges = Equivalences.in(log, emptied);
        Map<String, NodeRecord> standIns = Equivalences.standIns(log, rederive, merges);
        return new Fold(
            Retractions.in(log),
            Equivalences.folding(merges, emptied),
            standIns,
            Equivalences.nodesTheFoldHolds(log, standIns.keySet()));
      }
      ```

      Class javadoc must say: what it is (one log's fold, computed once, carried); that it decides
      **nothing** — `Equivalences` and `Retractions` own every rule and this holds their answers, so
      there is no second home for a rule to drift into; that `rederive` is required rather than
      defaulted, for `localsOfMerges`' stated reason; and that the emptied set is computed once and
      threaded, which is where the saving comes from. It cites `ArchitectureTest.theBootFoldsOnce`
      as what keeps the boot going through it, and ADR 64 for the measured figures — **the number
      itself lives in the ADR and is not restated here.** (Task 7 adds that ADR citation; leave a
      sentence saying the figures are in ADR 64 and let Task 7 make the link real.)

      `domain` may name `domain`, `java` and `javax` and nothing else
      (`ArchitectureTest.domainHasNoThirdPartyDependencies`) — `Fold` names none of the rest.
      `ArchitectureTest.domainValueTypesAreRecordsOrEnums` requires a record; `Fold` is one.

- [ ] **Step 3 — gate and commit.** Full gate, blocking. Stage
      `src/main/java/com/robsartin/segue/domain/Fold.java` and
      `src/test/java/com/robsartin/segue/domain/FoldTest.java` by explicit path. Commit.

---

### Task 4: migrate `GraphProjector.project` onto `Fold`, one call site at a time

**Four steps, four commits, the full gate green after each.** This is the Mikado leaf: nothing here
changes an answer, and after each step the boot still works — during steps 1–3 it does *more* work
than before, not less, because `Fold.of` runs beside the statics it is replacing. That is the price
of green at every commit and it is deliberate.

**Files:**
- Modify: `src/main/java/com/robsartin/segue/ingest/GraphProjector.java`
- Read only: `src/test/java/com/robsartin/segue/ingest/GraphProjectorTest.java`,
  `src/test/java/com/robsartin/segue/export/BothFoldsAgreeTest.java`,
  `src/main/java/com/robsartin/segue/domain/Fold.java`

**Interfaces:** consumes `Fold.of` and the four accessors. Produces nothing new. `project`'s
signature and return value are unchanged; `refuseRowsNamingAnEntityNoNodeStandsFor` is private and
its parameter list changes in step 4.

**Why there is no new test here.** Every step is a substitution of one expression for an equal one,
and the equality is what Task 3's `FoldTest` asserts. The existing suite is the regression net and
it is a real one: `GraphProjectorTest`, `BothFoldsAgreeTest` (including the applied count of 30),
`MergeAfterARetractionTest`, `OwnerClaimProjectionTest` and `MergeCarriesEverythingTest` all replay
through `project`. If any of them reds, the substitution was not equal — **do not adjust the test**,
report it.

- [ ] **Step 1 — build the `Fold` and take the retractions from it.** After `log.readAll()` add
      `Fold fold = Fold.of(assertions, KindMapper::rederive);`, delete the
      `Retractions retractions = Retractions.in(assertions);` line, and replace both uses of
      `retractions` (the replay loop at :110 and the pre-flight's own `retractions.survives`) — the
      pre-flight keeps taking a `Retractions` parameter for now, passed as `fold.retractions()`.
      Remove the now-unused `Retractions` import if nothing else in the file names it. Gate. Commit.

- [ ] **Step 2 — take the equivalences from it.** Replace
      `Equivalences equivalences = Equivalences.folding(assertions);` with a use of
      `fold.equivalences()` at the two sites that read it (the pre-flight argument and
      `IngestService.apply` at :114). Keep the comment above the deleted line — its explanation of
      why `folding()` rather than `in()` is still the reason `Fold.of` calls `folding`; move it, do
      not delete it, and add that the fold is now built once. Gate. Commit.

- [ ] **Step 3 — take the stand-ins from it.** Replace
      `for (NodeRecord standIn : Equivalences.standIns(assertions, KindMapper::rederive).values())`
      with `for (NodeRecord standIn : fold.standIns().values())`. The comment block above it stays
      (it explains why the stand-ins are created before replay begins, which is unchanged). Gate.
      Commit.

- [ ] **Step 4 — take the held set from it, and collapse the pre-flight's parameters.** Change
      `refuseRowsNamingAnEntityNoNodeStandsFor(List<LoggedAssertion>, Retractions, Equivalences)` to
      `refuseRowsNamingAnEntityNoNodeStandsFor(List<LoggedAssertion>, Fold)`, replace
      `Set<String> held = Equivalences.nodesTheFoldHolds(assertions);` with
      `Set<String> held = fold.nodesHeld();`, and read `fold.retractions()` and
      `fold.equivalences()` inside it. Remove the `Equivalences` import if the file no longer names
      the type — check first: the class javadoc uses `{@link Equivalences#nodesTheFoldHolds}` and
      `{@link Equivalences#standIns}`, and **javadoc `@link` targets need the import or a
      fully-qualified name**, so keep the import if those links stay (they should: they still
      explain the rules, now reached through `Fold`). Update the class javadoc's `<p><b>One family
      of failure is refused before the loop begins</b>` paragraph to say the held set comes from the
      `Fold`. Gate. Commit.

- [ ] **Step 5 — confirm the boot calls nothing.** `grep -n 'Equivalences\.\|Retractions\.'
      src/main/java/com/robsartin/segue/ingest/GraphProjector.java` must show **no call** — only
      javadoc and comment mentions. Quote the grep output in the report.

---

### Task 5: the fence, with its positive control

**Files:**
- Modify: `src/test/java/com/robsartin/segue/arch/ArchitectureTest.java`
- Modify: `docs/developer-guide.md` (one row in the "Which rules a machine enforces" table)
- Modify (plant only, reverted): `src/main/java/com/robsartin/segue/ingest/GraphProjector.java`

**Interfaces:** produces `ArchitectureTest.theBootFoldsOnce`. Consumes the existing private
`callTo(String, Class)` helper in that file.

- [ ] **Step 1 — the rule.** Add to `ArchitectureTest`, importing
      `com.robsartin.segue.domain.Equivalences`, `com.robsartin.segue.domain.Retractions` and
      `com.robsartin.segue.ingest.GraphProjector`:

      ```java
      @ArchTest
      static final ArchRule theBootFoldsOnce =
          noClasses()
              .that()
              .belongToAnyOf(GraphProjector.class)
              .should()
              .accessTargetWhere(
                  callTo("in", Equivalences.class)
                      .or(callTo("folding", Equivalences.class))
                      .or(callTo("standIns", Equivalences.class))
                      .or(callTo("nodesTheFoldHolds", Equivalences.class))
                      .or(callTo("retractedStandIns", Equivalences.class))
                      .or(callTo("localsOfMerges", Equivalences.class))
                      .or(callTo("in", Retractions.class)))
              .because(
                  "issue #238: the boot derives the fold once, through Fold.of, and every reader"
                      + " takes what it holds — a second log-taking call here is a second fold");
      ```

      Its javadoc must say: **one class, not the `ingest` package**, because `IngestService.claim`
      calls `nodesTheFoldHolds` and `folding` on the live path where there is no boot and no fold to
      reuse (`IngestService.java:218`, `:233`, `:234`) — a package fence would forbid the gate #233
      added. And why `Retractions.in` is in the list although the issue names only the `Equivalences`
      six: `Fold` carries the index, so after the migration the boot does not call it, and leaving it
      out would let one whole-log walk come back with the fence green. Also: this is a fence rather
      than an invocation counter, for the reason the spec gives.

- [ ] **Step 2 — run it clean.** `./gradlew test --tests '*ArchitectureTest'` (blocking). It must
      pass. Record that.

- [ ] **Step 3 (the positive control) — plant.** In `GraphProjector.project`, immediately after
      `Fold fold = Fold.of(assertions, KindMapper::rederive);`, add the single line:

      ```java
      Equivalences.in(assertions);
      ```

      (a bare invocation statement — valid Java, no unused local, so nothing but the fence objects).
      Re-add the `Equivalences` import if step 4 of Task 4 removed it.

- [ ] **Step 4 (the positive control) — observe the red.** Run
      `./gradlew test --tests '*ArchitectureTest'` (blocking). It must fail on `theBootFoldsOnce`,
      naming the method and the line, in the shape:

      ```
      Architecture Violation [Priority: MEDIUM] - Rule 'no classes that belong to any of
      [GraphProjector] should access target where a call or a method reference to Equivalences.in
      or ...' was violated (1 times):
      Method <com.robsartin.segue.ingest.GraphProjector.project(...)> calls method
      <com.robsartin.segue.domain.Equivalences.in(java.util.List)> in (GraphProjector.java:NN)
      ```

      **Quote the actual output in the report**, not this sketch. If it does not fail, the rule is
      vacuous — most likely `belongToAnyOf` matched nothing or `callTo`'s owner predicate missed —
      fix the rule, not the plant. A compile error is not a red; if the plant does not compile, fix
      the plant.

- [ ] **Step 5 — remove the plant.** Delete the planted line (and the import if it was re-added),
      run `git diff --stat src/main/java/com/robsartin/segue/ingest/GraphProjector.java` and confirm
      it is **empty**. Re-run `./gradlew test --tests '*ArchitectureTest'` and confirm green.

- [ ] **Step 6 — the guide row.** Add one row to the ArchUnit table in `docs/developer-guide.md`,
      in the same style as its neighbours:

      | `theBootFoldsOnce` | `GraphProjector` calling `Equivalences.in`, `folding`, `standIns`, `nodesTheFoldHolds`, `retractedStandIns` or `localsOfMerges`, or `Retractions.in` — the boot builds one `Fold` and every reader takes what it holds. One class rather than the `ingest` package: `IngestService.claim` folds on the live path, where there is no boot fold to reuse | issue [#238](https://github.com/robsartin/segue/issues/238) |

- [ ] **Step 7 — gate and commit.** Full gate, blocking (this is what runs
      `DeveloperGuideEnumerationsTest` against the new row). Stage the two files by explicit path.
      Commit.

---

### Task 6: the benchmark

**Files:**
- Create: `src/test/java/com/robsartin/segue/ingest/FoldOnceBenchmark.java`
- Read only: `src/test/java/com/robsartin/segue/ingest/GraphProjectorTest.java`,
  `src/main/java/com/robsartin/segue/sqlite/SqliteAssertionLog.java`,
  `src/test/java/com/robsartin/segue/arch/StandInQidsDenoteNothingTest.java`

**Interfaces:** produces nothing `src/main` consumes. Consumes `SqliteAssertionLog`,
`TinkerGraphStore`, `GraphProjector.project`, `IdentityMerge.NONE`.

**The shape, and why it is this shape.** An `@EnabledIfEnvironmentVariable` class, not `@Disabled`
(which `--tests` cannot run) and not a new Gradle task (a build change for one number). It compiles
under `./gradlew check` so it cannot rot, and it is skipped there so the gate does not spend minutes
folding 318k rows. **No wall-clock assertion**: the machine is loaded and a timing assertion would
be a flake generator. It asserts only that the fixture is the size it asked for and that the replay
applied a non-zero count, and it prints the elapsed milliseconds.

- [ ] **Step 1 (RED) — the fixture generator.** Write the class with
      `@EnabledIfEnvironmentVariable(named = "SEGUE_BENCHMARK", matches = "true")`, a `@TempDir Path
      tmp` field, and first a test of the generator alone that runs at a small size:

      ```java
      @Test
      @DisplayName("the generator writes exactly the number of rows it was asked for")
      void shouldWriteEveryRowWhenTheGeneratorIsAskedForALogOfAGivenSize() throws Exception {
        try (AssertionLog log = new SqliteAssertionLog(tmp.resolve("bench.db"))) {
          generate(log, 1_000);

          assertThat(log.readAll()).hasSize(1_000);
        }
      }
      ```

      with `generate` stubbed to append nothing. Run
      `SEGUE_BENCHMARK=true ./gradlew test --tests '*FoldOnceBenchmark'` (blocking) and **quote the
      assertion failure** (expected `0` against `1000`). The file-taking form is the public constructor
      `new SqliteAssertionLog(Path)` — it creates the file and the schema if absent, so the
      `@TempDir` path need not exist. (`GraphProjectorTest` uses `SqliteAssertionLog.inMemory()`;
      this benchmark wants the file, because a real boot reads one.)

- [ ] **Step 2 (GREEN) — the generator.** Implement it: a run of `NodeAssertion`s over invented
      qids and `AssertionRecord`s between them, in roughly the proportion a real log has (most rows
      are edges), plus a handful of merges and one retraction so the fixed point runs on something.
      **Every generated id carries a leading zero** — build them as `"Q0" + (1_000_000 + i)`, never
      `"Q" + i`, which mints ids Wikidata could allocate and which
      `StandInQidsDenoteNothingTest` cannot see (its own javadoc records that blind spot). Merge
      canonical sides take the eleven-digit shape (ADR 62). Every label is invented. Run, observe
      green.

- [ ] **Step 3 — the timing.** Add the benchmark proper:

      ```java
      @Test
      @DisplayName("a boot replay of a log at the real log's scale is timed, and nothing is asserted about the clock")
      void shouldReportTheElapsedTimeWhenABootReplaysALogAtTheRealScale() throws Exception {
        try (AssertionLog log = new SqliteAssertionLog(tmp.resolve("bench.db"))) {
          generate(log, 318_116);

          try (TinkerGraphStore store = new TinkerGraphStore()) {
            long start = System.nanoTime();
            long applied = GraphProjector.project(log, store, IdentityMerge.NONE);
            long millis = (System.nanoTime() - start) / 1_000_000;

            assertThat(applied)
                .as("a replay that applied nothing would be timing an empty list")
                .isPositive();
            LOG.info("fold-once benchmark: replayed {} rows in {} ms", applied, millis);
          }
        }
      }
      ```

      Use the project's SLF4J logger (`nothingWritesToStandardOut` bans `System.out` in `src/main`
      only, but stay consistent). 318,116 is the published aggregate from ADR 57 — an aggregate is
      publishable under ADR 51; a name would not be.

- [ ] **Step 4 — run it once and gate.** Run `SEGUE_BENCHMARK=true ./gradlew test --tests
      '*FoldOnceBenchmark'` blocking; report the elapsed figure and the generator's own setup time
      separately. Then run the **full gate without** `SEGUE_BENCHMARK` and confirm the class is
      **skipped** — quote the test report line showing it. Commit.

---

### Task 7: the measurement, ADR 64, and the two documents it corrects

**Files:**
- Create: `docs/adr/0064-fold-the-log-once-per-boot.md`
- Modify: `docs/adr/README.md` (one row, in the section ADR 63 is in — `## Uncategorized` — after
  the ADR 63 row)
- Modify: `src/main/java/com/robsartin/segue/domain/Equivalences.java` (the sentence that became
  false, plus the ADR citation)
- Modify: `src/main/java/com/robsartin/segue/domain/Fold.java` (make the ADR 64 citation real)
- Modify: `docs/developer-guide.md` (one new sub-section)
- Modify (plant only, reverted): `src/main/java/com/robsartin/segue/ingest/GraphProjector.java`

- [ ] **Step 1 — measure AFTER.** On `HEAD`, run `SEGUE_BENCHMARK=true ./gradlew test --tests
      '*FoldOnceBenchmark'` blocking, three times. Record all three elapsed figures.

- [ ] **Step 2 — measure BEFORE, by plant.** Temporarily restore the pre-migration body of
      `GraphProjector.project` — replace the `Fold` lines with exactly what `787ecdc` had:

      ```java
      Retractions retractions = Retractions.in(assertions);
      Equivalences equivalences = Equivalences.folding(assertions);
      refuseRowsNamingAnEntityNoNodeStandsFor(assertions, retractions, equivalences);
      for (NodeRecord standIn : Equivalences.standIns(assertions, KindMapper::rederive).values()) {
        store.upsertNode(standIn);
      }
      ```

      restoring the pre-flight's three-parameter signature and its
      `Set<String> held = Equivalences.nodesTheFoldHolds(assertions);`. Run the benchmark three
      times again, the same way. **`ArchitectureTest` is not run by `--tests '*FoldOnceBenchmark'`,
      so `theBootFoldsOnce` does not fire during this step — that is expected, not a bug.** Record
      the three figures.

- [ ] **Step 3 — revert the plant.** Restore the file:
      `git checkout -- src/main/java/com/robsartin/segue/ingest/GraphProjector.java`. Confirm
      `git diff --stat` for that path is empty. Run the full gate, blocking, and confirm green —
      including `theBootFoldsOnce`.

- [ ] **Step 4 — ADR 64.** Write `docs/adr/0064-fold-the-log-once-per-boot.md` with the same front
      matter shape as `0063-a-read-only-census-of-the-graph.md` (`status: Accepted`, `date:
      "2026-09-04"`, `topic: fold-the-log-once-per-boot`, tags, `supersedes: []`, `related:` naming
      `assertion-log-source-of-truth`, `retraction-as-a-new-claim`, `owner-claims-as-a-third-layer`,
      `store-p31-and-rederive-kind-at-projection`, `mikado-method-for-changes`,
      `use-test-driven-development`, `layering-and-archunit`), heading
      `# 64. Fold the assertion log once per boot, and hand it to every reader`, and sections
      **Context**, **Decision**, **Alternatives considered**, **Consequences**.

      Content, from the spec — do not restate the code:
      - **Context:** the measured call table (four log walks, two fixed points inside `folding`
        alone), the issue's own instrumented counts, and the fact that
        `Equivalences.java`'s own javadoc already admitted it.
      - **Decision:** `Fold` in `domain` as a carrier that decides nothing; the four overloads; the
        emptied set computed once and threaded; the ArchUnit fence rather than an invocation
        counter; the boot only, tools unchanged.
      - **Alternatives considered:** one paragraph each, with the reason it lost — leave it (the
        cost grows with the log, and ADR 44's 2026-09-04 amendment already prices a fold at this
        scale); cache across boots or keep an on-disk projection (ADR 24 rebuilds from the log and
        that decision stands; derived state that can disagree with the log is the failure the
        append-only design exists to prevent); widen every reader's signature (YAGNI — the tools run
        once and exit, the boot runs on every start); make `Fold` compute rather than carry (a
        second home for a rule is the drift `BothFoldsAgreeTest` exists to catch); a static
        invocation counter (a test-only mutable static in the layer ADR 18 keeps dependency-free,
        and a reader that folds without incrementing passes it); change the fixed point (explicitly
        not this issue).
      - **Consequences:** **the dated figures, once, here and nowhere else** — "Measured
        2026-09-04, on a synthetic 318,116-row log generated into a temporary directory by
        `FoldOnceBenchmark`: `GraphProjector.project` took X ms before and Y ms after (best of three
        each)." Say what the number does and does not cover: it is one machine on one day, it
        includes the replay loop's store writes (which this change does not touch), and it is a
        floor on the saving rather than a ceiling, because the synthetic log is shallow in merges
        where the owner's real log may not be. Also record the residual from the spec:
        `Retractions.in` is still re-derived inside each `Equivalences` static, which this issue does
        not address. And note that `folding(List)` now pays the fixed point once rather than twice,
        so every dev tool that calls it gets that half for free even though none was migrated.

- [ ] **Step 5 — the ADR index row.** Add to `docs/adr/README.md`, in `## Uncategorized`, after the
      ADR 63 row and in the exact three-line shape its neighbours use (row, indented description,
      indented `Related:` line).

- [ ] **Step 6 — the javadoc sentence that became false.** In
      `src/main/java/com/robsartin/segue/domain/Equivalences.java`, in `emptiedCanonicalIds`'
      javadoc, replace:

      > That count is per <em>invocation</em> of this method, not per boot: {@code
      > GraphProjector.project} invokes the fold - and so this loop - more than once while replaying
      > a single log, so the rounds are paid again each time rather than once per boot.

      with a sentence saying the count is still per invocation of this method, that **since #238 a
      boot invokes it once** — `GraphProjector.project` builds one `Fold` and every reader takes
      what it holds — and that the dev tools still fold per run and pay the rounds again each time.
      Do not delete the surrounding paragraph; only that sentence became false.

- [ ] **Step 7 — make `Fold`'s ADR citation real.** In `Fold`'s class javadoc, cite ADR 64 for the
      measured figures. **Do not restate the number** — one home per figure. Check the sentence does
      not wrap a `{@code …}` citation at a dot.

- [ ] **Step 8 — the developer-guide paragraph.** In `docs/developer-guide.md`, in the chapter
      "The log is the truth; the graph is a projection", add a short sub-section after
      "### Replay shares the apply step":

      `### The boot folds the log once` — one paragraph: the boot used to derive the fold four times
      from one row list, `GraphProjector.project` now builds a single `Fold` (in `domain`, beside
      `Equivalences` and `Retractions`, a carrier that decides nothing) and hands it to the
      pre-flight, the stand-in seeding and the replay loop; the dev tools still fold per run and are
      deliberately out of scope; `ArchitectureTest.theBootFoldsOnce` is what stops a second fold
      arriving. Link ADR 64 as `[64](adr/0064-fold-the-log-once-per-boot.md)`. **No figures** — cite
      the ADR for those.

- [ ] **Step 9 — gate and commit.** Full gate, blocking (it runs `AdrIndexTest`,
      `DocumentationLinksTest`, `DeveloperGuideEnumerationsTest` and `javadoc -Werror`). Stage every
      file by explicit path. Commit.

---

## Done when

- `GraphProjector` calls no log-taking static — proven by grep and by `theBootFoldsOnce`, which was
  seen red under a plant and green with it removed.
- `BothFoldsAgreeTest`'s applied count is still 30 and `StandInAgreesInEveryHomeTest` is unedited.
- Every dev tool is byte-identical to `787ecdc`.
- ADR 64 holds one dated before/after figure, and nothing else in the repository restates it.
- The full gate is green on the final commit.
