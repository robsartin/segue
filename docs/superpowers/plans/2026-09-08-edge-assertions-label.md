# The expander's edge line is an assertion count, and the label says so — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** close issue #293 — the expander's summary block, its per-entity progress line, the tally's
javadoc and the developer guide's runbook stop calling an assertion count "edges added" and call it
`edge assertions recorded`; ADR 66's output contract gains a dated amendment saying so. No Java
identifier changes, no MCP payload field changes, no new count is added.

**Architecture:** One new `public static final String` on `ExpansionReport`, used by `body()` and
read by `DeveloperGuideExpandPromotionsExamplesTest` so the runbook row and the printed line cannot
drift. `ExpansionReportTest`'s golden block stays a literal — it is the pin on the constant's own
text. `ExpandRun.detail()` and three prose sites follow.

**Tech Stack:** Java 25, Gradle (plain `./gradlew`), JUnit 5, AssertJ, TinkerGraph, SQLite.

**Spec:** `docs/superpowers/specs/2026-09-08-edge-assertions-label-design.md`

## Global Constraints

- **Pure TDD / red first.** Every behaviour: write the test, **run it, observe a real assertion
  failure**, then the minimum code. **A compile error is never a red** — if a first run ends in
  `BUILD FAILED` on compilation, the step has proved nothing; fix the compile and get the assertion
  failure before writing any production code. Quote the actual failure text in the task report.
- **Every guard gets a positive control.** Task 2's guide check is a guard over a document: its
  first red is on the real, pre-existing stale row, and it also gets a **planted control on the row
  lookup** so it cannot pass vacuously when the row moves. The plan writes the plant out.
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
  here is a `@TempDir` file or an in-memory `TinkerGraphStore`.
- **No Java identifier is renamed.** `ExpansionOutcome.Expanded#edgesAdded`,
  `ExpansionTally#edgesAdded` and `ExpandRun.run`'s local `edgesAdded` are read, never renamed. The
  MCP payload field and `SegueService`'s sentences are not touched.
- **No new count, no moved row, no moved section.** `edges by source` keeps its heading;
  `nodes added` and the edge row both stay under `graph`.
- **After `./gradlew spotlessApply`, re-read any javadoc this plan edits** and confirm no
  `{@code …}` was re-wrapped across two source lines. If one was, break the paragraph rather than
  lengthen the sentence.
- **`docs/` is a declared input of `test`** (`build.gradle.kts`), so a document edit re-runs the
  suite. Do not pass `--rerun-tasks` to the fast per-task loops; the point of running without it is
  to prove the declaration works.
- Invented ids only, ADR 58's leading zero. The two new ids in Task 3 are `Q0900961` and `Q0900962`,
  which nothing in `src/test` uses today.

---

## Task 1 — The printed label, and the constant behind it

Files: `src/test/java/com/robsartin/segue/expand/ExpansionReportTest.java`,
`src/main/java/com/robsartin/segue/expand/ExpansionReport.java`,
`src/main/java/com/robsartin/segue/expand/ExpansionTally.java`.

**Read first, so the red is not a surprise.** `ExpansionReport.render` derives the label column from
the widest counted label in the whole block. Today that is `  bound cut the result` (22 characters);
`  edge assertions recorded` is 26. **Every counted row in the golden block gains four spaces**, and
so do both pinned strings in `shouldAlignEveryColumnWhenTheCountsDifferInWidth`. That is the
alignment rule working.

- [ ] **Step 1 — RED. Re-pin the golden block.** In `ExpansionReportTest`, replace the sixteen
  counted rows of `GOLDEN_BLOCK` with these, leaving the header, the blank lines and the section
  headings exactly as they are:

  ```java
          "  considered                12",
          "  expanded                   9",
          "  added nothing              2",
          "  refused                    2",
          "  failed                     1",
          "",
          "graph",
          "  nodes added               34",
          "  edge assertions recorded  77",
          "",
          "edges by source",
          "  wikidata                  60",
          "  musicbrainz               17",
          "",
          "shortfalls",
          "  neighbours skipped         5",
          "  endpoints refused          3",
          "  bound cut the result       1",
          "  unavailable",
          "    musicbrainz              1",
          "  truncated",
          "    wikidata                 2",
          "",
          "refused, by reason",
          "  unknown entity             1",
          "  local entity               1");
  ```

  In the same file, re-pin `shouldAlignEveryColumnWhenTheCountsDifferInWidth`:

  ```java
    assertThat(considered).isEqualTo("  considered                100000");
    assertThat(failed).isEqualTo("  failed                         0");
  ```

