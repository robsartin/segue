# The scorer default on the expanded graph — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to
> implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** move `Recommendations.DEFAULT_SCORER` to `Scorer.RESOURCE_ALLOCATION` with the guard that
pins the deck to the recommender's default rebuilt so it can still tell the shipped default apart
from every other scorer, with every sentence in the tree that named the old value following the
constant, and with a dated amendment to ADR 45 recording the move as a decision taken outside the
rule of issue #245.

**Architecture:** almost none. Issue #244 left one constant with three consumers that all read it by
reference, so the code change is one line plus the javadoc that explains it. The work is in the test
fixture — the shipped one cannot discriminate the new default, structurally — and in the prose.

**Tech Stack:** Java (toolchain 25, `release 21`), JUnit 5, AssertJ, ArchUnit, JaCoCo. Markdown for
the amendment and the developer guide.

**Spec:** `docs/superpowers/specs/2026-09-07-scorer-default-on-the-expanded-graph-design.md` — it
holds the four findings about the code, the fixture arithmetic, the ordering argument and the
amendment text. **Cite it; do not restate its reasoning.** Where this plan and the spec appear to
differ, the spec wins and the divergence is a finding to report.

---

## Global Constraints

- **The decision is settled and this plan does not re-open it.** `DEFAULT_SCORER` becomes
  `Scorer.RESOURCE_ALLOCATION`; `Recommendations.MIN_CANDIDATE_DEGREE` does **not** move; no other
  constant moves; `Setting.GRID`, `HeldOut.EVERY` and the rule spec of issue #245 are not touched.
- **Pure TDD.** Failing test first, **run it and observe a real assertion failure** — a compile error
  is not a red. Where a step has no unit-testable behaviour, say so **out loud** and name the other
  explicit method that verifies it (Task 2 does exactly that).
- **Every guard gets a planted positive control**: plant the defect, watch the check fire, remove the
  plant, confirm the tree is clean again. Task 1 plants two.
- Test names `should<Expected>When<Condition>` with `@DisplayName`. The one test this plan edits
  already has both; do not rename it.
- **Mikado: green at every committed step.** Task 1's fixture change and constant move are one
  commit, and the spec's §5 is the argument for why they cannot be two.
- **NEVER** run `./gradlew own`, `ownClaim`, `retractEntity`, `rate`, `recommend`, `evaluate`,
  `expandPromotions` or any other writing or fetching dev task. **NEVER** read, write, copy or create
  `~/.segue/segue.db`. Everything below is unit tests and text.
- **ADRs are append-only.** The amendment is appended to the end of the file. Front matter
  (`status`, `date`, `topic`, `tags`, `supersedes`, `related`) is **not** touched, the title is not
  touched, the Decision section is not touched, and no line above the amendment is edited, reworded
  or deleted. `docs/adr/README.md` is **not** touched — no ADR's number, title or status changes,
  which is all `AdrIndexTest` compares. ADR 50 is **not** touched.
- **No commit hash anywhere in `docs/adr/`.** `AdrCitationsTest` reds on any new backticked
  seven-to-forty-character hex run there. Cite pull requests and issue numbers instead.
- **Never cite a `.superpowers/` path from a committed file.**
- **Invented identifiers only** in anything committed (ADR 58, ADR 51). No real entity, no real
  rating, no figure taken from the owner's data beyond what the eighth reading's amendment already
  carries — and nothing from that table is restated.
- **No wall-clock assertions and no timing-sensitive test.** The machine is loaded.
- **Never `git add -A`.** Stage every file by explicit path, with git's stderr visible (never
  `2>/dev/null`), and read `git status` before committing. One committer in this worktree.
- Commit messages end with a blank line then
  `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`.
- Gate, run **BLOCKING** (never backgrounded), after every task that changes a file:
  `SEGUE_REQUIRE_BROWSER=true SEGUE_REQUIRE_GRAPHVIZ=true ./gradlew check --rerun-tasks`
  Plain `./gradlew`; JDK 25 is the only JDK. If `spotlessCheck` fails run `./gradlew spotlessApply`
  and re-run the gate. `check` includes `javadoc` with `-Werror`, so a broken `{@link}` fails it.

