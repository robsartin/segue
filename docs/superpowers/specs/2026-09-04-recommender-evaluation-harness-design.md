# An offline evaluation harness for the recommender

Issue #239. Written 2026-09-04 against `main` at `787ecdc`. Everything below was read from the code
in this worktree; no real database was opened, and every identifier named here is invented and
carries ADR 58's leading zero.

## The premise the issue states, and what the code actually does

The issue says "hold out a slice of **rated works**". **The recommender cannot recommend a work**,
so a held-out set of works would measure nothing. `CandidateSweep.couldBeExplored` refuses any
candidate that is not a `PERSON` or a `GROUP`, and refuses one stating a recognition-institution
class on top of that — "a record, a prize or a city is a fact about a connection rather than
something to listen to next". The held-out set is therefore **rated `PERSON`/`GROUP` entities**, and
the harness is designed against the code.

Three further corrections the issue's shape does not carry, each read off the code rather than
assumed:

- **Only a promotion can be held out.** `KnownList.promoted` composes the known-list as the
  `--known` file **plus** everything rated at or above `KnownList.PROMOTION_RATING` that the file
  does not already name. Withdrawing a rating from an entity **the file names** removes nothing: the
  file puts it straight back. So the eligible population is exactly *rated ≥ `PROMOTION_RATING`,
  absent from the file, and offerable as a candidate*. Anything else cannot be held out at all, and
  a harness that tried would report a hit rate over a set the sweep was never blind to.
- **Suppression is what hides the negative signal, and it is a candidate-pool exclusion rather than
  a weight.** `CandidateSweep.over` takes `KnownList.notOffered` — `suppressed` ∪ what merges have
  retired — as a separate parameter and drops those qids before the kind test and before the floor
  test. A rating of 1 or 2 is therefore invisible in a normal run by construction, which is exactly
  why measuring it needs the suppression withheld.
- **Ratings must be resolved through the merges first.** `RecommendCli` does
  `merges.resolve(affinity.readRatings())` before anything downstream sees the map, because a merge
  leaves two rows naming one thing. The harness reads the taste layer the same way or its split and
  its known-list disagree with the tool it is measuring.

## What is being measured

One question, asked sixteen times: **with a fifth of what the owner has rated highly hidden, where
does the ranking put it back?** And, beside it, the negative reading: **where would the ranking have
put the entities the owner rated down, if suppression were off?**

Neither number is meaningful in the absolute. Both are meaningful *across settings*, which is the
whole point — ADR 45's degree floor, ADR 45's scorer, ADR 50's suppression boundary and ADR 48's
promotion threshold are all one-significant-figure judgements, and ADR 45 says outright that there
is no held-out set anybody has agreed with. There is one now.

## The protocol

### The split — deterministic, and by qid order

The eligible population is every qid in the merge-resolved ratings map that is

1. rated at or above `KnownList.PROMOTION_RATING`,
2. absent from the `--known` file, and
3. offerable as a candidate — `CandidateSweep.couldBeExplored`, asked of the real graph.

Sort it ascending by qid, index from zero, and **hold out every entity whose index is a multiple of
`HeldOut.EVERY`**, which is 5. No randomness, no seed, no clock: two runs over one unchanged
database produce byte-identical output, which is the same contract ADR 43 gives the ratings listing
and `Recommendations.rank` gives the recommender's tiebreak. A random split would be reproducible
only against a recorded seed, and ADR 57's lesson is that a number nobody can re-derive stops being
re-derived.

Condition 3 is asked of `CandidateSweep.couldBeExplored` itself rather than reimplemented here.
That method becomes public for this, which is precisely what happened to `PathRanking.isHub` when
the recommender needed routing's hub judgement (ADR 45): one implementation, two readings, and never
a second copy of the sentence. Nothing about it changes.

### What "known" is during a held-out run

The harness computes `ratingsWithout` — the resolved ratings map with the held-out entries **removed
entirely** — and then hands that one map to all three consumers, exactly as `RecommendCli` hands one
map to `regardFor` and `KnownList.promoted`:

| consumer | what it gets |
| --- | --- |
| `KnownList.promoted(fromFile, ratingsWithout)` | the known-list: the file unchanged, plus the surviving promotions |
| `Recommendations.regardFor(ratingsWithout)` | the regard function, so a held-out entity carries no weight |
| `KnownList.suppressed(ratingsWithout)` | the negatives, unchanged — nothing rated ≤ 2 is ever held out |

A held-out entity is therefore absent from `known`, absent from `notOffered`, and weightless — the
graph the sweep walks is exactly the graph it would have walked before that rating was written. It
is not separately subtracted from the composed list, because it was never added to it; that is a
property of the eligibility rule above and is pinned by a test rather than by a comment.

