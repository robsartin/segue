package com.robsartin.segue.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.segue.app.SegueApplication;
import com.robsartin.segue.domain.PathRanking;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.server.common.autoconfigure.annotations.McpServerAnnotationScannerAutoConfiguration.ServerMcpAnnotatedBeans;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Proves the six-tool surface (ADR 26) without starting a transport — no stdio process, no
 * listening socket, just Spring context refresh.
 *
 * <p>Discovery is confirmed two ways, and deliberately not just one:
 *
 * <ul>
 *   <li>{@link #toolBeansAreDiscoveredByTheStarterMechanism()} autowires the starter's own {@code
 *       ServerMcpAnnotatedBeans} registry — the exact object the {@code
 *       ServerAnnotatedMethodBeanPostProcessor} populates during context refresh (see the task-7
 *       report for how that mechanism was found by reading {@code
 *       spring-ai-autoconfigure-mcp-server-common:2.0.1}, not guessed). If {@link EntityTools} or
 *       {@link GraphTools} stopped being ordinary Spring beans — the only thing that
 *       BeanPostProcessor cares about — this is the test that would notice.
 *   <li>Everything else here is plain reflection over the {@code @McpTool} annotations, which is
 *       the right tool for asserting on annotation attribute VALUES (names, descriptions, {@code
 *       annotations}) — ArchUnit's structural rules are not built for reading annotation string
 *       content, which is also why no ArchUnit rule was added or extended: there was no existing
 *       tool-name rule to extend, and inspecting an annotation's own attributes is a reflection
 *       problem, not a dependency-structure one.
 * </ul>
 *
 * <p><b>{@code generateOutputSchema} is asserted FALSE, not true</b> — the reverse of increment
 * 4a's original intent. With it true, Spring AI's {@code SyncMcpToolProvider} puts every tool on
 * its STRUCTURED-mode path, whose {@code convertValueToCallToolResult} builds only {@code
 * structuredContent}: {@code content} came back {@code []} and {@code isError} was always {@code
 * false}, even for the errors this project deliberately models as {@code outcome: "error"} —
 * inverting ADR 27. Every {@code @McpTool} method here returns {@link
 * io.modelcontextprotocol.spec.McpSchema.CallToolResult} directly instead (see {@link
 * ToolResults}), which is the return type {@code SyncMcpToolProvider} recognises as already being
 * the protocol's own result and skips schema generation for entirely. See the final-fix report for
 * increment 4a (FIX 1) and the ADR 26 amendment.
 */
@SpringBootTest(classes = SegueApplication.class)
class ToolSurfaceTest {

  /** MCP tool names: ASCII letters, digits, underscore, hyphen, dot — nothing else. */
  private static final Pattern VALID_TOOL_NAME = Pattern.compile("[A-Za-z0-9_.-]+");

  /**
   * A generous, deliberately round bound; MCP does not fix an exact number, but every real client
   * caps tool names well under this.
   */
  private static final int MAX_NAME_LENGTH = 128;

  @Autowired private ServerMcpAnnotatedBeans annotatedBeans;

  @Test
  @DisplayName("EntityTools and GraphTools are found by the starter's real annotation scanner")
  void toolBeansAreDiscoveredByTheStarterMechanism() {
    List<Object> beansWithMcpTools = annotatedBeans.getBeansByAnnotation(McpTool.class);

    assertThat(beansWithMcpTools)
        .as("beans the ServerAnnotatedMethodBeanPostProcessor found carrying @McpTool methods")
        .extracting(Object::getClass)
        .contains(EntityTools.class, GraphTools.class, TasteTools.class);
  }

  @Test
  @DisplayName("exactly the six tools ADR 26 specifies exist, named exactly")
  void sixToolsWithTheSpecifiedNames() {
    assertThat(allTools())
        .extracting(McpTool::name)
        .containsExactlyInAnyOrder(
            "search_entities",
            "add_entity",
            "expand_entity",
            "get_entity",
            "find_paths",
            "note_affinity");
  }

  @Test
  @DisplayName("the taste layer adds one tool and no more - the read is not a seventh")
  void theTasteLayerAddsOneToolAndNoMore() {
    // ADR 39 exposes reading affinity on get_entity rather than as a seventh tool, precisely so
    // that this count stays at ADR 26's six. A get_affinity or list_affinity appearing here is
    // an ADR-level change, and should fail this test until an ADR says otherwise.
    assertThat(allTools()).hasSize(6);
    assertThat(allTools())
        .extracting(McpTool::name)
        .doesNotContain("get_affinity", "list_affinity");
  }

  @Test
  @DisplayName("note_affinity is annotated as a write: not read-only, not destructive, idempotent")
  void noteAffinityIsAnnotatedAccordingly() {
    // Idempotent because ADR 39 chose overwrite: sending the same rating twice leaves exactly
    // the state one call would have left, give or take the updated-at stamp.
    McpTool.McpAnnotations annotations = toolNamed("note_affinity").annotations();
    assertThat(annotations.readOnlyHint()).isFalse();
    assertThat(annotations.destructiveHint()).isFalse();
    assertThat(annotations.idempotentHint()).isTrue();
  }

  @Test
  @DisplayName("note_affinity's description states the 1-5 scale and the add_entity prerequisite")
  void noteAffinityDescriptionStatesTheScaleAndThePrerequisite() {
    String description = toolNamed("note_affinity").description();

    assertThat(description).contains("1").contains("5");
    assertThat(description).containsIgnoringCase("add_entity");
  }

