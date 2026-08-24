# Increment 0: Build Uplift — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the segue repository satisfy its own ADRs — a committed Gradle wrapper, a real test framework, enforced formatting, coverage and architecture rules, CI as the merge gate, and a LICENSE — while retiring the slice 0 `bakeoff/` scaffolding into permanent tests.

**Architecture:** No production behaviour changes. `src/main` loses the `bakeoff` package entirely; its three classes become test assets. `DomainSelfTest`'s hand-rolled checks become JUnit tests, `Fixture` moves to `src/test`, and `BakeOff`'s cross-engine comparison becomes `GraphStoreContract` — an abstract test class run against both `TinkerGraphStore` and `JenaGraphStore`, so the bake-off becomes a CI gate rather than a program someone has to remember to run.

**Tech Stack:** Gradle 9.7.1 (Kotlin DSL, version catalog), Java toolchain 25 / `release 21`, JUnit 6.1.3, AssertJ 3.27.7, ArchUnit 1.5.0 (`archunit-junit6`), Spotless 8.10.0 with google-java-format 1.36.1, JaCoCo 0.8.15, GitHub Actions.

## Global Constraints

- **Base package** is `com.robsartin.segue`. Gradle `group` is `com.robsartin`.
- **Java toolchain 25, `options.release = 21`.** Do not raise `release` in this increment.
- **All dependency versions live in `gradle/libs.versions.toml`.** No version literals in `build.gradle.kts`.
- **Exact versions:** Gradle `9.7.1`, JUnit `6.1.3`, AssertJ `3.27.7`, ArchUnit `1.5.0`, Spotless plugin `8.10.0`, google-java-format `1.36.1`, JaCoCo `0.8.15`, TinkerPop `3.7.3` (unchanged), Jena `5.3.0` (unchanged), slf4j-nop `2.0.16` (unchanged).
- **Coverage thresholds:** line > 0.80, branch > 0.65. Never lower them to make a build pass.
- **No `System.out` or `System.err` in `src/main`** once Task 7 lands. Test code may use them.
- **`src/main/java/com/robsartin/segue/bakeoff/` must not exist** at the end of this increment.
- **Do not touch `src/main/java/com/robsartin/segue/{domain,port,tinker,jena}`** except where a task says so explicitly. This increment adds infrastructure; it does not change behaviour.
- **Commit after every task.** Conventional Commits (`build:`, `test:`, `docs:`, `ci:`, `refactor:`).
- Run `./gradlew spotlessApply` before committing any task that touched Java.

---

## File Structure

**Created:**

| Path | Responsibility |
|---|---|
| `gradlew`, `gradlew.bat`, `gradle/wrapper/*` | Pinned Gradle wrapper |
| `gradle/libs.versions.toml` | Single source of truth for versions |
| `LICENSE` | Apache 2.0, verbatim |
| `NOTICE` | Copyright attribution |
| `.github/workflows/ci.yml` | The merge gate |
| `src/test/java/com/robsartin/segue/fixture/Fixture.java` | The Nick Cave neighbourhood, moved from `src/main` |
| `src/test/java/com/robsartin/segue/domain/RecordInvariantsTest.java` | Domain record validation |
| `src/test/java/com/robsartin/segue/domain/EdgeFoldTest.java` | Assertion→edge folding, corroboration, time travel, multigraph |
| `src/test/java/com/robsartin/segue/port/GraphStoreContract.java` | Abstract cross-engine contract |
| `src/test/java/com/robsartin/segue/tinker/TinkerGraphStoreContractTest.java` | Runs the contract on Gremlin |
| `src/test/java/com/robsartin/segue/jena/JenaGraphStoreContractTest.java` | Runs the contract on RDF |
| `src/test/java/com/robsartin/segue/arch/ArchitectureTest.java` | ADR enforcement |

**Modified:** `build.gradle.kts`, `.gitignore`, `CLAUDE.md`, `README.md`

**Deleted:** `src/main/java/com/robsartin/segue/bakeoff/` (all three classes), `_to_delete/`

---

### Task 1: Gradle wrapper and version catalog

No test for this task — it is the build harness every later task's tests run on. Its verification is that the build runs.

**Files:**
- Create: `gradle/wrapper/gradle-wrapper.properties`, `gradle/wrapper/gradle-wrapper.jar`, `gradlew`, `gradlew.bat` (all generated)
- Create: `gradle/libs.versions.toml`
- Modify: `build.gradle.kts`

**Interfaces:**
- Consumes: nothing
- Produces: `./gradlew` works; catalog accessors `libs.junit.bom`, `libs.assertj`, `libs.archunit.junit6`, `libs.tinkergraph`, `libs.jena.arq`, `libs.slf4j.nop`, and `libs.versions.googleJavaFormat`

- [ ] **Step 1: Generate the wrapper**

The repo has no `gradlew`. Bootstrap once with the system Gradle:

```bash
cd ~/code/segue
gradle wrapper --gradle-version 9.7.1
```

- [ ] **Step 2: Verify the wrapper runs**

```bash
./gradlew --version
```

Expected: `Gradle 9.7.1`. If Gradle cannot launch, check `JAVA_HOME` — Gradle 9.1.0 is the minimum that runs on Java 25.

- [ ] **Step 3: Write the version catalog**

Create `gradle/libs.versions.toml`:

```toml
[versions]
tinkerpop = "3.7.3"
jena = "5.3.0"
slf4j = "2.0.16"
junit = "6.1.3"
assertj = "3.27.7"
archunit = "1.5.0"
googleJavaFormat = "1.36.1"
jacoco = "0.8.15"
spotless = "8.10.0"

[libraries]
tinkergraph = { module = "org.apache.tinkerpop:tinkergraph-gremlin", version.ref = "tinkerpop" }
jena-arq = { module = "org.apache.jena:jena-arq", version.ref = "jena" }
slf4j-nop = { module = "org.slf4j:slf4j-nop", version.ref = "slf4j" }
junit-bom = { module = "org.junit:junit-bom", version.ref = "junit" }
junit-jupiter = { module = "org.junit.jupiter:junit-jupiter" }
junit-platform-launcher = { module = "org.junit.platform:junit-platform-launcher" }
assertj = { module = "org.assertj:assertj-core", version.ref = "assertj" }
archunit-junit6 = { module = "com.tngtech.archunit:archunit-junit6", version.ref = "archunit" }

[plugins]
spotless = { id = "com.diffplug.spotless", version.ref = "spotless" }
```

