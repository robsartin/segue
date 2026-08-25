package com.robsartin.segue.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpServerTransportProviderBase;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * ADR 28 ships both transports; exactly one of them is live in any given process.
 *
 * <p>This is the invariant that broke first when the Streamable HTTP transport was added, and it
 * broke in the way configuration invariants usually do — not with a wrong answer but with a context
 * that would not start at all: two beans of type {@link McpServerTransportProviderBase} and a
 * {@code NoUniqueBeanDefinitionException} from the SDK's own {@code mcpSyncServer}, because the
 * stdio transport bean was unconditional. {@link StreamableHttpTransportTest} proves the HTTP side
 * of the pair, and this proves the stdio side still wins under the {@code stdio} profile rather
 * than merely being one of two candidates.
 *
 * <p>{@code webEnvironment = NONE} is not a shortcut here — it is the point. The {@code stdio}
 * profile sets {@code spring.main.web-application-type: none}, and a test that let Spring build a
 * servlet context anyway would be asserting against a process shaped differently from the one an
 * MCP client actually launches.
 */
@SpringBootTest(
    classes = SegueApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("stdio")
class TransportSelectionTest {

  @TempDir static Path tempDir;

  @DynamicPropertySource
  static void isolateDatabase(DynamicPropertyRegistry registry) {
    registry.add("segue.database", () -> tempDir.resolve("transport-selection.db").toString());
  }

  @Autowired ApplicationContext context;

  @Test
  @DisplayName("the stdio profile leaves exactly one transport, and it is the stdio one")
  void stdioProfileSelectsOnlyTheStdioTransport() {
    assertThat(context.getBeansOfType(McpServerTransportProviderBase.class))
        .as("two live transports is not a degraded server, it is a server that cannot start")
        .hasSize(1)
        .allSatisfy(
            (name, transport) ->
                assertThat(transport).isInstanceOf(StdioServerTransportProvider.class));
  }

  @Test
  @DisplayName("no HTTP transport is even built when the stdio profile is active")
  void stdioProfileBuildsNoHttpTransport() {
    assertThat(context.getBeanNamesForType(Object.class))
        .as("nothing HTTP should be wired: a subprocess-launched server must listen on nothing")
        .doesNotContain("streamableHttpTransport", "webMvcStreamableServerRouterFunction");
  }
}
