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

## Consequences

- Cross-domain paths work by construction: a route from a musician to a novelist through a
  film needs no special case, because there is no per-domain dialect to bridge.
- One entity carries every role it has, and adding a new role is adding an edge type, which
  is a data change.
- Queries that genuinely want "films only" must filter on edge type or on source metadata
  rather than on node kind. This is the accepted cost.
- The six kinds do real work in disambiguation and search, so they must stay meaningful;
  pressure to add a seventh is a signal to re-read this ADR before acting on it.
