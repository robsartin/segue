# Labels folds retractions — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `listRatings` stops printing a confident label for a retracted entity, making true the invariant `Labels` already claims.

**Architecture:** `Labels.forQids` already builds `Retractions.in(logged)` and uses it only for merges. Apply `Retractions.survives` to the claims that name an entity — `NodeAssertion` and `LocalEntity` — exactly as `GraphProjector:70` does.

**Tech Stack:** Java (toolchain 25, `release 21`), Gradle 9.7.1, JUnit, AssertJ, ArchUnit, SQLite.

**Spec:** `docs/superpowers/specs/2026-09-01-labels-folds-retractions-design.md`

## Global Constraints

- **Pure TDD, one small behaviour per red→green loop.** Write the failing test, **run it and observe a real failure for the right reason**, then the minimum code. Reports must quote what each failure actually said. Do not write five tests and run them once.
- Test names read `should<Expected>When<Condition>` and carry a `@DisplayName`.
- **ADRs are immutable** (ADR 1). This task writes no ADR and edits none.
- **Never `git add -A`** — stage by explicit path.
- Full gate, **blocking, never backgrounded**: `SEGUE_REQUIRE_BROWSER=true ./gradlew check --rerun-tasks`. Baseline on `main` is **1000 tests / 111 classes** — confirm the real baseline before starting and report it.
- **Only JDK 25 is installed and Gradle 9.7.1 launches on it.** Do NOT set `JAVA_HOME=$(/usr/libexec/java_home -v 21)` — there is no JDK 21, and that command returns the **JDK 25 path with exit 0**, so it silently does nothing while looking like version pinning. Plain `./gradlew` is correct.
- **Never run a writing dev task** — not `ownClaim`, not `retractEntity`, not with `--dry-run`, not by abbreviation. `./gradlew own` resolves to `:ownClaim` by camel-case hump matching and *runs*. To check a task name: `./gradlew tasks --all | grep -i <name>`.
- `~/.segue/segue.db` is the owner's real database. Do not read, write, or create it. Tests use temp paths.

---

### Task 1: Fold retractions in `Labels`

**Files:**
- Modify: `src/main/java/com/robsartin/segue/ratings/Labels.java`
- Test: `src/test/java/com/robsartin/segue/ratings/LabelsTest.java` (create if absent — check first)

**Interfaces:**
- Consumes: `Retractions.in(List<LoggedAssertion>)` and `Retractions.survives(int index, LoggedAssertion)`, both already imported and used in this file for the merge case.
- Produces: no signature change. `forQids(AssertionLog, Set<String>)` keeps returning `Map<String, String>`, omitting qids it has no surviving label for — which is what makes the row fall to `AffinityRow.NO_LABEL`.

**Read `GraphProjector` around line 70 first.** It is the precedent: `if (!retractions.survives(i, assertion)) { continue; }`. Copy the shape rather than inventing a second one — the point of this fix is that one rule decides what a log means, in every reading of it.

**Five loops, five separate red phases.** Do not batch them.

- [ ] **Loop A — a rating on a retracted entity lists as `(not in the graph)`.** Retract a `NodeAssertion` and assert the qid is absent from `forQids`. Watch it fail; quote the message.

- [ ] **Loop B — a retracted `LocalEntity` is folded too.** Both claim types name an entity (#92), so a fold handling only `NodeAssertion` is a silent half-fix — the exact shape #92 kept producing. Its own red.

- [ ] **Loop C — a claim made *after* the retraction still counts.** Re-claiming an entity restores its name, because `survives` compares the claim's index against the last retraction of that qid. **Write this test even though you expect it to pass once A and B are green** — it pins behaviour that comes for free and would otherwise be undefended. If it passes on arrival, say so and demonstrate its teeth by mutation instead: make the fold suppress every claim of a retracted qid regardless of index, and quote the failure.

- [ ] **Loop D — a never-retracted entity is unaffected.** The regression case. Same rule as C: if green on arrival, show its teeth by mutation rather than claiming it as a red.

- [ ] **Loop E — the merge fold still works, and a retracted merge still carries no label.** This behaviour is #92's and must not move.

- [ ] **Step 2: Correct the `Labels` javadoc.** It asserts *"a label here is the label `get_entity` would return"* as though it held. Say what is true, and say that retraction is folded by the same `Retractions` the merge case uses. Do not add a second explanation of the rule — cite `GraphProjector`'s use of it.

- [ ] **Step 3: Run the gate and commit.**

---

## Self-Review

**Spec coverage.** Retraction folded → Loops A and B. `NO_LABEL` unchanged, no new string → no code needed, asserted by Loop A. Re-claim after retraction → Loop C. No regression → Loop D. Merge fold unchanged → Loop E. Javadoc overclaim → Step 2. No new ADR → deliberate, stated in the spec.

**Placeholders.** None: the one line to copy is quoted, its source named, and the two loops likely to pass on arrival carry explicit instructions for what to do instead of claiming a false red.

**Type consistency.** No signature changes. `survives(int, LoggedAssertion)` and `Retractions.in(List<LoggedAssertion>)` are used exactly as the file already uses them for merges.
