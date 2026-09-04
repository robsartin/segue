# A sourced edge the store refuses never reaches the log — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `IngestService.record` asks the graph's own precondition before it appends, so a sourced edge naming an entity the graph holds no node for is refused by name instead of becoming a permanent row that fails every boot. `SegueService.expandEntity` reports that refusal as a `partial` result instead of letting a store exception escape the facade.

**Architecture:** One new exception in `ingest` (`UnknownEndpointException`) and one private guard in `IngestService.record`, asked through `GraphStore.node` — the store's own precondition, one step earlier. No fold rule changes, no `Equivalences` method, no `GraphStore` widening, no `ExpansionSummary` field. `TinkerGraphStore.requireVertex` and `JenaGraphStore.requireKnown` stay exactly as they are, as the last line of defence.

**Tech Stack:** Java 25, Gradle 9.7.1 (plain `./gradlew`), JUnit 5, AssertJ, TinkerGraph, SQLite.

**Spec:** `docs/superpowers/specs/2026-09-03-record-before-append-design.md`

## Global Constraints

- **Pure TDD / red first**: every behaviour is seen red for the right reason — a real assertion failure, never a compile error — before the code that makes it green. Task 1's tests are therefore written against `IllegalStateException`, which is true today and after; Task 2 narrows them once the new type exists. Quote the actual failure text in every report. Test names `should<Expected>When<Condition>` with `@DisplayName`.
- **Every guard gets a positive control**: plant the defect, watch the check fire, quote it, remove the plant. Written out as steps below.
- **Mikado**: the gate is green before every commit. Task 1 parks its two tests `@Disabled` for exactly the length of the defect, on `MergedIdIsDrawnAsAnOrphanTest`'s precedent; Task 2 removes both annotations in the commit that makes them pass, and the gate's **skipped** count is what says it came off. **Stage by explicit path, git stderr visible — never `git add -A`, never `2>/dev/null` on `git add`.** Read `git status` before every commit. Commits end `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`. Never cite a `.superpowers/` path from a committed file.
- Gate, **blocking, never backgrounded**: `SEGUE_REQUIRE_BROWSER=true SEGUE_REQUIRE_GRAPHVIZ=true ./gradlew check --rerun-tasks`. Fast loops named per task. Run `./gradlew spotlessApply` before each gate.
- **Only JDK 25 is installed and Gradle 9.7.1 launches on it.** Plain `./gradlew`; never `/usr/libexec/java_home -v 21` (it silently returns 25).
- **Never run a writing dev task** (`own`, `ownClaim`, `retractEntity`, any seeding task). `~/.segue/segue.db` is never read, written, copied or created.
- Every id invented in `src/test` must take an unallocatable shape or `arch/StandInQidsDenoteNothingTest` reds: one leading zero for an ordinary stand-in (ADR 58), two for a local entity (ADR 59), eleven digits with no leading zero for a merge's canonical side (ADR 62). **Every id this plan adds — `Q0900102`, `Q0900103` — carries one leading zero, so no `ALLOWED` entry is added or needed.** `Q0900101` is already in use in `IngestServiceTest`.
- **YAGNI**: no parameter, helper or accessor beyond what a step below actually uses.
- Machine is loaded: no wall-clock assertions anywhere.
- `docs/` is a declared test input in `build.gradle.kts`, so a guide or ADR edit re-runs the tests that read it. Do not add an undeclared path.

---

### Task 1: The reproduction, red on both halves, parked

**Files:** Create `src/test/java/com/robsartin/segue/ingest/ARefusedEdgeNeverReachesTheLogTest.java`.

Two tests: the live half (the log keeps the row it was told failed) and the boot half (the log that call left behind cannot be replayed). Both must be seen red — the boot half is the one that makes this a poison pill rather than a bad error message, and a plan that only saw the live half red would not have reproduced the issue.

- [ ] **Step 1 — write the test class in full:**

