# Revise a Rating — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `./gradlew rate --args="--known … --revise 3"` deals only the entities currently rated 3, showing each card's existing rating, so a rating can be reconsidered.

**Architecture:** One new optional flag threaded through the existing seams. `Deck.deal` gains a revise selector; `Card` gains the current rating; the page renders it. `RateRun.buildDeck` already receives the full `Map<String, Integer> ratings`, so nothing new has to be read.

**Tech Stack:** Java 21 (release 21, toolchain 25), Gradle Kotlin DSL, JUnit 5 + AssertJ, ArchUnit, `jdk.httpserver`, plain HTML/CSS/JS.

## Global Constraints

- **Issue #109.** Branch `109-revise-a-rating`.
- **The card MUST show the existing rating in revise mode.** Re-rating blind is how a considered 2 becomes a reflexive 4, which is worse than offering no revision at all. This is the issue's one non-negotiable.
- **The default run is unchanged.** With no `--revise`, the deck still deals only unrated entities and resume behaviour is identical.
- **Ratings only — never a note.** `Card` has no note field and must not gain one (#85).
- **No rating value in any log line** (ADR 33). Counts and paths only.
- **No new third-party dependency.**
- Stage commits **by explicit path**. NEVER `git add -A` — an untracked `mad.vcf` must never be staged.
- `./gradlew check` green before every commit; run long commands **blocking**.
- **Never open `~/.segue/segue.db`.** Copy it and point `--db` at the copy; report the real file's mtime unchanged.
- TDD: failing test first, run it, watch it fail for the right reason, report what the failure said.

## Context: why this exists

A real session produced 973 ratings — 541 fives, 309 fours, **121 threes**, one 2, one 1. Measured: those 973 ratings moved **one entity** in the top 25 against no ratings at all, and the last 164 changed the ordering not at all. `Recommendations.regardFor` weights a 3 at exactly 1.0, identical to unrated, so **the 121 threes are arithmetic no-ops.** If half are honestly 2s, that is ~60 entities dropping to 1/3 — the largest movement available from data already collected. The deck cannot currently reach them, because `Deck.deal` excludes everything in `alreadyRated` and that exclusion is also the resume mechanism.

---

### Task 1: `Card` carries a current rating, `Deck` selects by one

**Files:**
- Modify: `src/main/java/com/robsartin/segue/rate/Card.java`
- Modify: `src/main/java/com/robsartin/segue/rate/Deck.java`
- Test: `src/test/java/com/robsartin/segue/rate/DeckTest.java`

**Interfaces:**
- Produces:
  - `Card` gains a component `OptionalInt currentRating`, empty for an unrated card.
  - `Card.rated(NodeRecord node, int degree, int currentRating) → Card`
  - `Deck.deal(List<String> knownQids, ToIntFunction<String> degreeByQid, Function<String, Optional<NodeRecord>> nodeByQid, Map<String, Integer> ratings, List<Explained> candidates, OptionalInt reviseRating) → List<Card>`
  - The fourth parameter changes from `Set<String> alreadyRated` to the full `Map<String, Integer> ratings`. `RateRun` already holds it.

- [ ] **Step 1: Write the failing tests**

Add to `DeckTest`. Use the existing fixture style and its synthetic `Q9000xx` qids.

```java
  @Test
  @DisplayName("revise mode deals only the entities at that rating, and nothing else")
  void reviseDealsOnlyThatRating() {
    List<Card> cards =
        Deck.deal(
            List.of("Q900001", "Q900002", "Q900003"),
            q -> DEGREES.getOrDefault(q, 0),
            q -> Optional.ofNullable(NODES.get(q)),
            Map.of("Q900001", 3, "Q900002", 5, "Q900003", 3),
            List.of(),
            OptionalInt.of(3));

    assertThat(cards).extracting(Card::qid).containsExactlyInAnyOrder("Q900001", "Q900003");
  }

  @Test
  @DisplayName("a revise card carries the rating it currently has, so it is not re-rated blind")
  void reviseCardShowsTheCurrentRating() {
    List<Card> cards =
        Deck.deal(
            List.of("Q900002"),
            q -> DEGREES.getOrDefault(q, 0),
            q -> Optional.ofNullable(NODES.get(q)),
            Map.of("Q900002", 5),
            List.of(),
            OptionalInt.of(5));

    assertThat(cards).hasSize(1);
    assertThat(cards.get(0).currentRating()).hasValue(5);
  }

  @Test
  @DisplayName("revise mode deals no candidates, because a candidate has no rating to revise")
  void reviseDealsNoCandidates() {
    List<Card> cards =
        Deck.deal(
            List.of("Q900001"),
            q -> DEGREES.getOrDefault(q, 0),
            q -> Optional.ofNullable(NODES.get(q)),
            Map.of("Q900001", 3),
            List.of(candidateFor("Q900101", "Candidate One")),
            OptionalInt.of(3));

    assertThat(cards).extracting(Card::qid).containsExactly("Q900001");
  }

  @Test
  @DisplayName("without revise, the deck still deals only unrated entities and no card shows a rating")
  void defaultModeIsUnchanged() {
    List<Card> cards =
        Deck.deal(
            List.of("Q900001", "Q900002"),
            q -> DEGREES.getOrDefault(q, 0),
            q -> Optional.ofNullable(NODES.get(q)),
            Map.of("Q900002", 4),
            List.of(),
            OptionalInt.empty());

    assertThat(cards).extracting(Card::qid).containsExactly("Q900001");
    assertThat(cards.get(0).currentRating()).isEmpty();
  }
```

Every existing `Deck.deal` call in the test file must be updated to the new signature — pass a `Map` where it passed a `Set`, and `OptionalInt.empty()` as the last argument. Do that mechanically; do not change what any existing test asserts.

- [ ] **Step 2: Run and watch them fail**

Run: `./gradlew test --tests 'com.robsartin.segue.rate.DeckTest'`
Expected: FAIL to compile — `deal` has no six-argument form and `Card` has no `currentRating`. Record the actual message.

- [ ] **Step 3: Add the `Card` component and factory**

`Card` gains `OptionalInt currentRating` as its last component, with a `Objects.requireNonNull` in the compact constructor. `Card.known` and `Card.candidate` pass `OptionalInt.empty()`. Add:

```java
  /**
   * An entity already rated, dealt for reconsideration (issue #109).
   *
   * <p><b>It carries the rating it already has, and that is the point of the card.</b> A revision
   * pass that hid the current value would invite a considered 2 to become a reflexive 4 — worse
   * than not offering revision at all, because it would look like new information.
   */
  public static Card rated(NodeRecord node, int degree, int currentRating) {
    Objects.requireNonNull(node, "node");
    return new Card(
        node.qid(),
        node.label(),
        node.kind(),
        ClassLabels.describe(node.instanceOf()),
        OptionalInt.of(degree),
        List.of(),
        OptionalInt.of(currentRating));
  }
```

- [ ] **Step 4: Add the selector to `Deck`**

Change the fourth parameter to `Map<String, Integer> ratings` and add `OptionalInt reviseRating` last. When `reviseRating` is present, select known qids whose rating equals it, build them with `Card.rated`, sort by the same degree-descending/qid comparator, and return them with **no candidates**. When absent, behave exactly as now, deriving `alreadyRated` as `ratings.keySet()`.

Document the asymmetry on the method:

```java
   * <p><b>Revise mode deals no candidates, and that is not an omission.</b> A candidate is by
   * definition something the owner does not have and has not rated, so there is nothing there to
   * reconsider; mixing discovery into a revision pass would also change what the pass measures.
```

- [ ] **Step 5: Run the tests**

Run: `./gradlew test --tests 'com.robsartin.segue.rate.DeckTest'`
Expected: PASS, all tests including the four new ones.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/robsartin/segue/rate/Card.java \
        src/main/java/com/robsartin/segue/rate/Deck.java \
        src/test/java/com/robsartin/segue/rate/DeckTest.java
git commit -m "Deal a rated card for reconsideration, carrying the rating it has (#109)"
```

---

### Task 2: `--revise` reaches the deck

**Files:**
- Modify: `src/main/java/com/robsartin/segue/rate/RateCli.java`
- Modify: `src/main/java/com/robsartin/segue/rate/RateRun.java`
- Modify: `build.gradle.kts` (the `rate` task description)
- Test: `src/test/java/com/robsartin/segue/rate/RateCliTest.java`, `src/test/java/com/robsartin/segue/rate/RateRunTest.java`

**Interfaces:**
- Consumes: `Deck.deal`'s six-argument form and `Card.rated` from Task 1.
- Produces: `RateCli.Options` gains `OptionalInt revise`; `RateRun.buildDeck(GraphStore, List<String> known, Map<String, Integer> ratings, int candidateCount, OptionalInt reviseRating, Consumer<String> notes)`.

- [ ] **Step 1: Write the failing tests**

In `RateCliTest`, following the file's existing style:

```java
  @Test
  @DisplayName("--revise is parsed and off by default")
  void parsesRevise() {
    assertThat(RateCli.parse(new String[] {"--known", "k.csv"}, null, "/home/x").revise()).isEmpty();
    assertThat(RateCli.parse(new String[] {"--known", "k.csv", "--revise", "3"}, null, "/home/x")
            .revise())
        .hasValue(3);
  }

  @Test
  @DisplayName("a --revise outside the 1-5 scale is refused, naming the scale")
  void refusesAReviseOffTheScale() {
    assertThatThrownBy(
            () -> RateCli.parse(new String[] {"--known", "k.csv", "--revise", "9"}, null, "/home/x"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("1 to 5");
  }
```

In `RateRunTest`:

```java
  @Test
  @DisplayName("revise mode deals the rated entities and says so, without naming a rating")
  void buildsAReviseDeck() throws Exception {
    try (TinkerGraphStore graph = new TinkerGraphStore()) {
      graph.upsertNode(new NodeRecord("Q900001", NodeKind.GROUP, "One", List.of()));
      graph.upsertNode(new NodeRecord("Q900002", NodeKind.GROUP, "Two", List.of()));
      List<String> notes = new ArrayList<>();

      List<Card> deck =
          RateRun.buildDeck(
              graph,
              List.of("Q900001", "Q900002"),
              Map.of("Q900001", 3, "Q900002", 5),
              0,
              OptionalInt.of(3),
              notes::add);

      assertThat(deck).extracting(Card::qid).containsExactly("Q900001");
      assertThat(deck.get(0).currentRating()).hasValue(3);
      assertThat(notes).noneMatch(n -> n.contains("Q900001") || n.contains("Q900002"));
    }
  }
```

- [ ] **Step 2: Run and watch them fail**

Run: `./gradlew test --tests 'com.robsartin.segue.rate.RateCliTest' --tests 'com.robsartin.segue.rate.RateRunTest'`
Expected: FAIL to compile. Record the message.

- [ ] **Step 3: Implement**

`Options` gains `OptionalInt revise`; `parse` handles `case "--revise" ->`, validating against `AffinityRecord.MIN_RATING`/`MAX_RATING` and refusing anything outside with a message naming the scale. Extend the usage string. `RateRun.buildDeck` takes the extra argument and passes it to `Deck.deal`; when it is present, skip the candidate sweep entirely and emit a note saying how many cards are up for reconsideration — **a count, never a rating value or a qid** (ADR 33).

`RateCli.main` passes `options.revise()` through.

- [ ] **Step 4: Update the Gradle task description**

In `build.gradle.kts`, extend the `rate` task's `description` to mention `--revise`, in the existing voice. Keep every existing comment in the block.

- [ ] **Step 5: Run and commit**

Run: `./gradlew check`
Expected: PASS.

```bash
git add src/main/java/com/robsartin/segue/rate/RateCli.java \
        src/main/java/com/robsartin/segue/rate/RateRun.java \
        src/test/java/com/robsartin/segue/rate/RateCliTest.java \
        src/test/java/com/robsartin/segue/rate/RateRunTest.java \
        build.gradle.kts
git commit -m "Thread --revise from the command line to the deck (#109)"
```

---

### Task 3: The page shows the rating, and the decision is recorded

**Files:**
- Modify: `src/main/java/com/robsartin/segue/rate/RateServer.java` (the card JSON)
- Modify: `src/main/resources/rate/deck.html`
- Modify: `src/test/java/com/robsartin/segue/rate/RateServerTest.java`, `src/test/java/com/robsartin/segue/rate/DeckPageTest.java`
- Modify: `docs/adr/0046-the-rating-deck.md`, `docs/developer-guide.md`

**Interfaces:**
- Consumes: `Card.currentRating()` from Task 1.
- Produces: the card JSON gains `"currentRating": N | null`.

- [ ] **Step 1: Write the failing tests**

In `RateServerTest`, a card built with `Card.rated(...)` must serialise its current rating; one built with `Card.known(...)` must serialise `null`. Assert on the JSON body.

In `DeckPageTest`, assert the page reads `currentRating` and renders it — and make the assertion behavioural rather than token-presence where you can. **Read the existing assertions first**: issue #103 records that this file's checks are token-presence and pass against a defective page, so do not add a fourth of the same kind without saying so in your report.

- [ ] **Step 2: Run and watch them fail.** Record the messages.

- [ ] **Step 3: Implement**

`RateServer.json` emits `currentRating` beside `degree`, using the same present/`null` treatment `degree` already gets. In `deck.html`, when `currentRating` is non-null, show it — clearly enough that the owner cannot mistake a revision card for a fresh one, and using `textContent`/DOM construction rather than `innerHTML` (the XSS fix from #101 must not be undone).

- [ ] **Step 4: Verify it live, against a COPY**

```bash
mkdir -p /tmp/revise-smoke && cp ~/.segue/segue.db /tmp/revise-smoke/copy.db
cd ~/code/segue && ./gradlew rate --args="--db /tmp/revise-smoke/copy.db --known $HOME/setlist-scout/filtered-qids.csv --revise 3"
```

Open `http://127.0.0.1:8090` and confirm with browser tools: the first card shows its current rating of 3, pressing `2` records and advances, and `listRatings --db /tmp/revise-smoke/copy.db` shows the change. Then confirm `~/.segue/segue.db`'s mtime is unchanged and report it.

- [ ] **Step 5: Amend ADR 46 and the guide**

ADR 46's consequences say the deck is "everything unrated, recomputed at startup" without stating that this made revision impossible. Add a dated amendment (2026-08-28, issue #109) recording: what the measurement showed (973 ratings moved one entity; 121 threes weight exactly 1.0, identical to unrated), that revision is now possible via `--revise`, that the card shows the current rating and why that is non-negotiable, and that revise mode deals no candidates. **Do not edit the original text.** Add the flag to the guide's `rate` section.

**One warning, from this ADR's own history:** #101 produced six false generalisations in a row from prose written off remembered code. Any sentence you write about a group — rules, ADRs, tools — must be verified against every member by opening the file, or rewritten so it does not span a set.

- [ ] **Step 6: Run and commit**

Run: `./gradlew check`

```bash
git add src/main/java/com/robsartin/segue/rate/RateServer.java \
        src/main/resources/rate/deck.html \
        src/test/java/com/robsartin/segue/rate/RateServerTest.java \
        src/test/java/com/robsartin/segue/rate/DeckPageTest.java \
        docs/adr/0046-the-rating-deck.md docs/developer-guide.md
git commit -m "Show the rating a revision card already has (#109)"
```

---

## Self-Review

**Spec coverage.** #109's acceptance list maps to tasks: revision mode → 1 and 2; card shows current rating → 1 and 3; default unchanged → 1 (`defaultModeIsUnchanged`); `Deck` tests including no-leak into default → 1; ADR 46 amended → 3.

**Type consistency.** `Card.rated(NodeRecord, int, int)` is used with that signature in Tasks 1 and 3. `Deck.deal`'s six-argument form matches its call in `RateRun.buildDeck`, whose own new signature matches its calls in `RateCli` and `RateRunTest`.

**Verified against source, not inferred.** `RateCli.Options` is `(Path database, Path known, int port)` at `RateCli.java:47` with `parse` at `:56`; `Deck.deal`'s current five-parameter form is at `Deck.java:43`; `RateRun.buildDeck` already takes `Map<String, Integer> ratings` at `RateRun.java:53-58`, which is why no new read is needed. `AffinityRecord.MIN_RATING`/`MAX_RATING` exist and are public.

**Known weakness carried forward.** Task 3's page assertions inherit #103's token-presence problem. That issue is filed and unlabeled; this plan does not fix it, and Task 3 says so rather than pretending otherwise.
