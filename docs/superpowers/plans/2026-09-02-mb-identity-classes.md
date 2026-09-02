# The MBID bridge returns classes — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `MusicBrainzIdentity` returns a described identity (QID, kind, label, classes) from the batched P434 query it already spends, `MusicBrainzSourceAdapter` fills `neighbors()` from it behind a label-and-classes guard, and the round trips saved are a number this repository measured offline rather than inherited from ADR 55's live probe.

**Architecture:** `BridgedIdentity` is a new record in `musicbrainz`; `MusicBrainzIdentity.identitiesFor` replaces `qidsFor`; `WikidataMusicBrainzIdentity.BATCH_TEMPLATE` gains `OPTIONAL { ?item wdt:P31 ?class }` and `SERVICE wikibase:label`, groups rows per item, and derives the kind with `KindMapper.fromInstanceOf` in `app`; the adapter emits a neighbour only when the identity carries a real label and a non-empty `instanceOf`. ADR 61 records the reversal; ADR 55 gains a dated amendment.

**Tech Stack:** Java (toolchain 25, `release 21`), Gradle 9.7.1, JUnit, AssertJ, Jackson, SPARQL over the Wikidata Query Service.

**Spec:** `docs/superpowers/specs/2026-09-02-mb-identity-classes-design.md`

## Global Constraints

- **Pure TDD, one behaviour per red→green loop**; every red run and quoted. A test written and then made to pass in one move is not TDD.
- Test names `should<Expected>When<Condition>` with `@DisplayName`. **ADRs immutable** (ADR 1). **Never `git add -A`** — stage by explicit path.
- Gate, **blocking, never backgrounded**: `SEGUE_REQUIRE_BROWSER=true ./gradlew check --rerun-tasks`. **Baseline measured on this branch: 121 suites, 1061 tests, 0 failures, 0 skips, BUILD SUCCESSFUL in ~1m06s.**
- **Only JDK 25 is installed and Gradle 9.7.1 launches on it.** Do NOT set `JAVA_HOME=$(/usr/libexec/java_home -v 21)` — it returns the JDK 25 path with exit 0. Plain `./gradlew`.
- **Offline.** Never call the live MusicBrainz or Wikidata APIs; `@Tag("live")` stays excluded from the gate. `check` must reach no network.
- **Never run a writing dev task.** `~/.segue/segue.db` is never read, written, or created.
- **Do not touch the two-pass ingest mechanism.** The three couplings and the fallback-only subtraction live in `wikidata`; nothing here changes `ClaimMapper`, `ReverseClaims`, `EdgeTypes` or `WikidataSourceAdapter`.
- **Mikado:** green at every committed step. Task 2 adds the new seam method beside `qidsFor`, not instead of it; the old one dies in Task 4 once every implementor has moved.

---

### Task 1: Measure the saving offline, before anything is widened

**Files:**
- Create: `src/test/java/com/robsartin/segue/musicbrainz/NeighbourFetchCountTest.java`

The point of doing this first is that "22 fetches" must be an observation of today's code, not a prediction about tomorrow's.

- [ ] **Loop A — the counting harness, green on today's code.** Build the `MusicBrainzNeighbourIdentityTest` shape (real `SqliteAssertionLog.inMemory`, `TinkerGraphStore`, `IngestService`, the committed fixture through `MusicBrainzClient.readingFrom`, `StubIdentity` mapping **all 22** mappable target MBIDs to distinct QIDs), with an `EntityResolver` that counts `fetch` calls and returns a claim carrying `Q5`. Expand the seed. Assert the count is **22**. Run it: it should be green first time — **that is not yet evidence**, so:
- [ ] **Loop B — prove the instrument can fail.** Assert **23**, run, and watch it red with the actual count in the message; quote it; restore 22. A counter never seen to disagree has not been shown to count.
- [ ] **Loop C — the shape controls, so the number cannot fall for the wrong reason.** In the same expansion assert (i) the 22 edges are recorded (`graph.edges(SEED_QID)` names each neighbour `MEMBER_OF` the seed), and (ii) each neighbour node carries `Q5`. Red first where they do not hold; quote.
- [ ] **Step 4 — record the baseline in the test's javadoc**: 22 mappable relations of 24, 22 distinct MBIDs, all `Person`; 22 fetches, 1 bridge round trip. Cite ADR 55's 214-of-461 as the live figure this fixture stands in for; do **not** restate ADR 55's table.
- [ ] **Step 5 — confirm open question 1 against the tests that would see it.** Read `CorroborationAcrossSourcesTest` and report whether a neighbour claim stamped `"wikidata"` from a MusicBrainz expansion disturbs any assertion there or in `MusicBrainzSourceAdapterTest`. **Report the finding; do not choose.** If it does disturb one, stop and raise it.
- [ ] **Step 6 — gate and commit.**

### Task 2: The seam widens, beside the old method

