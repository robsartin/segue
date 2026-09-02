# The `ownClaim` runbook — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `docs/developer-guide.md` gains an `ownClaim` runbook chapter whose every example is executed through `OwnCli.parse` by a test that is red on `main` today.

**Architecture:** One task, one red→green loop with several controls. A new test class in `com.robsartin.segue.arch` (beside `DeveloperGuideEnumerationsTest`, reusing `RepositoryTree`) extracts `./gradlew ownClaim --args="…"` lines from the guide, shell-splits them, and parses each with `OwnCli.parse`; it asserts at least one example per subcommand, no `~`, and no usage error. The chapter is written to make it green. No production change, no ADR.

**Tech Stack:** Java (toolchain 25, `release 21`), JUnit, AssertJ; the guide is Markdown.

**Spec:** `docs/superpowers/specs/2026-09-02-ownclaim-runbook-design.md`

## Global Constraints

- **Pure TDD**: the test is written first and seen RED on the unchanged guide for the right reason (no `ownClaim` examples); quote it. Then the chapter; GREEN; then the planted controls, each quoted and reverted.
- Test names `should<Expected>When<Condition>` with `@DisplayName`. **No ADR edit.** **Never `git add -A`.** Stage by explicit path.
- Gate, **blocking**: `SEGUE_REQUIRE_BROWSER=true ./gradlew check --rerun-tasks`. Baseline on `main` is 1050 tests — measure it.
- **Only JDK 25 is installed; Gradle 9.7.1 launches on it.** Plain `./gradlew`; never `java_home -v 21` (returns JDK 25 with exit 0).
- **Never run a writing dev task** (`ownClaim`, `retractEntity`, any abbreviation — `./gradlew own` runs `:ownClaim`). `~/.segue/segue.db` is never read, written, or created. The test calls `OwnCli.parse` only — never `OwnCli.run` or `main` — with an invented home such as `/home/invented`, exactly as `OwnCliTest` does.
- **Every behavioural claim in the chapter is quoted from or checked against the class the spec names** (`OwnRun`, `Equivalences`, `IngestService.carry`, `RetractRun`, `Retractions.survives`, `ArchitectureTest` ~1221, ADR 59, ADR 60, #178). Nothing about what the tool prints is written from memory.
- Never cite a `.superpowers/` path from a committed file.

---

### Task 1: The executable-examples test, and the chapter that makes it green

**Files:**
- Create: `src/test/java/com/robsartin/segue/arch/DeveloperGuideRunbooksTest.java`
- Modify: `docs/developer-guide.md` — new `## ` chapter after "Rating one card at a time" and before "How to read an ADR against the code"; one Contents entry in the same position
- Read only: `src/main/java/com/robsartin/segue/own/OwnCli.java`, `OwnRun.java`; `retract/RetractCli.java` (for the seam), `RetractRun.java`; `domain/Equivalences.java`, `domain/LocalEntity.java`, `domain/Retractions.java`; `ingest/IngestService.java` (`carry`); `arch/ArchitectureTest.java` (rules named in the spec, and ~line 1221); `docs/adr/0059*.md`, `0060*.md`, `0044*.md`; the existing "Taking something back out" chapter as the template; `gh issue view 178`

**Interfaces:**
- Consumes: `OwnCli.parse(String[] args, String envDatabase, String userHome)` (package-private in `own` — the test is in `arch`, so use whatever visibility route `OwnCliTest`'s pattern allows; if `parse` is not reachable from `arch`, place the test in `com.robsartin.segue.own` beside `OwnCliTest` and say so); `RepositoryTree.root()`/`read()`.
- Produces: nothing consumed later.

- [ ] **Step 1 — the test, RED on the unchanged guide.** Extract every guide line matching `./gradlew ownClaim --args="(.*)"`; shell-split the captured string (double-quoted outer string already stripped; single-quoted runs are one argument; `$HOME` → the invented home); assert (a) at least one example whose first argument is each of `mint`, `assert`, `merge`; (b) no example contains `~`; (c) each parses via `OwnCli.parse(args, null, "/home/invented")` without throwing — on failure the message names the guide line and the parser's sentence. Run it; quote the red: it must be (a), naming the missing subcommands, on `main`'s guide.
- [ ] **Step 2 — the chapter, GREEN.** Write it in the retraction chapter's shape (opening `bash` block with `--dry-run` then the real thing for each subcommand; then `###` sections). Content per the spec's "The decision" §2, and only that. Add the Contents entry. Run the test; green. Run `DeveloperGuideEnumerationsTest` too.
- [ ] **Step 3 — controls.** Change one example to `merge … --kind WORK` → red naming the line and "unknown option … for this operation"; put `~/.segue/segue.db` in one → red naming it; delete `--db` from one → red carrying `RequiredDatabase`'s refusal text. Quote each; revert each; tree clean.
- [ ] **Step 4 — `retractEntity` examples.** If `RetractCli` has a `parse(String[], String, String)` seam, extend the test to the guide's `./gradlew retractEntity --args="…"` lines with the same three assertions (subcommand assertion replaced by "at least one example"). If it does not, say so in the test's javadoc and in the report; do not add a seam to production code for this.
- [ ] **Step 5 — gate and commit** (one commit for test + chapter is fine; two if the red/green story reads better).

---

## Self-Review

**Spec coverage.** Executable check → Step 1; chapter content and placement → Step 2; controls → Step 3; retract coverage conditional → Step 4; no ADR → constraints. **Placeholders:** none. **Type consistency:** `OwnCli.parse` signature is the one in the code (`String[]`, `String`, `String`).
