# The two places #293 saw and left say edge assertions too — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** close issue #299 — the expander's `edges by source` section heading becomes
`edge assertions by source`, and `expand_entity`'s clean-expansion sentence becomes
`expanded <qid>: N edge assertion(s), M new node(s)`. ADR 66 gains a dated amendment recording both.
No Java identifier changes, no MCP payload field changes, no new count, nothing about what is
counted moves.

**Architecture:** Two one-line string changes in production code, each preceded by the pin that
reds on it, plus the prose that quotes them. The heading stays an inline literal in
`ExpansionReport.body()` — nothing outside that class says it, so a named constant would be
structure ahead of a reader. `SegueService`'s sentence is unpinned today, so Task 2 creates the pin
that reds.

**Tech Stack:** Java 25, Gradle (plain `./gradlew`), JUnit 5, AssertJ, TinkerGraph, SQLite.

**Spec:** `docs/superpowers/specs/2026-09-08-edge-assertions-heading-and-sentence-design.md`

## Global Constraints

- **Pure TDD / red first.** Every behaviour: write the test, **run it, observe a real assertion
  failure**, then the minimum code. **A compile error is never a red** — if a first run ends in
  `BUILD FAILED` on compilation, the step has proved nothing; fix the compile and get the assertion
  failure before writing any production code. Quote the actual failure text in the task report.
- **Every check gets a positive control, and where the red *is* the control the plan says so.**
  Task 1's empty-section lookup is self-controlling (a miss returns `-1` and `isPositive()` fires);
  Task 2's pin carries two payload assertions that must pass in the red run, which is what proves
  the red is on the wording and not on a mis-built fixture.
- Test names `should<Expected>When<Condition>` with `@DisplayName`.
- **Mikado**: the gate is green before every commit. **Stage by explicit path, git stderr visible —
  never `git add -A`, never `2>/dev/null` on `git add`.** Read `git status` before every commit.
  Commits end `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`. **Never cite a
  `.superpowers/` path from a committed file.**
- Gate, **blocking, never backgrounded**:
  `SEGUE_REQUIRE_BROWSER=true SEGUE_REQUIRE_GRAPHVIZ=true ./gradlew check --rerun-tasks`. Run
  `./gradlew spotlessApply` before each gate.
- **Only JDK 25 is installed and Gradle launches on it.** Plain `./gradlew`; never
  `/usr/libexec/java_home -v 21` (it silently returns 25).
- **Never run a writing dev task** — `expandPromotions`, `own`, `ownClaim`, `retractEntity`, `rate`,
  any seeding task. `~/.segue/segue.db` is never read, written, copied or created. Every database
  here is in-memory or a `@TempDir` file.
- **No Java identifier is renamed.** `ExpansionSummary#edgesAdded` (the wire field),
  `ExpansionOutcome.Expanded#edgesAdded`, `ExpansionTally#edgesAdded` and every local `edgesAdded`
  are read, never renamed.
- **`get_entity`'s sentence is not this issue.** `SegueService.getEntity`'s
  `"<label>: N edge(s), M type(s)"` counts the graph's edges on a node and is correct. Do not touch
  it, and do not run a search-and-replace over the file.
- **`CensusReport` and the developer guide are not this issue.** The guide's step-5 table row
  `` | `edges` by source | `` is `graphCensus`'s label for a different tool. Leave it exactly as it
  is. No file under `docs/developer-guide.md` is edited by this plan.
- **After `./gradlew spotlessApply`, re-read any javadoc this plan edits** and confirm the
  `{@code …}` spans are intact. google-java-format reflows javadoc paragraphs and *will* break
  inside an inline tag — the file already carries one such wrap today. A wrapped span renders fine
  and is not a defect; what must not happen is a lost or unbalanced brace. If you want the span on
  one source line, add or remove a word from the clause *before* it so the greedy fill moves the
  whole span to the next line — never lengthen the sentence to make it fit.
- **`docs/` is a declared input of `test`** (`build.gradle.kts`), so a document edit re-runs the
  suite. Do not pass `--rerun-tasks` to the fast per-task loops; the point of running without it is
  to prove the declaration works.
- Invented ids only, ADR 58's leading zero. The two new ids in Task 2 are `Q0900299` and `Q0900300`,
  which nothing in `src/test` uses today.