### One sweep per setting, not two

`CandidateSweep.over` is called **once per setting**, with `notOffered` set to the merges alone —
suppression withheld — so the negatives are in the pool and can be ranked. Both readings come out of
that one sweep:

- **the negatives** are ranked over the whole pool, which is the ranking the owner would have been
  shown had ADR 50 never been written;
- **the held-out positives** are ranked over the pool **with the suppressed candidates filtered
  out**, which reproduces the shipped ranking exactly.

The second half is a claim and it needs its licence. Excluding a candidate from the pool is **purely
subtractive**: `CandidateSweep.over` skips an excluded qid before it accumulates any evidence, and
no other candidate's evidence is built from it, so no surviving candidate's score or relative order
changes — the list backfills from the tail. That is what ADR 50 measured on the real graph and what
the code says. Filtering after the sweep is therefore identical to suppressing before it, and one
sweep answers both questions instead of two. **A test pins the equivalence against a real second
sweep** rather than leaving it as reasoning: without that control the claim is exactly the sort of
thing that stays true until somebody makes the pool interact with itself.

Sixteen sweeps rather than thirty-two also halves the run, which matters: ADR 45's consequences
record the wall clock of one recommendation run against the real graph including its replay. The
replay happens **once** — the harness boots the graph a single time in `EvaluateCli` and reuses one
`CandidateSweep` instance across the grid, so its memoised degrees are paid for once too. Do not
re-project per setting.

### The grid — fixed, and not on the command line

