# Owner claims: a first-person layer that reaches the graph

**Issue:** #92, decomposed from #78.
**Status:** design, approved 2026-08-31. Not yet planned or built.

## The problem, and why it is open now

#78 commits segue to holding *all* the owner's interests. Some of them are not in Wikidata, and
ADR 22 clause 1 makes a QID the identity of everything in the graph. So the general-interest
commitment and the identity spine disagree, and #92 was filed to settle it.

#92 said to try a second source first — *"the long tail may shrink enough that the identity
question never needs answering"*. That has now been tried and it did not shrink:

- **ADR 54** shipped MusicBrainz. Its QID-less neighbours are tribute acts, pseudonyms and
  billing variants, not the long tail.
- **ADR 54's alternatives section** assessed Open Library and OpenStreetMap. Open Library is
  blocked by ADR 22 clause 1 — `remote_ids` lives on author records, not work records — which is
  precisely the indie-author case #92 names. OSM is blocked twice over.
- **ADR 53's amendment (issue #144)** records that the restaurant obstacle is the identity spine,
  not the source, so no further source fixes it.

So the deferral has expired on its own stated terms.

## Decision

**Admit local entities and owner-asserted edges, as a third layer.**

## The shape: which two properties it borrows

ADR 33 keeps affinity separate from world facts — *"Two layers, two stores"* — first-person, no
`Provenance`, no corroboration, and `note_affinity` **never writes to the graph**. World facts are sourced, corroboratable, and project.

Owner claims need affinity's epistemology and the world layer's destination:

| | sourced | corroboratable | projects to graph |
|---|---|---|---|
| world facts | yes | yes | yes |
| affinity | no | no | no |
| **owner claims** | no | no | **yes** |

This is the whole design. Everything below follows from it.

## Identity

