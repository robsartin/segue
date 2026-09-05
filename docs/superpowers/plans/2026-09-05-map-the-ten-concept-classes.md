# Map the ten classes that hold most CONCEPT nodes — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give `KindMapper` a rule for each of the six top-ten CONCEPT classes that map to a kind, pin the four that stay CONCEPT, and record the decision as a dated amendment to ADR 42.

**Architecture:** Every rule is one `put` line in `KindMapper.BY_CLASS`; `KindMapper.rederive` (ADR 42) re-kinds every node stating the class at the next boot with no change to the log. Each rule lands as its own commit, RED first with an invented node stating only that class. The biggest class gets a live positive control in `WikidataLiveSmokeTest`, because an offline test proves only that the table says what the table says.

**Tech Stack:** Java 25, JUnit 5, AssertJ, Gradle (`./gradlew`, JDK 25 is the only JDK).

## Global Constraints

- Issue #261 and its lookup comment of 2026-09-05 are the spec. The rulings there are final: Q3331189, Q169930, Q6128115, Q17517379, Q7302866 → `WORK`; Q1573906 → `EVENT`; Q38033430, Q618779, Q378427, Q15632617 stay `CONCEPT`.
- Every rule is RED first: the new test runs and fails with `expected: WORK but was: CONCEPT` (or `EVENT`) before the `put` line exists. A compile error is not a red. The report quotes each failure.
- Every pin gets a planted positive control: temporarily register the class with the wrong kind, watch the pin fail, remove the plant. The report quotes the failure.
- Test names read `should<Expected>When<Condition>` and carry a `@DisplayName`.
- Never run `own`, `ownClaim`, `retractEntity`, `resolveNames`, `rate`, `graphCensus`, `evaluate`, or any seeding task. Never read, write, copy, open or create `~/.segue/segue.db`. The live smoke test reads `wikidata.org` only.
- Never invent a Wikidata id. The one real entity added to the live test must be found through the Wikidata API and its `P31` read back before it is written down.
- No figure from the owner's graph is restated in a committed file; cite issue #261 and date it.
- ADR 42 is immutable: one dated amendment appended at its end; nothing above it edited.
- Stage by explicit path, git stderr visible, never `git add -A`. No `.superpowers/` path in a committed file. Commit messages end with a blank line then `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`.
- Full gate, run BLOCKING at the end: `SEGUE_REQUIRE_BROWSER=true SEGUE_REQUIRE_GRAPHVIZ=true ./gradlew check --rerun-tasks`. Count tests once, from `build/test-results/test/*.xml`. If the gate reports formatting, `./gradlew spotlessApply` and amend.

---

## Task 1: Q3331189 "version, edition or translation" → WORK, with a live control

**Files:**
- Modify: `src/main/java/com/robsartin/segue/wikidata/KindMapper.java` (the `// works` block of `BY_CLASS`)
- Test: `src/test/java/com/robsartin/segue/wikidata/KindMapperTest.java`
- Test: `src/test/java/com/robsartin/segue/wikidata/WikidataLiveSmokeTest.java`

- [ ] **Step 1: Find one real entity typed only with Q3331189.** Query the Wikidata API (User-Agent `segue-issue-261 implementer`), for example:

```bash
curl -s -A 'segue-issue-261 implementer' 'https://query.wikidata.org/sparql?format=json&query=SELECT%20%3Fitem%20WHERE%20%7B%20%3Fitem%20wdt%3AP31%20wd%3AQ3331189%20.%20%3Fitem%20rdfs%3Alabel%20%3Fl%20FILTER(lang(%3Fl)%3D%22en%22)%20%7D%20LIMIT%205'
```

Then read one candidate's `P31` list back through `Special:EntityData/<QID>.json` and confirm that NONE of its classes is already in `BY_CLASS` (otherwise the live assertion is green before the rule and proves nothing). Record the QID, its English label and its full `P31` list in the report.

- [ ] **Step 2: Write the failing offline test** in `KindMapperTest`, after `theOtherWaysWikidataSaysGroup`:

