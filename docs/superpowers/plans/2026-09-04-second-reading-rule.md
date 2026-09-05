# The rule for the recommender's second evaluation reading — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to
> implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** put this issue's rule on the record before the second `evaluate` reading exists, then apply
it mechanically to that reading and land exactly one of three outcomes — the scorer default moves,
the degree floor moves, or the shipped setting stands — each with the reading on the record as a
dated amendment.

**Architecture:** there is almost no architecture here, and that is the point. Task 1 puts the rule
beyond editing and pushes it, so the ordering is witnessed by something nobody in this session
controls. Task 2 reads the owner's table and writes a ruling. Exactly **one** of Task 3A / 3B / 3C
then executes, chosen by that ruling; the other two are not started. Task 4 closes whichever ran.

**Tech Stack:** Java (toolchain 25, `release 21`), JUnit 5, AssertJ, ArchUnit, JaCoCo. Markdown for
the ADR amendment.

**Spec:** `docs/superpowers/specs/2026-09-04-second-reading-rule-design.md` — it holds the rule, its
eight clauses, the three outcomes and the alternatives rejected. **Cite it; never restate its
reasoning and never paraphrase a clause.** Where this plan and the spec appear to differ, the spec
wins and the divergence is a finding to report.

---

## Global Constraints

- **The rule in the spec is immutable for this issue.** It is committed *before* the reading exists,
  and that ordering is the whole evidential value of the issue. Do not add a clause, do not soften a
  threshold, do not introduce a tie-breaker the spec does not have, and do not re-read a clause "in
  the spirit of" anything. Fifteen points means fifteen points. If the reading makes the rule look
  wrong, say so in the report and change nothing.
- **At most one constant moves.** Either the default scorer `RecommendCli.parse` applies or
  `Recommendations.MIN_CANDIDATE_DEGREE` — never both, never a third. Clause 6 breaks the tie in
  favour of the scorer.
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
  `.superpowers/`, which is gitignored; the committed record of the reading is the verbatim table
  inside the ADR amendment and nothing else.
- **The table is quoted verbatim in the amendment and nowhere else.** One copy, in a fenced block,
  exactly as the owner pasted it — no re-alignment, no re-ordering, no added column, no "the
  interesting rows only". **No figure from it is restated anywhere** — not in the amendment's prose,
  not in a Javadoc, not in the developer guide, not in a commit message. Prose says *which cells were
  compared and what the clause concluded*, and points at the block. The same applies to the **first**
  reading: it stays in ADR 45's 2026-09-04 amendment and is neither copied nor quoted again.
- Pure TDD. Failing test first, **run it and observe a real assertion failure** — a compile error is
  not a red. Where a step has no unit-testable behaviour, say so **out loud** and name the other
  explicit method that verifies it (this plan does that in Tasks 1, 3B and 3C).
- Test names `should<Expected>When<Condition>` with `@DisplayName`.
- Mikado: green at every committed step.
- **Never `git add -A`.** Stage every file by explicit path, with git's stderr visible (never
  `2>/dev/null`), and read `git status` before committing. One committer in this worktree; sibling
  issues are in flight in neighbouring worktrees and nothing outside this one is touched.
- Commit messages end with a blank line then
  `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`.
- Gate, run **BLOCKING** (never backgrounded), after every task that changes a file:
  `SEGUE_REQUIRE_BROWSER=true SEGUE_REQUIRE_GRAPHVIZ=true ./gradlew check --rerun-tasks`
  Plain `./gradlew`; JDK 25 is the only JDK. If `spotlessCheck` fails run `./gradlew spotlessApply`
  and re-run the gate. `check` includes `javadoc` with `-Werror`, so a broken `{@link}` fails it.
- **Invented identifiers only** in anything committed (ADR 58, ADR 51). No real entity name, no real
  rating, nothing derived from the owner's data beyond the aggregate table the rule permits.
- **Dates.** Every amendment is dated **the day the reading is read**, expected to be **2026-09-04**;
  if the owner pastes the table on a later day, the amendment carries *that* day's date and the
  commit message says which day and why. The rule's own date and commit never change.
- **Issue #244 is in flight and this plan assumes it will land.** It replaces the second copies of
  the default scorer with one shared constant. Task 3A branches on whether it has merged and derives
  the answer from the code rather than assuming either way.

---

### Task 1: the rule, beyond editing, before the number exists

**No code changes.** Its deliverable is a commit hash and a pushed branch — the evidence that the
rule preceded the reading.

- [ ] **Confirm the rule is committed and unmodified.** Run, blocking:

```bash
git log --oneline -3
git status --short
git log -1 --format=%H -- docs/superpowers/specs/2026-09-04-second-reading-rule-design.md
```

  The third command must print a hash. **Call it `<RULE_COMMIT>`; it is quoted in every amendment
  below.** `git status --short` must show no modification to the spec. If the spec is dirty, STOP and
  report — a rule edited after it was written is not a rule fixed in advance.

- [ ] **Push the branch**, so the ordering is witnessed by the remote's own timestamp rather than by
      a local clock this session could set:

```bash
git push -u origin 245-ready
```

