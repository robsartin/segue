package com.robsartin.segue.musicbrainz;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * An in-process stand-in for MusicBrainz's {@code ws/2}, on the JDK's own HttpServer.
 *
 * <p>Deliberately a near-copy of {@code wikidata.StubWikidataServer} rather than a shared test
 * utility: ADR 32 keeps adapters siblings, and a shared cross-package test helper would be a small
 * crack in that for tests to widen later. Sixty lines is cheap to duplicate once.
 */
final class StubMusicBrainzServer implements AutoCloseable {

  private final HttpServer server;
  private final Deque<String> bodies = new ArrayDeque<>();
  private final Deque<Integer> statuses = new ArrayDeque<>();
  private final AtomicInteger requests = new AtomicInteger();

  /**
   * When each request arrived, measured as elapsed time since this stub was built rather than read
   * off a wall clock. {@link #arrivals} is read by a test that asserts a <i>spacing</i>, and {@code
   * System.nanoTime} is documented as monotonic where {@code Instant.now()} is not — a clock
   * correction landing mid-test could otherwise make two arrivals look closer together than they
   * were.
   */
  private final long startedAtNanos = System.nanoTime();

  private final List<Duration> arrivals = new CopyOnWriteArrayList<>();

  private volatile String lastUserAgent;

  StubMusicBrainzServer() {
    try {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    } catch (IOException e) {
      throw new IllegalStateException("could not start the stub server", e);
    }
    server.createContext(
        "/",
        exchange -> {
          arrivals.add(Duration.ofNanos(System.nanoTime() - startedAtNanos));
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

  URI baseUri() {
    return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
  }

  /** Queue one response body, consumed by the next request. */
  void enqueueBody(String json) {
    bodies.add(json);
  }

  /** Queue one response status, consumed by the next request. */
  void enqueueStatus(int status) {
    statuses.add(status);
  }

  int requestCount() {
    return requests.get();
  }

  /** How long after this stub was built each request arrived, in arrival order. */
  List<Duration> arrivals() {
    return List.copyOf(arrivals);
  }

  String lastUserAgent() {
    return lastUserAgent;
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
