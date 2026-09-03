# Retracting a merged local id leaves an edge the boot cannot replay

Issue #224. Written 2026-09-03, against `main` at `0783492` — the commit that landed #221, so
everything below is measured *with* the surviving-edge widening in place.

## The defect, measured

`[node(WREN), minted(L), merged(L → A), owned(WREN → A, "INFLUENCED_BY"), retract(L)]`.

`Retractions.survives` drops a `SameAs` on the **edge** rule — either side retracted and the merge
is gone (`Retractions`' own `SameAs` arm). So the merge stops projecting, `Equivalences.in` never
sees it, `localsOfMerges` filters it out before `stands` is asked about anything, and `standIns`
yields nothing. The owner edge names `A` and `WREN`, neither of which is retracted, so it survives —
and lands on an id no fold holds a node for.

Measured with a scratch probe in `export` (ids from `InventedGraph`: `WREN = Q0900101`,
`L = ALMANAC = Q001`, `A = PRESSING = Q10000900106`). Probe reverted; the tree is clean.

| reading | on `0783492` |
|---|---|
| `Equivalences.standIns(log, KindMapper::rederive)` | `{}` |
| `Equivalences.in(log)` | `Equivalences[canonicalByLocal={}, referencedEndpoints=[Q0900101, Q10000900106]]` |
| `LogProjection.of(log).nodes()` | `[Q0900101]` |
| `LogProjection.of(log).edges()` | `[]` |
| `LogProjection.of(log).danglingEdges()` | `1` |
| `GraphProjector.project(log, …)` | throws |
| `OwnRun.labelsInTheProjection` | `{Q0900101=Wren Alderman}` |
| `ratings/Labels.forQids(log, {A, L})` | `{}` |

The two folds, in their own words:

```
java.lang.IllegalStateException: replay failed at sequence 4
  caused by: java.lang.IllegalStateException: assertion references unknown entity Q10000900106 - upsert the node first
```

```
PROBE fold nodes = [Q0900101] edges = [] dangling = 1
```

So the export looks fine — one node, no edges, and a `danglingEdges` count whose own javadoc says
*"This should always be zero"* — and the application refuses to start at the next restart, on rows
ADR 19 forbids deleting.

**The issue's description of the code is accurate in every particular**, including the claim that
#221's ruling cannot reach this case: `stands` is asked only of merges `localsOfMerges` kept, and
`localsOfMerges` asks `Retractions.survives` first. Two things it does not say are worth adding,
because they narrow the fix:

**1. The other two homes of the stand-in rule already answer correctly.** `OwnRun` no longer offers
`A` as a claimable endpoint (measured above), so the supported flow cannot add a *second* edge to
`A` after the retraction; and `ratings/Labels.forQids` gives `A` no label, so a rating an earlier
build carried onto it reads as `(not in the graph)` — which is what that string was written for
(#221's residual). The defect is in the two folds alone.

**2. The rule must not reach a canonical id the projection holds on its own account**, and both
escapes were measured green on `0783492` and must stay green:

| fixture | on `0783492` |
|---|---|
| `[node(WREN), minted(L), node(A) sourced, merged(L → A), owned(WREN → A), retract(L)]` | fold nodes `[Q0900101, Q10000900106]`, 1 edge, 0 dangling; **replay OK** |
| `[node(WREN), minted(L), minted(M), merged(L → A), merged(M → A), owned(WREN → A), retract(L)]` | fold nodes `[Q10000900106, Q0900101, Q002]`, 1 edge, 0 dangling; **replay OK** |

In the first the source claimed `A` itself; in the second `M`'s merge still stands, so `standIns`
still names `A`. Retracting the local id must leave both exactly where they are — the developer
guide already promises the first in as many words: *"Retract the local id and its node claim, its
owner edges and the merge all stop projecting. What a source claimed about the canonical id is
untouched."*

## What the owner meant

He minted `L`, Wikidata caught up, he said `L` is `A`, and — because `OwnRun` offers a merge's
canonical id as an endpoint the moment its stand-in exists — he then claimed an edge against `A`.
Retracting `L` says, in ADR 44's words, *"everything recorded about this entity before now is
wrong"*. By his own merge, `L` **is** `A`; and the only node `A` ever had was the one that merge
stood in for. So the claim he made against `A` was a claim about the entity he has just taken back,
written under the name the merge gave it. It is not a neighbour ADR 44 declines to cascade to: a
neighbour has a node claim of its own, and this id has none.

That is the whole of the argument, and it is why the reach is bounded by *"unless something else
holds a node for it"* rather than by who claimed the edge.

## The decision

**Ruling: retracting a merged local id also takes the edges that name the stand-in its merge
created — and only those.** Stated as a fold rule under ADR 44, never as a delete:

> A canonical id is a **retracted stand-in** when a merge naming it was dropped because a retraction
> reached its *local* side, and nothing else in the projection holds a node for it: no surviving
> node claim, and no surviving merge whose stand-in it still is. An edge claim naming a retracted
> stand-in at either end does not reach the projection.

Both folds get it from one place, and neither fold grows a line: `Equivalences.foldEndpoints` — the
method both already call for every edge, and which both already handle yielding nothing for
(the self-loop rule, ADR 59's 2026-09-02 amendment) — yields nothing for such an edge. The set is
computed once, in `domain`, as `Equivalences.retractedStandIns(log)`, and carried as a third
component of the `Equivalences` the two folds build through a new named factory,
`Equivalences.folding(log)`. `Equivalences.in` is unchanged and keeps its callers (`OwnRun`,
`ratings/Labels`, `KnownList`, `RateRun`), none of which folds an edge.

### What each reading says afterwards

| reading | after the fix |
|---|---|
| `LogProjection.of(log).nodes()` | `[Q0900101]` — unchanged |
| `LogProjection.of(log).danglingEdges()` | `0` |
| `GraphProjector.project(log, …)` | boots; one node, no edges |
| a `full` export | `WREN` alone, no orphan, no edge — the owner's remaining claims and nothing else |
| the log | every row still in it, retraction included (ADR 19) |
| the rating | untouched. `Equivalences.last` still governs the carry, so nothing new is written; a score an earlier build carried onto `A` stays and reads `(not in the graph)`, exactly as #221's residual says |

### Which of the four homes it touches

None of them changes its answer, which is the point. `Equivalences.standIns` and
`IngestService.standIn` are untouched; `OwnRun.labelsInTheProjection` and `ratings/Labels.forQids`
already answer "no node" and "no label" (measured above). The rule lives beside the stand-in rule
rather than inside it: it is about the **edges** that named a stand-in, not about which stand-ins
exist. `StandInAgreesInEveryHomeTest` gains this shape as a pinned row all the same, because the
live home splits from the other three on it — `IngestService.record` is handed `Equivalences.NONE`
and refuses a `Retraction` outright, so the live graph keeps the stand-in until the next boot, which
is ADR 24's stated lag and the same shape as the twice-merged row already pinned there.

### The alternatives, and why each lost

- **The stand-in survives the retraction while a surviving edge names it, as #221 does for
  correction.** The symmetry is real and it is the first thing to reach for. **Lost on what the node
  would be made of.** In #221 the local node survives — only the *merge* was superseded — so the
  stand-in copies a claim that still stands. Here the local claim is retracted: `localsOfMerges`
  builds its `claimed` map from surviving rows only, so there is no local node to copy, and building
  one anyway means reading a retracted `LocalEntity` for its kind and its label and putting the
  owner's withdrawn working title on a live node in an export somebody keeps. That is ADR 44
  inverted — the projection would go on saying the thing the retraction exists to stop it saying —
  and the node would be assembled out of two retracted rows, the mint and the merge, with nothing
  surviving to support it. A label-less or annotated stand-in is the *"name the orphan in the
  export"* alternative #221 already rejected, and it loses here for the same three reasons plus a
  fourth: every one of the four homes would have to learn to build a node for a merge that does not
  survive, which unpicks *"every home asks `Retractions.survives` before it asks `stands`"*.

- **`GraphProjector` tolerates an unknown endpoint the way `LogProjection` does.** Rejected in #221
  for silently dropping a claim (#101's shape), and the question here is whether anything is
  different. **One thing genuinely is, and it does not save the option.** In #221 the missing
  endpoint was a defect — the fold had wrongly retired a node the log still needed — so tolerating
  it would have hidden a bug that had a fix. Here the absence is *correct*: the owner retracted the
  entity, and the node is rightly gone. But tolerance is still the wrong verb for it. It buys this
  one case by removing the loud failure from **every** case: a genuinely corrupt log, a bug in a
  future fold, a source adapter that emits an edge before its node — all of them stop failing at
  boot and start being counted in a field whose javadoc says it should always be zero, and
  `LogProjection.danglingEdges` exists *to report* that failure rather than to produce it. The right
  answer is to make the edge stop projecting for a stated reason, which is what the ruling does, and
  to keep the boot loud for everything else. Rejected again.

- **Refuse the retraction at the tool** when the local id has been merged and a surviving edge names
  its canonical id. Cheap, and it is where the operator is. **Lost twice over.** The fold must cope
  with the row regardless — ADR 19 makes the log append-only and a `retractEntity` refusal cannot
  reach a row already written, or one written by any other hand — so this would be a guard in front
  of a fold that still could not replay. And it takes the owner's only way back out of a wrong mint:
  #221 already rejected refusing a second merge on the grounds that *"the only alternative left to
  the owner would be a retraction"*, and this would close that door too.

- **Re-point the edge onto the local id, or onto nothing.** Rejected for #221's reason, unchanged:
  segue does not rewrite a claim on the owner's behalf. He named `A`.

- **Accept and record.** The repository has shipped documented refusals that beat their fixes.
  **Lost on the consequence**: the failure is not a distortion in a score, it is an application that
  will not start, reachable through the supported flow in four commands, on rows nothing may delete.

### Cost of the ruling, stated rather than glossed

A claim the owner made stops projecting, and he is not told at the moment he types the command.
`RetractRun.measure` counts claims naming the qid being retracted, so its report — the safety
feature the guide leans on — would say *"1 node claim(s) and 1 edge claim(s)"* and not mention the
edge naming `A`. Silence is half of the defect this ruling rejects Option 3 for, so it is closed
here too: the report gains a line naming the stranded canonical ids and counting the edge claims
that go with them, computed from the same `Equivalences.retractedStandIns` the folds use, over the
log the retraction would produce. `Effect`'s two counts keep their meaning — a retraction of an id
with nothing of its own is still refused on the same arithmetic — and the new line is a note.

`Equivalences.retractedStandIns` therefore takes **no** re-derivation parameter, unlike `standIns`
and `localsOfMerges`. It reads only which canonical ids have a stand-in, never what kind that node
is, and `retract` may not grow a dependency on `wikidata` to hand in a `KindMapper::rederive` it
would never use — the layering diagram this guide checks would gain an edge for nothing, and ADR 44
is explicit that *"a retraction is nobody's vocabulary"*. It calls `standIns(log,
UnaryOperator.identity())` and reads the key set, and the claim that the key set cannot depend on
the re-derivation is made falsifiable rather than asserted: `EquivalencesTest` compares the key sets
under two re-derivations that give every node a different kind, and shows the values differing so
the comparison is not vacuous.

## What this does not settle

- **An edge naming a canonical id appended *after* the retraction still dangles.** The retraction
  reaches backwards only (ADR 44), and this rule reaches exactly as far. It is unreachable through
  the supported flow — `OwnRun` refuses the id, measured above — and a sourced edge arriving before
  its own node claim is a general hazard this issue does not own.
- **The ADR amendment goes on ADR 44, not ADR 59.** The decision that changes is ADR 44's first
  question — the granularity of a retraction, and the paragraph that says it does not cascade. ADR
  59 needs none: `Equivalences.stands`, `Equivalences.standIns`, the rating carry and all four homes
  of the stand-in rule are untouched, and the 2026-09-02 amendment's *"a fold that would collapse an
  edge onto itself drops that edge"* already establishes that `foldEndpoints` may yield nothing.
- **ADR 44 has a stale consequence bullet, unrelated to this issue and not repaired here.** It says
  *"The ratings tool is deliberately not part of this. `Labels.forQids` reads node claims straight
  out of the log without applying the rule"*. `Labels.forQids` has applied `Retractions` since #92,
  and its javadoc cites ADR 44 as the precedent for doing so. Code and ADR disagree; the amendment
  written for this issue records the disagreement and does not quietly fix it by editing the
  original, and nothing here depends on which of the two is right.