`junit-jupiter` and `junit-platform-launcher` carry no version deliberately — the BOM supplies it.

- [ ] **Step 4: Rewrite build.gradle.kts to use the catalog**

Replace `build.gradle.kts` entirely:

```kotlin
plugins {
    java
    jacoco
    alias(libs.plugins.spotless)
}

group = "com.robsartin"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

dependencies {
    // Chosen engine. See docs/adr/0018-graph-engine-gremlin.md.
    implementation(libs.tinkergraph)
    // Reference implementation, kept working as a cross-check.
    implementation(libs.jena.arq)
    runtimeOnly(libs.slf4j.nop)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testImplementation(libs.archunit.junit6)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<JavaCompile>().configureEach {
    // Compiles on the toolchain JDK while staying runnable on 21.
    options.release.set(21)
    options.compilerArgs.add("-Xlint:unchecked")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("failed")
    }
}
```

Note what is gone: the `application` plugin and the `selfTest` task both pointed at
`bakeoff` classes that Task 6 deletes. Removing them now keeps the build green in between.

- [ ] **Step 5: Verify the build compiles**

```bash
./gradlew compileJava
```

Expected: `BUILD SUCCESSFUL`. Compilation covers `bakeoff` too, which still exists at this point.

- [ ] **Step 6: Update .gitignore and remove dead Maven scaffolding**

Append to `.gitignore` (it already ignores `build/`, `.gradle/`, `target/`, `_to_delete/`):

```gitignore
!gradle/wrapper/gradle-wrapper.jar
```

The wrapper JAR must be committed; make sure no earlier rule excludes it. Then:

```bash
rm -rf _to_delete
```

- [ ] **Step 7: Commit**

```bash
git add gradlew gradlew.bat gradle .gitignore build.gradle.kts
git commit -m "build: pin the Gradle wrapper and move versions into a catalog"
```

---

### Task 2: Move Fixture into test sources

**Files:**
- Create: `src/test/java/com/robsartin/segue/fixture/Fixture.java`
- Delete: `src/main/java/com/robsartin/segue/bakeoff/Fixture.java` (deferred to Task 8, so `BakeOff` keeps compiling)

**Interfaces:**
- Consumes: `libs` catalog from Task 1
- Produces: `com.robsartin.segue.fixture.Fixture` with the same public surface as before — `seed(GraphStore)`, `nodes()`, `assertions()`, and the QID constants `CAVE`, `BAD_SEEDS`, `BIRTHDAY_PARTY`, `GRINDERMAN`, `ELLIS`, `BLIXA`, `NEUBAUTEN`, `HARVEY_MICK`, `PROPOSITION`, `HILLCOAT`, `ASS_SAW_ANGEL`, `ROAD_FILM`, `MCCARTHY`, `ROAD_NOVEL`, `PJ_HARVEY`

- [ ] **Step 1: Copy the file into test sources**

```bash
mkdir -p src/test/java/com/robsartin/segue/fixture
cp src/main/java/com/robsartin/segue/bakeoff/Fixture.java \
   src/test/java/com/robsartin/segue/fixture/Fixture.java
```

- [ ] **Step 2: Change the package declaration**

In `src/test/java/com/robsartin/segue/fixture/Fixture.java`, change line 1 from
`package com.robsartin.segue.bakeoff;` to `package com.robsartin.segue.fixture;`.

Leave everything else byte-identical. The imports of `domain` and `port` types are already
fully qualified and still correct.

- [ ] **Step 3: Update the class comment about placeholder QIDs**

Replace the second paragraph of the class Javadoc with:

```java
 * <p><b>The QIDs below are PLACEHOLDERS in the Q9000xx range, not real Wikidata
 * identifiers.</b> They live in test sources precisely so they cannot reach a real
 * store — see docs/adr/0022-wikidata-identity-and-vocabulary.md. Slice 1 resolves
 * real QIDs via wbsearchentities; nothing depends on these values.
```

- [ ] **Step 4: Verify test sources compile**

```bash
./gradlew compileTestJava
```

Expected: `BUILD SUCCESSFUL`. There are two `Fixture` classes right now, in different
packages. That is intentional and temporary; Task 6 deletes the `bakeoff` one.

- [ ] **Step 5: Commit**

```bash
git add src/test/java/com/robsartin/segue/fixture/Fixture.java
git commit -m "test: move Fixture into test sources so placeholder QIDs cannot reach a real store"
```

---

### Task 3: Domain record invariant tests

Converts `DomainSelfTest.recordsRejectBadInput()` into real tests.

**Files:**
- Create: `src/test/java/com/robsartin/segue/domain/RecordInvariantsTest.java`

**Interfaces:**
- Consumes: `com.robsartin.segue.domain.{NodeRecord, Provenance, AssertionRecord, NodeKind}`
- Produces: nothing later tasks depend on

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/robsartin/segue/domain/RecordInvariantsTest.java`:

```java
package com.robsartin.segue.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNoException;

import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The domain records validate at construction. These were the first section of the old
 * DomainSelfTest; they are the guard rails every adapter relies on.
 */
class RecordInvariantsTest {

  private static final Instant WHEN = Instant.parse("2026-08-01T09:00:00Z");

