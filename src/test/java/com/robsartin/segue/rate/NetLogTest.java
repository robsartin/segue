package com.robsartin.segue.rate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link NetLog} reads what Chrome actually writes, including the parts that are not URIs.
 *
 * <p>{@link HeadlessChromeNetworkTest} is only as good as this parser, and a parser that yields
 * nothing for input it cannot handle is indistinguishable from a browser that did nothing — the
 * dead-instrument failure issue #169 spent two rounds on. The shipped parser had exactly that hole:
 * {@code java.net.URI} is RFC 2396, which forbids {@code ~} and {@code _} in a host, so {@code
 * getHost()} returned null and the sighting vanished silently.
 *
 * <p>The fixture below is transcribed from a real NetLog captured with the harness's own flags on
 * Chrome 152.0.7977.65 — the event names, source types and parameter shapes are as Chrome writes
 * them, including {@code "host": "https://~notfound"}, which is a scheme in front of a name that is
 * not a legal URI host. Event type ids are deliberately arbitrary: they are resolved through {@code
 * constants}, never hardcoded, and this fixture would break if that ever stopped being true.
 */
class NetLogTest {

  @TempDir private Path scratch;

  private static final String CAPTURED =
      """
      {
        "constants": {
          "logEventTypes": {
            "HOST_RESOLVER_MANAGER_REQUEST": 187,
            "URL_REQUEST_START_JOB": 402,
            "TCP_CONNECT": 91,
            "UDP_LOCAL_ADDRESS": 77,
            "HTTP2_SESSION": 55
          },
          "logSourceType": {
            "NETWORK_SERVICE_HOST_RESOLVER": 48,
            "URL_REQUEST": 1,
            "SOCKET": 42,
            "UDP_SOCKET": 43,
            "HTTP2_SESSION": 12
          }
        },
        "events": [
          {"type":187,"source":{"id":1,"type":48},
           "params":{"host":"https://~notfound","is_speculative":false}},
          {"type":402,"source":{"id":2,"type":1},
           "params":{"url":"https://evil_host.example/beacon"}},
          {"type":402,"source":{"id":3,"type":1},
           "params":{"url":"http://127.0.0.1:8080/api/card?i=0"}},
          {"type":91,"source":{"id":4,"type":42},
           "params":{"address_list":["93.184.216.34:443"]}},
          {"type":77,"source":{"id":5,"type":43},
           "params":{"address":"[2605:a601:1539:5900:41c2:cc80:e3e3:96df]:62151"}},
          {"type":55,"source":{"id":6,"type":12},
           "params":{"host":"h2.example.com:443"}}
        ]
      }
      """;

  private Path captured() throws IOException {
    return Files.writeString(scratch.resolve("net-log.json"), CAPTURED);
  }

  @Test
  @DisplayName("the resolver rule's own sentinel is seen, though it is not a legal URI host")
  void shouldSeeTheSentinelWhenChromeLogsItBehindAScheme() throws IOException {
    assertThat(NetLog.sightings(captured()))
        .as(
            "Chrome writes the mapped name as host = https://~notfound. A parser that drops"
                + " it reports a quieter browser than the one that ran")
        .contains(
            new NetLog.Sighting(
                "~notfound", NetLog.Kind.RESOLUTION, "HOST_RESOLVER_MANAGER_REQUEST"));
  }

  @Test
  @DisplayName("a host that is illegal in a URI is still seen, not silently dropped")
  void shouldSeeAHostWhenItsNameIsIllegalInAUri() throws IOException {
    assertThat(NetLog.sightings(captured()))
        .as(
            "an underscore is legal in DNS and illegal in an RFC 2396 host, so URI.getHost() gives"
                + " null — and a beacon to it must not become an empty result")
        .contains(
            new NetLog.Sighting("evil_host.example", NetLog.Kind.REQUEST, "URL_REQUEST_START_JOB"));
  }

