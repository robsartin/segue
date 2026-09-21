# Adding what a file names that the graph lacks — `expandPromotions --known … --add` — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** close issue #328. The fetch-and-record that `mcp.SegueService.addEntity` does today moves
into one rule in `expansion` — `EntityAddition` — that both callers read. The promotion expander's
`--known` run gains an `--add` switch: an id the file names that the graph holds no node for is
added first, by that rule, then expanded in the same pass, in the file's order. Without `--add`
nothing changes shape, and every block already pasted into an issue stays byte-identical.

**Architecture:** one extraction, two report fields, one new refusal reason, one flag, one
composition, three documents.

- **The extraction:** `expansion.EntityAddition` and `expansion.AdditionOutcome`. Five outcomes —
  added, no such entity, source unavailable, not a qid, local entity. `SegueService.addEntity`
  becomes a `switch` over the outcome that builds the same `ToolResult` it builds today.
- **The report:** `Preflight` gains `toAdd`, `ExpansionTally` gains `added`,
  `ExpansionOutcome.Reason` gains `NO_SUCH_ENTITY` with the label `no such entity`,
  `KnownNeverExpanded` gains `adding`. Each new row prints only when its count is non-zero, so a run
  without `--add` cannot print one.
- **The flag:** `--add` on `ExpandCli`, a boolean like `--dry-run`, refused with `--rated-since`,
  with `--second-hop`, and without `--known`.
- **The composition:** a third `ExpandRun` constructor taking the `EntityAddition`. `dryRun` counts
  `to add`; `run` adds a missing id and then expands it, in the population's own order.
- **The records:** a dated amendment to ADR 66, a developer-guide chapter after
  *The ring beside what your list cannot place: `--second-hop`*, one sentence in *Two-pass ingest*,
  and two table rows.

**Tech Stack:** Java 25, Gradle (plain `./gradlew`), JUnit 5, AssertJ, SQLite, ArchUnit.

**Spec:** `docs/superpowers/specs/2026-09-14-add-what-a-file-names-design.md`. Read it first. It
carries its own dated *Corrections* section for the two claims the code contradicts. **Two further
places where this plan departs from it are named below; where this plan and the spec differ, this
plan is the authority and the difference is stated out loud.**

### Premise corrections

1. **`SegueService.addEntity` has no local-entity check today.** The spec's *One add rule* says each
   outcome maps onto the tool result `addEntity` returns "word for word". Four do. `addEntity` today
   is: `Q\d+` or `not a QID: …`; `resolver.fetch`; `WikidataUnavailableException` →
   `wikidata unavailable: <message>`; empty → `no such entity: <qid>`; otherwise `ingest.record` and
   `added <qid> (<label>)`. There is no `LocalEntity.isLocal` call anywhere in it, so a minted id is
   fetched and comes back `no such entity`. **Refusing it before the fetch is new behaviour and
   needs a new sentence**, written in Task 1. No existing test calls `addEntity` with a `Q00…` id
   (`grep -rn 'addEntity' src/test` — five sites, none of them minted), so the four
   `SegueServiceTest` `addEntity` cases stay green as the control for the other four outcomes. The
   spec records this correction itself.
2. **There is no MCP-tools chapter in the developer guide.** `add_entity` appears in
   `docs/developer-guide.md` at exactly one line, as a node label inside *The layering*'s mermaid
   diagram. Task 5 puts the sentence in *Two-pass ingest* → *The full call, end to end*, beside the
   existing sentence that says `EntityExpansion` is the shared body, and names `EntityAddition` in
   the package table's `expansion` row. The spec records this correction itself.
3. **The `#` clause cannot carry a count of what the run added.** The spec says the clause "says,
   when `--add` was given, how many the run added". The clause is rendered from the `Population`
   value, and that value is composed in `ExpandCli.run` **before the first entity is visited** and
   is handed to both `ExpansionReport.dryRunLines` and `ExpansionReport.lines` — so on a dry run a
   count in it would be a lie, and on a real run it would restate a number the `added` row already
   carries, in the one report whose own design puts on the clause exactly what no row states
   (`sinceLine`'s and `knownLine`'s excluded counts are both counts of entities the run was **not**
   handed). **Task 2 instead gives `KnownNeverExpanded` a `boolean adding`**, and the clause says the
   switch was given and points at the row that holds the number. A pasted block is then still
   self-describing when nothing needed adding and both new rows are suppressed, which is the whole
   reason the clause exists.
4. **A comma-counting split of the mapping file is unsafe, so the runbook does not use one.** The
   spec's step 1 calls the non-touring rows "one line filter on that column". `SeedFiles.quote`
   quotes a field only when it holds a comma, a quote or a newline — true, and it is the `name`
   column that holds commas, so a row for a name with a comma in it is quoted and `awk -F,` reads
   its `$3` off by one. `status` itself is never quoted and always sits between two commas, so
   Task 5's chapter filters with `grep ',REJECTED,'` and says why.

---

## Global Constraints

These bind every task. An implementer who sees only one task brief still gets all of them.

- **Pure TDD / red first.** Every behaviour: write the test, **run it, observe a real assertion
  failure**, then the minimum code. **A compile error is never a red.** Where a record component or
  a type must exist before a test can compile, the step is split: add the component and mechanically
  update its call sites with the neutral value (`0`, `false`, `Optional.empty()`) so the whole suite
  is green and **the existing golden pins are the control that nothing printed changed**, then write
  the failing test for the new behaviour, observe the assertion failure, then the body. **Quote the
  actual failure text in the task report** — not "it failed".
- **Every guard gets a positive control, written out as its own steps: plant the defect, run the
  check, observe it fire, remove the plant.** The controls this plan requires by name are listed in
  each task; none may be skipped, and the report says what the planted run printed.
- Test names `should<Expected>When<Condition>` with `@DisplayName` on every test.
- **Mikado: the gate is green before every commit**, and each task ends green and is independently
  reviewable. Never a big-bang change guarded only by a final run.
- **Stage by explicit path, git stderr visible — never `git add -A`, never `2>/dev/null` on
  `git add`.** Read `git status` before every commit. Commit subjects name issue #328. Commits end,
  after a blank line, `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`.
- **Never cite a `.superpowers/` path from a committed file.**
- Gate, **blocking, never backgrounded**, run from `/Users/sartin/code/segue/wt-328`:
  `SEGUE_REQUIRE_BROWSER=true SEGUE_REQUIRE_GRAPHVIZ=true ./gradlew check --rerun-tasks`. Run
  `./gradlew spotlessApply` before each gate. **The final task counts the tests ONCE, from
  `build/test-results/test/*.xml`**, and reports that number.
- Per-task loops are `./gradlew test --tests '…'`, **run BLOCKING**. A `--tests` filter that matches
  nothing is a failure mode: check the reported test count is non-zero every time.
- **Only JDK 25 is installed and Gradle launches on it.** Plain `./gradlew`; never
  `/usr/libexec/java_home -v 21`, which silently returns 25.
- **Never run a writing dev task** — `expandPromotions`, `own`, `ownClaim`, `retractEntity`, `rate`,
  `evaluate`, `resolveNames`. **Never read, write, copy or create `~/.segue/segue.db`.** Every
  database in this plan is a `@TempDir` file and every known-list file is written by the test that
  reads it.
- **No test in this plan may reach the network, and this issue makes that sharper than before.** A
  non-dry `ExpandCli.main` builds a real `WikidataEntityResolver` over a real `WikidataClient`, and
  with `--add` it would also really *fetch entities the graph has never seen*. The rule, stated once
  and repeated at every test step: **`ExpandCliTest` stays dry-run only** — that is what its class
  Javadoc already promises — and **every non-dry test of the `--add` composition runs at
  `ExpandRun` level**, over a `@TempDir` SQLite log, a `TinkerGraphStore`, a real `IngestService`, a
  real `EntityAddition` built over a **stub `EntityResolver` written in the test**, and a real
  `EntityExpansion` over fixture `SourceAdapter`s. Each test step below says which of the two it is.
  **`ExpandCli` gets no test seam** — see Task 4 Step 1 for the decision and its reasons.
- **Invented ids only.** Stand-ins carry ADR 58's leading zero (`Q09…`); an id the owner minted
  carries two (`Q00…`); a merge's canonical side is ADR 62's eleven-digit reserved shape, which this
  issue has no case for and which therefore appears nowhere in it. **Never a real Wikidata id**,
  except through `StandInQidsDenoteNothingTest`'s `ALLOWED` map with the reason it is real. The ids
  this plan introduces are, in full:

  | id | what it stands for |
  | --- | --- |
  | `Q0901501` | the entity the stub answers for: added, and refreshed by the upsert on a second call |
  | `Q0901502` | an id Wikidata has no entity for |
  | `Q0901503` | an id whose fetch cannot be answered at all |
  | `Q0901504` | on the file, and in the graph |
  | `Q0901505` | on the file, no node, the resolver answers: added, then expanded |
  | `Q0901506` | on the file, no node, the resolver answers nothing |
  | `Q0901507` | on the file, no node, the resolver cannot be reached |
  | `Q0901508` | on the file, in the graph, and cited by a row as an expansion's seed |
  | `Q0901509` | the neighbour a fixture adapter's assertion names |
  | `Q0901510` | on the file, in the graph, second one, so a count can fall without falling to nothing |
  | `Q00901501` | minted: the rule refuses it before the resolver is asked |
  | `Q00901502` | minted, never recorded: the dry run counts it under `minted`, never under `to add` |

  `grep -rn 'Q09015' src docs` and `grep -rn 'Q009015' src docs` each find nothing today. **Run both
  in Task 1 Step 1 and stop if either finds anything.**
- **No commit hash, no `.superpowers/` path, no figure from the owner's graph, and no qid enters any
  file under `docs/adr`.** `AdrCitationsTest` reds on a backticked run of 7–40 hex characters, so
  **never put a bare digit run of seven or more inside backticks in ADR prose**. The census and run
  figures live on issues #317, #319 and #323 and are cited as "the census on #317 (2026-09-13)" and
  never restated — in the guide as well as in the ADR.
- **Markdown links whole and on one line**, `[text](target)`, no title, no raw HTML —
  `DocumentationLinksTest` refuses every other shape. Date-stamp anything time-bound.
- **`docs/` and `README.md` are already declared inputs of `tasks.test`** (`build.gradle.kts`, the
  `inputs.dir("docs")` and `inputs.file("README.md")` block) — **verify that before claiming a new
  declaration is needed; none is.** It does mean a document edit re-runs the suite, so run per-task
  loops **without** `--rerun-tasks`.
- **After `./gradlew spotlessApply`, re-read any Javadoc this plan writes** and confirm every
  `{@code …}` span is intact and **on one source line**: `grep -n '{@code$\|{@code *$' src/main
  src/test` must find nothing. google-java-format reflows Javadoc and will break inside an inline
  tag; to put a span on one line, shorten the clause *before* it.
- **No wall-clock assertion anywhere.** The machine is loaded. In particular, the source-unavailable
  fixtures in this plan use an HTTP **404**, which `WikidataClient.isTransient` refuses outright, and
  never a 5xx or a 429, which would retry four times with real sleeps.
- **No entity is ever named on the terminal.** The expansion block carries integers and, at most, a
  file's **basename** — `support.KnownListInput` is the one home of that rule. The progress line for
  an addition refusal carries the reason constant and no qid, exactly as the expansion refusal's
  does.
- **YAGNI.** No parameter, overload or helper ahead of a real need. `AdditionOutcome` carries the
  five outcomes the two callers use and nothing else.

---

## Task 1 — `expansion.EntityAddition`, with `SegueService.addEntity` reading it

**Files:** `src/main/java/com/robsartin/segue/expansion/AdditionOutcome.java` (new),
`src/main/java/com/robsartin/segue/expansion/EntityAddition.java` (new),
`src/test/java/com/robsartin/segue/expansion/EntityAdditionTest.java` (new),
`src/main/java/com/robsartin/segue/mcp/SegueService.java`.

**The control for the move is the existing offline MCP tests**: `SegueServiceTest`'s four
`addEntity` cases and `ToolSurfaceTest.addEntityIsAnnotatedAccordingly`, unchanged, green before and
after. The task's own reds are `EntityAdditionTest`'s, against `StubWikidataServer`.

