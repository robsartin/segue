# The known list's `never expanded` row is a floor — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** close issue #315. Record, in the two ADRs that own the decisions and in the two documents
and two javadoc comments that tell the owner what to do, that the census's `known list` /
`never expanded` row is a floor of Wikidata-thin entities once every entity it names has been
visited, and that a `--known` expansion run is idempotent in the graph but not self-limiting. Decline
the "expansion attempted, found nothing" claim in the log, with the reason it lost.

**Architecture:** nothing. **This plan changes no production behaviour and touches no method body.**
Four documents and two javadoc comments:

- `docs/adr/0063-a-read-only-census-of-the-graph.md` — one appended amendment, dated 2026-09-12,
  issue #315.
- `docs/adr/0066-expand-every-promotion-from-a-dev-tool.md` — one appended amendment, same date and
  issue, carrying the declined alternative.
- `docs/developer-guide.md` — one corrected sentence and one new paragraph in "Expanding every
  promotion"; one new paragraph in "Looking at the shape of your graph".
- `src/test/java/com/robsartin/segue/expand/DeveloperGuideExpandPromotionsExamplesTest.java` — one
  assertion *message*, which holds a second copy of the sentence being corrected.
- `src/main/java/com/robsartin/segue/domain/Expanded.java` — one sentence.
- `src/main/java/com/robsartin/segue/census/KnownListCensus.java` — one sentence, on a `@param`.

**Tech Stack:** Java 25, Gradle (plain `./gradlew`), JUnit 5, AssertJ, ArchUnit.

**Spec:** `docs/superpowers/specs/2026-09-12-known-list-floor-design.md` — read its **Premise
corrections** section first. In particular, correction 1: the guide contains **no** sentence
promising the row will read zero, and the sentence that does need correcting is a weaker one.

## Global Constraints

- **No behaviour changes, and no test is written for behaviour.** That is the honest exception this
  project allows for pure prose, and it is stated here rather than left implied. Nothing in this plan
  edits a method body, a signature, a constant or a rule. If a step seems to require one, stop and
  report.
- **What verifies the prose**, named rather than assumed: `AdrIndexTest` (every ADR file has one
  index row agreeing on number, title and status), `AdrCitationsTest` (no un-allowlisted commit hash
  under `docs/adr`), `DocumentationLinksTest` (every relative link resolves to a file and a heading),
  `DeveloperGuideExpandPromotionsExamplesTest` and `DeveloperGuideCensusExamplesTest` (the chapters'
  `./gradlew` examples and, for the expansion chapter, their exact order), `JavadocCitationsTest`
  (citation shapes in `src/main` javadoc), and `javadoc -Werror` inside `./gradlew check`.
- **Nothing here can red on the prose itself, so every task carries a positive control instead.**
  Each task plants a defect in the file it is about, runs the guard that reads that file, **quotes
  the real failure text in its report**, and removes the plant before making the real edit. A task
  whose control did not fire has proved nothing and must stop and report. A compile error is never a
  red.
- **`docs` is a declared input of the `test` task** (`build.gradle.kts`, `inputs.dir("docs")`), so a
  document edit re-runs the suite. Every per-task loop therefore runs **without** `--rerun-tasks`,
  and each task's report says the task ran rather than printed `UP-TO-DATE`.
- **ADRs are immutable.** Only an appended, dated amendment. Nothing above an amendment is edited,
  reworded or deleted, and both ADRs keep `Accepted`.
- **No commit hash anywhere under `docs/adr`. No `.superpowers/` path in any committed file. No qid.
  No figure from the owner's graph** — the two readings are cited as "the run on #313" and "the
  reading on #311", never restated.
- **No over-claims.** The words "settles", "establishes", "proves" and "by construction" do not
  appear in any text this plan adds. What was measured is cited as measured; what follows from the
  code is cited to the code.
- **Mikado**: the gate is green before every commit, and each task ends green and is independently
  reviewable. **Stage by explicit path, git stderr visible — never `git add -A`, never `2>/dev/null`
  on `git add`.** Read `git status` before every commit. Commits end, after a blank line,
  `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`.
