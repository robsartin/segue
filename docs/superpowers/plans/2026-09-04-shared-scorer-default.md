# One default scorer, read by both tools — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to
> implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** the rating deck and the recommender stop holding two copies of the same default scorer.
One constant in `domain`, read by both by reference, with the usage message's English word derived
from it — and a behavioural guard that fails if either tool goes back to a literal.

**Architecture:** three tasks, each green on its own. Task 1 adds the guard and proves it can fail,
changing no production code. Task 2 adds the constant and makes the usage message derive its word
from it. Task 3 points both tools at the constant, moves both pins onto it, proves the guard fires
from each side in turn, and adds the guide's one clause.

**Tech Stack:** Java (toolchain 25, `release 21`), JUnit 5, AssertJ, ArchUnit, JaCoCo, Spotless.

**Spec:** `docs/superpowers/specs/2026-09-04-shared-scorer-default-design.md` — it holds the survey
of the copies, the argument for the plant, and the alternatives rejected. **Cite it; never restate
its reasoning.** Where this plan and the spec appear to differ, the spec wins and the divergence is a
finding to report.

---

## Global Constraints

- **No default moves.** `Scorer.LIFT` is the default before this issue and after it. The only
  permitted change to a scorer value anywhere is a *temporary plant* that a later step in the same
  task removes. **A task never ends with a plant in the tree.**
- **No ADR and no amendment.** Nothing decided changes (issue #244 says so). `docs/adr/` is not
  touched.
- **The red comes from a plant, deliberately, and the spec says why.** Two copies that agree cannot
  make a test fail. In Task 1 and Task 2 the first run of a new test is expected to be GREEN; that
  observation is worth nothing on its own and the task says so out loud. The evidence is the run
  *after* the plant: **a real assertion failure, quoted in the report, naming the two values.** A
  compile error is not a red — if a planted run fails to compile, fix the compile error and re-run
  before claiming anything.
- **Every plant is removed and the removal is verified by a re-run.** Plant → observe the failure →
  remove → observe green. Never plant two things at once.
- **Never run `rate` or `recommend`**, or any writing dev task (`own`, `ownClaim`,
  `retractEntity`). **Never read, write, copy or create `~/.segue/segue.db`**, and never point
  anything at `$HOME/.segue`. Every graph in this plan is a `TinkerGraphStore` built in a test.
- **Invented identifiers only** (ADR 58, ADR 51). The new qids are in the `Q09004xx` range, which
  nothing else in `RateRunTest` uses. No real entity, no real rating.
- Test names `should<Expected>When<Condition>` with `@DisplayName`.
- Mikado: green at every committed step.
- YAGNI: no accessor, parameter or helper beyond what a step's test needs. In particular **do not**
  add a `--scorer` flag to `rate`, a scorer parameter to `RateRun.buildDeck`, or an accessor
  exposing the deck's scorer.
- **Never `git add -A`.** Stage every file by explicit path, with git's stderr visible (never
  `2>/dev/null`), and read `git status` before committing. One committer in this worktree.
- Commit messages end with a blank line then
  `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`.
- **Never cite a `.superpowers/` path from a committed file.**
- Gate, run **BLOCKING** (never backgrounded), before every commit:
  `SEGUE_REQUIRE_BROWSER=true SEGUE_REQUIRE_GRAPHVIZ=true ./gradlew check --rerun-tasks`
  Plain `./gradlew`; JDK 25 is the only JDK. If `spotlessCheck` fails, run `./gradlew spotlessApply`
  and re-run the gate. `check` runs `javadoc` with `-Werror`, so a broken `{@link}` fails the build.
- For the fast inner loop, a single class is
  `./gradlew test --tests 'com.robsartin.segue.rate.RateRunTest' --rerun-tasks` (likewise
  `...recommend.RecommendCliTest`). The full gate still runs before each commit.

---

### Task 1: a guard that reads what the deck's sweep actually did

**No production code changes in this task** beyond a plant that step 4 removes.

- [ ] **Read the two ends first.** `src/main/java/com/robsartin/segue/rate/RateRun.java` (the
      `CandidateSweep ... .over(...)` call in `buildDeck`, and its `Scorer.LIFT` argument) and
      `src/test/java/com/robsartin/segue/rate/RateRunTest.java` (the `ratingsMoveTheCandidates`
      test and the `twoCandidates` / `reaches` / `node` / `padDegreeTo` helpers this task reuses).
