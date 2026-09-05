# CONCEPT nodes by the class they state — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `graphCensus` prints a seventh section, `concept classes`, listing the classes `CONCEPT` nodes state — the top ten by count, each with its class qid, its count and whether `KindMapper` has a rule for it — plus the `CONCEPT` nodes that state no class at all, so the owner can paste it and open the follow-up issue about which classes deserve a mapper rule.

**Architecture:** One new section record, `census.ConceptClassCensus`, a pure `of(LogProjection)` like its six siblings; a seventh component on `Census`; a seventh block in `CensusReport.body`. `CensusIsSafeToPasteTest`'s `\bQ\d+\b` guard is narrowed to allow a qid only at the head of a line matching `^  class Q\d+`, with three planted controls. ADR 63 gains a dated amendment ruling that a class id is vocabulary. No `KindMapper` change.

**Tech Stack:** Java 25, Gradle (plain `./gradlew`), JUnit 5, AssertJ, ArchUnit, SQLite, Logback.

**Spec:** `docs/superpowers/specs/2026-09-04-concept-nodes-by-class-design.md`

## Global Constraints

- **Pure TDD / red first**: every behaviour is seen red for the right reason — a real assertion failure, never a compile error — before the code that makes it green. Where a class does not exist yet, create a **compiling stub that returns the wrong answer**, then write the test, then watch the assertion fire. Quote the actual failure text in the report. Test names `should<Expected>When<Condition>` with `@DisplayName`.
- **Every guard gets a positive control**: plant the defect, watch the check fire, quote it, remove the plant. Task 3 writes three of them out.
- **Mikado**: the gate is green before every commit. **Stage by explicit path, git stderr visible — never `git add -A`, never `2>/dev/null` on `git add`.** Read `git status` before every commit. Commits end `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`. Never cite a `.superpowers/` path from a committed file.
- Gate, **blocking, never backgrounded**: `SEGUE_REQUIRE_BROWSER=true SEGUE_REQUIRE_GRAPHVIZ=true ./gradlew check --rerun-tasks`. Run `./gradlew spotlessApply` before each gate. Fast loop per task is named in the task.
- **Only JDK 25 is installed.** Plain `./gradlew`; never `/usr/libexec/java_home -v 21` (it silently returns 25).
- **Never run a writing dev task** (`own`, `ownClaim`, `retractEntity`, any seeding task), and **never run `graphCensus`**. `~/.segue/segue.db` is never read, written, copied or created; every test builds its own database under a `@TempDir`.
- Every id invented in `src/test` must take an unallocatable shape or `arch/StandInQidsDenoteNothingTest` reds: two leading zeros for a local entity (ADR 59), eleven digits with no leading zero for a merge's canonical side (ADR 62), **one leading zero for anything else, class ids included** (ADR 58). The one real id used here is `Q5`, and Task 1 adds its site to that test's `ALLOWED` map in the same step that introduces it.
- **A stub step carries only the imports its stub body uses.** `spotlessApply` runs `removeUnusedImports`, so an import added ahead of the code that needs it is silently deleted and the next compile fails on a name that was there a minute ago. Each GREEN step adds the imports its own code needs.
- **Do not touch `InventedCensus.log()`.** Its row numbers are cited by every hand-counted expectation in the package, and issue #247 is editing the same package on this base. New cases build their own small logs, the precedent `EdgeCensusTest` set.
- **Do not edit any census section but the new one**, and append the new report block after `bridge`. Issue #247 is adding a by-kind breakdown to the `degree` section on this same base.
- **YAGNI**: no flag, no threshold, no label lookup, no accessor beyond what a step below uses.
- Machine is loaded: no wall-clock assertions anywhere.

---

### Task 1: `ConceptClassCensus` — the counting

**Files:** Create `src/main/java/com/robsartin/segue/census/ConceptClassCensus.java`, `src/test/java/com/robsartin/segue/census/ConceptClassCensusTest.java`. Modify `src/test/java/com/robsartin/segue/arch/StandInQidsDenoteNothingTest.java`.

No new package import edge: `census --> wikidata` and `census --> export` are both already in the developer guide's layering diagram, and `theCensusOpensNothingElse` deliberately does not ban `wikidata`.

Fast loop: `./gradlew test --tests 'com.robsartin.segue.census.ConceptClassCensusTest' --tests 'com.robsartin.segue.arch.StandInQidsDenoteNothingTest'`

- [ ] **Step 1 — the stub that compiles and answers wrongly.** Create `ConceptClassCensus.java`:

