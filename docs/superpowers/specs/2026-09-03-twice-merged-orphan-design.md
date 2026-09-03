# A local id merged twice leaves an orphan stand-in under the first canonical

Issue #221. Written 2026-09-03, against `main` at `2e01341`.

## The defect, measured

`Equivalences.standIns` names a stand-in for **every** surviving merge that has a local side, while
`Equivalences.canonicalByLocal` is **last-wins**. So a local id merged onto A and then onto B gets a
node under both, and the edges — folded through `foldEndpoints`, which reads `canonicalByLocal` —
land on B alone. A is left holding a node with the merged entity's label and no edges.

Measured on `2e01341` with a scratch probe in `export`, on an invented log (`node(WREN)`,
`minted(Q006, "the corrected pressing")`, `owned(Q006 → WREN)`, `merged(Q006 → Q10000900109)`,
`merged(Q006 → Q10000900107)`), driving both folds and the DOT writer. Probe reverted; the tree is
clean.

| reading | on `2e01341` |
|---|---|
| `LogProjection.of(log).nodes()` | `[Q10000900109, Q10000900107, Q0900101, Q006]` |
| `…nodes().get(Q10000900109)` | `NodeRecord[qid=Q10000900109, kind=WORK, label=the corrected pressing, instanceOf=[]]` |
| `LogProjection…edges()` | `[Q10000900107 INFLUENCED_BY Q0900101]` |
| replayed `graph.node(Q10000900109)` | `Optional[NodeRecord[…label=the corrected pressing…]]` |
| replayed `graph.edges(Q10000900109)` | `[]` |
| `IdentityMerge.follow` calls during replay | `[Q006 -> Q10000900109, Q006 -> Q10000900107]` |

The `full` DOT export draws **three** nodes carrying one label:

```
  "Q10000900109" [label="the corrected pressing", shape=note, …];
  "Q10000900107" [label="the corrected pressing", shape=note, …];
  "Q0900101"     [label="Wren Alderman", shape=ellipse, …];
  "Q006"         [label="the corrected pressing", shape=note, …];
  "Q10000900107" -> "Q0900101" [label="INFLUENCED_BY", …];
```

Two of those three the owner claimed: the local id, which ADR 59 keeps resolvable and #178's ruling
3 draws as an orphan, and the canonical id he corrected to. The third — `Q10000900109` — is a claim
nobody made about a real Wikidata item, carrying the owner's working title for something that item
is not.

### Three things the issue does not say, and they change the shape of the fix

