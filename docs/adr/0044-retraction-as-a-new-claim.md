---
status: Accepted
date: "2026-08-27"
topic: retraction-as-a-new-claim
tags: [project, provenance, data, tooling]
supersedes: []
related: [assertion-log-source-of-truth, bitemporal-time-model, sqlite-assertion-log, mcp-tool-surface, store-p31-and-rederive-kind-at-projection, taste-layer-separation, bulk-seeding-as-a-dev-tool, graph-exporter-views-and-formats, listing-your-own-ratings, layering-and-archunit]
---
# 44. Retract by appending a claim, and honour it in both projections

## Context

Add the wrong QID, expand it, and its edges are permanent. That is not a missing button; it is
what ADR 19 buys. The log is the source of truth, the graph is a projection, and nothing in the
system can take a claim back.

It came within one command of mattering. A seeding mapping had `The Highwaymen` as `Q7739667`,
which is a group of Florida landscape painters rather than the country supergroup; it was caught
only because the QIDs were checked against Wikidata before the expansion ran. Reproduced against a
copy of the real database while writing this: adding and expanding that QID puts a `GROUP` node
called "The Highwaymen" in the graph with four painters as `MEMBER_OF`, and every route through it
is quietly false.

The obvious fix — delete the rows — is the wrong one, and stating why is the whole point of this
decision. Deleting would make the log a mutable store that merely happens to be append-shaped, and
every guarantee resting on ADR 19 would become conditional on nobody having deleted anything:
replay reproducing the graph, the audit trail, ADR 42's offline re-derivation. A wrong claim is
data about what a source said, not corruption to be scrubbed. That we later concluded it was wrong
is itself a fact worth keeping.

## Decision

**A retraction is a claim, appended like any other. The log is never edited or rewritten.**

`Retraction` joins `NodeAssertion` and `AssertionRecord` in the sealed `LoggedAssertion`, and the
projections honour it by omitting what it retracts. This is exactly the shape ADR 42 established
for node kinds: a later row changes what the projection *says* without rewriting what was
*recorded*. `GraphProjectorTest.theLogStillHoldsEveryOriginalClaim` is that sentence as a test.

### The four questions the decision left open

**1. Granularity: the entity.** A retraction of `Q7739667` reaches that entity's node claims and
every edge claim with it at either end.

Not one edge: the case this exists for is a wrongly-*resolved* entity, where the whole expansion is
wrong and picking the edges off one at a time is a hundred commands and a chance to miss one.

Not one expansion, and that was the tempting answer — the issue's own framing is "everything this
expansion asserted". Two things ruled it out. First, **it would leave the wrong entity in the
graph**: the node claim came from `add_entity`, not from the expansion, so retracting the expansion
strips the edges and leaves a `GROUP` called "The Highwaymen" still in `search_entities`, still
rateable, still an argument to `expand_entity`. The identity is the thing that was wrong. Second,
**an expansion is not identifiable from what is recorded**, and making it so would be a schema
change of its own. Checked against the real log rather than assumed: `source_ref` is per-claim (a
Wikidata statement GUID forwards, `wdqs:<subject>:<property>:<object>` in reverse) and `asserted_at`
is shared by every claim of one expansion — one instant for both passes, deliberately, per ADR 20 —
but nothing distinguishes two expansions that happen to land in the same microsecond, and nothing
at all identifies an expansion in the 131,000 rows already written before this ADR existed. The
entity is the unit the operator actually knows, and it is expressible against the log exactly as it
already stands.

**It does not cascade, and that is deliberate.** Retracting `Q7739667` leaves the four painters in
the graph as nodes with no edges. Their node claims are not wrong — Wikidata really does say
`Q61380275` is a human called Alfred Hair — and cascading would delete neighbours that other,
correct expansions may also have reached. An orphan node is invisible to `find_paths`, harmless to
`get_entity`, and appears in a `full` or `subgraph` export. The alternative trades a visible,
harmless residue for an invisible, unbounded blast radius.

**2. Provenance: none.** `Retraction` carries `qid`, `reason` and `retractedAt`, and no
`Provenance`.

`Provenance` answers "which source told us this, and how much do we believe them". A retraction has
no source and is not a matter of belief — it is the owner's own act, the same first-person shape
ADR 33 gives affinity, and ADR 33's argument applies unchanged. Filling the record in would have
meant three fields that are not true: `sourceId` of `"operator"` carries no information in a
single-writer system (ADR 24), `sourceRef` is documented as "the citation you can click" and a
reason is not one, and `confidence` of 1.00 means "a Wikidata statement with a reference".

What a first-person act *can* honestly carry is what the record holds. **`reason` is required** and
validated non-blank: the value of keeping a retraction in an append-only log is that it records
both that we concluded something was wrong and what the conclusion was, and there is no editing one
afterwards to add the second half. **`retractedAt` is the one dimension of ADR 20 a retraction
has** — it is not a claim about the world, so it has no validity interval and there is nothing for
`validFrom`/`validTo` to mean.

