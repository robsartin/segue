---
status: Accepted
date: "2026-08-29"
topic: suppress-a-candidate-you-have-rejected
tags: [project, tooling, domain, privacy]
supersedes: []
related: [a-high-rating-counts-as-something-you-have, the-rating-deck, recommend-by-normalised-lift-with-routes, affinity-capture-and-read, taste-layer-separation, bulk-seeding-as-a-dev-tool, listing-your-own-ratings, privacy-and-data-handling]
---
# 50. Suppress a candidate rated 2 or below, and keep it reachable through `--revise`

## Context

[ADR 48](0048-a-high-rating-counts-as-something-you-have.md) built the promotion half of issue #106
and declined the other half in one bullet:

> **Promotion only. Suppression is deliberately not built.** […] the reason is the distribution
> above: **2 ratings below neutral against 87 above.** A suppression rule would have shipped against
> two data points. […] The re-open condition is arithmetic: enough ratings below neutral to see a
> distribution rather than two points.

### The condition was met, and by a change in the instrument rather than a change of mind

Issue #118 found that `Recommendations.MIN_CANDIDATE_DEGREE` was filtering candidates on *how much
segue had fetched* rather than on how obscure they are, and issue #119 gave the rating deck its own
`--min-degree` so the floor-5 candidate list could be dealt instead of only argued about. One
177-card pass at that lower floor produced a distribution that did not exist when ADR 48 was
written:

| | the floor-5 pass | every rating before it |
| --- | --- | --- |
| below neutral | **72 of 177 — 41%** | 8 of 973 — 0.8% |

A fifty-fold change in the rate of rejection, from one change to which candidates get offered. The
`affinity` table read for this ADR on 2026-08-29, against a copy of the real database:

| rating | 1 | 2 | 3 | 4 | 5 | total |
| --- | --- | --- | --- | --- | --- | --- |
| count | 3 | 77 | 117 | 387 | 566 | **1,150** |

**80 ratings are below neutral, against 2 when ADR 48 declined this.** That is a distribution.

### And the signal was inert in both directions at once

`Recommendations.regardFor` weights known-list qids only, and ADR 48's promotion admits 4 and 5
only. So a 2 on a candidate weighted nothing, suppressed nothing, and could not be revisited — the
same candidate was offered again on the next run. **72 of the 80 sit on entities the `--known` file
does not name**; the other 8 are on it, and were already excluded from the candidate pool for being
known rather than for being rejected.

## Decision

- **An entity rated at or below `KnownList.SUPPRESSION_RATING` is excluded from the candidate
  pool.** `KnownList.suppressed` is a pure function of the note-free ratings map and is the
  authority on its own rule; this ADR does not restate the code. It sits in `domain` beside
  `promoted` for the reason that method is there — so `recommend` and `rate` cannot apply two
  different answers to the same question.

- **The suppressed set reaches the sweep as its own parameter, never unioned into the known-list.**
  `CandidateSweep.over` takes `known` and `suppressed` separately and tests both at the point the
  known-list check already was. Each of the two `.over(` call sites in `src/main` — `RecommendRun`
  and `RateRun` — passes `KnownList.suppressed(ratings)`, and neither needed a new read: both
  already held the ratings map for `promoted`.

- **`--revise` can still reach a suppressed entity.** `Deck.dealRevision` and `RateRun`'s
  reconsideration count both take their population from `KnownList.revisitable`, which is the
  composed known-list unioned with the suppressed set.

- **The boundary is 2 because 3 is exactly neutral**, not because 2 felt like the right place. That
  is a consequence of arithmetic already in `Recommendations`, not a fresh judgement — unlike ADR
  48's promotion threshold, which that ADR is careful to call a judgement.

## Why suppression rather than a negative weight

This is the decision's whole argument, and it is arithmetic.