```java
  @Test
  @DisplayName("an edition or translation of a work is a WORK")
  void shouldMapToWorkWhenTheClassIsVersionEditionOrTranslation() {
    // The class that held the most CONCEPT nodes in the first census reading (issue #261,
    // 2026-09-05). Wikidata uses it to say "this item is a specific edition, adaptation or
    // translation of a work" — which is a work, in the sense PERFORMED and AUTHORED point at.
    // Label and description confirmed live before this line was written.
    assertThat(KindMapper.fromInstanceOf(List.of("Q3331189"))) // version, edition or translation
        .isEqualTo(NodeKind.WORK);
  }
```

- [ ] **Step 3: Write the failing live test** in `WikidataLiveSmokeTest`, after `theWorkClassesStillMapRealEntities`, substituting the QID from Step 1:

```java
  @Test
  @DisplayName("the class added for issue #261 places a real edition where it claims")
  void shouldMapARealEditionToWorkWhenItStatesOnlyVersionEditionOrTranslation() {
    // Same reason as the #52 control above: the offline test says the table maps Q3331189 to
    // WORK, which is true of whatever Q3331189 is. Only a live read says the entity wearing it
    // is an edition of a work. This entity states no class the table knew before #261.
    assertThat(kindOf("Q<from step 1>")) // <label from step 1>
        .isEqualTo(NodeKind.WORK);
  }
```

- [ ] **Step 4: Run both and watch them fail for the right reason**

```bash
./gradlew test --tests '*KindMapperTest' 2>&1 | tail -30
./gradlew liveTest --tests '*WikidataLiveSmokeTest' 2>&1 | tail -30
```

Expected: both new tests FAIL with `expected: WORK but was: CONCEPT`. Quote both in the report.

- [ ] **Step 5: Add the rule** at the end of the `// works` block in `KindMapper`, preceded by a comment that introduces the whole #261 group (the later tasks add their lines under it):

```java
    // The first census reading (issue #261, 2026-09-05) listed the ten Wikidata classes holding
    // most of the graph's CONCEPT nodes. Six of them are works or events the table had never
    // learned; the other four stay CONCEPT on purpose (awards, and fictional humans — see
    // KindMapperTest). Every one was looked up and confirmed by label AND description.
    put("Q3331189", NodeKind.WORK); // version, edition or translation
```

- [ ] **Step 6: Run both tests again**

Expected: PASS. Then run the whole `KindMapperTest` class and `RecognitionInstitutionsTest`: PASS.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/robsartin/segue/wikidata/KindMapper.java src/test/java/com/robsartin/segue/wikidata/KindMapperTest.java src/test/java/com/robsartin/segue/wikidata/WikidataLiveSmokeTest.java
git commit -m "Map version, edition or translation to WORK (#261)"
```

## Task 2: Q1573906 "concert tour" → EVENT

- [ ] **Step 1: Write the failing test** in `KindMapperTest`:

```java
  @Test
  @DisplayName("a concert tour is an EVENT")
  void shouldMapToEventWhenTheClassIsConcertTour() {
    // A series of concerts, the way a festival (Q132241, already EVENT) is. Issue #261.
    assertThat(KindMapper.fromInstanceOf(List.of("Q1573906"))) // concert tour
        .isEqualTo(NodeKind.EVENT);
  }
```

- [ ] **Step 2: Run it**: `./gradlew test --tests '*KindMapperTest'` — FAIL `expected: EVENT but was: CONCEPT`. Quote it.
- [ ] **Step 3: Add the rule** at the end of the `// events` block: `put("Q1573906", NodeKind.EVENT); // concert tour`
- [ ] **Step 4: Run it**: PASS.
- [ ] **Step 5: Commit** (same two files): `Map concert tour to EVENT (#261)`

## Task 3: Q169930 "extended play" → WORK

- [ ] **Step 1: Write the failing test**:

```java
  @Test
  @DisplayName("an extended play is a WORK")
  void shouldMapToWorkWhenTheClassIsExtendedPlay() {
    // The same family as album (Q482994). Issue #261.
    assertThat(KindMapper.fromInstanceOf(List.of("Q169930"))) // extended play
        .isEqualTo(NodeKind.WORK);
  }
```