- [ ] **Step 2 — run it and observe a real failure.**

  ```
  ./gradlew test --tests '*ExpansionReportTest'
  ```

  Expected: **two failing tests, both assertion failures, not a compile error.**

  `shouldRenderTheWholeBlockWhenATallyIsGiven` fails with AssertJ's
  `containsExactlyElementsOf` diff. The report must quote the two lines that carry the label:

  ```
  but some elements were not found:
    [..., "  edge assertions recorded  77", ...]
  and others were not expected:
    [..., "  edges added           77", ...]
  ```

  and must note that **every counted row appears in both lists**, because the column moved.

  `shouldAlignEveryColumnWhenTheCountsDifferInWidth` fails with:

  ```
  expected: "  considered                100000"
   but was: "  considered            100000"
  ```

  If either run ends in `BUILD FAILED` on compilation instead, that is not a red — fix and re-run.

- [ ] **Step 3 — GREEN. Add the constant and use it.** In `ExpansionReport`, directly after
  `DRY_RUN_HEADER` and before `private static final String GAP`, add:

  ```java
  /**
   * The {@code graph} section's second label, and the one the runbook cites (#293).
   *
   * <p><b>It counts assertions, not edges.</b> {@code EntityExpansion} increments once per edge
   * assertion it records, and an edge the graph already holds is recorded again — corroboration
   * and freshness are what that is for (ADR 19). So this row stands above the graph's net gain,
   * which {@code graphCensus} is the authority on and this tool never sees.
   *
   * <p>{@code nodes added} beside it <i>is</i> a net count. After #293 these two labels are the
   * only thing that says which of the two the reader is looking at.
   *
   * <p><b>A constant rather than a second literal.</b> Two documents say this label: this block,
   * and the developer guide's runbook row for {@code claims} / log rows.
   * {@code DeveloperGuideExpandPromotionsExamplesTest} reads it from here, so the row and the
   * printed line cannot drift apart. {@code ExpansionReportTest}'s golden block still pins the
   * text itself as a literal, exactly as it pins {@link #HEADER}: reading the pin off the constant
   * it is meant to pin would prove nothing.
   */
  public static final String EDGE_ASSERTIONS_RECORDED = "edge assertions recorded";
  ```

  Then, in `body()`, replace the edge row:

  ```java
      body.add(new Row("  " + EDGE_ASSERTIONS_RECORDED, tally.edgesAdded()));
  ```

  In `ExpansionTally`, replace the `edgesAdded` javadoc param with:

  ```java
   * @param edgesAdded edge assertions recorded, summed across every expanded entity — {@link
   *     ExpansionOutcome.Expanded#edgesAdded()} is the authority on what one of them counts: one
   *     increment per assertion appended, so an edge the graph already held is counted again
   *     (#293)
  ```

- [ ] **Step 4 — run it and observe it pass.**

  ```
  ./gradlew test --tests '*ExpansionReportTest'
  ```

  Expected: `BUILD SUCCESSFUL`. Report the count of tests run.

- [ ] **Step 5 — gate and commit.**

  ```
  ./gradlew spotlessApply
  SEGUE_REQUIRE_BROWSER=true SEGUE_REQUIRE_GRAPHVIZ=true ./gradlew check --rerun-tasks
  git status
  git add src/main/java/com/robsartin/segue/expand/ExpansionReport.java \
          src/main/java/com/robsartin/segue/expand/ExpansionTally.java \
          src/test/java/com/robsartin/segue/expand/ExpansionReportTest.java
  git status
  git commit
  ```

  Message: `Label the expander's edge line as the assertion count it is (#293)`.

  **Note for the report:** this commit leaves the developer guide and ADR 66 naming the old label,
  and no test can red on that — which is exactly the gap Task 2 closes. Tasks 2 to 4 must land.

