# The harness reads the held-out split by rating age

Issue #276. Written 2026-09-06 against `276-ready` (HEAD = `main`). Everything below was read from
the code in this worktree; no real database was opened, and every identifier named here is invented
and carries ADR 58's leading zero.

## What the reading is for

ADR 45's 2026-09-06 amendment for issue #272 recorded that the shipped setting's hit rate fell by
about two thirds of the margin after a deck session, and said outright that the table cannot say
why. Two explanations survive it: the deck deals what the same ranking already passed over, so each
session promotes entities the top twenty-five cannot reach by construction; or the taste layer
widened into territory the routes serve less well. Reading the held-out population in two halves —
promotions rated before an instant, and promotions rated on or after it — separates them: under the
first the new half's rate sits near zero while the old half holds, under the second the halves land
near each other. This work builds the instrument. **It takes no reading**, and it moves no constant.

## Where the issue's premise is looser than the code

The issue is right about the shape and about the fence. Four things are worth stating exactly.

- **`find` also carries the timestamp, and the note with it.** The issue says "the only read that
  carries `updatedAt` is `readAll()`". True of the *bulk* reads; `AffinityStore.find` returns a whole
  `AffinityRecord` too. It changes nothing here — `theEvaluationHarnessReadsRatingsAndNeverNotes`
  already bans `find` and `readAll` alike in `evaluate` — but "the only read" is not the sentence the
  code supports, and the ADR amendment below says "the only bulk read".
- **There is no shared `AffinityStore` contract test.** `port/GraphStoreContract` exists for the
  graph; the affinity port has no such class, and its contract is tested in
  `sqlite/SqliteAffinityStoreTest`. "Contract-tested in the store contract" therefore means: tested
  there, beside `readsEveryScoreAndNoNotes`, in the same style.
- **Four test doubles implement the port**, in `ratings`, `census`, `export` and `rate`. Adding a
  method to `AffinityStore` breaks all four at compile time. Each gets the method in the same step
  as the port, throwing the way that fake already refuses the reads its tool must not make.
- **The port read is not covered by any existing fence.** `onlyTheRecommenderReadsEveryRating` names
  `readRatings`, `onlyTheRatingsToolReadsEveryRating` names `readAll`. A new method is reachable from
  everywhere, including `mcp`, until a rule says otherwise — and a `Map<String, Instant>` over the
  whole table enumerates every qid the owner has rated, which is exactly the bulk enumeration ADR 39
  refused the model. So this design adds a fence the issue does not ask for. See "The fence the issue
  did not ask for" below; it is the one place this spec goes beyond the settled decisions, and it
  goes in the restricting direction.

## What changes, in one sentence each

1. `AffinityStore.readUpdatedAt()` returns qid to `Instant` and nothing else, implemented in
   `SqliteAffinityStore` from the `updated_at` column, contract-tested in `SqliteAffinityStoreTest`.
2. A new ArchUnit rule, `onlyTheEvaluationHarnessReadsWhenARatingChanged`, keeps that read inside
   `evaluate`.
3. `Equivalences.resolveUpdatedAt` collapses the timestamps through the merges by the rule
   `Equivalences.resolve` already applies to the ratings, so the two maps are keyed alike.
4. `RatingAge` (new, in `evaluate`) is the instant and the set of qids rated on or after it — a pure
   value built once per run, which refuses loudly if a rated entity has no timestamp.
5. `Halves` (new, in `evaluate`) is the four cells one reading contributes, plus the flag that says
   whether a split was asked for at all. `Reading` carries one.
6. `Scoring.read` fills them in the two passes it already makes; no pass is added.
7. `EvaluationReport` appends four columns and inserts one split line when an instant was given, and
   renders **byte-identically to today** when none was.
8. `EvaluateCli` takes `--rated-since <ISO-8601 instant>`, optional, and refuses a malformed one with
   a usage error.
9. ADR 65 gets a dated amendment. The developer guide's "Calibrating the recommender" chapter gains
   the flag, and its ArchUnit table gains the new rule's row.

The grid, the interval, the fold count, the eligible population, #245's rule, and every constant
`recommend` ships with are untouched. No line of `recommend`'s output moves.

