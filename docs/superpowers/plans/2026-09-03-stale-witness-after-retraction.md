# A retraction the running graph has not seen — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** close issue #234 the way the spec recommends — the gate goes on asking the running graph, the behaviour and its repair are pinned end to end, `RetractRun`'s closing line stops describing the two-writer window and starts telling the operator to close it, and ADR 24 records the ruling as a dated amendment beside the residual its #233 amendment filed.

**Architecture:** No new rule in `domain`, no `Equivalences` call from `IngestService.record`, no widening of `AssertionLog` or `GraphStore`, and no change to `Retractions`. Exactly one production line changes: the note `RetractRun.run` emits after the append. Everything else is tests and documents.

**Tech Stack:** Java 25, Gradle (plain `./gradlew`), JUnit 5, AssertJ, TinkerGraph, SQLite.

**Spec:** `docs/superpowers/specs/2026-09-03-stale-witness-after-retraction-design.md`

## Global Constraints

- **Pure TDD / red first.** Task 3 is an ordinary red → green: the assertion fails against the old sentence before the new one exists. **Tasks 1 and 2 pin behaviour that already holds**, so there is no natural red to observe, and saying so out loud is required rather than optional: each of them therefore earns its red with a **positive control** — plant the change that ought to break the assertion, run it, quote the real assertion failure, remove the plant, re-run green. A compile error is never a red. Quote the actual failure text in every report. Test names `should<Expected>When<Condition>` with `@DisplayName`.
- **Mikado**: the gate is green before every commit. **Stage by explicit path, git stderr visible — never `git add -A`, never `2>/dev/null` on `git add`.** Read `git status` before every commit. Commits end `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`. Never cite a `.superpowers/` path from a committed file.
- Gate, **blocking, never backgrounded**: `SEGUE_REQUIRE_BROWSER=true SEGUE_REQUIRE_GRAPHVIZ=true ./gradlew check --rerun-tasks`. Fast loops are named per task. Run `./gradlew spotlessApply` before each gate.
- **Only JDK 25 is installed and Gradle launches on it.** Plain `./gradlew`; never `/usr/libexec/java_home -v 21` (it silently returns 25).
- **Never run a writing dev task** (`own`, `ownClaim`, `retractEntity`, any seeding task). `~/.segue/segue.db` is never read, written, copied or created. Every retraction in this plan is appended through `IngestService.retract` or `RetractRun` against a `@TempDir` or in-memory database.
- Every id invented in `src/test` must take an unallocatable shape or `arch/StandInQidsDenoteNothingTest` reds — that allowlist is keyed by (id, file, context). **Every id this plan uses — `Q0900101`, `Q0900102` — carries ADR 58's single leading zero and is already used unallowlisted by `ARefusedEdgeNeverReachesTheLogTest`, so no `ALLOWED` entry is added or needed.**
- **YAGNI**: no parameter, helper or accessor beyond what a step below actually uses.
- Machine is loaded: **no wall-clock assertions anywhere.** The spec's 407 ms reading is a design input, not a test.
- `docs/` is a declared test input in `build.gradle.kts`, so a guide or ADR edit re-runs the tests that read it. Do not add an undeclared path.
- **#228 is in flight on branch `228-ready` and is not merged.** Nothing in tasks 1–4 may depend on it; Task 5 is the rebase and the reconciliation.

---

### Task 1: Pin the round trip — the append, the boot, and the repair

**Files:** Create `src/test/java/com/robsartin/segue/ingest/ARetractionTheRunningGraphHasNotSeenTest.java`.

Four tests: the append the gate lets through, the boot that cannot get past it, the one command that repairs it, and the control that says a re-added entity is still a legal endpoint.

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
import com.robsartin.segue.domain.Retraction;
import com.robsartin.segue.port.AssertionLog;
import com.robsartin.segue.port.GraphStore;
import com.robsartin.segue.port.IdentityMerge;
import com.robsartin.segue.sqlite.SqliteAssertionLog;
import com.robsartin.segue.tinker.TinkerGraphStore;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Issue #234: the gate #233 added asks the RUNNING graph, and ADR 44 leaves the running graph
 * holding a retracted entity until the next boot rebuilds it from the log. So an edge naming a
 * just-retracted id passes the gate, is appended, and the boot cannot get past it.
 *
 * <p><b>The first test looks like it asserts the defect, and it asserts a decision.</b> ADR 24's
 * 2026-09-04 amendment for this issue is the ruling: the witness stays the running graph, because
 * the witness that would see the retraction is the log's fold and asking it costs a whole
 * {@code readAll} per claim on a path that records hundreds of claims per expansion. What closes
 * the case is the other three tests — the boot names the row, and one more retraction repairs it
 * without deleting anything. If a future change makes the gate ask the log, this file and that
 * amendment are what have to be revisited together.
 *
 * <p><b>The fourth test is the reason the cheap version of that gate was rejected.</b> A retraction
 * reaches backwards only (ADR 44, question 4), so adding an entity back is how it returns, and an
 * edge claimed after the re-add is legal. A gate keyed on "the log holds a retraction naming this
 * id" would refuse it. Whatever asks the log next has to pass this test.
 *
 * <p><b>Not a case inside {@code ARefusedEdgeNeverReachesTheLogTest}.</b> That file's subject is an
 * edge the gate REFUSES; this one's is an edge it accepts.
 */
