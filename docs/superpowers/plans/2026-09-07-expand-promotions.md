# A dev tool expands the neighbourhood of every promotion — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** close issue #284 — move the expansion out of `SegueService` into one class both callers use,
and add a tenth dev-side tool, `./gradlew expandPromotions`, that expands every entity the owner rated
at or above `KnownList.PROMOTION_RATING`, one at a time, honouring each adapter's own rate limit,
writing only through `IngestService`, and printing one block of aggregates safe to paste. No bound and
no constant moves. The owner runs it; the implementer never does.

**Architecture:** A new package `expansion` holding `EntityExpansion` (the body of
`SegueService.expandEntity`, moved), `ExpansionOutcome` (its sealed result), `ExpansionSources` (the
two-adapter wiring, moved out of `SegueConfiguration`) and `WikidataMusicBrainzIdentity` (moved out of
`app`, because a plain-Java tool cannot reach `app`). A new dev-tool package `expand` holding
`ExpandCli`, `ExpandRun`, `Preflight`, `ExpansionTally` and `ExpansionReport`. Six new ArchUnit rules
and two widenings. One new ADR (66), one dated amendment (ADR 54), one guide chapter.

**Tech Stack:** Java 25, Gradle (plain `./gradlew`), JUnit 5, AssertJ, Logback, TinkerGraph, SQLite,
ArchUnit.

**Spec:** `docs/superpowers/specs/2026-09-07-expand-promotions-design.md`

## Global Constraints

- **Pure TDD / red first.** Every behaviour: write the test, **run it, observe a real assertion
  failure**, then the minimum code. A compile error is never a red — where a step's first run would
  only fail to compile, the step says so and names the stub to add first so that the failure is an
  assertion. Quote the actual failure text in every report.
- **Every guard gets a positive control.** The safe-to-paste test, each new ArchUnit fence and each
  widened one are guards, not behaviours: their evidence is a **planted defect seen to fire**, then
  removed, then green. The plan writes each plant out. A guard nobody has watched go red is an inert
  fence — issues #139 and #140.
- Test names `should<Expected>When<Condition>` with `@DisplayName`.
- **Mikado**: the gate is green before every commit, and the coupled commits below say which edits
  must travel together. **Stage by explicit path, git stderr visible — never `git add -A`, never
  `2>/dev/null` on `git add`.** Read `git status` before every commit. Commits end
  `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`. Never cite a `.superpowers/` path from a
  committed file.
- Gate, **blocking, never backgrounded**:
  `SEGUE_REQUIRE_BROWSER=true SEGUE_REQUIRE_GRAPHVIZ=true ./gradlew check --rerun-tasks`. Run
  `./gradlew spotlessApply` before each gate. Fast loops are named per task.
- **Only JDK 25 is installed and Gradle launches on it.** Plain `./gradlew`; never
  `/usr/libexec/java_home -v 21` (it silently returns 25).
- **Never run a writing dev task** — `own`, `ownClaim`, `retractEntity`, `rate`, any seeding task, and
  **`expandPromotions` itself**. `~/.segue/segue.db` is never read, written, copied or created. Every
  database in this plan is a `@TempDir` file or an in-memory `TinkerGraphStore`.
- **No constant changes anywhere.** `ExpansionBounds.CONCEPT_CEILING`, `ExpandContext.defaults()`,
  `application.yaml`'s `segue.max-new-edges`, `SegueProperties`' fallback,
  `KnownList.PROMOTION_RATING`, `MusicBrainzClient.DEFAULT_MIN_REQUEST_INTERVAL`,
  `WikidataClient.MAX_BACKOFF` and every `Scorer` value are read, never edited.
- **No adapter, no recommender, no harness changes.** `WikidataMusicBrainzIdentity` **moves package**
  and is otherwise edited only in its javadoc.
- **The MCP surface does not move.** No tool is added, no wire type gains a field,
  `SegueService.expandEntity`'s signature, its three `error(…)` sentences, its reason strings and its
  `ok`/`partial` shaping are byte-for-byte what they are today. `ToolSurfaceTest` is untouched.
- **ADR 33's taste fences hold.** The new tool may call `AffinityStore.readRatings` and nothing else
  on the taste layer: never `find`, never `readAll`, never `AffinityRecord`, never `readUpdatedAt`.
- **No qid, label, note or rating ever reaches a line the new tool writes.** The one carve-out is
  `EntityExpansion`'s own logger; Task 11 states and tests it.
