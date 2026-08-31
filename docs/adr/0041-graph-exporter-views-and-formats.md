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

- **Two formats, for two different jobs.** DOT carries `NodeKind` as node shape *and* fill and
  the type code as an edge label; it is for looking at, and the documentation says to use `sfdp` or
  `neato` rather than `dot` above a few hundred nodes. GraphML is for working in — Gephi and
  Cytoscape survive scale where Graphviz will not — and it carries the attributes that make
  filtering possible: `kind` and `label` on a node, `typeCode`, `confidence` and `sourceId`
  on an edge. Confidence earns its place because ADR 31 ranks by the weakest hop and demotes
  hub intermediates, so "show me every edge below 1.00" is exactly the question these files
  get opened to ask.

**Amendment (2026-08-27, issue #57): the `--out` extension is an argument, and a contradiction
is refused.**

Nothing above is withdrawn; the format enum is still the only place a word becomes a writer. What
the original decision missed is that `--out` names a format too. `--format` defaulted to GraphML
and the file name was never read, so

```bash
./gradlew exportGraph --args="--view route --from Q… --to Q… --out $HOME/route.dot"
```

wrote GraphML into `route.dot`, reported success, and failed minutes later inside Graphviz with
`syntax error in line 1 near '>'` — the XML declaration — and a lexer warning about `50t` from an
XML text node. Neither message points at the real problem, which is that the file is not what its
name says. Hit twice in the first two minutes of real use.

- **The extension is read when `--format` is absent.** `.dot` and `.gv` name DOT; `.graphml` and
  `.xml` name GraphML. Case-insensitively, and from the file name only, so a dot in a directory
  name is not an extension. The caller already stated the intent; discarding it was the bug.

- **A disagreement between `--format` and the extension is a usage error naming both.** Not a
  precedence rule — a precedence rule has to pick a winner, and *neither* answer is safe: obeying
  the flag writes the misnamed file that caused this issue, and obeying the extension silently
  overrules something the operator typed on purpose. The two arguments are one statement made
  twice, and when they differ one of them is a typo that nothing here can identify. Refusing costs
  one re-run; the message says which flag, which extension, and that changing either fixes it.
  **There is deliberately no `--force`**: an escape hatch for "write XML into a `.dot` file" exists
  to serve a case nobody has, and every use of it recreates the failure above.

- **The residual default becomes DOT.** It applies only when neither source says anything —
  `--out /tmp/graph` — which is now a rare path rather than the common one. DOT wins it because it
  renders in one command with a tool that is already installed, where GraphML needs Gephi before it
  shows anything; the first look at a new export should not require an install. GraphML remains the
  better format for the job it was chosen for, and every documented example that wants it names a
  `.graphml` file and gets it by inference.

Not free: `--out` now has a semantic consequence beyond where the bytes land, so adding a third
format means adding its extensions here too, and an operator who *wants* GraphML in a `.txt` file
must say `--format graphml`. Both are cheaper than the failure above.

**Amendment (2026-08-27, issue #59): DOT encodes `NodeKind` twice, as shape and as fill.**

Shape alone is unreadable at the 132 nodes of a real depth-1 neighbourhood and gone entirely in a
thumbnail. Colour is added *beside* the shape rather than instead of it: shape survives greyscale
printing and colour-blind viewing, colour survives being scaled down, and neither survives both.
The fills are six of the seven chromatic **Okabe-Ito** colours, tinted for black text at WCAG AAA,
with PERSON and GROUP — the pair that most needs telling apart — given the most separated pair in
the set under simulated protanopia, deuteranopia and tritanopia. `DotWriter.fill` carries the
working. **GraphML is deliberately unchanged**: it already carries `kind` as an attribute and Gephi
colours on it natively, so presentation stays the reader's. DOT is the format that bakes
presentation into the file, which is why this is a DOT-only concern.

**Amendment (2026-08-27, issue #63): every DOT node carries a `tooltip` naming its class, and WORK
alone is shaded by its four commonest classes.**

Since ADR 42 every node claim stores its raw `P31`, so a picture can say what a node *is* rather
than only which of six kinds it belongs to. Measured on the rebuilt graph (54,448 nodes, `P31` on
99.97% of them): 861 distinct classes, top 8 covering 80.5%, top 40 still only 96.6%. That is a tail
no palette reaches — but within a kind it concentrates, and the two halves of this amendment are
deliberately different in reach because of it.

- **A `tooltip` on every node, in DOT.** Graphviz renders it as `xlink:title` in SVG, so hovering
  says "concert tour" or "television special". It is the only thing that reaches CONCEPT's 458
  classes, which is the kind a reader most often wants to interrogate — "why is this pink node
  here?" is the usual question — and it costs the picture nothing.

- **The class names come from a table in the source, `ClassLabels`, and fall back to the bare QID.**
  The graph stores class QIDs and not labels, and the exporter is offline: a lookup at export time
  would be one HTTP round trip per node, 132 for a depth-1 neighbourhood and tens of thousands for
  `full`, and would make a picture depend on the internet being up. Storing labels at ingest was the
  alternative and is a schema change — which ADR 42's own note says must now come with a real
  migration — for a purely presentational string. **The fallback is honest rather than helpful**: a
  tooltip reading `Q1261214` is useless and true, where a guess would be useful and sometimes wrong
  with no way for the reader to tell which. The table names ~45 classes, every one read from
  Wikidata's own `labels/en` with the description confirmed, the way `KindMapper`'s whitelist was.

- **`ArchitectureTest.theExporterNeverSpeaksToANetwork`**, new here, because this is the change that
  creates the temptation. `export` may not depend on `java.net`, `javax.net` or the project's HTTP
  client. Read-only was already a rule; offline was only a habit.

- **Shade WORK by its top four classes — album, musical work/composition, single, film — and
  nothing else.** WORK is 81% of the graph and 106 classes wide, and those four (31%, 21%, 14%,
  10%) are genuinely different things. **No other kind is shaded**: PERSON is one class at 100%,
  GROUP is 75% "musical group", and CONCEPT's 458 classes are too flat for four shades to be
  anything but a lie about which four matter. Every other WORK class keeps plain WORK yellow. The
  first class with a shade wins, matching `KindMapper`'s first-recognised-class rule, so the picture
  and the kind agree about which class did the choosing.

- **The shades vary in lightness only, and the palette was re-scored rather than assumed.** Same
  Okabe-Ito yellow hue throughout, mixed with white or scaled down in linear light. Re-run under
  issue #59's own method — Machado et al. (2009) matrices at severity 1.0 for protanopia,
  deuteranopia and tritanopia, worst CIELAB distance over every pair:

  | | measured |
  |---|---|
  | palette's worst cross-kind pair, before and after | ΔE 11.88, PLACE/CONCEPT under deuteranopia — **unchanged** |
  | nearest any *shade* comes to another kind's fill | ΔE 17.28, film against GROUP under deuteranopia |
  | plain WORK yellow against GROUP, for comparison | ΔE 15.95 |
  | closest two of the five yellows | ΔE 8.88, single against film under tritanopia |
  | black-label contrast, worst of the ten fills | 7.55:1 on film — WCAG AAA |

  Darkening the yellow moves it *away* from GROUP, not toward it, because the GROUP orange is
  itself a light tint: the worry the issue raised turned out to be backwards, which is why it was
  measured. `PaletteSeparationTest` re-runs all three checks on every build against the fills the
  writer actually emits, so this table is a gate rather than a claim.

- **GraphML gains `instanceOf` and no tooltip.** The raw `P31` QIDs, space-separated exactly as the
  log column and the graph vertex pack them (no escaping needed: every value is a QID). Gephi shows
  attributes on hover and filters on them natively, so a name and a shade there would be presentation
  taken away from the reader. The DOT/GraphML split — one file bakes presentation in, the other
  hands over data — is the same one the #59 amendment drew.

Not free: `ClassLabels` is a hand-maintained sample of a long tail, so a class outside it shows as a
QID until someone adds it, and the four shaded QIDs are a second place (beside `KindMapper`) where a
Wikidata class id appears in the source. Both were preferred to the alternatives above.

**Amendment (2026-08-27, issue #70): DOT tooltips every edge, and stops labelling them above 40.**

The #59 amendment made a 132-node depth-1 neighbourhood readable as a *shape* and left it
unreadable as a *picture*: 144 edge labels land on top of each other around the hub, and they
overprint the node labels underneath, so the entity the neighbourhood is *of* cannot be read. The
exporter was emitting a label per edge whatever the density, while already knowing the counts — it
prints them before it writes.

- **Every edge carries a `tooltip`, always**, naming the relationship and both of its ends:
  `Steve Martin -RECEIVED_AWARD-> Writers Guild of America Award`. Same mechanism the #63
  amendment proved for nodes — Graphviz renders it as `xlink:title` in SVG — and same argument: the
  hover channel has no budget, so it can say more than a label ever could. The endpoints are in it
  because the edges that most need identifying are the ones fanning out of a hub drawn on top of
  each other, where "which of these did I just point at" is the whole question; the SVG's own
  `<title>` answers that in QIDs, which no reader can use.

- **The visible label is dropped above 40 edges.** Measured on slices of that same neighbourhood
  under `sfdp`: 26 edges, every label legible; 38, a couple of pairs touching; 51, labels
  overprinting each other and the nodes; 144, a solid block. 40 is the last count at which the
  picture still reads.

- **The threshold is on edges, not nodes, and deliberately not on `ViewKind`.** A label is drawn
  per edge, so edges are the thing being counted. And a `route` keeps its labels because a route is
  two or four edges, *not* because it is a route — which is the same rule that keeps them on a
  ten-node subgraph and drops them from a large one, instead of one rule about the picture plus a
  second about where the picture came from. The view kind is available at write time and is not
  worth spending: it would answer a question the edge count already answers, and answer it wrong
  for the small `subgraph` and the pathological `route`.

- **Suppression is reported, through the writer rather than around it.** `ViewWriter` gains
  `Optional<String> note(GraphView)` — anything this format had to do to this view — and
  `ExportRun` emits it beside the counts, before the file exists. The alternative was for
  `ExportRun` to ask whether it is holding a `DotWriter`, which puts a format concern in the one
  class this ADR keeps format-blind. `GraphMlWriter` inherits the empty default and says nothing,
  which is correct: **GraphML is unchanged**, and its `typeCode` attribute carries every edge type
  at every size. The note says so, so the operator has somewhere to go.

Not free: an operator who wants labels on a dense view cannot ask for them — there is no flag,
because the picture that flag produces is the one this issue is about. The tooltip needs an SVG and
a pointer, so a PNG of a dense view has the edge types nowhere; that is what GraphML is for.

**Amendment (2026-08-28, issue #81): DOT cannot put a tooltip where a browser will show it, and the
two amendments above were wrong to say it could.**

The #63 and #70 amendments both claim that Graphviz renders a `tooltip` as `xlink:title` "so
hovering a node says concert tour". The first half is true and the second does not follow.
**Browsers do not display `xlink:title`** — confirmed in Safari, in both directions: the shipped
SVG showed nothing useful, and a copy with the same text moved into the `<title>` element showed it
correctly. The tooltips were all present — 276 of them on the real 132-node depth-1 neighbourhood,
exactly one per node and edge — and not one of them reached a reader.

**How it got past the gate is the more useful half.** #63 grepped the rendered SVG, found the
attributes, and concluded the tooltips worked. They were in the file; they were simply not what a
browser reads. **Presence in the output was verified; the outcome was not** — which is the same
trap as asserting on DOT text rather than on rendered SVG, one level further out. The next check
after "is it in the file" is "does the thing that consumes the file act on it".

- **The mechanism.** Graphviz emits both, and the wrong one wins:

  ```xml
  <g id="node1" class="node">
    <title>Q16473</title>                       <- what a browser shows on hover
    <g id="a_node1"><a xlink:title="human">     <- where the class actually is
  ```

  The `<title>` **element** is the tooltip mechanism browsers implement. Graphviz writes it from
  the object's **name**, so a reader hovering a node gets `Q16473` and hovering an edge gets
  `Q16473->Q1415017`.

- **Nothing redirects it, and that was measured against the real binary rather than read out of the
  manual** (Graphviz 15.1.1). One node was given every plausible attribute at once — `id`, `class`,
  `tooltip`, `labeltooltip`, `URL`, `href`, `target`, `comment`, `xlabel`, and the non-attributes
  `title`, `name`, `desc`, `alt`, `description` — plus an HTML-like label carrying `TITLE`. `id`
  and `class` land on the `<g>`; every tooltip lands in `xlink:title`; the rest are ignored. The
  `<title>` element was the name in all of them. The `cairo` SVG renderer drops tooltips entirely
  and `svg_inline` is byte-identical to `svg`.

- **The issue's two candidate fixes were tried, and both fail.** Emitting the class as the node
  **name** does put it in `<title>` and is disqualifying for the reason the issue suspected, worse
  than expected: two nodes named `human` do not collide loudly, they **silently merge into one
  node** — Graphviz kept the second label, dropped the first, and turned the edge between them into
  a self-loop. Setting `id=` explicitly sets `<g id>` and leaves `<title>` alone.

- **For an edge it is not merely awkward, it is impossible.** An edge has no name. Its `<title>` is
  written mechanically as `tail->head` from the two node names, so the relationship type cannot
  appear there however the nodes are named — and naming nodes after their labels to rescue the
  endpoints would break identity twice over, since labels are not unique. The one channel that
  reaches an edge `<title>` is **port** syntax (`"A":"MEMBER_OF" -> "B"`, which does print), and
  that smuggles a relationship through a geometry channel, risks colliding with a compass point,
  and still prints the endpoints as the QIDs #70 rejected. **This is the finding: DOT cannot
  express it.** A documented "cannot" beats an attribute nothing reads.

- **The `tooltip` attribute stays, and is not inert.** `dot -Tcmapx` renders the same `tooltip` as
  an HTML `title` on an `<area>` — `title="human"`, `title="Steve Martin -RECEIVED_AWARD-> Writers
  Guild of America Award"` — and an HTML `title` is a tooltip in every browser. Verified on the
  real neighbourhood: 276 areas, one per node and edge. So a PNG plus its imagemap does show them,
  which corrects the #70 amendment's closing sentence as well. Deleting the attribute would have
  cost the only carrier of an edge's type above the label budget in exchange for nothing.

- **What changes in the output is one sentence.** `DotWriter.note` used to tell the operator to
  "render with -Tsvg and hover", which is precisely the thing that does not work. It now says the
  tooltip is in `xlink:title`, that an SVG hover shows the QIDs, and names the two renders that do
  answer the question — GraphML's `typeCode`, or `-Tcmapx`.

- **`WhatAHoverShowsTest` renders through the real Graphviz binary and asserts on `<title>`
  *content*.** Not on the presence of an attribute — on the string a browser would put in the
  tooltip. It pins both halves: the node `<title>` is the QID and carries no class, the edge
  `<title>` is two QIDs and carries no type, **and** the imagemap does carry both. If a future
  Graphviz starts writing the tooltip into `<title>`, the first two fail and this amendment wants
  revisiting — which is the point of pinning a "cannot" rather than merely writing it down.

- **CI installs Graphviz, because otherwise this test passes by not running.** The test skips itself
  where the binary is absent — a machine without it has no rendered file to read and should not
  fail for that — and the runner image does not carry it, so the first CI run reported four passes
  and four skips. A guard that reports success while executing nothing is the same failure as a
  tooltip that is present and unread, one level further out again, and it was caught only by
  reading the test report rather than the build's exit code. One `apt-get` step in the workflow.

Not free: the class and the relationship are now documented as unreachable through the render most
people will reach for, and the note has to spend two extra clauses saying so. The alternative was
to keep an attribute that reads as a working feature to anyone who greps for it — which is what
this issue cost.

**Amendment (2026-08-29, issue #98): the WORK shade is chosen by a fixed rank, and the #63
amendment above was wrong to say the first stated class wins.**

That sentence — "the first class with a shade wins, matching `KindMapper`'s
first-recognised-class rule" — described a rule that has since been deleted and a behaviour that
was never safe. Issue #87 replaced `KindMapper`'s first-match with a ranking precisely because
`P31` order is noise: an entity's statements arrive oldest-first from the entity JSON, or in
whatever order SPARQL bound the rows in `ReverseClaims`, and neither is a claim about which class
matters most. Reading that order one layer out left the exporter as the last place in the codebase
that still did, so a WORK stating two shaded classes could be drawn one shade on one run and
another on the next. Nothing reported it, because both fills are legal.

- **A ranking over the four shaded classes, most decisive first, in `DotWriter`.** The ranking and
  the shade table are one declaration, so they cannot drift apart, and it is the code that says
  what the order is.

- **Not `KindMapper.PRECEDENCE`, and not a reference to it.** That list ranks the six *kinds*;
  all four shaded classes are the same kind. WORK wins there as one block, which settles nothing
  about the four ways of being a WORK — so this is a ranking that layer does not have rather than
  one it could lend. Agreeing with the kind derivation means agreeing with how it works, not
  reusing its list.

- **The order is argued weakly, and deliberately so.** Musical work/composition ranks last as the
  broadest of the four and the one that tells a reader least; the other three fall in the order of
  the measured shares that chose the four in the first place. They rarely co-occur, so this is a
  tie-break rather than a rule. Shape already carries the kind alone, so a shade a reader cannot
  place costs them nothing — but two exports of the same graph disagreeing costs them trust in the
  picture, and that is the half worth fixing.

Not free: a fifth shaded class now has to be ranked as well as coloured, and a reordering of those
lines is a behaviour change that reads like a formatting change. The comment on the list says so.

**Amendment (2026-08-30, issue #99): the "cannot" above is about DOT, and it ends at the render —
a rewrite after Graphviz makes the tooltips visible, and it lives here.**

The #81 amendment stands unchanged and is not weakened by a word of this. DOT still cannot express
it: Graphviz writes the `<title>` element from the object's name, an edge has no name, and no
attribute redirects either. What #81 left was a reader who had been told at length what does not
work and never told what does.

**The fix is a property of our pipeline, not of DOT.** Once `dot` has written the SVG, the class
and the relationship are both in the file, one attribute away from the element a browser reads. So
`HoverableSvg` runs after the render and copies each `xlink:title` into a `<title>` element on the
anchor that carried it. SVG resolves a tooltip to the *nearest* ancestor with a `<title>` child, so
the inserted one wins; nothing is deleted, the attribute stays, and the outer `<title>` stays the
QID, which keeps every tool that reads either of them working.

- **It also titles the edge label, and that was found by measurement rather than by reading.**
  Graphviz puts a node's label inside the anchor and an edge's label *outside* it, as a sibling of
  the whole `<g class="edge">`. Rewriting only the anchors — which is what the issue asked for —
  leaves the visible relationship label still resolving to the two QIDs, and that label is drawn on
  every view under the 40-edge budget and is the likeliest thing a reader points at. Hit-testing
  the rendered label in Chrome is what showed it; the file looked fixed. Whether the owner's local
  script has the same gap is not recorded here, because it was not read.

- **A Java tool in `export` with a Gradle task, not a shell script in `scripts/`.** The issue asked
  for `scripts/`, and the repository has no shell script in it. Every operator tool here is a
  `main` behind a `JavaExec` — `resolveNames`, `exportGraph`, `listRatings`, `rate` — and a fifth
  costs no new pattern, no interpreter, and nothing in `gradle/libs.versions.toml`. It also lands
  inside Spotless, ArchUnit and the coverage gate rather than beside them. **The weak form of this
  argument is that a script could not be tested, and that is not true** — a test can run a script,
  and one in this change does exactly that to the imagemap recipe. The argument is consistency and
  no interpreter, and it is worth saying which one is load-bearing.

- **Not folded into `exportGraph`.** The exporter never shells out; an export is a pure function of
  one database file, which is what `theExporterNeverSpeaksToANetwork` and the read-only rule are
  protecting. The SVG this reads does not exist until the operator has run `dot`, so it is a third
  step and says so.

- **The `-Tcmapx` recipe was incomplete, and the issue's diagnosis of why was wrong in a way worth
  recording.** It said the map would be named `%1` for an unnamed graph and that a name with
  spaces would not bind. Neither holds: `DotWriter` names the graph after the view description, so
  the map is named for the view, and Chrome binds `usemap="#a made-up view"` correctly — measured,
  by hit-testing the image and getting the `<area>` back. **The actual defect was simpler and
  worse**: the recipe stopped after `dot`, so it produced a picture and an imagemap and no page
  binding them. It ran cleanly and did nothing, which is the shape this project keeps meeting. The
  recipe now writes the page and renames the map to a fixed id — not because the generated name
  fails, but because it is unknown until you look at the file.

- **The guide's recipe is executed rather than asserted.** `ImagemapRecipeTest` reads the ```` ```bash ````
  block out of `docs/developer-guide.md` and runs it against a real `DotWriter` render, then checks
  that the page's `usemap` names the map the recipe wrote. Issue #93 installed Graphviz in CI so a
  claim about `dot` could be executed; this is that install being spent again. A shell snippet in a
  markdown file is otherwise a claim with nothing behind it, which is how the broken one shipped.

- **`DotWriter.note` names the SVG again, and the #81 amendment's last bullet is superseded.**
  That bullet says the note "names the two renders that do answer the question — GraphML's
  `typeCode`, or `-Tcmapx`". It now names `-Tsvg` together with `hoverableSvg`, and GraphML.
  Naming the render *alone* was #81's defect, so `DotWriterTest` pins the pair rather than banning
  the word `-Tsvg`, which is what it used to do.

- **What is verified, and what is not, stated so nobody reads more into the tests.**
  `WhatAHoverShowsTest` renders through the real binary, rewrites, and asserts the string a browser
  would resolve for four hover targets — node shape, node label, edge line, edge label — by walking
  to the nearest ancestor carrying a `<title>`, which is the rule a user agent applies. **No test
  confirms that a browser paints the tooltip.** It is native browser chrome; it is in no DOM and no
  screenshot. That half was read by hand: in Safari for #81, in both directions, and in Chrome for
  #99, where all four targets and the imagemap's `<area>` were hit-tested and read back. It is a
  manual observation on both issues and it should be repeated by hand if the mechanism is ever
  doubted.

Not free: there is now a third step between a graph and a picture somebody can read, and an
operator who renders an SVG and skips it gets exactly the old confusing result. The note is what
tells them, and it only fires above the 40-edge budget — below it the labels are drawn, so the
picture is readable without any of this, which is the case where a reader is least likely to be
told and least likely to need it.

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
