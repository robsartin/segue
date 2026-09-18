# Two stale spots in the developer guide — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to
> implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** close issue #341. `docs/developer-guide.md` has two stale sentences noticed during the
reviews of #315 and #338. One (the supervised-run chapter's last bullet) claims the chapter has
"never been run" — false, since issue #249 ran it and issue #259 corrected the chapter from what
that run found. The other (the `find_paths` paragraph in the rating-deck chapter) restates two
constants — `Recommendations.MAX_HOPS` and `GraphTools.DEFAULT_MAX_HOPS` — as digits, so it goes
stale the moment either constant moves. This plan replaces both sentences with wording that cites
the deciding issues (spot 1) or names the constants instead of copying their values (spot 2).
**No behaviour changes, and no production code changes.**

**Spec:** `docs/superpowers/specs/2026-09-18-guide-two-stale-spots-design.md` — read it first. It
records the sweep that found exactly these two spots (and the one near-miss — "the two hops of one
route can …" in the recommender chapter — that is not a hit because it describes a route's shape,
not a constant's value).

**Architecture:** nothing. **This plan changes no production behaviour and touches no method body,
signature or constant.** One file, two sentences:

- `docs/developer-guide.md` — the last bullet under `### What to file from what you saw` (in `## A
  supervised first run`, around line 3148), and the "route *set* differs too" sentence in `### Three
  card shapes, because they answer different questions` (around line 2579).

**Tech Stack:** Java 25, Gradle (plain `./gradlew`; only JDK 25 is installed, so never
`/usr/libexec/java_home -v 21` — it silently returns 25), JUnit 5, AssertJ, ArchUnit.

**Task count and why two:** **Two tasks, one per spot.** The two sentences sit in different
chapters (`## A supervised first run` vs the rating-deck chapter's card-shapes section), are
verified by nothing that reads their substance in common — `DocumentationLinksTest` only resolves
links, and neither sentence carries one; `DeveloperGuideSupervisedRunExamplesTest` checks the
*order* of runnable examples, and spot 1's bullet carries no example at all (confirmed by reading
the file: the bullet is prose, no fenced command) — and a reviewer could accept the issue-citation
rewrite at spot 1 while rejecting the constant-naming rewrite at spot 2, or the reverse, without
either decision touching the other sentence's correctness. There is no shared guard, shared
example, or shared paragraph between them.

## Global Constraints

- **Docs only, no code.** Nothing in this plan edits a method body, a signature, or a constant. If
  a step seems to require one, stop and report.
- **Never restate a count or figure in prose; cite its home.** Spot 2's rewrite names
  `Recommendations.MAX_HOPS` and `GraphTools.DEFAULT_MAX_HOPS` by identifier, never by value.
- **No digits in the rewritten sentence at spot 2.** Not the constants' values, not a substitute
  number.
- **Markdown links whole on one line.** Neither rewritten sentence adds a markdown link, so this is
  a non-issue for the edits themselves, but if any step's report quotes guide text that happens to
  contain one, quote it inside a four-backtick fence rather than as a live line, so
  `DocumentationLinksTest` (which scans all of `docs/`) does not try to resolve it from this plan's
  location. Simplest: don't quote lines with links, and this plan doesn't need to.
- **Never cite a `.superpowers/` path anywhere in the plan or in committed text.**
- **Stage by explicit path — `git add docs/developer-guide.md`, never `git add -A`.** Read `git
  status` before every commit.
- **Commit trailer, after a blank line, exactly:** `Co-Authored-By: Claude Fable 5.1
  <noreply@anthropic.com>`.
- **Do not invent identifiers.** The only issue numbers usable anywhere in this plan or its output
  are #341, #249, #259, #311, #315, #338. Spot 1's bullet cites #249 and #259 and nothing else — no
  date, no count of what the run touched, no restatement of what #259 changed.
- **The full gate is not run by the implementer.** `SEGUE_REQUIRE_BROWSER=true
  SEGUE_REQUIRE_GRAPHVIZ=true ./gradlew check --rerun-tasks` is run by the controller after both
  tasks land, not inside either task.
- **Each task runs `./gradlew spotlessApply` and the two named test classes, blocking, and reads
  the exit code before claiming success.** No task backgrounds a Gradle command.
- **Honest exception to TDD, said out loud:** these are prose edits with no unit-testable
  behaviour. Verification is the derived grep (seen to hit before the edit, miss after — and for
  spot 2, hit again on a planted digit, proving the grep isn't vacuous), `spotlessApply` plus `git
  status --short`, and the two named test classes actually running.

---

## Task 1 — Spot 1: the supervised-run bullet no longer claims the chapter was never run

**Files:** `docs/developer-guide.md`

### Step 1 — run the derived grep and see it hit (RED, i.e. the stale text is present)

- [ ] Run: `grep -nE 'never been run|first run is what' docs/developer-guide.md`
- [ ] Confirm exactly one hit, matching:

  ```
  3149:  parsers, and it has never been run. The first run is what makes it true.
  ```

  (The line number may drift slightly if the file has changed since this plan was written — match
  on content, not the number.) If there is no hit, stop and report; the spot may already be fixed
  or the plan is stale.

### Step 2 — the edit

- [ ] In `docs/developer-guide.md`, inside `## A supervised first run` → `### What to file from
      what you saw`, find the bullet:

  ```
  - **Anything this chapter got wrong.** It was written against the code and checked against the
    parsers, and it has never been run. The first run is what makes it true.
  ```

- [ ] Replace it with:

  ```
  - **Anything this chapter got wrong.** It was written against the code and checked against the
    parsers, then run once by the owner under issue #249; issue #259 carried what that run found
    back into the chapter. A later run that disagrees with it now is a finding to file, not a
    reason to distrust the chapter.
  ```

  This cites #249 and #259 and nothing else — no date, no count of what the run touched, no
  restatement of what #259 changed beyond "carried what that run found back into the chapter."

### Step 3 — run the grep again and see it miss

- [ ] Run: `grep -nE 'never been run|first run is what' docs/developer-guide.md`
- [ ] Confirm exit code 1 (no match). If it still matches, the edit did not land where expected;
      stop and report.

### Step 4 — format and diff-scope check

- [ ] Run, blocking: `./gradlew spotlessApply`
- [ ] Run: `git status --short`
- [ ] Confirm the only line is `M docs/developer-guide.md`. If anything else appears (including a
      file this task did not touch), stop and report before proceeding.

### Step 5 — run the guard tests, blocking, and read the exit code

- [ ] Run, blocking:
      `./gradlew test --tests '*DocumentationLinksTest*' --tests '*DeveloperGuideSupervisedRunExamplesTest*'`
- [ ] Read the exit code. 0 means both classes passed (or had nothing new to fail — this bullet
      carries no runnable example, so `DeveloperGuideSupervisedRunExamplesTest` is not expected to
      exercise the new text directly; it is run anyway as the named guard for this chapter, per the
      spec's "What does not change" section). Non-zero: stop, quote the failure, report — do not
      proceed to commit.

### Step 6 — commit

- [ ] `git status` (confirm again, immediately before staging).
- [ ] `git add docs/developer-guide.md` — explicit path, never `git add -A`. Watch stderr; do not
      redirect it away.
- [ ] Commit with:

  ```
  Runbook: the supervised-run chapter no longer claims it was never run (#341)

  The chapter's last bullet said the chapter had never been run. Issue #249
  was the owner's supervised first run, and issue #259 carried what it found
  back into the chapter — the bullet was true the day it was written and
  stopped being true then. It now cites both issues instead of claiming the
  chapter is unrun.

  Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
  ```

---

## Task 2 — Spot 2: the `find_paths` paragraph names the constants instead of their digits

**Files:** `docs/developer-guide.md`

### Step 1 — run the derived grep and see it hit (RED, i.e. the stale text is present)

- [ ] Run:
      `grep -nE 'MAX_HOPS[^.]{0,40}\b[0-9]\b|defaults? to [0-9]' docs/developer-guide.md`
- [ ] Confirm exactly one hit, matching:

  ```
  2579:and `Deck.routeLines`. The route *set* differs too: `Recommendations.MAX_HOPS` is 2 where `find_paths`
  ```

  (Line number may drift; match on content.) If there is no hit, stop and report.

### Step 2 — the edit

- [ ] In `docs/developer-guide.md`, inside `### Three card shapes, because they answer different
      questions`, find the sentence (it spans two lines in the file):

  ```
  ... The route *set* differs too: `Recommendations.MAX_HOPS` is 2 where `find_paths`
  defaults to 4, and `bestFor` keeps only the top-ranked route per reaching entity. ...
  ```

- [ ] Replace just that sentence with:

  ```
  The route *set* differs too: the deck walks to `Recommendations.MAX_HOPS`, while `find_paths`
  falls back to `GraphTools.DEFAULT_MAX_HOPS` when a caller omits `maxHops`, and the two are set
  independently; `bestFor` keeps only the top-ranked route per reaching entity.
  ```

  leaving the sentences before and after it (the shared-steps sentence, the note-field sentence)
  untouched. The new sentence names both `Recommendations.MAX_HOPS` and `GraphTools.DEFAULT_MAX_HOPS`
  and contains no digit. `GraphTools.DEFAULT_MAX_HOPS` stays a private constant in code — the guide
  may name it as it names other members; this is prose, not a visibility change.

### Step 3 — run the grep again and see it miss

- [ ] Run:
      `grep -nE 'MAX_HOPS[^.]{0,40}\b[0-9]\b|defaults? to [0-9]' docs/developer-guide.md`
- [ ] Confirm exit code 1 (no match). If it still matches, stop and report — check whether the
      match is this sentence or an unrelated line elsewhere in the file (the spec's sweep found
      only this one, so a second hit here means something changed since the spec was written).

### Step 4 — planted positive control (proves the grep isn't vacuous)

- [ ] Temporarily put a digit back after `MAX_HOPS` in the new sentence with:

  ```bash
  sed -i '' 's/the deck walks to `Recommendations.MAX_HOPS`,/the deck walks to `Recommendations.MAX_HOPS` (2),/' docs/developer-guide.md
  ```

- [ ] Run: `grep -nE 'MAX_HOPS[^.]{0,40}\b[0-9]\b|defaults? to [0-9]' docs/developer-guide.md`
- [ ] Confirm it hits (exit code 0) on the planted line. Quote the matched line in the task report.
      If it does not hit, the grep pattern is not doing its job — stop and report before removing
      the plant.
- [ ] Remove the plant with:

  ```bash
  sed -i '' 's/the deck walks to `Recommendations.MAX_HOPS` (2),/the deck walks to `Recommendations.MAX_HOPS`,/' docs/developer-guide.md
  ```

- [ ] Run the grep a third time and confirm it misses again (exit code 1).

### Step 5 — format and diff-scope check

- [ ] Run, blocking: `./gradlew spotlessApply`
- [ ] Run: `git status --short`
- [ ] Confirm the only line is `M docs/developer-guide.md`. If anything else appears, stop and
      report before proceeding.

### Step 6 — run the guard tests, blocking, and read the exit code

- [ ] Run, blocking:
      `./gradlew test --tests '*DocumentationLinksTest*' --tests '*DeveloperGuideSupervisedRunExamplesTest*'`
- [ ] Read the exit code. 0: proceed. Non-zero: stop, quote the failure, report — do not proceed to
      commit. (Neither class asserts on this sentence's content specifically — `DocumentationLinksTest`
      only resolves links and this sentence adds none; `DeveloperGuideSupervisedRunExamplesTest`
      checks a different chapter's example order — but both are run as the named guards for
      anything touching this file, and a red here means something else broke.)

### Step 7 — commit

- [ ] `git status` (confirm again, immediately before staging).
- [ ] `git add docs/developer-guide.md` — explicit path, never `git add -A`. Watch stderr.
- [ ] Commit with:

  ```
  Runbook: the find_paths paragraph names the hop constants instead of their values (#341)

  The "route set differs too" sentence restated Recommendations.MAX_HOPS and
  find_paths's default hop bound as digits, so it would go stale the moment
  either constant moved. It now names Recommendations.MAX_HOPS and
  GraphTools.DEFAULT_MAX_HOPS directly and carries no digit.

  Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
  ```

---

## Done when

- [ ] Two commits (or one, if the controller merges the tasks) on the issue branch, each touching
      only `docs/developer-guide.md`.
- [ ] Both derived greps miss on the final state of the file.
- [ ] Spot 2's planted-digit control was seen to hit, quoted in the task report, then removed and
      reconfirmed missing.
- [ ] `git status --short` shows only `docs/developer-guide.md` changed, at each commit.
- [ ] Both named test classes ran (not skipped) with exit code 0 after each task's edit.
- [ ] Neither commit message nor the plan cites an issue number outside {#341, #249, #259, #311,
      #315, #338}, a date, a count, or a `.superpowers/` path.
- [ ] The controller runs `SEGUE_REQUIRE_BROWSER=true SEGUE_REQUIRE_GRAPHVIZ=true ./gradlew check
      --rerun-tasks` after both tasks land — not run by either task above.
