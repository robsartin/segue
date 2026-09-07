# A dev tool expands the neighbourhood of every promotion

Issue #284. Written 2026-09-07 against `284-ready` at `4d5d53f` (HEAD = `main`). Everything below was
read from the code in this worktree; no real database was opened, and every identifier named here is
invented and carries ADR 58's leading zero.

## The premise the issue states, and what the code actually does

The issue's premise holds. Five places where it is looser than the code, or where the code says
something the issue does not, are recorded here rather than left for the implementer to find.

- **"Nothing on the dev side expands" is right.** `SegueService.expandEntity` is the only caller of
  `SourceAdapters.all()` in `src/main`, and `GraphTools.expandEntity` is its only caller. `seed`
  cannot open a store at all (`seedNeverOpensAStore`), and no other dev tool reaches `ingest` except
  `retract` and `own`, each of which appends one hand-typed claim.
- **The issue asks for "nodes and edges added, per source". The code can honestly supply one of the
  two.** Every `AssertionRecord` carries a `Provenance` whose `sourceId` says which adapter produced
  it, so edges tally per source exactly. A *node* does not: a neighbour's identity comes either from
  an adapter's own `neighbors()` or from `EntityResolver.fetch`, and the second has no adapter behind
  it at all — `MusicBrainzSourceAdapter.toNeighbour` even stamps its neighbour claims `"wikidata"`
  deliberately (ADR 61). ADR 56 already refused a per-adapter breakdown on the wire because it would
  "restate `id()` as a second, forgeable authority for who the source was". So this design tallies
  **edges** by source and reports **nodes added** as one number, and says why in the report's own
  section names.
- **The issue says the tool reads promotions "through the taste layer's note-free bulk read".** That
  is `AffinityStore.readRatings`, and the tool must resolve the result through the log's merges
  before applying the threshold — `RecommendCli` and `EvaluateCli` both do, and the reason is on
  record: a merge leaves two affinity rows naming one thing, so an unresolved read promotes both and
  the tool would expand the retired local id (which `expandEntity` then refuses) as well as the
  canonical one.
- **`ExpansionSummary` has no per-source fields and is not going to get any.** ADR 56 is explicit
  that `sourceUnavailable()` and `truncated()` stay aggregate and that attribution lives in
  `ToolResult.detail`. The shared class this design extracts carries the two source-id **lists** the
  existing code already builds; `SegueService` joins them into exactly the sentences it builds today,
  and the new tool counts them. The MCP wire shape does not move.
- **The default bound is stated three times in this repository, and this issue does not fix that.**
  `application.yaml` sets `segue.max-new-edges: 200`; `SegueProperties`' compact constructor falls
  back to a literal `200`; `ExpandContext.defaults()` returns `new ExpandContext(200)`. The issue
  says "Not this issue: changing any bound", and moving where a bound lives is close enough to that
  line to leave alone. The tool therefore takes `ExpandContext.defaults().maxNewEdges()` as its
  `--max-new-edges` default — the same value `expand_entity` resolves today, and the constant
  `MusicBrainzProbe.SHARED_BOUND` already treats as "the shipped bound". **The three-way restatement
  is a finding, reported and not repaired here.**

Two documentation sentences are falsified by this issue and are edited by it:

- `docs/user-guide.md:40` — "`add_entity` and `expand_entity` call the live Wikidata API and the
  Wikidata Query Service. **Nothing else does.**"
- `docs/developer-guide.md`, the supervised-run chapter, step 9 — "**There is no dev-side bridge
  tool, deliberately.** MusicBrainz is reached only by `expand_entity` running inside the server".

Neither is pinned by a test. Both are corrected in prose, and the correction is named as a task.

## What changes, in one sentence each

1. A new package `expansion` holds **`EntityExpansion`** — the body of `SegueService.expandEntity`,
   moved unchanged — and **`ExpansionOutcome`**, the sealed result it returns.
2. `SegueService.expandEntity` keeps its signature and every sentence it prints, and becomes a switch
   over that outcome. It builds its own `EntityExpansion` in its constructor, so nothing that
   constructs a `SegueService` changes.
3. `Expanded` gains **`edgesBySource`**, tallied from each recorded assertion's provenance.
4. **`ExpansionSources.both`** moves the two-adapter wiring out of `SegueConfiguration`, and
   **`WikidataMusicBrainzIdentity` moves from `app` to `expansion`** so a tool with no Spring can
   reach it. ADR 54 gains a dated amendment; ADR 32 is untouched.
5. A tenth dev-side tool, package **`expand`**: `ExpandCli`, `ExpandRun`, `Preflight`,
   `ExpansionTally`, `ExpansionReport`, run as `./gradlew expandPromotions`.