  @Test
  @DisplayName("a qid that is not a Wikidata identifier is rejected")
  void rejectsNonWikidataQid() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new NodeRecord("nick-cave", NodeKind.PERSON, "Nick Cave"))
        .withMessageContaining("qid must look like");
  }

  @Test
  @DisplayName("a well-formed qid is accepted")
  void acceptsWikidataQid() {
    assertThatNoException()
        .isThrownBy(() -> new NodeRecord("Q5593", NodeKind.PERSON, "Pablo Picasso"));
  }

  @Test
  @DisplayName("confidence outside [0,1] is rejected at both ends")
  void rejectsConfidenceOutOfRange() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new Provenance("wikidata", "ref", WHEN, 1.5));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new Provenance("wikidata", "ref", WHEN, -0.1));
  }

  @Test
  @DisplayName("codec separators in provenance are rejected, because the codec does not escape")
  void rejectsCodecSeparators() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new Provenance("wikidata", "a\tb", WHEN, 1.0));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new Provenance("wikidata", "a\nb", WHEN, 1.0));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new Provenance("wiki\tdata", "ref", WHEN, 1.0));
  }

  @Test
  @DisplayName("a validity window that ends before it starts is rejected")
  void rejectsInvertedValidityWindow() {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                new AssertionRecord(
                    "Q1",
                    "Q2",
                    "MEMBER_OF",
                    LocalDate.of(2000, 1, 1),
                    LocalDate.of(1990, 1, 1),
                    new Provenance("wikidata", null, WHEN, 1.0)));
  }

  @Test
  @DisplayName("an open-ended validity window is allowed on either side")
  void allowsOpenEndedWindows() {
    assertThatNoException()
        .isThrownBy(
            () ->
                new AssertionRecord(
                    "Q1",
                    "Q2",
                    "MEMBER_OF",
                    LocalDate.of(1983, 1, 1),
                    null,
                    new Provenance("wikidata", null, WHEN, 1.0)));
  }

  @Test
  @DisplayName("the llm: prefix is what marks an assertion as a hypothesis")
  void hypothesisIsIdentifiedBySourcePrefix() {
    assertThat(new Provenance("llm:claude", "turn-1", WHEN, 0.30).isHypothesis()).isTrue();
    assertThat(new Provenance("wikidata", "S-1", WHEN, 1.00).isHypothesis()).isFalse();
  }

  @Test
  @DisplayName("NodeKind has exactly six constants")
  void nodeKindHasSixConstants() {
    // docs/adr/0021-six-kind-ontology.md. Wanting a seventh means the model is being
    // used wrong; this test is the guard on that decision.
    assertThat(NodeKind.values()).hasSize(6);
    assertThat(NodeKind.values())
        .containsExactly(
            NodeKind.PERSON,
            NodeKind.GROUP,
            NodeKind.WORK,
            NodeKind.PLACE,
            NodeKind.EVENT,
            NodeKind.CONCEPT);
  }
}
```

- [ ] **Step 2: Run the tests**

```bash
./gradlew test --tests '*RecordInvariantsTest'
```

Expected: PASS. These assert behaviour that already exists — this is a characterisation
conversion, not new behaviour, so green on the first run is correct. If any fail, the
production code disagrees with `DomainSelfTest` and that is a real finding: stop and report it.

- [ ] **Step 3: Commit**

```bash
./gradlew spotlessApply
git add src/test/java/com/robsartin/segue/domain/RecordInvariantsTest.java
git commit -m "test: convert domain record invariants from DomainSelfTest to JUnit"
```

---

### Task 4: Edge folding, corroboration and time-travel tests

Converts the remaining four sections of `DomainSelfTest`, including its reference `fold()`.

**Files:**
- Create: `src/test/java/com/robsartin/segue/domain/EdgeFoldTest.java`

**Interfaces:**
- Consumes: `com.robsartin.segue.fixture.Fixture` (Task 2), `com.robsartin.segue.domain.{AssertionRecord, EdgeRecord, Provenance}`
- Produces: nothing later tasks consume. Its private `fold()` reads `Fixture.assertions()` directly; Task 5 computes its expectations independently

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/robsartin/segue/domain/EdgeFoldTest.java`:

```java
package com.robsartin.segue.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.segue.fixture.Fixture;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The reference fold: what every GraphStore implementation must agree with, computed
 * straight from the assertion list. If an adapter disagrees with this, the adapter is
 * wrong. Converted from the old DomainSelfTest.
 */
class EdgeFoldTest {

  @Test
  @DisplayName("assertions collapse into fewer edges, and every edge keeps its sources")
  void assertionsCollapseIntoEdges() {
    Map<String, EdgeRecord> edges = fold();

    assertThat(edges.size()).isLessThan(Fixture.assertions().size());
    assertThat(edges.values()).allSatisfy(e -> assertThat(e.sources()).isNotEmpty());
  }

  @Test
  @DisplayName("the Bad Seeds lineup in June 1984 excludes Ellis and includes Blixa")
  void timeTravelTo1984() {
    List<String> lineup = badSeedsLineupOn(LocalDate.of(1984, 6, 1));

    assertThat(lineup).hasSize(3);
    assertThat(lineup).doesNotContain(Fixture.ELLIS); // joined 1994
    assertThat(lineup).contains(Fixture.BLIXA); // 1983-2003
  }

  @Test
  @DisplayName("the Bad Seeds lineup in June 2010 drops Mick Harvey and includes Ellis")
  void timeTravelTo2010() {
    List<String> lineup = badSeedsLineupOn(LocalDate.of(2010, 6, 1));

    assertThat(lineup).doesNotContain(Fixture.HARVEY_MICK); // left 2009
    assertThat(lineup).contains(Fixture.ELLIS);
  }

  @Test
  @DisplayName("corroboration counts distinct sources, and no model-only edge reaches two")
  void corroborationCountsDistinctSources() {
    List<EdgeRecord> corroborated =
        fold().values().stream().filter(e -> e.corroboration() >= 2).toList();

    assertThat(corroborated).hasSize(3);
    assertThat(corroborated).noneMatch(EdgeRecord::isUncorroboratedHypothesis);
  }

  @Test
  @DisplayName("model hypotheses stay quarantined")
  void hypothesesRemainQuarantined() {
    List<EdgeRecord> hypotheses =
        fold().values().stream().filter(EdgeRecord::isUncorroboratedHypothesis).toList();

    assertThat(hypotheses).hasSize(2);
    assertThat(hypotheses).allSatisfy(e -> assertThat(e.bestConfidence()).isLessThanOrEqualTo(0.30));
  }

  @Test
  @DisplayName("two different relationship types between the same pair stay separate edges")
  void multigraphKeepsParallelTypes() {
    List<EdgeRecord> caveToProposition =
        fold().values().stream()
            .filter(
                e ->
                    e.fromQid().equals(Fixture.CAVE) && e.toQid().equals(Fixture.PROPOSITION))
            .toList();

    assertThat(caveToProposition).hasSize(2);
    assertThat(caveToProposition)
        .extracting(EdgeRecord::typeCode)
        .containsExactlyInAnyOrder("WROTE_SCREENPLAY_FOR", "COMPOSED_FOR");
  }

  private static List<String> badSeedsLineupOn(LocalDate when) {
    return fold().values().stream()
        .filter(e -> e.toQid().equals(Fixture.BAD_SEEDS) && e.typeCode().equals("MEMBER_OF"))
        .filter(e -> e.validAt(when))
        .map(EdgeRecord::fromQid)
        .sorted()
        .toList();
  }

  /** Mirrors what each adapter must do: merge by (from, type, to), appending provenance. */
  private static Map<String, EdgeRecord> fold() {
    Map<String, EdgeRecord> byKey = new LinkedHashMap<>();
    for (AssertionRecord a : Fixture.assertions()) {
      EdgeRecord existing = byKey.get(a.edgeKey());
      if (existing == null) {
        byKey.put(
            a.edgeKey(),
            new EdgeRecord(
                a.fromQid(),
                a.toQid(),
                a.typeCode(),
                a.validFrom(),
                a.validTo(),
                List.of(a.provenance())));
      } else {
        List<Provenance> merged = new ArrayList<>(existing.sources());
        merged.add(a.provenance());
        byKey.put(
            a.edgeKey(),
            new EdgeRecord(
                a.fromQid(),
                a.toQid(),
                a.typeCode(),
                existing.validFrom() != null ? existing.validFrom() : a.validFrom(),
                existing.validTo() != null ? existing.validTo() : a.validTo(),
                merged));
      }
    }
    return byKey;
  }
}
```

- [ ] **Step 2: Run the tests**

```bash
./gradlew test --tests '*EdgeFoldTest'
```

Expected: PASS, for the same reason as Task 3 — this characterises existing behaviour.
A failure is a real finding; stop and report rather than adjusting the expected numbers.

- [ ] **Step 3: Commit**

```bash
./gradlew spotlessApply
git add src/test/java/com/robsartin/segue/domain/EdgeFoldTest.java
git commit -m "test: convert edge folding, corroboration and time travel to JUnit"
```

---

### Task 5: The cross-engine contract test

This is the increment's centrepiece: `BakeOff` becomes a gate.

**Files:**
- Create: `src/test/java/com/robsartin/segue/port/GraphStoreContract.java`
- Create: `src/test/java/com/robsartin/segue/tinker/TinkerGraphStoreContractTest.java`
- Create: `src/test/java/com/robsartin/segue/jena/JenaGraphStoreContractTest.java`

**Interfaces:**
- Consumes: `com.robsartin.segue.port.GraphStore`, `com.robsartin.segue.fixture.Fixture`
- Produces: `GraphStoreContract` with an abstract `protected abstract GraphStore createStore();`, which any future store implementation subclasses

- [ ] **Step 1: Write the abstract contract**

Create `src/test/java/com/robsartin/segue/port/GraphStoreContract.java`:

```java
package com.robsartin.segue.port;

import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.segue.domain.EdgeRecord;
import com.robsartin.segue.domain.PathResult;
import com.robsartin.segue.fixture.Fixture;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Every GraphStore must satisfy this, whichever engine backs it.
 *
 * <p>This was the BakeOff program in slice 0. Making it a contract test is the point:
 * the cross-engine comparison stops being something you remember to run and becomes
 * something CI refuses to merge without. See docs/adr/0018-graph-engine-gremlin.md,
 * which commits to keeping the Jena reference implementation working.
 *
 * <p>Note the assertions compare full result SETS. Comparing only the shortest path is
 * exactly what let the multigraph bug pass in slice 0.
 */
public abstract class GraphStoreContract {

  private GraphStore store;

  /** Supply a fresh, empty store. Called before each test. */
  protected abstract GraphStore createStore();

  @BeforeEach
  void seed() {
    store = createStore();
    Fixture.seed(store);
  }

  @AfterEach
  void tearDown() {
    if (store != null) {
      store.close();
    }
  }

  @Test
  @DisplayName("assertions collapse into fewer edges than were asserted")
  void ingestCollapsesAssertions() {
    assertThat(store.edgeCount()).isLessThan(Fixture.assertions().size());
  }

  @Test
  @DisplayName("every seeded node is retrievable")
  void nodesAreRetrievable() {
    assertThat(store.node(Fixture.CAVE)).isPresent();
    assertThat(store.node(Fixture.CAVE).orElseThrow().label()).isEqualTo("Nick Cave");
    assertThat(store.node("Q999999")).isEmpty();
  }

  @Test
  @DisplayName("Q1: Cave reaches Hillcoat in two hops, through a film")
  void pathsCrossFromMusicIntoFilm() {
    List<PathResult> paths = store.shortestPaths(Fixture.CAVE, Fixture.HILLCOAT, 4, 50);

    assertThat(paths).isNotEmpty();
    assertThat(paths.get(0).length()).isEqualTo(2);
  }

  @Test
  @DisplayName("Q1: the multigraph survives — three distinct two-hop routes")
  void multigraphProducesThreeTwoHopRoutes() {
    List<PathResult> paths = store.shortestPaths(Fixture.CAVE, Fixture.HILLCOAT, 4, 50);

    assertThat(paths.stream().filter(p -> p.length() == 2)).hasSize(3);
  }

  @Test
  @DisplayName("Q1b: the shortest Cave-McCarthy route is the model's unverified guess")
  void shortestIsNotMostTrustworthy() {
    List<PathResult> paths = store.shortestPaths(Fixture.CAVE, Fixture.MCCARTHY, 4, 5);

    PathResult shortest =
        paths.stream().min(java.util.Comparator.comparingInt(PathResult::length)).orElseThrow();
    PathResult longest =
        paths.stream().max(java.util.Comparator.comparingInt(PathResult::length)).orElseThrow();

    // This is the bug ADR 23 records and increment 1 fixes: length and trust disagree.
    assertThat(shortest.length()).isEqualTo(1);
    assertThat(shortest.weakestConfidence()).isLessThanOrEqualTo(0.30);
    assertThat(longest.weakestConfidence()).isGreaterThan(shortest.weakestConfidence());
  }

  @Test
  @DisplayName("Q2: last.fm contributed exactly one edge after 15 August")
  void auditBySourceAndTime() {
    List<EdgeRecord> edges =
        store.assertedBy("lastfm", Instant.parse("2026-08-15T00:00:00Z"));

    assertThat(edges).hasSize(1);
  }

  @Test
  @DisplayName("Q2: every model-asserted edge is still an uncorroborated hypothesis")
  void modelAssertionsRemainHypotheses() {
    List<EdgeRecord> edges = store.assertedBy("llm:claude", Instant.EPOCH);

    assertThat(edges).isNotEmpty();
    assertThat(edges).allMatch(EdgeRecord::isUncorroboratedHypothesis);
  }

  @Test
  @DisplayName("Q3: the Bad Seeds lineup in June 1984 has three members")
  void timeTravelTo1984() {
    List<EdgeRecord> lineup = store.validAt(Fixture.BAD_SEEDS, LocalDate.of(1984, 6, 1));

    assertThat(lineup).hasSize(3);
    assertThat(lineup).noneMatch(e -> e.fromQid().equals(Fixture.ELLIS));
    assertThat(lineup).anyMatch(e -> e.fromQid().equals(Fixture.BLIXA));
  }

  @Test
  @DisplayName("Q3: Mick Harvey has dropped out of the 2010 lineup")
  void timeTravelTo2010() {
    List<EdgeRecord> lineup = store.validAt(Fixture.BAD_SEEDS, LocalDate.of(2010, 6, 1));

    assertThat(lineup).noneMatch(e -> e.fromQid().equals(Fixture.HARVEY_MICK));
  }

  @Test
  @DisplayName("Q4: three edges have two independent sources, none of them model-only")
  void corroboration() {
    List<EdgeRecord> corroborated = store.corroborated(2);

    assertThat(corroborated).hasSize(3);
    assertThat(corroborated).noneMatch(EdgeRecord::isUncorroboratedHypothesis);
  }

  /**
   * Canonical rendering of a route set, so two engines can be compared exactly. Used by
   * the cross-engine agreement test, which lives in the Tinker subclass because it needs
   * both stores at once.
   */
  public static List<String> signatures(List<PathResult> paths) {
    return paths.stream()
        .map(
            p ->
                p.hops().stream()
                    .map(
                        h ->
                            h.from().qid()
                                + (h.traversedBackwards() ? "<-" : "-")
                                + h.edge().typeCode()
                                + (h.traversedBackwards() ? "-" : "->")
                                + h.to().qid())
                    .collect(Collectors.joining(" | ")))
        .sorted()
        .toList();
  }
}
```

- [ ] **Step 2: Write the two subclasses**

Create `src/test/java/com/robsartin/segue/tinker/TinkerGraphStoreContractTest.java`:

```java
package com.robsartin.segue.tinker;

import com.robsartin.segue.port.GraphStore;
import com.robsartin.segue.port.GraphStoreContract;

class TinkerGraphStoreContractTest extends GraphStoreContract {

  @Override
  protected GraphStore createStore() {
    return new TinkerGraphStore();
  }
}
```

Create `src/test/java/com/robsartin/segue/jena/JenaGraphStoreContractTest.java`:

```java
package com.robsartin.segue.jena;

import com.robsartin.segue.port.GraphStore;
import com.robsartin.segue.port.GraphStoreContract;

class JenaGraphStoreContractTest extends GraphStoreContract {

  @Override
  protected GraphStore createStore() {
    return new JenaGraphStore();
  }
}
```

- [ ] **Step 3: Run both contract suites**

```bash
./gradlew test --tests '*ContractTest'
```

Expected: PASS on both. Every assertion here was a passing `check(...)` in `BakeOff`.
If one engine fails where the other passes, that is a genuine divergence — report it
rather than relaxing the assertion.

- [ ] **Step 4: Add the cross-engine agreement test**

Both engines passing the same contract does not prove they return the *same* routes.
Add this to `TinkerGraphStoreContractTest` (it is the one place both stores are built):

```java
  @org.junit.jupiter.api.Test
  @org.junit.jupiter.api.DisplayName("both engines enumerate exactly the same route set")
  void enginesAgreeOnFullRouteSet() {
    try (GraphStore tinker = new TinkerGraphStore();
        GraphStore jena = new com.robsartin.segue.jena.JenaGraphStore()) {
      com.robsartin.segue.fixture.Fixture.seed(tinker);
      com.robsartin.segue.fixture.Fixture.seed(jena);

      assertThat(tinker.edgeCount()).isEqualTo(jena.edgeCount());
      assertThat(
              GraphStoreContract.signatures(
                  tinker.shortestPaths(
                      com.robsartin.segue.fixture.Fixture.CAVE,
                      com.robsartin.segue.fixture.Fixture.HILLCOAT,
                      4,
                      50)))
          .isEqualTo(
              GraphStoreContract.signatures(
                  jena.shortestPaths(
                      com.robsartin.segue.fixture.Fixture.CAVE,
                      com.robsartin.segue.fixture.Fixture.HILLCOAT,
                      4,
                      50)));
    }
  }
```