- [ ] **Say this out loud in the step report: there is no test in this task, and here is why.**
      Nothing behavioural exists yet to red. The verification method is the commit graph — `<RULE_COMMIT>`
      is an ancestor of every commit this issue lands, and the reading arrives after the push. Quote
      `<RULE_COMMIT>` and the push output in the report.
- [ ] **Report `<RULE_COMMIT>` to the controller and STOP.** The owner is asked for the reading only
      after this push has happened.

---

### Task 2: the reading, verbatim — and a ruling that names every cell it used

**No code changes in this task.** Its deliverable is a ruling file that the next task reads.

**The reading is not available when this plan is written.** The owner runs

```
./gradlew evaluate --args="--db $HOME/.segue/segue.db --known $HOME/known.csv"
```

against his own database and pastes the block it prints. The controller writes that paste to a file
and gives the implementer its path. Below, **`<READING_PATH>`** is that path (gitignored, under
`.superpowers/`, and never cited from a committed file).

- [ ] **Read the reading as given.** `cat <READING_PATH>`. Do not reformat it, do not re-align its
      columns, do not sort it, do not transcribe it by hand. Every later use is a copy of these exact
      bytes.
- [ ] **Confirm it is the report and not a fragment.** It must begin with `EvaluationReport.HEADER`
      — the line starting `# segue recommender evaluation — aggregates only:` — then the two `#`
      lines (`held out every …`, `top … per setting, over … setting(s).`), then the column row
      `scorer  floor  pool  in pool  hits  mean rank  negatives  neg mean rank`, then one row per
      `Setting.GRID` entry: `Scorer.values().length × Setting.FLOORS.size()` rows, scorer-major,
      floors ascending. **If a row is missing, if the header is absent, or if the paste has been
      re-wrapped so a row spans two lines, STOP and report.** A partial table cannot be judged by a
      rule with a dominance range in it, and quietly judging what arrived is the lenient-parser
      failure this repository has already paid for once.
- [ ] **Confirm it is safe to carry.** Every cell is an integer, a one-decimal number, the literal
      `-` (`EvaluationReport.NO_MEAN`), or a `Scorer` spelling. **If any label, note, name or
      qid-shaped token appears anywhere in the paste, STOP and report; do not commit it and do not
      quote it.** ADR 51 and ADR 65 permit the aggregate table and nothing else.
- [ ] **Apply the rule, clause by clause, writing the arithmetic down as you go.** Read the spec's
      "The rule" section and follow it in its own order. Do not skip a clause because an earlier one
      looks decisive; the ruling has to show each was evaluated.
      - Clause 1 fixes every denominator as the row's own `in pool` cell. Compute
        `hits / in pool` for every row you cite and write the division out. Never use the header
        line's held-out count as a denominator.
      - Clause 2's **void check runs first**, before any comparison: if any row a clause would
        compare carries `in pool ≤ 6`, the margin is void, the outcome is
        `THE SHIPPED SETTING STANDS`, and the ruling says the reason is the instrument. Record the
        check and its result whether or not it fires.
      - Clause 3 is the scorer question. (a) at `Recommendations.MIN_CANDIDATE_DEGREE`, every
        non-shipped scorer's hit rate against the shipped scorer's, needing the full fifteen points.
        (b) **derive the dominance range from the table**: the floors other than the shipped one at
        which the *shipped* scorer's `hits` cell is non-zero. Write that range down explicitly. If it
        is empty, the check fails and no scorer moves. Evaluate (a) and (b) for **every** non-shipped
        scorer and record each verdict, not only the best-looking one.
      - Clause 4 is the floor question at the scorer clause 3 chose, needing the full fifteen points
        against that scorer's shipped-floor row. Evaluate it for every other floor in the table.
      - Clause 5 means the `negatives` and `neg mean rank` cells decide nothing. **Read and record
        them anyway** — one line per row — because the amendment must be able to name them.
      - Clause 6 resolves both clearing: the scorer moves.
      - Clause 7 is the one that most often applies. Fourteen points is not fifteen. A better
        `mean rank` with no rate improvement clears nothing.
- [ ] **Write the ruling** to the path the controller names beside `<READING_PATH>` — call it
      **`<RULING_PATH>`**. It is short and it is arithmetic. It must contain, in this order:
      1. **The cells read.** Every one, addressed as `(scorer, floor) → column = value`. A cell not
         listed here was not used, and a clause that used an unlisted cell is a defect in the ruling.
      2. **The void check**, with the smallest `in pool` among the compared rows and its verdict.
      3. **The arithmetic per clause**, one line each, showing the division or comparison and its
         verdict — including the clauses that failed and the scorers that lost, and including the
         derived dominance range written out as a list of floors.
      4. **The negatives cells**, recorded as observation with the words "decides nothing".
      5. **The outcome**, as exactly one of these three literal strings:
         `SCORER MOVES TO <spelling>` / `FLOOR MOVES TO <n>` / `THE SHIPPED SETTING STANDS`.
      6. **Which of Task 3A / 3B / 3C that selects**, named.
- [ ] **Verify the ruling the way this task's "test" is defined:** re-read it against
      `<READING_PATH>` and confirm that **every cell the arithmetic uses appears in the list of cells
      read, and every value matches the table byte for byte**. A ruling whose arithmetic reaches for a
      number it did not first name is rejected and rewritten. Say in the report how many cells were
      listed and that each was checked.
