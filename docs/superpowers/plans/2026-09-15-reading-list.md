# A reading list — the `book` kind in the seed tool — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** close issue #333 — the seed tool gains a `book` kind, so a hand-written reading list of
authors and books resolves through `resolveNames` with the same three columns every other list uses.
A book row is a `WORK` **and** an instance of one of the written-work classes the kind mapper already
maps to `WORK`; a film or an edition sharing the title fails the class check and goes to the review
file the owner already reads. Nothing downstream changes: the expander, the deck, the sweep and the
promotion rule all handle a work today.

**Architecture:** one new signal, carried through four types that already exist.

- **`seed.Expectation`** is two sets today — the node kinds a row may resolve to and the occupations
  a person must carry. It gains a third of the same shape, `classes`, with `checksClass()` and
  `acceptsClass(Collection<String>)` mirroring `checksOccupation` / `acceptsOccupation`. A kind with
  an empty class set behaves exactly as it does now.
- **`seed.CandidateFacts`** keeps the raw `P31` list beside the kind the mapper folded it into.
  `WikidataFacts` already fetches `P31`; it threw the list away after folding it.
- **`seed.Adjudicator`** applies the class check beside the kind check, and the review line names
  the classes it saw.
- **`seed.Expectations`** gains `book` → `WORK` plus the written-work classes, and `forKinds`
  carries a class set through the union the resolver actually asks for.
- **`wikidata.KindMapper`** gains three named constants for the three class ids, so `Expectations`
  cites them instead of restating them. The mapper's own table is unchanged otherwise, and **no
  class is added to it**.

**Tech Stack:** Java 25, Gradle (plain `./gradlew`), JUnit 5, AssertJ, ArchUnit.

**Spec:** `docs/superpowers/specs/2026-09-15-reading-list-design.md` — read its **Notes added while
planning** section first; it is where this plan and the spec body differ, and it is the authority
over the body above it. Two things it settles that the body does not:

1. The developer guide never says what the `kind` column may hold, so Task 8 **adds** a sentence
   rather than editing one.
2. Production reaches the expectation through `Expectations.forKinds`, never `forKind` —
   `NameGroup.expectation()` is the only caller — so the class set must survive the union or no
   `book` row is ever class-checked. Task 6 proves both.

## Global Constraints

- **Pure TDD / red first.** Every behaviour: write the test, **run it, observe a real assertion
  failure**, then the minimum code. **A compile error is never a red.** Where a new component or
  accessor is needed before a test can compile, this plan splits the step: add the **stub** (the
  signature, returning the empty/false/wrong-but-compiling answer), then the test, then observe the
  assertion failure, then the body. **Quote the actual failure text in the task report** — not "it
  failed". Where a step's test passes against the stub, this plan says so and names the sibling
  assertion that is the red.
- **Every guard gets a planted positive control, as its own step.** The controls this plan requires
  by name: **Task 3 Step 5** (the class clause taken out of `describeMismatch`, so the refusal's
  reason no longer names it — the review line is its own guard, and the outcome red does not cover
  it); **Task 5 Step 6** (`forKinds` made to drop the class set again, so the table is right while
  the path the resolver takes is empty); **Task 5 Step 7** (the `anyUnconstrainedClass` branch
  removed, so a name listed as both a book and an author is class-checked after all); **Task 6
  Step 3** (`readList` made to refuse a blank status). Each control is planted, seen to fire with
  its text quoted, and removed, and the suite is green again before the commit.
- Test names `should<Expected>When<Condition>` with `@DisplayName`.
- **Mikado**: the gate is green before every commit, and each task ends green and is independently
  reviewable. **Stage by explicit path, git stderr visible — never `git add -A`, never `2>/dev/null`
  on `git add`.** Read `git status` before every commit. Commits end, after a blank line,
  `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`. **Never cite a `.superpowers/` path
  from a committed file.**
- Gate, **blocking, never backgrounded**:
  `SEGUE_REQUIRE_BROWSER=true SEGUE_REQUIRE_GRAPHVIZ=true ./gradlew check --rerun-tasks`. Run
  `./gradlew spotlessApply` before each gate. Per-task loops are
  `./gradlew test --tests '…'`, also blocking, also plain `./gradlew`.
- **Only JDK 25 is installed and Gradle launches on it.** Plain `./gradlew`; never
  `/usr/libexec/java_home -v 21` (it silently returns 25).
- **Never run a writing dev task** — `resolveNames`, `expandPromotions`, `own`, `ownClaim`,
  `retractEntity`, `rate`, `evaluate`, any seeding task. `~/.segue/segue.db` is never read, written,
  copied or created.
- **No test in this plan may reach the network.** Everything here is either a pure function over
  invented facts or a call against `StubWikidataServer`. If a step would put an HTTP call in
  `check`, stop and report.
- **No wall-clock assertion anywhere.** The machine is loaded.
- **Invented ids only, ADR 58's leading zero.** The ids this plan introduces are `Q0901601` to
  `Q0901612`; `grep -rn 'Q09016' src docs` finds none of them today (checked while planning, and
  Task 1 Step 1 re-checks it). The block covers **both** the invented entities and the invented
  *class* ids the fixtures state. The leading-zero form is exempt from
  `StandInQidsDenoteNothingTest`'s sweep. **No test or fixture in this plan names a real Wikidata
  class id as a literal**: where a test has to reach the production set it names
  `KindMapper.BOOK`, `KindMapper.LITERARY_WORK` and `KindMapper.WRITTEN_WORK`, which are code and
  not literals, so the allowlist needs no new entry. The one real id any test here writes is `Q5`,
  already in the fixture `WikidataFactsTest` owns and already an allowed site in
  `StandInQidsDenoteNothingTest.ALLOWED`. **If any step tempts you to add an entry to that
  allowlist, stop and report instead.**
- **After `./gradlew spotlessApply`, re-read any javadoc this plan writes** and confirm every
  `{@code …}` span is intact and **on one source line**. google-java-format reflows javadoc and will
  break inside an inline tag. The mechanical check is
  `grep -n '{@code [^}]*$' <file>` — it must print nothing. To put a span on one line, shorten the
  clause *before* it.
- **Markdown links whole and on one line, in the form the file that holds them needs.** A link in
  `docs/adr/*.md` is relative to `docs/adr/`; a link in `docs/developer-guide.md` is relative to
  `docs/`. **This plan's own prose names every path in backticks and links to nothing**, because a
  relative link written for an ADR does not resolve from `docs/superpowers/plans/` and
  `DocumentationLinksTest` scans the whole of `docs/`. Verbatim document text lives inside a fence,
  which that class strips before matching. **Run
  `./gradlew test --tests '*DocumentationLinksTest'` before every commit that touches a `.md`
  file**, including the commit that lands this plan.
- **No commit hash, no `.superpowers/` path, no qid, and no figure from the owner's graph enters any
  file under `docs/adr`.** `AdrCitationsTest` reads a backticked run of 7–40 hex characters as a
  commit citation, and a qid is close enough to that shape to be worth avoiding outright: the ADR
  amendment writes the three classes' **names in words** — book, literary work, written work — and
  never their ids.
- **`docs/` and `README.md` are already declared inputs of the `test` task** (`build.gradle.kts`,
  the `inputs.dir("docs")` / `inputs.file("README.md")` pair). **Verify that, do not re-declare it**,
  and run the per-task loops **without** `--rerun-tasks`.
- **YAGNI.** No parameter, helper or abstraction ahead of a real need. In particular: the accept
  reason's wording ("name, kind and occupation agree") is **not** changed by this plan — only the
  refusal names the class check, which is what the spec asks for and what a person reading the
  review file needs.

---

## Task 1 — `Expectation` gains a class set, and every existing kind names none

Files: `src/main/java/com/robsartin/segue/seed/Expectation.java`,
`src/main/java/com/robsartin/segue/seed/Expectations.java`,
`src/test/java/com/robsartin/segue/seed/ExpectationTest.java` (new).

