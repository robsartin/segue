package com.robsartin.segue.rate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What the deck page <em>does</em>, run in a real browser.
 *
 * <p>{@code DeckPageTest} reads the page as text and can only ever assert that a guard is written
 * down. Mutation-testing it (issue #103) showed what that is worth: deleting {@code if
 * (!response.ok) ...} was caught, and keeping the branch while deleting its {@code return} was not
 * — and that one missing {@code return} is exactly the defect issue #101 fixed, a refused rating
 * counted as saved and the deck dealt on, with no way to withdraw it afterwards (ADR 46). A
 * token-order assertion cannot see the difference. Running the page can.
 *
 * <p>Each test here drives the real {@code deck.html} against a stub server that can refuse, stall,
 * or die mid-request, and asserts on what the owner would see: which card is on screen, how many
 * ratings this session claims, what actually reached the server. Every one of them was verified to
 * fail against the <em>defective</em> version of its guard, not merely against the guard's absence.
 *
 * <p>Skipped where no Chrome or Chromium is installed — and CI sets {@code segue.requireBrowser} so
 * that there, a missing browser fails the build instead of quietly passing. Issue #93's lesson: a
 * check that skips itself is a check that passes by not running.
 *
 * <p>Every fixture is invented (ADR 33, ADR 40): no entity here is anything the owner has rated.
 */
class DeckBehaviourTest {

  /** How the stub answers the next POST to {@code /api/rate}. */
  private enum Answer {
    /** 204, the write landed. */
    ACCEPT,
    /** 403, the write was refused — the Origin allowlist's answer to a page that may not post. */
    REFUSE,
    /**
     * Nothing at all: the handler throws, {@code com.sun.net.httpserver} closes the connection, and
     * the browser's {@code fetch} promise rejects. This is what a store raising on SQLITE_BUSY
     * looks like from the page, and it is what used to leave the keyboard permanently inert.
     */
    NO_ANSWER,
    /** 204, but slowly — long enough for a second keypress to arrive mid-flight. */
    SLOW,
    /**
     * Nothing at all, and not straight away: the handler stalls for {@code SLOW_MILLIS} and only
     * then throws. Chrome's retry of a POST whose connection died is prompt — with an immediate
     * death all of its attempts land within about four milliseconds of the keypress, too close
     * together for any ordering against a later request to be observable. Stalling each attempt
     * spreads them out far enough to see which side of the re-rating they fall on (issue #127).
     */
    SLOW_NO_ANSWER
  }

  /** Three invented cards and a rate endpoint the test can make fail on demand. */
  private static final List<String> LABELS =
      List.of("The Paper Kettles", "Wren Alderman", "Marram Press");

  /**
   * The rating the deck was dealt with, per card — the server's startup snapshot. Only the last
   * card carries one, so the page's two sources for the revision banner (what the server dealt, and
   * what this session wrote) can each be exercised on its own.
   */
  private static final List<String> DEALT_RATING = List.of("null", "null", "2");

  private static final long SLOW_MILLIS = 400;

  /**
   * What {@link #warmUp()} asks for. Small, and not empty: the page has to read it to completion
   * for the socket to go back into the pool, and a zero-length body would let a test that never
   * read anything pass that check.
   */
  private static final byte[] WARM_UP_BODY = "warm".getBytes(StandardCharsets.UTF_8);

  private HttpServer server;
  private ExecutorService handlers;
  private HeadlessChrome chrome;
  private volatile Answer answer = Answer.ACCEPT;
  private final List<String> posts = Collections.synchronizedList(new ArrayList<>());

  /** How many exchanges the stub is serving right now, and when the most recent one arrived. */
  private final AtomicInteger inFlight = new AtomicInteger();

  private final AtomicLong lastArrived = new AtomicLong(System.nanoTime());

  private final AtomicReference<String> lastPath = new AtomicReference<>("nothing yet");

  /**
   * One exchange the stub served, and the TCP port the client sent it from.
   *
   * <p>The port is the only thing that separates the two ways {@code
   * aRetriedRatingCannotOverwriteAReRating}'s positive control can go red. Chrome resends a POST
   * only on a socket already in its pool, so a POST that was never resent either arrived on a
   * socket that had carried an earlier request — and Chrome declined to resend on it, which is the
   * browser changing — or on a brand new one, which means the pool was empty and the control never
   * had its precondition. Round 2 saw the second happen for a reason no server-side counter can
   * observe: Chrome closing every socket it held, browser-wide, without an exchange to count (ADR
   * 46's 2026-09-01 amendments, which carry the measurement).
   */
  private record Served(int port, String path) {}

  /** Every exchange the stub has served, in arrival order — see {@link Served}. */
  private final List<Served> portsServed = Collections.synchronizedList(new ArrayList<>());

  @BeforeAll
  static void requireBrowser() {
    if (Boolean.getBoolean("segue.requireBrowser") && !HeadlessChrome.available()) {
      // Not a skip. CI asks for this property precisely so that the one executable check on
      // deck.html cannot report success by never having run.
      throw new AssertionError(
          "segue.requireBrowser is set and no Chrome or Chromium was found — install one, or"
              + " point -Dsegue.chrome at it");
    }
    assumeTrue(
        HeadlessChrome.available(),
        "no Chrome or Chromium on this machine, so the deck page cannot be run");
  }