- **Invented ids only.** Every id in `src/test` here is in the `Q09006xx` (expansion) or `Q09007xx`
  (expand) family and carries ADR 58's leading zero, which Wikibase's grammar refuses — so
  `StandInQidsDenoteNothingTest` needs no `ALLOWED` entry. Every label and note in a fixture is made
  up (ADR 33, issue #37).
- **Javadoc is a gate** (`-Xdoclint:all,-missing -Werror`). Every `{@link}` must resolve; cite a test
  class as a `{@code}` span, never a link, and spell it exactly — `JavadocCitationsTest` resolves
  every `{@code Name.member}` span against `src/test`.
- **`ArchitectureTest` rules need a guide fences-table row** (`DeveloperGuideEnumerationsTest`), a new
  **package** needs a mermaid node, one mermaid edge per cross-package import and a package-table row,
  a new **dev tool** needs the line-380 sentence (list **and** the count word `ten`), and a new **ADR**
  needs its `docs/adr/README.md` row (`AdrIndexTest`). Each lands in the same commit as the thing it
  describes. `docs` is a declared test input, so a guide edit re-runs the tests that read it.
- **YAGNI**: no parameter, helper or accessor beyond what a step below actually uses.
- Machine is loaded: **no wall-clock assertions anywhere**, and no `Thread.sleep` in any new code.

---

### Task 1: `ExpansionOutcome` — the shared result

**Files:** create `src/main/java/com/robsartin/segue/expansion/ExpansionOutcome.java`,
`src/test/java/com/robsartin/segue/expansion/ExpansionOutcomeTest.java`. Edit
`docs/developer-guide.md`.

This task creates the `expansion` package, so the guide's mermaid diagram and package table move with
it. `ExpansionOutcome` imports nothing outside `java.*`, so it adds no mermaid edge yet.

- [ ] **Step 1 — write the failing test in full.** Create `ExpansionOutcomeTest.java`:

```java
package com.robsartin.segue.expansion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The two derived flags and the defensive copies, over invented ids. Nothing here comes from
 * anybody's graph (ADR 33, issue #37), and every id carries ADR 58's leading zero.
 */
class ExpansionOutcomeTest {

  private static final String SEED = "Q0900601";

  private static ExpansionOutcome.Expanded expanded(
      List<String> unavailable, List<String> truncating, boolean boundCut) {
    return new ExpansionOutcome.Expanded(
        SEED, 0, 0, 0, 10, unavailable, truncating, boundCut, List.of(), Map.of());
  }

  @Test
  @DisplayName("a source that could not be reached makes the result say a source was unavailable")
  void shouldReportSourceUnavailableWhenASourceIsNamedUnreachable() {
    assertThat(expanded(List.of("musicbrainz"), List.of(), false).sourceUnavailable()).isTrue();
    assertThat(expanded(List.of(), List.of(), false).sourceUnavailable()).isFalse();
  }

  @Test
  @DisplayName("a source that cut its own result makes the result say it was truncated")
  void shouldReportTruncatedWhenASourceIsNamedTruncating() {
    assertThat(expanded(List.of(), List.of("wikidata"), false).truncated()).isTrue();
  }

  @Test
  @DisplayName("the shared budget cutting the concatenation truncates the result, naming nobody")
  void shouldReportTruncatedWhenOnlyTheSharedBudgetCutTheConcatenation() {
    ExpansionOutcome.Expanded outcome = expanded(List.of(), List.of(), true);

    assertThat(outcome.truncated())
        .as("ADR 56: the bound applied to the concatenation is a truncation no adapter made")
        .isTrue();
    assertThat(outcome.truncatingSources()).isEmpty();
  }

  @Test
  @DisplayName("nothing is truncated or unavailable when every source answered in full")
  void shouldReportNeitherFlagWhenEverySourceAnsweredInFull() {
    ExpansionOutcome.Expanded outcome = expanded(List.of(), List.of(), false);

    assertThat(outcome.truncated()).isFalse();
    assertThat(outcome.sourceUnavailable()).isFalse();
  }

  @Test
  @DisplayName("the lists and the map are copied, so a caller's later edit cannot reach the result")
  void shouldCopyEveryCollectionWhenTheOutcomeIsBuilt() {
    List<String> mutable = new ArrayList<>(List.of("wikidata"));
    Map<String, Integer> counts = new LinkedHashMap<>(Map.of("wikidata", 3));

    ExpansionOutcome.Expanded outcome =
        new ExpansionOutcome.Expanded(
            SEED, 0, 0, 0, 10, List.of(), mutable, false, List.of(), counts);
    mutable.add("musicbrainz");
    counts.put("musicbrainz", 9);

    assertThat(outcome.truncatingSources()).containsExactly("wikidata");
    assertThat(outcome.edgesBySource()).containsExactly(Map.entry("wikidata", 3));
    assertThatThrownBy(() -> outcome.truncatingSources().add("jena"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  @DisplayName("a refusal carries the entity and the reason, and no sentence for either caller")
  void shouldCarryTheReasonWhenAnExpansionIsRefused() {
    ExpansionOutcome.Refused refused =
        new ExpansionOutcome.Refused(SEED, ExpansionOutcome.Reason.LOCAL_ENTITY);

    assertThat(refused.qid()).isEqualTo(SEED);
    assertThat(refused.reason()).isEqualTo(ExpansionOutcome.Reason.LOCAL_ENTITY);
  }
}
```

- [ ] **Step 2 — make the failure an assertion, not a compile error.** Create
  `ExpansionOutcome.java` as a shape that compiles and answers **wrongly**: both derived methods
  `return false;`, and the compact constructor copies nothing. Then run the fast loop and **quote the
  assertion failure**:

```bash
./gradlew test --tests 'com.robsartin.segue.expansion.ExpansionOutcomeTest'
```

Expect `shouldReportSourceUnavailableWhenASourceIsNamedUnreachable` to fail with
`Expecting value to be true but was false`, and
`shouldCopyEveryCollectionWhenTheOutcomeIsBuilt` to fail with
`Expecting actual: ["wikidata", "musicbrainz"] to contain exactly: ["wikidata"]`. Report both, quoted.

- [ ] **Step 3 — write the real file.**

```java
package com.robsartin.segue.expansion;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * What one expansion did, or why it was refused before any adapter ran.
 *
 * <p><b>Facts, not sentences.</b> Two callers read this and each says it in its own words: {@code
 * SegueService} builds the {@code ToolResult} a language model reads (ADR 27), and the promotion
 * expander counts. A shared sentence would be a wire string with two audiences, one of them a
 * model.
 *
 * <p><b>{@link Expanded#truncated()} and {@link Expanded#sourceUnavailable()} are derived here and
 * nowhere else.</b> ADR 56's rule is that the flags stay aggregate — ORed across adapters, because
 * "is this result complete?" has one answer however many sources ran — while attribution lives in
 * prose. Deriving them from the two lists is what stops that ORing being spelled out at each
 * caller, which is how two readers of one property drift apart.
 */
public sealed interface ExpansionOutcome {

  /** The entity this outcome is about. */
  String qid();

  /** Why an expansion was refused before any adapter ran. */
  enum Reason {
    /** The graph holds no node for it — it has to be added before it can be expanded. */
    UNKNOWN_ENTITY,
    /** The owner minted it, so no source has it and none ever will (ADR 58, ADR 59). */
    LOCAL_ENTITY,
    /** The caller asked for a bound of zero or less. */
    BOUND_NOT_POSITIVE
  }

  /** Refused before any adapter ran. Carries no sentence: see the interface's javadoc. */
  record Refused(String qid, Reason reason) implements ExpansionOutcome {

    public Refused {
      Objects.requireNonNull(qid, "qid");
      Objects.requireNonNull(reason, "reason");
    }
  }

  /**
   * An expansion that ran, whether or not every source answered in full.
   *
   * @param nodesAdded entities newly recorded, counted once each; an existing node whose identity
   *     this call refreshed (issue #55) is not among them
   * @param edgesAdded assertions recorded, per assertion rather than per pair of nodes
   * @param skippedNeighbors distinct entities this call could not identify
   * @param effectiveMax the bound actually applied, after {@code ExpansionBounds.effective}
   * @param unavailableSources {@code SourceAdapter.id()} of each source that could not be reached,
   *     in the order the adapters ran
   * @param truncatingSources {@code SourceAdapter.id()} of each source that cut its own result
   * @param boundCutTheConcatenation the shared budget cut the adapters' combined result, which is
   *     attributable to no single adapter because they were all handed one {@code ExpandContext}
   * @param refusedEndpoints endpoints the graph holds no node for, by endpoint rather than by
   *     assertion, insertion-ordered so a reason string built from them is stable (#233)
   * @param edgesBySource how many recorded assertions each source's provenance claimed. Edges only:
   *     a node's identity comes either from an adapter's own neighbours or from {@code
   *     EntityResolver.fetch}, and the second has no adapter behind it, so attributing a node would
   *     invent the per-adapter authority ADR 56 declined
   */
  record Expanded(
      String qid,
      int nodesAdded,
      int edgesAdded,
      int skippedNeighbors,
      int effectiveMax,
      List<String> unavailableSources,
      List<String> truncatingSources,
      boolean boundCutTheConcatenation,
      List<String> refusedEndpoints,
      Map<String, Integer> edgesBySource)
      implements ExpansionOutcome {

    public Expanded {
      Objects.requireNonNull(qid, "qid");
      unavailableSources = List.copyOf(Objects.requireNonNull(unavailableSources, "unavailable"));
      truncatingSources = List.copyOf(Objects.requireNonNull(truncatingSources, "truncating"));
      refusedEndpoints = List.copyOf(Objects.requireNonNull(refusedEndpoints, "refusedEndpoints"));
      // LinkedHashMap and not Map.copyOf: iteration order is what the report renders, and
      // Map.copyOf's is unspecified and salted per JVM.
      edgesBySource =
          Map.copyOf(new LinkedHashMap<>(Objects.requireNonNull(edgesBySource, "edgesBySource")));
    }

    /** At least one source could not be reached at all. Aggregate on purpose (ADR 56). */
    public boolean sourceUnavailable() {
      return !unavailableSources.isEmpty();
    }

    /** An adapter or the shared bound cut the result short. Aggregate for the same reason. */
    public boolean truncated() {
      return !truncatingSources.isEmpty() || boundCutTheConcatenation;
    }
  }
}
```

- [ ] **Step 4 — run it and observe it pass.** Same fast loop. Quote the green line.

- [ ] **Step 5 — the guide follows the tree.** `DeveloperGuideEnumerationsTest` now demands an
  `expansion` node in the mermaid diagram and a package-table row. Add the node beside `mcp`, and the
  row:

```
| `expansion` | One expansion: the source adapters, the bounds of ADR 49, the refusals of ADR 55 and ADR 59, and the partial-result facts both callers report in their own words. Reached by `mcp` and by `expand`, and by nothing else — `onlyTheClientAndTheExpanderExpandAnEntity`. | `port`, `domain`, `ingest`, `wikidata`, `musicbrainz` |
```

The "Depends on" column is prose and is not derivation-checked; it is written for the package as it
will be at the end of Task 4. Run the guide tests:

```bash
./gradlew test --tests 'com.robsartin.segue.arch.DeveloperGuideEnumerationsTest'
```

- [ ] **Step 6 — gate and commit.** `./gradlew spotlessApply`, then the full gate, blocking. Then
  `git status`, then stage by explicit path:

```bash
git add src/main/java/com/robsartin/segue/expansion/ExpansionOutcome.java \
        src/test/java/com/robsartin/segue/expansion/ExpansionOutcomeTest.java \
        docs/developer-guide.md
git commit
```

Message: `Add the shared expansion's result type (#284)`.

---

### Task 2: `EntityExpansion` — the extraction, behind the existing tests

**Files:** create `src/main/java/com/robsartin/segue/expansion/EntityExpansion.java`,
`src/test/java/com/robsartin/segue/expansion/EntityExpansionTest.java`. Edit
`src/main/java/com/robsartin/segue/mcp/SegueService.java`, `docs/developer-guide.md`.

**This is the Mikado step.** `SegueServiceTest`'s twenty-odd `expandEntity` call sites, `GraphToolsTest`,
`AnExpansionAfterARetractionTest`, `SharedAwardRouteTest`, `CorroborationAcrossSourcesTest`,
`MusicBrainzNeighbourIdentityTest` and `NeighbourFetchCountTest` are the characterisation harness and
they must pass **unedited**. Exactly two files under `src/test` change in this task: the new
`EntityExpansionTest.java`, and **one addition** to `SegueServiceTest` — the characterisation test for
the one sentence nothing pins today (Step 4), written and seen green **before** the body moves. The
task report has to state that `git show --stat` for this commit shows no other test file touched, and
that the addition was green both before and after the move.

- [ ] **Step 1 — write the failing test in full.** Create `EntityExpansionTest.java`. It drives the
  new class directly with an in-memory graph and stub adapters — the same shapes `SegueServiceTest`
  uses, one package over:

```java
package com.robsartin.segue.expansion;

import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.segue.domain.AssertionRecord;
import com.robsartin.segue.domain.EdgeTypes;
import com.robsartin.segue.domain.NodeAssertion;
import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.domain.Provenance;
import com.robsartin.segue.ingest.IngestService;
import com.robsartin.segue.port.AssertionLog;
import com.robsartin.segue.port.EntityResolver;
import com.robsartin.segue.port.ExpandContext;
import com.robsartin.segue.port.ExpandResult;
import com.robsartin.segue.port.GraphStore;
import com.robsartin.segue.port.IdentityMerge;
import com.robsartin.segue.port.SourceAdapter;
import com.robsartin.segue.port.SourceAdapters;
import com.robsartin.segue.sqlite.SqliteAssertionLog;
import com.robsartin.segue.tinker.TinkerGraphStore;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The expansion's own contract, driven without a {@code ToolResult} in sight. Every id is invented
 * and carries ADR 58's leading zero; nothing here comes from anybody's graph (ADR 33, issue #37).
 */
class EntityExpansionTest {

  private static final String SEED = "Q0900601";
  private static final String NEIGHBOUR = "Q0900602";
  private static final String MINTED = "Q00900603";

  @TempDir private Path dir;

  @Test
  @DisplayName("an entity the graph holds no node for is refused before any adapter runs")
  void shouldRefuseAsUnknownWhenTheGraphHoldsNoNodeForTheEntity() {
    …
    assertThat(expansion.expand(SEED, 10))
        .isEqualTo(new ExpansionOutcome.Refused(SEED, ExpansionOutcome.Reason.UNKNOWN_ENTITY));
  }

  @Test
  @DisplayName("an entity the owner minted is refused, because no source can ever have it")
  void shouldRefuseAsLocalWhenTheOwnerMintedTheEntity() { … Reason.LOCAL_ENTITY … }

  @Test
  @DisplayName("a bound of zero or less is refused before the ceiling is even applied")
  void shouldRefuseTheBoundWhenItIsNotPositive() { … Reason.BOUND_NOT_POSITIVE … }

  @Test
  @DisplayName("what an adapter returns is recorded, and counted as edges and new nodes")
  void shouldRecordWhatTheAdaptersReturnWhenTheSeedIsInTheGraph() { … }

  @Test
  @DisplayName("a source that could not be reached is named on the outcome, not thrown")
  void shouldNameTheSourceWhenAnAdapterCouldNotReachIt() { … }

  @Test
  @DisplayName("a CONCEPT seed is bounded below whatever the caller asked for")
  void shouldApplyTheCeilingWhenTheSeedIsAConcept() {
    … assertThat(expanded.effectiveMax()).isEqualTo(ExpansionBounds.CONCEPT_CEILING); …
  }
}
```

Write every case out in full when implementing — the elisions above are for this document only, and
each one is an assertion on a field of `ExpansionOutcome`, never on a rendered string. The stub
adapter is a local `record StubAdapter(String id, ExpandResult result) implements SourceAdapter`
whose `supports` answers true; the resolver is a local stub returning a `NodeAssertion` for
`NEIGHBOUR`. The graph is a `TinkerGraphStore`, the log a `SqliteAssertionLog` on
`dir.resolve("scratch.db")`, and the `IngestService` is built with `IdentityMerge.NONE`.

- [ ] **Step 2 — make the failure an assertion, not a compile error.** Create `EntityExpansion.java`
  as a stub that compiles and answers wrongly:

```java
public ExpansionOutcome expand(String qid, int maxNewEdges) {
  Objects.requireNonNull(qid, "qid");
  return new ExpansionOutcome.Refused(qid, ExpansionOutcome.Reason.BOUND_NOT_POSITIVE);
}
```

Run and **quote the failure**:

```bash
./gradlew test --tests 'com.robsartin.segue.expansion.EntityExpansionTest'
```

Expect `shouldRefuseAsUnknownWhenTheGraphHoldsNoNodeForTheEntity` to fail with
`expected: Refused[qid=Q0900601, reason=UNKNOWN_ENTITY] but was: Refused[qid=Q0900601, reason=BOUND_NOT_POSITIVE]`.

- [ ] **Step 3 — move the body.** Cut `SegueService.expandEntity`'s body and `neighborOf` into
  `EntityExpansion`, unchanged except for what it returns:

  - the three early refusals become `Refused` with the matching `Reason`, in **today's order** —
    unknown entity, then local entity, then the bound;
  - the trailing block that built `ExpansionSummary`, the reasons and the `ok`/`partial` result is
    **deleted from here** and returns `new ExpansionOutcome.Expanded(qid, nodesAdded, edgesAdded,
    skippedNeighbors, effectiveMax, unavailableSources, truncatingSources,
    boundCutTheConcatenation, List.copyOf(refusedEndpoints), Map.of())` — `edgesBySource` is empty
    until Task 3;
  - the `log.warn("expandEntity({}) neighbour {} unavailable: …")` and
    `log.warn("expandEntity({}) refused an edge: …")` calls **move with the body, message text
    unchanged**. The final `log.warn("expandEntity({}) partial: {}", qid, reasons)` stays with
    `SegueService`, because `reasons` is built there.
  - the whole `expandEntity` javadoc moves to `EntityExpansion.expand`, with the paragraphs that
    describe `ExpansionSummary`, `ToolResult` and `detail` rewritten to name `ExpansionOutcome`'s
    fields instead. Every `{@link}` must still resolve from the new package, and
    `JavadocCitationsTest` must still resolve every `{@code Name.member}` span — check the ones
    naming `CorroborationAcrossSourcesTest` and `MusicBrainzNeighbourIdentityTest`.

- [ ] **Step 4 — `SegueService` delegates, in this same commit.** Add the field, build it in the
  constructor, and rewrite the method:

```java
  private final EntityExpansion expansion;

  public SegueService(
      EntityResolver resolver,
      GraphStore graph,
      IngestService ingest,
      SourceAdapters adapters,
      AffinityStore affinity,
      Clock clock) {
    …
    // Built here rather than injected: every collaborator it needs is already a field, and a
    // seventh constructor parameter would move thirty-odd call sites for nothing. #284.
    this.expansion = new EntityExpansion(this.resolver, this.graph, this.ingest, this.adapters);
  }

  public ToolResult<ExpansionSummary> expandEntity(String qid, int maxNewEdges) {
    Objects.requireNonNull(qid, "qid");
    return switch (expansion.expand(qid, maxNewEdges)) {
      case ExpansionOutcome.Refused refused -> error(refusalSentence(refused));
      case ExpansionOutcome.Expanded expanded -> shape(expanded);
    };
  }

  private static String refusalSentence(ExpansionOutcome.Refused refused) {
    return switch (refused.reason()) {
      case UNKNOWN_ENTITY -> "unknown entity: " + refused.qid() + " — add it before expanding";
      case LOCAL_ENTITY ->
          "local entity: "
              + refused.qid()
              + " — no source to expand from, because the owner minted it";
      // The bound is not on the outcome: it never was in the message either, which reads
      // "maxNewEdges must be positive, got N" from the argument the caller passed. That
      // argument is not in scope here, so the sentence keeps the shape it has by taking the
      // reason and the value the caller still holds — see expandEntity's overload below.
      case BOUND_NOT_POSITIVE -> "maxNewEdges must be positive";
    };
  }
```

**Read this carefully before writing it.** Derived by grep over `src/test`, not assumed: **no test
anywhere pins the non-positive-bound sentence** — `SegueServiceTest` never calls `expandEntity` with
a bound at or below zero, and `grep -rn "positive" src/test/java/com/robsartin/segue/mcp/` finds only
two unrelated comments. So the string is unpinned today, which is exactly why it is the one most
likely to drift in this extraction. `Refused` carries no number, so `expandEntity` keeps the
requested bound in scope and appends `", got " + maxNewEdges` for that one reason — and **Task 2 adds
the test that has been missing**, as its own red:

```java
  @Test
  @DisplayName("a bound of zero or less is refused with the sentence naming what was asked for")
  void shouldNameTheBoundWhenItIsNotPositive() {
    assertThat(service.expandEntity("Q01", 0).detail())
        .isEqualTo("maxNewEdges must be positive, got 0");
  }
```

Write that test **before** the extraction moves the refusal, watch it pass against today's code
(it is a characterisation test, so it is green from the start and its evidence is that it stays green
across the move), and say so in the report. Confirm `ToolResult`'s accessor name and the `error`
result's shape before writing the assertion.

`shape` builds the `ExpansionSummary` and the reason list exactly as the deleted code did, in the same
order — unavailable, truncating, bound-cut, skipped neighbours, refused endpoints — and returns
`ok` when the list is empty and `partial(withCorrelation(String.join("; ", reasons)), summary)`
otherwise, after the `log.warn`.

- [ ] **Step 5 — run the whole harness and observe it pass, unedited.**

```bash
./gradlew test --tests 'com.robsartin.segue.mcp.*' \
               --tests 'com.robsartin.segue.expansion.*' \
               --tests 'com.robsartin.segue.musicbrainz.*'
```

Quote the green line and the test count. **If any of those tests needs editing to pass, the
extraction changed behaviour and the step is wrong** — find the difference rather than editing the
test.

- [ ] **Step 6 — the guide's edges.** `expansion` now imports `domain`, `port`, `ingest` and
  `wikidata`, and `mcp` imports `expansion`. Add exactly those mermaid edges; remove none.

- [ ] **Step 7 — gate and commit.** `spotlessApply`, full gate blocking, `git status`, then:

```bash
git add src/main/java/com/robsartin/segue/expansion/EntityExpansion.java \
        src/main/java/com/robsartin/segue/mcp/SegueService.java \
        src/test/java/com/robsartin/segue/expansion/EntityExpansionTest.java \
        src/test/java/com/robsartin/segue/mcp/SegueServiceTest.java \
        docs/developer-guide.md
git commit
```

Message: `Move the expansion out of the tool layer (#284)`. The report states that the only existing
test file touched is `SegueServiceTest`, that the touch is one added characterisation test, and that
every other test in the harness passed unedited.

---

### Task 3: `edgesBySource` — a new behaviour, not part of the extraction

**Files:** edit `src/main/java/com/robsartin/segue/expansion/EntityExpansion.java`,
`src/test/java/com/robsartin/segue/expansion/EntityExpansionTest.java`.

- [ ] **Step 1 — write the failing test.** Add to `EntityExpansionTest`:

```java
  @Test
  @DisplayName("recorded edges are tallied by the source their provenance names")
  void shouldTallyEdgesBySourceWhenTwoAdaptersEachRecordSome() {
    // Two adapters, two assertions each, one of the four naming an endpoint the graph holds no
    // node for — so the tally counts what was RECORDED and not what was returned.
    …
    ExpansionOutcome.Expanded expanded = (ExpansionOutcome.Expanded) expansion.expand(SEED, 10);

    assertThat(expanded.edgesBySource())
        .as("recorded assertions, keyed by Provenance.sourceId")
        .containsExactly(Map.entry("wikidata", 2), Map.entry("musicbrainz", 1));
    assertThat(expanded.edgesAdded())
        .as("the tally sums to edgesAdded, or one of the two numbers is lying")
        .isEqualTo(3);
  }
```

- [ ] **Step 2 — run it and quote the real failure.** With `Map.of()` still returned, expect
  `Expecting actual: {} to contain exactly: [...]`.

- [ ] **Step 3 — the minimum code.** In the loop, after `edgesAdded++`:

```java
      edgesBySource.merge(assertion.provenance().sourceId(), 1, Integer::sum);
```

with `Map<String, Integer> edgesBySource = new LinkedHashMap<>();` declared beside `edgesAdded`, and
passed to the `Expanded` constructor. Insertion order is adapter order, which is the order the report
prints.

- [ ] **Step 4 — run it and observe it pass.** Quote the green line.

- [ ] **Step 5 — gate and commit.** Message: `Tally an expansion's edges by source (#284)`.

---

### Task 4: `ExpansionSources`, and the bridge leaves `app`

**Files:** create `src/main/java/com/robsartin/segue/expansion/ExpansionSources.java`,
`src/test/java/com/robsartin/segue/expansion/ExpansionSourcesTest.java`. Move
`src/main/java/com/robsartin/segue/app/WikidataMusicBrainzIdentity.java` →
`src/main/java/com/robsartin/segue/expansion/WikidataMusicBrainzIdentity.java`, and
`src/test/java/com/robsartin/segue/app/WikidataMusicBrainzIdentityTest.java` and
`WikidataMusicBrainzIdentityLiveTest.java` to `src/test/java/com/robsartin/segue/expansion/`. Edit
`src/main/java/com/robsartin/segue/app/SegueConfiguration.java`,
`src/test/java/com/robsartin/segue/arch/ArchitectureTest.java` (javadoc only),
`docs/developer-guide.md`.

Use `git mv` so the moves are visible as moves.

- [ ] **Step 1 — write the failing test in full.** Create `ExpansionSourcesTest.java`:

```java
package com.robsartin.segue.expansion;

import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.segue.port.SourceAdapters;
import com.robsartin.segue.wikidata.WikidataClient;
import com.robsartin.segue.wikidata.WikidataEntityResolver;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The wiring both entry points share. It constructs clients and asks nothing of them, so this test
 * opens no socket — see {@code ExpansionSources.both}'s javadoc for why order is the whole point.
 */
class ExpansionSourcesTest {

  @Test
  @DisplayName("Wikidata is asked first and MusicBrainz second, because one bound is shared")
  void shouldAskWikidataFirstWhenBothSourcesAreWired() {
    SourceAdapters adapters =
        ExpansionSources.both(
            new WikidataEntityResolver(new WikidataClient(), Clock.systemUTC()),
            Clock.systemUTC());

    assertThat(adapters.all().stream().map(a -> a.id()).toList())
        .as(
            "the order is load-bearing: SegueService bounds the concatenation, so a tight bound is"
                + " spent by whichever adapter runs first")
        .isEqualTo(List.of("wikidata", "musicbrainz"));
  }
}
```

- [ ] **Step 2 — make the failure an assertion, not a compile error.** Create `ExpansionSources.java`
  returning `new SourceAdapters(List.of())`. Run and **quote**:

```bash
./gradlew test --tests 'com.robsartin.segue.expansion.ExpansionSourcesTest'
```

Expect `expected: ["wikidata", "musicbrainz"] but was: []`.

- [ ] **Step 3 — move the bridge.** `git mv` the three files, change their `package` and any import,
  and **rewrite the "Why it lives in `app`" paragraph** to say why it lives in `expansion` now: it
  has to see both `MusicBrainzIdentity` and `WikidataClient`, neither adapter package may see the
  other (`adaptersDoNotDependOnEachOther`, all twenty ordered pairs since #140), and the two callers
  that need it are the MCP server and a plain-Java dev tool that may not depend on `app`. Cite ADR 66
  and ADR 54's 2026-09-07 amendment. Check `WikidataMusicBrainzIdentityTest`'s comment naming
  `application.yaml`'s `max-new-edges` — leave the comment's claim alone, only fix the package.

- [ ] **Step 4 — write `ExpansionSources` for real**, moving the body of
  `SegueConfiguration.sourceAdapters` (and its whole javadoc, which is the argument) into it:

```java
public final class ExpansionSources {

  private ExpansionSources() {}

  public static SourceAdapters both(WikidataEntityResolver resolver, Clock clock) {
    Objects.requireNonNull(resolver, "resolver");
    Objects.requireNonNull(clock, "clock");
    WikidataClient queryService = WikidataClient.queryService();
    return new SourceAdapters(
        List.of(
            new WikidataSourceAdapter(resolver, queryService, clock),
            new MusicBrainzSourceAdapter(
                new MusicBrainzClient(), new WikidataMusicBrainzIdentity(queryService), clock)));
  }
}
```

`SegueConfiguration.sourceAdapters` becomes `return ExpansionSources.both(resolver, clock);` and
loses its `musicbrainz` and `WikidataMusicBrainzIdentity` imports. **One client per run is the
property that makes `MusicBrainzClient`'s slot reservation apply across a whole batch** — say so in
the javadoc, because a caller that built one per entity would silently unthrottle the tool.

- [ ] **Step 5 — run it and observe it pass**, then run `SegueConfigurationTest`, `MergeWiringTest`
  and the whole `app` and `musicbrainz` packages. Quote the green line.

- [ ] **Step 6 — correct the gloss that has just become false.**
  `ArchitectureTest.adaptersDoNotDependOnEachOther`'s javadoc ends "`app` is the only package ADR 32
  lets see two adapters at once." Rewrite it: `expansion` sees both, because it holds the bridge and
  the wiring two entry points share; ADR 32 is untouched, since its own text is about depending on
  *everything*, and it says "`ArchitectureTest` is the list, not this table." Javadoc only — the rule
  does not change.

- [ ] **Step 7 — the guide's edges.** `expansion` now imports `musicbrainz`; `app` no longer imports
  `musicbrainz`; `app` imports `expansion`. Adjust the mermaid edges to exactly what
  `DeveloperGuideEnumerationsTest` derives, and run it.

- [ ] **Step 8 — gate and commit.** `spotlessApply`, full gate blocking, `git status`, stage every
  moved and edited path explicitly, commit. Message:
  `Move the MBID bridge and the source wiring into expansion (#284)`.

---

### Task 5: the fence that keeps the expansion to two callers

**Files:** edit `src/test/java/com/robsartin/segue/arch/ArchitectureTest.java`,
`docs/developer-guide.md`.

- [ ] **Step 1 — write the rule.**

```java
  /**
   * #284: an expansion has two callers, and everything else in this project is fenced not to write.
   *
   * <p><b>This is the rule that lets the expansion leave {@code mcp} at all.</b> {@link
   * EntityExpansion} runs every adapter and appends what they return through {@link IngestService},
   * so a package that can reach it can turn its own read-only fence into a bulk write and a network
   * connection at once. The census, the exporter, the harness, the recommender, the ratings tool,
   * the rating deck, the seed tool and both claim tools are each fenced against exactly that, and
   * none of those fences would have caught this: they name {@code ingest}, {@code java.net} and
   * sibling packages, and a class in a package none of them has heard of is outside all of them.
   * That is ADR 54's finding restated — "a new adapter package inherits none of them: nothing fails
   * to compile, no test goes red".
   *
   * <p>{@code app} is permitted because wiring is its job (ADR 32) and it wires {@code
   * ExpansionSources}. {@code mcp} and {@code expand} are the two callers the decision names.
   */
  @ArchTest
  static final ArchRule onlyTheClientAndTheExpanderExpandAnEntity =
      noClasses()
          .that()
          .resideOutsideOfPackages("..mcp..", "..expand..", "..app..", "..expansion..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("..expansion..")
          .because(
              "#284: one expansion, two callers — the MCP tool and the promotion expander. Anything"
                  + " else reaching it would gain a bulk write and a network connection past its"
                  + " own fence");
```

- [ ] **Step 2 — plant the defect and watch it fire.** Add to
  `src/main/java/com/robsartin/segue/census/Census.java`:

```java
  private static final com.robsartin.segue.expansion.EntityExpansion PLANT = null;
```

Run:

```bash
./gradlew test --tests 'com.robsartin.segue.arch.ArchitectureTest'
```

**Quote the violation line**, which must name `Census` and `EntityExpansion`. Then **remove the
plant** and run again, green. A plant that only appears in a javadoc `{@code …}` span proves nothing —
javadoc leaves no bytecode edge, which #179 measured.

- [ ] **Step 3 — the fences table row.**

```
| `onlyTheClientAndTheExpanderExpandAnEntity` | any package but `mcp`, `expand`, `app` and `expansion` itself depending on `expansion` — the class that runs every adapter and appends what they return is a bulk write and a network connection in one object, and every other package's fence was written before it existed | [ADR 66](adr/0066-expand-every-promotion-from-a-dev-tool.md) |
```

The ADR link resolves only once Task 13 lands, and `DocumentationLinksTest` follows every link in
`docs/**/*.md`. **So this row's link is added in Task 13, not here** — write the row now with the
rule name and the prose, and the ADR citation in the third column as plain text `ADR 66`, then turn
it into a link in Task 13. Say so in the row's own commit message.

- [ ] **Step 4 — gate and commit.** Message: `Fence the shared expansion to its two callers (#284)`.

---

### Task 6: `ExpandCli.parse` — the command line, without a `main`

**Files:** create `src/main/java/com/robsartin/segue/expand/ExpandCli.java`,
`src/test/java/com/robsartin/segue/expand/ExpandCliTest.java`. Edit `docs/developer-guide.md`.

**No `public static void main` yet.** `PackageListsTest` keys on a `*Cli` class *declaring* one, so
adding it here would demand the Gradle task, `DEV_TOOL_PACKAGES` and every fence in the same breath.
Task 10 is where those travel together.

- [ ] **Step 1 — write the failing test in full.** `ExpandCliTest` covers, one `@Test` each:

  - `shouldRefuseWhenNoDatabaseIsNamed` — `parse(new String[] {}, null, home)` throws
    `IllegalArgumentException` whose message contains `--db is required` and the resolved default
    path, and **creates nothing under the test's own home** (assert `Files.notExists`);
  - `shouldRefuseWhenOnlySegueDbNamesTheDatabase` — same, with `envDatabase` set, and the message
    quotes that path back and still refuses;
  - `shouldRefuseWhenTheDatabaseIsNamedTwice` — `--db a --db b` throws `was given twice`;
  - `shouldDefaultToTheSharedBoundWhenNoMaxNewEdgesIsGiven` —
    `assertThat(parse(…).maxNewEdges()).isEqualTo(ExpandContext.defaults().maxNewEdges())`;
  - `shouldHonourTheBoundWhenOneIsGiven` — `--max-new-edges 25`;
  - `shouldRefuseTheBoundWhenItIsNotPositive` — `--max-new-edges 0` throws
    `--max-new-edges must be positive`;
  - `shouldRefuseTheBoundWhenItIsNotANumber` — `--max-new-edges lots`;
  - `shouldNotBeADryRunWhenTheFlagIsAbsent` and `shouldBeADryRunWhenTheFlagIsGiven`;
  - `shouldRefuseAnUnknownOption`.

- [ ] **Step 2 — make the failure an assertion, not a compile error.** Create `ExpandCli` with
  `record Options(Path database, int maxNewEdges, boolean dryRun)` and a `parse` that returns
  `new Options(Path.of("stub"), 1, false)` unconditionally. Run and **quote** the failure —
  `shouldRefuseWhenNoDatabaseIsNamed` fails with
  `Expecting code to raise a throwable` (nothing was thrown).

- [ ] **Step 3 — the real parser.** `EvaluateCli.parse`'s shape: a `for` loop reading `--dry-run` as a
  bare flag and everything else through `valueOf(args, i, flag)` then `i++`, a `LinkedHashMap` that
  refuses a repeated flag (`OwnCli`'s rule), `--db` removed from the map and refused through
  `RequiredDatabase.refusal(envDatabase, userHome)` when absent, `--max-new-edges` parsed by a
  private `number` helper, `default -> throw usage("unknown option " + flag)`, and a `usage` helper
  that appends a full stop and the `USAGE` constant. **`ExpandCli` never names `DefaultDatabase` and
  never takes a `Path` out of `support`** — Task 10's two fences hold that.

- [ ] **Step 4 — run it and observe it pass.** Quote the green line.

- [ ] **Step 5 — the guide's package table and diagram.** The `expand` package now exists, so it needs
  a mermaid node, its import edges (`domain`, `port`, `support`) and a package-table row. It is **not
  yet a dev tool** — it has no `main` and no Gradle task — so the line-380 sentence stays at nine and
  `ten`. Run `DeveloperGuideEnumerationsTest`.

- [ ] **Step 6 — gate and commit.** Message: `Parse the promotion expander's command line (#284)`.

---

### Task 7: `Preflight` and the dry run

**Files:** create `src/main/java/com/robsartin/segue/expand/Preflight.java`,
`src/main/java/com/robsartin/segue/expand/ExpandRun.java`,
`src/test/java/com/robsartin/segue/expand/ExpandRunTest.java`.

- [ ] **Step 1 — write the failing test in full.** In `ExpandRunTest`, with a `TinkerGraphStore` holding
  nodes for two of four invented promotions and a stub adapter that **fails the test if it is asked
  anything**:

```java
  @Test
  @DisplayName("a dry run counts what would be visited and asks no source anything")
  void shouldCountWithoutExpandingWhenTheRunIsADryRun() {
    List<String> promotions = List.of("Q0900701", "Q0900702", "Q00900703", "Q0900704");
    // Q0900701 and Q0900702 have nodes; Q00900703 is minted (two leading zeros, ADR 58);
    // Q0900704 is rated but the graph holds no node for it.
    …
    Preflight preflight = run.dryRun(promotions, lines::add);

    assertThat(preflight).isEqualTo(new Preflight(4, 2, 1));
    assertThat(neverAsked.get()).as("no adapter was called").isTrue();
    assertThat(lines).anyMatch(line -> line.contains("dry run"));
  }

  @Test
  @DisplayName("a dry run appends nothing to the log")
  void shouldAppendNothingWhenTheRunIsADryRun() {
    int before = log.readAll().size();
    run.dryRun(promotions, line -> {});
    assertThat(log.readAll()).hasSize(before);
  }
```

- [ ] **Step 2 — stub, then quote the failure.** `Preflight` as a record returning zeros from a stub
  `dryRun`. Expect `expected: Preflight[considered=4, inTheGraph=2, minted=1] but was:
  Preflight[considered=0, inTheGraph=0, minted=0]`.

- [ ] **Step 3 — the real code.** `ExpandRun(EntityExpansion expansion, GraphStore graph)`;
  `dryRun` counts `promotions.size()`, `graph.node(qid).isPresent()` and `LocalEntity.isLocal(qid)`,
  emits `ExpansionReport.dryRunLines(preflight)` — which does not exist yet, so for this task emit a
  single line and let Task 9 replace it with the report. **State that in the step**: the line here is
  `"dry run: nothing was written"`, and Task 9's RED is what moves it into the report.

- [ ] **Step 4 — run it and observe it pass.**

- [ ] **Step 5 — gate and commit.** Message: `Report what a promotion expansion would visit (#284)`.

---

### Task 8: the loop

**Files:** create `src/main/java/com/robsartin/segue/expand/ExpansionTally.java`. Edit
`src/main/java/com/robsartin/segue/expand/ExpandRun.java`,
`src/test/java/com/robsartin/segue/expand/ExpandRunTest.java`.

- [ ] **Step 1 — write the failing tests in full**, one behaviour each:

  - `shouldVisitEveryPromotionInQidOrderWhenTheRunIsNotADryRun` — a recording stub expansion
    (a seam: `ExpandRun` takes `EntityExpansion`, so the test builds a real one over stub adapters
    and asserts the recorded order, or drives the real path and asserts the tally) sees the
    promotions ascending;
  - `shouldTallyTheCountsWhenEveryExpansionSucceeds` — `nodesAdded`, `edgesAdded`,
    `edgesBySource` and `expanded` sum across entities;
  - `shouldCountAnExpansionThatAddedNothingWhenNeitherCountMoved` — `addedNothing` is 1 for an
    outcome with `nodesAdded == 0 && edgesAdded == 0`, and that entity is still counted in
    `expanded`;
  - `shouldTallyARefusalByItsReasonWhenAnEntityCannotBeExpanded` —
    `refusalsByReason` holds `LOCAL_ENTITY -> 1`;
  - `shouldTallyAShortfallToItsSourceWhenAnAdapterCouldNotBeReached` — `unavailableBySource`
    and `truncatedBySource` each take one increment per source id named, and
    `boundCutTheConcatenation` counts entities rather than sources;
  - `shouldCountTheFailureAndCarryOnWhenAnAdapterThrows` — a stub adapter throwing
    `IllegalStateException` on the second of three entities leaves `failed == 1`, `expanded == 2`,
    and the third entity **visited**;
  - `shouldNameNoEntityWhenAProgressLineIsWritten` — every line the run emits is checked against
    `Pattern.compile("\\bQ\\d+\\b")` and none matches.

- [ ] **Step 2 — stub, then quote the failure.** `ExpansionTally` with every component zero and an
  `ExpandRun.run` that returns it without looping. Expect
  `shouldVisitEveryPromotionInQidOrderWhenTheRunIsNotADryRun` to fail with
  `Expecting actual: [] to contain exactly: ["Q0900701", "Q0900702", "Q0900703"]`.

- [ ] **Step 3 — the real loop.**

```java
  public ExpansionTally run(List<String> promotions, int maxNewEdges, Consumer<String> lines) {
    …
    for (int i = 0; i < promotions.size(); i++) {
      String qid = promotions.get(i);
      ExpansionOutcome outcome;
      try {
        outcome = expansion.expand(qid, maxNewEdges);
      } catch (RuntimeException thrown) {
        // #284. One entity is not the run. SegueService.expandEntity wraps adapter.expand in no
        // try — right for one interactive call, where the MCP layer turns a throw into a protocol
        // error, and wrong for a batch that has already written most of what it came for. Named
        // without the qid: a line per entity naming the entity would enumerate the owner's
        // promotions down a terminal, which is the bulk read ADR 39 refused.
        failed++;
        log.warn("expansion {} of {} threw: {}", i + 1, promotions.size(), thrown.getMessage());
        lines.accept(progress(i, promotions.size(), "failed"));
        continue;
      }
      …
    }
    ExpansionReport.lines(tally).forEach(lines);
    return tally;
  }
```

`ExpansionTally` is a record of `int considered, expanded, addedNothing, failed, nodesAdded,
edgesAdded, skippedNeighbors, refusedEndpoints, boundCut`, plus
`Map<String, Integer> edgesBySource, unavailableBySource, truncatedBySource` and
`Map<ExpansionOutcome.Reason, Integer> refusalsByReason` — every map defensively copied into a
`LinkedHashMap` in the compact constructor, and **nothing in the signature able to carry an
identifier** (ADR 65's type-level fence). Accumulate into locals and build it once at the end.

The `ExpansionReport.lines` call does not compile until Task 9 — so in **this** task the run emits
nothing but progress lines and returns the tally, and Task 9's RED is a `ExpandRunTest` case asserting
the report's header reaches the consumer. Say so in the step.

- [ ] **Step 4 — run it and observe it pass.**

- [ ] **Step 5 — gate and commit.** Message: `Expand every promotion, one at a time (#284)`.

---

### Task 9: `ExpansionReport` — the two blocks

**Files:** create `src/main/java/com/robsartin/segue/expand/ExpansionReport.java`,
`src/test/java/com/robsartin/segue/expand/ExpansionReportTest.java`. Edit `ExpandRun.java` and
`ExpandRunTest.java`.

- [ ] **Step 1 — write the failing test in full**, as a golden block, `EvaluationReportTest`'s shape.
  The header is a **string literal here, not `ExpansionReport.HEADER`** — the point of the pin is to
  catch the header's own text moving, and reading it off the constant would let a reworded header
  carry the pin with it. `containsExactlyElementsOf` a `List<String>` written out in full for a tally
  of invented counts, plus:

  - `shouldRenderTheDryRunBlockWhenNothingWasWritten`;
  - `shouldAlignEveryColumnWhenTheCountsDifferInWidth` — the padding is arithmetic over the block's
    own widest label and widest count;
  - `shouldCarryNoIdentifierWhenTheBlockIsRendered` — no line matches `\bQ\d+\b`, for a tally whose
    source-keyed maps carry `"wikidata"` and `"musicbrainz"`;
  - `shouldOmitASectionWhenItHasNothingToSay` — decide and pin it: an empty `edges by source` map
    prints the heading with no rows rather than vanishing, so a reader can tell "no edges" from "the
    section is gone".

- [ ] **Step 2 — stub, then quote the failure.** `lines` returning `List.of(HEADER)`. Expect
  `Expecting actual: ["# segue promotion expansion — …"] to contain exactly …` naming the missing
  lines.

- [ ] **Step 3 — the real renderer**, and wire it into `ExpandRun.run` and `ExpandRun.dryRun`.

```java
  public static final String HEADER =
      "# segue promotion expansion — aggregates only: no labels, no notes, no entity ids"
          + " (ADR 51, ADR 63).";

  public static final String DRY_RUN_HEADER =
      "# segue promotion expansion — dry run: nothing was written. Aggregates only"
          + " (ADR 51, ADR 63).";
```

Sections and their order, which the golden block pins: `promotions`, `graph`, `edges by source`,
`shortfalls` (with `unavailable` and `truncated` as indented sub-headings, one row per source id),
`refused, by reason`. Labels padded to the block's widest, counts right-aligned to the block's
widest — `CensusReport`'s rule, so a six-figure count moves the column rather than jutting out.

- [ ] **Step 4 — run it and observe it pass**, plus `ExpandRunTest`'s new case asserting the header
  reaches the consumer.

- [ ] **Step 5 — gate and commit.** Message: `Render the promotion expansion's summary (#284)`.

---

### Task 10: register the tool — one coupled commit

**Files:** edit `src/main/java/com/robsartin/segue/expand/ExpandCli.java` (add `main` and `run`),
`build.gradle.kts`, `src/test/java/com/robsartin/segue/arch/ArchitectureTest.java`,
`src/test/java/com/robsartin/segue/expand/ExpandCliTest.java`, `docs/developer-guide.md`.

**These edits must travel together.** `PackageListsTest` asserts `DEV_TOOL_PACKAGES` equals the
Gradle-derived set *and* the `*Cli`-derived set, in both directions;
`DeveloperGuideEnumerationsTest` reads the tool sentence and the count word off the Gradle
derivation; `otherDevToolsAnd(List.of("expand"))` throws unless `"expand"` is in
`DEV_TOOL_PACKAGES`. Any one of them alone reds the build.

- [ ] **Step 1 — write the failing end-to-end test.** In `ExpandCliTest`:

```java
  @Test
  @DisplayName("a database that is not there is refused before anything is created")
  void shouldRefuseWhenTheDatabaseDoesNotExist() { … "no segue database at" … }

  @Test
  @DisplayName("a dry run over a real scratch database prints the block and writes nothing")
  void shouldPrintTheDryRunBlockWhenTheDatabaseHoldsRatedEntities() {
    // a @TempDir SqliteAssertionLog + SqliteAffinityStore carrying two invented nodes and two
    // ratings, one at PROMOTION_RATING and one below it
    ExpandCli.main(new String[] {"--db", db.toString(), "--dry-run"});
    …
  }
```

The refusal **order** is the behaviour: a missing `--db` is refused by `parse` before
`Files.exists` is reached, so the operator is told about a flag rather than a path they never typed.
Add `shouldRefuseTheMissingFlagBeforeTheMissingFileWhenNeitherIsGiven`.

- [ ] **Step 2 — run and quote the failure.** With no `main`, this is a compile error — so **add
  `main` and `run` first as a stub that parses and returns**, run, and quote the real assertion
  failure (`Expecting code to raise a throwable`).

- [ ] **Step 3 — the real `run`**, `EvaluateCli.run`'s shape exactly:

```java
  static void run(String[] args, String envDatabase, String userHome) {
    Options options = parse(args, envDatabase, userHome);

    if (!Files.exists(options.database())) {
      throw new IllegalArgumentException(
          "no segue database at " + options.database() + " — nothing to expand");
    }

    try (SqliteAssertionLog assertions = new SqliteAssertionLog(options.database());
        SqliteAffinityStore affinity = new SqliteAffinityStore(options.database());
        TinkerGraphStore graph = new TinkerGraphStore()) {
      Replay replay = GraphProjector.replay(assertions, graph, IdentityMerge.NONE);
      log.info("replayed {} assertion(s)", replay.applied());

      // The fold the replay already derived (#246, ADR 64) — never read back and folded again.
      Equivalences merges = replay.fold().equivalences();
      // Resolved before the threshold is applied: a merge leaves two affinity rows naming one
      // thing, and promoting both would expand the id the owner retired as well as the one he
      // kept. A count, never a qid and never a score (ADR 33).
      Map<String, Integer> ratings = merges.resolve(affinity.readRatings());
      log.info("read {} rating(s)", ratings.size());

      // KnownList.promoted with no file IS "rated at or above PROMOTION_RATING, ascending by
      // qid" — the threshold and the order from the class that owns both, rather than a second
      // copy of the rule here (issues #106 and #109).
      List<String> promotions = KnownList.promoted(List.of(), ratings);
      log.info("{} promotion(s) to visit", promotions.size());

      // IdentityMerge.NONE and not carryingRatings: an expansion never records a SameAs, so
      // there is nothing for a merge hook to carry — and carryingRatings writes the taste layer,
      // which theExpanderWritesThroughIngestAlone forbids outright.
      IngestService ingest = new IngestService(assertions, graph, IdentityMerge.NONE);
      EntityExpansion expansion =
          new EntityExpansion(
              new WikidataEntityResolver(new WikidataClient(), Clock.systemUTC()),
              graph,
              ingest,
              ExpansionSources.both(
                  new WikidataEntityResolver(new WikidataClient(), Clock.systemUTC()),
                  Clock.systemUTC()));
      ExpandRun run = new ExpandRun(expansion, graph);
      if (options.dryRun()) {
        run.dryRun(promotions, log::info);
      } else {
        run.run(promotions, options.maxNewEdges(), log::info);
      }
    }
  }
```

**Build one `WikidataEntityResolver` and pass it to both**, not two — the sketch above shows two and
that is a defect to fix while writing it: two resolvers means two `WikidataClient`s, and one client
per run is the property that keeps the pacing honest. Write it with one local `resolver` and one
local `clock`.

- [ ] **Step 4 — register the task** in `build.gradle.kts`, unindented, one literal `mainClass.set`,
  `outputs.upToDateWhen { false }`, `maxHeapSize = "4g"` (the whole graph is replayed),
  `jvmArgs("--enable-native-access=ALL-UNNAMED")`, and a description in `graphCensus`'s shape naming
  ADR 66, the required `--db`, `--dry-run`, `$HOME` and one `Example:` line:

```
Example: ./gradlew expandPromotions --args="--db \$HOME/.segue/segue.db --dry-run"
```

- [ ] **Step 5 — `DEV_TOOL_PACKAGES`** gains `"expand"`, in alphabetical position.

- [ ] **Step 6 — the five remaining fences.** Write each out with the javadoc the spec's table
  summarises:

```java
  @ArchTest
  static final ArchRule theExpanderWritesThroughIngestAlone =
      noClasses().that().resideInAPackage("..expand..")
          .should(ArchConditions.accessTargetWhere(
              APPLIES_A_CLAIM
                  .or(callTo("put", AffinityStore.class))
                  .or(callTo("updateRating", AffinityStore.class))))
          .because("#284: the expander appends through IngestService and writes nothing else — not"
              + " the graph directly, not the log directly, and never the taste layer it reads its"
              + " promotions from");

  @ArchTest
  static final ArchRule theExpanderReadsScoresAndNeverNotes =
      noClasses().that().resideInAPackage("..expand..")
          .should(ArchConditions.dependOnClassesThat(
                  JavaClass.Predicates.equivalentTo(AffinityRecord.class))
              .or(ArchConditions.accessTargetWhere(
                  callTo("find", AffinityStore.class)
                      .or(callTo("readAll", AffinityStore.class)))))
          .because("ADR 33 as amended by issue #85: the expander reads the note-free bulk map to"
              + " find the promotions, and the three reads that carry free text stay out");

  @ArchTest
  static final ArchRule theExpanderOpensNothingElse =
      noClasses().that().resideInAPackage("..expand..")
          .should().dependOnClassesThat()
          .resideInAnyPackage(otherDevToolsAnd(List.of("expand"), "..mcp..", "..app.."))
          .because("#284: the expander replays one log, runs the shipped expansion and appends what"
              + " it returns — it borrows no sibling's fence and cannot become an MCP tool by"
              + " accident. java.net is deliberately NOT banned: unlike every sibling, this tool"
              + " exists to fetch");

  @ArchTest
  static final ArchRule theExpanderHasNoDefaultDatabase = … DefaultDatabase … ;

  @ArchTest
  static final ArchRule theExpanderTakesItsDatabaseFromTheFlagAlone =
      noClasses().that().resideInAPackage("..expand..")
          .should(ArchConditions.accessTargetWhere(A_PATH_TAKEN_OUT_OF_SUPPORT))
          .because("ADR 60's measurement, a fifth time: a fence that forbids a class name stops only"
              + " the lazy version — what has to be unavailable is any route from support to a Path");
```

`theExpanderOpensNothingElse` deliberately bans neither `java.net` nor `..tinker..`, `..sqlite..`,
`..ingest..`, `..wikidata..` or `..musicbrainz..`, and its javadoc says why in full: this is the
first dev-side tool whose whole purpose is to reach a network, and the first that holds a running
`GraphStore` while writing — unlike `own` and `retract`, which hold none because they have no
projection to apply a claim to.

- [ ] **Step 7 — the two widenings.** Add `"..expand.."` to
  `onlyTheRecommenderReadsEveryRating`'s package list and to
  `theReplayingToolsTakeTheBootsFold`'s, and extend each javadoc with a paragraph saying why this is
  a widening rather than a new rule — the reasons are in the spec's fences section and must be
  written out, not gestured at.

- [ ] **Step 8 — plant every one of the seven, one at a time.** For each: add the plant, run
  `./gradlew test --tests 'com.robsartin.segue.arch.ArchitectureTest'`, **quote the violation line**,
  remove the plant, re-run green. The plants are:

  1. `theExpanderWritesThroughIngestAlone` — `graph.upsertNode(…)` in `ExpandRun`.
  2. `theExpanderReadsScoresAndNeverNotes` — an `AffinityRecord` field in `ExpandCli`.
  3. `theExpanderOpensNothingElse` — `import com.robsartin.segue.census.CensusRun;` used in a field.
  4. `theExpanderHasNoDefaultDatabase` — a real `DefaultDatabase.resolve(null, null, "x")` call in
     `ExpandCli` (**not** a javadoc mention: javadoc leaves no bytecode edge, which #179 measured).
  5. `theExpanderTakesItsDatabaseFromTheFlagAlone` — add
     `public static Path plant(String home) { return DefaultDatabase.resolve(null, null, home); }`
     to `RequiredDatabase` and call it from `ExpandCli`. **Check that rule 4 stays green under this
     plant** — that is ADR 60's measured gap, and seeing it again here is the evidence that the two
     rules are not one rule twice.
  6. `onlyTheRecommenderReadsEveryRating` — remove `"..expand.."` from its list and watch it fire on
     `ExpandCli`.
  7. `theReplayingToolsTakeTheBootsFold` — an `Equivalences.in(assertions.readAll())` call in
     `ExpandCli` beside the `replay.fold()` one.

- [ ] **Step 9 — the guide.** The line-380 sentence gains `` `expand` `` and becomes **ten**; add its
  bullet in the list beneath, naming what it reaches and that it is the first dev-side tool that
  writes *and* fetches. Add the six fences-table rows (ADR citation as plain text `ADR 66` for now,
  linked in Task 13) and edit the two widened rules' existing rows to name `expand`. Update the
  `expand` package-table row with the real dependency list.

- [ ] **Step 10 — gate and commit.** `spotlessApply`, full gate blocking, `git status`, stage every
  path explicitly. Message: `Register the promotion expander as the tenth dev tool (#284)`.

---

### Task 11: the safe-to-paste guard

**Files:** create `src/test/java/com/robsartin/segue/expand/ExpansionIsSafeToPasteTest.java`.

- [ ] **Step 1 — write the guard in full**, `CensusIsSafeToPasteTest`'s shape: a `ListAppender` on the
  root logger at `TRACE`, restored in `@AfterEach`; a `@TempDir` scratch database carrying an
  invented label, an invented note naming an invented qid, a rating at `PROMOTION_RATING` and a node
  for that entity; `captured.list.clear()`; a real `ExpandCli.main(new String[] {"--db", db, "--dry-run"})`.

The dry run is what the guard drives, because it reaches the report and the promotions without a
network. Add a second `@Test` driving the **non**-dry path over a database whose only promotion the
graph holds no node for, so the run reaches the refusal tally and the full block **without asking any
source anything** — an `UNKNOWN_ENTITY` refusal happens before an adapter runs.

Clauses, in order:

```java
    assertThat(everyLine)
        .as("the block was actually printed — without this the assertions below are vacuous")
        .contains(ExpansionReport.DRY_RUN_HEADER)
        .anyMatch(line -> line.startsWith("  considered"));
    assertThat(everyLine).as("no line carries a label (ADR 51, ADR 63, ADR 66)")
        .noneMatch(line -> line.contains(LABEL));
    assertThat(everyLine).as("no line carries a note (ADR 33, ADR 51)")
        .noneMatch(line -> line.contains(NOTE));
    assertThat(captured.list)
        .as("no line this tool writes carries anything qid-shaped, wherever it came from. The one"
            + " exception is a diagnostic from the shared expansion, which the MCP server emits"
            + " identically — see the class javadoc")
        .noneMatch(ExpansionIsSafeToPasteTest::carriesAnIdItMayNot);
```

with

```java
  private static final Pattern A_QID = Pattern.compile("\\bQ\\d+\\b");
  private static final String THE_SHARED_EXPANSION =
      "com.robsartin.segue.expansion.EntityExpansion";

  private static boolean carriesAnIdItMayNot(ILoggingEvent event) {
    return !THE_SHARED_EXPANSION.equals(event.getLoggerName())
        && A_QID.matcher(event.getFormattedMessage()).find();
  }
```

and a second `@Test` unit-testing the carve-out against planted events, the census's second test's
shape: a tool line carrying a qid fires; an `EntityExpansion` line carrying one does not; a line from
a logger whose name merely *contains* `EntityExpansion` as a substring of a longer name fires.

The class javadoc records, in ADR 51's and ADR 65's voice: what is safe to paste is the block and
every line this tool writes; the carve-out is one logger wide; the shared expansion's two warnings
name a neighbour and a refused endpoint and are emitted identically by the server; moving them out to
the callers would close the carve-out and is a separate change, named in ADR 66's consequences. It
also records that a leaked **rating** has no clause, for `EvaluationIsSafeToPasteTest`'s reason — a
bare digit is indistinguishable from a count the block legitimately prints — and that what keeps one
out is `ExpansionTally`'s type-level fence.

- [ ] **Step 2 — plant the leak and watch it fire.** In `ExpansionReport`, change the `promotions`
  section heading to `"promotions Q0900901"`. Run:

```bash
./gradlew test --tests 'com.robsartin.segue.expand.ExpansionIsSafeToPasteTest'
```

**Quote the failure**, which must be the fourth clause naming that line. Remove the plant, re-run
green. Record in the report that a plant in a `log.warn` inside `EntityExpansion` would **not** fire —
run that plant too, observe it green, and say so, because that is the carve-out's exact width.

- [ ] **Step 3 — gate and commit.** Message: `Prove the promotion expansion's block is safe to paste (#284)`.

---

### Task 12: the runbook

**Files:** edit `docs/developer-guide.md`, `docs/user-guide.md`. Create
`src/test/java/com/robsartin/segue/expand/DeveloperGuideExpandPromotionsExamplesTest.java`.

- [ ] **Step 1 — write the failing test in full.**

```java
  private static final String CHAPTER = "Expanding every promotion";

  @Test
  @DisplayName("every expandPromotions example in the guide parses through this tool's own parser")
  void shouldParseEveryExampleWhenTheGuideShowsTheTool() { … GuideExamples.of("expandPromotions") … }

  @Test
  @DisplayName("no example writes a tilde where $HOME belongs")
  void shouldWriteHomeRatherThanATildeWhenAnExampleNamesADatabase() { … withATilde() empty … }

  @Test
  @DisplayName("an example that cannot be read is named rather than skipped")
  void shouldNameTheLineWhenAnExampleCannotBeRead() { … unreadableExamples() empty … }

  @Test
  @DisplayName("the chapter shows the census, the dry run, the run and the census, in that order")
  void shouldRunEveryStepInOrderWhenTheChapterIsRead() {
    assertThat(steps())
        .containsExactly(
            "graphCensus", "expandPromotions --dry-run", "expandPromotions", "graphCensus");
  }
```

`steps()` merges `GuideExamples.inChapter(CHAPTER, "graphCensus")` and
`GuideExamples.inChapter(CHAPTER, "expandPromotions")`, sorts by guide line number, and reduces each
to its task name plus `" --dry-run"` where that flag is among its arguments —
`DeveloperGuideSupervisedRunExamplesTest`'s method, one chapter over.

- [ ] **Step 2 — run it and quote the failure.** With no chapter yet,
  `shouldRunEveryStepInOrderWhenTheChapterIsRead` fails with
  `Expecting actual: [] to contain exactly: ["graphCensus", "expandPromotions --dry-run", …]`, and
  the parse test fails on its own non-empty assertion.

- [ ] **Step 3 — write the chapter**, `## Expanding every promotion`, in `## A supervised first run`'s
  shape: a preamble that says the owner types every command and that an agent reading it is reading a
  description rather than a script (ADR 60); a sentence naming its own checks; a pointer to
  *Looking at the shape of your graph* rather than restating it. Then

```
### 0. Quit the client, and confirm nothing is holding the database
### 1. The census before
### 2. The dry run
### 3. The run
### 4. The census after
### 5. What should have moved, and what should not
### What to file from what you saw
```

Steps 1 and 4 carry the identical `graphCensus` line. Step 2 and step 3 carry
`./gradlew expandPromotions --args="--db $HOME/.segue/segue.db --dry-run"` and the same line without
the flag. Step 3's prose says what to expect: a progress line per promotion carrying a position and
counts and **no entity id**, a run measured in tens of minutes at a promotion count in the hundreds,
and the arithmetic behind that — one second of MusicBrainz's own pacing per `PERSON` or `GROUP`
promotion plus four Wikidata round trips and one more per neighbour no source described. Step 5 is
the `| line | direction | why |` table from the spec, **with no figures**, and the sentence that
`CensusReport` is the authority on the labels. Add the Contents entry.

- [ ] **Step 4 — correct the two sentences this issue falsifies.**
  `docs/user-guide.md:40` becomes "…call the live Wikidata API and the Wikidata Query Service. No
  other tool on this surface does." The supervised-run chapter's step 9 paragraph beginning "**There
  is no dev-side bridge tool, deliberately.**" is rewritten: MusicBrainz is now also reached by
  `./gradlew expandPromotions`, which runs the same expansion through the same adapters — the tool
  that does not exist is one that reaches MusicBrainz *differently*. **Prose only**: adding a
  `./gradlew … --args` line to that chapter would change what
  `DeveloperGuideSupervisedRunExamplesTest` asserts.

- [ ] **Step 5 — run it and observe it pass**, plus `DeveloperGuideSupervisedRunExamplesTest`,
  `DocumentationLinksTest` and `DeveloperGuideEnumerationsTest`.

- [ ] **Step 6 — gate and commit.** Message: `Write the runbook for expanding every promotion (#284)`.

---

### Task 13: ADR 66, the index row, and ADR 54's amendment

**Files:** create `docs/adr/0066-expand-every-promotion-from-a-dev-tool.md`. Edit
`docs/adr/README.md`, `docs/adr/0054-musicbrainz-as-the-second-source.md`,
`docs/developer-guide.md`.

- [ ] **Step 1 — write ADR 66**, in ADR 65's format: front matter (`status: Accepted`,
  `date: "2026-09-07"`, `topic: expand-every-promotion-from-a-dev-tool`, `tags`, `supersedes: []`,
  `related: [...]` naming ADR 19, 24, 26, 32, 33, 39, 40, 44, 49, 51, 54, 55, 56, 59, 60, 61, 63, 64,
  65 by topic slug), heading `# 66. …`, then `## Context`, `## Decision` with `###` subsections,
  `## Alternatives considered`, `## Consequences`.

  The decision sentence: **a tenth dev-side tool,
  `./gradlew expandPromotions --args="--db <segue.db>"`, expands every entity the owner rated at or
  above `KnownList.PROMOTION_RATING`, one at a time, through the same expansion the MCP tool runs, and
  prints one block of aggregates.** Subsections:

  - **One expansion, two callers** — the package, the class, and the fence that keeps it to two.
  - **Why the seed tool's "never writes" does not extend here** — ADR 40's argument is three claims
    stacked (`IngestService` is the only writer; `add_entity` owns adding; a fence makes it
    impossible), and **only the first survives the move**. This tool writes *through* `IngestService`,
    so ADR 19 is honoured rather than bent; it adds no entity nobody asked for, it expands entities
    the owner has already rated; and its fence is different because its job is different. What ADR 40
    was protecting — a committed tool that reads a private list must not be able to touch the
    database — is protected here by a different fence and by the guard on the output.
  - **What it costs** — the arithmetic from the spec, `P` named as the count `graphCensus` reports
    and no figure restated.
  - **It folds once** — ADR 64: one `GraphProjector.replay`, the fold taken back from `Replay`, held
    by `theReplayingToolsTakeTheBootsFold` widened to a fourth package.
  - **The output contract** — every value an integer or a literal; no qid, label or note on any line
    the tool writes; the carve-out, one logger wide, named as a limit.
  - **The seam that moved** — the bridge, and why `app` could not stay its home.

  Alternatives, each with the reason it lost, from the spec's list: a client loop; a flag on `seed`;
  expanding the whole known list; expanding through `expand_entity` from a script; recursion;
  `ingest` as the shared package; a per-adapter breakdown on `ExpansionSummary`; a qid per progress
  line; retries; parallelism.

  Consequences: the next evaluation reading is the measure, judged by #245's rule unchanged, and its
  denominator moves so it is not row-for-row comparable with the seven before it; the log grows with
  every run; the safe-to-paste carve-out is open and how to close it; the default bound is stated
  three times in this repository and this decision did not fix it; and the closing paragraph naming
  `AdrIndexTest`, `AdrCitationsTest`, `DocumentationLinksTest` and `javadoc -Werror` as what verifies
  the document.

- [ ] **Step 2 — the index row**, appended to the end of `## Uncategorized`, with the bracketed title
  **character-identical** to the `# 66. …` heading:

```
- [66. …](0066-expand-every-promotion-from-a-dev-tool.md) — _Accepted_
  <one-line description>
  Related: …
```

- [ ] **Step 3 — amend ADR 54**, dated, ADR 61's convention: a bold paragraph
  `**Amendment (2026-09-07, issue #284): the identity bridge moves out of `app`.**` saying that ADR
  54 placed `WikidataMusicBrainzIdentity` in `app` on ADR 32's "only package that may see two
  adapters" reading, that a second, Spring-free caller now needs it, that it lives in `expansion`
  with the wiring both callers share, that **nothing above is withdrawn and no decision above is
  edited**, and naming ADR 66. ADR 54 keeps `status: Accepted`.

- [ ] **Step 4 — turn the seven fences-table citations into links** to
  `adr/0066-expand-every-promotion-from-a-dev-tool.md`, now that the file exists.

- [ ] **Step 5 — run the document tests.**

```bash
./gradlew test --tests 'com.robsartin.segue.arch.AdrIndexTest' \
               --tests 'com.robsartin.segue.arch.AdrCitationsTest' \
               --tests 'com.robsartin.segue.arch.DocumentationLinksTest' \
               --tests 'com.robsartin.segue.arch.DeveloperGuideEnumerationsTest'
```

- [ ] **Step 6 — gate and commit.** Message: `Record the promotion expander as ADR 66 (#284)`.

---

### Task 14: hand the run back to the owner

**Files:** none. This task writes no code and runs no dev task.

- [ ] **Step 1 — run the full gate one last time, blocking**, and quote the result.

- [ ] **Step 2 — confirm the two things this plan promised not to touch.**
  `git diff main --stat` must show no change to `src/main/java/com/robsartin/segue/wikidata/`,
  `src/main/java/com/robsartin/segue/musicbrainz/`, `src/main/java/com/robsartin/segue/recommend/`,
  `src/main/java/com/robsartin/segue/evaluate/`, `src/main/resources/application.yaml`,
  `ExpansionBounds.java`, `ExpandContext.java` or `KnownList.java`. Any hit is a finding to report,
  not to fix quietly.

- [ ] **Step 3 — write the hand-back**, into the branch's PR body and nowhere else. It says:

  - the tool is `./gradlew expandPromotions --args="--db $HOME/.segue/segue.db --dry-run"` first,
    then the same line without the flag;
  - the runbook is `docs/developer-guide.md`, chapter *Expanding every promotion*, and it is the
    authority on the order;
  - **the implementer has not run it, and has not touched `~/.segue/segue.db`**;
  - the run is expected to take tens of minutes at a promotion count in the hundreds, and the
    progress line reports a position so a long silence is distinguishable from a hang;
  - what to paste afterwards: the census before, the expansion block, the census after;
  - and that the follow-up — the first evaluation reading after the run, judged by #245's rule, with
    the note that the denominator has moved — is **not written by this plan** and is the owner's to
    file.