**Read first.** `Expectation` is a record of two sets and three methods; `Expectations` is its only
producer, at three sites — `put`, `forKinds` and `unconstrained`. Nothing else in the tree calls
`new Expectation(`, so the third component costs three edits in one file plus the nineteen `put`
lines. `ExpectationsTest`'s nine existing cases are the control that nothing about the kind and
occupation halves moved.

- [ ] **Step 1 — confirm the ground before touching anything.**

  ```
  grep -rn 'Q09016' src docs
  grep -rn 'new Expectation(' src
  ls src/test/java/com/robsartin/segue/seed/
  ```

  Expected: the first finds **nothing** (exit 1) except this plan and the spec under
  `docs/superpowers/`; the second finds exactly three lines, all in `Expectations.java`; the third
  lists `AdjudicatorTest`, `ExpectationsTest`, `NamesTest`, `SeedCliTest`, `SeedFilesTest`,
  `SeedResolverTest`, `SeedRunTest`, `WikidataFactsTest` and **no** `ExpectationTest`. If any of
  those is different, stop and report.

- [ ] **Step 2 — the component and the stubs.** In `Expectation.java`, add the third component and
  the two methods, the methods deliberately **wrong so a test can fail on an assertion**:

  ```java
  public record Expectation(Set<NodeKind> kinds, Set<String> occupations, Set<String> classes) {

    public Expectation {
      kinds = Set.copyOf(Objects.requireNonNull(kinds, "kinds"));
      occupations = Set.copyOf(Objects.requireNonNull(occupations, "occupations"));
      classes = Set.copyOf(Objects.requireNonNull(classes, "classes"));
    }
  ```

  and, after `acceptsOccupation`:

  ```java
    public boolean checksClass() {
      return false;
    }

    public boolean acceptsClass(Collection<String> p31) {
      Objects.requireNonNull(p31, "p31");
      return true;
    }
  ```

  `Collection`, `Objects` and `Set` are already imported.

- [ ] **Step 3 — carry the third argument through `Expectations`, every kind naming no class.**
  Change the private `put` signature and its body:

  ```java
    private static void put(
        String kind, Set<NodeKind> kinds, Set<String> occupations, Set<String> classes) {
      Expectation prior = BY_KIND.put(kind, new Expectation(kinds, occupations, classes));
      if (prior != null) {
        throw new IllegalStateException("two expectations claim the kind " + kind);
      }
    }
  ```

  Replace the whole static block with the same nineteen kinds, each gaining `Set.of()` as its
  fourth argument and **every comment kept exactly as it is**:

  ```java
    static {
      // A musician on this list is as often a band as a person, so both kinds are allowed and the
      // occupation check only bites on the ones that turn out to be human.
      put("musician", EnumSet.of(NodeKind.PERSON, NodeKind.GROUP), MUSIC, Set.of());
      put("composer", EnumSet.of(NodeKind.PERSON), MUSIC, Set.of());
      put("conductor", EnumSet.of(NodeKind.PERSON), MUSIC, Set.of());
      put("comedian", EnumSet.of(NodeKind.PERSON, NodeKind.GROUP), COMEDY, Set.of());
      put("author", EnumSet.of(NodeKind.PERSON), WRITING, Set.of());
      put("actor", EnumSet.of(NodeKind.PERSON), ACTING, Set.of());
      put("director", EnumSet.of(NodeKind.PERSON), DIRECTING, Set.of());
      put("broadcaster", EnumSet.of(NodeKind.PERSON), BROADCASTING, Set.of());
      // Groups: no occupation exists to check, so the kind is the whole test.
      put("a-cappella", EnumSet.of(NodeKind.GROUP), Set.of(), Set.of());
      put("tribute", EnumSet.of(NodeKind.GROUP), Set.of(), Set.of());
      put("orchestra", EnumSet.of(NodeKind.GROUP), Set.of(), Set.of());
      put("choir", EnumSet.of(NodeKind.GROUP), Set.of(), Set.of());
      put("ensemble", EnumSet.of(NodeKind.GROUP), Set.of(), Set.of());
      put("org", EnumSet.of(NodeKind.GROUP), Set.of(), Set.of());
      put("tv-show", EnumSet.of(NodeKind.WORK), Set.of(), Set.of());
      // A fictional character has no NodeKind of its own — ADR 21 has six and none of them is
      // "character" — so it lands in CONCEPT, which is what an unmapped P31 always becomes.
      put("character", EnumSet.of(NodeKind.CONCEPT), Set.of(), Set.of());
      // No usable occupation vocabulary, so these constrain the kind and nothing else.
      put("public-figure", EnumSet.of(NodeKind.PERSON), Set.of(), Set.of());
      put("puppeteer", EnumSet.of(NodeKind.PERSON), Set.of(), Set.of());
    }
  ```

  And the other two construction sites:

  ```java
      return new Expectation(nodeKinds, anyUnconstrained ? Set.of() : occupations, Set.of());
  ```

  ```java
    private static Expectation unconstrained() {
      return new Expectation(ANY_KIND, Set.of(), Set.of());
    }
  ```

  **`forKinds` keeps `Set.of()` on purpose.** No kind names a class yet, so the union has nothing to
  carry, and writing the union rule here would be production code no test had asked for. Task 5
  drives it out red-first.

- [ ] **Step 4 — the existing suite is the control that nothing moved.**

  ```
  ./gradlew test --tests '*ExpectationsTest' --tests 'com.robsartin.segue.seed.*'
  ```

  Expected: green, with a non-zero count in `seed` — a `--tests` filter that matched nothing is the
  failure mode here. Record the count.

- [ ] **Step 5 — RED: the new `ExpectationTest`.** Create
  `src/test/java/com/robsartin/segue/seed/ExpectationTest.java`:

  ```java
  package com.robsartin.segue.seed;

  import static org.assertj.core.api.Assertions.assertThat;

  import com.robsartin.segue.domain.NodeKind;
  import java.util.EnumSet;
  import java.util.List;
  import java.util.Set;
  import org.junit.jupiter.api.DisplayName;
  import org.junit.jupiter.api.Test;

  /**
   * The class half of an expectation, on its own and beside {@link ExpectationsTest}, which is
   * about the table rather than the rule.
   *
   * <p>Every identifier here is invented and denotes nothing (ADR 58). What is under test is the
   * set rule; which real classes a {@code book} row takes is {@code Expectations}' business, and
   * naming one here would tie this file to that vocabulary for no gain.
   */
  class ExpectationTest {

    private static final String IN_THE_SET = "Q0901610";
    private static final String ALSO_IN_THE_SET = "Q0901612";
    private static final String OUTSIDE_THE_SET = "Q0901611";

    private static Expectation work(Set<String> classes) {
      return new Expectation(EnumSet.of(NodeKind.WORK), Set.of(), classes);
    }

    @Test
    @DisplayName("a work stating one of the kind's classes is the kind of thing the list meant")
    void shouldAcceptTheWorkWhenItStatesOneOfTheKindsClasses() {
      Expectation expectation = work(Set.of(IN_THE_SET, ALSO_IN_THE_SET));

      assertThat(expectation.acceptsClass(List.of(ALSO_IN_THE_SET))).isTrue();
      assertThat(expectation.acceptsClass(List.of(OUTSIDE_THE_SET, IN_THE_SET)))
          .as("one stated class in the set is enough, as one occupation already is")
          .isTrue();
    }

    @Test
    @DisplayName("a work stating only classes outside the set is refused")
    void shouldRefuseTheWorkWhenEveryStatedClassIsOutsideTheSet() {
      Expectation expectation = work(Set.of(IN_THE_SET));

      assertThat(expectation.acceptsClass(List.of(OUTSIDE_THE_SET))).isFalse();
      assertThat(expectation.acceptsClass(List.of()))
          .as("no class at all is a question, not an answer — the occupation rule, verbatim")
          .isFalse();
    }

    @Test
    @DisplayName("a kind that names no class takes any class, and no class at all")
    void shouldAcceptAnyClassWhenTheKindNamesNone() {
      Expectation expectation = work(Set.of());

      assertThat(expectation.acceptsClass(List.of(OUTSIDE_THE_SET))).isTrue();
      assertThat(expectation.acceptsClass(List.of())).isTrue();
    }

    @Test
    @DisplayName("the class is a real check only when the kind names one")
    void shouldCheckTheClassOnlyWhenTheKindNamesOne() {
      assertThat(work(Set.of(IN_THE_SET)).checksClass()).isTrue();
      assertThat(work(Set.of()).checksClass()).isFalse();
    }
  }
  ```

  Run it:

  ```
  ./gradlew test --tests '*ExpectationTest'
  ```

  **Expected: two real assertion failures**, of the shape:

  ```
  ExpectationTest > shouldRefuseTheWorkWhenEveryStatedClassIsOutsideTheSet FAILED
      java.lang.AssertionError:
      Expecting value to be false but was true

  ExpectationTest > shouldCheckTheClassOnlyWhenTheKindNamesOne FAILED
      java.lang.AssertionError:
      Expecting value to be true but was false
  ```

  **The other two cases pass against the stub, and that is expected rather than a problem**: a stub
  that always accepts satisfies "accepts", and a stub set that is never read satisfies "an empty set
  accepts anything". They are meaningful only once the two above are green, which is the next step.
  Quote the real text of both failures in the task report, and say which two cases passed vacuously.

