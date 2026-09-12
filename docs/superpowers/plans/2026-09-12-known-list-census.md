# `graphCensus --known` — the known-list census — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** close issue #311 — `graphCensus` gains an optional `--known <file>` and one section,
`known list`, printed last, in two sub-sections: the file's own entities, and the file composed
through `KnownList.promoted` with the ratings map. Each prints `named`, `in the graph`,
`never expanded`, `no known neighbour within two hops`, and the first two per `NodeKind`. Absent the
flag the block is **byte-identical to today's**. "Expanded" is one new rule, `Expanded` in `domain`,
derived from the provenance the log already holds, so the expander issue that follows reads the same
answer.

**Architecture:** Four new types and one move.

- `domain.Expanded` — the rule. `Expanded.in(log)` walks every row that carries a `Provenance` and
  derives the **seed** qid from the reference's shape: `<qid>$…` (a Wikidata statement id) or
  `wdqs:<other>:<property>:<qid>` (a reverse-discovered edge). It holds a `Set<String>` and answers
  `covers(qid)`. No graph, no network, no file. Two callers by design: this census now, the
  expander issue next.
- `census.Neighbours` — adjacency out of `LogProjection.edges()`, the sibling of `Degrees`, plus the
  bounded reach question the walk asks. The census has no engine (see Global Constraints), so this
  is the only traversal available to it.
- `census.KnownListInput` — a basename and a list of qids, read through `support.QidList`. The one
  home for "the basename, never the path".
- `census.KnownListCensus` — the section: two `Population` readings by one rule, over the resolved
  population.
- **The move:** `Routes.MAX_HOPS` → `Recommendations.MAX_HOPS` in `domain`, because `census` may not
  depend on `recommend`. See the spec's Premise correction 1.

`Census` gains an eighth component, `Optional<KnownListCensus>`; `CensusReport` prints it when it is
there and adds nothing when it is not, which is what keeps the no-flag block byte-identical.

**Tech Stack:** Java 25, Gradle (plain `./gradlew`), JUnit 5, AssertJ, SQLite, ArchUnit.

**Spec:** `docs/superpowers/specs/2026-09-12-known-list-census-design.md` — read its
**Premise corrections** section first; it is where this plan and that document differ from each
other's first drafts, and it is the authority over the body above it.

## Global Constraints

- **Pure TDD / red first.** Every behaviour: write the test, **run it, observe a real assertion
  failure**, then the minimum code. **A compile error is never a red.** Where a new type is needed
  before a test can compile, this plan splits the step: add the **stub** (the signature, returning
  the empty/false answer), then the test, then observe the assertion failure, then the body. If a
  first run ends in `BUILD FAILED` on compilation, the step has proved nothing. **Quote the actual
  failure text in the task report** — not "it failed".
- **Every guard gets a positive control.** The controls this plan requires by name: Task 2 Step 5
  (a node claim whose reference is its own bare qid must not read as expanded — plant it, watch the
  new test fail against a suffix-only rule, restore); Task 2 Step 7 (the prefix control); Task 3
  Step 5 (a member at three hops must not be reached — plant a depth of 3 and watch the
  "missed at three" assertion fail); Task 5 Step 4 (the unchanged golden pin is the control that
  the no-flag block did not move — its diff must be empty).
- Test names `should<Expected>When<Condition>` with `@DisplayName`.
- **Mikado**: the gate is green before every commit, and each task ends green and is independently
  reviewable. **Stage by explicit path, git stderr visible — never `git add -A`, never
  `2>/dev/null` on `git add`.** Read `git status` before every commit. Commits end, after a blank
  line, `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`. **Never cite a
  `.superpowers/` path from a committed file.**
- Gate, **blocking, never backgrounded**:
  `SEGUE_REQUIRE_BROWSER=true SEGUE_REQUIRE_GRAPHVIZ=true ./gradlew check --rerun-tasks`. Run
  `./gradlew spotlessApply` before each gate.
- **Only JDK 25 is installed and Gradle launches on it.** Plain `./gradlew`; never
  `/usr/libexec/java_home -v 21` (it silently returns 25).
- **Never run a writing dev task** — `own`, `ownClaim`, `retractEntity`, `rate`, `expandPromotions`,
  `evaluate`, any seeding task. `~/.segue/segue.db` is never read, written, copied or created. Every
  database in this plan is in-memory or a `@TempDir` file, and every known-list file is written by
  the test that reads it.
- **`docs/` is a declared input of `test`**, so a document edit re-runs the suite. Run the per-task
  loops **without** `--rerun-tasks`. `CLAUDE.md` is **not** a declared input — and it names neither
  `census` nor `graphCensus` today, so **this plan does not edit `CLAUDE.md` at all**.
- **No commit hash, no `.superpowers/` path, no figure from the owner's graph, and no qid enters any
  file under `docs/adr`.** `AdrCitationsTest` matches a backticked run of 7–40 hex characters, and
  an invented qid like `Q0900201` is seven characters behind a `Q`.
- **Invented ids only, ADR 58's leading zero.** The ids this plan introduces are `Q090100`,
  `Q0901001`, `Q0901002`, `Q0901003`, `Q0901004` and `Q0901005`; none appears anywhere under `src`
  or `docs` today (checked). They need no `StandInQidsDenoteNothingTest` allowlist entry, because
  that sweep exempts the leading-zero form. **`Q12` and `Q123` are real Wikidata ids** and may not
  be used for the prefix control, whatever the spec's illustration says.
- **After `./gradlew spotlessApply`, re-read any javadoc this plan writes** and confirm every
  `{@code …}` span is intact and **on one source line**. google-java-format reflows javadoc and will
  break inside an inline tag. To put a span on one line, shorten the clause *before* it.
- **No wall-clock assertion anywhere.** Task 7 measures and observes; it asserts correctness at
  scale and nothing about duration. The machine is loaded.
- **No entity is ever named in the block**, and every count is over the **resolved** population — the
  file's qids read through `Equivalences`, so a merge's two sides count once, on the canonical side.
- **No ArchUnit rule changes in this issue.** Premise correction 3 records why: `QidList` is in
  `support`, which `census` already reaches, and neither `theCensusOnlyReads` nor
  `theCensusOpensNothingElse` bites on a `java.nio.file` read. If a rule does fire, stop and report
  it rather than editing the rule.

**The two reference shapes, exactly** (confirmed against `ClaimMapper` and `ReverseClaims`; see
Premise correction 5):

```
Q0901001$4f1a-invented          a forward statement id — the seed is everything before the first $
wdqs:Q0901002:P737:Q0901001     a reverse-discovered edge — the seed is everything after the last :
Q0901002                        a discovered neighbour's own node claim — NOT an expansion of anything
```

---

## Task 1 — Move `MAX_HOPS` into `domain`, where the census can read it

Files: `src/main/java/com/robsartin/segue/domain/Recommendations.java`,
`src/main/java/com/robsartin/segue/recommend/Routes.java`,
`src/main/java/com/robsartin/segue/recommend/RecommendationReport.java`,
`docs/developer-guide.md`.

**This task has no red, and that is honest rather than an omission.** It changes no behaviour: the
same value, the same two readers, a different home. Its verification is the whole `recommend` suite
passing unchanged, the architecture rules accepting the new home, and the full gate. The task report
must say which method was used, in those words.

- [ ] **Step 1 — confirm the call sites before touching anything.**

  ```
  grep -rn 'MAX_HOPS' src docs | grep -v DEFAULT_MAX_HOPS
  ```

  Expected exactly four: the declaration and one use in `Routes`, one use in `RecommendationReport`,
  one prose mention in `docs/developer-guide.md`. **No test names it.** If the list is longer, stop
  and report — the plan was written against a shorter one.

- [ ] **Step 2 — add the constant to `Recommendations`**, immediately after
  `MIN_CANDIDATE_DEGREE`, carrying `Routes.MAX_HOPS`'s javadoc and one new paragraph saying why it
  is here:

  ```java
  /**
   * How far the sweep looks, and so how long an explanation may be.
   *
   * <p>The traversal is still allowed to return a ONE-hop route and to rank it first, and it
   * should: if you already have a direct edge to the candidate, "it is cited by something you
   * know" is a better answer than the two-hop route the scoring happened to count.
   *
   * <p><b>Here rather than in {@code recommend}, for {@link #MIN_CANDIDATE_DEGREE}'s reason</b>
   * (issue #311). A second reader arrived that may not depend on that package:
   * {@code theCensusOnlyReads} fences {@code census} to one sibling, {@code export}, so the known-
   * list census could not have read it where it was. The choice was between a second literal and
   * one home, and this project has already made it once, for the floor the same census reads.
   */
  public static final int MAX_HOPS = 2;
  ```

- [ ] **Step 3 — delete the declaration from `Routes`** and read the new home. In `Routes.java`,
  remove the `MAX_HOPS` field and its javadoc, add
  `import com.robsartin.segue.domain.Recommendations;`, and change the one use:

  ```java
              graph.paths(seed, candidate.entity().qid(), Recommendations.MAX_HOPS),
  ```

- [ ] **Step 4 — change the one use in `RecommendationReport`:**

  ```java
        "(no route within " + Recommendations.MAX_HOPS + " hops — the graph moved under the scan)";
  ```

  Add the import if the file does not already name `Recommendations`; remove the `Routes` import if
  nothing else in the file uses it (check with `grep -n 'Routes' src/main/java/com/robsartin/segue/recommend/RecommendationReport.java`).

