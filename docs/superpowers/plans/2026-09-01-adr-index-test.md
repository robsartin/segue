# The ADR index is machine-checked — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A test that fails when `docs/adr/README.md` disagrees with `docs/adr/`, wired so the build actually runs it on index edits.

**Architecture:** `AdrIndexTest` in `com.robsartin.segue.arch`, parsing the index's load-bearing row shape and comparing it to the ADR files' names, headings and front matter; `inputs.dir("docs/adr")` in `build.gradle.kts`; shared `repositoryRoot()`/`read()` extracted for both doc tests.

**Tech Stack:** Java (toolchain 25, `release 21`), Gradle 9.7.1, JUnit, AssertJ.

**Spec:** `docs/superpowers/specs/2026-09-01-adr-index-test-design.md`

## Global Constraints

- **Pure TDD, one small behaviour per red→green loop**; the red observed for the right reason and quoted. Do not write five tests and run them once.
- Test names read `should<Expected>When<Condition>` and carry a `@DisplayName`.
- **ADRs are immutable** (ADR 1): Task 2 adds a dated amendment, never an edit. **ADR 34 is not moved.**
- **Never `git add -A`** — stage by explicit path.
- Full gate, **blocking, never backgrounded**: `SEGUE_REQUIRE_BROWSER=true ./gradlew check --rerun-tasks`. Baseline on `main` is 1019 tests / 113 classes — measure it, do not trust this line.
- **Only JDK 25 is installed and Gradle 9.7.1 launches on it.** Do NOT set `JAVA_HOME=$(/usr/libexec/java_home -v 21)` — it returns the JDK 25 path with exit 0, a silent no-op. Plain `./gradlew`.
- **Never run a writing dev task** (`ownClaim`, `retractEntity`, any abbreviation — `./gradlew own` runs `:ownClaim`). `~/.segue/segue.db` is never read, written, or created.
- **Every assertion gets a positive control**: plant the defect, watch it go red naming the right thing, quote the message, revert.

---

### Task 1: `AdrIndexTest`, the shared helper, and the input declaration

**Files:**
- Create: `src/test/java/com/robsartin/segue/arch/AdrIndexTest.java`
- Create: `src/test/java/com/robsartin/segue/arch/RepositoryTree.java` (package-private: `root()`, `read(Path)`, moved from `DeveloperGuideEnumerationsTest`)
- Modify: `src/test/java/com/robsartin/segue/arch/DeveloperGuideEnumerationsTest.java` (call the helper; no behaviour change)
- Modify: `build.gradle.kts` (the `inputs` block)

**Interfaces:**
- Consumes: the index row shape `- [N. Title](NNNN-slug.md) — _Status_`, section headings `## `, ADR front matter `status:` and heading `# N. Title`.
- Produces: nothing other tasks consume beyond the test's name, which Task 2's amendment cites.

- [ ] **Step 0: Extract the helper first, green.** Move `repositoryRoot()` and `read(Path)` into `RepositoryTree`; make `DeveloperGuideEnumerationsTest` call it; run the gate; commit. A refactor with no behaviour has no red to observe — say so, verify by the gate.

- [ ] **Loop A — every ADR file has exactly one row.** Parse the index rows and the `docs/adr/NNNN-*.md` names. Red: it does not compile / `AdrIndexTest` does not exist. Green. **Control:** delete one row from the README, run, quote the failure (it must name the file), revert.

- [ ] **Loop B — every row names a file that exists.** Control: add a row for `0099-does-not-exist.md`, quote, revert.

- [ ] **Loop C — no number claimed twice**, in the index or on disk. Control: duplicate a row, quote, revert.

- [ ] **Loop D — rows ascend within each section.** Group rows by the preceding `## ` heading. Control: swap two adjacent rows inside Uncategorized, quote (it must name the section and the pair), revert. **Confirm the untouched index passes** — Language `9 10 11 34` is ascending and must not fail.

- [ ] **Loop E — each row agrees with its file on number, title and status.** Filename number == row number == heading number; row title == heading title; row `_Status_` == front matter `status:`. Control: change one row's status word, quote, revert. If the current index already disagrees with a file somewhere, **that is a finding to report, not smooth over** — stop and say which.

- [ ] **Step 6: Declare the input.** Add `inputs.dir("docs/adr")` beside the existing `developerGuide` declaration, with a comment in the same voice saying why. **Control:** edit only `docs/adr/README.md` (add then remove a blank line is not enough — make a change the test sees, e.g. the Loop A plant), run `./gradlew check` **without** `--rerun-tasks`, and confirm `test` executed and went red rather than reporting `UP-TO-DATE`. Revert. Quote what you saw.

- [ ] **Step 7: Run the gate and commit.**

---

### Task 2: Record it in ADR 1, and correct the issue's misreading

**Files:**
- Modify: `docs/adr/0001-record-architecture-decisions.md` — dated amendment only

- [ ] **Step 1:** Append a dated amendment (2026-09-01, issue #170): the index is now machine-checked; `AdrIndexTest` is the authority for what the index must contain, in the shape ADR 32 uses for its rule table; the index is sectioned and ordered *within* sections, so the issue's "34 between 11 and 12" was a misreading and ADR 34 stays; and what is deliberately not checked (description and `Related:` prose). Cite the test by name; mirror no rule table.

- [ ] **Step 2:** `git diff -- docs/adr/ | grep '^-'` must be empty. Index count unchanged (60/60) — and now `AdrIndexTest` says so; run it.

- [ ] **Step 3: Run the gate and commit.**

---

## Self-Review

**Spec coverage.** Assertions 1–5 → Loops A–E. `inputs.dir` with its own control → Step 6. Shared helper → Step 0. ADR 1 amendment → Task 2. Section-order correction recorded → Task 2 Step 1 and the issue comment. ADR 34 not moved → Global Constraints. Rejected alternatives → spec only, by design.

**Placeholders.** None: each loop names its plant and what the failure must name.

**Type consistency.** `RepositoryTree.root()` / `read(Path)` named once in Task 1 and used by both tests; Task 2 consumes only the test's name.
