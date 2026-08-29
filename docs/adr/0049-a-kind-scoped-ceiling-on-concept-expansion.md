---
status: Accepted
date: "2026-08-29"
topic: a-kind-scoped-ceiling-on-concept-expansion
tags: [project, mcp, ingest, graph]
supersedes: []
related: [main-subject-as-the-route-through-what-a-book-is-about, reverse-lookup-via-sparql, mcp-tool-surface, mcp-protocol-conformance, six-kind-ontology, path-ranking-by-confidence, assertion-log-source-of-truth, retraction-as-a-new-claim, source-adapter-spi]
---
# 49. Bound a CONCEPT expansion with a ceiling on the request, not a smaller default

## Context

[ADR 47](0047-main-subject-as-the-route-through-what-a-book-is-about.md) closes with a constraint it
states and does not enforce: a subject arrives as the neighbour of an ingested book and is never an
expansion seed. It names issue #112 as where that gets closed. This is that decision.

### The flood, and it is already live

`expand_entity` accepts any QID already in the graph, and the discipline that has kept a broad
subject from being expanded — *only expand PERSON and GROUP seeds* — has lived in a scratch seeding
script rather than in this repository. A model asked to expand everything known about a broad
subject would do exactly what the tool allows.

Issue #112 measured what that costs, against live Wikidata:

- **religion** and **accounting** each hit the reverse lookup's **501-row cap** (500 plus the
  truncation probe) — 466 and 483 of those rows arriving through `P921`.
- One call would give either of them **in-graph degree 500**.
- The kept prefix is ordered by `DESC(?sitelinks)`, and at rank 29 of it sits ***WikiProject
  Religion*** — a Wikipedia editing project, not a work anybody read. The ordering keeps the
  best-linked rows, which is the right rule and does not make a five-hundred-row answer a discovery.

**It is not confined to obviously abstract subjects, and it did not arrive with ADR 47.** Java
expands to **91 edges today** through `P737` and `P361`, properties `EdgeTypes` already held before
that ADR was written. No new property is involved.

The consequence is worse than a large node, because of what the rest of the design guarantees. Those
edges land on the correct side of [ADR 19](0019-assertion-log-source-of-truth.md)'s append-only log,
so they are reachable only through [ADR 44](0044-retraction-as-a-new-claim.md)'s retraction — one
entity at a time, by hand.

### The measurement that chose the number

The bound in the plan was a guess. It was replaced by a distribution, computed on a copy of the real
graph — the assertion log folded through the exporter's own `LogProjection`, so this is not a second
model of the graph — with degree counted in both directions, exactly as `SegueService`'s degree
lookup counts it:

| in-graph degree of a `CONCEPT` | count |
| --- | ---: |
| < 10 | 16,861 |
| 10–24 | 70 |
| 25–49 | 15 |
| ≥ 50 | 4 |
| **all `CONCEPT` nodes** | **16,950** |
| **all nodes, every kind** | **123,752** |

Read two ways. **A ceiling of 25 lets 16,931 of 16,950 `CONCEPT`s — 99.9% — expand fully**: only 19
`CONCEPT`s in the whole graph have ever accumulated more edges than that. A ceiling of 50 covers
99.96%, which is not a meaningfully different answer for twice the room, and both sit two orders of
magnitude below the 500-edge failure. And **89 `CONCEPT`s sit at degree ≥ 10, 0.072% of the
graph** — which reproduces [ADR 31](0031-path-ranking-by-confidence.md)'s recorded figure exactly,
on the same graph state ADR 47 re-measured. That agreement is worth stating because that figure is
load-bearing elsewhere: it is the evidence ADR 47 used to refuse raising `PathRanking.HUB_DEGREE`,
and a distribution that contradicted it would have reopened that refusal rather than settled this
one.

## Decision

- **A kind-scoped ceiling, in `domain`.** `ExpansionBounds.effective(NodeKind, int)` is the whole
  rule and the class is the authority on it; this ADR does not reproduce its body.

- **A ceiling on the request, never a smaller default.** `effective(CONCEPT, 200)` returns the
  ceiling and `effective(CONCEPT, 5)` returns 5. That direction is the entire point. The hazard
  measured above is *a caller asking for a large bound*, and a default is a number a caller
  overrides by naming one — so a default would be bypassed by precisely the call the guard exists to
  stop, while still shrinking the honest small request that was never a problem.

- **`CONCEPT` alone.** Every other kind's request passes through unchanged, which is issue #112's
  own requirement that the legitimate path keep working: expanding a person or a band is where 200
  edges is the point rather than the hazard. `ExpansionBoundsTest.leavesTheOtherKindsAlone` asserts
  it over `NodeKind.values()` rather than over a hand-written list, so the assertion covers whatever
  kinds exist rather than the ones its author remembered. It does **not** force a new kind to be
  considered: a seventh would pass through unbounded, and whether that is right is a question this
  decision leaves open for whoever adds one.

- **Applied in `SegueService.expandEntity`, not in `SourceAdapter.supports`.** The seam that looks
  right is the wrong one — see the alternatives.

- **25, and it is a judgement.** The distribution says what a ceiling costs at various values; it
  does not name one. What the measurement establishes is that anywhere in the 25-to-50 range is
  nearly free and that 500 is not a bound at all. 25 is the low end of that range, chosen because
  the cost of being wrong in this direction is a `partial` result a caller can react to, and the
  cost of being wrong in the other is edges that need retracting one at a time.