- [ ] **Step 5 — run the recommender suite and the architecture rules.**

  ```
  ./gradlew test --tests 'com.robsartin.segue.recommend.*' --tests '*ArchitectureTest'
  ```

  Expected green, with a non-zero test count for the `recommend` package — a `--tests` filter that
  matches nothing is the failure mode here. Record the count. The rules that could have caught this
  are `domainHasNoThirdPartyDependencies` (an `int`, so it passes) and
  `domainValueTypesAreRecordsOrEnums`.

- [ ] **Step 6 — correct the one prose mention.** In `docs/developer-guide.md` around line 2480,
  `` `Routes.MAX_HOPS` is 2 where `find_paths` `` becomes `` `Recommendations.MAX_HOPS` is 2 where
  `find_paths` ``. Change nothing else in that sentence.

- [ ] **Step 7 — format, gate, commit.**

  ```
  ./gradlew spotlessApply
  SEGUE_REQUIRE_BROWSER=true SEGUE_REQUIRE_GRAPHVIZ=true ./gradlew check --rerun-tasks
  ```

  Re-read `Recommendations.MAX_HOPS`'s javadoc after `spotlessApply` and confirm the
  `{@code theCensusOnlyReads}` and `{@code census}` spans are intact and each on one line. Then
  `git status`, stage by explicit path, commit:

  > Move MAX_HOPS into domain, where both its readers can reach it (#311)

---

## Task 2 — `Expanded` in `domain`: one rule, read off provenance

Files: `src/main/java/com/robsartin/segue/domain/Expanded.java` (new),
`src/test/java/com/robsartin/segue/domain/ExpandedTest.java` (new).

**Read first.** `LoggedAssertion` is a sealed interface permitting six records, and only two of them
carry a `Provenance`: `NodeAssertion` and `AssertionRecord`. `Provenance.sourceRef` is **nullable** —
`Provenance.owner(…)` passes `null`. The switch below is exhaustive over the six, with no `default`,
so adding a seventh claim type fails compilation here, which is the right obligation.

- [ ] **Step 1 — the stub, so the test compiles and then fails on an assertion.** Create
  `src/main/java/com/robsartin/segue/domain/Expanded.java`:

  ```java
  package com.robsartin.segue.domain;

  import java.util.List;
  import java.util.Objects;
  import java.util.Set;

  /** Which entities an expansion has been run over, read off the log. */
  public record Expanded(Set<String> seeds) {

    public Expanded {
      seeds = Set.copyOf(Objects.requireNonNull(seeds, "seeds"));
    }

    public static Expanded in(List<LoggedAssertion> log) {
      Objects.requireNonNull(log, "log");
      return new Expanded(Set.of());
    }

    public boolean covers(String qid) {
      Objects.requireNonNull(qid, "qid");
      return seeds.contains(qid);
    }
  }
  ```

- [ ] **Step 2 — RED: the forward shape.** Create
  `src/test/java/com/robsartin/segue/domain/ExpandedTest.java`. Every id here is invented with ADR
  58's leading zero.

  ```java
  package com.robsartin.segue.domain;

  import static org.assertj.core.api.Assertions.assertThat;

  import java.time.Instant;
  import java.util.List;
  import org.junit.jupiter.api.DisplayName;
  import org.junit.jupiter.api.Test;

  /**
   * The one rule that says whether an entity has been expanded, and the two ways a real run says so.
   *
   * <p>Both shapes are the adapters' own, confirmed against them rather than assumed: a forward
   * claim carries the Wikidata statement id, which begins with the subject's qid and a {@code $};
   * a reverse-discovered edge carries {@code wdqs:<other>:<property>:<seed>}. The two controls at
   * the foot are what stop a looser reading of the second shape: the reverse pass also records each
   * discovered neighbour as a node claim whose reference is that neighbour's own bare qid, and a
   * suffix test would read every one of them as an expansion of itself.
   */
  class ExpandedTest {

    private static final Instant WHEN = Instant.parse("2026-01-01T00:00:00Z");

    private static final String SEED = "Q0901001";
    private static final String OTHER = "Q0901002";

    /** {@link #SEED} with its last digit removed — a prefix of it, and a different entity. */
    private static final String SHORTER = "Q090100";

    private static AssertionRecord edge(String from, String to, String sourceRef) {
      return new AssertionRecord(
          from, to, "INFLUENCED_BY", null, null, new Provenance("wikidata", sourceRef, WHEN, 1.0));
    }

    private static NodeAssertion node(String qid, String sourceRef) {
      return new NodeAssertion(
          qid, NodeKind.PERSON, "An Invented Name", new Provenance("wikidata", sourceRef, WHEN, 1.0));
    }

    @Test
    @DisplayName("a forward claim citing the entity as the statement's subject is an expansion")
    void shouldCoverTheSeedWhenAForwardStatementNamesItAsTheSubject() {
      Expanded expanded = Expanded.in(List.of(edge(SEED, OTHER, SEED + "$4f1a-invented")));

      assertThat(expanded.covers(SEED)).isTrue();
      assertThat(expanded.covers(OTHER)).isFalse();
    }
  }
  ```

- [ ] **Step 3 — run it and observe a real assertion failure.**

  ```
  ./gradlew test --tests 'com.robsartin.segue.domain.ExpandedTest'
  ```

  Expected: **one failing assertion**, of the shape
  `Expecting value to be true but was false`, on the first assertion of
  `shouldCoverTheSeedWhenAForwardStatementNamesItAsTheSubject`. **`BUILD FAILED` on compilation is
  not this red** — if the compiler speaks, fix the test and run again. Quote the message.

- [ ] **Step 4 — GREEN: the body.** Replace `Expanded.in` and add the two private helpers and the
  class javadoc:

  ```java
  package com.robsartin.segue.domain;

  import java.util.LinkedHashSet;
  import java.util.List;
  import java.util.Objects;
  import java.util.Set;
  import java.util.regex.Pattern;

  /**
   * Which entities an expansion has been run over — read off what the log recorded, never guessed
   * from degree (issue #311).
   *
   * <p>An expansion leaves its seed's id in every edge it records, in one of two shapes. A Wikidata
   * forward claim carries the statement id, and a statement id begins with the subject's qid
   * followed by {@code $}. A reverse-discovered edge carries {@code wdqs:<other>:<property>:<seed>},
   * and the seed is the last field. This class reads the reference apart on those separators rather
   * than testing a prefix or a suffix, which is what makes it exact: {@code Q12} is not {@code Q123}
   * whichever end you look from.
   *
   * <p><b>The shape is the whole rule, and the trap it avoids is a real row.</b> The reverse pass
   * also records each neighbour it discovered as a node claim whose reference is that neighbour's
   * own bare qid. A rule phrased "the reference ends with the qid" would read every discovered
   * neighbour as having expanded itself, and the count would collapse to "every node in the graph".
   *
   * <p><b>It does not read the MusicBrainz adapter's references, and that is a stated limit rather
   * than an oversight.</b> Those name the seed's MBID, not its qid. Every expansion runs every
   * adapter that supports the seed's kind and the Wikidata adapter supports every kind, so a
   * MusicBrainz expansion is accompanied by a Wikidata expansion of the same seed in the same call.
   * The residual — a call in which Wikidata was unavailable and MusicBrainz was not — leaves an
   * entity this rule calls unexpanded, which is a true statement about what Wikidata recorded and
   * errs towards "expand it".
   *
   * <p><b>The two-caller shape ADR 42 gave {@code KindMapper.rederive} and ADR 44 gave {@link
   * Retractions}.</b> The census reads it to count what has never been expanded; the expander reads
   * it to choose its population. Two tools cannot disagree about who has been expanded when there is
   * one rule to disagree with.
   *
   * <p>It holds no graph, opens nothing and makes no network call: a list of rows in, a set of qids
   * out.
   *
   * @param seeds the entities some row cites as an expansion's seed. Membership is the only thing
   *     ever asked of it, so no order is promised and none is read
   */
  public record Expanded(Set<String> seeds) {

    private static final Pattern QID = Pattern.compile("Q\\d+");

    /** What {@code ReverseClaims} puts in front of a reference it built from a truthy triple. */
    private static final String FROM_THE_QUERY_SERVICE = "wdqs:";

    public Expanded {
      seeds = Set.copyOf(Objects.requireNonNull(seeds, "seeds"));
    }

    /** Every entity this log cites as the seed of an expansion. */
    public static Expanded in(List<LoggedAssertion> log) {
      Objects.requireNonNull(log, "log");
      Set<String> seeds = new LinkedHashSet<>();
      for (LoggedAssertion assertion : log) {
        // Exhaustive over the sealed interface, with no default: a seventh kind of claim has to
        // decide here whether it can carry an expansion's reference, rather than fall through.
        Provenance provenance =
            switch (assertion) {
              case NodeAssertion claim -> claim.provenance();
              case AssertionRecord claim -> claim.provenance();
              case Retraction ignored -> null;
              case LocalEntity ignored -> null;
              case OwnerEdge ignored -> null;
              case SameAs ignored -> null;
            };
        if (provenance == null) {
          continue;
        }
        String seed = seedOf(provenance.sourceRef());
        if (seed != null) {
          seeds.add(seed);
        }
      }
      return new Expanded(seeds);
    }

    /** Whether some row cites this entity as an expansion's seed. */
    public boolean covers(String qid) {
      Objects.requireNonNull(qid, "qid");
      return seeds.contains(qid);
    }

    /**
     * The seed a reference names, or null where it names none.
     *
     * <p>Read apart on the separator each shape actually uses. A reference that is neither shape —
     * {@code ClaimMapper}'s fallback for a statement carrying no id, a MusicBrainz citation, the
     * bare qid a discovered neighbour's node claim carries, or an owner claim's null — yields
     * nothing at all.
     */
    private static String seedOf(String sourceRef) {
      if (sourceRef == null) {
        return null;
      }
      if (sourceRef.startsWith(FROM_THE_QUERY_SERVICE)) {
        return qidOrNull(sourceRef.substring(sourceRef.lastIndexOf(':') + 1));
      }
      int statement = sourceRef.indexOf('$');
      return statement < 0 ? null : qidOrNull(sourceRef.substring(0, statement));
    }

    private static String qidOrNull(String candidate) {
      return QID.matcher(candidate).matches() ? candidate : null;
    }
  }
  ```

- [ ] **Step 4b — run it and observe green.** Same command as Step 3. Both assertions pass.

- [ ] **Step 5 — RED, then the positive control: the reverse shape and the neighbour-only case.**
  Add two tests to `ExpandedTest`:

  ```java
    @Test
    @DisplayName("a reverse-discovered edge whose reference ends in the entity is an expansion")
    void shouldCoverTheSeedWhenAReverseReferenceEndsWithIt() {
      Expanded expanded =
          Expanded.in(List.of(edge(OTHER, SEED, "wdqs:" + OTHER + ":P737:" + SEED)));

      assertThat(expanded.covers(SEED)).isTrue();
    }

    @Test
    @DisplayName("an entity that only appears as another expansion's neighbour is not expanded")
    void shouldNotCoverANeighbourWhenItOnlyAppearsInAnotherExpansionsRows() {
      // Exactly what one reverse pass writes: the edge, whose reference names the neighbour in the
      // middle, and the neighbour's own node claim, whose reference is its bare qid.
      Expanded expanded =
          Expanded.in(
              List.of(
                  edge(OTHER, SEED, "wdqs:" + OTHER + ":P737:" + SEED),
                  node(OTHER, OTHER)));

      assertThat(expanded.covers(OTHER))
          .as("the neighbour is the subject of the discovered triple, not the seed of the call")
          .isFalse();
      assertThat(expanded.covers(SEED)).isTrue();
    }
  ```

  Run: the reverse test passes immediately (the body already handles it) and the neighbour test
  passes too. **A test that is green on arrival has proved nothing yet, so plant the defect the
  rule exists to refuse and watch it fire.** Temporarily replace `seedOf`'s query-service arm with
  a suffix test:

  ```java
      if (sourceRef.startsWith(FROM_THE_QUERY_SERVICE) || QID.matcher(sourceRef).matches()) {
        return qidOrNull(sourceRef.substring(sourceRef.lastIndexOf(':') + 1));
      }
  ```

  Run `./gradlew test --tests 'com.robsartin.segue.domain.ExpandedTest'` and record the failure:
  `shouldNotCoverANeighbourWhenItOnlyAppearsInAnotherExpansionsRows` must fail with
  `[the neighbour is the subject of the discovered triple, not the seed of the call] Expecting value
  to be false but was true`. **Then remove the plant** and re-run green. The task report quotes both
  runs.

- [ ] **Step 6 — verify the plant is gone.**

  ```
  git diff src/main/java/com/robsartin/segue/domain/Expanded.java
  ```

  Read it. The arm must read `if (sourceRef.startsWith(FROM_THE_QUERY_SERVICE)) {` and nothing else.

- [ ] **Step 7 — RED, then the positive control: the prefix case.** Add:

  ```java
    @Test
    @DisplayName("an entity whose id is a prefix of the seed's is not expanded by the seed's rows")
    void shouldNotCoverAPrefixOfTheSeedWhenOnlyTheLongerIdWasExpanded() {
      Expanded expanded =
          Expanded.in(
              List.of(
                  edge(SEED, OTHER, SEED + "$4f1a-invented"),
                  edge(OTHER, SEED, "wdqs:" + OTHER + ":P737:" + SEED)));

      assertThat(expanded.covers(SHORTER))
          .as(SHORTER + " is a prefix of " + SEED + " and a different entity")
          .isFalse();
      assertThat(expanded.covers(SEED)).isTrue();
    }
  ```

  This is green on arrival too, so plant its defect: change `seedOf`'s forward arm to a
  `startsWith`-and-strip form that ignores where the `$` is —

  ```java
      int statement = sourceRef.indexOf('$');
      if (statement < 0) {
        return null;
      }
      return sourceRef.startsWith(SHORTER_PLANT) ? SHORTER_PLANT : qidOrNull(sourceRef.substring(0, statement));
  ```

  with `private static final String SHORTER_PLANT = "Q090100";` beside it. Run and record the
  failure on `shouldNotCoverAPrefixOfTheSeedWhenOnlyTheLongerIdWasExpanded`
  (`Expecting value to be false but was true`). **Remove the plant, both lines and the constant**,
  re-run green, and `git diff` the file again to confirm.

- [ ] **Step 8 — RED: the whole-log case, including the rows that carry no provenance at all.** Add:

  ```java
    @Test
    @DisplayName("rows carrying no source reference contribute no seed and throw nothing")
    void shouldCoverNothingWhenTheLogHoldsOnlyFirstPersonClaimsAndUnshapedReferences() {
      Expanded expanded =
          Expanded.in(
              List.of(
                  LocalEntity.minted("Q0901003", NodeKind.WORK, "A Minted Thing", WHEN),
                  OwnerEdge.claimed("Q0901003", SEED, "INFLUENCED_BY", WHEN),
                  SameAs.declared("Q0901003", "Q0901004", WHEN),
                  new Retraction("Q0901005", "an invented reason", WHEN),
                  edge(SEED, OTHER, "P737:" + OTHER),
                  edge(SEED, OTHER, "artist/invented#member of band:invented")));

      assertThat(expanded.seeds()).isEmpty();
    }
  ```

  Two of those references are real shapes that must **not** count: `ClaimMapper`'s fallback for a
  statement with no id, and a MusicBrainz citation. Run it; it should pass. If it does not, the
  failure is the finding — report it before changing anything. **Check the constructor arguments of
  `LocalEntity.minted`, `OwnerEdge.claimed`, `SameAs.declared` and `Retraction` against their own
  sources before writing this test**; this plan quotes them from `census/InventedCensus.java` and a
  signature change would show up here as a compile error, which is not a red.

- [ ] **Step 9 — format, gate, commit.**

  ```
  ./gradlew spotlessApply
  SEGUE_REQUIRE_BROWSER=true SEGUE_REQUIRE_GRAPHVIZ=true ./gradlew check --rerun-tasks
  ```

  Confirm `ArchitectureTest`'s `domainHasNoThirdPartyDependencies` is green — `java.util.regex` is
  `java..` and allowed. Re-read `Expanded`'s javadoc after `spotlessApply` for intact `{@code …}`
  spans on one line each. Then `git status`, stage by explicit path, commit:

  > Expanded: one rule for who has been expanded, read off provenance (#311)

---

## Task 3 — `Neighbours`: adjacency out of the fold, and the bounded reach question

Files: `src/main/java/com/robsartin/segue/census/Neighbours.java` (new),
`src/test/java/com/robsartin/segue/census/NeighboursTest.java` (new).

**Read first.** `Degrees` is the sibling to imitate: package-private, a static factory over
`LogProjection`, seeded from `projection.nodes().keySet()` so isolated nodes are in the map at zero,
and using `computeIfPresent` so a dangling endpoint cannot create a key. `LogProjection.edges()` has
already dropped dangling and withdrawn edges and applied retractions and merges, which is why the
walk needs no filter of its own (ADR 44).

- [ ] **Step 1 — the stub.** Create `src/main/java/com/robsartin/segue/census/Neighbours.java`:

  ```java
  package com.robsartin.segue.census;

  import com.robsartin.segue.export.LogProjection;
  import java.util.Map;
  import java.util.Objects;
  import java.util.Set;

  /** Who is next to whom, in the fold. */
  final class Neighbours {

    private Neighbours() {}

    static Map<String, Set<String>> in(LogProjection projection) {
      Objects.requireNonNull(projection, "projection");
      return Map.of();
    }

    static boolean reaches(
        Map<String, Set<String>> adjacency, String from, Set<String> population, int hops) {
      return false;
    }
  }
  ```

- [ ] **Step 2 — RED: adjacency is undirected and holds every node.** Create
  `src/test/java/com/robsartin/segue/census/NeighboursTest.java`:

  ```java
  package com.robsartin.segue.census;

  import static org.assertj.core.api.Assertions.assertThat;

  import com.robsartin.segue.domain.NodeKind;
  import com.robsartin.segue.export.LogProjection;
  import java.util.List;
  import java.util.Map;
  import java.util.Set;
  import org.junit.jupiter.api.DisplayName;
  import org.junit.jupiter.api.Test;

  /**
   * The adjacency the known-list walk runs on, and the bound it stops at.
   *
   * <p>A line of five: A — B — C — D — E. It is the one shape that can tell "within two hops" from
   * "within three" and from "within one", and every assertion here reads off it.
   */
  class NeighboursTest {

    private static final String A = "Q0901001";
    private static final String B = "Q0901002";
    private static final String C = "Q0901003";
    private static final String D = "Q0901004";
    private static final String E = "Q0901005";

    private static LogProjection line() {
      return LogProjection.of(
          new InventedCensus.FakeAssertionLog()
              .with(
                  InventedCensus.node(A, NodeKind.PERSON, "A"),
                  InventedCensus.node(B, NodeKind.PERSON, "B"),
                  InventedCensus.node(C, NodeKind.PERSON, "C"),
                  InventedCensus.node(D, NodeKind.PERSON, "D"),
                  InventedCensus.node(E, NodeKind.PERSON, "E"),
                  InventedCensus.edge(A, B, "MEMBER_OF", InventedCensus.sourced()),
                  InventedCensus.edge(B, C, "MEMBER_OF", InventedCensus.sourced()),
                  InventedCensus.edge(C, D, "MEMBER_OF", InventedCensus.sourced()),
                  InventedCensus.edge(D, E, "MEMBER_OF", InventedCensus.sourced())));
    }

    @Test
    @DisplayName("every node is a key and an edge is read from both ends")
    void shouldHoldBothEndsOfEveryEdgeWhenTheFoldIsRead() {
      Map<String, Set<String>> adjacency = Neighbours.in(line());

      assertThat(adjacency).containsOnlyKeys(A, B, C, D, E);
      assertThat(adjacency.get(A)).containsExactly(B);
      assertThat(adjacency.get(B)).containsExactlyInAnyOrder(A, C);
    }
  }
  ```

  Run `./gradlew test --tests 'com.robsartin.segue.census.NeighboursTest'` and observe a real
  assertion failure — the `containsOnlyKeys` diff against an empty map. Quote it.

- [ ] **Step 3 — GREEN: the adjacency.**

  ```java
    /**
     * Who each node shares a folded edge with — one home, because the known-list walk is the only
     * thing that asks and it asks it once per population.
     *
     * <p><b>Every node is a key, isolated ones with an empty set.</b> A node nothing reaches is
     * exactly the finding the walk exists to report, and a map that omitted it would answer the
     * question by losing it. {@code Degrees} seeds itself the same way for the same reason.
     *
     * <p><b>Undirected.</b> "Is anything I know within two hops of this" is a question about the
     * graph, not about which end of a relationship Wikidata states it on.
     *
     * <p>{@code LogProjection.edges()} has already dropped the dangling and the withdrawn and
     * applied retraction and merge (ADR 44), so nothing here filters.
     */
    static Map<String, Set<String>> in(LogProjection projection) {
      Objects.requireNonNull(projection, "projection");
      Map<String, Set<String>> adjacency = new LinkedHashMap<>();
      for (String qid : projection.nodes().keySet()) {
        adjacency.put(qid, new LinkedHashSet<>());
      }
      for (EdgeRecord edge : projection.edges()) {
        link(adjacency, edge.fromQid(), edge.toQid());
        link(adjacency, edge.toQid(), edge.fromQid());
      }
      Map<String, Set<String>> copy = new LinkedHashMap<>();
      adjacency.forEach((qid, of) -> copy.put(qid, Collections.unmodifiableSet(of)));
      return Collections.unmodifiableMap(copy);
    }

    /** Both ends have to be nodes: {@code Degrees} uses {@code computeIfPresent} for this. */
    private static void link(Map<String, Set<String>> adjacency, String from, String to) {
      Set<String> of = adjacency.get(from);
      if (of != null && adjacency.containsKey(to)) {
        of.add(to);
      }
    }
  ```

  Imports: `com.robsartin.segue.domain.EdgeRecord`, `java.util.Collections`,
  `java.util.LinkedHashMap`, `java.util.LinkedHashSet`. Run green.

- [ ] **Step 4 — RED: the bound, all three cases at once.** Add to `NeighboursTest`:

  ```java
    @Test
    @DisplayName("a member of the population two hops away is reached and one three hops away is not")
    void shouldReachAtTwoHopsAndMissAtThreeWhenTheWalkIsBounded() {
      Map<String, Set<String>> adjacency = Neighbours.in(line());

      assertThat(Neighbours.reaches(adjacency, A, Set.of(A, C), 2))
          .as("C is exactly two hops from A")
          .isTrue();
      assertThat(Neighbours.reaches(adjacency, A, Set.of(A, D), 2))
          .as("D is three hops from A, which is past the bound")
          .isFalse();
    }

    @Test
    @DisplayName("a neighbour outside the population is walked through and never counted")
    void shouldIgnoreANeighbourWhenItIsNotInThePopulation() {
      Map<String, Set<String>> adjacency = Neighbours.in(line());

      assertThat(Neighbours.reaches(adjacency, A, Set.of(A), 2))
          .as("B and C are there, and neither is in the population")
          .isFalse();
      assertThat(Neighbours.reaches(adjacency, A, Set.of(A, B), 2))
          .as("B is, at one hop — within two")
          .isTrue();
    }

    @Test
    @DisplayName("the entity itself is never its own known neighbour")
    void shouldNotReachItselfWhenItIsTheOnlyMemberOfThePopulation() {
      Map<String, Set<String>> adjacency = Neighbours.in(line());

      assertThat(Neighbours.reaches(adjacency, E, Set.of(E), 2)).isFalse();
    }
  ```

  Run. The first assertion of the first test fails: `[C is exactly two hops from A] Expecting value
  to be true but was false`. Quote it.

- [ ] **Step 5 — GREEN: the walk, then its positive control.**

  ```java
    /**
     * Whether any member of {@code population} other than {@code from} sits within {@code hops}.
     *
     * <p>Breadth-first and short-circuiting: the question is whether anything is there, not how
     * many or how far, so the walk stops at the first hit and at the first exhausted frontier.
     *
     * <p><b>{@code from} is marked seen before the first hop</b>, so an entity is never its own
     * known neighbour — including where the log holds a self-loop, which {@code Degrees} counts
     * twice and this deliberately does not count at all.
     */
    static boolean reaches(
        Map<String, Set<String>> adjacency, String from, Set<String> population, int hops) {
      Objects.requireNonNull(adjacency, "adjacency");
      Objects.requireNonNull(from, "from");
      Objects.requireNonNull(population, "population");
      Set<String> seen = new LinkedHashSet<>();
      seen.add(from);
      Set<String> frontier = Set.of(from);
      for (int hop = 0; hop < hops; hop++) {
        Set<String> next = new LinkedHashSet<>();
        for (String at : frontier) {
          for (String neighbour : adjacency.getOrDefault(at, Set.of())) {
            if (!seen.add(neighbour)) {
              continue;
            }
            if (population.contains(neighbour)) {
              return true;
            }
            next.add(neighbour);
          }
        }
        if (next.isEmpty()) {
          return false;
        }
        frontier = next;
      }
      return false;
    }
  ```

  Run green. **Then the positive control on the bound**: change `hop < hops` to `hop <= hops`, run,
  and record that `shouldReachAtTwoHopsAndMissAtThreeWhenTheWalkIsBounded` fails on its second
  assertion (`[D is three hops from A, which is past the bound] Expecting value to be false but was
  true`). **Remove the plant**, re-run green, and `git diff` the file to confirm the loop reads
  `hop < hops`.

- [ ] **Step 6 — format, gate, commit.**

  ```
  ./gradlew spotlessApply
  SEGUE_REQUIRE_BROWSER=true SEGUE_REQUIRE_GRAPHVIZ=true ./gradlew check --rerun-tasks
  ```

  Then `git status`, stage by explicit path, commit:

  > Neighbours: the census's own adjacency and its bounded reach (#311)

---

## Task 4 — `KnownListCensus`: the two populations, counted

Files: `src/main/java/com/robsartin/segue/census/KnownListInput.java` (new),
`src/main/java/com/robsartin/segue/census/KnownListCensus.java` (new),
`src/test/java/com/robsartin/segue/census/KnownListInputTest.java` (new),
`src/test/java/com/robsartin/segue/census/KnownListCensusTest.java` (new).

**Read first.** The merge fold is asked through `fold.equivalences()`:
`canonical(qid)` for the file's ids and `resolve(ratings)` for the ratings map — the same method
`recommend` and `rate` resolve a rating through, so a merged entity's rating lands on the canonical
side before `KnownList.promoted` sees it. `KnownList.promoted` returns the file's order followed by
the promotions sorted, and it is the only composition this section may use: ADR 48 is what makes the
second sub-section "the population `recommend` and `rate` reason over" rather than a third idea.

- [ ] **Step 1 — the two stubs, so both tests compile.**

  `KnownListInput.java`:

  ```java
  package com.robsartin.segue.census;

  import java.nio.file.Path;
  import java.util.List;
  import java.util.Objects;

  /** The known-list file, as the census is allowed to hold it. */
  public record KnownListInput(String name, List<String> qids) {

    public KnownListInput {
      Objects.requireNonNull(name, "name");
      qids = List.copyOf(Objects.requireNonNull(qids, "qids"));
    }

    public static KnownListInput read(Path file) {
      Objects.requireNonNull(file, "file");
      return new KnownListInput("", List.of());
    }
  }
  ```

  `KnownListCensus.java`: the two records with their components, `of` returning two empty
  `Population`s. Write the component lists from Step 4's final code and leave the bodies empty —
  a stub is a signature, not a guess at the answer.

- [ ] **Step 2 — RED: the input carries the basename and never the path.** Create
  `KnownListInputTest`:

  ```java
  package com.robsartin.segue.census;

  import static org.assertj.core.api.Assertions.assertThat;
  import static org.assertj.core.api.Assertions.assertThatThrownBy;

  import java.nio.file.Files;
  import java.nio.file.Path;
  import org.junit.jupiter.api.DisplayName;
  import org.junit.jupiter.api.Test;
  import org.junit.jupiter.api.io.TempDir;

  /**
   * The one place the known-list file becomes a basename and a list of ids.
   *
   * <p>The basename is the whole of what the report may say about the file: a path names a
   * directory on the owner's machine, and this output is meant to be pasted (ADR 51, ADR 63).
   */
  class KnownListInputTest {

    @TempDir private Path home;

    @Test
    @DisplayName("the input carries the file's basename and not a single component of its path")
    void shouldCarryTheBasenameWhenTheFileIsRead() throws Exception {
      Path directory = Files.createDirectories(home.resolve("a-private-directory"));
      Path file = Files.writeString(directory.resolve("known.csv"), "Q0901001\nQ0901002\n");

      KnownListInput input = KnownListInput.read(file);

      assertThat(input.name()).isEqualTo("known.csv");
      assertThat(input.name()).doesNotContain("a-private-directory");
      assertThat(input.qids()).containsExactly("Q0901001", "Q0901002");
    }

    @Test
    @DisplayName("a file that is not there is refused by name rather than counted as empty")
    void shouldRefuseWhenTheFileIsNotThere() {
      assertThatThrownBy(() -> KnownListInput.read(home.resolve("absent.csv")))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("no entity list at");
    }
  }
  ```

  Run and observe the real failure on `input.name()` (`expected: "known.csv" but was: ""`).

- [ ] **Step 3 — GREEN: `KnownListInput.read`.**

  ```java
    /**
     * The file's distinct qids, and its basename.
     *
     * <p><b>The basename, never the path</b>, and that is the only reason this type exists rather
     * than a {@code Path} reaching the section. The block is meant to be pasted, and a path names a
     * directory on the owner's machine (ADR 51, ADR 63).
     *
     * <p>The reading is {@code QidList}'s, unchanged and unwrapped: the same file {@code recommend},
     * {@code rate} and {@code evaluate} take, read by the same rule — the first comma-separated
     * field on a line that is exactly a qid. A field that is not one is passed over rather than
     * refused, and a file with no qid anywhere in it is refused by {@code QidList} itself.
     */
    public static KnownListInput read(Path file) {
      Objects.requireNonNull(file, "file");
      return new KnownListInput(file.getFileName().toString(), QidList.read(file));
    }
  ```

  Import `com.robsartin.segue.support.QidList`. Run green. **Confirm no ArchUnit rule fired**:

  ```
  ./gradlew test --tests '*ArchitectureTest'
  ```

  This is Premise correction 3's check, taken against the code rather than against the argument. If
  `theCensusOpensNothingElse` or `theCensusOnlyReads` fires, **stop and report**; do not edit a rule.

- [ ] **Step 4 — RED: the counts.** Create `KnownListCensusTest` with its own small log — **not**
  `InventedCensus.log()`, whose rows are numbered and cited by every other test in the package. Five
  tests, one behaviour each:

  - `shouldCountAFileQidTheGraphLacksUnderNamedAndNotUnderInTheGraph`
  - `shouldCountAMergeOnceOnTheCanonicalSideWhenBothItsSidesAreNamed`
  - `shouldCountAnEntityAsNeverExpandedWhenNoRowCitesItAsASeed`
  - `shouldCountNoKnownNeighbourWhenTheNearestMemberIsThreeHopsAway`
  - `shouldAddExactlyThePromotionsTheRatingsMapNamesWhenTheSecondPopulationIsRead`

  The last one is the sub-section comparison the spec asks for: an invented ratings map in which one
  entity is rated at `KnownList.PROMOTION_RATING` and absent from the file, one is rated below it,
  and one rated at or above it is already named — so `named` moves by exactly one and the assertion
  names which. The third must carry a log in which **`never expanded` is strictly less than
  `in the graph`**, with one row carrying a `<qid>$…` reference; a fixture where the two are equal
  cannot tell a working rule from one that returns the whole population.

  Run and observe real assertion failures against the empty `Population`s. Quote one.

- [ ] **Step 5 — GREEN: `KnownListCensus`.**

  ```java
  package com.robsartin.segue.census;

  import com.robsartin.segue.domain.Equivalences;
  import com.robsartin.segue.domain.Expanded;
  import com.robsartin.segue.domain.Fold;
  import com.robsartin.segue.domain.KnownList;
  import com.robsartin.segue.domain.NodeKind;
  import com.robsartin.segue.domain.NodeRecord;
  import com.robsartin.segue.domain.Recommendations;
  import com.robsartin.segue.export.LogProjection;
  import java.util.Collections;
  import java.util.EnumMap;
  import java.util.LinkedHashSet;
  import java.util.List;
  import java.util.Map;
  import java.util.Objects;
  import java.util.Set;

  /**
   * How much of the owner's own list the graph actually covers (issue #311).
   *
   * <p><b>Two populations, one rule.</b> The file's entities, and the same file composed through
   * {@link KnownList#promoted} with the ratings map — which is exactly the population {@code
   * recommend} and {@code rate} reason over (ADR 48), so the second sub-section is those tools'
   * answer rather than a third idea about what "known" means. Both are read by {@link #read}, so
   * the two sub-sections cannot come to mean different things.
   *
   * <p><b>Every count is over the RESOLVED population.</b> The file's ids go through {@link
   * Equivalences#canonical} and the ratings through {@link Equivalences#resolve} before anything is
   * counted, so a merge whose two sides are both named counts once, on the canonical side — and a
   * rating the owner wrote against a local id promotes the canonical entity, which is what the deck
   * would deal.
   *
   * <p><b>A qid the file names that the fold holds no node for counts under {@code named} and not
   * under {@code in the graph}.</b> That is not a gap in this reading; it is the first coverage gap
   * there is, and it is the one the section exists to print.
   *
   * <p><b>No entity is named.</b> Every component is an integer or a map of integers, and the only
   * text is the file's basename, which {@link KnownListInput} is the one home of.
   */
  public record KnownListCensus(String file, Population fromFile, Population withPromotions) {

    public KnownListCensus {
      Objects.requireNonNull(file, "file");
      Objects.requireNonNull(fromFile, "fromFile");
      Objects.requireNonNull(withPromotions, "withPromotions");
    }

    /**
     * One population's reading.
     *
     * @param named distinct entities in the population, after the merge fold
     * @param inTheGraph of those, the ones the fold holds a node for
     * @param neverExpanded in the graph, and no row cites them as an expansion's seed ({@link
     *     Expanded})
     * @param noKnownNeighbourWithinTwoHops in the graph, and no other member of this population
     *     within {@code Recommendations.MAX_HOPS} — the recommender's own route limit, read by
     *     reference
     * @param inTheGraphByKind the same in-graph count per kind, all six emitted in {@code NodeKind}
     *     declaration order. An {@code EnumMap} rather than {@code Map.copyOf}, on {@code
     *     NodeCensus}'s reason: that factory's order is salted per JVM and ADR 43's byte-identical
     *     contract is what the order serves
     * @param neverExpandedByKind the same, for the ones nothing has expanded
     */
    public record Population(
        int named,
        int inTheGraph,
        int neverExpanded,
        int noKnownNeighbourWithinTwoHops,
        Map<NodeKind, Integer> inTheGraphByKind,
        Map<NodeKind, Integer> neverExpandedByKind) {

      public Population {
        inTheGraphByKind = byKind(inTheGraphByKind, "inTheGraphByKind");
        neverExpandedByKind = byKind(neverExpandedByKind, "neverExpandedByKind");
      }

      private static Map<NodeKind, Integer> byKind(Map<NodeKind, Integer> counts, String name) {
        Objects.requireNonNull(counts, name);
        Map<NodeKind, Integer> copy = new EnumMap<>(NodeKind.class);
        copy.putAll(counts);
        return Collections.unmodifiableMap(copy);
      }
    }

    /**
     * @param known the file, as a basename and a list of ids
     * @param expanded this log's one answer to who has been expanded
     * @param fold this census's one fold (#246) — its equivalences are the only thing read here
     * @param ratings the note-free bulk read, unresolved; this method resolves it
     */
    public static KnownListCensus of(
        KnownListInput known,
        Expanded expanded,
        LogProjection projection,
        Fold fold,
        Map<String, Integer> ratings) {
      Objects.requireNonNull(known, "known");
      Objects.requireNonNull(expanded, "expanded");
      Objects.requireNonNull(projection, "projection");
      Objects.requireNonNull(fold, "fold");
      Objects.requireNonNull(ratings, "ratings");

      Equivalences merges = fold.equivalences();
      List<String> fromFile = canonical(known.qids(), merges);
      // KnownList.promoted appends the ratings map's own keys, which resolve() has already moved
      // onto their canonical side, so nothing here canonicalises a second time.
      List<String> withPromotions = KnownList.promoted(fromFile, merges.resolve(ratings));
      Map<String, Set<String>> adjacency = Neighbours.in(projection);
      return new KnownListCensus(
          known.name(),
          read(fromFile, expanded, projection, adjacency),
          read(withPromotions, expanded, projection, adjacency));
    }

    /** The file's ids on their canonical side, de-duplicated, in the file's own order. */
    private static List<String> canonical(List<String> qids, Equivalences merges) {
      Set<String> resolved = new LinkedHashSet<>();
      for (String qid : qids) {
        resolved.add(merges.canonical(qid));
      }
      return List.copyOf(resolved);
    }

    /** One population's figures, by one rule rather than two — {@code DegreeCensus}'s shape. */
    private static Population read(
        List<String> population,
        Expanded expanded,
        LogProjection projection,
        Map<String, Set<String>> adjacency) {
      Set<String> members = Set.copyOf(population);
      Map<NodeKind, Integer> inTheGraphByKind = zeroed();
      Map<NodeKind, Integer> neverExpandedByKind = zeroed();
      int inTheGraph = 0;
      int neverExpanded = 0;
      int alone = 0;
      for (String qid : population) {
        NodeRecord node = projection.nodes().get(qid);
        if (node == null) {
          continue;
        }
        inTheGraph++;
        inTheGraphByKind.merge(node.kind(), 1, Integer::sum);
        if (!expanded.covers(qid)) {
          neverExpanded++;
          neverExpandedByKind.merge(node.kind(), 1, Integer::sum);
        }
        if (!Neighbours.reaches(adjacency, qid, members, Recommendations.MAX_HOPS)) {
          alone++;
        }
      }
      return new Population(
          population.size(), inTheGraph, neverExpanded, alone, inTheGraphByKind, neverExpandedByKind);
    }

    private static Map<NodeKind, Integer> zeroed() {
      Map<NodeKind, Integer> counts = new EnumMap<>(NodeKind.class);
      for (NodeKind kind : NodeKind.values()) {
        counts.put(kind, 0);
      }
      return counts;
    }
  }
  ```

  Run `./gradlew test --tests 'com.robsartin.segue.census.KnownListCensusTest'` green, and record the
  test count.

- [ ] **Step 6 — format, gate, commit.**

  ```
  ./gradlew spotlessApply
  SEGUE_REQUIRE_BROWSER=true SEGUE_REQUIRE_GRAPHVIZ=true ./gradlew check --rerun-tasks
  ```

  `theCensusFoldsOnce` must stay green: nothing here calls `Fold.of`, `Retractions.in` or any of the
  five `Equivalences` factories — it reads the fold it was handed. Confirm that in the task report.
  Then `git status`, stage by explicit path, commit:

  > KnownListCensus: two populations of the owner's own list, counted (#311)

---

## Task 5 — the section in the block, with the no-flag pin unchanged

Files: `src/main/java/com/robsartin/segue/census/Census.java`,
`src/main/java/com/robsartin/segue/census/CensusReport.java`,
`src/main/java/com/robsartin/segue/census/CensusRun.java`,
`src/test/java/com/robsartin/segue/census/InventedCensus.java`,
`src/test/java/com/robsartin/segue/census/CensusReportTest.java`.

**Read first, and this is the load-bearing part of the task.** `CensusReport` derives both column
widths from the census it is given. The widest label today is `  merges superseded but edge-referenced`
(39 characters including the two-space indent) and the widest count is three digits. The new rows are
indented four and the widest of them, `    no known neighbour within two hops`, is **38** — one short
— and no new count reaches three digits on the fixture. **So the with-flag block should be the
no-flag block with the new section appended and nothing re-padded.** If you find the existing lines
moving, the padding rule is the authority and the finding goes in the task report before anything is
changed.

- [ ] **Step 1 — two reference shapes into the fixture, changing no existing count.** In
  `InventedCensus`, add two factories beside `sourced()`:

  ```java
    /**
     * A forward claim's reference: the Wikidata statement id, which begins with the subject's qid
     * and a {@code $}. The source id and confidence are {@link #sourced()}'s, so no count that reads
     * either of those moves when a row is given this instead.
     */
    static Provenance expandedFrom(String seedQid) {
      return new Provenance("invented", seedQid + "$0000-invented", WHEN, 1.0);
    }

    /** A reverse-discovered edge's reference, in {@code ReverseClaims}' own shape. */
    static Provenance discoveredFrom(String otherQid, String seedQid) {
      return new Provenance("invented", "wdqs:" + otherQid + ":P0000:" + seedQid, WHEN, 1.0);
    }
  ```

  Then change exactly two rows of `log()`, keeping their position and every other argument:

  ```java
          edge(WREN, HOLLOW, "MEMBER_OF", expandedFrom(WREN)),
  ```
  (the first of the two corroborating `WREN → HOLLOW` rows, currently `sourced()`), and

  ```java
          edge(NEIGHBOUR, HOLLOW, "MEMBER_OF", discoveredFrom(NEIGHBOUR, HOLLOW)),
  ```

  Extend `log()`'s javadoc with one sentence saying that two rows carry an expansion-shaped
  reference — `WREN` as a forward seed and `HOLLOW` as a reverse one — so the known-list section's
  `never expanded` row is not the whole population.

- [ ] **Step 2 — prove the fixture change moved nothing.**

  ```
  ./gradlew test --tests 'com.robsartin.segue.census.*'
  ```

  Expected: **green, with no change at all**. `sourceRef` is counted by nothing — `EdgeCensus`
  counts source ids, edge types and corroboration, and both new references keep the source id and
  confidence `sourced()` had. If anything fails, that is the finding: stop, quote it, and do not
  adjust an expectation to fit.

- [ ] **Step 3 — the eighth component on `Census`, and the plumbing that keeps the flag absent.**
  Add to `Census`:

  ```java
  public record Census(
      NodeCensus nodes,
      EdgeCensus edges,
      ClaimCensus claims,
      TasteCensus taste,
      DegreeCensus degree,
      BridgeCensus bridge,
      ConceptClassCensus conceptClasses,
      Optional<KnownListCensus> knownList) {
  ```

  with `Objects.requireNonNull(knownList, "knownList");` in the compact constructor, and change
  `of`:

  ```java
    /**
     * Fold once, read once, count seven ways — eight when a known-list file was named.
     *
     * <p><b>The eighth section is optional because the flag is</b>, and that is what keeps the
     * no-flag block byte-identical to the block this tool printed before issue #311:
     * {@code CensusReport} adds no line for an empty one, so neither column width moves.
     *
     * @param known the known-list file, or empty where {@code --known} was not given
     */
    public static Census of(
        AssertionLog log, AffinityStore ratings, Optional<KnownListInput> known) {
      Objects.requireNonNull(log, "log");
      Objects.requireNonNull(ratings, "ratings");
      Objects.requireNonNull(known, "known");
      List<LoggedAssertion> logged = log.readAll();
      Fold fold = Fold.of(logged, KindMapper::rederive);
      LogProjection projection = LogProjection.of(logged, fold);
      Map<String, Integer> scores = ratings.readRatings();
      return new Census(
          NodeCensus.of(projection),
          EdgeCensus.of(projection),
          ClaimCensus.of(logged, projection, fold),
          TasteCensus.of(scores, fold, projection),
          DegreeCensus.of(projection),
          BridgeCensus.of(projection),
          ConceptClassCensus.of(projection),
          known.map(file -> KnownListCensus.of(file, Expanded.in(logged), projection, fold, scores)));
    }
  ```

  Note that `readRatings` is now called once and shared, where it was called inline before — the
  same answer, read once, and the two sections cannot disagree about it. `Expanded.in` is built
  **inside the `map`**, so a run without the flag does not walk the rows for it at all.

  In `CensusRun.run`, pass `Optional.empty()` for now and leave the signature alone. In
  `CensusReportTest`, add `Optional.empty()` as the eighth argument to the existing
  `new Census(...)`. Compile and run the census package green — **this half has no red, because it
  changes no behaviour**, and the task report says so in those words.

- [ ] **Step 4 — RED: the with-flag block, with the no-flag pin as its control.** In
  `CensusReportTest`, leave `shouldRenderTheWholeCensusWhenTheFixtureIsCounted` **exactly as it is**
  — that unchanged pin is the control proving the no-flag block did not move — and add a second
  test beside it. Extract the fixture's census-building into a private helper taking
  `Optional<KnownListInput>` so both tests share it and the ratings map is written once.

  The known file for the pin, in the file's own order:

  ```java
    private static final KnownListInput KNOWN =
        new KnownListInput(
            "known.csv",
            List.of(
                InventedCensus.WREN,
                InventedCensus.HOLLOW,
                InventedCensus.UNCLAIMED,
                InventedCensus.LEDGER,
                InventedCensus.CORRECTED,
                InventedCensus.REROUTED));
  ```

  It is chosen so that every case the section exists to report is in it and none is vacuous:
  `UNCLAIMED` is named and is not a node; `LEDGER` and `CORRECTED` are the two sides of one merge;
  `REROUTED` is the stand-in with no edge, so it is in the graph and has no known neighbour at any
  distance; `WREN` and `HOLLOW` are the two Step 1 made expanded.

  **Derive every count by hand from the fixture before running anything, and write the derivation
  into the task report.** This plan's own derivation is below as a cross-check and **not as an
  oracle** — if yours differs, hand-count again from `InventedCensus.log()` before touching either
  side:

  | row | `file` | `file and promotions` |
  | --- | --- | --- |
  | `named` | 5 | 7 |
  | `in the graph` | 4 | 6 |
  | `never expanded` | 2 | 4 |
  | `no known neighbour within two hops` | 1 | 1 |
  | `PERSON in the graph` / `never expanded` | 1 / 0 | 1 / 0 |
  | `GROUP in the graph` / `never expanded` | 1 / 0 | 1 / 0 |
  | `WORK in the graph` / `never expanded` | 2 / 2 | 4 / 4 |
  | `PLACE`, `EVENT`, `CONCEPT` | 0 / 0 | 0 / 0 |

  The reasoning behind it, to check against: the file's six ids resolve to five (`LEDGER` folds onto
  `CORRECTED`); `UNCLAIMED` is never claimed as a node, so four are in the graph; `WREN` and
  `HOLLOW` are expanded, leaving `CORRECTED` and `REROUTED` never expanded and both `WORK`;
  `REROUTED` has no edge at all. The promotions the ratings map adds are the two entities rated at
  or above `KnownList.PROMOTION_RATING` that the file does not name, after resolution — both `WORK`,
  both in the graph, neither expanded, each within two hops of another member.

  The expected text is the existing block plus, at the foot:

  ```
  known list — known.csv

    file
      named                                  5
      in the graph                           4
      …
    file and promotions
      named                                  7
      …
  ```

  **Do not copy that indentation from this plan.** Apply `CensusReport`'s own rule — labels padded to
  the widest counted label, two spaces, count right-aligned in the widest count's width — to the
  fixture, exactly as the existing test's javadoc says it does. A blank line precedes each heading,
  as it does for every other section.

  Run `./gradlew test --tests 'com.robsartin.segue.census.CensusReportTest'`. Expected: the new test
  fails on a text diff whose whole content is the missing section, and **the old test passes
  untouched**. Quote both. If the old test fails, the column moved and the task's premise is wrong —
  report it.

- [ ] **Step 5 — GREEN: print the section.** In `CensusReport.body`, after the `concept classes`
  block:

  ```java
      census
          .knownList()
          .ifPresent(
              known -> {
                // The file's basename, which KnownListInput is the one home of: the block is meant
                // to be pasted, and a path names a directory on the owner's machine.
                body.add(section("known list — " + known.file()));
                body.add(subSection("file"));
                rows(body, known.fromFile());
                body.add(subSection("file and promotions"));
                rows(body, known.withPromotions());
              });
  ```

  and the three helpers:

  ```java
    /** A population's rows, so the two sub-sections read straight down against each other. */
    private static void rows(List<Line> body, KnownListCensus.Population population) {
      body.add(nested("named", population.named()));
      body.add(nested("in the graph", population.inTheGraph()));
      body.add(nested("never expanded", population.neverExpanded()));
      body.add(nested("no known neighbour within two hops", population.noKnownNeighbourWithinTwoHops()));
      for (NodeKind kind : NodeKind.values()) {
        String of = kind.name() + " ";
        body.add(nested(of + "in the graph", population.inTheGraphByKind().get(kind)));
        body.add(nested(of + "never expanded", population.neverExpandedByKind().get(kind)));
      }
    }

    private static Line subSection(String name) {
      return new Line("  " + name, null);
    }

    private static Line nested(String label, int value) {
      return new Line("    " + label, value);
    }
  ```

  Extend the class javadoc with one paragraph: the known-list section prints only when the flag was
  given, which is what makes the no-flag block byte-identical to the one this class printed before
  issue #311 — an absent section adds no `Line`, so neither width derived from `body` can move; and
  the basename is the second piece of text this class interpolates that is not a count, after the
  vocabulary named above, and it comes from the command line rather than from the data.

  Run the census package green.

- [ ] **Step 6 — the safe-to-paste guard still holds, and prove it can still fail.** Run:

  ```
  ./gradlew test --tests 'com.robsartin.segue.census.CensusIsSafeToPasteTest'
  ```

  Green — it drives `CensusCli.main` with no `--known`, so it exercises the no-flag path. Then the
  control: temporarily change `subSection("file")` to `subSection("file " + InventedCensus.WREN)`
  — no, that is a test constant; instead temporarily append a literal ` Q0900901` to the
  `known list — ` heading in `CensusReport`, add a `--known` run to a scratch copy of the test, and
  confirm the clause fires. **If that is more machinery than the control is worth, say so in the
  task report and record instead that `CensusIsSafeToPasteTest` does not cover the flagged path at
  all** — which is the honest statement, and Task 6 Step 5 is where the flagged path gets its own
  assertion.

- [ ] **Step 7 — format, gate, commit.**

  ```
  ./gradlew spotlessApply
  SEGUE_REQUIRE_BROWSER=true SEGUE_REQUIRE_GRAPHVIZ=true ./gradlew check --rerun-tasks
  ```

  Re-read `CensusReport`'s and `Census`'s javadoc after `spotlessApply`. Then `git status`, stage by
  explicit path, commit:

  > Print the known-list section, and leave the no-flag block byte-identical (#311)

---

## Task 6 — `--known` on the command line, end to end

Files: `src/main/java/com/robsartin/segue/census/CensusCli.java`,
`src/main/java/com/robsartin/segue/census/CensusRun.java`,
`src/test/java/com/robsartin/segue/census/CensusCliTest.java`,
`src/test/java/com/robsartin/segue/census/CensusRunTest.java`,
`build.gradle.kts`.

**Read first.** `CensusCli.parse` must not touch the filesystem for `--known`:
`DeveloperGuideCensusExamplesTest` runs every guide example through `parse` precisely because it
opens nothing, and Task 8 adds an example naming a file that does not exist on any machine. The
file is read in `run`, after the database check, so the two refusals keep their order and a third
joins the end of it.

- [ ] **Step 1 — RED: the flag is parsed and carried.** Add to `CensusCliTest`:

  ```java
    @Test
    @DisplayName("--known is optional, and absent it leaves the options carrying no known list")
    void shouldCarryNoKnownListWhenTheFlagIsAbsent() throws Exception {
      Path named = Files.createFile(home.resolve("named.db"));

      CensusCli.Options options =
          CensusCli.parse(new String[] {"--db", named.toString()}, null, home.toString());

      assertThat(options.known()).isEmpty();
    }

    @Test
    @DisplayName("--known is carried as given and no file is opened to parse it")
    void shouldCarryTheKnownListWhenTheFlagIsGiven() throws Exception {
      Path named = Files.createFile(home.resolve("named.db"));
      Path absent = home.resolve("never-written.csv");

      CensusCli.Options options =
          CensusCli.parse(
              new String[] {"--db", named.toString(), "--known", absent.toString()},
              null,
              home.toString());

      assertThat(options.known()).contains(absent);
      assertThat(absent).as("parse opens nothing, so the guide's examples can name a file").doesNotExist();
    }
  ```

  Run and observe the compile boundary first: `Options` has no `known()` yet, so **add the stub
  before the test** — a second component `Optional<Path> known` defaulted to `Optional.empty()` in
  `parse` — then run and observe the real assertion failure on `contains(absent)`.

- [ ] **Step 2 — GREEN: parse it.** In `CensusCli`:

  ```java
    private static final String USAGE = "usage: --db <segue.db> [--known <file of QIDs>]";
  ```

  ```java
    /**
     * The database to count, and the known-list file to count it against.
     *
     * @param database no default, on purpose — see this class's Javadoc, and {@code
     *     support.RequiredDatabase}, which owns the refusal sentence
     * @param known the same file {@code recommend}, {@code rate} and {@code evaluate} take, or
     *     empty. <b>Optional where {@code --db} is required</b>, and the asymmetry is the point:
     *     the database decides whether this runs at all, and this decides whether one section is
     *     printed. <b>It is not opened here</b> — {@code parse} refuses what could not work before
     *     any file is touched, and {@code DeveloperGuideCensusExamplesTest} runs the runbook's
     *     examples through it for exactly that reason
     */
    public record Options(Path database, Optional<Path> known) {

      public Options {
        Objects.requireNonNull(database, "database");
        Objects.requireNonNull(known, "known");
      }
    }
  ```

  and in the loop:

  ```java
        if ("--db".equals(flag)) {
          database = Path.of(value);
        } else if ("--known".equals(flag)) {
          known = Path.of(value);
        } else {
          throw usage("unknown option " + flag);
        }
  ```

  returning `new Options(database, Optional.ofNullable(known))`. Run green.

- [ ] **Step 3 — RED: the run reads the file and the block gains the section.** Add to
  `CensusRunTest` a test that writes a known-list file under the `@TempDir` naming two of
  `InventedCensus`'s ids, runs, and asserts that the emitted lines contain a line starting
  `known list — ` and that the census carries a present `knownList()` whose `fromFile().named()` is
  2 — and a second test with no file asserting `knownList()` is empty and **no** line starts
  `known list`. Run and observe the failure (the run signature does not take a file yet: add the
  stub parameter first, ignoring it, then the assertion failure is real).

- [ ] **Step 4 — GREEN: wire it.** `CensusRun.run` becomes:

  ```java
    /**
     * Count the graph and emit the report.
     *
     * @param known the known-list file, or empty. <b>Read here rather than in the CLI</b>, so the
     *     one place a path becomes a basename and a list of ids is {@link KnownListInput} and a
     *     test can exercise it without a command line
     * @return the census that was printed, so a caller can assert on the numbers without parsing
     *     the text back
     */
    public Census run(Consumer<String> lines, Optional<Path> known) {
      Objects.requireNonNull(lines, "lines");
      Objects.requireNonNull(known, "known");
      Census census = Census.of(log, ratings, known.map(KnownListInput::read));
      CensusReport.lines(census).forEach(lines);
      return census;
    }
  ```

  and `CensusCli.run`'s last line becomes
  `new CensusRun(assertions, ratings).run(log::info, options.known());`. Run green.

- [ ] **Step 5 — RED: the flagged block is safe to paste too.** Add one test to
  `CensusIsSafeToPasteTest` that drives `CensusCli.main` with `--known` pointing at a file it writes
  under its own `@TempDir` — naming the same two invented ids the existing fixture claims — and
  applies the **unchanged** `carriesAnIdItMayNot` clause to every captured line, plus a non-vacuity
  assertion that a line starting `known list — ` was printed. **The known-list file's basename must
  not be qid-shaped**; call it `known.csv`. Run: it should pass. Then the positive control — append
  a literal ` Q0900901` to the section heading in `CensusReport`, watch this test fail on the "no
  line carries anything qid-shaped" clause, and remove the plant. Quote both runs and `git diff`
  `CensusReport.java` afterwards to confirm the plant is gone.

- [ ] **Step 6 — the Gradle task description.** In `build.gradle.kts`, extend `graphCensus`'s
  `description` with one clause: `--known <file>` is optional and adds one section saying how much
  of that list the graph covers; the file is the same one `recommend` and `rate` take, it is
  personal data, and only its basename reaches the output. Keep the existing sentences verbatim.

- [ ] **Step 7 — format, gate, commit.**

  ```
  ./gradlew spotlessApply
  SEGUE_REQUIRE_BROWSER=true SEGUE_REQUIRE_GRAPHVIZ=true ./gradlew check --rerun-tasks
  ```

  Then `git status`, stage by explicit path, commit:

  > graphCensus --known: read the owner's own list and count the graph against it (#311)

---

## Task 7 — What the walk costs, measured once on a synthetic graph

Files: `src/test/java/com/robsartin/segue/census/KnownListCensusScaleTest.java` (new),
`docs/developer-guide.md`.

**This task asserts correctness at scale and nothing about time.** The machine is loaded, so a
duration assertion would be a flake generator; what it produces is an **observation**, recorded in
the task report and written into the runbook as an order of magnitude in prose.

- [ ] **Step 1 — build the synthetic graph in a test.** A generated log of the real graph's *shape*
  — nodes in the low six figures, edges a small multiple of that, degrees skewed so a few nodes
  carry hundreds and most carry none — with a known-list population in the high hundreds drawn from
  it. Every id is generated with ADR 58's leading zero (`"Q09" + String.format("%07d", i)` or
  similar) so `StandInQidsDenoteNothingTest` stays green; **check that the generated form carries
  the leading zero for every `i` in range before running the sweep**, because that test reads string
  literals and cannot see a generated id at all — a generator that produces an allocatable id is
  invisible to it, which is the hole this instruction closes.

- [ ] **Step 2 — assert what is true at scale, and nothing else.** The population's counts
  reconcile: `named` is the population's size, `in the graph` is at most `named`, `never expanded`
  and `no known neighbour within two hops` are each at most `in the graph`, the per-kind in-graph
  counts sum to `in the graph`, and the per-kind never-expanded counts sum to `never expanded`. Plus
  one planted fact the generator knows — an isolated member the walk must report, and a member two
  hops from another it must not. **No timing assertion of any kind.**

- [ ] **Step 3 — run it, and observe the time.**

  ```
  ./gradlew test --tests 'com.robsartin.segue.census.KnownListCensusScaleTest'
  ```

  Read the duration off `build/reports/tests/test/classes/…KnownListCensusScaleTest.html` — and
  separate the **generation** of the synthetic log from the **census** of it, by timing them apart
  with two `System.nanoTime()` reads printed to the test's own output. Only the second is the
  measurement; a slow generator says nothing about the flag. Record both in the task report, with
  the node and edge counts they were taken at.

- [ ] **Step 4 — write the order of magnitude into the runbook, as prose.** One sentence in
  `docs/developer-guide.md`'s census chapter, in the shape the spec asks for: the flag adds one scan
  for the expansion rule and one bounded neighbourhood read per known entity, against a fold that
  already dominates the run, so it costs **seconds** rather than minutes; if a run takes
  meaningfully longer than that, the walk is the thing to look at rather than the flag. **No figure,
  and nothing from the owner's graph** — the measurement was taken on a synthetic graph and the
  sentence says so.

- [ ] **Step 5 — format, gate, commit.**

  ```
  ./gradlew spotlessApply
  SEGUE_REQUIRE_BROWSER=true SEGUE_REQUIRE_GRAPHVIZ=true ./gradlew check --rerun-tasks
  ```

  Note the gate's own runtime before and after in the task report — a scale test that adds a minute
  to every CI run is a cost worth stating, and if it does, say so and propose the smaller graph
  rather than keeping it quiet. Then `git status`, stage by explicit path, commit:

  > Measure the known-list walk on a synthetic graph and say what to expect (#311)

---

## Task 8 — ADR 63's amendment, and the runbook

Files: `docs/adr/0063-a-read-only-census-of-the-graph.md`, `docs/developer-guide.md`.

**Read first.** An ADR is immutable: this appends a dated amendment at the end and edits nothing
above it. `AdrCitationsTest` matches a backticked run of 7–40 hex characters, so **no qid, no commit
hash and no `.superpowers/` path may appear in the ADR file**, and no figure from the owner's graph
may appear anywhere in this task.

- [ ] **Step 1 — the amendment, appended to `docs/adr/0063-a-read-only-census-of-the-graph.md`.**
  It must say, in the ADR's own voice and at the ADR's own length:

  - **What is added**, dated and attributed: *Amendment (2026-09-12, issue #311): the census takes
    an optional `--known <file>` and prints one further section, `known list`.*
  - **What the input is**: the same known-list file `recommend`, `rate` and `evaluate` take, read
    through the shared `QidList`. It is personal data — a list of who the owner listens to, reads
    and watches — and it lives outside this repository.
  - **Why that is within this decision rather than against it**: what the section prints is counts
    over that list, which is exactly the aggregate ADR 51 permits; no entity is named, every value
    is an integer, and the one piece of text is the file's **basename**, never its path. This
    decision's own guarantee is unchanged, and `CensusIsSafeToPasteTest` now covers the flagged path
    as well as the plain one.
  - **What the section answers, and why nothing else answers it**: the evaluation harness (ADR 65)
    scores only entities the owner has rated, so an entity on the list that the graph has never
    expanded, or that connects to nothing else on the list, is invisible to every current reading.
  - **The two populations**, and why the second is `KnownList.promoted` rather than a third idea
    (ADR 48: it is the population `recommend` and `rate` reason over).
  - **Where "expanded" comes from**: the provenance the log already holds, in two shapes, one rule
    in `domain` with a second caller coming. Say that it does not read the MusicBrainz adapter's
    references and why that errs in the safe direction.
  - **One constant moved**: `MAX_HOPS` now lives in `domain` beside the floor this census already
    reads, because `theCensusOnlyReads` fences `census` to one sibling. The alternative — a second
    literal — is the copy this project's rules exist to prevent.
  - **The residual, stated rather than mitigated**: a basename is text the owner typed, so a
    known-list file named after an entity would put that name in the block. Nothing hides it; `--db`
    and `--known` are both typed per invocation because whether to publish is the owner's decision,
    taken each time.

- [ ] **Step 2 — the runbook chapter.** In `docs/developer-guide.md`, "Looking at the shape of your
  graph":

  - add the flagged form to the opening code block:

    ```bash
    # the same counts, plus how well the graph covers your own list
    ./gradlew graphCensus --args="--db $HOME/.segue/segue.db --known $HOME/known.csv"
    ```

    Write `$HOME`, not `~` — `DeveloperGuideCensusExamplesTest.shouldWriteHomeRatherThanATildeWhenACensusExampleNamesADatabase`
    fails on a tilde, and the same test parses this line through `CensusCli.parse`.
  - add a fifth bullet to "What it is for": which of the owner's own list the graph has never
    expanded, and which of it connects to nothing else on it — the question the harness cannot ask,
    because it scores only what has been rated.
  - a short "What the two sub-sections mean" passage: `file` is what the file names, resolved
    through the merge fold; `file and promotions` is that plus everything rated at or above
    `KnownList.PROMOTION_RATING` that the file does not name, which is the population `recommend` and
    `rate` reason over (ADR 48) — so the difference between the two rows is what promotion adds.
    Say that `named` minus `in the graph` is the file naming something segue has never seen, which
    is the first coverage gap there is.
  - one sentence in "Why the output is safe to paste": the flag adds one piece of non-integer text,
    the file's **basename**, and never the path.
  - the cost sentence from Task 7 Step 4, if it is not already placed there.
  - **Do not list the rows.** `CensusReport` is the authority on which counts are emitted and in
    what order, and this chapter already says so; a second copy here goes stale on its own.

- [ ] **Step 3 — the dev-tool table row.** Extend the `census` row (around line 530) so it names the
  optional `--known` and the `known list` section. **The "Depends on" column does not change** —
  `support` and `domain` are already in it, and nothing new is reached. Check
  `DeveloperGuideEnumerationsTest` is green after the edit; it derives that column against the
  source.

- [ ] **Step 4 — run the document tests explicitly.**

  ```
  ./gradlew test --tests '*DeveloperGuide*' --tests '*AdrCitationsTest' --tests '*AdrIndexTest' --tests '*DocumentationLinksTest'
  ```

  Green, with non-zero counts for each. Record them.

- [ ] **Step 5 — format, gate, commit.**

  ```
  ./gradlew spotlessApply
  SEGUE_REQUIRE_BROWSER=true SEGUE_REQUIRE_GRAPHVIZ=true ./gradlew check --rerun-tasks
  ```

  Run it **without** `--rerun-tasks` first, and confirm the suite actually re-ran rather than
  reporting `UP-TO-DATE` — `docs/` is a declared input and this is the task that proves it. Then
  `git status`, stage by explicit path, commit:

  > ADR 63's amendment and the runbook for graphCensus --known (#311)

---

## What this plan deliberately does not do

- **It expands nothing.** The expander is the second issue, gated on what this prints.
- **It changes no ArchUnit rule.** Premise correction 3 is the check, and Task 4 Step 3 is where it
  is taken against the code rather than against the argument.
- **It changes nothing about the evaluation harness**, and it takes no reading of the owner's graph.
- **It names no entity anywhere** — not in the block, not in a document, not in a commit message.
