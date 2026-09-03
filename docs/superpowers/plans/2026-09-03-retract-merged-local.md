# Retracting a merged local id takes its stand-in's edges — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A log that mints an entity, merges it onto a canonical id, claims an edge against that canonical id and then retracts the local id **boots**, and both folds hold the same thing: the edge is gone with the merge that gave its endpoint a node, nothing dangles, and nothing else moves.

**Architecture:** One new set in `domain` — `Equivalences.retractedStandIns(log)` — carried as a third component of the `Equivalences` the two folds build through a new factory `Equivalences.folding(log)`, and consulted by `Equivalences.foldEndpoints`, which both folds already call for every edge and both already handle yielding nothing for (the self-loop rule). Neither fold's loop grows a line; each changes one factory call. `Equivalences.in`, `Equivalences.stands`, `Equivalences.standIns`, `IngestService.standIn` and the rating carry are untouched.

**Tech Stack:** Java 25, Gradle (plain `./gradlew`), JUnit 5, AssertJ, TinkerGraph, SQLite.

**Spec:** `docs/superpowers/specs/2026-09-03-retract-merged-local-design.md`

## Global Constraints

- **Pure TDD / red first**: every behaviour is seen red for the right reason — a real assertion failure or the reproduced replay exception, never a compile error — before the code that makes it green. Quote the actual failure text in the report. Test names `should<Expected>When<Condition>` with `@DisplayName`.
- **Every guard gets a positive control**: plant the defect, watch the check fire, quote it, remove the plant. Written out as steps below.
- **Mikado**: the gate is green before every commit, and no commit ships a rule that is wrong on a case a later task then corrects — Task 3 lands the exclusions in the same commit as the rule they bound, in three red→green loops.
- **Stage by explicit path, git stderr visible — never `git add -A`, never `2>/dev/null` on `git add`.** Read `git status` before every commit. Commits end `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`. Never cite a `.superpowers/` path from a committed file.
- Gate, **blocking, never backgrounded**: `SEGUE_REQUIRE_BROWSER=true SEGUE_REQUIRE_GRAPHVIZ=true ./gradlew check --rerun-tasks`. Fast loops named per task. Run `./gradlew spotlessApply` before each gate.
- **Only JDK 25 is installed.** Plain `./gradlew`; never `/usr/libexec/java_home -v 21` (it silently returns 25).
- **Never run a writing dev task** (`own`, `ownClaim`, `retractEntity`, any seeding task). `~/.segue/segue.db` is never read, written, copied or created.
- Every id invented in `src/test` must take an unallocatable shape or `arch/StandInQidsDenoteNothingTest` reds: two leading zeros for a local entity (ADR 59), eleven digits with no leading zero for a merge's canonical side (ADR 62), one leading zero for anything else (ADR 58). Every id this plan adds already has one, so no allowlist entry is needed — that list is keyed `(id, file, context)` and holds only deliberately **real** ids (#216).
- Main-source javadoc naming a test class is checked by `arch/JavadocCitationsTest`: the class must exist under `src/test/java` with that exact name. Task 1 creates `RetractedStandInTakesItsEdgesTest`; nothing before it may cite it.
- **YAGNI**: no parameter, helper or accessor beyond what a step below actually uses.
- Machine is loaded: no wall-clock assertions anywhere.

---

### Task 1: The reproducing test, seen red, parked

**Files:** Modify `src/test/java/com/robsartin/segue/export/InventedGraph.java`. Create `src/test/java/com/robsartin/segue/export/RetractedStandInTakesItsEdgesTest.java`.

The precedent for parking a guard red is `TwiceMergedIdLeavesNoOrphanTest`, whose javadoc records that three of its tests were committed `@Disabled`, red for the honest reason, until #221 fixed the defect. Do the same: the tree stays green at every commit, and Task 3 removes the annotations.

- [ ] **Step 1 — two invented ids.** Add to `InventedGraph`, after `STRAY`:

```java
  /**
   * An eighth id the owner minted, and the one issue #224 turns on: merged onto {@link #FORFEIT},
   * given an owner edge naming {@link #FORFEIT} directly, and then retracted. Two leading zeros,
   * for {@link #ALMANAC}'s reason.
   */
  static final String LAPSE = "Q008";
```

  and, after `REROUTED`:

```java
  /**
   * A seventh canonical id: the one {@link #LAPSE} was merged onto before the owner retracted the
   * local id, so the merge that gave this id its only node stops projecting and nothing else holds
   * one for it (#224). ADR 62's eleven-digit shape, for the reason {@link #KETTLES} takes it.
   */
  static final String FORFEIT = "Q10000900112";
```

- [ ] **Step 2 — the test class.** Create it in full:

```java
package com.robsartin.segue.export;

import static com.robsartin.segue.export.InventedGraph.ALMANAC;
import static com.robsartin.segue.export.InventedGraph.FORFEIT;
import static com.robsartin.segue.export.InventedGraph.HOLLOW_TIDE;
import static com.robsartin.segue.export.InventedGraph.LAPSE;
import static com.robsartin.segue.export.InventedGraph.WREN;
import static com.robsartin.segue.export.InventedGraph.merged;
import static com.robsartin.segue.export.InventedGraph.minted;
import static com.robsartin.segue.export.InventedGraph.node;
import static com.robsartin.segue.export.InventedGraph.owned;
import static com.robsartin.segue.export.InventedGraph.retract;
import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.segue.domain.EdgeRecord;
import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.export.InventedGraph.FakeAssertionLog;
import com.robsartin.segue.ingest.GraphProjector;
import com.robsartin.segue.port.IdentityMerge;
import com.robsartin.segue.tinker.TinkerGraphStore;
import java.io.IOException;
import java.io.StringWriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Issue #224: retracting a local id the owner had already merged takes the edges that name the
 * stand-in that merge created — and only those.
 *
 * <p><b>Why this is not a case inside {@code BothFoldsAgreeTest}.</b> That test compares the two
 * folds with each other, and here one of them <em>throws</em>: {@code GraphProjector} dies inside
 * the projection before anything is compared, so the comparison never runs and the failure it
 * reports is an exception rather than a difference. A blind spot of that shape needs a test that
 * looks at the thing itself, which is what this file does — each fold on its own, and the DOT
 * artefact beside them. {@code BothFoldsAgreeTest} gains the shape as well, so that a half-fix
 * reds there too.
 *
 * <p><b>Three of these were committed {@code @Disabled}, red for the honest reason: the log would
 * not boot.</b> Measured on {@code 0783492} — the commit that landed #221, so the surviving-edge
 * widening was already in place and does not reach this case:
 *
 * <pre>
 *   Equivalences.standIns(log, KindMapper::rederive)  {}
 *   LogProjection.of(log).nodes()                     [Q0900101]
 *   LogProjection.of(log).edges()                     []
 *   LogProjection.of(log).danglingEdges()             1
 *   GraphProjector.project(log, …)                    IllegalStateException:
 *       replay failed at sequence 5
 *       caused by: assertion references unknown entity Q10000900112 - upsert the node first
 * </pre>
 *
 * <p>{@code shouldKeepTheStandInAndItsEdgeWhenNothingIsRetracted} was green in both worlds and
 * stayed enabled throughout: it is what says the fixture holds the merge and the edge in the first
 * place, so the absences above mean something.
 *
 * <p>Every entity here is invented (ADR 40, issue #37).
 */
class RetractedStandInTakesItsEdgesTest {

  /**
   * Minted, merged onto a canonical id no source has claimed, given an owner edge naming that
   * canonical id DIRECTLY — which {@code OwnRun} offers as an endpoint the moment the merge's
   * stand-in exists — and then retracted. The {@code WREN → HOLLOW_TIDE} edge is here so that the
   * graph the fix leaves is not simply an empty one.
   */
  private static FakeAssertionLog retractedAfterMergingLog() {
    return new FakeAssertionLog()
        .with(
            node(WREN, NodeKind.PERSON, "Wren Alderman"),
            node(HOLLOW_TIDE, NodeKind.GROUP, "Hollow Tide"),
            minted(LAPSE, NodeKind.WORK, "a working title he took back"),
            merged(LAPSE, FORFEIT),
            owned(WREN, FORFEIT, "INFLUENCED_BY"),
            owned(WREN, HOLLOW_TIDE, "INFLUENCED_BY"),
            retract(LAPSE));
  }

  @Test
  @DisplayName("the exporter's fold drops the edge naming a stand-in a retraction took away")
  void shouldFoldNoEdgeOntoACanonicalIdWhenTheMergedLocalWasRetracted() {
    LogProjection folded = LogProjection.of(retractedAfterMergingLog());

    assertThat(folded.nodes())
        .as("the merge stopped projecting, so nothing holds a node for the canonical id")
        .doesNotContainKey(FORFEIT);
    assertThat(folded.edges().stream().map(RetractedStandInTakesItsEdgesTest::key))
        .as("the edge that named it goes with it, rather than being counted as a dangling edge")
        .containsExactly(WREN + " INFLUENCED_BY " + HOLLOW_TIDE);
    assertThat(folded.danglingEdges())
        .as(
            "danglingEdges is the count whose own javadoc says it should always be zero, because a"
                + " log holding one fails replay at boot - it read 1 before this fix")
        .isZero();
  }

  @Test
  @DisplayName("the boot replay survives a log that retracts a merged local id")
  void shouldReplayWithoutThrowingWhenAMergedLocalIdIsRetracted() {
    try (TinkerGraphStore replayed = new TinkerGraphStore()) {
      GraphProjector.project(retractedAfterMergingLog(), replayed, IdentityMerge.NONE);

      assertThat(replayed.node(FORFEIT))
          .as("no node under the canonical id, so the edge naming it cannot be applied")
          .isEmpty();
      assertThat(replayed.node(LAPSE)).as("and none under the retracted local id").isEmpty();
      assertThat(replayed.edgeCount())
          .as("the owner's other edge is untouched, so this is not an empty graph agreeing")
          .isEqualTo(1);
    }
  }

  @Test
  @DisplayName("a full export draws no node for a canonical id whose merge was retracted")
  void shouldDrawNoNodeForACanonicalIdWhenItsMergeWasRetracted() throws IOException {
    FakeAssertionLog log = retractedAfterMergingLog();
    try (TinkerGraphStore graph = new TinkerGraphStore()) {
      GraphProjector.project(log, graph, IdentityMerge.NONE);
      StringWriter out = new StringWriter();

      new DotWriter().write(new ViewSelector(graph, log).full(), out);

      assertThat(out.toString())
          .as("asserted on the artefact somebody keeps and opens in Gephi weeks later")
          .doesNotContain("\"" + FORFEIT + "\"");
    }
  }

  /** The same fixture with nothing retracted: the merge stands and the edge is on the graph. */
  private static FakeAssertionLog mergedAndNotRetractedLog() {
    return new FakeAssertionLog()
        .with(
            node(WREN, NodeKind.PERSON, "Wren Alderman"),
            node(HOLLOW_TIDE, NodeKind.GROUP, "Hollow Tide"),
            minted(LAPSE, NodeKind.WORK, "a working title he took back"),
            merged(LAPSE, FORFEIT),
            owned(WREN, FORFEIT, "INFLUENCED_BY"),
            owned(WREN, HOLLOW_TIDE, "INFLUENCED_BY"));
  }

  @Test
  @DisplayName("the stand-in and its edge are both there while nothing is retracted")
  void shouldKeepTheStandInAndItsEdgeWhenNothingIsRetracted() {
    LogProjection folded = LogProjection.of(mergedAndNotRetractedLog());

    assertThat(folded.nodes())
        .as("without this the absences above would hold over a fixture that never had them")
        .containsKey(FORFEIT);
    assertThat(folded.nodes().get(FORFEIT).label()).isEqualTo("a working title he took back");
    assertThat(folded.edges().stream().map(RetractedStandInTakesItsEdgesTest::key))
        .contains(WREN + " INFLUENCED_BY " + FORFEIT);
    assertThat(folded.danglingEdges()).isZero();
  }

  private static String key(EdgeRecord edge) {
    return edge.fromQid() + " " + edge.typeCode() + " " + edge.toQid();
  }
}
```

