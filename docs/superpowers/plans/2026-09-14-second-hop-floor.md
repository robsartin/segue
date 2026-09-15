# The second-hop rows inherit the never-expanded floor — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** close issue #326. Record, in the two ADRs that own the decisions and in four places in the
developer guide, that the census's `with someone to expand beside`, `with no one` and
`distinct to expand` rows — added by the 2026-09-13 amendment for #319 — inherit `never expanded`'s
floor for the identical reason: `SecondHop.toExpandBeside` excludes a neighbour only once
`domain.Expanded` already covers it, and `Expanded` reads a seed out of two Wikidata reference shapes
and nothing else. Decline the three alternatives the issue names, each with its own reason, and
correct the one clause in ADR 66's own prior amendment the new reading shows incomplete.

**Architecture:** nothing. **This plan changes no production behaviour and touches no method body,
signature or constant.** Two ADRs, one document, one javadoc comment:

- `docs/adr/0063-a-read-only-census-of-the-graph.md` — one appended amendment, dated 2026-09-14,
  issue #326.
- `docs/adr/0066-expand-every-promotion-from-a-dev-tool.md` — one appended amendment, same date and
  issue, overtaking one clause of its own 2026-09-13 amendment.
- `docs/developer-guide.md` — four edits: one sentence in "Expanding every promotion"'s
  `--second-hop` variant's "How to read the three rows" paragraph; a replaced paragraph in the same
  variant, after the dry run; one sentence in "Looking at the shape of your graph"'s
  `### What the two sub-sections mean`; one clause added to "What to file from what you saw".
- `src/main/java/com/robsartin/segue/census/KnownListCensus.java` — one sentence, on the
  `distinctToExpand` `@param`.

**Tech Stack:** Java 25, Gradle (plain `./gradlew`), JUnit 5, AssertJ, ArchUnit.

**Spec:** `docs/superpowers/specs/2026-09-14-second-hop-floor-design.md` — read its **Premise
corrections** section first. In particular, correction 2: ADR 66's own 2026-09-13 amendment states
one clause as an unconditional fact that the new reading shows holds only in the non-residual case,
and this plan corrects it with a short overtake rather than leaving it silently wrong.

## Global Constraints

- **No behaviour changes, and no test is written for behaviour.** That is the honest exception this
  project allows for pure prose, and it is stated here rather than left implied. Nothing in this plan
  edits a method body, a signature or a constant. If a step seems to require one, stop and report.
- **What verifies the prose**, named rather than assumed: `AdrIndexTest` (every ADR file has one
  index row agreeing on number, title and status), `AdrCitationsTest` (no un-allowlisted commit hash
  under `docs/adr`), `DocumentationLinksTest` (every relative link resolves to a file and a heading),
  `DeveloperGuideExpandPromotionsExamplesTest` (the chapter's twelve `./gradlew` examples, in order,
  unmoved), `DeveloperGuideCensusExamplesTest` (the census chapter's `graphCensus` examples, run as a
  control that chapter is otherwise unmoved), `JavadocCitationsTest` (citation shapes in `src/main`
  javadoc), and `javadoc -Werror` inside `./gradlew check`.
- **Nothing here can red on the prose itself, so every task carries a positive control instead.**
  Each task plants a defect in the file it is about, runs the guard that reads that file, **quotes
  the real failure text in its report**, and removes the plant before making the real edit. A task
  whose control did not fire has proved nothing and must stop and report. A compile error is never a
  red.
- **`docs` is a declared input of the `test` task** (`build.gradle.kts:150`,
  `inputs.dir("docs").withPropertyName("docs")...`), so a document edit re-runs the suite. Every
  per-task verification loop therefore runs **without** `--rerun-tasks`, and each task's report says
  the task **ran** rather than printed `UP-TO-DATE`.
- **ADRs are immutable.** Only an appended, dated amendment. Nothing above an amendment is edited,
  reworded or deleted, and both ADRs keep `Accepted`.
