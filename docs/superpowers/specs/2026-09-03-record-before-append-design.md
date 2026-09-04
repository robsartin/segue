# A sourced edge the store refuses is already in the log

Issue #233. Written 2026-09-04, against `main` at `f7e6fe4` — the commit that landed #227's census
cleanup, so everything below is measured *with* #221's surviving-edge widening and #224's withdrawal
rule already in place, and *without* #228, which is in flight.

Every reading below came from a throwaway probe under `src/test`, driving a real
`SqliteAssertionLog` (a `@TempDir` file for the boot half, `inMemory()` for the repair half), a real
`TinkerGraphStore`, `IngestService.record`, `GraphProjector.project`, `LogProjection.of` and
`SegueService.expandEntity`. The probes were deleted; the tree is clean. Every id is invented and
carries ADR 58's leading zero: `WREN = Q0900101`, `KETTLES = Q0900102`, `MARRAM = Q0900103`.

## The defect, measured

### The live throw, and the row it leaves behind

A log holding one node claim, then one sourced edge naming an endpoint no claim ever described:

```
seq 1  node(WREN)
seq 2  edge(WREN → KETTLES, "INFLUENCED_BY")
```

`IngestService.record` on the edge:

```
LIVE THROW: java.lang.IllegalStateException: assertion references unknown entity Q0900102 - upsert the node first
LOG AFTER THE THROW: 2 row(s)
  NodeAssertion[qid=Q0900101, …]
  AssertionRecord[fromQid=Q0900101, toQid=Q0900102, typeCode=INFLUENCED_BY, …]
```

The call failed. The row is in the log.

### And every boot after it

Reopening that same SQLite file into a fresh `TinkerGraphStore`:

```
REPLAY THROW: java.lang.IllegalStateException: replay failed at sequence 2
Caused by: java.lang.IllegalStateException: assertion references unknown entity Q0900102 - upsert the node first
REPLAY 2 THROW: java.lang.IllegalStateException: replay failed at sequence 2
Caused by: java.lang.IllegalStateException: assertion references unknown entity Q0900102 - upsert the node first
```

Twice, deliberately: the second boot is what says this is not a first-boot artefact. The exporter's
fold over the same log does not throw — it counts the edge instead:

```
FOLD: nodes=[Q0900101] edges=0 dangling=1 withdrawn=0
```

which is exactly what `LogProjection`'s own javadoc says `danglingEdges` is for: *"This should always
be zero — `TinkerGraphStore.record` requires both vertices, so a log holding one would fail replay at
boot"*. The alarm works. It is the only thing in the system that does.

### The obvious repair does not work; one repair does

Two repairs were tried against the poisoned log, both append-only and so both legal under ADR 19:

| repair | reading |
|---|---|
| *(none)* | `THROWS replay failed at sequence 2 ‖ assertion references unknown entity Q0900102 - upsert the node first` |
| append `node(KETTLES)` after the edge | `THROWS replay failed at sequence 2 ‖ assertion references unknown entity Q0900102 - upsert the node first` |
| append `retract(KETTLES)` after the edge | `BOOTS, applied 1, edges 0` |

**Appending the missing node claim does not repair the log.** Replay is positional and the new claim
lands *after* the row that needs it, so sequence 2 fails exactly as before. The only repair the
existing tools give is `./gradlew retractEntity --qid <the missing endpoint>`: `Retractions.survives`
drops every edge naming a retracted entity at either end, so the retraction withdraws the poisoned
row without deleting anything.

**This is load-bearing for the wording of any boot diagnosis**, and it is the reason the producer's
refusal and the boot's diagnosis cannot share a sentence. Before the append the right advice is
*record the node claim first*. After the append that advice is wrong, and the only true advice is
*retract the endpoint*. Two moments, two sentences.

## Does the supported flow reach it?

The issue says it does. It is reachable, and the guard that stands between it and a user today is an
adapter convention that nothing states, tests or enforces.

**`SegueService.expandEntity` resolves one endpoint per assertion, not two.**