```java
package com.robsartin.segue.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.robsartin.segue.domain.AssertionRecord;
import com.robsartin.segue.domain.NodeAssertion;
import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.domain.Provenance;
import com.robsartin.segue.port.AssertionLog;
import com.robsartin.segue.port.GraphStore;
import com.robsartin.segue.port.IdentityMerge;
import com.robsartin.segue.sqlite.SqliteAssertionLog;
import com.robsartin.segue.tinker.TinkerGraphStore;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Issue #233: a sourced edge the graph refuses is not written down.
 *
 * <p><b>The second test is the issue.</b> The first says the failed call left nothing behind, which
 * reads as tidiness; the second says what happens if it did — {@code GraphProjector.project} is
 * fatal on the first failure (ADR 24), so a row the graph refused once is a row it refuses at every
 * boot, and ADR 19 forbids removing it. Measured before the fix: the live call threw {@code
 * assertion references unknown entity Q0900102 - upsert the node first}, the log held two rows, and
 * two consecutive boots over that file both threw {@code replay failed at sequence 2} with the same
 * cause.
 *
 * <p><b>Not a case inside {@code IngestServiceTest}.</b> That file's subject is the ORDERING —
 * log first, then graph — and it holds the test this one replaces ({@code logLeadsTheGraph}, which
 * asserted the defect as the contract). The ordering is unchanged by this work and its test should
 * go on saying so; what belongs here is the precondition asked before the ordering begins.
 *
 * <p><b>Both were committed {@code @Disabled}, red for the honest reason: the log kept the row.</b>
 * The annotations came off in the commit that made them pass.
 */
class ARefusedEdgeNeverReachesTheLogTest {

  /** Invented, ADR 58's leading zero — no Wikibase allocation can ever give it a referent. */
  private static final String WREN = "Q0900101";

  /** The endpoint nothing describes. Same shape, same reason. */
  private static final String KETTLES = "Q0900102";

  private static final Provenance WIKIDATA =
      new Provenance("wikidata", "S-1", Instant.parse("2026-09-04T09:00:00Z"), 0.80);

  @Test
  @Disabled("#233: red until record() asks the graph before it appends")
  @DisplayName("should leave the log untouched when the graph holds no node for an endpoint")
  void shouldLeaveTheLogUntouchedWhenTheGraphHoldsNoNodeForAnEndpoint() {
    NodeAssertion seed = new NodeAssertion(WREN, NodeKind.PERSON, "Wren Alderman", WIKIDATA);
    AssertionRecord edge =
        new AssertionRecord(WREN, KETTLES, "INFLUENCED_BY", null, null, WIKIDATA);

    try (AssertionLog log = SqliteAssertionLog.inMemory();
        GraphStore graph = new TinkerGraphStore()) {
      IngestService ingest = new IngestService(log, graph, IdentityMerge.NONE);
      ingest.record(seed);

      assertThatThrownBy(() -> ingest.record(edge))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining(KETTLES);

      assertThat(log.readAll())
          .as("a claim the caller was told failed must not be in the log")
          .containsExactly(seed);
      assertThat(graph.edgeCount()).isZero();
    }
  }

  @Test
  @Disabled("#233: red until record() asks the graph before it appends")
  @DisplayName("should leave a log that still boots when record refuses the edge")
  void shouldLeaveALogThatStillBootsWhenRecordRefusesTheEdge(@TempDir Path dir) {
    Path db = dir.resolve("segue.db");
    AssertionRecord edge =
        new AssertionRecord(WREN, KETTLES, "INFLUENCED_BY", null, null, WIKIDATA);

    try (AssertionLog log = new SqliteAssertionLog(db);
        GraphStore graph = new TinkerGraphStore()) {
      IngestService ingest = new IngestService(log, graph, IdentityMerge.NONE);
      ingest.record(new NodeAssertion(WREN, NodeKind.PERSON, "Wren Alderman", WIKIDATA));
      assertThatThrownBy(() -> ingest.record(edge)).isInstanceOf(IllegalStateException.class);
    }

    // The next boot, over the file that failed call left behind. A real file rather than
    // inMemory(): the whole point is that the row survives the process that wrote it.
    try (AssertionLog reopened = new SqliteAssertionLog(db);
        GraphStore rebuilt = new TinkerGraphStore()) {
      assertThatCode(() -> GraphProjector.project(reopened, rebuilt, IdentityMerge.NONE))
          .as("every boot after a refused edge must still project the log")
          .doesNotThrowAnyException();
      assertThat(rebuilt.node(WREN)).isPresent();
      assertThat(rebuilt.edgeCount()).isZero();
    }
  }
}
```

- [ ] **Step 2 — RED, both halves, with the annotations temporarily off.** Comment out both `@Disabled` lines, then run `./gradlew test --tests '*ARefusedEdgeNeverReachesTheLogTest*'`.

  **Both must fail on a real assertion, not a compile error and not an unhandled exception.** Expect, in the first test, an AssertJ failure on `containsExactly` reporting two elements where one was expected — the second being the `AssertionRecord`. Expect, in the second, an AssertJ `doesNotThrowAnyException` failure quoting `java.lang.IllegalStateException: replay failed at sequence 2` with its cause `assertion references unknown entity Q0900102 - upsert the node first`.

  **Copy both failure texts verbatim into the task report.** If the second test fails on anything other than the replay throw, stop: the reproduction is wrong and the rest of the plan is built on it.

- [ ] **Step 3 — restore both `@Disabled` annotations.** Run `./gradlew test --tests '*ARefusedEdgeNeverReachesTheLogTest*'` and confirm 2 skipped, 0 failed.

