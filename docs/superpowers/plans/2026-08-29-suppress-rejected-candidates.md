# Suppress Rejected Candidates Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A candidate rated 1 or 2 stops being offered. 70 explicit rejections currently do nothing and return on every run.

**Architecture:** A pure rule in `domain` naming the suppressed set, and a second exclusion set in `CandidateSweep` — deliberately **separate from the known-list**, because "I have this" and "I rejected this" are different facts and the sweep reports on the first. Both tools that sweep pass it.

**Tech Stack:** Java 21 (release 21, toolchain 25), Gradle Kotlin DSL, JUnit 5 + AssertJ, ArchUnit.

## Global Constraints

- **Issue #106.** Branch `106-suppress-rejected`. Read the issue including its 2026-08-29 comment — that comment is the argument.
- **Never open `~/.segue/segue.db`.** Copy it for any measurement; report the real file's mtime unchanged. It holds **1,150** irreplaceable personal ratings.
- **This repo is PUBLIC and the owner's ratings are personal data** (ADR 33, issue #37). Aggregate figures are fine. **Never name an entity as something the owner rated, likes or rejected** — a recent branch did exactly that and its history had to be rewritten before pushing.
- Stage commits **by explicit path**. NEVER `git add -A`; an untracked `mad.vcf` must never be staged.
- `./gradlew check` green before every commit; run long commands **blocking**.
- `domain` has **no third-party dependencies**.
- No rating value in any log line (ADR 33) — counts only.
- TDD: failing test first, run it, watch it fail for the right reason, report what it said.

## Why, measured

