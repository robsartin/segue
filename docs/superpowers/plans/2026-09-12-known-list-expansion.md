# `expandPromotions --known` — the known-list expansion — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** close issue #313 — `expandPromotions` gains an optional `--known <file>` and a second
population: the file's entities, resolved through the merge fold, that `Expanded.in(log)` (its seeds
resolved through the same fold) does not cover. `--known` and `--rated-since` together are refused.
The block gains one `#` clause naming the file's basename and how many the rule excluded as already
expanded; the no-flag block and the since-form block stay **byte-identical**. The census and the
expander read one rule, so they cannot disagree about who has been expanded.

**Architecture:** one move, two extractions, one new record, one new flag.

- **The move:** `census.KnownListInput` → `support.KnownListInput`, because
  `theExpanderOpensNothingElse` forbids `expand → census` outright. `support` is where a file reader
  more than one dev tool shares already lives (ADR 45), beside `QidList`, which it wraps.
- **The extractions**, out of `KnownListCensus`'s private statics and into `domain`, where both
  tools already depend: `Equivalences.canonical(List<String>)` (the ids on their canonical side,
  distinct, in the order given) and `Expanded.onTheCanonicalSide(Equivalences)` (the seeds on the
  side the population is counted on). `Expanded.covers` is already shared and does not move.
- **The new record:** `expand.KnownNeverExpanded(String file, int excluded)`, the second implementor
  of a new sealed `expand.Population` whose first is `RatedSince`. The renderers and `ExpandRun` take
  `Optional<Population>`, so which population a run covered is representable once and a run can never
  be both.
- **The flag:** `ExpandCli` parses `--known`, refuses it beside `--rated-since`, and composes the
  population in `run` — the one place this tool composes a population (#307's rule: `ExpandRun`
  never filters).

**Tech Stack:** Java 25, Gradle (plain `./gradlew`), JUnit 5, AssertJ, SQLite, ArchUnit.

**Spec:** `docs/superpowers/specs/2026-09-12-known-list-expansion-design.md` — read its **Premise
corrections** section first; it is where this plan and the issue differ, and it is the authority over
the body above it.

## Global Constraints

- **Pure TDD / red first.** Every behaviour: write the test, **run it, observe a real assertion
  failure**, then the minimum code. **A compile error is never a red.** Where a new type or accessor
  is needed before a test can compile, this plan splits the step: add the **stub** (the signature,
  returning the empty/false/wrong-but-compiling answer), then the test, then observe the assertion
  failure, then the body. **Quote the actual failure text in the task report** — not "it failed".
- **Every guard gets a positive control.** The controls this plan requires by name: Task 5 Step 4
  (the same file and the same graph, one row's reference changed from the statement-id shape to
  `ClaimMapper`'s fallback — `considered` must move from 2 to 3); Task 4 Step 6 (each flag alone
  still parses, so the refusal is about the pair and not about either flag); Task 6 Step 4 (a fixture
  file whose basename is itself qid-shaped must make the paste guard fire); Task 3 Step 7 (the two
  unchanged golden pins are the control that the no-flag and since-form blocks did not move — their
  diff must be empty).
- Test names `should<Expected>When<Condition>` with `@DisplayName`.
- **Mikado**: the gate is green before every commit, and each task ends green and is independently
  reviewable. **Stage by explicit path, git stderr visible — never `git add -A`, never `2>/dev/null`
  on `git add`.** Read `git status` before every commit. Commits end, after a blank line,
  `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`. **Never cite a `.superpowers/` path
  from a committed file.**
- Gate, **blocking, never backgrounded**:
  `SEGUE_REQUIRE_BROWSER=true SEGUE_REQUIRE_GRAPHVIZ=true ./gradlew check --rerun-tasks`. Run
  `./gradlew spotlessApply` before each gate.
- **Only JDK 25 is installed and Gradle launches on it.** Plain `./gradlew`; never
  `/usr/libexec/java_home -v 21` (it silently returns 25).
- **Never run a writing dev task** — `expandPromotions`, `own`, `ownClaim`, `retractEntity`, `rate`,
  `evaluate`, any seeding task. `~/.segue/segue.db` is never read, written, copied or created. Every
  database here is a `@TempDir` file and every known-list file is written by the test that reads it.
- **No test in this plan may reach the network.** A non-dry `ExpandCli.main` really expands anything
  the graph holds a node for. Every non-dry test here names **only refusable ids** — an id with no
  node (`UNKNOWN_ENTITY`) — which is how `ExpansionIsSafeToPasteTest` already reaches the full block
  offline. If a step's fixture would give an expandable node to a non-dry run, stop and report.
- **`docs/` is a declared input of `test`**, so a document edit re-runs the suite. Run the per-task
  loops **without** `--rerun-tasks`.
- **No commit hash, no `.superpowers/` path, no figure from the owner's graph, and no qid enters any
  file under `docs/adr`.** The #311 reading is cited as "the reading on #311", never restated.
- **Invented ids only, ADR 58's leading zero.** The ids this plan introduces are `Q0901301`,
  `Q0901302`, `Q0901303`, `Q0901304` and `Q0901305`; `grep -rn 'Q09013' src docs` finds none of them
  today (checked). The leading-zero form is exempt from `StandInQidsDenoteNothingTest`'s sweep. Where
  a **merge** is needed, the local side must be the two-leading-zero form and the canonical side
  ADR 62's eleven-digit reserved shape — `ExpandedTest` already declares `Q00901003` and
  `Q10000901004` for exactly that, and this plan reuses them rather than minting a second pair.
- **After `./gradlew spotlessApply`, re-read any javadoc this plan writes** and confirm every
  `{@code …}` span is intact and **on one source line**. google-java-format reflows javadoc and will
  break inside an inline tag. To put a span on one line, shorten the clause *before* it.
- **No wall-clock assertion anywhere.** The machine is loaded.
- **No ArchUnit rule changes in this issue.** The spec's *Fences* section derives from the code why
  none is needed. If a rule fires, **stop and report it** rather than editing the rule.
- **No entity is ever named on the block.** The one piece of text the clause carries is the file's
  **basename**, and `KnownListInput` is the one home of that rule.

**The clause, exactly** (one line in the rendered block; wrapped here only for the page):

```
# only known-list entities from known.csv that no expansion has covered: 7 excluded (some row in
the log cites them as an expansion's seed) — the file's ids are read through the merge fold, so a
merge's two sides count once.
```

---

## Task 1 — Move `KnownListInput` into `support`, where the expander can reach it

Files: `src/main/java/com/robsartin/segue/support/KnownListInput.java` (moved),
`src/main/java/com/robsartin/segue/census/Census.java`,
`src/main/java/com/robsartin/segue/census/CensusRun.java`,
`src/main/java/com/robsartin/segue/census/KnownListCensus.java`,
`src/test/java/com/robsartin/segue/support/KnownListInputTest.java` (moved),
`src/test/java/com/robsartin/segue/census/KnownListCensusTest.java`,
`src/test/java/com/robsartin/segue/census/KnownListCensusScaleTest.java`,
`src/test/java/com/robsartin/segue/census/CensusReportTest.java`,
`docs/developer-guide.md`.

**This task has no red, and that is honest rather than an omission.** It changes no behaviour: the
same class, the same reader, a different package. Its verification is the census suite and the
architecture rules passing unchanged plus the full gate, and the task report must say so in those
words.

- [ ] **Step 1 — confirm the call sites before touching anything.**

  ```
  grep -rn 'KnownListInput' src docs/developer-guide.md
  ```

  Expected: the class itself, three `src/main` files in `census` (`Census`, `CensusRun`,
  `KnownListCensus` — plus one plain `//` comment in `CensusReport` that needs no import), four test
  files, and **no** mention in `docs/developer-guide.md`. Matches under `docs/superpowers/plans/` are
  the #311 plan and are historical — **do not edit them**. If `src` is longer than that, stop and
  report.