`Recommendations.regardFor` centres the scale on `NEUTRAL_RATING`: an entity's weight is its rating
over that constant, so an unrated entity weighs 1.0 and a 3 weighs the same. **The lowest weight the
function can produce is 1/3, which is still positive.** Admitting a rejected entity to the
known-list to "weight it negatively" would therefore make it a *seed of the sweep* whose connections
multiply — and so boost — every candidate it reaches. The rejection would strengthen exactly what it
was meant to argue against.

Getting a real negative signal out of that seam means weights below zero, and that is not a tuning
change. Every downstream number in ADR 45 assumes a non-negative multiplier: `Scorer` sums per-hop
contributions and divides by the candidate's degree, and a sum containing negative terms can be
zero, or negative, for two entirely different reasons — no evidence, or cancelled evidence. The
ranking that comes out has no defined meaning, and neither does the "N of yours through M shared
intermediate(s)" line the tool prints beside every candidate. **Excluding the entity says "not this"
without touching that arithmetic at all**, which is why it was chosen over the shape the issue
listed first.

## Why 2 rather than 3

`Recommendations.NEUTRAL_RATING` is 3, and `regardFor` divides by it, so a 3 already scores
identically to no rating at all. **A 3 is not a rejection; it is the absence of one**, which is what
the deck records for "I have heard of this and that is all". Suppressing it would remove entities
the owner declined to judge rather than entities they judged.

The cost is measurable rather than hypothetical: the table read for this ADR holds **117 threes**,
against 80 ratings at or below 2. A boundary at 3 would have removed those 117 from every future
sweep, silently, on the strength of a keystroke that means "no opinion".

This is also the same argument ADR 48 used to reject a promotion threshold of 3, running the other
way, and it is worth noticing that the two thresholds are not symmetric: promotion at 4 leaves the
3s in the candidate pool, and suppression at 2 leaves them there too. **A 3 is the rating that
changes nothing anywhere**, by construction.

## Why the suppressed set is separate from the known-list

`CandidateSweep.over` returns `knownFound` and `knownMissing` — how many of the known-list are in
the graph and how many are not. Both are reported to the operator, and both describe the
known-list. Folding rejections into `known` would silently redefine what those two numbers count,
so a run's "N entity(ies) on your list" line would stop being about the list.

It would also be wrong in the sweep itself. The seed loop walks `knownSet` and does not consult
`suppressed`; a rejected entity is therefore excluded as a *candidate* and remains available as an
*intermediate*, which is right — "you know two things, and this connects them" is a fact about the
graph whatever the owner thinks of the connector.

## Why `--revise` still reaches a suppressed entity

**There is no un-rate.** `AffinityStore` has no delete (ADR 39), so the only way to withdraw a
rejection is to re-rate the entity to 3 or above. If suppression also made the entity undealable,
the rejection would be permanent and unappealable — and that is issue #109's trap recreated one
layer out. #109 was filed because the deck could not return to an entity once it held any rating;
ADR 48 delivered `--revise 4` and `--revise 5` and left `--revise 3` unable to reach a three off
the file. Shipping suppression without reachability would have added a third such population, and
the one with the strongest claim to being revisitable: a snap "no" is exactly the judgement most
worth being able to take back.

`KnownList.revisitable` exists so the walk and the count that precedes it cannot disagree about that
population. Its javadoc records why it is in `domain` rather than in either caller.

## Alternatives considered

- **Weight the rating negatively instead of excluding the entity.** The issue's own second shape.
  Rejected on the arithmetic above: the weighting seam's floor is positive, so this cannot be done
  by passing a smaller number, and doing it properly rewrites ADR 45's scoring into something with
  no defined reading. It also fails the plainer test — the owner said "no", and the honest response
  to "no" is to stop offering the thing, not to offer it slightly less.