```java
package com.robsartin.segue.census;

import com.robsartin.segue.export.LogProjection;
import java.util.List;
import java.util.Objects;

/**
 * Which classes the graph's {@code CONCEPT} nodes state, and how many nodes state each.
 *
 * <p><b>What this is a map of.</b> {@code KindMapper.fromInstanceOf} answers {@code CONCEPT}
 * whenever none of an entity's stated classes is in its whitelist, so an unknown share of the
 * {@code CONCEPT} nodes are people, groups, works or places wearing a class the mapper has never
 * met. The class is already on the node (ADR 42), so this is a fold away, and it is the map of the
 * mapper's gaps that issue #52 last drew by hand with a throwaway probe.
 *
 * <p><b>Two gaps, kept apart.</b> {@code statingNoClass} counts nodes whose source classified them
 * without stating a class at all: no whitelist entry could ever catch those, and folding them into
 * the rows below would overstate what a mapper rule can reach.
 *
 * <p><b>A row is nodes, not statements.</b> A node stating three classes appears on three rows,
 * because each row answers "how many nodes would a rule for this class move"; a node stating one
 * class twice is one node.
 *
 * <p><b>Ten rows, ordered by count.</b> Ten keeps the section the size of its siblings, but the
 * load-bearing reason is that a class stated by a single node is the row that comes closest to
 * naming an entity, and ordering by count descending is what pushes it out. {@code
 * distinctClasses} reports the size of what was cut without printing it. ADR 63's 2026-09-04
 * amendment is where that is ruled on.
 */
public record ConceptClassCensus(int statingNoClass, int distinctClasses, List<ConceptClass> top) {

  /**
   * One class, and how many {@code CONCEPT} nodes state it.
   *
   * <p>{@code mapped} is {@code KindMapper.isMapped}, and it is the section's invariant printed
   * rather than a distinction the production fold can draw: {@code fromInstanceOf} skips a class
   * the whitelist does not know and every whitelist entry maps to a kind other than {@code
   * CONCEPT}, so a {@code CONCEPT} node cannot state a class the mapper knows. A row that reads
   * {@code mapped} is the fold and the mapper having come apart. It is also {@code isMapped}'s
   * first production caller — the seam ADR 21's issue-#87 amendment named and left unbuilt for
   * want of one.
   */
  public record ConceptClass(String classQid, int nodes, boolean mapped) {

    public ConceptClass {
      Objects.requireNonNull(classQid, "classQid");
    }
  }

  public ConceptClassCensus {
    top = List.copyOf(Objects.requireNonNull(top, "top"));
  }

  public static ConceptClassCensus of(LogProjection projection) {
    Objects.requireNonNull(projection, "projection");
    return new ConceptClassCensus(0, 0, List.of());
  }
}
```

- [ ] **Step 2 — the `Q5` site, so the tests below may name a class the whitelist really knows.** In `StandInQidsDenoteNothingTest`, the `"Q5"` entry already reads `real("class id — mapped by ClassLabels and KindMapper", …)` over nine sites. Add one, in path order, immediately after the `app/WikidataMusicBrainzIdentityTest.java` site and before the `domain/LoggedAssertionTest.java` one:

```java
                  code("src/test/java/com/robsartin/segue/census/ConceptClassCensusTest.java"),
```

- [ ] **Step 3 — RED (counting, kinds, and the no-class line).** Create `ConceptClassCensusTest.java`:

```java
package com.robsartin.segue.census;

import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.segue.census.ConceptClassCensus.ConceptClass;
import com.robsartin.segue.domain.LoggedAssertion;
import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.domain.NodeRecord;
import com.robsartin.segue.export.LogProjection;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Small logs of this section's own, folded — the precedent {@code EdgeCensusTest} set for a case
 * {@code InventedCensus.log()} cannot reach without renumbering every hand-counted expectation in
 * the package.
 *
 * <p><b>{@code Q5} is real, and it is here on purpose.</b> The marker asks whether {@code
 * KindMapper} has a rule for a class, and only a class the whitelist really holds can answer yes.
 * Its site is in {@code StandInQidsDenoteNothingTest}'s allowlist, beside the nine other files that
 * name it for the same reason.
 */
class ConceptClassCensusTest {

  private static final String A_NODE = "Q0900211";
  private static final String ANOTHER_NODE = "Q0900212";
  private static final String A_THIRD_NODE = "Q0900213";

  private static final String CLASS_ONE = "Q0900302";
  private static final String CLASS_TWO = "Q0900303";

  /** Wikidata's "human" — the one class here the whitelist really knows. */
  private static final String HUMAN = "Q5";

  private static LogProjection fold(LoggedAssertion... claims) {
    return LogProjection.of(new InventedCensus.FakeAssertionLog().with(claims));
  }

  @Test
  @DisplayName("a node counts once for each class it states, and once for one it states twice")
  void shouldCountANodeOncePerClassWhenItStatesSeveralAndRepeatsOne() {
    LogProjection projection =
        fold(
            InventedCensus.node(
                A_NODE, NodeKind.CONCEPT, "an invented thing", List.of(CLASS_ONE, CLASS_TWO)),
            InventedCensus.node(
                ANOTHER_NODE,
                NodeKind.CONCEPT,
                "another invented thing",
                List.of(CLASS_ONE, CLASS_ONE)));

    assertThat(ConceptClassCensus.of(projection).top())
        .as("a row is nodes stating the class, so the repeat is one node and not two")
        .containsExactly(
            new ConceptClass(CLASS_ONE, 2, false), new ConceptClass(CLASS_TWO, 1, false));
  }

  @Test
  @DisplayName("a class stated by a node of another kind is not counted")
  void shouldCountConceptNodesAloneWhenAnotherKindStatesAClass() {
    LogProjection projection =
        fold(
            InventedCensus.node(A_NODE, NodeKind.CONCEPT, "an invented thing", List.of(CLASS_ONE)),
            InventedCensus.node(
                ANOTHER_NODE, NodeKind.PERSON, "an invented person", List.of(HUMAN)));

    assertThat(ConceptClassCensus.of(projection).top())
        .as("the person states a class the whitelist knows, so the fold keeps it PERSON")
        .containsExactly(new ConceptClass(CLASS_ONE, 1, false));
  }

  @Test
  @DisplayName("a concept node that states no class is counted on its own line, not on a row")
  void shouldCountNodesStatingNoClassSeparatelyWhenAConceptNodeStatesNone() {
    LogProjection projection =
        fold(
            InventedCensus.node(A_NODE, NodeKind.CONCEPT, "an invented thing", List.of(CLASS_ONE)),
            InventedCensus.node(ANOTHER_NODE, NodeKind.CONCEPT, "an unclassified thing"));

    ConceptClassCensus counted = ConceptClassCensus.of(projection);

    assertThat(counted.statingNoClass())
        .as("no whitelist entry could ever reach a node whose source stated no class")
        .isEqualTo(1);
    assertThat(counted.top()).containsExactly(new ConceptClass(CLASS_ONE, 1, false));
  }
}
```

- [ ] **Step 4 — run it and watch it fail for the right reason.** `./gradlew test --tests 'com.robsartin.segue.census.ConceptClassCensusTest'`. Expect three `AssertionError`s from the stub's empty answer — `Expecting actual: [] to contain exactly …` on the first two, and `expected: 1 but was: 0` on `statingNoClass`. Quote one verbatim in the report.

- [ ] **Step 5 — GREEN.** Replace the body of `of` in `ConceptClassCensus.java`, and extend its imports to `com.robsartin.segue.domain.NodeKind`, `com.robsartin.segue.domain.NodeRecord`, `java.util.Map`, `java.util.Set`, `java.util.TreeMap`:

```java
  public static ConceptClassCensus of(LogProjection projection) {
    Objects.requireNonNull(projection, "projection");
    Map<String, Integer> byClass = new TreeMap<>();
    int statingNoClass = 0;
    for (NodeRecord node : projection.nodes().values()) {
      if (node.kind() != NodeKind.CONCEPT) {
        continue;
      }
      if (node.instanceOf().isEmpty()) {
        statingNoClass++;
        continue;
      }
      // A set, so a class a source stated twice about one node is one node on that class's row.
      for (String classQid : Set.copyOf(node.instanceOf())) {
        byClass.merge(classQid, 1, Integer::sum);
      }
    }
    List<ConceptClass> top =
        byClass.entrySet().stream()
            .map(stated -> new ConceptClass(stated.getKey(), stated.getValue(), false))
            .toList();
    return new ConceptClassCensus(statingNoClass, byClass.size(), top);
  }
```

- [ ] **Step 6 — run it and watch it pass.** Same command. Three green.

- [ ] **Step 7 — RED (the order and the cut).** Add to `ConceptClassCensusTest`:

```java
  /**
   * Eleven classes on one node, so the cut has something to cut and every tie is a real tie. The
   * twelfth claim gives the highest qid the highest count, which is the only way to tell an order
   * by count from an order by id.
   */
  private static final List<String> ELEVEN_CLASSES =
      List.of(
          "Q0900311",
          "Q0900312",
          "Q0900313",
          "Q0900314",
          "Q0900315",
          "Q0900316",
          "Q0900317",
          "Q0900318",
          "Q0900319",
          "Q0900320",
          "Q0900321");

  private static LogProjection elevenClassesOneOfThemTwice() {
    return fold(
        InventedCensus.node(A_NODE, NodeKind.CONCEPT, "an invented thing", ELEVEN_CLASSES),
        InventedCensus.node(
            A_THIRD_NODE, NodeKind.CONCEPT, "a third invented thing", List.of("Q0900321")));
  }

  @Test
  @DisplayName("the rows are the ten commonest classes, count first and qid on a tie")
  void shouldOrderByCountThenQidAndKeepTenWhenElevenClassesAreStated() {
    assertThat(ConceptClassCensus.of(elevenClassesOneOfThemTwice()).top())
        .as(
            "Q0900321 is the highest qid and the only class with two nodes, so an order by qid"
                + " would put it last and the cut would keep it; count first puts it first, and"
                + " the tie-break then drops Q0900320 as the highest of the ones left")
        .containsExactly(
            new ConceptClass("Q0900321", 2, false),
            new ConceptClass("Q0900311", 1, false),
            new ConceptClass("Q0900312", 1, false),
            new ConceptClass("Q0900313", 1, false),
            new ConceptClass("Q0900314", 1, false),
            new ConceptClass("Q0900315", 1, false),
            new ConceptClass("Q0900316", 1, false),
            new ConceptClass("Q0900317", 1, false),
            new ConceptClass("Q0900318", 1, false),
            new ConceptClass("Q0900319", 1, false));
  }

  @Test
  @DisplayName("every distinct class is counted, including the ones the cut dropped")
  void shouldCountEveryDistinctClassWhenMoreAreStatedThanAreShown() {
    assertThat(ConceptClassCensus.of(elevenClassesOneOfThemTwice()).distinctClasses())
        .as("without this the reader cannot tell a whole distribution from a truncated one")
        .isEqualTo(11);
  }
```

- [ ] **Step 8 — run it and watch it fail for the right reason.** Expect the order test to fail on the actual list starting `Q0900311` and holding eleven entries. `distinctClasses` is already right — say so, and note that it is asserted here because it is the number that makes the cut readable, not because it was red.

- [ ] **Step 9 — GREEN.** In `ConceptClassCensus.java`, add `private static final int TOP = 10;` above the compact constructor (deliberately private: a test that read it would assert the cut against itself), add `import java.util.Comparator;`, and replace the `top` assignment:

```java
    // A total order, so two runs over one unchanged log print one order and a diff between them is
    // never noise — ADR 43's byte-identical contract, held where the counting happens.
    Comparator<Map.Entry<String, Integer>> commonestFirst =
        Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue)
            .reversed()
            .thenComparing(Map.Entry::getKey);
    List<ConceptClass> top =
        byClass.entrySet().stream()
            .sorted(commonestFirst)
            .limit(TOP)
            .map(stated -> new ConceptClass(stated.getKey(), stated.getValue(), false))
            .toList();
```

- [ ] **Step 10 — run it and watch it pass.** Five green.

- [ ] **Step 11 — RED (the marker).** Add to `ConceptClassCensusTest`:

```java
  @Test
  @DisplayName("a class the whitelist knows is marked mapped, which the fold cannot produce")
  void shouldSayMappedWhenTheWhitelistKnowsTheClass() {
    // Built by hand rather than folded, and it has to be: KindMapper.fromInstanceOf skips a class
    // BY_CLASS does not know and every BY_CLASS entry maps to a kind other than CONCEPT, so no log
    // can fold to a CONCEPT node stating a mapped class. That is exactly why the marker is worth
    // printing — a row that reads mapped means the fold and the mapper have come apart.
    LogProjection byHand =
        new LogProjection(
            Map.of(A_NODE, new NodeRecord(A_NODE, NodeKind.CONCEPT, "an invented thing",
                List.of(HUMAN))),
            List.of(),
            0,
            0);

    assertThat(ConceptClassCensus.of(byHand).top())
        .containsExactly(new ConceptClass(HUMAN, 1, true));
  }
```

- [ ] **Step 12 — run it and watch it fail for the right reason.** Expect `expected: ConceptClass[classQid=Q5, nodes=1, mapped=true] but was: ConceptClass[classQid=Q5, nodes=1, mapped=false]`. Quote it.

- [ ] **Step 13 — GREEN.** In `ConceptClassCensus.java`, add `import com.robsartin.segue.wikidata.KindMapper;` and replace `false` in the `map` with `KindMapper.isMapped(stated.getKey())`.

- [ ] **Step 14 — run it and watch it pass.** Six green.

