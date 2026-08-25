package com.robsartin.segue.wikidata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
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
  @DisplayName("a 429 carrying Retry-After waits for as long as the header asks")
  void honoursRetryAfter() {
    // The Query Service throttles with 429 + Retry-After (its user manual: 60s of processing
    // per 60s per client). Retrying 200ms later — the exponential base — is both rude and
    // futile, because the budget has not refilled yet. Pure function, so the rule can be
    // asserted without a test that actually sleeps for a minute.
    assertThat(WikidataClient.retryDelay("30", 1)).isEqualTo(Duration.ofSeconds(30));
  }

  @Test
  @DisplayName("an absurd Retry-After is capped, not obeyed")
  void capsRetryAfter() {
    // An interactive tool call cannot hang for an hour on a header value, and this one is
    // attacker-influenceable in principle. The cap is what keeps "honour the server" from
    // becoming "let the server decide how long the user waits".
    assertThat(WikidataClient.retryDelay("3600", 1)).isEqualTo(WikidataClient.MAX_BACKOFF);
  }

  @Test
  @DisplayName("a missing or unparseable Retry-After falls back to exponential backoff")
  void fallsBackToExponentialBackoff() {
    // HTTP allows Retry-After to be an HTTP-date rather than a number of seconds, and this
    // client does not parse dates. Falling back is what stops that shape from becoming a
    // crash inside the retry loop.
    assertThat(WikidataClient.retryDelay(null, 1)).isEqualTo(Duration.ofMillis(200));
    assertThat(WikidataClient.retryDelay(null, 3)).isEqualTo(Duration.ofMillis(800));
    assertThat(WikidataClient.retryDelay("Wed, 21 Oct 2026 07:28:00 GMT", 2))
        .isEqualTo(Duration.ofMillis(400));
    assertThat(WikidataClient.retryDelay("-5", 1)).isEqualTo(Duration.ofMillis(200));
  }

  @Test
  @DisplayName("a 429 with Retry-After: 0 is retried immediately and succeeds")
  void retriesImmediatelyWhenRetryAfterIsZero() {
    try (StubWikidataServer stub = new StubWikidataServer()) {
      stub.enqueueStatus(429);
      stub.enqueueHeader("Retry-After", "0");
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
