# Map the three work classes among the second reading's entrants — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give `KindMapper` a rule for single release, comic book issue and video game, pin the three fictional-character classes as CONCEPT beside fictional human, and name any of the six `ClassLabels` lacks.

**Architecture:** Same shape as issue #261's plan (`2026-09-05-map-the-ten-concept-classes.md`), a third of the size: one `put` line per rule, RED first with an invented node stating only that class; `KindMapper.rederive` (ADR 42) carries each rule to every node at the next boot. No ADR amendment: no decision changes.

**Tech Stack:** Java 25, JUnit 5, AssertJ, Gradle (`./gradlew`, JDK 25 is the only JDK).

## Global Constraints

- Issue #265 is the spec; its table holds the rulings and the labels, quoted live from Wikidata on 2026-09-05. Q108352496, Q140727568, Q7889 → `WORK`; Q15773347, Q15773317, Q15711870 stay `CONCEPT`.
- Every rule is RED first: the test runs and fails with `expected: WORK but was: CONCEPT` before the `put` line exists. A compile error is not a red. The report quotes each failure.
- Every pin gets a planted positive control: temporarily register the class with a wrong kind (a QID not already in the table — a duplicate registration throws at class load, which is not the failure you want), watch the pin fail, remove the plant, `git diff src/main` empty before the pin commit. The report quotes each failure.
- Test names `should<Expected>When<Condition>` with `@DisplayName`; `{@code}` citations on one line.
- Never run `own`, `ownClaim`, `retractEntity`, `resolveNames`, `rate`, `graphCensus`, `evaluate` or any seeding task; never read, write, copy, open or create `~/.segue/segue.db`; no network.
- No figure from the owner's graph in a committed file (the counts live on issue #265). No `.superpowers/` path in a committed file.
- Real class ids in a test file must be registered in `StandInQidsDenoteNothingTest`'s allowlist naming exactly the files each appears in (see how #261's ids are registered there); do it in the same commit as the sighting.
- Stage by explicit path, git stderr visible, never `git add -A`. Commit messages end with a blank line then `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`.
- Full gate, BLOCKING, at the end: `SEGUE_REQUIRE_BROWSER=true SEGUE_REQUIRE_GRAPHVIZ=true ./gradlew check --rerun-tasks`; count tests once from `build/test-results/test/*.xml`; if it reports formatting, `./gradlew spotlessApply` and fold into the right commit.

---

## Task 1: Q108352496 "single release" → WORK

