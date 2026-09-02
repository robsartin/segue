# `corroborated(0)` across both engines — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Jena returns an owner-only edge at `corroborated(0)` exactly as Tinker does, and the differential guard compares the engines at every N the fixture makes meaningful, with a control seen red.

**Architecture:** `JenaGraphStore.corroborated` counts non-owner sources per edge without dropping edges whose only sources are the owner; `TinkerGraphStoreContractTest.enginesAgreeOnEdgeSets` loops N over 0..3 with shape assertions; ADR 18 gains a dated amendment; the port javadoc states what 0 means.

**Tech Stack:** Java (toolchain 25, `release 21`), Gradle 9.7.1, JUnit, AssertJ, Apache Jena SPARQL, TinkerPop.

**Spec:** `docs/superpowers/specs/2026-09-02-corroborated-zero-design.md`

## Global Constraints

- **Pure TDD, one behaviour per red→green loop**; reds quoted. The widened guard is red on today's Jena at N = 0 — a real red; use it.
- Test names `should<Expected>When<Condition>` with `@DisplayName`. **ADRs immutable** (ADR 1): a dated amendment to ADR 18 only. **Never `git add -A`.**
- Gate, **blocking, never backgrounded**: `SEGUE_REQUIRE_BROWSER=true ./gradlew check --rerun-tasks`. Baseline on `main` is 1043 tests — measure it.
- **Only JDK 25 is installed and Gradle 9.7.1 launches on it.** Do NOT set `JAVA_HOME=$(/usr/libexec/java_home -v 21)` — it returns the JDK 25 path with exit 0. Plain `./gradlew`.
- **Never run a writing dev task** (`ownClaim`, `retractEntity`, any abbreviation). `~/.segue/segue.db` is never read, written, or created.
- **Tinker is not changed.** The domain object is not changed.

---

### Task 1: Widen the guard, then make Jena agree

**Files:**
- Modify: `src/test/java/com/robsartin/segue/tinker/TinkerGraphStoreContractTest.java` (`enginesAgreeOnEdgeSets`)
- Modify: `src/main/java/com/robsartin/segue/jena/JenaGraphStore.java` (`corroborated(int)`, ~:470–485)
- Modify: `src/main/java/com/robsartin/segue/port/GraphStore.java` (javadoc of `corroborated`, ~:76–81)
- Modify: `src/test/java/com/robsartin/segue/port/GraphStoreContract.java` only if a per-engine assertion at N = 0 belongs beside the existing `corroboration()` test — judge and say.

- [ ] **Loop A — the widened guard, red on today's Jena.** Replace the single `corroborated(2)` comparison with a loop over N = 0..3: for each N, `keys(tinker.corroborated(N))` equals `keys(jena.corroborated(N))`, with the failure message naming N. Run it: **red at N = 0**, the message naming the owner-only edge (`Fixture`'s `owner(CAVE, "AUTHORED", ASS_SAW_ANGEL)`) present in Tinker's set and absent from Jena's. Quote it.
- [ ] **Loop B — the shape assertions**, so the loop cannot pass on two identical mistakes: at N = 0 the owner-only edge is in *both* sets; at N = 1 it is in *neither*; at N = 3 both sets are empty. Red first where today's engines disagree; quote.
- [ ] **Loop C — Jena.** Rewrite the query so the group survives with a count of the non-owner sources — e.g. `COUNT(DISTINCT IF(?src != ?owner, ?src, ?absent))` with `?absent` unbound, or a subquery over all edges left-joined with the non-owner count — then `HAVING (?n >= ?minSources)`. Green at every N. Every other query and test unchanged.
- [ ] **Step 4 — positive control.** Reintroduce the row-dropping `FILTER (?src != ?owner)` and run the guard: red **at N = 0 and only there**, naming the owner-only edge; quote; revert.
- [ ] **Step 5 — the port javadoc** says what `corroborated(0)` returns and why (the domain rule; ADR 59), citing the guard.
- [ ] **Step 6 — gate and commit.**

### Task 2: ADR 18's dated amendment

- [ ] Append a dated amendment (2026-09-02, issue #176), addition only: the Q4 claim was false at N = 0 from ADR 59 onward until this change; what `corroborated(0)` means for an owner-only edge and why; that the guard now spans 0..3 with the shape assertions; that Jena counts non-owner sources without dropping the edge; the three rejected alternatives with reasons. Cite the guard and the query by name; mirror no table. `git diff -- docs/adr/ | grep '^-' | grep -v '^---'` empty; `AdrIndexTest` green. Gate and commit.

---

## Self-Review

**Spec coverage.** Decision → Task 1 Loop C. Guard across the range with shape → Loops A/B. Control → Step 4. Port javadoc → Step 5. ADR 18 → Task 2. Rejected → spec + amendment. Tinker/domain unchanged → constraints.

**Placeholders.** None: the red is named, the range is fixed by the fixture, the control is specified.

**Type consistency.** No signatures change; `corroborated(int)` on both engines and the port is untouched in shape.
