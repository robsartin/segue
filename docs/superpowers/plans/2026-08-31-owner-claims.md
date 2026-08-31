# Owner Claims Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The owner can mint an entity Wikidata does not know, assert edges to it, route through it, rate it, and later merge it into a real QID without losing anything.

**Architecture:** A third claim layer. Owner claims join `LoggedAssertion`'s sealed permits beside `Retraction`, project into the graph like world facts, and are excluded from the corroboration count so the owner cannot vouch for himself. Local ids reuse ADR 58's unallocatable-QID mechanism, so no QID pattern changes. A merge is an appended equivalence, never an edit.

**Tech Stack:** Java 21 (release 21, toolchain 25), Gradle Kotlin DSL, JUnit 5 + AssertJ, ArchUnit.

**Spec:** `docs/superpowers/specs/2026-08-31-owner-claims-design.md`. Read it first — it carries the argument and the alternatives.

## Global Constraints

- **Issue #92.** Branch `92-owner-claims` (this plan is already committed there).
- **`domain` has no third-party dependencies** (ADR 18). New records go there; nothing they import may.
- **The log is append-only** (ADR 19) and **retraction is a new claim** (ADR 44). Nothing edits history.
- **`~/.segue/segue.db` must never be opened.** Copy it for any measurement; report mtime, size and inode unchanged. It holds the owner's irreplaceable ratings.
- **Repo is PUBLIC and the owner's ratings and interests are personal data** (ADR 33, ADR 51). Aggregate figures only in tracked files; **never name an entity as something the owner rated, likes or is known for.** Fixtures use ADR 58's leading-zero form.
- `./gradlew check --rerun-tasks` green **before every commit**, run **blocking**, `SEGUE_REQUIRE_BROWSER=true`. Do not trust a fast green: a docs-only or comment-only edit has produced `BUILD SUCCESSFUL in 1s` with `test UP-TO-DATE`. Count tests off the JUnit XML.
- Stage by explicit path. NEVER `git add -A`; an untracked `mad.vcf` must never be staged.
- **TDD: failing test first, run it, watch it fail for the right reason, and record in your report what the failure actually said.**
- **Every rule or guard needs a positive control** — plant the violation, watch it go red, revert, and quote the message. A guard never seen to fail has never been tested (#93; #139 was a second instance).
- **Thirty false generalisations have been recorded on this project in five days**, every one in a sentence *describing* the work rather than in the work. Two were in this plan's own spec and were caught by reading the code. For every claim you write, ask what would be observable if it were false, then produce that observation.

## File structure

**Create**
- `domain/LocalEntity.java` — a minted entity: `(String qid, NodeKind kind, String label, Instant mintedAt)`
- `domain/OwnerEdge.java` — an owner-asserted edge: `(String fromQid, String toQid, String typeCode, Instant assertedAt)`
- `domain/SameAs.java` — a merge: `(String localQid, String canonicalQid, Instant assertedAt)`
- `own/OwnRun.java`, `own/OwnCli.java` — the seventh dev tool

**Modify**
- `domain/LoggedAssertion.java` — sealed permits gains the three
- `domain/Provenance.java` — an owner source id and `isOwner()`
- `domain/EdgeRecord.java` — `corroboration()` excludes owner provenance
- `ingest/IngestService.java` — `apply` gains three cases
- `mcp/SegueService.java` — `expandEntity` refuses a local entity distinctly
- `arch/ArchitectureTest.java` — `DEV_TOOL_PACKAGES` and the sibling fences
- `build.gradle.kts` — the `own` JavaExec task

---

### Task 1: The three claim types

**Files:**
- Create: `src/main/java/com/robsartin/segue/domain/{LocalEntity,OwnerEdge,SameAs}.java`
- Modify: `src/main/java/com/robsartin/segue/domain/LoggedAssertion.java`
- Test: `src/test/java/com/robsartin/segue/domain/OwnerClaimTest.java`

**Interfaces — later tasks depend on exactly these:**
- `LocalEntity(String qid, NodeKind kind, String label, Instant mintedAt)` with `NodeRecord toNode()`
- `OwnerEdge(String fromQid, String toQid, String typeCode, Instant assertedAt)`
- `SameAs(String localQid, String canonicalQid, Instant assertedAt)`

Model them on `domain/Retraction.java` — a first-person `LoggedAssertion` with its own validation and no `Provenance`. Read it before writing.

- [ ] **Step 1: Write the failing tests**

```java
  @Test
  @DisplayName("should refuse a local entity whose id Wikidata could allocate")
  void shouldRefuseALocalEntityWhoseIdWikidataCouldAllocate() {
    assertThatThrownBy(
            () -> new LocalEntity("Q42", NodeKind.PERSON, "a minted person", Instant.EPOCH))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("allocatable");
  }

  @Test
  @DisplayName("should accept a local entity on an id Wikidata cannot allocate")
  void shouldAcceptALocalEntityOnAnIdWikidataCannotAllocate() {
    LocalEntity minted = new LocalEntity("Q00900042", NodeKind.PERSON, "a minted person", Instant.EPOCH);

    assertThat(minted.toNode().instanceOf()).isEmpty();
    assertThat(minted.toNode().qid()).isEqualTo("Q00900042");
  }

  @Test
  @DisplayName("should refuse a merge whose canonical side is not a real Wikidata id")
  void shouldRefuseAMergeWhoseCanonicalSideIsNotARealWikidataId() {
    assertThatThrownBy(() -> new SameAs("Q00900042", "Q00900043", Instant.EPOCH))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("should refuse an owner edge whose type nothing registers")
  void shouldRefuseAnOwnerEdgeWhoseTypeNothingRegisters() {
    assertThatThrownBy(() -> new OwnerEdge("Q00900042", "Q42", "NOT_A_TYPE", Instant.EPOCH))
        .isInstanceOf(IllegalArgumentException.class);
  }
```

The third test is the one that matters most: a merge that points at another local id would build an equivalence chain nothing resolves. The fourth keeps owner edges inside the registered vocabulary — ADR 22 clause 3 is not opened by this work.

- [ ] **Step 2: Run and watch them fail.** Record the compile error.

- [ ] **Step 3: Implement.** Each record validates in its compact constructor. `LocalEntity` requires a leading-zero qid; `SameAs` requires a leading-zero local side and an allocatable canonical side; `OwnerEdge` requires `EdgeTypes` to know the type code. Add all three to `LoggedAssertion`'s permits.

**Note:** ADR 58 claimed the leading-zero space for test fixtures. Pick a band for local entities that a reader can tell apart, state it in the javadoc, and say why — that convention is this task's decision to make.

- [ ] **Step 4: Run the gate and commit**

```bash
git add src/main/java/com/robsartin/segue/domain/LocalEntity.java \
        src/main/java/com/robsartin/segue/domain/OwnerEdge.java \
        src/main/java/com/robsartin/segue/domain/SameAs.java \
        src/main/java/com/robsartin/segue/domain/LoggedAssertion.java \
        src/test/java/com/robsartin/segue/domain/OwnerClaimTest.java
git commit -m "Give the owner three claims the log can carry (#92)"
```

---

### Task 2: They project, and they carry the owner's name

**Files:**
- Modify: `src/main/java/com/robsartin/segue/domain/Provenance.java`, `src/main/java/com/robsartin/segue/ingest/IngestService.java`, `src/main/java/com/robsartin/segue/sqlite/SqliteAssertionLog.java`, `src/main/java/com/robsartin/segue/domain/Retractions.java`
- Test: `src/test/java/com/robsartin/segue/ingest/OwnerClaimProjectionTest.java`

**Amended 2026-08-31 after Task 1's review. The original file list was wrong and this task could not
have gone green on it.**

Task 1 found that `LoggedAssertion` has **five** exhaustive switches, not the one this plan named,
and left throwing stubs in four. Three of them are on the round trip and **must be filled together
or not at all**:

- `SqliteAssertionLog.append` — the write half.
- `SqliteAssertionLog.readRow` — the read half. Its `default -> throw` is named in no task; it is
  yours.
- `Retractions.survives` — called **first** on every row, in all three folds, reached from MCP boot
  replay, `rate`, `recommend`, `exportGraph` and `retractEntity`.

**Why the order matters more than the code.** `IngestService.record` is `log.append()` *then*
`apply()`, so this task's first test dies in the persistence path before reaching the switch this
task owns. And filling `append` alone would let one owner claim into the owner's real database and
make it **unbootable across every tool** — with ADR 19 forbidding deletion of the row. Task 1's
throwing `append` is currently the gate that keeps that impossible; do not remove it without the
other two.

**Do not fake the log to go green.** The cheap route is an in-memory `AssertionLog` test double,
which would make this task pass while production still throws and hide the round-trip gap until
Task 5. Use `SqliteAssertionLog.inMemory()`, the real class, as `IngestServiceTest` and
`RetractRunTest` already do.

`RetractRun.measure` and `LogProjection.of` are the two stubs **not** on the round trip. Judge
whether they need filling here and say which you chose; a stub only the new types can reach is not
the same hazard as one existing behaviour walks into.

**Interfaces:**
- Consumes: Task 1's three records.
- Produces: `Provenance.OWNER` (the reserved source id) and `Provenance.isOwner()`.

`IngestService.apply` is a switch over the sealed interface — read it first; adding cases is the natural shape, and the compiler will tell you if you miss one.

- [ ] **Step 1: Write the failing tests**

```java
  @Test
  @DisplayName("should put a minted entity in the graph with no classes")
  void shouldPutAMintedEntityInTheGraphWithNoClasses() {
    ingest.record(new LocalEntity("Q00900042", NodeKind.PERSON, "a minted person", NOW));

    assertThat(graph.node("Q00900042")).isPresent();
    assertThat(graph.node("Q00900042").orElseThrow().instanceOf()).isEmpty();
  }

  @Test
  @DisplayName("should record an owner edge as the owner's claim, not a model's guess")
  void shouldRecordAnOwnerEdgeAsTheOwnersClaimNotAModelsGuess() {
    ingest.record(new LocalEntity("Q00900042", NodeKind.PERSON, "a minted person", NOW));
    ingest.record(new OwnerEdge("Q00900042", "Q00900043", "INFLUENCED_BY", NOW));

    EdgeRecord edge = onlyEdgeFrom("Q00900042");
    assertThat(edge.sources()).singleElement().matches(Provenance::isOwner);
    assertThat(edge.isUncorroboratedHypothesis())
        .as("an owner claim is not a model guess, so PathRanking must not demote it")
        .isFalse();
  }
```

The second assertion is the load-bearing one, and it is why **no `PathRanking` change is needed**: `isUncorroboratedHypothesis` is true only when every source `isHypothesis()`, which tests `sourceId.startsWith("llm:")`. Verify that by reading `Provenance.isHypothesis` before you rely on it.

- [ ] **Step 2: Run and watch them fail.** Record the messages.

- [ ] **Step 3: Implement.** `Provenance.OWNER` is a reserved source id that is **not** prefixed `llm:`. `apply` gains `case LocalEntity`, `case OwnerEdge`, `case SameAs` — leave `SameAs` a no-op with a comment pointing at Task 4, so this task's cases stay honest about what they do.

- [ ] **Step 4: Run the gate and commit**

---

### Task 3: They route, and they do not vouch

**Files:**
- Modify: `src/main/java/com/robsartin/segue/domain/EdgeRecord.java`
- Test: `src/test/java/com/robsartin/segue/domain/EdgeRecordTest.java`

- [ ] **Step 1: Write the failing test**

```java
  @Test
  @DisplayName("should not let an owner claim corroborate a source's claim")
  void shouldNotLetAnOwnerClaimCorroborateASourcesClaim() {
    EdgeRecord edge =
        new EdgeRecord(
            "Q00900042", "Q42", "INFLUENCED_BY", null, null,
            List.of(wikidataProvenance(), ownerProvenance()));

    assertThat(edge.corroboration())
        .as("the owner is not a second witness to the world; two sources here is one")
        .isEqualTo(1);
  }
```

**Why this direction.** ADR 55 declined `subgroup` partly because either coding would manufacture corroboration with one Wikidata coding while withholding it from the other. An owner edge over a pair Wikidata already asserts would do the same thing with the owner's own hand.

- [ ] **Step 2: Run and watch it fail.** It should report 2. Record the message.

- [ ] **Step 3: Implement.** `corroboration()` filters owner provenance out before counting distinct source ids.

- [ ] **Step 4: Prove the guard bites.** Delete the filter, watch this test fail, restore. Record the message.

- [ ] **Step 5: Run the gate and commit**

---

### Task 4: The merge carries edges and ratings

**Files:**
- Modify: `src/main/java/com/robsartin/segue/ingest/IngestService.java`
- Test: `src/test/java/com/robsartin/segue/ingest/MergeCarriesEverythingTest.java`

**This task has the irreplaceable data behind it.** Affinity is one row per qid and there is no history table and no un-rate (ADR 39, ADR 46). A merge that orphans a rating loses something that cannot be regenerated.

- [ ] **Step 1: Write the failing tests**

```java
  @Test
  @DisplayName("should carry an owner edge to the canonical id when a merge is asserted")
  void shouldCarryAnOwnerEdgeToTheCanonicalIdWhenAMergeIsAsserted() {
    ingest.record(new LocalEntity("Q00900042", NodeKind.PERSON, "a minted person", NOW));
    ingest.record(new OwnerEdge("Q00900042", "Q42", "INFLUENCED_BY", NOW));

    ingest.record(new SameAs("Q00900042", "Q900", NOW));

    assertThat(edgesFrom("Q900")).hasSize(1);
  }

  @Test
  @DisplayName("should carry a rating to the canonical id when a merge is asserted")
  void shouldCarryARatingToTheCanonicalIdWhenAMergeIsAsserted() {
    ingest.record(new LocalEntity("Q00900042", NodeKind.PERSON, "a minted person", NOW));
    affinity.put(new AffinityRecord("Q00900042", 5, null, NOW));

    ingest.record(new SameAs("Q00900042", "Q900", NOW));

    assertThat(affinity.find("Q900")).isPresent();
    assertThat(affinity.find("Q900").orElseThrow().rating()).isEqualTo(5);
  }

  @Test
  @DisplayName("should keep the local id resolvable after a merge, because the log still names it")
  void shouldKeepTheLocalIdResolvableAfterAMergeBecauseTheLogStillNamesIt() {
    ingest.record(new LocalEntity("Q00900042", NodeKind.PERSON, "a minted person", NOW));

    ingest.record(new SameAs("Q00900042", "Q900", NOW));

    assertThat(graph.node("Q00900042")).isPresent();
  }
```

- [ ] **Step 2: Run and watch them fail.** Record each message.

- [ ] **Step 3: Implement `case SameAs`.** It resolves both the graph and affinity onto the canonical id, and leaves the local id resolvable.

- [ ] **Step 4: Prove the rating case bites.** Remove the affinity half of the resolution, watch the second test fail, restore. **Report the message** — an orphaned rating is the failure this test exists for.

- [ ] **Step 4b: A merged local entity must still have a label.** *Added 2026-08-31 after Task 1's
review.* `ratings/Labels.forQids` filters `instanceof NodeAssertion`, so a local entity — which is a
`LocalEntity`, not a `NodeAssertion` — has **no label in `listRatings`**. This fails silently rather
than throwing, and this task is what makes a rated local entity possible. Write the failing test,
watch it produce a blank or missing label, then fix `Labels.forQids`.

- [ ] **Step 5: Run the gate and commit**

---

### Task 5: The seventh dev tool

**Files:**
- Create: `src/main/java/com/robsartin/segue/own/{OwnRun,OwnCli}.java`
- Modify: `build.gradle.kts`
- Test: `src/test/java/com/robsartin/segue/own/OwnRunTest.java`, `src/test/java/com/robsartin/segue/own/OwnCliTest.java`

**Dev-side, never MCP.** ADR 26 held `assert_edge` back until corroboration was visibly working, and ADR 56 made it work — but on the MCP surface a *model* could call this, and owner claims skip the corroboration count. That would launder model-generated structure into the one tier exempt from quarantine, which is what ADR 23 exists to prevent. Read `retract/RetractCli.java` and `retract/RetractRun.java` and follow their shape exactly.

Three operations: `mint` (kind, label → a new local id), `assert` (from, to, type), `merge` (local, canonical).

- [ ] **Step 1: Write the failing tests**

Cover, each as its own test: `mint` appends exactly one claim and returns the id it minted; `assert` refuses when either endpoint is absent from the graph, the way `RetractRun` refuses a qid nobody claimed; `merge` refuses when the local id was never minted; `--dry-run` appends nothing; and an absent database is refused rather than created, following `RecommendationsAreNeverLoggedTest.anAbsentDatabaseIsRefused`.

- [ ] **Step 2: Run and watch them fail.** Record the messages.

- [ ] **Step 3: Implement**, then register the Gradle task beside `retractEntity` — same `group`, a description that says it needs no network, and the sqlite native-library grant `retractEntity` already carries.

- [ ] **Step 4: Run the gate and commit**

---

### Task 6: Refuse expansion out loud, fence the package, record the decision

**Files:**
- Modify: `src/main/java/com/robsartin/segue/mcp/SegueService.java`, `src/test/java/com/robsartin/segue/arch/ArchitectureTest.java`
- Create: an ADR (confirm the number with `ls docs/adr/ | tail -3`)
- Modify: `docs/adr/README.md`, `docs/adr/0022-*.md`, `docs/adr/0026-*.md`, `docs/adr/0033-*.md`, `CLAUDE.md`, `docs/developer-guide.md`

- [ ] **Step 1: Write the failing test for the refusal**

`expand_entity` on a local entity must say *this entity has no source to expand from*, distinctly. ADR 56 established that an empty `ExpandResult` already means both "found nothing" and "source unavailable"; a third meaning rebuilds the defect ADR 56 fixed. Assert on the message, not only on the emptiness.

- [ ] **Step 2: Write the failing ArchUnit rule and add `own` to `DEV_TOOL_PACKAGES`**

The sibling fences derive from that list (#105), so adding the package is most of the work. **Positive control required**: plant a violation from `own` to a sibling, watch the rule go red naming itself, revert, quote the message.

- [ ] **Step 3: Run both and watch them fail.** Record the messages.

- [ ] **Step 4: Implement.**

- [ ] **Step 5: Write the ADR and the three amendments**

The new ADR records the decision, the six alternatives from the spec with the reason each lost, and what it does not settle. Then, as **dated amendments, never edits** (ADR 1):

- **ADR 22** — clause 1 now admits a second identity kind, and what that costs.
- **ADR 26** — its stated condition for `assert_edge` is met, and the tool arrives dev-side for a reason ADR 26 did not anticipate.
- **ADR 33** — *"Two layers, two stores"* becomes three; `note_affinity` remains the only writer of affinity, but that no longer describes the whole first-person surface.

And correct `CLAUDE.md:183`, which says *"the two layers never meet below `SegueService`"* — not an ADR, so a correction rather than an amendment.

**Verify the ADR index sequence after editing** (#170): it is append-at-tail and has silently lost entries three times.

- [ ] **Step 6: Run the gate and commit**

---

## Self-Review

**Spec coverage.** Third-layer table → Tasks 2 and 3. Identity on ADR 58's mechanism → Task 1. Merge as appended equivalence, edges and ratings following → Task 4. No routing exemption → Task 2's second assertion. Corroboration exclusion → Task 3. Owner edges count toward degree → no task, because `CandidateSweep` counts edges without asking who claimed them; **Task 3's reviewer should confirm that rather than assume it.** Expansion refusing distinctly → Task 6. Dev-side tool surface → Task 5. Seven tests each with a control → Tasks 1–6. Four ADRs owed → Task 6.

**Verified against source rather than inferred.** `LoggedAssertion` is `sealed … permits NodeAssertion, AssertionRecord, Retraction`. `IngestService.apply` is a switch over it. `EdgeRecord.corroboration()` counts distinct `Provenance::sourceId`. `isUncorroboratedHypothesis()` requires **all** sources to `isHypothesis()`, and that is `sourceId.startsWith("llm:")`. `Retraction` is the first-person precedent in `domain`. `retractEntity` is the Gradle task shape to copy.

**A judgement worth a reviewer's eye.** Task 1 picks the local-id band inside ADR 58's leading-zero space. Get that wrong and a fixture and one of the owner's books become indistinguishable — which is the convention-splitting outcome #141 stopped short of, arriving from the other side.

**The honest risk.** Task 4 resolves affinity through an equivalence. It is the only place in this plan that can lose data that cannot be regenerated, and its positive control is the one step in the plan that must not be skipped.
