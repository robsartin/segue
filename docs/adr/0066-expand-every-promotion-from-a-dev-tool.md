---
status: Accepted
date: "2026-09-07"
topic: expand-every-promotion-from-a-dev-tool
tags: [project, tooling, privacy, data, graph]
supersedes: []
related: [assertion-log-source-of-truth, sqlite-assertion-log, mcp-tool-surface, layering-and-archunit, taste-layer-separation, affinity-capture-and-read, bulk-seeding-as-a-dev-tool, retraction-as-a-new-claim, a-kind-scoped-ceiling-on-concept-expansion, what-an-adr-may-quote, musicbrainz-as-the-second-source, what-the-musicbrainz-adapter-refuses, attribute-a-shortfall-to-its-source, owner-claims-as-a-third-layer, the-claim-tools-require-an-explicit-database, the-bridge-returns-classes, a-read-only-census-of-the-graph, fold-the-log-once-per-boot, an-offline-evaluation-harness-for-the-recommender]
---
# 66. Expand every promotion from a tenth dev-side tool, through the expansion the MCP tool already runs

## Context

Nothing on the dev side expands. `SegueService.expandEntity` was the only caller of
`SourceAdapters.all()` in `src/main`, and `GraphTools.expandEntity` was its only caller — so every
edge in the graph that a source had to be asked for arrived through one interactive MCP call at a
time. `seed` cannot open a store at all ([ADR 40](0040-bulk-seeding-as-a-dev-tool.md),
`seedNeverOpensAStore`), and the only other dev tools that reach `ingest` are `retract` and `own`,
each of which appends exactly one hand-typed claim
([ADR 44](0044-retraction-as-a-new-claim.md), [ADR 59](0059-owner-claims-as-a-third-layer.md)).

That is a limit on what every downstream decision can see.
[ADR 48](0048-a-high-rating-counts-as-something-you-have.md) records that what bounds the deck is
expansion coverage rather than any rule in the code — only a minority of nodes are the subject of a
stored edge, so a promotion reorders the candidate pool far more than it grows it.
[ADR 65](0065-an-offline-evaluation-harness-for-the-recommender.md)'s consequence puts the same
point as a warning about its own instrument: *"reading a low hit rate as a verdict on the scorer
would be reading it as a verdict on ingest."* The harness cannot separate the two, and neither can
anybody reading it, while the entities the owner has judged most highly have never been expanded in
bulk. **The counts behind both sentences are readings of the owner's database and live in
`graphCensus`'s output ([ADR 63](0063-a-read-only-census-of-the-graph.md)) and in those ADRs, not
here.**

[ADR 54](0054-musicbrainz-as-the-second-source.md) sharpens it. The second source has only ever been
reached one interactive call at a time, because the identity bridge that makes it usable lives in
`app`, which every dev tool's fence bans.

So the missing thing is a supervised batch: run the expansion the client already runs, over the
population the recommender weights by, once, and report what it did. The population is not a new
idea either — `KnownList.promoted` already composes it, and this tool reads it.

## Decision

**A tenth dev-side tool, `./gradlew expandPromotions --args="--db <segue.db>"`, expands every entity
the owner rated at or above `KnownList.PROMOTION_RATING`, one at a time, through the same expansion
the MCP tool runs, and prints one block of aggregates.** It changes no bound, no constant and no line
of `expand_entity`'s output; it adds no MCP tool, and `ToolSurfaceTest` still counts six
([ADR 26](0026-mcp-tool-surface.md)).

### One expansion, two callers

The body of `SegueService.expandEntity` moved, unchanged, into
`com.robsartin.segue.expansion.EntityExpansion`, which returns facts — `ExpansionOutcome`, a sealed
interface of `Refused(qid, Reason)` and `Expanded(…)` — rather than sentences. `SegueService` became
a switch over that outcome and keeps its signature, its three `error(…)` strings, its reason list
and its `ok`/`partial` shaping, word for word. The extraction landed with `SegueService` delegating
in the same commit and **no file under `src/test` edited**, which is what says nothing changed: the
twenty-odd `expandEntity` call sites in `SegueServiceTest`, `GraphToolsTest`, and the five end-to-end
tests that drive it were the characterisation harness, and they were not touched to make it pass.

`ExpansionOutcome` carries facts precisely so each caller words them for its own reader — one for a
language model, one for a terminal. A shared sentence would be a wire string with two audiences, one
of them a model.