## Where the split lives, and why not in `HeldOut`

**A small pure value, `RatingAge`, and `HeldOut.every`'s signature does not move.** The alternative
was to give `HeldOut` the map of instants and a predicate, so the split hands back its two halves.
Four reasons it lost, and the first is the one the dispatch asks for:

- **`HeldOut.every` has four call sites in `HeldOutTest` and one in `EvaluateRun`, and ADR 65 quotes
  it as "the authority on the interval and on the split".** The age question is not a question about
  the split: fold `k` and fold `k + 1` disagree about which entities are hidden and agree exactly
  about which entities are old. A parameter that is constant across every call of a per-fold function
  belongs outside it.
- **`HeldOut` already carries a qid list and a qid-keyed map**, and ADR 65's type-level fence turns on
  keeping that type away from the report. Adding a second qid-keyed map deepens the type the fence
  exists to hold back.
- **`HeldOut` exposes the eligible population only as a count**, not as a list, so a half-aware
  `HeldOut` would have to grow accessors for each half of the eligible population as well. It does
  not need to: the folds partition the eligible population, so summing each fold's held-out entities
  by half over the run **is** the eligible population's two halves. That is the same argument the
  header already makes when it prints "held out over all folds" beside "eligible" and lets a reader
  see the two agree.
- The eligibility rule reads the rating and the known-list. Reading a timestamp there would make the
  *population* depend on the instant, which is not what is wanted: the population is the same, and it
  is the reporting of it that divides.

`RatingAge` is `(Instant since, Set<String> newer)`, built by
`RatingAge.of(since, updatedAt, ratedQids)`. `newer` is derived once, over the whole resolved ratings
keyset, before the first sweep. `isNew(qid)` is a set lookup.

**On or after the instant is new; before it is old.** `!instant.isBefore(since)`. An entity whose
timestamp is exactly the instant is new, and a test pins the boundary rather than leaving it to be
inferred from an implementation.

**A rated entity with no timestamp is refused loudly, not defaulted to old.** The two bulk reads are
two `SELECT`s over one table through one connection, so their keysets are the same in every ordinary
run; a disagreement means the resolution or the store is wrong, and silently reporting the entity as
old would hide it forever in a cell that looks plausible. The message names no qid and no count —
"how much the owner has rated" is itself a fact about him, which is why `SqliteAffinityStore.readAll`
already refuses to put a count in an exception. The refusal happens once, when `RatingAge` is built,
before the eighty sweeps rather than in the middle of them.

## How `Scoring` counts per half without a second sweep

`Scoring.read` makes two passes today: one over `sweep.candidates()` to build `shipped` (a stream
filter) and count `heldOutInPool` (a second stream over `shipped`), and one over `shippedTop` in
`ranksOf` to collect the hit ranks. **The half is a property of the held-out entity**, so each pass
can tally it where it already tests membership:

- The pool pass builds `shipped`, counts `heldOutInPool`, and increments `oldInPool` or `newInPool`
  in the same `heldOut.contains(qid)` branch. This *removes* a pass rather than adding one: the two
  streams over the candidate list become one loop.
- The hit pass walks `shippedTop` once, collecting the ranks and incrementing `oldHits` or `newHits`
  in the same `heldOut.contains(qid)` branch.

The negatives keep the reading they have — `ranksOf(withheldTop, negatives)`, unsplit — because a
rated-down entity is never held out and the question the halves ask is about promotions. Nothing is
ranked twice and no sweep is repeated: the run costs exactly what it costs today.

## How `Reading` carries the four cells

**A small record with a flag, `Halves`, and `Reading` gains one component.**
`record Halves(boolean split, int oldInPool, int oldHits, int newInPool, int newHits)`, with
`Halves.UNSPLIT` for a run that was given no instant.

- **A flag is unavoidable**, whichever shape is chosen: a run with no instant and a run whose halves
  happen to be all zeroes must render differently — four columns or none — and four zeroes cannot say
  which. What a record buys over four loose `int`s and a `boolean` on `Reading` is that the flag and
  the counts cannot drift apart: the compact constructor refuses a non-zero count on an unsplit
  value, and `plus` refuses to add a split value to an unsplit one. Both are guards, and both get a
  planted control.