---

## Task 1 — The section heading

Files: `src/test/java/com/robsartin/segue/expand/ExpansionReportTest.java`,
`src/main/java/com/robsartin/segue/expand/ExpansionReport.java`.

**Read first, so the red is not a surprise.** Unlike #293, **no column moves**. `ExpansionReport.render`
measures only counted rows:

```java
    for (Entry entry : body) {
      if (entry instanceof Row row) {
        labelWidth = Math.max(labelWidth, row.label().length());
        countWidth = Math.max(countWidth, String.valueOf(row.count()).length());
      }
    }
```

`Section` and `SubHeading` never enter either width, so the golden block is **not** re-padded and
every counted line stays byte for byte as it is. The expected diff in the test file is **exactly two
lines**. Belt and braces: even if headings were measured, `edge assertions by source` is 25
characters against the widest counted label `  edge assertions recorded` at 26, so the column would
still not move. If you find yourself re-padding counts, stop — something else is wrong.

- [ ] **Step 1 — RED. Re-pin the heading, in both places that name it.** In `ExpansionReportTest`,
  edit exactly two lines and nothing else.

  In `GOLDEN_BLOCK`, the section heading between the `graph` block and the two source rows:

  ```java
          "edge assertions by source",
  ```

  In `shouldStillPrintTheHeadingWhenASectionHasNoRows`, the lookup:

  ```java
    int index = lines.indexOf("edge assertions by source");
  ```

  Leave every counted row, the header, the blank lines and every other section heading untouched.

- [ ] **Step 2 — run it and observe a real failure.**

  ```
  ./gradlew test --tests '*ExpansionReportTest'
  ```

  Expected: **two failing tests, both assertion failures, not a compile error.**

  `shouldRenderTheWholeBlockWhenATallyIsGiven` fails with AssertJ's `containsExactlyElementsOf`
  diff, shaped like:

  ```
  but some elements were not found:
    ["edge assertions by source"]
  and others were not expected:
    ["edges by source"]
  ```

  **Quote it, and confirm each list holds exactly one element.** That single-element diff is the
  evidence the label column did not move — if every counted row also appears in the two lists, the
  `Read first` note above is wrong about `render` and the task stops for a re-read.

  `shouldStillPrintTheHeadingWhenASectionHasNoRows` fails on its own guard, because `indexOf` misses
  and returns `-1`:

  ```
  [the heading is printed even though the map is empty]
  Expecting actual:
    -1
  to be greater than:
    0
  ```

  **This is the positive control for that test**, taken for free: the assertion only means anything
  if a missing heading makes it fire, and here it did. Quote the actual text.

  If either run ends in `BUILD FAILED` on compilation instead, that is not a red — fix and re-run.

- [ ] **Step 3 — GREEN. Rename the section, and the javadoc that describes it.** In
  `ExpansionReport.body()`:

  ```java
      body.add(new Section("edge assertions by source"));
  ```

  Then the class javadoc's empty-section paragraph, which is the only prose in the file that names
  the heading. Replace the whole paragraph (the one beginning
  `<p><b>Every section prints its heading`) with:

  ```java
   * <p><b>Every section prints its heading, whether or not there is a row to show under it.</b> An
   * empty {@code edge assertions by source} means no edge assertion was recorded from any source,
   * and a reader has to be able to tell that from the section simply being gone — the same
   * distinction {@code CensusReport} does not have to draw, because its sections are never empty on
   * a real graph. Applied uniformly here rather than only where the issue names it, so the rule is
   * one rule and not a per-section judgement call.
  ```

  Nothing else in the file changes. In particular `EDGE_ASSERTIONS_RECORDED` and its javadoc stay
  exactly as #293 left them, and **no constant is introduced for the heading** — nothing outside
  this class says it, and ADR 66's output contract asks for a literal.

- [ ] **Step 4 — run it and observe it pass, and confirm the diff is two lines.**

  ```
  ./gradlew test --tests '*ExpansionReportTest'
  git diff --stat -- src/test/java/com/robsartin/segue/expand/ExpansionReportTest.java
  git diff -- src/test/java/com/robsartin/segue/expand/ExpansionReportTest.java
  ```

  Expected: `BUILD SUCCESSFUL`; report the count of tests run. The test-file diff is **1 insertion,
  1 deletion in each of two hunks** — the golden-block line and the lookup, and no counted row. If a
  counted row moved, say so and stop.