**The package is new, and every other package is barred from it.** `EntityExpansion` runs every
adapter that supports the seed's kind and appends what they return through `IngestService`: it is a
bulk write and a network connection in one object, so a package that can reach it gains both past
whatever its own fence says. `onlyTheClientAndTheExpanderExpandAnEntity` therefore permits `mcp`,
`expand`, `app` — wiring is its job ([ADR 32](0032-layering-and-archunit.md)) — and `expansion`
itself, and nothing else. `ingest` was the obvious home and is wrong: `own` and `retract` both depend
on it, so the expansion living there would hand two hand-typed-claim tools a route to a bulk write
and to the network, past fences whose own text says a claim about the owner's shelf is a pure
function of one local file.

**The `RuntimeException` catch belongs to the tool and not to the shared class.** `EntityExpansion`
wraps `adapter.expand` in no `try`, which is right for one interactive call — the MCP layer turns a
throw into a protocol error — and wrong for a batch that has already written most of what it came
for. `ExpandRun`'s loop catches, counts the entity as failed, and carries on. Nothing is retried: a
refused endpoint, an unreachable source and a truncation are all reported outcomes of an expansion
that completed, exactly as `EntityExpansion` already treats them one level down.

### Why the seed tool's "never writes" does not extend here

[ADR 40](0040-bulk-seeding-as-a-dev-tool.md)'s safety argument is three claims stacked, and **only
the first survives the move**.

1. **`IngestService` is the only write path** ([ADR 19](0019-assertion-log-source-of-truth.md)).
   Honoured rather than bent: this tool appends through `IngestService` and nothing else, and
   `theExpanderWritesThroughIngestAlone` fails the build if any class in `expand` calls
   `GraphStore.record`, `GraphStore.upsertNode` or `AssertionLog.append` — or either taste-layer
   write.
2. **`add_entity` owns adding an entity.** It still does. This tool adds no entity nobody asked for;
   it expands entities the owner has already rated, and the neighbours an expansion discovers arrive
   the same way they arrive through `expand_entity`.
3. **A fence makes writing impossible.** This one is different, because the job is different.
   `seedNeverOpensAStore` denies `seed` `sqlite`, `tinker`, `jena`, `ingest`, `mcp` and `app`, so it
   cannot open the database even to read it. This tool must open it, replay it and append to it.
   What ADR 40 was protecting — a committed tool that reads a private list must not be able to touch
   the database — is protected here by two other things: the write fence above, which permits exactly
   one path, and the guard on the output, which is what a private list would otherwise leak through.

### It reads scores, and cannot read a note

The promotions come from `AffinityStore.readRatings`, the note-free bulk read, resolved through the
boot's merges before the threshold is applied — a merge leaves two affinity rows naming one thing,
and promoting both would expand the id the owner retired as well as the one he kept. The threshold
and the sort are `KnownList.promoted`'s, called with an empty file, rather than a second copy of the
rule here; this repository has been bitten by that shape before
([ADR 48](0048-a-high-rating-counts-as-something-you-have.md)).

`theExpanderReadsScoresAndNeverNotes` bans `AffinityRecord` as a type and bans `AffinityStore.find`
and `readAll` as calls, which are the three routes a note could take — copied from
`theRecommenderReadsRatingsAndNeverNotes` ([ADR 33](0033-taste-layer-separation.md)).
`onlyTheRecommenderReadsEveryRating` is widened to admit `expand` rather than given a fifth copy:
nothing varies between the readers, and ADR 26's six-tool surface, which is what that rule protects,
is untouched by a fifth dev-side reader of a `Map<String, Integer>`.

There is no `--known` flag. The concert-history file is a list of entities, not of judgements
([ADR 48](0048-a-high-rating-counts-as-something-you-have.md)), and this tool expands what the owner
rated.

### It folds once

One `GraphProjector.replay`, and the equivalences taken back from the `Replay` it returns rather than
re-derived from the log — [ADR 64](0064-fold-the-log-once-per-boot.md), held by
`theReplayingToolsTakeTheBootsFold` widened to a fourth package. A widening rather than a new rule,
for ADR 64's own stated reason: a fence that skipped the newest replaying tool would be green over
another copy of the defect, and a fourth instance of one property is not a second property.

### The command line, and the database it will not guess

```
usage: --db <segue.db> [--max-new-edges <n>] [--dry-run]
```

