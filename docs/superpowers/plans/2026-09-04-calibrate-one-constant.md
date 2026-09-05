# Calibrate one recommender constant against the harness's first real reading — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to
> implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** apply the decision rule that commit `9937f86` fixed, mechanically, to the first real
`evaluate` reading, and land exactly one of three outcomes — the scorer default moves, the degree
floor moves, or the shipped setting stands — each with the reading on the record as a dated
amendment.

**Architecture:** there is almost no architecture here, and that is the point. The rule is already
written and committed; this plan is a decision procedure with three mutually exclusive tails. Task 1
reads the table and writes a ruling. Exactly **one** of Task 2A / 2B / 2C then executes, chosen by
that ruling, and the other two are not started. Task 3 closes whichever ran.

**Tech Stack:** Java (toolchain 25, `release 21`), JUnit 5, AssertJ, ArchUnit, JaCoCo. Markdown for
the ADR amendment.

**Spec:** `docs/superpowers/specs/2026-09-04-calibrate-one-constant-design.md` — it holds the rule,
its five clauses, the three outcomes and the alternatives rejected. **Cite it; never restate its
reasoning and never paraphrase a clause.** The rule as committed is the authority: where this plan
and the spec appear to differ, the spec wins and the divergence is a finding to report.

---

## Global Constraints

- **The rule in the spec is immutable for this issue.** It was committed at `9937f86` *before* the
  reading existed, and that ordering is the whole evidential value of this issue. Do not add a
  clause, do not soften a threshold, do not introduce a tie-breaker the spec does not have, and do
  not re-read a clause "in the spirit of" anything. Three hits means three hits. If the reading
  makes the rule look wrong, say so in the report and change nothing.
- **At most one constant moves.** Either `RecommendCli.parse`'s default scorer or
  `Recommendations.MIN_CANDIDATE_DEGREE` — never both, and never a third constant. Clause 4 of the
  rule decides the tie in favour of the scorer.
- **The OWNER runs `evaluate` and `recommend`; the implementer never does.** `~/.segue/segue.db` is
  never read, written, copied or created, and no dev task is run against it. Where this plan needs a
  reading it names what the owner must run and **stops** until the controller hands the output back
  as a file.
- **Never run a writing dev task** (`own`, `ownClaim`, `retractEntity`, `rate`, or any other).
- **ADRs are append-only.** An amendment is appended to the end of the file. Front matter
  (`status`, `date`, `topic`, `tags`, `supersedes`, `related`) is **not touched**; no line above the
  amendment is edited, reworded or deleted. `docs/adr/README.md` is **not** touched — an amendment
  changes no ADR's number, title or status, which is all `AdrIndexTest` compares.
- **Never cite a `.superpowers/` path from a committed file.** The reading and the ruling live under
  `.superpowers/`, which is gitignored (`.gitignore:53`); the committed record of the reading is the
  verbatim table inside the ADR amendment and nothing else.
- **The table is quoted verbatim in the amendment and nowhere else in prose.** One copy, in a fenced
  block, exactly as the owner pasted it — no re-alignment, no re-ordering of rows, no added column,
  no "the interesting rows only". **No figure from the table is restated anywhere** — not in the
  amendment's prose, not in a Javadoc, not in the developer guide, not in a commit message. Prose
  says *which cells were compared and what the clause concluded*, and points at the block.
- Pure TDD. Failing test first, **run it and observe a real assertion failure** — a compile error is
  not a red. Where a step has no unit-testable behaviour, say so **out loud** and name the other
  explicit method that verifies it (this plan does that in Task 2B and Task 2C).
- Test names `should<Expected>When<Condition>` with `@DisplayName`.
- Mikado: green at every committed step.
- **Never `git add -A`.** Stage every file by explicit path, with git's stderr visible (never
  `2>/dev/null`), and read `git status` before committing. One committer in this worktree.
- Commit messages end with a blank line then
  `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`.
- Gate, run **BLOCKING** (never backgrounded), after every task:
  `SEGUE_REQUIRE_BROWSER=true SEGUE_REQUIRE_GRAPHVIZ=true ./gradlew check --rerun-tasks`
  Plain `./gradlew`; JDK 25 is the only JDK. If `spotlessCheck` fails run `./gradlew spotlessApply`
  and re-run the gate. `check` includes `javadoc` with `-Werror`, so a broken `{@link}` fails the
  build.
- **Invented identifiers only** in anything committed (ADR 58, ADR 51). No real entity name, no real
  rating, nothing derived from the owner's data beyond the aggregate table the rule permits.
- **Dates.** Every amendment is dated **the day the reading is read**. That is expected to be
  **2026-09-04**; if the owner pastes the table on a later day, the amendment carries *that* day's
  date instead, and the commit message says which day and why. The rule's own date (2026-09-04,
  commit `9937f86`) is quoted in the amendment and never changed.