- **Plain `int`s, no `OptionalInt`.** Every one of the four is a count and renders as an integer,
  which keeps `EvaluationReport`'s contract — every cell an integer, a fixed one-decimal or the dash
  — exactly as it is. No new cell can ever be the dash, because none of them is a mean.
- **They total over folds like their whole-population siblings.** `oldHits`/`newHits` and
  `oldInPool`/`newInPool` are entity counts, because the folds partition the held-out set;
  `Reading.summed` adds them through `Halves.plus`, and `oldHits + newHits == hits` and
  `oldInPool + newInPool == heldOutInPool` hold in every summed row. That identity is what a reader
  checks the table with, so it is asserted rather than assumed.

## The report

**Four columns, appended:** `old in pool`, `old hits`, `new in pool`, `new hits`, after
`neg mean rank`. Appended rather than interleaved so the eight columns on every reading already
recorded keep their positions and their widths — a split row's first eight columns render byte for
byte as the same row would unsplit, which is asserted directly.

**One split line, inserted after the existing one:**

```
# split by rating age at 2026-09-06T15:00:00Z: 7 old (rated before it), 3 new (rated on or after it) — a rating's timestamp is its last write, so a re-rated old promotion counts as new.
```

It names the instant, both half sizes, and the known limit in one clause. It is present only when an
instant was given.

**The instant is rendered from the parsed `Instant`, never from the string the operator typed.** That
is what keeps the block safe to paste: `Instant.toString()` can only produce digits, `-`, `:`, `.`,
`T` and `Z`, so `EvaluationIsSafeToPasteTest`'s `\bQ\d+\b` cannot match it however the flag was
spelled on the command line. The guard does not choke on the `T` or the `Z` — there is no `Q` in an
instant at all — and the control that proves the guard still works is a qid planted into the clause,
watched firing.

**`EvaluationReport.lines` gains three parameters** — `Optional<Instant> since`, `int oldHeldOut`,
`int newHeldOut` — and the type-level fence ADR 65 records is untouched: two are `int`s, the third is
an `Instant`, and there is still nowhere in the signature to put an identifier. `RatingAge` is
deliberately **not** passed, because it carries a qid set.

**One consistency guard, two directions.** An instant with an unsplit reading, or a split reading
with no instant, is refused: one run is split or it is not, and a report assembled from two views of
one run is how a table stops meaning what it says. Both directions get a planted control.

**The absent case is pinned byte for byte.** Before the renderer is touched at all, a golden test
renders today's block from an invented fixture and asserts every line as a literal. It is a guard, so
its evidence is a planted change to the renderer watched failing it. After the flag lands, the same
literals are asserted through the new signature with `Optional.empty()` — which is the character-for-
character proof that every reading on record stays comparable.

## The command line

`--rated-since <ISO-8601 instant>`, optional, parsed with `Instant.parse`. A malformed value is a
usage error naming the flag and the shape wanted, thrown by `parse` before any store is opened, and
tested. `Options` gains `Optional<Instant> ratedSince`.

**The timestamps are read only when the flag is given.** `EvaluateCli` calls `readUpdatedAt` inside
the `Optional.map` — a default run reads no timestamps at all, which is data minimisation (ADR 16)
falling out of the shape rather than being argued for.

**No `--rated-before`, no range, and no default instant.** YAGNI: the reading the issue describes
needs one boundary, and a default would silently split every run by a date nobody typed.

## The fence the issue did not ask for

`onlyTheEvaluationHarnessReadsWhenARatingChanged` bans `AffinityStore.readUpdatedAt` outside
`evaluate`. ADR 39 refused a bulk taste read on the surface because it is the single call that puts
the whole taste layer in front of a model; the values here are timestamps rather than scores, but the
**keys are every qid the owner has rated**, so the enumeration is the same one. A new method with no
rule on it is reachable from `mcp` the moment somebody writes the line, and `ToolSurfaceTest` counts
tools rather than fields.