  @BeforeEach
  void start() throws Exception {
    byte[] page;
    try (InputStream in = DeckBehaviourTest.class.getResourceAsStream("/rate/deck.html")) {
      page = in.readAllBytes();
    }
    server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    List<HttpContext> contexts = new ArrayList<>();
    contexts.add(server.createContext("/api/card", this::card));
    contexts.add(server.createContext("/api/rate", this::rate));
    contexts.add(
        server.createContext(
            "/warm-up",
            exchange -> send(exchange, 200, "text/plain; charset=utf-8", WARM_UP_BODY)));
    contexts.add(
        server.createContext(
            "/", exchange -> send(exchange, 200, "text/html; charset=utf-8", page)));
    for (HttpContext context : contexts) {
      context.getFilters().add(accounting());
    }
    // A real executor, because two answers here stall deliberately and the JDK's default one
    // serves every request on the single thread start() creates. On that default a stalling
    // handler blocks the whole server, so what `posts` records is the order the server got round
    // to requests rather than the order they arrived — and the order they arrive is precisely the
    // question issue #127 asks, since that is the order the affinity table would be written in.
    handlers = Executors.newCachedThreadPool();
    server.setExecutor(handlers);
    server.start();
    chrome = HeadlessChrome.launch();
    chrome.open("http://127.0.0.1:" + server.getAddress().getPort() + "/");
    chrome.until("document.querySelector('#card h1')", "the first card to be dealt");
    // A card on screen is not a loaded page, and the difference is what issue #169 was. `open`
    // waits on `readyState` too, but it does so straight after `Page.navigate`, when the document
    // it asks may still be the `about:blank` the harness started on — so this is the first point
    // at which the answer is about the deck. Chrome issues `GET /favicon.ico` off this document's
    // load, which the stub's "/" context happily answers, and nothing on the page reflects it.
    chrome.until("document.readyState === 'complete'", "the deck page itself to finish loading");
    untilQuiet();
  }

  @AfterEach
  void stop() {
    if (chrome != null) {
      chrome.close();
    }
    if (server != null) {
      server.stop(0);
    }
    if (handlers != null) {
      handlers.shutdownNow();
    }
  }

  /**
   * Counts what the stub is serving, so a test can wait for it to be serving nothing.
   *
   * <p>This is not diagnostics. {@code aRetriedRatingCannotOverwriteAReRating}'s positive control
   * depends on Chrome having a socket in its pool when the rating POST goes out, and Chrome only
   * has one when nothing else is holding it — so "the page has finished asking for things" is a
   * precondition of that test, and one this file used to assume rather than establish.
   */
  private Filter accounting() {
    return new Filter() {
      @Override
      public void doFilter(HttpExchange exchange, Chain chain) throws IOException {
        inFlight.incrementAndGet();
        lastArrived.set(System.nanoTime());
        lastPath.set(exchange.getRequestURI().getPath());
        // On entry rather than on completion, because the exchange this most needs to record is
        // the one whose handler throws — the abandoned POST, whose port is the whole question.
        portsServed.add(
            new Served(exchange.getRemoteAddress().getPort(), exchange.getRequestURI().getPath()));
        try {
          chain.doFilter(exchange);
        } finally {
          inFlight.decrementAndGet();
        }
      }

      @Override
      public String description() {
        return "counts the exchanges in flight";
      }
    };
  }

  private void card(HttpExchange exchange) throws IOException {
    String query = exchange.getRequestURI().getQuery();
    int index = query != null && query.startsWith("i=") ? Integer.parseInt(query.substring(2)) : -1;
    if (index < 0 || index >= LABELS.size()) {
      send(exchange, 404, "application/json", "{}".getBytes(StandardCharsets.UTF_8));
      return;
    }
    String body =
        "{\"index\":"
            + index
            + ",\"qid\":\"Q090000"
            + (index + 1)
            + "\",\"label\":\""
            + LABELS.get(index)
            + "\",\"kind\":\"GROUP\",\"classes\":\"invented\",\"degree\":"
            + (index + 7)
            + ",\"currentRating\":"
            + DEALT_RATING.get(index)
            + ",\"routes\":[]}";
    send(exchange, 200, "application/json", body.getBytes(StandardCharsets.UTF_8));
  }

  private void rate(HttpExchange exchange) throws IOException {
    posts.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
    switch (answer) {
      case ACCEPT -> send(exchange, 204, "application/json", new byte[0]);
      case REFUSE -> send(exchange, 403, "application/json", "{}".getBytes(StandardCharsets.UTF_8));
      case NO_ANSWER ->
          throw new IOException("the handler threw, so the connection closes with no response");
      case SLOW -> {
        HeadlessChrome.sleep(SLOW_MILLIS);
        send(exchange, 204, "application/json", new byte[0]);
      }
      case SLOW_NO_ANSWER -> {
        HeadlessChrome.sleep(SLOW_MILLIS);
        throw new IOException("the handler threw late, so the connection closes with no response");
      }
    }
  }

  private static void send(HttpExchange exchange, int status, String type, byte[] body)
      throws IOException {
    exchange.getResponseHeaders().set("Content-Type", type);
    exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
    if (body.length > 0) {
      try (OutputStream out = exchange.getResponseBody()) {
        out.write(body);
      }
    }
  }

  /** The card the owner is looking at. */
  private String cardOnScreen() {
    Object label = chrome.eval("(document.querySelector('#card h1') || {}).textContent || ''");
    return label == null ? "" : label.toString();
  }

  /** What the page claims this session has written. */
  private int ratedThisSession() {
    Matcher count = Pattern.compile("(\\d+) rated").matcher(chrome.text("progress"));
    return count.find() ? Integer.parseInt(count.group(1)) : 0;
  }

