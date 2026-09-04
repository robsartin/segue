# Two logs that cannot boot after a retraction or a re-merge — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The withdrawal rule reads the endpoints the fold resolves; a withdrawn edge keeps no stand-in alive; an owner claim that would leave the log unbootable is refused **before** the append; and a log that already carries one is refused at boot by a message naming the row, the id and the repair.

**Architecture:** One new shared question in `domain` — `Equivalences.nodesTheFoldHolds(log)`, promoted from a private local — read by the producer guard, the boot refusal and `retractedStandIns`. One two-line change to `Equivalences.namesARetractedStandIn`. One least-fixed-point loop inside `Equivalences` replacing three straight-line computations. One guard method in `IngestService.claim`, one pre-flight in `GraphProjector.project`. No new type, no new field, no port change, no new ADR.

**Tech Stack:** Java 25, Gradle 9.7.1 (plain `./gradlew`), JUnit 5, AssertJ, TinkerGraph, SQLite.

**Spec:** `docs/superpowers/specs/2026-09-03-bypass-boot-breaks-design.md`

## Global Constraints

- **Pure TDD / red first**: every behaviour is seen red for the right reason — a real assertion failure, never a compile error — before the code that makes it green. Where a step introduces a method that does not exist yet, the stub goes in first (returning the wrong answer, never throwing), so the red is an assertion. **Quote the actual failure text in the report.** Test names `should<Expected>When<Condition>` with `@DisplayName`.
- **Every guard gets a positive control**: plant the defect, watch the check fire, quote it, remove the plant. Written out as steps below.
- **Mikado**: the gate is green before every commit. **Stage by explicit path, git stderr visible — never `git add -A`, never `2>/dev/null` on `git add`.** Read `git status` before every commit. Commits end `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`. Never cite a `.superpowers/` path from a committed file.
- Gate, **blocking, never backgrounded**: `SEGUE_REQUIRE_BROWSER=true SEGUE_REQUIRE_GRAPHVIZ=true ./gradlew check --rerun-tasks`. Fast loops named per task. Run `./gradlew spotlessApply` before each gate.
- **Only JDK 25 is installed and Gradle 9.7.1 launches on it.** Plain `./gradlew`; never `/usr/libexec/java_home -v 21` (it silently returns 25).
- **Never run a writing dev task** (`own`, `ownClaim`, `retractEntity`, `resolveNames`, `rate`). `~/.segue/segue.db` is never read, written, copied or created.
- Every id invented in `src/test` must take an unallocatable shape or `arch/StandInQidsDenoteNothingTest` reds: two leading zeros for a local entity (ADR 59), eleven digits with no leading zero for a merge's canonical side (ADR 62), one leading zero for anything else (ADR 58). The allowlist there is keyed `(id, file, context)` — no id added by this plan may need an entry, because none of them is allocatable.
- **YAGNI**: no parameter, helper or accessor beyond what a step below actually uses.
- Machine is loaded: **no wall-clock assertions anywhere**, and no timing measurement is part of this plan.

---

### Task 1: Withdrawal reads the endpoints the fold resolves

**Files:** Create `src/test/java/com/robsartin/segue/export/MergeAfterARetractionTest.java`. Modify `src/main/java/com/robsartin/segue/domain/Equivalences.java`, `src/test/java/com/robsartin/segue/export/BothFoldsAgreeTest.java`.

Closes break 2. `Equivalences.namesARetractedStandIn` reads `claim.fromQid()` and `claim.toQid()` as the claim wrote them, so an edge that reaches an emptied canonical id through a merge is not withdrawn.

- [ ] **Step 1 — the reproducing test, in full.** Create `src/test/java/com/robsartin/segue/export/MergeAfterARetractionTest.java`:

```java
package com.robsartin.segue.export;

import static com.robsartin.segue.export.InventedGraph.FORFEIT;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Issue #228: a merge appended <em>after</em> its local side was retracted, and what the two folds
 * make of an edge that then names either end of it.
 *
 * <p><b>Two shapes, and only one of them has a fold rule.</b> Where the second merge names the same
 * canonical id the retraction emptied, an edge naming the local id folds onto that emptied id and
 * is withdrawn — the rule ADR 44's 2026-09-03 amendment already states, applied to the case its
 * implementation missed, because {@code Equivalences.namesARetractedStandIn} read the claim's raw
 * endpoints before the fold resolved them. Where the second merge names a <em>different</em>
 * canonical id, nothing retracted that id and no fold rule can honestly reach it: the merge has no
 * local side, so no stand-in is built, and an edge naming it is a live claim about an entity
 * nothing describes. That log is refused before the append and, if a log already holds one, named
 * at boot — see the tests at the bottom of this file and ADR 59's 2026-09-04 amendment.
 *
 * <p>Every entity here is invented (ADR 40, issue #37).
 */
class MergeAfterARetractionTest {

  /**
   * Minted, merged, retracted, then merged again onto the <b>same</b> canonical id, with an owner
   * edge naming the retracted local id afterwards. The edge survives on its own terms — neither
   * {@code WREN} nor {@code LAPSE} is retracted at its position — and folds onto {@code FORFEIT},
   * which {@code Equivalences.retractedStandIns} has already emptied.
   *
   * <p>Measured on {@code a7c3455}: {@code retractedStandIns} names {@code Q10000900112}, {@code
   * standIns} is empty, the exporter's fold reports {@code danglingEdges 1} and {@code
   * withdrawnEdges 0}, and {@code GraphProjector.project} throws {@code replay failed at sequence
   * 6}, {@code assertion references unknown entity Q10000900112 - upsert the node first}.
   */
  private static FakeAssertionLog remergedOntoTheEmptiedIdLog() {
    return new FakeAssertionLog()
        .with(
            node(WREN, NodeKind.PERSON, "Wren Alderman"),
            minted(LAPSE, NodeKind.WORK, "a working title he took back"),
            merged(LAPSE, FORFEIT),
            retract(LAPSE),
            merged(LAPSE, FORFEIT),
            owned(WREN, LAPSE, "INFLUENCED_BY"));
  }

  @Test
  @DisplayName("the exporter withdraws an edge that folds onto a canonical id a retraction emptied")
  void shouldWithdrawAnEdgeThatFoldsOntoAnEmptiedCanonicalIdRatherThanDangle() {
    LogProjection folded = LogProjection.of(remergedOntoTheEmptiedIdLog());

    assertThat(folded.nodes())
        .as("the retraction took the only node the canonical id ever had")
        .doesNotContainKey(FORFEIT);
    assertThat(folded.edges())
        .as("and the edge that reaches it through the merge goes with it")
        .isEmpty();
    assertThat(folded.withdrawnEdges())
        .as("counted as a withdrawal, which is what the export says out loud (#224)")
        .isEqualTo(1);
    assertThat(folded.danglingEdges())
        .as(
            "and NOT as dangling - that count is the alarm for a log that cannot boot, and it read"
                + " 1 before this fix")
        .isZero();
  }

  @Test
  @DisplayName("the boot replay survives an edge that folds onto a canonical id a retraction emptied")
  void shouldReplayWithoutThrowingWhenAnEdgeFoldsOntoAnEmptiedCanonicalId() {
    try (TinkerGraphStore replayed = new TinkerGraphStore()) {
      GraphProjector.project(remergedOntoTheEmptiedIdLog(), replayed, IdentityMerge.NONE);

      assertThat(replayed.node(FORFEIT)).isEmpty();
      assertThat(replayed.edgeCount())
          .as("the edge has no endpoint to be applied against, so the graph holds none")
          .isZero();
      assertThat(replayed.node(WREN))
          .as("and the rest of the log is untouched, so this is not an empty graph agreeing")
          .isPresent();
    }
  }

  /** The same log with nothing retracted: the merge stands and the edge lands on its canonical id. */
  private static FakeAssertionLog mergedAndNotRetractedLog() {
    return new FakeAssertionLog()
        .with(
            node(WREN, NodeKind.PERSON, "Wren Alderman"),
            minted(LAPSE, NodeKind.WORK, "a working title he took back"),
            merged(LAPSE, FORFEIT),
            owned(WREN, LAPSE, "INFLUENCED_BY"));
  }

  @Test
  @DisplayName("an edge naming a merged local id still folds onto the canonical id when nothing is retracted")
  void shouldFoldTheEdgeOntoTheCanonicalIdWhenNothingIsRetracted() {
    LogProjection folded = LogProjection.of(mergedAndNotRetractedLog());

    assertThat(folded.nodes())
        .as("without this the absences above would hold over a fixture that never had them")
        .containsKey(FORFEIT);
    assertThat(folded.edges().stream().map(MergeAfterARetractionTest::key))
        .containsExactly(WREN + " INFLUENCED_BY " + FORFEIT);
    assertThat(folded.withdrawnEdges())
        .as("so the count above reports the withdrawal and is not a non-zero constant")
        .isZero();
  }

  private static String key(EdgeRecord edge) {
    return edge.fromQid() + " " + edge.typeCode() + " " + edge.toQid();
  }
}
```

