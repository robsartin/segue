package com.robsartin.segue.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.segue.app.SegueApplication;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.server.common.autoconfigure.annotations.McpServerAnnotationScannerAutoConfiguration.ServerMcpAnnotatedBeans;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Proves the five-tool surface (ADR 26) without starting a transport — no stdio process, no
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
 *       the right tool for asserting on annotation attribute VALUES (names, descriptions,
 *       generateOutputSchema) — ArchUnit's structural rules are not built for reading annotation
 *       string content, which is also why no ArchUnit rule was added or extended: there was no
 *       existing tool-name rule to extend, and inspecting an annotation's own attributes is a
 *       reflection problem, not a dependency-structure one.
 * </ul>
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
        .contains(EntityTools.class, GraphTools.class);
  }

  @Test
  @DisplayName("exactly the five tools ADR 26 specifies exist, named exactly")
  void fiveToolsWithTheSpecifiedNames() {
    assertThat(allTools())
        .extracting(McpTool::name)
        .containsExactlyInAnyOrder(
            "search_entities", "add_entity", "expand_entity", "get_entity", "find_paths");
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
  @DisplayName("every tool declares a non-blank description and requests an output schema")
  void everyToolHasADescriptionAndRequestsAnOutputSchema() {
    for (McpTool tool : allTools()) {
      assertThat(tool.description()).as("description of %s", tool.name()).isNotBlank();
      assertThat(tool.generateOutputSchema())
          .as("generateOutputSchema of %s — required by ADR 26", tool.name())
          .isTrue();
    }
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
    return Stream.concat(
            Arrays.stream(EntityTools.class.getDeclaredMethods()),
            Arrays.stream(GraphTools.class.getDeclaredMethods()))
        .map(method -> method.getAnnotation(McpTool.class))
        .filter(Objects::nonNull)
        .toList();
  }
}