- [ ] **Step 15 — gate and commit.** `./gradlew spotlessApply`, then `SEGUE_REQUIRE_BROWSER=true SEGUE_REQUIRE_GRAPHVIZ=true ./gradlew check --rerun-tasks`, blocking. `git status`, then stage by explicit path:

```
git add src/main/java/com/robsartin/segue/census/ConceptClassCensus.java src/test/java/com/robsartin/segue/census/ConceptClassCensusTest.java src/test/java/com/robsartin/segue/arch/StandInQidsDenoteNothingTest.java
```

Commit: `Count CONCEPT nodes by the class they state (#248)`.

---

### Task 2: The seventh section in the report

**Files:** Modify `src/main/java/com/robsartin/segue/census/Census.java`, `src/main/java/com/robsartin/segue/census/CensusReport.java`, `src/test/java/com/robsartin/segue/census/CensusReportTest.java`.

Fast loop: `./gradlew test --tests 'com.robsartin.segue.census.*'`

- [ ] **Step 1 — RED, without touching the constructor.** In `CensusReportTest`, append three lines to the expected text block, after `  of those, carrying classes              1` and inside the same block. The fixture's one `CONCEPT` node is `NEIGHBOUR`, which states `InventedCensus.UNKNOWN_CLASS` (`Q0900301`) and nothing else, so the section is one row and two zero-or-one counts.

The alignment is `CensusReport`'s own rule applied by hand, not copied from a run: the widest label stays `  merges superseded but edge-referenced` (38 characters) and the widest count stays two digits, so **every line in the block is 42 characters** and no existing line moves.

```
            concept classes
              stating no class                       0
              distinct classes                       1
              class Q0900301 unmapped                1
```

`  stating no class` and `  distinct classes` are 18 characters, so 23 spaces then the digit; `  class Q0900301 unmapped` is 25, so 16 spaces then the digit.

- [ ] **Step 2 — run it and watch it fail for the right reason.** `./gradlew test --tests 'com.robsartin.segue.census.CensusReportTest'`. Expect a string-comparison failure whose diff ends at `bridge`: the four expected lines are absent from the actual. Quote the tail of the diff.

- [ ] **Step 3 — GREEN, part one: the component.** In `Census.java`, add the seventh component and its null check, and the seventh call in `of`:

```java
public record Census(
    NodeCensus nodes,
    EdgeCensus edges,
    ClaimCensus claims,
    TasteCensus taste,
    DegreeCensus degree,
    BridgeCensus bridge,
    ConceptClassCensus conceptClasses) {
```

```java
    Objects.requireNonNull(conceptClasses, "conceptClasses");
```

```java
        BridgeCensus.of(projection),
        ConceptClassCensus.of(projection));
```

Correct the two counts in its javadoc — `in the six sections it prints them in` becomes `in the seven sections it prints them in`, and `Fold once, read twice, count six ways.` becomes `count seven ways.` — and replace the **Aggregates and nothing else** paragraph, which stops being true:

```java
 * <p><b>Aggregates, and one identifier.</b> Every component is an integer or a map of integers,
 * with a single exception ruled on by ADR 63's 2026-09-04 amendment: {@link ConceptClassCensus}
 * carries the class qids that {@code CONCEPT} nodes state. A class id is vocabulary rather than an
 * entity — the standing the edge type codes and source ids already have — and it is the only value
 * here that is not a number. No label and no note reaches this type at all, which is what still
 * lets {@code CensusIsSafeToPasteTest} assert over the whole output rather than a filtered part of
 * it.
```

- [ ] **Step 4 — GREEN, part two: the block.** In `CensusReport.java`, append after the `bridge` block and before `return body;`:

```java
    ConceptClassCensus conceptClasses = census.conceptClasses();
    body.add(section("concept classes"));
    body.add(count("stating no class", conceptClasses.statingNoClass()));
    body.add(count("distinct classes", conceptClasses.distinctClasses()));
    for (ConceptClassCensus.ConceptClass stated : conceptClasses.top()) {
      body.add(
          count(
              "class " + stated.classQid() + (stated.mapped() ? " mapped" : " unmapped"),
              stated.nodes()));
    }
```

and replace the **Every label is a literal in this file** paragraph's last sentence so the third exception is named where the other two are:

```java
 * <p><b>Every label is a literal in this file.</b> Nothing here interpolates a value read from the
 * data except an integer and one identifier. The exceptions are the edge type codes and source
 * ids, which are vocabulary rather than entities and are covered by {@code
 * CensusIsSafeToPasteTest}'s "no Q-shaped token anywhere" clause, and the class qids in the
 * concept-classes rows, which ADR 63's 2026-09-04 amendment rules the same way and for which that
 * clause is narrowed to the {@code   class Q…} prefix this block owns. The word after the qid is a
 * literal chosen from a boolean, not text read off the data.
```

