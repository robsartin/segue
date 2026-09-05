---
status: Accepted
date: "2026-09-03"
topic: a-read-only-census-of-the-graph
tags: [project, tooling, privacy, data, graph]
supersedes: []
related: [what-an-adr-may-quote, the-claim-tools-require-an-explicit-database, listing-your-own-ratings, graph-exporter-views-and-formats, taste-layer-separation, privacy-and-data-handling, the-floor-reports-itself, owner-claims-as-a-third-layer, what-the-musicbrainz-adapter-refuses, layering-and-archunit, assertion-log-source-of-truth, the-rating-deck]
---
# 63. A read-only census of the graph: aggregates only, with a test as the privacy boundary

## Context

Three decisions this project has taken end in a number nobody has.

[ADR 59](0059-owner-claims-as-a-third-layer.md)'s residual says it outright: *how many merges the
owner's real graph holds is unmeasured.* [ADR 57](0057-the-floor-reports-itself.md) chose a degree
floor by running two floors on the graph of the day and reading the two lists, and then made the
re-run trigger a procedure rather than somebody's memory: `FloorReading` emits the figures on every
recommendation run, and that ADR names the thresholds at which the two-floor comparison is taken
again. **Those figures are readings of one run's candidate pool** — the candidates that cleared the
floor, plus two counts of what the sweep discarded on degree — so what nobody has is where the floor
sits against the *graph*: the whole degree distribution, including every node no sweep considers.
[ADR 55](0055-what-the-musicbrainz-adapter-refuses.md) and issue #167 left open whether the bridge's
undescribed residual matters at the owner's scale.