- [ ] **Step 4 — gate and commit.** `./gradlew spotlessApply`, then `SEGUE_REQUIRE_BROWSER=true SEGUE_REQUIRE_GRAPHVIZ=true ./gradlew check --rerun-tasks`, blocking. `git status`, then `git add src/test/java/com/robsartin/segue/ingest/ARefusedEdgeNeverReachesTheLogTest.java` (stderr visible), then commit: `Reproduce the sourced edge that poisons every boot (#233)`.

---

### Task 2: The gate — refuse before the append

**Files:** Create `src/main/java/com/robsartin/segue/ingest/UnknownEndpointException.java`. Modify `src/main/java/com/robsartin/segue/ingest/IngestService.java`, `src/test/java/com/robsartin/segue/ingest/ARefusedEdgeNeverReachesTheLogTest.java`, `src/test/java/com/robsartin/segue/ingest/IngestServiceTest.java`.

- [ ] **Step 1 — the exception, in full:**

```java
package com.robsartin.segue.ingest;

import com.robsartin.segue.domain.AssertionRecord;

/**
 * A claim refused BEFORE the append, because the projection it is about holds no node for one of its
 * endpoints (#233).
 *
 * <p><b>Named rather than a plain {@link IllegalStateException}, for one caller's sake.</b> {@code
 * SegueService.expandEntity} has to tell this condition apart from a genuine store failure, a log
 * that cannot be written and a programmer error; catching {@code IllegalStateException} around
 * {@code IngestService.record} would swallow all three and report them as a refused edge. It extends
 * {@code IllegalStateException} anyway so that a caller which does not know about it — and every
 * existing one — sees exactly what {@code TinkerGraphStore.requireVertex} used to throw.
 *
 * <p><b>The repair it names is the one that is correct AT THIS MOMENT, and only at this moment.</b>
 * Before the append, recording the node claim first fixes it. After the append it does not: replay
 * is positional, so a node claim appended later than the edge still leaves the boot failing at the
 * edge's own sequence number — measured for #233. The repair for a log that already carries such a
 * row is to retract the endpoint, which withdraws the edge under ADR 44 without deleting anything,
 * and that sentence belongs to the boot diagnosis rather than here.
 */
public final class UnknownEndpointException extends IllegalStateException {

  private static final long serialVersionUID = 1L;

  private final String endpoint;

  UnknownEndpointException(String endpoint, AssertionRecord edge) {
    super(
        "refusing to append an edge naming an entity the graph holds no node for: "
            + endpoint
            + " in "
            + edge.edgeKey()
            + " - record the node claim first");
    this.endpoint = endpoint;
  }

  /** The endpoint nothing has claimed, so a caller can name it without parsing the message. */
  public String endpoint() {
    return endpoint;
  }
}
```

- [ ] **Step 2 — the guard in `IngestService`.** Add `import com.robsartin.segue.domain.OwnerEdge;` if it is not already imported (it is — the `claim` switch names it). Insert the call into `record`, between the retraction refusal and the append:

```java
    requireEveryEndpointIsInTheGraph(assertion);
    log.append(assertion);
```

  and add the two private methods at the end of the class, after `standIn`:

```java
  /**
   * Refuse an edge the graph will not hold, before the log keeps it forever (#233).
   *
   * <p><b>This is the store's own precondition, asked one step earlier.</b> {@code
   * TinkerGraphStore.requireVertex} and {@code JenaGraphStore.requireKnown} both throw {@code
   * "assertion references unknown entity … - upsert the node first"} for exactly this case, and
   * {@code GraphStoreContract} pins the pair as an agreed contract rather than one engine's habit.
   * Neither is changed: a store must keep refusing whatever a producer does. What this adds is that
   * the refusal now happens while it is still free. ADR 24's ordering — log first, then graph — is
   * untouched and so is its argument; see that ADR's 2026-09-04 amendment for what the argument does
   * NOT cover.
   *
   * <p><b>It asks {@link Equivalences#foldEndpoints}, the same call {@link #apply} is about to
   * make</b>, rather than reading {@code fromQid} and {@code toQid} off the claim. The two must not
   * be able to disagree about WHICH endpoints have to exist — that is #224's defect in miniature,
   * where a rule read raw endpoints while the fold resolved them. On {@link Equivalences#NONE},
   * which is all {@code record} ever holds, the fold is the identity and the second call costs an
   * empty-set lookup and two map misses.
   *
   * <p>A fold that yields nothing needs no check: {@link #apply} returns false for it and reaches
   * the graph with nothing at all.
   */
  private void requireEveryEndpointIsInTheGraph(LoggedAssertion assertion) {
    Optional<LoggedAssertion> folded = Equivalences.NONE.foldEndpoints(assertion);
    if (folded.isEmpty()) {
      return;
    }
    Optional<AssertionRecord> edge =
        switch (folded.get()) {
          case AssertionRecord sourced -> Optional.of(sourced);
          // record() accepts one today - nothing in production sends it, but MergeWiringTest's
          // sibling path does - and apply() hands it to graph.record exactly as it hands a sourced
          // edge, so it poisons a log identically. #228 gates the same shape on claim().
          case OwnerEdge owned -> Optional.of(owned.toAssertion());
          // The other four create a node or create nothing. upsertNode cannot refuse, a merge's
          // graph half is standIn() which upserts, and a retraction never reaches here because
          // record() refuses one above. The switch is exhaustive over the sealed interface so that
          // a seventh claim type cannot be added without deciding whether it has an endpoint.
          case NodeAssertion ignored -> Optional.empty();
          case LocalEntity ignored -> Optional.empty();
          case SameAs ignored -> Optional.empty();
          case Retraction ignored -> Optional.empty();
        };
    edge.ifPresent(this::requireBothEndpoints);
  }

  private void requireBothEndpoints(AssertionRecord edge) {
    requireEndpoint(edge, edge.fromQid());
    requireEndpoint(edge, edge.toQid());
  }

  private void requireEndpoint(AssertionRecord edge, String qid) {
    if (graph.node(qid).isEmpty()) {
      throw new UnknownEndpointException(qid, edge);
    }
  }
```

