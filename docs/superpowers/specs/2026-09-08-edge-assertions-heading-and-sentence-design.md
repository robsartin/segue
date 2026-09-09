# The two places #293 saw and left say edge assertions too

Issue #299. Written 2026-09-08 against `299-ready`, which is `origin/main`. Everything below was
read from the code in this worktree; no database was opened, and every identifier named here is
invented and carries ADR 58's leading zero.

This issue continues #293, which corrected the expander's `graph` row from `edges added` to
`edge assertions recorded`. ADR 66's 2026-09-08 amendment named two things it saw and left, and
named a separate issue as the place to change them. This is that issue, and its scope is exactly
those two things.

## The premise the issue states, and what the code actually does

The issue's premise holds on the substance in both halves: the `edges by source` heading breaks down
the same per-assertion quantity the renamed row sums, and `expand_entity`'s clean-expansion sentence
quotes the same `edgesAdded`. Five places where the issue is looser than the code, or says something
the code does not, are recorded here rather than left for the implementer to trip over.

- **The issue says "The developer guide's chapter that lists the block's sections follows." No
  chapter lists them, and nothing in the developer guide changes.** Across `docs/`, the string
  `edges by source` appears only in `docs/adr/0066-expand-every-promotion-from-a-dev-tool.md`
  (twice) and in the superpowers specs and plans that produced it. The chapter *Expanding every
  promotion* names the block's edge **row** — through step 5's `` | `claims` / log rows | `` row,
  which `DeveloperGuideExpandPromotionsExamplesTest` reads off
  `ExpansionReport.EDGE_ASSERTIONS_RECORDED` — and it names no section heading of the expansion
  block at all. So the guide test neither reds nor needs an edit, and the property #293 created
  (row and printed line cannot drift) does not extend to headings because nothing outside the class
  says one.

- **What the chapter *does* carry is a look-alike that must not be touched.** Step 5's table has a
  row `` | `edges` by source | up, **and `musicbrainz` up for the first time in bulk** | … ``. That
  is `CensusReport`'s label for `graphCensus`'s block — a different tool, a different number, the
  authority the runbook compares the expansion against. A rename applied by search-and-replace over
  the guide would corrupt it. `CensusReport` is out of scope entirely.

- **`SegueServiceTest` does not pin the sentence, and the nearest thing to a pin cannot red on this
  change.** `expandEntityDoesNotCountRefreshedInlineNeighboursAsAdded` asserts
  `result.detail()).contains("0 new node(s)")` — the half of the sentence this issue does not touch,
  so it stays green whichever word the other half uses and is not coverage. A comment in
  `shouldRefuseWithNoSourceToExpandFromWhenTheSeedIsALocalEntity` quotes
  `"0 edge(s), 0 new node(s)"` in prose, and goes stale with the change. So the pin the issue asks
  for has to be **created**, and it is the red.

- **Nothing in `src/test` reads the user guide's transcript, so the guide edit is prose verified by
  the gate.** `GuideExamples` reads `docs/developer-guide.md` and only that file. `DocumentationLinksTest`
  walks `README.md` and every `.md` under `docs/`, but it resolves relative links and anchors and
  asserts nothing about a document's sentences. `docs` is a declared input directory of the `test`
  task in `build.gradle.kts`, so editing the user guide does re-run the suite — that proves the
  declaration works, not that any assertion saw the string. No test can red on
  `docs/user-guide.md` line 912, and the plan says so rather than implying coverage.

- **The heading is a `Section`, not a `Row`, so no column moves — unlike #293.** `ExpansionReport.render`
  measures only counted rows:

  ```java
      for (Entry entry : body) {
        if (entry instanceof Row row) {
          labelWidth = Math.max(labelWidth, row.label().length());
          countWidth = Math.max(countWidth, String.valueOf(row.count()).length());
        }
      }
  ```

  `Section` and `SubHeading` never enter either width. So the golden block is **not** re-padded and
  every counted line stays byte for byte as it is; the diff is one line of the golden block plus one
  lookup. Belt and braces: even if headings were measured, `edge assertions by source` is 25
  characters against the widest counted label `  edge assertions recorded` at 26, so the column
  would still not move.

