---
status: Accepted
date: "2026-08-23"
topic: six-kind-ontology
tags: [project, domain, modelling]
supersedes: []
related: [wikidata-identity-and-vocabulary, assertion-log-source-of-truth]
---
# 21. Model six node kinds; express roles as edges

## Context

Segue spans any domain — music, film, literature, speakers, places, ideas. The obvious
modelling instinct is a node type per thing the user cares about: MUSICIAN, BAND, ALBUM,
FILM, NOVEL. That instinct produces a taxonomy that grows without bound, forces
per-domain dialects into every query, and cannot represent one person who is a novelist,
a screenwriter and a musician at once without either duplicating them or picking a
primary identity that is a lie.

Nick Cave is the test case: he performs, he writes novels, he writes screenplays, and he
composes film scores. All four at the same time, all four worth traversing.

## Decision

- **`NodeKind` has exactly six constants: PERSON, GROUP, WORK, PLACE, EVENT, CONCEPT.**
  This is intended to hold for the life of the project.
- **Roles are relationships, not types.** "Musician", "novelist", "director" are
  expressed as edges — `PERFORMED`, `AUTHORED`, `DIRECTED` — so one Nick Cave node is
  all of them at once and the enum never grows.
- **Wanting to add MUSICIAN or FILM means the model is being used wrong.** That sentence
  is the enum's actual invariant; treat the urge as a design smell to investigate, not a
  requirement to satisfy.
- **One flat relation namespace across all domains.** Music, film and literature relations
  share `EdgeTypes` rather than living in per-domain vocabularies.

**Amendment (2026-08-28, issue #87): when a source states classes belonging to more than one
kind, a fixed precedence decides which kind the entity is.** Six kinds do not stop a source
asserting several of them at once. `Q1219310`, "National Lampoon's Vacation", states `P31` =
`Q11424` (film) **and** `Q515` (city); the second statement is unsourced and simply wrong
upstream, but it is really there, and it made a comedy a `PLACE` in a real graph.
`KindMapper` took the first class it recognised, and that order carries no meaning — Wikidata's
entity JSON lists statements oldest first, the SPARQL reverse lookup binds them in row order, so
the same entity could resolve two different ways depending on which call happened to learn it.
**The kinds are now ranked — PERSON, WORK, GROUP, EVENT, PLACE, CONCEPT — and the highest-ranked
kind an entity states wins, whatever order the classes arrive in.** The ranking and the argument
for each rung live in `KindMapper.PRECEDENCE`; in short, `Q5` (human) is the least ambiguous
statement Wikidata makes, a thing that is both a work and something else is the work it was
released as, and every place-class conflict observed so far is a place class attached to something
that is not a place. CONCEPT ranks last because it means "we could not place this" (ADR 22) and
must never outrank a class the whitelist does recognise. Measured over the whole graph, 10 of
123,752 entities state two kinds and four of them change: a film out of `PLACE`, a singer out of
`GROUP`, and three concert recordings out of `EVENT` into `WORK`. Adding a seventh kind now means
deciding where it ranks — a static check fails the build if `PRECEDENCE` does not rank every
constant of `NodeKind` exactly once.

## Alternatives considered

- **A node type per domain concept** — the intuitive model and the one most graph examples
  use. Rejected because the taxonomy grows without limit, queries acquire per-domain
  branches, and multi-role entities cannot be represented honestly.
- **A type hierarchy (WORK → FILM → DOCUMENTARY)** — expressive, but every traversal then
  has to decide how far up or down the hierarchy to match, and the cross-domain paths this
  product exists to find are exactly the ones that cross hierarchy branches.
- **Mirroring the full Wikidata class hierarchy via P31/P279** — maximally faithful to the
  source, and far too large and too deep to traverse or reason about at personal scale.
- **Untyped nodes, kind inferred from edges** — maximally flexible, but gives disambiguation
  and search nothing to filter on, which the MCP tool surface needs.

Two more, weighed for the issue-#87 amendment above:

- **The most specific stated class wins, resolved through `P279` subclass chains** — more
  faithful than a ranking, and disqualified twice over. It is a round trip per unknown class,
  and `KindMapper` is re-applied by both projections **offline** at boot (ADR 42), so a mapper
  that could reach the network could not run where it is needed most. It would not even settle
  the case that prompted this: neither "city" nor "film" is a subclass of the other.
- **Refuse to choose, and flag the conflict** — the honest option, and the wrong one here on
  both halves. `CONCEPT` is a worse answer than a ranked one rather than a safer one: ADR 31
  reads a high-degree `CONCEPT` intermediate as a hub, so refusing would demote the film's
  routes as well as mislabel it. And the flag has nowhere to go — `isMapped`, the existing
  "report what we could not map" seam, has no production caller to report through. Building
  that surface for ten entities would be speculation; the ranking is deterministic, so a
  conflict report can be added later against unchanged data.

## Consequences

- Cross-domain paths work by construction: a route from a musician to a novelist through a
  film needs no special case, because there is no per-domain dialect to bridge.
- One entity carries every role it has, and adding a new role is adding an edge type, which
  is a data change.
- Queries that genuinely want "films only" must filter on edge type or on source metadata
  rather than on node kind. This is the accepted cost.
- The six kinds do real work in disambiguation and search, so they must stay meaningful;
  pressure to add a seventh is a signal to re-read this ADR before acting on it.
