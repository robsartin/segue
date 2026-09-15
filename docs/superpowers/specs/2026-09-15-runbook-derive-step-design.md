# The `--known --add` chapter's derive step, corrected against a real run — design

Issue #332. Written 2026-09-15 against the code on `main`, after #328 (the chapter itself,
"Adding what your list names that the graph has never held: `--known --add`") and the first real
run of it, issue #330 (2026-09-15). Docs only, one file: `docs/developer-guide.md`. **No behaviour
changes, and no production code changes.**

## What the chapter said, and what the first run found

The chapter's step 1, as #328 shipped it, told the owner: take the mapping the seed tool already
wrote over the whole names list, `grep ',REJECTED,'` it for the non-touring rows, and start the
census from there. That assumes a resolved mapping already exists that carries both the touring
and non-touring rows together, with `status` as the only thing distinguishing them.

The real list is not that. It is a Setlist Scout export — a `status` column, and **no id column
at all** — because Setlist Scout's own job is scheduling, not identity. Issue #330's run went:
reshape the export to the seed tool's `name,kind,status` header (Setlist Scout's `category` is
the seed tool's `kind`), keep only the non-touring rows, `resolveNames` **that already-filtered
list**, read the review file, and only then start the chapter's own census. The mapping
`resolveNames` wrote already held only the rows this chapter wants — nothing was grepped out of
it, because nothing else was ever resolved into it.

**The old step 1 is not just missing a step; one clause in it is backwards.** `support.QidList`
reads "the first comma-separated field on a line that is exactly a QID", not filtered by
`status` at all. So had the touring and non-touring rows been resolved together, as the old step
1 assumed, the resulting mapping's `status`-`REJECTED` rows would already carry ids and would
already be part of any `--known` population fed that whole mapping — there would be nothing left
to *derive*, because the id rule does not read `status`. What makes step 1 do something is
filtering **before** the resolution run, not after: an unresolved export names no qid, and
`resolveNames` is what supplies one. Grepping an already-resolved mapping was never a necessary
step; it was one way to arrive at a file the owner's real list never needed, because the owner's
real list needed resolving in the first place.

The chapter's own intro paragraph (the sentence directly above "Step 0 applies unchanged") carries
the same wrong assumption in one sentence: "The seed tool resolved them to ids in the same mapping
as the touring acts." It did not, on the run that exists, and saying so there is corrected in the
same edit as step 1, for the same reason — an intro that contradicts the step below it is worse
than one that says nothing.

## What changes

One file, `docs/developer-guide.md`, two paragraphs, both inside "Adding what your list names
that the graph has never held: `--known --add`":

1. **The chapter's intro paragraph.** One sentence: "The seed tool resolved them to ids in the
   same mapping as the touring acts (ADR 40) and stopped there" becomes "The seed tool resolves
   each of those rows to an id of its own, on a run over just the rows this chapter wants (ADR
   40), and stops there" — the rest of the paragraph, including the `--add` explanation, is
   untouched.
2. **Step 1, "Derive the file."** Replaced in full. The new text:
   - States the list is whatever names the owner holds for the domain, in the seed tool's own
     `name,kind,status` shape, citing the `Bulk seeding` chapter as the authority on that shape.
   - Says a Setlist Scout export is reshaped first — `category` renamed to `kind`, kept to the
     rows whose `status` is the one wanted — in **prose**, not a runnable command (see *Command
     or prose* below).
   - Shows `./gradlew resolveNames --args="--list $HOME/rejected-names.csv --mapping
     $HOME/rejected.csv"` as the resolution run, landing the mapping at the same
     `$HOME/rejected.csv` every step from 2 onward already names — so nothing past step 1 needs
     editing.
   - Says the review file (a sibling of the *list*, not the mapping) is read by the owner, names
     only, never pasted.
   - Adds the one sentence the issue asks for: why an already-resolved mapping's own rejected rows
     are not this step's input — the id rule reads any field that is a qid regardless of `status`,
     so once a mapping already exists a `status`-filtered subset of it is already on the known
     population, and there is nothing there to derive.