- **The bound is reported by the result that hit it, and observed rather than assumed.** This is
  issue #65's rule and [ADR 27](0027-mcp-protocol-conformance.md)'s requirement, not a new one: the
  `truncated` flag compares the size actually collected against the effective bound, the same shape
  `findPaths` uses when it compares its ranked list against its raw one. A bitten ceiling arrives as
  `partial` with the effective bound named in the detail, through the reporting path that already
  existed.

## The ceiling is spent server-side, which is stronger than the plan asked for

The plan asked only that the extra edges not be *recorded*. What the code does is not fetch them.
The effective bound is resolved before it reaches anything else, so it is the number that travels:
`SegueService.expandEntity` builds its `ExpandContext` from the effective bound,
`WikidataSourceAdapter` passes `ctx.maxNewEdges()` into `ReverseClaims.lookup`, and `ReverseClaims`
interpolates it into the SPARQL as `LIMIT n+1` — the server-side spend
[ADR 36](0036-reverse-lookup-via-sparql.md) chose, with the extra row that makes truncation an
observation. A bounded `CONCEPT` expansion therefore asks Wikidata for 26 rows rather than 501. It
does not fetch five hundred and throw most of them away.

This applies to the reverse pass, which is the pass that floods. The forward pass reads the claims
stated on the seed itself and is cut to the bound in the adapter, after the fetch — unchanged by
this decision, and not the failure mode measured here.

## Alternatives considered

- **Refuse to expand a `CONCEPT` at all.** Issue #112 calls this "simple, and probably right", and
  the graph's value really does sit in people, groups and works. Rejected for two reasons. It
  forecloses narrow cases nobody has hit yet — a caller asking for five edges on a `CONCEPT` is not
  the hazard measured above, and under a ceiling that request is honoured in full while a refusal
  returns nothing. And the seam it would naturally be written at is wrong:
  `SourceAdapter.supports(kind)` exists, `SegueService.expandEntity` skips an adapter that declines
  a kind, and **`WikidataSourceAdapter.supports` returns `true` unconditionally** — it is the only
  `SourceAdapter` implementation in `src/main`, and the only one `SegueConfiguration` wires.
  Teaching it to decline `CONCEPT` would leave the loop with no adapter to run, and it would report
  a successful expansion that added nothing. **A silent zero, not a refusal** — the failure mode
  ADR 27 exists to prevent, and worse than the flood in one respect: nothing in the result would say
  why.

- **Refuse above a projected degree.** The most precise option, and genuinely available:
  `ReverseClaims` already fetches `n+1` to observe truncation rather than guess it, so a counting
  query is a small variation on something that works. Rejected on cost and on honesty. It spends a
  round trip on every expansion to protect against a case that arises for 19 nodes in 123,752, and
  the threshold it would compare against is a number nobody has measured — trading a judgement made
  against a distribution for a judgement made against nothing, at the price of a network call.

- **Report and require confirmation.** `partial` exists and `find_paths` uses it for this shape. It
  fails on who the caller is: `expand_entity` is invoked by a language model, so "require
  confirmation" means the model decides whether to proceed — and a model deciding is exactly what
  this guard exists to constrain. The reporting half is kept, because a bound that bites must be
  visible; the gate half would have been a gate with nobody behind it.

## Consequences

- **The rule lives in the code.** Issue #112's acceptance asked for a rule a test would fail
  without, and there are two: `ExpansionBoundsTest` pins the ceiling's direction in both directions
  (a large request pulled down, a small one honoured), and `SegueServiceTest`'s
  `expandEntityCapsAConceptAtTheCeiling` drives a `CONCEPT` seed through an adapter offering more
  than the ceiling and asserts the outcome is `partial`, the edges recorded equal the ceiling, and
  the detail names it.

- **A `CONCEPT` that legitimately wants more cannot get it through the tool.** There is no override
  argument and deliberately none: an override is a default wearing a different name, and the caller
  who would use it is the caller this bounds. What the result does say is that it stopped and where,
  so the shortfall is visible rather than silent, and repeated calls are the escape hatch — which is
  also the limit below.

- **`ExpandContext` is still one field.** Its own comment says more knobs arrive when something
  needs them; a ceiling that resolves *before* the context is built needed none, and the adapters
  stay unaware that any of this happened.

## What this does not fix

Stated plainly, because an ADR that implied otherwise would be the thing that stops the remaining
work being done.

- **It bounds damage; it does not express the policy.** The discipline is *only expand PERSON and
  GROUP*, and that sentence still lives outside this repository. What is now in the code is a
  smaller number for one kind. A `CONCEPT` expansion is still allowed, still adds up to the ceiling,
  and still adds works nobody has read — just 25 of them rather than 500.

- **It bounds one call, and calls are not counted.** Ten expansions of the same broad subject add
  ten times the ceiling, and nothing here sees the second one. Fixing that means state across calls,
  which is a different decision with a different shape; if it matters it should be filed as its own
  issue rather than solved by lowering this number, which would only make each of the ten smaller.

- **ADR 47's constraint is now partly enforced and not fully expressed.** The subject-as-neighbour
  rule said a subject is never an expansion seed. This makes seeding one cheap rather than
  impossible, which is the trade chosen above and not a claim that the constraint is met.
