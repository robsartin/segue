# The stand-in takes the fold's re-derived kind — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** on the bypass path a merge's stand-in node carries the kind the fold re-derived for the node it stands in for, in both folds, instead of the kind the claim stated. ADR 59's first residual closes.

**Architecture:** `Equivalences.localsOfMerges` and `Equivalences.standIns` take the re-derivation as a required `UnaryOperator<NodeAssertion>`; `LogProjection.of` and `GraphProjector.project` each pass the `KindMapper::rederive` they already apply to every node claim. `domain` gains no dependency — `java.util.function` is a `java..` package — and no new cross-package import appears anywhere.

**Tech Stack:** Java 25, JUnit 5, AssertJ, ArchUnit, plain `./gradlew`.

**Spec:** `docs/superpowers/specs/2026-09-03-bypass-kind-design.md`

## Global Constraints

- **Pure TDD / red first**: every behaviour is seen red for the right reason — a real assertion failure, never a compile error — before the code that makes it green. Every guard is planted against, the output quoted, the plant reverted. Test names `should<Expected>When<Condition>` with `@DisplayName`.
- **Mikado**: the gate is green before every commit. **Stage by explicit path, git stderr visible.** Commits end `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`. Never cite a `.superpowers/` path from a committed file.
- Gate, **blocking**: `SEGUE_REQUIRE_BROWSER=true SEGUE_REQUIRE_GRAPHVIZ=true ./gradlew check --rerun-tasks`. Fast loop: `./gradlew test --tests 'com.robsartin.segue.export.StandInKindMatchesTheLocalNodeTest' --tests 'com.robsartin.segue.domain.EquivalencesTest'` (~7s). Run `./gradlew spotlessApply` before the gate — google-java-format owns the layout.
- **Only JDK 25 is installed; Gradle 9.7.1 launches on it.** Plain `./gradlew`; never `java_home -v 21`.
- **Never run a writing dev task** (`own`, `ownClaim`, `retractEntity`); `~/.segue/segue.db` is never read, written, or created.
- **Smallest region, because #220 and #221 are in flight on sibling branches and touch the same method and the same ADR.** Do **not** widen `BothFoldsAgreeTest.ownedLog()`. Do **not** touch `StandInQidsDenoteNothingTest`. The ADR amendment is a new dated section appended at the end of the file; nothing above it is edited.

---

### Task 1: The stand-in's kind comes from the caller's re-derivation

**Files:** Create: `src/test/java/com/robsartin/segue/export/StandInKindMatchesTheLocalNodeTest.java`. Modify: `src/test/java/com/robsartin/segue/export/InventedGraph.java`, `src/main/java/com/robsartin/segue/domain/Equivalences.java`, `src/main/java/com/robsartin/segue/export/LogProjection.java`, `src/main/java/com/robsartin/segue/ingest/GraphProjector.java`, `src/test/java/com/robsartin/segue/domain/EquivalencesTest.java`. Read: `KindMapper.rederive` (the rule being handed in), `Equivalences.standIns` and `localsOfMerges` (~lines 174–259).

- [ ] **Step 1 — the fixture id.** In `InventedGraph`, after the `TWICE` constant (~line 93), add:

```java
  /**
   * A class no whitelist knows, so a claim stating it re-derives to {@code CONCEPT} — {@code
   * KindMapper.rederive}'s "when classes ARE stated, this list is the authority, including when it
   * answers CONCEPT" (ADR 42). ADR 58's leading-zero shape, the next free number in this file's own
   * sequence, so it needs no entry in {@code StandInQidsDenoteNothingTest}'s allowlist (#222).
   */
  static final String UNKNOWN_CLASS = "Q0900109";
```

- [ ] **Step 2 — RED, the defect reproduced in both folds.** Create `StandInKindMatchesTheLocalNodeTest.java`:

```java
package com.robsartin.segue.export;

import static com.robsartin.segue.export.InventedGraph.BYPASS;
import static com.robsartin.segue.export.InventedGraph.STANDING;
import static com.robsartin.segue.export.InventedGraph.UNKNOWN_CLASS;
import static com.robsartin.segue.export.InventedGraph.merged;
import static com.robsartin.segue.export.InventedGraph.node;
import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.export.InventedGraph.FakeAssertionLog;
import com.robsartin.segue.ingest.GraphProjector;
import com.robsartin.segue.port.IdentityMerge;
import com.robsartin.segue.tinker.TinkerGraphStore;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A merge's stand-in node and the node it stands in for are one entity, so they cannot be two
 * kinds (#222, ADR 59's first residual).
 *
 * <p><b>Deliberately not a case in {@code BothFoldsAgreeTest}.</b> That test compares the two folds
 * to <em>each other</em>, and this defect is invisible there because both folds are wrong in the
 * same direction: each re-derives the local node's kind through {@code KindMapper.rederive} (ADR
 * 42) and each read the stand-in's kind off the claim as stated. What is compared here is the
 * stand-in against the node beside it, <em>within</em> one fold - asked twice, once of each fold,
 * because a fix that reached only one of them would put the two folds back where #178 found them.
 *
 * <p><b>The control assertion is not decoration.</b> Two nodes that were both left un-re-derived
 * would agree perfectly, so each test first says that the fold did re-derive the local node. That
 * is {@code BothFoldsAgreeTest}'s own "two empty sets agree about nothing" argument, applied to a
 * comparison of two kinds.
 *
 * <p><b>{@code BYPASS} is the path this is about.</b> Spec ruling 2 refuses to assume that a claim
 * naming a merged local id came through {@code OwnCli}, so a plain {@link
 * com.robsartin.segue.domain.NodeAssertion} can name one - and unlike a minted entity it can carry
 * classes, which is what gives the fold something to re-derive from.
 */
class StandInKindMatchesTheLocalNodeTest {

  /** A bypass claim stating a kind, and one class that contradicts it. */
  private static FakeAssertionLog bypassLog() {
    return new FakeAssertionLog()
        .with(
            node(BYPASS, NodeKind.WORK, "a local-shaped id a source named", List.of(UNKNOWN_CLASS)),
            merged(BYPASS, STANDING));
  }

  @Test
  @DisplayName("the exporter's stand-in takes the kind the fold re-derived for the local node")
  void shouldGiveTheStandInTheRederivedKindWhenTheExporterFoldsABypassClaimStatingClasses() {
    LogProjection folded = LogProjection.of(bypassLog());

    assertThat(folded.nodes().get(BYPASS).kind())
        .as("control: the fold re-derived the local node, so there is a disagreement to find")
        .isEqualTo(NodeKind.CONCEPT);
    assertThat(folded.nodes().get(STANDING).kind())
        .as("a stand-in is the same entity as the node it stands in for, so it is the same kind")
        .isEqualTo(folded.nodes().get(BYPASS).kind());
  }

  @Test
  @DisplayName("the boot replay's stand-in takes the kind the fold re-derived for the local node")
  void shouldGiveTheStandInTheRederivedKindWhenTheBootReplaySeesABypassClaimStatingClasses() {
    FakeAssertionLog log = bypassLog();

    try (TinkerGraphStore replayed = new TinkerGraphStore()) {
      GraphProjector.project(log, replayed, IdentityMerge.NONE);

      assertThat(replayed.node(BYPASS).orElseThrow().kind())
          .as("control: replay re-derived the local node, so there is a disagreement to find")
          .isEqualTo(NodeKind.CONCEPT);
      assertThat(replayed.node(STANDING).orElseThrow().kind())
          .as("a stand-in is the same entity as the node it stands in for, so it is the same kind")
          .isEqualTo(replayed.node(BYPASS).orElseThrow().kind());
    }
  }
}
```

  Run `./gradlew test --tests 'com.robsartin.segue.export.StandInKindMatchesTheLocalNodeTest'` and **record the output**. Expected, and seen on `2e01341`: `2 tests completed, 2 failed`, each an `org.opentest4j.AssertionFailedError` reading `expected: CONCEPT but was: WORK`, with the message `[a stand-in is the same entity as the node it stands in for, so it is the same kind]` on the second assertion of each — the **controls pass**, which is what makes it the right red. If either control fails, stop: the fixture is not exercising re-derivation and nothing below is warranted.

- [ ] **Step 3 — the inert seam, and the second red.** Add the parameter without applying it, so the next red is an assertion and not a compile error.

  In `Equivalences.java`, add `import java.util.function.UnaryOperator;` after `import java.util.Set;`, and change the two signatures:

```java
  public static Map<String, NodeRecord> standIns(
      List<LoggedAssertion> log, UnaryOperator<NodeAssertion> rederive) {
    Map<String, NodeRecord> standIns = new LinkedHashMap<>();
    for (Map.Entry<Integer, NodeRecord> at : localsOfMerges(log, rederive).entrySet()) {
```

