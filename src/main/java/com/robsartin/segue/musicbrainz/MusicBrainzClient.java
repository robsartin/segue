package com.robsartin.segue.musicbrainz;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * MusicBrainz's {@code ws/2} web service, read for one thing: an artist's {@code artist-rels}.
 *
 * <p>Modelled on {@code wikidata.WikidataClient} — same User-Agent shape, same retry policy, same
 * one-failure-type contract ({@link MusicBrainzUnavailableException}) — deliberately, per ADR 32:
 * adding a source is not licence to invent a second HTTP style. It differs in two ways that the
 * source itself forces:
 *
 * <ul>
 *   <li>MusicBrainz needs no API key, but does ask for a <b>proactive</b> ~1 request/second pace
 *       rather than a reactive one. See {@link #MIN_REQUEST_INTERVAL}.
 *   <li>This client also parses the one response shape it fetches, into {@link ArtistRelation}.
 *       Wikidata splits fetching ({@code WikidataClient}) from interpreting ({@code ClaimMapper},
 *       {@code ReverseClaims}) because it has two endpoints and two shapes to reconcile; this
 *       source has one endpoint and one shape, so the split would be a second file with nothing of
 *       its own to say.
 * </ul>
 *
 * <p>No Spring, for the same reason as {@code WikidataClient}: staying plain Java is what lets this
 * be tested with no application context, and what keeps ADR 25's promise that adding a source
 * touches only its own package.
 */
public final class MusicBrainzClient {

  private static final URI DEFAULT_BASE = URI.create("https://musicbrainz.org/ws/2/");

  /**
   * MusicBrainz's policy asks callers to identify themselves and offer a contact route. The
   * repository URL is that route. A personal email address is not ours to send (ADR 16).
   */
  private static final String USER_AGENT = "segue/0.1 (https://github.com/robsartin/segue)";

  private static final int MAX_ATTEMPTS = 4;
  private static final Duration BACKOFF_BASE = Duration.ofMillis(200);

  /**
   * The ceiling on a single wait between attempts. See {@code WikidataClient}'s field of the same
   * name for why an interactive tool call needs one.
   */
  static final Duration MAX_BACKOFF = Duration.ofSeconds(30);

  /**
   * MusicBrainz's own stated requirement for unauthenticated {@code ws/2} access is roughly one
   * request per second — this is not a guess this project made up. Enforced proactively, before
   * sending, rather than reactively after a 503: {@code docs/design/2026-08-30-three-source-
   * adapters.md} records a probe that drew a "server is currently busy" response at roughly
   * 1-second spacing, so waiting for that response to say so would waste a request MusicBrainz
   * already asked callers not to send.
   */
  static final Duration MIN_REQUEST_INTERVAL = Duration.ofSeconds(1);

  private final URI baseUri;
  private final HttpClient http;
  private final ObjectMapper mapper = new ObjectMapper();
  private final Path fixture;
  private final Clock clock;
  private volatile Instant lastRequestAt;

  public MusicBrainzClient() {
    this(DEFAULT_BASE, null, Clock.systemUTC());
  }

  public MusicBrainzClient(URI baseUri) {
    this(Objects.requireNonNull(baseUri, "baseUri"), null, Clock.systemUTC());
  }

  /**
   * Package-private: a real-HTTP client with an injectable clock, so a test can drive {@link
   * #throttle()}'s wiring to {@link #fetch} deterministically rather than only its pure {@link
   * #throttleDelay} calculation.
   */
  MusicBrainzClient(URI baseUri, Clock clock) {
    this(Objects.requireNonNull(baseUri, "baseUri"), null, Objects.requireNonNull(clock, "clock"));
  }

  /**
   * A client that answers {@link #artistRelations(String)} for any mbid by re-reading one committed
   * response file, rather than the network. This is how {@code ./gradlew check} tests this class
   * with no network reachable — see CLAUDE.md's "No network in {@code ./gradlew check}" rule.
   */
  public static MusicBrainzClient readingFrom(Path fixture) {
    return new MusicBrainzClient(
        null, Objects.requireNonNull(fixture, "fixture"), Clock.systemUTC());
  }

  private MusicBrainzClient(URI baseUri, Path fixture, Clock clock) {
    this.baseUri = baseUri;
    this.fixture = fixture;
    this.clock = clock;
    this.http =
        fixture == null
            ? HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build()
            : null;
  }

  /**
   * The artist's relations to other artists — {@code inc=artist-rels}, whichever end of the pair
   * {@code mbid} names (see the MusicBrainz section of {@code docs/design/2026-08-30-three-source-
   * adapters.md} for why one call suffices). A relation whose target is not an artist — a work, a
   * label, a release, a URL — is skipped rather than reported: {@code inc=artist-rels} responses
   * carry those too, and skipping one is normal operation, not an error.
   */
  public List<ArtistRelation> artistRelations(String mbid) {
    Objects.requireNonNull(mbid, "mbid");
    JsonNode root = fixture != null ? readFixture() : fetch(mbid);
    return parseRelations(root);
  }

  private JsonNode readFixture() {
    try {
      return mapper.readTree(Files.readString(fixture, StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new MusicBrainzUnavailableException("could not read fixture " + fixture, e);
    } catch (JacksonException e) {
      throw new MusicBrainzUnavailableException("fixture at " + fixture + " would not parse", e);
    }
  }

  private static List<ArtistRelation> parseRelations(JsonNode root) {
    List<ArtistRelation> relations = new ArrayList<>();
    for (JsonNode relation : root.path("relations")) {
      JsonNode artist = relation.path("artist");
      if (artist.isMissingNode() || artist.isNull()) {
        // Points at a work, label, release, recording, series, place, event or URL instead —
        // not this client's concern. MusicBrainz mixes these into one "relations" array with no
        // separate collection per target kind.
        continue;
      }
      String targetMbid = artist.path("id").asText(null);
      if (targetMbid == null || targetMbid.isBlank()) {
        continue;
      }
      JsonNode endedNode = relation.path("ended");
      relations.add(
          new ArtistRelation(
              targetMbid,
              relation.path("type").asText(null),
              relation.path("direction").asText(null),
              artist.path("name").asText(null),
              relation.path("begin").asText(null),
              relation.path("end").asText(null),
              // Missing and JSON null both mean "MusicBrainz didn't say" — the same case
              // asText(null) already collapses for begin/end/type/direction/name above. asBoolean()
              // has no such default-on-null form, so it is spelled out here instead.
              endedNode.isMissingNode() || endedNode.isNull() ? null : endedNode.asBoolean()));
    }
    return List.copyOf(relations);
  }

  private JsonNode fetch(String mbid) {
    // Encoded like every parameter WikidataClient sends (its encode(), :190-198) — mbids are
    // UUIDs today, so this changes nothing about them, but it keeps one convention rather than an
    // interpolated-elsewhere exception to it.
    URI uri =
        URI.create(
            baseUri
                + "artist/"
                + URLEncoder.encode(mbid, StandardCharsets.UTF_8)
                + "?inc=artist-rels&fmt=json");

    RuntimeException last = null;
    String retryAfter = null;
    for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
      throttle();
      // Recorded here, before the send is even attempted, not after a response comes back:
      // an attempt that never gets a response — a connection refused, a timeout — still spent
      // MusicBrainz's ~1-request-per-second budget and must still count against it. Recording
      // this only on success let a run of connection failures retry in a tight loop with no
      // throttle wait between them at all (issue found in fix round 1 of #91's Task 2 review).
      lastRequestAt = clock.instant();
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
          throw new MusicBrainzUnavailableException(
              "MusicBrainz returned HTTP " + status + " for " + uri);
        }
        retryAfter = response.headers().firstValue("Retry-After").orElse(null);
        last = new MusicBrainzUnavailableException("MusicBrainz returned HTTP " + status);
      } catch (IOException e) {
        last = new MusicBrainzUnavailableException("could not reach MusicBrainz", e);
      } catch (JacksonException e) {
        last =
            new MusicBrainzUnavailableException("MusicBrainz sent a body that would not parse", e);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new MusicBrainzUnavailableException("interrupted while calling MusicBrainz", e);
      }
      // No backoff after the final attempt — the decision to fail is already made.
      if (attempt < MAX_ATTEMPTS) {
        sleep(retryDelay(retryAfter, attempt));
      }
    }
    throw new MusicBrainzUnavailableException(
        "MusicBrainz did not answer after " + MAX_ATTEMPTS + " attempts", last);
  }

  /**
   * Blocks, if needed, so that no two requests leave this client less than {@link
   * #MIN_REQUEST_INTERVAL} apart. A no-op on the very first call.
   */
  private void throttle() {
    Instant previous = lastRequestAt;
    if (previous == null) {
      return;
    }
    sleep(throttleDelay(previous, clock.instant()));
  }

  /**
   * How long to wait before the next request leaves, given when the last one left. A pure function
   * for the same reason {@link #retryDelay} is: the rule is what is worth asserting, and asserting
   * it through {@link #throttle} would mean a test that really waits a second.
   */
  static Duration throttleDelay(Instant lastRequestAt, Instant now) {
    Duration elapsed = Duration.between(lastRequestAt, now);
    Duration remaining = MIN_REQUEST_INTERVAL.minus(elapsed);
    return remaining.isNegative() ? Duration.ZERO : remaining;
  }

  private static boolean isTransient(int status) {
    return status == 429 || status >= 500;
  }

  /** How long to wait before attempt {@code attempt + 1}. See {@code WikidataClient.retryDelay}. */
  static Duration retryDelay(String retryAfter, int attempt) {
    Duration exponential = BACKOFF_BASE.multipliedBy(1L << (attempt - 1));
    if (retryAfter == null || retryAfter.isBlank()) {
      return min(exponential, MAX_BACKOFF);
    }
    long seconds;
    try {
      seconds = Long.parseLong(retryAfter.trim());
    } catch (NumberFormatException e) {
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
      throw new MusicBrainzUnavailableException("interrupted while waiting", e);
    }
  }
}
