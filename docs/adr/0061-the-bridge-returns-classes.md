---
status: Accepted
date: "2026-09-02"
topic: the-bridge-returns-classes
tags: [project, ingest, extensibility, performance]
supersedes: []
related: [what-the-musicbrainz-adapter-refuses, musicbrainz-as-the-second-source, reverse-lookup-via-sparql, store-p31-and-rederive-kind-at-projection, source-adapter-spi, layering-and-archunit, assertion-log-source-of-truth, wikidata-identity-and-vocabulary, stand-in-identifiers-cannot-be-allocatable, mikado-method-for-changes, record-architecture-decisions, what-an-adr-may-quote]
---
# 61. The MBID bridge returns classes, so `neighbors()` becomes safe to fill

## Context

[ADR 55](0055-what-the-musicbrainz-adapter-refuses.md) declined issue #143 — filling
`ExpandResult.neighbors()` from the MusicBrainz response — and named its own successor while doing
so. Issue #163 is that successor.

**The shape of the problem.** `MusicBrainzSourceAdapter` returned no `neighbors()`, so
`SegueService.expandEntity` fell back to `EntityResolver.fetch`, one Wikidata Action API round trip,
for each newly discovered neighbour. #143 proposed paying for those neighbours out of the
MusicBrainz response, which already carries an MBID, a name and an `artist.type`. ADR 55 refused
that on a measured trade — more nodes lose their classes than round trips are saved — and that ADR
remains the authority on those figures, which are not restated here.

**The premise was re-tested rather than inherited.** Every relation target in the committed fixture
`src/test/resources/musicbrainz/artist-with-relations.json` carries an identifier, a name, a
sort-name, a type, a type-id, a country and a disambiguation, and nothing else: no tags, no genres,
nothing that is a Wikidata class. `type` is a `NodeKind`, not a `P31`, and `type-id` is a
MusicBrainz UUID whose only destination is the shape check inside `IngestService.apply` — a throw,
after the log entry has already been appended. **MusicBrainz cannot pay for the missing third of a
neighbour's identity, and no amount of parsing changes that.**

**The classes were already being fetched, on a call this source already makes.**
`WikidataMusicBrainzIdentity` spends one batched Query Service round trip per batch of MBIDs to turn
MBIDs into QIDs through P434. [ADR 36](0036-reverse-lookup-via-sparql.md)'s `ReverseClaims` asks a
structurally similar question about Wikidata's own neighbours and gets a label and the classes back
in the same response. The bridge can carry the same riders at the same round-trip count: more
columns on a call already made, not a new call.

