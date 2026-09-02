# Derive the package lists from the tree — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `DEV_TOOL_PACKAGES` and `ADAPTER_PACKAGES` are asserted equal to sets derived from the tree, so a new tool or adapter reds the build until the constant names it.

**Architecture:** A new `PackageListsTest` in `com.robsartin.segue.arch` derives the dev-tool set from `build.gradle.kts`'s `JavaExec` `mainClass` values and from `*Cli` classes with `main`, and the adapter set from ArchUnit's class graph (implementors of `..port..` interfaces); asserts each equals its constant; and compares the guide's dev-tool sentence to the derived set. ADR 32 gains a dated amendment.

**Tech Stack:** Java (toolchain 25, `release 21`), Gradle 9.7.1, JUnit, AssertJ, ArchUnit.

**Spec:** `docs/superpowers/specs/2026-09-02-derive-package-lists-design.md`

## Global Constraints

- **Pure TDD, one behaviour per red→green loop**; reds quoted. Where a derivation is green on arrival (the sets agree today), prove its teeth by a **planted** control, not by claiming a red.
- Test names `should<Expected>When<Condition>` with `@DisplayName`. **ADRs immutable** (ADR 1): a dated amendment to ADR 32 only. **Never `git add -A`.**
- Gate, **blocking, never backgrounded**: `SEGUE_REQUIRE_BROWSER=true ./gradlew check --rerun-tasks`. Baseline on `main` is 1046 tests — measure it.
- **Only JDK 25 is installed and Gradle 9.7.1 launches on it.** Do NOT set `JAVA_HOME=$(/usr/libexec/java_home -v 21)` — it returns the JDK 25 path with exit 0. Plain `./gradlew`.
- **Never run a writing dev task** (`ownClaim`, `retractEntity`, any abbreviation — `./gradlew own` runs `:ownClaim`). `~/.segue/segue.db` is never read, written, or created.
- The constants stay; they are asserted, not removed. No production change.

---

### Task 1: `PackageListsTest`

**Files:**
- Create: `src/test/java/com/robsartin/segue/arch/PackageListsTest.java`
- Modify: `src/test/java/com/robsartin/segue/arch/ArchitectureTest.java` (javadoc on both constants: "asserted equal to the tree by `PackageListsTest`"); `src/test/java/com/robsartin/segue/arch/DeveloperGuideEnumerationsTest.java` only if its guide check for the dev-tool sentence moves to the derived set (judge and say)

**Interfaces:**
- Consumes: `DEV_TOOL_PACKAGES`, `ADAPTER_PACKAGES` (package-private), `RepositoryTree.root()` / `read()`, ArchUnit `ClassFileImporter` as `ArchitectureTest` uses it.
- Produces: package-private `devToolsFromGradle()`, `devToolsFromCliClasses()`, `adaptersFromPortImplementors()` — so the controls can be planted against each derivation by name.

- [ ] **Loop A — dev tools from Gradle.** Parse `build.gradle.kts` for every `register<JavaExec>(…)` block's `mainClass.set("com.robsartin.segue.<pkg>.<Class>")` and collect `<pkg>`. Assert equals `DEV_TOOL_PACKAGES`. Green on arrival is expected; **control:** plant a `register<JavaExec>("promote") { mainClass.set("com.robsartin.segue.promote.PromoteCli") … }` → red naming `promote`; revert; quote.
- [ ] **Loop B — dev tools from `*Cli` classes.** Through ArchUnit's imported classes: every class whose simple name ends `Cli` and declares `public static void main(String[])`, by package. Assert equals the constant **and** equals Loop A's set. Control: plant `src/main/java/com/robsartin/segue/promote/PromoteCli.java` with a `main` → red naming `promote`; **also** confirm `ArchitectureTest` stays green with the plant in place (that silence is the residual this issue exists for — quote the 32/32 or whatever the count is); revert; quote.
- [ ] **Loop C — adapters from port implementors.** Every imported class assignable to any interface in `..port..`, grouped by package, minus `port`. Assert equals `ADAPTER_PACKAGES`. Control: plant `src/main/java/com/robsartin/segue/rocks/RocksGraphStore.java implements GraphStore` (stub methods) → red naming `rocks`; revert; quote.
- [ ] **Loop D — the other direction.** Add a bogus entry to each constant (`"promote"`, `"rocks"`) → each test reds naming the entry the tree lacks; revert; quote.
- [ ] **Loop E — the guide, against the derived set.** The sentence "…are the seven dev-side tools" is compared as a set to Loop A's set (order is chronological, not alphabetical; the count word is checked as `DeveloperGuideEnumerationsTest` checks others). If `DeveloperGuideEnumerationsTest` already holds that sentence to the constant, retarget it to the derived set rather than duplicating; say which.
- [ ] **Step 6 — javadoc on both constants** in `ArchitectureTest`: no longer "adding a source adds one entry here and nothing else" — say the tree is the source and this test the check.
- [ ] **Step 7 — gate and commit.**

### Task 2: ADR 32's dated amendment

- [ ] Append a dated amendment (2026-09-02, issue #165), addition only: the constants were the only source of truth and a package they did not name was fenced by nothing (cite #165's measurement: a planted seventh tool left every fence green); both sets are now derived from the tree and the constants asserted equal, by `PackageListsTest` (cite by name; restate no assertion); what is deliberately not derived (the `rate → recommend` exception is a decision); the rejected alternatives. `git diff -- docs/adr/ | grep '^-' | grep -v '^---'` empty; `AdrIndexTest` green. Gate and commit.

---

## Self-Review

**Spec coverage.** Two dev-tool signals → Loops A/B and their equality. Adapters via ArchUnit → Loop C. Both directions → Loop D. Guide against the derivation → Loop E. Constants kept and re-documented → Step 6. ADR 32 → Task 2. Controls → each loop names its plant and what the red must name.

**Placeholders.** None.

**Type consistency.** The three package-private derivation methods are named once and used by the controls; the constants keep their names and visibility.