- Gate, **blocking, never backgrounded**, before each commit:
  `SEGUE_REQUIRE_BROWSER=true SEGUE_REQUIRE_GRAPHVIZ=true ./gradlew check --rerun-tasks`. Run
  `./gradlew spotlessApply` first in the two tasks that touch `.java` files.
- **Only JDK 25 is installed and Gradle launches on it.** Plain `./gradlew`; never
  `/usr/libexec/java_home -v 21` (it silently returns 25).
- **Never run a writing dev task** — `expandPromotions`, `graphCensus` against a real database,
  `own`, `ownClaim`, `retractEntity`, `rate`, `evaluate`, any seeding task. `~/.segue/segue.db` is
  never read, written, copied or created. Nothing in this plan runs any dev tool at all.
- **`{@code X}` spans stay whole on one source line.** `spotlessApply` re-wraps javadoc; after it
  runs, re-read the two edited javadoc comments and confirm no `{@code …}` was split across lines.
  A paragraph break is the fix if one is.
- Work only in `/Users/sartin/code/segue/wt-315`, on branch `315-ready`. You are the sole committer
  there.

---

## Task 1 — ADR 63: the row is a floor

**Files:** `docs/adr/0063-a-read-only-census-of-the-graph.md`

### Step 1 — read the file's tail and confirm the anchor

- [ ] `sed -n '/Amendment (2026-09-12, issue #313)/,$p' docs/adr/0063-a-read-only-census-of-the-graph.md`
- [ ] Confirm the file's last line is `this entry records which sentence here it overtakes.` The new
      amendment is appended after it, separated by one blank line. **Nothing above it is edited.**

### Step 2 — positive control: watch the ADR guard fire (RED)

- [ ] Append this plant to the end of the file:

```
Merged in `a1b2c3d4e5f6`.
```

- [ ] Run, blocking: `./gradlew test --tests '*AdrCitationsTest'`
- [ ] **Observe a real assertion failure**, not a compile error. Expect
      `every commit hash cited in docs/adr is allowlisted, in the file it is cited in` to fail,
      naming `0063-a-read-only-census-of-the-graph.md` and the hash `a1b2c3d4e5f6`. **Quote the
      actual message in the task report.** If it passes, the guard is not reading this file and the
      task stops and reports.
- [ ] Remove the plant. Re-run the same command and see it green.

### Step 3 — append the amendment (GREEN)

- [ ] Append exactly this, after one blank line at the end of the file:

```
**Amendment (2026-09-12, issue #315): the `known list` section's `never expanded` row is a floor,
and this records what the first run that tried to move it measured.**

Nothing above is edited and this ADR keeps `Accepted`. The amendment above for #311 named a residual
— an expansion that ran and recorded nothing leaves no row, so this rule cannot tell that case from
an entity nothing ever visited — and put it in "a small family" that all err the same conservative
way. The run on #313 measured that residual on the whole of the population this row names: every
entity the row counted was visited, Wikidata returned no whitelisted claim for any of them, and the
`known list` section printed afterwards read the same as the reading on #311. No figure from either
is restated here; the issues carry them.

**So the row is a floor once every entity it names has been visited**, and not a countdown. What is
left in it at that point is entities Wikidata states nothing about in the vocabulary this project
registers — thin acts rather than neglected ones — and no Wikidata expansion can leave a trace on
them for `Expanded` to read. Nothing in this tool will drive the row to zero, and an operator who
waits for zero is waiting on the wrong number.

**Nothing about the row, the rule or the section changes.** `KnownListCensus` and `CensusReport` are
untouched by this issue, and so is `domain.Expanded`'s rule. What changes is what the developer
guide tells the owner to do with the number, and
[ADR 66](0066-expand-every-promotion-from-a-dev-tool.md)'s 2026-09-12 amendment for #315 is where
the expander's half of that is decided — including the log-side alternative that would have let the
row empty, declined there with its reason.

**Nothing here is unit-testable on its own, and that is said out loud rather than left implied.** No
behaviour changed and no test was written for behaviour. The verification of this *document* is the
full gate over an otherwise unchanged tree: `AdrIndexTest`, `AdrCitationsTest`,
`DocumentationLinksTest` for the relative link above, and `javadoc -Werror` inside
`./gradlew check`.
```

