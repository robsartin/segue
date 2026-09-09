# Pin biographical article and discography at CONCEPT — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Pin `Q19389637` (biographical article) and `Q273057` (discography) at `CONCEPT` in
`KindMapperTest`, name `biographical article` in `ClassLabels`, and name issue #300 in the
`KindMapper` table comment that already says a reading added no rule. **No `KindMapper` rule is
added**, so `rederive` (ADR 42) moves no node at the next boot.

**Architecture:** Exactly issue #294's shape
(`2026-09-08-pin-two-wikimedia-page-classes.md`), minus the `ClassLabels` name that already
exists and minus the developer-guide repair #294 already made. The design is
`2026-09-08-pin-biographical-article-and-discography-design.md` in `docs/superpowers/specs`; read it
first, especially "Where the issue's premise had to be checked against the code" and "Why a pin
needs a planted control".

**Tech Stack:** Java 25, JUnit 5, AssertJ, Gradle (`./gradlew`, JDK 25 is the only JDK).

## Global Constraints

- Issue #300 is the spec. Its table holds the two rulings and the two labels, quoted live from
  Wikidata on 2026-09-08. Use those strings exactly; look nothing up, guess nothing, invent no
  identifier, and make no network call.
- **A pin's RED is planted.** For each pin: put the wrong ruling into `KindMapper`'s static block,
  write the pin, run it, quote the assertion failure, remove the plant, run green, and confirm `git
  diff --stat src/main` prints nothing before the commit. A pin that has only ever been green is not
  a tested pin. A compile error or an `IllegalStateException` from `put`'s duplicate check is **not**
  a red — plant only an id `BY_CLASS` does not already hold, and both of these qualify (checked:
  neither `Q19389637` nor `Q273057` appears in `KindMapper.java`).
- **Every red in this plan is a real assertion failure**, and the expected text is written into the
  step. Two per pin task: the pin itself, and the stand-in fence on the undeclared site. Quote every
  one verbatim in the report; do not paraphrase and do not report a red you did not see.
- `StandInQidsDenoteNothingTest.ALLOWED` holds **no hash** — an entry is a reason plus `code("<path>")`
  sites, written by hand. (The `(file, hash)` allowlist is `AdrCitationsTest`'s, for commit citations
  in `docs/adr/`, and nothing here touches it.) Never recompute anything; never widen the fence past
  the site just sighted.
- The fence reds in both directions, so **an entry may not be written ahead of its sighting**: each
  pin task registers its id at `KindMapperTest` only, and Task 3 adds the `ClassLabelsTest` site to
  `Q19389637` in the same commit that first cites it there. `Q273057` keeps one site for good.
- **`Q273057` is already named in `ClassLabels`** (`put("Q273057", "discography");`, in the concepts
  block) and has no allowlist entry and no `ClassLabelsTest` test — the fence sweeps `src/test` only.
  So this issue gives it a pin and a fresh one-site entry, and no label work at all. Do not add a
  `ClassLabelsTest` test for it: the label is already there, so such a test could never be seen red.
- Test names `should<Expected>When<Condition>` with a `@DisplayName`. One assertion per class, one
  test per class.
- Never run `own`, `ownClaim`, `retractEntity`, `resolveNames`, `rate`, `graphCensus`, `evaluate`,
  `expand` or any seeding or writing dev task; never read, write, copy, open or create
  `~/.segue/segue.db`.