- [ ] **Step 2 — move the class and its test with `git mv`**, so the history follows:

  ```
  git mv src/main/java/com/robsartin/segue/census/KnownListInput.java src/main/java/com/robsartin/segue/support/KnownListInput.java
  git mv src/test/java/com/robsartin/segue/census/KnownListInputTest.java src/test/java/com/robsartin/segue/support/KnownListInputTest.java
  ```

  In both files change `package com.robsartin.segue.census;` to `package com.robsartin.segue.support;`
  and **delete** `import com.robsartin.segue.support.QidList;` from the main file — `QidList` is now
  a package sibling.

- [ ] **Step 3 — add the paragraph that says why it lives here.** In `KnownListInput`'s class
  javadoc, keep every existing paragraph and append one:

  ```java
   * <p><b>In {@code support} because two dev tools read the same file, which is the reason {@code
   * QidList} is here too (ADR 45).</b> The census reads it to count coverage and the promotion
   * expander reads it to choose its population (#311, #313), and {@code
   * ArchitectureTest.theExpanderOpensNothingElse} forbids the expander every sibling dev tool with
   * no exception — so a shared reader neither of them owns is the only way they can read one file
   * by one rule.
  ```

  Change the record's own summary line from "as the census is allowed to hold it" to "as a dev tool
  is allowed to hold it".

- [ ] **Step 4 — add the import to the three `census` files that name the type:**
  `Census.java`, `CensusRun.java` and `KnownListCensus.java` each gain

  ```java
  import com.robsartin.segue.support.KnownListInput;
  ```

  in import order (after `com.robsartin.segue.export.LogProjection` where one is present, before the
  `java.*` block). Add the same import to `KnownListCensusTest`, `KnownListCensusScaleTest` and
  `CensusReportTest`. `CensusReport.java` names it only inside a `//` comment and needs nothing.

- [ ] **Step 5 — run the suites this could have broken.**

  ```
  ./gradlew test --tests 'com.robsartin.segue.census.*' --tests 'com.robsartin.segue.support.*' --tests '*ArchitectureTest' --tests '*PackageListsTest' --tests '*DeveloperGuideEnumerationsTest'
  ```

  Expected green with a non-zero test count in each package — a `--tests` filter matching nothing is
  the failure mode here. Record the counts. The rules that could have caught this are
  `theCensusOpensNothingElse`, `theCensusTakesItsDatabaseFromTheFlagAlone` (it fires on a `Path`
  handed out of `support`; `KnownListInput.read` hands back a `KnownListInput`) and
  `noPackageCycles`.

