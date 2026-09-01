---
status: Accepted
date: "2026-08-23"
topic: wikidata-identity-and-vocabulary
tags: [project, domain, modelling]
supersedes: []
related: [six-kind-ontology, assertion-log-source-of-truth, graph-engine-gremlin]
---
# 22. Anchor identity and vocabulary to Wikidata

## Context

A cross-domain graph needs one answer to "is this the same thing" that works across
music, film and literature at once. Every domain source has its own identifier space —
MusicBrainz MBIDs, TMDB ids, ISBNs, last.fm names — and none of them spans domains.
Picking one and mapping the rest makes whichever source we picked structurally
privileged, and leaves entities that source has never heard of unrepresentable.

The relationship vocabulary has the same problem. Inventing edge types produces a
vocabulary nobody else shares, that drifts as it grows, and that has to be re-decided
every time a new domain arrives.

## Decision

- **The Wikidata QID is the identity spine.** `NodeRecord.qid` is the identity, validated
  to look like `Q12345`.
- **Source-local identifiers resolve to a QID in the ingest layer and never appear in the
  domain.** MBIDs, TMDB ids and last.fm names are an adapter concern.
- **Edge vocabulary is borrowed from Wikidata properties, not invented.** `MEMBER_OF` is
  P463, `DIRECTED` is P57, `COMPOSED_FOR` is P86.
- **`EdgeType.wikidataInverted` records statement direction.** Wikidata states most creative
  relations on the work ("film P57 director person"); an affinity graph reads better oriented
  from the person ("person DIRECTED film"), so the flag lets ingest flip it mechanically
  instead of by hand per property.
- **`wikidataProperty` is null for derived types.** `COLLABORATED_WITH` and `SIMILAR_TO` are
  computed or model-proposed, never fetched, and the null records that honestly.
- **`EdgeTypes` is a spike stand-in.** In the real system these are rows, so adding a relation
  type is a data change rather than a redeploy.

## Alternatives considered

- **Internal surrogate ids with a mapping table per source** — full control over identity and
  no external dependency, at the cost of owning entity resolution across every domain forever,
  which is the hardest part of the problem and the part Wikidata has already solved.
- **MusicBrainz MBIDs as the spine** — excellent within music and useless outside it, which
  fails the cross-domain premise on the first film.
- **Inventing a bespoke relation vocabulary** — fits the product exactly on day one, then
  drifts, needs re-litigating per domain, and forfeits free alignment with an external dump.
- **Full schema.org or RDF ontology alignment** — more standard and more interoperable, and
  far heavier than a whitelist of roughly fifteen properties needs to be.

## Consequences

- Cross-domain identity is free: one QID is the same entity whether it arrived via music,
  film or literature ingest.
- The Jena reference adapter uses **real Wikidata IRIs**, so a Wikidata dump or a federated
  SPARQL query loads into it with no identifier mapping at all.
- The vocabulary is externally grounded and extensible by whitelisting another property.
- **Entities Wikidata does not know cannot be represented.** For a personal interest graph this
  is an acceptable boundary; if it stops being one, it needs its own decision, not a workaround.
- Ingest depends on Wikidata's availability and modelling choices, including its habit of
  stating creative relations on the work.
- The QIDs currently in `Fixture` are placeholders in the `Q9000xx` range, not real identifiers.
  Wikidata ingest retires them; nothing depends on their values.

**Amendment (2026-08-31, issue #141).** The consequence above is false, and was false when it was
written: nearly every `Q9000xx` id in `Fixture` resolves to a real Wikidata entity, and so does
every one of the `Q90000xxx` ids the seed tests use.
[ADR 58](0058-stand-in-identifiers-cannot-be-allocatable.md) holds the measurement, the date it was
taken and the decision that replaces this bullet: a stand-in QID now carries a leading zero, which
Wikibase's item-id grammar refuses outright, so it is unallocatable rather than merely unallocated.

The range was picked on the assumption that a high number would be free, which is what inventing an
external identifier looks like when it is done in good faith. The bullet is retained unedited above
because an ADR records what was decided, not what turned out to be true. The rest of this ADR —
anchoring identity to Wikidata QIDs — is unaffected.

**Amendment (2026-08-31, issue #92).** Clause 1 above — *"The Wikidata QID is the identity spine"* —
now admits a second identity kind, and the consequence *"Entities Wikidata does not know cannot be
represented"* has had the decision it asked for.

That consequence said the boundary was acceptable *"for a personal interest graph"* and that if it
stopped being one it *"needs its own decision, not a workaround."*
[ADR 53](0053-all-the-owners-interests-bounded-per-domain.md) is what stopped it being one, and
[ADR 59](0059-owner-claims-as-a-third-layer.md) is that decision. A local entity carries an id with
two leading zeros, which Wikibase's own `ItemId` grammar can never allocate
([ADR 58](0058-stand-in-identifiers-cannot-be-allocatable.md)), so it is a second kind of identity
living inside a shape the first kind cannot reach.

**What that costs.** "Everything in the graph has a Wikidata QID" is no longer true, so a reader who
assumed it — or a future adapter that round-trips an id to a source — has to read the id's shape
first. In exchange, the two populations can never collide, and nothing outside `LocalEntity` and
`SameAs` has to learn a second pattern: `Qid.check`, every `Q\d+` in `src/main`, and all of `port`,
`tinker` and `jena` are untouched. The alternative that would have been honest at a glance — a
visibly different prefix such as `L42` — is recorded in ADR 59 and lost on exactly that blast
radius.

Clauses 2 through 6 are unaffected: source-local identifiers still resolve to a QID in the ingest
layer, and the edge vocabulary is still borrowed rather than invented. An owner edge is drawn from
that same borrowed vocabulary.