- **The amendment names the commit that fixed the rule: `9937f86`.** In every outcome. It is the
  evidence that the rule preceded the number.

---

### Task 1: the reading, verbatim — and a ruling that names every cell it used

**No code changes in this task.** Its deliverable is a ruling file that the next task reads.

**The reading is not available when this plan is written.** The owner runs `./gradlew evaluate`
against his own database and pastes the block it prints. The controller writes that paste to a file
and gives the implementer its path. In the steps below, **`<READING_PATH>`** is that path (the
controller substitutes it; it will be under `.superpowers/`, which is gitignored, and it is never
cited from a committed file).

- [ ] **Read the reading as given.** `cat <READING_PATH>`. Do not reformat it, do not re-align its
      columns, do not sort it, do not transcribe it by hand into another file. Every later use is a
      copy of these exact bytes.
- [ ] **Confirm it is the report and not a fragment.** It must begin with `EvaluationReport.HEADER`
      — the line starting `# segue recommender evaluation — aggregates only:` — followed by the two
      `#` lines (`held out every …`, `top … per setting, over … setting(s).`), then the column row
      `scorer  floor  pool  in pool  hits  mean rank  negatives  neg mean rank`, then one row per
      `Setting.GRID` entry. `Setting.GRID` is `Scorer.values().length × Setting.FLOORS.size()` rows,
      scorer-major, floors ascending `2, 5, 8, 12`. **If a row is missing, if the header is absent,
      or if the paste has been re-wrapped so a row spans two lines, STOP and report** — a partial
      table cannot be judged by a rule that requires dominance across floors, and quietly judging
      what arrived is exactly the failure mode of a lenient parser.
- [ ] **Confirm it is safe to carry.** Every cell is an integer, a one-decimal number, the literal
      `-` (`EvaluationReport.NO_MEAN`), or a `Scorer` spelling. **If any label, note, name or
      qid-shaped token appears anywhere in the paste, STOP and report; do not commit it and do not
      quote it.** ADR 51 and ADR 65 permit the aggregate table and nothing else.
- [ ] **Apply the rule, clause by clause, writing the arithmetic down as you go.** Read the spec's
      "The rule" section and follow it in its own order. Do not skip a clause because an earlier one
      looks decisive; the ruling has to show that each was evaluated.
      - Clause 1 fixes the denominator: the `in pool` cell of the setting's own row. Note it for
        every row you cite; never use the held-out count from the `#` header line as a denominator.
      - Clause 2 is the scorer question, evaluated **at floor 5** — three sub-conditions, all of
        which must hold: at least three more `hits` than `LIFT`'s floor-5 row; `negatives` no
        greater than `LIFT`'s floor-5 row; and **more `hits` than `LIFT` at every one of floors
        2, 8 and 12 as well**. Evaluate it for every non-`LIFT` scorer, not only the best-looking
        one, and record each verdict.
      - Clause 3 is the floor question, evaluated **at the scorer clause 2 chose** (which is `LIFT`
        unless clause 2 displaced it): at least three more `hits` than that scorer's floor-5 row,
        and `negatives` no greater. Evaluate it for each of floors 2, 8 and 12.
      - Clause 4 resolves both clearing: the scorer moves, the floor is re-asked later.
      - Clause 5 is the one that most often applies. Two is not three. A better `mean rank` with
        equal or fewer `hits` clears nothing.
- [ ] **Write the ruling** to the path the controller names (it will sit beside `<READING_PATH>`
      under `.superpowers/`; call it **`<RULING_PATH>`**). It is short and it is arithmetic. It must
      contain, in this order:
      1. **The cells read.** Every one, addressed as `(scorer, floor) → column = value`. A cell that
         is not listed here was not used, and a clause that used an unlisted cell is a defect in
         this ruling.
      2. **The arithmetic per clause**, one line each, showing the subtraction or comparison and its
         verdict — including the clauses that failed and the scorers that lost.
      3. **The outcome**, as exactly one of these three literal strings:
         `SCORER MOVES TO <spelling>` / `FLOOR MOVES TO <n>` / `THE SHIPPED SETTING STANDS`.
      4. **Which of Task 2A / 2B / 2C that selects**, named.
- [ ] **Verify the ruling the way this task's "test" is defined:** re-read the ruling against
      `<READING_PATH>` and confirm that **every cell the arithmetic uses appears in the ruling's
      list of cells read, and every value matches the table byte for byte**. A ruling whose
      arithmetic reaches for a number it did not first name is rejected and rewritten. Say in the
      report how many cells were listed and that each was checked.
