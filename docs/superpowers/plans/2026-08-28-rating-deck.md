# Rating Deck Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A sixth dev-side tool, `./gradlew rate`, that serves a local page dealing one entity at a time and records a 1-5 rating per keystroke, so the empty `affinity` table can be filled without typing.

**Architecture:** A new package `com.robsartin.segue.rate`, following `recommend` exactly: a CLI that parses arguments, a Run that replays the assertion log into a `TinkerGraphStore`, a pure `Deck` that orders cards, and a `RateServer` on `jdk.httpserver` bound to loopback. Candidates and their routes come from `recommend`'s existing public `CandidateSweep` and `Routes`, so a card's routes are the routes `find_paths` would return.

**Tech Stack:** Java 21 (release 21, toolchain 25), Gradle Kotlin DSL, JUnit 5 + AssertJ, ArchUnit, `jdk.httpserver` (JDK built-in, no new dependency), SQLite via the existing `SqliteAffinityStore` / `SqliteAssertionLog`.

## Global Constraints

- **Spec:** `docs/superpowers/specs/2026-08-28-rating-deck-design.md`. Issue #101. Branch `101-rating-deck`.
- **No new third-party dependency.** The HTTP server is `com.sun.net.httpserver` from the `jdk.httpserver` module.
- **Ratings only — never a note.** Write `new AffinityRecord(qid, rating, null, Instant.now())`. `AffinityRecord`'s compact constructor does not null-check `note`, so this is legal and is the boundary from issue #85 held by construction.
- **No rating value in any log line** (ADR 33). Log counts and paths only, as `RatingsRun` does. Notes go to a `Consumer<String>`, never to a logger owned by the class.
- **Bind loopback only:** `InetAddress.getLoopbackAddress()`, never `0.0.0.0` (ADR 28).
- **`domain` stays free of third-party dependencies.** `Deck` lives in `rate`, not `domain`, because it takes graph and candidate types.
- **Stage commits by explicit path.** Never `git add -A` — other agents share this repository. There is an untracked `mad.vcf` in the working tree that must never be staged.
- **Gate before every push:** `./gradlew check` (spotless, ArchUnit, tests, jacoco) must be green.
- **Never open `~/.segue/segue.db`.** Copy it to a scratch directory for any manual verification, and confirm the original's mtime is unchanged.
- **Never invent a Wikidata QID.** Any QID in a test fixture must either be invented-looking and clearly synthetic (`Q900001`), or looked up and confirmed by label *and* description.

---

## File Structure

**Created:**

| File | Responsibility |
|---|---|
| `src/main/java/com/robsartin/segue/support/ClassLabels.java` | moved from `export`; offline class-QID → English label table |
| `src/main/java/com/robsartin/segue/rate/Card.java` | one dealt card: qid, label, kind, classes, and either a degree or routes |
| `src/main/java/com/robsartin/segue/rate/Deck.java` | pure ordering: known + degrees + ratings + candidates → `List<Card>` |
| `src/main/java/com/robsartin/segue/rate/RateServer.java` | `jdk.httpserver`, three routes, Origin/Host allowlist |
| `src/main/java/com/robsartin/segue/rate/RateRun.java` | replay, build deck, start server, block |
| `src/main/java/com/robsartin/segue/rate/RateCli.java` | argument parsing and `main` |
| `src/main/resources/rate/deck.html` | the page — markup, style and script in one file, no external assets |

**Modified:**

| File | Change |
|---|---|
| `src/main/java/com/robsartin/segue/export/DotWriter.java` | import `ClassLabels` from its new package |
| `build.gradle.kts` | register the `rate` JavaExec task |
| `src/test/java/com/robsartin/segue/arch/ArchitectureTest.java` | three new fences |
| `docs/adr/0046-the-rating-deck.md` | new ADR |
| `docs/developer-guide.md` | a section on the sixth tool |

---

### Task 1: Move `ClassLabels` to `support`

The deck needs class names on every card. `ClassLabels.label` and `ClassLabels.describe` are package-private in `export`, and `rate` must not depend on a sibling dev tool — the `ratings` fence bans exactly that, because a dependency on a sibling lets a tool inherit the sibling's looser rules. `support` is where shared offline helpers already live (`QidList`, `UuidV7`).

This is a pure move plus a visibility widening. No behaviour changes.

**Files:**
- Create: `src/main/java/com/robsartin/segue/support/ClassLabels.java`
- Delete: `src/main/java/com/robsartin/segue/export/ClassLabels.java`
- Modify: `src/main/java/com/robsartin/segue/export/DotWriter.java` (import)
- Move: `src/test/java/com/robsartin/segue/export/ClassLabelsTest.java` → `src/test/java/com/robsartin/segue/support/ClassLabelsTest.java`

**Interfaces:**
- Produces: `com.robsartin.segue.support.ClassLabels.label(String classQid) → String` and `ClassLabels.describe(List<String> classQids) → String`, both now `public static`. `describe` returns `ClassLabels.NO_CLASS` ("no stated class") for an empty list.

- [ ] **Step 1: Move the class and widen visibility**

```bash
cd ~/code/segue
git mv src/main/java/com/robsartin/segue/export/ClassLabels.java \
       src/main/java/com/robsartin/segue/support/ClassLabels.java
git mv src/test/java/com/robsartin/segue/export/ClassLabelsTest.java \
       src/test/java/com/robsartin/segue/support/ClassLabelsTest.java
```

Then edit both files: change `package com.robsartin.segue.export;` to `package com.robsartin.segue.support;`, and change the three package-private members to public:

```java
public static final String NO_CLASS = "no stated class";

public static String label(String classQid) { ... }

public static String describe(List<String> classQids) { ... }
```

Add to the class javadoc:

```java
 * <p><b>It lives in {@code support} rather than in {@code export} because two tools read it.</b>
 * The exporter puts a class name in a DOT tooltip; the rating deck puts it on a card. A dev tool
 * that depended on {@code export} to reach this table would inherit that package's looser fence,
 * which is the reason the ratings tool bans the dependency outright.
```

- [ ] **Step 2: Fix the one consumer**

In `src/main/java/com/robsartin/segue/export/DotWriter.java`, add the import:

```java
import com.robsartin.segue.support.ClassLabels;
```

- [ ] **Step 3: Run the gate to verify nothing else referenced it**

Run: `./gradlew check`
Expected: PASS. If compilation fails, the error names the remaining reference — add the same import there.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/robsartin/segue/support/ClassLabels.java \
        src/main/java/com/robsartin/segue/export/DotWriter.java \
        src/test/java/com/robsartin/segue/support/ClassLabelsTest.java