- [ ] **Add the fixture constants** to `RateRunTest`, beside the existing `BELOVED` / `CROWDED`:

```java
  /** A candidate three of yours reach, sitting at the floor — small enough for lift to like it. */
  private static final String OBSCURE = "Q0900401";

  /** A candidate six of yours reach, big enough that dividing by its own degree buries it. */
  private static final String FAMOUS = "Q0900402";

  /** Twelve times the floor: far enough apart that lift and counting cannot agree. */
  private static final int FAMOUS_DEGREE = 60;
```

- [ ] **Add the fixture and the sweep helper** at the bottom of `RateRunTest`, beside
      `twoCandidates`:

```java
  /**
   * Two ancestors the scorers rank in opposite orders. One is reached by three of yours and carries
   * the floor's worth of edges; the other is reached by six and carries twelve times as many.
   * Counting, Adamic-Adar and resource allocation all prefer the crowded one; lift, which divides
   * by the candidate's own degree, is alone in preferring the other — so this graph does not merely
   * separate lift from counting, it separates lift from every other point on the dial.
   */
  private static void oneObscureAndOneFamous(TinkerGraphStore graph) {
    node(graph, OBSCURE, NodeKind.GROUP, "the obscure ancestor");
    node(graph, FAMOUS, NodeKind.GROUP, "the famous ancestor");
    int intermediate = 0;
    for (String seed : LOVED) {
      reaches(graph, seed, "Q09004" + (10 + intermediate++), OBSCURE);
    }
    for (String seed : LUKEWARM) {
      reaches(graph, seed, "Q09004" + (10 + intermediate++), FAMOUS);
    }
    padDegreeTo(graph, OBSCURE, MIN_CANDIDATE_DEGREE);
    padDegreeTo(graph, FAMOUS, FAMOUS_DEGREE);
  }

  /**
   * What the recommender's own sweep ranks first under one scorer, run here exactly as {@code
   * RateRun} runs it — same sweep class, same institution filter, same floor, same regard — so the
   * scorer is the only thing that differs between the two sides of the assertion.
   */
  private static String topCandidate(TinkerGraphStore graph, List<String> known, Scorer scorer) {
    Sweep sweep =
        new CandidateSweep(graph, RecognitionInstitutions::isRecognitionInstitution)
            .over(
                known,
                KnownList.notOffered(Map.of(), Equivalences.NONE),
                scorer,
                MIN_CANDIDATE_DEGREE,
                Recommendations.regardFor(Map.of()));
    return Recommendations.rank(sweep.candidates(), 1).get(0).entity().qid();
  }
```

- [ ] **Add the guard itself**, after `ratingsMoveTheCandidates`:

```java
  @Test
  @DisplayName("the deck deals the candidate the recommender's default scorer ranks first")
  void shouldDealTheRecommendersTopCandidateWhenTheScorersDisagree() throws Exception {
    // The deck's sweep held its own copy of the recommender's default scorer (issue #244): a
    // literal here, a literal in RecommendCli.parse, and nothing pairing them. Issue #242 came
    // within one clause of moving that default — and had it moved, the deck would have gone on
    // dealing lift candidates while `recommend` ranked with something else, with the whole gate
    // green. This is the check that would not have been.
    try (TinkerGraphStore graph = new TinkerGraphStore()) {
      oneObscureAndOneFamous(graph);
      List<String> everything = new ArrayList<>(LOVED);
      everything.addAll(LUKEWARM);

      // The fixture has to be able to tell the scorers apart, or every assertion below is
      // vacuously true: counting prefers the candidate more of yours reach, lift prefers the one
      // its own degree does not bury. Asserted, not assumed, so a later fixture change that made
      // the two agree fails here instead of reporting clean forever.
      String byCounting = topCandidate(graph, everything, Scorer.RAW);
      String byTheDefault = topCandidate(graph, everything, Scorer.LIFT);
      assertThat(byTheDefault).isNotEqualTo(byCounting);

      List<Card> deck =
          RateRun.buildDeck(
              graph,
              everything,
              Map.of(),
              Equivalences.NONE,
              1,
              MIN_CANDIDATE_DEGREE,
              OptionalInt.empty(),
              note -> {});

      assertThat(deck).extracting(Card::qid).contains(byTheDefault).doesNotContain(byCounting);
    }
  }
```

