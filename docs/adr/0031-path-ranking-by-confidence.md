---
status: Accepted
date: "2026-08-24"
topic: path-ranking-by-confidence
tags: [project, graph, trust]
supersedes: []
related: [quarantine-model-generated-assertions, graph-engine-gremlin, mcp-tool-surface, six-kind-ontology, award-received-as-the-first-non-collaboration-edge]
---
# 31. Rank paths by weakest confidence, not by hop count

## Context

ADR 23 records this as a known open issue. In the slice 0 fixture the shortest route
between Nick Cave and Cormac McCarthy is an unverified model guess at confidence 0.30,
while the trustworthy route is three hops at 1.00. Ranking by length surfaces the guess
first.

`PathResult.weakestConfidence()` already exists and nothing calls it. Because
`find_paths` is one of the six tools, shipping it unranked would mean the first thing
seen in a conversation is a plausible wrong answer, which is the precise failure mode
the provenance design exists to prevent.

The obstacle is structural rather than arithmetic: `shortestPaths(from, to, maxHops,
limit)` truncates inside each adapter, so the best routes can be discarded before any
ranking code could see them. Ranking cannot be fixed where the results are already cut.

## Decision

- **`shortestPaths(from, to, maxHops, limit)` becomes `paths(from, to, maxHops)`.**
  Adapters return every route they found up to `maxHops`. The old name was a misnomer:
  the traversal already returned all routes, not only the shortest.
- **A shared `PathRanking` orders and limits once, above the port**, so both adapters
  get identical ordering and neither can drift.
- **The order is weakest confidence descending, then hop count ascending.** A path is
  only as trustworthy as its shakiest hop; length breaks ties.
- **An internal cap bounds the returned list**, so a dense neighbourhood cannot produce
  an unbounded result.

