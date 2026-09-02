# SEGUE_REQUIRE_GRAPHVIZ — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** a missing `dot` fails the build when `SEGUE_REQUIRE_GRAPHVIZ` is set, skips visibly otherwise; CI sets it; every other external-dependency skip is enumerated.

**Architecture:** Reuse the browser flag's helper/shape for Graphviz; set the flag in `.github/workflows`; document beside the browser flag; a report section enumerating skips.

**Tech Stack:** JUnit 6 assumptions, GitHub Actions.

**Spec:** `docs/superpowers/specs/2026-09-02-require-graphviz-design.md`

## Global Constraints

- **Pure TDD / red first**: every guard is seen red for the right reason before it is trusted; every control quoted and reverted. Test names `should<Expected>When<Condition>` with `@DisplayName`.
- **ADRs immutable** (ADR 1): dated amendments appended after any existing ones, in the shape of ADR 18 line 86; `git diff -- docs/adr/ | grep '^-' | grep -v '^---'` empty and quoted.
- **Stage by explicit path, git stderr visible.** Commits end `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`.
- Gate, **blocking**: `SEGUE_REQUIRE_BROWSER=true ./gradlew check --rerun-tasks`. Baseline on `main` is 1061 tests — measure it. **Only JDK 25 is installed; Gradle 9.7.1 launches on it.** Plain `./gradlew`; never `java_home -v 21`.
- **Never run a writing dev task**; `~/.segue/segue.db` is never read, written, or created. Never cite a `.superpowers/` path from a committed file. Count words in prose are drift.

- `SEGUE_REQUIRE_GRAPHVIZ=true` joins the gate line for this branch once it exists.

---

### Task 1: The flag, the CI line, the guide, the enumeration

**Files:** Modify: the Graphviz-dependent tests (find by grep: they reference `dot`/Graphviz and skip when absent) or the helper they share; `.github/workflows/*.yml` (beside `SEGUE_REQUIRE_BROWSER`); `docs/developer-guide.md` (beside the browser flag). Read: how `SEGUE_REQUIRE_BROWSER` is read and where it throws.

- [ ] **Step 1 — read the browser mechanism** and name the helper/lines in the report.
- [ ] **Step 2 — RED first:** with `PATH` lacking `dot` and `SEGUE_REQUIRE_GRAPHVIZ=true`, the Graphviz tests must fail — before the change they skip (quote the `skipped` count from `build/test-results`); after, they fail naming Graphviz and the flag (quote).
- [ ] **Step 3 — the other direction:** flag unset, `dot` absent → skipped, visible (quote); flag set, `dot` present → green.
- [ ] **Step 4 — CI:** add the env var beside the browser flag. Guide: one paragraph beside the browser flag's.
- [ ] **Step 5 — enumerate every skip-on-missing-dependency** in `src/test` (grep list in the report) with a ruling each.
- [ ] **Step 6 — gate (with the new flag set) and commit.**