- [ ] **Step 3 — update `record`'s javadoc.** Its opening line reads *"Append one claim to the log, then apply it to the graph."* Add a paragraph after it:

```java
   * <p><b>An edge whose endpoint the graph holds no node for is refused before the append</b>
   * (#233), the same way a retraction is. The log is append-only (ADR 19) and replay is fatal on the
   * first failure (ADR 24), so a row the graph refuses once is a row every later boot refuses too —
   * the log's "correct failure direction" only holds for a claim that can eventually project. See
   * {@link #requireEveryEndpointIsInTheGraph}.
```

- [ ] **Step 4 — replace `IngestServiceTest.logLeadsTheGraph`.** That test pins the defect as the contract, so it goes in this commit and not before. Replace the whole method with:

```java
  @Test
  @DisplayName("record refuses an edge it cannot apply rather than appending one it must keep")
  void recordRefusesAnEdgeItCannotApply() {
    // #233. This method used to be logLeadsTheGraph and asserted the opposite: that the log had
    // already kept a claim the caller was told had failed. The ORDERING that name described is
    // unchanged and is still asserted by liveAndReplayAgree and retractAppendsAndTouchesNoGraph;
    // what changed is that a claim which cannot survive the ordering never enters it.
    AssertionRecord dangling =
        new AssertionRecord("Q0404", "Q0405", "MEMBER_OF", null, null, WIKIDATA);

    assertThatThrownBy(() -> ingest.record(dangling))
        .isInstanceOf(UnknownEndpointException.class)
        .hasMessageContaining("Q0404");

    assertThat(log.readAll()).isEmpty();
  }
```

  `Q0404` is refused first because it is the `from` endpoint and neither exists.

- [ ] **Step 5 — take the parking off.** In `ARefusedEdgeNeverReachesTheLogTest`, delete both `@Disabled` lines and the now-unused `import org.junit.jupiter.api.Disabled;`, and narrow both `isInstanceOf(IllegalStateException.class)` to `isInstanceOf(UnknownEndpointException.class)` — importing nothing, since the test is in the same package.

- [ ] **Step 6 — GREEN.** `./gradlew test --tests '*ARefusedEdgeNeverReachesTheLogTest*' --tests '*IngestServiceTest*'`. Both new tests pass, no skips, `IngestServiceTest` fully green. Quote the run summary.

- [ ] **Step 7 — POSITIVE CONTROL, the half-guard.** In `requireBothEndpoints`, delete the `toQid` line so only the `from` endpoint is required:

```java
  private void requireBothEndpoints(AssertionRecord edge) {
    requireEndpoint(edge, edge.fromQid());
  }
```

  Re-run the two tests. **Both must fail**, because the fixture's edge is `WREN → KETTLES` and it is the `to` end that is unknown: the first on `containsExactly` seeing two rows, the second on the replay throw. Quote both failures. **If either still passes, the guard is not checking what the test thinks it is — stop and say so.** Restore the line and re-run green.

