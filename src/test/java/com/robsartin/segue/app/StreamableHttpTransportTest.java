package com.robsartin.segue.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The other half of ADR 28: segue also speaks Streamable HTTP, and it is integration-tested rather
 * than merely built. ADR 28 is explicit that "both built, only stdio tested" was considered and
 * rejected — an untested transport is one that quietly stops working.
 *
 * <p>This boots the real application on a real port and drives it with an ordinary {@link
 * HttpClient}, not a mock: the thing under test is the transport, so anything that stubs the
 * transport out tests nothing. The session is a complete MCP conversation over Streamable HTTP —
 * {@code initialize}, the {@code notifications/initialized} notification, {@code tools/list}, and a
 * real {@code tools/call} — because a handshake alone would not prove a tool can be invoked this
 * way.
 *
 * <p>Two protocol details that are easy to get wrong and produce confusing failures:
 *
 * <ul>
 *   <li>A Streamable HTTP POST must send {@code Accept: application/json, text/event-stream}. The
 *       SDK's transport rejects anything else outright ("Invalid Accept headers"), because it
 *       chooses per response whether to answer with a single JSON body or an SSE stream.
 *   <li>The response to {@code initialize} carries an {@code Mcp-Session-Id} header, and every
 *       later request in the session must echo it back. The pinned protocol revision (2025-11-25,
 *       ADR 27) still has sessions; the revision after it removes them, which is exactly why
 *       nothing in this project's own code depends on one.
 * </ul>
 *
 * <p>The origin tests are not decoration. ADR 28 makes {@code Origin} validation with 403 on
 * mismatch a requirement, because a server bound to localhost is still reachable from any web page
 * the user has open unless it checks who is asking — that is the DNS-rebinding attack, and the
 * check is the defence. A test that only proved the happy path would leave the whole reason for the
 * localhost binding unverified.
 */
