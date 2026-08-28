---
status: Accepted
date: "2026-08-27"
topic: store-p31-and-rederive-kind-at-projection
tags: [project, domain, data, persistence]
supersedes: []
related: [assertion-log-source-of-truth, bitemporal-time-model, six-kind-ontology, sqlite-assertion-log, wikidata-identity-and-vocabulary, taste-layer-separation]
---
# 42. Store P31 on node claims, and re-derive the kind at projection time

## Context

`KindMapper` read Wikidata's `P31` (instance of), derived one of the six `NodeKind`
constants from it, and threw the classes away. The log kept the derivation and not the
fact it was derived from.

That is backwards for an append-only log, and it has already cost real time. `KindMapper`
gained **17 classes** across issues #49 and #52 — rock band, musical duo, orchestra,
musical work/composition, television series episode and the rest — each one measured
against a real graph rather than guessed. None of them could reach an entity the graph
already held, because nothing in the database remembered what Wikidata had said. Issue #55
exists solely because of that, its fix only corrects a node when an expansion happens to
touch it again, and getting the benefit across the whole graph meant a **full re-seed,
twice, at about seventeen minutes each**.

The classes are not expensive to keep. `WikidataEntityResolver` already reads them from the
entity, and `ReverseClaims` already gets them inline in its SPARQL response — both then
discarded them.

## Decision

- **`NodeAssertion` and `NodeRecord` carry `instanceOf`**, the raw class QIDs the source
  stated, beside the `NodeKind` derived from them. A list, in the order the source gave
  them, because the mapping takes the first class it recognises — order is part of the
  value, which is why it is not a set.
  *(Amended 2026-08-28, issue #87: the second sentence's REASON is gone, the type is not.
  The mapping no longer takes the first recognised class — ADR 21's amendment ranks the
  kinds, so the derived kind is the same whatever order the classes are in, which is the
  whole point of that change. It stays a list because the field records what the source
  said, and what a source said has an order; a set would be this projection editing the
  claim. Both stores still round-trip that order and `GraphStoreContract` still pins it.)*
- **`NodeKind` still has exactly six constants.** ADR 21 is untouched. This keeps the raw
  fact beside the derived classification; it does not enlarge the classification.
- **Both projections re-derive the kind from the stored classes, always, with no network.**
  `GraphProjector` (the boot replay, ADR 24) and `LogProjection` (the exporter's fold, ADR
  41) both call `KindMapper.rederive`. A claim stating no classes keeps the kind it
  recorded; a claim that states classes takes the mapper's answer, **including when that
  answer is `CONCEPT`** — otherwise the whitelist would be a ratchet, where additions
  propagate and corrections never do.
- **The rule lives in `KindMapper`**, beside the table it re-applies, not in either
  projection. Two copies would be free to disagree about a graph and a picture of that
  graph, which is the same argument that makes `GraphProjector` share
  `IngestService.apply` rather than own a second copy of it.
- **Re-derivation is always on, not a flag.** An opt-in correction is one every future
  caller has to remember, and forgetting is invisible: a graph that looks right and quietly
  holds a stale classification. That was already the shape of issue #55.
- **The log is never rewritten.** It keeps saying what the source said and what was made of
  it at the time (ADR 19). Re-derivation belongs to the projection, which is the part that
  is meant to be rebuilt.
- **The list is stored as one space-separated column**, `instance_of`, beside `node_kind`,
  and as one packed literal on a graph vertex. `NodeRecord` validates every value as
  `Q\d+`, so no value can contain the separator and nothing needs escaping. That is
  `ProvenanceCodec`'s argument reached from the other end: it forbids its separators in the
  free text it packs; here the whole value is constrained, so nothing has to be forbidden.
- **No migration.** The schema arrives with `CREATE TABLE IF NOT EXISTS` against a fresh
  file, with `P31` present from the first row. The existing database is deleted and
  re-seeded after this lands. See the consequences below, which is the important half.

## Alternatives considered

- **Keep discarding P31 and re-seed whenever the mapper improves** — the status quo, and it
  works: seventeen minutes and a Wikidata round trip for every entity, every time, for a
  correction the database already had the information to make offline.