- No figure from the owner's graph in a committed file (the census reading lives on issue #297). No
  `.superpowers/` path in a committed file. No ADR touched, so no commit hash under `docs/adr`.
  Nothing under `docs/` is edited by the implementer at all: `docs/developer-guide.md` is untouched
  (issue #294 already removed the drifting `ClassLabels` count), and the only files there this
  branch adds are this plan and its design spec, committed before implementation begins.
- Stage by explicit path, git stderr visible, never `git add -A`. Commit messages end with a blank
  line then `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`.
- Full gate, BLOCKING, at the end: `SEGUE_REQUIRE_BROWSER=true SEGUE_REQUIRE_GRAPHVIZ=true ./gradlew
  check --rerun-tasks`. Count tests once from `build/test-results/test/*.xml`. If it reports
  formatting, `./gradlew spotlessApply` and fold the result into the commit it belongs to.

---

## Task 1: Pin `Q19389637` "biographical article" at CONCEPT

**Files:** test `src/test/java/com/robsartin/segue/wikidata/KindMapperTest.java`; allowlist
`src/test/java/com/robsartin/segue/arch/StandInQidsDenoteNothingTest.java`;
`src/main/java/com/robsartin/segue/wikidata/KindMapper.java` **only while the plant is in**,
reverted before the commit.

- [ ] **Step 1: Plant the wrong ruling.** In `KindMapper`'s static block, immediately after
      `put("Q7889", NodeKind.WORK); // video game` and before the `// The third reading (issue #294`
      comment, add:

```java
    put("Q19389637", NodeKind.WORK); // PLANTED positive control — remove before committing
```

- [ ] **Step 2: Write the pin** in `KindMapperTest`, immediately after the closing brace of
      `shouldStayConceptWhenTheClassIsAnEncyclopediaArticle` and before
      `shouldMapToWorkWhenTheClassIsSingleRelease`:

```java
  @Test
  @DisplayName("a biographical article stays CONCEPT: it is the entry, not the person")
  void shouldStayConceptWhenTheClassIsABiographicalArticle() {
    // Issue #300, the class pass below issue #294's. Wikidata describes this class as an "article
    // in a dictionary or encyclopedia": it is an encyclopedia article's sibling, and #294 pinned
    // that parent for this reason. The person the entry is about is the entity worth recommending
    // and usually has a node of its own, so mapping the entry to WORK would offer a reference page
    // as something to explore and would take a high-degree hub out of the reach of the rule that
    // demotes routes through one (ADR 31, amended by issue #52). Label and description confirmed
    // live on 2026-09-08, on the issue.
    assertThat(KindMapper.fromInstanceOf(List.of("Q19389637"))) // biographical article
        .isEqualTo(NodeKind.CONCEPT);
  }
```

- [ ] **Step 3: Run it and observe the planted red.** `./gradlew test --tests '*KindMapperTest'` —
      FAIL, one test, with

```
expected: CONCEPT
 but was: WORK
```

  Quote the failure verbatim in the report. If instead the build fails to compile, or throws
  `IllegalStateException: two kinds claim Q19389637`, that is **not** the red: fix the cause and
  re-run until the assertion itself fires.

- [ ] **Step 4: Remove the plant.** Delete the line added in Step 1. Confirm with `git diff --stat
      src/main` — it must print nothing.
- [ ] **Step 5: Run the pin green.** `./gradlew test --tests '*KindMapperTest'` — PASS.
- [ ] **Step 6: Run the fence and observe the second red.** `./gradlew test --tests
      '*StandInQidsDenoteNothingTest'` — FAIL, because a real Wikidata id now sits in a test file
      with no allowlist entry. The failure carries the long `as(...)` description followed by the
      sighting, of the shape

```
Expecting empty but was: "src/test/java/com/robsartin/segue/wikidata/KindMapperTest.java:<line>  Q19389637"
```

  Quote it verbatim, with the real line number the run printed. `shouldCarryNoDeadSiteWhen…` stays
  green here — nothing has been added to `ALLOWED` yet.

- [ ] **Step 7: Register the id.** In `ALLOWED`, keys run by digit count then numerically (a
      convention, not an asserted rule — keep it anyway). `Q19389637` is an eight-digit id and goes
      **between the `Q19351429` entry and the `Q19863965` entry**:

```java
          entry(
              "Q19389637",
              real(
                  "class id — deliberately left unmapped by KindMapper, pinned at CONCEPT by"
                      + " KindMapperTest (issue #300)",
                  code("src/test/java/com/robsartin/segue/wikidata/KindMapperTest.java"))),
```

- [ ] **Step 8: Run both green.** `./gradlew test --tests '*KindMapperTest' --tests
      '*StandInQidsDenoteNothingTest'` — PASS. `git diff --stat src/main` still prints nothing.
- [ ] **Step 9: Commit** the two test files by explicit path:
      `Pin biographical article at CONCEPT (#300)`

## Task 2: Pin `Q273057` "discography" at CONCEPT

**Files:** the same two test files; `KindMapper.java` only while the plant is in.

`Q273057` is already named in `ClassLabels`, in `src/main`, which the fence does not sweep — so this
is still its first sighting in `src/test` and it still needs a brand-new entry, not a widened one.

- [ ] **Step 1: Plant the wrong ruling.** In `KindMapper`'s static block, in the same place Task 1
      used:

```java
    put("Q273057", NodeKind.WORK); // PLANTED positive control — remove before committing
```

- [ ] **Step 2: Write the pin** in `KindMapperTest`, immediately after
      `shouldStayConceptWhenTheClassIsABiographicalArticle`:

```java
  @Test
  @DisplayName("a discography stays CONCEPT: it is the catalogue, not the recordings")
  void shouldStayConceptWhenTheClassIsADiscography() {
    // Issue #300. Wikidata describes this class as the "study and cataloging of published sound
    // recordings" — a discipline, and a catalogue OF releases rather than a release. It is the
    // class the Wikimedia artist-discography pages #294 pinned belong to, so promoting it would
    // undo that pin one level up. Many works and people point at a catalogue and nobody did
    // anything with it, which is the hub shape the CONCEPT-gated rules exist to demote (ADR 31,
    // amended by issue #52). Label and description confirmed live on 2026-09-08, on the issue.
    assertThat(KindMapper.fromInstanceOf(List.of("Q273057"))) // discography
        .isEqualTo(NodeKind.CONCEPT);
  }
```

- [ ] **Step 3: Run and observe the planted red.** `./gradlew test --tests '*KindMapperTest'` — FAIL
      with

```
expected: CONCEPT
 but was: WORK
```

  Quote it verbatim. It must be the assertion, not a load-time throw.

- [ ] **Step 4: Remove the plant.** `git diff --stat src/main` prints nothing.
- [ ] **Step 5: Run the pin green.** `./gradlew test --tests '*KindMapperTest'` — PASS.
- [ ] **Step 6: Run the fence and observe the second red.** `./gradlew test --tests
      '*StandInQidsDenoteNothingTest'` — FAIL naming the new sighting, of the shape

```
Expecting empty but was: "src/test/java/com/robsartin/segue/wikidata/KindMapperTest.java:<line>  Q273057"
```

  Quote it verbatim. If the run reports no failure at all, stop: it would mean the id was already
  allowlisted, which contradicts the reading this plan was written from, and the entry below must
  not be duplicated.

- [ ] **Step 7: Register the id.** `Q273057` is a six-digit id and goes **between the `Q255032`
      entry and the `Q277308` entry**:

```java
          entry(
              "Q273057",
              real(
                  "class id — already named by ClassLabels, deliberately left unmapped by"
                      + " KindMapper, pinned at CONCEPT by KindMapperTest (issue #300)",
                  code("src/test/java/com/robsartin/segue/wikidata/KindMapperTest.java"))),
```

- [ ] **Step 8: Run both green.** `./gradlew test --tests '*KindMapperTest' --tests
      '*StandInQidsDenoteNothingTest'` — PASS. `git diff --stat src/main` still prints nothing.
- [ ] **Step 9: Commit** the two test files: `Pin discography at CONCEPT (#300)`

## Task 3: Name `biographical article` in `ClassLabels`

**Files:** `src/main/java/com/robsartin/segue/support/ClassLabels.java`;
`src/test/java/com/robsartin/segue/support/ClassLabelsTest.java`;
`src/test/java/com/robsartin/segue/arch/StandInQidsDenoteNothingTest.java`.

One name, not two: `discography` is already in the table. This is the first commit to cite
`Q19389637` in `ClassLabelsTest`, so it is the commit that adds the second site to that entry — not
earlier, or `shouldCarryNoDeadSiteWhenTheAllowlistIsCheckedAgainstTheTree` reds on a site the tree
does not yet carry. `Q273057`'s entry is not touched.

- [ ] **Step 1: Write the failing test** at the end of `ClassLabelsTest`, after
      `shouldLabelEncyclopediaArticleWhenTheClassIsQ13433827`:

```java
  @Test
  @DisplayName("biographical article has a label, confirmed live 2026-09-08 (issue #300)")
  void shouldLabelBiographicalArticleWhenTheClassIsQ19389637() {
    assertThat(ClassLabels.label("Q19389637")).isEqualTo("biographical article");
  }
```

- [ ] **Step 2: Run and observe the red.** `./gradlew test --tests '*ClassLabelsTest'` — FAIL, one
      test, with the fallback returning the bare qid:

```
expected: "biographical article"
 but was: "Q19389637"
```

  Quote it verbatim.

- [ ] **Step 3: Add the name**, at the end of the concepts block in `ClassLabels`, after
      `put("Q13433827", "encyclopedia article");`:

```java
    // Issue #300's one new name, from the class pass below issue #294's: a biographical article is
    // a dictionary or encyclopedia entry about a person, so it is the entry and not the person, and
    // KindMapperTest pins it at CONCEPT the way it pins #294's two. That pass's other class,
    // discography, is named in the award block above already and needed only the pin.
    put("Q19389637", "biographical article");
```

- [ ] **Step 4: Run green.** `./gradlew test --tests '*ClassLabelsTest'` — PASS, including
      `isAWellFormedTable` and `namesEachClassOnce` (no existing value is `biographical article`;
      the nearest are `encyclopedia article` and `discography`, both distinct).
- [ ] **Step 5: Run the fence and observe the red on the new site.** `./gradlew test --tests
      '*StandInQidsDenoteNothingTest'` — FAIL. This red differs in shape from the two pin tasks':
      the id now HAS an entry, naming a different file, so `report` appends the sites it is allowed
      at:

```
Expecting empty but was: "src/test/java/com/robsartin/segue/support/ClassLabelsTest.java:<line>  Q19389637  — allowed, but only at src/test/java/com/robsartin/segue/wikidata/KindMapperTest.java (in code)"
```

  Quote it verbatim, with the real line number. This is the guard the design describes: an id
  allowed at one file is not allowed at another until the entry says so.

- [ ] **Step 6: Add the second site to `Q19389637`'s entry** written in Task 1, and widen its reason
      to say the table now names it. Leave `Q273057`'s entry exactly as Task 2 wrote it:

```java
          entry(
              "Q19389637",
              real(
                  "class id — named by ClassLabels, deliberately left unmapped by KindMapper,"
                      + " pinned at CONCEPT by KindMapperTest (issue #300)",
                  code("src/test/java/com/robsartin/segue/wikidata/KindMapperTest.java"),
                  code("src/test/java/com/robsartin/segue/support/ClassLabelsTest.java"))),
```

- [ ] **Step 7: Run green.** `./gradlew test --tests '*ClassLabelsTest' --tests
      '*StandInQidsDenoteNothingTest' --tests '*KindMapperTest'` — PASS.
- [ ] **Step 8: Commit** the three files: `Name biographical article in the class table (#300)`

## Task 4: Name issue #300 in the table's no-rule note, then the gate

**Files:** `src/main/java/com/robsartin/segue/wikidata/KindMapper.java`.

A comment, with no behaviour to test — the honest exception, stated out loud so nobody reads a
test-after into it. It is verified by another explicit method: the full gate below (compilation,
`spotless`, and every test that reads this class), plus re-reading the diff to confirm it names no
class id. The reason it is worth a commit: #294's sentence is the only trace in the table that a
class pass deliberately refused to add a rule, and an issue that adds no `put` leaves no other. One
clause keeps that sentence true for #300 instead of letting a reader think #294 was the last pass to
refuse anything.

- [ ] **Step 1: Edit the sentence.** Replace the comment block currently reading

```java
    // The third reading (issue #294, after the first expander run) added no rule. The two classes
    // at the head of its CONCEPT list are Wikimedia pages about a thing rather than the thing, so
    // they stay CONCEPT on purpose and KindMapperTest pins them, the way it pins the character
    // classes. The ids are in the test, not here: this table is for what a class MEANS, and a
    // second copy of the list would be the one a later editor forgets.
```

  with

```java
    // The third reading (issue #294, after the first expander run) added no rule, and the pass
    // below it (issue #300) added none either. The classes at the head of those CONCEPT lists are
    // pages and catalogues about a thing rather than the thing, so they stay CONCEPT on purpose and
    // KindMapperTest pins them, the way it pins the character classes. The ids are in the test, not
    // here: this table is for what a class MEANS, and a second copy of the list would be the one a
    // later editor forgets.
```

- [ ] **Step 2: Verify.** `./gradlew test --tests '*KindMapperTest' --tests
      '*StandInQidsDenoteNothingTest'` — PASS. Read `git diff
      src/main/java/com/robsartin/segue/wikidata/KindMapper.java` and confirm it changes comment
      lines only: no `put`, no class id, and `BY_CLASS` unchanged.
- [ ] **Step 3: Commit**: `Say in the table that the pass below #294 added no rule either (#300)`
- [ ] **Step 4: Full gate, BLOCKING:** `SEGUE_REQUIRE_BROWSER=true SEGUE_REQUIRE_GRAPHVIZ=true
      ./gradlew check --rerun-tasks`. BUILD SUCCESSFUL; count tests once from
      `build/test-results/test/*.xml`. No new commit unless `spotlessApply` had to run, in which
      case fold the formatting into the commit that introduced the line.
- [ ] **Step 5: Report** every red observed, quoted verbatim: two planted pin failures, three
      stand-in fence failures (two bare sightings, one "allowed, but only at"), one label failure.
      Confirm `git diff origin/main --stat` shows exactly the five source files this plan names —
      `KindMapperTest.java`, `StandInQidsDenoteNothingTest.java`, `ClassLabelsTest.java`,
      `ClassLabels.java`, `KindMapper.java` — plus this plan and its design spec from the planning
      commit, and nothing else. Confirm that `KindMapper`'s
      `BY_CLASS` gained no entry, so the next boot's `rederive` moves nothing, which is the whole
      point of the issue. Then post the closing comment the issue asks for, with the next census
      reading, on the issue rather than in a committed file.
