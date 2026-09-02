package com.robsartin.segue.rate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The harness's browser reaches nothing but loopback — asserted, not commented.
 *
 * <p>{@link HeadlessChrome} used to carry a comment saying "nothing here should reach the network".
 * It was false on every launch. The NetLogs captured for issue #169 — quoted in {@code
 * docs/retry-pool-flush-evidence.md} §4 — show Chrome resolving and connecting to {@code
 * clients2.google.com}, {@code accounts.google.com}, {@code www.google.com} and {@code gstatic.com}
 * despite {@code --disable-background-networking}, and §5 shows why that is more than untidiness:
 * when that background work settles, Chrome closes <em>every</em> socket it holds in one
 * browser-wide flush, including the loopback sockets {@code DeckBehaviourTest} depends on. This
 * test is issue #186's answer — the claim becomes a check that fails when it stops being true.
 *
 * <p><b>What it asserts, precisely, and what it does not.</b> Two assertions, because "reaches
 * nothing but loopback" turned out to be two different facts:
 *
 * <ol>
 *   <li><b>Nothing is reached.</b> No TCP connect, no TLS or QUIC handshake, no byte in either
 *       direction, to any host but {@value #LOOPBACK}. This is a zero, it is what the resolver rule
 *       buys, and it is the one the positive control fails: drop {@code --host-resolver-rules} from
 *       {@code HeadlessChrome.flags} and this goes red naming {@code www.google.com} and Google's
 *       addresses.
 *   <li><b>Attempts are inventoried.</b> Chrome still asks its resolver for three of its own hosts,
 *       and is refused. That is not a zero and this test does not pretend it is: the hosts named
 *       must be a subset of {@link #KNOWN_ATTEMPTS}, which lists each one, what it was asking for,
 *       and that it is unfinished work. A phone-home nobody has accounted for fails here.
 * </ol>
 *
 * <p>Issue #186's spec asked for one assertion — no request, resolution <em>or</em> socket, all
 * zero — on the expectation that the flags Puppeteer launches with would suppress the attempts.
 * Measured one at a time on Chrome 152.0.7977.65, <b>not one of them removed anything</b>. Rather
 * than assert something false or quietly drop the clause, the claim is split so the part that holds
 * is a zero and the part that does not is a named list. {@link NetLog.Kind} carries the
 * resolution/request/socket distinction the failure messages report.
 *
 * <p><b>The instrument is checked before it is trusted.</b> A NetLog that recorded nothing would
 * pass an "is empty" assertion perfectly, so this also asserts that the log contains the loopback
 * traffic the test knows it caused. {@link NetLog} throws rather than returning empty on a log it
 * cannot parse, for the same reason.
 *
 * <p>Skipped where no Chrome is installed, and failed rather than skipped where {@code
 * segue.requireBrowser} is set — the same rule as {@code DeckBehaviourTest}, and for the same
 * reason: a check that skips itself is a check that passes by not running.
 *
 * <p>Every fixture is invented (ADR 33, ADR 40): the one card the stub deals is nothing the owner
 * has rated.
 */
class HeadlessChromeNetworkTest {

  /** The only host the browser may name. The stub is bound here and the page is loaded by IP. */
  private static final String LOOPBACK = "127.0.0.1";

  private static final byte[] WARM_UP_BODY = "warm".getBytes(StandardCharsets.UTF_8);

  private static final String CARD =
      "{\"index\":0,\"qid\":\"Q0900001\",\"label\":\"The Paper Kettles\",\"kind\":\"GROUP\","
          + "\"classes\":\"invented\",\"degree\":7,\"currentRating\":null,\"routes\":[]}";

  @TempDir private Path scratch;

  private HttpServer server;
  private ExecutorService handlers;

  @BeforeAll
  static void requireBrowser() {
    if (Boolean.getBoolean("segue.requireBrowser") && !HeadlessChrome.available()) {
      throw new AssertionError(
          "segue.requireBrowser is set and no Chrome or Chromium was found — install one, or"
              + " point -Dsegue.chrome at it");
    }
    assumeTrue(
        HeadlessChrome.available(),
        "no Chrome or Chromium on this machine, so its network posture cannot be measured");
  }

  /**
   * The same stub {@code DeckBehaviourTest} drives, reduced to one card.
   *
   * <p>The real page, not a placeholder: what Chrome fetches is part of what is being measured, and
   * a blank document would exercise none of the deck's own requests.
   */
  @BeforeEach
  void start() throws Exception {
    byte[] page;
    try (InputStream in = HeadlessChromeNetworkTest.class.getResourceAsStream("/rate/deck.html")) {
      page = in.readAllBytes();
    }
    server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext("/api/card", this::card);
    server.createContext(
        "/warm-up", exchange -> send(exchange, 200, "text/plain; charset=utf-8", WARM_UP_BODY));
    server.createContext("/", exchange -> send(exchange, 200, "text/html; charset=utf-8", page));
    handlers = Executors.newCachedThreadPool();
    server.setExecutor(handlers);
    server.start();
  }

  @AfterEach
  void stop() {
    if (server != null) {
      server.stop(0);
    }
    if (handlers != null) {
      handlers.shutdownNow();
    }
  }

  /**
   * {@link #PHONE_HOME_CONTROL} is the part of {@link #KNOWN_ATTEMPTS} the instrument control
   * relies on. If a flag ever removes one of those hosts, both lists have to move together, and
   * until this test existed only a javadoc said so. No browser is launched here; it is a check on
   * the two constants.
   */
  @Test
  @DisplayName("the instrument control's hosts are inside the allowlist it guards")
  void shouldKeepTheControlHostsInsideTheAllowlistWhenTheListsAreCompared() {
    assertThat(KNOWN_ATTEMPTS)
        .as(
            "PHONE_HOME_CONTROL must be a subset of KNOWN_ATTEMPTS: a host the control expects to"
                + " see is one the allowlist must admit, or the guard contradicts itself")
        .containsAll(PHONE_HOME_CONTROL);
  }

  @Test
  @DisplayName(
      "the browser reaches no host but loopback, and asks only for the phone-homes on record")
  void shouldContactOnlyLoopbackWhenTheDeckPageIsDriven() {
    Path netLog = scratch.resolve("chrome-net-log.json");
    String origin = "http://" + LOOPBACK + ":" + server.getAddress().getPort() + "/";

    try (HeadlessChrome chrome = HeadlessChrome.launch(netLog)) {
      chrome.open(origin);
      chrome.until("document.querySelector('#card h1')", "the first card to be dealt");
      chrome.until("document.readyState === 'complete'", "the deck page itself to finish loading");
      // One warm-up, exactly as the deck test issues before its keypress: it is the request that
      // puts a used socket back in Chrome's pool, and it is the last thing the page does.
      chrome.eval(
          "fetch('/warm-up', {cache: 'no-store'}).then(r => r.text()).then(body => body.length)");
    }

    List<NetLog.Sighting> sightings = NetLog.sightings(netLog);
    Set<String> hosts = NetLog.hostsContacted(netLog);

    // The instrument first. An empty or unreadable log would satisfy the real assertion below
    // without having observed anything, and this suite has been caught by a dead instrument
    // before (docs/retry-precondition-evidence.md).
    assertThat(hosts)
        .as("the NetLog at %s should contain the loopback traffic this test just caused", netLog)
        .contains(LOOPBACK);
    assertThat(kinds(sightings, LOOPBACK))
        .as("the NetLog should show both the page's requests and its sockets, or it saw too little")
        .contains(NetLog.Kind.REQUEST, NetLog.Kind.SOCKET);
    requireTheParserStillSeesChromesAttempts(sightings);

    // The guarantee. Nothing leaves this machine for a non-loopback host: no TCP connect, no TLS
    // or QUIC handshake, no byte sent or received. This is the assertion the resolver rule makes
    // true and the one the positive control makes fail.
    assertThat(lines(sightings, sighting -> reachedTheNetwork(sighting.event())))
        .as(
            "HeadlessChrome must open no socket, complete no handshake and carry no byte to any"
                + " host other than %s — the whole point of the --host-resolver-rules line in"
                + " HeadlessChrome.flags (issue #186; docs/retry-pool-flush-evidence.md §5)",
            LOOPBACK)
        .isEmpty();

    // The attempts. What is left is Chrome asking for names it will never get, and this is an
    // inventory rather than a zero: see KNOWN_ATTEMPTS. A phone-home this list does not name —
    // a new one, or an old one returning — fails here.
    assertThat(offLoopbackHosts(sightings))
        .as(
            "HeadlessChrome must make no DNS resolution and no URL request to any host other than"
                + " %s, beyond the attempts measured and named in KNOWN_ATTEMPTS. A host here that"
                + " is not in that list is a phone-home nobody has accounted for",
            LOOPBACK)
        .isSubsetOf(KNOWN_ATTEMPTS);
  }

  /**
   * Hosts Chrome still <em>asks</em> for, which the resolver rule then refuses.
   *
   * <p><b>This list is a debt, not a design.</b> The spec for issue #186 expected the flags
   * Puppeteer launches with to remove these attempts; measured one at a time against the NetLog on
   * Chrome 152.0.7977.65, <em>not one of them removed anything</em> (the flags tried are listed in
   * {@code HeadlessChrome.flags}). Two flags outside that set did, and they are the two this
   * harness keeps.
   *
   * <p>Re-derived from this parser, over six runs of this very test, after the RFC 2396 hole that
   * had been hiding {@code ~notfound} was fixed. Every entry below appeared in <b>6 of 6</b> runs:
   *
   * <ul>
   *   <li>{@code accounts.google.com} — 30 sightings; {@code /ListAccounts}, the account
   *       reconcilor's cookie-jar read, on a profile with no account in it
   *   <li>{@code www.google.com} — 30 sightings; {@code /async/folae}, the omnibox's AI-mode
   *       eligibility fetch
   *   <li>{@code android.clients.google.com} — {@code /checkin}, GCM's device check-in. <b>0 of
   *       6</b> in this test's own runs, and listed anyway: it is seen from browsers left running
   *       longer than this test keeps one, and under load this test's browser may live that long.
   *       The asymmetry decides it — with {@code isSubsetOf}, an over-listed host is silent while
   *       an under-listed one reds the gate, so a residual that <em>appears</em> is a flake and one
   *       that <em>disappears</em> is not
   *   <li>{@code ~notfound} — 61 sightings, all {@code HOST_RESOLVER_MANAGER_REQUEST}. Not a host:
   *       Chrome logs the name the resolver rule <em>mapped it to</em>, so this entry is the rule
   *       working rather than anything escaping. It is kept because the parser reports it in the
   *       host position, which is where Chrome put it
   *   <li>{@code 2001:4860:4860::8888} — 12 sightings, all {@code UDP_CONNECT} / {@code
   *       SOCKET_CONNECT} on port <b>443</b>. Chromium's hardcoded IPv6 reachability probe: a UDP
   *       {@code connect()} that asks the kernel for a route. Read off a captured NetLog, the
   *       socket's entire life is {@code SOCKET_OPEN}, {@code UDP_CONNECT}, {@code
   *       UDP_LOCAL_ADDRESS}, {@code SOCKET_ALIVE} end — <b>no byte or packet event of any
   *       kind</b>. Not Secure DNS: the same log records {@code doh_config: {servers: []}} and
   *       {@code can_use_secure_dns_transactions: false}, and never names {@code dns.google}.
   *       {@code --disable-features=DnsOverHttpsUpgrade}, {@code DnsOverHttps} and {@code
   *       --dns-over-https-mode=off} were each measured against it and removed nothing
   * </ul>
   *
   * <p>Each entry is stopped at DNS, so none of them reaches anything; that is the assertion above,
   * and it is the one that matters. <b>The check is {@code isSubsetOf}, so this list can only
   * over-list.</b> An attempt that stops happening will never announce itself here, and a stale
   * entry silently weakens the guard by the width of one host — so it is re-derived when the
   * browser changes, not trimmed on a hunch. Shortening it for real is work left undone.
   */
  private static final List<String> KNOWN_ATTEMPTS =
      List.of(
          "accounts.google.com",
          "www.google.com",
          "android.clients.google.com",
          "~notfound",
          "2001:4860:4860::8888");

  /**
   * Whether an event means the browser actually got onto the network, rather than merely asked.
   *
   * <p>A {@code QUIC_SESSION_POOL_JOB} is a job that wanted a session and never got one; a {@code
   * URL_REQUEST_START_JOB} is a request that died at DNS. Neither is on this list. A TCP connect, a
   * TLS or QUIC handshake, and any byte in either direction are.
   */
  private static boolean reachedTheNetwork(String event) {
    return event.contains("TCP_CONNECT")
        || event.contains("BYTES_SENT")
        || event.contains("BYTES_RECEIVED")
        || event.contains("PACKET_SENT")
        || event.contains("PACKET_RECEIVED")
        || event.startsWith("SSL_")
        || "QUIC_SESSION".equals(event);
  }

  /**
   * The subset of {@link #KNOWN_ATTEMPTS} used as a live control on the instrument. Both were asked
   * for in 6 of 6 re-derivation runs.
   */
  private static final List<String> PHONE_HOME_CONTROL =
      List.of("accounts.google.com", "www.google.com");

  /**
   * A live control on the instrument: the parser must still see Chrome asking for its own hosts.
   *
   * <p>The two assertions this guard rests on are both about <em>absence</em>, and absence is what
   * a broken instrument produces for free. {@code NetLogTest} pins the parser against a fixture
   * transcribed from a real capture, but a fixture cannot notice a future Chrome that keeps its
   * event <em>names</em> and changes its parameter <em>shapes</em> — that Chrome would sail through
   * {@code NetLogTest} while every non-loopback sighting quietly vanished here, and the guard would
   * report a browser that reaches nothing because it had stopped being able to see.
   *
   * <p>So this asserts the one thing the harness knows to be true and cannot fake: Chrome phones
   * home, is refused at DNS, and the parser sees it. Both hosts in {@link #PHONE_HOME_CONTROL} were
   * asked for in 6 of 6 re-derivation runs; the control needs either.
   *
   * <p>Package-private and static so {@code NetLogTest} can plant a NetLog with the phone-home
   * events stripped and watch this fail. A control never seen to fail is not a control.
   */
  static void requireTheParserStillSeesChromesAttempts(List<NetLog.Sighting> sightings) {
    assertThat(hostsAskedFor(sightings))
        .as(
            "the parser no longer sees Chrome's own attempts — re-derive KNOWN_ATTEMPTS against a"
                + " fresh NetLog before trusting the zero this test reports. Every run so far has"
                + " shown at least one of %s asked for and refused; a run showing none means either"
                + " this NetLog's shape moved under the parser or Chrome stopped phoning home, and"
                + " until that is settled an empty non-loopback set is a blind instrument rather"
                + " than a quiet browser",
            PHONE_HOME_CONTROL)
        .containsAnyElementsOf(PHONE_HOME_CONTROL);
  }

  /** Non-loopback hosts Chrome named to its resolver or asked for by URL, however it went. */
  private static List<String> hostsAskedFor(List<NetLog.Sighting> sightings) {
    return sightings.stream()
        .filter(
            sighting ->
                sighting.kind() == NetLog.Kind.RESOLUTION || sighting.kind() == NetLog.Kind.REQUEST)
        .map(NetLog.Sighting::host)
        .filter(host -> !LOOPBACK.equals(host))
        .distinct()
        .sorted()
        .toList();
  }

  /** Every non-loopback sighting matching a predicate, as one readable line each. */
  private static List<String> lines(
      List<NetLog.Sighting> sightings, java.util.function.Predicate<NetLog.Sighting> matching) {
    return sightings.stream()
        .filter(sighting -> !LOOPBACK.equals(sighting.host()))
        .filter(matching)
        .map(NetLog.Sighting::toString)
        .distinct()
        .sorted()
        .toList();
  }

  /** Every non-loopback host the log names, however it names it. */
  private static List<String> offLoopbackHosts(List<NetLog.Sighting> sightings) {
    return sightings.stream()
        .map(NetLog.Sighting::host)
        .filter(host -> !LOOPBACK.equals(host))
        .distinct()
        .sorted()
        .toList();
  }

  private static Set<NetLog.Kind> kinds(List<NetLog.Sighting> sightings, String host) {
    return sightings.stream()
        .filter(sighting -> host.equals(sighting.host()))
        .map(NetLog.Sighting::kind)
        .collect(Collectors.toCollection(() -> EnumSet.noneOf(NetLog.Kind.class)));
  }

  private void card(HttpExchange exchange) throws IOException {
    String query = exchange.getRequestURI().getQuery();
    boolean first = "i=0".equals(query);
    send(
        exchange,
        first ? 200 : 404,
        "application/json",
        (first ? CARD : "{}").getBytes(StandardCharsets.UTF_8));
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
}
