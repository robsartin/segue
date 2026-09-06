# The harness reads every fold of the split — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to
> implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `./gradlew evaluate` reads every fold of its deterministic split instead of one — every
eligible entity held out exactly once, the known-list each fold sweeps from the size it is today —
and prints the same sixteen rows with counts totalled over the folds and means taken over every hit
in the run.

**Architecture:** five folds where there was one, and the arithmetic that makes sixteen summed rows
exact. `HeldOut.every` gains an offset (task 1). `Reading` carries integer rank *sums* instead of
`OptionalDouble` means, so folds add without rounding twice, and the report divides once (task 2).
`Reading.summed` is the pure function that adds one reading per fold (task 3). `EvaluateRun.run`
loops the folds and the header states them — one coupled commit, because either half alone puts a
false sentence in the report (task 4). Then the ADR amendment and the guide (task 5), and the
hand-back to the owner (task 6).

**Tech Stack:** Java (toolchain 25, `release 21`), JUnit 5, AssertJ, ArchUnit, JaCoCo. Markdown for
the ADR amendment and the developer guide.

**Spec:** `docs/superpowers/specs/2026-09-06-every-fold-design.md` — it holds the design decisions,
the three ways a mean could have been formed and why two lost, and the alternatives rejected.
**Cite it; never restate its reasoning.** Where this plan and the spec appear to differ, the spec
wins and the divergence is a finding to report.

---

## Global Constraints

- **Pure TDD, every step.** Failing test first, **run it and observe a real assertion failure** — a
  compile error is not a red. Where a step's failure would be a compile error, this plan says which
  stub to write so the failure is an assertion instead, and every step that expects a red names the
  message it expects. **Quote what the failure actually said** in the step report; if it said
  something else, stop and report rather than proceeding.
- **Every guard gets a planted control.** Remove or weaken the guard, run its test, see it fire,
  restore, see it pass. The plan writes these out as steps; do not skip one because the guard "is
  obviously right".
- Test names `should<Expected>When<Condition>` with `@DisplayName`.
- **Mikado: green at every committed step.** Nothing is committed with a red gate.
- **The OWNER runs `evaluate`; the implementer never does.** `~/.segue/segue.db` is never read,
  written, copied or created, and no dev task is run against it. **Never run a writing dev task**
  (`own`, `ownClaim`, `retractEntity`, `rate`, or any other).
- **ADRs are append-only.** An amendment is appended to the end of the file. Front matter is not
  touched; no line above the amendment is edited, reworded or deleted. `docs/adr/README.md` is not
  touched — an amendment changes no ADR's number, title or status. **ADR 45 is not touched at all**:
  the two readings quoted in its amendments were taken by the unfolded instrument and stay exactly
  as they are.
- **Never cite a `.superpowers/` path from a committed file.**
- **Never `git add -A`.** Stage every file by explicit path, with git's stderr visible (never
  `2>/dev/null`), and read `git status` before committing. One committer in this worktree; sibling
  issues are in flight in neighbouring worktrees and nothing outside `wt-268` is touched.
- Commit messages end with a blank line then
  `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`.
- **Gate, run BLOCKING (never backgrounded), after every task that changes a file:**
  `SEGUE_REQUIRE_BROWSER=true SEGUE_REQUIRE_GRAPHVIZ=true ./gradlew check --rerun-tasks`
  Plain `./gradlew`; JDK 25 is the only JDK. If `spotlessCheck` fails run `./gradlew spotlessApply`
  and re-run the gate. `check` includes `javadoc` with `-Werror`, so a missing `@param` or a broken
  `{@link}` fails it.
- **The fast loop between steps** is `./gradlew test --tests 'com.robsartin.segue.evaluate.*'`; the
  gate is what a commit rests on.
- **Invented identifiers only** in anything committed (ADR 58, ADR 51): every stand-in qid carries
  the leading zero, and nothing derived from the owner's data appears anywhere.
- **No wall-clock assertion, anywhere.** The run gets five times longer; that is recorded in prose
  and never asserted.
- YAGNI: no parameter, overload or helper this plan does not need.

---

### Task 1: `HeldOut.every` takes an offset, and the folds partition the population

**Files:** `src/main/java/com/robsartin/segue/evaluate/HeldOut.java`,
`src/test/java/com/robsartin/segue/evaluate/HeldOutTest.java`,
`src/main/java/com/robsartin/segue/evaluate/EvaluateRun.java` (call site only).

- [ ] **Step 1 — write the tests.** In `HeldOutTest`, add these imports if absent
      (`java.util.ArrayList`, `java.util.List`), add the shared fixture, add the three new tests, and
      change the four existing `HeldOut.every(...)` calls to pass the new offset `0` as the second
      argument. The fixture replaces the map built inline in
      `shouldHoldOutEveryFifthByQidOrderWhenThePopulationIsEligible`, which now calls it:

```java
  /**
   * Ten eligible entities, deliberately inserted out of order: the split must read qid order, not
   * map order.
   */
  private static final List<String> TEN =
      List.of(
          "Q0900406",
          "Q0900401",
          "Q0900409",
          "Q0900403",
          "Q0900407",
          "Q0900402",
          "Q0900410",
          "Q0900405",
          "Q0900408",
          "Q0900404");

  private static Map<String, Integer> tenEligible() {
    Map<String, Integer> ratings = new LinkedHashMap<>();
    for (String qid : TEN) {
      ratings.put(qid, KnownList.PROMOTION_RATING);
    }
    return ratings;
  }
```

