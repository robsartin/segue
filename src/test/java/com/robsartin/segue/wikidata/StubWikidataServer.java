package com.robsartin.segue.wikidata;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
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
  private final AtomicInteger requests = new AtomicInteger();
  private volatile String lastUserAgent;

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
          int status = statuses.isEmpty() ? 200 : statuses.poll();
          byte[] body = (bodies.isEmpty() ? "{}" : bodies.poll()).getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
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

  public int requestCount() {
    return requests.get();
  }

  public String lastUserAgent() {
    return lastUserAgent;
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