**Why this is a new ADR and not an edit or a supersession.**
[ADR 1](0001-record-architecture-decisions.md) makes an Accepted ADR immutable and offers
supersession for a decision that changes. ADR 55 decided *two* things — `subgroup` (#142) and
`neighbors()` (#143) — and only the second changes here; marking it `Superseded by` would reopen a
`subgroup` question nothing in #163 measured. **This project had no precedent for a partial
reversal, so the convention is stated rather than left to be inferred: ADR 55 keeps status
`Accepted` and gains a dated amendment naming this ADR, and this ADR's front matter names ADR 55
under `related`, not `supersedes`.**

**Privacy.** This repository is public and the owner's interests are personal data
([ADR 33](0033-taste-layer-separation.md), [ADR 51](0051-what-an-adr-may-quote.md)). Every figure
below was observed offline against a committed fixture; no entity is named, and nothing here is
framed as the owner's taste.

## Decision

**`MusicBrainzIdentity` answers with a described identity rather than a bare QID, and
`MusicBrainzSourceAdapter` emits a neighbour only from an identity that says enough.**

- **The seam asks one question.** `MusicBrainzIdentity.identitiesFor` returns a `BridgedIdentity`
  per MBID and `qidsFor` is retired, so no implementor can answer half the question. A
  `BridgedIdentity` is deliberately **not** a `NodeAssertion`: the seam mints no `Provenance`, which
  issue #147 located in the adapter next to the guards on every string that goes into one.
- **The widened query lives in `app`.** `WikidataMusicBrainzIdentity` carries the label service and
  the class pattern on its existing batched query, groups rows per item — the optional class and the
  label service both multiply rows, so a row is not an entity — and derives the kind with
  `KindMapper.fromInstanceOf`. That call is in `app` because [ADR 32](0032-layering-and-archunit.md)
  forbids `musicbrainz` importing `wikidata`, and `app` is the only package allowed to see two
  adapters at once. **The query text and the batch size are that class's javadoc to state; read them
  there rather than here.**
- **The guard is the decision.** `MusicBrainzSourceAdapter.describes` emits a neighbour only when
  the identity carries at least one class *and* a real label. Anything less is omitted and the
  caller's `fetch` happens exactly as before. Both halves are erasure rather than fastidiousness:
  `SegueService` prefers an adapter's neighbour to a fetch and records it whether or not the node
  already exists (issue #55), and `TinkerGraphStore.upsertNode` is last-writer-wins on `instanceOf`,
  empty included and deliberately. That method's javadoc is the authority on the argument; this ADR
  is the authority on why the guard exists at all.
- **A label that is the bare QID is not a label.** `wikibase:label` answers with the QID itself when
  no English label exists, and believing it fills the graph with nodes named after their own
  identifiers. `WikibaseLabels.believable` is the single rule both `ReverseClaims` and the bridge
  apply; a neighbour it refuses is left undescribed, so the fetch still happens.
- **An unreadable class id makes the whole identity undescribed.** `BridgedIdentity.describing` is
  the producer's factory and *drops* such a row; the constructor *throws*. The two are not
  interchangeable, because the adapter catches only `MusicBrainzIdentityUnavailableException` and
  `SegueService.expandEntity` wraps `expand` in no `try`, so a throw inside a producer aborts a
  whole expansion across every adapter. **Shortening the class list silently was refused for a
  second reason:** [ADR 42](0042-store-p31-and-rederive-kind-at-projection.md) keeps the raw classes
  so a kind can be re-derived offline, and a shortened list would overwrite a complete one and then
  be re-derived from — wrongly, confidently, and unmarked — at every future improvement to the
  whitelist. That is #143's erasure in miniature, which is the one thing this change exists not to
  do. Undescribed costs one `fetch`, the fallback that already exists, which reads the complete list
  from Wikidata directly.
- **`ArchitectureTest.bridgedIdentitiesAreBuiltThroughTheirFactory` keeps producers off the
  constructor**, so the drop cannot be bypassed by a bridge that means well. `ArchitectureTest` is
  the authority on what that rule says and what it does not cover.
- **The neighbour claim is stamped `"wikidata"`; the edge stays `"musicbrainz"`.** The kind, label
  and classes are Wikidata's facts, fetched on the bridge's own round trip. `SourceAdapter.id()`'s
  javadoc is scoped to `assertions()` for this reason. `EdgeRecord.corroboration()` counts distinct
  sources per **edge**, so neither stamp manufactures corroboration — checked against
  `CorroborationAcrossSourcesTest` on 2026-09-02 rather than reasoned about.

### What was observed, and when

Stated as dated observations, because each is a measurement of one tree on one day and not a
standing property.

**On 2026-09-02, over the committed fixture** — 22 of its 24 relations mappable, over 22 distinct
target MBIDs — a fixture-driven `expandEntity` with a counting resolver spent **22**
`EntityResolver.fetch` calls before the change and **0** after, at **one** bridge round trip in both
cases. With the bridge describing none of them it spent 22 again; describing exactly half of them,
11. `NeighbourFetchCountTest` is that measurement and carries its own positive controls: every row
also asserts that the edges landed and that every neighbour node carries its class, so the count
cannot fall because the guard silently swallowed neighbours or the expansion stopped producing
anything. The counting resolver was proved able to fail before any row was believed.

**On the same day the widened request line was re-measured**, not reasoned about: the label service
and the class pattern cost a fixed number of bytes and nothing per MBID, so the line stays linear in
the batch. Measured 2026-09-02 it was `351 + 43n` bytes against an 8,192-byte request-line ceiling,
and the shipped batch size was left unchanged with headroom to spare.
`WikidataMusicBrainzIdentity`'s javadoc carries the table and is the authority on the current
figures.

### The class-shrinking exposure, and why it does not arise here

The open question this change was expected to accept was that a truthy `wdt:P31` returns fewer
classes than `ClaimMapper.instanceOf` reads from full statements, so a bridge-described refresh
could *shrink* an existing node's `instanceOf` — a milder cousin of #143's erasure, with
`ReverseClaims` as the accepted precedent since ADR 36.

**It was closed rather than accepted, and the two halves of that rest on different evidence.**
Parity with `ClaimMapper` holds **by the query's shape, not by observation**: the bridge's template
reads the full `p:P31/ps:P31` statements the forward mapper reads, so a refresh through this path
cannot return fewer classes than a `fetch` would. That is an argument about the query and it is
fenced as one — `WikidataMusicBrainzIdentityTest` asserts the shipped template's text contains
`p:P31/ps:P31`, so the shape cannot be narrowed silently; no response was compared against a fetch's
class list to observe the parity. **What was measured** is the other half: that carrying full
statements fits inside the request-line ceiling at the shipped batch size, which is why the choice
needed no fallback. `ReverseClaims` still carries the truthy exposure and is unchanged by this
decision; if the batched query ever cannot carry full statements within the ceiling, the answer is a
smaller batch and a measurement, not a silent fall back to the truthy form.

## Alternatives considered

- **#143 as filed — `neighbors()` from `artist.type` alone.** Refused by ADR 55 on a measured count.
  Nothing here reopens it: `MusicBrainzNeighbourIdentityTest`'s findings are unedited and this
  change passes them, which is exactly the property ADR 55 said the bridge route would have to have.
- **Read the classes from MusicBrainz's own fields.** Measured against the committed fixture and
  there is nothing to read — no tags, no genres, and a `type-id` whose only destination is the throw
  inside `IngestService.apply`, after the log entry is written.
- **A second query for the classes.** Doubles the bridge's round trips to obtain data the existing
  query returns in the same response, and the round-trip count is the entire point of the change.
- **Return a `NodeAssertion` from the seam.** Would make `musicbrainz`'s seam mint a `Provenance`,
  which issue #147 deliberately put in the adapter beside the guards that check every string
  entering one. `BridgedIdentity` carries the facts and the adapter carries the attribution.
- **Call `KindMapper` from the adapter.** ADR 32 forbids it and an ArchUnit slice rule enforces it;
  the derivation is done in `app`, where seeing both adapters is legal.
- **Emit a neighbour with an empty `instanceOf` and let a later fetch refresh it.** #143's erasure
  with an extra step: the upsert happens first and `upsertNode` is last-writer-wins, so the window
  is not a window — it is the recorded state until something happens to look again.
- **Fix the refresh in `SegueService` so a class-less source cannot overwrite a described node.** ADR
  55 named this as real and explicitly not wrong. It addresses the erasure of existing nodes and
  leaves newly discovered ones class-less, and it changes `mcp`, outside the adapter. Once the
  classes ride the bridge there is nothing left for it to fix on this path, so it is not taken —
  again not because it is wrong.
- **Omit only the unreadable class and keep the rest of the identity.** Rejected for the
  re-derivation reason above: a silently shortened list overwrites a complete one and is then
  believed by every later projection.
- **Supersede ADR 55 outright.** Would reopen `subgroup` (#142), which this change neither measured
  nor touched, and would lose a decline that is still correct on its own evidence.

## Consequences

- **`MusicBrainzSourceAdapter`'s javadoc stays the authority on the guard and on the whitelist**, as
  ADR 54 established and ADR 55 kept. This ADR carries the reasoning; the code carries the rules, and
  neither copies the other's table.
- **ADR 55 keeps status `Accepted`.** Its `subgroup` decline stands untouched; only its `neighbors()`
  decline is reversed, by this ADR, and its dated amendment says so.
- **Three layers now refuse a bad identity, and they refuse different things.** `BridgedIdentity`
  checks the shape of the QID and of every class id; each producer withholds a description it cannot
  build, answering `undescribed` — **the entry survives and the edge assertion still lands; only the
  node claim is withheld, and the neighbour costs the `fetch` it would have cost anyway**; and the
  adapter's guard asks only whether the identity says *enough*. The adapter's own shape check is
  consequently defence in depth rather than the live check — a well-formed `BridgedIdentity` cannot
  carry a non-QID past it.
- **The saving is a property of this fixture and this graph, not of MusicBrainz.** ADR 55's live
  sample is the only measurement here of the real graph, and it is cited rather than re-run.
- **A future source that supplies `neighbors()` inherits the rule, not the number, and the rule is
  completeness.** An adapter may volunteer identity only where it can state a description as full as
  the one a `fetch` would record — a real label and the whole class list, not merely a non-empty one
  — and must emit nothing otherwise, letting the `fetch` happen. The developer guide's "Adding a
  source adapter" chapter now says so.
- **A narrower described class list would overwrite a fuller one, and nothing on this path stops
  it.** Probed offline while this change was reviewed: a neighbour already holding two classes,
  re-described by a bridge with one and a believable label, came back holding one —
  `upsertNode` is last-writer-wins (ADR 42's hazard) and `describes` tests non-empty rather than
  complete. It cannot arise on the shipped bridge, whose template reads full statements, and
  `WikidataMusicBrainzIdentityTest`'s assertion on that template text is what holds it there; the
  exposure is a *future* implementor of the seam, and it is accepted knowingly rather than guarded
  against a second time.
- **Nothing in `./gradlew check` needs the network, and nothing in it reads `~/.segue/segue.db`.**
  The measurement is fixture-driven and offline, and the `@Tag("live")` tests stay excluded.
