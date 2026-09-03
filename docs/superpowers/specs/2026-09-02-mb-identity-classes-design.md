# The MBID bridge returns classes, and `neighbors()` becomes safe to fill

Issue #163, the successor #143 named when ADR 55 declined it. Written 2026-09-02.

## What #143 could not collect, and why

`MusicBrainzSourceAdapter` returns no `ExpandResult.neighbors()`, so `SegueService.expandEntity`
falls back to `EntityResolver.fetch` — one Wikidata Action API round trip — for each newly
discovered neighbour. #143 proposed filling `neighbors()` from the MusicBrainz response, which
already carries the neighbour's MBID, name and `artist.type`.

ADR 55 refused it on a count. The response pays for **two thirds** of a neighbour's identity; the
missing third is `instanceOf`, the raw `P31` ADR 42 keeps so a kind can be re-derived. `SegueService`
records an adapter's neighbour **whether or not the node already exists** (issue #55), and
`TinkerGraphStore.upsertNode` writes `instanceOf` on every upsert, empty included and deliberately.
So a MusicBrainz `neighbors()` would have created **214 class-less nodes and erased the classes of 57
existing ones** to save 214 round trips. Watched red, then reverted:
`Expecting actual: [] to contain exactly ["Q5"]`.

**The failure was erasure, not a throw.** GAP 8, not GAP 7 — an empty `instanceOf` has no element for
`Qid.looksLikeAQid` to reject. `MusicBrainzNeighbourIdentityTest` holds all three findings and is the
guard this change has to pass rather than edit around.

The saving itself is real and measured: of 461 resolved neighbours across 200 seeds, 203 (44%) were
already in the graph, 44 (10%) were described by Wikidata's reverse pass in the same call, and **214
(46%) are the entire saving**, at a median of 1 fetch per expansion.

## Where the classes actually come from — measured, against the premise

**Not from MusicBrainz.** The premise that the MusicBrainz response could yield classes was tested
against the committed fixture (`src/test/resources/musicbrainz/artist-with-relations.json`) rather
than assumed. Every one of its 24 relation targets carries exactly `id`, `name`, `sort-name`, `type`,
`type-id`, `country`, `disambiguation` — **no `tags`, no `genres`, and nothing that is a Wikidata
class**. `type` is `Person` (22) or `Group` (2), which is a `NodeKind`, not a `P31`. `type-id` is a
MusicBrainz UUID (`b6e035f4-…` for Person); putting it in `instanceOf` is precisely GAP 7 — it builds
a `NodeAssertion` cleanly and throws inside `IngestService.apply`, after the log entry is written.

**They come from the bridge, in the round trip it already spends.** `WikidataMusicBrainzIdentity.qidsFor`
already asks the Query Service one batched SPARQL question per 100 MBIDs:

```sparql
SELECT ?item ?mbid WHERE { VALUES ?mbid { … } ?item wdt:P434 ?mbid . } ORDER BY ?mbid ?item
```

`ReverseClaims` asks a structurally identical question for Wikidata's own neighbours and gets label
and classes back in the same response, via `OPTIONAL { ?other wdt:P31 ?type }` and
`SERVICE wikibase:label`. **The bridge can carry the same two riders at the same round-trip count.**
That is the whole change: no new network call, more columns on the call already made.

## The decision

**`MusicBrainzIdentity` returns a described identity rather than a bare QID, and the adapter fills
`neighbors()` only from identities that carry both a real label and at least one class.**

