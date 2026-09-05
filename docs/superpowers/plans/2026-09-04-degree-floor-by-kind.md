# Report the degree floor over the kinds the recommender can offer — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to
> implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** the census's degree section reads its figures by `NodeKind` as well as over the whole
graph, and reports the floor's bite as a count *and* a whole percent in both, so the floor is judged
against the population `CandidateSweep` can actually offer.

**Architecture:** one record gains one component and one nested type; one renderer gains lines
inside a section it already prints. `FloorReading`, `CandidateSweep`, `Degrees`, `Census` and every
other census section are untouched.

**Tech Stack:** Java (toolchain 25, `release 21`), JUnit 5, AssertJ, ArchUnit, JaCoCo. Markdown for
the ADR amendment and the developer guide.

**Spec:** `docs/superpowers/specs/2026-09-04-degree-floor-by-kind-design.md` — it holds the design,
the reasoning and the alternatives rejected. **Cite it; never restate its reasoning.** Where this
plan and the spec appear to differ, the spec wins and the divergence is a finding to report.

---

## Global Constraints

- **Pure TDD.** Failing test first, **run it and observe a real assertion failure** — a compile error
  is not a red. Java cannot compile a test that names an accessor which does not exist, so where a
  step needs one, it adds the component wired to an obviously-wrong constant **as a compilation
  scaffold** and the red is the assertion failure that constant produces. Every step below that does
  this says so out loud, and the report must quote the failure text.
- **Every guard and every new rule gets a positive control**: plant the defect, run, watch the check
  fire, quote it, remove the plant, re-run green. The plants are written out below; none is ever
  committed.
- Test names `should<Expected>When<Condition>` with `@DisplayName`.
- **Mikado: green at every committed step.** Nothing is committed with a plant in place or a test
  red.
- **Never read, write, copy or create `~/.segue/segue.db`.** Never run `own`, `ownClaim`,
  `retractEntity`, `rate`, or any other writing dev task. **`graphCensus` is not run at all** in this
  work — no step needs it, and the only database it may ever be pointed at is a `@TempDir` one
  created by a test.
- **Invented identifiers only** in anything committed (ADR 58, ADR 51). No real entity, no real
  figure, nothing derived from the owner's data.
- **Keep the edits where this plan puts them.** `DegreeCensus`, `CensusReport`'s degree block,
  `DegreeCensusTest`, `CensusReportTest`, the ADR 63 amendment and one developer-guide bullet.
  **Do not add a census section, do not touch `Census`, and do not edit `CensusReport`'s class
  Javadoc** — issue #248 is in flight against this same base and adds a section there; keeping off
  that surface is what lets the two merge.
- **`CensusIsSafeToPasteTest` is not edited.** It must stay green untouched; if it goes red, that is
  the finding.
- **ADRs are append-only.** The amendment is appended at the end of the file. Front matter
  (`status`, `date`, `topic`, `tags`, `supersedes`, `related`) is **not touched**, no line above the
  amendment is edited, and `docs/adr/README.md` is **not** touched — an amendment changes no ADR's
  number, title or status, which is all `AdrIndexTest` compares.
- **Never cite a `.superpowers/` path from a committed file.**
- **Never `git add -A`.** Stage every file by explicit path, with git's stderr visible (never
  `2>/dev/null`), and read `git status` before committing. One committer in this worktree.
- Commit messages end with a blank line then
  `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`.
- **Gate, run BLOCKING** (never backgrounded), at the end of every task:
  `SEGUE_REQUIRE_BROWSER=true SEGUE_REQUIRE_GRAPHVIZ=true ./gradlew check --rerun-tasks`
  Plain `./gradlew`; JDK 25 is the only JDK. If `spotlessCheck` fails run `./gradlew spotlessApply`
  and re-run the gate. `check` includes `javadoc` with `-Werror` and every doclint group but
  `missing`, so a malformed `@param` or a `{@link}` naming something that does not exist fails the
  build.
