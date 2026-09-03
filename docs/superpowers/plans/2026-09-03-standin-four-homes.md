# The stand-in rule's four homes give one answer — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** one fixture log, fed to all four homes of the stand-in rule, with a guard that reds — naming
the pair — when any two of them call a canonical id something different, proven able to fail by four
positive controls that are planted, observed red, and reverted before commit.

**Architecture:** one new test class, `StandInAgreesInEveryHomeTest`, in
`com.robsartin.segue.export` beside `BothFoldsAgreeTest` (which is where `InventedGraph`'s
package-private helpers live). Two one-line public probe classes in the `ratings` and `own` test
packages reach the two homes that are not `public`. The only `src/main` behaviour-free changes are
one visibility modifier and two javadoc paragraphs. Task 1 lands the three homes that need no
production change at all; Task 2 adds the fourth, which needs the one seam.

**Tech Stack:** Java (toolchain 25, `release 21`), JUnit 5, AssertJ, ArchUnit.

**Spec:** `docs/superpowers/specs/2026-09-03-standin-four-homes-design.md` — it holds the four
corrections to the issue's framing (in particular that `Equivalences.standIns` carries no
"unless something claimed it" condition, and that `IngestService.standIn` upserts only on the live
path), the fixture table, the pinned answers, the rejected alternatives, and the #221/#222
concurrency notes. Do not restate that reasoning here; cite it.

## Global Constraints

- **No production behaviour changes.** Task 2 changes one modifier (`private static` →
  package-private `static`) and two javadoc paragraphs. Nothing else in `src/main` is edited except
  as a positive-control plant that is reverted before the commit.
- **The positive controls are this guard's RED.** There is no production behaviour to drive into
  existence, so red-before-green becomes: the guard passes on the real code → a plant makes it red
  on the *named* assertion → the plant is reverted and it passes again. A plant that produces a
  compile error is not a red; if that happens, fix the plant.
- Test names `should<Expected>When<Condition>` with `@DisplayName`.
- **Never `git add -A`**; stage every file by explicit path, with git's stderr visible. Two commits,
  one per task.
- Gate, **blocking** (no backgrounding): `SEGUE_REQUIRE_BROWSER=true SEGUE_REQUIRE_GRAPHVIZ=true ./gradlew check --rerun-tasks`.
  Plain `./gradlew` — only JDK 25 is installed. If `spotlessCheck` fails, run `./gradlew spotlessApply`
  and re-run the gate.
- **Never run a writing dev task** (`own`, `ownClaim`, `retractEntity`, or any other);
  `~/.segue/segue.db` is never read, written, or created.
- Never cite a `.superpowers/` path from a committed file.
- `src/test/java/com/robsartin/segue/export/InventedGraph.java` is **not** edited — issues #221 and
  #222 run in parallel and may touch it.

---

### Task 1: the guard, over the three homes that need no production change

