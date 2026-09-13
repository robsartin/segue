# The known list's `never expanded` row is a floor — design

Issue #315. Written 2026-09-12 against the code on `main` after #311 (the known-list census) and
#313 (`expandPromotions --known` and the first run of it). Prose only: two dated ADR amendments,
two developer-guide corrections, and one sentence each in two javadoc comments. **No behaviour
changes, and no production code changes at all.**

## What was measured, and what it makes true

The first `--known` run and the census taken after it are both on issue #313. The run visited every
known-list entity `Expanded` said no row cites as an expansion's seed; Wikidata returned no
whitelisted claim for any of them; MusicBrainz corroborated a few edges the graph already held; no
node and no net edge was added, and the `known list` section printed after the run was byte-identical
to the reading on #311. No figure from either is restated here or in anything this issue commits —
both are cited as "the run on #313" and "the reading on #311".

Two things follow, and both are statements this repository has already half-written.

1. **The row is a floor.** Once every entity the row names has been visited, what is left in it is
   entities Wikidata states nothing about in the vocabulary segue registers. That is a property of
   the population, not work left undone, and the row does not fall to zero by design.

2. **A `--known` run is not self-limiting.** `domain.Expanded` reads a seed out of two Wikidata
   reference shapes and nothing else, so an expansion that ran and recorded no Wikidata assertion
   leaves nothing for the rule to see. The same entities are in the population on the next run and
   the run after that. The run is idempotent in the *graph* — the run on #313 added no node and no
   net edge — and is **not** a no-op on the *log*: an assertion restated is a row appended, which is
   how corroboration and freshness work (ADR 19).

Neither is new as a *mechanism*. ADR 57 already
recorded that a derived expansion flag "conflates *never expanded* with *expanded and found
nothing*", and that a flag which did not conflate them "would have to be recorded rather than
derived, which is a schema change to the assertion log". ADR 63's 2026-09-12 amendment for #311
named the same residual as one of a small family that all err the same way. What #313 adds is that
on this population the residual is not a margin: it is the whole of it.

## What changes

### ADR 63 — a dated amendment (2026-09-12, issue #315)

Records that the `known list` section's `never expanded` row is a floor of Wikidata-thin entities
once every entity it names has been visited, citing the run on #313 and restating no figure from
it; that the row is not a countdown to zero and nothing in the tool will make it one; and that
neither the row, the rule nor the section changes — `KnownListCensus` and `CensusReport` are
untouched by this issue, and what changes is what the runbook tells the owner to do with the number.

### ADR 66 — a dated amendment (2026-09-12, issue #315)

Records that a `--known` run is idempotent in the graph and not self-limiting, and why: the rule
reads Wikidata evidence, and an attempt that finds nothing is not evidence. Names the operator's
replacement for "run it until the row reads zero" — compare one dry run's `considered` against the
previous `--known` run's — and cites the guide as the authority on the procedure rather than
restating it.

**The declined alternative, with the reason it lost.** An "expansion attempted, found nothing"
claim in the log, so the rule could see an attempt and the population could empty. It loses on
price against what it buys: it is a seventh implementor of the sealed `LoggedAssertion`, which every
exhaustive switch over that interface in `src/main` would have to decide about — `Expanded.in`'s
own, `SqliteAssertionLog`'s codec and `LogProjection` among them — and a schema change to a log that
is never rewritten. ADR 42 is where that price is already recorded: it shipped one schema change
with no migration on an argument "about the data that happens to be there", and says in as many
words that the next schema change gets a real migration path. ADR 57 reached the same conclusion
from the other end. What it would buy is a row that reads zero instead of a floor — the same
reading, spelled so that it looks finished. The 2026-09-11 amendment on ADR 66 already declined a
marker of the same shape for the promotions. A later issue may reopen it with an argument this one
does not have: that re-visiting the thin population costs enough to be worth a schema change.

### The developer guide, two chapters

- **"Expanding every promotion", the `--known` variant.** The sentence that makes the census's row
  the thing that "says whether this run is worth making at all" is replaced: the row is the reading
  the run is measured against, not a number the run drives to zero. And a new paragraph after the
  dry run says when to stop — compare this dry run's `considered` against the previous `--known`
  run's; an unchanged count means the entities left are thin, and running again visits the same
  entities, calls the same public APIs and appends rows that move the graph nowhere.
- **"Looking at the shape of your graph", the `known list` section's description.** One paragraph
  saying the same about the row itself, and pointing at the runbook for the procedure.

The comparison the runbook names is the **dry run's `considered`** rather than the census's row,
because `considered` is the population the expander was handed. The two are the same reading over
slightly different populations, and the guide says so: `Preflight.considered` counts every file id
the rule did not exclude, including ids the projection holds no node for (which the run refuses as
unknown entities), while `KnownListCensus.Population.neverExpanded` counts only the ones in the
graph.

### Two javadoc sentences

- `domain.Expanded` — one sentence on the paragraph that already states its limits, saying that the
  residual is measured rather than hypothetical and is what makes a re-expansion pass reading this
  answer not self-limiting.
- `census.KnownListCensus.Population`'s `neverExpanded` `@param` — one sentence saying to read it as
  a floor rather than a queue that empties.

## Verification, and the honest exception

**No behaviour changes, so no test is written for behaviour.** That is the honest exception this
project allows for pure prose, and it is said out loud rather than left implied. What verifies the
documents is named instead:

- `AdrIndexTest` — every ADR file has exactly one index row agreeing on number, title and status; an
  amendment changes none of those, and this is what says so.
- `AdrCitationsTest` — no commit hash reaches `docs/adr` outside its allowlist.
- `DocumentationLinksTest` — every relative link in the new text resolves to a file and a heading.
- `DeveloperGuideExpandPromotionsExamplesTest` — the chapter is present, every `--args` line parses
  through `ExpandCli.parse`, no tilde stands where `$HOME` belongs, and **the chapter's `./gradlew`
  lines are exactly these nine in this order**. That last one is the guard that the prose edit moved
  no command, and the plan plants a defect to watch it fire.
- `DeveloperGuideCensusExamplesTest` — the same for the census chapter's `graphCensus` lines.
- `JavadocCitationsTest` and `javadoc -Werror` inside `./gradlew check` — the two javadoc edits.
- `docs` is a declared input of the `test` task (`build.gradle.kts`), so an edit under `docs/`
  re-runs the suite rather than leaving it `UP-TO-DATE`. The plan proves that per task, without
  `--rerun-tasks`.

## Premise corrections (2026-09-12, issue #315)

Read from the tree before the plan was written. Each names what the issue assumed, what is actually
there, and what the plan does instead.

1. **No sentence in the guide promises the row will read zero.** The issue's *Shape* asks for
   "any sentence that says the census's `never expanded` row should read zero after a run" to be
   replaced. There is none. `grep -n "zero\|should read" docs/developer-guide.md` returns ten hits,
   none of them about this row; `grep -n "never expanded"` returns two in the guide, one of which is
   the census chapter's "what it is for" bullet and is true as it stands. **The one sentence that
   needs correcting is weaker than the issue's description of it**: "Take the census first, with the
   same file — its `never expanded` row is what says whether this run is worth making at all". It
   promises no zero; what it does is make a single reading of the row the decision procedure, which
   is exactly what the run on #313 showed a single reading cannot be after the first run. The plan
   corrects that sentence, and adds the stopping rule the chapter has never had rather than
   replacing one it did.

2. **The same claim is restated inside a test's assertion message, and that is a second copy.**
   `DeveloperGuideExpandPromotionsExamplesTest.shouldRunEveryStepInOrderWhenTheChapterIsRead`'s
   `as(...)` text says "the census with the same flag comes first because the never expanded count
   it prints is what says whether the run is worth making at all". Left alone it would be the guide
   sentence this issue corrects, surviving under a different roof. The plan corrects it in the same
   task as the chapter.

3. **Nothing can red on the prose, and the plan says so rather than inventing a red.** No test in
   this repository reads guide *prose*; `DeveloperGuideEnumerationsTest` enumerates the ArchUnit
   table, the layering diagram, the dev-tool list, the package table and the stub-server table, and
   none of those is touched here. The guide's `./gradlew` enumeration *can* red, but only if a
   command moves — and no command moves in this issue. So each document task plants a defect,
   watches the guard that reads that document fire, and removes the plant: that is the positive
   control this repository requires, and it is what proves the guard is looking at the file at all.

4. **"Idempotent" is true of the graph and false of the log**, and the issue's phrase "it re-records
   nothing new" would be wrong if it were read as "it appends nothing". The run on #313 appended
   rows — MusicBrainz corroborated edges the graph already held. Both ADR amendments and the guide
   paragraph say "appends rows that move the graph nowhere" rather than "changes nothing", and cite
   ADR 19 for why a restated assertion is a row.

5. **`Expanded`'s javadoc already states the limit; what it lacks is that the limit was measured.**
   The class comment's fourth paragraph already says "an expansion that ran and returned nothing at
   all leaves no row either, and is indistinguishable here from one that never ran". So the new
   sentence adds the measurement and its consequence for a *re-run*, and does not restate the
   mechanism.

6. **ADR 57 is prior art for the declined alternative and is cited as such.** It records both the
   conflation and the "would have to be recorded rather than derived, which is a schema change to
   the assertion log" objection. The plan's ADR 66 amendment cites ADR 57 beside ADR 42 rather than
   presenting the argument as new.

## Not this design

The attempt marker itself. The isolated-but-expanded entities (the second-hop question). The three
wrongly-kinded file entries. Any change to `Expanded`'s rule, to `KnownListCensus`, to `CensusReport`
or to the expander.

## Correction (2026-09-12, during execution)

Two sentences above are wrong about the tree, and were written differently where they shipped.

- **The ADR 57 citation in *What was measured, and what it makes true* (and again in note 6) fuses
  two findings ADR 57 makes separately.** The conflation (ADR 57, line 168) is a property of the
  *derivable* flag. The "would have to be recorded rather than derived, which is a schema change to
  the assertion log" objection (lines 197–205) is about a flag that **spanned sources** — the
  MusicBrainz problem ADR 54 created — not about a flag that avoided the conflation. ADR 66's
  amendment for #315 cites the two findings separately, each on the flag ADR 57 attaches it to.
- **"`KnownListCensus` and `CensusReport` are untouched by this issue" is false.** Task 4 adds one
  javadoc sentence to `KnownListCensus`. The ADR 63 amendment says instead that both emit exactly
  what they emitted before and that the only `src/main` edits this issue makes are two javadoc
  sentences.
