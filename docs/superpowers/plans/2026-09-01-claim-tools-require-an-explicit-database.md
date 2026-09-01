# Claim tools require an explicit database — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `retractEntity` and `ownClaim` refuse to run unless `--db` explicitly names a database, and the six duplicated copies of the default-path resolution become one.

**Architecture:** A new `support.DefaultDatabase` holds the single resolution — `SEGUE_DB` if set, otherwise `${user.home}/.segue/segue.db` — and the four tools that keep a default use it. The two claim tools have no default at all, and an ArchUnit rule forbids them from depending on `DefaultDatabase`, so it cannot be reintroduced.

**Tech Stack:** Java (toolchain 25, `release 21`), Gradle 9.7.1, JUnit, AssertJ, ArchUnit, SQLite.

**Spec:** `docs/superpowers/specs/2026-09-01-claim-tools-require-an-explicit-database.md`

## Global Constraints

- **Pure TDD, one small behaviour per red→green loop.** Write the failing test, **run it and observe a real failure for the right reason**, then the minimum code. Reports must quote what each failure actually said. Do not batch several behaviours into one red.
- Test names read `should<Expected>When<Condition>` and carry a `@DisplayName`.
- **ADRs are immutable** (ADR 1): dated amendments or supersession, never edits.
- **Never `git add -A`** — the worktree is shared. Stage by explicit path.
- Full gate, run **blocking, never backgrounded**: `SEGUE_REQUIRE_BROWSER=true ./gradlew check --rerun-tasks`
- **Only JDK 25 is installed on this machine** and Gradle 9.7.1 launches on it. Do **not** set `JAVA_HOME=$(/usr/libexec/java_home -v 21)` — there is no JDK 21, and that command returns the **JDK 25 path with exit 0**, so it silently does nothing while looking like version pinning. Plain `./gradlew` is correct.
- **Never run a writing dev task** — not `ownClaim`, not `retractEntity`, not with `--dry-run`, not by abbreviation. `./gradlew own` resolves to `:ownClaim` by camel-case hump matching and *runs*; that is the incident this work exists to prevent. To check a task name: `./gradlew tasks --all | grep -i <name>`.
- `~/.segue/segue.db` is the owner's real database. Do not read, write, or create it. Tests use temp paths.
- ArchUnit fences name packages as **literal strings**, so a new package inherits nothing. Every new rule needs a positive control: plant the violation, watch it go red **naming itself**, revert, quote the message. Establish which *forms* it catches — `callConstructorWhere` silently misses a method reference; `dependOnClassesThat` and `accessTargetWhere` catch both.

---

### Task 1: One resolution for the four tools that keep a default

**Files:**
- Create: `src/main/java/com/robsartin/segue/support/DefaultDatabase.java`
- Create: `src/test/java/com/robsartin/segue/support/DefaultDatabaseTest.java`
- Modify: `src/main/java/com/robsartin/segue/export/ExportCli.java`, `rate/RateCli.java`, `ratings/RatingsCli.java`, `recommend/RecommendCli.java`

**Interfaces:**
- Produces: `DefaultDatabase.resolve(String flagValueOrNull, String segueDbEnvOrNull, String userHome)` returning `Path`. Pure — no `System.getenv`, no `System.getProperty` inside; callers pass those in, exactly as the six `parse(...)` methods already do.

`support` is the right home and the precedent is already recorded in `ArchitectureTest` (~line 1043): `QidList` was moved there rather than let a shared reader create a dependency between two dev tools. Four dev-tool packages already depend on `support`.

- [ ] **Step 1: Pin the behaviour that must not change.** Write `DefaultDatabaseTest` covering, each as its own test: the `--db` value wins when given; `SEGUE_DB` wins when no flag; `${user.home}/.segue/segue.db` when neither; and an empty or blank `SEGUE_DB` is treated as unset, **if and only if** that is what the current code does — read all six `parse(...)` methods first and pin what they actually do, not what they ought to do.

- [ ] **Step 2: Run it and watch it fail.** Quote the message (`cannot find symbol: class DefaultDatabase`).

- [ ] **Step 3: Write `DefaultDatabase`, run green.**

- [ ] **Step 4: Migrate the four tools one at a time**, running the gate between each, so a behaviour change in any one of them is attributable. If any tool's existing resolution differs from the others in any respect, **stop and report it** rather than smoothing it away — a difference here is a finding, not noise.

- [ ] **Step 5: Run the gate and commit.**

---

### Task 2: The two claim tools require `--db`