- [ ] **Step 2 — RED, and quote it.** Run, blocking: `./gradlew test --tests 'com.robsartin.segue.export.MergeAfterARetractionTest'`. Expect `shouldFoldTheEdgeOntoTheCanonicalIdWhenNothingIsRetracted` **green**, and the other two red — one on `expected: 1 but was: 0` for `withdrawnEdges` (with `danglingEdges` at 1), the other on `java.lang.IllegalStateException: replay failed at sequence 6` caused by `assertion references unknown entity Q10000900112 - upsert the node first`. **Quote both failures verbatim in the report.** If the sequence number is not 6, quote what it actually is and correct the fixture's javadoc to match.

- [ ] **Step 3 — GREEN.** In `Equivalences`, replace the body of `namesARetractedStandIn`:

```java
  public boolean namesARetractedStandIn(AssertionRecord claim) {
    Objects.requireNonNull(claim, "claim");
    return retractedStandIns.contains(canonical(claim.fromQid()))
        || retractedStandIns.contains(canonical(claim.toQid()));
  }
```

  and add this paragraph to that method's javadoc, after the "One home for the question" one:

```java
   * <p><b>It asks about the endpoints the fold resolves, not the ones the claim wrote</b> (#228).
   * An edge naming a merged local id whose merge points at an emptied canonical id is claimed
   * against the same absent endpoint as one that names that id directly - the endpoint the fold
   * would give it is the entity the retraction took away - so the raw read let the rule miss its
   * own case: {@code [minted(L), merged(L to A), retract(L), merged(L to A), owned(WREN to L)]}
   * threw {@code replay failed at sequence 6} on {@code a7c3455} while {@code retractedStandIns}
   * already named {@code A}. Reading through {@link #canonical} costs nothing where no merge is
   * involved, because that map answers with the id it was given.
```

- [ ] **Step 4 — verify GREEN, fast loop.** `./gradlew test --tests 'com.robsartin.segue.export.MergeAfterARetractionTest' --tests 'com.robsartin.segue.export.RetractedStandInTakesItsEdgesTest' --tests 'com.robsartin.segue.domain.EquivalencesTest' --tests 'com.robsartin.segue.retract.RetractRunTest'`. All green. `RetractRun.strandedByThisRetraction` asks the same predicate, so its report now names an edge that reaches an emptied id through a merge — check nothing in `RetractRunTest` pinned the old answer, and if something did, quote it and say why the new number is the right one.

- [ ] **Step 5 — positive control.** Revert `namesARetractedStandIn` to `retractedStandIns.contains(claim.fromQid()) || retractedStandIns.contains(claim.toQid())`. Re-run the fast loop; expect the two tests from Step 2 red again with the same two failures. **Quote them.** Restore the fix; green.

- [ ] **Step 6 — the pair, in `BothFoldsAgreeTest`.** Both folds change, so the fixture that holds them together must carry the shape. In `ownedLog()`, append two rows after `retract(LAPSE)`:

```java
            retract(LAPSE),
            // #228: the merge re-declared onto the id the retraction already emptied, and an owner
            // edge naming the retracted LOCAL id after it. The edge reaches FORFEIT through
            // canonicalByLocal rather than by name, which is the case Equivalences
            // .namesARetractedStandIn missed while it read a claim's raw endpoints.
            merged(LAPSE, FORFEIT),
            owned(WREN, LAPSE, "INFLUENCED_BY"));
```

  and in `shouldHoldTheSameEdgesWhenTheOwnerHasMintedAndMerged`, change the `applied` expectation from `29` to `30`, extending its `as(...)` reason with:

```java
                  + " The re-merge #228 added applies nothing to the graph either - its local side"
                  + " does not survive the retraction, so standIn() finds no node to copy - but a"
                  + " SameAs counts as applied whether or not it builds one, so the count moves by"
                  + " one row and not by two: the owner edge beside it is withdrawn."
```

- [ ] **Step 7 — verify the pair.** `./gradlew test --tests 'com.robsartin.segue.export.BothFoldsAgreeTest'`. Green. If `applied` is not 30, **do not** adjust the number to whatever came out: quote it, work out which row applied, and say so in the report before changing anything.

- [ ] **Step 8 — gate and commit.** `./gradlew spotlessApply`, then blocking `SEGUE_REQUIRE_BROWSER=true SEGUE_REQUIRE_GRAPHVIZ=true ./gradlew check --rerun-tasks`. Read `git status`. Stage by explicit path: `git add src/main/java/com/robsartin/segue/domain/Equivalences.java src/test/java/com/robsartin/segue/export/MergeAfterARetractionTest.java src/test/java/com/robsartin/segue/export/BothFoldsAgreeTest.java`. Commit: `A withdrawal reads the endpoints the fold resolves (#228)`.

---

### Task 2: One shared answer to "which ids does the fold hold a node for"

**Files:** Modify `src/main/java/com/robsartin/segue/domain/Equivalences.java`, `src/test/java/com/robsartin/segue/export/BothFoldsAgreeTest.java`.

The `held` set inside `retractedStandIns` is already this question. Three later tasks need it, so it is promoted rather than copied. No behaviour changes in this task.

- [ ] **Step 1 — the stub, so that the red is an assertion and not a compile error.** Add to `Equivalences`, above `retractedStandIns`:

```java
  /**
   * Every id the fold will hold a node for: the stand-ins it builds, plus every id a surviving node
   * claim or minted entity names (#228).
   *
   * <p><b>Promoted from a local, because three readers now ask it.</b> {@link #retractedStandIns}
   * asks it to decide whether a merge's canonical id is emptied; {@code IngestService.claim} asks
   * it to refuse an owner claim naming an endpoint the fold would hold no node for, before the
   * append rather than at the next boot; and {@code GraphProjector.project} asks it to name the
   * rows of a log that already carries one. A second copy of this walk is how the gate and the fold
   * would come to disagree about which entities exist, which is the one disagreement that stops the
   * application starting.
   *
   * <p><b>It is exactly {@code LogProjection.of(log).nodes().keySet()}</b>, computed without
   * folding a single edge, and exactly the node set a {@code GraphProjector} replay leaves. That is
   * asserted rather than claimed - {@code BothFoldsAgreeTest
   * .shouldNameExactlyTheNodesTheFoldHoldsWhenAskedOfOneLog} compares all three over the fixture
   * that holds every shape the third layer has.
   *
   * <p><b>No re-derivation parameter</b>, for {@link #retractedStandIns}' reason exactly: this
   * reads which ids have a node, never what kind it is, and {@link #standIns}' key set cannot
   * depend on the re-derivation.
   */
  public static Set<String> nodesTheFoldHolds(List<LoggedAssertion> log) {
    Objects.requireNonNull(log, "log");
    return Set.of();
  }
```

- [ ] **Step 2 — the test.** In `BothFoldsAgreeTest`, add the import `com.robsartin.segue.domain.Equivalences` and this test after `shouldHoldTheSameEdgesWhenTheOwnerHasMintedAndMerged`:

```java
  @Test
  @DisplayName("the shared held-node question answers exactly the nodes both folds hold")
  void shouldNameExactlyTheNodesTheFoldHoldsWhenAskedOfOneLog() {
    FakeAssertionLog log = ownedLog();
    Set<String> held = Equivalences.nodesTheFoldHolds(log.readAll());

    assertThat(held)
        .as(
            "the producer guard and the boot refusal both read this rather than re-deriving which"
                + " entities exist, so it has to be the fold's own answer (#228)")
        .containsExactlyInAnyOrderElementsOf(LogProjection.of(log).nodes().keySet());

    try (TinkerGraphStore replayed = new TinkerGraphStore()) {
      GraphProjector.project(log, replayed, IdentityMerge.NONE);

      for (String qid : OWNED_QIDS) {
        assertThat(replayed.node(qid).isPresent())
            .as("and the boot replay agrees about %s", qid)
            .isEqualTo(held.contains(qid));
      }
    }
  }
```