- [ ] **Report the outcome to the controller and STOP.** Do not begin a Task 2 variant on your own
      reading of the table; the controller dispatches the selected variant. Nothing is committed in
      this task — `git status` must show a clean tree (`.superpowers/` is ignored).

---

### Task 2A: the scorer moves

**Run this task only if the ruling's outcome line reads `SCORER MOVES TO <spelling>`.** Below,
`<NEW>` is that scorer's enum constant (`RAW`, `ADAMIC_ADAR` or `RESOURCE_ALLOCATION`) and
`<new-spelling>` its `Scorer.spelling()`.

#### Step 1 — a drift test for the second copy of the default (a prerequisite, and its control comes in step 2)

`RecommendCli` holds the default **twice**: `parse` has the literal `Scorer scorer = Scorer.LIFT;`
and `USAGE` has the string `">, default lift]"`. Nothing pins them together, and the repository's
own standard elsewhere is the opposite — `RateCli`'s Javadoc says the floor is taken "by reference,
never by a second copy of the number". Land the pin before the move so the move cannot half-happen.

- [ ] Add to `src/test/java/com/robsartin/segue/recommend/RecommendCliTest.java`:

```java
  @Test
  @DisplayName("the usage line names the scorer the parser actually defaults to")
  void shouldNameTheParsedDefaultScorerWhenUsageIsPrinted() {
    Scorer theDefault = parse("--known", "/tmp/known.csv", "--out", "/tmp/out.txt").scorer();

    assertThatThrownBy(() -> parse("--out", "/tmp/out.txt"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("default " + theDefault.spelling());
  }
```

- [ ] **Say this out loud in the step report: this test is GREEN when added, and that is deliberate.**
      It is a characterisation test, not a red. Its red arrives in step 2, where flipping only
      `parse` fires it — that is its positive control, and it is observed rather than assumed. Run
      it now and record that it passes.
- [ ] Commit: `Pin the recommend usage line to the scorer the parser defaults to (#242)`.

#### Step 2 — RED: move the pin, then the default

- [ ] **RED.** In `RecommendCliTest.theTwoPathsAreAllItNeeds`, change the expectation only:

```java
    assertThat(options.scorer()).isEqualTo(Scorer.LIFT);
```

  becomes

```java
    assertThat(options.scorer()).isEqualTo(Scorer.<NEW>);
```

- [ ] Run `./gradlew test --tests '*RecommendCliTest*'` **blocking** and **quote the failure** in the
      step report. It must be an AssertJ comparison failure on that line, of the shape
      `expected: <NEW>  but was: LIFT` — not a compile error, not a different test.
- [ ] **GREEN, part one.** In `src/main/java/com/robsartin/segue/recommend/RecommendCli.java`,
      `parse`:

```java
    Scorer scorer = Scorer.LIFT;
```

  becomes

```java
    Scorer scorer = DEFAULT_SCORER;
```

  with the constant added beside `DEFAULT_TOP`:

```java
  /**
   * The scorer a run uses when {@code --scorer} is not given. Measured — ADR 45 chose it and that
   * ADR's amendment for issue #242 records the reading that moved it — and held here once so the
   * usage line and the parser cannot disagree.
   */
  public static final Scorer DEFAULT_SCORER = Scorer.<NEW>;
```

- [ ] Run the two tests. `theTwoPathsAreAllItNeeds` is now green and
      `shouldNameTheParsedDefaultScorerWhenUsageIsPrinted` is now **RED** — the usage string still
      says `lift`. **Quote that failure too.** This is step 1's positive control firing for the right
      reason; a step 1 test that stays green here is a test that proves nothing, and the step stops
      until it reds.
- [ ] **GREEN, part two.** Derive the usage line from the same constant:

```java
          + ">, default lift]"
```

  becomes

```java
          + ">, default "
          + DEFAULT_SCORER.spelling()
          + "]"
```

- [ ] Run `./gradlew test --tests '*RecommendCliTest*'` blocking; both green.
- [ ] Commit: `The recommender defaults to <new-spelling> (#242)`.

#### Step 3 — the docs sweep, derived rather than assumed

- [ ] Derive the set rather than eyeball it. Run both, blocking, and paste the full output into the
      step report:

```bash
grep -rn "LIFT" docs src --include='*.md' --include='*.java'
grep -rni "lift" docs --include='*.md'
```