```java
  /** The other end of an assertion from the seed's point of view, or null if both ends are it. */
  private static String neighborOf(AssertionRecord assertion, String seedQid) {
    if (!assertion.fromQid().equals(seedQid)) {
      return assertion.fromQid();
    }
    if (!assertion.toQid().equals(seedQid)) {
      return assertion.toQid();
    }
    return null;
  }
```

For an edge naming the seed at one end this is complete: the far end is resolved and recorded, the
near end is the seed, and the seed is in the graph because `expandEntity` refuses to start otherwise.
For an edge naming the seed at *neither* end it resolves `fromQid` and never looks at `toQid`.

Driven through the facade with a stub adapter returning one third-party edge, a stub resolver that
can identify `KETTLES` and not `MARRAM`, and `WREN` seeded in the graph:

```
EXPAND THREW OUT OF THE FACADE: java.lang.IllegalStateException: assertion references unknown entity Q0900103 - upsert the node first
LOG: 3 row(s)
  NodeAssertion[qid=Q0900101, …]
  NodeAssertion[qid=Q0900102, …]
  AssertionRecord[fromQid=Q0900102, toQid=Q0900103, …]
REPLAY THROW: replay failed at sequence 3 | cause: assertion references unknown entity Q0900103 - upsert the node first
```

