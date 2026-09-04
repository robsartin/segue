---
status: Accepted
date: "2026-09-04"
topic: an-offline-evaluation-harness-for-the-recommender
tags: [project, tooling, privacy, data, graph]
supersedes: []
related: [recommend-by-normalised-lift-with-routes, suppress-a-candidate-you-have-rejected, a-high-rating-counts-as-something-you-have, the-floor-reports-itself, a-read-only-census-of-the-graph, what-an-adr-may-quote, taste-layer-separation, mcp-tool-surface, the-claim-tools-require-an-explicit-database, layering-and-archunit, privacy-and-data-handling]
---
# 65. An offline evaluation harness for the recommender: a deterministic held-out slice, a fixed grid, and aggregates only

## Context

Every knob the ranking turns is a one-significant-figure judgement, and each was recorded as one.
[ADR 45](0045-recommend-by-normalised-lift-with-routes.md) chose the scorer and the degree floor,
and its 2026-08-29 amendment moved the floor by running two of them and reading the two lists.
`RecommendationWeights` says outright that only the *order* of its weights is measured.
`Recommendations.NEUTRAL_RATING` centres the regard function on a rating nobody argued for
numerically. [ADR 48](0048-a-high-rating-counts-as-something-you-have.md)'s promotion threshold
says in its own text that it is a judgement rather than a measurement, and
[ADR 50](0050-suppress-a-candidate-you-have-rejected.md)'s suppression boundary is one step below
it because the step below that is exactly neutral. [ADR 31](0031-path-ranking-by-confidence.md)'s hub
threshold is the same shape.

ADR 45 also says why none of them can be tuned: **there is no held-out set of recommendations
anybody has agreed with.** Every alternative that ADR weighed ended at the same wall — the ranking
could be changed, and nothing could say whether the change was an improvement.

The taste layer now holds such a set. `graphCensus`
([ADR 63](0063-a-read-only-census-of-the-graph.md)) counted it; that count is a reading of the
owner's database and lives in the census output rather than here. What it means is that there are
now entities the owner has judged, at a strength, which the recommender did not put in front of
him — and a ranking can be asked where it would have put them back.

That set cannot leave the machine. [ADR 33](0033-taste-layer-separation.md) makes taste personal
data, [ADR 16](0016-privacy-and-data-handling.md) is the rule, and issue #37 corrected the belief
that a closed repository was the protection: the protection is that the data lives under the
owner's home directory and never enters git. So the instrument has to run on his machine, and
whatever it prints has to be publishable on [ADR 51](0051-what-an-adr-may-quote.md)'s terms.

**The issue's premise named works, and the code refuses them.** Issue #239 asked to hold out a slice
of *rated works*; `CandidateSweep.couldBeExplored` will not offer a candidate that is not a `PERSON`
or a `GROUP`, and will not offer one stating a recognition-institution class either — a record, a
prize or a city is a fact about a connection rather than something to listen to next. A held-out set
of works would have measured nothing, and the run would have looked healthy while measuring it. Two
further corrections are the same shape, and both were read off the code rather than assumed: only a
*promotion* can be held out at all (below), and the ratings must be resolved through the merges
before anything downstream sees them, because `RecommendCli` does exactly that and a harness that
did not would split a map the tool it is measuring never sees. **This ADR is written against the
code, not against the issue.**

## Decision

**A ninth dev-side tool, `./gradlew evaluate --args="--db <segue.db> --known <known.csv>"`, hides a
deterministic slice of what the owner has rated highly, runs the shipped candidate sweep from what
is left over a fixed grid, and prints one block of aggregates.** It changes no constant and no line
of `recommend`'s output. Six things are fixed, and each is fixed for a reason.

### The eligible population, and why only a promotion can be held out

`KnownList.promoted` composes the known-list as the `--known` file **plus** everything rated at or
above `KnownList.PROMOTION_RATING` that the file does not already name. Withdraw a rating from an
entity the file names and the file puts it straight back — the sweep was never blind to it, and a
hit against it would be a hit against something that was never hidden. So the eligible population is
exactly *rated at or above the promotion threshold, absent from the file, and offerable as a
candidate*.