- [ ] **Step 6 — the guide's `support` row follows the code.** In `docs/developer-guide.md`, in the
  `### What each package is for` table, the `support` row lists what lives there. Insert
  `KnownListInput` immediately after the `QidList` clause, keeping the row's sentence shape:

  > `KnownListInput` (the basename-and-ids reading of that file `census` and `expand` share, so the
  > block each prints names a basename and never a path — issues #311 and #313),

  Change nothing else in the row, and **do not** touch the `Depends on` column: `census` and `expand`
  both already list `support`, and `DeveloperGuideEnumerationsTest` checks only the row set and says
  in as many words that that column is prose.

- [ ] **Step 7 — format, gate, commit.**

  ```
  ./gradlew spotlessApply
  SEGUE_REQUIRE_BROWSER=true SEGUE_REQUIRE_GRAPHVIZ=true ./gradlew check --rerun-tasks
  ```

  Re-read the javadoc paragraph from Step 3 after `spotlessApply` and confirm the `{@code QidList}`
  and `{@code ArchitectureTest.theExpanderOpensNothingElse}` spans are each intact on one line. Then
  `git status`, stage by explicit path, commit:

  > Move KnownListInput into support, where both its readers can reach it (#313)

---

## Task 2 — Extract the two folds into `domain`, so the expander reuses the census's rule

Files: `src/main/java/com/robsartin/segue/domain/Equivalences.java`,
`src/main/java/com/robsartin/segue/domain/Expanded.java`,
`src/main/java/com/robsartin/segue/census/KnownListCensus.java`,
`src/test/java/com/robsartin/segue/domain/EquivalencesTest.java`,
`src/test/java/com/robsartin/segue/domain/ExpandedTest.java`.

**Read first.** `KnownListCensus` holds both folds as private statics today: `canonical(List<String>,
Equivalences)` and `onTheCanonicalSide(Expanded, Equivalences)`. `expand` may not call into `census`,
so they move rather than get copied. The census's own tests — `KnownListCensusTest`'s merge case and
`KnownListCensusScaleTest` — are the control that the move changed no answer.

- [ ] **Step 1 — the stub on `Equivalences`.** Add, immediately after the existing
  `canonical(String)`:

  ```java
  public List<String> canonical(List<String> qids) {
    Objects.requireNonNull(qids, "qids");
    return List.of();
  }
  ```

  `LinkedHashSet`, `List`, `Objects` and `Set` are already imported.

- [ ] **Step 2 — RED: the list fold.** In `EquivalencesTest`, add (using the file's own existing
  local/canonical constants if it declares a merge pair; otherwise the two shapes named in Global
  Constraints):

  ```java
  @Test
  @DisplayName("a merge's two sides collapse to one id, on the canonical side, in the order given")
  void shouldFoldBothSidesOntoOneIdWhenAListNamesAMergesLocalAndCanonicalSides() {
    Equivalences merges = new Equivalences(Map.of("Q00901003", "Q10000901004"));

    assertThat(merges.canonical(List.of("Q0901301", "Q00901003", "Q10000901004", "Q0901302")))
        .as("distinct, in the order given, and the merge counted once on the side it turned out"
            + " to be")
        .containsExactly("Q0901301", "Q10000901004", "Q0901302");
  }
  ```

  Run it:

  ```
  ./gradlew test --tests '*EquivalencesTest'
  ```

  **Expected failure — a real assertion, not a compile error**, of the shape:

  ```
  java.lang.AssertionError: [distinct, in the order given, ...]
  Expecting actual:
    []
  to contain exactly (and in same order):
    ["Q0901301", "Q10000901004", "Q0901302"]
  but could not find the following elements:
    ["Q0901301", "Q10000901004", "Q0901302"]
  ```

  Quote the real text in the task report.

- [ ] **Step 3 — GREEN: the body and its javadoc.** Replace the stub with:

  ```java
  /**
   * Every id here on the side it turned out to be, distinct, in the order given.
   *
   * <p><b>The list shape of {@link #canonical(String)}, beside {@link #resolve} and {@link
   * #resolveUpdatedAt} for the two map shapes.</b> It is here rather than in either caller because
   * two dev tools read one known-list file and count over it — the census counts coverage and the
   * expander chooses its population (#311, #313) — and they may not depend on each other. De-
   * duplication is part of the fold and not a caller's afterthought: a file naming both sides of a
   * merge names one entity, and counting it twice would be a second answer to a question this type
   * already owns.
   */
  public List<String> canonical(List<String> qids) {
    Objects.requireNonNull(qids, "qids");
    Set<String> resolved = new LinkedHashSet<>();
    for (String qid : qids) {
      resolved.add(canonical(qid));
    }
    return List.copyOf(resolved);
  }
  ```

  Re-run Step 2's command: green.

- [ ] **Step 4 — the stub on `Expanded`.** Add, after `covers`:

  ```java
  public Expanded onTheCanonicalSide(Equivalences merges) {
    Objects.requireNonNull(merges, "merges");
    return this;
  }
  ```

- [ ] **Step 5 — RED: the seeds through the fold.** In `ExpandedTest`, add — `LOCAL` and `CANONICAL`
  are the constants that file already declares:

  ```java
  @Test
  @DisplayName("a seed recorded on a merge's retired side covers the canonical id after the fold")
  void shouldCoverTheCanonicalIdWhenTheSeedWasRecordedOnTheRetiredSide() {
    Expanded expanded = Expanded.in(List.of(edge(LOCAL, OTHER, LOCAL + "$4f1a-invented")));

    assertThat(expanded.covers(CANONICAL))
        .as("the raw rows cite the id the owner has since retired")
        .isFalse();
    assertThat(expanded.onTheCanonicalSide(new Equivalences(Map.of(LOCAL, CANONICAL)))
            .covers(CANONICAL))
        .as("a population already on its canonical side must find the work that was really done")
        .isTrue();
  }
  ```

  Add `import java.util.Map;` if the file lacks it. The first assertion is the control: it is what
  says the second one is about the fold and not about the rule.

  Run:

  ```
  ./gradlew test --tests '*ExpandedTest'
  ```

  **Expected failure:**

  ```
  java.lang.AssertionError: [a population already on its canonical side must find the work that was really done]
  Expecting value to be true but was false
  ```

- [ ] **Step 6 — GREEN: the body and its javadoc.**

  ```java
  /**
   * The seeds on the side a population is counted on, by the fold that population is read by.
   *
   * <p><b>Defensive rather than reachable today.</b> {@link #in} reads the rows as they were
   * written, so a row recorded before a merge cites the id the owner has since retired; asked about
   * a population already folded, it would report an entity as never expanded on work that was
   * really done. No writer in {@code src/main} records a seed on a retired side — a merge retires
   * an id the expander no longer visits — so this exists so that one fold, one side and every count
   * stay true whatever a later writer does (#311, #313).
   */
  public Expanded onTheCanonicalSide(Equivalences merges) {
    Objects.requireNonNull(merges, "merges");
    Set<String> resolved = new LinkedHashSet<>();
    for (String seed : seeds) {
      resolved.add(merges.canonical(seed));
    }
    return new Expanded(resolved);
  }
  ```

  Re-run Step 5's command: green.

- [ ] **Step 7 — rewire `KnownListCensus` and delete both copies.** In `KnownListCensus.of`:

  ```java
    Equivalences merges = fold.equivalences();
    List<String> fromFile = merges.canonical(known.qids());
  ```

  and

  ```java
    Expanded seeds = expanded.onTheCanonicalSide(merges);
  ```

  Delete the two private statics `onTheCanonicalSide` and `canonical` and the now-unused
  `LinkedHashSet` import if nothing else in the file uses it (check with
  `grep -n 'LinkedHashSet' src/main/java/com/robsartin/segue/census/KnownListCensus.java`). Keep the
  class javadoc's "The expansion seeds are read through the same fold" paragraph and add, at its end:

  ```java
   * The two folds themselves now live on {@link Equivalences} and {@link Expanded}, because the
   * promotion expander composes its known-list population by the same two steps and may not depend
   * on this package (#313).
  ```

- [ ] **Step 8 — the census suites are the control.**

  ```
  ./gradlew test --tests 'com.robsartin.segue.census.*' --tests 'com.robsartin.segue.domain.*'
  ```

  Expected green, unchanged, with `KnownListCensusTest`'s merge case among them. Record the counts.

- [ ] **Step 9 — format, gate, commit.**

  ```
  ./gradlew spotlessApply
  SEGUE_REQUIRE_BROWSER=true SEGUE_REQUIRE_GRAPHVIZ=true ./gradlew check --rerun-tasks
  ```

  Re-read both new javadocs after `spotlessApply` for one-line `{@code …}` spans. Then `git status`,
  stage by explicit path, commit:

  > Move the known-list folds into domain, beside the rule they fold for (#313)

---

## Task 3 — `Population`: one clause, two shapes, and the pins that hold them

Files: `src/main/java/com/robsartin/segue/expand/Population.java` (new),
`src/main/java/com/robsartin/segue/expand/KnownNeverExpanded.java` (new),
`src/main/java/com/robsartin/segue/expand/RatedSince.java`,
`src/main/java/com/robsartin/segue/expand/ExpansionReport.java`,
`src/main/java/com/robsartin/segue/expand/ExpandRun.java`,
`src/main/java/com/robsartin/segue/expand/ExpandCli.java`,
`src/test/java/com/robsartin/segue/expand/ExpansionReportTest.java`.

**Read first.** Every existing caller passes `Optional.of(new RatedSince(…))` inline, and Java infers
the target type at the call, so widening the parameter needs **no test edit**. The one place a local
variable is declared is `ExpandCli.run` (`Optional<RatedSince> filter`). If any call site does not
compile after Step 2, fix the call site — never the parameter.

- [ ] **Step 1 — the sealed interface.** Create
  `src/main/java/com/robsartin/segue/expand/Population.java`:

  ```java
  package com.robsartin.segue.expand;

  /**
   * Which population a run covered, where it was not simply every promotion (#307, #313).
   *
   * <p><b>Sealed, and carried as one {@link java.util.Optional}, so a run cannot be two
   * populations.</b> The flags that select them are exclusive at the command line; this is the same
   * exclusivity one level in, where no parser has to be trusted for it. {@code ExpansionReport}
   * switches over this with no {@code default}, so a third population has to decide what it prints
   * rather than fall through to another population's sentence.
   *
   * <p><b>There is one operator-supplied fact in each shape and no room for an entity id in
   * either.</b> {@code Instant.toString} emits no letter but {@code T} and {@code Z};
   * {@code KnownNeverExpanded} carries a basename, which {@code KnownListInput} is the one home of
   * — the basename and never the path (ADR 51, ADR 63).
   */
  public sealed interface Population permits RatedSince, KnownNeverExpanded {}
  ```

  Add `implements Population` to `RatedSince`'s declaration. **This does not compile yet** —
  `KnownNeverExpanded` is Step 2 — so Steps 1 and 2 are one edit before anything is run.

- [ ] **Step 2 — the second shape, stubbed into the block as nothing.** Create
  `src/main/java/com/robsartin/segue/expand/KnownNeverExpanded.java`:

  ```java
  package com.robsartin.segue.expand;

  import java.util.Objects;

  /**
   * The known-list population a run covered, and what the rule excluded (issue #313).
   *
   * @param file the known-list file's <b>basename</b>, never its path — {@code
   *     support.KnownListInput} is the one home of that rule, and the block is meant to be pasted
   * @param excluded how many of the file's entities, after the merge fold, some row in the log
   *     cites as an expansion's seed
   */
  public record KnownNeverExpanded(String file, int excluded) implements Population {

    public KnownNeverExpanded {
      Objects.requireNonNull(file, "file");
      if (excluded < 0) {
        throw new IllegalArgumentException("excluded cannot be negative, got " + excluded);
      }
    }
  }
  ```

- [ ] **Step 3 — widen the renderers and the run, and switch on the population.** In
  `ExpansionReport`, change both long arities' parameter type and rename the parameter:

  ```java
  /** Render the whole block, header included, saying which population it covered. */
  public static List<String> lines(ExpansionTally tally, Optional<Population> covered) {
    Objects.requireNonNull(covered, "covered");
    return render(HEADER, covered, body(tally));
  }
  ```

  ```java
  /** Render what a dry run would visit, saying which population it would have covered. */
  public static List<String> dryRunLines(Preflight preflight, Optional<Population> covered) {
    Objects.requireNonNull(covered, "covered");
    return render(DRY_RUN_HEADER, covered, dryRunBody(preflight));
  }
  ```

  Change `render`'s parameter to `Optional<Population> covered`, its body line to
  `covered.ifPresent(it -> rendered.add(clause(it)));`, and add the dispatcher beside `sinceLine`,
  with the second arm **stubbed to the empty string** so the next step's red is an assertion:

  ```java
  /**
   * The one {@code #} clause a run prints when it covered something other than every promotion.
   *
   * <p>Exhaustive over {@link Population} with no {@code default}: a third population decides what
   * it says here, rather than inheriting another population's sentence.
   */
  private static String clause(Population covered) {
    return switch (covered) {
      case RatedSince since -> sinceLine(since);
      case KnownNeverExpanded known -> knownLine(known);
    };
  }

  private static String knownLine(KnownNeverExpanded known) {
    return "";
  }
  ```

  In `ExpandRun`, change both long arities' second parameter to `Optional<Population> covered`
  (rename `filter` throughout those two methods, including the `Objects.requireNonNull` and the two
  `ExpansionReport` calls). In `ExpandCli.run`, change the local declaration to
  `Optional<Population> filter = …` and leave the rest of that method alone for now.

- [ ] **Step 4 — the package compiles and every existing test still passes.**

  ```
  ./gradlew test --tests 'com.robsartin.segue.expand.*'
  ```

  Expected green, with the two golden pins among them. This is a refactor with no behaviour change;
  say so in the task report, and say that its verification was this suite.

- [ ] **Step 5 — RED: the two known-form pins.** In `ExpansionReportTest`, add beside `SINCE_LINE`:

  ```java
  /** The invented basename the clause names; a path never reaches the block. */
  private static final String KNOWN_FILE = "known.csv";

  /**
   * The clause both blocks print when a known-list file was given, character for character. A
   * literal, not ExpansionReport's sentence — see GOLDEN_BLOCK.
   */
  private static final String KNOWN_LINE =
      "# only known-list entities from known.csv that no expansion has covered: 7 excluded (some"
          + " row in the log cites them as an expansion's seed) — the file's ids are read through"
          + " the merge fold, so a merge's two sides count once.";

  /** The golden block with the known clause inserted under its header, and nothing else moved. */
  private static List<String> withKnownLine(List<String> block) {
    List<String> withIt = new ArrayList<>(block);
    withIt.add(1, KNOWN_LINE);
    return List.copyOf(withIt);
  }
  ```

  and the two tests:

  ```java
  @Test
  @DisplayName("the block names the file and what it excluded when a known list was expanded")
  void shouldNameTheFileWhenTheBlockCoversOnlyWhatWasNeverExpanded() {
    assertThat(
            ExpansionReport.lines(
                goldenTally(), Optional.of(new KnownNeverExpanded(KNOWN_FILE, 7))))
        .containsExactlyElementsOf(withKnownLine(GOLDEN_BLOCK));
  }

  @Test
  @DisplayName("the dry run block names the file and what it excluded when a known list was given")
  void shouldNameTheFileWhenTheDryRunBlockCoversOnlyWhatWasNeverExpanded() {
    List<String> lines =
        ExpansionReport.dryRunLines(
            new Preflight(4, 2, 1), Optional.of(new KnownNeverExpanded(KNOWN_FILE, 7)));

    assertThat(lines)
        .containsExactly(
            "# segue promotion expansion — dry run: appends nothing. Aggregates only"
                + " (ADR 51, ADR 63).",
            KNOWN_LINE,
            "",
            "promotions",
            "  considered    4",
            "  in the graph  2",
            "  minted        1");
  }
  ```

  Run:

  ```
  ./gradlew test --tests '*ExpansionReportTest'
  ```

  **Expected failure — two assertions, on the clause line alone**, of the shape:

  ```
  java.lang.AssertionError:
  Expecting actual:
    ["# segue promotion expansion — dry run: appends nothing. Aggregates only (ADR 51, ADR 63).",
     "",
     "",
     "promotions", ...]
  to contain exactly (and in same order):
    ["# segue promotion expansion — dry run: appends nothing. Aggregates only (ADR 51, ADR 63).",
     "# only known-list entities from known.csv that no expansion has covered: 7 excluded ...",
     "",
     "promotions", ...]
  ```

  Every other line matches in order; the empty string the stub returns is what differs. Quote the
  real text.

- [ ] **Step 6 — GREEN: the sentence.** Replace `knownLine`'s body:

  ```java
  /**
   * Said under the header when a known-list file was given, and not at all when none was —
   * {@link #sinceLine}'s argument, which carries the reasoning for a clause rather than a row.
   *
   * <p>The fold clause is here rather than in the guide alone because the number beside it is
   * misread without it: the file's ids are counted on their canonical side, so a file naming both
   * sides of a merge names one entity, and the excluded count is over that population and not over
   * the file's lines.
   */
  private static String knownLine(KnownNeverExpanded known) {
    return "# only known-list entities from "
        + known.file()
        + " that no expansion has covered: "
        + known.excluded()
        + " excluded (some row in the log cites them as an expansion's seed) — the file's ids are"
        + " read through the merge fold, so a merge's two sides count once.";
  }
  ```

  Re-run Step 5's command: green.

- [ ] **Step 7 — the control: the two blocks already on record did not move.**

  ```
  git diff -- src/test/java/com/robsartin/segue/expand/ExpansionReportTest.java | grep -E '^[-+].*(GOLDEN_BLOCK|SINCE_LINE) ?=' 
  git diff -- src/test/java/com/robsartin/segue/expand/ExpansionReportTest.java | grep -c '^-'
  ```

  Expected: no line of `GOLDEN_BLOCK` or `SINCE_LINE` altered, and **zero deleted lines** in that
  file — every change is an addition. Record both results. If a deletion appears, the no-flag or
  since-form block moved and the task has failed its own control.

- [ ] **Step 8 — format, gate, commit.**

  ```
  ./gradlew spotlessApply
  SEGUE_REQUIRE_BROWSER=true SEGUE_REQUIRE_GRAPHVIZ=true ./gradlew check --rerun-tasks
  ```

  Re-read `Population`'s and `KnownNeverExpanded`'s javadoc after `spotlessApply` for one-line
  `{@code …}` spans. Then `git status`, stage by explicit path, commit:

  > Say which population a run covered, in two shapes rather than one (#313)

---

## Task 4 — `--known` at the command line, and the refusal that keeps the two flags apart

Files: `src/main/java/com/robsartin/segue/expand/ExpandCli.java`,
`src/test/java/com/robsartin/segue/expand/ExpandCliTest.java`.

**Read first.** `parse` must **not** read the file: `DeveloperGuideExpandPromotionsExamplesTest` runs
every guide example through `parse` with an invented `$HOME`, and a file read there would red the
guide on a path that does not exist. The file is read in `run`, exactly as `--db`'s existence is
checked in `run`. Append the new flag's bracket group to the **end** of `USAGE`: an existing test
asserts the message contains `[--rated-since`, and that survives only while that flag keeps its own
bracket group.

- [ ] **Step 1 — the stub: the option exists and is always empty.** In `ExpandCli`, add the
  component and the javadoc line:

  ```java
  /**
   * What to expand against, and how far.
   *
   * @param database no default, on purpose — see this class's javadoc
   * @param maxNewEdges the bound handed to every entity's expansion, defaulting to {@link
   *     ExpandContext#defaults()}
   * @param dryRun report what would be visited and touch no network and no log
   * @param ratedSince the instant to filter promotions by, or empty for no filter
   * @param known the known-list file whose never-expanded entities are the population, or empty for
   *     the promotions. Never read here: the guide's examples are parsed with an invented home
   */
  record Options(
      Path database,
      int maxNewEdges,
      boolean dryRun,
      Optional<Instant> ratedSince,
      Optional<Path> known) {}
  ```

  and in `parse`, after the `--rated-since` block, consume the flag and discard it for now:

  ```java
    String knownValue = values.remove("--known");
  ```

  and return `new Options(database, maxNewEdges, dryRun, Optional.ofNullable(ratedSince), Optional.empty());`

  Compile with `./gradlew compileJava`. Nothing else constructs `Options`.

- [ ] **Step 2 — RED: the file is carried.** In `ExpandCliTest`, add beside the `--rated-since`
  parser tests:

  ```java
  @Test
  @DisplayName("--known is carried as the path when one is given, and the file is not read here")
  void shouldCarryTheKnownFileWhenOneIsGiven() {
    assertThat(
            ExpandCli.parse(
                    new String[] {"--db", "db.sqlite", "--known", "known.csv"},
                    null,
                    home.toString())
                .known())
        .as("parse opens nothing: the guide's examples are parsed against an invented home")
        .contains(Path.of("known.csv"));
  }

  @Test
  @DisplayName("no file is carried when the flag is absent, which is every run before this one")
  void shouldCarryNoKnownFileWhenTheFlagIsAbsent() {
    assertThat(ExpandCli.parse(new String[] {"--db", "db.sqlite"}, null, home.toString()).known())
        .isEmpty();
  }
  ```

  Run:

  ```
  ./gradlew test --tests '*ExpandCliTest'
  ```

  **Expected failure:**

  ```
  java.lang.AssertionError: [parse opens nothing: the guide's examples are parsed against an invented home]
  Expecting Optional to contain:
    known.csv
  but was empty.
  ```

- [ ] **Step 3 — GREEN.** In `parse`, replace the discard with

  ```java
    Path known = null;
    String knownValue = values.remove("--known");
    if (knownValue != null) {
      known = Path.of(knownValue);
    }
  ```

  and return `Optional.ofNullable(known)` as the fifth component. Re-run Step 2's command: green.

- [ ] **Step 4 — RED: the two flags together are refused.**

  ```java
  @Test
  @DisplayName("--known and --rated-since together are refused: they name different populations")
  void shouldRefuseBothFlagsWhenAKnownFileAndAnInstantAreGiven() {
    assertThatThrownBy(
            () ->
                ExpandCli.parse(
                    new String[] {
                      "--db", "db.sqlite",
                      "--known", "known.csv",
                      "--rated-since", "2026-09-08T00:00:00Z"
                    },
                    null,
                    home.toString()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("--known and --rated-since name different populations")
        .hasMessageContaining("--db <segue.db>");
  }
  ```

  Run Step 2's command. **Expected failure:**

  ```
  java.lang.AssertionError:
  Expecting code to raise a throwable.
  ```

- [ ] **Step 5 — GREEN: the refusal and the usage line.** In `parse`, immediately after both flags
  have been read and before the unknown-option check:

  ```java
    if (known != null && ratedSince != null) {
      // Two populations, not two filters over one: --rated-since narrows the promotions and
      // --known replaces them. A run that took both would have to say which one it covered, and
      // the block says exactly one thing (#313).
      throw usage("--known and --rated-since name different populations — give one or neither");
    }
  ```

  and extend `USAGE`, keeping every existing bracket group intact and appending the new one last:

  ```java
  private static final String USAGE =
      "usage: --db <segue.db> [--max-new-edges <n>] [--dry-run] [--rated-since <ISO-8601 instant,"
          + " e.g. 2026-09-06T15:00:00Z>] [--known <file of QIDs>]";
  ```

  Re-run: green.

- [ ] **Step 6 — the control: the refusal is about the pair.** Confirm — by re-running the suite,
  not by adding assertions — that `shouldCarryTheKnownFileWhenOneIsGiven` (a `--known` run with no
  instant) and `shouldCarryTheInstantWhenRatedSinceIsGiven` (an instant with no file) both still
  pass, and that `shouldRefuseTheInstantWhenItIsNotAnInstant` still passes with its
  `hasMessageContaining("[--rated-since")` assertion — that one is the control that the new bracket
  group did not swallow the old one. Name all three in the task report.

- [ ] **Step 7 — format, gate, commit.**

  ```
  ./gradlew spotlessApply
  SEGUE_REQUIRE_BROWSER=true SEGUE_REQUIRE_GRAPHVIZ=true ./gradlew check --rerun-tasks
  ```

  `git status`, stage by explicit path, commit:

  > Parse --known, and refuse it beside --rated-since (#313)

---

## Task 5 — The population: the file, the fold, and the rule

Files: `src/main/java/com/robsartin/segue/expand/ExpandCli.java`,
`src/main/java/com/robsartin/segue/expand/Preflight.java`,
`src/main/java/com/robsartin/segue/expand/ExpansionTally.java`,
`src/main/java/com/robsartin/segue/expand/ExpandRun.java`,
`src/test/java/com/robsartin/segue/expand/ExpandCliTest.java`.

**Read first.** Every test in this task that is **not** a dry run must name only ids the graph holds
no node for, or it will really expand something. The dry-run tests below hold nodes on purpose; the
one non-dry test holds none.

- [ ] **Step 1 — RED: the known-list population is what a `--known` run visits.** In `ExpandCliTest`,
  add the constants, the fixture and the test:

  ```java
  /** On the known-list file, with a node, and cited by a row as an expansion's seed. */
  private static final String ALREADY_EXPANDED = "Q0901301";

  /** On the file, with a node, cited by nothing as a seed. */
  private static final String NEVER_EXPANDED = "Q0901302";

  /** The same, so the count below can drop by one and still not be a drop to nothing. */
  private static final String ALSO_NEVER_EXPANDED = "Q0901303";

  /** On the file, and deliberately given no node at all. */
  private static final String NO_NODE = "Q0901304";

  /**
   * Three invented entities with nodes, one edge between two of them, and no rating anywhere.
   *
   * <p>The edge's reference decides the whole test: {@code reference} is either a Wikidata
   * statement id, which names {@link #ALREADY_EXPANDED} as the expansion's seed, or {@code
   * ClaimMapper}'s fallback for a statement carrying no id, which names no seed at all. One
   * character of fixture is the difference between the rule firing and not.
   */
  private Path knownListGraph(String name, String reference) {
    Path db = home.resolve(name);
    Provenance sourced = new Provenance("wikidata", reference, WHEN, 1.0);
    Provenance plain = new Provenance("invented", "invented:6", WHEN, 1.0);
    try (SqliteAssertionLog log = new SqliteAssertionLog(db)) {
      log.append(new NodeAssertion(ALREADY_EXPANDED, NodeKind.GROUP, "an invented act", plain));
      log.append(new NodeAssertion(NEVER_EXPANDED, NodeKind.GROUP, "another invented act", plain));
      log.append(new NodeAssertion(ALSO_NEVER_EXPANDED, NodeKind.GROUP, "a third one", plain));
      log.append(
          new AssertionRecord(
              ALREADY_EXPANDED, NEVER_EXPANDED, "INFLUENCED_BY", null, null, sourced));
    }
    return db;
  }

  /** The file the runs below are given: three ids, one per line, in a @TempDir. */
  private Path knownFile(String name) throws Exception {
    return Files.writeString(
        home.resolve(name),
        ALREADY_EXPANDED + "\n" + NEVER_EXPANDED + "\n" + ALSO_NEVER_EXPANDED + "\n");
  }

  @Test
  @DisplayName("only the known-list entities no row cites as a seed are considered")
  void shouldConsiderOnlyTheNeverExpandedEntitiesWhenAKnownFileIsGiven() throws Exception {
    Path db = knownListGraph("known.db", ALREADY_EXPANDED + "$4f1a-invented");
    Path file = knownFile("known.csv");
    captured.list.clear();

    ExpandCli.main(
        new String[] {"--db", db.toString(), "--dry-run", "--known", file.toString()});

    assertThat(countOn(lines(), "considered"))
        .as("three ids in the file, one of them cited by a row as an expansion's seed")
        .isEqualTo(2);
    assertThat(lines())
        .as("and the block says which population it covered, naming the basename")
        .anyMatch(line -> line.startsWith("# only known-list entities from known.csv"));
  }
  ```

  Add the imports `com.robsartin.segue.domain.AssertionRecord` and `java.nio.file.Files`.

  Run:

  ```
  ./gradlew test --tests '*ExpandCliTest'
  ```

  **Expected failure — the flag is parsed but nothing reads it, so the run still covers the
  promotions, of which this database has none:**

  ```
  java.lang.AssertionError: [three ids in the file, one of them cited by a row as an expansion's seed]
  expected: 2
   but was: 0
  ```

  If instead the run reports `replay refused:` on the edge, stop: the three node claims must be
  appended before it, and both endpoints must have one.

- [ ] **Step 2 — GREEN: compose the population in `ExpandCli.run`.** Replace the block that reads the
  ratings and builds the promotions — from `Map<String, Integer> ratings = …` through
  `log.info("{} promotion(s) to visit", promotions.size());` — with:

  ```java
      List<String> population;
      Optional<Population> covered;
      if (options.known().isPresent()) {
        // The census's own two folds and its own rule (#311, #313), reused rather than copied:
        // the file's ids on their canonical side, and the log's expansion seeds on the same side,
        // so this tool and graphCensus cannot come to disagree about who has been expanded. A
        // --known run composes no promotions, so it reads no rating at all — ADR 16's
        // minimisation falling out of the shape, exactly as a run with no --rated-since reads no
        // timestamp.
        KnownListInput known = KnownListInput.read(options.known().get());
        List<String> named = merges.canonical(known.qids());
        Expanded expanded = Expanded.in(assertions.readAll()).onTheCanonicalSide(merges);
        population = named.stream().filter(qid -> !expanded.covers(qid)).toList();
        covered =
            Optional.of(
                new KnownNeverExpanded(known.name(), named.size() - population.size()));
        log.info("{} known-list entity(s) to visit", population.size());
      } else {
        // Resolved before the threshold is applied: a merge leaves two affinity rows naming one
        // thing, and promoting both would expand the id the owner retired as well as the one he
        // kept. A count, never a qid and never a score (ADR 33).
        Map<String, Integer> ratings = merges.resolve(affinity.readRatings());
        log.info("read {} rating(s)", ratings.size());
        // KnownList.promoted with no file IS "rated at or above PROMOTION_RATING, ascending by
        // qid" — the threshold and the order from the class that owns both, rather than a second
        // copy of the rule here (issues #106 and #109).
        List<String> promoted = KnownList.promoted(List.of(), ratings);
        // Read only when asked, and resolved through the same merges the ratings were, so the two
        // maps are keyed alike and a promotion's age cannot be read off another row (#276, #307).
        // The set handed over is the PROMOTIONS: a rated entity this tool was never going to
        // visit is not a reason to refuse the run.
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
        covered = age.map(it -> new RatedSince(it.since(), promoted.size() - promotions.size()));
        population = promotions;
        log.info("{} promotion(s) to visit", promotions.size());
      }
  ```

  Then rename the four call sites' argument at the foot of the method — `promotions` becomes
  `population` and `filter` becomes `covered`:

  ```java
      if (options.dryRun()) {
        if (covered.isPresent()) {
          run.dryRun(population, covered, log::info);
        } else {
          run.dryRun(population, log::info);
        }
      } else if (covered.isPresent()) {
        run.run(population, covered, options.maxNewEdges(), log::info);
      } else {
        run.run(population, options.maxNewEdges(), log::info);
      }
  ```

  Add the imports `com.robsartin.segue.domain.Expanded` and
  `com.robsartin.segue.support.KnownListInput`. `promotions` inside the `else` must stay a separate
  local: the lambda that builds `RatedSince` captures it, and `population` is not effectively final.

  Re-run Step 1's command: green.

- [ ] **Step 3 — RED then GREEN is done; now the planted control.** Add, beside Step 1's test:

  ```java
  @Test
  @DisplayName("the same file over a log citing no seed considers every entity in it")
  void shouldConsiderEveryEntityWhenNoRowCitesAnyOfThemAsASeed() throws Exception {
    // The control for the test above: the same three ids, the same graph, and one row's reference
    // changed to ClaimMapper's fallback for a statement carrying no id — which names no seed. If
    // this reported 2 as well, the drop above would not be the rule firing.
    Path db = knownListGraph("nothing-expanded.db", "P737:" + NEVER_EXPANDED);
    Path file = knownFile("control.csv");
    captured.list.clear();

    ExpandCli.main(
        new String[] {"--db", db.toString(), "--dry-run", "--known", file.toString()});

    assertThat(countOn(lines(), "considered"))
        .as("no row cites a seed, so nothing is excluded as already expanded")
        .isEqualTo(3);
  }
  ```

  Run the suite: green. **This is the control, and the task report must say what it showed** — 2 with
  the statement-id reference, 3 with the fallback, one character of fixture apart.

- [ ] **Step 4 — the file id the graph holds no node for.** Add:

  ```java
  @Test
  @DisplayName("a known-list id the graph holds no node for is refused as an unknown entity")
  void shouldRefuseTheKnownEntityWhenTheGraphHoldsNoNodeForIt() throws Exception {
    // A REAL run, and it reaches no network by construction: EntityExpansion refuses an entity
    // with no node before any adapter is asked. The file names that one id and nothing else, so
    // there is nothing here that could be expanded.
    Path db = home.resolve("no-node.db");
    try (SqliteAssertionLog log = new SqliteAssertionLog(db)) {
      assertThat(log.readAll()).as("the log is deliberately empty").isEmpty();
    }
    Path file = Files.writeString(home.resolve("absentee.csv"), NO_NODE + "\n");
    captured.list.clear();

    ExpandCli.main(new String[] {"--db", db.toString(), "--known", file.toString()});

    assertThat(countOn(lines(), "considered")).isEqualTo(1);
    assertThat(countOn(lines(), "unknown entity"))
        .as("the same refusal a promotion with no node already gets, counted the same way")
        .isEqualTo(1);
  }
  ```

  Run the suite: green. If it hangs or logs an HTTP call, **stop and report** — something reached a
  network and the fixture is wrong.

- [ ] **Step 5 — the javadoc the new population makes untrue.** Four edits, no behaviour:

  - `Preflight`'s `@param considered` — after "which, when {@code --rated-since} was given, is the
    promoted population **after** the instant filtered it", add: "and which, when {@code --known} was
    given, is the file's entities after the never-expanded rule filtered them (#313)".
  - `ExpansionTally`'s `@param considered` — "every promotion the run was handed" becomes "every
    entity the run was handed — the promotions, or the known-list population when {@code --known} was
    given (#313)".
  - `ExpandRun`'s class javadoc, the "This class never filters" paragraph — `RatedSince` becomes
    `{@link Population}`, and add one sentence: "The population is composed at {@code ExpandCli},
    from {@code KnownList.promoted} and the merges or from the known-list file and {@code Expanded};
    a second place that knows how to drop an entity would be a second answer to the same question
    (#307, #313)."
  - `ExpandCli`'s class javadoc — add a paragraph after the `--rated-since` one:

    ```java
     * <p><b>It reads a known-list file, and only when {@code --known} asks.</b> The file is the one
     * {@code recommend}, {@code rate}, {@code evaluate} and {@code graphCensus} take, read through
     * {@code support.KnownListInput}, and the population is its entities — folded onto their
     * canonical side — that no row in the log cites as an expansion's seed. That rule is {@code
     * domain.Expanded}, which the census reads too, so the two tools cannot disagree about who has
     * been expanded (#311, #313). A run given a file composes no promotions and reads no rating at
     * all. The two flags are exclusive: they name different populations, and the block names one.
    ```

- [ ] **Step 6 — format, gate, commit.**

  ```
  ./gradlew spotlessApply
  SEGUE_REQUIRE_BROWSER=true SEGUE_REQUIRE_GRAPHVIZ=true ./gradlew check --rerun-tasks
  ```

  Re-read every javadoc touched in Step 5 after `spotlessApply` for one-line `{@code …}` spans. Then
  `git status`, stage by explicit path, commit:

  > Expand the known-list entities nothing has expanded (#313)

---

## Task 6 — The block stays safe to paste, and the guard is shown to fire

Files: `src/test/java/com/robsartin/segue/expand/ExpansionIsSafeToPasteTest.java`.

**Read first.** This class asserts that **no line this tool writes** carries anything qid-shaped,
with one exact-match carve-out for `com.robsartin.segue.expansion.EntityExpansion`. The clause
carries the first operator-supplied *text* the block has ever held, so this is the guard that matters
most in this issue — and whether a basename is qid-shaped is a property of the file the owner points
at, which is the sentence `CensusIsSafeToPasteTest` already carries for the census's own heading.

- [ ] **Step 1 — the fixture ids.** Add to the constants:

  ```java
  /** On the known-list file, with a node, and cited by a row as an expansion's seed. */
  private static final String EXPANDED_ALREADY = "Q0901305";
  ```

- [ ] **Step 2 — the flagged path.** Add:

  ```java
  @Test
  @DisplayName("a dry run over a known list reaches the block, and the clause carries no id")
  void shouldEmitCountsAndNothingElseWhenTheRunCoversAKnownList() throws Exception {
    Path db = home.resolve("known.db");
    Provenance sourced = new Provenance("invented", "invented:6", WHEN, 1.0);
    Provenance expansion = new Provenance("wikidata", EXPANDED_ALREADY + "$4f1a-invented", WHEN, 1.0);
    try (SqliteAssertionLog log = new SqliteAssertionLog(db);
        SqliteAffinityStore affinity = new SqliteAffinityStore(db)) {
      log.append(new NodeAssertion(RATED, NodeKind.GROUP, LABEL, sourced));
      log.append(new NodeAssertion(EXPANDED_ALREADY, NodeKind.GROUP, LABEL, sourced));
      log.append(
          new AssertionRecord(
              EXPANDED_ALREADY, RATED, "INFLUENCED_BY", null, null, expansion));
      affinity.put(new AffinityRecord(RATED, KnownList.PROMOTION_RATING, NOTE, WHEN));
    }
    // The basename reaches the clause, so it must not itself be qid-shaped — which is a property
    // of the file the owner points at, and this names the one the runbook tells him to use.
    Path known = Files.writeString(home.resolve("known.csv"), RATED + "\n" + EXPANDED_ALREADY + "\n");
    captured.list.clear();

    ExpandCli.main(
        new String[] {"--db", db.toString(), "--dry-run", "--known", known.toString()});

    assertEverySafe(ExpansionReport.DRY_RUN_HEADER);
    assertThat(lines())
        .as("the known clause really was printed, or the assertions above cover a block without it")
        .anyMatch(line -> line.contains("only known-list entities from known.csv"));
  }
  ```

  Add the imports `com.robsartin.segue.domain.AssertionRecord` and `java.nio.file.Files`. Run:

  ```
  ./gradlew test --tests '*ExpansionIsSafeToPasteTest'
  ```

  Expected green. **A green guard proves nothing until it has been seen to fail** — Step 3 is that.

- [ ] **Step 3 — the positive control: plant a qid-shaped basename.** Change the fixture's file name
  to one that is itself a qid, and nothing else:

  ```java
    Path known = Files.writeString(home.resolve("Q0901304.csv"), RATED + "\n" + EXPANDED_ALREADY + "\n");
  ```

  and the last assertion's substring to `"only known-list entities from Q0901304.csv"`. Re-run.
  **Expected failure**, from `assertEverySafe`:

  ```
  java.lang.AssertionError: [no line this tool writes carries anything qid-shaped, wherever it came from. ...]
  Expecting no elements of:
    [..., "# only known-list entities from Q0901304.csv that no expansion has covered: 1 excluded ...", ...]
  to match given predicate
  ```

  Quote the real text in the task report. This is what says the guard covers the clause and not only
  the rows.

- [ ] **Step 4 — remove the plant** and restore Step 2's `known.csv` fixture and substring. Re-run:
  green.

- [ ] **Step 5 — format, gate, commit.**

  ```
  ./gradlew spotlessApply
  SEGUE_REQUIRE_BROWSER=true SEGUE_REQUIRE_GRAPHVIZ=true ./gradlew check --rerun-tasks
  ```

  `git status` — confirm the plant is gone and the diff shows `known.csv` — stage by explicit path,
  commit:

  > Hold the known-list clause to ADR 51's line, and show the guard firing (#313)

---

## Task 7 — The runbook: the known-list variant

Files: `docs/developer-guide.md`,
`src/test/java/com/robsartin/segue/expand/DeveloperGuideExpandPromotionsExamplesTest.java`.

**Read first.** `docs/` is a declared input of `test`, so the document edit re-runs the suite; the
guide test is what makes this a red-then-green step rather than prose nobody checks. `steps()`
reduces each `./gradlew` line in the chapter to its task name plus the flags it carries, and
`containsExactly` pins the order.

- [ ] **Step 1 — RED: teach the test the variant, before the guide has it.** In
  `DeveloperGuideExpandPromotionsExamplesTest`, add the third flag to `steps()`:

  ```java
        if (example.arguments().contains("--known")) {
          command += " --known";
        }
  ```

  and extend the expected sequence and its `as(…)` clause:

  ```java
        .containsExactly(
            "graphCensus",
            "expandPromotions --dry-run",
            "expandPromotions",
            "graphCensus",
            "expandPromotions --dry-run --rated-since",
            "expandPromotions --rated-since",
            "graphCensus --known",
            "expandPromotions --dry-run --known",
            "expandPromotions --known");
  ```

  In the `as(…)` text, after the `--rated-since` sentence, add: "The three `--known` entries are the
  second variant, and the census with the same flag comes first because the `never expanded` count it
  prints is what says whether the run is worth making at all."

  Run:

  ```
  ./gradlew test --tests '*DeveloperGuideExpandPromotionsExamplesTest'
  ```

  **Expected failure:**

  ```
  java.lang.AssertionError: [docs/developer-guide.md, 'Expanding every promotion' — ...]
  Expecting actual:
    ["graphCensus", "expandPromotions --dry-run", "expandPromotions", "graphCensus",
     "expandPromotions --dry-run --rated-since", "expandPromotions --rated-since"]
  to contain exactly (and in same order):
    [..., "graphCensus --known", "expandPromotions --dry-run --known", "expandPromotions --known"]
  but could not find the following elements:
    ["graphCensus --known", "expandPromotions --dry-run --known", "expandPromotions --known"]
  ```

- [ ] **Step 2 — GREEN: write the section.** In `docs/developer-guide.md`, at the **end** of the
  `## Expanding every promotion` chapter — after the `--rated-since` section's closing paragraphs and
  immediately before `### What to file from what you saw` — insert (the four-backtick fence below is
  this plan's own; what goes in the guide is everything inside it, three-backtick blocks included):

  ````markdown
  ### Only what your own list says nothing has expanded: `--known`

  Every run above covers the **promotions**. Your known-list file holds entities that are not
  promotions and have never been expanded at all — known acts whose own neighbourhoods have never
  been fetched, so nothing routes through them. `--known <file>` covers exactly those: the file's
  entities, resolved through the merge fold, that no row in the log cites as an expansion's seed.
  `--known` and `--rated-since` name different populations and are refused together.

  **Step 0 applies unchanged**, and so does everything this chapter says about a single writer.

  Take the census first, with the same file — its `never expanded` row is what says whether this run
  is worth making at all, and it is the same rule this run selects by
  ([ADR 63](adr/0063-a-read-only-census-of-the-graph.md), #311):

  ```bash
  ./gradlew graphCensus --args="--db $HOME/.segue/segue.db --known $HOME/known.csv"
  ```

  Then the dry run:

  ```bash
  ./gradlew expandPromotions --args="--db $HOME/.segue/segue.db --dry-run --known $HOME/known.csv"
  ```

  The block now carries a second `#` line naming the file's **basename** — never its path — and how
  many of its entities the rule excluded as already expanded.

  **What `considered` means here.** It is the file's entities *after* that rule, so step 2's
  arithmetic (`considered` minus `in the graph` minus `minted`) still reads, over this population:
  an id your file names that the graph holds no node for is **refused as an unknown entity**, counted
  under `refused, by reason`, not silently dropped. `considered` plus the excluded count on the
  clause is the whole of your file as the fold sees it — two ids that turned out to be one entity
  counting once — which is how you check the file was read as you meant.

  The run:

  ```bash
  ./gradlew expandPromotions --args="--db $HOME/.segue/segue.db --known $HOME/known.csv"
  ```

  **A `--known` run reads no rating at all.** It composes no promotions, so the taste layer is never
  asked anything — the same shape as a run with no `--rated-since` reading no timestamp.

  **How to read step 5's table afterwards.** Unchanged, and against the census you took first: every
  `up` is still `up`. `bridge / entities MusicBrainz reached` is the row to watch hardest here — the
  reading on #311 says this population is people and groups, so the bridge should be asked once per
  entity.
  ````

  Re-run Step 1's command: green. Then run the census guide test too, because the chapter now shows a
  `graphCensus` line:

  ```
  ./gradlew test --tests '*DeveloperGuideCensusExamplesTest' --tests '*DocumentationLinksTest'
  ```

- [ ] **Step 3 — the `expand` row follows the code.** In the `### What each package is for` table,
  the `expand` row, append one sentence before the `--db` sentence:

  > Since #313 it takes an optional `--known <file>` instead, and covers the entities that file names
  > — folded onto their canonical side — that no row in the log cites as an expansion's seed, by
  > `domain.Expanded`, the rule `graphCensus --known` counts by; the two flags are exclusive.

  Leave the `Depends on` column alone: `domain` and `support` are already listed and no new package
  is reached.

- [ ] **Step 4 — format, gate, commit.**

  ```
  ./gradlew spotlessApply
  SEGUE_REQUIRE_BROWSER=true SEGUE_REQUIRE_GRAPHVIZ=true ./gradlew check --rerun-tasks
  ```

  `git status`, stage by explicit path, commit:

  > Add the known-list variant to the expansion runbook (#313)

---

## Task 8 — ADR 66's dated amendment

Files: `docs/adr/0066-expand-every-promotion-from-a-dev-tool.md`.

**Read first.** An ADR is immutable: nothing above is edited, the status keeps `Accepted`, and this
is appended **after** the 2026-09-11 amendment as the file's last section. **No commit hash, no
`.superpowers/` path, no qid, and no figure from the owner's graph** — the #311 reading is cited as
"the reading on #311". `AdrCitationsTest` reads a backticked run of 7–40 hex characters as a commit
citation, and an invented qid is exactly that shape.

- [ ] **Step 1 — append the amendment:**

  ```markdown
  **Amendment (2026-09-12, issue #313): the tool takes `--known`, and expands a second population —
  the known-list entities the log says were never expanded.**

  Nothing above is withdrawn, no decision above is edited, and this ADR keeps `Accepted`. What
  changes is that a run may now cover a population that is not the promotions at all, and the block
  says so when it did. `ExpandCli.USAGE` is the authority on the flags' current text.

  **The flag.** `./gradlew expandPromotions --args="--db <segue.db> --known <file>"`, optional, the
  same known-list file `recommend`, `rate`, `evaluate` and `graphCensus --known` already take, read
  by the same rule. The population is the file's entities, resolved through the merge fold, that no
  row in the log cites as an expansion's seed. Not given, the tool covers the promotions and behaves
  exactly as it always has.

  **Why it reuses the census's rule rather than its own.**
  [ADR 63](0063-a-read-only-census-of-the-graph.md)'s 2026-09-12 amendment said a second reader of
  `domain.Expanded` was expected — "the re-expansion pass this section's number exists to gate" —
  and this is that reader. One rule in `domain`, read by both, is what stops `graphCensus --known`
  reporting a coverage gap this tool then declines to close, or closing one the census never saw. Two
  steps of the census's composition moved into `domain` with it, because `expand` may not depend on
  `census`: the fold of the file's ids onto their canonical side and the fold of the log's seeds onto
  the same side. Both were private to the census and are now `Equivalences.canonical` in its list
  shape and `Expanded.onTheCanonicalSide`. The file reader moved the same way, from `census` into
  `support` beside `QidList`, which is where a file more than one dev tool reads already lives
  ([ADR 45](0045-recommend-by-normalised-lift-with-routes.md)).

  **The two flags are exclusive.** `--known` and `--rated-since` name different populations — one
  replaces the promotions, the other narrows them — so a run given both is refused with this tool's
  own usage message. The exclusivity is also a property of the types: which population a run covered
  is one sealed value, so a block cannot describe two.

  **What `considered` means under it.** The file's entities after the rule, the same field with a
  different population, so `considered == expanded + refused + failed` and the dry run's arithmetic
  both survive. The excluded count is on the clause and not on the tally, for the reason the
  2026-09-11 amendment gives for its own: it counts entities the run was **not** handed.

  **An id the graph holds no node for is refused, not dropped.** `EntityExpansion` reads the node
  first and refuses an unknown entity before any adapter is asked, and the block counts that under
  `refused, by reason` exactly as it already does for a promotion with no node. A known-list file
  naming something the graph has never seen is the first coverage gap there is, and it is reported
  rather than filtered away — the same choice this ADR's 2026-09-11 amendment made for a promotion
  with no timestamp.

  **The header form, and the one new thing in it.** One `#` clause under the block's own header, in
  both the dry-run block and the real block, naming the file's **basename** and how many entities the
  rule excluded as already expanded — a clause and not a row, for the reason the 2026-09-11
  amendment gives in full. **The block with no flag and the block with an instant are both
  byte-identical to today's**, and the golden pins that predate this work are unchanged beside the
  two new ones. The clause carries the first operator-supplied *text* this block has ever held: an
  instant cannot contain an identifier, and a file name can. What holds the line is that the text is
  the basename and never the path — `support.KnownListInput` is the one home of that rule, and it is
  the only reason that type exists — and that the paste guard now covers the flagged path, shown
  failing on a fixture whose basename was itself qid-shaped before it was shown passing.
  [ADR 51](0051-what-an-adr-may-quote.md)'s line is unchanged, and
  [ADR 63](0063-a-read-only-census-of-the-graph.md)'s 2026-09-12 amendment already took this step for
  the census's own section heading.

  **A `--known` run reads no rating at all.** It composes no promotions, so the taste layer's bulk
  read is never made — [ADR 16](0016-privacy-and-data-handling.md)'s minimisation falling out of the shape
  rather than being argued for, the same way a run with no instant reads no timestamp.

  **Alternatives rejected.**

  - **A separate dev tool for the known-list expansion.** Rejected: it would be this tool's whole
    body — the replay, the shared expansion, the tally, the block, the fences — with one population
    composed differently, and an eleventh tool's fences would be this tool's copied under a new name.
    That is the failure mode this repository has already measured once, when a rule duplicated under
    a second name drifted.
  - **A "never expanded" filter on the promotions population instead of a second population.**
    Rejected on the evidence: the reading on #311 says every promotion has already been expanded, so
    that filter would select nothing today, and the entities the owner wants reached are precisely
    the ones on his list that are *not* promotions.
  - **Composing the file with the promotions**, as the census's second sub-section does. Rejected:
    the promotions are already this tool's other population, so composing them in would make a
    `--known` run a superset of a no-flag run and the two flags no longer describe disjoint work.
  - **Copying `KnownListInput` and the two folds into `expand`** rather than moving them. Rejected
    for the reason the 2026-09-11 amendment rejected copying `RatingAge`: a second copy of one
    judgement is how two tools come to answer one question differently.

  **Nothing here is unit-testable on its own, and that is said out loud rather than left implied.**
  This entry records a decision whose code landed with its own tests — the move, the two extracted
  folds and their reds, the parser and the exclusivity refusal seen red, the population with a
  planted control one reference-character wide, the unknown-entity refusal, the clause and its two
  new pins beside two unchanged ones, and the paste guard shown firing on a planted qid-shaped
  basename. The verification of the *document* is the full gate over an otherwise unchanged tree:
  `AdrIndexTest`, `AdrCitationsTest`, `DocumentationLinksTest` for the relative links above, and
  `javadoc -Werror` inside `./gradlew check`.
  ```

- [ ] **Step 2 — check the links resolve before the gate does.**

  ```
  grep -n '](00' docs/adr/0066-expand-every-promotion-from-a-dev-tool.md | tail -12
  ls docs/adr/0016-*.md docs/adr/0045-*.md docs/adr/0051-*.md docs/adr/0063-*.md
  ```

  Every link target above must exist with exactly that filename. **If `docs/adr/0016-*.md` is not
  the data-minimisation ADR, correct the citation to whichever ADR is** — check with
  `grep -l 'minimisation' docs/adr/*.md` — rather than leaving a link that resolves to the wrong
  decision.

- [ ] **Step 3 — gate and commit.**

  ```
  SEGUE_REQUIRE_BROWSER=true SEGUE_REQUIRE_GRAPHVIZ=true ./gradlew check --rerun-tasks
  ```

  `git status`, stage by explicit path, commit:

  > Record the expander's second population in ADR 66 (#313)

---

## Done when

- `./gradlew expandPromotions --args="--db … --known …"` covers the file's never-expanded entities,
  and `--known` with `--rated-since` is refused.
- The no-flag block and the since-form block are byte-identical to what they were, proved by pins
  that were not edited.
- `graphCensus --known` and `expandPromotions --known` select by **one** rule in `domain`.
- Every red in this plan was a real assertion failure, quoted in its task report; every control was
  planted, seen to fire, and removed.
- The full gate is green:
  `SEGUE_REQUIRE_BROWSER=true SEGUE_REQUIRE_GRAPHVIZ=true ./gradlew check --rerun-tasks`.
