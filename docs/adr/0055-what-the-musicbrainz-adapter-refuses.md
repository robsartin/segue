---
status: Accepted
date: "2026-08-30"
topic: what-the-musicbrainz-adapter-refuses
tags: [project, ingest, extensibility, privacy]
supersedes: []
related: [musicbrainz-as-the-second-source, wikidata-identity-and-vocabulary, award-received-as-the-first-non-collaboration-edge, store-p31-and-rederive-kind-at-projection, assertion-log-source-of-truth, reverse-lookup-via-sparql, quarantine-model-generated-assertions, taste-layer-separation, mikado-method-for-changes, what-an-adr-may-quote]
---
# 55. What the MusicBrainz adapter refuses — `subgroup` and `neighbors()`, each declined on a count

## Context

[ADR 54](0054-musicbrainz-as-the-second-source.md) shipped the MusicBrainz adapter and filed ten
issues, two of which it deliberately left undecided: **#142**, whether the `subgroup` relation
should map to an edge type, and **#143**, whether the adapter should return
`ExpandResult.neighbors()` and spare `SegueService` a `EntityResolver.fetch` per newly discovered
neighbour.

Both issues asked for the same thing first: **a measurement, before any code**. #142 because "a
relation type nobody's graph contains is not worth a vocabulary decision"; #143 because the fetch it
proposes to save is gated on `isNew`, and a neighbour already in the graph costs nothing today.

Both measurements were taken on 2026-08-30 and **both refuse the change**. They are recorded
together because they came out of one probe over one sample, not because they lose on the same
grounds — they do not, and the two Decision sections below are kept separate for that reason.

**A decline carries a higher burden than a fix, because nothing downstream will ever exercise it.**
That is why the method is written out here rather than only the conclusion, and why #143's decline
also ships a test (`MusicBrainzNeighbourIdentityTest`) that was watched red against the change it
declines.

**Privacy.** This repository is public and the owner's interests are personal data
([ADR 33](0033-taste-layer-separation.md), [ADR 51](0051-what-an-adr-may-quote.md)). Every figure
below is an aggregate; no entity is named, and nothing here is framed as the owner's taste.

## The probe both decisions rest on

Stated once, because both sections cite it.

- **Instruments: the shipped ones.** `MusicBrainzClient`, `WikidataMusicBrainzIdentity`,
  `MusicBrainzSourceAdapter` and — for #143 — the production `WikidataSourceAdapter`, driven as a
  scratch `liveTest` against the real `ws/2` and the real Query Service at MusicBrainz's ~1 request
  per second. What it counted is therefore what the adapter would have seen, not what a
  reimplementation of it would have.
- **Sample: 200 seeds from the graph's own `PERSON` and `GROUP` nodes**, 100 of each, ordered by a
  deterministic pseudorandom function of the QID rather than by any property that would bias
  towards music. `GROUP` is over-sampled on purpose: it is well under a tenth of the eligible
  population and it is the only half where `subgroup` can arise at all.
- **120 of the 200 bridged to MusicBrainz through P434** — 81 of 100 `GROUP`, 39 of 100 `PERSON` —
  and returned **959 artist relations** between them.
- **The graph was read from a copy of the assertion log, never from the live database.** Where node
  kind is used below it is the `node_kind` on the latest node claim in that log, not the kind
  `GraphProjector` re-derives at projection time; the two can differ, and no argument here turns on
  a case where they would.
- **The sample measures what *this* graph would gain.** It is not a measurement of MusicBrainz's
  population and must not be cited as one.

## Decision

### `subgroup` maps to nothing — two relations in 959, and one edge (#142)

**The census of those 959 in full**, because the shape of the tail is what says whether the
whitelist is missing anything:

| relation type | count |
| --- | --- |
| `member of band` | 828 |
| `instrumental supporting musician` | 43 |
| `tribute` | 18 |
| `sibling` | 13 |
| `parent` | 11 |
| `supporting musician` | 9 |
| `collaboration` | 8 |
| `married` | 6 |
| `vocal supporting musician` | 4 |
| `is person` | 4 |
| `artist rename` | 4 |
| `involved with` | 3 |
| `founder` | 3 |
| **`subgroup`** | **2** |
| `named after artist` | 2 |
| `teacher` | 1 |

**`subgroup` is two relations, both on one seed, and one edge.** Both carry a direction and a
UUID-shaped target, so both would pass `isMappable`; the two targets are distinct, and **one
resolved to a QID** through the bridge. The other falls in the 49% ADR 54 measured as ADR 22's
identity clause declining to reach. So admitting `subgroup` would have produced **exactly one edge**
on this sample, in exchange for a permanent vocabulary commitment.

