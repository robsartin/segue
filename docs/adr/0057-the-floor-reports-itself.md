---
status: Accepted
date: "2026-08-30"
topic: the-floor-reports-itself
tags: [project, domain, tooling, graph, privacy]
supersedes: []
related: [recommend-by-normalised-lift-with-routes, suppress-a-candidate-you-have-rejected, what-an-adr-may-quote, a-high-rating-counts-as-something-you-have, taste-layer-separation, assertion-log-source-of-truth, source-adapter-spi, musicbrainz-as-the-second-source, record-architecture-decisions]
---
# 57. Make the degree floor report itself, and refuse both remedies that would change what it admits

## Context

[ADR 45](0045-recommend-by-normalised-lift-with-routes.md)'s 2026-08-29 amendment lowered
`Recommendations.MIN_CANDIDATE_DEGREE` from twelve to five, recorded the cost beside the benefit,
and closed by naming three questions it did not answer. Those are issues
[#133](https://github.com/robsartin/segue/issues/133),
[#134](https://github.com/robsartin/segue/issues/134) and
[#135](https://github.com/robsartin/segue/issues/135), and they are three views of one thing: the
candidate floor, and what expansion does to it.

**This ADR takes all three and says yes to one of them.** The reasoning for each refusal is below,
and each is refused on something measured rather than on what it would cost to build.

### The fence, which is what makes this hard

Seven shapes were measured over two days and six were rejected. Two of those results are proofs,
not observations, and ADR 45's amendment is the authority on both:

- **Any denominator monotone in degree is a dial on one axis.** Smoothing multiplies each score by
  `degree/(degree + K)`, so it interpolates between one floor's behaviour and the next and cannot
  produce a ranking neither floor produces.
- **`intermediates ≤ degree` always** — 0 violations in 9,273 candidates — so a corroboration
  threshold of *k* entails a degree floor of *k*, and the maximum number of intermediates at
  degree *d* is exactly *d*.

Nothing decided below is a new denominator or a new threshold on a quantity bounded by degree. The
one shape considered here that is neither — expansion state, issue #133 — is refused on its own
measurement, and the section on it says precisely why the two results do not reach it.

### What was measured for this decision, and on what

A copy of the owner's graph, taken 2026-08-30: 318,116 assertions, 124,127 nodes, 967 known
entities after promotions, 1,150 ratings, 143 hub intermediates excluded. The live database was
copied and never opened. Aggregates only, per [ADR 51](0051-what-an-adr-may-quote.md) — no entity
is named here, and the raw candidate lists behind these figures are retained outside this
repository for the reason ADR 45's issue-#115 amendment gives.

## Decision

### The floor takes a reading of itself on every run (issue #135)

`FloorReading` is a pure record in `domain` and it is the authority on which figures are emitted;
`CandidateSweep` counts the two the sweep alone can know, `Sweep` carries them, and
`RecommendationReport` writes the reading into the file's header while `RecommendRun` also emits it
as a note before the file exists.

**The reading is in the file and not only in the log, because the file is what gets kept.** Both
floors this project has shipped were chosen the same way: run two floors, open the two outputs,
read them side by side. A diagnostic that lived only in the terminal would be gone by the time that
comparison happened.

**It changes no score and reorders nothing.** Every figure is counted from candidates that have
already been scored and ranked, plus two counts of what the sweep discarded. Three independent
lines of evidence that the ranking is unchanged, rather than an assurance:

- The degree test moved out of `CandidateSweep.couldBeExplored` to its caller with the same
  comparison against the same number, so the admitted set is defined by the same predicate.
- Every pre-existing test still passes.
- The two runs below reproduce, exactly, the figures ADR 45's amendment computed by hand on `main`
  before this branch existed.

#### The reading, on the graph of 2026-08-30

| | floor 12 | floor 5 (the default) |
|---|---:|---:|
| candidates that cleared the floor | 1,011 | 1,604 |
| **the pool's median degree** | 38 | **19** |
| median degree of the 25 ranked | 27 | 6 |
| **of the 25 ranked, how many sit exactly on the floor** | 1 | **11** |
| of the 25 ranked, how many have every edge already counted as evidence | 1 | 12 |
| entities held out on degree alone | 8,262 | 7,669 |
| of those, how many carry a single edge | 5,874 | 5,874 |

**Four of the eight rows in the amendment's own before-and-after table are reproduced here at both
floors — eight figures — along with its count of 143 hub intermediates.** The amendment computed
them by hand from two output files; the tool now computes them on every run. That is the whole
point of the change, and it is also the check that the change is faithful: a reading that did not
reproduce them would mean the refactor had moved the admitted set.

Two figures are new. **The pool's median degree is 19 against a floor of 5** — the floor sits at
about a quarter of the middle of the population it filters, which is the distance ADR 45's
amendment describes in words and never put a number on. And **5,874 of the held-out entities carry
exactly one edge**, which is issue #134's population and is identical at both floors, as it must be
for any floor above one.

**The pool's median degree is comparable across runs at one floor and not across floors.** Raising
a floor removes low-degree members and so raises the median of what remains, mechanically; the 38
against the 19 above is that effect and is not evidence of anything. Only a later run *at floor 5*
may be compared against the 19.

#### What re-opens the question, stated so a procedure catches the drift rather than a person

**Re-run the two-floor comparison when the ranked head stops sitting on the floor.** Today at
floor 5, 11 of the 25 ranked sit exactly on it; the configuration this project measured and
rejected — floor 12 — puts 1 of 25 there, on the same graph on the same day. So the *direction* is
measured: a head that has come off the floor is what the rejected configuration looks like from
the inside.

**The threshold is chosen and not measured, and it is stated as chosen.** Re-run when fewer than
6 of 25 sit exactly on the floor, or when more than 19 of 25 do. Six is a quarter of the head and
sits nearer the rejected configuration's 1 than to today's 11; nineteen is its mirror, where the
floor would be most of what the ranking is. **No measurement distinguishes 6 from 5 or from 7**, and
anyone re-deciding this should move the number rather than treat it as a finding.

**One trigger, not three.** The pool's median degree and the held-out counts are emitted beside it
and are the figures to read once the trigger has fired, but a document that named three thresholds
would be one nobody honours.

### A newly discovered node is still not ranked, and the run now says how many there are (issue #134)

**Refused, and the refusal is the answer.** A node arriving from an expansion at degree 1 is not
admitted to the ranked list, at five as at twelve. ADR 45's amendment is the authority on why, and
nothing measured here weakens it: at K = 1 with the floor removed, 11 of the top 15 rows carried
5 distinct scores, broken by QID, and one expansion can add hundreds of such nodes at once.

What this ADR adds is the argument against the *third* shape, which that amendment named and did
not consider — **surface such nodes somewhere other than the ranked list.** Taken as a list it is
worse than taken as a count:

- **Ranking them is the measured failure**, above.
- **Listing them unranked is 5,874 rows in an order nothing justifies.** Every ordering available
  is derived from degree or from quantities bounded by it, which the second structural result
  closes; and an unordered list of that size is not a thing anybody reads.
- **Counting them is what an operator can act on**, and it is now what the run does. ADR 51 would
  in any case forbid the tracked-file version of such a list, because it is derived from the
  known-list.

**The lever it makes usable already exists.** `expand_entity` requires only that the entity be in
the graph — `GraphTools` states that contract — and a node discovered by an expansion is in the
graph. So fetching a second edge for a newly discovered node, which is the remedy ADR 45's
amendment names first, needs no new code at all. What was missing was any signal that 5,874 such
nodes were sitting there, and that is what the reading now gives.

So the exclusion stays deliberate, the developer guide's claim that it is deliberate stays true,
and the guide gains the sentence that the count is now reported.

### Expansion state is not recorded, and the score does not read it (issue #133)

**Refused on measurement.** Issue #117's third option was to record whether a node has been
expanded, when, and against which source, and to account for it in the score, so that "thin because
unfetched" and "thin in the world" stop being the same number.

**Neither structural result reaches it, and that is why it was worth measuring rather than
dismissing.** Expansion state is a boolean and not a denominator, so the first result — about
denominators monotone in degree — does not apply to it; and it is not bounded by degree the way
distinct intermediates are, so the second does not either. It fails for three reasons of its own.

**It is already recorded, and the recording is one-sided.** Every edge carries its provenance, and a
Wikidata `source_ref` names the entity that was fetched: a statement id is prefixed by its subject,
and a reverse-lookup ref names its seed. Over the copy, 17,942 edge assertions carry a statement id
and 147,460 carry a reverse-lookup ref, with none carrying neither, and 3,737 distinct entities are
derivable as expanded. **But an expansion that produced no mappable claim writes no edge assertion
at all, and the node assertion it writes is byte-identical in shape to the one written for a
neighbour that was merely discovered** — `WikidataEntityResolver` and `ReverseClaims` construct the
same `Provenance` for both. So the derivable flag means "expanded and productive", and it conflates
*never expanded* with *expanded and found nothing*. **Those are exactly the two cases the proposal
exists to separate**, and for a thin node they are the whole question. The size of that blind spot
is not measurable from the log, which is itself the finding.

**Where the graph is well connected the flag is a function of degree, and buys nothing.** Over the
19,166 `PERSON`/`GROUP` nodes in the copy, the share that is derivably expanded rises monotonically
with degree: 3.0% at degree 1 (388 of 12,733), 42.0% at degree 5 (162 of 386), 40 of 40 at degree
20, and **860 of 860 at degree 50 and above**. Above roughly degree 20 the flag is determined, and
a determined quantity separates nothing.

**Where it is informative, nothing chooses the sign of the adjustment.** At degree 5 — where the
floor cuts — 162 of 386 nodes are derivably expanded and 224 are not, a split no function of degree
can produce. That is real information. But issue #117's two readings of the demotion imply opposite
adjustments, and ADR 45's amendment records that both readings remain live after the correction of
the correlation figure. **Each direction undoes a decision this project has already taken on
evidence:** penalising the unexpanded demotes the recognisable-but-unfetched population that the
floor-5 decision was taken to admit and that produced ADR 50's 72-below-neutral pass, while
rewarding them makes the ranking depend *more* on ingest history, which is the defect issue #117
was filed about. An adjustment whose sign is undetermined is not a remedy.

**And the moment for a source-format derivation has passed.** [ADR 54](0054-musicbrainz-as-the-second-source.md)
took MusicBrainz as a second source three days ago. Parsing a Wikidata statement id is
Wikidata-specific and would report "never expanded" for an entity whose edges came from
MusicBrainz — silently, and in the direction that looks like a finding. A flag that spanned sources
would have to be recorded rather than derived, which is a schema change to the assertion log, and
issue #117's own words for that are "more machinery than the problem may deserve".

**What survives of the idea is the non-scoring half**, and it is the section above: the run now
reports how much of the pool sits on the boundary and how much was held out. That does not
distinguish unfetched from obscure for any individual candidate, and this ADR does not claim it
does.

## Alternatives considered

- **A relative floor — a percentile of the pool's degree distribution rather than an absolute
  count** (issue #135's first option). It tracks the axis as it moves, which is its real appeal, and
  it loses on two counts. It is still a cut on degree, so the first structural result applies to it:
  it can only reproduce some absolute floor's behaviour on the graph of the day, and the pair of
  runs above shows the two floors it would interpolate between. And it makes a run irreproducible
  across ingest states *by construction* rather than merely in practice — `--min-degree 12` no
  longer reproduces ADR 45's original behaviour if the floor is a percentile, which the amendment
  names as the method for reading two lists side by side. Rejected: it removes the tool that the
  drift is diagnosed with.
- **A recorded re-measurement cadence and no emitted figures** (issue #135's second option, and the
  cheap one). Half of it is taken — the trigger above is exactly this. Alone it fails, because the
  trigger has to be evaluated against something, and without the reading that means re-running the
  hand computation the amendment did, which is the manual step whose absence is the issue.
- **Emitting per-candidate expansion state in the report**, so that a reader could see which
  candidates are thin because unfetched without any score reading it. The most defensible partial
  version of issue #133, and it fails on the same one-sidedness: a candidate marked "not expanded"
  might have been expanded to nothing, and a per-row flag invites exactly the reading the data
  cannot support. It would also be Wikidata-specific, per ADR 54 above.
- **A separate output listing the newly discovered nodes**, issue #134's third shape taken as a list
  rather than a count. Rejected above: 5,874 rows with no defensible order, and derived from the
  known-list, so ADR 51 governs where it could go.
- **Enforcing the trigger with a test.** There is no oracle in CI: the figures come from a run
  against the owner's private graph, which this repository may not contain — the same argument
  ADR 51 makes for why its own rule cannot be tested. Stating a trigger that no gate enforces is
  honest; implying a gate exists would be worse than saying nothing, because the reader would stop
  looking.
- **Doing nothing, and leaving all three issues open.** The floor would keep drifting silently,
  which is the failure issue #135 describes: the list keeps looking plausible. And two of the three
  questions are answerable now, on measurements that already exist.

## Consequences

- **Every recommendation file gains two header lines, and every run two notes.** The output format
  changes; nothing that reads it programmatically exists in this repository.
- **`RecommendationReport.write` takes a `FloorReading` where it took an `int minDegree`.** Two
  arguments that had to agree are one, and the floor is now carried by the thing that was measured
  against it.
- **`CandidateSweep.couldBeExplored` no longer applies the floor.** The caller does, so that an
  entity refused for its kind and an entity refused for its size are countable apart. A count that
  mixed in records and learned societies would not be a reading of the floor.
- **The reading is quotable in a tracked file where the ranking under it is not.** Every field is an
  aggregate and none is a qid or a label, which is the line [ADR 51](0051-what-an-adr-may-quote.md)
  draws.
- **Two of the three issues close on a refusal.** That is the outcome, not a shortfall: issue #134's
  exclusion is now argued rather than inherited, and issue #133 is closed on three measurements
  rather than left as a shape nobody had tried.
- **`heldOutAtDegreeOne` will grow with every expansion and shrink only as those nodes gain a second
  edge.** It is the number to watch if the question "should growth be able to produce a
  recommendation" is ever re-opened, and it is now on the record of every run rather than
  recoverable only by driving `CandidateSweep` directly, which is what measuring it took before.
- **The trigger is held by a reader, like ADR 51's rule.** Nothing fails a build when the floor
  drifts. What changes is that a drifted run now *looks* different.
