package com.robsartin.segue.wikidata;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Wikidata's HTTP endpoints over the JDK's own HTTP client.
 *
 * <p>Two endpoints, one client: the Action API ({@code /w/api.php}) that {@link
 * WikidataEntityResolver} reads entities from, and the Query Service ({@code
 * query.wikidata.org/sparql}) that {@link ReverseClaims} runs SPARQL against (ADR 36). Both are
 * GETs with query parameters, both want the same User-Agent, and both throttle the same way — so
 * they get the same retry policy and the same single failure type rather than a second client with
 * its own subtly different rules.
 *
 * <p>No Spring, deliberately: keeping this package plain Java is what lets it be tested against an
 * in-process stub with no application context, and what keeps ADR 25's promise that adding a source
 * touches only its own adapter.
 *
 * <p>Retries are for transient conditions only. A 429 or a 5xx may succeed on a second attempt; a
 * 404 will not, so retrying it just wastes someone else's capacity.
 */
public final class WikidataClient {

  private static final URI DEFAULT_BASE = URI.create("https://www.wikidata.org/w/api.php");

  /**
   * The Wikidata Query Service SPARQL endpoint. Named here rather than in {@code app} so the
   * endpoint stays an adapter detail — ADR 25's rule is that adding or moving a source touches only
   * its own package.
   */
  private static final URI QUERY_SERVICE = URI.create("https://query.wikidata.org/sparql");

  /**
   * Wikidata's policy asks callers to identify themselves and offer a contact route. The repository
   * URL is that route. A personal email address is not ours to send (ADR 16).
   */
  private static final String USER_AGENT = "segue/0.1 (https://github.com/robsartin/segue)";

  private static final int MAX_ATTEMPTS = 4;
  private static final Duration BACKOFF_BASE = Duration.ofMillis(200);

  /**
   * The ceiling on a single wait between attempts, and specifically on an honoured {@code
   * Retry-After}. Without it the server decides how long an interactive tool call blocks, and a
   * header saying {@code 3600} would hang {@code expand_entity} for an hour.
   */
  static final Duration MAX_BACKOFF = Duration.ofSeconds(30);

  private final URI baseUri;
  private final HttpClient http;
  private final ObjectMapper mapper = new ObjectMapper();

  public WikidataClient() {
    this(DEFAULT_BASE);
  }

  /** A client aimed at the SPARQL endpoint rather than the Action API. See ADR 36. */
  public static WikidataClient queryService() {
    return new WikidataClient(QUERY_SERVICE);
  }

  public WikidataClient(URI baseUri) {
    this.baseUri = Objects.requireNonNull(baseUri, "baseUri");
    this.http =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
  }

  /** GET the API with the given parameters, always as JSON. */
  public JsonNode get(Map<String, String> queryParams) {
    Objects.requireNonNull(queryParams, "queryParams");
    URI uri = URI.create(baseUri + "?" + encode(queryParams));

    RuntimeException last = null;
    String retryAfter = null;
    for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
      retryAfter = null;
      try {
        HttpResponse<String> response =
            http.send(
                HttpRequest.newBuilder(uri)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(20))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        int status = response.statusCode();
        if (status == 200) {
          return mapper.readTree(response.body());
        }
        if (!isTransient(status)) {
          throw new WikidataUnavailableException(
              "Wikidata returned HTTP " + status + " for " + uri);
        }
        // The Query Service throttles with 429 + Retry-After (60s of query processing per 60s
        // per client, per its user manual). Coming back 200ms later, as the exponential base
        // would, is both rude and futile — the budget has not refilled.
        retryAfter = response.headers().firstValue("Retry-After").orElse(null);
        last = new WikidataUnavailableException("Wikidata returned HTTP " + status);
      } catch (IOException e) {
        last = new WikidataUnavailableException("could not reach Wikidata", e);
      } catch (JacksonException e) {
        // A 200 whose body will not parse. Jackson 2's parse failure was an IOException and so
        // fell into the handler above by accident of the type hierarchy; Jackson 3's is unchecked
        // and would otherwise escape this loop raw (ADR 35). Callers are entitled to one failure
        // type from this adapter — expand() reports it as sourceUnavailable — so it is named here
        // rather than left to propagate.
        last = new WikidataUnavailableException("Wikidata sent a body that would not parse", e);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new WikidataUnavailableException("interrupted while calling Wikidata", e);
      }
      // No backoff after the final attempt — the decision to fail is already made, and
      // expand() swallows this exception, so the wait would be a user staring at nothing.
      if (attempt < MAX_ATTEMPTS) {
        sleep(retryDelay(retryAfter, attempt));
      }
    }
    throw new WikidataUnavailableException(
        "Wikidata did not answer after " + MAX_ATTEMPTS + " attempts", last);
  }

  private static boolean isTransient(int status) {
    return status == 429 || status >= 500;
  }

  /**
   * How long to wait before attempt {@code attempt + 1}.
   *
   * <p>A pure function on purpose. The rule it encodes — obey the server, but only up to {@link
   * #MAX_BACKOFF}, and fall back to exponential backoff when the header is absent or is not a plain
   * number of seconds — is the part worth asserting, and asserting it through the retry loop would
   * mean a test that really sleeps for the values it is checking.
   *
   * @param retryAfter the response's {@code Retry-After} header, or null
   * @param attempt the 1-based attempt that just failed
   */
  static Duration retryDelay(String retryAfter, int attempt) {
    Duration exponential = BACKOFF_BASE.multipliedBy(1L << (attempt - 1));
    if (retryAfter == null || retryAfter.isBlank()) {
      return min(exponential, MAX_BACKOFF);
    }
    long seconds;
    try {
      seconds = Long.parseLong(retryAfter.trim());
    } catch (NumberFormatException e) {
      // HTTP also allows an HTTP-date here, which this client does not parse. Falling back
      // keeps a legal-but-unhandled header shape from becoming a crash inside the retry loop.
      return min(exponential, MAX_BACKOFF);
    }
    if (seconds < 0) {
      return min(exponential, MAX_BACKOFF);
    }
    return min(Duration.ofSeconds(seconds), MAX_BACKOFF);
  }

  private static Duration min(Duration a, Duration b) {
    return a.compareTo(b) <= 0 ? a : b;
  }

  private static void sleep(Duration delay) {
    if (delay.isZero() || delay.isNegative()) {
      return;
    }
    try {
      Thread.sleep(delay.toMillis());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new WikidataUnavailableException("interrupted while backing off", e);
    }
  }

  private static String encode(Map<String, String> params) {
    return params.entrySet().stream()
        .map(
            e ->
                URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8)
                    + "="
                    + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
        .collect(Collectors.joining("&"));
  }
}
