# The stand-in rule has four homes and nothing fails if one drifts

Issue #220. Written 2026-09-03, against `main` at `2e01341`.

## What the four homes actually are, read off the code

ADR 59's amendment names them and says *nothing fails if one drifts*. Read in the code they are:

| home | what it reads | what it returns | reachable from a test? |
|---|---|---|---|
| `Equivalences.standIns` | the log | `Map<String, NodeRecord>` — kind **and** label | `public static`, directly |
| `IngestService.standIn` | the running graph | a `GraphStore.upsertNode` | `private`, but the live path is public: `new IngestService(log, graph, IdentityMerge.NONE).record(claim)` per row — and see the third correction below, that is the **only** path on which it does anything |
| `OwnRun.labelsInTheProjection` | the log | `Map<String, String>` — label only | **`private static`**; nothing public returns the map |
| `ratings/Labels.forQids` | the log | `Map<String, String>` — label only | package-private `static` in a package-private class, so reachable from a test in `com.robsartin.segue.ratings` |

Four corrections to the issue's framing, all of them load-bearing for the design:

1. **They do not all carry the same condition, and one of them carries none at all.**
   `IngestService.standIn` asks `graph.node(canonical).isEmpty()`; `OwnRun` and `Labels` ask
   `!labels.containsKey(canonicalQid)`. `Equivalences.standIns` asks **nothing** — it is
   `putIfAbsent` keyed by canonical id and emits an entry whether or not a source has claimed that
   id. Its own javadoc says both things in two paragraphs: *"All four agree today, condition for
   condition"* in the four-homes paragraph, and *"Offered whether or not a source has named the
   canonical entity … Applied first, the same guarantee comes free from the other direction"* three
   paragraphs above. The second is the true one. So a guard that compared the four raw seams would
   red on `main`, on a difference that is the design.
2. **`Labels.forQids` is `ratings/Labels.forQids`** — the issue drops the package and the file is
   easy to miss; it is `src/main/java/com/robsartin/segue/ratings/Labels.java`.