- **Make the known-list "things I have an opinion about", with the sign carried separately.** The
  issue's third shape, and the most conceptually tidy: one population, one field. Rejected because
  it merges two questions the sweep asks separately — *is this already yours* and *have you
  rejected it* — and every consumer of the merged set would then need the sign to tell them apart.
  `knownFound`/`knownMissing`, `regardFor`'s seed weighting and `dealRevision`'s population each
  want a different subset, and the current shape gives each of them exactly one.
- **Suppress at 3, so every non-positive rating stops being offered.** Rejected on the 117 threes,
  and on `NEUTRAL_RATING` — argued above.
- **Suppress at 1 only, the unambiguous rejection.** Would have applied to 3 entities. The
  distribution that re-opened this decision is almost entirely 2s (77 of the 80), so a boundary at 1
  ignores the evidence that made the case. It also reads the scale wrongly: the deck offers five
  keys, and a 2 pressed deliberately is a rejection with a shade of politeness, not a near-miss.
- **Suppress, and remove the entity from the known-list as well when it is on the file.** Eight of
  the 80 are on the `--known` file, so they seed the sweep at weight 2/3 while their neighbours are
  scored. Rejected: the file means "acts I have seen live" (ADR 40), and having seen something is a
  fact about the past that a later low rating does not repeal. Suppression answers "should this be
  offered", not "did this happen".
- **A time-limited suppression, so a rejection expires.** Rejected as machinery ahead of a need: it
  would require a second timestamp dimension in a table ADR 39 deliberately keeps historyless, and
  `--revise` already provides the appeal path at the cost of one keystroke.
- **Do nothing again, and wait for more data.** ADR 48's position, and it was right when it was
  taken. The stated re-open condition was arithmetic and it has been met by a factor of forty; going
  on deferring would be treating "we deferred once" as the reason rather than the evidence.

## Consequences

### It moved the ranking, which five earlier levers on this data did not

Measured on 2026-08-29 by running `./gradlew recommend` against a **copy** of the real database at
the merge-base and at this branch's head, with the same real `--known` file and `--top 25`. The
original database was not opened; its modification time was checked before and after and is
unchanged.