- [ ] **Step 5 — gate and commit.**

  ```
  ./gradlew spotlessApply
  ```

  Re-read `ExpansionReport`'s class javadoc after this and confirm every `{@code …}` still has its
  closing brace (see the Global Constraint — a span wrapped across two source lines is normal here
  and is not a defect).

  ```
  SEGUE_REQUIRE_BROWSER=true SEGUE_REQUIRE_GRAPHVIZ=true ./gradlew check --rerun-tasks
  git status
  git add src/main/java/com/robsartin/segue/expand/ExpansionReport.java \
          src/test/java/com/robsartin/segue/expand/ExpansionReportTest.java
  git status
  git commit
  ```

  Message: `Say edge assertions on the expander's per-source heading (#299)`.

  **Note for the report:** this commit leaves ADR 66's output contract naming the old heading, and
  no test can red on that. Task 3 closes it, and Task 3 must land.

---

## Task 2 — `expand_entity`'s clean-expansion sentence

Files: `src/test/java/com/robsartin/segue/mcp/SegueServiceTest.java`,
`src/main/java/com/robsartin/segue/mcp/SegueService.java`, `docs/user-guide.md`.

**Read first.** The sentence is **unpinned today**. The nearest assertion in the file,
`expandEntityDoesNotCountRefreshedInlineNeighboursAsAdded`'s
`assertThat(result.detail()).contains("0 new node(s)")`, checks the half of the sentence this task
does not touch — it stays green whichever word the other half uses and is **not** coverage. So the
pin is created here, and it is the red.

- [ ] **Step 1 — RED. Pin the whole sentence.** In `SegueServiceTest`, immediately after
  `expandEntityCountsNodesOnceAndAssertionsSeparately`, add:

  ```java
    @Test
    @DisplayName("a clean expansion's sentence counts assertions, not pairs of nodes")
    void shouldNameTheCountAsAssertionsWhenACleanExpansionReportsItsYield() {
      // Nothing pinned this sentence before #299 — the nearest assertion in this file checks the
      // "new node(s)" half, which this change does not touch, so it could never have caught the
      // other half being wrong. Two assertions about one pair of nodes, so the number the sentence
      // quotes is visibly the assertion count and not the graph's edge count: ExpansionSummary's
      // javadoc for edgesAdded is the authority, and the sentence now says what that javadoc says.
      String seed = "Q0900299";
      String neighbour = "Q0900300";
      ingest.record(new NodeAssertion(seed, NodeKind.PERSON, "an act nobody signed", WIKIDATA));
      resolver.withEntity(
          new NodeAssertion(neighbour, NodeKind.WORK, "a film nobody shot", WIKIDATA));
      AssertionRecord wrote =
          new AssertionRecord(seed, neighbour, "WROTE_SCREENPLAY_FOR", null, null, WIKIDATA);
      AssertionRecord scored =
          new AssertionRecord(seed, neighbour, "COMPOSED_FOR", null, null, WIKIDATA);
      SourceAdapter adapter =
          new StubSourceAdapter(
              "multigraph", new ExpandResult(List.of(wrote, scored), false, false));

      ToolResult<SegueService.ExpansionSummary> result = service(adapter).expandEntity(seed, 10);

      assertThat(result.outcome()).isEqualTo(ToolResult.Outcome.OK);
      // These two are the control on the fixture: they must PASS in the red run below, or the
      // sentence's failure would be a mis-built expansion rather than the wrong word.
      assertThat(result.payload().edgesAdded()).isEqualTo(2);
      assertThat(result.payload().nodesAdded()).isEqualTo(1);
      assertThat(result.detail())
          .as("the sentence a model reads, in the words the payload's own javadoc already uses")
          .isEqualTo("expanded Q0900299: 2 edge assertion(s), 1 new node(s)");
    }
  ```

  The expected string is a **literal**, qid included, rather than built from `seed` — the point of
  the pin is to catch the sentence's own text moving, and assembling the expectation from the same
  pieces the code assembles it from would prove less.