- [ ] **Add the imports** `RateRunTest` now needs, in the file's existing order:
      `com.robsartin.segue.domain.Recommendations`, `com.robsartin.segue.domain.Scorer`,
      `com.robsartin.segue.recommend.CandidateSweep`, `com.robsartin.segue.recommend.Sweep`,
      `com.robsartin.segue.wikidata.RecognitionInstitutions`, `java.util.Set` **only if** a step
      above ended up needing it (the helper as written does not — `KnownList.notOffered` returns the
      set). `Equivalences`, `KnownList`, `List`, `Map` and `OptionalInt` are already imported.
- [ ] **Run it and record that it is GREEN.**
      `./gradlew test --tests 'com.robsartin.segue.rate.RateRunTest' --rerun-tasks`.
      **This green is not evidence of anything** — both copies say `LIFT`, so the guard passes
      whether or not it is capable of failing. Say exactly that in the report, then do the next step.
      (If it *fails* here, stop: either the fixture does not discriminate — the `isNotEqualTo` line
      fires — or the sweep found no candidate at all and `.get(0)` threw. Both mean the fixture is
      wrong, not the deck.)
- [ ] **RED — plant the divergence.** In `RateRun.buildDeck`, change the sweep's scorer argument
      from `Scorer.LIFT` to `Scorer.RAW`, nothing else. Re-run the same command **BLOCKING**.
      Observe a real assertion failure and **quote it in the report** — it will name the deck's
      dealt qids and complain that they do not contain `Q0900401` (or that they contain
      `Q0900402`). This run is both the red and the positive control: it is the proof the guard can
      fail for the reason it exists.
- [ ] **Remove the plant.** Restore `Scorer.LIFT` in `RateRun.buildDeck`. Re-run and observe green.
      Confirm with `git diff --stat src/main/java/com/robsartin/segue/rate/RateRun.java` that it
      reports **no changes** to that file.
- [ ] **Gate, BLOCKING:**
      `SEGUE_REQUIRE_BROWSER=true SEGUE_REQUIRE_GRAPHVIZ=true ./gradlew check --rerun-tasks`
- [ ] **Commit** — `git status` first, then stage by explicit path:
      `git add src/test/java/com/robsartin/segue/rate/RateRunTest.java`
      Message: `Pin the deck's candidates to the recommender's default scorer (#244)`, a body saying
      the two copies agreed so the red was produced by planting `Scorer.RAW` in the deck's sweep,
      and the trailer.

---

### Task 2: one constant, and a usage word that derives from it

