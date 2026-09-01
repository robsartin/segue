---
status: Accepted
date: "2026-08-31"
topic: owner-claims-as-a-third-layer
tags: [project, domain, modelling, tooling, privacy]
supersedes: []
related: [wikidata-identity-and-vocabulary, taste-layer-separation, mcp-tool-surface, stand-in-identifiers-cannot-be-allocatable, quarantine-model-generated-assertions, assertion-log-source-of-truth, retraction-as-a-new-claim, layering-and-archunit, all-the-owners-interests-bounded-per-domain, musicbrainz-as-the-second-source, attribute-a-shortfall-to-its-source, what-an-adr-may-quote]
---
# 59. Admit owner claims as a third layer: first-person, uncorroboratable, and projected to the graph

## Context

[ADR 53](0053-all-the-owners-interests-bounded-per-domain.md) commits segue to holding *all* the
owner's interests. [ADR 22](0022-wikidata-identity-and-vocabulary.md) clause 1 makes a Wikidata QID
the identity of everything in the graph. Some of what ADR 53 commits to is not in Wikidata — the
self-published book, the record pressed in an edition of three hundred — so the two decisions
disagree, and issue #92 was filed to settle which one gives.

### The deferral expired on its own stated terms

#92 declined to answer immediately and said to try a second source first, on the grounds that *"the
long tail may shrink enough that the identity question never needs answering"*. That has now been
tried, three times over, and it did not shrink:

- [ADR 54](0054-musicbrainz-as-the-second-source.md) shipped MusicBrainz. Its QID-less neighbours
  turned out to be tribute acts, pseudonyms and billing variants — the *near* tail, not the far one.
- ADR 54's alternatives section assessed Open Library and OpenStreetMap. Open Library is blocked by
  ADR 22 clause 1, because `remote_ids` lives on author records rather than work records — which is
  precisely the indie-author case #92 names. OSM is blocked twice over.
- [ADR 53's amendment](0053-all-the-owners-interests-bounded-per-domain.md) (issue #144) revisits
  the restaurant domain and finds obstacles that are not *"something a choice of source settles on
  its own"* — an OSM identifier is not stable across edits while this log is append-only, and no
  location property is registered. A third source does not remove them.

Three measurements agree that the obstacle is structural. Continuing to defer is no longer a
deferral; it is a decision to exclude a domain ADR 53 committed to holding, taken without saying so.

### The two existing layers, and the gap between them

[ADR 33](0033-taste-layer-separation.md) separates world facts from affinity along three axes at
once — sourced, corroboratable, projected to the graph — and world facts are *yes* to all three
while affinity is *no* to all three. The claim #92 needs is neither: it has affinity's epistemology
(no source, nothing to corroborate against, and it cannot be wrong about itself) and the world
layer's destination (it must be traversable, routable and citable, or it is not in the graph at
all). Nothing in ADR 33 forbids that combination; nothing in it anticipates one either.

## Decision

**Admit local entities and owner-asserted edges as a third layer: first-person like affinity,
projected to the graph like a world fact, and excluded from corroboration entirely.**

- **Three claim types, in `domain`, on `Retraction`'s precedent.** A minted local entity, an owner
  edge, and an asserted equivalence between a local id and the QID it turned out to be. Each is a
  `LoggedAssertion` with its own validation and **no `Provenance`** — there is no source to
  attribute, because the owner is the source. The types and the sealed permits clause are the
  authority for what they hold; this ADR does not restate them.

- **Identity reuses ADR 58's mechanism rather than widening the spine.** Wikibase's `ItemId` grammar
  is `Q[1-9]\d{0,9}`, so a leading zero can never be allocated;
  [ADR 58](0058-stand-in-identifiers-cannot-be-allocatable.md) already claimed that space for
  stand-ins. A local entity takes **two** leading zeros and a stand-in keeps one, so the two
  populations are told apart by *shape* rather than by a numeric range that a later migration could
  walk into. `Qid.check`, every `Q\d+` pattern in `src/main`, and every line of `port`, `tinker` and
  `jena` are unchanged. `LocalEntity` holds the rule and the argument for it.