---

### Task 1: the fixture, the constant, and every sentence that named the old value

One commit. The spec's §5 is the authority for why the fixture and the constant cannot be split into
two green commits: the guard is red with the old fixture and the new constant, and red with the new
fixture and the old constant.

#### Step 1 — RED: rebuild the fixture so it discriminates the new default, and generalise the guard

- [ ] In `src/test/java/com/robsartin/segue/rate/RateRunTest.java`, add the import (Spotless orders
      imports; put it with the other `java.util` ones):

```java
import java.util.Arrays;
```

- [ ] Replace the three constants

```java
  /** A candidate three of yours reach, sitting at the floor — small enough for lift to like it. */
  private static final String OBSCURE = "Q0900401";

  /** A candidate six of yours reach, big enough that dividing by its own degree buries it. */
  private static final String FAMOUS = "Q0900402";

  /** Twelve times the floor: far enough apart that lift and counting cannot agree. */
  private static final int FAMOUS_DEGREE = 60;
```

  with

```java
  /** A candidate three of yours reach, through intermediates nothing much else touches. */
  private static final String QUIETLY_REACHED = "Q0900401";

  /** A candidate six of yours reach, through intermediates more than twice as busy. */
  private static final String BUSILY_REACHED = "Q0900402";

  /** The degree every intermediate that reaches {@link #QUIETLY_REACHED} is padded to. */
  private static final int QUIET_VIA_DEGREE = 3;

  /** The degree every intermediate that reaches {@link #BUSILY_REACHED} is padded to. */
  private static final int BUSY_VIA_DEGREE = 7;

  /**
   * Both candidates, deliberately equal. Lift then divides both sides by the same number, so it
   * follows Adamic-Adar here instead of producing a third answer.
   */
  private static final int BOTH_CANDIDATES_DEGREE = 12;
```

- [ ] Replace the fixture

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
```

  with

```java
  /**
   * Two ancestors that only resource allocation ranks the way it does. One is reached by three of
   * yours through intermediates at {@link #QUIET_VIA_DEGREE}; the other by six, through
   * intermediates at {@link #BUSY_VIA_DEGREE}. Both candidates carry the same degree.
   *
   * <p><b>Varying the INTERMEDIATE's degree is the whole mechanism, and the fixture this replaced
   * could not do it.</b> The three scorers that do not divide by the candidate's own degree differ
   * only in how they discount the intermediate's ({@code Scorer.score}), so a graph whose
   * intermediates are all one degree ranks identically under all three and can never tell them
   * apart. Here the seed counts are three against six and the intermediate degrees three against
   * seven, so the count ratio sits strictly between {@code ln 7 / ln 3} and {@code 7 / 3}: counting,
   * Adamic-Adar and lift all prefer the busily reached candidate, and resource allocation is alone
   * in preferring the other. Issue #291's design spec holds the arithmetic and the margins.
   *
   * <p>Padding is left to the shared {@code padDegreeTo} and its shared filler range on purpose. A
   * filler is a {@code WORK}, so {@code CandidateSweep.couldBeExplored} never offers one, and it is
   * never one hop from a seed, so it is never an intermediate either — two nodes padding through the
   * same filler changes no score. A busy intermediate is not at risk of being dropped as a hub
   * either: {@code PathRanking.isHub} answers yes to a busy {@code CONCEPT} or a recognition
   * institution, and {@link #reaches} mints intermediates as {@code PERSON}.
   */
  private static void oneQuietlyReachedAndOneBusilyReached(TinkerGraphStore graph) {
    node(graph, QUIETLY_REACHED, NodeKind.GROUP, "the quietly reached ancestor");
    node(graph, BUSILY_REACHED, NodeKind.GROUP, "the busily reached ancestor");
    int intermediate = 0;
    for (String seed : LOVED) {
      String via = "Q09004" + (10 + intermediate++);
      reaches(graph, seed, via, QUIETLY_REACHED);
      padDegreeTo(graph, via, QUIET_VIA_DEGREE);
    }
    for (String seed : LUKEWARM) {
      String via = "Q09004" + (10 + intermediate++);
      reaches(graph, seed, via, BUSILY_REACHED);
      padDegreeTo(graph, via, BUSY_VIA_DEGREE);
    }
    padDegreeTo(graph, QUIETLY_REACHED, BOTH_CANDIDATES_DEGREE);
    padDegreeTo(graph, BUSILY_REACHED, BOTH_CANDIDATES_DEGREE);
  }