  /**
   * Puts a socket in Chrome's pool, used and idle, and returns how many bytes the page read.
   *
   * <p><strong>This creates the precondition {@link #untilQuiet()} can only infer.</strong> Chrome
   * resends an abandoned POST only on a socket already in its pool, so {@code
   * aRetriedRatingCannotOverwriteAReRating} needs one to exist at the keypress — and round 2
   * measured the way silence fails to imply that: in 225 ms of genuine quiet, Chrome closed every
   * socket it held, across every origin, in a single browser-wide flush. A flush is not an
   * exchange, so the stub's counter saw nothing and {@code untilQuiet()} returned truthfully with a
   * false conclusion (ADR 46's 2026-09-01 amendments; the resend rule itself is in {@code
   * docs/retry-precondition-evidence.md} §4).
   *
   * <p>So rather than wait for a socket and hope, the test makes one: a same-origin GET issued
   * <em>from the page</em>, whose body the page reads to the end. Draining matters — an undrained
   * response keeps the socket checked out (#188) — and so does what the caller does next, which
   * must be to press the key immediately. Every millisecond between this returning and the POST is
   * a millisecond a flush can land in; the residual is small but it is not zero, and when it
   * happens the control now says so rather than blaming the browser.
   *
   * <p>Waits on both sides: the page's promise has resolved (so the body is read and the socket
   * released) and the stub has seen the exchange end.
   */
  private int warmUp() {
    Object read =
        chrome.eval(
            "fetch('/warm-up', {cache: 'no-store'}).then(r => r.text()).then(body => body.length)");
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (inFlight.get() != 0 && System.nanoTime() < deadline) {
      HeadlessChrome.sleep(20);
    }
    if (inFlight.get() != 0) {
      throw new AssertionError(
          "the warm-up's own exchange never finished at the stub, so nothing can be said about"
              + " Chrome's pool: "
              + inFlight.get()
              + " still in flight, last "
              + lastPath.get());
    }
    if (!(read instanceof Integer bytes)) {
      throw new AssertionError("the page did not report reading the warm-up's body: " + read);
    }
    return bytes;
  }

  /** The most recent exchange the stub served. */
  private Served lastServed() {
    List<Served> served = servedSoFar();
    if (served.isEmpty()) {
      throw new AssertionError("the stub has served nothing at all");
    }
    return served.get(served.size() - 1);
  }

  /** Every exchange served so far, safe to read while the stub is still running. */
  private List<Served> servedSoFar() {
    synchronized (portsServed) {
      return List.copyOf(portsServed);
    }
  }

  /** How many times a rating of this value reached the server. */
  private long sent(int rating) {
    synchronized (posts) {
      return posts.stream().filter(body -> body.contains("\"rating\":" + rating)).count();
    }
  }

  private String failureShown() {
    return chrome.text("problem");
  }

  /**
   * Waits until neither side has anything outstanding: the page's own guards say it is idle with a
   * card on screen ({@code !busy && current !== null}), and the stub is serving nothing.
   *
   * <p><strong>The condition, and why those two halves are it.</strong> {@code rate()} sets {@code
   * busy} before it issues the POST and clears it in the {@code finally} that ends <em>that
   * fetch</em> — not the handler, which goes on to {@code problem()} and {@code show()} afterwards
   * — so what {@code busy} covers is the fetch itself, and every attempt Chrome makes inside one,
   * retries included. {@code current} covers the rest: {@code rate()} nulls it before its first
   * {@code await} and only {@code show()} sets it again, after the next card has been dealt and
   * rendered, and nothing yields between {@code rate()}'s {@code finally} and {@code show()}'s own
   * {@code busy = true}. Together they are "the page has nothing outstanding", which is a positive
   * observation rather than an interval — and {@code inFlight == 0} adds the half the page cannot
   * see, an exchange the stub has begun and not yet finished. Bounded and polled, in {@link
   * #untilSent(int)}'s shape and for its reason.
   *
   * <p><strong>The limit of this instrument: a request on the wire is in neither half.</strong> One
   * that has left Chrome and not yet reached the stub is not an unsettled {@code fetch} the page is
   * holding and not an exchange the stub has begun, so nothing here excludes it — and the two
   * halves are read a moment apart, {@code inFlight} first and the page after, as {@link
   * #untilQuiet()} reads its own two. No sleep excluded it either; a fixed 600 ms merely made the
   * window a different length. What closes it is the measurement below rather than the instrument,
   * and if that measurement ever stopped holding, nothing here would go red — the first sign would
   * be the retried-rating test's ordering assertion failing intermittently.
   *
   * <p>This used to be {@code sleep(600)}, which is the thing {@link #untilSent(int)}'s javadoc
   * argues against in this very file: after a fixed sleep, an ordering or absence assertion says
   * only "nothing had arrived <em>yet</em>", and a straggler on a loaded runner reads exactly like
   * a straggler the page correctly never sent. The evidence is what makes a condition sufficient
   * here: {@code docs/retry-precondition-evidence.md} traced 59 runs and 3 forced failures and saw
   * <em>no</em> request arrive in the 2500 ms after the assertion point, because Chrome's retries
   * all happen inside the one {@code fetch} that {@code busy} is held across. So the last attempt
   * is always spent before the page reports done — and waiting for the page to report done is
   * therefore enough, where waiting 600 ms was only long enough so far.
   */
  private void settle() {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
    while (System.nanoTime() < deadline) {
      if (inFlight.get() == 0
          && Boolean.TRUE.equals(chrome.eval("!!(!busy && current !== null)"))) {
        return;
      }
      HeadlessChrome.sleep(20);
    }
    // Both halves, for untilQuiet()'s reason: "0 in flight" alone would not say whether the page
    // is still working or the stub is, and only one of those is a hung page.
    throw new AssertionError(
        "the page never reported it had finished, so nothing here can be said to be in its final"
            + " order: busy = "
            + chrome.eval("busy")
            + ", current = "
            + chrome.eval("current === null ? 'null' : current.qid")
            + ", "
            + inFlight.get()
            + " still in flight at the stub (last "
            + lastPath.get()
            + ")");
  }