- **Walk `P279` (subclass of) upward instead of keeping a whitelist** — more faithful to
  Wikidata and the reason the whitelist has an "honest fallback" in the first place, but it
  is a round trip per unknown class over a hierarchy deep enough to be its own project, and
  it does not help the entities already stored. Storing `P31` is what makes any future
  version of the mapping — whitelist, `P279` walk, anything — applicable retroactively.
- **Store the classes on the graph vertex only, not on the claim** — smaller change, and it
  makes the graph the thing that remembers, which is precisely what ADR 19 refuses. A
  rebuild from the log would lose them.
- **Store the list as JSON** — self-describing and escapable, and it would put a JSON
  library inside an adapter that deliberately touches only `java.sql`, to encode a list of
  values that cannot contain a delimiter.
- **A set rather than a list** — the natural type for "the classes this is an instance of",
  and wrong here: the mapping takes the first recognised class, so a set would make the
  derived kind depend on iteration order. *(Amended 2026-08-28, issue #87: that hazard is
  now removed at the other end — the kinds are ranked, so iteration order cannot change the
  answer. `ReverseClaims` had in fact been collecting the classes into a `LinkedHashSet`
  keyed on SPARQL row order all along, which is how a film ended up stored city-first. The
  conclusion is unchanged for the reason given above.)*
- **Re-derive only on an explicit `--rederive` flag** — auditable, and it reproduces the
  failure mode this ADR exists to remove.
- **A schema version and an `ALTER TABLE` upgrade path** — the responsible default, and it
  was the original plan for this issue. Withdrawn deliberately; see below.

## Consequences

- A future `KindMapper` improvement corrects every affected node **for free, at the next
  boot, offline**. `GraphProjectorTest` pins exactly that: a node logged as `CONCEPT` with
  a class the mapper has since learned projects as `GROUP`, with no fetch and no rewrite of
  the log. *(Issue #87 was the first change to collect on it: replaying a copy of the real
  307,037-row log corrected four nodes with no re-seed and no network.)*
- Old claims do not heal. Rows written before this change have no classes, so they keep the
  kind they recorded — which is why the graph is re-seeded once more rather than migrated.
  After that re-seed, every node carries its classes and no further re-seed is needed for a
  mapping change.

### This shortcut works exactly once more

The world-fact layer is regenerable and the taste layer is not. Every one of the 265,046
assertions in the log is a Wikidata-derived claim about the world, reproducible from the
seed list in about seventeen minutes, and `affinity` currently has **0 rows** — so deleting
the file costs time and nothing else. That is the entire argument for shipping a schema
change with no migration, and it is an argument about the data that happens to be there,
not about how schema changes are done here.

**The moment a rating exists it stops being true.** ADR 33 puts affinity in the same file
as the world facts, and a rating is first-person data with no external source and no way to
re-derive it (ADR 39 keeps no history either). The next schema change gets a real migration
path. The absence of one here is not a precedent.

### Assertion timestamps reset

Every fact in the rebuilt log will be dated from the re-seed rather than from when it was
actually first learned. ADR 20 treats assertion time as a real dimension — `assertedBy`
answers "everything this source told us after time T", which is the blast-radius query for
a source that turns out to be wrong — and re-seeding flattens it to a single instant. On a
two-day-old graph that is noise rather than loss, which is why it is acceptable now and
would not be later. It is a genuine cost and is recorded here rather than glossed.

### Smaller ones

- Both graph engines persist the list, so the record's field is not silently dropped by
  either store. `GraphStoreContract` pins the round trip, including the order.
- `ingest` and `export` now both depend on `wikidata` for the re-derivation rule. That is
  the direction ADR 32's layering already allows (adapters sit below both), and
  `KindMapper` is pure, network-free and Spring-free, but it does mean the projections know
  the name of a source. If a second source ever states classes of its own, this is the
  seam that has to move.
- Exports agree with the running graph about what a node is, because they apply the same
  rule. DOT colours and shapes nodes by kind (issue #61), so a disagreement would have been
  visible and misleading.
