package com.robsartin.segue.rate;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * A real Chrome, headless, driven over the DevTools protocol — the smallest thing that can run
 * {@code deck.html} as written.
 *
 * <p><b>Why a browser at all.</b> The page is a hundred lines of {@code async}/{@code await} over
 * {@code fetch}, and its five guards are behaviours, not tokens: {@code if (!response.ok) ...
 * return;} reads identically to a version with the {@code return} deleted, and that deletion is the
 * exact silent-data-loss defect issue #101 fixed. Only running the page can tell those apart.
 *
 * <p><b>Why no test dependency.</b> HtmlUnit 5.4.0 — the pure-Java candidate, which would have
 * needed no browser on the machine — cannot run this page at all: it has no {@code fetch} and its
 * JavaScript engine does not parse {@code async}, so the whole script block is a syntax error and
 * the deck never leaves "loading…". Measured, not assumed; see issue #103. That left a real
 * browser, and once a real browser is required, Playwright and Selenium buy an API over a protocol
 * the JDK can already speak: {@link ProcessBuilder} launches Chrome, {@link WebSocket} carries the
 * commands, and Jackson (already here) reads the answers. Chrome is discovered, never downloaded.
 *
 * <p><b>Network posture: loopback only, and checked.</b> This class used to say "nothing here
 * should reach the network" over two flags that did not achieve it — every NetLog captured for
 * issue #169 shows Chrome reaching {@code clients2.google.com}, {@code accounts.google.com} and
 * {@code gstatic.com} on each launch, opening QUIC sessions to them, and then closing every socket
 * it holds when that work settles, the loopback ones included ({@code
 * docs/retry-pool-flush-evidence.md} §4–§5). What is true now, and enforced by {@link
 * HeadlessChromeNetworkTest} rather than asserted here: <b>no socket, no handshake and no byte
 * reaches any host but {@code 127.0.0.1}</b>, because {@code --host-resolver-rules} fails every
 * other name at DNS. Chrome still <em>asks</em> for three of its own hosts and is refused; that
 * residual is measured, listed and asserted on in that test, not hidden. See {@link #flags} for
 * each flag and what the NetLog showed it removing.
 *
 * <p>Absent a browser this class reports {@link #available()} false and the tests that need it skip
 * — with CI made to fail rather than skip, see {@code DeckBehaviourTest}.
 */
final class HeadlessChrome implements AutoCloseable {

  /** Overrides discovery, for a machine whose browser is somewhere unusual. */
  private static final String EXECUTABLE_PROPERTY = "segue.chrome";

  private static final List<String> CANDIDATES =
      List.of(
          "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
          "/Applications/Chromium.app/Contents/MacOS/Chromium",
          "/usr/bin/google-chrome",
          "/usr/bin/google-chrome-stable",
          "/usr/bin/chromium",
          "/usr/bin/chromium-browser",
          "/snap/bin/chromium");

  private static final ObjectMapper JSON = JsonMapper.builder().build();

  private final Process process;
  private final Path userData;
  private final WebSocket socket;
  private final LinkedBlockingQueue<String> messages = new LinkedBlockingQueue<>();
  private final AtomicInteger nextId = new AtomicInteger(1);

  static Optional<Path> executable() {
    // The property first, the environment variable second — and a BLANK property falls through
    // to the environment rather than shadowing it, because the build forwards the property
    // unconditionally and would otherwise hand every run an empty override.
    String named = System.getProperty(EXECUTABLE_PROPERTY);
    if (named == null || named.isBlank()) {
      named = System.getenv("SEGUE_CHROME");
    }
    if (named != null && !named.isBlank()) {
      Path path = Path.of(named);
      return Files.isExecutable(path) ? Optional.of(path) : Optional.empty();
    }
    return CANDIDATES.stream().map(Path::of).filter(Files::isExecutable).findFirst();
  }

  static boolean available() {
    return executable().isPresent();
  }

  /** Launches a throwaway browser and attaches to its first (blank) tab. */
  static HeadlessChrome launch() {
    return launch(null);
  }

  /**
   * The same browser, additionally writing Chrome's own NetLog to {@code netLog} (JSON).
   *
   * <p>Only {@link HeadlessChromeNetworkTest} and the measurements in {@code
   * docs/retry-pool-flush-evidence.md} pass a path; every other test takes the no-argument launch
   * and its command line is unchanged by this. The instrument was checked in that study — 20 of 81
   * runs made with the NetLog flags removed were indistinguishable from the rest (§2, "Instrument
   * control") — so capturing it neither causes nor masks what the guard asserts on.
   *
   * <p>{@code IncludeSensitive} rather than the default: the default strips URLs and headers it
   * judges private, and a guard that asserts on what Chrome reached must see everything Chrome
   * reached. Nothing sensitive is in reach — the only origin is a loopback stub this test started.
   */
  static HeadlessChrome launch(Path netLog) {
    Path exe =
        executable()
            .orElseThrow(() -> new IllegalStateException("no Chrome or Chromium on this machine"));
    try {
      Path userData = Files.createTempDirectory("segue-deck-chrome");
      List<String> command = new ArrayList<>();
      command.add(exe.toString());
      command.addAll(flags(userData));
      if (netLog != null) {
        command.add("--log-net-log=" + netLog);
        command.add("--net-log-capture-mode=IncludeSensitive");
      }
      command.add("about:blank");
      Process process =
          new ProcessBuilder(command)
              .redirectOutput(ProcessBuilder.Redirect.DISCARD)
              .redirectError(ProcessBuilder.Redirect.DISCARD)
              .start();
      return new HeadlessChrome(process, userData);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** The command line, minus the executable, the NetLog options and the page to open. */
  private static List<String> flags(Path userData) {
    return List.of(
        "--headless=new",
        "--disable-gpu",
        // The browser only ever loads a loopback page this test just started, and a
        // CI container may run it as root, where the sandbox refuses to start at all.
        "--no-sandbox",
        "--no-first-run",
        "--no-default-browser-check",
        "--disable-extensions",
        // Kept, though neither is sufficient and this one is measurably not what its name
        // says: with only these two, Chrome 152 still resolves and connects to
        // clients2.google.com, accounts.google.com, www.google.com, www.gstatic.com and
        // android.clients.google.com on every launch. That is what the next three flags exist
        // for, and what HeadlessChromeNetworkTest now refuses to let anyone forget.
        "--disable-background-networking",
        "--disable-component-update",
        // THE GUARANTEE. Every hostname resolution outside loopback fails at DNS, so no socket,
        // no TLS handshake and no QUIC session to a non-loopback host can exist. Measured: with
        // this line, every TCP_CONNECT, SSL handshake and QUIC session to a Google address
        // disappears from the NetLog.
        //
        // `EXCLUDE 127.0.0.1` is load-bearing, and was NOT obvious. Issue #186's spec assumed the
        // literal needs no exclusion because "the page is loaded by IP literal, which is never
        // resolved". It is: with `EXCLUDE localhost` alone, Chrome 152 maps 127.0.0.1 to
        // ~NOTFOUND like any other name and every test here loads an ERR_NAME_NOT_RESOLVED page
        // instead of the deck. Found by the deck failing to deal a card, not by reading.
        //
        // Not --proxy-server to a dead port, which proxies loopback too unless bypassed, and the
        // bypass list would be a second place the loopback rule lives (issue #186).
        "--host-resolver-rules=MAP * ~NOTFOUND, EXCLUDE localhost, EXCLUDE 127.0.0.1",
        // ON TOP OF THE GUARANTEE: the two flags that measurably stop an *attempt* being made,
        // so there is less for a configuration change to tear down. Added one at a time against
        // the NetLog on Chrome 152.0.7977.65, keeping only what removed something:
        //
        //   NetworkTimeServiceQuerying          removes http://clients2.google.com/time/1/current
        //   SafeBrowsingHashPrefixRealTimeLookups
        //                                       removes https://www.gstatic.com/ohttp_gateway/…
        //
        // The first is not incidental: docs/retry-pool-flush-evidence.md §4 caught the browser-wide
        // socket flush firing in the same millisecond that clients2.google.com/time completed.
        //
        // Every flag Puppeteer launches with was tried here and removed NOTHING on this build —
        // --disable-sync, --disable-default-apps, --metrics-recording-only, --no-service-autorun,
        // --disable-domain-reliability, --disable-client-side-phishing-detection,
        // --safebrowsing-disable-auto-update, --disable-component-extensions-with-background-pages,
        // --disable-breakpad, --enable-automation, --no-pings, and --disable-features= for
        // Translate, OptimizationHints, MediaRouter, InterestFeedContentSuggestions,
        // AutofillServerCommunication, CertificateTransparencyComponentUpdater and
        // DialMediaRouteProvider. Nor did --disable-features=DnsOverHttpsUpgrade or
        // DnsOverHttps or --dns-over-https-mode=off, each measured against the one socket
        // that looks like a DNS probe (see KNOWN_ATTEMPTS: it sends no byte, and this
        // profile has no DoH server configured). None is here, because a flag that removes
        // nothing is a flag nobody can explain later.
        "--disable-features=NetworkTimeServiceQuerying,SafeBrowsingHashPrefixRealTimeLookups",
        "--remote-debugging-port=0",
        "--user-data-dir=" + userData);
  }

  private HeadlessChrome(Process process, Path userData) {
    this.process = process;
    this.userData = userData;
    WebSocket connected;
    try {
      connected = connect(devToolsPort());
    } catch (RuntimeException | Error handshakeFailed) {
      // Chrome is already running by the time we get here, and close() is only ever reached
      // through a constructed object — so without this, a browser that never wrote its port, or
      // never listed a page target, is left running with its profile directory behind it, once
      // per failing test.
      kill();
      throw handshakeFailed;
    }
    this.socket = connected;
  }

  /**
   * The port Chrome actually chose.
   *
   * <p>Asked for as 0 and read back from the file Chrome writes into its own profile, so a suite
   * running beside anything else cannot collide on a fixed port.
   */
  private int devToolsPort() {
    Path portFile = userData.resolve("DevToolsActivePort");
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
    while (System.nanoTime() < deadline) {
      try {
        if (Files.exists(portFile)) {
          List<String> lines = Files.readAllLines(portFile);
          if (!lines.isEmpty() && !lines.get(0).isBlank()) {
            return Integer.parseInt(lines.get(0).trim());
          }
        }
        if (!process.isAlive()) {
          throw new IllegalStateException("Chrome exited before it opened a debugging port");
        }
        Thread.sleep(50);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(e);
      }
    }
    throw new IllegalStateException("Chrome never wrote DevToolsActivePort");
  }

  private WebSocket connect(int port) {
    String endpoint = pageEndpoint(port);
    WebSocket.Listener listener =
        new WebSocket.Listener() {
          private final StringBuilder partial = new StringBuilder();

          @Override
          public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            ws.request(1);
            partial.append(data);
            if (last) {
              messages.add(partial.toString());
              partial.setLength(0);
            }
            return null;
          }
        };
    return HttpClient.newHttpClient()
        .newWebSocketBuilder()
        .buildAsync(URI.create(endpoint), listener)
        .join();
  }

  /** The debugger URL of the one open tab, from Chrome's own HTTP endpoint. */
  private String pageEndpoint(int port) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
    RuntimeException last = new IllegalStateException("Chrome listed no page target");
    while (System.nanoTime() < deadline) {
      try {
        HttpResponse<String> response =
            HttpClient.newHttpClient()
                .send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/json/list"))
                        .build(),
                    HttpResponse.BodyHandlers.ofString());
        for (JsonNode target : JSON.readTree(response.body())) {
          if ("page".equals(target.path("type").asString())) {
            return target.path("webSocketDebuggerUrl").asString();
          }
        }
      } catch (IOException e) {
        last = new UncheckedIOException(e);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(e);
      }
      sleep(50);
    }
    throw last;
  }

  /** One command, and the answer to that command. Callers are single-threaded, and wait. */
  private JsonNode send(String method, Map<String, Object> params) {
    int id = nextId.getAndIncrement();
    Map<String, Object> command = new LinkedHashMap<>();
    command.put("id", id);
    command.put("method", method);
    command.put("params", params);
    socket.sendText(JSON.writeValueAsString(command), true).join();
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
    List<String> events = new ArrayList<>();
    try {
      while (System.nanoTime() < deadline) {
        String message = messages.poll(1, TimeUnit.SECONDS);
        if (message == null) {
          continue;
        }
        JsonNode node = JSON.readTree(message);
        if (node.path("id").asInt(-1) == id) {
          // Anything read while waiting was an unsolicited event; put it back for nobody, since
          // nothing here subscribes to events. Kept only to make a mis-ordered answer visible.
          events.clear();
          if (node.has("error")) {
            throw new IllegalStateException(method + " failed: " + node.path("error"));
          }
          return node.path("result");
        }
        events.add(message);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
    throw new IllegalStateException(
        "no answer to " + method + " (saw " + events.size() + " events)");
  }

  /** Loads a URL and waits for the document to be parsed. */
  void open(String url) {
    send("Page.enable", Map.of());
    send("Page.navigate", Map.of("url", url));
    until("document.readyState === 'complete'", "the page to load");
  }

  /**
   * Evaluates an expression in the page and returns its value, failing if the expression threw.
   *
   * <p>The {@code exceptionDetails} check is not housekeeping. Without it a JavaScript error comes
   * back as an empty value, so {@link #text(String)} yields {@code ""} and every negative-space
   * assertion — {@code isZero}, {@code doesNotContain}, {@code isEmpty} — passes on a question that
   * was never actually asked. That is precisely the silent no-op this whole harness exists to
   * close, and it may not live inside the harness itself.
   */
  Object eval(String expression) {
    JsonNode answer =
        send(
            "Runtime.evaluate",
            Map.of(
                "expression", expression,
                "returnByValue", true,
                "awaitPromise", true));
    if (answer.has("exceptionDetails")) {
      JsonNode thrown = answer.path("exceptionDetails");
      String said =
          thrown
              .path("exception")
              .path("description")
              .asString(thrown.path("text").asString("a JavaScript error with no description"));
      throw new IllegalStateException("evaluating `" + expression + "` in the page threw: " + said);
    }
    JsonNode result = answer.path("result");
    return switch (result.path("type").asString("undefined")) {
      case "string" -> result.path("value").asString();
      case "boolean" -> result.path("value").asBoolean();
      case "number" -> result.path("value").asInt();
      default -> result.path("value").isNull() ? null : result.path("value").toString();
    };
  }

  /** The visible text of one element, by id. */
  String text(String id) {
    Object value = eval("document.getElementById('" + id + "').textContent.trim()");
    return value == null ? "" : value.toString();
  }

  /**
   * Waits for a JavaScript condition to hold, and says what it was waiting for when it does not.
   */
  void until(String condition, String what) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
    while (System.nanoTime() < deadline) {
      if (Boolean.TRUE.equals(eval("!!(" + condition + ")"))) {
        return;
      }
      sleep(20);
    }
    throw new AssertionError("timed out waiting for " + what + " (" + condition + ")");
  }

  /**
   * One key, as the operating system would deliver it.
   *
   * <p>{@code autoRepeat} is Chrome's own flag, so the page sees a genuine {@code event.repeat} —
   * this is what lets a held key be tested as a held key rather than as a synthesised event object
   * the page's guard was written to match.
   */
  void press(String key, boolean autoRepeat, int modifiers) {
    Map<String, Object> down = new LinkedHashMap<>();
    down.put("type", "keyDown");
    down.put("key", key);
    down.put("code", codeFor(key));
    down.put("text", key);
    down.put("windowsVirtualKeyCode", virtualKeyFor(key));
    down.put("modifiers", modifiers);
    down.put("autoRepeat", autoRepeat);
    send("Input.dispatchKeyEvent", down);
    if (!autoRepeat) {
      Map<String, Object> up = new LinkedHashMap<>(down);
      up.put("type", "keyUp");
      up.remove("text");
      up.put("autoRepeat", false);
      send("Input.dispatchKeyEvent", up);
    }
  }

  void press(String key) {
    press(key, false, 0);
  }

  private static String codeFor(String key) {
    if (key.length() == 1 && Character.isDigit(key.charAt(0))) {
      return "Digit" + key;
    }
    if (" ".equals(key)) {
      return "Space";
    }
    return "Key" + key.toUpperCase(java.util.Locale.ROOT);
  }

  private static int virtualKeyFor(String key) {
    if (" ".equals(key)) {
      return 32;
    }
    char c = key.charAt(0);
    return Character.isDigit(c) ? c : Character.toUpperCase(c);
  }

  static void sleep(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  @Override
  public void close() {
    socket.abort();
    kill();
  }

  /**
   * Ends the browser and removes its throwaway profile.
   *
   * <p>Separate from {@link #close()} because the constructor needs it before there is a socket to
   * abort.
   */
  private void kill() {
    process.destroy();
    try {
      process.waitFor(10, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    process.destroyForcibly();
    try {
      // Deleting the profile while the process is still dying is what makes entries vanish
      // mid-walk.
      process.waitFor(10, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    deleteTree(userData);
  }

  /**
   * Removes the throwaway profile, tolerating a tree that changes underneath.
   *
   * <p>Chrome keeps removing its own profile files as it exits, so entries vanish mid-walk. This
   * visits rather than streaming because {@link Files#walk} is lazy: a listing that fails partway
   * through raises {@link java.io.UncheckedIOException}, which is not an {@link IOException} and so
   * escaped the {@code catch} written to swallow exactly this. Failing here failed a passing test
   * from {@code @AfterEach}.
   *
   * <p>Every failure is continued past rather than caught at the top, so one unreadable corner does
   * not abandon the rest of the profile.
   */
  static void deleteTree(Path root) {
    try {
      Files.walkFileTree(
          root,
          new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
              deleteQuietly(file);
              return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException failure) {
              // Vanished as Chrome exited, or a directory we may not list. Neither is our business.
              return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException failure) {
              deleteQuietly(dir);
              return FileVisitResult.CONTINUE;
            }
          });
    } catch (IOException ignored) {
      // A leftover profile in the temp directory is not worth failing a test over.
    }
  }

  private static void deleteQuietly(Path path) {
    try {
      Files.deleteIfExists(path);
    } catch (IOException ignored) {
      // Same: it may have gone on its own, or be a directory we could not empty.
    }
  }
}
