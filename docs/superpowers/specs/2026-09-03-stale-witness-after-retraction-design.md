# The gate asks the running graph, and a retraction has not reached it

Issue #234. Written 2026-09-04, against `main` at `a79c6ca` — the commit that landed #233's
pre-append gate, so everything below is measured *with* that gate, #221's surviving-edge widening
and #224's withdrawal rule already in place, and *without* #228, which is in flight on branch
`228-ready`.

Every reading below came from throwaway probes under `src/test`, driving a real `SqliteAssertionLog`
(a `@TempDir` file, so the row survives the process that wrote it), a real `TinkerGraphStore`,
`IngestService.record`, `IngestService.retract`, `GraphProjector.project`, `Retractions.in` and
`Equivalences.folding`. One probe was run a second time against a copy of branch `228-ready`, taken
read-only into a scratch directory, to see what that branch's boot pre-flight says about the same
log. The probes were deleted; this worktree is clean. No real graph was read for any of it (ADR 51):
every id is invented and carries ADR 58's leading zero — `WREN = Q0900101`, `KETTLES = Q0900102` —
and the one timing below was taken on a synthetic log built row by row in a temp file.

## The defect, measured

```
seq 1  node(WREN)
seq 2  node(KETTLES)
seq 3  retract(KETTLES)
seq 4  edge(WREN → KETTLES, "INFLUENCED_BY")
```

The live call, then two consecutive boots over the file it left behind:

```
PROBE record() ACCEPTED the edge naming the retracted Q0900102
PROBE log rows = 4
PROBE graph still holds retracted node? true
PROBE BOOT THREW: replay failed at sequence 4
  | cause: java.lang.IllegalStateException: assertion references unknown entity Q0900102
           - upsert the node first
PROBE SECOND BOOT THREW: replay failed at sequence 4
  | cause: java.lang.IllegalStateException: assertion references unknown entity Q0900102
           - upsert the node first
```