- [ ] Classify **every** hit into one of three buckets and say which in the report. A grep is
      narrower than the claim, so state the classification per line, not per file:
      1. **A claim that `lift` is the default** — must change. Known, at plan time, to be exactly one
         editable line: `docs/developer-guide.md:1841`, the comment
         `# the measured defaults: lift, ...` above the `./gradlew recommend` example. Replace
         `lift` there with `<new-spelling>`. **Re-derive this list from the grep**; do not trust this
         plan's count.
      2. **A statement about the `lift` scorer itself** — `lift` divides by the candidate's degree,
         the dial's spellings, the anti-pattern section, `Scorer`'s Javadoc, ADR 45's scorer table.
         **These stay exactly as they are.** `LIFT` is still a scorer and every such sentence is
         still true.
      3. **An immutable ADR that names `LIFT` as the default** — at plan time,
         `docs/adr/0050-suppress-a-candidate-you-have-rejected.md:88` ("`LIFT`, the measured
         default") and ADR 45's own heading at line 113 ("defaulting to lift"). **Do not edit
         either.** ADR 1 makes them immutable; the ADR 45 amendment below supersedes them in
         writing, and step 4 requires it to name them.
- [ ] Run, blocking:
      `SEGUE_REQUIRE_BROWSER=true SEGUE_REQUIRE_GRAPHVIZ=true ./gradlew check --rerun-tasks`.
      This is what verifies the guide edit: `DocumentationLinksTest`,
      `DeveloperGuideEnumerationsTest` and `DeveloperGuideEvaluateExamplesTest` all read these
      documents. Say in the report that the guide edit has no unit test of its own and that the gate
      is the method.
- [ ] Commit: `Name the new default scorer where the guide states it (#242)`.

#### Step 4 — the record: a dated amendment to ADR 45

Append to the **end** of `docs/adr/0045-recommend-by-normalised-lift-with-routes.md` (currently 707
lines). Nothing above line 707 is edited. Modelled on the 2026-08-29 amendment, which is the
precedent: a dated bold heading, an explicit "nothing above is withdrawn", the table, the cost.

- [ ] Write it to this skeleton, filling only the bracketed parts:

````markdown

**Amendment (2026-09-04, issue #242): the default scorer is `<new-spelling>`, not `lift`, on the
first reading the recommender has ever been measured by.**

Nothing above is withdrawn and no decision above is edited. The scorer *spectrum* is unchanged, the
formula in each `Scorer` constant is unchanged, and this ADR's argument for normalising by the
candidate's own degree stands exactly as written. What changes is which point on the spectrum a run
takes when `--scorer` is not given.

**The rule was fixed before the number existed, and that is the evidence.** Commit `9937f86` on
2026-09-04 committed `docs/superpowers/specs/2026-09-04-calibrate-one-constant-design.md`, which
states the decision rule in full — the denominator, the three-hit bar, the no-more-negatives
condition, the dominance-across-floors condition, and the one-constant limit. The reading below was
taken afterwards. The rule is the authority on what would have counted; it is not restated here.

**The reading.** One run of `./gradlew evaluate` ([ADR 65](0065-an-offline-evaluation-harness-for-the-recommender.md))
on the owner's database, quoted whole and unedited. Aggregates only, per
[ADR 51](0051-what-an-adr-may-quote.md): every cell is a count, a one-decimal mean or a dash, and
every label is a column name or a `Scorer` spelling.

```
[THE PASTED TABLE, BYTE FOR BYTE, INCLUDING ITS HEADER AND ITS THREE `#` LINES]
```

**What the rule made of it.** [ONE SHORT PARAGRAPH. Name the cells the clauses compared — by
(scorer, floor) and column — and state each clause's verdict. Do NOT restate a value from the block
above; the block is the only place any figure appears.]

### What this costs, which the decision above does not

[THE COST SECTION. Modelled on "The cost, which this ADR records nowhere above". It must state, in
the ADR's own voice and without restating a figure:
 - that the held-out set is small and one entity is several points of hit rate, which is why the
   rule asked for a difference no single entity could produce, and that a three-hit margin on a
   population this size is still a thin result;
 - that hit rate is measured over the reachable, so this reading says nothing about the entities
   ingest cannot reach, and is not a verdict on coverage (ADR 65's consequence);
 - that `lift`'s argument — dividing by the candidate's own degree escapes fame — is unchanged and
   this move does not refute it; a different point on the spectrum won on one reading of one
   graph on one day, which is the same standing the floor's five has;
 - that a ranking produced at the new default is not comparable to any ranking this project has
   published, and `--scorer lift` reproduces the old behaviour exactly.]

### What this amendment does not move

- **`Recommendations.MIN_CANDIDATE_DEGREE` stays at five.** The rule permits one constant to move
  and this is the one that moved; the floor question is re-asked against this default in a later
  issue and is undecided rather than settled.
- **`Setting.GRID` needs no edit.** Every scorer is already swept, so the next reading compares the
  new default against the others on the same instrument.
- **`RateRun` deals its candidates at `Scorer.LIFT` by its own literal**, and this amendment does
  not change it. The deck has no `--scorer` dial, so the two tools now disagree at their defaults
  where they previously agreed. That is a consequence of taking the one-constant limit literally
  and it is filed rather than fixed here.
- **[ADR 50](0050-suppress-a-candidate-you-have-rejected.md) calls `LIFT` "the measured default",
  and the heading above says "defaulting to lift".** Both are immutable and both are superseded by
  this amendment on that one word. Neither is edited; ADR 1 is why.

### Consequences of this amendment

- **`./gradlew recommend` with no `--scorer` returns a different population**, and the usage line
  says which. `--scorer lift` is how the two are read side by side, which is the method this ADR
  has recommended for every such question.
- **The rating deck is unchanged**, so the deck and `recommend` now sample differently.
- **The constants are no longer untested.** ADR 65 exists so that this issue could move one; it
  moved one, against a number, by a rule written first.
````

- [ ] Verify by the gate: `AdrIndexTest` (number, title, status and index row unchanged),
      `DocumentationLinksTest` (every relative link in the block above resolves to a file and a
      heading). Run the full gate blocking.
- [ ] **Check the verbatim rule and the no-restated-figure rule before committing.** `diff` the
      fenced block against `<READING_PATH>`; it must be byte-identical. Then re-read the amendment's
      prose and confirm no digit from the table appears in it. Say both in the report.
- [ ] Commit: `Record the first evaluation reading and the scorer it moved (#242)`.

---

### Task 2B: the floor moves

**Run this task only if the ruling's outcome line reads `FLOOR MOVES TO <n>`.** `<n>` is 2, 8 or 12
— `Setting.FLOORS` holds exactly those besides the shipped five, and the spec says any other value
is not this issue's to choose.

#### Step 0 — the stop condition, read before anything is edited

- [ ] **If `<n>` is 2, STOP and report; do not edit anything.**
      `RecommendationsTest.theFloorIsAboveTheThinNodeThatToppedTheRanking` asserts
      `assertThat(Recommendations.MIN_CANDIDATE_DEGREE).isGreaterThan(2);` under the display name
      "the floor is a real bound, and it is above the degree that let a thin node top the ranking".
      That is a **red for the right reason** — it fires on the assertion, not on a compile — but it
      is not a pin that follows a constant. It records a finding: at degree 2 a thin node topped the
      ranking. Two is also `LOWEST_USEFUL_FLOOR` in both `RecommendCli` and `RateCli` — the point
      the code calls "below this a normalised score is meaningless" — and `Setting`'s Javadoc calls
      it "the point below which a normalised score stops meaning anything", not a candidate default.
      Shipping the default *at* that bound is a different decision from moving between two useful
      floors, and weakening the assertion to accommodate it would delete the evidence that produced
      it. **The rule did not anticipate this collision, and the rule is immutable, so this is the
      owner's decision and not the implementer's.** Report the collision, quote the assertion and
      its display name, and stop. Nothing in this task runs until the owner rules.
- [ ] If `<n>` is 8 or 12, continue.

#### Step 1 — the honest exception, stated rather than implied

- [ ] **Say this out loud in the step report: the floor's value has no failing test available, and
      test-after is not being reached for.** Every consumer in the codebase reads
      `Recommendations.MIN_CANDIDATE_DEGREE` **by reference** — `RecommendCli`, `RateCli`,
      `RateRun`, `DegreeCensus`, and in tests `RecommendCliTest`, `RateCliTest`, `RateRunTest`
      (which derives its lowered floor as `MIN_CANDIDATE_DEGREE - 2` and says in a comment that
      "what the test pins is the DIAL and never a number"), `DegreeCensusTest`,
      `AffinityWeightedRecommendationTest`, `CandidateSweepTest` and `SettingTest`. That is a
      deliberate, recorded property: a number held once. A test asserting the parser's default
      equals the new literal would compare the constant to itself and prove nothing.
      `SettingTest.shouldIncludeTheShippedFloorWhenTheFloorsAreListed` stays green because
      `Setting.FLOORS` already holds 2, 5, 8 and 12.
- [ ] **Name the two verification methods that replace the red, and use both:**
      1. **The full gate**, which is not a formality here — it is what proves every by-reference
         consumer still holds at the new value, including `RateRunTest`'s derived `- 2` fixture and
         `DegreeCensusTest`'s equality.
      2. **One `recommend` run by the OWNER** against ADR 57's trigger band (step 3).

#### Step 2 — the move, and the Javadoc that goes stale with it

- [ ] In `src/main/java/com/robsartin/segue/domain/Recommendations.java`:

```java
  public static final int MIN_CANDIDATE_DEGREE = 5;
```

  becomes

```java
  public static final int MIN_CANDIDATE_DEGREE = <n>;
```

- [ ] **The Javadoc above it names five in prose and must move with the number.** Do not restate any
      figure from the reading; point at the amendment. Add, as the last paragraph of that Javadoc:

```java
   * <p><b>Moved again on the first measured reading</b> — ADR 45's amendment for issue #242 carries
   * the reading, the rule that was fixed before it, and the cost. Nothing here restates them.
```

  and correct any sentence in that Javadoc that asserts the old value as current.
- [ ] **`Setting`'s Javadoc names five as "what the recommender ships with" and now names the wrong
      number.** In `src/main/java/com/robsartin/segue/evaluate/Setting.java`, that sentence reads:
      `{@code 5} is what the recommender ships with, {@code Recommendations.MIN_CANDIDATE_DEGREE};`
      — rewrite so `<n>` is the shipped one and 5 is the floor the recommender shipped with until
      issue #242. Every floor still "earns its place"; only which one is the default changes.
- [ ] Derive the rest rather than assume it. Run, blocking, and paste the output:

```bash
grep -rn "MIN_CANDIDATE_DEGREE" src docs --include='*.java' --include='*.md'
grep -rn "floor.*five\|five.*floor\|floor of 5\|floor 5" docs --include='*.md'
```

  Classify every hit: a by-reference use (leave), a statement about *this* default (fix), or an
  immutable ADR's record of a past decision (**leave — ADR 45's amendment of 2026-08-29 and ADR 57
  are the history and stay exactly as written**).
- [ ] Run the full gate, **blocking**. Quote its result. If anything reds, that red is information
      about a consumer this plan did not derive — report it rather than patching around it.
- [ ] Commit: `The candidate degree floor defaults to <n> (#242)`.

#### Step 3 — the owner's `recommend` run, and ADR 57's band

- [ ] **The implementer does not run this.** Hand the controller this line for the owner to run
      against his own database, with his own `--known` file and an output path he chooses:

```
./gradlew recommend --args="--known $HOME/known.csv --out $HOME/floor-<n>.txt"
```

  and ask for **the `FloorReading` header lines only** — the two `#` lines the report writes, which
  carry `floor`, `pool`, `poolMedianDegree`, `heldOut`, `heldOutAtDegreeOne`, `head`,
  `headMedianDegree`, `headOnTheFloor` and `headEveryEdgeCounted`. Every one is an aggregate;
  none is a name. **Do not ask for the ranked list**, which is known-list content at one remove and
  is exactly what ADR 51 forbids quoting.
- [ ] **Read `headOnTheFloor` against ADR 57's band**, and that clause only: re-run the two-floor
      comparison when fewer than 6 of 25 sit exactly on the floor, or more than 19 of 25 do. ADR 57
      states the threshold as *chosen and not measured* and says so itself; do not treat it as a
      finding and do not move it here.
- [ ] **The spec requires this check only for a floor below five; this plan asks for it above five
      as well, and here is why — say it in the report.** ADR 57 records that floor 12 put **1 of 25**
      on the floor, on the same graph on the same day, which is below the band's own lower bound. So
      a move upward may ship a configuration ADR 57's trigger already says to re-run. That is
      evidence added, not a bar lowered: **the rule is unchanged and the move is not gated on this
      reading.** If the reading falls outside the band, the move still proceeds — the amendment says
      plainly that it ships outside the band and why, and the implementer reports it. Nothing fails a
      build when the floor drifts; ADR 57 says the trigger is held by a reader.

#### Step 4 — the record: a dated amendment to ADR 57

Append to the **end** of `docs/adr/0057-the-floor-reports-itself.md` (currently 269 lines). Nothing
above is edited; front matter untouched.

- [ ] Write it to this skeleton:

````markdown

**Amendment (2026-09-04, issue #242): the degree floor defaults to `<n>`, not five, on the first
reading the recommender has ever been measured by — and what the floor reading says about it.**

Nothing above is withdrawn and no decision above is edited. The reading this ADR added to every run
is unchanged, the trigger band is unchanged, and both refusals it argued — a newly discovered node
is still not ranked, expansion state is still not scored — stand exactly as written. What changes is
the number the reading is a reading *of*.

**The rule was fixed before the number existed, and that is the evidence.** Commit `9937f86` on
2026-09-04 committed `docs/superpowers/specs/2026-09-04-calibrate-one-constant-design.md`, which
states the decision rule in full, including the extra condition a floor below five would have had to
meet. The reading below was taken afterwards. The rule is the authority on what would have counted;
it is not restated here.

**The reading.** One run of `./gradlew evaluate` ([ADR 65](0065-an-offline-evaluation-harness-for-the-recommender.md))
on the owner's database, quoted whole and unedited. Aggregates only, per
[ADR 51](0051-what-an-adr-may-quote.md).

```
[THE PASTED TABLE, BYTE FOR BYTE, INCLUDING ITS HEADER AND ITS THREE `#` LINES]
```

**What the rule made of it.** [ONE SHORT PARAGRAPH naming the cells the clauses compared — by
(scorer, floor) and column — and each clause's verdict, including why the scorer question was
answered first and did not clear. Do NOT restate a value from the block above.]

**What the floor reading says at the new default.** [ONE SHORT PARAGRAPH. State whether
`headOnTheFloor` sits inside the band this ADR chose, and if it does not, say so plainly and say
that the move ships outside it anyway because the rule that governs this decision does not gate on
it. Name `FloorReading` as the authority on the figures. `poolMedianDegree` is comparable across
runs at one floor and **never** across floors — this ADR's own caveat — so do not compare it to the
figure recorded above for floor 5 or floor 12.]

### What this costs, which the decision above does not

[THE COST SECTION, modelled on ADR 45's "The cost, which this ADR records nowhere above". Without
restating a figure, it must state:
 - the direction of the trade this move makes — a higher floor removes thinly-fetched entities from
   the ranking and with them the disagreement ADR 45's amendment measured the deck losing at
   floor 12, or a lower one admits more of what ingest has and has not reached;
 - that the held-out set is small, that one entity is several points of hit rate, and that a
   three-hit margin on it is a thin result rather than a settled one;
 - that hit rate is read over the reachable, so this says nothing about coverage (ADR 65);
 - that ADR 45's 2026-08-29 amendment and ADR 50's distribution are the authorities on what
   choosing a floor costs the taste layer, and that nothing measured here revisits them.]

### What this amendment does not move

- **The scorer default is unchanged.** The rule permits one constant to move and this is the one
  that moved; the scorer question is re-asked in a later issue.
- **`Setting.FLOORS` is unchanged.** It already held this value, which is why the grid could
  produce this reading at all.
- **The trigger band is unchanged**, and it is still chosen rather than measured. Anyone
  re-deciding it should move the number rather than treat it as a finding — this ADR's own words.

### Consequences of this amendment

- **The default list is a different population**, and `--min-degree 5` reproduces the old behaviour
  exactly — the method this project uses for exactly this question.
- **The deck deals at the new floor too**, because `rate` reads the constant by reference.
- **The constants are no longer untested.** ADR 65 exists so that this issue could move one; it
  moved one, against a number, by a rule written first.
````

- [ ] Verify by the gate, and `diff` the fenced block against `<READING_PATH>` for byte-identity.
      Confirm no digit from the table appears in the amendment's prose. Say both in the report.
- [ ] Commit: `Record the first evaluation reading and the floor it moved (#242)`.

---

### Task 2C: the shipped setting stands

**Run this task only if the ruling's outcome line reads `THE SHIPPED SETTING STANDS`.**

- [ ] **No code changes at all.** `RecommendCli.parse` keeps `Scorer.LIFT`,
      `Recommendations.MIN_CANDIDATE_DEGREE` keeps 5, no Javadoc moves, no guide line moves. **Say
      out loud in the step report that there is no test in this task and why**: nothing behavioural
      changed, so there is nothing to red. The verification method is the full gate, which proves the
      tree is unchanged from a green baseline, plus the ruling from Task 1, which is the reasoning
      this task records.
- [ ] **This is a result and must not be written as an absence of one.** The amendment says what was
      measured and that nothing cleared the bar. It does not apologise for the outcome, does not
      speculate about what a wider grid might have shown, and does not add a "but X looked
      promising" clause — clause 5 of the rule exists precisely to refuse that.

#### The record: a dated amendment to ADR 45

Append to the **end** of `docs/adr/0045-recommend-by-normalised-lift-with-routes.md` (currently 707
lines). Nothing above is edited; front matter untouched.

- [ ] Write it to this skeleton:

````markdown

**Amendment (2026-09-04, issue #242): the first measured reading of the recommender moved nothing,
and the shipped scorer and floor stand on evidence rather than on judgement alone.**

Nothing above is withdrawn and no decision above is edited. No constant changed and no code changed.
What changed is the standing of two numbers: `lift` and a floor of five were chosen by reading
ranked lists side by side, and they have now been measured against the owner's own held-out ratings
and were not displaced.

**The rule was fixed before the number existed, and that is the whole evidential value of this
entry.** Commit `9937f86` on 2026-09-04 committed
`docs/superpowers/specs/2026-09-04-calibrate-one-constant-design.md`, which states the decision rule
in full — the denominator, the three-hit bar, the no-more-negatives condition, the
dominance-across-floors condition, the one-constant limit, and the clause that says a near miss
stands. The reading below was taken afterwards. Had the reading come first, this paragraph would be
a rationalisation with a table attached; the rule is the authority on what would have counted and is
not restated here.

**The reading.** One run of `./gradlew evaluate` ([ADR 65](0065-an-offline-evaluation-harness-for-the-recommender.md))
on the owner's database, quoted whole and unedited. Aggregates only, per
[ADR 51](0051-what-an-adr-may-quote.md): every cell is a count, a one-decimal mean or a dash, and
every label is a column name or a `Scorer` spelling.

```
[THE PASTED TABLE, BYTE FOR BYTE, INCLUDING ITS HEADER AND ITS THREE `#` LINES]
```

**What the rule made of it.** [ONE SHORT PARAGRAPH. Name the cells each clause compared — by
(scorer, floor) and column — and say for each candidate scorer and each candidate floor which
condition it failed. Where a candidate came close, say that it came close and that clause 5 is why
it stands: two hits is not three, and a better mean rank with no more hits is not a hit. Do NOT
restate a value from the block above.]

### What this does and does not establish

- **It does not establish that `lift` at five is the best setting.** It establishes that on this
  reading, by this rule, nothing displaced it. ADR 65's first consequence is the governing one: no
  row of that table means anything on its own.
- **It does not establish that the harness can tell these settings apart.** The held-out set is
  small — one entity is several points of hit rate, which is why the rule asked for a difference no
  single entity could produce — so a null result is also what an instrument too blunt for the
  question would produce. Nothing here distinguishes those two readings, and a second reading on a
  larger split is the only thing that would.
- **It says nothing about the entities ingest cannot reach.** Rates are read over the reachable
  (ADR 65's consequence), so this is not a verdict on expansion coverage.
- **The constants are no longer untested, and that is the change.** ADR 45 declined to tune them
  because nothing could evaluate them; something now can, it has, and the answer was "stand".

### Consequences of this amendment

- **Nothing in the tool moves**, so no ranking, no deck and no output line changes.
- **`Setting.GRID` is unchanged**, so the next reading is comparable to this one row by row — the
  property #239 fixed the grid for.
- **The question is re-asked, not closed.** A later issue takes a second reading, and a wider grid
  or a further metric is that issue's to argue rather than this one's.
````

- [ ] Verify by the gate: `AdrIndexTest`, `DocumentationLinksTest`, and the full run.
      `diff` the fenced block against `<READING_PATH>` for byte-identity; confirm no digit from the
      table appears in the prose. Say both in the report.
- [ ] Commit: `Record the first evaluation reading, which moved nothing (#242)`.

---

### Task 3: the closing sweep

Runs after whichever Task 2 variant executed. If Task 2B stopped at its step 0, this task does not
run — the branch is reported to the owner as blocked, not swept.

- [ ] **The gate, blocking, from a clean tree:**
      `SEGUE_REQUIRE_BROWSER=true SEGUE_REQUIRE_GRAPHVIZ=true ./gradlew check --rerun-tasks`
      Quote `BUILD SUCCESSFUL` and the task count. A gate that was last run before the final commit
      is not a gate.
- [ ] **No `.superpowers/` citation reached a committed file.** Run, blocking:

```bash
git diff --stat main...HEAD
git grep -n "\.superpowers" -- docs src
```

  The second must print nothing. `<READING_PATH>` and `<RULING_PATH>` are never named in a committed
  file; the committed record of the reading is the fenced block in the amendment.
- [ ] **The amendment quotes the rule's commit hash.** Run, blocking:

```bash
git grep -n "9937f86" -- docs/adr
```

  It must print exactly one line, inside the amendment this issue appended. Zero lines means the
  evidence that the rule preceded the number is not on the record and the amendment is incomplete.
- [ ] **The table appears once and no figure is restated.** Confirm the fenced block `diff`s clean
      against `<READING_PATH>`, that `git grep` finds the `EvaluationReport.HEADER` line in exactly
      one committed file, and that the amendment's prose carries no digit lifted from the block.
      Report all three as checked, not as assumed.
- [ ] **The ADR front matter is untouched and the index is untouched.** Confirm from
      `git diff main...HEAD -- docs/adr/` that the only change under `docs/adr/` is an append at the
      end of one file, and that `docs/adr/README.md` is not in the diff.
- [ ] **One constant moved, or none.** Confirm from `git diff main...HEAD -- src/main/` that at most
      one of `RecommendCli`'s default scorer and `Recommendations.MIN_CANDIDATE_DEGREE` changed
      value. Any other constant in the diff is a defect; report it.
- [ ] **Report to the controller**, naming: which variant ran, the outcome string from the ruling,
      the commits, the gate result, and every finding that did not fit the rule — including the ones
      this plan already anticipated (`RateRun`'s own `Scorer.LIFT` literal, ADR 50's superseded
      "measured default" line, a floor reading outside ADR 57's band). A finding is reported, never
      documented away.
