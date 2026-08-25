package com.robsartin.segue.wikidata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class WikidataClientTest {

  @Test
  @DisplayName("it parses a JSON response")
  void parsesJson() {
    try (StubWikidataServer stub = new StubWikidataServer()) {
      stub.enqueueBody("{\"search\":[{\"id\":\"Q5593\"}]}");
      WikidataClient client = new WikidataClient(stub.baseUri());

      assertThat(client.get(Map.of("action", "wbsearchentities")).at("/search/0/id").asText())
          .isEqualTo("Q5593");
    }
  }

  @Test
  @DisplayName("it identifies segue by repository URL and never by an email address")
  void sendsRepositoryUserAgent() {
    // ADR 16 and ADR 30. Wikidata's policy invites contact details; a personal address is
    // not ours to put in an outbound header.
    try (StubWikidataServer stub = new StubWikidataServer()) {
      stub.enqueueBody("{}");
      new WikidataClient(stub.baseUri()).get(Map.of("action", "wbgetentities"));

      assertThat(stub.lastUserAgent()).contains("segue").contains("github.com/robsartin/segue");
      assertThat(stub.lastUserAgent()).doesNotContain("@");
    }
  }

  @Test
  @DisplayName("it retries a 429 and succeeds when the retry does")
  void retriesRateLimit() {
    try (StubWikidataServer stub = new StubWikidataServer()) {
      stub.enqueueStatus(429);
      stub.enqueueBody("{}");
      stub.enqueueStatus(200);
      stub.enqueueBody("{\"ok\":true}");

      assertThat(
              new WikidataClient(stub.baseUri()).get(Map.of("action", "x")).at("/ok").asBoolean())
          .isTrue();
      assertThat(stub.requestCount()).isEqualTo(2);
    }
  }

  @Test
  @DisplayName("it gives up after repeated failures rather than retrying forever")
  void givesUpEventually() {
    try (StubWikidataServer stub = new StubWikidataServer()) {
      for (int i = 0; i < 6; i++) {
        stub.enqueueStatus(503);
        stub.enqueueBody("{}");
      }
      WikidataClient client = new WikidataClient(stub.baseUri());

      assertThatThrownBy(() -> client.get(Map.of("action", "x")))
          .isInstanceOf(WikidataUnavailableException.class);
      assertThat(stub.requestCount()).isLessThanOrEqualTo(4);
    }
  }

  @Test
  @DisplayName("a 404 is not retried — it will not become a 200")
  void doesNotRetryClientErrors() {
    try (StubWikidataServer stub = new StubWikidataServer()) {
      stub.enqueueStatus(404);
      stub.enqueueBody("{}");
      WikidataClient client = new WikidataClient(stub.baseUri());

      assertThatThrownBy(() -> client.get(Map.of("action", "x")))
          .isInstanceOf(WikidataUnavailableException.class);
      assertThat(stub.requestCount()).isEqualTo(1);
    }
  }

  @Test
  @DisplayName("a 200 carrying unparseable JSON surfaces as unavailable, not as a raw parser error")
  void unparseableBodyIsReportedAsUnavailable() {
    // Characterisation test, pinned before the Jackson 3 migration (#21). Jackson 2's parse
    // failure is an IOException, so it fell into this client's existing IOException handler by
    // accident of the type hierarchy; Jackson 3's is an unchecked JacksonException that would
    // escape it. The contract worth keeping is the one the callers rely on: everything that goes
    // wrong reaching or reading Wikidata arrives as WikidataUnavailableException, which is what
    // expand_entity reports as sourceUnavailable rather than failing the whole tool call.
    try (StubWikidataServer stub = new StubWikidataServer()) {
      for (int i = 0; i < 4; i++) {
        stub.enqueueStatus(200);
        stub.enqueueBody("this is not JSON");
      }
      WikidataClient client = new WikidataClient(stub.baseUri());

      assertThatThrownBy(() -> client.get(Map.of("action", "x")))
          .isInstanceOf(WikidataUnavailableException.class);
    }
  }
}