**`--db` is required and `SEGUE_DB` does not satisfy it**, refused by the parser before any file is
opened, with the path it would have resolved to quoted back. That is
[ADR 60](0060-the-claim-tools-require-an-explicit-database.md)'s central clause and its consequence
together: an agent's shell inherits `SEGUE_DB` from the owner's profile, and this tool writes. Two
rules hold it — `theExpanderHasNoDefaultDatabase` forbids the name `support.DefaultDatabase`, and
`theExpanderTakesItsDatabaseFromTheFlagAlone` forbids taking a `java.nio.file.Path` out of `support`
at all, which is the capability where the first is the name. Both are **new rules rather than
widenings of ADR 60's pair**, for ADR 63's and ADR 65's stated reason: a rule named for one tool and
quoted in an immutable ADR does not get stretched to cover another.

`--max-new-edges` defaults to `ExpandContext.defaults().maxNewEdges()` and is refused at or below
zero at parse time, so the per-entity `BOUND_NOT_POSITIVE` refusal cannot happen inside a run.
`--dry-run` counts what a real run would visit — the promotions, those the projection holds a node
for, and those `LocalEntity.isLocal` answers true for — and asks no adapter anything and appends
nothing. A flag given twice is refused, `OwnCli`'s rule.

`theExpanderOpensNothingElse` bans every sibling dev tool, `mcp` and `app`. It deliberately does
**not** ban `java.net`, `tinker`, `sqlite`, `ingest`, `wikidata` or `musicbrainz`. `rate` is the only
other dev-tool fence that leaves `java.net` open, and for the opposite reason — it serves on
loopback and never fetches, where this one fetches and never serves. Every other sibling fence bans
a network because each of those tools is a pure function of one local file, and this one is the
batch form of `expand_entity` and exists to fetch. It holds a `GraphStore`, unlike `own` and
`retract`, because an expansion reads the graph to decide what is new and `IngestService.record`
needs the projection the replay built — so the type is permitted and the write calls are forbidden.

### What it costs

`P` is the number of promotions. **No figure for it is written into any committed file**: it is
derived from `graphCensus`'s taste section, which reports how many ratings sit at each of the five
values, by summing the buckets at or above `KnownList.PROMOTION_RATING`. That sum is an upper bound
rather than the number itself, because the census reports the raw table while this tool resolves the
ratings through the merges first, and a merged pair collapses to one promotion.

Per promotion:

- **MusicBrainz**: one artist-relations request for a `PERSON` or `GROUP` seed that bridges to an
  MBID — the two kinds `MusicBrainzSourceAdapter` describes — paced by
  `MusicBrainzClient.DEFAULT_MIN_REQUEST_INTERVAL`, reserved **before** sending rather than in
  response to a rejection. Every other kind costs nothing, because the adapter is skipped.
- **Wikidata**: two calls for the adapter (the entity, then the reverse-claims query,
  [ADR 36](0036-reverse-lookup-via-sparql.md)), **plus two more for the bridge (the seed's MBID,
  then one batched lookup per hundred neighbours) when the seed is a `PERSON` or `GROUP` that
  reaches MusicBrainz** — the same gate the MusicBrainz bullet above describes — and one
  `EntityResolver.fetch` for each neighbour no source described. None is proactively throttled; each
  costs a round trip, and a rejection costs a backoff.

So the floor is **`P` × the MusicBrainz interval**, and the realistic cost is
**`P × (one interval + (4 + u) × t)`** for a `PERSON` or `GROUP` promotion that reaches MusicBrainz;
every other promotion pays only **`P × (2 + u) × t`**, where `t` is a Wikidata round trip and `u` is
the number of neighbours per expansion neither adapter could describe. At a `P` in the hundreds that
is tens of minutes — which is why the tool is supervised, why the dry run comes first, and why every
progress line reports a position.

**There is no sleep in this tool and no wall-clock assertion anywhere in its tests.** The pacing is
the adapters' own, and it is correct only because every entity shares one client instance: the
reservation is per instance, and `ExpansionSources.both` is what constructs each client once for the
whole run. That is also why the expansions are not parallelised.

### The output contract

**Every value the tool prints is an integer, and every label is a literal in `ExpansionReport`.**
The block is a header, then `promotions` (considered, expanded, added nothing, refused, failed),
`graph` (nodes added, edges added), `edges by source`, `shortfalls` (neighbours skipped, endpoints
refused, bound cut the result, then `unavailable` and `truncated` per source id) and `refused, by
reason`. An empty section still prints its heading, so "no edges from any source" is distinguishable
from a section that vanished.

**`nodes added` sits under `graph` and not under `edges by source`**, and the section names carry the
reason. Every `AssertionRecord` carries a `Provenance` whose `sourceId` says which adapter produced
it, so edges tally per source exactly. A node does not: a neighbour's identity comes either from an
adapter's own neighbours or from `EntityResolver.fetch`, and the second has no adapter behind it.
[ADR 56](0056-attribute-a-shortfall-to-its-source.md) already refused to restate `id()` as a second,
forgeable authority for who a source was, and this report does not either.

