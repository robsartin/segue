# ADR index headings — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `AdrIndexTest` reds on an unknown or duplicated `## ` heading in the ADR index.

**Architecture:** One test method added to `AdrIndexTest`, reusing its section parser; the allowed-name list lives in the test with the toolkit cited as authority.

**Tech Stack:** JUnit, AssertJ.

**Spec:** `docs/superpowers/specs/2026-09-02-adr-index-headings-design.md`

## Global Constraints

- **Pure TDD / red first**: every guard is seen red for the right reason before it is trusted; every control quoted and reverted. Test names `should<Expected>When<Condition>` with `@DisplayName`.
- **ADRs immutable** (ADR 1): dated amendments appended after any existing ones, in the shape of ADR 18 line 86; `git diff -- docs/adr/ | grep '^-' | grep -v '^---'` empty and quoted.
- **Stage by explicit path, git stderr visible.** Commits end `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`.
- Gate, **blocking**: `SEGUE_REQUIRE_BROWSER=true ./gradlew check --rerun-tasks`. Baseline on `main` is 1061 tests — measure it. **Only JDK 25 is installed; Gradle 9.7.1 launches on it.** Plain `./gradlew`; never `java_home -v 21`.
- **Never run a writing dev task**; `~/.segue/segue.db` is never read, written, or created. Never cite a `.superpowers/` path from a committed file. Count words in prose are drift.

---

### Task 1: The heading assertion

**Files:** Modify: `src/test/java/com/robsartin/segue/arch/AdrIndexTest.java`. Read: `docs/adr/README.md` (the headings as they are), the #170 spec in `docs/superpowers/specs/` if present (the toolkit's role).

- [ ] **Step 1 — derive the allowed set** from the index as committed (`grep '^## ' docs/adr/README.md`); paste into the report.
- [ ] **Step 2 — the test, RED first.** `shouldRejectAnUnknownOrDuplicatedSectionHeadingWhenTheIndexIsRead`: every `## ` heading ∈ allowed set; no heading twice; message cites `adr_toolkit/index.py _AXIS_DISPLAY_NAMES` as the authority and names the offender. Plant `## Uncategorised` in a scratch edit of the index → red; plant a duplicate section with one row → red; quote both; revert.
- [ ] **Step 3 — javadoc** on the test: why the list is hand-held (toolkit-owned names), the accepted cost, and the split it closes.
- [ ] **Step 4 — gate and commit.**