6. **Six new ArchUnit rules and two widenings**, each with a planted control.
7. A developer-guide runbook chapter, a `docs/adr/0066-…` recording the decision, and the two
   sentences above.

Nothing about the bounds, the adapters, the recommender, the harness or any constant moves. No MCP
tool is added; `ToolSurfaceTest` still counts six.

## The shared expansion

### Where it lives, and why not somewhere cheaper

`EntityExpansion` needs `EntityResolver`, `GraphStore`, `SourceAdapters` and `IngestService`. Two
callers need it: `mcp` and the new `expand`. Everything else must be barred from it, because
everything else is fenced *not* to write, and a class that runs the adapters and appends what they
return is the one object in the system that turns a read-only tool into a writer.

- **`ingest`** was the obvious home and is wrong. `own` and `retract` both depend on `ingest` for the
  static `claim`/`retract`, so putting the expansion there would hand two hand-typed-claim tools a
  route to the network and to a bulk write, while their own fences say in as many words that a claim
  about the owner's shelf is "a pure function of one local file". Their fences forbid `java.net` as a
  *package*; they do not forbid reaching a class that runs adapters.
- **`domain`** is barred by construction (it may hold no port).
- **`mcp`** is where it is today, and `expand`'s fence must ban `..mcp..` for the reason every
  sibling's does — a dev tool that can reach the tool layer can become an MCP tool by accident.
- **A new package `expansion`** is the answer: `mcp` may depend on it, `expand` may depend on it,
  `app` may depend on it because wiring is its job (ADR 32), and **one new rule bars everybody
  else** — including the census, the exporter and the harness, which is what the issue asks for.

The name pairs with the tool's package deliberately: `expansion` is what an expansion *is*, `expand`
is the tool that does many of them.

### `ExpansionOutcome`

```java
public sealed interface ExpansionOutcome {

  String qid();

  /** Why an expansion was refused before any adapter ran. */
  enum Reason {
    UNKNOWN_ENTITY,
    LOCAL_ENTITY,
    BOUND_NOT_POSITIVE
  }

  record Refused(String qid, Reason reason) implements ExpansionOutcome {}

  record Expanded(
      String qid,
      int nodesAdded,
      int edgesAdded,
      int skippedNeighbors,
      int effectiveMax,
      List<String> unavailableSources,
      List<String> truncatingSources,
      boolean boundCutTheConcatenation,
      List<String> refusedEndpoints,
      Map<String, Integer> edgesBySource)
      implements ExpansionOutcome {

    public boolean sourceUnavailable() { … }

    public boolean truncated() { … }
  }
}
```

- **`Refused` carries a reason and no sentence.** The sentence is the caller's: `SegueService` builds
  the three `error(…)` strings it builds today, word for word, and the tool renders a tally label.
  A shared sentence would be a wire string with two audiences, one of them a language model.
- **`truncated()` and `sourceUnavailable()` are derived, not stored.** ADR 56's ORing rule stops
  being spelled out at each caller. `sourceUnavailable()` is `!unavailableSources.isEmpty()`;
  `truncated()` is `!truncatingSources.isEmpty() || boundCutTheConcatenation`. Both get their own
  RED, both with the `boundCutTheConcatenation`-only case, which is the one an "any list non-empty"
  reading would get wrong.
- **`effectiveMax`** is what `ExpansionBounds.effective` returned. `SegueService`'s truncation
  sentence quotes it ("at the bound of N"), so it has to travel.
- **`refusedEndpoints` carries ids, and the tool prints only its size.** `SegueService` names them in
  `detail`, as it does today. The tool's guard is what proves it does not.
- Every list and map component is defensively copied in the compact constructor and handed back
  unmodifiable, `LinkedHashMap`/`List.copyOf` — the reason strings are order-sensitive and
  `Map.copyOf`'s iteration order is salted per JVM.

### The API

```java
public final class EntityExpansion {
  public EntityExpansion(
      EntityResolver resolver, GraphStore graph, IngestService ingest, SourceAdapters adapters) { … }

  public ExpansionOutcome expand(String qid, int maxNewEdges) { … }
}
```

The body is `SegueService.expandEntity`'s, unchanged: the local-entity refusal before the bound
check, `ExpansionBounds.effective`, one `ExpandContext` for every adapter, the concatenation bound,
the neighbour memo doing double duty as `skippedNeighbors`, issue #55's identity refresh, #233's
refused-endpoint collection, and the two `log.warn` diagnostics. Its logger moves with it and keeps
the message text, including the `expandEntity(…)` prefix — the messages are diagnostics an operator
greps, and renaming them is a second change wearing this one's clothes.

