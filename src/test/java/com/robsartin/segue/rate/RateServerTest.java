package com.robsartin.segue.rate;

import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.segue.domain.AffinityRecord;
import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.domain.NodeRecord;
import com.robsartin.segue.port.AffinityStore;
import com.robsartin.segue.sqlite.SqliteAffinityStore;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class RateServerTest {

  /** Records what it was given; asserts by inspection, so no mocking framework is needed. */
  private static final class RecordingAffinity implements AffinityStore {
    private final List<AffinityRecord> written = new ArrayList<>();

    @Override
    public void put(AffinityRecord affinity) {
      written.add(affinity);
    }

    /**
     * The write the deck actually makes. Recorded as the record it is equivalent to — the deck
     * never has a note, so the note-free write and a note-free record carry the same information,
     * and every existing assertion on {@code written} keeps meaning what it meant.
     */
    @Override
    public void updateRating(String qid, int rating, Instant updatedAt) {
      written.add(new AffinityRecord(qid, rating, null, updatedAt));
    }

    @Override
    public Optional<AffinityRecord> find(String qid) {
      return Optional.empty();
    }

    @Override
    public List<AffinityRecord> readAll() {
      return List.of();
    }

    @Override
    public Map<String, Integer> readRatings() {
      return Map.of();
    }

    @Override
    public void close() {}
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
  @DisplayName("a card built with Card.known has no current rating, and the JSON says so")
  void unratedCardSerialisesCurrentRatingAsNull() throws Exception {
    HttpResponse<String> response =
        client.send(request("/api/card?i=0").build(), HttpResponse.BodyHandlers.ofString());

    ObjectMapper mapper = JsonMapper.builder().build();
    JsonNode node = mapper.readTree(response.body());

    assertThat(node.has("currentRating"))
        .as("currentRating must be present, even when null")
        .isTrue();
    assertThat(node.path("currentRating").isNull()).isTrue();
  }

  @Test
  @DisplayName(
      "a card built with Card.rated (issue #109) serialises the rating it already has, exactly")
  void revisionCardSerialisesItsExistingRating() throws Exception {
    Card revision =
        Card.rated(new NodeRecord("Q900002", NodeKind.GROUP, "Rated Band", List.of()), 7, 3);
    RateServer revisionServer = new RateServer(List.of(revision), affinity, 0);
    revisionServer.start();
    try {
      HttpResponse<String> response =
          client.send(
              HttpRequest.newBuilder(
                      URI.create("http://127.0.0.1:" + revisionServer.port() + "/api/card?i=0"))
                  .build(),
              HttpResponse.BodyHandlers.ofString());

      ObjectMapper mapper = JsonMapper.builder().build();
      JsonNode node = mapper.readTree(response.body());

      assertThat(node.path("currentRating").isNull()).isFalse();
      assertThat(node.path("currentRating").asInt()).isEqualTo(3);
    } finally {
      revisionServer.stop();
    }
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
  @DisplayName("a card request carrying a foreign Origin is refused, so ratings cannot be read out")
  void refusesAForeignOriginOnACard() throws Exception {
    // /api/card carried no Origin check at all until the issue-#109 review. Once the body grew
    // currentRating, the read path leaked the ratings themselves rather than mere known-list
    // membership — under exactly the DNS-rebinding scenario the allowlist exists to stop.
    HttpResponse<String> response =
        client.send(
            request("/api/card?i=0").header("Origin", "https://evil.example.com").build(),
            HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(403);
    assertThat(response.body()).doesNotContain("Test Band");
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

  @Test
  @DisplayName(
      "a label carrying a newline, a tab and a control character comes back as valid JSON and"
          + " round-trips to the exact original string")
  void escapesControlCharactersInJson() throws Exception {
    // A real Wikidata label is free text from an openly editable source; this is a deliberately
    // adversarial one, invented for the test (never real data — see CLAUDE.md), that exercises
    // every character RateServer.escape() must turn into a valid JSON string escape rather than
    // emit literally: \n (a JSON control character), \t (another), and \u0001 (a raw C0 control
    // character with no dedicated JSON shorthand, so it needs the generic \\uXXXX form).
    String weirdLabel = "Weird\nLabel\tWith\u0001Control";
    NodeRecord weird = new NodeRecord("Q900099", NodeKind.GROUP, weirdLabel, List.of());
    RateServer weirdServer = new RateServer(List.of(Card.known(weird, 3)), affinity, 0);
    weirdServer.start();
    try {
      HttpResponse<String> response =
          client.send(
              HttpRequest.newBuilder(
                      URI.create("http://127.0.0.1:" + weirdServer.port() + "/api/card?i=0"))
                  .build(),
              HttpResponse.BodyHandlers.ofString());

      // readTree throws on malformed JSON — a literal, unescaped control character embedded in a
      // JSON string is invalid per RFC 8259, and a real strict JSON parser (this is the same
      // Jackson 3 the rest of the project uses, not a lenient hand-rolled reader) is what would
      // choke on the deck page's own `await response.json()`.
      ObjectMapper mapper = JsonMapper.builder().build();
      JsonNode node = mapper.readTree(response.body());

      assertThat(node.path("label").asText()).isEqualTo(weirdLabel);
    } finally {
      weirdServer.stop();
    }
  }

  @Test
  @DisplayName("an unterminated JSON string in the body is a 400, not a handler that throws")
  void refusesAnUnterminatedString() throws Exception {
    // The body a browser could never send and a curl typo sends constantly. The scan for the
    // closing quote used to return -1 and go straight into String.substring, which raises
    // StringIndexOutOfBoundsException — NOT an IllegalArgumentException, so the catch missed it,
    // the handler threw, and com.sun.net.httpserver closed the connection with no response at
    // all. A malformed body must be refused, and refusing means answering.
    HttpResponse<String> response =
        client.send(
            request("/api/rate")
                .POST(HttpRequest.BodyPublishers.ofString("{\"qid\":\"Q900001"))
                .build(),
            HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(affinity.written).isEmpty();
  }

  @Test
  @DisplayName("a body with no colon after the field name is a 400 rather than a thrown handler")
  void refusesAFieldWithNoValue() throws Exception {
    HttpResponse<String> response =
        client.send(
            request("/api/rate").POST(HttpRequest.BodyPublishers.ofString("{\"qid\"}")).build(),
            HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(affinity.written).isEmpty();
  }

  @Test
  @DisplayName("a non-integer rating is refused, never truncated into the affinity table")
  void refusesANonIntegerRating() throws Exception {
    // 4.7 used to be stored as 4: the digit scan stopped at the '.' and the remainder was
    // dropped in silence. The affinity table is the one thing in segue with no source to
    // regenerate it from, so a fabricated value in it is worse than a refusal.
    HttpResponse<String> response =
        client.send(
            request("/api/rate")
                .POST(HttpRequest.BodyPublishers.ofString("{\"qid\":\"Q900001\",\"rating\":4.7}"))
                .build(),
            HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(affinity.written).isEmpty();
  }

  @Test
  @DisplayName("the IPv6 loopback origin the allowlist claims to accept is actually accepted")
  void acceptsTheIpv6LoopbackOrigin() throws Exception {
    // URI.getHost() returns an IPv6 literal in its brackets, so "http://[::1]:8090" yields
    // "[::1]" — the bare "::1" the allowlist used to carry could never match anything, and both
    // ADR 46 and this class's Javadoc claimed it did.
    HttpResponse<String> response =
        client.send(
            request("/api/rate")
                .header("Origin", "http://[::1]:" + server.port())
                .POST(HttpRequest.BodyPublishers.ofString("{\"qid\":\"Q900001\",\"rating\":3}"))
                .build(),
            HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(204);
    assertThat(affinity.written).hasSize(1);
  }

  @Test
  @DisplayName(
      "re-rating an entity that already has a note leaves the note alone (issue #109 review)")
  void reRatingKeepsTheNoteThatIsAlreadyThere() throws Exception {
    // The failure --revise made reachable. Before this branch the deck could only deal UNRATED
    // entities, and a note requires a rating (note_affinity writes both), so no note-bearing row
    // was reachable from here at all. dealRevision inverts that: it selects exactly the
    // already-rated population, which is precisely where SegueService.noteAffinity writes notes.
    //
    // A real store, not the recording fake — the erasure happened in the SQL, where the upsert
    // takes excluded.note, so a fake that only remembers what it was handed cannot show it.
    try (SqliteAffinityStore store = SqliteAffinityStore.inMemory()) {
      store.put(
          new AffinityRecord(
              "Q900001", 3, "invented note, never Rob's", Instant.parse("2026-01-01T00:00:00Z")));
      Card card =
          Card.rated(new NodeRecord("Q900001", NodeKind.GROUP, "Test Band", List.of()), 42, 3);
      RateServer revising = new RateServer(List.of(card), store, 0);
      revising.start();
      try {
        HttpResponse<String> response =
            client.send(
                HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + revising.port() + "/api/rate"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"qid\":\"Q900001\",\"rating\":2}"))
                    .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(204);
        assertThat(store.find("Q900001")).isPresent();
        assertThat(store.find("Q900001").orElseThrow().rating()).isEqualTo(2);
        assertThat(store.find("Q900001").orElseThrow().note())
            .as("the affinity table is the one thing in segue with no source to regenerate it from")
            .isEqualTo("invented note, never Rob's");
      } finally {
        revising.stop();
      }
    }
  }

  @Test
  @DisplayName("a missing deck.html answers 500 rather than closing the connection on the browser")
  void answersWhenThePageResourceIsMissing() throws Exception {
    RateServer broken = new RateServer(List.of(), affinity, 0, "/rate/there-is-no-such-page.html");
    broken.start();
    try {
      HttpResponse<String> response =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + broken.port() + "/")).build(),
              HttpResponse.BodyHandlers.ofString());

      assertThat(response.statusCode()).isEqualTo(500);
    } finally {
      broken.stop();
    }
  }
}