**And the capability it would buy is not missing.** This is the claim that most needed checking, and
checking it changed the argument. #142 is titled "the one relation that would give `MEMBER_OF`
between two groups", which is true of MusicBrainz and false of the graph: the log copy already holds
**136 `MEMBER_OF` and 34 `PART_OF` assertions whose endpoints are both `GROUP`** — 162 distinct
pairs, 2 of which carry both codes. **Every one of the 170 has `source_id` `wikidata`.** Group-in-
group is a shape this graph has had all along, from P463 and P361 stated directly, and `subgroup`
would add one edge to it.

### The adapter returns no `neighbors()` — the saving is under half of what #143 assumed (#143)

#143's premise is that "every MusicBrainz-discovered neighbour costs a Wikidata round trip that the
MusicBrainz response had already paid for". **The response has paid for two thirds of it.** The
MBID, the name and `artist.type` are all in the response — #143 is right that the javadoc's earlier
claim to the contrary was false — but `instanceOf` is not, and cannot be: MusicBrainz classifies an
artist as `Person` or `Group` without stating Wikidata classes.

**The `isNew` figure the issue asked for, which is the first half of why the saving is smaller than
it looks.** Of the 120 bridged seeds, 90 produced at least one neighbour that resolved to a QID —
**461 resolved neighbours in total**, and the fetch is gated on `isNew`:

| what the neighbour was | count | share | fetch spent today? |
| --- | --- | --- | --- |
| already in the graph | 203 | 44% | no — `isNew` is false |
| new, but described by Wikidata's own reverse pass in the same call | 44 | 10% | no — `described` wins |
| **new and undescribed** | **214** | **46%** | **yes — this is the whole saving** |