```java
  @Test
  @DisplayName("fold one holds out the entities fold zero stepped over")
  void shouldHoldOutTheNextEntitiesWhenTheOffsetIsOne() {
    HeldOut split = HeldOut.every(HeldOut.EVERY, 1, tenEligible(), NOTHING_ON_FILE, ANYTHING);

    assertThat(split.heldOut())
        .as("indices 1 and 6 of ten eligible entities sorted ascending")
        .containsExactly("Q0900402", "Q0900407");
    assertThat(split.eligible())
        .as("the offset does not enter the eligibility rule")
        .isEqualTo(10);
    assertThat(split.ratingsWithout())
        .as("this fold's entities are gone and fold zero's are not")
        .hasSize(8)
        .doesNotContainKeys("Q0900402", "Q0900407")
        .containsEntry("Q0900401", KnownList.PROMOTION_RATING);
  }

  @Test
  @DisplayName("every offset of one interval is a fold, and the folds partition the population")
  void shouldPartitionTheEligiblePopulationWhenEveryOffsetIsTaken() {
    List<String> overEveryFold = new ArrayList<>();

    for (int fold = 0; fold < HeldOut.EVERY; fold++) {
      HeldOut split = HeldOut.every(HeldOut.EVERY, fold, tenEligible(), NOTHING_ON_FILE, ANYTHING);
      assertThat(split.eligible())
          .as("fold %d: every fold shares one denominator, which is what lets the rows sum", fold)
          .isEqualTo(10);
      overEveryFold.addAll(split.heldOut());
    }

    assertThat(overEveryFold)
        .as("each eligible entity is held out in exactly one fold, and every one of them is")
        .doesNotHaveDuplicates()
        .containsExactlyInAnyOrderElementsOf(TEN);
  }

  @Test
  @DisplayName("an offset that is not one of the interval's folds is refused")
  void shouldRefuseTheOffsetWhenItIsNotOneOfTheFolds() {
    assertThatThrownBy(
            () ->
                HeldOut.every(
                    HeldOut.EVERY, HeldOut.EVERY, tenEligible(), NOTHING_ON_FILE, ANYTHING))
        .as("fold 5 of a 5-fold split does not exist; it would duplicate fold zero")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("fold 5");
    assertThatThrownBy(
            () -> HeldOut.every(HeldOut.EVERY, -1, tenEligible(), NOTHING_ON_FILE, ANYTHING))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("fold -1");
  }
```

- [ ] **Step 2 — make the failure an assertion, not a compile error.** Give `every` the offset
      parameter and **ignore it**, so everything compiles and answers fold zero for every fold. In
      `HeldOut.java`, change the signature and add the `@param` (javadoc `-Werror` needs it), leaving
      the body exactly as it is:

```java
  /**
   * Split one ratings map.
   *
   * @param interval hold out every {@code interval}-th eligible entity
   * @param offset which fold: hold out positions {@code offset, offset + interval, …}. Ignored —
   *     task 1, step 2. Step 3 makes it count.
   * @param ratings the note-free bulk read, already resolved through {@code Equivalences.resolve}
   * @param onFile the qids the {@code --known} file names
   * @param couldBeOffered whether the sweep could return this entity as a candidate at all
   */
  public static HeldOut every(
      int interval,
      int offset,
      Map<String, Integer> ratings,
      Set<String> onFile,
      Predicate<String> couldBeOffered) {
```

      and in `EvaluateRun.run`, pass fold zero at the one call site:

```java
    HeldOut split =
        HeldOut.every(
            HeldOut.EVERY, 0, ratings, new LinkedHashSet<>(fromFile), sweep::couldBeExplored);
```

      Run, blocking:

```bash
./gradlew test --tests 'com.robsartin.segue.evaluate.HeldOutTest'
```

      **Expect three real assertion failures**, and quote them:
      `shouldHoldOutTheNextEntitiesWhenTheOffsetIsOne` —
      `Expecting actual: ["Q0900401", "Q0900406"] to contain exactly: ["Q0900402", "Q0900407"]`;
      `shouldPartitionTheEligiblePopulationWhenEveryOffsetIsTaken` — the collected list has ten
      elements that are five duplicated pairs, so `doesNotHaveDuplicates` fires first;
      `shouldRefuseTheOffsetWhenItIsNotOneOfTheFolds` — no exception was thrown. If any of the five
      pre-existing tests is red, stop: only the offset was meant to change.

- [ ] **Step 3 — write the implementation.** In `HeldOut.java`: replace the `offset` `@param` line
      with its real text, add the guard after the existing interval guard, and start the loop at the
      offset:

```java
   * @param offset which fold to take: hold out the eligible entities at positions {@code offset,
   *     offset + interval, …}. Fold zero is the split as it was before folding; the harness reads
   *     every fold of the interval, so each eligible entity is held out exactly once over a run
   *     (issue #268).
```

```java
    if (offset < 0 || offset >= interval) {
      throw new IllegalArgumentException(
          "fold "
              + offset
              + " is not one of the "
              + interval
              + " folds of this split: the offset is at least 0 and less than the interval, and a"
              + " fold outside that range would duplicate one inside it");
    }
```

```java
    List<String> heldOut = new ArrayList<>();
    for (int i = offset; i < eligible.size(); i += interval) {
      heldOut.add(eligible.get(i));
    }
```

      Add the folding paragraph to the class javadoc, after the "The order is the qid's" paragraph:

```java
 * <p><b>Every offset of one interval is a fold, and the harness reads all of them</b> (issue #268).
 * Fold {@code k} holds out positions {@code k, k + interval, …}, so the folds partition the eligible
 * population — each entity held out exactly once over a run — while every fold leaves a known-list
 * of the same size a single fold leaves, to within the one entity by which fold sizes differ.
 * Widening the split instead would have shrunk that known-list, which is an input to the thing being
 * measured. The eligibility rule does not read the offset, so every fold reports the same {@link
 * #eligible}.
```