- [ ] **Step 8 — POSITIVE CONTROL, the fold.** Change `Equivalences.NONE.foldEndpoints(assertion)` to `Optional.of(assertion)` — removing the shared-fold call while leaving the guard's shape intact. Run `./gradlew test --tests '*IngestServiceTest*' --tests '*MergeCarriesEverythingTest*' --tests '*OwnerClaimProjectionTest*'` and record what happens. **This one is expected to stay GREEN**, and that is the finding to report rather than a failure to chase: on `Equivalences.NONE` the fold IS the identity, so nothing in the suite can tell the two apart. Restore the line anyway — the call is there so the two cannot drift if `record` is ever handed real equivalences, and a plan that quietly dropped it because no test noticed is how #224 happened. Say this in the report.

- [ ] **Step 9 — gate and commit.** `./gradlew spotlessApply`, then the full gate blocking. `git status`, then `git add src/main/java/com/robsartin/segue/ingest/UnknownEndpointException.java src/main/java/com/robsartin/segue/ingest/IngestService.java src/test/java/com/robsartin/segue/ingest/ARefusedEdgeNeverReachesTheLogTest.java src/test/java/com/robsartin/segue/ingest/IngestServiceTest.java` (stderr visible), then commit: `Refuse a sourced edge before the log keeps it forever (#233)`.

---

### Task 3: The caller — a refusal the MCP client can read

**Files:** Modify `src/main/java/com/robsartin/segue/mcp/SegueService.java`, `src/test/java/com/robsartin/segue/mcp/SegueServiceTest.java`.

Measured for the spec: driven through the facade with an adapter returning an edge naming the seed at neither end, the store's `IllegalStateException` escaped `expandEntity` after two rows were already committed. That contradicts the class's own second invariant and the developer guide's sentence about this method.

- [ ] **Step 1 — RED first.** Add to `SegueServiceTest`, in the expand section. Add the imports it needs: `com.robsartin.segue.ingest.GraphProjector`, `static org.assertj.core.api.Assertions.assertThatCode`. Add three constants beside `MINTED`:

```java
  /** Invented, ADR 58's leading zero. The seed, which the graph holds before the call. */
  private static final String WREN = "Q0900101";

  /** A neighbour the stub resolver can identify. */
  private static final String KETTLES = "Q0900102";

  /** The far endpoint of a third-party edge, which nothing describes and nothing can fetch. */
  private static final String MARRAM = "Q0900103";
```

  and the test:

```java
  @Test
  @DisplayName("should report a refused edge as partial when no source describes its far endpoint")
  void shouldReportARefusedEdgeAsPartialWhenNoSourceDescribesItsFarEndpoint() {
    // #233. neighborOf resolves ONE endpoint - the other end of the assertion from the seed's point
    // of view - so an edge naming the seed at NEITHER end has its far endpoint resolved by nobody.
    // No shipped adapter emits one (all three put the seed at an end) and nothing says they must,
    // which is why the gate is at IngestService and the report is here.
    ingest.record(new NodeAssertion(WREN, NodeKind.PERSON, "Wren Alderman", WIKIDATA));
    resolver.withEntity(new NodeAssertion(KETTLES, NodeKind.GROUP, "Kettles Anonymous", WIKIDATA));
    AssertionRecord thirdParty =
        new AssertionRecord(KETTLES, MARRAM, "INFLUENCED_BY", null, null, WIKIDATA);

    ToolResult<SegueService.ExpansionSummary> result =
        service(
                new StubSourceAdapter(
                    "stub", new ExpandResult(List.of(thirdParty), List.of(), false, false)))
            .expandEntity(WREN, 10);

    assertThat(result.outcome()).isEqualTo(ToolResult.Outcome.PARTIAL);
    assertThat(result.detail()).contains(MARRAM);
    assertThat(result.payload().edgesAdded()).isZero();
    try (GraphStore rebuilt = new TinkerGraphStore()) {
      assertThatCode(() -> GraphProjector.project(log, rebuilt, IdentityMerge.NONE))
          .as("what the call left in the log must still boot")
          .doesNotThrowAnyException();
    }
  }
```

- [ ] **Step 2 — run it and read the failure carefully.** `./gradlew test --tests '*SegueServiceTest*'`. After Task 2 the gate already refuses the edge, so `UnknownEndpointException` now escapes `expandEntity` and the test fails on **that** rather than on the outcome assertion. That is the right red for this task — the facade is letting a refusal through instead of reporting it. Quote it. (If it fails on `result.outcome()` instead, Task 2 did not land; stop.)

- [ ] **Step 3 — GREEN.** In `SegueService`, add `import com.robsartin.segue.ingest.UnknownEndpointException;` and `import java.util.LinkedHashSet;`. Beside `identityRecorded`, add:

```java
    // Endpoints no claim describes, by endpoint rather than by assertion — the same unit
    // skippedNeighbors uses, and for the same reason: two assertions naming one unknown entity are
    // one thing the caller can act on. Insertion-ordered so the reason string is stable.
    Set<String> refusedEndpoints = new LinkedHashSet<>();
```

  Replace the two closing lines of the `bounded` loop:

```java
      ingest.record(assertion);
      edgesAdded++;
```

  with:

```java
      try {
        ingest.record(assertion);
      } catch (UnknownEndpointException e) {
        // #233. The gate refused this edge BEFORE the append, so nothing is half-written and the
        // expansion carries on rather than aborting a thirty-round-trip call over one bad row —
        // the same choice made for an unresolvable neighbour above. Letting it escape is what the
        // class's second invariant forbids and what ADR 27 turns into a readable result instead.
        log.warn("expandEntity({}) refused an edge: {}", qid, e.getMessage());
        refusedEndpoints.add(e.endpoint());
        continue;
      }
      edgesAdded++;
```

  and add a reason, after the `skippedNeighbors` one:

```java
    if (!refusedEndpoints.isEmpty()) {
      reasons.add(
          refusedEndpoints.size()
              + " endpoint(s) no claim describes were refused: "
              + String.join(", ", refusedEndpoints));
    }
```

  **Nothing is added to `ExpansionSummary`.** That record is the MCP wire shape and ADR 56 already refused to widen it for attribution, on the argument that the model reads `detail`; this is the same argument, and `ToolSurfaceTest` counts tools rather than fields, so nothing would have caught a field added by reflex.

- [ ] **Step 4 — document it on the method.** Add a paragraph to `expandEntity`'s javadoc, after the "Neighbour fetches that fail once are not retried" paragraph:

```java
   * <p><b>An edge {@code IngestService} refuses is skipped and named, not thrown</b> (#233). {@link
   * #neighborOf} resolves ONE endpoint — the far end from the seed's point of view — so an edge
   * naming the seed at neither end has its second endpoint resolved by nobody. Every adapter in
   * {@code src/main} puts the seed at an end, and nothing in {@link SourceAdapter} says it must, so
   * this is the report rather than the guard: the guard is at the append, where a refusal costs a
   * message instead of a log that cannot boot. Counted by distinct endpoint, like {@link
   * ExpansionSummary#skippedNeighbors()}, and named in {@code detail} rather than on the summary —
   * ADR 56's reason for keeping attribution out of the wire shape.
```

- [ ] **Step 5 — verify green.** `./gradlew test --tests '*SegueServiceTest*'`. Quote the summary.

- [ ] **Step 6 — POSITIVE CONTROL.** Delete the whole `catch (UnknownEndpointException e)` block, leaving `ingest.record(assertion);` bare. Re-run the test: it must fail with `UnknownEndpointException` escaping the facade. Quote it. Restore and re-run green.

- [ ] **Step 7 — gate and commit.** `./gradlew spotlessApply`, then the full gate blocking. `git status`, then `git add src/main/java/com/robsartin/segue/mcp/SegueService.java src/test/java/com/robsartin/segue/mcp/SegueServiceTest.java` (stderr visible), then commit: `Report a refused edge instead of throwing it at the MCP client (#233)`.

---

### Task 4: The record — guide, ADR amendment, gotcha

**Files:** Modify `docs/developer-guide.md`, `docs/adr/0024-sqlite-assertion-log.md`, `CLAUDE.md`.

No new ADR: ADR 24's ordering decision is unchanged and ADR 19's append-only rule is unchanged. What is added is a dated amendment saying what one of ADR 24's consequences does not cover.

- [ ] **Step 1 — the guide's ordering section.** In `docs/developer-guide.md`, the paragraph under **The ordering, and why it is not an accident** currently opens *"`IngestService.record` is three lines"* — which is stale twice over. Replace that whole paragraph with:

```markdown
`IngestService.record` refuses what it cannot keep, then appends to the log, then applies to the
graph. The last two are deliberately **not** atomic. If the graph write fails, the log is ahead of
the graph — the recoverable direction, because the next boot replays it. The reverse order would
lose the claim permanently and leave the log authoritative in name only. Do not "fix" this by
wrapping both in a transaction that rolls the log back.

**That recoverability has a precondition, and issue #233 is what happens without it.** The log is
ahead recoverably only if the claim can eventually project. An edge naming an entity the graph holds
no node for cannot: `TinkerGraphStore.requireVertex` and `JenaGraphStore.requireKnown` both refuse
it, `GraphProjector.project` is fatal on the first failure, and ADR 19 forbids removing the row — so
the live call fails once and every boot after it fails at that row. `record` therefore asks the
store's own precondition, through `GraphStore.node`, **before** the append, and refuses with
`UnknownEndpointException` naming the endpoint. The stores are unchanged: their throw is the last
line of defence and a store must keep it whatever a producer does. Note the repair the refusal names
is only correct at that moment — appending the missing node claim does **not** rescue a log that
already carries such a row, because replay is positional and the later claim lands after the row that
needed it. For a log that already carries one, the repair is to retract the endpoint (ADR 44), which
withdraws the edge without deleting anything.
```

