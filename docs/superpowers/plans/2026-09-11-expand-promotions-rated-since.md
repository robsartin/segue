# `expandPromotions --rated-since` — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** close issue #307 — `expandPromotions --rated-since <instant>` expands only the promotions
whose rating's last write is on or after the instant. Absent, nothing about the tool changes and no
timestamp is read at all. `RatingAge` moves to `domain` and is reused rather than copied; the
`readUpdatedAt` fence widens to admit `..expand..` and is renamed to say so; the runbook chapter
gains the variant; ADR 65 and ADR 66 each gain one dated amendment.

**Architecture:** The filter is composed at `ExpandCli`, the one class in `expand` that touches the
store, exactly as `EvaluateCli` composes it in `evaluate`. `RatingAge.isNew` is the predicate and
`Stream.filter` is the loop — no new filtering method is written. A new record `RatedSince(Instant,
int)` carries the applied filter into `ExpansionReport`, which renders it as one `#` clause under
the block's own header in both blocks. `ExpandRun` never filters; it reports the filter its caller
applied.

**Tech Stack:** Java 25, Gradle (plain `./gradlew`), JUnit 5, AssertJ, TinkerGraph, SQLite, ArchUnit.

**Spec:** `docs/superpowers/specs/2026-09-11-expand-promotions-rated-since-design.md`

## Global Constraints

- **Pure TDD / red first.** Every behaviour: write the test, **run it, observe a real assertion
  failure**, then the minimum code. **A compile error is never a red.** Where a new API is needed
  before a test can compile, this plan says so explicitly and splits the step: add the **stub**
  (the signature, ignoring the new argument), then the test, then observe the assertion failure,
  then the body. If a first run ends in `BUILD FAILED` on compilation, the step has proved nothing.
  **Quote the actual failure text in the task report.**
- **Every guard gets a positive control, and where a check cannot be seen red the plan says so
  rather than letting it look like evidence.** Task 2's and Task 3's reds are `containsExactly`
  diffs whose "not expected" list must be empty — that emptiness is the control proving nothing but
  the new line moved. Task 5 carries two: the fence firing on `ExpandCli` before it is widened, and
  the plant in `census` firing after. The three the issue asks for are Task 5 Step 2 (a promotion
  rated before the instant is excluded and `considered` drops), Task 5 Step 2 (a re-rated old
  promotion is included) and Task 5 Step 9 (the fence on a third package).
- Test names `should<Expected>When<Condition>` with `@DisplayName`.
- **Mikado**: the gate is green before every commit, and each task ends green and is independently
  reviewable. **Stage by explicit path, git stderr visible — never `git add -A`, never
  `2>/dev/null` on `git add`.** Read `git status` before every commit. Commits end
  `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`. **Never cite a
  `.superpowers/` path from a committed file.**
- Gate, **blocking, never backgrounded**:
  `SEGUE_REQUIRE_BROWSER=true SEGUE_REQUIRE_GRAPHVIZ=true ./gradlew check --rerun-tasks`. Run
  `./gradlew spotlessApply` before each gate.
- **Only JDK 25 is installed and Gradle launches on it.** Plain `./gradlew`; never
  `/usr/libexec/java_home -v 21` (it silently returns 25).
- **Never run a writing dev task** — `expandPromotions`, `evaluate`, `own`, `ownClaim`,
  `retractEntity`, `rate`, any seeding task. `~/.segue/segue.db` is never read, written, copied or
  created. Every database here is in-memory or a `@TempDir` file.
- **`docs/` is a declared input of `test`** (`build.gradle.kts:150`), so a document edit re-runs the
  suite. Run the per-task loops **without** `--rerun-tasks`; the point is to prove the declaration
  works. `CLAUDE.md` is **not** a declared input — only `docs` and `README.md` are — so the
  `CLAUDE.md` edit in Task 1 is verified by the gate and by reading, and the task report must say so.
- **No commit hash, no `.superpowers/` path and no figure from the owner's graph enters any file
  under `docs/adr`.** `AdrCitationsTest` matches a backticked run of 7–40 hex characters, and an
  invented qid like `Q0900703` is seven characters behind a `Q` — keep every test identifier out of
  `docs/adr`.
- **Invented ids only, ADR 58's leading zero.** The three this plan introduces are `Q0900703`,
  `Q0900705` and `Q0900706`; none appears in `src/test` today. They need no
  `StandInQidsDenoteNothingTest` allowlist entry, because that sweep exempts the leading-zero form.
- **After `./gradlew spotlessApply`, re-read any javadoc this plan edits** and confirm the
  `{@code …}` spans are intact. google-java-format reflows javadoc paragraphs and will break inside
  an inline tag. A wrapped span renders fine; what must not happen is a lost or unbalanced brace. To
  put a span on one source line, change the clause *before* it — never lengthen the sentence to make
  it fit.
- **`evaluate` does not change behaviour anywhere in this plan.** Its flag, its report, its halves
  and `EvaluationReport` are untouched. Task 1 moves a class it uses and adds imports; nothing else.
- **`CensusReport` is not this issue.** The runbook's step-5 row `` | `edges` by source | `` is
  `graphCensus`'s label for a different tool. Do not touch it.

**The one instant used as an example throughout:** `2026-09-08T00:00:00Z`. It is an example, not a
reading; nothing derived from the owner's database appears in any file.

**The since clause, the exact text both blocks print** (one literal, defined once in
`ExpansionReport` and pinned once as a literal in `ExpansionReportTest`):

```
# only promotions rated on or after 2026-09-08T00:00:00Z: 7 excluded (rated before it) — a rating's timestamp is its last write, so a re-rated old promotion counts as new.
```

---

## Task 1 — Move `RatingAge` into `domain`

Files: `src/main/java/com/robsartin/segue/evaluate/RatingAge.java` →
`src/main/java/com/robsartin/segue/domain/RatingAge.java`;
`src/test/java/com/robsartin/segue/evaluate/RatingAgeTest.java` →
`src/test/java/com/robsartin/segue/domain/RatingAgeTest.java`;
`evaluate/EvaluateCli.java`, `evaluate/EvaluateRun.java`, `evaluate/Scoring.java`,
`src/test/java/com/robsartin/segue/evaluate/EvaluateRunTest.java`,
`src/test/java/com/robsartin/segue/evaluate/ScoringTest.java`; `docs/developer-guide.md`;
`CLAUDE.md`.