What does **not** move: `error(…)`, `ToolResult.ok/partial`, `withCorrelation`, the reason sentences,
and `ExpansionSummary`. Those are the tool-result shaping the issue says `SegueService` keeps.

### What `SegueService` becomes

```java
public ToolResult<ExpansionSummary> expandEntity(String qid, int maxNewEdges) {
  Objects.requireNonNull(qid, "qid");
  return switch (expansion.expand(qid, maxNewEdges)) {
    case ExpansionOutcome.Refused refused -> error(refusalSentence(refused));
    case ExpansionOutcome.Expanded expanded -> shape(expanded);
  };
}
```

`refusalSentence` is a switch over `Reason` producing the three strings that exist today; `shape`
builds the `ExpansionSummary`, assembles the reason list in today's order, and returns `ok` or
`partial` exactly as today. `expansion` is a fifth field, built in the constructor from the four
collaborators `SegueService` already holds — **no constructor signature changes**, which is what
keeps thirty-odd existing call sites and every `SegueServiceTest` fixture untouched.

### The Mikado order, and what proves nothing changed

`SegueServiceTest` carries over twenty `expandEntity` call sites, `GraphToolsTest` drives the tool wrapper, and
`AnExpansionAfterARetractionTest`, `SharedAwardRouteTest`, `CorroborationAcrossSourcesTest`,
`MusicBrainzNeighbourIdentityTest` and `NeighbourFetchCountTest` each drive it end to end. Those are
the characterisation harness, and the extraction is done **behind them**:

1. `ExpansionOutcome` lands first, with its own tests, used by nobody.
2. `EntityExpansion` lands as a stub that answers wrongly, its own test goes red on an assertion, and
   the body is then moved in from `SegueService` — with `SegueService` delegating **in the same
   commit**, because a copy of the body in two places is exactly the drift this issue exists to end.
3. The gate is green at that commit with **no test file edited**. That is the proof, and the task
   report has to say that no file under `src/test` changed in it.

Only then does `edgesBySource` arrive, as a new behaviour with its own red.

## Wiring two adapters without Spring, and the seam that has to move

`SegueConfiguration.sourceAdapters` builds one `WikidataClient.queryService()` and hands it to both
`WikidataSourceAdapter` and `new WikidataMusicBrainzIdentity(queryService)`. **The order is
load-bearing** — its own javadoc says so, and `CorroborationAcrossSourcesTest` pins it from both ends
— because one `ExpandContext` bounds the concatenation, so a tight budget is spent by whichever
adapter runs first.

A second entry point that built its own list would be a second statement of that order. So the
wiring becomes a plain-Java factory in `expansion`:

```java
public static SourceAdapters both(WikidataEntityResolver resolver, Clock clock)
```

and `SegueConfiguration`'s bean is one line calling it.

