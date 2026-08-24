---
status: Accepted
date: "2026-08-23"
topic: quarantine-model-generated-assertions
tags: [project, provenance, trust]
supersedes: []
related: [assertion-log-source-of-truth, bitemporal-time-model, graph-engine-gremlin]
---
# 23. Quarantine model-generated assertions until corroborated

## Context

A language model is very good at proposing plausible connections between entities and
has no way to distinguish the ones it knows from the ones it has constructed. In a graph
whose product is a *citable* explanation, an uncorroborated model guess that looks like a
fact is the failure mode that destroys the product: the explanation still renders, still
reads well, and is not true.

Slice 0 demonstrated this concretely. In the fixture, the shortest route between Nick Cave
and Cormac McCarthy is an unverified model guess at confidence 0.30, while the trustworthy
route is three hops at confidence 1.00. Ranking by path length surfaces the guess.

## Decision

- **Model-generated assertions carry a `llm:` source prefix.** `Provenance.isHypothesis()`
  is that check, and `EdgeRecord.isUncorroboratedHypothesis()` is true while every supporting
  assertion is one.
- **A shared confidence convention across all adapters:**
  - 1.00 — structured and authoritative (a Wikidata statement with a reference)
  - 0.80 — structured but unreferenced (a Wikidata statement with no source cited)
  - 0.50 — statistical or behavioural (last.fm-style similarity)
  - 0.30 — model-generated hypothesis, not yet corroborated
- **Corroboration is counted by distinct source, not by assertion count.**
  `EdgeRecord.corroboration()` deduplicates by `sourceId`, so one chatty source cannot
  corroborate itself.
- **A hypothesis is promoted only when a real source independently asserts the same
  relationship** — which, because the log merges by `(from, type, to)`, happens by itself.
- **`PathResult.weakestConfidence()` is the trust measure for an explanation**: a path is
  only as trustworthy as its shakiest hop.

## Alternatives considered

- **Refuse model-generated edges entirely** — safest, and discards the exploratory
  suggestions that make an affinity graph interesting to author into. The prefix keeps them
  while making them impossible to mistake for facts.
- **A single boolean `verified` flag** — simpler than a confidence scale, but flattens the
  real difference between a referenced Wikidata statement, an unreferenced one, and a
  statistical similarity into one bit.
- **Ranking paths by hop count alone** — the conventional shortest-path default, and actively
  wrong here: the fixture shows it preferring a 0.30-confidence guess over a 1.00-confidence
  three-hop route.
- **Storing hypotheses in a separate graph** — clean isolation, at the cost of making every
  traversal query two stores and merge results, for a distinction one source prefix already
  carries.

## Consequences

- An uncorroborated guess is always distinguishable from a sourced fact, at the edge and
  along a whole path.
- Corroboration is a countable signal rather than a judgement call.
- **Shortest path is currently the wrong default ranking and nothing yet ranks by
  `weakestConfidence()`.** This is a known open issue, and it should be fixed before the
  graph grows large enough for weak routes to be common.
- The confidence scale is a convention every adapter must honour. A new adapter picking its
  own numbers silently corrupts the ranking, so the scale belongs in review.
- The `llm:` prefix is a string convention rather than a type, which keeps `Provenance`
  free of adapter knowledge but means the check is not compiler-enforced.

---

*Correction, 2026-08-24: the Context section originally named John Hillcoat as the
endpoint of the low-confidence shortcut. The fixture's model-generated shortcut is
Cave→McCarthy; all Cave→Hillcoat routes are wikidata-sourced at confidence 1.00.
The decision is unaffected.*

*Resolved, 2026-08-24: the open ranking issue in the Consequences is closed by ADR 31 and
increment 1. `GraphStore.paths(from, to, maxHops)` now returns every route untruncated and a
shared `PathRanking` orders them by weakest confidence (hop count as tiebreak) above the port,
so the trustworthy route is surfaced first. See docs/adr/0031-path-ranking-by-confidence.md.*