- [ ] **Step 3 — RED, and it must be an assertion.** `./gradlew test --tests 'com.robsartin.segue.export.BothFoldsAgreeTest'`. Expect `shouldNameExactlyTheNodesTheFoldHoldsWhenAskedOfOneLog` red on `Expecting actual: [] to contain exactly in any order: [...]`. **Quote it.** If it fails to compile instead, the stub was skipped — go back to Step 1.

- [ ] **Step 4 — GREEN.** Replace the stub's body, and rewrite `retractedStandIns` to read the same walk:

```java
  public static Set<String> nodesTheFoldHolds(List<LoggedAssertion> log) {
    Objects.requireNonNull(log, "log");
    return Collections.unmodifiableSet(
        nodesHeld(log, standIns(log, UnaryOperator.identity()).keySet()));
  }

  /**
   * {@link #nodesTheFoldHolds}' walk, over a stand-in key set the caller has already decided.
   *
   * <p>Separate from the public method for one caller: {@link #retractedStandIns}' own computation
   * has to ask this question of a stand-in set it is still working out, and calling the public
   * method there would ask {@link #standIns} - which reads {@link #in} - in the middle of deciding
   * what {@link #in} answers.
   */
  private static Set<String> nodesHeld(List<LoggedAssertion> log, Set<String> standInIds) {
    Retractions retractions = Retractions.in(log);
    Set<String> held = new LinkedHashSet<>(standInIds);
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
    return held;
  }
```

  and in `retractedStandIns`, replace the `held` block — everything from `Set<String> held = new LinkedHashSet<>(standIns(...))` down to the closing brace of that `for` loop — with:

```java
    Set<String> held = nodesHeld(log, standIns(log, UnaryOperator.identity()).keySet());
```

  leaving the `emptied` loop below it exactly as it is.

- [ ] **Step 5 — verify GREEN.** `./gradlew test --tests 'com.robsartin.segue.export.*' --tests 'com.robsartin.segue.domain.EquivalencesTest'`. All green, and nothing else moved: this task changes no answer.

- [ ] **Step 6 — positive control on the extraction.** Delete the `case LocalEntity minted -> held.add(minted.qid());` arm from `nodesHeld`. Re-run; expect `RetractedStandInTakesItsEdgesTest` and the new test both red, which is what says the extracted walk is the one `retractedStandIns` actually uses. **Quote a failure.** Restore; green.

- [ ] **Step 7 — gate and commit.** `./gradlew spotlessApply`, then the full blocking gate. `git status`, stage `src/main/java/com/robsartin/segue/domain/Equivalences.java src/test/java/com/robsartin/segue/export/BothFoldsAgreeTest.java`. Commit: `One shared answer to which ids the fold holds a node for (#228)`.

---

### Task 3: A withdrawn edge keeps no superseded stand-in alive

**Files:** Modify `src/test/java/com/robsartin/segue/export/InventedGraph.java`, `src/test/java/com/robsartin/segue/export/TwiceMergedIdLeavesNoOrphanTest.java`, `src/main/java/com/robsartin/segue/domain/Equivalences.java`, `src/test/java/com/robsartin/segue/export/StandInAgreesInEveryHomeTest.java` (only if it moves — see Step 6).

Closes break 3, the only one of the three the supported flow can produce. `referencedEndpoints` is built from the surviving rows rather than the ones the fold keeps.

- [ ] **Step 1 — two invented ids.** In `InventedGraph`, after `LAPSE`:

```java
  /**
   * A ninth id the owner minted, and the one issue #228's third defect turns on: merged onto
   * {@link #SEVERED} and then retracted, so that the edge naming {@code SEVERED} is WITHDRAWN
   * rather than retracted - the distinction the surviving-edge widening could not see. Two leading
   * zeros, for {@link #ALMANAC}'s reason.
   */
  static final String SLIP = "Q009";
```

  and, after `FORFEIT`:

```java
  /**
   * An eighth canonical id: the one {@link #SLIP} was merged onto before the owner retracted it,
   * so an edge naming this id is withdrawn by the fold while every row of it still survives (#228).
   * ADR 62's eleven-digit shape, for the reason {@link #KETTLES} takes it.
   */
  static final String SEVERED = "Q10000900113";
```

- [ ] **Step 2 — the reproducing test.** In `TwiceMergedIdLeavesNoOrphanTest`, add the static imports for `SEVERED`, `SLIP` and `retract` if they are not already there, and add this fixture and test directly after `shouldKeepNoSupersededStandInAliveWhenTheOnlyNamingEdgeIsRetracted` — its retracted sibling:

```java
  /**
   * The surviving-edge fixture again, with the naming edge WITHDRAWN rather than retracted (#228).
   * {@code MISHEARD}'s stand-in is superseded by the correction onto {@code WATERMARK} and is kept
   * alive only by the {@code MISHEARD -> SEVERED} edge; that edge names a canonical id a retraction
   * emptied, so the fold withdraws it and holds it in neither projection. Every row of the edge
   * still <em>survives</em> — neither of its endpoints is retracted — which is exactly why {@code
   * referencedEndpoints}, built from the surviving rows, went on counting it.
   *
   * <p>Every row here is one the supported flow writes: a second merge is a correction {@code
   * OwnCli} says rather than refuses, {@code ownClaim assert} offers both canonical ids the moment
   * their stand-ins exist, and {@code retractEntity} retracts a local id.
   */
  private static FakeAssertionLog correctedLogWithAWithdrawnSurvivingEdge() {
    return new FakeAssertionLog()
        .with(
            minted(CORRECTED, NodeKind.WORK, "A Self-Pressed Record"),
            merged(CORRECTED, MISHEARD),
            minted(SLIP, NodeKind.WORK, "a working title he took back"),
            merged(SLIP, SEVERED),
            owned(MISHEARD, SEVERED, "INFLUENCED_BY"),
            merged(CORRECTED, WATERMARK),
            retract(SLIP));
  }

  @Test
  @DisplayName("a withdrawn edge keeps no superseded stand-in alive")
  void shouldKeepNoSupersededStandInAliveWhenTheOnlyNamingEdgeIsWithdrawn() {
    FakeAssertionLog log = correctedLogWithAWithdrawnSurvivingEdge();

    assertThat(Equivalences.standIns(log.readAll(), KindMapper::rederive))
        .as(
            "the MISHEARD -> SEVERED edge survives every retraction and the fold withdraws it all"
                + " the same, so it keeps nothing alive - it read [MISHEARD, WATERMARK] before"
                + " this fix (#228)")
        .containsOnlyKeys(WATERMARK);

    LogProjection folded = LogProjection.of(log);
    assertThat(folded.nodes())
        .as("so a full export draws no node with no edges under the id he corrected away from")
        .doesNotContainKey(MISHEARD);
    assertThat(folded.edges()).isEmpty();
    assertThat(folded.withdrawnEdges())
        .as("the edge is still counted as withdrawn, which is what says it was ever there")
        .isEqualTo(1);
    assertThat(folded.danglingEdges()).isZero();

    try (TinkerGraphStore replayed = new TinkerGraphStore()) {
      GraphProjector.project(log, replayed, IdentityMerge.NONE);

      assertThat(replayed.node(MISHEARD))
          .as("and the boot replay agrees, which is the half a fold-only fix would not move")
          .isEmpty();
      assertThat(replayed.node(WATERMARK))
          .as("the merge that stands today keeps its node, so this is not an empty graph agreeing")
          .isPresent();
    }
  }
```