## The decision

### The heading

`edges by source` becomes **`edge assertions by source`**. The rows under it are
`ExpansionOutcome.Expanded#edgesBySource()` accumulated per adapter id — the same increments that
sum to `edge assertions recorded` directly above them — so the heading had the exact defect #293
corrected one row up, and after #293 it was the only place in the block still calling that quantity
"edges".

The alternative was to leave it, which is what #293 chose, on the grounds that it sits under the
renamed row and sums to it so the reader can infer the quantity. It lost here because inference
across sections is not what the block does anywhere else: every other label says what it counts,
and a section heading is read on its own when somebody pastes the block into an issue.

### It stays a literal, not a constant

`EDGE_ASSERTIONS_RECORDED` became a named constant in #293 for one reason: the developer guide's
runbook row cites that label, and a test reads the constant so the two cannot drift. Nothing outside
`ExpansionReport` says this heading — not the developer guide, not the user guide, not any test but
`ExpansionReportTest`, which pins block text as literals on purpose. A constant with one reader
would be structure ahead of a need. It stays an inline literal in `body()`, which is also what ADR
66's output contract requires of every label.

### The class javadoc goes with it

`ExpansionReport`'s class javadoc says *"An empty `edges by source` means no edge was recorded from
any source"* as the worked example of the empty-section rule. That is the same string in prose in
the same file; left behind it would make the file contradict its own output. It is reworded with the
heading, and the sentence says *edge assertion* for the same reason the heading does.

### The `expand_entity` sentence

`"expanded " + qid + ": " + edgesAdded + " edge(s), " + nodesAdded + " new node(s)"` becomes
`"expanded " + qid + ": " + edgesAdded + " edge assertion(s), " + nodesAdded + " new node(s)"`.

`edgesAdded` here is `ExpansionOutcome.Expanded#edgesAdded()`, and `ExpansionSummary`'s javadoc for
the payload field already says what it counts: *"per assertion, not per pair of nodes, so two
sources claiming the same relationship count twice and are merged downstream by `GraphStore.record`"*.
The sentence was the one place restating the number in the wrong words, next to a javadoc that had
it right all along — the same shape of defect #293 found at the printed label.

**No identifier moves, and nothing about the payload changes.** `ExpansionSummary#edgesAdded`, the
wire field name, `ExpansionOutcome.Expanded#edgesAdded` and `ExpansionTally#edgesAdded` all stay.

### Why the sentence is not a protocol change

The `detail` string is human-readable prose a model reads. No client parses it: the number a caller
wants is `payload.edgesAdded`, which is unchanged, and the sentence carries no field a schema
names. The tool surface ADR 26 governs — the six tools, their arguments, their payload shapes — is
untouched, so **ADR 26 needs no amendment**. What records the move is ADR 66's amendment, because
ADR 66's own #293 amendment is what said this sentence was seen and left.

### `get_entity`'s sentence is correct and is not touched

`"<label>: N edge(s), M type(s)"` counts `graph.edges(qid)` — the edges the graph holds on one node,
after `GraphStore.record` has merged corroborating assertions into single edges. That is an edge
count and `edge(s)` is the right word for it. It is the same word in a neighbouring method for a
genuinely different quantity, which is why the plan verifies the file afterwards rather than
trusting a rename.

### ADR 66 gains a dated amendment

A dated amendment appended at the end of `docs/adr/0066-expand-every-promotion-from-a-dev-tool.md`,
in the same form as the 2026-09-08 amendment for #293, appended after it and editing nothing above
it. It has to carry four things:

1. The old and the new heading, and that #293's amendment left the heading calling the rename "a
   separate decision nobody has asked for" — #299 is that ask.
2. That the *output contract*'s `nodes added` paragraph still holds word for word under the new
   name. Its substance is that a node's identity may come from `EntityResolver.fetch`, which has no
   adapter behind it, while every assertion carries a `Provenance` whose `sourceId` does — a
   contrast between two sections and the authority each has for a source id, not a claim about the
   word "edges".
