# Increment 3: SourceAdapter SPI and Wikidata Ingest — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fill the graph with real data from Wikidata, behind two source-agnostic SPIs, so that adding a second source later touches nothing but its own adapter.

**Architecture:** Two ports — `SourceAdapter` (expansion) and `EntityResolver` (search and fetch) — because a similarity source expands but has nothing to resolve (ADR 25). `IngestService` becomes the live write path: log first, then graph, sharing its apply step with `GraphProjector` so replay and live ingest cannot drift. The Wikidata adapter is plain Java over `java.net.http.HttpClient` with no Spring, so it is testable against a stub server in-process.

**Tech Stack:** Java 21 (toolchain 25), Gradle, JUnit 6.1.3, AssertJ, ArchUnit 1.5.0, Jackson 2.22.2, the JDK's own `HttpClient` and `HttpServer`. No Spring in this increment.

## Global Constraints

- **Base package** `com.robsartin.segue`. Build `group` is `com.robsartin`.
- **Java toolchain 25, `options.release = 21`.**
- **All versions live in `gradle/libs.versions.toml`.** Jackson is exactly `2.22.2`.
- **Coverage gates: line > 0.80, branch > 0.65, instruction > 0.80.** Never lower them.
- **No `System.out`, `System.err` or `printStackTrace` in `src/main`** — ArchUnit enforces all three.
- **No Spring anywhere in this increment.** `wikidata` must stay a plain-Java package.
- **`domain` depends on nothing outside `java..`/`javax..` and itself. `port` depends only on `domain`.**
- **Adapters never depend on each other, nor upward on `ingest`/`mcp`/`app`.**
- **Only `ingest` may call `GraphStore.record`, `GraphStore.upsertNode` or `AssertionLog.append`** — existing ArchUnit rule `onlyIngestAppliesClaimsToTheGraph`.
- **Outbound User-Agent identifies segue by repository URL, never by an email address** (ADR 16, ADR 30).
- **Never log an affinity note.** Not applicable yet, but the rule stands.
- `./gradlew spotlessApply` before every commit; **`./gradlew check` green at every commit** — never commit a red build.
- Conventional Commits.
- **Work in `~/code/segue-wt/3-wikidata-ingest` on branch `3-wikidata-ingest`.** Other worktrees belong to other sessions — do not touch `~/code/segue`.

## Note on shape

Tasks 1–5 are the source-agnostic half: two ports, `IngestService`, and a fixture-backed
adapter that proves the SPI has more than one implementation. They involve no network and
no Wikidata. Tasks 6–12 are the Wikidata adapter itself. **That boundary is a clean split
point** — if this grows too large to review in one PR, land 1–5 first.

---

## File Structure

**Created:**

| Path | Responsibility |
|---|---|
| `domain/Candidate.java` | A search hit: qid, label, disambiguating description, kind |
| `port/SourceAdapter.java` | Expansion SPI |
| `port/EntityResolver.java` | Resolution SPI |
| `port/ExpandContext.java` | Bounds passed into an expansion |
| `ingest/IngestService.java` | The live write path: log, then graph |
| `wikidata/WikidataClient.java` | HTTP, retry/backoff, User-Agent, JSON parsing |
| `wikidata/WikidataEntityResolver.java` | `wbsearchentities` and `wbgetentities` |
| `wikidata/WikidataSourceAdapter.java` | Claims to assertions, with a bounded fan-out |
| `wikidata/ClaimMapper.java` | One entity's claims JSON to `AssertionRecord`s |
| `wikidata/KindMapper.java` | `P31` to `NodeKind`, with an honest fallback |
| `src/test/.../fixture/FixtureSourceAdapter.java` | The SPI's second implementation |
| `src/test/.../wikidata/StubWikidataServer.java` | In-process HTTP stub over the JDK's `HttpServer` |
| `src/test/resources/wikidata/*.json` | Recorded responses |

**Modified:** `ingest/GraphProjector.java`, `arch/ArchitectureTest.java`, `build.gradle.kts`, `gradle/libs.versions.toml`, `CLAUDE.md`

---

### Task 1: Candidate, ExpandContext and the two SPIs

**Files:**
- Create: `src/main/java/com/robsartin/segue/domain/Candidate.java`
- Create: `src/main/java/com/robsartin/segue/port/ExpandContext.java`
- Create: `src/main/java/com/robsartin/segue/port/SourceAdapter.java`
- Create: `src/main/java/com/robsartin/segue/port/EntityResolver.java`
- Create: `src/test/java/com/robsartin/segue/domain/CandidateTest.java`

**Interfaces:**
- Consumes: `NodeKind`, `NodeRecord`, `NodeAssertion`, `AssertionRecord` (all on main)
- Produces: `Candidate(String qid, String label, String description, NodeKind kind)`; `ExpandContext(int maxNewEdges)`; `SourceAdapter` with `id()`, `supports(NodeKind)`, `expand(NodeRecord, ExpandContext)`; `EntityResolver` with `id()`, `search(String, NodeKind, int)`, `fetch(String)`. Every later task consumes these.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/robsartin/segue/domain/CandidateTest.java`:

```java
package com.robsartin.segue.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNoException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A search hit, before anything is written. The description is what makes disambiguation
 * possible — "Q11571, Spanish painter" is answerable by a human, "Q11571" is not.
 */
class CandidateTest {

  @Test
  @DisplayName("a candidate validates its qid the same way a node does")
  void rejectsNonWikidataQid() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new Candidate("picasso", "Pablo Picasso", "Spanish painter", NodeKind.PERSON));
  }

  @Test
  @DisplayName("a missing description is allowed — Wikidata does not always have one")
  void allowsNullDescription() {
    assertThatNoException()
        .isThrownBy(() -> new Candidate("Q5593", "Pablo Picasso", null, NodeKind.PERSON));
  }

  @Test
  @DisplayName("it renders for disambiguation, description first when present")
  void rendersForDisambiguation() {
    assertThat(new Candidate("Q5593", "Pablo Picasso", "Spanish painter", NodeKind.PERSON).describe())
        .isEqualTo("Q5593 — Pablo Picasso (Spanish painter) [PERSON]");
    assertThat(new Candidate("Q5593", "Pablo Picasso", null, NodeKind.PERSON).describe())
        .isEqualTo("Q5593 — Pablo Picasso [PERSON]");
  }
}
```

- [ ] **Step 2: Run it and confirm it fails to compile**

```bash
./gradlew compileTestJava
```

Expected: FAIL — `Candidate` does not exist.

- [ ] **Step 3: Create Candidate**

Create `src/main/java/com/robsartin/segue/domain/Candidate.java`:

```java
package com.robsartin.segue.domain;

import java.util.Objects;

/**
 * One possible answer to "which entity did you mean".
 *
 * <p>Not a {@link NodeRecord}: nothing has been decided or written yet. The {@code description} is
 * the field that makes a choice possible — Wikidata's short gloss is what separates the painter
 * from the film named after him — so it is carried even though the graph never stores it.
 */
public record Candidate(String qid, String label, String description, NodeKind kind) {

  public Candidate {
    Objects.requireNonNull(label, "label");
    Objects.requireNonNull(kind, "kind");
    // Reuse NodeRecord's qid rule rather than restating it, so the two cannot drift.
    new NodeRecord(qid, kind, label);
  }

  /** Human-readable form for disambiguation. */
  public String describe() {
    return description == null
        ? qid + " — " + label + " [" + kind + "]"
        : qid + " — " + label + " (" + description + ") [" + kind + "]";
  }
}
```

- [ ] **Step 4: Create ExpandContext**

Create `src/main/java/com/robsartin/segue/port/ExpandContext.java`:

```java
package com.robsartin.segue.port;

/**
 * Bounds on a single expansion.
 *
 * <p>Deliberately one field. The MCP specification requires servers to rate-limit tool
 * invocations, and an unbounded expansion of a well-connected entity is the obvious way to
 * violate that by accident. More knobs arrive when something needs them, not before.
 *
 * @param maxNewEdges the most assertions an adapter may return from one call
 */
public record ExpandContext(int maxNewEdges) {

  public ExpandContext {
    if (maxNewEdges <= 0) {
      throw new IllegalArgumentException("maxNewEdges must be positive, got: " + maxNewEdges);
    }
  }

  /** A sensible ceiling for an interactive expansion. */
  public static ExpandContext defaults() {
    return new ExpandContext(200);
  }
}
```

- [ ] **Step 5: Create the two SPIs**

Create `src/main/java/com/robsartin/segue/port/SourceAdapter.java`:

```java
package com.robsartin.segue.port;

import com.robsartin.segue.domain.AssertionRecord;
import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.domain.NodeRecord;
import java.util.List;

/**
 * A source of relationships.
 *
 * <p>Adapters emit assertions and know nothing about storage — see
 * docs/adr/0019-assertion-log-source-of-truth.md. An adapter that could write directly would be
 * able to skip the log, which is why ArchUnit forbids it rather than a comment discouraging it.
 *
 * <p>Design rule from CLAUDE.md: adding a source must not require touching the graph layer.
 */
public interface SourceAdapter {

  /** Stable identifier, and the {@code sourceId} every assertion this adapter emits will carry. */
  String id();

  /** Whether this source has anything to say about entities of a given kind. */
  boolean supports(NodeKind kind);

  /**
   * Claims this source makes about {@code seed}, bounded by {@code ctx}.
   *
   * <p>Implementations return what they successfully gathered rather than throwing on partial
   * failure: the caller is a language model, and a partial result it can see beats an exception it
   * can only retry.
   */
  List<AssertionRecord> expand(NodeRecord seed, ExpandContext ctx);
}
```

Create `src/main/java/com/robsartin/segue/port/EntityResolver.java`:

```java
package com.robsartin.segue.port;

import com.robsartin.segue.domain.Candidate;
import com.robsartin.segue.domain.NodeAssertion;
import com.robsartin.segue.domain.NodeKind;
import java.util.List;
import java.util.Optional;

/**
 * A source that can turn a name into an identity.
 *
 * <p>Separate from {@link SourceAdapter} on purpose (ADR 25). Resolution and expansion are
 * different capabilities with different implementors: a statistical similarity source expands but
 * has nothing to resolve, and folding both into one interface would force it to throw.
 */
public interface EntityResolver {

  /** Stable identifier, matching the {@link SourceAdapter#id()} of the same source. */
  String id();

  /**
   * Candidates for a free-text query, best match first.
   *
   * @param kind narrow to one kind, or null for any
   */
  List<Candidate> search(String query, NodeKind kind, int limit);

