# An offline evaluation harness for the recommender — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** close issue #239 — a ninth dev-side tool, `./gradlew evaluate`, that holds out a deterministic fifth of the owner's high ratings on offerable entities, runs the shipped candidate sweep from what is left over a fixed sixteen-setting grid, and prints one block of aggregates the owner can paste. No constant moves; `recommend`'s output does not change.

**Architecture:** A new package `evaluate` with a pure core — `HeldOut` (the split), `Setting` (the grid), `Scoring` (the per-setting metrics from a `Sweep`), `EvaluationReport` (the table) — plus `EvaluateRun` (composes and sweeps, reports through a `Consumer<String>`) and `EvaluateCli` (the only class that opens SQLite). It depends on `recommend` for the sweep itself, which is the third permitted dev-tool→dev-tool dependency and widens the ArchUnit fences by hand. One production edit outside the new package: `CandidateSweep.couldBeExplored` becomes public, the same move ADR 45 made for `PathRanking.isHub`.

**Tech Stack:** Java 25, Gradle (plain `./gradlew`), JUnit 5, AssertJ, Logback, TinkerGraph, SQLite, ArchUnit.

**Spec:** `docs/superpowers/specs/2026-09-04-recommender-evaluation-harness-design.md`

## Global Constraints

- **Pure TDD / red first.** Every behaviour: write the test, **run it, observe a real assertion failure**, then the minimum code. A compile error is never a red — where a step's first run would only fail to compile, the step says so and names the stub to add first so the failure is an assertion. Quote the actual failure text in every report.
- **Every guard gets a positive control.** The safe-to-paste test and each new ArchUnit fence are guards, not behaviours: their evidence is a **planted defect seen to fire**, then removed, then green. The plan writes each plant out. A guard that has never been seen red is an inert fence — issues #139 and #140.
- Test names `should<Expected>When<Condition>` with `@DisplayName`.
- **Mikado**: the gate is green before every commit, and the coupled commits below say which edits must travel together. **Stage by explicit path, git stderr visible — never `git add -A`, never `2>/dev/null` on `git add`.** Read `git status` before every commit. Commits end `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`. Never cite a `.superpowers/` path from a committed file.
- Gate, **blocking, never backgrounded**: `SEGUE_REQUIRE_BROWSER=true SEGUE_REQUIRE_GRAPHVIZ=true ./gradlew check --rerun-tasks`. Run `./gradlew spotlessApply` before each gate. Fast loops are named per task.
- **Only JDK 25 is installed and Gradle launches on it.** Plain `./gradlew`; never `/usr/libexec/java_home -v 21` (it silently returns 25).
- **Never run a writing dev task** (`own`, `ownClaim`, `retractEntity`, `rate`, any seeding task). `~/.segue/segue.db` is never read, written, copied or created. Every database in this plan is a `@TempDir` file or an in-memory `TinkerGraphStore`.
- **No constant changes anywhere.** `Recommendations.MIN_CANDIDATE_DEGREE`, `NEUTRAL_RATING`, `KnownList.PROMOTION_RATING`, `KnownList.SUPPRESSION_RATING`, `RecommendationWeights`, `PathRanking.HUB_DEGREE`, `RecommendCli.DEFAULT_TOP` and `Scorer`'s values are read, never edited. The only edit to `recommend` is one `private` → `public`.
- **`recommend`'s output is unchanged.** No line, default or ordering in `RecommendationReport`, `RecommendRun` or `RecommendCli` moves.
- **ADR 33's taste fences hold.** The new package may call `AffinityStore.readRatings` and nothing else on the taste layer: never `find`, never `readAll`, never `AffinityRecord`. No qid, label, note or rating value ever reaches a log line or the report.
- **Invented ids only.** Every id in `src/test` here is in the `Q09004xx` family and carries ADR 58's leading zero, which Wikibase's grammar refuses — so `StandInQidsDenoteNothingTest` needs no `ALLOWED` entry. Every label, note and rating in a fixture is made up (ADR 33, issue #37).
- **Javadoc is a gate** (`-Xdoclint:all,-missing -Werror`). Every `{@link}` must resolve; cite a test class as a `{@code}` span, never a link, and spell it exactly — `JavadocCitationsTest` resolves every `{@code Name.member}` span against `src/test`.
- **`ArchitectureTest` rules need a guide table row** (`DeveloperGuideEnumerationsTest`), and a **new ADR needs its `docs/adr/README.md` row** (`AdrIndexTest`). Both land in the same commit as the thing they describe.
- **The guide's layering diagram and package table are derived from the tree.** The moment `src/main/java/com/robsartin/segue/evaluate/` exists, `DeveloperGuideEnumerationsTest` demands an `evaluate` node, a package-table row, and one edge per cross-package import. Each task below names the edges it adds. `docs/` is a declared test input, so a guide edit re-runs the tests that read it.
- **YAGNI**: no parameter, helper or accessor beyond what a step below actually uses.
- Machine is loaded: **no wall-clock assertions anywhere.**
- **#238 is in flight on another branch and takes ADR 0064.** This plan takes **0065**. Expect a conflict in `docs/adr/README.md` at merge; resolve by keeping both rows in ascending order.

---

### Task 1: `HeldOut` — the deterministic split

**Files:** create `src/main/java/com/robsartin/segue/evaluate/HeldOut.java`, `src/test/java/com/robsartin/segue/evaluate/HeldOutTest.java`. Edit `docs/developer-guide.md`.

This task creates the package, so the guide's diagram and package table move with it.

- [ ] **Step 1 — write the failing test in full.** Create `HeldOutTest.java`:

```java
package com.robsartin.segue.evaluate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.robsartin.segue.domain.KnownList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The split, over invented ratings. Nothing here comes from anybody's taste layer (ADR 33, issue
 * #37), and every id carries ADR 58's leading zero.
 */
class HeldOutTest {

  private static final Set<String> NOTHING_ON_FILE = Set.of();

  /** Everything offered to the split is a candidate the sweep could return, unless a test says so. */
  private static final java.util.function.Predicate<String> ANYTHING = qid -> true;

  @Test
  @DisplayName("every fifth entity by qid order is held out, and the rest keep their ratings")
  void shouldHoldOutEveryFifthByQidOrderWhenThePopulationIsEligible() {
    Map<String, Integer> ratings = new LinkedHashMap<>();
    // Deliberately inserted out of order: the split must read qid order, not map order.
    for (String qid :
        java.util.List.of(
            "Q0900406", "Q0900401", "Q0900409", "Q0900403", "Q0900407", "Q0900402", "Q0900410",
            "Q0900405", "Q0900408", "Q0900404")) {
      ratings.put(qid, KnownList.PROMOTION_RATING);
    }

    HeldOut split = HeldOut.every(HeldOut.EVERY, ratings, NOTHING_ON_FILE, ANYTHING);

    assertThat(split.heldOut())
        .as("indices 0 and 5 of ten eligible entities sorted ascending")
        .containsExactly("Q0900401", "Q0900406");
    assertThat(split.eligible()).isEqualTo(10);
    assertThat(split.ratingsWithout())
        .as("the held-out ratings are gone and nothing else moved")
        .hasSize(8)
        .doesNotContainKeys("Q0900401", "Q0900406")
        .containsEntry("Q0900402", KnownList.PROMOTION_RATING);
  }

  @Test
  @DisplayName("a rating below the promotion threshold is never eligible, and never held out")
  void shouldIgnoreARatingWhenItIsBelowThePromotionThreshold() {
    Map<String, Integer> ratings =
        Map.of(
            "Q0900401", KnownList.PROMOTION_RATING - 1,
            "Q0900402", KnownList.SUPPRESSION_RATING);

    HeldOut split = HeldOut.every(HeldOut.EVERY, ratings, NOTHING_ON_FILE, ANYTHING);

    assertThat(split.eligible()).isZero();
    assertThat(split.heldOut()).isEmpty();
    assertThat(split.ratingsWithout())
        .as("a suppressed rating stays in the map: it is the negative signal, not the held-out set")
        .isEqualTo(ratings);
  }

  @Test
  @DisplayName("an entity the known-list file names is never eligible, because the file puts it back")
  void shouldIgnoreAnEntityWhenTheKnownListFileAlreadyNamesIt() {
    Map<String, Integer> ratings = Map.of("Q0900401", 5, "Q0900402", 5);

    HeldOut split = HeldOut.every(HeldOut.EVERY, ratings, Set.of("Q0900401"), ANYTHING);

    assertThat(split.eligible()).isEqualTo(1);
    assertThat(split.heldOut()).containsExactly("Q0900402");
  }

  @Test
  @DisplayName("an entity the sweep could never offer is never eligible")
  void shouldIgnoreAnEntityWhenTheSweepCouldNotOfferItBack() {
    Map<String, Integer> ratings = Map.of("Q0900401", 5, "Q0900402", 5);

    HeldOut split =
        HeldOut.every(HeldOut.EVERY, ratings, NOTHING_ON_FILE, "Q0900402"::equals);

    assertThat(split.eligible()).isEqualTo(1);
    assertThat(split.heldOut()).containsExactly("Q0900402");
  }

  @Test
  @DisplayName("holding out every entity is refused, because a run with no known-list measures nothing")
  void shouldRefuseTheIntervalWhenItWouldHoldOutTheWholePopulation() {
    assertThatThrownBy(() -> HeldOut.every(1, Map.of("Q0900401", 5), NOTHING_ON_FILE, ANYTHING))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("every 1");
  }
}
```

- [ ] **Step 2 — make the failure an assertion, not a compile error.** Create `HeldOut.java` as a stub that compiles and answers wrongly, then run the fast loop and **quote the assertion failure**:

```java
package com.robsartin.segue.evaluate;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/** Stub — task 1, step 2. Replaced in step 3. */
public record HeldOut(List<String> heldOut, Map<String, Integer> ratingsWithout, int eligible) {

  public static final int EVERY = 5;

  public static HeldOut every(
      int interval,
      Map<String, Integer> ratings,
      Set<String> onFile,
      Predicate<String> couldBeOffered) {
    return new HeldOut(List.of(), ratings, 0);
  }
}
```

```bash
./gradlew test --tests 'com.robsartin.segue.evaluate.HeldOutTest'
```

Expect a real assertion failure on the first test (`Expecting actual: [] to contain exactly: ["Q0900401", "Q0900406"]`) — record what it actually said.

- [ ] **Step 3 — write the implementation.** Replace `HeldOut.java` in full:

```java
package com.robsartin.segue.evaluate;

import com.robsartin.segue.domain.KnownList;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;

/**
 * A deterministic slice of what the owner has rated highly, hidden — and the taste layer as it
 * would have looked before those ratings were written (issue #239, ADR 65).
 *
 * <p><b>Only a promotion can be held out, and that is arithmetic rather than a preference.</b>
 * {@link KnownList#promoted} composes the known-list as the {@code --known} file <em>plus</em>
 * everything rated at or above {@link KnownList#PROMOTION_RATING} the file does not name. Withdraw
 * a rating from an entity the file names and the file puts it straight back, so the sweep was never
 * blind to it and a hit against it would measure nothing. The eligible population is therefore
 * rated highly, absent from the file, and offerable as a candidate — the third condition asked of
 * {@code CandidateSweep.couldBeExplored} itself rather than restated here.
 *
 * <p><b>The order is the qid's and there is no randomness at all.</b> Two runs over one unchanged
 * database hold out the same entities and produce byte-identical output, which is the contract ADR
 * 43 gives the ratings listing and {@code Recommendations.rank} gives the recommender's tiebreak. A
 * random split would be reproducible only against a seed somebody remembered to record, and ADR
 * 57's finding is that a number nobody re-derives stops being re-derived.
 *
 * <p><b>A rating at or below {@link KnownList#SUPPRESSION_RATING} is never held out</b>: it is the
 * negative signal the harness reads separately, and it stays in {@link #ratingsWithout} so {@code
 * KnownList.suppressed} can still name it.
 *
 * @param heldOut the hidden entities, ascending by qid
 * @param ratingsWithout the ratings map with those entries removed — the one map the known-list,
 *     the regard function and the suppressed set are all built from, so no two of them can disagree
 * @param eligible how many entities could have been held out, which is the denominator the report
 *     states its split against
 */
public record HeldOut(List<String> heldOut, Map<String, Integer> ratingsWithout, int eligible) {

  /** Hold out one entity in five. A fifth is enough to measure and little enough to still rank. */
  public static final int EVERY = 5;

  public HeldOut {
    heldOut = List.copyOf(Objects.requireNonNull(heldOut, "heldOut"));
    ratingsWithout = Map.copyOf(Objects.requireNonNull(ratingsWithout, "ratingsWithout"));
  }

  /**
   * Split one ratings map.
   *
   * @param interval hold out every {@code interval}-th eligible entity, counting from the first
   * @param ratings the note-free bulk read, already resolved through {@code Equivalences.resolve}
   * @param onFile the qids the {@code --known} file names
   * @param couldBeOffered whether the sweep could return this entity as a candidate at all
   */
  public static HeldOut every(
      int interval,
      Map<String, Integer> ratings,
      Set<String> onFile,
      Predicate<String> couldBeOffered) {
    Objects.requireNonNull(ratings, "ratings");
    Objects.requireNonNull(onFile, "onFile");
    Objects.requireNonNull(couldBeOffered, "couldBeOffered");
    if (interval < 2) {
      throw new IllegalArgumentException(
          "holding out every "
              + interval
              + " would leave nothing to recommend from: the interval must be at least 2");
    }

    List<String> eligible = new ArrayList<>();
    for (String qid : new TreeSet<>(ratings.keySet())) {
      if (ratings.get(qid) >= KnownList.PROMOTION_RATING
          && !onFile.contains(qid)
          && couldBeOffered.test(qid)) {
        eligible.add(qid);
      }
    }

    List<String> heldOut = new ArrayList<>();
    for (int i = 0; i < eligible.size(); i += interval) {
      heldOut.add(eligible.get(i));
    }

    Map<String, Integer> without = new LinkedHashMap<>(ratings);
    heldOut.forEach(without::remove);
    return new HeldOut(heldOut, without, eligible.size());
  }
}
```

- [ ] **Step 4 — run the fast loop and see it green.** `./gradlew test --tests 'com.robsartin.segue.evaluate.HeldOutTest'`

- [ ] **Step 5 — the guide, in the same commit.** `docs/developer-guide.md`:
  - in the mermaid diagram under `## The layering`, add the node **after** `census`:
    `  evaluate["evaluate<br/>EvaluateCli, HeldOut, Scoring, EvaluationReport"]`
  - and the edge, after the last `census -->` line: `  evaluate --> domain`
  - in `### What each package is for`, add a row after the `census` row:

```
| `evaluate` | The recommender's evaluation harness ([ADR 65](adr/0065-an-offline-evaluation-harness-for-the-recommender.md)): holds out a deterministic slice of the entities you rated highly, runs the shipped candidate sweep from what is left over a fixed grid of scorers and degree floors, and reports where the held-out entities and the ones you rated down land. Run as `./gradlew evaluate`. Plain Java, read-only, offline, and the whole output is aggregates — no label, no id, no note, no rating — so it is safe to paste. `--db` is required, and `SEGUE_DB` does not satisfy it. | `port`, `domain`, `ingest`, `sqlite`, `tinker`, `wikidata`, `recommend`, `support` |
```

  The ADR link resolves only once task 11 lands; markdown links are not checked by any test, and the row's package list is the end state (the "Depends on" column is prose and is not derived).

- [ ] **Step 6 — gate and commit.** `./gradlew spotlessApply` then the full gate, blocking. Commit `src/main/java/com/robsartin/segue/evaluate/HeldOut.java`, `src/test/java/com/robsartin/segue/evaluate/HeldOutTest.java`, `docs/developer-guide.md` by explicit path.

---

### Task 2: `Setting` — the fixed grid

**Files:** create `src/main/java/com/robsartin/segue/evaluate/Setting.java`, `src/test/java/com/robsartin/segue/evaluate/SettingTest.java`.

No new import edge: `Setting` imports `com.robsartin.segue.domain.Scorer`, and `evaluate --> domain` is already drawn.

- [ ] **Step 1 — write the failing test.** Create `SettingTest.java`:

```java
package com.robsartin.segue.evaluate;

import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.segue.domain.Recommendations;
import com.robsartin.segue.domain.Scorer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SettingTest {

  @Test
  @DisplayName("the grid is every scorer against every floor, scorer-major and floor-ascending")
  void shouldPairEveryScorerWithEveryFloorWhenTheGridIsBuilt() {
    assertThat(Setting.GRID)
        .hasSize(Scorer.values().length * Setting.FLOORS.size())
        .startsWith(new Setting(Scorer.values()[0], Setting.FLOORS.get(0)))
        .endsWith(
            new Setting(
                Scorer.values()[Scorer.values().length - 1],
                Setting.FLOORS.get(Setting.FLOORS.size() - 1)));
    assertThat(Setting.GRID.stream().map(Setting::scorer).distinct())
        .containsExactly(Scorer.values());
  }

  @Test
  @DisplayName("the floors ascend and include the one the recommender ships with")
  void shouldIncludeTheShippedFloorWhenTheFloorsAreListed() {
    assertThat(Setting.FLOORS).isSorted().doesNotHaveDuplicates();
    assertThat(Setting.FLOORS)
        .as("a grid that cannot reproduce today's default cannot say what changing it costs")
        .contains(Recommendations.MIN_CANDIDATE_DEGREE);
  }
}
```

- [ ] **Step 2 — stub, then a real red.** Create `Setting.java` with `FLOORS = List.of(5)` and `GRID = List.of()`, run `./gradlew test --tests 'com.robsartin.segue.evaluate.SettingTest'`, and quote the size assertion failure.

- [ ] **Step 3 — implement.** Replace `Setting.java`:

```java
package com.robsartin.segue.evaluate;

import com.robsartin.segue.domain.Scorer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * One point on the grid the harness sweeps: which scorer, and which degree floor (ADR 65).
 *
 * <p><b>Fixed, and deliberately not on the command line.</b> The value of this tool is one block a
 * person reads in one sitting, and a flag would produce a stack of runs nobody could line up beside
 * each other. Every setting appears in every run, so two runs a month apart diff row by row.
 *
 * <p><b>Each floor earns its place.</b> {@code 2} is the point below which a normalised score stops
 * meaning anything — {@code RecommendCli} refuses a smaller {@code --min-degree} for that reason.
 * {@code 5} is what the recommender ships with, {@code Recommendations.MIN_CANDIDATE_DEGREE}; a
 * grid that could not reproduce today's default could not say what changing it costs. {@code 12} is
 * the floor ADR 50 took its measurements against, before ADR 45's 2026-08-29 amendment lowered it.
 * {@code 8} sits between the two so the trend between them is read rather than inferred. The
 * numbers are a grid, not a set of defaults: nothing here changes what any tool ships with.
 */
public record Setting(Scorer scorer, int floor) {

  /** The degree floors swept, ascending. */
  public static final List<Integer> FLOORS = List.of(2, 5, 8, 12);

  /** Every scorer against every floor, in {@code Scorer} declaration order. */
  public static final List<Setting> GRID = grid();

  public Setting {
    Objects.requireNonNull(scorer, "scorer");
  }

  private static List<Setting> grid() {
    List<Setting> grid = new ArrayList<>();
    for (Scorer scorer : Scorer.values()) {
      for (int floor : FLOORS) {
        grid.add(new Setting(scorer, floor));
      }
    }
    return List.copyOf(grid);
  }
}
```

- [ ] **Step 4 — green, gate, commit** the two files by explicit path.

---

### Task 3: `Reading` and `Scoring` — the metrics, over the whole pool

**Files:** create `src/main/java/com/robsartin/segue/evaluate/Reading.java`, `src/main/java/com/robsartin/segue/evaluate/Scoring.java`, `src/test/java/com/robsartin/segue/evaluate/ScoringTest.java`. Edit `docs/developer-guide.md`.

This task adds the edge `evaluate --> recommend` (`Scoring` imports `recommend.Sweep`).

**Deliberately incomplete:** this task ranks the held-out positives over the **whole** pool, negatives included. Task 4's test is what shows that is wrong and drives the filter in. Do not write the filter here.

- [ ] **Step 1 — write the failing test.** Create `ScoringTest.java`:

```java
package com.robsartin.segue.evaluate;

import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.domain.NodeRecord;
import com.robsartin.segue.domain.Recommendation;
import com.robsartin.segue.domain.Scorer;
import com.robsartin.segue.recommend.Sweep;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The metrics, over a hand-built pool. No graph, no store, no traversal: {@code Scoring} is a pure
 * function of a {@code Sweep}, and this is what says so. Every id, label and score is invented.
 */
class ScoringTest {

  private static final Setting SETTING = new Setting(Scorer.LIFT, 5);

  @Test
  @DisplayName("a held-out entity in the top N is a hit, and its rank is 1-based")
  void shouldReportTheHitAndItsRankWhenAHeldOutEntityIsRankedHighly() {
    Sweep sweep = pool(List.of("Q0900401", "Q0900402", "Q0900403", "Q0900404"));

    Reading reading = Scoring.read(sweep, SETTING, Set.of("Q0900402"), Set.of(), 4);

    assertThat(reading.pool()).isEqualTo(4);
    assertThat(reading.heldOutInPool()).isEqualTo(1);
    assertThat(reading.hits()).isEqualTo(1);
    assertThat(reading.meanHitRank()).hasValue(2.0);
  }

  @Test
  @DisplayName("a held-out entity below the cut is in the pool and is not a hit")
  void shouldCountItInThePoolAndNotAsAHitWhenAHeldOutEntityFallsOutsideTheTop() {
    Sweep sweep = pool(List.of("Q0900401", "Q0900402", "Q0900403", "Q0900404"));

    Reading reading = Scoring.read(sweep, SETTING, Set.of("Q0900404"), Set.of(), 2);

    assertThat(reading.heldOutInPool()).isEqualTo(1);
    assertThat(reading.hits()).isZero();
    assertThat(reading.meanHitRank())
        .as("a mean over nothing is absent, not zero — zero is a rank")
        .isEmpty();
  }

  @Test
  @DisplayName("the mean rank of two hits is their arithmetic mean")
  void shouldAverageTheRanksWhenMoreThanOneHeldOutEntityIsAHit() {
    Sweep sweep = pool(List.of("Q0900401", "Q0900402", "Q0900403", "Q0900404"));

    Reading reading = Scoring.read(sweep, SETTING, Set.of("Q0900401", "Q0900404"), Set.of(), 4);

    assertThat(reading.hits()).isEqualTo(2);
    assertThat(reading.meanHitRank()).hasValue(2.5);
  }

  @Test
  @DisplayName("a rated-down entity in the top N is reported with its rank")
  void shouldReportTheNegativeAndItsRankWhenTheRankingWouldHaveOfferedIt() {
    Sweep sweep = pool(List.of("Q0900401", "Q0900402", "Q0900403", "Q0900404"));

    Reading reading = Scoring.read(sweep, SETTING, Set.of(), Set.of("Q0900403"), 4);

    assertThat(reading.negativesOffered()).isEqualTo(1);
    assertThat(reading.meanNegativeRank()).hasValue(3.0);
  }

  /** Descending scores, so the qid order below is the ranked order. */
  private static Sweep pool(List<String> qids) {
    List<Recommendation> candidates = new ArrayList<>();
    double score = qids.size();
    for (String qid : qids) {
      candidates.add(
          new Recommendation(
              new NodeRecord(qid, NodeKind.GROUP, "an invented act " + qid, List.of()),
              score--,
              12,
              List.of()));
    }
    return new Sweep(candidates, qids.size(), 0, 0, 0, 0);
  }
}
```

- [ ] **Step 2 — stub `Reading` and `Scoring` so the failure is an assertion.** Create `Reading.java` in its final form (it is a plain carrier, nothing to get wrong):

```java
package com.robsartin.segue.evaluate;

import java.util.Objects;
import java.util.OptionalDouble;

/**
 * What one setting's sweep said about the held-out entities and the rated-down ones (ADR 65).
 *
 * <p>Every field is a count or a mean of ranks. Nothing here names an entity, which is what makes
 * the report over it safe to paste (ADR 51, ADR 63).
 *
 * @param setting which scorer and which floor produced it
 * @param pool how many candidates cleared the floor, the rated-down ones removed — the pool the
 *     recommender would actually have ranked
 * @param heldOutInPool how many held-out entities are in that pool at all, whatever their rank. A
 *     hit count with no denominator says nothing: an entity below the floor and an entity ranked
 *     900th are different failures
 * @param hits how many held-out entities the top N names
 * @param meanHitRank the mean 1-based rank of those, absent when there are none. Absent rather than
 *     zero, because zero is a rank a reader would compare against
 * @param negativesOffered how many entities rated at or below {@code KnownList.SUPPRESSION_RATING}
 *     the ranking would have offered in the top N with suppression off (ADR 50)
 * @param meanNegativeRank the mean 1-based rank of those, absent when there are none
 */
public record Reading(
    Setting setting,
    int pool,
    int heldOutInPool,
    int hits,
    OptionalDouble meanHitRank,
    int negativesOffered,
    OptionalDouble meanNegativeRank) {

  public Reading {
    Objects.requireNonNull(setting, "setting");
    Objects.requireNonNull(meanHitRank, "meanHitRank");
    Objects.requireNonNull(meanNegativeRank, "meanNegativeRank");
  }
}
```

  and `Scoring.java` as a stub returning zeros:

```java
package com.robsartin.segue.evaluate;

import com.robsartin.segue.recommend.Sweep;
import java.util.OptionalDouble;
import java.util.Set;

/** Stub — task 3, step 2. Replaced in step 3. */
public final class Scoring {

  private Scoring() {}

  public static Reading read(
      Sweep sweep, Setting setting, Set<String> heldOut, Set<String> negatives, int top) {
    return new Reading(setting, 0, 0, 0, OptionalDouble.empty(), 0, OptionalDouble.empty());
  }
}
```

  Run `./gradlew test --tests 'com.robsartin.segue.evaluate.ScoringTest'` and quote the failure.

- [ ] **Step 3 — implement (whole-pool ranking, on purpose).** Replace `Scoring.java`:

```java
package com.robsartin.segue.evaluate;

import com.robsartin.segue.domain.Recommendation;
import com.robsartin.segue.domain.Recommendations;
import com.robsartin.segue.recommend.Sweep;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.Set;

/**
 * One sweep in, one row of the table out. A pure function of its arguments (ADR 65).
 *
 * <p>The sweep it reads was run with suppression <b>withheld</b> — {@code CandidateSweep.over} was
 * given the merges alone — so the entities the owner rated down are in the pool and can be ranked.
 * That is the only way to answer "where would the ranking have offered them", which is a question
 * about a ranking ADR 50 makes it impossible to see.
 */
public final class Scoring {

  private Scoring() {}

  /**
   * Read one setting.
   *
   * @param sweep the candidates that setting produced, suppression withheld
   * @param heldOut the entities hidden from the known-list for this run
   * @param negatives the entities rated at or below {@code KnownList.SUPPRESSION_RATING}
   * @param top how many candidates a run would have shown
   */
  public static Reading read(
      Sweep sweep, Setting setting, Set<String> heldOut, Set<String> negatives, int top) {
    Objects.requireNonNull(sweep, "sweep");
    Objects.requireNonNull(setting, "setting");
    Objects.requireNonNull(heldOut, "heldOut");
    Objects.requireNonNull(negatives, "negatives");

    List<Recommendation> ranked = Recommendations.rank(sweep.candidates(), top);
    List<Integer> hitRanks = ranksOf(ranked, heldOut);
    List<Integer> negativeRanks = ranksOf(ranked, negatives);

    return new Reading(
        setting,
        sweep.candidates().size(),
        (int) sweep.candidates().stream().filter(in(heldOut)).count(),
        hitRanks.size(),
        mean(hitRanks),
        negativeRanks.size(),
        mean(negativeRanks));
  }

  private static java.util.function.Predicate<Recommendation> in(Set<String> wanted) {
    return candidate -> wanted.contains(candidate.entity().qid());
  }

  private static List<Integer> ranksOf(List<Recommendation> ranked, Set<String> wanted) {
    List<Integer> ranks = new ArrayList<>();
    for (int i = 0; i < ranked.size(); i++) {
      if (wanted.contains(ranked.get(i).entity().qid())) {
        ranks.add(i + 1);
      }
    }
    return List.copyOf(ranks);
  }

  private static OptionalDouble mean(List<Integer> ranks) {
    return ranks.stream().mapToInt(Integer::intValue).average();
  }
}
```

- [ ] **Step 4 — green.** `./gradlew test --tests 'com.robsartin.segue.evaluate.ScoringTest'`

- [ ] **Step 5 — the guide edge.** Add `  evaluate --> recommend` to the diagram, after `evaluate --> domain`.

- [ ] **Step 6 — gate and commit** the four files by explicit path.

---

### Task 4: the positives are ranked over the shipped pool, and the equivalence is pinned

**Files:** create `src/test/java/com/robsartin/segue/evaluate/SuppressionIsPurelySubtractiveTest.java`, `src/test/java/com/robsartin/segue/evaluate/InventedEvaluation.java`. Edit `src/main/java/com/robsartin/segue/evaluate/Scoring.java`, `src/test/java/com/robsartin/segue/evaluate/ScoringTest.java`.

The harness runs **one** sweep per setting and reads two rankings out of it. That is only legitimate if filtering the suppressed candidates out *after* the sweep gives the same ranking as excluding them *before* it. ADR 50 measured that on the real graph; this pins it here, against a real second sweep, and the same test is the red that drives the filter into `Scoring`.

- [ ] **Step 1 — write the fixture.** Create `InventedEvaluation.java`, a graph where a rated-down entity outranks a held-out one:

```java
package com.robsartin.segue.evaluate;

import com.robsartin.segue.domain.AssertionRecord;
import com.robsartin.segue.domain.EdgeTypes;
import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.domain.NodeRecord;
import com.robsartin.segue.domain.Provenance;
import com.robsartin.segue.tinker.TinkerGraphStore;
import java.time.Instant;
import java.util.List;

/**
 * An invented neighbourhood for the harness: two of "your" acts, three intermediates, and three
 * candidates — one you would be recommended, one you rated down, one held out.
 *
 * <p>Every id carries ADR 58's leading zero, so no future Wikidata allocation can give it a
 * referent, and nothing here comes from anybody's graph or anybody's list (ADR 33, issue #37).
 */
final class InventedEvaluation {

  static final String KNOWN_ONE = "Q0900411";
  static final String KNOWN_TWO = "Q0900412";

  static final String VIA_ONE = "Q0900421";
  static final String VIA_TWO = "Q0900422";
  static final String VIA_THREE = "Q0900423";

  /** Never rated: the ordinary candidate. */
  static final String STRANGER = "Q0900431";

  /** Rated down, so a shipped run suppresses it. */
  static final String REJECTED = "Q0900432";

  /** Rated highly and absent from the file, so the split can hide it. */
  static final String HIDDEN = "Q0900433";

  private static final Instant WHEN = Instant.parse("2026-01-01T00:00:00Z");

  private InventedEvaluation() {}

  static Provenance sourced() {
    return new Provenance("invented", "invented:1", WHEN, 1.0);
  }

  static void node(TinkerGraphStore graph, String qid, NodeKind kind) {
    graph.upsertNode(new NodeRecord(qid, kind, "an invented act " + qid, List.of()));
  }

  static void edge(TinkerGraphStore graph, String from, String to) {
    graph.record(new AssertionRecord(from, to, EdgeTypes.INFLUENCED_BY.code(), null, null, sourced()));
  }

  /** Pad a node out to {@code degree} edges with works, which are never candidates. */
  static void padDegreeTo(TinkerGraphStore graph, String qid, int degree) {
    int already = graph.edges(qid).size();
    for (int i = already; i < degree; i++) {
      String filler = "Q090049" + Math.abs((qid + i).hashCode() % 100000);
      node(graph, filler, NodeKind.WORK);
      graph.record(
          new AssertionRecord(qid, filler, EdgeTypes.PERFORMED.code(), null, null, sourced()));
    }
  }

  /**
   * Two of yours, three intermediates, three candidates — each candidate reached by both of yours
   * through a different intermediate, so their relative order is decided by degree alone.
   */
  static TinkerGraphStore graph() {
    TinkerGraphStore graph = new TinkerGraphStore();
    for (String qid : List.of(KNOWN_ONE, KNOWN_TWO, VIA_ONE, VIA_TWO, VIA_THREE)) {
      node(graph, qid, NodeKind.GROUP);
    }
    for (String qid : List.of(STRANGER, REJECTED, HIDDEN)) {
      node(graph, qid, NodeKind.GROUP);
    }
    for (String via : List.of(VIA_ONE, VIA_TWO, VIA_THREE)) {
      edge(graph, KNOWN_ONE, via);
      edge(graph, KNOWN_TWO, via);
    }
    edge(graph, STRANGER, VIA_ONE);
    edge(graph, REJECTED, VIA_TWO);
    edge(graph, HIDDEN, VIA_THREE);
    // Distinct degrees, so lift orders them REJECTED, STRANGER, HIDDEN and the order is stable.
    padDegreeTo(graph, REJECTED, 5);
    padDegreeTo(graph, STRANGER, 6);
    padDegreeTo(graph, HIDDEN, 7);
    return graph;
  }
}
```

  **Verify the intended order before relying on it**: add a throwaway assertion or a temporary print that names the ranked qids at floor 5 under `Scorer.LIFT`, confirm it is `REJECTED, STRANGER, HIDDEN`, then delete the scaffolding. If the real order differs, adjust the padding rather than the assertions below.

- [ ] **Step 2 — write the failing test.** Create `SuppressionIsPurelySubtractiveTest.java`:

```java
package com.robsartin.segue.evaluate;

import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.segue.domain.Recommendations;
import com.robsartin.segue.domain.Scorer;
import com.robsartin.segue.recommend.CandidateSweep;
import com.robsartin.segue.recommend.Sweep;
import com.robsartin.segue.tinker.TinkerGraphStore;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The licence for running <b>one</b> sweep per setting instead of two (ADR 65).
 *
 * <p>The harness withholds suppression so the rated-down entities can be ranked, then filters them
 * out to read the held-out ones. That is only the shipped ranking if excluding a candidate from the
 * pool is purely subtractive — no surviving candidate's score or relative order moves. ADR 50
 * measured exactly that on the real graph; this holds it here, against a real second sweep, so the
 * claim is a test rather than a paragraph.
 *
 * <p>Every id, label and edge is invented.
 */
class SuppressionIsPurelySubtractiveTest {

  private static final Setting SETTING = new Setting(Scorer.LIFT, 5);
  private static final int TOP = 25;

  @Test
  @DisplayName("filtering the rated-down out after the sweep ranks the rest exactly as suppressing them before it")
  void shouldRankTheSurvivorsIdenticallyWhenSuppressionIsAppliedAfterTheSweepRatherThanBefore() {
    try (TinkerGraphStore graph = InventedEvaluation.graph()) {
      CandidateSweep sweep = new CandidateSweep(graph, qid -> false);
      List<String> known = List.of(InventedEvaluation.KNOWN_ONE, InventedEvaluation.KNOWN_TWO);
      Set<String> rejected = Set.of(InventedEvaluation.REJECTED);

      Sweep withheld = sweep.over(known, Set.of(), SETTING.scorer(), SETTING.floor(), Recommendations.EQUAL_REGARD);
      Sweep suppressed =
          sweep.over(known, rejected, SETTING.scorer(), SETTING.floor(), Recommendations.EQUAL_REGARD);

      assertThat(qidsOf(withheld, rejected))
          .as("the pool the harness filters, against the pool the recommender would have ranked")
          .isEqualTo(
              Recommendations.rank(suppressed.candidates(), TOP).stream()
                  .map(candidate -> candidate.entity().qid())
                  .toList());
    }
  }

  @Test
  @DisplayName("the held-out entity's rank is read over the shipped pool, not the pool with the rated-down in it")
  void shouldRankTheHeldOutEntityOverTheShippedPoolWhenARatedDownEntityOutranksIt() {
    try (TinkerGraphStore graph = InventedEvaluation.graph()) {
      Sweep withheld =
          new CandidateSweep(graph, qid -> false)
              .over(
                  List.of(InventedEvaluation.KNOWN_ONE, InventedEvaluation.KNOWN_TWO),
                  Set.of(),
                  SETTING.scorer(),
                  SETTING.floor(),
                  Recommendations.EQUAL_REGARD);

      Reading reading =
          Scoring.read(
              withheld,
              SETTING,
              Set.of(InventedEvaluation.HIDDEN),
              Set.of(InventedEvaluation.REJECTED),
              TOP);

      assertThat(reading.pool())
          .as("the pool the report states is the one the recommender would have ranked")
          .isEqualTo(2);
      assertThat(reading.meanHitRank())
          .as("rank 2 of the two survivors, not rank 3 of the three the sweep returned")
          .hasValue(2.0);
      assertThat(reading.meanNegativeRank())
          .as("the rated-down entity is still read over the whole pool — that is its whole point")
          .hasValue(1.0);
    }
  }

  private static List<String> qidsOf(Sweep sweep, Set<String> removed) {
    return Recommendations.rank(
            sweep.candidates().stream()
                .filter(candidate -> !removed.contains(candidate.entity().qid()))
                .toList(),
            TOP)
        .stream()
        .map(candidate -> candidate.entity().qid())
        .toList();
  }
}
```

- [ ] **Step 3 — run it and observe the red.** `./gradlew test --tests 'com.robsartin.segue.evaluate.SuppressionIsPurelySubtractiveTest'`. The first test passes (that is the property, and it already holds); **the second fails** on `pool()` being 3 rather than 2 and `meanHitRank()` being 3.0 rather than 2.0, because `Scoring` still ranks the positives over the whole pool. Quote both failures.

- [ ] **Step 4 — make it green.** In `Scoring.read`, rank the positives over the pool with the negatives removed, and report that pool:

```java
    List<Recommendation> shipped =
        sweep.candidates().stream().filter(in(negatives).negate()).toList();
    List<Recommendation> shippedTop = Recommendations.rank(shipped, top);
    List<Recommendation> withheldTop = Recommendations.rank(sweep.candidates(), top);
    List<Integer> hitRanks = ranksOf(shippedTop, heldOut);
    List<Integer> negativeRanks = ranksOf(withheldTop, negatives);

    return new Reading(
        setting,
        shipped.size(),
        (int) shipped.stream().filter(in(heldOut)).count(),
        hitRanks.size(),
        mean(hitRanks),
        negativeRanks.size(),
        mean(negativeRanks));
```

  and extend the class javadoc with the paragraph the equivalence rests on:

```java
 * <p><b>Two rankings out of one sweep, and the second is a claim with a test behind it.</b> The
 * rated-down entities are ranked over the whole pool — the ranking the owner would have been shown
 * had ADR 50 never been written. The held-out entities are ranked over the pool with those removed,
 * which reproduces the shipped ranking exactly, because excluding a candidate from the pool is
 * purely subtractive: {@code CandidateSweep.over} skips an excluded qid before it accumulates any
 * evidence, and no other candidate's evidence is built from it, so no survivor's score or relative
 * order moves. That is what ADR 50 measured on the real graph, and {@code
 * SuppressionIsPurelySubtractiveTest} pins it here against a real second sweep — without which this
 * paragraph would be reasoning rather than a guarantee, and sixteen sweeps would have to be
 * thirty-two.
```

- [ ] **Step 5 — repair `ScoringTest`.** Its four tests pass `Set.of()` for one population or the other, so none of them changes meaning; run it and confirm. If the `negativesOffered` test now reports a pool of 3 rather than 4, update that expectation and say so in the report.

- [ ] **Step 6 — gate and commit** the four files by explicit path.

---

### Task 5: `EvaluationReport` — the table

**Files:** create `src/main/java/com/robsartin/segue/evaluate/EvaluationReport.java`, `src/test/java/com/robsartin/segue/evaluate/EvaluationReportTest.java`.

No new import edge.

- [ ] **Step 1 — write the failing test.** Create `EvaluationReportTest.java`:

```java
package com.robsartin.segue.evaluate;

import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.segue.domain.Scorer;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class EvaluationReportTest {

  /** Anything qid-shaped at all, wherever it appears. */
  private static final Pattern A_QID = Pattern.compile("\\bQ\\d+\\b");

  private static final HeldOut SPLIT =
      new HeldOut(List.of("Q0900401", "Q0900406"), Map.of("Q0900402", 5), 10);

  @Test
  @DisplayName("the header names the split and the top, and the table has one row per reading")
  void shouldStateTheSplitAndOneRowPerReadingWhenTheReportIsRendered() {
    List<String> lines = EvaluationReport.lines(SPLIT, 25, List.of(reading(), sparse()));

    assertThat(lines.get(0)).isEqualTo(EvaluationReport.HEADER);
    assertThat(lines.get(1))
        .contains("10 eligible")
        .contains("2 held out")
        .contains("8 left on the known-list");
    assertThat(lines.get(2)).contains("top 25").contains("2 setting(s)");
    assertThat(lines).hasSize(3 + 1 + 2);
    assertThat(lines.get(3)).startsWith("scorer").contains("neg mean rank");
  }

  @Test
  @DisplayName("a mean is one decimal, and a mean over nothing is a literal dash")
  void shouldRenderADashWhenAMeanHasNothingToAverage() {
    List<String> lines = EvaluationReport.lines(SPLIT, 25, List.of(reading(), sparse()));

    assertThat(lines.get(4)).contains("7.5").contains("4.0");
    assertThat(lines.get(5))
        .as("no hits and no negatives — two dashes, never two zeroes")
        .contains(EvaluationReport.NO_MEAN);
  }

  @Test
  @DisplayName("every column lines up, because the widths come from the cells")
  void shouldAlignTheColumnsWhenACountIsWiderThanItsHeading() {
    Reading wide =
        new Reading(
            new Setting(Scorer.RAW, 2), 123456, 40, 12, OptionalDouble.of(9.25), 0,
            OptionalDouble.empty());

    List<String> lines = EvaluationReport.lines(SPLIT, 25, List.of(wide, sparse()));

    assertThat(lines.get(3).length())
        .as("the heading row is padded to the same width as every body row")
        .isEqualTo(lines.get(4).length())
        .isEqualTo(lines.get(5).length());
  }

  @Test
  @DisplayName("nothing qid-shaped reaches the report, whatever the split held")
  void shouldCarryNoIdentifierWhenTheSplitNamesEntities() {
    assertThat(EvaluationReport.lines(SPLIT, 25, List.of(reading())))
        .noneMatch(line -> A_QID.matcher(line).find());
  }

  private static Reading reading() {
    return new Reading(
        new Setting(Scorer.LIFT, 5), 900, 40, 4, OptionalDouble.of(7.5), 2, OptionalDouble.of(4.0));
  }

  private static Reading sparse() {
    return new Reading(
        new Setting(Scorer.RAW, 12), 40, 3, 0, OptionalDouble.empty(), 0, OptionalDouble.empty());
  }
}
```

- [ ] **Step 2 — stub, then a real red.** Create `EvaluationReport.java` returning `List.of(HEADER)`, run `./gradlew test --tests 'com.robsartin.segue.evaluate.EvaluationReportTest'`, quote the failure.

- [ ] **Step 3 — implement.** Replace `EvaluationReport.java`:

```java
package com.robsartin.segue.evaluate;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.OptionalDouble;

/**
 * Readings in, one aligned block of text out. A pure function, and the only class here that decides
 * what a person sees (ADR 65).
 *
 * <p><b>Every value is an integer, a fixed one-decimal, or the literal {@link #NO_MEAN}, and every
 * label is a literal in this file or a {@code Scorer} spelling.</b> That is what makes the whole
 * output safe to paste and what {@code EvaluationIsSafeToPasteTest} asserts — the same property
 * {@code CensusReport} has and ADR 63 argues for. No qid, label, note or rating value reaches this
 * method at all: {@link Reading} has nowhere to put one.
 *
 * <p><b>A mean over nothing is a dash rather than zero.</b> No hits and a mean rank of zero are
 * different facts, and a table that renders them the same is a table that misleads. One decimal
 * rather than a whole number because the point of the block is comparing its rows: at a top of 25 a
 * mean of 8 and a mean of 8.4 are a real difference.
 *
 * <p><b>The widths are derived from the cells</b>, exactly as {@code CensusReport} derives its
 * column, so a five-figure pool moves the column rather than jutting out of it and no number here
 * is a constant somebody has to keep.
 */
public final class EvaluationReport {

  /** Said on the first line, every time — what this is, and what it is not. */
  public static final String HEADER =
      "# segue recommender evaluation — aggregates only: no labels, no ids, no notes, no ratings"
          + " (ADR 51, ADR 63, ADR 65).";

  /** Printed where a mean has nothing to average. */
  public static final String NO_MEAN = "-";

  private static final List<String> COLUMNS =
      List.of(
          "scorer",
          "floor",
          "pool",
          "held out",
          "hits",
          "mean rank",
          "negatives",
          "neg mean rank");

  private static final String GAP = "  ";

  private EvaluationReport() {}

  /**
   * Render the whole block, header included.
   *
   * @param split what was hidden, for the three counts the header states
   * @param top how many candidates each setting was read over
   * @param readings one per setting, in the order they should be read
   */
  public static List<String> lines(HeldOut split, int top, List<Reading> readings) {
    Objects.requireNonNull(split, "split");
    Objects.requireNonNull(readings, "readings");

    List<List<String>> rows = new ArrayList<>();
    rows.add(COLUMNS);
    readings.forEach(reading -> rows.add(cells(reading)));
    int[] widths = widths(rows);

    List<String> rendered = new ArrayList<>();
    rendered.add(HEADER);
    rendered.add(
        "# held out every "
            + HeldOut.EVERY
            + " of "
            + split.eligible()
            + " eligible entity(ies): "
            + split.heldOut().size()
            + " held out, "
            + (split.eligible() - split.heldOut().size())
            + " left on the known-list.");
    rendered.add("# top " + top + " per setting, over " + readings.size() + " setting(s).");
    rows.forEach(row -> rendered.add(render(row, widths)));
    return List.copyOf(rendered);
  }

  private static List<String> cells(Reading reading) {
    return List.of(
        reading.setting().scorer().spelling(),
        String.valueOf(reading.setting().floor()),
        String.valueOf(reading.pool()),
        String.valueOf(reading.heldOutInPool()),
        String.valueOf(reading.hits()),
        mean(reading.meanHitRank()),
        String.valueOf(reading.negativesOffered()),
        mean(reading.meanNegativeRank()));
  }

  private static String mean(OptionalDouble value) {
    return value.isPresent() ? String.format(Locale.ROOT, "%.1f", value.getAsDouble()) : NO_MEAN;
  }

  private static int[] widths(List<List<String>> rows) {
    int[] widths = new int[COLUMNS.size()];
    for (List<String> row : rows) {
      for (int column = 0; column < widths.length; column++) {
        widths[column] = Math.max(widths[column], row.get(column).length());
      }
    }
    return widths;
  }

  /** The first column is a word and is left-aligned; every other is a number and is not. */
  private static String render(List<String> row, int[] widths) {
    StringBuilder line = new StringBuilder();
    for (int column = 0; column < widths.length; column++) {
      String cell = row.get(column);
      String padding = " ".repeat(widths[column] - cell.length());
      if (column > 0) {
        line.append(GAP);
      }
      line.append(column == 0 ? cell + padding : padding + cell);
    }
    return line.toString();
  }
}
```

- [ ] **Step 4 — green, gate, commit** the two files by explicit path.

---

### Task 6: `EvaluateRun` — compose, sweep once per setting, report

**Files:** create `src/main/java/com/robsartin/segue/evaluate/EvaluateRun.java`, `src/test/java/com/robsartin/segue/evaluate/EvaluateRunTest.java`. Edit `src/main/java/com/robsartin/segue/recommend/CandidateSweep.java`, `docs/developer-guide.md`.

Adds the edges `evaluate --> port` and `evaluate --> support`. **Two red→green loops:** the run itself, then the eligibility rule that forces `CandidateSweep.couldBeExplored` public.

`EvaluateRun` takes its options as a record it does not own yet, so this task introduces a minimal `EvaluateRun.Options`-shaped parameter list instead: `run(Path known, int top, Consumer<String> lines)`. Task 7 does **not** change it — `EvaluateCli` unpacks its own `Options` at the call site, which keeps the run free of the CLI's record and keeps this task's tests free of a parser.

- [ ] **Step 1 — write the first failing test.** Create `EvaluateRunTest.java`:

```java
package com.robsartin.segue.evaluate;

import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.segue.domain.Equivalences;
import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.domain.NodeRecord;
import com.robsartin.segue.tinker.TinkerGraphStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The run, over an invented graph and invented ratings. Nothing here comes from anybody's taste
 * layer (ADR 33, issue #37).
 */
class EvaluateRunTest {

  @TempDir private Path dir;

  @Test
  @DisplayName("one row per setting reaches the report, and the held-out entity is hidden from the sweep")
  void shouldReportOneRowPerSettingWhenTheRunSweepsTheGrid() throws IOException {
    try (TinkerGraphStore graph = InventedEvaluation.graph()) {
      List<String> lines = new ArrayList<>();

      List<Reading> readings =
          new EvaluateRun(
                  graph,
                  qid -> false,
                  Map.of(InventedEvaluation.HIDDEN, 5, InventedEvaluation.REJECTED, 1),
                  Equivalences.NONE)
              .run(knownList(), 25, lines::add);

      assertThat(readings).hasSameSizeAs(Setting.GRID);
      assertThat(lines).hasSize(3 + 1 + Setting.GRID.size());
      assertThat(lines.get(0)).isEqualTo(EvaluationReport.HEADER);
      assertThat(readings)
          .as("the one eligible entity was held out, so it is a candidate the sweep can return")
          .anyMatch(reading -> reading.hits() == 1);
      assertThat(readings)
          .as("the rated-down entity is in the pool, because suppression is withheld")
          .anyMatch(reading -> reading.negativesOffered() == 1);
    }
  }

  @Test
  @DisplayName("a highly rated entity the sweep could never offer is not counted as eligible")
  void shouldNotCountAnEntityAsEligibleWhenTheSweepCouldNotOfferItBack() throws IOException {
    try (TinkerGraphStore graph = InventedEvaluation.graph()) {
      // An institution: a GROUP by kind, and refused as a candidate by the class it states.
      graph.upsertNode(
          new NodeRecord("Q0900441", NodeKind.GROUP, "an invented academy", List.of("Q0900801")));
      List<String> lines = new ArrayList<>();

      new EvaluateRun(
              graph,
              "Q0900801"::equals,
              Map.of(InventedEvaluation.HIDDEN, 5, "Q0900441", 5),
              Equivalences.NONE)
          .run(knownList(), 25, lines::add);

      assertThat(lines.get(1))
          .as("one eligible entity, not two — an institution is never a candidate")
          .contains("1 eligible entity(ies)");
    }
  }

  private Path knownList() throws IOException {
    Path known = dir.resolve("known.csv");
    Files.writeString(
        known, InventedEvaluation.KNOWN_ONE + "\n" + InventedEvaluation.KNOWN_TWO + "\n");
    return known;
  }
}
```

- [ ] **Step 2 — write `EvaluateRun` with a kind-only eligibility rule, so the first test can go red for the right reason then green and the second stays red.** Create `EvaluateRun.java`:

```java
package com.robsartin.segue.evaluate;

import com.robsartin.segue.domain.Equivalences;
import com.robsartin.segue.domain.KnownList;
import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.domain.Recommendations;
import com.robsartin.segue.port.GraphStore;
import com.robsartin.segue.recommend.CandidateSweep;
import com.robsartin.segue.recommend.Sweep;
import com.robsartin.segue.support.QidList;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;

/**
 * Split, sweep the grid, report — in that order, and one sweep per setting (ADR 65).
 *
 * <p><b>Lines go to a {@link Consumer} rather than to a logger of this class's own</b>, so the whole
 * report is observable from a test and this class has no logger to misuse — {@code CensusRun}'s
 * discipline, and {@code RatingsRun}'s and {@code SqliteAffinityStore}'s before it.
 *
 * <p><b>There is no warning to say first.</b> {@code RecommendRun} warns because what the operator
 * does next is decide where to put a file of personal data. This produces no file and no personal
 * data: the header says what the output is, and that is the whole of it.
 *
 * <p><b>One map, three consumers.</b> {@link HeldOut} hands back the ratings with the held-out
 * entries removed, and the known-list, the regard function and the suppressed set are all built
 * from that one map — the same discipline {@code RecommendCli} keeps when it resolves the merges
 * once and hands the result to both {@code regardFor} and {@code KnownList.promoted}. Two views of
 * the taste layer inside one run is how a split stops meaning what it says.
 *
 * <p><b>The graph is booted once and one {@link CandidateSweep} is reused across the grid</b>, so
 * the replay is paid for once and the sweep's memoised degrees are paid for once. ADR 45's
 * consequences record what a single recommendation run costs against the real graph; do not
 * re-project per setting.
 *
 * <p><b>It reads and cannot write.</b> {@code ArchitectureTest.theEvaluationHarnessOnlyReads}
 * forbids this package the three world-fact writes, both taste-layer writes and {@code
 * IngestService}.
 */
public final class EvaluateRun {

  private final GraphStore graph;
  private final Predicate<String> recognitionInstitutionClass;
  private final Map<String, Integer> ratings;
  private final Equivalences merges;

  /**
   * @param ratings the note-free bulk read, already resolved through {@code Equivalences.resolve}
   * @param merges what the owner has merged — passed to the sweep as the only exclusion, because
   *     withholding {@code KnownList.suppressed} is the whole point and a retired local id is not a
   *     judgement the harness is measuring
   */
  public EvaluateRun(
      GraphStore graph,
      Predicate<String> recognitionInstitutionClass,
      Map<String, Integer> ratings,
      Equivalences merges) {
    this.graph = Objects.requireNonNull(graph, "graph");
    this.recognitionInstitutionClass =
        Objects.requireNonNull(recognitionInstitutionClass, "recognitionInstitutionClass");
    this.ratings = Objects.requireNonNull(ratings, "ratings");
    this.merges = Objects.requireNonNull(merges, "merges");
  }

  /**
   * Run the whole grid.
   *
   * @return the readings that were printed, so a caller can assert on the numbers without parsing
   *     the text back
   */
  public List<Reading> run(Path known, int top, Consumer<String> lines) throws IOException {
    Objects.requireNonNull(known, "known");
    Objects.requireNonNull(lines, "lines");

    List<String> fromFile = QidList.read(known);
    CandidateSweep sweep = new CandidateSweep(graph, recognitionInstitutionClass);
    HeldOut split =
        HeldOut.every(
            HeldOut.EVERY, ratings, new LinkedHashSet<>(fromFile), this::couldBeOffered);

    List<String> knownList = KnownList.promoted(fromFile, split.ratingsWithout());
    ToDoubleFunction<String> regard = Recommendations.regardFor(split.ratingsWithout());
    Set<String> negatives = KnownList.suppressed(split.ratingsWithout());
    Set<String> heldOut = Set.copyOf(split.heldOut());

    List<Reading> readings = new ArrayList<>();
    for (Setting setting : Setting.GRID) {
      // Suppression withheld on purpose: merges.merged() and nothing else, so the rated-down
      // entities are in the pool and can be ranked. Scoring filters them back out for the
      // held-out reading.
      Sweep swept =
          sweep.over(knownList, merges.merged(), setting.scorer(), setting.floor(), regard);
      readings.add(Scoring.read(swept, setting, heldOut, negatives, top));
    }

    EvaluationReport.lines(split, top, readings).forEach(lines);
    return List.copyOf(readings);
  }

  private boolean couldBeOffered(String qid) {
    return graph
        .node(qid)
        .map(node -> node.kind() == NodeKind.PERSON || node.kind() == NodeKind.GROUP)
        .orElse(false);
  }
}
```

- [ ] **Step 3 — run it.** `./gradlew test --tests 'com.robsartin.segue.evaluate.EvaluateRunTest'`. The first test should pass. **The second must fail**, reporting `2 eligible entity(ies)` where 1 was expected — quote it. If the first test is also red, fix it before proceeding; the second is the one that must stay red into step 4.

- [ ] **Step 4 — make `CandidateSweep.couldBeExplored` public and delegate to it.** In `src/main/java/com/robsartin/segue/recommend/CandidateSweep.java`, change `private boolean couldBeExplored(String qid)` to `public boolean couldBeExplored(String qid)` and add to that method's javadoc:

```java
   * <p><b>Public since issue #239, and for the reason {@code PathRanking.isHub} is.</b> The
   * evaluation harness holds out entities it must be able to offer back, so its eligibility rule
   * and this one have to be the same sentence. One implementation, two readings — a second copy
   * would agree until the day somebody changed one of them.
```

  Then in `EvaluateRun`, drop the `NodeKind` import and replace `couldBeOffered` with a delegation. The sweep is already constructed before the split, which is why it is built where it is:

```java
    HeldOut split =
        HeldOut.every(HeldOut.EVERY, ratings, new LinkedHashSet<>(fromFile), sweep::couldBeExplored);
```

  and delete the private `couldBeOffered` method.

- [ ] **Step 5 — green.** Re-run the fast loop; both tests pass. Also run `./gradlew test --tests 'com.robsartin.segue.recommend.*'` to confirm the visibility change moved nothing in the recommender.

- [ ] **Step 6 — the guide edges.** Add `  evaluate --> port` and `  evaluate --> support` to the diagram.

- [ ] **Step 7 — gate and commit** `EvaluateRun.java`, `EvaluateRunTest.java`, `CandidateSweep.java`, `docs/developer-guide.md` by explicit path.

---

### Task 7: `EvaluateCli`, the Gradle task, and the ninth dev tool — one commit

**Files:** create `src/main/java/com/robsartin/segue/evaluate/EvaluateCli.java`, `src/test/java/com/robsartin/segue/evaluate/EvaluateCliTest.java`. Edit `build.gradle.kts`, `src/test/java/com/robsartin/segue/arch/ArchitectureTest.java` (`DEV_TOOL_PACKAGES` only), `docs/developer-guide.md`.

**These edits must travel together and the plan says so out loud.** `PackageListsTest` derives the dev-tool set three ways — from `build.gradle.kts`, from every `*Cli` class declaring a `main`, and from `ArchitectureTest.DEV_TOOL_PACKAGES` — and asserts all three equal. `DeveloperGuideEnumerationsTest` holds the guide's "…are the N dev-side tools" sentence to the Gradle derivation. A commit carrying any one of them alone is red.

Adds the edges `evaluate --> ingest`, `evaluate --> sqlite`, `evaluate --> tinker`, `evaluate --> wikidata`.

- [ ] **Step 1 — write the failing test.** Create `EvaluateCliTest.java`:

```java
package com.robsartin.segue.evaluate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.robsartin.segue.domain.AffinityRecord;
import com.robsartin.segue.domain.AssertionRecord;
import com.robsartin.segue.domain.EdgeTypes;
import com.robsartin.segue.domain.NodeAssertion;
import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.recommend.RecommendCli;
import com.robsartin.segue.sqlite.SqliteAffinityStore;
import com.robsartin.segue.sqlite.SqliteAssertionLog;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The parser's refusals, and the whole tool driven once end to end over a {@code @TempDir}
 * database. Every id, label, note and rating is invented (ADR 33, issue #37).
 */
class EvaluateCliTest {

  private static final String INVENTED_HOME = "/home/invented";

  @TempDir private Path dir;

  @Test
  @DisplayName("--db is required, and SEGUE_DB does not satisfy it")
  void shouldRefuseTheRunWhenTheDatabaseFlagIsAbsent() {
    assertThatThrownBy(
            () ->
                EvaluateCli.run(
                    new String[] {"--known", "/nowhere/known.csv"},
                    "/somewhere/else/segue.db",
                    INVENTED_HOME))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("--db");
  }

  @Test
  @DisplayName("a missing --db is refused before a missing file is, so the message names the flag")
  void shouldNameTheFlagRatherThanAPathWhenNeitherWasGiven() {
    assertThatThrownBy(() -> EvaluateCli.run(new String[] {}, null, INVENTED_HOME))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageNotContaining("no segue database at");
  }

  @Test
  @DisplayName("--known is required, because a held-out run needs a list to hold out of")
  void shouldRefuseTheRunWhenTheKnownListIsAbsent() {
    assertThatThrownBy(
            () ->
                EvaluateCli.run(
                    new String[] {"--db", dir.resolve("scratch.db").toString()}, null, INVENTED_HOME))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("--known");
  }

  @Test
  @DisplayName("a database that is not there is refused rather than created empty")
  void shouldRefuseTheRunWhenTheDatabaseDoesNotExist() throws IOException {
    Path known = dir.resolve("known.csv");
    Files.writeString(known, InventedEvaluation.KNOWN_ONE + "\n");

    assertThatThrownBy(
            () ->
                EvaluateCli.run(
                    new String[] {
                      "--db", dir.resolve("absent.db").toString(), "--known", known.toString()
                    },
                    null,
                    INVENTED_HOME))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("nothing to evaluate");
    assertThat(dir.resolve("absent.db")).doesNotExist();
  }

  @Test
  @DisplayName("--top defaults to the recommender's own, and a number below one is refused")
  void shouldDefaultToTheRecommendersTopWhenNoneIsGiven() {
    Path db = dir.resolve("scratch.db");

    assertThat(
            EvaluateCli.parse(
                    new String[] {"--db", db.toString(), "--known", "/nowhere/known.csv"},
                    null,
                    INVENTED_HOME)
                .top())
        .isEqualTo(RecommendCli.DEFAULT_TOP);
    assertThatThrownBy(
            () ->
                EvaluateCli.parse(
                    new String[] {
                      "--db", db.toString(), "--known", "/nowhere/known.csv", "--top", "0"
                    },
                    null,
                    INVENTED_HOME))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("--top");
  }

  @Test
  @DisplayName("the whole tool runs against a real database and prints the whole table")
  void shouldPrintTheWholeTableWhenTheToolIsRunEndToEnd() throws IOException {
    Path db = graphOnDisk();
    Path known = dir.resolve("known.csv");
    Files.writeString(
        known, InventedEvaluation.KNOWN_ONE + "\n" + InventedEvaluation.KNOWN_TWO + "\n");

    // No assertion on the output here beyond "it did not throw": the report's content is
    // EvaluationReportTest's, and what nothing else covers is that main() wires a real log, a real
    // affinity table and a real replay together.
    EvaluateCli.main(new String[] {"--db", db.toString(), "--known", known.toString()});
  }

  /** The same neighbourhood {@code InventedEvaluation} builds, written to a log instead. */
  private Path graphOnDisk() {
    Path db = dir.resolve("scratch.db");
    Instant when = Instant.parse("2026-01-01T00:00:00Z");
    try (SqliteAssertionLog log = new SqliteAssertionLog(db);
        SqliteAffinityStore affinity = new SqliteAffinityStore(db)) {
      for (String qid :
          java.util.List.of(
              InventedEvaluation.KNOWN_ONE,
              InventedEvaluation.KNOWN_TWO,
              InventedEvaluation.VIA_ONE,
              InventedEvaluation.STRANGER,
              InventedEvaluation.HIDDEN)) {
        log.append(
            new NodeAssertion(qid, NodeKind.GROUP, "an invented act", InventedEvaluation.sourced()));
      }
      edge(log, InventedEvaluation.KNOWN_ONE, InventedEvaluation.VIA_ONE);
      edge(log, InventedEvaluation.KNOWN_TWO, InventedEvaluation.VIA_ONE);
      edge(log, InventedEvaluation.STRANGER, InventedEvaluation.VIA_ONE);
      edge(log, InventedEvaluation.HIDDEN, InventedEvaluation.VIA_ONE);
      affinity.put(
          new AffinityRecord(InventedEvaluation.HIDDEN, 5, "an invented note", when));
    }
    return db;
  }

  private static void edge(SqliteAssertionLog log, String from, String to) {
    log.append(
        new AssertionRecord(
            from, to, EdgeTypes.INFLUENCED_BY.code(), null, null, InventedEvaluation.sourced()));
  }
}
```

  `InventedEvaluation` is package-private today; leave it so — this test is in the same package.

- [ ] **Step 2 — stub `EvaluateCli` so the failure is an assertion.** A `parse` that returns a fixed `Options` and a `run` that does nothing is enough. Run `./gradlew test --tests 'com.robsartin.segue.evaluate.EvaluateCliTest'` and quote the failure.

- [ ] **Step 3 — implement.** Replace `EvaluateCli.java`:

```java
package com.robsartin.segue.evaluate;

import com.robsartin.segue.domain.Equivalences;
import com.robsartin.segue.ingest.GraphProjector;
import com.robsartin.segue.port.IdentityMerge;
import com.robsartin.segue.recommend.RecommendCli;
import com.robsartin.segue.sqlite.SqliteAffinityStore;
import com.robsartin.segue.sqlite.SqliteAssertionLog;
import com.robsartin.segue.support.RequiredDatabase;
import com.robsartin.segue.tinker.TinkerGraphStore;
import com.robsartin.segue.wikidata.RecognitionInstitutions;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The entry point, run from Gradle: {@code ./gradlew evaluate --args="--db … --known …"} (ADR 65).
 *
 * <p><b>The second dev-side tool whose whole output is aggregates</b>, and it is held to that
 * mechanically for the reason ADR 63 gives: every value the report emits is an integer or a fixed
 * decimal, and every label is a literal, so {@code EvaluationIsSafeToPasteTest} can assert the
 * property over the shape of the text rather than over what any name means. The claim is over the
 * report and not over a failed run — a refusal below names the path it was given, and an exception
 * out of an adapter prints a stack trace like any other tool's.
 *
 * <p><b>{@code --db} is required, and {@code SEGUE_DB} does not satisfy it.</b> ADR 60's central
 * clause, reached from ADR 63's direction: an agent's shell is initialised from the owner's profile
 * and inherits the variable, and this output is a reading of the owner's whole taste layer.
 * Producing it is the owner's decision per invocation, and the number that comes out is evidence —
 * it gets pasted into an issue and quoted in an ADR, where a wrong export is discarded and a wrong
 * measurement becomes the record.
 *
 * <p><b>No {@code --out}, and no {@code System.out}.</b> {@code
 * ArchitectureTest.nothingWritesToStandardOut} bans stdout project-wide (ADR 28, ADR 30), so the
 * table goes through SLF4J at {@code info}, one call per line — and there is nothing here a log
 * line may not carry. Nor does it say which database it read.
 *
 * <p><b>It reads ratings and cannot read a note</b>, exactly as {@code RecommendCli} does and under
 * a fence of the same shape: {@code theEvaluationHarnessReadsRatingsAndNeverNotes}. This is the only
 * class in the package that touches the store.
 */
public final class EvaluateCli {

  private static final Logger log = LoggerFactory.getLogger(EvaluateCli.class);

  private static final String USAGE =
      "usage: --db <segue.db> --known <file of QIDs> [--top <n>, default "
          + RecommendCli.DEFAULT_TOP
          + "]";

  private EvaluateCli() {}

  /**
   * What to measure against, and how deep to read.
   *
   * @param database no default, on purpose — see this class's javadoc, and {@code
   *     support.RequiredDatabase}, which owns the refusal sentence
   * @param known the entities the owner already has, the same file {@code recommend} takes
   * @param top how many candidates each setting is read over. Defaults to {@code
   *     RecommendCli.DEFAULT_TOP} by reference, so the harness measures the list length the tool
   *     actually shows
   */
  public record Options(Path database, Path known, int top) {

    public Options {
      Objects.requireNonNull(database, "database");
      Objects.requireNonNull(known, "known");
    }
  }

  /** Parse and validate, refusing anything that could not work before a store is opened. */
  static Options parse(String[] args, String envDatabase, String userHome) {
    Path database = null;
    Path known = null;
    int top = RecommendCli.DEFAULT_TOP;

    for (int i = 0; i < args.length; i++) {
      String flag = args[i];
      String value = valueOf(args, i, flag);
      i++;
      switch (flag) {
        case "--db" -> database = Path.of(value);
        case "--known" -> known = Path.of(value);
        case "--top" -> top = number(flag, value);
        default -> throw usage("unknown option " + flag);
      }
    }

    if (database == null) {
      throw usage(RequiredDatabase.refusal(envDatabase, userHome));
    }
    if (known == null) {
      throw usage("--known is required: a held-out run needs the list it is holding out of");
    }
    if (top < 1) {
      throw usage("--top must be at least 1");
    }
    return new Options(database, known, top);
  }

  private static int number(String flag, String value) {
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException e) {
      throw usage(flag + " takes a whole number, got " + value);
    }
  }

  private static String valueOf(String[] args, int i, String flag) {
    if (i + 1 >= args.length) {
      throw usage(flag + " needs a value");
    }
    return args[i + 1];
  }

  private static IllegalArgumentException usage(String problem) {
    String sentence = problem.endsWith(".") ? problem : problem + ".";
    return new IllegalArgumentException(sentence + " " + USAGE);
  }

  public static void main(String[] args) {
    run(args, System.getenv("SEGUE_DB"), System.getProperty("user.home"));
  }

  /**
   * {@code main}, with the two environment reads passed in.
   *
   * <p>A seam for {@code CensusCli.run}'s reason: the order of the two refusals is the behaviour. A
   * missing {@code --db} has to be refused by {@link #parse} before {@code Files.exists} is
   * reached, or the operator is told "no segue database at …" — which reads as a missing file
   * rather than a missing flag, and names a path they never typed.
   */
  static void run(String[] args, String envDatabase, String userHome) {
    Options options = parse(args, envDatabase, userHome);

    // Refuse a database that is not there rather than creating an empty one and measuring nothing:
    // both sqlite constructors create the file and its schema if absent, which is right for a
    // server starting fresh and wrong for a tool whose whole job is to read.
    if (!Files.exists(options.database())) {
      throw new IllegalArgumentException(
          "no segue database at " + options.database() + " — nothing to evaluate");
    }

    try (SqliteAssertionLog assertions = new SqliteAssertionLog(options.database());
        SqliteAffinityStore affinity = new SqliteAffinityStore(options.database());
        TinkerGraphStore graph = new TinkerGraphStore()) {
      long applied = GraphProjector.project(assertions, graph, IdentityMerge.NONE);
      log.info("replayed {} assertion(s)", applied);

      // Resolved through the merges before anything downstream sees it, exactly as RecommendCli
      // does and for the same reason: a merge leaves two affinity rows naming one thing, and a
      // split that counted both would hold out one id and leave the other in the known-list.
      Equivalences merges = Equivalences.in(assertions.readAll());
      Map<String, Integer> ratings = merges.resolve(affinity.readRatings());
      // A count, never a qid and never a score.
      log.info("read {} rating(s)", ratings.size());

      new EvaluateRun(
              graph, RecognitionInstitutions::isRecognitionInstitution, ratings, merges)
          .run(options.known(), options.top(), log::info);
    } catch (IOException e) {
      throw new UncheckedIOException("could not read " + options.known(), e);
    }
  }
}
```

- [ ] **Step 4 — the Gradle task.** In `build.gradle.kts`, after the `graphCensus` block, add — column-zero `tasks.register<JavaExec>("evaluate") {`, closing brace on its own line at column zero, exactly one `mainClass` line spelled out in full, because `PackageListsTest` parses this shape and fails on anything else:

```kotlin
tasks.register<JavaExec>("evaluate") {
    group = "application"
    description =
        "Holds out a deterministic slice of the PERSON and GROUP entities you rated highly, runs " +
            "the recommender's own candidate sweep from what is left over a fixed grid of scorers " +
            "and degree floors (`Setting.GRID` is the authority), and reports where the held-out " +
            "entities land and where the ones you rated down would have. Aggregates only — no " +
            "labels, no ids, no notes, no ratings — so the output is safe to paste. Reads only; " +
            "needs no network. Changes no constant. See ADR 65. --db is required, and SEGUE_DB " +
            "does not satisfy it. Write \$HOME and not ~ — a tilde does not expand inside double " +
            "quotes. Example: ./gradlew evaluate --args=\"--db \$HOME/.segue/segue.db --known " +
            "\$HOME/known.csv\""
    mainClass.set("com.robsartin.segue.evaluate.EvaluateCli")
    classpath = sourceSets["main"].runtimeClasspath
    // sqlite-jdbc loads a native library, the same grant tasks.test makes.
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    // The whole graph is replayed into memory, and a real one is six figures of assertions.
    maxHeapSize = "4g"
    // Never up-to-date: the graph changes under it, and the point is to measure it now.
    outputs.upToDateWhen { false }
}
```

- [ ] **Step 5 — `DEV_TOOL_PACKAGES`.** In `ArchitectureTest`, add `"evaluate"` in alphabetical position:

```java
  static final List<String> DEV_TOOL_PACKAGES =
      List.of(
          "census", "evaluate", "export", "own", "rate", "ratings", "recommend", "retract",
          "seed");
```

  This automatically bans `..evaluate..` from every other tool's `otherDevToolsAnd` fence. Nothing in `src/main` imports it, so those stay green; if any goes red, that is a real finding.

- [ ] **Step 6 — the guide's dev-tool sentence and bullet.** In `## The layering`:
  - the sentence becomes `` `seed`, `export`, `ratings`, `retract`, `recommend`, `rate`, `own`, `census` and `evaluate` are the nine dev-side tools. None is `` — the count word is checked and must read `nine`;
  - add a bullet after the `census` one:

```
- **`evaluate` reaches `sqlite`, `tinker`, `ingest`, `wikidata`, `support` and `recommend`, and is
  the only dev-side tool that measures another one.** It replays the log once, hides a deterministic
  fifth of what you rated highly, and runs `recommend`'s own `CandidateSweep` from what is left,
  once per setting on a fixed grid — the third dependency between dev tools, after `rate → recommend`
  and `census → export`, and deliberate for the same reason: a harness with a sweep of its own would
  answer a question about itself. It writes nothing, and `--db` is required
  ([ADR 65](adr/0065-an-offline-evaluation-harness-for-the-recommender.md)).
```

  - add the four remaining diagram edges: `  evaluate --> ingest`, `  evaluate --> sqlite`, `  evaluate --> tinker`, `  evaluate --> wikidata`.

- [ ] **Step 7 — gate and commit.** `./gradlew spotlessApply`, then the full gate blocking. Commit `EvaluateCli.java`, `EvaluateCliTest.java`, `build.gradle.kts`, `ArchitectureTest.java`, `docs/developer-guide.md` by explicit path, together.

---

### Task 8: the safe-to-paste guard, with its planted control

**Files:** create `src/test/java/com/robsartin/segue/evaluate/EvaluationIsSafeToPasteTest.java`.

- [ ] **Step 1 — write the guard**, copied from `CensusIsSafeToPasteTest` and extended with a rating value:

```java
package com.robsartin.segue.evaluate;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.robsartin.segue.domain.AffinityRecord;
import com.robsartin.segue.domain.AssertionRecord;
import com.robsartin.segue.domain.EdgeTypes;
import com.robsartin.segue.domain.NodeAssertion;
import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.sqlite.SqliteAffinityStore;
import com.robsartin.segue.sqlite.SqliteAssertionLog;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ADR 51's line, held by a test rather than by review, for the second artefact where it can be.
 *
 * <p>The sibling of {@code CensusIsSafeToPasteTest}, and it exists for the same reason with a
 * different subject and one more thing to hide. ADR 51 says no test can hold its rule and gives two
 * reasons — the framing decides whether a QID is a citation or a disclosure, and a test would have
 * to read the private store to know which entities are the owner's. Neither reaches this output:
 * there is no framing to judge, because every value is an integer, a fixed decimal or a literal in
 * {@code EvaluationReport}; and there is nothing to look up, because the assertion is over the
 * shape of the text.
 *
 * <p>The fixture carries a label, a note, a {@code Q} id inside that note and a <b>rating</b> — the
 * fourth is this tool's own hazard, since a harness over the taste layer is the one tool with a
 * reason to print a score. The capture is at TRACE so sqlite-jdbc's own statement logging is
 * included, which is how {@code RatingsAreNeverLoggedTest} found the driver logging SQL.
 *
 * <p>It is a guard rather than a behaviour, so its evidence is a planted leak seen to fire.
 */
class EvaluationIsSafeToPasteTest {

  /** Anything qid-shaped at all, wherever it appears. */
  private static final Pattern A_QID = Pattern.compile("\\bQ\\d+\\b");

  private static final String LABEL = "A Label Unlike Anything Real";
  private static final String NOTE = "an invented note that names Q0900901 and nothing else";

  @TempDir private Path dir;

  private Logger rootLogger;
  private Level originalLevel;
  private ListAppender<ILoggingEvent> captured;

  @BeforeEach
  void setUp() {
    captured = new ListAppender<>();
    captured.start();
    rootLogger = (Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    originalLevel = rootLogger.getLevel();
    rootLogger.setLevel(Level.TRACE);
    rootLogger.addAppender(captured);
  }

  @AfterEach
  void tearDown() {
    rootLogger.detachAppender(captured);
    rootLogger.setLevel(originalLevel);
  }

  @Test
  @DisplayName("the whole table reaches the log, and no label, note, id or rating reaches it with them")
  void shouldEmitCountsAndNothingElseWhenTheGraphHoldsALabelANoteAnIdAndARating() throws IOException {
    Path db = dir.resolve("scratch.db");
    Path known = dir.resolve("known.csv");
    Files.writeString(known, InventedEvaluation.KNOWN_ONE + "\n");
    try (SqliteAssertionLog log = new SqliteAssertionLog(db);
        SqliteAffinityStore affinity = new SqliteAffinityStore(db)) {
      log.append(
          new NodeAssertion(
              InventedEvaluation.KNOWN_ONE, NodeKind.GROUP, LABEL, InventedEvaluation.sourced()));
      log.append(
          new NodeAssertion(
              InventedEvaluation.VIA_ONE, NodeKind.GROUP, LABEL, InventedEvaluation.sourced()));
      log.append(
          new NodeAssertion(
              InventedEvaluation.HIDDEN, NodeKind.GROUP, LABEL, InventedEvaluation.sourced()));
      log.append(
          new AssertionRecord(
              InventedEvaluation.KNOWN_ONE,
              InventedEvaluation.VIA_ONE,
              EdgeTypes.INFLUENCED_BY.code(),
              null,
              null,
              InventedEvaluation.sourced()));
      log.append(
          new AssertionRecord(
              InventedEvaluation.HIDDEN,
              InventedEvaluation.VIA_ONE,
              EdgeTypes.INFLUENCED_BY.code(),
              null,
              null,
              InventedEvaluation.sourced()));
      affinity.put(
          new AffinityRecord(
              InventedEvaluation.HIDDEN, 5, NOTE, Instant.parse("2026-02-01T08:00:00Z")));
    }
    captured.list.clear();

    EvaluateCli.main(new String[] {"--db", db.toString(), "--known", known.toString()});

    List<String> everyLine =
        List.copyOf(captured.list).stream().map(ILoggingEvent::getFormattedMessage).toList();

    assertThat(everyLine)
        .as("the table was actually printed — without this the assertions below are vacuous")
        .contains(EvaluationReport.HEADER)
        .anyMatch(line -> line.startsWith("lift"));
    assertThat(everyLine)
        .as("no line carries a label (ADR 51, ADR 63, ADR 65)")
        .noneMatch(line -> line.contains(LABEL));
    assertThat(everyLine)
        .as("no line carries a note (ADR 33, ADR 51)")
        .noneMatch(line -> line.contains(NOTE));
    assertThat(everyLine)
        .as(
            "no line carries anything qid-shaped, wherever it came from — a label, a note, or an"
                + " edge type code that turned out to look like an entity")
        .noneMatch(line -> A_QID.matcher(line).find());
  }
}
```

  If the "actually printed" clause is red because the ranked rows do not begin with `lift`, adjust it to the spelling `Scorer.values()[0]` actually produces rather than weakening the clause.

- [ ] **Step 2 — the positive control, and it is the point of this task.** `System.out` is banned project-wide (`nothingWritesToStandardOut`), so the plant is a **log line carrying a label**. Temporarily, in `EvaluateRun.run`, immediately before `EvaluationReport.lines(...)`:

```java
      // PLANTED LEAK — remove after observing the red.
      lines.accept("leaked: " + graph.node(split.heldOut().get(0)).orElseThrow().label());
```

  Run `./gradlew test --tests 'com.robsartin.segue.evaluate.EvaluationIsSafeToPasteTest'`. It **must** fail on the label clause — record which clauses fired and quote the message. **Then remove the plant** and re-run green. A guard that has never been seen red is an inert fence.

- [ ] **Step 3 — a second plant, for the qid clause.** Replace the plant with `lines.accept("leaked: " + split.heldOut().get(0));`, observe the `\bQ\d+\b` clause fire, quote it, remove, re-run green.

- [ ] **Step 4 — gate and commit** the one test file by explicit path. Confirm `git status` shows no residue of either plant.

---

### Task 9: the ArchUnit fences, each with a planted control

**Files:** edit `src/test/java/com/robsartin/segue/arch/ArchitectureTest.java`, `docs/developer-guide.md`.

Five rules: four new, one widened. The guide's rule table is derived from the declared `@ArchTest` fields, so its rows land in the same commit.

- [ ] **Step 1 — widen `onlyTheRecommenderReadsEveryRating`.** Change the package list to `resideOutsideOfPackages("..recommend..", "..rate..", "..census..", "..evaluate..")`, extend the `because(...)` to name four dev-side tools, and add a paragraph to its javadoc:

```java
   * <p>Widened again by issue #239 (ADR 65): the evaluation harness splits, weights and reports by
   * the same note-free map, and holding it off {@code readRatings} would mean re-deriving the
   * owner's ratings from somewhere else. All four readers are dev-side tools off the MCP surface,
   * so the thing this rule actually protects — ADR 26's six tools — is unchanged.
```

- [ ] **Step 2 — add the four new rules**, beside the recommender's own so a reader meets them together:

```java
  /**
   * ADR 65: measuring the recommender is a read.
   *
   * <p>The same fence {@link #theRecommenderOnlyReads} carries, on the tool that measures it. A
   * harness that could write would be able to change the thing it is reporting on, which is the one
   * failure mode a calibration tool cannot have.
   */
  @ArchTest
  static final ArchRule theEvaluationHarnessOnlyReads =
      noClasses()
          .that()
          .resideInAPackage("..evaluate..")
          .should(
              ArchConditions.accessTargetWhere(
                      APPLIES_A_CLAIM
                          .or(callTo("put", AffinityStore.class))
                          .or(callTo("updateRating", AffinityStore.class)))
                  .or(
                      ArchConditions.dependOnClassesThat(
                          JavaClass.Predicates.equivalentTo(IngestService.class))))
          .because(
              "ADR 65: measuring the recommender is a read — the harness never appends to the log,"
                  + " never writes the graph, never writes a rating, and cannot reach the one class"
                  + " that is allowed to");

  /**
   * ADR 65: the harness needs a log, an engine and the recommender, and nothing else.
   *
   * <p><b>{@code recommend} is the permitted sibling, and it is the third such exception this
   * project has</b> — after {@code rate → recommend} (ADR 46) and {@code census → export} (ADR 63).
   * It is the whole design: the harness measures the shipped {@code CandidateSweep}, and a harness
   * with a walk of its own would answer a question about itself. It runs one way only —
   * {@link #theRecommenderOpensNothingElse} bans the return trip, over {@link #DEV_TOOL_PACKAGES},
   * from the moment {@code evaluate} joins that list.
   *
   * <p>{@code java.net} because a measurement is a pure function of one local file; {@code jena} as
   * the reference adapter nothing outside the bake-off reaches; {@code tinker} deliberately not,
   * because the throwaway projection is a {@code TinkerGraphStore} exactly as the recommender's is.
   */
  @ArchTest
  static final ArchRule theEvaluationHarnessOpensNothingElse =
      noClasses()
          .that()
          .resideInAPackage("..evaluate..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              otherDevToolsAnd(
                  List.of("evaluate", "recommend"),
                  "..jena..",
                  "..mcp..",
                  "..app..",
                  "java.net..",
                  "javax.net.."))
          .because(
              "ADR 65: the harness replays one local log into one in-memory graph and sweeps it"
                  + " once per setting, offline — it needs no second engine, no network and no"
                  + " sibling tool but the recommender, whose sweep it exists to measure");

  /**
   * ADR 65 on issue #85's line: the harness reads the score and cannot read the note.
   *
   * <p>The same shape as {@link #theRecommenderReadsRatingsAndNeverNotes}, and it has to be: this
   * tool reads more of the taste layer than any other except the listing tool — every score, twice
   * over, to split it and to weight what is left. {@code AffinityRecord} unnameable, {@code find}
   * and {@code readAll} unreachable, {@code readRatings} allowed.
   */
  @ArchTest
  static final ArchRule theEvaluationHarnessReadsRatingsAndNeverNotes =
      noClasses()
          .that()
          .resideInAPackage("..evaluate..")
          .should(
              ArchConditions.dependOnClassesThat(
                      JavaClass.Predicates.equivalentTo(AffinityRecord.class))
                  .or(
                      ArchConditions.accessTargetWhere(
                          callTo("find", AffinityStore.class)
                              .or(callTo("readAll", AffinityStore.class)))))
          .because(
              "ADR 65 on issue #85's line: the harness splits and weights by the score and cannot"
                  + " reach the note — readRatings returns a map of qid to rating, and the two"
                  + " reads that carry free text stay out of this package");

  /**
   * ADR 65 on ADR 60's clause: the harness names its database on the command line.
   *
   * <p>A fourth rule rather than a wider one, for {@link #theCensusHasNoDefaultDatabase}'s reason:
   * ADR 60's two are named for the claim tools, ADR 60 is immutable, and its consequences say a
   * further tool joins by hand.
   */
  @ArchTest
  static final ArchRule theEvaluationHarnessHasNoDefaultDatabase =
      noClasses()
          .that()
          .resideInAPackage("..evaluate..")
          .should()
          .dependOnClassesThat(JavaClass.Predicates.equivalentTo(DefaultDatabase.class))
          .because(
              "ADR 65: the harness names its database on the command line — SEGUE_DB is inherited"
                  + " by any shell started from the owner's profile, so it cannot stand in for a"
                  + " flag typed per invocation");

  /**
   * The sibling of {@link #theEvaluationHarnessHasNoDefaultDatabase}, forbidding the capability
   * where that one forbids the name — the gap ADR 60 measured and {@link
   * #theCensusTakesItsDatabaseFromTheFlagAlone} repeats. {@code evaluate} depends on {@code
   * support.RequiredDatabase} for the refusal sentence, and that class calls {@code
   * DefaultDatabase} itself.
   */
  @ArchTest
  static final ArchRule theEvaluationHarnessTakesItsDatabaseFromTheFlagAlone =
      noClasses()
          .that()
          .resideInAPackage("..evaluate..")
          .should(ArchConditions.accessTargetWhere(A_PATH_TAKEN_OUT_OF_SUPPORT))
          .because(
              "ADR 65, on ADR 60's measurement: a fence that forbids a class name stops only the"
                  + " lazy version — what has to be unavailable is any route from support to a"
                  + " java.nio.file.Path");
```

- [ ] **Step 3 — the guide's rule table, same commit.** Edit the `onlyTheRecommenderReadsEveryRating` row to name `evaluate` as a fourth package, and add five rows after the census rows:

```
| `theEvaluationHarnessOnlyReads` | `evaluate` calling the three world-fact writes or either taste-layer write (`AffinityStore.put`, `updateRating`), or depending on `IngestService` at all — a tool that could write could change what it is reporting on | [ADR 65](adr/0065-an-offline-evaluation-harness-for-the-recommender.md) |
| `theEvaluationHarnessOpensNothingElse` | `evaluate` depending on `jena`, `mcp`, `app`, `java.net`, `javax.net` or every other dev tool bar one. `recommend` is deliberately allowed — the harness measures the shipped sweep rather than a second copy of it, which is the third dependency between dev tools after `rate → recommend` and `census → export` — and `theRecommenderOpensNothingElse` keeps that trip one-way | [ADR 65](adr/0065-an-offline-evaluation-harness-for-the-recommender.md), [ADR 46](adr/0046-the-rating-deck.md), [ADR 63](adr/0063-a-read-only-census-of-the-graph.md) |
| `theEvaluationHarnessReadsRatingsAndNeverNotes` | `evaluate` depending on `AffinityRecord` **as a type**, or calling `AffinityStore.find` or `readAll` — it may hold the store and call the note-free `readRatings`, and nothing that carries free text | [ADR 33](adr/0033-taste-layer-separation.md), [ADR 65](adr/0065-an-offline-evaluation-harness-for-the-recommender.md) |
| `theEvaluationHarnessHasNoDefaultDatabase` | `evaluate` depending on `support.DefaultDatabase` at all. A fourth rule rather than a wider one, for the census rule's reason: ADR 60 names the two claim tools and is immutable | [ADR 65](adr/0065-an-offline-evaluation-harness-for-the-recommender.md), [ADR 60](adr/0060-the-claim-tools-require-an-explicit-database.md) |
| `theEvaluationHarnessTakesItsDatabaseFromTheFlagAlone` | `evaluate` calling any `support` method that returns a `java.nio.file.Path`, or reading any `support` field of that type — the capability, where the rule above forbids the name | [ADR 65](adr/0065-an-offline-evaluation-harness-for-the-recommender.md), [ADR 60](adr/0060-the-claim-tools-require-an-explicit-database.md) |
```

- [ ] **Step 4 — five planted controls, one per rule, each seen to fire and then removed.** Run `./gradlew test --tests 'com.robsartin.segue.arch.ArchitectureTest'` after each plant and **quote the violation text**:

  1. `theEvaluationHarnessOnlyReads` — in `EvaluateCli.run`, add `affinity.updateRating("Q0900401", 5, Instant.now());` inside the try block (that is `AffinityStore.updateRating`'s real three-argument signature — check it rather than trusting this line).
  2. `theEvaluationHarnessOpensNothingElse` — add `import com.robsartin.segue.ratings.RatingsRun;` and a field of that type to `EvaluateCli`.
  3. `theEvaluationHarnessReadsRatingsAndNeverNotes` — change `affinity.readRatings()` to `affinity.readAll()` and adapt the line enough to compile.
  4. `theEvaluationHarnessHasNoDefaultDatabase` — replace `RequiredDatabase.refusal(...)` with a `DefaultDatabase.resolve(...)` call.
  5. `theEvaluationHarnessTakesItsDatabaseFromTheFlagAlone` — temporarily add a `public static Path anything()` to `support.RequiredDatabase` and call it from `EvaluateCli`. Remove both halves afterwards.

  Also confirm the widening of `onlyTheRecommenderReadsEveryRating` did not go slack: temporarily add a `readRatings` call in `mcp.SegueService` and see that rule fire, then remove it.

- [ ] **Step 5 — gate and commit** `ArchitectureTest.java` and `docs/developer-guide.md` by explicit path. Confirm `git status` and `git diff` show no residue of any plant, in `src/main` or `src/test`.

---

### Task 10: the guide chapter, and its examples run through the parser

**Files:** create `src/test/java/com/robsartin/segue/evaluate/DeveloperGuideEvaluateExamplesTest.java`. Edit `docs/developer-guide.md`.

- [ ] **Step 1 — write the chapter.** In `docs/developer-guide.md`, add `- [Calibrating the recommender](#calibrating-the-recommender)` to `## Contents` after the "What to explore next" entry, and the chapter itself immediately after `## What to explore next`'s last subsection. Follow "Looking at the shape of your graph"'s shape: a fenced `bash` block with the pasteable command, then subsections for what it is for, the protocol, why the output is safe to paste, and what it is not allowed to do. It must contain at least one line of the exact form

```bash
./gradlew evaluate --args="--db $HOME/.segue/segue.db --known $HOME/known.csv"
```

  Points the chapter must make, each cited rather than restated: **the held-out set is `PERSON` and `GROUP` entities, not works** — the issue said works and the recommender cannot recommend one; **only a promotion can be held out**, because the `--known` file puts anything it names straight back; **the split is deterministic by qid**, so two runs diff; **one sweep per setting with suppression withheld**, and the filter that makes the positive reading the shipped ranking; **the grid is fixed and no flag moves it**; **`--db` is required and `SEGUE_DB` does not satisfy it**; **no constant changes** — the harness exists so a later issue can move one against a number. `Setting.GRID`, `HeldOut` and `EvaluationReport` are named as the authorities on the grid, the split and the columns; do not restate the floors or the column list in prose.

- [ ] **Step 2 — write the runbook test**, copied from `DeveloperGuideCensusExamplesTest` and pointed at `evaluate`:

```java
package com.robsartin.segue.evaluate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.robsartin.segue.arch.GuideExamples;
import com.robsartin.segue.arch.GuideExamples.Example;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * "Calibrating the recommender" shows commands the owner is meant to paste, and this runs every one
 * of them through {@link EvaluateCli#parse} — the fourth tool that requires {@code --db}, after the
 * two claim tools and the census.
 *
 * <p><b>Why {@code parse} and not the tool.</b> Running an example end to end would need a
 * database, and the only one on this machine is the owner's. What a runbook has to get right is the
 * command line, and {@code parse} is what enforces it before any file is opened.
 *
 * <p>In {@code evaluate} rather than beside the document tests in {@code arch}, because {@link
 * EvaluateCli#parse} is package-private, exactly as its siblings' are.
 */
class DeveloperGuideEvaluateExamplesTest {

  private static final GuideExamples RUNBOOK = GuideExamples.of("evaluate");

  @Test
  @DisplayName("the guide shows at least one evaluate example")
  void shouldShowAnExampleWhenTheGuideDocumentsTheHarness() {
    assertThat(RUNBOOK.examples())
        .as(
            "docs/developer-guide.md, 'Calibrating the recommender' — at least one ./gradlew"
                + " evaluate --args=\"…\" line. Without this the other checks pass vacuously on a"
                + " chapter that shows nothing")
        .isNotEmpty();
  }

  @Test
  @DisplayName("no evaluate example writes a tilde where $HOME belongs")
  void shouldWriteHomeRatherThanATildeWhenAnExampleNamesADatabase() {
    assertThat(RUNBOOK.withATilde())
        .as(
            "docs/developer-guide.md — a tilde does not expand inside the double quotes of"
                + " --args=\"…\", so the example arrives at the tool as a literal ~. EvaluateCli"
                + " cannot see this, because a tilde is a valid path character")
        .isEmpty();
  }

  @Test
  @DisplayName("every line naming evaluate is read as a command, or is prose with no --args")
  void shouldNameTheLineWhenAnExampleCannotBeRead() {
    assertThat(RUNBOOK.unreadableExamples())
        .as(
            "docs/developer-guide.md — a line naming evaluate that this test cannot read is a line"
                + " nothing checks, and skipping it silently is the hole this assertion exists to"
                + " close. A line with no --args at all is prose and is allowed")
        .isEmpty();
  }

  @Test
  @DisplayName("every evaluate example the guide shows parses")
  void shouldParseEveryExampleWhenTheGuideShowsACommand() {
    List<String> refused = new ArrayList<>();
    for (Example example : RUNBOOK.examples()) {
      assertThatCode(
              () ->
                  EvaluateCli.parse(
                      example.arguments().toArray(new String[0]),
                      null,
                      GuideExamples.INVENTED_HOME))
          .as("docs/developer-guide.md line %d: %s", example.line(), example.text())
          .doesNotThrowAnyException();
    }
    assertThat(refused).isEmpty();
  }
}
```

  Check `GuideExamples`'s public surface before writing this — `withATilde()`, `unreadableExamples()`, `examples()` and `INVENTED_HOME` are read off `DeveloperGuideCensusExamplesTest` and `GuideExamples` itself; if a name differs, use the real one. Drop the unused `refused` list if the fourth test is written without it.

- [ ] **Step 3 — the control.** Temporarily change the chapter's example to `--args="--db ~/.segue/segue.db --known $HOME/known.csv"` and confirm the tilde test fires; then to `--args="--known $HOME/known.csv"` and confirm the parse test fires on the missing `--db`. Quote both, restore the chapter.

- [ ] **Step 4 — gate and commit** the test and the guide by explicit path.

---

### Task 11: ADR 0065, and its index row

**Files:** create `docs/adr/0065-an-offline-evaluation-harness-for-the-recommender.md`. Edit `docs/adr/README.md`.

- [ ] **Step 1 — write the ADR.** Front matter in `docs/adr/0063-a-read-only-census-of-the-graph.md`'s exact shape:

```
---
status: Accepted
date: "2026-09-04"
topic: an-offline-evaluation-harness-for-the-recommender
tags: [project, tooling, privacy, data, graph]
supersedes: []
related: [recommend-by-normalised-lift-with-routes, suppress-a-candidate-you-have-rejected, a-high-rating-counts-as-something-you-have, the-floor-reports-itself, a-read-only-census-of-the-graph, what-an-adr-may-quote, taste-layer-separation, mcp-tool-surface, the-claim-tools-require-an-explicit-database, layering-and-archunit, privacy-and-data-handling]
---
# 65. An offline evaluation harness for the recommender: a deterministic held-out slice, a fixed grid, and aggregates only
```

  Sections `## Context`, `## Decision`, `## Alternatives considered`, `## Consequences`. The heading number and title must match the index row exactly (`AdrIndexTest`).

  **Context**: every ranking knob is a one-significant-figure judgement (ADR 45's scorer and floor, ADR 45's amendment for the floor's move, `RecommendationWeights`, `Recommendations.NEUTRAL_RATING`, ADR 48's promotion threshold, ADR 50's suppression boundary, ADR 31's hub threshold), and ADR 45 says why none can be tuned: *there is no held-out set of recommendations anybody has agreed with*. The taste layer now holds one — the census counted it — and it cannot leave the machine (ADR 33, ADR 16, issue #37). **Say plainly that the issue's premise named works and the code refuses them**, and that the ADR is written against the code.

  **Decision**: the six things the spec fixes, each with its reason — the eligible population and why only a promotion can be held out; the deterministic every-fifth-by-qid split; one map handed to the known-list, the regard and the suppressed set; one sweep per setting with suppression withheld, plus the subtractivity licence and the test that pins it; the fixed sixteen-setting grid with each floor's reason; and the output contract (every value an integer, a fixed decimal or a literal; `EvaluationReport` the authority on the columns, not this document). Placement: a ninth dev-side tool, `--db` required on ADR 60's clause, and the third permitted dev-tool→dev-tool dependency with the four fences that widen for it.

  **Alternatives considered**, each with why it lost: an MCP tool (ADR 26 pins the surface at six, ADR 33 keeps taste off it, ADR 39 already refused the bulk read and ADR 43 reserved it to the owner's machine); a random split with a recorded seed (reproducible only against a number somebody remembers — ADR 57's lesson); two sweeps per setting (twice the run for a number subtractivity already gives); tuning a constant in the same issue (the first reading would have been taken against the thing it was used to justify); reading `readAll` so the report could break the split down by note or recency (`onlyTheRatingsToolReadsANote` is where that line lives, and nothing reported needs it); holding out a slice of the `--known` file (a concert history carries no strength, so a hit measures reach rather than agreement); a harness with a sweep of its own (would answer a question about itself).

  **Consequences**: the number is comparative and not absolute; the split's denominator depends on expansion coverage, so a held-out entity the graph cannot reach is a miss for a reason that has nothing to do with the knobs; the grid grows if a scorer or a floor is added, and `Setting.GRID` is the authority; **the safe-to-paste property is over the report and not over a failed run**, ADR 63's limit restated here rather than left to be found; the harness has never been run against real ratings by anybody but the owner, and this repository holds none; and this decision changes no constant — it exists so that the next issue can.

- [ ] **Step 2 — the index row.** Append to the end of the `## Uncategorized` section of `docs/adr/README.md`, in the three-line shape every other entry uses (row, indented description, indented `Related:` line), keeping the section ascending by number. **Expect a conflict here with #238's ADR 0064**; resolve by keeping both, 64 then 65.

- [ ] **Step 3 — gate and commit** the two files by explicit path.

- [ ] **Step 4 — final sweep.** Run the full gate once more, blocking, and confirm: `git status` clean; no `.superpowers/` path in any committed file (`grep -rn '\.superpowers' docs src build.gradle.kts`); no plant left behind; `./gradlew tasks --group application` lists `evaluate` with its description; and `git log --oneline` shows one commit per task, each ending with the `Co-Authored-By` trailer.