**Local ids reuse the unallocatable-QID mechanism established by ADR 58 (issue #141).** Wikibase's
`ItemId` grammar is `Q[1-9]\d{0,9}`, so a leading zero can never be allocated; every QID pattern in
`src/main` is `Q\d+`. A local entity therefore carries an id no production code has to learn about.

No QID pattern, and no code in `port`, `tinker` or `jena`, has to learn a second identity shape.

`domain` does change, but only to gain the claim types themselves: `LoggedAssertion` is a sealed
interface permitting `NodeAssertion`, `AssertionRecord` and `Retraction`, and owner claims join that
list. `Retraction` is the precedent — a first-person claim, in `domain`, with its own validation and
no `Provenance`. ADR 18's purity rule is about third-party dependencies, which this does not touch.

**ADR 58 claimed the leading-zero space for test fixtures.** Local entities need a documented band
of their own inside it, or a reader cannot tell a stand-in from one of the owner's books. That is a
convention decision for the implementation, not a mechanism decision.

## The merge, when Wikidata catches up

**A merge is an asserted equivalence, never an edit.** ADR 19 makes the log append-only and ADR 44
makes retraction a new claim, so "replaced when Wikidata catches up" cannot rewrite history. It
appends a claim that a local id and a QID are the same thing; the projection collapses them.

The log keeps what was actually claimed at the time, which is the honest record: the owner did not
know the QID, and later did.

Three properties follow:

- **Edges survive.** Asserted against the local id, carried to the QID at projection time. Nothing
  is re-asserted and nothing is lost.
- **Ratings follow.** Affinity is keyed by qid, so the projection must resolve affinity through the
  equivalence. A merge that orphans a rating loses the one thing in this system that cannot be
  regenerated (ADR 39, ADR 46: no history table, no un-rate).
- **A wrong merge is correctable** by asserting its retraction, like anything else.

**Matches are declared manually.** Automatic name-matching is how you merge the wrong artist. A
wrong merge is correctable but not free, so the default is the owner saying which QID it is. If
evidence later argues for more, that is its own decision.

**A merged local id stays resolvable** — old log entries must keep meaning what they meant — but
stops being offered anywhere, the way ADR 50's suppressed candidate stays reachable through
`--revise` and is never dealt.

## Routing, recommendation and expansion

**Routing: no exemption is needed, and the first draft of this spec was wrong to claim one.**
`PathRanking.restsOnAModelGuess` asks `EdgeRecord.isUncorroboratedHypothesis()`, which is true only
when *every* source `isHypothesis()` — and that is `sourceId.startsWith("llm:")`. An owner edge
carries a non-`llm:` provenance, so it is already not a hypothesis and paths through it are already
not demoted. **`PathRanking` and `EdgeRecord.isUncorroboratedHypothesis` are unchanged.**

**Corroboration does need one change, in the other direction.** `EdgeRecord.corroboration()` counts
distinct `sourceId`s. An edge asserted by both Wikidata and the owner would therefore count **2**,
letting the owner manufacture agreement with himself — the same hazard ADR 55 identified when it
declined `subgroup`, where either coding would manufacture corroboration with one Wikidata coding
while withholding it from the other.

So owner claims must be **excluded from the corroboration count**: they route, and they do not
vouch. That is the precise sense in which first-person claims sit outside the ladder rather than low
on it. ADR 23 quarantines model guesses because a model can be confidently wrong about the world;
the owner cannot be wrong about their own shelf, and also cannot be a second witness to it.

**Recommendation: no exemption at all.** An owner edge counts toward degree like any other. A local
entity with two edges sits below `MIN_CANDIDATE_DEGREE` and is not a candidate; connect it further
and it becomes one. ADR 57 measured 5,874 entities already in that position.

Routing and candidacy separate cleanly: **the owner can always route through what they asserted, and
gets recommendations from it only once it is connected enough to earn them** — the same two-jobs
distinction ADR 45's issue-#117/#118 amendment drew for the floor, applied to a new population.

**Expansion must refuse out loud.** No source knows a local entity. ADR 56 has just established that
an empty `ExpandResult` already means both "found nothing" and "source unavailable"; making it mean
a third thing rebuilds the defect ADR 56 fixed. `expand_entity` must say *this entity has no source
to expand from*, distinctly.

**A route crossing an owner edge says so.** `Provenance` carries `sourceId` and `PathResult.render()`
already prints citations, so an explanation can read "you told me this" rather than attributing it
to Wikidata. The project's premise is that "you like this because" is checkable; an owner claim is
checkable against the owner, but only if the route names whose claim it is.

## Tool surface

**Dev-side, not MCP.** ADR 26 held `assert_edge` back *"until corroboration is visibly working"*,
and ADR 56 has made corroboration real. But the reason for holding it back now cuts the other way:
on the MCP surface a *model* could call it, and owner claims are exempt from the corroboration
ladder — so an MCP `assert_edge` would let a model launder model-generated structure into the one
tier that skips quarantine, which is precisely what ADR 23 exists to prevent.

Dev-side keeps it the owner's, in the shape of `rate`, `recommend` and `listRatings`.

This makes it the **seventh dev tool**, so it lands in `ArchitectureTest`'s `DEV_TOOL_PACKAGES` and
the sibling fences, and is the first real exercise of issue #165.

Three operations, one package: **mint** a local entity (name and kind), **assert** an edge between
two ids, **merge** a local id into a QID.

## What must be tested, and how it fails

Each of these wants a positive control — the violation planted, the failure observed, then reverted.
A guard never seen to fail has never been tested (#93, and #139 was a second instance).

- **An owner edge routes and is labelled as the owner's.** Mutate the exemption away: the path drops.
  Mutate the provenance: the citation lies.
- **An owner edge counts toward degree**, so a local entity crosses the floor when connected.
- **A local entity is ratable.** #5's rule already permits it once the entity is in the graph, so
  this test asserts that the rule needs no exception.
- **A merge carries edges and ratings.** The rating half has irreplaceable data behind it: merge,
  assert the rating resolves through the equivalence, then mutate the resolution away and watch it
  orphan.
- **A wrong merge is retractable**, and the retraction is itself a claim.
- **`expand_entity` refuses distinctly** rather than returning the empty result that already means
  two other things.
- **ArchUnit fences the new package**, with the control #158 established.

## ADRs owed

- A new ADR recording this decision and the alternatives below.
- **ADR 22** — a dated amendment: clause 1 now admits a second identity kind, and what that costs.
- **ADR 33** — a dated amendment. Its **"Two layers, two stores"** (line 30) becomes three, and its
  **"`note_affinity` is the only tool that writes affinity, and it never writes to the graph.
  `IngestService` never sees a rating"** (lines 37-38) still holds for affinity but no longer
  describes the whole first-person surface. The amendment must say what the third layer may touch.
- **`CLAUDE.md:183`** — *"Affinity is not an assertion, and the two layers never meet below
  `SegueService`"* becomes false with three layers. It is not an ADR and needs no amendment, only a
  correction.
- **ADR 26** — a dated amendment: the stated condition for `assert_edge` is met, and the tool arrives
  dev-side rather than on the MCP surface, for a reason ADR 26 did not anticipate.

Note issue #170: the ADR index is append-at-tail and has silently lost entries. Verify the sequence
after editing.

## Alternatives, and why each lost

- **Keep QID-required (#92's option 1).** Honest and cheapest, and it was the right answer until the
  second source was tried. It is now a decision to exclude a domain #78 committed to holding, on the
  strength of an obstacle three measurements say no source removes.
- **A second source fills the gap (#92's option 3).** Tried. ADR 54 shipped MusicBrainz; Open Library
  and OSM were assessed and are blocked by clause 1 and by identity respectively. The long tail did
  not shrink.
- **Owner as a source adapter.** `owner:` alongside `wikidata` and `musicbrainz`; everything
  downstream works unchanged and the SPI was built for it. Rejected because it makes the owner a data
  source among data sources — the thing ADR 23 prevents for models and would now permit for the
  owner — and because an adapter that cannot `expand()` has no honest answer for `supports(kind)`.
- **Owner claims as decaying seeds.** Assert an edge; it routes provisionally and stops routing if no
  source corroborates it within some horizon. Keeps one corroboration model, and punishes exactly the
  case #92 exists for: the indie author no source will ever know.
- **A visibly different id prefix** (`L42`, `local:42`). Honest at a glance, but `Qid.check` and every
  `Q\d+` pattern would have to widen, touching `domain`, which ADR 18 keeps pure.
- **Automatic match declaration.** Convenient, and how you merge the wrong artist. Deferred, not
  rejected: it can be argued for later on evidence.

## What this does not settle

- **The band within the unallocatable space** that separates local entities from ADR 58's fixtures.
- **Whether a local entity may ever be expanded** by a future source that learns it — the merge path
  covers the case where Wikidata catches up, but not a source that knows it under its own id.
- **Whether `assert_edge` should ever reach the MCP surface** if corroboration matures further.
  ADR 26's condition is met; this design declines the surface for a different reason, which a later
  decision may revisit.
