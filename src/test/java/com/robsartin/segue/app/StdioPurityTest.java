package com.robsartin.segue.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assertions.fail;

import com.robsartin.segue.domain.AssertionRecord;
import com.robsartin.segue.domain.NodeAssertion;
import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.domain.Provenance;
import com.robsartin.segue.port.AssertionLog;
import com.robsartin.segue.sqlite.SqliteAssertionLog;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

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
 *
 * <p><b>Issue #23:</b> every one of those {@code tools/call} requests takes the ERROR path, and an
 * error {@code ToolResult} carries {@code payload = null} by construction — so no {@code PathView},
 * no {@code ProvenanceView} and no {@code java.time.Instant} has ever reached the mapper from this
 * test. That is exactly how issue #18, a serialisation defect inside {@code find_paths}, survived a
 * green build despite an end-to-end test calling the broken tool over the real transport: the one
 * input shape that could have caught it was the one shape never sent. {@link
 * #successfulFindPathsSurvivesTheStdioRoundTrip} closes that gap.
 */
class StdioPurityTest {

  private static final String PROTOCOL_REVISION = "2025-11-25";
  private static final Duration STARTUP_TIMEOUT = Duration.ofSeconds(30);
  private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(15);

  // Placeholder QIDs in the same Q9000xx range the test fixture uses, and for the same reason:
  // they cannot collide with a real Wikidata identifier. See Fixture's Javadoc.
  private static final String CAVE = "Q900001";
  private static final String PROPOSITION = "Q900009";
  private static final String HILLCOAT = "Q900010";

  /** Fixed, so the round-tripped timestamp can be compared rather than merely shape-checked. */
  private static final Instant SEEDED_AT = Instant.parse("2026-08-01T09:00:00.123456Z");

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
    assertThat(toolsListResponse.at("/result/tools")).as("six MCP tools registered").hasSize(6);

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

  /**
   * The success path over the real transport (issue #23): a {@code find_paths} that returns routes.
   *
   * <p>The test above cannot catch a serialisation defect in {@code find_paths} however carefully
   * it asserts, because both of its {@code tools/call} requests take the error path and an error
   * result's payload is null — nothing carrying an {@code Instant} is ever written. This test is
   * the one that puts a real {@code PathView} — hops, edges, and the {@code ProvenanceView}
   * timestamp that broke in issue #18 — through the serialiser, the MCP SDK's own framing, the
   * stdio pipe, and back out as text this JVM parses.
   *
   * <p><b>How the graph gets populated with no network call.</b> A successful {@code find_paths}
   * needs entities in the graph, and the only tools that put them there — {@code add_entity} and
   * {@code expand_entity} — both call Wikidata. The live tests are tagged and excluded from {@code
   * check} for a good reason (a recorded fixture cannot tell you the upstream API changed), so this
   * does not go near them. Instead it writes an assertion log directly — three nodes and two edges
   * into a temporary SQLite file — and points the subprocess at it with {@code -Dsegue.database}.
   * The boot projection ({@code GraphProjector}, ADR 19/ADR 24) rebuilds the graph from that log
   * exactly as it does for a real database, so the server under test reached its populated state
   * through production code rather than a test-only back door.
   *
   * <p>The alternative considered was injecting a stub Wikidata endpoint into the subprocess so the
   * handshake could {@code add_entity} for real. That needs a production wiring seam whose only
   * purpose is to be overridden by a test, and it would still end up loading the graph through this
   * same projection — so it costs main-source complexity to reach the same place.
   *
   * <p>The seeded route is deliberately two hops (Cave → The Proposition → Hillcoat, joined by two
   * different creative-role edges), so "every hop carries provenance" has more than one hop to be
   * true of. {@code maxHops} is pinned to 2 rather than left to default, so the assertion on the
   * route's length cannot start passing for the wrong reason if the default changes.
   */
  @Test
  void successfulFindPathsSurvivesTheStdioRoundTrip(@TempDir Path tempDir) throws Exception {
    Path database = tempDir.resolve("stdio-find-paths-test.db");
    seedTwoHopRoute(database);

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

    sendLine(
        stdin,
        "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":"
            + "\"find_paths\",\"arguments\":{\"fromQid\":\""
            + CAVE
            + "\",\"toQid\":\""
            + HILLCOAT
            + "\",\"maxHops\":2}}}");
    awaitLineCount(stdout, 2, RESPONSE_TIMEOUT, "the find_paths tools/call response");

    process.destroy();
    stdoutReader.join(5_000);
    stderrReader.join(5_000);

    ObjectMapper mapper = new ObjectMapper();
    JsonNode response = responseWithId(mapper, stdout, 2);

    assertThat(response.at("/result/isError").asBoolean())
        .as("find_paths between two seeded, connected entities must succeed: %s", response)
        .isFalse();
    assertThat(response.at("/result/content").size())
        .as("content must be non-empty — a client that renders only content must not see blank")
        .isGreaterThan(0);
    assertThat(response.at("/result/structuredContent/payload").size())
        .as("structuredContent must carry at least one route: %s", response)
        .isGreaterThan(0);

    JsonNode hops = response.at("/result/structuredContent/payload/0/hops");
    assertThat(hops.size()).as("the seeded route is two hops long: %s", response).isEqualTo(2);
    for (JsonNode hop : hops) {
      JsonNode sources = hop.at("/edge/sources");
      assertThat(sources.size())
          .as("every hop must be citable — an uncited hop is the payoff feature missing: %s", hop)
          .isGreaterThan(0);
      for (JsonNode source : sources) {
        JsonNode assertedAt = source.get("assertedAt");
        // The exact shape issue #18 got wrong. A mapper without java.time support either throws
        // while writing this value or emits an epoch number; insisting on a parseable ISO-8601
        // string is what distinguishes a citable timestamp from a number a reader cannot cite.
        assertThat(assertedAt.isString())
            .as("assertedAt must reach the wire as an ISO-8601 string, not a number: %s", source)
            .isTrue();
        assertThat(Instant.parse(assertedAt.stringValue()))
            .as("and must survive the round trip unchanged, to sub-second precision")
            .isEqualTo(SEEDED_AT);
      }
    }
  }

  /**
   * The taste layer over the real transport, across a real restart (increment 5, ADR 39).
   *
   * <p>Two subprocesses against one database file. The first records an affinity with {@code
   * note_affinity}; it is then destroyed, taking its whole JVM — the Gremlin graph, the SQLite
   * connections, every cache — with it. The second boots from nothing but the file on disk and is
   * asked for the same entity with {@code get_entity}.
   *
   * <p>Why this is not covered by {@code SqliteAffinityStoreTest.persistsAcrossReopen}: that test
   * reopens one class. This one proves the wiring — that the server writes affinity to the SAME
   * file the graph is projected from, that the record survives a process boundary rather than an
   * object lifetime, and that the read path a model actually calls returns it afterwards. An
   * affinity written to an in-memory store, or to a second file nobody points at on restart, passes
   * every unit test in this repository and fails this one.
   *
   * <p>The graph is seeded through the assertion log for the same reason as the test above: {@code
   * add_entity} would call Wikidata, and ADR 39 requires the entity to be in the graph before it
   * can be rated. The rating and the note are invented — the repository is public and affinity is
   * personal data (ADR 33, as amended by issue #37).
   */
  @Test
  void affinityRecordedOverStdioSurvivesARestart(@TempDir Path tempDir) throws Exception {
    Path database = tempDir.resolve("stdio-affinity-test.db");
    seedTwoHopRoute(database);
    String note = "invented note, written by the test suite";

    List<String> firstRun = new CopyOnWriteArrayList<>();
    JsonNode noted =
        oneStdioSession(
            database,
            firstRun,
            stdin ->
                sendLine(
                    stdin,
                    "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":"
                        + "\"note_affinity\",\"arguments\":{\"qid\":\""
                        + CAVE
                        + "\",\"rating\":4,\"note\":\""
                        + note
                        + "\"}}}"));

    assertThat(noted.at("/result/isError").asBoolean())
        .as("note_affinity on a seeded entity must succeed: %s", noted)
        .isFalse();
    assertThat(noted.at("/result/structuredContent/payload/rating").asInt()).isEqualTo(4);

    // A second JVM, the same file, nothing shared but the bytes on disk.
    List<String> secondRun = new CopyOnWriteArrayList<>();
    JsonNode readBack =
        oneStdioSession(
            database,
            secondRun,
            stdin ->
                sendLine(
                    stdin,
                    "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":"
                        + "\"get_entity\",\"arguments\":{\"qid\":\""
                        + CAVE
                        + "\"}}}"));

    JsonNode affinity = readBack.at("/result/structuredContent/payload/affinity");
    assertThat(affinity.isMissingNode() || affinity.isNull())
        .as("get_entity must carry the affinity recorded before the restart: %s", readBack)
        .isFalse();
    assertThat(affinity.get("rating").asInt()).isEqualTo(4);
    assertThat(affinity.get("note").stringValue()).isEqualTo(note);
    assertThat(Instant.parse(affinity.get("updatedAt").stringValue()))
        .as("updatedAt must round-trip as a parseable ISO-8601 instant")
        .isNotNull();

    // ADR 33: the rating must not have leaked into the log the graph is rebuilt from. The second
    // server replayed that log at boot and still found the graph exactly as it was seeded.
    try (AssertionLog log = new SqliteAssertionLog(database)) {
      assertThat(log.readAll())
          .as("the assertion log must hold only the five seeded claims, and no rating")
          .hasSize(5);
    }
  }

  /**
   * Run one stdio server against {@code database}, do the handshake, send one request with id 2,
   * and return its parsed response — then destroy the process.
   */
  private JsonNode oneStdioSession(Path database, List<String> stdout, StdinAction request)
      throws Exception {
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

    request.send(stdin);
    awaitLineCount(stdout, 2, RESPONSE_TIMEOUT, "the tools/call response");

    process.destroy();
    stdoutReader.join(5_000);
    stderrReader.join(5_000);

    return responseWithId(new ObjectMapper(), stdout, 2);
  }

  /** One request written to a running server's stdin. */
  @FunctionalInterface
  private interface StdinAction {
    void send(OutputStream stdin) throws IOException;
  }

  /**
   * Write the assertion log the subprocess will boot from: Cave → The Proposition ← Hillcoat.
   *
   * <p>Written through {@link SqliteAssertionLog} rather than as raw SQL on purpose — the schema is
   * that class's business, and a test that hand-rolled the {@code INSERT} would keep passing after
   * the log's storage format changed while the real server could no longer read the file. The
   * connection is closed before the subprocess starts, so the two never hold the file at once.
   *
   * <p>Nothing is written to a {@code GraphStore} here. The subprocess builds its own graph by
   * replaying this log at boot, which is the point: the route the test asks for exists only if the
   * production projection path works.
   *
   * <p>The file lives under the test's {@code @TempDir}, which JUnit deletes afterwards — including
   * the {@code -wal}/{@code -shm} companions SQLite may leave beside it.
   */
  private static void seedTwoHopRoute(Path database) {
    Provenance provenance = new Provenance("wikidata", "S-stdio-seed", SEEDED_AT, 0.8);
    try (AssertionLog log = new SqliteAssertionLog(database)) {
      log.append(new NodeAssertion(CAVE, NodeKind.PERSON, "Nick Cave", provenance));
      log.append(new NodeAssertion(PROPOSITION, NodeKind.WORK, "The Proposition", provenance));
      log.append(new NodeAssertion(HILLCOAT, NodeKind.PERSON, "John Hillcoat", provenance));
      // Two different creative roles, so neither hop is a mirror of the other. Both point at the
      // work, because Wikidata states creative relations on the work — see CLAUDE.md's gotchas.
      log.append(new AssertionRecord(CAVE, PROPOSITION, "COMPOSED_FOR", null, null, provenance));
      log.append(new AssertionRecord(HILLCOAT, PROPOSITION, "DIRECTED", null, null, provenance));
    }
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