- [ ] **Step 2 — run it and observe a real failure.**

  ```
  ./gradlew test --tests '*SegueServiceTest'
  ```

  Expected: **exactly one failing test, an assertion failure, not a compile error**, shaped like:

  ```
  [the sentence a model reads, in the words the payload's own javadoc already uses]
  expected: "expanded Q0900299: 2 edge assertion(s), 1 new node(s)"
   but was: "expanded Q0900299: 2 edge(s), 1 new node(s)"
  ```

  **Report the actual text, and state that the two payload assertions above it passed.** If either
  of them failed instead, the fixture is wrong and the pin proves nothing about the wording: fix the
  fixture, get this same red, and only then continue.

- [ ] **Step 3 — GREEN. Change the sentence.** In `SegueService.shape`, the clean return:

  ```java
      if (reasons.isEmpty()) {
        return ToolResult.ok(
            "expanded "
                + qid
                + ": "
                + edgesAdded
                + " edge assertion(s), "
                + nodesAdded
                + " new node(s)",
            summary);
      }
  ```

  Let `spotlessApply` decide the final line breaks. **Nothing else in the file changes** — not
  `ExpansionSummary`, whose `@param edgesAdded` javadoc already says *"per assertion, not per pair
  of nodes"* and is what this sentence is being brought into line with, and not `getEntity`.

- [ ] **Step 4 — run it and observe it pass.**

  ```
  ./gradlew test --tests '*SegueServiceTest'
  ```

  Expected: `BUILD SUCCESSFUL`. Report the count of tests run.

- [ ] **Step 5 — follow the two places that quote the sentence, neither of which can red.**

  (a) In `SegueServiceTest`, `shouldRefuseWithNoSourceToExpandFromWhenTheSeedIsALocalEntity`'s
  comment quotes the old sentence. It is prose inside a test and nothing checks it, so it goes stale
  silently. Change the last line of that comment to:

  ```java
      // meanings an empty ExpandResult already carries — "found nothing" and "the source was
      // unavailable". A third meaning would rebuild the defect ADR 56 fixed, so the refusal is
      // said out loud instead. The adapter here returns the "found nothing" shape: without the
      // refusal this call succeeds with a truthful-looking "0 edge assertion(s), 0 new node(s)".
  ```

  (b) In `docs/user-guide.md`, the one transcript that quotes it (in *Being in Wikidata is not the
  same as being connected in it*):

  ```json
    "detail": "expanded Q24525280: 0 edge assertion(s), 0 new node(s)",
  ```

  The `payload` line beneath it is unchanged — `edgesAdded` is the wire field and does not move.

  **Say in the report that neither of these can red.** `GuideExamples` reads only
  `docs/developer-guide.md`; `DocumentationLinksTest` walks every `.md` under `docs/` but resolves
  links and anchors and asserts nothing about a sentence. `docs` is a declared input of `test`, so
  the edit does re-run the suite — that proves the declaration works, not that anything saw the
  string. This is prose verified by the gate.

- [ ] **Step 6 — confirm `get_entity` was not caught by the change.**

  ```
  grep -n 'edge(s)' src/main/java/com/robsartin/segue/mcp/SegueService.java
  grep -rn 'edge(s)' docs/user-guide.md
  ```

  Expected: **exactly one hit in `SegueService.java`**, in `getEntity`'s
  `"<label>: N edge(s), M type(s)"` — that one counts the graph's edges on a node, is correct, and
  stays. Expected in `docs/user-guide.md`: **exactly the two `get_entity` transcripts**
  (`"Nick Cave: 0 edge(s), 0 type(s)"` and `"Nick Cave: 89 edge(s), 9 type(s), rated"`), and no
  `expanded …` line.

  **State the limit of that grep in the report**: it proves what these two files still say, not that
  no other file says `edge(s)`. The other sites in the tree — `RecommendRun`, `RetractRun`,
  `ExportRun`, `DotWriter`, `GraphView`, and their tests — are enumerated in the spec's *Not this
  issue* and are deliberately untouched.

- [ ] **Step 7 — gate and commit.**

  ```
  ./gradlew spotlessApply
  SEGUE_REQUIRE_BROWSER=true SEGUE_REQUIRE_GRAPHVIZ=true ./gradlew check --rerun-tasks
  git status
  git add src/main/java/com/robsartin/segue/mcp/SegueService.java \
          src/test/java/com/robsartin/segue/mcp/SegueServiceTest.java \
          docs/user-guide.md
  git status
  git commit
  ```

  Message: `Say edge assertions in expand_entity's clean-expansion sentence (#299)`.

