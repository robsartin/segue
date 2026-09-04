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

- **A merge is an appended equivalence, never an edit.** The log stores what the owner asserted, and
  nothing already written is rewritten or deleted: the local id keeps its node, its edges and its
  affinity row. That is what makes a wrong merge retractable by the ordinary mechanism of
  [ADR 44](0044-retraction-as-a-new-claim.md) and what keeps every earlier entry meaning what it
  meant when it was written, which [ADR 19](0019-assertion-log-source-of-truth.md) requires. An
  in-place rewrite would have been simpler to read and would have destroyed both properties.
  **It applies in two places, and they are not the same time.** Ingest and every boot replay
  *carry* it — `IngestService` copies the node and the edges onto the canonical id, and the
  `IdentityMerge` port carries the rating — while `Equivalences` *resolves* it at read time, for a
  single run of `recommend` or `rate`, which replay with no carry wired and so may meet a merge
  nothing has carried yet. Each of those classes is the authority for its own half. An earlier
  draft of this bullet said the merge applies only "on the way out", which described the read-time
  half and left the writes out.

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
- ADR 33's *"Two layers, two stores"* becomes three layers and two stores. The third layer's tool
  writes to the world-fact store and is fenced out of the taste layer's — but **`note_affinity`
  stops being the only writer of affinity**, which is a real change to ADR 33's second bullet and
  not a re-reading of it. Declaring a merge carries the owner's rating onto the canonical id, and
  the carry is a write through `AffinityStore.updateRating`, wired into the running application by
  `SegueConfiguration`; the statement behind it upserts, so a merge can create a row for an id
  nothing has ever rated. It is bounded to a score, never a note, and it never overwrites a rating
  stamped later than the one it carries. ADR 33's amendment records the correction.
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