```java
  public static Map<Integer, NodeRecord> localsOfMerges(
      List<LoggedAssertion> log, UnaryOperator<NodeAssertion> rederive) {
    Objects.requireNonNull(log, "log");
    Objects.requireNonNull(rederive, "rederive");
```

  In `LogProjection.of`, line ~115: `Equivalences.standIns(logged)` becomes `Equivalences.standIns(logged, KindMapper::rederive)`.

  In `GraphProjector.project`, line ~86: `Equivalences.standIns(assertions)` becomes `Equivalences.standIns(assertions, KindMapper::rederive)`.

  In `EquivalencesTest`, add `import java.util.function.UnaryOperator;` and, after the `WHEN` constant:

```java
  /**
   * Kinds as the claim stated them. Every fixture in this file states no classes, so {@code
   * KindMapper.rederive} would be the identity on all of them (ADR 42) - naming it here says the
   * choice was made rather than defaulted, and keeps {@code wikidata} out of a {@code domain} test.
   */
  private static final UnaryOperator<NodeAssertion> AS_CLAIMED = UnaryOperator.identity();
```

  and pass it at all eight existing call sites: `Equivalences.standIns(log)` becomes `Equivalences.standIns(log, AS_CLAIMED)` (seven sites) and `Equivalences.localsOfMerges(log)` becomes `Equivalences.localsOfMerges(log, AS_CLAIMED)` (one site).

  Then add the unit statement of the rule to `EquivalencesTest`, after `shouldStandInWhereAPlainNodeClaimNamedTheMergedLocalId`:

```java
  @Test
  @DisplayName("the stand-in's kind is the caller's re-derivation, not the kind the claim stated")
  void shouldTakeTheStandInsKindFromTheCallersRederivationWhenALocalSideStatesOne() {
    // The rule that closes ADR 59's first residual, stated where it lives (#222). KindMapper is in
    // wikidata and domain may not reach it - domainHasNoThirdPartyDependencies allows domain only
    // domain, java and javax - so the re-derivation arrives as a function and both folds hand in
    // the one they already apply to every node claim. The stub below stands in for it.
    List<LoggedAssertion> log =
        List.of(
            new NodeAssertion(
                MINTED,
                NodeKind.WORK,
                "a local-shaped id a source named",
                new Provenance("invented", "invented:1", WHEN, 1.0)),
            SameAs.declared(MINTED, CANONICAL, WHEN));

    assertThat(Equivalences.standIns(log, claim -> claim.withKind(NodeKind.PERSON)))
        .as("both folds re-derive this claim's kind, and the stand-in is the same entity")
        .containsExactly(
            Map.entry(
                CANONICAL,
                new NodeRecord(
                    CANONICAL, NodeKind.PERSON, "a local-shaped id a source named", List.of())));
  }
```

  Run the fast loop. **Expect three failures, and check each is the right one:** the new `EquivalencesTest` method fails with `expected: PERSON but was: WORK` (the seam is inert, which is what this step is proving), and the two fold tests from Step 2 still fail with `expected: CONCEPT but was: WORK` — unchanged, because a parameter nothing reads changes no behaviour. Every other `EquivalencesTest` method must **pass**, which is what says `AS_CLAIMED` is honest.

- [ ] **Step 4 — GREEN.** In `Equivalences.localsOfMerges`, apply the operator on the one arm that can carry classes:

```java
        case NodeAssertion claim -> claimed.put(claim.qid(), rederive.apply(claim).toNode());
```

  The `LocalEntity` arm is left exactly as it is: the owner states a kind and no classes, so there is nothing to re-derive from and `rederive` would be the identity there anyway (ADR 42).

  Run the fast loop. Expect all three green.

- [ ] **Step 5 — positive control, one plant per fold, because one plant proves only one assertion.**

  Plant A: in `LogProjection.of`, change `Equivalences.standIns(logged, KindMapper::rederive)` to `Equivalences.standIns(logged, java.util.function.UnaryOperator.identity())`. Run the fast loop. Expect **exactly one** failure — `shouldGiveTheStandInTheRederivedKindWhenTheExporterFoldsABypassClaimStatingClasses`, `expected: CONCEPT but was: WORK` — and the boot-replay test green. Quote the output in the task report. Revert the plant.

  Plant B: the same substitution in `GraphProjector.project`. Expect **exactly one** failure — `shouldGiveTheStandInTheRederivedKindWhenTheBootReplaySeesABypassClaimStatingClasses`, same message — and the exporter test green. Quote it. Revert the plant.

  Confirm `git diff` shows no plant left before going on.