```

- [ ] In `shouldDealTheRecommendersTopCandidateWhenTheScorersDisagree`, leave the `@Test`,
      `@DisplayName` and the issue-#244 comment block exactly as they are, and replace the body from
      `oneObscureAndOneFamous(graph);` to the final assertion with:

```java
      oneQuietlyReachedAndOneBusilyReached(graph);
      List<String> everything = new ArrayList<>(LOVED);
      everything.addAll(LUKEWARM);

      // The fixture has to be able to tell the default apart from every other point on the dial, or
      // every assertion below is vacuously true. Asserted against all of them rather than against
      // raw counting alone (issue #291): the fixture this replaced claimed in its javadoc to
      // separate the default from every scorer and compared it with one, so the claim could not
      // fail. A scorer added to the enum now joins the comparison without anybody remembering to.
      String byTheDefault = topCandidate(graph, everything, DEFAULT_SCORER);
      List<String> byEveryOtherScorer =
          Arrays.stream(Scorer.values())
              .filter(scorer -> scorer != DEFAULT_SCORER)
              .map(scorer -> topCandidate(graph, everything, scorer))
              .toList();
      assertThat(byTheDefault)
          .as(
              "this fixture must separate %s from every other scorer; if the default has moved,"
                  + " rebuild oneQuietlyReachedAndOneBusilyReached so it discriminates the new one",
              DEFAULT_SCORER)
          .isNotIn(byEveryOtherScorer);

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

      assertThat(deck)
          .extracting(Card::qid)
          .contains(byTheDefault)
          .doesNotContainAnyElementsOf(byEveryOtherScorer);
```

- [ ] Run, blocking, and **quote the failure verbatim in the step report**:

```bash
./gradlew test --tests '*RateRunTest*'
```

  It must be an **AssertJ assertion failure** on the `isNotIn` line, carrying the `as(…)` description
  naming `LIFT` and reporting that the actual value is in the list — the shipped default and raw
  counting now agree on this fixture, which is the point. **If it is a compile error, that is not a
  red**: fix the compile error and run again until a real assertion fires. Report which other tests
  in the class ran and that they passed.

#### Step 2 — GREEN: move the one constant

- [ ] In `src/main/java/com/robsartin/segue/domain/Recommendations.java`, change

```java
  public static final Scorer DEFAULT_SCORER = Scorer.LIFT;
```

  to

```java
  public static final Scorer DEFAULT_SCORER = Scorer.RESOURCE_ALLOCATION;
```

- [ ] In the same javadoc, replace the first paragraph

```java
   * <p><b>Measured, not chosen.</b> ADR 45 is the authority for why this point and not another, and
   * for what the ranked lists looked like at the others; nothing here restates it. {@link Scorer}'s
   * own javadoc holds the failure mode at each end of the dial.