**Files:**
- Create: `src/main/java/com/robsartin/segue/musicbrainz/BridgedIdentity.java`
- Modify: `src/main/java/com/robsartin/segue/musicbrainz/MusicBrainzIdentity.java`
- Modify: `src/main/java/com/robsartin/segue/app/WikidataMusicBrainzIdentity.java`
- Modify: `src/test/java/com/robsartin/segue/app/WikidataMusicBrainzIdentityTest.java`

Parallel field, Mikado-style: `identitiesFor` arrives as a `default` that delegates to `qidsFor` and describes nothing, so all six implementors keep compiling and the gate stays green while nothing yet consumes it.

- [ ] **Loop A — the record and the defaulted method.** `BridgedIdentity(String qid, NodeKind kind, String label, List<String> instanceOf)`, compact constructor copying the list and requiring a non-blank `qid`. Add `default Map<String, BridgedIdentity> identitiesFor(Collection<String>)` delegating to `qidsFor`. Test the default's shape first, red, then implement.
- [ ] **Loop B — the widened query, red.** Assert `decodedQuery(stub)` contains `wdt:P31` and `wikibase:label` alongside `wdt:P434`, and that the parsed result carries the classes. Red against today's `BATCH_TEMPLATE`; quote. Then widen the template and override `identitiesFor` in `WikidataMusicBrainzIdentity`. **Still one round trip per 100 MBIDs** — assert the stub's request count is unchanged for a 22-MBID batch.
- [ ] **Loop C — rows multiply, entities do not.** A stub response with the same `?item` on several rows (two P31 values) must yield **one** `BridgedIdentity` carrying **both** classes. Red first; quote. Key on the item, as `ReverseClaims` does.
- [ ] **Loop D — the bare-QID label rule.** `wikibase:label` returning the QID itself (`"Q0900002"`) must leave the identity **undescribed** rather than labelling a node `Q0900002`. Red first; quote. Same rule as `ReverseClaims.rememberLabel`.
- [ ] **Loop E — an item with no P31** yields an identity with an empty `instanceOf` (not an absent entry, not a throw). Red first.
- [ ] **Step 6 — re-measure `MAX_MBIDS_PER_QUERY`.** Drive the *widened* template through `WikidataClient`'s own encoding and record the actual request-URI length for 50/100/200 MBIDs, as the existing javadoc did for the narrow one. Update the javadoc's figures to the measured ones and say whether 100 is still the right number. **Measure; do not reason from `180 + 43n`.**
- [ ] **Step 7 — the existing `qidsFor` tests stay green and untouched.** Gate and commit.

### Task 3: The adapter fills `neighbors()`, behind the guard

**Files:**
- Modify: `src/main/java/com/robsartin/segue/musicbrainz/MusicBrainzSourceAdapter.java`
- Modify: `src/test/java/com/robsartin/segue/musicbrainz/MusicBrainzNeighbourIdentityTest.java`
- Modify: `src/test/java/com/robsartin/segue/musicbrainz/StubIdentity.java`
- Modify: `src/test/java/com/robsartin/segue/musicbrainz/NeighbourFetchCountTest.java`

This is where ADR 55's decision is reversed, so the tests holding it are rewritten deliberately and their javadoc rewritten with them — **not** deleted, and not quietly relaxed.

- [ ] **Loop A — the guard, first and on its own.** A resolved neighbour with an **empty** `instanceOf`, or an unreal label, is **not** emitted as a neighbour. Assert the existing-node case directly: seed a neighbour carrying `Q5`, expand with a bridge that describes it with no classes, and assert `Q5` **survives**. Red first — this is #143's erasure, and it must be seen. Quote the red.
- [ ] **Loop B — the described neighbour is emitted.** Bridge returns `Q5` and a real label; adapter emits a `NodeAssertion` with `Provenance(<sourceId from open question 1>, qid, assertedAt, 1.00)`. `MusicBrainzNeighbourIdentityTest`'s two tests must be green **for the new reason** — the classes arrive from the bridge rather than from the fetch that no longer happens. Watch each red before it passes.
- [ ] **Loop C — rewrite that test's class javadoc.** It currently states the decision this change reverses. It must now say what the guard is, that the erasure case is still asserted (Loop A), and that GAP 7's empty-list case still holds. Keep the third test as it is.
- [ ] **Step 4 — the measurement flips.** `NeighbourFetchCountTest` now asserts **0** fetches for 22 described neighbours, at the same one bridge round trip. Watch it go from 22 to 0.
- [ ] **Step 5 — positive control, definition of done.** With the bridge describing **none** of the 22, the count must be **22 again**, the edges still recorded, and no node's classes erased. Then describe exactly 11 and assert **11**. A saving that does not vary with what the bridge describes is not measuring the bridge.
- [ ] **Step 6 — gate and commit.**

### Task 4: Retire `qidsFor`

