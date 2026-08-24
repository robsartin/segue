---
status: Accepted
date: "2026-08-23"
topic: bitemporal-time-model
tags: [project, provenance, data]
supersedes: []
related: [assertion-log-source-of-truth, graph-engine-gremlin]
---
# 20. Keep valid time and assertion time independent

## Context

"Blixa Bargeld was a Bad Seed from 1983 to 2003" and "we learned that on 3 March 2026"
are different facts, and conflating them produces questions the system cannot answer.
Band tenures, marriages, group memberships and label affiliations all have a period
during which they held; a source's claim about that period has its own timestamp, and
two sources are allowed to disagree about the period itself.

## Decision

- **Two independent time dimensions, never conflated:**
  - `validFrom` / `validTo` — when the relationship was true in the world.
  - `provenance.assertedAt` — when we learned it.
- **Validity dates live on the `AssertionRecord`, not on the edge.** Sources are
  permitted to disagree about when something was true, so the dates belong to the
  claim, not to the derived relationship.
- **Open-ended intervals count as true.** `null` on one side means unbounded; `null`
  on both means "always". `EdgeRecord.validAt` implements this.
- **`validTo` before `validFrom` is rejected at construction**, in the domain record.
- Each dimension gets its own query: `validAt(qid, asOf)` answers the world question,
  `assertedBy(sourceId, since)` answers the knowledge question.

## Alternatives considered

- **Single timestamp per edge** — much simpler, and wrong for the domain: it cannot
  distinguish "this stopped being true" from "we stopped believing it", which is exactly
  the distinction band tenures and corrected sources require.
- **Full temporal database semantics (SQL:2011 system-versioned tables)** — rigorous and
  standard, but ties the model to a specific storage engine, which ADR 19 deliberately
  refuses to do.
- **Resolving conflicting validity at ingest, storing one reconciled interval** — smaller
  graph and simpler reads, but discards the disagreement, which is a signal worth keeping
  in a system whose whole premise is recording who said what.

## Consequences

- "Who was in the Bad Seeds in 1984" is answerable, and correctly excludes members who
  joined later while including ones who have since left.
- "Everything this source told us after time T" is answerable independently, which is the
  blast-radius query for when a source turns out to be wrong.
- **Conflicting validity intervals are resolved first-writer-wins** in both adapters. This
  is a deliberate deferral, not a solved problem: the disagreement is preserved in the log
  even though the projection currently picks one. Revisit when two real sources actually
  disagree in practice.
- The projection has to choose a single interval per edge while the log holds several,
  so the derived `EdgeRecord` is lossy in a way the log is not. Anything that needs the
  full disagreement reads the log.