- [ ] **Step 2: Run it**: FAIL `expected: WORK but was: CONCEPT`. Quote it.
- [ ] **Step 3: Add the rule** under the #261 comment in the works block: `put("Q169930", NodeKind.WORK); // extended play`
- [ ] **Step 4: Run it**: PASS.
- [ ] **Step 5: Commit**: `Map extended play to WORK (#261)`

## Task 4: Q6128115 "7″ single" → WORK

- [ ] **Step 1: Write the failing test**:

```java
  @Test
  @DisplayName("a seven-inch single is a WORK")
  void shouldMapToWorkWhenTheClassIsSevenInchSingle() {
    // A physical format of a single (Q134556, already WORK). Issue #261.
    assertThat(KindMapper.fromInstanceOf(List.of("Q6128115"))) // 7-inch single
        .isEqualTo(NodeKind.WORK);
  }
```

- [ ] **Step 2: Run it**: FAIL `expected: WORK but was: CONCEPT`. Quote it.
- [ ] **Step 3: Add the rule**: `put("Q6128115", NodeKind.WORK); // 7-inch single`
- [ ] **Step 4: Run it**: PASS.
- [ ] **Step 5: Commit**: `Map the seven-inch single to WORK (#261)`

## Task 5: Q17517379 "animated short film" → WORK

- [ ] **Step 1: Write the failing test**:

```java
  @Test
  @DisplayName("an animated short film is a WORK")
  void shouldMapToWorkWhenTheClassIsAnimatedShortFilm() {
    // Both of its parents, animated film (Q202866) and short film (Q24862), are already WORK;
    // the table does not walk P279, so the class needs its own line. Issue #261.
    assertThat(KindMapper.fromInstanceOf(List.of("Q17517379"))) // animated short film
        .isEqualTo(NodeKind.WORK);
  }
```

- [ ] **Step 2: Run it**: FAIL `expected: WORK but was: CONCEPT`. Quote it.
- [ ] **Step 3: Add the rule**: `put("Q17517379", NodeKind.WORK); // animated short film`
- [ ] **Step 4: Run it**: PASS.
- [ ] **Step 5: Commit**: `Map animated short film to WORK (#261)`

## Task 6: Q7302866 "audio track" → WORK

- [ ] **Step 1: Write the failing test**:

```java
  @Test
  @DisplayName("an audio track is a WORK")
  void shouldMapToWorkWhenTheClassIsAudioTrack() {
    // The same family as song (Q7366) and music track with vocals (Q55850593). Its parent
    // "musical work" (Q2188189) is NOT the "musical work/composition" (Q105543609) the table
    // holds — two similarly named classes, and only the latter is registered. Issue #261.
    assertThat(KindMapper.fromInstanceOf(List.of("Q7302866"))) // audio track
        .isEqualTo(NodeKind.WORK);
  }
```

- [ ] **Step 2: Run it**: FAIL `expected: WORK but was: CONCEPT`. Quote it.
- [ ] **Step 3: Add the rule**: `put("Q7302866", NodeKind.WORK); // audio track`
- [ ] **Step 4: Run it**: PASS.
- [ ] **Step 5: Commit**: `Map audio track to WORK (#261)`

## Task 7: Pin the four that stay CONCEPT

- [ ] **Step 1: Extend `awardsStayConcepts`** with one line after the literary-award assertion:

```java
    assertThat(KindMapper.fromInstanceOf(List.of("Q38033430"))) // class of award
        .isEqualTo(NodeKind.CONCEPT);
```

- [ ] **Step 2: Add the fictional-human pin** as its own test:

```java
  @Test
  @DisplayName("a fictional human stays a CONCEPT, deliberately")
  void shouldStayConceptWhenTheClassIsFictionalHuman() {
    // Issue #261's one judgment call. Q15632617 is not Q5: PERSON is the kind the recommender
    // explores and the MusicBrainz adapter describes (ADR 54), so mapping it would put
    // characters in the candidate pool and send them to a source that cannot know them. A
    // character that appears in many works is exactly the shape the hub rule demotes.
    assertThat(KindMapper.fromInstanceOf(List.of("Q15632617"))) // fictional human
        .isEqualTo(NodeKind.CONCEPT);
  }
```