- **Owner claims are excluded from the corroboration count.** `EdgeRecord.corroboration()` counts
  distinct source ids, so an edge asserted by both Wikidata and the owner would otherwise count two
  — the owner manufacturing agreement with himself, which is the hazard
  [ADR 55](0055-what-the-musicbrainz-adapter-refuses.md) identified when it declined `subgroup`.
  **They route, and they do not vouch.** That is the precise sense in which a first-person claim
  sits *outside* the ladder rather than low on it:
  [ADR 23](0023-quarantine-model-generated-assertions.md) quarantines model guesses because a model
  can be confidently wrong about the world; the owner cannot be wrong about their own shelf, and
  cannot be a second witness to it either.

- **Routing needs no exemption, and recommendation gets none.** `PathRanking` asks
  `EdgeRecord.isUncorroboratedHypothesis()`, which is true only when *every* source is an `llm:`
  one, so an owner edge is already not a hypothesis and paths through it are already not demoted —
  both are unchanged. An owner edge counts toward degree like any other, so a local entity earns
  candidacy by being connected rather than by being the owner's. The owner can always **route**
  through what they asserted, and gets **recommendations** from it only once it is connected enough
  to earn them.

- **A merge is an appended equivalence resolved at read time, never an edit.** The log stores what
  the owner asserted; the merge applies on the way out, through `Equivalences`. That is what makes a
  wrong merge retractable by the ordinary mechanism of
  [ADR 44](0044-retraction-as-a-new-claim.md) and what keeps every earlier entry meaning what it
  meant when it was written, which [ADR 19](0019-assertion-log-source-of-truth.md) requires. An
  in-place rewrite would have been simpler to read and would have destroyed both properties.

- **When two ids merge into one canonical id and both carry ratings, the earliest in log order
  wins.** The choice between them is arbitrary; that it is *deterministic* is not. `KnownList.promoted`
  sorts so its output is byte-identical run to run, and resolving a collision through an unspecified
  map iteration order broke that — measured, not feared: with `Map.copyOf` in place the answer
  flipped between JVM launches. `Equivalences.resolve` therefore preserves log order and uses
  `putIfAbsent`, and that class is the authority for the rule.

- **Expansion refuses out loud.** No source knows a local entity and none ever will, because its id
  is one Wikidata cannot allocate. [ADR 56](0056-attribute-a-shortfall-to-its-source.md) has just
  finished establishing that an empty `ExpandResult` already carries two meanings — "found nothing"
  and "the source was unavailable" — so teaching it a third would rebuild the defect ADR 56 fixed.
  `expand_entity` returns an error saying there is no source to expand from.

- **The tool is dev-side, not on the MCP surface.** [ADR 26](0026-mcp-tool-surface.md) held
  `assert_edge` back *"until corroboration is visibly working"*, and ADR 56 has made corroboration
  real — but the reason for holding it back now cuts the other way. On the MCP surface a *model*
  could call it, and owner claims are exempt from the corroboration ladder, so an MCP `assert_edge`
  would let a model launder model-generated structure into the one tier that skips quarantine. That
  is exactly what ADR 23 exists to prevent. The tool is the seventh dev-side one, in the shape of
  `rate`, `recommend` and `listRatings`, and joins `ArchitectureTest.DEV_TOOL_PACKAGES` so that
  every sibling fence covers it without anyone remembering to widen seven rules by hand.

## Alternatives considered

