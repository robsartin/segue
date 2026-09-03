# Late flush marker — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `FlushWait` records whether the flush marker landed after the page's first socket; the harness prints it; the retry-control test skips with that reason instead of passing vacuously; a fixture-driven unit test proves each branch.

**Architecture:** Test-harness code only (`src/test`): `FlushWait` keeps consuming the NetLog tail after navigate and exposes the observation; `HeadlessChrome` prints it; the retry-control test in `DeckBehaviourTest` (or wherever #186 put it — find it) assumes on it. Fixture NetLog tails under `src/test/resources`.

**Tech Stack:** JUnit 6 (`Assumptions`), the existing constants-driven NetLog reader.

**Spec:** `docs/superpowers/specs/2026-09-02-late-flush-marker-design.md`

## Global Constraints

- **Pure TDD / red first**: the guard is seen red for the right reason before it is trusted; controls quoted and reverted. Test names `should<Expected>When<Condition>` with `@DisplayName`.
- **Browser tests drive real headless Chrome**: run blocking with `SEGUE_REQUIRE_BROWSER=true`, never backgrounded; a red is an assertion message, not a compile error or a suspiciously fast failure. Other agents share this machine; timings vary — take any timing at least three times and report the spread.
- **Stage by explicit path, git stderr visible.** Commits end `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`. No ADR. Never cite a `.superpowers/` path from a committed file.
- Gate, **blocking**: `SEGUE_REQUIRE_BROWSER=true ./gradlew check --rerun-tasks`. **Only JDK 25 is installed; Gradle 9.7.1 launches on it.** Plain `./gradlew`; never `java_home -v 21`.
- **Never run a writing dev task**; `~/.segue/segue.db` is never read, written, or created.

- **Do not edit `DeckBehaviourTest`'s stub or its `inFlight` machinery** — issue #188 is changing that file on another branch right now; confine edits there to the single assumption line in the retry-control test, and say so in the report.

---

### Task 1: The observation, proven on fixtures; then surfaced

**Files:** Modify: the `FlushWait`/NetLog classes under `src/test/java/com/robsartin/segue/rate/` (find by grep `sawMarker`), `HeadlessChrome` (the print), the retry-control test (one `assumeTrue`/`assumeFalse` line with a reason). Create: a unit test beside `FlushWait` with three fixture tails under `src/test/resources/`. Modify: the evidence page that records the flush wait (grep `flush` in `docs/*.md`).

- [ ] **Step 1 — find and read**: `FlushWait`, `sawMarker()`, how the tail is polled and where polling stops today; the marker's event type and the first-socket event type as constants; the retry-control test's precondition comment. Capture a real NetLog from `build/reports/netlet/` or by running one browser test (blocking) and trim it into three fixtures: marker-before-socket, marker-after-socket, no-marker.
- [ ] **Step 2 — RED**: `shouldReportTheMarkerAfterTheFirstSocketWhenTheTailSaysSo` over the after fixture → fails (no such observation yet); quote. Then implement: keep consuming after navigate until the first socket event is seen (bounded), record positions; the before fixture → false; the no-marker fixture → the existing path. GREEN.
- [ ] **Step 3 — surface**: the harness prints the observation beside the existing "did not see the marker" line; the retry-control test assumes the precondition held, with a reason naming both positions. Control: force the late case (feed the after fixture through whatever seam the harness allows, or a system property that points `FlushWait` at a fixture — say which) → the retry-control test is `skipped` in `build/test-results` with the reason; quote the XML line; revert.
- [ ] **Step 4 — evidence page** dated paragraph; `DocumentationLinksTest` green. Gate and commit.