**Amendment (2026-09-02, issue #178): the merge bullet's graph half — *"the local id keeps its
node, its edges and its affinity row"* — is now false in its middle term. The edges move.**

Nothing above is withdrawn, no sentence above is edited, and the decision this ADR made is
unchanged: a merge is still an appended equivalence and never an edit, the log is still untouched,
and a wrong merge is still retractable by [ADR 44](0044-retraction-as-a-new-claim.md)'s ordinary
mechanism, because the fold asks `Retractions.survives` before it folds anything. What changes is
where the merged entity's edges live once the log has been read.

**What the bullet says, and what is true instead.** Four sentences of it no longer describe the
code, and each is replaced by one line here rather than corrected in place:

- *"the local id keeps its node, its edges and its affinity row"* — it keeps its **node** and its
  **affinity row**. Its edges are resolved onto the canonical id at projection, so they exist once.
- *"`IngestService` copies the node and the edges onto the canonical id"* — it copies neither. The
  canonical node is built by `Equivalences.standIns` in a pre-pass that runs before either fold
  begins, and the edges are folded rather than copied.
- *"Ingest and every boot replay carry it"* — every boot replay folds it; live ingest no longer has
  a graph half to carry. `IngestService.record` sees one claim and not a log, so a `SameAs` arriving
  there appends, gets its canonical stand-in node, and moves no edge until the next boot — the same
  contract `retract()` already gives, and for the same reason ([ADR 24](0024-sqlite-assertion-log.md),
  [ADR 18](0018-graph-engine-gremlin.md): `GraphStore` cannot remove or rewrite an edge).
- *"Each of those classes is the authority for its own half"* — `Equivalences` is now the authority
  for both halves. `Equivalences.foldEndpoints` is the rule; `GraphProjector.project` and
  `LogProjection.of` are its two callers, and each is still the authority for its own fold's
  mechanics. This ADR mirrors no table of theirs.

The rating half of a merge, and the correction ADR 33's amendment records about `note_affinity`,
are untouched by all of this.

**The measurement that forced it, as dated observations (2026-09-02).** All of it is an invented
fixture with invented ids, so [ADR 51](0051-what-an-adr-may-quote.md) does not bite: there is no
known-list behind it. Driving the real `recommend` against a scratch log, a merge of one minted
entity carrying 2, 5 and 20 owner edges moved 8, 10 and 18 of the top 25 scores; ranks moved by at
most three places; one entry left the page and one entered at twenty edges; and the worst single
score change was −12.50 %, which unseated the rank-1 candidate whose degree went 7 → 8. The issue's
own estimate of "roughly 3 %" turned out to be the large-degree end of that curve rather than its
middle. **Nothing in [ADR 57](0057-the-floor-reports-itself.md)'s reading would have told an
operator any of it had happened**: the pool's median degree read 19 → 20 at five edges and identical
at two and twenty. The positive control — the same fixture with the owner's edges counted once —
returns the pre-merge top 25 in the pre-merge order, largest score difference 0.0000000000, so the
target state is exact and needs no tolerance. `MergeDoesNotInflateDegreeTest` is that control kept,
and it has been seen red at all three degrees with the copy restored, quoting those same figures.
The instrument was validated first: two replays of one unchanged log are byte-identical.

**Why the fix is in the projection and not in the scorer.** The inflated degree had four readers —
`Scorer`'s division by the candidate's own degree, `PathRanking.isHub` on the routing side, the
exporter's picture, and `find_paths` offering two identical routes — and one fold corrects all four.
Correcting it inside the scorer would have added a third job to a number
[ADR 45](0045-recommend-by-normalised-lift-with-routes.md)'s 2026-08-29 amendment already records as
doing two it cannot separate, and would have left the other three readers reading the old number
with nothing comparing them. `BothFoldsAgreeTest` is what stops the two folds drifting apart while
they move together, and it reds with the copy restored just as the ranking guard does.

**How it was built, because the order is part of the record.** The whole change was tried first as a
Mikado probe and reverted: **11 tests failed**, and the prerequisites they named were then landed
leaf-first, green at every commit. Two of those leaves are worth naming. `Equivalences.standIns`
walks the log itself in a second pass rather than reading the store, because at the moment the
stand-in map is wanted nothing has been projected yet and the store cannot answer. And
`Equivalences.localsOfMerges` became the single predicate for "which surviving merges have a local
side, and what did it look like" — the exporter used to keep its own answer in its node accumulator,
which is the two-readings-of-one-log shape this issue is a member of.

**A fold that would collapse an edge onto itself drops that edge.** The owner minting one thing
twice and merging both onto one canonical id is a real path, and the edge between the two would fold
to a self-loop nobody claimed, so `foldEndpoints` yields nothing for it and both folds skip it. This
is scoped to what the fold creates: a self-loop already in the log is left exactly where it is,
because refusing those is a different rule and belongs on the record, where every writer meets it.

**Consequence for `exportGraph`.** A merged local id becomes a node with no edges, and it is drawn —
in a `full` or `subgraph` export, like any other orphan, with nothing hiding it. That is asserted on
the DOT artefact itself and not only on the records behind it. The node *order* of an export holding
a merge also changes, because the stand-ins are now listed by the pre-pass rather than at the merge's
row; the separate, pre-existing question of node-order determinism in that writer is issue #207.

**Rejected, with the reason each lost.**

- **Exclude merged local ids from degree in the scorer** — `CandidateSweep` takes the equivalences
  and subtracts the duplicate edges itself. By far the cheapest: no projection moves, no export
  artefact changes, and no clause of this ADR would have needed amending. **Lost because it is the
  shape of the bug it fixes.** A corrected degree only the scorer knows about is a second reading of
  one log that the other three readers do not share, which is exactly the family (#176, #177, #178)
  this fix belongs to; and it amends ADR 45's formula rather than this ADR's bullet, giving degree a
  third job on top of the two it already cannot separate.
- **Accept and record** — state the residual and make it visible beside ADR 57's floor reading.
  Honest, and this repository has shipped four documented refusals that each beat their fix.
  **Lost on the measurement**: 12.50 % on one score, enough to unseat rank 1 and swap an entry off
  the page, at a merged degree the owner would call ordinary, and it compounds with every merge in
  exactly the neighbourhood he cares most about. Recording it honestly is itself a build, because
  "silent" is half the defect.
- **The rename form of the fold** — the local id becomes the canonical id everywhere, node included,
  so the graph holds one node and the export has no orphan to draw. Cleaner. **Rejected for now**
  because it deletes more of the bullet above than the defect requires, and `get_entity` on a local
  id would start answering nothing with no redirect to offer instead. Worth re-opening if the
  isolated node turns out to be worse than the missing one.
- **A `GraphStore` edge delete**, so the carry could move edges incrementally. **Lost** because no
  `SameAs` reaches a live graph, so it would widen the port that exists to keep the engine choice
  reversible (ADR 18) for a case that does not arise.

**Residuals accepted rather than closed**, each visible in the code today:

- **The stand-in's kind is taken as the claim stated it** on the bypass path — a merge whose local
  side is a plain node claim carrying classes gives a stand-in with the claimed kind while both folds
  re-derive the local node's own. Both folds agree, so `BothFoldsAgreeTest` cannot see it; re-deriving
  inside `domain` would drag `KindMapper` in and break `noPackageCycles`.
- **The stand-in rule has four homes**: `Equivalences.standIns`, `IngestService.standIn`,
  `OwnRun.labelsInTheProjection` and `ratings/Labels.forQids`. The last two read labels off the log
  rather than nodes off a graph, which is why they are copies rather than callers. Nothing fails if
  one drifts.
- **How many merges the owner's real graph holds is unmeasured.** Nothing in this work opened
  `~/.segue/segue.db`; every figure above is from an invented fixture, so the size of the effect this
  amendment removes — `merges × their degree` — is known for the fixture and unknown for the graph it
  was built to stand in for. The spec left this open and it stays open.
- **A local id merged twice leaves an orphan stand-in under the *first* canonical id** while its
  edges land on the last, because the stand-in map is `putIfAbsent` and the canonical map is
  last-wins. Both folds agree about it; it is a correction's leftover rather than anything the owner
  claimed.

**Amendment (2026-09-03, issue #222): the first residual above is closed. The stand-in carries the
kind the fold re-derived for the node it stands in for, and the re-derivation reaches `domain` as a
parameter.**

Nothing above is withdrawn and no sentence above is edited. The residual said it plainly: *"the
stand-in's kind is taken as the claim stated it"* on the bypass path, *"re-deriving inside `domain`
would drag `KindMapper` in and break `noPackageCycles`"*. The first half was true and is now false;
the second half named the wrong rule and was still right about the obstacle.

**What the code does now.** `Equivalences.localsOfMerges` and `Equivalences.standIns` take the
re-derivation as a required `UnaryOperator<NodeAssertion>`, and `LogProjection.of` and
`GraphProjector.project` each hand in the `KindMapper::rederive` they already apply to every node
claim they fold. A merge's canonical node therefore ends the fold as the same kind as the node it
stands in for. Those classes are the authority for the mechanics; this amendment mirrors no table
of theirs.

**The measurement, on an invented fixture** — invented ids, invented labels, no known list behind
it, so [ADR 51](0051-what-an-adr-may-quote.md) does not bite. A bypass `NodeAssertion` naming a
local id as `WORK` while stating one class the whitelist does not know, then a merge onto a
canonical id: before the change, **both** folds held the local node as `CONCEPT` and the canonical
node as `WORK`, failing on `expected: CONCEPT but was: WORK`; after it, both hold `CONCEPT`. Both
folds were wrong in the same direction, which is exactly why `BothFoldsAgreeTest` was silent — it
compares the two folds to each other. `StandInKindMatchesTheLocalNodeTest` compares the stand-in
with the node beside it, in each fold separately, and was seen red in both and planted against once
per fold.

**The obstacle was stricter than the residual said, and that changed the answer.**
`ArchitectureTest.noPackageCycles` was the rule named; the binding one is
`domainHasNoThirdPartyDependencies`, which allows `domain` only `domain`, `java` and `javax`. So a
port interface for the re-derivation was never available either — `domain` may not reach `port` —
and the seam had to be a `java.util.function` type. That is not a workaround for the rule; it is
what the rule leaves, and the `localsOfMerges` javadoc had already named the shape: *"only a rule
that moved re-derivation behind a port would close it"*.

**Rejected, with the reason each lost.**

- **The merge event carries the re-derived kind when it is written** — `SameAs` gains a `NodeKind`,
  set at the moment of the merge, and both folds read it. No package problem at all. **Lost because
  it writes a derived value into an append-only log**, which is the thing
  [ADR 42](0042-store-p31-and-rederive-kind-at-projection.md) exists to undo: a kind frozen into a
  row is immune to every later correction of the whitelist, which is the ratchet issue #60 removed
  at the cost of two full re-seeds. It also fails to fix what it was proposed for — every `SameAs`
  already in the log carries no kind, so the fold needs the fallback anyway and the lag survives on
  precisely the rows that exist.
- **Copy from the resolved local node in a post-pass.** The issue's own first candidate, and it
  cannot be taken as stated: **neither fold has resolved any node at the point the stand-in is
  built.** The pre-pass runs before the fold, and it has to — an edge claimed earlier in the log
  than the merge that names its endpoint arrives on the canonical id first, and
  `TinkerGraphStore.record` refuses an endpoint it has never seen. As a post-pass the exporter could
  do it, and the boot replay could not: a `GraphStore` cannot say which canonical nodes were
  stand-ins, so that fold would keep its own record of the pre-pass — a second answer to "which
  merges have a local side", the two-readings-of-one-log shape the 2026-09-02 amendment spent an
  issue removing.
- **The stand-in carries the local node's classes** so the folds re-derive it like any other node.
  **Lost** because neither fold re-derives a `NodeRecord`, so each would need its own conversion,
  and because it would assert classes about the *canonical* entity that no source ever stated for
  it — which is what `standIns` already refuses: a stand-in carries what it was given rather than
  inventing a class.
- **Move `KindMapper` into `domain`.** ArchUnit would permit it, since a class with only private
  constructors is a static registry rather than a value type. **Lost on what it puts there**: a
  whitelist of Wikidata `P31` ids, grown from measurements against Wikidata and owned by the adapter
  that fetches them, made visible to every domain type.
- **Accept and record again.** The path is unreachable from today's sources — no source can allocate
  a `Q00` id. **Lost for the reason the 2026-09-02 amendment already gives when it declines that
  defence**: it is the same premise spec ruling 2 refuses to rely on, and the fold admits a
  `NodeAssertion` on that path *because* it refuses to rely on it.

**What this does not settle.**

- **The live path does not re-derive at all.** `IngestService.record` applies a node claim as it was
  stated, so `IngestService.standIn` — which copies the local node off the running graph — answers
  with the claimed kind there, and agrees with the local node beside it because that node is
  un-re-derived too. That is the same lag [ADR 42](0042-store-p31-and-rederive-kind-at-projection.md)
  already accepts for the node itself — a claim's kind is stale until the next boot's fold re-derives
  it — extended to the stand-in beside it on the same terms: a bypass claim leaves its node and its
  stand-in at the claimed kind together until the next boot, at which point both `LogProjection` and
  `GraphProjector` re-derive both through `KindMapper::rederive`. Nothing in production reaches it:
  `OwnRun` appends a merge through `claim()`, which holds no graph. Whether ADR 42's re-derivation
  should also run on the live write is a separate question nobody has argued.
- **The stand-in rule still has four homes.** This closes the kind lag in one of them and unifies
  none of them; that residual stands, and is issue #220.

**Amendment (2026-09-03, issue #221): the last of the residuals above is closed — a local id merged
twice now leaves nothing under the first canonical id, unless a surviving edge still names it.**

Nothing above is withdrawn and no sentence above is edited, the residual bullet included: it is the
true account of the code between #178 and this issue, and it is what this amendment answers.

**What was there, measured on `2e01341`** on an invented log (ADR 40, ADR 51: no known-list behind
it) holding one minted entity with one owner edge, merged onto one canonical id and then onto
another. The exported fold and the boot replay each held a node under the **first** canonical id
carrying the merged entity's label and no edges; the `full` DOT drew three nodes under one label for
one entity, of which the owner had claimed two; and `IdentityMerge.follow` was called for **both**
merges on every replay, so `carryingRatings` wrote the owner's rating onto the id he had corrected
away from — which, by [ADR 48](0048-a-high-rating-counts-as-something-you-have.md), goes on telling
`recommend` he owns it.

**The rule, in one place — first landed last-wins alone, then widened once more before this closed.**
`Equivalences.stands` answers whether a merge still contributes a node. All four homes of the
stand-in rule ask it: `Equivalences.standIns` skips a merge it answers false for,
`IngestService.apply`'s `SameAs` arm does neither half of its job for one, and
`OwnRun.labelsInTheProjection` and `ratings/Labels.forQids` lend it no label.

**They ask one predicate; they do not ask it of one `Equivalences`, and the difference is visible in
exactly this case.** Equivalences that have never heard of the local id answer **true**, which is
what keeps `IngestService.record` — applying one claim with `Equivalences.NONE`, having no log to
read — creating its live stand-in exactly as before. So three homes agree about a twice-merged local
id *because* they read the same log, and the live one still builds the stand-in the correction
retired. That is the shape [ADR 42](0042-store-p31-and-rederive-kind-at-projection.md) already
accepts for a node's kind, extended to the stand-in on the same terms: the live path applies the one
claim it was handed and lags until the next boot's fold applies the whole log. Nothing in production
reaches it — `IngestService.record`'s own javadoc records that nothing sends a `SameAs` there, since
`OwnRun` appends a merge through `claim`, which has no graph half. `StandInAgreesInEveryHomeTest`
(issue #220) pins the two answers per home rather than asserting them equal.

**Fixing the exporter's fold alone would not have been a fix, and that is measured too.** With
`Equivalences.standIns` corrected and `IngestService` untouched, the boot replay went on building
the same node a second time at the merge's own row, the two folds held different graphs, and the
whole suite stayed green — `BothFoldsAgreeTest` could not see it because its fixture held no
twice-merged local id. It holds one now, and removing either half of the fix reds it naming the id.

**A legal, supported-flow log could not be replayed under last-wins alone, and that is what widened
the rule again before this closed.** Landed first as `stands` = last-wins only — a superseded merge
contributes no node at all — a fix-round review reproduced, on `fdd420d`, a log the supported flow
itself can produce: `[node(WREN), minted(CORRECTED), merged(CORRECTED→MISHEARD), owned(WREN→MISHEARD,
"INFLUENCED_BY"), merged(CORRECTED→WATERMARK)]`. Under last-wins alone,
`Equivalences.standIns(log)` names only `WATERMARK` — `MISHEARD` is dropped entirely, though an
`OwnerEdge` still names it — and `GraphProjector.project` then throws `replay failed at sequence 4`,
`assertion references unknown entity … - upsert the node first`, on a row ADR 19 forbids deleting.
`LogProjection` tolerates the same edge as dangling rather than throwing, so the two folds disagreed
in the worst direction this design set out to prevent. The scenario is reachable through the
supported flow and not a constructed edge case: `OwnRun` offers a merge's canonical id as a claimable
endpoint the moment its stand-in exists (`labelsInTheProjection`), so an owner who merges, claims an
edge against the new canonical id, then corrects the merge produces exactly this log, and every row
in it is one ADR 19 forbids deleting.

**Ruling.** A superseded merge's stand-in survives while any surviving `AssertionRecord` or
`OwnerEdge` names its canonical id as an endpoint: the node is then not an orphan — it has an edge —
and the export shows the owner's real claim rather than silently dropping it. `Equivalences.stands`
is widened from last-wins alone to *last-wins OR a surviving edge names this merge's canonical id*,
computed once in `Equivalences.in` from the same pass that builds `canonicalByLocal`, over surviving
rows only (`referencedEndpoints`). The rating carry stays last-wins only — a separate, narrower
predicate, `Equivalences.last` — because a node surviving on account of an edge is a fact about the
graph, not the owner's opinion about the thing he corrected himself onto; widening `stands` without
keeping the carry narrow would have reintroduced the defect the first round of this issue fixed, a
rating written onto every canonical id a local id ever touched rather than only the one that stands
today.

**Two alternatives were considered and rejected, for the widening specifically:**

- **Re-point the edge onto the corrected canonical id.** Rejected: it silently rewrites what the
  owner actually claimed — he named the *first* id, not the second, and the first id may itself turn
  out to be a real, distinct entity the correction says nothing about. Segue does not edit a claim on
  the owner's behalf; ADR 19 already settles that a correction is a new claim, never an edit of an
  old one.
- **Have `GraphProjector` tolerate the missing endpoint as a dangling edge**, matching how
  `LogProjection` already behaves. Rejected: it replays the owner's claim into nothing without saying
  so — the same silent-data-loss shape issue #101 fixed once already for the rating deck, and
  precisely the failure mode `danglingEdges()` exists to report rather than to produce.

Each of the four homes was checked against its own existing coverage after the widening —
`OwnRunTest.shouldRefuseTheCanonicalIdOfAMergeWhenALaterMergeCorrectedIt` still reds correctly,
because that fixture has no surviving edge naming the corrected-away id — and given one added case
where the surviving-edge path was previously untested,
`RatingsRunTest.shouldKeepACanonicalIdsLabelWhenASurvivingEdgeNamesItDirectlyThoughALaterMergeCorrectedIt`.

**Rejected, from the original design, and still rejected under the widened rule.**

- **Name the orphan in the export rather than retire it.** Mark the node as a superseded stand-in so
  a reader knows why it is there. Honest about the log, which holds both merges, and it changes least
  about what is in the graph. **Lost on three counts.** A stand-in exists so that a folded edge has
  an endpoint to land on, and a superseded merge that no surviving edge needs folds no edge, so the
  node has no job left — annotating it is more machinery for less truth. It states a fact about the
  owner's correction history inside the artefact this ADR's consequences call a picture of the
  **world** graph, the one that may be shared. And it costs a node attribute reaching `NodeRecord`,
  both writers and both folds, against one predicate asked in four places — while leaving the taste
  half writing a rating onto an id the owner corrected away from, which no annotation in the export
  reaches.
- **Refuse a second merge of one local id.** `OwnCli` says it, and it must go on saying it: a second
  merge is how a wrong merge is corrected, and the only alternative left to the owner would be a
  retraction that takes every other claim about the id with it.

**Residual, and it cannot be closed by any later change either.** A rating an earlier build already
carried onto a superseded canonical id **stays**: `carryingRatings` copies a score and never removes
one, and `AffinityStore` has no delete (ADR 39, ADR 46). What this amendment changes is that no
further boot re-writes it, and — for the ordinary case, where no surviving edge names the superseded
id — that `ratings/Labels.forQids` no longer supplies it a label, so it reads as `(not in the graph)`, which
is what that string was written for. Where a surviving edge does name the superseded id, the node
itself stands and carries the merged entity's label again — but the carried rating is not thereby
made correct: `Equivalences.last` stayed narrower than `Equivalences.stands` on purpose, so a label
reappearing on the node does not un-orphan a rating that only ever belonged to the merge that stands
today. Whether segue should offer any way to disown such a row is a separate decision nobody has
argued.

---

**Amendment (2026-09-04, issue #228): the 2026-09-03 ruling above counted an edge the fold does not
keep, and an owner claim that would leave the log unbootable is now refused before the append.**

Nothing above is withdrawn and no sentence above is edited: it is the true account of the code
between #221 and this issue, and it is what this amendment answers.

**Three phrases in that ruling are now wrong, and this is where they are corrected.** It reads
*"while any surviving `AssertionRecord` or `OwnerEdge` names its canonical id as an endpoint"*, and
*"computed once in `Equivalences.in` from the same pass that builds `canonicalByLocal`, over
surviving rows only"*. All three are superseded:

- **"any surviving edge" is now "any edge the fold KEEPS."** Surviving and kept are different sets. An
  edge the fold *withdraws* — because it names a canonical id a retraction emptied
  ([ADR 44](0044-retraction-as-a-new-claim.md)'s #224 rule) — survives every retraction and claims
  nothing all the same, and so does one the fold *collapses* onto a single id (#178). Neither can
  keep a superseded stand-in alive, because neither reaches the graph.
- **"the same pass" is now a least fixed point.** Whether an edge is kept depends on which canonical
  ids are emptied, which depends back on which stand-ins survive, which depends on this set. The
  emptied set is therefore computed from the empty set upwards until it stops growing, before the
  reference set can be built at all. The termination argument, the cost and a dated measurement are
  [ADR 44](0044-retraction-as-a-new-claim.md)'s 2026-09-04 amendment's to carry.
- **"over surviving rows only" is now over the rows the fold keeps**, which is narrower and holds
  for the same reason the original clause gave: a retracted edge claims nothing and keeps nothing
  alive, and neither does one withdrawn or collapsed.

**What it looked like, measured on `a7c3455`.** On a log the supported flow itself produces — a
correction, plus an unrelated retraction that empties the other end of the one edge naming the
superseded id — the exported fold and the boot replay each held a labelled node with no edges under
the id the owner had corrected himself away from, carrying his withdrawn working title, while the
same fold reported that edge as withdrawn. The rating carry stays where the 2026-09-03 ruling put
it: `Equivalences.last` is still narrower than `Equivalences.stands`, and this changes nothing about
it.

**Both folds and both label copies move together, which is the point.** Three of the stand-in rule's
four homes read `Equivalences.in`, so `OwnRun` stops offering an endpoint whose node is an artefact
and `ratings/Labels.forQids` reports a rating carried onto it as `(not in the graph)` — which is
what that string was written for. The fourth, `IngestService.standIn` on the live path, is handed
`Equivalences.NONE`: it holds no log, so it has no edge to withdraw, and it is unchanged. The count
of homes is not reduced and the residual about it stands.

**An owner claim is validated BEFORE the append, and this is the gate.** `IngestService.claim` — the
one gate every owner claim passes, `OwnRun`'s included — now refuses a `SameAs` whose local side the
projection holds no node for, and an `OwnerEdge` whose **folded** endpoint the fold would hold no
node for. Both were already refused by `OwnRun`, which is why the logs issue #228 measured are
reachable only by a caller that comes straight to `claim` or writes the row into SQLite by hand; a
guard in front of one caller is not a gate, and the log is append-only, so a claim rejected only at
replay is rejected at every replay for good.

**The gate's questions are narrower than the tool's, deliberately, and both homes stay.**
`OwnRun.declareMerge` requires a merge's local side to be something the owner *minted*, because
pointing a merge at a sourced entity is a different claim that tool does not make, and
`OwnRun.assertEdge` refuses an endpoint it does not *offer*. The gate asks the fold's own questions
instead — any surviving node claim, and the endpoint the fold would resolve to — so it refuses only
what cannot boot, and a claim naming a merged local id that the fold resolves onto a held canonical
id is accepted rather than second-guessed. Two questions, two homes; the friendlier message stays
the tool's. **Moving the refusal into `OwnRun` and deleting it here was rejected** for that reason,
and **refusing in `OwnRun` only** was rejected because the whole issue is about the path that does
not go through `OwnRun`.

**Its refusal is #233's `UnknownEndpointException`, not a second type.** That gate and
`IngestService.record`'s ask one question — does the projection this claim is about hold a node for
the id it needs — of two different projections: `claim` has a log and no graph, so it asks the log's
fold; `record` has a graph and no log view, so it asks the running graph. They share the type and
each writes its own sentence, because a message saying *"the graph"* about a check that asked the
log is the caller-facing misdescription [ADR 27](0027-mcp-protocol-conformance.md) exists to keep out.

**And a log that already carries such a row is refused at boot, by name.** `GraphProjector.project`
checks every edge the fold keeps against the nodes the fold holds before it applies anything, and
throws one message listing each offending sequence number, the id no node stands for, and the
repair. It reports every row rather than the first, departing from the replay loop's own rule,
because this is a decidable property of the log and an operator repairing one wants the list rather
than one row per restart. What the repair is, and why appending a node claim is not it, belongs to
[ADR 44](0044-retraction-as-a-new-claim.md)'s 2026-09-04 amendment. `LogProjection` deliberately
still tolerates the same edge as dangling: the exporter has to produce a picture, and that ADR
argues why the boot's answer is the opposite one.

**A residual, recorded rather than repaired.** `IngestService.record` — the sourced path — refuses an
edge whose endpoints the RUNNING GRAPH holds no node for (#233), which is the same shape this
amendment closes for owner claims, asked of the projection that path can see. It is not the same
guarantee: the running graph is stale after a retraction until the next boot, so an edge naming a
retracted entity still passes it. That gap is [ADR 24](0024-sqlite-assertion-log.md)'s 2026-09-04
amendment's residual and issue **#234**; the boot refusal above now names such a row.

**Every path in this amendment is fixture-only today**, on a graph issue #227's census measured on
2026-09-04 — the numbers are its, not restated here. That is the argument for the cheapest correct
answer at each ruling rather than the most general one; it is not an argument for leaving any of
them unfixed, since the log is append-only and the first instance of each is permanent.
