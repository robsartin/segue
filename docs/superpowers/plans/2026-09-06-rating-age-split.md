# The harness reads the held-out split by rating age — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to
> implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `./gradlew evaluate --args="… --rated-since <ISO-8601 instant>"` reads the held-out
population in two halves — promotions rated before the instant, and promotions rated on or after it
— and every row gains four cells saying how many of each half were in the pool and how many the top
N named. Without the flag the block is byte-identical to today's, so every reading on record stays
comparable.

**Architecture:** a note-free timestamp read on the affinity port, fenced to `evaluate` the day it
lands (task 1); today's block pinned character for character before anything is touched (task 2);
the merge-resolution of the timestamps and the pure `RatingAge` value that answers "is this one new"
(task 3); `Halves` on `Reading`, filled by `Scoring` in the two passes it already makes (task 4);
the report's four columns and its split line (task 5); the run and the command line, end to end
(task 6); then ADR 65's amendment and the guide (task 7), and the hand-back (task 8).

**Tech Stack:** Java (toolchain 25, `release 21`), JUnit 5, AssertJ, ArchUnit, JaCoCo. Markdown for
the ADR amendment and the developer guide.

**Spec:** `docs/superpowers/specs/2026-09-06-rating-age-split-design.md` — it holds the design
decisions, why the split is not in `HeldOut`, why the new fence exists, and the alternatives
rejected. **Cite it; never restate its reasoning.** Where this plan and the spec appear to differ,
the spec wins and the divergence is a finding to report.

---

## Global Constraints

- **Pure TDD, every step.** Failing test first, **run it and observe a real assertion failure** — a
  compile error is not a red. Where a step's failure would otherwise be a compile error, this plan
  says which stub to write so the failure is an assertion instead, and every step that expects a red
  names the message it expects. **Quote what the failure actually said** in the step report; if it
  said something else, stop and report rather than proceeding.
- **Every guard gets a planted control.** Plant the defect, run its test, see it fire, remove the
  plant, see it pass. The plan writes these out as steps; do not skip one because the guard "is
  obviously right".
- Test names `should<Expected>When<Condition>` with `@DisplayName`.
- **Mikado: green at every committed step.** Nothing is committed with a red gate.
- **The OWNER runs `evaluate`; the implementer never does.** `~/.segue/segue.db` is never read,
  written, copied or created, and no dev task is run against it. **Never run a writing dev task**
  (`own`, `ownClaim`, `retractEntity`, `rate`, or any other).
- **ADRs are append-only.** An amendment is appended to the end of the file. Front matter is not
  touched; no line above the amendment is edited, reworded or deleted. `docs/adr/README.md` is not
  touched. **ADR 45 is not touched at all** — the fifth reading is a follow-up issue, and no reading
  is taken here.
- **No commit hash is cited in anything under `docs/adr/`** (`AdrCitationsTest`, issue #274), and no
  code span in a committed document is 7–40 hex characters.
- **Never cite a `.superpowers/` path from a committed file.**
- **Never `git add -A`.** Stage every file by explicit path, with git's stderr visible (never
  `2>/dev/null`), and read `git status` before committing. One committer in this worktree; sibling
  issues are in flight in neighbouring worktrees and nothing outside `wt-276` is touched.
- Commit messages end with a blank line then
  `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`.
- **Gate, run BLOCKING (never backgrounded), after every task that changes a file:**
  `SEGUE_REQUIRE_BROWSER=true SEGUE_REQUIRE_GRAPHVIZ=true ./gradlew check --rerun-tasks`
  Plain `./gradlew`; JDK 25 is the only JDK. If `spotlessCheck` fails run `./gradlew spotlessApply`
  and re-run the gate. `check` includes `javadoc` with `-Werror`, so a missing `@param` or a broken
  `{@link}` fails it.
- **The fast loop between steps** is
  `./gradlew test --tests 'com.robsartin.segue.evaluate.*' --tests 'com.robsartin.segue.sqlite.*'`;
  the gate is what a commit rests on.
- **Invented identifiers only** in anything committed (ADR 58, ADR 51): every stand-in qid carries
  the leading zero, and nothing derived from the owner's data appears anywhere.
- **No wall-clock assertion, anywhere.** Every instant in every fixture is a literal.
- YAGNI: no parameter, overload or helper this plan does not need.

---

### Task 1: the port grows a timestamp read, and its fence lands with it

**Files:** `src/main/java/com/robsartin/segue/port/AffinityStore.java`,
`src/main/java/com/robsartin/segue/sqlite/SqliteAffinityStore.java`,
`src/test/java/com/robsartin/segue/sqlite/SqliteAffinityStoreTest.java`,
`src/test/java/com/robsartin/segue/ratings/InventedRatings.java`,
`src/test/java/com/robsartin/segue/census/InventedCensus.java`,
`src/test/java/com/robsartin/segue/export/AffinityOverlayTest.java`,
`src/test/java/com/robsartin/segue/rate/RateServerTest.java`,
`src/test/java/com/robsartin/segue/arch/ArchitectureTest.java`, `docs/developer-guide.md`.

**Why the rule and the guide row are in this commit.** A new port method is reachable from every
package until a rule says otherwise, and `DeveloperGuideEnumerationsTest` compares the guide's
ArchUnit table against the declared rules exactly — so rule and row land together, and both land
with the method. That is ADR 65's own precedent for a coupled change.

- [ ] **Step 1 — write the interface method and the stub that makes the red an assertion.** In
      `AffinityStore`, after `readRatings()`, add:

```java
  /**
   * When each rating was last written, keyed by qid — and nothing else (issue #276).
   *
   * <p><b>A third bulk read, one field narrower than {@link #readAll()} in the direction that
   * matters.</b> {@code readAll} carries this column already and carries the note with it, and
   * {@code ArchitectureTest.onlyTheRatingsToolReadsANote} is where that line lives — it does not
   * move. The evaluation harness reads its held-out population in two halves by rating age (ADR 65
   * as amended for issue #276), which needs this column and nothing beside it, so the return type
   * is the fence exactly as {@link #readRatings()}'s is: a {@code Map<String, Instant>} has nowhere
   * to put a note or a score, and an implementation must not select either column to build it.
   *
   * <p><b>The keys are every rated entity, which is why this read is fenced at all.</b> An instant
   * is not a note and not a score, but the keyset is the whole taste layer enumerated — the single
   * call ADR 39 refused to put in front of a model, whatever is on the other side of the arrow.
   * {@code ArchitectureTest.onlyTheEvaluationHarnessReadsWhenARatingChanged} keeps it to the one
   * dev-side tool that asked for it.
   *
   * <p><b>It is the last write, not the first.</b> ADR 39 keeps one row per entity and lets the
   * later rating win, so an opinion held for years and re-rated today reads as today. {@link
   * com.robsartin.segue.domain.AffinityRecord} says the same of the field this reads.
   *
   * <p>Unordered, for {@link #readRatings()}'s reason: a lookup table rather than a listing.
   */
  Map<String, Instant> readUpdatedAt();
```

      In `SqliteAffinityStore`, add the method with a **deliberate stub** so the test compiles and
      fails on its assertion rather than on `javac`:

```java
  @Override
  public Map<String, Instant> readUpdatedAt() {
    return Map.of();
  }
```

      In each of the four test doubles, add the method refusing the read the way that fake already
      refuses the ones its tool must not make (`InventedRatings.FakeAffinityStore`,
      `InventedCensus.FakeAffinityStore`, `AffinityOverlayTest.FakeAffinityStore`,
      `RateServerTest.RecordingAffinity`), adjusting the sentence to the tool:

```java
    /**
     * Deliberately unusable. The timestamps belong to the evaluation harness (issue #276), and a
     * fake that answered this read would let this tool quietly start making it without failing
     * anything — the discipline the other bulk read here already keeps.
     */
    @Override
    public Map<String, Instant> readUpdatedAt() {
      throw new UnsupportedOperationException("the ratings tool never reads when a rating changed");
    }
```

      Add `import java.time.Instant;` where a file lacks it. Build only:
      `./gradlew compileJava compileTestJava` — it must compile.

- [ ] **Step 2 — RED: the contract tests.** In `SqliteAffinityStoreTest`, after
      `readsNoScoresWhenNothingIsRated`, add:

```java
  @Test
  @DisplayName("readUpdatedAt returns when each rating last changed, by qid, and nothing else")
  void shouldReturnEveryTimestampByQidWhenTheTableHoldsRatings() {
    try (SqliteAffinityStore store = SqliteAffinityStore.inMemory()) {
      store.put(new AffinityRecord("Q0900001", 2, "an invented note", FIRST));
      store.put(new AffinityRecord("Q0900002", 4, null, LATER));

      // A Map<String, Instant> has nowhere to put a note or a score, and the SQL behind it selects
      // neither column. That is the fence readRatings already sets, one field over (issue #276).
      assertThat(store.readUpdatedAt())
          .containsExactlyInAnyOrderEntriesOf(Map.of("Q0900001", FIRST, "Q0900002", LATER));
    }
  }

  @Test
  @DisplayName("readUpdatedAt keeps the sub-second precision the column stores")
  void shouldKeepTheSubSecondPrecisionWhenARatingCarriesIt() {
    try (SqliteAffinityStore store = SqliteAffinityStore.inMemory()) {
      store.put(new AffinityRecord("Q0900001", 3, null, FIRST));

      assertThat(store.readUpdatedAt().get("Q0900001")).isEqualTo(FIRST);
    }
  }

  @Test
  @DisplayName("readUpdatedAt reports the later write after a re-rating, because there is one row")
  void shouldReportTheLaterInstantWhenAnEntityIsReRated() {
    // ADR 39's overwrite decision, read back: this column is the last write and never the first,
    // which is the limit the harness's report states in its own header (issue #276).
    try (SqliteAffinityStore store = SqliteAffinityStore.inMemory()) {
      store.put(new AffinityRecord("Q0900001", 2, "an invented note", FIRST));
      store.updateRating("Q0900001", 5, LATER);

      assertThat(store.readUpdatedAt()).containsExactly(Map.entry("Q0900001", LATER));
    }
  }

  @Test
  @DisplayName("readUpdatedAt on an unrated store is empty, not an error")
  void shouldReturnNoTimestampsWhenNothingIsRated() {
    try (SqliteAffinityStore store = SqliteAffinityStore.inMemory()) {
      assertThat(store.readUpdatedAt()).isEmpty();
    }
  }
```

- [ ] **Step 3 — verify it fails, for the right reason.** Run
      `./gradlew test --tests 'com.robsartin.segue.sqlite.SqliteAffinityStoreTest'`. Expect three
      failures — the empty-store one passes against the stub — each an AssertJ assertion, the first
      reading like *"Expecting map: {} to contain exactly in any order: ["Q0900001"=…]"*. **A
      compile error here means step 1 was not completed; fix that rather than proceeding.** Quote
      the real message.

- [ ] **Step 4 — GREEN: implement the read.** In `SqliteAffinityStore`, add the statement beside
      `SELECT_SCORES`:

```java
  /**
   * The timestamp-only bulk read (issue #276). <b>The column list is the fence in SQL</b>: neither
   * the note nor the rating is named, so a caller of {@code readUpdatedAt} cannot be handed either
   * however carelessly it is written. Unordered, for {@link #SELECT_SCORES}'s reason.
   */
  private static final String SELECT_UPDATED = "SELECT qid, updated_at FROM affinity";
```

      and replace the stub with:

```java
  @Override
  public Map<String, Instant> readUpdatedAt() {
    Map<String, Instant> updated = new HashMap<>();
    try (PreparedStatement ps = conn.prepareStatement(SELECT_UPDATED);
        ResultSet rs = ps.executeQuery()) {
      while (rs.next()) {
        updated.put(rs.getString("qid"), Instant.parse(rs.getString("updated_at")));
      }
      return Map.copyOf(updated);
    } catch (SQLException e) {
      // No qid and no count, for readAll's reason: how much the owner has rated is itself a fact
      // about him, and this string is the likeliest on this path to be logged upstream (ADR 33).
      throw new IllegalStateException("cannot read the affinity table", e);
    }
  }
```

- [ ] **Step 5 — verify it passes.** Re-run the same test class; all four green.

- [ ] **Step 6 — declare the fence.** In `ArchitectureTest`, immediately after
      `onlyTheRecommenderReadsEveryRating`, add:

```java
  /**
   * Issue #276: the timestamp read is the evaluation harness's alone.
   *
   * <p>The third rule of this shape, and it is here for {@link #onlyTheRatingsToolReadsEveryRating}'s
   * reason rather than {@link #onlyTheRecommenderReadsEveryRating}'s. What it protects is not a
   * value — an instant is not a note and not a score — but the <b>keyset</b>: a {@code Map<String,
   * Instant>} over the affinity table enumerates every entity the owner has rated, which is the
   * single call ADR 39 refused to put in front of a model, whatever is on the other side of the
   * arrow. A method with no rule on it is reachable from {@code mcp} the moment somebody writes the
   * line, and {@code ToolSurfaceTest} counts tools rather than fields.
   *
   * <p><b>A new rule rather than a widening of either sibling</b>, for ADR 63's reason that ADR 65
   * restates: a rule named for one tool and quoted in an immutable ADR does not get stretched to
   * cover a second. It names {@code evaluate} because the harness splits its held-out population by
   * rating age, and nothing else has asked.
   */
  @ArchTest
  static final ArchRule onlyTheEvaluationHarnessReadsWhenARatingChanged =
      noClasses()
          .that()
          .resideOutsideOfPackage("..evaluate..")
          .should()
          .accessTargetWhere(callTo("readUpdatedAt", AffinityStore.class))
          .because(
              "ADR 39 and issue #276: a bulk read keyed by qid enumerates the whole taste layer"
                  + " whatever its values are — when a rating last changed belongs to the one"
                  + " dev-side tool that splits its held-out population by rating age");
```

- [ ] **Step 7 — the guide's row, which the rule cannot land without.** In `docs/developer-guide.md`,
      in the table under `### Which rules a machine enforces`, immediately after the
      `onlyTheRecommenderReadsEveryRating` row, add:

```markdown
| `onlyTheEvaluationHarnessReadsWhenARatingChanged` | calling `AffinityStore.readUpdatedAt` from outside `evaluate` — a bulk read keyed by qid enumerates the whole taste layer whatever its values are, so when a rating last changed belongs to the one tool that splits its held-out population by rating age | [ADR 33](adr/0033-taste-layer-separation.md), [ADR 39](adr/0039-affinity-capture-and-read.md), [ADR 65](adr/0065-an-offline-evaluation-harness-for-the-recommender.md) |
```

- [ ] **Step 8 — verify the fence passes and the guide agrees.** Run
      `./gradlew test --tests 'com.robsartin.segue.arch.*'`. Green, including
      `shouldNameEveryArchUnitRuleWhenTheGuideTabulatesThem`.

- [ ] **Step 9 — PLANTED CONTROL for the new fence.** In `census/Census.java`, inside `Census.of`,
      immediately before the `return new Census(`, insert `ratings.readUpdatedAt();`. Run
      `./gradlew test --tests 'com.robsartin.segue.arch.ArchitectureTest'` and confirm
      `onlyTheEvaluationHarnessReadsWhenARatingChanged` fails, naming `Census` calling
      `AffinityStore.readUpdatedAt`. **Quote the violation.** Remove the line and re-run; green.

- [ ] **Step 10 — PLANTED CONTROL for the guide's table.** Delete the row added in step 7, run
      `./gradlew test --tests 'com.robsartin.segue.arch.DeveloperGuideEnumerationsTest'`, and
      confirm `shouldNameEveryArchUnitRuleWhenTheGuideTabulatesThem` fails naming the missing rule.
      Restore the row and re-run; green.

- [ ] **Step 11 — gate and commit.** Run the full gate, blocking. Then stage by explicit path — the
      nine files above — read `git status`, and commit:
      `The affinity port reads when a rating last changed (#276)`.

---

### Task 2: pin today's block, character for character

**Files:** `src/test/java/com/robsartin/segue/evaluate/EvaluationReportTest.java`.

**This is a guard, not a behaviour**, so it is green the moment it is written and its evidence is a
planted change to the renderer watched failing it. It exists before anything touches
`EvaluationReport`, because "byte-identical when the flag is absent" is a claim about the renderer
as it is **today** — a golden written after the change would only pin the change.

- [ ] **Step 1 — write the golden.** In `EvaluationReportTest`, add the constant and the test. The
      literals below were derived from the renderer's own arithmetic against the fixtures already in
      this class; if any line differs when the test runs, **stop and report** — that difference is a
      finding about the renderer, not a typo to paper over:

```java
  /**
   * Today's block, character for character (issue #276). The age split appends columns and inserts
   * one line, and this is what says the block is untouched when no instant is given — the property
   * every reading already on the record depends on.
   */
  private static final List<String> UNSPLIT_BLOCK =
      List.of(
          EvaluationReport.HEADER,
          "# held out every 5 of 10 eligible entity(ies), in 5 fold(s): 10 held out over all"
              + " folds, at least 8 left on the known-list in each.",
          "# top 25 per setting, over 2 setting(s).",
          "scorer  floor  pool  in pool  hits  mean rank  negatives  neg mean rank",
          "lift        5   900       40     4        7.5          2            4.0",
          "raw        12    40        3     0          -          0              -");

  @Test
  @DisplayName("the whole block renders exactly as it does today, character for character")
  void shouldRenderTheBlockUnchangedWhenNoInstantIsGiven() {
    assertThat(
            EvaluationReport.lines(
                ELIGIBLE, FOLDS, HELD_OUT_TOTAL, LEAST_LEFT, 25, List.of(reading(), sparse())))
        .containsExactlyElementsOf(UNSPLIT_BLOCK);
  }
```

- [ ] **Step 2 — verify it passes.** `./gradlew test --tests
      'com.robsartin.segue.evaluate.EvaluationReportTest'`. Green. If it is not, the literals are
      wrong: quote the actual block and stop.

- [ ] **Step 3 — PLANTED CONTROL.** In `EvaluationReport`, change `GAP` from `"  "` to `"   "`. Run
      the class and confirm `shouldRenderTheBlockUnchangedWhenNoInstantIsGiven` fails on the column
      rows. **Quote the failure.** Restore `GAP` and re-run; green.