Steps 2 through 7 of the chapter are untouched: step 2's census already reads a file named
`$HOME/rejected.csv`, and that is exactly the path the new resolveNames example writes to.

### Command or prose

The dispatch's open question: show the export's reshape as a small command, on the precedent the
guide already sets with `grep ',REJECTED,' "$HOME/names-resolved.csv" > "$HOME/rejected.csv"`, or
say it in prose. **Decision: prose.** The precedent command works because `SeedFiles` — the
mapping's own writer — is code in this repository, so its quoting rule (a field is quoted only
when it holds a comma, a quote or a newline) is something this guide can cite as fact. A Setlist
Scout export is not: its column order, whether it carries columns beyond `name`/`category`/
`status`, and its own quoting convention are facts about another repository this one does not
read. A concrete `awk` or `python` one-liner would have to guess at that shape, and a guessed external
format believed forever by whatever reads it next is the same failure mode this project already
treats an invented QID as. Prose states what the reshaped file must look like — the seed tool's own three-column header is the one fact this repository *can* assert
— and leaves the mechanism to whatever the owner already has open.

### Why the "review rows carry no qid" clause is dropped, not carried forward

The old step 1 ended with "the review rows, which carry no qid of their own, are passed over,"
paraphrasing `support.QidList`'s own class javadoc. Reading `seed.Adjudicator.decide`, that is not
reliably true: two of the three `REVIEW` branches return `closest.qid()` or `named.get(0).qid()`
— a real, non-null qid — and only the pure `UNRESOLVED` case (`Decision`'s own javadoc: "the
identifier, or null when nothing was found at all") returns none. `Decision`'s class javadoc says
as much directly: "A `REVIEW` decision still carries a `qid` whenever there was a plausible one."

This does not make step 1 wrong, because step 1 never feeds `QidList` the review file — `seed.
SeedRun.run` writes only `Outcome.ACCEPTED` rows to the mapping and everything else to the
review file, two separate files, and every step of this chapter from 2 onward reads the mapping
alone. The clause was true of a scenario (`QidList` reading the review file) this chapter never
puts in front of it, so dropping it costs the new text nothing. **This is a discrepancy between
`support.QidList`'s own class javadoc and `seed.Adjudicator`'s actual returns, in production code
neither this issue nor its "Not this issue" list mentions.** It is reported here rather than
fixed: fixing a class javadoc in `support` is outside a chapter rewrite in
`docs/developer-guide.md`, and nothing this issue asks for depends on it being fixed.

### Whether a `resolveNames` fence needs an entry anywhere

**Decision: no.** `arch.GuideExamples.of` and `.inChapter` are always called with one Gradle task
name, and every existing caller names `graphCensus`, `expandPromotions`, `listRatings`,
`retractEntity`, `ownClaim` or `evaluate` — `grep -rn "GuideExamples.of(\|GuideExamples.inChapter("
src/test` finds no caller naming `resolveNames`, and `GuideExamples`'s own "mention" rule keys on
the task name appearing as a whole word on the line, so a `./gradlew resolveNames …` line is
invisible to every fence that scans for `expandPromotions`, `graphCensus` or `rate` — including
`DeveloperGuideExpandPromotionsExamplesTest.shouldRunEveryStepInOrderWhenTheChapterIsRead`, whose
`steps()` helper collects only those three tasks. The new example line therefore needs no matching
test entry anywhere, and adding one that named `resolveNames` for the first time would be new
coverage this issue does not ask for.

## No ADR changes

**ADR 40** already is the decision that `resolveNames` resolves a `name,kind,status` list to a
mapping and a review file, on its own run, and never merges two runs' outputs — nothing about that
decision changes; the chapter was describing the decision wrongly, not describing a decision that
changed. **ADR 66's 2026-09-15 amendment for #328** already is the decision that `--known --add`
reads a mapping the seed tool wrote and expands what the graph lacks — it says nothing about how
that mapping came to exist, and this issue does not touch that either. Neither ADR is amended.

## Verification, and the honest exception

**No behaviour changes, so no test is written for behaviour.** What verifies the two paragraph
edits:

- `DocumentationLinksTest` — the one new relative link (`[Bulk seeding](#bulk-seeding)`) resolves
  to a heading in the same file; the two links carried over unedited (`ADR 40`, `ADR 33`) are
  proof only that the guard was reading this region at all, via a planted control (see below).
- `DeveloperGuideExpandPromotionsExamplesTest` — the chapter is present, the seventeen `./gradlew`
  examples (`graphCensus`, `expandPromotions`, `rate`) stay the exact same sequence in the exact
  same order, because neither edit touches one of those three tasks' command lines; every
  `expandPromotions` example still parses through `ExpandCli.parse`; no tilde stands where `$HOME`
  belongs; nothing becomes unreadable.
- `DeveloperGuideEnumerationsTest` — run as a control that nothing in the two edited paragraphs
  touches a table, a diagram or a count this class derives from the tree; neither paragraph is
  inside one of those sections, so this class is expected unmoved.
- `docs` is a declared input of the `test` task (`build.gradle.kts:150`), so the edit re-runs the
  suite; the plan proves each per-task run **ran** rather than printed `UP-TO-DATE`, without
  `--rerun-tasks`, and only the final gate before commit adds `--rerun-tasks`.

**Positive controls, one per guard relied on**, each planted, watched red, quoted, then removed:

- `DeveloperGuideExpandPromotionsExamplesTest`'s order check: temporarily delete the chapter's
  existing `./gradlew rate --args="--known $HOME/rejected.csv --db $HOME/.segue/segue.db"` line
  and watch `shouldRunEveryStepInOrderWhenTheChapterIsRead` fail on a `containsExactly` diff
  missing the trailing `rate --known` — the same control the #326 precedent plan used on the
  `--second-hop` variant's own last line, for the same reason: it is the one guard in this
  repository that reads this chapter's *substance*, and it is also the control that neither edit
  moved a command.
- `DocumentationLinksTest`: temporarily add a broken link
  (`[ADR 999](adr/0999-does-not-exist.md)`) next to where the real `[Bulk seeding](#bulk-seeding)`
  link lands, and watch `shouldResolveEveryTargetWhenTheDocumentationLinksAreFollowed` fail naming
  `developer-guide.md` and the missing file.

## Premise corrections

1. **The issue's own account of the run (#330) is accurate against the code and is what this
   design follows**, not a correction — `seed.SeedRow`'s javadoc ("status is carried through
   untouched… REJECTED means 'does not tour'"), `seed.SeedRun.run` (mapping gets only
   `Outcome.ACCEPTED` rows; review gets `REVIEW` and `UNRESOLVED`), and `support.QidList`'s id
   rule (any field that is exactly a qid, `status` unread) all confirm it.
2. **The "review rows carry no qid" clause the old step 1 carried forward from `QidList`'s own
   class javadoc does not hold against `seed.Adjudicator.decide`**, which returns a real qid on
   two of its three `REVIEW` branches. See *Why the "review rows carry no qid" clause is dropped*
   above. Reported, not fixed — the production javadoc it lives in is untouched by this issue.

## Not this design

Sub-projects 2(b) and 2(c), and any change to the seed tool's kinds or columns — the issue's own
"Not this issue" rules both out, and nothing measured here argues for either. Also not this
design: fixing `support.QidList`'s class javadoc against `seed.Adjudicator`'s actual `REVIEW`
returns (see *Premise corrections* above) — reported for a separate pass, not folded into a
developer-guide chapter rewrite.