- **The fixture's hand-counted figures.** `InventedCensus.log()` folds to thirteen nodes whose
  degrees, by kind, are: `PERSON` `[2, 2, 6]`, `GROUP` `[2]`, `WORK` `[0, 0, 0, 0, 1, 1, 1, 5]`,
  `PLACE` `[]`, `EVENT` `[]`, `CONCEPT` `[2]`. Those thirteen are the whole graph's
  `[0, 0, 0, 0, 1, 1, 1, 2, 2, 2, 2, 5, 6]`, which `DegreeCensusTest`'s Javadoc already counts, and
  the kind of each is `NodeCensusTest`'s already-pinned `PERSON 3, GROUP 1, WORK 8, PLACE 0,
  EVENT 0, CONCEPT 1`. **If a run disagrees with any figure below, stop and report it** — the two
  existing tests say which side is wrong.

---

## Task 1: `DegreeCensus` reads the distribution by kind

### Step 1 — RED: the six kinds' figures

- [ ] Add to `src/test/java/com/robsartin/segue/census/DegreeCensusTest.java` (add the static import
      `org.assertj.core.api.Assertions.entry`, as `NodeCensusTest` has; `NodeKind` is already
      imported and no `java.util.Map` import is needed):

```java
  @Test
  @DisplayName("each kind's distribution is read over that kind's own nodes")
  void shouldReadEachKindsOwnDistributionWhenTheKindsDifferFromTheWhole() {
    assertThat(CENSUS.byKind())
        .as(
            "PERSON is [2, 2, 6] and WORK is [0, 0, 0, 0, 1, 1, 1, 5]; the whole graph's p50 of 1 is"
                + " a degree neither kind's median has")
        .containsExactly(
            entry(NodeKind.PERSON, new DegreeCensus.KindDegrees(2, 6, 6, 6, 2)),
            entry(NodeKind.GROUP, new DegreeCensus.KindDegrees(2, 2, 2, 2, 1)),
            entry(NodeKind.WORK, new DegreeCensus.KindDegrees(0, 5, 5, 5, 8)),
            entry(NodeKind.PLACE, new DegreeCensus.KindDegrees(0, 0, 0, 0, 0)),
            entry(NodeKind.EVENT, new DegreeCensus.KindDegrees(0, 0, 0, 0, 0)),
            entry(NodeKind.CONCEPT, new DegreeCensus.KindDegrees(2, 2, 2, 2, 1)));
  }
```

- [ ] **Compilation scaffold, said out loud.** Add to `DegreeCensus` the nested record and the new
      component, and wire `of` to a value that cannot be right — every kind reading as zeros. This is
      scaffolding so the test compiles; it is not an implementation, and the red it produces is an
      assertion failure on a value.

```java
  /** One kind's population, read by the rules the whole graph is read by. */
  public record KindDegrees(int p50, int p90, int p99, int max, int atOrBelowTheFloor) {}
```

  Add `Map<NodeKind, KindDegrees> byKind` as the record's last component, add the compact
  constructor below it, and in `of` build the scaffold map:

```java
  public DegreeCensus {
    Objects.requireNonNull(byKind, "byKind");
    // new EnumMap<>(map) refuses an empty map it cannot infer the key type from; the class
    // constructor plus putAll takes one, and no caller has to know that.
    Map<NodeKind, KindDegrees> copy = new EnumMap<>(NodeKind.class);
    copy.putAll(byKind);
    byKind = Collections.unmodifiableMap(copy);
  }
```

```java
    Map<NodeKind, KindDegrees> byKind = new EnumMap<>(NodeKind.class);
    for (NodeKind kind : NodeKind.values()) {
      byKind.put(kind, new KindDegrees(0, 0, 0, 0, 0)); // scaffold, replaced in step 2
    }