- [ ] **Step 5 — GREEN, part three: the fixture's constructor call.** In `CensusReportTest`, add the seventh argument to `new Census(...)`:

```java
            BridgeCensus.of(projection),
            ConceptClassCensus.of(projection));
```

and correct its javadoc's two `six section tests` to `seven section tests`.

- [ ] **Step 6 — run it and watch it pass.** `./gradlew test --tests 'com.robsartin.segue.census.*'` — the report test, and `CensusRunTest` and `CensusCliTest` which emit whatever the report returns.

- [ ] **Step 7 — gate and commit.** `./gradlew spotlessApply`, then the full gate, blocking. `CensusIsSafeToPasteTest` is expected to stay **green** here: its fixture claims one `WORK` with no classes, so the section prints no class row and nothing qid-shaped reaches the log. If it reds at this step, stop — that is a leak from somewhere this plan has not looked. `git status`, then:

```
git add src/main/java/com/robsartin/segue/census/Census.java src/main/java/com/robsartin/segue/census/CensusReport.java src/test/java/com/robsartin/segue/census/CensusReportTest.java
```

Commit: `Print the concept-classes section in the census report (#248)`.

---

### Task 3: Narrow the safe-to-paste guard to the section's own rows

**Files:** Modify `src/test/java/com/robsartin/segue/census/CensusIsSafeToPasteTest.java`.

Fast loop: `./gradlew test --tests 'com.robsartin.segue.census.CensusIsSafeToPasteTest'`

- [ ] **Step 1 — RED, and it is the old guard proving it was live.** Give the fixture a node that reaches the new section, and assert the section actually printed. Add the constants and the claim:

```java
  /** A class no whitelist knows, so the claim below re-derives to CONCEPT and reaches the rows. */
  private static final String A_CLASS = "Q0900302";
```

```java
      log.append(InventedCensus.node("Q0900901", NodeKind.WORK, LABEL));
      log.append(
          InventedCensus.node(
              "Q0900903", NodeKind.WORK, "Another Label Unlike Anything Real", List.of(A_CLASS)));
```

(`java.util.List` is already imported by this file — do not add it again; `spotlessApply` would
not save a duplicate), and add an anchor to the first assertion group:

```java
    assertThat(everyLine)
        .as(
            "the concept-classes section printed a class row — without this the narrowed clause"
                + " below is satisfied by a run that emitted no qid at all")
        .anyMatch(line -> line.startsWith("  class Q"));
```

- [ ] **Step 2 — run it and watch it fail for the right reason.** Expect the *existing* `\bQ\d+\b` assertion to fire on `  class Q0900302 unmapped                1` (the anchor above passes). Quote it: this is the evidence that the clause ADR 63's amendment narrows was not vacuous, and it is the only red this task gets from the code rather than from a plant.

- [ ] **Step 3 — GREEN: narrow the clause to one prefix.** Replace the `A_QID` constant's neighbourhood with the pattern pair and the predicate:

```java
  /** Anything qid-shaped at all, wherever it appears. */
  private static final Pattern A_QID = Pattern.compile("\\bQ\\d+\\b");

  /**
   * The one place ADR 63's 2026-09-04 amendment lets a qid stand: the head of a concept-classes
   * row. {@code CensusReport} indents every counted line by two spaces and puts the literal {@code
   * class } in front of the id, so no other line in the report can produce this prefix.
   */
  private static final Pattern A_CLASS_ROW = Pattern.compile("^  class Q\\d+");

  /**
   * Whether a line carries a qid the amendment does not allow it. Exactly one class-row prefix is
   * removed, once and only from the head; the unchanged clause is then applied to everything left,
   * so a second qid on an allowed row fires, a qid on any other line fires, and the section's own
   * words without its indent fire.
   */
  private static boolean carriesAnIdItMayNot(String line) {
    return A_QID.matcher(A_CLASS_ROW.matcher(line).replaceFirst("")).find();
  }
```

and change the last assertion to use it:

```java
    assertThat(everyLine)
        .as(
            "no line carries anything qid-shaped, wherever it came from — a label, a note, a source"
                + " id or an edge type code that turned out to look like an entity. The one"
                + " exception is a class id at the head of a concept-classes row (ADR 63,"
                + " amended 2026-09-04), and it is one prefix wide")
        .noneMatch(CensusIsSafeToPasteTest::carriesAnIdItMayNot);
  }
```

