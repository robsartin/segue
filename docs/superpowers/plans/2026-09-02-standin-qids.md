# Stand-in QIDs outside `Fixture` — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** every stand-in identifier in `src/test` takes a shape Wikibase's grammar refuses, and
`StandInQidsDenoteNothingTest` fails the build on a new one — seen red on today's set first, then
migrated band by band with the gate green at every commit, the guard's exclusion list shrinking to
empty as it goes.

**Architecture:** One new test in `com.robsartin.segue.arch` on `RepositoryTree`, carrying two lists:
an **allowlist** of deliberately-real ids with a reason each (permanent), and an **exclusion list**
of ids not yet migrated (temporary, deleted by the last task). Then one commit per band, each a
whole-file rewrite of that band's ids to leading-zero form. No production change.
`FixtureQidsDenoteNothingTest` is untouched.

**Tech Stack:** Java (toolchain 25, `release 21`), JUnit, AssertJ.

**Spec:** `docs/superpowers/specs/2026-09-02-standin-qids-design.md` — it holds the measurements,
the band table, the triage, and the reasoning. Do not restate a figure from it; cite it.

## Global Constraints

- **Pure TDD.** Task 1's guard is written and seen RED on the unchanged tree before any id moves.
  Every later task runs the gate before and after, and quotes what changed.
- **Green at every commit (ADR 4).** One band, one commit. Never two bands in one commit, and never
  a band left half-moved — five bands share `src/test/java/com/robsartin/segue/recommend/InventedWorld.java`
  and it is touched once per band, deliberately.
- **The migration rule is: prepend one zero.** `Q900100` → `Q0900100`, `Q900` → `Q0900`,
  `Q404` → `Q0404`. Nothing is renumbered. Two leading zeros are `LocalEntity`'s shape and are never
  produced here.
- **Rewrite the whole file, not the constructor calls.** Expectations assert rendered ids in the
  same file that builds them. A rewrite scoped to `new NodeRecord(...)` leaves them behind.
- **Move the prefix with the family.** Ids built as `"Q9001" + …` are not literals of themselves.
  See each task's Files block; the prefix and every id it mints belong to the same commit.
- Test names `should<Expected>When<Condition>` with `@DisplayName`. **Never `git add -A`**; stage by
  explicit path with git's stderr visible.
- Gate, **blocking**: `SEGUE_REQUIRE_BROWSER=true ./gradlew check --rerun-tasks`. **Measure the
  baseline test count on `main` before Task 1 and quote it in Task 1's report**; do not copy a count
  out of another document.
- **Only JDK 25 is installed; Gradle 9.7.1 launches on it.** Plain `./gradlew`; never
  `java_home -v 21`.
- **Never run a writing dev task** (`ownClaim`, `retractEntity`, abbreviations); `~/.segue/segue.db`
  is never read, written, or created.
- Never cite a `.superpowers/` path from a committed file.

---

### Task 1: `StandInQidsDenoteNothingTest`, red on today's set

**Files:**
- Create: `src/test/java/com/robsartin/segue/arch/StandInQidsDenoteNothingTest.java`
- Read only: `RepositoryTree.java`, `DocumentationLinksTest.java` and `PackageListsTest.java` (style
  and failure-reporting shape), `fixture/FixtureQidsDenoteNothingTest.java` (the grammar constant it
  must agree with), `domain/Qid.java`