The third condition is asked of `CandidateSweep.couldBeExplored` itself rather than restated in
`evaluate`. That method becomes `public` for this, which is precisely what happened to
`PathRanking.isHub` when the recommender needed routing's hub judgement (ADR 45): one
implementation, two readings, and never a second copy of the sentence. Nothing about it changes.

### The split: every *n*-th eligible entity by qid order

Sort the eligible population ascending by qid, index from zero, and hold out every entity whose
index is a multiple of `HeldOut.EVERY`. `HeldOut` is the authority on the interval and on the split;
this document does not restate it.

No randomness, no seed, no clock. Two runs over one unchanged database hold out the same entities
and produce byte-identical output — the contract [ADR 43](0043-listing-your-own-ratings.md) gives
the ratings listing and `Recommendations.rank` gives the recommender's tiebreak.

### One map, handed to all three consumers

The harness computes the resolved ratings map **with the held-out entries removed entirely**, and
hands that one map to the known-list composition, to the regard function and to the suppressed set —
exactly as `RecommendCli` hands one map to `regardFor` and `KnownList.promoted`. A held-out entity
is therefore absent from the known-list, absent from what is not offered, and weightless: the graph
the sweep walks is the graph it would have walked before those ratings were written. It is not
separately subtracted from anything, because it was never added; that is a property of the
eligibility rule above, and a test pins it rather than a comment.

### One sweep per setting, with suppression withheld

`CandidateSweep.over` is called **once** per setting, with only the merges excluded — suppression
withheld — so the entities the owner rated down are in the pool and can be ranked. Both readings
come out of that one sweep: the negatives are read over the whole pool, which is the ranking the
owner would have been shown had ADR 50 never been written; the held-out positives are read over the
pool **with the suppressed candidates filtered out**, which reproduces the shipped ranking.

That second half is a claim, and it has a licence. Excluding a candidate from the pool is **purely
subtractive**: `CandidateSweep.over` skips an excluded qid before it accumulates any evidence, and
no surviving candidate's evidence is built from it, so no score and no relative order changes — the
list backfills from the tail. ADR 50 measured exactly that on the real graph. Filtering after the
sweep is therefore identical to suppressing before it. **The equivalence is pinned by a test against
a real second sweep** (`SuppressionIsPurelySubtractiveTest`) rather than left as reasoning: without
that control the claim is the sort of thing that stays true until somebody makes the pool interact
with itself.

The replay happens once and one `CandidateSweep` is reused across the grid, so the memoised degrees
are paid for once too.

### The grid is fixed, and is not on the command line

Every scorer against every floor, in `Scorer` declaration order and ascending by floor. `Setting.GRID`
is the authority on the grid and `Setting.FLOORS` on the floors; neither is restated here, and a
scorer added to the enum joins the grid without an edit.

It is chosen rather than offered as flags because the value of the tool is **one comparable block a
person reads in one sitting**; a `--scorer` or a `--min-degree` would produce a stack of runs nobody
could line up beside each other. Each floor earns its place: the lowest is the point below which a
normalised score stops meaning anything — `RecommendCli` refuses a smaller `--min-degree` for that
reason; the next is what the recommender ships with, `Recommendations.MIN_CANDIDATE_DEGREE`, because
a grid that could not reproduce today's default could not say what changing it costs; the highest is
the floor ADR 50 took its measurements against, before ADR 45's 2026-08-29 amendment lowered it; and
one sits between those last two so the trend between them is read rather than inferred.

`--db` and `--known` are required and `--top` defaults to `RecommendCli.DEFAULT_TOP` **by reference**
rather than as a second copy.

### The output contract: every value an integer, a fixed decimal, or a literal

`EvaluationReport` is the authority on the columns and their order; this document does not list them,
because a list here would be a second copy going stale on its own — ADR 63's reason, unchanged. What
this ADR fixes is the *shape*: every value the report renders is an integer, a fixed one-decimal, or
a literal in that class, and every label is a literal there or a `Scorer` spelling. A mean over
nothing renders as the dash rather than as zero, because no hits and a mean rank of zero are
different facts. One decimal rather than a whole number because the point of the block is comparing
its rows.

