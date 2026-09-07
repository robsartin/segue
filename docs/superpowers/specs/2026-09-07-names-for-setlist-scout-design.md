# Design: `listRatings` writes the promotions Setlist Scout does not track yet (issue #285)

Branch `285-ready`, off `main` at `4d5d53f`. Plan:
`docs/superpowers/plans/2026-09-07-names-for-setlist-scout.md`.

## The problem

The recommender's known-list has two halves. One is the `--known` file, produced from a concert
history and therefore meaning "acts I have seen live". The other is the **promotions** — everything
rated at or above `KnownList.PROMOTION_RATING` that the file does not name — which
[ADR 48](../../adr/0048-a-high-rating-counts-as-something-you-have.md) added because the first half
omits everything liked but never attended.

That second half is a list of acts the owner would go and see and that nothing on the owner's side
tracks. Setlist Scout accepts a plain-text upload of artist names, one per line. Nothing in segue
produces that file.

## What is being added

One flag pair on the existing tool:

```
./gradlew listRatings --args="--promotions-off $HOME/filtered-qids.csv --names $HOME/promotions.txt"
```

`--promotions-off <known.csv>` names the known-list file; `--names <out.txt>` names the file to
write. One line per promotion, the label the graph holds, sorted by that label, preceded by a
one-line `#` header saying what the file is and how many names follow. `--out` is unchanged, and is
now optional when the names export is asked for.

## Where the promotion set comes from

**Through `KnownList.promoted`, minus the file's own qids.** Not a smaller predicate written here.

`KnownList.promoted(fromFile, ratings)` returns *the file's entities, in the file's order, followed
by everything rated at or above the threshold that the file does not already name*. The promotions
are therefore exactly `promoted(...)` with the file's qids removed, and taking them that way is what
makes it impossible for this tool to disagree with `recommend` and `evaluate` about who is promoted.
The alternative — `rating >= KnownList.PROMOTION_RATING && !onFile` written in `ratings` — is the
same rule stated a second time, and ADR 48's own argument for putting the composition in `domain`
is that a second statement of it diverges the moment somebody adds a clause to one copy. This
repository has already paid for that shape once (issue #109, quoted in `KnownList.revisitable`'s
javadoc).

The subtraction is by set (`new LinkedHashSet<>(fromFile)`), not by taking the tail from index
`fromFile.size()`. The tail is what `promoted` builds today, but a positional read would silently
become wrong if `promoted` ever de-duplicated its first half; a set difference asks the question the
name of the method answers.

The ratings handed to `promoted` are **resolved through the merges first** —
`Equivalences.resolve` — exactly as `RecommendCli` and `EvaluateCli` do before they compose their
known-lists. Without it, a merged local id and its canonical id are two rows naming one thing, and
both are promoted: the owner's one opinion becomes two names in the upload.

### The fold this tool may use

`Equivalences.in(logged)`, not `replay.fold().equivalences()`. `recommend` gets its merges back from
a replay it performs anyway; this tool performs no replay and must not start one —
`theRatingsToolOpensNothingElse` bans `ingest` and `tinker` precisely so it cannot. The merges-only
form answers `resolve`, `merged` and `canonical` identically to the folding form; that is pinned by
`EquivalencesTest.shouldAnswerAsTheMergesDoWhenTheFoldingFormIsAskedTheToolsQuestions`.

## Where the labels come from

`Labels.forQids`, which is what `RatingsRun` already joins for the listing's `label` column: the
last surviving `NodeAssertion` or `LocalEntity` claim in the log, with a merge's stand-in carry
folded in. Reusing it is the whole point — a second label rule would be a second answer to "what
does the graph call this".

**One correction the issue does not mention.** `RatingsRun` today asks `Labels.forQids` for the
qids that have a *stored* rating row. A promotion need not be one of those: where a local id was
rated and merged onto a canonical id that has no row of its own, `Equivalences.resolve` moves the
rating onto the canonical id, and *that* is the promotion. Asking for the stored qids alone would
leave it unlabelled and write a bare qid for an entity the graph can perfectly well name. So in the
names path the label lookup is asked for the **union** of the stored qids and the promotion qids.
This has a test of its own; without it the defect is silent, because a bare qid on a line looks
exactly like the honest fallback below.

## A promotion the graph cannot name

**Written with its qid, and counted in a log note.** Not skipped.

The two candidates were "write the qid" and "skip it and report the count". Both report; only one
keeps the promotion in the file. The file is an upload of things to go and see, and a promotion
dropped out of it is a thing the owner said yes to that the upload does not contain — recoverable
only by re-deriving the set by hand. A qid on a line is visible, searchable, wrong in an obvious way
rather than a quiet way, and it is the same fallback ADR 43 already chose for the listing's own
`(not in the graph)` column: *honest rather than helpful*.

`(not in the graph)` itself is **not** used here. That string is for a person reading a table; this
file is pasted into a search box, and a line reading `(not in the graph)` would be searched for as
an artist. The qid carries the same information and identifies the row.

