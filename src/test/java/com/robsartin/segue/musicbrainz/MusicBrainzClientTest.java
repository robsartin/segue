package com.robsartin.segue.musicbrainz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MusicBrainzClientTest {

  /**
   * Quintette du Hot Club de France, mbid {@code ee55e4e8-807d-49b1-8470-d1c0898ed7cb}: a French
   * jazz ensemble, active 1934-1948 and never reformed (every core member is long dead — Django
   * Reinhardt in 1953, the last survivor, Stéphane Grappelli, in 1997). Chosen, on the second round
   * of review, specifically against the prior fix round 1 named: CLAUDE.md documents that {@code
   * --known} is built from a <b>concert history</b> ("A list of acts you chose to go and see cannot
   * disagree with itself", ADR 40/48), which is the signal a fixture entity must sit well off, not
   * the absence of one. A group that finished existing in 1948 cannot appear on anyone's concert
   * history alive today; it does not merely predate a plausible Rob Sartin concert, it predates the
   * possibility of one for anyone. It is also non-Anglophone (French), a second, independent reason
   * it sits off that prior. Framing stays a reproducible API probe throughout — client javadoc,
   * this comment, the fixtures, the commit — never a statement about taste (ADR 51).
   */
  private static final String HOT_CLUB_QUINTET = "ee55e4e8-807d-49b1-8470-d1c0898ed7cb";

  @Test
  @DisplayName("should return every artist relation when the response states several")
  void shouldReturnEveryArtistRelationWhenTheResponseStatesSeveral() {
    // Captured with exactly inc=artist-rels — the same request fetch(mbid) sends in production —
    // so this fixture is a true record of "what MusicBrainz says to this client", not a superset.
    MusicBrainzClient client = MusicBrainzClient.readingFrom(fixture("artist-with-relations.json"));

    List<ArtistRelation> relations = client.artistRelations(HOT_CLUB_QUINTET);

    assertThat(relations).hasSize(24);
    assertThat(relations).allSatisfy(r -> assertThat(r.targetMbid()).isNotBlank());
    assertThat(relations).extracting(ArtistRelation::type).contains("member of band");
    // Every field of the record, not only the four the interface names — counted directly off
    // the committed fixture (all 24 rows), not recalled:
    assertThat(relations).extracting(ArtistRelation::direction).containsOnly("backward");
    assertThat(relations).extracting(ArtistRelation::targetName).contains("Django Reinhardt");
    // This entity's relations carry no join/leave dates in MusicBrainz at all — begin and end are
    // JSON null on all 24 rows, and ended is JSON false (present, not absent) on all 24. The
    // record must pass both through unmodified rather than coerce either: null must stay null
    // (nothing invents a date), and a real false must survive as Boolean.FALSE, not collapse to
    // the same null a missing/null "ended" would produce (see the ended-vs-begin/end note on
    // MusicBrainzClient.parseRelations).
    assertThat(relations).allSatisfy(r -> assertThat(r.begin()).isNull());
    assertThat(relations).allSatisfy(r -> assertThat(r.end()).isNull());
    assertThat(relations).allSatisfy(r -> assertThat(r.ended()).isEqualTo(Boolean.FALSE));
  }

  @Test
  @DisplayName("should skip a relation whose target is not an artist rather than throw")
  void shouldSkipARelationWhoseTargetIsNotAnArtistRatherThanThrow() {
    // fetch(mbid) only ever sends inc=artist-rels, and — verified live, not assumed — that
    // request alone never returns a non-artist-targeted relation: MusicBrainz only mixes target
    // kinds into one "relations" array when more than one inc category is requested. So this
    // second fixture is deliberately captured with inc=artist-rels+url-rels, a request this
    // client does not send, purely to give the "skip a non-artist target" branch a real response
    // to skip within, honestly — its filename says so, and it is not read by any other test.
    MusicBrainzClient client =
        MusicBrainzClient.readingFrom(fixture("artist-with-url-relations.json"));

    List<ArtistRelation> relations = client.artistRelations(HOT_CLUB_QUINTET);

    // This fixture holds 31 relations: 24 artist-targeted (the same 24 as the production-shaped
    // fixture above) plus 7 url-targeted (Discogs, IMDb, ...). 24 is what must come back — the 7
    // url-targeted relations must be skipped, not thrown on.
    assertThat(relations).hasSize(24);
  }

  @Test
  @DisplayName("a fixture that cannot be read surfaces as unavailable, not as a raw IOException")
  void unreadableFixtureSurfacesAsUnavailable() {
    MusicBrainzClient client = MusicBrainzClient.readingFrom(Path.of("does-not-exist.json"));

    assertThatThrownBy(() -> client.artistRelations(HOT_CLUB_QUINTET))
        .isInstanceOf(MusicBrainzUnavailableException.class);
  }

  @Test
  @DisplayName("throttleDelay waits out the remainder of the minimum request interval")
  void throttleDelayWaitsOutTheRemainderOfTheMinimumInterval() {
    Instant last = Instant.parse("2026-08-30T00:00:00.400Z");
    Instant now = Instant.parse("2026-08-30T00:00:00.900Z");

    // 500ms elapsed of the required 1000ms — 500ms still owed.
    assertThat(MusicBrainzClient.throttleDelay(last, now)).isEqualTo(Duration.ofMillis(500));
  }

  @Test
  @DisplayName("throttleDelay asks for no wait once the minimum interval has already passed")
  void throttleDelayAsksForNoWaitOnceTheIntervalHasPassed() {
    Instant last = Instant.parse("2026-08-30T00:00:00.000Z");
    Instant now = Instant.parse("2026-08-30T00:00:01.500Z");

    assertThat(MusicBrainzClient.throttleDelay(last, now)).isEqualTo(Duration.ZERO);
  }

  @Test
  @DisplayName("a 429 carrying Retry-After waits for as long as the header asks")
  void honoursRetryAfter() {
    // Mirrors WikidataClientTest — retryDelay is a package-private pure static, byte-identical in
    // shape to WikidataClient's, and fix round 1 found it had never been exercised here even
    // though its Wikidata twin is.
    assertThat(MusicBrainzClient.retryDelay("30", 1)).isEqualTo(Duration.ofSeconds(30));
  }

  @Test
  @DisplayName("an absurd Retry-After is capped, not obeyed")
  void capsRetryAfter() {
    assertThat(MusicBrainzClient.retryDelay("3600", 1)).isEqualTo(MusicBrainzClient.MAX_BACKOFF);
  }

  @Test
  @DisplayName("a missing or unparseable Retry-After falls back to exponential backoff")
  void fallsBackToExponentialBackoff() {
    assertThat(MusicBrainzClient.retryDelay(null, 1)).isEqualTo(Duration.ofMillis(200));
    assertThat(MusicBrainzClient.retryDelay(null, 3)).isEqualTo(Duration.ofMillis(800));
    assertThat(MusicBrainzClient.retryDelay("Wed, 21 Oct 2026 07:28:00 GMT", 2))
        .isEqualTo(Duration.ofMillis(400));
    assertThat(MusicBrainzClient.retryDelay("-5", 1)).isEqualTo(Duration.ofMillis(200));
  }

  @Test
  @DisplayName("it identifies segue by repository URL and never by an email address")
  void sendsRepositoryUserAgent() {
    try (StubMusicBrainzServer stub = new StubMusicBrainzServer()) {
      stub.enqueueBody(memberOfBandBody());
      new MusicBrainzClient(stub.baseUri()).artistRelations(HOT_CLUB_QUINTET);

      assertThat(stub.lastUserAgent()).contains("segue").contains("github.com/robsartin/segue");
      assertThat(stub.lastUserAgent()).doesNotContain("@");
    }
  }

  @Test
  @DisplayName("it retries a 429 and succeeds when the retry does")
  void retriesRateLimit() {
    try (StubMusicBrainzServer stub = new StubMusicBrainzServer()) {
      stub.enqueueStatus(429);
      stub.enqueueBody("{}");
      stub.enqueueStatus(200);
      stub.enqueueBody(memberOfBandBody());

      List<ArtistRelation> relations =
          new MusicBrainzClient(stub.baseUri()).artistRelations(HOT_CLUB_QUINTET);

      assertThat(relations).hasSize(1);
      assertThat(stub.requestCount()).isEqualTo(2);
    }
  }

  @Test
  @DisplayName("it gives up after repeated failures rather than retrying forever")
  void givesUpEventually() {
    try (StubMusicBrainzServer stub = new StubMusicBrainzServer()) {
      for (int i = 0; i < 6; i++) {
        stub.enqueueStatus(503);
        stub.enqueueBody("{}");
      }
      MusicBrainzClient client = new MusicBrainzClient(stub.baseUri());

      assertThatThrownBy(() -> client.artistRelations(HOT_CLUB_QUINTET))
          .isInstanceOf(MusicBrainzUnavailableException.class);
      assertThat(stub.requestCount()).isLessThanOrEqualTo(4);
    }
  }

  @Test
  @DisplayName("a 404 is not retried — it will not become a 200")
  void doesNotRetryClientErrors() {
    try (StubMusicBrainzServer stub = new StubMusicBrainzServer()) {
      stub.enqueueStatus(404);
      stub.enqueueBody("{}");
      MusicBrainzClient client = new MusicBrainzClient(stub.baseUri());

      assertThatThrownBy(() -> client.artistRelations(HOT_CLUB_QUINTET))
          .isInstanceOf(MusicBrainzUnavailableException.class);
      assertThat(stub.requestCount()).isEqualTo(1);
    }
  }

  @Test
  @DisplayName("a 200 carrying unparseable JSON surfaces as unavailable, not as a raw parser error")
  void unparseableBodyIsReportedAsUnavailable() {
    try (StubMusicBrainzServer stub = new StubMusicBrainzServer()) {
      for (int i = 0; i < 4; i++) {
        stub.enqueueStatus(200);
        stub.enqueueBody("this is not JSON");
      }
      MusicBrainzClient client = new MusicBrainzClient(stub.baseUri());

      assertThatThrownBy(() -> client.artistRelations(HOT_CLUB_QUINTET))
          .isInstanceOf(MusicBrainzUnavailableException.class);
    }
  }

  @Test
  @DisplayName("a request that never gets a response still counts against the 1 rps limit")
  void throttleAppliesEvenAfterAConnectionFailure() {
    // Fix round 1 of #91's Task 2 review: lastRequestAt was previously set only after http.send
    // returned, so an IOException (a refused or timed-out connection) never recorded an attempt —
    // a run of connection failures could retry in a tight loop with no throttle wait between them
    // at all, against the exact 1 rps limit this class exists to respect. A closed local port
    // reproduces "never gets a response" instantly and repeatably: connection-refused needs no
    // real network and no timeout to wait out.
    //
    // With the fix, four attempts against a dead port are spaced by ~MIN_REQUEST_INTERVAL each
    // (the backoff sleep between attempts is topped up to a full second by throttle()), so total
    // wall time is close to 3 seconds. The bug this guards would finish in the backoff time alone
    // — 200+400+800ms, about 1.4 seconds — so 2.5s is a lower bound that separates the two
    // clearly without being tight enough to flake on CI scheduling jitter.
    int deadPort = closedPort();
    MusicBrainzClient client =
        new MusicBrainzClient(URI.create("http://127.0.0.1:" + deadPort + "/"), Clock.systemUTC());

    long startNanos = System.nanoTime();
    assertThatThrownBy(() -> client.artistRelations(HOT_CLUB_QUINTET))
        .isInstanceOf(MusicBrainzUnavailableException.class);
    Duration elapsed = Duration.ofNanos(System.nanoTime() - startNanos);

    assertThat(elapsed).isGreaterThan(Duration.ofMillis(2500));
  }

  /** A single valid, minimal {@code artist-rels} response body, for the stub-server tests. */
  private static String memberOfBandBody() {
    return """
        {
          "relations": [
            {
              "type": "member of band",
              "direction": "backward",
              "artist": {
                "id": "11111111-1111-1111-1111-111111111111",
                "name": "A Stub Musician"
              }
            }
          ]
        }
        """;
  }

  private static int closedPort() {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    } catch (IOException e) {
      throw new IllegalStateException("could not find a free port to leave closed", e);
    }
  }

  private static Path fixture(String name) {
    try {
      return Path.of(MusicBrainzClientTest.class.getResource("/musicbrainz/" + name).toURI());
    } catch (URISyntaxException e) {
      throw new IllegalStateException(e);
    }
  }
}