3. **`IngestService.standIn` does nothing at all on the replay path.** `GraphProjector.project`
   upserts every `Equivalences.standIns` entry into the store *before* its loop begins, so by the
   time `apply` reaches a `SameAs` the canonical node exists and `graph.node(canonical).isEmpty()`
   is false. Every merge with a local side is covered by the pre-pass; every merge without one is
   refused by `standIn`'s first guard. So `standIn` upserts **only** on the live
   `IngestService.record` path — which is what its own javadoc says (*"this one is the live path's
   copy"*) and what ADR 59's residual says (*"over the running graph, live path only"*). It is not
   an alternative probe: it is the only one.
4. **`KindMapper` never needs to reach `domain`, and the rule that would stop it is not the one the
   ADR names.** The comparison below happens at the projection level and in the test source set, so
   no `src/main` import changes and no ArchUnit rule moves (ArchUnit imports with
   `ImportOption.DoNotIncludeTests`, so the test's own imports are outside every rule). For the
   record, the rule a `domain` → `wikidata` import fires first is
   `ArchitectureTest.domainHasNoThirdPartyDependencies` — `..domain..` may depend only on
   `..domain..`, `java..` and `javax..`, so not even on `port`. `noPackageCycles`, which ADR 59's
   residual and `Equivalences.localsOfMerges`'s javadoc both name, would fire as well, because
   `wikidata` depends on `domain`; it is the second rule to fire, not the first.

A fifth observation, outside this issue's question but worth recording: **the live path never
re-derives a node kind.** `GraphProjector.rederived` and `LogProjection`'s
`KindMapper.rederive(claim)` re-derive a `NodeAssertion`'s kind from its `P31` classes;
`IngestService.record` → `apply` → `node.toNode()` does not. Combined with correction 3, that means
the live copy of the stand-in rule reads a graph in which nothing has been re-derived. Today no
producer makes a claim whose stated kind disagrees with its own classes, so nothing is wrong — but
it is why this guard compares **canonical ids only** and never the merged local node itself, and it
is what #222 will have to decide about (see the last section).

## What the guard asserts

**One question, asked of all four: for each canonical id a surviving merge names, what does the
projection you build hold for it — the label, and the kind where you expose one?**

- **Labels** are compared across all four homes.
- **Kinds are compared only where a home exposes one** — the two that do are `Equivalences.standIns`
  (via the fold) and the live `IngestService` graph. `OwnRun.labelsInTheProjection` and
  `ratings/Labels.forQids` return `Map<String, String>` and are label-only homes by design; asking
  them for a kind would be asking them to answer a question they were built not to ask (`Labels`'s
  javadoc: *"The kind is deliberately not re-derived … this is a list of names"*). The guard says so
  in its own javadoc rather than leaving the reader to infer it from two homes being missing.
- **`Equivalences.standIns` is additionally read raw**, before any overlay, and pinned separately.
  That is the correction from point 1 above: its answer for a canonical id a source has claimed is
  the *owner's* working title, and the fold then overwrites it. Without the raw reading, a change
  to `standIns` that only affected already-claimed canonical ids would be invisible behind the
  overlay.

**This is a drift guard, not a correctness claim.** Every expected value below is *what the four
do today*, recorded so that one of them moving alone reds. Two of the pinned values are behaviours
ADR 59 lists as residuals and issues #221 and #222 exist to change; pinning them is not agreeing
with them.

### The seams, and the one that has to be widened

Three of the four need no production change:

- `Equivalences.standIns(logged)` — called directly.
- `Equivalences.standIns` **as its callers see it** — `LogProjection.of(log).nodes()`, the exporter
  fold, which seeds itself from `standIns` and then lets the log's own claims land on top.
  `GraphProjector.project` is the other caller and `BothFoldsAgreeTest` already holds the two to one
  graph, so covering one covers both; using the record-returning one keeps this guard free of a
  second `GraphStore`.
- `IngestService.standIn` — drive the live path: `new IngestService(new FakeAssertionLog(), graph,
  IdentityMerge.NONE)` and `record` each row in order, then read `graph.node(canonical)`. Correction
  3 above is why there is no choice here: replaying through `GraphProjector.project` would exercise
  the pre-pass and never `standIn` itself, so it would be home 1 wearing home 2's name.
- `ratings/Labels.forQids` — package-private, so a public one-line probe class in the
  `com.robsartin.segue.ratings` **test** package reaches it. No production change.

`OwnRun.labelsInTheProjection` is `private`. Two ways to reach it:

- **Drive `OwnRun.run` with a dry-run `OwnCli.Assert` per canonical id and parse the label out of
  the operator note.** No production change at all. **Rejected**: it puts a prose parser in front of
  a guard, which is the shape this repository keeps finding (`DocumentationLinksTest`'s own javadoc:
  a lenient matcher turns *"cannot read this"* into *"there is nothing here"*), it couples the guard
  to a human-readable message that is free to change for unrelated reasons, and it can only probe
  one id per invocation.
- **Widen `private static` to package-private `static`, with a javadoc paragraph saying why**, and
  reach it from a public one-line probe class in the `com.robsartin.segue.own` test package. No
  behaviour changes, no `src/main` caller is added, no ArchUnit rule moves. **Chosen**, as the
  minimum seam.

### The fixture

One log, no edges and no retractions, built from `InventedGraph`'s helpers (`minted`, `node`,
`merged`, `FakeAssertionLog`) with its own ids so that `InventedGraph.java` — a file #221 and #222
may also touch — is not edited.

- **No edges.** The stand-in rule is about nodes; edges are `BothFoldsAgreeTest`'s question, and an
  edge on the live path would also meet `TinkerGraphStore.record`'s unknown-endpoint refusal for
  reasons that have nothing to do with stand-ins.
- **No retractions.** `IngestService.record` refuses a `Retraction` by contract and the live graph
  has no way to un-apply one (ADR 24), so a retraction in this fixture would make the live home
  disagree for a reason that is not drift. Retraction of a merge is `Equivalences.in`'s own tested
  rule.

| # | row | what it is for |
|---|---|---|
| 1 | `minted(Q0011, WORK, "the April tape")` | a `LocalEntity`-side merge |
| 2 | `merged(Q0011, Q10000900201)` | |
| 3 | `node(Q0012, WORK, "a signal a source named", ["Q5"])` | the **bypass** side: a plain `NodeAssertion` about a local-shaped id, **carrying classes** that re-derive to `PERSON` while the claim says `WORK` (ADR 59's first residual, issue #222) |
| 4 | `merged(Q0012, Q10000900202)` | |
| 5 | `minted(Q0013, WORK, "the ledger, twice over")` | **one local merged twice** (ADR 59's fourth residual, issue #221) |
| 6 | `merged(Q0013, Q10000900203)` | the first canonical id — the orphan stand-in |
| 7 | `merged(Q0013, Q10000900204)` | the last canonical id — where the edges would land |
| 8 | `minted(Q0014, WORK, "the owner's working title")` | the canonical side **already claimed** |
| 9 | `node(Q10000900205, GROUP, "the name the source already had")` | claimed **before** the merge |
| 10 | `merged(Q0014, Q10000900205)` | |
| 11 | `minted(Q0015, WORK, "the owner's other working title")` | the canonical side claimed **after** the merge |
| 12 | `merged(Q0015, Q10000900206)` | |
| 13 | `node(Q10000900206, GROUP, "the name the source brought later")` | last claim wins over the stand-in |
| 14 | `minted(Q0016, WORK, "the second working title")` | **two locals onto one canonical** |
| 15 | `merged(Q0016, Q10000900201)` | the first merge must keep the name |

Rows 8–10 and 14–15 are what give the guard teeth: without a canonical id something else claims,
the condition three of the four homes carry never fires, and a home that lost it would stay green.

Ids: locals take ADR 59's `Q00…` shape and canonical sides take ADR 62's eleven-digit shape, so
none of them is allocatable and `StandInQidsDenoteNothingTest` sweeps past them. The one allocatable
id in the file is `"Q5"` in row 3's classes, and it needs one new `code(...)` site on `Q5`'s
allowlist entry (issue #216's key is (id, file, context)).

### The pinned answers, one row per canonical id

`stand-in` is `Equivalences.standIns` read raw; `shown` is what all four homes hold once the log's
own claims have landed.

| canonical | stand-in (raw) | shown (all four) | why |
|---|---|---|---|
| `Q10000900201` | `WORK "the April tape"` | same | the first merge onto an id names it; row 15's later merge does not |
| `Q10000900202` | `WORK "a signal a source named"` | same | the **claimed** kind, not `PERSON` — ADR 59's first residual, in both homes that expose a kind |
| `Q10000900203` | `WORK "the ledger, twice over"` | same | the orphan stand-in under the **first** canonical id — ADR 59's fourth residual |
| `Q10000900204` | `WORK "the ledger, twice over"` | same | and under the last one, where the edges go |
| `Q10000900205` | `WORK "the owner's working title"` | `GROUP "the name the source already had"` | **the two disagree, by design**: the pre-pass is unconditional and the source's claim lands on top |
| `Q10000900206` | `WORK "the owner's other working title"` | `GROUP "the name the source brought later"` | the same, with the claim arriving after the merge — last claim wins in all four |

Row `Q10000900202` is worth reading twice. `LogProjection` re-derives the *local* node `Q0012` to
`PERSON` and the live graph leaves it `WORK`; the *stand-in* is `WORK` in both, because
`Equivalences.localsOfMerges` copies `claim.toNode()` and the live `standIn` copies the live node.
So the two kind-exposing homes agree today, and they agree on the value ADR 59 calls a lag.

### The failure message

Per canonical id, each home's answer is described as `KIND "label"`, `"label"` (label-only homes) or
`no node`, and every disagreeing **pair** is emitted as its own line:

```
Q10000900205: Equivalences.standIns (via LogProjection.of) says GROUP "the name the source already had", ratings/Labels.forQids says "the owner's working title"
```

The assertion is that the list of such lines is empty, so a failure names every pair that
disagrees and shows both answers — not "expected X but was Y" with no home attached.

**Vacuity.** Four homes that all answered nothing would agree perfectly, so the label test also
asserts that the number of non-null answers is `4 ×` the number of rows the table says are present,
and the kind test `2 ×`; the pinned-answer test asserts the values themselves.

### The three tests

1. `shouldAgreeOnEveryCanonicalLabelWhenAllFourHomesReadOneLog` — the pairwise label comparison.
2. `shouldAgreeOnEveryCanonicalKindWhenBothHomesThatExposeAKindReadOneLog` — the same for the two
   homes that expose a kind.
3. `shouldHoldTodaysStandInAnswerWhenTheFixtureIsRead` — the pin: the raw `standIns` map and the
   folded projection, row by row, plus `standIns`'s key set in log order.

## Positive controls

Each is planted in `src/main`, observed red on the named assertion, and reverted before any commit.

| # | plant | must red |
|---|---|---|
| 1 | `ratings/Labels.forQids`: drop `!labels.containsKey(merge.canonicalQid())` so a later merge wins — the issue's own "prefer the last merge" | test 1, naming `ratings/Labels.forQids` against each of the other three, at `Q10000900205` (the source's name overwritten by the owner's) and `Q10000900201` (row 15's merge overwriting row 2's) |
| 2 | `IngestService.standIn`: pass `NodeKind.CONCEPT` instead of `minted.get().kind()` | test 2, naming `IngestService.standIn (live record)` against `Equivalences.standIns (via LogProjection.of)`, on four of the six ids — not `Q10000900205` (its node already exists in the log before the merge, so `standIn` no-ops) or `Q10000900206` (its node claim arrives after the merge and overwrites the plant's kind) |
| 3 | `Equivalences.standIns`: `put` instead of `putIfAbsent` | test 3, on `Q10000900201`'s stand-in row (`"the second working title"` for `"the April tape"`); test 1 reds too, which is the guard agreeing with itself |
| 4 | `OwnRun.labelsInTheProjection`: drop its `!labels.containsKey(merge.canonicalQid())` | test 1, naming `OwnRun.labelsInTheProjection` — the control that proves the fourth home is really wired in and not just imported |

Control 2 is what proves the kind comparison is not vacuous, and control 4 is the one that must be
run *after* the fourth home is added, not before.

## What this does not do

- **It does not unify the four.** Out of scope by the issue, and the ADR's reason stands.
- **It does not close ADR 59's residual.** The residual is *"the stand-in rule has four homes"*, and
  after this it still has four. What changes is the clause *"nothing fails if one drifts"* —
  something does now. **No ADR amendment**: no decision changed, and the residual list stays exactly
  as written. The javadoc sentence that carried the same claim (`Equivalences.standIns`: *"nothing
  holds them to it but this paragraph"*) is corrected in place, because it is javadoc and not a
  decision record. Whichever of #221 or #222 lands first will amend ADR 59 for its own residual and
  can record the guard there.
- **It says nothing about edges, ratings or retraction.** `BothFoldsAgreeTest`,
  `MergeDoesNotInflateDegreeTest` and `MergeCarriesEverythingTest` are those.

## Rejected

- **Unify the four into one caller.** The obvious fix, and the one the issue puts out of scope.
  It stays out: `OwnRun` and `Labels` read *labels off a log* and never build a graph — `Labels`'s
  javadoc makes that the reason `ratings` can carry the tightest fence of the three dev tools — so a
  shared caller would either hand them a `NodeRecord` map they must destructure or hand
  `Equivalences` a label-only mode. That is a design decision with an ADR behind it, not a test.
- **Test the homes pairwise: `standIns` vs `Labels`, `standIns` vs `OwnRun`, and so on.** Cheaper to
  write and each failure names its pair for free. **Rejected** because three pairwise tests over
  four homes is itself a copy that can drift: a fixture row added to one pair and not the others
  gives exactly the coverage gap this issue is about, one level up. One fixture, one loop, every
  pair.
- **Compare only the raw seams.** `Equivalences.standIns` against the other three, map to map.
  **Rejected** on point 1 above: it reds on `main` for a difference that is the design, and the only
  way to make it green is to encode the "unless something claimed it" rule *in the test*, which
  makes the test a fifth home of the rule.
- **Put the guard in a new test package with its own fixture helpers.** Tidier boundary.
  **Rejected**: `InventedGraph` is package-private in `com.robsartin.segue.export`, so a new package
  means copying `FakeAssertionLog`, `minted`, `node` and `merged` — four more copies, in the issue
  about copies. The guard sits beside `BothFoldsAgreeTest`, which is not about export either.
- **Reach `OwnRun` through its CLI note.** Recorded above with its reason.
- **Assert kinds for all four homes by re-deriving in the test.** Would need the test to decide what
  `OwnRun` and `Labels` "would have said" about a kind. That is inventing an answer and comparing
  against it.

## Running beside #221 and #222

Both are `ready` and both change `Equivalences.standIns`'s behaviour. The fixture and the pinned
table are shaped so that each is a one-row edit here:

- **#221** (a local merged twice) changes what `Q10000900203` holds. Option 1 (the orphan goes) makes
  the row `Pinned(FIRST, null, null, null, null)`; the two counting assertions and the key-set
  assertion derive their expectations from the table, so nothing else moves. If #221 changes only
  `Equivalences.standIns` and not `IngestService.standIn`, this guard reds naming that pair — which
  is the guard working, and #221 owns the decision.
- **#222** (the bypass kind) changes `Q10000900202`'s kind from `WORK` to `PERSON`. Its plan gives
  `Equivalences.localsOfMerges` and `standIns` a **required** `UnaryOperator<NodeAssertion>` that
  each fold passes as `KindMapper::rederive`, keeping `KindMapper` out of `domain`. Two edits land
  here, both mechanical: `Q10000900202`'s `standInKind` and `shownKind` in the pinned table, and the
  guard's **one** call site of `Equivalences.standIns(logged)` in test 3, which gains the same
  argument the folds pass. The pinned table is deliberately the only place a kind is written down,
  so there is no second copy to find.

  **What #222's plan and this spec disagree about, and it is worth settling before either lands.**
  #222 records that `IngestService.standIn` "already has that shape (it receives the re-derived
  node)". That is true of the *replay* path — and correction 3 above says `standIn` never upserts on
  the replay path. On the live `record` path, which is the only one where it does upsert, nothing
  re-derives (correction 5), so after #222 the live home would still copy the **claimed** kind while
  the fold copied the re-derived one. If that is how it lands, test 2 reds naming
  `IngestService.standIn (live record)` against `Equivalences.standIns (via LogProjection.of)`.
  **That red is this guard working**, and #222 owns the decision it forces: either feed the live
  path the same re-derive function, or record the split as a new residual. It is not a one-line
  edit here, and pretending it were would be the guard agreeing with a change it was built to
  notice.

The one file both may also touch is `Equivalences.java`. This work edits exactly one javadoc
paragraph there (the four-homes paragraph) and no code, so a conflict is a paragraph merge.
`InventedGraph.java` is deliberately not touched.