Consequence, taken deliberately: `LoggedAssertion.provenance()` moves off the sealed interface onto
the two claim types. Nothing called it polymorphically. Anything that wants a row's provenance now
has to say which kind of row it is holding, which is the correct obligation.

**3. Where it is invoked: a fourth dev-side tool, `./gradlew retractEntity`.** Still six MCP tools.

ADR 26 pins the surface at six; ADR 40, ADR 41 and ADR 43 each declined a seventh. This is the
heaviest of those refusals, and the reason is not tool-count arithmetic. **The caller of an MCP tool
is a language model, and a model that proposes retractions of its own is a different and much larger
question.** ADR 26 already holds back `assert_edge` because a model cannot distinguish a plausible
relationship from one it knows; a tool that *removes* claims hands the same faculty a stronger
verb. That question is deliberately left closed here — this ADR does not decide it either way, and
`ToolSurfaceTest.retractIsNotATool` fails the build if somebody reopens it in passing.

ADR 26 needs **no amendment**. Its consequences already read "nothing in the surface can currently
retract a claim; retraction is expressible against the log but deliberately unexposed", which is
what this ADR makes literally true rather than aspirational.

The tool is the first of the four that **writes**, so its fence is shaped differently and says so:
it may append a retraction through `IngestService`, and it may not hold a `GraphStore` (named as a
type, not just as two forbidden calls), an `AffinityStore`, an engine, a sibling tool or a network.
`IngestService.retract` is static, taking the log, precisely so that satisfying a constructor could
never become the reason this tool held a graph.

**It reports before it appends**: the entity's **label** and how many node and edge claims will stop
projecting, while the log is still untouched. Borrowed from `ExportRun`, for a stronger reason — an
export leaves a file to inspect and this leaves a permanent row. Naming the label is the safety
feature that matters, because a retraction of the wrong QID is the very mistake this issue is about,
one level up. `--dry-run` stops after the report. **Nothing to retract is refused, not recorded**: a
mistyped QID would otherwise append a retraction that does nothing, forever, reading like a decision
somebody made.

**4. A retraction is not retracted. Append a fresh claim instead.**

A retraction reaches **backwards only** — it retracts the claims that precede it in the log, and
claims appended after it stand. So an entity comes back by being added and expanded again, and
nothing special happens on the way: the new claims are simply newer than the retraction. There is no
un-retract verb, no tombstone to clear, and no third state. An entity that came back can be retracted
again by the ordinary path, which `RetractRunTest.canRetractSomethingThatCameBack` pins.

**Backwards by position in the log, not by `assertedAt`.** Sequence order is a guarantee the port
already makes and it is total, where assertion time can tie — a whole Wikidata expansion shares one
instant by construction — and can legitimately run behind the append carrying it. Position asks the
question the decision actually means: what had we already been told when this was decided?

### The rule lives in one place and both projections call it