- [ ] **Step 4 — gate and commit.** Full gate, blocking. Stage
      `src/test/java/com/robsartin/segue/evaluate/EvaluationReportTest.java` and commit:
      `Pin the evaluation block character for character (#276)`.

---

### Task 3: the timestamps resolve through the merges, and `RatingAge` says which half

**Files:** `src/main/java/com/robsartin/segue/domain/Equivalences.java`,
`src/test/java/com/robsartin/segue/domain/EquivalencesTest.java`, a new
`src/main/java/com/robsartin/segue/evaluate/RatingAge.java`, a new
`src/test/java/com/robsartin/segue/evaluate/RatingAgeTest.java`.

**Both land unused**, which is what keeps the build green while the pieces arrive leaf-first.

- [ ] **Step 1 — refactor `resolve` onto a generic body, with no behaviour change.** In
      `Equivalences`, replace the body of `resolve` with a delegation and add the private helper.
      Nothing else in the file moves, the javadoc on `resolve` is untouched:

```java
  public Map<String, Integer> resolve(Map<String, Integer> ratings) {
    Objects.requireNonNull(ratings, "ratings");
    return collapse(ratings);
  }

  /**
   * One merge rule, whatever is on the other side of the arrow. The ratings and the timestamps are
   * two views of one table and its one keyset, so a second copy of this loop is the second copy of
   * a rule that a future editor changes in one place only — {@link Retractions}' own reason for
   * existing, and this class's.
   */
  private <V> Map<String, V> collapse(Map<String, V> byQid) {
    if (canonicalByLocal.isEmpty()) {
      return Map.copyOf(byQid);
    }
    Map<String, V> resolved = new LinkedHashMap<>(byQid);
    for (Map.Entry<String, String> merge : canonicalByLocal.entrySet()) {
      V local = resolved.remove(merge.getKey());
      if (local != null) {
        resolved.putIfAbsent(merge.getValue(), local);
      }
    }
    return Map.copyOf(resolved);
  }
```

      Run `./gradlew test --tests 'com.robsartin.segue.domain.EquivalencesTest'`; green, unchanged.

- [ ] **Step 2 — write the timestamp method as a stub, so the next red is an assertion.** Add, after
      `resolve`:

```java
  /**
   * The rating timestamps as they read through the equivalences: one row per thing, not one per id
   * (issue #276).
   *
   * <p><b>The same rule {@link #resolve} applies to the ratings, applied to the other column of the
   * same rows</b> — a merged local id leaves the map and its instant lands on the canonical id only
   * where the canonical id has none. The two maps come from one table and have one keyset, so the
   * same key survives in both and a rating cannot end up beside another row's timestamp. Two
   * readers with their own idea of what a merge reaches is the defect this class exists to prevent,
   * so the harness asks here rather than resolving a second time of its own.
   *
   * @param updatedAt qid to when that rating was last written — {@code
   *     AffinityStore.readUpdatedAt}, note-free and score-free by construction, which is what keeps
   *     this method in {@code domain}
   */
  public Map<String, Instant> resolveUpdatedAt(Map<String, Instant> updatedAt) {
    Objects.requireNonNull(updatedAt, "updatedAt");
    return Map.copyOf(updatedAt);
  }
```

      Add `import java.time.Instant;`.

- [ ] **Step 3 — RED: the merge case.** In `EquivalencesTest`, beside the existing `resolve` tests,
      add (reusing that class's `MINTED`, `CANONICAL` and its way of building `merges`):

```java
  @Test
  @DisplayName("a merged local id's timestamp lands on the canonical id, like its rating")
  void shouldCarryTheTimestampToTheCanonicalIdWhenTheLocalIdIsMerged() {
    Instant when = Instant.parse("2026-03-04T05:06:07Z");

    assertThat(merges.resolveUpdatedAt(Map.of(MINTED, when)))
        .containsExactly(Map.entry(CANONICAL, when));
  }

  @Test
  @DisplayName("the canonical id's own timestamp wins, because its own rating does")
  void shouldKeepTheCanonicalTimestampWhenBothIdsAreRated() {
    Instant local = Instant.parse("2026-03-04T05:06:07Z");
    Instant canonical = Instant.parse("2026-04-05T06:07:08Z");

    assertThat(merges.resolveUpdatedAt(Map.of(MINTED, local, CANONICAL, canonical)))
        .containsExactly(Map.entry(CANONICAL, canonical));
  }
```

      Run the class. Expect the first to fail with *"Expecting map: {"Q…minted"=2026-03-04T05:06:07Z}
      to contain exactly: ["Q…canonical"=…]"* — an assertion, not a compile error. Quote it. (The
      second passes against the stub; that is expected and is why the first exists.)

- [ ] **Step 4 — GREEN.** Replace the stub's body with `return collapse(updatedAt);` (keeping the
      `requireNonNull`). Re-run; both green, and `EquivalencesTest` green as a whole.

- [ ] **Step 5 — RED: `RatingAge`.** Create `RatingAgeTest` with the three behaviours, and create
      `RatingAge` with **stubs** so the failures are assertions: `of` returning
      `new RatingAge(since, Set.of())` and `isNew` returning `newer.contains(qid)`.

```java
package com.robsartin.segue.evaluate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Which promotions are new, over invented ratings. Nothing here comes from anybody's taste layer
 * (ADR 33, issue #37) and every id carries ADR 58's leading zero.
 */
class RatingAgeTest {

  private static final Instant SINCE = Instant.parse("2026-09-06T15:00:00Z");
  private static final String OLD = "Q0900451";
  private static final String NEW = "Q0900452";
  private static final String EXACT = "Q0900453";

  @Test
  @DisplayName("a rating written before the instant is old and one written after it is new")
  void shouldSplitThePopulationWhenTheInstantFallsBetweenTwoRatings() {
    RatingAge age =
        RatingAge.of(
            SINCE,
            Map.of(
                OLD, Instant.parse("2026-09-05T23:59:59Z"),
                NEW, Instant.parse("2026-09-06T15:00:01Z")),
            Set.of(OLD, NEW));

    assertThat(age.isNew(NEW)).isTrue();
    assertThat(age.isNew(OLD)).isFalse();
    assertThat(age.newer()).containsExactly(NEW);
  }

  @Test
  @DisplayName("a rating written at the instant itself is new, because the half is on or after it")
  void shouldCountTheRatingAsNewWhenItsTimestampIsTheInstantItself() {
    RatingAge age = RatingAge.of(SINCE, Map.of(EXACT, SINCE), Set.of(EXACT));

    assertThat(age.isNew(EXACT)).isTrue();
  }

  @Test
  @DisplayName("a rated entity with no timestamp is refused, rather than counted as old")
  void shouldRefuseThePopulationWhenARatedEntityHasNoTimestamp() {
    // A lenient read feeding a guard turns "cannot tell" into "old", and the cell that results
    // looks exactly like a real one. The message names no qid and no count (ADR 33).
    assertThatThrownBy(() -> RatingAge.of(SINCE, Map.of(OLD, SINCE), Set.of(OLD, NEW)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no rating timestamp")
        .hasMessageNotContaining(NEW);
  }
}
```

      Run `./gradlew test --tests 'com.robsartin.segue.evaluate.RatingAgeTest'`. Expect two
      assertion failures — *"Expecting value to be true but was false"* on the first two — and the
      third failing with *"Expecting code to raise a throwable"*. Quote them.

- [ ] **Step 6 — GREEN: `RatingAge`.** Write the file:

```java
package com.robsartin.segue.evaluate;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Where the line between an old promotion and a new one falls, and which side each rated entity is
 * on (issue #276, ADR 65 as amended).
 *
 * <p><b>A value built once per run, not a question asked per fold.</b> Fold {@code k} and fold
 * {@code k + 1} disagree about which entities are hidden and agree exactly about which are old, so
 * the age is not a property of the split: {@link HeldOut#every} keeps the signature it has, and this
 * is derived once, before the first sweep, from the ratings the run is about to read.
 *
 * <p><b>On or after the instant is new; before it is old.</b> An entity whose rating was last
 * written at the instant itself is new, and a test pins that boundary rather than leaving it to be
 * read off an implementation.
 *
 * <p><b>A rated entity with no timestamp is refused rather than counted as old.</b> The two bulk
 * reads are two selects over one table through one connection, so a disagreement between their
 * keysets means the resolution or the store is wrong — and an entity silently reported in the old
 * half would sit in a cell indistinguishable from a real one. The refusal names no qid and no
 * count: how much the owner has rated is itself a fact about him (ADR 33), which is why {@code
 * SqliteAffinityStore.readAll} already keeps both out of its own exception.
 *
 * <p><b>The timestamp is the last write.</b> ADR 39 keeps one row per entity, so a promotion rated
 * long ago and re-rated after the instant lands in the new half. That is the limit the report's
 * header states, and it is why the halves are an observation rather than a count of new promotions.
 *
 * @param since the boundary the operator gave, parsed
 * @param newer every rated qid whose rating was last written at or after {@link #since}
 */
public record RatingAge(Instant since, Set<String> newer) {

  public RatingAge {
    Objects.requireNonNull(since, "since");
    newer = Set.copyOf(Objects.requireNonNull(newer, "newer"));
  }

  /**
   * Derive the new half, once.
   *
   * @param since the instant the halves are drawn at
   * @param updatedAt qid to when that rating was last written, already resolved through the merges
   * @param rated every qid the run's ratings map names, resolved the same way
   * @throws IllegalStateException if a rated entity has no timestamp
   */
  public static RatingAge of(Instant since, Map<String, Instant> updatedAt, Set<String> rated) {
    Objects.requireNonNull(since, "since");
    Objects.requireNonNull(updatedAt, "updatedAt");
    Objects.requireNonNull(rated, "rated");

    Set<String> newer = new LinkedHashSet<>();
    for (String qid : rated) {
      Instant when = updatedAt.get(qid);
      if (when == null) {
        throw new IllegalStateException(
            "a rated entity has no rating timestamp: the two bulk reads of the affinity table"
                + " disagree about which entities are rated, and the age split cannot say which"
                + " half this one belongs in");
      }
      if (!when.isBefore(since)) {
        newer.add(qid);
      }
    }
    return new RatingAge(since, newer);
  }

  /** Whether this entity's rating was last written at or after {@link #since}. */
  public boolean isNew(String qid) {
    return newer.contains(qid);
  }
}
```

      Re-run the class; green.