### Step 4 — what in the tree makes each sentence true

- [ ] Confirm each, by reading the named file, and record the check in the task report:

| sentence | what makes it true |
| --- | --- |
| "The amendment above for #311 named a residual … 'a small family' that all err the same conservative way" | ADR 63's own 2026-09-12 amendment for #311, the paragraph beginning "The rule deliberately does not read the MusicBrainz adapter's own references": "That is one of a small family of residuals rather than the only one … an expansion that ran and returned nothing leaves no row either" |
| "every entity the row counted was visited, Wikidata returned no whitelisted claim for any of them, and the `known list` section printed afterwards read the same as the reading on #311" | the run comment on issue #313 (the census after, and its read) — cited, not restated |
| "no Wikidata expansion can leave a trace on them for `Expanded` to read" | `Expanded.seedOf` reads a seed only from a reference beginning `wdqs:` or containing `$` with a `[Qq]\d+` prefix; both are written by `ClaimMapper` / `ReverseClaims` from a Wikidata response, so a response with no whitelisted claim writes neither |
| "`KnownListCensus` and `CensusReport` are untouched by this issue, and so is `domain.Expanded`'s rule" | `git diff --stat` at the end of this plan shows no change to either class's code; Task 4 adds javadoc only |
| "ADR 66's 2026-09-12 amendment for #315 is where the expander's half of that is decided" | Task 2 of this plan writes it; if Task 2 is not yet committed the link still resolves, because it names the file and no anchor |

### Step 5 — verify (GREEN) and commit

- [ ] Blocking, without `--rerun-tasks`:
      `./gradlew test --tests '*AdrIndexTest' --tests '*AdrCitationsTest' --tests '*DocumentationLinksTest'`
- [ ] Confirm the report says the task **ran** rather than `UP-TO-DATE` — that is the check that
      `inputs.dir("docs")` saw the edit.
- [ ] Blocking:
      `SEGUE_REQUIRE_BROWSER=true SEGUE_REQUIRE_GRAPHVIZ=true ./gradlew check --rerun-tasks`
- [ ] `git status`, then
      `git add docs/adr/0063-a-read-only-census-of-the-graph.md` (stderr visible), then commit:

```
ADR 63: the known list's never expanded row is a floor (#315)

The run on #313 visited every entity the row named and Wikidata had nothing
to say about any of them, so what the row counts after that is Wikidata-thin
entities rather than work left undone. Records the reading as a dated
amendment; the row, the rule and the section are unchanged.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
```

---

## Task 2 — ADR 66: idempotent, not self-limiting, and the marker declined

**Files:** `docs/adr/0066-expand-every-promotion-from-a-dev-tool.md`

### Step 1 — read the file's tail and confirm the anchor

- [ ] `sed -n '/Amendment (2026-09-12, issue #313)/,$p' docs/adr/0066-expand-every-promotion-from-a-dev-tool.md`
- [ ] Confirm the file ends `` `javadoc -Werror` inside `./gradlew check`. `` The new amendment is
      appended after one blank line. **Nothing above it is edited.**

### Step 2 — positive control: watch the link guard fire (RED)

- [ ] Append this plant to the end of the file:

```
See [ADR 99](0099-no-such-decision.md).
```

- [ ] Run, blocking: `./gradlew test --tests '*DocumentationLinksTest'`
- [ ] **Observe a real assertion failure** from
      `every relative link in the documentation resolves to a file and a heading`, naming
      `0066-expand-every-promotion-from-a-dev-tool.md` and `0099-no-such-decision.md`. **Quote it in
      the report.** If it passes, the guard is not reading this file; stop and report.
- [ ] Remove the plant; re-run and see it green. This control is what says the five relative links
      the amendment below adds are actually checked.

### Step 3 — append the amendment (GREEN)

- [ ] Append exactly this, after one blank line at the end of the file:

```
**Amendment (2026-09-12, issue #315): a `--known` run is idempotent in the graph and not
self-limiting, and the log-side marker that would make it self-limiting is declined here with its
reason.**

Nothing above is withdrawn, no decision above is edited, and this ADR keeps `Accepted`. The
amendment above for #313 shipped the flag; this one records what the first run of it showed about
running it a second time.

**It is not self-limiting.** The population is the file's ids, on their canonical side, that no row
in the log cites as an expansion's seed, and `domain.Expanded` reads a seed out of two Wikidata
reference shapes and nothing else — a forward statement id, and a reverse-discovered edge's own
reference. An expansion that ran and recorded no Wikidata assertion therefore leaves nothing for
that rule to see, so the same entity is in the population on the next run and the run after that. A
second `--known` run over the same file visits the same entities.

**It is idempotent where it matters, and it is not a no-op on the log.** The run on #313 added no
node and no net edge. It did append rows: an assertion restated is a row appended, which is how
corroboration and freshness work ([ADR 19](0019-assertion-log-source-of-truth.md)). So running it
again costs public-API calls and log rows and moves the projection nowhere.

**What the operator does instead of waiting for a zero.** Compare one dry run's `considered` against
the previous `--known` run's. `Preflight.considered` is the population after the rule, so it falls by
exactly the entities that became ones the rule covers; an unchanged count says the rest of the file
is thin and the run can stop. The developer guide's "Expanding every promotion" chapter is the
authority on that procedure and it is not restated here.
[ADR 63](0063-a-read-only-census-of-the-graph.md)'s 2026-09-12 amendment for #315 records the census
row's half of the same reading.

**Alternative rejected: an "expansion attempted, found nothing" claim in the log**, so that an
attempt would be evidence the rule could read and the population could empty to zero.

- **The price is a claim type and a migration.** It is a seventh implementor of the sealed
  `LoggedAssertion`, which every exhaustive switch over that interface in `src/main` would have to
  decide about — `Expanded.in`'s own, `SqliteAssertionLog`'s codec and `LogProjection` among them —
  and a schema change to a log that is never rewritten.
  [ADR 42](0042-store-p31-and-rederive-kind-at-projection.md) is where that price is already
  recorded: it shipped one schema change with no migration on an argument about the data that
  happened to be there, and says in as many words that the next schema change gets a real migration
  path and that the absence of one there is not a precedent.
- **The purchase is a row that reads zero instead of a floor** — the same reading, spelled so that
  it looks finished. What the owner needs from the number is whether another run would reach
  anything, and the comparison above answers that with no new state at all.
- **It is the same shape this project has declined twice already**, and neither refusal has been
  overtaken: the 2026-09-11 amendment above declined a marker for the promotions ("a real marker is
  a schema change this repository's own rule says gets a real migration path"), and
  [ADR 57](0057-the-floor-reports-itself.md) declined a derived expansion flag in part because one
  that did not conflate "never expanded" with "expanded and found nothing" "would have to be
  recorded rather than derived, which is a schema change to the assertion log".
- **A later issue may reopen it** with an argument this one does not have: that re-visiting the thin
  population costs enough — in calls, in rows, or in an operator's attention — to be worth a schema
  change. Nothing here forecloses that; what it refuses is paying for it to make a number read
  zero.

**Nothing here is unit-testable on its own, and that is said out loud rather than left implied.** No
behaviour changed and no test was written for behaviour. The verification of this *document* is the
full gate over an otherwise unchanged tree: `AdrIndexTest`, `AdrCitationsTest`,
`DocumentationLinksTest` for the relative links above, and `javadoc -Werror` inside
`./gradlew check`.
```

### Step 4 — what in the tree makes each sentence true

- [ ] Confirm each, by reading the named file, and record the check in the task report:

| sentence | what makes it true |
| --- | --- |
| "the file's ids, on their canonical side, that no row in the log cites as an expansion's seed" | `ExpandCli` composes the `--known` population from `Equivalences.canonical(List)` and `Expanded.onTheCanonicalSide(...)`; `KnownListCensus.of` composes the census's by the same two calls |
| "`domain.Expanded` reads a seed out of two Wikidata reference shapes and nothing else" | `Expanded.seedOf`: `wdqs:`-prefixed → last `:`-field; otherwise the text before `$` matched against `FORWARD_QID`; every other reference yields `null` |
| "an assertion restated is a row appended" | ADR 19 (assertion log is the source of truth, append-only); the guide's step-5 table row `claims / log rows` says the same of this tool |
| "`Preflight.considered` is the population after the rule" | `Preflight`'s `@param considered` javadoc: "when `--known` was given, is the file's entities after the never-expanded rule filtered them (#313)" |
| "a seventh implementor of the sealed `LoggedAssertion`" | `LoggedAssertion` `permits NodeAssertion, AssertionRecord, Retraction, LocalEntity, OwnerEdge, SameAs` — six |
| "every exhaustive switch over that interface in `src/main` would have to decide about — `Expanded.in`'s own, `SqliteAssertionLog`'s codec and `LogProjection` among them" | `grep -rln "case SameAs" src/main` names `IngestService`, `SqliteAssertionLog`, `RetractRun`, `OwnRun`, `LogProjection`, `Retractions`, `Equivalences`, `Expanded`; the amendment names three of them and says "among them", so the sentence does not depend on the count |
| "says in as many words that the next schema change gets a real migration path and that the absence of one there is not a precedent" | ADR 42, *This shortcut works exactly once more*: "The next schema change gets a real migration path. The absence of one here is not a precedent." |
| "the 2026-09-11 amendment above declined a marker for the promotions" | ADR 66's own 2026-09-11 amendment, *Alternatives rejected*, first bullet |
| "[ADR 57] declined a derived expansion flag … 'would have to be recorded rather than derived, which is a schema change to the assertion log'" | ADR 57, the passage beginning "And the moment for a source-format derivation has passed" |
| every relative link (`0019-`, `0042-`, `0057-`, `0063-`) | the four files exist under `docs/adr`; Step 2's control is what says the guard would catch one that did not |

### Step 5 — verify (GREEN) and commit

- [ ] Blocking, without `--rerun-tasks`:
      `./gradlew test --tests '*AdrIndexTest' --tests '*AdrCitationsTest' --tests '*DocumentationLinksTest'`
- [ ] Confirm the task **ran** rather than printing `UP-TO-DATE`.
- [ ] Blocking:
      `SEGUE_REQUIRE_BROWSER=true SEGUE_REQUIRE_GRAPHVIZ=true ./gradlew check --rerun-tasks`
- [ ] `git status`, then
      `git add docs/adr/0066-expand-every-promotion-from-a-dev-tool.md` (stderr visible), then
      commit:

```
ADR 66: a --known run is idempotent in the graph, not self-limiting (#315)

The rule reads Wikidata evidence, so an attempt that finds nothing is not
evidence and the same entities are handed to every later run. Records the
operator's replacement for waiting on a zero, and declines the log-side
attempt marker with its price: a seventh claim type and a migration, for a
row that would read zero instead of a floor.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
```

---

## Task 3 — the developer guide, and the second copy of its sentence in a test

**Files:** `docs/developer-guide.md`,
`src/test/java/com/robsartin/segue/expand/DeveloperGuideExpandPromotionsExamplesTest.java`

**Read first:** spec premise corrections 1 and 2. There is **no** sentence in the guide promising a
zero; the sentence to correct is the one that makes a single reading of the row the whole decision
procedure. `grep -n "never expanded" docs/developer-guide.md` returns exactly two hits, and only the
one at the `--known` variant is corrected here.

### Step 1 — positive control: watch the order guard fire (RED)

The one guard in this repository that reads this chapter's *substance* is the example-order check.
This control is what says it is looking at the chapter, and it is also the control that the prose
edits below move no command.

- [ ] In `docs/developer-guide.md`, temporarily delete the line
      `./gradlew graphCensus --args="--db $HOME/.segue/segue.db --known $HOME/known.csv"` from the
      "Only what your own list says nothing has expanded: `--known`" section (leave its fence).
- [ ] Run, blocking:
      `./gradlew test --tests '*DeveloperGuideExpandPromotionsExamplesTest'`
- [ ] **Observe a real assertion failure** from
      `the chapter shows the census, the dry run, the run, the census, then the since-variant's own
      dry run and run, then the known-list variant's census, dry run and run, in that order` — an
      AssertJ `containsExactly` diff whose actual list is missing the third `graphCensus`. **Quote
      the actual diff in the report.**
- [ ] Restore the deleted line. Re-run the same command and see it green.

### Step 2 — correct the runbook's census sentence (GREEN)

- [ ] Replace exactly this text in `docs/developer-guide.md`:

```
Take the census first, with the same file — its `never expanded` row is what says whether this run
is worth making at all, and it is the same rule this run selects by
([ADR 63](adr/0063-a-read-only-census-of-the-graph.md), #311):
```

with:

```
Take the census first, with the same file. Its `never expanded` row is the same rule this run
selects by ([ADR 63](adr/0063-a-read-only-census-of-the-graph.md), #311), so it is the reading this
run is measured against — and not a number this run drives to zero. The row counts entities no row
in the log cites as an expansion's seed, and that rule reads Wikidata's own reference shapes and
nothing else, so an entity Wikidata states nothing about in the vocabulary segue registers stays in
the count however many times you expand it
([ADR 66](adr/0066-expand-every-promotion-from-a-dev-tool.md)'s 2026-09-12 amendment for #315):
```

### Step 3 — add the stopping rule after the dry run (GREEN)

- [ ] In the same section, insert this paragraph between the paragraph beginning
      ``**What `considered` means here.**`` and the line `The run:`, with one blank line either side:

```
**When to stop running this at all.** Compare this dry run's `considered` against the previous
`--known` run's dry run. It falls by exactly the entities that became ones the rule covers, so a
smaller count means the last run reached something. **An unchanged count means the entities left
are Wikidata-thin** — Wikidata states nothing about them in the vocabulary segue registers, so
there is nothing for an expansion to record and nothing for the rule to read afterwards — and
running it again visits the same entities, calls the same public APIs and appends rows that move the
graph nowhere. Stop there. The census's `never expanded` row says the same thing over a slightly
smaller population: it counts only the entities the graph holds a node for, where `considered`
also counts the ids your file names that it does not, which this run refuses one at a time as
unknown entities. Neither is a countdown (the run on #313;
[ADR 63](adr/0063-a-read-only-census-of-the-graph.md)'s and
[ADR 66](adr/0066-expand-every-promotion-from-a-dev-tool.md)'s 2026-09-12 amendments for #315).
```

### Step 4 — the census chapter's description of the row (GREEN)

- [ ] In "Looking at the shape of your graph", at the end of `### What the two sub-sections mean` —
      after the paragraph ending "is the everyday way that happens." and before
      `### Why the output is safe to paste` — insert, with one blank line either side:

```
**`never expanded` is a floor, not a queue.** The row counts entities in the graph that no row in
the log cites as an expansion's seed, and `domain.Expanded` reads a seed out of Wikidata's own
reference shapes alone — so an expansion that ran and recorded no Wikidata assertion leaves nothing
for it to count. Once a `--known` expansion run has visited everything the row names, what is left
in it is the entities Wikidata states nothing about in the vocabulary segue registers, and the row
stays where it is. [Expanding every promotion](#expanding-every-promotion) says how to read one
reading against the next, and how to tell that another run would reach nothing (the run on #313;
[ADR 63](adr/0063-a-read-only-census-of-the-graph.md)'s and
[ADR 66](adr/0066-expand-every-promotion-from-a-dev-tool.md)'s 2026-09-12 amendments for #315).
```

### Step 5 — the second copy of the sentence, inside a test's message (GREEN)

The guide sentence Step 2 corrects is restated inside an assertion description. Left alone it
survives the correction under a different roof — the single-source failure this repository keeps
recording.

- [ ] In `DeveloperGuideExpandPromotionsExamplesTest.shouldRunEveryStepInOrderWhenTheChapterIsRead`,
      replace this fragment of the `as(...)` text:

```
                + " three --known entries are the second variant, and the census with the same"
                + " flag comes first because the never expanded count it prints is what says"
                + " whether the run is worth making at all. A parser cannot see any of that",
```

with:

```
                + " three --known entries are the second variant, and the census with the same"
                + " flag comes first because the never expanded count it prints is the reading"
                + " the run is measured against — a floor rather than a countdown, which is why"
                + " the chapter compares one dry run's considered with the previous run's"
                + " (#315). A parser cannot see any of that",
```

- [ ] **No assertion changes.** `containsExactly`'s nine expected commands are untouched, which is
      the point: the chapter's commands did not move.

### Step 6 — what in the tree makes each added sentence true

| sentence | what makes it true |
| --- | --- |
| "that rule reads Wikidata's own reference shapes and nothing else" | `Expanded.seedOf` — `wdqs:` prefix, or the text before `$` matching `[Qq]\d+`; anything else yields `null` |
| "`considered` … falls by exactly the entities that became ones the rule covers" | `Preflight`'s `@param considered`; `ExpandCli` filters the file's canonical ids by `Expanded.covers` |
| "it counts only the entities the graph holds a node for, where `considered` also counts the ids your file names that it does not" | `KnownListCensus.read` increments `neverExpanded` only inside the `node != null` branch; `Preflight.considered` is the filtered population before any node lookup, and `inTheGraph` is the separate count |
| "which this run refuses one at a time as unknown entities" | `EntityExpansion.expand` returns `Refused(UNKNOWN_ENTITY)` before any adapter is asked; ADR 66's 2026-09-12 amendment for #313, *An id the graph holds no node for is refused, not dropped* |
| "an expansion that ran and recorded no Wikidata assertion leaves nothing for it to count" | `Expanded.in` reads only `NodeAssertion` / `AssertionRecord` provenance; no row means no seed |
| the anchor `#expanding-every-promotion` | the guide's `## Expanding every promotion` heading, already linked from its table of contents |

### Step 7 — verify (GREEN) and commit

- [ ] Blocking, without `--rerun-tasks`:
      `./gradlew test --tests '*DeveloperGuideExpandPromotionsExamplesTest' --tests '*DeveloperGuideCensusExamplesTest' --tests '*DeveloperGuideEnumerationsTest' --tests '*DocumentationLinksTest'`
- [ ] Confirm the task **ran** rather than printing `UP-TO-DATE`, and that the order check passed —
      that pair is the control that the prose edits changed no command.
- [ ] `./gradlew spotlessApply` (Step 5 touched a `.java` file), then re-read the edited `as(...)`
      block and confirm it still reads as intended after re-wrapping.
- [ ] Blocking:
      `SEGUE_REQUIRE_BROWSER=true SEGUE_REQUIRE_GRAPHVIZ=true ./gradlew check --rerun-tasks`
- [ ] `git status`, then
      `git add docs/developer-guide.md src/test/java/com/robsartin/segue/expand/DeveloperGuideExpandPromotionsExamplesTest.java`
      (stderr visible), then commit:

```
The runbook says when a --known run can stop (#315)

One reading of the census's never expanded row cannot say whether a run is
worth making, because the row is a floor after the first one. The chapter now
compares one dry run's considered against the previous run's, and says what an
unchanged count means. The census chapter says the same about the row, and the
guide test's own restatement of the old sentence follows.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
```

---

## Task 4 — the two javadoc sentences

**Files:** `src/main/java/com/robsartin/segue/domain/Expanded.java`,
`src/main/java/com/robsartin/segue/census/KnownListCensus.java`

### Step 1 — positive control: watch the javadoc guards fire (RED)

- [ ] In `Expanded.java`'s class javadoc, temporarily add a line:
      `   * <p>See {@code ExpandedTest#shouldNotExistAnywhere}.`
- [ ] Run, blocking: `./gradlew test --tests '*JavadocCitationsTest'`
- [ ] **Observe a real assertion failure** naming `Expanded.java` and
      `ExpandedTest#shouldNotExistAnywhere` as a citation that resolves to nothing. **Quote it.**
- [ ] Remove the plant; re-run and see it green.
- [ ] Second control, for the other gate: in `KnownListCensus.java`, temporarily change
      `{@link Expanded}` in the `neverExpanded` `@param` to `{@link Expanded#noSuchMember}`, then run
      `./gradlew javadoc` blocking and **observe the build fail** with javadoc's own
      `reference not found` error. Restore, re-run, see it pass. If either control does not fire,
      stop and report.

### Step 2 — `Expanded`: one sentence (GREEN)

- [ ] In `Expanded.java`'s class javadoc, insert this paragraph **immediately before** the line
      `` * <p><b>It lives here for the shape {@code KindMapper.rederive} (ADR 42) and {@link Retractions} ``
      — that is, after the bare `` * `` separator line that follows
      `` * here — no row in the log cites the entity as a seed — and both err the same conservative way. ``.
      The block below ends with its own `` * `` separator, so the result has exactly one blank
      javadoc line on each side of the new paragraph:

```
 * <p><b>That residual is measured rather than hypothetical, and it is what makes a re-expansion
 * pass reading this answer not self-limiting:</b> the first {@code expandPromotions --known} run
 * visited every known-list entity this rule called unexpanded and recorded no Wikidata assertion
 * for any of them, so those entities are still in this answer and would be handed to the next run
 * as well — ADR 63's and ADR 66's 2026-09-12 amendments carry that reading and the log-side
 * alternative declined with it (#313, #315).
 *
```

- [ ] `{@code expandPromotions --known}` is whole on one source line and must stay that way.

### Step 3 — `KnownListCensus`: one sentence (GREEN)

- [ ] In `KnownListCensus.Population`'s javadoc, replace exactly:

```
   * @param neverExpanded in the graph, and no row cites them as a seed — {@link Expanded}'s answer
```

with:

```
   * @param neverExpanded in the graph, and no row cites them as a seed — {@link Expanded}'s answer.
   *     Once a {@code --known} expansion run has visited everything this counts, what is left is
   *     entities Wikidata states nothing about in the vocabulary this project registers, so read it
   *     as a floor rather than a queue that empties (the run on #313, #315)
```

### Step 4 — what in the tree makes each sentence true

| sentence | what makes it true |
| --- | --- |
| "the first `expandPromotions --known` run visited every known-list entity this rule called unexpanded and recorded no Wikidata assertion for any of them" | the run comment on issue #313 — cited by issue number, no figure restated |
| "those entities are still in this answer and would be handed to the next run as well" | `Expanded.in` adds a seed only from a reference `seedOf` can read; no Wikidata assertion recorded means no such reference, so `covers` still answers false and `ExpandCli`'s `--known` filter still includes them |
| "read it as a floor rather than a queue that empties" | the same, plus ADR 63's 2026-09-12 amendment for #315 (Task 1) |
| "ADR 63's and ADR 66's 2026-09-12 amendments carry that reading" | Tasks 1 and 2; both are committed before this task runs |

### Step 5 — verify (GREEN) and commit

- [ ] `./gradlew spotlessApply`, then **re-read both edited javadoc comments** and confirm no
      `{@code …}` or `{@link …}` span was split across two source lines by the re-wrap. If one was,
      break the paragraph rather than letting it wrap.
- [ ] Blocking, without `--rerun-tasks`:
      `./gradlew test --tests '*JavadocCitationsTest'` and `./gradlew javadoc`
- [ ] Blocking:
      `SEGUE_REQUIRE_BROWSER=true SEGUE_REQUIRE_GRAPHVIZ=true ./gradlew check --rerun-tasks`
- [ ] Confirm `git diff --stat` shows **javadoc lines only** — no method body, signature or constant
      changed in either file.
- [ ] `git status`, then
      `git add src/main/java/com/robsartin/segue/domain/Expanded.java src/main/java/com/robsartin/segue/census/KnownListCensus.java`
      (stderr visible), then commit:

```
Expanded and KnownListCensus say the row is a floor (#315)

One sentence each: the residual this rule has always stated was measured on
the whole of the known list's never-expanded population by the run on #313, so
a re-expansion pass reading it is not self-limiting and the census row is a
floor rather than a queue that empties. Javadoc only.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
```

---

## Done when

- [ ] Four commits on `315-ready`, one per document group, each green at the gate.
- [ ] `git diff main...315-ready --stat` shows six files: two ADRs, the guide, one test (a message
      only), and two `src/main` files (javadoc only).
- [ ] No commit hash, `.superpowers/` path, qid or graph figure in anything committed.
- [ ] Each task's report quotes the real failure text of its positive control.