The context for reading this is that most levers tried on this data have produced very little.
Weighting by 973 real ratings moved **one** entity in the top 25 against no ratings at all; a
further 164 ratings changed the ordering **not at all**; #109's revision pass produced **one** rank
swap; and 150 candidate expansions added **no** candidates whatever (#118). The exception before
this one is ADR 48's promotion, which did move the top 25 — by removing promoted entities from the
pool, and it says of itself that it reordered the pool that already existed and reached no new
territory. **This lever moved the ranking too, and at the floor the ratings were actually collected
at it moved it a great deal.**

At the **default floor of 12**:

| | before | after |
| --- | --- | --- |
| candidate pool | 1,027 | 1,011 |
| top 25 unchanged | — | 18 of 25 |

- **16 entities left the candidate pool** — the suppressed entities that were in it.
- **7 of the previous top 25 are gone, and every one of the seven was suppressed.** Not one
  departure was a displacement. Each of the seven was rated 2.
- **They included ranks 1 and 2, and four of the top 10.** The two best recommendations segue had
  were both things the owner had already turned down.
- **7 genuinely new entities entered**, backfilled from the tail.
- **The 18 survivors kept their relative order exactly**, and none kept its absolute rank, because
  the two departures at the head shifted everything below them up.

At **floor 5**, which is where the 177-card pass that re-opened this decision was actually dealt:

| | before | after |
| --- | --- | --- |
| candidate pool | 1,676 | 1,604 |
| top 25 unchanged | — | 9 of 25 |

- **72 entities left the candidate pool** — every suppressed entity not on the `--known` file, so at
  this floor the whole suppressed population was live.
- **16 of the previous top 25 are gone, and every one of the sixteen was suppressed** (one rated 1,
  fifteen rated 2). **8 of the top 10**, including rank 1.
- **16 genuinely new entities entered.** Survivor order again unchanged.

**The effect is purely subtractive, and that is worth stating as a property rather than an
observation.** No score changed; the survivors' relative order is byte-identical on both runs. The
list simply loses its rejected members and backfills from below. That follows from where the check
sits — at the candidate end of the sweep, not in the seed loop — and it means the size of the effect
is entirely a function of how many rejected entities were ranking highly, which at the default floor
was seven and at floor 5 was sixteen.

### The effect depends on the degree floor, and that is the honest limitation

**The default floor of 12 sees only 16 of the 72 off-list suppressed entities; floor 5 sees all
72.** The ratings that made this decision possible were collected at floor 5 (issue #119), so most
of the rejections the owner actually made are invisible to a default-floor run. Anyone reading the
floor-12 turnover as "the size of the effect" is reading the wrong number: it is the size of the
effect *on the population the default floor admits*.

### The interaction with #117 and #118 is a real limitation of this decision

Issue #118 records that `MIN_CANDIDATE_DEGREE` filters on in-graph degree, which is a measure of
what segue has fetched rather than of what exists; issue #117 records the same defect from the other
side, that expanding a candidate lowers its own score. Lowering the floor to 5 is what let the deck
deal the candidates that produced 72 rejections. **So the suppressed population is keyed on a
selection rule that #118 says is measuring ingest coverage.**

Stated plainly, because it is not a footnote: **suppressing an entity that was only ever offered
because segue had under-fetched it is a judgement made on incomplete information.** A thinly
ingested entity is presented with few routes and a small neighbourhood, which is exactly the card
most likely to read as "no"; the same entity, fully expanded, might have been a different card. The
rejection is stored permanently, the graph improves afterwards, and nothing re-opens the question.

Three things bound that, and none of them dissolve it:

- The rejection is a first-person judgement about an entity, not about a card. The owner is entitled
  to say no to a name they recognise regardless of how many edges segue holds.
- `--revise 1` and `--revise 2` reach every suppressed entity, so the judgement is appealable at one
  keystroke — deliberately, and see above.
- Suppression removes a candidate; it does not retract anything or touch the graph. Nothing about
  the world-fact layer is decided by it.

What is *not* bounded is that nobody is prompted to re-open the question when ingest improves.
Whether the answer is a decision on #117 and #118 that removes the ingest dependency from the
ranking, or a revision prompt keyed on "this entity has been expanded since you rated it", is left
open here — it belongs with those issues, and this ADR deliberately does not pre-empt them.

### Suppression does not un-know anything

Eight of the 80 suppressed entities are on the `--known` file. They stay on the known-list, they
still seed the sweep, and `regardFor` still weights them — at 2/3, because that is what the scale
says. Suppression only answers the candidate question. Anyone expecting a low rating to remove
something from "what I have" will not find that here, and the alternative above says why.

### The threes are still stranded, and now more visibly

`--revise 3` reaches a three only if the entity is on the composed known-list; a three is below the
promotion threshold and above the suppression one, so a three on an off-list entity is on neither
population `revisitable` unions. There are **111** such entities in the table read for this ADR.
ADR 48 recorded this for the 78 it measured then; the number has grown and the situation has not
changed. It is a real gap, it is the only one of issue #106's populations still unreachable, and it
wants its own issue rather than a rule smuggled in here.

### The measurement is a snapshot, not a fixture

Every figure above comes from one `--known` file and one moment's 1,150-rating state of one real
database, on a copy. It is not repeatable, and it is not in this repository: the ratings are
personal data ADR 33 keeps out of a public repo, and no entity is named here for the same reason.
The behaviour is demonstrated in the gate on invented ratings instead — `KnownListTest`,
`CandidateSweepTest`, `RecommendRunTest`, `RateRunTest` and `DeckTest` each carry an issue-#106
case, exactly as `AffinityWeightedRecommendationTest` demonstrates ADR 45's weighting. Re-running
against a later snapshot will name different entities; what should hold is the shape — a purely
subtractive effect whose size tracks the degree floor.
