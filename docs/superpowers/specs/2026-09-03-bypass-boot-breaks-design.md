# Two logs that cannot boot after a retraction or a re-merge, and one that boots wrong

Issue #228. Written 2026-09-04, against `main` at `a7c3455` — the commit that landed #227's census,
so everything below is measured *with* #221's surviving-edge widening and #224's withdrawal rule
already in place.

Every reading below came from a throwaway probe in `export` driving `Equivalences`,
`LogProjection.of` and `GraphProjector.project` over hand-built `FakeAssertionLog`s. The probe was
deleted; the tree is clean. Every id is invented (ADR 40, ADR 51): `WREN = Q0900101`,
`LAPSE = Q008` (a local the owner minted), `FORFEIT = Q10000900112` and `KETTLES = Q10000900102`
(canonical sides), `ALMANAC = Q001`, `PRESSING = Q10000900106`.

## The three defects, measured

### 1. Retract the local, then merge it again somewhere else

```
seq 1  node(WREN)
seq 2  minted(LAPSE)
seq 3  merged(LAPSE → FORFEIT)
seq 4  retract(LAPSE)
seq 5  merged(LAPSE → KETTLES)
seq 6  owned(WREN → KETTLES, "INFLUENCED_BY")
```

| reading | on `a7c3455` |
|---|---|
| `Equivalences.in(log)` | `canonicalByLocal={Q008=Q10000900102}, referencedEndpoints=[Q0900101, Q10000900102]` |
| `Equivalences.retractedStandIns(log)` | `[Q10000900112]` |
| `Equivalences.standIns(log, KindMapper::rederive)` | `{}` |
| `LogProjection.of(log).nodes()` | `[Q0900101]` |
| `LogProjection.of(log).danglingEdges()` | `1` |
| `LogProjection.of(log).withdrawnEdges()` | `0` |
| `GraphProjector.project(log, …)` | throws |

```
java.lang.IllegalStateException: replay failed at sequence 6
  caused by: java.lang.IllegalStateException: assertion references unknown entity Q10000900102 - upsert the node first
```

The retraction reaches backwards, so `minted(LAPSE)` and the first merge stop projecting; the
*second* merge lies after the retraction and survives it, so `canonicalByLocal` says `LAPSE` is
`KETTLES`. But `localsOfMerges` finds no surviving node claim for `LAPSE` at that merge's row, so
the merge has no local side, `standIns` builds nothing for `KETTLES`, and nothing else in the log
claims it. `KETTLES` is not in `retractedStandIns` either — no retraction emptied it; it never had
anything — so `foldEndpoints` does not withdraw the edge. The edge names an id no fold holds.

**The same log with the edge naming the LOCAL rather than the canonical throws identically** —
`owned(WREN → LAPSE)` at sequence 6 folds onto `KETTLES` through `canonicalByLocal` and dies on the
same message. **And the same log with the edge left off boots cleanly** (`applied 2`, no node under
either canonical id), which is what says the merge row alone is inert and the edge is what breaks
the boot.

### 2. An edge naming a local that folds onto a canonical a retraction emptied

```
seq 1  node(WREN)
seq 2  minted(LAPSE)
seq 3  merged(LAPSE → FORFEIT)
seq 4  retract(LAPSE)
seq 5  merged(LAPSE → FORFEIT)      ← the same canonical id, not a different one
seq 6  owned(WREN → LAPSE, "INFLUENCED_BY")
```

| reading | on `a7c3455` |
|---|---|
| `Equivalences.in(log)` | `canonicalByLocal={Q008=Q10000900112}, referencedEndpoints=[Q0900101, Q008]` |
| `Equivalences.retractedStandIns(log)` | `[Q10000900112]` |
| `Equivalences.standIns(log, KindMapper::rederive)` | `{}` |
| `LogProjection.of(log).nodes()` | `[Q0900101]` |
| `LogProjection.of(log).danglingEdges()` | `1` |
| `LogProjection.of(log).withdrawnEdges()` | `0` |
| `GraphProjector.project(log, …)` | throws |

