package com.robsartin.segue.wikidata;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * An in-process stand-in for the Wikidata API, on the JDK's own HttpServer.
 *
 * <p>No WireMock: its 4.x line is still beta, and this needs about sixty lines. Tests that talk to
 * a stub are deterministic and fast; the one test that talks to the real API is tagged {@code live}
 * and excluded from CI, because a recorded fixture cannot tell you the upstream API changed.
 */
public final class StubWikidataServer implements AutoCloseable {

  private final HttpServer server;
  private final Deque<String> bodies = new ArrayDeque<>();
  private final Deque<Integer> statuses = new ArrayDeque<>();
  private final Deque<Map.Entry<String, String>> headers = new ArrayDeque<>();
  private final AtomicInteger requests = new AtomicInteger();
  private final List<String> queries = Collections.synchronizedList(new ArrayList<>());
  private volatile String lastUserAgent;
  private volatile String lastQuery;

  public StubWikidataServer() {
    try {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    } catch (IOException e) {
      throw new IllegalStateException("could not start the stub server", e);
    }
    server.createContext(
        "/",
        exchange -> {
          requests.incrementAndGet();
          lastUserAgent = exchange.getRequestHeaders().getFirst("User-Agent");
          lastQuery = exchange.getRequestURI().getRawQuery();
          queries.add(lastQuery);
          int status = statuses.isEmpty() ? 200 : statuses.poll();
          byte[] body = (bodies.isEmpty() ? "{}" : bodies.poll()).getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          Map.Entry<String, String> header = headers.poll();
          if (header != null) {
            exchange.getResponseHeaders().add(header.getKey(), header.getValue());
          }
          exchange.sendResponseHeaders(status, body.length);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
          }
        });
    server.start();
  }

  public URI baseUri() {
    return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
  }

  /** Queue one response body, consumed by the next request. */
  public void enqueueBody(String json) {
    bodies.add(json);
  }

  /** Queue one response status, consumed by the next request. */
  public void enqueueStatus(int status) {
    statuses.add(status);
  }

  /** Queue one extra response header, consumed by the next request — {@code Retry-After} et al. */
  public void enqueueHeader(String name, String value) {
    headers.add(Map.entry(name, value));
  }

  public int requestCount() {
    return requests.get();
  }

  public String lastUserAgent() {
    return lastUserAgent;
  }

  /**
   * The raw, still-percent-encoded query string of the last request. Tests that care about what was
   * asked — a SPARQL query's LIMIT, the properties in its VALUES clause — assert on the decoded
   * form themselves, because decoding here would hide an encoding bug rather than expose it.
   */
  public String lastQuery() {
    return lastQuery;
  }

  /**
   * Every request's raw query string, in the order they arrived. {@link #lastQuery()} answers for a
   * caller that makes one request; this is for one that may split its work across several, where
   * the sizes and contents of each are the thing under test.
   */
  public List<String> queries() {
    return List.copyOf(queries);
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