- [ ] **Step 4 — run it and watch it pass.** Green.

- [ ] **Step 5 — the three controls, as a test of their own.** Add:

```java
  @Test
  @DisplayName("the carve-out is one prefix wide: only a class id at the head of a class row passes")
  void shouldFireWhenAQidSitsAnywhereButTheHeadOfAClassRow() {
    assertThat(carriesAnIdItMayNot("  class Q0900302 unmapped                1"))
        .as("the row the amendment allows")
        .isFalse();
    assertThat(carriesAnIdItMayNot("  ratings                        Q0900901"))
        .as("an entity id on another section's line")
        .isTrue();
    assertThat(carriesAnIdItMayNot("  class Q0900302 unmapped  Q0900901"))
        .as("a second id smuggled onto an allowed row")
        .isTrue();
    assertThat(carriesAnIdItMayNot("class Q0900302 unmapped  1"))
        .as("the section's own words without the indent no other line can fake")
        .isTrue();
  }
```

- [ ] **Step 6 — run it and watch it pass.** Green. It is a guard, so passing proves nothing yet; Step 7 is its evidence.

- [ ] **Step 7 — positive controls: plant each defect, watch the check fire, remove the plant.** One at a time, re-running `./gradlew test --tests 'com.robsartin.segue.census.CensusIsSafeToPasteTest'` after each edit and after each revert. Quote each failure in the report.

  - [ ] **Plant A — widen the prefix past the section.** `Pattern.compile("^  \\w+\\s+Q\\d+")`. Expect the *entity id on another section's line* assertion to fail (`Expecting value to be true but was false`), because `  ratings   Q0900901` now looks like an allowed head. Revert.
  - [ ] **Plant B — allow the whole row.** Make the predicate short-circuit: `if (line.startsWith("  class")) { return false; }` as its first statement. Expect the *second id smuggled onto an allowed row* assertion to fail. Revert. This is the plant that says the carve-out did not widen into a hole.
  - [ ] **Plant C — drop the anchor.** `Pattern.compile("  class Q\\d+")` without `^`, or `Pattern.compile("class Q\\d+")`. Expect the *without the indent* assertion to fail. Revert.
  - [ ] After the third revert, run once more and confirm all of `CensusIsSafeToPasteTest` is green with no plant left in the file — `git diff` the file and read it.

- [ ] **Step 8 — gate and commit.** `./gradlew spotlessApply`, then the full gate, blocking. `git status`, then:

```
git add src/test/java/com/robsartin/segue/census/CensusIsSafeToPasteTest.java
```

Commit: `Narrow the safe-to-paste clause to the concept-classes rows (#248)`.

---

### Task 4: ADR 63's amendment, and the developer guide

**Files:** Modify `docs/adr/0063-a-read-only-census-of-the-graph.md`, `docs/developer-guide.md`.

**Verification, said out loud:** neither file carries unit-testable behaviour, so there is no red for this task. It is verified by the full `check` gate — `AdrIndexTest` over the ADR index, the doc-link test over every link added below, and `DeveloperGuideEnumerationsTest` over the guide's derived tables (nothing here changes a package, an import edge or an ArchUnit rule, so no row of those moves) — plus reading the amendment against `ConceptClassCensus` and `CensusIsSafeToPasteTest` as they now stand. Run the gate and say which checks covered it.

- [ ] **Step 1 — the ADR amendment.** ADR 63 is immutable; this is a dated amendment. Insert it at the end of the subsection *"Every value is an integer, and that is what makes ADR 51 testable here"*, immediately after the paragraph beginning **"This does not overturn ADR 51 or narrow it."**:

```markdown
**Amendment (2026-09-04, issue #248): the "nothing matching `\bQ\d+\b` anywhere" clause above is
narrowed by one prefix, because a class identifier is vocabulary.** The census gained a section
counting `CONCEPT` nodes by the class they state — the map of `KindMapper`'s gaps, which nothing
reported and which 17,099 `CONCEPT` nodes make worth having. Printing counts against ranks and
leaving the owner to look the classes up was the alternative, and it fails on use: a rank is not
something you can look anything up by, so the section would answer a question nobody could act on
without a second tool.

**The ruling is that a Wikidata class id is vocabulary rather than the owner's data.** That is not
new ground: this decision already admits two kinds of raw text off the log on exactly that basis,
the edge type codes in `of type …` and the source ids in `backed by …`. A class id is the same kind
of thing — Wikidata's shared name for a category, stated by a source about an entity — and it
identifies no entity in the owner's graph. What the row adds to it is a count, which is an
aggregate. ADR 51's line is where that lands, and ADR 51 itself is untouched: it remains the rule
for prose, held by review.

**The carve-out is one prefix wide, and that is enforced rather than intended.**
`CensusIsSafeToPasteTest` strips exactly one leading `^  class Q\d+` and applies the unchanged
clause to what is left, so a second qid on an allowed row fires, a qid on any other line fires, and
a line carrying the section's words without the two-space indent `CensusReport` gives every counted
line fires. Three planted lines assert all three, and each is proved able to fail by a matching
plant in the guard — widening the prefix past the section, short-circuiting the whole row, and
dropping the anchor. The test also asserts that a class row was actually printed, so the narrowed
clause can never be satisfied by a run that emitted no id at all. `EvaluationIsSafeToPasteTest`'s
own clause is a separate guard over a separate report and is **not** narrowed.

**The residual, stated rather than mitigated.** A class stated by exactly one node is the row that
comes closest to naming an entity. The section prints the ten commonest classes, so ordering by
count descending is what pushes such a row out — that is the load-bearing reason for the cut, ahead
of brevity — but on a graph small enough that ten rows is the whole distribution, a count of one
can still reach the output. Nothing hides it, and the answer is this decision's own: `--db` is typed
per invocation because whether to publish is the owner's decision, taken each time.

**What was rejected.** Printing a label beside the qid from `ClassLabels`: its fallback prints the
bare qid, so on exactly the classes this section exists to surface — the ones nobody has met — it
would print the qid anyway and add a column of blanks, while putting a curated English string into
an output whose guarantee is that it interpolates nothing but integers and one identifier. And
suppressing rows below a minimum count: it would hide the residual above rather than report it, and
it would make `distinct classes` the only honest number in the section.
```

- [ ] **Step 2 — the guide: what the section is for.** In *"Looking at the shape of your graph"*, under **"What it is for"**, the three bullets are the three open questions. Append a fourth, after the MusicBrainz bullet:

```markdown
- which Wikidata classes the `CONCEPT` nodes are wearing, which is the map of `KindMapper`'s gaps —
  `fromInstanceOf` answers `CONCEPT` for any class its whitelist has never met, so an unknown share
  of those nodes are people, groups, works or places. The `concept classes` section counts them, and
  which classes then deserve a rule is a separate issue that only a real reading can open.
```

- [ ] **Step 3 — the guide: why it is still safe to paste.** In the same chapter, under **"Why the output is safe to paste"**, replace the first sentence of the first paragraph so the third exception is named where the other two are:

```markdown
Every value is an integer, and every label is a literal in `CensusReport` but for three it reads off
the log — the edge type codes and the source ids, in `of type …` and `backed by …`, and the class
qids in the `concept classes` rows. All three are vocabulary rather than entities. The first two are
covered by the test's "nothing `Q`-shaped anywhere" clause; the third is the one exception to it,
narrowed to the `  class Q…` prefix that only those rows can produce, with three planted lines
asserting that a qid anywhere else — including a second one on an allowed row — still fires. See
[ADR 63](adr/0063-a-read-only-census-of-the-graph.md)'s 2026-09-04 amendment.
```

Leave the rest of that paragraph (the note about labels, notes and what `CensusIsSafeToPasteTest`
feeds it) as it stands.

- [ ] **Step 4 — the guide: the package table.** In the *"What each package is for"* table, the `census` row lists what the tool counts. Add the section to that list, after `and what MusicBrainz reached`:

```markdown
, and the classes its `CONCEPT` nodes state
```

and change `the whole output is aggregates — no label, no id, no note —` to `the whole output is aggregates and class ids — no label, no note, no entity id —` so the row does not contradict the amendment.

- [ ] **Step 5 — gate and commit.** `SEGUE_REQUIRE_BROWSER=true SEGUE_REQUIRE_GRAPHVIZ=true ./gradlew check --rerun-tasks`, blocking. `git status`, then:

```
git add docs/adr/0063-a-read-only-census-of-the-graph.md docs/developer-guide.md
```

Commit: `Rule that a class id is vocabulary, and document the new section (#248)`.

---

## What this plan deliberately does not do

- **No `KindMapper` entry.** Which classes deserve a rule is the follow-up issue, and it needs the
  owner's real reading first. Anything added here would be the speculation the whitelist's own
  javadoc warns against.
- **No `--top` flag, threshold, label column, or second section for classes the whitelist knows.**
  Each is a decision the first reading should inform.
- **No edit to `InventedCensus.log()` and no edit to another section's report lines**, so this
  branch and issue #247's meet in `CensusReport.body` and `CensusReportTest`'s block at appended
  lines rather than at a reflow.