- [ ] **Step 1 — prove the id block is free, and record the call sites.** Run all four, blocking,
      and paste the output into the task report. **Stop and report if any of the first two prints
      anything.**

  ```
  grep -rn 'Q09015' src docs
  grep -rn 'Q009015' src docs
  grep -rn 'addEntity' src/test
  grep -rn 'isLocal' src/main/java/com/robsartin/segue/mcp/SegueService.java
  ```

  Expected: nothing from the first two; five `addEntity` sites in `src/test`
  (`SegueServiceTest` ×4 plus its comment, `SharedAwardRouteTest`, `ToolSurfaceTest`,
  `PersonSeededRouteLiveTest`, `SharedAwardRouteLiveTest`), **none of them a `Q00…` id**; and
  **nothing at all** from the fourth — that absence is Premise correction 1.

- [ ] **Step 2 — the outcome type.** New file
      `src/main/java/com/robsartin/segue/expansion/AdditionOutcome.java`:

  ```java
  package com.robsartin.segue.expansion;

  import com.robsartin.segue.domain.NodeAssertion;
  import java.util.Objects;

  /**
   * What one addition did, or why it was refused before the node claim was recorded.
   *
   * <p><b>Facts, not sentences</b> — {@link ExpansionOutcome}'s rule, and for its reason. Two
   * callers read this: {@code SegueService} builds the {@code ToolResult} a language model reads
   * (ADR 27), and the promotion expander tallies. A shared sentence would be a wire string with two
   * audiences, one of them a model.
   *
   * <p><b>{@link Refused#detail()} is the source's own words and nothing else.</b> Only {@link
   * Reason#SOURCE_UNAVAILABLE} has any: the sentence the MCP tool has always returned for an outage
   * quotes the exception's message, so the fact has to travel or the tool's words would change. It
   * is empty for every other reason, and it is never an entity — see {@link #NO_DETAIL}.
   */
  public sealed interface AdditionOutcome {

    /** The entity this outcome is about. */
    String qid();

    /** The detail of a refusal that has nothing to add beyond its reason. */
    String NO_DETAIL = "";

    /** Why nothing was recorded. */
    enum Reason {
      /** The id is well formed and Wikidata has no entity at it. */
      NO_SUCH_ENTITY,
      /** The resolver could not be reached at all. */
      SOURCE_UNAVAILABLE,
      /** Not {@code Q} followed by digits, so no source could be asked for it. */
      NOT_A_QID,
      /** The owner minted it (ADR 58, ADR 59), so no source has it and none ever will. */
      LOCAL_ENTITY
    }

    /**
     * Recorded, through {@code IngestService.record}, which is an upsert.
     *
     * @param qid the entity that was added
     * @param node the claim that was recorded, so a caller can build its view from it without
     *     fetching the entity a second time
     */
    record Added(String qid, NodeAssertion node) implements AdditionOutcome {

      public Added {
        Objects.requireNonNull(qid, "qid");
        Objects.requireNonNull(node, "node");
      }
    }

    /** Nothing was recorded. Carries no sentence: see the interface's javadoc. */
    record Refused(String qid, Reason reason, String detail) implements AdditionOutcome {

      public Refused {
        Objects.requireNonNull(qid, "qid");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(detail, "detail");
      }

      /** A refusal whose reason is the whole of what there is to say. */
      public Refused(String qid, Reason reason) {
        this(qid, reason, NO_DETAIL);
      }
    }
  }
  ```

- [ ] **Step 3 — the stub, so the test compiles and its assertions can fail for the right reason.**
      New file `src/main/java/com/robsartin/segue/expansion/EntityAddition.java`, with the body
      **deliberately wrong-but-compiling** — every call refuses as `NOT_A_QID`:

  ```java
  package com.robsartin.segue.expansion;

  import com.robsartin.segue.ingest.IngestService;
  import com.robsartin.segue.port.EntityResolver;
  import java.util.Objects;

  /** One addition: ask the resolver for an entity's identity and record it. */
  public final class EntityAddition {

    private final EntityResolver resolver;
    private final IngestService ingest;

    public EntityAddition(EntityResolver resolver, IngestService ingest) {
      this.resolver = Objects.requireNonNull(resolver, "resolver");
      this.ingest = Objects.requireNonNull(ingest, "ingest");
    }

    /** Add one entity. */
    public AdditionOutcome add(String qid) {
      Objects.requireNonNull(qid, "qid");
      return new AdditionOutcome.Refused(qid, AdditionOutcome.Reason.NOT_A_QID);
    }
  }
  ```

  The two fields are read by nothing yet; javac warns about neither, and `spotless` does not mind.
  **Do not commit here** — this stub exists only so Step 4's reds are assertion failures.

- [ ] **Step 4 — RED: the rule's five outcomes, against `StubWikidataServer`.** New file
      `src/test/java/com/robsartin/segue/expansion/EntityAdditionTest.java`:

  ```java
  package com.robsartin.segue.expansion;

  import static org.assertj.core.api.Assertions.assertThat;

  import com.robsartin.segue.domain.NodeKind;
  import com.robsartin.segue.ingest.IngestService;
  import com.robsartin.segue.port.AssertionLog;
  import com.robsartin.segue.port.GraphStore;
  import com.robsartin.segue.port.IdentityMerge;
  import com.robsartin.segue.sqlite.SqliteAssertionLog;
  import com.robsartin.segue.tinker.TinkerGraphStore;
  import com.robsartin.segue.wikidata.StubWikidataServer;
  import com.robsartin.segue.wikidata.WikidataClient;
  import com.robsartin.segue.wikidata.WikidataEntityResolver;
  import java.nio.file.Path;
  import java.time.Clock;
  import java.time.Instant;
  import java.time.ZoneOffset;
  import org.junit.jupiter.api.AfterEach;
  import org.junit.jupiter.api.BeforeEach;
  import org.junit.jupiter.api.DisplayName;
  import org.junit.jupiter.api.Test;
  import org.junit.jupiter.api.io.TempDir;

  /**
   * The add rule, against an in-process stand-in for the Wikidata API — {@code SeedResolverTest}'s
   * method (#328). Every id here carries ADR 58's leading zero, or ADR 59's two; nothing here comes
   * from anybody's graph (ADR 33, issue #37), and no test in this class reaches a network.
   */
  class EntityAdditionTest {

    /** The stub answers for this one. */
    private static final String ANSWERED_FOR = "Q0901501";

    /** Wikidata has no entity at this one. */
    private static final String NO_SUCH_ENTITY = "Q0901502";

    /** The fetch for this one cannot be answered at all. */
    private static final String UNREACHABLE = "Q0901503";

    /** Minted by the owner, so no source has it and none ever will (ADR 59). */
    private static final String MINTED = "Q00901501";

    private static final Clock FIXED =
        Clock.fixed(Instant.parse("2026-09-15T09:00:00Z"), ZoneOffset.UTC);

    @TempDir private Path dir;

    private AssertionLog log;
    private GraphStore graph;
    private IngestService ingest;

    @BeforeEach
    void setUp() {
      log = new SqliteAssertionLog(dir.resolve("scratch.db"));
      graph = new TinkerGraphStore();
      ingest = new IngestService(log, graph, IdentityMerge.NONE);
    }

    @AfterEach
    void tearDown() {
      graph.close();
      log.close();
    }

    private EntityAddition against(StubWikidataServer stub) {
      return new EntityAddition(
          new WikidataEntityResolver(new WikidataClient(stub.baseUri()), FIXED), ingest);
    }

    /** One entity, with no {@code P31} at all, so the kind derives to {@code CONCEPT}. */
    private static String entity(String qid, String label) {
      return "{\"entities\":{\""
          + qid
          + "\":{\"id\":\""
          + qid
          + "\",\"labels\":{\"en\":{\"value\":\""
          + label
          + "\"}}}}}";
    }

    @Test
    @DisplayName("the rule records the node claim and reports what it recorded")
    void shouldRecordTheNodeClaimWhenTheSourceAnswers() {
      try (StubWikidataServer stub = new StubWikidataServer()) {
        stub.enqueueBody(entity(ANSWERED_FOR, "an act nobody signed"));

        AdditionOutcome outcome = against(stub).add(ANSWERED_FOR);

        assertThat(outcome)
            .isInstanceOfSatisfying(
                AdditionOutcome.Added.class,
                added -> {
                  assertThat(added.qid()).isEqualTo(ANSWERED_FOR);
                  assertThat(added.node().label()).isEqualTo("an act nobody signed");
                  assertThat(added.node().kind()).isEqualTo(NodeKind.CONCEPT);
                });
        assertThat(graph.node(ANSWERED_FOR)).isPresent();
      }
    }

    @Test
    @DisplayName("a second call refreshes the node rather than duplicating it, because record upserts")
    void shouldRefreshTheNodeWhenTheSameEntityIsAddedTwice() {
      try (StubWikidataServer stub = new StubWikidataServer()) {
        stub.enqueueBody(entity(ANSWERED_FOR, "an act nobody signed"));
        stub.enqueueBody(entity(ANSWERED_FOR, "an act nobody booked"));
        EntityAddition addition = against(stub);

        addition.add(ANSWERED_FOR);
        AdditionOutcome second = addition.add(ANSWERED_FOR);

        assertThat(second).isInstanceOf(AdditionOutcome.Added.class);
        assertThat(graph.node(ANSWERED_FOR).orElseThrow().label())
            .as("the later reading is the better one — GraphStore.upsertNode is last-writer-wins")
            .isEqualTo("an act nobody booked");
        assertThat(log.readAll()).as("two claims, because a changed belief is a new claim").hasSize(2);
      }
    }

    @Test
    @DisplayName("an id the source has no entity for is refused, and nothing is recorded")
    void shouldRefuseAndRecordNothingWhenTheSourceHasNoSuchEntity() {
      try (StubWikidataServer stub = new StubWikidataServer()) {
        stub.enqueueBody("{\"entities\":{\"" + NO_SUCH_ENTITY + "\":{\"missing\":\"\"}}}");

        AdditionOutcome outcome = against(stub).add(NO_SUCH_ENTITY);

        assertThat(outcome)
            .isEqualTo(
                new AdditionOutcome.Refused(NO_SUCH_ENTITY, AdditionOutcome.Reason.NO_SUCH_ENTITY));
        assertThat(log.readAll()).isEmpty();
      }
    }

    @Test
    @DisplayName("a source that cannot be reached is a refusal carrying its own words, not a throw")
    void shouldRefuseWithTheSourcesWordsWhenTheSourceCannotBeReached() {
      try (StubWikidataServer stub = new StubWikidataServer()) {
        // 404, not 5xx: WikidataClient.isTransient refuses it outright, so nothing here sleeps.
        stub.enqueueStatus(404);

        AdditionOutcome outcome = against(stub).add(UNREACHABLE);

        assertThat(outcome)
            .isInstanceOfSatisfying(
                AdditionOutcome.Refused.class,
                refused -> {
                  assertThat(refused.reason())
                      .isEqualTo(AdditionOutcome.Reason.SOURCE_UNAVAILABLE);
                  assertThat(refused.detail()).contains("404");
                });
        assertThat(log.readAll()).isEmpty();
      }
    }

    @Test
    @DisplayName("a value that is not a qid is refused before the source is asked")
    void shouldRefuseBeforeAskingWhenTheValueIsNotAQid() {
      try (StubWikidataServer stub = new StubWikidataServer()) {
        AdditionOutcome outcome = against(stub).add("an act nobody signed");

        assertThat(outcome)
            .isEqualTo(
                new AdditionOutcome.Refused(
                    "an act nobody signed", AdditionOutcome.Reason.NOT_A_QID));
        assertThat(stub.requestCount()).as("the source was never asked").isZero();
      }
    }

    @Test
    @DisplayName("a minted id is refused before the source is asked, because no source can have it")
    void shouldRefuseBeforeAskingWhenTheIdWasMintedByTheOwner() {
      try (StubWikidataServer stub = new StubWikidataServer()) {
        AdditionOutcome outcome = against(stub).add(MINTED);

        assertThat(outcome)
            .isEqualTo(new AdditionOutcome.Refused(MINTED, AdditionOutcome.Reason.LOCAL_ENTITY));
        assertThat(stub.requestCount()).as("the source was never asked").isZero();
        assertThat(log.readAll()).isEmpty();
      }
    }
  }
  ```

