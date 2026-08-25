package com.robsartin.segue.wikidata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

/**
 * The Wikidata Action API over the JDK's own HTTP client.
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
   * Wikidata's policy asks callers to identify themselves and offer a contact route. The repository
   * URL is that route. A personal email address is not ours to send (ADR 16).
   */
  private static final String USER_AGENT = "segue/0.1 (https://github.com/robsartin/segue)";

  private static final int MAX_ATTEMPTS = 4;
  private static final Duration BACKOFF_BASE = Duration.ofMillis(200);

  private final URI baseUri;
  private final HttpClient http;
  private final ObjectMapper mapper = new ObjectMapper();

  public WikidataClient() {
    this(DEFAULT_BASE);
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
    for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
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
        last = new WikidataUnavailableException("Wikidata returned HTTP " + status);
      } catch (IOException e) {
        last = new WikidataUnavailableException("could not reach Wikidata", e);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new WikidataUnavailableException("interrupted while calling Wikidata", e);
      }
      // No backoff after the final attempt — the decision to fail is already made, and
      // expand() swallows this exception, so the wait would be a user staring at nothing.
      if (attempt < MAX_ATTEMPTS) {
        backoff(attempt);
      }
    }
    throw new WikidataUnavailableException(
        "Wikidata did not answer after " + MAX_ATTEMPTS + " attempts", last);
  }

  private static boolean isTransient(int status) {
    return status == 429 || status >= 500;
  }

  private static void backoff(int attempt) {
    try {
      Thread.sleep(BACKOFF_BASE.toMillis() * (1L << (attempt - 1)));
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
