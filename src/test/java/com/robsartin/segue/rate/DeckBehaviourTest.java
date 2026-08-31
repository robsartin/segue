package com.robsartin.segue.rate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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

  private HttpServer server;
  private ExecutorService handlers;
  private HeadlessChrome chrome;
  private volatile Answer answer = Answer.ACCEPT;
  private final List<String> posts = Collections.synchronizedList(new ArrayList<>());

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
    server.createContext("/api/card", this::card);
    server.createContext("/api/rate", this::rate);
    server.createContext("/", exchange -> send(exchange, 200, "text/html; charset=utf-8", page));
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
            + ",\"qid\":\"Q90000"
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

  /** How many times a rating of this value reached the server. */
  private long sent(int rating) {
    synchronized (posts) {
      return posts.stream().filter(body -> body.contains("\"rating\":" + rating)).count();
    }
  }

  private String failureShown() {
    return chrome.text("problem");
  }

  /** Long enough for anything the page was going to do to have happened. */
  private void settle() {
    HeadlessChrome.sleep(600);
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
    answer = Answer.SLOW_NO_ANSWER;

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
    // below must not run while one could still be on its way.
    settle();

    List<Integer> order;
    synchronized (posts) {
      order = posts.stream().map(DeckBehaviourTest::ratingIn).toList();
    }

    // The positive control, and it is the reason this test is not vacuous: an ordering assertion
    // over a sequence with nothing to reorder passes by having had no work to do. Chrome's retry
    // is what puts a second 1 in this list, so if a future browser stops retrying, this fails
    // saying the hazard it guards no longer exists — which is a fact worth being told, not a
    // flake. Counted by value rather than by size, because the re-rating is in this list too.
    assertThat(order.stream().filter(rating -> rating == 1).count())
        .as(
            "the abandoned rating must actually have been retried, or there is nothing to order —"
                + " a failure here is that fact and not a flake, and ADR 46's 2026-08-30 amendment"
                + " says what it would mean")
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

  private static String quoted(String text) {
    return "'" + text + "'";
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
