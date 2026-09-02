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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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

  /**
   * How much under {@link #concurrentCallersDoNotLeaveTogether}'s client's own {@code
   * minRequestInterval} an observed gap may fall before that test calls it a violation, for two
   * reasons that are both about scheduling and neither about the property under test.
   *
   * <p>The first is the measurement. The invariant is about when a request <i>leaves</i>; what a
   * stub server can observe is when one <i>arrives</i>, and the send latency between the two varies
   * per request. The second is real and is written down in {@code MusicBrainzClient.reserve}'s
   * javadoc: slots are issued a fixed interval apart and no request leaves before its own, but a
   * thread descheduled past its slot sends late and can land nearer the caller behind it.
   * Serialising every send behind one lock is the only thing that would close that, at the cost of
   * the non-blocking path the compare-and-set leaves open.
   *
   * <p><b>This is the fallback scale, not the primary one, and the reason is measured, not
   * guessed.</b> {@code docs/superpowers/specs/2026-09-02-mb-concurrency-design.md} calls for a
   * 50ms interval with a 10ms allowance first; the 100ms/20ms fallback is the <i>plan's</i> ({@code
   * docs/superpowers/plans/2026-09-02-mb-concurrency.md}, Step 6), which is what sanctions it — the
   * word "fallback" appears nowhere in the spec. On this machine, shared with other agents' builds
   * (load average 110–166, well above the design's own ≥100 reference scenario), the
   * <em>correct</em> client reproducibly failed at that scale — descheduling gaps of 0.0161s and
   * 0.0237s against a 0.04s floor, absolute overruns an order of magnitude past what a quiet
   * machine showed. That is the real behaviour {@code reserve}'s javadoc already predicts, not the
   * #146 defect: the planted #146 defect's own signature at this same 50ms scale was sub-24ms and
   * absolute regardless of load ({@code [0.0119S, 0.0236S, 0.0136S]}), which is indistinguishable
   * from ordinary scheduling jitter once contention is this heavy — the two can no longer be told
   * apart at 50ms on a machine this busy. Widening to 100ms/20ms does not change the absolute
   * scheduling jitter a busy machine imposes, but it does buy back the ratio: the same class of
   * overrun stayed clear of an 0.08s floor across repeated runs <b>of the full class</b>, which the
   * 0.04s floor could not tolerate.
   *
   * <p><b>Contention is not the variable, and this allowance does not survive the correction.</b>
   * An earlier draft of this javadoc said the 0.08s floor was cleared "once contention eased". It
   * was not: Task 1's review re-measured at load average 125–152 throughout and found the
   * <em>correct</em> client 0/5 when this test runs alone in a fresh JVM and 5/5 inside the full
   * class, at this same 100ms/20ms scale. The variable is a cold JVM versus a warm one — caller 1's
   * first ever send costs 40–60ms to reach the socket where caller 2's, on a path 18 other tests
   * have already JIT-compiled, costs about 1ms — so the allowance is absorbing send-latency
   * variance an order of magnitude larger than the arithmetic error it is meant to bound. That is
   * this repository's "proxy assertion clean on a small sample": arrival spacing is not departure
   * spacing, and no width of allowance fixes an assertion whose noise term is bigger than its
   * signal. Recorded here rather than quietly deleted, because the number below was shipped on the
   * strength of the sentence it corrects.
   *
   * <p><b>It is nowhere near wide enough to admit the defect.</b> The #146 check-then-act defect's
   * signature is absolute and sub-25ms regardless of scale (see above); this allowance is 20ms at a
   * 100ms interval, leaving an 80ms floor — over three times the worst gap the defect produced in
   * six runs across both scales.
   */
  private static final Duration SLOT_OVERRUN_ALLOWANCE = Duration.ofMillis(20);

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
    assertThat(MusicBrainzClient.throttleDelay(last, now, Duration.ofSeconds(1)))
        .isEqualTo(Duration.ofMillis(500));
  }

  @Test
  @DisplayName("throttleDelay asks for no wait once the minimum interval has already passed")
  void throttleDelayAsksForNoWaitOnceTheIntervalHasPassed() {
    Instant last = Instant.parse("2026-08-30T00:00:00.000Z");
    Instant now = Instant.parse("2026-08-30T00:00:01.500Z");

    assertThat(MusicBrainzClient.throttleDelay(last, now, Duration.ofSeconds(1)))
        .isEqualTo(Duration.ZERO);
  }

  @Test
  @DisplayName("a default-constructed client keeps MusicBrainz's real one-second pace")
  void shouldKeepTheDefaultRequestIntervalWhenNoInstanceOverrideIsGiven() {
    // Control 3 (spec, "the definition of done"): the interval seam Step 4 added must not move
    // production's pace. SegueConfiguration:139 builds a client with the no-argument constructor,
    // which never overrides minRequestInterval, so this pins that path directly rather than only
    // pinning the constant it is supposed to equal.
    MusicBrainzClient client = new MusicBrainzClient();

    assertThat(client.minRequestInterval()).isEqualTo(Duration.ofSeconds(1));
    assertThat(MusicBrainzClient.DEFAULT_MIN_REQUEST_INTERVAL).isEqualTo(Duration.ofSeconds(1));
  }

  @Test
  @DisplayName("a default-constructed client waits for real, not through a test's no-op")
  void shouldKeepARealSleeperWhenNoInstanceOverrideIsGiven() throws InterruptedException {
    // Control 3's other half, for the second seam. Three tests in this class now pass a sleeper
    // that returns at once; the thing that must never happen is that one reaching production.
    // SegueConfiguration:139 builds a client with the no-argument constructor, which passes
    // Thread::sleep, so this pins that path directly.
    //
    // Fifty milliseconds against a floor of forty, and the asymmetry is the point: the defect this
    // guards is a sleeper that does not wait at all, so the signal is the whole 50ms and the noise
    // is system-timer precision, well under a millisecond. Thread.sleep can only overrun. That is
    // the reverse of the ratio that made the arrival-spacing assertion unsound.
    MusicBrainzClient client = new MusicBrainzClient();

    long startNanos = System.nanoTime();
    client.sleeper().sleep(Duration.ofMillis(50));
    Duration elapsed = Duration.ofNanos(System.nanoTime() - startNanos);

    assertThat(elapsed).isGreaterThanOrEqualTo(Duration.ofMillis(40));
  }

  @Test
  @DisplayName("a wait is asked for in full, with its sub-millisecond remainder intact")
  void aWaitIsAskedForInFullRatherThanTruncated() {
    // The defect this guards is not the concurrency one; it was found by it. sleep() passed
    // delay.toMillis() to Thread.sleep, and toMillis() truncates, so 999.5ms of owed wait became a
    // 999ms sleep and the request left half a millisecond inside DEFAULT_MIN_REQUEST_INTERVAL —
    // measured, as a 0.999339625s gap, on the first run of the concurrency test below.
    //
    // Driven through an injected sleeper rather than asserted end to end, because the shortfall is
    // 0.5ms and the concurrency test now runs at a 100ms interval allowing 20ms of slack — forty
    // times too much to see it — while tightening that allowance would flake on scheduling jitter
    // long before it caught anything. A recorder needs no wall clock and does not wait.
    List<Duration> asked = new ArrayList<>();
    Duration owed = Duration.ofMillis(999).plusNanos(500_000);

    MusicBrainzClient.sleep(owed, asked::add);

    // The whole duration, remainder and all. Thread.sleep(Duration) keeps it: JDK 25's is
    // sleepNanos(nanos) with no millisecond conversion in it, so any rounding would be this
    // client's own.
    //
    // What this says is that there is none in THIS method. It says nothing about which sleeper the
    // production path picks — sleep(Duration) delegating as `d -> Thread.sleep(d.toMillis())`
    // leaves the whole suite green, measured. That residual is one line and is recorded in
    // sleep(Duration, Sleeper)'s javadoc; claiming this assertion covered it would be the same
    // over-claim as the defect the seam was built for.
    assertThat(asked).containsExactly(owed);
  }

  @Test
  @DisplayName("a wait of nothing never reaches the sleeper at all")
  void aWaitOfNothingNeverReachesTheSleeper() {
    // reserve() returns zero for the first caller of every client, and throttleDelay returns zero
    // once the interval has passed, so this is the common path rather than an edge case.
    List<Duration> asked = new ArrayList<>();

    MusicBrainzClient.sleep(Duration.ZERO, asked::add);
    MusicBrainzClient.sleep(Duration.ofMillis(-5), asked::add);

    assertThat(asked).isEmpty();
  }

  @Test
  @DisplayName("an interrupted wait surfaces as unavailable and leaves the interrupt flag set")
  void anInterruptedWaitSurfacesAsUnavailableAndRestoresTheFlag() {
    // The third and last branch of sleep(Duration, Sleeper), and cheap only because the sleeper is
    // a parameter: interrupting a real Thread.sleep from a test would need a second thread. Both
    // halves matter. Swallowing InterruptedException without re-setting the flag is the classic way
    // to make a thread uninterruptible, and MusicBrainzUnavailableException is the one failure type
    // this client's callers are written against — an InterruptedException escaping artistRelations
    // would go straight past MusicBrainzSourceAdapter's catch and out of expand().
    assertThatThrownBy(
            () ->
                MusicBrainzClient.sleep(
                    MusicBrainzClient.DEFAULT_MIN_REQUEST_INTERVAL,
                    delay -> {
                      throw new InterruptedException("stopped mid-wait");
                    }))
        .isInstanceOf(MusicBrainzUnavailableException.class);

    // Thread.interrupted() reads the flag AND clears it, so this asserts the restoration and leaves
    // no interrupt behind for whatever JUnit runs next on this thread.
    assertThat(Thread.interrupted()).as("the interrupt flag is left set for the caller").isTrue();
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

      List<ArtistRelation> relations = retryClient(stub).artistRelations(HOT_CLUB_QUINTET);

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
      MusicBrainzClient client = retryClient(stub);

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
      MusicBrainzClient client = retryClient(stub);

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
    // With the fix, four attempts against a dead port are spaced by ~DEFAULT_MIN_REQUEST_INTERVAL
    // each (the backoff sleep between attempts is topped up to a full second by reserve()), so
    // total
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

  @Test
  @DisplayName("three callers sharing one client still leave a minimum request interval apart")
  void concurrentCallersDoNotLeaveTogether() throws InterruptedException {
    // Issue #146. One MusicBrainzClient is built in SegueConfiguration.sourceAdapters(...) and
    // held by a singleton chain to GraphTools over the servlet transport, so concurrent tool calls
    // share this object. throttle() read lastRequestAt and then slept the remainder, which is
    // check-then-act: three callers read the same value, wait the same remainder and fire
    // together. MusicBrainz's ~1 rps is a condition of anonymous ws/2 access, not a performance
    // guideline, so this is the invariant the class exists for.
    //
    // The assertion is on the SPACING between arrivals, not on total elapsed time, because
    // Thread.sleep only ever runs long: a slow machine can only push a correct implementation's
    // gaps further above the client's minRequestInterval, never below it, so it cannot turn this
    // test green for the broken code. Three callers rather than two for the same reason from the
    // other side —
    // the broken code fails every gap at once, so scheduling jitter would have to fake a full
    // second twice over to hide it.
    try (StubMusicBrainzServer stub = new StubMusicBrainzServer()) {
      MusicBrainzClient client =
          new MusicBrainzClient(stub.baseUri(), Clock.systemUTC(), Duration.ofMillis(100));
      int callers = 3;
      CountDownLatch release = new CountDownLatch(1);
      CountDownLatch finished = new CountDownLatch(callers);
      List<Exception> failures = new CopyOnWriteArrayList<>();
      ExecutorService pool = Executors.newFixedThreadPool(callers);
      try {
        for (int i = 0; i < callers; i++) {
          pool.execute(
              () -> {
                try {
                  release.await();
                  client.artistRelations(HOT_CLUB_QUINTET);
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                  failures.add(e);
                } catch (RuntimeException e) {
                  failures.add(e);
                } finally {
                  finished.countDown();
                }
              });
        }
        release.countDown();
        assertThat(finished.await(60, TimeUnit.SECONDS)).as("every caller finished").isTrue();
      } finally {
        pool.shutdownNow();
      }
      assertThat(failures).isEmpty();

      List<Duration> arrivals = stub.arrivals();
      assertThat(arrivals).hasSize(callers);
      List<Duration> gaps = new ArrayList<>();
      for (int i = 1; i < arrivals.size(); i++) {
        gaps.add(arrivals.get(i).minus(arrivals.get(i - 1)));
      }
      // allSatisfy rather than a loop of assertions: a loop stops at the first gap that fails and
      // would report one number, where the defect's signature is every gap at once.
      Duration floor = client.minRequestInterval().minus(SLOT_OVERRUN_ALLOWANCE);
      assertThat(gaps)
          .as("gaps between consecutive requests, in arrival order")
          .allSatisfy(gap -> assertThat(gap).isGreaterThanOrEqualTo(floor));
    }
  }

  /**
   * A client against {@code stub} that keeps production's request interval and asks for every wait
   * production would, but never waits for one.
   *
   * <p><b>Admissible here because none of the three tests that use it asserts a duration.</b>
   * {@code retriesRateLimit} asserts a relation count and a request count, {@code
   * givesUpEventually} an exception type and a request count, {@code
   * unparseableBodyIsReportedAsUnavailable} an exception type. Each of those outcomes is produced
   * by the same code paths whether the waits between attempts are real or not, and each was
   * re-planted with its own defect after this sleeper was injected to prove exactly that (Task 2
   * Step 4). What is <em>not</em> admissible is a test whose assertion is elapsed time: {@code
   * throttleAppliesEvenAfterAConnectionFailure} keeps a real sleeper for that reason and is now the
   * only test in this class that waits.
   *
   * <p>The interval stays {@link MusicBrainzClient#DEFAULT_MIN_REQUEST_INTERVAL} rather than being
   * shrunk alongside: with nothing waiting there is nothing to shrink, and leaving production's
   * number in place keeps these three exercising the arithmetic production runs.
   */
  private static MusicBrainzClient retryClient(StubMusicBrainzServer stub) {
    return new MusicBrainzClient(
        stub.baseUri(),
        Clock.systemUTC(),
        MusicBrainzClient.DEFAULT_MIN_REQUEST_INTERVAL,
        delay -> {});
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
