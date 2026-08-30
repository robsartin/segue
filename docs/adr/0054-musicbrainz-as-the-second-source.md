---
status: Accepted
date: "2026-08-30"
topic: musicbrainz-as-the-second-source
tags: [project, ingest, extensibility, privacy]
supersedes: []
related: [source-adapter-spi, wikidata-identity-and-vocabulary, quarantine-model-generated-assertions, layering-and-archunit, taste-layer-separation, reverse-lookup-via-sparql, award-received-as-the-first-non-collaboration-edge, all-the-owners-interests-bounded-per-domain, what-an-adr-may-quote]
---
# 54. Take MusicBrainz as the second source — the SPI held, and the fences around it did not

## Context

[ADR 25](0025-source-adapter-spi.md) split ingest into two ports so that *adding a source is
implementing one or both interfaces plus a `@Bean` method. No graph, storage or MCP code changes.*
That sentence had **one production implementor** for six days, which makes it a design intention
rather than a finding. Issue #91 exists to turn it into one.

The method was the shape [ADR 18](0018-graph-engine-gremlin.md) and
[ADR 38](0038-award-received-as-the-first-non-collaboration-edge.md) already use: design more than
one, commit to one. `docs/design/2026-08-30-three-source-adapters.md` designs three — MusicBrainz,
Open Library, OpenStreetMap — against the SPI as it stands, names ten gaps, and recommends building
exactly one of them. This ADR records what building it then established, and it is written because
that note's substance is a set of counts against a tree that will move: the note is dated evidence,
and the decision belongs somewhere immutable that cites code rather than mirroring it.

**Privacy.** This repository is public and the owner's interests are personal data
([ADR 33](0033-taste-layer-separation.md), [ADR 51](0051-what-an-adr-may-quote.md)). Every figure
below is an aggregate; no entity is named anywhere in this document, as a probe or otherwise, and
nothing here is framed as the owner's taste, holdings, or the output of a tool run over them.

## Decision

### MusicBrainz is the second source, over Open Library and OpenStreetMap

**#91's own body favours Open Library**, on coverage: it names the technical shelf that
[ADR 47](0047-main-subject-as-the-route-through-what-a-book-is-about.md) showed Wikidata cannot
reach. The issue's 2026-08-29 comment is the argument that overturned its own body, and it is the
citation for this decision. Two reasons, and the second is the one about the SPI:

- **MusicBrainz does not force [ADR 22](0022-wikidata-identity-and-vocabulary.md) clause 3.** Its
  `member of band` is P463, which `EdgeTypes` already registers as `MEMBER_OF`, so the relation
  carrying the value needs no vocabulary decision. Open Library and OSM are where clause 3 bites,
  and taking the source that does not force it keeps the vocabulary question separable from the
  question #91 was filed to answer.
- **It states its relations on the artist, which is a different ingest shape from Wikidata's.**
  Wikidata states creative relations on the work, which is why
  [ADR 36](0036-reverse-lookup-via-sparql.md) needed a whole SPARQL reverse pass. A source that
  answers from either end of the relation exercises the SPI rather than re-running it: an adapter
  that merely repeated Wikidata's shape would have proved that the SPI takes two Wikidatas.

`MusicBrainzSourceAdapter` whitelists one relation type, and `MusicBrainzSourceAdapter`'s own
javadoc is the authority on which and why. Two exclusions are decisions rather than omissions:
`collaboration`, because `EdgeTypes.COLLABORATED_WITH` is registered `derived` with a null Wikidata
property and a javadoc saying no source states it — admitting a source-stated one would falsify that
sentence or invent a code; and `subgroup`, which is filed as **#142** rather than decided here.
Under [ADR 23](0023-quarantine-model-generated-assertions.md) a MusicBrainz relation is structured
and unreferenced, so **0.80**, with nothing to widen.

### The verdict on ADR 25: the interfaces held; the consequences were optimistic

