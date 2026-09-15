# `expandPromotions --known … --add` — adding what a file names that the graph lacks — design

Issue #328. Written 2026-09-14 against the code on `main` after #327. The first step of the second coverage
sub-project the owner named on 2026-09-12, other domains, which he ordered on 2026-09-14 as: (a) the
non-touring rows already in the original names list, (b) a reading list, (c) a film list. This
design is (a), and it is the batch step every later list needs too.

## The gap this closes

The original names list carried rows Setlist Scout rejected as non-touring — the authors, thinkers
and comedians the seed code notes are the relations this graph is short of (`seed.SeedRow`'s note on
`status`). The seed tool (ADR 40) resolved them to ids in the same mapping file as the touring acts,
and there they stopped: the known-list file the recommender, the deck, the census and the harness
read is Setlist Scout's export of the touring acts, so nothing ever added the rejected rows to the
graph, expanded them, or dealt them for rating.

Nothing in the repository adds entities in bulk. The seed tool never writes (ADR 40); the MCP tool
`add_entity` adds one entity per call; and the promotion expander refuses an id the graph holds no
node for, with `unknown entity` as the reason, "it has to be added before it can be expanded"
(`expansion.ExpansionOutcome.Reason`). The gap is exactly that refusal.

## What the owner chose

- **The rejected rows first**, then a reading list, then a film list — each its own issue after
  this one; this design is the mechanism and the first run.
- **They are not "known" until rated.** They are added and expanded so their neighbourhoods exist,
  dealt on the deck, and the ones rated at or above `KnownList.PROMOTION_RATING` join the known list
  by the promotion rule that already exists (ADR 48). The touring file stays the file every other
  tool reads.
- **An `--add` switch on the expander's `--known` run**, not a separate tool and not the MCP by
  hand.

## One add rule, in `expansion`

The fetch-and-record that `mcp.SegueService.addEntity` does today — check the id's shape, ask the
Wikidata resolver for the entity, record the node claim through `IngestService.record`, which is an
upsert — moves into `expansion` beside `EntityExpansion`, as one class (working name
`EntityAddition`) with one method taking a qid and returning a small outcome: **added**, **no such
entity** (the resolver returned nothing), **source unavailable** (the resolver threw
`WikidataUnavailableException`), **not a qid**, and **local entity** (a minted id,
`LocalEntity.isLocal`, refused before the resolver is asked, as the expander refuses it). The
outcome carries the recorded `NodeAssertion` when there is one, so the MCP tool can still build its
view from it.

`SegueService.addEntity` calls the rule and maps each outcome onto the tool result it returns today,
word for word; its existing tests are the control that nothing observable changed. The expander
calls the same rule and tallies. `IngestService.record` stays the only write, so
`ArchitectureTest.theExpanderWritesThroughIngestAlone` holds as it is, and `expansion` is already
inside the expander's allowed imports and outside the dev-tool set, so no fence moves.

## The switch

`expandPromotions --db <db> --known <file> --add [--dry-run] [--max-new-edges <n>]`.

- **Population.** The file's ids through the merge fold, minus those `Expanded` covers, as a
  `--known` run composes today. With `--add`, the remainder splits: ids the graph holds a node for
  are expanded as now; ids it holds none for are **added first, then expanded, in the same pass, in
  the file's order**. Without `--add`, a missing id is refused as `unknown entity` exactly as today,
  so no existing run changes shape.
- **Refusals.** `--add` without `--known` is refused with the usage message; so is `--add` with
  `--rated-since` or with `--second-hop`, in the words the existing pairwise refusals use — those
  two populations are drawn from the graph, so nothing in them can be missing, and only a file can
  name an entity the graph lacks. The usage string gains `[--add]` after `--known`.
- **The dry run fetches nothing.** It counts the ids it would add and prints them as one more
  preflight row, `to add`, so `considered` equals `in the graph` plus `minted` plus `to add` and the
  runbook's arithmetic still closes. `Preflight` gains that integer; runs without `--add` print
  nothing new, so every existing block stays byte-identical.
- **The block** gains one row under `promotions`, `added`, counting entities the run added before
  expanding them. An id Wikidata has no entity for is counted under `refused, by reason` as
  `no such entity`, beside the existing reasons; a source outage on the add is a `failed` like any
  other expansion failure. `ExpansionTally` gains the integer; `ExpansionOutcome.Reason` gains the
  constant. Aggregates only, no label and no id, as every row already is.
- **The `#` clause** for a `--known` run says, when `--add` was given, how many the run added, in
  the clause's existing shape; the basename rule (`support.KnownListInput`) is unchanged.
- **Idempotent** for the same reason a `--known` run is: an added entity that recorded a Wikidata
  seed row is covered next time; one that recorded nothing seed-shaped stays in the population and
  is refreshed by the upsert, which is the floor ADR 63's and ADR 66's amendments already describe.

## The runbook: sub-project 2(a), end to end

A chapter after the `--second-hop` one. Step 0 applies to every writing run.

1. **Derive the file.** The mapping the seed tool wrote has a `status` column; the seed writer
   quotes a field only when it holds a comma, a quote or a newline, so the non-touring rows are one
   line filter on that column, into a file outside the working tree. `QidList` reads the mapping's
   rows as they are (the first comma-separated field that is exactly a qid), so no reshaping.
