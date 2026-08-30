package com.robsartin.segue.rate;

import com.robsartin.segue.domain.Qid;
import com.robsartin.segue.domain.RatingScale;
import com.robsartin.segue.port.AffinityStore;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The deck, over loopback HTTP.
 *
 * <p><b>Loopback and an Origin allowlist, which is ADR 28's argument used a second time.</b>
 * Binding to {@code 127.0.0.1} stops another machine reaching this; it does not stop a hostile page
 * open in the owner's own browser from posting here, which is what DNS rebinding exploits. This
 * endpoint writes the one table in segue that cannot be regenerated, so both halves are needed.
 *
 * <p><b>No rating reaches a log line</b> (ADR 33). The logs here carry the bound port and counts.
 */
public final class RateServer {

  private static final Logger log = LoggerFactory.getLogger(RateServer.class);

  private static final byte[] EMPTY_JSON = "{}".getBytes(StandardCharsets.UTF_8);

  private static final byte[] PAGE_MISSING =
      "the deck page is missing from this build".getBytes(StandardCharsets.UTF_8);

  /**
   * The loopback hostnames a browser is allowed to say it came from.
   *
   * <p>Matched against {@link URI#getHost()}, never against the raw header text: an earlier version
   * of this check used {@code String.startsWith}, and {@code "http://127.0.0.1.evil.com"} starts
   * with {@code "http://127.0.0.1"} as a string while naming a completely different host. Parsing
   * the header as a URI and comparing hosts exactly closes that.
   *
   * <p><b>The IPv6 loopback is spelled {@code [::1]}, brackets included, because that is what
   * {@link URI#getHost()} returns for an IPv6 literal</b> — {@code URI.create("http://[::1]:8090")
   * .getHost()} is {@code "[::1]"}, not {@code "::1"}. This set carried the bare form until issue
   * #101's final review, which made the entry dead: an owner who opened the deck at {@code
   * http://[::1]:8090} was refused by a rule that claimed to allow them, and both this Javadoc and
   * ADR 46 said otherwise.
   *
   * <p>The literal string {@code "null"} is deliberately NOT in this set. A browser sends it as the
   * {@code Origin} of a sandboxed iframe or a {@code data:} navigation — exactly the shape an
   * attacker controls to manufacture an origin of their choosing, which makes it the opposite of an
   * allowlist entry. An absent header (no entry in the request's header map at all) is handled
   * separately below and is fine: curl and a real MCP-style client send none, and a browser fetch()
   * call always sends one, so that branch never protects a browser request.
   */
  private static final Set<String> ALLOWED_ORIGIN_HOSTS = Set.of("127.0.0.1", "localhost", "[::1]");

  /** Where the page lives in the jar. A field only so the absent-resource branch can be driven. */
  static final String PAGE_RESOURCE = "/rate/deck.html";

  private final List<Card> deck;
  private final AffinityStore affinity;
  private final int requestedPort;
  private final String pageResource;
  private HttpServer server;

  public RateServer(List<Card> deck, AffinityStore affinity, int requestedPort) {
    this(deck, affinity, requestedPort, PAGE_RESOURCE);
  }

  /**
   * The same server, told where to find its page.
   *
   * <p>Package-private and for one caller: {@code RateServerTest} pointing at a resource that is
   * not there, so the missing-page branch answers over a real socket rather than being reasoned
   * about. A resource absent from the jar is a build accident, but the handler's behaviour when it
   * happens is not — throwing out of a {@code com.sun.net.httpserver} handler closes the connection
   * with no response, which reads to the owner as a browser that will not load rather than as an
   * error.
   */
  RateServer(List<Card> deck, AffinityStore affinity, int requestedPort, String pageResource) {
    this.deck = List.copyOf(Objects.requireNonNull(deck, "deck"));
    this.affinity = Objects.requireNonNull(affinity, "affinity");
    this.requestedPort = requestedPort;
    this.pageResource = Objects.requireNonNull(pageResource, "pageResource");
  }

