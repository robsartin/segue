package com.robsartin.segue.rate;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The wait {@link HeadlessChrome#open} makes before it navigates, and the bound it gives up on.
 *
 * <p>No browser is launched here, and that is the point. The wait's two outcomes are a condition
 * that arrives late and a condition that never arrives, and neither can be produced on demand by a
 * real Chrome on this machine — every launch measured for {@code docs/loopback-only-evidence.md}
 * wrote the marker, 80 of 80, and wrote it before the harness got round to looking. So the NetLog
 * is planted rather than captured: one the marker reaches <em>after the wait has begun</em>, so
 * that blocking for it is the only way to see it, and one it never reaches, which is what a browser
 * that has stopped firing it would leave behind.
 *
 * <p><b>The bound is not a timeout.</b> A Chrome that never creates its certificate verifier is the
 * good outcome — there is no flush to wait out — so the wait ends by <em>proceeding</em>, and says
 * which of the two ended it. Failing there would turn good news into a red gate.
 */
class HeadlessChromeFlushWaitTest {

  @TempDir private Path scratch;

  /** Chrome's shape: constants first, then one event per line, and no closing bracket yet. */
  private static final String STREAMING_HEAD =
      """
      {"constants":{"logEventTypes":{\
      "QUIC_SESSION_POOL_MARK_ALL_ACTIVE_SESSIONS_GOING_AWAY":311,\
      "CERT_VERIFY_PROC_CREATED":812,"TCP_CONNECT":91},\
      "logSourceType":{"SOCKET":42}},
      "events": [
      {"type":91,"source":{"id":4,"type":42},"params":{"address_list":["127.0.0.1:8080"]}},
      """;

  private static final String GOING_AWAY_LINE =
      "{\"type\":311,\"source\":{\"id\":7,\"type\":42}},\n";

  /**
   * How long the marker is withheld. The assertion below is deliberately 100 ms under it: the
   * writer's own clock starts at {@link Thread#start()} and the wait's starts a moment later, so an
   * exact comparison could flake on thread scheduling, while 500 ms is already two orders of
   * magnitude past the single poll a wait that did not block would cost.
   */
  private static final long MARKER_DELAY_MILLIS = 600;

  @Test
  @DisplayName("the wait blocks until the marker arrives, rather than looking once and giving up")
  void shouldBlockUntilTheMarkerReachesTheNetLog() throws Exception {
    Path netLog = Files.writeString(scratch.resolve("net-log.json"), STREAMING_HEAD);
    Thread writer =
        new Thread(
            () -> {
              try {
                Thread.sleep(MARKER_DELAY_MILLIS);
                Files.writeString(netLog, GOING_AWAY_LINE, StandardOpenOption.APPEND);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              } catch (IOException e) {
                throw new UncheckedIOException(e);
              }
            });
    writer.setDaemon(true);
    // Touch the class before the clock starts. Its static initialiser builds a Jackson mapper,
    // which cost 122 ms on the first call here and came off the measured wait — a warm-up artefact
    // reading as a wait that did not block.
    HeadlessChrome.available();

    writer.start();
    HeadlessChrome.FlushWait wait = HeadlessChrome.awaitFlush(netLog, System.nanoTime(), 5000);
    writer.join();

    assertThat(wait.sawMarker())
        .as("the marker did arrive, well inside the bound, so the wait ended on the condition")
        .isTrue();
    assertThat(wait.waitedMillis())
        .as(
            "the marker was not in the log when the wait began and arrived %s ms later. A wait that"
                + " read the file once and returned would answer in a single poll and be just as"
                + " green as one that blocks — which is the whole difference between an ordering"
                + " that is enforced and an ordering that happened to hold",
            MARKER_DELAY_MILLIS)
        .isGreaterThanOrEqualTo(500L);
  }

  @Test
  @DisplayName("the wait proceeds on its labelled bound when no marker ever arrives")
  void shouldProceedOnTheBoundWhenTheMarkerNeverReachesTheNetLog() throws IOException {
    Path netLog = Files.writeString(scratch.resolve("net-log.json"), STREAMING_HEAD);

    long started = System.nanoTime();
    HeadlessChrome.FlushWait wait = HeadlessChrome.awaitFlush(netLog, started, 200);
    long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

    assertThat(wait.sawMarker()).as("no marker was ever written to this log").isFalse();
    assertThat(elapsed)
        .as("the bound is a deadline, and it is honoured")
        .isGreaterThanOrEqualTo(200L);
    assertThat(wait.toString())
        .as(
            "the run has to be able to say which of the two ended the wait — a launch that"
                + " proceeded on the bound is not a launch that observed the flush, and the two"
                + " must not read alike in the output")
        .contains("bound")
        .doesNotContain("marker passed");
  }
}