2. **Census over it.** `graphCensus --known <rejected file>`: `named` against `in the graph` is the
   count the run would add; `never expanded` is what it would expand.
3. **Dry run, then run**, with `--add`. Read `to add` against the census; read `added` in the block.
4. **Census again**, and compare.
5. **A deck session with the rejected file as its own `--known`.** The deck deals what its file
   names that is in the graph and unrated, degree first, with a candidate every fifth card as it
   always does (`rate.Deck`), and nothing already rated. The deck's `--known` is a statement about
   what to deal, and it is per session; it does not make the file the recommender's list.
6. **Everything else keeps the touring file.** `recommend`, `graphCensus --known`, the harness: the
   file with promotions, which now carry whichever new rows were rated highly (ADR 48).
7. **The fourteenth reading** follows on the normal rule, with its own note, after the deck session:
   the graph moved and the taste layer moved, so the note says which observations it allows.

## Records

- **ADR 66**, a dated amendment: the switch, why it rides on `--known` alone, the `to add` and
  `added` rows and the `no such entity` reason, idempotence, and the alternatives rejected below.
  ADR 26's six-tool surface and ADR 19's single writer are untouched, and the amendment says so.
- **ADR 40** needs no amendment: the seed tool still resolves and never writes; the mapping is read
  as it was designed to be.
- **The developer guide**: the chapter above; one sentence in the MCP tools' chapter that
  `add_entity` reads the shared rule; the dev-tool table's `expand` row names the switch.

## Testing

Pure TDD, red observed before green, invented ids only, planted positive controls, no wall-clock
assertion, no test on the network.

- **The add rule**, against `StubWikidataServer` as the seed tool's resolver tests are: added
  (a node claim recorded, the upsert refreshing on a second call), no such entity, source
  unavailable, not a qid, local entity refused with the stub never asked (the planted control: the
  stub counting requests).
- **The MCP tool**: the existing offline `SegueServiceTest` and `EntityToolsTest` cases for
  `addEntity` unchanged and green through the move — the control that the tool's words did not
  change. The two live-tagged route tests that also call it are not part of the gate.
- **The expander**: the three refusals of `--add`; the dry run's `to add` row on a file naming an
  id the graph lacks, offline; a run without `--add` still refusing that id as `unknown entity`; the
  `added` row and the `no such entity` reason through a stubbed resolver on a `@TempDir` database;
  `ExpansionReportTest` pinning the new rows' words and that every existing golden block is
  byte-identical.
- **Architecture**: `theExpanderWritesThroughIngestAlone` green, with a planted write around
  `IngestService` observed red and removed; the dev-tool fences unchanged.

## Alternatives rejected

- **A separate `addKnown` dev tool.** Single responsibility, but a new package joins every fence,
  needs its own block, dry run and chapter, and the owner runs two things where one pass does both.
- **Add through the MCP tools by hand.** Hundreds of calls, no dry run, no aggregates block to
  paste, and how it may have been done the first time — which nothing in the repository records.
- **Add on every `--known` run, no switch.** A `--known` run would then create nodes whenever a
  file carried a typo'd or stale id; the switch keeps "refuse what the graph lacks" the default.
- **Treat the rejected rows as known without rating.** The owner chose to rate first; the
  promotion rule already turns a high rating into membership, so no second membership rule.
- **Merge the rejected rows into the touring file.** It would make them known for every tool at
  once, which is the alternative above, and would put the file's provenance beyond the export that
  produced it.

## Out of scope

The reading list (b) and the film list (c): each is a resolution run and then this chapter. The
provenance of how the touring acts were first added. Any change to the deck, the recommender or the
harness. The fourteenth reading's note, written on its day.

## Corrections (2026-09-15, planning for issue #328)

Two factual claims above are contradicted by the code and the guide as they stand. Nothing else here
is withdrawn; the plan at `docs/superpowers/plans/2026-09-14-add-what-a-file-names.md` is written
against the corrections and states any further deviation at the task that makes it.

1. **`SegueService.addEntity` has no local-entity check today, so `LOCAL_ENTITY` cannot be mapped
   "word for word" onto a sentence it already returns.** *One add rule, in `expansion`* says the
   rule's five outcomes map onto the tool result `addEntity` returns today word for word. Four of
   them do. The fifth does not exist: `addEntity` checks `Q\d+` and then fetches, so a minted id
   reaches the resolver and comes back as `no such entity: …`. Refusing it before the fetch is a
   new, deliberate behaviour and needs a new sentence. No existing test covers a minted id through
   `addEntity`, so the four `SegueServiceTest` cases are still the control that the other four
   outcomes did not move.
2. **The developer guide has no MCP-tools chapter.** *Records* asks for "one sentence in the MCP
   tools' chapter that `add_entity` reads the shared rule". `add_entity` is named in the guide only
   as a node label in *The layering*'s diagram; the tools' own descriptions live on `EntityTools`'s
   `@McpTool` annotations. The sentence goes where the guide already says which class owns the
   shared body — *Two-pass ingest* → *The full call, end to end*, beside the existing sentence about
   `EntityExpansion` — and the package table's `expansion` row names the new class.