- [ ] **Read** `src/main/java/com/robsartin/segue/domain/Recommendations.java` (the
      `MIN_CANDIDATE_DEGREE` javadoc — the constant this one sits beside, and the model for its
      tone: it cites ADR 45's amendment as the authority for its figures and restates none of them)
      and `src/main/java/com/robsartin/segue/recommend/RecommendCli.java` (`USAGE`, `parse`).
- [ ] **Add the constant** to `Recommendations`, immediately above `MIN_CANDIDATE_DEGREE`:

```java
  /**
   * Where on {@link Scorer}'s spectrum both tools start.
   *
   * <p><b>Measured, not chosen.</b> ADR 45 is the authority for why this point and not another, and
   * for what the ranked lists looked like at the others; nothing here restates it. {@link
   * Scorer}'s own javadoc holds the failure mode at each end of the dial.
   *
   * <p><b>One copy, because two tools apply it.</b> {@code RecommendCli} defaults {@code --scorer}
   * to this and {@code RateRun} sweeps with it, so the rating deck's candidate cards are the
   * candidates {@code ./gradlew recommend} would rank for the same known-list. Each held its own
   * literal until issue #244 — and issue #242 came within one clause of moving one of them, which
   * would have left the deck dealing one tool's answer while the report gave another's, with
   * nothing failing. This is the floor's rule (see {@link #MIN_CANDIDATE_DEGREE}) applied to the
   * other dial: by reference, never by a second copy.
   *
   * <p><b>It is a default and not a verdict</b>, which is why {@code --scorer} exists on
   * {@code recommend}: the honest way to disagree with it is to run two scorers and read the two
   * lists. The deck has no such flag on purpose — a deck that could deal something other than the
   * recommender's answer is the divergence this constant exists to prevent.
   */
  public static final Scorer DEFAULT_SCORER = Scorer.LIFT;
```

- [ ] **Compile and run both touched classes** — nothing reads the constant yet, so this must be
      green: `./gradlew test --tests 'com.robsartin.segue.recommend.RecommendCliTest' --tests
      'com.robsartin.segue.rate.RateRunTest' --rerun-tasks`.
- [ ] **Write the usage test** in `RecommendCliTest`, after `anUnknownScorerIsRefused`:

```java
  @Test
  @DisplayName("the usage message spells the default scorer from the constant, not a second word")
  void shouldSpellTheDefaultScorerFromTheConstantWhenItRefusesAnything() {
    // The word in the usage string was a third copy of the default (issue #244): the enum could
    // move and the sentence offered to the operator would go on saying the old word.
    assertThatThrownBy(() -> parse("--out", "/tmp/out.txt"))
        .hasMessageContaining("default " + Recommendations.DEFAULT_SCORER.spelling());
  }
```

- [ ] **Run it and record that it is GREEN** (`--tests
      'com.robsartin.segue.recommend.RecommendCliTest' --rerun-tasks`). As in Task 1, **this proves
      nothing**: the constant and the hard-coded word both say `lift`.
- [ ] **RED — plant the divergence in the constant.** In `Recommendations`, change
      `DEFAULT_SCORER = Scorer.LIFT` to `DEFAULT_SCORER = Scorer.RAW`. Re-run the same command
      **BLOCKING**. Observe a real assertion failure on the new test and **quote it** — it expects
      the message to contain `default raw` while `USAGE` still says `default lift`.
      (`RateRunTest` is unaffected at this point: nothing reads the constant yet.)
- [ ] **GREEN — with the plant still in place**, make `USAGE` derive the word. In `RecommendCli`,
      replace the two lines

```java
          + Scorer.names()
          + ">, default lift]"
```

      with

```java
          + Scorer.names()
          + ">, default "
          + Recommendations.DEFAULT_SCORER.spelling()
          + "]"
```

      Re-run and observe the test **pass while the plant is still there** — that is what says the
      word now comes from the constant rather than from a coincidence.
- [ ] **Remove the plant.** Restore `DEFAULT_SCORER = Scorer.LIFT`. Re-run and observe green, and
      check the usage message still reads `default lift` (the existing `--known`-is-required test
      exercises the same string).
- [ ] **Gate, BLOCKING.**
- [ ] **Commit** — `git status`, then stage by explicit path:
      `git add src/main/java/com/robsartin/segue/domain/Recommendations.java
      src/main/java/com/robsartin/segue/recommend/RecommendCli.java
      src/test/java/com/robsartin/segue/recommend/RecommendCliTest.java`
      Message: `Name the recommender's default scorer once (#244)`, body noting the red was produced
      by planting `Scorer.RAW` in the new constant and that the fix was verified *under* the plant,
      plus the trailer.

---

### Task 3: both tools read it, and the guard fires from either side

- [ ] **Point `RateRun` at the constant.** In `buildDeck`'s `.over(...)` call, replace
      `Scorer.LIFT,` with `Recommendations.DEFAULT_SCORER,`. `Recommendations` is already imported;
      **`Scorer` now has no other use in the file, so delete
      `import com.robsartin.segue.domain.Scorer;`** (an unused import fails the gate).
- [ ] **Say why in `RateRun`'s class javadoc**, as a new paragraph after the "Candidates come from
      the recommender's own sweep" one:

```java
 * <p><b>Scored with the recommender's own default, by reference.</b> The sweep's scorer is {@code
 * Recommendations.DEFAULT_SCORER} — the same constant {@code RecommendCli} defaults {@code
 * --scorer} to — rather than a second copy of the enum here, which is the rule the floor below
 * already follows (issue #244). Move the constant and both tools move together; that is what keeps
 * a card in this deck the card {@code ./gradlew recommend} would have printed.
```

- [ ] **Move the deck guard's expectation onto the constant.** In `RateRunTest`, add the static
      import `import static com.robsartin.segue.domain.Recommendations.DEFAULT_SCORER;` beside the
      existing `MIN_CANDIDATE_DEGREE` one, and change the guard's line

```java
      String byTheDefault = topCandidate(graph, everything, Scorer.LIFT);
```

      to

```java
      String byTheDefault = topCandidate(graph, everything, DEFAULT_SCORER);
```

      `Scorer` stays imported — `Scorer.RAW` is still the counting side of the comparison.
- [ ] **Move `RecommendCli.parse`'s default onto the constant**: `Scorer scorer =
      Recommendations.DEFAULT_SCORER;`.
- [ ] **Move `RecommendCliTest`'s pin onto the constant**, in `theTwoPathsAreAllItNeeds`:
      `assertThat(options.scorer()).isEqualTo(Recommendations.DEFAULT_SCORER);` — the same shape the
      line below it already uses for `MIN_CANDIDATE_DEGREE`. `Scorer` stays imported for
      `theScorerIsADialTheCommandLineTurns`.
- [ ] **Run both classes and observe green:** `./gradlew test --tests
      'com.robsartin.segue.rate.RateRunTest' --tests 'com.robsartin.segue.recommend.RecommendCliTest'
      --rerun-tasks`.
- [ ] **Positive control A — the deck side.** In `RateRun.buildDeck`, replace
      `Recommendations.DEFAULT_SCORER,` with `Scorer.RAW,` (re-adding the import), which is exactly
      the regression this issue removes. Run `RateRunTest` **BLOCKING**, observe the assertion
      failure, **quote it**, then restore the constant and delete the import again. Confirm
      `git diff` on `RateRun.java` shows only the intended change.
- [ ] **Positive control B — the recommender side.** In `RecommendCli.parse`, replace
      `Recommendations.DEFAULT_SCORER` with `Scorer.RAW`. Run `RecommendCliTest` **BLOCKING**,
      observe `theTwoPathsAreAllItNeeds` fail (expected `LIFT`, got `RAW`), **quote it**, then
      restore. Confirm `git diff` on `RecommendCli.java` shows only the intended change.
      **Note in the report that the usage test does *not* fire here** — the usage word is derived
      from the constant and the constant did not move, which is correct and is what Task 2's own
      plant already proved.
- [ ] **The developer guide's one clause.** In `docs/developer-guide.md`, in the `rate` section's
      sentence beginning "`RateCli`'s `--min-degree` defaults to the same
      `Recommendations.MIN_CANDIDATE_DEGREE` `recommend`'s does", extend the parenthetical so it
      covers both dials — the deck's sweep also scores with `Recommendations.DEFAULT_SCORER`, the
      constant `recommend` defaults `--scorer` to, by reference and not by a second copy of the enum
      (issue #244). **One sentence's worth. Do not add a table, do not restate ADR 45, and do not
      name the scorer's value anywhere the constant is not cited** — the enum is the authority.
      This edit has no unit-testable behaviour; it is verified by the gate, in which
      `DeveloperGuideEnumerationsTest` re-parses the guide's shapes (this sentence is prose in no
      enumeration, so the gate's job here is to prove the edit broke none of them).
- [ ] **Confirm the last copy is gone:** `grep -rn 'Scorer\.LIFT' src/main/java` must return
      **exactly one line** — the `DEFAULT_SCORER` declaration in
      `domain/Recommendations.java`, which is the one place the value is allowed to be written — and
      `grep -rn 'default lift' src/` must return nothing. Any other hit is a copy this plan missed:
      report it rather than deleting it silently. (A grep is narrower than the claim it seems to
      make: it will not see a copy written as `Scorer.valueOf("LIFT")` or reached through
      `Scorer.parse("lift")`, so also read the `--scorer` case in `RecommendCli.parse` and the
      `.over(...)` call in `RateRun.buildDeck` with your eyes.)
- [ ] **Gate, BLOCKING:**
      `SEGUE_REQUIRE_BROWSER=true SEGUE_REQUIRE_GRAPHVIZ=true ./gradlew check --rerun-tasks`
- [ ] **Commit** — `git status`, then stage by explicit path:
      `git add src/main/java/com/robsartin/segue/rate/RateRun.java
      src/main/java/com/robsartin/segue/recommend/RecommendCli.java
      src/test/java/com/robsartin/segue/rate/RateRunTest.java
      src/test/java/com/robsartin/segue/recommend/RecommendCliTest.java
      docs/developer-guide.md`
      Message: `Read one default scorer from both tools (#244)`, a body naming the two positive
      controls and what each failure said, plus the trailer.

---

## Done when

- `Scorer.LIFT` is written exactly once in `src/main/java` — in `Recommendations.DEFAULT_SCORER` —
  and no string anywhere spells the default in English.
- `RateRunTest`'s guard and `RecommendCliTest`'s pin both read `Recommendations.DEFAULT_SCORER`, and
  each has been seen to fail with its own side planted.
- The gate is green with `--rerun-tasks`, the tree has no plant left in it, and `git status` is
  clean apart from what was committed.
- The default is still `lift`.