class ARetractionTheRunningGraphHasNotSeenTest {

  /** Invented, ADR 58's leading zero — no Wikibase allocation can ever give it a referent. */
  private static final String WREN = "Q0900101";

  /** The endpoint that is retracted while the graph goes on holding a node for it. */
  private static final String KETTLES = "Q0900102";

  private static final Provenance WIKIDATA =
      new Provenance("wikidata", "S-1", Instant.parse("2026-09-04T09:00:00Z"), 0.80);
  private static final Instant RETRACTED_AT = Instant.parse("2026-09-04T10:00:00Z");
  private static final Instant REPAIRED_AT = Instant.parse("2026-09-04T11:00:00Z");

  private static final AssertionRecord EDGE =
      new AssertionRecord(WREN, KETTLES, "INFLUENCED_BY", null, null, WIKIDATA);

  @Test
  @DisplayName("the gate asks the running graph, which a retraction has not reached, so the edge is appended")
  void shouldAppendAnEdgeNamingARetractedEndpointWhenOnlyTheRunningGraphIsAsked(@TempDir Path dir) {
    Path db = dir.resolve("segue.db");
    try (AssertionLog log = new SqliteAssertionLog(db);
        GraphStore graph = new TinkerGraphStore()) {
      IngestService ingest = new IngestService(log, graph, IdentityMerge.NONE);
      ingest.record(new NodeAssertion(WREN, NodeKind.PERSON, "Wren Alderman", WIKIDATA));
      ingest.record(new NodeAssertion(KETTLES, NodeKind.PERSON, "Kettles Nye", WIKIDATA));
      retract(log, "resolved to the wrong entity", RETRACTED_AT);

      assertThatCode(() -> ingest.record(EDGE))
          .as("the gate asks GraphStore.node, and the retraction has not reached the graph")
          .doesNotThrowAnyException();

      assertThat(graph.node(KETTLES))
          .as("ADR 44: GraphStore cannot remove anything, so the node is still there")
          .isPresent();
      assertThat(log.readAll()).hasSize(4).endsWith(EDGE);
    }
  }

  @Test
  @DisplayName("every boot stops at the edge when it names an endpoint a retraction took away")
  void shouldStopEveryBootWhenAnEdgeNamesARetractedEndpoint(@TempDir Path dir) {
    Path db = dir.resolve("segue.db");
    poison(db);

    // Twice, deliberately: a row that stops one boot stops every later one, and ADR 19 forbids
    // removing it. That is what makes this a poison pill rather than a bad error message.
    assertThatThrownBy(() -> boot(db)).hasMessageContaining("sequence 4");
    assertThatThrownBy(() -> boot(db)).hasMessageContaining("sequence 4");
  }

  @Test
  @DisplayName("the boot succeeds again when the endpoint is retracted a second time, and the log keeps every row")
  void shouldBootAgainWhenTheEndpointIsRetractedASecondTime(@TempDir Path dir) {
    Path db = dir.resolve("segue.db");
    poison(db);

    try (AssertionLog log = new SqliteAssertionLog(db)) {
      retract(log, "repairing the log after #234", REPAIRED_AT);
    }

    try (AssertionLog reopened = new SqliteAssertionLog(db);
        GraphStore rebuilt = new TinkerGraphStore()) {
      assertThatCode(() -> GraphProjector.project(reopened, rebuilt, IdentityMerge.NONE))
          .as("the second retraction lies after the edge, so the edge stops projecting")
          .doesNotThrowAnyException();
      assertThat(rebuilt.node(WREN)).isPresent();
      assertThat(rebuilt.node(KETTLES)).isEmpty();
      assertThat(rebuilt.edgeCount()).isZero();
      assertThat(reopened.readAll())
          .as("nothing is deleted: the repair is one more claim (ADR 19, ADR 44)")
          .hasSize(5);
    }
  }

