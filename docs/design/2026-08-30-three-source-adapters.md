# Three source adapters, designed on paper — and what each one asks of the SPI

**Date:** 2026-08-30 · **Issue:** [#91](https://github.com/robsartin/segue/issues/91) · **Task 1 of 6**

Design only. No production code is written by this note, and that ordering is deliberate: building
one adapter first would settle the SPI's shape from whichever source turned out to be easiest.
Designing three exposes the gaps before any of them is committed to. The same argument as
[ADR 18](../adr/0018-graph-engine-gremlin.md) (two engines built, one chosen) and
[ADR 38](../adr/0038-award-received-as-the-first-non-collaboration-edge.md) (one property at a
time).

**Privacy — what was checked, and what that check does not cover.** This repository is public and
the owner's ratings and interests are personal data ([ADR 33](../adr/0033-taste-layer-separation.md),
[ADR 51](../adr/0051-what-an-adr-may-quote.md)). ADR 51 is explicit that it "is held by review, and
by nothing else", so this paragraph states what was done rather than offering an assurance — a
blanket all-clear is exactly what makes a reviewer stop looking.

Checked: every entity named below was chosen by me as a probe against a public API, none was taken
from the known-list or from any tool run over it, and every figure is a count over public data or a
property of code in this repository.

**The Open Library section had its identifying framing removed** — the "owner's book collection"
possessive, the title, the author name, the OLIDs and the QID. That framing is the disclosure ADR 51
forbids, and [ADR 47](../adr/0047-main-subject-as-the-route-through-what-a-book-is-about.md)'s own
amendment identifies the same construction in its own text as a breach. **It is not, however,
anonymous, and this note does not claim it is:** the section still cites ADR 47's *What this cannot
reach* table and says the probe was a programming title with no Wikidata item, and that table has
exactly one row of that description — so a reader can recover the title in one hop, through a
pointer this note supplies. The pointer is kept because ADR 47 is public, deliberately unrepaired,
and the authority for the claim; what is not kept is any sentence framing the book as the owner's.

Not covered by that check: I cannot verify a negative about entities I have never seen, since the
known-list is not in this repository and ADR 51 forbids committing one. A reviewer who knows the
list is the only reader who can confirm that no probe here coincides with it.

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
- `app/SegueConfiguration.java:100–107` builds the adapter list in one `@Bean` method, which is
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
| `artist/b10bbbfc-cf9e-42e0-be17-e2c3e1d2600d?inc=artist-rels` | Group | 109, of which 9 `member of band` **backward** |
| `artist/ba550d0e-adac-4864-b88b-407cab5e76af?inc=artist-rels` | Person | 39, of which 13 `member of band` **forward** |

The same relation type, the same database, opposite `direction` values, one call each. **So a
MusicBrainz adapter needs no reverse pass for `artist-rels`** — a genuinely different ingest shape
from the only one the SPI has ever carried.

**How far that generalises is not established here.** Two artists, one `inc` parameter. The
mechanism is documented behaviour rather than a coincidence of these two records — MusicBrainz
stores one relation on the pair and reports it with a `direction` field — but I have not probed
`release-rels`, `recording-rels` or `work-rels`, and this note does not claim they behave the same
way. The v1 adapter should read `artist-rels` only, which is what was measured.

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

