package com.robsartin.segue.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.segue.domain.Candidate;
import com.robsartin.segue.domain.NodeAssertion;
import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.domain.Provenance;
import com.robsartin.segue.fixture.Fixture;
import com.robsartin.segue.fixture.FixtureSourceAdapter;
import com.robsartin.segue.ingest.IngestService;
import com.robsartin.segue.port.AffinityStore;
import com.robsartin.segue.port.AssertionLog;
import com.robsartin.segue.port.EntityResolver;
import com.robsartin.segue.port.GraphStore;
import com.robsartin.segue.port.IdentityMerge;
import com.robsartin.segue.port.SourceAdapters;
import com.robsartin.segue.sqlite.SqliteAffinityStore;
import com.robsartin.segue.sqlite.SqliteAssertionLog;
import com.robsartin.segue.tinker.TinkerGraphStore;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;

/**
 * The note does not leave through <b>any</b> MCP tool — not the one somebody remembered (ADR 33 as
 * amended by issue #85).
 *
 * <p>Issue #85 split a boundary that used to run around the whole taste layer: the rating is
 * ordinary data a model may read and weight, and the note is not. The leak it had to close was
 * older than the issue — {@code get_entity} had returned the note since ADR 39 — and the lesson of
 * that leak is the design of this test. A test naming {@code get_entity} would have proved exactly
 * the thing already known and would have said nothing about {@code note_affinity}'s echo of the
 * words it was just handed, or about the seventh tool nobody has written.
 *
 * <p><b>So the surface is discovered, not listed.</b> Every class in {@code
 * com.robsartin.segue.mcp} carrying an {@code @McpTool} method is found by classpath scan,
 * constructed, and every one of its tool methods is called against a store holding one invented
 * note. A new tool class is swept in with no edit here; a tool this cannot construct fails the run
 * rather than being skipped, because a tool that cannot be driven is a tool nothing has proved.
 *
 * <p><b>The instrument is checked before it is believed.</b> A run where every call errored would
 * carry no note and pass vacuously, so {@code get_entity} must come back carrying the rating, and
 * the note must still be in the store when the sweep finishes: what is being proved is that the
 * note stayed behind, not that nothing was stored.
 *
 * <p>The rating, the note and the qids are invented, like everything else in this suite. ADR 33 as
 * amended by issue #37 names a fixture written from real ratings as one of the few ways this public
 * repository could leak the only personal data segue holds.
 */
class NoteNeverLeavesThroughAToolTest {

  /**
   * Invented, and shaped to be unmistakable in a haystack of JSON: nothing else in this project
   * writes this string, so any occurrence anywhere in a tool result is this note escaping.
   */
  private static final String SECRET_NOTE = "invented-note-marker-kwyjibo";

  private static final String RATED = Fixture.CAVE;

  private static final Instant RATED_AT = Instant.parse("2026-08-27T10:00:00Z");

  private AssertionLog log;
  private GraphStore graph;
  private AffinityStore affinity;
  private SegueService service;

  @BeforeEach
  void setUp() {
    log = SqliteAssertionLog.inMemory();
    graph = new TinkerGraphStore();
    affinity = SqliteAffinityStore.inMemory();
    Fixture.seed(graph);
    service =
        new SegueService(
            new StubResolver(),
            graph,
            new IngestService(log, graph, IdentityMerge.NONE),
            new SourceAdapters(List.of(new FixtureSourceAdapter())),
            affinity,
            Clock.fixed(RATED_AT, ZoneOffset.UTC));
  }

  @AfterEach
  void tearDown() {
    affinity.close();
    graph.close();
    log.close();
  }

  @Test
  @DisplayName("no tool on the surface returns the note, and the surface is discovered not listed")
  void noToolReturnsTheNote() {
    service.noteAffinity(RATED, 4, SECRET_NOTE);

    List<Method> tools = toolMethods();
    assertThat(tools)
        .as("every @McpTool method in the mcp package — six today (ADR 26)")
        .hasSizeGreaterThanOrEqualTo(6);

    List<String> results = new ArrayList<>();
    for (Method tool : tools) {
      String wire = wireFormOf(call(tool));
      assertThat(wire)
          .as("the JSON %s puts on the wire must not carry the user's own words", tool.getName())
          .doesNotContain(SECRET_NOTE);
      results.add(wire);
    }

    // The instrument, checked: a sweep in which everything errored would pass the assertion above
    // while proving nothing at all. get_entity is the tool that reads affinity back, so its result
    // has to show the rating it did read.
    assertThat(results)
        .as("get_entity's result, proving the rated entity was really reached")
        .anySatisfy(wire -> assertThat(wire).contains(RATED).contains("\"rating\":4"));

    // And the note was stored all along — this proves it stayed behind, not that it never existed.
    assertThat(affinity.find(RATED)).get().extracting("note").isEqualTo(SECRET_NOTE);
  }

