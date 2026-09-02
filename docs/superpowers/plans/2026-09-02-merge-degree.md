# A merge counts an entity's edges once — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to
> implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

> **This plan is not authorised to run.** The spec's decision is a *recommendation*; the owner
> ratifies or refuses Shape 1 first, and answers open questions 1 and 2, before Task 1 starts.
> Question 1's answer changes Task 4's scope.

**Goal:** after a merge, the merged entity's edges exist once — on the canonical id — so every
neighbour's degree is what the world justifies, and the recommender's top 25 is what it was before
the merge, score for score.

**Architecture:** both replay paths resolve an edge's endpoints through `Equivalences.in(log)`
before applying it. `GraphProjector.project` and `LogProjection.of` each already read the whole log
first, so the view a rewrite needs is in hand. `IngestService`'s `SameAs` arm keeps creating the
canonical node when nothing has claimed one and loses its edge-copy loop. The local entity keeps its
node and its affinity row; only its edges move. No `GraphStore` change: no `SameAs` reaches a live
graph (`OwnRun` appends through `IngestService.claim` and the graph is rebuilt at the next boot).

**Tech Stack:** Java (toolchain 25, `release 21`), Gradle 9.7.1, JUnit, AssertJ, SQLite, TinkerPop,
Apache Jena.

**Spec:** `docs/superpowers/specs/2026-09-02-merge-degree-design.md`

## Global Constraints

- **Pure TDD, one behaviour per red → green loop, and the red is run and quoted.** This is a bug
  fix, so it **reproduces first**: Task 2's guard is seen red for the right reason — naming the
  inflated candidate — before any production line changes.
- **Mikado, green at every committed step** (ADR 4). Task 1 is the probe: make the change, look at
  what breaks, **revert to green**, and only then implement leaf-first.
- **No parallel field is needed and the reason is stated rather than assumed:**
  `IngestService.apply` gains one parameter and has two call sites, both inside `ingest`. If the
  probe finds a third, stop and add a prerequisite leaf.
- Test names `should<Expected>When<Condition>` with `@DisplayName`. **ADRs are immutable** (ADR 1):
  a dated amendment to ADR 59 only, never an edit.
- **Never `git add -A`** — this worktree is shared. Stage by explicit path.
- Gate, **blocking, never backgrounded**: `SEGUE_REQUIRE_BROWSER=true ./gradlew check --rerun-tasks`.
  Measure the test count on `main` before Task 1 and quote it; quote it again at the end.
- **Only JDK 25 is installed and Gradle 9.7.1 launches on it.** Do NOT set
  `JAVA_HOME=$(/usr/libexec/java_home -v 21)` — it returns the JDK 25 path with exit 0. Plain
  `./gradlew`.
- **Never run a writing dev task** (`ownClaim`, `retractEntity`, any abbreviation).
  `~/.segue/segue.db` is never read, written, copied or created.
- Every qid, label and score in any fixture or document is invented (ADR 40, ADR 51).

---

### Task 1: The Mikado probe — find the prerequisites, then revert

**No production code is committed by this task.** Its output is the list Task 3 works from.

- [ ] Apply the target change crudely: in `IngestService.apply`'s `SameAs` arm delete the edge-copy
      loop, and in `GraphProjector.project` resolve `from`/`to` on every `AssertionRecord` and
      `OwnerEdge` through `Equivalences.in(assertions)` before `apply`. Do the same in
      `LogProjection.of`.
- [ ] Run the full gate, **blocking**. Record every failing test by name with the assertion that
      failed — not a count. Expect at least `MergeCarriesEverythingTest`, `BothFoldsAgreeTest`,
      `OwnerClaimProjectionTest` and the export fixtures; do not assume that list is complete.
- [ ] `git checkout -- src/` and re-run the gate to confirm green. **Record the count.**
- [ ] Write the prerequisite list into this plan under Task 3 before continuing.

### Task 2: The guard, seen red for the right reason

**Files:** create `src/test/java/com/robsartin/segue/recommend/MergeDoesNotInflateDegreeTest.java`.

- [ ] Build the spec's fixture: four known entities on a `--known` file (two rated, 5 and 4), six
      `PERSON` intermediates at degrees 12–22, thirty `GROUP` candidates at degrees 6–33 padded with
      `WORK` fillers, and one minted local entity — **unrated**, so this tests the graph half and not
      #92's rating fold — whose owner edges reach one intermediate and then candidates. Drive the
      real `RecommendCli.main` against a scratch log in a `@TempDir` at `--scorer lift`,
      `--min-degree 5`, `--top 25`.
- [ ] **Instrument control first.** Replay the unchanged log twice and assert the two report files
      are byte-identical. An empty difference and a dead instrument look the same; this separates
      them. Green on today's code.
- [ ] **The guard.** Append `SameAs.declared(local, canonical)`, run again, and assert the after
      top 25 equals the before top 25 — same qids, same order, scores equal within 1e-9 — with the
      merged entity's id resolved to the canonical one. Parameterise the merged entity's edge count
      over 2, 5 and 20.
