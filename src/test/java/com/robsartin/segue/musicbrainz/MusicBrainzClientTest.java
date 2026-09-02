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
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
   * The slot spacing {@link #concurrentCallersDoNotLeaveTogether} builds its client with — five
   * minutes, and deliberately absurd.
   *
   * <p>Nothing waits for it. That test injects a sleeper that records the wait it was asked for and
   * returns, so the interval costs no wall-clock time at all and there is no reason to keep it
   * small. What a large one buys is that the property being asserted cannot be reached by
   * scheduling: {@code reserve} issues slot <i>n</i> at {@code previous + minRequestInterval}
   * exactly, <b>provided</b> the caller reaches {@code reserve} within one interval of the slot
   * before it. Any stall long enough to break that would first have to blow the test's own
   * 60-second liveness assertion, five times over. So the exact-equality assertion below cannot
   * flake on a loaded machine; it can only fail if {@code reserve}'s arithmetic is wrong.
   *
   * <p><b>This replaces a 20ms allowance on a 100ms interval, and the replacement is the point.</b>
   * The allowance existed because the old assertion compared <em>arrival</em> spacing at a stub
   * server against a floor derived from <em>departure</em> spacing, and had to absorb per-request
   * send latency. Task 1's review measured that latency at 40–60ms on a cold JVM against a 20ms
   * allowance — noise larger than the signal — and the correct client accordingly passed 0 of 5
   * isolated runs and 5 of 5 inside the warm class, at load average 125–152. Widening or narrowing
   * an allowance cannot fix an assertion whose noise term is bigger than what it measures;
   * asserting the claim instead of its consequence can.
   */
  private static final Duration CLAIM_INTERVAL = Duration.ofMinutes(5);

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
    // Driven through an injected sleeper rather than asserted end to end, because a 0.5ms
    // shortfall is not observable in anything this class measures: the concurrency test asserts the
    // slot the client claimed rather than a wall clock, and the one test that does time itself,
    // throttleAppliesEvenAfterAConnectionFailure, separates three seconds from one and a half. A
    // recorder needs no wall clock and does not wait.
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
    // THIS TEST IS THE ONE THAT STILL WAITS, and deliberately: it keeps the default one-second
    // interval and a real sleeper, because its assertion is elapsed time. Every other test in this
    // class that reaches MusicBrainzClient.sleep now passes a sleeper that returns at once, so this
    // is the only end-to-end verification left that the throttle's waiting is real at all — and
    // shrinking either seam here would collapse the ~3s it asserts onto the ~1.4s of backoff that
    // is the defect's own number, making the criterion vacuous (see the spec's note on
    // criterion re-validation).
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
  @DisplayName("three concurrent callers sharing one client are each issued their own slot")
  void concurrentCallersDoNotLeaveTogether() throws Exception {
    // Issue #146. One MusicBrainzClient is built in SegueConfiguration.sourceAdapters(...) and
    // held by a singleton chain to GraphTools over the servlet transport, so concurrent tool calls
    // share this object. throttle() read lastRequestAt and then slept the remainder, which is
    // check-then-act: three callers read the same value, wait the same remainder and fire
    // together. MusicBrainz's ~1 rps is a condition of anonymous ws/2 access, not a performance
    // guideline, so this is the invariant the class exists for.
    //
    // WHAT IS ASSERTED: the three slots the client CLAIMED — the instant each caller was told to
    // wait until, recovered from the clock reading reserve() computed against plus the wait it
    // asked its sleeper for. reserve() is exact arithmetic, so those three instants are exactly
    // CLAIM_INTERVAL apart from each other and from the slot the warm-up call claimed, to the
    // nanosecond. There is no allowance, no floor and no tolerance in the assertion.
    //
    // WHY NOT THE WALL CLOCK: this test used to time arrivals at the stub server and require each
    // gap to clear the interval less 20ms. Arrival spacing is not departure spacing — the send
    // latency between them was measured at 40–60ms on a cold JVM and about 1ms on a warm one — so
    // the allowance was absorbing a term an order of magnitude larger than the arithmetic error it
    // was meant to catch, and the correct client failed 0/5 alone and passed 5/5 in the class for
    // reasons belonging to the JIT (Task 1 review, section (c)). What the client owns is the claim;
    // the JVM owns everything between the claim and the socket. This asserts the client's half and
    // asserts it exactly. See CLAIM_INTERVAL for why no stall can reach it.
    //
    // The real-time property that remains — that the three callers really are concurrent, which is
    // what makes this a test of check-then-act rather than of three sequential calls — is asserted
    // with a barrier, not a timing window: no caller passes it until all three have reached it.
    try (StubMusicBrainzServer stub = new StubMusicBrainzServer()) {
      SlotRecorder recorder = new SlotRecorder();
      MusicBrainzClient client =
          new MusicBrainzClient(stub.baseUri(), recorder, CLAIM_INTERVAL, recorder);

      // One call first, on this thread, so that every concurrent caller below has to compute its
      // slot from a claim that already exists — the exact situation #146 got wrong. The first
      // caller of a fresh client is owed nothing and so never reaches the sleeper; without this,
      // one of the three would record no slot at all and only two gaps could be asserted.
      client.artistRelations(HOT_CLUB_QUINTET);
      Instant firstSlot = recorder.lastInstantReadOnThisThread();

      int callers = 3;
      CyclicBarrier allRunning = new CyclicBarrier(callers);
      CountDownLatch finished = new CountDownLatch(callers);
      List<Exception> failures = new CopyOnWriteArrayList<>();
      ExecutorService pool = Executors.newFixedThreadPool(callers);
      try {
        for (int i = 0; i < callers; i++) {
          pool.execute(
              () -> {
                try {
                  allRunning.await(30, TimeUnit.SECONDS);
                  client.artistRelations(HOT_CLUB_QUINTET);
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                  failures.add(e);
                } catch (BrokenBarrierException | TimeoutException e) {
                  failures.add(e);
                } catch (RuntimeException e) {
                  failures.add(e);
                } finally {
                  finished.countDown();
                }
              });
        }
        // The bound the exactness argument leans on: every caller's clock reading happens inside
        // this window, which is a twelfth of one CLAIM_INTERVAL.
        assertThat(finished.await(60, TimeUnit.SECONDS)).as("every caller finished").isTrue();
      } finally {
        pool.shutdownNow();
      }
      assertThat(failures).isEmpty();

      // Four requests, so no attempt was retried — every wait the recorder saw is a slot claim
      // rather than a retry backoff, which is what lets the slots below be read off it directly.
      assertThat(stub.requestCount()).isEqualTo(callers + 1);

      // In any order: which thread wins which slot is the compare-and-set's business and is not a
      // property. That there are three distinct slots, each one interval further on than the last,
      // is. Under the #146 defect all three callers compute against the same claim and this
      // collapses to three copies of one instant, measured 5/5 red.
      assertThat(recorder.slots())
          .as("the departure slots the client issued to three concurrent callers")
          .containsExactlyInAnyOrder(
              firstSlot.plus(CLAIM_INTERVAL),
              firstSlot.plus(CLAIM_INTERVAL.multipliedBy(2)),
              firstSlot.plus(CLAIM_INTERVAL.multipliedBy(3)));
    }
  }

  /**
   * The clock the client reads and the sleeper it waits through, in one object, so that what {@link
   * MusicBrainzClient#reserve} claimed can be read back exactly.
   *
   * <p>{@code reserve} computes {@code sendAt = now.plus(delay)} from a clock reading it does not
   * expose and returns only the {@code delay}. Recording the reading per thread and adding the
   * delay to it at the moment the wait is asked for reconstructs {@code sendAt} to the nanosecond —
   * the same arithmetic, not an approximation of it. Per thread because three callers read this
   * clock concurrently; the last reading a thread took is the one its successful claim used, since
   * a compare-and-set that loses re-reads before trying again.
   *
   * <p>The clock is a real one. A fixed clock would change what {@code reserve} means rather than
   * freeze it (see {@code MusicBrainzClient(URI, Clock)}'s javadoc), and nothing here needs time to
   * stand still — only to be observed.
   */
  private static final class SlotRecorder extends Clock implements MusicBrainzClient.Sleeper {

    private final Clock delegate = Clock.systemUTC();
    private final ThreadLocal<Instant> lastRead = new ThreadLocal<>();
    private final List<Instant> slots = new CopyOnWriteArrayList<>();

    @Override
    public Instant instant() {
      Instant now = delegate.instant();
      lastRead.set(now);
      return now;
    }

    @Override
    public ZoneId getZone() {
      return delegate.getZone();
    }

    @Override
    public Clock withZone(ZoneId zone) {
      // Nothing in MusicBrainzClient calls this; a client only ever asks for instant().
      return delegate.withZone(zone);
    }

    @Override
    public void sleep(Duration delay) {
      slots.add(lastRead.get().plus(delay));
    }

    Instant lastInstantReadOnThisThread() {
      return lastRead.get();
    }

    List<Instant> slots() {
      return List.copyOf(slots);
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