  public void start() throws IOException {
    server =
        HttpServer.create(
            new InetSocketAddress(InetAddress.getLoopbackAddress(), requestedPort), 0);
    server.createContext("/api/card", this::card);
    server.createContext("/api/rate", this::rate);
    server.createContext("/", this::page);
    server.start();
    log.info("rating deck on http://127.0.0.1:{} with {} card(s)", port(), deck.size());
  }

  public int port() {
    return server.getAddress().getPort();
  }

  public void stop() {
    if (server != null) {
      server.stop(0);
    }
  }

  /**
   * The page, or a 500 saying why not — never an exception out of the handler.
   *
   * <p>This used to throw {@link IllegalStateException} when the resource was absent. Throwing out
   * of a {@code com.sun.net.httpserver} handler closes the connection with no response at all, so
   * the owner saw a browser that would not load rather than a server that had something to say.
   * Same treatment as a malformed body on {@code /api/rate}: refuse, and refusing means answering.
   */
  private void page(HttpExchange exchange) throws IOException {
    try (InputStream in = RateServer.class.getResourceAsStream(pageResource)) {
      if (in == null) {
        log.error("{} is missing from the classpath — the deck cannot be served", pageResource);
        send(exchange, 500, "text/plain; charset=utf-8", PAGE_MISSING);
        return;
      }
      send(exchange, 200, "text/html; charset=utf-8", in.readAllBytes());
    }
  }

  private void card(HttpExchange exchange) throws IOException {
    // Checked here as well as on /api/rate (issue #109 review). Only the write path used to carry
    // the allowlist, on the reasoning that a hostile page could at worst learn whether a qid was
    // on the known-list. That stopped being the worst case when the card body grew currentRating:
    // under the DNS-rebinding scenario this class's own javadoc names as the reason the allowlist
    // exists, a hostile page could read the owner's actual ratings, one index at a time.
    if (!originAllowed(exchange)) {
      send(exchange, 403, "application/json", EMPTY_JSON);
      return;
    }
    if (!methodAllowed(exchange, "GET")) {
      return;
    }
    int index = indexFrom(exchange.getRequestURI().getQuery());
    if (index < 0 || index >= deck.size()) {
      send(exchange, 404, "application/json", EMPTY_JSON);
      return;
    }
    send(
        exchange,
        200,
        "application/json",
        json(deck.get(index), index).getBytes(StandardCharsets.UTF_8));
  }

  private void rate(HttpExchange exchange) throws IOException {
    if (!originAllowed(exchange)) {
      send(exchange, 403, "application/json", EMPTY_JSON);
      return;
    }
    if (!methodAllowed(exchange, "POST")) {
      return;
    }
    String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    try {
      String qid = stringField(body, "qid");
      int rating = intField(body, "rating");
      // Qid and RatingScale, not AffinityRecord: one definition of each rule, in classes that
      // carry no rating of their own. RatingScale's message names no value, which is deliberate
      // (ADR 33). Both checks also run inside updateRating, which is where the contract holds them
      // for every caller; these two refuse an untrusted body at the boundary that parsed it.
      Qid.check(qid);
      RatingScale.check(rating);
      // updateRating, never put — in BOTH modes, and this handler could not tell them apart if it
      // wanted to (it holds a List<Card> and no flag). put writes the whole row, and the deck can
      // never have a note to write: theRatingDeckNeverReadsANote bans every class here from
      // reading one. Through put, re-rating a --revise card wrote note = null over a note only the
      // owner could ever restore. Through updateRating the note column is never mentioned, and the
      // default mode — where the row is absent and gets its first rating — is served by the same
      // call, because an inserted row has no note for anything to preserve.
      affinity.updateRating(qid, rating, Instant.now());
    } catch (IllegalArgumentException e) {
      send(exchange, 400, "application/json", EMPTY_JSON);
      return;
    }
    send(exchange, 204, "application/json", new byte[0]);
  }