git commit -m "Move ClassLabels to support, where two tools can reach it (#101)"
```

---

### Task 2: `Card` and `Deck`

The only component with a decision in it, and therefore the only one worth testing hard. Pure: no HTTP, no database, no graph traversal beyond the degree function handed to it.

**Files:**
- Create: `src/main/java/com/robsartin/segue/rate/Card.java`
- Create: `src/main/java/com/robsartin/segue/rate/Deck.java`
- Test: `src/test/java/com/robsartin/segue/rate/DeckTest.java`

**Interfaces:**
- Consumes: `ClassLabels.describe` from Task 1; `NodeRecord(String qid, NodeKind kind, String label, List<String> instanceOf)`; `Explained(Recommendation candidate, List<PathResult> routes)`; `Recommendation.entity() → NodeRecord`.
- Produces:
  - `Card.known(NodeRecord node, int degree) → Card`
  - `Card.candidate(NodeRecord node, List<String> routeLines) → Card`
  - accessors `qid()`, `label()`, `kind()`, `classes()`, `degree()` (`OptionalInt`), `routes()` (`List<String>`)
  - `Deck.deal(List<String> knownQids, ToIntFunction<String> degreeByQid, Function<String, Optional<NodeRecord>> nodeByQid, Set<String> alreadyRated, List<Explained> candidates) → List<Card>`
  - `Deck.CANDIDATE_EVERY = 5`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/robsartin/segue/rate/DeckTest.java`:

```java
package com.robsartin.segue.rate;

import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.domain.NodeRecord;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DeckTest {

  private static final Map<String, NodeRecord> NODES =
      Map.of(
          "Q900001", new NodeRecord("Q900001", NodeKind.GROUP, "Low Degree", List.of("Q900901")),
          "Q900002", new NodeRecord("Q900002", NodeKind.GROUP, "High Degree", List.of("Q900901")),
          "Q900003", new NodeRecord("Q900003", NodeKind.PERSON, "Mid Degree", List.of("Q900902")),
          "Q900004", new NodeRecord("Q900004", NodeKind.WORK, "Already Rated", List.of()));

  private static final Map<String, Integer> DEGREES =
      Map.of("Q900001", 3, "Q900002", 90, "Q900003", 20, "Q900004", 50);

  private static List<Card> deal(List<String> known, Set<String> rated, List<Explained> cands) {
    return Deck.deal(
        known, q -> DEGREES.getOrDefault(q, 0), q -> Optional.ofNullable(NODES.get(q)), rated,
        cands);
  }

  @Test
  @DisplayName("known entities are dealt by in-graph degree, highest first")
  void ordersKnownByDegreeDescending() {
    List<Card> cards = deal(List.of("Q900001", "Q900002", "Q900003"), Set.of(), List.of());

    assertThat(cards).extracting(Card::qid).containsExactly("Q900002", "Q900003", "Q900001");
    assertThat(cards.get(0).degree()).hasValue(90);
  }

  @Test
  @DisplayName("an entity that is already rated is never dealt")
  void excludesAlreadyRated() {
    List<Card> cards =
        deal(List.of("Q900001", "Q900004", "Q900002"), Set.of("Q900004"), List.of());

    assertThat(cards).extracting(Card::qid).doesNotContain("Q900004");
    assertThat(cards).hasSize(2);
  }

  @Test
  @DisplayName("an entity on the list but absent from the graph is skipped, not dealt blank")
  void skipsEntitiesMissingFromTheGraph() {
    List<Card> cards = deal(List.of("Q900002", "Q900999"), Set.of(), List.of());

    assertThat(cards).extracting(Card::qid).containsExactly("Q900002");
  }

  @Test
  @DisplayName("a known card carries a degree and no routes; the reverse for a candidate")
  void knownAndCandidateCardsDifferInShape() {
    Card known = Card.known(NODES.get("Q900002"), 90);
    Card candidate = Card.candidate(NODES.get("Q900003"), List.of("a -[X]-> b"));

    assertThat(known.degree()).hasValue(90);
    assertThat(known.routes()).isEmpty();
    assertThat(candidate.degree()).isEmpty();
    assertThat(candidate.routes()).containsExactly("a -[X]-> b");
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests 'com.robsartin.segue.rate.DeckTest'`
Expected: FAIL to compile — `Deck`, `Card` and `Explained` cannot be resolved in package `rate`.

- [ ] **Step 3: Write `Card`**

Create `src/main/java/com/robsartin/segue/rate/Card.java`:

```java
package com.robsartin.segue.rate;

import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.domain.NodeRecord;
import com.robsartin.segue.support.ClassLabels;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;

/**
 * One dealt entity, in the two shapes the page renders.
 *
 * <p><b>Two shapes, because "why is this here" only has an answer for one of them.</b> A known
 * entity is on the owner's list already, so the useful thing to show is how much the graph hangs
 * off it — which is also the number the deck sorted by, so the card explains its own position. A
 * candidate is something the owner may never have heard of, so the useful thing is the routes
 * tying it to what they know.
 *
 * <p><b>There is no note field here and there never should be.</b> Issue #85 made a rating
 * ordinary data and a note protected; the deck writes ratings, and a type with nowhere to put a
 * note cannot leak one.
 */
public record Card(
    String qid, String label, NodeKind kind, String classes, OptionalInt degree,
    List<String> routes) {

  public Card {
    Objects.requireNonNull(qid, "qid");
    Objects.requireNonNull(label, "label");
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(classes, "classes");
    Objects.requireNonNull(degree, "degree");
    routes = List.copyOf(Objects.requireNonNull(routes, "routes"));
  }

  /** An entity already on the owner's list, showing the degree the deck ordered it by. */
  public static Card known(NodeRecord node, int degree) {
    Objects.requireNonNull(node, "node");
    return new Card(
        node.qid(),
        node.label(),
        node.kind(),
        ClassLabels.describe(node.instanceOf()),
        OptionalInt.of(degree),
        List.of());
  }

  /** Something the owner does not have, shown with the routes that reached it. */
  public static Card candidate(NodeRecord node, List<String> routeLines) {
    Objects.requireNonNull(node, "node");
    return new Card(
        node.qid(),
        node.label(),
        node.kind(),
        ClassLabels.describe(node.instanceOf()),
        OptionalInt.empty(),
        routeLines);
  }
}
```

- [ ] **Step 4: Write `Deck`**

Create `src/main/java/com/robsartin/segue/rate/Deck.java`:

```java
package com.robsartin.segue.rate;

import com.robsartin.segue.domain.NodeRecord;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.ToIntFunction;

/**
 * What to ask about, and in what order. Pure, so the one decision in this tool can be tested
 * without a database, a server or a browser.
 *
 * <p><b>Degree descending, because a rating is worth what it moves.</b> A known entity's rating
 * reaches candidate scores through the intermediates it touches, so rating the busiest entities
 * first buys the most movement per keystroke — the owner should be able to feel the recommender
 * change inside one session rather than after eight hundred cards. The card shows that same degree,
 * so a card near the top says why it is near the top.
 *
 * <p><b>Already-rated entities are excluded rather than re-asked, and that is the whole of the
 * resume mechanism.</b> The deck is "everything unrated", recomputed at startup from {@code
 * AffinityStore.readRatings()}. There is no position file to persist, to corrupt, or to leave
 * personal data lying in.
 */
public final class Deck {

  /**
   * One candidate every this many cards.
   *
   * <p>The mixed stream is what the owner asked for: rating doubles as discovery. Five keeps the
   * deck mostly on the entities whose ratings actually move a score today — a candidate's rating
   * is recorded but inert, because {@code Recommendations.regardFor} reads only known-list qids.
   */
  public static final int CANDIDATE_EVERY = 5;

  private Deck() {}

  public static List<Card> deal(
      List<String> knownQids,
      ToIntFunction<String> degreeByQid,
      Function<String, Optional<NodeRecord>> nodeByQid,
      Set<String> alreadyRated,
      List<Explained> candidates) {
    Objects.requireNonNull(knownQids, "knownQids");
    Objects.requireNonNull(degreeByQid, "degreeByQid");
    Objects.requireNonNull(nodeByQid, "nodeByQid");
    Objects.requireNonNull(alreadyRated, "alreadyRated");
    Objects.requireNonNull(candidates, "candidates");

    List<Card> known = new ArrayList<>();
    for (String qid : knownQids) {
      if (alreadyRated.contains(qid)) {
        continue;
      }
      // An entity on the list that the graph does not hold has nothing to show and nothing to
      // explain. Skipping is right; dealing a blank card would ask the owner to rate a name.
      nodeByQid.apply(qid).ifPresent(node -> known.add(Card.known(node, degreeByQid.applyAsInt(qid))));
    }
    known.sort(Comparator.comparingInt((Card c) -> c.degree().orElse(0)).reversed()
        .thenComparing(Card::qid));

    List<Card> fresh = new ArrayList<>();
    for (Explained explained : candidates) {
      String qid = explained.candidate().entity().qid();
      if (alreadyRated.contains(qid)) {
        continue;
      }
      fresh.add(Card.candidate(explained.candidate().entity(), routeLines(explained)));
    }

    return interleave(known, fresh);
  }

  private static List<String> routeLines(Explained explained) {
    return explained.routes().stream().map(Object::toString).toList();
  }

  private static List<Card> interleave(List<Card> known, List<Card> candidates) {
    List<Card> dealt = new ArrayList<>(known.size() + candidates.size());
    int nextCandidate = 0;
    for (Card card : known) {
      dealt.add(card);
      if (dealt.size() % CANDIDATE_EVERY == 0 && nextCandidate < candidates.size()) {
        dealt.add(candidates.get(nextCandidate++));
      }
    }
    // Whatever is left over goes on the end rather than being dropped: a short known list must not
    // silently discard candidates the sweep paid to find.
    dealt.addAll(candidates.subList(nextCandidate, candidates.size()));
    return List.copyOf(dealt);
  }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew test --tests 'com.robsartin.segue.rate.DeckTest'`
Expected: PASS, 4 tests.

- [ ] **Step 6: Add the interleave test now that the shape exists**

Append to `DeckTest`:

```java
  @Test
  @DisplayName("a candidate is dealt after every fifth known card, and leftovers are not dropped")
  void interleavesCandidatesEveryFifthCard() {
    List<String> known = List.of("Q900001", "Q900002", "Q900003", "Q900005", "Q900006");
    Map<String, NodeRecord> extra =
        Map.of(
            "Q900005", new NodeRecord("Q900005", NodeKind.GROUP, "Five", List.of()),
            "Q900006", new NodeRecord("Q900006", NodeKind.GROUP, "Six", List.of()));
    Explained one = candidateFor("Q900101", "Candidate One");
    Explained two = candidateFor("Q900102", "Candidate Two");

    List<Card> cards =
        Deck.deal(
            known,
            q -> DEGREES.getOrDefault(q, 1),
            q -> Optional.ofNullable(NODES.containsKey(q) ? NODES.get(q) : extra.get(q)),
            Set.of(),
            List.of(one, two));

    assertThat(cards.get(4).qid()).isEqualTo("Q900101");
    assertThat(cards).extracting(Card::qid).contains("Q900102");
    assertThat(cards).hasSize(7);
  }
```

Add the helper, which builds a `Recommendation` with no shared intermediates and no routes — the deck only reads the entity and the route list:

```java
  private static Explained candidateFor(String qid, String label) {
    NodeRecord node = new NodeRecord(qid, NodeKind.GROUP, label, List.of());
    return new Explained(new Recommendation(node, 1.0, 12, List.of()), List.of());
  }
```

Add imports `com.robsartin.segue.domain.Recommendation` and `com.robsartin.segue.recommend.Explained`.

- [ ] **Step 7: Run and commit**

Run: `./gradlew test --tests 'com.robsartin.segue.rate.DeckTest'`
Expected: PASS, 5 tests.

```bash
git add src/main/java/com/robsartin/segue/rate/Card.java \
        src/main/java/com/robsartin/segue/rate/Deck.java \
        src/test/java/com/robsartin/segue/rate/DeckTest.java
git commit -m "Deal the deck by degree, so a rating buys the most movement (#101)"
```

---

### Task 3: `RateServer`

**Files:**
- Create: `src/main/java/com/robsartin/segue/rate/RateServer.java`
- Test: `src/test/java/com/robsartin/segue/rate/RateServerTest.java`

**Interfaces:**
- Consumes: `Card` and `Deck` from Task 2; `AffinityStore.put(AffinityRecord)`.
- Produces:
  - `new RateServer(List<Card> deck, AffinityStore affinity, int port)`
  - `start() → void`, `port() → int` (the actual bound port, so a test can pass 0), `stop() → void`
  - Routes: `GET /` → the page; `GET /api/card?i=N` → card JSON or 404 past the end; `POST /api/rate` with `{"qid":"Q1","rating":4}` → 204.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/robsartin/segue/rate/RateServerTest.java`:

```java
package com.robsartin.segue.rate;

