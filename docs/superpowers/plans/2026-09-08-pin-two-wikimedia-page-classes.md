# Pin the two Wikimedia page classes the expansion brought into the top ten — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Pin `Q104635718` (Wikimedia artist discography) and `Q13433827` (encyclopedia article) at `CONCEPT` in `KindMapperTest`, name both in `ClassLabels`, and say in `KindMapper`'s table why issue #294 added no rule. **No `KindMapper` rule is added**, so `rederive` (ADR 42) moves no node at the next boot.

**Architecture:** Same shape as issue #265's plan (`2026-09-05-second-pass-on-concept-classes.md`), minus every mapping task. The design is `2026-09-08-pin-two-wikimedia-page-classes-design.md` in `docs/superpowers/specs`; read it first, especially "Where the issue's premise is looser than the code" and "Why a pin needs a planted control".

**Tech Stack:** Java 25, JUnit 5, AssertJ, Gradle (`./gradlew`, JDK 25 is the only JDK).

## Global Constraints

- Issue #294 is the spec. Its table holds the two rulings and the two labels, quoted live from Wikidata on 2026-09-08. Use those strings exactly; look nothing up, guess nothing, and invent no identifier.
- **A pin's RED is planted.** For each pin: put the wrong ruling into `KindMapper`'s static block, write the pin, run it, quote the assertion failure, remove the plant, run green, and confirm `git diff src/main` is empty before committing. A pin that has only ever been green is not a tested pin. A compile error or an `IllegalStateException` from `put`'s duplicate check is **not** a red — plant only an id `BY_CLASS` does not already hold, and both of these qualify.
- **Every red in this plan is a real assertion failure.** Two per pin task: the pin itself, and the stand-in fence on the undeclared site. Quote both verbatim in the report; do not paraphrase and do not report a red you did not see.
- `StandInQidsDenoteNothingTest.ALLOWED` holds **no hash** — an entry is a reason plus `code("<path>")` sites, written by hand. (The `(file, hash)` allowlist is `AdrCitationsTest`'s, for commit citations in `docs/adr/`, and nothing here touches it.) Never recompute anything; never widen the fence.
- The fence reds in both directions, so **an entry may not be written ahead of its sighting**: each pin task registers its id at `KindMapperTest` only, and Task 3 adds the `ClassLabelsTest` site in the same commit that first cites the id there.
- Test names `should<Expected>When<Condition>` with a `@DisplayName`. One assertion per class, one test per class.
- Never run `own`, `ownClaim`, `retractEntity`, `resolveNames`, `rate`, `graphCensus`, `evaluate`, `expand` or any seeding or writing dev task; never read, write, copy, open or create `~/.segue/segue.db`; no network.
- No figure from the owner's graph in a committed file (the census reading lives on issues #284 and #294). No `.superpowers/` path in a committed file. No commit hash in `docs/adr` — this plan touches no ADR at all.
- Stage by explicit path, git stderr visible, never `git add -A`. Commit messages end with a blank line then `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`.
- Full gate, BLOCKING, at the end: `SEGUE_REQUIRE_BROWSER=true SEGUE_REQUIRE_GRAPHVIZ=true ./gradlew check --rerun-tasks`. Count tests once from `build/test-results/test/*.xml`. If it reports formatting, `./gradlew spotlessApply` and fold the result into the commit it belongs to.

---

## Task 1: Pin `Q104635718` "Wikimedia artist discography" at CONCEPT

**Files:** test `src/test/java/com/robsartin/segue/wikidata/KindMapperTest.java`; allowlist `src/test/java/com/robsartin/segue/arch/StandInQidsDenoteNothingTest.java`; `src/main/java/com/robsartin/segue/wikidata/KindMapper.java` **only while the plant is in**, reverted before the commit.

- [ ] **Step 1: Plant the wrong ruling.** In `KindMapper`'s static block, immediately after `put("Q7889", NodeKind.WORK); // video game` (the last line of the issue-#265 group), add:

```java
    put("Q104635718", NodeKind.WORK); // PLANTED positive control — remove before committing
```

- [ ] **Step 2: Write the pin** in `KindMapperTest`, immediately after the closing brace of `shouldStayConceptWhenTheClassIsAFictionalCharacter` and before `shouldMapToWorkWhenTheClassIsSingleRelease`:

```java
  @Test
  @DisplayName("a Wikimedia artist discography stays CONCEPT: it is the page, not the releases")
  void shouldStayConceptWhenTheClassIsAWikimediaArtistDiscography() {
    // Issue #294, the reading after the first expander run. A Wikimedia list of an artist's
    // releases is a Wikipedia page ABOUT the releases, not a release: many works and people point
    // at it and nobody did anything with it, which is the hub shape the CONCEPT-gated rules exist
    // to demote (ADR 38, issue #52). Mapping it to WORK would put a page in the recommender's
    // candidate pool. Label and description confirmed live on 2026-09-08, on the issue.
    assertThat(KindMapper.fromInstanceOf(List.of("Q104635718"))) // Wikimedia artist discography
        .isEqualTo(NodeKind.CONCEPT);
  }
```

- [ ] **Step 3: Run it and observe the planted red.** `./gradlew test --tests '*KindMapperTest'` — FAIL, one test, with

```
expected: CONCEPT
 but was: WORK
```

  Quote the failure verbatim in the report. If instead the build fails to compile, or throws `IllegalStateException: two kinds claim Q104635718`, that is **not** the red: fix the cause and re-run until the assertion itself fires.

- [ ] **Step 4: Remove the plant.** Delete the line added in Step 1. Confirm with `git diff --stat src/main` — it must print nothing.
- [ ] **Step 5: Run the pin green.** `./gradlew test --tests '*KindMapperTest'` — PASS.
- [ ] **Step 6: Run the fence and observe the second red.** `./gradlew test --tests '*StandInQidsDenoteNothingTest'` — FAIL, because a real Wikidata id now sits in a test file with no allowlist entry. The failure carries the long `as(...)` description followed by the sighting, of the shape

```
Expecting empty but was: "src/test/java/com/robsartin/segue/wikidata/KindMapperTest.java:<line>  Q104635718"
```

  Quote it verbatim, with the real line number the run printed.

- [ ] **Step 7: Register the id.** In `ALLOWED`, keys run by digit count then numerically; `Q104635718` is a nine-digit id and goes **between the `Q97798779` entry and the `Q105543609` entry**:

```java
          entry(
              "Q104635718",
              real(
                  "class id — deliberately left unmapped by KindMapper, pinned at CONCEPT by"
                      + " KindMapperTest (issue #294)",
                  code("src/test/java/com/robsartin/segue/wikidata/KindMapperTest.java"))),
```

- [ ] **Step 8: Run both green.** `./gradlew test --tests '*KindMapperTest' --tests '*StandInQidsDenoteNothingTest'` — PASS. `git diff --stat src/main` still prints nothing.
- [ ] **Step 9: Commit** the two test files by explicit path: `Pin Wikimedia artist discography at CONCEPT (#294)`

## Task 2: Pin `Q13433827` "encyclopedia article" at CONCEPT

**Files:** the same two test files; `KindMapper.java` only while the plant is in.

- [ ] **Step 1: Plant the wrong ruling.** In `KindMapper`'s static block, after the issue-#265 group:

```java
    put("Q13433827", NodeKind.WORK); // PLANTED positive control — remove before committing
```

- [ ] **Step 2: Write the pin** in `KindMapperTest`, immediately after `shouldStayConceptWhenTheClassIsAWikimediaArtistDiscography`:

```java
  @Test
  @DisplayName("an encyclopedia article stays CONCEPT: it is the page, not the subject")
  void shouldStayConceptWhenTheClassIsAnEncyclopediaArticle() {
    // Issue #294. An article is a page ABOUT a subject; the subject is the entity worth
    // recommending and usually has a node of its own. Mapping the article to WORK would offer a
    // reference page as something to explore, and would take a high-degree hub out of the reach of
    // the rule that demotes routes through one. Confirmed live on 2026-09-08, on the issue.
    assertThat(KindMapper.fromInstanceOf(List.of("Q13433827"))) // encyclopedia article
        .isEqualTo(NodeKind.CONCEPT);
  }
```

- [ ] **Step 3: Run and observe the planted red.** `./gradlew test --tests '*KindMapperTest'` — FAIL with `expected: CONCEPT but was: WORK`. Quote it verbatim. It must be the assertion, not a load-time throw.
- [ ] **Step 4: Remove the plant.** `git diff --stat src/main` prints nothing.
- [ ] **Step 5: Run the pin green** — PASS.
- [ ] **Step 6: Run the fence and observe the second red.** `./gradlew test --tests '*StandInQidsDenoteNothingTest'` — FAIL naming `KindMapperTest.java:<line>  Q13433827`. Quote it.
- [ ] **Step 7: Register the id.** `Q13433827` is an eight-digit id and goes **between the `Q12057459` entry and the `Q13473501` entry**:

```java
          entry(
              "Q13433827",
              real(
                  "class id — deliberately left unmapped by KindMapper, pinned at CONCEPT by"
                      + " KindMapperTest (issue #294)",
                  code("src/test/java/com/robsartin/segue/wikidata/KindMapperTest.java"))),
```

- [ ] **Step 8: Run both green** — PASS. `git diff --stat src/main` prints nothing.
- [ ] **Step 9: Commit**: `Pin encyclopedia article at CONCEPT (#294)`

## Task 3: Name both classes in `ClassLabels`

**Files:** `src/main/java/com/robsartin/segue/support/ClassLabels.java`; `src/test/java/com/robsartin/segue/support/ClassLabelsTest.java`; `src/test/java/com/robsartin/segue/arch/StandInQidsDenoteNothingTest.java`.

This is the first commit to cite either id in `ClassLabelsTest`, so it is the commit that adds the second site to each allowlist entry — not earlier, or `shouldCarryNoDeadSiteWhenTheAllowlistIsCheckedAgainstTheTree` reds on a site the tree does not yet carry.

- [ ] **Step 1: Write both failing tests** at the end of `ClassLabelsTest`, after `shouldLabelAnimatedCharacterWhenTheClassIsQ15711870`:

```java
  @Test
  @DisplayName("Wikimedia artist discography has a label, confirmed live 2026-09-08 (issue #294)")
  void shouldLabelWikimediaArtistDiscographyWhenTheClassIsQ104635718() {
    assertThat(ClassLabels.label("Q104635718")).isEqualTo("Wikimedia artist discography");
  }

  @Test
  @DisplayName("encyclopedia article has a label, confirmed live 2026-09-08 (issue #294)")
  void shouldLabelEncyclopediaArticleWhenTheClassIsQ13433827() {
    assertThat(ClassLabels.label("Q13433827")).isEqualTo("encyclopedia article");
  }
```

- [ ] **Step 2: Run and observe two reds.** `./gradlew test --tests '*ClassLabelsTest'` — FAIL, two tests, with the fallback returning the bare qid:

```
expected: "Wikimedia artist discography"
 but was: "Q104635718"
```

```
expected: "encyclopedia article"
 but was: "Q13433827"
```

  Quote both verbatim.

- [ ] **Step 3: Add both names**, at the end of the concepts block in `ClassLabels`, after `put("Q11448906", "science award");`:

```java
    // Issue #294's two, from the reading after the first expander run: both are Wikimedia pages
    // about a thing rather than the thing, so KindMapper leaves them unmapped and KindMapperTest
    // pins them at CONCEPT. Named here so a tooltip and a rating card can say what the node is.
    put("Q104635718", "Wikimedia artist discography");
    put("Q13433827", "encyclopedia article");
```

- [ ] **Step 4: Run green.** `./gradlew test --tests '*ClassLabelsTest'` — PASS, including `isAWellFormedTable` and `namesEachClassOnce` (neither name collides with the existing `discography`).
- [ ] **Step 5: Run the fence and observe the red on the new sites.** `./gradlew test --tests '*StandInQidsDenoteNothingTest'` — FAIL, naming both sightings in `ClassLabelsTest.java`, of the shape

```
Expecting empty but was: "src/test/java/com/robsartin/segue/support/ClassLabelsTest.java:<line>  Q104635718
src/test/java/com/robsartin/segue/support/ClassLabelsTest.java:<line>  Q13433827"
```

  Quote it verbatim. This is the guard the design describes: an id allowed at one file is not allowed at another until the entry says so.

- [ ] **Step 6: Add the second site to each entry** written in Tasks 1 and 2, and widen each reason to say the table now names it:

```java
          entry(
              "Q104635718",
              real(
                  "class id — named by ClassLabels, deliberately left unmapped by KindMapper,"
                      + " pinned at CONCEPT by KindMapperTest (issue #294)",
                  code("src/test/java/com/robsartin/segue/wikidata/KindMapperTest.java"),
                  code("src/test/java/com/robsartin/segue/support/ClassLabelsTest.java"))),
```

```java
          entry(
              "Q13433827",
              real(
                  "class id — named by ClassLabels, deliberately left unmapped by KindMapper,"
                      + " pinned at CONCEPT by KindMapperTest (issue #294)",
                  code("src/test/java/com/robsartin/segue/wikidata/KindMapperTest.java"),
                  code("src/test/java/com/robsartin/segue/support/ClassLabelsTest.java"))),
```

- [ ] **Step 7: Run green.** `./gradlew test --tests '*ClassLabelsTest' --tests '*StandInQidsDenoteNothingTest' --tests '*KindMapperTest'` — PASS.
- [ ] **Step 8: Commit** the three files: `Name the two Wikimedia page classes (#294)`

## Task 4: Say in `KindMapper`'s table why #294 added no rule

**Files:** `src/main/java/com/robsartin/segue/wikidata/KindMapper.java`.

A comment, with no behaviour to test — the honest exception. It is verified by the full gate in Task 5 (compilation, `spotless`, and every test that reads this class), and by re-reading the diff to confirm it names no class id. The reason it is worth a commit: the block above the #261 rules already tells a reader that four classes of that reading stay `CONCEPT` on purpose and points at `KindMapperTest`; #294 adds no `put` at all, so without one sentence the issue leaves no trace in the table a later class pass will read.

- [ ] **Step 1: Add the sentence** immediately after `put("Q7889", NodeKind.WORK); // video game` and before the `// places` comment:

```java
    // The third reading (issue #294, after the first expander run) added no rule. The two classes
    // at the head of its CONCEPT list are Wikimedia pages about a thing rather than the thing, so
    // they stay CONCEPT on purpose and KindMapperTest pins them, the way it pins the character
    // classes. The ids are in the test, not here: this table is for what a class MEANS, and a
    // second copy of the list would be the one a later editor forgets.
```

- [ ] **Step 2: Verify.** `./gradlew test --tests '*KindMapperTest' --tests '*StandInQidsDenoteNothingTest'` — PASS. Read `git diff src/main/java/com/robsartin/segue/wikidata/KindMapper.java` and confirm it adds comment lines only, no `put`, and no class id.
- [ ] **Step 3: Commit**: `Say in the table why the third reading added no rule (#294)`

## Task 5: Drop the drifting count from the developer guide, then the gate

**Files:** `docs/developer-guide.md`.

Found while working: the exporter chapter calls `ClassLabels` "a table in the source of about 45 classes"; it holds 51 entries before this work and 53 after. The repair is to stop restating a number the table is the authority for. Prose, no unit-testable behaviour: verified by the full gate below, which declares `docs` as a test input, so `DocumentationLinksTest` and the guide's enumeration tests re-read the edited file.

- [ ] **Step 1: Edit** the sentence in the "What a node is, not just which kind it is" section, replacing

```
The names come from `ClassLabels`, a table in the source of about 45 classes
read from Wikidata's own `labels/en`, and **an unknown class shows as its bare QID** rather than a
```

  with

```
The names come from `ClassLabels`, a table in the source whose entries were
read from Wikidata's own `labels/en`, and **an unknown class shows as its bare QID** rather than a
```

- [ ] **Step 2: Run** `./gradlew test --tests '*DocumentationLinksTest' --tests '*DeveloperGuide*'` — PASS.
- [ ] **Step 3: Commit**: `Stop restating the class table's size (#294)`
- [ ] **Step 4: Full gate, BLOCKING:** `SEGUE_REQUIRE_BROWSER=true SEGUE_REQUIRE_GRAPHVIZ=true ./gradlew check --rerun-tasks`. BUILD SUCCESSFUL; count tests once from `build/test-results/test/*.xml`. No new commit unless `spotlessApply` had to run, in which case fold the formatting into the commit that introduced the line.
- [ ] **Step 5: Report** every red observed, quoted verbatim: two planted pin failures, three stand-in fence failures, two label failures. Confirm `git diff origin/main --stat` shows exactly the six files this plan names, and that `KindMapper`'s `BY_CLASS` gained no entry — so the next boot's `rederive` moves nothing, which is the whole point of the issue.