**Files:**
- Create: `src/test/java/com/robsartin/segue/export/StandInAgreesInEveryHomeTest.java`
- Create: `src/test/java/com/robsartin/segue/ratings/LabelsProbe.java`
- Modify: `src/test/java/com/robsartin/segue/arch/StandInQidsDenoteNothingTest.java` (one `code(...)`
  site on `Q5`'s entry)
- Read only: `src/main/java/com/robsartin/segue/domain/Equivalences.java`,
  `src/main/java/com/robsartin/segue/ingest/IngestService.java`,
  `src/main/java/com/robsartin/segue/export/LogProjection.java`,
  `src/main/java/com/robsartin/segue/ratings/Labels.java`,
  `src/test/java/com/robsartin/segue/export/InventedGraph.java`,
  `src/test/java/com/robsartin/segue/export/BothFoldsAgreeTest.java`

**Interfaces:**
- Consumes: `Equivalences.standIns(List)`, `Equivalences.in(List)`, `LogProjection.of(AssertionLog)`,
  `IngestService#IngestService(AssertionLog, GraphStore, IdentityMerge)`, `IngestService.record`,
  `TinkerGraphStore.node`, `Labels.forQids(AssertionLog, Set)`, `InventedGraph.{minted,node,merged,FakeAssertionLog}`.
  All existing; none change.
- Produces: `LabelsProbe.forQids`, consumed only by the guard.

- [ ] **Step 1 — the `ratings` probe.** Create
      `src/test/java/com/robsartin/segue/ratings/LabelsProbe.java`:

      ```java
      package com.robsartin.segue.ratings;

      import com.robsartin.segue.port.AssertionLog;
      import java.util.Map;
      import java.util.Set;

      /**
       * Reaches {@code Labels.forQids}, which is package-private in a package-private class, from the
       * stand-in guard in another test package (issue #220).
       *
       * <p>A probe rather than a widening: {@code Labels} needs no production change to be reachable
       * from its own package, and this class is the whole of the reach.
       */
      public final class LabelsProbe {

        private LabelsProbe() {}

        /** {@code ratings/Labels.forQids}, the fourth home of the stand-in rule (ADR 59's residual). */
        public static Map<String, String> forQids(AssertionLog log, Set<String> qids) {
          return Labels.forQids(log, qids);
        }
      }
      ```

- [ ] **Step 2 — the guard, over three homes.** Create
      `src/test/java/com/robsartin/segue/export/StandInAgreesInEveryHomeTest.java`. The class
      javadoc must say (a) that it pins today's answers rather than claiming they are right, (b) that
      two of the pinned rows are ADR 59 residuals owned by issues #221 and #222, (c) that a kind is
      compared only where a home exposes one, and (d) that ADR 59's residual is not closed by it.
      Body:

      ```java
      package com.robsartin.segue.export;

      import static com.robsartin.segue.export.InventedGraph.merged;
      import static com.robsartin.segue.export.InventedGraph.minted;
      import static com.robsartin.segue.export.InventedGraph.node;
      import static org.assertj.core.api.Assertions.assertThat;

      import com.robsartin.segue.domain.Equivalences;
      import com.robsartin.segue.domain.LoggedAssertion;
      import com.robsartin.segue.domain.NodeKind;
      import com.robsartin.segue.domain.NodeRecord;
      import com.robsartin.segue.export.InventedGraph.FakeAssertionLog;
      import com.robsartin.segue.ingest.IngestService;
      import com.robsartin.segue.port.IdentityMerge;
      import com.robsartin.segue.ratings.LabelsProbe;
      import com.robsartin.segue.tinker.TinkerGraphStore;
      import java.util.ArrayList;
      import java.util.LinkedHashMap;
      import java.util.LinkedHashSet;
      import java.util.List;
      import java.util.Map;
      import java.util.Objects;
      import java.util.Set;
      import org.junit.jupiter.api.DisplayName;
      import org.junit.jupiter.api.Test;

      class StandInAgreesInEveryHomeTest {

        private static final String APRIL = "Q0011";
        private static final String SIGNAL = "Q0012";
        private static final String TWICE_OVER = "Q0013";
        private static final String CLAIMED_LOCAL = "Q0014";
        private static final String LATE_LOCAL = "Q0015";
        private static final String SPARE = "Q0016";

        private static final String TAPE = "Q10000900201";
        private static final String BEACON = "Q10000900202";
        private static final String FIRST = "Q10000900203";
        private static final String SECOND = "Q10000900204";
        private static final String KNOWN = "Q10000900205";
        private static final String LATER = "Q10000900206";

        private static final String FOLD = "Equivalences.standIns (via LogProjection.of)";
        private static final String LIVE = "IngestService.standIn (live record)";
        private static final String RATINGS = "ratings/Labels.forQids";

        /** The fixture: the spec's table, row for row. No edges and no retractions - see the spec. */
        private static FakeAssertionLog fourHomesLog() {
          return new FakeAssertionLog()
              .with(
                  minted(APRIL, NodeKind.WORK, "the April tape"),
                  merged(APRIL, TAPE),
                  node(SIGNAL, NodeKind.WORK, "a signal a source named", List.of("Q5")),
                  merged(SIGNAL, BEACON),
                  minted(TWICE_OVER, NodeKind.WORK, "the ledger, twice over"),
                  merged(TWICE_OVER, FIRST),
                  merged(TWICE_OVER, SECOND),
                  minted(CLAIMED_LOCAL, NodeKind.WORK, "the owner's working title"),
                  node(KNOWN, NodeKind.GROUP, "the name the source already had"),
                  merged(CLAIMED_LOCAL, KNOWN),
                  minted(LATE_LOCAL, NodeKind.WORK, "the owner's other working title"),
                  merged(LATE_LOCAL, LATER),
                  node(LATER, NodeKind.GROUP, "the name the source brought later"),
                  minted(SPARE, NodeKind.WORK, "the second working title"),
                  merged(SPARE, TAPE));
        }

        /**
         * One canonical id and what the four homes say about it today.
         *
         * @param standInKind what {@code Equivalences.standIns} holds before either fold overlays
         *     the log's own claims; null with {@code standInLabel} when it holds nothing
         * @param shownKind what the projection shows once those claims have landed - the answer all
         *     four homes give
         */
        private record Pinned(
            String canonical,
            NodeKind standInKind,
            String standInLabel,
            NodeKind shownKind,
            String shownLabel) {}

        private static final List<Pinned> PINNED =
            List.of(
                new Pinned(TAPE, NodeKind.WORK, "the April tape", NodeKind.WORK, "the April tape"),
                new Pinned(
                    BEACON,
                    NodeKind.WORK,
                    "a signal a source named",
                    NodeKind.WORK,
                    "a signal a source named"),
                new Pinned(
                    FIRST,
                    NodeKind.WORK,
                    "the ledger, twice over",
                    NodeKind.WORK,
                    "the ledger, twice over"),
                new Pinned(
                    SECOND,
                    NodeKind.WORK,
                    "the ledger, twice over",
                    NodeKind.WORK,
                    "the ledger, twice over"),
                new Pinned(
                    KNOWN,
                    NodeKind.WORK,
                    "the owner's working title",
                    NodeKind.GROUP,
                    "the name the source already had"),
                new Pinned(
                    LATER,
                    NodeKind.WORK,
                    "the owner's other working title",
                    NodeKind.GROUP,
                    "the name the source brought later"));

        // Read once. Every home gets the same fifteen rows, which is the whole point, and building
        // the live graph per assertion would open a TinkerGraph six times over.
        private static final FakeAssertionLog LOG = fourHomesLog();
        private static final Map<String, NodeRecord> IN_THE_FOLD = LogProjection.of(LOG).nodes();
        private static final Map<String, NodeRecord> IN_THE_LIVE_GRAPH = liveGraphNodes();
        private static final Map<String, String> IN_THE_RATINGS_LIST =
            LabelsProbe.forQids(LOG, canonicalIds());

        /** One home's answer: a label always, a kind only where the home exposes one. */
        private record Answer(String label, NodeKind kind) {

          static final Answer NOTHING = new Answer(null, null);

          String describe() {
            if (label == null) {
              return "no node";
            }
            return kind == null ? "\"" + label + "\"" : kind + " \"" + label + "\"";
          }
        }

        @Test
        @DisplayName("every home calls each canonical id the same thing")
        void shouldAgreeOnEveryCanonicalLabelWhenAllFourHomesReadOneLog() {
          List<String> disagreements = new ArrayList<>();
          long answered = 0;
          for (Pinned row : PINNED) {
            List<Map.Entry<String, Answer>> homes = List.copyOf(answersFor(row.canonical()).entrySet());
            for (Map.Entry<String, Answer> home : homes) {
              answered += home.getValue().label() == null ? 0 : 1;
            }
            for (int i = 0; i < homes.size(); i++) {
              for (int j = i + 1; j < homes.size(); j++) {
                if (!Objects.equals(homes.get(i).getValue().label(), homes.get(j).getValue().label())) {
                  disagreements.add(
                      row.canonical()
                          + ": "
                          + homes.get(i).getKey()
                          + " says "
                          + homes.get(i).getValue().describe()
                          + ", "
                          + homes.get(j).getKey()
                          + " says "
                          + homes.get(j).getValue().describe());
                }
              }
            }
          }

          // Homes that all answered nothing would agree perfectly.
          assertThat(answered)
              .as("every home answered for every canonical id the pinned table says is present")
              .isEqualTo(homeCount() * PINNED.stream().filter(r -> r.shownLabel() != null).count());
          assertThat(disagreements)
              .as(
                  "one stand-in rule, %d homes (ADR 59's residual, issue #220) - each line names the"
                      + " pair that disagrees",
                  homeCount())
              .isEmpty();
        }

        @Test
        @DisplayName("both homes that expose a kind give each canonical id the same kind")
        void shouldAgreeOnEveryCanonicalKindWhenBothHomesThatExposeAKindReadOneLog() {
          List<String> disagreements = new ArrayList<>();
          long answered = 0;
          for (Pinned row : PINNED) {
            Answer inFold = fromNode(IN_THE_FOLD.get(row.canonical()));
            Answer inGraph = fromNode(IN_THE_LIVE_GRAPH.get(row.canonical()));
            answered += inFold.kind() == null ? 0 : 1;
            answered += inGraph.kind() == null ? 0 : 1;
            if (!Objects.equals(inFold.kind(), inGraph.kind())) {
              disagreements.add(
                  row.canonical()
                      + ": "
                      + FOLD
                      + " says "
                      + inFold.describe()
                      + ", "
                      + LIVE
                      + " says "
                      + inGraph.describe());
            }
          }

          assertThat(answered)
              .as("both kind-exposing homes answered for every canonical id the table says is present")
              .isEqualTo(2 * PINNED.stream().filter(r -> r.shownKind() != null).count());
          assertThat(disagreements)
              .as("the two homes that expose a kind, on one log - each line names the pair")
              .isEmpty();
        }

        @Test
        @DisplayName("the stand-in answer today, before and after the log's own claims land on it")
        void shouldHoldTodaysStandInAnswerWhenTheFixtureIsRead() {
          Map<String, NodeRecord> standIns = Equivalences.standIns(LOG.readAll());

          for (Pinned row : PINNED) {
            assertThat(describe(standIns.get(row.canonical())))
                .as(
                    "Equivalences.standIns for %s, read raw - it has no \"unless something claimed"
                        + " it\" condition, and gets that guarantee from being applied first",
                    row.canonical())
                .isEqualTo(describe(row.standInKind(), row.standInLabel()));
            assertThat(describe(IN_THE_FOLD.get(row.canonical())))
                .as("what the projection shows for %s once the log's own claims land", row.canonical())
                .isEqualTo(describe(row.shownKind(), row.shownLabel()));
          }

          assertThat(standIns.keySet())
              .as("the pre-pass offers a node for every canonical id a surviving merge names, in log order")
              .containsExactlyElementsOf(
                  PINNED.stream().filter(r -> r.standInLabel() != null).map(Pinned::canonical).toList());
        }

        /** Every home's answer for one canonical id, in a fixed order so a failure reads alike twice. */
        private static Map<String, Answer> answersFor(String canonical) {
          Map<String, Answer> byHome = new LinkedHashMap<>();
          byHome.put(FOLD, fromNode(IN_THE_FOLD.get(canonical)));
          byHome.put(LIVE, fromNode(IN_THE_LIVE_GRAPH.get(canonical)));
          byHome.put(RATINGS, fromLabel(IN_THE_RATINGS_LIST.get(canonical)));
          return byHome;
        }

        /** How many homes {@link #answersFor} reads - Task 2 makes it four. */
        private static long homeCount() {
          return answersFor(TAPE).size();
        }

        /**
         * The live path, which is the only one on which {@code IngestService.standIn} upserts
         * anything: {@code GraphProjector.project} seeds every stand-in from {@code
         * Equivalences.standIns} before its loop, so the canonical node always exists by the time a
         * {@code SameAs} is applied and that copy of the rule never fires.
         */
        private static Map<String, NodeRecord> liveGraphNodes() {
          Map<String, NodeRecord> nodes = new LinkedHashMap<>();
          List<LoggedAssertion> logged = fourHomesLog().readAll();
          try (TinkerGraphStore graph = new TinkerGraphStore()) {
            IngestService ingest =
                new IngestService(new FakeAssertionLog(), graph, IdentityMerge.NONE);
            logged.forEach(ingest::record);
            for (String qid : canonicalIds()) {
              graph.node(qid).ifPresent(node -> nodes.put(qid, node));
            }
          }
          return nodes;
        }

        private static Set<String> canonicalIds() {
          return new LinkedHashSet<>(PINNED.stream().map(Pinned::canonical).toList());
        }

        private static Answer fromNode(NodeRecord node) {
          return node == null ? Answer.NOTHING : new Answer(node.label(), node.kind());
        }

        private static Answer fromLabel(String label) {
          return label == null ? Answer.NOTHING : new Answer(label, null);
        }

        private static String describe(NodeRecord node) {
          return node == null ? "no node" : describe(node.kind(), node.label());
        }

        private static String describe(NodeKind kind, String label) {
          return label == null ? "no node" : kind + " \"" + label + "\"";
        }
      }
      ```

- [ ] **Step 3 — run the guard, and the id sweep.** `./gradlew test --tests
      'com.robsartin.segue.export.StandInAgreesInEveryHomeTest'` — expected **PASS** (three tests).
      Then `./gradlew test --tests 'com.robsartin.segue.arch.StandInQidsDenoteNothingTest'` —
      expected **RED**: `"Q5"` is an allocatable id at a site the allowlist does not declare. Quote
      both outputs. If the guard does **not** pass, do not adjust the pinned table to make it pass
      until you have read the actual values off the failure and checked them against the spec's
      pinned-answers table; a disagreement between the two is a finding to report, not a value to
      overwrite.
- [ ] **Step 4 — declare the site.** In
      `src/test/java/com/robsartin/segue/arch/StandInQidsDenoteNothingTest.java`, add one line to
      `Q5`'s entry, in the existing alphabetical order (after
      `export/ImagemapRecipeTest.java`, before `export/WhatAHoverShowsTest.java`):

      ```java
                  code("src/test/java/com/robsartin/segue/export/StandInAgreesInEveryHomeTest.java"),
      ```

      Re-run `StandInQidsDenoteNothingTest`: **PASS**. Quote it.