**Files:** modify `src/main/java/com/robsartin/segue/wikidata/KindMapper.java` (the `// works` block, under the issue-#261 comment); test `src/test/java/com/robsartin/segue/wikidata/KindMapperTest.java`; allowlist `src/test/java/com/robsartin/segue/arch/StandInQidsDenoteNothingTest.java`.

- [ ] **Step 1: Write the failing test** after the last #261 rule test:

```java
  @Test
  @DisplayName("a single release is a WORK")
  void shouldMapToWorkWhenTheClassIsSingleRelease() {
    // The second census reading (issue #265, 2026-09-05). A release of a single is WORK the way
    // single (Q134556) and the seven-inch single (Q6128115) already are. Label and description
    // confirmed live before this line was written.
    assertThat(KindMapper.fromInstanceOf(List.of("Q108352496"))) // single release
        .isEqualTo(NodeKind.WORK);
  }
```

- [ ] **Step 2: Run** `./gradlew test --tests '*KindMapperTest'` — FAIL `expected: WORK but was: CONCEPT`. Quote it.
- [ ] **Step 3: Add the rule** under a new one-line comment in the works block: `// Issue #265, the second census reading: three more works.` then `put("Q108352496", NodeKind.WORK); // single release`
- [ ] **Step 4: Run** the class and `*StandInQidsDenoteNothingTest`: PASS (register the id's sighting if the fence asks).
- [ ] **Step 5: Commit**: `Map single release to WORK (#265)`

## Task 2: Q140727568 "comic book issue" → WORK

- [ ] **Step 1: Write the failing test**:

```java
  @Test
  @DisplayName("a comic book issue is a WORK")
  void shouldMapToWorkWhenTheClassIsComicBookIssue() {
    // An issue of a published comic: a published work, the way book (Q571) is. Issue #265.
    assertThat(KindMapper.fromInstanceOf(List.of("Q140727568"))) // comic book issue
        .isEqualTo(NodeKind.WORK);
  }
```

- [ ] **Step 2: Run** — FAIL `expected: WORK but was: CONCEPT`. Quote it.
- [ ] **Step 3: Add** `put("Q140727568", NodeKind.WORK); // comic book issue`
- [ ] **Step 4: Run**: PASS (allowlist as needed).
- [ ] **Step 5: Commit**: `Map comic book issue to WORK (#265)`

## Task 3: Q7889 "video game" → WORK

- [ ] **Step 1: Write the failing test**:

```java
  @Test
  @DisplayName("a video game is a WORK")
  void shouldMapToWorkWhenTheClassIsVideoGame() {
    // Its own direct parent, audiovisual work (Q2431196), is already WORK; the table does not
    // walk P279, so the class needs its own line. Issue #265.
    assertThat(KindMapper.fromInstanceOf(List.of("Q7889"))) // video game
        .isEqualTo(NodeKind.WORK);
  }
```

- [ ] **Step 2: Run** — FAIL `expected: WORK but was: CONCEPT`. Quote it.
- [ ] **Step 3: Add** `put("Q7889", NodeKind.WORK); // video game`
- [ ] **Step 4: Run**: PASS (allowlist as needed).
- [ ] **Step 5: Commit**: `Map video game to WORK (#265)`

## Task 4: Pin the three character classes as CONCEPT

- [ ] **Step 1: Add one test** beside `shouldStayConceptWhenTheClassIsFictionalHuman`:

```java
  @Test
  @DisplayName("film, television and animated characters stay CONCEPT, as fictional human does")
  void shouldStayConceptWhenTheClassIsAFictionalCharacter() {
    // Issue #265 turns issue #261's fictional-human ruling into a family rule: a character shared
    // by several works is a subject those works have in common, not something anyone did, and
    // that is the hub shape the CONCEPT-gated rules exist to demote.
    assertThat(KindMapper.fromInstanceOf(List.of("Q15773347"))) // film character
        .isEqualTo(NodeKind.CONCEPT);
    assertThat(KindMapper.fromInstanceOf(List.of("Q15773317"))) // television character
        .isEqualTo(NodeKind.CONCEPT);
    assertThat(KindMapper.fromInstanceOf(List.of("Q15711870"))) // animated character
        .isEqualTo(NodeKind.CONCEPT);
  }
```

- [ ] **Step 2: Run**: PASS — these pin behaviour that already holds; say so in the report.
- [ ] **Step 3: Plant** `put("Q15773347", NodeKind.PERSON);` in `BY_CLASS`, run, quote `expected: CONCEPT but was: PERSON`; remove; repeat once for `Q15711870` with `NodeKind.WORK`; remove; `git diff src/main` empty; run PASS.
- [ ] **Step 4: Commit** (test + allowlist): `Pin the character classes as CONCEPT (#265)`

## Task 5: ClassLabels

- [ ] **Step 1:** `grep -n 'Q15773347\|Q108352496\|Q140727568\|Q15773317\|Q7889\|Q15711870' src/main/java/com/robsartin/segue/support/ClassLabels.java`. For each id missing, RED first in `ClassLabelsTest` (the pattern #261 used: assert the label, see `expected: "<label>" but was: "<qid>"`), then add the `put` with the label exactly as issue #265's table quotes it, under the section comment that is true of it (works with works, characters with the concepts). Quote each failure.
- [ ] **Step 2:** Run `*ClassLabelsTest` and `*StandInQidsDenoteNothingTest`: PASS. Commit: `Name the second reading's six classes (#265)`

## Task 6: Gate

- [ ] `SEGUE_REQUIRE_BROWSER=true SEGUE_REQUIRE_GRAPHVIZ=true ./gradlew check --rerun-tasks`, BLOCKING. BUILD SUCCESSFUL; count tests once. No commit unless formatting had to be applied (fold into the right commit).