- [ ] **Step 2 — the guide's `expand_entity` section.** In the "What the diagram shows" paragraph for that tool, the last sentence reads *"The call returns a single `ToolResult` whose outcome is `ok` or `partial`, never a thrown exception."* It was false for one case; append after it:

```markdown
That was not true for one case until issue #233: an edge naming the seed at neither end had its
second endpoint resolved by nobody, and the store's exception escaped the facade after some rows were
already committed. `IngestService` now refuses such an edge before the append and `expandEntity`
catches the refusal, skips the assertion and names the endpoint in `detail` — the same treatment an
unresolvable neighbour already got.
```

- [ ] **Step 3 — the ADR 24 amendment.** Append to the end of `docs/adr/0024-sqlite-assertion-log.md`, after the "One writer is assumed" bullet:

```markdown

**Amendment (2026-09-04, issue #233): the fifth consequence above is scoped, and the scope was not
stated.** It reads *"Because the log is written first, a failure applying to the graph leaves the log
ahead. That is the correct failure direction: a restart replays it right."* That is true of a claim
that can eventually project, and only of one. `TinkerGraphStore.record` and `JenaGraphStore.record`
both refuse an edge naming an entity nothing has claimed as a node, and the fourth decision bullet
above makes replay fatal at the first such row — so for that claim the log being ahead is not
recoverable in any sense: the row is permanent under ADR 19, and the restart that was supposed to
"replay it right" fails at it instead, as does every restart after that. Measured for #233 on a
temp-file log: the live call threw, the log held the row, and two consecutive replays both threw
`replay failed at sequence 2`.

**The ordering decision is unchanged, and so is its argument.** What changes is that
`IngestService.record` now asks the store's own precondition — through `GraphStore.node`, on the port,
so both engines answer alike — **before** the append, and refuses with a message naming the endpoint.
The log therefore never gets ahead by a row that can never catch up, which is the only case the
original sentence did not cover. Appending is still first; the two halves are still not atomic; a
crash between them still leaves the recoverable direction, and now genuinely so.

**Two things this does not do.** It does not widen `GraphStore` (the stores keep throwing; the gate
is a second, earlier asking of the same question, and `GraphStoreContract` is what keeps the two
answers identical), and it does not repair a log that already carries such a row. That repair is not
"append the missing node claim" — replay is positional, so a claim appended after the edge still
leaves the boot failing at the edge's sequence number. It is `./gradlew retractEntity` on the
endpoint, which withdraws the edge under ADR 44 without deleting anything.
```

- [ ] **Step 4 — the gotcha.** Add to `CLAUDE.md`, in **Gotchas already paid for**, immediately after the bullet beginning *"A retraction is honoured by the FOLD, never applied to a store."*:

```markdown
- **`IngestService.record` refuses before it appends, and the log-then-graph ordering is why.** The
  log is append-only and replay is fatal at the first failure, so a claim the graph refuses is a row
  every later boot refuses too — the live call fails once and the server never starts again. `record`
  therefore asks `GraphStore.node` for both of an edge's folded endpoints before `log.append` and
  throws `UnknownEndpointException`; `TinkerGraphStore.requireVertex` and `JenaGraphStore.requireKnown`
  are unchanged and stay the last line of defence, pinned as one contract by `GraphStoreContract`.
  **Do not "fix" this by applying to the graph first** — that loses a claim the graph accepted if the
  process dies between the halves, which is ADR 19's whole failure mode. **Do not tolerate the
  missing endpoint at boot** either; `LogProjection.danglingEdges` is the alarm for exactly this and
  is supposed to stay 0. **And the obvious repair is wrong**: appending the missing node claim does
  not rescue an already-poisoned log, because replay is positional; retracting the endpoint (ADR 44)
  is the one repair that works. ADR 24's 2026-09-04 amendment, issue #233.
```

- [ ] **Step 5 — run the doc-reading tests.** `./gradlew test --tests '*DocumentationLinksTest*' --tests '*DeveloperGuideEnumerationsTest*' --tests '*AdrIndexTest*' --tests '*DeveloperGuideCensusExamplesTest*' --tests '*DeveloperGuideRetractionExamplesTest*'`. `docs` is a declared test input, so these re-run without `--rerun-tasks`; confirm they actually ran rather than reporting UP-TO-DATE, and quote the summary.

- [ ] **Step 6 — gate and commit.** `./gradlew spotlessApply`, then the full gate blocking. `git status`, then `git add docs/developer-guide.md docs/adr/0024-sqlite-assertion-log.md CLAUDE.md` (stderr visible), then commit: `Say what the log-ahead argument does not cover (#233)`.

---