- [ ] **Step 6 — the gate, and commit.** `./gradlew spotlessApply`, then, blocking:

```
SEGUE_REQUIRE_BROWSER=true SEGUE_REQUIRE_GRAPHVIZ=true ./gradlew check --rerun-tasks
```

  `ArchitectureTest.noPackageCycles` and `domainHasNoThirdPartyDependencies` are the two that matter here and both are inside `check`. `DeveloperGuideEnumerationsTest`'s cross-package-import assertion should be unmoved: `export` and `ingest` both imported `KindMapper` already.

  Commit, staging by explicit path:

```
git add src/main/java/com/robsartin/segue/domain/Equivalences.java \
        src/main/java/com/robsartin/segue/export/LogProjection.java \
        src/main/java/com/robsartin/segue/ingest/GraphProjector.java \
        src/test/java/com/robsartin/segue/domain/EquivalencesTest.java \
        src/test/java/com/robsartin/segue/export/InventedGraph.java \
        src/test/java/com/robsartin/segue/export/StandInKindMatchesTheLocalNodeTest.java
git status
git commit
```

---

### Task 2: The javadoc says the rule instead of the lag

**Files:** Modify: `src/main/java/com/robsartin/segue/domain/Equivalences.java` (javadoc only), `src/main/java/com/robsartin/segue/export/LogProjection.java` (javadoc only), `src/main/java/com/robsartin/segue/ingest/GraphProjector.java` (javadoc only), `docs/developer-guide.md`.

No behaviour changes in this task, so there is nothing to see red. The verification is the gate — `javadoc` gates in this build, and `DocumentationLinksTest` and `DeveloperGuideEnumerationsTest` read the guide.

- [ ] **Step 1 — `localsOfMerges`: replace the two paragraphs that state the defect** (the block beginning `<p><b>Node kinds are taken as the claim stated them, and on the bypass path that is a known lag.</b>` and the paragraph after it beginning `<p>It is documented rather than fixed`) with:

```
   * <p><b>Node kinds come from the caller, not from the claim (#222).</b> {@code
   * KindMapper.rederive} is the identity on a claim carrying no {@code P31} classes (ADR 42), which
   * covers every {@link LocalEntity}: the owner states a kind and no classes. A {@link
   * NodeAssertion} <em>can</em> carry classes, and both folds re-derive the local node's own kind
   * from them - so for as long as this method read the claim's stated kind, a bypass claim gave a
   * stand-in of one kind beside a local node re-derived to another, and the two nodes standing for
   * one entity disagreed about what it is. Both folds read this one method, so they agreed about
   * the lagging kind and {@code BothFoldsAgreeTest} could not see it; {@code
   * StandInKindMatchesTheLocalNodeTest} compares the stand-in with the node beside it instead.
   *
   * <p><b>Which is why the re-derivation is a parameter.</b> {@code KindMapper} lives in {@code
   * wikidata}, and {@code ArchitectureTest.domainHasNoThirdPartyDependencies} allows this package
   * {@code domain}, {@code java} and {@code javax} and nothing else - not even {@code port}, so a
   * seam declared there was never available either. What is available is a {@code
   * java.util.function} type, and each fold hands in the {@code KindMapper::rederive} it already
   * applies to every node claim it folds. It is required rather than defaulted, on {@link
   * #NONE}'s reason: an overload quietly restoring the old behaviour is how a third fold would
   * arrive with the lag and nothing saying so.
```

- [ ] **Step 2 — the two `@param` tags.** Give `localsOfMerges` and `standIns` a `@param rederive` above their `@return`:

```
   * @param rederive how the calling fold derives a node claim's kind - {@code
   *     KindMapper::rederive} from both of them, handed in because {@code domain} may not name it
```

- [ ] **Step 3 — `standIns`, the four-homes paragraph.** After its last sentence (`All four agree today, condition for condition; nothing holds them to it but this paragraph.`) add:

```
   * The kind this one carries now comes through {@code rederive} (#222); the other three are
   * unchanged by that, because two of them carry a label and no kind at all, and {@code
   * IngestService.standIn} copies the local node as the graph in front of it holds it - which on
   * the live path is the claim un-re-derived, because that path is not a projection (ADR 42).
```

