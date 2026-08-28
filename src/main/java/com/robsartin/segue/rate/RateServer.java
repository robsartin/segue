package com.robsartin.segue.rate;

import com.robsartin.segue.domain.AffinityRecord;
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

  /**
   * The loopback hostnames a browser is allowed to say it came from.
   *
   * <p>Matched against {@link URI#getHost()}, never against the raw header text: an earlier version
   * of this check used {@code String.startsWith}, and {@code "http://127.0.0.1.evil.com"} starts
   * with {@code "http://127.0.0.1"} as a string while naming a completely different host. Parsing
   * the header as a URI and comparing hosts exactly closes that.
   *
   * <p>The literal string {@code "null"} is deliberately NOT in this set. A browser sends it as the
   * {@code Origin} of a sandboxed iframe or a {@code data:} navigation — exactly the shape an
   * attacker controls to manufacture an origin of their choosing, which makes it the opposite of an
   * allowlist entry. An absent header (no entry in the request's header map at all) is handled
   * separately below and is fine: curl and a real MCP-style client send none, and a browser fetch()
   * call always sends one, so that branch never protects a browser request.
   */
  private static final Set<String> ALLOWED_ORIGIN_HOSTS = Set.of("127.0.0.1", "localhost", "::1");

  private final List<Card> deck;
  private final AffinityStore affinity;
  private final int requestedPort;
  private HttpServer server;

  public RateServer(List<Card> deck, AffinityStore affinity, int requestedPort) {
    this.deck = List.copyOf(Objects.requireNonNull(deck, "deck"));
    this.affinity = Objects.requireNonNull(affinity, "affinity");
    this.requestedPort = requestedPort;
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

  private void page(HttpExchange exchange) throws IOException {
    try (InputStream in = RateServer.class.getResourceAsStream("/rate/deck.html")) {
      if (in == null) {
        throw new IllegalStateException("deck.html is missing from the jar");
      }
      send(exchange, 200, "text/html; charset=utf-8", in.readAllBytes());
    }
  }

  private void card(HttpExchange exchange) throws IOException {
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
    String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    try {
      String qid = field(body, "qid");
      int rating = Integer.parseInt(field(body, "rating"));
      // Let AffinityRecord do the range check: one definition of the scale, in the type that
      // carries it. Its message names no value, which is deliberate (ADR 33).
      affinity.put(new AffinityRecord(qid, rating, null, Instant.now()));
    } catch (IllegalArgumentException e) {
      send(exchange, 400, "application/json", EMPTY_JSON);
      return;
    }
    send(exchange, 204, "application/json", new byte[0]);
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
        + ",\"routes\":"
        + routes
        + "}";
  }

  private static String escape(String raw) {
    return raw.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ");
  }

  private static String field(String body, String name) {
    int at = body.indexOf('"' + name + '"');
    if (at < 0) {
      throw new IllegalArgumentException("missing field");
    }
    int colon = body.indexOf(':', at);
    String rest = body.substring(colon + 1).trim();
    if (rest.startsWith("\"")) {
      return rest.substring(1, rest.indexOf('"', 1));
    }
    int end = 0;
    while (end < rest.length()
        && (Character.isDigit(rest.charAt(end)) || rest.charAt(end) == '-')) {
      end++;
    }
    if (end == 0) {
      throw new IllegalArgumentException("unparseable field");
    }
    return rest.substring(0, end);
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
