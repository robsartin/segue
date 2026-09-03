# A read-only census of the owner's graph — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `./gradlew graphCensus --args="--db $HOME/.segue/segue.db"` prints one plain-text table of counts — nodes, edges, claims, taste, degree, bridge — and nothing else. No labels, no ids, no notes, so the owner can paste it into an issue.

**Architecture:** A sixth dev-tool package `census`, fenced by four new ArchUnit rules and joined to `ArchitectureTest.DEV_TOOL_PACKAGES`. Six section records, each a pure `of(...)` over `export.LogProjection` and the raw log, plus one shared `Degrees` helper and one formatter. `--db` is required (ADR 60's clause, not its consequence). `AffinityStore.readRatings` is widened to a third package, which is what makes this ADR-level.

**Tech Stack:** Java 25, Gradle 9.7.1 (plain `./gradlew`), JUnit 5, AssertJ, ArchUnit, SQLite, Logback.

**Spec:** `docs/superpowers/specs/2026-09-03-graph-census-design.md`

## Global Constraints

- **Pure TDD / red first**: every behaviour is seen red for the right reason — a real assertion failure, never a compile error — before the code that makes it green. Where a class does not exist yet, create a **compiling stub that returns the wrong answer**, then write the test, then watch the assertion fire. Quote the actual failure text in the report. Test names `should<Expected>When<Condition>` with `@DisplayName`.
- **Every guard gets a positive control**: plant the defect, watch the check fire, quote it, remove the plant. Written out as steps below.
- **Mikado**: the gate is green before every commit. **Stage by explicit path, git stderr visible — never `git add -A`, never `2>/dev/null` on `git add`.** Read `git status` before every commit. Commits end `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`. Never cite a `.superpowers/` path from a committed file.
- **Derived documentation moves in the same commit as the code.** Four `DeveloperGuideEnumerationsTest` checks read the tree: the layering diagram's node set (one per directory under `src/main/java/com/robsartin/segue`), its edge set (one per `import com.robsartin.segue.<other package>.` in `src/main`), the "What each package is for" row set, and the ArchUnit rule table. **Any step that creates the package, adds a new cross-package import to `census`, or adds an `@ArchTest` rule edits `docs/developer-guide.md` in the same commit.** Each task below names the edges it introduces.
- Gate, **blocking, never backgrounded**: `SEGUE_REQUIRE_BROWSER=true SEGUE_REQUIRE_GRAPHVIZ=true ./gradlew check --rerun-tasks`. Fast loops named per task. Run `./gradlew spotlessApply` before each gate.
- **Only JDK 25 is installed and Gradle 9.7.1 launches on it.** Plain `./gradlew`; never `/usr/libexec/java_home -v 21` (it silently returns 25).
- **Never run a writing dev task** (`own`, `ownClaim`, `retractEntity`, any seeding task). **Never run `graphCensus` against `~/.segue/segue.db`.** That file is never read, written, copied or created; every test builds its own database under a `@TempDir`.
- Every id invented in `src/test` must take an unallocatable shape or `arch/StandInQidsDenoteNothingTest` reds: two leading zeros for a local entity (ADR 59), eleven digits with no leading zero for a merge's canonical side (ADR 62), one leading zero for anything else (ADR 58).
- **A stub step carries only the imports its stub body uses.** `spotlessApply` runs `removeUnusedImports`, so an import added ahead of the code that needs it is silently deleted and the next compile fails on a name that was there a minute ago. Each GREEN step below adds the imports its own code needs.
- **YAGNI**: no parameter, helper or accessor beyond what a step below actually uses. Exactly the issue's numbers, no options, no `--out`.
- Machine is loaded: no wall-clock assertions anywhere.

---

### Task 1: The package, the task, and `--db` required

**Files:** Create `src/main/java/com/robsartin/segue/census/CensusCli.java`, `src/test/java/com/robsartin/segue/census/CensusCliTest.java`. Modify `build.gradle.kts`, `src/test/java/com/robsartin/segue/arch/ArchitectureTest.java`, `docs/developer-guide.md`.

New import edge introduced: `census --> support`.

- [ ] **Step 1 — the stub that compiles and answers wrongly.** Create `CensusCli.java`:

```java
package com.robsartin.segue.census;

import com.robsartin.segue.support.RequiredDatabase;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The entry point, run from Gradle: {@code ./gradlew graphCensus --args="--db <segue.db>"}.
 *
 * <p><b>The sixth dev-side tool, and the only one whose whole output is aggregates.</b> ADR 51
 * draws the line — a count over the owner's data may be published, an entity presented as the
 * owner's may not — and says in as many words that the line is held by review and nothing else.
 * That is true in general and false for one artefact: this tool emits no free text from the data at
 * all, so {@code CensusIsSafeToPasteTest} can hold it mechanically. See ADR 63.
 *
 * <p><b>{@code --db} is required, and {@code SEGUE_DB} does not satisfy it.</b> Not ADR 60's
 * consequence — nothing here writes, and a wrong count costs a re-run — but ADR 60's central
 * clause: an agent's shell is initialised from the owner's profile and inherits the variable, and
 * this tool's output is the shape of the owner's whole graph and taste layer. Whether to produce
 * that is the owner's decision per invocation. The second half is that a census is evidence: it is
 * pasted into an issue and quoted in an ADR, where a wrong export is discarded and a wrong count
 * becomes the record.
 *
 * <p><b>No {@code --out}, and no {@code System.out}.</b> {@code
 * ArchitectureTest.nothingWritesToStandardOut} bans stdout project-wide (ADR 28, ADR 30), so the
 * table goes through SLF4J at {@code info}, one call per line — the route {@code ExportCli} and
 * {@code RatingsCli} already use for their notes. {@code RatingsCli} writes a file because ADR 33
 * keeps affinity out of every log line and its output is the whole taste layer; this output is
 * counts alone, so there is nothing a log line may not carry and nothing left on disk afterwards.
 */
public final class CensusCli {

  private static final Logger log = LoggerFactory.getLogger(CensusCli.class);

  private static final String USAGE = "usage: --db <segue.db>";

  private CensusCli() {}

  /**
   * The database to count.
   *
   * @param database no default, on purpose — see this class's Javadoc, and {@code
   *     support.RequiredDatabase}, which owns the refusal sentence
   */
  public record Options(Path database) {

    public Options {
      Objects.requireNonNull(database, "database");
    }
  }

  /** Parse and validate, refusing anything that could not work before a store is opened. */
  static Options parse(String[] args, String envDatabase, String userHome) {
    Path database = null;

    for (int i = 0; i < args.length; i++) {
      String flag = args[i];
      String value = valueOf(args, i, flag);
      i++;
      if ("--db".equals(flag)) {
        database = Path.of(value);
      } else {
        throw usage("unknown option " + flag);
      }
    }

    return new Options(database == null ? Path.of("unset") : database);
  }

  private static String valueOf(String[] args, int i, String flag) {
    if (i + 1 >= args.length) {
      throw usage(flag + " needs a value");
    }
    return args[i + 1];
  }

  private static IllegalArgumentException usage(String problem) {
    String sentence = problem.endsWith(".") ? problem : problem + ".";
    return new IllegalArgumentException(sentence + " " + USAGE);
  }

  public static void main(String[] args) {
    run(args, System.getenv("SEGUE_DB"), System.getProperty("user.home"));
  }

  /**
   * {@code main}, with the two environment reads passed in.
   *
   * <p>A seam for the same reason {@code RetractCli.run} is one: the order of the two refusals is
   * the behaviour. A missing {@code --db} has to be refused by {@link #parse} before {@code
   * Files.exists} is reached, or the operator is told "no segue database at …" — which reads as a
   * missing file rather than a missing flag, and names a path they never typed. A test can only
   * hold that order if it can supply a home directory of its own.
   */
  static void run(String[] args, String envDatabase, String userHome) {
    Options options = parse(args, envDatabase, userHome);

    // Refuse a database that is not there rather than creating an empty one and counting nothing:
    // both sqlite constructors create the file and its schema if absent, which is right for a
    // server starting fresh and wrong for a tool whose whole job is to read. ExportCli, RatingsCli
    // and RetractCli check the same thing for the same reason.
    if (!Files.exists(options.database())) {
      throw new IllegalArgumentException(
          "no segue database at " + options.database() + " — nothing to count");
    }

    log.info("counting {}", options.database());
  }
}
```

  **Omit `import com.robsartin.segue.support.RequiredDatabase;` from this step** — the stub does not call it and `removeUnusedImports` would strip it. Step 6 adds the import, the call and the `census --> support` diagram edge together. Everything else above is final.

- [ ] **Step 2 — register the task and name the package.** In `build.gradle.kts`, after the `ownClaim` block:

```kotlin
tasks.register<JavaExec>("graphCensus") {
    group = "application"
    description =
        "Counts the graph and prints the counts: nodes by kind, edges by type, source and " +
            "corroboration, the claim rows and what retraction and merge did to them, the taste " +
            "layer by score, degree quantiles against ADR 57's floor, and what MusicBrainz " +
            "reached. Aggregates only — no labels, no ids, no notes — so the output is safe to " +
            "paste. Reads only; needs no network. See ADR 63. --db is required, and SEGUE_DB " +
            "does not satisfy it. Write \$HOME and not ~ — a tilde does not expand inside " +
            "double quotes. Example: ./gradlew graphCensus --args=\"--db \$HOME/.segue/segue.db\""
    mainClass.set("com.robsartin.segue.census.CensusCli")
    classpath = sourceSets["main"].runtimeClasspath
    // sqlite-jdbc loads a native library, the same grant tasks.test makes.
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    // The whole graph is folded in memory, and a real log is six figures of assertions.
    maxHeapSize = "4g"
    // Never up-to-date: the graph changes under it, and the point is to count it now.
    outputs.upToDateWhen { false }
}
```

  In `ArchitectureTest`, add `"census"` to `DEV_TOOL_PACKAGES` (alphabetical, first):

```java
  static final List<String> DEV_TOOL_PACKAGES =
      List.of("census", "export", "own", "rate", "ratings", "recommend", "retract", "seed");
```

- [ ] **Step 3 — the guide's derived sets, in this same commit.** In `docs/developer-guide.md`:
  - **Contents**, after the "Looking at what you have rated" entry: `- [Looking at the shape of your graph](#looking-at-the-shape-of-your-graph)`. **Do not add this yet** — the anchor does not exist until Task 10 and `DocumentationLinksTest` resolves headings. Task 10 adds both together.
  - **Layering diagram**, after the `own[...]` node: `  census["census<br/>CensusCli, CensusRun, Census, CensusReport"]`
  - **Layering diagram edges**, after the `own -->` block: `  census --> support`
  - **The dev-tool sentence**: change `` `seed`, `export`, `ratings`, `retract`, `recommend`, `rate` and `own` are the seven dev-side tools`` to `` `seed`, `export`, `ratings`, `retract`, `recommend`, `rate`, `own` and `census` are the eight dev-side tools``.
  - **The bullet list of tools**, after the `own` bullet:

```markdown
- **`census` reaches `sqlite`, `support`, `export` and `wikidata`, and is the second tool whose
  whole output is aggregates.** It folds the log through `export.LogProjection` rather than folding
  it again — a third fold of one log is the drift `BothFoldsAgreeTest` exists to catch — and counts
  what comes out. It writes nothing, and `--db` is required.
```

  **`DocumentationLinksTest` resolves every relative link, and ADR 63 does not exist until Task 10.** So write this bullet ending "`--db` is required." with no link, and Task 10 adds the citation.
  - **`### What each package is for`**, a new row after the `rate` row:

```markdown
| `census` | The graph census: nodes by kind, edges by type, source and corroboration, the claim rows and what retraction and merge did to them, the taste layer by score, degree quantiles against `Recommendations.MIN_CANDIDATE_DEGREE`, and what MusicBrainz reached. Run as `./gradlew graphCensus`. Plain Java, read-only, offline, and the whole output is aggregates — no label, no id, no note — so it is safe to paste. `--db` is required, and `SEGUE_DB` does not satisfy it. | `port`, `domain`, `sqlite`, `support`, `export`, `wikidata` |
```

  The "Depends on" column is prose and is not derived; write the final list now so it does not need revisiting.
  - **The `rate` bullet** says "`recommend` itself, the one dependency between two dev tools". That stops being true in Task 3. Change it there, not here.

- [ ] **Step 4 — RED: the tool refuses without `--db`.** Create `CensusCliTest.java`:

```java
package com.robsartin.segue.census;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The refusal, and the two ways round it that must not work — the shape {@code RetractCliTest} and
 * {@code OwnCliTest} take for ADR 60's two claim tools, applied here for ADR 63's reason instead.
 *
 * <p>Each test also asserts that <b>no database was created under the test's own home</b>: a
 * refusal that opened one first would fail twice, which is what pins the refusal ahead of {@code
 * Files.exists}.
 */
class CensusCliTest {

  @TempDir private Path home;

  @Test
  @DisplayName("the census refuses to run when --db does not name a database")
  void shouldRefuseWhenTheDatabaseIsNotNamed() {
    assertThatThrownBy(() -> CensusCli.parse(new String[] {}, null, home.toString()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("--db is required")
        .hasMessageContaining(home.resolve(".segue").resolve("segue.db").toString());

    assertThat(home.resolve(".segue")).doesNotExist();
  }

  @Test
  @DisplayName("SEGUE_DB does not satisfy --db, and is quoted back in the refusal")
  void shouldRefuseWhenOnlySegueDbNamesADatabase() {
    Path inherited = home.resolve("inherited.db");

    assertThatThrownBy(
            () -> CensusCli.parse(new String[] {}, inherited.toString(), home.toString()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("--db is required")
        .hasMessageContaining(inherited.toString())
        .hasMessageContaining("SEGUE_DB is inherited");

    assertThat(inherited).doesNotExist();
  }

  @Test
  @DisplayName("the named database is what the options carry, whatever SEGUE_DB says")
  void shouldTakeTheFlagWhenBothTheFlagAndSegueDbNameADatabase() throws Exception {
    Path named = Files.createFile(home.resolve("named.db"));

    CensusCli.Options options =
        CensusCli.parse(
            new String[] {"--db", named.toString()},
            home.resolve("inherited.db").toString(),
            home.toString());

    assertThat(options.database()).isEqualTo(named);
  }
}
```

- [ ] **Step 5 — run it and read the failure.** `./gradlew test --tests '*CensusCliTest*'`. Expect two assertion failures (not compile errors): the first two tests fail on `Expecting code to raise a throwable` — `parse` returns `Options[database=unset]` instead of throwing. Quote the exact text in the report. The third test passes already, which is correct: it is the control that the flag itself was never broken.

- [ ] **Step 6 — GREEN.** In `CensusCli`, add `import com.robsartin.segue.support.RequiredDatabase;` and replace the closing line of `parse`:

```java
    if (database == null) {
      throw usage(RequiredDatabase.refusal(envDatabase, userHome));
    }
    return new Options(database);
```

  Add the `census --> support` edge to the guide's layering diagram now (see Step 3).

- [ ] **Step 7 — verify.** `./gradlew test --tests '*CensusCliTest*'` — three green. Then `./gradlew spotlessApply` and the full gate. `PackageListsTest` and `DeveloperGuideEnumerationsTest` must both be green: they are what says the package, the task, the constant and the guide agree.

- [ ] **Step 8 — commit.** `git add` by explicit path: `build.gradle.kts`, `src/main/java/com/robsartin/segue/census/CensusCli.java`, `src/test/java/com/robsartin/segue/census/CensusCliTest.java`, `src/test/java/com/robsartin/segue/arch/ArchitectureTest.java`, `docs/developer-guide.md`. Message: `A sixth dev tool, and it will not run without --db (#227)`.

---

### Task 2: The four fences, each seen fire

**Files:** Modify `src/test/java/com/robsartin/segue/arch/ArchitectureTest.java`, `docs/developer-guide.md`.

All four rules are vacuously green on today's `census`, which is exactly why each needs a planted violation before it is trusted.

- [ ] **Step 1 — the two behaviour fences.** Add to `ArchitectureTest`, after `theExporterNeverSpeaksToANetwork`:

```java
  /**
   * ADR 63: the census reads, and it cannot write either layer.
   *
   * <p>The sixth dev-side tool, and its fence is the exporter's with one clause moved: {@code
   * AffinityStore.put} and {@code updateRating} are named here, as they are for {@code ratings} and
   * {@code recommend}, because this tool holds the whole score map and affinity is the one part of
   * segue that cannot be regenerated from a source.
   *
   * <p><b>{@code export} is the one sibling this tool may reach, and that is a decision rather than
   * an oversight.</b> Four of the six sections are counts over the fold, and there are two ways to
   * have a fold: read {@code LogProjection}, or write a third one. {@code BothFoldsAgreeTest}
   * exists because two folds of one log drifted, and {@code Equivalences.foldEndpoints} and {@code
   * Retractions.survives} were both moved into {@code domain} to stop it recurring — so a census
   * that disagreed with the export about how many nodes there are would be exactly the defect this
   * repository has spent three issues preventing. The borrowed fence is bounded the way {@code rate
   * → recommend} is bounded (ADR 46): {@link #theExporterOnlyReads} makes {@code export}
   * read-only, so nothing reachable through it can write.
   */
  @ArchTest
  static final ArchRule theCensusOnlyReads =
      noClasses()
          .that()
          .resideInAPackage("..census..")
          .should(
              ArchConditions.accessTargetWhere(
                      APPLIES_A_CLAIM
                          .or(callTo("put", AffinityStore.class))
                          .or(callTo("updateRating", AffinityStore.class)))
                  .or(
                      ArchConditions.dependOnClassesThat(
                          JavaClass.Predicates.equivalentTo(IngestService.class)))
                  .or(
                      ArchConditions.dependOnClassesThat(
                          JavaClass.Predicates.resideInAnyPackage(
                              otherDevToolsAnd(List.of("census", "export"))))))
          .because(
              "ADR 63: counting is a read — the census never appends to the log, never writes the"
                  + " graph, never writes a rating, and reaches exactly one sibling, export, so"
                  + " that there is one fold of the log rather than two");

  /**
   * ADR 63: the census opens the two stores in one file, folds the log, and reaches nothing else.
   *
   * <p>No traversal, so no {@code tinker} and no {@code jena}; no replay, so no {@code ingest};
   * nothing to serve, so no {@code mcp} and no {@code app}. {@code wikidata} is deliberately NOT
   * banned, for the reason {@link #theExporterNeverSpeaksToANetwork} gives: {@code
   * KindMapper.rederive} is a static table and no more a network call than {@code ClassLabels} is,
   * and both {@code LogProjection} and {@code Equivalences.standIns} are driven by it. {@code
   * musicbrainz} IS banned as a package, exactly as it is for the exporter — the census reads the
   * source id {@code "musicbrainz"} as text off the log, which is the only thing the log holds, and
   * importing the adapter would buy it nothing but a second HTTP client.
   *
   * <p>{@link #REACHES_A_NETWORK} is the clause that names no client, and it is here for issue
   * #139's reason: a census is a pure function of one local file, and the entity a count is short
   * of is exactly the row that makes fetching one look like an improvement.
   */
  @ArchTest
  static final ArchRule theCensusOpensNothingElse =
      noClasses()
          .that()
          .resideInAPackage("..census..")
          .should()
          .dependOnClassesThat(
              JavaClass.Predicates.resideInAnyPackage(
                      "..tinker..", "..jena..", "..ingest..", "..mcp..", "..app..", "..musicbrainz..")
                  .or(ON_A_NETWORK_API)
                  .or(REACHES_A_NETWORK))
          .because(
              "ADR 63: the census folds the log and counts what comes out — it needs no engine, no"
                  + " replay and no network, and cannot become an MCP tool by accident");
```

- [ ] **Step 2 — positive controls for both.** One at a time, plant, run `./gradlew test --tests '*ArchitectureTest*'`, quote the violation, revert:
  1. Add `private static final com.robsartin.segue.port.AffinityStore LEAK = null;` plus a call `LEAK.put(null)` inside a private method of `CensusCli` → `theCensusOnlyReads` fires.
  2. Add `import com.robsartin.segue.ratings.RatingsRun;` and a field of that type to `CensusCli` → `theCensusOnlyReads` fires on the sibling clause.
  3. Add `import com.robsartin.segue.export.LogProjection;` and a field of that type to `CensusCli` → **both rules stay green.** This is the negative control for the carve-out: without it, "export is permitted" is a claim nothing has tested. Note it in the report.
  4. Add `import com.robsartin.segue.tinker.TinkerGraphStore;` and a field → `theCensusOpensNothingElse` fires.
  5. Add `import com.robsartin.segue.wikidata.WikidataClient;` and a field → `theCensusOpensNothingElse` fires on `REACHES_A_NETWORK`, **not** on the package list, which is what says the carve-out for `wikidata` is a carve-out for `KindMapper` and not for the client beside it.

- [ ] **Step 3 — the two database fences.** Add after `theClaimToolsTakeTheirDatabaseFromTheFlagAlone`:

```java
  /**
   * ADR 63: the census has no default database either, and it is fenced separately from ADR 60's
   * two claim tools.
   *
   * <p><b>Why a third rule rather than a wider one.</b> {@link #theClaimToolsHaveNoDefaultDatabase}
   * and {@link #theClaimToolsTakeTheirDatabaseFromTheFlagAlone} are named for the tools that append
   * a first-person claim, ADR 60 names both rules in its text and is immutable, and its
   * consequences say in as many words that a third tool would have to be added by hand. Widening
   * them would make two rule names describe something that is not a claim tool.
   *
   * <p><b>Why the census requires the flag at all, when nothing here writes.</b> ADR 60's central
   * clause rather than its consequence: an agent's shell is initialised from the owner's profile
   * and inherits {@code SEGUE_DB}, and this tool's output is the shape of the owner's whole graph
   * and taste layer. Aggregates are publishable (ADR 51); whether to publish them is the owner's
   * decision per invocation.
   */
  @ArchTest
  static final ArchRule theCensusHasNoDefaultDatabase =
      noClasses()
          .that()
          .resideInAPackage("..census..")
          .should()
          .dependOnClassesThat(JavaClass.Predicates.equivalentTo(DefaultDatabase.class))
          .because(
              "ADR 63: the census names its database on the command line — SEGUE_DB is inherited by"
                  + " any shell started from the owner's profile, so it cannot stand in for a flag"
                  + " typed per invocation");

  /**
   * The sibling of {@link #theCensusHasNoDefaultDatabase}, and the reason ADR 60 gives for having
   * two: the first forbids a <em>name</em> and the second forbids the <em>capability</em>. {@code
   * census} depends on {@code support.RequiredDatabase} for the refusal sentence, and that class
   * calls {@code DefaultDatabase} itself — so a {@code Path}-returning method added there and wired
   * in restores the default while the rule above stays green. Planted exactly that way for ADR 60,
   * measured green; the same hole is the same hole here.
   */
  @ArchTest
  static final ArchRule theCensusTakesItsDatabaseFromTheFlagAlone =
      noClasses()
          .that()
          .resideInAPackage("..census..")
          .should(ArchConditions.accessTargetWhere(A_PATH_TAKEN_OUT_OF_SUPPORT))
          .because(
              "ADR 63, on ADR 60's measurement: a fence that forbids a class name stops only the"
                  + " lazy version — what has to be unavailable is any route from support to a"
                  + " java.nio.file.Path");
```

  `A_PATH_TAKEN_OUT_OF_SUPPORT` is the predicate `theClaimToolsTakeTheirDatabaseFromTheFlagAlone` already uses, and `DefaultDatabase` is already imported for the rule above it — **reuse both, do not restate either.** A second predicate saying the same thing is the second copy of a rule this repository keeps finding. Both new rules are declared **after** the two claim-tool rules, so the static fields they read are initialised first.

- [ ] **Step 4 — positive controls for both.** Plant, run, quote, revert:
  1. `import com.robsartin.segue.support.DefaultDatabase;` and a call to `DefaultDatabase.resolve(null, null, "/x")` in `CensusCli.parse` → `theCensusHasNoDefaultDatabase` fires.
  2. Add `static Path defaultPath(String env, String home) { return DefaultDatabase.resolve(null, env, home); }` to `support.RequiredDatabase` and call it from `CensusCli` → **`theCensusHasNoDefaultDatabase` stays green and `theCensusTakesItsDatabaseFromTheFlagAlone` fires.** That divergence is the whole point of the second rule; quote both results.

- [ ] **Step 5 — the guide's rule table, same commit.** Add four rows to `### Which rules a machine enforces`, after the `theExporterNeverSpeaksToANetwork` row for the first two and after `theClaimToolsTakeTheirDatabaseFromTheFlagAlone` for the second two:

```markdown
| `theCensusOnlyReads` | `census` calling the three world-fact writes or either taste-layer write (`AffinityStore.put`, `updateRating`), depending on `IngestService`, or depending on any dev tool but `export`. `export` is permitted deliberately: the census counts `LogProjection`'s fold rather than writing a third one, and a third fold of one log is the drift `BothFoldsAgreeTest` exists to catch | ADR 63 |
| `theCensusOpensNothingElse` | `census` depending on `tinker`, `jena`, `ingest`, `mcp`, `app`, the whole `musicbrainz` package, `java.net`, `javax.net`, or any class of this project's that reaches a network. `wikidata` is not banned, for the exporter's reason — `KindMapper.rederive` is a static table | ADR 63 |
| `theCensusHasNoDefaultDatabase` | `census` depending on `support.DefaultDatabase` at all. A third rule rather than a wider one: ADR 60's two are named for claim tools, ADR 60 names both and is immutable, and its consequences say a third tool joins by hand | ADR 63, [ADR 60](adr/0060-the-claim-tools-require-an-explicit-database.md) |
| `theCensusTakesItsDatabaseFromTheFlagAlone` | `census` calling any `support` method that returns a `java.nio.file.Path`, or reading any `support` field of that type — the capability, where the rule above forbids the name | ADR 63, [ADR 60](adr/0060-the-claim-tools-require-an-explicit-database.md) |
```

  Write `ADR 63` as plain text in these four rows for now; Task 10 turns it into a link once the file exists.

- [ ] **Step 6 — verify and commit.** `./gradlew spotlessApply` then the full gate. `DeveloperGuideEnumerationsTest.shouldNameEveryArchUnitRuleWhenTheGuideTabulatesThem` is what says the table and the rules agree. Commit `src/test/java/com/robsartin/segue/arch/ArchitectureTest.java` and `docs/developer-guide.md`: `Four fences for the census, each seen fire (#227)`.

---

### Task 3: The fixture, and the node and edge counts

**Files:** Create `src/test/java/com/robsartin/segue/census/InventedCensus.java`, `src/main/java/com/robsartin/segue/census/Degrees.java`, `src/main/java/com/robsartin/segue/census/NodeCensus.java`, `src/main/java/com/robsartin/segue/census/EdgeCensus.java`, `src/test/java/com/robsartin/segue/census/NodeCensusTest.java`, `src/test/java/com/robsartin/segue/census/EdgeCensusTest.java`. Modify `docs/developer-guide.md`.

New import edges introduced: `census --> port`, `census --> domain`, `census --> export`.

- [ ] **Step 1 — the fixture.** Create `InventedCensus.java`. Every id is unallocatable; every label and note is invented. The log below is the one every count in Tasks 3 to 7 is hand-counted against, so **do not change it without recounting every expectation**.

```java
package com.robsartin.segue.census;

import com.robsartin.segue.domain.AssertionRecord;
import com.robsartin.segue.domain.LocalEntity;
import com.robsartin.segue.domain.LoggedAssertion;
import com.robsartin.segue.domain.NodeAssertion;
import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.domain.OwnerEdge;
import com.robsartin.segue.domain.Provenance;
import com.robsartin.segue.domain.Retraction;
import com.robsartin.segue.domain.SameAs;
import com.robsartin.segue.port.AffinityStore;
import com.robsartin.segue.port.AssertionLog;
import com.robsartin.segue.domain.AffinityRecord;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * One invented log, small enough to count by hand, and the two fake stores the census reads.
 *
 * <p><b>Every value here is made up.</b> ADR 40 and issue #37 are explicit that this repository is
 * public and the personal data lives outside it. The ids take shapes Wikibase's grammar refuses — a
 * leading zero for a stand-in (ADR 58), two for one the owner minted (ADR 59), eleven digits for a
 * merge's canonical side (ADR 62) — so none of them denotes anything, ever.
 *
 * <p><b>It is not {@code export.InventedGraph} widened, and that is deliberate.</b> That class is
 * package-private in {@code export}, as {@code ratings.InventedRatings} is in {@code ratings} and
 * {@code recommend.InventedWorld} is in {@code recommend}: one invented fixture per package is this
 * repository's pattern. Reaching across for one would be the dependency direction the sibling
 * fences forbid, arriving through the test tree.
 *
 * <p><b>The log is designed so that no count is trivially right.</b> Two sources agree on one edge
 * and one source is a model, so corroboration has three buckets; one entity is retracted after it
 * has both a node claim and an edge; one edge names an endpoint nothing ever claims, so the
 * dangling count is not zero; one local id is merged twice with an owner edge naming the first
 * canonical id in between and one is merged twice with nothing naming it, so standing, superseded
 * and superseded-but-edge-referenced are each non-empty and different; one node's degree is exactly
 * ADR 57's floor and one is above it, so "at or below the floor" cannot pass with {@code &lt;}.
 */
final class InventedCensus {

  /** A source-claimed person, and the busiest node in the fixture. */
  static final String WREN = "Q0900201";

  /** A source-claimed group. */
  static final String HOLLOW = "Q0900202";

  /** A source-claimed work, and the node whose degree is exactly ADR 57's floor. */
  static final String PRIZE = "Q0900203";

  /** A source-claimed work, retracted at the end of the log along with the edge that names it. */
  static final String GONE = "Q0900204";

  /** The one entity carrying classes, and one of the two MusicBrainz reached. */
  static final String NEIGHBOUR = "Q0900205";

  /** Named as an edge endpoint and never claimed as a node — the fixture's one dangling edge. */
  static final String UNCLAIMED = "Q0900206";

  static final String THIRD = "Q0900207";
  static final String FOURTH = "Q0900208";

  /**
   * A class no whitelist knows, so a claim stating it re-derives to {@code CONCEPT} — {@code
   * KindMapper.rederive}'s "when classes ARE stated, this list is the authority, including when it
   * answers CONCEPT" (ADR 42). Using a real class id would need an entry in {@code
   * StandInQidsDenoteNothingTest}'s allowlist for no gain: what the bridge count asks is whether
   * classes are there at all.
   */
  static final String UNKNOWN_CLASS = "Q0900301";

  /** Minted, merged onto {@link #FIRST_CANONICAL} and then corrected onto {@link #CORRECTED}. */
  static final String LEDGER = "Q0021";

  /** Minted and merged once, so its stand-in carries the edges. */
  static final String SKETCH = "Q0022";

  /** Minted, merged twice, and nothing ever names the first canonical id it was merged onto. */
  static final String DOUBLE = "Q0023";

  /** Superseded, and kept alive by an owner edge claimed against it while it stood (#221). */
  static final String FIRST_CANONICAL = "Q10000900201";

  /** What {@link #LEDGER} is corrected onto. */
  static final String CORRECTED = "Q10000900202";

  /** {@link #SKETCH}'s canonical side. */
  static final String SETTLED = "Q10000900203";

  /** Superseded with nothing naming it, so it gets no stand-in at all. */
  static final String ABANDONED = "Q10000900204";

  /** What {@link #DOUBLE} is corrected onto, and the one stand-in that ends with no edge. */
  static final String REROUTED = "Q10000900205";

  static final String WREN_LABEL = "Wren Alderman";
  static final String LEDGER_LABEL = "A Ledger Nobody Printed";
  static final String NEIGHBOUR_NOTE = "an invented note naming Q0900205, unlike anything real";

  private static final Instant WHEN = Instant.parse("2026-01-01T00:00:00Z");

  private InventedCensus() {}

  static Provenance sourced() {
    return new Provenance("invented", "invented:1", WHEN, 1.0);
  }

  static Provenance secondSource() {
    return new Provenance("also-invented", "also-invented:1", WHEN, 0.8);
  }

  static Provenance guessed() {
    return new Provenance("llm:invented", "turn-1", WHEN, 0.3);
  }

  /**
   * The source id a MusicBrainz edge claim carries. A literal, because the log holds text and the
   * adapter's own constant is private to a package {@code census} may not import.
   */
  static Provenance fromMusicBrainz() {
    return new Provenance("musicbrainz", "artist/invented#member of band:invented", WHEN, 0.8);
  }

  static NodeAssertion node(String qid, NodeKind kind, String label) {
    return new NodeAssertion(qid, kind, label, sourced());
  }

  static NodeAssertion node(String qid, NodeKind kind, String label, List<String> instanceOf) {
    return new NodeAssertion(qid, kind, label, instanceOf, sourced());
  }

  static LocalEntity minted(String qid, String label) {
    return LocalEntity.minted(qid, NodeKind.WORK, label, WHEN);
  }

  static OwnerEdge owned(String from, String to) {
    return OwnerEdge.claimed(from, to, "INFLUENCED_BY", WHEN);
  }

  static SameAs merged(String localQid, String canonicalQid) {
    return SameAs.declared(localQid, canonicalQid, WHEN);
  }

  static AssertionRecord edge(String from, String to, String type, Provenance provenance) {
    return new AssertionRecord(from, to, type, null, null, provenance);
  }

  static Retraction retract(String qid) {
    return new Retraction(qid, "an invented reason, unlike anything a real one would say", WHEN);
  }

  /**
   * The fixture log, thirty rows in this exact order. Row numbers are cited by every hand-counted
   * expectation in this package, so an insertion renumbers them all.
   */
  static List<LoggedAssertion> log() {
    return List.of(
        node(WREN, NodeKind.PERSON, WREN_LABEL),
        node(HOLLOW, NodeKind.GROUP, "The Hollow Tide"),
        node(PRIZE, NodeKind.WORK, "A Placeholder Prize"),
        node(GONE, NodeKind.WORK, "A Thing Taken Back"),
        node(NEIGHBOUR, NodeKind.PERSON, "A Neighbour", List.of(UNKNOWN_CLASS)),
        node(THIRD, NodeKind.PERSON, "A Third Invented Person"),
        node(FOURTH, NodeKind.PERSON, "A Fourth Invented Person"),
        edge(WREN, HOLLOW, "MEMBER_OF", sourced()),
        edge(WREN, HOLLOW, "MEMBER_OF", secondSource()),
        edge(NEIGHBOUR, HOLLOW, "MEMBER_OF", sourced()),
        edge(WREN, PRIZE, "INFLUENCED_BY", guessed()),
        edge(GONE, WREN, "MEMBER_OF", sourced()),
        edge(WREN, NEIGHBOUR, "MEMBER_OF", fromMusicBrainz()),
        edge(WREN, THIRD, "MEMBER_OF", sourced()),
        edge(WREN, FOURTH, "MEMBER_OF", sourced()),
        edge(THIRD, PRIZE, "INFLUENCED_BY", sourced()),
        edge(FOURTH, PRIZE, "INFLUENCED_BY", sourced()),
        edge(WREN, UNCLAIMED, "MEMBER_OF", sourced()),
        minted(LEDGER, LEDGER_LABEL),
        minted(SKETCH, "A Sketch Nobody Kept"),
        minted(DOUBLE, "A Thing Minted Twice"),
        owned(LEDGER, WREN),
        merged(LEDGER, FIRST_CANONICAL),
        owned(FIRST_CANONICAL, PRIZE),
        merged(LEDGER, CORRECTED),
        merged(SKETCH, SETTLED),
        owned(SKETCH, PRIZE),
        merged(DOUBLE, ABANDONED),
        merged(DOUBLE, REROUTED),
        retract(GONE));
  }

  /** A log that answers {@code readAll} from a list. */
  static final class FakeAssertionLog implements AssertionLog {

    private final List<LoggedAssertion> assertions = new ArrayList<>();

    FakeAssertionLog with(List<LoggedAssertion> claims) {
      assertions.addAll(claims);
      return this;
    }

    FakeAssertionLog with(LoggedAssertion... claims) {
      assertions.addAll(List.of(claims));
      return this;
    }

    @Override
    public void append(LoggedAssertion assertion) {
      throw new UnsupportedOperationException("the census never writes");
    }

    @Override
    public List<LoggedAssertion> readAll() {
      return List.copyOf(assertions);
    }

    @Override
    public void close() {}
  }

  /**
   * A taste store that answers the note-free bulk read and nothing else.
   *
   * <p>{@code readAll} and {@code find} throw, deliberately: the census reads scores through {@code
   * readRatings}, whose {@code Map<String, Integer>} has nowhere to put a note, and a fake that
   * answered the note-carrying reads would let this tool quietly start using one without failing
   * anything. That is {@code InventedRatings.FakeAffinityStore}'s discipline, inverted.
   */
  static final class FakeAffinityStore implements AffinityStore {

    private final Map<String, Integer> ratings = new LinkedHashMap<>();

    FakeAffinityStore rated(String qid, int rating) {
      ratings.put(qid, rating);
      return this;
    }

    @Override
    public void put(AffinityRecord affinity) {
      throw new UnsupportedOperationException("the census never writes");
    }

    @Override
    public void updateRating(String qid, int rating, Instant updatedAt) {
      throw new UnsupportedOperationException("the census never writes");
    }

    @Override
    public Optional<AffinityRecord> find(String qid) {
      throw new UnsupportedOperationException("the census reads scores, never a row with a note");
    }

    @Override
    public List<AffinityRecord> readAll() {
      throw new UnsupportedOperationException("the census reads scores, never a row with a note");
    }

    @Override
    public Map<String, Integer> readRatings() {
      return Map.copyOf(ratings);
    }

    @Override
    public void close() {}
  }
}
```

- [ ] **Step 2 — `Degrees`, the one home for incidence.** Create `Degrees.java`:

```java
package com.robsartin.segue.census;

import com.robsartin.segue.domain.EdgeRecord;
import com.robsartin.segue.export.LogProjection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * How many folded edges name each node — one home, because two sections read it.
 *
 * <p>{@link DegreeCensus} reports the quantiles and {@link ClaimCensus} asks which stand-ins ended
 * with no edge, and a second incidence count would be free to disagree with the first about what a
 * degree is.
 *
 * <p><b>Every node is in the map, isolated ones at zero.</b> "At or below the floor" is meaningless
 * against a denominator that has already dropped what nothing reaches.
 *
 * <p><b>A self-loop counts twice</b>, once at each end, which is what "how many edges name this
 * node" means. {@code Equivalences.foldEndpoints} drops the self-loop a merge would create, so one
 * here is a self-loop the log itself holds — and that record's Javadoc says such a claim is left
 * exactly where it is.
 */
final class Degrees {

  private Degrees() {}

  static Map<String, Integer> in(LogProjection projection) {
    Objects.requireNonNull(projection, "projection");
    Map<String, Integer> degrees = new LinkedHashMap<>();
    for (String qid : projection.nodes().keySet()) {
      degrees.put(qid, 0);
    }
    for (EdgeRecord edge : projection.edges()) {
      // computeIfPresent, not merge: an endpoint outside the node set is a dangling edge, which
      // LogProjection has already excluded from edges() and counted separately.
      degrees.computeIfPresent(edge.fromQid(), (qid, degree) -> degree + 1);
      degrees.computeIfPresent(edge.toQid(), (qid, degree) -> degree + 1);
    }
    return Collections.unmodifiableMap(degrees);
  }
}
```

- [ ] **Step 3 — the two stubs that compile and answer wrongly.** Create `NodeCensus.java` and `EdgeCensus.java` with the final record components and an `of` that returns all zeros and empty maps. Keep the Javadoc; only the body is a stub.

```java
package com.robsartin.segue.census;

import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.domain.NodeRecord;
import com.robsartin.segue.export.LogProjection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * What the fold holds, by kind.
 *
 * <p><b>All six kinds are emitted, zeros included</b>, so a kind that has gone to zero is visible
 * as a zero rather than as a missing row somebody has to notice.
 *
 * <p>The map is an {@code EnumMap} kept in declaration order rather than a {@code Map.copyOf}: that
 * factory's iteration order is unspecified and salted per JVM, so two runs over one unchanged log
 * would print two orders and a diff between them would be noise. {@code LogProjection} makes the
 * same choice for the same reason (issue #207), and ADR 43's byte-identical contract is what both
 * serve.
 */
public record NodeCensus(Map<NodeKind, Integer> byKind, int total) {

  public NodeCensus {
    Objects.requireNonNull(byKind, "byKind");
    // new EnumMap<>(map) refuses an empty map it cannot infer the key type from; the class
    // constructor plus putAll takes one, and no caller has to know that.
    Map<NodeKind, Integer> copy = new EnumMap<>(NodeKind.class);
    copy.putAll(byKind);
    byKind = Collections.unmodifiableMap(copy);
  }

  public static NodeCensus of(LogProjection projection) {
    Objects.requireNonNull(projection, "projection");
    Map<NodeKind, Integer> byKind = new EnumMap<>(NodeKind.class);
    for (NodeKind kind : NodeKind.values()) {
      byKind.put(kind, 0);
    }
    return new NodeCensus(byKind, 0);
  }
}
```

```java
package com.robsartin.segue.census;

import com.robsartin.segue.domain.EdgeRecord;
import com.robsartin.segue.domain.Provenance;
import com.robsartin.segue.export.LogProjection;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * What the fold holds, by type, by who said it, and by how many said it.
 *
 * <p><b>{@code bySource} does not sum to {@code total}, and that is not a defect.</b> An edge two
 * sources assert is counted under both, which is the collapse that makes corroboration countable at
 * all (ADR 19). The report's row label says "backed by" for exactly this reason.
 *
 * <p><b>The type codes and source ids are raw text off the log</b>, not a vocabulary this class
 * knows. A row the current {@code EdgeTypes} no longer registers still appears, because ADR 19
 * forbids deleting the claim that carries it, and a census that silently dropped it would be
 * answering a different question. {@code CensusIsSafeToPasteTest}'s "no Q-shaped token anywhere"
 * assertion is what covers the one hazard that comes with printing stored text.
 *
 * <p>Sorted maps rather than {@code Map.copyOf}, for {@link NodeCensus}'s reason.
 */
public record EdgeCensus(
    Map<String, Integer> byType,
    Map<String, Integer> bySource,
    Map<Integer, Integer> byCorroboration,
    int total,
    int dangling) {

  public EdgeCensus {
    byType = Collections.unmodifiableMap(new TreeMap<>(Objects.requireNonNull(byType, "byType")));
    bySource =
        Collections.unmodifiableMap(new TreeMap<>(Objects.requireNonNull(bySource, "bySource")));
    byCorroboration =
        Collections.unmodifiableMap(
            new TreeMap<>(Objects.requireNonNull(byCorroboration, "byCorroboration")));
  }

  public static EdgeCensus of(LogProjection projection) {
    Objects.requireNonNull(projection, "projection");
    return new EdgeCensus(new TreeMap<>(), new TreeMap<>(), new TreeMap<>(), 0, 0);
  }
}
```

- [ ] **Step 4 — RED: the node counts.** Create `NodeCensusTest.java`:

```java
package com.robsartin.segue.census;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.export.LogProjection;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Counted by hand off {@code InventedCensus.log()}.
 *
 * <p>The fold holds thirteen nodes: seven the sources claimed, minus the one row 29 retracts, plus
 * the three the owner minted, plus a stand-in for each of the four merges that stand — the merge at
 * row 27 is superseded with nothing naming its canonical id, so it names no stand-in at all.
 *
 * <p>{@code NEIGHBOUR} is claimed {@code PERSON} and states a class no whitelist knows, so both
 * folds re-derive it to {@code CONCEPT} (ADR 42). That is the one kind in this fixture that is not
 * the kind its claim states, and it is here on purpose: a census that read the claim rather than
 * the fold would report {@code PERSON} and disagree with the exported picture.
 */
class NodeCensusTest {

  private static final LogProjection PROJECTION =
      LogProjection.of(new InventedCensus.FakeAssertionLog().with(InventedCensus.log()));

  @Test
  @DisplayName("the total is every node the fold holds, stand-ins and minted ids included")
  void shouldCountEveryNodeInTheFoldWhenTheLogIsCounted() {
    assertThat(NodeCensus.of(PROJECTION).total()).isEqualTo(13);
  }

  @Test
  @DisplayName("every kind gets a count, including the ones no node has")
  void shouldCountEveryKindWhenSomeKindsAreEmpty() {
    assertThat(NodeCensus.of(PROJECTION).byKind())
        .containsExactly(
            entry(NodeKind.PERSON, 3),
            entry(NodeKind.GROUP, 1),
            entry(NodeKind.WORK, 8),
            entry(NodeKind.PLACE, 0),
            entry(NodeKind.EVENT, 0),
            entry(NodeKind.CONCEPT, 1));
  }
}
```

  (`containsExactly` on a map asserts entry order as well as content, which is what pins the enum ordering.)

- [ ] **Step 5 — run it and read the failure.** `./gradlew test --tests '*NodeCensusTest*'`. Expect `expected: 13 but was: 0` and a `containsExactly` mismatch listing six zeros. Quote both.

- [ ] **Step 6 — GREEN.** Replace `NodeCensus.of`'s body's stub return:

```java
    for (NodeRecord node : projection.nodes().values()) {
      byKind.merge(node.kind(), 1, Integer::sum);
    }
    return new NodeCensus(byKind, projection.nodes().size());
```

- [ ] **Step 7 — RED: the edge counts.** Create `EdgeCensusTest.java`:

```java
package com.robsartin.segue.census;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import com.robsartin.segue.export.LogProjection;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Counted by hand off {@code InventedCensus.log()}.
 *
 * <p>Eleven folded edges. Rows 7 and 8 are one claim from two sources and collapse into one edge
 * with two provenances; row 11 does not survive the retraction at row 29; row 17 names an endpoint
 * nothing claims and is the fixture's one dangling edge; the three owner edges fold onto the
 * canonical ids their local sides were merged onto, except the one claimed against a canonical id
 * directly.
 */
class EdgeCensusTest {

  private static final EdgeCensus CENSUS =
      EdgeCensus.of(
          LogProjection.of(new InventedCensus.FakeAssertionLog().with(InventedCensus.log())));

  @Test
  @DisplayName("the total is every folded edge, and the dangling one is not among them")
  void shouldCountTheFoldedEdgesWhenOneNamesAnUnclaimedEndpoint() {
    assertThat(CENSUS.total()).isEqualTo(11);
    assertThat(CENSUS.dangling()).isEqualTo(1);
  }

  @Test
  @DisplayName("edges are counted by the type code the log holds")
  void shouldCountByTypeWhenTheFoldHoldsTwoTypes() {
    assertThat(CENSUS.byType()).containsExactly(entry("INFLUENCED_BY", 6), entry("MEMBER_OF", 5));
  }

  @Test
  @DisplayName("an edge two sources assert is counted under both")
  void shouldCountAnEdgeUnderEverySourceWhenTwoSourcesAssertIt() {
    assertThat(CENSUS.bySource())
        .containsExactly(
            entry("also-invented", 1),
            entry("invented", 6),
            entry("llm:invented", 1),
            entry("musicbrainz", 1),
            entry("owner", 3));
  }

  @Test
  @DisplayName("an owner-only edge corroborates zero, and is counted rather than dropped")
  void shouldCountTheOwnerOnlyEdgesAtZeroWhenCorroborationIsDistributed() {
    assertThat(CENSUS.byCorroboration()).containsExactly(entry(0, 3), entry(1, 7), entry(2, 1));
  }
}
```

- [ ] **Step 8 — run it and read the failure.** Expect `expected: 11 but was: 0` and three empty-map mismatches. Quote them.

- [ ] **Step 9 — GREEN.** Replace `EdgeCensus.of`'s stub return:

```java
    Map<String, Integer> byType = new TreeMap<>();
    Map<String, Integer> bySource = new TreeMap<>();
    Map<Integer, Integer> byCorroboration = new TreeMap<>();
    for (EdgeRecord edge : projection.edges()) {
      byType.merge(edge.typeCode(), 1, Integer::sum);
      edge.sources().stream()
          .map(Provenance::sourceId)
          .distinct()
          .forEach(sourceId -> bySource.merge(sourceId, 1, Integer::sum));
      byCorroboration.merge(edge.corroboration(), 1, Integer::sum);
    }
    return new EdgeCensus(
        byType,
        bySource,
        byCorroboration,
        projection.edges().size(),
        projection.danglingEdges());
```

- [ ] **Step 10 — guide edges, same commit.** Add to the layering diagram, beside `census --> support`:

```
  census --> port
  census --> domain
  census ==>|"one fold, not two"| export
```

  And correct the `rate` bullet in "The layering": `**`rate` reaches the same four and `recommend` itself**, one of the two dependencies between dev tools (the other is `census → export`), for the candidate half of the deck.

- [ ] **Step 11 — verify and commit.** `./gradlew spotlessApply` then the full gate. Commit the fixture, `Degrees`, the two records, the two tests and the guide: `Nodes and edges, counted off the fold (#227)`.

---

### Task 4: The claim counts

**Files:** Create `src/main/java/com/robsartin/segue/census/ClaimCensus.java`, `src/test/java/com/robsartin/segue/census/ClaimCensusTest.java`. Modify `docs/developer-guide.md`.

New import edge introduced: `census --> wikidata`.

- [ ] **Step 1 — the stub.** Create `ClaimCensus.java` with the full Javadoc and record header and an `of` returning `new ClaimCensus(0, 0, 0, 0, 0, 0, 0, 0, 0, 0)`:

```java
package com.robsartin.segue.census;

import com.robsartin.segue.domain.Equivalences;
import com.robsartin.segue.domain.LocalEntity;
import com.robsartin.segue.domain.LoggedAssertion;
import com.robsartin.segue.domain.Retraction;
import com.robsartin.segue.domain.Retractions;
import com.robsartin.segue.domain.SameAs;
import com.robsartin.segue.export.LogProjection;
import com.robsartin.segue.wikidata.KindMapper;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * What the log holds, and what retraction and merge did to it.
 *
 * <p><b>Every rule here is read from {@code domain}, not restated.</b> {@link Retractions#survives}
 * is the same question both folds ask, {@link Equivalences#last} is the rating carry's predicate
 * and {@link Equivalences#stands} is the stand-in's, and {@link Equivalences#standIns} is the
 * pre-pass both folds seed themselves from. A census with its own idea of what a merge reaches
 * would be a fourth home for a rule that already has four (issue #220).
 *
 * @param rows every row in the log, retractions and superseded merges included — the raw size,
 *     which is the one figure here that is not a derivation
 * @param retractions rows that are a {@link Retraction}
 * @param rowsRetracted rows a retraction reaches: not a retraction themselves, and refused by
 *     {@link Retractions#survives}. <b>Not the same as {@code retractions}</b>, and the gap between
 *     the two is the blast radius ADR 44 talks about
 * @param entitiesRetracted distinct entities a retraction names
 * @param localEntitiesMinted surviving {@code LocalEntity} rows. <b>Rows, not entities:</b> nothing
 *     forbids one qid appearing on two of them, and a count that deduplicated would hide it
 * @param mergesStanding surviving merges that resolve their local id today
 * @param mergesSuperseded surviving merges a later merge of the same local id has corrected
 * @param mergesSupersededButEdgeReferenced the subset of those whose canonical id a surviving edge
 *     still names, so its stand-in stands anyway (#221). A subset, never a third bucket
 * @param standIns canonical ids a surviving merge names a node for
 * @param standInsWithNoEdge how many of those ended with degree zero — a node standing for an
 *     entity the fold knows nothing else about
 */
public record ClaimCensus(
    int rows,
    int retractions,
    int rowsRetracted,
    int entitiesRetracted,
    int localEntitiesMinted,
    int mergesStanding,
    int mergesSuperseded,
    int mergesSupersededButEdgeReferenced,
    int standIns,
    int standInsWithNoEdge) {

  public static ClaimCensus of(List<LoggedAssertion> logged, LogProjection projection) {
    Objects.requireNonNull(logged, "logged");
    Objects.requireNonNull(projection, "projection");
    return new ClaimCensus(0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
  }
}
```

- [ ] **Step 2 — RED.** Create `ClaimCensusTest.java`:

```java
package com.robsartin.segue.census;

import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.segue.export.LogProjection;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Counted by hand off {@code InventedCensus.log()}, thirty rows.
 *
 * <p>One retraction, at row 29, reaching two rows: the node claim at row 3 and the edge at row 11
 * that names the retracted entity. Three minted entities. Five surviving merges: rows 24, 25 and 28
 * stand, rows 22 and 27 are superseded, and of those two only row 22's canonical id is named by a
 * surviving edge — the owner edge at row 23, claimed against it while it stood. So four canonical
 * ids get a stand-in, and one of the four ends with no edge at all.
 */
class ClaimCensusTest {

  private static final ClaimCensus CENSUS =
      ClaimCensus.of(
          InventedCensus.log(),
          LogProjection.of(new InventedCensus.FakeAssertionLog().with(InventedCensus.log())));

  @Test
  @DisplayName("every row in the log is counted, retractions and superseded merges included")
  void shouldCountEveryRowWhenTheLogIsRead() {
    assertThat(CENSUS.rows()).isEqualTo(30);
  }

  @Test
  @DisplayName("one retraction naming one entity reaches more rows than itself")
  void shouldCountTheRowsARetractionReachesWhenItNamesANodeAndAnEdge() {
    assertThat(CENSUS.retractions()).isEqualTo(1);
    assertThat(CENSUS.entitiesRetracted()).isEqualTo(1);
    assertThat(CENSUS.rowsRetracted()).isEqualTo(2);
  }

  @Test
  @DisplayName("the owner's own minted rows are counted apart from the sources' node claims")
  void shouldCountTheMintedRowsWhenTheOwnerHasClaimedEntitiesOfHisOwn() {
    assertThat(CENSUS.localEntitiesMinted()).isEqualTo(3);
  }

  @Test
  @DisplayName("a superseded merge whose canonical id an edge still names is counted apart")
  void shouldSplitTheMergesWhenOneCorrectionLeavesAnEdgeBehindAndOneDoesNot() {
    assertThat(CENSUS.mergesStanding()).isEqualTo(3);
    assertThat(CENSUS.mergesSuperseded()).isEqualTo(2);
    assertThat(CENSUS.mergesSupersededButEdgeReferenced()).isEqualTo(1);
  }

  @Test
  @DisplayName("a merge no edge names and no later merge keeps gets no stand-in at all")
  void shouldCountOnlyTheStandingStandInsWhenAMergeWasCorrectedAway() {
    assertThat(CENSUS.standIns()).isEqualTo(4);
    assertThat(CENSUS.standInsWithNoEdge()).isEqualTo(1);
  }
}
```

- [ ] **Step 3 — run it and read the failure.** Five tests, every assertion `expected: N but was: 0`. Quote at least the merge split, which is the one number no other test in this repository covers.

- [ ] **Step 4 — GREEN.** Replace `ClaimCensus.of`'s stub return:

```java
    Retractions retractions = Retractions.in(logged);
    Equivalences equivalences = Equivalences.in(logged);
    Map<String, Integer> degrees = Degrees.in(projection);

    int retractionRows = 0;
    int rowsRetracted = 0;
    int minted = 0;
    int standing = 0;
    int superseded = 0;
    int supersededButReferenced = 0;
    for (int i = 0; i < logged.size(); i++) {
      LoggedAssertion assertion = logged.get(i);
      if (assertion instanceof Retraction) {
        // A retraction never survives its own rule — it describes the fold rather than appearing
        // in it — so it is counted here and not as a row something retracted.
        retractionRows++;
      } else if (!retractions.survives(i, assertion)) {
        rowsRetracted++;
      } else if (assertion instanceof LocalEntity) {
        minted++;
      } else if (assertion instanceof SameAs merge) {
        if (equivalences.last(merge)) {
          standing++;
        } else {
          superseded++;
          if (equivalences.stands(merge)) {
            supersededButReferenced++;
          }
        }
      }
    }

    Set<String> standIns = Equivalences.standIns(logged, KindMapper::rederive).keySet();
    int withNoEdge =
        (int) standIns.stream().filter(qid -> degrees.getOrDefault(qid, 0) == 0).count();

    return new ClaimCensus(
        logged.size(),
        retractionRows,
        rowsRetracted,
        retractions.lastRetraction().size(),
        minted,
        standing,
        superseded,
        supersededButReferenced,
        standIns.size(),
        withNoEdge);
```

- [ ] **Step 5 — guide edge, same commit.** Add `  census --> wikidata` to the layering diagram.

- [ ] **Step 6 — verify and commit.** `./gradlew spotlessApply`, full gate, commit: `What retraction and merge did to the log, counted (#227)`.

---

### Task 5: The taste layer, and the third package allowed to read every score

**Files:** Create `src/main/java/com/robsartin/segue/census/TasteCensus.java`, `src/test/java/com/robsartin/segue/census/TasteCensusTest.java`. Modify `src/test/java/com/robsartin/segue/arch/ArchitectureTest.java`, `docs/developer-guide.md`.

- [ ] **Step 1 — widen the fence first, and see that it still bites.** `onlyTheRecommenderReadsEveryRating` today reads `resideOutsideOfPackages("..recommend..", "..rate..")`. Change it to `resideOutsideOfPackages("..recommend..", "..rate..", "..census..")` and extend its Javadoc:

```java
   * <p>Widened again by issue #227 (ADR 63): the census reports how many ratings sit at each score,
   * and needs the same note-free map to do it. <b>The note-carrying reads are untouched</b> —
   * {@link #onlyTheRatingsToolReadsEveryRating} keeps {@code readAll} to the listing tool and {@link
   * #onlyTheRatingsToolReadsANote} keeps the accessor there — so what this widening admits is a
   * {@code Map<String, Integer>} with nowhere to put a note, which is the same fence the
   * recommender's own rule turns on. All three readers are dev-side tools off the MCP surface, so
   * the thing this rule actually protects is unchanged.
```

  and its `because` clause, from "the recommender or the rating deck" to "the recommender, the rating deck or the census".

- [ ] **Step 2 — positive control for the widening.** Plant `import com.robsartin.segue.port.AffinityStore;` and a call to `readRatings()` in a class in `export`, run `./gradlew test --tests '*ArchitectureTest*'`, watch `onlyTheRecommenderReadsEveryRating` fire, quote it, revert. Without this, the widening is a change nobody has seen the rule survive.

- [ ] **Step 3 — the stub.** Create `TasteCensus.java`, `of` returning an all-zeros census with the five score keys present:

```java
package com.robsartin.segue.census;

import com.robsartin.segue.domain.Equivalences;
import com.robsartin.segue.domain.LocalEntity;
import com.robsartin.segue.domain.LoggedAssertion;
import com.robsartin.segue.domain.RatingScale;
import com.robsartin.segue.domain.Retractions;
import com.robsartin.segue.export.LogProjection;
import com.robsartin.segue.wikidata.KindMapper;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * The taste layer by score, and the three kinds of id a rating can end up sitting on.
 *
 * <p><b>Scores and nothing else.</b> This class is handed {@code AffinityStore.readRatings}, whose
 * {@code Map<String, Integer>} has nowhere to put a note, so the census structurally cannot see one
 * — the same fence the recommender's rule turns on (issue #85). {@code
 * ArchitectureTest.onlyTheRecommenderReadsEveryRating} is what admits this package to that read,
 * and ADR 63 is the decision behind it.
 *
 * <p><b>A histogram of scores is an aggregate, and ADR 51 says an aggregate is publishable.</b> No
 * row here names an entity, so no row can attribute a rating to one, which is what "a rating is
 * personal data" actually means — the same line {@code RatingsAreNeverLoggedTest} draws for the
 * listing tool's log lines.
 *
 * @param byScore how many ratings sit at each value, all five emitted and zeros included. A value
 *     outside 1 to 5 would appear as its own row rather than be discarded: {@code
 *     AffinityStore.updateRating} validates through {@link RatingScale}, so one in the table is a
 *     finding, and a census that swallowed it would be the parser that drops what it cannot read
 * @param total every rating there is
 * @param onALocalId ratings on an id the owner minted, by {@link LocalEntity#isLocal} — the one
 *     home for what a local id looks like, asked of the shape rather than of the log, on that
 *     method's own argument that the shape <em>is</em> the identity decision
 * @param onAStandIn ratings on a canonical id a merge named a node for, which is where {@code
 *     IdentityMerge.carryingRatings} moves a rating to
 * @param onARetractedId ratings on an entity a retraction names <b>and</b> the fold no longer
 *     holds. The second half is load-bearing: the log is append-only, so a retracted entity that
 *     was later re-added still has its retraction row forever, and "named by a retraction" alone
 *     would go on counting a rating whose entity is back in the graph
 */
public record TasteCensus(
    Map<Integer, Integer> byScore,
    int total,
    int onALocalId,
    int onAStandIn,
    int onARetractedId) {

  public TasteCensus {
    byScore =
        Collections.unmodifiableMap(new TreeMap<>(Objects.requireNonNull(byScore, "byScore")));
  }

  public static TasteCensus of(
      Map<String, Integer> ratings, List<LoggedAssertion> logged, LogProjection projection) {
    Objects.requireNonNull(ratings, "ratings");
    Objects.requireNonNull(logged, "logged");
    Objects.requireNonNull(projection, "projection");
    Map<Integer, Integer> byScore = new TreeMap<>();
    for (int score = RatingScale.MIN; score <= RatingScale.MAX; score++) {
      byScore.put(score, 0);
    }
    return new TasteCensus(byScore, 0, 0, 0, 0);
  }
}
```

- [ ] **Step 4 — RED.** Create `TasteCensusTest.java`:

```java
package com.robsartin.segue.census;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import com.robsartin.segue.domain.LoggedAssertion;
import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.export.LogProjection;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Counted by hand off {@code InventedCensus.log()} and eight invented ratings.
 *
 * <p>Two sit on ids the owner minted, one on a stand-in a merge named, and one on the entity row 29
 * retracts. The rest sit on ordinary source-claimed entities.
 */
class TasteCensusTest {

  private static final LogProjection PROJECTION =
      LogProjection.of(new InventedCensus.FakeAssertionLog().with(InventedCensus.log()));

  private static final TasteCensus CENSUS =
      TasteCensus.of(
          new InventedCensus.FakeAffinityStore()
              .rated(InventedCensus.WREN, 5)
              .rated(InventedCensus.SETTLED, 5)
              .rated(InventedCensus.HOLLOW, 4)
              .rated(InventedCensus.PRIZE, 4)
              .rated(InventedCensus.LEDGER, 3)
              .rated(InventedCensus.DOUBLE, 2)
              .rated(InventedCensus.NEIGHBOUR, 2)
              .rated(InventedCensus.GONE, 1)
              .readRatings(),
          InventedCensus.log(),
          PROJECTION);

  @Test
  @DisplayName("every score gets a row, and they sum to the number of ratings")
  void shouldCountEveryScoreWhenTheTasteLayerIsRead() {
    assertThat(CENSUS.total()).isEqualTo(8);
    assertThat(CENSUS.byScore())
        .containsExactly(entry(1, 1), entry(2, 2), entry(3, 1), entry(4, 2), entry(5, 2));
  }

  @Test
  @DisplayName("a rating on an id the owner minted is counted apart from one on a stand-in")
  void shouldSplitTheRatingsWhenSomeSitOnIdsNoSourceCanAllocate() {
    assertThat(CENSUS.onALocalId()).isEqualTo(2);
    assertThat(CENSUS.onAStandIn()).isEqualTo(1);
  }

  @Test
  @DisplayName("a rating on a retracted entity is counted, because a rating outlives the graph")
  void shouldCountTheRatingWhenItsEntityHasBeenRetracted() {
    assertThat(CENSUS.onARetractedId()).isEqualTo(1);
  }

  @Test
  @DisplayName("a rating on an entity retracted and then claimed again is not counted as retracted")
  void shouldNotCountTheRatingWhenTheRetractedEntityWasClaimedAgain() {
    List<LoggedAssertion> readded =
        List.of(
            InventedCensus.node(InventedCensus.WREN, NodeKind.PERSON, InventedCensus.WREN_LABEL),
            InventedCensus.retract(InventedCensus.WREN),
            InventedCensus.node(InventedCensus.WREN, NodeKind.PERSON, InventedCensus.WREN_LABEL));

    TasteCensus census =
        TasteCensus.of(
            new InventedCensus.FakeAffinityStore().rated(InventedCensus.WREN, 5).readRatings(),
            readded,
            LogProjection.of(new InventedCensus.FakeAssertionLog().with(readded)));

    assertThat(census.onARetractedId())
        .as("the log still holds the retraction row forever; the fold holds the entity again")
        .isZero();
  }
}
```

- [ ] **Step 5 — run it and read the failure.** Three tests red on `expected: N but was: 0`; the fourth passes already, which is the control that the second half of the retracted clause is not the only thing making it pass — note in the report that it must **stay** green after Step 6, and that Step 7 plants the version that breaks it.

- [ ] **Step 6 — GREEN.** Replace `TasteCensus.of`'s stub return:

```java
    Set<String> standIns = Equivalences.standIns(logged, KindMapper::rederive).keySet();
    Set<String> retracted = Retractions.in(logged).lastRetraction().keySet();

    int onALocalId = 0;
    int onAStandIn = 0;
    int onARetractedId = 0;
    for (Map.Entry<String, Integer> rated : ratings.entrySet()) {
      byScore.merge(rated.getValue(), 1, Integer::sum);
      String qid = rated.getKey();
      if (LocalEntity.isLocal(qid)) {
        onALocalId++;
      }
      if (standIns.contains(qid)) {
        onAStandIn++;
      }
      if (retracted.contains(qid) && !projection.nodes().containsKey(qid)) {
        onARetractedId++;
      }
    }
    return new TasteCensus(byScore, ratings.size(), onALocalId, onAStandIn, onARetractedId);
```

- [ ] **Step 7 — positive control for the re-added clause.** Drop `&& !projection.nodes().containsKey(qid)`, run `./gradlew test --tests '*TasteCensusTest*'`, watch `shouldNotCountTheRatingWhenTheRetractedEntityWasClaimedAgain` go from green to `expected: 0 but was: 1`, quote it, restore the clause. Without this the clause is untested code that happens to be correct.

- [ ] **Step 8 — guide row, same commit.** Update the `onlyTheRecommenderReadsEveryRating` row in the rule table: `calling `AffinityStore.readRatings` from outside `recommend`, `rate` **and `census`**` and extend the sentence to name the census's use. Cite ADR 63 as plain text.

- [ ] **Step 9 — verify and commit.** `./gradlew spotlessApply`, full gate, commit: `The taste layer by score, and a third reader of it (#227)`.

---

### Task 6: Degree quantiles against ADR 57's floor

**Files:** Create `src/main/java/com/robsartin/segue/census/DegreeCensus.java`, `src/test/java/com/robsartin/segue/census/DegreeCensusTest.java`.

- [ ] **Step 1 — the stub.** Create `DegreeCensus.java`, `of` returning `new DegreeCensus(Recommendations.MIN_CANDIDATE_DEGREE, 0, 0, 0, 0, 0)`:

```java
package com.robsartin.segue.census;

import com.robsartin.segue.domain.Recommendations;
import com.robsartin.segue.export.LogProjection;
import java.util.List;
import java.util.Objects;

/**
 * How connected the graph is, read beside the floor that decides what gets recommended.
 *
 * <p><b>This is the number ADR 57 left open.</b> That decision made the floor report itself on
 * every recommender run, through {@code FloorReading}, and the figures it emits describe the
 * candidate population. What nobody has is the same reading over the <em>whole</em> graph, which is
 * what says how far the population has moved away from a floor that was measured once, on a graph
 * that grows under it (issue #135).
 *
 * <p><b>A quantile is a degree some node actually has</b>, on {@code FloorReading.medianDegree}'s
 * stated reason: a median of 6.5 edges describes nothing in the graph, and the figure is read
 * beside an integer floor. The rule is nearest-rank — {@code sorted.get(min(size - 1, (int) (size *
 * p)))} — which at {@code p = 0.5} is exactly that method's upper middle. An empty graph reads as
 * zero, distinguishable from every real reading because every floor is at least one.
 *
 * <p><b>Isolated nodes are in the population</b>, at degree zero. "At or below the floor" against a
 * denominator that had already dropped what nothing reaches would be a different question.
 *
 * @param floor {@code Recommendations.MIN_CANDIDATE_DEGREE}, by reference and never by a second
 *     copy of the number — a reading has to say which floor it is a reading of
 * @param atOrBelowTheFloor nodes whose degree is at most {@code floor}. <b>At or below, where
 *     {@code CandidateSweep} excludes below</b> ({@code candidateDegree < minDegree}), so this is
 *     the sweep's exclusions plus the nodes sitting exactly on the floor — the population {@code
 *     FloorReading.headOnTheFloor} says one expansion moves first
 */
public record DegreeCensus(
    int floor, int p50, int p90, int p99, int max, int atOrBelowTheFloor) {

  public static DegreeCensus of(LogProjection projection) {
    Objects.requireNonNull(projection, "projection");
    return new DegreeCensus(Recommendations.MIN_CANDIDATE_DEGREE, 0, 0, 0, 0, 0);
  }
}
```

- [ ] **Step 2 — RED.** Create `DegreeCensusTest.java`:

```java
package com.robsartin.segue.census;

import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.segue.domain.Recommendations;
import com.robsartin.segue.export.LogProjection;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Counted by hand off {@code InventedCensus.log()}. The thirteen nodes' degrees, sorted, are
 * {@code [0, 0, 0, 0, 1, 1, 1, 2, 2, 2, 2, 5, 6]}: four isolated — three minted ids whose owner
 * edges folded onto their canonical sides, and the stand-in nothing names — three stand-ins at one,
 * four entities at two, one work at exactly ADR 57's floor and one person above it.
 *
 * <p>That last pair is the fixture's whole reason for its two extra people: with no node above the
 * floor, "at or below" and "below" would give the same answer and the test would pass on the wrong
 * comparison.
 */
class DegreeCensusTest {

  private static final DegreeCensus CENSUS =
      DegreeCensus.of(
          LogProjection.of(new InventedCensus.FakeAssertionLog().with(InventedCensus.log())));

  @Test
  @DisplayName("the floor is the recommender's own constant, not a second copy of the number")
  void shouldReportTheRecommendersFloorWhenAReadingIsTaken() {
    assertThat(CENSUS.floor()).isEqualTo(Recommendations.MIN_CANDIDATE_DEGREE);
  }

  @Test
  @DisplayName("each quantile is a degree some node actually has")
  void shouldReportADegreeSomeNodeHasWhenTheQuantilesAreRead() {
    assertThat(CENSUS.p50()).isEqualTo(1);
    assertThat(CENSUS.p90()).isEqualTo(5);
    assertThat(CENSUS.p99()).isEqualTo(6);
    assertThat(CENSUS.max()).isEqualTo(6);
  }

  @Test
  @DisplayName("a node sitting exactly on the floor is counted at or below it")
  void shouldCountTheNodeOnTheFloorWhenTheFloorsBiteIsMeasured() {
    assertThat(CENSUS.atOrBelowTheFloor())
        .as("twelve of the thirteen; only the degree-6 node is above the floor of 5")
        .isEqualTo(12);
  }
}
```

- [ ] **Step 3 — run it and read the failure.** The floor test passes (the stub already reads the constant); the other two red on `expected: 1 but was: 0` and `expected: 12 but was: 0`.

- [ ] **Step 4 — GREEN.** Replace `DegreeCensus.of`'s stub return and add the private helper:

```java
    List<Integer> sorted = Degrees.in(projection).values().stream().sorted().toList();
    int floor = Recommendations.MIN_CANDIDATE_DEGREE;
    return new DegreeCensus(
        floor,
        quantile(sorted, 0.50),
        quantile(sorted, 0.90),
        quantile(sorted, 0.99),
        sorted.isEmpty() ? 0 : sorted.getLast(),
        (int) sorted.stream().filter(degree -> degree <= floor).count());
  }

  private static int quantile(List<Integer> sorted, double proportion) {
    return sorted.isEmpty()
        ? 0
        : sorted.get(Math.min(sorted.size() - 1, (int) (sorted.size() * proportion)));
```

- [ ] **Step 5 — positive control for the comparison.** Change `degree <= floor` to `degree < floor`, run, watch `shouldCountTheNodeOnTheFloorWhenTheFloorsBiteIsMeasured` fail with `expected: 12 but was: 11`, quote it, restore. This is the one number in the census whose off-by-one is invisible in a smaller fixture.

- [ ] **Step 6 — verify and commit.** `./gradlew spotlessApply`, full gate, commit: `Degree quantiles, read beside ADR 57's floor (#227)`.

---

### Task 7: The bridge, and what the log cannot say

**Files:** Create `src/main/java/com/robsartin/segue/census/BridgeCensus.java`, `src/test/java/com/robsartin/segue/census/BridgeCensusTest.java`.

- [ ] **Step 1 — the stub.** Create `BridgeCensus.java`, `of` returning `new BridgeCensus(0, 0)`. The Javadoc is the deliverable here as much as the numbers, because it records what was measured and refused:

```java
package com.robsartin.segue.census;

import com.robsartin.segue.domain.EdgeRecord;
import com.robsartin.segue.domain.Provenance;
import com.robsartin.segue.export.LogProjection;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * What the MusicBrainz adapter reached, and how much of it the graph can describe (ADR 55, issue
 * #167).
 *
 * <p><b>Issue #227 asked for "how many have classes the bridge supplied", and the log cannot say
 * so.</b> {@code MusicBrainzSourceAdapter.toNeighbour} stamps a bridge-supplied neighbour claim
 * {@code new Provenance("wikidata", neighbour.qid(), assertedAt, 1.00)}, and its own Javadoc says
 * why: the claim "is byte-identical to what {@code ReverseClaims} and {@code
 * WikidataEntityResolver.fetch} would have produced for the same entity, because it is the same
 * claim from the same source". Both of those build exactly that provenance — same source id, same
 * reference, same confidence. Stamping it {@code musicbrainz} would attribute Wikidata's classes to
 * a database that states none, which ADR 61 refuses. So there is no marker to count, deliberately,
 * and separating the two would be a change to what the log records rather than a count over it.
 *
 * <p><b>What is counted instead is the shape of the residual ADR 55 and #167 ask about</b>: how
 * many entities a MusicBrainz-sourced edge names, and how many of those the fold can describe at
 * all. An entity reached and undescribed is the one that costs a fetch.
 *
 * <p><b>"Carries a MusicBrainz id" is read as "a MusicBrainz-sourced edge names it".</b> No MBID is
 * stored per entity anywhere — {@code NodeRecord} is {@code (qid, kind, label, instanceOf)} by ADR
 * 22 clause 2 — and the only MBIDs in the log are inside the {@code sourceRef} of a MusicBrainz
 * edge claim. Counting distinct MBIDs would mean parsing a citation, which is the one kind of
 * string this tool exists not to print.
 *
 * @param entitiesReached distinct endpoints of folded edges carrying a {@code musicbrainz}
 *     provenance
 * @param entitiesReachedWithClasses how many of those have a node in the fold stating at least one
 *     {@code P31} class — the half ADR 42 says a kind can be re-derived from
 */
public record BridgeCensus(int entitiesReached, int entitiesReachedWithClasses) {

  /**
   * The source id a MusicBrainz claim carries in the log.
   *
   * <p>A literal here rather than the adapter's own constant, for two reasons that point the same
   * way. {@code ArchitectureTest.theCensusOpensNothingElse} bans {@code musicbrainz} as a package,
   * for the exporter's reason — the adapter offers a census nothing but an HTTP client. And the log
   * holds <em>text</em>: ADR 19 forbids deleting a row, so a claim written by an adapter version
   * that no longer exists is still counted, and reading the value the log actually holds is the
   * question rather than a shortcut to it.
   */
  private static final String MUSICBRAINZ = "musicbrainz";

  public static BridgeCensus of(LogProjection projection) {
    Objects.requireNonNull(projection, "projection");
    return new BridgeCensus(0, 0);
  }
}
```

- [ ] **Step 2 — RED.** Create `BridgeCensusTest.java`:

```java
package com.robsartin.segue.census;

import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.segue.export.LogProjection;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Counted by hand off {@code InventedCensus.log()}. One edge carries a {@code musicbrainz}
 * provenance — row 12 — and it names two entities. One of the two states a class and the other
 * states none, which is the whole distinction the ADR 55 residual turns on.
 */
class BridgeCensusTest {

  private static final BridgeCensus CENSUS =
      BridgeCensus.of(
          LogProjection.of(new InventedCensus.FakeAssertionLog().with(InventedCensus.log())));

  @Test
  @DisplayName("both endpoints of a MusicBrainz-sourced edge count as reached")
  void shouldCountBothEndpointsWhenOneEdgeCarriesAMusicBrainzProvenance() {
    assertThat(CENSUS.entitiesReached()).isEqualTo(2);
  }

  @Test
  @DisplayName("an entity the fold cannot describe is reached and not counted as described")
  void shouldCountOnlyTheDescribedEntityWhenOneOfTwoStatesNoClasses() {
    assertThat(CENSUS.entitiesReachedWithClasses()).isEqualTo(1);
  }
}
```

- [ ] **Step 3 — run it and read the failure.** Both `expected: N but was: 0`.

- [ ] **Step 4 — GREEN.** Replace `BridgeCensus.of`'s stub return:

```java
    Set<String> reached = new LinkedHashSet<>();
    for (EdgeRecord edge : projection.edges()) {
      if (edge.sources().stream().map(Provenance::sourceId).anyMatch(MUSICBRAINZ::equals)) {
        reached.add(edge.fromQid());
        reached.add(edge.toQid());
      }
    }
    int described =
        (int)
            reached.stream()
                .map(projection.nodes()::get)
                .filter(node -> node != null && !node.instanceOf().isEmpty())
                .count();
    return new BridgeCensus(reached.size(), described);
```

- [ ] **Step 5 — verify and commit.** `./gradlew spotlessApply`, full gate, commit: `What MusicBrainz reached, and what the log cannot say about it (#227)`.

---

### Task 8: The report, and the tool that prints it

**Files:** Create `src/main/java/com/robsartin/segue/census/Census.java`, `src/main/java/com/robsartin/segue/census/CensusReport.java`, `src/main/java/com/robsartin/segue/census/CensusRun.java`, `src/test/java/com/robsartin/segue/census/CensusReportTest.java`, `src/test/java/com/robsartin/segue/census/CensusRunTest.java`. Modify `src/main/java/com/robsartin/segue/census/CensusCli.java`, `docs/developer-guide.md`.

New import edge introduced: `census --> sqlite`.

- [ ] **Step 1 — `Census`.** Create it whole; there is no behaviour to red here that the six section tests do not already hold, and its `of` is assembly:

```java
package com.robsartin.segue.census;

import com.robsartin.segue.domain.LoggedAssertion;
import com.robsartin.segue.export.LogProjection;
import com.robsartin.segue.port.AffinityStore;
import com.robsartin.segue.port.AssertionLog;
import java.util.List;
import java.util.Objects;

/**
 * Every number the census reports, in the six sections it prints them in.
 *
 * <p><b>Aggregates and nothing else.</b> Every component is an integer or a map of integers; no
 * qid, label or note reaches this type, which is what lets {@code CensusIsSafeToPasteTest} assert
 * over the whole output rather than over a filtered part of it. That is {@code FloorReading}'s own
 * design — "every field is an aggregate, and that is deliberate" — applied to the whole graph.
 *
 * <p><b>The log is read twice</b>, once for the raw rows and once by {@link LogProjection#of}. The
 * alternative is an overload on {@code LogProjection} taking an already-read list, which widens
 * another package's public API for a dev tool's convenience; this tool is run by hand, and a second
 * pass costs seconds.
 */
public record Census(
    NodeCensus nodes,
    EdgeCensus edges,
    ClaimCensus claims,
    TasteCensus taste,
    DegreeCensus degree,
    BridgeCensus bridge) {

  public Census {
    Objects.requireNonNull(nodes, "nodes");
    Objects.requireNonNull(edges, "edges");
    Objects.requireNonNull(claims, "claims");
    Objects.requireNonNull(taste, "taste");
    Objects.requireNonNull(degree, "degree");
    Objects.requireNonNull(bridge, "bridge");
  }

  /** Fold the log once, count it six ways. */
  public static Census of(AssertionLog log, AffinityStore ratings) {
    Objects.requireNonNull(log, "log");
    Objects.requireNonNull(ratings, "ratings");
    List<LoggedAssertion> logged = log.readAll();
    LogProjection projection = LogProjection.of(log);
    return new Census(
        NodeCensus.of(projection),
        EdgeCensus.of(projection),
        ClaimCensus.of(logged, projection),
        TasteCensus.of(ratings.readRatings(), logged, projection),
        DegreeCensus.of(projection),
        BridgeCensus.of(projection));
  }
}
```

- [ ] **Step 2 — the formatter stub.** Create `CensusReport.java` with `lines` returning `List.of(HEADER)`:

```java
package com.robsartin.segue.census;

import com.robsartin.segue.domain.NodeKind;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A census in, one aligned block of text out. A pure function, and the only class here that decides
 * what a person sees.
 *
 * <p><b>It orders as well as renders</b>, on {@code RatingsTable}'s reason: a writer that announced
 * an ordering somebody else applied could be made to lie by one refactor. Kinds come out in
 * declaration order, scores 1 to 5 always, types and source ids sorted, corroboration ascending —
 * so two runs over one unchanged log produce byte-identical text, which is ADR 43's contract.
 *
 * <p><b>Every label is a literal in this file.</b> Nothing here interpolates a value read from the
 * data except an integer, which is the property that makes the whole output safe to paste and the
 * property {@code CensusIsSafeToPasteTest} asserts. The two exceptions are the edge type codes and
 * source ids, which are vocabulary rather than entities and are covered by that test's "no Q-shaped
 * token anywhere" clause.
 */
public final class CensusReport {

  /** Said on the first line, every time — what this is, and what it is not. */
  public static final String HEADER =
      "# segue graph census — aggregates only: no labels, no ids, no notes (ADR 51, ADR 63).";

  private static final String GAP = "  ";

  private record Line(String label, Integer count) {}

  private CensusReport() {}

  public static List<String> lines(Census census) {
    Objects.requireNonNull(census, "census");
    return List.of(HEADER);
  }
}
```

- [ ] **Step 3 — RED.** Create `CensusReportTest.java`, which pins the whole text for the fixture. It is one assertion because the report is one artefact, and a diff on it is what a reviewer actually reads:

```java
package com.robsartin.segue.census;

import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.segue.export.LogProjection;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The whole report for the invented fixture, pinned exactly.
 *
 * <p>Every number here is one the six section tests already assert on its own; what this adds is
 * the labels, the order and the alignment — the part a person reads, and the part no per-number
 * test can see.
 */
class CensusReportTest {

  @Test
  @DisplayName("the report is one aligned block, in a fixed order, with a header that names it")
  void shouldRenderTheWholeCensusWhenTheFixtureIsCounted() {
    LogProjection projection =
        LogProjection.of(new InventedCensus.FakeAssertionLog().with(InventedCensus.log()));
    Census census =
        new Census(
            NodeCensus.of(projection),
            EdgeCensus.of(projection),
            ClaimCensus.of(InventedCensus.log(), projection),
            TasteCensus.of(
                new InventedCensus.FakeAffinityStore()
                    .rated(InventedCensus.WREN, 5)
                    .rated(InventedCensus.SETTLED, 5)
                    .rated(InventedCensus.HOLLOW, 4)
                    .rated(InventedCensus.PRIZE, 4)
                    .rated(InventedCensus.LEDGER, 3)
                    .rated(InventedCensus.DOUBLE, 2)
                    .rated(InventedCensus.NEIGHBOUR, 2)
                    .rated(InventedCensus.GONE, 1)
                    .readRatings(),
                InventedCensus.log(),
                projection),
            DegreeCensus.of(projection),
            BridgeCensus.of(projection));

    assertThat(String.join("\n", CensusReport.lines(census)))
        .isEqualTo(
            """
            # segue graph census — aggregates only: no labels, no ids, no notes (ADR 51, ADR 63).

            nodes
              total                                  13
              PERSON                                  3
              GROUP                                   1
              WORK                                    8
              PLACE                                   0
              EVENT                                   0
              CONCEPT                                 1

            edges
              total                                  11
              dangling                                1
              backed by also-invented                 1
              backed by invented                      6
              backed by llm:invented                  1
              backed by musicbrainz                   1
              backed by owner                         3
              of type INFLUENCED_BY                   6
              of type MEMBER_OF                       5
              corroborated by 0                       3
              corroborated by 1                       7
              corroborated by 2                       1

            claims
              log rows                               30
              retractions                             1
              rows they removed                       2
              entities they name                      1
              local entities minted                   3
              merges standing                         3
              merges superseded                       2
              merges superseded but edge-referenced    1
              stand-ins                               4
              stand-ins with no edge                  1

            taste
              ratings                                 8
              rated 1                                 1
              rated 2                                 2
              rated 3                                 1
              rated 4                                 2
              rated 5                                 2
              on a local id                           2
              on a stand-in                           1
              on a retracted id                       1

            degree
              floor                                   5
              p50                                     1
              p90                                     5
              p99                                     6
              max                                     6
              at or below the floor                  12

            bridge
              entities MusicBrainz reached            2
              of those, carrying classes              1"""
                .stripTrailing());
  }
}
```

  **Compute the column by hand before running anything.** The widest label is `  merges superseded but edge-referenced` — 37 characters plus the two-space indent, 39 — so every value starts at column 42 (39, then the two-space `GAP`). Pad the block above to that and check it, so the expected text is written from the rule rather than from the output. **The numbers are the assertion and are already pinned by the six section tests; the alignment is arithmetic.** If the first run disagrees only on padding, fix the padding; if it disagrees on a number, the number is the finding — do not copy the actual output over the expectation.

- [ ] **Step 4 — run it and read the failure.** `./gradlew test --tests '*CensusReportTest*'`. Expect a string comparison failure showing one line against fifty. Quote the first few lines of the diff.

- [ ] **Step 5 — GREEN.** Replace `CensusReport.lines`'s stub body:

```java
    List<Line> body = body(census);
    int width =
        body.stream()
            .filter(line -> line.count() != null)
            .mapToInt(line -> line.label().length())
            .max()
            .orElse(0);
    List<String> rendered = new ArrayList<>();
    rendered.add(HEADER);
    for (Line line : body) {
      if (line.count() == null) {
        rendered.add("");
        rendered.add(line.label());
      } else {
        rendered.add(line.label() + " ".repeat(width - line.label().length()) + GAP + line.count());
      }
    }
    return List.copyOf(rendered);
  }

  private static List<Line> body(Census census) {
    List<Line> body = new ArrayList<>();

    body.add(section("nodes"));
    body.add(count("total", census.nodes().total()));
    for (Map.Entry<NodeKind, Integer> kind : census.nodes().byKind().entrySet()) {
      body.add(count(kind.getKey().name(), kind.getValue()));
    }

    body.add(section("edges"));
    body.add(count("total", census.edges().total()));
    body.add(count("dangling", census.edges().dangling()));
    census.edges().bySource().forEach((source, n) -> body.add(count("backed by " + source, n)));
    census.edges().byType().forEach((type, n) -> body.add(count("of type " + type, n)));
    census
        .edges()
        .byCorroboration()
        .forEach((sources, n) -> body.add(count("corroborated by " + sources, n)));

    ClaimCensus claims = census.claims();
    body.add(section("claims"));
    body.add(count("log rows", claims.rows()));
    body.add(count("retractions", claims.retractions()));
    body.add(count("rows they removed", claims.rowsRetracted()));
    body.add(count("entities they name", claims.entitiesRetracted()));
    body.add(count("local entities minted", claims.localEntitiesMinted()));
    body.add(count("merges standing", claims.mergesStanding()));
    body.add(count("merges superseded", claims.mergesSuperseded()));
    body.add(
        count("merges superseded but edge-referenced", claims.mergesSupersededButEdgeReferenced()));
    body.add(count("stand-ins", claims.standIns()));
    body.add(count("stand-ins with no edge", claims.standInsWithNoEdge()));

    TasteCensus taste = census.taste();
    body.add(section("taste"));
    body.add(count("ratings", taste.total()));
    taste.byScore().forEach((score, n) -> body.add(count("rated " + score, n)));
    body.add(count("on a local id", taste.onALocalId()));
    body.add(count("on a stand-in", taste.onAStandIn()));
    body.add(count("on a retracted id", taste.onARetractedId()));

    DegreeCensus degree = census.degree();
    body.add(section("degree"));
    body.add(count("floor", degree.floor()));
    body.add(count("p50", degree.p50()));
    body.add(count("p90", degree.p90()));
    body.add(count("p99", degree.p99()));
    body.add(count("max", degree.max()));
    body.add(count("at or below the floor", degree.atOrBelowTheFloor()));

    body.add(section("bridge"));
    body.add(count("entities MusicBrainz reached", census.bridge().entitiesReached()));
    body.add(count("of those, carrying classes", census.bridge().entitiesReachedWithClasses()));

    return body;
  }

  private static Line section(String name) {
    return new Line(name, null);
  }

  private static Line count(String label, int value) {
    return new Line("  " + label, value);
  }
```

- [ ] **Step 6 — `CensusRun`, and wire the CLI.** Create `CensusRun.java`:

```java
package com.robsartin.segue.census;

import com.robsartin.segue.port.AffinityStore;
import com.robsartin.segue.port.AssertionLog;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Count, then say so — and nothing between the two.
 *
 * <p>Lines go to a {@link Consumer} rather than to a logger of this class's own, so the whole report
 * is observable from a test and so this class has no logger to misuse — the discipline {@code
 * RatingsRun} and {@code SqliteAffinityStore} both keep.
 *
 * <p><b>There is no warning to say first</b>, which is the one way this differs from {@code
 * ExportRun} and {@code RatingsRun}. Those warn because what the operator does next is decide where
 * to put a file of personal data. This produces no file and no personal data; the header line says
 * what the output is, and that is the whole of it.
 *
 * <p><b>It reads and cannot write.</b> {@code ArchitectureTest.theCensusOnlyReads} forbids this
 * package the three world-fact writes, both taste-layer writes and {@code IngestService}.
 */
public final class CensusRun {

  private final AssertionLog log;
  private final AffinityStore ratings;

  public CensusRun(AssertionLog log, AffinityStore ratings) {
    this.log = Objects.requireNonNull(log, "log");
    this.ratings = Objects.requireNonNull(ratings, "ratings");
  }

  /**
   * Count the graph and emit the report.
   *
   * @return the census that was printed, so a caller can assert on the numbers without parsing the
   *     text back
   */
  public Census run(Consumer<String> lines) {
    Objects.requireNonNull(lines, "lines");
    Census census = Census.of(log, ratings);
    CensusReport.lines(census).forEach(lines);
    return census;
  }
}
```

  In `CensusCli`, replace `log.info("counting {}", options.database());` with:

```java
    try (AssertionLog assertions = new SqliteAssertionLog(options.database());
        AffinityStore ratings = new SqliteAffinityStore(options.database())) {
      new CensusRun(assertions, ratings).run(log::info);
    }
```

  and add the four imports (`port.AffinityStore`, `port.AssertionLog`, `sqlite.SqliteAffinityStore`, `sqlite.SqliteAssertionLog`).

- [ ] **Step 7 — RED then GREEN on the run.** Create `CensusRun` in Step 6 with a stub `run` that emits **only** `CensusReport.HEADER` and returns the census. Then write `CensusRunTest`, against the two fakes and never a real database, asserting that the lines `run` emits are exactly `CensusReport.lines(census)` in order and that the returned census reports 13 nodes. Run it: the returned count is already right (the stub builds a real `Census`), and the line assertion fails with one line against fifty. Quote that. Then replace the stub body with `CensusReport.lines(census).forEach(lines);` and re-run.

- [ ] **Step 8 — guide edge, same commit.** Add `  census --> sqlite` to the layering diagram.

- [ ] **Step 9 — verify and commit.** `./gradlew spotlessApply`, full gate, commit: `The census prints itself (#227)`.

---

### Task 9: The privacy test, and the runbook's own examples

**Files:** Create `src/test/java/com/robsartin/segue/census/CensusIsSafeToPasteTest.java`, `src/test/java/com/robsartin/segue/census/DeveloperGuideCensusExamplesTest.java`.

`DeveloperGuideCensusExamplesTest` needs the guide chapter that Task 10 writes. **Run Task 10 first if the two are done by different agents**; otherwise write the chapter in Task 10 and this test last.

- [ ] **Step 1 — RED, and the fixture that carries all three.** Create `CensusIsSafeToPasteTest.java`. It drives `CensusCli.main` over a real SQLite database, because the run reports through a `Consumer` and would pass by never reaching a logger at all — `RatingsAreNeverLoggedTest`'s own reason for driving the CLI:

```java
package com.robsartin.segue.census;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.robsartin.segue.domain.AffinityRecord;
import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.sqlite.SqliteAffinityStore;
import com.robsartin.segue.sqlite.SqliteAssertionLog;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ADR 51's line, held by a test rather than by review, for the one artefact where it can be.
 *
 * <p>ADR 51 says plainly that no test can hold its rule, and gives two reasons: the framing decides
 * whether a QID is a citation or a disclosure, and a test would have to read the private store to
 * know which entities are the owner's. <b>Neither reason reaches this output.</b> There is no
 * framing to judge, because every value the census emits is an integer and every label is a literal
 * in {@code CensusReport}; and there is nothing to look up, because the assertion is over the shape
 * of the text rather than over what any name means.
 *
 * <p>The fixture carries all three of the things that must not appear — a label, a note, and a
 * {@code Q} id inside that note — and the capture is at TRACE so that sqlite-jdbc's own statement
 * logging is included, which is how the sibling {@code RatingsAreNeverLoggedTest} found the driver
 * logging SQL.
 */
class CensusIsSafeToPasteTest {

  /** Anything qid-shaped at all, wherever it appears. */
  private static final Pattern A_QID = Pattern.compile("\\bQ\\d+\\b");

  private static final String LABEL = "A Label Unlike Anything Real";
  private static final String NOTE = "an invented note that names Q0900901 and nothing else";

  @TempDir private Path home;

  private Logger rootLogger;
  private Level originalLevel;
  private ListAppender<ILoggingEvent> captured;

  @BeforeEach
  void setUp() {
    captured = new ListAppender<>();
    captured.start();
    rootLogger = (Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    originalLevel = rootLogger.getLevel();
    rootLogger.setLevel(Level.TRACE);
    rootLogger.addAppender(captured);
  }

  @AfterEach
  void tearDown() {
    rootLogger.detachAppender(captured);
    rootLogger.setLevel(originalLevel);
  }

  @Test
  @DisplayName("the whole census reaches the log, and no label, note or id reaches it with them")
  void shouldEmitCountsAndNothingElseWhenTheGraphHoldsALabelANoteAndAnId() {
    Path db = home.resolve("scratch.db");
    try (SqliteAssertionLog log = new SqliteAssertionLog(db);
        SqliteAffinityStore affinity = new SqliteAffinityStore(db)) {
      log.append(InventedCensus.node("Q0900901", NodeKind.WORK, LABEL));
      affinity.put(
          new AffinityRecord("Q0900901", 5, NOTE, Instant.parse("2026-02-01T08:00:00Z")));
    }
    captured.list.clear();

    CensusCli.main(new String[] {"--db", db.toString()});

    List<String> everyLine =
        List.copyOf(captured.list).stream().map(ILoggingEvent::getFormattedMessage).toList();

    assertThat(everyLine)
        .as("the census was actually printed — without this the assertions below are vacuous")
        .contains(CensusReport.HEADER)
        .anyMatch(line -> line.startsWith("  ratings"));
    assertThat(everyLine)
        .as("no line carries a label (ADR 51, ADR 63)")
        .noneMatch(line -> line.contains(LABEL));
    assertThat(everyLine)
        .as("no line carries a note (ADR 33, ADR 51)")
        .noneMatch(line -> line.contains(NOTE));
    assertThat(everyLine)
        .as(
            "no line carries anything qid-shaped, wherever it came from — a label, a note, a source"
                + " id or an edge type code that turned out to look like an entity")
        .noneMatch(line -> A_QID.matcher(line).find());
  }
}
```

  `InventedCensus.node` is package-private in this package, so it is reachable; the qid `Q0900901` is unallocatable (ADR 58) and appears here and in the note deliberately.

- [ ] **Step 2 — run it.** It should be **green on the first run**, which is not a TDD violation and must be said out loud in the report: this is a guard, not a behaviour, and a guard's evidence is the positive control in Step 3, not a red before the code. If it is red, the census is leaking and that is the finding.

- [ ] **Step 3 — positive controls.** Two required plants and one required non-plant. One at a time: plant, run `./gradlew test --tests '*CensusIsSafeToPasteTest*'`, quote the failure, revert.

  1. **A leaked id.** In `CensusReport.lines`, after the header line, add:

```java
    rendered.add("sample id Q0900901");
```

     The `A_QID` assertion fires. Quote it. Revert.

  2. **A leaked label.** In `CensusRun.run`, before `CensusReport.lines`, add:

```java
    lines.accept(
        "widest label: "
            + com.robsartin.segue.export.LogProjection.of(log).nodes().values().stream()
                .map(com.robsartin.segue.domain.NodeRecord::label)
                .findFirst()
                .orElse(""));
```

     The label assertion fires, and so does the `A_QID` one only if the label happens to be
     qid-shaped — it is not, which is what says the two assertions are independent. Quote the label
     failure. Revert.

  3. **The non-plant, which must stay green.** In `CensusCli.run`, add `log.info("counting {}", options.database())` before opening the stores. The temp-directory path carries no qid and no fixture label, so **every assertion stays green.** That is the control that this test is not simply banning log lines: without it, "no line contains the label" would pass on a tool that printed nothing. Confirm, then revert.

  **Also record a finding.** A leak of the *note* specifically cannot be planted from this package at all: reaching it needs `AffinityStore.readAll` or `AffinityRecord.note()`, and `onlyTheRatingsToolReadsEveryRating` and `onlyTheRatingsToolReadsANote` each fire before any test runs. Try it once, quote the ArchUnit violation, and say so in the report — it is the strongest thing this task can say about the note, and it is stronger than the privacy test's own note assertion.

- [ ] **Step 4 — the runbook's examples.** Create `DeveloperGuideCensusExamplesTest.java`, the third instance of the shape `DeveloperGuideRetractionExamplesTest` and `DeveloperGuideOwnClaimExamplesTest` take. It exists because `--db` is required here too, so a guide example that forgot it would be a line the owner pastes and the tool refuses:

```java
package com.robsartin.segue.census;

import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.segue.arch.GuideExamples;
import com.robsartin.segue.arch.GuideExamples.Example;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * "Looking at the shape of your graph" shows commands the owner is meant to paste, and this runs
 * every one of them through {@link CensusCli#parse} — the third tool that requires {@code --db},
 * after the two claim tools (#183, #227).
 *
 * <p><b>Why {@code parse} and not the tool.</b> Running an example end to end would need a database,
 * and the only one on this machine is the owner's. What a runbook has to get right is the command
 * line, and {@code parse} is what enforces it before any file is opened.
 *
 * <p>In {@code census} rather than beside the document tests in {@code arch}, because {@link
 * CensusCli#parse} is package-private, exactly as both of its siblings are.
 */
class DeveloperGuideCensusExamplesTest {

  private static final GuideExamples RUNBOOK = GuideExamples.of("graphCensus");

  @Test
  @DisplayName("the guide shows at least one graphCensus example")
  void shouldShowAnExampleWhenTheGuideDocumentsTheCensus() {
    assertThat(RUNBOOK.examples())
        .as(
            "docs/developer-guide.md, 'Looking at the shape of your graph' — at least one"
                + " ./gradlew graphCensus --args=\"…\" line. Without this the other checks pass"
                + " vacuously on a chapter that shows nothing")
        .isNotEmpty();
  }

  @Test
  @DisplayName("no graphCensus example writes a tilde where $HOME belongs")
  void shouldWriteHomeRatherThanATildeWhenACensusExampleNamesADatabase() {
    assertThat(RUNBOOK.withATilde())
        .as(
            "docs/developer-guide.md — a tilde does not expand inside the double quotes of"
                + " --args=\"…\", so the example arrives at the tool as a literal ~ and dies with"
                + " \"no segue database at ~/.segue/segue.db\". CensusCli.parse cannot see this,"
                + " because a tilde is a valid path character")
        .isEmpty();
  }

  @Test
  @DisplayName("every line naming graphCensus is read as a command, or is prose with no --args")
  void shouldNameTheLineWhenACensusExampleCannotBeRead() {
    assertThat(RUNBOOK.unreadableExamples())
        .as(
            "docs/developer-guide.md — a line naming graphCensus that this test cannot read is a"
                + " line nothing checks, and skipping it silently is the hole this assertion exists"
                + " to close. A line with no --args at all is prose and is allowed")
        .isEmpty();
  }

  @Test
  @DisplayName("every graphCensus example parses through the tool's own parser")
  void shouldParseEveryExampleWhenTheGuideShowsACensusCommand() {
    List<String> refused = new ArrayList<>();
    for (Example example : RUNBOOK.examples()) {
      try {
        CensusCli.parse(
            example.arguments().toArray(String[]::new), null, GuideExamples.INVENTED_HOME);
      } catch (RuntimeException refusal) {
        refused.add(
            "line " + example.line() + ": " + example.text() + "\n    " + refusal.getMessage());
      }
    }

    assertThat(refused)
        .as(
            "docs/developer-guide.md — every graphCensus example is run through CensusCli.parse,"
                + " the boundary that decides whether a line is correct to type. --db is enforced"
                + " there, so an example that forgot it fails here")
        .isEmpty();
  }
}
```

- [ ] **Step 5 — positive control for the runbook test.** Temporarily drop `--db` from one guide example, run, watch `shouldParseEveryExampleWhenTheGuideShowsACensusCommand` fire with the refusal sentence, quote it, restore.

- [ ] **Step 6 — verify and commit.** `./gradlew spotlessApply`, full gate, commit both tests: `The census is safe to paste, and the guide's own examples run (#227)`.

---

### Task 10: The chapter, the ADR, and the index

**Files:** Create `docs/adr/0063-a-read-only-census-of-the-graph.md`. Modify `docs/developer-guide.md`, `docs/adr/README.md`.

- [ ] **Step 1 — the guide chapter.** Add `## Looking at the shape of your graph` after the "Looking at what you have rated" chapter and before "Taking something back out", and the matching Contents entry `- [Looking at the shape of your graph](#looking-at-the-shape-of-your-graph)` after the "Looking at what you have rated" entry.

````markdown
## Looking at the shape of your graph

```bash
# every count there is, over the database you name
./gradlew graphCensus --args="--db $HOME/.segue/segue.db"
```

It prints one block of counts and writes nothing. **`--db` is required and `SEGUE_DB` does not
satisfy it**, for the reason [ADR 60](adr/0060-the-claim-tools-require-an-explicit-database.md) gives
about the two claim tools, arriving at a read from a different direction: an agent's shell inherits
the variable, and this output is the shape of your whole graph and taste layer. Write `$HOME`, not
`~`.

### What it is for

Three questions this repository has left open need a number nobody has, and all three are aggregates
over the one database nobody but you may open:

- how many merges the real graph holds, which [ADR 59](adr/0059-owner-claims-as-a-third-layer.md)'s
  residual says is unmeasured;
- where the degree distribution sits relative to
  `Recommendations.MIN_CANDIDATE_DEGREE`, which [ADR 57](adr/0057-the-floor-reports-itself.md)
  measured once on a graph that has grown since;
- how much of what MusicBrainz reached the graph can describe, which
  [ADR 55](adr/0055-what-the-musicbrainz-adapter-refuses.md) and issue #167 left open.

`CensusReport` is the authority on which counts are emitted and in what order; this chapter does not
list them, because a list here would be a second copy going stale on its own.

### Why the output is safe to paste

Every value is an integer and every label is a literal in the source. No qid, label or note reaches
the output, so [ADR 51](adr/0051-what-an-adr-may-quote.md)'s line — an aggregate over your data may
be published, an entity presented as yours may not — is satisfied by construction rather than by
care. `CensusIsSafeToPasteTest` holds it: it feeds a graph containing a label, a note and a `Q` id
inside that note, captures every log line at TRACE, and asserts that none of the three appears
anywhere. ADR 51 says its rule cannot be tested in general and explains why; this is the one artefact
where it can be, and [ADR 63](adr/0063-a-read-only-census-of-the-graph.md) records why.

### It counts the export's fold, not a second one

Four of the six sections are counts over `export.LogProjection` — the same fold `exportGraph` draws
and, through `Equivalences` and `Retractions`, the same rules `GraphProjector` replays at boot. A
census with a fold of its own could disagree with the picture about how many nodes there are, which
is the drift `BothFoldsAgreeTest` exists to catch. That is why `census` depends on `export`, the
second of the two dependencies between dev tools.

### Three things this is not allowed to do

- **Write.** `theCensusOnlyReads` forbids the three world-fact writes, both taste-layer writes and
  `IngestService`.
- **Name anything.** There is no per-entity output and no `--out`; the counts go to the terminal
  through SLF4J, because `nothingWritesToStandardOut` bans stdout project-wide and there is nothing
  here a log line may not carry.
- **Reach the network, an engine or a sibling other than `export`.** `theCensusOpensNothingElse`,
  which names `REACHES_A_NETWORK` rather than any HTTP client.
````

  Then turn the four `ADR 63` plain-text mentions in the rule table, and the `census` bullet in "The layering", into links to `adr/0063-a-read-only-census-of-the-graph.md`.

- [ ] **Step 2 — ADR 63.** Create `docs/adr/0063-a-read-only-census-of-the-graph.md`. The `# 63. …` heading must match the index row's title **exactly, backticks included**, and `status:` must match the row's status, or `AdrIndexTest.shouldAgreeWithTheFileOnEveryFieldWhenARowIsComparedToIt` reds.

```markdown
---
status: Accepted
date: "2026-09-03"
topic: a-read-only-census-of-the-graph
tags: [project, tooling, privacy, data, graph]
supersedes: []
related: [what-an-adr-may-quote, the-claim-tools-require-an-explicit-database, listing-your-own-ratings, graph-exporter-views-and-formats, taste-layer-separation, privacy-and-data-handling, the-floor-reports-itself, owner-claims-as-a-third-layer, what-the-musicbrainz-adapter-refuses, layering-and-archunit, assertion-log-source-of-truth, the-rating-deck]
---
# 63. A read-only census of the graph: aggregates only, with a test as the privacy boundary

## Context

Three decisions this project has taken end in a number nobody has.

[ADR 59](0059-owner-claims-as-a-third-layer.md)'s residual says it outright: *how many merges the
owner's real graph holds is unmeasured.* [ADR 57](0057-the-floor-reports-itself.md) chose a degree
floor by running two floors on the graph of the day and reading the two lists, and said in as many
words that the graph changes under it and nothing re-opens the question.
[ADR 55](0055-what-the-musicbrainz-adapter-refuses.md) and issue #167 left open whether the bridge's
undescribed residual matters at the owner's scale.

Each of the three is a count over one database, and that database is private: nobody but the owner
may open it ([ADR 16](0016-privacy-and-data-handling.md), issue #37). A reviewer cannot answer them,
an agent cannot answer them, and the owner has had no way to answer them either — the tools that read
the whole graph produce output that names entities, which is exactly what may not leave the machine.

[ADR 51](0051-what-an-adr-may-quote.md) already drew the line this needs: **an aggregate over the
owner's data is publishable; an entity presented as the owner's is not.** What it also said is that
the line is held by review and by nothing else, for two reasons it argues are fatal to any test — the
framing decides whether a QID is a citation or a disclosure, and a test would have to read the
private store to know which entities are the owner's.

## Decision

**A sixth read-only dev-side tool, `./gradlew graphCensus --args="--db <segue.db>"`, prints one block
of counts and nothing else.** `CensusReport` is the authority on which counts; this ADR does not list
them, because a list here would be a second copy going stale on its own.

Four properties are the decision, and each is enforced.

### Every value is an integer, and that is what makes ADR 51 testable here

ADR 51's two reasons are true in general and **neither reaches one tool's output**. There is no
framing to judge, because the census emits no free text from the data at all: every value is an
integer and every label is a literal in `CensusReport`. And there is nothing to look up, because the
assertion is over the *shape* of the text rather than over what a name means — no label from the
fixture, no note from the fixture, and nothing matching `\bQ\d+\b` anywhere.

`CensusIsSafeToPasteTest` drives the CLI over a real SQLite database whose fixture carries all three,
captures every log line at TRACE so the JDBC driver's own statement logging is included, and asserts
all three absent. It is a guard rather than a behaviour, so its evidence is a planted leak seen fire
rather than a red before the code, and two were planted and quoted.

The `\bQ\d+\b` clause also covers the one hazard that comes with printing stored text: edge type
codes and source ids are read raw off the log, because [ADR 19](0019-assertion-log-source-of-truth.md)
forbids deleting a row and a census that dropped a retired vocabulary would answer a different
question. Those are vocabulary rather than entities. If one ever arrives entity-shaped, the test reds,
which is the safe direction.

**This does not overturn ADR 51 or narrow it.** ADR 51 remains the rule for prose, and remains
held by review. What is added is that one named artefact's compliance is now mechanical.

### It counts the exporter's fold, so `census` depends on `export`

Four of the six sections are counts over the folded graph, and there are exactly two ways to have
one: read `export.LogProjection`, or write a third fold. `BothFoldsAgreeTest` exists because two folds
of one log drifted; `Equivalences.foldEndpoints` and `Retractions.survives` were both moved into
`domain` to make drifting impossible rather than merely detectable. A census that disagreed with the
exported picture about how many nodes there are would be that defect returning in the one artefact
whose whole purpose is to be quoted.

So `census → export` is permitted, and it is the **second** dependency between two dev tools after
`rate → recommend` ([ADR 46](0046-the-rating-deck.md)). The mechanism is the one that already exists:
`ArchitectureTest.otherDevToolsAnd` takes the permitted siblings as a list. The borrowed fence is
bounded the same way — `theExporterOnlyReads` makes `export` read-only, so nothing reachable through
it can write.

**`LogProjection` is not moved to `support`.** That is the precedent `ClassLabels` set when `rate`
needed it, and it does not reach: `LogProjection` depends on `port`, `domain` and `wikidata`, and
`support` depends on nothing.

### `--db` is required, on ADR 60's clause rather than its consequence

[ADR 60](0060-the-claim-tools-require-an-explicit-database.md) required the flag for the two tools
that append a first-person claim, because a wrong row in an append-only log cannot be taken back.
**That argument does not reach a read**, and pretending it does would be the kind of borrowed
reasoning that makes a rule look arbitrary later.

The argument that does reach is ADR 60's central clause: *an agent's shell is initialised from the
owner's profile … an environment variable cannot distinguish the owner from an agent running as the
owner; a flag typed per invocation can.* This tool's output is the shape of the owner's whole graph
and taste layer. Aggregates are publishable under ADR 51; **whether to publish them is the owner's
decision, taken per invocation**, not one an inherited `SEGUE_DB` makes on his behalf because a task
happened to mention counting things. And a census is evidence rather than a working file: it is
pasted into an issue and quoted in an ADR, where a wrong export is discarded and a wrong count
becomes the record.

**Two fences of its own, not ADR 60's widened.** `theClaimToolsHaveNoDefaultDatabase` and
`theClaimToolsTakeTheirDatabaseFromTheFlagAlone` are named for claim tools, ADR 60 names both in its
text and is immutable, and its consequences say a third tool joins by hand.
`theCensusHasNoDefaultDatabase` and `theCensusTakesItsDatabaseFromTheFlagAlone` are written beside
them with the same division of labour — the first forbids the name, the second forbids the
capability — because ADR 60 measured that the first alone stops only the lazy version.

### The note-free bulk read is widened to a third package

`ArchitectureTest.onlyTheRecommenderReadsEveryRating` confined `AffinityStore.readRatings` to
`recommend` and `rate`, and its own Javadoc says widening the taste layer's readership stays an
ADR-level decision. It is widened here to `census`, and this paragraph is that decision.

**`readRatings`, never `readAll`.** The map is `Map<String, Integer>` and has nowhere to put a note,
so the census structurally cannot see one — the same fence that lets the recommender hold the store
at all (issue #85). `onlyTheRatingsToolReadsEveryRating` and `onlyTheRatingsToolReadsANote` are
untouched: the note-carrying reads stay the listing tool's. What the census emits is a histogram, and
[ADR 33](0033-taste-layer-separation.md)'s "never logged" is satisfied for the reason
`RatingsAreNeverLoggedTest` already gives about the listing tool's own log lines — no row names an
entity, so no row can attribute a rating to one.

## Alternatives considered

- **Answer the three questions with a throwaway probe and delete it.** How every measurement in ADRs
  55, 57 and 59 was taken. Rejected because a probe answers once: ADR 57's whole decision was that
  the floor should report itself on every run, precisely because a manual reading is one nobody
  repeats. The three questions recur, and a committed tool is the difference between a number and a
  number somebody can produce again next month.

- **Add a `--census` view to `exportGraph`.** No new package, no new fence, and the fold is already
  there. Rejected on the reason ADR 41 and ADR 43 both give for a sibling rather than a mode: the
  exporter writes files that name entities and this writes counts that name none, and a tool with two
  outputs of different sensitivity cannot have a fence that means anything about either. The privacy
  test would have had to assert over one code path of a class whose other path is required to emit
  labels.

- **Write the census to a file, as `export`, `ratings` and `recommend` all do.** Consistent, and one
  fewer thing to explain. Rejected because those three write a file for a reason that inverts here:
  ADR 33 keeps affinity out of every log line, so their output must not be logged. This output is
  counts alone. A file would leave the one artefact designed to be pasted sitting on the owner's disk,
  and would add a required flag that buys nothing.

- **Let `SEGUE_DB` satisfy `--db`, since nothing is written.** Kinder, and the failure mode is a
  re-run rather than a permanent row. Rejected on the clause above: the variable cannot tell the owner
  apart from an agent running as the owner, and what is at stake is not damage to the log but a
  decision about the owner's own data being taken by an inherited environment.

- **Report the exact number of entities whose classes the MusicBrainz bridge supplied**, which is
  what issue #227 asked for. **Rejected on the code**, not on cost.
  `MusicBrainzSourceAdapter.toNeighbour` stamps such a claim `wikidata`, with the entity's own qid as
  the reference and confidence 1.00, and its Javadoc says the claim "is byte-identical to what
  `ReverseClaims` and `WikidataEntityResolver.fetch` would have produced for the same entity, because
  it is the same claim from the same source". [ADR 61](0061-the-bridge-returns-classes.md) is why:
  stamping it `musicbrainz` would attribute Wikidata's classes to a database that states none. There
  is no marker in the log, deliberately, so the census reports what the log can answer — how many
  entities a MusicBrainz-sourced edge names, and how many of those carry classes at all — and says so
  where it counts them.

## Consequences

- **The three open questions become answerable, and none of them is answered here.** This ADR ships
  the instrument. Reading it and deciding something about the floor, the merges or the bridge is
  separate work, and an amendment written before the tool has been run would record a decision nobody
  took.

- **There are now two dependencies between dev tools, not one.** The developer guide's layering
  section said "the one dependency between two dev tools" and has been corrected. `rate → recommend`
  and `census → export` are both a tool borrowing a read-only sibling's work rather than copying it.

- **A sixth `*Cli`, a sixth `JavaExec` task, and one more entry in `DEV_TOOL_PACKAGES`** — which is
  what puts `..census..` into every sibling's fence at once, and what makes `PackageListsTest` hold
  the constant to the tree in both directions (issue #165).

- **The census reads the log twice**, once for the raw rows and once through `LogProjection.of`. The
  alternative was an overload on `LogProjection` taking an already-read list, which widens another
  package's API for a dev tool's convenience. Accepted; the tool is run by hand.

- **Nothing here makes the owner's numbers public.** The tool produces text that is *safe* to paste;
  what is pasted, and where, stays the owner's decision — which is the whole reason `--db` is typed
  per invocation.
```

- [ ] **Step 3 — the index row.** Append to the end of the `## Uncategorized` section of `docs/adr/README.md`, after the ADR 62 block:

```markdown
- [63. A read-only census of the graph: aggregates only, with a test as the privacy boundary](0063-a-read-only-census-of-the-graph.md) — _Accepted_
  Three decisions end in a number nobody has, over a database nobody but the owner may open; ADR 51 says an aggregate is publishable and that no test can hold that line, which is true in general and false for one artefact.
  Related: [51. An ADR may quote an aggregate; it may not name an entity as the owner's](0051-what-an-adr-may-quote.md), [60. The two claim tools require an explicit `--db`, and the absence of a default is fenced](0060-the-claim-tools-require-an-explicit-database.md), [43. Listing your own ratings](0043-listing-your-own-ratings.md), [41. Export bounded views of the graph](0041-graph-exporter-views-and-formats.md), [57. Make the degree floor report itself, and refuse both remedies that would change what it admits](0057-the-floor-reports-itself.md), [59. Admit owner claims as a third layer: first-person, uncorroboratable, and projected to the graph](0059-owner-claims-as-a-third-layer.md), [55. What the MusicBrainz adapter refuses — `subgroup` and `neighbors()`, each declined on a count](0055-what-the-musicbrainz-adapter-refuses.md), [46. The rating deck](0046-the-rating-deck.md), [33. Keep the taste layer separate from the world-facts layer](0033-taste-layer-separation.md), [16. Privacy and data handling](0016-privacy-and-data-handling.md)
```

  **Copy each related title from the row that already carries it**, not from memory: `AdrIndexTest` compares titles exactly, backticks included, and two of these (41 and 43) are abbreviated above — open `docs/adr/README.md` and take the exact link text of each.

- [ ] **Step 4 — verify.** `./gradlew spotlessApply` then the full gate, blocking. `AdrIndexTest` (six checks), `DocumentationLinksTest` (every relative link resolves to a file *and a heading*) and `DeveloperGuideEnumerationsTest` are the three that this task is measured by. If Task 9's `DeveloperGuideCensusExamplesTest` was written first, it turns green here.

- [ ] **Step 5 — commit.** `git add` by explicit path: `docs/adr/0063-a-read-only-census-of-the-graph.md`, `docs/adr/README.md`, `docs/developer-guide.md`. Message: `ADR 63, and the runbook for the census (#227)`.

---

## Done when

- `./gradlew graphCensus --args="--db <a scratch database>"` prints the six sections and writes
  nothing. **Never run it against `~/.segue/segue.db`.**
- `SEGUE_REQUIRE_BROWSER=true SEGUE_REQUIRE_GRAPHVIZ=true ./gradlew check --rerun-tasks` is green.
- Every count in the report is asserted against `InventedCensus` with a hand-counted expectation, and
  each was seen red at zero first.
- Every new fence was seen fire on a planted violation, and the `export` carve-out was seen *not* to
  fire. Both privacy plants were seen fire.
- The guide's four derived sets, the ADR index and every relative link are green without anyone
  editing a test to make them so.