**No line the tool writes carries a qid, a label, a note or a rating.** That is stronger than
"aggregates only", and the reason is the run's shape rather than any field's sensitivity: a progress
line per entity, over every promotion, in qid order, is the owner's whole promoted population
enumerated down a terminal — the bulk read [ADR 39](0039-affinity-capture-and-read.md) refused,
arriving by another route. So a progress line is a position and a count. **A completed expansion**
takes one of three forms: what the expansion added, why it was refused, or — when a source was
unreachable, an adapter truncated, the shared budget bit, or an endpoint was refused — that it was
**partial** and in which of those ways, counted and never named. A qid on a refusal line would be
genuinely useful and is not worth that; `--dry-run` answers the same question in aggregate and
`listRatings` ([ADR 43](0043-listing-your-own-ratings.md)) answers it precisely, offline, for the
owner alone. **A fourth form, `failed`, is not one of those three**, because it is not a completed
expansion's outcome at all: one entity's `expand` throwing, caught by the loop and never retried,
counted and named by nothing but its position.

Two things hold it, and the second is the stronger. `ExpansionIsSafeToPasteTest` captures the root
logger at `TRACE` — so the sqlite driver's own statement logging is included — over a scratch
database carrying an invented label, an invented note, an id inside that note and a rating, and
asserts that the block was printed and that no line carries any of them. And `ExpansionReport.lines`
takes an `ExpansionTally` whose every component is an `int`, a map keyed by a source id or a map
keyed by a refusal reason: there is nowhere in the signature to put an identifier, which is a
stronger guarantee than a body that merely happens not to print one
([ADR 51](0051-what-an-adr-may-quote.md), [ADR 63](0063-a-read-only-census-of-the-graph.md)).

