# Javadoc in the gate — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `./gradlew check` runs `javadoc` strictly (all doclint groups but `missing`, warnings as errors), the two existing errors are fixed without losing their prose, and a test keeps every main-source citation of a test class or member resolving.

**Architecture:** Task 1 is the build change plus the `OwnCli` fix and the guide's gate list. Task 2 is `JavadocCitationsTest` in `arch` on `RepositoryTree`. Task 3 is ADR 34's dated amendment. No production behaviour changes.

**Tech Stack:** Gradle 9.7.1 Kotlin DSL, JDK 25 javadoc (`-Xdoclint`, `-Werror`), JUnit, AssertJ.

**Spec:** `docs/superpowers/specs/2026-09-02-javadoc-in-check-design.md`

## Global Constraints

- **Pure TDD / red first.** Task 1's red is `./gradlew check` failing in `:javadoc` on the unchanged `OwnCli` once the task is attached (quote it); Task 2's test is seen red via a planted rename before the real citations are trusted. Every control quoted and reverted.
- Test names `should<Expected>When<Condition>` with `@DisplayName`. **ADRs immutable**: ADR 34 gets an appended dated amendment only. **Stage by explicit path, git stderr visible.**
- Gate, **blocking**: `SEGUE_REQUIRE_BROWSER=true ./gradlew check --rerun-tasks`. Baseline on `main` is 1061 tests — measure it. After Task 1 the gate includes `:javadoc`.
- **Only JDK 25 is installed; Gradle 9.7.1 launches on it.** Plain `./gradlew`; never `java_home -v 21`.
- **Never run a writing dev task**; `~/.segue/segue.db` is never read, written, or created.
- **Doclint:** `-Xdoclint:all,-missing` and `-Werror` on `tasks.javadoc`; the exclusion of `missing` is a stated decision in the build comment (99 undocumented record components today; not this issue's work). Nothing else excluded.
- Never cite a `.superpowers/` path from a committed file. Count words in prose are drift: the guide's gate list loses "four".

---

### Task 1: `javadoc` in `check`, the two errors fixed, the guide's list

**Files:**
- Modify: `build.gradle.kts` (`tasks.javadoc { … }` with the doclint options and a comment naming #195 and the `missing` decision; `tasks.check { dependsOn(…, tasks.javadoc) }` at ~line 362)
- Modify: `src/main/java/com/robsartin/segue/own/OwnCli.java` lines ~86–104 (the interface javadoc's two `@param` blocks become `<p>` paragraphs headed by the component name in `{@code}`; every sentence kept)
- Modify: `docs/developer-guide.md` ~line 1054 ("attaches four things" → count-free; item 5 `javadoc`, one sentence on the doclint groups and why `missing` is out)

- [ ] **Step 1 — attach, see RED.** Add `tasks.check { dependsOn(tasks.javadoc) }` and the doclint options; run `./gradlew check` (blocking) on the unchanged `OwnCli` → red in `:javadoc` with the two `invalid use of @param` errors. Quote.
- [ ] **Step 2 — fix `OwnCli`.** Paragraphs, no sentence lost (diff the prose). GREEN: `./gradlew javadoc` prints no warning and no error (quote the empty output / "BUILD SUCCESSFUL" with zero `warning:` lines).
- [ ] **Step 3 — controls.** (a) `{@link NoSuchClass}` in any main javadoc → `check` red in `:javadoc` naming file:line; (b) a new record with an undocumented component in a scratch main file → green (quote; this is the `missing` exclusion); (c) `@param database` restored on the interface → red. Revert each.
- [ ] **Step 4 — the guide list.** Rewrite count-free; add item 5. `DeveloperGuideEnumerationsTest` green.
- [ ] **Step 5 — gate and commit.** Note the `:javadoc` time added to `check`.

### Task 2: `JavadocCitationsTest`

**Files:**
- Create: `src/test/java/com/robsartin/segue/arch/JavadocCitationsTest.java`
- Read: `RepositoryTree.java`, `DocumentationLinksTest.java` (the mention-vs-complete recogniser pattern and failure-message style), `ArchitectureTest.java` (rules are `static final ArchRule name =`)

**Interfaces:** consumes `RepositoryTree.root()/read()`; produces nothing.

- [ ] **Step 1 — the test, GREEN on the tree, then RED by a planted rename.** Strict pattern: `{@code` + optional package prefix + a simple name containing `Test` + optional `.`/`#` member + optional `()` + `}`. Resolve: class = a file `src/test/java/**/<Name>.java`; member = a `void <member>(` method or a `static final … <member> =` field in it. Mention: any `{@code` whose content contains a token ending in `Test` or containing `Test` followed by a word char, not consumed by the strict pattern → "unsupported citation shape". Vacuity: ≥1 site. Run: green, with the count of sites in the assertion message. Control: rename `theExporterOnlyReads` in a scratch edit of `ArchitectureTest` → red naming each citing file:line (five of them) and the member; revert. Plant `{@code ArchitectureTest theExporterOnlyReads}` → red "unsupported citation shape"; revert.
- [ ] **Step 2 — javadoc on the test** states what it checks, what it deliberately does not (`{@code MainClass.member}` citations of main classes; convertible to `{@link}` now that `javadoc` gates), and the recogniser rule.
- [ ] **Step 3 — gate and commit.**

### Task 3: ADR 34's dated amendment

- [ ] Append, after any existing amendment, in the shape of ADR 18 line 86 / ADR 32's amendments: date 2026-09-02, issue #195; the gate was four things and `javadoc` failed outside it; now a fifth, strict except `missing`, with the measured reason; `JavadocCitationsTest` cited by name; the rejected alternatives from the spec. `git diff -- docs/adr/ | grep '^-' | grep -v '^---'` empty (quote). `AdrIndexTest` and `DocumentationLinksTest` green. Gate and commit.

---

## Self-Review

**Spec coverage.** Decision 1 → Task 1 (all three controls, the guide list); decision 2 → Task 2; decision 3 → Task 3. **Placeholders:** none. **Type consistency:** `RepositoryTree.root()/read()`; `tasks.javadoc`/`tasks.check` are the Gradle names.
