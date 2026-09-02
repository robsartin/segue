package com.robsartin.segue.rate;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
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
 * that arrives and a condition that never does, and only one of them can be produced by a real
 * Chrome on this machine — every launch measured for {@code docs/loopback-only-evidence.md} wrote
 * the marker, 80 of 80. So the NetLog is planted rather than captured: a log the marker reaches,
 * and a log it never reaches, which is what a browser that has stopped firing it would leave
 * behind.
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

  @Test
  @DisplayName("the wait ends on the flush marker, not on the clock")
  void shouldEndOnTheConditionWhenTheMarkerReachesTheNetLog() throws IOException {
    Path netLog = Files.writeString(scratch.resolve("net-log.json"), STREAMING_HEAD);
    Files.writeString(netLog, GOING_AWAY_LINE, StandardOpenOption.APPEND);

    HeadlessChrome.FlushWait wait = HeadlessChrome.awaitFlush(netLog, System.nanoTime(), 5000);

    assertThat(wait.sawMarker())
        .as(
            "the marker is in the log, so the wait has its condition and must not sit out the bound")
        .isTrue();
    assertThat(wait.waitedMillis())
        .as("a condition already met costs one poll at most, not the bound")
        .isLessThan(5000L);
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
