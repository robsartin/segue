# Report the degree floor over the kinds the recommender can offer — Design

**Issue:** [#247](https://github.com/robsartin/segue/issues/247). **Date:** 2026-09-04.

## The problem, restated against the code

The degree floor is `Recommendations.MIN_CANDIDATE_DEGREE`, one number for every node kind. The
census reports the graph's degree distribution and how much of it sits at or below that number, and
the graph is dominated by `WORK` nodes the recommender never ranks. So the figure a reader compares
the floor against is mostly made of nodes the floor is never applied to.

## What the code says, where the issue left it open

The issue offers two homes for the fix — `FloorReading`, or the census's degree section — and the
code decides between them.

**`FloorReading` needs nothing, and this was checked rather than assumed.**
`CandidateSweep.couldBeExplored` returns `false` for every kind but `PERSON` and `GROUP`, and the
sweep asks it *before* it asks the degree question: the kind test is on the same line as the
already-known and not-offered tests, and `candidateDegree < minDegree` is the next statement, with
a comment saying the two are separated so that what the floor held out is countable apart from what
the kind rules refused. Every field `FloorReading` carries — `pool`, `poolMedianDegree`, `heldOut`,
`heldOutAtDegreeOne`, `head`, `headMedianDegree`, `headOnTheFloor`, `headEveryEdgeCounted` — is
therefore already a reading of the `PERSON`/`GROUP` population. There is no kind-blind number in it
to split.

**The kind-blind number is `DegreeCensus`.** `Degrees.in(projection)` puts every node in the map,
isolated ones at zero, and `DegreeCensus.of` reads its quantiles and its at-or-below-floor count
over all of them at once. That is exactly what ADR 63 set out to build — its context says the open
question is "where the floor sits against the *graph*" — and it is correct as a statement about the
graph. It is misleading as a statement about the floor.

**So the fix is a by-kind reading inside the census's degree section, and nothing else changes.**

## The design

`DegreeCensus` gains one component and one nested type.

- `Map<NodeKind, KindDegrees> byKind` — **all six kinds, zeros included**, in an `EnumMap` kept in
  declaration order. This is `NodeCensus`'s rule and its reason: a kind that has gone to zero is
  visible as a zero rather than as a missing row somebody has to notice, and `Map.copyOf`'s
  iteration order is unspecified and salted per JVM, which ADR 43's byte-identical contract forbids.
- `KindDegrees(p50, p90, p99, max, atOrBelowTheFloor, atOrBelowTheFloorPercent)` — the same five
  figures the whole graph reports, plus the share.

**One rule, read seven times.** A private `read(population, floor)` produces a `KindDegrees`, and
the whole-graph components on `DegreeCensus` are taken from `read` over every node's degree. The
quantile rule is the existing `quantile` helper, unchanged: ADR 55's nearest rank, so every quantile
is a degree some node actually has. Nothing here is a second copy of a rule the record already had.

**One fold.** `DegreeCensus.of` already takes the `LogProjection` that `Census.of` holds; `Degrees.in`
is called once and its map is both bucketed by kind and read whole. The log is not read again.

**The share is a whole percent, nearest, an exact half going up**, computed in integers as
`(200 * part + whole) / (2 * whole)`, with an empty population reading zero. No floating point
decides a boundary, and the value stays an integer — the property ADR 63's first decision clause
rests on and `CensusIsSafeToPasteTest` asserts.

**The whole-graph reading is kept, and gains the share line beside its count.** The count alone
would leave the reader dividing by a total printed in another section while every kind's share is
handed to him, which is the asymmetry that makes a reading get mis-read. No existing line changes
its label or its meaning.

**`CensusReport` renders the new figures inside the existing `degree` section**, after the
whole-graph lines, six lines per kind, labelled with the `NodeKind` constant name exactly as the
`nodes` section already labels its counts.

**The column reflows, and that is evidence rather than damage.** A share of `100` is three digits
where the widest count was two, so `CensusReport`'s derived count width moves from 2 to 3 and every
number in the block shifts one place right. The report's own Javadoc says the column is derived from
the census twice over; the reflow is that rule being observed rather than a pinned width being
edited.

## What gets an ADR, and what does not

**ADR 63 gains a dated amendment** recording that the degree section is now read by kind as well as
whole, and why: the population the floor governs is the one `couldBeExplored` admits. It does not
list the lines — ADR 63's own decision says `CensusReport` is the authority and a list in the ADR
would be a second copy going stale on its own.

**ADR 57 gets nothing on this branch, and that is deliberate.** ADR 57 refused an adaptive floor on
measurements and that refusal stands: this issue changes the reading, not the cut. The dated
amendment ADR 57 will eventually carry is the one recording what a real census *shows* — and no real
census is taken here. ADR 63's own first consequence already fixes this: the tool is the instrument,
and an amendment written before it has been run would record a decision nobody took. **Taking that
reading is the owner's step, out of this branch**, and the amendment that follows it is later work he
triggers.

**The developer guide's census chapter** does not list the counts — it says so, and defers to
`CensusReport` — so the only edit it needs is the "What it is for" bullet about the degree
distribution, which currently describes the reading as whole-graph only.

## Alternatives considered

- **Split the reading inside `FloorReading` instead.** The issue's first suggestion. **Rejected on
  the code:** every figure it carries is already a `PERSON`/`GROUP` figure, because the kind test is
  asked before the degree test. Splitting `PERSON` from `GROUP` there is a different question, which
  nobody has asked and which no reading has yet shown to matter.

- **A new top-level census section, `degree by kind`.** Reads well and groups the new lines under a
  heading of their own. Rejected because the sections are `Census`'s components: a seventh would
  change `Census`, its Javadoc's account of itself and the guide's, for a reading that belongs to the
  degree section and that the degree section can hold. The section list is also a surface other work
  is touching at the same time, and a reading does not need one to be legible.

- **Report the share to one decimal, as the issue's own prose quotes it (97.4%).** Rejected because
  ADR 63's first decision clause is *every value is an integer*, and `CensusIsSafeToPasteTest`'s
  guarantee is built on it. A decimal is not a label and would leak nothing, but the property that
  makes the test worth having is the flat one, and trading it for one digit is a bad exchange when
  the exact count sits on the line above.

- **Truncate the share rather than round it.** Simpler, and `100 * part / whole` is one expression.
  Rejected because the reading's whole use is comparing one kind's share against another's and
  against the whole graph's, and truncation biases every one of them downwards at once: two nodes in
  three reads 66 rather than 67, and the error is systematic rather than random.

- **Report the share and drop the count.** Rejected: a share of `0` covers both "none" and "a handful
  out of a hundred thousand", and the count is what tells them apart.

- **Report `PERSON` and `GROUP` only, since they are the kinds the floor governs.** Rejected on
  `NodeCensus`'s precedent — all kinds, zeros included — and because `WORK`'s row is the one that
  shows *why* the whole-graph figure reads as it does. A reading that dropped it would answer the
  issue's question and hide its evidence.

- **Drop the whole-graph reading now that a better one exists.** Rejected: it is one of the three
  numbers ADR 63 was built to produce (issue #135's drift question is about the graph the floor was
  measured against), and removing it would answer a different question than the one that was asked.

## Verification

Behaviour is covered by unit tests over invented fixtures — the by-kind figures, the share and its
rounding, the report's exact lines — each with a planted defect seen to fire. The ADR amendment and
the guide edit have no unit-testable behaviour; **they are verified by the full gate**
(`AdrIndexTest`, `DocumentationLinksTest` and `DeveloperGuideCensusExamplesTest` all read these two
files) **and by a re-read of the rendered text**, and this sentence is here so that no test-after is
implied by their absence.

## Out of scope

No floor moves. No per-kind floor exists. `FloorReading`, `CandidateSweep`, `Degrees` and every
other census section are untouched. No real census is taken and no figure from the owner's database
appears anywhere in this work.