- **Keep QID-required (#92's option 1).** Honest and by far the cheapest, and it was the right
  answer right up until a second source had been tried. **Lost** because it is now a decision to
  exclude a domain ADR 53 committed to holding, on the strength of three measurements that say no
  source removes the obstacle — and it would be taken silently, by leaving the deferral in place,
  rather than recorded.

- **A second source fills the gap (#92's option 3).** The deferral's own preferred outcome. **Lost
  on measurement rather than on argument**: MusicBrainz shipped and returned the near tail; Open
  Library is blocked by ADR 22 clause 1 and OSM by identity; ADR 53's amendment attributes the
  restaurant obstacles to identifier stability and to an unregistered vocabulary rather than to
  source availability. This is the alternative that had to be *tried* before any of the others
  could be judged, and it was.

- **Owner as a source adapter — `owner:` alongside `wikidata` and `musicbrainz`.** Everything
  downstream works unchanged, the SPI was built for exactly this shape, and it needs no new claim
  types at all. **Lost on two counts.** It makes the owner a data source among data sources, which
  is the thing ADR 23 prevents for models and would now permit for the owner: corroboration would
  count him as a witness, and the exclusion above would have to be re-added as a special case
  *inside* the mechanism that exists to treat sources uniformly. And an adapter that cannot
  `expand()` has no honest answer to `supports(kind)` — it would have to claim every kind and then
  return nothing, which is the ambiguous empty result ADR 56 has just spent an ADR removing.

- **Owner claims as decaying seeds.** Assert an edge; it routes provisionally, and stops routing if
  no source corroborates it within some horizon. Attractive because it keeps exactly one
  corroboration model for the whole graph and needs no exemption anywhere. **Lost because it
  punishes precisely the case #92 exists for**: the indie author no source will ever know is the
  claim that would always decay, so the feature would work for everything except its own motivating
  example.

- **A visibly different id prefix — `L42`, `local:42`.** Honest at a glance, which is a real virtue:
  nobody would ever mistake one for a QID. **Lost on blast radius.** `Qid.check` and every `Q\d+`
  pattern in the project would have to widen, and that reaches into `domain`, which
  [ADR 18](0018-graph-engine-gremlin.md) keeps free of anything it does not need. ADR 58's
  leading-zero space buys the same guarantee — an id that can never collide with a real one — for no
  change to any pattern at all.

- **Automatic match declaration.** When a source later turns out to know a local entity, declare the
  equivalence without asking. Convenient, and it is how you merge the wrong artist. **Deferred
  rather than rejected**: it can be argued for later on evidence about how often the match is right,
  which nobody has yet.

## Consequences

- ADR 22 clause 1 now admits a second identity kind. The cost is that "everything in the graph has a
  Wikidata QID" stops being true, and a reader who assumed it must now read the id's shape. The
  mitigation is that the shape is unallocatable, so the two populations can never collide, and no
  code outside `LocalEntity` and `SameAs` has to know.
- ADR 33's *"Two layers, two stores"* becomes three layers and two stores. The third layer writes to
  the world-fact store and is fenced out of the taste layer's, so `note_affinity` remains the only
  writer of affinity — but that sentence no longer describes the whole first-person surface.
- ADR 26's six-tool surface is unchanged, and the reason it is unchanged has been replaced.
- A first-person claim can now reach the graph, so the sentence in `CLAUDE.md` about the two layers
  never meeting below `SegueService` has been corrected rather than amended: it is not an ADR.
- The graph the exporter writes may now contain entities that exist nowhere else, which is a change
  in what "the world graph can be shared" means. It carries no ratings and no notes, so
  [ADR 16](0016-privacy-and-data-handling.md)'s line is where it was; what is new is that a local
  entity's *label* is the owner's own words rather than a source's.
- A merge that is wrong is retractable, and the retraction is itself a claim. Nothing is ever
  edited or deleted.

## What this does not settle

- **Whether a local entity may ever be expanded** by a future source that learns it under its own
  id. The merge path covers Wikidata catching up; it does not cover a source that knows the entity
  without a QID.
- **Whether `assert_edge` should ever reach the MCP surface** if corroboration matures further. ADR
  26's stated condition is met and this decision declines the surface for a different reason, which
  a later decision may revisit.
- **Whether the collision rule should be anything but arbitrary.** Determinism was the requirement;
  "earliest wins" was the cheapest way to get it. A rule that preferred, say, the more recent
  rating would also be deterministic and has not been argued either way.
- **The migration of ADR 58's remaining stand-ins** into leading-zero form, which is issue #171 and
  is deliberately untouched here.