- [ ] **Step 4 — the two folds' class javadoc.** In `LogProjection`, in the paragraph beginning `<b>Node kinds are re-derived from the classes the claim recorded</b>`, append: `A merge's stand-in node goes through the same rule, because it stands in for a node this fold re-derived (#222).` Add the same sentence to the matching paragraph in `GraphProjector` (`<b>Node kinds are re-derived here, always, from the {@code P31} classes the claim carries</b>`).

- [ ] **Step 5 — the developer guide.** In `docs/developer-guide.md`, section *"A merge is said, not done — and it lands in two places at two times"*, in the sentence ending `built by \`Equivalences.standIns\` in a pre-pass that runs before either fold begins.`, append:

```
It carries the merged entity's label and the kind that fold **re-derived** for it, not the kind the
claim happened to state — the two are the same node's, and a bypass claim carrying classes used to
make them differ ([#222](https://github.com/robsartin/segue/issues/222)).
```

- [ ] **Step 6 — gate and commit.** `./gradlew spotlessApply`, then blocking `SEGUE_REQUIRE_BROWSER=true SEGUE_REQUIRE_GRAPHVIZ=true ./gradlew check --rerun-tasks`. Commit, staging the four paths explicitly.

---

### Task 3: ADR 59's dated amendment

**Files:** Modify: `docs/adr/0059-owner-claims-as-a-third-layer.md` (append only). Read: the 2026-09-02 amendment's residual list, which is the thing being closed and must not be edited.

- [ ] **Step 1 — append, and edit nothing.** At the very end of the file, after the last residual bullet, add a new section. Nothing above it changes — not the residual bullet this closes, not one word of it, not the frontmatter.

```markdown
**Amendment (2026-09-03, issue #222): the first residual above is closed. The stand-in carries the
kind the fold re-derived for the node it stands in for, and the re-derivation reaches `domain` as a
parameter.**

Nothing above is withdrawn and no sentence above is edited. The residual said it plainly: *"the
stand-in's kind is taken as the claim stated it"* on the bypass path, *"re-deriving inside `domain`
would drag `KindMapper` in and break `noPackageCycles`"*. The first half was true and is now false;
the second half named the wrong rule and was still right about the obstacle.

**What the code does now.** `Equivalences.localsOfMerges` and `Equivalences.standIns` take the
re-derivation as a required `UnaryOperator<NodeAssertion>`, and `LogProjection.of` and
`GraphProjector.project` each hand in the `KindMapper::rederive` they already apply to every node
claim they fold. A merge's canonical node therefore ends the fold as the same kind as the node it
stands in for. Those classes are the authority for the mechanics; this amendment mirrors no table
of theirs.

**The measurement, on an invented fixture** — invented ids, invented labels, no known list behind
it, so [ADR 51](0051-what-an-adr-may-quote.md) does not bite. A bypass `NodeAssertion` naming a
local id as `WORK` while stating one class the whitelist does not know, then a merge onto a
canonical id: before the change, **both** folds held the local node as `CONCEPT` and the canonical
node as `WORK`, failing on `expected: CONCEPT but was: WORK`; after it, both hold `CONCEPT`. Both
folds were wrong in the same direction, which is exactly why `BothFoldsAgreeTest` was silent — it
compares the two folds to each other. `StandInKindMatchesTheLocalNodeTest` compares the stand-in
with the node beside it, in each fold separately, and was seen red in both and planted against once
per fold.

**The obstacle was stricter than the residual said, and that changed the answer.**
`ArchitectureTest.noPackageCycles` was the rule named; the binding one is
`domainHasNoThirdPartyDependencies`, which allows `domain` only `domain`, `java` and `javax`. So a
port interface for the re-derivation was never available either — `domain` may not reach `port` —
and the seam had to be a `java.util.function` type. That is not a workaround for the rule; it is
what the rule leaves, and the `localsOfMerges` javadoc had already named the shape: *"only a rule
that moved re-derivation behind a port would close it"*.

**Rejected, with the reason each lost.**

- **The merge event carries the re-derived kind when it is written** — `SameAs` gains a `NodeKind`,
  set at the moment of the merge, and both folds read it. No package problem at all. **Lost because
  it writes a derived value into an append-only log**, which is the thing
  [ADR 42](0042-store-p31-and-rederive-kind-at-projection.md) exists to undo: a kind frozen into a
  row is immune to every later correction of the whitelist, which is the ratchet issue #60 removed
  at the cost of two full re-seeds. It also fails to fix what it was proposed for — every `SameAs`
  already in the log carries no kind, so the fold needs the fallback anyway and the lag survives on
  precisely the rows that exist.
- **Copy from the resolved local node in a post-pass.** The issue's own first candidate, and it
  cannot be taken as stated: **neither fold has resolved any node at the point the stand-in is
  built.** The pre-pass runs before the fold, and it has to — an edge claimed earlier in the log
  than the merge that names its endpoint arrives on the canonical id first, and
  `TinkerGraphStore.record` refuses an endpoint it has never seen. As a post-pass the exporter could
  do it, and the boot replay could not: a `GraphStore` cannot say which canonical nodes were
  stand-ins, so that fold would keep its own record of the pre-pass — a second answer to "which
  merges have a local side", the two-readings-of-one-log shape the 2026-09-02 amendment spent an
  issue removing.
- **The stand-in carries the local node's classes** so the folds re-derive it like any other node.
  **Lost** because neither fold re-derives a `NodeRecord`, so each would need its own conversion,
  and because it would assert classes about the *canonical* entity that no source ever stated for
  it — which is what `standIns` already refuses: a stand-in carries what it was given rather than
  inventing a class.
- **Move `KindMapper` into `domain`.** ArchUnit would permit it, since a class with only private
  constructors is a static registry rather than a value type. **Lost on what it puts there**: a
  whitelist of Wikidata `P31` ids, grown from measurements against Wikidata and owned by the adapter
  that fetches them, made visible to every domain type.
- **Accept and record again.** The path is unreachable from today's sources — no source can allocate
  a `Q00` id. **Lost for the reason the 2026-09-02 amendment already gives when it declines that
  defence**: it is the same premise spec ruling 2 refuses to rely on, and the fold admits a
  `NodeAssertion` on that path *because* it refuses to rely on it.

**What this does not settle.**

- **The live path does not re-derive at all.** `IngestService.record` applies a node claim as it was
  stated, so `IngestService.standIn` — which copies the local node off the running graph — answers
  with the claimed kind there, and agrees with the local node beside it because that node is
  un-re-derived too. Nothing in production reaches it: `OwnRun` appends a merge through `claim()`,
  which holds no graph. Whether ADR 42's re-derivation should also run on the live write is a
  separate question nobody has argued.
- **The stand-in rule still has four homes.** This closes the kind lag in one of them and unifies
  none of them; that residual stands, and is issue #220.
```

- [ ] **Step 2 — gate.** Blocking `SEGUE_REQUIRE_BROWSER=true SEGUE_REQUIRE_GRAPHVIZ=true ./gradlew check --rerun-tasks`. `DocumentationLinksTest` resolves every relative link added above; `AdrIndexTest` compares number, title and status, none of which move. If `docs/adr/README.md` reds, the frontmatter was touched — revert that, do not regenerate.

- [ ] **Step 3 — commit** `docs/adr/0059-owner-claims-as-a-third-layer.md` by explicit path.

---

### Task 4: Rebase, and reconcile with the sibling branches

**Files:** possibly `src/test/…` files that #220 adds. Read: the merged state of issues #220 and #221 on `main`.

- [ ] **Step 1 — rebase onto `main`.** `git fetch origin && git rebase origin/main`. Resolve conflicts in favour of keeping **both** sides: in `Equivalences.java` the sibling issues touch `standIns`' body and javadoc, and this branch touches its signature; in ADR 59 each amendment is its own appended dated section, so the resolution is to keep them in date order and edit neither.

- [ ] **Step 2 — if #220's four-homes guard has landed, update its bypass expectation.** Its fixture includes *"one whose local side is a bypass `NodeAssertion`"* and pins each home's stand-in kind and label. For that case only, the expected kind for `Equivalences.standIns` moves from the kind the claim states to the kind `KindMapper.rederive` gives it. Every `LocalEntity` case and every label is unmoved — `rederive` is the identity on a claim with no classes.

  **And say this in the task report, because it is a real difference and not drift:** where #220's guard reads `IngestService.standIn` through a graph filled by the *live* path, that home answers with the claimed kind, because `IngestService.record` never re-derives. Both homes copy the local node as the reader in front of them holds it; the readers differ. If the guard cannot express that, it should compare each home at the seam it can answer and name the live path as the reason, rather than assert an equality that is false about the code.

- [ ] **Step 3 — if #221 has landed**, re-run and expect no expected value here to move: it changes *which* merge names a stand-in (`putIfAbsent` versus last-wins), not what kind the stand-in carries. If `StandInKindMatchesTheLocalNodeTest` reds after that rebase, stop and report — the two are meant to be orthogonal and a red says one of them is not.

- [ ] **Step 4 — the full gate one more time, blocking**, then push the branch and open the PR for review. Do not merge.