Four scorers (`Scorer.values()`, in declaration order) × four floors, `{2, 5, 8, 12}`, ascending:
sixteen rows in one table. Chosen, not offered as flags, because the value of this tool is one
comparable block a person reads in one sitting — a flag would produce sixteen incomparable runs.
Each floor earns its place: **2** is the point below which a normalised score is meaningless
(`RecommendCli`'s own lower bound), **5** is the shipped `Recommendations.MIN_CANDIDATE_DEGREE`,
**12** is the floor ADR 50 measured against before ADR 45's 2026-08-29 amendment lowered it, and
**8** sits between the two so the trend between them is visible rather than inferred.

### The command line

`--db` **required**, and `SEGUE_DB` does not satisfy it — ADR 60's central clause, arriving at a
read from ADR 63's direction: an agent's shell is initialised from the owner's profile and inherits
the variable, and this output is a reading of the owner's whole taste layer that gets pasted into an
issue and quoted in an ADR. A wrong export is discarded; a wrong measurement becomes the record.
`--known` **required**, for the reason `recommend` requires it. `--top` optional, defaulting to
`RecommendCli.DEFAULT_TOP` by reference and never as a second copy. No `--out`, no `--scorer`, no
`--min-degree`: the grid is the tool.

## The output

Aggregates only, census style (ADR 63), through SLF4J at `info` — `nothingWritesToStandardOut` bans
`System.out` project-wide and there is nothing here a log line may not carry. Three header lines and
then one row per setting:

| column | what it is | shape |
| --- | --- | --- |
| `scorer` | `Scorer.spelling()` | a literal in the enum |
| `floor` | the setting's degree floor | integer |
| `pool` | candidates that cleared the floor, suppressed ones removed — the shipped pool | integer |
| `held out` | how many of the held-out entities are in that pool at all | integer |
| `hits` | how many of them are in the top *N* | integer |
| `mean rank` | the mean 1-based rank of those hits | one decimal, `Locale.ROOT`, or `-` |
| `negatives` | how many rated-down entities the unsuppressed ranking offers in the top *N* | integer |
| `neg mean rank` | the mean 1-based rank of those | one decimal, `Locale.ROOT`, or `-` |

**Every value is an integer, a fixed one-decimal, or the literal `-`; every label is a literal in
`EvaluationReport` or a `Scorer` spelling.** No qid, no label, no note, and no rating value appears
anywhere — not even the split's own thresholds are echoed from data. A mean over an empty set is
`-` rather than `0.0`, because zero hits and a mean rank of zero are different facts and a table
that renders them the same is a table that misleads. One decimal rather than an integer because the
whole point is comparing sixteen rows: a mean of 8 and a mean of 8.4 are a real difference at *N* =
25 and rounding them together would hide it.

The header lines carry the split — how many were eligible, how many were held out, how many are
still known — and the `--top` in force. Those are counts over the owner's data, which ADR 51 permits
and ADR 63 has already demonstrated.

Column widths are derived from the rendered cells, exactly as `CensusReport` derives them, so a
five-figure pool moves the column rather than jutting out of it.

### Why this one is safe to paste, mechanically

The same argument ADR 63 makes for the census, and it holds here for the same reason: there is no
framing to judge because there is no free text from the data, and nothing to look up because the
assertion is over the *shape* of the text. `EvaluationIsSafeToPasteTest` copies
`CensusIsSafeToPasteTest` — a fixture carrying a label, a note, a `Q` id inside that note and a
rating, every log line captured at `TRACE` so sqlite-jdbc's own statement logging is included, and
all four asserted absent along with anything matching `\bQ\d+\b`.

The limit is ADR 63's limit, unchanged and restated in ADR 65 rather than left for a reader to find:
the property is over **the report**, not over a failed run. A refusal names the path it was given and
an exception out of the log decoder can carry a malformed row's own text.

## Placement

A new dev-side package, `evaluate`, with a Gradle task to match `graphCensus`: `JavaExec`, 4 GB
heap, `--enable-native-access=ALL-UNNAMED`, never up-to-date.

It depends on `recommend` — the sweep, `Sweep`, `KnownList`'s composition through it, and
`RecommendCli.DEFAULT_TOP`. That is the **third** permitted dependency between dev tools, after
`rate → recommend` (ADR 46) and `census → export` (ADR 63), and it is deliberate for the same reason
both of those were: the harness must measure *the shipped sweep*, not a second implementation of it.
A harness with its own walk would answer a question about itself. The fences widen by hand and one
at a time, each with a planted control, because `ArchitectureTest.otherDevToolsAnd` is an allowlist
and the exception is the thing a reader has to justify.

`evaluate` reads `AffinityStore.readRatings` and nothing else from the taste layer:
`onlyTheRecommenderReadsEveryRating` widens to name it, and a new rule bans `AffinityRecord` as a
type and `find`/`readAll` as calls, exactly as `theRecommenderReadsRatingsAndNeverNotes` does for
`recommend`. It writes nothing at all.

## Pure core, thin shell

`HeldOut` (the split), `Setting` (the grid), `Scoring` (the per-setting metrics from a `Sweep`) and
`EvaluationReport` (the table) are pure functions, tested over invented fixtures — a hand-built list
of `Recommendation`s for the scoring, a `TinkerGraphStore` in the `InventedWorld` idiom where a
graph is needed. `EvaluateRun` composes them and takes a `Consumer<String>`, so the whole report is
observable from a test and the class has no logger to misuse (`CensusRun`'s discipline).
`EvaluateCli` is the only class that opens SQLite, and it is driven end to end from a `@TempDir` in
`AffinityWeightedRecommendationTest`'s pattern.

## What this issue does not do

- **No constant changes anywhere.** Not the floor, not the scorer default, not
  `PROMOTION_RATING`, not `SUPPRESSION_RATING`, not the weights. The harness exists so that a later
  issue can change one of them against a number instead of a judgement, and changing one in the
  same issue would mean the first reading was taken against the thing it was used to justify.
- **`recommend` behaves identically.** The only edit to that package is the visibility of one
  method; no output line, no default and no ordering moves.
- **Nothing goes on the MCP surface.** ADR 26 pins it at six, and a harness over the owner's whole
  taste layer is the last thing that should be conversational — ADR 33's line is that taste does not
  leave the machine, and this reads all of it.
- **Nobody here runs it against the real database.** The owner runs it and pastes the aggregates.

## Alternatives considered

- **An MCP tool.** Refused: ADR 26 pins the surface at six, and ADR 33 keeps the taste layer off it.
  A tool whose input is every rating the owner holds is the exact shape ADR 39 declined and ADR 43
  reserved to the owner's own machine.
- **A random split with a recorded seed.** Refused: reproducible only against a number somebody
  remembers to record, and ADR 57's whole finding is that a threshold nobody re-derives stops being
  re-derived. Deterministic-by-qid needs nothing remembered.
- **Two sweeps per setting, one suppressed and one not.** Refused: twice the run for a number the
  subtractivity of pool exclusion already gives, and the equivalence is pinned by a test rather than
  argued.
- **Tuning a constant in the same issue.** Refused; see above.
- **Reading `AffinityStore.readAll` so the report could break the split down by note or by
  recency.** Refused: `readAll` carries the note, `onlyTheRatingsToolReadsANote` is where that line
  lives, and nothing the harness reports needs anything but the score.
- **Holding out a slice of the `--known` file instead of the ratings.** Refused: the file is a
  concert history with no strength attached to any row (ADR 48), so a hit against it measures reach
  rather than agreement — and the issue's own premise is that the ratings are the held-out set
  because they are the thing the owner actually judged.