- [ ] **Step 5 — run it and observe the reds.** Blocking:

  ```
  ./gradlew test --tests 'com.robsartin.segue.expansion.EntityAdditionTest'
  ```

  Expect **five failures and one pass** — `shouldRefuseBeforeAskingWhenTheValueIsNotAQid` passes
  against the stub body by accident, which is why it is not the only one written. The five say, in
  substance:

  - `shouldRecordTheNodeClaimWhenTheSourceAnswers` —
    `Expecting actual: Refused[qid=Q0901501, reason=NOT_A_QID, detail=] to be an instance of: AdditionOutcome$Added`
  - `shouldRefreshTheNodeWhenTheSameEntityIsAddedTwice` — the same, on `second`
  - `shouldRefuseAndRecordNothingWhenTheSourceHasNoSuchEntity` —
    `expected: Refused[qid=Q0901502, reason=NO_SUCH_ENTITY, detail=] but was: Refused[qid=Q0901502, reason=NOT_A_QID, detail=]`
  - `shouldRefuseWithTheSourcesWordsWhenTheSourceCannotBeReached` —
    `Expecting actual's reason to be SOURCE_UNAVAILABLE but was NOT_A_QID`
  - `shouldRefuseBeforeAskingWhenTheIdWasMintedByTheOwner` —
    `expected: …reason=LOCAL_ENTITY… but was: …reason=NOT_A_QID…`

  **Paste the real text into the task report.** If any of the five passes, stop and report: the stub
  body is not the one this step wrote.

- [ ] **Step 6 — GREEN: the rule's body.** Replace `EntityAddition.add` and give the class the
      Javadoc that argues for it:

  ```java
  package com.robsartin.segue.expansion;

  import com.robsartin.segue.domain.LocalEntity;
  import com.robsartin.segue.domain.NodeAssertion;
  import com.robsartin.segue.ingest.IngestService;
  import com.robsartin.segue.port.EntityResolver;
  import com.robsartin.segue.wikidata.WikidataUnavailableException;
  import java.util.Objects;
  import java.util.Optional;
  import java.util.regex.Pattern;
  import org.slf4j.Logger;
  import org.slf4j.LoggerFactory;

  /**
   * One addition: check the id's shape, ask the resolver for the entity, and record the node claim
   * through {@link IngestService}, which is an upsert (#328).
   *
   * <p><b>Two callers, one body</b> — {@link EntityExpansion}'s argument, one step earlier in the
   * same story. {@code SegueService.addEntity} turns the outcome into the {@code ToolResult} a
   * language model reads (ADR 27); the promotion expander's {@code --add} run adds what a known-list
   * file names that the graph holds no node for, and then expands it. Before this class the body
   * lived in the tool layer, so the expander had either to reach the tool layer or to state the
   * fetch-and-record a second time.
   *
   * <p><b>It writes, and that is why it is fenced with the expansion.</b> {@code
   * ArchitectureTest.onlyTheClientAndTheExpanderExpandAnEntity} already bars every package but
   * {@code mcp}, {@code expand}, {@code app} and this one from depending on {@code expansion}, so
   * this class joins that fence by living here and no rule moves.
   *
   * <p><b>A minted id is refused before the resolver is asked</b> (ADR 58, ADR 59). Its id is one
   * Wikibase's grammar can never allocate, so no source has it and none ever will; asking anyway
   * would spend a round trip to be told what the shape already says, and the answer would come back
   * as "no such entity", which is a different fact. This is the one outcome the MCP tool did not
   * report before #328 — it checked {@code Q\d+} and fetched — so its sentence is new, and it is
   * named in that method rather than here, because a sentence has one audience.
   *
   * <p>What did NOT move here: {@code ToolResult}, the {@code ok} shaping and the refusal sentences.
   * Those are the tool layer's, and the outcome carries facts precisely so that each caller can word
   * them for its own reader.
   */
  public final class EntityAddition {

    private static final Logger log = LoggerFactory.getLogger(EntityAddition.class);

    /** ADR 26/ADR 22: identity is a Wikidata QID, always of this shape. */
    private static final Pattern QID = Pattern.compile("Q\\d+");

    private final EntityResolver resolver;
    private final IngestService ingest;

    public EntityAddition(EntityResolver resolver, IngestService ingest) {
      this.resolver = Objects.requireNonNull(resolver, "resolver");
      this.ingest = Objects.requireNonNull(ingest, "ingest");
    }

    /**
     * Add one entity, or say why nothing was recorded.
     *
     * @param qid the entity to add
     * @return {@link AdditionOutcome.Added} carrying the recorded claim, or {@link
     *     AdditionOutcome.Refused}
     */
    public AdditionOutcome add(String qid) {
      Objects.requireNonNull(qid, "qid");
      if (!QID.matcher(qid).matches()) {
        return new AdditionOutcome.Refused(qid, AdditionOutcome.Reason.NOT_A_QID);
      }
      if (LocalEntity.isLocal(qid)) {
        return new AdditionOutcome.Refused(qid, AdditionOutcome.Reason.LOCAL_ENTITY);
      }
      Optional<NodeAssertion> fetched;
      try {
        fetched = resolver.fetch(qid);
      } catch (WikidataUnavailableException e) {
        log.warn("add({}) source unavailable: {}", qid, e.getMessage());
        return new AdditionOutcome.Refused(
            qid, AdditionOutcome.Reason.SOURCE_UNAVAILABLE, e.getMessage());
      }
      if (fetched.isEmpty()) {
        return new AdditionOutcome.Refused(qid, AdditionOutcome.Reason.NO_SUCH_ENTITY);
      }
      NodeAssertion assertion = fetched.get();
      ingest.record(assertion);
      return new AdditionOutcome.Added(qid, assertion);
    }
  }
  ```

  Re-run the filter from Step 5 and observe six passes.

- [ ] **Step 7 — the control: the MCP tests before the switch.** Blocking, and **paste the counts**:

  ```
  ./gradlew test --tests 'com.robsartin.segue.mcp.SegueServiceTest' --tests 'com.robsartin.segue.mcp.EntityToolsTest' --tests 'com.robsartin.segue.mcp.ToolSurfaceTest'
  ```

  All green. These are the control for Step 8 and **not one of them may be edited in this task.**

- [ ] **Step 8 — GREEN: `SegueService.addEntity` reads the rule.** In `SegueService`, build the
      addition beside the expansion in the constructor:

  ```java
      // Built here rather than injected: every collaborator it needs is already a field, and a
      // seventh constructor parameter would move thirty-odd call sites for nothing. #284.
      this.expansion = new EntityExpansion(this.resolver, this.graph, this.ingest, this.adapters);
      // The same argument one step earlier in the same story (#328).
      this.addition = new EntityAddition(this.resolver, this.ingest);
  ```

  with `private final EntityAddition addition;` beside `expansion`, and the import
  `com.robsartin.segue.expansion.AdditionOutcome` and
  `com.robsartin.segue.expansion.EntityAddition`. Replace the method body:

  ```java
    /**
     * Fetch one entity's identity from the resolver and record it. Recording is an upsert, so a
     * second call with the same qid is idempotent — it refreshes the node rather than duplicating it.
     *
     * <p><b>The fetch-and-record itself is {@link EntityAddition}'s</b> (#328): the promotion
     * expander's {@code --add} run needs it and does not need a {@code ToolResult}. What stays here
     * is the shaping — the four sentences this method has always returned, byte for byte, and the
     * one that is new.
     *
     * <p><b>The new one is the minted id.</b> Before #328 this method checked {@code Q\d+} and then
     * fetched, so a qid the owner minted went to Wikidata and came back {@code no such entity} — a
     * true-sounding sentence about the wrong thing, and a round trip spent to learn what ADR 58's
     * grammar already says. The rule refuses it before the resolver is asked, and this is the
     * sentence for it.
     */
    public ToolResult<NodeView> addEntity(String qid) {
      Objects.requireNonNull(qid, "qid");
      return switch (addition.add(qid)) {
        case AdditionOutcome.Added added ->
            ToolResult.ok(
                "added " + added.qid() + " (" + added.node().label() + ")",
                ViewMapper.toNodeView(added.node().toNode()));
        case AdditionOutcome.Refused refused -> error(refusalSentence(refused));
      };
    }

    /** The four sentences this method has always returned, byte for byte, and the fifth (#328). */
    private static String refusalSentence(AdditionOutcome.Refused refused) {
      return switch (refused.reason()) {
        case NOT_A_QID -> "not a QID: " + refused.qid();
        case LOCAL_ENTITY ->
            "local entity: "
                + refused.qid()
                + " — no source to add it from, because the owner minted it";
        case SOURCE_UNAVAILABLE -> "wikidata unavailable: " + refused.detail();
        case NO_SUCH_ENTITY -> "no such entity: " + refused.qid();
      };
    }
  ```

  Then remove whatever is now unused: the `log.warn` in `addEntity` moved into `EntityAddition`, so
  check whether `SegueService`'s `log` and its `resolver`/`ingest` fields are still read elsewhere
  (`search` reads `resolver` and logs; `noteAffinity` and others read `ingest`) — **do not delete a
  field the compiler still needs**, and run `./gradlew compileJava` before moving on. The `QID`
  pattern constant stays if another method reads it; `grep -n 'QID\.' src/main/java/com/robsartin/segue/mcp/SegueService.java`
  decides, and the report says which way it went.

- [ ] **Step 9 — run the control again and the whole `mcp` and `expansion` packages.** Blocking:

  ```
  ./gradlew test --tests 'com.robsartin.segue.mcp.*' --tests 'com.robsartin.segue.expansion.*'
  ```

  Green, and **the four `addEntity` cases are unedited**. If any of them reds, the move changed the
  tool's words: stop and report which sentence moved.

- [ ] **Step 10 — the positive control for the minted refusal: prove the check can fire.** Plant the
      defect by deleting the `LocalEntity.isLocal` branch from `EntityAddition.add`, run

  ```
  ./gradlew test --tests 'com.robsartin.segue.expansion.EntityAdditionTest'
  ```

  and observe `shouldRefuseBeforeAskingWhenTheIdWasMintedByTheOwner` fail — it will fail on
  `stub.requestCount()` being `1`, or on the reason being `NO_SUCH_ENTITY`, depending on what the
  empty stub returns. **Paste the failure text**, then restore the branch and re-run green. A test
  whose planted control fails on the wrong assertion is still a control; one that stays green is not,
  and if it does, stop and report.

- [ ] **Step 11 — the architecture control: prove the fence covers the new class.** Plant an import
      of `com.robsartin.segue.expansion.EntityAddition` into
      `src/main/java/com/robsartin/segue/census/CensusCli.java` (a field declaration is enough to
      make ArchUnit see the dependency), run

  ```
  ./gradlew test --tests 'com.robsartin.segue.arch.ArchitectureTest'
  ```

  and observe `onlyTheClientAndTheExpanderExpandAnEntity` red, naming `CensusCli` and
  `EntityAddition`. **Paste the text.** Remove the plant, re-run green. This is the evidence for the
  spec's claim that no fence has to move.

- [ ] **Step 12 — `spotlessApply`, the `{@code}` check, the gate, and commit.**

  ```
  ./gradlew spotlessApply
  grep -n '{@code$' src/main/java/com/robsartin/segue/expansion/EntityAddition.java src/main/java/com/robsartin/segue/expansion/AdditionOutcome.java src/main/java/com/robsartin/segue/mcp/SegueService.java
  SEGUE_REQUIRE_BROWSER=true SEGUE_REQUIRE_GRAPHVIZ=true ./gradlew check --rerun-tasks
  git status --short
  git add src/main/java/com/robsartin/segue/expansion/AdditionOutcome.java src/main/java/com/robsartin/segue/expansion/EntityAddition.java src/test/java/com/robsartin/segue/expansion/EntityAdditionTest.java src/main/java/com/robsartin/segue/mcp/SegueService.java
  git status --short
  git commit
  ```

  The grep must print nothing. Subject: `#328 extract the add rule into expansion.EntityAddition`.
  Never `git add -A`; never hide `git add`'s stderr.

---

## Task 2 — The two new counts, the new reason, and the clause that says the switch was given

**Files:** `src/main/java/com/robsartin/segue/expand/Preflight.java`,
`src/main/java/com/robsartin/segue/expand/ExpansionTally.java`,
`src/main/java/com/robsartin/segue/expand/KnownNeverExpanded.java`,
`src/main/java/com/robsartin/segue/expansion/ExpansionOutcome.java`,
`src/main/java/com/robsartin/segue/expand/ExpansionReport.java`,
`src/main/java/com/robsartin/segue/expand/ExpandRun.java` (call sites only),
`src/main/java/com/robsartin/segue/expand/ExpandCli.java` (call site only),
`src/test/java/com/robsartin/segue/expand/ExpansionReportTest.java`,
`src/test/java/com/robsartin/segue/expand/ExpandRunTest.java` (call sites only).