**The interfaces held, and by a wider margin than #91's acceptance asked for.** Checked against the
merge-base rather than recalled — `git diff --name-status main...HEAD -- src/main` — the whole set
of production files this branch touches is seven:

| file | change |
| --- | --- |
| `musicbrainz/ArtistRelation` | new |
| `musicbrainz/MusicBrainzClient` | new |
| `musicbrainz/MusicBrainzUnavailableException` | new |
| `musicbrainz/MusicBrainzIdentity` | new |
| `musicbrainz/MusicBrainzSourceAdapter` | new |
| `app/WikidataMusicBrainzIdentity` | new |
| `app/SegueConfiguration` | modified — the `@Bean` ADR 25 named |

That is the enumeration, not a summary of one. Nothing under `domain`, `port`, `tinker`, `jena` or
`ingest` changed, which is #91's criterion. **`mcp/SegueService` did not change either**, which the
criterion did not require and which matters more: the class that iterates `SourceAdapters` and calls
`expand` took a second adapter without noticing.

**The consequence ADR 25 stated is wrong in the optimistic direction, and it is amended below.**
Adding a source was not one interface plus a bean. It was that, plus:

- **Eight architecture-rule changes.** `ArchitectureTest` went from 35 rules to 36. Five bodies
  changed — `adaptersDoNotDependUpward`, `sqliteDoesNotDependOnOtherAdapters`,
  `wikidataDoesNotDependOnOtherAdapters`, `theExporterNeverSpeaksToANetwork` and
  `theWorldFactLayerNeverTouchesAffinity`. Two were widened and renamed for what they now forbid —
  `tinkerDoesNotDependOnJena` and `jenaDoesNotDependOnTinker`, which is why neither name exists at
  `HEAD`; find them as `tinkerDoesNotDependOnJenaOrMusicbrainz` and
  `jenaDoesNotDependOnTinkerOrMusicbrainz`. One is new,
  `musicbrainzDoesNotDependOnOtherAdapters`. `ArchitectureTest` is the authority on each rule's
  contents; that list is which rules, not what they say.
- **An identity bridge that fits in neither adapter package.** See the next section.
- **A whole HTTP client.** `WikidataClient` owns the User-Agent, the retry loop and the
  `Retry-After` handling and lives in `wikidata`, which ADR 32 forbids reaching for.
  `MusicBrainzClient` had to be written. Its own javadoc says it is modelled on `WikidataClient` —
  same User-Agent shape, same retry policy, same one-failure-type contract — so the cost is not
  novelty but that the code could not be shared at all. It does differ where the source forces it:
  MusicBrainz requires a **proactive** ~1 request/second throttle where Wikidata needs reactive
  backoff.
- **A bound two sources now share.** `SegueService.expandEntity` builds one `ExpandContext`, hands
  that same one to every adapter, ORs the two result flags across them, and bounds the
  *concatenation*. So `maxNewEdges` is spent by whichever adapter `SourceAdapters.all()` names
  first, and the second adapter's HTTP calls have already happened when its work is discarded.

### The failure mode of that cost is silence, and that is the finding

Every fence listed above names adapter packages as **literal strings**. A new adapter package
inherits none of them: nothing fails to compile, no test goes red, and no rule reports that it has
nothing to say about a package it has never heard of. Before this branch,
`theWorldFactLayerNeverTouchesAffinity` — **ADR 33's privacy fence, in a public repository** — named
`ingest`, `tinker`, `jena` and `wikidata`, and a `musicbrainz` package was simply outside its
subject. The rule was green throughout, and it was green because it was not looking.

That is why the cost belongs in an ADR rather than in a checklist. A cost you can see is a cost you
pay; this one is only visible to somebody who goes looking for what a new package did **not**
inherit, and the two defects filed as **#139** and **#140** were both found by exactly that search,
before a line of adapter code existed.

### The identity bridge: ADR 22 stays, and the crossing is P434, outside both adapters