```
java.lang.IllegalStateException: replay failed at sequence 6
  caused by: java.lang.IllegalStateException: assertion references unknown entity Q10000900112 - upsert the node first
```

`FORFEIT` **is** in `retractedStandIns` — the fold knows the id was emptied — and the edge still
projects, because `Equivalences.namesARetractedStandIn` reads `claim.fromQid()` and `claim.toQid()`
as the claim wrote them. The edge names `LAPSE`, not `FORFEIT`; the resolution happens two lines
later. So the withdrawal rule #224 landed does not fire on an edge that reaches the emptied id
through a merge rather than by name.

**Finding: the issue says this throws at sequence 5; it throws at sequence 6.** A five-row version
of this log does throw at sequence 5, but on `unknown entity Q0900101` — the edge's *other*
endpoint, which no row had claimed. That is a different absence, and reporting it as this defect
would have pinned the wrong cause. The fixture below claims `WREN` first, and the count moves to
six. Nothing else in the issue's description of the code is wrong: `foldEndpoints` does read raw
endpoints before resolving them, exactly as stated.

### 3. A withdrawn edge still keeps a superseded stand-in alive

```
seq 1  minted(LAPSE)
seq 2  merged(LAPSE → FORFEIT)
seq 3  minted(ALMANAC)
seq 4  merged(ALMANAC → PRESSING)
seq 5  owned(FORFEIT → PRESSING, "INFLUENCED_BY")
seq 6  merged(LAPSE → KETTLES)          ← the correction; FORFEIT is superseded
seq 7  retract(ALMANAC)                 ← empties PRESSING, so seq 5 is withdrawn
```

| reading | on `a7c3455` |
|---|---|
| `Equivalences.in(log).referencedEndpoints` | `[Q10000900112, Q10000900106]` |
| `Equivalences.retractedStandIns(log)` | `[Q10000900106]` |
| `Equivalences.standIns(log, KindMapper::rederive)` | `[Q10000900112, Q10000900102]` |
| `LogProjection.of(log).nodes()` | `[Q10000900112, Q10000900102, Q008]` |
| `LogProjection.of(log).edges()` | `[]` |
| `LogProjection.of(log).withdrawnEdges()` | `1` |
| `GraphProjector.project(log, …)` | `applied 3`, node under `Q10000900112` |

The one edge is withdrawn — the fold says so, and counts it — and `FORFEIT` keeps a labelled node
anyway, because `referencedEndpoints` is built from the *surviving* rows and not from the ones the
fold keeps. So a `full` export draws a node with no edges under an id the owner corrected himself
away from, carrying his withdrawn working title, and `OwnRun` goes on offering that id as a
claimable endpoint. Replay is unaffected: a node nothing names is exactly what boots.