**This task renders; it composes nothing.** No flag is parsed yet and no addition happens yet — the
new fields are written by nobody but a test until Task 4. **`ExpansionReportTest`'s `GOLDEN_BLOCK`,
`SINCE_LINE` and `KNOWN_LINE` are the pin**, and every one of them must stay byte-identical.

- [ ] **Step 1 — the structural move, with the pins as its control.** Add the components and update
      every call site with the neutral value, in one edit, so the tree compiles and the whole suite
      is green. Nothing printed changes.

  - `Preflight` gains a fourth component:

    ```java
    /**
     * @param toAdd those the run would add before expanding — not {@link
     *     com.robsartin.segue.domain.LocalEntity#isLocal} and with no node — which is zero on every
     *     run that was not given {@code --add}, because only such a run adds anything. Kept disjoint
     *     from {@link #minted} by the same first-check rule {@link ExpandRun#dryRun} applies, so
     *     {@code considered == inTheGraph + minted + toAdd} closes exactly on an {@code --add} run
     */
    public record Preflight(int considered, int inTheGraph, int minted, int toAdd) {}
    ```

    Call sites: `ExpandRun.dryRun` (pass `0`), `ExpansionReportTest` ×4, `ExpandRunTest` ×1.

  - `ExpansionTally` gains `int added` **immediately after `considered`**, with the javadoc line:

    ```java
     * @param added entities this run recorded a node claim for before expanding them, which is zero
     *     on every run that was not given {@code --add}. NOT a fourth member of the {@code
     *     expanded}/{@code refused}/{@code failed} partition: an entity that was added and then
     *     expanded is counted here and under {@code expanded}, so {@code considered == expanded +
     *     refused + failed} is untouched
    ```

    Call sites: `ExpandRun.run` (pass `0`), `ExpansionReportTest` ×3.

  - `KnownNeverExpanded` gains `boolean adding`:

    ```java
    /**
     * @param adding whether {@code --add} was given, so the clause can say that an id the file names
     *     and the graph lacks was added before it was expanded. A boolean and not a count: this value
     *     is composed before the first entity is visited and is rendered into the dry-run block as
     *     well as the real one, so a count in it would be a lie on the dry run — and the count is
     *     already a row ({@code added}, or {@code to add} on a dry run). The clause carries what no
     *     row states, which is the rule {@code excluded} above follows too
     */
    public record KnownNeverExpanded(String file, int excluded, boolean adding) implements Population {
    ```

    Call sites: `ExpandCli.run` (pass `false`), `ExpansionReportTest` ×2.

  - `ExpansionOutcome.Reason` gains the constant, **last**, so no existing `switch` order moves:

    ```java
      /** The caller asked for a bound of zero or less. */
      BOUND_NOT_POSITIVE,
      /** The id is well formed and no source has an entity at it, so nothing could be added (#328). */
      NO_SUCH_ENTITY
    ```

    and `ExpansionReport.label` gains `case NO_SUCH_ENTITY -> "no such entity";`. `SegueService
    .refusalSentence` switches over the same enum exhaustively — **it will not compile until it
    handles the new constant**, so add there:

    ```java
        case NO_SUCH_ENTITY ->
            "no such entity: " + refused.qid() + " — nothing could be added for it";
    ```

    with a line of Javadoc saying that `EntityExpansion.expand` never returns this reason, and it is
    handled because the enum is one enum and an unhandled constant is a compile error, not a
    judgement.

  Then, blocking:

  ```
  ./gradlew test --tests 'com.robsartin.segue.expand.*' --tests 'com.robsartin.segue.expansion.*' --tests 'com.robsartin.segue.mcp.*'
  ```

  **Every test green, and `ExpansionReportTest`'s three pins untouched.** That green is this step's
  control: four new record components and a new enum constant changed no printed byte. If a pin reds,
  stop and report which line moved.

- [ ] **Step 2 — RED: the dry-run block prints `to add` when something would be added.** Add to
      `ExpansionReportTest`:

  ```java
    @Test
    @DisplayName("the dry run block adds a to-add row when the run would add something")
    void shouldPrintTheToAddRowWhenTheDryRunWouldAddSomething() {
      List<String> lines = ExpansionReport.dryRunLines(new Preflight(4, 2, 1, 1));

      assertThat(lines)
          .containsExactly(
              "# segue promotion expansion — dry run: appends nothing. Aggregates only"
                  + " (ADR 51, ADR 63).",
              "",
              "promotions",
              "  considered    4",
              "  in the graph  2",
              "  minted        1",
              "  to add        1");
    }

    @Test
    @DisplayName("the dry run block is byte-identical to today's when nothing would be added")
    void shouldPrintNoToAddRowWhenTheRunWouldAddNothing() {
      assertThat(ExpansionReport.dryRunLines(new Preflight(4, 2, 1, 0)))
          .as("a run without --add can only ever pass zero here, so every pasted block survives")
          .containsExactlyElementsOf(ExpansionReport.dryRunLines(new Preflight(4, 2, 1, 0)))
          .hasSize(6);
    }
  ```

  Run:

  ```
  ./gradlew test --tests 'com.robsartin.segue.expand.ExpansionReportTest'
  ```

  Expect the first to fail with
  `Actual and expected have the same elements but not in the same order` — no: expect
  `actual … did not contain "  to add        1"`, AssertJ reporting a six-element actual against a
  seven-element expected. **Paste the real text.** The second passes already and is the pin.

- [ ] **Step 3 — GREEN: the row, printed only when non-zero.** In `ExpansionReport.dryRunBody`:

  ```java
    /**
     * <p><b>The {@code to add} row prints only when it is not zero.</b> A run with no {@code --add}
     * can never produce a non-zero value for it — {@link ExpandRun#dryRun} counts it only when it
     * holds an addition — so suppressing the zero keeps every block already pasted into an issue
     * byte-identical, which is the rule the two {@code #} clauses follow for the same reason. An
     * {@code --add} run that found nothing to add prints no row either, and says so truthfully: it
     * added nothing.
     */
    private static List<Entry> dryRunBody(Preflight preflight) {
      List<Entry> body = new ArrayList<>();
      body.add(new Section("promotions"));
      body.add(new Row("  considered", preflight.considered()));
      body.add(new Row("  in the graph", preflight.inTheGraph()));
      body.add(new Row("  minted", preflight.minted()));
      if (preflight.toAdd() > 0) {
        body.add(new Row("  to add", preflight.toAdd()));
      }
      return List.copyOf(body);
    }
  ```

  Re-run: both green.

- [ ] **Step 4 — RED: the real block prints `added`, and the new reason's label.** Add to
      `ExpansionReportTest`:

  ```java
    /** The golden tally with three entities added before they were expanded. */
    private static ExpansionTally tallyWithAdditions() {
      Map<String, Integer> edgesBySource = new LinkedHashMap<>();
      edgesBySource.put("wikidata", 60);
      edgesBySource.put("musicbrainz", 17);
      Map<ExpansionOutcome.Reason, Integer> refusalsByReason = new LinkedHashMap<>();
      refusalsByReason.put(ExpansionOutcome.Reason.UNKNOWN_ENTITY, 1);
      refusalsByReason.put(ExpansionOutcome.Reason.LOCAL_ENTITY, 1);
      refusalsByReason.put(ExpansionOutcome.Reason.NO_SUCH_ENTITY, 4);
      return new ExpansionTally(
          12, 3, 9, 2, 1, 34, 77, 5, 3, 1,
          edgesBySource, Map.of("musicbrainz", 1), Map.of("wikidata", 2), refusalsByReason);
    }

    @Test
    @DisplayName("the block counts what the run added, under considered and above expanded")
    void shouldCountWhatWasAddedWhenTheRunAddedSomething() {
      List<String> lines = ExpansionReport.lines(tallyWithAdditions());

      assertThat(lines.subList(lines.indexOf("promotions"), lines.indexOf("promotions") + 7))
          .containsExactly(
              "promotions",
              "  considered                12",
              "  added                      3",
              "  expanded                   9",
              "  added nothing              2",
              "  refused                    6",
              "  failed                     1");
    }

    @Test
    @DisplayName("an id no source has an entity for is labelled no such entity")
    void shouldLabelTheReasonWhenNothingCouldBeAddedForAnId() {
      assertThat(ExpansionReport.lines(tallyWithAdditions()))
          .contains("  no such entity             4");
    }

    @Test
    @DisplayName("the block is byte-identical to today's when the run added nothing")
    void shouldPrintNoAddedRowWhenTheRunAddedNothing() {
      assertThat(ExpansionReport.lines(goldenTally())).containsExactlyElementsOf(GOLDEN_BLOCK);
    }
  ```

  **`goldenTally()` keeps `added` at `0`** (Step 1 passed `0` there). Run the filter and observe the
  first two red: the first with `expected size: 7 but was: 6` and no `  added` line, the second with
  `Expecting … to contain: "  no such entity             4"`. The third is the pin and passes.

- [ ] **Step 5 — GREEN: the row.** In `ExpansionReport.body`, immediately after `considered`:

  ```java
      body.add(new Row("  considered", tally.considered()));
      // #328. Printed only when it is not zero, for dryRunBody's reason: a run with no --add can
      // never make it non-zero, so no block already on record moves. Placed here and not beside
      // `added nothing` because the order of these rows is the order of the work — an entity is
      // added and then expanded — and because `refusedTotal` below already counts what could not be.
      if (tally.added() > 0) {
        body.add(new Row("  added", tally.added()));
      }
      body.add(new Row("  expanded", tally.expanded()));
  ```

  `label(NO_SUCH_ENTITY)` was added in Step 1. Re-run: all three green.

- [ ] **Step 6 — RED: `knownLine` says the switch was given.** Add to `ExpansionReportTest`:

  ```java
    /** The clause a --known --add run prints, character for character. A literal — see GOLDEN_BLOCK. */
    private static final String KNOWN_ADDING_LINE =
        "# only known-list entities from known.csv that no expansion has covered: 7 excluded (some"
            + " row in the log cites them as an expansion's seed) — the file's ids are read through"
            + " the merge fold, so a merge's two sides count once. --add was given, so an id the"
            + " file names that the graph holds no node for was added before it was expanded; how"
            + " many is the added row below, or to add on a dry run.";

    @Test
    @DisplayName("the clause says the switch was given when a known-list run was told to add")
    void shouldSayTheSwitchWasGivenWhenTheKnownListRunWasToldToAdd() {
      List<String> lines =
          ExpansionReport.lines(
              tallyWithAdditions(), Optional.of(new KnownNeverExpanded(KNOWN_FILE, 7, true)));

      assertThat(lines.get(1)).isEqualTo(KNOWN_ADDING_LINE);
    }

    @Test
    @DisplayName("the clause is byte-identical to today's when the switch was not given")
    void shouldPrintTodaysClauseWhenTheKnownListRunWasNotToldToAdd() {
      assertThat(
              ExpansionReport.lines(
                  goldenTally(), Optional.of(new KnownNeverExpanded(KNOWN_FILE, 7, false))))
          .containsExactlyElementsOf(withKnownLine(GOLDEN_BLOCK));
    }
  ```

  Run and observe the first red — the actual line is today's clause, ending
  `so a merge's two sides count once.` The second is the pin and passes. **Paste the real text.**

- [ ] **Step 7 — GREEN: the clause.** In `ExpansionReport.knownLine`, append to the returned string:

  ```java
    private static String knownLine(KnownNeverExpanded known) {
      String line =
          "# only known-list entities from "
              + known.file()
              + " that no expansion has covered: "
              + known.excluded()
              + " excluded (some row in the log cites them as an expansion's seed) — the file's ids"
              + " are read through the merge fold, so a merge's two sides count once.";
      if (!known.adding()) {
        return line;
      }
      // #328. A sentence and not a number: this value is composed before the run and is rendered
      // into the dry-run block too, and the number is already a row. What a pasted block cannot
      // otherwise tell is whether the switch was given at all, because both new rows are suppressed
      // when they are zero — so that is what this says.
      return line
          + " --add was given, so an id the file names that the graph holds no node for was added"
          + " before it was expanded; how many is the added row below, or to add on a dry run.";
    }
  ```

  Add a Javadoc paragraph on the method repeating the two sentences above. Re-run: green.