**All three of ADR 22's clauses survive.** Clause 2 — source-local identifiers resolve to a QID in
the ingest layer and never appear in the domain — names MBIDs first among its examples, and the same
ADR's alternatives already considered and rejected MBIDs *as the spine*. It is the clause written
with this source in mind, and it needed no widening.

**The bridge crosses through Wikidata's `P434`**, read back from the Action API on 2026-08-30 as
"MusicBrainz artist ID", datatype `external-id`, rather than recalled.

**It lives in `app`, and that placement is forced rather than chosen.** `musicbrainz` declares
`MusicBrainzIdentity` and cannot implement it: `musicbrainzDoesNotDependOnOtherAdapters` and
`wikidataDoesNotDependOnOtherAdapters` make both directions build failures, and both were watched
red against a scratch class placed in each package in turn. [ADR 32](0032-layering-and-archunit.md)
names the one package that may see both, in one sentence: *"`app` is the only package permitted to
depend on everything, because wiring is its job."* Nothing more general is claimed for the
placement. `WikidataMusicBrainzIdentity` is the authority on how it batches, orders and refuses.

**What the crossing excludes, with the instrument stated beside the number.** #91's 2026-08-29
comment measured 40 seeds spread across the known-list and 387 distinct artist-relation neighbours,
with zero failures: **197 (51%) carry a QID through P434, 190 (49%) do not.** The ratio is not the
finding; the **character** of the 49% is. A 30-entity sample of the QID-less was tribute acts,
artists' pseudonyms, one act's backing band, two musicians' children, a film director, and several
billing variants of artists already present — material a discography database holds as first-class
and an affinity graph is better off not reaching. *The measurement recorded its own instrument
caveat and this ADR keeps it rather than the headline alone:* an automatic classifier flagged 11 of
the 30 by matching disambiguation text, and by inspection it is closer to 18, because a tribute act
whose name says so carries no disambiguation. **The names were the evidence; the count was the
weaker half.**

**So ADR 22 is not superseded and is not amended.** Its identity clause is doing real filtering
work, and the 49% is largely material worth not reaching.

### `check` stays offline, and the bridge is checked against reality separately

#91 also requires that the identity mapping work on **real** entities, which no fixture can show.
The repository already has the mechanism — `@Tag("live")` tests, excluded from `test` and included
by `./gradlew liveTest`, which is never up-to-date — and it is used rather than extended:
`WikidataMusicBrainzIdentityLiveTest` asserts that `mbidFor(qidsFor(mbid))` returns the MBID it
started from against the real Query Service, and `MusicBrainzLiveSmokeTest` asserts the response
shape and an end-to-end expansion against the real `ws/2`. A full `./gradlew liveTest` on 2026-08-30
ran 15 tests with no failures and no skips.

**A dev-side probe on a wider real sample, run for this ADR on 2026-08-30**, drove the shipped
`MusicBrainzClient` and the shipped `WikidataMusicBrainzIdentity` over six seeds chosen as world
facts, all of them ensembles that ended long ago and were picked to sit off the known-list's prior
rather than near it. **95 distinct artist-relation neighbours; 60 resolved to a QID (63%).** Of the
92 that arrived on `member of band`, 59 resolved (64%). All six seeds round-tripped QID to MBID and
back to the same MBID.

**That probe does not replicate the 51%, and is not offered as replicating it.** Its sample is six
seeds of one era rather than forty spread across the known-list, and the tail it leaves is a
different tail: of the 35 unresolved, 33 arrived on `member of band` and 2 on `named after artist`
— session musicians with no Wikidata item rather than tribute acts. What the probe does establish is
the thing #91 asked for and the fixtures cannot: the bridge resolves real MBIDs against the real
service, at a rate in the same region as the measured one, and agrees with itself in both
directions.

### Corroboration is real for the first time, and its limits are stated