3. That the block's columns do not move, unlike #293's longer row label, and why.
4. That the sentence #293's amendment said was seen and left has now moved, and why that is not a
   protocol change: the payload field is unchanged, no client parses `detail`, ADR 26's surface is
   the same and needs no amendment.

No commit hash may appear in a code span anywhere under `docs/adr` (`AdrCitationsTest`), no figure
from the owner's graph is restated, and no `.superpowers/` path is cited. The heading and front
matter are untouched, so `docs/adr/README.md` does not move (`AdrIndexTest`).

## What changes

| file | change |
| --- | --- |
| `src/main/java/com/robsartin/segue/expand/ExpansionReport.java` | `body()`'s `new Section("edges by source")` becomes `new Section("edge assertions by source")`; the class javadoc's empty-section paragraph is reworded |
| `src/test/java/com/robsartin/segue/expand/ExpansionReportTest.java` | one line of `GOLDEN_BLOCK`; the `lines.indexOf(...)` lookup in `shouldStillPrintTheHeadingWhenASectionHasNoRows` |
| `src/main/java/com/robsartin/segue/mcp/SegueService.java` | the clean-expansion `ToolResult.ok` sentence in `shape` |
| `src/test/java/com/robsartin/segue/mcp/SegueServiceTest.java` | a new pin on the whole sentence; the stale quotation in an existing comment |
| `docs/user-guide.md` | the one transcript that quotes the sentence |
| `docs/adr/0066-expand-every-promotion-from-a-dev-tool.md` | a dated amendment, appended |

## Not this issue

- **Anything that changes what is counted.** No new count, no moved row, no moved section, no
  second census inside either tool.
- **`get_entity`'s sentence**, for the reason above.
- **The payload field `edgesAdded` and every Java identifier.** Renaming a wire field is a protocol
  change; renaming the Java names is churn with no reader behind it.
- **`CensusReport` and the developer guide's step-5 table**, including its `` `edges` by source ``
  row, which belongs to a different tool.
- **`RecommendRun`, `RetractRun`, `ExportRun`, `DotWriter` and `GraphView`'s own `edge(s)` phrases**,
  which count graph edges and are correct.
- **A constant for the heading**, for the YAGNI reason above.

## How this is verified

- **The heading**: `ExpansionReportTest`'s golden block pins the exact string as a literal, seen red
  on `edges by source` first. `shouldStillPrintTheHeadingWhenASectionHasNoRows` looks the heading up
  by the same text and asserts the index `isPositive()`, so it is its own positive control — a
  lookup that misses returns `-1` and the assertion fires. Both are seen red in the same run before
  the code moves.
- **No column moved**: the golden-block diff is one line. Every counted row is unchanged, and the
  implementer confirms that from `git diff` rather than from this document.
- **The sentence**: a new `SegueServiceTest` pin asserting the whole detail string with
  `isEqualTo`, seen red on `edge(s)` before `SegueService` moves. Its two payload assertions
  (`edgesAdded` is 2, `nodesAdded` is 1) must pass in that same red run — they are what proves the
  fixture produced the expansion the pin describes, so the failure is on the wording and not on a
  mis-built fixture.
- **`get_entity` untouched**: after the change, `SegueService.java` still contains exactly one
  `edge(s)`, at `getEntity`. That grep proves it for this file only; the whole set of `edge(s)`
  sites in the tree is enumerated in *Not this issue* above, derived from a tree-wide grep, and none
  of the others is edited.
- **The user guide transcript and ADR 66's amendment**: prose with no unit-testable behaviour. They
  are verified by the full gate and by nothing else —
  `SEGUE_REQUIRE_BROWSER=true SEGUE_REQUIRE_GRAPHVIZ=true ./gradlew check --rerun-tasks`, with
  `DocumentationLinksTest` (links and anchors resolve), `AdrIndexTest` (the index row still agrees)
  and `AdrCitationsTest` (no commit citation) among the tests run. This is stated rather than left
  to be inferred.