---

## Task 2 — The runbook row, tied to the constant

Files: `src/test/java/com/robsartin/segue/expand/DeveloperGuideExpandPromotionsExamplesTest.java`,
`docs/developer-guide.md`.

**The issue asserts this test already reads the label from the constant. It does not** — its five
tests check the chapter's presence, its `--args` examples, tildes, unreadable lines and step order,
and none of them reads any printed label. This task creates that property.

- [ ] **Step 1 — RED. Add the check.** In `DeveloperGuideExpandPromotionsExamplesTest`, add the
  field beside `CHAPTER`:

  ```java
    /** Step 5's row that cites the block's edge count, matched by its own leading cell. */
    private static final String LOG_ROWS_ROW = "| `claims` / log rows |";
  ```

  and add this test after `shouldShowTheChapterWhenTheGuideDocumentsAPromotionExpansion`:

  ```java
    @Test
    @DisplayName("the runbook's log-rows row cites the label the block actually prints")
    void shouldCiteThePrintedLabelWhenTheRunbookExplainsTheLogRows() {
      String row =
          GuideExamples.chapterText(CHAPTER).orElseThrow().lines()
              .filter(line -> line.startsWith(LOG_ROWS_ROW))
              .findFirst()
              .orElseThrow(
                  () ->
                      new AssertionError(
                          "docs/developer-guide.md, '"
                              + CHAPTER
                              + "' — no step-5 row starting '"
                              + LOG_ROWS_ROW
                              + "'. That row is the only place the guide names the block's edge"
                              + " count, and a lookup that finds nothing has to say so rather"
                              + " than leave the assertion below with nothing to check"));

      assertThat(row)
          .as(
              "docs/developer-guide.md, '%s' — this row names a label ExpansionReport prints, so"
                  + " it is read from ExpansionReport.EDGE_ASSERTIONS_RECORDED and never typed"
                  + " again here. The count is per assertion and the label is what says so (#293)",
              CHAPTER)
          .contains(ExpansionReport.EDGE_ASSERTIONS_RECORDED);
    }
  ```

  `ExpansionReport` is in this test's own package, so no import is added.

  **Why the row and not the chapter.** A `contains` over the whole chapter text would go green on
  Step 4's new sentence while the table row still said the old thing — a check narrower than the
  claim it makes.

- [ ] **Step 2 — run it and observe a real failure.** The guide is stale today, so the defect the
  guard is for is already planted:

  ```
  ./gradlew test --tests '*DeveloperGuideExpandPromotionsExamplesTest'
  ```

  Expected, an assertion failure and not a compile error:

  ```
  java.lang.AssertionError: [docs/developer-guide.md, 'Expanding every promotion' — this row names a label ExpansionReport prints, ...]
  Expecting actual:
    "| `claims` / log rows | up by at least the edges added | every recorded assertion is a row ([ADR 19](adr/0019-assertion-log-source-of-truth.md)) |"
  to contain:
    "edge assertions recorded"
  ```

- [ ] **Step 3 — positive control on the lookup.** The assertion above proves the *comparison* can
  fail; this proves the *lookup* can. Temporarily edit `docs/developer-guide.md` line ~3181, changing
  only the row's first cell from ``| `claims` / log rows |`` to ``| `claims` / rows |``, then:

  ```
  ./gradlew test --tests '*DeveloperGuideExpandPromotionsExamplesTest'
  ```

  Expected — a different failure, naming the missing row rather than silently passing:

  ```
  java.lang.AssertionError: docs/developer-guide.md, 'Expanding every promotion' — no step-5 row starting '| `claims` / log rows |'. ...
  ```

  **Revert the plant** (`git checkout -- docs/developer-guide.md`) and confirm `git status` shows the
  guide unmodified before going on. Quote both failure texts in the task report.