Add `import static org.assertj.core.api.Assertions.assertThat;` to that file.

- [ ] **Step 5: Run it**

```bash
./gradlew test --tests '*TinkerGraphStoreContractTest'
```

Expected: PASS. README records 11 identical routes between Cave and Hillcoat, 3 of them
two-hop.

- [ ] **Step 6: Commit**

```bash
./gradlew spotlessApply
git add src/test/java/com/robsartin/segue/port/GraphStoreContract.java \
        src/test/java/com/robsartin/segue/tinker/TinkerGraphStoreContractTest.java \
        src/test/java/com/robsartin/segue/jena/JenaGraphStoreContractTest.java
git commit -m "test: turn the engine bake-off into a cross-engine contract test"
```

---

### Task 6: Retire the bakeoff package

Deletion comes **before** the quality gates are switched on, so every commit leaves the
build green. Turning on coverage or the stdout rule while `bakeoff` still exists would
commit a red build, which the Mikado discipline forbids.

**Files:**
- Delete: `src/main/java/com/robsartin/segue/bakeoff/BakeOff.java`
- Delete: `src/main/java/com/robsartin/segue/bakeoff/DomainSelfTest.java`
- Delete: `src/main/java/com/robsartin/segue/bakeoff/Fixture.java`

**Interfaces:**
- Consumes: the replacements from Tasks 2-5, which must already pass
- Produces: a `src/main` containing only `domain`, `port`, `tinker`, `jena`

- [ ] **Step 1: Confirm the replacements are green first**

```bash
./gradlew test --tests '*RecordInvariantsTest' --tests '*EdgeFoldTest' --tests '*ContractTest'
```

Expected: PASS. Do not proceed if any fail — deleting the originals while their
replacements are red loses the only record of what the behaviour was.

- [ ] **Step 2: Record the evidence that the deletion is warranted**

Task 8 adds an ArchUnit rule forbidding `System.out`. This is the code that violates it,
so capture the violation before it disappears:

```bash
grep -c "System.out" src/main/java/com/robsartin/segue/bakeoff/*.java
```

Expected: non-zero counts for `BakeOff.java` and `DomainSelfTest.java`. Put these counts
in the commit message — they are the observed red state that Task 8's rule would catch.

- [ ] **Step 3: Delete the package**

```bash
git rm -r src/main/java/com/robsartin/segue/bakeoff
```

- [ ] **Step 4: Verify the build and tests are green**

```bash
./gradlew clean build test
```

Expected: `BUILD SUCCESSFUL`. Nothing referenced `bakeoff` except itself — Task 1 already
removed the `application` plugin and the `selfTest` task that pointed at it.

- [ ] **Step 5: Confirm src/main is down to four packages**

```bash
ls src/main/java/com/robsartin/segue
```

Expected: exactly `domain jena port tinker`.

- [ ] **Step 6: Commit**

```bash
git commit -m "refactor: retire the bakeoff package now that its checks are tests"
```

---

### Task 7: Spotless and JaCoCo

**Files:**
- Modify: `build.gradle.kts`

**Interfaces:**
- Consumes: catalog from Task 1, tests from Tasks 3-5, the deletion from Task 6
- Produces: `./gradlew check` runs format, tests and coverage

- [ ] **Step 1: Add the Spotless and JaCoCo configuration**

Append to `build.gradle.kts`:

```kotlin
spotless {
    java {
        googleJavaFormat(libs.versions.googleJavaFormat.get())
        target("src/**/*.java")
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
    }
}

jacoco {
    toolVersion = libs.versions.jacoco.get()
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

tasks.jacocoTestCoverageVerification {
    violationRules {
        rule {
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "0.80".toBigDecimal()
            }
        }
        rule {
            limit {
                counter = "BRANCH"
                value = "COVEREDRATIO"
                minimum = "0.65".toBigDecimal()
            }
        }
    }
}

tasks.check {
    dependsOn(tasks.jacocoTestReport, tasks.jacocoTestCoverageVerification)
}
```

- [ ] **Step 2: Apply formatting across the codebase**

```bash
./gradlew spotlessApply
```

This reformats `src/main` and `src/test` to google-java-format, which uses a two-space
indent where the existing code uses four. That is a large, purely mechanical diff — it is
why this is its own commit.

- [ ] **Step 3: Verify formatting is clean and tests still pass**

```bash
./gradlew spotlessCheck test
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Verify coverage clears the thresholds**

```bash
./gradlew jacocoTestCoverageVerification
```

Expected: `BUILD SUCCESSFUL`. `bakeoff` is gone, so the untested code that would have
dragged the ratio down no longer exists.

**If this fails, do not lower the thresholds.** Report the actual line and branch
percentages from `build/reports/jacoco/test/html/index.html` and stop. A real shortfall
means a genuine coverage gap in `tinker` or `jena` that needs a test, not a weaker gate.

- [ ] **Step 5: Run the whole gate**

```bash
./gradlew clean check
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```bash
git add build.gradle.kts src
git commit -m "build: enforce formatting with Spotless and coverage with JaCoCo"
```

---

### Task 8: Architecture tests

**Files:**
- Create: `src/test/java/com/robsartin/segue/arch/ArchitectureTest.java`

**Interfaces:**
- Consumes: `libs.archunit.junit6`, the deletion from Task 6
- Produces: the rule set later increments extend as new packages arrive

These rules pass on their first run, because Task 6 already removed the code that
violated them. That is deliberate, not a weak test: the red state was real and was
observed in Task 6 Step 2, where you counted the `System.out` references in `bakeoff`.
Committing the rule while it was still red would have left a broken build in history.

- [ ] **Step 1: Write the architecture test**

Create `src/test/java/com/robsartin/segue/arch/ArchitectureTest.java`:

```java
package com.robsartin.segue.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition;

/**
 * Each rule names the decision it defends. See docs/adr/0032-layering-and-archunit.md.
 *
 * <p>Rules for packages that do not exist yet (ingest, mcp, app, sqlite, wikidata)
 * arrive with those packages in later increments. ArchUnit rules over empty package
 * sets pass vacuously and teach nothing, so they are not written in advance.
 */
@AnalyzeClasses(
    packages = "com.robsartin.segue",
    importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

  /** ADR 18: the domain layer carries zero third-party dependencies. */
  @ArchTest
  static final ArchRule domainHasNoThirdPartyDependencies =
      classes()
          .that()
          .resideInAPackage("..domain..")
          .should()
          .onlyDependOnClassesThat()
          .resideInAnyPackage("..domain..", "java..")
          .because("ADR 18 keeps the domain runnable with nothing but a JDK");

  /** ADR 18: the port layer is the seam, so it depends only on the domain. */
  @ArchTest
  static final ArchRule portDependsOnlyOnDomain =
      classes()
          .that()
          .resideInAPackage("..port..")
          .should()
          .onlyDependOnClassesThat()
          .resideInAnyPackage("..domain..", "..port..", "java..")
          .because("the port exists to make the engine choice reversible");

  /** ADR 32: adapters never depend on each other. */
  @ArchTest
  static final ArchRule tinkerDoesNotDependOnJena =
      noClasses()
          .that()
          .resideInAPackage("..tinker..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("..jena..")
          .because("ADR 32: adapters are siblings, not collaborators");

  /** ADR 32: adapters never depend on each other. */
  @ArchTest
  static final ArchRule jenaDoesNotDependOnTinker =
      noClasses()
          .that()
          .resideInAPackage("..jena..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("..tinker..")
          .because("ADR 32: adapters are siblings, not collaborators");

  /** ADR 28: stdout belongs to the MCP protocol and nothing else. */
  @ArchTest
  static final ArchRule nothingWritesToStandardOut =
      noClasses()
          .should()
          .accessField(System.class, "out")
          .because(
              "ADR 28: on the stdio transport stdout carries the protocol; a stray"
                  + " println corrupts the JSON-RPC stream");

  /** ADR 30: SLF4J is the only logging API, and stderr is written through it. */
  @ArchTest
  static final ArchRule nothingWritesToStandardError =
      noClasses()
          .should()
          .accessField(System.class, "err")
          .because("ADR 30: logging goes through SLF4J, which is configured to target stderr");

  /** ADR 30: no competing logging API. */
  @ArchTest
  static final ArchRule noJavaUtilLogging =
      noClasses()
          .should()
          .dependOnClassesThat()
          .resideInAPackage("java.util.logging..")
          .because("ADR 30: SLF4J is the only logging API");

  /** ADR 32 forbids package cycles outright. */
  @ArchTest
  static final ArchRule noPackageCycles =
      SlicesRuleDefinition.slices()
          .matching("com.robsartin.segue.(*)..")
          .should()
          .beFreeOfCycles();
}
```


- [ ] **Step 2: Run the arch tests**

```bash
./gradlew test --tests '*ArchitectureTest'
```

Expected: PASS.

If `nothingWritesToStandardOut` or `nothingWritesToStandardError` **fails**, something in
`domain`, `port`, `tinker` or `jena` writes to a standard stream. That is a real ADR 28 or
ADR 30 violation in production code — report it with the offending class rather than
relaxing the rule.

- [ ] **Step 3: Verify the rule actually bites**

A rule that passes vacuously is worthless. Prove it can fail:

```bash
cat > /tmp/ArchProbe.java <<'PROBE'
package com.robsartin.segue.domain;

final class ArchProbe {
  static void probe() {
    System.out.println("this must be caught");
  }
}
PROBE
cp /tmp/ArchProbe.java src/main/java/com/robsartin/segue/domain/ArchProbe.java
./gradlew test --tests '*ArchitectureTest'
```

Expected: **FAIL**, naming `ArchProbe` in the `nothingWritesToStandardOut` violation.

- [ ] **Step 4: Remove the probe and confirm green**

```bash
rm src/main/java/com/robsartin/segue/domain/ArchProbe.java
./gradlew test --tests '*ArchitectureTest'
```

Expected: PASS. Confirm `git status` shows no leftover probe file before committing.

- [ ] **Step 5: Run the whole gate**

```bash
./gradlew clean check
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```bash
./gradlew spotlessApply
git add src/test/java/com/robsartin/segue/arch/ArchitectureTest.java
git commit -m "test: add ArchUnit rules enforcing the layering and stdout decisions"
```

---

### Task 9: LICENSE and documentation

**Files:**
- Create: `LICENSE`
- Modify: `CLAUDE.md`, `README.md`

**Interfaces:**
- Consumes: nothing
- Produces: nothing

- [ ] **Step 1: Add the Apache 2.0 licence**

```bash
curl -sSL https://www.apache.org/licenses/LICENSE-2.0.txt -o LICENSE
```

Verify it downloaded the real thing rather than an error page:

```bash
head -3 LICENSE && wc -l LICENSE
```

Expected: the first line reads `Apache License`, and the file is roughly 200 lines.

Then create `NOTICE` alongside it, which is where the copyright attribution belongs
under Apache 2.0 — the `LICENSE` file itself stays verbatim:

```
segue
Copyright 2026 Rob Sartin

Licensed under the Apache License, Version 2.0.
See LICENSE for the full text.
```

- [ ] **Step 2: Fix the stale paths in CLAUDE.md**

`CLAUDE.md` documents a `javac` invocation over `src/main/java/dev/rob/affinity/...`,
which is both the wrong package and a path that no longer exists. Replace the whole
"Build and run" section with:

```markdown
## Build and run

```bash
./gradlew check           # format, tests, coverage, arch rules — the full CI gate
./gradlew test            # tests only
./gradlew spotlessApply   # fix formatting
```