**Interfaces:**
- Consumes: `RepositoryTree.root()` / `read()` (public since #183).
- Produces: `ALLOWED` (`Map<String, String>`, id → reason) and `NOT_YET_MIGRATED` (`Set<String>`),
  both package-private and both read by later tasks.

- [ ] **Step 1 — the scan, RED with both lists empty.** Walk every file under `src/test` (`Files.walk`,
      sorted, so failure order is stable). For a `.java` file strip block comments (`/*…*/`, DOTALL)
      then line comments (`//…`), take every string literal, and collect every `\bQ\d+\b` inside one;
      for every other file collect every `\bQ\d+\b` in the whole text. Fail on any token matching
      `Q[1-9]\d{0,9}` — the same grammar constant `FixtureQidsDenoteNothingTest` spells, quoted from
      WikibaseDataModel `src/Entity/ItemId.php` — reporting `"<file>:<line>  <id>"`, every failure at
      once. Run it. **Quote the full red**: the failure count is the guard's own measurement of the
      problem and the plan's baseline. It must agree with the spec's second scan; if it does not, the
      difference is a checker bug and is this task's first finding.
- [ ] **Step 2 — the allowlist, populated from the red.** Add `ALLOWED` as id → reason. Every entry
      says which of the four kinds it is (class id / entity a test is genuinely about / deliberately
      allocatable negative control / not an identifier). At minimum it carries `Q42`
      (`OwnerClaimTest` asserts `LocalEntity` refuses it — migrating it would destroy the control)
      and `Q1`–`Q4` for `GraphStoreContract`'s `@DisplayName` question numbers. Re-run: the red now
      names only stand-ins.
- [ ] **Step 3 — the exclusion list.** Add `NOT_YET_MIGRATED` holding today's stand-ins, with a
      javadoc saying it shrinks to empty in Task 12 and that adding to it is not a fix. GREEN.
- [ ] **Step 4 — the vacuity guard.** Assert the sweep read at least one file and at least one
      allocatable id. Control: point the walk at an empty temp directory → red on the guard; restore.
- [ ] **Step 5 — the three remaining controls,** each quoted and reverted. (a) Plant
      `new ViewNode("Q900016", NodeKind.CONCEPT, "Invented Prize")` in `export/DotWriterTest.java`
      in place of `Q906` → the guard reds naming that file, line and `Q900016`. **The same plant is
      `BUILD SUCCESSFUL` on `main` today** — run it against `main`'s tests first and quote both, since
      that pair is the whole argument for this test existing. (b) Delete one `ALLOWED` entry → red on
      the real id it covered. (c) Delete the comment-stripping step → red on
      `FixtureQidsDenoteNothingTest`'s own javadoc, which is the false positive the design avoids.
- [ ] **Step 6 — say what the guard cannot see,** in its javadoc: six sites build a qid from a bare
      `"Q"` and an integer (`PathRankingTest:368-369`, `SegueServiceTest:304,339,370`,
      `MusicBrainzSourceAdapterTest:575`), and no scan over source text can reach them. Task 11 fixes
      those by hand. A limit stated in the test is a limit; a limit nobody wrote down is a hole.
- [ ] **Step 7 — gate and commit** (one commit; the report quotes Step 1's red and Step 5(a)'s pair).

---

### Task 2: band I — `Q100001`–`Q100004`

**Files:**
- Modify: `src/test/java/com/robsartin/segue/port/GraphStoreContract.java`
- Modify: `src/test/java/com/robsartin/segue/arch/StandInQidsDenoteNothingTest.java` (drop these
  four from `NOT_YET_MIGRATED`)

**Interfaces:** consumes `NOT_YET_MIGRATED`. Produces nothing.

- [ ] **Step 1** — rewrite the four ids in place, prepending a zero. **Do not touch `Q1`–`Q4` in
      this file's `@DisplayName`s** — those are question numbers and are on the allowlist.
- [ ] **Step 2** — drop the four from `NOT_YET_MIGRATED`. Run the guard: GREEN, and re-adding one id
      to the file without re-adding it to the list reds — quote it, revert it.
- [ ] **Step 3** — gate, blocking. Both engines run this contract; quote the test count and confirm
      it is unchanged from Task 1's baseline. Commit.

---

### Task 3: band J — the high-range wager

**Files:**
- Modify: `src/test/java/com/robsartin/segue/wikidata/KindMapperTest.java`,
  `src/test/java/com/robsartin/segue/wikidata/WikidataEntityResolverTest.java`,
  `src/test/java/com/robsartin/segue/wikidata/WikidataSourceAdapterTest.java`,
  `src/test/java/com/robsartin/segue/ingest/GraphProjectorTest.java`,
  `src/test/java/com/robsartin/segue/app/StdioPurityTest.java`,
  `src/test/java/com/robsartin/segue/app/StreamableHttpTransportTest.java`,
  `src/test/java/com/robsartin/segue/port/GraphStoreContract.java`
- Modify: `StandInQidsDenoteNothingTest.java`

**Interfaces:** consumes `NOT_YET_MIGRATED` and `ALLOWED`.

- [ ] **Step 1 — separate the two uses first, and write the split down.** Where the id stands in for
      "something no source knows" (`KindMapperTest`'s unmapped P31 values, the resolver's 404
      subject, the JSON-RPC payloads in `StdioPurityTest` and `StreamableHttpTransportTest`), migrate
      it. Where the *subject of the test or javadoc is that the id is well-formed but unallocated* —
      `FixtureQidsDenoteNothingTest`'s javadoc contrasts `Q999999999`'s `resource-not-found` with
      `Q0900001`'s `invalid-path-parameter`, which is the fact ADR 58 turns on — it stays, and moves
      to `ALLOWED` with that reason rather than to nothing.
- [ ] **Step 2** — apply, shrink `NOT_YET_MIGRATED`, run the guard. GREEN.
- [ ] **Step 3** — gate, blocking. `StdioPurityTest` runs a real subprocess; confirm it passes rather
      than skips. Commit.

---

### Task 4: band H — the bare small ids

**Files:**
- Modify: `src/test/java/com/robsartin/segue/domain/LoggedAssertionTest.java`,
  `src/test/java/com/robsartin/segue/domain/RecordInvariantsTest.java`,
  `src/test/java/com/robsartin/segue/ingest/GraphProjectorTest.java`,
  `src/test/java/com/robsartin/segue/ingest/IngestServiceTest.java`,
  `src/test/java/com/robsartin/segue/mcp/SegueServiceTest.java`,
  `src/test/java/com/robsartin/segue/fixture/FixtureSourceAdapterTest.java`,
  `src/test/java/com/robsartin/segue/port/GraphStoreContract.java`,
  `src/test/java/com/robsartin/segue/wikidata/ReverseClaimsTest.java`
- Modify: `StandInQidsDenoteNothingTest.java`

**Interfaces:** consumes `NOT_YET_MIGRATED`, `ALLOWED`.

- [ ] **Step 1 — read the spec's "Band H's target form" open question and use the answer the
      controller gave.** If none was given, stop and ask; do not pick one. The draft's proposal is
      prepending (`Q1`→`Q01`, `Q404`→`Q0404`); the alternative is renumbering into `Q0900xxx`.
- [ ] **Step 2 — `Q1`, `Q2`, `Q3` are the trap in this band.** They are node ids in five files and
      question numbers in `GraphStoreContract`'s `@DisplayName`s. Rewrite the node ids; leave the
      display names. Confirm by reading the diff of `GraphStoreContract.java` line by line —
      a whole-file substitution is wrong here and it is the only file in the plan where that is true.
- [ ] **Step 3** — `SegueServiceTest` asserts `result.detail()).contains("Q404")` and `contains("Q1")`;
      those expectations move with the ids. Shrink `NOT_YET_MIGRATED`. GREEN.
- [ ] **Step 4** — gate, blocking. Commit.

---

### Task 5: band D — `Q9004xx`–`Q9008xx`, and the one JSON resource

**Files:**
- Modify: `src/test/java/com/robsartin/segue/domain/PathRankingTest.java`,
  `src/test/java/com/robsartin/segue/mcp/AffinityIsNeverLoggedTest.java`,
  `src/test/java/com/robsartin/segue/mcp/SegueServiceTest.java`,
  `src/test/java/com/robsartin/segue/mcp/TasteToolsTest.java`,
  `src/test/java/com/robsartin/segue/rate/DeckTest.java`,
  `src/test/java/com/robsartin/segue/recommend/InventedWorld.java`,
  `src/test/java/com/robsartin/segue/wikidata/WikidataSourceAdapterTest.java`,
  `src/test/resources/wikidata/bad-seeds-reverse.json`
- Modify: `StandInQidsDenoteNothingTest.java`

**Interfaces:** consumes `NOT_YET_MIGRATED`. `InventedWorld` is shared with bands A, B, C and E —
touch only this band's ids in it.

- [ ] **Step 1 — the prefixes.** `"Q9004" + (10 + i)` (`SegueServiceTest:916`),
      `"Q9005" + (10 + i)` (`SegueServiceTest:993`), `"Q9006" + (10 + i)`
      (`SegueServiceTest:956,1024`) become `"Q09004"`, `"Q09005"`, `"Q09006"`. The ids they mint are
      not literals anywhere, so the prefix is the only place they exist.
- [ ] **Step 2 — the JSON pair.** `src/test/resources/wikidata/bad-seeds-reverse.json` carries
      `"http://www.wikidata.org/entity/Q900790"` and `WikidataSourceAdapterTest:319` asserts
      `containsExactly("Q900790")`. Both, in this commit. **Every other qid in that file is a real
      recorded value** (`Q192668`, `Q316528`, `Q166565`, `Q383784`, `Q809003`, `Q134556`, `Q5`) and
      must not move; confirm the diff touches one line of JSON.
- [ ] **Step 3** — shrink `NOT_YET_MIGRATED`. GREEN. Gate, blocking. Commit.

---

### Task 6: band E — `Q9009xx`, `Q9010x`

**Files:**
- Modify: `src/test/java/com/robsartin/segue/export/DotWriterTest.java`,
  `src/test/java/com/robsartin/segue/export/PaletteSeparationTest.java`,
  `src/test/java/com/robsartin/segue/export/ViewSelectorTest.java`,
  `src/test/java/com/robsartin/segue/rate/DeckTest.java`,
  `src/test/java/com/robsartin/segue/rate/MergedIdIsNotDealtTest.java`,
  `src/test/java/com/robsartin/segue/rate/RateRunTest.java`,
  `src/test/java/com/robsartin/segue/recommend/AffinityWeightedRecommendationTest.java`,
  `src/test/java/com/robsartin/segue/recommend/CandidateSweepTest.java`,
  `src/test/java/com/robsartin/segue/recommend/InventedWorld.java`,
  `src/test/java/com/robsartin/segue/recommend/MergedIdIsOfferedOnceTest.java`,
  `src/test/java/com/robsartin/segue/recommend/RecommendRunTest.java`,
  `src/test/java/com/robsartin/segue/recommend/RecommendationsAreNeverLoggedTest.java`,
  `src/test/java/com/robsartin/segue/retract/RetractRunTest.java`,
  `src/test/java/com/robsartin/segue/seed/WikidataFactsTest.java`,
  `src/test/java/com/robsartin/segue/support/ClassLabelsTest.java`,
  `src/test/java/com/robsartin/segue/wikidata/RecognitionInstitutionsTest.java`
- Modify: `StandInQidsDenoteNothingTest.java`

**Interfaces:** consumes `NOT_YET_MIGRATED`.

- [ ] **Step 1 — five prefixes and one hash.** `"Q9009" + …` in `MergedIdIsNotDealtTest:120`,
      `RateRunTest:419`, `AffinityWeightedRecommendationTest:160`, `MergedIdIsOfferedOnceTest:171`,
      and `InventedWorld:105`'s `"Q9009" + (Math.abs(qid.hashCode()) % 90 + 10) + index`; plus
      `"Q9010" + i` in `WikidataFactsTest:83`. All become `"Q09009"` / `"Q09010"`.
- [ ] **Step 2 — the expectations.** `DotWriterTest` asserts `tooltip=\"Q900901\"`;
      `ViewSelectorTest` asserts `hasMessageContaining("Q900999")` twice; `PaletteSeparationTest`,
      `ClassLabelsTest` and `RecognitionInstitutionsTest` each name `Q900901` — the last two use it
      as a class id that is deliberately *not* in the mapper's vocabulary, so it migrates like any
      other stand-in. Whole-file rewrite in each.
- [ ] **Step 3 — ordering.** `DeckTest` mixes this band with already-migrated `Q09…` fixture ids and
      `Deck` sorts `thenComparing(Card::qid)`. If an ordering assertion reds, that is the
      cross-family tiebreak the spec predicts, not a mistake — fix the expectation and say so in the
      report.
- [ ] **Step 4** — shrink `NOT_YET_MIGRATED`. GREEN. Gate, blocking. Commit.

---

### Task 7: band C — `Q9003xx`

**Files:**
- Modify: `src/test/java/com/robsartin/segue/domain/FloorReadingTest.java`,
  `src/test/java/com/robsartin/segue/domain/RecommendationTest.java`,
  `src/test/java/com/robsartin/segue/domain/RecommendationsTest.java`,
  `src/test/java/com/robsartin/segue/mcp/SegueServiceTest.java`,
  `src/test/java/com/robsartin/segue/rate/RateRunTest.java`,
  `src/test/java/com/robsartin/segue/recommend/AffinityWeightedRecommendationTest.java`,
  `src/test/java/com/robsartin/segue/recommend/CandidateSweepTest.java`,
  `src/test/java/com/robsartin/segue/recommend/InventedWorld.java`,
  `src/test/java/com/robsartin/segue/recommend/MergedIdIsOfferedOnceTest.java`,
  `src/test/java/com/robsartin/segue/recommend/RecommendationReportTest.java`
- Modify: `StandInQidsDenoteNothingTest.java`

**Interfaces:** consumes `NOT_YET_MIGRATED`.

- [ ] **Step 1** — `CandidateSweepTest:435` builds ids as `"Q90031" + i`, which mints `Q900310`
      upward and overlaps this band's literal ids. `"Q090031"`. Confirm no literal `Q90031x` in that
      file is left behind.
- [ ] **Step 2** — the rest of the band, whole-file. Shrink `NOT_YET_MIGRATED`. GREEN.
- [ ] **Step 3** — gate, blocking. Commit.

---

### Task 8: band B — `Q9002xx`, and the hub predicate

**Files:**
- Modify: `src/test/java/com/robsartin/segue/domain/FloorReadingTest.java`,
  `src/test/java/com/robsartin/segue/domain/PathRankingTest.java`,
  `src/test/java/com/robsartin/segue/domain/RecommendationTest.java`,
  `src/test/java/com/robsartin/segue/domain/RecommendationsTest.java`,
  `src/test/java/com/robsartin/segue/domain/ScorerTest.java`,
  `src/test/java/com/robsartin/segue/domain/SharedIntermediateTest.java`,
  `src/test/java/com/robsartin/segue/export/ExportRunTest.java`,
  `src/test/java/com/robsartin/segue/rate/MergedIdIsNotDealtTest.java`,
  `src/test/java/com/robsartin/segue/rate/RateRunTest.java`,
  `src/test/java/com/robsartin/segue/recommend/AffinityWeightedRecommendationTest.java`,
  `src/test/java/com/robsartin/segue/recommend/InventedWorld.java`,
  `src/test/java/com/robsartin/segue/recommend/MergedIdIsOfferedOnceTest.java`,
  `src/test/java/com/robsartin/segue/recommend/RecommendationReportTest.java`
- Modify: `StandInQidsDenoteNothingTest.java`

**Interfaces:** consumes `NOT_YET_MIGRATED`.

- [ ] **Step 1 — the predicate is the risk in this band, and it gets a control.** `PathRankingTest:320`
      reads `qid -> qid.startsWith("Q9002") ? PathRanking.HUB_DEGREE : PathRanking.HUB_DEGREE - 1`.
      **Before migrating anything, change only that line to `"Q09002"` and run `PathRankingTest`** —
      it must red, because nothing is a hub any more. Quote the red, revert. That proves the
      predicate is load-bearing and that migrating the constructors without it would have inverted
      the classification silently.
- [ ] **Step 2** — migrate the prefixes (`"Q9002" + …` at `FloorReadingTest:20`,
      `ExportRunTest:313`, `RateRunTest:384,387`, `AffinityWeightedRecommendationTest:136,139`), the
      predicate, and the literal ids — all in this one commit. Shrink `NOT_YET_MIGRATED`. GREEN.
- [ ] **Step 3** — gate, blocking. Commit.

---

### Task 9: band A — `Q9001xx`, the largest family

**Files:**
- Modify: `src/test/java/com/robsartin/segue/domain/FloorReadingTest.java`,
  `.../domain/PathRankingTest.java`, `.../domain/RecommendationTest.java`,
  `.../domain/RecommendationsTest.java`, `.../domain/RetractionTest.java`,
  `.../domain/RetractionsTest.java`, `.../domain/ScorerTest.java`,
  `.../domain/SharedIntermediateTest.java`, `.../export/AffinityOverlayTest.java`,
  `.../export/DotWriterTest.java`, `.../export/ExportCliTest.java`,
  `.../export/GraphMlWriterTest.java`, `.../export/InventedGraph.java`,
  `.../ingest/GraphProjectorTest.java`, `.../ingest/IngestServiceTest.java`,
  `.../rate/DeckTest.java`, `.../rate/MergedIdIsNotDealtTest.java`, `.../rate/RateRunTest.java`,
  `.../recommend/AffinityWeightedRecommendationTest.java`, `.../recommend/InventedWorld.java`,
  `.../recommend/MergedIdIsOfferedOnceTest.java`, `.../recommend/RecommendationReportTest.java`,
  `.../retract/RetractCliTest.java`, `.../retract/RetractRunTest.java`,
  `.../sqlite/SqliteAssertionLogTest.java`, `.../support/QidListTest.java`,
  `.../tinker/SharedSubjectRouteTest.java` — all under `src/test/java/com/robsartin/segue/`
- Modify: `StandInQidsDenoteNothingTest.java`

**Interfaces:** consumes `NOT_YET_MIGRATED`. Two shared helpers move here: `InventedGraph.java` and
`InventedWorld.java`.

- [ ] **Step 1 — the prefixes.** `"Q9001" + String.format("%02d", i)` at `DotWriterTest:381` and
      `GraphMlWriterTest:110` → `"Q09001"`.
- [ ] **Step 2 — the band, whole-file, in one commit.** This is the family ADR 58 and
      `SharedSubjectRouteTest`'s javadoc both declined to split; splitting it now would be the same
      mistake. Expect expectation churn in `DotWriterTest`, `GraphMlWriterTest`, `ExportCliTest`
      and `RetractCliTest`, which render ids into output they assert on.
- [ ] **Step 3 — retire the admission.** Delete `SharedSubjectRouteTest`'s javadoc paragraph saying
      its ids "do denote something, and have not been fixed yet" — it is describing a state this
      commit ends. Leave the rest of the class javadoc.
- [ ] **Step 4** — shrink `NOT_YET_MIGRATED`. GREEN. Gate, blocking; expect the longest run of the
      plan. Commit.

---

### Task 10: bands F and G — the merge canonical sides — **BLOCKED**

**Do not start this task until the controller has answered the spec's first open question.** The
answer changes what the code says, not merely which ids are used.

**Files:**
- Modify: `src/test/java/com/robsartin/segue/ratings/InventedRatings.java`,
  `src/test/java/com/robsartin/segue/app/MergeWiringTest.java`,
  `src/test/java/com/robsartin/segue/domain/EquivalencesTest.java`,
  `src/test/java/com/robsartin/segue/domain/KnownListTest.java`,
  `src/test/java/com/robsartin/segue/domain/OwnerClaimTest.java`,
  `src/test/java/com/robsartin/segue/ingest/MergeCarriesEverythingTest.java`,
  `src/test/java/com/robsartin/segue/own/OwnCliTest.java`,
  `src/test/java/com/robsartin/segue/own/OwnRunTest.java`,
  `src/test/java/com/robsartin/segue/export/DotWriterTest.java`,
  `src/test/java/com/robsartin/segue/export/GraphMlWriterTest.java`,
  `src/test/java/com/robsartin/segue/export/ImagemapRecipeTest.java`,
  `src/test/java/com/robsartin/segue/export/WhatAHoverShowsTest.java`,
  `src/test/java/com/robsartin/segue/rate/MergedIdIsNotDealtTest.java`,
  `src/test/java/com/robsartin/segue/rate/RateRunTest.java`,
  `src/test/java/com/robsartin/segue/recommend/MergedIdIsOfferedOnceTest.java`,
  `src/test/java/com/robsartin/segue/recommend/RecommendRunTest.java`
- Modify: `StandInQidsDenoteNothingTest.java`
- Possibly modify (option 3 only): `src/main/java/com/robsartin/segue/domain/Qid.java`
- Possibly create (options 2 and 3): `docs/adr/00NN-<topic>.md`

**Interfaces:** `SameAs.declared` calls `Qid.checkAllocatable` on the canonical side; that is the
constraint the whole task turns on.

- [ ] **Step 1 — split the band before touching it.** `Q901`, `Q902` and `Q906` are plain
      `ViewNode`s in the four `export` tests and are ordinary stand-ins: migrate them by prepending,
      with `WhatAHoverShowsTest`'s `"Q901->Q902"` assertion. `Q900`–`Q905` where they stand as a
      merge's canonical side, and `Q900042` in `InventedRatings`, are the blocked half. Two commits,
      not one; the first needs no decision and can land before the answer arrives.
- [ ] **Step 2 — RED first, whichever option was chosen.** Under option 1 the red is the guard
      naming these ids and the green is an `ALLOWED` entry per id whose reason is *"a merge's
      canonical side must satisfy `Qid.checkAllocatable`"*. Under options 2 or 3 the red is a new
      assertion that `Qid.checkAllocatable` accepts the chosen form while
      `FixtureQidsDenoteNothingTest`'s grammar constant refuses it — written and seen red before the
      ids move.
- [ ] **Step 3 — the ADR, under options 2 and 3 only.** A second unallocatable shape is a decision.
      Record it, with the alternatives and why each lost, and cite `Qid`/`SameAs` as the authority
      for the rule rather than restating it. Under option 1 there is no ADR.
- [ ] **Step 4** — shrink `NOT_YET_MIGRATED`. GREEN. Gate, blocking. Commit.

---

### Task 11: the six sites no scan can see

**Files:**
- Modify: `src/test/java/com/robsartin/segue/domain/PathRankingTest.java` (lines 368-369),
  `src/test/java/com/robsartin/segue/mcp/SegueServiceTest.java` (lines 304, 339, 370),
  `src/test/java/com/robsartin/segue/musicbrainz/MusicBrainzSourceAdapterTest.java` (line 575)

**Interfaces:** none. This task exists because the guard cannot enforce it.

- [ ] **Step 1** — `"Q" + i`, `"Q" + (i + 1)`, `"Q" + (700 + i)`, `"Q" + (100 + i)` twice, and
      `"Q" + next++` each mint real Wikidata ids at runtime. Change the literal to `"Q0"` so every
      id they mint carries a leading zero. Confirm by asserting, in each loop, that the minted id
      fails `Q[1-9]\d{0,9}` — an assertion inside the loop is the only oracle available here, and it
      is what makes this task's work checkable at all.
- [ ] **Step 2** — control: revert one `"Q0"` to `"Q"` → the in-loop assertion reds. Quote, restore.
- [ ] **Step 3** — gate, blocking. Commit.

---

### Task 12: the exclusion list goes to empty

**Files:**
- Modify: `src/test/java/com/robsartin/segue/arch/StandInQidsDenoteNothingTest.java`
- Modify: `CLAUDE.md` (the *"Do not read that as 'the test fixtures are clean'"* bullet),
  `docs/developer-guide.md` (testing-strategy table),
  `docs/adr/0058-stand-in-identifiers-cannot-be-allocatable.md` (dated amendment only)

**Interfaces:** deletes `NOT_YET_MIGRATED`.

- [ ] **Step 1** — confirm `NOT_YET_MIGRATED` is empty, then **delete the field and every reference
      to it**, so the guard has one list and no escape hatch. GREEN.
- [ ] **Step 2 — the control that matters most, run last.** Plant an allocatable stand-in in a test
      file chosen at random from the tree → red, naming file, line and id. Quote it beside the
      `BUILD SUCCESSFUL` from Task 1 Step 5(a). Revert.
- [ ] **Step 3 — retire what is no longer true.** `CLAUDE.md`'s bullet says most ids in `src/test`
      are allocatable-form and points at this issue; replace it with a sentence naming the guard as
      what keeps the rule, and **restate no count**. Add the guard to the developer guide's
      testing-strategy table. Amend ADR 58 with a dated note saying its *"the repository is not
      clean"* consequence is discharged and by what — **an amendment, never an edit to the
      decision**.
- [ ] **Step 4** — gate, blocking, and quote the final test count beside Task 1's baseline. Commit.

---

## Self-Review

**Spec coverage.** Scan definition and comment stripping (Task 1 Step 1); the four allowlist kinds
(Task 1 Step 2, Task 3 Step 1, Task 10 Step 2); every band A–J (Tasks 2–10); the runtime prefixes
(Tasks 5, 6, 7, 8, 9 Step 1); the `startsWith` predicate (Task 8 Step 1); the JSON resource
(Task 5 Step 2); the lexicographic tiebreak (Task 6 Step 3); the invisible constructors (Task 1
Step 6 and Task 11); the guard's final form and the documents (Task 12).

**Blocked work is marked and isolated:** Task 10, and only its second half — the export `ViewNode`
ids in the same band need no decision and are split out in Step 1 so the rest of the plan does not
wait.

**Placeholders:** none, except the ADR number in Task 10, which cannot be known before the decision
and is marked `00NN`.

**Type consistency:** `RepositoryTree.root()`/`read()` as they exist; `Qid.isAllocatable` and
`FixtureQidsDenoteNothingTest`'s `Q[1-9]\d{0,9}` are read, never widened.

**What could still go wrong.** The guard's red in Task 1 disagreeing with the spec's scan — treated
as a checker bug and the task's first finding, not as a number to adjust. And an ordering assertion
flipping in a band that is not `DeckTest`; the per-band gate is what catches it, which is why there
is no task that migrates two bands at once.