- [ ] **Step 8 — the paste guard, unchanged and re-run.** Blocking:

  ```
  ./gradlew test --tests 'com.robsartin.segue.expand.ExpansionIsSafeToPasteTest'
  ```

  Green. The new clause carries no operator text beyond the basename the guard already covers, and
  **no assertion in that class may be edited in this task.**

- [ ] **Step 9 — the positive control for the suppression rule: prove it can fire.** Plant the defect
      by changing `if (tally.added() > 0)` to `if (true)`, run

  ```
  ./gradlew test --tests 'com.robsartin.segue.expand.ExpansionReportTest'
  ```

  and observe `shouldPrintNoAddedRowWhenTheRunAddedNothing` red, with
  `unexpected elements: ["  added                       0"]` or the equivalent index mismatch.
  **Paste the text.** Restore the guard, re-run green. Repeat the same plant for `preflight.toAdd()`
  and observe `shouldPrintNoToAddRowWhenTheRunWouldAddNothing` red. Both plants, both failures, both
  removals go in the report.

- [ ] **Step 10 — `spotlessApply`, the `{@code}` check, the gate, and commit.**

  ```
  ./gradlew spotlessApply
  grep -rn '{@code$' src/main/java/com/robsartin/segue/expand src/main/java/com/robsartin/segue/expansion
  SEGUE_REQUIRE_BROWSER=true SEGUE_REQUIRE_GRAPHVIZ=true ./gradlew check --rerun-tasks
  git status --short
  git add src/main/java/com/robsartin/segue/expand/Preflight.java src/main/java/com/robsartin/segue/expand/ExpansionTally.java src/main/java/com/robsartin/segue/expand/KnownNeverExpanded.java src/main/java/com/robsartin/segue/expand/ExpansionReport.java src/main/java/com/robsartin/segue/expand/ExpandRun.java src/main/java/com/robsartin/segue/expand/ExpandCli.java src/main/java/com/robsartin/segue/expansion/ExpansionOutcome.java src/main/java/com/robsartin/segue/mcp/SegueService.java src/test/java/com/robsartin/segue/expand/ExpansionReportTest.java src/test/java/com/robsartin/segue/expand/ExpandRunTest.java
  git status --short
  git commit
  ```

  Subject: `#328 count what a run adds, and say so in the block`.

---

## Task 3 — `--add` on the command line, and the three refusals

**Files:** `src/main/java/com/robsartin/segue/expand/ExpandCli.java`,
`src/test/java/com/robsartin/segue/expand/ExpandCliTest.java`.

**Everything in this task is `ExpandCli.parse`**, which opens no store and reaches no network.
Nothing here runs the tool.

- [ ] **Step 1 — RED: the flag and its three refusals.** Add to `ExpandCliTest`, beside the
      `--second-hop` cases:

  ```java
    @Test
    @DisplayName("--add is off unless it is given")
    void shouldNotAddWhenTheSwitchIsAbsent() {
      assertThat(ExpandCli.parse(new String[] {"--db", "db.sqlite"}, null, home.toString()).add())
          .isFalse();
    }

    @Test
    @DisplayName("--add is on when it is given beside a known-list file")
    void shouldAddWhenTheSwitchIsGivenWithAKnownList() {
      assertThat(
              ExpandCli.parse(
                      new String[] {"--db", "db.sqlite", "--known", "known.csv", "--add"},
                      null,
                      home.toString())
                  .add())
          .isTrue();
    }

    @Test
    @DisplayName("--add without --known is refused, because only a file can name what the graph lacks")
    void shouldRefuseWhenAddIsGivenWithoutAKnownList() {
      assertThatThrownBy(
              () -> ExpandCli.parse(new String[] {"--db", "db.sqlite", "--add"}, null, home.toString()))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("--add needs --known")
          .hasMessageContaining("only a file can name an entity the graph lacks");
    }

    @Test
    @DisplayName("--add and --rated-since are refused together, because that population is in the graph")
    void shouldRefuseWhenAddIsGivenWithRatedSince() {
      assertThatThrownBy(
              () ->
                  ExpandCli.parse(
                      new String[] {"--db", "db.sqlite", "--rated-since", THE_INSTANT, "--add"},
                      null,
                      home.toString()))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("--add and --rated-since name different populations");
    }

    @Test
    @DisplayName("--add and --second-hop are refused together, for the same reason")
    void shouldRefuseWhenAddIsGivenWithSecondHop() {
      assertThatThrownBy(
              () ->
                  ExpandCli.parse(
                      new String[] {"--db", "db.sqlite", "--second-hop", "known.csv", "--add"},
                      null,
                      home.toString()))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("--add and --second-hop name different populations");
    }

    @Test
    @DisplayName("the usage message names the switch, so a refusal shows how to spell it")
    void shouldNameTheSwitchInTheUsageWhenAnythingIsRefused() {
      assertThatThrownBy(() -> ExpandCli.parse(new String[] {}, null, home.toString()))
          .hasMessageContaining("[--add]");
    }
  ```

  Run, blocking:

  ```
  ./gradlew test --tests 'com.robsartin.segue.expand.ExpandCliTest'
  ```

  The first two will not compile until `Options.add()` exists, so **split as the Global Constraints
  require**: first add `boolean add` to the `Options` record (after `dryRun`) with a Javadoc line,
  return `false` from `parse` unconditionally, run the whole suite green — that is the structural
  step and the existing `ExpandCliTest` is its control — then run the filter and observe:

  - `shouldAddWhenTheSwitchIsGivenWithAKnownList` — `Expecting value to be true but was false`, and
    before that a refusal, because `--add` is still an unknown option: the actual failure is
    `unknown option --add. usage: …`. Either text is a real red; **paste what actually printed.**
  - the three refusal tests — `Expecting message to contain … but was "unknown option --add. usage: …"`
  - `shouldNameTheSwitchInTheUsageWhenAnythingIsRefused` —
    `Expecting message to contain "[--add]"`

- [ ] **Step 2 — GREEN: parse it.** In `ExpandCli`:

  ```java
    private static final String USAGE =
        "usage: --db <segue.db> [--max-new-edges <n>] [--dry-run] [--rated-since <ISO-8601 instant,"
            + " e.g. 2026-09-06T15:00:00Z>] [--known <file of QIDs>] [--add] [--second-hop <file of"
            + " QIDs>]";
  ```

  In the parse loop, beside `--dry-run`:

  ```java
      boolean dryRun = false;
      boolean add = false;

      for (int i = 0; i < args.length; i++) {
        String flag = args[i];
        if ("--dry-run".equals(flag)) {
          dryRun = true;
          continue;
        }
        if ("--add".equals(flag)) {
          add = true;
          continue;
        }
  ```

  After the three existing pairwise refusals, and **in this order**, so that a run naming both
  `--known` and `--rated-since` is still refused in the sentence already on record:

  ```java
      if (add && ratedSince != null) {
        // Both of the graph-side populations are, by construction, entities the graph holds a node
        // for — one is KnownList.promoted and the other is a ring read out of the fold's own nodes
        // map — so nothing in either can be missing, and --add would have nothing to do. Only a
        // file can name an entity the graph lacks (#328).
        throw usage("--add and --rated-since name different populations — give one or neither");
      }
      if (add && secondHop != null) {
        throw usage("--add and --second-hop name different populations — give one or neither");
      }
      if (add && known == null) {
        throw usage("--add needs --known — only a file can name an entity the graph lacks");
      }
  ```

  and add `add` to the `Options` construction. Re-run the filter: all six green, and every existing
  `ExpandCliTest` case still green.

- [ ] **Step 3 — the positive control for the ordering: prove the first refusal still wins.** Add one
      more test, and watch it fail before the order is right by temporarily moving the
      `add && known == null` check **above** the three existing pairwise ones:

  ```java
    @Test
    @DisplayName("a run naming both populations is refused in the sentence already on record")
    void shouldRefuseInTheOlderSentenceWhenBothPopulationsAreNamedBesideAdd() {
      assertThatThrownBy(
              () ->
                  ExpandCli.parse(
                      new String[] {
                        "--db", "db.sqlite", "--known", "known.csv", "--rated-since", THE_INSTANT,
                        "--add"
                      },
                      null,
                      home.toString()))
          .hasMessageContaining("--known and --rated-since name different populations");
    }
  ```

  With the check moved up, this reds with
  `Expecting message to contain "--known and --rated-since name different populations" but was "--add and --rated-since name different populations. usage: …"`.
  **Paste it**, restore the order, re-run green. A script written against the older sentence still
  reads, which is the property this control holds.

- [ ] **Step 4 — `spotlessApply`, the gate, and commit.**

  ```
  ./gradlew spotlessApply
  SEGUE_REQUIRE_BROWSER=true SEGUE_REQUIRE_GRAPHVIZ=true ./gradlew check --rerun-tasks
  git status --short
  git add src/main/java/com/robsartin/segue/expand/ExpandCli.java src/test/java/com/robsartin/segue/expand/ExpandCliTest.java
  git status --short
  git commit
  ```

  Subject: `#328 parse --add, and refuse it where no file names the population`.

---

## Task 4 — Add what the file names that the graph lacks, then expand it

**Files:** `src/main/java/com/robsartin/segue/expand/ExpandRun.java`,
`src/main/java/com/robsartin/segue/expand/ExpandCli.java`,
`src/test/java/com/robsartin/segue/expand/ExpandRunTest.java`,
`src/test/java/com/robsartin/segue/expand/ExpandCliTest.java`.

- [ ] **Step 1 — the stubbing decision, recorded before any code is written.** Read
      `ExpandCli.run`'s wiring block (`Clock clock = Clock.systemUTC();` through
      `ExpandRun run = new ExpandRun(expansion, graph);`) and `WikidataClient`'s two constructors.
      **The decision, and its reasons, which the task report restates:**

  - `WikidataClient` honours **no** base-URL system property. Its default endpoint is a `private
    static final URI` and the only seam is the public `WikidataClient(URI)` constructor, which
    nothing in `src/main` calls. So "point the real CLI at the stub through a property" is not
    available without inventing a property, and inventing one would put a network endpoint under the
    control of any process that can set a system property on the owner's machine — a worse thing than
    the test it would buy.
  - **`ExpandCli` therefore gets no seam at all, and no non-dry test.** `ExpandCliTest`'s class
    Javadoc already states the standing rule — "The end-to-end test is a DRY run, and that is what
    keeps it offline" — and `--add` makes it sharper, because a non-dry `--add` run fetches entities
    the graph has never seen. A package-private `run` overload taking a resolver would exist only for
    a test and would be the first such seam in any of the ten dev tools; YAGNI, and the thing it
    would cover is one construction line, which is exactly the coverage the existing
    `new EntityExpansion(...)` line has had since #284.
  - **The composition is tested at `ExpandRun`**, which is where it lives, over a `@TempDir`
    `SqliteAssertionLog`, a `TinkerGraphStore`, a real `IngestService`, a real `EntityAddition` built
    over a **stub `EntityResolver` written in the test file**, and a real `EntityExpansion` over
    fixture `SourceAdapter`s — the wiring `ExpandRunTest` already builds in `setUp`. Nothing in it
    opens a socket.

- [ ] **Step 2 — the structural move: a third constructor on `ExpandRun`.** Keep the two-argument
      constructor exactly as it is, so every existing call site compiles and stays green:

  ```java
    private final EntityExpansion expansion;
    private final GraphStore graph;
    private final Optional<EntityAddition> addition;

    /** A run that expands what it is handed and adds nothing — every population but {@code --add}'s. */
    public ExpandRun(EntityExpansion expansion, GraphStore graph) {
      this(expansion, graph, null);
    }

    /**
     * A run that may also add.
     *
     * <p><b>A constructor and not a parameter on the four methods</b> (#328): whether this run adds
     * is a property of the run, not of one call, and threading it through both {@code dryRun}
     * overloads and both {@code run} overloads would change four signatures — and their call sites —
     * to say one thing.
     *
     * @param addition the rule this run adds with, or null for a run that adds nothing
     */
    public ExpandRun(EntityExpansion expansion, GraphStore graph, EntityAddition addition) {
      this.expansion = Objects.requireNonNull(expansion, "expansion");
      this.graph = Objects.requireNonNull(graph, "graph");
      this.addition = Optional.ofNullable(addition);
    }
  ```

  Run the whole `expand` package green — that is this step's control.