Per expansion the saving is a **median of 1** fetch, p90 of 4, max of 54. Separately, the shared
`maxNewEdges` bound (ADR 54's GAP 3) would have discarded MusicBrainz's assertions on only 2 of the
90 seeds at the shipped bound of 200, so the bound is not what limits this.

**The second half is that the fetch is not pure cost — it is how a node gets its classes.**
`SegueService` prefers an adapter's neighbour to a fetch and records it **whether or not the node
already exists** (issue #55), and `TinkerGraphStore.upsertNode` writes `instanceOf` on every upsert,
empty included and deliberately so — its own comment says a later claim stating no classes must not
leave an earlier claim's behind. So a MusicBrainz `neighbors()` would not merely decline to add
classes:

- the **214** new neighbours would be created with an empty `instanceOf` instead of the classes
  `WikidataEntityResolver.fetch` reads from `ClaimMapper.instanceOf`; and
- of the 203 already in the graph, **58 (57 distinct)** would not have been described by Wikidata in
  the same call, so MusicBrainz's class-less claim would be the one recorded for them — and **all 57
  carry a non-empty `instanceOf` today, which the upsert would erase.**

`PathRanking.isHub`, `CandidateSweep`, `rate/Card`, `DotWriter` and `GraphMlWriter` all read that
field, and [ADR 42](0042-store-p31-and-rederive-kind-at-projection.md) is the decision that the log
keeps the raw classes so a derivation can be revisited at all. **271 nodes degraded to save 214
round trips is the trade, and it is the wrong way round.**

**Watched red, not reasoned.** The adapter was changed to emit a `NodeAssertion` per resolved
neighbour — `artist.type` read into `ArtistRelation`, `Person`/`Group` mapped onto `NodeKind` — and
both preservation tests failed with `Expecting actual: [] to contain exactly ["Q5"]`. The change was
then reverted, per [ADR 4](0004-mikado-method-for-changes.md). **The failure was the erasure and not
a throw**, which matters because the trap ADR 54's design note documents as GAP 7 is a throw:
`NodeAssertion.toNode()` runs `Qid.looksLikeAQid` over every `instanceOf` element inside
`IngestService.apply`, after the log entry is written. An **empty** list has no element to fail on,
so that path is safe, and `MusicBrainzNeighbourIdentityTest`'s third test asserts exactly that. GAP
7 is not what makes this a bad trade; GAP 8 is.

## Alternatives considered

### For `subgroup` (#142)

- **`P361` / `PART_OF`.** #142 calls it "arguably the better fit" for "this act is part of that
  act", and `EdgeTypes` registers it (`EdgeType.direct("PART_OF", "P361", "part of")`), so no code
  would be invented. **It loses on evidence rather than on meaning.**
  [ADR 38](0038-award-received-as-the-first-non-collaboration-edge.md) is this project's precedent
  for admitting a property and its criterion is a measurement — P166 admitted at a measured hub of
  127, P106 rejected at 35,977, P136 at 16,552. One edge is no measured return, and the commitment
  does not expire: [ADR 19](0019-assertion-log-source-of-truth.md)'s log is append-only, so edges
  written under a mapping survive the mapping being reconsidered and unwinding one is a retraction
  pass. **If this is ever reopened, this is the candidate to reopen it with.**
- **`P463` / `MEMBER_OF`.** #142 describes it as "semantically close but a membership claim about a
  person", and **that objection is wrong** — the log copy holds 136 Wikidata `MEMBER_OF` assertions
  between two `GROUP` endpoints, so P463 group-to-group is ordinary here. It loses on a different
  and better-evidenced ground: **the source does not distinguish the two candidates, and Wikidata
  does.** Over group-to-group pairs Wikidata itself splits 136 P463 against 28 P361; MusicBrainz
  says only `subgroup`. Picking either code makes the adapter assert, at ADR 23's 0.80, a
  distinction its source never drew — and `EdgeRecord.corroboration()` counts distinct `sourceId`s
  **on one edge**, so the choice manufactures corroboration with one Wikidata coding while
  withholding it from the other. ADR 54 shipped that count as the first real evidence two sources
  agree; this would make it evidence that two sources appear to agree.
- **Either code, accepting a duplicate coding over the same pair.** The measured precedent is
  against it and is in `EdgeTypes.HAS_PART`'s own javadoc: issue #33 found 4 of 23 `HAS_PART` edges
  shadowing a `MEMBER_OF` over the same pair, "which is two identical routes through `find_paths`
  and two slots against one `maxNewEdges` bound", and the fix was to register `HAS_PART`
  `fallbackOnly`. Two group-to-group pairs in the log copy already carry both codes today.
- **Map it provisionally and revisit when the graph grows.** "Provisional" is not a property a
  written edge has, for the append-only reason above. Declining costs one line to reverse; admitting
  costs a retraction pass.
- **Decide nothing and write no ADR.** The state #142 was filed to end. The whitelist's silence
  would stay an absence, and the next reader would have to rediscover both the candidates and the
  count.

### For `neighbors()` (#143)

- **Emit them anyway and accept the class loss.** The trade is quantified above: 271 nodes degraded
  against 214 round trips saved, at a median of one per expansion. ADR 54's GAP 8 already judged an
  empty `instanceOf` "harmless" for MusicBrainz on the ground that artists are not recognition
  institutions, and that judgement holds for the hub rule and **only** for the hub rule — it did not
  consider erasing classes a node already has, nor `rate/Card`, `DotWriter` or ADR 42's
  re-derivation.
- **Emit them and fix the refresh in `SegueService` so a class-less source cannot overwrite a
  described node.** This is a real fix and it addresses 57 of the 271; it leaves the 214 new nodes
  class-less, and it changes `mcp`, which is outside the adapter. Not taken here, and not because it
  is wrong.
- **Widen the bridge to return classes alongside QIDs — the route that actually wins.** One batched
  Query Service round trip per 100 neighbours, which is the shape
  [ADR 36](0036-reverse-lookup-via-sparql.md)'s `ReverseClaims` already uses for Wikidata's own
  neighbours, instead of one fetch each. It collects the saving with none of the loss. It widens
  `MusicBrainzIdentity` and the `app` class behind it, so it is a change of its own rather than a
  line in the adapter. **This is the recommended successor to #143, not a consolation.**
- **Leave the javadoc's original reason in place.** Rejected outright: it stated that the response
  does not carry the neighbour's type, and it does. #143 found that by reading the committed fixture.
  A wrong reason for a right decision is worse than no reason, because it stops the next reader
  looking.

## Consequences

- **Group-in-group is unreachable *from MusicBrainz*, and is not otherwise unreachable.** Wikidata's
  direct P463 and P361 claims and ADR 36's reverse pass continue to produce it — 170 such assertions
  over 162 distinct pairs in the log copy at the time of measurement.
- **`MusicBrainzSourceAdapter`'s javadoc stays the authority on the whitelist and on `neighbors()`**,
  as ADR 54 established, and cites this ADR rather than restating the counts: a table copied into
  prose goes stale silently.
- **ADR 22 clause 3 remains unforced by this source**, which is what ADR 54 chose it for. This
  decision is therefore still no evidence about whether the vocabulary rule survives a source that
  does force it.
- **The triggers for reopening are stated, so "revisit later" is not a wish.** For `subgroup`:
  re-run the census, and if it reaches edges in the tens per hundred bridged group seeds — two
  orders of magnitude above what was measured — `PART_OF` is the candidate. For `neighbors()`: it is
  not a threshold but a design change, the bridge that returns classes.
- **`MusicBrainzNeighbourIdentityTest` is what stops #143 being re-implemented by accident**, and it
  fails on the erasure rather than on the absence of an optimisation — so it does not forbid the
  bridge-with-classes route, which passes it.
- **`subgroup` ships no test, and that is deliberate rather than an omission.** A decision to write
  no code has no behaviour to assert; what would be observable if it were false is a `subgroup` edge
  in the graph, and the whitelist is a two-entry `Map.of` a reader checks faster than a test could.
  #143's decline is the opposite case — it *is* observable, so it is tested.
- **Nothing in `./gradlew check` needs the network and nothing in it reads `~/.segue/segue.db`.**
  The probe was a scratch `liveTest`, left nothing behind, and read a copy of the log rather than
  the live database.