- [ ] **Step 4 — verify it passes.** Run the fast loop; all eight `HeldOutTest` tests green. Quote
      the count.

- [ ] **Step 5 — planted control for the offset guard.** Comment out the `offset < 0 || offset >=
      interval` guard, run
      `./gradlew test --tests 'com.robsartin.segue.evaluate.HeldOutTest'`, and confirm
      `shouldRefuseTheOffsetWhenItIsNotOneOfTheFolds` **fails** — with the guard gone, offset 5 over
      ten entities holds out nothing and offset −1 throws
      `ArrayIndexOutOfBoundsException`/`IndexOutOfBoundsException` rather than
      `IllegalArgumentException`, and either way the assertion fires. Quote the failure. Restore the
      guard, re-run, green.

- [ ] **Step 6 — gate and commit.** Run the gate, blocking. Then:

```bash
git status --short
git add src/main/java/com/robsartin/segue/evaluate/HeldOut.java \
        src/main/java/com/robsartin/segue/evaluate/EvaluateRun.java \
        src/test/java/com/robsartin/segue/evaluate/HeldOutTest.java
git commit
```

      Message: `Give the split an offset, so every fold of it can be read (#268)`.

---

### Task 2: `Reading` carries rank sums, and the report divides once

**Files:** `Reading.java`, `Scoring.java`, `EvaluationReport.java`, `ScoringTest.java`,
`EvaluationReportTest.java`, `SuppressionIsPurelySubtractiveTest.java`.

**Why this task exists at all** is the spec's "the means, and the one thing that could drift": a
per-fold mean is a rounded double, and multiplying it back by its hit count to combine folds can move
a rendered tenth. Integers add exactly. **The rendered output does not change** — every cell renders
the same string it renders today — so this task's evidence is the tests, not a diff of the table.

- [ ] **Step 1 — write the tests against the new shape.** Three test files change.

      `ScoringTest`: replace the four mean assertions with sums.

```java
    assertThat(reading.hitRankSum()).as("one hit, at rank 2").isEqualTo(2);
```

```java
    assertThat(reading.hitRankSum())
        .as("a sum over nothing is zero, and it is the hit count beside it that prints the dash")
        .isZero();
```

```java
    assertThat(reading.hits()).isEqualTo(2);
    assertThat(reading.hitRankSum()).as("ranks 1 and 4, which the report means to 2.5").isEqualTo(5);
```

```java
    assertThat(reading.negativesOffered()).isEqualTo(1);
    assertThat(reading.negativeRankSum()).isEqualTo(3);
```

      `SuppressionIsPurelySubtractiveTest`, in
      `shouldRankTheHeldOutEntityOverTheShippedPoolWhenARatedDownEntityOutranksIt`:

```java
      assertThat(reading.hitRankSum())
          .as("one hit, at rank 2 of the two survivors — not rank 3 of the three the sweep returned")
          .isEqualTo(2);
      assertThat(reading.negativeRankSum())
          .as("the rated-down entity is still read over the whole pool — that is its whole point")
          .isEqualTo(1);
```

      `EvaluationReportTest`: drop the `java.util.OptionalDouble` import and rebuild the three
      fixtures so the rendered cells are unchanged — 30 over 4 hits is 7.5, 8 over 2 negatives is
      4.0, 111 over 12 hits is 9.25:

```java
    Reading wide = new Reading(new Setting(Scorer.RAW, 2), 123456, 40, 12, 111, 0, 0);
```

```java
  private static Reading reading() {
    return new Reading(new Setting(Scorer.LIFT, 5), 900, 40, 4, 30, 2, 8);
  }

  private static Reading sparse() {
    return new Reading(new Setting(Scorer.RAW, 12), 40, 3, 0, 0, 0, 0);
  }
```

- [ ] **Step 2 — make the failures assertions, not compile errors.** Change the record's two
      components, and make both producers answer wrongly: `Scoring` sums to zero, and the report
      renders the dash for every mean.

      `Reading.java` — components and their javadoc (drop the `java.util.OptionalDouble` import):

```java
public record Reading(
    Setting setting,
    int pool,
    int heldOutInPool,
    int hits,
    int hitRankSum,
    int negativesOffered,
    int negativeRankSum) {

  public Reading {
    Objects.requireNonNull(setting, "setting");
  }
}
```

```java
 * @param hitRankSum the sum of those 1-based ranks — a sum rather than a mean, so that folds add
 *     exactly (issue #268). The report divides it by {@link #hits} once, over every hit in the run.
 *     Zero when there are none, which is why the report reads the count and not this field to
 *     decide on the dash: no hits and a mean rank of zero are still different facts
 * @param negativeRankSum the sum of those 1-based ranks, on the same terms
```

      `Scoring.java` — replace `mean` with a stub that answers zero, and construct with it:

```java
        hitRanks.size(),
        0,
        negativeRanks.size(),
        0);
```

      Delete the `mean` method and the `java.util.OptionalDouble` import.

      `EvaluationReport.java` — the cells call a two-argument `mean` that always answers the dash
      (drop the `java.util.OptionalDouble` import):

```java
        mean(reading.hitRankSum(), reading.hits()),
        String.valueOf(reading.negativesOffered()),
        mean(reading.negativeRankSum(), reading.negativesOffered()));
  }

  /** Stub — task 2, step 2. Step 3 divides. */
  private static String mean(int rankSum, int count) {
    return NO_MEAN;
  }
```

      Run, blocking:

```bash
./gradlew test --tests 'com.robsartin.segue.evaluate.*'
```

      **Expect two real assertion failures**, and quote both:
      `ScoringTest.shouldReportTheHitAndItsRankWhenAHeldOutEntityIsRankedHighly` —
      `expected: 2 but was: 0`; and
      `EvaluationReportTest.shouldRenderADashWhenAMeanHasNothingToAverage` — the first row's cell 5
      is `-` where `7.5` was expected. `SuppressionIsPurelySubtractiveTest` fails on the sum for the
      same reason as the first.

- [ ] **Step 3 — write the implementation.** `Scoring.java`:

```java
        hitRanks.size(),
        sum(hitRanks),
        negativeRanks.size(),
        sum(negativeRanks));
  }
```

```java
  private static int sum(List<Integer> ranks) {
    return ranks.stream().mapToInt(Integer::intValue).sum();
  }
```

      and a paragraph in `Scoring`'s class javadoc, after the two-rankings paragraph:

```java
 * <p><b>It reports rank sums rather than rank means</b> (issue #268). One row of the report is a sum
 * over the folds of the split, and sums add exactly where means do not: combining per-fold means
 * would divide once per fold and multiply back, and a value a hair either side of a rounding
 * boundary would render a different tenth. {@code EvaluationReport} divides, once, over every hit in
 * the run.
```

      `EvaluationReport.java` — the real division:

```java
  /** A mean over nothing is the dash rather than zero, and the count is what says which. */
  private static String mean(int rankSum, int count) {
    return count == 0 ? NO_MEAN : String.format(Locale.ROOT, "%.1f", (double) rankSum / count);
  }
```

      and, in the class javadoc, replace the sentence "A mean over nothing is a dash rather than
      zero." paragraph's first sentence with one that says where the division happens — append to
      that paragraph:

```java
 * The division happens here and nowhere else: {@link Reading} carries the rank sum and the count, so
 * a row summed over the folds of the split is meaned over every hit in the run rather than over a
 * mean of means (issue #268).
```

- [ ] **Step 4 — verify it passes.** Run the fast loop; every `evaluate` test green. Quote the count.

- [ ] **Step 5 — gate and commit.** Gate, blocking. Then stage by explicit path:

```bash
git status --short
git add src/main/java/com/robsartin/segue/evaluate/Reading.java \
        src/main/java/com/robsartin/segue/evaluate/Scoring.java \
        src/main/java/com/robsartin/segue/evaluate/EvaluationReport.java \
        src/test/java/com/robsartin/segue/evaluate/ScoringTest.java \
        src/test/java/com/robsartin/segue/evaluate/EvaluationReportTest.java \
        src/test/java/com/robsartin/segue/evaluate/SuppressionIsPurelySubtractiveTest.java
git commit
```

      Message: `Carry the rank sums, so folds add without rounding twice (#268)`.

---

### Task 3: `Reading.summed`, the pure sum over folds

**Files:** `Reading.java`, and a new `src/test/java/com/robsartin/segue/evaluate/ReadingTest.java`.

It lands unused by production for exactly one commit; task 4 calls it. That is deliberate: the
arithmetic is proved in milliseconds, before the loop that depends on it.

- [ ] **Step 1 — write the tests.** New file:

```java
package com.robsartin.segue.evaluate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.robsartin.segue.domain.Scorer;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * One row of the folded report is one reading per fold, added. Every number here is invented and
 * nothing reads a graph or a store (ADR 33, issue #37).
 */
class ReadingTest {

  private static final Setting SETTING = new Setting(Scorer.LIFT, 5);

  @Test
  @DisplayName("every count and every rank sum adds, and the setting is carried through")
  void shouldAddEveryFieldWhenTheFoldsOfOneSettingAreSummed() {
    Reading first = new Reading(SETTING, 900, 40, 4, 30, 2, 8);
    Reading second = new Reading(SETTING, 880, 38, 3, 21, 1, 5);
    Reading third = new Reading(SETTING, 870, 37, 0, 0, 0, 0);

    Reading summed = Reading.summed(List.of(first, second, third));

    assertThat(summed.setting()).isEqualTo(SETTING);
    assertThat(summed.pool()).isEqualTo(2650);
    assertThat(summed.heldOutInPool()).isEqualTo(115);
    assertThat(summed.hits()).isEqualTo(7);
    assertThat(summed.hitRankSum())
        .as("51 over 7 hits is the mean over every hit in the run, not a mean of three means")
        .isEqualTo(51);
    assertThat(summed.negativesOffered()).isEqualTo(3);
    assertThat(summed.negativeRankSum()).isEqualTo(13);
  }

  @Test
  @DisplayName("one fold sums to itself")
  void shouldReturnTheSameNumbersWhenThereIsOnlyOneFold() {
    Reading only = new Reading(SETTING, 900, 40, 4, 30, 2, 8);

    assertThat(Reading.summed(List.of(only))).isEqualTo(only);
  }

  @Test
  @DisplayName("readings of two different settings are refused, because one row is one setting")
  void shouldRefuseTheFoldsWhenTheyAreNotAllOfOneSetting() {
    Reading lift = new Reading(SETTING, 900, 40, 4, 30, 2, 8);
    Reading raw = new Reading(new Setting(Scorer.RAW, 5), 900, 40, 4, 30, 2, 8);

    assertThatThrownBy(() -> Reading.summed(List.of(lift, raw)))
        .as("summing across settings instead of across folds is the transposition this catches")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("two different settings");
  }

  @Test
  @DisplayName("no folds at all is refused, because there is no setting to name")
  void shouldRefuseTheFoldsWhenThereAreNone() {
    assertThatThrownBy(() -> Reading.summed(List.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("no folds");
  }
}
```