- [ ] **Step 3 — RED: the dry run counts `to add`, and keeps it disjoint from `minted`.** Add to
      `ExpandRunTest`. `setUp` already records `IN_GRAPH_ONE`, `IN_GRAPH_TWO` and `MINTED`; add a
      second run object beside `run` and the ids the file names:

  ```java
    /** On the file, no node, and the stub resolver answers for it. */
    private static final String ADDABLE = "Q0901505";

    /** Minted by the owner and never recorded, so the graph holds no node for it either. */
    private static final String MINTED_NO_NODE = "Q00901502";

    /** A resolver that answers for exactly what it was given, and counts what it was asked. */
    private static final class CountingResolver implements EntityResolver {
      private final Map<String, NodeAssertion> answers = new HashMap<>();
      private final Set<String> unreachable = new HashSet<>();
      private int fetches;

      CountingResolver answering(String qid, String label) {
        answers.put(qid, new NodeAssertion(qid, NodeKind.PERSON, label, WIKIDATA));
        return this;
      }

      CountingResolver unreachableFor(String qid) {
        unreachable.add(qid);
        return this;
      }

      @Override
      public String id() {
        return "stub";
      }

      @Override
      public List<Candidate> search(String query, NodeKind kind, int limit) {
        throw new AssertionError("this run must not search");
      }

      @Override
      public Optional<NodeAssertion> fetch(String qid) {
        fetches++;
        if (unreachable.contains(qid)) {
          throw new WikidataUnavailableException("the stub was told this one is unreachable");
        }
        return Optional.ofNullable(answers.get(qid));
      }

      int fetches() {
        return fetches;
      }
    }
  ```

  and the two tests:

  ```java
    @Test
    @DisplayName("a dry run that may add counts what it would add and still asks no source anything")
    void shouldCountWhatItWouldAddWhenTheDryRunMayAdd() {
      CountingResolver resolver = new CountingResolver().answering(ADDABLE, "an act nobody booked");
      ExpandRun adding =
          new ExpandRun(expansionOver(resolver), graph, new EntityAddition(resolver, ingest));
      List<String> lines = new ArrayList<>();

      Preflight preflight =
          adding.dryRun(
              List.of(IN_GRAPH_ONE, MINTED, MINTED_NO_NODE, ADDABLE, NOT_IN_GRAPH),
              Optional.of(new KnownNeverExpanded("rejected.csv", 0, true)),
              lines::add);

      assertThat(preflight).isEqualTo(new Preflight(5, 1, 2, 2));
      assertThat(preflight.considered())
          .as("considered == in the graph + minted + to add, exactly, on an --add run")
          .isEqualTo(preflight.inTheGraph() + preflight.minted() + preflight.toAdd());
      assertThat(resolver.fetches()).as("a dry run fetches nothing").isZero();
      assertThat(neverAsked.get()).as("no adapter was called").isTrue();
    }

    @Test
    @DisplayName("a dry run that may not add counts nothing to add, however many ids have no node")
    void shouldCountNothingToAddWhenTheDryRunMayNotAdd() {
      List<String> lines = new ArrayList<>();

      Preflight preflight =
          run.dryRun(List.of(IN_GRAPH_ONE, MINTED, MINTED_NO_NODE, NOT_IN_GRAPH), lines::add);

      assertThat(preflight.toAdd())
          .as("every block already pasted into an issue came from a run like this one")
          .isZero();
    }
  ```

  `expansionOver(resolver)` is a small helper that builds the same `EntityExpansion` `setUp` builds,
  with the given resolver — extract it from `setUp` in this step. Run:

  ```
  ./gradlew test --tests 'com.robsartin.segue.expand.ExpandRunTest'
  ```

  Expect the first red with
  `expected: Preflight[considered=5, inTheGraph=1, minted=2, toAdd=2] but was: Preflight[considered=5, inTheGraph=1, minted=2, toAdd=0]`.
  The second passes: it is the pin. **Paste the real text.**

- [ ] **Step 4 — GREEN: count it.** In `ExpandRun.dryRun`'s long form:

  ```java
      int inTheGraph = 0;
      int minted = 0;
      int toAdd = 0;
      for (String qid : promotions) {
        if (LocalEntity.isLocal(qid)) {
          minted++;
        } else if (graph.node(qid).isPresent()) {
          inTheGraph++;
        } else if (addition.isPresent()) {
          // #328. Only counted when this run holds an addition, so a run without --add produces
          // the byte-identical block it always has. The isLocal check above still comes first, so
          // a minted id the graph holds no node for lands in `minted` and never here — which is
          // what makes considered == inTheGraph + minted + toAdd an identity rather than a hope.
          toAdd++;
        }
      }
      Preflight preflight = new Preflight(promotions.size(), inTheGraph, minted, toAdd);
  ```

  Re-run: both green.

- [ ] **Step 5 — RED: a missing id is added first, then expanded, in the file's order — and the same
      fixture without the switch still refuses it.** This is the planted positive control the spec
      asks for: one fixture, two runs, one switch between them. Add to `ExpandRunTest`:

  ```java
    @Test
    @DisplayName("an id the graph lacks is added and then expanded when the run may add")
    void shouldAddAndThenExpandWhenTheRunMayAddAndTheGraphLacksTheId() {
      CountingResolver resolver = new CountingResolver().answering(ADDABLE, "an act nobody booked");
      ExpandRun adding =
          new ExpandRun(expansionOver(resolver), graph, new EntityAddition(resolver, ingest));
      List<String> lines = new ArrayList<>();

      ExpansionTally tally = adding.run(List.of(ADDABLE), Optional.empty(), 10, lines::add);

      assertThat(tally.added()).isOne();
      assertThat(tally.expanded()).as("added first, then expanded, in the same pass").isOne();
      assertThat(tally.refusalsByReason()).isEmpty();
      assertThat(graph.node(ADDABLE)).isPresent();
    }

    @Test
    @DisplayName("the same id is refused as an unknown entity when the run may not add")
    void shouldRefuseTheSameIdAsUnknownWhenTheRunMayNotAdd() {
      List<String> lines = new ArrayList<>();

      ExpansionTally tally = run.run(List.of(ADDABLE), Optional.empty(), 10, lines::add);

      assertThat(tally.added()).isZero();
      assertThat(tally.expanded()).isZero();
      assertThat(tally.refusalsByReason())
          .containsExactly(entry(ExpansionOutcome.Reason.UNKNOWN_ENTITY, 1));
      assertThat(graph.node(ADDABLE)).isEmpty();
    }

    @Test
    @DisplayName("an id no source has an entity for is refused as no such entity, not as unknown")
    void shouldRefuseAsNoSuchEntityWhenNoSourceHasTheEntity() {
      CountingResolver resolver = new CountingResolver();
      ExpandRun adding =
          new ExpandRun(expansionOver(resolver), graph, new EntityAddition(resolver, ingest));
      List<String> lines = new ArrayList<>();

      ExpansionTally tally = adding.run(List.of(NOTHING_THERE), Optional.empty(), 10, lines::add);

      assertThat(tally.added()).isZero();
      assertThat(tally.refusalsByReason())
          .containsExactly(entry(ExpansionOutcome.Reason.NO_SUCH_ENTITY, 1));
      assertThat(lines).anyMatch(line -> line.contains("refused: NO_SUCH_ENTITY"));
      assertThat(lines).noneMatch(line -> line.contains(NOTHING_THERE));
    }

    @Test
    @DisplayName("a source that cannot be reached on the add is a failure, not a refusal")
    void shouldCountAFailureWhenTheSourceCannotBeReachedOnTheAdd() {
      CountingResolver resolver = new CountingResolver().unreachableFor(UNREACHABLE_ON_ADD);
      ExpandRun adding =
          new ExpandRun(expansionOver(resolver), graph, new EntityAddition(resolver, ingest));
      List<String> lines = new ArrayList<>();

      ExpansionTally tally =
          adding.run(List.of(UNREACHABLE_ON_ADD), Optional.empty(), 10, lines::add);

      assertThat(tally.failed()).isOne();
      assertThat(tally.refusalsByReason()).isEmpty();
      assertThat(lines).noneMatch(line -> line.contains(UNREACHABLE_ON_ADD));
    }

    @Test
    @DisplayName("a minted id the graph lacks is never handed to the add rule")
    void shouldNeverAskTheAddRuleWhenTheIdWasMintedByTheOwner() {
      CountingResolver resolver = new CountingResolver();
      ExpandRun adding =
          new ExpandRun(expansionOver(resolver), graph, new EntityAddition(resolver, ingest));
      List<String> lines = new ArrayList<>();

      ExpansionTally tally = adding.run(List.of(MINTED_NO_NODE), Optional.empty(), 10, lines::add);

      assertThat(resolver.fetches()).as("the rule was never asked, so no source was").isZero();
      assertThat(tally.refusalsByReason())
          .containsExactly(entry(ExpansionOutcome.Reason.LOCAL_ENTITY, 1));
    }
  ```

  with `NOTHING_THERE = "Q0901506"` and `UNREACHABLE_ON_ADD = "Q0901507"` as constants. Run and
  observe four reds — `added` zero where one was expected, `UNKNOWN_ENTITY` where `NO_SUCH_ENTITY`
  was expected, `failed` zero, and `LOCAL_ENTITY` reached only after the rule was asked. The second
  test (`shouldRefuseTheSameIdAsUnknownWhenTheRunMayNotAdd`) **passes now and must still pass at the
  end** — it is the control that the switch is what changed the behaviour. **Paste every failure.**

- [ ] **Step 6 — GREEN: add before expanding.** In `ExpandRun.run`'s loop, immediately inside the
      `for` and before the `try` that calls `expansion.expand`:

  ```java
      for (int i = 0; i < promotions.size(); i++) {
        String qid = promotions.get(i);
        // #328. The only place this run departs from "expand what you are handed": an id the file
        // names that the graph holds no node for is added first, and then expanded, in the same
        // pass and in the population's own order. A minted id is never offered to the rule — it
        // always fails LocalEntity.isLocal, so asking would spend a refusal to learn what the shape
        // says, and it would also break the dry run's arithmetic, where `minted` and `to add` are
        // kept disjoint by exactly this check. The expansion below refuses it as it always has.
        if (addition.isPresent() && !LocalEntity.isLocal(qid) && graph.node(qid).isEmpty()) {
          AdditionOutcome outcome = addition.get().add(qid);
          if (outcome instanceof AdditionOutcome.Refused refused) {
            switch (refused.reason()) {
              case NO_SUCH_ENTITY -> {
                refusalsByReason.merge(ExpansionOutcome.Reason.NO_SUCH_ENTITY, 1, Integer::sum);
                lines.accept(
                    progress(i, promotions.size(), "refused: " + ExpansionOutcome.Reason.NO_SUCH_ENTITY));
              }
              // An outage on the add is a failure exactly as an outage during an expansion is:
              // nothing is wrong with the id, and a later run will reach it. NOT_A_QID and
              // LOCAL_ENTITY cannot arise from this tool's populations — QidList yields only
              // Q\d+, and the guard above takes every minted id — but the switch is exhaustive
              // rather than defaulted, because a reason this run cannot name is a defect in this
              // run and not a row in somebody's block.
              case SOURCE_UNAVAILABLE, NOT_A_QID, LOCAL_ENTITY -> {
                failed++;
                log.warn(
                    "addition {} of {} refused: {}", i + 1, promotions.size(), refused.reason());
                lines.accept(progress(i, promotions.size(), "failed"));
              }
            }
            continue;
          }
          added++;
        }
        ExpansionOutcome outcome;
        try {
          outcome = expansion.expand(qid, maxNewEdges);
        } catch (RuntimeException thrown) {
  ```

  with `int added = 0;` beside the other counters and `added` passed into the `ExpansionTally`
  construction in the position Task 2 gave it. Add a paragraph to the class Javadoc saying that the
  addition is the one thing this class does before expanding, that it never composes or filters the
  population, and that the refusal line carries the reason constant and no qid for the reason the
  class already gives. Re-run: all five green, including the control.