- The seam gains `identitiesFor(Collection<String>)` returning `Map<String, BridgedIdentity>`, and
  `qidsFor` is retired once every implementor has moved. `BridgedIdentity(String qid, NodeKind kind,
  String label, List<String> instanceOf)` is a new record in `musicbrainz` — **not** a `NodeAssertion`,
  so the seam never mints a `Provenance` (issue #147 put that in the adapter on purpose).
- `WikidataMusicBrainzIdentity` widens `BATCH_TEMPLATE` with the `OPTIONAL` P31 and the label service,
  groups classes per item (the `OPTIONAL` and the label service both multiply rows, so a row is not an
  entity — `ReverseClaims` says so and keys accordingly), derives the kind with
  `KindMapper.fromInstanceOf`, and applies `ReverseClaims`' own label rule: **`wikibase:label` hands
  back the bare QID when no English label exists, and believing it fills the graph with nodes called
  `Q121998451`** — such a neighbour is left undescribed so the fetch still happens.
- `MusicBrainzSourceAdapter` emits a neighbour **only when `instanceOf` is non-empty and the label is
  real**. Anything less is omitted, and `SegueService` falls back to `fetch` exactly as today. This is
  the guard that makes the change non-erasing, and it is what ADR 55's two tests actually assert.
- The neighbour claim carries `Provenance("wikidata", qid, assertedAt, 1.00)` — byte-identical to what
  `ReverseClaims` and `WikidataEntityResolver.fetch` produce, because it is the same claim from the
  same source. **This is the one place the change is visible in the log, and it is an open question**
  (see below).

`KindMapper` is called in `app`, never in `musicbrainz`: ADR 32's adapters-are-siblings fence forbids
`musicbrainz` importing `wikidata`, and `app` is the only package ADR 32 lets see two adapters at once.

**The SPI is untouched.** ADR 25's two interfaces are `SourceAdapter` and `EntityResolver`; neither
changes, and `ExpandResult.neighbors` already exists and is already documented as an optimisation an
adapter may supply. `MusicBrainzIdentity` is an internal seam declared in `musicbrainz` and implemented
in `app`, with **six implementors**: `WikidataMusicBrainzIdentity` and five test doubles
(`StubIdentity`, `BridgeThatCannotAnswer`, `UnavailableIdentity`, `UnavailableOnBatch`,
`RecordingIdentity`).

**The two-pass ingest mechanism is not touched.** All three couplings that must stay coupled
(`ClaimMapper.BY_PROPERTY` ≡ `EdgeTypes`; `reverseProperties()` ≡ the forward whitelist minus
fallback-only; direction as one rule both ways) live inside `wikidata`, as does the fallback-only
subtraction. This change adds columns to a query in `app` and a guard in `musicbrainz`, and must leave
all four alone.

## How the saving is measured, offline

**Its own task, before the widening, so the number is observed rather than predicted.** A
fixture-driven `SegueService.expandEntity` in the shape `MusicBrainzNeighbourIdentityTest` already
builds — real `IngestService`, real `TinkerGraphStore`, the committed fixture through
`MusicBrainzClient.readingFrom` — with a **counting** `EntityResolver` and a **counting** identity
stub. No network: `check` reaches none, and the `@Tag("live")` tests stay excluded.

The fixture's denominator is exact: **22 of its 24 relations are mappable** (`member of band`, a
forward/backward direction, an MBID target), over **22 distinct target MBIDs, all `Person`**.

| | `EntityResolver.fetch` calls | bridge round trips |
| --- | --- | --- |
| today, 22 neighbours new | **22** | 1 |
| after, the bridge describes them | **0** | 1 |
| after, bridge describes none (no classes) | 22 | 1 |

**Positive controls, definition of done.** The "after" row is worthless without them:

- A neighbour the bridge resolves to a QID but **cannot** describe (empty `instanceOf`, or a label that
  is the bare QID) still costs exactly **one** fetch — so the count cannot fall because the guard
  silently swallowed neighbours.
- The edge is still recorded and the classes are still on the node — so the count cannot fall because
  the expansion stopped producing anything. `MusicBrainzNeighbourIdentityTest`'s existing edge
  assertion is exactly this control and is kept.
- The counting resolver is proved able to count: the "today" row is a real red-to-green observation,
  not an assumed baseline.

## Rejected

- **#143 as filed — `neighbors()` from `artist.type` alone.** Refused by ADR 55 on the count: 214 round
  trips saved against 214 class-less nodes created and 57 existing nodes' classes erased. Nothing here
  reopens it; the guard tests stay.