---

## Task 3 — ADR 66's dated amendment

File: `docs/adr/0066-expand-every-promotion-from-a-dev-tool.md`.

**No test of its own, and that is said out loud rather than left to be inferred.** This is prose with
no unit-testable behaviour. It is verified by the full gate and by nothing else: `AdrIndexTest` (the
`docs/adr/README.md` row still agrees with the unchanged heading and front matter — **do not touch
either**), `AdrCitationsTest` (**no seven-to-forty hex-character token inside a code span may appear
anywhere under `docs/adr`**), and `DocumentationLinksTest` (the relative links below must resolve;
`0026-mcp-tool-surface.md` exists beside this file and is already linked from it).

**The ADR is immutable: this is a pure append.** Nothing above the insertion point is edited — in
particular the 2026-09-08 amendment for #293, whose sentence *"`edges by source` … keeps its
heading: … renaming it is a separate decision nobody has asked for"* stays exactly as written. The
new amendment records that the ask arrived; it does not correct the old text.

- [ ] **Step 1 — append the amendment.** At the very end of the file, after the last line of the
  2026-09-08 amendment for #293, leave one blank line and add:

  ```markdown
  **Amendment (2026-09-08, issue #299): the per-source heading, and `expand_entity`'s detail
  sentence.**

  Nothing above is withdrawn, no decision above is edited, and this ADR keeps `Accepted`.

  *The output contract* above names a section `edges by source`. It is now printed as
  **`edge assertions by source`**. Nothing about what it counts changed: those rows are the same
  increments, broken down per adapter id, that the row above them sums as `edge assertions
  recorded`. The amendment for #293 renamed that row and left this heading, saying renaming it was
  a separate decision nobody had asked for. #299 is that ask, and this records it; the sentence
  above stands as written.

  **The `nodes added` paragraph in the output contract still holds, word for word, under the new
  name.** It says `nodes added` sits under `graph` and not under the per-source section, and its
  reason is that every `AssertionRecord` carries a `Provenance` whose `sourceId` says which adapter
  produced it while a node's identity may instead come from `EntityResolver.fetch`, which has no
  adapter behind it. That is a contrast between two sections and the authority each has for a
  source id, not a claim about the word "edges", so renaming the section changes neither side of
  it.

  **No column moves, and that is the difference from #293.** `ExpansionReport.render` derives the
  label column and the count column from counted rows alone; a section heading is never measured.
  So unlike the longer row label, which shifted every count in the block, this rename shifts
  nothing. `ExpansionReportTest`'s golden block pins the heading as a literal and its
  empty-section test looks the heading up by the same text, so both carry the new name and a
  missing heading still reds. The heading stays an inline literal rather than becoming a named
  constant: nothing outside `ExpansionReport` says it, and the contract above asks for a literal.

  **`expand_entity`'s detail sentence has moved.** The amendment for #293 recorded that
  `SegueService`'s clean-expansion sentence still said `edge(s)` of the same number, that it was
  seen and left, and that changing it was a separate issue. It now reads
  `expanded <qid>: N edge assertion(s), M new node(s)`.

  **That is not a protocol change.** The payload field `edgesAdded`
  ([ADR 26](0026-mcp-tool-surface.md)) is untouched, and so is every Java identifier behind it. What
  moved is the human-readable `detail` string, which no client parses — a caller that wants the
  number reads the field, and `ExpansionSummary`'s javadoc for that field already said the count is
  per assertion rather than per pair of nodes, which is what the sentence now says too. The tool
  surface ADR 26 governs is unchanged, so ADR 26 needs no amendment of its own. Nothing in
  `src/test` pinned that sentence before #299; a pin was added with the change, and it was seen red
  on the old wording first.

  `get_entity`'s sentence is a different quantity and stays as it is. It counts the edges the graph
  holds on one node, after corroborating assertions have been merged into single edges, so
  `edge(s)` is the correct word for it — the same word in a neighbouring method for a number that
  really is an edge count.
  ```