- [ ] **Step 5 — positive control 1: `ratings/Labels.forQids` prefers the last merge.** In
      `src/main/java/com/robsartin/segue/ratings/Labels.java`, change

      ```java
              if (local != null && !labels.containsKey(merge.canonicalQid())) {
      ```

      to

      ```java
              if (local != null) {
      ```

      Re-run the guard alone. It must go **RED** on
      `shouldAgreeOnEveryCanonicalLabelWhenAllFourHomesReadOneLog`, with lines naming
      `ratings/Labels.forQids` against each other home at `Q10000900205` (the source's name replaced
      by the owner's working title) **and** at `Q10000900201` (the second merge's name replacing the
      first's). **Quote the failure output verbatim**, including at least one full disagreement line.
      If it reds on a different assertion, or on only one of the two ids, stop and re-read the
      fixture before continuing.
- [ ] **Step 6 — revert control 1 exactly.** `git diff -- src/main/java/com/robsartin/segue/ratings/Labels.java`
      must be empty. Re-run the guard: **PASS**.
- [ ] **Step 7 — positive control 2: the live home invents a kind.** In
      `src/main/java/com/robsartin/segue/ingest/IngestService.java`, inside `standIn`, change

      ```java
            graph.upsertNode(
                new NodeRecord(canonical, minted.get().kind(), minted.get().label(), List.of()));
      ```

      to use `NodeKind.CONCEPT` in place of `minted.get().kind()` (add the
      `com.robsartin.segue.domain.NodeKind` import if the file does not already have it). Re-run the
      guard: it must go **RED** on
      `shouldAgreeOnEveryCanonicalKindWhenBothHomesThatExposeAKindReadOneLog`, naming
      `IngestService.standIn (live record)` against `Equivalences.standIns (via LogProjection.of)`
      on the four ids whose node the stand-in creates (`Q10000900201`–`Q10000900204`). Quote the
      failure. This is the control that proves the kind comparison is not vacuous.
- [ ] **Step 8 — revert control 2 exactly.** `git diff -- src/main/java/com/robsartin/segue/ingest/IngestService.java`
      must be empty. Re-run the guard: **PASS**.
- [ ] **Step 9 — positive control 3: the pre-pass lets the last merge name the id.** In
      `src/main/java/com/robsartin/segue/domain/Equivalences.java`, inside `standIns`, change
      `standIns.putIfAbsent(` to `standIns.put(`. Re-run the guard: it must go **RED** on
      `shouldHoldTodaysStandInAnswerWhenTheFixtureIsRead`, on `Q10000900201`'s raw stand-in row
      (`WORK "the second working title"` where the table says `WORK "the April tape"`).
      `shouldAgreeOnEveryCanonicalLabelWhenAllFourHomesReadOneLog` reds too, naming
      `Equivalences.standIns (via LogProjection.of)` against the other homes — that is the guard
      agreeing with itself, and both reds should be quoted.
- [ ] **Step 10 — revert control 3 exactly.** `git diff -- src/main/java/com/robsartin/segue/domain/Equivalences.java`
      must be empty. Re-run the guard: **PASS**.
- [ ] **Step 11 — gate, blocking.** `SEGUE_REQUIRE_BROWSER=true SEGUE_REQUIRE_GRAPHVIZ=true ./gradlew check --rerun-tasks`.
      Confirm first that `git status` shows no modification under `src/main` — the gate must run
      against the reverted state, not a plant. Quote the build result and the total test count.
- [ ] **Step 12 — commit.** Stage exactly three paths, each named in full, with git's stderr visible:
      `src/test/java/com/robsartin/segue/export/StandInAgreesInEveryHomeTest.java`,
      `src/test/java/com/robsartin/segue/ratings/LabelsProbe.java`,
      `src/test/java/com/robsartin/segue/arch/StandInQidsDenoteNothingTest.java`. Run `git status`
      and read it before committing. The message says the guard covers three of the four homes and
      that Task 2 adds `OwnRun`.

---

### Task 2: the fourth home, and the sentences that said nothing held them to it

**Files:**
- Modify: `src/main/java/com/robsartin/segue/own/OwnRun.java` (one modifier, one javadoc paragraph)
- Modify: `src/main/java/com/robsartin/segue/domain/Equivalences.java` (one javadoc paragraph, no code)
- Modify: `docs/developer-guide.md` (one sentence)
- Create: `src/test/java/com/robsartin/segue/own/ProjectionLabelsProbe.java`
- Modify: `src/test/java/com/robsartin/segue/export/StandInAgreesInEveryHomeTest.java`

**Interfaces:**
- Consumes: `OwnRun.labelsInTheProjection(List, Equivalences)` — visibility widens from `private` to
  package-private; the signature, the body and every existing caller are unchanged.
- Produces: `ProjectionLabelsProbe.labelsInTheProjection`, consumed only by the guard.

- [ ] **Step 1 — the seam.** In `src/main/java/com/robsartin/segue/own/OwnRun.java`, change

      ```java
        private static Map<String, String> labelsInTheProjection(
      ```

      to

      ```java
        static Map<String, String> labelsInTheProjection(
      ```

      and add, as the last paragraph of that method's existing javadoc:

      ```java
       * <p><b>Package-private rather than private, and the reason is named so it is not guessed
       * at.</b> This is the third of the stand-in rule's four homes (ADR 59's residual, issue #220),
       * and {@code StandInAgreesInEveryHomeTest} feeds all four one log and holds them to one answer
       * per canonical id. The alternative was to drive {@code OwnRun.run} with a dry-run {@code
       * OwnCli.Assert} and read the labels back out of the operator note, which would put a prose
       * parser in front of a guard - the shape that turns "cannot read it" into "it is not there".
       * Nothing outside this package calls it, and no ArchUnit rule changes.
      ```

- [ ] **Step 2 — the `own` probe.** Create
      `src/test/java/com/robsartin/segue/own/ProjectionLabelsProbe.java`:

      ```java
      package com.robsartin.segue.own;

      import com.robsartin.segue.domain.Equivalences;
      import com.robsartin.segue.domain.LoggedAssertion;
      import java.util.List;
      import java.util.Map;

      /**
       * Reaches {@link OwnRun#labelsInTheProjection}, which is package-private for this reason, from
       * the stand-in guard in another test package (issue #220).
       */
      public final class ProjectionLabelsProbe {

        private ProjectionLabelsProbe() {}

        /** {@code OwnRun.labelsInTheProjection}, the third home of the stand-in rule. */
        public static Map<String, String> labelsInTheProjection(
            List<LoggedAssertion> logged, Equivalences merges) {
          return OwnRun.labelsInTheProjection(logged, merges);
        }
      }
      ```

- [ ] **Step 3 — wire the fourth home into the guard.** In `StandInAgreesInEveryHomeTest`, add the
      imports `com.robsartin.segue.own.ProjectionLabelsProbe` and the constant

      ```java
        private static final String OWN = "OwnRun.labelsInTheProjection";
      ```

      one read-once constant, declared after `IN_THE_LIVE_GRAPH` so that `LOG` is already
      initialised:

      ```java
        private static final Map<String, String> IN_THE_OWN_TOOL =
            ProjectionLabelsProbe.labelsInTheProjection(LOG.readAll(), Equivalences.in(LOG.readAll()));
      ```

      and one line in `answersFor`, between the `LIVE` and `RATINGS` puts (the map is a
      `LinkedHashMap`, so its position is the order a failure lists the homes in):

      ```java
            byHome.put(OWN, fromLabel(IN_THE_OWN_TOOL.get(canonical)));
      ```

      Nothing else changes: `homeCount()` reads the map's size, and the two counting assertions
      derive from it and from the pinned table.
- [ ] **Step 4 — run the guard.** `./gradlew test --tests
      'com.robsartin.segue.export.StandInAgreesInEveryHomeTest'` — expected **PASS**, three tests,
      now over four homes. Confirm from the run that it is four by checking `homeCount()`'s effect:
      the label test's `answered` count is `4 × 6 = 24`; if the count assertion passes at 18 the new
      home is not in the map. Quote the pass.
- [ ] **Step 5 — positive control 4: the newly added home drops its condition.** In
      `src/main/java/com/robsartin/segue/own/OwnRun.java`, inside `labelsInTheProjection`, change

      ```java
            } else if (assertion instanceof SameAs merge && !labels.containsKey(merge.canonicalQid())) {
      ```

      to

      ```java
            } else if (assertion instanceof SameAs merge) {
      ```

      Re-run the guard: it must go **RED** on
      `shouldAgreeOnEveryCanonicalLabelWhenAllFourHomesReadOneLog`, with lines naming
      `OwnRun.labelsInTheProjection` against each of the other three at `Q10000900205` and
      `Q10000900201`. **Quote the failure verbatim.** This is the control that proves the fourth home
      is really being read and not merely imported — if the guard stays green, Step 3 is wrong.
- [ ] **Step 6 — revert control 4 exactly.** `git diff -- src/main/java/com/robsartin/segue/own/OwnRun.java`
      must show **only** the Step 1 modifier and javadoc change. Re-run the guard: **PASS**.
- [ ] **Step 7 — correct the two sentences that said nothing held them to it.** In
      `src/main/java/com/robsartin/segue/domain/Equivalences.java`, in `standIns`'s four-homes
      paragraph, replace

      ```java
       * the tool offers the canonical id as an endpoint) and {@code ratings/Labels.forQids} (so a carried
       * canonical row is not listed as "not in the graph"). The last two read labels off the log rather
       * than nodes off a graph, which is why they are copies rather than callers. All four agree today,
       * condition for condition; nothing holds them to it but this paragraph.
      ```

      with

      ```java
       * the tool offers the canonical id as an endpoint) and {@code ratings/Labels.forQids} (so a carried
       * canonical row is not listed as "not in the graph"). The last two read labels off the log rather
       * than nodes off a graph, which is why they are copies rather than callers. All four agree today
       * about what the projection holds - though not condition for condition, because this method has no
       * such condition at all and takes its guarantee from being applied first, as the paragraph above
       * says. {@code StandInAgreesInEveryHomeTest} is what holds them to it (issue #220): one log, four
       * homes, one answer per canonical id. It pins what they do rather than claiming it is right, and
       * ADR 59's residual - four homes, not one caller - is untouched by it.
      ```

      Run `./gradlew test --tests 'com.robsartin.segue.arch.JavadocCitationsTest'`: **PASS** — the
      test class the javadoc now names exists under `src/test/java`. Quote it.
- [ ] **Step 8 — one sentence in the developer guide.** In `docs/developer-guide.md`, in the merge
      section, after the sentence ending `... is what stops the replayed graph and the exported
      picture from drifting apart.`, add:

      ```
      `StandInAgreesInEveryHomeTest` is the third guard in that family: the stand-in rule has four
      homes — `Equivalences.standIns`, `IngestService.standIn`, `OwnRun.labelsInTheProjection` and
      `ratings/Labels.forQids` — and it feeds all four one log and reds when any pair of them calls a
      canonical id something different.
      ```

      No markdown link is added, so `DocumentationLinksTest` has nothing new to resolve. Run
      `./gradlew test --tests 'com.robsartin.segue.arch.DocumentationLinksTest' --tests
      'com.robsartin.segue.arch.DeveloperGuideEnumerationsTest'`: **PASS**. Quote it.
- [ ] **Step 9 — gate, blocking.** `SEGUE_REQUIRE_BROWSER=true SEGUE_REQUIRE_GRAPHVIZ=true ./gradlew check --rerun-tasks`.
      Confirm `git status` first: the only `src/main` diff is Step 1's modifier plus the two javadoc
      paragraphs. If `spotlessCheck` fails, `./gradlew spotlessApply` and re-run. Quote the build
      result and the total test count.
- [ ] **Step 10 — commit.** Stage exactly five paths by name, with git's stderr visible:
      `src/main/java/com/robsartin/segue/own/OwnRun.java`,
      `src/main/java/com/robsartin/segue/domain/Equivalences.java`,
      `src/test/java/com/robsartin/segue/own/ProjectionLabelsProbe.java`,
      `src/test/java/com/robsartin/segue/export/StandInAgreesInEveryHomeTest.java`,
      `docs/developer-guide.md`. Read `git status` before committing. The report quotes Step 4's
      pass, Step 5's red (the exact disagreement lines), Step 6's revert diff, and Step 9's gate.

---

## Self-Review

**Spec coverage.** The fixture's fifteen rows, the six pinned canonical ids, the label comparison
across four homes, the kind comparison across the two that expose one, the raw `standIns` reading,
the pair-naming failure message, the two vacuity counts, the `OwnRun` seam with its rejected
alternative, and the four positive controls are all in the steps above. The spec's rejected
alternatives and its #221/#222 notes are cited, not restated.

**Ordering.** Task 1 needs no `src/main` change at all, so the first commit is a pure test addition
that is green on its own; Task 2 adds the one seam and the fourth home. Green at every committed
step, and no plant is ever committed — each control has its own revert step with a `git diff` check
before the next one is planted.

**What could still go wrong.**

- *The guard passes on `main` for the wrong reason.* Controls 1, 2 and 4 each red a different
  assertion for a different home, and control 3 reds the pin; a guard that passed vacuously would
  survive none of them.
- *The pinned values are wrong.* Step 3 of Task 1 says explicitly not to overwrite the table from a
  failure without checking it against the spec first. A disagreement between the code and the
  spec's table is a finding to report.
- *`InventedGraph` gains a conflict with #221 or #222.* It is not edited; the fixture's ids are the
  guard's own.
- *`Equivalences.java` conflicts with #221 or #222.* Task 2 edits one javadoc paragraph there and no
  code, so the conflict is a paragraph merge.
- *#222 lands first.* Then `Equivalences.standIns` takes a `UnaryOperator<NodeAssertion>`; the guard
  has exactly one call site of it (Task 1 Step 2, in the pinned-answer test) and one pinned row to
  change, and the spec's last section records the decision #222 must make about the live path.