```

  and pass `byKind` as the last argument to the existing `new DegreeCensus(...)`. Imports to add:
  `com.robsartin.segue.domain.NodeKind`, `java.util.Collections`, `java.util.EnumMap`,
  `java.util.Map`.

- [ ] Run `./gradlew test --tests '*DegreeCensusTest*'` **blocking** and **quote the failure**. It
      must be an AssertJ `containsExactly` failure naming `PERSON=KindDegrees[p50=0, …]` where
      `KindDegrees[p50=2, …]` was expected — not a compile error. If it is a compile error, the
      scaffold is wrong; fix the scaffold, never the expectation.

### Step 2 — GREEN: bucket by the fold's kind and read each population

- [ ] Replace the body of `DegreeCensus.of` and add the two private helpers:

```java
  public static DegreeCensus of(LogProjection projection) {
    Objects.requireNonNull(projection, "projection");
    Map<String, Integer> degrees = Degrees.in(projection);
    int floor = Recommendations.MIN_CANDIDATE_DEGREE;
    Map<NodeKind, List<Integer>> collected = new EnumMap<>(NodeKind.class);
    for (NodeKind kind : NodeKind.values()) {
      collected.put(kind, new ArrayList<>());
    }
    // Every key came from projection.nodes(), which Degrees.in seeds itself from, so there is no
    // absent node to defend against here.
    degrees.forEach((qid, degree) -> collected.get(projection.nodes().get(qid).kind()).add(degree));
    Map<NodeKind, KindDegrees> byKind = new EnumMap<>(NodeKind.class);
    collected.forEach((kind, population) -> byKind.put(kind, read(population, floor)));
    KindDegrees whole = read(List.copyOf(degrees.values()), floor);
    return new DegreeCensus(
        floor, whole.p50(), whole.p90(), whole.p99(), whole.max(), whole.atOrBelowTheFloor(),
        byKind);
  }

  /** One population's figures — the whole graph's and every kind's, by one rule rather than two. */
  private static KindDegrees read(List<Integer> population, int floor) {
    List<Integer> sorted = population.stream().sorted().toList();
    return new KindDegrees(
        quantile(sorted, 0.50),
        quantile(sorted, 0.90),
        quantile(sorted, 0.99),
        sorted.isEmpty() ? 0 : sorted.getLast(),
        (int) sorted.stream().filter(degree -> degree <= floor).count());
  }
```

  Imports to add: `java.util.ArrayList`. `List` and `Objects` are already imported.

- [ ] Run `./gradlew test --tests '*DegreeCensusTest*'` **blocking**. Green — quote the pass count.

### Step 3 — two more expectations, green on arrival, whose reds arrive in step 4

- [ ] **Say this out loud in the step report: both assertions below pass the moment they are added,
      and that is deliberate.** They are invariants rather than behaviours, and their evidence is the
      planted defects in step 4, each seen to fire.

- [ ] Add to `DegreeCensusTest`:

```java
  @Test
  @DisplayName("the kinds partition the graph, so their counts sum to the whole graph's")
  void shouldSumEachKindsCountToTheWholeGraphsWhenBothAreRead() {
    int summed =
        CENSUS.byKind().values().stream().mapToInt(DegreeCensus.KindDegrees::atOrBelowTheFloor).sum();

    assertThat(summed)
        .as("every node has exactly one kind, so no node may be counted twice or dropped")
        .isEqualTo(CENSUS.atOrBelowTheFloor());
  }
```

- [ ] Extend `shouldReadEveryFigureAsZeroWhenTheProjectionIsEmpty` with, after its existing
      assertions:

```java
    assertThat(census.byKind())
        .as("all six kinds are present at zero, never absent — NodeCensus's rule")
        .containsExactly(
            entry(NodeKind.PERSON, new DegreeCensus.KindDegrees(0, 0, 0, 0, 0)),
            entry(NodeKind.GROUP, new DegreeCensus.KindDegrees(0, 0, 0, 0, 0)),
            entry(NodeKind.WORK, new DegreeCensus.KindDegrees(0, 0, 0, 0, 0)),
            entry(NodeKind.PLACE, new DegreeCensus.KindDegrees(0, 0, 0, 0, 0)),
            entry(NodeKind.EVENT, new DegreeCensus.KindDegrees(0, 0, 0, 0, 0)),
            entry(NodeKind.CONCEPT, new DegreeCensus.KindDegrees(0, 0, 0, 0, 0)));