  /**
   * One verb per route, and a 405 that names it — answered here rather than by the caller.
   *
   * <p>{@code GET /api/rate} used to fall through to the body parser, find no {@code qid} in an
   * empty body and answer 400 (issue #107). Adequate, and the wrong thing to say: nothing was
   * malformed, there was no body to malform. Worse on the other route — {@code POST /api/card}
   * dealt a card, answering 200 through a verb that has no business reading one.
   *
   * <p>The refusal carries {@code Allow}, which RFC 9110 requires of every 405, and it is checked
   * AFTER the Origin allowlist: a foreign origin is refused whatever verb it arrives on, and a 405
   * would otherwise tell it which one to try instead.
   *
   * @return true when the handler should carry on; false when this method has already answered
   */
  private static boolean methodAllowed(HttpExchange exchange, String allowed) throws IOException {
    if (allowed.equals(exchange.getRequestMethod())) {
      return true;
    }
    exchange.getResponseHeaders().set("Allow", allowed);
    send(exchange, 405, "application/json", EMPTY_JSON);
    return false;
  }

  private boolean originAllowed(HttpExchange exchange) {
    List<String> origins = exchange.getRequestHeaders().get("Origin");
    if (origins == null || origins.isEmpty()) {
      return true;
    }
    try {
      String host = URI.create(origins.get(0)).getHost();
      return host != null && ALLOWED_ORIGIN_HOSTS.contains(host);
    } catch (IllegalArgumentException malformed) {
      return false;
    }
  }

  private static int indexFrom(String query) {
    if (query == null || !query.startsWith("i=")) {
      return -1;
    }
    try {
      return Integer.parseInt(query.substring(2));
    } catch (NumberFormatException e) {
      return -1;
    }
  }

  /** Minimal, because the payload is four fields and a dependency for that would be silly. */
  private static String json(Card card, int index) {
    StringBuilder routes = new StringBuilder("[");
    for (int i = 0; i < card.routes().size(); i++) {
      routes.append(i == 0 ? "" : ",").append('"').append(escape(card.routes().get(i))).append('"');
    }
    routes.append(']');
    return "{\"index\":"
        + index
        + ",\"qid\":\""
        + escape(card.qid())
        + "\""
        + ",\"label\":\""
        + escape(card.label())
        + "\""
        + ",\"kind\":\""
        + card.kind()
        + "\""
        + ",\"classes\":\""
        + escape(card.classes())
        + "\""
        + ",\"degree\":"
        + (card.degree().isPresent() ? card.degree().getAsInt() : "null")
        + ",\"currentRating\":"
        + (card.currentRating().isPresent() ? card.currentRating().getAsInt() : "null")
        + ",\"routes\":"
        + routes
        + "}";
  }

  /**
   * A minimal JSON string escaper (this hand-rolled encoder has no library backing it — see {@link
   * #json}), fixed after it shipped incomplete: it turned every '\n' into a literal space rather
   * than a JSON escape, silently flattening the line-per-hop structure a candidate card's routes
   * depend on ({@code PathResult.render()}), and left every other control character — tabs,
   * carriage returns, anything below U+0020 — completely unescaped, which is not "ugly JSON" but
   * invalid JSON: a real parser refuses it (confirmed live and pinned by {@code
   * RateServerTest.escapesControlCharactersInJson}, which reads the server's own response back
   * through Jackson).
   *
   * <p>A single left-to-right pass, not a chain of {@code String.replace} calls: the backslash case
   * only ever sees a bare {@code \\} from the ORIGINAL text, because by the time this method would
   * revisit that position it has already moved past it — there is no second pass over the escapes
   * just written to accidentally double-escape.
   */
  private static String escape(String raw) {
    StringBuilder out = new StringBuilder(raw.length());
    for (int i = 0; i < raw.length(); i++) {
      char c = raw.charAt(i);
      switch (c) {
        case '\\' -> out.append("\\\\");
        case '"' -> out.append("\\\"");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        default -> {
          if (c < 0x20) {
            out.append(String.format("\\u%04x", (int) c));
          } else {
            out.append(c);
          }
        }
      }
    }
    return out.toString();
  }