- [ ] **Step 2 — make the failure an assertion, not a compile error.** Add a stub to `Reading.java`
      that compiles and answers the first fold (add `import java.util.List;`):

```java
  /** Stub — task 3, step 2. Step 3 adds. */
  public static Reading summed(List<Reading> folds) {
    return folds.get(0);
  }
```

      Run, blocking:

```bash
./gradlew test --tests 'com.robsartin.segue.evaluate.ReadingTest'
```

      **Expect three real assertion failures**, and quote them:
      `shouldAddEveryFieldWhenTheFoldsOfOneSettingAreSummed` — `expected: 2650 but was: 900`;
      `shouldRefuseTheFoldsWhenTheyAreNotAllOfOneSetting` — no exception thrown;
      `shouldRefuseTheFoldsWhenThereAreNone` — `IndexOutOfBoundsException` where
      `IllegalArgumentException` was expected. `shouldReturnTheSameNumbersWhenThereIsOnlyOneFold`
      passes against the stub, and that is expected — it is the case a stub cannot get wrong.

- [ ] **Step 3 — write the implementation.** Replace the stub:

```java
  /**
   * One row of the report from one reading per fold (issue #268).
   *
   * <p><b>Counts add, rank sums add, and nothing here divides.</b> The report takes the single
   * division, over every hit in the run — see {@link #hitRankSum}. The folds of one split are
   * disjoint, so a held-out entity is counted in exactly one of them and a total is a total rather
   * than an overlap.
   *
   * <p><b>It refuses folds of two different settings</b>, which is the one transposition this
   * arithmetic is exposed to: summing down the grid instead of across the folds would produce a
   * table that looks entirely plausible and means nothing.
   *
   * @param folds one reading per fold, all of one setting, at least one
   */
  public static Reading summed(List<Reading> folds) {
    Objects.requireNonNull(folds, "folds");
    if (folds.isEmpty()) {
      throw new IllegalArgumentException(
          "no folds to sum: a row of the report is one setting over at least one fold");
    }

    Setting setting = folds.get(0).setting();
    int pool = 0;
    int heldOutInPool = 0;
    int hits = 0;
    int hitRankSum = 0;
    int negativesOffered = 0;
    int negativeRankSum = 0;
    for (Reading fold : folds) {
      if (!setting.equals(fold.setting())) {
        throw new IllegalArgumentException(
            "readings of two different settings cannot be summed: one row of the report is one"
                + " setting read over every fold of the split");
      }
      pool += fold.pool();
      heldOutInPool += fold.heldOutInPool();
      hits += fold.hits();
      hitRankSum += fold.hitRankSum();
      negativesOffered += fold.negativesOffered();
      negativeRankSum += fold.negativeRankSum();
    }
    return new Reading(
        setting, pool, heldOutInPool, hits, hitRankSum, negativesOffered, negativeRankSum);
  }
```

- [ ] **Step 4 — verify it passes.** Fast loop over `ReadingTest`; four green. Quote the count.

- [ ] **Step 5 — planted control, guard one.** Comment out the `folds.isEmpty()` throw, run
      `ReadingTest`, confirm `shouldRefuseTheFoldsWhenThereAreNone` **fails** with
      `IndexOutOfBoundsException` where `IllegalArgumentException` was expected. Quote it. Restore,
      re-run, green.

- [ ] **Step 6 — planted control, guard two.** Comment out the `!setting.equals(fold.setting())`
      throw, run `ReadingTest`, confirm `shouldRefuseTheFoldsWhenTheyAreNotAllOfOneSetting` **fails**
      — no exception thrown, the two readings sum happily under `lift`'s setting, which is exactly
      the silent-wrong-table failure the guard exists for. Quote it. Restore, re-run, green.

- [ ] **Step 7 — gate and commit.** Gate, blocking. Then:

```bash
git status --short
git add src/main/java/com/robsartin/segue/evaluate/Reading.java \
        src/test/java/com/robsartin/segue/evaluate/ReadingTest.java
git commit
```

      Message: `Sum one reading per fold into one row (#268)`.

---

### Task 4: the run reads every fold, and the header says so

**Files:** `EvaluateRun.java`, `EvaluationReport.java`, `EvaluateRunTest.java`,
`EvaluationReportTest.java`.

**These two halves land in one commit on purpose.** Either alone leaves a true build printing a false
sentence: a header that says "in 5 fold(s)" over a single-fold run, or a folded run whose header
says one fold's counts. That is ADR 4's coupled-change shape, and ADR 65 records the same reasoning
for the rule that had to land with the CLI.

- [ ] **Step 1 — write the tests.** `EvaluationReportTest`: the fixture counts and the header
      assertions.

```java
  /** What the split reported: 10 eligible, 5 folds, all 10 held out over them, 8 left at least. */
  private static final int ELIGIBLE = 10;

  private static final int FOLDS = 5;

  private static final int HELD_OUT_TOTAL = 10;

  private static final int LEAST_LEFT = 8;
```

      Every `EvaluationReport.lines(ELIGIBLE, HELD_OUT_COUNT, 25, …)` call becomes
      `EvaluationReport.lines(ELIGIBLE, FOLDS, HELD_OUT_TOTAL, LEAST_LEFT, 25, …)` — there are four.
      The header assertion becomes:

```java
    assertThat(lines.get(1))
        .contains("10 eligible")
        .contains("in 5 fold(s)")
        .contains("10 held out over all folds")
        .contains("at least 8 left on the known-list");
```

      `EvaluateRunTest`: one new test, and one existing assertion that has to move.