**The guard has one carve-out, and it is a limit rather than a convenience.** `EntityExpansion` emits
two `warn` lines that name an entity — a neighbour it could not fetch, and an edge endpoint
`IngestService` refused (#233) — and the MCP server emits those same two lines, from the same class,
on every `expand_entity` call. They are the shared expansion's diagnostics, not this tool's report,
so the guard exempts that one logger name by exact match and holds the property over everything
else. The exemption is matched exactly and never by substring, which is itself tested.

### The seam that moved

`WikidataMusicBrainzIdentity` moved from `app` to `expansion`, and nothing else about it changed.
ADR 54 placed it in `app` and said the placement was forced rather than chosen: `musicbrainz`
declares the seam and cannot implement it, because neither adapter package may import the other, and
ADR 32 names `app` as the one package that may see everything. With one entry point that settled it.
There are two now, and the second is a plain-Java tool whose own fence bans `app` — rightly, because
`app` is Spring and reaches `mcp` through `SegueConfiguration`. A bridge the second caller cannot
reach is a bridge only one source crosses.

**ADR 32 is not amended**: `expansion` depends on two adapters and a handful of other packages, which
is not everything, and ADR 32 says of itself that `ArchitectureTest` is the list rather than its own
table. **ADR 54 gains a dated amendment**, which is
[ADR 61](0061-the-bridge-returns-classes.md)'s convention for a partial reversal — the older ADR
keeps `Accepted`, nothing in it is withdrawn, and the amendment names this one.

The two-adapter wiring moved with it, into `ExpansionSources.both`, so that the order the sources are
asked in is stated once. That order is load-bearing: one `ExpandContext` bounds the concatenation, so
a tight budget is spent by whichever adapter runs first, and a second entry point building its own
list would be a second statement of it.

## Alternatives considered

- **Drive `expand_entity` from the client in a loop.** The cheapest thing that could work, and what
  the owner has today. Rejected on three counts, and they are ADR 40's three against `import_list`,
  in the same order: the caller is a language model, so several hundred calls cost a context window
  and produce a transcript rather than a summary; the list it iterates is the owner's promotions, so
  the loop puts the whole taste layer into that transcript, which is the bulk read ADR 39 refused;
  and there is no aggregate at the end, because each call answers about itself.
- **A flag on `seed`.** Rejected outright. ADR 40's whole safety argument is that `seed` resolves and
  reports and never writes, held by a fence that forbids it from opening a store at all. A writing
  mode would not widen that fence, it would delete it — and it would put a bulk writer inside the one
  dev tool whose committed input is a private list.
- **Expand the whole known list rather than the promotions.** More reach per run, and rejected on
  ADR 65's finding: the `--known` file is a concert history and carries no strength on any row, so it
  names entities the owner attended rather than entities he judged. The promotions are the population
  the recommender weights by and the population the harness holds out from, so they are the
  population whose neighbourhoods change what either can say. A later issue may widen it, with the
  census's own before and after to argue from.
- **Expand recursively, or expand what the first pass discovered.** Rejected as unbounded.
  [ADR 49](0049-a-kind-scoped-ceiling-on-concept-expansion.md) bounds one call and says in as many
  words that calls are not counted; a depth-two run over a few hundred seeds is tens of thousands of
  expansions, which is a different decision needing a different cost argument.
- **Put the shared expansion in `ingest`.** Rejected above: it hands `own` and `retract` a route to
  the network and to a bulk write, past fences that say they have neither. Their fences forbid
  `java.net` as a package; they do not forbid reaching a class that runs adapters.
- **Leave the expansion in `SegueService` and have the tool call `SegueService`.** Rejected: the tool
  would depend on `mcp`, which every sibling fence bans and for the reason all of them give — a dev
  tool that can reach the tool layer can become an MCP tool by accident — and it would drag
  `ToolResult`, `CorrelationId` and the view types into a tool that wants counts.
- **A per-adapter breakdown on `ExpansionSummary`.** Rejected by ADR 56 already, and not reopened:
  the MCP wire shape does not move for a dev tool's convenience. The structured lists live on
  `ExpansionOutcome`, which is below the wire.
- **Print a qid on each progress line.** Rejected on the enumeration argument above rather than on
  the sensitivity of the field. A single qid in a single line is unremarkable; one per promotion, in
  qid order, is the list.
- **Retry a failed entity at the end of the run.** Rejected: it needs a retry policy against clients
  that already retry with backoff, it makes the run's cost unpredictable in the one dimension the
  owner is planning around, and re-running the whole tool converges in the graph anyway. The counts
  say how many would be worth a second run.
- **Parallelise the expansions.** Rejected: the MusicBrainz client's slot reservation is what makes
  the pace correct across a batch, and the honest way to go faster is the bounded virtual-thread
  neighbour fan-out that `EntityExpansion`'s javadoc already names as a follow-up — inside one
  expansion rather than across many.

## Consequences

- **The measure of this decision is the next evaluation reading, and this ADR does not take it.**
  It is judged by ADR 65's rule unchanged. **The denominator will have moved, and that is the point
  rather than a problem**: the pool count is what expanding every promotion is an attempt to raise,
  so a reading taken after a run is judged by the rule within itself and is **not row-for-row
  comparable** with the readings before it — the same thing ADR 65 said of its own fold change, for
  the same reason. If the pool rises and the hits rise with it, the misses were ingest's; if the pool
  rises and the hits do not, they were not, and the question moves back to the ranking with one
  confound removed.
- **The log grows with every run.** `ProvenanceCodec.append` drops a provenance whose source and
  reference it already holds, so the graph converges and corroboration does not inflate; the log,
  being append-only ([ADR 24](0024-sqlite-assertion-log.md)), grows by every assertion a run
  recorded again. Re-running is the owner's decision and costs the same wall clock.
- **The safe-to-paste carve-out is open, and closing it is a separate change.** Moving
  `EntityExpansion`'s two entity-naming warnings out to its callers would close it entirely — each
  caller would then decide whether to name the entity, and this tool would decide not to. That is a
  change to a shared class with the MCP server's diagnostics on the other end of it, so it is not
  made here.
- **The default bound is stated three times in this repository and this decision did not repair it.**
  `application.yaml`'s `segue.max-new-edges`, `SegueProperties`' compact-constructor fallback and
  `ExpandContext.defaults()` each state it, nothing reconciles them, and the MCP server's runtime
  default is the first while this tool's is the third. The issue forbade moving a bound, and moving
  where a bound lives is close enough to that line to leave alone. Reported as a finding.
- **There is no resume file and no ledger.** `seed` has one because it resolves thousands of names
  against a fuzzy matcher; this walks a list the ratings table regenerates in a millisecond, and a
  half-finished run is re-runnable at the cost of re-recording assertions the graph already merges.
  If a run turns out to want resuming, that is an issue with a measurement behind it.
- **This decision falsified statements in the user guide, the developer guide, and
  [ADR 61](0061-the-bridge-returns-classes.md), and each is corrected rather than deleted**: the user
  guide's claim that nothing but `add_entity` and `expand_entity` calls the live Wikidata API now
  says *no other tool on this surface* does; the developer guide's "there is no dev-side bridge tool"
  now says which narrower tool still does not exist; and ADR 61's Decision bullet placing
  `WikidataMusicBrainzIdentity` in `app` — and naming `app` the only package that sees two adapters —
  is corrected by ADR 61's own 2026-09-07 amendment, since an ADR is corrected in place by an
  amendment rather than here. None of the three was pinned by a test, which is why they had to be
  found by reading.
- **What verifies this document.** `AdrIndexTest` checks that this file has exactly one row in
  `docs/adr/README.md` and that the row agrees with the heading and the front matter character for
  character; `AdrCitationsTest` refuses a commit citation anywhere in `docs/adr/`, which is why the
  ordering evidence above is prose rather than a hash; `DocumentationLinksTest` resolves every
  relative link and anchor here; and `javadoc -Werror` is what keeps the citations in the code that
  points back at this decision from rotting.

**Amendment (2026-09-08, issue #293): the `graph` section's second label is corrected.**

Nothing above is withdrawn, no decision above is edited, and this ADR keeps `Accepted`.

*The output contract* above names the `graph` section's two rows as *nodes added, edges added*. The
second is now printed as **`edge assertions recorded`**. Nothing about what it counts changed: it
was always one increment per edge assertion `EntityExpansion` recorded, and an edge the graph
already holds is recorded again; the *Consequences* bullet above says this of the log, and it is
true of this row for the same reason ([ADR 19](0019-assertion-log-source-of-truth.md)). `nodes
added` beside it is a net count, and after this change the two labels are the only thing that
tells a reader which is which.

**The reason is the first full run, reported in #284's closing comment.** The tool's figure and the
census's edge total before and after did not agree, and a reader taking `edges added` at its word
would have read the tool's number as the graph's gain. No figure from that reading is restated
here: `graphCensus` is the authority on the graph and the issue is the record of the run, and a
number copied into this document could only go stale.

**The alternative was to change the number rather than the label**: report the graph's net gain,
so the tool and the census agree. It lost because this tool never sees that figure. A net gain is
the graph before against the graph after, which is a census, and `graphCensus` is the authority on
it ([ADR 63](0063-a-read-only-census-of-the-graph.md)); step 5 of the runbook is where the owner
makes that comparison,
with the tool's block on one side of it. A second census inside this tool would duplicate that
authority to correct a label.

The label is `ExpansionReport.EDGE_ASSERTIONS_RECORDED` — still a literal in `ExpansionReport`, as
the contract above requires, and now a named one because the developer guide's runbook cites it
too. `DeveloperGuideExpandPromotionsExamplesTest` reads that constant when it checks the runbook's
`claims` / log rows row, so the row and the printed line cannot drift; `ExpansionReportTest`'s
golden block still pins the text itself as a literal, as it pins every other label. `ExpandRun`'s
per-entity progress line says `edge assertion(s)` because the same number is behind it.

Nothing else in the block moves. The section names, their order, the empty-section rule and the
rule that widths are derived from the whole block are unchanged — the last of those is why the
longer label shifted every count in the block by four characters, which is the rule working rather
than a second change. `edges by source`, which is the same quantity broken down, keeps its heading:
it sits directly under the renamed row and sums to it, and renaming it is a separate decision
nobody has asked for.

The MCP surface is untouched. `expand_entity`'s `edgesAdded` payload field
([ADR 26](0026-mcp-tool-surface.md)) keeps its name, because renaming a wire field is a protocol
change for clients and `SegueService`'s javadoc already says the field counts per assertion rather
than per pair of nodes. `SegueService`'s own detail sentence for a clean expansion still says
`edge(s)` of that number; it was seen and left, because it is `expand_entity`'s output under ADR 26
and not this tool's block, and changing it is a separate issue. The Java names
`ExpansionOutcome.Expanded#edgesAdded` and `ExpansionTally#edgesAdded` stay too; only the printed
label and the prose moved.

**Amendment (2026-09-08, issue #299): the per-source heading, and `expand_entity`'s detail
sentence.**

Nothing above is edited and this ADR keeps `Accepted`. One choice above is superseded: #293's
amendment left this heading alone for want of an ask, and #299 is that ask.

*The output contract* above names a section `edges by source`. It is now printed as
**`edge assertions by source`**. Nothing about what it counts changed: those rows are the same
increments, broken down per adapter id, that the row above them sums as `edge assertions
recorded` — and that is the reason for the rename, since a section labelled `edges` sitting
directly under a row labelled `edge assertions recorded` re-opens one line later the ambiguity
#293 closed. The amendment for #293 renamed that row and left this heading, saying renaming it
was a separate decision nobody had asked for. #299 is that ask, and this records it; the sentence
above is not edited, and reads as the record of what was decided then, not as a description of the
block today.

**The `nodes added` paragraph in the output contract holds unchanged under the new name; read the
heading it names, and the one in the section list above it, as the renamed one.** It says `nodes
added` sits under `graph` and not under the per-source section, and its reason is that every
`AssertionRecord` carries a `Provenance` whose `sourceId` says which adapter produced it while a
node's identity may instead come from `EntityResolver.fetch`, which has no adapter behind it. That
is a contrast between two sections and the authority each has for a source id, not a claim about
the word "edges", so renaming the section changes neither side of it.

**No column moves, and that is the difference from #293.** `ExpansionReport.render` derives the
label column and the count column from counted rows alone; a section heading is never measured.
So unlike the longer row label, which shifted every count in the block, this rename shifts
nothing. `ExpansionReportTest`'s golden block pins the heading as a literal and its
empty-section test looks the heading up by the same text, so both carry the new name and a
missing heading still reds. The heading stays an inline literal rather than becoming a named
constant. #293 named its label because the developer guide's runbook cites it and
`DeveloperGuideExpandPromotionsExamplesTest` reads it from there. Nothing reads this heading out
of a document — the mentions above are prose no test resolves — so there is nothing for a
constant to keep in step, and the golden block pins the text as a literal exactly as it pins every
other heading.

**`expand_entity`'s detail sentence has moved.** The amendment for #293 recorded that
`SegueService`'s clean-expansion sentence still said `edge(s)` of the same number, that it was
seen and left, and that changing it was a separate issue. It now reads
`expanded <qid>: N edge assertion(s), M new node(s)`.

**That is not a protocol change.** The payload field `edgesAdded`
([ADR 26](0026-mcp-tool-surface.md)) is untouched, and so is every Java identifier behind it. What
moved is the `detail` string. `ToolResult` declares that field human-readable for every outcome,
and [ADR 27](0027-mcp-protocol-conformance.md) is why its failure half is prose a model can act on
rather than a protocol error; nothing anywhere makes it a field to compute from — a caller that
wants the number reads `edgesAdded`, nothing in this repository reads the sentence for it, and
`ExpansionSummary`'s javadoc for that field already said the count is per assertion rather than
per pair of nodes, which is what the sentence now says too. The tool surface ADR 26 governs is
unchanged, so ADR 26 needs no amendment of its own. Nothing in `src/test` pinned that sentence
before #299; a pin was added with the change, and it was seen red on the old wording first.

`get_entity`'s sentence is a different quantity and stays as it is. It counts the edges the graph
holds on one node, after corroborating assertions have been merged into single edges, so
`edge(s)` is the correct word for it — the same word in a neighbouring method for a number that
really is an edge count. The user guide's `expand_entity` transcript moved with the sentence and
its two `get_entity` transcripts deliberately did not, so the guide shows both wordings — which
is the distinction, not a drift.

**Amendment (2026-09-11, issue #307): the tool takes `--rated-since`, and expands only the
promotions the instant admits.**

Nothing above is withdrawn, no decision above is edited, and this ADR keeps `Accepted`. What changes
is that a run may now be asked to visit a smaller population than every promotion, and the block
says so when it was. The usage line above no longer lists every flag: the tool takes an optional
`--rated-since`, and `ExpandCli.USAGE` is the authority on its current text.

**The flag.** `./gradlew expandPromotions --args="--db <segue.db> --rated-since <ISO-8601
instant>"`, optional, parsed exactly as `evaluate` already parses its own flag of the same name —
`Instant.parse`, refused with this tool's own usage error rather than the harness's on a value that
does not parse. Not given, the tool reads no timestamp at all and behaves exactly as it always has;
this is the same shape [ADR 65](0065-an-offline-evaluation-harness-for-the-recommender.md)'s
2026-09-06 amendment gave the harness, read here rather than restated.

**What `considered` means under it.** `Preflight.considered` and `ExpansionTally.considered` are the
promoted population **after** the filter — the same field, a smaller population, never a second
field naming the smaller count — so the identity `considered == expanded + refused + failed` and
step 2's dry-run arithmetic both survive unchanged: every promotion the run was handed is accounted
for by one of those three, whether or not `--rated-since` was given. The excluded count is not a
second field on the tally at all; it is on the clause below, because it counts promotions the run
was **not** handed, which `considered` and its three parts have no business describing.

**The header form.** One `#` clause is printed directly under the block's own header, in **both**
the dry-run block and the real block, naming the instant, how many promotions it excluded and the
last-write limit below. A clause rather than a counted row. A row would print on every run, and on a
run with no instant it would read an excluded count of zero — a count of a filter nobody applied,
on every block ever pasted, for a value only one run in many carries. `ExpansionReport.sinceLine`
is the authority on the clause's
wording and carries that argument in full. **The block with no instant is byte-identical to
today's**, which is what keeps every block already pasted into an issue comparable to a new one, and
which is what [ADR 65](0065-an-offline-evaluation-harness-for-the-recommender.md)'s 2026-09-06
amendment does for its own report, for the same reason. A golden test that predates the flag pins
the unsplit block character for character and is unchanged by this work; the two new with-instant
pins were seen failing — on the missing clause alone, every other line matching in order — before
the renderer took the filter.

**The type-level fence is untouched.** The renderer's new argument is an `Instant` and an `int` —
`RatedSince`, one instant and one count, exactly as `ExpansionReport.lines` and `dryRunLines`
already took only `int`s and a map keyed by a source id or a refusal reason. There is still nowhere
in either signature to put an identifier: `Instant.toString` emits only digits, `+`, `-`, `:`, `.`,
`T` and `Z` — never a letter but `T` and `Z` — so the one operator-supplied fact in the whole block
cannot carry a qid into it however the flag was spelled, the same property this ADR's output
contract already relies on for every other field.

**Where the filter lives.** Composed at `ExpandCli` from `KnownList.promoted` and the
merge-resolved rating timestamps — `AffinityStore.readUpdatedAt`, resolved through the same
`Equivalences` the ratings themselves are resolved through, so the two maps agree on which qid names
which entity — and not a second filtering rule inside the run. The age comparison itself is
`RatingAge`, moved from `evaluate` into `domain` so that both tools read one answer to "since" rather
than two copies of the same one-line comparison drifting apart the way a copied rule always does in
this repository.

**A promotion with no timestamp is refused, not excluded.** The two bulk reads of the affinity table
are two selects over one table through one connection, so a disagreement between their keysets means
the resolution or the store is wrong, not that the entity is old. Excluding it silently would mean an
entity the owner asked this tool to expand is never visited and nothing on the block says so — a
promotion that vanishes into the same count as one the instant genuinely excluded. The refusal names
no qid and no count, for the reason every other refusal in this block does: how much the owner has
rated is itself a fact about him.

**Alternatives rejected.**

- **Recording which promotions have already been expanded**, as a new claim type or a mark in the
  log, so a later run could skip them outright rather than merely visiting fewer of them. **Not done
  here, and no issue is recorded for it:** the rating's own timestamp is a proxy for "probably
  already covered" that needs no new state at all, and a real marker is a schema change this
  repository's own rule says gets a real migration path, not a rider on this issue. It becomes worth
  raising if the proxy is ever seen to re-expand enough promotions to matter.
- **An excluded row instead of a clause.** Rejected above, for the reason given there — a row prints
  on every block and pads every count for a number that is usually zero.
- **Making the filter mandatory, with a default instant.** Rejected: a boundary nobody typed would
  break comparability with every block already on record — the reason
  [ADR 65](0065-an-offline-evaluation-harness-for-the-recommender.md)'s 2026-09-06 amendment gives
  for refusing a default instant for the evaluation harness's own split.
- **Copying the harness's age-split machinery into `expand` rather than moving it into `domain`.**
  Rejected: a second copy of `RatingAge`'s one comparison is exactly the shape this repository has
  already been bitten by once — a rule duplicated under a second name is how a fold amendment
  drifted after [ADR 64](0064-fold-the-log-once-per-boot.md) was written and before its fences
  landed, and moving the class rather than copying its body is what keeps the two tools answering
  one question the same way.

**Nothing here is unit-testable on its own, and that is said out loud rather than left implied.**
This entry records a decision whose code landed with its own tests — the parser, the filter's
composition at the call site, the refusal for a promotion with no timestamp, the header clause and
its two new pins, the golden block beside them left unchanged, and the widened fence this ADR's
sibling amendment records — each with its own control. The verification of the *document*
is the full gate over an otherwise unchanged tree: `AdrIndexTest`, `AdrCitationsTest`,
`DocumentationLinksTest` for the relative links above, and `javadoc -Werror` inside
`./gradlew check`.