- [ ] **Step 3 — RED, three of them.** Run, blocking: `./gradlew test --tests 'com.robsartin.segue.export.RetractedStandInTakesItsEdgesTest'`. Expect `shouldKeepTheStandInAndItsEdgeWhenNothingIsRetracted` green and the other three red — one on `expected: 0 but was: 1` (the exporter's fold) and two on `IllegalStateException: replay failed at sequence 5 … assertion references unknown entity Q10000900112 - upsert the node first`. **Quote all three failures verbatim in the report.** If the sequence number is not 5, quote what it actually is and correct the class javadoc to match.

- [ ] **Step 4 — park the three.** Add `import org.junit.jupiter.api.Disabled;` and annotate the three failing methods with `@Disabled("#224: red until the fold rule lands - see this class's javadoc")`. Re-run: 4 tests, 3 skipped, 0 failed.

- [ ] **Step 5 — commit.** `./gradlew spotlessApply`, then the full gate blocking. `git status`, then `git add src/test/java/com/robsartin/segue/export/InventedGraph.java src/test/java/com/robsartin/segue/export/RetractedStandInTakesItsEdgesTest.java` (stderr visible), then commit: `#224: the reproducing test for a retracted merged local id, parked red`.

---

### Task 2: `Retractions.reaches`, the one question `survives` cannot answer

**Files:** Modify `src/main/java/com/robsartin/segue/domain/Retractions.java`, `src/test/java/com/robsartin/segue/domain/RetractionsTest.java`.

Task 3 has to tell a merge dropped because its **local** side was retracted from one dropped because its canonical side was: only the first leaves a canonical id holding a node nothing else supports. `survives` takes the whole row and answers about both ends at once, so it cannot say which end did it.

- [ ] **Step 1 — RED.** Add to `RetractionsTest`, at the end:

```java
  @Test
  @DisplayName("a retraction reaches an id at a row before it, and not at one after it")
  void shouldReachOnlyTheRowsBeforeItWhenAnIdWasRetracted() {
    List<LoggedAssertion> log =
        List.of(node("Q0900101"), retract("Q0900101"), node("Q0900101"));
    Retractions retractions = Retractions.in(log);

    assertThat(retractions.reaches(0, "Q0900101"))
        .as("the claim before the retraction is reached, which is what drops it from the fold")
        .isTrue();
    assertThat(retractions.reaches(2, "Q0900101"))
        .as("backwards only (ADR 44): a claim appended afterwards stands")
        .isFalse();
    assertThat(retractions.reaches(0, "Q0900102"))
        .as("an id nothing retracted is reached at no row at all")
        .isFalse();
  }
```

  Add the stub that makes it compile, in `Retractions`, immediately above `isRetractedAt`:

```java
  public boolean reaches(int index, String qid) {
    Objects.requireNonNull(qid, "qid");
    return false;
  }
```

  Run `./gradlew test --tests 'com.robsartin.segue.domain.RetractionsTest'` and confirm the failure is the **assertion** — `expected: true but was: false` on the first assertion — and not a compile error. Quote it.

- [ ] **Step 2 — GREEN.** Replace the stub body with `return isRetractedAt(index, qid);` and give it the javadoc:

```java
  /**
   * Whether a retraction of {@code qid} reaches the row at {@code index} — {@link #survives}'s own
   * test, asked about one id rather than about a whole row.
   *
   * <p><b>Public for one question {@link #survives} cannot answer</b> (#224). A {@link SameAs} is
   * dropped when <em>either</em> of its two ids is retracted, and {@code
   * Equivalences.retractedStandIns} has to tell those two cases apart: a merge dropped because its
   * LOCAL side went leaves a canonical id holding a node nothing else supports, and one dropped
   * because its canonical side went does not. Answering it with a second copy of the comparison
   * would put the "last, not first" rule in two places.
   */
```

  Re-run: green.

- [ ] **Step 3 — positive control.** Change the body to `return index > lastRetraction.getOrDefault(qid, Integer.MAX_VALUE);` (the reversed comparison), re-run, and confirm the new test reds on the first assertion. Quote it; restore; green.

- [ ] **Step 4 — commit.** `spotlessApply`, full gate blocking, `git status`, `git add src/main/java/com/robsartin/segue/domain/Retractions.java src/test/java/com/robsartin/segue/domain/RetractionsTest.java`, commit: `#224: a retraction can be asked which of a merge's two ids it reached`.

---

### Task 3: the rule, its two boundaries, and both folds

**Files:** Modify `src/main/java/com/robsartin/segue/domain/Equivalences.java`, `src/main/java/com/robsartin/segue/ingest/GraphProjector.java`, `src/main/java/com/robsartin/segue/export/LogProjection.java`, `src/test/java/com/robsartin/segue/domain/EquivalencesTest.java`, `src/test/java/com/robsartin/segue/export/RetractedStandInTakesItsEdgesTest.java`.

Three red→green loops in one commit, deliberately: loop 1's rule over-reaches, and loops 2 and 3 are the reds that bound it. Splitting them across commits would ship a rule that wipes a source's edges.

- [ ] **Step 1 — RED, the defect itself.** Remove the three `@Disabled` annotations and the now-unused `Disabled` import from `RetractedStandInTakesItsEdgesTest`. Run `./gradlew test --tests 'com.robsartin.segue.export.RetractedStandInTakesItsEdgesTest'` and confirm the same three failures Task 1 quoted.

- [ ] **Step 2 — GREEN, the rule.** In `Equivalences`, four edits.

  (a) The record header and the compact constructor gain a third component:

```java
public record Equivalences(
    Map<String, String> canonicalByLocal,
    Set<String> referencedEndpoints,
    Set<String> retractedStandIns) {
```

```java
    retractedStandIns =
        Collections.unmodifiableSet(
            new LinkedHashSet<>(Objects.requireNonNull(retractedStandIns, "retractedStandIns")));
```

  with the `@param` the javadoc gate requires, appended to the class javadoc's parameter list:

```java
 * @param retractedStandIns the canonical ids a retraction emptied (#224): a merge named each of
 *     them and a retraction of that merge's LOCAL side dropped it, and nothing else in the
 *     projection holds a node for the id — no surviving node claim, and no surviving merge whose
 *     stand-in it still is. {@link #foldEndpoints} yields nothing for an edge naming one, because
 *     the endpoint the edge was claimed against was the retracted entity under the name its merge
 *     gave it. Populated only by {@link #folding}; empty everywhere else, including {@link #NONE}
 *     and {@link #in}, which have no edge to fold. Insertion-ordered for {@link
 *     #referencedEndpoints}' reason - nothing reads the order, and a record's {@code toString}
 *     prints it into every failing assertion over an {@code Equivalences}
```

  (b) The existing two-argument constructor becomes a convenience one, above the one-argument one:

```java
  /**
   * A caller that has the merges and the surviving edges but no fold to perform — {@link #in},
   * whose readers ask about ratings, labels and known lists and never about an edge's endpoints.
   * An empty {@link #retractedStandIns} is exactly as accurate there as a computed one: {@link
   * #foldEndpoints} is the only method that reads it, and no caller of {@link #in} calls it.
   */
  public Equivalences(Map<String, String> canonicalByLocal, Set<String> referencedEndpoints) {
    this(canonicalByLocal, referencedEndpoints, Set.of());
  }
```

  (c) The new set and the new factory, immediately after `standIns`:

```java
  /**
   * The canonical ids a retraction emptied — a merge gave each of them its only node, a retraction
   * of that merge's local side took the merge away, and nothing else holds a node for the id
   * (#224).
   *
   * <p><b>Why an edge naming one does not project, and why that is ADR 44 rather than a delete.</b>
   * {@code OwnRun} offers a merge's canonical id as a claimable endpoint the moment its stand-in
   * exists, so the owner can claim an edge against it. Retracting the local id afterwards drops the
   * merge — {@link Retractions#survives} drops a {@link SameAs} on the edge rule, either side — and
   * with it the only node that id ever had. The edge survives on its own terms, names an endpoint
   * no fold holds, and {@code TinkerGraphStore.record} refuses it: {@code replay failed at sequence
   * … assertion references unknown entity … - upsert the node first}, at every boot, on rows ADR 19
   * forbids deleting. The claim was one the owner made about the entity he has just retracted,
   * written under the name his own merge gave it, so it goes with it. Nothing is deleted: the log
   * keeps every row, and this changes only what the fold makes of them.
   *
   * <p><b>Only the local side counts</b>, which is why {@link Retractions#reaches} exists. A merge
   * dropped because its CANONICAL side was retracted leaves nothing to repair here: that id is
   * retracted outright, and {@link Retractions#survives} has already dropped every edge naming it.
   *
   * <p><b>No re-derivation parameter, unlike {@link #standIns} and {@link #localsOfMerges}.</b>
   * This reads which canonical ids have a stand-in, never what kind that node is, and the key set
   * of {@link #standIns} cannot depend on the re-derivation: {@link #localsOfMerges} decides which
   * merges have a local side by survival alone and {@link #stands} reads no kind, so the operator
   * only ever sets a value this method discards. It is checked rather than asserted — {@code
   * EquivalencesTest.shouldNameTheSameCanonicalIdsWhateverKindTheFoldDerives} compares the key sets
   * under two re-derivations that disagree about every kind. The parameter is left off so that
   * {@code retract} — which needs this set for its report and must not learn Wikidata's vocabulary
   * (ADR 44: "a retraction is nobody's vocabulary") — can call it.
   */
  public static Set<String> retractedStandIns(List<LoggedAssertion> log) {
    Objects.requireNonNull(log, "log");
    Retractions retractions = Retractions.in(log);
    Set<String> emptied = new LinkedHashSet<>();
    for (int i = 0; i < log.size(); i++) {
      if (log.get(i) instanceof SameAs merge && retractions.reaches(i, merge.localQid())) {
        emptied.add(merge.canonicalQid());
      }
    }
    return Collections.unmodifiableSet(emptied);
  }
```

  **This rule over-reaches, knowingly and only within this task.** As written it would empty a
  canonical id a source has claimed as a node, and one a second surviving merge still stands in for.
  Steps 4 and 5 are the reds that bound it, and the commit at Step 8 is the first that contains
  either. Do not commit before Step 8. Leave the two exclusion paragraphs out of the javadoc until
  the code they describe exists — Step 4 and Step 5 each add their own.

  Then, immediately below it, the factory:

```java
  /**
   * The merges as a fold reads them: {@link #in}, plus the canonical ids a retraction emptied
   * (#224).
   *
   * <p><b>Named rather than an overload of {@link #in}, on {@link #localsOfMerges}' reason.</b> The
   * two folds are the only callers of {@link #foldEndpoints} that hold a log, and an overload
   * quietly giving one of them the older, edge-blind answer is how the two would drift while
   * looking identical at the call site. {@code GraphProjector.project} and {@code LogProjection.of}
   * both build their equivalences here; every other caller of {@link #in} — {@code OwnRun}, {@code
   * ratings/Labels}, {@code KnownList}, {@code RateRun} — asks about ratings, labels and known
   * lists and folds no edge.
   */
  public static Equivalences folding(List<LoggedAssertion> log) {
    Equivalences merges = Equivalences.in(log);
    return new Equivalences(
        merges.canonicalByLocal(), merges.referencedEndpoints(), retractedStandIns(log));
  }
```

  `LinkedHashSet`, `Set`, `Collections` and `UnaryOperator` are all already imported by this file.

  (d) `foldEndpoints(AssertionRecord)` gains the check, **above** the unchanged-claim shortcut, because an edge that names no merged id at all still has to be dropped:

```java
    if (retractedStandIns.contains(claim.fromQid())
        || retractedStandIns.contains(claim.toQid())) {
      // A retraction took away the merge that gave this endpoint its only node (#224). Above the
      // shortcut below, deliberately: such an edge usually names no merged id at all, so the
      // "most of a log names nothing merged" fast path would return it unchanged.
      return Optional.empty();
    }
```

  and its javadoc gains a paragraph beside the self-loop one:

```java
   * <p><b>The second thing that yields nothing is a retraction, and it is a decision too</b>
   * (#224). An edge naming a {@link #retractedStandIns} id was claimed against a stand-in a
   * retraction has taken away, so the fold has no endpoint for it and neither would the store. It
   * is dropped rather than replayed into nothing: see that component, and ADR 44's 2026-09-03
   * amendment for why tolerating it as a dangling edge was refused a second time.
```

- [ ] **Step 3 — GREEN, both folds.** In `GraphProjector.project`, change `Equivalences.in(assertions)` to `Equivalences.folding(assertions)` and extend the comment above it with: `// folding() rather than in(): the fold is also where an edge naming a stand-in a retraction took away stops projecting (#224).` In `LogProjection.of`, change `Equivalences.in(logged)` to `Equivalences.folding(logged)` and add the same sentence to the comment above it. Neither loop changes.

  Run `./gradlew test --tests 'com.robsartin.segue.export.RetractedStandInTakesItsEdgesTest'`: 4 tests, 0 skipped, 0 failed.

- [ ] **Step 4 — RED, boundary 1: a source's own claim.** Add to `RetractedStandInTakesItsEdgesTest`:

```java
  /**
   * The same shape with a source claiming the canonical id as a node of its own, before the merge.
   * Retracting the local id must leave that claim and every edge naming it exactly where they are.
   */
  private static FakeAssertionLog retractedAfterMergingOntoAClaimedIdLog() {
    return new FakeAssertionLog()
        .with(
            node(WREN, NodeKind.PERSON, "Wren Alderman"),
            node(FORFEIT, NodeKind.GROUP, "the name the source already had"),
            minted(LAPSE, NodeKind.WORK, "a working title he took back"),
            merged(LAPSE, FORFEIT),
            owned(WREN, FORFEIT, "INFLUENCED_BY"),
            retract(LAPSE));
  }

  @Test
  @DisplayName("an edge naming a canonical id a source claimed survives the local id's retraction")
  void shouldKeepAnEdgeNamingACanonicalIdASourceClaimedWhenTheMergedLocalIsRetracted() {
    LogProjection folded = LogProjection.of(retractedAfterMergingOntoAClaimedIdLog());

    assertThat(folded.nodes())
        .as("the source's node claim is untouched by a retraction of the owner's local id")
        .containsKey(FORFEIT);
    assertThat(folded.edges().stream().map(RetractedStandInTakesItsEdgesTest::key))
        .as("so the edge naming it is untouched too - the guide's own promise")
        .containsExactly(WREN + " INFLUENCED_BY " + FORFEIT);
  }
```

  Run `./gradlew test --tests 'com.robsartin.segue.export.RetractedStandInTakesItsEdgesTest'`. Expect it **red** on the edges assertion — `expected: [Q0900101 INFLUENCED_BY Q10000900112] but was: []` — because Step 2's rule empties the canonical id whatever else claims it. Quote it. Add the matching unit test from Step 6's first two blocks now if you prefer them beside the code; either way both must be seen red here.

  **GREEN.** Add the `held` set to `retractedStandIns`, above the loop that builds `emptied`:

```java
    Set<String> held = new LinkedHashSet<>();
    for (int i = 0; i < log.size(); i++) {
      LoggedAssertion assertion = log.get(i);
      if (!retractions.survives(i, assertion)) {
        continue;
      }
      switch (assertion) {
        case NodeAssertion claim -> held.add(claim.qid());
        case LocalEntity minted -> held.add(minted.qid());
        // An edge, a merge and a retraction all claim no node. Named explicitly rather than
        // through a default, matching Retractions.survives and Equivalences.in: a default arm
        // would let a seventh LoggedAssertion that DOES claim a node compile silently into
        // "claims nothing" and empty a canonical id the log holds.
        case AssertionRecord ignored -> {}
        case OwnerEdge ignored -> {}
        case SameAs ignored -> {}
        case Retraction ignored -> {}
      }
    }
```

  and the clause `&& !held.contains(merge.canonicalQid())` to the `if` below it. Add to the javadoc:

```java
   * <p><b>A canonical id the projection holds on its own account is not emptied.</b> A source may
   * have claimed it as a node — the developer guide's promise that "what a source claimed about the
   * canonical id is untouched" — and then the merge was never the only thing holding it up. Without
   * this, retracting one thing the owner minted would strip the edges off a real Wikidata entity's
   * whole expansion.
```

  Re-run: green.

- [ ] **Step 5 — RED, boundary 2: a second merge still standing.** Add:

```java
  /**
   * Two local ids merged onto ONE canonical id, and only one of them retracted. The other merge
   * still names the stand-in, so the id is not emptied and the edge naming it stays.
   */
  private static FakeAssertionLog oneOfTwoMergesRetractedLog() {
    return new FakeAssertionLog()
        .with(
            node(WREN, NodeKind.PERSON, "Wren Alderman"),
            minted(LAPSE, NodeKind.WORK, "a working title he took back"),
            minted(ALMANAC, NodeKind.WORK, "The Salt Almanac"),
            merged(LAPSE, FORFEIT),
            merged(ALMANAC, FORFEIT),
            owned(WREN, FORFEIT, "INFLUENCED_BY"),
            retract(LAPSE));
  }

  @Test
  @DisplayName("an edge naming a canonical id a surviving merge still stands in for is kept")
  void shouldKeepAnEdgeNamingACanonicalIdWhenAnotherMergeStillStandsInForIt() {
    LogProjection folded = LogProjection.of(oneOfTwoMergesRetractedLog());

    assertThat(folded.nodes())
        .as("the second merge's stand-in is what the id has now, and it is not the retracted one")
        .containsKey(FORFEIT);
    assertThat(folded.nodes().get(FORFEIT).label()).isEqualTo("The Salt Almanac");
    assertThat(folded.edges().stream().map(RetractedStandInTakesItsEdgesTest::key))
        .containsExactly(WREN + " INFLUENCED_BY " + FORFEIT);
    assertThat(folded.danglingEdges()).isZero();
  }
```

  Run: expect it **red** on the edges assertion — `expected: [Q0900101 INFLUENCED_BY Q10000900112] but was: []`. The node assertions above it pass, which is the shape of the defect: the second merge's stand-in really is there, and the rule from Step 4 has emptied the id anyway, on the retracted merge's account. Quote the failure.

  **GREEN.** Seed `held` with the ids a stand-in already covers, replacing `Set<String> held = new LinkedHashSet<>();` with:

```java
    Set<String> held = new LinkedHashSet<>(standIns(log, UnaryOperator.identity()).keySet());
```

  and add the second javadoc paragraph:

```java
   * <p><b>Nor is one a surviving merge still stands in for.</b> Two local ids merged onto one
   * canonical id and only one of them retracted leaves the other merge's stand-in exactly where it
   * was, so the id has a node and the edges naming it have an endpoint. {@link #standIns} is the
   * one place that answers "which canonical ids have a stand-in", and this reads it rather than
   * deciding it again.
```

  Re-run: green. Then add the paragraph the two keeping tests earn to `RetractedStandInTakesItsEdgesTest`'s class javadoc, above the "Every entity here is invented" line:

```java
 * <p><b>The two keeping tests are the rule's boundary.</b> A canonical id a source has claimed as a
 * node, and one a surviving merge still stands in for, both keep their node and their edge:
 * retracting the local id reaches what the merge created and nothing else, which is the developer
 * guide's own promise that "what a source claimed about the canonical id is untouched". Both were
 * measured green before the fix and both were seen red against the rule without its exclusions.
```

- [ ] **Step 6 — the domain's own four, and the key-set guard.** Add to `EquivalencesTest`, after the last `standIns` test:

```java
  @Test
  @DisplayName("a canonical id whose merge a retraction of the local side dropped is emptied")
  void shouldEmptyACanonicalIdWhenARetractionReachedItsMergesLocalSide() {
    List<LoggedAssertion> log =
        List.of(
            LocalEntity.minted(MINTED, NodeKind.WORK, "The Salt Almanac", WHEN),
            SameAs.declared(MINTED, CANONICAL, WHEN),
            new Retraction(MINTED, "the mint was a mistake", WHEN));

    assertThat(Equivalences.retractedStandIns(log)).containsExactly(CANONICAL);
  }

  @Test
  @DisplayName("a canonical id a source claimed as a node of its own is not emptied")
  void shouldEmptyNoCanonicalIdWhenASourceHasClaimedItAsANode() {
    List<LoggedAssertion> log =
        List.of(
            LocalEntity.minted(MINTED, NodeKind.WORK, "The Salt Almanac", WHEN),
            new NodeAssertion(CANONICAL, NodeKind.GROUP, "the source's own name", SOURCE),
            SameAs.declared(MINTED, CANONICAL, WHEN),
            new Retraction(MINTED, "the mint was a mistake", WHEN));

    assertThat(Equivalences.retractedStandIns(log)).isEmpty();
  }

  @Test
  @DisplayName("a canonical id a surviving merge still stands in for is not emptied")
  void shouldEmptyNoCanonicalIdWhenASurvivingMergeStillNamesIt() {
    List<LoggedAssertion> log =
        List.of(
            LocalEntity.minted(MINTED, NodeKind.WORK, "The Salt Almanac", WHEN),
            LocalEntity.minted(OTHER_MINTED, NodeKind.WORK, "the other one", WHEN),
            SameAs.declared(MINTED, CANONICAL, WHEN),
            SameAs.declared(OTHER_MINTED, CANONICAL, WHEN),
            new Retraction(MINTED, "the mint was a mistake", WHEN));

    assertThat(Equivalences.retractedStandIns(log)).isEmpty();
  }

  @Test
  @DisplayName("a merge a retraction of the CANONICAL side dropped empties nothing")
  void shouldEmptyNoCanonicalIdWhenTheRetractionReachedTheCanonicalSide() {
    List<LoggedAssertion> log =
        List.of(
            LocalEntity.minted(MINTED, NodeKind.WORK, "The Salt Almanac", WHEN),
            SameAs.declared(MINTED, CANONICAL, WHEN),
            new Retraction(CANONICAL, "the merge named the wrong item", WHEN));

    assertThat(Equivalences.retractedStandIns(log))
        .as(
            "that id is retracted outright, so Retractions.survives has already dropped every edge"
                + " naming it - emptying it here as well would be a second rule saying the same"
                + " thing, and a different one the moment either changed")
        .isEmpty();
  }

  @Test
  @DisplayName("the canonical ids a stand-in exists for do not depend on the derived kind")
  void shouldNameTheSameCanonicalIdsWhateverKindTheFoldDerives() {
    List<LoggedAssertion> log =
        List.of(
            LocalEntity.minted(MINTED, NodeKind.WORK, "The Salt Almanac", WHEN),
            SameAs.declared(MINTED, CANONICAL, WHEN));

    assertThat(Equivalences.standIns(log, AS_CLAIMED).keySet())
        .as(
            "retractedStandIns reads this key set under UnaryOperator.identity() and says the"
                + " re-derivation cannot change it; this is that claim, made falsifiable")
        .isEqualTo(
            Equivalences.standIns(log, claim -> claim.withKind(NodeKind.PERSON)).keySet());
    assertThat(Equivalences.standIns(log, AS_CLAIMED).get(CANONICAL).kind())
        .as("and the values DO differ, so the comparison above is not comparing nothing")
        .isNotEqualTo(
            Equivalences.standIns(log, claim -> claim.withKind(NodeKind.PERSON))
                .get(CANONICAL)
                .kind());
  }
```

  `EquivalencesTest` has no `SOURCE` constant and builds a `Provenance` inline wherever it needs one; write `new Provenance("invented", "invented:1", WHEN, 1.0)` in the second test rather than adding a constant. `NodeAssertion.withKind` exists — the file's own re-derivation test already uses `claim -> claim.withKind(NodeKind.PERSON)`.

  These four are the domain-level twins of the fold-level behaviours already driven red above; the fifth is the falsifiable form of the claim `retractedStandIns`' javadoc makes about `UnaryOperator.identity()`. Each of the first three has already been seen red in Step 2, Step 4 or Step 5 respectively **if** you wrote it there; for any you did not, delete the clause it covers, run it, quote the red, restore. The fourth — the canonical-side retraction — must also be seen red: temporarily change `retractions.reaches(i, merge.localQid())` to `!retractions.survives(i, log.get(i))`, which is the rule as it would read without `Retractions.reaches` at all, run it, quote the red, restore.

- [ ] **Step 7 — positive control on the whole rule.** Make `retractedStandIns` `return Set.of();` unconditionally. Run `./gradlew test --tests 'com.robsartin.segue.export.RetractedStandInTakesItsEdgesTest' --tests 'com.robsartin.segue.domain.EquivalencesTest'` and confirm the three Task 1 tests and the first `EquivalencesTest` test red with the failures Task 1 quoted. Quote them; restore; green.

- [ ] **Step 8 — commit.** `spotlessApply`, full gate blocking. Confirm the gate reports **0 skipped** — a skip above zero would mean a guard shipped switched off. `git status`, then `git add` the five paths by name, commit: `#224: a retraction takes the edges that named the stand-in it emptied`.

---

### Task 4: `BothFoldsAgreeTest` gains the retracted merge

**Files:** Modify `src/test/java/com/robsartin/segue/export/BothFoldsAgreeTest.java`.

This task adds no behaviour and cannot red on its own: the fix is already in. Its verification is the planted control in Step 3, which is the whole reason for the task — the two folds must be held to agreeing about this shape, and until now the fixture had no case where one of them threw.

- [ ] **Step 1 — widen the fixture.** In `ownedLog()`, append after `merged(STRAY, REROUTED)`:

```java
            minted(LAPSE, NodeKind.WORK, "a working title he took back"),
            merged(LAPSE, FORFEIT),
            owned(WREN, FORFEIT, "INFLUENCED_BY"),
            retract(LAPSE));
```

  Add `LAPSE` and `FORFEIT` to `OWNED_QIDS`, add the static imports for them and for `retract`, and add a javadoc paragraph to `ownedLog()`:

```java
   * <p><b>{@code LAPSE} is the retracted merge (#224), and it is the case where one fold does not
   * disagree but <em>throws</em>.</b> It is minted, merged onto {@code FORFEIT}, given an owner
   * edge naming {@code FORFEIT} directly — which {@code OwnRun} offers the moment the stand-in
   * exists — and then retracted. The merge stops projecting and takes the only node {@code FORFEIT}
   * ever had with it, so before the fix {@code GraphProjector.project} died inside this fixture at
   * the edge's own row and the comparison below never ran at all. Both folds must now hold no node
   * under {@code FORFEIT} and no edge naming it. {@code RetractedStandInTakesItsEdgesTest} pins the
   * shape against each fold on its own; this fixture is what asks whether the two still agree.
```

- [ ] **Step 2 — assert it in both tests.** In `shouldHoldTheSameNodesWhenTheOwnerHasMintedAndMerged`, beside the `MISHEARD` absence:

```java
    // #224: the retraction took the merge, and the merge was the only thing holding a node under
    // FORFEIT. The local id goes too - its own claim is what the retraction names.
    assertThat(folded.nodes()).doesNotContainKey(FORFEIT);
    assertThat(folded.nodes()).doesNotContainKey(LAPSE);
```

  and in `shouldHoldTheSameEdgesWhenTheOwnerHasMintedAndMerged`, beside the self-loop absence:

```java
    assertThat(folded)
        .as(
            "the owner claimed this against a stand-in a retraction has since taken away, so it"
                + " goes with it rather than replaying into nothing (#224)")
        .doesNotContain(WREN + " INFLUENCED_BY " + FORFEIT);
```

  Run `./gradlew test --tests 'com.robsartin.segue.export.BothFoldsAgreeTest'` — green.

- [ ] **Step 3 — positive control, both directions, and it is the point of the task.** One at a time, quoting each failure: (a) change `LogProjection.of` back to `Equivalences.in(logged)` — expect the edge test red, because the exporter keeps an edge the replayed graph has dropped; (b) restore it and change `GraphProjector.project` back to `Equivalences.in(assertions)` — expect both tests red with `replay failed at sequence …`, which is the state Task 1 measured. Restore; green.

- [ ] **Step 4 — commit.** `spotlessApply`, full gate blocking, `git status`, `git add src/test/java/com/robsartin/segue/export/BothFoldsAgreeTest.java`, commit: `#224: both folds are held to agreeing about a retracted merge`.

---

### Task 5: the four-homes guard gains the retracted-merge row

**Files:** Modify `src/test/java/com/robsartin/segue/export/StandInAgreesInEveryHomeTest.java`.

The stand-in rule's four homes do not change their answer under this fix — the fold, `OwnRun` and `ratings/Labels` all held nothing for the emptied id before it and hold nothing after it, measured in the spec. The row goes in the pinned table anyway, because the **live** home splits from the other three on it, and a guard that records only the splits it already knows about stops recording new ones.

- [ ] **Step 1 — two ids and the fixture rows.** Add beside the other constants:

```java
  private static final String LAPSED = "Q0017";
  private static final String STRANDED = "Q10000900207";
```

  and append to `fourHomesLog()`, after `merged(SPARE, TAPE)`:

```java
            minted(LAPSED, NodeKind.WORK, "the working title he took back"),
            merged(LAPSED, STRANDED),
            owned(KNOWN, STRANDED, "INFLUENCED_BY"),
            retract(LAPSED));
```

  Add the static imports for `owned` and `retract`, and the import for `com.robsartin.segue.domain.Retraction`. Correct the `fourHomesLog` javadoc: it says *"No edges and no retractions - see the spec"*; replace with *"One edge and one retraction, both of them issue #224's row and nothing else's — see that row's comment in `PINNED`."*

- [ ] **Step 2 — the pinned row.** Append to `PINNED`:

```java
          // The retracted-merge row (#224), and the second one the homes split by PRESENCE. The
          // retraction dropped the merge, so the three homes that read the whole log hold nothing
          // under this id; the live one never sees the retraction at all - IngestService.record
          // refuses one outright and IngestService.retract has no graph half - so it keeps the
          // stand-in until the next boot re-folds the log. That is ADR 24's stated lag, the same
          // shape as the twice-merged row above.
          new Pinned(
              STRANDED,
              LAPSED,
              null,
              null,
              null,
              NodeKind.WORK,
              null,
              "the working title he took back"));
```

- [ ] **Step 3 — the live home learns what a live writer does with a retraction.** In `liveGraphNodes()`, replace `logged.forEach(ingest::record);` with:

```java
      for (LoggedAssertion assertion : logged) {
        if (assertion instanceof Retraction retraction) {
          // record() refuses a retraction outright - it is log-then-graph and a retraction has no
          // graph half (ADR 44) - so the live home is shown what a live writer actually does with
          // one: IngestService.retract appends it, and the running graph is stale until the next
          // boot rebuilds it (ADR 24). Skipping it silently would make this home's answer look
          // like a choice rather than the lag it is.
          IngestService.retract(liveLog, retraction);
          continue;
        }
        ingest.record(assertion);
      }
```

  with `liveLog` hoisted out of the `IngestService` construction: `FakeAssertionLog liveLog = new FakeAssertionLog();` then `new IngestService(liveLog, graph, IdentityMerge.NONE)`.

- [ ] **Step 4 — the split assertion, and the javadoc that explains it.** Change the presence-split assertion in `shouldAgreeOnEveryCanonicalLabelWhenTheThreeLogReadingHomesReadOneLog` from `.containsExactly(FIRST)` to `.containsExactly(FIRST, STRANDED)` and rewrite its `as(...)` to name **both** causes:

```java
        .as(
            "the live home is split from the other three by PRESENCE on exactly two rows, and each"
                + " has its own cause: the twice-merged row, where it is handed Equivalences.NONE"
                + " and still builds a stand-in the correction retired (#221), and the"
                + " retracted-merge row, where it never sees the retraction at all because a"
                + " retraction has no graph half (#224). A table splitting them anywhere else"
                + " would be recording a drift as though it were one of these; one splitting them"
                + " nowhere would have stopped recording them")
```

  Add a paragraph to the class javadoc, after the twice-merged one, saying the same in prose and naming issue #224.

- [ ] **Step 5 — run and control.** `./gradlew test --tests 'com.robsartin.segue.export.StandInAgreesInEveryHomeTest'` — green. Then the positive control, twice, quoting each: (a) change the new row's `shownInTheLiveGraphLabel` to `null` — expect the label-departure assertion red naming `IngestService.standIn (live record)` and `Q10000900207`, and the split assertion red; (b) restore, then change `shownInTheLiveGraph` to `null` — expect the kind test's departure assertion red. Restore; green.

- [ ] **Step 6 — commit.** `spotlessApply`, full gate blocking, `git status`, `git add src/test/java/com/robsartin/segue/export/StandInAgreesInEveryHomeTest.java`, commit: `#224: the four-homes guard pins the retracted-merge row`.

---

### Task 6: the retraction tool says what else stops projecting

**Files:** Modify `src/main/java/com/robsartin/segue/retract/RetractRun.java`, `src/test/java/com/robsartin/segue/retract/RetractRunTest.java`.

The report before the append is the safety feature the guide leans on, and after Task 3 a retraction can drop an edge it does not mention. Silence is half of what this issue rejected the tolerate-the-dangle option for; it is not allowed to arrive here instead.

- [ ] **Step 1 — RED.** Add to `RetractRunTest`, with two ids beside the existing constants (`private static final String WORKING_TITLE = "Q00900201";` and `private static final String CAUGHT_UP = "Q10000900301";`):

```java
  @Test
  @DisplayName("retracting a merged local id reports the edges that go with its stand-in")
  void shouldReportTheStrandedEdgesWhenTheRetractedIdWasMerged() {
    log.append(new NodeAssertion(OTHER, NodeKind.PERSON, "Ines Marlow", SOURCE));
    log.append(LocalEntity.minted(WORKING_TITLE, NodeKind.WORK, "a working title", NOW));
    log.append(SameAs.declared(WORKING_TITLE, CAUGHT_UP, NOW));
    log.append(OwnerEdge.claimed(OTHER, CAUGHT_UP, "INFLUENCED_BY", NOW));

    run.run(options(WORKING_TITLE, "the mint was a mistake", true), notes::add);

    assertThat(notes)
        .as(
            "the merge goes with the local id, and it was the only thing holding a node under the"
                + " canonical id - so the edge claimed against that id stops projecting too, and"
                + " the operator has to be told before the row is written (#224)")
        .anySatisfy(note -> assertThat(note).contains(CAUGHT_UP).contains("1 edge claim"));
  }
```

  Add the imports it needs — `com.robsartin.segue.domain.LocalEntity`, `OwnerEdge`, `SameAs` — and build all three through their factories, never their constructors (`ArchitectureTest.ownerClaimsAreMadeThroughTheirFactories`). Run `./gradlew test --tests 'com.robsartin.segue.retract.RetractRunTest'` and confirm the red is the assertion — no note mentions the canonical id. Quote it.

- [ ] **Step 2 — GREEN.** In `RetractRun`, add the import `com.robsartin.segue.domain.Equivalences;` and, in `run`, after the `the log keeps every one of them` note and **before** the dry-run branch:

```java
    for (String stranded : strandedByThisRetraction(options)) {
      notes.accept(stranded);
    }
```

  and the method, below `measure`:

```java
  /**
   * What ELSE stops projecting: the canonical ids this retraction empties, and the edge claims that
   * go with them (#224).
   *
   * <p>Retracting a local id the owner had merged drops the merge — {@link Retractions#survives}
   * drops a {@link com.robsartin.segue.domain.SameAs} when either of its ids is retracted — and
   * with it the only node the canonical id may ever have had. Both folds then drop the edges that
   * named it, so the report has to name them: {@link Effect}'s two counts are claims naming the qid
   * being retracted, and these name a different id. They are reported rather than added to those
   * counts for that reason, and because the counts decide whether "nothing to retract" is refused.
   *
   * <p><b>Asked of the log this retraction would produce</b>, not of the log as it stands: the rule
   * is about what a retraction reaches, and there is no retraction in the log yet. Nothing is
   * appended — the row is built in memory, and {@link #run} may still be a dry run.
   */
  private List<String> strandedByThisRetraction(Options options) {
    List<LoggedAssertion> after = new ArrayList<>(log.readAll());
    after.add(new Retraction(options.qid(), options.reason(), clock.instant()));
    Set<String> emptied = Equivalences.retractedStandIns(after);
    if (emptied.isEmpty()) {
      return List.of();
    }
    Retractions retractions = Retractions.in(after);
    List<String> notes = new ArrayList<>();
    for (String canonical : emptied) {
      int edges = 0;
      for (int i = 0; i < after.size(); i++) {
        LoggedAssertion assertion = after.get(i);
        if (!retractions.survives(i, assertion)) {
          continue;
        }
        if (assertion instanceof AssertionRecord edge
            && (edge.fromQid().equals(canonical) || edge.toQid().equals(canonical))) {
          edges++;
        }
        if (assertion instanceof OwnerEdge owned
            && (owned.fromQid().equals(canonical) || owned.toQid().equals(canonical))) {
          edges++;
        }
      }
      notes.add(
          "the merge onto "
              + canonical
              + " goes too, and nothing else holds a node for that id, so "
              + edges
              + " edge claim(s) naming it stop projecting with it (#224)");
    }
    return notes;
  }
```

  Add the imports it needs (`java.util.ArrayList`, `java.util.Set`). Re-run: green.

- [ ] **Step 3 — positive control.** Make `strandedByThisRetraction` `return List.of();`, re-run, confirm the new test reds with the Step 1 message. Quote it; restore; green. Then confirm the existing `RetractRunTest` cases are untouched — a retraction of an id with no merge must add no note at all, which the suite's existing note assertions already say.

- [ ] **Step 4 — commit.** `spotlessApply`, full gate blocking, `git status`, `git add src/main/java/com/robsartin/segue/retract/RetractRun.java src/test/java/com/robsartin/segue/retract/RetractRunTest.java`, commit: `#224: the retraction report names the edges its stand-in takes with it`.

---

### Task 7: the developer guide and ADR 44's amendment

**Files:** Modify `docs/developer-guide.md`, `docs/adr/0044-retraction-as-a-new-claim.md`.

No unit test covers a documentation change. The verification method is the build gate, which runs `arch/DeveloperGuideEnumerationsTest`, `arch/DocumentationLinksTest`, `arch/AdrIndexTest` and both guide-example tests — say so out loud in the report rather than letting test-after arrive by implication. Add no new `./gradlew` example line: the chapter's existing examples are what those tests parse.

- [ ] **Step 1 — the guide.** In *"Undoing one, and why it matters which id you retract"*, replace the first bullet. From:

```
- **Retract the local id** and its node claim, its owner edges and the merge all stop projecting.
  What a source claimed about the canonical id is untouched.
```

  to:

```
- **Retract the local id** and its node claim, its owner edges and the merge all stop projecting —
  and so do the edges that named the canonical id **the merge was standing in for**
  ([#224](https://github.com/robsartin/segue/issues/224)). `merge` gives that id a node and
  `ownClaim assert` will then offer it as an endpoint, so an edge claimed against it is a claim
  about the entity you are now taking back, written under the name your own merge gave it; dropping
  the merge without it left the boot replay refusing an endpoint nothing had ever claimed. It
  reaches no further than that: a canonical id a **source** has claimed as a node of its own, or one
  a second merge still stands in for, keeps its node and every edge naming it. What a source claimed
  about the canonical id is untouched. `retractEntity` names the ids it empties and counts those
  edges before it appends anything.
```

- [ ] **Step 2 — the ADR amendment**, appended as its own dated section at the very end of `docs/adr/0044-retraction-as-a-new-claim.md`. Nothing above it is edited, including the two paragraphs it qualifies and the stale consequence bullet it records:

```markdown
**Amendment (2026-09-03, issue #224): question 1's granularity has one more clause. A retraction of
a local id the owner had merged also reaches the edges that name the stand-in that merge created —
and nothing else.**

Nothing above is withdrawn and no sentence above is edited. *"The unit is the entity"* stands, and
so does *"it does not cascade"*: what this adds is not a cascade to a neighbour but the same
entity's own node under the other name the owner himself gave it.

**What was there, measured on `0783492`** — the commit that landed
[ADR 59](0059-owner-claims-as-a-third-layer.md)'s 2026-09-03 amendment, so the surviving-edge
widening was already in place and does not reach this case. An invented log (ADR 40,
[ADR 51](0051-what-an-adr-may-quote.md): no known list behind it) holding `node(WREN)`, one minted
entity, a merge of it onto a canonical id no source has claimed, an owner edge naming that canonical
id directly, and a retraction of the local id. `Equivalences.standIns` named nothing — the local no
longer survives, so `localsOfMerges` filtered the merge out before `stands` was asked anything —
`LogProjection` reported `danglingEdges() == 1` and carried on, and `GraphProjector` threw `replay
failed at sequence 5`, `assertion references unknown entity … - upsert the node first`. The export
looked correct and the application refused to start at the next restart, on rows
[ADR 19](0019-assertion-log-source-of-truth.md) forbids deleting. Every row in that log is one the
supported flow produces: `OwnRun` offers a merge's canonical id as a claimable endpoint the moment
its stand-in exists, and `retractEntity` is the tool this ADR builds.

**Ruling.** A canonical id is a *retracted stand-in* when a merge named it, a retraction of that
merge's **local** side dropped the merge, and nothing else in the projection holds a node for the id
— no surviving node claim, and no surviving merge whose stand-in it still is. An edge claim naming
one at either end does not reach the projection. The rule is `Equivalences.retractedStandIns`, in
`domain`, computed once and carried by the `Equivalences` both folds build through
`Equivalences.folding`; `Equivalences.foldEndpoints` — which both folds already call for every edge,
and which already yields nothing for an edge a merge would collapse onto itself — yields nothing for
this one too. Neither fold's loop changed. Those classes are the authority for the mechanics; this
amendment mirrors no table of theirs.

**The two exclusions are the ruling, not caveats on it.** Without them, retracting one thing the
owner minted would strip the edges off a real Wikidata entity's whole expansion — which contradicts
this ADR's own reach and the developer guide's promise that *"what a source claimed about the
canonical id is untouched"*. Both were measured green before the change and are pinned by
`RetractedStandInTakesItsEdgesTest` and `EquivalencesTest`.

**Only the local side counts.** A merge dropped because its *canonical* side was retracted leaves
nothing to repair: that id is retracted outright and `Retractions.survives` has already dropped
every edge naming it. Telling the two apart is why `Retractions.reaches` is public.

**Rejected, with the reason each lost.**

- **Let the stand-in survive the retraction while a surviving edge names it**, as ADR 59's
  2026-09-03 amendment does for a *corrected* merge. The symmetry is the first thing to reach for.
  **Lost on what the node would be made of.** There, the local node still stands and the stand-in
  copies a claim that is still true; here the local claim is retracted, so building the node means
  reading a retracted `LocalEntity` for its kind and its label and putting the owner's withdrawn
  working title on a live node in an export somebody keeps — a node assembled entirely out of
  retracted rows. That is this ADR inverted: the projection would go on saying the thing the
  retraction exists to stop it saying. A label-less or annotated stand-in is the *"name the orphan
  in the export"* alternative ADR 59 already rejected.
- **Have `GraphProjector` tolerate the unknown endpoint as `LogProjection` does**, so the two folds
  agree on a dangling edge. Rejected once already in ADR 59's 2026-09-03 amendment, and the question
  here was whether anything is different. One thing is: there the missing endpoint was a defect with
  a fix, and here the absence is correct — the owner really did retract the entity. It does not save
  the option. Tolerance buys this one case by removing the loud failure from **every** case: a
  corrupt log, a future fold's bug, a source adapter emitting an edge before its node all stop
  failing at boot and start being counted in a field whose javadoc says it should always be zero.
  `LogProjection.danglingEdges` exists to report that failure, not to produce it. Dropping the edge
  for a stated reason keeps the boot loud for everything else.
- **Refuse the retraction at the tool** when the local id has been merged and a surviving edge names
  its canonical id. **Lost twice.** The fold must cope with the row regardless — the log is
  append-only and a refusal cannot reach a row already written — so it would be a guard in front of
  a fold that still could not replay; and it takes away the owner's only way back out of a wrong
  mint, which ADR 59's amendment already declined to do from the other side.
- **Re-point the edge onto the local id.** Rejected for ADR 59's reason, unchanged: segue does not
  rewrite a claim on the owner's behalf. He named the canonical id.

**Consequence for `retractEntity`.** The report before the append names each id the retraction
empties and counts the edge claims that stop projecting with it. `Effect`'s two counts keep their
meaning — claims naming the qid being retracted — because they are what decides whether *"nothing to
retract"* is refused. Silence was half of what the tolerate-the-dangle option was rejected for, and
it is not allowed to arrive at the tool instead.

**A consequence above is stale, and it is recorded rather than repaired.** *"The ratings tool is
deliberately not part of this. `Labels.forQids` reads node claims straight out of the log without
applying the rule"* has been false since issue #92: that method asks `Retractions.survives` before a
claim can name or rename anything, and cites this ADR as the precedent for doing so. Nothing in this
issue depends on which of the two is right, and an ADR is not edited to match what the code became.
Whether the ratings listing *should* honour retractions is a decision nobody has argued in writing.
```

- [ ] **Step 3 — commit.** Full gate blocking (it is the verification), `git status`, `git add docs/developer-guide.md docs/adr/0044-retraction-as-a-new-claim.md`, commit: `#224: the guide and ADR 44's amendment record what a retraction reaches through a merge`.

---

### Task 8: the final gate and the report

- [ ] **Step 1 — rebase.** `git fetch origin`, then `git rebase origin/main`. A textual conflict is possible in `Equivalences` (any branch touching `standIns` or the record header) and in `BothFoldsAgreeTest`'s `ownedLog()`. Keep both sides: this branch's rows go at the end of that fixture, and this branch's third record component sits after `referencedEndpoints`.

- [ ] **Step 2 — the gate, blocking, from a clean tree.** `SEGUE_REQUIRE_BROWSER=true SEGUE_REQUIRE_GRAPHVIZ=true ./gradlew check --rerun-tasks`. Confirm **BUILD SUCCESSFUL** and **0 skipped**.

- [ ] **Step 3 — report.** State: the quoted red for every behaviour (Task 1's three, Task 2's, Task 3's five, Task 6's), the quoted output of every positive control, that Task 7's verification method was the build gate rather than a unit test, whether Step 4 and Step 5 of Task 3 were seen red by deletion or arrived green (and which), and the final gate line.