  /** The source's claim about one entity, or empty if it does not know the identifier. */
  Optional<NodeAssertion> fetch(String qid);
}
```

- [ ] **Step 6: Verify**

```bash
./gradlew test --tests '*CandidateTest'
```

Expected: PASS, 3 tests.

- [ ] **Step 7: Full gate and commit**

```bash
./gradlew spotlessApply && ./gradlew clean check
git add src/main/java/com/robsartin/segue/domain/Candidate.java src/main/java/com/robsartin/segue/port src/test/java/com/robsartin/segue/domain/CandidateTest.java
git commit -m "feat: add the SourceAdapter and EntityResolver SPIs"
```

---

### Task 2: IngestService, and sharing apply with GraphProjector

**Files:**
- Create: `src/main/java/com/robsartin/segue/ingest/IngestService.java`
- Modify: `src/main/java/com/robsartin/segue/ingest/GraphProjector.java`
- Create: `src/test/java/com/robsartin/segue/ingest/IngestServiceTest.java`

**Interfaces:**
- Consumes: `AssertionLog`, `GraphStore`, `LoggedAssertion` (all on main)
- Produces: `IngestService(AssertionLog, GraphStore)` with `record(LoggedAssertion)` and `recordAll(List<LoggedAssertion>)`; package-private `static void apply(GraphStore, LoggedAssertion)` shared with `GraphProjector`.

- [ ] **Step 1: Read what GraphProjector does today**

```bash
cat src/main/java/com/robsartin/segue/ingest/GraphProjector.java
```

It already switches over `LoggedAssertion` to apply a claim. That switch is about to have a second caller, and two copies would be free to drift — a rebuilt graph silently differing from a live one is exactly the failure ADR 19 exists to prevent.

- [ ] **Step 2: Write the failing test**

Create `src/test/java/com/robsartin/segue/ingest/IngestServiceTest.java`:

```java
package com.robsartin.segue.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.robsartin.segue.domain.AssertionRecord;
import com.robsartin.segue.domain.LoggedAssertion;
import com.robsartin.segue.domain.NodeAssertion;
import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.domain.Provenance;
import com.robsartin.segue.port.AssertionLog;
import com.robsartin.segue.port.GraphStore;
import com.robsartin.segue.sqlite.SqliteAssertionLog;
import com.robsartin.segue.tinker.TinkerGraphStore;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The live write path is one-way and ordered: log first, then graph.
 *
 * <p>The ordering is the whole point (ADR 19). It is deliberately not atomic, and the direction
 * of that non-atomicity is chosen: if the graph write fails, the log is ahead and a restart
 * replays it. The reverse would lose the claim for good.
 */
class IngestServiceTest {

  private static final Provenance WIKIDATA =
      new Provenance("wikidata", "S-1", Instant.parse("2026-08-24T09:00:00Z"), 1.0);

  private AssertionLog log;
  private GraphStore graph;
  private IngestService ingest;

  @BeforeEach
  void setUp() {
    log = SqliteAssertionLog.inMemory();
    graph = new TinkerGraphStore();
    ingest = new IngestService(log, graph);
  }

  @AfterEach
  void tearDown() {
    graph.close();
    log.close();
  }

  @Test
  @DisplayName("a node claim lands in the log and the graph")
  void recordsNodeInBoth() {
    NodeAssertion node = new NodeAssertion("Q5593", NodeKind.PERSON, "Pablo Picasso", WIKIDATA);

    ingest.record(node);

    assertThat(log.readAll()).containsExactly(node);
    assertThat(graph.node("Q5593")).isPresent();
  }

  @Test
  @DisplayName("a batch is recorded in order")
  void recordAllPreservesOrder() {
    List<LoggedAssertion> batch =
        List.of(
            new NodeAssertion("Q1", NodeKind.PERSON, "A", WIKIDATA),
            new NodeAssertion("Q2", NodeKind.GROUP, "B", WIKIDATA),
            new AssertionRecord("Q1", "Q2", "MEMBER_OF", null, null, WIKIDATA));

    ingest.recordAll(batch);

    assertThat(log.readAll()).containsExactlyElementsOf(batch);
    assertThat(graph.edgeCount()).isEqualTo(1);
  }

  @Test
  @DisplayName("when the graph rejects a claim the log has already kept it")
  void logLeadsTheGraph() {
    // TinkerGraphStore.record calls requireVertex, which throws when an endpoint is unknown.
    AssertionRecord dangling =
        new AssertionRecord("Q404", "Q405", "MEMBER_OF", null, null, WIKIDATA);

    assertThatThrownBy(() -> ingest.record(dangling)).isInstanceOf(IllegalStateException.class);

    assertThat(log.readAll()).containsExactly(dangling);
  }

  @Test
  @DisplayName("live ingest and replay produce the same graph")
  void liveAndReplayAgree() {
    // Both go through IngestService.apply. If they ever diverged, a rebuilt graph would
    // silently differ from the one it replaced — the failure ADR 19 exists to prevent.
    ingest.record(new NodeAssertion("Q1", NodeKind.PERSON, "A", WIKIDATA));
    ingest.record(new NodeAssertion("Q2", NodeKind.GROUP, "B", WIKIDATA));
    ingest.record(new AssertionRecord("Q1", "Q2", "MEMBER_OF", null, null, WIKIDATA));

    try (GraphStore rebuilt = new TinkerGraphStore()) {
      GraphProjector.project(log, rebuilt);

      assertThat(rebuilt.edgeCount()).isEqualTo(graph.edgeCount());
      assertThat(rebuilt.node("Q1")).isEqualTo(graph.node("Q1"));
      assertThat(rebuilt.edges("Q1")).hasSameSizeAs(graph.edges("Q1"));
    }
  }
}
```

- [ ] **Step 3: Run it and confirm it fails**

```bash
./gradlew test --tests '*IngestServiceTest'
```

Expected: FAIL — `IngestService` does not exist.

- [ ] **Step 4: Write IngestService**

Create `src/main/java/com/robsartin/segue/ingest/IngestService.java`:

```java
package com.robsartin.segue.ingest;

import com.robsartin.segue.domain.AssertionRecord;
import com.robsartin.segue.domain.LoggedAssertion;
import com.robsartin.segue.domain.NodeAssertion;
import com.robsartin.segue.port.AssertionLog;
import com.robsartin.segue.port.GraphStore;
import java.util.List;
import java.util.Objects;

/**
 * The only thing in the system that writes.
 *
 * <p>Source adapters and, later, MCP tools hand claims to this and never touch a store. ArchUnit
 * enforces that (rule {@code onlyIngestAppliesClaimsToTheGraph}), which turns ADR 19's invariant
 * from a convention into a build failure.
 *
 * <p><b>Order matters and is not an accident.</b> The log is appended first, then the graph is
 * updated, and the two are deliberately not atomic. If the graph update fails, the log is ahead —
 * the recoverable direction, because a restart replays it. The reverse ordering would lose the
 * claim permanently and leave the log authoritative in name only.
 */
public final class IngestService {

  private final AssertionLog log;
  private final GraphStore graph;

  public IngestService(AssertionLog log, GraphStore graph) {
    this.log = Objects.requireNonNull(log, "log");
    this.graph = Objects.requireNonNull(graph, "graph");
  }

  /** Append one claim to the log, then apply it to the graph. */
  public void record(LoggedAssertion assertion) {
    Objects.requireNonNull(assertion, "assertion");
    log.append(assertion);
    apply(graph, assertion);
  }

  /** Record a batch in order; each claim is logged and applied before the next is considered. */
  public void recordAll(List<LoggedAssertion> assertions) {
    Objects.requireNonNull(assertions, "assertions");
    assertions.forEach(this::record);
  }

  /**
   * Apply a claim to a graph.
   *
   * <p>Shared with {@link GraphProjector} so replay and live ingest cannot drift. Two copies of
   * this switch would be free to disagree, and a rebuilt graph that silently differs from the one
   * it replaced defeats the point of having a log at all.
   */
  static void apply(GraphStore graph, LoggedAssertion assertion) {
    switch (assertion) {
      case NodeAssertion node -> graph.upsertNode(node.toNode());
      case AssertionRecord edge -> graph.record(edge);
    }
  }
}
```

- [ ] **Step 5: Point GraphProjector at the shared apply**

In `GraphProjector`, replace its inline `switch (assertion) { ... }` with a call to
`IngestService.apply(store, assertion)`. Keep everything else — the sequence numbering, the
fatal-on-failure behaviour, the return value — exactly as it is. Remove any imports that
become unused (`NodeAssertion`, `AssertionRecord`) or Spotless will fail the build.

- [ ] **Step 6: Verify both**

```bash
./gradlew test --tests '*IngestServiceTest' --tests '*GraphProjectorTest'
```

Expected: PASS on both. `GraphProjectorTest` must still pass unchanged — if it does not, the
refactor changed behaviour and that is a real finding.

- [ ] **Step 7: Full gate and commit**

```bash
./gradlew spotlessApply && ./gradlew clean check
git add src/main/java/com/robsartin/segue/ingest src/test/java/com/robsartin/segue/ingest
git commit -m "feat: add IngestService as the live write path, shared with replay"
```

---

### Task 3: A fixture-backed SourceAdapter

A port with one implementation is a class with extra steps. This is the SPI's second one, and it
lets every later task be tested without a network.

**Files:**
- Create: `src/test/java/com/robsartin/segue/fixture/FixtureSourceAdapter.java`
- Create: `src/test/java/com/robsartin/segue/fixture/FixtureSourceAdapterTest.java`

**Interfaces:**
- Consumes: `SourceAdapter`, `ExpandContext` (Task 1), `Fixture` (existing, in test sources)
- Produces: `FixtureSourceAdapter` implementing `SourceAdapter`, id `"fixture"`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/robsartin/segue/fixture/FixtureSourceAdapterTest.java`:

```java
package com.robsartin.segue.fixture;

import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.segue.domain.AssertionRecord;
import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.domain.NodeRecord;
import com.robsartin.segue.port.ExpandContext;
import com.robsartin.segue.port.SourceAdapter;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The SPI's second implementation, which is what makes it a seam rather than a formality. */
class FixtureSourceAdapterTest {

  private final SourceAdapter adapter = new FixtureSourceAdapter();

  @Test
  @DisplayName("it expands a seed to the claims the fixture makes about it")
  void expandsSeed() {
    List<AssertionRecord> claims =
        adapter.expand(
            new NodeRecord(Fixture.CAVE, NodeKind.PERSON, "Nick Cave"), ExpandContext.defaults());

    assertThat(claims).isNotEmpty();
    assertThat(claims)
        .allSatisfy(
            c -> assertThat(c.fromQid().equals(Fixture.CAVE) || c.toQid().equals(Fixture.CAVE)).isTrue());
  }

  @Test
  @DisplayName("it honours maxNewEdges rather than returning everything")
  void honoursBound() {
    List<AssertionRecord> claims =
        adapter.expand(
            new NodeRecord(Fixture.CAVE, NodeKind.PERSON, "Nick Cave"), new ExpandContext(2));

    assertThat(claims).hasSize(2);
  }

  @Test
  @DisplayName("an unknown seed yields nothing, and is not an error")
  void unknownSeedIsEmpty() {
    assertThat(
            adapter.expand(
                new NodeRecord("Q999999", NodeKind.PERSON, "Nobody"), ExpandContext.defaults()))
        .isEmpty();
  }

  @Test
  @DisplayName("it declares what it supports and identifies itself")
  void declaresItself() {
    assertThat(adapter.id()).isEqualTo("fixture");
    assertThat(adapter.supports(NodeKind.PERSON)).isTrue();
  }
}
```

- [ ] **Step 2: Run it and confirm it fails**

```bash
./gradlew test --tests '*FixtureSourceAdapterTest'
```

Expected: FAIL — `FixtureSourceAdapter` does not exist.

- [ ] **Step 3: Write it**

Create `src/test/java/com/robsartin/segue/fixture/FixtureSourceAdapter.java`:

```java
package com.robsartin.segue.fixture;

import com.robsartin.segue.domain.AssertionRecord;
import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.domain.NodeRecord;
import com.robsartin.segue.port.ExpandContext;
import com.robsartin.segue.port.SourceAdapter;
import java.util.List;

/**
 * A source backed by the Nick Cave fixture, with no network.
 *
 * <p>Exists so the SPI has a second implementation and so everything downstream of it can be
 * tested deterministically. Lives in test sources: its QIDs are placeholders and must never reach
 * a real store (ADR 22).
 */
public final class FixtureSourceAdapter implements SourceAdapter {

  @Override
  public String id() {
    return "fixture";
  }

  @Override
  public boolean supports(NodeKind kind) {
    return true;
  }

  @Override
  public List<AssertionRecord> expand(NodeRecord seed, ExpandContext ctx) {
    return Fixture.assertions().stream()
        .filter(a -> a.fromQid().equals(seed.qid()) || a.toQid().equals(seed.qid()))
        .limit(ctx.maxNewEdges())
        .toList();
  }
}
```

- [ ] **Step 4: Verify, gate, commit**

```bash
./gradlew test --tests '*FixtureSourceAdapterTest'
./gradlew spotlessApply && ./gradlew clean check
git add src/test/java/com/robsartin/segue/fixture
git commit -m "test: add a fixture-backed SourceAdapter as the SPI's second implementation"
```

---

### Task 4: KindMapper — P31 to NodeKind

**Files:**
- Create: `src/main/java/com/robsartin/segue/wikidata/KindMapper.java`
- Create: `src/test/java/com/robsartin/segue/wikidata/KindMapperTest.java`

**Interfaces:**
- Consumes: `NodeKind`
- Produces: `KindMapper.fromInstanceOf(List<String> p31Qids)` returning `NodeKind`; `KindMapper.isMapped(String qid)` returning `boolean`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/robsartin/segue/wikidata/KindMapperTest.java`:

```java
package com.robsartin.segue.wikidata;

import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.segue.domain.NodeKind;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Wikidata has tens of thousands of classes and segue has six kinds (ADR 21). This is the
 * deliberately small bridge, plus an honest fallback for everything else.
 */
class KindMapperTest {

  @Test
  @DisplayName("a human is a PERSON")
  void mapsHuman() {
    assertThat(KindMapper.fromInstanceOf(List.of("Q5"))).isEqualTo(NodeKind.PERSON);
  }

  @Test
  @DisplayName("bands and organisations are GROUPs")
  void mapsGroups() {
    assertThat(KindMapper.fromInstanceOf(List.of("Q215380"))).isEqualTo(NodeKind.GROUP);
    assertThat(KindMapper.fromInstanceOf(List.of("Q43229"))).isEqualTo(NodeKind.GROUP);
  }

  @Test
  @DisplayName("films, albums and books are WORKs")
  void mapsWorks() {
    assertThat(KindMapper.fromInstanceOf(List.of("Q11424"))).isEqualTo(NodeKind.WORK);
    assertThat(KindMapper.fromInstanceOf(List.of("Q482994"))).isEqualTo(NodeKind.WORK);
    assertThat(KindMapper.fromInstanceOf(List.of("Q7725634"))).isEqualTo(NodeKind.WORK);
  }

  @Test
  @DisplayName("cities and countries are PLACEs")
  void mapsPlaces() {
    assertThat(KindMapper.fromInstanceOf(List.of("Q515"))).isEqualTo(NodeKind.PLACE);
    assertThat(KindMapper.fromInstanceOf(List.of("Q6256"))).isEqualTo(NodeKind.PLACE);
  }

  @Test
  @DisplayName("an unmapped class falls back to CONCEPT rather than guessing")
  void unmappedFallsBackToConcept() {
    // ADR 22: record what we could not map rather than inventing a kind for it.
    assertThat(KindMapper.fromInstanceOf(List.of("Q99999999"))).isEqualTo(NodeKind.CONCEPT);
    assertThat(KindMapper.isMapped("Q99999999")).isFalse();
    assertThat(KindMapper.isMapped("Q5")).isTrue();
  }

  @Test
  @DisplayName("no instance-of claims at all is CONCEPT, not a crash")
  void emptyIsConcept() {
    assertThat(KindMapper.fromInstanceOf(List.of())).isEqualTo(NodeKind.CONCEPT);
  }

  @Test
  @DisplayName("the first mapped class wins, even when an unmapped one comes first")
  void firstMappedWins() {
    // Real entities carry several P31 values. Picking the first RECOGNISED one is what
    // stops an obscure class shadowing "human".
    assertThat(KindMapper.fromInstanceOf(List.of("Q99999999", "Q5"))).isEqualTo(NodeKind.PERSON);
  }
}
```

- [ ] **Step 2: Run and confirm it fails**

```bash
./gradlew test --tests '*KindMapperTest'
```

Expected: FAIL — `KindMapper` does not exist.

- [ ] **Step 3: Write it**

Create `src/main/java/com/robsartin/segue/wikidata/KindMapper.java`:

```java
package com.robsartin.segue.wikidata;

