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
}