**Correction, 2026-08-30 (#91, Task 5): that is not what shipped, and this paragraph's reason for it
had lapsed by the time the code was written.** The recommendation above rests on one objection —
a SPARQL `P434` bridge "would be an adapter depending on another adapter". Task 3 removed that
objection by declaring `MusicBrainzIdentity` in `musicbrainz` and putting the implementation
outside both packages, which ADR 32 permits in exactly one place: "`app` is the only package
permitted to depend on everything." `app/WikidataMusicBrainzIdentity` crosses `P434` in one batched
query, and both directions of the fence — `musicbrainz → wikidata` and `wikidata → musicbrainz` —
are ArchUnit rules that were watched red. This note's own text already rated that route faster
(one round trip for a whole expansion against one per neighbour), so the supersession costs nothing
this section priced.

**What it does cost is a failure channel, and neither this note nor Task 5's first report connected
it.** A `url-rels` bridge would have failed with `MusicBrainzUnavailableException` — the adapter's
*own* failure type, the one `expand` already turns into `sourceUnavailable`. A Wikidata-backed one
fails with `WikidataUnavailableException`, which the seam cannot declare, so the bridge swallows it
and an outage reads downstream as "MusicBrainz holds nothing for this seed".

**But the `url-rels` advantage was available rather than realised, and saying otherwise would
overstate it.** As `MusicBrainzSourceAdapter.expand` is actually written, its one `try` wraps only
the `client.artistRelations` call: `identity.mbidFor` is called before that `try` opens and
`identity.qidsFor` after its `catch` closes, so neither is covered. A `url-rels` bridge throwing
from either would have escaped `expand` entirely and reached the bare `for` loop over
`adapters.all()` in `SegueService.expandEntity`, which has no `try` of its own — a broken SPI
contract rather than a correct flag — until that `try` was widened to cover both calls. One line,
not a property the route came with.

**In the shipped wiring the visible loss is narrower than it first appears.** Both uses of the
Query Service share one `WikidataClient`, `WikidataSourceAdapter.supports` returns `true` for every
kind so it always runs alongside, and it sets `sourceUnavailable` in the `catch` around its own
reverse-lookup pass, which `SegueService.expandEntity` ORs into the result. A Query Service outage
therefore does surface — just unattributed to a source, which is GAP 4. The residual is the narrow
case where the `P434` query alone fails.

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

**Caveat on the scope of these probes.** Two artists (one Group, one Person), and three endpoints:
`artist/<mbid>?inc=artist-rels`, `artist/<mbid>?inc=url-rels` and
`url?resource=<wikidata url>&inc=artist-rels`. No release, recording or work relations; no `browse`
or `search` endpoint; no test of how the bridge behaves for an artist MusicBrainz holds but Wikidata
does not (#91's 2026-08-29 comment measured that ratio at scale and is the authority on it).

**What it does NOT need:** the `EntityResolver` half. Its seed arrives from the graph already
carrying a QID, and it can fill `ExpandResult.neighbors()` itself from the artist-rels response
plus one url-rels call each. Under ADR 25's own rule — *a source implements whichever it can
honour* — MusicBrainz should implement `SourceAdapter` only.

---

## Open Library — books

### What it is authoritative for

Books as works and editions, their authors, and free-text subjects. It holds books that have no
Wikidata item at all — ADR 47's *What this cannot reach* section is the authority on which books
segue's own vocabulary misses and why, and this note does not repeat its rows. I confirmed the
general shape by searching Open Library for a programming title with no Wikidata item and getting
two work records back. **This is the domain where a second source would add coverage rather than
depth.**

No title, author name, OLID or QID appears in this section, and no sentence frames a book as the
owner's. The argument is about what fields an Open Library record does and does not have, which no
name makes any sharper. This is not the same as anonymity — the citation of ADR 47 below is a
pointer a reader can follow — and the privacy note above says so rather than claiming otherwise.

### Where its relations live — on the work, and as strings

Probed two `works/OL…W.json` documents — the programming title above, and a novel chosen only
because its record is the richer of the two. A work record states:

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

- **Author records carry the bridge directly.** An `authors/OL…A.json` document has a
  `remote_ids` object whose keys include `wikidata`, alongside `viaf` and `isni`. The QID is a
  field value: no round trip, no Wikidata dependency, no URL parsing. **Better than MusicBrainz's**,
  which costs a call.
- **It is sparsely populated, and Open Library duplicates people.** The search response returned
  two work records for one title naming two *different* author keys for one person. Fetching both
  author records: one carries `remote_ids` with a `wikidata` key; the other has no `remote_ids`
  field at all. **The bridge exists per record, not per person**, so whether an author resolves
  depends on which duplicate a work happens to point at.
- **Work records carry no bridge whatsoever.** Neither probed work record has `remote_ids`, and
  the schema has no field for one.

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
`series` would need P179 ("part of the series"), which exists in Wikidata and is not registered
here — so admitting it would be a normal ADR 38-style one-at-a-time admission, not a vocabulary
invention. **Nothing Open
Library states requires inventing a code.**

The vocabulary pressure it does create is one level down and is about *values*, not codes: an
`ABOUT` edge needs a QID on the far end, and Open Library gives a string. Resolving a subject
heading such as `"Object-oriented programming (Computer science)"` to a QID is a Wikidata search per
subject — an adapter calling another adapter's source again, and this time with no clean in-source
bridge to use instead.

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
domains it reaches restaurants — the domain
[ADR 53](../adr/0053-all-the-owners-interests-bounded-per-domain.md) records as out of scope for
Wikidata, and **not because Wikidata's entities are thin but because nothing they carry maps to a
registered relation.**

### The recommendation, and why it does not rest on a census

**An OSM adapter should not be built**, and the argument is structural. Each of the four steps below
was checked against code or against an ADR in this repository; none of them needs a sample of OSM
data to hold.

1. **`EdgeTypes` registers no location property.** I read all fifteen registrations in
   `EdgeTypes` — which is the authority — one at a time. None is a location property, which
   confirms ADR 53's statement from the code rather than from the ADR.
2. **So OSM needs a new registration to produce any route at all**, and the only candidate that
   would connect two premises is a location edge to the area containing them.
3. **[ADR 38](../adr/0038-award-received-as-the-first-non-collaboration-edge.md)'s own criterion
   rejects it.** ADR 38 admitted P166 at a measured hub size of 127 and rejected P106 at 35,977 and
   P136 at 16,552. Every premises in a city hanging off one city node is that shape or worse, and
   the route it yields — "these two are both in this city" — is the coincidence ADR 36's issue-#71
   amendment calls a route that means nothing.
4. **And the hub rule could not demote it — given the kind the adapter would assert.**
   `PathRanking.isHub` has two halves and I read both (`domain/PathRanking.java:176–197`).
   `isBusyConcept` requires `node.kind() == NodeKind.CONCEPT`; `isRecognitionInstitution` reads
   `node.instanceOf()`, which is empty for a source stating no Wikidata classes. So an OSM place
   asserted as `PLACE` — the honest kind, and what `KindMapper:91` gives a Wikidata city — trips
   **neither half**, and a city hub would rank as a genuine explanation. **This step depends on that
   design choice**: an adapter that filed cities as `CONCEPT` would trip `isBusyConcept` at a degree
   of ten (`>= HUB_DEGREE`, `PathRanking:66` and `:177`), at the cost of calling a city a thing it
   could not place. The recommendation does not rest on this step — step 3 carries it alone — but
   ADR 31's issue-#88 amendment refused to generalise the hub rule, and this is the case that
   refusal leaves open.

Recording this conclusion is worth more than the adapter would have been, and it is the same kind of
answer #89 exists to make sayable.

### Its own identity — ids that are not stable

OSM identity is an object type plus a numeric id: `node/1234`, `way/1234`, `relation/1234`, unique
only within its type. Two properties matter to an adapter and neither is a sampling question:

- **The id is not stable across edits.** A premises remapped from a node to a building way gets a
  new id and the old one is deleted; a way split in two yields ids that did not exist before. OSM
  documents this and tells consumers not to treat an id as a permanent reference to a feature.
- **The id identifies a mapped object, not a business.** A restaurant that changes hands may keep
  its node and change its `name`, or be deleted and re-added.

So even setting ADR 22 aside, an OSM id is a poor spine: segue's log is append-only and a QID that
silently stopped meaning the same thing would be unfixable by replay. **This is an argument against
OSM as an identity source independent of the one against it as a relation source**, and the two do
not rescue each other.

### Where its relations live — attributes, not relations

Measured with one Overpass query over the bounding box **`53.470,-2.260,53.490,-2.220`** (a 0.020°
by 0.040° box over a dense city centre, chosen in a city unconnected to anything in this project),
`amenity=restaurant`, all three object types. **308 nodes, 5 ways, 0 relations; 110 distinct tag
keys over the nodes.** The census:

| tag | nodes carrying it (of 308) |
|---|---|
| `name` | 305 |
| `addr:street` | 261 |
| `cuisine` | 229 |
| `website` | 102 |
| `brand` / `brand:wikidata` | 47 / 47 |
| `operator` | 5 |
| `wikidata` | 0 |
| `wikipedia` | 0 |

**This is supporting colour for the structural argument above, not a substitute for it.** One box in
one city cannot establish that OSM restaurants carry no `wikidata` tag in general, and notable
restaurants elsewhere certainly do. What it does show is the *shape*: the tags that are common are
strings — a cuisine is `chinese`, `italian`, `pizza`; an address is three strings — and the only tag
pointing at another entity for more than a handful of features is `brand:wikidata`, whose QID is the
**chain's**, shared by all 47.

**On OSM's own `relation` objects.** OSM does have relations as a first-class data type. The query
above asked for `relation["amenity"="restaurant"]` in the same box and returned **none**, so within
this sample a restaurant is never a relation member by virtue of being a restaurant. I have **not**
checked whether these premises appear as members of boundary or site relations that carry no
`amenity` tag, and the earlier draft of this note asserted that they do; **that assertion was
unverified and is withdrawn.** It does not affect the recommendation, which rests on the four
structural steps and not on this.

### Identity, and the bridge to a QID

The `brand:wikidata` tag is a real QID, and it is the wrong entity: ingesting it would collapse
distinct premises onto a handful of chain QIDs and assert that a restaurant *is* its chain. No tag
in the census points at a QID for the premises itself.

Under ADR 22 clause 1 an entity is its QID, so an OSM feature with no `wikidata` tag and no stable
id of its own has nothing to be. ADR 53 concluded that reaching this domain "requires a different
source, which is #91's territory". **The four structural steps above say a different source does not
fix it, because the obstacle is the identity spine and the vocabulary rather than the source.** That
is a finding this note owes back to ADR 53, and ADR 1 makes it an amendment rather than an edit.

### Does it force ADR 22 clause 3? Softly — and it is a registration, not an invention

Establishing rather than asserting. Every relation an OSM restaurant could carry has a real Wikidata
property to borrow, each id verified against the Action API rather than recalled:

- **location** — P131, "located in the administrative territorial entity". Exists, not registered.
- **brand / operator** — P1716 "brand", P137 "operator". Exist, not registered.
- **cuisine** — P2012 "cuisine". Exists, not registered, and its far end is a string in OSM, so it
  inherits Open Library's value problem on top of everything else.

**So clause 3 survives in the letter:** nothing OSM states would have to be invented. What OSM forces
is a *registration* under ADR 38's one-at-a-time discipline — and step 3 above is ADR 38 declining.

### What it needs from the SPI that the SPI does not offer

1. **The same string-valued-claim shape Open Library needs**, for `cuisine`.
2. **A hub judgement that does not depend on Wikidata-shaped data.** Not in the SPI at all; it is in
   `domain`, which is where it is most awkward. See GAP 8.
3. **Nothing else.** OSM's problem is upstream of the SPI in both directions: identity refuses its
   entities and the vocabulary refuses its relations. The SPI is not what fails here.

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

A `musicbrainz` package is not on that list, so the guarantee ADR 33 describes as holding "by
construction rather than by care" would quietly revert to care. **This is the most important finding
in this note:** cheap to fix, and invisible if missed.

**And it is not one rule.** I extracted every rule body in `ArchitectureTest` and checked each for a
literal naming `tinker`, `jena`, `sqlite` or `wikidata`. **13 of the 35 rules name an adapter
package (or an adapter class) as a literal.** The number alone is a trap, so here is the list —
Task 2 must inherit the list, not the number.

**Tier 1 — must change, or a real fence does not extend to the new adapter (3):**

| rule | lines | what breaks without the edit |
|---|---|---|
| `theWorldFactLayerNeverTouchesAffinity` | 975–983 | **ADR 33's privacy fence.** Subject list; the new adapter could reach the affinity types. |
| `adaptersDoNotDependUpward` | 110–117 | Subject list; the new adapter could depend on `ingest`, `mcp`, `app`, `seed`. |
| `theExporterNeverSpeaksToANetwork` | 409–418 | Bans `java.net..` and `javax.net..`, so `export → MusicBrainzClient` is caught by neither — ArchUnit sees **direct** dependencies only. See the defect note below before copying this rule's third argument. |

**Tier 2 — the four ADR 32 sibling rules, which should be replaced rather than extended (4).**
`tinkerDoesNotDependOnJena` (88–95), `jenaDoesNotDependOnTinker` (99–106),
`sqliteDoesNotDependOnOtherAdapters` (282–289) and `wikidataDoesNotDependOnOtherAdapters`
(1005–1012) carry the identical `.because("ADR 32: adapters are siblings, not collaborators")` — at
`:95`, `:106`, `:289` and `:1012`, four hits and no more.

**Correcting this note's first draft, which called the first two "not holes".** They are holes, and
the reasoning that put them in the wrong tier was that a new `musicbrainzDoesNotDependOnOtherAdapters`
would cover them. It cannot: such a rule has **musicbrainz as its subject**, so it says nothing
about `tinker → musicbrainz` or `jena → musicbrainz`. `tinker`'s only object is `jena` (`:94`) and
`jena`'s only object is `tinker` (`:105`), so both directions stay uncaught — and `noPackageCycles`
cannot rescue them, because the sibling rule forbids the return edge and no cycle ever forms. That
was the same risk the tier-1 reasoning cited for adding the new package to `sqlite`'s and
`wikidata`'s object lists, applied inconsistently.

**The arithmetic is the argument for replacing rather than patching.** Four adapters make twelve
ordered pairs; these four rules cover **eight**. The four uncovered are `tinker → sqlite`,
`tinker → wikidata`, `jena → sqlite`, `jena → wikidata` — so `tinker → wikidata` is unforbidden
today, before any second source exists. Five adapters make twenty ordered pairs, and patching the
object lists one at a time means five rules holding twenty package literals between them, with the
next adapter needing five edits and a sixth rule.

**Recommended, and filed as [issue #140](https://github.com/robsartin/segue/issues/140): one rule
over a single adapter list**, replacing all four — no class in an adapter package may depend on a
class in a *different* adapter package. Twenty of twenty pairs, one list, and the next adapter is a
one-line change instead of five.

**The mechanism, checked against the project's own `archunit-1.5.0` jar with `javap` rather than
recalled.** A `DescribedPredicate` cannot express this rule, and an earlier draft of this note said
it could. `ClassesShould.dependOnClassesThat` has exactly one predicate overload and it takes a
`DescribedPredicate<? super JavaClass>` over the **target** class, so no object-side predicate can
see which adapter package the *origin* is in — which is the whole of "a different adapter package".
The naive `resideInAnyPackage(ADAPTERS) → resideInAnyPackage(ADAPTERS)` form fails for a second
reason as well: it would flag every intra-`tinker` dependency.

**The working form is the slices API this file already imports** (`:27`, used by
`noPackageCycles`):

```java
SlicesRuleDefinition.slices().assignedFrom(assignment).should().notDependOnEachOther()
```

with a `SliceAssignment` whose `getIdentifierOf` returns `SliceIdentifier.of(<adapter package>)` for
a class in one of the adapter packages and `SliceIdentifier.ignore()` for everything else. Slices
are only ever compared *across* slices, so the intra-package problem dissolves rather than being
worked around. `Creator.assignedFrom(SliceAssignment)`, `SliceIdentifier.ignore()` and
`SlicesShould.notDependOnEachOther()` were all confirmed present in 1.5.0. About ten lines.
(An `ArchCondition<JavaClass>` walking `JavaClass.getDirectDependenciesFromSelf()` — also confirmed
present — would work too, and is more code for the same answer.)

**Scope: this is a pre-existing defect, so it is NOT part of #91.** Four of the six uncovered pairs
at N=5 exist today with no second source involved. The four-rule replacement is filed as
[#140](https://github.com/robsartin/segue/issues/140), and **Task 2 does only what #91 needs**:

| pairs | who owns them |
|---|---|
| `tinker → musicbrainz`, `jena → musicbrainz` | **#91** — created by adding the source |
| `sqlite → musicbrainz`, `wikidata → musicbrainz`, `musicbrainz → {t,j,s,w}` | **#91** — created by adding the source |
| `tinker → {sqlite, wikidata}`, `jena → {sqlite, wikidata}` | **#140** — uncovered today |

**The fallback warning stands if the replacement is deferred.** Widening only `sqlite`'s and
`wikidata`'s object lists and adding a `musicbrainz` subject rule reaches **14 of 20** — it leaves
all six `tinker`/`jena` → {`sqlite`, `wikidata`, `musicbrainz`} pairs uncovered, two of which #91
creates. Those two must be picked up either way.

**Tier 3 — a pre-existing shape the new adapter widens (3):** `theRatingsToolOpensNothingElse`
(464–485), `theRecommenderOpensNothingElse` (807–827) and `theRetractionToolOpensNothingElse`
(884–906) each ban `java.net..` to keep an offline tool offline, and each omits `..wikidata..` from
its package list. Because ArchUnit sees direct dependencies only, any adapter's HTTP client already
slips through them; a second one widens a hole rather than opening it. Worth a line in the Task 2
report, not a blocker.

**Tier 4 — checked and NOT holes (3), so Task 2 does not churn them:** `seedNeverOpensAStore`
(129–146) bans *stores* and already omits `..wikidata..` deliberately; `onlyTheRatingsToolReadsANote`
(538–547) uses `resideOutsideOfPackages`, so a new package is inside its subject automatically;
`theRatingDeckOpensNothingElse` (773–790) bans no network at all.

Separately, and not among the thirteen because it names no package:
`affinityNeverTouchesTheWorldFactLayer` (957–964) is written against **types**
(`AFFINITY_TYPES` / `WORLD_FACT_TYPES`), so it **needs no edit when a fifth adapter arrives.** It
does not thereby *cover* the new adapter — its subject is the affinity types, and the adapter-facing
counterpart is tier 1's `theWorldFactLayerNeverTouchesAffinity` (975–983), which does need the edit.
Nor is it evidence that a predicate could express tier 2's rule: those two sets are fixed and
disjoint, which is the easy case. What it *is* good evidence for is the narrower point that one
named constant beats literals scattered across rules.

### An open defect in `theExporterNeverSpeaksToANetwork`, found while checking this

**The rule's third argument is inert.** `:415` passes `"..wikidata.WikidataClient"` to
`resideInAnyPackage`, which matches **package** identifiers. `WikidataClient` resides in package
`com.robsartin.segue.wikidata`, so that pattern matches no class in this repository and
`export → WikidataClient` is not forbidden today. The rule's own javadoc (`:403–405`) reads as
though the project's HTTP client is handled, and it is not. No live violation exists — `export`
imports `KindMapper` and `RecognitionInstitutions` from `wikidata`, not the client — so this is an
inert fence rather than a breach.

**This is a pre-existing defect, filed as [issue #139](https://github.com/robsartin/segue/issues/139);
it is recorded here and NOT fixed in this task**, which is a design note and changes no code. A
sweep of the whole file for the same mistake found that **`:415` is the only rule passing a class
name to a package predicate**; that answer belongs to #139 and is cited here rather than repeated.

**The warning Task 2 must not miss:** do not mirror the pattern as
`"..musicbrainz.MusicBrainzClient"`, which would ship an equally inert fence and read as protection.
A class-level ban needs `haveFullyQualifiedName` or an `assignableTo` predicate, not
`resideInAnyPackage`.

ADR 25's consequence *"Adding a source is implementing one or both interfaces plus a `@Bean`
method"* needs an amendment saying "and extending the architecture rules that name adapter packages
— which, on the day this was checked, meant **three rules, plus all four sibling object lists, plus
a new sibling rule**, and surfaced two pre-existing defects in the fences themselves".

**That tally is read off the pair-ownership table above, and must stay that way.** Every pair the
table assigns to #91 names the rule that has to change: `tinker → musicbrainz` and
`jena → musicbrainz` are constrained by nothing but `tinkerDoesNotDependOnJena` and
`jenaDoesNotDependOnTinker`, whose object lists hold one package each, so those two lists widen for
#91 as surely as `sqlite`'s and `wikidata`'s do. An earlier draft of this sentence said "two sibling
object lists", which came from the fallback paragraph — the shape the note is warning **against** —
rather than from the table.

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
The other five mention it in javadoc only, and they do **not** all import nothing from `wikidata`
— I checked the import block of each rather than generalising from the `KindMapper` result.
`support/ClassLabels`, `export/DotWriter`, `ratings/Labels` and `domain/Retractions` import nothing
from `wikidata`, so ADR 18's purity rule is intact exactly as ADR 53 says. **`mcp/SegueService` does
import two** — `RecognitionInstitutions` (`:22`) and `WikidataUnavailableException` (`:23`) — and
the second of those is the whole of GAP 2 above.

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

This collides on the very first MusicBrainz membership edge. The Group probe's nine
`member of band` relations are all dated — the fullest is
`"begin": "1960-08-12", "end": "1962-08-16", "ended": true`. Wikidata's forward pass reads P580/P582
into the same fields (`ClaimMapper:153–154`), while its reverse pass always writes null
(`ReverseClaims:201`, which says so). So the same membership can arrive dated from one source and
undated from another, in either order, and the graph keeps whichever landed first.

**A second, sharper edge on the same probe: MusicBrainz dates are variable-precision and
`validFrom` is a `LocalDate`.** Of those nine relations, **1 of 9 `begin` values and 4 of 9 `end`
values carry a day**; the rest are `1960` or `1962-08`. (The `end` figure read 5 of 9 until #91's
Task 4 review re-probed the same entity and counted 4 — `1962-08-16` plus `1970-04-10` three times.
The `begin` figure is unchanged, and it is the one the adapter's javadoc cites, so no code moved.) There is a settled precedent for this and
the adapter must follow it rather than re-decide: `ClaimMapper.qualifierDate` returns null below day
precision, with a comment saying a year- or month-precision date read as a `LocalDate` "would feed
false day-level precision into `validAt()` time-travel queries". **The MusicBrainz adapter should
drop any date that is not `YYYY-MM-DD`**, which on this probe means keeping one `validFrom` of
nine.

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

**Two things this note found are explicitly NOT #91's** — both pre-existing, both filed: the inert
`WikidataClient` ban ([#139](https://github.com/robsartin/segue/issues/139)) and the four-rule
sibling replacement ([#140](https://github.com/robsartin/segue/issues/140)). Task 2 covers only the
pairs adding a source creates; GAP 1 has the split.

And the finding that reaches furthest is the one no adapter delivers: **ADR 25's interfaces survive
contact with a second source; its consequences do not.** Adding a source is not "one or both
interfaces plus a `@Bean` method". It is that, plus **three architecture rules extended, all four
sibling object lists widened and a new sibling rule written** — eight rule changes, one of the three
being ADR 33's privacy fence — plus an HTTP client with its own rate policy, plus a decision about a
bound that two sources now share. **And the act of checking that list is what surfaced two defects
that had nothing to do with a second source**, which may be the most useful thing #91 has produced
so far.