```java
  @Test
  @DisplayName("every fold is read, so an entity held out only in a later fold is a hit too")
  void shouldCountAHitFromEveryFoldWhenTwoEligibleEntitiesFallInDifferentFolds() throws IOException {
    try (TinkerGraphStore graph = InventedEvaluation.graph()) {
      List<String> lines = new ArrayList<>();

      List<Reading> readings =
          new EvaluateRun(
                  graph,
                  qid -> false,
                  Map.of(
                      InventedEvaluation.STRANGER, 5,
                      InventedEvaluation.HIDDEN, 5,
                      InventedEvaluation.REJECTED, 1),
                  Equivalences.NONE)
              .run(knownList(), 25, lines::add);

      assertThat(lines.get(1))
          .as("two eligible entities, one in fold zero and one in fold one, and both are read")
          .contains("2 eligible entity(ies)")
          .contains("in " + HeldOut.EVERY + " fold(s)")
          .contains("2 held out over all folds")
          .contains("at least 1 left on the known-list");
      assertThat(readings)
          .as("one hit in fold zero and one in fold one — a single-fold run reports one")
          .anyMatch(reading -> reading.hits() == 2);
    }
  }
```

      In the existing `shouldReportOneRowPerSettingWhenTheRunSweepsTheGrid`, the negatives assertion
      changes, because a rated-down entity is offered once **per fold**:

```java
      assertThat(readings)
          .as("the rated-down entity is in the pool in every fold, because suppression is withheld")
          .anyMatch(reading -> reading.negativesOffered() == HeldOut.EVERY);
```

      Its `hits() == 1` assertion does **not** change: one eligible entity is held out in fold zero
      and in no other, so the total is one.

- [ ] **Step 2 — make the failures assertions, not compile errors.** Widen the report's signature
      and pass the new counts from the still-single-fold run, but keep emitting the old split line.

      `EvaluationReport.lines`:

```java
  /**
   * Render the whole block, header included.
   *
   * @param eligible how many entities could have been held out — the split's denominator
   * @param folds how many folds of that split were read
   * @param heldOutTotal how many entities were held out over all of them
   * @param leastLeft the fewest left on the known-list in any one fold
   * @param top how many candidates each setting was read over
   * @param readings one per setting, in the order they should be read
   */
  public static List<String> lines(
      int eligible,
      int folds,
      int heldOutTotal,
      int leastLeft,
      int top,
      List<Reading> readings) {
```

      Leave the body's split line untouched for this step, changing only the names it reads so it
      compiles (`heldOutTotal` where `heldOutCount` was, `eligible - heldOutTotal` for the tail).

      `EvaluateRun.run`, still one fold, at the call:

```java
    EvaluationReport.lines(
            split.eligible(),
            HeldOut.EVERY,
            split.heldOut().size(),
            split.eligible() - split.heldOut().size(),
            top,
            readings)
        .forEach(lines);
```

      Run, blocking:

```bash
./gradlew test --tests 'com.robsartin.segue.evaluate.*'
```

      **Expect two real assertion failures**, and quote them:
      `EvaluationReportTest.shouldStateTheSplitAndOneRowPerReadingWhenTheReportIsRendered` — the line
      does not contain `in 5 fold(s)`; and
      `EvaluateRunTest.shouldCountAHitFromEveryFoldWhenTwoEligibleEntitiesFallInDifferentFolds` —
      the same missing substring (the split line assertion runs first). The existing
      `shouldReportOneRowPerSettingWhenTheRunSweepsTheGrid` is also red on
      `negativesOffered() == HeldOut.EVERY`, which is the second half of this task and is expected to
      stay red until step 4.

- [ ] **Step 3 — write the new split line.** In `EvaluationReport.lines`, replace the second rendered
      line:

```java
    rendered.add(
        "# held out every "
            + HeldOut.EVERY
            + " of "
            + eligible
            + " eligible entity(ies), in "
            + folds
            + " fold(s): "
            + heldOutTotal
            + " held out over all folds, at least "
            + leastLeft
            + " left on the known-list in each.");
```

      and add to the class javadoc, after the widths paragraph:

```java
 * <p><b>The split line states folds</b> (issue #268). The folds partition the eligible population,
 * so the total held out equals the denominator beside it and a reader can see the two agree; what
 * each fold leaves on the known-list differs by one between folds, so the line states the smallest —
 * the worst case for what the recommender had to learn from — rather than a number that is right for
 * some folds only. The fold count is passed rather than read off {@code HeldOut.EVERY}, because how
 * many folds were read is a fact about the run rather than an assumption this class may make.
```

      Re-run the fast loop: `EvaluationReportTest` is green, `EvaluateRunTest` is still red on
      `hits() == 2` and on `negativesOffered() == HeldOut.EVERY`. **Quote the remaining failure** —
      it is the red the fold loop is about to turn green.

- [ ] **Step 4 — write the fold loop.** Replace `EvaluateRun.run`'s body:

```java
  public List<Reading> run(Path known, int top, Consumer<String> lines) {
    Objects.requireNonNull(known, "known");
    Objects.requireNonNull(lines, "lines");

    List<String> fromFile = QidList.read(known);
    Set<String> onFile = new LinkedHashSet<>(fromFile);
    CandidateSweep sweep = new CandidateSweep(graph, recognitionInstitutionClass);

    List<List<Reading>> byFold = new ArrayList<>();
    for (int setting = 0; setting < Setting.GRID.size(); setting++) {
      byFold.add(new ArrayList<>());
    }

    int eligible = 0;
    int heldOutTotal = 0;
    int leastLeft = 0;
    for (int fold = 0; fold < HeldOut.EVERY; fold++) {
      HeldOut split = HeldOut.every(HeldOut.EVERY, fold, ratings, onFile, sweep::couldBeExplored);
      List<String> knownList = KnownList.promoted(fromFile, split.ratingsWithout());
      ToDoubleFunction<String> regard = Recommendations.regardFor(split.ratingsWithout());
      Set<String> negatives = KnownList.suppressed(split.ratingsWithout());
      Set<String> heldOut = Set.copyOf(split.heldOut());

      // The same in every fold: the eligibility rule does not read the offset.
      eligible = split.eligible();
      heldOutTotal += split.heldOut().size();
      int left = split.eligible() - split.heldOut().size();
      leastLeft = fold == 0 ? left : Math.min(leastLeft, left);

      for (int i = 0; i < Setting.GRID.size(); i++) {
        Setting setting = Setting.GRID.get(i);
        // Suppression withheld on purpose: merges.merged() and nothing else, so the rated-down
        // entities are in the pool and can be ranked. Scoring filters them back out for the
        // held-out reading.
        Sweep swept =
            sweep.over(knownList, merges.merged(), setting.scorer(), setting.floor(), regard);
        byFold.get(i).add(Scoring.read(swept, setting, heldOut, negatives, top));
      }
    }

    List<Reading> readings = byFold.stream().map(Reading::summed).toList();
    EvaluationReport.lines(eligible, HeldOut.EVERY, heldOutTotal, leastLeft, top, readings)
        .forEach(lines);
    return List.copyOf(readings);
  }
```

      Update the class javadoc: change the first line to
      `Split, sweep the grid, report — in that order, every fold of the split, and one sweep per
      setting per fold (ADR 65, issue #268).`, and add after the "One map, three consumers"
      paragraph:

```java
 * <p><b>Every fold, and the counts are totals over them.</b> The split has {@link HeldOut#EVERY}
 * folds and this runs all of them, so every eligible entity is held out exactly once and no fold's
 * known-list is any smaller than a single fold's was (issue #268). One row per setting reaches the
 * report — {@link Reading#summed} over that setting's folds — so the counts are totals and the means
 * are over every hit in the run. It costs {@code HeldOut.EVERY} times the sweeps; the boot, the
 * projection and the sweep's memoised degrees are still paid once, because none of them depends on
 * the known-list.
```

      Run the fast loop. Everything green. Quote the count.

- [ ] **Step 5 — planted control for the safe-to-paste guard on the new line.** The new split line is
      a line `EvaluationIsSafeToPasteTest` reads, and the claim that its qid clause covers it is
      proved rather than assumed. Temporarily append an invented qid to the split line in
      `EvaluationReport`:

```java
            + " left on the known-list in each. Q0900901");
```

      Run, blocking:

```bash
./gradlew test --tests 'com.robsartin.segue.evaluate.EvaluationIsSafeToPasteTest'
```

      Confirm the **third** assertion fires — "no line carries anything qid-shaped, wherever it came
      from" — and quote it. Remove the plant, re-run, green. Do **not** leave the plant in the tree;
      `git diff` must show the file back at its committed content for that line before staging.

- [ ] **Step 6 — gate and commit.** Gate, blocking. Then:

```bash
git status --short
git add src/main/java/com/robsartin/segue/evaluate/EvaluateRun.java \
        src/main/java/com/robsartin/segue/evaluate/EvaluationReport.java \
        src/test/java/com/robsartin/segue/evaluate/EvaluateRunTest.java \
        src/test/java/com/robsartin/segue/evaluate/EvaluationReportTest.java
git commit
```

      Message: `Read every fold of the split, and say so in the header (#268)`.

---

### Task 5: the ADR amendment and the developer guide

**Files:** `docs/adr/0065-an-offline-evaluation-harness-for-the-recommender.md` (append only),
`docs/developer-guide.md`.

