# The rule for the recommender's second evaluation reading — design (#245)

Issue #245. Committed before the second reading is taken, so this rule is fixed before the number it
judges exists. The commit timestamp is the evidence, exactly as it was for the first rule.

This document does not restate the first rule and does not restate the first reading. The first rule
is `docs/superpowers/specs/2026-09-04-calibrate-one-constant-design.md`; the first reading, the
ruling that was made of it and the four observations that produced this issue are ADR 45's
2026-09-04 amendment for issue #242. **Both are cited as authorities and neither is copied.** No
figure from that amendment's table appears anywhere below.

## What is being decided

The same two constants the harness sweeps, and nothing else:

- the scorer default (`RecommendCli.parse`; `Setting.GRID` sweeps every `Scorer`);
- the degree floor (`Recommendations.MIN_CANDIDATE_DEGREE`; `Setting.FLOORS` is the authority on the
  floors swept).

Nothing else the ranking uses is swept, so nothing else may move.

## The evidence

One run of `./gradlew evaluate` (ADR 65) on the owner's database, pasted by the owner as the block it
prints: `EvaluationReport.HEADER`, two `#` lines, the column row, and one row per `Setting.GRID`
entry. Aggregates only; ADR 51 and ADR 65 permit quoting the whole block.

The held-out population is still small. `HeldOut.every` holds out one eligible entity in
`HeldOut.EVERY`, and eligibility is narrow — rated at or above `KnownList.PROMOTION_RATING`, absent
from the `--known` file, and offerable by `CandidateSweep.couldBeExplored`. The first reading's
header line names the eligible count and how many of those were held out, and its `in pool` column
names how many of the held-out entities each floor reaches at all; read them there. **The size to
expect is a few dozen held out, and fewer than that in pool at the higher floors.** Every threshold
below is derived from that size rather than chosen to look defensible.

## What the first reading showed that this rule has to answer

ADR 45's 2026-09-04 amendment records four observations. Three of them shape this rule; the fourth
(that the shipped scorer records no hits at the lowest floor in the grid) is the *reason* the
dominance repair below takes the shape it does. The amendment is the authority on all four and they
are not restated here.

## The rule

### 1. Every comparison is a rate, and the denominator is the row's own `in pool` cell

`hitRate(row) = hits / in pool`, taken from that row and no other. Never a hit **count**, and never
the held-out count from the header line — a held-out entity the graph cannot reach at that floor is
ingest's miss (ADR 65's consequence), and counting it would judge expansion coverage rather than the
setting.

**Where this actually bites, stated so the change is not oversold.** `in pool` does not depend on the
scorer. `CandidateSweep.over` decides membership from `minDegree`, the known-list and the `notOffered`
set; the `Scorer` enters only at `scorer.score(...)` when each surviving `Recommendation` is
constructed, and `Scoring.read` derives `pool` and `heldOutInPool` from that membership. So at one
floor every scorer's row carries the same `in pool`, and clause 2's comparison orders identically
whether it is read as counts or as rates. The change of unit is load-bearing at clause 4 and at the
dominance range in clause 3, where floors — and therefore denominators — differ. Stating one rule for
all of them is cheaper than stating two and remembering which applies.

### 2. The margin that replaces "three hits": fifteen percentage points of hit rate

A challenger displaces an incumbent only where `hitRate(challenger) − hitRate(incumbent) ≥ 0.15`.

**Why fifteen.** One held-out entity entering or leaving a top-N moves that row's hit rate by
`1 / in pool`. At every `in pool` cell the first reading carries, fifteen points is worth three or
more entities — the same granularity the first rule's three-hit bar asked for, now stated as a
difference that survives being compared across two rows whose denominators are not the same. **No
single entity can produce it at that size**, which is the entire property the bar exists for.

**The margin is void where a single entity could produce it, and that is arithmetic rather than a
second judgement.** `1 / in pool ≥ 0.15` exactly when `in pool ≤ 6`. If any row a clause compares
carries an `in pool` at or below six, one entity is worth the whole margin there, the bar has stopped
being a bar, and the rule refuses to rule: the outcome is that the shipped setting stands, recorded
with the reason named as the instrument rather than as evidence. A split that has shrunk that far is
a finding about the split, and the issue that widens it is not this one.

### 3. The scorer question, at the shipped floor, with a dominance check that can fail