**1. `putIfAbsent` is not the cause.** The issue attributes the orphan to `standIns` being
`putIfAbsent` keyed by canonical id. It is not: `putIfAbsent` decides *which of two locals* names a
stand-in when both merge onto **one** canonical id (ADR 59's "the first merge onto a canonical id
names it", pinned by `EquivalencesTest.shouldLetTheFirstMergeOntoACanonicalIdNameTheStandIn`). The
orphan here comes from the loop **emitting one entry per surviving merge** while `canonicalByLocal`
keeps only the last. The two canonical ids are different, so `putIfAbsent` never fires. That
distinction is load-bearing: the fix must drop the superseded merge and leave the first-wins rule
exactly where it is.

**2. The stand-in has a fourth home that also builds the node at replay, and it is not `standIns`.**
`GraphProjector` seeds `Equivalences.standIns` before the loop *and* calls `IngestService.apply` for
every row, whose `SameAs` arm calls `IngestService.standIn` — which creates the canonical node from
the running graph. Measured: with `standIns` fixed and `IngestService` untouched, the exporter drops
the orphan and **the boot replay keeps it**, and the whole suite still passes. That is the two folds
disagreeing, which is the family of defect (#176, #177, #178) this issue belongs to, and
`BothFoldsAgreeTest` cannot see it because its fixture holds no twice-merged local id. **A fix to
`standIns` alone is not a fix.**

Widening that fixture does not close the blind spot either, and this was measured rather than
assumed: with the twice-merged local id added and **no** fix applied, `BothFoldsAgreeTest` is still
green, because both folds build the orphan and comparing them says nothing. What the widened fixture
buys is the *half*-fix — land only `standIns` and it reds naming the id. Both failures matter, and
only the second is a comparison's to catch.

**3. The rating is carried onto the superseded canonical too, on every boot.** `follow` is called
per `SameAs` row, so `IdentityMerge.carryingRatings` writes the owner's rating onto the wrong
canonical id as well as the right one — measured above. By ADR 48 a high rating counts as something
the owner has, so a wrong merge he corrected goes on telling `recommend` he owns the item he
corrected it away from. The issue does not mention this; it is the same defect's taste half and it
is closed here.

## The decision

**Option 1: the orphan goes.** A merge the owner has since corrected names no stand-in, carries no
rating, and puts nothing in either fold. The rule is stated once, in `domain`, as a predicate every
home of the stand-in rule asks:

> `Equivalences.stands(SameAs)` — whether these equivalences still point at this merge's canonical
> id. Equivalences that have never heard of the local id do not contradict the merge, so they answer
> **true**.

Five edits, one predicate:

> **Superseded — see the second amendment at the end of this file (2026-09-03).** The table below is
> the design, not what landed: `stands` gained a sibling predicate and a new record component, and
> `IngestService.apply` took two guards rather than one. Read the amendment for the blast radius.

| where | change |
|---|---|
| `Equivalences.stands` | new; `canonicalByLocal.get(local)` is `null` or equals the merge's canonical |
| `Equivalences.standIns` | skip a merge that does not stand |
| `IngestService.apply`, `SameAs` arm | return early where the merge does not stand — the stand-in **and** the carry |
| `OwnRun.labelsInTheProjection` | a superseded merge lends no label, so the tool stops offering that id |
| `Labels.forQids` | the same, so `listRatings` stops naming a node that is not there |

The `null → true` clause is what keeps the live path unchanged. `IngestService.record` applies a
claim with `Equivalences.NONE` because it sees one claim and not a log; a `SameAs` arriving there
must go on getting its canonical node, or `record` would leave the running graph with an endpoint it
has never heard of. A caller holding the log gets the last-wins answer; a caller holding no log gets
the merge it was handed. `MergeCarriesEverythingTest`'s live-path assertions are what hold that.

**Measured, on `2e01341` with all five edits applied:** `./gradlew test` — `BUILD SUCCESSFUL`, no
existing test fails. Export nodes `[Q10000900107, Q0900101, Q006]`; replayed
`node(Q10000900109)` = `Optional.empty`; `follow` calls `[Q006 -> Q10000900107]`. Reverted.

### Why the two label copies are not optional extras

`OwnRun` is the sharper of the two. It offers an endpoint for a new owner edge from
`labelsInTheProjection`, whose merge branch is the stand-in rule's third home. Fix the folds and
leave it, and the tool offers a canonical id **no fold now gives a node** — the owner claims an edge
against it, and at the next boot `TinkerGraphStore.record` refuses an endpoint it has never seen and
`GraphProjector` throws `replay failed at sequence N`, on a row ADR 19 forbids deleting. So this is
not tidiness; it is the difference between a correction and a log that will not boot. It lands
**before** the fold change for that reason.

`Labels.forQids` is the fourth home and states its own invariant in its javadoc: a canonical row
must not be listed as `(not in the graph)` while the node is in the graph. After the fold change the
node is *not* in the graph, so leaving `Labels` alone would invert that invariant rather than
preserve it. Changing it also does something useful: the rating already carried onto the superseded
canonical by an earlier build stops being disguised as a graph entity and shows as
`(not in the graph)` — which is exactly what that string is for, a rating that outlived its node.

### What a reader of the export should see

The local id, drawn as the orphan #178 made it. The canonical id the owner corrected to, carrying
every edge once. Nothing else. The mistaken canonical id is not in the world graph because nothing
in the world is that id: no source claimed it, and the only claim that ever reached it has been
corrected. The correction itself is not lost — both `SameAs` rows are in the append-only log, which
is where an append-only record keeps its history. Retiring a stand-in retires a **derived** node,
not a claim; it is the same thing the fold already does to the merged local id's edges.

### Ratings: what moves, and what cannot

Nothing moves. `carryingRatings` copies a score onto the canonical id and never removes one, and
`AffinityStore` has no delete (ADR 39, ADR 46) — so a rating a previous build already carried onto a
superseded canonical id **stays**, and no change here or later can withdraw it. What this change
does is stop re-writing it at every boot, and stop the listing from pretending the id is in the
graph. The residual is named in the ADR amendment rather than quietly fixed, because the honest
statement is "we stopped making more of them", not "we cleaned them up".

## Rejected, with the reason each lost

- **Option 2 — the orphan stays and the export names it as a superseded stand-in.** Honest in the
  sense that the log holds both merges, and it is the option that changes least about what is in the
  graph. **Lost on three counts.** It puts a node nobody claimed into the picture and then annotates
  it, which is more machinery for less truth: a stand-in exists so that a folded edge has an
  endpoint to land on, and a superseded merge folds no edge, so the node has no job left. It states
  a fact about the owner's *correction history* in an artefact that is a picture of the **world**
  graph and is the thing ADR 59 says may be shared. And it costs a new node attribute reaching
  `NodeRecord`, both writers and both folds — against five one-line guards on one predicate — while
  leaving the taste half writing a rating onto an id the owner corrected away from, which no export
  annotation reaches.
- **Fix `standIns` alone**, as the issue's option 1 is worded. **Lost on measurement**: it leaves the
  boot replay holding the orphan through `IngestService.standIn` and the two folds disagreeing, with
  the whole suite green. Recorded here because it is the shape the issue describes and the one an
  implementer would otherwise ship.
- **Compute the last-merge-per-local inside `standIns`** instead of asking `Equivalences.in(log)`.
  Cheaper by one walk of the log. **Lost** because it writes the last-wins rule a second time, in a
  second form (by log position rather than by the map), in the class whose whole argument is that
  one rule has one home — and the other three homes could not share it.
- **Rename the local id away, so one node carries everything** (#178's "rename form of the fold").
  It would remove this orphan and the local id's own. **Still lost, for #178's reason unchanged**:
  `get_entity` on a local id would start answering nothing with no redirect to offer, and it deletes
  more of ADR 59's merge bullet than the defect requires.
- **Refuse a second merge of one local id in `OwnCli`.** No correction, no orphan. **Lost** because a
  second merge *is* how a wrong merge is corrected — the developer guide says so in the operator's
  own words — and refusing it would leave the owner with no way to fix a merge but a retraction that
  takes every other claim about the id with it.
- **Suppress the superseded rating at read time in `Equivalences.resolve`.** It already maps the
  local id onto the last canonical only, so nothing more is needed there — but a row written
  directly against the superseded canonical is indistinguishable from a rating the owner made
  himself, and inventing a rule that drops it would be this class deciding it knows better than the
  store. Out of scope, and named as a residual.

## Concurrency: #220 and #222

Both are `ready` on sibling branches off the same base and both touch this region.

- **#222** (the stand-in's kind on the bypass path) edits the `NodeRecord` construction inside
  `standIns`; this change edits that loop's `if`. A textual conflict is likely and trivial.
- **#220** (a guard over the four homes of the stand-in rule) builds a fixture that explicitly
  includes *"one local merged twice"* and pins **today's** answers. If #220 lands first, its
  expected values for the twice-merged canonical are exactly what this change inverts: all four
  homes must then answer "no stand-in" rather than "a stand-in labelled with the merged entity's
  label". **The final task rebases onto `main` and, if #220's guard is present, updates that case's
  expectation and says so in the commit.** Note for whoever reconciles them: **this note was wrong
  as first written, and is corrected here rather than left to mislead the task it addresses** — it
  said the four homes would then agree about the twice-merged case *by construction, because all
  four ask `Equivalences.stands`*. They do all ask it; they do not all ask it of the same
  `Equivalences`. `IngestService.record` is handed `Equivalences.NONE`, whose `stands` is
  unconditionally true, so the live home goes on building the stand-in the other three retire.
  #220's guard must therefore pin that row **per home** rather than null it outright. See the second
  amendment at the end of this file.

Keep the ADR amendment in its own dated section at the end of `0059-owner-claims-as-a-third-layer.md`
so that three amendments landing in the same week merge cleanly.

## What this does not settle

- **A rating already carried onto a superseded canonical id.** It stays, there is no delete, and
  after this change it reads as `(not in the graph)`. Whether segue should offer any way to disown it
  is a separate decision.
- **Whether `OwnRun` should refuse a superseded canonical id by name.** It now refuses it as an
  absence — *"nothing in the projection is Q… — mint or seed it first"* — which is true but says
  nothing about the correction that made it absent. A named refusal would need `Equivalences` to
  expose the superseded ids; nothing asks for that yet.
- **How many twice-merged local ids the owner's real graph holds.** Unmeasured, as #178's own
  residual says: nothing here opened `~/.segue/segue.db`.

## Amendment during implementation (2026-09-03)

Task 4's review found that the fix as designed above — `stands` is last-wins alone, so a
superseded merge names no stand-in at all — makes a **legal, supported-flow log unbootable**.
Reproduced by the reviewer on `fdd420d`: the log `[node(WREN), minted(CORRECTED),
merged(CORRECTED→MISHEARD), owned(WREN→MISHEARD, "INFLUENCED_BY"),
merged(CORRECTED→WATERMARK)]` gives `Equivalences.standIns(log) = [WATERMARK]` — MISHEARD is
dropped entirely — and `GraphProjector.project` then throws:

```
java.lang.IllegalStateException: replay failed at sequence 4
Caused by: java.lang.IllegalStateException: assertion references unknown entity Q10000900109 - upsert the node first
```

while `LogProjection` tolerates the same `WREN → MISHEARD` edge as dangling rather than throwing,
so the two folds disagree in the worst direction the design above set out to prevent.

The scenario is reachable through the supported flow, not a constructed edge case: `OwnRun` offers
a merge's canonical id as a claimable endpoint the moment its stand-in exists (`assertEdge` via
`labelsInTheProjection`), so an owner who merges, then claims an edge against the new canonical id,
then corrects the merge, produces exactly this log — and every row in it is one ADR 19 forbids
deleting.

**Ruling (controller).** A superseded merge's stand-in **survives** while any surviving edge names
its canonical id: the node is then not an orphan — it has an edge — and the export shows the
owner's real claim rather than silently dropping it. `Equivalences.stands` is widened from
last-wins alone to *last-wins OR a surviving `AssertionRecord`/`OwnerEdge` names this merge's
canonical id as an endpoint*, computed once in `Equivalences.in` from the same pass that builds
`canonicalByLocal`, over surviving rows only. The **rating carry stays last-wins only** — a
separate predicate, `Equivalences.last` — because a node surviving on account of an edge is a fact
about the graph, not the owner's opinion about the thing he corrected himself onto; widening
`stands` without keeping the carry narrow would have reintroduced the very defect a previous round
of this issue fixed (a rating written onto every canonical id a local id ever touched, not only the
one that stands today).

**Two alternatives were considered and rejected:**

- **Re-point the edge onto the corrected canonical id.** Rejected: it silently rewrites what the
  owner actually claimed — he named the *first* id, not the second, and the first id may itself
  turn out to be a real, distinct entity the correction says nothing about. Segue does not edit a
  claim on the owner's behalf; ADR 19 already settles that a correction is a new claim, never an
  edit of an old one.
- **Have `GraphProjector` tolerate the missing endpoint as a dangling edge**, matching how
  `LogProjection` already behaves. Rejected: it replays the owner's claim into nothing without
  saying so — the same silent-data-loss shape issue #101 fixed once already for the rating deck,
  and precisely the failure mode `danglingEdges()` exists to report rather than to produce.

Consequence for the four homes named in `Equivalences.standIns`' javadoc: all four (`standIns`,
`IngestService.standIn`, `OwnRun.labelsInTheProjection`, `ratings/Labels.forQids`) ask
`Equivalences.stands` directly, so the widening reaches every home that reads a log — see the second
amendment for the one that does not — and each was
checked against its own existing coverage (`OwnRunTest.shouldRefuseTheCanonicalIdOfAMergeWhen…`
still reds correctly, because that fixture has no surviving edge naming the corrected-away id) and
given one added case where the surviving-edge path was previously untested
(`RatingsRunTest.shouldKeepACanonicalIdsLabelWhenASurvivingEdgeNamesItDirectlyThoughALaterMergeCorrectedIt`).

This finding and ruling are also the basis for Task 6's ADR 59 amendment.

## Second amendment (2026-09-03, on rebasing onto #220 and #222)

Two corrections and one reconciliation, all of them found by the whole-branch review at `44610ab`.

**1. "All four homes agree by construction" is false, and this branch is what made it false.** The
claim appears above in "Concurrency: #220 and #222" and in the first amendment's closing paragraph;
both are annotated in place, and the same sentence has been corrected in
`Equivalences.standIns`' javadoc, in `IngestService.standIn`'s, in the developer guide and in
[ADR 59](../../adr/0059-owner-claims-as-a-third-layer.md)'s dated amendment before that amendment
landed. What is true: **the four homes ask one predicate, and they agree wherever they are asked the
same equivalences.** Three of them read the whole log and ask `Equivalences.in(log).stands`;
`IngestService.record` applies one claim with `Equivalences.NONE`, whose `stands` is unconditionally
true because it holds no log to contradict the merge in front of it — the `null → true` clause the
design section above states deliberately. So for a local id merged twice the live path still builds
the stand-in the correction retired, and lags until the next boot re-folds the log. That is the same
shape ADR 42 already accepts for a node's kind, and nothing in production reaches it:
`IngestService.record`'s javadoc records that nothing sends a `SameAs` there, because `OwnRun`
appends a merge through `claim`, which has no graph half.

**2. #220's guard, reconciled.** `StandInAgreesInEveryHomeTest` pins one canonical id per row across
the four homes. The row for the twice-merged local id is now pinned **per home** — the fold, the own
tool and the ratings listing hold nothing; the live graph holds the local's kind and label — with an
assertion that it is the only row split that way. #222 had already given the same table a per-home
shape for the bypass row, which is split by *kind* rather than by *presence*; the two assertions are
kept apart so that neither can stand in for the other. Driving the live home through
`GraphProjector` instead would make it agree and was rejected: #220's own `liveGraphNodes` javadoc
says it is the only probe this repository has of `IngestService.standIn`'s upsert.

**3. What actually landed, correcting the "five edits, one predicate" table.** Two predicates, not
one — `stands` (a node) and `last` (the rating carry), deliberately different widths; a new record
component `referencedEndpoints`, with a convenience constructor for callers that have only merges;
a rewritten `Equivalences.in` building that set from the same pass, over an exhaustive switch with
no default arm; **two** guards in `IngestService.apply`'s `SameAs` arm rather than one, because the
stand-in and the carry no longer answer to the same predicate; and a new `OwnRun.correctedTo`, so
the tool refuses a corrected-away canonical id by name instead of as a bare absence. The two label
copies (`OwnRun.labelsInTheProjection`, `Labels.forQids`) are as the table has them.