**`EvaluationReport.lines` takes two plain counts, not the `HeldOut`.** This is the type-level
fence convention, and it is a deliberate narrowing of what the plan first drafted: the method needs
the split's denominator and how many were held out, and a `HeldOut` carries a qid list and a
qid-keyed map — somewhere to put a qid, even where the method never reads one. It is the same move
that lets the recommender hold the taste layer at all: `readRatings` returns a note-free map, so the
note is structurally unreachable rather than merely unread (issue #85). A signature that cannot
carry an identifier is a stronger guarantee than a body that does not print one, and it is the
guarantee that survives the next person editing the body.

### Placement, and the fences that widen for it

A new package `evaluate` with a `JavaExec` task, a sibling of `census` rather than a mode of
`recommend`. `--db` is **required, and `SEGUE_DB` does not satisfy it** — not
[ADR 60](0060-the-claim-tools-require-an-explicit-database.md)'s consequence, since nothing here
writes, but ADR 60's central clause reached from ADR 63's direction: an agent's shell is initialised
from the owner's profile and inherits the variable, and this output is a reading of the owner's
whole taste layer that gets pasted into an issue and quoted in an ADR. A wrong export is discarded;
a wrong measurement becomes the record.

`evaluate → recommend` is the **third** permitted dependency between two dev tools, after
`rate → recommend` ([ADR 46](0046-the-rating-deck.md)) and `census → export` (ADR 63), and it is
deliberate for the reason both of those were: the harness must measure *the shipped sweep*. A
harness with a walk of its own would answer a question about itself. `theRecommenderOpensNothingElse`
keeps the trip one-way from the moment `evaluate` joins `DEV_TOOL_PACKAGES`.

The fences `ArchitectureTest` declares for the package are written — read-only, opens nothing else,
reads ratings and never notes, and the two halves of the `--db` rule — each with a planted control,
and each with its row in the developer guide's table. They are new rules rather than widenings of
`census`'s, for ADR 63's own reason: a rule named for one tool and quoted in an immutable ADR does
not get quietly stretched to cover a second.

One existing rule *is* widened rather than copied: `onlyTheRecommenderReadsEveryRating` names
`evaluate` as a fourth reader of `AffinityStore.readRatings`, and this paragraph is that decision —
ADR 63 established that widening the note-free bulk read's readership is an ADR-level act, and it
is widened here for the tool that splits and weights by the score and reports neither. **`readRatings`,
never `readAll`**: the map is qid-to-integer and has nowhere to put a note.

**That widening had to land in the same commit as the CLI**, and the reason is worth recording
because it is the shape of every coupled change here: `PackageListsTest` holds
`DEV_TOOL_PACKAGES` against the source tree in both directions, so the package cannot exist without
the constant naming it; and the moment `EvaluateCli` calls `readRatings`, the un-widened rule reds.
Landing either half alone puts the build through a red it cannot be argued out of, which is what
[ADR 4](0004-mikado-method-for-changes.md) asks a coupled change to avoid. The fences
`ArchitectureTest` declares for the harness's own package followed in their own commit, after it.

## Alternatives considered

- **An MCP tool.** The conversational shape the rest of this project reaches for first. Refused
  three times over: [ADR 26](0026-mcp-tool-surface.md) pins the surface at six tools and holds back
  `assert_edge` until corroboration works, ADR 33 keeps the taste layer off that surface altogether,
  [ADR 39](0039-affinity-capture-and-read.md) already declined a bulk taste read on ADR 16's
  data-minimisation grounds, and ADR 43 reserved the bulk read to a tool on the owner's own machine.
  A tool whose *input* is every rating the owner holds is the exact shape all four refused.

- **A random split with a recorded seed.** The textbook answer, and it would give a fresh split per
  run. Refused because it is reproducible only against a number somebody remembers to write down,
  and [ADR 57](0057-the-floor-reports-itself.md)'s whole finding is that a reading nobody can
  re-derive stops being re-derived — which is why that ADR made the floor report itself rather than
  trusting a procedure. Deterministic-by-qid needs nothing remembered.

- **Two sweeps per setting, one suppressed and one not.** Obviously correct, and it needs no
  argument about subtractivity. Refused because it is twice the run for a number the subtractivity
  of pool exclusion already gives — and ADR 45's consequences record what one recommendation run
  costs. The argument that replaces the second sweep is not left as reasoning: a test runs the
  second sweep and asserts the two agree.

- **Tune a constant in the same issue, now that there is something to tune it against.** The whole
  point of building the instrument, and the temptation is real. Refused: the first reading would
  have been taken against the thing it was used to justify, and nothing afterwards could separate
  the measurement from the change. The harness exists so that a *later* issue can move one of those
  numbers against a number instead of a judgement.

- **Read `AffinityStore.readAll`, so the report could break the split down by note or by recency.**
  Genuinely more informative, and the port already offers it. Refused: `readAll` carries the note,
  `onlyTheRatingsToolReadsANote` is where that line lives, and nothing the harness reports needs
  anything but the score. Widening a fence for a column nobody asked for is how the line stops
  meaning anything.

- **Hold out a slice of the `--known` file instead of the ratings.** A far larger population, and
  no eligibility rule to get wrong. Refused because ADR 40's file is a concert history and carries
  no strength on any row (ADR 48), so a hit against it measures *reach* — whether the graph connects
  to the entity at all — rather than agreement. The ratings are the thing the owner actually judged.

- **A harness with a candidate sweep of its own**, avoiding the third dev-tool dependency entirely.
  Refused outright, and it is the alternative with a defect already on the record in this
  repository: two folds of one log drifted, which is what `BothFoldsAgreeTest` and the move of
  `Retractions.survives` into `domain` were for. A second sweep would put that drift in the one
  artefact whose whole purpose is to say whether the first sweep is any good — it would answer a
  question about itself.

## Consequences

- **The number is comparative, and it is not an absolute.** No row of the table means anything on
  its own; the whole grid beside itself means a great deal. Nothing here establishes what a good
  hit rate is, and an ADR that quoted one row as a score would be inventing a scale.

- **The split's denominator depends on expansion coverage.** ADR 48 records that only a fraction of
  the graph's nodes are the subject of a stored edge; the census is the authority on that fraction
  today. So a held-out entity the graph cannot reach is a miss for a reason that has nothing to do
  with any knob this harness sweeps. Reading a low hit rate as a verdict on the scorer would be
  reading it as a verdict on ingest.

- **The grid grows when a scorer or a floor is added, and `Setting.GRID` is the authority.** A new
  `Scorer` constant joins every run with no edit here and no edit to the guide, which is the right
  direction; a floor is a deliberate edit to `Setting.FLOORS`, and the reason for each existing one
  is above.

- **"Safe to paste" is a claim about the report, and a failed run is not the report.** ADR 63's
  limit, restated here rather than left for a reader to find in another ADR: a refusal names the
  path it was given — the database's or the known-list's — and an exception out of the log decoder
  can put a malformed row's own text on screen — `CensusIsSafeToPasteTest`'s sibling captures
  Logback events, and a thrown exception is not one.

- **The guard's scope is narrower still, and the narrowing is honest rather than an oversight.**
  `EvaluationIsSafeToPasteTest` asserts the absence of a label, a note and anything qid-shaped; it
  **cannot see a leaked rating**, because a leaked rating is a bare digit and nothing distinguishes
  one from a floor, a hit count or a pool size the legitimate table prints on every row. A pattern
  narrow enough to catch the first and broad enough to survive the second does not exist, and a
  planted `rating 5` fired none of the assertions. What actually keeps a rating out is upstream of
  that test and is the type-level fence above: `EvaluationReport.lines` takes counts, and `Reading`
  carries aggregates. The test still plants a rating in its fixture, because the label and the note
  beside it must reach the log through the same read path a rating would.

- **This decision changes no constant, and it exists so that the next issue can.** Not the floor,
  not the scorer default, not the promotion threshold, not the suppression boundary, not the
  weights. The only edit to `recommend` is one method's visibility; no output line, no default and
  no ordering moves.

- **The harness has never been run against real ratings by anybody but the owner**, and this
  repository holds none. Every fixture here is invented (ADR 33, issue #37) and every stand-in
  identifier carries [ADR 58](0058-stand-in-identifiers-cannot-be-allocatable.md)'s leading zero.
  What the tool says about the owner's own taste layer is a reading he takes and chooses whether to
  publish — which is the whole reason `--db` is typed per invocation.