A scorer displaces the shipped default only if **both** hold:

- **(a) Margin, at the shipped floor.** Its hit rate at `Recommendations.MIN_CANDIDATE_DEGREE`
  exceeds the shipped scorer's by at least the margin.
- **(b) Dominance, over the floors where the shipped scorer has hits.** The dominance range is the
  floors in `Setting.FLOORS`, other than the shipped one, at which the **shipped** scorer's `hits`
  cell is non-zero. At every floor in that range the challenger's hit rate must be strictly greater
  than the shipped scorer's.

Two things about (b) are deliberate:

- **A floor where the shipped scorer records no hits is excluded.** That is the repair: on the first
  reading the condition was free at such a floor, where every challenger cleared it without being
  better. A comparison against zero is not evidence that a scorer generalises.
- **An empty dominance range does not pass.** If the shipped scorer has hits at no floor but the
  shipped one, the check cannot be evaluated, and an unevaluable check is a failed one. The scorer
  does not move. This is the clause that stops the repair from turning "free at one floor" into "free
  everywhere".

Dominance asks only for strict improvement, not for the margin again. It is the shape test; (a) is
the size test. Requiring the margin at every floor would be a bar nothing in this grid could clear,
and a bar written so no reading can pass it is a decision not to measure.

**What this clause still cannot fix, said out loud.** If a challenger's `hits` cell does not move
across the grid, the floors in the dominance range are one comparison repeated rather than several
independent ones, and no wording here manufactures independence out of rows that do not move. The
ruling records the repetition where it occurs; the rule does not pretend it away.

### 4. The floor question, at the chosen scorer

A floor displaces `Recommendations.MIN_CANDIDATE_DEGREE` only if, at the scorer clause 3 chose (the
shipped one, unless clause 3 displaced it), its hit rate exceeds the shipped floor's row by at least
the margin.

A candidate floor **below** the shipped one must in addition be checked with one `recommend` run by
the owner: its `FloorReading` must sit inside ADR 57's trigger band, and ADR 57 is the authority on
that band and on the fact that the band is chosen rather than measured. That check **gates** a move
downward, as it did in the first rule.

For a candidate floor **above** the shipped one the same reading is taken and **reported, not
gated** — ADR 57 records a floor above the shipped one already sitting outside its own band on the
graph of the day, so gating upward on it would refuse a move on a reading nobody has argued is a
fault. Whichever direction the move goes, the reading is quoted in the amendment.

### 5. The negatives clause is dropped, and nothing replaces it as a gate

The first rule's "no more negatives in the top N" condition does not appear in this rule. Three
reasons, in the order they were checked:

- **Its source is contaminated, and the contamination favours a challenger.** ADR 45's 2026-09-04
  amendment is the authority: a low rating comes mostly from the deck (ADR 46), and the deck deals
  what `recommend` surfaces at the shipped setting, so the shipped setting's `negatives` cell partly
  measures a list the owner was offered and rejected while every other row's measures a list he was
  never shown.
- **It cannot be restricted to negatives rated before the deck existed, because the harness cannot
  date a rating.** `AffinityRecord.updatedAt` is *when the rating was last written* — ADR 39 keeps
  one row per entity and re-rating overwrites, and `rate --revise` re-rates by design — so it cannot
  say when an entity was *first* rated even if it were reachable. It is not reachable:
  `ArchitectureTest.theEvaluationHarnessReadsRatingsAndNeverNotes` makes `AffinityRecord` unnameable
  inside `..evaluate..` and forbids `AffinityStore.find` and `readAll`, and `readRatings` — the one
  bulk read ADR 65 widened to the harness, deliberately — is a `Map<String, Integer>` with nowhere to
  put a timestamp. And no first-deal date is on the record anywhere to restrict against: ADR 46's
  front matter dates the *decision*, and the nearest facts about the negatives' arrival are ADR 48's
  and ADR 50's own measurements, which are dated findings rather than a boundary a rule could apply.
  Restricting the clause therefore means widening a fence ADR 65 wrote on purpose and adding a field
  to `Reading`, which is this issue's "Not this issue".
- **Clause 1's repair cannot even be applied to that column.** `Reading` carries no count of how many
  rated-down entities are in the pool, and `EvaluationReport` prints no denominator for
  `negatives` — `pool` excludes them and `in pool` counts held-out entities. So the negatives column
  can only ever be a count, on the very axis clause 1 refuses.