`Retractions` is in `domain`, beside `Retraction`, and `GraphProjector` (boot replay, ADR 24) and
`LogProjection` (the exporter's fold, ADR 41) both ask it the same question about the same list.
This is ADR 42's argument repeated, and the failure it prevents is worse here than for node kinds:
a picture that still shows edges the graph has dropped is not a stale detail, it is a false record
of what is in the graph, and an export is the artefact somebody keeps and opens in Gephi weeks later.
`BothFoldsAgreeTest` runs one deliberately awkward log — retracted entity with edges in and out, an
untouched entity, a re-add, a second entity retracted after a re-add — through both and compares the
node and edge sets. It was confirmed to fail when either fold is changed to ignore the rule.

The rule sits in `domain` rather than beside either caller because, unlike `KindMapper`, a
retraction is nobody's vocabulary. It is the log's own.

**`IngestService.apply` throws if it is ever handed a retraction.** Unreachable through either
projection, and a guard rather than a path: reaching it means a caller replayed the log without
applying the rule, which would produce a graph still holding edges somebody took back out. Silently
ignoring it is the one response that would hide exactly that.

### A migration, and this time a real one

**Yes, this needed one, and it is `ALTER TABLE assertion ADD COLUMN reason TEXT`**, guarded by
reading `PRAGMA table_info` rather than by a version table — "does this column exist" has an exact
answer here, where a version number is a second source of truth a hand-edited file can contradict.
`CREATE TABLE IF NOT EXISTS` is a no-op against an existing table, so without this a database
written before today would silently keep a schema with no `reason` column and fail on the first
retraction.

ADR 42 shipped a schema change with no migration and said in as many words that the next one gets a
real path, because the world facts are regenerable and a rating is not. This is that next one, and
it was **tested against a copy of the live database** — 131,672 assertions, no `reason` column — as
well as from a fixture that writes the old schema by hand. SQLite performs `ADD COLUMN` by rewriting
the schema rather than the rows, so it costs the same on an empty file and on a hundred thousand of
them.

Two `NOT NULL` columns predate this row type and mean nothing for a retraction: `source_id` and
`confidence`. They are filled with fixed values (`(retraction)` and `1.0`) and never read back for a
`RETRACT` row. Removing a `NOT NULL` in SQLite means rebuilding the table — a real rewrite of the
whole log, to relax a constraint on rows that simply have nothing to put there. The literal is named
rather than `operator` or blank so that anyone reading the table in a SQL client sees a
discriminator and not a source they might go looking for.

## Alternatives considered

- **Delete the rows** — the smallest change, no new row type, no fold rule, and nothing to explain
  to a future reader of the schema. Rejected in the issue's own decision comment and restated here
  because it is the alternative everybody reaches for first: it makes ADR 19 conditional on nobody
  having deleted anything, and the guarantees it silently weakens (replay, audit, re-derivation) are
  the ones nobody would notice losing until they needed them.

- **A `retracted` flag on the existing rows** — one boolean, no new type, and the fold is a `WHERE`
  clause. It is deletion wearing a hat: the log's rows become mutable, "what we were told" and "what
  we currently believe" collapse into one record, and there is nowhere to put the reason or the
  moment. Everything this ADR keeps is in the *separateness* of the retraction row.

- **Retract an expansion rather than an entity** — closest to how the mistake is actually made
  ("that expansion was wrong"), and rejected above on two grounds: it leaves the wrong identity
  behind, and an expansion is not identifiable in the log without a schema change that would help no
  row already written.

- **Retract one edge** — the finest grain, and useful for a *different* problem: a single edge from a
  correct entity that is wrong. Nothing here forbids adding it later, and it is deliberately not
  built now, because the case in hand needs a hundred of them and one missed edge is a false route
  that looks sourced.

- **Cascade to the neighbours the expansion discovered** — the tidiest-looking graph afterwards, and
  it deletes nodes that other correct expansions also reached, with a blast radius nobody can see at
  the moment they type the command. Orphans are visible and harmless; this is neither.

- **Give the retraction a `Provenance`** — uniform with every other row, and the storage would need
  no new column at all. It buys that uniformity by writing down three things that are not true, in
  the one record whose entire purpose is honesty about a past mistake.

- **A seventh MCP tool, `retract_entity`** — the model could fix its own error in the conversation
  where it noticed it, which is genuinely where the mistake surfaces. It is also the first tool that
  would let a model *remove* what a source said, on a surface that deliberately withholds
  `assert_edge`; that trade deserves its own ADR and its own evidence, not a row added while
  building the mechanism.

- **Honour retractions in only one projection and rebuild the other from it** — one fold instead of
  two, and it is what `LogProjection` exists to avoid: bounded views traverse a real projection,
  `full` and `subgraph` do not, and the port has no enumerate-all method (ADR 41). One shared rule
  called twice is the shape that already works here.

- **Compare `assertedAt` instead of log position** — reads better in an ADR about a bitemporal
  model, and it ties the decision to a clock that is allowed to tie, is reset wholesale by a re-seed
  (ADR 42's own note), and can legitimately precede the append that carries it.

## Consequences

- **The wrongly-expanded entity has a way out, and it costs one command.** Measured on a copy of the
  real database: retracting `Q7739667` removed one node claim and four edge claims from the
  projection, the log grew by exactly one row, all five original claims were still in it, the boot
  replay applied 131,667 of 131,673 rows, `get_entity` reported the entity unknown, and a
  neighbourhood export of one of the painters showed him still present with no edges.

- **A running server is stale until it restarts.** A retraction changes the projection, and
  `GraphStore` cannot remove anything — widening the port that exists to keep the engine choice
  reversible (ADR 18), for a dev-side tool, is what ADR 41 already refused. The tool says so in its
  last line. This is the same contract ADR 24 gives replay, reached from the other side.

- **Replay does slightly less work than the log has rows**, and the numbers now differ by design.
  "Replayed N assertions" is no longer the row count, which is exactly the visible signal that a
  retraction took.

- **The ratings tool is deliberately not part of this.** `Labels.forQids` reads node claims straight
  out of the log without applying the rule, so a rating whose entity has been retracted still lists
  under the label the log last gave it. That is a decision, not an oversight: a retraction is a
  statement about the world-fact layer and a rating is not (ADR 33), affinity is the one thing here
  that cannot be regenerated (ADR 43), and turning a row into `(not in the graph)` with no
  explanation would be worse than showing the name of a thing you rated and later took back out.

- **Retraction is a fourth relationship with the data, and the fences now say which is which.**
  `seed` may not open a store; `export` may read one and build a projection; `ratings` may read two
  and project nothing; `retract` may append exactly one kind of row and may not hold a graph. Four
  tools, four rules, which is the pattern ADR 41 and ADR 43 established.

- **`Retraction` is deliberately outside the fold's data model.** It is not projected, so nothing
  downstream — `EdgeRecord`, `NodeRecord`, a view, an export — grows a field for it. Anything that
  wants to know what was retracted reads the log, which is what ADR 19 says anyway.

- **A retraction cannot be undone by a smaller act than re-adding.** Adding and expanding an entity
  again costs a Wikidata round trip and re-stamps `assertedAt`, which ADR 20 treats as real. On a
  wrongly-resolved entity that is the correct cost — there was nothing worth keeping — and it is a
  genuine cost, recorded here rather than glossed.