`EdgeRecord.corroboration()` counts distinct `sourceId`s and had counted one since it was written.
`CorroborationAcrossSourcesTest` runs **both production adapters** through
`SegueService.expandEntity`, replays the log with `GraphProjector`, and asserts on the rebuilt graph
that one `MEMBER_OF` edge has `corroboration() == 2` with sources exactly `wikidata` and
`musicbrainz`. The shape is the honest one: Wikidata reaches that edge only through ADR 36's reverse
pass, because it states P463 on the member, while MusicBrainz returns the same relation from either
end. **Two different ingest shapes producing one edge is what the count demonstrates.**

**What it does not demonstrate, stated because the test's own javadoc is the only other place it is
said.** The test stubs the identity seam — the MBID-to-QID mapping in it is the test's own, supplied
by `StubIdentity` — so **nothing anywhere exercises the two adapters and the real bridge together.**
Both sides are fixtures. There is no live end-to-end check that the two sources corroborate a real
pair, and #91's neighbour-coverage measurement is a different claim. `WikidataMusicBrainzIdentityLiveTest`
covers the bridge alone; the gap between them is uncovered.

## Alternatives considered

- **Open Library as the second source** — #91's own preference, and the coverage argument for it is
  real: it holds books with no Wikidata item at all, which is exactly ADR 47's finding. It loses on
  **clause 1, not clause 3**. An Open Library *author* record carries the bridge as a field
  (`remote_ids` with a `wikidata` key, better than MusicBrainz's, which costs a call), but a *work*
  record has no such field and the schema has no place for one. The books it reaches that Wikidata
  does not are by definition books with no QID, and `NodeRecord`'s compact constructor runs
  `Qid.check`, so those claims would fail inside `IngestService.apply` — the adapter would drop
  exactly what it was added to reach. That is the identity-spine question ADR 53 defers to #92, and
  #91 is not the place to reopen it. *Probe scope, stated: one search response, two work records and
  two author records; no editions layer, no subjects API, no bulk dump. The conclusion rests on a
  schema fact rather than on that sample.*
- **OpenStreetMap as the second source** — it reaches restaurants, the domain
  [ADR 53](0053-all-the-owners-interests-bounded-per-domain.md) records as out of Wikidata's reach.
  It loses twice over, and neither loss is about the source's data quality. **As a relation source:**
  `EdgeTypes` registers no location property, so OSM needs a new registration to produce any route at
  all, and the only candidate that connects two premises is a location edge to the area containing
  them — every premises in a city hanging off one city node. ADR 38 admitted P166 at a measured hub
  of 127 and rejected P106 at 35,977 and P136 at 16,552, so its own criterion declines this.
  **As an identity source, independently:** an OSM id is an object type plus a number, unique only
  within type and **not stable across edits** — a node remapped to a way gets a new id, a split way
  yields new ones — and segue's log is append-only, so an identifier that silently stops meaning the
  same thing is unfixable by replay. The two arguments do not rescue each other.
- **The in-MusicBrainz `url-rels` bridge** — the design note's own recommendation, one call per
  neighbour, and **superseded by what shipped**. Its whole justification was that a SPARQL P434
  query "would be an adapter depending on another adapter"; declaring `MusicBrainzIdentity` in
  `musicbrainz` and implementing it in `app` removed that objection, and the batched query is one
  round trip per 100 neighbours where `url-rels` is one per neighbour. **Per 100, not per
  expansion**: the request URI measures 180 + 43n bytes, so a whole expansion under the shipped
  `max-new-edges: 200` would put 8,780 bytes on a request line whose classic limit is 8,192.
  `WikidataMusicBrainzIdentity.MAX_MBIDS_PER_QUERY` is the authority on the bound and on what
  exceeding it would have cost. **On the one advantage it
  is often credited with, take the weaker claim, because the stronger one was checked and is false.**
  A `url-rels` bridge would *not* have flagged `sourceUnavailable` correctly as `expand` is written:
  its single `try` wraps only the `client.artistRelations` call, `identity.mbidFor` is called before
  that `try` opens and `identity.qidsFor` after its `catch` closes, and the bare `for` loop over
  `adapters.all()` in `SegueService.expandEntity` has no `try` of its own — so a throwing bridge
  would have escaped `expand` entirely and broken the SPI contract rather than setting a flag. The
  advantage was **available in one line**, by widening that `try`; it was not a property the route
  came with.
- **Putting the bridge in `wikidata`, or in `musicbrainz`** — both are now build failures, and both
  were watched red rather than assumed. That run is what settled the placement.
- **Pluralising `EntityResolver` alongside `SourceAdapters`** — `SegueService` holds one resolver and
  a list of adapters, which is an asymmetry a second source could have been read as demanding. It is
  unused capacity here, not a blocker: a MusicBrainz seed arrives from the graph already carrying a
  QID, and searching MusicBrainz by name is a capability Wikidata's search already covers. **With a
  fuse:** this is only true while ADR 22 clause 1 holds. If #92 ever lets a QID-less entity exist,
  the singleton becomes a hard blocker the same day.
- **Leaving all of this in the design note and writing no ADR** — cheapest, and it loses on the
  note's own terms. That document's value is that every claim in it was checked against the tree on
  one day, and its central figures are counts over a file that will change. The decision and the
  verdict on ADR 25 need a home that cites `ArchitectureTest` and `SegueService` as the authority
  instead of restating them.

## Consequences

- **Both adapters ship, in the order `wikidata, musicbrainz`, and the order is load-bearing.**
  `SegueConfiguration.sourceAdapters` is the authority, and `CorroborationAcrossSourcesTest` pins the
  behaviour from both ends: two tests differing only in adapter order, each asserting that a tight
  bound goes wholly to whichever adapter comes first. Wikidata is first because it was first. **No
  evidence was gathered about which source *should* win a tight budget** — that is the design note's
  GAP 3, established rather than redesigned, and GAP 4 is its companion: `sourceUnavailable` and
  `truncated` are ORed across adapters, so a flag no longer says which source raised it. GAP 4 is
  filed as **#148**; GAP 3 is not filed.
- **Every `PERSON` or `GROUP` expansion now costs one extra Query Service round trip**, spent by
  `mbidFor` before anything is known, including for the seeds MusicBrainz has nothing for. Not
  measured against production latency, and live rather than hypothetical.
- **A P434-only outage reads downstream as "this artist has no members".** The bridge degrades
  instead of throwing, because the seam declares no failure type and `SegueService.expandEntity`
  wraps nothing; an empty MBID is how the adapter says MusicBrainz holds nothing for this seed. The
  loss is narrower than it first looks — `WikidataSourceAdapter.supports` returns true for every kind
  so it always runs alongside, and it sets `sourceUnavailable` when its own reverse pass fails — so a
  general Query Service outage does surface, unattributed. The residual is the narrow case.
- **Ten issues were filed on this branch, and not one of them was found by anything failing.**
  `check` was green throughout; each came out of somebody checking a claim against the tree — #139
  and #140 by checking which fences a new adapter package would inherit, #141 by checking whether a
  fixture QID resolves, #142 by checking what MusicBrainz actually uses to relate one act to
  another, #143 by checking a javadoc's stated reason against the committed fixture, #144 by
  checking this ADR's own OpenStreetMap argument against what ADR 53 already said, #145 by checking
  the developer guide's rule table against `ArchitectureTest`, and #146, #147 and #148 by the
  whole-branch review reading the finished code and its deferral list. That is the enumeration;
  what each says, and whether the condition it describes was already there at the merge-base,
  follows.
  - **#139** — `theExporterNeverSpeaksToANetwork` passes `..wikidata.WikidataClient` to a package
    predicate, which matches no class, so that third of the rule is inert while its javadoc describes
    a ban it does not impose. **Condition present at the merge-base**; not #91's; the new argument
    added to that rule is a package identifier and does bite.
  - **#140** — the pairwise sibling rules covered 8 of 12 ordered adapter pairs before this branch,
    leaving `tinker → wikidata` among others unforbidden today. **Condition present at the
    merge-base.** This branch reaches 16 of 20 at five adapters; the four still open are #140's, none
    created by #91.
  - **#141** — `CLAUDE.md` states that the `Q9000xx` fixture QIDs are placeholders and not real
    Wikidata ids. **Every one checked resolves to a real Wikidata entity**, so code is being written
    on a guarantee that does not hold. **Condition present at the merge-base** — the sentence is
    there — and not #91's. Not an ADR 51 breach either: no name appears and nothing is framed as
    anyone's taste. Not repaired here; one javadoc on this branch that had derived a safety argument
    from it was rewritten to say what is true instead.
  - **#142** — `subgroup` is the only MusicBrainz relation that could yield a group-in-group edge,
    and choosing between `MEMBER_OF` (P463) and `PART_OF` (P361) for it is a clause 3 decision. Left
    unmapped deliberately, and filed so that the whitelist's silence is a record rather than an
    absence. **Raised by this branch**, which wrote the whitelist.
  - **#143** — the MusicBrainz response already carries each neighbour's artist type, and the adapter
    returns no `neighbors()`, so `SegueService` falls back to one `EntityResolver.fetch` per new
    neighbour. An avoidable Wikidata round trip per neighbour, found by opening the committed fixture
    instead of accepting a javadoc's stated reason. **Raised by this branch**, which wrote the
    adapter.
  - **#144** — ADR 53 sends a reader who wants to reach restaurants to #91, and the OpenStreetMap
    alternative above argues that a different source does not fix it, because the obstacle is the
    identity spine and the vocabulary. **Half and half**: ADR 53's sentence is at the merge-base, the
    disagreement is this ADR's doing. It is the amendment decision the ADR 53 bullet below describes,
    filed rather than made here, because ADR 1 makes ADR 53 immutable.
  - **#145** — `docs/developer-guide.md` enumerates every rule in `ArchitectureTest` by hand and
    nothing checks the enumeration. **Half and half**: the table was **exact** at the merge-base, at
    35 names, and this branch renamed two rules and added one, whereupon it went stale silently while
    the build stayed green. The same issue records two more enumerations in that guide which this
    branch falsified the same way.

  - **#146** — `MusicBrainzClient.throttle()` reads `lastRequestAt` and then sleeps the remainder,
    which is check-then-act on a `volatile` field: two concurrent callers read the same instant,
    wait the same remainder and fire together, so the method's own stated invariant — no two
    requests less than the minimum interval apart — does not hold under concurrency. One client is
    a singleton behind `SegueService` over the servlet transport, so concurrent expansions share
    it, and MusicBrainz's ~1 request/second is a **policy for anonymous `ws/2` access** rather than
    a performance guideline. Nothing in the repo drives concurrent expansions today and every test
    is single-threaded. **Raised by this branch**, which wrote the client.
  - **#147** — the GAP 9 guard validates `targetQid` and argues at length that it must not depend
    on which bridge is wired, and the two other externally-sourced strings in the same method —
    `seedMbid` and `relation.targetMbid()` — go unvalidated into `sourceRef`, where `Provenance`'s
    compact constructor throws on a tab or a newline. That `IllegalArgumentException` escapes
    `expand()`, which `SegueService.expandEntity` does not wrap, so the expansion aborts instead of
    degrading. Not reachable through the shipped bridge, because `WikidataMusicBrainzIdentity`
    validates both directions against a UUID pattern — **which is exactly the dependency the guard's
    own javadoc says it does not have.** **Raised by this branch**, which wrote both.
  - **#148** — GAP 4: `SegueService.expandEntity` ORs `sourceUnavailable` and `truncated` across
    every adapter, so a caller learns *a* source failed and never which. **Half and half**: the
    aggregation is at the merge-base and was unambiguous with one adapter; the ambiguity is this
    branch's, and it bites hardest on the bridge, whose own failure yields an empty result with
    `sourceUnavailable` **false**. This ADR and the design note both record GAP 4 as established
    and unfixed; until #148 it was the one deferral in that set with no issue behind it.

  **"Predates" is true of some of the defects and of none of the issues, and collapsing those two is
  what this roll-up got wrong before.** All ten were filed during this branch, on 2026-08-30, the
  earliest of them 43 minutes after its first commit and the last three after its final code commit
  — so no issue predates the branch. What predates it is the condition each one describes: three
  wholly (#139, #140, #141), three in part (#144's ADR 53 sentence, #145's unenforced table,
  #148's aggregation), and four not at all (#142, #143, #146 and #147, which are about the adapter
  and the client this branch wrote).
- **What this does not settle.** The two unbuilt adapters are unbuilt, and Open Library's obstacle is
  #92's question rather than the SPI's. **ADR 22 clause 3 is untouched**, deliberately: MusicBrainz
  was chosen partly for not forcing it, so this ADR is no evidence at all about whether the
  vocabulary rule survives a source that does. GAP 3 and GAP 4 are established and not fixed.
- **ADR 53 owes a dated amendment, and it has four things to cover rather than one.** ADR 1 makes
  that ADR immutable, so nothing in it is edited; this list exists so whoever writes the amendment
  does not have to rediscover it. Each was checked at the merge-base and at this branch's head.
  - **Its implementor enumeration is falsified in two of its three counts.** It reads *"In
    `src/main` there is exactly one `SourceAdapter` (`WikidataSourceAdapter`) and one
    `EntityResolver` (`WikidataEntityResolver`) … The seven test doubles in `src/test` — two
    adapters and five resolvers"*. `src/main` now holds **two** `SourceAdapter`s, and `src/test`
    holds **eight** doubles — two adapters and **six** resolvers, the sixth being
    `CorroborationAcrossSourcesTest`'s `AlwaysResolves`. The two counts that still hold are the one
    `EntityResolver` in `src/main` and the two adapters in `src/test`.
  - **"The only source that exists" is stated twice and is no longer true** — once where the ADR
    explains why it needed writing, and once in its "what remains open" entry for #91. What the
    clause was *supporting* in both places is untouched: no second source reaches restaurants, and
    this ADR is the reason why.
  - **Its restaurants conclusion is contested rather than falsified, and the difference matters.**
    ADR 53 says reaching that domain *"requires a different source, which is #91's territory"*. The
    OSM alternative above argues a different source does **not** fix it, because the obstacle is the
    identity spine and the vocabulary rather than the source. That is an argument against a
    conclusion, not a count that moved, and an amendment should say so in those terms.
  - **Its prediction about ADR 42's seam did not happen**, which is the weakest of the four and is
    included because it is a claim about this very issue: *"ADR 42's seam is the first thing it will
    press on."* `KindMapper` did not move and needed no change — MusicBrainz states no Wikidata
    classes, so `KindMapper.rederive` returns a no-classes claim untouched, and `musicbrainz` names
    that class in javadoc while importing nothing from `wikidata`. The first thing #91 actually
    pressed on was the architecture fences. The prediction was reasonable and the seam is still the
    one a class-stating source would move; it simply was not this source.
- **Nothing in `./gradlew check` needs the network**, and nothing in it reads `~/.segue/segue.db`.
  The two new live tests are `@Tag("live")` and excluded; the probe above was run as a scratch
  `liveTest` and left nothing behind.
- **`docs/developer-guide.md`'s "adding a source adapter" walkthrough understated step 1**, which
  said to extend the sibling rules. It is corrected alongside this ADR to name what a new package
  actually fails to inherit, because that walkthrough is the document a sixth adapter's author will
  read instead of this one.