- [ ] **Step 2 — check the three things that would fail the build, before the gate.**

  ```
  git diff -- docs/adr | grep -nE '`[0-9a-fA-F]{7,40}`' || echo 'no hash-shaped code span in the ADR diff'
  git diff -- docs/adr | grep -n 'superpowers' || echo 'no .superpowers path in the ADR diff'
  git diff --stat -- docs/adr/README.md
  ```

  Expected: no hash-shaped code span, no `superpowers` path, and **an empty diffstat for
  `docs/adr/README.md`** — an amendment changes no heading and no front matter, so the index row
  must not move. Note for the writer: an invented qid such as `Q0900299` is seven hex digits behind
  a `Q`, so keep test identifiers out of this document entirely; none appears in the text above.

  Also confirm the append did not disturb what came before:

  ```
  git diff -- docs/adr/0066-expand-every-promotion-from-a-dev-tool.md
  ```

  Expected: **additions only**, no deleted line anywhere in the hunk.

- [ ] **Step 3 — gate, blocking.**

  ```
  ./gradlew spotlessApply
  SEGUE_REQUIRE_BROWSER=true SEGUE_REQUIRE_GRAPHVIZ=true ./gradlew check --rerun-tasks
  ```

  Expected: `BUILD SUCCESSFUL`, with `AdrIndexTest`, `AdrCitationsTest` and `DocumentationLinksTest`
  among the tests run. Name them in the report — they are this task's only verification.

- [ ] **Step 4 — commit.**

  ```
  git status
  git add docs/adr/0066-expand-every-promotion-from-a-dev-tool.md
  git status
  git commit
  ```

  Message: `Amend ADR 66 for the renamed heading and the moved sentence (#299)`.

---

## Definition of done

- [ ] The block prints `edge assertions by source`, pinned as a literal in `ExpansionReportTest`'s
  golden block and looked up by the same text in the empty-section test, both seen red first.
- [ ] The golden block's counted rows are unchanged, confirmed from the diff — no column moved.
- [ ] `ExpansionReport`'s class javadoc names the new heading in its empty-section paragraph.
- [ ] `expand_entity`'s clean sentence reads `expanded <qid>: N edge assertion(s), M new node(s)`,
  pinned by a new `SegueServiceTest` test seen red on `edge(s)`, whose payload assertions passed in
  that red run.
- [ ] The user guide's one transcript and the stale test comment follow, both recorded as prose
  nothing can red on.
- [ ] `get_entity`'s sentence is untouched, and `SegueService.java` still holds exactly one
  `edge(s)`.
- [ ] No Java identifier renamed; the `edgesAdded` payload field untouched; ADR 26 unamended;
  `CensusReport` and `docs/developer-guide.md` untouched.
- [ ] ADR 66 carries a 2026-09-08 amendment for #299, appended with no deletion above it, naming
  the old and new heading, saying the `nodes added` contrast still holds, saying no column moved,
  and saying why the sentence is not a protocol change — with no commit citation, no figure from
  the owner's graph and no `.superpowers/` path.
- [ ] `SEGUE_REQUIRE_BROWSER=true SEGUE_REQUIRE_GRAPHVIZ=true ./gradlew check --rerun-tasks` is
  green on the final commit.

## Report to the requester

- **The issue's claim that "the developer guide's chapter that lists the block's sections follows"
  is wrong: no chapter lists them and the developer guide is not edited.** `edges by source` appears
  in `docs/` only inside ADR 66 and the superpowers documents. `DeveloperGuideExpandPromotionsExamplesTest`
  reads the *row* label off `ExpansionReport.EDGE_ASSERTIONS_RECORDED` and names no section heading,
  so it neither reds nor changes.
- **The guide does carry a look-alike that must not be touched**: step 5's
  `` | `edges` by source | `` row is `CensusReport`'s label for `graphCensus`. A search-and-replace
  over the guide would have corrupted the runbook's comparison table.
- **`SegueServiceTest` did not pin the sentence**, and the nearest assertion (`contains("0 new
  node(s)")`) checks the untouched half, so it could not have red. The pin is created by Task 2.
- **Nothing can red on the user guide transcript.** No test reads `docs/user-guide.md`'s sentences;
  `docs` being a declared input of `test` re-runs the suite but pins no string. Recorded as prose
  verified by the gate rather than left to be inferred.
- **Section headings do not enter `ExpansionReport`'s width computation**, so unlike #293 this
  rename re-pads nothing.