  /**
   * The value of one field out of a body this class parses by hand, still as text.
   *
   * <p><b>Every</b> way of failing here and in its two callers is an {@link
   * IllegalArgumentException} — which is the type {@link #rate} catches and turns into a 400.
   *
   * <p>Two ways it used to fail differently, both found by issue #101's final review and both
   * reaching the affinity table, the one thing in segue with no source to regenerate it from:
   *
   * <ul>
   *   <li><b>An unterminated string threw the wrong type.</b> {@code {"qid":"Q900001} } left the
   *       scan for the closing quote at -1, and {@code String.substring(1, -1)} raises {@code
   *       StringIndexOutOfBoundsException}, which is NOT an {@code IllegalArgumentException}. The
   *       catch missed it, the handler threw, and the connection closed with no response at all. A
   *       missing colon had the same shape.
   *   <li><b>A non-integer was silently truncated.</b> {@code {"rating":4.7} } stopped the digit
   *       scan at the {@code .} and stored <b>4</b>, answering 204 as though the owner had said so.
   *       A fabricated rating is worse than a refusal, so a number this parser cannot represent
   *       exactly is refused: after the digits, the next character must actually end the value.
   * </ul>
   */
  private static String valueOf(String body, String name) {
    int at = body.indexOf('"' + name + '"');
    if (at < 0) {
      throw new IllegalArgumentException("missing field");
    }
    int colon = body.indexOf(':', at);
    if (colon < 0) {
      throw new IllegalArgumentException("field has no value");
    }
    return body.substring(colon + 1).trim();
  }

  /**
   * A field the body must give as a JSON string, which is what {@code qid} is.
   *
   * <p>Split out of one lenient {@code field} method by issue #107, together with {@link
   * #intField}. Reading both shapes through one method meant {@code {"rating":"4"} } — a JSON
   * string where a number belongs — parsed as 4 and stored. The page sends neither form, so this
   * was looseness rather than a defect; but the parser is hand-rolled and writes the one table that
   * cannot be regenerated, so a body no correct client produces is now refused outright.
   */
  private static String stringField(String body, String name) {
    String rest = valueOf(body, name);
    if (!rest.startsWith("\"")) {
      throw new IllegalArgumentException("field is not a string");
    }
    int close = rest.indexOf('"', 1);
    if (close < 0) {
      throw new IllegalArgumentException("unterminated string");
    }
    return rest.substring(1, close);
  }

  /**
   * A field the body must give as a JSON integer, which is what {@code rating} is.
   *
   * <p>Refuses three shapes that all used to arrive as a number (the first two by issue #107):
   *
   * <ul>
   *   <li>the quoted form, {@code {"rating":"4"} } — see {@link #stringField};
   *   <li>a leading zero, {@code {"rating":04} }, which is not a number JSON has at all: a real
   *       parser refuses it, and so does this one rather than guessing that 4 was meant;
   *   <li>anything the digit scan cannot represent exactly, {@code {"rating":4.7} } — after the
   *       digits the next character must actually end the value.
   * </ul>
   *
   * <p>{@code Integer.parseInt} has the last word, and its {@code NumberFormatException} is an
   * {@link IllegalArgumentException}, so an out-of-range or otherwise malformed run of digits lands
   * on the same 400 as everything else here.
   */
  private static int intField(String body, String name) {
    String rest = valueOf(body, name);
    if (rest.startsWith("\"")) {
      throw new IllegalArgumentException("field is a string, not a number");
    }
    int end = 0;
    while (end < rest.length()
        && (Character.isDigit(rest.charAt(end)) || rest.charAt(end) == '-')) {
      end++;
    }
    if (end == 0) {
      throw new IllegalArgumentException("unparseable field");
    }
    if (end < rest.length() && !endsAValue(rest.charAt(end))) {
      throw new IllegalArgumentException("not a whole number");
    }
    String digits = rest.substring(0, end);
    String magnitude = digits.startsWith("-") ? digits.substring(1) : digits;
    if (magnitude.length() > 1 && magnitude.charAt(0) == '0') {
      throw new IllegalArgumentException("a leading zero is not a JSON number");
    }
    return Integer.parseInt(digits);
  }

  /** What may legitimately follow a number in the one-object bodies this endpoint accepts. */
  private static boolean endsAValue(char c) {
    return c == ',' || c == '}' || c == ']' || Character.isWhitespace(c);
  }

  private static void send(HttpExchange exchange, int status, String type, byte[] body)
      throws IOException {
    exchange.getResponseHeaders().set("Content-Type", type);
    exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
    if (body.length > 0) {
      try (var out = exchange.getResponseBody()) {
        out.write(body);
      }
    }
  }
}