- [ ] **Step 4 — GREEN. Correct the guide.** Two edits in `docs/developer-guide.md`, both inside
  `## Expanding every promotion`.

  The step-5 table row (~line 3181) becomes:

  ```markdown
  | `claims` / log rows | up by at least the edge assertions recorded | every recorded assertion is a row ([ADR 19](adr/0019-assertion-log-source-of-truth.md)) |
  ```

  And the paragraph in *3. The run* that explains the block — the one ending *"Then one aggregate
  block at the end, in `graphCensus`'s shape and safe to paste for the same reason."* — gains one
  sentence, appended to that paragraph:

  ```markdown
  **`edge assertions recorded` in that block counts assertions and not new edges**: an edge the graph already holds is recorded again, which is how corroboration and freshness work ([ADR 19](adr/0019-assertion-log-source-of-truth.md)), so it stands above the `edges` / total movement step 5 compares — the census is the authority on what the graph actually gained.
  ```

- [ ] **Step 5 — run it and observe it pass.**

  ```
  ./gradlew test --tests '*DeveloperGuideExpandPromotionsExamplesTest'
  ```

  Expected: `BUILD SUCCESSFUL`, six tests run — and **not** `Task :test UP-TO-DATE`, which would
  mean the `docs` input declaration had stopped working. Say which of the two the run printed.

- [ ] **Step 6 — gate and commit.**

  ```
  ./gradlew spotlessApply
  SEGUE_REQUIRE_BROWSER=true SEGUE_REQUIRE_GRAPHVIZ=true ./gradlew check --rerun-tasks
  git status
  git add docs/developer-guide.md \
          src/test/java/com/robsartin/segue/expand/DeveloperGuideExpandPromotionsExamplesTest.java
  git status
  git commit
  ```

  Message: `Tie the runbook's edge row to the label the block prints (#293)`.

---

## Task 3 — The per-entity progress line

Files: `src/test/java/com/robsartin/segue/expand/ExpandRunTest.java`,
`src/main/java/com/robsartin/segue/expand/ExpandRun.java`,
`src/test/java/com/robsartin/segue/expand/ExpansionIsSafeToPasteTest.java`,
`docs/developer-guide.md`.

`ExpandRunTest` pins the *partial* progress-line form twice and pins neither of the clean form's two
counts. That gap is why `detail()` could say `edge(s)` unchallenged.

- [ ] **Step 1 — RED. Pin the clean progress line.** Add to `ExpandRunTest`, after
  `shouldSayWhatFellShortWhenAnExpansionWasPartial`:

  ```java
    @Test
    @DisplayName("a clean expansion's progress line counts assertions, not pairs of nodes")
    void shouldNameTheCountAsAssertionsWhenACleanExpansionReportsItsYield() {
      String seed = "Q0900961";
      String neighbour = "Q0900962";

      try (AssertionLog scriptLog = new SqliteAssertionLog(dir.resolve("yield.db"));
          GraphStore scriptGraph = new TinkerGraphStore()) {
        IngestService ingest = new IngestService(scriptLog, scriptGraph, IdentityMerge.NONE);
        ingest.record(new NodeAssertion(seed, NodeKind.PERSON, "an act nobody signed", WIKIDATA));
        ScriptedResolver resolver =
            new ScriptedResolver()
                .withEntity(
                    new NodeAssertion(neighbour, NodeKind.GROUP, "a band nobody named", WIKIDATA));
        ScriptedAdapter adapter =
            new ScriptedAdapter(
                "wikidata", Map.of(seed, ExpandResult.of(List.of(memberOf(seed, neighbour)))));
        ExpandRun scriptedRun =
            new ExpandRun(
                new EntityExpansion(
                    resolver, scriptGraph, ingest, new SourceAdapters(List.of(adapter))),
                scriptGraph);
        List<String> lines = new ArrayList<>();

        scriptedRun.run(List.of(seed), 10, lines::add);

        assertThat(lines)
            .as(
                "the first progress-line form, in the block's own words: one increment per"
                    + " assertion recorded, never per pair of nodes (#293)")
            .contains("[1/1] 1 edge assertion(s), 1 new node(s)");
      }
    }
  ```