- [ ] **Step 7 — gate and commit.** Full gate, blocking. Stage the four files by explicit path and
      commit: `Resolve rating timestamps and derive the age halves (#276)`.

---

### Task 4: `Halves` on `Reading`, filled by `Scoring` in the passes it already makes

**Files:** a new `src/main/java/com/robsartin/segue/evaluate/Halves.java`, a new
`src/test/java/com/robsartin/segue/evaluate/HalvesTest.java`,
`src/main/java/com/robsartin/segue/evaluate/Reading.java`,
`src/main/java/com/robsartin/segue/evaluate/Scoring.java`,
`src/main/java/com/robsartin/segue/evaluate/EvaluateRun.java` (call site only),
`src/test/java/com/robsartin/segue/evaluate/ReadingTest.java`,
`src/test/java/com/robsartin/segue/evaluate/ScoringTest.java`,
`src/test/java/com/robsartin/segue/evaluate/EvaluationReportTest.java` (fixtures only).

**The report is not touched in this task.** Every reading is built `UNSPLIT` until task 5 renders
one, and the golden from task 2 is what says so.

- [ ] **Step 1 — RED: `Halves` and its two guards.** Write `HalvesTest`, and `Halves` as a stub
      record with **no compact constructor and a `plus` that adds without checking** — so both reds
      are assertions:

```java
package com.robsartin.segue.evaluate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The four cells the age split adds to one row, and the two ways they could stop meaning it. */
class HalvesTest {

  @Test
  @DisplayName("every cell adds, and the split flag is carried through")
  void shouldAddEveryCellWhenTwoSplitFoldsAreAdded() {
    Halves first = new Halves(true, 30, 3, 10, 1);
    Halves second = new Halves(true, 28, 2, 9, 0);

    assertThat(first.plus(second)).isEqualTo(new Halves(true, 58, 5, 19, 1));
  }

  @Test
  @DisplayName("a reading with no split cannot carry a count in either half")
  void shouldRefuseTheCellsWhenThereIsNoSplitToCountThem() {
    assertThatThrownBy(() -> new Halves(false, 1, 0, 0, 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("no age split");
  }

  @Test
  @DisplayName("a split reading and an unsplit one cannot be added, because one run is one or none")
  void shouldRefuseTheAdditionWhenOneSideIsSplitAndTheOtherIsNot() {
    assertThatThrownBy(() -> new Halves(true, 1, 0, 0, 0).plus(Halves.UNSPLIT))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("one run is split");
  }
}
```

      Run the class: the first passes against the stub, the two guards fail with *"Expecting code to
      raise a throwable"*. Quote them.

- [ ] **Step 2 — GREEN: `Halves`.** Write the file in full:

```java
package com.robsartin.segue.evaluate;

import java.util.Objects;

/**
 * The four cells one row contributes to the age split, and the flag that says whether a split was
 * asked for at all (issue #276).
 *
 * <p><b>The flag is not avoidable, whatever shape these take.</b> A run given no instant and a run
 * whose halves happen to be all zeroes must render differently — four columns or none — and four
 * zeroes cannot say which. What a record buys over four loose fields on {@link Reading} is that the
 * flag and the counts cannot drift apart: the compact constructor refuses a count on an unsplit
 * value and {@link #plus} refuses to add across the two, so a table assembled from two views of one
 * run is a build failure rather than a plausible block of numbers.
 *
 * <p><b>Four plain integers, and none of them can ever be the dash.</b> Every one is a count rather
 * than a mean, which is what keeps {@code EvaluationReport}'s contract exactly as ADR 65 fixed it.
 *
 * <p><b>They are entity counts, on {@link Reading#hits}' scale rather than {@link Reading#pool}'s.</b>
 * The folds of one split partition the held-out set, so a held-out entity is counted in exactly one
 * fold and a summed value is still a count of distinct entities. In a summed row {@code oldHits +
 * newHits} equals {@link Reading#hits} and {@code oldInPool + newInPool} equals {@link
 * Reading#heldOutInPool}, which is how a reader checks the table against itself.
 *
 * @param split whether this reading was taken with an instant at all
 * @param oldInPool held-out entities rated before the instant that are in the pool at all
 * @param oldHits held-out entities rated before the instant that the top N names
 * @param newInPool held-out entities rated on or after the instant that are in the pool at all
 * @param newHits held-out entities rated on or after the instant that the top N names
 */
public record Halves(boolean split, int oldInPool, int oldHits, int newInPool, int newHits) {

  /** A reading of a run that was given no instant: no halves, and four cells that never render. */
  public static final Halves UNSPLIT = new Halves(false, 0, 0, 0, 0);

  public Halves {
    if (!split && (oldInPool != 0 || oldHits != 0 || newInPool != 0 || newHits != 0)) {
      throw new IllegalArgumentException(
          "a reading with no age split cannot carry a count in either half: the flag and the four"
              + " cells are one fact, and a row assembled from two of them says what neither does");
    }
  }

  /** Add one fold's cells to another's. */
  public Halves plus(Halves other) {
    Objects.requireNonNull(other, "other");
    if (split != other.split()) {
      throw new IllegalArgumentException(
          "a split reading and an unsplit one cannot be added: one run is split by rating age or"
              + " it is not, and every fold of it is read the same way");
    }
    return new Halves(
        split,
        oldInPool + other.oldInPool(),
        oldHits + other.oldHits(),
        newInPool + other.newInPool(),
        newHits + other.newHits());
  }
}
```

      Re-run `HalvesTest`; green.

- [ ] **Step 3 — PLANTED CONTROLS for both guards.** One at a time: comment out the compact
      constructor's `throw`, run `HalvesTest`, see
      `shouldRefuseTheCellsWhenThereIsNoSplitToCountThem` fail, restore, green. Then comment out
      `plus`'s `throw`, run, see `shouldRefuseTheAdditionWhenOneSideIsSplitAndTheOtherIsNot` fail,
      restore, green. **Quote both failures.**

- [ ] **Step 4 — carry `Halves` on `Reading`, with `summed` not yet adding it.** Add the component
      last, with its `@param`, `Objects.requireNonNull(halves, "halves")` in the compact
      constructor, and a paragraph pointing at `Halves` as the authority; in `summed`, carry the
      first fold's value through **without adding** (the deliberate stub):

```java
    Halves halves = folds.get(0).halves();
```

      and pass it as the last argument to the constructed `Reading`. Update every call site to pass
      `Halves.UNSPLIT`: `Scoring.read`'s construction, `ReadingTest`'s four fixtures,
      `EvaluationReportTest`'s `reading()`, `sparse()` and `wide`. Build:
      `./gradlew compileJava compileTestJava`.

- [ ] **Step 5 — RED: `summed` adds the halves.** In `ReadingTest`, add:

```java
  @Test
  @DisplayName("the age split's four cells add over the folds, like the counts beside them")
  void shouldAddTheHalvesWhenTheFoldsOfOneSplitSettingAreSummed() {
    Reading first = new Reading(SETTING, 900, 40, 4, 30, 2, 8, new Halves(true, 30, 3, 10, 1));
    Reading second = new Reading(SETTING, 880, 38, 3, 21, 1, 5, new Halves(true, 28, 2, 9, 0));

    Reading summed = Reading.summed(List.of(first, second));

    assertThat(summed.halves()).isEqualTo(new Halves(true, 58, 5, 19, 1));
    assertThat(summed.halves().oldHits() + summed.halves().newHits())
        .as("the halves partition the hits, because the folds partition the held-out set")
        .isEqualTo(summed.hits());
  }

  @Test
  @DisplayName("a split fold and an unsplit one cannot be summed into one row")
  void shouldRefuseTheFoldsWhenOneIsSplitByRatingAgeAndAnotherIsNot() {
    Reading split = new Reading(SETTING, 900, 40, 4, 30, 2, 8, new Halves(true, 30, 3, 10, 1));
    Reading unsplit = new Reading(SETTING, 880, 38, 3, 21, 1, 5, Halves.UNSPLIT);

    assertThatThrownBy(() -> Reading.summed(List.of(split, unsplit)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("one run is split");
  }
```

      Run `ReadingTest`. Expect the first to fail with *"expected: Halves[split=true, oldInPool=58,
      …] but was: Halves[split=true, oldInPool=30, …]"* and the second with *"Expecting code to
      raise a throwable"*. Quote them.