**The bridge cannot stay in `app`.** `WikidataMusicBrainzIdentity` is the only implementation of
`MusicBrainzIdentity` there is, and it lives in `app` because `musicbrainz` may not import `wikidata`
and `wikidata` may not import `musicbrainz` (`adaptersDoNotDependOnEachOther`, over every one of the
twenty ordered pairs since issue #140). Every dev tool's fence bans `..app..`, and rightly: `app` is
Spring, and through `SegueConfiguration` it reaches `mcp`. So the tool cannot reach the bridge where
it is, and the alternatives are worse:

- **Wire only Wikidata in the tool.** Rejected: MusicBrainz supports `PERSON` and `GROUP`, which is
  what a promotion overwhelmingly is, and the whole point of the issue is reach. It would also mean
  the two callers disagree about what an expansion is, which is the thing the shared class exists to
  prevent.
- **Let `expand` depend on `app`.** Rejected: it drags Spring into a plain-Java tool and gives it a
  route to `mcp`, which is the accident every sibling fence names.
- **A package whose only member is the bridge.** Rejected as a package with one class and no
  argument; `expansion` is already the package both entry points share.

So the bridge moves to `expansion`, with its "Why it lives in `app`" paragraph rewritten to say why
it lives here now. `adaptersDoNotDependOnEachOther` is untouched — `expansion` is not an adapter
package, so the slice rule never compared it — but that rule's javadoc gloss ("`app` is the only
package ADR 32 lets see two adapters at once") becomes false and is corrected in the same commit.

**ADR 32 is not amended.** Its text says `app` is the only package permitted to depend on
*everything*; `expansion` depends on two adapters and four other packages, which is not everything,
and ADR 32 itself says "`ArchitectureTest` is the list, not this table". **ADR 54 is amended**, dated
2026-09-07, naming ADR 66: its text places the bridge in `app` and gives a reason that a second
non-Spring caller changes. That is ADR 61's stated convention for a partial reversal — the older ADR
keeps `Accepted` and gains a dated amendment naming the newer one.

## The tool

### The command line

```
usage: --db <segue.db> [--max-new-edges <n>] [--dry-run]
```

- **`--db` is required**, through `RequiredDatabase.refusal(envDatabase, userHome)`, refused by
  `parse` before `Files.exists` is reached — the ordering `RetractCli`, `OwnCli`, `CensusCli` and
  `EvaluateCli` all keep, so a missing flag reads as a missing flag and not as a missing file. The
  tool never names `DefaultDatabase`, and never takes a `Path` out of `support`. It requires the flag
  on **ADR 60's central clause and its consequence together**: an agent's shell inherits `SEGUE_DB`
  from the owner's profile, and this tool writes.
- **`--max-new-edges <n>`** defaults to `ExpandContext.defaults().maxNewEdges()`, refused at or below
  zero at parse time so the per-entity `BOUND_NOT_POSITIVE` refusal cannot happen in a run.
- **`--dry-run`** reports what would be visited and touches no network and no log.
- A flag given twice is refused, `OwnCli`'s rule: last-wins is worst on the one flag whose value the
  operator reads back.

### What a promotion is

```java
List<String> promotions = KnownList.promoted(List.of(), ratings);
```

`KnownList.promoted` with an empty file is exactly "everything rated at or above
`KnownList.PROMOTION_RATING`, sorted ascending by qid" — the threshold and the sort in one call,
from the class that owns both. Writing the filter out here would be a second copy of the threshold
rule, and this repository has been bitten by that shape twice (issues #106 and #109). There is no
`--known` flag: the concert-history file is a list of entities, not of judgements, and this tool
expands what the owner rated.

`ratings` is `merges.resolve(affinity.readRatings())`, with `merges` taken from `replay.fold()` —
never re-derived. That is ADR 64 and issue #246, and the fence is
`theReplayingToolsTakeTheBootsFold`, widened to `com.robsartin.segue.expand..`.

### The run

One `SqliteAssertionLog`, one `SqliteAffinityStore`, one `TinkerGraphStore`, one
`GraphProjector.replay(assertions, graph, IdentityMerge.NONE)` — `EvaluateCli`'s shape exactly.
`IdentityMerge.NONE` and not `carryingRatings`: an expansion never records a `SameAs`, so there is
nothing for a merge hook to carry, and `carryingRatings` writes the taste layer, which this tool's
fence forbids outright. One `IngestService(assertions, graph, IdentityMerge.NONE)`, one
`ExpansionSources.both(resolver, clock)`, one `EntityExpansion`.

Then, for each promotion in qid order:

- `expansion.expand(qid, maxNewEdges)`, one at a time, single-threaded.
- `Refused` → `refusalsByReason[reason]++`.
- `Expanded` → the counts add; `addedNothing++` when `nodesAdded == 0 && edgesAdded == 0`;
  `unavailableBySource` and `truncatedBySource` take one increment per source id named;
  `boundCutTheConcatenation` and the `refusedEndpoints.size()` add.
- A `RuntimeException` out of `expand` → `failed++`, one `log.warn` naming **no qid**, and the loop
  continues.

**Nothing is retried, and nothing is skipped.** A refused endpoint, an unreachable source and a
truncation are all *reported outcomes* of an expansion that completed; the entity is counted once and
the run moves on. That is the same choice `expandEntity` already makes inside one call — "rather than
aborting a 30-round-trip expansion after some assertions are already committed" — applied one level
up. Retrying would need a policy for how many times, against sources whose own clients already retry
with backoff, and it would make a run's cost unpredictable in the one dimension the owner is planning
around.

**The `RuntimeException` catch is the tool's, not the shared class's.** `SegueService.expandEntity`
wraps `adapter.expand` in no `try`, and a `RuntimeException` from an adapter escapes it today. For a
single interactive call that is right — the MCP layer turns it into a protocol error. For a run over
hundreds of entities it is not: one bad row would abort a run that had already written most of what
it came for. Catching in `ExpandRun`'s loop leaves the MCP path byte for byte unchanged and gets its
own red, driven by a stub adapter that throws.

**Rate limits are the adapters', and the tool adds none of its own.** `MusicBrainzClient` reserves a
slot at least `DEFAULT_MIN_REQUEST_INTERVAL` (one second) after the last, proactively, before
sending; `WikidataClient` backs off on `Retry-After` up to `MAX_BACKOFF`. Both are honoured for free
by a sequential loop **provided every entity shares one client instance**, because the reservation is
per instance — which is what `ExpansionSources.both` guarantees by constructing each client once for
the whole run. There is no sleep in this tool and therefore **no wall-clock assertion anywhere**.

### The dry run

`ExpandRun.dryRun` returns `Preflight(int considered, int inTheGraph, int minted)`:

- `considered` — the promotions.
- `inTheGraph` — those the projection holds a node for. The rest are what `UNKNOWN_ENTITY` would
  refuse; a rating survives its entity's retraction, so this number is genuinely smaller.
- `minted` — those `LocalEntity.isLocal` answers true for, which `LOCAL_ENTITY` would refuse.

All three are read off the replayed graph and the resolved ratings. **No adapter is asked anything**,
and a test proves it with a stub adapter that fails the test if it is called, plus an assertion that
the log holds the same number of rows after the dry run as before.

## What may be printed, and what may not

**No line this tool writes carries a qid, and none carries a label.** That is stronger than the
issue's "never a label", and the reason is the run's shape rather than the field's sensitivity: a
progress line per entity, over every promotion, in qid order, is the owner's whole promoted
population enumerated down a terminal. ADR 39 refused a bulk `list_affinity` because it "would put
the whole taste layer in front of a model"; printing it to a scrollback an agent shares, or an owner
pastes, is the same disclosure by another route. A qid on a refusal line would be genuinely useful
and is not worth that; `--dry-run` answers the same question in aggregate, and `listRatings` answers
it precisely, offline, for the owner alone.

So a progress line is a position and counts:

```
[  17/ 431] 24 edge(s), 3 new node(s)
[  18/ 431] refused: LOCAL_ENTITY
[  19/ 431] partial: 1 source unavailable, 2 endpoint(s) refused
```

and the pasteable block is aggregates, in `CensusReport`'s shape — a header line, section headings,
counted lines indented two spaces, labels padded to one width and counts right-aligned in another:

```
# segue promotion expansion — aggregates only: no labels, no notes, no entity ids (ADR 51, ADR 63).

promotions
  considered           431
  expanded             428
  added nothing         57
  refused                3
  failed                 0

graph
  nodes added         2914
  edges added        11703

edges by source
  wikidata            9880
  musicbrainz         1823

shortfalls
  neighbours skipped   142
  endpoints refused     11
  bound cut the result   4
  unavailable
    musicbrainz          2
  truncated
    wikidata            37

refused, by reason
  unknown entity         2
  local entity           1
```

Every number above is invented for this document; the report is the authority on the labels and their
order, and no figure from a real run is written into any committed file.

**`nodes added` sits under `graph` and not under `edges by source`**, and the section names carry the
reason: a node's discovery has no single source to attribute it to (see the premise section), so the
report never implies one.

### The guard, and the one carve-out

`ExpansionIsSafeToPasteTest` is `CensusIsSafeToPasteTest`'s shape: a `ListAppender` on the root logger
at `TRACE` so sqlite-jdbc's own statement logging is captured, a scratch `@TempDir` database carrying
a label, a note, a `Q` id inside that note and a rating, a real `ExpandCli.main`, and then

1. **the block was actually printed** — `ExpansionReport.HEADER` present and some line starting
   `  considered` — without which every clause below is vacuous;
2. no line carries the label;
3. no line carries the note;
4. no line carries anything matching `\bQ\d+\b` — **except** a line whose logger is
   `com.robsartin.segue.expansion.EntityExpansion`.

**That carve-out is one logger wide, and it is an honest limit rather than a convenience.**
`EntityExpansion` inherits `SegueService`'s two diagnostic warnings, which name a neighbour and a
refused edge, and it emits them identically inside the MCP server. They are the shared expansion's
diagnostics, not this tool's report. The alternative — moving the warnings out to the callers — would
change what the server logs and is a second change; it is named in ADR 66's consequences as the
follow-up that would close the carve-out, and not done here. What the ADR claims is precise: **the
block is safe to paste, and so is every line this tool writes.**

The carve-out gets its own unit test against planted strings, exactly as the census's does: a
`  considered  Q0900901` line from the tool's own logger fires; a qid on an `EntityExpansion` line
does not; a second qid smuggled onto a line whose logger merely *contains* the name fires.

**Planted control for the guard:** append an invented qid to the `promotions` section heading in
`ExpansionReport`, run the test, watch clause 4 fire naming that line, remove the plant, watch it
pass. The plant has to be in the report and not in a log call, because the report is what the clause
exists to hold.

**Type-level fence, ADR 65's:** `ExpansionReport.lines` takes an `ExpansionTally` whose components
are `int`s, a `Map<String, Integer>` keyed by `SourceAdapter.id()` and a `Map<Reason, Integer>`. There
is nowhere in that signature to put an identifier, which is a stronger guarantee than a body that
happens not to print one.

## The fences

Six new rules and two widenings. Every one of them gets a planted control in the plan — a rule nobody
has watched go red is an inert fence, which is issues #139 and #140 in one sentence.

| rule | forbids | plant |
| --- | --- | --- |
| `onlyTheClientAndTheExpanderExpandAnEntity` | any class outside `..mcp..`, `..expand..`, `..app..`, `..expansion..` depending on `..expansion..` | a field of type `EntityExpansion` in `census.Census` |
| `theExpanderWritesThroughIngestAlone` | `..expand..` applying a claim (`GraphStore.record`/`upsertNode`, `AssertionLog.append`) or calling `AffinityStore.put`/`updateRating` | a `graph.upsertNode(…)` call in `ExpandRun` |
| `theExpanderReadsScoresAndNeverNotes` | `..expand..` depending on `AffinityRecord`, or calling `AffinityStore.find`/`readAll` | an `AffinityRecord` field in `ExpandCli` |
| `theExpanderOpensNothingElse` | `..expand..` depending on any other dev tool (`otherDevToolsAnd(List.of("expand"))`), `..mcp..` or `..app..` | an import of `census.CensusRun` in `ExpandRun` |
| `theExpanderHasNoDefaultDatabase` | `..expand..` depending on `DefaultDatabase` | a `DefaultDatabase.resolve(…)` call in `ExpandCli` |
| `theExpanderTakesItsDatabaseFromTheFlagAlone` | `..expand..` taking a `Path` out of `support` (`A_PATH_TAKEN_OUT_OF_SUPPORT`) | a `Path`-returning method added to `RequiredDatabase` and called from `ExpandCli` |
| `onlyTheRecommenderReadsEveryRating` **widened** | `readRatings` outside `recommend`, `rate`, `census`, `evaluate`, **`expand`** | remove `"..expand.."` from the list |
| `theReplayingToolsTakeTheBootsFold` **widened** | the seven log-taking statics and `Fold.of` in `recommend`, `rate`, `evaluate`, **`expand`** | an `Equivalences.in(logged)` call in `ExpandCli` |

Notes on the shape of each, because the choices are decisions:

- **`theExpanderOpensNothingElse` does not ban `java.net`, `tinker`, `sqlite`, `ingest`, `wikidata`
  or `musicbrainz`, and that is the whole point.** Every sibling fence bans a network because
  "a decision about your own graph is a pure function of one local file". This tool's decision is
  not: it exists to fetch. The fence that remains is the one that matters — it may not reach a
  sibling tool, the tool layer, or Spring.
- **It holds a `GraphStore`, unlike `own` and `retract`.** Their fences name `GraphStore` as a type
  because neither has a running graph to apply a claim to. This one does: an expansion reads the
  graph to decide what is new, and `IngestService.record` needs the projection the replay built. So
  the type is permitted and the two *write* calls are forbidden at the package as well as globally.
- **`AffinityStore` is not banned as a type**, unlike `own`'s fence — the tool reads `readRatings`.
  What `theExpanderReadsScoresAndNeverNotes` bans instead is the three routes a note could take,
  copied from `theRecommenderReadsRatingsAndNeverNotes`.
- **Widening rather than a new rule, twice, and each for its own reason.**
  `onlyTheRecommenderReadsEveryRating` has been widened three times already (#101, #227, #239); its
  javadoc says what it protects is ADR 26's six tools, and a fifth dev-side reader of a
  `Map<String, Integer>` does not touch that. `theReplayingToolsTakeTheBootsFold` is named for a
  *class* of tools rather than for one, already covers three packages, and ADR 64's amendment
  explicitly added `evaluate` to it because "a fence that skipped it would be green over a third copy
  of the defect". A fourth replaying tool is the fourth instance of one property, not a second
  property. Contrast `theExpanderHasNoDefaultDatabase`, which is a **new** rule rather than a
  widening of ADR 60's pair, for ADR 63's and ADR 65's stated reason: a rule named for one tool and
  quoted in an immutable ADR does not get stretched to cover another.

Adding `"expand"` to `DEV_TOOL_PACKAGES` bans it from every sibling's fence in the same move, which
is the whole value of the derived list.

## What the run costs, and the arithmetic

`P` is the number of promotions — the count `graphCensus`'s taste section reports at or above
`KnownList.PROMOTION_RATING`, resolved through merges. No figure for it is written into any committed
file: the census is the authority, and a number copied here would be a second one going stale.

Per promotion:

- **MusicBrainz**: one `artistRelations` request for a `PERSON` or `GROUP` seed that bridges to an
  MBID, paced by `MusicBrainzClient.DEFAULT_MIN_REQUEST_INTERVAL` — **one second, enforced before
  sending**. Every other kind costs nothing here, because `MusicBrainzSourceAdapter.supports` answers
  false and the adapter is skipped.
- **Wikidata**: two calls for the Wikidata adapter (the entity, then the reverse-claims query), two
  for the bridge (`mbidFor`, then one batched `identitiesFor` per hundred neighbours), and one
  `EntityResolver.fetch` per neighbour no source described. None of these is proactively throttled;
  each costs a round trip, and a 429 costs `Retry-After` up to `MAX_BACKOFF`.

So the floor is **`P` seconds of MusicBrainz pacing**, and the realistic cost is
**`P × (1 s + (4 + u) × t)`**, where `t` is a Wikidata round trip and `u` is the number of neighbours
per expansion that neither adapter could describe. At a `P` in the hundreds that is tens of minutes,
not seconds and not hours — which is why the tool is supervised, why `--dry-run` comes first, and why
the progress line reports a position.

**A second run is not free and is not harmful.** `ProvenanceCodec.append` drops a provenance whose
`sourceId` and `sourceRef` it already holds, so the graph converges and corroboration does not
inflate; the log, being append-only, grows by every assertion the run recorded again. Re-running is
the owner's decision and costs the same wall clock.

## The runbook, and what a first run's census should show

A new developer-guide chapter, `## Expanding every promotion`, in the shape of `## A supervised first
run`: a preamble saying the owner types every command and that an agent reading it is reading a
description rather than a script (ADR 60), then numbered steps, each prose then one bash fence, dry
run before the write, `$HOME` and never `~`.

```
### 0. Quit the client, and confirm nothing is holding the database
### 1. The census before
### 2. The dry run
### 3. The run
### 4. The census after
### 5. What should have moved, and what should not
### What to file from what you saw
```

Pinned by `DeveloperGuideExpandPromotionsExamplesTest` in `com.robsartin.segue.expand`: every
`expandPromotions` example in the whole guide parses through `ExpandCli.parse`, none carries a tilde,
none is unreadable, and **within the chapter** the merged `expandPromotions` and `graphCensus`
examples, sorted by guide line, read exactly

```
graphCensus, expandPromotions --dry-run, expandPromotions, graphCensus
```

so a step written out of order, or a real run shown before its dry run, reds the build.

**Step 5 writes no figures**, for the supervised chapter's stated reason — a table of expected counts
is a second source of truth about the owner's graph, going stale on its own. It writes which lines
move and in which direction:

| line | direction | why |
| --- | --- | --- |
| `nodes` by kind | up | every neighbour no claim described before is a new node |
| `edges` by type | up | the assertions the adapters returned |
| `edges` by source | up, **and `musicbrainz` up for the first time in bulk** | the second source has only ever been reached one interactive call at a time |
| `edges` by corroboration | up at two sources | where both sources state one relationship |
| `claims` / log rows | up by at least the edges added | every recorded assertion is a row (ADR 19) |
| `degree` quantiles | up | the promotions are the seeds, so the low quantiles move most |
| `bridge` / entities MusicBrainz reached | up | one bridge lookup per `PERSON` or `GROUP` promotion |
| `concept classes` | may move | new nodes arrive whose classes `KindMapper` may not yet place |
| `taste` by score | **unchanged** | this tool writes no rating, and its fence forbids one |
| `claims` / withdrawn | **unchanged** | nothing is retracted |

The last two are the ones worth checking hardest: they are what a fence being wrong would show up as.

## The reading afterwards, and why it is a separate issue

The measure of this issue is the next evaluation reading, and **this plan does not take it and does
not write the issue that does.** What the follow-up issue has to carry, so that whoever writes it does
not have to re-derive it:

- The reading is judged by **#245's rule, unchanged** — the rate `hits / in pool`, the fifteen-point
  margin, the void clause, at most one constant moving.
- **The denominator will have moved, and that is the point rather than a problem.** `in pool` counts
  the held-out entities the pool reaches at a floor, and expanding every promotion is precisely an
  attempt to raise it. So a reading taken after the run is judged by the rule *within itself*, and is
  **not row-for-row comparable** with the seven readings before it — the same thing ADR 65's fold
  amendment said of its own change, for the same reason.
- ADR 65's consequence is what the run tests: "reading a low hit rate as a verdict on the scorer
  would be reading it as a verdict on ingest." If `in pool` rises and `hits` rise with it, the misses
  were ingest's. If `in pool` rises and `hits` do not, they were not, and the question moves back to
  the ranking with one confound removed.
- The outcome is a dated amendment to ADR 45, quoting the table once and restating no figure from it
  in prose.

## What this issue does not do

- **It does not run the tool.** The implementer never runs `expandPromotions`, `own`, `ownClaim`,
  `retractEntity` or `rate`, and never reads, writes, copies or creates `~/.segue/segue.db`. Every
  database in the plan is a `@TempDir` file or an in-memory `TinkerGraphStore`.
- **It changes no bound and no constant.** Not `ExpansionBounds.CONCEPT_CEILING`, not
  `KnownList.PROMOTION_RATING`, not `ExpandContext.defaults()`, not `application.yaml`'s
  `max-new-edges`, not the adapters' intervals or backoffs.
- **It changes no adapter, no recommender and no harness.** `MusicBrainzSourceAdapter`,
  `WikidataSourceAdapter`, `ReverseClaims`, `CandidateSweep` and `evaluate` are read and not edited.
  `WikidataMusicBrainzIdentity` **moves** and is not otherwise changed.
- **It adds no MCP tool.** ADR 26's six stand; `ToolSurfaceTest` is untouched.
- **It expands nothing but promotions.** No `--known` file, no whole-known-list mode, no `--qid`.
- **It adds no resume file and no ledger.** `seed` has one because it is resolving thousands of names
  against a fuzzy matcher; this walks a list the ratings table regenerates in a millisecond, and a
  half-finished run is re-runnable at the cost of re-recording assertions the graph already merges.
  If the run turns out to want resuming, that is an issue with a measurement behind it.
- **It does not close the safe-to-paste carve-out.** Named in ADR 66's consequences, not done.
- **It does not repair the three-way restatement of the default bound.** Reported as a finding.

## Alternatives considered

- **Drive `expand_entity` from the client in a loop.** The cheapest thing that could work, and it is
  what the owner has today. Rejected on three counts. The caller is a language model, so a run of
  several hundred calls costs a context window and produces a transcript rather than a summary; the
  list it would iterate is the owner's promotions, so the loop puts the whole taste layer into that
  transcript, which is exactly the bulk read ADR 39 refused; and there is no aggregate at the end,
  because each call answers about itself. ADR 40 rejected `import_list` for the same three reasons in
  the same order.
- **A flag on `seed`.** Rejected outright. ADR 40's entire safety argument is that `seed` "resolves
  and reports; it never writes", held by `seedNeverOpensAStore`, which forbids it from depending on
  `sqlite`, `tinker`, `jena`, `ingest`, `mcp` or `app` — so it cannot open the database even to read
  it. Adding a writing mode would not widen that fence, it would delete it, and it would put a bulk
  writer inside the one dev tool whose committed input is a private list.
- **Expand the whole known list rather than the promotions.** More reach per run, and rejected on
  ADR 65's finding: the `--known` file is a concert history and carries no strength on any row (ADR
  48), so it names entities the owner attended rather than entities he judged. The promotions are
  the population the recommender weights by and the population the harness holds out from, so they
  are the population whose neighbourhoods change what either one can say. Nothing stops a later issue
  widening it, with the census's own before-and-after to argue from.
- **Expand recursively, or expand what the first pass discovered.** Rejected as unbounded: ADR 49
  bounds one call and says in as many words that "it bounds one call, and calls are not counted".
  A depth-two run over a few hundred seeds is tens of thousands of expansions, which is a different
  decision needing a different cost argument.
- **Put the shared expansion in `ingest`.** Rejected above: it hands `own` and `retract` a route to
  the network and to a bulk write past fences that say they have neither.
- **Leave the expansion in `SegueService` and have the tool call `SegueService`.** Rejected: the tool
  would depend on `..mcp..`, which every sibling fence bans and for the reason all of them give, and
  it would drag `ToolResult`, `CorrelationId` and the view types into a tool that wants counts.
- **A per-adapter breakdown on `ExpansionSummary`.** Rejected by ADR 56 already, and not reopened:
  the MCP wire shape does not move for a dev tool's convenience. The structured lists live on
  `ExpansionOutcome`, which is below the wire.
- **Print a qid per entity.** Rejected above, on the enumeration argument rather than on the field.
- **Retry a failed entity at the end of the run.** Rejected: it needs a retry policy against clients
  that already retry, it makes the run's cost unpredictable, and re-running the whole tool is
  idempotent in the graph. The counts say how many would be worth a second run.
- **Parallelise the expansions.** Rejected: `MusicBrainzClient`'s slot reservation is what makes the
  pace correct, and the honest way to go faster is the bounded virtual-thread neighbour fan-out
  `SegueService`'s javadoc already names as a follow-up, inside one expansion rather than across
  many.
