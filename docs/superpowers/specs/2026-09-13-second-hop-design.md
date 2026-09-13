# The second hop — the known-list acts the graph cannot place, and the neighbours to fetch for them — design

Issue #319. Written 2026-09-13 against the code on `main` after #318 (the twelfth reading). The
third step of the first coverage sub-project the owner named: the census measures the gap (#311),
the expander closes the part the log says was never fetched (#313, #315), and this reads the part
that is left — the known-list acts that have been expanded and still have no known neighbour within
the recommender's hop limit — and fetches the one ring of entities that could place them.

## The gap this reads

The census on #317 (2026-09-13) reports, for the owner's known list with promotions, that a bit
over a hundred entities have no known neighbour within `Recommendations.MAX_HOPS` hops, against a
few dozen the log says were never expanded. The two sets barely overlap: nearly every isolated act
*has* been expanded. Its own ring is in the graph, and nothing in that ring is known, and nothing
one hop beyond it has been fetched to find out whether the ring connects to anything known.

Isolation of that shape has two causes the census cannot tell apart today: the ring's own
neighbourhoods were **never fetched** (a bandmate, a producer, a label-mate whose record in the
graph is one node and the edge that placed it), or the ring, fully fetched, **really touches
nothing known**. The first is fixable by the shipped expander. The second is a fact about Wikidata
and the owner's list, and the runbook's floor rule (#315) is how it is read. This work does two
things, in the order the owner chose: **diagnose first, then a bounded expansion.**

- **A.** The census says, under each known-list population, how many isolated acts have at least
  one unexpanded person or group beside them, how many have none, and how many distinct unexpanded
  people and groups there are across all of them — the spend a run would make, before any run.
  On request it writes a file naming the isolated acts, so the owner can see which acts the graph
  cannot place.
- **B.** The expander gains a third population: the unexpanded people and groups beside the
  isolated acts, and nothing else. The dry run is the only bound.

## One rule, in `domain`

**`SecondHop`** is a new domain type, the only home of "isolated" and "worth expanding next". It is
built from the fold's nodes and edges (`Map<String, NodeRecord>`, `List<EdgeRecord>` — the two
things `LogProjection` carries), a known population (the list `KnownList.promoted` gives, on its
canonical side), and `Expanded` (on the same side). It reads `Recommendations.MAX_HOPS` by
reference, as the census row does today, and it keeps the one set of kinds it treats as worth
fetching — `NodeKind.PERSON` and `NodeKind.GROUP` — as a constant the two readers and the ADR
amendments cite rather than restate.

It answers three questions, each about the population it was given:

- **`isolated()`** — the members, in the population's own order, that are in the graph and have no
  *other* member within `MAX_HOPS` hops, walking folded edges in either direction. This is exactly
  the walk `census.Neighbours` does today (`in` builds an undirected adjacency over the projection's
  nodes and edges; `reaches` is breadth-first, short-circuiting, and marks the start seen before
  the first hop so a self-loop never counts). **The walk moves to `domain` with the rule**, as a
  package-private helper of `SecondHop`; `census` stops calling it and reads the rule instead, and
  the `no known neighbour within N hops` row becomes `isolated().size()`. `NeighboursTest` moves
  with it and keeps every case.
- **`toExpandBeside(String isolated)`** — the nodes one folded edge from that member whose kind is
  in the constant and that `Expanded` does not cover. A set; an id of any other kind, or of a
  covered node, is not in it. Asking about an id that is not isolated is a caller error and throws.
- **`toExpand()`** — the union of the above over every isolated member, distinct, in first-seen
  order over `isolated()`. At the current hop limit no isolated act shares a neighbour with another
  member, since a shared neighbour is two hops and places both; the union is a set so the answer
  does not depend on the hop limit (correction found at implementation, 2026-09-13).

Nothing here reads a rating, a timestamp or a label. The type is a pure function of its four
inputs, so it takes ordinary unit tests on invented graphs (see *Testing*).

**Why the population with promotions decides isolation for the expander.** The census reports both
populations, and the file-only figure stays visible there. The expander runs on the population
with promotions because that is the recommender's own notion of "known" (`KnownList.promoted` is
what `recommend` seeds from), and an act that is one hop from a promotion is not one the graph
cannot place. Reading it that way means the run reads ratings, as the promotions runs do; the
expander logs the count of ratings it read and nothing else about them, as it does today (ADR 33).

## The census

### Three rows under each population

`KnownListCensus.Population` gains three integers, rendered directly after the
`no known neighbour within N hops` row and nested one level under it:

```
      no known neighbour within 2 hops   111
        with someone to expand beside     ...
        with no one                       ...
        distinct to expand                ...
```

The first two partition the row above them. The third is `toExpand().size()` for that population.
The labels name no kind: the kinds are the rule's constant, and ADR 63's amendment says which they
are by citing it. All three are counts, so *Every value is an integer* holds, `CensusIsSafeToPasteTest`'s
two existing cases cover them by being run over the new rows, and a census run with no flag prints
a block identical to today's plus three rows.

The census already builds the projection and the two canonical populations; it now hands them to
`SecondHop` once per population instead of running the walk itself. `KnownListCensusScaleTest`
stays as the control that the larger question is still answered in the time the smaller one was.

### The file: `--isolated <file>`

`graphCensus --db <db> --known <file> --isolated <out>` writes the isolated members of the
**population with promotions**, one per line, in the population's own order — the file's order and
then the promotions ascending by qid, as `KnownList.promoted` gives it. No sort: a second ordering
rule would be a second thing to keep in step with that one (the #313 spec's reasoning), and the
file is for reading, not for diffing. Each line is four tab-separated fields:

```
<qid>	<label>	<kind>	<count of unexpanded people and groups beside it>
```

The label is `NodeRecord.label` as the graph holds it; where the graph holds none the field is the
qid again, on `NamesFile`'s reasoning (wrong in an obvious way, never dropped). Tabs rather than
commas because labels contain commas and this is a listing to read, not a table to load; the
ratings tool's names file made the same call (*Plain text rather than CSV*, `RatingsTable`).

The first line is a `#` comment naming the file as personal data under ADR 33 and telling the
owner to keep it outside the working tree — `NamesFile.PERSONAL_DATA_HEADER`'s wording, for this
file. It holds entity ids and labels off the owner's list, which is exactly what the census block
exists to never print; **`CensusIsSafeToPasteTest`'s discipline does not apply to the file and must
not be added by analogy** (`NamesFile`'s own note says why). What that test *does* gain is one case:
the report on the terminal with `--isolated` given is byte-identical to the report without it.

`--isolated` without `--known` is refused with the usage message, on the pattern of
`--names` needing `--promotions-off`. The file is written after the report, so a report that could
not be produced writes nothing; an existing file is overwritten, as `NamesFile` overwrites.

## The expander: `--second-hop <file>`

`expandPromotions --db <db> --second-hop <known.csv> [--dry-run] [--max-new-edges <n>]` visits
`SecondHop.toExpand()` for the population with promotions and nothing else. It is the third shape
of `expand.Population`: `SecondHopNeighbours(String file, int isolated)` — the file's basename and
how many isolated acts the population was read beside — so the dry run and the run print a `#`
line saying which file and beside how many acts, as `KnownNeverExpanded` prints its file and its
excluded count. `--second-hop`, `--known` and `--rated-since` name three populations and any two
together are refused with the usage message, in the words the existing refusal uses.

Composition, in `ExpandCli.run`, beside the two branches there today:

1. read the file (`KnownListInput.read`) and canonicalise its ids through the replay's fold;
2. read the ratings, resolve them through the same fold, log the count, and compose
   `KnownList.promoted(named, ratings)`;
3. read the log once (`assertions.readAll()`) and take from it both `Expanded.in(…)` on the
   canonical side and the projection's nodes and edges;
4. `SecondHop.of(nodes, edges, promoted, expanded)`; the population is `toExpand()`.

The population is fixed at that point. Nothing in the run re-reads it, so a run that expands its
first entity does not shrink its own list mid-way. The **next** run reads a smaller population by
the rule alone: every entity the first run expanded is now covered by `Expanded`, so it is no
longer "to expand", and an isolated act the new edges connected to something known is no longer
isolated. No state, no ledger, no `--limit`: the dry run's `considered` is the bound, and an owner
who wants a smaller run reads the file and edits nothing — a smaller run is a later run, after the
census moves.

`considered` is `toExpand().size()`. An entity in it is by construction a node the graph holds,
so this population cannot produce `refused, unknown entity`; the preflight's other refusals apply
as they do to every population. `ExpandRun` still never filters.

## The plumbing move: `LogProjection` to `ingest`

The expander may import `domain`, `expansion`, `ingest`, `port`, `sqlite`, `support`, `tinker` and
`wikidata`, and `ArchitectureTest.theExpanderOpensNothingElse` refuses every other dev-tool package
— `export` and `census` among them. The fold into nodes and edges the rule consumes is
`export.LogProjection`, and it depends on `port.AssertionLog` and `wikidata.KindMapper`, so it
cannot go to `domain`. **It moves to `ingest`**, beside `Replay` and `GraphProjector`: the package
that already owns the boot's fold and that the exporter and the expander may open. The same move
`KnownListInput` made out of `census` for #313.

**Corrected 2026-09-13 while planning, against the code.** This paragraph read "that every reader of
the projection (`export`, `census`, and now `expand`) may open", and `census` may not:
`ArchitectureTest.theCensusOpensNothingElse` bans `..ingest..` for `census` outright, with the reason
*no replay*. Eleven `census` classes import `LogProjection`, so the move fails that rule as it
stands. The rule therefore gains a named exemption for `LogProjection` alone — the shape
`theBootFoldsOnce` already uses for `IngestService` and `nothingWritesToStandardOut` for
`SegueApplication` — carved out by class rather than by package precisely so that the clause the rule
exists for still holds: `GraphProjector`, `Replay` and `IngestService` stay banned, and a planted
positive control proves it. The plan does this in its first task, and records three further places
the code contradicts this section in its own *Premise corrections*.

What moves with it and what the planner verifies:

- the class and `LogProjectionTest`, with imports updated in every reader — `census`, `export`,
  `arch`, and the tests in `recommend`, `retract` and `ingest` that build one — a move with no
  behaviour change, proven by the whole suite;
- `theExportFoldsOnce` exempts `LogProjection.class` by reference; after the move the exemption
  names a class outside the package and the rule still holds. `theBootFoldsOnce` exempts only
  `IngestService`; `LogProjection.of(AssertionLog)` calls `Fold.of`, which that rule does not
  forbid, and its body reads the fold rather than the log-taking statics, so the rule holds
  unchanged. Both are proven by the gate, and the planner reads both rules' `because` clauses
  before deciding whether either wants a word;
- `Census`'s class note, which says the projection is "another package's" fold, and ADR 63's
  section *It counts the exporter's fold, so `census` depends on `export`* — the note is corrected,
  the ADR is amended (below), never edited.

## Records

- **ADR 63** (the census): an amendment dated 2026-09-13 records the three rows, the `--isolated`
  file and why the file is not paste-safe, the walk moving out of `census` into the domain rule,
  and the projection moving out of `export` — naming the alternatives rejected below that belong to
  the census. It cites the constant for the kinds; it prints no number from a reading.
- **ADR 66** (the expander): an amendment dated 2026-09-13 records the third population, the
  three-way refusal, the fixed-at-start population and why re-runs need no state, and that this
  run reads ratings where `--known` does not.
- **The developer guide**: a chapter after *Only what your own list says nothing has expanded:
  `--known`*, in the same shape — step 0 unchanged; census first with `--known` and `--isolated`;
  read the three rows; look at the file; dry-run; run; census again; then the note and the next
  reading on the normal rule (`docs/superpowers/specs/2026-09-04-second-reading-rule-design.md`),
  which this issue does not pre-write. It says the file is personal data outside the working tree
  and is never attached to an issue. `DocumentationLinksTest` and `DeveloperGuideCensusExamplesTest`
  cover it as they cover the chapter above it.

## Testing

Pure TDD, red seen for the right reason before every green, invented ids only (`Q09…` stand-ins,
`Q00…` locals, eleven-digit canonicals; a real class id only through
`StandInQidsDenoteNothingTest`'s allowlist). No wall-clock assertion anywhere.

- **`SecondHopTest`** (domain), on hand-built node maps and edge lists — an isolated act beside an
  unexpanded person is isolated and the person is to expand; beside an expanded person it is
  isolated with no one to expand; an act with a member within `MAX_HOPS` is not isolated and
  contributes nothing even though it has unexpanded neighbours (the planted control for the
  "isolated first" guard); a neighbour of a kind outside the constant is not to expand;
  two isolated acts far apart each contribute their own neighbours, in isolated order; edge
  direction does not matter;
  a member not in the graph is neither isolated nor an error; `toExpandBeside` on a non-isolated id
  throws. `NeighboursTest`'s cases come across unchanged.
- **`KnownListCensusTest`** extends `InventedCensus` for the three rows under both populations, with
  one case where the file-only and with-promotions figures differ (a promotion that places an act).
  `CensusReportTest` pins the three labels and their nesting. `CensusCliTest` covers `--isolated`
  refused without `--known`, the file's four fields, the header line, the qid-as-label fallback,
  and the population order. `CensusIsSafeToPasteTest` gains the byte-identical-with-the-flag case.
- **`ExpandCliTest`** on a `@TempDir` database: `--second-hop` with each of the other two flags is
  refused in the existing words; the dry run's `considered` equals the rule's `toExpand()` for the
  same fixture and prints the `#` line with the basename and the isolated count; the run visits
  exactly that set. `ExpansionReportTest` pins the `#` line's words. The ratings-read count appears
  in the log lines and no qid or score does (the existing `RatingsAreNeverLoggedTest` shape).
- **Architecture**: `theExpanderOpensNothingElse` and the two fold fences stay green through the
  move. The walk is package-private in `domain`, so the compiler is the fence that keeps
  `SecondHop` its only reader; no new rule.

## Alternatives rejected

- **Print the isolated acts on the terminal.** ADR 63: the block is pasted into public issues and
  holds no id and no label. A file the owner asks for by flag, held outside the tree, is the
  ratings tool's answer to the same need (#285).
- **Expand every unexpanded neighbour of every known act.** The population is the whole ring of
  the list, most of it beside acts the graph already places. The question is the isolated acts;
  spend goes where the diagnosis points.
- **A `--limit <n>` on the run.** It needs an order to be meaningful, and an order is a second
  rule. The dry run bounds the spend; a smaller run is a later run.
- **The rule in `census`, read by the expander.** `expand` may not open `census`; that is why
  `Expanded` and `KnownListInput` left it. The rule goes where they went.
- **`LogProjection` to `domain`.** It reads the port and the kind mapper; `domain` reads neither.
- **Let `expand` open `export`.** It is a dev-tool package; the fence is the point.
- **Rebuild nodes and edges in the expander from the graph store.** `GraphStore` lists no nodes,
  and a second projection is the fold done twice — the thing #246 removed.
- **Rows only, no file.** The count says how much; only the names say which acts, and the owner
  asked to see them.
- **Decide isolation on the file alone for the expander.** An act one hop from a promotion is
  placed, by the recommender's own notion of known; expanding beside it is spend without a
  question.
- **Sort the file by id.** A second ordering rule, and a lexical sort of qids is not numeric
  anyway.

## Out of scope

- The thirteenth reading and its note: written the day the owner runs it, by the standing rule.
- The second and third coverage sub-projects (other domains; what Wikidata lacks).
- The two types named `Population` (`census` and `expand`) — a follow-up, not this issue.
- Any change to the recommender, the harness, or `Recommendations.MAX_HOPS`.