**Amendment (2026-08-26, issue #52): a second dimension, and it goes first.**

Confidence is the right axis and nothing above is withdrawn. It is not the only axis, and
dogfooding a real 25,815-node graph showed what it misses. The best-connected route between
Anjelica Huston and Bea Arthur was

```
Anjelica Huston -[RECEIVED_AWARD]- star on Hollywood Walk of Fame
                -[RECEIVED_AWARD]- Billy Crystal
                -[RECEIVED_AWARD]- Disney Legends
                -[RECEIVED_AWARD]- Bea Arthur
```

Four hops, every one a referenced Wikidata statement at 1.00, ranked top by the rule above, and
it explains nothing — a Walk of Fame star says "this person was famous", which is true of
everyone in the graph and is therefore a relationship between nobody. Of the 25,525 nodes shared
by two or more seeds, only 26 were shared by ten or more, and they were overwhelmingly
career-recognition awards. ADR 38 left this open as its question 3; this is the answer.

- **Ranking gains a specificity dimension: the in-graph degree of a `CONCEPT` intermediate.**
  A route through a busy `CONCEPT` is demoted. `PathRanking.HUB_DEGREE` is 10.
- **Kind is half the rule and the half that makes it work.** Degree alone is the wrong signal,
  because the busiest nodes in the graph are the expanded seeds themselves — The Beatles at 200
  edges, David Bowie at 200 — and those are legitimate connectors. Every hub measured was a
  `CONCEPT`; every busy legitimate node was a `PERSON`, a `GROUP` or a `WORK`.
- **Endpoints are not intermediates.** "What connects me to the Rock and Roll Hall of Fame" is a
  fair question, and its answer must not be demoted for ending where it was asked to end.
- **Specificity is a veto, not a score.** Two classes — hub-bearing or not — rather than ordering
  continuously by degree. Fifteen of 25,815 nodes clear the threshold, so confidence still
  decides all but a handful of comparisons; ordering by raw degree would make confidence
  vestigial, since degrees rarely tie.
- **The full order is: no model guess, then fewest hub intermediates, then weakest confidence
  descending, then hop count ascending.**

### How the two compose, and which wins

**Specificity wins.** A route through a low-degree intermediate beats a route through a hub of
the same confidence — and of *higher* confidence, which is the part that had to be true for this
to be worth doing. The award routes are all 1.00 and the informative ones are 0.80, so a
specificity tiebreak *within* equal confidence would have changed nothing at all; the empty
routes filled the entire top band and no tiebreak could reach them. The two dimensions answer
different questions, and the order says which question is asked first: **confidence asks whether
a route is true, specificity asks whether it means anything, and a route that means nothing is
not made better by being certain.**

**One exception, and it is this ADR's own decision.** Specificity never promotes a path that
rests on a model guess (ADR 23's `llm:` tier) above one a real source stands behind. This ADR
exists so a plausible wrong answer cannot be the first thing a conversation sees, and letting
specificity invert that would have been a regression dressed as an improvement — a hub route at
1.00 losing to a 0.30 guess. Inside each of the two quarantine tiers, specificity leads as
above. The guard changes no ordering that exists today (a hypothesis hop is 0.30 by convention,
so weakest-confidence already sorts it last); it exists so that the day model-generated edges do
arrive, they cannot be promoted by a dimension that says nothing about whether they are true.

### The architecture this is allowed to touch

`PathRanking` lives in `domain`, which carries zero third-party dependencies and no graph access
(ADR 18, ArchUnit-enforced) — and specificity is a fact about the graph's shape. **The degree
lookup is passed in as a `java.util.function.ToIntFunction<String>` over a qid**, which is
`java.*` and leaves the domain pure; `SegueService` supplies it from the port it already holds,
memoised for the duration of one call. A no-degree overload keeps the original order for callers
that cannot supply one — notably the contract tests, which compare two engines rather than judge
a route.

### Consequences of the amendment

- Huston ↔ Arthur now leads with a route through two specific acting awards bridged by a person
  who won both, verified against the real graph. Both known-good routes survive: Gottfried ↔
  Seyfried through CSI, Mulaney ↔ Martin through the Saturday Night Live 50th Anniversary
  Special.
- **It depends on `KindMapper` being right, and `KindMapper` was not.** "High-degree `CONCEPT`"
  only means "hub" while `CONCEPT` means "we could not place this". Measured over the 1,416
  `CONCEPT` nodes that could be an intermediate at all, 1,058 were works wearing a class the
  whitelist did not know — including the SNL 50th Anniversary Special, the single best connector
  in the graph, which this rule would have vetoed. Those classes were added in the same change.
  **Adding an award class to `KindMapper` would silently switch this rule off**; a test in
  `KindMapperTest` pins awards to `CONCEPT` for that reason.
- **The threshold is an absolute degree on a personal-scale graph, so it will drift.** It names
  the tail of a measured distribution — 9,495 `CONCEPT` nodes of degree 1, 1,329 of degree 2-4,
  fifteen at ten or above — rather than expressing a preference. Re-measure before changing it.
- **This is a ranking change, so it needs no re-projection**, unlike the `KindMapper` half: node
  kind is recorded on the assertion, and the log is append-only (ADR 19), so replay reproduces
  the kinds as they were fetched. Corrected kinds arrive when an entity is next added or
  expanded.
- **Two things it deliberately does not do.** It does not stop hub edges being ingested — ADR 38's
  award vocabulary is untouched, and a hub route is still returned, merely last. And it says
  nothing about a busy `GROUP`: the American Academy of Arts and Sciences connects 21 seeds
  through `MEMBER_OF` and is career recognition by another name. Left open on purpose, because
  the measurement that would settle it is membership, not awards.

## Alternatives considered

- **Rank inside each adapter** — no port change, and it duplicates the comparator in two
  implementations that must then be kept identical, which the contract tests would have
  to police forever.
- **Keep `limit` in the port and sort what comes back** — smallest diff, and it ranks a
  set the adapter has already truncated, which is the bug rather than a fix for it.
- **A `rank` parameter on `find_paths`** — flexible, and it pushes a question with a right
  answer onto the model at call time and widens the tool surface ADR 26 keeps narrow.
- **A combined score mixing length and confidence** — plausible, and it invents a weighting
  nobody can justify, where the lexicographic rule states the intent exactly.

## Consequences

- The most trustworthy explanation is presented first, which is what makes the payoff
  feature honest.
- The comparator lives in one place and both engines are held to it by the contract tests.
- Returning all routes before ranking costs more memory than truncating early. Bounded by
  `maxHops` and the internal cap, and acceptable at personal scale.
- A long, fully sourced route now outranks a short guess. That is the intent, and it will
  occasionally surprise, so `find_paths` results show per-hop citations that explain the order.

---

*Correction, 2026-08-24: the Context section originally named John Hillcoat as the
endpoint of the low-confidence shortcut. The fixture's model-generated shortcut is
Cave→McCarthy; all Cave→Hillcoat routes are wikidata-sourced at confidence 1.00.
The decision is unaffected.*