  @Test
  @DisplayName("an HTTP/2 session names its host as a socket-level sighting")
  void shouldClassifyAnHttp2SessionAsASocketWhenItNamesItsHost() throws IOException {
    assertThat(NetLog.sightings(captured()))
        .as("QUIC sessions are classified; HTTP/2 sessions are the same kind of thing")
        .contains(new NetLog.Sighting("h2.example.com", NetLog.Kind.SOCKET, "HTTP2_SESSION"));
  }

  @Test
  @DisplayName("the ordinary hosts and addresses are read, and the near end of a socket is not")
  void shouldReadHostsAndAddressesWhenTheyAreWellFormed() throws IOException {
    assertThat(NetLog.hostsContacted(captured()))
        .contains("127.0.0.1", "93.184.216.34")
        .as("UDP_LOCAL_ADDRESS is this machine's own address, not a host it reached")
        .doesNotContain("2605:a601:1539:5900:41c2:cc80:e3e3:96df");
  }

  @Test
  @DisplayName("a log with no events fails rather than reporting a browser that did nothing")
  void shouldFailWhenTheLogHasNoEvents() throws IOException {
    Path empty =
        Files.writeString(
            scratch.resolve("empty.json"),
            "{\"constants\":{\"logEventTypes\":{\"TCP_CONNECT\":91}},\"events\":[]}");
    assertThatThrownBy(() -> NetLog.sightings(empty))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("nothing in it");
  }

  @Test
  @DisplayName("a log the browser was killed part-way through writing is still read")
  void shouldRepairTheLogWhenTheBrowserWasKilledMidWrite() throws IOException {
    int lastEvent = CAPTURED.lastIndexOf("{\"type\":55");
    Path truncated =
        Files.writeString(scratch.resolve("truncated.json"), CAPTURED.substring(0, lastEvent));
    List<NetLog.Sighting> sightings = NetLog.sightings(truncated);
    assertThat(sightings).isNotEmpty();
    assertThat(NetLog.hostsContacted(truncated)).contains("93.184.216.34");
  }

  /**
   * The same log with Chrome's own phone-home events taken out — a browser that reached nothing and
   * asked for nothing, which is also what a parser blinded by a NetLog format change looks like
   * from the outside.
   */
  private static final String NO_PHONE_HOME =
      """
      {
        "constants": {
          "logEventTypes": {"URL_REQUEST_START_JOB": 402, "TCP_CONNECT": 91},
          "logSourceType": {"URL_REQUEST": 1, "SOCKET": 42}
        },
        "events": [
          {"type":402,"source":{"id":1,"type":1},
           "params":{"url":"http://127.0.0.1:8080/api/card?i=0"}},
          {"type":91,"source":{"id":2,"type":42},
           "params":{"address_list":["127.0.0.1:8080"]}}
        ]
      }
      """;

  @Test
  @DisplayName(
      "the guard's instrument control fails when the parser sees none of Chrome's attempts")
  void shouldFailTheInstrumentControlWhenNoPhoneHomeIsSeen() throws IOException {
    Path stripped = Files.writeString(scratch.resolve("no-phone-home.json"), NO_PHONE_HOME);

    assertThatThrownBy(
            () ->
                HeadlessChromeNetworkTest.requireTheParserStillSeesChromesAttempts(
                    NetLog.sightings(stripped)))
        .as(
            "a NetLog with no non-loopback attempt in it must not let the guard's zero pass"
                + " unremarked — that is a blind instrument, not a quiet browser")
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("re-derive KNOWN_ATTEMPTS");
  }

  @Test
  @DisplayName("the instrument control passes when Chrome's attempts are in the log")
  void shouldPassTheInstrumentControlWhenAPhoneHomeIsSeen() throws IOException {
    Path withAttempt =
        Files.writeString(
            scratch.resolve("with-phone-home.json"),
            NO_PHONE_HOME.replace(
                "\"url\":\"http://127.0.0.1:8080/api/card?i=0\"",
                "\"url\":\"https://accounts.google.com/ListAccounts\""));

    HeadlessChromeNetworkTest.requireTheParserStillSeesChromesAttempts(
        NetLog.sightings(withAttempt));
  }
}