**This task has no red, and that is honest rather than an omission.** It changes no behaviour: the
same record, the same tests, a different package. Its verification is the moved `RatingAgeTest`
still passing **at its new package**, the two `domain` ArchUnit rules accepting it, and the full
gate. The task report must say which method was used, in those words.

- [ ] **Step 1 — move both files with `git mv`.**

  ```
  git mv src/main/java/com/robsartin/segue/evaluate/RatingAge.java src/main/java/com/robsartin/segue/domain/RatingAge.java
  git mv src/test/java/com/robsartin/segue/evaluate/RatingAgeTest.java src/test/java/com/robsartin/segue/domain/RatingAgeTest.java
  ```

  `git mv` and not a copy, so the review sees a rename rather than a new file and a deletion.

- [ ] **Step 2 — change both `package` lines to `com.robsartin.segue.domain`.**

- [ ] **Step 3 — repair the one javadoc reference the move breaks.** `RatingAge`'s class javadoc
  says:

  ```java
   * the age is not a property of the split: {@link HeldOut#every} keeps the signature it has, and
  ```

  `HeldOut` is in `evaluate`, and `domainHasNoThirdPartyDependencies` allows a `domain` class to
  depend on `..domain..`, `java..` and `javax..` and nothing else. Make it plain code text, with no
  link and no import:

  ```java
   * the age is not a property of the split: {@code HeldOut.every} keeps the signature it has, and
  ```

  In the same javadoc, reword `of`'s second `@param` so it describes the parameter rather than the
  harness's caller — a second caller arrives in Task 5 and passes a different set:

  ```java
   * @param rated the qids whose age the caller needs, resolved through the merges the same way
   *     {@code updatedAt} was — the evaluation harness passes every rated entity, the promotion
   *     expander passes its promotions
  ```

  Change nothing else in the file. In particular the refusal sentence, the boundary rule ("on or
  after the instant is new") and the last-write paragraph stay exactly as they are.

- [ ] **Step 4 — add `import com.robsartin.segue.domain.RatingAge;`** to `EvaluateCli`,
  `EvaluateRun`, `Scoring`, `EvaluateRunTest` and `ScoringTest`. Those are the five files that name
  the type; confirm with `grep -rn 'RatingAge' src/main src/test` that the list is complete before
  and after.

- [ ] **Step 5 — run the moved test at its new package and confirm it really ran.**

  ```
  ./gradlew test --tests 'com.robsartin.segue.domain.RatingAgeTest'
  ```

  Expected: `BUILD SUCCESSFUL`, **three tests executed**. A `--tests` filter that matches nothing is
  the failure mode here — if Gradle reports no tests found, or the HTML report under
  `build/reports/tests/test` shows zero for that class, the package line or the file location is
  wrong. Record the count in the task report; "BUILD SUCCESSFUL" alone is not evidence.

- [ ] **Step 6 — run the architecture rules.**

  ```
  ./gradlew test --tests '*ArchitectureTest'
  ```

  Expected green. The two that could have caught this are `domainValueTypesAreRecordsOrEnums` (a
  record, so it passes) and `domainHasNoThirdPartyDependencies` (`java.time` and `java.util` only).

- [ ] **Step 7 — name the class where the documents describe `domain`.** In
  `docs/developer-guide.md`, the package table's `domain` row, extend the sentence that introduces
  `KnownList` so `RatingAge` is named beside it — something of this shape, in the row's own voice:

  > … plus `KnownList` — the pure rules that turn a `--known` file and the ratings map into the
  > populations the dev tools need … — and `RatingAge`, the same kind of rule over the other column
  > of the same table: which rated entities were last written on or after an instant. It is read by
  > `evaluate`, which splits its held-out population by it, and by `expand`, which filters its
  > promotions by it, so the two tools cannot disagree about what "since" means.

  In `CLAUDE.md`, the `domain/` block (around line 70), add a matching clause naming `RatingAge` and
  the two readers. Keep the block's terse style; do not restate the guide.

  Neither edit is read by a test. The guide's package-table check derives **package names**, not row
  prose, and `CLAUDE.md` is not a declared input of `test` at all. Both are gate-verified prose, and
  the task report says so.

- [ ] **Step 8 — format, gate, commit.**

  ```
  ./gradlew spotlessApply
  SEGUE_REQUIRE_BROWSER=true SEGUE_REQUIRE_GRAPHVIZ=true ./gradlew check --rerun-tasks
  ```

  Re-read `RatingAge`'s javadoc after `spotlessApply` and confirm the `{@code HeldOut.every}` span
  is intact. Then `git status`, stage by explicit path, commit:

  > Move RatingAge into domain, where both its readers can reach it (#307)

---

## Task 2 — `ExpansionReport` names the population the block covers

Files: `src/main/java/com/robsartin/segue/expand/RatedSince.java` (new),
`src/main/java/com/robsartin/segue/expand/ExpansionReport.java`,
`src/test/java/com/robsartin/segue/expand/ExpansionReportTest.java`.

**Read first.** `ExpansionReport.render` measures the label and count columns from `Row` entries
alone (`if (entry instanceof Row row)`), so a `#` line added under the header changes no width and
re-pads nothing. The expected diff against each existing golden block is **exactly one inserted
line**. If you find yourself re-padding counts, stop — something else is wrong.

- [ ] **Step 1 — the stub, so the test can compile and then fail on an assertion.** Create
  `RatedSince`:

  ```java
  package com.robsartin.segue.expand;

  import java.time.Instant;
  import java.util.Objects;

  /**
   * The filter a run applied to its promotions, and what it cost (issue #307).
   *
   * <p><b>A record rather than two arguments on the renderer.</b> {@code EvaluationReport.lines}
   * takes an instant and its two counts as three positional arguments and then guards at runtime
   * against the combination that cannot happen. Wrapping them makes that combination
   * unrepresentable: a block with no filter passes {@code Optional.empty()} and has no count to
   * disagree with. It also keeps {@link #excluded} from sitting two positions along a call from
   * {@code maxNewEdges} as a second bare {@code int}, which is a swap no compiler can see.
   *
   * <p><b>There is nowhere here to put an identifier.</b> {@code Instant.toString} emits only
   * digits, {@code -}, {@code :}, {@code .}, {@code T} and {@code Z}, so the one operator-supplied
   * fact the block carries cannot bring a qid into it however the flag was spelled — the same
   * property {@code EvaluationReport} relies on for its own split line.
   *
   * @param since the instant the operator gave, parsed — never the string they typed
   * @param excluded how many promotions were dropped because their rating's last write fell before
   *     it
   */
  public record RatedSince(Instant since, int excluded) {

    public RatedSince {
      Objects.requireNonNull(since, "since");
      if (excluded < 0) {
        throw new IllegalArgumentException("excluded cannot be negative, got " + excluded);
      }
    }
  }
  ```

  In `ExpansionReport`, add the two new arities **ignoring the new argument for now** — the stub
  exists only so Step 2's test compiles:

  ```java
    /** Render the whole block, header included, saying which population it covered. */
    public static List<String> lines(ExpansionTally tally, Optional<RatedSince> filter) {
      Objects.requireNonNull(filter, "filter");
      return render(HEADER, body(tally));
    }

    /** Render what a dry run would visit, saying which population it would have covered. */
    public static List<String> dryRunLines(Preflight preflight, Optional<RatedSince> filter) {
      Objects.requireNonNull(filter, "filter");
      return render(DRY_RUN_HEADER, dryRunBody(preflight));
    }
  ```

  and make today's two methods delegate with `Optional.empty()`, extracting the dry run's body list
  into a private `dryRunBody(Preflight)` so both arities build it once.

- [ ] **Step 2 — RED. Pin both with-instant forms.** In `ExpansionReportTest`, add the constants and
  two tests. The clause is a **literal here**, exactly as `GOLDEN_BLOCK`'s header is a literal and
  for the same reason: reading it off the constant it is meant to pin would prove nothing.

  ```java
    /** The example instant, invented; nothing here was read from a database. */
    private static final Instant SINCE = Instant.parse("2026-09-08T00:00:00Z");

    /**
     * The clause both blocks print when an instant was given, character for character. A literal,
     * not ExpansionReport's constant — see GOLDEN_BLOCK.
     */
    private static final String SINCE_LINE =
        "# only promotions rated on or after 2026-09-08T00:00:00Z: 7 excluded (rated before it) —"
            + " a rating's timestamp is its last write, so a re-rated old promotion counts as new.";

    /** The golden block with the clause inserted under its header, and nothing else moved. */
    private static List<String> withSinceLine(List<String> block) {
      List<String> withIt = new ArrayList<>(block);
      withIt.add(1, SINCE_LINE);
      return List.copyOf(withIt);
    }

    @Test
    @DisplayName("the block names the instant and what it excluded when a filter was applied")
    void shouldNameTheInstantWhenTheBlockCoversOnlyWhatWasRatedSince() {
      assertThat(ExpansionReport.lines(goldenTally(), Optional.of(new RatedSince(SINCE, 7))))
          .containsExactlyElementsOf(withSinceLine(GOLDEN_BLOCK));
    }
  ```

  and the dry-run twin, pinning the same clause under `DRY_RUN_HEADER` over
  `new Preflight(4, 2, 1)` and `new RatedSince(SINCE, 7)` — write that block's six expected lines
  out as literals beside the existing `shouldRenderTheDryRunBlockWhenNothingIsAppended`, with
  `SINCE_LINE` second.

  **Do not touch `GOLDEN_BLOCK` or `shouldRenderTheDryRunBlockWhenNothingIsAppended`.** Their
  byte-for-byte survival is the other half of what this task proves.

- [ ] **Step 3 — run it and observe a real failure.**

  ```
  ./gradlew test --tests '*ExpansionReportTest'
  ```

  Expected: **two failing tests, both assertion failures, not a compile error.** Each is AssertJ's
  `containsExactly` diff, shaped like:

  ```
  but some elements were not found:
    ["# only promotions rated on or after 2026-09-08T00:00:00Z: 7 excluded (rated before it) — a rating's timestamp is its last write, so a re-rated old promotion counts as new."]
  and others were not expected:
    []
  ```

  **Quote it, and confirm the "not expected" list is empty in both.** That emptiness is the positive
  control for this step: it proves the only difference is the missing clause, that no column moved,
  and that the fixture is otherwise correct. If any counted row appears in either list, the
  `Read first` note is wrong about `render` and the task stops for a re-read.

- [ ] **Step 4 — GREEN. Render the clause.** In `ExpansionReport`, one private method, used by both
  arities:

  ```java
    /**
     * Said under the header when a filter was applied, and not at all when none was.
     *
     * <p><b>A clause rather than a counted row.</b> A row would print on every run, and on a run
     * with no instant it would read {@code excluded 0} — a count of a filter nobody applied, a
     * line a reader would believe; ADR 65 keeps its own no-instant block byte-identical for the
     * same reason. It would also land on every block already pasted into an issue, for a value
     * only one run in many carries.
     *
     * <p>The last-write clause is here rather than in the guide alone because the number beside it
     * is misread without it: {@code updated_at} is when the rating last changed, so a promotion
     * rated years ago and re-rated after the instant is expanded again (ADR 39).
     */
    private static String sinceLine(RatedSince filter) {
      return "# only promotions rated on or after "
          + filter.since()
          + ": "
          + filter.excluded()
          + " excluded (rated before it) — a rating's timestamp is its last write, so a re-rated"
          + " old promotion counts as new.";
    }
  ```

  and have `render` take the optional filter so both blocks insert it in one place — the header,
  then the clause when present, then the body. Update the class javadoc's type-level paragraph:
  the signature now also carries an `Instant` and an `int`, and there is still nowhere in it to put
  an identifier.

- [ ] **Step 5 — re-run, and check the unfiltered blocks did not move.**

  ```
  ./gradlew test --tests '*ExpansionReportTest'
  ```

  Expected: all tests green, `shouldRenderTheWholeBlockWhenATallyIsGiven` and
  `shouldRenderTheDryRunBlockWhenNothingIsAppended` included. Those two are the regression: their
  passing is what says the no-instant block is byte-identical to today's.

- [ ] **Step 6 — format, gate, commit.** `./gradlew spotlessApply`, re-read the two javadocs for
  intact `{@code …}` spans, then the full gate, then `git status`, stage by explicit path, commit:

  > Say which population an expansion block covered, when a filter was applied (#307)

---

## Task 3 — `ExpandRun` carries the filter to the block

Files: `src/main/java/com/robsartin/segue/expand/ExpandRun.java`,
`src/main/java/com/robsartin/segue/expand/Preflight.java`,
`src/test/java/com/robsartin/segue/expand/ExpandRunTest.java`.

**`ExpandRun` does not filter.** It is handed the promotions its caller decided on and reports the
filter its caller applied. That is deliberate: the population is composed at `ExpandCli` from
`KnownList.promoted` and the merges, and a second place that knows how to drop a promotion is a
second answer to the same question.

- [ ] **Step 1 — the stub.** Add the two arities, ignoring the new argument:

  ```java
    public Preflight dryRun(List<String> promotions, Optional<RatedSince> filter, Consumer<String> lines)
    public ExpansionTally run(List<String> promotions, Optional<RatedSince> filter, int maxNewEdges, Consumer<String> lines)
  ```

  Each `Objects.requireNonNull(filter, "filter")` and then calls the existing report method that
  takes no filter. Make today's two arities delegate to these with `Optional.empty()`. **Do not
  touch any existing call site in any test** — the delegating forms exist so that fifteen
  assertions whose subject is not the filter do not have to change.

  The new argument sits **beside `promotions`**, which is what it describes, rather than beside
  `maxNewEdges`.

- [ ] **Step 2 — RED. Two tests, one per block.** In `ExpandRunTest`, add:

  ```java
    /** The example instant, invented. */
    private static final Instant SINCE = Instant.parse("2026-09-08T00:00:00Z");

    @Test
    @DisplayName("a dry run says which population it would have covered when a filter was applied")
    void shouldNameTheInstantWhenADryRunCoversOnlyWhatWasRatedSince() {
      List<String> lines = new ArrayList<>();

      run.dryRun(PROMOTIONS, Optional.of(new RatedSince(SINCE, 2)), lines::add);

      assertThat(lines)
          .as("the clause reaches the consumer, not just the renderer")
          .anyMatch(line -> line.startsWith("# only promotions rated on or after " + SINCE))
          .contains(ExpansionReport.DRY_RUN_HEADER);
    }
  ```

  and the real-run twin modelled on `shouldEmitTheReportsHeaderWhenTheRunFinishes` (the scripted
  adapter, one seed, its own `@TempDir` database) using seed `Q0900703` and
  `scriptedRun.run(List.of(seed), Optional.of(new RatedSince(SINCE, 2)), 10, lines::add)`, asserting
  the same clause and `ExpansionReport.HEADER`.

  The `.contains(…HEADER)` half of each assertion is the positive control: it must pass in the red
  run, which proves the block was rendered and reached the consumer, so the red is on the missing
  clause and not on a run that produced nothing.

- [ ] **Step 3 — run it and observe a real failure.**

  ```
  ./gradlew test --tests '*ExpandRunTest'
  ```

  Expected: **two failing tests, both assertion failures.** AssertJ reports the `anyMatch` half
  first, shaped like:

  ```
  [the clause reaches the consumer, not just the renderer]
  Expecting any element of:
    ["# segue promotion expansion — dry run: appends nothing. Aggregates only (ADR 51, ADR 63).",
     "",
     "promotions",
     "  considered    4",
     "  in the graph  2",
     "  minted        1"]
  to match given predicate but none did.
  ```

  **Quote it, and confirm the listed lines include the header** — that is the control, and it means
  the block was produced with the clause missing rather than not produced at all.

- [ ] **Step 4 — GREEN.** Pass the filter through to `ExpansionReport.dryRunLines` and
  `ExpansionReport.lines`. Re-run: green, and every existing `ExpandRunTest` assertion still green.

- [ ] **Step 5 — say what `considered` means under a filter.** In `Preflight`, extend the
  `considered` javadoc:

  ```java
   * @param considered every promotion the run was handed — which, when {@code --rated-since} was
   *     given, is the promoted population **after** the instant filtered it. What the instant
   *     removed is not counted here; it is named on the block's own clause, so a pasted block says
   *     what population it covered
  ```

  Add the matching clause to `ExpandRun`'s class javadoc, saying that this class never filters.

- [ ] **Step 6 — format, gate, commit.**

  > Carry the applied filter from the run to the block (#307)

---

## Task 4 — `--rated-since` on the command line

Files: `src/main/java/com/robsartin/segue/expand/ExpandCli.java`,
`src/test/java/com/robsartin/segue/expand/ExpandCliTest.java`.

Parsing only. Nothing reads a timestamp yet, nothing filters yet, and the fence is untouched — the
store read is Task 5, and it lands in the same commit as the fence that admits it.

- [ ] **Step 1 — the stub.** Add the component to `Options`:

  ```java
    record Options(Path database, int maxNewEdges, boolean dryRun, Optional<Instant> ratedSince) {}
  ```

  and have `parse` return `Optional.empty()` for it, always. Nothing outside `parse` constructs
  `Options`, so this is additive; confirm with `grep -rn 'new Options(' src`.

- [ ] **Step 2 — RED. The value arrives.** In `ExpandCliTest`:

  ```java
    @Test
    @DisplayName("--rated-since is carried as the parsed instant when one is given")
    void shouldCarryTheInstantWhenRatedSinceIsGiven() {
      assertThat(
              ExpandCli.parse(
                      new String[] {"--db", "db.sqlite", "--rated-since", "2026-09-08T00:00:00Z"},
                      null,
                      home.toString())
                  .ratedSince())
          .contains(Instant.parse("2026-09-08T00:00:00Z"));
    }

    @Test
    @DisplayName("no instant is carried when the flag is absent, which is every run before this one")
    void shouldCarryNoInstantWhenRatedSinceIsAbsent() {
      assertThat(
              ExpandCli.parse(new String[] {"--db", "db.sqlite"}, null, home.toString())
                  .ratedSince())
          .isEmpty();
    }
  ```

- [ ] **Step 3 — run it and observe a real failure.**

  ```
  ./gradlew test --tests '*ExpandCliTest'
  ```

  Expected: **one failing test**, `shouldCarryTheInstantWhenRatedSinceIsGiven`, an assertion
  failure:

  ```
  Expecting Optional to contain:
    2026-09-08T00:00:00Z
  but was empty.
  ```

  `shouldCarryNoInstantWhenRatedSinceIsAbsent` passes in this run, and its passing is the control:
  it says the accessor exists and reads the component, so the other red is about parsing and not
  about the record. **Both facts go in the task report.**

- [ ] **Step 4 — GREEN, minimally.** Read `--rated-since` out of the `values` map with
  `Instant.parse` directly — no wrapper yet:

  ```java
      Instant ratedSince = null;
      String ratedSinceValue = values.remove("--rated-since");
      if (ratedSinceValue != null) {
        ratedSince = Instant.parse(ratedSinceValue);
      }
  ```

  and return `Optional.ofNullable(ratedSince)`. Re-run: green.

- [ ] **Step 5 — RED. A value that is not an instant is refused the way the harness refuses it.**

  ```java
    @Test
    @DisplayName("--rated-since that is not an instant is refused with this tool's usage error")
    void shouldRefuseTheInstantWhenItIsNotAnInstant() {
      assertThatThrownBy(
              () ->
                  ExpandCli.parse(
                      new String[] {"--db", "db.sqlite", "--rated-since", "last Tuesday"},
                      null,
                      home.toString()))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining(
              "--rated-since takes an ISO-8601 instant like 2026-09-06T15:00:00Z, got last Tuesday")
          .hasMessageContaining("[--rated-since");
    }
  ```

- [ ] **Step 6 — run it and observe a real failure.** Expected: an assertion failure on the
  exception **type**, because `Instant.parse` throws `DateTimeParseException`:

  ```
  Expecting actual throwable to be an instance of:
    java.lang.IllegalArgumentException
  but was:
    java.time.format.DateTimeParseException: Text 'last Tuesday' could not be parsed at index 0
  ```

  Quote it. This is a real failure of the assertion, not a compile error.

- [ ] **Step 7 — GREEN.** Add the private helper, `EvaluateCli.instant`'s body, and extend `USAGE`:

  ```java
    private static Instant instant(String value) {
      try {
        return Instant.parse(value);
      } catch (DateTimeParseException e) {
        throw usage(
            "--rated-since takes an ISO-8601 instant like 2026-09-06T15:00:00Z, got " + value);
      }
    }
  ```

  ```java
    private static final String USAGE =
        "usage: --db <segue.db> [--max-new-edges <n>] [--dry-run] [--rated-since <ISO-8601 instant,"
            + " e.g. 2026-09-06T15:00:00Z>]";
  ```

  Re-run `*ExpandCliTest`: green, every existing test included. `ExpandCli.number` takes no flag
  argument today and this helper matches it; the flag name is a literal in the one message it
  builds, exactly as `--max-new-edges` is in `number`.

- [ ] **Step 8 — a pin for the repeat, labelled as a pin.**

  ```java
    @Test
    @DisplayName("--rated-since given twice is refused, because last-wins is worst on a filter")
    void shouldRefuseTheInstantWhenItIsGivenTwice() { … hasMessageContaining("was given twice") … }
  ```

  **This cannot be seen red.** `parse`'s `values.put(flag, value) != null` check has refused every
  repeated flag since #284, so the assertion passes the moment it compiles. It is a regression pin
  against somebody special-casing this flag out of that loop later, and it is not evidence for this
  change. Say exactly that in the task report.

- [ ] **Step 9 — format, gate, commit.**

  > Parse expandPromotions --rated-since the way the harness parses it (#307)

---

## Task 5 — The filter, and the fence that had to move

Files: `src/main/java/com/robsartin/segue/expand/ExpandCli.java`,
`src/test/java/com/robsartin/segue/expand/ExpandCliTest.java`,
`src/test/java/com/robsartin/segue/expand/ExpansionIsSafeToPasteTest.java`,
`src/test/java/com/robsartin/segue/arch/ArchitectureTest.java`, `docs/developer-guide.md`, and —
**temporarily, reverted within this task** — `src/main/java/com/robsartin/segue/census/CensusCli.java`.

This is the task the issue is about, and its three parts are inseparable: the read, the fence that
admits it, and the guide row the fence's name is derived into. ADR 65 records that #276's fence
"landed in the same commit as the method, so there was no window in which the read existed
unfenced, and in the same commit as its row in the developer guide's table". Same here.

- [ ] **Step 1 — a fixture with three promotions at two instants.** In `ExpandCliTest`, add a
  second scratch database beside `scratchDatabase()`. Three invented entities, all with node
  claims, all rated at `KnownList.PROMOTION_RATING`:

  - `Q0900703` — rated **before** the instant (`2026-01-01T00:00:00Z`, the file's existing `WHEN`).
  - `Q0900705` — rated **on or after** the instant.
  - `Q0900706` — **re-rated**: written once at `WHEN`, then written again at an instant after the
    boundary. `SqliteAffinityStore.put` upserts one row per entity (ADR 39), so the second write is
    what `updated_at` holds. Write it twice on purpose — a fixture that only ever writes the later
    value would pass without the re-rating being what makes it pass.

  Add a helper that reads a count off a rendered block rather than matching a padded literal, so the
  assertion survives a column width change:

  ```java
    /** The number on the block's row for this label — the padding is not the subject here. */
    private static int countOn(List<String> everyLine, String label) {
      String row =
          everyLine.stream()
              .filter(line -> line.strip().startsWith(label))
              .findFirst()
              .orElseThrow(() -> new AssertionError("no '" + label + "' row in the block: " + everyLine));
      return Integer.parseInt(row.strip().substring(label.length()).strip());
    }
  ```

- [ ] **Step 2 — RED. The two controls the issue asks for, in one test each.**

  ```java
    @Test
    @DisplayName("only the promotions rated since the instant are considered, and the rest drop out")
    void shouldConsiderOnlyTheRecentPromotionsWhenAnInstantIsGiven() {
      Path db = threePromotions();

      captured.list.clear();
      ExpandCli.main(new String[] {"--db", db.toString(), "--dry-run"});
      int withoutTheInstant = countOn(lines(), "considered");

      captured.list.clear();
      ExpandCli.main(
          new String[] {"--db", db.toString(), "--dry-run", "--rated-since", "2026-06-01T00:00:00Z"});
      int withTheInstant = countOn(lines(), "considered");

      assertThat(withoutTheInstant)
          .as("all three promotions, or the drop below is a drop from nothing")
          .isEqualTo(3);
      assertThat(withTheInstant)
          .as("the one rated before the instant is gone; the recent one and the re-rated one stay")
          .isEqualTo(2);
    }
  ```

  and a second test for the re-rating on its own, so the two facts fail separately:

  ```java
    @Test
    @DisplayName("a promotion rated long ago and re-rated after the instant is expanded again")
    void shouldConsiderThePromotionWhenItWasRatedAgainAfterTheInstant() { … }
  ```

  — **two** promotions in its own database: `Q0900706`, written at `WHEN` and then re-written after
  the boundary, and `Q0900703`, written once at `WHEN` and left there. With the instant given,
  `considered == 1`. The second, never-re-rated promotion is what makes this test capable of
  failing: a database holding the re-rated entity alone reports `considered == 1` whether the filter
  runs or not, so the test would be green before the code existed and would prove nothing. Assert
  `considered == 2` without the instant in the same test, as the control.

  The `withoutTheInstant == 3` assertion is the positive control for the first test: it must pass in
  the red run, which is what proves the fixture really holds three promotions and the drop, when it
  comes, is the instant's doing. The second test's `== 2` without the instant does the same job for
  it.

- [ ] **Step 3 — run it and observe a real failure.**

  ```
  ./gradlew test --tests '*ExpandCliTest'
  ```

  Expected: **two failing tests, assertion failures**:

  ```
  [the one rated before the instant is gone; the recent one and the re-rated one stay]
  expected: 2
   but was: 3
  ```

  and, in the second test, the same shape on its own boundary:

  ```
  [the re-rated promotion is the only one left; the one never re-rated is gone]
  expected: 1
   but was: 2
  ```

  **Quote both failures, and confirm the control assertion passed in each** — `withoutTheInstant`
  is 3 in the first test and 2 in the second. A second test that reports `expected: 1 but was: 1`
  is not red at all: its database holds only the re-rated entity, so the filter has nothing to
  remove and the test is vacuous. Go back to Step 2 and add the older promotion.

- [ ] **Step 4 — GREEN, and watch the fence fire.** Wire the filter into `ExpandCli.run`:

  ```java
      List<String> promoted = KnownList.promoted(List.of(), ratings);
      // Read only when asked: a run with no --rated-since reads no timestamp at all, which is
      // ADR 16's data minimisation falling out of the shape rather than being argued for. The
      // timestamps are resolved through the same merges the ratings were, so the two maps are
      // keyed alike and a promotion's age cannot be read off another row (issues #276, #307).
      // The set handed over is the PROMOTIONS and not every rated entity: this tool's population
      // is the promotions, so a rated entity it was never going to visit is not a reason to
      // refuse the run.
      Optional<RatingAge> age =
          options
              .ratedSince()
              .map(
                  since ->
                      RatingAge.of(
                          since,
                          merges.resolveUpdatedAt(affinity.readUpdatedAt()),
                          Set.copyOf(promoted)));
      List<String> promotions =
          age.map(it -> promoted.stream().filter(it::isNew).toList()).orElse(promoted);
      Optional<RatedSince> filter =
          age.map(it -> new RatedSince(it.since(), promoted.size() - promotions.size()));
      log.info("{} promotion(s) to visit", promotions.size());
  ```

  and pass `filter` to `run.dryRun` / `run.run` — the long arity when it is present, the short one
  when it is not, so both arities keep a production caller.

  Then run the architecture rules **before** touching them:

  ```
  ./gradlew test --tests '*ArchitectureTest'
  ```

  Expected: **`onlyTheEvaluationHarnessReadsWhenARatingChanged` fails**, shaped like:

  ```
  Architecture Violation [Priority: MEDIUM] - Rule 'no classes that reside outside of package
  '..evaluate..' should access target where target is method
  <com.robsartin.segue.port.AffinityStore.readUpdatedAt()>, because ADR 39 and issue #276: …'
  was violated (1 times):
  Method <com.robsartin.segue.expand.ExpandCli.run(java.lang.String[], java.lang.String,
  java.lang.String)> calls method <com.robsartin.segue.port.AffinityStore.readUpdatedAt()> in
  (ExpandCli.java:NNN)
  ```

  **Quote it.** This is the fence's own positive control, and it is the one ADR 65 describes for
  #276 — the read written, the fence seen firing. Confirm the violation count is **1** and names
  `ExpandCli`.

- [ ] **Step 5 — widen and rename the fence.** In `ArchitectureTest`, rename
  `onlyTheEvaluationHarnessReadsWhenARatingChanged` to
  **`onlyTheHarnessAndTheExpanderReadWhenARatingChanged`**, widen the package predicate to
  `resideOutsideOfPackages("..evaluate..", "..expand..")`, and extend the `because` clause to name
  the second reader. Rewrite the javadoc: keep the keyset argument as it is, replace the "It names
  `evaluate` because … nothing else has asked" paragraph with what the rename records —

  - the expander already holds every rated qid, because `onlyTheRecommenderReadsEveryRating` has
    admitted `..expand..` since #284, so this read adds no entity the tool did not already have one
    line earlier; what it adds is one `Instant` per entity, which is neither a note nor a score, and
    the return type is what keeps it that way;
  - the rename rather than a second rule, because ADR 65's objection was to a rule whose **name**
    goes false, and the fix for a false name is a true one. `onlyTheRecommenderReadsEveryRating` is
    the precedent in this same file: it admits five packages and keeps its name, because that name
    had already become shorthand. This one had not;
  - and the reason not to copy: `theReplayingToolsTakeTheBootsFold`'s row already records that a
    copy under a new name is how `evaluate` grew a defect after ADR 64.

- [ ] **Step 6 — re-run and observe the guide go red.**

  ```
  ./gradlew test --tests '*ArchitectureTest' --tests '*DeveloperGuideEnumerationsTest'
  ```

  Expected: `ArchitectureTest` **green**, and
  `DeveloperGuideEnumerationsTest.shouldNameEveryArchUnitRuleWhenTheGuideTabulatesThem` **red** on
  a set difference:

  ```
  [docs/developer-guide.md, 'Which rules a machine enforces' — one row per rule, rule names in
  backticks in the first column]
  expected: [… "onlyTheHarnessAndTheExpanderReadWhenARatingChanged" …]
   but was: [… "onlyTheEvaluationHarnessReadsWhenARatingChanged" …]
  ```

  That red is the derivation working, and it is the control that says the guide's table is checked
  against the declared rules rather than trusted.

- [ ] **Step 7 — repair the two places in the guide that name the rule.**

  - The rule table row (`docs/developer-guide.md:565`): rename the first cell and widen the "What it
    forbids" cell to say "from outside `evaluate` **or** `expand`", and name why the expander is
    admitted — it already reads every score, so the keyset is already in its hands.
  - The sentence in the evaluation-harness chapter (`docs/developer-guide.md:2355`) says the rule
    "keeps that read inside this package". That is false after the rename. Correct it to name both
    packages. Nothing reads this sentence, so it is gate-verified prose — say so in the report.

  Re-run the two tests **without `--rerun-tasks`**: green. That the suite re-ran at all is the proof
  `docs` is a declared input.

- [ ] **Step 8 — say it in the code too.** Add a paragraph to `ExpandCli`'s class javadoc, modelled
  on `EvaluateCli`'s: it reads when a rating last changed, only when `--rated-since` asks, through
  `{@code com.robsartin.segue.port.AffinityStore#readUpdatedAt}`, under
  `{@code ArchitectureTest.onlyTheHarnessAndTheExpanderReadWhenARatingChanged}`, and it is the only
  class in the package that touches the store.

- [ ] **Step 9 — the planted control: a third package must still fail.** Temporarily add one line
  to `CensusCli.run`, inside the try-with-resources that already holds
  `AffinityStore ratings = new SqliteAffinityStore(options.database())` (around line 126):

  ```java
        log.info("planted {}", ratings.readUpdatedAt().size());
  ```

  A count, never a qid — the plant must not itself be a disclosure, even for the minutes it exists.

  ```
  ./gradlew test --tests '*ArchitectureTest'
  ```

  Expected: **`onlyTheHarnessAndTheExpanderReadWhenARatingChanged` fails, naming `CensusCli`**, with
  the violation count **1**. Quote it, and confirm it does **not** name `ExpandCli` — that is what
  says the widening admitted exactly the one package it meant to.

  Then **revert the plant** (`git checkout -- src/main/java/com/robsartin/segue/census/CensusCli.java`),
  re-run, and confirm green. `git status` must show `CensusCli.java` unmodified before you commit.

- [ ] **Step 10 — the safe-to-paste pin, labelled as a pin.** In `ExpansionIsSafeToPasteTest`, add a
  dry run driven with `--rated-since` over the same label-and-note database
  `shouldEmitCountsAndNothingElseWhenTheDatabaseHoldsALabelANoteAndAnId` builds, asserting through
  the existing `assertEverySafe(ExpansionReport.DRY_RUN_HEADER)`.

  **This cannot be seen red on this change**, because the clause it exercises can only emit an
  `Instant` and an `int`, and `Instant.toString` cannot produce a qid. It is a regression pin, not
  evidence, and the task report says so in those words.

- [ ] **Step 11 — format, gate, commit.** `./gradlew spotlessApply`; re-read `ExpandCli`'s and the
  fence's javadoc for intact `{@code …}` spans; full gate; `git status` (confirm `CensusCli.java` is
  **not** listed); stage by explicit path; commit:

  > Expand only the promotions rated since the instant, and widen the fence that admits the read (#307)

---

## Task 6 — The runbook gains the variant

Files: `src/test/java/com/robsartin/segue/expand/DeveloperGuideExpandPromotionsExamplesTest.java`,
`docs/developer-guide.md`.

- [ ] **Step 1 — RED. Extend the order assertion first.** In the guide test, extend `steps()`'s
  reduction so a `--rated-since` example is distinguishable, and extend the expected sequence:

  ```java
        for (Example example : GuideExamples.inChapter(CHAPTER, task).examples()) {
          String command = task;
          if (example.arguments().contains("--dry-run")) {
            command += " --dry-run";
          }
          if (example.arguments().contains("--rated-since")) {
            command += " --rated-since";
          }
          found.add(new Numbered(example.line(), command));
        }
  ```

  ```java
          .containsExactly(
              "graphCensus",
              "expandPromotions --dry-run",
              "expandPromotions",
              "graphCensus",
              "expandPromotions --dry-run --rated-since",
              "expandPromotions --rated-since");
  ```

  Extend the assertion's `as(...)` description so it says what the two new entries are for: the
  variant is shown after the full runbook, and its dry run comes before its run for the same reason
  the full runbook's does.

- [ ] **Step 2 — run it and observe a real failure.**

  ```
  ./gradlew test --tests '*DeveloperGuideExpandPromotionsExamplesTest'
  ```

  Expected: **one failing test**, an assertion failure:

  ```
  Expecting actual:
    ["graphCensus", "expandPromotions --dry-run", "expandPromotions", "graphCensus"]
  to contain exactly (and in same order):
    ["graphCensus", "expandPromotions --dry-run", "expandPromotions", "graphCensus",
     "expandPromotions --dry-run --rated-since", "expandPromotions --rated-since"]
  but could not find the following elements:
    ["expandPromotions --dry-run --rated-since", "expandPromotions --rated-since"]
  ```

  Quote it. The four elements that **are** found are the control: the four existing steps still
  reduce exactly as they did, so the extended reduction did not break the chapter it already read.

- [ ] **Step 3 — GREEN. Write the section.** In `docs/developer-guide.md`, after step 5's table and
  the two sentences that follow it, and **before** `### What to file from what you saw`, add an
  unnumbered section. It must contain, in this order:

  1. **What the flag is for.** The full runbook re-records every assertion of the last run for the
     sake of the newest few. `--rated-since <ISO-8601 instant>` keeps only the promotions whose
     rating's last write is on or after the instant. Absent, nothing changes and no timestamp is
     read at all.
  2. **The dry run, first**, in a fenced `bash` block:

     ```bash
     ./gradlew expandPromotions --args="--db $HOME/.segue/segue.db --dry-run --rated-since 2026-09-08T00:00:00Z"
     ```

     Say that the instant is yours to choose — the end of the last run is the usual one — and that
     the block now carries a second `#` line naming it and how many promotions it excluded.
  3. **What `considered` means here.** It is the promoted population **after** the filter, so step
     2's arithmetic (`considered` minus `in the graph` minus `minted`) still reads, over the smaller
     population. `considered` plus the excluded count on the clause is the whole promoted
     population, which is how you check the instant did what you meant.
  4. **The run**, in a fenced `bash` block:

     ```bash
     ./gradlew expandPromotions --args="--db $HOME/.segue/segue.db --rated-since 2026-09-08T00:00:00Z"
     ```
  5. **The limit, stated where the number is read.** `updated_at` is the last write (ADR 39), so a
     promotion rated years ago and re-rated after the instant is expanded again. That is cheap and
     harmless — one entity re-expanded — and it is why this is "rated since" rather than "new
     since".
  6. **How to read step 5's table after a partial run.** The table is unchanged and is still read
     against step 1's census: every `up` is still `up`, because expanding a smaller population
     cannot move a line the other way. What changes is the **size** of each movement, which is
     bounded by the promotions that actually ran rather than by the whole population — so a small
     delta against a large `considered` is the finding, and a small delta against a small
     `considered` is not. The two `unchanged` rows — `taste` and `edges / withdrawn` — are unchanged
     for reasons that have nothing to do with how many promotions ran, so they are still the ones to
     check hardest.

  Write no figure from the owner's graph anywhere in the section, and use `$HOME` and never a tilde
  — `RUNBOOK.withATilde()` is what catches that.

- [ ] **Step 4 — re-run, without `--rerun-tasks`.**

  ```
  ./gradlew test --tests '*DeveloperGuideExpandPromotionsExamplesTest'
  ```

  Expected: green, all six tests. Two of them now carry the new lines for free —
  `shouldParseEveryExampleWhenTheGuideShowsTheTool` puts both new commands through
  `ExpandCli.parse`, which is what makes the flag in the document and the flag in the parser one
  thing, and `shouldWriteHomeRatherThanATildeWhenAnExampleNamesADatabase` sweeps them too. Say in
  the report that the suite re-ran without `--rerun-tasks`, which is the proof `docs` is a declared
  input.

- [ ] **Step 5 — format, gate, commit.**

  > Show the since-variant in the promotion-expansion runbook (#307)

---

## Task 7 — The two ADR amendments

Files: `docs/adr/0065-an-offline-evaluation-harness-for-the-recommender.md`,
`docs/adr/0066-expand-every-promotion-from-a-dev-tool.md`.

**Append only.** Nothing above either amendment is edited, no sentence is deleted, and neither ADR's
status changes. Before committing, run `git diff --stat` and then `git diff docs/adr` and confirm
**every line of the diff is an addition** — a deletion in an ADR file is the defect this step exists
to prevent.

There is no red here and the task report says so: this is a documentation task, verified by
`AdrIndexTest`, `AdrCitationsTest`, `DocumentationLinksTest` and `javadoc -Werror` inside the full
gate, over a tree that is otherwise unchanged.

- [ ] **Step 1 — ADR 65's amendment.** Append at the end of the file, in the house style
  (`**Amendment (2026-09-11, issue #307): …**` as a bold sentence, then paragraphs each opening with
  a bold claim). It must record:

  - **The second reader.** `expand` now calls `AffinityStore.readUpdatedAt`, and the fence is one
    rule that admits `evaluate` or `expand` rather than two rules.
  - **The rename, and the sentence above it that had to be engaged with rather than quietly
    outgrown.** The 2026-09-06 amendment says a rule named for one tool and quoted in an immutable
    ADR does not get stretched to cover a second. #307 is that second tool. The objection is to a
    rule whose *name* goes false, and the answer is a true name:
    `onlyTheHarnessAndTheExpanderReadWhenARatingChanged`. Name the precedent in the same file —
    `onlyTheRecommenderReadsEveryRating` admits five packages and keeps its name because "only the
    recommender" had already become shorthand — and say why this one could not do the same.
  - **Why data minimisation still holds.** The expander already reads every score
    (`onlyTheRecommenderReadsEveryRating` has admitted `..expand..` since #284), so it already holds
    every qid the owner has rated; this read adds no entity it did not already have and one
    `Instant` per entity, which the return type keeps free of a note and a score. The read stays
    inside `ExpandCli`, the one class in the package that touches the store.
  - **What is not changed.** No reading, no flag, no report and no behaviour of `evaluate`; the
    harness's own fences, its grid, its fold count and its eligible population are exactly as
    decided.
  - **The controls.** The fence was seen firing on `ExpandCli` before it was widened, and a planted
    call from `census` was seen firing after — a third package is still refused.
  - **How the document itself was verified**, in the closing paragraph both existing amendments use.

  Name no commit hash, no test identifier and no `.superpowers/` path.

- [ ] **Step 2 — ADR 66's amendment.** Append at the end of the file, same style. It must record:

  - **The flag**: `expandPromotions --rated-since <ISO-8601 instant>`, optional, parsed exactly as
    `evaluate` parses it; absent is today's behaviour and reads no timestamp at all.
  - **What `considered` means under it**: the promoted population after the filter — the same field,
    a smaller population — so the identity `considered == expanded + refused + failed` and step 2's
    dry-run arithmetic both survive unchanged. The excluded count is not a second field on the
    tally; it is on the clause.
  - **The header form**: one `#` clause under the block's own header, in **both** blocks, naming the
    instant, the excluded count and the last-write limit. Say why a clause and not a counted row
    (a row would name a division nothing made, and a row would re-pad every
    count in every block for a number that is usually zero). Say that **the block with no instant is
    byte-identical to today's**, which is what keeps every block already pasted into an issue
    comparable, and that a golden test pins it character for character.
  - **The type-level fence is untouched**: the renderer's new argument is an `Instant` and an `int`,
    and there is still nowhere in the signature to put an identifier.
  - **Where the filter lives**: composed at `ExpandCli` from `KnownList.promoted` and the
    merge-resolved timestamps, with `RatingAge` — moved from `evaluate` into `domain` so both tools
    read one answer to "since" — and not a second filtering rule inside the run.
  - **A promotion with no timestamp is refused, not excluded**, and why: a silent exclusion means an
    entity the owner asked to expand is never visited and nothing says so. The refusal names no qid
    and no count.
  - **Alternatives rejected**: recording which promotions have been expanded (a new claim type or a
    mark in the log — the timestamp is a proxy that needs no state; not done here, and no issue is
    recorded for it); an
    excluded **row** instead of a clause; making the filter mandatory with a default instant; and
    copying the harness's machinery into `expand` rather than moving it into `domain`.
  - **The closing verification paragraph**, as above.

- [ ] **Step 3 — prove the append.**

  ```
  git diff docs/adr | grep '^-' | grep -v '^---'
  ```

  Expected: **no output**. Any line here is a deletion inside an immutable document; stop and
  restore it.

- [ ] **Step 4 — gate and commit.** Full gate, `git status`, stage by explicit path, commit:

  > Record the expander's second reader and its since-filter in ADR 65 and ADR 66 (#307)

---

## Done when

- `./gradlew expandPromotions --args="--db … --rated-since <instant>"` parses, filters and reports;
  with no flag the tool reads no timestamp and its two blocks are byte-identical to today's.
- `RatingAge` is in `domain`, read by `evaluate` and `expand`, with one copy of the rule.
- `onlyTheHarnessAndTheExpanderReadWhenARatingChanged` admits exactly `evaluate` and `expand`, was
  seen firing on `ExpandCli` before the widening and on `census` after it, and the guide's rule
  table names it.
- The runbook's variant section parses through `ExpandCli.parse` and its order is asserted.
- ADR 65 and ADR 66 each carry one 2026-09-11 amendment for #307, appended, with no deletion.
- `SEGUE_REQUIRE_BROWSER=true SEGUE_REQUIRE_GRAPHVIZ=true ./gradlew check --rerun-tasks` is green
  and `git status` is clean.