ADR 39 requires an entity to be in the graph before it can be rated, so this should be empty. The
count is noted so that "should be empty" is checked on every run rather than assumed.

## The file

```
# segue promotions off your known list — personal data under ADR 33 and issue #37. Keep this file outside the working tree and out of version control: this repository is public. 3 name(s), one per line.
A Placeholder Novel
An Ensemble Nobody Booked
Q0900003
```

- **One header line**, carrying both the personal-data sentence and the count, in the same shape
  and for the same reason as `RatingsTable.PERSONAL_DATA_HEADER`: a file copied, pasted or attached
  somewhere else still says what it is. `*.txt` is already gitignored (ADR 43), so this is the third
  lock, not the first.
- **Labels and nothing else.** No qid column, no rating, no note. The upload wants names; a second
  column would make this a different kind of file, and a note in it would be free text leaving the
  machine.
- **Sorted by the written name, then by qid.** `String.compareTo` — code-point order, not a
  `Collator`: `SortOrder`'s comparators end in `qid` so that two runs over an unchanged table
  produce byte-identical files, and the same argument applies here. A case-insensitive order was
  considered and rejected: it is not total on its own, so it needs the code-point comparison as a
  second tiebreak anyway, and the audience for this file is an uploader rather than a reader.
- **No default path**, for ADR 43's reason: a tool that picks a path for you is a tool that quietly
  writes personal data into the repository.

### The cost of the header, stated

The `#` line is a comment by convention and by nothing else. Whether Setlist Scout's uploader
ignores a leading `#` is not known on this side, and this issue explicitly ends at segue's edge. The
runbook paragraph therefore says to drop the first line if it comes back as an artist nobody has
heard of. The alternative — no header — was rejected: the third lock is worth more than one
line of paste discipline, and a file of names with no provenance is exactly the file that gets
attached to an issue.

**2026-09-07 — the runbook paragraph above was superseded during implementation, and this
paragraph was not, which is the defect.** Task 3's implementer checked the far side rather than
leaving it unknown: Setlist Scout's bulk uploader (`ArtistImportService`, `ArtistSeedService`) skips
lines beginning with `#`, issue #177 there. The runbook (`docs/developer-guide.md`) and
`NamesFile`'s javadoc were corrected on this branch to say the upload ignores the header rather than
telling the owner to strip it first — dropping it is no longer necessary and the paragraph above
must not be read as current instruction. This note records the resolution rather than rewriting the
paragraph it corrects, the way an ADR would; a design document gets a dated amendment for the same
reason.

## The command line

| flag | before | after |
| --- | --- | --- |
| `--out` | required | required **unless** `--names` is given |
| `--sort` | optional, default `rating` | unchanged |
| `--db` | optional, `DefaultDatabase.resolve` | unchanged |
| `--promotions-off` | — | required **with** `--names`, refused without it |
| `--names` | — | required **with** `--promotions-off`, refused without it |

Three usage errors, each naming the flag that is missing:

- `--names` without `--promotions-off`;
- `--promotions-off` without `--names`;
- neither `--out` nor `--names` (the existing `--out is required` message, unchanged).

The pairing is enforced in `Options`' compact constructor as well as in `parse`, so the record
cannot be built in a state the tool has no behaviour for. `parse` is what produces the readable
message.

`--promotions-off` is **not** checked for existence at parse time, matching `RecommendCli` and
`RateCli`: `QidList.read` throws `no entity list at <path>` when it is missing and `no QID in
<path>` when it holds none, and those are the messages the operator should see. The known file is
read **before either output file is written**, so a bad path fails before anything lands on disk.

`QidList` reads it exactly as `recommend` and `evaluate` do: the first comma-separated field on a
line that is exactly a `Q\d+`. A `filtered-qids.csv` header row
(`name,kind,status,qid,label,confidence,reason`) contains no such field and is skipped without any
header handling.

## What reads the log, and how often

`RatingsRun` needs two things out of the log in the names path — the labels and the merges — and
today `Labels.forQids` takes an `AssertionLog` and calls `readAll()` itself. Folding the merges
separately would therefore read a quarter of a million assertions twice.

So a prerequisite refactor, landed first and green on its own: **`Labels.forQids` takes the
already-read log** (`List<LoggedAssertion>`), and `RatingsRun` owns the "nothing rated, so do not
read the log at all" skip that `Labels` owns today. `LabelsProbe` keeps its signature by calling
`log.readAll()` itself, so `StandInAgreesInEveryHomeTest` is untouched. One read per run, in every
path. This is the principle ADR 63's census fence states out loud — *"so that there is one fold of
the log rather than two"* — reaching the tool that had the tightest fence and the sloppiest read.

**What is deliberately not done:** the `Equivalences.in(logged)` fold still happens twice in the
names path, once inside `Labels.forQids` and once in `RatingsRun`. That is a linear pass over a list
already in memory, not a second query; threading an `Equivalences` through `Labels` would change a
signature that three test files reach for no measured gain. Said here rather than left for a
reviewer to notice.