- [ ] **Step 6 — GREEN.** In `summed`, initialise and accumulate:

```java
    Halves halves = new Halves(folds.get(0).halves().split(), 0, 0, 0, 0);
```

      and inside the loop, beside the other accumulations, `halves = halves.plus(fold.halves());`.
      Re-run `ReadingTest`; green, the existing three included.

- [ ] **Step 7 — RED: `Scoring` fills the cells.** In `ScoringTest`, add the age to every existing
      `Scoring.read(...)` call as `Optional.empty()` (and `import java.util.Optional;`), then add:

```java
  @Test
  @DisplayName("the hits and the pool split into halves when the run is given an age")
  void shouldCountEachHalfSeparatelyWhenTheRunIsSplitByRatingAge() {
    Sweep sweep = pool(List.of("Q0900401", "Q0900402", "Q0900403", "Q0900404"));
    RatingAge age =
        new RatingAge(Instant.parse("2026-09-06T15:00:00Z"), Set.of("Q0900402", "Q0900404"));

    Reading reading =
        Scoring.read(
            sweep, SETTING, Set.of("Q0900401", "Q0900402", "Q0900404"), Set.of(), 2, Optional.of(age));

    // Ranks 1 and 2 are inside the top 2; rank 4 is in the pool and is not a hit.
    assertThat(reading.halves()).isEqualTo(new Halves(true, 1, 1, 2, 1));
    assertThat(reading.halves().oldInPool() + reading.halves().newInPool())
        .isEqualTo(reading.heldOutInPool());
    assertThat(reading.halves().oldHits() + reading.halves().newHits()).isEqualTo(reading.hits());
  }

  @Test
  @DisplayName("a run with no age carries no halves at all, not four zeroes it could render")
  void shouldCarryNoHalvesWhenTheRunIsNotSplitByRatingAge() {
    Sweep sweep = pool(List.of("Q0900401", "Q0900402"));

    Reading reading = Scoring.read(sweep, SETTING, Set.of("Q0900401"), Set.of(), 2, Optional.empty());

    assertThat(reading.halves()).isEqualTo(Halves.UNSPLIT);
  }
```

      `Scoring.read` must first take the parameter for this to compile: add
      `Optional<RatingAge> age` as the last parameter with `Objects.requireNonNull(age, "age")` and
      **ignore it**, still building `Halves.UNSPLIT` — the deliberate stub. Update `EvaluateRun`'s
      one call site to pass `Optional.empty()`. Then run `ScoringTest` and expect the first to fail
      with *"expected: Halves[split=true, oldInPool=1, oldHits=1, newInPool=2, newHits=1] but was:
      Halves[split=false, …]"*. Quote it.

- [ ] **Step 8 — GREEN: the two passes tally.** Replace the body of `Scoring.read` between the
      null checks and the `return` with the two loops, and delete the now-unused private `in`
      helper (`ranksOf` and `sum` stay, for the negatives):

```java
    // One pass, where there were two streams: the pool is built, the held-out entities in it are
    // counted, and each one's half is tallied in the same membership test. The half is a property
    // of the entity, so nothing is swept, ranked or walked a second time to learn it (issue #276).
    List<Recommendation> shipped = new ArrayList<>();
    int heldOutInPool = 0;
    int oldInPool = 0;
    int newInPool = 0;
    for (Recommendation candidate : sweep.candidates()) {
      String qid = candidate.entity().qid();
      if (negatives.contains(qid)) {
        continue;
      }
      shipped.add(candidate);
      if (heldOut.contains(qid)) {
        heldOutInPool++;
        if (age.isPresent()) {
          if (age.get().isNew(qid)) {
            newInPool++;
          } else {
            oldInPool++;
          }
        }
      }
    }

    // The same again over the ranked top: one walk, the ranks and the halves out of it.
    List<Recommendation> shippedTop = Recommendations.rank(shipped, top);
    int hits = 0;
    int hitRankSum = 0;
    int oldHits = 0;
    int newHits = 0;
    for (int rank = 1; rank <= shippedTop.size(); rank++) {
      String qid = shippedTop.get(rank - 1).entity().qid();
      if (!heldOut.contains(qid)) {
        continue;
      }
      hits++;
      hitRankSum += rank;
      if (age.isPresent()) {
        if (age.get().isNew(qid)) {
          newHits++;
        } else {
          oldHits++;
        }
      }
    }

    List<Recommendation> withheldTop = Recommendations.rank(sweep.candidates(), top);
    List<Integer> negativeRanks = ranksOf(withheldTop, negatives);

    return new Reading(
        setting,
        shipped.size(),
        heldOutInPool,
        hits,
        hitRankSum,
        negativeRanks.size(),
        sum(negativeRanks),
        age.isPresent()
            ? new Halves(true, oldInPool, oldHits, newInPool, newHits)
            : Halves.UNSPLIT);
```

      Add a paragraph to `Scoring`'s class javadoc saying the halves are tallied in the two passes it
      already makes and that the negatives are read unsplit, because a rated-down entity is never
      held out. Run `ScoringTest`; green, the five existing tests included — they are what says the
      loops reproduce the streams exactly.

- [ ] **Step 9 — gate and commit.** Full gate, blocking. `EvaluationReportTest`'s golden must still
      be green: quote that it is. Stage the eight files and commit:
      `Carry the age split's four cells on a reading (#276)`.

---

### Task 5: the report renders the halves and states the split

**Files:** `src/main/java/com/robsartin/segue/evaluate/EvaluationReport.java`,
`src/main/java/com/robsartin/segue/evaluate/EvaluateRun.java` (call site only),
`src/test/java/com/robsartin/segue/evaluate/EvaluationReportTest.java`.

- [ ] **Step 1 — take the new parameters, and render nothing new yet.** Change the signature to

```java
  public static List<String> lines(
      int eligible,
      int folds,
      int heldOutTotal,
      int leastLeft,
      int top,
      Optional<Instant> since,
      int oldHeldOut,
      int newHeldOut,
      List<Reading> readings) {
```

      with `Objects.requireNonNull(since, "since")` and three `@param` entries — `since` "the instant
      the halves were drawn at, or empty when none was given", `oldHeldOut`/`newHeldOut` "how many of
      the eligible population fell each side of it, summed over the folds". Leave the body otherwise
      untouched. Update `EvaluateRun`'s call to pass `Optional.empty(), 0, 0` before `readings`, and
      the four calls in `EvaluationReportTest` — the golden's included. Build and run
      `./gradlew test --tests 'com.robsartin.segue.evaluate.*'`; green, and **the golden is still
      green**, which is the point of this step being separate.

- [ ] **Step 2 — RED: the split line and the four columns.** In `EvaluationReportTest`, add the
      split fixtures and the tests:

```java
  private static final Instant SINCE = Instant.parse("2026-09-06T15:00:00Z");

  @Test
  @DisplayName("the block states the instant and both halves, and every row gains four cells")
  void shouldStateTheHalvesWhenAnInstantIsGiven() {
    List<String> lines =
        EvaluationReport.lines(
            ELIGIBLE,
            FOLDS,
            HELD_OUT_TOTAL,
            LEAST_LEFT,
            25,
            Optional.of(SINCE),
            7,
            3,
            List.of(splitReading(), splitSparse()));

    assertThat(lines).hasSize(4 + 1 + 2);
    assertThat(lines.get(2))
        .isEqualTo(
            "# split by rating age at 2026-09-06T15:00:00Z: 7 old (rated before it), 3 new (rated"
                + " on or after it) — a rating's timestamp is its last write, so a re-rated old"
                + " promotion counts as new.");
    assertThat(lines.get(4)).endsWith("old in pool  old hits  new in pool  new hits");
    assertThat(cellsOf(lines.get(5)))
        .containsExactly(
            "lift", "5", "900", "40", "4", "7.5", "2", "4.0", "30", "3", "10", "1");
  }

  @Test
  @DisplayName("a split row's first eight columns render exactly as the same row does unsplit")
  void shouldLeaveTheExistingColumnsWhereTheyAreWhenTheHalvesAreAppended() {
    // Appended rather than interleaved, so every reading already on the record keeps its shape.
    List<String> split =
        EvaluationReport.lines(
            ELIGIBLE, FOLDS, HELD_OUT_TOTAL, LEAST_LEFT, 25, Optional.of(SINCE), 7, 3,
            List.of(splitReading(), splitSparse()));

    assertThat(split.get(5)).startsWith(UNSPLIT_BLOCK.get(4));
    assertThat(split.get(6)).startsWith(UNSPLIT_BLOCK.get(5));
  }

  @Test
  @DisplayName("nothing qid-shaped reaches the split line, whatever instant was given")
  void shouldCarryNoIdentifierWhenTheBlockStatesTheInstant() {
    // The instant is rendered from the parsed value, never from the string the operator typed,
    // so an Instant's own alphabet — digits, '-', ':', '.', 'T', 'Z' — is all this line can hold.
    assertThat(
            EvaluationReport.lines(
                ELIGIBLE, FOLDS, HELD_OUT_TOTAL, LEAST_LEFT, 25, Optional.of(SINCE), 7, 3,
                List.of(splitReading())))
        .noneMatch(line -> A_QID.matcher(line).find());
  }

  @Test
  @DisplayName("an instant with an unsplit reading is refused, because one run is split or is not")
  void shouldRefuseTheBlockWhenTheInstantAndTheReadingsDisagree() {
    assertThatThrownBy(
            () ->
                EvaluationReport.lines(
                    ELIGIBLE, FOLDS, HELD_OUT_TOTAL, LEAST_LEFT, 25, Optional.of(SINCE), 7, 3,
                    List.of(reading())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("disagree");
  }

  @Test
  @DisplayName("halves with no instant are refused, because there is no line to state them on")
  void shouldRefuseTheBlockWhenThereAreHalvesButNoInstant() {
    assertThatThrownBy(
            () ->
                EvaluationReport.lines(
                    ELIGIBLE, FOLDS, HELD_OUT_TOTAL, LEAST_LEFT, 25, Optional.empty(), 7, 3,
                    List.of(reading())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("no instant was given");
  }

  private static Reading splitReading() {
    return new Reading(new Setting(Scorer.LIFT, 5), 900, 40, 4, 30, 2, 8, new Halves(true, 30, 3, 10, 1));
  }

  private static Reading splitSparse() {
    return new Reading(new Setting(Scorer.RAW, 12), 40, 3, 0, 0, 0, 0, new Halves(true, 2, 0, 1, 0));
  }
```

      Add the imports (`java.time.Instant`, `java.util.Optional`,
      `static org.assertj.core.api.Assertions.assertThatThrownBy`). Run the class and expect the
      first to fail on the size — *"Expected size: 7 but was: 6"* — and the guards to fail with
      *"Expecting code to raise a throwable"*. Quote them.

- [ ] **Step 3 — GREEN: render it.** In `EvaluationReport`:

      Add beside `COLUMNS`:

```java
  /**
   * The four cells the age split adds, <b>appended</b> so the eight above keep their positions and
   * their widths: a split row's first eight columns render byte for byte as the same row does
   * unsplit, which is what keeps every reading on the record comparable (issue #276).
   */
  private static final List<String> SPLIT_COLUMNS =
      Stream.concat(
              COLUMNS.stream(),
              Stream.of("old in pool", "old hits", "new in pool", "new hits"))
          .toList();
```

      Add the two guards and the rendering to `lines`, after the null checks:

```java
    boolean split = since.isPresent();
    if (!split && (oldHeldOut != 0 || newHeldOut != 0)) {
      throw new IllegalArgumentException(
          "no instant was given, so there are no halves to state: a split line naming a division"
              + " nothing made is a line a reader would believe");
    }
    if (readings.stream().anyMatch(reading -> reading.halves().split() != split)) {
      throw new IllegalArgumentException(
          "the instant and the readings disagree about whether this run was split by rating age:"
              + " one run is split or it is not, and every setting is read the same way");
    }

    List<String> columns = split ? SPLIT_COLUMNS : COLUMNS;
    List<List<String>> rows = new ArrayList<>();
    rows.add(columns);
    readings.forEach(reading -> rows.add(cells(reading)));
    int[] widths = widths(rows, columns.size());
```

      and, between the existing split line and the `# top …` line:

```java
    since.ifPresent(instant -> rendered.add(ageLine(instant, oldHeldOut, newHeldOut)));
```

      Add the two helpers and widen the existing ones:

```java
  /**
   * <b>The instant is rendered from the parsed value, never from the string the operator typed.</b>
   * {@code Instant.toString()} can only produce digits, {@code -}, {@code :}, {@code .}, {@code T}
   * and {@code Z}, so the one operator-supplied fact in the whole block cannot carry an identifier
   * into it however the flag was spelled — which is what keeps {@code
   * EvaluationIsSafeToPasteTest}'s claim true of this line as well as of the table.
   *
   * <p>The last-write clause is here rather than in the guide alone because the number beside it is
   * misread without it: {@code updatedAt} is when the rating last changed, so a promotion rated
   * years ago and re-rated after the instant is counted as new (ADR 39).
   */
  private static String ageLine(Instant since, int oldHeldOut, int newHeldOut) {
    return "# split by rating age at "
        + since
        + ": "
        + oldHeldOut
        + " old (rated before it), "
        + newHeldOut
        + " new (rated on or after it) — a rating's timestamp is its last write, so a re-rated old"
        + " promotion counts as new.";
  }
```

      In `cells`, keep the eight as they are in a local `List<String> whole`, then:

```java
    if (!reading.halves().split()) {
      return whole;
    }
    Halves halves = reading.halves();
    return Stream.concat(
            whole.stream(),
            Stream.of(
                String.valueOf(halves.oldInPool()),
                String.valueOf(halves.oldHits()),
                String.valueOf(halves.newInPool()),
                String.valueOf(halves.newHits())))
        .toList();
```

      and change `widths` to take the column count: `private static int[] widths(List<List<String>>
      rows, int columns)` with `int[] widths = new int[columns];`. Add
      `import java.util.stream.Stream;`, `java.time.Instant`, `java.util.Optional`. Extend the class
      javadoc with a paragraph on the appended columns and the split line.

      Run `EvaluationReportTest`; every test green, **the golden included**.

- [ ] **Step 4 — PLANTED CONTROLS for the two consistency guards.** Comment out the first `throw`,
      run, see `shouldRefuseTheBlockWhenThereAreHalvesButNoInstant` fail, restore, green. Then the
      second `throw`, see `shouldRefuseTheBlockWhenTheInstantAndTheReadingsDisagree` fail, restore,
      green. **Quote both.**

- [ ] **Step 5 — gate and commit.** Full gate, blocking. Stage the three files and commit:
      `Render the rating-age halves and the split line (#276)`.

---

### Task 6: the run threads it, and `--rated-since` reaches the command line

**Files:** `src/main/java/com/robsartin/segue/evaluate/EvaluateRun.java`,
`src/main/java/com/robsartin/segue/evaluate/EvaluateCli.java`,
`src/test/java/com/robsartin/segue/evaluate/EvaluateRunTest.java`,
`src/test/java/com/robsartin/segue/evaluate/EvaluateCliTest.java`,
`src/test/java/com/robsartin/segue/evaluate/EvaluationIsSafeToPasteTest.java`.

- [ ] **Step 1 — RED: the run reports the halves.** In `EvaluateRunTest`, add
      `Optional.empty()` as a fifth constructor argument to the three existing `new EvaluateRun(...)`
      calls (imports `java.util.Optional`, `java.time.Instant`, `com.robsartin.segue.evaluate` is the
      package), which needs the constructor parameter to exist: add
      `Optional<RatingAge> age` as the last constructor parameter with its `@param` and
      `Objects.requireNonNull`, **stored and not yet read** — the deliberate stub. Then add:

```java
  @Test
  @DisplayName("the header states both halves and every row splits when an instant is given")
  void shouldReportBothHalvesWhenTheRunIsSplitByRatingAge() throws IOException {
    try (TinkerGraphStore graph = InventedEvaluation.graph()) {
      List<String> lines = new ArrayList<>();
      RatingAge age =
          new RatingAge(
              Instant.parse("2026-09-06T15:00:00Z"), Set.of(InventedEvaluation.STRANGER));

      List<Reading> readings =
          new EvaluateRun(
                  graph,
                  qid -> false,
                  Map.of(
                      InventedEvaluation.STRANGER, 5,
                      InventedEvaluation.HIDDEN, 5,
                      InventedEvaluation.REJECTED, 1),
                  Equivalences.NONE,
                  Optional.of(age))
              .run(knownList(), 25, lines::add);

      assertThat(lines.get(2))
          .as("two eligible entities, one each side of the instant")
          .contains("1 old (rated before it)")
          .contains("1 new (rated on or after it)");
      assertThat(readings)
          .as("every row carries the halves, and they partition the whole-population cells")
          .allMatch(reading -> reading.halves().split())
          .allMatch(
              reading ->
                  reading.halves().oldHits() + reading.halves().newHits() == reading.hits());
    }
  }
```

      Run `EvaluateRunTest`. Expect the new test to fail on `lines.get(2)` — the split line is not
      there and index 2 is *"# top 25 per setting…"* — quoting the AssertJ message. Quote it.

- [ ] **Step 2 — GREEN: tally the halves over the folds and pass them on.** In `EvaluateRun.run`,
      beside `heldOutTotal`, add `int oldHeldOut = 0;` and `int newHeldOut = 0;`, and inside the fold
      loop after `heldOutTotal += …`:

```java
      // The folds partition the eligible population, so summing each fold's held-out entities by
      // half over the run IS the eligible population's two halves — the same identity the header
      // already shows by printing "held out over all folds" beside "eligible" (issue #276). It
      // needs no second accessor on HeldOut, and HeldOut.every keeps the signature it has.
      if (age.isPresent()) {
        for (String qid : split.heldOut()) {
          if (age.get().isNew(qid)) {
            newHeldOut++;
          } else {
            oldHeldOut++;
          }
        }
      }
```

      Pass `age` as `Scoring.read(...)`'s last argument, and render with
      `EvaluationReport.lines(eligible, HeldOut.EVERY, heldOutTotal, leastLeft, top,
      age.map(RatingAge::since), oldHeldOut, newHeldOut, readings)`. Add a class-javadoc paragraph
      saying the halves are an observation, that `RatingAge` never reaches the report, and that the
      run is unchanged when no instant is given. Re-run `EvaluateRunTest`; all four green — the three
      existing ones are what say the unsplit run did not move.

- [ ] **Step 3 — RED: the flag parses.** In `EvaluateCliTest`, add:

```java
  @Test
  @DisplayName("--rated-since is optional, and parses an ISO-8601 instant when it is given")
  void shouldParseTheInstantWhenTheRatedSinceFlagIsGiven() {
    Path db = dir.resolve("scratch.db");

    assertThat(
            EvaluateCli.parse(
                    new String[] {"--db", db.toString(), "--known", "/nowhere/known.csv"},
                    null,
                    INVENTED_HOME)
                .ratedSince())
        .isEmpty();
    assertThat(
            EvaluateCli.parse(
                    new String[] {
                      "--db", db.toString(),
                      "--known", "/nowhere/known.csv",
                      "--rated-since", "2026-09-06T15:00:00Z"
                    },
                    null,
                    INVENTED_HOME)
                .ratedSince())
        .contains(Instant.parse("2026-09-06T15:00:00Z"));
  }

  @Test
  @DisplayName("a --rated-since that is not an instant is refused with a usage error")
  void shouldRefuseTheRunWhenTheInstantIsMalformed() {
    Path db = dir.resolve("scratch.db");

    assertThatThrownBy(
            () ->
                EvaluateCli.parse(
                    new String[] {
                      "--db", db.toString(),
                      "--known", "/nowhere/known.csv",
                      "--rated-since", "2026-09-06"
                    },
                    null,
                    INVENTED_HOME))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("--rated-since")
        .hasMessageContaining("ISO-8601");
  }
```

      For this to compile, `Options` must gain the component: add
      `Optional<Instant> ratedSince` last, with its `@param` and
      `Objects.requireNonNull(ratedSince, "ratedSince")`, and have `parse` always pass
      `Optional.empty()` — the deliberate stub. Run `EvaluateCliTest` and expect the first to fail
      with *"Expecting Optional to contain 2026-09-06T15:00:00Z but was empty"* and the second with
      *"Expecting code to raise a throwable"*. Quote them.

- [ ] **Step 4 — GREEN: parse it.** In `EvaluateCli`: extend `USAGE` with
      `" [--rated-since <ISO-8601 instant, e.g. 2026-09-06T15:00:00Z>]"`; declare
      `Instant ratedSince = null;` in `parse`; add
      `case "--rated-since" -> ratedSince = instant(flag, value);` to the switch; return
      `new Options(database, known, top, Optional.ofNullable(ratedSince))`; and add:

```java
  private static Instant instant(String flag, String value) {
    try {
      return Instant.parse(value);
    } catch (DateTimeParseException e) {
      throw usage(flag + " takes an ISO-8601 instant like 2026-09-06T15:00:00Z, got " + value);
    }
  }
```

      Re-run `EvaluateCliTest`; green, the six existing tests included.

- [ ] **Step 5 — GREEN: wire the read.** In `EvaluateCli.run`, after the ratings are read and
      logged:

```java
      // Read only when asked: a run with no --rated-since reads no timestamp at all, which is
      // ADR 16's data minimisation falling out of the shape rather than being argued for. The
      // timestamps are resolved through the same merges the ratings were, so the two maps are
      // keyed alike and the halves cannot be read off another row (issue #276).
      Optional<RatingAge> age =
          options
              .ratedSince()
              .map(
                  since ->
                      RatingAge.of(
                          since,
                          merges.resolveUpdatedAt(affinity.readUpdatedAt()),
                          ratings.keySet()));

      new EvaluateRun(
              graph, RecognitionInstitutions::isRecognitionInstitution, ratings, merges, age)
          .run(options.known(), options.top(), log::info);
```

      Extend the class javadoc's "It reads ratings and cannot read a note" paragraph with a sentence
      saying it also reads when a rating last changed, through the read that carries neither the note
      nor the score, and that the note fence is unchanged. Run
      `./gradlew test --tests 'com.robsartin.segue.evaluate.*'`; green.

- [ ] **Step 6 — RED: the split block is safe to paste too.** In `EvaluationIsSafeToPasteTest`, add a
      second test that runs the same fixture with the flag. The fixture rates `HIDDEN` at
      `2026-02-01T08:00:00Z`, so an instant of `2026-01-01T00:00:00Z` puts it in the new half:

```java
  @Test
  @DisplayName(
      "the split line reaches the log with the instant on it, and no label, note or id with it")
  void shouldEmitTheSplitLineAndNothingElseWhenTheRunIsSplitByRatingAge() throws IOException {
    Path db = dir.resolve("scratch.db");
    Path known = dir.resolve("known.csv");
    writeTheFixture(db, known);
    captured.list.clear();

    EvaluateCli.main(
        new String[] {
          "--db", db.toString(),
          "--known", known.toString(),
          "--rated-since", "2026-01-01T00:00:00Z"
        });

    List<String> everyLine =
        List.copyOf(captured.list).stream().map(ILoggingEvent::getFormattedMessage).toList();

    assertThat(everyLine)
        .as("the split line was actually printed — without this the assertions below are vacuous")
        .anyMatch(line -> line.startsWith("# split by rating age at 2026-01-01T00:00:00Z"));
    assertThat(everyLine)
        .as("no line carries a label (ADR 51, ADR 63, ADR 65)")
        .noneMatch(line -> line.contains(LABEL));
    assertThat(everyLine)
        .as("no line carries a note (ADR 33, ADR 51)")
        .noneMatch(line -> line.contains(NOTE));
    assertThat(everyLine)
        .as("no line carries anything qid-shaped — the instant is rendered from the parsed value")
        .noneMatch(line -> A_QID.matcher(line).find());
  }
```

      Extract the existing test's fixture building into `private void writeTheFixture(Path db, Path
      known)` and have both tests call it, so the label, the note, the id inside the note and the
      rating reach the log through exactly one read path. Add a paragraph to the class javadoc: the
      instant is the one operator-supplied fact in the block, it is rendered from the parsed
      `Instant` and never from the argument string, and the `T` and the `Z` are not what the qid
      pattern looks at — there is no `Q` in an instant at all.

      Run the class. It should pass immediately — this is a **guard**, not a behaviour, which is why
      the next step is where its evidence comes from.

- [ ] **Step 7 — PLANTED CONTROL: a qid in the new header clause.** In `EvaluationReport.ageLine`,
      append `+ " Q0900901"` to the returned string. Run `EvaluationIsSafeToPasteTest` and
      `EvaluationReportTest` and confirm the qid assertions in **both**
      (`shouldEmitTheSplitLineAndNothingElseWhenTheRunIsSplitByRatingAge` and
      `shouldCarryNoIdentifierWhenTheBlockStatesTheInstant`) fail. **Quote both failures.** Remove
      the plant and re-run; green.

- [ ] **Step 8 — PLANTED CONTROL: the note fence is exactly where it was.** In `EvaluateCli.run`,
      add `affinity.readAll();` immediately after the `readRatings` line. Run
      `./gradlew test --tests 'com.robsartin.segue.arch.ArchitectureTest'` and confirm
      `theEvaluationHarnessReadsRatingsAndNeverNotes` fails naming `EvaluateCli` calling
      `AffinityStore.readAll`. **Quote the violation** — this is the evidence that the harness's new
      timestamp read widened nothing. Remove the line and re-run; green.

- [ ] **Step 9 — gate and commit.** Full gate, blocking. Stage the five files and commit:
      `Split the held-out population by rating age (#276)`.

---

### Task 7: the ADR amendment and the developer guide

**Files:** `docs/adr/0065-an-offline-evaluation-harness-for-the-recommender.md` (append only),
`docs/developer-guide.md`.

**Say this out loud in the step report: there is no unit test in this task, and here is why.** No
behaviour changes — the code is already as this task describes it. The verification method is the
full gate over an otherwise unchanged tree: `AdrIndexTest` (untouched index), `AdrCitationsTest` (no
commit hash is cited, and no code span below is 7–40 hex characters), `DocumentationLinksTest` (every
relative link below resolves), `DeveloperGuideEnumerationsTest` (the guide's tables and diagram — the
ArchUnit row landed in task 1 and no package, import edge or dev task changes here),
`DeveloperGuideEvaluateExamplesTest` (the new runbook command parses, through `EvaluateCli.parse`),
and `javadoc -Werror`.

- [ ] **Step 1 — append the amendment to ADR 65.** At the very end of the file, after the last line
      of the fold amendment, append (front matter untouched, nothing above edited):

```markdown

**Amendment (2026-09-06, issue #276): the port gained a read that carries when a rating changed and
nothing else, and the harness reads its held-out population in two halves by rating age.**

Nothing above is withdrawn and no decision above is edited, the fold amendment included. The eligible
population, the interval, the fold count, the grid, the output contract's shape and every fence are
exactly as decided. What changes is that the report can be asked one more question of the same split.

**Why an instrument, and not a reading.** [ADR 45](0045-recommend-by-normalised-lift-with-routes.md)'s
2026-09-06 amendment for issue #272 recorded that the shipped setting's hit rate fell after a deck
session and said the table cannot say why. Two explanations survive it: the deck deals what the same
ranking already passed over, so each session promotes entities the top twenty-five cannot reach by
construction; or the taste layer widened into territory the routes serve less well. Reading the
held-out population in two halves separates them — under the first the new half's rate sits near zero
while the old half holds, under the second the halves land near each other. **No reading is taken
here**, and ADR 45 is not touched.

**The alternative this ADR refused, and the clause of the refusal that moved.** "Read
`AffinityStore.readAll`, so the report could break the split down by note or by recency" is above,
refused because `readAll` carries the note, `onlyTheRatingsToolReadsANote` is where that line lives,
and *nothing the harness reports needs anything but the score*. Every clause survives except the
last: a reading has now asked for the column. So the port answers it with a read that carries the
timestamp and neither the note nor the score — `AffinityStore.readUpdatedAt`, qid to `Instant`,
implemented from the `updated_at` column and contract-tested where this port's contract is tested —
and **the harness still never calls `readAll`**. `theEvaluationHarnessReadsRatingsAndNeverNotes` is
where it was, to the character, and the planted control for this work was that line being written
into the harness and the fence seen firing.

**A new fence lands with the new read.** `onlyTheEvaluationHarnessReadsWhenARatingChanged` keeps
`readUpdatedAt` inside `evaluate`. The values are not personal in the way a note is; the **keys**
are every entity the owner has rated, which is the single call
[ADR 39](0039-affinity-capture-and-read.md) refused to put in front of a model, whatever is on the
other side of the arrow. It is a new rule rather than a widening of either sibling, for the reason
this ADR gives for the harness's own fences: a rule named for one tool and quoted in an immutable ADR
does not get stretched to cover a second. It landed in the same commit as the method, so there was no
window in which the read existed unfenced, and in the same commit as its row in the developer guide's
table, which the guide's own test compares against the declared rules exactly.

**`evaluate --rated-since <ISO-8601 instant>`, optional.** Given, the eligible population is
partitioned by whether its rating's timestamp falls before the instant, every row gains four cells —
`in pool` and `hits` for each half — and the header names the instant and the size of each half. Not
given, **the block is byte-identical to today's**, which is what keeps every reading on record
comparable; a golden test pins the unsplit block character for character and was seen failing against
a planted change to the renderer before the flag was written. The whole-population `in pool` and
`hits` are what issue #245's rule reads; the halves are observations and decide nothing.

**Where the split lives.** A small pure value built once per run, not a widening of `HeldOut`: fold
*k* and fold *k + 1* disagree about which entities are hidden and agree exactly about which are old,
so the age is not a property of the split and `HeldOut.every` keeps the signature it has. The
report's two half-sizes are summed from the folds rather than read off a new accessor, because the
folds partition the eligible population — the identity the header already shows by printing what was
held out over all folds beside the eligible count. `Scoring` tallies the halves inside the two passes
it already makes, so no sweep, rank or walk is repeated and the run costs what it cost.

**The type-level fence above is untouched.** `EvaluationReport.lines` takes three more arguments —
two counts and the instant — and there is still nowhere in the signature to put an identifier. The
value that knows which entities are new carries a qid set and is deliberately not passed to it. The
instant is rendered from the parsed value and never from the string the operator typed, so the one
operator-supplied fact in the whole block cannot carry an identifier into it; the guard that asserts
so was seen firing on a qid planted into that clause.

**Known limit, stated in the header as well as here: the timestamp is the last write.** ADR 39 keeps
one row per entity and lets the later rating win, so a promotion rated long ago and re-rated after
the instant lands in the new half. The census's `taste` deltas bound how many ratings changed, not
how many are new. The halves are therefore an observation about a population, not a count of new
promotions, and a reading that treats them as one is reading more than the instrument says.

**What it does not change.** Not the rule that judges a reading, not the grid, not the fold count,
not the eligible population, not the interval, not the promotion threshold, not the suppression
boundary, not the scorer default, not the floor. No line of `recommend`'s output moves, and a run
with no instant reads no timestamp at all.

Alternatives rejected: tagging ratings with their source, which separates the two explanations
exactly rather than by proxy but is a schema change to `affinity`, and
[ADR 42](0042-store-p31-and-rederive-kind-at-projection.md) says the next schema change gets a real
migration path — it is filed only if the age split cannot settle the question; widening the harness
to `readAll` (above); making the split mandatory with a default instant (a boundary nobody typed
breaks the row-for-row diff that is the whole value of the instrument); a fold column or one row per
half (thirty-two rows of half-sized counts, and the cells issue #245's rule reads would have to be
re-derived by the reader — the fold amendment's reason, restated); and treating a rated entity with
no timestamp as old (a lenient read feeding a guard turns "cannot tell" into "old", in a cell
indistinguishable from a real one).

**Nothing here is unit-testable, and that is said out loud rather than left implied.** This entry
records a decision whose code landed with its own tests — the port read, the merge resolution of the
timestamps, the halves and both of their guards, the report's two consistency guards, and the two
fences — each with a planted control. The verification of the *document* is the full gate over an
otherwise unchanged tree: `AdrIndexTest`, `AdrCitationsTest`, `DocumentationLinksTest` for the
relative links above, and `javadoc -Werror` inside `./gradlew check`.
```

- [ ] **Step 2 — the guide's runbook.** In `docs/developer-guide.md`, in `## Calibrating the
      recommender`, after the paragraph beginning "**Every fold of that split is read, not one.**",
      insert (the outer fence below is four backticks only so the example inside it survives; write
      the example into the guide with three, like every other example in that chapter):

````markdown
**Two halves by rating age, when you ask for one.** `--rated-since <ISO-8601 instant>` is optional.
Given, the eligible population is split by whether each rating was last written before that instant
or on or after it, every row gains `old in pool`, `old hits`, `new in pool` and `new hits`, and the
header names the instant and the size of each half. Not given, the block is **byte-identical** to
every one already on record, which is what keeps two runs diffable row by row. The whole-population
`in pool` and `hits` are the cells a reading is judged on; the halves are an observation.

```bash
./gradlew evaluate --args="--db $HOME/.segue/segue.db --known $HOME/known.csv --rated-since 2026-09-06T15:00:00Z"
```

**The timestamp is the last write, not the first.** One row per entity
([ADR 39](adr/0039-affinity-capture-and-read.md)), so a promotion you rated years ago and re-rated
after the instant counts as new. The report's split line says so on its own line, because the numbers
beside it are misread without it. `graphCensus`'s `taste` deltas bound how many ratings *changed*,
not how many are new.
````

      Then, in the same chapter's `### What it is not allowed to do` list, extend the "See a note"
      bullet with a sentence: "It reads when a rating last changed through `readUpdatedAt`, which
      carries neither the note nor the score, and
      `onlyTheEvaluationHarnessReadsWhenARatingChanged` keeps that read inside this package."

- [ ] **Step 3 — gate.** Full gate, blocking. `DeveloperGuideEvaluateExamplesTest` is what says the
      new example parses; quote that it passed.

- [ ] **Step 4 — commit.** Stage the two documents by explicit path and commit:
      `Record the rating-age split in ADR 65 and the guide (#276)`.

---

### Task 8: hand the run back to the owner

**No file changes, and nothing is run against a real database.**

- [ ] **Step 1 — confirm the tree.** `git status --short` is clean; `git log --oneline -8` shows the
      seven commits above on `276-ready`. Run the gate once more, blocking, from a clean tree and
      quote the result.

- [ ] **Step 2 — say what the owner runs, and do not run it.** The split reading is his to take, on
      his own database, with the instant of his 2026-09-06 deck session:

```bash
./gradlew evaluate --args="--db $HOME/.segue/segue.db --known $HOME/known.csv --rated-since <the session's instant>"
```

      Report that a run without the flag prints exactly the block it printed before, that the flag
      adds four columns and one header line, and that the run costs the same either way.

- [ ] **Step 3 — name the follow-up, and file nothing.** The fifth reading — taken after the owner's
      next deck session, judged by issue #245's rule unchanged on the whole-population cells, with
      the halves recorded as the observation that settles issue #272's question — is a separate
      issue, and ADR 45 is amended there rather than here. Report that it is wanted, and report the
      one limit it must state out loud when it applies the rule: the halves are drawn on the last
      write, so a re-rated old promotion is in the new half, and the census's `taste` deltas bound
      how many ratings changed rather than how many are new. **Do not amend, reinterpret or "adjust"
      issue #245's rule here.**