Three findings in one reading. The store's exception **escapes `SegueService`**, against that class's
own second invariant (*"nothing thrown by a port escapes a public method here except a programmer
error"*) and against the developer guide's sentence about this exact method (*"The call returns a
single `ToolResult` whose outcome is `ok` or `partial`, never a thrown exception"*). It escapes
**after two rows are already committed**, so the caller is told nothing about what did land. And the
log it leaves is unbootable.

Make `MARRAM` unresolvable *too* and the assertion is skipped instead — `PARTIAL / 1 neighbour(s)
could not be resolved and were skipped`, one row in the log, replay clean. The defect needs the first
endpoint resolvable and the second not.

**Do the shipped adapters produce such an edge? No — every one of them puts the seed at an end.**

- `ClaimMapper.map(subjectQid, …)`: `String from = type.wikidataInverted() ? objectQid : subjectQid;`
  and `to` the other of the two. One end is always the seed.
- `ReverseClaims.assertion(seedQid, other, …)`: the same shape, and it additionally `continue`s on
  `other.equals(seedQid)` with a comment naming `neighborOf` as the reason.
- `MusicBrainzSourceAdapter.toAssertion(seedQid, …)`: `String from = forward ? seedQid : targetQid;`.

So the answer to the issue's question is: **the supported flow reaches the throw, but only through an
adapter that does not exist yet.** Nothing in `SourceAdapter`'s contract forbids a third-party edge —
a similarity source, or a MusicBrainz relation between two of the seed's collaborators, is exactly the
shape that would produce one — and the SPI's design rule is that *adding a source must not require
touching the graph layer*. Today it would require touching `SegueService`, silently, or the next
source poisons the log the first time it runs.

**Does an adapter return an edge whose endpoint it did not also describe as a node claim? Yes,
routinely, and that half is already handled.** `ReverseClaims.neighbours` `continue`s on a neighbour
whose label `WikibaseLabels.believable` rejects, keeping the assertion; `MusicBrainzSourceAdapter`
adds the assertion unconditionally and fills `neighbors()` only when `describes(neighbour)` — label
present and classes non-empty. In both cases `expandEntity` falls back to `resolver.fetch` and, on
failure, adds the neighbour to `unresolvableNeighbors` and skips the assertion. That path is correct
and is not what this issue is about.

**Can a partial failure mid-batch leave an edge without its node? No, and for a reason worth
stating.** Within one assertion `expandEntity` records the neighbour's node claim through
`ingest.record` *before* the edge, and `IngestService.record` is one claim at a time — there is no
batch to be half-applied. `recordAll` is `assertions.forEach(this::record)`, so a failure stops the
batch with every earlier claim fully written to both stores; it never leaves a node claim in the log
that the graph refused, because `upsertNode` cannot refuse. The mid-batch hazard is the opposite one,
and the probe shows it: a failure **after** committed rows, reported to the caller as a stack trace.

## Where the rule already lives

The issue's objection to checking before the append is *"Two homes of one rule unless the check is
the store's own"*. It is already two homes, and has been since the bake-off:

- `TinkerGraphStore.requireVertex` — `"assertion references unknown entity " + qid + " - upsert the node first"`.
- `JenaGraphStore.requireKnown` — the same sentence, byte for byte, with the comment *"An edge to an
  entity nothing has claimed is a claim about nothing."*
- `GraphStoreContract` pins both: *"recording an assertion against an unknown entity is rejected, not
  silently materialised"*.
- `LogProjection.danglingEdges` counts the same condition in the exporter's fold.

So the rule is not one home being split into two. It is a **store precondition**, agreed across two
engines and pinned by a contract test, that nothing asks before writing the row the store will refuse.

## The decision

**Ruling 1 — `IngestService.record` refuses, before the append, an edge whose folded endpoint the
graph holds no node for.** It asks the store's own precondition one step earlier, through
`GraphStore.node`, which is on the port and answers identically on both engines. The refusal is a new
`UnknownEndpointException` naming the endpoint, the edge, and the repair that is correct *at that
moment* — record the node claim first. Nothing is appended.

**Ruling 2 — `SegueService.expandEntity` catches that refusal per assertion and reports it.** The
assertion is skipped, the expansion continues, and the refused endpoints are named in the
`ToolResult` detail alongside the existing reasons (ADR 27, ADR 56). **No field is added to
`ExpansionSummary`** — that record is the MCP wire shape, ADR 56 already refused to widen it for
attribution on the argument that the model reads the prose, and this is the same argument.

**Ruling 3 — the boot diagnosis is #228's ruling 4 and is not written twice.** #228 is adding, to
`GraphProjector.project`, a pre-replay refusal naming each offending sequence number, the id nothing
stands for, and the repair. That diagnosis covers this issue's row without modification, because a
poisoned log looks the same whichever producer wrote it. This issue adds no second diagnosis; the
plan's final task reconciles (below).

`GraphStore` is not widened, `TinkerGraphStore` and `JenaGraphStore` are untouched, and their throw
stays exactly where it is — as the last line of defence, which a store must keep whatever a producer
does.

### What each reading says afterwards

| reading | today | after |
|---|---|---|
| `ingest.record(edge)` with an unknown endpoint | `IllegalStateException: assertion references unknown entity Q0900102` | `UnknownEndpointException`, naming `Q0900102` and the edge |
| `log.readAll()` after that call | 2 rows | 1 row |
| `GraphProjector.project` over that log | throws at sequence 2 | boots, `applied 1` |
| `LogProjection.of(log).danglingEdges()` | `1` | `0` |
| `expandEntity` over a third-party edge | `IllegalStateException` escapes the facade | `PARTIAL`, naming the refused endpoint |
| `TinkerGraphStore.record` / `JenaGraphStore.record` | throws | unchanged |

### Which of the four homes this touches

None. This changes no fold rule: `Equivalences` gains no method, `Retractions` is untouched, and
`IngestService.apply` and `standIn` are not edited. `BothFoldsAgreeTest` and
`StandInAgreesInEveryHomeTest` therefore stand unchanged, and neither gains a case — there is nothing
here for two folds to disagree about, because the row the two folds would disagree about never
reaches the log.

`IngestService.record` is handed `Equivalences.NONE`, so the gate's fold is the identity fold, and
that is deliberate: the gate asks the same `foldEndpoints` call `apply` is about to make, so the pair
cannot drift about *which* endpoints must exist. The cost is one extra `foldEndpoints` per recorded
claim — on `NONE` that is a `namesARetractedStandIn` lookup against an empty set and two map misses.

### The alternatives, and why each lost

**Apply, then append — the issue's first option.** Rejected, and it is the most tempting of the three.

- It inverts ADR 24's stated ordering decision for *every* claim type in order to fix one. That
  decision has a consequence written into the ADR: *"a failure applying to the graph leaves the log
  ahead. That is the correct failure direction: a restart replays it right, whereas the reverse
  ordering would lose the claim permanently."* Under the reverse ordering, a crash between the two
  halves leaves the running graph holding an edge no log row supports: `find_paths` answers with a
  route that has no receipt, `get_entity` shows a neighbour the export cannot draw, and the next boot
  loses it with nothing saying so. That is ADR 19's "graph as a second source of truth" failure and
  the silent-data-loss shape #101 fixed once already.
- Under ruling 1 the same crash is harmless and stays harmless: the log is ahead by exactly one row,
  and that row has already been proved projectable.
- It also does not close the class of defect. An edge the live graph accepts today can still be
  refused by a *later* fold — #228 measures two such logs — so the boot diagnosis is needed either
  way, and reordering buys nothing the diagnosis does not already have to cover.
- The one thing it is genuinely better at: it needs no new check, and it cannot disagree with the
  store, because the store *is* the check. That is a real advantage and it is bought with a worse
  crash.

**Tolerate at boot — the issue's third option.** Rejected for the third time (#221, #224, and here).
It drops a claim nobody retracted, and it turns `danglingEdges` — the alarm this issue's census
reading depends on staying zero — into a number that would rise unwatched. ADR 44's own words:
`danglingEdges` exists to *report* a log that cannot boot.

**Put the check inside `GraphStore.record` and have it return a refusal instead of throwing.**
Rejected. It widens the port that exists to keep the engine choice reversible (ADR 18) — the same
widening ADR 41 refused for the exporter — and it would make every implementor responsible for a
return contract two of them currently express as an exception. It also does not help: the row is
already appended by the time `record` is called.

**Resolve both endpoints in `SegueService.expandEntity` instead of one.** Rejected as *the* answer,
and it is the fourth option this spec found rather than one the issue listed. It closes the one
reachable path measured above and it is a guard in front of one caller rather than a gate: `retract`,
a future dev tool, and any second facade would each need their own copy. #228 rejected the same shape
in its own words — *"A guard in front of one caller is not a gate."* Ruling 1 makes it unnecessary:
`expandEntity` learns about the bad edge from the refusal rather than from a second copy of the rule,
which is ruling 2.

**Make `record`'s refusal silent — log a warning and drop the claim.** Rejected: that is the
producer-side version of tolerating at boot, and the memory rule it violates is the one this issue
cites. A claim the system will not keep must be reported to whoever made it, which is what ruling 2
is for.

**Give the refusal a plain `IllegalStateException`, as the store does.** Rejected. `SegueService` has
to catch *this* condition and must not swallow a genuine store failure, an out-of-disk `AssertionLog`
or a programmer error; catching `IllegalStateException` around `ingest.record` would swallow all
three. A named type is the smallest thing that makes ruling 2 honest.

### Cost, stated rather than glossed

**One extra `GraphStore.node` lookup per endpoint per recorded edge.** On TinkerGraph that is an
indexed vertex lookup — `graph.createIndex(P_QID, Vertex.class)` in the constructor — which
`TinkerGraphStore.record` is about to perform anyway inside `requireVertex`, so the real cost is one
duplicate indexed read per endpoint on the happy path. No timing was taken: the real database may not
be read by this work, and a synthetic timing assertion is banned on a loaded machine.

**Replay is untouched.** `GraphProjector` does not call `record`; it calls `apply` directly, so boot
gains nothing and loses nothing from ruling 1.

### What the census reading means

#227's census reports **0 dangling edges** in the real graph today. That says three things and it is
worth separating them.

- **For urgency: there is nothing to repair, and that is the whole argument for doing it now.** The
  fix is a guard, not a migration. There is no reconciliation task, no data to inspect, and the
  boot diagnosis will never fire on the owner's database as it stands. After the fact the position
  reverses completely: ADR 19 forbids removing the row, appending the missing node claim does not
  help (measured above), and the only repair is to retract an entity the owner never meant to
  retract, losing every other edge that names it.
- **For the boot diagnosis: it cannot be validated against real data, and must not pretend to be.**
  Its only evidence is its positive control. #228 owns it, and this spec's contribution is the
  measured sentence it should carry — *retract the endpoint*, not *upsert the node first*.
- **For `danglingEdges`: it stays the detector, and 0 stays its expected reading.** Nothing here
  changes what it counts. If it ever reads non-zero, the census is reporting a log written by a build
  older than this fix, or a row written into SQLite by hand.

## What this does not settle

**A third-party edge is still resolved one endpoint at a time inside `expandEntity`.** Ruling 1 stops
it poisoning the log and ruling 2 reports it, but the *neighbour* whose identity the call never
fetched is still not fetched: such an edge is refused and skipped rather than completed. Completing
it means fetching both endpoints, which is a change to the expansion's round-trip budget and belongs
with the bounded virtual-thread fan-out that is already unbuilt. Filed as a finding here, not fixed.

**Nothing states that an adapter must name the seed at an end.** All three shipped adapters do, and
`ReverseClaims` even names `neighborOf` as its reason for skipping a self-loop, but `SourceAdapter`'s
contract says nothing about it and no test asks. After this work a source that breaks the convention
is refused loudly instead of silently poisoning the log, which is the point — but the convention is
still undocumented, and writing it into the SPI is a separate decision.

**A log written before this fix still dies at boot on the store's own message** until #228's ruling 4
lands. That is stated, not hidden: see the reconciliation below.

**There is no `--force`.** A caller determined to write an unbootable row can still write it into
SQLite by hand, which is why #228's boot diagnosis exists at all. A gate at the producer bounds the
mistake; it does not make the shape unrepresentable.

## Reconciling with #228

#228 (branch `228-ready`) is adding a producer gate at `IngestService.claim` for the owner's paths
and a named boot diagnosis at `GraphProjector.project`. The two issues must land as one shape, not
two. The plan's final task does the reconciliation, and it has two branches because the merge order
is not ours to choose.

**If #228 has landed on `main` first:** rebase this branch onto `main`; keep exactly one refusal type
and one message builder — whichever name `claim`'s gate took, `record`'s adopts — and delete the
duplicate; add the sourced-edge shape as a case to #228's boot-diagnosis test rather than writing a
second test file; and check that the diagnosis names *retract the endpoint* as the repair, correcting
it against the measurement above if it says *upsert the node first*.

**If #228 has not landed:** this work lands with `UnknownEndpointException` in `ingest`, next to both
gates, and **adds no boot diagnosis** — writing a second one is the failure both issues are trying to
avoid. The spec records that #228 adopts this type for its own refusal, and records as a stated
residual that until it lands, a log already carrying such a row still dies at boot on
`TinkerGraphStore`'s message rather than a named one.

Either way `record`'s gate and `claim`'s gate ask their question of **different projections, and that
is not drift**: `claim` has a log and no graph, so it asks the log's fold
(`Equivalences.nodesTheFoldHolds`); `record` has a graph and no log view, so it asks the running
graph. One question — *does the projection this claim is about hold a node for each folded endpoint*
— two projections, both stated.

**Note (2026-09-04, issue #233, Task 5): this landed first.** `git fetch` at reconciliation time found
no #228 commits on `origin/main` and no `Equivalences.nodesTheFoldHolds`, so this work takes the
second branch above: no boot diagnosis is added here. `UnknownEndpointException` in `ingest` is the
type #228's `claim` gate should adopt for its own refusal, so the two issues share one refusal shape
rather than minting a second. The stated residual holds until #228's boot diagnosis lands: a log
written by an older build — or a row written into SQLite by hand — still dies at boot on
`TinkerGraphStore`'s own message rather than a named one, and the repair it needs is
`./gradlew retractEntity` on the endpoint, not appending the missing node claim (replay is
positional; see ADR 24's 2026-09-04 amendment).

## Amendment

**ADR 24 takes a dated amendment; ADR 19 takes none.** Nothing about append-only changes: no row is
deleted, no row is edited, and the log stays the source of truth. What changes is the scope of one of
ADR 24's consequences.

The amendment says: the sentence *"a failure applying to the graph leaves the log ahead. That is the
correct failure direction: a restart replays it right"* is true only of a claim that can eventually
project. For a claim the graph refuses on a precondition replay will apply identically, the log being
ahead is not recoverable — the row is permanent under ADR 19 and every boot fails at it. The ordering
decision is unchanged and the argument for it is unchanged; what is added is that `record` asks the
store's own precondition **before** the append, so the log never gets ahead by a row that can never
catch up. The originals are untouched.