That is the issue's own quotation, reproduced: `record() ACCEPTED …`, `BOOT THREW: replay failed at
sequence 4`. **Nothing in the issue's description of the code is wrong.** One thing it worried about
is already fixed: it says ADR 24's #233 amendment "says it scopes *the only case the original
sentence did not cover*", and asks for that to be corrected before #233 merged. It was — the merged
text reads *"That closes one of two cases the original sentence did not cover, not both"* and names
this issue.

### What each witness says about the retracted id

| witness | answer for `KETTLES` | so the gate … |
|---|---|---|
| `GraphStore.node` — what `record` asks (#233) | present | accepts |
| `Retractions.in(log)` | `{Q0900102=2}` | — |
| `Equivalences.folding(log).retractedStandIns` | `[]` — #224's rule is about a merge's canonical id, and no merge is involved | does not withdraw |
| `Equivalences.nodesTheFoldHolds(log)` (on `228-ready`) | `[Q0900101]` — the retracted id is absent | would refuse |

The running graph is the only witness that says the node is there, and ADR 44 says why it does:
`GraphStore` cannot remove anything, so a retraction is honoured by the fold at the next boot and by
nothing before it.

## Is the supported flow reachable? Yes — and only in two writers

The issue says the flow reaches it "whenever an expansion after a retraction returns an edge naming
what was retracted, which is likely". That is right, and it is narrower and stranger than it looks.

**Two sub-paths through `SegueService.expandEntity`, and only one of them poisons the log.**

- **Expanding the retracted entity itself.** The stale graph still holds its node, so `get_entity`
  and `search_entities` still show it and `expand_entity` accepts it as a seed. Every edge the
  expansion returns names it at one end, no node claim for the seed is written along the way — the
  seed is never a *neighbour* — and every one of those edges is appended. This is the worst case
  and the easiest to reach: the operator retracts a QID, sees it still in the graph, and expands it.
- **Expanding a neighbour.** Here it depends on which half of the Wikidata adapter produced the
  edge. `WikidataSourceAdapter.expand` fills `ExpandResult.neighbors()` from the **reverse** pass
  alone (ADR 36); the forward pass, `ClaimMapper.map`, carries no identity. For a reverse-pass edge
  the neighbour's identity rides along, `SegueService` re-records it unconditionally (issue #55),
  that node claim lands *after* the retraction and survives it — **so the log repairs itself by
  accident and the boot is fine**. For a forward-pass edge nothing describes the neighbour, and
  because the stale graph holds a node for it `isNew` is false, so no fetch is made either: the edge
  is appended alone, and the boot dies.

**Both need two writers on one log, which ADR 24 says it does not support.** No retraction can be
appended from inside the server: `ToolSurfaceTest.retractIsNotATool` keeps it off the MCP surface,
and `IngestService.retract`'s only production caller is `RetractRun`, a dev-side tool that requires
`--db` (ADR 60) and therefore runs in its own JVM. So the running graph can only be stale about a
retraction another process wrote — and ADR 24's own consequence says *"One writer is assumed.
Concurrent writers would need revisiting."* Restart the server after retracting and before ingesting
again, and the defect is unreachable: the rebuilt graph holds no node for the retracted id, and
#233's gate refuses the edge correctly.

`RetractRun` currently *describes* that two-writer mode neutrally, in its closing line: *"a server
that is up still holds the old edges until it restarts"*. It does not say what happens if you go on
ingesting into it.

## The cost of asking the fold, measured

The gate would have to ask the log, and `AssertionLog` offers one read: `readAll()`. On a synthetic
log of 131,000 node claims in a temp SQLite file — about the size ADR 44 records for the real one:

```
PROBE readAll(131000) = 407 ms
PROBE fold passes (standIns + Retractions.in + Equivalences.folding) = 116 ms
```

`segue.expand.max-new-edges` defaults to **200**, and an expansion calls `record` once per edge and
once more per neighbour whose identity a source volunteered — up to about 400 calls in one
`expand_entity`. At ~520 ms each that is **roughly three and a half minutes of log re-reading added
to a single tool call**, growing linearly with the log for ever after. The fixed point #228 adds is
the cheap half of that number; `readAll` is the expensive one, and no arrangement of the fold rules
avoids it.

## The decision

**Recommended: option 4, sharpened. The gate goes on asking the running graph; the residual is
closed by decision, by the boot, and by the tool's own words — not by a per-claim fold.**

Concretely, three things and no new rule in `domain`:

1. **The behaviour is pinned end to end**, at both the `record` level and through
   `SegueService.expandEntity`, together with the repair, so that nothing about it can change
   silently and the next reader does not have to re-derive it from three ADRs.
2. **`RetractRun`'s closing note becomes an instruction rather than an observation**: it names the
   consequence of ingesting into the still-running server and says to restart it first. That is
   free, it is at the only place the two-writer window is ever opened, and it is the one guard the
   producer can afford.
3. **A dated amendment to ADR 24**, which is where the residual was filed by #233's own amendment
   and therefore where the ruling belongs. **ADR 44 gets no amendment**: its *"a running server is
   stale until it restarts"* consequence is unchanged and still correct, and mirroring the ruling
   into a second document is how two documents come to disagree.

### Why each of the other three lost

**1. The gate asks the fold (`Equivalences.nodesTheFoldHolds` over the log), instead of or as well
as the running graph.** This is the *correct* witness — it is exactly the set of ids the boot will
hold — and it is the one #228 promotes for the same question on the owner path. It loses on
measurement, not on principle: ~520 ms per `record`, ~400 `record` calls per expansion, three and a
half minutes added to one tool call and growing with the log. #228 can afford the same call because
its caller is a dev tool that appends one row per invocation; `record` is the live per-neighbour
path, and that difference is the whole of the trade. A cached witness does not rescue it: everything
the server appends, the server already knows about, so the only thing a cache would have to see is
precisely the row another process wrote.

**2. `retract` updates the running graph so the live witness is not stale.** ADR 44's reasons are
unchanged and still hold — `GraphStore` cannot remove anything, widening the port that keeps the
engine choice reversible (ADR 18) is what ADR 41 already refused for a dev tool, and
`theRetractionToolOpensNothingElse` forbids that tool a `GraphStore` **as a type**, deliberately, so
that satisfying a constructor could never become the reason it held one. There is now a third reason
the ADR does not give, and it is decisive on its own: **the tool is a different process from the
server.** It requires `--db` (ADR 60) and runs under `./gradlew retractEntity`. Even a widened port
and a graph-holding tool would update a `TinkerGraph` in the wrong JVM and leave the server's own
exactly as stale.

**3. The gate asks the graph AND checks `Retractions.in(log)` for the endpoints.** The issue calls
this "a cheaper positional check … no fold needed". It is **not correct as stated**, and it is
barely cheaper. Not correct, because a retraction reaches backwards only and ADR 44's documented way
back in is to add and expand the entity again — measured on
`[node(WREN), node(KETTLES), retract(KETTLES), node(KETTLES), edge(WREN → KETTLES)]`:

```
PROBE-B re-add BOOT OK applied=3 edges=1
```

A gate that refuses because *a* retraction names the endpoint would refuse that edge, which is a
legal claim about a legally re-added entity, and would break ADR 44's question 4 from the producer
side. The correct positional question is "does the fold hold a node for this id **now**", which
means comparing the id's last retraction against its last surviving node claim — and that is
`nodesTheFoldHolds` under another name. So option 3 either loses on correctness or collapses into
option 1. Barely cheaper, because the fixed point it skips is 116 ms of the 523; `readAll` is the
other 407 and is unavoidable through the port as it stands. Widening `AssertionLog` with a narrow
indexed read was considered and refused for a fourth reason: it would put the fold's own rule into
SQL, outside `domain`, giving "does a node exist for this id" a second home — the one shape ADR 42,
ADR 44 and #228 have each spent an issue removing.

### What the boot says, and what the repair costs

Both halves of option 4's safety net were run rather than assumed.

**On `main` at `a79c6ca`** the boot throws `replay failed at sequence 4`, wrapping the store's
`assertion references unknown entity Q0900102 - upsert the node first` — which names the id and
neither the cause nor a repair that works. (*"Upsert the node first"* is wrong once the edge is in
the log: replay is positional, so a later node claim leaves the boot failing at the same sequence.)

**On branch `228-ready`, the same log, the same probe:**

```
PROBE BOOT THREW: replay refused: 1 row(s) name an entity no node stands for.
  sequence 4: Q0900101 INFLUENCED_BY Q0900102 names Q0900102, which no node stands for
  Nothing is deleted (ADR 19). To repair: retract the endpoint, which withdraws the edge under
  ADR 44 without deleting anything. Appending a node claim for the named id does NOT repair it —
  replay is positional, so a claim later than the row leaves the boot failing at that same
  sequence. A merge whose local side the projection does hold repairs it too, because the stand-in
  it builds is created before replay begins. See ADR 44, ADR 59 and issue #228.