```

  with

```java
   * <p><b>Measured, then decided against the measurement's own rule.</b> ADR 45 is the authority for
   * why a point on this dial rather than a different kind of score, and for what the ranked lists
   * looked like at the others; its amendment of 2026-09-07 (issue #291) is the authority for why
   * this point rather than the one this ADR shipped with, for the four alternatives rejected, and
   * for what the move costs. Nothing here restates either. {@link Scorer}'s own javadoc holds the
   * failure mode at each end of the dial.
```

  Leave the "one copy, because two tools apply it" and "it is a default and not a verdict"
  paragraphs untouched: both are still true and both are the reason this move is one line.

- [ ] Run, blocking:

```bash
./gradlew test --tests '*RateRunTest*' --tests '*RecommendCliTest*' --tests '*RecommendRunTest*' --tests '*CandidateSweepTest*'
```

  All green. **Say out loud in the step report** that `RecommendCli`'s `--scorer` default, its usage
  sentence and `RateRun`'s sweep were not edited and followed the constant by reference (issue #244)
  — that is the property this move is the first to spend, and it is stated rather than assumed. Name
  the tests that proved it: `RecommendCliTest.theTwoPathsAreAllItNeeds` and
  `shouldSpellTheDefaultScorerFromTheConstantWhenItRefusesAnything`.

- [ ] Run the whole suite once, blocking, and report anything else that reds:

```bash
./gradlew test
```

  The spec predicts nothing else reds. **If something does, stop and report it as a finding before
  changing it** — a test that was silently reading the shipped default is exactly what this issue
  wants found.

#### Step 3 — the planted controls, both of them

- [ ] **Control 1: the old default.** Put `Scorer.LIFT` back in `Recommendations.DEFAULT_SCORER`,
      change nothing else, and run `./gradlew test --tests '*RateRunTest*'` blocking. The guard must
      fail on the `isNotIn` assertion with the same shape as step 1's red. **Quote it.** Restore
      `Scorer.RESOURCE_ALLOCATION` and confirm `git diff` shows the plant gone.

- [ ] **Control 2: the divergence #244 exists to prevent.** In
      `src/main/java/com/robsartin/segue/rate/RateRun.java`, replace the sweep's

```java
                  Recommendations.DEFAULT_SCORER,
```

  with

```java
                  com.robsartin.segue.domain.Scorer.LIFT,
```

  and run `./gradlew test --tests '*RateRunTest*'` blocking. The guard must now fail on the **deck**
  assertion — the deck deals the busily reached candidate while the recommender's default ranks the
  quietly reached one first — which is the divergence that would once have gone unnoticed. **Quote
  it**, then restore the reference and confirm `git diff src/main/java/com/robsartin/segue/rate/RateRun.java`
  is empty.

#### Step 4 — every other sentence that named the old value

No behaviour changes in this step. **Say out loud that these are prose and have no unit-testable
behaviour**; the explicit verification is `javadoc -Werror` inside the gate, `DocumentationLinksTest`
for the guide's relative links, and the greps in Task 3.

- [ ] `src/main/java/com/robsartin/segue/domain/Recommendations.java` — append to
      `MIN_CANDIDATE_DEGREE`'s javadoc, after its last paragraph and before the closing `*/`:

```java
   * <p><b>It stays where it is, now that {@link #DEFAULT_SCORER} no longer normalises.</b> The
   * argument this javadoc opens with is an argument about a normalised score, and since ADR 45's
   * amendment of 2026-09-07 (issue #291) the shipped default is not one. The floor is kept
   * regardless: {@code --scorer lift} is one flag away and needs it exactly as before, and what the
   * floor holds out is what ADR 57's floor reading reports on. Whether a floor measured for a
   * normalised scorer is the right floor for one that does not normalise is a question for a
   * reading, and that amendment does not answer it.
```

- [ ] `src/main/java/com/robsartin/segue/domain/Scorer.java` — in the class javadoc, replace

```java
 * <p><b>Why a dial and not simply {@link #LIFT}.</b> The right point differs by domain, and the
 * failure mode at each end is real rather than theoretical. {@link #RAW} rediscovers fame. {@link
 * #LIFT} rewards a thin entity whose whole presence in the graph is a list of influences, which is
 * why it is paired with a degree floor rather than used alone (see {@code
 * Recommendations.MIN_CANDIDATE_DEGREE}). A domain whose graph is shallower than music's may well
 * want {@link #RESOURCE_ALLOCATION}, so the choice belongs on the command line where it can be
 * compared in one run, not buried in a constant.
```

  with

```java
 * <p><b>Why a dial and not a constant.</b> The right point differs by domain, and by what ingest has
 * since made of the graph; the failure mode at each end is real rather than theoretical. {@link
 * #RAW} rediscovers fame. {@link #LIFT} rewards a thin entity whose whole presence in the graph is a
 * list of influences, which is why it is paired with a degree floor rather than used alone (see
 * {@code Recommendations.MIN_CANDIDATE_DEGREE}) — and why an expansion that put thin neighbours
 * beside the owner's highest-rated entities moved the default off it (ADR 45's amendment of
 * 2026-09-07, issue #291). That the shipped point could move at all is the argument for the dial:
 * the choice belongs on the command line where two points can be compared in one run, not buried in
 * a constant.
```

  The paragraph above it — "the two knobs answer different questions, and the second one is the
  finding" — is ADR 45's measurement and is **not** touched.

- [ ] `Scorer.RESOURCE_ALLOCATION`'s javadoc — replace

```java
  /**
   * Discount each intermediate by its degree itself. Harsher than Adamic-Adar by an order of
   * magnitude on the busiest nodes, which is the point: it all but ignores a connection through
   * something everybody touches.
   */
```

  with

```java
  /**
   * Discount each intermediate by its degree itself. Harsher than Adamic-Adar by an order of
   * magnitude on the busiest nodes, which is the point: it all but ignores a connection through
   * something everybody touches.
   *
   * <p><b>The shipped default since 2026-09-07</b>, by decision rather than by measurement: ADR 45's
   * amendment for issue #291 is the authority for it, and {@code Recommendations.DEFAULT_SCORER} is
   * the constant every tool reads.
   */
```

- [ ] `Scorer.LIFT`'s javadoc — replace

```java
  /**
   * Adamic-Adar, then divided by the candidate's own degree. The measured default: on the real
   * graph this is the point at which the list stopped naming the most famous entities in it and
   * started naming things reached by the list far more often than their size in the graph would
   * predict.
   */
```

  with

```java
  /**
   * Adamic-Adar, then divided by the candidate's own degree. The point ADR 45 measured and shipped:
   * on the graph of that measurement this is where the list stopped naming the most famous entities
   * in it and started naming things reached by the list far more often than their size in the graph
   * would predict.
   *
   * <p><b>It stopped being the default on 2026-09-07</b> (ADR 45's amendment for issue #291), on a
   * graph the promotion expander had changed the shape of. It is one {@code --scorer lift} away, at
   * the unchanged floor, and it is still the only point here that divides by the candidate's own
   * degree.
   */
```

- [ ] `src/test/java/com/robsartin/segue/recommend/MergedIdIsOfferedOnceTest.java` — one word in the
      class javadoc. Replace

```java
 * degree. Under the shipped {@code lift} the same fixture read 0.2236 before the merge, 0.4332
```

  with

```java
 * degree. Under the {@code lift} this test pins explicitly the same fixture read 0.2236 before the
 * merge, 0.4332
```

  and re-wrap the paragraph if Spotless asks. The figures stay: the test still runs under `lift`.
  `MergeDoesNotInflateDegreeTest` is **not** edited — it passes `--scorer lift` on the command line
  and its javadoc is about that scorer's arithmetic.

- [ ] `docs/developer-guide.md`, "What to explore next" — one word in the code block's comment. The
      `./gradlew` lines in that block are **not** edited.

```
# the measured defaults: `Recommendations.DEFAULT_SCORER`, `Recommendations.MIN_CANDIDATE_DEGREE`, twenty-five candidates, three routes each
```

  becomes

```
# the shipped defaults: `Recommendations.DEFAULT_SCORER`, `Recommendations.MIN_CANDIDATE_DEGREE`, twenty-five candidates, three routes each
```

- [ ] `docs/developer-guide.md`, "The score, in one formula" — insert a paragraph after "…which is
      the thing this feature exists to escape." and qualify the floor paragraph. Replace

```markdown
Dividing by the candidate's degree rewards a small denominator, so a **degree floor is not
optional** — `--min-degree`, defaulting to `Recommendations.MIN_CANDIDATE_DEGREE`. Without one the
answer is whatever is thinnest.
```

  with

```markdown
**The shipped point is `resource-allocation`, which is not the normalising one.** That is a decision
rather than a measurement, taken on the graph the promotion expander left and recorded in
[ADR 45](adr/0045-recommend-by-normalised-lift-with-routes.md)'s 2026-09-07 amendment for issue #291
— which holds the evidence, the four alternatives rejected and the cost. The paragraph above is ADR
45's finding and is not withdrawn, so what the default gives up is exactly the
surprise-over-popularity property, and `--scorer lift` at the unchanged floor is one flag away.
`Recommendations.DEFAULT_SCORER` is the constant; `RecommendCli`'s `--scorer` default, the usage line
it prints and the deck's own sweep all read it (issue #244).

Dividing by the candidate's degree rewards a small denominator, so under `lift` a **degree floor is
not optional** — `--min-degree`, defaulting to `Recommendations.MIN_CANDIDATE_DEGREE`. Without one
the answer is whatever is thinnest. The floor did not move when the default did, and whether a floor
measured for a normalised scorer is the right one for a scorer that does not normalise is a question
for a reading rather than something this guide should assert.
```

- [ ] `docs/developer-guide.md`, "Expanding a top candidate demotes it" — scope the section to the
      scorer it is about. Replace

```markdown
**Read this before running a batch of expansions, not after.** `lift` divides by the candidate's
own degree, and `expand_entity` raises exactly that number.
```

  with

```markdown
**Read this before running a batch of expansions, not after.** **This section is about `--scorer
lift`**, which stopped being the default on 2026-09-07 (issue #291) and is still one flag away; the
measurement below was taken under it. `lift` divides by the candidate's own degree, and
`expand_entity` raises exactly that number.
```

  and append to the end of that section, after "…the ranking tracking ingest history rather than the
  world.":

```markdown
**The shipped default no longer divides by the candidate's own degree**, so this route to demotion is
not in it. That is arithmetic from `Scorer.score` — `normalisedByCandidateDegree` is true for `LIFT`
alone — and not a second measurement. It is not a licence to expand top candidates either: an
expansion still moves the degrees of the intermediates a candidate is reached through, which every
scorer but `raw` discounts by. And this mechanism is why the default moved at all: the same expansion
is the graph change ADR 45's 2026-09-07 amendment for issue #291 decided on.
```

#### Step 5 — gate and commit

- [ ] Run the gate, **blocking**:

```bash
SEGUE_REQUIRE_BROWSER=true SEGUE_REQUIRE_GRAPHVIZ=true ./gradlew check --rerun-tasks
```

  If `spotlessCheck` fails, run `./gradlew spotlessApply` and re-run the gate. Quote the final line.

- [ ] Stage by explicit path, stderr visible, and read the status before committing:

```bash
git add src/main/java/com/robsartin/segue/domain/Recommendations.java \
        src/main/java/com/robsartin/segue/domain/Scorer.java \
        src/test/java/com/robsartin/segue/rate/RateRunTest.java \
        src/test/java/com/robsartin/segue/recommend/MergedIdIsOfferedOnceTest.java \
        docs/developer-guide.md
git status --short
```

  `git status --short` must show exactly those five files staged and nothing of anybody else's.

- [ ] Commit:

```
The recommender and the deck default to resource-allocation (#291)

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
```

---

### Task 2: the amendment to ADR 45

- [ ] **Say out loud, in the step report, that this task has no unit-testable behaviour and why.** No
      code changes; a markdown amendment has no assertion to red. The explicit verification methods
      are named and each is run: `AdrIndexTest` (no number, title or status changes),
      `AdrCitationsTest` (no commit hash is introduced), `DocumentationLinksTest` (every relative
      link above resolves), and a re-read of the appended text against the spec's §7.

- [ ] Confirm the file's current tail before appending, blocking:

```bash
tail -5 docs/adr/0045-recommend-by-normalised-lift-with-routes.md
```

  It must be the end of the 2026-09-07 amendment for issue #289. Nothing above it is edited.

- [ ] Append the amendment to the end of
      `docs/adr/0045-recommend-by-normalised-lift-with-routes.md`, **exactly** as the spec's §7 gives
      it — one blank line, then the fenced block's contents without the fence. Do not reflow it, do
      not add a figure, do not add a commit hash, do not shorten a rejected alternative.

- [ ] Verify the two mechanical properties before the gate, blocking, and quote both:

```bash
grep -nE '`[0-9a-fA-F]{7,40}`' docs/adr/0045-recommend-by-normalised-lift-with-routes.md | tail -20
git diff --stat docs/adr/
```

  The first must print nothing newer than the citations `AdrCitationsTest` already allowlists — the
  appended text introduces none. The second must show one file changed, insertions only.

- [ ] Run the gate, **blocking**:

```bash
SEGUE_REQUIRE_BROWSER=true SEGUE_REQUIRE_GRAPHVIZ=true ./gradlew check --rerun-tasks
```

- [ ] Stage by explicit path and commit:

```bash
git add docs/adr/0045-recommend-by-normalised-lift-with-routes.md
git status --short
```

```
Record the decision to move the default scorer (#291)

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
```

---

### Task 3: the gate on the finished tree, and the proof the cascade is complete

- [ ] Run the gate once more on the finished tree, **blocking**, and quote the final line:

```bash
SEGUE_REQUIRE_BROWSER=true SEGUE_REQUIRE_GRAPHVIZ=true ./gradlew check --rerun-tasks
```

- [ ] **Derive the set rather than eyeballing it.** Run each of these blocking and paste the full
      output into the report; a grep that returns nothing is only evidence if you say what it was
      looking for:

```bash
grep -rn 'Scorer.LIFT\|DEFAULT_SCORER' src/main --include='*.java'
grep -rni 'lift' src/main --include='*.java'
grep -rni 'lift' docs/developer-guide.md docs/user-guide.md README.md
grep -rn 'Scorer.LIFT\|"lift"' src/test --include='*.java'
```

  Expected, and each to be confirmed one at a time: `src/main` names `LIFT` only in `Scorer`'s own
  enum constant, its javadoc and the class javadoc's dial paragraph — never as a default. Every
  remaining `lift` in `src/test` is a scorer passed explicitly. Every remaining `lift` in the guide
  is an ADR filename, the enumeration of the four spellings, the `--scorer lift` section, or the
  merge chapter's sentence about the scorer `MergeDoesNotInflateDegreeTest` pins. `docs/user-guide.md`
  and `README.md` name no scorer at all. **Anything else is a finding: report it, do not quietly
  edit it.**

- [ ] Confirm the tree is clean and the two commits are the only ones:

```bash
git status --short
git log --oneline -3
```

- [ ] **Draft, but do not file, the follow-up issue for the ninth reading.** Filing is the owner's
      call and this plan does not create GitHub issues. Put the draft in the report:

  - Title: `The ninth reading, the first dealt from the new default`
  - Body: the ninth reading is the first taken after a deck session dealt candidate cards from
    `Recommendations.DEFAULT_SCORER` at its new value. Per ADR 45's 2026-09-07 amendment for issue
    #291, its note must be fixed before the reading exists and must record that the deck's cards —
    and so the eligible population — now follow `resource-allocation`, which is where issue #272's
    bias moved to. The rule of issue #245 applies unchanged, with `resource-allocation` as the
    shipped scorer and the floor unchanged.

- [ ] Write the report. It carries: both commit hashes, the two quoted reds from Task 1 step 1 and
      the two quoted planted-control failures from step 3, the gate's final line, the grep output
      above, and any place where the issue's description of the code did not match the code (the spec
      §1 lists four found while planning; report any fifth).
