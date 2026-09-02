# ADR counts — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** every ADR that states a count code can move carries a dated amendment naming where the value lives; ADR 52 first.

**Architecture:** Docs only. A one-off script (kept in the report, not the repo) lists candidate hits; hand triage; one amendment per affected ADR.

**Tech Stack:** Markdown; JUnit for the existing ADR tests.

**Spec:** `docs/superpowers/specs/2026-09-02-adr-counts-design.md`

## Global Constraints

- **Pure TDD / red first**: every guard is seen red for the right reason before it is trusted; every control quoted and reverted. Test names `should<Expected>When<Condition>` with `@DisplayName`.
- **ADRs immutable** (ADR 1): dated amendments appended after any existing ones, in the shape of ADR 18 line 86; `git diff -- docs/adr/ | grep '^-' | grep -v '^---'` empty and quoted.
- **Stage by explicit path, git stderr visible.** Commits end `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`.
- Gate, **blocking**: `SEGUE_REQUIRE_BROWSER=true ./gradlew check --rerun-tasks`. Baseline on `main` is 1061 tests — measure it. **Only JDK 25 is installed; Gradle 9.7.1 launches on it.** Plain `./gradlew`; never `java_home -v 21`.
- **Never run a writing dev task**; `~/.segue/segue.db` is never read, written, or created. Never cite a `.superpowers/` path from a committed file. Count words in prose are drift.

---

### Task 1: The sweep, then the amendments

**Files:** Modify: `docs/adr/0052*.md` and every ADR the triage selects. Read: ADR 32's amendments (the disclaimer shape), ADR 18 line 86 (amendment lead), `docs/adr/README.md`.

- [ ] **Step 1 — the instrument.** A script over `docs/adr/*.md` (front matter and lines after the first `**Amendment` excluded) matching numerals and number-words (one…twenty, dozen) within four words of a countable noun (tests, rules, tools, adapters, implementors, sources, packages, endpoints, columns, queries, ADRs, files, classes). Paste the full hit list into the report.
- [ ] **Step 2 — triage by hand.** For each hit: *amend* (a claim about the present that code moves), *dated observation* (fixed forever — say why), or *not a count*. Record every ruling in the report.
- [ ] **Step 3 — ADR 52's amendment** (`Amendment (2026-09-02, issue #157): …`): the count was a dated observation, name the class/package that is the browser suite now, and say counts of tests are read from the tree.
- [ ] **Step 4 — the other amendments**, same shape, one per ADR selected in Step 2; a claim that is a dated observation gets no amendment.
- [ ] **Step 5 — deletions check** (quote empty), `AdrIndexTest` + `DocumentationLinksTest` green, full gate, commit.