Gradle, not Maven. The wrapper is pinned to 9.7.1 and committed; **Gradle 9.1.0 is the
minimum that runs on Java 25**. The build uses a toolchain of JDK 25 and compiles at
`release 21`.

Versions live in `gradle/libs.versions.toml`, never in `build.gradle.kts`.
```

- [ ] **Step 3: Replace the Architecture section of CLAUDE.md**

The old section lists `bakeoff/`, which no longer exists:

```markdown
## Architecture

```
domain/   records + Wikidata-derived edge vocabulary. NO third-party deps.
port/     GraphStore — the seam that keeps the engine choice reversible.
tinker/   Gremlin adapter (the chosen one).
jena/     RDF adapter (reference implementation, keep it working).
```

Tests mirror this, plus `fixture/` (the Nick Cave neighbourhood, test-only) and
`arch/` (the ArchUnit rules that enforce the ADRs).

The engine bake-off is now `GraphStoreContract` — an abstract test run against both
adapters, so the cross-engine comparison is a merge gate rather than a program.
```

- [ ] **Step 4: Point CLAUDE.md at the ADRs**

Replace the "Decision already made: use Gremlin" section's final line
(`Full reasoning: README.md, and decisions/graph-engine.md in the Interest Finder project.`)
with:

```markdown
Full reasoning: `docs/adr/0018-graph-engine-gremlin.md`. All decisions are recorded in
`docs/adr/`; the slice 1 and 2 design is `docs/design/2026-08-24-slice-1-2-design.md`.
```

- [ ] **Step 5: Fix the same stale path in README.md**

`README.md` contains the identical `dev/rob/affinity` `javac` block under "Running it".
Replace that whole section with:

```markdown
## Running it

```bash
./gradlew check    # format, tests, coverage, arch rules
```

No infrastructure: TinkerGraph and Jena's TxnMem dataset are both in-process.
```

Also update the "Layout" block to drop `bakeoff/`, and change the
"Deliberately not here" section's final paragraph to note that slice 0 is complete and
the increments are tracked as GitHub issues.

- [ ] **Step 6: Verify the documented commands actually work**

```bash
./gradlew check
```

Expected: `BUILD SUCCESSFUL`. Every command quoted in the docs must run — ADR 6.

- [ ] **Step 7: Commit**

```bash
git add LICENSE NOTICE CLAUDE.md README.md
git commit -m "docs: add the Apache 2.0 licence and correct stale package paths"
```

---

### Task 10: CI as the merge gate

**Files:**
- Create: `.github/workflows/ci.yml`

**Interfaces:**
- Consumes: `./gradlew check` from Tasks 1–8
- Produces: a required status check on every PR

- [ ] **Step 1: Write the workflow**

Create `.github/workflows/ci.yml`:

```yaml
name: CI

on:
  push:
    branches: [main]
  pull_request:

permissions:
  contents: read

concurrency:
  group: ${{ github.workflow }}-${{ github.ref }}
  cancel-in-progress: true

jobs:
  check:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - name: Set up JDK 25
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '25'

      - name: Set up Gradle
        uses: gradle/actions/setup-gradle@v4

      - name: Check
        run: ./gradlew --no-daemon check

      - name: Upload test and coverage reports
        if: always()
        uses: actions/upload-artifact@v4
        with:
          name: reports
          path: |
            build/reports/tests/
            build/reports/jacoco/
          retention-days: 7
```

`check` covers formatting, tests, coverage verification and the arch rules, so one step
is the whole gate. ADR 5 requires the gate to be enforced, not merely available.

- [ ] **Step 2: Verify the workflow file parses**

```bash
python3 -c "import yaml,sys; yaml.safe_load(open('.github/workflows/ci.yml')); print('valid YAML')"
```

Expected: `valid YAML`.

- [ ] **Step 3: Confirm the same command passes locally**

```bash
./gradlew clean check
```

Expected: `BUILD SUCCESSFUL`. CI runs exactly this, so a local failure is a CI failure.

- [ ] **Step 4: Commit**

```bash
git add .github/workflows/ci.yml
git commit -m "ci: run the full check gate on every pull request"
```

---

### Task 11: Open the pull request

**Files:** none

- [ ] **Step 1: Final full verification**

```bash
./gradlew clean check
```

Expected: `BUILD SUCCESSFUL`. Record the actual coverage numbers from
`build/reports/jacoco/test/html/index.html` for the PR body.

- [ ] **Step 2: Confirm the bakeoff package is gone**

```bash
test ! -d src/main/java/com/robsartin/segue/bakeoff && echo "bakeoff retired"
ls src/main/java/com/robsartin/segue
```

Expected: `bakeoff retired`, then exactly `domain jena port tinker`.

- [ ] **Step 3: Push and open the PR**

```bash
git push -u origin 7-build-uplift
gh pr create --fill-first
```

Then edit the body to state: the coverage numbers, that `ADR 34 supersedes ADR 10`
(JUnit 6 and no Testcontainers), and that `Closes #7`.

- [ ] **Step 4: Stop for review**

Do not merge. The PR goes up for review.

---

## Notes for the implementer

**Tasks 3, 4, 5 and 8 are characterisation tests, so passing on the first run is correct.**
They encode behaviour that already exists and already passed under `DomainSelfTest` and
`BakeOff`. This is the one place in this repo where red-then-green does not apply — the
red state was slice 0 shipping without a test framework. If any of them fails, you have
found a real divergence between the old hand-rolled checks and the production code: stop
and report it rather than adjusting the expected value.

**Nothing in this plan commits a red build.** The deletion in Task 6 comes before the
gates in Tasks 7 and 8 for exactly that reason — switching on coverage or the stdout rule
while `bakeoff` still existed would put a broken commit in history. The red state is still
observed, in Task 6 Step 2 and Task 8 Step 3, it is just never committed.

**Never lower the coverage thresholds** to make a build pass. If coverage falls short after
Task 8, report the numbers.