import com.robsartin.segue.domain.NodeKind;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Maps Wikidata's {@code P31} (instance of) onto segue's six kinds.
 *
 * <p>Wikidata has tens of thousands of classes; segue has six, permanently (ADR 21). Walking
 * {@code P279} (subclass of) upward to find a known root would be more faithful and would cost an
 * extra round trip per unknown class, on a hierarchy deep enough that the walk is its own project.
 * A short whitelist plus an honest fallback is the trade.
 *
 * <p>Unmapped classes become {@link NodeKind#CONCEPT} and are reported by
 * {@link #isMapped(String)}, so the whitelist can grow from real data rather than speculation.
 */
public final class KindMapper {

  private static final Map<String, NodeKind> BY_CLASS = new LinkedHashMap<>();

  static {
    // people
    put("Q5", NodeKind.PERSON); // human
    // groups
    put("Q215380", NodeKind.GROUP); // musical group
    put("Q43229", NodeKind.GROUP); // organization
    put("Q2088357", NodeKind.GROUP); // musical ensemble
    put("Q4830453", NodeKind.GROUP); // business
    put("Q891723", NodeKind.GROUP); // public company
    // works
    put("Q11424", NodeKind.WORK); // film
    put("Q482994", NodeKind.WORK); // album
    put("Q7725634", NodeKind.WORK); // literary work
    put("Q571", NodeKind.WORK); // book
    put("Q134556", NodeKind.WORK); // single
    put("Q7366", NodeKind.WORK); // song
    put("Q5398426", NodeKind.WORK); // television series
    put("Q47461344", NodeKind.WORK); // written work
    put("Q3305213", NodeKind.WORK); // painting
    put("Q2431196", NodeKind.WORK); // audiovisual work
    // places
    put("Q515", NodeKind.PLACE); // city
    put("Q6256", NodeKind.PLACE); // country
    put("Q532", NodeKind.PLACE); // village
    put("Q3957", NodeKind.PLACE); // town
    put("Q35657", NodeKind.PLACE); // US state
    put("Q82794", NodeKind.PLACE); // geographic region
    // events
    put("Q1656682", NodeKind.EVENT); // event
    put("Q182832", NodeKind.EVENT); // concert
    put("Q132241", NodeKind.EVENT); // festival
    put("Q198", NodeKind.EVENT); // war
  }

  private KindMapper() {}

  private static void put(String qid, NodeKind kind) {
    BY_CLASS.put(qid, kind);
  }

  /**
   * The kind implied by an entity's {@code P31} values.
   *
   * <p>Real entities carry several. The first RECOGNISED one wins, so an obscure class listed
   * ahead of "human" does not shadow it.
   */
  public static NodeKind fromInstanceOf(List<String> instanceOfQids) {
    if (instanceOfQids == null) {
      return NodeKind.CONCEPT;
    }
    return instanceOfQids.stream()
        .map(BY_CLASS::get)
        .filter(java.util.Objects::nonNull)
        .findFirst()
        .orElse(NodeKind.CONCEPT);
  }

  /** Whether this class is in the whitelist, so callers can report what they could not map. */
  public static boolean isMapped(String classQid) {
    return BY_CLASS.containsKey(classQid);
  }
}
```

- [ ] **Step 4: Verify, gate, commit**

```bash
./gradlew test --tests '*KindMapperTest'
./gradlew spotlessApply && ./gradlew clean check
git add src/main/java/com/robsartin/segue/wikidata/KindMapper.java src/test/java/com/robsartin/segue/wikidata/KindMapperTest.java
git commit -m "feat: map Wikidata P31 classes onto the six node kinds"
```

---

### Task 5: WikidataClient and the stub server

**Files:**
- Modify: `gradle/libs.versions.toml`, `build.gradle.kts`
- Create: `src/main/java/com/robsartin/segue/wikidata/WikidataClient.java`
- Create: `src/test/java/com/robsartin/segue/wikidata/StubWikidataServer.java`
- Create: `src/test/java/com/robsartin/segue/wikidata/WikidataClientTest.java`

**Interfaces:**
- Consumes: nothing from earlier tasks
- Produces: `WikidataClient(URI baseUri)` and `WikidataClient()` (defaulting to the real API) with `JsonNode get(Map<String,String> queryParams)`; `StubWikidataServer` (public, so tests in other packages can use it) implementing `AutoCloseable` with `URI baseUri()`, `void enqueueBody(String json)`, `void enqueueStatus(int status)`, `int requestCount()`, `String lastUserAgent()`.

- [ ] **Step 1: Add Jackson**

In `gradle/libs.versions.toml`, add to `[versions]`:

```toml
jackson = "2.22.2"
```

and to `[libraries]`:

```toml
jackson-databind = { module = "com.fasterxml.jackson.core:jackson-databind", version.ref = "jackson" }
```

In `build.gradle.kts`, inside `dependencies`, after the sqlite line:

```kotlin
    // Wikidata responses. Jackson rather than a second parser because Spring Boot brings
    // it in increment 4 anyway, and one JSON library is better than two.
    implementation(libs.jackson.databind)
```

- [ ] **Step 2: Write the stub server**

Create `src/test/java/com/robsartin/segue/wikidata/StubWikidataServer.java`:

```java
package com.robsartin.segue.wikidata;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * An in-process stand-in for the Wikidata API, on the JDK's own HttpServer.
 *
 * <p>No WireMock: its 4.x line is still beta, and this needs about sixty lines. Tests that talk to
 * a stub are deterministic and fast; the one test that talks to the real API is tagged {@code live}
 * and excluded from CI, because a recorded fixture cannot tell you the upstream API changed.
 */
public final class StubWikidataServer implements AutoCloseable {

  private final HttpServer server;
  private final Deque<String> bodies = new ArrayDeque<>();
  private final Deque<Integer> statuses = new ArrayDeque<>();
  private final AtomicInteger requests = new AtomicInteger();
  private volatile String lastUserAgent;

  public StubWikidataServer() {
    try {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    } catch (IOException e) {
      throw new IllegalStateException("could not start the stub server", e);
    }
    server.createContext(
        "/",
        exchange -> {
          requests.incrementAndGet();
          lastUserAgent = exchange.getRequestHeaders().getFirst("User-Agent");
          int status = statuses.isEmpty() ? 200 : statuses.poll();
          byte[] body =
              (bodies.isEmpty() ? "{}" : bodies.poll()).getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(status, body.length);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
          }
        });
    server.start();
  }

  public URI baseUri() {
    return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
  }

  /** Queue one response body, consumed by the next request. */
  public void enqueueBody(String json) {
    bodies.add(json);
  }

  /** Queue one response status, consumed by the next request. */
  public void enqueueStatus(int status) {
    statuses.add(status);
  }

  public int requestCount() {
    return requests.get();
  }

  public String lastUserAgent() {
    return lastUserAgent;
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
```

- [ ] **Step 3: Write the failing test**

Create `src/test/java/com/robsartin/segue/wikidata/WikidataClientTest.java`:

```java
package com.robsartin.segue.wikidata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class WikidataClientTest {

  @Test
  @DisplayName("it parses a JSON response")
  void parsesJson() {
    try (StubWikidataServer stub = new StubWikidataServer()) {
      stub.enqueueBody("{\"search\":[{\"id\":\"Q5593\"}]}");
      WikidataClient client = new WikidataClient(stub.baseUri());

      assertThat(client.get(Map.of("action", "wbsearchentities")).at("/search/0/id").asText())
          .isEqualTo("Q5593");
    }
  }

  @Test
  @DisplayName("it identifies segue by repository URL and never by an email address")
  void sendsRepositoryUserAgent() {
    // ADR 16 and ADR 30. Wikidata's policy invites contact details; a personal address is
    // not ours to put in an outbound header.
    try (StubWikidataServer stub = new StubWikidataServer()) {
      stub.enqueueBody("{}");
      new WikidataClient(stub.baseUri()).get(Map.of("action", "wbgetentities"));

      assertThat(stub.lastUserAgent()).contains("segue").contains("github.com/robsartin/segue");
      assertThat(stub.lastUserAgent()).doesNotContain("@");
    }
  }

  @Test
  @DisplayName("it retries a 429 and succeeds when the retry does")
  void retriesRateLimit() {
    try (StubWikidataServer stub = new StubWikidataServer()) {
      stub.enqueueStatus(429);
      stub.enqueueBody("{}");
      stub.enqueueStatus(200);
      stub.enqueueBody("{\"ok\":true}");

      assertThat(new WikidataClient(stub.baseUri()).get(Map.of("action", "x")).at("/ok").asBoolean())
          .isTrue();
      assertThat(stub.requestCount()).isEqualTo(2);
    }
  }

  @Test
  @DisplayName("it gives up after repeated failures rather than retrying forever")
  void givesUpEventually() {
    try (StubWikidataServer stub = new StubWikidataServer()) {
      for (int i = 0; i < 6; i++) {
        stub.enqueueStatus(503);
        stub.enqueueBody("{}");
      }
      WikidataClient client = new WikidataClient(stub.baseUri());

      assertThatThrownBy(() -> client.get(Map.of("action", "x")))
          .isInstanceOf(WikidataUnavailableException.class);
      assertThat(stub.requestCount()).isLessThanOrEqualTo(4);
    }
  }

  @Test
  @DisplayName("a 404 is not retried — it will not become a 200")
  void doesNotRetryClientErrors() {
    try (StubWikidataServer stub = new StubWikidataServer()) {
      stub.enqueueStatus(404);
      stub.enqueueBody("{}");
      WikidataClient client = new WikidataClient(stub.baseUri());

      assertThatThrownBy(() -> client.get(Map.of("action", "x")))
          .isInstanceOf(WikidataUnavailableException.class);
      assertThat(stub.requestCount()).isEqualTo(1);
    }
  }
}
```

- [ ] **Step 4: Run and confirm it fails**

```bash
./gradlew test --tests '*WikidataClientTest'
```

Expected: FAIL — `WikidataClient` does not exist.

- [ ] **Step 5: Write the exception and the client**

Create `src/main/java/com/robsartin/segue/wikidata/WikidataUnavailableException.java`:

```java
package com.robsartin.segue.wikidata;

/** Wikidata could not be reached, or refused, after retries. Callers degrade; they do not crash. */
public final class WikidataUnavailableException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public WikidataUnavailableException(String message) {
    super(message);
  }

  public WikidataUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
```

Create `src/main/java/com/robsartin/segue/wikidata/WikidataClient.java`:

```java
package com.robsartin.segue.wikidata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * The Wikidata Action API over the JDK's own HTTP client.
 *
 * <p>No Spring, deliberately: keeping this package plain Java is what lets it be tested against an
 * in-process stub with no application context, and what keeps ADR 25's promise that adding a source
 * touches only its own adapter.
 *
 * <p>Retries are for transient conditions only. A 429 or a 5xx may succeed on a second attempt; a
 * 404 will not, so retrying it just wastes someone else's capacity.
 */
public final class WikidataClient {

  private static final URI DEFAULT_BASE = URI.create("https://www.wikidata.org/w/api.php");

  /**
   * Wikidata's policy asks callers to identify themselves and offer a contact route. The
   * repository URL is that route. A personal email address is not ours to send (ADR 16).
   */
  private static final String USER_AGENT = "segue/0.1 (https://github.com/robsartin/segue)";

  private static final int MAX_ATTEMPTS = 4;
  private static final Duration BACKOFF_BASE = Duration.ofMillis(200);

  private final URI baseUri;
  private final HttpClient http;
  private final ObjectMapper mapper = new ObjectMapper();

  public WikidataClient() {
    this(DEFAULT_BASE);
  }

  public WikidataClient(URI baseUri) {
    this.baseUri = Objects.requireNonNull(baseUri, "baseUri");
    this.http =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
  }

  /** GET the API with the given parameters, always as JSON. */
  public JsonNode get(Map<String, String> queryParams) {
    Objects.requireNonNull(queryParams, "queryParams");
    URI uri = URI.create(baseUri + "?" + encode(queryParams));

    RuntimeException last = null;
    for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
      try {
        HttpResponse<String> response =
            http.send(
                HttpRequest.newBuilder(uri)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(20))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        int status = response.statusCode();
        if (status == 200) {
          return mapper.readTree(response.body());
        }
        if (!isTransient(status)) {
          throw new WikidataUnavailableException("Wikidata returned HTTP " + status + " for " + uri);
        }
        last = new WikidataUnavailableException("Wikidata returned HTTP " + status);
      } catch (IOException e) {
        last = new WikidataUnavailableException("could not reach Wikidata", e);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new WikidataUnavailableException("interrupted while calling Wikidata", e);
      }
      backoff(attempt);
    }
    throw new WikidataUnavailableException(
        "Wikidata did not answer after " + MAX_ATTEMPTS + " attempts", last);
  }

  private static boolean isTransient(int status) {
    return status == 429 || status >= 500;
  }

  private static void backoff(int attempt) {
    try {
      Thread.sleep(BACKOFF_BASE.toMillis() * (1L << (attempt - 1)));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new WikidataUnavailableException("interrupted while backing off", e);
    }
  }

  private static String encode(Map<String, String> params) {
    return params.entrySet().stream()
        .map(
            e ->
                URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8)
                    + "="
                    + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
        .collect(Collectors.joining("&"));
  }
}
```

Note the `givesUpEventually` test asserts at most 4 requests; `MAX_ATTEMPTS` is 4, so that holds.
The backoff is short enough that four attempts cost well under two seconds.

- [ ] **Step 6: Verify, gate, commit**

```bash
./gradlew test --tests '*WikidataClientTest'
./gradlew spotlessApply && ./gradlew clean check
git add gradle/libs.versions.toml build.gradle.kts src/main/java/com/robsartin/segue/wikidata src/test/java/com/robsartin/segue/wikidata
git commit -m "feat: add a Wikidata HTTP client with backoff and a repo-URL user agent"
```

---

### Task 6: ClaimMapper — claims JSON to assertions

**This task carries the increment's most important design constraint.** Read the whole task
before starting.

**Files:**
- Create: `src/main/java/com/robsartin/segue/wikidata/ClaimMapper.java`
- Create: `src/test/resources/wikidata/proposition-claims.json`
- Create: `src/test/java/com/robsartin/segue/wikidata/ClaimMapperTest.java`

**Interfaces:**
- Consumes: `EdgeTypes`, `EdgeType`, `AssertionRecord`, `Provenance`
- Produces: `ClaimMapper.map(String subjectQid, JsonNode entityNode, Instant assertedAt)` returning `List<AssertionRecord>`.

**The direction problem, and what we do about it.** Wikidata states most creative relations on
the *work*: the triple is `film P57 person`, not `person P57 film`. segue reads better from the
person's side, which is why `EdgeType.wikidataInverted` exists — `DIRECTED` is stored
`person DIRECTED film`.

The consequence is unavoidable and must be stated rather than papered over: **fetching an
entity returns only the claims stated ON it.** Expanding a film finds its director; expanding a
person does not find their films, because Wikidata never stated that triple on the person. Finding
those requires backlinks — a Query Service SPARQL call or the `wbsearchentities` haystack — and
that is a separate piece of work, not a line of this one.

So: map what the entity states, and use `wikidataInverted` to decide which way round to store it.

- [ ] **Step 1: Record a fixture**

Create `src/test/resources/wikidata/proposition-claims.json` — a trimmed `wbgetentities`
response shaped exactly like the real one, for a film with a director, a composer, a screenwriter,
and one property we do not whitelist:

```json
{
  "entities": {
    "Q1194713": {
      "id": "Q1194713",
      "labels": { "en": { "language": "en", "value": "The Proposition" } },
      "descriptions": { "en": { "language": "en", "value": "2005 film by John Hillcoat" } },
      "claims": {
        "P31": [
          { "mainsnak": { "snaktype": "value", "property": "P31",
              "datavalue": { "type": "wikibase-entityid", "value": { "id": "Q11424" } } },
            "rank": "normal", "references": [] } ],
        "P57": [
          { "mainsnak": { "snaktype": "value", "property": "P57",
              "datavalue": { "type": "wikibase-entityid", "value": { "id": "Q1339275" } } },
            "rank": "normal",
            "references": [ { "hash": "abc" } ] } ],
        "P86": [
          { "mainsnak": { "snaktype": "value", "property": "P86",
              "datavalue": { "type": "wikibase-entityid", "value": { "id": "Q214601" } } },
            "rank": "normal", "references": [] } ],
        "P58": [
          { "mainsnak": { "snaktype": "value", "property": "P58",
              "datavalue": { "type": "wikibase-entityid", "value": { "id": "Q214601" } } },
            "rank": "normal", "references": [] } ],
        "P462": [
          { "mainsnak": { "snaktype": "value", "property": "P462",
              "datavalue": { "type": "wikibase-entityid", "value": { "id": "Q23444" } } },
            "rank": "normal", "references": [] } ],
        "P463": [
          { "mainsnak": { "snaktype": "value", "property": "P463",
              "datavalue": { "type": "wikibase-entityid", "value": { "id": "Q1299" } } },
            "rank": "normal", "references": [],
            "qualifiers": {
              "P580": [ { "snaktype": "value", "property": "P580",
                  "datavalue": { "type": "time", "value": { "time": "+1983-01-01T00:00:00Z", "precision": 11 } } } ],
              "P582": [ { "snaktype": "value", "property": "P582",
                  "datavalue": { "type": "time", "value": { "time": "+2003-07-31T00:00:00Z", "precision": 11 } } } ]
            } } ],
        "P1476": [
          { "mainsnak": { "snaktype": "value", "property": "P1476",
              "datavalue": { "type": "monolingualtext", "value": { "text": "The Proposition", "language": "en" } } },
            "rank": "normal", "references": [] } ],
        "P2047": [
          { "mainsnak": { "snaktype": "somevalue", "property": "P2047" },
            "rank": "normal", "references": [] } ]
      }
    }
  }
}
```

- [ ] **Step 2: Write the failing test**

Create `src/test/java/com/robsartin/segue/wikidata/ClaimMapperTest.java`:

```java
package com.robsartin.segue.wikidata;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.robsartin.segue.domain.AssertionRecord;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Wikidata claims to segue assertions: whitelist, direction, dates, confidence. */
class ClaimMapperTest {

  private static final Instant PULL = Instant.parse("2026-08-24T09:00:00Z");
  private static final String SUBJECT = "Q1194713";

  private JsonNode entity;

  @BeforeEach
  void loadFixture() throws IOException {
    try (InputStream in =
        getClass().getResourceAsStream("/wikidata/proposition-claims.json")) {
      entity = new ObjectMapper().readTree(in).at("/entities/" + SUBJECT);
    }
  }

  @Test
  @DisplayName("only whitelisted properties become assertions")
  void mapsOnlyWhitelistedProperties() {
    // P462 (colour) and P1476 (title) are real properties we deliberately do not model.
    // ADR 22: the vocabulary is borrowed and small, not everything Wikidata knows.
    List<AssertionRecord> out = ClaimMapper.map(SUBJECT, entity, PULL);

    assertThat(out).extracting(AssertionRecord::typeCode)
        .containsExactlyInAnyOrder("DIRECTED", "COMPOSED_FOR", "WROTE_SCREENPLAY_FOR", "MEMBER_OF");
  }

  @Test
  @DisplayName("inverted properties are stored from the person's side")
  void invertsCreativeRelations() {
    // Wikidata says "film P57 person". segue stores "person DIRECTED film" (ADR 22).
    AssertionRecord directed =
        ClaimMapper.map(SUBJECT, entity, PULL).stream()
            .filter(a -> a.typeCode().equals("DIRECTED"))
            .findFirst()
            .orElseThrow();

    assertThat(directed.fromQid()).isEqualTo("Q1339275");
    assertThat(directed.toQid()).isEqualTo(SUBJECT);
  }

  @Test
  @DisplayName("direct properties keep the subject on the left")
  void keepsDirectRelations() {
    AssertionRecord memberOf =
        ClaimMapper.map(SUBJECT, entity, PULL).stream()
            .filter(a -> a.typeCode().equals("MEMBER_OF"))
            .findFirst()
            .orElseThrow();

    assertThat(memberOf.fromQid()).isEqualTo(SUBJECT);
    assertThat(memberOf.toQid()).isEqualTo("Q1299");
  }

  @Test
  @DisplayName("P580 and P582 qualifiers become the validity window")
  void mapsQualifiersToValidity() {
    AssertionRecord memberOf =
        ClaimMapper.map(SUBJECT, entity, PULL).stream()
            .filter(a -> a.typeCode().equals("MEMBER_OF"))
            .findFirst()
            .orElseThrow();

    assertThat(memberOf.validFrom()).isEqualTo(LocalDate.of(1983, 1, 1));
    assertThat(memberOf.validTo()).isEqualTo(LocalDate.of(2003, 7, 31));
  }

  @Test
  @DisplayName("a referenced statement is trusted more than an unreferenced one")
  void confidenceReflectsReferences() {
    // ADR 23's scale: 1.00 structured and referenced, 0.80 structured but unreferenced.
    List<AssertionRecord> out = ClaimMapper.map(SUBJECT, entity, PULL);

    AssertionRecord referenced =
        out.stream().filter(a -> a.typeCode().equals("DIRECTED")).findFirst().orElseThrow();
    AssertionRecord unreferenced =
        out.stream().filter(a -> a.typeCode().equals("COMPOSED_FOR")).findFirst().orElseThrow();

    assertThat(referenced.provenance().confidence()).isEqualTo(1.00);
    assertThat(unreferenced.provenance().confidence()).isEqualTo(0.80);
  }

  @Test
  @DisplayName("every assertion is attributed to wikidata and carries a citable statement ref")
  void attributesToWikidata() {
    assertThat(ClaimMapper.map(SUBJECT, entity, PULL))
        .allSatisfy(
            a -> {
              assertThat(a.provenance().sourceId()).isEqualTo("wikidata");
              assertThat(a.provenance().assertedAt()).isEqualTo(PULL);
              assertThat(a.provenance().sourceRef()).isNotNull();
              assertThat(a.provenance().isHypothesis()).isFalse();
            });
  }

  @Test
  @DisplayName("a snak with no value is skipped rather than crashing the whole entity")
  void skipsValuelessSnaks() {
    // P2047 in the fixture is snaktype "somevalue" — Wikidata's "we know there is one but
    // not what it is". One unusable claim must not lose the other forty.
    assertThat(ClaimMapper.map(SUBJECT, entity, PULL)).isNotEmpty();
  }

  @Test
  @DisplayName("instance-of claims are read for kind, not turned into edges")
  void doesNotEmitInstanceOfEdges() {
    assertThat(ClaimMapper.map(SUBJECT, entity, PULL))
        .noneMatch(a -> a.toQid().equals("Q11424"));
  }

  @Test
  @DisplayName("instanceOf exposes the P31 values for KindMapper")
  void exposesInstanceOf() {
    assertThat(ClaimMapper.instanceOf(entity)).containsExactly("Q11424");
  }

  @Test
  @DisplayName("the English label and description are readable")
  void readsLabelAndDescription() {
    assertThat(ClaimMapper.label(entity)).isEqualTo("The Proposition");
    assertThat(ClaimMapper.description(entity)).isEqualTo("2005 film by John Hillcoat");
  }
}
```

- [ ] **Step 3: Run and confirm it fails**

```bash
./gradlew test --tests '*ClaimMapperTest'
```

Expected: FAIL — `ClaimMapper` does not exist.

- [ ] **Step 4: Write the mapper**

Create `src/main/java/com/robsartin/segue/wikidata/ClaimMapper.java`:

```java
package com.robsartin.segue.wikidata;

import com.fasterxml.jackson.databind.JsonNode;
import com.robsartin.segue.domain.AssertionRecord;
import com.robsartin.segue.domain.EdgeType;
import com.robsartin.segue.domain.EdgeTypes;
import com.robsartin.segue.domain.Provenance;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Turns one entity's Wikidata claims into segue assertions.
 *
 * <p>The whitelist is not a separate list: it IS {@link EdgeTypes}, keyed by
 * {@link EdgeType#wikidataProperty()}. ADR 22 says the vocabulary is borrowed rather than invented,
 * and deriving the filter from the vocabulary is what keeps that true — adding a relation type is
 * one registration, not a registration plus a filter entry that can fall out of step.
 *
 * <p><b>Direction.</b> Wikidata states most creative relations on the work
 * ({@code film P57 person}); segue stores them from the person ({@code person DIRECTED film}).
 * {@link EdgeType#wikidataInverted()} records which, and this flips them mechanically.
 *
 * <p><b>Known limitation.</b> Fetching an entity returns only claims stated ON it, so expanding a
 * film finds its director but expanding a person does not find their films — Wikidata never stated
 * that triple on the person. Backlinks need a Query Service call and are deliberately out of scope
 * here.
 */
public final class ClaimMapper {

  private static final String SOURCE_ID = "wikidata";
  private static final String INSTANCE_OF = "P31";
  private static final String START_TIME = "P580";
  private static final String END_TIME = "P582";

  private static final Map<String, EdgeType> BY_PROPERTY = new HashMap<>();

  static {
    for (EdgeType type : EdgeTypes.all()) {
      if (type.wikidataProperty() != null) {
        BY_PROPERTY.put(type.wikidataProperty(), type);
      }
    }
  }

  private ClaimMapper() {}

  /** Every whitelisted claim on {@code entity}, as assertions. */
  public static List<AssertionRecord> map(String subjectQid, JsonNode entity, Instant assertedAt) {
    List<AssertionRecord> out = new ArrayList<>();
    JsonNode claims = entity.path("claims");
    claims
        .properties()
        .forEach(
            property -> {
              EdgeType type = BY_PROPERTY.get(property.getKey());
              if (type == null) {
                return;
              }
              for (JsonNode statement : property.getValue()) {
                toAssertion(subjectQid, type, statement, assertedAt).ifPresent(out::add);
              }
            });
    return List.copyOf(out);
  }

  private static Optional<AssertionRecord> toAssertion(
      String subjectQid, EdgeType type, JsonNode statement, Instant assertedAt) {

    JsonNode snak = statement.path("mainsnak");
    if (!"value".equals(snak.path("snaktype").asText())) {
      // "somevalue"/"novalue": Wikidata knows there is one but not what it is. Nothing to store.
      return Optional.empty();
    }
    String objectQid = snak.at("/datavalue/value/id").asText(null);
    if (objectQid == null || objectQid.isBlank()) {
      return Optional.empty();
    }

    String from = type.wikidataInverted() ? objectQid : subjectQid;
    String to = type.wikidataInverted() ? subjectQid : objectQid;

    // ADR 23: a referenced statement is authoritative, an unreferenced one is merely structured.
    boolean referenced = statement.path("references").isArray() && !statement.path("references").isEmpty();
    double confidence = referenced ? 1.00 : 0.80;

    LocalDate validFrom = qualifierDate(statement, START_TIME);
    LocalDate validTo = qualifierDate(statement, END_TIME);
    if (validFrom != null && validTo != null && validTo.isBefore(validFrom)) {
      // Wikidata occasionally holds an inverted window. Keep the claim, drop the nonsense,
      // rather than letting AssertionRecord's constructor reject the whole entity.
      validFrom = null;
      validTo = null;
    }

    String statementRef = statement.path("id").asText(type.wikidataProperty() + ":" + objectQid);

    return Optional.of(
        new AssertionRecord(
            from,
            to,
            type.code(),
            validFrom,
            validTo,
            new Provenance(SOURCE_ID, statementRef, assertedAt, confidence)));
  }

  private static LocalDate qualifierDate(JsonNode statement, String property) {
    JsonNode value = statement.at("/qualifiers/" + property + "/0/datavalue/value/time");
    if (value.isMissingNode() || value.asText().isBlank()) {
      return null;
    }
    // Wikidata times look like "+1983-01-01T00:00:00Z" — a leading sign, and zeroes where the
    // precision does not reach. A zero month or day cannot be a LocalDate, so treat it as absent.
    String raw = value.asText();
    String iso = raw.startsWith("+") || raw.startsWith("-") ? raw.substring(1) : raw;
    String datePart = iso.length() >= 10 ? iso.substring(0, 10) : iso;
    if (datePart.contains("-00")) {
      return null;
    }
    try {
      return LocalDate.parse(datePart);
    } catch (DateTimeParseException e) {
      return null;
    }
  }

  /** The entity's {@code P31} values, for {@link KindMapper}. */
  public static List<String> instanceOf(JsonNode entity) {
    List<String> out = new ArrayList<>();
    for (JsonNode statement : entity.path("claims").path(INSTANCE_OF)) {
      String qid = statement.at("/mainsnak/datavalue/value/id").asText(null);
      if (qid != null && !qid.isBlank()) {
        out.add(qid);
      }
    }
    return List.copyOf(out);
  }

  /** The English label, or null. */
  public static String label(JsonNode entity) {
    return entity.at("/labels/en/value").asText(null);
  }

  /** The English description — what makes disambiguation possible — or null. */
  public static String description(JsonNode entity) {
    return entity.at("/descriptions/en/value").asText(null);
  }
}
```

**Note:** `JsonNode.properties()` and `asText(String)` exist in Jackson 2.22. If the compiler
disagrees, use `fields()` and an explicit null check instead, and say so in your report — do not
change the behaviour to suit the API.

- [ ] **Step 5: Verify, gate, commit**

```bash
./gradlew test --tests '*ClaimMapperTest'
./gradlew spotlessApply && ./gradlew clean check
git add src/main/java/com/robsartin/segue/wikidata/ClaimMapper.java src/test/java/com/robsartin/segue/wikidata/ClaimMapperTest.java src/test/resources/wikidata
git commit -m "feat: map Wikidata claims to assertions through the borrowed vocabulary"
```

---

### Task 7: WikidataEntityResolver

**Files:**
- Create: `src/main/java/com/robsartin/segue/wikidata/WikidataEntityResolver.java`
- Create: `src/test/resources/wikidata/search-cave.json`
- Create: `src/test/java/com/robsartin/segue/wikidata/WikidataEntityResolverTest.java`

**Interfaces:**
- Consumes: `EntityResolver`, `Candidate` (Task 1), `WikidataClient` (Task 5), `ClaimMapper`/`KindMapper` (Tasks 4, 6)
- Produces: `WikidataEntityResolver(WikidataClient client, Clock clock)` and `WikidataEntityResolver(WikidataClient client)`, implementing `EntityResolver` with id `"wikidata"`.

- [ ] **Step 1: Record the search fixture**

Create `src/test/resources/wikidata/search-cave.json`:

```json
{
  "searchinfo": { "search": "nick cave" },
  "search": [
    { "id": "Q214601", "label": "Nick Cave",
      "description": "Australian musician, songwriter and author" },
    { "id": "Q6013406", "label": "Nick Cave",
      "description": "American sculptor and performance artist" },
    { "id": "Q19863965", "label": "Nick Cave and the Bad Seeds",
      "description": "Australian rock band" }
  ],
  "success": 1
}
```

Two people with the identical label is the point: without the description, a caller cannot choose.

- [ ] **Step 2: Write the failing test**

Create `src/test/java/com/robsartin/segue/wikidata/WikidataEntityResolverTest.java`:

```java
package com.robsartin.segue.wikidata;

import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.segue.domain.Candidate;
import com.robsartin.segue.domain.NodeAssertion;
import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.port.EntityResolver;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class WikidataEntityResolverTest {

  private static final Instant PULL = Instant.parse("2026-08-24T09:00:00Z");
  private static final Clock FIXED = Clock.fixed(PULL, ZoneOffset.UTC);

  private static String resource(String name) throws IOException {
    try (InputStream in = WikidataEntityResolverTest.class.getResourceAsStream(name)) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  @Test
  @DisplayName("search returns candidates carrying the description that disambiguates them")
  void searchReturnsDisambiguatingCandidates() throws IOException {
    try (StubWikidataServer stub = new StubWikidataServer()) {
      stub.enqueueBody(resource("/wikidata/search-cave.json"));
      EntityResolver resolver = new WikidataEntityResolver(new WikidataClient(stub.baseUri()), FIXED);

      List<Candidate> hits = resolver.search("nick cave", null, 10);

      assertThat(hits).hasSize(3);
      assertThat(hits.get(0).qid()).isEqualTo("Q214601");
      // Two entries share the label. Only the description separates them.
      assertThat(hits.get(0).label()).isEqualTo(hits.get(1).label());
      assertThat(hits.get(0).description()).isNotEqualTo(hits.get(1).description());
    }
  }

  @Test
  @DisplayName("search writes nothing — it is a question, not a change")
  void searchIsReadOnly() throws IOException {
    try (StubWikidataServer stub = new StubWikidataServer()) {
      stub.enqueueBody(resource("/wikidata/search-cave.json"));
      new WikidataEntityResolver(new WikidataClient(stub.baseUri()), FIXED).search("nick cave", null, 10);

      assertThat(stub.requestCount()).isEqualTo(1);
    }
  }

  @Test
  @DisplayName("an empty result is empty, not an error")
  void emptySearchIsNotAnError() {
    try (StubWikidataServer stub = new StubWikidataServer()) {
      stub.enqueueBody("{\"search\":[],\"success\":1}");
      EntityResolver resolver = new WikidataEntityResolver(new WikidataClient(stub.baseUri()), FIXED);

      assertThat(resolver.search("asdfghjkl", null, 10)).isEmpty();
    }
  }

  @Test
  @DisplayName("fetch returns a sourced node claim with the kind read from P31")
  void fetchReturnsNodeAssertion() throws IOException {
    try (StubWikidataServer stub = new StubWikidataServer()) {
      stub.enqueueBody(resource("/wikidata/proposition-claims.json"));
      EntityResolver resolver = new WikidataEntityResolver(new WikidataClient(stub.baseUri()), FIXED);

      Optional<NodeAssertion> fetched = resolver.fetch("Q1194713");

      assertThat(fetched).isPresent();
      NodeAssertion node = fetched.orElseThrow();
      assertThat(node.qid()).isEqualTo("Q1194713");
      assertThat(node.label()).isEqualTo("The Proposition");
      assertThat(node.kind()).isEqualTo(NodeKind.WORK); // P31 = Q11424, film
      assertThat(node.provenance().sourceId()).isEqualTo("wikidata");
      assertThat(node.provenance().assertedAt()).isEqualTo(PULL);
    }
  }

  @Test
  @DisplayName("an unknown identifier yields empty rather than a fabricated node")
  void unknownQidIsEmpty() {
    try (StubWikidataServer stub = new StubWikidataServer()) {
      stub.enqueueBody("{\"entities\":{\"Q999999999\":{\"missing\":\"\"}}}");
      EntityResolver resolver = new WikidataEntityResolver(new WikidataClient(stub.baseUri()), FIXED);

      assertThat(resolver.fetch("Q999999999")).isEmpty();
    }
  }

  @Test
  @DisplayName("it identifies itself as the same source its adapter will")
  void identifiesItself() {
    try (StubWikidataServer stub = new StubWikidataServer()) {
      assertThat(new WikidataEntityResolver(new WikidataClient(stub.baseUri()), FIXED).id())
          .isEqualTo("wikidata");
    }
  }
}
```

- [ ] **Step 2b: Run and confirm it fails**

```bash
./gradlew test --tests '*WikidataEntityResolverTest'
```

Expected: FAIL — `WikidataEntityResolver` does not exist.

- [ ] **Step 3: Write the resolver**

Create `src/main/java/com/robsartin/segue/wikidata/WikidataEntityResolver.java`:

```java
package com.robsartin.segue.wikidata;

import com.fasterxml.jackson.databind.JsonNode;
import com.robsartin.segue.domain.Candidate;
import com.robsartin.segue.domain.NodeAssertion;
import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.domain.Provenance;
import com.robsartin.segue.port.EntityResolver;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Resolution against the Wikidata Action API: {@code wbsearchentities} to find, {@code
 * wbgetentities} to fetch.
 *
 * <p>Search is read-only by construction — it returns {@link Candidate}s and writes nothing. That
 * matters for the MCP surface (ADR 26), where the model is expected to search, show the user the
 * options, and only then add one.
 */
public final class WikidataEntityResolver implements EntityResolver {

  private static final String SOURCE_ID = "wikidata";

  private final WikidataClient client;
  private final Clock clock;

  public WikidataEntityResolver(WikidataClient client) {
    this(client, Clock.systemUTC());
  }

  public WikidataEntityResolver(WikidataClient client, Clock clock) {
    this.client = Objects.requireNonNull(client, "client");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Override
  public String id() {
    return SOURCE_ID;
  }

  @Override
  public List<Candidate> search(String query, NodeKind kind, int limit) {
    Objects.requireNonNull(query, "query");
    JsonNode response =
        client.get(
            Map.of(
                "action", "wbsearchentities",
                "search", query,
                "language", "en",
                "uselang", "en",
                "limit", Integer.toString(Math.clamp(limit, 1, 50)),
                "format", "json"));

    List<Candidate> out = new ArrayList<>();
    for (JsonNode hit : response.path("search")) {
      String qid = hit.path("id").asText(null);
      String label = hit.path("label").asText(null);
      if (qid == null || label == null) {
        continue;
      }
      String description = hit.path("description").asText(null);
      // wbsearchentities does not return P31, so the kind is not knowable here without a
      // second round trip per hit. Report CONCEPT and let fetch() settle it — better than
      // paying N requests to decorate a list the caller may discard.
      Candidate candidate = new Candidate(qid, label, description, NodeKind.CONCEPT);
      if (kind == null || kind == candidate.kind()) {
        out.add(candidate);
      }
    }
    return List.copyOf(out);
  }

  @Override
  public Optional<NodeAssertion> fetch(String qid) {
    Objects.requireNonNull(qid, "qid");
    JsonNode entity = entity(qid);
    if (entity == null) {
      return Optional.empty();
    }
    String label = ClaimMapper.label(entity);
    if (label == null || label.isBlank()) {
      return Optional.empty();
    }
    NodeKind kind = KindMapper.fromInstanceOf(ClaimMapper.instanceOf(entity));
    return Optional.of(
        new NodeAssertion(
            qid, kind, label, new Provenance(SOURCE_ID, qid, clock.instant(), 1.00)));
  }

  /** The raw entity node, or null when Wikidata does not have it. Shared with the adapter. */
  JsonNode entity(String qid) {
    JsonNode response =
        client.get(
            Map.of(
                "action", "wbgetentities",
                "ids", qid,
                "languages", "en",
                "format", "json"));
    JsonNode entity = response.at("/entities/" + qid);
    if (entity.isMissingNode() || entity.has("missing")) {
      return null;
    }
    return entity;
  }
}
```

**Note on `search` and kind:** filtering by kind cannot work until `fetch` has run, because
`wbsearchentities` does not return `P31`. The filter is therefore a no-op today for any non-null
kind, which is honest but weak. **Report this in your task report** — it may deserve either a
second round trip per hit or dropping the parameter, and that is a decision for review, not for you.

- [ ] **Step 4: Verify, gate, commit**

```bash
./gradlew test --tests '*WikidataEntityResolverTest'
./gradlew spotlessApply && ./gradlew clean check
git add src/main/java/com/robsartin/segue/wikidata src/test/java/com/robsartin/segue/wikidata src/test/resources/wikidata
git commit -m "feat: resolve Wikidata entities by search and by identifier"
```

---

### Task 8: WikidataSourceAdapter

**Files:**
- Create: `src/main/java/com/robsartin/segue/wikidata/WikidataSourceAdapter.java`
- Create: `src/test/java/com/robsartin/segue/wikidata/WikidataSourceAdapterTest.java`

**Interfaces:**
- Consumes: `SourceAdapter`, `ExpandContext` (Task 1), `WikidataEntityResolver` (Task 7), `ClaimMapper` (Task 6)
- Produces: `WikidataSourceAdapter(WikidataEntityResolver resolver, Clock clock)` implementing `SourceAdapter` with id `"wikidata"`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/robsartin/segue/wikidata/WikidataSourceAdapterTest.java`:

```java
package com.robsartin.segue.wikidata;

import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.segue.domain.AssertionRecord;
import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.domain.NodeRecord;
import com.robsartin.segue.port.ExpandContext;
import com.robsartin.segue.port.SourceAdapter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class WikidataSourceAdapterTest {

  private static final Clock FIXED =
      Clock.fixed(Instant.parse("2026-08-24T09:00:00Z"), ZoneOffset.UTC);
  private static final NodeRecord SEED =
      new NodeRecord("Q1194713", NodeKind.WORK, "The Proposition");

  private static String resource(String name) throws IOException {
    try (InputStream in = WikidataSourceAdapterTest.class.getResourceAsStream(name)) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private static SourceAdapter adapterFor(StubWikidataServer stub) {
    WikidataClient client = new WikidataClient(stub.baseUri());
    return new WikidataSourceAdapter(new WikidataEntityResolver(client, FIXED), FIXED);
  }

  @Test
  @DisplayName("expanding a work yields its whitelisted relations")
  void expandsWork() throws IOException {
    try (StubWikidataServer stub = new StubWikidataServer()) {
      stub.enqueueBody(resource("/wikidata/proposition-claims.json"));

      List<AssertionRecord> claims = adapterFor(stub).expand(SEED, ExpandContext.defaults());

      assertThat(claims).isNotEmpty();
      assertThat(claims).extracting(AssertionRecord::typeCode).contains("DIRECTED");
      assertThat(claims).allSatisfy(c -> assertThat(c.provenance().sourceId()).isEqualTo("wikidata"));
    }
  }

  @Test
  @DisplayName("it honours maxNewEdges, and stops before fetching what it would discard")
  void honoursBound() throws IOException {
    try (StubWikidataServer stub = new StubWikidataServer()) {
      stub.enqueueBody(resource("/wikidata/proposition-claims.json"));

      List<AssertionRecord> claims = adapterFor(stub).expand(SEED, new ExpandContext(2));

      assertThat(claims).hasSize(2);
    }
  }

  @Test
  @DisplayName("an unreachable Wikidata degrades to an empty result, it does not propagate")
  void degradesWhenUnavailable() {
    // The caller is a language model. A partial result it can see beats an exception it can
    // only retry — see the error-handling section of the slice 1-2 design.
    try (StubWikidataServer stub = new StubWikidataServer()) {
      for (int i = 0; i < 6; i++) {
        stub.enqueueStatus(503);
        stub.enqueueBody("{}");
      }

      assertThat(adapterFor(stub).expand(SEED, ExpandContext.defaults())).isEmpty();
    }
  }

  @Test
  @DisplayName("an unknown seed yields nothing")
  void unknownSeedIsEmpty() {
    try (StubWikidataServer stub = new StubWikidataServer()) {
      stub.enqueueBody("{\"entities\":{\"Q999999999\":{\"missing\":\"\"}}}");

      assertThat(
              adapterFor(stub)
                  .expand(
                      new NodeRecord("Q999999999", NodeKind.PERSON, "Nobody"),
                      ExpandContext.defaults()))
          .isEmpty();
    }
  }

  @Test
  @DisplayName("it supports every kind and names itself consistently with the resolver")
  void declaresItself() {
    try (StubWikidataServer stub = new StubWikidataServer()) {
      SourceAdapter adapter = adapterFor(stub);

      assertThat(adapter.id()).isEqualTo("wikidata");
      assertThat(adapter.supports(NodeKind.PERSON)).isTrue();
      assertThat(adapter.supports(NodeKind.CONCEPT)).isTrue();
    }
  }
}
```

- [ ] **Step 2: Run and confirm it fails**

```bash
./gradlew test --tests '*WikidataSourceAdapterTest'
```

Expected: FAIL — `WikidataSourceAdapter` does not exist.

- [ ] **Step 3: Write the adapter**

Create `src/main/java/com/robsartin/segue/wikidata/WikidataSourceAdapter.java`:

```java
package com.robsartin.segue.wikidata;

import com.fasterxml.jackson.databind.JsonNode;
import com.robsartin.segue.domain.AssertionRecord;
import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.domain.NodeRecord;
import com.robsartin.segue.port.ExpandContext;
import com.robsartin.segue.port.SourceAdapter;
import java.time.Clock;
import java.util.List;
import java.util.Objects;

/**
 * Expansion from Wikidata.
 *
 * <p>Wikidata is first among sources deliberately: no API key, cross-domain by construction, and
 * it supplies both the QID identity spine and the edge vocabulary (ADR 22).
 *
 * <p><b>Failures degrade rather than propagate.</b> The eventual caller is a language model, and a
 * partial result it can see and act on beats an exception it can only retry. An unreachable
 * Wikidata yields an empty expansion, not a thrown error.
 */
public final class WikidataSourceAdapter implements SourceAdapter {

  private static final String SOURCE_ID = "wikidata";

  private final WikidataEntityResolver resolver;
  private final Clock clock;

  public WikidataSourceAdapter(WikidataEntityResolver resolver, Clock clock) {
    this.resolver = Objects.requireNonNull(resolver, "resolver");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Override
  public String id() {
    return SOURCE_ID;
  }

  @Override
  public boolean supports(NodeKind kind) {
    // Wikidata spans every kind segue models. That breadth is the reason it is the first source.
    return true;
  }

  @Override
  public List<AssertionRecord> expand(NodeRecord seed, ExpandContext ctx) {
    Objects.requireNonNull(seed, "seed");
    Objects.requireNonNull(ctx, "ctx");
    try {
      JsonNode entity = resolver.entity(seed.qid());
      if (entity == null) {
        return List.of();
      }
      return ClaimMapper.map(seed.qid(), entity, clock.instant()).stream()
          .limit(ctx.maxNewEdges())
          .toList();
    } catch (WikidataUnavailableException e) {
      // Deliberately swallowed. The tool layer reports the shortfall to the model; throwing
      // here would turn a partial answer into no answer.
      return List.of();
    }
  }
}
```

- [ ] **Step 4: Verify, gate, commit**

```bash
./gradlew test --tests '*WikidataSourceAdapterTest'
./gradlew spotlessApply && ./gradlew clean check
git add src/main/java/com/robsartin/segue/wikidata src/test/java/com/robsartin/segue/wikidata
git commit -m "feat: expand entities from Wikidata behind the SourceAdapter SPI"
```

---

### Task 9: End-to-end ingest, and the ArchUnit rules for wikidata

**Files:**
- Modify: `src/main/java/com/robsartin/segue/ingest/GraphProjector.java`
- Create: `src/test/java/com/robsartin/segue/ingest/WikidataIngestEndToEndTest.java`
- Modify: `src/test/java/com/robsartin/segue/arch/ArchitectureTest.java`

**Interfaces:**
- Consumes: everything from Tasks 1-8
- Produces: `GraphProjector.project` returns `long` (was `void`) — the count of assertions replayed

- [ ] **Step 0: Give `GraphProjector.project` a return value**

`project()` currently returns `void`. The end-to-end test below asserts on how many
assertions were replayed, which is worth knowing — a replay that silently applied nothing
looks identical to one that worked.

In `src/main/java/com/robsartin/segue/ingest/GraphProjector.java`, change the signature to
return `long`, count the applied assertions, and return the count. Keep the existing
sequence numbering and fatal-on-failure behaviour exactly as they are.

Update the Javadoc to document the return:

```java
   * @return how many assertions were applied
```

`GraphProjectorTest` must still pass **unmodified** — `void` to `long` is source-compatible
for callers that ignore the result. If it does not, you changed behaviour, not just the
signature.

```bash
./gradlew test --tests '*GraphProjectorTest' --tests '*IngestServiceTest'
```

Expected: PASS, unmodified.

- [ ] **Step 1: Write the end-to-end test**

This is the increment's actual promise: a real-shaped Wikidata response becomes a durable,
replayable graph.

Create `src/test/java/com/robsartin/segue/ingest/WikidataIngestEndToEndTest.java`:

```java
package com.robsartin.segue.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.segue.domain.AssertionRecord;
import com.robsartin.segue.domain.LoggedAssertion;
import com.robsartin.segue.domain.NodeAssertion;
import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.domain.NodeRecord;
import com.robsartin.segue.domain.Provenance;
import com.robsartin.segue.port.AssertionLog;
import com.robsartin.segue.port.ExpandContext;
import com.robsartin.segue.port.GraphStore;
import com.robsartin.segue.port.SourceAdapter;
import com.robsartin.segue.sqlite.SqliteAssertionLog;
import com.robsartin.segue.tinker.TinkerGraphStore;
import com.robsartin.segue.wikidata.StubWikidataServer;
import com.robsartin.segue.wikidata.WikidataClient;
import com.robsartin.segue.wikidata.WikidataEntityResolver;
import com.robsartin.segue.wikidata.WikidataSourceAdapter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Wikidata response to durable graph, and back again by replay. */
class WikidataIngestEndToEndTest {

  private static final Clock FIXED =
      Clock.fixed(Instant.parse("2026-08-24T09:00:00Z"), ZoneOffset.UTC);
  private static final NodeRecord SEED =
      new NodeRecord("Q1194713", NodeKind.WORK, "The Proposition");

  @TempDir Path tempDir;

  private static String resource(String name) throws IOException {
    try (InputStream in = WikidataIngestEndToEndTest.class.getResourceAsStream(name)) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private static Provenance sourced(String ref) {
    return new Provenance("wikidata", ref, FIXED.instant(), 1.0);
  }

  @Test
  @DisplayName("a Wikidata expansion becomes a graph that survives a restart")
  void expansionBecomesADurableGraph() throws IOException {
    Path dbFile = tempDir.resolve("ingest.db");
    List<LoggedAssertion> recorded = new ArrayList<>();
    int expectedEdges;

    try (StubWikidataServer stub = new StubWikidataServer();
        AssertionLog log = new SqliteAssertionLog(dbFile);
        GraphStore graph = new TinkerGraphStore()) {

      stub.enqueueBody(resource("/wikidata/proposition-claims.json"));
      WikidataEntityResolver resolver =
          new WikidataEntityResolver(new WikidataClient(stub.baseUri()), FIXED);
      SourceAdapter adapter = new WikidataSourceAdapter(resolver, FIXED);

      List<AssertionRecord> claims = adapter.expand(SEED, ExpandContext.defaults());
      expectedEdges = claims.size();

      // Every entity an edge touches must exist before the edge does. The neighbours are
      // stubs here: a real ingest would resolve each one, which is the fan-out increment 4
      // spends its virtual threads on.
      Set<String> seen = new LinkedHashSet<>();
      recorded.add(new NodeAssertion(SEED.qid(), SEED.kind(), SEED.label(), sourced(SEED.qid())));
      seen.add(SEED.qid());
      for (AssertionRecord claim : claims) {
        for (String qid : List.of(claim.fromQid(), claim.toQid())) {
          if (seen.add(qid)) {
            recorded.add(new NodeAssertion(qid, NodeKind.CONCEPT, qid, sourced(qid)));
          }
        }
      }
      recorded.addAll(claims);

      new IngestService(log, graph).recordAll(recorded);

      assertThat(claims).isNotEmpty();
      assertThat(graph.edgeCount()).isEqualTo(expectedEdges);
      assertThat(log.size()).isEqualTo(recorded.size());
    }

    // Everything above is now closed. Reopen from disk and rebuild from the log alone.
    try (AssertionLog reopened = new SqliteAssertionLog(dbFile);
        GraphStore rebuilt = new TinkerGraphStore()) {

      long replayed = GraphProjector.project(reopened, rebuilt);

      assertThat(replayed).isEqualTo(recorded.size());
      assertThat(rebuilt.node("Q1194713")).isPresent();
      assertThat(rebuilt.edgeCount()).isEqualTo(expectedEdges);
    }
  }
}
```

Note the neighbour nodes are recorded as `CONCEPT` stubs. A real ingest resolves each one — that
fan-out is what increment 4 spends virtual threads on — but doing it here would need a stub
response per neighbour and would test the stub, not the pipeline.

- [ ] **Step 2: Run it**

```bash
./gradlew test --tests '*WikidataIngestEndToEndTest'
```

Expected: PASS. If the node-before-edge ordering is wrong you will see
`assertion references unknown entity` — fix the ordering, not the assertion.

- [ ] **Step 3: Add the ArchUnit rules**

Add to `ArchitectureTest`:

```java
  /** ADR 25: the wikidata adapter stays plain Java so it is testable without a context. */
  @ArchTest
  static final ArchRule wikidataDoesNotDependOnSpring =
      noClasses()
          .that()
          .resideInAPackage("..wikidata..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("org.springframework..")
          .because("ADR 25: adding a source must not require a framework");

  /** ADR 32: wikidata is an adapter like any other. */
  @ArchTest
  static final ArchRule wikidataDoesNotDependOnOtherAdapters =
      noClasses()
          .that()
          .resideInAPackage("..wikidata..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("..tinker..", "..jena..", "..sqlite..")
          .because("ADR 32: adapters are siblings, not collaborators");
```

- [ ] **Step 4: Prove the Spring rule bites**

It currently passes vacuously — Spring is not on the classpath, so nothing *could* import it.
A rule that cannot fail is worth knowing about. Confirm by reasoning, and **state plainly in your
report that `wikidataDoesNotDependOnSpring` is vacuous until increment 4 adds Spring Boot**, so a
reviewer is not misled into thinking it is exercised. Do not try to force it red.

`wikidataDoesNotDependOnOtherAdapters` is NOT vacuous — prove it. Temporarily add an import of
`com.robsartin.segue.tinker.ProvenanceCodec` to a wikidata class, run the arch tests, confirm the
rule fails naming it, then remove it and confirm green and a clean `git status`.

- [ ] **Step 5: Verify, gate, commit**

```bash
./gradlew spotlessApply && ./gradlew clean check
git add src/test/java/com/robsartin/segue
git commit -m "test: cover Wikidata ingest end to end and fence the adapter"
```

---

### Task 10: The live smoke test

**Files:**
- Modify: `build.gradle.kts`
- Create: `src/test/java/com/robsartin/segue/wikidata/WikidataLiveSmokeTest.java`

**Interfaces:**
- Consumes: `WikidataClient`, `WikidataEntityResolver`, `WikidataSourceAdapter`
- Produces: a `liveTest` Gradle task

**Why this exists.** Every other Wikidata test replays a recorded fixture. Recorded fixtures pass
forever against a dead endpoint — they cannot tell you the upstream API changed shape. This is the
positive control, and it is the only test here that can catch that.

- [ ] **Step 1: Exclude the tag from the normal run**

In `build.gradle.kts`, change the `tasks.test` block to:

```kotlin
tasks.test {
    useJUnitPlatform {
        // Excluded from the normal gate: it needs the network and can fail for reasons
        // that have nothing to do with this code. Run it deliberately, via ./gradlew liveTest.
        excludeTags("live")
    }
    testLogging {
        events("failed")
    }
}

tasks.register<Test>("liveTest") {
    group = "verification"
    description = "Runs the tagged live tests against the real Wikidata API. Needs network."
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform { includeTags("live") }
    // Never up-to-date: the point is to re-check the real endpoint.
    outputs.upToDateWhen { false }
}
```

Note `jacocoTestCoverageVerification` measures `test` only, so excluding the live tag does not
change coverage accounting.

- [ ] **Step 2: Write the live test**

Create `src/test/java/com/robsartin/segue/wikidata/WikidataLiveSmokeTest.java`:

```java
package com.robsartin.segue.wikidata;

import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.segue.domain.AssertionRecord;
import com.robsartin.segue.domain.Candidate;
import com.robsartin.segue.domain.NodeAssertion;
import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.domain.NodeRecord;
import com.robsartin.segue.port.ExpandContext;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The positive control. Everything else in this package replays a recorded fixture, and a
 * recorded fixture passes forever against a dead endpoint — it cannot detect that Wikidata
 * changed its response shape.
 *
 * <p>Tagged {@code live} and excluded from CI, because it needs the network and can fail for
 * reasons unrelated to this code. Run it on purpose: {@code ./gradlew liveTest}.
 */
@Tag("live")
class WikidataLiveSmokeTest {

  /** Nick Cave. A real, stable identifier with relations across music, film and literature. */
  private static final String CAVE = "Q214601";

  private final WikidataEntityResolver resolver = new WikidataEntityResolver(new WikidataClient());

  @Test
  @DisplayName("wbsearchentities still returns id, label and description")
  void searchStillWorks() {
    List<Candidate> hits = resolver.search("Nick Cave", null, 5);

    assertThat(hits).isNotEmpty();
    assertThat(hits).allSatisfy(c -> assertThat(c.qid()).matches("Q\\d+"));
    assertThat(hits).anySatisfy(c -> assertThat(c.description()).isNotNull());
  }

  @Test
  @DisplayName("wbgetentities still yields a labelled entity with a mappable P31")
  void fetchStillWorks() {
    Optional<NodeAssertion> cave = resolver.fetch(CAVE);

    assertThat(cave).isPresent();
    assertThat(cave.orElseThrow().label()).isEqualTo("Nick Cave");
    assertThat(cave.orElseThrow().kind()).isEqualTo(NodeKind.PERSON);
  }

  @Test
  @DisplayName("a real expansion still produces whitelisted, attributed claims")
  void expansionStillWorks() {
    List<AssertionRecord> claims =
        new WikidataSourceAdapter(resolver, java.time.Clock.systemUTC())
            .expand(new NodeRecord(CAVE, NodeKind.PERSON, "Nick Cave"), new ExpandContext(50));

    // Not asserting a count: Wikidata changes. Asserting the shape still holds.
    assertThat(claims)
        .allSatisfy(
            c -> {
              assertThat(c.provenance().sourceId()).isEqualTo("wikidata");
              assertThat(c.provenance().confidence()).isBetween(0.80, 1.00);
              assertThat(c.typeCode()).isNotBlank();
            });
  }
}
```

- [ ] **Step 3: Confirm it is excluded from the normal gate**

```bash
./gradlew clean check 2>&1 | grep -i "LiveSmoke" && echo "LEAKED INTO CI" || echo "correctly excluded"
```

Expected: `correctly excluded`.

- [ ] **Step 4: Run it for real, once**

```bash
./gradlew liveTest
```

Expected: PASS against the real API. **If it fails, that is a genuine finding about the live API
and you must report it rather than adjusting the assertions to match** — it is the one test whose
failure means something the fixtures cannot tell us.

If there is no network available in your environment, say so plainly in your report and do not
pretend it passed.

- [ ] **Step 5: Gate and commit**

```bash
./gradlew spotlessApply && ./gradlew clean check
git add build.gradle.kts src/test/java/com/robsartin/segue/wikidata/WikidataLiveSmokeTest.java
git commit -m "test: add a tagged live smoke test against the real Wikidata API"
```

---

### Task 11: Documentation and pull request

**Files:**
- Modify: `CLAUDE.md`

- [ ] **Step 1: Update the architecture map**

Add the new packages to `CLAUDE.md`'s `## Architecture` block:

```
domain/   records + edge vocabulary. NO third-party deps.
port/     GraphStore, AssertionLog, SourceAdapter, EntityResolver — the seams.
tinker/   Gremlin adapter (the chosen one).
jena/     RDF adapter (reference implementation, keep it working).
sqlite/   The append-only assertion log, in one file.
wikidata/ The first source: resolution and expansion. Plain Java, no Spring.
ingest/   IngestService (the only write path) and GraphProjector (boot replay).
```

**Do not name dependency versions in this file.** `gradle/libs.versions.toml` is the only place
they live.

- [ ] **Step 2: Record the direction limitation where someone will find it**

Add to `CLAUDE.md`'s "Gotchas already paid for" section:

```markdown
- **Wikidata states creative relations on the WORK, not the person.** Fetching an entity
  returns only claims stated on it, so expanding a film finds its director while expanding
  a person does not find their films. `EdgeType.wikidataInverted` fixes the stored
  direction, not the discovery problem. Backlinks need a Query Service call, and that is
  its own piece of work.
- **`wbsearchentities` does not return P31**, so search results cannot be filtered by kind
  without a second round trip per hit. `EntityResolver.search`'s `kind` parameter is
  therefore weaker than it looks.
```

- [ ] **Step 3: Update the "Next steps" section**

Slice 1 is now done. Replace its heading with a note that it landed, and leave slice 2 as the
next thing. Keep the section's existing framing that the design doc and ADRs are authoritative.

- [ ] **Step 4: Verify every documented command runs**

```bash
grep -oE '^\./gradlew [a-zA-Z]+' CLAUDE.md README.md | sort -u
./gradlew clean check
```

- [ ] **Step 5: Commit, push, open the PR**

```bash
./gradlew spotlessApply
git add CLAUDE.md
git commit -m "docs: describe the Wikidata source and its direction limitation"
git push -u origin 3-wikidata-ingest
gh pr create --fill-first
```

Edit the body to state: final test count and coverage, that it closes #3, the direction
limitation and the `search` kind-filter weakness as known gaps, whether the live test actually
ran, and that `wikidataDoesNotDependOnSpring` is vacuous until increment 4.

- [ ] **Step 6: Stop for review**

Do not merge.

---

## Notes for the implementer

**Failures degrade, they do not propagate.** The eventual caller is a language model. Everywhere
you are tempted to throw out of an adapter, return what you have instead and let the layer above
report the shortfall. The exception is programmer error — a null argument, an invalid QID — which
should fail loudly and immediately.

**One bad claim must not lose the other forty.** `ClaimMapper` skips a valueless snak and an
inverted date window rather than aborting the entity. If you find another case Wikidata produces
that would abort a whole expansion, skip it and count it, and say so in your report.

**Never lower a coverage threshold.** If the gate fails, report the numbers.

**The build must be green at every commit.**

**Two things in this plan are deliberately left undone**, and should stay that way unless review
says otherwise: retiring the placeholder QIDs in `Fixture` (it touches every test's expected
counts and deserves its own change), and backlink discovery via the Query Service.
