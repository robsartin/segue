# A minted local id is not a never-expanded shortfall — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to
> implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** close issue #344. After #342, a list's mapping can carry a local id the owner minted
under ADR 59. `graphCensus --known` counts it under `in the graph` and then, because
`domain.Expanded` reads an owner claim as covering nothing, under `never expanded` forever — and the
same floor reaches the three second-hop rows and `expandPromotions --known`'s own population, which
refuses a local id it visits as `local entity` (#92) after spending a pass learning what its shape
already said. Stop counting a minted id as a shortfall a further run could close: `domain.SecondHop`
excludes a local neighbour from what it offers to expand; `census.KnownListCensus` counts a local id
separately (`local`, nested under `in the graph`) and excludes it from `never expanded`; and
`expand.ExpandCli`'s `--known` population excludes it too, so the tool that visits and the row that
counts agree again. `--rated-since` is unchanged and is where a rated local id can still reach the
`local entity` refusal.

**Architecture:** no new class, no new port, no new package. Three production files gain a narrow
exclusion each, on a rule already public (`LocalEntity.isLocal`); one record gains one `int`
component; two ADRs gain one appended amendment each; the developer guide gains one new paragraph
and two rewritten ones, and confirms a fourth is already accurate. See the spec's **What changes**
section for the one-paragraph form of each; this plan does not restate it, only where and how.

**Tech Stack:** Java 25, Gradle (plain `./gradlew`), JUnit 5, AssertJ, ArchUnit, `javadoc -Werror`.

**Spec:** `docs/superpowers/specs/2026-09-19-census-local-row-design.md`.

## Global Constraints

- **Pure TDD.** Every code step is RED (write the test, run it, read a real failure — an assertion
  for the stated reason, never a compile error) then GREEN (the minimal change) then a green
  re-run. Each task's report quotes what the RED failure actually said.
- **Mikado: green at every committed step.** A task that would break another test on the way to
  green is not done until that other test is dealt with inside the same task (see Task 1's Step 7 —
  `SecondHop`'s own change breaks one pre-existing `ExpandCliTest` case, and that task removes it
  rather than leaving the build red between tasks). Never a big-bang change guarded only by a final
  full-suite run.
- **`docs` is a declared input of the `test` task** (`build.gradle.kts:150`), so a documentation
  edit re-runs the suite; every verification loop in Task 4 runs **without** `--rerun-tasks`, and
  the report says the task **ran** rather than printed `UP-TO-DATE`.
- **Nothing in Task 4 can red on the prose itself, so each prose edit carries a positive control**:
  plant a defect the guard reads for (a bad relative link, an un-allowlisted commit hash), run the
  guard, quote the real failure, remove the plant, then make the real edit. A compile error is
  never a red.
- **ADRs are immutable.** Only an appended, dated amendment (`2026-09-19, issue #344`). Nothing
  above an amendment is edited, reworded or deleted, and both ADRs keep `Accepted`.
- **Quoting the guide or an ADR inside this plan** (this plan itself, and any future report that
  quotes it) wraps a passage that contains a markdown link in a four-backtick fence — a three-tick
  fence is fine for anything else (a Java diff, a shell command) but not for a quoted passage that
  carries a working `[text](target)` link, because `DocumentationLinksTest` walks every `.md` file
  under `docs/`, including this one.
- **No `.superpowers/` path in any committed file.** No commit hash under `docs/adr`. No qid
  anywhere in this plan or in the touched ADRs/guide — every id in a code example below is one
  `SecondHopTest`, `KnownListCensusTest`, `CensusReportTest`, `ExpandCliTest` or `InventedCensus`
  already invents, or a fresh one in the same shape (`Q00…`, two leading zeros — ADR 59).
- **The only issue numbers cited anywhere in this plan or in the prose it writes are #344, #342,
  #326, #319, #315, #328, #313, #311 and #92; the only ADR numbers are 59, 62, 63, 66, 44 and 33.**
  No other issue or ADR is named — not even ones the surrounding text already cites (ADR 58, for
  one) — so nothing here restates a figure or a decision that belongs to a document this plan does
  not open.
- **Never restate a count or a figure from another document.** The developer guide and the ADRs
  cite `Recommendations.MAX_HOPS` and the row labels by name, never by copying a number.
- **`{@code X}` and `{@link X}` spans stay whole on one source line.** After `./gradlew
  spotlessApply`, re-read every javadoc comment this plan edits and confirm no span was split across
  lines — a paragraph break is the fix if one was. Prove it on each touched file with
  `grep -nE '\{@(code|link)[^}]*$' <file>` — empty output is the pass. **The rule is NO NEW split
  relative to `HEAD`** on a file that already carries one (none of the four touched here do, but the
  check is run anyway, per file, after `spotlessApply`).
- **Test names read `should<Expected>When<Condition>` and carry a `@DisplayName`.**
- **Commits stage by explicit path** (`git add <exact paths>`, never `-A`, never `git add . `),
  with `git status` read first and `git add` stderr visible (never `2>/dev/null`). Every commit
  message ends, after a blank line, with exactly `Co-Authored-By: Claude Fable 5.1
  <noreply@anthropic.com>`.
- **The full gate (`./gradlew check`) is the controller's job, not a task's.** Each task's own
  verification is the specific test class(es) named in that task, run blocking, with the exit code
  and the relevant output read before the commit.
- Work in `/Users/sartin/code/segue/wt-344`, on branch `344-ready`. You are the sole committer
  there.

---

## Task 1 — `domain.SecondHop` excludes a local neighbour

**Files:**
- `src/main/java/com/robsartin/segue/domain/SecondHop.java`
- `src/test/java/com/robsartin/segue/domain/SecondHopTest.java`
- `src/test/java/com/robsartin/segue/expand/ExpandCliTest.java` (Mikado green-keeping step —
  see Step 7)

**Interfaces:** no signature changes. `toExpandBeside(String isolated)` still returns
`Set<String>`; `toExpand()` still returns `List<String>`; `WORTH_EXPANDING` is unchanged.

### Step 1 — RED: plant the local-neighbour test

Add to `SecondHopTest.java`, after the existing `ABSENT` constant (before `nodes(...)`):

```java
  /** A minted PERSON beside {@link #ACT} — LocalEntity.isLocal, so it is never to expand (#344). */
  private static final String LOCAL_NEIGHBOUR = "Q00901409";
```

Add this test method, after `shouldReadTheEdgeFromBothEndsWhenTheNeighbourIsTheSubject` and before
`shouldPassOverTheMemberWhenTheFoldHoldsNoNodeForIt`:

```java
  @Test
  @DisplayName(
      "a minted local neighbour beside an isolated act is never to expand, and an ordinary"
          + " unexpanded neighbour beside the same act still is")
  void shouldExcludeTheLocalNeighbourWhenOneIsBesideAnIsolatedAct() {
    // BANDMATE is the planted control: same act, same kind, no expansion — if this came back
    // empty too, the assertion below would not prove the local id was what was excluded.
    SecondHop rule =
        SecondHop.of(
            nodes(
                new LinkedHashMap<>(
                    Map.of(
                        ACT, NodeKind.GROUP,
                        LOCAL_NEIGHBOUR, NodeKind.PERSON,
                        BANDMATE, NodeKind.PERSON))),
            List.of(edge(ACT, LOCAL_NEIGHBOUR), edge(ACT, BANDMATE)),
            List.of(ACT),
            new Expanded(Set.of()));

    assertThat(rule.isolated()).containsExactly(ACT);
    assertThat(rule.toExpandBeside(ACT))
        .as("the minted neighbour is excluded; BANDMATE is the control that this did not go blind")
        .containsExactly(BANDMATE);
    assertThat(rule.toExpand()).containsExactly(BANDMATE);
  }
```

### Step 2 — verify-fails (RED)

Run, blocking: `./gradlew test --tests '*SecondHopTest'`

**Expect a real assertion failure, not a compile error.** `Neighbours.in` preserves edge insertion
order into a `LinkedHashSet` per node, and the edges are given `edge(ACT, LOCAL_NEIGHBOUR)` before
`edge(ACT, BANDMATE)`, so before the fix `toExpandBeside(ACT)` returns both, in that order. Expect
AssertJ's `containsExactly` to report the actual collection holding `Q00901409` ahead of
`Q0901403`, called out as an element `containsExactly` did not expect. **Quote the real message in
the task report** — the exact wording is AssertJ's own and is not restated here. If the test
compiles but passes, the fixture is not exercising the neighbour path and the task stops and
reports.

### Step 3 — GREEN: exclude a local neighbour

In `SecondHop.java`, change `toExpandBeside`:

```java
  public Set<String> toExpandBeside(String isolated) {
    Objects.requireNonNull(isolated, "isolated");
    if (!this.isolated.contains(isolated)) {
      throw new IllegalArgumentException("not an isolated member of this population: " + isolated);
    }
    Set<String> beside = new LinkedHashSet<>();
    for (String neighbour : adjacency.getOrDefault(isolated, Set.of())) {
      NodeRecord node = nodes.get(neighbour);
      if (node != null
          && WORTH_EXPANDING.contains(node.kind())
          && !expanded.covers(neighbour)
          && !LocalEntity.isLocal(neighbour)) {
        beside.add(neighbour);
      }
    }
    return Collections.unmodifiableSet(beside);
  }
```

`LocalEntity` is in the same package (`domain`); no new import.

### Step 4 — verify (GREEN)

Run, blocking: `./gradlew test --tests '*SecondHopTest'`. Confirm all tests pass, including the new
one and every existing case (`BANDMATE`, `PRODUCER`, `RECORD`, the two-isolated-acts ordering case,
the edge-direction case, the absent-member case, the not-isolated refusal case). None of those
fixtures name a `Q00…` id, so none of them can be affected by this change — that is itself a
control that the exclusion is additive.

### Step 5 — javadoc

Update `toExpandBeside`'s javadoc:

```java
  /**
   * The nodes one folded edge from an isolated member whose kind is in {@link #WORTH_EXPANDING},
   * that {@link Expanded} does not cover, and that is not local ({@link LocalEntity#isLocal}).
   *
   * <p><b>A local neighbour is excluded, never merely never-covered.</b> The owner minted it
   * because no source models it, on ADR 59's own decision, so no source will ever answer for it —
   * not now and not on any later run — and it is not a never-expanded shortfall a run could still
   * close. Counting it here put it under {@code with someone to expand beside} and {@code distinct
   * to expand} forever, and a {@code --second-hop} run visiting it spent a whole pass learning what
   * this exclusion already knows: {@code EntityExpansion.expand} refuses a local id as {@code
   * LOCAL_ENTITY} before any adapter runs (#92), and this exclusion means that refusal is one such
   * a run can no longer produce at all (#344).
   *
   * <p>An isolated member that is itself local is unaffected by this — {@link #isolated()} still
   * reports it exactly as it reports any other member the fold holds a node for and cannot place;
   * only a <i>neighbour</i> is excluded here.
   *
   * @throws IllegalArgumentException if {@code isolated} is not one of {@link #isolated()} — the
   *     question is about an act the graph cannot place, and asking it about any other id is a
   *     caller error rather than an empty answer
   */
```

Add one short paragraph to the class javadoc, after the `{@link #WORTH_EXPANDING}` paragraph and
before the closing `*/`:

```java
 *
 * <p><b>A local neighbour is never to expand.</b> {@link LocalEntity#isLocal} is checked beside
 * {@link #WORTH_EXPANDING} and {@link Expanded} in {@link #toExpandBeside}, for the reason the
 * census's {@code local} row and {@code expand.ExpandCli}'s {@code --known} population exclude one
 * too (#344): no source will ever answer for a minted id.
```

Run `./gradlew spotlessApply`, then `grep -nE '\{@(code|link)[^}]*$' src/main/java/com/robsartin/segue/domain/SecondHop.java` — expect empty output.

### Step 6 — verify (GREEN, javadoc)

Run, blocking: `./gradlew compileJava` (javadoc lint runs inside `check`, but a fast compile here
catches a malformed tag before the next step). Then re-run
`./gradlew test --tests '*SecondHopTest'` and confirm still green.

### Step 7 — Mikado green-keeping: retire the now-false `ExpandCliTest` case

`ExpandCliTest.shouldRefuseTheMintedNeighbourWhenASecondHopRunVisitsIt` (and the two constants and
one helper method that exist only for it) asserts that a real `--second-hop` run visiting a minted
neighbour refuses it as `local entity`. After Step 3, `SecondHop.toExpandBeside` never offers that
neighbour to the run at all — the population it composes no longer names it — so this test's own
premise is now false, and left alone it goes red the moment this task is committed.

Run, blocking, first to confirm the break is real and not assumed:
`./gradlew test --tests '*ExpandCliTest'`

**Expect a real assertion failure**: `countOn(lines(), "considered")` reporting `0`, not `1` (the
isolated act's one neighbour is now excluded before the run ever composes its population), so the
first assertion (`.isEqualTo(1)`) fails. Quote the actual message in the task report.

Remove exactly this block from `ExpandCliTest.java` (currently the 44 lines beginning at the
`/** Isolated, with a minted local PERSON beside it, and nothing else on the file. */` comment and
ending at the closing `}` of `shouldRefuseTheMintedNeighbourWhenASecondHopRunVisitsIt` — confirm the
span with
`grep -n "Isolated, with a minted local PERSON\|shouldRefuseTheMintedNeighbourWhenASecondHopRunVisitsIt" src/test/java/com/robsartin/segue/expand/ExpandCliTest.java`
before deleting, since a line number can move if an earlier task in this file has already landed):

```java
  /** Isolated, with a minted local PERSON beside it, and nothing else on the file. */
  private static final String LOCAL_NEIGHBOUR_ACT = "Q0901413";

  /**
   * The owner's own minted entity, beside {@link #LOCAL_NEIGHBOUR_ACT}.
   *
   * <p>{@code EntityExpansion.expand} refuses {@code LocalEntity.isLocal} before any adapter runs
   * (#92).
   */
  private static final String LOCAL_NEIGHBOUR = "Q00901413";

  private Path secondHopLocalGraph(String name) {
    Path db = home.resolve(name);
    Provenance plain = new Provenance("invented", "invented:8", WHEN, 1.0);
    try (SqliteAssertionLog log = new SqliteAssertionLog(db)) {
      log.append(new NodeAssertion(LOCAL_NEIGHBOUR_ACT, NodeKind.GROUP, "an invented act", plain));
      log.append(LocalEntity.minted(LOCAL_NEIGHBOUR, NodeKind.PERSON, "a minted bandmate", WHEN));
      log.append(
          new AssertionRecord(
              LOCAL_NEIGHBOUR_ACT, LOCAL_NEIGHBOUR, "MEMBER_OF", null, null, plain));
    }
    return db;
  }

  @Test
  @DisplayName("a real second-hop run refuses a minted local neighbour without reaching an adapter")
  void shouldRefuseTheMintedNeighbourWhenASecondHopRunVisitsIt() throws Exception {
    // A REAL run (no --dry-run), and it reaches no network: EntityExpansion.expand
    // (src/main/java/com/robsartin/segue/expansion/EntityExpansion.java) checks
    // LocalEntity.isLocal(qid) and returns Refused(LOCAL_ENTITY) before ExpandContext is built or
    // any adapter is asked — see this task's report for the confirmed line numbers.
    Path db = secondHopLocalGraph("second-hop-local.db");
    Path file = Files.writeString(home.resolve("second-hop-local.csv"), LOCAL_NEIGHBOUR_ACT + "\n");
    captured.list.clear();

    ExpandCli.main(new String[] {"--db", db.toString(), "--second-hop", file.toString()});

    assertThat(countOn(lines(), "considered"))
        .as("one isolated act, one minted PERSON beside it")
        .isEqualTo(1);
    assertThat(countOn(lines(), "local entity"))
        .as("the minted neighbour is refused before any adapter runs, never expanded")
        .isEqualTo(1);
  }
```

Replace it with nothing (delete the block outright) — **do not** leave a stub. Task 3, Step 5 below
adds the replacement coverage (the same property, proved through `--rated-since`, the one
population this issue leaves able to carry a local id).

Run, blocking: `./gradlew test --tests '*ExpandCliTest'`. Confirm green — no test in the file now
names `LOCAL_NEIGHBOUR_ACT`, `LOCAL_NEIGHBOUR` or `secondHopLocalGraph`
(`grep -c "LOCAL_NEIGHBOUR\|secondHopLocalGraph" src/test/java/com/robsartin/segue/expand/ExpandCliTest.java`
— expect `0`).

### Step 8 — commit

Stage by explicit path:

```bash
git add src/main/java/com/robsartin/segue/domain/SecondHop.java \
        src/test/java/com/robsartin/segue/domain/SecondHopTest.java \
        src/test/java/com/robsartin/segue/expand/ExpandCliTest.java
```

Commit message subject: `SecondHop excludes a local neighbour from what it offers to expand
(#344)`. Body: one or two sentences on the exclusion and the retired `ExpandCliTest` case, why it
had to go in this task rather than Task 3 (Mikado — a pre-existing test this task's own change
breaks). Blank line, then `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`.

---

## Task 2 — `census.KnownListCensus` counts local ids; `CensusReport` prints the `local` row

**Files:**
- `src/main/java/com/robsartin/segue/census/KnownListCensus.java`
- `src/main/java/com/robsartin/segue/census/CensusReport.java`
- `src/test/java/com/robsartin/segue/census/KnownListCensusTest.java`
- `src/test/java/com/robsartin/segue/census/CensusReportTest.java`

**Interfaces:**

```java
public record Population(
    int named,
    int inTheGraph,
    int local,
    int neverExpanded,
    int noKnownNeighbourWithinMaxHops,
    int isolatedWithSomeoneToExpand,
    int isolatedWithNoOne,
    int distinctToExpand,
    Map<NodeKind, Integer> inTheGraphByKind,
    Map<NodeKind, Integer> neverExpandedByKind)
```

`local` is inserted between `inTheGraph` and `neverExpanded`. **There is exactly one constructor
call site in `src/main`** — `KnownListCensus.read`, at the end of the method
(`grep -rn "new Population(" src/main src/test` confirms this before editing: one hit, in
`KnownListCensus.java`). No other file names `KnownListCensus.Population`'s constructor.

### Step 1 — RED: `KnownListCensusTest`, a minted unmerged local id

Add to `KnownListCensusTest.java`, after the `census(List<String> file)` overload and before the
first `@Test`:

```java
  /** Minted, and never merged — LocalEntity.isLocal on the population it is named in (#344). */
  private static final String UNMERGED_LOCAL = "Q0032";
```

(`Q0032` is a fresh, two-leading-zero id in this file's own convention, distinct from `LOCAL`
(`Q0031`, which this file's own log merges onto `CANONICAL`) and from every id `InventedCensus`
already uses.)

Add this test, after `shouldCountAMergeOnceOnTheCanonicalSideWhenBothItsSidesAreNamed`:

```java
  @Test
  @DisplayName(
      "a minted, unmerged local id counts under in-the-graph and local, and not under"
          + " never-expanded")
  void shouldCountTheLocalIdUnderLocalAndNotUnderNeverExpandedWhenTheFileNamesOne() {
    // Its own log: InventedCensus's LOCAL is merged onto CANONICAL in this file's shared fixture,
    // which is the case this file already covers (the test above). This test needs one that
    // stays unmerged.
    List<LoggedAssertion> log =
        List.of(
            InventedCensus.node(SEEN, NodeKind.PERSON, "An Invented Performer"),
            InventedCensus.minted(UNMERGED_LOCAL, "A Thing The Owner Minted And Kept"));
    Fold fold = Fold.of(log, KindMapper::rederive);
    LogProjection projection = LogProjection.of(log, fold);

    KnownListCensus.Population population =
        KnownListCensus.of(
                new KnownListInput("known.csv", List.of(SEEN, UNMERGED_LOCAL)),
                Expanded.in(log),
                projection,
                fold,
                Map.of())
            .fromFile();

    assertThat(population.named()).isEqualTo(2);
    assertThat(population.inTheGraph())
        .as("minting records a node, same as any other claim")
        .isEqualTo(2);
    assertThat(population.local()).as("one of the two is local").isEqualTo(1);
    assertThat(population.neverExpanded())
        .as("the local one is excluded from the shortfall row — no source will ever answer for it")
        .isZero();
    assertThat(population.neverExpandedByKind())
        .as("and it never reaches its kind's row either")
        .containsEntry(NodeKind.WORK, 0);
  }
```

`InventedCensus.minted(qid, label)` mints a `NodeKind.WORK` (its own fixed choice — see
`InventedCensus.minted`'s body), which is why the assertion above checks `NodeKind.WORK` and not
`PERSON`.

### Step 2 — verify-fails (RED)

Run, blocking: `./gradlew test --tests '*KnownListCensusTest'`

**Expect a compile error first**, because `Population.local()` does not exist yet — that is not the
red this task counts. Comment out the two lines calling `population.local()` and
`.neverExpandedByKind()...isZero` temporarily is not needed; instead, run the test class and expect
the build to fail at compilation with `cannot find symbol: method local()`. **Quote that compiler
message in the task report as the first red**, then proceed straight to Step 3 (there is no
meaningful "compiles but fails" state to observe first, since the test cannot compile until the
record gains the field — this is the honest exception the project's own TDD rule allows when the
RED step's failure is a compile error for the right reason: the interface the test needs does not
exist yet).

### Step 3 — GREEN: `Population` gains `local`; `read` computes it; `neverExpanded` excludes it

In `KnownListCensus.java`, add the import:

```java
import com.robsartin.segue.domain.LocalEntity;
```

Change the `Population` record declaration:

```java
  public record Population(
      int named,
      int inTheGraph,
      int local,
      int neverExpanded,
      int noKnownNeighbourWithinMaxHops,
      int isolatedWithSomeoneToExpand,
      int isolatedWithNoOne,
      int distinctToExpand,
      Map<NodeKind, Integer> inTheGraphByKind,
      Map<NodeKind, Integer> neverExpandedByKind) {
```

(The compact constructor and `byKind` helper are unchanged — `local` is a plain `int`.)

Change `read`'s loop and the `new Population(...)` call:

```java
  private static Population read(
      List<String> population, Expanded expanded, LogProjection projection, SecondHop secondHop) {
    Map<NodeKind, Integer> inTheGraphByKind = zeroed();
    Map<NodeKind, Integer> neverExpandedByKind = zeroed();
    int inTheGraph = 0;
    int local = 0;
    int neverExpanded = 0;
    for (String qid : population) {
      NodeRecord node = projection.nodes().get(qid);
      if (node == null) {
        continue;
      }
      inTheGraph++;
      inTheGraphByKind.merge(node.kind(), 1, Integer::sum);
      if (LocalEntity.isLocal(qid)) {
        local++;
      } else if (!expanded.covers(qid)) {
        neverExpanded++;
        neverExpandedByKind.merge(node.kind(), 1, Integer::sum);
      }
    }
    List<String> isolated = secondHop.isolated();
    int withSomeone = 0;
    for (String qid : isolated) {
      if (!secondHop.toExpandBeside(qid).isEmpty()) {
        withSomeone++;
      }
    }
    return new Population(
        population.size(),
        inTheGraph,
        local,
        neverExpanded,
        isolated.size(),
        withSomeone,
        isolated.size() - withSomeone,
        secondHop.toExpand().size(),
        inTheGraphByKind,
        neverExpandedByKind);
  }
```

`qid` here is already on its canonical side — `populationsOf` resolves the file through
`merges.canonical` before `read` ever sees it — so `LocalEntity.isLocal(qid)` is asking exactly the
right question: a merged local id is not the id in this loop any more (the canonical replaces it),
so it is never counted as local here, matching the existing merge test's answer.

### Step 4 — verify (GREEN)

Run, blocking: `./gradlew test --tests '*KnownListCensusTest'`. Confirm every test passes, including
the new one and the existing merge case
(`shouldCountAMergeOnceOnTheCanonicalSideWhenBothItsSidesAreNamed`, whose answer is unchanged: the
merge's canonical side is not `LocalEntity.isLocal`, so it was never going to move).

Run, blocking: `./gradlew test --tests '*KnownListCensusScaleTest'`. This test asserts relational
identities (`neverExpanded <= inTheGraph`, the per-kind sums, etc.) over a synthetic graph with no
local id in its known-list file, so it is unaffected — confirm it is still green as the control that
this change is additive, not a rewrite of the arithmetic those identities depend on.

### Step 5 — javadoc: `Population`'s new field, and the two it narrows

Add the new `@param`, and extend the two existing ones, in `Population`'s javadoc block:

```java
   * @param named distinct entities in the population, after the merge fold
   * @param inTheGraph of those, the ones the fold holds a node for
   * @param local of those, the ones {@link com.robsartin.segue.domain.LocalEntity#isLocal} answers
   *     true for — minted by the owner (ADR 59) and not merged onto a canonical id; a merged local
   *     id is counted as its canonical side, never here, matching every other count in this record.
   *     No source will ever answer for one, so it is excluded from {@code neverExpanded} and its
   *     by-kind row below rather than counted as a shortfall a further {@code --known} run could
   *     close (#344)
   * @param neverExpanded in the graph, not local, and no row cites them as a seed — {@link
   *     Expanded}'s answer. Once a {@code --known} expansion run that reported no failure and no
   *     unavailable source has visited everything this counts, what is left is entities Wikidata
   *     states nothing about in the vocabulary this project registers, so read it as a floor rather
   *     than a queue that empties (the run on #313, #315). A local id never inflates this floor
   *     (#344): it was never a shortfall a source could close
```

Update the `neverExpandedByKind` `@param` similarly, adding one clause:

```java
   * @param neverExpandedByKind the same, for the ones nothing has expanded and that are not local
   *     (#344)
```

### Step 6 — javadoc: retire the now-vacuous `local entity` clause on `distinctToExpand`

`distinctToExpand`'s own `@param` (in the same record) says:

```
 *     <p>{@code neighbours skipped} and {@code endpoints refused} join {@code added nothing},
 *     {@code refused} and {@code failed} in the developer guide's six-cell stopping rule, each
 *     zero and no source under {@code unavailable}. A refusal reason of {@code local entity}
 *     alone is permanent and does not withhold that guarantee.
```

After Task 1, `SecondHop.toExpandBeside` excludes a local neighbour outright, so a `--second-hop`
run's population can never contain one, and this refusal reason can no longer arise from that run
at all — the sentence now describes a case that cannot occur, rather than one that does not
withhold a guarantee. Replace exactly that last sentence:

```java
   *     <p>{@code neighbours skipped} and {@code endpoints refused} join {@code added nothing},
   *     {@code refused} and {@code failed} in the developer guide's six-cell stopping rule, each
   *     zero and no source under {@code unavailable}. A {@code refused} reading {@code local
   *     entity} can no longer arise from a {@code --second-hop} run at all: {@link
   *     com.robsartin.segue.domain.SecondHop#toExpandBeside} excludes the owner's own minted
   *     neighbours from what it offers before this row is ever composed (#344)
```

Run `./gradlew spotlessApply`, then
`grep -nE '\{@(code|link)[^}]*$' src/main/java/com/robsartin/segue/census/KnownListCensus.java` —
expect empty output.

### Step 7 — RED: `CensusReport` prints `local`, nested one level under `in the graph`, only when non-zero

Add to `CensusReportTest.java`, after the existing `KNOWN` constant and before `census(...)`:

```java
  /** Minted, and never merged: the fixture for the new `local` row (#344). */
  private static final String LOCAL_ONLY = "Q0091";
```

Add this test, after `shouldAppendTheKnownListSectionWhenAKnownListWasGiven`:

```java
  @Test
  @DisplayName(
      "a local id on the known-list file prints the `local` row, nested one level under `in the"
          + " graph`, in both sub-sections")
  void shouldPrintTheLocalRowWhenTheFileNamesAMintedUnmergedLocalId() {
    // Its own small log, not InventedCensus's — every local id that log mints is merged, which is
    // the "prints no such row" case the two pinned tests above already cover.
    List<LoggedAssertion> log =
        List.of(
            InventedCensus.node(InventedCensus.WREN, NodeKind.PERSON, InventedCensus.WREN_LABEL),
            InventedCensus.minted(LOCAL_ONLY, "A Local Thing, Never Merged"));
    LogProjection projection = LogProjection.of(new InventedCensus.FakeAssertionLog().with(log));
    Fold fold = Fold.of(log, KindMapper::rederive);
    KnownListInput known =
        new KnownListInput("local.csv", List.of(InventedCensus.WREN, LOCAL_ONLY));
    Census census =
        new Census(
            NodeCensus.of(projection),
            EdgeCensus.of(projection),
            ClaimCensus.of(log, projection, fold),
            TasteCensus.of(Map.of(), fold, projection),
            DegreeCensus.of(projection),
            BridgeCensus.of(projection),
            ConceptClassCensus.of(projection),
            Optional.of(KnownListCensus.of(known, Expanded.in(log), projection, fold, Map.of())));

    List<String> lines = CensusReport.lines(census);

    assertThat(rowValue(lines, "in the graph"))
        .as("both ids the file names, once each")
        .isEqualTo(2);
    assertThat(rowValue(lines, "local")).as("the one that is local").isEqualTo(1);
    assertThat(rawLine(lines, "in the graph"))
        .as("`in the graph` is nested one level (NESTED — four spaces)")
        .startsWith("    in the graph");
    assertThat(rawLine(lines, "local"))
        .as("`local` is nested one level deeper (DEEPER — six spaces), under `in the graph`")
        .startsWith("      local");
    assertThat(lines)
        .as("no promotion in this fixture, so both sub-sections read the same population")
        .filteredOn(line -> line.strip().equals(rawLine(lines, "local").strip()))
        .hasSize(2);
  }

  /** The row whose stripped, label-only text equals this exactly — not a prefix match. */
  private static String rawLine(List<String> lines, String label) {
    return lines.stream()
        .filter(line -> matchesLabel(line, label))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no '" + label + "' row in: " + lines));
  }

  private static int rowValue(List<String> lines, String label) {
    String row = rawLine(lines, label);
    String[] parts = row.strip().split(" {2,}");
    return Integer.parseInt(parts[1]);
  }

  /**
   * True when the line's label — everything before the first run of two or more spaces — equals
   * {@code label} exactly. The two-or-more-space split is what tells "local" apart from "local
   * entities minted", whose words are separated by single spaces and so survive the split intact.
   */
  private static boolean matchesLabel(String line, String label) {
    String[] parts = line.strip().split(" {2,}");
    return parts.length == 2 && parts[0].equals(label);
  }
```

Add the one missing import this test needs: `java.util.Optional` is already imported by this file;
confirm `com.robsartin.segue.domain.NodeKind` and `com.robsartin.segue.domain.LoggedAssertion` are
too (both already are, per the file's existing imports) — no new import is required.

### Step 8 — verify-fails (RED)

Run, blocking: `./gradlew test --tests '*CensusReportTest'`

**Expect a real assertion failure**, not a compile error (the test compiles against the `Population`
record Step 3 already gave `local`, since Task 2's steps run in this file order). Expect
`rowValue(lines, "local")` to throw the `AssertionError` this test's own helper raises
(`"no 'local' row in: [...]"`), because `CensusReport` does not print the row yet. **Quote the real
message.** If it instead finds a `local` row already, `CensusReport` changed somewhere it should
not have and the task stops and reports.

### Step 9 — GREEN: `CensusReport` prints the row

In `CensusReport.java`, change `rows`:

```java
  private static void rows(List<Line> body, KnownListCensus.Population population) {
    body.add(nested("named", population.named()));
    body.add(nested("in the graph", population.inTheGraph()));
    // Nested one level under `in the graph`, the row it breaks down, and printed only when it is
    // not zero — the print-when-non-zero precedent #328 set for `added` and `to add`, kept for the
    // same reason: a file naming no local id must print this block byte for byte as it always has.
    if (population.local() != 0) {
      body.add(deeper("local", population.local()));
    }
    body.add(nested("never expanded", population.neverExpanded()));
    body.add(
        nested(
            "no known neighbour within " + Recommendations.MAX_HOPS + " hops",
            population.noKnownNeighbourWithinMaxHops()));
    body.add(deeper("with someone to expand beside", population.isolatedWithSomeoneToExpand()));
    body.add(deeper("with no one", population.isolatedWithNoOne()));
    body.add(deeper("distinct to expand", population.distinctToExpand()));
    for (NodeKind kind : NodeKind.values()) {
      String of = kind.name() + " ";
      body.add(nested(of + "in the graph", population.inTheGraphByKind().get(kind)));
      body.add(nested(of + "never expanded", population.neverExpandedByKind().get(kind)));
    }
  }
```

(Only the four new lines are added; everything else in the method is unchanged.)

### Step 10 — verify (GREEN)

Run, blocking: `./gradlew test --tests '*CensusReportTest'`. Confirm all three tests pass:

- `shouldRenderTheWholeCensusWhenTheFixtureIsCounted` (no known-list section at all — untouched by
  definition, since `rows` is only called when a known-list section exists).
- `shouldAppendTheKnownListSectionWhenAKnownListWasGiven` — **the existing pinned block, byte for
  byte unchanged.** `InventedCensus`'s known-list-eligible local ids (`LEDGER`, `SKETCH`, `DOUBLE`)
  are all merged in that fixture's log, so `local` is `0` in both sub-sections and the row is
  suppressed — the exact control this step exists to prove. If this test's pinned block needs a
  single character changed to stay green, the print-when-non-zero condition in Step 9 is wrong and
  the task stops and reports rather than editing the pin to match.
- The new `shouldPrintTheLocalRowWhenTheFileNamesAMintedUnmergedLocalId`.

### Step 11 — commit

```bash
git add src/main/java/com/robsartin/segue/census/KnownListCensus.java \
        src/main/java/com/robsartin/segue/census/CensusReport.java \
        src/test/java/com/robsartin/segue/census/KnownListCensusTest.java \
        src/test/java/com/robsartin/segue/census/CensusReportTest.java
```

Subject: `KnownListCensus counts a local id separately, and CensusReport prints it (#344)`. Body:
`Population` gains `local`; `neverExpanded` and its by-kind row exclude it; `CensusReport` prints
`local` nested under `in the graph`, only when non-zero, in both sub-sections; the existing pinned
block is unchanged. Blank line, `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`.

---

## Task 3 — `expand.ExpandCli`'s `--known` population excludes local ids

**Files:**
- `src/main/java/com/robsartin/segue/expand/ExpandCli.java`
- `src/main/java/com/robsartin/segue/expand/ExpansionReport.java`
- `src/main/java/com/robsartin/segue/expand/KnownNeverExpanded.java`
- `src/test/java/com/robsartin/segue/expand/ExpandCliTest.java`
- `src/test/java/com/robsartin/segue/expand/ExpansionReportTest.java`

**Interfaces:** no signature changes anywhere in this task — `ExpandCli.run`'s `--known` branch
gains one more `.filter(...)` in an existing stream pipeline, and `ExpansionReport.knownLine`
changes a string literal it already builds.

### Step 1 — RED: the dry-run population excludes a local id

Add to `ExpandCliTest.java`, after `knownFile(String name)` and before
`shouldConsiderOnlyTheNeverExpandedEntitiesWhenAKnownFileIsGiven`:

```java
  /** A normal, never-expanded act — the control that only the local id drops (#344). */
  private static final String KNOWN_ORDINARY = "Q0901109";

  /** Minted, unmerged: LocalEntity.isLocal, so it must never reach the `--known` population. */
  private static final String KNOWN_LOCAL = "Q00901108";

  private Path knownListWithLocalGraph(String name) {
    Path db = home.resolve(name);
    Provenance plain = new Provenance("invented", "invented:10", WHEN, 1.0);
    try (SqliteAssertionLog log = new SqliteAssertionLog(db)) {
      log.append(new NodeAssertion(KNOWN_ORDINARY, NodeKind.GROUP, "an invented act", plain));
      log.append(
          LocalEntity.minted(KNOWN_LOCAL, NodeKind.PERSON, "the owner's own minted entity", WHEN));
    }
    return db;
  }
```

Add this test, right after `shouldRefuseTheKnownEntityWhenTheGraphHoldsNoNodeForIt`:

```java
  @Test
  @DisplayName(
      "a --known --dry-run over a file naming a local id excludes it from the population, and"
          + " from the run's own `minted` bucket too")
  void shouldExcludeTheLocalIdWhenAKnownDryRunNamesOne() throws Exception {
    Path db = knownListWithLocalGraph("known-local.db");
    Path file =
        Files.writeString(home.resolve("known-local.csv"), KNOWN_ORDINARY + "\n" + KNOWN_LOCAL
            + "\n");
    captured.list.clear();

    ExpandCli.main(new String[] {"--db", db.toString(), "--dry-run", "--known", file.toString()});

    assertThat(countOn(lines(), "considered"))
        .as("two ids on the file; the minted one is excluded before the population is composed")
        .isEqualTo(1);
    assertThat(countOn(lines(), "minted"))
        .as("dryRun's own per-id classification never sees it either — filtered out upstream")
        .isZero();
    assertThat(countOn(lines(), "excluded"))
        .as("excluded now also carries a reason other than \"cited as a seed\" — see the clause")
        .isEqualTo(1);
  }
```

`countOn(lines(), "excluded")` reads the `#` clause's own number the same way every other
`countOn` call in this file reads a row — the clause line is `"# only known-list entities from ...
that no expansion has covered: 1 excluded (...)"`, and `countOn`'s `label.length()` cut plus
`Integer.parseInt` on the remainder works on that line exactly as it does on a counted row, since
both shapes put the label then the number with nothing else numeric before it up to that point.
**Confirm this by reading `countOn`'s body before relying on it** — if the clause's punctuation
breaks the parse, assert on the line text directly instead
(`assertThat(lines()).anyMatch(line -> line.contains("1 excluded ("))`) and say so in the report.

### Step 2 — verify-fails (RED)

Run, blocking: `./gradlew test --tests '*ExpandCliTest'`

**Expect a real assertion failure** on `countOn(lines(), "considered")`: before this task's change,
`--known`'s population is `named.stream().filter(qid -> !expanded.covers(qid)).toList()`, so both
`KNOWN_ORDINARY` and `KNOWN_LOCAL` survive (neither is covered by `Expanded`), and `considered` is
`2`. Expect the assertion to report `2` where `1` was wanted. **Quote the real message.**

### Step 3 — GREEN: exclude a local id from the `--known` population

In `ExpandCli.java`, change the `--known` branch's population line:

```java
        KnownListInput known = KnownListInput.read(options.known().get());
        List<String> named = merges.canonical(known.qids());
        Expanded expanded = Expanded.in(assertions.readAll()).onTheCanonicalSide(merges);
        population =
            named.stream()
                .filter(qid -> !LocalEntity.isLocal(qid))
                .filter(qid -> !expanded.covers(qid))
                .toList();
```

(Everything else in the `--known` branch — the `covered` composition below it, the log line — is
unchanged; `LocalEntity` is already imported by this file.)

### Step 4 — verify (GREEN)

Run, blocking: `./gradlew test --tests '*ExpandCliTest'`. Confirm the new test passes and every
existing `--known` test still does
(`shouldConsiderOnlyTheNeverExpandedEntitiesWhenAKnownFileIsGiven`,
`shouldConsiderEveryEntityWhenNoRowCitesAnyOfThemAsASeed`,
`shouldRefuseTheKnownEntityWhenTheGraphHoldsNoNodeForIt`, and the `--add` dry-run tests) — none of
their fixtures name a `Q00…` id, so none of them can be affected by this filter, which is itself the
control that the exclusion is additive rather than a rewrite of the existing rule.

### Step 5 — RED: the `excluded` clause names both reasons, not just one

The clause `ExpansionReport.knownLine` builds currently reads "… excluded (some row in the log
cites them as an expansion's seed) …", which is now inaccurate whenever a local id is part of what
was excluded — `KNOWN_LOCAL` above was excluded for being local, not for being cited as a seed, and
the sentence should say so.

Change the pinned literal in `ExpansionReportTest.java`:

```java
  private static final String KNOWN_LINE =
      "# only known-list entities from known.csv that no expansion has covered: 7 excluded (some"
          + " row in the log cites them as an expansion's seed, or they are the owner's own minted"
          + " local entities) — the file's ids are read through the merge fold, so a merge's two"
          + " sides count once.";
```

Run, blocking: `./gradlew test --tests '*ExpansionReportTest'`

**Expect a real assertion failure** comparing the golden block against `ExpansionReport`'s current
(unchanged) output — the two known-list-clause lines differ by the new clause. Quote the real
diff AssertJ reports.

### Step 6 — GREEN: the production clause, and its javadoc

In `ExpansionReport.java`, change `knownLine`:

```java
  private static String knownLine(KnownNeverExpanded known) {
    String line =
        "# only known-list entities from "
            + known.file()
            + " that no expansion has covered: "
            + known.excluded()
            + " excluded (some row in the log cites them as an expansion's seed, or they are the"
            + " owner's own minted local entities) — the file's ids are read through the merge"
            + " fold, so a merge's two sides count once.";
```

(The rest of the method — the `--add` clause appended below it — is unchanged.)

In `KnownNeverExpanded.java`, update the `@param excluded` javadoc:

```java
 * @param excluded how many of the file's entities, after the merge fold, some row in the log cites
 *     as an expansion's seed, or that are the owner's own minted local entities ({@code
 *     LocalEntity#isLocal}) — no source will ever answer for one, so it is not a shortfall a later
 *     run could close (#344)
```

### Step 7 — verify (GREEN)

Run, blocking: `./gradlew test --tests '*ExpansionReportTest'`. Confirm green — the golden block,
the `withSinceLine` and `withKnownLine` variants, and every other pinned case in the file (none of
which touch the known-list clause) are unaffected.

Run, blocking: `./gradlew test --tests '*ExpandCliTest'` once more, to confirm Step 1's
`countOn(lines(), "excluded")` assertion (or its fallback, if the parse needed one) now reads `1`.

Run `./gradlew spotlessApply`, then:
`grep -nE '\{@(code|link)[^}]*$' src/main/java/com/robsartin/segue/expand/ExpandCli.java src/main/java/com/robsartin/segue/expand/ExpansionReport.java src/main/java/com/robsartin/segue/expand/KnownNeverExpanded.java`
— expect empty output.

### Step 8 — RED: the `local entity` refusal, moved to `--rated-since`

Task 1, Step 7 deleted the one existing real-run test that observed `EntityExpansion.expand`
refusing a local id (it drove the fact through `--second-hop`, which after Task 1 can no longer
reach it). Add its replacement here, driven through `--rated-since` — the one population this issue
leaves able to name a local id, per the spec and per ADR 66's amendment in Task 4.

Add to `ExpandCliTest.java`, after `reRate(SqliteAffinityStore affinity)`:

```java
  /** Minted, and never merged: a rating can still name it, so it can still reach the refusal. */
  private static final String RATED_LOCAL = "Q00900799";

  private Path ratedLocalGraph(String name) {
    Path db = home.resolve(name);
    try (SqliteAssertionLog log = new SqliteAssertionLog(db);
        SqliteAffinityStore affinity = new SqliteAffinityStore(db)) {
      log.append(
          LocalEntity.minted(RATED_LOCAL, NodeKind.PERSON, "the owner's own minted entity", WHEN));
      affinity.put(new AffinityRecord(RATED_LOCAL, KnownList.PROMOTION_RATING, null, AGAIN));
    }
    return db;
  }
```

Add this test, in the `--rated-since` real-run area — after
`shouldRefuseTheKnownEntityWhenTheGraphHoldsNoNodeForIt` is one reasonable place, since both are
real (non-dry) runs that end in a specific refusal:

```java
  @Test
  @DisplayName(
      "--rated-since is where a rated local id still reaches the local-entity refusal, now that"
          + " --known and --second-hop both exclude one")
  void shouldRefuseTheRatedLocalIdWhenARatedSinceRunVisitsIt() throws Exception {
    // A REAL run, and it reaches no network: EntityExpansion.expand checks LocalEntity.isLocal
    // and returns Refused(LOCAL_ENTITY) before any adapter is asked (#92). This is the same fact
    // the retired --second-hop test proved; a rating is a claim about the owner's own local entity
    // exactly as it is about a Wikidata one, and KnownList.promoted does not filter by shape, so
    // this population is the one left that can still carry one (#344).
    Path db = ratedLocalGraph("rated-local.db");
    captured.list.clear();

    ExpandCli.main(new String[] {"--db", db.toString(), "--rated-since", THE_INSTANT});

    assertThat(countOn(lines(), "considered"))
        .as("the one rated local id — --rated-since does not filter it out")
        .isEqualTo(1);
    assertThat(countOn(lines(), "local entity"))
        .as("EntityExpansion.expand refuses it before any adapter runs")
        .isEqualTo(1);
  }
```

### Step 9 — verify (this step is a green addition, not a red one — say so)

This test exercises behaviour this issue leaves **unchanged**: nothing in `ExpandCli`'s
default/`--rated-since` branch is touched by Task 3, Step 3 (that edit is scoped to the `--known`
branch alone), so `EntityExpansion.expand`'s pre-existing `LOCAL_ENTITY` refusal already produces
this exact output on unmodified `main`. **State this in the task report rather than staging a false
red**: run the test once, blocking (`./gradlew test --tests '*ExpandCliTest'`), confirm it passes
immediately, and say so plainly — this is the "moves to `--rated-since`" half of Task 1 Step 7's
deletion, landing here as new coverage rather than as a RED→GREEN cycle, because the underlying
behaviour was never broken.

### Step 10 — full-file verify

Run, blocking: `./gradlew test --tests '*ExpandCliTest'`. Confirm every test in the file passes —
the new dry-run exclusion test, the new `--rated-since` refusal test, and every pre-existing case.

### Step 11 — commit

```bash
git add src/main/java/com/robsartin/segue/expand/ExpandCli.java \
        src/main/java/com/robsartin/segue/expand/ExpansionReport.java \
        src/main/java/com/robsartin/segue/expand/KnownNeverExpanded.java \
        src/test/java/com/robsartin/segue/expand/ExpandCliTest.java \
        src/test/java/com/robsartin/segue/expand/ExpansionReportTest.java
```

Subject: `ExpandCli's --known population excludes a local id, and the excluded clause says why
(#344)`. Body: the exclusion; the `excluded` clause and `KnownNeverExpanded`'s javadoc now name
both reasons; the retired `--second-hop` refusal test is replaced by a `--rated-since` one, the one
population this issue leaves able to carry a local id. Blank line, `Co-Authored-By: Claude Fable 5.1
<noreply@anthropic.com>`.

---

## Task 4 — prose: the developer guide's four spots, and the two ADR amendments

**Files:**
- `docs/developer-guide.md` (one new paragraph, two rewritten paragraphs, one spot confirmed
  unchanged)
- `docs/adr/0063-a-read-only-census-of-the-graph.md` (one appended amendment)
- `docs/adr/0066-expand-every-promotion-from-a-dev-tool.md` (one appended amendment)

**No test is written for behaviour — this task changes no method body, signature or constant.**
What verifies it: `AdrIndexTest`, `AdrCitationsTest`, `DocumentationLinksTest`,
`DeveloperGuideCensusExamplesTest` and `DeveloperGuideExpandPromotionsExamplesTest` (controls that
neither chapter's `./gradlew` example blocks moved), `JavadocCitationsTest`, and `javadoc -Werror`
inside the full gate (the controller's job, not this task's). Each edit below carries its own
positive control per the Global Constraints.

### Step 1 — positive control: watch `DocumentationLinksTest` fire (RED)

Append this plant to the very end of `docs/developer-guide.md`:

```
[a broken link](adr/0999-does-not-exist.md)
```

Run, blocking: `./gradlew test --tests '*DocumentationLinksTest'`

**Expect a real assertion failure**, not a compile error — a relative-link resolution failure
naming `docs/developer-guide.md` and `adr/0999-does-not-exist.md`. **Quote the actual message in
the task report.** If it passes, the guard is not reading this file and the task stops and reports.

Remove the plant. Re-run the same command and confirm green before touching the real content.

### Step 2 — census chapter: the new `local` paragraph

In `docs/developer-guide.md`, under `### What the two sub-sections mean`, insert one new paragraph
immediately after the `**`never expanded` is a floor, not a queue.**` paragraph (which ends
`` …2026-09-12 amendments for #315). ``) and before the `**`no known neighbour` breaks into three
nested rows…**` paragraph. Locate the exact boundary first:

```bash
grep -n "is a floor, not a queue\|breaks into three nested rows" docs/developer-guide.md
```

Insert, as its own paragraph (blank line before and after):

````markdown
**`local` is nested one level under `in the graph`, and only prints when it is not zero.** It
counts the file's own ids, after the merge fold, that `LocalEntity.isLocal` answers true for — the
owner's own minted entities, on ADR 59's shape — and the print-when-non-zero choice is the one
[ADR 63](adr/0063-a-read-only-census-of-the-graph.md)'s 2026-09-19 amendment for #344 records, the
same one issue #328 already made for `added` and `to add`: a file naming no local id prints the
block byte for byte as it always has. `never expanded` excludes every id counted here — no source
will ever write a seed reference against an id Wikidata will never allocate, so it was never a
shortfall a further `--known` run could close, and counting it there understated the floor.
`expand.ExpandCli`'s `--known` population excludes the same ids for the same reason
([ADR 66](adr/0066-expand-every-promotion-from-a-dev-tool.md)'s 2026-09-19 amendment for #344), so
the row and the population the tool visits agree again.
````

### Step 3 — the `--second-hop` chapter's stop rule

Locate the paragraph:

```bash
grep -n "When to stop running this at all" docs/developer-guide.md
```

Replace the whole `**When to stop running this at all.**` paragraph — the text from that bold
opener through `…the dry run's `considered` is the only bound.` — with:

````markdown
**When to stop running this at all.** Read the run's own block before you take that second census.
Check six cells: `added nothing`, `refused` and `failed` in `promotions`, `neighbours skipped` and
`endpoints refused` under `shortfalls`, and no source named under `unavailable`. `added nothing` is
summed over every source, so a neighbour whose Wikidata answer was nothing but whose MusicBrainz
answer still recorded an edge is not under it; and `EntityExpansion.expand` catches a per-neighbour
`WikidataUnavailableException` and an `UnknownEndpointException` on the append and folds each into
`neighbours skipped` or `endpoints refused`, never into `unavailable`. So all six have to read zero
before the block says every visited entity's Wikidata answer was recorded in full, and only then is
whatever `distinct to expand` still counts afterwards known to be Wikidata-thin rather than merely
unlucky on one source or one neighbour: `SecondHop.toExpandBeside` excludes a neighbour only once
`Expanded` covers it, the same rule and the same residual the `--known` variant's stopping rule
above reads — a neighbour this run recorded only a MusicBrainz-backed edge or a Wikidata forward
claim with no id for leaves no seed reference and stays counted for good
([ADR 63](adr/0063-a-read-only-census-of-the-graph.md)'s 2026-09-14 amendment for #326). **A
`refused` reading `local entity` can no longer come from this run at all** — `SecondHop.toExpandBeside`
now excludes the owner's own minted ids from what it offers before this run ever sees one
([ADR 63](adr/0063-a-read-only-census-of-the-graph.md)'s and
[ADR 66](adr/0066-expand-every-promotion-from-a-dev-tool.md)'s 2026-09-19 amendments for #344); that
refusal can still fire on the no-flag and `--rated-since` runs below, where a rated entity can still
be one of the owner's own. Stop there; a second run visits the same neighbours, calls the same
public APIs and moves neither `distinct to expand` nor `with someone to expand beside`. **When any
of the checklist's six cells is not zero**, that guarantee does not hold, and the `--known`
variant's own rule applies instead: compare this dry run's `considered` against the previous
`--second-hop` dry run's, over the same file — a fall means the last run reached something and
another is worth taking, and an unchanged count means the rest is thin only once a run reporting
all six cells clean has read it. There is still no `--limit`: the dry run's `considered` is the only
bound.
````

### Step 4 — the `--known --add` derive step: confirm unchanged

```bash
grep -n "is what it would expand" docs/developer-guide.md
```

Read the sentence in context (`**2. The census over it**, before anything is written:` through the
sentence ending `…the same rule the \`--known\` chapter states in full.`). **Make no edit here.**
`named` minus `in the graph` was never affected by `never expanded`, and after Task 2 (`neverExpanded`
excludes a local id) and Task 3 (the `--known` population does too), `never expanded` is once more
exactly what a `--add` run's own `--known` pass would go on to expand — the sentence was already
worded that way and is now true rather than merely close, with no wording change needed. Say so in
the task report as a confirmed-unchanged spot, not a skipped one.

### Step 5 — the #342 runbook sentence

Locate:

```bash
grep -n "until issue #344 lands" docs/developer-guide.md
```

Replace the sentence (the paragraph beginning `**The mapping is where a local id lives for
`--known`.**`, specifically its last two sentences: `` `graphCensus --known <mapping>` counts it
under `in the graph`, and, until issue #344 lands, under `never expanded` too, for the reason that
issue states. ``) with:

````markdown
`graphCensus --known <mapping>` counts it under `in the graph` and, since it is one of the owner's
own, under `local` too — never under `never expanded`, and `expandPromotions --known` never visits
it
([ADR 63](adr/0063-a-read-only-census-of-the-graph.md)'s and
[ADR 66](adr/0066-expand-every-promotion-from-a-dev-tool.md)'s 2026-09-19 amendments for #344).
````

The paragraph's first two sentences (`**The mapping is where a local id lives for `--known`.**`
through `…with nothing else to do.`) are unchanged.

### Step 6 — verify (GREEN), all four guide spots

Run, blocking, without `--rerun-tasks`:

```bash
./gradlew test --tests '*DocumentationLinksTest' --tests '*DeveloperGuideCensusExamplesTest' --tests '*DeveloperGuideExpandPromotionsExamplesTest'
```

Confirm all pass, and confirm each **ran** (not `UP-TO-DATE`) in the output. The two
`DeveloperGuide…ExamplesTest` classes are the control that neither chapter's `./gradlew` example
blocks moved — none of Steps 2–5 touch a fenced code block, only surrounding prose.

### Step 7 — positive control: watch `AdrCitationsTest` fire (RED), before either ADR amendment

Append this plant to the end of `docs/adr/0063-a-read-only-census-of-the-graph.md`:

```
Landed in `a1b2c3d4e5f6`.
```

Run, blocking: `./gradlew test --tests '*AdrCitationsTest'`

**Expect a real assertion failure** naming `0063-a-read-only-census-of-the-graph.md` and the hash
`a1b2c3d4e5f6`. **Quote the actual message.** Remove the plant; re-run and confirm green.

### Step 8 — ADR 63: append the amendment

Confirm the anchor first:

```bash
tail -6 docs/adr/0063-a-read-only-census-of-the-graph.md
```

Confirm the last line is `` `javadoc -Werror` inside `./gradlew check`. `` — the end of the
2026-09-14 amendment for #326. Append, after one blank line:

````markdown
**Amendment (2026-09-19, issue #344): a minted local id is no longer counted as a never-expanded
shortfall, on either the row or the three rows that inherit its floor, and the known-list section
gains one row that says how many of an entity's own were counted at all.**

Nothing above is edited and this ADR keeps `Accepted`. `KnownListCensus.Population` gains `local` —
the population's ids the fold holds a node for that `LocalEntity.isLocal` answers true for, on the
canonical side exactly as every other count here is: a local id merged onto a canonical is counted
as the canonical, never as local, because the canonical is what a source can answer for and the
local id no longer is what the population names. `neverExpanded` and its by-kind rows now exclude a
local id for the reason the 2026-09-12 amendment for #315 above already gave the row's own floor:
`domain.Expanded` reads a seed out of Wikidata's own reference shapes, and no source will ever write
one against an id Wikidata's own grammar refuses to allocate, on ADR 59's own decision. Before this
issue such an id sat under `never expanded` forever, counted as a shortfall a further `--known` run
could still close by finding a source. It never could; it is now counted where it belongs.

`CensusReport` prints `local` nested one level under `in the graph`, in each sub-section, only when
it is not zero — the print-when-non-zero choice issue #328 already made for `added` and `to add`,
kept for the same reason: a file naming no local id prints the block byte for byte as before, and
every block already pasted into an issue stays readable against this one.

**The three second-hop rows move with it, from the one rule `SecondHop.toExpandBeside` already
is.** A local neighbour beside an isolated act is now excluded from that rule outright —
`domain.SecondHop`'s own change for this issue — so it is counted under neither `with someone to
expand beside` nor `distinct to expand`, and an isolated act whose only unexpanded neighbour was a
local one now counts under `with no one` instead. `distinctToExpand`'s own javadoc carried a clause
that a `refused` reading of `local entity` from a `--second-hop` run is permanent and does not
withhold the six-cell guarantee the 2026-09-14 amendment for #326 above describes; that clause is
now vacuous rather than wrong — a `--second-hop` run can no longer produce that refusal at all,
since the population it visits never contains a local neighbour to begin with — and the sentence is
removed rather than left describing a case that cannot occur.

**Alternatives rejected.**

- **Counting a local id under `never expanded` and noting it in prose alone.** Rejected: the row's
  own name is the claim, and the developer guide already reads the row as a floor a further
  `--known` run could close (the 2026-09-12 amendment for #315 above). A number that cannot close is
  not that floor, and a footnote does not change what the row says by itself when pasted without
  one.
- **Printing `local` unconditionally, zero or not.** Rejected on the same precedent issue #328 gave
  `added` and `to add`: every block already pasted into an issue would gain a new zero row, which is
  the byte-identity break this project has twice now declined to make.
- **A separate top-level section for local entities, beside `claims`' `local entities minted`.**
  Rejected: that row already counts every local entity the log has ever minted, whether or not it is
  on this file, and duplicating the count under a new heading answers a different question — which
  of *this file's* entities are local — under a name that invites confusing the two.

**Nothing here is unit-testable on its own but for the counting rule and the row itself, and those
are**: `SecondHopTest`, `KnownListCensusTest` and `CensusReportTest` carry the new cases, each seen
red before the change that turns it green. The verification of this *document* is the full gate
over an otherwise unchanged tree: `AdrIndexTest`, `AdrCitationsTest`, `DocumentationLinksTest` for
the relative links above, and `javadoc -Werror` inside `./gradlew check`.
````

### Step 9 — verify (GREEN, ADR 63)

Run, blocking: `./gradlew test --tests '*AdrCitationsTest' --tests '*AdrIndexTest' --tests '*DocumentationLinksTest'`. Confirm all pass.

### Step 10 — positive control: watch `AdrCitationsTest` fire (RED), ADR 66

Append this plant to the end of `docs/adr/0066-expand-every-promotion-from-a-dev-tool.md`:

```
Landed in `f6e5d4c3b2a1`.
```

Run, blocking: `./gradlew test --tests '*AdrCitationsTest'`

**Expect a real assertion failure** naming `0066-expand-every-promotion-from-a-dev-tool.md` and the
hash `f6e5d4c3b2a1`. **Quote the actual message.** Remove the plant; re-run and confirm green.

### Step 11 — ADR 66: append the amendment

Confirm the anchor:

```bash
tail -6 docs/adr/0066-expand-every-promotion-from-a-dev-tool.md
```

Confirm the last line ends `…the relative links above, and \`javadoc -Werror\` inside \`./gradlew
check\`.` — the end of the 2026-09-15 amendment for #328. Append, after one blank line:

````markdown
**Amendment (2026-09-19, issue #344): a `--known` run's population excludes a local id, exactly as
`--second-hop`'s already does from `domain.SecondHop`'s own change for the same issue, and the
`local entity` refusal this ADR's earlier amendments describe is reachable from `--rated-since`
alone.**

Nothing above is edited and this ADR keeps `Accepted`. `ExpandCli`'s `--known` population was the
file's ids, on their canonical side, that `domain.Expanded` does not cover; it now also excludes an
id `LocalEntity.isLocal` answers true for, composed in the same filter and before either the dry
run's preflight or the run's loop ever sees the population — the same exclusion
[ADR 63](0063-a-read-only-census-of-the-graph.md)'s amendment for this issue gives the census's
`never expanded` row, so the two tools read one rule again rather than two that happen to agree on
today's fixtures.

**Before this issue, a `--known` run over a file naming a minted id visited it anyway** — nothing
excluded a local id from the population, so it reached `EntityExpansion.expand`, which has refused
`LOCAL_ENTITY` since issue #92 and refuses it still, before any adapter is asked. The round trip
cost nothing over the network (the refusal fires first), but it spent a `refused` line and a pass of
the loop learning what `LocalEntity.isLocal` already knew at the moment the population was composed.
`domain.SecondHop.toExpandBeside`'s own change for this issue closes the same gap on the population
`--second-hop` composes, so a `local entity` refusal can no longer arise from either of the two
file-driven runs.

**It can still arise from the third population, and that is unchanged.** A run given neither
`--known` nor `--second-hop` composes its population from `KnownList.promoted` over the ratings map
alone — narrowed by `--rated-since` when one is given — and nothing filters that population by
shape: a rating is a claim about the owner's own local entity exactly as it is about a Wikidata one,
and excluding it there would refuse to expand something the owner asked this tool to visit rather
than telling him it cannot be done. The refusal still fires, on the same call, for the same reason.

**The runbook's `--known --add` derive step is true again.** `named` minus `in the graph` is still
what a `--add` run would add — that arithmetic never involved `never expanded` — and `never
expanded` is now, once more, what an `--add` run's own `--known` pass would then expand: before this
issue a minted id already in the graph sat under `never expanded` and inflated a number the sentence
promised was the next step's spend, when no `--add` run would ever visit it. Excluding it from the
row is what makes the sentence true rather than merely close.

**Alternatives rejected.**

- **Filtering at `EntityExpansion.expand` alone, and leaving the population composition unchanged.**
  Rejected: the refusal there already exists and was never the gap — the population naming the id
  at all is. A caller that composes a population still has to know not to count it, or the dry run's
  `considered` keeps naming an entity no real run will ever expand.
- **A `--known` refusal reason distinct from `LOCAL_ENTITY`.** Rejected: the two runs would then
  give an operator two different words for one fact — the id is the owner's own and no source will
  ever answer for it — which is the confusion issue #328's own addition-refusal reasons were
  written to avoid repeating.
- **Excluding a local id from `--rated-since`'s population too, for symmetry.** Rejected: this
  population is not drawn from the graph the way the other two are — it is composed from ratings —
  and a rating on a local entity is exactly as real a claim as a rating on anything else. Refusing to
  even attempt it would hide the one honest answer this tool has for that population, which is the
  refusal itself.

**Nothing here is unit-testable on its own but for the exclusion and the refusal's remaining path,
and those are**: `ExpandCliTest` carries a `--known --dry-run` case over a file naming a local id,
seen red before the exclusion and green after, and a `--rated-since` case carrying the refusal this
amendment says still fires — the same case an earlier `--second-hop` fixture used to carry, moved
here because `--second-hop` can no longer produce it. The verification of this *document* is the
full gate over an otherwise unchanged tree: `AdrIndexTest`, `AdrCitationsTest`,
`DocumentationLinksTest` for the relative link above, and `javadoc -Werror` inside `./gradlew
check`.
````

### Step 12 — verify (GREEN, ADR 66, and the whole task)

Run, blocking, without `--rerun-tasks`:

```bash
./gradlew test --tests '*AdrCitationsTest' --tests '*AdrIndexTest' --tests '*DocumentationLinksTest' --tests '*DeveloperGuideCensusExamplesTest' --tests '*DeveloperGuideExpandPromotionsExamplesTest' --tests '*JavadocCitationsTest'
```

Confirm every class passes and every one of them **ran**.

### Step 13 — commit

```bash
git add docs/developer-guide.md \
        docs/adr/0063-a-read-only-census-of-the-graph.md \
        docs/adr/0066-expand-every-promotion-from-a-dev-tool.md
```

Subject: `Developer guide and ADR 63/66 record the local row and the floor rows' exclusion (#344)`.
Body: the four guide spots (one new paragraph, two rewritten, one confirmed unchanged) and the two
appended ADR amendments. Blank line, `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`.

---

## After Task 4

Report to the controller: all four tasks landed, each committed independently and green on its own
sub-suite; the full gate (`./gradlew check`) is the controller's own next step, not run here.