@SpringBootTest(
    classes = SegueApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StreamableHttpTransportTest {

  private static final String PROTOCOL_REVISION = "2025-11-25";
  private static final String SESSION_HEADER = "Mcp-Session-Id";
  private static final String ACCEPT = "application/json, text/event-stream";

  @TempDir static Path tempDir;

  @DynamicPropertySource
  static void isolateDatabase(DynamicPropertyRegistry registry) {
    registry.add("segue.database", () -> tempDir.resolve("streamable-http.db").toString());
  }

  @LocalServerPort int port;

  @Autowired Environment environment;

  private final ObjectMapper json = new ObjectMapper();
  private HttpClient client;

  @BeforeEach
  void openClient() {
    client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
  }

  @Test
  @DisplayName("the embedded server is bound to loopback only")
  void boundToLoopback() {
    assertThat(environment.getProperty("server.address"))
        .as("ADR 28: bind to 127.0.0.1, never 0.0.0.0 — remote exposure is a deliberate change")
        .isEqualTo("127.0.0.1");
  }

  @Test
  @DisplayName("a full MCP session runs over Streamable HTTP and can call a tool")
  void servesAFullMcpSession() throws Exception {
    HttpResponse<String> initialize = post(initializeRequest(), null, null);

    assertThat(initialize.statusCode()).isEqualTo(200);
    String sessionId =
        initialize
            .headers()
            .firstValue(SESSION_HEADER)
            .orElseThrow(() -> new AssertionError("initialize returned no " + SESSION_HEADER));
    JsonNode initializeResult = resultOf(initialize);
    assertThat(initializeResult.at("/result/protocolVersion").asString())
        .as("ADR 27 pins the revision the SDK actually speaks")
        .isEqualTo(PROTOCOL_REVISION);
    assertThat(initializeResult.at("/result/serverInfo/name").asString()).isEqualTo("segue");

    post("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}", sessionId, null);

    HttpResponse<String> toolsList =
        post(
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}",
            sessionId,
            null);
    assertThat(toolsList.statusCode()).isEqualTo(200);
    assertThat(resultOf(toolsList).at("/result/tools"))
        .as("the same five tools ADR 26 exposes on stdio — one surface, two transports")
        .hasSize(5);

    HttpResponse<String> call =
        post(
            "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":"
                + "\"get_entity\",\"arguments\":{\"qid\":\"Q999999999\"}}}",
            sessionId,
            null);
    assertThat(call.statusCode()).isEqualTo(200);
    JsonNode callResult = resultOf(call);
    assertThat(callResult.at("/result/isError").asBoolean())
        .as("ADR 27: a tool execution error is isError: true, not a JSON-RPC error")
        .isTrue();
    assertThat(callResult.at("/result/content").size())
        .as("a client that renders only content must not see a blank result")
        .isGreaterThan(0);
  }

  @Test
  @DisplayName("a request from a foreign Origin is refused with 403")
  void foreignOriginIsRefused() throws Exception {
    HttpResponse<String> response = post(initializeRequest(), null, "https://evil.example");

    assertThat(response.statusCode())
        .as("ADR 28: Origin is validated on every request, 403 on mismatch — DNS rebinding")
        .isEqualTo(403);
  }

  @Test
  @DisplayName("a request from a localhost Origin is allowed")
  void localhostOriginIsAllowed() throws Exception {
    HttpResponse<String> response = post(initializeRequest(), null, "http://localhost:5173");

    assertThat(response.statusCode())
        .as("a local tool served from any localhost port is the case the allowlist exists for")
        .isEqualTo(200);
  }

  /**
   * The Origin check alone does not stop DNS rebinding; this is the half that does.
   *
   * <p>In the attack the victim's browser loads {@code attacker.example}, whose DNS record is then
   * re-pointed at 127.0.0.1. The request is now same-origin as far as the browser is concerned, so
   * it may carry no {@code Origin} header at all — but it still arrives at segue carrying {@code
   * Host: attacker.example}, because that is the name the browser resolved. Refusing a Host that is
   * not loopback is what closes it, and the SDK answers 421 Misdirected Request, which is the
   * status that actually means "this server does not answer for that name".
   *
   * <p>{@code Host} is a restricted header in {@code java.net.http}; {@code build.gradle.kts} sets
   * {@code jdk.httpclient.allowRestrictedHeaders=host} on the test JVM so this can be forged.
   */
  @Test
  @DisplayName("a request for a Host that is not loopback is refused with 421")
  void foreignHostIsRefused() throws Exception {
    HttpResponse<String> response =
        client.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp"))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .header("Accept", ACCEPT)
                .header("Host", "attacker.example")
                .POST(HttpRequest.BodyPublishers.ofString(initializeRequest()))
                .build(),
            HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode())
        .as("a rebound name resolves to loopback but still names itself in Host")
        .isEqualTo(421);
  }

  private HttpResponse<String> post(String body, String sessionId, String origin)
      throws IOException, InterruptedException {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp"))
            .timeout(Duration.ofSeconds(20))
            .header("Content-Type", "application/json")
            .header("Accept", ACCEPT)
            .POST(HttpRequest.BodyPublishers.ofString(body));
    if (sessionId != null) {
      request.header(SESSION_HEADER, sessionId);
    }
    if (origin != null) {
      request.header("Origin", origin);
    }
    return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  /**
   * The JSON-RPC message in a Streamable HTTP response, whichever shape the transport chose.
   *
   * <p>A POST carrying a request may be answered either with a single {@code application/json} body
   * or with an SSE stream whose payload sits behind {@code data:} lines. Both are legal in the same
   * session and the transport picks per response, so a test that assumed one of them would be
   * asserting on an implementation detail rather than on the protocol.
   */
  private JsonNode resultOf(HttpResponse<String> response) {
    String body = response.body();
    Optional<String> sseData =
        body.lines()
            .filter(line -> line.startsWith("data:"))
            .map(line -> line.substring("data:".length()).trim())
            .reduce((first, second) -> second);
    return json.readTree(sseData.orElse(body));
  }

  private static String initializeRequest() {
    return "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{"
        + "\"protocolVersion\":\""
        + PROTOCOL_REVISION
        + "\",\"capabilities\":{},"
        + "\"clientInfo\":{\"name\":\"streamable-http-transport-test\",\"version\":\"0.0.1\"}}}";
  }
}