- [ ] Run it. **Red, at all three degrees**, the message naming the displaced candidate — at 20
      edges the rank-1 entry loses 12.50 % and drops to rank 2; at 2 edges a degree-10 candidate
      loses 9.15 % and moves three places. **Quote the actual failure text.** A compile error is not
      a red.
- [ ] Leave it red? No: commit it `@Disabled` with a one-line reason naming this issue, or hold the
      commit until Task 4 — **decide and say which**, and if `@Disabled`, remove it in Task 4.
- [ ] Gate and commit the fixture plus the instrument control.

### Task 3: The prerequisite leaves, green on today's code

Filled in from Task 1's list. Each leaf lands green on unchanged production code, so the change in
Task 4 has nothing left to knock over.

- [ ] For every test the probe broke, separate the assertion that encodes *"the canonical id gained
      the edges"* — which stays true — from the assertion that encodes *"the local id kept them"* —
      which is the behaviour being changed. Give each its own `@DisplayName` naming what it holds.
      `MergeCarriesEverythingTest` is the largest of these.
- [ ] `BothFoldsAgreeTest` keeps comparing the pair; only the expected sets move, in Task 4. Do not
      weaken it here.
- [ ] Gate and commit.

### Task 4: Both folds resolve endpoints through the equivalences

**The two folds change in one commit, deliberately.** `BothFoldsAgreeTest` is the test that forbids
a graph and a picture of that graph from disagreeing; splitting this across two commits would make
it red in between, which is the thing that test exists to prevent.

**Files:**
- Modify `src/main/java/com/robsartin/segue/ingest/GraphProjector.java` — build
  `Equivalences.in(assertions)` alongside the existing `Retractions.in(assertions)` and pass it down.
- Modify `src/main/java/com/robsartin/segue/ingest/IngestService.java` — `apply` takes the
  equivalences and resolves an `AssertionRecord`/`OwnerEdge`'s endpoints; the `SameAs` arm keeps the
  canonical-node creation and loses `carry`'s edge loop.
- Modify `src/main/java/com/robsartin/segue/export/LogProjection.java` — the same resolution over
  its own accumulator; `carry` goes.
- Modify the tests Task 3 separated.

- [ ] Resolve endpoints in the boot fold. Run Task 2's guard: green at all three degrees.
- [ ] Resolve endpoints in the export fold. `BothFoldsAgreeTest` green with the merged entity as one
      node carrying the edges and the local node present and isolated.
- [ ] `IngestService.record`'s live path: a `SameAs` arriving there cannot move edges already
      recorded, and nothing produces one. **Make that explicit** — refuse it with a message naming
      `OwnRun`, or state the limitation in javadoc. Decide and say which.
- [ ] Answer open question 1 in code: a claim appended *after* a merge is now resolved too, because
      the resolution is over the whole log. If the owner chose the other reading, `Equivalences` must
      carry a position and this step changes shape — **stop and re-plan rather than guessing.**
- [ ] Check `ratings/Labels` and `OwnRun`'s merge report still read correctly against a folded graph.
- [ ] **Positive control.** Restore `carry`'s edge-copy loop in the boot fold alone and run the
      guard: red at all three degrees, and `BothFoldsAgreeTest` red as well. Quote both. Revert.
- [ ] Gate and commit.

### Task 5: The record

- [ ] **ADR 59, dated amendment (2026-09-02, issue #178), addition only.** What the merge bullet
      said, what it says now — the local id keeps its node and its affinity row and its edges move —
      the measurement that forced it (the headline table, aggregates and invented ids only), the two
      rejected shapes with their reasons, and the consequence for `exportGraph`: a merged local id
      draws as an isolated node. Cite `Equivalences`, `GraphProjector` and `LogProjection` as the
      authority for the mechanism; **mirror no table of theirs.**
- [ ] `Equivalences`' javadoc: its measured before/after paragraph describes the defect this closes,
      and the "the graph half of a merge is not this" paragraph is now wrong. Correct both, and say
      what they said.
- [ ] The developer guide's merge paragraph and `IngestService.carry`'s javadoc.
- [ ] `git diff -- docs/adr/ | grep '^-' | grep -v '^---'` empty. `AdrIndexTest` green. Gate, commit,
      push, open the PR.

---

## Self-Review

**Spec coverage.** Decision (fold at projection, edges move, node stays) → Task 4. Measurement as a
guard → Task 2. Instrument control → Task 2. Positive control seen red → Task 2 (before the fix) and
Task 4 (after it). Rejected shapes → spec, recorded in Task 5. ADR 59 → Task 5. Open questions 1 and
2 → gated before Task 1, and Task 4 stops rather than guesses.

**Placeholders.** Task 3 is deliberately empty until Task 1's probe fills it — that is Mikado's
shape, not a gap. Task 2's `@Disabled`-or-hold and Task 4's live-`SameAs` handling are two decisions
the implementer must make and state, not omissions.

**Type consistency.** One signature moves: `IngestService.apply` gains an `Equivalences`. Two call
sites, both in `ingest`; no parallel field, and Task 1 is instructed to stop if the probe finds a
third.