- [ ] **Step 7 — the positive control for the log line: prove it names no entity.** Plant the defect
      by changing the `log.warn` above to interpolate `qid`, and add nothing — instead run

  ```
  ./gradlew test --tests 'com.robsartin.segue.expand.ExpansionIsSafeToPasteTest' --tests 'com.robsartin.segue.expand.ExpandRunTest'
  ```

  and observe `shouldCountAFailureWhenTheSourceCannotBeReachedOnTheAdd`'s
  `assertThat(lines).noneMatch(...)` stay green — **because the plant is in the log, not in `lines`**.
  That is the finding to report: `ExpansionIsSafeToPasteTest` and the `lines` assertions guard the
  *pasted block*, and nothing in this repository guards a `log.warn`. Restore the plant immediately,
  then plant the qid in the `lines.accept(...)` call instead, re-run, and observe
  `shouldCountAFailureWhenTheSourceCannotBeReachedOnTheAdd` red with
  `expecting no elements to match … but found "[1/1] failed Q0901507"` or equivalent. **Paste both
  outcomes.** Restore, re-run green.

- [ ] **Step 8 — GREEN: wire it in `ExpandCli.run`.** In the wiring block:

  ```java
        ExpandRun run =
            options.add()
                ? new ExpandRun(expansion, graph, new EntityAddition(resolver, ingest))
                : new ExpandRun(expansion, graph);
  ```

  and in the `--known` branch, carry the switch onto the population value:

  ```java
          covered =
              Optional.of(
                  new KnownNeverExpanded(
                      known.name(), named.size() - population.size(), options.add()));
  ```

  Add to the class Javadoc one paragraph:

  ```java
   * <p><b>It adds what the file names that the graph lacks, and only when {@code --add} asks.</b>
   * The population is composed exactly as a {@code --known} run composes it; what changes is that an
   * id in it the graph holds no node for is added through {@code expansion.EntityAddition} and then
   * expanded, rather than refused as an unknown entity. The switch is refused without {@code
   * --known}, and with either of the other two populations, because both of those are drawn from the
   * graph and nothing in them can be missing — only a file can name an entity the graph lacks.
   * {@code IngestService.record} is still the only write this package reaches (#328, ADR 19).
  ```

- [ ] **Step 9 — RED then GREEN: the dry run through the whole CLI wiring, offline.** Add to
      `ExpandCliTest`, in the `--known` section, using the ids from the Global Constraints' table.
      **This is a `--dry-run` test and it is the only place `--add` reaches `ExpandCli.run` in this
      plan.** Record `ON_FILE_IN_GRAPH` (`Q0901504`) and `ALSO_IN_GRAPH` (`Q0901510`) as node claims
      in the `@TempDir` database, put `Q0901505` (no node) and `Q00901502` (minted, never recorded)
      on the file beside them, and assert on the captured log lines:

  ```java
    @Test
    @DisplayName("a dry run with --add says how many it would add, and appends nothing")
    void shouldSayHowManyItWouldAddWhenTheDryRunWasToldToAdd() {
      Path database = home.resolve("segue.db");
      seedForAdding(database);
      Path file = knownListNaming(ON_FILE_IN_GRAPH, ALSO_IN_GRAPH, ADDABLE, MINTED_NO_NODE);

      ExpandCli.run(
          new String[] {
            "--db", database.toString(), "--dry-run", "--known", file.toString(), "--add"
          },
          null,
          home.toString());

      assertThat(lines()).contains("  to add        1");
      assertThat(lines()).anyMatch(line -> line.contains("--add was given"));
      assertThat(new SqliteAssertionLog(database).readAll())
          .as("a dry run appends nothing, --add or not")
          .hasSize(claimsSeeded());
    }

    @Test
    @DisplayName("a dry run without --add prints no to-add row, on the same file")
    void shouldPrintNoToAddRowWhenTheSameDryRunWasNotToldToAdd() {
      Path database = home.resolve("segue.db");
      seedForAdding(database);
      Path file = knownListNaming(ON_FILE_IN_GRAPH, ALSO_IN_GRAPH, ADDABLE, MINTED_NO_NODE);

      ExpandCli.run(
          new String[] {"--db", database.toString(), "--dry-run", "--known", file.toString()},
          null,
          home.toString());

      assertThat(lines()).noneMatch(line -> line.contains("to add"));
      assertThat(lines()).noneMatch(line -> line.contains("--add was given"));
    }
  ```

  `seedForAdding`, `knownListNaming`, `claimsSeeded` and `lines()` follow the helpers the existing
  `--known` tests in this class already use; **read them and reuse rather than write new ones.** Run,
  observe the first red (`to add` absent, because the file's ids are all covered or minted until the
  helper is right), then adjust the fixture until the red is the honest one, then green. **Paste
  both.** The second test is the pin.

- [ ] **Step 10 — the gate and the commit.**

  ```
  ./gradlew spotlessApply
  grep -rn '{@code$' src/main/java/com/robsartin/segue/expand
  SEGUE_REQUIRE_BROWSER=true SEGUE_REQUIRE_GRAPHVIZ=true ./gradlew check --rerun-tasks
  git status --short
  git add src/main/java/com/robsartin/segue/expand/ExpandRun.java src/main/java/com/robsartin/segue/expand/ExpandCli.java src/test/java/com/robsartin/segue/expand/ExpandRunTest.java src/test/java/com/robsartin/segue/expand/ExpandCliTest.java
  git status --short
  git commit
  ```

  Subject: `#328 add what a --known file names that the graph lacks, then expand it`.

---

## Task 5 — The records: ADR 66's amendment, the runbook chapter, and two tables

**Files:** `docs/adr/0066-expand-every-promotion-from-a-dev-tool.md`, `docs/developer-guide.md`,
`src/test/java/com/robsartin/segue/expand/DeveloperGuideExpandPromotionsExamplesTest.java`.

- [ ] **Step 1 — RED first, on the document test.** The new chapter is a `###` section **inside**
      `## Expanding every promotion`, so
      `DeveloperGuideExpandPromotionsExamplesTest.shouldRunEveryStepInOrderWhenTheChapterIsRead`
      reads its commands too. Extend `steps()` to notice the switch, and extend the expected list —
      **before writing the chapter**:

  ```java
          if (example.arguments().contains("--add")) {
            command += " --add";
          }
  ```

  placed after the `--known` clause and before the `--second-hop` one, and the expected list gains,
  at the end:

  ```java
              "graphCensus --known",
              "expandPromotions --dry-run --known --add",
              "expandPromotions --known --add",
              "graphCensus --known",
              "rate --known"
  ```

  — **except** that `steps()` only merges `graphCensus` and `expandPromotions`; adding `rate` means
  adding it to the `List.of("graphCensus", "expandPromotions")` loop **and** giving it a `command`
  of its own. Do that, and extend the `@DisplayName` and the `as(...)` sentence to say that the
  fourth variant is sub-project 2(a) end to end, that its census comes first because `named` against
  `in the graph` is the count the run would add, and that the deck session comes last because the
  rows are not known until they are rated (ADR 48). Run:

  ```
  ./gradlew test --tests 'com.robsartin.segue.expand.DeveloperGuideExpandPromotionsExamplesTest'
  ```

  Observe the red: `containsExactly` reporting the five missing trailing elements. **Paste it.**