- [ ] **Report the outcome to the controller and STOP.** Do not begin a Task 3 variant on your own
      reading of the table; the controller dispatches the selected variant. Nothing is committed here
      — `git status` must show a clean tree.

---

### Task 3A: the scorer moves

**Run this task only if the ruling's outcome line reads `SCORER MOVES TO <spelling>`.** Below,
`<NEW>` is that scorer's enum constant (`RAW`, `ADAMIC_ADAR` or `RESOURCE_ALLOCATION`) and
`<new-spelling>` its `Scorer.spelling()`.

#### Step 1 — derive whether #244 has landed, rather than assuming it

- [ ] Run, blocking, and paste the full output into the step report:

```bash
git log --oneline -12
grep -rn "Scorer.LIFT" src/main src/test --include='*.java'
grep -rn "default lift" src/main --include='*.java'
```

- [ ] Classify the result into exactly one of two states and name it in the report:
      - **#244 has merged** if `src/main` holds the default in exactly one place — a named constant
        — and `RecommendCli.parse`, `RateRun` and the usage string all read it by reference. Record
        the constant's **fully qualified name and its home class**, derived from that grep, and use
        it below wherever this plan writes `<DEFAULT_SCORER>`. Do not assume the name; #244's issue
        text proposes a home beside `Recommendations.MIN_CANDIDATE_DEGREE`, and what merged is the
        authority.
      - **#244 has not merged** if `Scorer.LIFT` still appears as a literal in `RecommendCli.parse`
        *and* in `RateRun`, and `RecommendCli`'s usage string still carries the word `lift`.
- [ ] Follow **step 2M** if it merged and **step 2U** if it did not. Do not run both.

#### Step 2M — RED then GREEN against the shared constant (#244 merged)

- [ ] **RED.** In `src/test/java/com/robsartin/segue/recommend/RecommendCliTest.java`,
      `theTwoPathsAreAllItNeeds`, change the expectation only:

```java
    assertThat(options.scorer()).isEqualTo(Scorer.LIFT);
```

  becomes

```java
    assertThat(options.scorer()).isEqualTo(Scorer.<NEW>);
```

- [ ] Run `./gradlew test --tests '*RecommendCliTest*'` **blocking** and **quote the failure** in the
      step report. It must be an AssertJ comparison failure on that line, of the shape
      `expected: <NEW>  but was: LIFT` — not a compile error, not a different test. If #244 also
      added a pin coupling the deck's scorer to the parser's default, that test stays **green**
      through this step and reds only if the two are allowed to diverge; say which tests ran and what
      each did.
- [ ] **GREEN.** Change the one shared constant `<DEFAULT_SCORER>` from `Scorer.LIFT` to
      `Scorer.<NEW>`, and nothing else. Correct any sentence in that constant's Javadoc that asserts
      the old value as current, pointing at ADR 45's amendment for the reading rather than restating a
      figure:

```java
   * <p><b>Moved on the second measured reading</b> — ADR 45's amendment for issue #245 carries the
   * reading, the rule that was fixed before it, and the cost. Nothing here restates them.
```

- [ ] Run `./gradlew test --tests '*RecommendCliTest*' --tests '*RateRunTest*' --tests '*RateCliTest*'`
      blocking; all green. **Say out loud that the deck followed by reference and was not edited** —
      that is what #244 bought, and it is the property to state rather than assume.
- [ ] Commit: `The recommender defaults to <new-spelling> (#245)`.

#### Step 2U — the second copies first, then the move (#244 has not merged)

The default lives in three places with nothing pinning them together: `RecommendCli.parse`'s literal,
`RecommendCli.USAGE`'s `">, default lift]"`, and `RateRun`'s `Scorer.LIFT` argument. Land a pin
before the move so the move cannot half-happen.

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
      It is a characterisation test, not a red. Its red arrives below, where flipping only `parse`
      fires it — that is its positive control, observed rather than assumed. Run it now and record
      that it passes.
- [ ] Commit: `Pin the recommend usage line to the scorer the parser defaults to (#245)`.
- [ ] **RED.** In `RecommendCliTest.theTwoPathsAreAllItNeeds`, change the expectation only:

```java
    assertThat(options.scorer()).isEqualTo(Scorer.LIFT);
```

  becomes

```java
    assertThat(options.scorer()).isEqualTo(Scorer.<NEW>);
```

- [ ] Run `./gradlew test --tests '*RecommendCliTest*'` blocking and **quote the failure**: an AssertJ
      comparison failure of the shape `expected: <NEW>  but was: LIFT`.
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
   * ADR's amendment for issue #245 records the reading that moved it — and held here once so the
   * usage line and the parser cannot disagree.
   */
  public static final Scorer DEFAULT_SCORER = Scorer.<NEW>;
