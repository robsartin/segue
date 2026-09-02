package com.robsartin.segue.rate;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * What a Chrome NetLog says the browser tried to reach.
 *
 * <p>Written for {@link HeadlessChromeNetworkTest}, which turns {@link HeadlessChrome}'s claim that
 * "nothing here should reach the network" into an assertion. A NetLog is the only instrument that
 * can settle that claim: Chrome's own background requests are made by the browser process, not by
 * the page, so no stub server, no CDP event and no counter on the test's side can see them. Issue
 * #186; the traces this parses are the ones {@code docs/retry-pool-flush-evidence.md} §4 quotes.
 *
 * <p><b>Three kinds, because the claim has three parts.</b> "Reaches nothing but loopback" is not
 * one fact. A resolver rule can leave the host set loopback-only while Chrome still <em>asks</em>
 * for {@code clients2.google.com} and is told ERR_NAME_NOT_RESOLVED — the attempt is made, the
 * machinery runs, and a guard that only looked at sockets would call that clean. So every sighting
 * carries which of {@link Kind#RESOLUTION}, {@link Kind#REQUEST} and {@link Kind#SOCKET} named the
 * host, and the guard treats the three differently: reaching a host is a zero, merely naming one is
 * an inventory it holds Chrome to.
 *
 * <p><b>Event ids are not stable and are never hardcoded.</b> Chrome numbers its event types by
 * their order in an internal list, so the same id means different things across versions. The
 * mapping is in the log itself, under {@code constants.logEventTypes} and {@code
 * constants.logSourceType}, and it is read from there.
 */
final class NetLog {

  /**
   * Chrome's own name for the going-away notification that ends the startup cert-verifier flush.
   *
   * <p>{@code docs/loopback-only-evidence.md} §4 has the sequence verbatim: two {@code
   * CERT_VERIFY_PROC_CREATED} events in one millisecond, every pooled socket closed with {@code
   * {"reason": "Cert verifier changed"}}, then this. It is the last of the three, so it is the one
   * to wait for; the pair is kept as the second half of the condition in case a future Chrome stops
   * logging this event but still creates the verifier.
   */
  private static final String GOING_AWAY = "QUIC_SESSION_POOL_MARK_ALL_ACTIVE_SESSIONS_GOING_AWAY";

  private static final String CERT_VERIFIER_CREATED = "CERT_VERIFY_PROC_CREATED";

  /**
   * Chrome's own names for one socket coming into existence — as opposed to the pool bookkeeping
   * around it.
   *
   * <p>Matched by exact name rather than by the prefixes {@link #kindOf} uses, and that is the
   * whole point of the pair. {@code SOCKET_POOL_CONNECTING_N_SOCKETS} and {@code
   * TCP_CLIENT_SOCKET_POOL_REQUESTED_SOCKETS} both satisfy those prefixes and both precede any
   * socket by several events, so a prefix rule would answer "the page had a socket" before it had
   * one. Reading a socket as earlier than it was is exactly the error that would make {@link
   * Tail#markerPosition()} report a flush as late when it was not, and a false late reading costs a
   * test that skips on it its coverage, silently. {@code SOCKET_ALIVE} is logged as one socket
   * object is created and {@code TCP_CONNECT} as one connection is made; either is a socket.
   */
  private static final String SOCKET_ALIVE = "SOCKET_ALIVE";

  private static final String TCP_CONNECT = "TCP_CONNECT";

  /**
   * A NetLog read while Chrome is still writing it, answering one question: has the startup
   * cert-verifier flush passed?
   *
   * <p><b>Why a tail and not a parse.</b> The file is not valid JSON until the browser exits — the
   * events array is left open — so {@link #sightings} cannot be asked this question of a running
   * browser. What Chrome does guarantee is the shape: the {@code constants} block first, then one
   * event per line, appended. So this resolves the two event ids out of that block <em>once</em>
   * (never hardcoded — see the class note) and then reads only the bytes that appeared since the
   * last look, parsing each completed line on its own.
   *
   * <p>Each poll advances only as far as the last newline in what it read, so a line Chrome is
   * halfway through writing is left for the next one, and a UTF-8 sequence is never split: {@code
   * 0x0A} cannot occur inside a multi-byte character.
   *
   * <p><b>The top-level {@code type} only.</b> An event's own type and its {@code source.type} are
   * two different numberings that share a range, so {@code "type":311} appears in NetLogs meaning
   * something else entirely. Matching on the parsed field rather than the text is what keeps an
   * ordinary socket event from reading as the flush.
   */
  static final class Tail {

    private final Path netLog;
    private long offset;
    private int goingAwayId = -1;
    private int certVerifierId = -1;
    private int socketAliveId = -1;
    private int tcpConnectId = -1;
    private boolean idsResolved;
    private boolean goingAwaySeen;
    private int certVerifierSeen;
    private int eventsSeen;
    private int markerPosition;
    private int firstSocketPosition;

    Tail(Path netLog) {
      this.netLog = netLog;
    }

    /**
     * A tail that carries on from where this one has read, counting from zero.
     *
     * <p>{@code HeadlessChrome.open} makes one of these immediately before {@code Page.navigate},
     * so that "the page's first socket" means the first socket in the log the browser writes from
     * there on, and not one of the several Chrome opens for itself during startup. Every position
     * either tail reports is an ordinal within its own stretch.
     *
     * <p>The flush counters reset with it, deliberately. A cert-verifier pair split across the
     * navigate — one before, one after — is not evidence that a flush happened while the page held
     * a socket, and this reports the late case only on evidence that lies wholly after the page
     * began loading. The resolved ids are kept, because they are a property of the log's constants
     * block and re-reading it would cost a full parse of a file that is still growing.
     */
    Tail resumed() {
      Tail next = new Tail(netLog);
      next.offset = offset;
      next.goingAwayId = goingAwayId;
      next.certVerifierId = certVerifierId;
      next.socketAliveId = socketAliveId;
      next.tcpConnectId = tcpConnectId;
      next.idsResolved = idsResolved;
      return next;
    }

    /**
     * Whether the flush has passed, reading whatever Chrome has appended since the last call.
     *
     * <p>False while the log does not exist, while the constants block is still being written, and
     * while neither the going-away marker nor a second cert-verifier creation has been logged.
     */
    boolean flushHasPassed() {
      poll();
      return goingAwaySeen || certVerifierSeen >= 2;
    }

    /**
     * Reads whatever Chrome has appended since the last call, and counts it.
     *
     * <p>Unconditionally, even once the flush has passed: the positions below are the reason this
     * class exists after {@code Page.navigate} as well as before it, and a poll that returned early
     * on a condition already met would stop counting exactly when the interesting events arrive.
     */
    void poll() {
      if (!idsResolved && !resolveIds()) {
        return;
      }
      for (String line : appendedLines()) {
        int type = typeOf(line);
        if (type < 0) {
          continue;
        }
        eventsSeen++;
        if (type == goingAwayId) {
          goingAwaySeen = true;
        } else if (type == certVerifierId) {
          certVerifierSeen++;
        } else if (type == socketAliveId || type == tcpConnectId) {
          if (firstSocketPosition == 0) {
            firstSocketPosition = eventsSeen;
          }
          continue;
        }
        if (markerPosition == 0 && (goingAwaySeen || certVerifierSeen >= 2)) {
          markerPosition = eventsSeen;
        }
      }
    }

    /**
     * Where in this tail the flush condition was satisfied, or 0 while it has not been.
     *
     * <p>The ordinal of the event that satisfied it — the going-away marker, or the second
     * cert-verifier creation, whichever came first — because that is the event the wait itself acts
     * on, so this and {@link #flushHasPassed()} can never disagree about when the flush was.
     */
    int markerPosition() {
      return markerPosition;
    }

    /** Where in this tail the first socket appeared, or 0 while none has. */
    int firstSocketPosition() {
      return firstSocketPosition;
    }

    /**
     * Reads the event ids out of the log's own constants block, which Chrome writes before any
     * event.
     *
     * <p>Every name is looked up independently and any one of them is enough: a Chrome that renamed
     * one should cost the caller that one observation, not its ability to make the others. Once the
     * block has been read at all this does not run again — a constants block that named none of
     * them will not name them later, and the retry would cost a full parse of a growing file on
     * every poll.
     */
    private boolean resolveIds() {
      JsonNode types;
      try {
        types = parse(netLog).path("constants").path("logEventTypes");
      } catch (RuntimeException notWrittenYet) {
        return false;
      }
      goingAwayId = types.path(GOING_AWAY).asInt(-1);
      certVerifierId = types.path(CERT_VERIFIER_CREATED).asInt(-1);
      socketAliveId = types.path(SOCKET_ALIVE).asInt(-1);
      tcpConnectId = types.path(TCP_CONNECT).asInt(-1);
      idsResolved =
          goingAwayId >= 0 || certVerifierId >= 0 || socketAliveId >= 0 || tcpConnectId >= 0;
      return idsResolved;
    }

    /** Every line completed since the last call, and the file position moved past them. */
    private List<String> appendedLines() {
      try (SeekableByteChannel channel = Files.newByteChannel(netLog, StandardOpenOption.READ)) {
        long available = channel.size() - offset;
        if (available <= 0) {
          return List.of();
        }
        ByteBuffer buffer = ByteBuffer.allocate((int) Math.min(available, 1 << 20));
        channel.position(offset);
        int read = channel.read(buffer);
        if (read <= 0) {
          return List.of();
        }
        byte[] bytes = buffer.array();
        int lastNewline = -1;
        for (int i = read - 1; i >= 0; i--) {
          if (bytes[i] == '\n') {
            lastNewline = i;
            break;
          }
        }
        if (lastNewline < 0) {
          return List.of();
        }
        offset += lastNewline + 1L;
        return List.of(new String(bytes, 0, lastNewline, StandardCharsets.UTF_8).split("\n", -1));
      } catch (IOException notReadableYet) {
        return List.of();
      }
    }

    /** The event's own type id, or −1 for a line that is not one event. */
    private static int typeOf(String line) {
      String text = line.strip();
      if (text.endsWith(",")) {
        text = text.substring(0, text.length() - 1);
      }
      if (!text.startsWith("{") || !text.endsWith("}")) {
        return -1;
      }
      try {
        return JSON.readTree(text).path("type").asInt(-1);
      } catch (RuntimeException notAnEvent) {
        return -1;
      }
    }
  }

  /** Which part of "no request, resolution or socket" a sighting belongs to. */
  enum Kind {
    /** A DNS lookup was asked for — the host was named to the resolver. */
    RESOLUTION,
    /** A URL request was issued for the host, whether or not it ever got a socket. */
    REQUEST,
    /**
     * A socket or QUIC session named the host or its address. QUIC sessions count here: a session
     * is a thing held over a socket, and the flush in {@code retry-pool-flush-evidence.md} §4 tears
     * down both.
     */
    SOCKET
  }

  /**
   * One place in the log where a host was named.
   *
   * @param host the host, lowercased, with any port stripped
   * @param kind which part of the claim this sighting bears on
   * @param event the NetLog event type that named it, e.g. {@code HOST_RESOLVER_MANAGER_JOB}
   */
  record Sighting(String host, Kind kind, String event) {
    @Override
    public String toString() {
      return kind + " " + host + " (" + event + ")";
    }
  }

  private static final ObjectMapper JSON = JsonMapper.builder().build();

  /**
   * Params that carry a host or an address. Deliberately a fixed list rather than a scan of every
   * string in every param: a scan would drag in user-agent strings, group ids and error text, and a
   * guard that reports things it cannot explain is a guard people start ignoring.
   */
  private static final List<String> HOST_PARAMS =
      List.of("host", "hostname", "dns_query_name", "domain", "url", "original_url");

  private static final List<String> ADDRESS_PARAMS =
      List.of("address", "remote_address", "peer_address");

  private static final List<String> ADDRESS_LIST_PARAMS = List.of("address_list", "addresses");

  private NetLog() {}

  /**
   * Where a kept NetLog goes: {@code build/reports/netlog/}, inside the tree CI uploads.
   *
   * <p>Read from {@code segue.reports}, which {@code tasks.test} sets from Gradle's own build
   * directory, so the copy follows a relocated {@code build/} rather than guessing. The fallback is
   * the conventional path relative to the working directory, for a run launched from an IDE that
   * sets no property.
   */
  private static Path reportsDirectory() {
    String configured = System.getProperty("segue.reports", "");
    return configured.isBlank() ? Path.of("build", "reports") : Path.of(configured);
  }

  /**
   * Copies a NetLog to {@code build/reports/netlog/<name>.json} and answers where it landed.
   *
   * <p><b>Why the log has to outlive the run.</b> {@code HeadlessChromeNetworkTest}'s allowlist is
   * per-scenario <em>and</em> per-platform, and the second half was learned the expensive way: CI
   * run 33655745937 reddened on {@code redirector.gvt1.com}, a host Google Chrome stable on {@code
   * ubuntu-latest} asks for and Chrome 152 on macOS does not. The guard's NetLog was a temporary
   * file the browser owned, and the workflow's {@code reports} artifact carried the test XML and
   * the coverage HTML and nothing else — so the CI host set could be recovered only from the two
   * lists AssertJ printed in the failure. That is a derivation from an error message, which is
   * exactly what this project does not do with measurements.
   *
   * <p><b>On every run, not only on a red.</b> A copy kept only when the assertion fails gives the
   * next re-derivation nothing to compare against: the interesting question on a new platform is
   * what the <em>green</em> run named, and a baseline that exists only after a failure is not a
   * baseline. It costs one file copy per guarded launch.
   *
   * <p>Throws rather than reporting failure quietly. The guard asserts on the copy, so a copy that
   * did not happen must not read as a browser that reached nothing.
   */
  static Path keep(Path netLog, String name) {
    Path destination = reportsDirectory().resolve("netlog").resolve(name + ".json");
    try {
      Files.createDirectories(destination.getParent());
      Files.copy(netLog, destination, StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException e) {
      throw new UncheckedIOException("could not keep " + netLog + " at " + destination, e);
    }
    return destination;
  }

  /** Every host the log names, in the order first seen. */
  static Set<String> hostsContacted(Path netLog) {
    Set<String> hosts = new LinkedHashSet<>();
    for (Sighting sighting : sightings(netLog)) {
      hosts.add(sighting.host());
    }
    return hosts;
  }

  /**
   * Every sighting of a host in the log.
   *
   * <p>Throws rather than returning empty on a log it cannot read. An unreadable NetLog and a
   * NetLog showing no traffic are the same value to a caller that swallows the failure, and the
   * second is exactly what this guard is supposed to be unable to say by accident.
   */
  static List<Sighting> sightings(Path netLog) {
    JsonNode root = parse(netLog);
    Map<Integer, String> eventTypes = namesById(root.path("constants").path("logEventTypes"));
    Map<Integer, String> sourceTypes = namesById(root.path("constants").path("logSourceType"));
    if (eventTypes.isEmpty()) {
      throw new IllegalStateException(
          "no constants.logEventTypes in " + netLog + ", so no event can be identified");
    }
    JsonNode events = root.path("events");
    if (!events.isArray() || events.isEmpty()) {
      throw new IllegalStateException(
          "no events in " + netLog + " — the browser wrote a NetLog with nothing in it");
    }
    List<Sighting> sightings = new ArrayList<>();
    for (JsonNode event : events) {
      String eventName = eventTypes.getOrDefault(event.path("type").asInt(-1), "");
      String sourceName = sourceTypes.getOrDefault(event.path("source").path("type").asInt(-1), "");
      Kind kind = kindOf(eventName, sourceName);
      if (kind == null) {
        continue;
      }
      JsonNode params = event.path("params");
      for (String key : HOST_PARAMS) {
        add(sightings, hostOf(params.path(key).asString("")), kind, eventName);
      }
      for (String key : ADDRESS_PARAMS) {
        add(sightings, hostOf(params.path(key).asString("")), kind, eventName);
      }
      for (String key : ADDRESS_LIST_PARAMS) {
        for (JsonNode entry : params.path(key)) {
          add(sightings, hostOf(entry.asString("")), kind, eventName);
        }
      }
    }
    return List.copyOf(sightings);
  }

  /**
   * Which part of the claim an event bears on, or null for an event that names no host.
   *
   * <p>Matched on the event type <em>and</em> the source type, because the two disagree about where
   * the interesting parameter lives: the address of a socket is on a {@code TCP_CONNECT} event,
   * whose name says nothing about sockets, and whose source type is {@code SOCKET}.
   */
  private static Kind kindOf(String eventName, String sourceName) {
    if (eventName.contains("LOCAL_ADDRESS")) {
      // The near end of a socket, not a host reached — on this machine the address is the LAN
      // interface's own. Reporting it would make the guard both wrong and unportable.
      return null;
    }
    if (eventName.startsWith("HOST_RESOLVER") || sourceName.startsWith("HOST_RESOLVER")) {
      return Kind.RESOLUTION;
    }
    if (eventName.startsWith("URL_REQUEST") || sourceName.startsWith("URL_REQUEST")) {
      return Kind.REQUEST;
    }
    if (eventName.startsWith("SOCKET")
        || eventName.startsWith("TCP_")
        || eventName.startsWith("UDP_")
        || eventName.startsWith("QUIC_SESSION")
        // HTTP/2 for the same reason as QUIC: a session is a thing held over a socket, and the
        // flush in retry-pool-flush-evidence.md §4 closes both. h2 is caught today through the
        // TCP and SSL events underneath it, but leaving it out made the symmetry one-sided.
        || eventName.startsWith("HTTP2_SESSION")
        || sourceName.endsWith("SOCKET")
        || sourceName.startsWith("QUIC_SESSION")
        || sourceName.startsWith("HTTP2_SESSION")) {
      return Kind.SOCKET;
    }
    return null;
  }

  private static void add(List<Sighting> sightings, String host, Kind kind, String event) {
    if (!host.isEmpty()) {
      sightings.add(new Sighting(host, kind, event));
    }
  }

  /**
   * The host in one param value: a URL's authority, an {@code address:port}, or a bare name.
   *
   * <p>Returns "" for anything with no host to speak of — {@code about:blank}, {@code chrome://},
   * {@code data:} — which the browser reaches without touching the network at all.
   */
  private static String hostOf(String value) {
    if (value == null || value.isBlank()) {
      return "";
    }
    String text = value.trim();
    int scheme = text.indexOf("://");
    if (scheme > 0) {
      String host = null;
      try {
        host = new URI(text).getHost();
      } catch (URISyntaxException notAUri) {
        // Fall through to the raw authority below.
      }
      // java.net.URI is RFC 2396, which forbids `~` and `_` in a host — so getHost() answers null
      // for names Chrome writes and DNS accepts. Two of them matter here: `https://~notfound`,
      // which is how Chrome logs a name the resolver rule mapped, and anything of the shape
      // `https://evil_host.example/`. The shipped parser returned "" for both, so a NetLog full of
      // them read as a browser that had reached nothing at all — the dead instrument #169 spent
      // two rounds on, rebuilt inside the guard against it. Falling back to the authority Chrome
      // wrote is what keeps "could not parse" from looking like "nothing happened".
      return strip(host != null ? host : authority(text.substring(scheme + 3)));
    }
    if (text.contains(":/") || text.startsWith("about:") || text.startsWith("data:")) {
      return "";
    }
    return strip(text);
  }

  /**
   * The authority of a URL whose scheme has been removed: everything before the path, query or
   * fragment, with any {@code user@} dropped.
   */
  private static String authority(String afterScheme) {
    int end = afterScheme.length();
    for (int i = 0; i < afterScheme.length(); i++) {
      char c = afterScheme.charAt(i);
      if (c == '/' || c == '?' || c == '#') {
        end = i;
        break;
      }
    }
    String authority = afterScheme.substring(0, end);
    int credentials = authority.lastIndexOf('@');
    return credentials < 0 ? authority : authority.substring(credentials + 1);
  }

  /** Removes a trailing {@code :port} and IPv6 brackets, and lowercases what is left. */
  private static String strip(String hostOrAuthority) {
    String text = hostOrAuthority.trim();
    if (text.startsWith("[")) {
      int close = text.indexOf(']');
      return close < 0 ? "" : text.substring(1, close).toLowerCase(Locale.ROOT);
    }
    int colon = text.lastIndexOf(':');
    if (colon > 0 && text.indexOf(':') == colon && isPort(text.substring(colon + 1))) {
      text = text.substring(0, colon);
    }
    return text.toLowerCase(Locale.ROOT);
  }

  private static boolean isPort(String candidate) {
    if (candidate.isEmpty()) {
      return false;
    }
    return candidate.chars().allMatch(Character::isDigit);
  }

  private static Map<Integer, String> namesById(JsonNode nameToId) {
    Map<Integer, String> byId = new HashMap<>();
    for (Map.Entry<String, JsonNode> entry : nameToId.properties()) {
      if (entry.getValue().isInt()) {
        byId.put(entry.getValue().asInt(), entry.getKey());
      }
    }
    return byId;
  }

  /**
   * Reads the log, repairing the one truncation a killed browser leaves behind.
   *
   * <p>Chrome streams a NetLog as it runs and only writes the closing {@code ]}} on a clean
   * shutdown. The harness ends the browser with a signal, so the events array can be left open —
   * and a parse failure there must not be allowed to read as "no events", which would make the
   * guard pass on an instrument that never worked. So: close the array, dropping at most a few
   * trailing lines to reach a boundary, and throw the original failure if that does not work.
   */
  private static JsonNode parse(Path netLog) {
    String text;
    try {
      text = Files.readString(netLog);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    RuntimeException unreadable;
    try {
      return JSON.readTree(text);
    } catch (RuntimeException incomplete) {
      unreadable = incomplete;
    }
    String head = text.stripTrailing();
    for (int attempt = 0; attempt < 4 && !head.isEmpty(); attempt++) {
      String candidate = head.endsWith(",") ? head.substring(0, head.length() - 1) : head;
      try {
        return JSON.readTree(candidate + "]}");
      } catch (RuntimeException stillIncomplete) {
        int lastLine = head.lastIndexOf('\n');
        if (lastLine < 0) {
          break;
        }
        head = head.substring(0, lastLine).stripTrailing();
      }
    }
    throw new IllegalStateException("could not read the NetLog at " + netLog, unreadable);
  }
}