  @Test
  @DisplayName("an edge naming an entity added back after its retraction still boots")
  void shouldStillBootWhenAnEdgeNamesAnEntityAddedBackAfterItsRetraction(@TempDir Path dir) {
    Path db = dir.resolve("segue.db");
    try (AssertionLog log = new SqliteAssertionLog(db);
        GraphStore graph = new TinkerGraphStore()) {
      IngestService ingest = new IngestService(log, graph, IdentityMerge.NONE);
      ingest.record(new NodeAssertion(WREN, NodeKind.PERSON, "Wren Alderman", WIKIDATA));
      ingest.record(new NodeAssertion(KETTLES, NodeKind.PERSON, "Kettles Nye", WIKIDATA));
      retract(log, "resolved to the wrong entity", RETRACTED_AT);
      // ADR 44 question 4: an entity comes back by being added again, and nothing special
      // happens on the way — the new claim is simply newer than the retraction.
      ingest.record(new NodeAssertion(KETTLES, NodeKind.PERSON, "Kettles Nye", WIKIDATA));
      ingest.record(EDGE);
    }

    try (AssertionLog reopened = new SqliteAssertionLog(db);
        GraphStore rebuilt = new TinkerGraphStore()) {
      assertThatCode(() -> GraphProjector.project(reopened, rebuilt, IdentityMerge.NONE))
          .doesNotThrowAnyException();
      assertThat(rebuilt.node(KETTLES)).isPresent();
      assertThat(rebuilt.edgeCount()).isOne();
    }
  }

  /** The log the issue describes, written through the live path exactly as a server would. */
  private static void poison(Path db) {
    try (AssertionLog log = new SqliteAssertionLog(db);
        GraphStore graph = new TinkerGraphStore()) {
      IngestService ingest = new IngestService(log, graph, IdentityMerge.NONE);
      ingest.record(new NodeAssertion(WREN, NodeKind.PERSON, "Wren Alderman", WIKIDATA));
      ingest.record(new NodeAssertion(KETTLES, NodeKind.PERSON, "Kettles Nye", WIKIDATA));
      retract(log, "resolved to the wrong entity", RETRACTED_AT);
      ingest.record(EDGE);
    }
  }

  /**
   * A retraction, appended the way the dev tool appends one. In production it is a different
   * process (ADR 60), which is the whole reason the running graph can be stale about it.
   */
  private static void retract(AssertionLog log, String reason, Instant at) {
    IngestService.retract(log, new Retraction(KETTLES, reason, at));
  }

  private static long boot(Path db) {
    try (AssertionLog reopened = new SqliteAssertionLog(db);
        GraphStore rebuilt = new TinkerGraphStore()) {
      return GraphProjector.project(reopened, rebuilt, IdentityMerge.NONE);
    }
  }
}
```

- [ ] **Step 2 — run it and read the output.** `./gradlew test --tests 'com.robsartin.segue.ingest.ARetractionTheRunningGraphHasNotSeenTest'`. It **passes**, because it pins behaviour that already holds. Record in the report that this is not a red and that steps 3 and 4 are what earn it.

- [ ] **Step 3 — positive control A, the append.** Plant a fold gate in `IngestService.record`, immediately before `log.append(assertion)`:

```java
    // PLANT — positive control for #234, removed in step 3.
    if (assertion instanceof AssertionRecord probe
        && com.robsartin.segue.domain.Retractions.in(log.readAll())
            .lastRetraction()
            .containsKey(probe.toQid())) {
      throw new IllegalStateException("PLANT refused " + probe.toQid());
    }