**What replaces it is disclosure, not a gate.** The `negatives` and `neg mean rank` cells are read
and named in the ruling and quoted in the amendment, so a scorer that moves on hit rate while
carrying a visibly worse negatives column is a finding the owner sees before it ships. A gate on a
contaminated column that reads one way mechanically is worse than no gate, because it looks like
evidence.

### 6. At most one constant moves

If the scorer and a floor both clear their clauses, the scorer moves — it is the larger lever — and
the floor question is re-asked against the new default in a later issue. If neither clears, **the
shipped setting stands, and that is the recorded result**, not the absence of one.

### 7. Near misses stand

Fourteen points is not fifteen. A better `mean rank` with no rate improvement is not an improvement.
A dominance range that is empty is not a dominance check that passed. An `in pool` at or below six
voids the margin rather than lowering it. The rule has no second clause for "but it looks better".

### 8. The protocol, unchanged from the first rule

The rule is committed **before** the reading, and its commit is named in whatever amendment records
the outcome. The **owner** runs `evaluate` and any `recommend` reading clause 4 needs; nothing else
does. The outcome is recorded as a dated amendment to ADR 45 (a scorer move, or a stand) or ADR 57 (a
floor move), quoting the table verbatim exactly once and restating no figure from it in prose.

## What happens for each outcome

- **A scorer moves.** The default that `RecommendCli.parse` applies changes, and its existing pin in
  `RecommendCliTest` is the red. Issue #244 is landing a single shared constant for that default,
  read by `RecommendCli` and the deck by reference; where it has merged, this is a one-line change to
  that constant and the deck follows. Where it has not, the move also has to carry the second copies
  #244 exists to remove, and the plan says how. ADR 45 gains the dated amendment.
- **A floor moves.** `Recommendations.MIN_CANDIDATE_DEGREE` changes to a value `Setting.FLOORS`
  already holds; the by-reference consumers follow, and the full gate is what proves they do. ADR 57
  gains the dated amendment, carrying the `FloorReading` clause 4 required.
- **The shipped setting stands.** No code changes. ADR 45 gains a dated amendment recording the
  second reading, this rule, and what did not clear it.

## Alternatives rejected

- **Keep counts and add a second clause for cross-floor comparisons.** Two units in one rule, and a
  reader has to know which clause is in which. Rates everywhere costs one sentence explaining that
  clause 2 is unaffected, which this document spends.
- **Express the margin as a number of entities rather than a rate.** That is the first rule's bar
  again, and it is the thing the first reading showed does not survive rows whose denominators
  differ.
- **Scale the margin to each reading's own split** (three entities' worth at the larger denominator,
  say). It adapts, and it is also a rule whose threshold nobody can state before seeing the number —
  which is the property this whole exercise exists to avoid. A fixed fifteen points with an arithmetic
  void condition is stateable in advance and checkable afterwards.
- **Restrict the negatives clause by rating date.** Refused on the code, above: the timestamp is a
  last-write, it is unreachable from `evaluate`, and no first-deal date exists to compare it against.
- **Weight the negatives clause instead of dropping it** — count a negative at the shipped setting for
  less than one elsewhere. There is no measured weight to use, and inventing one puts a made-up number
  inside the gate that is supposed to be the honest part.
- **Drop the dominance check altogether**, since it was free once. It was free at one floor for a
  reason the amendment names; excluding that floor is the repair, and removing the check would leave
  a scorer moving on a single row, which is what dominance was added to prevent.
- **Widen the grid, enlarge the split, or add a metric first.** ADR 65 fixed the grid deliberately and
  this issue's own text excludes all three. This rule judges with the instrument as built, and clause
  2's void condition is what stops it from ruling when that instrument has become too blunt.
- **Move both constants at once.** Two moves against one reading cannot be attributed.

## Verification

Nothing here is unit-testable until the outcome is known, and that is said out loud rather than left
implied. The rule is verified by its commit preceding the reading; a scorer move by
`RecommendCliTest`'s pin going red then green; a floor move by the full gate over every by-reference
consumer plus the owner's `FloorReading`; the amendment by `AdrIndexTest`, `DocumentationLinksTest`
and the guide's derived checks inside `./gradlew check`.
