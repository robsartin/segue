---
status: Accepted
date: "2026-08-26"
topic: graph-exporter-views-and-formats
tags: [project, tooling, graph, privacy]
supersedes: []
related: [bulk-seeding-as-a-dev-tool, assertion-log-source-of-truth, path-ranking-by-confidence, taste-layer-separation, mcp-tool-surface, layering-and-archunit, privacy-and-data-handling]
---
# 41. Export bounded views through a format-blind selection layer, from a sibling of `seed`

## Context

Bulk seeding (ADR 40) loaded a real graph — 25,815 nodes and 30,307 edges — and there is no
way to look at it. `get_entity` and `find_paths` answer one question each in a conversation;
neither shows shape.

Three things constrain the answer, and each one rules out the obvious move.

**The whole graph is not a picture.** Measured on the real graph: 25,815 nodes, 30,307
merged edges. Graphviz's `dot` degrades in the low thousands, and a hairball of that size
answers no question anyone has.

**The stated destination is an interactive app, not a file.** So whatever chooses *what
goes in the picture* is the durable part, and whatever writes DOT or GraphML is a tail that
will be replaced. If format concerns leak into the query code, the work is thrown away the
day a UI wants JSON.

**`seed` cannot host it.** ADR 40's whole safety argument is
`ArchitectureTest.seedNeverOpensAStore`: the seeding tool reads a private list of names and
must not be able to open the database even to read it. An exporter's entire job is reading
the database.

## Decision

- **A sibling package, `export`, with its own Gradle task.** `./gradlew exportGraph`, plain
  Java, a `main` behind a `JavaExec`, exactly the shape ADR 40 gave `resolveNames`. Two
  dev-side tools with opposite relationships to the store, two packages, a rule each. It is
  **not** an MCP tool: ADR 26 pins the surface at six, and drawing a picture is an
  operator's job rather than a model's.

- **View selection knows nothing about output format. This is the decision that matters.**

  - `ViewSelector` produces a `GraphView` — a description, a list of `ViewNode`, a list of
    `ViewEdge`. It never mentions DOT, GraphML, XML, a file or a `Writer`.
  - `ViewWriter` consumes a `GraphView` and knows nothing about a graph, a store or a query.
    It is a pure function of the view: same view in, same bytes out.
  - The two meet in exactly two places, both trivial: the `OutputFormat` enum, which maps a
    command-line word to a writer, and `ExportRun`, which selects, optionally decorates,
    reports the size and hands the result over.

  A future UI reuses `ViewSelector` unchanged and adds a third `ViewWriter`. That is the
  test of whether this decision was worth making, and it is why the split is stated here
  rather than left as a matter of taste.

- **Four bounded views, selectable from the command line.** Sizes are from the real graph:

  | view | contents | measured |
  |---|---|---|
  | `route` | one `find_paths` result, hop by hop | 5 nodes, 4 edges |
  | `neighbourhood` | one entity and its edges, to a depth | 78 nodes at depth 1; 179 nodes, 227 edges at depth 2 |
  | `subgraph` | only the entities on a supplied list, and the edges between them | 179 nodes, 256 edges over one such list |
  | `full` | everything, behind `--all` | 25,815 nodes, 30,307 edges |

  **`subgraph` is the interesting one.** Fed the seeding tool's mapping file it shows the
  acts actually on the list and how they connect, with every discovered intermediate
  stripped. It reads a QID list by a shape rather than a schema — the first
  comma-separated field on a line that *is* a QID — so a bare list and ADR 40's mapping
  file both work, and it does not depend on `seed`. That rule also declines the QID a
  *review* row quotes in its prose, which a looser "first `Q\d+` on the line" would have
  exported as if it had been chosen.

- **Bounded views read the graph; `full` and `subgraph` read the log.** `GraphStore` has no
  enumerate-all method, and adding one would widen the port that exists to make the engine
  choice reversible (ADR 18) for the benefit of a dev tool. ADR 19 makes reading the log the
  correct answer rather than merely the cheap one: the log is the source of truth and the
  graph is its projection. `LogProjection` performs the same fold the graph performs —
  assertions over one `(from, type, to)` collapse into one edge carrying every provenance,
  different types stay separate edges — so the two paths cannot disagree about what an edge
  is.

- **`route` goes through the real traversal and the shared `PathRanking`.** An exported
  route is the route `find_paths` returns, degree lookup and all, not a second
  implementation that can drift. Verified against the real graph: Huston ↔ Arthur exports
  the two-specific-awards route ADR 31's amendment describes, not the Walk of Fame hub.

- **`full` is refused without `--all`, and every view reports its counts before writing.**
  The refusal is at argument-parsing time, before a store is opened. The counts reach the
  operator while the output file still does not exist, which is what `ExportRunTest`
  asserts — the useful moment to learn a picture has 30,000 edges is before it is written,
  not after.