  /**
   * Waits until a rating of this value has actually reached the stub, and fails saying so if none
   * ever does.
   *
   * <p>This is what makes an <em>absence</em> assertion mean something. {@code assertThat(posts)
   * .isEmpty()} after a fixed sleep says only "nothing had arrived yet": a leaked POST that a slow
   * runner had not yet delivered reads exactly like a POST the guard correctly suppressed, and the
   * build goes green asserting a guard that is not there — the silent success issue #103 exists to
   * close, reintroduced in the test closing it.
   *
   * <p>So the tests that assert nothing was sent drive a <em>later</em> action that must send, and
   * wait here for that one to land. The page's own {@code busy} guard serialises them: a leaked
   * rating holds {@code busy} until its response is in, so the sentinel cannot even be issued until
   * the leaked POST is already recorded here, ahead of it in the list. A leak is therefore
   * impossible to mistake for lateness — either it is sitting in {@code posts} when the sentinel
   * lands, or it swallowed the sentinel and this wait fails outright. Both are red.
   *
   * <p>Callers pair this with a {@code chrome.until} on the card the sentinel deals, because the
   * stub records a POST before it answers it: this returns while the page is still waiting on the
   * response, and what is on screen has not caught up yet. That second wait is a condition too, not
   * a sleep.
   */
  private void untilSent(int rating) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
    while (System.nanoTime() < deadline) {
      if (sent(rating) > 0) {
        return;
      }
      HeadlessChrome.sleep(20);
    }
    throw new AssertionError(
        "no rating of "
            + rating
            + " ever reached the server, so nothing anchors what did not: "
            + posts);
  }

  /**
   * How long the stub must go without a new request before the page counts as loaded.
   *
   * <p>A bound on <em>issuance</em> latency, not a settle: the favicon request is Chrome's own and
   * the page says nothing about it, so there is no condition to wait on — only a length of silence
   * after which one has certainly gone out. Measured on this machine, the favicon reaches the stub
   * 6–14 ms after {@code /api/card} does, and nothing else arrives in the 1500 ms after it; over
   * the 59 traced runs in {@code docs/retry-precondition-evidence.md} the same gap was 6–20 ms,
   * under a load average between 6 and 30 on 28 cores. This is ten times that measured worst case,
   * and comfortably above {@link #untilQuiet()}'s own 20 ms polling granularity.
   *
   * <p><strong>If that bound is ever wrong, nothing goes red.</strong> A browser that issued the
   * favicon — or anything else — more than this long after load would slip past the wait and go
   * straight back to racing the POST for the pooled socket, which is a flake and not a failure: the
   * first anyone would know is the positive control failing again, intermittently, saying something
   * untrue about the browser. The ten-fold margin is the only thing holding that off, and it is why
   * a real condition would be worth having if one ever became observable.
   */
  private static final long QUIET_MILLIS = 200;

  /**
   * Waits until the stub is serving nothing and has been asked for nothing recently.
   *
   * <p>This is a precondition of {@code aRetriedRatingCannotOverwriteAReRating}, not tidiness.
   * Chrome resends a POST whose connection died only when it had a socket <em>already in its
   * pool</em> to give that attempt; a socket the pool had to connect for the request is never
   * resent on. So the number of attempts is one plus the number of pooled sockets free when the key
   * is pressed, and if an unfinished request is holding them all, the count is one and that test's
   * positive control goes red having found nothing wrong with the page (issue #169; the measurement
   * is in {@code docs/retry-precondition-evidence.md}, §6 of which forces that failure on demand,
   * and the rule it establishes is in ADR 46's 2026-09-01 amendment).
   *
   * <p>The request that used to do the holding is Chrome's {@code GET /favicon.ico}: it lands only
   * a few milliseconds before the POST, because waiting for {@code #card h1} waits for {@code
   * /api/card} and for nothing else. This waits for it, and for anything else the page or the
   * browser has started, to be finished and back in the pool.
   *
   * <p><strong>The limit of this instrument: it counts exchanges, not sockets.</strong> Chrome
   * keeps a socket checked out until the response body has been <em>read</em>, so a response the
   * page never drains leaves the socket held long after the exchange here has ended and the count
   * has gone back to zero — this wait would report quiet and be wrong, with no assertion to fire.
   * That is not hypothetical: {@code deck.html} returns on {@code !response.ok} without reading the
   * body, on both its card path and its rating path, and the stub answers 403 and 404 with a body.
   * The precondition holds today only because nothing issues such a response before this wait
   * returns — every refusal in this file is set up after {@code start()} has finished. A test that
   * made the stub refuse during load would break it silently, and the fix would be to drain in the
   * page rather than to lengthen anything here.
   *
   * <p>A condition with a deadline rather than a sleep, for {@link #untilSent(int)}'s reason: a
   * fixed sleep that turned out to be too short would go quietly back to being the same flake.
   */
  private void untilQuiet() {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
    long quiet = TimeUnit.MILLISECONDS.toNanos(QUIET_MILLIS);
    while (System.nanoTime() < deadline) {
      if (inFlight.get() == 0 && System.nanoTime() - lastArrived.get() >= quiet) {
        return;
      }
      HeadlessChrome.sleep(20);
    }
    // Both halves of the condition, because "0 still in flight" on its own explains nothing: it
    // is the other half — a request arriving every few milliseconds, so the quiet window never
    // completes — and only the elapsed time and the path say which of the two happened.
    throw new AssertionError(
        "the page never stopped asking the stub for things, so no test here can assume a socket is"
            + " free: "
            + inFlight.get()
            + " still in flight, last request "
            + (System.nanoTime() - lastArrived.get()) / 1_000_000
            + " ms ago ("
            + lastPath.get()
            + "), needing "
            + QUIET_MILLIS
            + " ms of silence");
  }

  @Test
  @DisplayName("a refused rating does not advance the deck, and says so")
  void aRefusedRatingDoesNotAdvance() {
    answer = Answer.REFUSE;

    chrome.press("1");
    chrome.until(
        "document.getElementById('problem').textContent.length > 0", "the refusal to show");
    settle();

    assertThat(posts).as("the rating was sent once").hasSize(1);
    assertThat(cardOnScreen())
        .as("a refused rating must leave the same card on screen")
        .isEqualTo(LABELS.get(0));
    assertThat(ratedThisSession())
        .as("a refused rating is not a rating this session wrote")
        .isZero();
    assertThat(failureShown()).as("the owner must be told").contains("403");
    assertThat(chrome.text("card"))
        .as("a refused rating must not be remembered as one that landed")
        .doesNotContain("Currently rated");
  }

  @Test
  @DisplayName("a rating the server never answers leaves the keyboard working")
  void anUnansweredRatingLeavesTheDeckUsable() {
    answer = Answer.NO_ANSWER;

    chrome.press("2");
    chrome.until(
        "document.getElementById('problem').textContent.length > 0", "the failure to show");
    settle();

    assertThat(sent(2)).as("the rating was attempted").isPositive();
    assertThat(cardOnScreen()).isEqualTo(LABELS.get(0));
    assertThat(ratedThisSession()).isZero();

    // The half that matters: the page must still be able to rate afterwards. A rejected promise
    // used to leave `current` null with nothing to set it back, so every later rating key did
    // nothing at all and the owner had no way to know the deck had stopped listening.
    answer = Answer.ACCEPT;
    chrome.press("3");
    chrome.until(
        "document.querySelector('#card h1').textContent !== " + quoted(LABELS.get(0)),
        "the next card after a recovered rating");

    // Counted by rating, not by size: Chrome retries a request whose connection died before any
    // response arrived, so one unanswered POST reaches the server more than once. That is the
    // browser's business and not this page's — and it is a second reason the page may not treat
    // an unanswered rating as written, since it cannot know how many of those attempts landed.
    assertThat(sent(3)).as("the rating pressed after the failure must reach the server").isOne();
    assertThat(cardOnScreen()).isEqualTo(LABELS.get(1));
    assertThat(ratedThisSession()).isOne();
  }

  @Test
  @DisplayName("one physical keypress, held down, writes exactly one rating")
  void aHeldKeyWritesOneRating() {
    // One press of '4' held for a third of a second: the first event is a real press, every
    // event after it carries Chrome's own autoRepeat flag, exactly as the operating system
    // delivers them. A finger resting on '4' used to write about fifteen ratings of 4 to
    // whatever cards went past, none of which can be withdrawn.
    chrome.press("4", false, 0);

    // The repeats begin only once the first press has been fully dealt with and the next card is
    // up. Not politeness — necessity. Fired during the first round trip they would be dropped by
    // `busy`, which has nothing to do with the guard under test, so on a runner slow enough for
    // one round trip to outlast the whole hold, a deleted `event.repeat` guard would survive.
    // Delivered here, every repeat reaches a page that is idle and holding a card, and a page
    // without the guard must post.
    chrome.until(
        "document.querySelector('#card h1').textContent === " + quoted(LABELS.get(1)),
        "the card the first press dealt, before the key is held");
    for (int i = 0; i < 10; i++) {
      HeadlessChrome.sleep(33);
      chrome.press("4", true, 0);
    }

    // The sentinel: a real press that must post. Once its POST is in, any repeat that leaked is
    // already in the list ahead of it — see untilSent. Nothing here is gated on wall-clock.
    chrome.press("5");
    untilSent(5);
    chrome.until(
        "document.querySelector('#card h1').textContent === " + quoted(LABELS.get(2)),
        "the sentinel's own rating to land and the deck to move");

    assertThat(posts).as("one press is one rating, however long it is held").hasSize(2);
    assertThat(posts.get(0)).as("the held key wrote its one rating").contains("\"rating\":4");
    assertThat(posts.get(1))
        .as("and the very next thing the server saw was the sentinel, not a repeat")
        .contains("\"rating\":5");
    assertThat(ratedThisSession()).isEqualTo(2);
    assertThat(cardOnScreen())
        .as("the hold advanced the deck once, the sentinel once more")
        .isEqualTo(LABELS.get(2));
  }

  @Test
  @DisplayName("a key pressed with a modifier rates nothing and skips nothing")
  void aModifiedKeyDoesNothing() {
    int alt = 1;
    int ctrl = 2;
    int meta = 4;

    chrome.press("4", false, ctrl);
    chrome.press("4", false, meta);
    chrome.press("4", false, alt);
    chrome.press("s", false, meta);
    chrome.press(" ", false, ctrl);

    // The sentinel: an unmodified '5', which must rate the card still on screen and advance. It
    // rates a 5 precisely so a leaked 4 is distinguishable from it in the list. Waiting for it
    // server-side is what turns "nothing has arrived" into "nothing was sent" — see untilSent.
    chrome.press("5");
    untilSent(5);
    chrome.until(
        "document.querySelector('#card h1').textContent !== " + quoted(LABELS.get(0)),
        "the sentinel's own rating to land and the deck to move");

    assertThat(posts).as("Ctrl/Cmd/Alt + a digit belongs to the browser").hasSize(1);
    assertThat(posts.get(0))
        .as("and the one thing the server saw was the sentinel")
        .contains("\"rating\":5");
    assertThat(cardOnScreen())
        .as("Cmd+S must not skip a card: the sentinel rated the FIRST card, so this is the second")
        .isEqualTo(LABELS.get(1));
    assertThat(ratedThisSession()).as("the sentinel is the only rating this session wrote").isOne();
  }

  @Test
  @DisplayName("skip pressed while a rating is in flight advances the deck by exactly one card")
  void skipDuringAnInFlightRatingAdvancesOnce() {
    answer = Answer.SLOW;

    chrome.press("3");
    HeadlessChrome.sleep(80);
    chrome.press("s");
    chrome.until(
        "document.querySelector('#card h1').textContent !== " + quoted(LABELS.get(0)),
        "the rating to land and the deck to move");
    settle();

    assertThat(posts).hasSize(1);
    assertThat(ratedThisSession()).isOne();
    assertThat(cardOnScreen())
        .as("the rating moved the deck on by one; the skip that arrived mid-flight moved nothing")
        .isEqualTo(LABELS.get(1));
  }

  @Test
  @DisplayName("back pressed while a rating is in flight advances the deck by exactly one card")
  void backDuringAnInFlightRatingIsIgnored() {
    chrome.press("s");
    chrome.until(
        "document.querySelector('#card h1').textContent === " + quoted(LABELS.get(1)),
        "the skipped-to card");

    answer = Answer.SLOW;
    chrome.press("3");
    HeadlessChrome.sleep(80);
    chrome.press("b");
    chrome.until(
        "document.querySelector('#card h1').textContent !== " + quoted(LABELS.get(1)),
        "the rating to land and the deck to move");
    settle();

    assertThat(posts).hasSize(1);
    assertThat(cardOnScreen())
        .as("back arriving mid-flight must not walk the deck backwards over a landing rating")
        .isEqualTo(LABELS.get(2));
  }

  @Test
  @DisplayName("going back shows the rating this session just wrote, not the deck's stale copy")
  void goingBackShowsWhatThisSessionWrote() {
    chrome.press("4");
    chrome.until(
        "document.querySelector('#card h1').textContent === " + quoted(LABELS.get(1)),
        "the next card");

    chrome.press("b");
    chrome.until(
        "document.querySelector('#card h1').textContent === " + quoted(LABELS.get(0)),
        "the card the owner just rated");

    // This card is dealt with currentRating null, so the only place a 4 can come from is what the
    // page itself sent — the fix issue #109 made, since the server's copy is a startup snapshot
    // and cannot know about a rating written since.
    assertThat(chrome.text("card")).contains("Currently rated 4");
  }

  @Test
  @DisplayName("a card the deck dealt with a rating already on it says so before it is re-rated")
  void aCardDealtWithARatingShowsIt() {
    chrome.press("s");
    chrome.until(
        "document.querySelector('#card h1').textContent === " + quoted(LABELS.get(1)),
        "the second card");
    assertThat(chrome.text("card"))
        .as("an unrated card must not claim a rating")
        .doesNotContain("Currently rated");

    chrome.press("s");
    chrome.until(
        "document.querySelector('#card h1').textContent === " + quoted(LABELS.get(2)),
        "the card that was dealt already rated");

    assertThat(chrome.text("card")).contains("Currently rated 2");
  }

  /** The rating one recorded body carries, so a sequence of them can be read in arrival order. */
  private static int ratingIn(String body) {
    Matcher value = Pattern.compile("\"rating\":(\\d+)").matcher(body);
    if (!value.find()) {
      throw new AssertionError("a body reached /api/rate carrying no rating at all: " + body);
    }
    return Integer.parseInt(value.group(1));
  }

  @Test
  @DisplayName("a retried rating cannot overwrite the re-rating its own failure message invites")
  void aRetriedRatingCannotOverwriteAReRating() {
    // Issue #127. The page tells the owner an unanswered rating "may not have been recorded" and
    // that nothing has advanced, which invites them to rate the same card again — and Chrome, on
    // its own account, retries a POST whose connection died before any response arrived. The
    // question is whether one of those retries can land AFTER the new rating and put the abandoned
    // value back, which nothing in segue would ever show: the write is last-writer-wins with no
    // history table and no un-rate (ADR 39, ADR 46).
    assumeTrue(
        !chrome.flushWait().markerAfterFirstSocket(),
        () ->
            "this launch's startup cert-verifier flush landed after the page had its socket, so"
                + " Chrome's pool was flushed with that socket in it and there is nothing pooled"
                + " to resend on — the precondition below is gone before the test begins, and a"
                + " green here would be the vacuous pass issue #193 is about. "
                + chrome.flushWait().order());
    answer = Answer.SLOW_NO_ANSWER;

    // And then press at once. `start()`'s wait for quiet establishes that nothing is *holding* a
    // socket; this establishes that one *exists*, which is the thing Chrome's resend actually
    // depends on and the thing silence cannot imply — see `warmUp()`. Nothing may go between the
    // two lines below: every millisecond here is a millisecond in which Chrome can flush its pool
    // and take the socket away again.
    warmUp();
    chrome.press("1");
    chrome.until(
        "document.getElementById('problem').textContent.length > 0", "the failure to show");

    // The owner takes the page at its word and gives the same card a different number — but not
    // before the page is listening again. Waiting on the page's own two guards rather than on the
    // message is the point rather than politeness: `busy` and `current` are exactly what hold the
    // re-rating back until the failed fetch has settled, and a keypress delivered while they are
    // still set would be dropped by them and prove nothing about ordering.
    answer = Answer.ACCEPT;
    chrome.until("!busy && current !== null", "the page to be ready for another rating");
    chrome.press("4");
    untilSent(4);
    chrome.until(
        "document.querySelector('#card h1').textContent === " + quoted(LABELS.get(1)),
        "the re-rating to land and the deck to move");
    // A retry arriving after everything else has finished is the whole hazard, so the assertions
    // below must not run while one could still be on its way. What holds them back is the page
    // saying it has nothing outstanding, not a length of time having passed — see settle(), and
    // issue #187 for the control that plants a late attempt and watches this go red on it.
    settle();

    List<Integer> order;
    synchronized (posts) {
      order = posts.stream().map(DeckBehaviourTest::ratingIn).toList();
    }

    // The positive control, and it is the reason this test is not vacuous: an ordering assertion
    // over a sequence with nothing to reorder passes by having had no work to do. Chrome's retry
    // is what puts a second 1 in this list, so if a future browser stops retrying, this fails
    // saying the hazard it guards no longer exists. Counted by value rather than by size, because
    // the re-rating is in this list too.
    //
    // What this control needs from the fixture, and what issue #169 was: Chrome resends only on a
    // socket already in its pool, so it resends nothing if there is no such socket when the key is
    // pressed. Two different things used to take it away. One is another request holding it —
    // Chrome's favicon, which got there first seven times, and which `start()`'s `untilQuiet` now
    // waits out. The other is Chrome closing the socket for reasons of its own, which produces no
    // exchange and which no length of silence excludes; `warmUp()` above answers that one by
    // making a socket instead of waiting for one.
    //
    // Neither is perfect, so this control says which case a red is rather than asserting the
    // browser changed — see `whyNoRetryHappened`.
    assertThat(order.stream().filter(rating -> rating == 1).count())
        .as(
            "the abandoned rating must actually have been retried, or there is nothing to order —"
                + " %s",
            whyNoRetryHappened())
        .isGreaterThan(1);

    // The finding. Every attempt at the abandoned rating reached the server before the re-rating
    // did, and not by luck: Chrome's retries all happen inside the one fetch, and `busy` and
    // `current` both stay held until that fetch settles — so the page cannot even issue the
    // re-rating until the last retry is already spent.
    assertThat(order.lastIndexOf(1))
        .as("every retry of the abandoned rating must reach the server before the re-rating")
        .isLessThan(order.indexOf(4));
    assertThat(order)
        .as("so the value left standing in the affinity table is the one the owner meant")
        .endsWith(4);
    assertThat(ratedThisSession())
        .as("and only the re-rating counts as written: the unanswered one never could be")
        .isOne();
  }

  /**
   * Why {@code aRetriedRatingCannotOverwriteAReRating}'s positive control just went red, in the two
   * cases that are worth telling apart.
   *
   * <p>A red means Chrome did not resend an abandoned POST, and by itself that reads as "the
   * browser stopped retrying" — the finding ADR 46's 2026-09-01 amendment asks to be told about. It
   * is usually not. Chrome resends only on a socket already in its pool, so the same red also
   * appears whenever the pool was empty at the keypress, and round 2 measured Chrome emptying it
   * browser-wide in a single millisecond for reasons of its own (ADR 46's 2026-09-01 amendments).
   * Before {@link #warmUp()} that was common; after it, it is a few milliseconds' window rather
   * than a couple of hundred, but it is not gone.
   *
   * <p>The client port separates them, which is why the stub records one for every exchange. A POST
   * on a port that had already served a request was given a pooled socket and not resent on it —
   * the browser changing, and that half is observed.
   *
   * <p>The other half is an observation and an inference, and the message keeps them apart. What is
   * observed is that the POST went out on a port never seen before, so the pool had to connect a
   * socket for it and there was nothing pooled to resend on. <em>Why</em> the pool was empty is not
   * observed here: a flush is the only cause ever seen, but a socket held open by something else
   * looks exactly the same from this side — the controls for this classifier produced the
   * first-seen port by holding sockets, with nothing flushed at all. Either way the red is
   * environmental and says nothing about the page or the browser; a NetLog is what tells them
   * apart.
   */
  private String whyNoRetryHappened() {
    List<Served> served = servedSoFar();
    int post = -1;
    for (int i = 0; i < served.size(); i++) {
      if ("/api/rate".equals(served.get(i).path())) {
        post = i;
        break;
      }
    }
    if (post < 0) {
      return "no rating reached the stub at all, so there was never anything to resend: " + served;
    }
    int port = served.get(post).port();
    List<String> alreadyServed =
        served.subList(0, post).stream()
            .filter(earlier -> earlier.port() == port)
            .map(Served::path)
            .toList();
    if (alreadyServed.isEmpty()) {
      return "the POST arrived on a fresh connection (port "
          + port
          + ", never seen before this), so Chrome had no pooled socket to resend on. The one cause"
          + " ever observed is Chrome's network-change pool flush (ADR 46's 2026-09-01"
          + " amendments; docs/retry-pool-flush-evidence.md); a socket held open by something"
          + " else would look identical from here. Environmental, not the browser ceasing to"
          + " resend — rerun, and if it repeats, capture a NetLog";
    }
    return "Chrome was bound to a pooled socket (port "
        + port
        + ", which served "
        + String.join(", ", alreadyServed)
        + ") and still did not resend: this browser no longer retries a POST whose connection"
        + " died, which ADR 46's 2026-09-01 amendment says how to read";
  }

  private static String quoted(String text) {
    return "'" + text + "'";
  }

  /**
   * What {@link #warmUp()} can be held to, run for run.
   *
   * <p><strong>Not asserted here: that a used socket is idle in Chrome's pool afterwards.</strong>
   * That is the property the warm-up exists for, and it is not observable from this side. Which
   * pooled socket Chrome hands a request — the one the page used, or the preconnect spare it opened
   * alongside and never used — is Chrome's choice, and both are pooled, so no port this stub
   * records can be equal to anything run after run. Two attempts to assert it that way flaked — the
   * first at about one run in ten, the second at one in sixty — which is the one outcome this round
   * cannot ship: a spurious red introduced to remove a spurious red.
   *
   * <p>It is established instead where it can be. The Loop C and D controls demonstrate it — with
   * round 1's occupancy probe holding every pooled socket, the retry control fails without this
   * warm-up and passes with it — and {@code aRetriedRatingCannotOverwriteAReRating} enforces it on
   * every run, since Chrome's resend is exactly the observable that depends on it.
   *
   * <p>What is left here is what the stub genuinely sees, and it is worth having: each call puts
   * exactly one exchange through, that exchange finishes, and the page read the whole body back.
   */
  @Test
  @DisplayName("each warm-up puts exactly one drained exchange through the stub, and finishes it")
  void shouldServeOneCompletedExchangeWhenTheWarmUpRuns() {
    int before = servedSoFar().size();

    int bodyRead = warmUp();

    assertThat(servedSoFar())
        .as("one exchange per call — a warm-up that reached no socket at all is not a warm-up")
        .hasSize(before + 1);
    assertThat(lastServed().path()).isEqualTo("/warm-up");
    assertThat(bodyRead)
        .as(
            "the page must read the warm-up's body to completion — an undrained response keeps the"
                + " socket checked out (#188), which is the opposite of what this is for")
        .isEqualTo(WARM_UP_BODY.length);
    assertThat(inFlight.get())
        .as("and the stub has finished with it, so the socket is idle rather than checked out")
        .isZero();

    // Again, because "exactly one" is a claim about every call and not just the first: a warm-up
    // that Chrome served from its cache, or that opened two connections, would show up here.
    int second = warmUp();

    assertThat(servedSoFar()).as("and one more for the second call").hasSize(before + 2);
    assertThat(lastServed().path()).isEqualTo("/warm-up");
    assertThat(second).isEqualTo(WARM_UP_BODY.length);
    assertThat(inFlight.get()).isZero();
  }

  @Test
  @DisplayName("the stub records the client port of every request it serves, in arrival order")
  void shouldRecordTheClientPortWhenAnExchangeIsServed() {
    List<Served> exchanges = servedSoFar();

    assertThat(exchanges).as("loading the deck must have been recorded").isNotEmpty();
    assertThat(exchanges.get(0).path())
        .as("recorded in arrival order, so the page itself comes first")
        .isEqualTo("/");
    Served cardFetch =
        exchanges.stream()
            .filter(served -> "/api/card".equals(served.path()))
            .findFirst()
            .orElseThrow(
                () ->
                    new AssertionError(
                        "the deck's own card fetch was never recorded: " + exchanges));
    assertThat(cardFetch.port())
        .as("a real client port, which is what tells a reused socket from a fresh one")
        .isGreaterThan(0);
  }

  @Test
  @DisplayName("the page's own script is running in strict mode")
  void thePageRunsInStrictMode() {
    // Issue #154. Renaming only the `let busy = false;` declaration left every one of the ten
    // tests above green: in sloppy mode the remaining `busy = ...` assignments simply created a
    // global, and the page went on working with the real binding stale. This page writes the
    // affinity table, which has no history and no un-rate (ADR 39, ADR 46), so a lost declaration
    // must fail loudly rather than as silence.
    //
    // Asserted at runtime rather than by looking for the directive as text, because the token
    // `'use strict'` can be present and inert — inside a comment, or after a statement, where it
    // is no longer a directive. What is checked here is the property that actually matters: the
    // page's functions are strict ones. `Function.prototype.caller` is an accessor that throws
    // TypeError, and only a non-strict function has an own `caller` shadowing it (returning
    // null), so reading `.caller` off a function the page declared separates the two cases. Any
    // top-level function declaration would do; `skip` is used because it is one of the guards the
    // lost declaration would have disarmed.
    //
    // What this does NOT cover: it says the script is strict, not that any particular typo is
    // caught. The mutation that found this — renaming the declaration and watching the suite go
    // red — cannot live in the suite, and was run by hand; see the pull request for #154.
    Object strict =
        chrome.eval(
            "(function () { try { skip.caller; return false; }"
                + " catch (thrown) { return thrown instanceof TypeError; } })()");
    assertThat(strict)
        .as(
            "deck.html must run in strict mode, so that an assignment to an undeclared identifier"
                + " is a ReferenceError instead of a silent global")
        .isEqualTo(Boolean.TRUE);
  }

  @Test
  @DisplayName("this suite is not silently doing nothing")
  void theBrowserWasActuallyDriven() {
    assertThat(chrome.eval("navigator.userAgent").toString())
        .as("a positive control: the page really is running in a browser")
        .containsIgnoringCase("chrome");
  }

  @Test
  @DisplayName(
      "an expression that throws in the page fails, rather than reading as an empty answer")
  void aJavaScriptErrorIsNotAnEmptyAnswer() {
    // The second positive control, and it is aimed at the harness rather than the page. An
    // expression that throws comes back from DevTools with a value of nothing at all, so without
    // the exceptionDetails check this returns "" — and every isZero, isEmpty and doesNotContain
    // in this file would go green on a question the browser never answered. That is the same
    // silent success as a POST that was merely late, one layer further down.
    assertThatThrownBy(
            () -> chrome.eval("document.getElementById('no-element-has-this-id').textContent"))
        .as("a JavaScript error must reach the test, and must say what it was")
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("threw")
        .hasMessageContaining("no-element-has-this-id");
  }
}