### Task 5: Reconcile with #228 — one gate shape, one diagnosis

**Files:** depends on the branch below. Read `git log --oneline origin/main -20` first and decide which branch applies.

#228 is adding a producer gate at `IngestService.claim` for the owner's paths and a **named boot diagnosis** at `GraphProjector.project`. This issue must not produce a second of either. Its own spec already files this issue as a finding it deliberately does not fix, so the two are designed to meet.

- [ ] **Step 1 — determine the state.** `git fetch origin && git log --oneline origin/main -20`. Look for #228's commits and for `Equivalences.nodesTheFoldHolds`. Record which branch you are taking and why.

**Branch A — #228 has landed on `main`.**

- [ ] **Step A1 — rebase.** `git rebase origin/main`. Resolve conflicts in `IngestService.java` by keeping both gates: `claim`'s asks the log's fold, `record`'s asks the running graph. Run the full gate blocking before going on.
- [ ] **Step A2 — one exception type.** If #228 introduced its own refusal type, delete `UnknownEndpointException` and make `record`'s guard throw #228's, keeping this plan's message wording where it is more specific (it names the edge as well as the endpoint). If #228 threw a plain `IllegalStateException`, do the opposite: make `claim`'s refusal throw `UnknownEndpointException` too. **One type and one message builder, whichever name survives** — say in the report which, and update `SegueService`'s catch and `ARefusedEdgeNeverReachesTheLogTest`'s `isInstanceOf` to match. Full gate blocking.
- [ ] **Step A3 — one diagnosis, extended rather than duplicated.** Add the sourced-edge shape as a case to #228's boot-diagnosis test rather than creating a file: a log of `node(WREN)` then `edge(WREN → KETTLES)`, hand-appended through `AssertionLog.append` (not through `record`, which now refuses it — that is the point: the diagnosis exists for logs the gate could not reach). Assert `GraphProjector.project` refuses it by name, quoting the sequence number and `Q0900102`. Red first: comment out #228's diagnosis, watch it fall through to `TinkerGraphStore`'s message, quote that, restore.
- [ ] **Step A4 — check the repair sentence.** #228's diagnosis must name **retracting the endpoint**, not *upsert the node first*. Measured for this issue on a real SQLite log: appending the missing node claim leaves the boot failing at the same sequence, and only `retractEntity` on the endpoint makes it boot (`applied 1, edges 0`). If #228's message says otherwise, correct it in this commit and say so — it is a defect against a measurement, not a redesign.
- [ ] **Step A5 — gate and commit.** Full gate blocking, `git status`, stage by explicit path, commit: `Share one refusal and one boot diagnosis with #228 (#233)`.

**Branch B — #228 has not landed.**

- [ ] **Step B1 — add no boot diagnosis.** Writing a second one is the failure both issues exist to avoid. Do not create `GraphProjector` changes here.
- [ ] **Step B2 — leave the handshake in writing.** Append to `docs/superpowers/specs/2026-09-03-record-before-append-design.md`, under **Reconciling with #228**, a short dated note recording that this landed first, that `UnknownEndpointException` in `ingest` is the type #228's `claim` gate should adopt, and the stated residual: **until #228's boot diagnosis lands, a log written by an older build — or by a hand-written SQLite row — still dies at boot on `TinkerGraphStore`'s own message rather than a named one, and the repair it needs is `retractEntity` on the endpoint.**
- [ ] **Step B3 — gate and commit.** Full gate blocking, `git status`, `git add docs/superpowers/specs/2026-09-03-record-before-append-design.md`, commit: `Record what #228 still owes the boot path (#233)`.

---

## Verification before the branch is finished

- [ ] `SEGUE_REQUIRE_BROWSER=true SEGUE_REQUIRE_GRAPHVIZ=true ./gradlew check --rerun-tasks` green, blocking, quoted.
- [ ] `./gradlew test --tests '*ARefusedEdgeNeverReachesTheLogTest*'` reports **0 skipped** — the parking came off.
- [ ] `BothFoldsAgreeTest` and `StandInAgreesInEveryHomeTest` are **unmodified** (`git diff origin/main --stat` names neither). No fold rule changed; if either needed editing, something in this plan went further than it was supposed to.
- [ ] `TinkerGraphStore.java`, `JenaGraphStore.java`, `GraphStore.java`, `Equivalences.java`, `Retractions.java`, `GraphProjector.java` are unmodified on branch B (on branch A, only `GraphProjector`'s test may be touched, and only to add a case).
- [ ] Every positive control was seen to FIRE, and Task 2 Step 8's control was seen NOT to fire, with that stated as a finding rather than hidden.
- [ ] No id added to `src/test` needed a `StandInQidsDenoteNothingTest.ALLOWED` entry; if one did, the id took the wrong shape.
