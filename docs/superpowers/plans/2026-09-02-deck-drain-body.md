# deck.html drains refused bodies — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** both `!response.ok` paths in `deck.html` drain the body; a browser test proves the stub sees zero in-flight exchanges after a refusal.

**Architecture:** Two one-line page edits; one new test method in the deck browser test class using the existing stub's in-flight counter (#169).

**Tech Stack:** JUnit, headless Chrome harness (`SEGUE_REQUIRE_BROWSER`), the JDK `HttpServer` stub.

**Spec:** `docs/superpowers/specs/2026-09-02-deck-drain-body-design.md`

## Global Constraints

- **Pure TDD / red first**: every guard is seen red for the right reason before it is trusted; every control quoted and reverted. Test names `should<Expected>When<Condition>` with `@DisplayName`.
- **ADRs immutable** (ADR 1): dated amendments appended after any existing ones, in the shape of ADR 18 line 86; `git diff -- docs/adr/ | grep '^-' | grep -v '^---'` empty and quoted.
- **Stage by explicit path, git stderr visible.** Commits end `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`.
- Gate, **blocking**: `SEGUE_REQUIRE_BROWSER=true ./gradlew check --rerun-tasks`. Baseline on `main` is 1061 tests — measure it. **Only JDK 25 is installed; Gradle 9.7.1 launches on it.** Plain `./gradlew`; never `java_home -v 21`.
- **Never run a writing dev task**; `~/.segue/segue.db` is never read, written, or created. Never cite a `.superpowers/` path from a committed file. Count words in prose are drift.

- Browser tests are real-Chrome: run them **blocking**, never backgrounded; a red must be an assertion, not a compile error or a 4-second failure.

---

### Task 1: The test, red on the page as it is; then the two lines

> **NOT EXECUTED (2026-09-02).** Step 1's own stop condition fired: the test was green on the unchanged page, so the premise was false and the plan was abandoned there. See the superseding section at the foot of the spec.

**Files:** Modify: `src/main/resources/rate/deck.html` (two `return` sites); the deck browser test class under `src/test/java/com/robsartin/segue/rate/` (find `DeckBehaviourTest` and the stub with the in-flight counter); `docs/retry-precondition-evidence.md` (one dated line). Read: #169's harness javadoc about the precondition.

- [ ] **Step 1 — the test, RED.** `shouldLeaveNoExchangeInFlightWhenTheServerRefuses`: stub refuses (non-2xx with a small JSON body) the card fetch, then the rating POST; after each, poll the in-flight counter briefly (bounded, not a fixed sleep) and assert it reaches zero. Run blocking with `SEGUE_REQUIRE_BROWSER=true`: red on the unchanged page — quote the counter value. If it is green on arrival, the premise is wrong: stop and report (it may mean Chrome drains small bodies itself, in which case the issue's option 2 wins and the spec must change).
- [ ] **Step 2 — the two lines** (`await response.text();` before each refused `return`). GREEN.
- [ ] **Step 3 — control:** revert one line → red naming which path; restore.
- [ ] **Step 4 — the evidence page's dated line;** `DocumentationLinksTest` green. Gate and commit.
