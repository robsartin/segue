# Export order — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `LogProjection` emits nodes and edges in log order; a test proves byte-identical output across iteration-order conditions, red on today's `Map.copyOf` first.

**Architecture:** One task. `LogProjection` keeps a `LinkedHashMap` (insertion = log order) and exposes nothing new; a new test in `export/` renders a fixture log twice under shuffled map orders and asserts identical bytes and log order.

**Tech Stack:** Java, JUnit, AssertJ.

**Spec:** `docs/superpowers/specs/2026-09-02-export-order-design.md`

## Global Constraints

- **Pure TDD / red first**: the guard is seen red for the right reason on today's code before it is trusted; every control quoted and reverted. Test names `should<Expected>When<Condition>` with `@DisplayName`. No ADR. **Stage by explicit path, git stderr visible.** Commits end `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`. Never cite a `.superpowers/` path from a committed file.
- Gate, **blocking**: `SEGUE_REQUIRE_BROWSER=true SEGUE_REQUIRE_GRAPHVIZ=true ./gradlew check --rerun-tasks`. Baseline on `main` is 1172 tests — measure it. **Only JDK 25 is installed; Gradle 9.7.1 launches on it.** Plain `./gradlew`; never `java_home -v 21`.
- **Never run a writing dev task**; `~/.segue/segue.db` is never read, written, or created.

---

### Task 1: The order, proven

**Files:** Modify: `src/main/java/com/robsartin/segue/export/LogProjection.java` (~line 70, `Map.copyOf`; and any other copy/collect that drops order). Create: `src/test/java/com/robsartin/segue/export/ExportOrderIsLogOrderTest.java`. Modify: `docs/developer-guide.md` ("Looking at the graph", one sentence). Read: `BothFoldsAgreeTest` (fixture shape), `DotWriterTest`/`GraphMlWriterTest`, ADR 43.

- [ ] **Step 1 — RED.** The test builds a log with ≥8 nodes and edges, projects and renders it under two deliberately different map iteration orders (feed the fold through a wrapper that shuffles, or vary the hash seed in a forked JVM if the harness has one — say which), asserts the DOT bytes equal and the GraphML bytes equal. On today's code it must red; quote the two node orders.
- [ ] **Step 2 — GREEN.** `LinkedHashMap` in the fold (and wherever else order is dropped — grep `Map.copyOf`, `Set.copyOf`, `toMap`, `toSet` in `export/`). Green.
- [ ] **Step 3 — log order pinned.** Second assertion: the rendered node order equals the order of first surviving claims in the fixture log. Control: reverse the fixture's claim order → the rendered order reverses (red if the fold sorted instead).
- [ ] **Step 4 — controls.** Re-plant `Map.copyOf` → Step 1 reds; revert. Existing writer tests untouched and green — if any expectation had to move, say so.
- [ ] **Step 5 — guide sentence; gate; commit.**