**Files:**
- Modify: `src/main/java/com/robsartin/segue/retract/RetractCli.java`, `src/main/java/com/robsartin/segue/own/OwnCli.java`
- Test: `src/test/java/com/robsartin/segue/retract/RetractCliTest.java`, `src/test/java/com/robsartin/segue/own/OwnCliTest.java`

**Interfaces:**
- Consumes: nothing from Task 1. These two tools must **not** reference `DefaultDatabase` — that absence is what Task 3 fences.

Four behaviours, **four separate red→green loops**. Do not write all four tests and run them once.

- [ ] **Loop A — refusal.** `retractEntity` with no `--db` refuses. Assert on the **message**: it must name `--db` and the path it would otherwise have used, so the owner's next command is a copy-paste. Watch it fail; quote it.

- [ ] **Loop B — the same for `ownClaim`.** Separate red.

- [ ] **Loop C — `--dry-run` does not exempt.** Both tools refuse with `--dry-run` and no `--db`. The refusal must fire **before any database is opened**, so no file is touched and no "database not found" error can mask it.

- [ ] **Loop D — `SEGUE_DB` does not satisfy the requirement.** Both tools refuse when only `SEGUE_DB` is set. This is the case that would silently re-open the hole, because an agent's shell is initialised from the owner's profile and inherits it.

- [ ] **Step 5: Confirm the happy path still works** — `--db` naming an existing database proceeds, and an absent database is still refused rather than created (both tools already do this; make sure the new check did not displace it).

- [ ] **Step 6: Run the gate and commit.**

---

### Task 3: Fence the absence, then document the decision

**Files:**
- Modify: `src/test/java/com/robsartin/segue/arch/ArchitectureTest.java`
- Create: `docs/adr/0060-*.md` (confirm the number with `ls docs/adr/ | tail -3`)
- Modify: `docs/adr/README.md`, `build.gradle.kts`, `docs/developer-guide.md`

- [ ] **Step 1: Write the failing ArchUnit rule.** No class in `..retract..` or `..own..` may depend on `DefaultDatabase`. Name it for what it protects, not for what it forbids.

- [ ] **Step 2: Run it and watch it fail.** Quote the message.

- [ ] **Step 3: Implement, run green, then prove it with two positive controls** — plant a dependency in `retract`, watch it red **naming itself**, revert; then the same from `own`. Both packages, because a rule naming one and not the other is exactly the silent half-fence this codebase keeps producing. Quote both messages and say which violation *forms* the rule catches.

- [ ] **Step 4: Correct the two Gradle task descriptions.** Both `retractEntity` and `ownClaim` carry `--args` examples that would now fail; both need `--db`. An example that does not work is worse than none, because it will be copied.

- [ ] **Step 5: Correct the javadocs.** `RetractCli` and `OwnCli` currently promise a default they no longer have — including the "stated here as well as in…" sentences, which were the duplication admitting itself.

- [ ] **Step 6: Update `docs/developer-guide.md`** with the rule, the reason, and the abbreviation warning: `./gradlew own` resolves to `:ownClaim` and will not report an unknown task. Say it plainly — the next person to expect `Task 'own' not found` deserves to be told it will not happen.

- [ ] **Step 7: Write ADR 60.** The decision, the **five rejected alternatives from the spec with the reason each lost** — including that terminal-detection was rejected on a *measurement*, not a hunch (only `rate` wires `standardInput`, so `System.console()` is null for the owner too) — and what it does not settle: the abbreviation remains, and nothing here protects the four reading tools, which do not need it. Cite code as the authority; never mirror a rule into a table.

- [ ] **Step 8: Verify the ADR index sequence** (#170 — it is append-at-tail and has silently lost entries three times). Count entries before and after and state both numbers.

- [ ] **Step 9: Run the gate and commit.**

---

## Self-Review

**Spec coverage.** Decision (claim tools require `--db`) → Task 2. One resolution replacing six → Task 1. Fence on the absence → Task 3 Steps 1–3. `--dry-run` not exempt → Task 2 Loop C. `SEGUE_DB` insufficient → Task 2 Loop D. Refusal message names flag and path → Task 2 Loop A. Four unchanged tools pinned → Task 1 Step 1. Abbreviation documented not fixed → Task 3 Step 6. ADR 60 with five alternatives → Task 3 Step 7. Stray `Q001` row → deliberately out of scope, per the owner's decision.

**Placeholders.** None: every step names its files, and the two design choices an implementer might otherwise guess at (`--dry-run` is not exempt; `SEGUE_DB` does not satisfy) are stated with their reasons.

**Type consistency.** `DefaultDatabase.resolve(String, String, String)` returning `Path` is named once in Task 1 and referenced by the four migrations there; Tasks 2 and 3 deliberately do not consume it.