  @Test
  @DisplayName("get_entity's description tells a model that it is where affinity is read back")
  void getEntityDescriptionMentionsAffinity() {
    // The read path is only discoverable from the schema if the tool carrying it says so; a
    // model that never learns get_entity returns affinity will never look for it there.
    assertThat(toolNamed("get_entity").description()).containsIgnoringCase("affinity");
  }

  @Test
  @DisplayName("find_paths' description warns that a dense pair's result can be capped")
  void findPathsDescriptionStatesTheCap() {
    // Issue #65. The result now reports its own truncation, but a model plans the call from
    // the schema — and this description opened with "Find every route", which is a promise the
    // tool cannot keep on a dense pair. expand_entity's description already says it will report
    // stopping early; this one has to as well, or the two tools tell different stories about
    // the same kind of shortfall.
    String description = toolNamed("find_paths").description();

    assertThat(description).contains(String.valueOf(PathRanking.MAX_PATHS));
    assertThat(description).containsIgnoringCase("best-ranked");
  }

  @Test
  @DisplayName("every tool name matches MCP's naming charset and length bound")
  void namesMatchMcpCharsetRules() {
    for (McpTool tool : allTools()) {
      assertThat(tool.name()).as("tool name").matches(VALID_TOOL_NAME);
      assertThat(tool.name().length())
          .as("length of '%s'", tool.name())
          .isLessThanOrEqualTo(MAX_NAME_LENGTH);
    }
  }

  @Test
  @DisplayName(
      "every tool declares a non-blank description and does NOT ask Spring AI to generate a"
          + " schema (FIX 1 — see the class Javadoc)")
  void everyToolHasADescriptionAndSkipsFrameworkSchemaGeneration() {
    for (McpTool tool : allTools()) {
      assertThat(tool.description()).as("description of %s", tool.name()).isNotBlank();
      assertThat(tool.generateOutputSchema())
          .as(
              "generateOutputSchema of %s must stay false — true routes the tool onto the"
                  + " STRUCTURED-mode path that drops isError (FIX 1)",
              tool.name())
          .isFalse();
    }
  }

  @Test
  @DisplayName("every tool returns CallToolResult directly, opting out of framework conversion")
  void everyToolReturnsCallToolResultDirectly() {
    for (Method method : toolMethods().toList()) {
      assertThat(method.getReturnType())
          .as("return type of %s", method.getName())
          .isEqualTo(io.modelcontextprotocol.spec.McpSchema.CallToolResult.class);
    }
  }

  @Test
  @DisplayName("read-only tools are annotated readOnlyHint / not destructive / idempotent")
  void readOnlyToolsAreAnnotatedAccordingly() {
    for (String name : List.of("search_entities", "get_entity", "find_paths")) {
      McpTool.McpAnnotations annotations = toolNamed(name).annotations();
      assertThat(annotations.readOnlyHint()).as("%s readOnlyHint", name).isTrue();
      assertThat(annotations.destructiveHint()).as("%s destructiveHint", name).isFalse();
      assertThat(annotations.idempotentHint()).as("%s idempotentHint", name).isTrue();
    }
  }

  @Test
  @DisplayName("add_entity is annotated non-destructive and idempotent, but not read-only")
  void addEntityIsAnnotatedAccordingly() {
    McpTool.McpAnnotations annotations = toolNamed("add_entity").annotations();
    assertThat(annotations.readOnlyHint()).isFalse();
    assertThat(annotations.destructiveHint()).isFalse();
    assertThat(annotations.idempotentHint()).isTrue();
  }

  @Test
  @DisplayName("expand_entity is annotated non-destructive, non-idempotent, and not read-only")
  void expandEntityIsAnnotatedAccordingly() {
    McpTool.McpAnnotations annotations = toolNamed("expand_entity").annotations();
    assertThat(annotations.readOnlyHint()).isFalse();
    assertThat(annotations.destructiveHint()).isFalse();
    assertThat(annotations.idempotentHint()).isFalse();
  }

  @Test
  @DisplayName("search_entities' description states plainly that kind does not filter")
  void searchEntitiesDescriptionDisclaimsTheKindFilter() {
    McpTool searchEntities =
        allTools().stream()
            .filter(tool -> tool.name().equals("search_entities"))
            .findFirst()
            .orElseThrow();

    assertThat(searchEntities.description())
        .as(
            "search_entities must warn that kind is not applied as a filter (see CLAUDE.md's"
                + " wbsearchentities gotcha and ADR 26)")
        .containsIgnoringCase("kind")
        .containsIgnoringCase("does not filter");
  }

  @Test
  @DisplayName("assert_edge is deliberately absent, per ADR 26")
  void assertEdgeIsNotAToolYet() {
    assertThat(allTools()).extracting(McpTool::name).doesNotContain("assert_edge");
  }

  private static List<McpTool> allTools() {
    return toolMethods().map(method -> method.getAnnotation(McpTool.class)).toList();
  }

  /** Every {@code @McpTool} method on the three tool classes - the whole published surface. */
  private static Stream<Method> toolMethods() {
    return Stream.of(EntityTools.class, GraphTools.class, TasteTools.class)
        .flatMap(type -> Arrays.stream(type.getDeclaredMethods()))
        .filter(method -> method.getAnnotation(McpTool.class) != null);
  }

  private static McpTool toolNamed(String name) {
    return allTools().stream().filter(tool -> tool.name().equals(name)).findFirst().orElseThrow();
  }
}