```

Run the same fast loop. `shouldAppendAnEdgeNamingARetractedEndpointWhenOnlyTheRunningGraphIsAsked` must fail on its `doesNotThrowAnyException` assertion, and `shouldStillBootWhenAnEdgeNamesAnEntityAddedBackAfterItsRetraction` must fail too — that second failure is the spec's argument that the cheap gate is wrong, observed rather than reasoned. Quote both failures in the report. **Remove the plant** and re-run green.

- [ ] **Step 4 — positive control B, the boot.** In `poison`, comment out the `retract(...)` line. Run the same fast loop: `shouldStopEveryBootWhenAnEdgeNamesARetractedEndpoint` must fail because the boot now succeeds, and `shouldBootAgainWhenTheEndpointIsRetractedASecondTime` must fail on its row count. That proves the retraction, and not something else in the fixture, is what stops the boot. Quote both failures. **Restore the line** and re-run green.

- [ ] **Step 5 — gate and commit.** `./gradlew spotlessApply`, then `SEGUE_REQUIRE_BROWSER=true SEGUE_REQUIRE_GRAPHVIZ=true ./gradlew check --rerun-tasks`, blocking. `git status`, then `git add src/test/java/com/robsartin/segue/ingest/ARetractionTheRunningGraphHasNotSeenTest.java` with stderr visible, then commit: `An edge naming a retracted endpoint is appended, and one more retraction repairs the log (#234)`.

---

### Task 2: Pin that the supported flow reaches it

**Files:** Create `src/test/java/com/robsartin/segue/mcp/AnExpansionAfterARetractionTest.java`.

The issue says the supported flow reaches this "likely". This task turns that into a measurement, and pins the boundary: an expansion whose source **volunteers** the retracted neighbour's identity repairs the log by accident, and one whose source does not, poisons it.

- [ ] **Step 1 — write the test class in full:**

```java
package com.robsartin.segue.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.robsartin.segue.domain.AssertionRecord;
import com.robsartin.segue.domain.Candidate;
import com.robsartin.segue.domain.NodeAssertion;
import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.domain.NodeRecord;
import com.robsartin.segue.domain.Provenance;
import com.robsartin.segue.domain.Retraction;
import com.robsartin.segue.ingest.GraphProjector;
import com.robsartin.segue.ingest.IngestService;
import com.robsartin.segue.port.AffinityStore;
import com.robsartin.segue.port.AssertionLog;
import com.robsartin.segue.port.EntityResolver;
import com.robsartin.segue.port.ExpandContext;
import com.robsartin.segue.port.ExpandResult;
import com.robsartin.segue.port.GraphStore;
import com.robsartin.segue.port.IdentityMerge;
import com.robsartin.segue.port.SourceAdapter;
import com.robsartin.segue.port.SourceAdapters;
import com.robsartin.segue.sqlite.SqliteAffinityStore;
import com.robsartin.segue.sqlite.SqliteAssertionLog;
import com.robsartin.segue.tinker.TinkerGraphStore;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Issue #234, reachability: the supported flow really does produce the log that cannot boot, and
 * which half of the Wikidata adapter produced the edge decides whether it does.
 *
 * <p>{@code WikidataSourceAdapter.expand} fills {@link ExpandResult#neighbors()} from the REVERSE
 * pass alone (ADR 36); the forward pass, {@code ClaimMapper.map}, carries no identity. When a
 * neighbour's identity rides along, {@code SegueService.expandEntity} re-records it whether or not
 * the graph holds the node (issue #55) — that claim lands after the retraction, survives it, and
 * the boot is fine. When it does not, the stale graph makes {@code isNew} false so nothing is
 * fetched either, and the edge is appended alone.
 *
 * <p><b>Reaching either needs two writers on one database</b>, which is not the single writer ADR
 * 24 assumes: no retraction can be appended from inside the server ({@code
 * ToolSurfaceTest.retractIsNotATool}), so {@code IngestService.retract} below stands in for {@code
 * ./gradlew retractEntity} running in its own process (ADR 60) against a database a server is still
 * holding open. Restart in between and the rebuilt graph refuses the edge correctly (#233).
 */
class AnExpansionAfterARetractionTest {

  /** Invented, ADR 58's leading zero. The seed being expanded. */
  private static final String WREN = "Q0900101";

  /** The neighbour that is retracted while the running graph goes on holding its node. */
  private static final String KETTLES = "Q0900102";

  private static final Instant NOW = Instant.parse("2026-09-04T09:00:00Z");
  private static final Provenance WIKIDATA = new Provenance("wikidata", "S-1", NOW, 0.80);
  private static final AssertionRecord EDGE =
      new AssertionRecord(WREN, KETTLES, "INFLUENCED_BY", null, null, WIKIDATA);
  private static final NodeAssertion KETTLES_CLAIM =
      new NodeAssertion(KETTLES, NodeKind.PERSON, "Kettles Nye", WIKIDATA);

  @Test
  @DisplayName("an expansion after a retraction leaves a log that cannot boot when the source names no neighbour")
  void shouldLeaveALogThatCannotBootWhenTheSourceNamesNoNeighbour(@TempDir Path dir) {
    Path db = dir.resolve("segue.db");
    expandAfterRetracting(db, ExpandResult.of(List.of(EDGE)));

    assertThatThrownBy(() -> boot(db))
        .as("the edge names an endpoint the fold holds no node for")
        .hasMessageContaining("sequence 4");
  }

  @Test
  @DisplayName("the same expansion leaves a bootable log when the source volunteers the neighbour's identity")
  void shouldLeaveABootableLogWhenTheSourceVolunteersTheNeighboursIdentity(@TempDir Path dir) {
    Path db = dir.resolve("segue.db");
    expandAfterRetracting(
        db, new ExpandResult(List.of(EDGE), List.of(KETTLES_CLAIM), false, false));

    try (AssertionLog reopened = new SqliteAssertionLog(db);
        GraphStore rebuilt = new TinkerGraphStore()) {
      assertThatCode(() -> GraphProjector.project(reopened, rebuilt, IdentityMerge.NONE))
          .as("issue #55's unconditional identity refresh lands after the retraction")
          .doesNotThrowAnyException();
      assertThat(rebuilt.node(KETTLES)).isPresent();
      assertThat(rebuilt.edgeCount()).isOne();
    }
  }

  /**
   * Seed, retract the neighbour behind the server's back, then expand through the facade. The
   * expansion is asserted to have succeeded: nothing refused the edge, so nothing tells the caller
   * anything is wrong, which is why the boot diagnosis is the thing that has to be good.
   */
  private static void expandAfterRetracting(Path db, ExpandResult result) {
    try (AssertionLog log = new SqliteAssertionLog(db);
        GraphStore graph = new TinkerGraphStore();
        AffinityStore affinity = SqliteAffinityStore.inMemory()) {
      IngestService ingest = new IngestService(log, graph, IdentityMerge.NONE);
      ingest.record(new NodeAssertion(WREN, NodeKind.PERSON, "Wren Alderman", WIKIDATA));
      ingest.record(KETTLES_CLAIM);
      IngestService.retract(
          log, new Retraction(KETTLES, "resolved to the wrong entity", NOW.plusSeconds(60)));

      SegueService service =
          new SegueService(
              new NothingResolver(),
              graph,
              ingest,
              new SourceAdapters(List.of(new FixedAdapter(result))),
              affinity,
              Clock.fixed(NOW, ZoneOffset.UTC));

      ToolResult<SegueService.ExpansionSummary> expansion = service.expandEntity(WREN, 10);

      assertThat(expansion.outcome()).isEqualTo(ToolResult.Outcome.OK);
      assertThat(expansion.payload().edgesAdded())
          .as("the edge was accepted, so the expansion reports nothing unusual")
          .isOne();
    }
  }

  private static long boot(Path db) {
    try (AssertionLog reopened = new SqliteAssertionLog(db);
        GraphStore rebuilt = new TinkerGraphStore()) {
      return GraphProjector.project(reopened, rebuilt, IdentityMerge.NONE);
    }
  }

  /** One source, one canned answer — the shape of the two halves of the Wikidata adapter. */
  private record FixedAdapter(ExpandResult result) implements SourceAdapter {

    @Override
    public String id() {
      return "wikidata";
    }

    @Override
    public boolean supports(NodeKind kind) {
      return true;
    }

    @Override
    public ExpandResult expand(NodeRecord seed, ExpandContext ctx) {
      return result;
    }
  }

  /**
   * Identifies nothing. Deliberate: the point is that a stale node stops the fetch being reached
   * at all, so a resolver that could rescue the neighbour would hide the defect.
   */
  private static final class NothingResolver implements EntityResolver {

    @Override
    public String id() {
      return "wikidata";
    }

    @Override
    public List<Candidate> search(String query, NodeKind kind, int limit) {
      return List.of();
    }

    @Override
    public Optional<NodeAssertion> fetch(String qid) {
      return Optional.empty();
    }
  }
}
```

- [ ] **Step 2 — run it.** `./gradlew test --tests 'com.robsartin.segue.mcp.AnExpansionAfterARetractionTest'`. Both pass; this pins existing behaviour, as Task 1 did. If `SegueService`'s constructor or `EntityResolver` has moved since this plan was written, fix the wiring — do not weaken an assertion to make it compile.

- [ ] **Step 3 — positive control.** In `shouldLeaveALogThatCannotBootWhenTheSourceNamesNoNeighbour`, temporarily pass `new ExpandResult(List.of(EDGE), List.of(KETTLES_CLAIM), false, false)` instead of `ExpandResult.of(List.of(EDGE))`. The test must fail, because the boot then succeeds. That is the whole finding — *which pass produced the edge is what decides* — observed rather than asserted from reading the adapter. Quote the failure, then restore.

- [ ] **Step 4 — gate and commit.** `./gradlew spotlessApply`, then the full gate, blocking. `git status`, `git add src/test/java/com/robsartin/segue/mcp/AnExpansionAfterARetractionTest.java` with stderr visible, commit: `An expansion after a retraction poisons the log only when no source names the neighbour (#234)`.

---

### Task 3: The tool tells the operator to close the window it opens

**Files:** `src/main/java/com/robsartin/segue/retract/RetractRun.java`, `src/test/java/com/robsartin/segue/retract/RetractRunTest.java`.

The one production change in this plan. The closing note currently *describes* the two-writer window (*"a server that is up still holds the old edges until it restarts"*); it says nothing about what happens if you keep ingesting into that server.

- [ ] **Step 1 — RED.** Add to `RetractRunTest`:

```java
  @Test
  @DisplayName("the closing note tells the operator to restart before anything else is ingested")
  void shouldTellTheOperatorToRestartBeforeIngestingWhenARetractionIsAppended() {
    seedAWronglyExpandedEntity();

    run.run(options(WRONG, "resolved to the painters, not the band", false), notes::add);

    assertThat(notes)
        .as("a server still holding a node for the retracted id will accept a claim naming it")
        .anyMatch(
            note ->
                note.contains("restart it before anything else is ingested")
                    && note.contains(WRONG));
  }
```

- [ ] **Step 2 — verify it fails for the right reason.** `./gradlew test --tests 'com.robsartin.segue.retract.RetractRunTest'`. Expect an AssertJ failure saying no element matched the predicate, listing the notes that were emitted — the old sentence among them. **Quote it.** A compile error is not a red; if it will not compile, fix the test and re-run before going on.

- [ ] **Step 3 — GREEN.** In `RetractRun.run`, replace the note emitted after `IngestService.retract(...)` with:

```java
    notes.accept(
        "appended. The running graph is rebuilt from the log at the next boot (ADR 24), so a"
            + " server that is up still holds the old edges until it restarts — restart it before"
            + " anything else is ingested: until then its graph still holds a node for "
            + options.qid()
            + ", so a claim naming that id passes the ingest gate, is appended, and the next boot"
            + " will not get past that row (#234)");
```

Nothing else in `RetractRun` changes. The wording deliberately avoids quoting either boot message, so it stays true both before and after #228 lands.

- [ ] **Step 4 — verify it passes**, same fast loop, and quote the run. Then check nothing else asserted the old sentence: `grep -rn "still holds the old edges" src docs` — the developer guide paraphrases it at the retraction chapter and quotes `OwnRun`'s different line elsewhere, so nothing should red. Report what the grep found.

- [ ] **Step 5 — gate and commit.** `./gradlew spotlessApply`, full gate, blocking. `git status`, then `git add src/main/java/com/robsartin/segue/retract/RetractRun.java src/test/java/com/robsartin/segue/retract/RetractRunTest.java` with stderr visible, commit: `The retraction tool says to restart before ingesting again, not just that the graph is stale (#234)`.

---

### Task 4: The ruling, recorded where the residual was filed

**Files:** `docs/adr/0024-sqlite-assertion-log.md` (append only), `docs/developer-guide.md`.

ADR 24's 2026-09-04 amendment for #233 filed this residual, so this is where the ruling belongs. **ADR 44 gets nothing**: its *"a running server is stale until it restarts"* consequence is unchanged and still correct, and mirroring a ruling into a second document is how two documents come to disagree.

**No unit-testable behaviour here**, and that is said out loud rather than left implied: verification is the documentation gates — `arch/AdrIndexTest`, `arch/DocumentationLinksTest` and `arch/DeveloperGuideEnumerationsTest` — run under the full gate with `--rerun-tasks`, plus a read-through against the constraint that no sentence above the amendment is edited.

- [ ] **Step 1 — append to `docs/adr/0024-sqlite-assertion-log.md`, at the very end, editing nothing above it:**

```markdown
**Amendment (2026-09-04, issue #234): the second case named just above is closed by a decision, a
boot diagnosis and an instruction — not by a second gate. `record` goes on asking the running
graph.**

Reproduced first, on a temp-file log holding `node`, `node`, `retract`, then a sourced edge naming
the retracted id: `record` accepted the edge, and two consecutive boots both threw `replay failed at
sequence 4`, wrapping `assertion references unknown entity … - upsert the node first`.

**The witness that would see it is the log's fold, and it costs too much to ask per claim.**
`AssertionLog` offers one read, `readAll`, and the fold rules are computed over the whole list.
Measured on a synthetic log of 131,000 rows in a temp file — about the size this log has reached —
`readAll` took 407 ms and the fold's own passes 116 ms. `segue.expand.max-new-edges` defaults to 200
and an expansion records once per edge and once more per neighbour a source described, so a single
`expand_entity` would pay that half-second up to four hundred times, and would go on paying more of
it as the log grows. Issue #228 asks the same question of the same method on the owner path and can
afford to, because its caller is a dev tool that appends one row per invocation.

**And reaching this at all takes two writers on one database, which the consequence above already
says is not supported.** Nothing inside the server can append a retraction — the MCP surface has no
such tool by ADR 26 and ADR 44, and `IngestService.retract`'s only production caller is
`./gradlew retractEntity`, which requires `--db` (ADR 60) and therefore runs in its own JVM. So the
running graph can only be stale about a retraction another process wrote. Restart the server between
retracting and ingesting again and the rebuilt graph holds no node for the retracted id, which is
the case the amendment above already closes.

**What is done instead, and it is three things.** The tool that opens the window says how to close
it: `RetractRun`'s closing note now tells the operator to restart before anything else is ingested,
and names the consequence of not doing so. Issue #228's boot pre-flight names the row and the repair
for a log that already carries one — confirmed on that branch against this exact log, which it
refuses by sequence number without a change of its own, because the check is a property of the log
rather than of the third layer. And the repair is one command: retracting the same id again reaches
backwards past the edge, so the edge stops projecting, the boot succeeds and every row stays in the
log. It is not refused as *"nothing to retract"* either — `RetractRun` counts what survives, and the
edge appended after the first retraction does.

**Alternatives considered, and why each lost.**

- **Ask the fold as well as the graph** — the correct witness, and the one #228 promotes. Lost on
  the measurement above, and only on that. A cache does not rescue it: the server already knows
  every row it appended itself, so the only row a cache would have to see is exactly the one another
  process wrote.
- **Have `retract` update the running graph, so the live witness is not stale.** ADR 44's reasons
  are unchanged — `GraphStore` cannot remove anything, widening the port that keeps the engine
  choice reversible (ADR 18) is what ADR 41 refused for a dev tool, and the retraction tool is
  forbidden a `GraphStore` as a type so that satisfying a constructor could never become the reason
  it held one. There is now a reason that decides it on its own: the tool is a different process, so
  it would update a graph in the wrong JVM and leave the server's exactly as stale.
- **Ask the graph, and also whether the log holds a retraction naming the endpoint** — the cheap
  positional check, and it is wrong. A retraction reaches backwards only, so ADR 44's own way back
  in is to add the entity again; measured on `node, node, retract, node, edge`, that log boots and
  holds the edge. A gate keyed on "a retraction names this id" refuses a legal claim about a legally
  re-added entity. The correct positional question — does the fold hold a node for this id *now* —
  is the first alternative under another name, and it skips only the 116 ms half of the cost.
- **Widen `AssertionLog` with a narrow indexed read**, so the question can be asked cheaply. Lost on
  where the rule would then live: the answer would be computed in SQL, outside `domain`, giving
  "does a node exist for this id" a second home that can disagree with the fold's — the shape ADR
  42, ADR 44 and issue #228 have each spent an issue removing.
- **Guard in `SegueService.expandEntity`, the one path this is reachable through.** Rejected here
  for the reason the amendment above rejects it: a check in front of one caller is not a gate.

**Consequences, taken deliberately.**

- **A row of this shape is still writable.** The operator is told to close the window at the moment
  he opens it, the boot names the row and the repair if he does not, and the repair deletes nothing.
- **The census has never seen one.** Measured 2026-09-04: the real log holds no retractions at all.
- **`record`'s witness is still the running graph**, so `UnknownEndpointException`'s message — *"the
  graph holds no node for"* — stays accurate, and its note about which projection each caller asks
  stands unchanged.
- **Two writers on one file remains an assumption rather than an enforcement.** Nothing detects
  `retractEntity` running against a live server's database. Enforcing it would reach `own` and
  `seed` too, and is not decided here.
```

- [ ] **Step 2 — extend the developer guide's retraction chapter.** Under `### The graph you are looking at right now is stale`, after the existing paragraph, add:

```markdown
**Restart it before you ingest anything else.** The ingest gate asks the running graph
(`GraphStore.node`, issue #233), so until the server restarts it will accept a claim naming the
entity you just retracted — and the next boot cannot get past that row. Opening that window takes
two writers on one database, which is not the single writer [ADR 24](adr/0024-sqlite-assertion-log.md)
assumes: retracting is a dev tool in its own process
([ADR 60](adr/0060-the-claim-tools-require-an-explicit-database.md)), so a server left running
through a retraction is the only way there. If a log already carries such a row, retract the same id
again — it reaches backwards past the edge, the edge stops projecting, and nothing is deleted. ADR
24's 2026-09-04 amendment for issue #234 is the ruling, the measurement and the alternatives.
```

- [ ] **Step 3 — verify.** Run the documentation gates first as a fast loop: `./gradlew test --tests 'com.robsartin.segue.arch.*'`. Then confirm by reading `git diff docs/adr/0024-sqlite-assertion-log.md` that **every hunk is an addition at the end of the file** — an ADR is immutable and this must be visibly true, not merely intended.

- [ ] **Step 4 — gate and commit.** `./gradlew spotlessApply`, full gate, blocking. `git status`, then `git add docs/adr/0024-sqlite-assertion-log.md docs/developer-guide.md` with stderr visible, commit: `Record why the ingest gate keeps asking the running graph after a retraction (#234)`.

---

### Task 5: Rebase onto main once #228 has merged, and reconcile

**Files:** `src/test/java/com/robsartin/segue/ingest/ARetractionTheRunningGraphHasNotSeenTest.java`, `src/test/java/com/robsartin/segue/mcp/AnExpansionAfterARetractionTest.java`, `docs/adr/0024-sqlite-assertion-log.md`.

**Do this task last, and only after #228 is on `main`.** If #234 is ready first, say so and stop: this branch may merge ahead of #228, in which case the reconciliation below moves to #228's side and this task is closed as not needed. Waiting is preferred — the sentences below are this issue's claims, and they should be verified by this issue.

- [ ] **Step 1 — rebase.** `git fetch origin`, then `git rebase origin/main`. Confirm `git log --oneline -1 origin/main` names #228's merge.

- [ ] **Step 2 — run the two new test classes unchanged and read what moved.** `./gradlew test --tests 'com.robsartin.segue.ingest.ARetractionTheRunningGraphHasNotSeenTest' --tests 'com.robsartin.segue.mcp.AnExpansionAfterARetractionTest'`. They should still pass: both boot assertions match on `sequence 4`, which appears in `GraphProjector`'s old wrapper (`replay failed at sequence 4`) and in #228's pre-flight (`sequence 4: … names …, which no node stands for`) alike. That was checked against branch `228-ready` while this plan was written; if anything reds, the message moved and the assertion is what has to follow it.

- [ ] **Step 3 — tighten both boot assertions onto the diagnosis #228 now gives.** Replace each `hasMessageContaining("sequence 4")` with the three things the operator actually needs, which only hold post-#228:

```java
    assertThatThrownBy(() -> boot(db))
        .hasMessageContaining("sequence 4")
        .hasMessageContaining(KETTLES)
        .hasMessageContaining("retract the endpoint");
```

Verify it fails before it passes: run it once against the *pre*-#228 wording by planting `throw new IllegalStateException("replay failed at sequence 4")` at the top of `GraphProjector.project` — the two added lines must fail, the first must still pass. Quote the failure, remove the plant, re-run green.

- [ ] **Step 4 — add one sentence to the #234 amendment**, naming what the boot now says. The amendment is not yet on `main`, so this is still writing it rather than editing a published decision — say so in the commit message. After *"confirmed on that branch against this exact log"*, the branch is no longer a branch: change that clause to name the merged behaviour, and add the message's own shape:

```markdown
The boot now refuses the whole log before it applies anything, listing each offending row by
sequence number, the id nothing stands for, and the repair — `retract the endpoint, which withdraws
the edge under ADR 44 without deleting anything`. `ARetractionTheRunningGraphHasNotSeenTest` holds
it to all three.
```

- [ ] **Step 5 — full gate and commit.** `./gradlew spotlessApply`, then `SEGUE_REQUIRE_BROWSER=true SEGUE_REQUIRE_GRAPHVIZ=true ./gradlew check --rerun-tasks`, blocking. `git status`, add the three files by explicit path with stderr visible, commit: `Tighten the boot assertions onto the diagnosis #228 landed (#234)`.
