package com.robsartin.segue.rate;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
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