- **Affinity is excluded by default, and including it warns at the point of export.** ADR 33
  is the reason a world-fact export is uncontroversial: "the world graph can be shared,
  exported or made public without carrying personal data." A rating is the other layer.
  `--include-affinity` puts `AffinityOverlay` in the pipeline, and the first thing the tool
  says — before the view exists, long before the file does — is that the output is personal
  data under ADR 33 and issue #37 and belongs outside the working tree. `*.dot` and
  `*.graphml` join `*.csv` and `*.db` in `.gitignore` as the second lock, not the first.

- **`ArchitectureTest.theExporterOnlyReads`**, the mirror of
  `onlyIngestAppliesClaimsToTheGraph`. No class in `export` may call `GraphStore.record`,
  `GraphStore.upsertNode` or `AssertionLog.append`, **or depend on `IngestService` at all**.
  The second half is the one nothing else says: the first half is already covered from the
  other direction, but without the second a class here could route a claim through the one
  legitimate writer and break no rule. `GraphProjector` is deliberately not forbidden — the
  bounded views need a projection, and the exporter builds one by replaying the log into a
  throwaway in-memory `TinkerGraphStore`, exactly as the application does at boot. What the
  rule guarantees is that nothing *durable* changes.

- **Two formats, for two different jobs.** DOT carries `NodeKind` as node shape and the type
  code as an edge label; it is for looking at, and the documentation says to use `sfdp` or
  `neato` rather than `dot` above a few hundred nodes. GraphML is for working in — Gephi and
  Cytoscape survive scale where Graphviz will not — and it carries the attributes that make
  filtering possible: `kind` and `label` on a node, `typeCode`, `confidence` and `sourceId`
  on an edge. Confidence earns its place because ADR 31 ranks by the weakest hop and demotes
  hub intermediates, so "show me every edge below 1.00" is exactly the question these files
  get opened to ask.

## Alternatives considered

- **Put it in `seed`, since that is where the other dev tool lives** — one package for
  "tools", and it would have required relaxing `seedNeverOpensAStore` to permit `sqlite`,
  `tinker` and `ingest`, which is the entire fence that makes a tool reading a private list
  safe. The rule would have survived as words.

- **One class per format, reading the store directly** — fewer types, no intermediate model,
  and the query logic would exist twice, once per format, drifting; a UI would inherit
  neither copy. This is the decision above, seen from the side where it was not made.

- **Add `nodes()` and `edges()` to `GraphStore`** — the natural way to enumerate, and it
  widens the port that exists to make the engine choice reversible, obliging both adapters
  and the contract tests to carry a method only a dev tool wants. ADR 19 already provides
  the enumeration.

- **Render the full graph and let the operator zoom** — no view selection at all, and it is
  the thing that does not work: 25,815 nodes is a hairball in Graphviz and a slow one in
  Gephi, and every question anyone actually asks is about a neighbourhood, a route, or a
  list they already have.

- **A seventh MCP tool, `export_graph`** — the model could drive it, and it breaks ADR 26's
  six for an operator's job whose output is a file on a filesystem the model cannot see.

- **Colour nodes by rating, on by default** — the most useful picture, and it makes every
  export personal data under ADR 33 by default, in a public repository, which is the failure
  ADR 40 and issue #37 have already paid for once.

- **A `Graph` model shared with the MCP view records in `mcp`** — one fewer set of records,
  and it couples the exporter to the tool surface's serialisation shape and gives `export` a
  reason to depend on `mcp`. `ViewNode` and `ViewEdge` are six fields between them.

## Consequences

- A future non-MCP HTTP surface (issue #50's follow-up, which reopens ADR 29 and inherits
  ADR 37's `Origin`/`Host` allowlist) adds a `JsonViewWriter` and reuses the selection layer
  whole. If that turns out not to be true, this ADR was wrong.
- The exporter depends on `ingest` — only on `GraphProjector`, only to replay into memory —
  which `seed` is forbidden. Two dev-side tools now have visibly different fences, and the
  rules say which is which.
- Replaying 56,583 logged assertions to answer a bounded view takes well under a second on
  the real graph, so the projection is not worth avoiding. It is skipped for `full` and
  `subgraph` anyway, because those views never traverse.
- `AffinityOverlay` is caught by `affinityNeverTouchesTheWorldFactLayer` for free: that rule
  matches taste-layer types by *name* rather than by package, because ADR 33's boundary is
  not a package. The fence was not written for this class and is exactly the right one.
- Two numbers now describe the same graph and can look like a contradiction: a depth-2
  neighbourhood of 179 nodes carries 227 edges, and a subgraph over those same 179 nodes
  carries 256. Both are right — the neighbourhood walks outward and never traverses the
  edges *between* two nodes at the frontier, and the subgraph keeps every edge whose ends
  are both on the list.
- The exporter must be re-run to see a change. It is a snapshot tool, deliberately: an
  always-live picture is the interactive app, and that is a different decision.