PROBE nodesTheFoldHolds = [Q0900101]
```

**Confirmed: #228's boot pre-flight already catches a log of this shape, names the row and names the
repair**, with no change of its own. It reaches this case for free because the check is a property
of the log rather than of the third layer: the edge survives the retraction (it was appended after
it), the fold yields it (no merge withdraws it), and `nodesTheFoldHolds` does not contain the
endpoint.

**And the repair it names works, measured on the same log:**

```
PROBE-B poisoned BOOT THREW: replay failed at sequence 4
PROBE-B after SECOND retraction BOOT OK applied=1 edges=0 rows=5
```

One more `retractEntity` on the same id, and the boot is green with every row still in the log. It
is not refused as *"nothing about … is in the projection"* either, which is the obvious thing to
worry about: `RetractRun.measure` counts what **survives**, and the poisoned edge does — it was
appended after the first retraction. It reports `0 node claim(s) and 1 edge claim(s) will stop
projecting` under the label `(no node claim in the projection)`, which is the truth about an entity
whose only surviving claim is an edge.

## Consequences, stated

- **A row of this shape is still writable**, and that is the decision rather than an oversight. The
  operator who opens the window is told to close it at the moment he opens it; the boot names the
  row and the repair if he does not; and the repair is one command that deletes nothing.
- **The census has never seen one.** Measured on 2026-09-04 for issue #228: the real log holds 0
  retractions. This is a shape that has never occurred, reachable only outside ADR 24's stated
  single-writer assumption, and the cheapest correct answer is the one that argues for.
- **`record`'s witness is still the running graph**, so its message — *"the graph holds no node
  for"* — stays accurate, and `UnknownEndpointException`'s two-witness note stands as written.
- **`SegueService.expandEntity` reports nothing unusual** for the poisoning expansion: the edge is
  accepted, so it is counted in `edgesAdded` and no refusal reason is built. That is consistent —
  nothing refused it — and it is why the boot diagnosis is the thing that has to be good.

## What this does not settle

- **The owner path is not this issue.** #228 gates `IngestService.claim` against the fold, and can
  afford to.
- **Two writers on one SQLite file remains an assumption rather than an enforcement.** Nothing stops
  `retractEntity` running against a live server's database, and nothing detects it. Enforcing it —
  a lock file, a refusal, a "the server appears to be running" warning — is a bigger decision than
  this issue, and it would reach `own` and `seed` too.
- **A row already written by a build older than #228** still gets the store's own message rather
  than the named refusal. Nothing here changes that; #228 does.