**Say this out loud in the step report: there is no unit test in this task, and here is why.** No
behaviour changes — the code is already as this task describes it. The verification method is the
full gate over an otherwise unchanged tree: `AdrIndexTest` (untouched index), `DocumentationLinksTest`
(every relative link below resolves), `DeveloperGuideEnumerationsTest` (the guide's derived tables and
diagram are unaffected — no package, no import edge, no arch rule and no dev task changes),
`DeveloperGuideEvaluateExamplesTest` (the runbook's commands still parse), and `javadoc -Werror`.

- [ ] **Step 1 — append the amendment to ADR 65.** At the very end of the file, after the last
      consequence, append (front matter untouched, nothing above edited):

```markdown

**Amendment (2026-09-06, issue #268): the split is read on every fold, not on one.**

Nothing above is withdrawn and no decision above is edited. The eligible population, the interval,
the grid, the command line, the fences and the output contract's shape are exactly as decided. What
changes is how many times the split is taken.

**What was wrong with reading one fold.** `HeldOut.every` held out the eligible entities at positions
`0, EVERY, 2·EVERY, …` and the harness read that one slice, so four fifths of the eligible population
was never held out and every hit count was the size a single fold gives.
[ADR 45](0045-recommend-by-normalised-lift-with-routes.md)'s two readings both ended on the same
sentence: a null result on that split cannot tell "the setting is right" from "the split is too small
to say".

**The change.** `HeldOut.every` takes an offset as well as an interval; fold *k* holds out positions
`k, k + interval, …`, and `HeldOut` is the authority on it. `EvaluateRun` runs folds `0 … EVERY − 1`,
one sweep per setting per fold. Every eligible entity is held out exactly once over a run, and every
fold leaves a known-list the size a single fold left — which is why this rather than a wider split:
holding out more would shrink the known-list the recommender learns from, and that is an input to the
thing being measured. Fold zero is the old behaviour unchanged.

**The number of folds is `HeldOut.EVERY`, and it is not on the command line**, for the reason this
ADR keeps the grid off it: the value of the tool is one comparable block, and a flag would produce a
stack of runs nobody could line up beside each other.

**One row per setting, still sixteen rows.** Counts are totals over the folds; means are over every
hit in the run. The arithmetic is exact rather than nearly so: `Reading` carries the *sum* of the
ranks as an integer and the report divides once. Combining per-fold means instead would divide once
per fold and multiply back, and a value a hair either side of a rounding boundary would render a
different tenth — which an instrument whose whole value is that two readings diff row by row cannot
afford. The rendered contract is unchanged: every cell is still an integer, a fixed one-decimal or
the dash, and a mean over nothing is still the dash, now because its count is zero.
`EvaluationReport.lines` takes five plain counts where it took three, and the type-level fence above
is untouched by that: every one is an `int`, none is a `HeldOut`, and there is still nowhere in the
signature to put an identifier. The split line states the folds; `EvaluationReport` is the authority
on its text.

**What it costs.** `HeldOut.EVERY` times the sweeps — five times today's run. The replay, the
projection and the sweep's memoised degrees are still paid once, because none of them depends on the
known-list. There is no wall-clock assertion anywhere in the harness and none was added.

**What it does not change.** Not the rule that judges a reading, not the grid, not the eligible
population, not the interval, not the promotion threshold, not the suppression boundary, not the
scorer default, not the floor. No line of `recommend`'s output moves.

**The readings already on the record stay exactly as they are.** ADR 45's amendments of 2026-09-04
and 2026-09-06 quote two blocks taken by the unfolded harness. They remain comparable **to each
other** — same instrument, same split — and are **not** comparable row for row to a folded reading:
every count in a folded row is a total over five folds and every mean is taken over a different
population of hits. Neither block is edited, and no figure from either is restated here.

Alternatives rejected: lowering `HeldOut.EVERY` (shrinks the known-list, so two things move at once);
a `--folds` flag (the grid's reason, restated); a fold column or sixteen rows per fold (eighty rows of
five-times-smaller counts is a less legible table saying less); and weighting the per-fold means
(rounds twice, and the second rounding can move a rendered tenth).

**Nothing here is unit-testable, and that is said out loud rather than left implied.** This entry
records a decision whose code landed with its own tests — the partition of the folds, the sum over
them and both of that sum's guards, each with a planted control. The verification of the *document*
is the full gate over an otherwise unchanged tree: `AdrIndexTest`, `DocumentationLinksTest` for the
relative link above, and `javadoc -Werror` inside `./gradlew check`.
```

- [ ] **Step 2 — the developer guide.** In "Calibrating the recommender", in "The protocol", insert a
      paragraph immediately after the "**The split is deterministic by qid, so two runs diff.**"
      paragraph:

```markdown
**Every fold of that split is read, not one.** The split holds out one eligible entity in
`HeldOut.EVERY`; the harness takes every offset of that interval, so each eligible entity is held out
in exactly one fold and each fold leaves a known-list the size one fold ever left. Holding out *more*
per fold would have shrunk the known-list the recommender learns from, which is an input to the thing
being measured; folding grows the evidence instead. One row per setting still reaches the table, with
its counts totalled over the folds and its means taken over every hit in the run. **It costs
`HeldOut.EVERY` times the sweeps** — the replay and the sweep's memoised degrees are still paid once,
but budget five times a single-fold run.
```

      And in the dev-tool table row for `evaluate` (the row beginning `| `evaluate` |`), change
      "holds out a deterministic slice of the entities you rated highly, runs the shipped candidate
      sweep from what is left over a fixed grid of scorers and degree floors" to "holds out a
      deterministic slice of the entities you rated highly, reads every fold of that split, runs the
      shipped candidate sweep from what is left over a fixed grid of scorers and degree floors".
      **Change nothing else in that row** — `DeveloperGuideEnumerationsTest` parses the table's
      shape.

- [ ] **Step 3 — gate and commit.** Gate, blocking — the doc tests are the verification and they only
      run there. Then:

```bash
git status --short
git add docs/adr/0065-an-offline-evaluation-harness-for-the-recommender.md \
        docs/developer-guide.md
git commit
```

      Message: `Record reading every fold, and say what it costs (#268)`.

---

### Task 6: hand the run back to the owner

**No file changes, and nothing is run against a real database.**

- [ ] **Step 1 — confirm the tree.** `git status --short` is clean; `git log --oneline -6` shows the
      five commits above on `268-ready`. Run the gate once more, blocking, from a clean tree and
      quote the result.

- [ ] **Step 2 — say what the owner runs, and do not run it.** The first folded reading is his to
      take, on his own database:

```bash
./gradlew evaluate --args="--db $HOME/.segue/segue.db --known $HOME/known.csv"
```

      Report that it will take about `HeldOut.EVERY` times as long as the readings of 2026-09-04 and
      2026-09-06, that the block is the same sixteen rows with a changed split line, and that its
      counts are totals over five folds and so are **not** row-for-row comparable with either reading
      quoted in ADR 45.

- [ ] **Step 3 — name the follow-up, and file nothing.** The reading itself, judged by #245's rule
      unchanged and recorded as an ADR 45 amendment, is a separate issue. Report to the controller
      that it is wanted and include the one substantive observation this work produced for it: #245's
      void clause tests the smallest `in pool` cell among the rows a clause compares, and on a folded
      table that cell is a total over five folds, so the clause cannot fire for the reason it was
      written for — which the reading issue must say out loud before it applies the rule. **Do not
      amend, reinterpret or "adjust" that rule here.**
