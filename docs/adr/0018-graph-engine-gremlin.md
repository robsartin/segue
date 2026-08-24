---
status: Accepted
date: "2026-08-23"
topic: graph-engine-gremlin
tags: [project, graph, engine]
supersedes: []
related: [assertion-log-source-of-truth, bitemporal-time-model]
---
# 18. Use Gremlin/TinkerPop as the graph engine

## Context

Segue is a provenance-first affinity graph whose payoff feature is a citable
explanation: given two entities, return the route between them with every hop
attributable to a source. Three properties of the domain make the usual graph
examples a poor guide:

1. **Multigraph** — Nick Cave both wrote and scored *The Proposition*. Two
   relationships, one pair of nodes.
2. **Provenance-first** — every edge carries who claimed it, when, and how much we
   trust them; corroboration across sources is a first-class signal.
3. **Bitemporal** — when a fact was true in the world is independent of when we
   learned it.

Property graphs and RDF diverge sharply on all three, so slice 0 answered the
question empirically rather than by argument: one domain model, one `GraphStore`
port, two complete adapters (Apache TinkerPop and Apache Jena), and four queries
chosen because the engines differ on them. Both adapters return identical results
on all four.

## Decision

- **Apache TinkerPop / Gremlin is the graph engine.** `TinkerGraphStore` is the
  implementation the system uses.
- **`JenaGraphStore` is kept working as a reference implementation**, not deleted.
  It is the cross-check that keeps the port honest and the decision cheap to revisit.
- All access goes through the **`GraphStore` port**, which is the seam that makes
  the engine choice reversible.

Two reasons decided it, in order of weight:

1. **Correctness on a multigraph.** The natural RDF neighbour query,
   `SELECT DISTINCT ?other`, walks *nodes* and silently collapses parallel edges.
   It returned plausible wrong answers: one route vanished, the walk backfilled
   with a longer detour, and reconstruction had to guess which relationship it had
   traversed. Gremlin's `bothE().otherV()` steps through edges by construction and
   cannot have this bug.
2. **Paths are the product.** Gremlin answers Q1 in 27 non-comment lines — one
   traversal that states the intent. Jena needs 81, because SPARQL 1.1 property
   paths can test *that* two entities are connected but offer no standard way to
   return the path, forcing hand-rolled depth-first enumeration, a neighbour cache
   to avoid quadratic round trips, and a reconstruction pass.

## Alternatives considered

- **RDF / SPARQL via Apache Jena, one named graph per assertion** — genuinely better
  at provenance. Corroboration is a single `GROUP BY` the engine can index; merging
  two sources that claim the same relationship needs no code at all, because the same
  triple in two named graphs *is* the merge; retracting a bad source is a graph-level
  `DELETE`; and entity IRIs are real Wikidata IRIs, so a dump loads with no identifier
  mapping. It lost on the query the product is built around, and on a failure mode that
  produces wrong answers rather than errors.
- **Reifying every relationship as a Claim vertex in the property graph** — would make
  provenance queryable by the traversal engine instead of opaque, but turns every logical
  hop into three graph hops and makes Q1, the payoff query, substantially worse.
- **A managed graph database (Neo4j, Neptune)** — infrastructure and operational cost with
  no benefit at personal scale, where the whole graph fits in memory.

## Consequences

- Path queries — the feature this exists for — are expressed directly, and parallel
  edges are distinct routes without anyone having to think about it.
- **Provenance is opaque to the traversal engine.** It is packed into an edge property
  by `ProvenanceCodec`, so the audit query (Q2, everything source X said after time T)
  and the corroboration query (Q4, edges backed by ≥N distinct sources) degrade to full
  edge scans in application memory. Acceptable at personal scale; the assertion log means
  the escape hatch is projecting into a second store for exactly those queries, not a rewrite.
- Merging two sources that assert the same relationship needs explicit find-then-append
  logic, and retracting a source is a scan-and-rewrite of every affected edge's blob.
- The provenance codec is a delimited string with no escaping, so `Provenance` forbids
  tabs and newlines in `sourceId` and `sourceRef`. That constraint is enforced in the
  domain record, not in the adapter.
- Revisit only if auditing the graph becomes more common than walking it. Because the
  assertion log is the source of truth, that revisit is a replay, not a migration.