```

- [ ] Run the two tests. `theTwoPathsAreAllItNeeds` is green and
      `shouldNameTheParsedDefaultScorerWhenUsageIsPrinted` is now **RED** — the usage string still
      says `lift`. **Quote that failure too.** This is the pin's positive control firing for the right
      reason; a pin that stays green here proves nothing, and the step stops until it reds.
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
- [ ] Commit: `The recommender defaults to <new-spelling> (#245)`.
- [ ] **`RateRun`'s own `Scorer.LIFT` literal is NOT changed here, and that is a finding, not an
      oversight.** The deck now deals at `lift` while `recommend` ranks at `<new-spelling>`. Fixing it
      is issue #244's whole content, and doing it here would move a second constant against clause 6.
      Say so in the report, name `src/main/java/com/robsartin/segue/rate/RateRun.java` and its line,
      and say that the amendment below records the divergence.

#### Step 3 — the docs sweep, derived rather than assumed

- [ ] Derive the set rather than eyeball it. Run both, blocking, and paste the full output:

```bash
grep -rn "LIFT" docs src --include='*.md' --include='*.java'
grep -rni "lift" docs --include='*.md'
```

- [ ] Classify **every** hit into one of three buckets and say which in the report, **per line, not
      per file** — a grep is narrower than the claim:
      1. **A claim that `lift` is the default** — must change. Known at plan time to be
         `docs/developer-guide.md:1841`, the comment
         `# the measured defaults: lift, ...` above the `./gradlew recommend` example. Replace `lift`
         there with `<new-spelling>`. **Re-derive this list from the grep**; do not trust this plan's
         count.
      2. **A statement about the `lift` scorer itself** — that it divides by the candidate's degree,
         the dial's spellings, the anti-pattern section, `Scorer`'s Javadoc, ADR 45's scorer table.
         **These stay exactly as they are.** `LIFT` is still a scorer and every such sentence is still
         true.
      3. **An immutable ADR that names `LIFT` as the default** — at plan time
         `docs/adr/0050-suppress-a-candidate-you-have-rejected.md` ("`LIFT`, the measured default")
         and ADR 45's own heading ("defaulting to lift"). **Do not edit either.** ADR 1 makes them
         immutable; the amendment below supersedes them in writing and must name them.
- [ ] Run the full gate blocking. `DocumentationLinksTest`, `DeveloperGuideEnumerationsTest` and
      `DeveloperGuideEvaluateExamplesTest` all read these documents. **Say in the report that the
      guide edit has no unit test of its own and that the gate is the method.**
- [ ] Commit: `Name the new default scorer where the guide states it (#245)`.

#### Step 4 — the record: a dated amendment to ADR 45

Append to the **end** of `docs/adr/0045-recommend-by-normalised-lift-with-routes.md`. Nothing above
is edited; front matter untouched. Modelled on the 2026-09-04 amendment already at the end of that
file.

- [ ] Write it to this skeleton, filling only the bracketed parts:

````markdown

**Amendment (2026-09-04, issue #245): on the second measured reading, judged by a rule written to
answer what the first reading exposed, the default scorer is `<new-spelling>`.**

Nothing above is withdrawn and no decision above is edited, including the amendment immediately
above this one: that entry's reading, its ruling and its four observations stand exactly as written,
and this one answers the last of them rather than replacing it. The scorer *spectrum* is unchanged,
the formula in each `Scorer` constant is unchanged, and this ADR's argument for normalising by the
candidate's own degree stands as written. What changes is which point on the spectrum a run takes
when `--scorer` is not given.

**The rule was fixed before the number existed, and it was written to answer the previous reading
rather than this one.** Commit `<RULE_COMMIT>` on 2026-09-04 committed
`docs/superpowers/specs/2026-09-04-second-reading-rule-design.md`, which states the decision rule in
full — rates over the `in pool` cell, the margin that replaced a hit count, the dominance range that
excludes the floors where the shipped scorer has no hits, the dropped negatives clause and why it was
dropped, and the one-constant limit. The reading below was taken afterwards. The rule is the
authority on what would have counted; it is not restated here.

**The reading.** One run of `./gradlew evaluate` ([ADR 65](0065-an-offline-evaluation-harness-for-the-recommender.md))
on the owner's database, quoted whole and unedited. Aggregates only, per
[ADR 51](0051-what-an-adr-may-quote.md): every cell is a count, a one-decimal mean or a dash, and
every label is a column name or a `Scorer` spelling.

```
[THE PASTED TABLE, BYTE FOR BYTE, INCLUDING ITS HEADER AND ITS THREE `#` LINES]
```

**What the rule made of it.** [ONE SHORT PARAGRAPH. Name the cells each clause compared — by
(scorer, floor) and column — the dominance range the rule derived, and each clause's verdict. Say
that the negatives cells were read and decided nothing. Do NOT restate a value from the block above;
the block is the only place any figure appears.]

### What this costs, which the decision above does not

[THE COST SECTION. Modelled on "The cost, which this ADR records nowhere above". Without restating a
figure, it must state:
 - that the held-out set is small, that one entity is a visible share of any hit rate on it, and that
   this is why the rule asked for a difference no single entity could produce — a margin cleared on a
   population this size is still a thin result;
 - that a rate read over `in pool` rises when a higher floor removes held-out entities the setting
   was missing anyway, so a cross-floor rate improvement is precision over what was reachable and not
   a claim that more was found;
 - that the negatives condition was dropped rather than met, so nothing in this decision guards
   against a default that surfaces more of what the owner would reject — the cells are quoted above
   and that disclosure is the whole of the protection;
 - that hit rate is measured over the reachable, so this reading says nothing about the entities
   ingest cannot reach and is not a verdict on coverage (ADR 65's consequence);
 - that `lift`'s argument — dividing by the candidate's own degree escapes fame — is unchanged and
   this move does not refute it; a different point on the spectrum won on one reading of one graph on
   one day, which is the same standing the floor's own value has;
 - that a ranking produced at the new default is not comparable to any ranking this project has
   published, and `--scorer lift` reproduces the old behaviour exactly.]

### What this amendment does not move

- **`Recommendations.MIN_CANDIDATE_DEGREE` is unchanged.** The rule permits one constant to move and
  this is the one that moved; the floor question is re-asked against this default in a later issue and
  is undecided rather than settled.
- **`Setting.GRID` needs no edit.** Every scorer is already swept, so the next reading compares this
  default against the others on the same instrument.
- **[ADR 50](0050-suppress-a-candidate-you-have-rejected.md) calls `LIFT` "the measured default", and
  the heading above says "defaulting to lift".** Both are immutable and both are superseded by this
  amendment on that one word. Neither is edited; ADR 1 is why.
- [IF STEP 2U RAN, ADD THIS BULLET, AND OMIT IT IF STEP 2M RAN: **`RateRun` deals its candidates at
  `Scorer.LIFT` by its own literal**, and this amendment does not change it. The deck has no
  `--scorer` dial, so the two tools now disagree at their defaults where they previously agreed. That
  is a consequence of taking the one-constant limit literally; issue #244 is where it is repaired.]

### Consequences of this amendment

- **`./gradlew recommend` with no `--scorer` returns a different population**, and the usage line says
  which. `--scorer lift` is how the two are read side by side, which is the method this ADR has
  recommended for every such question.
- **The constants are no longer untested and one has now moved.** ADR 65 exists so that a reading
  could move one; the first reading moved none and the second moved this.
````

- [ ] Verify by the gate: `AdrIndexTest` (number, title, status and index row unchanged),
      `DocumentationLinksTest` (every relative link above resolves to a file and a heading). Run the
      full gate blocking.
- [ ] **Check the verbatim rule and the no-restated-figure rule before committing.** `diff` the fenced
      block against `<READING_PATH>`; it must be byte-identical. Then re-read the amendment's prose and
      confirm no digit from either reading appears in it. Say both in the report.
- [ ] Commit: `Record the second evaluation reading and the scorer it moved (#245)`.

---

### Task 3B: the floor moves

**Run this task only if the ruling's outcome line reads `FLOOR MOVES TO <n>`.** `<n>` is a value
`Setting.FLOORS` already holds besides the shipped one.

#### Step 0 — the stop condition, read before anything is edited

- [ ] **If `<n>` is 2, STOP and report; do not edit anything.**
      `RecommendationsTest.theFloorIsAboveTheThinNodeThatToppedTheRanking` asserts
      `assertThat(Recommendations.MIN_CANDIDATE_DEGREE).isGreaterThan(2);` under the display name
      "the floor is a real bound, and it is above the degree that let a thin node top the ranking".
      That is a red for the right reason — it fires on the assertion — but it is not a pin that
      follows a constant; it records a finding. Two is also `LOWEST_USEFUL_FLOOR` in both
      `RecommendCli` and `RateCli`, the point the code calls "below this a normalised score is
      meaningless", and `Setting`'s Javadoc calls it the point below which a normalised score stops
      meaning anything, not a candidate default. Shipping the default *at* that bound is a different
      decision from moving between two useful floors, and weakening the assertion to accommodate it
      would delete the evidence that produced it. **The rule did not anticipate this collision and the
      rule is immutable, so this is the owner's decision and not the implementer's.** Report the
      collision, quote the assertion and its display name, and stop.
- [ ] Otherwise continue.

#### Step 1 — the honest exception, stated rather than implied

- [ ] **Say this out loud in the step report: the floor's value has no failing test available, and
      test-after is not being reached for.** Every consumer reads
      `Recommendations.MIN_CANDIDATE_DEGREE` **by reference** — `RecommendCli`, `RateCli`, `RateRun`,
      `DegreeCensus`, and in tests `RecommendCliTest`, `RateCliTest`, `RateRunTest` (which derives its
      lowered floor from the constant and says in a comment that what it pins is the dial and never a
      number), `DegreeCensusTest`, `AffinityWeightedRecommendationTest`, `CandidateSweepTest` and
      `SettingTest`. That is a deliberate, recorded property: a number held once. A test asserting the
      parser's default equals the new literal would compare the constant to itself.
      `SettingTest.shouldIncludeTheShippedFloorWhenTheFloorsAreListed` stays green because
      `Setting.FLOORS` already holds the value — **derive that from `Setting.FLOORS` and say so**,
      rather than trusting this sentence.
- [ ] **Name the two verification methods that replace the red, and use both:**
      1. **The full gate**, which is not a formality here — it is what proves every by-reference
         consumer still holds at the new value, including `RateRunTest`'s derived fixture and
         `DegreeCensusTest`'s equality.
      2. **One `recommend` run by the OWNER** against ADR 57's band (step 3).

#### Step 2 — the move, and the Javadoc that goes stale with it

- [ ] In `src/main/java/com/robsartin/segue/domain/Recommendations.java`:

```java
  public static final int MIN_CANDIDATE_DEGREE = 5;
```

  becomes

```java
  public static final int MIN_CANDIDATE_DEGREE = <n>;
```

- [ ] **The Javadoc above it argues for the old value in prose and must move with the number.** Do not
      restate any figure from either reading; point at the amendment. Add, as the last paragraph of
      that Javadoc:

```java
   * <p><b>Moved again on the second measured reading</b> — ADR 57's amendment for issue #245 carries
   * the reading, the rule that was fixed before it, and the cost. Nothing here restates them.
```

  and correct any sentence in that Javadoc that asserts the old value as current — in particular the
  paragraph describing the direction the value was last moved in, which now describes a previous
  state.
- [ ] **`Setting`'s Javadoc names the old value as "what the recommender ships with" and now names the
      wrong number.** In `src/main/java/com/robsartin/segue/evaluate/Setting.java` that sentence reads
      `{@code 5} is what the recommender ships with, {@code Recommendations.MIN_CANDIDATE_DEGREE};` —
      rewrite so `<n>` is the shipped one and the old value is described as the floor the recommender
      shipped with until issue #245. Every floor still earns its place; only which one is the default
      changes.
- [ ] Derive the rest rather than assume it. Run, blocking, and paste the output:

```bash
grep -rn "MIN_CANDIDATE_DEGREE" src docs --include='*.java' --include='*.md'
grep -rn "floor.*five\|five.*floor\|floor of 5\|floor 5" docs --include='*.md'
```

  Classify every hit: a by-reference use (leave), a statement about *this* default (fix), or an
  immutable ADR's record of a past decision (**leave — ADR 45's amendments and ADR 57 are the history
  and stay exactly as written**).
- [ ] Run the full gate, **blocking**. Quote its result. If anything reds, that red is information
      about a consumer this plan did not derive — report it rather than patching around it.
- [ ] Commit: `The candidate degree floor defaults to <n> (#245)`.

#### Step 3 — the owner's `recommend` run, and ADR 57's band

- [ ] **The implementer does not run this.** Hand the controller this line for the owner to run
      against his own database, with his own `--known` file and an output path he chooses:

```
./gradlew recommend --args="--known $HOME/known.csv --out $HOME/floor-<n>.txt"
```

  and ask for **the `FloorReading` header lines only** — the two `#` lines the report writes, which
  carry `floor`, `pool`, `poolMedianDegree`, `heldOut`, `heldOutAtDegreeOne`, `head`,
  `headMedianDegree`, `headOnTheFloor` and `headEveryEdgeCounted`. Every one is an aggregate; none is
  a name. **Do not ask for the ranked list**, which is known-list content at one remove and is exactly
  what ADR 51 forbids quoting.
- [ ] **Read `headOnTheFloor` against ADR 57's trigger band**, and that clause only. ADR 57 is the
      authority on the band's numbers and states them as chosen rather than measured; do not restate
      them here, do not treat the threshold as a finding, and do not move it.
- [ ] **Apply the spec's clause 4 exactly.** A move **downward** is gated on this reading: outside the
      band, the move does not ship, and the task stops and reports. A move **upward** is not gated —
      the reading is recorded either way, and where it falls outside the band the amendment says so
      plainly and says the move ships anyway because the rule does not gate it. Nothing fails a build
      when the floor drifts; ADR 57 says the trigger is held by a reader.
- [ ] **`poolMedianDegree` is comparable across runs at one floor and never across floors** — ADR 57's
      own caveat. Do not compare it to any figure recorded there for another floor.

#### Step 4 — the record: a dated amendment to ADR 57

Append to the **end** of `docs/adr/0057-the-floor-reports-itself.md`. Nothing above is edited; front
matter untouched.

- [ ] Write it to this skeleton:

````markdown

**Amendment (2026-09-04, issue #245): the degree floor defaults to `<n>` on the second measured
reading — and what the floor reading says about it.**

Nothing above is withdrawn and no decision above is edited. The reading this ADR added to every run
is unchanged, the trigger band is unchanged and is still chosen rather than measured, and both
refusals it argued — a newly discovered node is still not ranked, expansion state is still not
scored — stand exactly as written. What changes is the number the reading is a reading *of*.

**The rule was fixed before the number existed.** Commit `<RULE_COMMIT>` on 2026-09-04 committed
`docs/superpowers/specs/2026-09-04-second-reading-rule-design.md`, which states the decision rule in
full, including how a rate is read over the `in pool` cell, the margin that replaced a hit count, and
the extra reading a floor below the shipped one has to satisfy. The reading below was taken
afterwards. The rule is the authority on what would have counted; it is not restated here.

**The reading.** One run of `./gradlew evaluate` ([ADR 65](0065-an-offline-evaluation-harness-for-the-recommender.md))
on the owner's database, quoted whole and unedited. Aggregates only, per
[ADR 51](0051-what-an-adr-may-quote.md).

```
[THE PASTED TABLE, BYTE FOR BYTE, INCLUDING ITS HEADER AND ITS THREE `#` LINES]
```

**What the rule made of it.** [ONE SHORT PARAGRAPH naming the cells the clauses compared — by
(scorer, floor) and column — the dominance range, and each clause's verdict, including why the scorer
question was answered first and did not clear. Say that the negatives cells were read and decided
nothing. Do NOT restate a value from the block above.]

**What the floor reading says at the new default.** [ONE SHORT PARAGRAPH. State whether
`headOnTheFloor` sits inside the band this ADR chose, and if it does not, say so plainly and say
whether the rule gated on it in this direction. Name `FloorReading` as the authority on the figures,
and do not compare `poolMedianDegree` across floors — this ADR's own caveat.]

### What this costs, which the decision above does not

[THE COST SECTION, modelled on ADR 45's "The cost, which this ADR records nowhere above". Without
restating a figure, it must state:
 - the direction of the trade this move makes — a higher floor removes thinly-fetched entities from
   the ranking, a lower one admits more of what ingest has and has not reached;
 - that a hit rate read over `in pool` rises mechanically when a higher floor removes held-out
   entities the setting was missing anyway, so the margin was cleared on precision over what was
   reachable rather than on finding more;
 - that the held-out set is small, that one entity is a visible share of any rate on it, and that a
   cleared margin on it is a thin result rather than a settled one;
 - that the negatives condition was dropped rather than met, and the cells are quoted above instead;
 - that hit rate is read over the reachable, so this says nothing about coverage (ADR 65);
 - that ADR 45's 2026-08-29 amendment and ADR 50's distribution are the authorities on what choosing
   a floor costs the taste layer, and that nothing measured here revisits them.]

### What this amendment does not move

- **The scorer default is unchanged.** The rule permits one constant to move and this is the one that
  moved; the scorer question is re-asked in a later issue.
- **`Setting.FLOORS` is unchanged.** It already held this value, which is why the grid could produce
  this reading at all.
- **The trigger band is unchanged**, and it is still chosen rather than measured. Anyone re-deciding
  it should move the number rather than treat it as a finding — this ADR's own words.

### Consequences of this amendment

- **The default list is a different population**, and `--min-degree` at the old value reproduces the
  old behaviour exactly — the method this project uses for exactly this question.
- **The deck deals at the new floor too**, because `rate` reads the constant by reference.
- **The constants are no longer untested and one has now moved.** ADR 65 exists so that a reading
  could move one; the first reading moved none and the second moved this.
````

- [ ] Verify by the gate, and `diff` the fenced block against `<READING_PATH>` for byte-identity.
      Confirm no digit from either reading appears in the amendment's prose. Say both in the report.
- [ ] Commit: `Record the second evaluation reading and the floor it moved (#245)`.

---

### Task 3C: the shipped setting stands

**Run this task only if the ruling's outcome line reads `THE SHIPPED SETTING STANDS`.** That includes
the case where clause 2's void condition fired; the amendment below has a bracketed sentence for it.

- [ ] **No code changes at all.** The default scorer is unchanged, `Recommendations.MIN_CANDIDATE_DEGREE`
      is unchanged, no Javadoc moves, no guide line moves. **Say out loud in the step report that there
      is no test in this task and why**: nothing behavioural changed, so there is nothing to red. The
      verification method is the full gate over an otherwise unchanged tree, plus the ruling from
      Task 2.
- [ ] **This is a result and must not be written as an absence of one.** The amendment says what was
      measured and that nothing cleared the bar. It does not apologise for the outcome, does not
      speculate about what a wider grid might have shown, and does not add a "but X looked promising"
      clause — clause 7 exists precisely to refuse that.

#### The record: a dated amendment to ADR 45

Append to the **end** of `docs/adr/0045-recommend-by-normalised-lift-with-routes.md`. Nothing above is
edited; front matter untouched.

- [ ] Write it to this skeleton:

````markdown

**Amendment (2026-09-04, issue #245): a second reading, judged by a rule written to answer what the
first one exposed, moved nothing either.**

Nothing above is withdrawn and no decision above is edited, including the amendment immediately above
this one: that entry's reading, its ruling and its four observations stand exactly as written. No
constant changed and no code changed. What changed is that the shipped scorer and floor have now been
measured twice, the second time by a rule built to answer the first reading's own criticisms of
itself.

**The rule was fixed before the number existed, and it was written to answer the previous reading
rather than this one.** Commit `<RULE_COMMIT>` on 2026-09-04 committed
`docs/superpowers/specs/2026-09-04-second-reading-rule-design.md`, which states the decision rule in
full — rates over the `in pool` cell, the margin that replaced a hit count and the arithmetic that
voids it on a split too small to carry it, the dominance range that excludes the floors where the
shipped scorer has no hits, the dropped negatives clause and why it was dropped, the one-constant
limit, and the clause that says a near miss stands. The reading below was taken afterwards. The rule
is the authority on what would have counted and is not restated here.

**The reading.** One run of `./gradlew evaluate` ([ADR 65](0065-an-offline-evaluation-harness-for-the-recommender.md))
on the owner's database, quoted whole and unedited. Aggregates only, per
[ADR 51](0051-what-an-adr-may-quote.md): every cell is a count, a one-decimal mean or a dash, and
every label is a column name or a `Scorer` spelling.

```
[THE PASTED TABLE, BYTE FOR BYTE, INCLUDING ITS HEADER AND ITS THREE `#` LINES]
```

**What the rule made of it.** [ONE SHORT PARAGRAPH. Name the cells each clause compared — by
(scorer, floor) and column — the dominance range the rule derived, and say for each candidate scorer
and each candidate floor which condition it failed. Where a candidate came close, say so and say that
clause 7 is why it stands. Say that the negatives cells were read and decided nothing. Do NOT restate
a value from the block above.]

[IF THE VOID CONDITION FIRED, ADD ONE SENTENCE HERE AND OMIT IT OTHERWISE: the smallest `in pool`
cell among the rows the rule would have compared is low enough that a single held-out entity is worth
the whole margin, so by the rule's own arithmetic the margin was void and this reading was not
readable as evidence — the shipped setting stands on the instrument rather than on the number.]

### What this does and does not establish

- **It does not establish that the shipped setting is the best one.** It establishes that on this
  reading, by this rule, nothing displaced it. ADR 65's first consequence is the governing one: no row
  of that table means anything on its own.
- **It does not establish that the harness can tell these settings apart, and a second null result
  makes that the live question.** The held-out set is small, so a null result is also what an
  instrument too blunt for the question would produce. Two readings that move nothing do not
  distinguish "the setting is right" from "the split is too small to say", and enlarging the split is
  the only thing that would.
- **The negatives condition was dropped rather than satisfied**, so nothing here is a finding about
  the negatives column; the cells are quoted above and decided nothing.
- **It says nothing about the entities ingest cannot reach.** Rates are read over the reachable
  (ADR 65's consequence), so this is not a verdict on expansion coverage.

### Consequences of this amendment

- **Nothing in the tool moves**, so no ranking, no deck and no output line changes.
- **`Setting.GRID` is unchanged**, so a third reading is comparable to both of these row by row — the
  property #239 fixed the grid for.
- **The question is re-asked, not closed**, and what the next issue should change is the split rather
  than the rule.
````

- [ ] Verify by the gate: `AdrIndexTest`, `DocumentationLinksTest`, and the full run blocking.
      `diff` the fenced block against `<READING_PATH>` for byte-identity; confirm no digit from either
      reading appears in the prose. Say both in the report.
- [ ] Commit: `Record the second evaluation reading, which moved nothing (#245)`.

---

### Task 4: the closing sweep

Runs after whichever Task 3 variant executed. If Task 3B stopped at its step 0 or its step 3 gate,
this task does not run — the branch is reported to the owner as blocked, not swept.

- [ ] **The gate, blocking, from a clean tree:**
      `SEGUE_REQUIRE_BROWSER=true SEGUE_REQUIRE_GRAPHVIZ=true ./gradlew check --rerun-tasks`
      Quote `BUILD SUCCESSFUL` and the task count. A gate last run before the final commit is not a
      gate.
- [ ] **No `.superpowers/` citation reached a committed file — checked over this branch's own diff,
      not the repository.** Run, blocking:

```bash
git diff --stat main...HEAD
git diff main...HEAD -- docs/adr src | grep -n "\.superpowers"
```

  The second must print nothing. **A repo-wide `git grep` is the wrong check and must not be
  substituted**: every committed plan under `docs/superpowers/plans/` states the constraint in its own
  Global Constraints and so contains the literal, which is why #242's repo-wide form could not pass.
  The check that means something is that this branch added no such citation under `docs/adr` or `src`.
  `<READING_PATH>` and `<RULING_PATH>` are named in no committed file.
- [ ] **The amendment quotes the rule's commit hash.** Run, blocking:

```bash
git grep -n "<RULE_COMMIT>" -- docs/adr
```

  It must print exactly one line, inside the amendment this issue appended. Zero lines means the
  evidence that the rule preceded the number is not on the record and the amendment is incomplete.
- [ ] **The table appears once and no figure is restated.** Confirm the fenced block `diff`s clean
      against `<READING_PATH>`; confirm `git diff main...HEAD -- docs/adr` adds the
      `EvaluationReport.HEADER` line exactly once; confirm the amendment's prose carries no digit
      lifted from this reading **or from the first**. Report all three as checked, not assumed.
- [ ] **The ADR front matter and the index are untouched.** Confirm from
      `git diff main...HEAD -- docs/adr/` that the only change under `docs/adr/` is an append at the
      end of one file, and that `docs/adr/README.md` is not in the diff.
- [ ] **One constant moved, or none.** Confirm from `git diff main...HEAD -- src/main/` that at most
      one of the default scorer and `Recommendations.MIN_CANDIDATE_DEGREE` changed value. Any other
      constant in the diff is a defect; report it.
- [ ] **Report to the controller**, naming: which variant ran, the outcome string from the ruling,
      whether #244 had merged and which of step 2M / 2U ran, the commits, the gate result, and every
      finding that did not fit the rule — including the ones this plan already anticipates (`RateRun`'s
      own literal if step 2U ran, ADR 50's superseded "measured default" line, a floor reading outside
      ADR 57's band, a dominance range that was a single comparison repeated). A finding is reported,
      never documented away.