**Finding: this is the only one of the three reachable through the supported flow.** Every row above
is one the own tool would write — `merge` says a second merge rather than refusing it, `assert`
offers both canonical ids the moment their stand-ins exist, and `retractEntity` retracts a local
id. Both *boot* breaks, by contrast, need a merge `OwnRun.declareMerge` refuses outright
(`mintedInTheProjection` holds nothing for a retracted local: *"nothing in the projection minted
… — check the id, or it may already be retracted"*), so they are reachable only by a caller that
appends through `IngestService.claim` directly, or writes the row into SQLite by hand. The issue
calls this third one "smaller"; it is smaller in **effect**, not in reach.

## What the owner meant

Two of these three logs say something. The third does not.

- **Break 3** is a correction followed by an unrelated retraction. The owner meant both. What he did
  not mean is a node standing under an id whose only claim the projection has just withdrawn.
- **Break 2** is a merge re-declared onto the same id the owner had already emptied by retracting
  its local side, with an edge claimed against the retracted local afterwards. The edge is a claim
  about the entity he retracted, written under a name his own merge gave it — which is word for word
  the sentence ADR 44's 2026-09-03 amendment already uses to justify withdrawing such an edge. It is
  the same case wearing the local id's name.
- **Break 1** says nothing at all. `merged(LAPSE → KETTLES)` after `retract(LAPSE)` asserts that a
  thing the projection does not hold is a Wikidata item. There is no local side to stand in for, so
  there is no node to build, and there is nothing about the log that says what `KETTLES` is. An edge
  claimed against it is a claim about an entity nothing has ever described.

That difference is the whole of the decision below: two of the three can be answered by the fold,
and the third cannot be answered by any fold that does not either invent a node or drop a claim
nobody retracted.

## The decision

**Four rulings, and they are not all the same kind of thing.**

1. **Withdrawal reads the endpoints the fold resolves, not the ones the claim wrote.**
   `Equivalences.namesARetractedStandIn` resolves both endpoints through `canonicalByLocal` before
   asking `retractedStandIns`. This closes break 2 by applying the rule ADR 44 already states to the
   case its implementation missed — a defect against a decision, not a new decision.

2. **A withdrawn edge keeps nothing alive.** `referencedEndpoints` counts only the edges the fold
   keeps, so a superseded merge's stand-in does not survive on the strength of a claim the
   projection has withdrawn. This closes break 3.

3. **An owner claim that would leave the log unbootable is refused before the append**, at
   `IngestService.claim` — the one gate every owner claim passes, `OwnRun`'s included. Two
   refusals: a `SameAs` whose local side the projection holds no node for, and an `OwnerEdge` whose
   folded endpoint the fold would hold no node for. This closes break 1 at the producer.

4. **A log that already carries such a row is refused at boot by name.** `GraphProjector.project`
   asks, before it applies anything, whether every surviving edge the fold keeps names endpoints the
   fold holds; if not it throws a message naming each sequence number, the id nothing stands for, and
   the repair. The unknown-entity throw from `TinkerGraphStore.record` stops being the operator's
   first news of the problem.

**Ruling 3 plus ruling 4 is the recommendation the issue asked for**, and it is deliberately both
halves. A producer guard alone leaves a log written by an older build — or by a hand-written SQLite
row — dying at every boot on a stack trace that names a QID and not a cause. A boot diagnosis alone
lets the row in and then explains it forever: *"a claim rejected only on replay poisons every
boot"*. Rulings 1 and 2 are separate from that pair; they are fold rules for the two cases where a
fold rule is available and correct.

**One new shared question in `domain` carries three of the four.**
`Equivalences.nodesTheFoldHolds(log)` — the stand-ins the fold builds, plus every id a surviving
node claim or minted entity names — already existed as the private `held` local inside
`retractedStandIns`. It is promoted, unchanged, and read by the producer guard (ruling 3), the boot
refusal (ruling 4) and `retractedStandIns` itself. Its answer is exactly
`LogProjection.of(log).nodes().keySet()` and exactly the node set a `GraphProjector` replay leaves;
a test asserts all three rather than asserting the method against itself.

### Why the surviving-edge rule needs a loop, and why the loop is the rule

Ruling 2 is circular on its face. Which edges are withdrawn depends on which canonical ids a
retraction emptied; which ids are emptied depends on which stand-ins survive; which stand-ins
survive depends — since #221 — on which edges reference them. Dropping withdrawn edges from that
last set can retire a stand-in, which can empty another canonical id, which can withdraw another
edge.

So the emptied set is computed as a least fixed point: start from the empty set, compute the emptied
set that follows from it, repeat until it stops growing. It terminates because the step is monotone
— a larger emptied set withdraws more edges, references fewer ids, stands fewer merges, holds fewer
nodes and therefore empties at least as much — and the canonical ids in a log are finite. For a log
with no retractions it costs exactly one round and returns the empty set, which is every log the
real graph has ever held.

One round would have closed the case measured above and left a second-order chain open. "One round,
and here is the residual" is a worse rule to write into an ADR than a loop of six lines.

### What each reading says afterwards

| reading | break 1 | break 2 | break 3 |
|---|---|---|---|
| `IngestService.claim` on the offending row | refused, naming the id | refused, naming the id | not applicable — every row is legal |
| `LogProjection.of(log).nodes()` | `[Q0900101]`, unchanged | `[Q0900101]` | `[Q10000900102, Q008]` — no `Q10000900112` |
| `LogProjection.of(log).danglingEdges()` | `1`, unchanged | `0` | `0` |
| `LogProjection.of(log).withdrawnEdges()` | `0`, unchanged | `1` | `1`, unchanged |
| `GraphProjector.project` | refuses, naming sequence 6 and the repair | boots, edge withdrawn | boots, no node under `Q10000900112` |

**The two folds still disagree about break 1, and that is the accepted asymmetry, not a regression.**
The exporter counts a dangling edge and produces a picture; the boot refuses to start. ADR 44
already argues why: `danglingEdges` exists to *report* a log that cannot boot, and making
`GraphProjector` tolerate the missing endpoint instead would take the loud failure away from every
other case — a corrupt log, a future fold's bug, an adapter emitting an edge before its node.
`BothFoldsAgreeTest` cannot hold break 1's shape for the same reason it could not hold #224's before
the fix: one fold throws, so the comparison never runs.

### Which of the four homes this touches

Ruling 2 changes `Equivalences.in`, so it reaches all three homes of the stand-in rule that read a
log — `Equivalences.standIns` (both folds), `OwnRun.labelsInTheProjection` and
`ratings/Labels.forQids` — and they move together, which is what `StandInAgreesInEveryHomeTest`
exists to check. The fourth home, `IngestService.standIn` on the live path, is handed
`Equivalences.NONE` and is unchanged: it holds no log, so it has no edge to withdraw. Both
directions of that change are improvements — the own tool stops offering an endpoint whose node is
an artefact, and `listRatings` reports a rating carried onto it as `(not in the graph)`, which is
what that string was written for.

`BothFoldsAgreeTest` gains break 2's two rows on the end of its `ownedLog` fixture, because ruling 1
changes what both folds make of them. Break 3's shape is **not** added there: building a superseded
canonical whose only reference is a withdrawn edge takes seven more rows and four more invented ids,
and `TwiceMergedIdLeavesNoOrphanTest` already drives both folds over exactly that shape — its
`shouldKeepNoSupersededStandInAliveWhenTheOnlyNamingEdgeIsRetracted` is the retracted sibling of the
new withdrawn case.

### The alternatives, and why each lost

**A fold rule for break 1 — the issue's first option.** Two shapes were considered and both lose.

- **Build a stand-in for the second merge anyway.** There is nothing to build it from: the local
  side's claim is retracted, so its kind and its label are rows the retraction exists to stop the
  projection reading. That is ADR 44's own argument against the symmetric proposal for #224
  (*"a node assembled entirely out of retracted rows"*), and a label-less node is the *name the
  orphan in the export* alternative ADR 59 already rejected.
- **Withdraw the edge, as ruling 1 withdraws break 2's.** Nothing retracted `KETTLES`. It may be a
  real Wikidata item that a source will claim tomorrow, and the edge is a claim the owner made about
  it that no retraction reaches. Withdrawing it would replay a live claim into nothing — the
  silent-data-loss shape #101 fixed once already, and the one both ADR 59 and ADR 44 have now
  refused twice. The generalisation *"withdraw any edge whose folded endpoint the fold holds no node
  for"* is the same option wearing a rule's clothes: it is exactly the tolerate-the-dangle option,
  moved from `GraphProjector` into `Equivalences`, and it turns `danglingEdges` — the alarm that is
  supposed to stay zero — into a number nobody would ever see rise.

**A named refusal at boot alone — the issue's second option.** Kept, as ruling 4, and rejected as
the *whole* answer. It leaves the producer free to write the row, so the next one arrives the same
way; and the log is append-only, so every such row is permanent. Validation belongs at the append,
where a rejection costs the operator a message and not a graph.

**A producer guard alone — the third option.** Kept, as ruling 3, and rejected as the whole answer
for the mirror reason: a guard cannot reach a row already written, and the two logs in this issue
are precisely logs somebody could already hold.

**Refuse the merge in `OwnRun` only, and leave `IngestService.claim` alone.** `OwnRun` already
refuses it — that is why both breaks are bypass-only — and the whole issue is about the path that
does not go through `OwnRun`. A guard in front of one caller is not a gate.

**Move the refusal into `OwnRun` and delete it from `IngestService`.** Rejected the other way: the
tool's refusal is *narrower* than the gate's, and deliberately. `OwnRun.declareMerge` requires the
local side to be something the **owner minted**, because pointing a merge at a sourced entity is a
different claim it does not make; the gate asks the fold's own question — is there a surviving node
claim of any kind — because spec ruling 2 requires the fold to accept a local-shaped id a source
named. Two questions, two homes, both stated.

**Leave break 3 as a residual again.** ADR 44 already recorded it once. Recording a known defect
twice is how it becomes permanent, and this is the only one of the three that the supported flow can
produce. Lost.

**One round of the fixpoint instead of a loop.** Closes the measured case and leaves a constructible
one open, with no way to say in an ADR where the line is. Lost to six lines.

### Cost, stated rather than glossed

**`IngestService.claim` now reads the whole log before it appends.** For a `SameAs` it also builds
`Equivalences.localsOfMerges`; for an `OwnerEdge` it builds `Equivalences.folding` and
`nodesTheFoldHolds`. Both are a handful of linear passes over the log. The only caller is a dev-side
tool that has already read the whole log once in the same run and appends exactly one row, so the
cost is a second read per invocation, not per claim. No timing was taken: the real database may not
be read by this work, and a synthetic timing assertion is banned on a loaded machine.

**`GraphProjector.project` now makes three extra passes before replay** —
`nodesTheFoldHolds` (which recomputes `standIns`) and one fold of every surviving row. Boot already
walks the log four times (`Retractions.in`, `folding`, `standIns`, the replay loop). This is the same
order of cost, offline, once per process start.

**The fixpoint runs one round on a log with no retractions**, which is every log the owner's real
graph has held: measured today by the census, it holds 0 retractions, 0 merges and 1 minted local.
Every path in this document is fixture-only, and that fact is what argues for the cheapest correct
answer at each of the four rulings rather than for the most general one.

## What this does not settle

**`IngestService.record` still appends before it applies, and a sourced edge naming an endpoint the
graph has never seen is already in the log when `graph.record` throws.** That is the same poison-pill
shape this issue closes for owner claims, on the live path, and the ordering it comes from is
deliberate and argued (*"if the graph update fails, the log is ahead — the recoverable direction"*).
It is recoverable only when the row can eventually project; when it cannot, the log is unbootable
and ADR 19 forbids removing the row. Ruling 4's boot refusal will now name it, which is strictly
better than today, and ruling 3 deliberately does not reach it: `record` is the sourced path, it has
no dev-tool caller, and changing its ordering is a decision against ADR 19's own reasoning that
belongs in its own issue. **Filed as a finding here, not fixed.**

**Nothing gives the owner a way to disown a rating carried onto an id whose stand-in this work
retires.** ADR 59's 2026-09-03 amendment already records that residual and it is untouched:
`AffinityStore` has no delete.

**The stand-in rule still has four homes.** Ruling 2 makes three of them move together; it does not
reduce the count, and ADR 59's residual stands.

**There is no `--force` and no way to make a refused claim through the tools.** A caller determined
to write an unbootable row can still write it into SQLite by hand, which is why ruling 4 exists at
all. A guard at the producer bounds the mistake; it does not make the shape unrepresentable.