import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.segue.domain.AffinityRecord;
import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.domain.NodeRecord;
import com.robsartin.segue.port.AffinityStore;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RateServerTest {

  /** Records what it was given; asserts by inspection, so no mocking framework is needed. */
  private static final class RecordingAffinity implements AffinityStore {
    private final List<AffinityRecord> written = new ArrayList<>();

    @Override public void put(AffinityRecord affinity) { written.add(affinity); }
    @Override public Optional<AffinityRecord> find(String qid) { return Optional.empty(); }
    @Override public List<AffinityRecord> readAll() { return List.of(); }
    @Override public Map<String, Integer> readRatings() { return Map.of(); }
    @Override public void close() {}
  }

  private RecordingAffinity affinity;
  private RateServer server;
  private HttpClient client;

  @BeforeEach
  void start() throws Exception {
    affinity = new RecordingAffinity();
    Card card = Card.known(new NodeRecord("Q900001", NodeKind.GROUP, "Test Band", List.of()), 42);
    server = new RateServer(List.of(card), affinity, 0);
    server.start();
    client = HttpClient.newHttpClient();
  }

  @AfterEach
  void stop() {
    server.stop();
  }

  private HttpRequest.Builder request(String path) {
    return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path));
  }

  @Test
  @DisplayName("a card comes back with its label, classes and degree")
  void servesACard() throws Exception {
    HttpResponse<String> response =
        client.send(request("/api/card?i=0").build(), HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).contains("Test Band").contains("42");
  }

  @Test
  @DisplayName("past the end of the deck is a 404, not an empty card")
  void refusesAnIndexPastTheEnd() throws Exception {
    HttpResponse<String> response =
        client.send(request("/api/card?i=99").build(), HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(404);
  }

  @Test
  @DisplayName("a rating is written to the affinity store, with no note")
  void writesARating() throws Exception {
    HttpResponse<String> response =
        client.send(
            request("/api/rate")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"qid\":\"Q900001\",\"rating\":2}"))
                .build(),
            HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(204);
    assertThat(affinity.written).hasSize(1);
    assertThat(affinity.written.get(0).qid()).isEqualTo("Q900001");
    assertThat(affinity.written.get(0).rating()).isEqualTo(2);
    assertThat(affinity.written.get(0).note()).isNull();
  }

  @Test
  @DisplayName("a request carrying a foreign Origin is refused and writes nothing")
  void refusesAForeignOrigin() throws Exception {
    HttpResponse<String> response =
        client.send(
            request("/api/rate")
                .header("Origin", "https://evil.example.com")
                .POST(HttpRequest.BodyPublishers.ofString("{\"qid\":\"Q900001\",\"rating\":5}"))
                .build(),
            HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(403);
    assertThat(affinity.written).isEmpty();
  }

  @Test
  @DisplayName("a rating outside 1-5 is refused rather than stored")
  void refusesARatingOffTheScale() throws Exception {
    HttpResponse<String> response =
        client.send(
            request("/api/rate")
                .POST(HttpRequest.BodyPublishers.ofString("{\"qid\":\"Q900001\",\"rating\":9}"))
                .build(),
            HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(affinity.written).isEmpty();
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests 'com.robsartin.segue.rate.RateServerTest'`
Expected: FAIL to compile — `RateServer` cannot be resolved.

- [ ] **Step 3: Write `RateServer`**

Create `src/main/java/com/robsartin/segue/rate/RateServer.java`:

```java
package com.robsartin.segue.rate;

import com.robsartin.segue.domain.AffinityRecord;
import com.robsartin.segue.port.AffinityStore;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The deck, over loopback HTTP.
 *
 * <p><b>Loopback and an Origin allowlist, which is ADR 28's argument used a second time.</b>
 * Binding to {@code 127.0.0.1} stops another machine reaching this; it does not stop a hostile page
 * open in the owner's own browser from posting here, which is what DNS rebinding exploits. This
 * endpoint writes the one table in segue that cannot be regenerated, so both halves are needed.
 *
 * <p><b>No rating reaches a log line</b> (ADR 33). The logs here carry the bound port and counts.
 */
public final class RateServer {

  private static final Logger log = LoggerFactory.getLogger(RateServer.class);

  /** What a browser is allowed to say it came from. Null and absent are fine — curl sends none. */
  private static final Set<String> ALLOWED_ORIGINS =
      Set.of("http://127.0.0.1", "http://localhost", "null");

  private final List<Card> deck;
  private final AffinityStore affinity;
  private final int requestedPort;
  private HttpServer server;

  public RateServer(List<Card> deck, AffinityStore affinity, int requestedPort) {
    this.deck = List.copyOf(Objects.requireNonNull(deck, "deck"));
    this.affinity = Objects.requireNonNull(affinity, "affinity");
    this.requestedPort = requestedPort;
  }

  public void start() throws IOException {
    server = HttpServer.create(
        new InetSocketAddress(InetAddress.getLoopbackAddress(), requestedPort), 0);
    server.createContext("/api/card", this::card);
    server.createContext("/api/rate", this::rate);
    server.createContext("/", this::page);
    server.start();
    log.info("rating deck on http://127.0.0.1:{} with {} card(s)", port(), deck.size());
  }

  public int port() {
    return server.getAddress().getPort();
  }

  public void stop() {
    if (server != null) {
      server.stop(0);
    }
  }

  private void page(HttpExchange exchange) throws IOException {
    try (InputStream in = RateServer.class.getResourceAsStream("/rate/deck.html")) {
      if (in == null) {
        throw new IllegalStateException("deck.html is missing from the jar");
      }
      send(exchange, 200, "text/html; charset=utf-8", in.readAllBytes());
    }
  }

  private void card(HttpExchange exchange) throws IOException {
    int index = indexFrom(exchange.getRequestURI().getQuery());
    if (index < 0 || index >= deck.size()) {
      send(exchange, 404, "application/json", "{}".getBytes(StandardCharsets.UTF_8));
      return;
    }
    send(exchange, 200, "application/json", json(deck.get(index), index).getBytes(StandardCharsets.UTF_8));
  }

  private void rate(HttpExchange exchange) throws IOException {
    if (!originAllowed(exchange)) {
      send(exchange, 403, "application/json", "{}".getBytes(StandardCharsets.UTF_8));
      return;
    }
    String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    String qid = field(body, "qid");
    int rating;
    try {
      rating = Integer.parseInt(field(body, "rating"));
      // Let AffinityRecord do the range check: one definition of the scale, in the type that
      // carries it. Its message names no value, which is deliberate (ADR 33).
      affinity.put(new AffinityRecord(qid, rating, null, Instant.now()));
    } catch (IllegalArgumentException e) {
      send(exchange, 400, "application/json", "{}".getBytes(StandardCharsets.UTF_8));
      return;
    }
    send(exchange, 204, "application/json", new byte[0]);
  }

  private boolean originAllowed(HttpExchange exchange) {
    List<String> origins = exchange.getRequestHeaders().get("Origin");
    if (origins == null || origins.isEmpty()) {
      return true;
    }
    String origin = origins.get(0);
    return ALLOWED_ORIGINS.stream().anyMatch(allowed -> origin.startsWith(allowed));
  }

  private static int indexFrom(String query) {
    if (query == null || !query.startsWith("i=")) {
      return -1;
    }
    try {
      return Integer.parseInt(query.substring(2));
    } catch (NumberFormatException e) {
      return -1;
    }
  }

  /** Minimal, because the payload is four fields and a dependency for that would be silly. */
  private static String json(Card card, int index) {
    StringBuilder routes = new StringBuilder("[");
    for (int i = 0; i < card.routes().size(); i++) {
      routes.append(i == 0 ? "" : ",").append('"').append(escape(card.routes().get(i))).append('"');
    }
    routes.append(']');
    return "{\"index\":" + index
        + ",\"qid\":\"" + escape(card.qid()) + "\""
        + ",\"label\":\"" + escape(card.label()) + "\""
        + ",\"kind\":\"" + card.kind() + "\""
        + ",\"classes\":\"" + escape(card.classes()) + "\""
        + ",\"degree\":" + (card.degree().isPresent() ? card.degree().getAsInt() : "null")
        + ",\"routes\":" + routes + "}";
  }

  private static String escape(String raw) {
    return raw.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ");
  }

  private static String field(String body, String name) {
    int at = body.indexOf('"' + name + '"');
    if (at < 0) {
      throw new IllegalArgumentException("missing field");
    }
    int colon = body.indexOf(':', at);
    String rest = body.substring(colon + 1).trim();
    if (rest.startsWith("\"")) {
      return rest.substring(1, rest.indexOf('"', 1));
    }
    int end = 0;
    while (end < rest.length() && (Character.isDigit(rest.charAt(end)) || rest.charAt(end) == '-')) {
      end++;
    }
    if (end == 0) {
      throw new IllegalArgumentException("unparseable field");
    }
    return rest.substring(0, end);
  }

  private static void send(HttpExchange exchange, int status, String type, byte[] body)
      throws IOException {
    exchange.getResponseHeaders().set("Content-Type", type);
    exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
    if (body.length > 0) {
      try (var out = exchange.getResponseBody()) {
        out.write(body);
      }
    }
  }
}
```

- [ ] **Step 4: Add a placeholder page so `GET /` resolves**

Create `src/main/resources/rate/deck.html` with a single line for now; Task 5 replaces it:

```html
<!doctype html><title>segue rating deck</title><p>placeholder</p>
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew test --tests 'com.robsartin.segue.rate.RateServerTest'`
Expected: PASS, 5 tests.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/robsartin/segue/rate/RateServer.java \
        src/main/resources/rate/deck.html \
        src/test/java/com/robsartin/segue/rate/RateServerTest.java
git commit -m "Serve the deck on loopback, and refuse a foreign Origin (#101)"
```

---

### Task 4: `RateCli`, `RateRun`, and the Gradle task

**Files:**
- Create: `src/main/java/com/robsartin/segue/rate/RateRun.java`
- Create: `src/main/java/com/robsartin/segue/rate/RateCli.java`
- Modify: `build.gradle.kts` (register `rate` after the `recommend` task block)
- Test: `src/test/java/com/robsartin/segue/rate/RateRunTest.java`

**Interfaces:**
- Consumes: `Deck.deal` (Task 2), `RateServer` (Task 3), `CandidateSweep`, `Routes`, `Recommendations`, `RecognitionInstitutions::isRecognitionInstitution`, `QidList.read`, `GraphProjector.project`.
- Produces: `RateRun.buildDeck(GraphStore graph, List<String> known, Set<String> alreadyRated, int candidateCount, Consumer<String> notes) → List<Card>`; `RateCli.main(String[])`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/robsartin/segue/rate/RateRunTest.java`:

```java
package com.robsartin.segue.rate;

import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.domain.NodeRecord;
import com.robsartin.segue.tinker.TinkerGraphStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RateRunTest {

  @Test
  @DisplayName("the deck is built from the graph, and the notes carry counts and no rating")
  void buildsADeckAndSaysWhatItDid() throws Exception {
    try (TinkerGraphStore graph = new TinkerGraphStore()) {
      graph.upsertNode(new NodeRecord("Q900001", NodeKind.GROUP, "One", List.of()));
      graph.upsertNode(new NodeRecord("Q900002", NodeKind.GROUP, "Two", List.of()));
      List<String> notes = new ArrayList<>();

      List<Card> deck =
          RateRun.buildDeck(graph, List.of("Q900001", "Q900002"), Set.of("Q900002"), 0, notes::add);

      assertThat(deck).extracting(Card::qid).containsExactly("Q900001");
      assertThat(notes).anyMatch(n -> n.contains("1 card(s)"));
      assertThat(notes).noneMatch(n -> n.matches(".*rating [1-5].*"));
    }
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests 'com.robsartin.segue.rate.RateRunTest'`
Expected: FAIL to compile — `RateRun` cannot be resolved.

- [ ] **Step 3: Write `RateRun`**

Create `src/main/java/com/robsartin/segue/rate/RateRun.java`:

```java
package com.robsartin.segue.rate;

import com.robsartin.segue.domain.Recommendation;
import com.robsartin.segue.domain.Recommendations;
import com.robsartin.segue.domain.Scorer;
import com.robsartin.segue.port.GraphStore;
import com.robsartin.segue.recommend.CandidateSweep;
import com.robsartin.segue.recommend.Explained;
import com.robsartin.segue.recommend.Routes;
import com.robsartin.segue.recommend.Sweep;
import com.robsartin.segue.wikidata.RecognitionInstitutions;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Replay, sweep, deal. The orchestration, and nothing that decides anything.
 *
 * <p><b>Candidates come from the recommender's own sweep, not a second implementation.</b> A card's
 * routes are then the routes {@code find_paths} would return for the same pair, which is the
 * property that makes "why is this here" answerable at all.
 *
 * <p>Notes go to a {@link Consumer} rather than to a logger of this class's own, as {@code
 * RatingsRun} does, so a test can assert on their order and content — and so this class has no
 * logger through which a rating could reach a log line (ADR 33).
 */
public final class RateRun {

  /** As many routes as fit on a card without turning it into a page to read. */
  private static final int ROUTES_PER_CARD = 3;

  /** The recommender's own floor: below this a normalised score divides by too little. */
  private static final int MIN_CANDIDATE_DEGREE = 12;

  private RateRun() {}

  public static List<Card> buildDeck(
      GraphStore graph,
      List<String> known,
      Set<String> alreadyRated,
      int candidateCount,
      Consumer<String> notes) {
    Objects.requireNonNull(graph, "graph");
    Objects.requireNonNull(known, "known");
    Objects.requireNonNull(alreadyRated, "alreadyRated");
    Objects.requireNonNull(notes, "notes");

    notes.accept(known.size() + " entity(ies) on your list, " + alreadyRated.size() + " already rated");

    List<Explained> candidates = new ArrayList<>();
    if (candidateCount > 0) {
      Sweep sweep =
          new CandidateSweep(graph, RecognitionInstitutions::isRecognitionInstitution)
              .over(known, Scorer.LIFT, MIN_CANDIDATE_DEGREE, Recommendations.EQUAL_REGARD);
      Routes routes = new Routes(graph, RecognitionInstitutions::isRecognitionInstitution);
      for (Recommendation candidate : Recommendations.rank(sweep.candidates(), candidateCount)) {
        candidates.add(new Explained(candidate, routes.bestFor(candidate, ROUTES_PER_CARD)));
      }
      notes.accept(candidates.size() + " candidate(s) mixed in");
    }

    List<Card> deck =
        Deck.deal(
            known,
            qid -> graph.edges(qid).size(),
            graph::node,
            alreadyRated,
            candidates);
    notes.accept(deck.size() + " card(s) to rate");
    return deck;
  }
}
```

**Note on `Scorer.LIFT`:** confirm the constant's exact name with `grep -n 'LIFT\|enum Scorer' src/main/java/com/robsartin/segue/domain/Scorer.java` before writing this line, and use whatever it actually is.

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew test --tests 'com.robsartin.segue.rate.RateRunTest'`
Expected: PASS.

- [ ] **Step 5: Write `RateCli`**

Create `src/main/java/com/robsartin/segue/rate/RateCli.java`, following `RecommendCli`'s shape exactly — including its refusal to create a database that is not there:

```java
package com.robsartin.segue.rate;

import com.robsartin.segue.ingest.GraphProjector;
import com.robsartin.segue.sqlite.SqliteAffinityStore;
import com.robsartin.segue.sqlite.SqliteAssertionLog;
import com.robsartin.segue.support.QidList;
import com.robsartin.segue.tinker.TinkerGraphStore;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The sixth dev-side tool: {@code ./gradlew rate --args="--known …"}. See ADR 46. */
public final class RateCli {

  private static final Logger log = LoggerFactory.getLogger(RateCli.class);

  /** Not 8080: the MCP server may be running, and nothing addressed to one should reach the other. */
  public static final int DEFAULT_PORT = 8090;

  /** Enough to keep the stream mixed without spending the whole sweep on one sitting. */
  private static final int DEFAULT_CANDIDATES = 200;

  private RateCli() {}

  public static void main(String[] args) throws IOException {
    Path database = Path.of(System.getenv().getOrDefault(
        "SEGUE_DB", System.getProperty("user.home") + "/.segue/segue.db"));
    Path known = null;
    int port = DEFAULT_PORT;

    for (int i = 0; i < args.length - 1; i += 2) {
      String value = args[i + 1];
      switch (args[i]) {
        case "--known" -> known = Path.of(value);
        case "--db" -> database = Path.of(value);
        case "--port" -> port = Integer.parseInt(value);
        default -> throw new IllegalArgumentException("unknown flag: " + args[i]);
      }
    }
    if (known == null) {
      throw new IllegalArgumentException(
          "--known is required: the deck is a statement about entities you have");
    }
    if (!Files.exists(database)) {
      throw new IllegalArgumentException("no graph at " + database + " — nothing to rate");
    }

    try (SqliteAssertionLog assertions = new SqliteAssertionLog(database);
        SqliteAffinityStore affinity = new SqliteAffinityStore(database);
        TinkerGraphStore graph = new TinkerGraphStore()) {
      long applied = GraphProjector.project(assertions, graph);
      log.info("replayed {} assertion(s) from {}", applied, database);

      // A count, never a qid and never a score (ADR 33).
      Map<String, Integer> rated = affinity.readRatings();
      log.info("{} entity(ies) already rated", rated.size());

      List<Card> deck =
          RateRun.buildDeck(
              graph, QidList.read(known), rated.keySet(), DEFAULT_CANDIDATES, RateCli::note);

      RateServer server = new RateServer(deck, affinity, port);
      server.start();
      log.info("open http://127.0.0.1:{} — press ctrl-c to stop", server.port());
      Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
      Thread.currentThread().join();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (IOException e) {
      throw new UncheckedIOException("could not serve the deck", e);
    }
  }

  private static void note(String message) {
    log.info("{}", message);
  }
}
```

- [ ] **Step 6: Register the Gradle task**

In `build.gradle.kts`, immediately after the `recommend` task block:

```kotlin
tasks.register<JavaExec>("rate") {
    group = "application"
    description =
        "Serves a local page that deals your entities one at a time and records a 1-5 rating " +
            "per keystroke, filling the affinity table the recommender weights by. Loopback " +
            "only. Writes the taste layer and nothing else. See ADR 46. Example: ./gradlew rate " +
            "--args=\"--known \$HOME/setlist-scout/filtered-qids.csv\""
    mainClass.set("com.robsartin.segue.rate.RateCli")
    classpath = sourceSets["main"].runtimeClasspath
    // sqlite-jdbc loads a native library, the same grant tasks.test makes.
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    // The whole graph is replayed into memory, and a real one is six figures of assertions.
    maxHeapSize = "4g"
    // A long-running server: Gradle must not hold the console.
    standardInput = System.`in`
    // Never up-to-date: the ratings change under it, and the point is to add to them now.
    outputs.upToDateWhen { false }
}
```

- [ ] **Step 7: Run the full gate and commit**

Run: `./gradlew check`
Expected: PASS.

```bash
git add src/main/java/com/robsartin/segue/rate/RateRun.java \
        src/main/java/com/robsartin/segue/rate/RateCli.java \
        src/test/java/com/robsartin/segue/rate/RateRunTest.java \
        build.gradle.kts
git commit -m "Wire the deck to the graph and the affinity table (#101)"
```

---

### Task 5: The page

**Files:**
- Modify: `src/main/resources/rate/deck.html` (replace the placeholder)
- Test: `src/test/java/com/robsartin/segue/rate/DeckPageTest.java`

**Interfaces:**
- Consumes: `GET /api/card?i=N` and `POST /api/rate` from Task 3.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/robsartin/segue/rate/DeckPageTest.java`:

```java
package com.robsartin.segue.rate;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DeckPageTest {

  private static String page() throws Exception {
    try (InputStream in = DeckPageTest.class.getResourceAsStream("/rate/deck.html")) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  @Test
  @DisplayName("the page reaches no external host, so it works offline and cannot phone anywhere")
  void hasNoExternalAssets() throws Exception {
    assertThat(page()).doesNotContain("http://").doesNotContain("https://").doesNotContain("//cdn");
  }

  @Test
  @DisplayName("the five ratings are real buttons, not clickable divs")
  void ratingsAreSemanticButtons() throws Exception {
    String html = page();
    for (int rating = 1; rating <= 5; rating++) {
      assertThat(html).contains("data-rating=\"" + rating + "\"");
    }
    assertThat(html).contains("<button");
  }

  @Test
  @DisplayName("the card region announces itself to a screen reader")
  void announcesEachCard() throws Exception {
    assertThat(page()).contains("aria-live");
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests 'com.robsartin.segue.rate.DeckPageTest'`
Expected: FAIL — the placeholder has no buttons and no `aria-live`.

- [ ] **Step 3: Write the page**

Replace `src/main/resources/rate/deck.html`:

```html
<!doctype html>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>segue rating deck</title>
<style>
  :root { color-scheme: light dark; --ink: #111; --paper: #fff; --muted: #555; --line: #ccc; }
  @media (prefers-color-scheme: dark) {
    :root { --ink: #eee; --paper: #16181c; --muted: #aaa; --line: #444; }
  }
  body { margin: 0; padding: 2rem 1rem; background: var(--paper); color: var(--ink);
         font: 16px/1.5 system-ui, sans-serif; display: flex; justify-content: center; }
  main { max-width: 34rem; width: 100%; }
  h1 { font-size: 2rem; margin: 0 0 .25rem; }
  .kind { color: var(--muted); font-size: .9rem; text-transform: lowercase; }
  .why { margin: 1.25rem 0; padding-left: 1rem; border-left: 3px solid var(--line);
         color: var(--muted); font-size: .95rem; }
  .why li { margin: .35rem 0; }
  .rate { display: flex; gap: .5rem; margin: 2rem 0 1rem; }
  button { flex: 1; padding: 1rem 0; font-size: 1.25rem; cursor: pointer;
           color: var(--ink); background: var(--paper); border: 2px solid var(--line);
           border-radius: .4rem; }
  button:hover { border-color: var(--ink); }
  button:focus-visible { outline: 3px solid var(--ink); outline-offset: 2px; }
  .keys, .progress { color: var(--muted); font-size: .85rem; }
  .done { font-size: 1.25rem; }
</style>
<main>
  <div id="card" aria-live="polite">
    <p class="progress">loading…</p>
  </div>
  <div class="rate">
    <button data-rating="1" aria-label="1, strongly not for me">1</button>
    <button data-rating="2" aria-label="2">2</button>
    <button data-rating="3" aria-label="3">3</button>
    <button data-rating="4" aria-label="4">4</button>
    <button data-rating="5" aria-label="5, a favourite">5</button>
  </div>
  <p class="keys">
    Keys <b>1</b>–<b>5</b> rate and advance · <b>s</b> or space skips · <b>b</b> goes back.
    Going back re-rates; a rating cannot be withdrawn, only changed.
  </p>
  <p class="progress" id="progress"></p>
</main>
<script>
  let index = 0;
  let rated = 0;

  async function show() {
    const response = await fetch(`/api/card?i=${index}`);
    const card = document.getElementById('card');
    if (response.status === 404) {
      card.innerHTML = '<p class="done">That is the whole deck. Nothing left to rate.</p>';
      return;
    }
    const c = await response.json();
    const why = c.degree !== null
      ? `<p class="why">connects ${c.degree} things in the graph</p>`
      : `<ul class="why">${c.routes.map(r => `<li>${r}</li>`).join('')}</ul>`;
    card.innerHTML =
      `<h1>${c.label}</h1><p class="kind">${c.kind} · ${c.classes}</p>${why}`;
    document.getElementById('progress').textContent =
      `card ${index + 1} · ${rated} rated this session`;
  }

  async function rate(value) {
    const response = await fetch(`/api/card?i=${index}`);
    if (response.status === 404) return;
    const c = await response.json();
    await fetch('/api/rate', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ qid: c.qid, rating: value }),
    });
    rated++;
    index++;
    show();
  }

  document.querySelectorAll('button[data-rating]').forEach(b =>
    b.addEventListener('click', () => rate(Number(b.dataset.rating))));

  document.addEventListener('keydown', event => {
    if (event.key >= '1' && event.key <= '5') { rate(Number(event.key)); }
    else if (event.key === 's' || event.key === ' ') { index++; show(); event.preventDefault(); }
    else if (event.key === 'b' && index > 0) { index--; show(); }
  });

  show();
</script>
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests 'com.robsartin.segue.rate.DeckPageTest'`
Expected: PASS, 3 tests.

- [ ] **Step 5: Look at it — against a COPY of the database, never the real one**

**The owner's real ratings are not a scratch pad.** `affinity` is the one table in segue that cannot be regenerated, and a manual smoke test writes to it. Copy first, and point `--db` at the copy:

```bash
mkdir -p /tmp/rate-smoke && cp ~/.segue/segue.db /tmp/rate-smoke/copy.db
cd ~/code/segue && ./gradlew rate --args="--db /tmp/rate-smoke/copy.db --known $HOME/setlist-scout/filtered-qids.csv"
```

Open `http://127.0.0.1:8090`. Check by eye: the first card is a high-degree entity, its degree matches what the card claims, pressing `3` advances, pressing `b` returns to it. Then stop with ctrl-c and confirm the rating landed in the copy:

```bash
cd ~/code/segue && ./gradlew listRatings --args="--db /tmp/rate-smoke/copy.db --out /tmp/rate-smoke/rated.txt" && head -5 /tmp/rate-smoke/rated.txt
```

Finally, confirm the real database was untouched — its mtime must be unchanged:

```bash
stat -f '%m %Sm %N' ~/.segue/segue.db
```

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/rate/deck.html \
        src/test/java/com/robsartin/segue/rate/DeckPageTest.java
git commit -m "The card, the five buttons, and the keys that drive them (#101)"
```

---

### Task 6: The three fences

Written after the package exists, because a fence over an empty package passes by having nothing to judge. Each must be **verified to bite**: introduce the violation, watch the rule fail, revert.

**Files:**
- Modify: `src/test/java/com/robsartin/segue/arch/ArchitectureTest.java`

**Interfaces:**
- Consumes: the `..rate..` package from Tasks 2-5.

- [ ] **Step 1: Write the three rules**

Add to `ArchitectureTest`, following `theRatingsToolOnlyReads`' shape (read it first — it uses the file's existing `APPLIES_A_CLAIM` and `callTo` helpers):

```java
  /**
   * Issue #101: the deck writes the taste layer and nothing else.
   *
   * <p>The mirror image of {@code theRatingsToolOnlyReads}. That tool may read every rating and
   * write none; this one may write a rating and must not touch the graph or the log. Between them
   * the two dev tools that meet the affinity table can each do exactly one thing to it.
   */
  @ArchTest
  static final ArchRule theRatingDeckWritesOnlyAffinity =
      noClasses()
          .that()
          .resideInAPackage("..rate..")
          .should(ArchConditions.callMethodWhere(APPLIES_A_CLAIM))
          .because(
              "ADR 46: the deck records what the owner thinks and never what the world says — it"
                  + " appends no claim, records no edge and upserts no node");

  /**
   * Issue #85, held by construction and then by rule.
   *
   * <p>{@code Card} has no note field, so there is nothing for the page to render even by
   * accident; this stops the field being reintroduced by someone who thinks it would be handy.
   */
  @ArchTest
  static final ArchRule theRatingDeckNeverReadsANote =
      noClasses()
          .that()
          .resideInAPackage("..rate..")
          .should(ArchConditions.callMethodWhere(callTo("note", AffinityRecord.class)))
          .because(
              "issue #85: a rating is ordinary data and a note is not — the deck writes the first"
                  + " and must never be able to display the second");

  /**
   * ADR 33: no rating reaches a log line.
   *
   * <p>Narrower than it looks. The deck logs a port, a count and a path; a qid paired with a score
   * is the personal part, and the easiest way to leak it is a debug line added while chasing
   * something else.
   */
  @ArchTest
  static final ArchRule theRatingDeckLogsNoRating =
      noClasses()
          .that()
          .resideInAPackage("..rate..")
          .should()
          .dependOnClassesThat()
          .haveFullyQualifiedName("com.robsartin.segue.domain.AffinityRecord")
          .orShould()
          .callMethodWhere(callTo("rating", AffinityRecord.class))
          .because(
              "ADR 33 keeps affinity out of every log line, and RateServer is the one class that"
                  + " may hold a rating long enough to write it");
```

**Important:** the third rule as written above will fail, because `RateServer` legitimately constructs an `AffinityRecord`. That is deliberate — narrow it in Step 3 to exclude `RateServer` by name, so the exception is stated rather than assumed.

- [ ] **Step 2: Run and watch the third rule fail**

Run: `./gradlew test --tests '*ArchitectureTest'`
Expected: FAIL, naming `RateServer` as depending on `AffinityRecord`.

- [ ] **Step 3: Narrow the third rule to the one class that may**

```java
  @ArchTest
  static final ArchRule theRatingDeckLogsNoRating =
      noClasses()
          .that()
          .resideInAPackage("..rate..")
          .and()
          .haveSimpleNameNotEndingWith("RateServer")
          .should()
          .dependOnClassesThat()
          .haveFullyQualifiedName("com.robsartin.segue.domain.AffinityRecord")
          .because(
              "ADR 33 keeps affinity out of every log line. RateServer is the single exception,"
                  + " because it must build the record it writes; nothing else in the deck may"
                  + " hold a rating at all, and RateServer owns no logger that prints one");
```

- [ ] **Step 4: Verify each rule bites**

For each of the three in turn: introduce the violation, run `./gradlew test --tests '*ArchitectureTest'`, confirm it fails naming your line, then revert.

- `theRatingDeckWritesOnlyAffinity` — add `graph.upsertNode(...)` to `RateRun.buildDeck`
- `theRatingDeckNeverReadsANote` — add `affinity.find(qid).map(AffinityRecord::note)` to `RateServer.rate`
- `theRatingDeckLogsNoRating` — add an `AffinityRecord` field to `Deck`

- [ ] **Step 5: Run the full gate and commit**

Run: `./gradlew check`
Expected: PASS.

```bash
git add src/test/java/com/robsartin/segue/arch/ArchitectureTest.java
git commit -m "Fence the deck: writes affinity, reads no note, logs no rating (#101)"
```

---

### Task 7: ADR 46 and the developer guide

**Files:**
- Create: `docs/adr/0046-the-rating-deck.md`
- Modify: `docs/developer-guide.md`

- [ ] **Step 1: Confirm the next ADR number**

```bash
ls docs/adr/ | tail -3
```

Use the next unused number; the filename below assumes 46.

- [ ] **Step 2: Write the ADR**

Follow the house structure of a recent ADR — read `docs/adr/0045-*.md` first for the section order and voice. It must record:

- **Decision:** a sixth dev-side tool, not a controller in the Spring app and not a seventh MCP tool.
- **Why not the Spring app:** the server already exists on `127.0.0.1:8080` and the machinery is there, but that would put a taste-layer *writer* on the MCP server's unauthenticated port, and ADR 32 confines Spring to `app` and `mcp`.
- **Why not a seventh MCP tool:** ADR 26 pins the surface at six; ADR 39 and ADR 43 reserve bulk taste-layer work to the owner's machine. Four ADRs have now declined to widen that surface.
- **Port 8090, not 8080:** so the deck and the MCP server can run at once and nothing addressed to one arrives at the other.
- **The Origin allowlist as ADR 28's argument used a second time:** loopback binding does not stop a hostile page in the owner's own browser, and this endpoint writes the one table that cannot be regenerated.
- **Degree ordering, with the arithmetic:** `regardFor` centres on the middle of the scale, so a 1 against a 5 is a 5× spread and a 4 against a 5 is 1.25×. The low ratings are the point; skip exists as a separate non-recording action so an unrecognised name does not become a low rating.
- **No un-rate:** `AffinityStore` has no delete and the deck does not add one. Back re-rates, which is a second `put`. State the consequence plainly — a first rating can be changed but never withdrawn.
- **Consequences, including the accepted gap:** a candidate's rating is recorded but changes no score, because `regardFor` reads only known-list qids. Closing it reopens ADR 40 and ADR 43 and is its own issue.

- [ ] **Step 3: Add the developer-guide section**

Add after the `recommend` section, in the guide's existing voice. Cover: what `./gradlew rate` does, the two card shapes and why they differ, the keys, that there is no session file because the deck is "everything unrated", and that ratings are the only thing it writes. Cite `Deck` and `RateServer` as the authority for the rules rather than restating them — the guide has been bitten by mirrored detail before (issue #46, issue #94).

- [ ] **Step 4: Run the gate and commit**

Run: `./gradlew check`
Expected: PASS.

```bash
git add docs/adr/0046-the-rating-deck.md docs/developer-guide.md
git commit -m "Record the sixth tool, its port, and why the low ratings are the point (#101)"
```

- [ ] **Step 5: Push and open the PR**

```bash
git push -u origin 101-rating-deck
gh pr create --fill --base main
```

Leave it open and non-draft once CI is green. Do not merge.

---

## Self-Review

**Spec coverage.** Every section of the spec maps to a task: placement → Task 4 and 7; `RateCli` → 4; `RateRun` → 4; `Deck` → 2; `RateServer` → 3; the card's two shapes → 2 and 5; the gesture → 5; fences → 6; testing → spread across 2, 3, 5, 6; ADR → 7. The spec's "known gap, accepted" is recorded in `Deck.CANDIDATE_EVERY`'s javadoc and in ADR 46.

**Type consistency.** `Card.known` / `Card.candidate` are used with those names in Tasks 2, 3 and 4. `Deck.deal`'s five parameters match its call in `RateRun.buildDeck`. `RateServer`'s constructor `(List<Card>, AffinityStore, int)` matches its use in `RateCli` and in `RateServerTest`. `Explained.candidate().entity()` matches the real record.

**Identifiers verified against the source, not inferred.** `Scorer.LIFT` exists (`Scorer.java:75`, alongside `RAW`, `ADAMIC_ADAR` and `RESOURCE_ALLOCATION`). `ArchitectureTest`'s `@ArchTest` annotation, its private `callTo(String, Class)` helper (`:199`) and `APPLIES_A_CLAIM` (`:244`) all exist and are reachable from a rule declared in that same class. `CandidateSweep`, `Routes` and `Sweep` are public, so `rate` can reuse them without widening anything.

**One thing the implementer should expect to fail on purpose.** Task 6 Step 1's third rule is written knowing it will fail, because `RateServer` legitimately constructs an `AffinityRecord`. Step 3 narrows it. That sequence is the point — the exception gets stated in a `because` clause rather than silently designed around.