- [ ] **Step 3 — RED, and quote it.** `./gradlew test --tests 'com.robsartin.segue.export.TwiceMergedIdLeavesNoOrphanTest'`. Expect the new test red on `Expecting ... to contain only following keys: [Q10000900107] but could not find the following keys: [] and the following keys were unexpected: [Q10000900109]` (or AssertJ's equivalent wording naming `MISHEARD`). **Quote it verbatim**, and quote the follow-on assertion the run does not reach.

- [ ] **Step 4 — GREEN, the least fixed point.** In `Equivalences`, replace `in` and `retractedStandIns` with the computation below, leaving `standIns`, `localsOfMerges`, `folding`, `stands`, `last` and everything else untouched.

  Replace the body of `in` (keeping its existing javadoc and adding the paragraph in Step 5):

```java
  public static Equivalences in(List<LoggedAssertion> log) {
    Objects.requireNonNull(log, "log");
    return new Equivalences(mergesIn(log), referencedEndpoints(log, emptiedCanonicalIds(log)));
  }

  /** Each merged local id and the id it turned out to be, last surviving claim wins. */
  private static Map<String, String> mergesIn(List<LoggedAssertion> log) {
    Retractions retractions = Retractions.in(log);
    Map<String, String> byLocal = new LinkedHashMap<>();
    for (int i = 0; i < log.size(); i++) {
      LoggedAssertion assertion = log.get(i);
      if (retractions.survives(i, assertion) && assertion instanceof SameAs merge) {
        byLocal.put(merge.localQid(), merge.canonicalQid());
      }
    }
    return byLocal;
  }

  /**
   * Every id an edge the fold KEEPS names, read raw off the log (#221, narrowed by #228).
   *
   * @param emptied the canonical ids a retraction emptied, which is what decides whether an edge is
   *     withdrawn. Passed in rather than read, because this method is one step of the fixed point
   *     {@link #emptiedCanonicalIds} computes and cannot ask for the finished answer
   */
  private static Set<String> referencedEndpoints(List<LoggedAssertion> log, Set<String> emptied) {
    Retractions retractions = Retractions.in(log);
    Map<String, String> byLocal = mergesIn(log);
    Set<String> referenced = new LinkedHashSet<>();
    for (int i = 0; i < log.size(); i++) {
      LoggedAssertion assertion = log.get(i);
      if (!retractions.survives(i, assertion)) {
        continue;
      }
      switch (assertion) {
        case AssertionRecord edge ->
            reference(referenced, byLocal, emptied, edge.fromQid(), edge.toQid());
        case OwnerEdge edge -> reference(referenced, byLocal, emptied, edge.fromQid(), edge.toQid());
        // A node claim, a merge or a retraction names no relationship, and this set is only ever
        // asked about a canonical id's edges. Named explicitly, matching Retractions.survives and
        // IngestService.apply, rather than through a default: a default arm would let a seventh
        // LoggedAssertion that DOES carry endpoints compile silently into "names nothing" and
        // reproduce the fix-round-1 defect this set exists to close.
        case NodeAssertion ignored -> {}
        case LocalEntity ignored -> {}
        case SameAs ignored -> {}
        case Retraction ignored -> {}
      }
    }
    return referenced;
  }

  /** One edge's two endpoints, unless the fold withdraws the edge and it therefore names nothing. */
  private static void reference(
      Set<String> referenced,
      Map<String, String> byLocal,
      Set<String> emptied,
      String from,
      String to) {
    if (emptied.contains(byLocal.getOrDefault(from, from))
        || emptied.contains(byLocal.getOrDefault(to, to))) {
      return;
    }
    referenced.add(from);
    referenced.add(to);
  }
```

  Replace the body of `retractedStandIns` (keeping its javadoc and adding Step 5's paragraph) with:

```java
  public static Set<String> retractedStandIns(List<LoggedAssertion> log) {
    Objects.requireNonNull(log, "log");
    return emptiedCanonicalIds(log);
  }

  /**
   * The emptied set as a least fixed point, because the rule is circular and the circle is real
   * (#228).
   *
   * <p>Which edges the fold withdraws depends on which canonical ids are emptied; which are emptied
   * depends on which stand-ins survive; which survive depends - since #221 - on which edges name
   * them. Dropping a withdrawn edge can therefore retire a stand-in, which can empty a second
   * canonical id, which can withdraw a second edge.
   *
   * <p><b>It terminates, and the argument is monotonicity rather than a bound on the depth.</b> A
   * larger emptied set withdraws at least as many edges, so it references at most as many ids,
   * stands at most as many merges, holds at most as many nodes and empties at least as many
   * canonical ids. So the chain from the empty set only grows, and a log has finitely many ids.
   *
   * <p><b>One round for a log with no retractions</b> - every log the owner's real graph has held -
   * because the first step returns the empty set it was given.
   */
  private static Set<String> emptiedCanonicalIds(List<LoggedAssertion> log) {
    Set<String> emptied = Set.of();
    while (true) {
      Set<String> next = emptiedGiven(log, emptied);
      if (next.equals(emptied)) {
        return Collections.unmodifiableSet(next);
      }
      emptied = next;
    }
  }

  /** One step of {@link #emptiedCanonicalIds}: what is emptied if {@code emptied} already is. */
  private static Set<String> emptiedGiven(List<LoggedAssertion> log, Set<String> emptied) {
    Retractions retractions = Retractions.in(log);
    Set<String> held =
        nodesHeld(log, standInCanonicalIds(log, referencedEndpoints(log, emptied)));
    Set<String> next = new LinkedHashSet<>();
    for (int i = 0; i < log.size(); i++) {
      if (log.get(i) instanceof SameAs merge
          && retractions.reaches(i, merge.localQid())
          && !held.contains(merge.canonicalQid())) {
        next.add(merge.canonicalQid());
      }
    }
    return next;
  }

  /**
   * {@link #standIns}' key set, over a referenced set the caller has decided - the same {@link
   * #stands} question, asked without building a node, so that {@link #emptiedCanonicalIds} can ask
   * it before {@link #in} has an answer to give.
   */
  private static Set<String> standInCanonicalIds(List<LoggedAssertion> log, Set<String> referenced) {
    Equivalences merges = new Equivalences(mergesIn(log), referenced);
    Set<String> ids = new LinkedHashSet<>();
    for (Integer at : localsOfMerges(log, UnaryOperator.identity()).keySet()) {
      if (log.get(at) instanceof SameAs merge && merges.stands(merge)) {
        ids.add(merge.canonicalQid());
      }
    }
    return ids;
  }
```

- [ ] **Step 5 — the two javadoc paragraphs the rule now needs.** Add to `in`'s javadoc, after its "referenced-endpoint set is built here too" paragraph:

```java
   * <p><b>Only the edges the fold KEEPS count, which is what makes this a fixed point</b> (#228).
   * An edge the fold withdraws claims nothing in the projection, so it cannot be what keeps a
   * superseded merge's stand-in alive - before this, a log holding a correction and an unrelated
   * retraction drew a node with no edges, under the id the owner had corrected himself away from,
   * carrying his withdrawn working title. Withdrawal depends on which canonical ids are emptied and
   * that depends back on this set, so the two are computed together: see {@link
   * #emptiedCanonicalIds} for why the loop terminates.
```

  and to `retractedStandIns`' javadoc, after its "Nor is one a surviving merge still stands in for" paragraph:

```java
   * <p><b>A stand-in kept alive only by an edge THIS set withdraws does not count as holding one</b>
   * (#228). That is the circularity {@link #emptiedCanonicalIds} resolves, and it is the reason
   * this method delegates rather than computing the answer in one pass as it used to.
```

- [ ] **Step 6 — verify GREEN across all four homes.** `./gradlew test --tests 'com.robsartin.segue.export.*' --tests 'com.robsartin.segue.domain.EquivalencesTest' --tests 'com.robsartin.segue.own.OwnRunTest' --tests 'com.robsartin.segue.ratings.*' --tests 'com.robsartin.segue.retract.RetractRunTest' --tests 'com.robsartin.segue.ingest.*'`. `StandInAgreesInEveryHomeTest`'s fixture has no withdrawn edge, so it should be untouched; `BothFoldsAgreeTest`'s `applied` should stay 30, because the two edges its fixture withdraws name `WREN`, `FORFEIT` and `LAPSE`, none of which is a superseded merge's canonical id. **If either moves, quote the failure and work out which row moved it before changing an expectation** — a moved answer there is either the fix reaching a case worth pinning or the fix reaching too far, and only reading the row tells the two apart.

- [ ] **Step 7 — positive control, both halves.** (a) Change `reference` to add the endpoints unconditionally (delete the early return). Expect the new test red on the `standIns` assertion. (b) Restore, then replace `emptiedCanonicalIds`' loop with a single `return Collections.unmodifiableSet(emptiedGiven(log, Set.of()));`. Expect the new test red again, because one round computes the emptied set from an unfiltered referenced set. **Quote both.** Restore; green.

- [ ] **Step 8 — gate and commit.** `./gradlew spotlessApply`, full blocking gate. `git status`, stage `src/main/java/com/robsartin/segue/domain/Equivalences.java src/test/java/com/robsartin/segue/export/InventedGraph.java src/test/java/com/robsartin/segue/export/TwiceMergedIdLeavesNoOrphanTest.java` (and `StandInAgreesInEveryHomeTest.java` only if Step 6 moved it). Commit: `A withdrawn edge keeps no superseded stand-in alive (#228)`.

---

### Task 4: The gate refuses a merge whose local side the projection does not hold

**Files:** Modify `src/main/java/com/robsartin/segue/ingest/IngestService.java`, `src/test/java/com/robsartin/segue/ingest/IngestServiceTest.java`.

- [ ] **Step 1 — the test.** In `IngestServiceTest`, add the imports `com.robsartin.segue.domain.SameAs` and `java.util.List` is already there; add these two tests at the end of the class:

```java
  private static final Instant CLAIMED_AT = Instant.parse("2026-08-31T20:00:00Z");

  @Test
  @DisplayName("should refuse a merge when the projection holds no node for its local side")
  void shouldRefuseAMergeWhenTheProjectionHoldsNoNodeForItsLocalSide() {
    // The bypass path #228 measures: OwnRun.declareMerge already refuses this - it reads what the
    // projection has MINTED and still survives - so a log can only carry it if something appended
    // through this method directly. The merge itself boots; what it leaves behind is a canonical id
    // with no stand-in, and the first edge naming that id stops the boot replay on a row ADR 19
    // forbids deleting.
    LocalEntity minted =
        LocalEntity.minted("Q00900042", NodeKind.WORK, "a working title he took back", CLAIMED_AT);
    IngestService.claim(log, minted);
    IngestService.retract(log, new Retraction("Q00900042", "the wrong thing", CLAIMED_AT));

    assertThatThrownBy(
            () -> IngestService.claim(log, SameAs.declared("Q00900042", "Q10000900120", CLAIMED_AT)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Q00900042")
        .hasMessageContaining("Q10000900120")
        .hasMessageContaining("holds no node");

    assertThat(log.readAll())
        .as("validated BEFORE the append, so the log never carries a row that cannot boot")
        .hasSize(2);
  }

  @Test
  @DisplayName("should append a merge when the projection does hold a node for its local side")
  void shouldAppendAMergeWhenTheProjectionHoldsANodeForItsLocalSide() {
    // Without this the refusal above would be satisfied by refusing every merge.
    LocalEntity minted =
        LocalEntity.minted("Q00900042", NodeKind.WORK, "a self-pressed record", CLAIMED_AT);
    IngestService.claim(log, minted);
    SameAs merge = SameAs.declared("Q00900042", "Q10000900120", CLAIMED_AT);

    IngestService.claim(log, merge);

    assertThat(log.readAll()).containsExactly(minted, merge);
  }
```

- [ ] **Step 2 — RED, and quote it.** `./gradlew test --tests 'com.robsartin.segue.ingest.IngestServiceTest'`. Expect `shouldRefuseAMergeWhenTheProjectionHoldsNoNodeForItsLocalSide` red on `Expecting code to raise a throwable` (nothing is thrown today), and the second test green. **Quote the failure.**

- [ ] **Step 3 — GREEN.** In `IngestService`, add the imports `com.robsartin.segue.domain.Equivalences` (already present), `java.util.ArrayList` and `java.util.function.UnaryOperator`, insert the guard call into `claim` immediately before `log.append(claim)`:

```java
    refuseAClaimTheFoldCouldNotHold(log.readAll(), claim);
    log.append(claim);
```

  and add the method below `claim`:

```java
  /**
   * Refuse an owner claim that would leave the log unbootable, before it is appended (#228).
   *
   * <p><b>Here rather than in {@code OwnRun}, and in addition to it.</b> That tool refuses both of
   * these already, which is why the two logs issue #228 measures are reachable only by a caller
   * that comes straight here. A guard in front of one caller is not a gate; this method is the one
   * every owner claim passes, {@code OwnRun}'s included, so the log cannot carry a row no fold can
   * project. The log is append-only (ADR 19), so a row rejected at replay instead would be rejected
   * at every replay, forever.
   *
   * <p><b>The two refusals ask narrower questions than {@code OwnRun} does, deliberately.</b>
   * {@code OwnRun.declareMerge} requires a merge's local side to be something the owner MINTED,
   * because pointing a merge at a sourced entity is a different claim that tool does not make; this
   * asks the fold's own question - {@link Equivalences#localsOfMerges}, any surviving node claim -
   * because spec ruling 2 requires the fold to accept a local-shaped id a source named. And {@code
   * OwnRun.assertEdge} refuses an endpoint it does not OFFER, which includes a merged local id;
   * this asks only whether the FOLDED endpoint has a node, because both folds resolve such an edge
   * onto the canonical id and it boots. Two questions, two homes, and the friendlier message is the
   * tool's.
   *
   * <p><b>It reads the whole log.</b> The only caller is a dev-side tool that has already read it
   * once in the same run and appends exactly one row, so this is a second read per invocation
   * rather than per claim.
   */
  private static void refuseAClaimTheFoldCouldNotHold(
      List<LoggedAssertion> logged, LoggedAssertion claim) {
    List<LoggedAssertion> would = new ArrayList<>(logged);
    would.add(claim);
    if (claim instanceof SameAs merge
        && !Equivalences.localsOfMerges(would, UnaryOperator.identity())
            .containsKey(would.size() - 1)) {
      throw new IllegalArgumentException(
          "the projection holds no node for "
              + merge.localQid()
              + ", so this merge would build no stand-in for "
              + merge.canonicalQid()
              + " — the first edge naming that id would stop the boot replay, on rows ADR 19"
              + " forbids deleting (#228). Claim a node for "
              + merge.localQid()
              + " first, or merge an id the projection does hold");
    }
  }
```

- [ ] **Step 4 — verify GREEN.** `./gradlew test --tests 'com.robsartin.segue.ingest.*' --tests 'com.robsartin.segue.own.*'`. Green. `OwnRun` refuses first with its own message, so no `OwnRunTest` expectation should move; if one does, quote it and say which guard fired.

- [ ] **Step 5 — positive control.** Comment out the `refuseAClaimTheFoldCouldNotHold(...)` call. Re-run; expect the refusal test red on `Expecting code to raise a throwable`. **Quote it.** Restore; green.

- [ ] **Step 6 — gate and commit.** `./gradlew spotlessApply`, full blocking gate. `git status`, stage `src/main/java/com/robsartin/segue/ingest/IngestService.java src/test/java/com/robsartin/segue/ingest/IngestServiceTest.java`. Commit: `The gate refuses a merge with no local side, before the append (#228)`.

---

### Task 5: The gate refuses an owner edge naming an endpoint the fold would hold no node for

**Files:** Modify `src/main/java/com/robsartin/segue/ingest/IngestService.java`, `src/test/java/com/robsartin/segue/ingest/IngestServiceTest.java`.

- [ ] **Step 1 — the tests, refusal and the control that matters most.** Append to `IngestServiceTest`:

```java
  @Test
  @DisplayName("should refuse an owner edge when the fold would hold no node for an endpoint")
  void shouldRefuseAnOwnerEdgeWhenTheFoldWouldHoldNoNodeForAnEndpoint() {
    LocalEntity minted =
        LocalEntity.minted("Q00900042", NodeKind.WORK, "a self-pressed record", CLAIMED_AT);
    IngestService.claim(log, minted);

    assertThatThrownBy(
            () ->
                IngestService.claim(
                    log, OwnerEdge.claimed("Q00900042", "Q0900199", "INFLUENCED_BY", CLAIMED_AT)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Q0900199")
        .hasMessageContaining("holds no node");

    assertThat(log.readAll()).hasSize(1);
  }

  @Test
  @DisplayName("should append an owner edge naming a merged local id, which the fold resolves")
  void shouldAppendAnOwnerEdgeNamingAMergedLocalIdWhichTheFoldResolves() {
    // Spec ruling 2: "a later claim naming the local id, by a path that bypasses the tool, folds
    // onto the canonical id like any other". OwnRun refuses this by name, as a courtesy; the gate
    // must not, because the fold resolves it onto an id that HAS a stand-in and the log boots.
    // A gate that asked about the raw endpoint would refuse a claim both folds can project.
    ingest.record(new NodeAssertion("Q0900101", NodeKind.PERSON, "Ines Marlow", WIKIDATA));
    IngestService.claim(
        log, LocalEntity.minted("Q00900042", NodeKind.WORK, "a self-pressed record", CLAIMED_AT));
    IngestService.claim(log, SameAs.declared("Q00900042", "Q10000900120", CLAIMED_AT));
    OwnerEdge edge = OwnerEdge.claimed("Q0900101", "Q00900042", "INFLUENCED_BY", CLAIMED_AT);

    IngestService.claim(log, edge);

    assertThat(log.readAll()).hasSize(4).endsWith(edge);
  }
```

  Add the import `com.robsartin.segue.domain.OwnerEdge`.

- [ ] **Step 2 — RED, and quote it.** `./gradlew test --tests 'com.robsartin.segue.ingest.IngestServiceTest'`. Expect the refusal test red on `Expecting code to raise a throwable` and the resolution test **green** (nothing refuses anything yet). **Quote the failure.**

- [ ] **Step 3 — GREEN.** In `IngestService`, extend `refuseAClaimTheFoldCouldNotHold` with the edge arm, after the merge arm:

```java
    if (claim instanceof OwnerEdge owned) {
      Set<String> held = Equivalences.nodesTheFoldHolds(would);
      Equivalences.folding(would)
          .foldEndpoints(owned.toAssertion())
          .ifPresent(
              folded -> {
                refuseAnEndpointNothingHolds(owned.fromQid(), folded.fromQid(), held);
                refuseAnEndpointNothingHolds(owned.toQid(), folded.toQid(), held);
              });
    }
```

  and add:

```java
  /**
   * Refuse one endpoint of an owner edge the fold would hold no node for (#228).
   *
   * <p>Asked of the FOLDED id rather than the claimed one, and both are named when they differ, so
   * that an operator reading the refusal can see whether the id he typed is the id the projection
   * complained about. A folded pair the fold yields nothing for is not asked at all - a withdrawn
   * or collapsed edge applies nothing and the log boots, which is the only thing this guard is for.
   */
  private static void refuseAnEndpointNothingHolds(
      String claimed, String folded, Set<String> held) {
    if (held.contains(folded)) {
      return;
    }
    throw new IllegalArgumentException(
        "the projection holds no node for "
            + folded
            + (folded.equals(claimed) ? "" : " (claimed against " + claimed + ", which folds onto "
                + folded + ")")
            + " — an owner edge naming an endpoint the fold holds no node for stops the boot"
            + " replay, on a row ADR 19 forbids deleting (#228). Mint or seed it first");
  }
```

  Add the import `java.util.Set`.

- [ ] **Step 4 — verify GREEN.** `./gradlew test --tests 'com.robsartin.segue.ingest.*' --tests 'com.robsartin.segue.own.*' --tests 'com.robsartin.segue.export.*'`. Green.

- [ ] **Step 5 — positive control, and a second one for the fold-aware half.** (a) Comment out the `if (claim instanceof OwnerEdge owned)` block; expect the refusal test red. Restore. (b) Change `refuseAnEndpointNothingHolds`'s calls to pass `owned.fromQid()`/`owned.toQid()` as the `folded` argument too — the raw-endpoint version of the same guard — and expect `shouldAppendAnOwnerEdgeNamingAMergedLocalIdWhichTheFoldResolves` red, which is what says the guard reads the fold rather than the claim. **Quote both.** Restore; green.

- [ ] **Step 6 — gate and commit.** `./gradlew spotlessApply`, full blocking gate. `git status`, stage the two files. Commit: `The gate refuses an owner edge with no endpoint to land on (#228)`.

---

### Task 6: The boot names the row and the repair, for a log that already carries one

**Files:** Modify `src/test/java/com/robsartin/segue/export/InventedGraph.java`, `src/test/java/com/robsartin/segue/export/MergeAfterARetractionTest.java`, `src/main/java/com/robsartin/segue/ingest/GraphProjector.java`.

Closes break 1, which no fold rule can reach. The guard in Task 4 stops the row being written; this is for a log written before it, or by hand.

- [ ] **Step 1 — one invented id.** In `InventedGraph`, after `SEVERED`:

```java
  /**
   * A ninth canonical id: the one a merge names when it is declared AFTER its local side was
   * retracted, so the merge has no local side, builds no stand-in, and leaves an id nothing in the
   * log describes (#228). ADR 62's eleven-digit shape, for the reason {@link #KETTLES} takes it.
   */
  static final String RESUMED = "Q10000900114";
```

- [ ] **Step 2 — the tests.** In `MergeAfterARetractionTest`, add the static imports for `RESUMED` and `SLIP`, the imports `org.assertj.core.api.Assertions.assertThatThrownBy` and `com.robsartin.segue.domain.NodeKind` (already there), and append:

```java
  /**
   * Minted, merged, retracted, then merged onto a <b>different</b> canonical id, with an owner edge
   * naming that id. Nothing retracted {@code RESUMED}; the merge simply has no local side, so no
   * stand-in is built and the log holds nothing that says what {@code RESUMED} is. There is no fold
   * rule here: building the node would assemble it out of retracted rows, and withdrawing the edge
   * would replay a live claim into nothing. See the spec's "alternatives" section.
   *
   * <p>Measured on {@code a7c3455}: {@code GraphProjector.project} throws {@code replay failed at
   * sequence 6}, {@code assertion references unknown entity Q10000900114 - upsert the node first}.
   */
  private static FakeAssertionLog remergedElsewhereLog() {
    return new FakeAssertionLog()
        .with(
            node(WREN, NodeKind.PERSON, "Wren Alderman"),
            minted(LAPSE, NodeKind.WORK, "a working title he took back"),
            merged(LAPSE, FORFEIT),
            retract(LAPSE),
            merged(LAPSE, RESUMED),
            owned(WREN, RESUMED, "INFLUENCED_BY"));
  }

  @Test
  @DisplayName("the boot names the row, the id and the repair when a re-merge left no stand-in")
  void shouldNameTheRowAndTheRepairWhenAReMergeLeftItsCanonicalIdWithNoNode() {
    try (TinkerGraphStore replayed = new TinkerGraphStore()) {
      assertThatThrownBy(
              () -> GraphProjector.project(remergedElsewhereLog(), replayed, IdentityMerge.NONE))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("sequence 6")
          .hasMessageContaining(RESUMED)
          .hasMessageContaining("no node stands for")
          .hasMessageContaining("append a claim that gives the named id a node");
    }
  }

  @Test
  @DisplayName("the same log boots once a local id the projection holds is merged onto that id")
  void shouldBootWhenALocalIdTheProjectionHoldsIsMergedOntoTheSameCanonicalId() {
    // The repair the message names, carried out: a NEW local id, because an id is never recycled
    // (OwnRun.anIdNothingHasNamed reads every row the log has ever held), minted and merged. A
    // diagnosis that named a repair which does not repair would be worse than no diagnosis.
    FakeAssertionLog repaired =
        remergedElsewhereLog()
            .with(
                minted(SLIP, NodeKind.WORK, "the working title, minted again"),
                merged(SLIP, RESUMED));

    try (TinkerGraphStore replayed = new TinkerGraphStore()) {
      GraphProjector.project(repaired, replayed, IdentityMerge.NONE);

      assertThat(replayed.node(RESUMED))
          .as("the second merge has a local side, so the stand-in exists and the edge lands")
          .isPresent();
      assertThat(replayed.edgeCount()).isEqualTo(1);
    }
  }

  @Test
  @DisplayName("the exporter still counts the edge as dangling where the boot refuses the log")
  void shouldStillCountTheEdgeAsDanglingWhereTheBootRefusesTheLog() {
    // Pinned, not fixed. The two folds disagree here on purpose: the exporter has to produce a
    // picture and reports the shortfall in the count whose javadoc says it should always be zero,
    // and the boot refuses to start. ADR 44 argues why tolerating the missing endpoint in the boot
    // instead would take the loud failure away from every other cause of one.
    LogProjection folded = LogProjection.of(remergedElsewhereLog());

    assertThat(folded.nodes()).doesNotContainKey(RESUMED);
    assertThat(folded.danglingEdges()).isEqualTo(1);
    assertThat(folded.withdrawnEdges())
        .as("nothing retracted RESUMED, so this is not a withdrawal and must not be counted as one")
        .isZero();
  }

  @Test
  @DisplayName("the boot refuses nothing when every edge names an endpoint the fold holds")
  void shouldRefuseNothingWhenEveryEdgeNamesAnEndpointTheFoldHolds() {
    try (TinkerGraphStore replayed = new TinkerGraphStore()) {
      GraphProjector.project(mergedAndNotRetractedLog(), replayed, IdentityMerge.NONE);

      assertThat(replayed.edgeCount())
          .as("without this the refusal above would be satisfied by refusing every log")
          .isEqualTo(1);
    }
  }
```

- [ ] **Step 3 — RED, and quote it.** `./gradlew test --tests 'com.robsartin.segue.export.MergeAfterARetractionTest'`. Expect `shouldNameTheRowAndTheRepairWhenAReMergeLeftItsCanonicalIdWithNoNode` red on the message check — AssertJ reporting that the actual message `replay failed at sequence 6` does not contain `no node stands for` — and the other three green. **Quote it.** The other three being green is what says the fixture, the repair and the pin are all describing today's code correctly.

- [ ] **Step 4 — GREEN.** In `GraphProjector`, add the imports `com.robsartin.segue.domain.AssertionRecord`, `com.robsartin.segue.domain.OwnerEdge`, `java.util.ArrayList`, `java.util.Optional`, `java.util.Set`, and insert the call in `project` immediately after `Equivalences equivalences = Equivalences.folding(assertions);`:

```java
    refuseRowsNamingAnEntityNoNodeStandsFor(assertions, retractions, equivalences);
```

  then add:

```java
  /**
   * Refuse the whole log, by name, where a surviving edge the fold keeps names an entity no node in
   * the log stands for (#228).
   *
   * <p><b>What this replaces is not a silence, it is an unhelpful noise.</b> {@code
   * TinkerGraphStore.record} refuses such an edge with {@code assertion references unknown entity
   * … - upsert the node first}, wrapped as {@code replay failed at sequence N}, which names the id
   * and nothing about why the id has no node or what to do next. The two logs issue #228 measured
   * both arrive that way, and both are permanent: ADR 19 forbids removing the row, so the message
   * is what the operator has.
   *
   * <p><b>Before anything is applied, and reporting every row rather than the first.</b> The store
   * is untouched when this throws, so a refused boot leaves no half-built graph; and an operator
   * repairing a log wants the whole list, not one row per restart. That is a departure from the
   * replay loop's own fail-on-the-first-row rule, and it is deliberate: this checks a decidable
   * property of the log, where the loop catches whatever a store happens to object to.
   *
   * <p><b>{@code LogProjection} deliberately does not do this.</b> The exporter has to produce a
   * picture and reports the same shortfall as {@code danglingEdges}. ADR 44 argues why the boot's
   * answer is the opposite one, and {@code MergeAfterARetractionTest} pins both.
   */
  private static void refuseRowsNamingAnEntityNoNodeStandsFor(
      List<LoggedAssertion> assertions, Retractions retractions, Equivalences equivalences) {
    Set<String> held = Equivalences.nodesTheFoldHolds(assertions);
    List<String> rows = new ArrayList<>();
    for (int i = 0; i < assertions.size(); i++) {
      LoggedAssertion assertion = assertions.get(i);
      if (!retractions.survives(i, assertion)) {
        continue;
      }
      Optional<LoggedAssertion> folded = equivalences.foldEndpoints(assertion);
      if (folded.isEmpty()) {
        // Withdrawn (#224) or collapsed (#178). Nothing reaches the graph, so nothing can be
        // missing an endpoint.
        continue;
      }
      switch (folded.get()) {
        case AssertionRecord edge -> describe(rows, i + 1, assertion, edge, held);
        case OwnerEdge edge -> describe(rows, i + 1, assertion, edge.toAssertion(), held);
        default -> {
          // A node claim, a minted entity and a merge all name no endpoint to be missing.
        }
      }
    }
    if (rows.isEmpty()) {
      return;
    }
    throw new IllegalStateException(
        "replay refused: "
            + rows.size()
            + " row(s) name an entity no node stands for.\n"
            + String.join("\n", rows)
            + "\nNothing is deleted (ADR 19). To repair: append a claim that gives the named id a"
            + " node — a node claim for it, or a merge whose local side the projection does hold —"
            + " and the row projects again. See ADR 44, ADR 59 and issue #228.");
  }

  /** One line per endpoint the fold holds no node for, naming the claim as the log wrote it. */
  private static void describe(
      List<String> rows,
      int sequence,
      LoggedAssertion claimed,
      AssertionRecord folded,
      Set<String> held) {
    for (String endpoint : List.of(folded.fromQid(), folded.toQid())) {
      if (!held.contains(endpoint)) {
        rows.add(
            "  sequence " + sequence + ": " + written(claimed) + " names " + endpoint
                + ", which no node stands for");
      }
    }
  }

  /** A claim as the log wrote it, before the fold moved either endpoint. */
  private static String written(LoggedAssertion claimed) {
    return switch (claimed) {
      case AssertionRecord edge ->
          edge.fromQid() + " " + edge.typeCode() + " " + edge.toQid();
      case OwnerEdge edge -> edge.fromQid() + " " + edge.typeCode() + " " + edge.toQid();
      default -> claimed.toString();
    };
  }
```

  Add a paragraph to the class javadoc, after the "Replay is fatal on the first failure" one:

```java
 * <p><b>One family of failure is refused before the loop begins, by name</b> (#228). A surviving
 * edge the fold keeps, naming an entity no node in the log stands for, cannot be applied by any
 * store, and the message a store gives for it names the id and no cause. So the log is checked
 * against {@link Equivalences#nodesTheFoldHolds} first, every offending row is listed with its
 * sequence number, and the repair is named. The loop below still catches everything else on the
 * first row that fails.
```

- [ ] **Step 5 — verify GREEN.** `./gradlew test --tests 'com.robsartin.segue.export.*' --tests 'com.robsartin.segue.ingest.*'`. Green, including `RetractedStandInTakesItsEdgesTest` and `TwiceMergedIdLeavesNoOrphanTest` — every log in them boots today, so a pre-flight that refused any of them would be over-reaching.

- [ ] **Step 6 — positive control, both directions.** (a) Comment out the `refuseRowsNamingAnEntityNoNodeStandsFor(...)` call; expect the diagnosis test red on the old message. Restore. (b) Delete the `if (folded.isEmpty()) { continue; }` early exit and replace it with nothing, so a withdrawn edge is described from its raw endpoints — expect `RetractedStandInTakesItsEdgesTest` and `MergeAfterARetractionTest`'s withdrawal tests red, which says the pre-flight really does read the fold rather than the log. **Quote both.** Restore; green.

- [ ] **Step 7 — gate and commit.** `./gradlew spotlessApply`, full blocking gate. `git status`, stage `src/main/java/com/robsartin/segue/ingest/GraphProjector.java src/test/java/com/robsartin/segue/export/InventedGraph.java src/test/java/com/robsartin/segue/export/MergeAfterARetractionTest.java`. Commit: `The boot names the row and the repair instead of an unknown entity (#228)`.

---

### Task 7: The decisions, written down

**Files:** Modify `docs/adr/0044-retraction-as-a-new-claim.md`, `docs/adr/0059-owner-claims-as-a-third-layer.md`, `docs/developer-guide.md`, `CLAUDE.md`.

Nothing above is withdrawn and no sentence in either ADR is edited — including the residual bullets, which are the true account of the code between #224 and this issue and are what these amendments answer.

- [ ] **Step 1 — ADR 44's amendment.** Append to `docs/adr/0044-retraction-as-a-new-claim.md`:

```markdown
**Amendment (2026-09-04, issue #228): the first of the two residuals above is closed, and the
withdrawal rule now reads the endpoints the fold resolves. The second is closed at the producer and
at the boot, not by a fold rule, and the reason a fold rule lost is recorded here.**

**What the withdrawal rule missed, measured on `a7c3455`.** `Equivalences.namesARetractedStandIn`
read `claim.fromQid()` and `claim.toQid()` as the claim wrote them, so an edge reaching an emptied
canonical id *through a merge* rather than by name was not withdrawn. On
`[node(WREN), minted(LAPSE), merged(LAPSE → FORFEIT), retract(LAPSE), merged(LAPSE → FORFEIT),
owned(WREN → LAPSE)]` the fold already named `FORFEIT` in `retractedStandIns`, reported
`danglingEdges 1` and `withdrawnEdges 0`, and the boot replay threw `replay failed at sequence 6`,
`assertion references unknown entity Q10000900112 - upsert the node first`. **Ruling:** the
predicate resolves both endpoints through `canonicalByLocal` before asking. The edge is claimed
against the same absent endpoint either way — the entity the owner retracted, under a name his own
merge gave it — which is this amendment's own 2026-09-03 sentence, applied to the case its
implementation missed. This is a defect against a decision already made, not a new decision.

**The other residual has no fold rule, and both candidates were considered.** A local id retracted
and then merged onto a *different* canonical id leaves that id with no stand-in and nothing in the
log describing it. **Building the stand-in anyway** was rejected on this ADR's own ground: the local
side's kind and label are rows the retraction exists to stop the projection reading, which is the
"node assembled entirely out of retracted rows" argument this ADR already makes once. **Withdrawing
the edge** was rejected because nothing retracted the second canonical id — it may be a real
Wikidata item a source claims tomorrow — so withdrawing would replay a live claim into nothing,
which is the tolerate-the-dangle option under another name, and it turns `danglingEdges` from an
alarm that stays zero into a number nobody sees rise. The ruling is at the producer and at the boot
instead; see [ADR 59](0059-owner-claims-as-a-third-layer.md)'s 2026-09-04 amendment.

**Consequence for `retractEntity`.** `RetractRun.strandedByThisRetraction` asks the same predicate,
so its report now names an edge that reaches an emptied id through a merge as well as one that names
it directly — which is what keeps that report, `LogProjection.withdrawnEdges` and a `full` export
agreeing by construction rather than by three people counting alike.
```

- [ ] **Step 2 — ADR 59's amendment.** Append to `docs/adr/0059-owner-claims-as-a-third-layer.md`:

```markdown
**Amendment (2026-09-04, issue #228): a withdrawn edge keeps no superseded stand-in alive, and an
owner claim that would leave the log unbootable is refused before the append.**

**The surviving-edge widening counted an edge the fold does not keep.** The 2026-09-03 amendment
above widened `Equivalences.stands` to *last-wins OR a surviving edge names this merge's canonical
id*, and built `referencedEndpoints` from the **surviving** rows. An edge the fold *withdraws*
(ADR 44's #224 rule) survives every retraction and claims nothing all the same. Measured on
`a7c3455`, on a log the supported flow itself produces — a correction, plus an unrelated retraction
that empties the other end of the one edge naming the superseded id — the exported fold and the boot
replay each held a labelled node with no edges under the id the owner had corrected himself away
from, carrying his withdrawn working title, while the same fold reported the edge as withdrawn.
**Ruling:** `referencedEndpoints` counts only the edges the fold keeps. Because withdrawal depends
on which canonical ids are emptied, and that depends back on which stand-ins survive, the two are
computed together as a least fixed point over the emptied set: the step is monotone — a larger
emptied set withdraws at least as many edges, references at most as many ids and therefore empties
at least as many canonical ids — so the chain from the empty set terminates, and a log with no
retractions costs exactly one round. **One round instead of a loop was rejected**: it closes the
measured case, leaves a constructible chain open, and gives an ADR no line to draw.

**Both folds and both label copies move together**, which is the point: three of the stand-in rule's
four homes read `Equivalences.in`, so `OwnRun` stops offering an endpoint whose node is an artefact
and `ratings/Labels.forQids` reports a rating carried onto it as `(not in the graph)`. The fourth,
`IngestService.standIn` on the live path, is handed `Equivalences.NONE`: it holds no log, so it has
no edge to withdraw and is unchanged.

**An owner claim is validated before the append.** `IngestService.claim` — the one gate every owner
claim passes, `OwnRun`'s included — now refuses a `SameAs` whose local side the projection holds no
node for, and an `OwnerEdge` whose **folded** endpoint the fold would hold no node for. Both were
already refused by `OwnRun`, which is why the logs issue #228 measured are reachable only by a
caller that comes straight to `claim` or writes the row into SQLite by hand; a guard in front of one
caller is not a gate, and the log is append-only, so a claim rejected only at replay is rejected at
every replay for good. **The gate's questions are narrower than the tool's, deliberately**: the tool
requires a merge's local side to be something the owner *minted* and refuses an endpoint it does not
*offer*, while the gate asks the fold's own questions — any surviving node claim (spec ruling 2),
and the endpoint the fold would resolve to — so it refuses only what cannot boot. Two questions, two
homes, and the friendlier message stays the tool's.

**And a log that already carries such a row is refused at boot, by name.** `GraphProjector.project`
checks every surviving edge the fold keeps against `Equivalences.nodesTheFoldHolds` before it
applies anything, and throws one message listing each offending sequence number, the id no node
stands for, and the repair: append a claim that gives that id a node — a node claim, or a merge
whose local side the projection does hold. It replaces `assertion references unknown entity … -
upsert the node first`, which names an id and no cause. It reports every row rather than the first,
departing from the replay loop's own rule, because this is a decidable property of the log and an
operator repairing one wants the list rather than one row per restart. `LogProjection` deliberately
still tolerates the same edge as dangling: the exporter has to produce a picture, and ADR 44 argues
why the boot's answer is the opposite one.

**A residual, recorded rather than repaired.** `IngestService.record` — the sourced path — still
appends before it applies, so a sourced edge naming an endpoint the graph has never seen is already
in the log when `GraphStore.record` throws. That is the same shape this amendment closes for owner
claims, on a path whose log-then-graph ordering is argued in that method's own javadoc, and changing
it is a decision against ADR 19's reasoning that belongs in its own issue. The boot refusal above
now names such a row, which is strictly better than what it did before.

**Every path in this amendment is fixture-only today.** Measured by issue #227's census on the real
graph on 2026-09-04: 0 retractions, 0 merges, 1 minted local. That is the argument for the cheapest
correct answer at each ruling rather than the most general one; it is not an argument for leaving
any of them unfixed, since the log is append-only and the first instance of each is permanent.
```

- [ ] **Step 3 — the developer guide.** In `### A merge is said, not done — and it lands in two places at two times`, after the paragraph ending *"…not your opinion about the thing you corrected yourself onto."*, insert:

```markdown
**The exception needs a surviving edge the fold actually keeps.** An edge the fold *withdraws* —
because it names a canonical id a retraction emptied (ADR 44) — claims nothing in the projection, so
it keeps no superseded stand-in alive either, even though every row of it survives
([#228](https://github.com/robsartin/segue/issues/228)). Before that, a correction plus an unrelated
retraction left a labelled node with no edges under the id you corrected away from.
```

  In `### Undoing one, and why it matters which id you retract`, after the paragraph beginning *"It reaches backwards only, by position in the log…"*, insert:

```markdown
**A merge declared after its local side was retracted is refused before it is written.** The local
id has no node in the projection, so the merge would stand in for nothing, and the first edge naming
its canonical id would stop the boot replay on rows nothing can be deleted from.
`ownClaim merge` has always refused it; since
[#228](https://github.com/robsartin/segue/issues/228) so does `IngestService.claim`, the gate every
owner claim passes, and so does an owner edge naming an endpoint the fold would hold no node for. If
a log already carries one, the boot says so by name — every offending sequence number, the id no
node stands for, and the repair — instead of `assertion references unknown entity`. The repair is
always a new claim, never a deletion: mint a fresh local id and merge that one, since ids are never
recycled.
```

- [ ] **Step 4 — one `CLAUDE.md` gotcha.** In `## Gotchas already paid for`, after the bullet beginning **"A retraction is honoured by the FOLD, never applied to a store."**, insert:

```markdown
- **An owner claim is validated BEFORE the append, because the log is append-only and a row that
  cannot boot cannot be removed.** `IngestService.claim` — not just `OwnRun` — refuses a merge whose
  local side the projection holds no node for, and an owner edge whose *folded* endpoint the fold
  would hold no node for. The gate asks the fold's questions and the tool asks narrower ones; both
  homes are deliberate. Where a log already carries such a row, `GraphProjector` refuses at boot
  with every offending sequence number and the repair, rather than the store's `assertion references
  unknown entity`. **Not every broken shape has a fold rule**: an edge naming a canonical id a
  retraction emptied is *withdrawn* (ADR 44, and since #228 that reads the endpoints the fold
  resolves), but an id nothing ever described cannot be repaired by a fold without either inventing
  a node out of retracted rows or dropping a live claim. ADR 44 and ADR 59, both amended 2026-09-04.
```

- [ ] **Step 5 — verify the docs build.** `./gradlew spotlessApply`, then the full blocking gate — `javadocInCheck` and the ADR/guide checks run there. Confirm no test reads these files as declared inputs and went stale: if a doc-reading test is `UP-TO-DATE` after this edit, say so in the report rather than assuming it passed.

- [ ] **Step 6 — commit.** `git status`, stage `docs/adr/0044-retraction-as-a-new-claim.md docs/adr/0059-owner-claims-as-a-third-layer.md docs/developer-guide.md CLAUDE.md`. Commit: `Record the four rulings and the residual (#228)`.

---

## Verification, at the end

- [ ] Full blocking gate one more time from clean: `./gradlew clean` then `SEGUE_REQUIRE_BROWSER=true SEGUE_REQUIRE_GRAPHVIZ=true ./gradlew check --rerun-tasks`. Quote the final line.
- [ ] `git log --oneline a7c3455..HEAD` — seven commits, each one green.
- [ ] `git status` — clean. No probe, no scratch file, no `~/.segue` access at any point.