ADR 48 deferred suppression on a stated arithmetic condition: **two ratings below neutral against 87 above**. That condition expired. One 177-card pass at a lower degree floor (#118, #119) produced **72 below neutral — 41%**, against 0.8% across all 973 prior ratings.

**And 70 of those 72 are inert.** `Recommendations.regardFor` weights known-list qids only, and `KnownList.promoted` admits 4 and 5 only. So a 2 on a candidate weights nothing and suppresses nothing — the same candidate returns on the next run.

**Suppression, not negative weighting.** Negative weighting would need weights below zero: `regardFor`'s lowest is 1/3, still positive, so admitting a disliked entity to the known-list would make it *boost* whatever it connects to. That changes ADR 45's arithmetic and yields scores with no defined meaning. Suppression maps onto machinery that already exists.

---

### Task 1: The suppressed set, and the sweep honours it

**Files:**
- Modify: `src/main/java/com/robsartin/segue/domain/KnownList.java`
- Modify: `src/main/java/com/robsartin/segue/recommend/CandidateSweep.java`
- Test: `src/test/java/com/robsartin/segue/domain/KnownListTest.java`, `src/test/java/com/robsartin/segue/recommend/CandidateSweepTest.java`

**Interfaces:**
- Produces: `KnownList.suppressed(Map<String, Integer> ratings) → Set<String>` and `KnownList.SUPPRESSION_RATING = 2`; `CandidateSweep.over(List<String> known, Set<String> suppressed, Scorer, int minDegree, ToDoubleFunction<String> regard)`.

- [ ] **Step 1: Write the failing tests**

For `KnownList.suppressed`: a rating at or below 2 is in the set, 3 and above is not, and the result is a `Set` so order cannot matter. Cover the boundary in both directions — 2 in, 3 out — because an off-by-one here silently suppresses everything neutral.

For `CandidateSweep`: an entity that would otherwise be a candidate is **absent** when suppressed, and the sweep's own counts still describe the known-list rather than the suppressed set.

- [ ] **Step 2: Run and watch them fail.** Record the messages.

- [ ] **Step 3: Implement**

`KnownList.suppressed` alongside `promoted`, with javadoc carrying the argument: the measured 72-of-177, why suppression rather than negative weighting, and that 2 is the boundary because `regardFor` treats 3 as exactly neutral.

`CandidateSweep.over` gains a **separate** `Set<String> suppressed` parameter. **Do not union it into `knownSet`** — the sweep reports `knownFound` and `knownMissing`, and folding rejections into that would make both figures describe something other than the known-list. Exclude at the same point the known check happens (`CandidateSweep.java:131`).

- [ ] **Step 4: Run and commit**

```bash
git add src/main/java/com/robsartin/segue/domain/KnownList.java \
        src/main/java/com/robsartin/segue/recommend/CandidateSweep.java \
        src/test/java/com/robsartin/segue/domain/KnownListTest.java \
        src/test/java/com/robsartin/segue/recommend/CandidateSweepTest.java
git commit -m "Stop offering what you have already rejected (#106)"
```

---

### Task 2: Both tools pass it, and revision can still reach a suppressed entity

**Files:**
- Modify: `src/main/java/com/robsartin/segue/recommend/RecommendRun.java`, `src/main/java/com/robsartin/segue/rate/RateRun.java`, `src/main/java/com/robsartin/segue/rate/Deck.java`
- Test: the existing tests of each

**The design point the issue did not anticipate, and it matters.**

Suppression makes an entity unreachable for revision. `Deck.dealRevision` walks the known-list, and a suppressed candidate is not on it — so `--revise 2` cannot reach the very entities this task creates. That is exactly the trap #109 was filed to fix for known entities, reintroduced one layer out.

**Make revision reach them.** `dealRevision` should walk the known-list **plus** the suppressed set, so `--revise 2` deals what you rejected. A rating given in twenty minutes at three seconds a card must be revisable.

- [ ] **Step 1: Write the failing tests** — for each tool, that a suppressed entity is not dealt as a candidate; and for the deck, that `--revise 2` *does* deal it.

- [ ] **Step 2: Run and watch them fail.** Record the messages.

- [ ] **Step 3: Implement.** Both `RecommendRun` and `RateRun` already hold the ratings map, so neither needs a new read. Thread `KnownList.suppressed(ratings)` to the sweep, and widen `dealRevision`'s walk.

- [ ] **Step 4: Run the gate and commit**

---

### Task 3: Measure it, then record it

**Files:** an ADR, `docs/adr/README.md`, `docs/developer-guide.md`, `CLAUDE.md`.

- [ ] **Step 1: Measure on a COPY**

`mkdir -p /tmp/suppress && cp ~/.segue/segue.db /tmp/suppress/copy.db`, then run `recommend` against the copy at the merge-base and at your branch head, same `--known`, `--top 25`.

Report: how many entities the suppression excluded (expect ~70 — **verify**); how many of the previous top 25 left because they were suppressed; how many genuinely new entities entered; the before/after top 10.

**If the ranking barely moves, say so plainly.** Four theories have failed against this data — the taste layer alone, the revision pass, the expansion, and (partly) promotion. A fifth null result is a perfectly good outcome and better known than hidden.

- [ ] **Step 2: Write the ADR**

Record: the decision; that ADR 48's stated reopening condition was met and how (72 of 177 versus 8 of 973); **why suppression rather than negative weighting**, with the arithmetic — `regardFor`'s floor is 1/3, still positive, so a disliked entity admitted to the known-list would boost what it touches; that 2 is the boundary because 3 is exactly neutral; that revision can still reach a suppressed entity and why that mattered; and Task 1's measurement.

**Record the interaction with #117 and #118**: suppression keyed on a candidate that only appeared because of a degree floor inherits that floor's ingest-completeness defect. Suppressing an entity that was only ever offered because segue had under-fetched it is a judgement made on incomplete information.

**One warning from this repo's history.** Issue #101 produced six false generalisations in a row — sentences about a *group* written from memory rather than from the files, each fix introducing a narrower one still false. Any sentence claiming something about a set must be verified against every member by opening the file, or rewritten so it does not span a set.

- [ ] **Step 3: Run the gate and commit**

---

## Self-Review

**Spec coverage.** #106's remaining acceptance maps to: suppression decided and recorded → Task 3; the inert ratings used → Tasks 1-2; demonstrated on invented ratings → Tasks 1-2 tests; re-run against the real graph and record whether the top 25 moves → Task 3 Step 1; ADR → Task 3.

**Verified against source rather than inferred.** `CandidateSweep.over` builds `knownSet` at `:91` and excludes at `:131`; `KnownList.promoted` and `PROMOTION_RATING = 4` exist; `RecommendRun` and `RateRun` both already hold the ratings map after #106's first half; `Deck.dealRevision` walks `knownQids` only.

**A judgement worth a reviewer's eye.** Suppression is keyed on the rating alone, so re-rating to 3 or above unsuppresses. That is intended and follows from there being no delete — but it means the only way back is through `--revise`, which is why Task 2 exists.