- [ ] **Step 2 — run it and observe a real failure.**

  ```
  ./gradlew test --tests '*ExpandRunTest'
  ```

  Expected, an assertion failure and not a compile error:

  ```
  java.lang.AssertionError: [the first progress-line form, in the block's own words: ...]
  Expecting ArrayList:
    ["[1/1] 1 edge(s), 1 new node(s)", "# segue promotion expansion — aggregates only: ...", ...]
  to contain:
    ["[1/1] 1 edge assertion(s), 1 new node(s)"]
  but could not find the following element(s):
    ["[1/1] 1 edge assertion(s), 1 new node(s)"]
  ```

  The `"[1/1] 1 edge(s), 1 new node(s)"` in the actual list is what proves the test drove the code
  it meant to. Quote it.

- [ ] **Step 3 — GREEN. Change the sentence.** In `ExpandRun.detail()`, the clean-return line:

  ```java
      return one.edgesAdded() + " edge assertion(s), " + one.nodesAdded() + " new node(s)";
  ```

- [ ] **Step 4 — run it and observe it pass.**

  ```
  ./gradlew test --tests '*ExpandRunTest'
  ```

  Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5 — follow the two places that quote that line.** Neither can red, and both would
  otherwise be stale samples of a sentence that moved. Say so in the report rather than letting them
  look like tested changes.

  In `ExpansionIsSafeToPasteTest.shouldFireWhenAQidComesFromAnyLoggerButTheSharedExpansion`, the
  hand-built sample of an ordinary progress line:

  ```java
      assertThat(carriesAnIdItMayNot(from(ExpandRun.class.getName(), "[1/3] 4 edge assertion(s)")))
          .as("an ordinary progress line, which is what the guard must not red on")
          .isFalse();
  ```

  It is an invented input to the qid guard, not a pin on `detail()`; it changes so the guard keeps
  being handed something that looks like what the tool prints.

  In `docs/developer-guide.md`, *3. The run*, the first of the four example progress lines
  (~line 3124):

  ```markdown
  that expansion did — `[17/431] 12 edge assertion(s), 4 new node(s)`, `[18/431] refused: LOCAL_ENTITY`,
  ```

- [ ] **Step 6 — gate and commit.**

  ```
  ./gradlew spotlessApply
  SEGUE_REQUIRE_BROWSER=true SEGUE_REQUIRE_GRAPHVIZ=true ./gradlew check --rerun-tasks
  git status
  git add src/main/java/com/robsartin/segue/expand/ExpandRun.java \
          src/test/java/com/robsartin/segue/expand/ExpandRunTest.java \
          src/test/java/com/robsartin/segue/expand/ExpansionIsSafeToPasteTest.java \
          docs/developer-guide.md
  git status
  git commit
  ```

  Message: `Say edge assertion(s) on the per-entity progress line (#293)`.

---

## Task 4 — ADR 66's dated amendment

File: `docs/adr/0066-expand-every-promotion-from-a-dev-tool.md`.

**No test of its own, and that is said out loud rather than left to be inferred.** This is prose with
no unit-testable behaviour. It is verified by the full gate and by nothing else: `AdrIndexTest` (the
`docs/adr/README.md` row still agrees with the unchanged heading and front matter — **do not touch
either**), `AdrCitationsTest` (**no commit hash may appear anywhere under `docs/adr`**), and
`DocumentationLinksTest` (both relative links below must resolve; `0019-assertion-log-source-of-truth.md`
and `0026-mcp-tool-surface.md` both exist beside this file and are already linked from it).

