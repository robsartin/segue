# The expander's edge line is an assertion count, and the label says so

Issue #293. Written 2026-09-08 against `293-ready`, which is `origin/main`. Everything below was read
from the code in this worktree; no database was opened, and every identifier named here is invented
and carries ADR 58's leading zero.

## The premise the issue states, and what the code actually does

The issue's premise holds on the substance: the line labelled `edges added` counts edge assertions
recorded, not the graph's net gain. Five places where the issue is looser than the code, or says
something the code does not, are recorded here rather than left for the implementer to trip over.

- **The counting is exactly as the issue describes it.** `ExpandRun.run` accumulates
  `edgesAdded += one.edgesAdded()` over every `ExpansionOutcome.Expanded`, and
  `ExpansionOutcome.Expanded`'s own javadoc already says what that component is:
  *"assertions recorded, per assertion rather than per pair of nodes"*. So the truth was written down
  at the source all along; what lost it is the two places downstream that restate the number — the
  printed label, and `ExpansionTally`'s `@param edgesAdded summed across every expanded entity`,
  which says how it was summed and not what it counts.

- **The issue says the guide-chapter test "reads the label from the same constant, so the two cannot
  drift". It does not, and there is no constant.** `DeveloperGuideExpandPromotionsExamplesTest` has
  five tests: the chapter is present, every `./gradlew expandPromotions --args="…"` line parses
  through `ExpandCli.parse`, no example writes a tilde, no example line is unreadable, and the four
  commands appear in the runbook's order. **None of them reads any label the report prints.** The
  label is an inline string literal inside `ExpansionReport.body()`. So the property the issue
  describes as existing is a property this change has to *create*, and creating it is the reason the
  constant exists at all rather than a tidy-up.

- **The rename widens every counted row in the block by four characters.** `ExpansionReport.render`
  derives the label column from the widest counted label anywhere in the document. Today that is
  `  bound cut the result` (22 characters with its indent); after the rename it is
  `  edge assertions recorded` (26). Every row in `ExpansionReportTest`'s golden block therefore
  moves, not just the one, and so do both pinned strings in
  `shouldAlignEveryColumnWhenTheCountsDifferInWidth`. This is the alignment rule working, not a
  regression, but a plan that expected a one-line diff would read the red as a surprise.

- **The `graph` section holds one net count and one assertion count, and after this change the
  labels are the only thing that distinguishes them.** `nodes added` *is* net — #284's closing
  comment reports the census's node total moving by exactly the tool's figure — while the edge row
  is per assertion. ADR 66 decided deliberately that `nodes added` sits under `graph` rather than
  under `edges by source`; that placement is not reopened. The row is not moved out of `graph`
  either: moving a row is a second contract change with no reading behind it, and the label is what
  misled.

- **`SegueService`'s javadoc already says the wire field counts per assertion**
  (*"per assertion, not per pair of nodes"*), which is why the issue is right to leave the payload
  alone.

## The decision

### The label

`edge assertions recorded`, exactly as the issue proposes. Nothing in the code argues against it:

- **"assertions" is the word the code uses.** `ExpansionOutcome.Expanded#edgesAdded` says
  "assertions recorded"; `IngestService` records `AssertionRecord`s; ADR 19 is
  *the assertion log is the source of truth*.
- **"recorded" and not "appended" or "written".** `ExpansionReport`'s own class javadoc already says
  *"An empty `edges by source` means no edge was **recorded** from any source"*, so the block's
  vocabulary is already this word. It is also the honest one: ADR 66's consequence records that
  `ProvenanceCodec.append` drops a provenance whose source and reference it already holds, so
  "recorded" describes what the expansion did without claiming anything about what the log row count
  became. The runbook's `at least` hedge stays true under it.
- **It does not overclaim.** It says nothing about edges, which is the whole defect.

### Where the label lives

A new `public static final String ExpansionReport.EDGE_ASSERTIONS_RECORDED = "edge assertions
recorded"`, holding the bare label without the two-space indent that `body()` adds.

A constant rather than a second literal, and this is the judgement the issue asked for. The
alternative — a literal in `ExpansionReport` and a matching literal in the guide test — leaves two
strings that must agree and nothing that makes them agree, which is the drift this issue exists to
close. With the constant the chain has no untied link: `ExpansionReportTest`'s golden block pins the
constant's **text** as a literal (deliberately not reading the constant, exactly as it deliberately
does not read `HEADER` — the pin's whole point is to catch the text moving), and
`DeveloperGuideExpandPromotionsExamplesTest` reads the **constant** to check the runbook row, so a
reworded label reds the pin and a reworded runbook row reds the guide test.

ADR 66's output contract says *"every label is a literal in `ExpansionReport`"*. A constant in
`ExpansionReport` satisfies that sentence; the amendment says so rather than leaving a reader to
wonder.

### What else says the same thing in the same words

- **`ExpandRun`'s clean per-entity progress line**, today `12 edge(s), 4 new node(s)`, becomes
  `12 edge assertion(s), 4 new node(s)`. It is the same number one entity at a time, and it was the
  same misnomer. The `(s)` shape matches `new node(s)` beside it and every other count this project
  prints.
- **`ExpansionTally`'s `@param edgesAdded`**, which today says only how the number was summed.
- **The developer guide's runbook row** for `claims` / log rows, and the chapter's own progress-line
  example.
- **One sentence in the chapter's paragraph about the block**, saying an already-held edge is
  recorded again and why.

### `edges by source` keeps its heading

The rows under `edges by source` are the same quantity broken down — `ExpansionOutcome.Expanded#edgesBySource()`
summed — so the heading is misleading in the same way. It is left alone here, deliberately:

- The issue scopes itself to the line, and ADR 66 names `edges by source` in two places, one of them
  the load-bearing contrast *"`nodes added` sits under `graph` and not under `edges by source`"*.
  Renaming it is a second contract change with its own ADR text to work through.
- The section sits immediately under the renamed row and sums to it, and the chapter's new sentence
  covers both, so a reader who has read the label is not misled by the breakdown.
- `ExpansionReportTest.shouldStillPrintTheHeadingWhenASectionHasNoRows` pins the heading by literal,
  so it cannot move silently later.

**Reported as a finding rather than done here:** if the reviewer wants the section renamed too, that
is a separate issue and a second amendment sentence, not a widening of this one.

### No net-edges figure

Unchanged from the issue. The tool holds no before-and-after of the graph; `graphCensus` does, and
the runbook's step 5 is where the comparison is made. Adding a net count would mean the tool taking
its own census, which is ADR 63's job.

### ADR 66 gains a dated amendment

ADR 66's *The output contract* section names the line, so the label is part of a documented decision
and the correction belongs in the ADR rather than only in the code. The amendment is dated
2026-09-08, follows ADR 54's and ADR 61's convention (a bold `**Amendment (date, issue #N): …**`
paragraph appended after Consequences, nothing above withdrawn, status stays `Accepted`), names the
old label and the new one, and cites **#284's closing comment** as the reason.

**No figure from that reading is restated in the amendment** — not the tool's count, not the census's
edge totals, not a ratio between them. The census is the authority on the graph and the issue is the
record of the run; a number copied into an immutable document is a drift generator. No commit hash
appears anywhere in `docs/adr` (`AdrCitationsTest` fails the build on one), and no `.superpowers/`
path appears in any committed file.

## What changes

| file | change |
| --- | --- |
| `src/main/java/com/robsartin/segue/expand/ExpansionReport.java` | new `EDGE_ASSERTIONS_RECORDED` constant; `body()` uses it |
| `src/main/java/com/robsartin/segue/expand/ExpansionTally.java` | `@param edgesAdded` says what it counts |
| `src/main/java/com/robsartin/segue/expand/ExpandRun.java` | `detail()` prints `edge assertion(s)` |
| `src/test/java/com/robsartin/segue/expand/ExpansionReportTest.java` | golden block re-padded; alignment pins re-padded |
| `src/test/java/com/robsartin/segue/expand/ExpandRunTest.java` | new test pinning the clean progress line |
| `src/test/java/com/robsartin/segue/expand/DeveloperGuideExpandPromotionsExamplesTest.java` | new test tying the runbook row to the constant |
| `src/test/java/com/robsartin/segue/expand/ExpansionIsSafeToPasteTest.java` | its hand-built sample progress line keeps resembling the real one |
| `docs/developer-guide.md` | runbook row, progress-line example, one new sentence |
| `docs/adr/0066-expand-every-promotion-from-a-dev-tool.md` | dated amendment |

## Not this issue

- **The MCP `expand_entity` payload field `edgesAdded`** and everything on that surface: ADR 26's
  tool shape, `ExpansionSummary`, `SegueService`'s two `edge(s)` sentences and its javadoc. Renaming
  a payload field is a protocol change for clients, and the javadoc there already carries the
  meaning. `ToolSurfaceTest` is untouched and the user guide's transcripts are untouched.
- **Every Java identifier.** `ExpansionOutcome.Expanded#edgesAdded`, `ExpansionTally#edgesAdded` and
  the local `edgesAdded` in `ExpandRun.run` keep their names. Only prose moves.
- **The `edges by source` section heading**, for the reasons above.
- **A net-edges count**, for the reasons above.
- **`RecommendRun`, `RetractRun`, `ExportRun`, `DotWriter` and `GraphView`'s own `edge(s)` phrases.**
  Different tools counting different things — projected edges, stranded edges, exported edges — none
  of them an assertion count, and none of them named in this issue.
- **The historical documents under `docs/superpowers/`.** `2026-09-07-expand-promotions-design.md`
  and its plan record what was designed then; they are a record, not a source of truth, and editing
  them would falsify the record rather than correct anything.

## How this is verified

- **The printed label**: `ExpansionReportTest`'s golden block, changed first and seen red on the old
  label, is the pin. It also proves the alignment consequence, because every row in it moves.
- **The progress line**: a new `ExpandRunTest` test, seen red on `edge(s)` before `detail()` moves.
- **The runbook row cannot drift from the label**: a new
  `DeveloperGuideExpandPromotionsExamplesTest` test reading `EDGE_ASSERTIONS_RECORDED`, seen red
  against the guide as it stands today — the defect is real and pre-existing, which is a stronger
  control than a planted one — **plus** a planted control on its row lookup: change the row's leading
  cell and confirm the test names the missing row rather than passing vacuously. That control is the
  point: a `contains` over the whole chapter would have passed on the new sentence alone while the
  table row still said the old thing.
- **`docs/` is a declared input of the `test` task** (`build.gradle.kts`, `inputs.dir("docs")`), so a
  guide-only edit re-runs the suite instead of reporting `UP-TO-DATE`. Both guide edits and the ADR
  amendment ship in commits that also touch the tests that read them, so nothing here depends on
  that declaration being remembered.
- **The ADR amendment and the two javadoc edits have no unit-testable behaviour.** Stated out loud
  rather than left implied: they are verified by the full gate —`AdrIndexTest` (the README row still
  agrees with the unchanged heading and front matter), `AdrCitationsTest` (no commit hash),
  `DocumentationLinksTest` (both new relative ADR links resolve) and `javadoc -Werror` — and by no
  test of their own.
- **The whole gate**, blocking:
  `SEGUE_REQUIRE_BROWSER=true SEGUE_REQUIRE_GRAPHVIZ=true ./gradlew check --rerun-tasks`.
