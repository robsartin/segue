# settle() as a condition — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `DeckBehaviourTest.settle()` waits for the page to report the re-rating landed (bounded), not 600 ms; the ordering assertion is proven to still catch a late attempt.

**Architecture:** Test-only change in `DeckBehaviourTest`; reuse `untilSent()`'s polling shape and the page's own state (`#problem` or whatever element the page writes — read the page and the evidence to name it).

**Tech Stack:** JUnit, headless Chrome harness.

**Spec:** `docs/superpowers/specs/2026-09-02-settle-condition-design.md`

## Global Constraints

- **Pure TDD / red first**: the guard is seen red for the right reason before it is trusted; controls quoted and reverted. Test names `should<Expected>When<Condition>` with `@DisplayName`.
- **Browser tests drive real headless Chrome**: run blocking with `SEGUE_REQUIRE_BROWSER=true`, never backgrounded; a red is an assertion message, not a compile error or a suspiciously fast failure. Other agents share this machine; timings vary — take any timing at least three times and report the spread.
- **Stage by explicit path, git stderr visible.** Commits end `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`. No ADR. Never cite a `.superpowers/` path from a committed file.
- Gate, **blocking**: `SEGUE_REQUIRE_BROWSER=true ./gradlew check --rerun-tasks`. **Only JDK 25 is installed; Gradle 9.7.1 launches on it.** Plain `./gradlew`; never `java_home -v 21`.
- **Never run a writing dev task**; `~/.segue/segue.db` is never read, written, or created.

- **This branch is based on `main` after #188 merges** (it adds a test to the same file); rebase before starting and say so.

---

### Task 1: The condition, the late-attempt control, the soak

**Files:** Modify: `src/test/java/com/robsartin/segue/rate/DeckBehaviourTest.java` (`settle()` and its call sites; the retried-rating test's javadoc), `docs/retry-precondition-evidence.md` (dated line). Read: `untilSent()` and its javadoc; `src/main/resources/rate/deck.html` (what the page writes when a re-rating lands); the evidence page.

- [ ] **Step 1 — name the condition** from the page and the evidence; write it in `settle()`'s javadoc first.
- [ ] **Step 2 — the late-attempt control on the OLD code** (this is the red that shows what the sleep was for): plant a stub that sends one extra attempt 700 ms after the page reports done → with the 600 ms sleep the ordering assertion is *green by luck* (quote); keep the plant.
- [ ] **Step 3 — replace the sleep** with the bounded condition (page state + stub in-flight zero). With the plant still in: the assertion must now red on the late attempt (quote) — if it does not, the condition is not observing the right thing; stop and say so. Remove the plant → green.
- [ ] **Step 4 — the wait is seen to wait**: delay the page state via the stub; measure three runs; quote the spread; a return before the state appears is a fail.
- [ ] **Step 5 — soak**: 20 consecutive blocking runs of the retried-rating test; report pass count and wall-time spread.
- [ ] **Step 6 — evidence line**, gate, commit.