It is a **new rule rather than a widening**, for ADR 63's reason that ADR 65 restates: a rule named
for one tool and quoted in an immutable ADR does not get quietly stretched to cover a second. It
lands in the same commit as the port method — there is no window in which the read exists unfenced —
and in the same commit as its row in the developer guide's table, because
`DeveloperGuideEnumerationsTest` compares that table against the declared rules exactly.

## What ADR 65 says now, and what the amendment changes

ADR 65 rejected this in as many words: *"Read `AffinityStore.readAll`, so the report could break the
split down by note or by recency. Genuinely more informative, and the port already offers it.
Refused: `readAll` carries the note, `onlyTheRatingsToolReadsANote` is where that line lives, and
nothing the harness reports needs anything but the score. Widening a fence for a column nobody asked
for is how the line stops meaning anything."*

Every clause of that refusal survives. `readAll` is still not read by the harness; the note fence is
exactly where it was; and the widening it refused — the harness reaching `readAll` — is refused
again. What changed is the premise in its last clause: a reading has now asked for the column, and
the port answers it with a read that carries the timestamp and nothing else, so no fence moves at
all. The amendment records that, records the new rule, and records the alternatives rejected.

**The known limit goes in the amendment as well as in the header.** `updatedAt` is the last write, so
a promotion rated long ago and re-rated after the instant lands in the new half. The census's `taste`
deltas bound how many ratings changed, not how many are new, so the amendment says the halves are an
*observation* and not a measurement of new promotions.

**ADR 45 is not touched.** The fifth reading — taken after the owner's next deck session, judged by
#245's rule unchanged on the whole-population cells, with the halves recorded as the observation that
settles #272's question — is a follow-up issue, and taking a reading here would be taking it against
the instrument built to justify it.

## Alternatives rejected

- **Widen the harness to `readAll`.** One line, no new port method, and the recency is already in
  the record it returns. Refused for ADR 65's own reason, unchanged: `readAll` carries the note, and
  `theEvaluationHarnessReadsRatingsAndNeverNotes` is where that line lives. The planted control in
  this work is exactly that line being written and the fence firing.
- **Tag ratings with their source** (deck candidate, deck known card, `note_affinity`), which
  separates the two explanations exactly rather than by proxy. Refused here because it is a schema
  change to `affinity`, and ADR 42 says the next schema change gets a real migration path. It is the
  issue's own "not this issue", and it is filed only if the age split cannot settle the question.
- **Make the split mandatory, with a default instant.** Simpler code, one rendering path. Refused:
  every reading on record was taken with no split, and a default boundary nobody typed would break
  the row-for-row diff that is the whole value of the instrument.
- **A fold column, or one row per half.** Thirty-two rows of half-sized counts, and the whole-
  population cells #245's rule reads would have to be re-derived by the reader. Refused for the
  reason ADR 65's fold amendment refused eighty rows: a less legible table saying less.
- **Put the halves in `HeldOut`.** Covered above.
- **Derive the halves from a second sweep, or a second rank.** Refused outright: the run already
  costs five sweeps per setting, and the half is a property of the entity that the passes already
  walking it can tally for nothing.
- **Let `RatingAge` treat a missing timestamp as old.** Refused: a lenient read feeding a guard turns
  "cannot tell" into "old", and the cell that results looks exactly like a real one.

## Verification

Every behaviour above is unit-testable and every step is RED before it is GREEN. Three things are
guards rather than behaviours and are proved by a planted control watched firing: the byte-identical
golden block, the two consistency guards on `lines` and `Halves`, and the two ArchUnit fences — the
existing note fence (plant `readAll` in the harness) and the new timestamp fence (plant a call from
`census`). The documents in the last task have no unit test; their verification method is the full
gate over an otherwise unchanged tree — `AdrIndexTest`, `AdrCitationsTest`, `DocumentationLinksTest`,
`DeveloperGuideEnumerationsTest`, `DeveloperGuideEvaluateExamplesTest` and `javadoc -Werror` — and
that is said out loud rather than left implied.

No wall-clock assertion is added anywhere. Every identifier in every fixture is invented and carries
ADR 58's leading zero, and no real database is opened at any point.