  @Test
  @DisplayName("the tool that is handed a note does not echo it back")
  void theWritingToolDoesNotEchoTheNote() {
    // The arch rule cannot see this one: note_affinity's note arrives as a parameter rather than
    // from the store, so nothing it returns has to call AffinityRecord.note() to leak it. The
    // caller supplied the words, but the caller is a model and the result is context that leaves
    // the machine, so the surface says the same thing on the way out as it does everywhere else.
    CallToolResult result =
        new TasteTools(service).noteAffinity(RATED, 5, SECRET_NOTE + " on the way in");

    assertThat(wireFormOf(result)).doesNotContain(SECRET_NOTE);
  }

  /** Drive one tool method, giving every parameter the least evasive value that fits it. */
  private CallToolResult call(Method tool) {
    Object instance;
    try {
      Constructor<?> constructor = tool.getDeclaringClass().getDeclaredConstructors()[0];
      Object[] wiring =
          Arrays.stream(constructor.getParameterTypes())
              .map(type -> type == SegueService.class ? service : type == int.class ? 25 : null)
              .toArray();
      instance = constructor.newInstance(wiring);
    } catch (ReflectiveOperationException | RuntimeException e) {
      throw new AssertionError(
          "cannot construct "
              + tool.getDeclaringClass().getSimpleName()
              + " to drive it — a tool this test cannot call is a tool nothing has proved",
          e);
    }
    Object[] args = Arrays.stream(tool.getParameters()).map(this::argumentFor).toArray();
    try {
      return (CallToolResult) tool.invoke(instance, args);
    } catch (ReflectiveOperationException e) {
      throw new AssertionError("calling " + tool.getName() + " failed", e);
    }
  }

  /**
   * A qid for anything qid-shaped, the invented note for the note, a valid rating for the rating.
   *
   * <p>Parameter names are load-bearing here, and they exist because Spring Boot's Gradle plugin
   * compiles with {@code -parameters} — the same fact the MCP schema generation depends on. {@link
   * #parameterNamesSurviveCompilation()} is what stops this degrading into "every String gets a
   * qid" if that ever stops being true.
   */
  private Object argumentFor(Parameter parameter) {
    if (parameter.getType() == String.class) {
      return parameter.getName().toLowerCase().contains("note") ? SECRET_NOTE : RATED;
    }
    if (parameter.getType() == int.class) {
      return 4;
    }
    return null;
  }

  @Test
  @DisplayName("parameter names survive compilation, so the note parameter can be recognised")
  void parameterNamesSurviveCompilation() {
    List<String> names =
        toolMethods().stream()
            .flatMap(tool -> Arrays.stream(tool.getParameters()))
            .map(Parameter::getName)
            .toList();

    assertThat(names)
        .as("compiled without -parameters, every tool argument would be a qid by accident")
        .contains("qid", "note")
        .doesNotContain("arg0");
  }

  private static String wireFormOf(CallToolResult result) {
    String text =
        result.content().stream()
            .filter(TextContent.class::isInstance)
            .map(content -> ((TextContent) content).text())
            .reduce("", (a, b) -> a + b);
    return text + String.valueOf(result.structuredContent());
  }

  /**
   * Every {@code @McpTool} method on every class in the mcp package — scanned rather than named, so
   * a seventh tool is covered by this test on the day it is written.
   */
  private static List<Method> toolMethods() {
    ClassPathScanningCandidateComponentProvider scanner =
        new ClassPathScanningCandidateComponentProvider(false);
    scanner.addIncludeFilter((reader, factory) -> true);
    return scanner.findCandidateComponents("com.robsartin.segue.mcp").stream()
        .map(definition -> loadClass(definition.getBeanClassName()))
        .flatMap(type -> Arrays.stream(type.getDeclaredMethods()))
        .filter(method -> method.isAnnotationPresent(McpTool.class))
        .toList();
  }

  private static Class<?> loadClass(String name) {
    try {
      return Class.forName(name);
    } catch (ClassNotFoundException e) {
      throw new AssertionError("scanned a class that will not load: " + name, e);
    }
  }

  /** Enough of a resolver to let search_entities and add_entity run against the fixture. */
  private static final class StubResolver implements EntityResolver {

    private static final Provenance INVENTED =
        new Provenance("invented", "invented:1", RATED_AT, 1.0);

    @Override
    public String id() {
      return "invented";
    }

    @Override
    public List<Candidate> search(String query, NodeKind kind, int limit) {
      return List.of(new Candidate(RATED, "An Invented Act", "an invented act", NodeKind.PERSON));
    }

    @Override
    public Optional<NodeAssertion> fetch(String qid) {
      return Optional.of(new NodeAssertion(RATED, NodeKind.PERSON, "An Invented Act", INVENTED));
    }
  }
}