**Files:**
- Modify: `MusicBrainzIdentity.java` (drop `qidsFor`, drop the `default`), `WikidataMusicBrainzIdentity.java`
- Modify: all five doubles — `StubIdentity`, `CorroborationAcrossSourcesTest.BridgeThatCannotAnswer`, `MusicBrainzSourceAdapterTest.UnavailableIdentity` / `UnavailableOnBatch` / `RecordingIdentity`
- Modify: `WikidataMusicBrainzIdentityTest`, `MusicBrainzIdentityTest`, `MusicBrainzLiveSmokeTest`, `WikidataMusicBrainzIdentityLiveTest`

- [ ] **Step 1 — migrate the five doubles and every caller**, one at a time, gate green after each. `RecordingIdentity` keeps recording what it was handed; the two unavailable doubles keep throwing `MusicBrainzIdentityUnavailableException` from the new method — the seam's declared failure is unchanged and must stay asserted.
- [ ] **Step 2 — delete `qidsFor` and the `default`.** Compile: nothing left refers to it. The `@Tag("live")` tests must still **compile** even though they do not run — check with `./gradlew compileTestJava`.
- [ ] **Step 3 — the seam javadoc.** `identitiesFor`'s contract restates what `qidsFor`'s said and adds what is new: an absent MBID still carries no QID (ADR 22 clause 2, measured at 49% of neighbours, and still not an error); a **present** entry with an empty `instanceOf` means "bridged but undescribed", which is a third answer the old signature could not give. The exception contract is unchanged.
- [ ] **Step 4 — gate and commit.**

### Task 5: Record it

**Files:**
- Create: `docs/adr/0061-*.md`
- Modify: `docs/adr/0055-what-the-musicbrainz-adapter-refuses.md` (dated amendment, addition only), `docs/adr/README.md`
- Modify: `docs/developer-guide.md`, `MusicBrainzSourceAdapter`'s class javadoc, `port/SourceAdapter`'s `id()` javadoc if open question 1 lands on `"wikidata"`

- [ ] **Step 1 — ADR 61.** The decision, and the alternatives rejected with the reason each lost (the spec's Rejected list). Record the measured offline before/after and the fixture it rests on; cite `NeighbourFetchCountTest` and the guard by name. **Cite the code as the authority — mirror no table**, and do not restate ADR 55's live figures beyond one citation. State open question 2 (truthy `wdt:P31` vs `ClaimMapper.instanceOf`) as a known, accepted exposure with `ReverseClaims` as the precedent.
- [ ] **Step 2 — ADR 55's dated amendment**, addition only: its `#143` clause is reversed by ADR 61 on new evidence — the bridge can carry classes at no extra round trip, which was not on the table when 55 was written — and its `subgroup` half (#142) **stands unchanged**. Verify `git diff -- docs/adr/0055*.md | grep '^-' | grep -v '^---'` is empty. `AdrIndexTest` green.
- [ ] **Step 3 — `MusicBrainzSourceAdapter`'s class javadoc.** Several paragraphs currently explain why it emits no `neighbors()`. Replace them with what it now does and the guard that makes it safe; keep the GAP 7 / GAP 9 paragraphs.
- [ ] **Step 4 — developer guide.** The "Two-pass ingest" chapter's neighbour-identity paragraphs mention only Wikidata's reverse pass as a source of inline identity. Add MusicBrainz's bridge. **Do not restate any number that lives in the ADR** — cite it. Check the doc-link test stays green.
- [ ] **Step 5 — gate and commit.**

---

## Self-Review

**Spec coverage.** Widened seam → Task 2. Guard that makes it non-erasing → Task 3 Loop A. Offline before/after in its own right → Task 1, flipped in Task 3 Step 4. Positive controls → Task 1 Loops B/C, Task 3 Step 5. Six implementors → Task 4. Batch arithmetic re-measured → Task 2 Step 6. ADR 55 immutable + reversal → Task 5. Rejected alternatives → spec + ADR 61.

**Open questions are routed, not guessed.** Q1 (`sourceId`) is investigated in Task 1 Step 5 and *reported*, and Task 3 Loop B leaves it as a parameter until the owner rules; Q2 is recorded in ADR 61; Q3 shapes Task 5's two steps; Q4 is a measurement in Task 2 Step 6. **An implementer that finds Q1 disturbs an existing test must stop and raise it, not choose.**

**Placeholders.** One deliberate: the neighbour claim's `sourceId` in Task 3 Loop B. Everything else is named.

**Type consistency.** `SourceAdapter`, `EntityResolver`, `ExpandResult`, `ExpandContext`, `NodeAssertion`, `NodeRecord` and `Provenance` are unchanged in shape. The only signature that changes is `MusicBrainzIdentity`'s, and it changes twice on purpose — added in Task 2, old one removed in Task 4 — so the gate is green in between.
