# Live set by annotation — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `DeveloperGuideEnumerationsTest` derives the live-test set from `@Tag("live")` on compiled classes, with controls in both directions red on today's substring derivation first.

**Architecture:** One task; one derivation method replaced inside the existing test; ArchUnit import reused.

**Tech Stack:** JUnit, ArchUnit, AssertJ.

**Spec:** `docs/superpowers/specs/2026-09-02-live-set-by-annotation-design.md`

## Global Constraints

- **Pure TDD / red first**: the guard is seen red for the right reason on today's code before it is trusted; every control quoted and reverted. Test names `should<Expected>When<Condition>` with `@DisplayName`. No ADR. **Stage by explicit path, git stderr visible.** Commits end `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`. Never cite a `.superpowers/` path from a committed file.
- Gate, **blocking**: `SEGUE_REQUIRE_BROWSER=true SEGUE_REQUIRE_GRAPHVIZ=true ./gradlew check --rerun-tasks`. Baseline on `main` is 1172 tests — measure it. **Only JDK 25 is installed; Gradle 9.7.1 launches on it.** Plain `./gradlew`; never `java_home -v 21`.
- **Never run a writing dev task**; `~/.segue/segue.db` is never read, written, or created.

---

### Task 1: Replace the substring derivation

**Files:** Modify: `src/test/java/com/robsartin/segue/arch/DeveloperGuideEnumerationsTest.java` (the live-set derivation, ~line 212). Read: `PackageListsTest` (ArchUnit import + derivation shape), `ArchitectureTest` (`ClassFileImporter` usage), the guide's live row, every `@Tag(` in `src/test` (measure: how many, at what level, any constant/meta usage).

- [ ] **Step 1 — measure.** List every `@Tag` usage and value in `src/test`; note the `probe` tag (#167). Paste in the report.
- [ ] **Step 2 — RED both ways on today's code.** (a) Plant `// @Tag("live") is what the smoke tests carry` in a non-live test class → the guide row reds falsely. (b) In a scratch copy of a live class, tag through a constant (`static final String LIVE = "live"; @Tag(LIVE)`) → the derivation misses it and the row is green while the set is wrong (quote). Revert both.
- [ ] **Step 3 — GREEN.** Derive via ArchUnit: classes (or methods' declaring classes) annotated `@Tag` with value `live`. Re-run (a) and (b): (a) no false red; (b) enrolled. Revert.
- [ ] **Step 4 — the existing direction kept.** Remove one live class from the guide row → red naming it; restore. Vacuity: set non-empty (control: filter to none → red).
- [ ] **Step 5 — javadoc**, gate, commit.
