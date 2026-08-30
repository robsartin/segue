# Three source adapters, designed on paper — and what each one asks of the SPI

**Date:** 2026-08-30 · **Issue:** [#91](https://github.com/robsartin/segue/issues/91) · **Task 1 of 6**

Design only. No production code is written by this note, and that ordering is deliberate: building
one adapter first would settle the SPI's shape from whichever source turned out to be easiest.
Designing three exposes the gaps before any of them is committed to. The same argument as
[ADR 18](../adr/0018-graph-engine-gremlin.md) (two engines built, one chosen) and
[ADR 38](../adr/0038-award-received-as-the-first-non-collaboration-edge.md) (one property at a
time).

**Privacy.** This repository is public and the owner's ratings and interests are personal data
([ADR 33](../adr/0033-taste-layer-separation.md),
[ADR 51](../adr/0051-what-an-adr-may-quote.md)). Nothing here names an entity as his. Every entity
below is a probe chosen for this note to make a world fact about a public API checkable, and every
figure is either a count over public data or a property of code in this repository. Where an
argument would have been sharper with a name from the known-list, it is made weaker on purpose.

**Sources of fact.** The SPI is `port/SourceAdapter.java`, `port/EntityResolver.java`,
`port/ExpandResult.java`, `port/ExpandContext.java` and `port/SourceAdapters.java`; its two
production implementors are `wikidata/WikidataSourceAdapter.java` and
`wikidata/WikidataEntityResolver.java`. They are the authority on their own contents and are cited
rather than reproduced. The live probes below were run on 2026-08-30 against public endpoints with
a `User-Agent` of the same shape `WikidataClient` already sends — a name and the repository URL,
**no email address**, per [ADR 16](../adr/0016-privacy-and-data-handling.md)'s minimisation and
no-PII rules.

---

## What the SPI offers today, in one paragraph

`SourceAdapter` is three methods: an `id()` that becomes the `sourceId` on every assertion it emits,
a `supports(NodeKind)` predicate, and `expand(NodeRecord seed, ExpandContext ctx)` returning an
`ExpandResult`. `EntityResolver` is a separate interface with `search` and `fetch`, so a source that
can expand but not resolve is not forced to throw — that split is
[ADR 25](../adr/0025-source-adapter-spi.md)'s whole decision. `ExpandResult` carries the assertions,
an optional list of neighbour identities the source already knew, and two booleans saying whether
the source was unreachable and whether the result was cut short. `ExpandContext` carries one number.
`SourceAdapters` is a holder round `List<SourceAdapter>` and is already plural.

Three facts about the wiring matter to everything below and were checked in the tree rather than
assumed:

- `SegueService` holds **one** `EntityResolver` and **a list of** `SourceAdapter`s
  (`mcp/SegueService.java:75–78`). The expand path is pluggable; the resolve path is not.
- `NodeRecord` is `(qid, kind, label, instanceOf)` and carries **no external identifier**, so an
  adapter handed a seed has to obtain its own source-local id from the QID itself.
- `app/SegueConfiguration.java:101–106` builds the adapter list in one `@Bean` method, which is
  exactly the "plus a `@Bean` method" ADR 25 promised.

---

## MusicBrainz — music

### What it is authoritative for

Recorded music: artists, groups, releases, recordings, and the credited relationships between them,
at a depth Wikidata does not reach. Of the owner's stated domains
([ADR 53](../adr/0053-all-the-owners-interests-bounded-per-domain.md)) it reaches musicians and
their groups — which ADR 53 identifies as the domain already best served, so MusicBrainz *deepens*
rather than *widens*. That is the point of choosing it first: it is the source that tests the seam
with the fewest other things going wrong at once.

### Where its relations live — on the artist, and returned from both ends

This is the structural difference that makes MusicBrainz worth testing the SPI against.

Wikidata states most creative relations on the work, which is why
[ADR 36](../adr/0036-reverse-lookup-via-sparql.md) needed an entire SPARQL reverse-lookup pass:
the forward claims read off the entity are not the same set as the claims pointing at it, and
`WikidataSourceAdapter.expand` runs two passes and reconciles them.

MusicBrainz states a relation once, on the pair, with an explicit `direction` field, and
**`inc=artist-rels` returns it whichever end you ask.** Two probes, one request each:

| probe | entity type | relations returned |
|---|---|---|
| `artist/b10bbbfc-…?inc=artist-rels` | Group | 109, of which 9 `member of band` **backward** |
| `artist/ba550d0e-…?inc=artist-rels` | Person | 39, of which 13 `member of band` **forward** |

The same relation type, the same database, opposite `direction` values, one call each. **So a
MusicBrainz adapter needs no reverse pass at all** — and that is a genuinely different ingest shape
from the only one the SPI has ever carried.

Each relation object also carries `begin`, `end` and `ended`, so membership validity arrives on the
relation itself rather than as a statement qualifier. `AssertionRecord` has `validFrom`/`validTo`
fields waiting for exactly this.

### Identity, and the bridge to a QID

[ADR 22](../adr/0022-wikidata-identity-and-vocabulary.md)'s three clauses were read rather than
trusted. **Clause 2 is as the issue describes it**: source-local identifiers resolve to a QID *in
the ingest layer* and never appear in the domain, and the sentence names MBIDs first among its
examples. Worth noting that the same ADR's alternatives section already considered and rejected
MBIDs *as the spine*, on the grounds that they are excellent within music and useless outside it —
so clause 2 is not incidental to this source, it is the clause written with this source in mind.

The bridge exists **inside MusicBrainz**, which matters more than it sounds:

- **QID → MBID:** `ws/2/url?resource=https%3A%2F%2Fwww.wikidata.org%2Fwiki%2FQ1299&inc=artist-rels`
  returns the artist with a `wikidata` relation. One call.
- **MBID → QID:** `ws/2/artist/<mbid>?inc=url-rels` returns a `wikidata` relation whose
  `url.resource` is the item URL. One call.

**Neither call touches Wikidata.** That is load-bearing: ADR 32's layering makes adapters siblings,
and `ArchitectureTest.wikidataDoesNotDependOnOtherAdapters` and `.sqliteDoesNotDependOnOtherAdapters`
express "adapters are siblings, not collaborators" as build failures. A bridge implemented as a
SPARQL `VALUES ?mbid { … } ?item wdt:P434 ?mbid` query would be one round trip for a whole
expansion instead of one per neighbour — and would be an adapter depending on another adapter.
**The design takes the slower, architecturally clean route**, and the cost is stated plainly in the
gap list below.

The ~49% of artist-relation neighbours with no QID (measured in #91's 2026-08-29 comment) is not a
loss to route around. Its character — tributes, pseudonyms, billing variants — is why ADR 22 stays.

### Does it force ADR 22 clause 3? Nearly not.

Clause 3 is *"edge vocabulary is borrowed from Wikidata properties, not invented."* MusicBrainz's
`member of band` is P463, already registered as `MEMBER_OF`. So the relation that carries the value
needs no vocabulary decision, and the issue's claim holds.

**One relation type pushes on the vocabulary from a different direction, and should simply not be
whitelisted in v1.** MusicBrainz states `collaboration` as a first-class artist relation (5 of the
39 on the Person probe). `EdgeTypes.COLLABORATED_WITH` exists, but it is registered
`EdgeType.derived` with a null `wikidataProperty` and a javadoc that says *no source states this*.
Admitting a source-stated collaboration would either falsify that sentence or invent a code, and
Wikidata has no general collaboration property to borrow. **Not clause 3, but adjacent to it, and
avoided by leaving `collaboration` out of the first whitelist.**

### A whitelist is required, and not only for quality

The Group probe returned 109 relations of which **95 are `tribute`** — 87% noise by the issue's own
argument. The Person probe returned `parent` (6), `married` (3) and `sibling` (1): family relations
about living third parties. ADR 16 says collect only what a clear purpose requires; none of these
maps to a registered edge type and none should be requested. **The MusicBrainz adapter's whitelist
is a privacy control as well as a quality one**, which is not true of the Wikidata adapter's, where
the property whitelist is derived from `EdgeTypes` and the vocabulary does the filtering.

### What it needs from the SPI that the SPI does not offer

1. **A cost bound, not an edge bound.** `ExpandContext` carries `maxNewEdges` and nothing else.
   For Wikidata that number moves server-side as `LIMIT n+1` (ADR 36). For MusicBrainz the whole
   relation list arrives in one response and the real cost is the **one HTTP call per neighbour**
   the MBID→QID bridge needs, at MusicBrainz's ~1 request/second. `maxNewEdges` does bound that
   if the adapter truncates before resolving — but the SPI never says so, and an adapter that
   truncated after resolving would be correct by the interface and take two minutes on the Group
   probe.
2. **No way to say *which* n.** ADR 36's bound is a quality decision, not only a cost one: the n
   kept are the most-linked, ordered by `DESC(?sitelinks)`, and that ADR's issue-#71 amendment
   rests on connector density falling off exactly where the bound cuts. MusicBrainz relation
   objects carry no prominence signal — no sitelink count, no popularity, no ordering guarantee.
   **The quality property ADR 36 relies on is Wikidata-local and does not transfer**, and the SPI
   has no vocabulary for a source to say "I truncated arbitrarily" as distinct from "I truncated
   well".
3. **Nothing to build an HTTP client on.** `WikidataClient` owns the User-Agent, the retry loop and
   the `Retry-After` handling, and lives in `wikidata/`. MusicBrainz needs a *proactive* ~1 rps
   throttle rather than a reactive backoff — one of the two probe sequences here drew a
   "server is currently busy" response at roughly 1-second spacing. There is no shared client, and
   ADR 32 forbids reaching for the Wikidata one. Adding a source includes writing a whole HTTP
   client. That is not an SPI gap, but it is a cost ADR 25's consequences do not mention.

**What it does NOT need:** the `EntityResolver` half. Its seed arrives from the graph already
carrying a QID, and it can fill `ExpandResult.neighbors()` itself from the artist-rels response
plus one url-rels call each. Under ADR 25's own rule — *a source implements whichever it can
honour* — MusicBrainz should implement `SourceAdapter` only.

---

## Open Library — books

### What it is authoritative for

Books as works and editions, their authors, and free-text subjects. Of the owner's stated domains it
reaches the book collection —
[ADR 47](../adr/0047-main-subject-as-the-route-through-what-a-book-is-about.md)'s *What this cannot
reach* section is the authority on what Wikidata misses there, and it records that *Effective Java*
has no Wikidata item at all. A probe of Open Library's search returns two work records for that
title. **This is the domain where a second source adds coverage rather than depth.**

### Where its relations live — on the work, and as strings

Probed `works/OL6223299W.json` and `works/OL27448W.json`. A work record states:

- **`authors`** — a list of `/authors/OL…A` keys. A real link to a real record. Maps to P50,
  registered as `AUTHORED`.
- **`subjects`**, and on richer records `subject_people`, `subject_places`, `subject_times` — **free
  text, not identifiers.** The first probe's list mixes topical headings with a Library of Congress
  call number and a Dewey number in the same array. Maps in meaning to P921, registered as `ABOUT`.
- **`series`** — an `/series/OL…L` key. **`links`** — editorial URLs, one of them a Wikipedia
  article, chosen by a contributor rather than structured.

So the shape is: relations stated **on the work**, like Wikidata, but with the far end of most of
them being a **string rather than an entity**.

### Identity, and the bridge to a QID — this is where it breaks

- **Authors carry the bridge directly.** `authors/OL1607920A.json` has
  `remote_ids: {wikidata: "Q92992", viaf: …, isni: …}`. No round trip, no Wikidata dependency, no
  URL parsing. Better than MusicBrainz's.
- **It is sparsely populated, and Open Library duplicates people.** The search response returns two
  work records for the same title carrying two *different* author keys for the same person, and
  fetching the second (`authors/OL9595897A.json`) shows no `remote_ids` key at all. The bridge exists per record, not per person.
- **Works carry no bridge whatsoever.** Neither probed work record has `remote_ids`. There is no
  field for one.

**That last point is fatal in the direction the domain matters.** The books Open Library reaches
that Wikidata does not are, by definition, books with no QID — and ADR 22 clause 1 makes the QID the
identity. An adapter would emit `person AUTHORED work` where the work can never have a `toQid`.

This is not a soft edge. `NodeRecord`'s compact constructor calls `Qid.check`, so the claim fails
at `NodeAssertion.toNode()` inside `IngestService.apply`. The adapter would have to drop exactly
the works it was added to reach.

**So Open Library's blocker is clause 1, not clause 3** — the identity spine, which ADR 53 records
as deliberately deferred to #92 and which this note does not reopen.

### Does it force ADR 22 clause 3? No — established, not asserted

Both of the relations Open Library actually states have a registered Wikidata property already:
authorship is P50 and aboutness is P921, and `EdgeTypes` is the authority that both are registered.
`series` would need P179 ("part of the series"), which exists in Wikidata and is not registered here — so admitting it
would be a normal ADR 38-style one-at-a-time admission, not a vocabulary invention. **Nothing Open
Library states requires inventing a code.**

The vocabulary pressure it does create is one level down and is about *values*, not codes: an
`ABOUT` edge needs a QID on the far end, and Open Library gives a string. Resolving
`"Java (Computer program language)"` to a QID is a Wikidata search per subject — an adapter calling
another adapter's source again, and this time without a clean in-source bridge to use instead.

**Caveat on the scope of this probe.** I read one search response and two work records plus two
author records. Open Library also has editions, a subjects API and a bulk dump, and an adapter
design would want the editions layer probed too. The conclusion above rests on the work record
having no `remote_ids` field, which is a schema fact, not a sampling one.

### What it needs from the SPI that the SPI does not offer

1. **A representable entity without a QID**, which is a domain decision (#92) and not an SPI one.
   Until that is decided, the SPI cannot be blamed for this: `ExpandResult` could carry the
   assertion perfectly well, and `NodeRecord` would refuse it.
2. **A way to emit a claim whose object is a literal**, for subjects and for `subject_places`.
   `AssertionRecord` is `(fromQid, toQid, …)`; there is no shape for a string-valued claim.
   Every source in segue's future that is a cataloguing database rather than a knowledge base has
   this problem.
3. **The `EntityResolver` half is genuinely wanted here**, and cannot be had — see the gap list.

---

## OpenStreetMap — places

### What it is authoritative for

Physical features and premises, at a coverage no other open source matches. Of the owner's stated
domains it reaches restaurants — the domain ADR 53 records as **out of scope for Wikidata, and not
because Wikidata's entities are thin but because nothing they carry maps to a registered relation.**

### Where its relations live — mostly nowhere

Probed via Overpass: every `amenity=restaurant` node in a dense 0.02° × 0.04° urban box, chosen in
a city unconnected to anything in this project. **308 nodes, 110 distinct tag keys.** The census:

| tag | nodes carrying it |
|---|---|
| `name` | 305 |
| `cuisine` | 229 |
| `addr:street` | 261 |
| `website` | 102 |
| `brand` / `brand:wikidata` | 47 / 47 |
| `operator` | 5 |
| **`wikidata`** | **0** |
| **`wikipedia`** | **0** |

These are **attributes, not relations.** A cuisine is a string (`chinese`, `italian`, `pizza`). An
address is three strings. The single tag that points at another entity for more than a handful of
these nodes is `brand:wikidata` — and that QID is the *chain's*, shared by all 47.

OSM does have relations as a first-class data type, but for restaurant nodes they are boundaries and
routes, which say a premises is inside an administrative area. That is geometry, not a stated link.

### Identity, and the bridge to a QID — there is none

**Zero of 308 carry a `wikidata` tag.** Not "few". Zero. The 47 `brand:wikidata` values identify
the brand, so ingesting them would collapse 47 distinct premises onto a handful of chain QIDs and
assert that a restaurant *is* its chain.

Under ADR 22 clause 1, **an OSM restaurant is unrepresentable**, and this is the same wall #89 hit
from the Wikidata side, reached from the other direction with a much larger sample. ADR 53 concluded
that reaching this domain "requires a different source, which is #91's territory". **The measurement
above says a different source does not fix it either, because the obstacle is the identity spine
rather than the source.** That is a finding this note owes back to ADR 53, and it belongs in an
amendment rather than in an edit.

### Does it force ADR 22 clause 3? Yes, softly — and ADR 38 then vetoes what it forces

Establishing rather than asserting. To produce a route at all, an OSM restaurant needs one of:

- **location** — Wikidata has P131 (located in the administrative territorial entity). Exists,
  borrowable, **not registered**. I checked every registration in `EdgeTypes` — which is the
  authority — and none is a location property, confirming ADR 53's statement.
- **brand or operator** — Wikidata has P1716 (brand) and P137 (operator). Exist, borrowable, not
  registered.
- **cuisine** — Wikidata has P2012 (cuisine). Exists, is borrowable, and the far end is a string in
  OSM, so it inherits Open Library's value problem.

So clause 3 survives in the letter: **every relation OSM would need can be borrowed from an existing
Wikidata property rather than invented.** What OSM forces is not a vocabulary invention but a
*registration*, under ADR 38's one-at-a-time discipline.

**And ADR 38's own criterion rejects the one that would matter.** Its measured argument admitted
P166 at a hub size of 127 and rejected P106 at 35,977 and P136 at 16,552. A `LOCATED_IN` edge to a
city is that shape or worse — every premises in the city on one node — and the resulting route,
"these two restaurants are both in this city", is exactly the coincidence ADR 36's issue-#71
amendment called a route that means nothing.

**Worse: the hub rule could not demote it.** `PathRanking.isHub` has two halves, and I read both.
`isBusyConcept` requires `node.kind() == NodeKind.CONCEPT`; a city maps to `PLACE`.
`isRecognitionInstitution` reads `node.instanceOf()`; an OSM-sourced node states no Wikidata
classes, so that list is empty. **Both halves of the hub rule are unavailable for an OSM place**,
and a city hub would rank as a genuine explanation. ADR 31's issue-#88 amendment refused to
generalise the hub rule; this is the case that refusal leaves open.

### What it needs from the SPI that the SPI does not offer

1. **The same string-valued-claim shape Open Library needs**, for `cuisine`.
2. **A hub judgement that does not depend on Wikidata-shaped data** — see the gap list. This is not
   in the SPI at all; it is in `domain`, which is where it is most awkward.
3. **Nothing else.** OSM's problem is upstream of the SPI in both directions: identity refuses its
   entities and the vocabulary refuses its relations. **An OSM adapter should not be built.**
   Recording that conclusion is worth more than the adapter would have been, and it is the same
   answer #89 exists to have made sayable.

---

## What three sources say about the SPI

Every item below was checked in the tree. Verdicts are stated plainly, including where the answer
is that the SPI is fine.

### The single-`EntityResolver` asymmetry: unused capacity, not a blocker — with a fuse

`SegueService` holds one `EntityResolver` (`mcp/SegueService.java:75`) and calls it in three places:
`search` for the `search_entities` tool, `fetch` for `add_entity`, and `fetch` as the fallback for a
neighbour no adapter described. `SourceAdapters` is a list; the resolver is not.

**For MusicBrainz this blocks nothing.** Expansion never needs the resolver: the seed arrives from
the graph with a QID, and the adapter fills `ExpandResult.neighbors()` itself. The neighbour
fallback firing against Wikidata is not merely tolerable but *correct*, because identity in segue
**is** a QID and Wikidata is the authority on those. The only thing genuinely lost is the ability to
search MusicBrainz by name from the MCP surface, and Wikidata's search already covers music.

**The fuse is that this is only true while ADR 22 clause 1 holds.** The moment a source's entities
are allowed to exist without a QID — the question #92 carries and ADR 53 deliberately declines —
`add_entity` and `search_entities` can reach exactly one source, and the singleton becomes a hard
blocker on the same day. The asymmetry is safe because the identity spine is doing the work, not
because the shape is right.

**Recommendation:** leave it. Do not pluralise the resolver in this issue; it would be structure
ahead of a need, and #92 is where the need would arrive.

### GAP 1 — ADR 33's privacy fence is a literal package list, and a new adapter falls outside it

`ArchitectureTest.theWorldFactLayerNeverTouchesAffinity` forbids `..ingest..`, `..tinker..`,
`..jena..` and `..wikidata..` from depending on the affinity types, *"so a source adapter cannot be
tempted to emit one"* and so the world graph *"stays free of personal data so it can be exported or
shared without one"*. Its javadoc says this matters more than usual **because this repository is
public**.

A `musicbrainz` package is not on that list. The same is true of `adaptersDoNotDependUpward`
(`:113`), which is what stops an adapter reaching into `ingest`, `mcp`, `app` or `seed`, and of the
two sibling rules. **Four rules name adapter packages by literal string, and a fourth adapter
inherits none of them.** The guarantee ADR 33 describes as holding "by construction rather than by
care" would quietly revert to care.

**This is the most important finding in this note.** It is cheap to fix and invisible if missed.
Task 2's definition of done must include adding the new package to all four, and ADR 25's
consequence *"Adding a source is implementing one or both interfaces plus a `@Bean` method"* needs
an amendment saying "and extending the architecture rules that name adapter packages".

### GAP 2 — `mcp` names a `wikidata` exception type to honour its own stated invariant

`SegueService`'s class javadoc states that nothing thrown by a port escapes a public method. It
delivers that by importing `com.robsartin.segue.wikidata.WikidataUnavailableException` and catching
it at three call sites. `WikidataUnavailableException` is a `RuntimeException` in an adapter package.

A second `EntityResolver` throwing its own unreachable-type would escape, and the invariant would be
false without any rule failing — no ArchUnit rule forbids `mcp → wikidata`, and there is no cycle.

**Not blocking for Task 2**, because `SourceAdapter.expand`'s contract already says implementations
return what they gathered rather than throwing, and MusicBrainz should implement `SourceAdapter`
only. It is a latent gap that becomes real with the first non-Wikidata resolver. The fix, when
needed, is a port-level `SourceUnavailableException` in `port` that adapters extend — but building
it now would be structure ahead of a need.

### GAP 3 — `maxNewEdges` is shared across adapters, so adapter order decides who gets the slots

`SegueService.expandEntity` passes the *same* `ExpandContext(effectiveMax)` to every adapter, then
bounds the **concatenation** of everything they returned at `effectiveMax`. With one adapter these
are the same number. With two, an adapter that returns `effectiveMax` assertions consumes the entire
budget, and whatever `SourceAdapters.all()` iterates second contributes nothing at all —
**silently**, since `truncated` is a boolean that cannot say whose result was cut.

Wikidata routinely returns a full bound on a well-connected act (ADR 36's own measurements are
mostly at or near the bound), so this is not a corner case. It is the first behaviour the second
source will hit.

**Blocking for Task 2 in the sense that it must be decided**, not necessarily changed: per-adapter
budgets, round-robin, or an explicit "first source wins, and that is the policy" with the ordering
documented on the `@Bean`. The current behaviour is a coin flip nobody has chosen.

### GAP 4 — `ExpandResult`'s two booleans lose their subject when there is more than one source

`sourceUnavailable` and `truncated` are OR-ed across adapters and land in a single
`ExpansionSummary`. The message says "a source was unavailable and could not be reached", which is
honest but unactionable, and "the result was truncated at the bound of N", which names one bound for
what may have been several. ADR 27 and `ExpandResult`'s own javadoc argue that a partial result the
model can see beats an exception — the same argument says the model needs to know **which** source
fell over, because "MusicBrainz is down" and "Wikidata is down" call for different next moves.

**Small, contained, and worth doing in this issue**: attributing the flags by `adapter.id()`, which
the SPI already requires every adapter to have.

### GAP 5 — `ExpandContext` bounds edges; MusicBrainz's cost is round trips

One field, deliberately (`ExpandContext`'s javadoc: *"More knobs arrive when something needs
them"*). Something now needs one. For Wikidata `maxNewEdges` becomes a server-side `LIMIT` and the
call count is fixed at two. For MusicBrainz the call count is `2 + neighbours resolved`, at ~1 rps,
and `maxNewEdges` bounds it only if the adapter truncates *before* the MBID→QID pass. Nothing in the
interface says it must.

**Not a new field yet.** Document the obligation on `SourceAdapter.expand` — truncate before you
spend — and revisit if a third source cannot honour it.

### GAP 6 — the SPI cannot distinguish a well-chosen truncation from an arbitrary one

ADR 36's bound is a quality argument resting on `DESC(?sitelinks)`, and its issue-#71 amendment
declines to raise the bound precisely because connector density falls off where the bound cuts.
MusicBrainz offers no prominence signal, so its truncation is arbitrary, and `ExpandResult` reports
both with the same boolean. Nothing downstream can tell them apart.

**Named, not fixed.** ADR 36's argument is Wikidata-local and should be labelled as such rather than
inherited by a second source that cannot support it.

### GAP 7 — `KindMapper` is the seam ADR 42 predicted, and it is narrower than it looks

[ADR 42](../adr/0042-store-p31-and-rederive-kind-at-projection.md) says: *"If a second source ever
states classes of its own, this is the seam that has to move."* ADR 53 records `KindMapper` as
referenced from `seed`, `ingest`, `ratings`, `mcp`, `support` and `export`.

I opened all eight main-source files outside `wikidata/` that name it. **Three import it and call
it** — `ingest/GraphProjector.java:73`, `export/LogProjection.java:75`, `seed/WikidataFacts.java:82`.
The other five — `support/ClassLabels`, `export/DotWriter`, `ratings/Labels`, `mcp/SegueService`,
`domain/Retractions` — mention it in javadoc only and import nothing from `wikidata`. ADR 18's
purity rule is intact, as ADR 53 says.

**And the seam does not have to move for MusicBrainz.** `KindMapper.rederive` returns a claim with
no stated classes **untouched**, with a javadoc explaining that a source classifying without stating
classes has nothing to re-derive from. MusicBrainz gives `type: Person` / `type: Group` on the
artist object — a kind without Wikidata classes — which is precisely the case that path was written
for. The adapter uses the four-argument `NodeAssertion` constructor, leaves `instanceOf` empty, and
the projections leave it alone.

**The trap to write down:** `instanceOf` is a list of **QIDs**, enforced by `NodeRecord`'s compact
constructor. A source tempted to record its own class vocabulary there — an OSM `amenity=restaurant`
tag, a MusicBrainz artist type-id UUID — would construct a `NodeAssertion` successfully and fail
later, inside `IngestService.apply`, at `toNode()`. **Do not put a non-Wikidata class in
`instanceOf`. Leave it empty.**

### GAP 8 — an empty `instanceOf` costs the recognition half of the hub rule, permanently

Consequence of GAP 7 and worth separating, because it is the price of the correct choice.
`PathRanking.isHub` demotes a node either for being a busy `CONCEPT` or for stating a class that
makes it a body one is elected to (`isRecognitionInstitution`, reading `node.instanceOf()`). A
source that states no classes can never trip the second half. For MusicBrainz this is harmless —
artists and groups are `PERSON` and `GROUP`, not recognition institutions. For a place-shaped source
it is fatal, as the OSM section shows: both halves of the rule are unavailable at once.

**Named, not fixed.** It is a fact about how much of segue's quality machinery is downstream of
Wikidata's data shape, which is the sort of thing #91 exists to discover.

### GAP 9 — `AssertionRecord` does not validate its endpoints

`NodeRecord` and `Candidate` both run `Qid.check`. `AssertionRecord` requires its `fromQid` and
`toQid` non-null and nothing more. An adapter that emitted an MBID or an OLID as an endpoint would
construct the record cleanly and fail later at the node that names it — a confusing failure a long
way from its cause, and exactly the mistake a first-time adapter author makes.

`Qid`'s own javadoc already says the sweep of the classes that spell the regex themselves "is a
change of its own", so this is consistent with a known position rather than an oversight. **Worth
one line in the Task 2 brief** so the adapter author does not learn it from a stack trace.

### GAP 10 — validity disagreement is preserved in the log and collapsed in the graph, silently

`AssertionRecord`'s javadoc says sources are *allowed* to disagree about validity, "which is why the
dates live on the assertion rather than on the derived edge". `EdgeRecord` nonetheless has one
`validFrom` and one `validTo`, and `TinkerGraphStore` sets each only when the edge does not already
carry it (`:103–108`) — **first writer wins, with no report.**

This collides on the very first MusicBrainz membership edge. The Group probe returned
`begin: "1960", end: "1960", ended: true` on a relation; Wikidata's forward pass reads P580/P582
into the same fields (`ClaimMapper:153–154`), while its reverse pass always writes null
(`ReverseClaims:201`, which says so). So the same membership can arrive dated from one source and
undated from another, in either order, and the graph keeps whichever landed first.

**Not blocking, and genuinely good news for #91's acceptance criteria**: `EdgeRecord.corroboration()`
counts distinct `sourceId`s and the two sources will collapse onto one edge exactly as ADR 23
describes, so "corroboration across sources demonstrated on at least one edge" should fall out of a
membership edge without any new code. The validity collapse is the part to watch and to state in
the increment's report rather than to fix here.

### Not a gap: the confidence convention already covers a second structured source

ADR 23's tiers are 1.00 structured-and-referenced, 0.80 structured-unreferenced, 0.50 statistical,
0.30 model-generated, and they are written as a convention "shared by all adapters" rather than as
Wikidata's. A MusicBrainz relation is structured and carries no citation, so **0.80**, with no
decision to make and nothing to widen.

### Not a gap: `SourceAdapters`, `supports(kind)` and `id()` are the right shape

`SourceAdapters` is already a list. `supports(NodeKind)` does real work for the first time with a
second source — MusicBrainz answers true for `PERSON` and `GROUP` and false for the rest, where
`WikidataSourceAdapter.supports` returns an unconditional true with a comment saying why. And `id()`
is what GAP 4's attribution and `EdgeRecord.corroboration()` both key on. **The parts of ADR 25 that
were untested turn out to be right; the parts that fail are the consequences it claimed, not the
interfaces it defined.**

---

## The recommendation this note carries into Task 2

**Build MusicBrainz. Do not build OpenStreetMap. Leave Open Library until #92 answers the identity
question.**

The four items that must be settled inside this issue rather than deferred are **GAP 1** (the
privacy fence, non-negotiable and cheap), **GAP 3** (the shared budget, which is a live coin flip),
**GAP 4** (per-source attribution of the two flags), and the **GAP 7 trap** written into the Task 2
brief. Everything else is named here so that it is not rediscovered as a surprise.

And the finding that reaches furthest is the one no adapter delivers: **ADR 25's interfaces survive
contact with a second source; its consequences do not.** Adding a source is not "one or both
interfaces plus a `@Bean` method". It is that, plus four architecture rules, plus an HTTP client
with its own rate policy, plus a decision about a bound that two sources now share.
