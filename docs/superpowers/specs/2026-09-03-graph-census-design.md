# A read-only census of the owner's graph, safe to paste

Issue [#227](https://github.com/robsartin/segue/issues/227). Written 2026-09-03, against `main` at
`0783492`.

## Why there is nothing to read but the owner's own graph

Three questions this repository has left open can only be answered by a number nobody has:

- [ADR 59](../../adr/0059-owner-claims-as-a-third-layer.md)'s residual — *"How many merges the
  owner's real graph holds is unmeasured."*
- [ADR 57](../../adr/0057-the-floor-reports-itself.md) and issue #135 — the degree floor is a
  measured default on a graph that grows under it, and nothing re-opens the question.
- [ADR 55](../../adr/0055-what-the-musicbrainz-adapter-refuses.md) and issue #167 — whether the
  MusicBrainz bridge's undescribed residual shows up at the owner's scale.

The database is private and nobody but the owner may open it
([ADR 16](../../adr/0016-privacy-and-data-handling.md), issue #37). So the answer is a tool the
owner runs in one command whose **whole output is aggregates**:
[ADR 51](../../adr/0051-what-an-adr-may-quote.md) already says an aggregate over the owner's data is
publishable and an entity presented as the owner's is not, and this tool is that line made
mechanical for one artefact.

## What is built

A sixth read-only dev-side tool, in its own package `com.robsartin.segue.census`, run as:

```
./gradlew graphCensus --args="--db $HOME/.segue/segue.db"
```

It prints one plain-text table of counts and writes nothing.

### Where each number comes from

Every number is folded out of the append-only log — the whole thing, or `LogProjection` of it. The
sections below name the source for each.

**Nodes** — `LogProjection.nodes()`.

| number | derivation |
|---|---|
| total | `projection.nodes().size()` |
| per `NodeKind` | group the node records by `kind()`, all six kinds emitted, zeros included |

**Edges** — `LogProjection.edges()` and `LogProjection.danglingEdges()`.

| number | derivation |
|---|---|
| total | `projection.edges().size()` |
| dangling | `projection.danglingEdges()` — the count that record's Javadoc says should always be zero |
| by type | group by `EdgeRecord.typeCode()` |
| by source | for each distinct `Provenance.sourceId()` across every edge's `sources()`, how many edges carry at least one |
| by corroboration | group by `EdgeRecord.corroboration()` |

**By source does not sum to the total, and that is not a bug.** An edge two sources assert is counted
under both, which is exactly what makes corroboration countable
([ADR 19](../../adr/0019-assertion-log-source-of-truth.md)). The row labels say "edges backed by".

**World-fact claims** — the raw `AssertionLog.readAll()` list, through `Retractions` and
`Equivalences`, which are the rules both folds already apply.

| number | derivation |
|---|---|
| log rows | `logged.size()` |
| retractions | rows that are a `Retraction` |
| rows they removed | rows that are not a `Retraction` and for which `Retractions.survives` is false |
| entities they name | `Retractions.lastRetraction().size()` |
| local entities minted | surviving `LocalEntity` rows |
| merges standing | surviving `SameAs` rows where `Equivalences.last` is true |
| merges superseded | surviving `SameAs` rows where `Equivalences.last` is false |
| merges superseded but edge-referenced | of those, the ones where `Equivalences.stands` is true (#221) |
| stand-ins | `Equivalences.standIns(logged, KindMapper::rederive).size()` |
| stand-ins with no edge | of those canonical ids, the ones with degree 0 in the fold |

Standing plus superseded is every surviving merge; superseded-but-edge-referenced is a subset of
superseded, not a third bucket. **Minted counts rows, not entities**: nothing forbids the same qid
appearing on two `LocalEntity` rows, and a count that silently deduplicated would hide that.

**The taste layer** — `AffinityStore.readRatings()`, the note-free bulk read.

| number | derivation |
|---|---|
| total | `readRatings().size()` |
| per score, 1 to 5 | group by value; all five rows emitted, zeros included |
| on a local id | `LocalEntity.isLocal(qid)` — the one home for what a local id looks like |
| on a stand-in | the qid is a key of `Equivalences.standIns` |
| on a retracted id | the qid is named by a `Retraction` **and** has no node in the fold |

The retracted clause carries the second half because the log is append-only and a retracted entity
can be re-added: the retraction row stays forever, so "named by a retraction" alone would go on
counting a rating whose entity is back in the graph.

**Degree** — incidence over `LogProjection.edges()`, per node in `LogProjection.nodes()`.

| number | derivation |
|---|---|
| floor | `Recommendations.MIN_CANDIDATE_DEGREE`, by reference — ADR 57 |
| p50, p90, p99, max | nearest-rank over the sorted degrees |
| at or below the floor | nodes whose degree is `<= floor` |

**Quantiles are a degree some node actually has**, on `FloorReading.medianDegree`'s stated reason: a
median of 6.5 edges describes nothing in the graph, and the figure is read beside an integer floor.
The rule is `sorted.get(min(size - 1, (int) (size * p)))`, which at `p = 0.5` is exactly
`FloorReading`'s upper middle. Empty reads as zero, distinguishable from every real reading because
every floor is at least one — the same argument that record makes.

**Isolated nodes are in the population.** Degree is taken over every node in the fold, zeros
included, because "at or below the floor" is meaningless against a denominator that has already
dropped the nodes nothing reaches. `CandidateSweep` excludes at `degree < minDegree`, so this count
is the sweep's exclusions **plus** the nodes sitting exactly on the floor — the population
`FloorReading.headOnTheFloor` says moves first.

**The bridge** — projection edges carrying a MusicBrainz provenance.

| number | derivation |
|---|---|
| entities MusicBrainz reached | distinct endpoint qids of edges with a `sourceId` of `musicbrainz` |
| of those, carrying classes | those whose node in the fold has a non-empty `instanceOf()` |

## Three places the issue's description does not match the code

### 1. "How many have classes the bridge supplied" is not answerable from the log

The issue's sixth bullet asks for *"how many entities carry a MusicBrainz id, and how many have
classes the bridge supplied (#163)"*. The second half cannot be derived, and the code says so in as
many words. `MusicBrainzSourceAdapter.toNeighbour` stamps a bridge-supplied neighbour claim with
`new Provenance("wikidata", neighbour.qid(), assertedAt, 1.00)` and its Javadoc explains why:

> this claim is byte-identical to what `ReverseClaims` and `WikidataEntityResolver.fetch` would have
> produced for the same entity, because it is the same claim from the same source.

`WikidataEntityResolver.fetch` and `ReverseClaims` build exactly that `Provenance` — same source id,
same `sourceRef` (the qid itself), same confidence. **There is no marker in the log**, deliberately:
stamping it `musicbrainz` would attribute Wikidata's classes to a database that states none, which
[ADR 61](../../adr/0061-the-bridge-returns-classes.md) refuses. So the census reports the two things
the log *can* answer — how many entities a MusicBrainz-sourced edge reaches, and how many of those
carry classes at all — which is the shape of the ADR 55 / #167 residual, and it does not pretend to
separate who supplied them. Separating them needs a marker on the claim, which is a change to the
log's contents and a different issue.

**"Carries a MusicBrainz id" is also read as "a MusicBrainz-sourced edge names it".** No MBID is
stored per entity anywhere: `NodeRecord` is `(qid, kind, label, instanceOf)` by
[ADR 22](../../adr/0022-wikidata-identity-and-vocabulary.md) clause 2, and the only MBIDs in the log
are inside the `sourceRef` of a MusicBrainz edge claim
(`"artist/" + seedMbid + "#" + type + ":" + targetMbid`). Counting distinct MBIDs out of that string
would be parsing a citation, which is what the census refuses to print in the first place.

### 2. There is no `InventedGraph` the census can use

`export.InventedGraph` and `ratings.InventedRatings` are both package-private classes with
package-private members, and this repository's pattern is one invented fixture per package
(`recommend.InventedWorld` is the third). A census test in `com.robsartin.segue.census` cannot see
any of them. So the census gets `census.InventedCensus`, built on the same conventions — unallocatable
ids per [ADR 58](../../adr/0058-stand-in-identifiers-cannot-be-allocatable.md),
[ADR 59](../../adr/0059-owner-claims-as-a-third-layer.md) and
[ADR 62](../../adr/0062-reserve-a-shape-for-a-merges-canonical-side.md), invented labels and notes —
rather than a widened `InventedGraph`. Widening a fixture across a fence the production packages
carry would be the one dependency direction the sibling rules exist to forbid, arriving through the
test tree.

### 3. "Prints" cannot mean `System.out`

`ArchitectureTest.nothingWritesToStandardOut` bans reading `System.out` project-wide, with one named
exception that is not a dev tool ([ADR 28](../../adr/0028-mcp-transports.md),
[ADR 30](../../adr/0030-structured-logging.md)). So the census emits through SLF4J at `info`, one
call per line, the same route `ExportCli` and `RatingsCli` use for their notes.

**The reason `ratings` writes a file does not apply here, and it inverts.** `RatingsCli`'s Javadoc:
"ADR 33 says affinity is never logged. So the ratings go to the operator's chosen path and the log
lines carry counts alone." The census *is* counts alone. There is nothing in it a log line may not
carry, so there is no output file, no `--out`, and nothing for the owner to find on disk afterwards
— which is the whole of what "safe to paste" buys. The line prefix comes from Logback's default
configuration, the same prefix every dev tool already prints, and is not this issue's business.

## Decisions

### It reads through the ports, never the SQLite file

`CensusCli` opens `SqliteAssertionLog` and `SqliteAffinityStore` and hands the two `port` interfaces
down, exactly as `RatingsCli` does. Reading the schema directly would make the census a second reader
of a store [ADR 18](../../adr/0018-graph-engine-gremlin.md) and
[ADR 24](../../adr/0024-sqlite-assertion-log.md) exist to keep replaceable — and worse, it would have
to re-implement retraction, merge folding and kind re-derivation in SQL to get a single number right.
`SqliteAssertionLog.readRow` already owns decoding a row; a census with its own copy is the second
copy of a rule, which is the defect this repository keeps finding.

### It reads `LogProjection`, and therefore depends on `export`

Four of the six sections — nodes, edges, dangling, degree — are counts over the fold. There are
exactly two ways to have one:

- **Fold the log again inside `census`.** Rejected outright. `BothFoldsAgreeTest` exists because two
  folds of one log drifted, `Equivalences.foldEndpoints` and `Retractions.survives` were both moved
  into `domain` to stop it happening again, and a census that disagreed with the export about how
  many nodes there are would be worse than no census. A third fold is the one thing this repository
  has spent three issues preventing.
- **Depend on `export.LogProjection`.** Taken. The census counts the graph the exporter draws, by
  construction rather than by agreement.

So `census → export` is permitted, and it is the **second** dependency between two dev tools. The
first is `rate → recommend` ([ADR 46](../../adr/0046-the-rating-deck.md)), and the mechanism is
already built for it: `ArchitectureTest.otherDevToolsAnd` takes the permitted siblings as a list. The
borrowed fence is bounded in the same way it is for `rate`: `theExporterOnlyReads` makes `export`
read-only, so nothing the census can reach through it can write.

**`LogProjection` is not moved to `support`.** That is the precedent `ClassLabels` set when `rate`
needed it, and it does not reach here: `LogProjection` depends on `port`, `domain` and
`wikidata.KindMapper`, and `support` depends on nothing. Moving it would give `support` three
dependencies to buy one dev tool a fold, and would touch a class a dozen Javadocs cite.

**The log is read twice** — once by `LogProjection.of(log)`, once by the census for the raw rows.
Accepted. The alternative is an overload `LogProjection.of(List<LoggedAssertion>)`, which widens
another package's public API for a dev tool's convenience; the census is run by hand, occasionally,
and a second pass over the rows costs seconds.

### `--db` is required, on ADR 60's clause and not its consequence

[ADR 60](../../adr/0060-the-claim-tools-require-an-explicit-database.md) required the flag for the
two tools that append a first-person claim, because a wrong row in an append-only log cannot be taken
back. **That argument does not reach a read.** A different one does, and it is ADR 60's own central
clause:

> an agent's shell is initialised from the owner's profile … An environment variable cannot
> distinguish the owner from an agent running as the owner. A flag typed per invocation can.

The census's output is the **shape of the owner's entire graph and taste layer**. Aggregates are
publishable under ADR 51, but *whether to publish them* is the owner's decision, taken per
invocation — not one an inherited `SEGUE_DB` makes on his behalf, in an agent's context window,
because a task happened to mention counting things. And the second half is that the output is
evidence: it gets pasted into an issue and quoted in an ADR, where a wrong export is discarded and a
wrong count becomes the record. A census of the wrong database is a wrong census, permanently, in a
document.

So `census` uses `support.RequiredDatabase.refusal` — the same sentence, one home — and has no
default to resolve.

**It gets its own two fences rather than joining ADR 60's.** `theClaimToolsHaveNoDefaultDatabase` and
`theClaimToolsTakeTheirDatabaseFromTheFlagAlone` are named for claim tools and scoped to `retract` and
`own`; ADR 60's consequences say in as many words that a third tool would have to be added by hand.
Widening them would make two rule names describe something that is not a claim tool, and ADR 60 —
immutable — names both. So `theCensusHasNoDefaultDatabase` and
`theCensusTakesItsDatabaseFromTheFlagAlone` are written beside them, with the same two-line
reasoning: the first forbids the name, the second forbids the capability, and ADR 60 measured that
the first alone stops only the lazy version.

### The taste-layer read is widened, and that is why there is an ADR

`ArchitectureTest.onlyTheRecommenderReadsEveryRating` confines `AffinityStore.readRatings` to
`recommend` and `rate`. Its own Javadoc says widening the taste layer's readership "stays an
ADR-level decision", and the last widening — to `rate`, for the deck — was recorded in ADR 46 and
issue #101. So this one is recorded too.

**`readRatings`, never `readAll`.** The map is `Map<String, Integer>` and has nowhere to put a note,
so the census structurally cannot see one — the same reason that rule's sibling gives for letting the
recommender hold the store at all. `onlyTheRatingsToolReadsEveryRating` and
`onlyTheRatingsToolReadsANote` are untouched: the listing tool keeps the note-carrying read.

### The privacy boundary is a test, not a review obligation

ADR 51 says plainly that its line "is held by review, and by nothing else", and gives two reasons no
test can hold it in general: the framing decides whether a QID is a citation or a disclosure, and a
test would have to read the private store to know.

**Neither reason reaches one tool's output.** There is no framing to judge, because the census emits
no free text from the data at all — every value is an integer and every label is a literal in
`CensusReport`. And there is nothing to look up, because the assertion is over the *shape* of the
output rather than over what any name means: no `Q`-shaped token anywhere, no label from the fixture,
no note from the fixture. So `CensusIsSafeToPasteTest` drives `CensusCli.main` over a real SQLite
database whose fixture carries a label, a note, and a `Q` id *inside* the note, captures every log
line at TRACE — sqlite-jdbc's own statement logging included, which is how the sibling test found the
driver logging SQL — and asserts none of the three appears.

The `\bQ\d+\b` assertion also covers a case nothing else does: the census prints raw text off the log
in two places, edge type codes and source ids. Those are vocabulary rather than entities, but they
are data, and if one ever arrived Q-shaped the test reds. That is the safe direction.

## What is deliberately not built

- **No `--out`.** See "prints" above.
- **No per-entity anything, no options, no second view.** The issue's numbers, once each.
- **No amendment to ADR 55, 57 or 59.** The census produces the numbers those residuals ask for;
  reading them and deciding something is the next issue, and an amendment written before the tool has
  ever been run would record a decision nobody has taken.
- **No re-derivation of what the sweep excludes.** The census reports degree over the whole fold. The
  candidate population is `CandidateSweep`'s and `FloorReading` already reports it.

## The shape of the code

```
census/
  CensusCli.java      main, --db required, opens the two sqlite stores, routes lines to SLF4J
  CensusRun.java      holds the two ports, builds a Census, emits the report through a Consumer
  Census.java         the six sections, and the one static factory that folds the log
  NodeCensus.java     record + of(LogProjection)
  EdgeCensus.java     record + of(LogProjection)
  ClaimCensus.java    record + of(List<LoggedAssertion>, LogProjection)
  TasteCensus.java    record + of(Map<String,Integer>, List<LoggedAssertion>, LogProjection)
  DegreeCensus.java   record + of(LogProjection)
  BridgeCensus.java   record + of(LogProjection)
  CensusReport.java   Census in, List<String> out — the only class that decides what a person sees
```

**One home per number, and one hand-counted test per number.** The issue asks for "one function per
number"; what it is asking for is that a wrong count is a red rather than a shrug, and that is
delivered by each number having exactly one place that computes it and one test asserting its value
against a fixture small enough to count by hand. Twenty-five one-line static methods, each with a
Javadoc, would be structure ahead of the need. Each section record's `of` is pure, takes only what it
counts over, and is tested per field.

**`CensusReport` sorts as well as renders**, on `RatingsTable`'s stated reason: a writer that
announced an ordering somebody else applied could be made to lie by one refactor. Kinds come out in
enum order, scores 1 to 5 always, edge types and source ids sorted, corroboration ascending — so two
runs over one unchanged log produce byte-identical text ([ADR 43](../../adr/0043-listing-your-own-ratings.md)'s
contract).

## Fences

| rule | forbids |
|---|---|
| `theCensusOnlyReads` | `census` calling `GraphStore.record`/`upsertNode`, `AssertionLog.append`, `AffinityStore.put` or `updateRating`, or depending on `IngestService`, or on any dev tool but `export` |
| `theCensusOpensNothingElse` | `census` depending on `tinker`, `jena`, `ingest`, `mcp`, `app`, `musicbrainz`, `java.net`, `javax.net`, or any class of this project's that reaches a network |
| `theCensusHasNoDefaultDatabase` | `census` depending on `support.DefaultDatabase` |
| `theCensusTakesItsDatabaseFromTheFlagAlone` | `census` taking a `java.nio.file.Path` out of any `support` class |

`census` joins `ArchitectureTest.DEV_TOOL_PACKAGES`, so every sibling's fence gains `..census..` at
once and `PackageListsTest` holds the constant to the tree in both directions. `wikidata` is not
banned, for the exporter's reason exactly — `KindMapper.rederive` is a static table and no more a
network call than `ClassLabels` is — and `sqlite` is not banned because opening the two stores is the
tool's job.

## The ADR

**ADR 63**, and the case against it is worth stating: a sixth read-only dev tool with a required
`--db` is four existing decisions applied, not a new one, and this repository does not want an ADR per
tool.

It is written anyway, because three things change that no existing ADR covers:

1. **`onlyTheRecommenderReadsEveryRating` gains a third package.** That rule's Javadoc says the
   widening is ADR-level, and the precedent (ADR 46, issue #101) is that it is recorded.
2. **A second permitted dependency between two dev tools**, against a guide sentence that today reads
   "the one dependency between two dev tools".
3. **ADR 51's review obligation becomes a mechanical one for a named artefact.** ADR 51 states
   flatly that no test can hold its line and explains why; saying "for this one output, one can" is a
   correction to the shape of that decision and belongs in a record, not in a Javadoc.

Requiring `--db` on a read is a fourth, and it is the one that would not have carried an ADR alone.
ADR 51, 55, 57 and 59 are cited and none is amended.
