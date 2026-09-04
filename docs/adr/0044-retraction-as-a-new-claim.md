---
status: Accepted
date: "2026-08-27"
topic: retraction-as-a-new-claim
tags: [project, provenance, data, tooling]
supersedes: []
related: [assertion-log-source-of-truth, bitemporal-time-model, sqlite-assertion-log, mcp-tool-surface, store-p31-and-rederive-kind-at-projection, taste-layer-separation, bulk-seeding-as-a-dev-tool, graph-exporter-views-and-formats, listing-your-own-ratings, layering-and-archunit]
---
# 44. Retract by appending a claim, and honour it in both projections

## Context

Add the wrong QID, expand it, and its edges are permanent. That is not a missing button; it is
what ADR 19 buys. The log is the source of truth, the graph is a projection, and nothing in the
system can take a claim back.

It came within one command of mattering. A seeding mapping had `The Highwaymen` as `Q7739667`,
which is a group of Florida landscape painters rather than the country supergroup; it was caught
only because the QIDs were checked against Wikidata before the expansion ran. Reproduced against a
copy of the real database while writing this: adding and expanding that QID puts a `GROUP` node
called "The Highwaymen" in the graph with four painters as `MEMBER_OF`, and every route through it
is quietly false.

The obvious fix — delete the rows — is the wrong one, and stating why is the whole point of this
decision. Deleting would make the log a mutable store that merely happens to be append-shaped, and
every guarantee resting on ADR 19 would become conditional on nobody having deleted anything:
replay reproducing the graph, the audit trail, ADR 42's offline re-derivation. A wrong claim is
data about what a source said, not corruption to be scrubbed. That we later concluded it was wrong
is itself a fact worth keeping.

## Decision

**A retraction is a claim, appended like any other. The log is never edited or rewritten.**

`Retraction` joins `NodeAssertion` and `AssertionRecord` in the sealed `LoggedAssertion`, and the
projections honour it by omitting what it retracts. This is exactly the shape ADR 42 established
for node kinds: a later row changes what the projection *says* without rewriting what was
*recorded*. `GraphProjectorTest.theLogStillHoldsEveryOriginalClaim` is that sentence as a test.

### The four questions the decision left open

**1. Granularity: the entity.** A retraction of `Q7739667` reaches that entity's node claims and
every edge claim with it at either end.

Not one edge: the case this exists for is a wrongly-*resolved* entity, where the whole expansion is
wrong and picking the edges off one at a time is a hundred commands and a chance to miss one.

Not one expansion, and that was the tempting answer — the issue's own framing is "everything this
expansion asserted". Two things ruled it out. First, **it would leave the wrong entity in the
graph**: the node claim came from `add_entity`, not from the expansion, so retracting the expansion
strips the edges and leaves a `GROUP` called "The Highwaymen" still in `search_entities`, still
rateable, still an argument to `expand_entity`. The identity is the thing that was wrong. Second,
**an expansion is not identifiable from what is recorded**, and making it so would be a schema
change of its own. Checked against the real log rather than assumed: `source_ref` is per-claim (a
Wikidata statement GUID forwards, `wdqs:<subject>:<property>:<object>` in reverse) and `asserted_at`
is shared by every claim of one expansion — one instant for both passes, deliberately, per ADR 20 —
but nothing distinguishes two expansions that happen to land in the same microsecond, and nothing
at all identifies an expansion in the 131,000 rows already written before this ADR existed. The
entity is the unit the operator actually knows, and it is expressible against the log exactly as it
already stands.

**It does not cascade, and that is deliberate.** Retracting `Q7739667` leaves the four painters in
the graph as nodes with no edges. Their node claims are not wrong — Wikidata really does say
`Q61380275` is a human called Alfred Hair — and cascading would delete neighbours that other,
correct expansions may also have reached. An orphan node is invisible to `find_paths`, harmless to
`get_entity`, and appears in a `full` or `subgraph` export. The alternative trades a visible,
harmless residue for an invisible, unbounded blast radius.

**2. Provenance: none.** `Retraction` carries `qid`, `reason` and `retractedAt`, and no
`Provenance`.