- [ ] **Step 3: Run**: `./gradlew test --tests '*KindMapperTest'` — PASS (these pin behaviour that already holds; there is no red to see, and the report says so).
- [ ] **Step 4: Plant the control.** Temporarily add `put("Q15632617", NodeKind.PERSON);` and `put("Q38033430", NodeKind.GROUP);` to `BY_CLASS`, run the class, watch BOTH pins fail (`expected: CONCEPT but was: PERSON` / `GROUP`), quote the failures, remove both plants, run again: PASS. `git diff src/main` must be empty before the commit.
- [ ] **Step 5: Commit** (test file only): `Pin the four top-ten classes that stay CONCEPT (#261)`

## Task 8: ADR 42 amendment

**Files:**
- Modify: `docs/adr/0042-store-p31-and-rederive-kind-at-projection.md` (append only)

- [ ] **Step 1: Append** after the last line of the file:

```markdown

**Amendment (2026-09-05, issue #261): the first growth of the table driven by the census, and
what the lookup taught about the walk this ADR refuses.**

Nothing here is withdrawn. This records the first time the whitelist grew from the reading
ADR 63's CONCEPT-by-class section was built to give (its 2026-09-04 amendment, issue #248),
rather than from a seeding sweep (issues #49 and #52) — which is the growth path this ADR's
own decision promised, and the first time re-derivation carried a table change to every
affected node with no re-seed, offline, at the next boot.

**What was read.** The owner's reading of 2026-09-05, recorded in issue #261, listed the ten
classes holding most of the graph's CONCEPT nodes. Every one was looked up live, label and
description both, before anything was decided; the table of results is on the issue.

**What changed.** Six of the ten are works or events the table had never learned — an
edition or translation of a work, an extended play, a seven-inch single, an animated short
film, an audio track, and a concert tour — and `KindMapper.BY_CLASS` now holds a line for
each, one commit apiece with a failing test first. The code is the authority for the table;
this amendment names the classes as history, not as a mirror.

**What deliberately did not change.** Three of the ten are award classes, and they stay
`CONCEPT` because ADR 38 and issue #52 depend on it: the hub rule reads "high-degree CONCEPT"
as "hub", and `KindMapperTest` pins award, literary award and now class of award there. The
tenth, fictional human, was the one judgment call. **Rejected: mapping it to `PERSON`.** It is
not `Q5`; `PERSON` is the kind the recommender explores and the kind the MusicBrainz adapter
describes (ADR 54), so the rule would have put characters in the candidate pool and sent
them to a source that cannot know them, and a character appearing in many works is exactly
the shape the hub rule exists to demote. It stays `CONCEPT`, pinned, so a later reading does
not re-open it by accident.

**What the lookup taught.** Literary award's own direct `P279` parent is planned event, which
the table maps to `EVENT`. A mapper that walked `P279` even one level would have turned an
award into an event. ADR 21 rejected the walk for its cost and because it could not settle
"city versus film"; this is the first observed case where the walk would have been wrong
outright, and it is recorded here as evidence for a decision that already stood. A second
observation, noted and not acted on: audio track's parent "musical work" (Q2188189) is a
different class from the "musical work/composition" (Q105543609) the table holds, and no
reading has yet shown the former on a CONCEPT node.

**What to read next.** The census after the next boot: the CONCEPT count should fall by
roughly the sum of the six classes mapped, the `WORK` and `EVENT` by-kind degree lines should
take those nodes, and the class section should show the six gone from its top ten. That
reading, not this amendment, decides whether the smaller classes are worth a pass.
```

- [ ] **Step 2: Verify** the file's front matter and everything above the amendment are byte-identical to main (`git diff main -- docs/adr/0042-store-p31-and-rederive-kind-at-projection.md` shows additions only), then `./gradlew test --tests '*AdrIndexTest' --tests '*DocumentationLinksTest'`: PASS.
- [ ] **Step 3: Full gate, BLOCKING**: `SEGUE_REQUIRE_BROWSER=true SEGUE_REQUIRE_GRAPHVIZ=true ./gradlew check --rerun-tasks`. BUILD SUCCESSFUL. Count tests once from `build/test-results/test/*.xml`.
- [ ] **Step 4: Commit** (ADR only): `Record why six classes moved and four stayed (#261)`