## Fences, and what does not change

- **`theRatingsToolOnlyReads` is unchanged and still holds.** Nothing added here writes: `QidList`
  reads, `KnownList` and `Equivalences` are pure, and the only new I/O is a `Writer` on a path the
  owner named. The plan runs the rule's positive control — plant an `AffinityStore.put` inside
  `ratings`, watch the rule fire, remove it — rather than asserting the fence still covers a tool
  that grew.
- **`theRatingsToolOpensNothingElse` is unchanged.** `KnownList` and `Equivalences` are `domain`;
  `QidList` is `support`. Both are already on the tool's allowed list, and the developer guide's
  package table (`port`, `domain`, `sqlite`, `support`) needs no edit.
- **`onlyTheRatingsToolReadsEveryRating` and `onlyTheRatingsToolReadsANote` are unchanged.** The
  promotion set is derived from the `readAll()` this tool already makes; `AffinityStore.readRatings`
  is *not* called, and `InventedRatings.FakeAffinityStore` keeps throwing on it.
- **Counts alone in the log.** The names path adds two notes — how many names were written, and how
  many of them the graph could not name — and a `wrote <path>` line. No label, no qid, no note text.
  `RatingsAreNeverLoggedTest` gains a case that drives the real `main` down the names path.
- **Nothing to the console.** `nothingWritesToStandardOut` already forbids `System.out`
  project-wide; there is nothing to add.
- **`…IsSafeToPaste` does not apply.** `CensusIsSafeToPasteTest` and `EvaluationIsSafeToPasteTest`
  exist because those reports are *meant* to be pasted into a public issue. This file is personal
  data that is never pasted anywhere but a private upload form, so the property those tests assert
  is not one this file has or wants. The javadoc says so, so that nobody adds the test by analogy.

## ADRs

**No new ADR, and no amendment to ADR 43.** Checked against ADR 43's Decision text rather than
against the issue's assertion:

- The decision is scoped to *the tool*, not to *one output*: "A third dev-side tool, `ratings`, run
  as `./gradlew listRatings --args="--out …"`", the audience separation, the bulk read fenced to one
  package, `--out` without a default, two orderings, labels from the log, the two ArchUnit rules and
  the naming rule. Nothing in it says the tool writes one file, and every constraint it does state —
  owner-named path, no default, file rather than console, counts in the log, the file names itself —
  is honoured by the second output.
- The nearest thing to a prohibition is the rejected alternative **"A `--min-rating` filter, or a
  search"**, whose stated reason for losing is *"neither has a use yet … Speculative structure ahead
  of a real need"*. That is a YAGNI rejection, not a rule, and it is no longer true: ADR 48 has since
  defined the promotion, and issue #285 is the use. This is also not a filter of the listing — the
  listing is unchanged — but a second, differently-shaped enumeration of the same table.

So the tool's javadoc cites ADR 43 and ADR 48 and no ADR file is touched. If a reviewer disagrees,
the correction is a one-paragraph dated amendment to ADR 43 saying the enumeration may take a second
shape; it is not an edit to the decision text (ADRs are immutable).

**The user guide is not touched.** Its four `listRatings` mentions are all about reading a *note*
back on the owner's own machine, and none of them describes the tool's outputs.

## The invented fixture

Everything the tests use is made up, per ADR 43's closing consequence and `InventedRatings`' own
javadoc. Existing constants are reused where they fit; two are added:

| constant | id | why |
| --- | --- | --- |
| `QUARTET` | `Q0900001` | rated 5, absent from the file — the ordinary promotion |
| `NOVEL` | `Q0900002` | rated 3 — below `PROMOTION_RATING`, must not be promoted |
| `VANISHED` | `Q0900003` | rated 5, no claim in the log — the promotion with no label |
| `MINTED` | `Q00900042` | rated 5 and merged — must not be promoted under its own id |
| `CANONICAL` | `Q10000900042` | unrated, holds the merge's carried rating — the promotion |
| `SEEN_LIVE` (new) | `Q0900005` | rated 5 **and** named by the file — must not be promoted |
| `SEEN_LIVE_LABEL` (new) | "An Ensemble Nobody Booked" | a name of a thing that does not exist |

Plus a `knownFile(Path, String... qids)` helper writing the mapping file's real shape — the
`name,kind,status,qid,label,confidence,reason` header row and one row per qid — so the fixture
exercises `QidList`'s field rule rather than a bare list.

## Risks

- **The header line is uploaded verbatim if the operator pastes the whole file.** Mitigated by the
  runbook paragraph and by the `#`, and stated above rather than hidden.
- **`Options` grows from three fields to five and `out` becomes nullable.** Contained: the record's
  compact constructor carries the invariant, and the two call sites (`RatingsCli.main`,
  `RatingsRunTest`'s helper) are both in this change.
- **The label-union correction is invisible if untested.** It has a named test, and the plan writes
  the RED as a bare qid where a label belongs.