`Provenance` answers "which source told us this, and how much do we believe them". A retraction has
no source and is not a matter of belief — it is the owner's own act, the same first-person shape
ADR 33 gives affinity, and ADR 33's argument applies unchanged. Filling the record in would have
meant three fields that are not true: `sourceId` of `"operator"` carries no information in a
single-writer system (ADR 24), `sourceRef` is documented as "the citation you can click" and a
reason is not one, and `confidence` of 1.00 means "a Wikidata statement with a reference".

What a first-person act *can* honestly carry is what the record holds. **`reason` is required** and
validated non-blank: the value of keeping a retraction in an append-only log is that it records
both that we concluded something was wrong and what the conclusion was, and there is no editing one
afterwards to add the second half. **`retractedAt` is the one dimension of ADR 20 a retraction
has** — it is not a claim about the world, so it has no validity interval and there is nothing for
`validFrom`/`validTo` to mean.

Consequence, taken deliberately: `LoggedAssertion.provenance()` moves off the sealed interface onto
the two claim types. Nothing called it polymorphically. Anything that wants a row's provenance now
has to say which kind of row it is holding, which is the correct obligation.

**3. Where it is invoked: a fourth dev-side tool, `./gradlew retractEntity`.** Still six MCP tools.

ADR 26 pins the surface at six; ADR 40, ADR 41 and ADR 43 each declined a seventh. This is the
heaviest of those refusals, and the reason is not tool-count arithmetic. **The caller of an MCP tool
is a language model, and a model that proposes retractions of its own is a different and much larger
question.** ADR 26 already holds back `assert_edge` because a model cannot distinguish a plausible
relationship from one it knows; a tool that *removes* claims hands the same faculty a stronger
verb. That question is deliberately left closed here — this ADR does not decide it either way, and
`ToolSurfaceTest.retractIsNotATool` fails the build if somebody reopens it in passing.

ADR 26 needs **no amendment**. Its consequences already read "nothing in the surface can currently
retract a claim; retraction is expressible against the log but deliberately unexposed", which is
what this ADR makes literally true rather than aspirational.

The tool is the first of the four that **writes**, so its fence is shaped differently and says so:
it may append a retraction through `IngestService`, and it may not hold a `GraphStore` (named as a
type, not just as two forbidden calls), an `AffinityStore`, an engine, a sibling tool or a network.
`IngestService.retract` is static, taking the log, precisely so that satisfying a constructor could
never become the reason this tool held a graph.

**It reports before it appends**: the entity's **label** and how many node and edge claims will stop
projecting, while the log is still untouched. Borrowed from `ExportRun`, for a stronger reason — an
export leaves a file to inspect and this leaves a permanent row. Naming the label is the safety
feature that matters, because a retraction of the wrong QID is the very mistake this issue is about,
one level up. `--dry-run` stops after the report. **Nothing to retract is refused, not recorded**: a
mistyped QID would otherwise append a retraction that does nothing, forever, reading like a decision
somebody made.

**4. A retraction is not retracted. Append a fresh claim instead.**

A retraction reaches **backwards only** — it retracts the claims that precede it in the log, and
claims appended after it stand. So an entity comes back by being added and expanded again, and
nothing special happens on the way: the new claims are simply newer than the retraction. There is no
un-retract verb, no tombstone to clear, and no third state. An entity that came back can be retracted
again by the ordinary path, which `RetractRunTest.canRetractSomethingThatCameBack` pins.

**Backwards by position in the log, not by `assertedAt`.** Sequence order is a guarantee the port
already makes and it is total, where assertion time can tie — a whole Wikidata expansion shares one
instant by construction — and can legitimately run behind the append carrying it. Position asks the
question the decision actually means: what had we already been told when this was decided?

### The rule lives in one place and both projections call it