- **Read classes from MusicBrainz's own fields.** Measured against the committed fixture and there is
  nothing to read: no tags, no genres, and `type-id` is a MusicBrainz UUID whose only destination is
  GAP 7's throw.
- **A second query for the classes.** Doubles the bridge's round trips for data the existing query
  returns in the same response — and the round-trip count is the entire point of the change.
- **Return `NodeAssertion` from the seam.** Would make `musicbrainz`'s seam mint a `Provenance`, which
  issue #147 deliberately located in the adapter, next to the guards that check every string going into
  one.
- **Call `KindMapper` from the adapter.** ADR 32 forbids it, and the ArchUnit slice rule enforces it.
- **Emit a neighbour with an empty `instanceOf` and let the fetch refresh it later.** This is #143's
  erasure with an extra step: the upsert happens first and `upsertNode` is last-writer-wins.

## Open questions for the owner

1. **Which `sourceId` does the neighbour claim carry?** The data is Wikidata's (P434 + P31 + label), and
   `ReverseClaims`/`fetch` both stamp `"wikidata"`. But `SourceAdapter.id()`'s javadoc says it is *"the
   sourceId every assertion this adapter emits will carry"*, and this adapter's id is `"musicbrainz"`.
   `"wikidata"` keeps the log honest and makes the change invisible downstream; `"musicbrainz"` matches
   that sentence and misattributes Wikidata's classes. **Recommended: `"wikidata"`**, with the javadoc
   sentence amended to say it governs `assertions()`, not `neighbors()`. `EdgeRecord.corroboration()`
   counts distinct sources per **edge**, so neither choice manufactures corroboration — but this should
   be confirmed against `CorroborationAcrossSourcesTest` in Task 1, not assumed.
2. **`wdt:P31` is truthy; `ClaimMapper.instanceOf` reads the full statements.** The bridge could
   therefore return *fewer* classes than `fetch` would for the same entity, shrinking an existing node's
   `instanceOf` on refresh — a milder cousin of #143's erasure. `ReverseClaims` has had exactly this
   exposure since ADR 36 and it is accepted precedent, so this change is no worse than the shipped
   behaviour; worth stating in the ADR rather than discovering later.
3. **ADR 55 is immutable and this reverses half of it.** Its `subgroup` half (#142) still stands, so
   wholesale supersession would wrongly reopen it. **Recommended: a new ADR 61** for the reversal, plus a
   dated amendment on ADR 55 pointing at it and saying what new evidence changed.
4. **`MAX_MBIDS_PER_QUERY = 100` rests on measured arithmetic** (`180 + 43n` bytes; 100 MBIDs = 4,480,
   against an 8,192 request-line ceiling). The javadoc anticipates this change — *"a batch whose safety
   does not depend on … the template never gaining a line"* — but the widened template must be
   **re-measured** and the figures updated, not reasoned about.

## Controller rulings (2026-09-02)

1. **Source id of the neighbour claim: `wikidata`.** The kind, label and classes are Wikidata's facts,
   fetched on the bridge's own round trip; the edge stays MusicBrainz's. `SourceAdapter.id()`'s javadoc is
   amended to govern `assertions()`. Task 1 reports, and does not choose, whether
   `CorroborationAcrossSourcesTest` is disturbed.
2. **Read full `P31` statements, not truthy ones.** The bridge uses the same statement shape
   `ClaimMapper.instanceOf` reads (`p:P31/ps:P31`), so a refresh can never shrink an existing node's
   classes; if the batched query cannot carry it within the request-line ceiling, that is a measured
   finding for the ADR, not a silent fallback to `wdt:`.
3. **ADR 55's partial reversal (new ADR 61 + dated amendment on 55) awaits the owner's ratification.**
   Tasks 2–5 do not start until then. Task 1 — the offline measurement with a counter proved able to
   fail — is knowledge either way and proceeds.
4. **`MAX_MBIDS_PER_QUERY` is re-measured** in Task 2, as the plan says; its javadoc figures are not
   inherited.

**Ratified by the owner, 2026-09-02:** proceed — new ADR 61 plus a dated amendment on ADR 55. Tasks
2–5 are unblocked.