- [ ] **Step 1 — append the amendment.** At the very end of the file, after the last bullet of
  `## Consequences`, leave one blank line and add:

  ```markdown
  **Amendment (2026-09-08, issue #293): the `graph` section's second label is corrected.**

  Nothing above is withdrawn, no decision above is edited, and this ADR keeps `Accepted`.

  *The output contract* above names the `graph` section's two rows as *nodes added, edges added*. The
  second is now printed as **`edge assertions recorded`**. Nothing about what it counts changed: it
  was always one increment per edge assertion `EntityExpansion` recorded, and an edge the graph
  already holds is recorded again, because that is how corroboration and freshness work
  ([ADR 19](0019-assertion-log-source-of-truth.md)). `nodes added` beside it is a net count, and
  after this change the two labels are the only thing that tells a reader which is which.

  **The reason is the first full run, reported in #284's closing comment.** The tool's figure and the
  census's edge total before and after did not agree, and a reader taking `edges added` at its word
  would have read the tool's number as the graph's gain. No figure from that reading is restated
  here: `graphCensus` is the authority on the graph and the issue is the record of the run, and a
  number copied into this document could only go stale.

  The label is `ExpansionReport.EDGE_ASSERTIONS_RECORDED` — still a literal in `ExpansionReport`, as
  the contract above requires, and now a named one because the developer guide's runbook cites it
  too. `DeveloperGuideExpandPromotionsExamplesTest` reads that constant when it checks the runbook's
  `claims` / log rows row, so the row and the printed line cannot drift; `ExpansionReportTest`'s
  golden block still pins the text itself as a literal, as it pins every other label. `ExpandRun`'s
  per-entity progress line says `edge assertion(s)` for the same reason.

  Nothing else in the block moves. The section names, their order, the empty-section rule and the
  rule that widths are derived from the whole block are unchanged — the last of those is why the
  longer label shifted every count in the block by four characters, which is the rule working rather
  than a second change. `edges by source`, which is the same quantity broken down, keeps its heading:
  it sits directly under the renamed row and sums to it, and renaming it is a separate decision
  nobody has asked for.

  The MCP surface is untouched. `expand_entity`'s `edgesAdded` payload field
  ([ADR 26](0026-mcp-tool-surface.md)) keeps its name, because renaming a wire field is a protocol
  change for clients and `SegueService`'s javadoc already says the field counts per assertion rather
  than per pair of nodes. So do the Java names `ExpansionOutcome.Expanded#edgesAdded` and
  `ExpansionTally#edgesAdded`; only the printed label and the prose moved.
  ```

- [ ] **Step 2 — check the two things that would fail the build, before the gate.**

  ```
  git diff -- docs/adr | grep -nE '[0-9a-f]{7,40}' || echo 'no hash-shaped token in the ADR diff'
  git diff -- docs/adr | grep -n 'superpowers' || echo 'no .superpowers path in the ADR diff'
  git diff --stat -- docs/adr/README.md
  ```

  Expected: no hash-shaped token, no `superpowers` path, and **an empty diffstat for
  `docs/adr/README.md`** — an amendment changes no heading and no front matter, so the index row must
  not move.

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

  Message: `Amend ADR 66 for the corrected edge label (#293)`.

---

## Definition of done

- [ ] The block prints `edge assertions recorded`, and `ExpansionReportTest`'s golden block pins it
  as a literal.
- [ ] `ExpandRun`'s clean progress line says `edge assertion(s)`, pinned by a test that was seen red
  on `edge(s)`.
- [ ] The runbook row reads `ExpansionReport.EDGE_ASSERTIONS_RECORDED`, seen red on the stale guide
  and seen to fire on a planted change to the row's leading cell.
- [ ] `ExpansionTally`'s `@param edgesAdded` says what it counts, not only how it was summed.
- [ ] The guide's chapter carries the new sentence and the corrected progress-line example.
- [ ] ADR 66 carries a 2026-09-08 amendment naming the old and new labels, citing #284's closing
  comment, restating no figure from it, and containing no commit hash.
- [ ] No Java identifier renamed; no MCP payload field touched; `ToolSurfaceTest` untouched.
- [ ] `SEGUE_REQUIRE_BROWSER=true SEGUE_REQUIRE_GRAPHVIZ=true ./gradlew check --rerun-tasks` is green
  on the final commit.

## Report to the requester

- The issue's claim that `DeveloperGuideExpandPromotionsExamplesTest` already reads the label from a
  shared constant was wrong: no such constant existed and that test read no label. The property is
  created by Task 2, not relied on.
- `edges by source` is the same per-assertion quantity under a heading with the same defect, and is
  deliberately left alone. Worth a follow-up issue if the reviewer wants the section renamed.