- [ ] **Step 6 — GREEN: the bodies and the javadoc.** Replace the two stubs:

  ```java
    /** Whether the class is a real check for this kind, or vacuous. */
    public boolean checksClass() {
      return !classes.isEmpty();
    }

    /**
     * Whether these {@code P31} values are compatible.
     *
     * <p>The same shape as {@link #acceptsOccupation}, and the same refusal: a work that states no
     * class this kind names does not pass a kind that checks one. A title is ambiguous across
     * editions, translations and adaptations, and the stated class is the only thing in the fetched
     * facts that tells them apart.
     */
    public boolean acceptsClass(Collection<String> p31) {
      Objects.requireNonNull(p31, "p31");
      return !checksClass() || p31.stream().anyMatch(classes::contains);
    }
  ```

  And the class javadoc — keep the first paragraph's opening sentence structure, replace "Two
  independent signals" with three:

  ```java
  /**
   * What the input list's {@code kind} column says a candidate must look like.
   *
   * <p>Three independent signals, because none of them is sufficient alone. {@link NodeKind} comes
   * from {@code P31} and separates a person from a band from a film; it cannot separate a musician
   * from a minister, because both are {@code Q5}. Occupation comes from {@code P106} and does
   * exactly that. The classes are {@code P31} again, unfolded — the kind a mapper folded it into is
   * too coarse for a title, because albums, films and episodes are all works.
   *
   * <p>An empty {@code occupations} set means "this kind constrains no occupation" — a band has no
   * {@code P106} at all, and neither does a television series. An empty {@code classes} set says
   * the same about the classes, and every kind but {@code book} has one.
   */
  ```

  Re-run Step 5's command: green, four cases.

