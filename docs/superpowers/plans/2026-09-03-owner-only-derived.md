# The owner-only edge is derived from the data, not just named — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** a new fixture self-test asserts that the triples `Fixture.assertions()` asserts exactly
once, by the owner, are exactly the triples `Fixture.isOwnerOnlyEdge` accepts — proven able to fail
by a positive control that is planted, observed red, and reverted before commit.

**Architecture:** One new test class, `FixtureIsOwnerOnlyEdgeMatchesTheDataTest`, in
`com.robsartin.segue.fixture`, beside `FixtureQidsDenoteNothingTest`. It reads only
`Fixture.assertions()` and `Fixture.isOwnerOnlyEdge` — no `GraphStore`, no engine. No production code
changes. `Fixture.java`, `GraphStoreContract.java` and `TinkerGraphStoreContractTest.java` are read
but not modified.

**Tech Stack:** Java (toolchain 25, `release 21`), JUnit 5, AssertJ.

**Spec:** `docs/superpowers/specs/2026-09-03-owner-only-derived-design.md` — it holds the reasoning
for keeping `isOwnerOnlyEdge` name-based, the derivation's exact definition, and the residual note
about `isOwnerOnlyEdge` not reading `typeCode`. Do not restate that reasoning here; cite it.

## Global Constraints

- **No production code changes.** Only a new test file is created. `Fixture.java` is edited only as
  a temporary, unstaged positive control and must be reverted before the commit.
- **The positive control is the RED step for this guard.** There is no production behaviour to drive
  into existence, so the usual red-before-green ordering does not apply here; the spec's "Positive
  control" section is the authority on the ordering (pass on the real fixture → red once the plant is
  in → pass again once it is reverted) and on which assertion must be the one that fails.
- Test names `should<Expected>When<Condition>` with `@DisplayName`.
- **Never `git add -A`**; stage the one new file by explicit path with git's stderr visible.
- Gate, **blocking**: `SEGUE_REQUIRE_BROWSER=true SEGUE_REQUIRE_GRAPHVIZ=true ./gradlew check --rerun-tasks`.
  Plain `./gradlew` — only JDK 25 is installed.
- **Never run a writing dev task** (`own`, `ownClaim`, `retractEntity`, or any other one);
  `~/.segue/segue.db` is never read, written, or created.
- Never cite a `.superpowers/` path from a committed file.

---

### Task 1: `FixtureIsOwnerOnlyEdgeMatchesTheDataTest`, proven able to fail, then committed

**Files:**
- Create: `src/test/java/com/robsartin/segue/fixture/FixtureIsOwnerOnlyEdgeMatchesTheDataTest.java`
- Read only: `src/test/java/com/robsartin/segue/fixture/Fixture.java` (temporarily edited for the
  positive control in Step 3, then reverted in Step 4 — no net change),
  `src/test/java/com/robsartin/segue/fixture/FixtureQidsDenoteNothingTest.java` (sibling style),
  `src/main/java/com/robsartin/segue/domain/AssertionRecord.java`,
  `src/main/java/com/robsartin/segue/domain/EdgeRecord.java`,
  `src/main/java/com/robsartin/segue/domain/Provenance.java`

**Interfaces:**
- Consumes: `Fixture.assertions()`, `Fixture.isOwnerOnlyEdge(EdgeRecord)`,
  `AssertionRecord.edgeKey()`, `AssertionRecord.provenance()`, `Provenance.isOwner()`,
  `EdgeRecord.key()`. All existing; none change.
- Produces: nothing consumed elsewhere — this is a leaf self-test.

- [ ] **Step 1 — write the test, exactly as the spec's "The test" section gives it.** Group
      `Fixture.assertions()` by `AssertionRecord::edgeKey`; derive the owner-only set as the keys of
      groups of size 1 whose sole assertion's `provenance().isOwner()` is `true`; build one
      representative `EdgeRecord` per triple (any one assertion in the group; `sources` may be
      `List.of()` since `isOwnerOnlyEdge` never reads it — spec's "Rejected" section explains why key
      comparison is used instead of `EdgeRecord` equality); collect the keys `isOwnerOnlyEdge`
      accepts; assert the derived set is non-empty and equal to the accepted set.
- [ ] **Step 2 — run it against the unmodified fixture.** Expected: **PASS**. This is not the guard's
      red — see Global Constraints — but it must be confirmed before the positive control means
      anything: a test that passes vacuously (e.g. a typo that always returns two empty sets) would
      also "pass" here, which is exactly what Step 3 is for. Quote the passing run
      (`./gradlew test --tests
      'com.robsartin.segue.fixture.FixtureIsOwnerOnlyEdgeMatchesTheDataTest'`).
- [ ] **Step 3 — the positive control.** Temporarily edit `Fixture.java`'s `assertions()` to add, as
      the new last element (adjusting the trailing `);`):

      ```java
      owner(LOCAL_NOVELIST, "AUTHORED", LOCAL_NOVEL),
      wikidata(LOCAL_NOVELIST, "AUTHORED", LOCAL_NOVEL, null, null, "S-owner-only-plant"));
      ```

      Re-run the same single-test command. It must go **RED**, and specifically on
      `assertThat(derivedOwnerOnly).isNotEmpty()` — the derived set loses its only member because the
      triple's assertion group is now size 2 — not on the equality assertion after it. **Quote the
      actual failure output**, naming which assertion failed. If the equality assertion fails instead
      (or both pass), stop: that means the derivation or the vacuity check is wired wrong, and Step 1
      needs revisiting before continuing — do not paper over it by adjusting the control.
- [ ] **Step 4 — revert the plant exactly.** `git diff -- src/test/java/com/robsartin/segue/fixture/Fixture.java`
      must be empty. Re-run the single test: **PASS** again, confirming the revert was exact and the
      guard returns to its baseline state.
- [ ] **Step 5 — gate, blocking.** `SEGUE_REQUIRE_BROWSER=true SEGUE_REQUIRE_GRAPHVIZ=true ./gradlew check --rerun-tasks`.
      Confirm `Fixture.java` still shows no diff (the gate must run against the reverted state, not
      the plant). Quote the build result and the total test count.
- [ ] **Step 6 — commit.** Stage only the new test file by explicit path (`git add
      src/test/java/com/robsartin/segue/fixture/FixtureIsOwnerOnlyEdgeMatchesTheDataTest.java`), with
      git's stderr visible. One commit. The report quotes Step 2's pass, Step 3's red (the exact
      assertion and message), and Step 5's gate result.

---

## Self-Review

**Spec coverage.** The derivation definition, the non-empty and equality assertions, the
representative-`EdgeRecord`-with-empty-`sources` construction, and the positive control's exact plant
and expected failing assertion are all in Task 1's steps, matching the spec's "The test" and
"Positive control" sections. The spec's "Decision" (keep `isOwnerOnlyEdge` name-based) and "Rejected"
sections require no code — they are recorded in the spec and cited, not re-argued in the plan.

**Placeholders:** none.

**Type consistency:** `AssertionRecord.edgeKey()`/`EdgeRecord.key()` produce identically-shaped
strings (`fromQid + " " + typeCode + " " + toQid`), confirmed by reading both methods; no adapter or
conversion is needed between the two sets being compared.

**What could still go wrong.** The plant lands on the wrong assertion (equality fails instead of
non-emptiness, or both pass) — Step 3 says to stop and revisit rather than adjust the control to fit.
A stray whitespace or comment difference leaving `Fixture.java`'s diff non-empty after the revert —
Step 4's explicit `git diff` check is the guard against committing the plant by accident.
