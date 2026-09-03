package com.robsartin.segue.musicbrainz;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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
  private final Map<String, String> bodiesByPath = new ConcurrentHashMap<>();
  private final Deque<Integer> statuses = new ArrayDeque<>();
  private final AtomicInteger requests = new AtomicInteger();

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
          requests.incrementAndGet();
          lastUserAgent = exchange.getRequestHeaders().getFirst("User-Agent");
          int status = statuses.isEmpty() ? 200 : statuses.poll();
          String registered = bodiesByPath.get(exchange.getRequestURI().getPath());
          byte[] body =
              (registered != null ? registered : bodies.isEmpty() ? "{}" : bodies.poll())
                  .getBytes(StandardCharsets.UTF_8);
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

  /**
   * Queue one response body, consumed by the next request.
   *
   * <p>The queue is the right shape for a test about <i>when</i> a response arrives — a retry, a
   * throttle, a run of failures — where every request goes to the same artist and only the order
   * matters. {@link #enqueueBody(String, String)} is the right shape for a test about <i>which</i>
   * artist was asked for. Both exist because a walk over several seeds cannot use the queue: it
   * would pass whatever number of requests happened to arrive first, in whatever order, which is
   * exactly what such a test has to be able to fail on.
   */
  void enqueueBody(String json) {
    bodies.add(json);
  }

  /**
   * Answer {@code path} — {@code /artist/<mbid>} — with {@code json}, however often it is asked for
   * and whatever else is queued.
   *
   * <p>A registered path wins over the queue and does not consume it, so a test that registers
   * nothing sees exactly the behaviour above. Queued <i>statuses</i> still apply, because a status
   * queue is about the response's fate rather than its subject.
   */
  void enqueueBody(String path, String json) {
    bodiesByPath.put(path, json);
  }

  /** Queue one response status, consumed by the next request. */
  void enqueueStatus(int status) {
    statuses.add(status);
  }

  int requestCount() {
    return requests.get();
  }

  String lastUserAgent() {
    return lastUserAgent;
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