- [ ] **Step 7 — format, check the spans, gate, commit.**

  ```
  ./gradlew spotlessApply
  grep -n '{@code [^}]*$' src/main/java/com/robsartin/segue/seed/Expectation.java
  SEGUE_REQUIRE_BROWSER=true SEGUE_REQUIRE_GRAPHVIZ=true ./gradlew check --rerun-tasks
  ```

  The `grep` must print nothing. Then `git status`, stage by explicit path, commit:

  > Give an expectation a class set, checked as occupation is (#333)

---

## Task 2 — `CandidateFacts` keeps the raw `P31` beside the kind it was folded into

Files: `src/main/java/com/robsartin/segue/seed/CandidateFacts.java`,
`src/main/java/com/robsartin/segue/seed/WikidataFacts.java`,
`src/test/java/com/robsartin/segue/seed/AdjudicatorTest.java`,
`src/test/java/com/robsartin/segue/seed/WikidataFactsTest.java`.

**Read first.** `WikidataFacts.readBatch` already reads `ClaimMapper.instanceOf(entity)` and hands
it straight to `KindMapper.fromInstanceOf`, keeping only the answer. There are exactly eight
construction sites for the record — one in `WikidataFacts`, seven in `AdjudicatorTest`, two of those
inside that file's `person` and `group` helpers — so the component is added everywhere in one
structural step rather than through a parallel field.

- [ ] **Step 1 — confirm the eight sites.**

  ```
  grep -rn 'new CandidateFacts(' src
  ```

  Expected: one line in `src/main/java/com/robsartin/segue/seed/WikidataFacts.java` and seven in
  `src/test/java/com/robsartin/segue/seed/AdjudicatorTest.java`. If there are more, stop and report
  — this step's whole safety is that the list is short enough to change at once.

- [ ] **Step 2 — the component, and every producer passing an empty list.** In
  `CandidateFacts.java`, insert `List<String> classes` **immediately after `kind`**, add the copy to
  the compact constructor, and add the `@param`:

  ```java
  /**
   * ...
   * @param aliases the entity's other recorded English names — Wikidata's own claim that this thing
   *     is also called that, which is how a duo billed under an early name is still found
   * @param classes the raw {@code P31} values, kept beside the kind they were folded into. The fold
   *     is lossy on purpose — segue has six kinds — and a title needs what was lost: a book, a film
   *     of the book and a printing of the book are one kind and three classes.
   * @param sitelinks how many Wikipedias carry an article, standing in for how well known it is
   */
  public record CandidateFacts(
      String qid,
      String label,
      String description,
      List<String> aliases,
      NodeKind kind,
      List<String> classes,
      List<String> occupations,
      int sitelinks) {

    public CandidateFacts {
      Objects.requireNonNull(qid, "qid");
      Objects.requireNonNull(label, "label");
      aliases = List.copyOf(Objects.requireNonNull(aliases, "aliases"));
      Objects.requireNonNull(kind, "kind");
      classes = List.copyOf(Objects.requireNonNull(classes, "classes"));
      occupations = List.copyOf(Objects.requireNonNull(occupations, "occupations"));
    }
  ```

  In `WikidataFacts.readBatch`, pass `List.of()` in the new slot for now — the stub:

  ```java
            new CandidateFacts(
                qid,
                label,
                ClaimMapper.description(entity),
                ClaimMapper.aliases(entity),
                kind,
                List.of(),
                ClaimMapper.itemValues(entity, OCCUPATION),
                entity.path("sitelinks").size()));
  ```

  In `AdjudicatorTest`, put `List.of(),` after the `NodeKind` argument at all seven sites. The two
  helpers become:

  ```java
    private static CandidateFacts person(
        String qid, String label, int sitelinks, String... occupations) {
      return new CandidateFacts(
          qid,
          label,
          "a description",
          List.of(),
          NodeKind.PERSON,
          List.of(),
          List.of(occupations),
          sitelinks);
    }

    private static CandidateFacts group(String qid, String label, int sitelinks) {
      return new CandidateFacts(
          qid, label, "a band", List.of(), NodeKind.GROUP, List.of(), List.of(), sitelinks);
    }
  ```

  and the five inline ones are in `anAliasIsAName`, `aLabelMatchBeatsAnAliasMatch`,
  `anAliasMatchNeedsASubstantialName`, `theWrongKindIsSkipped` and `everyNameMatchTheWrongKind`.

- [ ] **Step 3 — the seed suite is the control that the structural change changed no answer.**

  ```
  ./gradlew test --tests 'com.robsartin.segue.seed.*'
  ```

  Expected: green. Record the count; it must equal Task 1 Step 4's plus the four new
  `ExpectationTest` cases.

- [ ] **Step 4 — RED: the stub server says what the entity states.** In `WikidataFactsTest`, inside
  `readsEverythingTheDecisionNeeds`, add one assertion immediately after the `kind` assertion:

  ```java
        assertThat(person.classes())
            .as("the raw P31, kept beside the kind the mapper folded it into")
            .containsExactly("Q5");
  ```

  `Q5` is already a literal in this file's fixture and already an allowed site in
  `StandInQidsDenoteNothingTest.ALLOWED` — **do not add an allowlist entry**, and if the arch test
  reds here, stop and report rather than editing the allowlist. Also rename the case's
  `@DisplayName` to match what it now covers:

  ```java
    @DisplayName("one call carries label, aliases, sitelink count, kind, classes and occupations")
  ```

  Run:

  ```
  ./gradlew test --tests '*WikidataFactsTest'
  ```

  **Expected failure — a real assertion:**

  ```
  java.lang.AssertionError: [the raw P31, kept beside the kind the mapper folded it into]
  Expecting actual:
    []
  to contain exactly (and in same order):
    ["Q5"]
  but could not find the following elements:
    ["Q5"]
  ```

  Quote the real text in the task report.

- [ ] **Step 5 — GREEN: read the list once and keep it.** In `WikidataFacts.readBatch`, replace

  ```java
        NodeKind kind = KindMapper.fromInstanceOf(ClaimMapper.instanceOf(entity));
  ```

  with

  ```java
        List<String> classes = ClaimMapper.instanceOf(entity);
  ```

  and, in the constructor call, pass `KindMapper.fromInstanceOf(classes)` in the `kind` slot and
  `classes` in the new one. **Delete `import com.robsartin.segue.domain.NodeKind;`** — line 82 was
  its only use, and an unused import fails the format gate.

  Re-run Step 4's command: green.

- [ ] **Step 6 — format, gate, commit.**

  ```
  ./gradlew spotlessApply
  grep -n '{@code [^}]*$' src/main/java/com/robsartin/segue/seed/CandidateFacts.java
  SEGUE_REQUIRE_BROWSER=true SEGUE_REQUIRE_GRAPHVIZ=true ./gradlew check --rerun-tasks
  ```

  `git status`, stage by explicit path, commit:

  > Keep a candidate's stated classes beside the kind they folded into (#333)

---

## Task 3 — `Adjudicator` checks the class beside the kind, and the review line says so

Files: `src/main/java/com/robsartin/segue/seed/Adjudicator.java`,
`src/test/java/com/robsartin/segue/seed/AdjudicatorTest.java`.

**Read first.** `fits` is the whole filter: kind, then occupation for a `PERSON` only. Everything
after it — the label-match preference, the sitelink ranking, the margin — reads whatever `fits`
let through, so a class check placed inside `fits` gets the ordering for free: an edition that would
have won the margin never reaches it. `decide` is public and takes an `Expectation` directly, so
this task needs no `book` kind in the table and invents its own classes; Task 5 wires the real ones.

- [ ] **Step 1 — the fixtures, in `AdjudicatorTest`.** Add beside the two occupation constants:

  ```java
    // Invented classes (ADR 58), because what is under test here is the check and not the
    // vocabulary. Which real classes a book row takes is Expectations' decision, pinned in
    // ExpectationsTest.
    private static final String WRITTEN_CLASS = "Q0901601";
    private static final String OTHER_WRITTEN_CLASS = "Q0901602";
    private static final String FILM_CLASS = "Q0901603";
    private static final String EDITION_CLASS = "Q0901604";

    /** A kind that takes a work of either written class — the shape Expectations gives "book". */
    private static final Expectation BOOK =
        new Expectation(
            EnumSet.of(NodeKind.WORK), Set.of(), Set.of(WRITTEN_CLASS, OTHER_WRITTEN_CLASS));

    private static CandidateFacts work(String qid, String label, int sitelinks, String... classes) {
      return new CandidateFacts(
          qid, label, "a work", List.of(), NodeKind.WORK, List.of(classes), List.of(), sitelinks);
    }
  ```

  Add `import java.util.EnumSet;` and `import java.util.Set;` in import order.

- [ ] **Step 2 — RED: the four cases.** Append to `AdjudicatorTest`:

  ```java
    @Test
    @DisplayName("a work whose stated class is one the kind names is accepted")
    void shouldAcceptTheWorkWhenItStatesAClassTheKindNames() {
      Decision decision =
          Adjudicator.decide(
              "The Salt Almanac",
              BOOK,
              List.of(work("Q0901605", "The Salt Almanac", 9, WRITTEN_CLASS)));

      assertThat(decision.outcome()).isEqualTo(Outcome.ACCEPTED);
      assertThat(decision.qid()).isEqualTo("Q0901605");
    }

    @Test
    @DisplayName("the film of the book is refused on its class, however well known it is")
    void shouldReviewTheFilmWhenOnlyItsClassSeparatesItFromTheBook() {
      Decision refused =
          Adjudicator.decide(
              "The Salt Almanac",
              BOOK,
              List.of(work("Q0901606", "The Salt Almanac", 300, FILM_CLASS)));

      assertThat(refused.outcome()).isEqualTo(Outcome.REVIEW);
      assertThat(refused.reason())
          .as("the line a person reads has to say which signal refused it, and what it saw")
          .contains("class")
          .contains(FILM_CLASS);
      assertThat(refused.qid()).as("the candidate is still reported").isEqualTo("Q0901606");

      // The control, one field wide: the same title, the same sitelink count, the same identifier,
      // one class changed. Without it, the refusal above could be about anything.
      Decision accepted =
          Adjudicator.decide(
              "The Salt Almanac",
              BOOK,
              List.of(work("Q0901606", "The Salt Almanac", 300, WRITTEN_CLASS)));

      assertThat(accepted.outcome()).isEqualTo(Outcome.ACCEPTED);
    }

    @Test
    @DisplayName("an edition does not outrank the work it is an edition of")
    void shouldPreferTheWorkWhenAnEditionSharesItsTitleAndIsBetterKnown() {
      // The edition would win the margin outright. It never reaches it: the class check sits
      // inside the same filter as the kind check, so the ranking only ever sees what fits.
      Decision decision =
          Adjudicator.decide(
              "The Salt Almanac",
              BOOK,
              List.of(
                  work("Q0901607", "The Salt Almanac", 120, EDITION_CLASS),
                  work("Q0901605", "The Salt Almanac", 9, WRITTEN_CLASS)));

      assertThat(decision.outcome()).isEqualTo(Outcome.ACCEPTED);
      assertThat(decision.qid()).isEqualTo("Q0901605");
    }

    @Test
    @DisplayName("two written works one title apart are a question for a person")
    void shouldReviewWhenTwoWrittenWorksShareATitleWithinTheMargin() {
      Decision decision =
          Adjudicator.decide(
              "The Salt Almanac",
              BOOK,
              List.of(
                  work("Q0901605", "The Salt Almanac", 20, WRITTEN_CLASS),
                  work("Q0901608", "The Salt Almanac", 17, OTHER_WRITTEN_CLASS)));

      assertThat(decision.outcome()).isEqualTo(Outcome.REVIEW);
      assertThat(decision.reason()).contains("margin");
    }
  ```

  Run:

  ```
  ./gradlew test --tests '*AdjudicatorTest'
  ```

  **Expected: two real assertion failures**, of the shape:

  ```
  AdjudicatorTest > shouldReviewTheFilmWhenOnlyItsClassSeparatesItFromTheBook FAILED
      java.lang.AssertionError:
      expected: REVIEW
       but was: ACCEPTED

  AdjudicatorTest > shouldPreferTheWorkWhenAnEditionSharesItsTitleAndIsBetterKnown FAILED
      java.lang.AssertionError:
      expected: "Q0901605"
       but was: "Q0901607"
  ```

  The acceptance case and the margin case pass already — a kind check alone accepts a work and a
  thin margin is a thin margin — and that is said out loud rather than counted as evidence. Quote
  both real failures in the task report.

- [ ] **Step 3 — GREEN: the check, in `fits`.** Replace `fits` with:

  ```java
    /**
     * Whether this candidate is the kind of thing the list said it was.
     *
     * <p>The occupation half applies to people only. A band has no {@code P106}, so requiring one
     * would reject every band, and a television series has none either.
     *
     * <p>The class half applies to whatever names one. {@code WORK} covers albums, films, episodes
     * and books alike, so a kind that means a written work says which classes it will take — and it
     * is checked HERE, inside the filter, rather than after the ranking: an edition or an
     * adaptation is regularly the better known of the two, and a check that ran after the margin
     * would be a check the margin had already lost.
     */
    private static boolean fits(Expectation expectation, CandidateFacts candidate) {
      if (!expectation.acceptsKind(candidate.kind())) {
        return false;
      }
      if (!expectation.acceptsClass(candidate.classes())) {
        return false;
      }
      return candidate.kind() != NodeKind.PERSON
          || expectation.acceptsOccupation(candidate.occupations());
    }
  ```

  Then the refusal's reason and the mismatch description:

  ```java
        return new Decision(
            Outcome.REVIEW,
            closest.qid(),
            closest.label(),
            "name matches but the kind, class or occupation does not: "
                + describeMismatch(expectation, named));
  ```

  ```java
    private static String describeMismatch(Expectation expectation, List<CandidateFacts> named) {
      StringBuilder out = new StringBuilder();
      for (CandidateFacts candidate : named) {
        if (!out.isEmpty()) {
          out.append("; ");
        }
        out.append(candidate.describe()).append(" is ").append(candidate.kind());
        if (expectation.checksClass()) {
          out.append(" of classes ").append(candidate.classes());
        }
        if (candidate.kind() == NodeKind.PERSON) {
          out.append(" with occupations ").append(candidate.occupations());
        }
      }
      return out.toString();
    }
  ```

  The classes are shown only when the kind checks them, for the reason the occupations are shown
  only for a person: a review line that lists every stated class of every candidate on every row is
  a line nobody finishes reading.

  Finally, the class javadoc's second signal — keep the list at three items and extend the second:

  ```java
   *   <li><b>The kind, and for a person the occupation, and for a written work the class.</b>
   *       {@code P31} separates a person from a band from a film. It does not separate a musician
   *       from a minister — every human is {@code Q5} — so for a {@code PERSON} the input list's
   *       {@code kind} column is checked against {@code P106}. It does not separate a book from the
   *       film of the book either, because both fold to {@code WORK}, so a kind that names classes
   *       is checked against the raw {@code P31} as well. This is the signal that stops a confident
   *       wrong answer, which is the only kind of wrong answer that matters here.
   ```

  Re-run Step 2's command: green, and the twelve cases that were there before are green too.

- [ ] **Step 4 — the accept reason is deliberately unchanged, and the report says so.** It still
  reads "name, kind and occupation agree". Changing it is out of scope: the spec asks only that a
  refusal name the class check, an accepted row is not read by a person the way a review line is,
  and the wording is asserted by `anAcceptedDecisionShowsItsWorking` only as "not blank". Note this
  in the task report rather than silently widening the change.

- [ ] **Step 5 — PLANTED CONTROL: the review line's own guard.** Step 2's red proves the *outcome*
  moves. It does not prove the *reason* names the class — that is a second guard, on the text a
  person reads, and it gets its own plant. Temporarily remove the class clause from
  `describeMismatch`:

  ```java
        // PLANT — remove after this step
        // if (expectation.checksClass()) {
        //   out.append(" of classes ").append(candidate.classes());
        // }
  ```

  ```
  ./gradlew test --tests '*AdjudicatorTest'
  ```

  **Expected: `shouldReviewTheFilmWhenOnlyItsClassSeparatesItFromTheBook` fails**, on the
  `.contains(FILM_CLASS)` half only:

  ```
  java.lang.AssertionError: [the line a person reads has to say which signal refused it, and what it saw]
  Expecting actual:
    "name matches but the kind, class or occupation does not: Q0901606 (The Salt Almanac — a work) is WORK"
  to contain:
    "Q0901603"
  ```

  Quote the real text. **Restore the three lines**, re-run: green. Say in the task report that the
  plant was removed and the suite was green again before the commit.

- [ ] **Step 6 — format, check the spans, gate, commit.**

  ```
  ./gradlew spotlessApply
  grep -n '{@code [^}]*$' src/main/java/com/robsartin/segue/seed/Adjudicator.java
  SEGUE_REQUIRE_BROWSER=true SEGUE_REQUIRE_GRAPHVIZ=true ./gradlew check --rerun-tasks
  ```

  `git status` — confirm the plant is not in the diff — stage by explicit path, commit:

  > Refuse a candidate whose stated class the kind does not name (#333)

---

## Task 4 — Three named classes in `KindMapper`, so the seed table cites them

Files: `src/main/java/com/robsartin/segue/wikidata/KindMapper.java`.

**This task has no red, and that is honest rather than an omission.** It changes no behaviour: three
`put` lines take a constant where they took a literal, and the constant holds the same string. Its
verification is `KindMapperTest` and the full gate passing unchanged, and the task report must say so
in those words.

**Why it exists.** Task 5 needs the three written-work class ids in `seed`. Writing them there as
literals would be a second copy of three rows of this table, and this class's own note says a second
copy of the list is the one a later editor forgets. `support.ClassLabels` does keep its own copy, and
deliberately — it is a display table that answers a different question and its javadoc argues the
case — which is not a precedent for a table that is *derived* from this one. So the ids get one home
each, here, and `Expectations` names them.

- [ ] **Step 1 — the constants.** In `KindMapper`, immediately after the class declaration and
  before `BY_CLASS`:

  ```java
    /** The class Wikidata calls "book". */
    public static final String BOOK = "Q571";

    /** The class Wikidata calls "literary work". */
    public static final String LITERARY_WORK = "Q7725634";

    /** The class Wikidata calls "written work". */
    public static final String WRITTEN_WORK = "Q47461344";
  ```

- [ ] **Step 2 — the table names them.** In the works block, replace the three literals:

  ```java
      put(LITERARY_WORK, NodeKind.WORK); // literary work
      put(BOOK, NodeKind.WORK); // book
  ```

  ```java
      put(WRITTEN_WORK, NodeKind.WORK); // written work
  ```

  The trailing comments stay: this block's comment column is what a reader scans for a label, and
  three rows without one would read as three rows of a different kind.

- [ ] **Step 3 — the class javadoc says why three of sixty are named.** Append a paragraph after the
  `PRECEDENCE` paragraph of the class javadoc:

  ```java
   * <p>Three rows below are named constants, because one other package reads exactly those three.
   * The seed tool's {@code book} kind takes the written-work classes this table already maps to
   * {@code WORK}, and it cites them rather than deciding them a second time; the note above about a
   * second copy of the list is the reason. Nothing else here is named, because nothing outside this
   * file refers to one row.
  ```

- [ ] **Step 4 — the control: the mapper's own tests, unchanged.**

  ```
  ./gradlew test --tests '*KindMapperTest' --tests 'com.robsartin.segue.wikidata.*'
  ```

  Expected: green, non-zero counts, and **no test file edited in this task**. `KindMapperTest`
  already asserts that the literal `Q7725634` maps to `WORK`; that it still does, with the
  production side reading a constant, is exactly the control this task has.

- [ ] **Step 5 — format, check the spans, gate, commit.**

  ```
  ./gradlew spotlessApply
  grep -n '{@code [^}]*$' src/main/java/com/robsartin/segue/wikidata/KindMapper.java
  SEGUE_REQUIRE_BROWSER=true SEGUE_REQUIRE_GRAPHVIZ=true ./gradlew check --rerun-tasks
  ```

  `git status`, stage by explicit path, commit:

  > Name the three written-work classes, so one table owns their ids (#333)

---

## Task 5 — `Expectations` gains `book`, and the union carries it

Files: `src/main/java/com/robsartin/segue/seed/Expectations.java`,
`src/test/java/com/robsartin/segue/seed/ExpectationsTest.java`.

**Read first.** `NameGroup.expectation()` calls `Expectations.forKinds(kinds())` — for a group of one
row as much as for a name listed twice — and `forKinds` builds a **fresh** `Expectation` from the
kinds it unions. The table's entry is therefore not what the resolver asks; the union of it is. A
`book` entry with no union rule behind it is a dead path, green in `forKind` and silent in
production. Both halves are driven out red-first here.

- [ ] **Step 1 — RED: the table has no `book`.** In `ExpectationsTest`, add
  `import com.robsartin.segue.wikidata.KindMapper;` in import order, and append:

  ```java
    @Test
    @DisplayName("a book is a work of a written class, and the other work kind still names none")
    void shouldExpectAWorkOfAWrittenClassWhenTheKindIsBook() {
      Expectation expectation = Expectations.forKind("book");

      assertThat(expectation.acceptsKind(NodeKind.WORK)).isTrue();
      assertThat(expectation.acceptsKind(NodeKind.PERSON))
          .as("an unrecognised kind constrains nothing, so this is also the test that it IS known")
          .isFalse();
      assertThat(expectation.checksClass()).isTrue();
      assertThat(expectation.classes())
          .as("the ids live in KindMapper and are cited, never restated")
          .containsExactlyInAnyOrder(
              KindMapper.BOOK, KindMapper.LITERARY_WORK, KindMapper.WRITTEN_WORK);
      assertThat(Expectations.forKind("tv-show").checksClass())
          .as("the other WORK kind is untouched")
          .isFalse();
    }
  ```

  The last assertion, and the file's nine existing cases, are the control that no other kind moved.

  ```
  ./gradlew test --tests '*ExpectationsTest'
  ```

  **Expected failure — a real assertion:**

  ```
  java.lang.AssertionError: [an unrecognised kind constrains nothing, so this is also the test that it IS known]
  Expecting value to be false but was true
  ```

- [ ] **Step 2 — GREEN: the set and the kind.** In `Expectations`, add
  `import com.robsartin.segue.wikidata.KindMapper;` in import order, and add the set beside the
  occupation sets, after `BROADCASTING`:

  ```java
    /**
     * The classes a work has to state for a {@code book} row to resolve to it.
     *
     * <p>Drawn from what {@code KindMapper} already maps to {@code WORK} and cited from it, so the
     * three ids have one home. Nothing is added to that table: a class it does not map is not a
     * {@code WORK}, so it could not pass the kind check either, and widening the mapper would
     * change every projection (ADR 42) — a different decision from this one.
     *
     * <p><b>"Version, edition or translation" is deliberately outside this set.</b> The mapper
     * makes it a {@code WORK}, and a title matches a printing as readily as it matches the work, so
     * a row that finds only an edition fails the class check and goes to review — where a person
     * can point it at the work — instead of resolving to a printing of the thing the owner meant.
     */
    private static final Set<String> WRITTEN =
        Set.of(KindMapper.BOOK, KindMapper.LITERARY_WORK, KindMapper.WRITTEN_WORK);
  ```

  and the kind, immediately after the `tv-show` line in the static block:

  ```java
      // The one kind that names classes. A book row is a WORK, and WORK alone is albums, films and
      // episodes too — the kind check cannot separate a book from the film of the book. Issue #333.
      put("book", EnumSet.of(NodeKind.WORK), Set.of(), WRITTEN);
  ```

  Re-run Step 1's command: green.

- [ ] **Step 3 — RED: the union the resolver actually asks for drops it.** Append to
  `ExpectationsTest`:

  ```java
    @Test
    @DisplayName("one row's kind carries its classes through the union the resolver asks for")
    void shouldCarryTheClassesThroughWhenTheOnlyRoleIsBook() {
      // NameGroup.expectation() calls forKinds for every group, including a group of one row, so
      // this — not forKind — is the path a book row is judged by.
      Expectation expectation = Expectations.forKinds(List.of("book"));

      assertThat(expectation.checksClass()).isTrue();
      assertThat(expectation.classes()).isEqualTo(Expectations.forKind("book").classes());
    }

    @Test
    @DisplayName("a name listed as both a book and an author checks no class")
    void shouldCheckNoClassWhenAnotherRoleConstrainsNone() {
      // The permissive rule the occupation union already follows, for the same reason: the author
      // half resolves to a PERSON, which states none of the written classes, so intersecting would
      // refuse the very row the union exists to serve.
      Expectation expectation = Expectations.forKinds(List.of("book", "author"));

      assertThat(expectation.acceptsKind(NodeKind.PERSON)).isTrue();
      assertThat(expectation.acceptsKind(NodeKind.WORK)).isTrue();
      assertThat(expectation.checksClass()).isFalse();
    }
  ```

  ```
  ./gradlew test --tests '*ExpectationsTest'
  ```

  **Expected: one real assertion failure**, in the first of the two:

  ```
  ExpectationsTest > shouldCarryTheClassesThroughWhenTheOnlyRoleIsBook FAILED
      java.lang.AssertionError:
      Expecting value to be true but was false
  ```

  **The second passes already, vacuously** — a union that carries no class checks no class — and it
  is a pin rather than a red. Step 7 is its planted control, and the task report must say both.

- [ ] **Step 4 — GREEN: the union.** Replace the body of `forKinds`:

  ```java
    public static Expectation forKinds(Collection<String> kinds) {
      Objects.requireNonNull(kinds, "kinds");
      Set<NodeKind> nodeKinds = EnumSet.noneOf(NodeKind.class);
      Set<String> occupations = new LinkedHashSet<>();
      Set<String> classes = new LinkedHashSet<>();
      boolean anyUnconstrained = false;
      boolean anyUnconstrainedClass = false;
      for (String kind : kinds) {
        Expectation expectation = forKind(kind);
        nodeKinds.addAll(expectation.kinds());
        if (expectation.checksOccupation()) {
          occupations.addAll(expectation.occupations());
        } else {
          anyUnconstrained = true;
        }
        if (expectation.checksClass()) {
          classes.addAll(expectation.classes());
        } else {
          anyUnconstrainedClass = true;
        }
      }
      return new Expectation(
          nodeKinds,
          anyUnconstrained ? Set.of() : occupations,
          anyUnconstrainedClass ? Set.of() : classes);
    }
  ```

  and append one paragraph to its javadoc, keeping every existing one:

  ```java
     * <p>The class set follows the same rule for the same reason. A name listed as both a book and
     * an author is one title and one person, and a person states none of the written classes — so a
     * role that names no class widens the union to all of them rather than narrowing it to none.
  ```

  Re-run Step 3's command: green.

- [ ] **Step 5 — the whole seed suite, and the resolver's own tests.**

  ```
  ./gradlew test --tests 'com.robsartin.segue.seed.*'
  ```

  Expected: green. `SeedResolverTest` and `SeedRunTest` go through `NameGroup.expectation()` and are
  the control that the union's new branch changed no existing answer.

- [ ] **Step 6 — PLANTED CONTROL: `forKinds` really is the production path.** Temporarily make the
  union drop the class set again — in `forKinds`, replace the third constructor argument with
  `Set.of()`:

  ```java
      return new Expectation(nodeKinds, anyUnconstrained ? Set.of() : occupations, Set.of());
  ```

  ```
  ./gradlew test --tests '*ExpectationsTest'
  ```

  **Expected:** `shouldCarryTheClassesThroughWhenTheOnlyRoleIsBook` fails and
  `shouldExpectAWorkOfAWrittenClassWhenTheKindIsBook` still passes — which is the point of the
  plant: the table can be right while the path the resolver takes is empty. Quote the real text,
  **restore the line**, re-run: green.

- [ ] **Step 7 — PLANTED CONTROL: the permissive union.** Temporarily remove the
  `anyUnconstrainedClass` branch, so the union always keeps whatever classes it gathered:

  ```java
      return new Expectation(nodeKinds, anyUnconstrained ? Set.of() : occupations, classes);
  ```

  ```
  ./gradlew test --tests '*ExpectationsTest'
  ```

  **Expected:** `shouldCheckNoClassWhenAnotherRoleConstrainsNone` fails —
  `Expecting value to be false but was true` — which is what makes that pin evidence rather than a
  sentence. Quote the real text, **restore the branch**, re-run: green.

- [ ] **Step 8 — format, check the spans, gate, commit.**

  ```
  ./gradlew spotlessApply
  grep -n '{@code [^}]*$' src/main/java/com/robsartin/segue/seed/Expectations.java
  SEGUE_REQUIRE_BROWSER=true SEGUE_REQUIRE_GRAPHVIZ=true ./gradlew check --rerun-tasks
  ```

  `git status` — confirm neither plant is in the diff — stage by explicit path, commit:

  > Add the book kind, and carry its classes through the union (#333)

---

## Task 6 — A row with no status reads, because a hand list carries none

Files: `src/test/java/com/robsartin/segue/seed/SeedFilesTest.java`.

**Read first, and read honestly.** `SeedFiles.readList` refuses a row with fewer than three fields
and reads the third field as whatever it is; a trailing empty field survives the parser's
`stripTrailing`. **So this already works, and this task adds a pin, not a behaviour.** It is here
because the reading list is the first list to be written by hand — every previous list came from a
tool that filled the status column — and because "it already works" is a claim, not a test. The
honest sequence is therefore: write the pin, watch it pass, then **plant the failure it exists to
catch and watch it fire**. The task report says all three in those words, and does not describe this
task as red-first.

- [ ] **Step 1 — the pin.** In `SeedFilesTest`, after `readsTheList`, add:

  ```java
    @Test
    @DisplayName("a row with an empty status reads, because a hand-written list carries none")
    void shouldReadTheRowWhenTheStatusFieldIsEmpty() throws IOException {
      // The reading list is written by hand, and a tour status is a fact about scheduling that a
      // hand list has nothing to say about. The column is carried through untouched, as SeedRow's
      // own note says, so "untouched" has to include empty.
      Path list =
          write(
              "reading.csv",
              """
              name,kind,status
              The Salt Almanac,book,
              Marguerite Vale,author,
              """);

      List<SeedRow> rows = SeedFiles.readList(list);

      assertThat(rows).hasSize(2);
      assertThat(rows.get(0).kind()).isEqualTo("book");
      assertThat(rows.get(0).status()).isEmpty();
      assertThat(rows.get(1).status()).isEmpty();
    }
  ```

- [ ] **Step 2 — run it, and expect green.**

  ```
  ./gradlew test --tests '*SeedFilesTest'
  ```

  Expected: green. **If it is red, stop and report** — that would mean the reader refuses a blank
  status today, which is a behaviour change this plan has not scoped and the spec assumes away.
  Record which it was.

- [ ] **Step 3 — PLANTED CONTROL: prove the pin can fail.** In
  `src/main/java/com/robsartin/segue/seed/SeedFiles.java`, temporarily widen the refusal in
  `readList`:

  ```java
        // PLANT — remove after this step
        if (fields.size() < 3 || fields.get(2).isBlank()) {
          throw new IllegalArgumentException(path + " has a row with fewer than three fields");
        }
  ```

  ```
  ./gradlew test --tests '*SeedFilesTest'
  ```

  **Expected:** `shouldReadTheRowWhenTheStatusFieldIsEmpty` fails with an
  `IllegalArgumentException` ending `has a row with fewer than three fields`, and
  `readsTheList` stays green — its rows all carry a status, which is what makes the plant specific.
  Quote the real text. **Remove the plant**, re-run: green.

- [ ] **Step 4 — format, gate, commit.**

  ```
  ./gradlew spotlessApply
  SEGUE_REQUIRE_BROWSER=true SEGUE_REQUIRE_GRAPHVIZ=true ./gradlew check --rerun-tasks
  ```

  `git status` — confirm `SeedFiles.java` is **not** in the diff — stage by explicit path, commit:

  > Pin that a hand-written row with no status reads (#333)

---

## Task 7 — ADR 40's dated amendment

Files: `docs/adr/0040-bulk-seeding-as-a-dev-tool.md`.

**Read first.** An ADR is immutable: nothing above is edited, the front matter is untouched, the
status keeps `Accepted`, and this is appended as the file's last section, after `## Consequences`.
ADR 40 carries no amendment yet, so this is the first. **No commit hash, no `.superpowers/` path, no
qid, and no figure from the owner's graph** — `AdrCitationsTest` reads a backticked run of 7–40 hex
characters as a commit citation, and the three classes are therefore written **in words**: book,
literary work, written work. The index needs no change: no ADR is added, and the number, title and
status on the row are all unchanged.

- [ ] **Step 1 — append the amendment.** Everything inside the four-backtick fence below goes into
  the file; the fence itself does not.

  ````markdown
  **Amendment (2026-09-15, issue #333): a `book` kind, and a third signal — the classes a work may
  state.**

  Nothing above is withdrawn, no decision above is edited, and this ADR keeps `Accepted`. What
  changes is that the `kind` column may now name a work rather than only a person or a group, and
  that one kind checks a third property to do it. `seed.Expectations` is the authority on the
  column's current values.

  **Why the kind alone is not enough for a title.** "Auto-accept only when three independent signals
  agree" reads, for a person, as name plus kind plus occupation. A book row has no occupation to
  check, and its kind is `WORK` — which [ADR 21](0021-six-kind-ontology.md)'s six make the same kind
  as an album, a film, a television episode and a song. A title is regularly shared by the book, the
  film of the book and a dozen printings of the book, and the film is regularly the better known of
  them. Kind plus margin would resolve such a row confidently and wrongly, which is the one outcome
  this tool exists to avoid.

  **The third signal, the same shape as the second.** An expectation is a set of node kinds, a set
  of occupations and now a set of Wikidata classes; a kind whose class set is empty is checked
  exactly as it was before, which is every kind but `book`. `book` expects a `WORK` that states one
  of three classes — book, literary work, or written work — and those three are named where
  `KindMapper` already maps them to `WORK`, so the ids have one home and the seed table cites it
  rather than restating it. The check sits inside the same filter as the kind check, ahead of the
  sitelink ranking, so a better-known candidate of the wrong class never reaches the margin. A
  candidate refused on it goes to the review file with the classes it stated on the line, exactly as
  a person refused on occupation goes there with the occupations they stated.

  **"Version, edition or translation" is deliberately outside the set.** It is a `WORK` to the
  mapper, and the owner's row means the work, not a printing of it. Leaving it out costs a review
  line whenever a title finds only an edition — where a person can point it at the work in one look
  — and including it would buy an auto-accepted answer that is quietly the wrong entity.

  **No class is added to `KindMapper`.** The set is drawn from what that table already maps to
  `WORK`. A class it does not map is not a `WORK` at all, so it could not pass the kind check
  either; and widening the mapper would change every projection, which is
  [ADR 42](0042-store-p31-and-rederive-kind-at-projection.md)'s territory and a different decision
  from this one.

  **`P31` read this way is a resolver filter, not an edge** — the rule this ADR already states for
  `P106`, and for the same reason. The graph stores an entity's classes on the node and re-derives
  its kind from them; reading those same classes to decide which of several same-titled works the
  row meant creates no edge and adds nothing to the graph.

  **The list itself.** Three columns still, `name,kind,status`, with `author` or `book` in the kind
  column. A hand-written list carries no tour status, so the status field is empty on every row; the
  column is carried through untouched, as it always has been, and the reader takes an empty field.
  The file lives outside the working tree with every other list
  ([ADR 33](0033-taste-layer-separation.md)), and a row becomes known only by being rated, through
  the rule [ADR 48](0048-a-high-rating-counts-as-something-you-have.md) already sets — this
  amendment adds no second route to membership.

  **Alternatives rejected.**

  - **A fourth column naming the author, and a tie-break on the author property.** Fewer review
    lines, at the price of a four-column list, another property read in the facts pass, and a second
    pass that cannot run until the authors themselves resolve. The review file already exists for
    the residue. This is the upgrade to reach for if the residue turns out large on a real list, and
    it is cheap to add later precisely because nothing here forecloses it.
  - **Authors only, with books picked out of their expansions.** Sidesteps title matching
    altogether, and hands the owner a picking step over lists of titles — which is the deck's job,
    and the deck deals people and groups.
  - **Accept any `WORK` for a `book` row.** The cheapest change there is, and it is the confident
    wrong answer above: whenever the film is better known than the book, the film wins the margin.
  - **Books known outright, without a rating.** The owner chose rate-first, and the promotion rule
    already turns a high rating into membership; a second membership rule would be a second answer
    to one question.
  - **A `book` class added to `KindMapper`.** There is nothing to add: the set is what that table
    already maps to `WORK`. Adding to it would change every projection for a resolver's benefit.

  **Nothing here is unit-testable on its own, and that is said out loud rather than left implied.**
  This entry records a decision whose code landed with its own tests: the class set and its two
  predicates seen red on a stub; the raw classes seen red against the stub server before they were
  kept; the film refused and the identical candidate with a written class accepted; the edition
  losing to the work it is an edition of; two written works within the margin sent to review; the
  table's entry and the union the resolver actually asks for driven out separately, because the
  first is green while the second is empty; and three planted controls — the review line's classes,
  the union's permissive rule, and a reader made to refuse a blank status — each seen to fire and
  then removed. The verification of the *document* is the full gate over an otherwise unchanged
  tree: `AdrIndexTest`, `AdrCitationsTest`, `DocumentationLinksTest` for the relative links above,
  and `javadoc -Werror` inside `./gradlew check`.
  ````

- [ ] **Step 2 — check the links and the shapes before the gate does.**

  ```
  grep -n '](00' docs/adr/0040-bulk-seeding-as-a-dev-tool.md
  ls docs/adr/0021-*.md docs/adr/0033-*.md docs/adr/0042-*.md docs/adr/0048-*.md
  grep -nE '`Q[0-9]+`' docs/adr/0040-bulk-seeding-as-a-dev-tool.md
  ```

  The first two must agree — every link target exists with exactly that filename, and each is
  relative to `docs/adr/` with no directory part. The third must print **nothing**: no qid enters a
  file under `docs/adr`. If `docs/adr/0033-*.md` is not the ADR that governs the personal list,
  check with `grep -l 'listen to, read and watch' docs/adr/*.md` and correct the citation rather
  than leaving a link that resolves to the wrong decision.

  ```
  ./gradlew test --tests '*AdrIndexTest' --tests '*AdrCitationsTest' --tests '*DocumentationLinksTest'
  ```

- [ ] **Step 3 — gate and commit.**

  ```
  SEGUE_REQUIRE_BROWSER=true SEGUE_REQUIRE_GRAPHVIZ=true ./gradlew check --rerun-tasks
  ```

  `git status`, stage by explicit path, commit:

  > Record the book kind and its class check in ADR 40 (#333)

---

## Task 8 — The bulk-seeding chapter names the kind

Files: `docs/developer-guide.md`.

**Read first.** The chapter never says what the `kind` column may hold — it says only "The list is
three columns — `name,kind,status`" — so this task **adds** a sentence rather than editing one. It
names `book` and cites `seed.Expectations` for the rest: copying nineteen kinds into the guide would
be a second copy of that table, and the one a later editor forgets. Nothing in
`DeveloperGuideEnumerationsTest` derives anything from `Expectations`, so no arch test changes;
**if one reds here, stop and report** rather than editing a rule.

- [ ] **Step 1 — the kind column.** In the `## Bulk seeding` chapter, in the paragraph beginning
  "The list is three columns", insert one sentence immediately after the first sentence:

  > The `kind` column says what the row is — a role a person plays (`author`), a sort of group
  > (`orchestra`), or, since #333, a work (`book`); `seed.Expectations` holds the whole list and is
  > the authority on it, and a value it has never seen constrains nothing rather than rejecting
  > everything.

  Change nothing else in that paragraph.

- [ ] **Step 2 — the third signal, in "How it decides".** In the paragraph beginning "Auto-accept
  needs three independent signals to agree", replace the parenthesis after "the kind" so the
  sentence reads:

  > Auto-accept needs three independent signals to agree: the name (label or alias, with a label
  > match outranking an alias match), the kind (`P31` for the `NodeKind`, `P106` for a person's
  > occupation, and for a `book` row the raw `P31` again — `WORK` is albums, films and episodes as
  > well as books, so that kind names the classes it will take and an edition or an adaptation is
  > refused on them), and a sitelink margin over the runner-up.

  Then extend the `P106` paragraph that follows by one sentence, keeping the existing two:

  > `P31` read this way is the same: the graph already stores an entity's classes and re-derives its
  > kind from them, and reading them again to choose between a book and the film of the book adds
  > nothing to the graph either.

- [ ] **Step 3 — the guide's own tests.**

  ```
  ./gradlew test --tests '*DeveloperGuide*' --tests '*DocumentationLinksTest'
  ```

  Expected: green, with a non-zero count. The chapter gained no link and no enumeration, so this is
  a check that it gained none by accident either.

- [ ] **Step 4 — gate and commit.**

  ```
  SEGUE_REQUIRE_BROWSER=true SEGUE_REQUIRE_GRAPHVIZ=true ./gradlew check --rerun-tasks
  ```

  `git status`, stage by explicit path, commit:

  > Name the book kind in the bulk-seeding chapter (#333)

---

## Task 9 — The final gate, counted once

Files: none.

- [ ] **Step 1 — confirm the document inputs are already declared. Verify, do not re-declare.**

  ```
  grep -n 'inputs.dir("docs")' build.gradle.kts
  grep -n 'inputs.file("README.md")' build.gradle.kts
  ```

  Both must print a line. `docs/` and `README.md` are inputs of `test`, which is why every task
  above that touched a document re-ran the suite rather than reporting `UP-TO-DATE`. **If either is
  missing, stop and report** — adding it is a different issue.

- [ ] **Step 2 — the whole gate, blocking, from a clean slate.**

  ```
  ./gradlew spotlessApply
  git status
  SEGUE_REQUIRE_BROWSER=true SEGUE_REQUIRE_GRAPHVIZ=true ./gradlew check --rerun-tasks
  ```

  `spotlessApply` must leave the tree unchanged — if it rewrites a file, a task above committed
  unformatted source; commit the format fix on its own and say so. The gate must be green.

- [ ] **Step 3 — count the tests ONCE, from the run you just made.** Do not re-run the suite to
  count it:

  ```
  grep -h '<testsuite ' build/test-results/test/*.xml | sed -E 's/.* tests="([0-9]+)".*/\1/' | paste -sd+ - | bc
  grep -h '<testsuite ' build/test-results/test/*.xml | sed -E 's/.* failures="([0-9]+)".*/\1/' | paste -sd+ - | bc
  grep -h '<testsuite ' build/test-results/test/*.xml | sed -E 's/.* errors="([0-9]+)".*/\1/' | paste -sd+ - | bc
  grep -h '<testsuite ' build/test-results/test/*.xml | sed -E 's/.* skipped="([0-9]+)".*/\1/' | paste -sd+ - | bc
  ```

  Report the four numbers. Failures and errors must be zero. The total must exceed the count
  recorded in Task 1 Step 4 by the cases this plan added — four in `ExpectationTest`, four in
  `AdjudicatorTest`, three in `ExpectationsTest`, one in `SeedFilesTest`, and none anywhere else —
  and if it does not, say so rather than rounding it off: a suite that grew by the wrong number
  means a test was overwritten or a filter matched nothing.

- [ ] **Step 4 — confirm the plants are gone and no invented id leaked into production.**

  ```
  grep -rn 'PLANT' src
  grep -rn 'Q09016' src/main
  git status
  ```

  The first two must print nothing. `git status` must be clean.

---

## Done when

- A `name,kind,status` list with `book` in the kind column and an empty status resolves through
  `resolveNames`: a work stating book, literary work or written work is accepted, and a film, an
  adaptation or an edition sharing the title goes to review with its classes on the line.
- The class set reaches the decision through `Expectations.forKinds`, the path
  `NameGroup.expectation()` actually takes — proved by a test that fails when only `forKind` is
  right.
- Every other kind in the table is unchanged, proved by the nine `ExpectationsTest` cases that were
  not edited.
- The three class ids have one home, in `KindMapper`, and `Expectations` cites them.
- No test or fixture in the tree names a real Wikidata class id that it did not already name, and
  `StandInQidsDenoteNothingTest.ALLOWED` gained no entry.
- Every red in this plan was a real assertion failure, quoted in its task report; every vacuous pass
  was named as one; every planted control was planted, seen to fire, and removed.
- ADR 40 carries a dated amendment and no qid; the bulk-seeding chapter names the kind.
- The full gate is green:
  `SEGUE_REQUIRE_BROWSER=true SEGUE_REQUIRE_GRAPHVIZ=true ./gradlew check --rerun-tasks`, counted
  once from `build/test-results/test/*.xml`.