- **No commit hash anywhere under `docs/adr`. No `.superpowers/` path in any committed file. No qid.
  No figure from the owner's graph** — the census and run this plan cites are cited as "the census
  and run on issue #323 (2026-09-14)", never restated.
- **No over-claims.** The words "settles", "establishes", "proves" and "by construction" do not
  appear in any text this plan adds. What was measured is cited as measured; what follows from the
  code is cited to the code.
- **Mikado**: the gate is green before every commit, and each task ends green and is independently
  reviewable. **Stage by explicit path, git stderr visible — never `git add -A`, never `2>/dev/null`
  on `git add`.** Read `git status` before every commit. Commits end, after a blank line,
  `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`.
- Gate, **blocking, never backgrounded**, before each commit:
  `SEGUE_REQUIRE_BROWSER=true SEGUE_REQUIRE_GRAPHVIZ=true ./gradlew check --rerun-tasks`. Run
  `./gradlew spotlessApply` first in the task that touches a `.java` file.
- **Only JDK 25 is installed and Gradle launches on it.** Plain `./gradlew`; never
  `/usr/libexec/java_home -v 21` (it silently returns 25).
- **Never run a writing dev task** — `expandPromotions`, `graphCensus` against a real database,
  `own`, `ownClaim`, `retractEntity`, `rate`, `evaluate`, any seeding task. `~/.segue/segue.db` is
  never read, written, copied or created. Nothing in this plan runs any dev tool at all.
- **`{@code X}` and `{@link X}` spans stay whole on one source line.** `spotlessApply` re-wraps
  javadoc; after it runs, re-read the edited javadoc comment and confirm no span was split across
  lines. A paragraph break is the fix if one is. Prove it with
  `grep -nE '\{@(code|link)[^}]*$' src/main/java/com/robsartin/segue/census/KnownListCensus.java` —
  empty output is the pass.
- **Markdown links stay whole on one line.** No `[text](target)` this plan adds is split across a
  line break.
- Work only in `/Users/sartin/code/segue/wt-326`, on branch `326-ready`. You are the sole committer
  there.

---

## Task 1 — ADR 63: the three second-hop rows are a floor

**Files:** `docs/adr/0063-a-read-only-census-of-the-graph.md`

### Step 1 — read the file's tail and confirm the anchor

