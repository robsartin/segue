package com.robsartin.segue.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The test that actually protects the protocol (ADR 28).
 *
 * <p>The ArchUnit rule {@code nothingWritesToStandardOut} cannot see into a misbehaving dependency
 * or into the framework's own startup output — it only sees this project's source. Only running the
 * process for real can catch that. This launches the built application exactly as an MCP client
 * would: a subprocess, the {@code stdio} profile active, a JSON-RPC request on its stdin — and
 * asserts that every single line landing on its stdout is valid JSON, while its logs land on stderr
 * instead.
 *
 * <p>The subprocess is given the current build's compiled classes and dependency jars via {@code
 * segue.mainRuntimeClasspath}, a system property {@code build.gradle.kts} sets from {@code
 * sourceSets["main"].runtimeClasspath}. That is what the {@code test} task just compiled — no
 * separate {@code bootJar} build required to exercise this property, and no risk of testing a stale
 * jar left over from a previous build.
 *
 * <p>An empty stdout would make every assertion below pass vacuously, so this does a real MCP
 * handshake (bare {@code tools/list} with no prior {@code initialize} gets no response at all —
 * confirmed by hand while writing this test) before checking anything: {@code initialize}, then the
 * {@code notifications/initialized} notification, then {@code tools/list}. Two response lines are
 * required, not just "no line was invalid."
 *
 * <p><b>FIX 6 of the increment-4a final review:</b> the handshake used to stop at {@code
 * tools/list}, so the Gremlin traversal, the SQLite write path and the whole {@code SegueService}
 * call path — precisely the libraries most likely to print something unexpected — were never
 * exercised while stdout was being observed. Two {@code tools/call} requests now follow: {@code
 * get_entity} on a QID nothing has added, and {@code find_paths} on two QIDs neither of which
 * exists. Both are offline (no Wikidata call — {@code get_entity} and {@code find_paths} only read
 * the local graph) and deterministic (an unknown qid always errors the same way), and both exercise
 * the code paths FIX 1 was about: this test would have caught {@code content: []}/{@code isError:
 * false} on a genuine error result, which the handshake alone never could.
 */
class StdioPurityTest {

  private static final String PROTOCOL_REVISION = "2025-11-25";
  private static final Duration STARTUP_TIMEOUT = Duration.ofSeconds(30);
  private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(15);

  private Process process;

  @AfterEach
  void tearDown() {
    if (process != null && process.isAlive()) {
      process.destroyForcibly();
    }
  }