Each of the three is a count over one database, and that database is private: nobody but the owner
may open it ([ADR 16](0016-privacy-and-data-handling.md), issue #37). A reviewer cannot answer them,
an agent cannot answer them, and the owner has had no way to answer them either — the tools that read
the whole graph produce output that names entities, which is exactly what may not leave the machine.

[ADR 51](0051-what-an-adr-may-quote.md) already drew the line this needs: **an aggregate over the
owner's data is publishable; an entity presented as the owner's is not.** What it also said is that
the line is held by review and by nothing else, for two reasons it argues are fatal to any test — the
framing decides whether a QID is a citation or a disclosure, and a test would have to read the
private store to know which entities are the owner's.

## Decision

**A read-only dev-side tool of its own, `./gradlew graphCensus --args="--db <segue.db>"`, prints one
block of counts and nothing else.** `CensusReport` is the authority on which counts and in what
order; this ADR does not list them, because a list here would be a second copy going stale on its
own.

Four properties are the decision, and each is enforced.

### Every value is an integer, and that is what makes ADR 51 testable here

ADR 51's two reasons are true in general and **neither reaches one tool's report**. There is no
framing to judge, because the report carries no free text from the data at all: every value is an
integer and every label is a literal in `CensusReport`. The property is over **the census the tool
emits**, and not over everything a run can put on a terminal — see the limits below. And there is nothing to look up, because the
assertion is over the *shape* of the text rather than over what a name means — no label from the
fixture, no note from the fixture, and nothing matching `\bQ\d+\b` anywhere.

`CensusIsSafeToPasteTest` drives the CLI over a real SQLite database whose fixture carries all three,
captures every log line at TRACE so the JDBC driver's own statement logging is included, and asserts
all three absent. It is a guard rather than a behaviour, so its evidence is a planted leak seen fire
rather than a red before the code.

The `\bQ\d+\b` clause also covers the one hazard that comes with printing stored text: edge type
codes and source ids are read raw off the log, because [ADR 19](0019-assertion-log-source-of-truth.md)
forbids deleting a row and a census that dropped a retired vocabulary would answer a different
question. Those are vocabulary rather than entities. If one ever arrives entity-shaped, the test reds,
which is the safe direction.

**This does not overturn ADR 51 or narrow it.** ADR 51 remains the rule for prose, and remains
held by review. What is added is that one named artefact's compliance is now mechanical.

### It counts the exporter's fold, so `census` depends on `export`

`Census`'s components are the sections and say which reads what: most count nothing but the folded
graph, `ClaimCensus` reads the raw log rows beside it, and `TasteCensus` reads the score map through
`AffinityStore.readRatings` as well as both. There are exactly two ways to have a fold: read `export.LogProjection`, or write a
third one. `BothFoldsAgreeTest` exists because two folds of one log drifted;
`Equivalences.foldEndpoints` and `Retractions.survives` were both moved into `domain` to make
drifting impossible rather than merely detectable. A census that disagreed with the exported picture
about how many nodes there are would be that defect returning in the one artefact whose whole purpose
is to be quoted.

So `census → export` is permitted, and it is the **second** dependency between two dev tools after
`rate → recommend` ([ADR 46](0046-the-rating-deck.md)). The mechanism is the one that already exists:
`ArchitectureTest.otherDevToolsAnd` takes the permitted siblings as a list. The borrowed fence is
bounded the same way — `theExporterOnlyReads` makes `export` read-only, so nothing reachable through
it can write.

**`LogProjection` is not moved to `support`.** That is the precedent `ClassLabels` set when `rate`
needed it, and it does not reach: `LogProjection` depends on `port`, `domain` and `wikidata`, and
`support` depends on nothing.

### `--db` is required, on ADR 60's clause rather than its consequence

[ADR 60](0060-the-claim-tools-require-an-explicit-database.md) required the flag for the two tools
that append a first-person claim, because a wrong row in an append-only log cannot be taken back.
**That argument does not reach a read**, and pretending it does would be the kind of borrowed
reasoning that makes a rule look arbitrary later.

The argument that does reach is ADR 60's central clause: *an agent's shell is initialised from the
owner's profile … an environment variable cannot distinguish the owner from an agent running as the
owner; a flag typed per invocation can.* This tool's output is the shape of the owner's whole graph
and taste layer. Aggregates are publishable under ADR 51; **whether to publish them is the owner's
decision, taken per invocation**, not one an inherited `SEGUE_DB` makes on his behalf because a task
happened to mention counting things. And a census is evidence rather than a working file: it is
pasted into an issue and quoted in an ADR, where a wrong export is discarded and a wrong count
becomes the record.

**Two fences of its own, not ADR 60's widened.** `theClaimToolsHaveNoDefaultDatabase` and
`theClaimToolsTakeTheirDatabaseFromTheFlagAlone` are named for claim tools, ADR 60 names both in its
text and is immutable, and its consequences say a third tool joins by hand.
`theCensusHasNoDefaultDatabase` and `theCensusTakesItsDatabaseFromTheFlagAlone` are written beside
them with the same division of labour — the first forbids the name, the second forbids the
capability — because ADR 60 measured that the first alone stops only the lazy version. The refusal
sentence itself is `support.RequiredDatabase`'s, so there is one home for it rather than a third
copy.

### The note-free bulk read is widened to a third package

`ArchitectureTest.onlyTheRecommenderReadsEveryRating` confined `AffinityStore.readRatings` to
`recommend` and `rate`, and the javadoc beside it — `theRecommenderReadsRatingsAndNeverNotes`'s,
which explains what all three taste fences are for — says that rule keeps the note-free bulk read off
the MCP surface *so that widening the taste layer's readership stays an ADR-level decision*. It is
widened here to `census`, and this paragraph is that decision.

**`readRatings`, never `readAll`.** The map is `Map<String, Integer>` and has nowhere to put a note,
so the census structurally cannot see one — the same fence that lets the recommender hold the store
at all (issue #85). `onlyTheRatingsToolReadsEveryRating` and `onlyTheRatingsToolReadsANote` are
untouched: the note-carrying reads stay the listing tool's. What the census emits is a histogram, and
[ADR 33](0033-taste-layer-separation.md)'s "never logged" is satisfied for the reason
`RatingsAreNeverLoggedTest` already gives about the listing tool's own log lines — no row names an
entity, so no row can attribute a rating to one.

## Alternatives considered

- **Answer the three questions with a throwaway probe and delete it.** How every measurement in ADRs
  55, 57 and 59 was taken. Rejected because a probe answers once: ADR 57's whole decision was that
  the floor should report itself on every run, precisely because a manual reading is one nobody
  repeats. The three questions recur, and a committed tool is the difference between a number and a
  number somebody can produce again next month.

- **Add a `--census` view to `exportGraph`.** No new package, no new fence, and the fold is already
  there. Rejected on the reason ADR 41 and ADR 43 both give for a sibling rather than a mode: the
  exporter writes files that name entities and this writes counts that name none, and a tool with two
  outputs of different sensitivity cannot have a fence that means anything about either. The privacy
  test would have had to assert over one code path of a class whose other path is required to emit
  labels.

- **Fold the log a third time, inside `census`.** The fold is a few dozen lines and the dependency
  between two dev tools would not have to be argued for. Rejected outright, and it is the one
  alternative with a defect already on the record: two folds of one log drifted, which is what
  `BothFoldsAgreeTest` and the move of `Retractions.survives` and `Equivalences.foldEndpoints` into
  `domain` were for. A third fold puts the drift in the artefact whose numbers get quoted.

- **Read the SQLite schema directly, since the census only counts rows.** Fewer objects, and a
  `SELECT count(*)` per number is faster than folding six figures of assertions. Rejected twice
  over: it makes the census a second reader of a store [ADR 18](0018-graph-engine-gremlin.md) and
  [ADR 24](0024-sqlite-assertion-log.md) exist to keep replaceable, and getting a single number right
  would mean re-implementing retraction, merge folding and kind re-derivation in SQL —
  `SqliteAssertionLog.readRow` already owns decoding a row, and a second copy of a rule is the defect
  this repository keeps finding. So `CensusCli` opens the two stores and hands the `port` interfaces
  down, exactly as `RatingsCli` does.

- **Print with `System.out`, which is what "prints" ordinarily means.** Rejected because
  `ArchitectureTest.nothingWritesToStandardOut` bans it project-wide
  ([ADR 28](0028-mcp-transports.md), [ADR 30](0030-structured-logging.md)) with one named exception that is not a dev tool, and a rule
  that admits a second exception for convenience stops being a rule. The lines go through SLF4J at
  `info`, one call per line, the route `ExportCli` and `RatingsCli` already use.

- **Write the census to a file, as `export`, `ratings` and `recommend` all do.** Consistent, and one
  fewer thing to explain. Rejected because those three write a file for a reason that inverts here:
  ADR 33 keeps affinity out of every log line, so their output must not be logged. This output is
  counts alone. A file would leave the one artefact designed to be pasted sitting on the owner's disk,
  and would add a required flag that buys nothing.

- **Let `SEGUE_DB` satisfy `--db`, or give the census the same default database the server resolves,
  since nothing is written.** Kinder, and the failure mode is a re-run rather than a permanent row.
  Rejected on the clause above: the variable cannot tell the owner apart from an agent running as the
  owner, and what is at stake is not damage to the log but a decision about the owner's own data being
  taken by an inherited environment. A default that a flag overrides would be the same decision made
  silently, which is why the absence of one is fenced rather than merely intended.

- **Hold the counted groups in `Map.copyOf` / `Set.copyOf`**, the immutable factories the rest of the
  project reaches for. Rejected because their iteration order is unspecified and salted per JVM, so
  two runs over one unchanged log would print two orders and a diff between them would be noise —
  against [ADR 43](0043-listing-your-own-ratings.md)'s byte-identical contract, and against the whole
  point of an output somebody pastes into an issue and compares with last month's. The counting keeps
  an `EnumMap` in declaration order and `TreeMap`s otherwise, which is the choice `LogProjection` made
  for the same reason (issue #207), and `CensusReport` walks those maps rather than ordering anything
  itself.

- **Report the exact number of entities whose classes the MusicBrainz bridge supplied**, which is
  what issue #227 asked for. **Rejected on the code**, not on cost.
  `MusicBrainzSourceAdapter.toNeighbour` stamps such a claim `wikidata`, with the entity's own qid as
  the reference and confidence 1.00, and its Javadoc says the claim "is byte-identical to what
  `ReverseClaims` and `WikidataEntityResolver.fetch` would have produced for the same entity, because
  it is the same claim from the same source". [ADR 61](0061-the-bridge-returns-classes.md) is why:
  stamping it `musicbrainz` would attribute Wikidata's classes to a database that states none. There
  is no marker in the log, deliberately, so the census reports what the log can answer — how many
  entities a MusicBrainz-sourced edge names, and how many of those carry classes at all — and says so
  where it counts them.

## Consequences

- **The three open questions become answerable, and none of them is answered here.** This ADR ships
  the instrument. Reading it and deciding something about the floor, the merges or the bridge is
  separate work, and an amendment written before the tool has been run would record a decision nobody
  took.

- **There are now two dependencies between dev tools, not one.** The developer guide's layering
  section said "the one dependency between two dev tools" and has been corrected. `rate → recommend`
  and `census → export` are both a tool borrowing a read-only sibling's work rather than copying it.

- **One more `*Cli`, one more `JavaExec` task, and one more entry in `DEV_TOOL_PACKAGES`** — which is
  what puts `..census..` into every sibling's fence at once, and what makes `PackageListsTest` hold
  the constant to the tree in both directions (issue #165).

- **The census reads the log twice**, once for the raw rows and once through `LogProjection.of`. The
  alternative was an overload on `LogProjection` taking an already-read list, which widens another
  package's API for a dev tool's convenience. Accepted; the tool is run by hand.

- **The lines carry Logback's default prefix, and that is a limit rather than a feature.**
  `logback-spring.xml` is loaded by Spring Boot and this tool has no Spring context, so under the
  `JavaExec` task Logback falls back to its built-in console appender: every line reaches **stdout**
  carrying Logback's default layout — a timestamp, the level, the thread and the logger among them —
  in front of the census's own text. Logback owns that layout and this ADR does not restate it. The
  *content* is still aggregates alone, which is what `CensusIsSafeToPasteTest` asserts, and the
  prefix is the same on every line in practice, because every line comes from one logger at one
  level on one thread — so the aligned column survives being pasted, prefix and all. Stripping it would mean a logging
  configuration of this tool's own, which is a change to how the whole project logs (ADR 30) for a
  cosmetic gain.

- **"Safe to paste" is a claim about the report, and a failed run is not the report.** Two paths put
  text on the terminal that no assertion covers, and both are shared with `ExportCli` and
  `RatingsCli` rather than new here: a refusal names the database path it was given, and an
  exception prints a stack trace — `SqliteAssertionLog`'s "cannot decode assertion log row at
  sequence N" over a cause such as `Qid`'s "qid must look like Q12345, got: …", which puts a
  malformed row's own id text on screen. `CensusIsSafeToPasteTest` captures Logback events and a
  thrown exception is not one, so the test cannot see either. The leak surface is a qid or a class
  id — nothing in the decode path validates a label or a note — and the answer is the ordinary one:
  read what you paste when a run has failed. Narrowing the guarantee is the decision; catching and
  rewriting every adapter's exceptions for one dev tool is not.

- **Nothing here makes the owner's numbers public.** The tool produces text that is *safe* to paste;
  what is pasted, and where, stays the owner's decision — which is the whole reason `--db` is typed
  per invocation.

---

**Amendment (2026-09-04, issue #248): the "nothing matching `\bQ\d+\b` anywhere" clause of *Every
value is an integer, and that is what makes ADR 51 testable here* above is narrowed by one prefix,
because a class identifier is vocabulary.** The census gained a section counting `CONCEPT` nodes by
the class they state — the map of `KindMapper`'s gaps, which nothing reported and which 17,099
`CONCEPT` nodes make worth having. Printing counts against ranks and leaving the owner to look the
classes up was the alternative, and it fails on use: a rank is not something you can look anything up
by, so the section would answer a question nobody could act on without a second tool.

**The ruling is that a Wikidata class id is vocabulary rather than the owner's data.** That is not
new ground: this decision already admits two kinds of raw text off the log on exactly that basis, the
edge type codes in `of type …` and the source ids in `backed by …`. A class id is the same kind of
thing — Wikidata's shared name for a category, stated by a source about an entity — and it is
Wikidata's name for a category rather than an id of the owner's, and the row attributes nothing to
the entity it names. What the row adds to it is a count, which is an aggregate. ADR 51's line is
where that lands, and ADR 51 itself is untouched: it remains the rule for prose, held by review.

**The carve-out is one prefix wide, and that is enforced rather than intended.**
`CensusIsSafeToPasteTest` strips exactly one leading `^  class Q\d+` and applies the unchanged clause
to what is left, so a second qid on an allowed row fires, a qid on any other line fires, and a line
carrying the section's words without the two-space indent `CensusReport` gives every counted line
fires. Three planted lines assert all three, and each is proved able to fail by a matching plant in
the guard — widening the prefix past the section, short-circuiting the whole row, and dropping the
anchor. The test also asserts that a class row was actually printed, so the narrowed clause can never
be satisfied by a run that emitted no id at all. `EvaluationIsSafeToPasteTest`'s own clause is a
separate guard over a separate report and is **not** narrowed.

**The residual, stated rather than mitigated.** A class stated by exactly one node is the row that
comes closest to naming an entity. The section prints the ten commonest classes, so ordering by count
descending is what pushes such a row out — that is the load-bearing reason for the cut, ahead of
brevity — but on a graph small enough that ten rows is the whole distribution, a count of one can
still reach the output. Nothing hides it, and the answer is this decision's own: `--db` is typed per
invocation because whether to publish is the owner's decision, taken each time.

A second residual: `instanceOf` is whatever P31 stated, and nothing validates that the target is a
class, so a P31 naming a specific entity — a modelling error, or an item like an award that Wikidata
really does use in the class position — would be printed as a class row, and this clause can no
longer red on it the way it does for an entity-shaped edge type code. What the row still adds is a
count over the owner's nodes, and the id itself is Wikidata's, public either way.

**What was rejected.** Printing a label beside the qid from `ClassLabels`: its fallback prints the
bare qid, so on exactly the classes this section exists to surface — the ones nobody has met — it
would print the qid anyway and add a column of blanks, while putting a curated English string into an
output whose guarantee is that it interpolates nothing but integers and one identifier. And
suppressing rows below a minimum count: it would hide the residual above rather than report it, and
it would make `distinct classes` the only honest number in the section.