- [ ] **Step 2 — GREEN: the chapter.** Insert **immediately after** the `--second-hop` chapter and
      **immediately before** `### What to file from what you saw`, verbatim:

  > ### Adding what your list names that the graph has never held: `--known --add`
  >
  > The three chapters above all expand something the graph already holds. This one covers the
  > entities it does not: the rows the original names list carried that Setlist Scout rejected as
  > non-touring — the authors, thinkers and comedians `seed.SeedRow`'s note on `status` calls the
  > relations this graph is short of. The seed tool resolved them to ids in the same mapping as the
  > touring acts ([ADR 40](../../adr/0040-bulk-seeding-as-a-dev-tool.md)) and stopped there, because
  > nothing in this repository adds entities in bulk. `--add`, given beside `--known`, adds an id the
  > file names that the graph holds no node for — the same fetch-and-record the `add_entity` MCP tool
  > does, through the shared `expansion.EntityAddition` — and then expands it, in the same pass and
  > in the file's order. Without `--add` that id is refused as an unknown entity, exactly as it
  > always has been.
  >
  > `--add` is refused without `--known`, and with `--rated-since` or `--second-hop`: both of those
  > populations are drawn from the graph, so nothing in them can be missing, and only a file can name
  > an entity the graph lacks.
  >
  > **Step 0 applies unchanged**, and so does everything this chapter says about a single writer.
  >
  > **1. Derive the file.** The mapping the seed tool wrote has a `status` column, and the rejected
  > rows are the ones you want. `SeedFiles` quotes a field only when it holds a comma, a quote or a
  > newline, so `status` is never quoted and always sits between two commas — but a **name** with a
  > comma in it is quoted, which shifts the columns of that row. Match the literal field, not the
  > third comma-separated one, and write the result outside the working tree:
  >
  > ```bash
  > grep ',REJECTED,' "$HOME/names-resolved.csv" > "$HOME/rejected.csv"
  > ```
  >
  > `QidList` reads that file exactly as it reads the mapping — the first comma-separated field that
  > is exactly a qid — so no reshaping is needed and the review rows, which carry no qid of their
  > own, are passed over. **The file is personal data**: a list of who someone reads and watches is
  > what [ADR 33](../../adr/0033-taste-layer-separation.md) governs, `*.csv` is gitignored beside `*.db`,
  > and the protection is where the file lives rather than what git ignores (issue #37).
  >
  > **2. The census over it**, before anything is written:
  >
  > ```bash
  > ./gradlew graphCensus --args="--db $HOME/.segue/segue.db --known $HOME/rejected.csv"
  > ```
  >
  > `named` minus `in the graph` is what a `--add` run would add; `never expanded` is what it would
  > then expand. Both are readings to compare against, not numbers to drive to zero — the same rule
  > the `--known` chapter states in full.
  >
  > **3. The dry run, and then the run:**
  >
  > ```bash
  > ./gradlew expandPromotions --args="--db $HOME/.segue/segue.db --dry-run --known $HOME/rejected.csv --add"
  > ```
  >
  > ```bash
  > ./gradlew expandPromotions --args="--db $HOME/.segue/segue.db --known $HOME/rejected.csv --add"
  > ```
  >
  > **What the two blocks say that no other run's do.** The dry run gains a `to add` row, and
  > `considered` is then exactly `in the graph` plus `minted` plus `to add` — read `to add` against
  > step 2's `named` minus `in the graph` and they should agree; a disagreement is a defect in one of
  > them. The real block gains an `added` row under `promotions`, counting entities this run
  > recorded before expanding them, and `refused, by reason` may gain `no such entity` — an id your
  > file names that Wikidata has no entity at, which is a different fault from `unknown entity` and
  > is worth a look at the mapping row it came from. **Both rows print only when they are not zero**,
  > so a run that added nothing prints the block it always printed; the `#` clause under the header
  > is what says `--add` was given at all. An outage while adding is a `failed`, like any other
  > failure, and a later run reaches it.
  >
  > **4. The census again**, with the same file, and compare it against step 2's: `named` is
  > unchanged, `in the graph` is up by what `added` said, and `never expanded` is down by what the
  > run expanded.
  >
  > **5. A deck session with this file as the deck's own `--known`:**
  >
  > ```bash
  > ./gradlew rate --args="--known $HOME/rejected.csv --db $HOME/.segue/segue.db"
  > ```
  >
  > The deck deals what its file names that is in the graph and unrated, degree first, with a
  > candidate every fifth card as it always does. **The deck's `--known` is a statement about what to
  > deal, and it is per session**: it does not make this file the recommender's list, and no other
  > tool learns about it.
  >
  > **6. Everything else keeps the touring file.** `recommend`, `graphCensus --known` and the
  > evaluation harness all keep taking the list they always took. Whichever of these new rows you
  > rated at or above `KnownList.PROMOTION_RATING` is on that list already, by the promotion rule
  > ([ADR 48](../../adr/0048-a-high-rating-counts-as-something-you-have.md)) — which is why there is no
  > second membership rule here and no merging of the two files.
  >
  > **7. The next reading** follows on the recommender's normal rule, with its own note, after the
  > deck session: the graph moved and the taste layer moved, so the note says which observations it
  > allows.
  >
  > **When to stop.** The same rule the `--known` chapter gives, read over this file: compare one
  > dry run's `considered` against the previous one's. What is new here is that `to add` should fall
  > to zero after the first `--add` run and stay there — an entity that was added has a node, so the
  > next dry run counts it under `in the graph`. A `to add` that does **not** fall is a run that
  > could not reach Wikidata or a file that keeps growing, and the `failed` row and the
  > `unavailable` sub-heading of the previous real run are what tell the two apart.

- [ ] **Step 3 — the two table rows.** In *What each package is for*, the `expansion` row gains,
      before its final sentence: `` Also `EntityAddition`, the fetch-and-record the `add_entity` MCP
      tool and the expander's `--add` run share, so one rule decides what adding an entity means
      (#328). `` In the same table, the `expand` row gains, before `` `--db` is required ``:
      `` since #328 a `--known` run also takes `--add`, which adds an id that file names and the
      graph holds no node for — through `expansion.EntityAddition`, the rule `add_entity` reads too —
      and then expands it in the same pass; `--add` is refused without `--known` and with either of
      the other two populations. `` Leave the "Depends on" columns alone: `expansion` already depends
      on `ingest`, and `expand` already depends on `expansion`.

- [ ] **Step 4 — the sentence in *Two-pass ingest*.** In *The full call, end to end*, in the
      paragraph beginning **What the diagram shows**, after the sentence naming `EntityExpansion`,
      insert: `An `add_entity` call takes the other of the two shared rules, `EntityAddition`: it
      checks the id's shape, refuses one the owner minted before any source is asked, fetches the
      entity's identity, and records it through `IngestService` — the same body the promotion
      expander's `--add` run uses, so the two cannot come to disagree about what adding an entity
      means (#328).` Write it with the backticks as code spans and the link-free prose the
      surrounding paragraph uses.

- [ ] **Step 5 — run the document tests.** Blocking:

  ```
  ./gradlew test --tests 'com.robsartin.segue.expand.DeveloperGuideExpandPromotionsExamplesTest' --tests 'com.robsartin.segue.arch.DeveloperGuideEnumerationsTest' --tests 'com.robsartin.segue.arch.DocumentationLinksTest'
  ```

  All green. If `shouldParseEveryExampleWhenTheGuideShowsTheTool` reds, an example in the new chapter
  does not parse — read the message, which names the line and the refusal.

- [ ] **Step 6 — the positive control for the order assertion: prove it can fire.** Swap the new
      chapter's dry-run block and its run block, run the filter from Step 5, and observe
      `shouldRunEveryStepInOrderWhenTheChapterIsRead` red naming the two elements out of order.
      **Paste it.** Swap them back and re-run green.

- [ ] **Step 7 — ADR 66's amendment.** Append at the very end of
      `docs/adr/0066-expand-every-promotion-from-a-dev-tool.md`, editing nothing above it, verbatim:

  > **Amendment (2026-09-15, issue #328): a `--known` run takes `--add`, and adds what the file names
  > that the graph holds no node for before expanding it.**
  >
  > Nothing above is withdrawn, no decision above is edited, and this ADR keeps `Accepted`. The
  > 2026-09-12 amendment for #313 shipped `--known` and said, of an id the graph holds no node for,
  > that it "is refused, not dropped" — "the first coverage gap there is, and it is reported rather
  > than filtered away". That stays the default and stays true of every run that does not ask for
  > otherwise. What changes is that a run may now be asked to close that gap instead of reporting it.
  > `ExpandCli.USAGE` is the authority on the flags' current text, and `ExpansionReport` on the
  > block's.
  >
  > **The switch.** `--add`, given beside `--known` and nowhere else. The population is composed
  > exactly as a `--known` run composes it — the file's ids through the merge fold, minus those
  > `domain.Expanded` covers — and the remainder then splits: an id the graph holds a node for is
  > expanded as before, and an id it holds none for is added first and expanded second, in the same
  > pass and in the file's order. Not given, every run behaves exactly as it did.
  >
  > **One rule, in `expansion`, and why it went there.** The fetch-and-record was the MCP facade's:
  > check the shape, ask the resolver, record the node claim through `IngestService`, which upserts.
  > It is now `expansion.EntityAddition`, and `SegueService.addEntity` reads it. This is #284's
  > argument one step earlier in the same story — two callers, one body, and the second caller needed
  > the rule and not a tool result. It could not stay in `mcp`, because
  > `ArchitectureTest.theExpanderOpensNothingElse` forbids the expander that package outright; it
  > could not go to `domain`, which names no port; and a copy in `expand` is how two tools come to
  > answer one question differently, which this repository has already measured once.
  >
  > **No fence moves, and that is checked rather than asserted.**
  > `ArchitectureTest.onlyTheClientAndTheExpanderExpandAnEntity` already bars every package but the
  > two callers, the wiring and `expansion` itself from reaching into `expansion`, so the new class
  > joins that fence by living there. `IngestService.record` is still the only write the expander can
  > make, so `theExpanderWritesThroughIngestAlone` holds unchanged, and no dev-tool package list
  > gains or loses a name.
  >
  > **[ADR 26](../../adr/0026-mcp-tool-surface.md) and [ADR 19](../../adr/0019-assertion-log-source-of-truth.md) are
  > untouched, and this says so out loud.** The tool surface is still six tools: nothing was added to
  > it, nothing was removed from it, and `add_entity` returns the same result for the same input with
  > one exception named below. The single writer is still `IngestService`: the new rule appends
  > through it and reaches neither the log nor the graph directly, which is the same relationship the
  > expansion has had since #284.
  >
  > **The one behaviour of `add_entity` that did change**, recorded here rather than left to be
  > discovered: the rule refuses a qid the owner minted before the resolver is asked. Before this
  > issue the tool checked only `Q` followed by digits and then fetched, so such an id went to
  > Wikidata and came back as "no such entity" — a round trip spent to learn what ADR 58's grammar
  > already fixes, and an answer about the wrong thing. It now returns a refusal that names the
  > minting, in the shape the expansion's own local-entity refusal already uses.
  >
  > **What the block says.** One row under `promotions`, `added`, counting entities a run recorded
  > before expanding them; one row in the dry run's preflight, `to add`, counting the ones it would;
  > and one more constant in the refusal reasons, for an id no source has an entity at, labelled
  > distinctly from the id the graph merely has not got yet. **Both new rows print only when they are
  > not zero**, and a run that was not given the switch cannot make either of them non-zero, so every
  > block already pasted into an issue is byte-identical — the byte-identity the 2026-09-12 amendment
  > kept for the same reason, and ADR 65's 2026-09-06 amendment before it. `considered` is still the
  > population the run was handed, so `considered == expanded + refused + failed` is untouched:
  > `added` is a count of a step, not a fourth member of that partition, and an entity that was added
  > and then expanded is counted under both.
  >
  > **The `#` clause says the switch was given, and not how many it added.** That number is a row,
  > and the clause's own rule — the one the 2026-09-11 and 2026-09-12 amendments each argue for — is
  > that it carries what no row states. The population value the clause is rendered from is also
  > composed before the first entity is visited and is rendered into the dry-run block as well, where
  > nothing has been added yet. What a pasted block could not otherwise tell is that the switch was
  > given at all, precisely because both new rows are suppressed when they are zero, and that is what
  > the clause now says.
  >
  > **Refused where no file names the population.** `--add` with `--rated-since` and `--add` with
  > `--second-hop` are each refused in the words the existing pairwise refusals use, and `--add`
  > without `--known` is refused in its own: both of those populations are drawn from the graph — one
  > is `KnownList.promoted`, the other a ring read out of the fold's own nodes — so nothing in either
  > can be missing. A run naming both `--known` and `--rated-since` beside `--add` is still refused in
  > the sentence already on record, so a script written against it still reads.
  >
  > **Idempotent, for the reason a `--known` run is.** Adding is `IngestService.record`, which
  > upserts: a second `--add` run over the same file re-records nothing it does not have to, and an
  > entity added on the first run has a node on the second, so it is expanded rather than added. An
  > entity whose expansion recorded nothing seed-shaped stays in the population and is refreshed,
  > which is the floor this ADR's 2026-09-12 amendment for #315 and its 2026-09-14 amendment for #326
  > already describe; nothing here changes that floor or claims to.
  >
  > **Alternatives rejected.**
  >
  > - **A separate bulk-add dev tool.** Single responsibility, and rejected on the same evidence the
  >   2026-09-12 amendment rejected a separate known-list tool: an eleventh tool would be this one's
  >   replay, block, dry run, fences and chapter under a new name, with one step different, and the
  >   owner would run two things where one pass does both.
  > - **Adding through the MCP tools by hand.** Hundreds of calls, no dry run, no aggregates block to
  >   paste, and no record of what was done. It is also, as far as anything under version control
  >   says, how the touring acts were first added — which is the argument for writing the mechanism
  >   down rather than repeating it.
  > - **Adding on every `--known` run, with no switch.** Rejected: a file carrying a typo'd or stale
  >   id would then create a node for it, silently, on a tool that writes the log. The switch keeps
  >   "refuse what the graph lacks" the default, which is what the 2026-09-12 amendment chose.
  > - **Treating the added rows as known without rating them.** Rejected: the owner chose to rate
  >   first, and the promotion rule
  >   ([ADR 48](../../adr/0048-a-high-rating-counts-as-something-you-have.md)) already turns a high rating into
  >   membership, so a second membership rule would be two answers to one question.
  > - **Merging the rejected rows into the touring file.** Rejected: that is the alternative above
  >   reached from the other side — it would make them known to every tool at once — and it would put
  >   the file's provenance beyond the export that produced it.
  >
  > **Nothing here is unit-testable on its own, and that is said out loud rather than left implied.**
  > This entry records a decision whose code landed with its own tests: the extracted rule's five
  > outcomes against an in-process stand-in for the API, with the minted id's refusal shown firing on
  > a planted control and the request counter proving no source was asked; the MCP facade's existing
  > offline cases unedited as the control that the tool's words did not move; the parser's three
  > refusals and the older sentence still winning, each seen red; the two new rows seen red and then
  > pinned, beside the unchanged golden block; and the composition seen red on one fixture run twice,
  > once with the switch and once without. The verification of the *document* is the full gate over an
  > otherwise unchanged tree: `AdrIndexTest`, `AdrCitationsTest`, `DocumentationLinksTest` for the
  > relative links above, and `javadoc -Werror` inside `./gradlew check`.

  **Before committing, check the ADR by hand:** `grep -n 'Q[0-9]' docs/adr/0066-*.md` must find
  nothing new, and no backticked run of seven or more digits may appear anywhere in what was added.

- [ ] **Step 8 — the gate and the commit.**

  ```
  ./gradlew spotlessApply
  SEGUE_REQUIRE_BROWSER=true SEGUE_REQUIRE_GRAPHVIZ=true ./gradlew check --rerun-tasks
  git status --short
  git add docs/adr/0066-expand-every-promotion-from-a-dev-tool.md docs/developer-guide.md src/test/java/com/robsartin/segue/expand/DeveloperGuideExpandPromotionsExamplesTest.java
  git status --short
  git commit
  ```

  Subject: `#328 record the switch: ADR 66 amendment and the sub-project 2(a) runbook`.

---

## Task 6 — The final gate

**Files:** none. This task changes nothing and proves everything.

- [ ] **Step 1 — verify `docs/` is already a declared test input rather than declaring it.** Run:

  ```
  grep -n 'inputs.dir("docs")' build.gradle.kts
  grep -n 'inputs.file("README.md")' build.gradle.kts
  ```

  Both must print a line. **If either prints nothing, stop and report** — do not add a declaration;
  the Global Constraints say this is a verification, and a missing one is a finding.

- [ ] **Step 2 — the full gate, blocking, from `/Users/sartin/code/segue/wt-328`:**

  ```
  SEGUE_REQUIRE_BROWSER=true SEGUE_REQUIRE_GRAPHVIZ=true ./gradlew check --rerun-tasks
  ```

  Never backgrounded. Report `BUILD SUCCESSFUL` and the wall time Gradle itself printed — that is a
  report of what Gradle said, not an assertion about it.

- [ ] **Step 3 — count the tests ONCE, from the XML.** Run exactly this, and report the number:

  ```
  grep -ho 'tests="[0-9]*"' build/test-results/test/*.xml | grep -o '[0-9]*' | paste -sd+ - | bc
  ```

  Count it once and quote that one number. Do not add up Gradle's console lines as a second count.

- [ ] **Step 4 — confirm the branch is what it should be.**

  ```
  git status --short
  git log --oneline origin/main..HEAD
  git log -1 --format=%B
  ```

  `git status --short` shows nothing uncommitted. The log shows the plan commit plus one commit per
  task above it. **`git log -1 --format=%B` ends with the blank line and
  `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>` — check the model name character by
  character; a wrong one has cost this branch a replay before.** Report all three outputs.
