---
status: Accepted
date: "2026-08-23"
topic: assertion-log-source-of-truth
tags: [project, provenance, data]
supersedes: []
related: [graph-engine-gremlin, bitemporal-time-model, quarantine-model-generated-assertions]
---
# 19. Make the append-only assertion log the source of truth

## Context

Segue records what *sources say*, not what is true. Two sources may claim the same
relationship, disagree about when it held, or turn out later to be wrong. A graph
that stores only the current believed state cannot answer "who told us this", cannot
retract one source's contribution without damaging another's, and cannot be rebuilt
when the storage engine changes.

The engine bake-off (ADR 18) also left a live risk: the chosen engine is the weaker
one for provenance queries. That is only survivable if the graph is disposable.

## Decision

- **`AssertionRecord` is the unit of ingest and the source of truth.** It is
  append-only: one source's claim that a relationship exists, carrying its own
  provenance and its own validity dates.
- **The graph is a derived projection.** Any `GraphStore` implementation can be
  rebuilt by replaying the log from the beginning.
- **Source adapters emit `AssertionRecord`, never `EdgeRecord`.** An adapter has no
  way to express "this is now a fact"; it can only say "this source said this".
- **`EdgeRecord` is the projection's view**: several assertions of the same
  `(from, type, to)` collapse into one edge holding every supporting `Provenance`.
  That collapse is what makes `corroboration()` countable. Different relationship
  *types* between the same pair stay distinct edges, which is why the store must be
  a multigraph.
- **`GraphStore.record` must merge, never duplicate.** A second source asserting an
  existing relationship adds provenance to the existing edge.

## Alternatives considered

- **Store the graph as the source of truth, with provenance as edge metadata** — fewer
  moving parts and one less projection step, but it makes the engine choice irreversible,
  makes retraction destructive, and leaves no record of what a source said before it was
  corrected.
- **Event sourcing with full command/event separation** — the same durability benefit with
  materially more machinery (command handlers, event upcasting, snapshot policy) than a
  personal-scale graph with one write path justifies.
- **Bi-directional sync between log and graph** — lets the graph accept direct writes, but
  reintroduces exactly the ambiguity about which store is authoritative that this decision
  exists to remove.

## Consequences

- Choosing the wrong engine costs a replay, not a rewrite. That is what makes ADR 18
  affordable despite its known weakness on provenance queries.
- Retracting a source is expressible against the log even where the projection makes it
  awkward, and the projection can be rebuilt to prove the retraction took.
- Every write path is one-way: adapters and tools append assertions; nothing edits the
  graph directly. Anything that wants to change a believed fact appends a new claim.
- The log must be durable and replayable in order, which is a real storage obligation
  rather than an in-memory convenience.
- Replay cost grows with history. At personal scale this is a startup detail; if it ever
  stops being one, the answer is a snapshot of the projection, not an authoritative graph.