`Retractions` is in `domain`, beside `Retraction`, and `GraphProjector` (boot replay, ADR 24) and
`LogProjection` (the exporter's fold, ADR 41) both ask it the same question about the same list.
This is ADR 42's argument repeated, and the failure it prevents is worse here than for node kinds:
a picture that still shows edges the graph has dropped is not a stale detail, it is a false record
of what is in the graph, and an export is the artefact somebody keeps and opens in Gephi weeks later.
`BothFoldsAgreeTest` runs one deliberately awkward log — retracted entity with edges in and out, an
untouched entity, a re-add, a second entity retracted after a re-add — through both and compares the
node and edge sets. It was confirmed to fail when either fold is changed to ignore the rule.

The rule sits in `domain` rather than beside either caller because, unlike `KindMapper`, a
retraction is nobody's vocabulary. It is the log's own.

**`IngestService.apply` throws if it is ever handed a retraction.** Unreachable through either
projection, and a guard rather than a path: reaching it means a caller replayed the log without
applying the rule, which would produce a graph still holding edges somebody took back out. Silently
ignoring it is the one response that would hide exactly that.

### A migration, and this time a real one

**Yes, this needed one, and it is `ALTER TABLE assertion ADD COLUMN reason TEXT`**, guarded by
reading `PRAGMA table_info` rather than by a version table — "does this column exist" has an exact
answer here, where a version number is a second source of truth a hand-edited file can contradict.
`CREATE TABLE IF NOT EXISTS` is a no-op against an existing table, so without this a database
written before today would silently keep a schema with no `reason` column and fail on the first
retraction.

ADR 42 shipped a schema change with no migration and said in as many words that the next one gets a
real path, because the world facts are regenerable and a rating is not. This is that next one, and
it was **tested against a copy of the live database** — 131,672 assertions, no `reason` column — as
well as from a fixture that writes the old schema by hand. SQLite performs `ADD COLUMN` by rewriting
the schema rather than the rows, so it costs the same on an empty file and on a hundred thousand of
them.

Two `NOT NULL` columns predate this row type and mean nothing for a retraction: `source_id` and
`confidence`. They are filled with fixed values (`(retraction)` and `1.0`) and never read back for a
`RETRACT` row. Removing a `NOT NULL` in SQLite means rebuilding the table — a real rewrite of the
whole log, to relax a constraint on rows that simply have nothing to put there. The literal is named
rather than `operator` or blank so that anyone reading the table in a SQL client sees a
discriminator and not a source they might go looking for.

## Alternatives considered

- **Delete the rows** — the smallest change, no new row type, no fold rule, and nothing to explain
  to a future reader of the schema. Rejected in the issue's own decision comment and restated here
  because it is the alternative everybody reaches for first: it makes ADR 19 conditional on nobody
  having deleted anything, and the guarantees it silently weakens (replay, audit, re-derivation) are
  the ones nobody would notice losing until they needed them.

- **A `retracted` flag on the existing rows** — one boolean, no new type, and the fold is a `WHERE`
  clause. It is deletion wearing a hat: the log's rows become mutable, "what we were told" and "what
  we currently believe" collapse into one record, and there is nowhere to put the reason or the
  moment. Everything this ADR keeps is in the *separateness* of the retraction row.

- **Retract an expansion rather than an entity** — closest to how the mistake is actually made
  ("that expansion was wrong"), and rejected above on two grounds: it leaves the wrong identity
  behind, and an expansion is not identifiable in the log without a schema change that would help no
  row already written.

- **Retract one edge** — the finest grain, and useful for a *different* problem: a single edge from a
  correct entity that is wrong. Nothing here forbids adding it later, and it is deliberately not
  built now, because the case in hand needs a hundred of them and one missed edge is a false route
  that looks sourced.

- **Cascade to the neighbours the expansion discovered** — the tidiest-looking graph afterwards, and
  it deletes nodes that other correct expansions also reached, with a blast radius nobody can see at
  the moment they type the command. Orphans are visible and harmless; this is neither.

- **Give the retraction a `Provenance`** — uniform with every other row, and the storage would need
  no new column at all. It buys that uniformity by writing down three things that are not true, in
  the one record whose entire purpose is honesty about a past mistake.

- **A seventh MCP tool, `retract_entity`** — the model could fix its own error in the conversation
  where it noticed it, which is genuinely where the mistake surfaces. It is also the first tool that
  would let a model *remove* what a source said, on a surface that deliberately withholds
  `assert_edge`; that trade deserves its own ADR and its own evidence, not a row added while
  building the mechanism.

- **Honour retractions in only one projection and rebuild the other from it** — one fold instead of
  two, and it is what `LogProjection` exists to avoid: bounded views traverse a real projection,
  `full` and `subgraph` do not, and the port has no enumerate-all method (ADR 41). One shared rule
  called twice is the shape that already works here.

- **Compare `assertedAt` instead of log position** — reads better in an ADR about a bitemporal
  model, and it ties the decision to a clock that is allowed to tie, is reset wholesale by a re-seed
  (ADR 42's own note), and can legitimately precede the append that carries it.

## Consequences

- **The wrongly-expanded entity has a way out, and it costs one command.** Measured on a copy of the
  real database: retracting `Q7739667` removed one node claim and four edge claims from the
  projection, the log grew by exactly one row, all five original claims were still in it, the boot
  replay applied 131,667 of 131,673 rows, `get_entity` reported the entity unknown, and a
  neighbourhood export of one of the painters showed him still present with no edges.

- **A running server is stale until it restarts.** A retraction changes the projection, and
  `GraphStore` cannot remove anything — widening the port that exists to keep the engine choice
  reversible (ADR 18), for a dev-side tool, is what ADR 41 already refused. The tool says so in its
  last line. This is the same contract ADR 24 gives replay, reached from the other side.

- **Replay does slightly less work than the log has rows**, and the numbers now differ by design.
  "Replayed N assertions" is no longer the row count, which is exactly the visible signal that a
  retraction took.

- **The ratings tool is deliberately not part of this.** `Labels.forQids` reads node claims straight
  out of the log without applying the rule, so a rating whose entity has been retracted still lists
  under the label the log last gave it. That is a decision, not an oversight: a retraction is a
  statement about the world-fact layer and a rating is not (ADR 33), affinity is the one thing here
  that cannot be regenerated (ADR 43), and turning a row into `(not in the graph)` with no
  explanation would be worse than showing the name of a thing you rated and later took back out.

- **Retraction is a fourth relationship with the data, and the fences now say which is which.**
  `seed` may not open a store; `export` may read one and build a projection; `ratings` may read two
  and project nothing; `retract` may append exactly one kind of row and may not hold a graph. Four
  tools, four rules, which is the pattern ADR 41 and ADR 43 established.

- **`Retraction` is deliberately outside the fold's data model.** It is not projected, so nothing
  downstream — `EdgeRecord`, `NodeRecord`, a view, an export — grows a field for it. Anything that
  wants to know what was retracted reads the log, which is what ADR 19 says anyway.

- **A retraction cannot be undone by a smaller act than re-adding.** Adding and expanding an entity
  again costs a Wikidata round trip and re-stamps `assertedAt`, which ADR 20 treats as real. On a
  wrongly-resolved entity that is the correct cost — there was nothing worth keeping — and it is a
  genuine cost, recorded here rather than glossed.

**Amendment (2026-09-03, issue #224): question 1's granularity has one more clause. A retraction of
a local id the owner had merged also reaches the edges that name the stand-in that merge created —
and nothing else.**

Nothing above is withdrawn and no sentence above is edited. The entity remains the unit, and so
does *"it does not cascade"*: what this adds is not a cascade to a neighbour but the same
entity's own node under the other name the owner himself gave it.

**What was there, measured on `0783492`** — the commit that landed
[ADR 59](0059-owner-claims-as-a-third-layer.md)'s 2026-09-03 amendment, so the surviving-edge
widening was already in place and does not reach this case. An invented log (ADR 40,
[ADR 51](0051-what-an-adr-may-quote.md): no known list behind it) holding `node(WREN)`, one minted
entity, a merge of it onto a canonical id no source has claimed, an owner edge naming that canonical
id directly, and a retraction of the local id. `Equivalences.standIns` named nothing — the local no
longer survives, so `localsOfMerges` filtered the merge out before `stands` was asked anything —
`LogProjection` reported `danglingEdges() == 1` and carried on, and `GraphProjector` threw `replay
failed at sequence 4`, `assertion references unknown entity … - upsert the node first` — sequence 4
being the owner edge, the fourth of that log's five rows. The export
looked correct and the application refused to start at the next restart, on rows
[ADR 19](0019-assertion-log-source-of-truth.md) forbids deleting. Every row in that log is one the
supported flow produces: `OwnRun` offers a merge's canonical id as a claimable endpoint the moment
its stand-in exists, and `retractEntity` is the tool this ADR builds.

**Ruling.** A canonical id is a *retracted stand-in* when a merge named it, a retraction of that
merge's **local** side dropped the merge, and nothing else in the projection holds a node for the id
— no surviving node claim, and no surviving merge whose stand-in it still is. An edge claim naming
one at either end does not reach the projection. The rule is `Equivalences.retractedStandIns`, in
`domain`, computed once and carried by the `Equivalences` both folds build through
`Equivalences.folding`; `Equivalences.foldEndpoints` — which both folds already call for every edge,
and which already yields nothing for an edge a merge would collapse onto itself — yields nothing for
this one too. Neither fold's loop changed. Those classes are the authority for the mechanics; this
amendment mirrors no table of theirs.

**This rule does not follow question 4's reach, and that is deliberate rather than a loose end left
for the reader to reconcile.** Question 4 above says the reach is backwards and by position in the
log — it retracts what precedes it, and a claim appended afterwards stands. That
reach does not carry over to withdrawal. `Equivalences.retractedStandIns` computes the emptied ids
over the whole log, and `Equivalences.foldEndpoints` takes no index, so an edge naming an emptied
canonical id is withdrawn whether it was claimed before the retraction or after. This was tried the
other way first, and measured wrong: a position-aware version of the rule, filtered to retractions
that reach the row being folded, left `[node(WREN), minted(LAPSE), merged(LAPSE→FORFEIT),
retract(LAPSE), owned(WREN→FORFEIT)]` — the edge claimed last — with `danglingEdges() == 1` and
`GraphProjector` throwing `replay failed … assertion references unknown entity … - upsert the node
first`, the exact break this amendment exists to close, re-created by which row happens to come
after which rather than fixed. The two reaches answer different questions. A retraction's own
backwards-only reach answers *"which claims did the owner take back"*, and position is the right
unit for that — a claim made after the retraction was not one of the ones taken back. Withdrawal
answers *"does the endpoint this edge names exist in the folded graph"*, and a node either exists in
a projection or it does not: there is no index at which an emptied canonical id has a node again, so
there is no index at which an edge naming it could be applied. `RetractedStandInTakesItsEdgesTest`
pins both folds on that log in both directions.

**The two exclusions are the ruling, not caveats on it.** Without them, retracting one thing the
owner minted would strip the edges off a real Wikidata entity's whole expansion — which contradicts
this ADR's own reach and the developer guide's promise that *"what a source claimed about the
canonical id is untouched"*. Both were measured green before the change and are pinned by
`RetractedStandInTakesItsEdgesTest` and `EquivalencesTest`.

**Only the local side counts.** A merge dropped because its *canonical* side was retracted leaves
nothing to repair: that id is retracted outright and `Retractions.survives` has already dropped
every edge naming it. Telling the two apart is why `Retractions.reaches` is public.

**Rejected, with the reason each lost.**

- **Let the stand-in survive the retraction while a surviving edge names it**, as ADR 59's
  2026-09-03 amendment does for a *corrected* merge. The symmetry is the first thing to reach for.
  **Lost on what the node would be made of.** There, the local node still stands and the stand-in
  copies a claim that is still true; here the local claim is retracted, so building the node means
  reading a retracted `LocalEntity` for its kind and its label and putting the owner's withdrawn
  working title on a live node in an export somebody keeps — a node assembled entirely out of
  retracted rows. That is this ADR inverted: the projection would go on saying the thing the
  retraction exists to stop it saying. A label-less or annotated stand-in is the *"name the orphan
  in the export"* alternative ADR 59 already rejected.
- **Have `GraphProjector` tolerate the unknown endpoint as `LogProjection` does**, so the two folds
  agree on a dangling edge. Rejected once already in ADR 59's 2026-09-03 amendment, and the question
  here was whether anything is different. One thing is: there the missing endpoint was a defect with
  a fix, and here the absence is correct — the owner really did retract the entity. It does not save
  the option. Tolerance buys this one case by removing the loud failure from **every** case: a
  corrupt log, a future fold's bug, a source adapter emitting an edge before its node all stop
  failing at boot and start being counted in a field whose javadoc says it should always be zero.
  `LogProjection.danglingEdges` exists to report that failure, not to produce it. Dropping the edge
  for a stated reason keeps the boot loud for everything else.
- **Refuse the retraction at the tool** when the local id has been merged and a surviving edge names
  its canonical id. **Lost twice.** The fold must cope with the row regardless — the log is
  append-only and a refusal cannot reach a row already written — so it would be a guard in front of
  a fold that still could not replay; and it takes away the owner's only way back out of a wrong
  mint, which ADR 59's amendment already declined to do from the other side.
- **Re-point the edge onto the local id.** Rejected for ADR 59's reason, unchanged: segue does not
  rewrite a claim on the owner's behalf. He named the canonical id.

**Consequence for `retractEntity`.** The report before the append names each id the retraction
empties and counts the edge claims that stop projecting with it — by distinct edge, not by claim
row: two sources corroborating one relationship name one edge, and the count that matters is the
one that agrees with `LogProjection.withdrawnEdges` and with a `full` export of the same log, both
of which group corroborating claims the same way before counting. A row count was tried during
review and rejected for exactly that disagreement — it read a different size than the export the
moment two sources backed one withdrawn edge — and issue #227's census is written to read this
number rather than re-derive it, so the three had to already agree. `Effect`'s two counts keep their
meaning — claims naming the qid being retracted — because they are what decides whether *"nothing to
retract"* is refused. Silence was half of what the tolerate-the-dangle option was rejected for, and
it is not allowed to arrive at the tool instead.

**A consequence above is stale, and it is recorded rather than repaired.** *"The ratings tool is
deliberately not part of this. `Labels.forQids` reads node claims straight out of the log without
applying the rule"* has been false since issue #92: that method asks `Retractions.survives` before a
claim can name or rename anything, and cites this ADR as the precedent for doing so. Nothing in this
issue depends on which of the two is right, and an ADR is not edited to match what the code became.
Whether the ratings listing *should* honour retractions is a decision nobody has argued in writing.

**Two more residuals, recorded rather than repaired.**

- **A withdrawn edge still counts as a reference.** `Equivalences.referencedEndpoints` is built from
  the *surviving* edges rather than the *folded* ones, so an edge that names an emptied canonical id
  still keeps a **different** canonical id alive through the surviving-edge widening
  [ADR 59](0059-owner-claims-as-a-third-layer.md)'s 2026-09-03 amendment added: a superseded
  canonical whose stand-in was justified only by that edge keeps its stand-in after the edge is
  gone. The result is a node with no edges in a `full` export. Replay is unaffected — a node
  nothing names is exactly what boots — and this is the safe direction of the two, since the
  opposite would take a node away from an edge that might still name it. Measured during the final
  review of issue #224, on a log holding both shapes at once.
- **Two legal logs still stop the boot replay, and neither is a regression.** A local id retracted
  and then merged again onto a *different* canonical id leaves that second canonical with neither a
  node nor a withdrawal, and an edge naming a local id that itself folds onto an emptied canonical
  escapes the check in `Equivalences.namesARetractedStandIn`, which reads the claim's raw endpoints
  before the fold resolves them. Both were measured throwing on `0783492` as well, so this
  amendment neither introduced nor repaired them, and `OwnRun.declareMerge` refuses the merge that
  would produce either — it reads only what the projection has minted and still survives. They are
  filed as **issue #228** with both logs, rather than widened into this ruling on the way past.