  @Test
  void everyStdoutLineParsesAsJsonAndLogsGoToStderr(@TempDir Path tempDir) throws Exception {
    Path database = tempDir.resolve("stdio-purity-test.db");
    List<String> stdout = new CopyOnWriteArrayList<>();
    List<String> stderr = new CopyOnWriteArrayList<>();

    process = launchStdioServer(database);
    Thread stdoutReader = drain(process.getInputStream(), stdout);
    Thread stderrReader = drain(process.getErrorStream(), stderr);
    stdoutReader.start();
    stderrReader.start();

    awaitStderrContains(stderr, "Started SegueApplication", STARTUP_TIMEOUT);

    OutputStream stdin = process.getOutputStream();
    sendLine(stdin, initializeRequest());
    awaitLineCount(stdout, 1, RESPONSE_TIMEOUT, "the initialize response");

    sendLine(stdin, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
    sendLine(stdin, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}");
    awaitLineCount(stdout, 2, RESPONSE_TIMEOUT, "the tools/list response");

    // FIX 6: exercise the Gremlin/SQLite/SegueService call path — not just the handshake —
    // while stdout is being observed. Both calls are offline and deterministic: neither qid
    // was ever added, so both must error rather than call out to Wikidata.
    sendLine(
        stdin,
        "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":"
            + "\"get_entity\",\"arguments\":{\"qid\":\"Q999999999\"}}}");
    awaitLineCount(stdout, 3, RESPONSE_TIMEOUT, "the get_entity tools/call response");

    sendLine(
        stdin,
        "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\",\"params\":{\"name\":"
            + "\"find_paths\",\"arguments\":{\"fromQid\":\"Q999999998\",\"toQid\":"
            + "\"Q999999997\"}}}");
    awaitLineCount(stdout, 4, RESPONSE_TIMEOUT, "the find_paths tools/call response");

    process.destroy();
    stdoutReader.join(5_000);
    stderrReader.join(5_000);

    assertThat(stdout)
        .as(
            "the handshake plus both tools/call requests produced four responses"
                + " (initialize, tools/list, get_entity, find_paths)")
        .hasSizeGreaterThanOrEqualTo(4);

    ObjectMapper mapper = new ObjectMapper();
    for (String line : stdout) {
      assertThatCode(() -> mapper.readTree(line))
          .as(
              "every line on stdout must be a single valid JSON value — the transport is stdio, "
                  + "and one bad line corrupts the whole stream. Offending line: %s",
              line)
          .doesNotThrowAnyException();
    }

    // Matched by the request id (2), not by the substring "tools" — the initialize response
    // already contains a "tools" capability flag and would false-match a naive filter.
    JsonNode toolsListResponse =
        mapper.readTree(
            stdout.stream()
                .filter(line -> line.contains("\"id\":2"))
                .findFirst()
                .orElseThrow(
                    () -> new AssertionError("no stdout line carried the tools/list result")));
    assertThat(toolsListResponse.at("/result/tools")).as("five MCP tools registered").hasSize(5);

    // FIX 1/FIX 6: a genuine error result must carry isError: true and a non-empty content
    // block — this is exactly what content: [], isError: false (the bug FIX 1 fixed) would
    // have failed.
    JsonNode getEntityResponse = responseWithId(mapper, stdout, 3);
    assertThat(getEntityResponse.at("/result/isError").asBoolean())
        .as("get_entity on a never-added qid must report isError: true")
        .isTrue();
    assertThat(getEntityResponse.at("/result/content").size())
        .as("content must be non-empty — a client that renders only content must not see blank")
        .isGreaterThan(0);

    JsonNode findPathsResponse = responseWithId(mapper, stdout, 4);
    assertThat(findPathsResponse.at("/result/isError").asBoolean())
        .as("find_paths on two never-added qids must report isError: true (FIX 8)")
        .isTrue();
    assertThat(findPathsResponse.at("/result/content").size())
        .as("content must be non-empty — a client that renders only content must not see blank")
        .isGreaterThan(0);

    assertThat(stderr)
        .as("stderr must carry the application's logs — proves logging did not silently vanish")
        .anyMatch(line -> line.contains("Started SegueApplication"));
  }

  /** The stdout line carrying the response to request {@code id}, parsed as JSON. */
  private static JsonNode responseWithId(ObjectMapper mapper, List<String> stdout, int id)
      throws IOException {
    for (String line : stdout) {
      JsonNode node = mapper.readTree(line);
      if (node.has("id") && node.get("id").asInt() == id) {
        return node;
      }
    }
    throw new AssertionError("no stdout line carried a response with id " + id + ": " + stdout);
  }

  private Process launchStdioServer(Path database) throws IOException {
    String classpath = System.getProperty("segue.mainRuntimeClasspath");
    assertThat(classpath)
        .as(
            "segue.mainRuntimeClasspath system property, set by build.gradle.kts' tasks.test —"
                + " this test cannot launch the application without it")
        .isNotBlank();

    String javaExecutable = System.getProperty("java.home") + "/bin/java";
    ProcessBuilder builder =
        new ProcessBuilder(
            javaExecutable,
            "--enable-native-access=ALL-UNNAMED",
            "-cp",
            classpath,
            "-Dspring.profiles.active=stdio",
            "-Dsegue.database=" + database,
            "com.robsartin.segue.app.SegueApplication");
    builder.redirectErrorStream(false);
    return builder.start();
  }

  private static String initializeRequest() {
    return "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{"
        + "\"protocolVersion\":\""
        + PROTOCOL_REVISION
        + "\",\"capabilities\":{},"
        + "\"clientInfo\":{\"name\":\"stdio-purity-test\",\"version\":\"0.0.1\"}}}";
  }

  private static void sendLine(OutputStream stdin, String line) throws IOException {
    stdin.write((line + "\n").getBytes(StandardCharsets.UTF_8));
    stdin.flush();
  }

  private static Thread drain(InputStream in, List<String> sink) {
    Thread thread =
        new Thread(
            () -> {
              try (BufferedReader reader =
                  new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                  if (!line.isBlank()) {
                    sink.add(line);
                  }
                }
              } catch (IOException expectedWhenProcessIsDestroyed) {
                // The subprocess is torn down in tearDown(); its streams close underneath us.
              }
            });
    thread.setDaemon(true);
    return thread;
  }

  private static void awaitStderrContains(List<String> stderr, String marker, Duration timeout)
      throws InterruptedException {
    Instant deadline = Instant.now().plus(timeout);
    while (Instant.now().isBefore(deadline)) {
      if (stderr.stream().anyMatch(line -> line.contains(marker))) {
        return;
      }
      Thread.sleep(50);
    }
    fail("server did not log '" + marker + "' to stderr within " + timeout);
  }

  private static void awaitLineCount(
      List<String> stdout, int minimumLines, Duration timeout, String awaiting)
      throws InterruptedException {
    Instant deadline = Instant.now().plus(timeout);
    while (Instant.now().isBefore(deadline)) {
      if (stdout.size() >= minimumLines) {
        return;
      }
      Thread.sleep(50);
    }
    fail("timed out waiting for " + awaiting + "; stdout so far: " + stdout);
  }
}