- [ ] `sed -n '/Amendment (2026-09-13, issue #319)/,$p' docs/adr/0063-a-read-only-census-of-the-graph.md`
- [ ] Confirm the file's last line is `` `javadoc -Werror` inside `./gradlew check`. `` The new
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
**Amendment (2026-09-14, issue #326): the three rows the 2026-09-13 amendment for #319 added
inherit `never expanded`'s floor, and this records what the first `--second-hop` run measured.**

Nothing above is edited and this ADR keeps `Accepted`. `with someone to expand beside`, `with no
one` and `distinct to expand` are read off `domain.SecondHop.toExpandBeside`, which excludes a
neighbour only once `Expanded.covers` it — the same call, and the same rule, the `never expanded`
row and the 2026-09-12 amendments for #311, #313 and #315 above already govern. A `--second-hop` run
expands exactly the neighbours `distinct to expand` names, so the question this amendment answers is
the one the amendment for #315 above already answered for `--known`, asked of a different
population: does expanding a neighbour make `Expanded` cover it afterwards?

**Not always, and the census and run on issue #323 (2026-09-14) is where that was measured.** A
neighbour whose expansion recorded only a MusicBrainz-backed edge, or a Wikidata forward claim
carrying no statement id, leaves no reference `Expanded.seedOf` reads — the same residual the
2026-09-12 amendment for #311 above already grouped into a small family of residuals that all err
the same conservative way, met here on a population `--known` never visits. The run on #323 visited
every neighbour `distinct to expand` named for that population and recorded something for each of
them, and the row printed afterwards did not reach zero.

**So the three rows are a floor once every entity `distinct to expand` names has been visited by a
`--second-hop` run that reported `added nothing`, `refused` and `failed` all zero and named no
source under `unavailable`** — and not a countdown. What is left at that point is neighbours
Wikidata (and, for the residual above, MusicBrainz) recorded something for that carries no seed
reference this rule reads — thin, not unfetched — and no further `--second-hop` run can move it. The
developer guide's `--second-hop` chapter is the authority on the procedure this puts in the
operator's hands, and it is not restated here.

**Alternatives rejected.**

- **A visited-marker row in the log.** Declined already, on #313 and #315: the reverse pass already
  records each neighbour it discovers as a node claim carrying that neighbour's own bare qid, and a
  rule phrased "the reference names this entity" would read every discovered neighbour as having
  expanded itself — `Expanded`'s own class javadoc names this as the trap its shape is written to
  avoid.
- **Reading the MusicBrainz adapter's own references.** `MusicBrainzSourceAdapter.toAssertion`
  builds `sourceRef` from the seed's MBID, never its qid, so a rule reading it would need a map back
  from a MusicBrainz MBID to a Wikidata qid, and the fold holds none: `MusicBrainzIdentity`
  (`expansion.WikidataMusicBrainzIdentity` in the shipped wiring) answers `mbidFor` and
  `identitiesFor` at expansion time, and nothing stores what either call returned. Direction
  compounds it — MusicBrainz reports `forward` or `backward` relative to the seed, and
  `toAssertion` puts the seed on whichever end that names, so `from` and `to` swap with it and the
  reference alone cannot say which end was the seed either.
- **A "visited" count in the census.** Nothing in the log carries it. The log records what an
  expansion asserted, not that it ran, and the residual above is exactly the case where it asserted
  something that carries no reference this rule reads — the same gap a visited marker would need a
  new claim type to close, declined for its own reason by the 2026-09-12 amendment above for #315.

**Nothing about the rows, the rule or the sections changes.** `domain.SecondHop`, `KnownListCensus`
and `CensusReport` emit exactly what they emitted before; the only edit under `src/main` this issue
makes is one javadoc sentence on `KnownListCensus.Population`'s `distinctToExpand`.
[ADR 66](0066-expand-every-promotion-from-a-dev-tool.md)'s 2026-09-14 amendment corrects the one
sentence there this reading overtakes.

**Nothing here is unit-testable on its own, and that is said out loud rather than left implied.** No
behaviour changed and no test was written for behaviour. The verification of this *document* is the
full gate over an otherwise unchanged tree: `AdrIndexTest`, `AdrCitationsTest`,
`DocumentationLinksTest` for the relative link above, and `javadoc -Werror` inside `./gradlew check`.
```

### Step 4 — what in the tree makes each sentence true

- [ ] Confirm each, by reading the named file, and record the check in the task report:

| sentence | what makes it true |
| --- | --- |
| "`with someone to expand beside`, `with no one` and `distinct to expand` are read off `domain.SecondHop.toExpandBeside`, which excludes a neighbour only once `Expanded.covers` it" | `SecondHop.toExpandBeside`: `if (node != null && WORTH_EXPANDING.contains(node.kind()) && !expanded.covers(neighbour)) beside.add(neighbour);`; `KnownListCensus.read` derives `isolatedWithSomeoneToExpand`, `isolatedWithNoOne` and `distinctToExpand` from `secondHop.toExpandBeside(qid)` and `secondHop.toExpand()` |
| "A `--second-hop` run expands exactly the neighbours `distinct to expand` names" | ADR 66's 2026-09-13 amendment: "The population this flag visits is `SecondHop.toExpand()`"; `KnownListCensus.Population`'s `distinctToExpand` `@param`: "Only on the with-promotions population is this the spend a `--second-hop` run would make ... since that is the population the run itself composes" |
| "A neighbour whose expansion recorded only a MusicBrainz-backed edge, or a Wikidata forward claim carrying no statement id, leaves no reference `Expanded.seedOf` reads" | `Expanded.seedOf`: a `wdqs:`-prefixed reference reads its last `:`-field; otherwise the text before `$` is matched against `FORWARD_QID`; anything else returns `null`. `MusicBrainzSourceAdapter.toAssertion`'s `sourceRef = "artist/" + seedMbid + "#" + relation.type() + ":" + relation.targetMbid()` matches neither shape; `ClaimMapper`'s fallback `<property>:<objectQid>` reference (named in `Expanded`'s own class javadoc) matches neither either |
| "the census and run on issue #323 (2026-09-14) is where that was measured" | issue #323 — cited by number, no figure restated |
| "MusicBrainz reports `forward` or `backward` relative to the seed, and `toAssertion` puts the seed on whichever end that names" | `MusicBrainzSourceAdapter.toAssertion`: `boolean forward = FORWARD.equals(relation.direction()); String from = forward ? seedQid : targetQid; String to = forward ? targetQid : seedQid;` |
| "`MusicBrainzIdentity` (`expansion.WikidataMusicBrainzIdentity` in the shipped wiring) answers `mbidFor` and `identitiesFor` at expansion time, and nothing stores what either call returned" | `MusicBrainzIdentity` interface declares `Optional<String> mbidFor(String qid)` and `Map<String, BridgedIdentity> identitiesFor(Collection<String> mbids)`; `find . -iname WikidataMusicBrainzIdentity.java` resolves to package `com.robsartin.segue.expansion`; neither `Fold`, `LogProjection` nor `Equivalences` — the classes `SecondHop` and `KnownListCensus` read — holds a field for either call's answer |
| "the reverse pass already records each neighbour it discovers as a node claim carrying that neighbour's own bare qid" | `Expanded`'s class javadoc, the paragraph beginning "The shape is the whole rule, and the trap it avoids is a real row": "The reverse pass also records each neighbour it discovered as a node claim whose reference is that neighbour's own bare qid" |
| "the only edit under `src/main` this issue makes is one javadoc sentence on `KnownListCensus.Population`'s `distinctToExpand`" | Task 4 of this plan; `git diff main...326-ready --stat` at the end shows no other `src/main` file changed |
| link `[ADR 66](0066-expand-every-promotion-from-a-dev-tool.md)` | the file exists under `docs/adr`; Step 2's control is what says the guard would catch one that did not |

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
ADR 63: the second-hop rows inherit the never-expanded floor (#326)

SecondHop.toExpandBeside excludes a neighbour only once Expanded covers it,
the same rule and residual the known-list floor already reads, so the three
rows #319 added floor rather than empty. Records the reading, the developer
guide as the authority on the procedure, and the three alternatives declined.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
```

---

## Task 2 — ADR 66: one clause overtaken

**Files:** `docs/adr/0066-expand-every-promotion-from-a-dev-tool.md`

### Step 1 — read the file's tail and confirm the anchor

- [ ] `sed -n '/Amendment (2026-09-13, issue #319)/,$p' docs/adr/0066-expand-every-promotion-from-a-dev-tool.md`
- [ ] Confirm the file's last line is `` `javadoc -Werror` inside `./gradlew check`. `` and that the
      *Fixed at the start, and why re-runs need no state* paragraph contains the sentence "an entity
      this run expanded is covered by `Expanded` on the next read of the log". The new amendment is
      appended after one blank line at the end of the file. **Nothing above it is edited.**

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
- [ ] Remove the plant; re-run and see it green. This control is what says the link this amendment
      adds is actually checked.

### Step 3 — append the amendment (GREEN)

- [ ] Append exactly this, after one blank line at the end of the file:

```
**Amendment (2026-09-14, issue #326): one clause in the paragraph above is overtaken — an entity a
`--second-hop` run expanded is not always covered by `Expanded` afterwards.**

Nothing above is withdrawn, no decision above is edited, and this ADR keeps `Accepted`. The
2026-09-13 amendment's *Fixed at the start, and why re-runs need no state* paragraph says "an entity
this run expanded is covered by `Expanded` on the next read of the log", as an unconditional fact.
The census and run on issue #323 (2026-09-14) show it holds only when the expansion recorded a
reference `Expanded.seedOf` reads, and not when it recorded only a MusicBrainz-backed edge or a
Wikidata forward claim with no id — the same residual
[ADR 63](0063-a-read-only-census-of-the-graph.md)'s 2026-09-14 amendment for #326 records on this
population's own census rows. That amendment is the decision; this entry records which clause here
it overtakes.

**Nothing here is unit-testable on its own, and that is said out loud rather than left implied.** No
behaviour changed and no test was written for behaviour. The verification of this *document* is the
full gate over an otherwise unchanged tree: `AdrIndexTest`, `AdrCitationsTest`,
`DocumentationLinksTest` for the relative link above, and `javadoc -Werror` inside `./gradlew check`.
```

### Step 4 — what in the tree makes each sentence true

- [ ] Confirm each, by reading the named file, and record the check in the task report:

| sentence | what makes it true |
| --- | --- |
| the quoted clause "an entity this run expanded is covered by `Expanded` on the next read of the log" | ADR 66's 2026-09-13 amendment for #319, *Fixed at the start, and why re-runs need no state* paragraph, verbatim (confirmed in Step 1) |
| "it holds only when the expansion recorded a reference `Expanded.seedOf` reads" | Task 1's third table row, same guard |
| link `[ADR 63](0063-a-read-only-census-of-the-graph.md)` | the file exists; Step 2's own control proves the guard reads a link added to this file |

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
ADR 66: correct one clause about --second-hop coverage (#326)

The 2026-09-13 amendment said an expanded entity is covered by Expanded on
the next read of the log, as an unconditional fact. It holds only when the
expansion recorded a reference the rule reads, not for a MusicBrainz-only or
forward-without-id residual. Overtakes the one clause; nothing else moves.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
```

---

## Task 3 — the developer guide, four edits

**Files:** `docs/developer-guide.md`

**Read first:** spec premise correction 1. No test in `src/test` restates the sentence being
replaced, so unlike the #315 plan's Task 3 this task needs no matching test-message edit.

### Step 1 — positive control: watch the order guard fire (RED)

The one guard in this repository that reads this chapter's *substance* is the example-order check.
This control is what says it is looking at the chapter, and it is also the control that the prose
edits below move no command.

- [ ] In `docs/developer-guide.md`, temporarily delete the line
      `./gradlew expandPromotions --args="--db $HOME/.segue/segue.db --second-hop $HOME/known.csv"`
      from the "The ring beside what your list cannot place: `--second-hop`" section (leave its
      fence).
- [ ] Run, blocking: `./gradlew test --tests '*DeveloperGuideExpandPromotionsExamplesTest'`
- [ ] **Observe a real assertion failure** from
      `the chapter shows the census, the dry run, the run, the census, then the since-variant's own
      dry run and run, then the known-list variant's census, dry run and run, then the second-hop
      variant's census, dry run and run, in that order` — an AssertJ `containsExactly` diff whose
      actual list is missing the trailing `expandPromotions --second-hop`. **Quote the actual diff in
      the report.**
- [ ] Restore the deleted line. Re-run the same command and see it green.

### Step 2 — the "How to read the three rows" paragraph (GREEN)

- [ ] Replace exactly this text in `docs/developer-guide.md`:

```
**How to read the three rows.** `with someone to expand beside` plus `with no one` is the
`no known neighbour within N hops` row itself — the two partition it. Each sub-section prints its
own three rows, but only the `file and promotions` sub-section's `distinct to expand` is what a
`--second-hop` run would visit, counted before any run — the same `SecondHop.toExpand()` the run
itself visits, over the same with-promotions population. An act under `with no one` is one nothing
here can help: either its ring is fully fetched already, or its ring is works and places rather than
people and groups.
```

with:

```
**How to read the three rows.** `with someone to expand beside` plus `with no one` is the
`no known neighbour within N hops` row itself — the two partition it. Each sub-section prints its
own three rows, but only the `file and promotions` sub-section's `distinct to expand` is what a
`--second-hop` run would visit, counted before any run — the same `SecondHop.toExpand()` the run
itself visits, over the same with-promotions population. An act under `with no one` is one nothing
here can help: either its ring is fully fetched already, or its ring is works and places rather than
people and groups. **All three inherit the floor `never expanded` already has**, because
`SecondHop.toExpandBeside` excludes a neighbour only once `Expanded` already covers it — the same
rule and the same residual
([ADR 63](adr/0063-a-read-only-census-of-the-graph.md)'s 2026-09-14 amendment for #326).
```

### Step 3 — replace the wrong "should be down" paragraph with the stopping rule (GREEN)

- [ ] Replace exactly this text in `docs/developer-guide.md`:

```
Take the census again, with the same file, and compare it against the one you took first:
`no known neighbour` should be down, `distinct to expand` should be down, and `nodes` and `edges`
should be up. **A smaller run is a later run** — the dry run's `considered` is the only bound, and
there is no `--limit`.
```

with:

```
Take the census again, with the same file, and compare it against the one you took first: `nodes`
and `edges` should be up.

**When to stop running this at all.** Read the run's own block before you take that second census.
With `added nothing`, `refused` and `failed` all zero and no source named under `unavailable`, every
entity `considered` named was visited and recorded something, so whatever `distinct to expand` still
counts afterwards was visited too and is Wikidata-thin: `SecondHop.toExpandBeside` excludes a
neighbour only once `Expanded` covers it, the same rule and the same residual the `--known` variant's
stopping rule above reads — a neighbour this run recorded only a MusicBrainz-backed edge or a
Wikidata forward claim with no id for leaves no seed reference and stays counted for good
([ADR 63](adr/0063-a-read-only-census-of-the-graph.md)'s 2026-09-14 amendment for #326). Stop there;
a second run visits the same neighbours, calls the same public APIs and moves neither row. **When
the block named a `failed` entity or a source under `unavailable`**, that guarantee does not hold,
and the `--known` variant's own rule applies instead: compare this dry run's `considered` against
the previous `--second-hop` dry run's, over the same file — a fall means the last run reached
something and another is worth taking, and an unchanged count means the rest is thin only once a run
reporting no failure and no unavailable source has read it. There is still no `--limit`: the dry
run's `considered` is the only bound.
```

### Step 4 — the census chapter's own description of the three rows (GREEN)

- [ ] Replace exactly this text in `docs/developer-guide.md` (in `### What the two sub-sections
      mean`, under "## Looking at the shape of your graph"):

```
**`no known neighbour` breaks into three nested rows, in each sub-section.** `with someone to
expand beside` and `with no one` partition the row above them, so the two add up to it. `distinct to
expand` prints under both `file` and `file and promotions`, but only in the `file and promotions`
row is it what a `--second-hop` run would visit — the distinct people and groups across every act in
the first of those two, counted before any run, over the same population the run itself composes.
The labels name no kind on purpose —
`domain.SecondHop.WORTH_EXPANDING` is the one statement of which kinds count, and the labels cite it
rather than restating it.
```

with:

```
**`no known neighbour` breaks into three nested rows, in each sub-section.** `with someone to
expand beside` and `with no one` partition the row above them, so the two add up to it. `distinct to
expand` prints under both `file` and `file and promotions`, but only in the `file and promotions`
row is it what a `--second-hop` run would visit — the distinct people and groups across every act in
the first of those two, counted before any run, over the same population the run itself composes.
The labels name no kind on purpose —
`domain.SecondHop.WORTH_EXPANDING` is the one statement of which kinds count, and the labels cite it
rather than restating it. **All three inherit `never expanded`'s floor**, for the same reason: a
neighbour a `--second-hop` run visited and expanded, but whose recorded reference `Expanded.seedOf`
cannot read a seed out of, stays counted.
[Expanding every promotion](#expanding-every-promotion) says how to tell a `--second-hop` run has
reached it
([ADR 63](adr/0063-a-read-only-census-of-the-graph.md)'s 2026-09-14 amendment for #326).
```

### Step 5 — the "What to file from what you saw" bullet (GREEN)

- [ ] Replace exactly this text in `docs/developer-guide.md`:

```
- **Anything this chapter got wrong.** It was written against the code and checked against the
  parser, and it has been run — the expander's first run over the promotions (#284) and the first
  `--known` run (#313) among them. Each of those two sent something back: #293 corrected a label out
  of the first, and #315 corrected what this chapter says about the `--known` variant's stopping
  rule. The next run is what keeps it true.
```

with:

```
- **Anything this chapter got wrong.** It was written against the code and checked against the
  parser, and it has been run — the expander's first run over the promotions (#284), the first
  `--known` run (#313) and the first `--second-hop` run (#319) among them. Each of those runs sent
  something back: #293 corrected a label out of the first, #315 corrected what this chapter says
  about the `--known` variant's stopping rule, and #326 corrected what it says about the
  `--second-hop` variant's. The next run is what keeps it true.
```

### Step 6 — what in the tree makes each added sentence true

| sentence | what makes it true |
| --- | --- |
| "`SecondHop.toExpandBeside` excludes a neighbour only once `Expanded` already covers it" | Task 1's first table row, same guard |
| "with `added nothing`, `refused` and `failed` all zero and no source named under `unavailable`" | ADR 66's *The output contract*: "promotions (considered, expanded, added nothing, refused, failed)" and "shortfalls (... then `unavailable` and `truncated` per source id)" |
| "compare this dry run's `considered` against the previous `--second-hop` dry run's" | mirrors the `--known` variant's own "When to stop running this at all" paragraph, already in the guide and unedited by this task |
| "the expander's first run over the promotions (#284), the first `--known` run (#313) and the first `--second-hop` run (#319)" | ADR 66's own decision (#284) and its 2026-09-12 amendment for #313 and 2026-09-13 amendment for #319 |
| the anchor `#expanding-every-promotion` | already used and verified three times elsewhere in this document (the contents list, and two chapter cross-references) |
| "a neighbour a `--second-hop` run visited and expanded, but whose recorded reference `Expanded.seedOf` cannot read a seed out of, stays counted" | Task 1's third table row, same guard |

### Step 7 — verify (GREEN) and commit

- [ ] Blocking, without `--rerun-tasks`:
      `./gradlew test --tests '*DeveloperGuideExpandPromotionsExamplesTest' --tests '*DeveloperGuideCensusExamplesTest' --tests '*DeveloperGuideEnumerationsTest' --tests '*DocumentationLinksTest'`
- [ ] Confirm every task **ran** rather than printed `UP-TO-DATE`, and that the order check passed —
      that pair is the control that the prose edits changed no command.
- [ ] Blocking:
      `SEGUE_REQUIRE_BROWSER=true SEGUE_REQUIRE_GRAPHVIZ=true ./gradlew check --rerun-tasks`
- [ ] `git status`, then `git add docs/developer-guide.md` (stderr visible), then commit:

```
Runbook: when a --second-hop run can stop (#326)

The old paragraph told the owner to take a second census to see the row
should be down, when the first run's own block already decides it: with
added nothing, refused and failed zero and no unavailable source, the three
rows floor rather than empty. Adds the stopping rule in the --known
variant's shape, and says the same in the two places the three rows are
already described.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
```

---

## Task 4 — one javadoc sentence

**Files:** `src/main/java/com/robsartin/segue/census/KnownListCensus.java`

### Step 1 — positive control: watch the javadoc guards fire (RED)

- [ ] In `KnownListCensus.java`'s class javadoc, temporarily add a line:
      `   * <p>See {@code KnownListCensusTest#shouldNotExistAnywhere}.`
- [ ] Run, blocking: `./gradlew test --tests '*JavadocCitationsTest'`
- [ ] **Observe a real assertion failure** naming `KnownListCensus.java` and
      `KnownListCensusTest#shouldNotExistAnywhere` as a citation that resolves to nothing. **Quote
      it.**
- [ ] Remove the plant; re-run and see it green.
- [ ] Second control, for the other gate: in the `distinctToExpand` `@param` below, temporarily
      change `{@link Expanded}` to `{@link Expanded#noSuchMember}`, then run `./gradlew javadoc`
      blocking and **observe the build fail** with javadoc's own `reference not found` error.
      Restore, re-run, see it pass. If either control does not fire, stop and report.

### Step 2 — the sentence (GREEN)

- [ ] In `KnownListCensus.Population`'s javadoc, replace exactly:

```
   * @param distinctToExpand the distinct people and groups to expand across every isolated member
   *     of this population, counted before any run. Only on the with-promotions population is this
   *     the spend a {@code --second-hop} run would make, since that is the population the run
   *     itself composes (#319)
```

with:

```
   * @param distinctToExpand the distinct people and groups to expand across every isolated member
   *     of this population, counted before any run. Only on the with-promotions population is this
   *     the spend a {@code --second-hop} run would make, since that is the population the run
   *     itself composes (#319). Once a run that reported {@code added nothing}, {@code refused} and
   *     {@code failed} all zero, and no source under {@code unavailable}, has visited everything
   *     this counts, what is left is neighbours carrying no seed {@link Expanded} reads — the same
   *     floor {@link #neverExpanded} is, inherited because {@link SecondHop#toExpandBeside}
   *     excludes a neighbour only once {@link Expanded#covers} it too (#326)
```

### Step 3 — what in the tree makes each sentence true

| sentence | what makes it true |
| --- | --- |
| "the same floor `{@link #neverExpanded}` is" | `KnownListCensus.Population`'s own `neverExpanded` component, and its `@param` javadoc (from #315) reading it as a floor |
| "inherited because `{@link SecondHop#toExpandBeside}` excludes a neighbour only once `{@link Expanded#covers}` it too" | Task 1's first table row, same guard; `KnownListCensus` already imports both `Expanded` and `SecondHop` |
| "(#326)" | this issue |

### Step 4 — verify (GREEN) and commit

- [ ] `./gradlew spotlessApply`, then **re-read the edited javadoc** and confirm no `{@code …}` or
      `{@link …}` span was split across two source lines by the re-wrap. Prove it:
      `grep -nE '\{@(code|link)[^}]*$' src/main/java/com/robsartin/segue/census/KnownListCensus.java`
      — empty output is the pass. If one was split, break the paragraph rather than letting it wrap.
- [ ] Blocking, without `--rerun-tasks`:
      `./gradlew test --tests '*JavadocCitationsTest'` and `./gradlew javadoc`
- [ ] Blocking:
      `SEGUE_REQUIRE_BROWSER=true SEGUE_REQUIRE_GRAPHVIZ=true ./gradlew check --rerun-tasks`
- [ ] Confirm `git diff --stat` shows **javadoc lines only** — no method body, signature or constant
      changed.
- [ ] `git status`, then
      `git add src/main/java/com/robsartin/segue/census/KnownListCensus.java` (stderr visible), then
      commit:

```
KnownListCensus: distinctToExpand holds a floor after a run (#326)

One sentence: once a --second-hop run with no failure and no unavailable
source has visited everything this counts, what is left carries no seed
Expanded reads, for the same reason neverExpanded does. Javadoc only.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
```

---

## Done when

- [ ] Four commits on `326-ready`, one per task, each green at the gate.
- [ ] `git diff main...326-ready --stat` shows four files: two ADRs, the guide, and one `src/main`
      file (javadoc only).
- [ ] No commit hash, `.superpowers/` path, qid or graph figure in anything committed.
- [ ] Each task's report quotes the real failure text of its positive control.
