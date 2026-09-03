# A twice-merged local id leaves no orphan — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A merge the owner has since corrected names no stand-in node, carries no rating and appears in neither fold. The first canonical id keeps nothing; the last keeps everything.

**Architecture:** One new predicate in `domain` — `Equivalences.stands(SameAs)` — asked by all four homes of the stand-in rule. Five one-line guards, no new type, no new field, no signature change outside `Equivalences`. The two label copies (`OwnRun`, `Labels`) land **before** the two folds, because the tool must stop offering an endpoint before the fold stops giving it a node.

**Tech Stack:** Java 25, Gradle 9.7.1 (plain `./gradlew`), JUnit 5, AssertJ, TinkerGraph, SQLite.

**Spec:** `docs/superpowers/specs/2026-09-03-twice-merged-orphan-design.md`

## Global Constraints

- **Pure TDD / red first**: every behaviour is seen red for the right reason — a real assertion failure, never a compile error — before the code that makes it green. Quote the actual failure text in the report. Test names `should<Expected>When<Condition>` with `@DisplayName`.
- **Every guard gets a positive control**: plant the defect, watch the check fire, quote it, remove the plant. Written out as steps below.
- **Mikado**: the gate is green before every commit, and no commit leaves the two folds disagreeing (see Task 4's note). **Stage by explicit path, git stderr visible — never `git add -A`, never `2>/dev/null` on `git add`.** Read `git status` before every commit. Commits end `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`. Never cite a `.superpowers/` path from a committed file.
- Gate, **blocking, never backgrounded**: `SEGUE_REQUIRE_BROWSER=true SEGUE_REQUIRE_GRAPHVIZ=true ./gradlew check --rerun-tasks`. Fast loops named per task. Run `./gradlew spotlessApply` before each gate.
- **Only JDK 25 is installed and Gradle 9.7.1 launches on it.** Plain `./gradlew`; never `/usr/libexec/java_home -v 21` (it silently returns 25).
- **Never run a writing dev task** (`own`, `ownClaim`, `retractEntity`, any seeding task). `~/.segue/segue.db` is never read, written, copied or created.
- Every id invented in `src/test` must take an unallocatable shape or `arch/StandInQidsDenoteNothingTest` reds: two leading zeros for a local entity (ADR 59), eleven digits with no leading zero for a merge's canonical side (ADR 62), one leading zero for anything else (ADR 58).
- **YAGNI**: no parameter, helper or accessor beyond what a step below actually uses.
- Machine is loaded: no wall-clock assertions anywhere.

---

### Task 1: The reproducing test, seen red, parked

**Files:** Modify `src/test/java/com/robsartin/segue/export/InventedGraph.java`. Create `src/test/java/com/robsartin/segue/export/TwiceMergedIdLeavesNoOrphanTest.java`.

The precedent for parking a guard red is `MergedIdIsDrawnAsAnOrphanTest`, whose javadoc records that it was committed `@Disabled` for exactly as long as the defect stood, and that the gate's **skipped** count is what says it came off. Do the same: the tree stays green at every commit, and Task 4 removes the annotations.

- [ ] **Step 1 — two invented ids.** Add to `InventedGraph`, after `TWICE`:

```java
  /**
   * A sixth id the owner minted, and the one issue #221 turns on: merged onto {@link #MISHEARD} and
   * then — the correction — onto {@link #WATERMARK}. Two leading zeros, for {@link #ALMANAC}'s
   * reason.
   */
  static final String CORRECTED = "Q006";
```

  and, after `STANDING`:

```java
  /**
   * A fourth canonical id: the wrong Wikidata item, named by a merge the owner has since corrected
   * (#221). ADR 62's eleven-digit shape, for the reason {@link #KETTLES} takes it, keeping the
   * band-A digits of the ids beside it.
   */
  static final String MISHEARD = "Q10000900109";
```

- [ ] **Step 2 — the test class.** Create it in full:

```java
package com.robsartin.segue.export;

import static com.robsartin.segue.export.InventedGraph.CORRECTED;
import static com.robsartin.segue.export.InventedGraph.MISHEARD;
import static com.robsartin.segue.export.InventedGraph.WATERMARK;
import static com.robsartin.segue.export.InventedGraph.WREN;
import static com.robsartin.segue.export.InventedGraph.merged;
import static com.robsartin.segue.export.InventedGraph.minted;
import static com.robsartin.segue.export.InventedGraph.node;
import static com.robsartin.segue.export.InventedGraph.owned;
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
 * Issue #221: a local id merged onto one canonical id and then onto another leaves nothing behind
 * under the first.
 *
 * <p><b>Why this is not a case inside {@code BothFoldsAgreeTest}.</b> That test compares the two
 * folds with each other, and until #221 they agreed about the orphan — the exporter's fold built it
 * from {@code Equivalences.standIns} and the boot replay built it a second time from {@code
 * IngestService.standIn}. Two folds that agree about a wrong answer is the one failure comparing
 * them cannot see, so this file looks at the thing itself: it asserts the absence, on both folds
 * separately and on the DOT artefact, and {@code BothFoldsAgreeTest} gains the twice-merged local id
 * as well so that a half-fix reds there too.
 *
 * <p><b>Three of these were committed {@code @Disabled}, red for the honest reason: the orphan was
 * there.</b> Measured on {@code 2e01341}, the exported fold held {@code MISHEARD} carrying the
 * merged entity's label and no edges, the replayed graph held it too, and the {@code full} DOT drew
 * three nodes under one label for one entity. The fourth test below is green in both worlds and
 * stays enabled: it is what says the two folds hold the corrected merge rather than holding
 * nothing, so the absences above mean something.
 *
 * <p>Every entity here is invented (ADR 40, issue #37).
 */
class TwiceMergedIdLeavesNoOrphanTest {

  /** Minted, given one owner edge, merged onto the wrong item and then onto the right one. */
  private static FakeAssertionLog correctedLog() {
    return new FakeAssertionLog()
        .with(
            node(WREN, NodeKind.PERSON, "Wren Alderman"),
            minted(CORRECTED, NodeKind.WORK, "A Self-Pressed Record"),
            owned(CORRECTED, WREN, "INFLUENCED_BY"),
            merged(CORRECTED, MISHEARD),
            merged(CORRECTED, WATERMARK));
  }

  @Test
  @DisplayName("the exporter's fold holds no node for a canonical id a later merge corrected")
  void shouldHoldNoNodeForTheFirstCanonicalIdWhenALaterMergeCorrectedIt() {
    assertThat(LogProjection.of(correctedLog()).nodes())
        .as(
            "the edges went to the corrected id, so the first keeps a node with the merged"
                + " entity's label and nothing else - a correction's leftover, not a claim")
        .doesNotContainKey(MISHEARD);
  }

  @Test
  @DisplayName("the boot replay holds no node for a canonical id a later merge corrected")
  void shouldReplayNoNodeForTheFirstCanonicalIdWhenALaterMergeCorrectedIt() {
    try (TinkerGraphStore replayed = new TinkerGraphStore()) {
      GraphProjector.project(correctedLog(), replayed, IdentityMerge.NONE);

      assertThat(replayed.node(MISHEARD))
          .as(
              "the replay builds the stand-in twice - once from Equivalences.standIns before the"
                  + " loop and once from IngestService.standIn at the merge's own row - so fixing"
                  + " the first alone leaves the two folds holding different graphs")
          .isEmpty();
    }
  }

  @Test
  @DisplayName("a full export draws no node for a canonical id a later merge corrected")
  void shouldDrawNoNodeForTheFirstCanonicalIdWhenALaterMergeCorrectedIt() throws IOException {
    FakeAssertionLog log = correctedLog();
    try (TinkerGraphStore graph = new TinkerGraphStore()) {
      GraphProjector.project(log, graph, IdentityMerge.NONE);
      StringWriter out = new StringWriter();

      new DotWriter().write(new ViewSelector(graph, log).full(), out);

      assertThat(out.toString())
          .as(
              "asserted on the artefact somebody keeps and opens in Gephi: one entity drew THREE"
                  + " nodes under one label before #221, and only two of them were claimed")
          .doesNotContain("\"" + MISHEARD + "\"");
    }
  }

  @Test
  @DisplayName("the corrected canonical id keeps the label and every edge when a merge is redone")
  void shouldKeepTheLabelAndTheEdgesOnTheCorrectedCanonicalIdWhenAMergeIsRedone() {
    LogProjection folded = LogProjection.of(correctedLog());

    // Two folds holding nothing would satisfy the three absences above. This is what says the
    // correction landed: the last canonical id is the one with the node and the edge on it, and
    // the local id keeps its own node (ADR 59), drawn as the orphan #178's ruling 3 made it.
    assertThat(folded.nodes()).containsKeys(WATERMARK, CORRECTED, WREN);
    assertThat(folded.nodes().get(WATERMARK).label()).isEqualTo("A Self-Pressed Record");
    assertThat(folded.edges().stream().map(TwiceMergedIdLeavesNoOrphanTest::key))
        .containsExactly(WATERMARK + " INFLUENCED_BY " + WREN);
    assertThat(folded.danglingEdges())
        .as("retiring a stand-in must not leave an edge pointing at a node the fold never made")
        .isZero();
  }

  private static String key(EdgeRecord edge) {
    return edge.fromQid() + " " + edge.typeCode() + " " + edge.toQid();
  }
}
```

- [ ] **Step 3 — RED, three of them.** Run, blocking:

```
./gradlew test --tests 'com.robsartin.segue.export.TwiceMergedIdLeavesNoOrphanTest'
```

  Expect `4 tests completed, 3 failed`. The first should read close to `Expecting actual: … not to contain key: "Q10000900109"`; the second `Expecting Optional to be empty but was Optional[NodeRecord[qid=Q10000900109, kind=WORK, label=A Self-Pressed Record, instanceOf=[]]]`; the third that the DOT contains `"Q10000900109"`. **Quote all three verbatim in the report.** The fourth must pass — if it does not, the fixture is wrong, not the code.

- [ ] **Step 4 — park the three.** Add `import org.junit.jupiter.api.Disabled;` and annotate the three failing methods:

```java
  @Disabled("red until #221 retires the superseded stand-in — see this class's javadoc")
```

  Re-run: `4 tests completed`, 3 skipped, 0 failed.

- [ ] **Step 5 — commit.** `./gradlew spotlessApply`, then the full gate blocking. `git status`, then `git add src/test/java/com/robsartin/segue/export/InventedGraph.java src/test/java/com/robsartin/segue/export/TwiceMergedIdLeavesNoOrphanTest.java` (stderr visible), then commit: `#221: the reproducing test for a twice-merged local id, parked red`.

---

### Task 2: `Equivalences.stands`, and the claim tool stops offering a corrected canonical id

**Files:** Modify `src/main/java/com/robsartin/segue/domain/Equivalences.java`, `src/main/java/com/robsartin/segue/own/OwnRun.java`, `src/test/java/com/robsartin/segue/own/OwnRunTest.java`.

The predicate lands with its first caller, so nothing unused is committed. `OwnRun` goes first of the four homes for the reason in the spec: fix the folds while the tool still offers the corrected-away id, and an owner edge claimed against it fails replay at the next boot on a row ADR 19 forbids deleting.

- [ ] **Step 1 — RED.** Add to `OwnRunTest`, after `shouldAcceptTheCanonicalIdOfAMergeAsAnEndpointWhenNoSourceHasClaimedIt`:

```java
  @Test
  @DisplayName("should refuse the canonical id of a merge when a later merge corrected it")
  void shouldRefuseTheCanonicalIdOfAMergeWhenALaterMergeCorrectedIt() {
    // The stand-in the first merge named is retired by the second (#221), so this id has no node
    // in the projection any more. Offering it would be worse than unhelpful: the owner would claim
    // an edge against an endpoint no fold gives a node, and TinkerGraphStore.record refuses one it
    // has never seen - a replay failure at the next boot, on a row ADR 19 forbids deleting.
    seedASourcedEntity(SOURCED, "Ines Marlow");
    String minted = mintOne("A Self-Pressed Record");
    run.run(merge(minted, false), notes::add);
    run.run(new OwnCli.Merge(UNUSED, minted, OTHER_CANONICAL, false), notes::add);
    notes.clear();

    assertThatThrownBy(() -> run.run(claim(SOURCED, CANONICAL, false), notes::add))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(CANONICAL);
  }

  @Test
  @DisplayName("should still offer the canonical id of the merge that stands after a correction")
  void shouldStillOfferTheCanonicalIdOfTheMergeThatStandsAfterACorrection() {
    seedASourcedEntity(SOURCED, "Ines Marlow");
    String minted = mintOne("A Self-Pressed Record");
    run.run(merge(minted, false), notes::add);
    run.run(new OwnCli.Merge(UNUSED, minted, OTHER_CANONICAL, false), notes::add);
    notes.clear();

    LoggedAssertion appended = run.run(claim(SOURCED, OTHER_CANONICAL, false), notes::add);

    assertThat(appended).isEqualTo(new OwnerEdge(SOURCED, OTHER_CANONICAL, "INFLUENCED_BY", NOW));
    assertThat(notes)
        .as("the correction is the merge that stands, so its id is the one that has the label")
        .anyMatch(note -> note.contains("A Self-Pressed Record"));
  }
```

  Run `./gradlew test --tests 'com.robsartin.segue.own.OwnRunTest'`. The first must fail with exactly `java.lang.AssertionError: Expecting code to raise a throwable.` — nothing is thrown, because the tool accepts the corrected-away id today; the second must already pass. **Quote both outcomes.** (Both were run against `2e01341` while this plan was written, and that is the message they gave.)

- [ ] **Step 2 — GREEN, the predicate.** In `Equivalences`, immediately above `private String canonical(String qid)`:

```java
  /**
   * Whether these equivalences still point at this merge's canonical id — {@link
   * #canonicalByLocal}'s last-wins rule, asked about one row (#221).
   *
   * <p><b>The stand-in rule needs it because a second merge of one local id is a correction.</b>
   * {@link #standIns} named a stand-in for every surviving merge, so a local id merged onto one
   * canonical id and then onto another left a node under the first carrying the merged entity's
   * label and no edges — the edges having folded onto the last, which is the id this map keeps.
   * Nothing claimed that node and nothing named it. ADR 59's amendment of 2026-09-03 records the
   * decision to stop making it.
   *
   * <p><b>Equivalences that have never heard of the local id do not contradict the merge, so the
   * answer is true.</b> That is not a convenience for the empty case: {@code IngestService.record}
   * applies a claim with {@link #NONE}, because it sees one claim and not a log, and a {@code
   * SameAs} arriving there must go on getting its canonical node or the running graph is left with
   * an endpoint it has never heard of — which is the whole job of {@code IngestService.standIn}. A
   * caller holding the log gets the last-wins answer; a caller holding no log gets the merge it was
   * handed.
   *
   * <p>A merge a retraction reaches is absent from this map and would also answer true. No caller
   * is affected: every home of the stand-in rule asks {@link Retractions#survives} first, and
   * {@link #localsOfMerges} does it for both folds.
   */
  public boolean stands(SameAs merge) {
    Objects.requireNonNull(merge, "merge");
    String canonical = canonicalByLocal.get(merge.localQid());
    return canonical == null || canonical.equals(merge.canonicalQid());
  }
```

- [ ] **Step 3 — GREEN, the caller.** In `OwnRun.labelsInTheProjection`, widen the merge branch's condition:

```java
      } else if (assertion instanceof SameAs merge
          && merges.stands(merge)
          && !labels.containsKey(merge.canonicalQid())) {
```

  and extend that branch's existing comment with one sentence:

```java
        // A merge the owner has since corrected lends no label (#221): the second merge retires
        // the stand-in the first named, so that id has no node to offer as an endpoint.
```

  Re-run the fast loop: `OwnRunTest` green.

- [ ] **Step 4 — positive control.** Temporarily drop `&& merges.stands(merge)` from `OwnRun`, run `./gradlew test --tests 'com.robsartin.segue.own.OwnRunTest'`, and confirm `shouldRefuseTheCanonicalIdOfAMergeWhenALaterMergeCorrectedIt` reds with the same message as Step 1. Quote it; restore the guard; re-run green.

- [ ] **Step 5 — commit.** `spotlessApply`, full gate blocking, `git status`, `git add` the three paths by name, commit: `#221: a merge the owner corrected lends no label to the claim tool`.

---

### Task 3: the ratings listing stops naming a node that is not there

**Files:** Modify `src/main/java/com/robsartin/segue/ratings/Labels.java`, `src/test/java/com/robsartin/segue/ratings/InventedRatings.java`, `src/test/java/com/robsartin/segue/ratings/RatingsRunTest.java`.

`Labels.forQids` is the fourth home. Its javadoc states the invariant it exists for: a canonical row must not read `(not in the graph)` while the node is in the graph. After Task 4 the node is not in the graph, so this guard keeps that sentence true rather than inverting it.

- [ ] **Step 1 — an invented id.** Add to `InventedRatings`, after `CANONICAL`:

```java
  /**
   * A second canonical id, for a merge the owner corrected by merging the same local id again
   * (#221). ADR 62's shape, for the reason {@link #CANONICAL} takes it.
   */
  static final String CORRECTED_CANONICAL = "Q10000900043";
```

- [ ] **Step 2 — RED.** Add to `RatingsRunTest`, after `shouldNameTheCanonicalIdWhenAMergeHasCarriedTheRatingOntoIt`:

```java
  @Test
  @DisplayName("should say a canonical id is not in the graph when a later merge corrected it")
  void shouldSayACanonicalIdIsNotInTheGraphWhenALaterMergeCorrectedIt() throws IOException {
    // A rating an earlier build carried onto the wrong canonical id stays - AffinityStore has no
    // delete (ADR 39, ADR 46) - and since #221 that id has no node. "(not in the graph)" is what
    // that string is for: a rating that outlived its node. Naming it with the merged entity's
    // label would be this listing insisting on a node both folds have stopped making.
    FakeAffinityStore ratings = new FakeAffinityStore().rated(CANONICAL, 5, null, LATE);
    FakeAssertionLog log =
        new FakeAssertionLog()
            .with(
                minted(MINTED, MINTED_LABEL),
                merged(MINTED, CANONICAL),
                merged(MINTED, CORRECTED_CANONICAL));

    assertThat(run(ratings, log, SortOrder.RATING))
        .extracting(AffinityRow::qid, AffinityRow::displayLabel)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(CANONICAL, AffinityRow.NO_LABEL));
  }
```

  Add `import static com.robsartin.segue.ratings.InventedRatings.CORRECTED_CANONICAL;` in the file's existing static-import block. Run `./gradlew test --tests 'com.robsartin.segue.ratings.RatingsRunTest'`. Measured red on `2e01341`:

```
Expecting actual:
  [("Q10000900042", "A Book No Source Knows")]
to contain exactly (and in same order):
  [("Q10000900042", "(not in the graph)")]
```

  **Quote it.**

- [ ] **Step 3 — GREEN.** In `Labels.forQids`, add the import `com.robsartin.segue.domain.Equivalences;` (alphabetical, before `LocalEntity`), build the merges beside `wanted`:

```java
    Equivalences merges = Equivalences.in(logged);
    Set<String> wanted = new HashSet<>(qids);
```

  and guard the merge branch in the main loop:

```java
      if (assertion instanceof SameAs merge && merges.stands(merge)) {
```

  Extend that branch's comment with one sentence: `A merge a later one corrected lends nothing (#221): both folds have retired the node it stood in for, so a label here would deny the "(not in the graph)" the row honestly deserves.` Re-run: green.

- [ ] **Step 4 — positive control.** Drop `&& merges.stands(merge)`, re-run, confirm the new test reds with the Step 2 message. Quote; restore; green.

- [ ] **Step 5 — commit.** `spotlessApply`, full gate blocking, `git status`, `git add` the three paths by name, commit: `#221: a corrected merge carries no label into the ratings listing`.

---

### Task 4: both folds retire the superseded stand-in, and the carry with it

**Files:** Modify `src/main/java/com/robsartin/segue/domain/Equivalences.java`, `src/main/java/com/robsartin/segue/ingest/IngestService.java`, `src/test/java/com/robsartin/segue/domain/EquivalencesTest.java`, `src/test/java/com/robsartin/segue/ingest/MergeCarriesEverythingTest.java`, `src/test/java/com/robsartin/segue/export/TwiceMergedIdLeavesNoOrphanTest.java`.

**Both fold halves land in ONE commit, deliberately.** Measured on `2e01341`: fixing `Equivalences.standIns` alone leaves the boot replay building the same node a second time through `IngestService.standIn`, and the whole suite stays green while the two folds hold different graphs. Committing that state would be committing the exact defect family this issue belongs to, so the two red loops below share one commit.

- [ ] **Step 1 — RED, the exporter's half.** Add to `EquivalencesTest`, after `shouldLetTheFirstMergeOntoACanonicalIdNameTheStandIn`:

```java
  @Test
  @DisplayName("a merge a later one corrected names no stand-in, so nothing is left under it")
  void shouldNameNoStandInWhenALaterMergeCorrectedTheCanonicalId() {
    List<LoggedAssertion> log =
        List.of(
            LocalEntity.minted(MINTED, NodeKind.WORK, "The Salt Almanac", WHEN),
            SameAs.declared(MINTED, CANONICAL, WHEN),
            SameAs.declared(MINTED, OTHER_CANONICAL, WHEN));

    assertThat(Equivalences.standIns(log))
        .as(
            "the edges fold onto the last canonical id, so a stand-in under the first is a node"
                + " with the merged entity's label and no edges that nothing ever claimed")
        .containsExactly(
            Map.entry(
                OTHER_CANONICAL,
                new NodeRecord(OTHER_CANONICAL, NodeKind.WORK, "The Salt Almanac", List.of())));
  }

  @Test
  @DisplayName("the same merge declared twice still names its stand-in from the first of them")
  void shouldStillNameTheStandInWhenTheSameMergeWasDeclaredTwice() {
    // stands() compares canonical ids, not log positions, so re-declaring one merge changes
    // nothing - the idempotence IdentityMerge already claims for the rating half. The label is
    // the one the entity had at the FIRST of the two, which is putIfAbsent's answer unchanged.
    List<LoggedAssertion> log =
        List.of(
            LocalEntity.minted(MINTED, NodeKind.WORK, "The Salt Almanac", WHEN),
            SameAs.declared(MINTED, CANONICAL, WHEN),
            LocalEntity.minted(MINTED, NodeKind.WORK, "a name it was given later", WHEN),
            SameAs.declared(MINTED, CANONICAL, WHEN));

    assertThat(Equivalences.standIns(log))
        .containsExactly(
            Map.entry(
                CANONICAL,
                new NodeRecord(CANONICAL, NodeKind.WORK, "The Salt Almanac", List.of())));
  }
```

  Run `./gradlew test --tests 'com.robsartin.segue.domain.EquivalencesTest'`. Measured red on `2e01341`:

```
Actual and expected should have same size but actual size is: 2 while expected size is: 1
Actual was:
  {"Q10000000900"=NodeRecord[qid=Q10000000900, kind=WORK, label=The Salt Almanac, instanceOf=[]],
   "Q10000000901"=NodeRecord[qid=Q10000000901, kind=WORK, label=The Salt Almanac, instanceOf=[]]}
```

  The second test must already pass — it pins behaviour the fix must not change. **Quote both.**

- [ ] **Step 2 — GREEN, the exporter's half.** In `Equivalences.standIns`, build the merges and guard the loop:

```java
  public static Map<String, NodeRecord> standIns(List<LoggedAssertion> log) {
    Equivalences merges = Equivalences.in(log);
    Map<String, NodeRecord> standIns = new LinkedHashMap<>();
    for (Map.Entry<Integer, NodeRecord> at : localsOfMerges(log).entrySet()) {
      if (log.get(at.getKey()) instanceof SameAs merge && merges.stands(merge)) {
```

  and add a paragraph to `standIns`'s javadoc, after the "Log order, in both senses" one:

```java
   * <p><b>A merge a later merge corrected names nothing</b> (#221). {@link #stands} is the rule and
   * it is {@link #canonicalByLocal}'s own: the last merge of a local id wins, for the edges through
   * {@link #foldEndpoints} and now for the node as well, so the first canonical id is not left
   * holding a labelled node with no edges that nothing claimed. Two local ids merged onto ONE
   * canonical id are untouched by it — that is the {@code putIfAbsent} above, and a different
   * question.
```

  Re-run: `EquivalencesTest` green. **Do not commit yet** — see the task note.

- [ ] **Step 3 — RED, the replay's half.** Add to `MergeCarriesEverythingTest`, after `shouldStandInWithTheOwnersLabelWhereNoSourceHasNamedTheCanonicalEntity`, with the constant it needs beside `NEIGHBOUR`:

```java
  /** A third allocatable id: the canonical side a second merge corrects the first onto (#221). */
  private static final String CORRECTED_TO = "Q10000000902";
```

```java
  @Test
  @DisplayName("should replay no stand-in for a canonical id a later merge corrected")
  void shouldReplayNoStandInForACanonicalIdALaterMergeCorrected() {
    ingest.record(LocalEntity.minted(MINTED, NodeKind.PERSON, "a minted person", NOW));
    ingest.record(SameAs.declared(MINTED, CANONICAL, NOW));
    ingest.record(SameAs.declared(MINTED, CORRECTED_TO, NOW));

    try (GraphStore rebuilt = new TinkerGraphStore()) {
      GraphProjector.project(log, rebuilt, IdentityMerge.NONE);

      assertThat(rebuilt.node(CANONICAL))
          .as(
              "the replay builds the stand-in a second time at the merge's own row, so retiring it"
                  + " in Equivalences.standIns alone leaves the two folds holding different graphs")
          .isEmpty();
      assertThat(rebuilt.node(CORRECTED_TO))
          .as("and the correction is what keeps its node")
          .isPresent();
    }
  }

  @Test
  @DisplayName("should carry no rating onto a canonical id a later merge corrected")
  void shouldCarryNoRatingOntoACanonicalIdALaterMergeCorrected() {
    // follow() runs per SameAs row, so before #221 a replay wrote the owner's rating onto BOTH
    // canonical ids at every boot - and by ADR 48 a high rating counts as something he has, so a
    // merge he corrected went on telling recommend he owns the item he corrected it away from.
    ingest.record(LocalEntity.minted(MINTED, NodeKind.PERSON, "a minted person", NOW));
    affinity.put(new AffinityRecord(MINTED, 5, null, NOW));
    ingest.record(SameAs.declared(MINTED, CANONICAL, NOW));
    ingest.record(SameAs.declared(MINTED, CORRECTED_TO, NOW));

    try (GraphStore rebuilt = new TinkerGraphStore()) {
      GraphProjector.project(log, rebuilt, IdentityMerge.carryingRatings(affinity));
    }

    assertThat(affinity.find(CORRECTED_TO).orElseThrow().rating())
        .as("the correction is the merge that stands, and it carries what the local id was rated")
        .isEqualTo(5);
  }
```

  Run `./gradlew test --tests 'com.robsartin.segue.ingest.MergeCarriesEverythingTest'`. Measured red on `2e01341` for the first: `Expecting an empty Optional but was containing value: NodeRecord[qid=Q10000000900, kind=PERSON, label=a minted person, instanceOf=[]]`. The second must already pass — `CORRECTED_TO` is carried today too, and it pins the half that must survive. **Quote both.**

  The assertion that the *superseded* id gains no rating cannot be made against these live stores: `ingest.record` applies with `Equivalences.NONE`, so the live carry writes it before replay ever runs. Say so in the report. The replay's own answer is asserted instead, with a recording port:

```java
  @Test
  @DisplayName("should ask nothing to follow a merge a later merge corrected, on every replay")
  void shouldAskNothingToFollowAMergeALaterMergeCorrected() {
    IngestService blind = new IngestService(log, graph, IdentityMerge.NONE);
    blind.record(LocalEntity.minted(MINTED, NodeKind.PERSON, "a minted person", NOW));
    blind.record(SameAs.declared(MINTED, CANONICAL, NOW));
    blind.record(SameAs.declared(MINTED, CORRECTED_TO, NOW));
    List<String> followed = new ArrayList<>();

    try (GraphStore rebuilt = new TinkerGraphStore()) {
      GraphProjector.project(log, rebuilt, (local, canonical) -> followed.add(canonical));
    }

    assertThat(followed)
        .as("a replay that carried the rating onto both would re-write the wrong one every boot")
        .containsExactly(CORRECTED_TO);
  }
```

  with `import java.util.ArrayList;` and `import java.util.List;`. Measured red on `2e01341`:

```
Expecting actual:
  ["Q10000000900", "Q10000000902"]
to contain exactly (and in same order):
  ["Q10000000902"]
```

  **Quote it.**

- [ ] **Step 4 — GREEN, the replay's half.** In `IngestService.apply`, make the `SameAs` arm return early:

```java
      case SameAs merge -> {
        if (!equivalences.stands(merge)) {
          // A merge a later one corrected (#221) does neither half: no stand-in, because the node
          // would be a labelled orphan under an id the owner corrected away from, and no carry,
          // because the rating belongs to the merge that stands. Equivalences.NONE - the live path,
          // which sees one claim and not a log - answers true, so record() is unchanged.
          return;
        }
        standIn(graph, merge);
        // The taste half, and it runs on replay too - see standIn()'s last paragraph and
        // IdentityMerge, which together say why that is a repair rather than a hazard.
        merges.follow(merge.localQid(), merge.canonicalQid());
      }
```

  Re-run both fast loops: `MergeCarriesEverythingTest` and `EquivalencesTest` green.

- [ ] **Step 5 — un-park Task 1's guards.** Remove the three `@Disabled` annotations and the now-unused `Disabled` import from `TwiceMergedIdLeavesNoOrphanTest`, and rewrite that javadoc paragraph in the past tense (`were committed @Disabled` → keep, it is already past tense; delete nothing else). Run `./gradlew test --tests 'com.robsartin.segue.export.TwiceMergedIdLeavesNoOrphanTest'`: **4 tests completed, 0 skipped, 0 failed.**

- [ ] **Step 6 — positive control, both halves.** One at a time: (a) drop `&& merges.stands(merge)` from `standIns` — expect `TwiceMergedIdLeavesNoOrphanTest`'s exporter test and `EquivalencesTest`'s new test red; (b) restore it and remove the early return from `IngestService` — expect the replay and DOT tests red and the exporter test green, which is the two-folds-disagree state the spec measured. Quote both; restore; green.

- [ ] **Step 7 — commit.** `spotlessApply`, full gate blocking. Confirm the gate reports **0 skipped** — a skip above zero would mean a guard shipped switched off. `git status`, `git add` the five paths by name, commit: `#221: a merge the owner corrected retires its stand-in and its carry`.

---

### Task 5: `BothFoldsAgreeTest` gains the twice-merged local id

**Files:** Modify `src/test/java/com/robsartin/segue/export/BothFoldsAgreeTest.java`.

**What this task can and cannot buy, measured before it was written.** With the twice-merged local id in the fixture and **no** fix at all, `BothFoldsAgreeTest` is still green — both folds built the orphan, so comparing them says nothing. That is the blind spot the issue names, and widening the fixture does **not** close it. What it buys is the other failure: a *half*-fix, which is the one an implementer is most likely to ship and the one the spec measured. Land only the `standIns` half and this test reds naming the id. That is the whole value of the task, and Step 3 is where it is earned — Step 2 has no red of its own and the plan does not pretend otherwise.

- [ ] **Step 1 — widen the fixture.** In `ownedLog()`, append after the last row:

```java
            minted(CORRECTED, NodeKind.WORK, "A Self-Pressed Record"),
            owned(CORRECTED, MARLOW, "INFLUENCED_BY"),
            merged(CORRECTED, MISHEARD),
            merged(CORRECTED, WATERMARK)
```

  Add `CORRECTED` and `MISHEARD` to `OWNED_QIDS` and to the static imports, and add a paragraph to `ownedLog`'s javadoc:

```java
   * <p><b>{@code CORRECTED} is merged twice</b> (#221) — onto {@code MISHEARD} and then onto {@code
   * WATERMARK}, which is how a wrong merge is corrected. Both folds must hold no node at all under
   * {@code MISHEARD}: the exporter's fold and the boot replay each built one, from {@code
   * Equivalences.standIns} and {@code IngestService.standIn} respectively, so a fix to either alone
   * leaves them holding different graphs and this is the test that says so.
```

  Note `WATERMARK` now takes two locals' merges (`LEDGER` and `CORRECTED`) and `putIfAbsent` keeps `LEDGER`'s label — that is ADR 59's first-wins rule and both folds must agree about it, which is the point.

- [ ] **Step 2 — run.** `./gradlew test --tests 'com.robsartin.segue.export.BothFoldsAgreeTest'` — green. No red of its own, by construction; see the note above.

- [ ] **Step 3 — positive control, and it is the whole reason for this task.** Remove the early return from `IngestService.apply`'s `SameAs` arm — the half-fix — run `BothFoldsAgreeTest`, and confirm `shouldHoldTheSameNodesWhenTheOwnerHasMintedAndMerged` reds. Measured while this plan was written:

```
[replayed graph holds Q10000900109, exported fold holds: [Q10000900106, Q10000900102,
 Q10000900107, Q10000900108, Q0900101, Q0900104, Q0900103, Q001, Q003, Q002, Q004, Q005, Q006]]
expected: false
 but was: true
```

  **Quote it**: before this task that plant was invisible here. Restore; green.

- [ ] **Step 4 — commit.** `spotlessApply`, full gate blocking, `git status`, `git add` the one path, commit: `#221: both folds are held to agreeing about a twice-merged local id`.

---

### Task 6: the developer guide and ADR 59's amendment

**Files:** Modify `docs/developer-guide.md`, `docs/adr/0059-owner-claims-as-a-third-layer.md`.

No unit-testable behaviour: verified by the full build gate, which runs the docs tests
(`DeveloperGuideOwnClaimExamplesTest` among them), and by reading the two paragraphs against the
code they describe. Say so out loud in the report — this is the honest exception, not test-after.

- [ ] **Step 1 — the guide.** In *"A merge is said, not done"*, replace the last two sentences of the second-merge paragraph. From:

```
One rule now answers for both halves — the edges land on the last canonical id
alone, where the copy used to leave one on each. What the first canonical id keeps is an orphan
stand-in node with the merged entity's label and no edges, which is a correction's leftover rather
than anything you claimed.
```

  to:

```
One rule now answers for both halves — the edges land on the last canonical id
alone, where the copy used to leave one on each. **The first canonical id keeps nothing**
([#221](https://github.com/robsartin/segue/issues/221)): a second merge retires the stand-in the
first named, so there is no labelled orphan under an id you corrected away from, and no rating is
carried onto it either. `Equivalences.stands` is that rule and all four homes of the stand-in
ask it. A rating an older build already carried onto such an id stays — there is no un-rate
(ADR 39) — and `listRatings` now shows it as `(not in the graph)`, which is what that line is for.
```

- [ ] **Step 2 — the ADR amendment**, appended as its own dated section at the very end of `docs/adr/0059-owner-claims-as-a-third-layer.md`, below the residuals list. Nothing above it is edited, including the residual bullet this closes.

```markdown
**Amendment (2026-09-03, issue #221): the last of the residuals above is closed — a local id merged
twice now leaves nothing under the first canonical id.**

Nothing above is withdrawn and no sentence above is edited, the residual bullet included: it is the
true account of the code between #178 and this issue, and it is what this amendment answers.

**What was there, measured on `2e01341`** on an invented log (ADR 40, ADR 51: no known-list behind
it) holding one minted entity with one owner edge, merged onto one canonical id and then onto
another. The exported fold and the boot replay each held a node under the **first** canonical id
carrying the merged entity's label and no edges; the `full` DOT drew three nodes under one label for
one entity, of which the owner had claimed two; and `IdentityMerge.follow` was called for **both**
merges on every replay, so `carryingRatings` wrote the owner's rating onto the id he had corrected
away from — which, by [ADR 48](0048-a-high-rating-counts-as-something-you-have.md), goes on telling
`recommend` he owns it.

**The rule, in one place.** `Equivalences.stands` answers whether the equivalences still point at a
merge's canonical id — the last-wins reading `Equivalences.canonicalByLocal` already had for the
edges, now asked about the node and the carry as well. All four homes of the stand-in rule ask it,
and that is what makes them agree about this case by construction rather than by inspection:
`Equivalences.standIns` skips the merge, `IngestService.apply`'s `SameAs` arm does neither half of
it, and `OwnRun.labelsInTheProjection` and `ratings/Labels.forQids` lend it no label. Equivalences
that have never heard of the local id answer **true**, which is what keeps `IngestService.record` —
applying one claim with `Equivalences.NONE`, having no log to read — creating its live stand-in
exactly as before.

**Fixing the exporter's fold alone would not have been a fix, and that is measured too.** With
`Equivalences.standIns` corrected and `IngestService` untouched, the boot replay went on building
the same node a second time at the merge's own row, the two folds held different graphs, and the
whole suite stayed green — `BothFoldsAgreeTest` could not see it because its fixture held no
twice-merged local id. It holds one now, and removing either half of the fix reds it naming the id.

**Rejected: name the orphan in the export rather than retire it.** Mark the node as a superseded
stand-in so a reader knows why it is there. Honest about the log, which holds both merges, and it
changes least about what is in the graph. **Lost on three counts.** A stand-in exists so that a
folded edge has an endpoint to land on, and a superseded merge folds no edge, so the node has no job
left — annotating it is more machinery for less truth. It states a fact about the owner's correction
history inside the artefact this ADR's consequences call a picture of the **world** graph, the one
that may be shared. And it costs a node attribute reaching `NodeRecord`, both writers and both
folds, against one predicate asked in five places — while leaving the taste half writing a rating
onto an id the owner corrected away from, which no annotation in the export reaches.

**Also rejected: refuse a second merge of one local id.** `OwnCli` says it, and it must go on saying
it: a second merge is how a wrong merge is corrected, and the only alternative left to the owner
would be a retraction that takes every other claim about the id with it.

**Residual, and it cannot be closed by any later change either.** A rating an earlier build already
carried onto a superseded canonical id **stays**: `carryingRatings` copies a score and never removes
one, and `AffinityStore` has no delete (ADR 39, ADR 46). What this amendment changes is that no
further boot re-writes it, and that `ratings/Labels` no longer supplies it a label — so it reads as
`(not in the graph)`, which is what that string was written for. Whether segue should offer any way
to disown such a row is a separate decision nobody has argued.
```

- [ ] **Step 3 — commit.** Full gate blocking, `git status`, `git add docs/developer-guide.md docs/adr/0059-owner-claims-as-a-third-layer.md`, commit: `#221: the guide and ADR 59's amendment close the twice-merged residual`.

---

### Task 7: rebase, reconcile with #220, and the final gate

**Files:** none by default — see Step 2.

- [ ] **Step 1 — rebase.** `git fetch origin`, then `git rebase origin/main`. Expect a possible textual conflict inside `Equivalences.standIns` with **#222** (which edits the `NodeRecord` construction in the same loop): keep both — #222's kind expression inside the body, this branch's `&& merges.stands(merge)` on the `if`.

- [ ] **Step 2 — #220's guard, if it has landed.** `git log origin/main --oneline | grep -i 220` and look for the four-homes drift guard. Its fixture includes a local merged twice and pins **today's** answer for it. If present, update that case: every one of the four homes must now answer "no stand-in under the first canonical id", and after this change they agree by construction because all four ask `Equivalences.stands`. Adjust the guard's expectation, not its mechanism, and say in the commit message that #221 inverted that one case. Commit separately: `#221: reconcile #220's four-homes guard with the corrected-merge rule`.

- [ ] **Step 3 — the gate, blocking, from a clean tree.** `SEGUE_REQUIRE_BROWSER=true SEGUE_REQUIRE_GRAPHVIZ=true ./gradlew check --rerun-tasks`. Confirm **BUILD SUCCESSFUL** and **0 skipped**.

- [ ] **Step 4 — report.** State: the quoted red for each of the seven behaviours, the quoted output of each positive control, that the docs task's verification was the build gate rather than a unit test, whether #220 had landed and what was changed if so, and the final gate line.
