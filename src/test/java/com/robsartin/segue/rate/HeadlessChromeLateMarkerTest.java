package com.robsartin.segue.rate;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URISyntaxException;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What {@link HeadlessChrome#open} records once it has navigated: whether Chrome's startup
 * cert-verifier flush marker arrived <em>after</em> the page's first socket.
 *
 * <p><b>Why this is recorded and not failed on.</b> The wait before {@code Page.navigate} ends on a
 * bound, not a timeout, so a slow enough launch navigates without having seen the marker. If the
 * marker then lands while the page already holds a socket, the flush takes that socket — and every
 * test whose precondition is a pooled socket has quietly lost it, while staying green. Reddening
 * would red on machine speed, which is the trap the bound exists to avoid. So the launch records
 * it, and a test that needs the precondition skips with the reason (issue #193).
 *
 * <p><b>Fixtures, not a browser.</b> The late case cannot be produced on demand by a real Chrome
 * here: 80 launches of 80 wrote the marker, and wrote it before the harness got round to looking
 * ({@code docs/loopback-only-evidence.md} §6). So each branch is driven from a NetLog tail trimmed
 * out of a real capture — how, and out of which one, is in {@code
 * src/test/resources/rate/netlog/README.md}. The positions asserted below are that README's table.
 */
class HeadlessChromeLateMarkerTest {

  /**
   * The state a launch is in when this question is worth asking: the wait before {@code
   * Page.navigate} ended on the bound with no marker in sight.
   */
  private static final HeadlessChrome.FlushWait BOUND_FIRED =
      new HeadlessChrome.FlushWait(
          false,
          HeadlessChrome.FLUSH_BOUND_MILLIS,
          HeadlessChrome.FLUSH_BOUND_MILLIS,
          HeadlessChrome.MarkerOrder.NOT_OBSERVED);

  /**
   * Short, because every fixture is a file that is already complete. The bound only decides how
   * long a tail that will never show the marker is waited on, and no test here wants to wait.
   */
  private static final long BOUND_MILLIS = 100;

  @Test
  @DisplayName("the launch reports the marker landing after the page's first socket")
  void shouldReportTheMarkerAfterTheFirstSocketWhenTheTailSaysSo() {
    NetLog.Tail tail = new NetLog.Tail(fixture("marker-after-first-socket.json"));

    HeadlessChrome.FlushWait observed =
        HeadlessChrome.observeMarkerOrder(BOUND_FIRED, tail, BOUND_MILLIS);

    assertThat(observed.markerAfterFirstSocket())
        .as(
            "this tail has the page's socket at event 4 and the flush marker at event 7, which is"
                + " the whole hazard: the pool was flushed with the page's socket in it. A launch"
                + " that cannot say so is a launch that passes vacuously — %s",
            observed.order())
        .isTrue();
    assertThat(observed.order().firstSocketPosition())
        .as("the reason has to name where the socket was, or it cannot be checked against the log")
        .isEqualTo(4);
    assertThat(observed.order().markerPosition()).as("and where the marker was").isEqualTo(7);
  }

  @Test
  @DisplayName("a marker that arrived before the page's first socket is not reported as late")
  void shouldNotReportTheMarkerAsLateWhenItArrivedBeforeTheFirstSocket() {
    NetLog.Tail tail = new NetLog.Tail(fixture("marker-before-first-socket.json"));

    HeadlessChrome.FlushWait observed =
        HeadlessChrome.observeMarkerOrder(BOUND_FIRED, tail, BOUND_MILLIS);

    assertThat(observed.markerAfterFirstSocket())
        .as(
            "the flush finished at event 2 and the page's socket was made at event 5, so the pool"
                + " this launch flushed was empty of anything the page owned — the ordering the"
                + " wait exists to produce, arriving a moment later than usual. Reporting this as"
                + " late would skip a test that had its precondition — %s",
            observed.order())
        .isFalse();
    assertThat(observed.order().markerPosition()).isEqualTo(2);
    assertThat(observed.order().firstSocketPosition()).isEqualTo(5);
  }

  @Test
  @DisplayName("a tail that never shows the marker leaves the existing not-seen answer standing")
  void shouldLeaveTheNotSeenAnswerStandingWhenNoMarkerEverArrives() {
    NetLog.Tail tail = new NetLog.Tail(fixture("no-marker.json"));

    HeadlessChrome.FlushWait observed =
        HeadlessChrome.observeMarkerOrder(BOUND_FIRED, tail, BOUND_MILLIS);

    assertThat(observed.markerAfterFirstSocket())
        .as(
            "a browser that never creates its certificate verifier has no flush to be late — the"
                + " outcome this whole line of work wants, and the one thing this must not turn"
                + " into a skip — %s",
            observed.order())
        .isFalse();
    assertThat(observed.order().markerPosition())
        .as("no marker in this tail, which is not the same fact as a marker at event 0")
        .isZero();
    assertThat(observed.order().firstSocketPosition())
        .as("the page still made its socket, so the tail was read and did see events")
        .isEqualTo(4);
    assertThat(observed.sawMarker())
        .as("and the wait's own answer is untouched: this launch proceeded on the bound")
        .isFalse();
  }

  /** A fixture tail on disk, which is what {@link NetLog.Tail} reads. */
  private static Path fixture(String name) {
    try {
      return Path.of(
          HeadlessChromeLateMarkerTest.class.getResource("/rate/netlog/" + name).toURI());
    } catch (URISyntaxException notAFile) {
      throw new IllegalStateException("could not resolve the NetLog fixture " + name, notAFile);
    }
  }
}