```

- [ ] Run `./gradlew test --tests '*DegreeCensusTest*'` **blocking**. Green.

### Step 4 — positive controls: three plants, each seen to fire

Each plant: apply it, run `./gradlew test --tests '*DegreeCensusTest*'` **blocking**, **quote the
failure and name which test fired**, then revert the plant and re-run to green before the next one.
**If a plant does not fire, that is the finding** — the assertion it was supposed to fire is not
checking what it claims to.

- [ ] **Plant A — the kind-blind number wearing a by-kind label.** In `of`, pass the whole graph's
      population to every kind: replace
      `collected.forEach((kind, population) -> byKind.put(kind, read(population, floor)));` with
      `collected.forEach((kind, population) -> byKind.put(kind, read(List.copyOf(degrees.values()), floor)));`
      Expect `shouldReadEachKindsOwnDistributionWhenTheKindsDifferFromTheWhole` to fire — `PERSON`
      reads p50 1 rather than 2, `WORK` reads p50 1 rather than 0 — and
      `shouldSumEachKindsCountToTheWholeGraphsWhenBothAreRead` to fire too (six times twelve, not
      twelve). **This plant is the defect issue #247 exists about**, which is why it is first.
- [ ] **Plant B — below rather than at or below.** In `read`, change
      `filter(degree -> degree <= floor)` to `filter(degree -> degree < floor)`. Expect
      `shouldReadEachKindsOwnDistributionWhenTheKindsDifferFromTheWhole` to fire on `WORK` (7 rather
      than 8, the node sitting exactly on the floor dropped),
      `shouldCountTheNodeOnTheFloorWhenTheFloorsBiteIsMeasured` to fire on the whole graph (11 rather
      than 12), and `shouldSumEachKindsCountToTheWholeGraphsWhenBothAreRead` to stay green — say that
      last part out loud, because it is why the sum test alone is not enough.
- [ ] **Plant C — the empty kinds dropped.** In `of`, delete the loop that seeds `collected` with an
      empty list per kind and change the `forEach` to
      `degrees.forEach((qid, degree) -> collected.computeIfAbsent(projection.nodes().get(qid).kind(), kind -> new ArrayList<>()).add(degree));`
      Expect both `containsExactly` assertions to fire — `PLACE` and `EVENT` absent rather than zero,
      and on the empty projection every kind absent.

- [ ] Confirm the file is back to step 2's code and `./gradlew test --tests '*DegreeCensusTest*'` is
      green before committing.

### Step 5 — Javadoc and commit

- [ ] Add to `DegreeCensus`'s class Javadoc, after the existing "Isolated nodes are in the
      population" paragraph:

```java
 * <p><b>The same figures are read again per {@link com.robsartin.segue.domain.NodeKind}</b>, all
 * six kinds and zeros included, because the floor is applied to two of them and nothing else:
 * {@code CandidateSweep.couldBeExplored} refuses every kind but {@code PERSON} and {@code GROUP}
 * before the degree test is reached, so the whole-graph reading above is a true statement about the
 * graph and a misleading one about the floor (issue #247). The whole-graph reading stays because
 * issue #135's question is about the graph the floor was measured against. One rule reads both —
 * {@code read} — so a kind's quantile and the graph's cannot come to disagree about what a quantile
 * is.
```

- [ ] Add the `@param byKind` tag beside the existing `@param` tags, keeping the name exactly
      `byKind` (doclint rejects a `@param` naming a component that does not exist):

```java
 * @param byKind the same reading taken over each kind's own nodes, in {@code NodeKind} declaration
 *     order. An {@code EnumMap} rather than {@code Map.copyOf}, on {@code NodeCensus}'s reason:
 *     that factory's iteration order is unspecified and salted per JVM, and ADR 43's
 *     byte-identical contract is what the order serves
```

- [ ] Run the gate **BLOCKING**:
      `SEGUE_REQUIRE_BROWSER=true SEGUE_REQUIRE_GRAPHVIZ=true ./gradlew check --rerun-tasks`
- [ ] `git status`, then stage by explicit path:
      `git add src/main/java/com/robsartin/segue/census/DegreeCensus.java src/test/java/com/robsartin/segue/census/DegreeCensusTest.java`
- [ ] Commit: `Read the census degree distribution by node kind (#247)`.

---

## Task 2: the floor's bite as a share

### Step 1 — RED: the share, whole graph and per kind

- [ ] Add to `DegreeCensusTest`:

```java
  @Test
  @DisplayName("the floor's bite is reported as a whole percent of the population it is applied to")
  void shouldReportTheFloorsBiteAsAWholePercentWhenAPopulationIsRead() {
    assertThat(CENSUS.atOrBelowTheFloorPercent())
        .as("twelve of thirteen is 92.3%, and a whole percent of it is 92")
        .isEqualTo(92);
    assertThat(CENSUS.byKind().get(NodeKind.PERSON).atOrBelowTheFloorPercent())
        .as("two of three is 66.7%, and an exact-two-thirds share rounds up rather than truncating")
        .isEqualTo(67);
    assertThat(CENSUS.byKind().get(NodeKind.PLACE).atOrBelowTheFloorPercent())
        .as("an empty population reads zero rather than dividing by nothing")
        .isEqualTo(0);
  }
```

- [ ] **Compilation scaffold, said out loud, exactly as in Task 1.** Add
      `int atOrBelowTheFloorPercent` as `KindDegrees`'s last component and as `DegreeCensus`'s
      component immediately after `atOrBelowTheFloor`, and wire both to the literal `0` in `read` and
      in `of` (`whole.atOrBelowTheFloorPercent()` is what `of` will pass, so only `read` needs the
      literal). Update the six-entry `containsExactly` blocks in
      `shouldReadEachKindsOwnDistributionWhenTheKindsDifferFromTheWhole` and in the
      empty-projection test to the six-argument `KindDegrees`, using the real expected shares:
      `PERSON … , 67`, `GROUP … , 100`, `WORK … , 100`, `PLACE … , 0`, `EVENT … , 0`,
      `CONCEPT … , 100`, and all zeros on the empty projection.
- [ ] Run `./gradlew test --tests '*DegreeCensusTest*'` **blocking** and **quote the failure** — an
      AssertJ `expected: 92 but was: 0`, not a compile error.

### Step 2 — GREEN: the percent rule

- [ ] In `DegreeCensus`, add the helper and use it in `read`:

```java
  /**
   * A whole percent of the population, nearest, an exact half going up: {@code (200 * part + whole)
   * / (2 * whole)} in integers, so no floating point decides a boundary and the value the report
   * prints stays an integer (ADR 63). <b>An empty population reads zero</b> rather than dividing by
   * nothing — and a share of zero is why the count is printed beside it, because zero covers both
   * "none" and "a handful of a hundred thousand".
   */
  private static int percent(int part, int whole) {
    if (whole == 0) {
      return 0;
    }
    return (200 * part + whole) / (2 * whole);
  }
```

  In `read`, hold the count in a local and pass `percent(atOrBelowTheFloor, sorted.size())` as the
  new last argument:

```java
  private static KindDegrees read(List<Integer> population, int floor) {
    List<Integer> sorted = population.stream().sorted().toList();
    int atOrBelowTheFloor = (int) sorted.stream().filter(degree -> degree <= floor).count();
    return new KindDegrees(
        quantile(sorted, 0.50),
        quantile(sorted, 0.90),
        quantile(sorted, 0.99),
        sorted.isEmpty() ? 0 : sorted.getLast(),
        atOrBelowTheFloor,
        percent(atOrBelowTheFloor, sorted.size()));
  }
```

  and in `of`, pass `whole.atOrBelowTheFloorPercent()` after `whole.atOrBelowTheFloor()`.

- [ ] Run `./gradlew test --tests '*DegreeCensusTest*'` **blocking**. Green.

### Step 3 — positive controls: three plants, each seen to fire

Same discipline as Task 1 step 4 — apply, run blocking, quote, revert, re-run green.

- [ ] **Plant D — truncation instead of rounding.** Replace the return in `percent` with
      `return 100 * part / whole;`. Expect
      `shouldReportTheFloorsBiteAsAWholePercentWhenAPopulationIsRead` to fire on `PERSON`, 66 rather
      than 67, and the `containsExactly` on the kinds to fire with it. **Say out loud that the
      whole-graph figure does not move** (92 either way), which is exactly why the rounding is
      asserted on a kind rather than on the graph.
- [ ] **Plant E — the wrong denominator.** In `read`, pass `percent(atOrBelowTheFloor, 13)` (the
      whole fixture's node count standing in for the graph total). Expect `PERSON` to read 15 and
      `GROUP` 8, firing both assertions — the defect where a kind's share is taken over the graph
      rather than over the kind.
- [ ] **Plant F — the empty-population guard removed.** Delete the `if (whole == 0)` arm. Expect
      `shouldReadEveryFigureAsZeroWhenTheProjectionIsEmpty` and
      `shouldReportTheFloorsBiteAsAWholePercentWhenAPopulationIsRead` to fail with an
      `ArithmeticException: / by zero`. **Say out loud that this control fires as an exception rather
      than an assertion**, which is what a guard's control looks like.

- [ ] Revert, re-run green.

### Step 4 — Javadoc and commit

- [ ] Add `@param atOrBelowTheFloorPercent` to `DegreeCensus`'s Javadoc, beside the existing tags:

```java
 * @param atOrBelowTheFloorPercent the same population as a whole percent of every node, printed
 *     beside the count rather than instead of it
```

- [ ] Run the gate **BLOCKING**.
- [ ] `git status`, then
      `git add src/main/java/com/robsartin/segue/census/DegreeCensus.java src/test/java/com/robsartin/segue/census/DegreeCensusTest.java`
- [ ] Commit: `Report the floor's bite as a share of the population it is applied to (#247)`.

---

## Task 3: `CensusReport` prints the by-kind degree lines

### Step 1 — RED: the whole block, pinned

- [ ] In `src/test/java/com/robsartin/segue/census/CensusReportTest.java`, replace the entire
      expected text block with the one below. **Two things about it, both of which the step report
      must state:**
      1. Every existing counted line gains **exactly one space** before its number. That is not a
         hand edit of the alignment — a share of `100` is three digits where the widest count was
         two, so `CensusReport`'s derived count width moves from 2 to 3 and the whole column shifts.
         The reflow is the derived-column rule being observed, which the report's own Javadoc claims
         and this is the first change that exercises.
      2. The label width does not move: it is still `  merges superseded but edge-referenced`, at 39
         characters, and the longest new label (`  CONCEPT at or below the floor %`) is 33.

```java
            """
            # segue graph census — aggregates only: no labels, no ids, no notes (ADR 51, ADR 63).

            nodes
              total                                   13
              PERSON                                   3
              GROUP                                    1
              WORK                                     8
              PLACE                                    0
              EVENT                                    0
              CONCEPT                                  1

            edges
              total                                   11
              dangling                                 1
              withdrawn                                0
              backed by also-invented                  1
              backed by invented                       6
              backed by llm:invented                   1
              backed by musicbrainz                    1
              backed by owner                          3
              of type INFLUENCED_BY                    6
              of type MEMBER_OF                        5
              corroborated by 0                        3
              corroborated by 1                        7
              corroborated by 2                        1

            claims
              log rows                                30
              retractions                              1
              rows they removed                        2
              entities they name                       1
              local entities minted                    3
              merges standing                          3
              merges superseded                        2
              merges superseded but edge-referenced    1
              stand-ins                                4
              stand-ins with no edge                   1

            taste
              ratings                                  8
              rated 1                                  1
              rated 2                                  2
              rated 3                                  1
              rated 4                                  2
              rated 5                                  2
              on a local id                            2
              on a stand-in                            1
              on a retracted id                        1

            degree
              floor                                    5
              p50                                      1
              p90                                      5
              p99                                      6
              max                                      6
              at or below the floor                   12
              at or below the floor %                 92
              PERSON p50                               2
              PERSON p90                               6
              PERSON p99                               6
              PERSON max                               6
              PERSON at or below the floor             2
              PERSON at or below the floor %          67
              GROUP p50                                2
              GROUP p90                                2
              GROUP p99                                2
              GROUP max                                2
              GROUP at or below the floor              1
              GROUP at or below the floor %          100
              WORK p50                                 0
              WORK p90                                 5
              WORK p99                                 5
              WORK max                                 5
              WORK at or below the floor               8
              WORK at or below the floor %           100
              PLACE p50                                0
              PLACE p90                                0
              PLACE p99                                0
              PLACE max                                0
              PLACE at or below the floor              0
              PLACE at or below the floor %            0
              EVENT p50                                0
              EVENT p90                                0
              EVENT p99                                0
              EVENT max                                0
              EVENT at or below the floor              0
              EVENT at or below the floor %            0
              CONCEPT p50                              2
              CONCEPT p90                              2
              CONCEPT p99                              2
              CONCEPT max                              2
              CONCEPT at or below the floor            1
              CONCEPT at or below the floor %        100

            bridge
              entities MusicBrainz reached             2
              of those, carrying classes               1""");
```

- [ ] Run `./gradlew test --tests '*CensusReportTest*'` **blocking** and **quote the failure** — an
      AssertJ string comparison whose first difference is the `nodes/total` line's extra space. Name
      the first differing line in the step report.

### Step 2 — GREEN: the lines

- [ ] In `CensusReport.body`, replace the degree block with:

```java
    DegreeCensus degree = census.degree();
    body.add(section("degree"));
    body.add(count("floor", degree.floor()));
    body.add(count("p50", degree.p50()));
    body.add(count("p90", degree.p90()));
    body.add(count("p99", degree.p99()));
    body.add(count("max", degree.max()));
    body.add(count("at or below the floor", degree.atOrBelowTheFloor()));
    body.add(count("at or below the floor %", degree.atOrBelowTheFloorPercent()));
    for (Map.Entry<NodeKind, DegreeCensus.KindDegrees> kind : degree.byKind().entrySet()) {
      // The kind's own name, as the nodes section already labels its counts with it: a NodeKind
      // constant is vocabulary this file compiles against, not text read off the log.
      String of = kind.getKey().name() + " ";
      DegreeCensus.KindDegrees read = kind.getValue();
      body.add(count(of + "p50", read.p50()));
      body.add(count(of + "p90", read.p90()));
      body.add(count(of + "p99", read.p99()));
      body.add(count(of + "max", read.max()));
      body.add(count(of + "at or below the floor", read.atOrBelowTheFloor()));
      body.add(count(of + "at or below the floor %", read.atOrBelowTheFloorPercent()));
    }
```

  `Map` and `NodeKind` are already imported.

- [ ] Run `./gradlew test --tests '*CensusReportTest*'` **blocking**. Green.

### Step 3 — positive control

- [ ] **Plant G — the by-kind lines before the whole-graph ones.** Move the `for` loop above the
      seven whole-graph `body.add` calls. Run blocking; expect the string comparison to fire on the
      first `degree` line. Quote it, revert, re-run green. This is the control for the pinned order,
      which no per-number test can see.

### Step 4 — the whole suite, then commit

- [ ] Run `./gradlew test --tests '*census*'` **blocking** — `CensusIsSafeToPasteTest` and
      `CensusRunTest` must be green **without being edited**. If `CensusIsSafeToPasteTest` reds, stop
      and report: a new label has put something in the output that may not be there.
- [ ] Run the gate **BLOCKING**.
- [ ] `git status`, then
      `git add src/main/java/com/robsartin/segue/census/CensusReport.java src/test/java/com/robsartin/segue/census/CensusReportTest.java`
- [ ] Commit: `Print the census degree section by node kind (#247)`.

---

## Task 4: the ADR amendment and the developer guide

**This task has no unit-testable behaviour, and that is said out loud rather than left implied.** It
is prose. It is verified by two explicit methods: the full gate — `AdrIndexTest`,
`DocumentationLinksTest` and `DeveloperGuideCensusExamplesTest` all read these two files — and a
re-read of both edits against the spec before committing.

### Step 1 — append the amendment to ADR 63

- [ ] Append to the **end** of `docs/adr/0063-a-read-only-census-of-the-graph.md`, after the last
      consequence, with one blank line before it. Change nothing above it and do not touch
      `docs/adr/README.md`.

```markdown
---

**Amendment (2026-09-04, issue #247): the degree section is read by kind as well as over the whole
graph.**

The context above says what that section was for: `FloorReading`'s figures are readings of one run's
candidate pool, "so what nobody has is where the floor sits against the *graph*". The section shipped
answers that, and the answer is dominated by a population the floor is never applied to.
`CandidateSweep.couldBeExplored` refuses every kind but `PERSON` and `GROUP`, and the sweep asks it
*before* it asks the degree question — the two are deliberately separate, so that what the floor held
out is countable apart from what the kind rules refused. A whole-graph reading is therefore a true
statement about the graph and a misleading one about the floor: it is the share of nodes below a cut
most of them are never offered to.

So the degree section now reads its figures twice — once over every node, once per `NodeKind`, all
six kinds and zeros included — and reports the floor's bite as a count and a whole percent in both.
One rule reads every population, so a kind's quantile and the graph's cannot come to disagree about
what a quantile is. **`CensusReport` remains the authority on which counts are emitted and in what
order**, and this amendment lists none of them, for the reason the decision above already gives.

Three things this does not do.

- **It does not move the floor, and it does not make one per kind.**
  [ADR 57](0057-the-floor-reports-itself.md) refused an adaptive floor on measurements and that
  refusal stands untouched: a floor that varied with a property of the candidate is the shape it
  rejected. What changes here is the reading, not the cut.
- **It does not touch `FloorReading`.** That record was read against the code for this issue, and
  every figure it carries is already over the `PERSON`/`GROUP` pool, for the ordering above. Issue
  #247 offered it as an alternative home; the code decided against it.
- **It records no reading.** The first consequence above already says so — this tool is the
  instrument, and deciding something about the floor from what it prints is separate work. No census
  of the real graph is taken here, and an amendment to ADR 57 written before the owner has run the
  tool would record a decision nobody took.
```

### Step 2 — the developer guide bullet

- [ ] In `docs/developer-guide.md`, in "Looking at the shape of your graph" → "What it is for",
      replace the second bullet:

```markdown
- where the whole graph's degree distribution sits relative to
  `Recommendations.MIN_CANDIDATE_DEGREE`, which [ADR 57](adr/0057-the-floor-reports-itself.md)
  re-opens on figures `FloorReading` takes over one recommender run's candidate pool — nothing
  reports the nodes that pool never considers;
```

  with:

```markdown
- where the degree distribution sits relative to `Recommendations.MIN_CANDIDATE_DEGREE`, which
  [ADR 57](adr/0057-the-floor-reports-itself.md) re-opens on figures `FloorReading` takes over one
  recommender run's candidate pool — nothing reports the nodes that pool never considers. The degree
  section reads it twice, over the whole graph and over each kind separately, because
  `CandidateSweep.couldBeExplored` refuses every kind but `PERSON` and `GROUP` before the floor is
  applied, so the floor only ever cuts those two;
```

- [ ] **Do not add a list of the new lines.** The chapter says two paragraphs later that
      `CensusReport` is the authority on which counts are emitted and in what order; a list here
      would be the second copy that sentence exists to prevent.

### Step 3 — verify and commit

- [ ] Re-read both edits end to end and confirm: no figure from any real database appears; the ADR's
      front matter and every line above the amendment are byte-identical to before
      (`git diff docs/adr/0063-a-read-only-census-of-the-graph.md` shows additions only); no
      `.superpowers/` path is cited; the guide's link resolves.
- [ ] Run the gate **BLOCKING**. State in the step report which of `AdrIndexTest`,
      `DocumentationLinksTest` and `DeveloperGuideCensusExamplesTest` ran and passed — that is this
      task's verification, and naming it is the point.
- [ ] `git status`, then
      `git add docs/adr/0063-a-read-only-census-of-the-graph.md docs/developer-guide.md`
- [ ] Commit: `Record the by-kind degree reading as an amendment to ADR 63 (#247)`.

---

## Done when

- The census's degree section reads whole-graph and per-kind figures, with the floor's bite as a
  count and a whole percent in each.
- Seven planted defects (A–G) have each been seen to fire and been removed.
- `CensusIsSafeToPasteTest` is green and unedited; `Census`, `FloorReading`, `CandidateSweep` and
  `Degrees` are unchanged.
- The full gate is green on a clean tree, and four commits sit on `247-ready`.
