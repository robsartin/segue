---
status: Accepted
date: "2026-08-24"
topic: path-ranking-by-confidence
tags: [project, graph, trust]
supersedes: []
related: [quarantine-model-generated-assertions, graph-engine-gremlin, mcp-tool-surface]
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
