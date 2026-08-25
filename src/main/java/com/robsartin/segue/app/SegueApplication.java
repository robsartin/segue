package com.robsartin.segue.app;

import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpServerTransportProviderBase;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import tools.jackson.databind.json.JsonMapper;

/**
 * Entry point.
 *
 * <p>The banner is off and every appender targets stderr, because on the stdio transport stdout
 * carries the MCP protocol and a single stray line corrupts it. See docs/adr/0028-mcp-transports.md
 * — this is enforced by an ArchUnit rule and a stdout-purity integration test, not by remembering.
 *
 * <p><b>FIX 5 of the increment-4a final review — the stdout guarantee is now structural, not just
 * "the config happens to be valid today."</b> Spring Boot's {@code LogbackLoggingSystem} calls
 * {@code StatusPrinter2.printInCaseOfErrorsOrWarnings} the moment Logback logs a WARN during its
 * own configuration — an XML typo in {@code logback-spring.xml}, a missing encoder after a version
 * bump, a stray classpath {@code logback.xml}. That printer's stream defaults to {@code
 * System.out}. It is a dependency's own behaviour, so ArchUnit's {@code nothingWritesToStandardOut}
 * cannot see it (it only scans this project's source), and today's valid config means {@code
 * StdioPurityTest} cannot catch it either — the defence would fail exactly when the logging setup
 * breaks, which is precisely when you cannot afford stdout to be corrupted.
 *
 * <p>{@code main} therefore captures the real stdout and swaps {@code System.out} for stderr before
 * Spring runs, so any later {@code System.out} write — this project's own (there are none; ArchUnit
 * still forbids it everywhere else) or a dependency's — lands harmlessly on stderr instead of on
 * the JSON-RPC stream. {@link #stdioServerTransport} then hands the MCP transport the captured
 * stream directly, so the protocol still writes to the real stdout rather than to the
 * now-redirected one. This is the one exemption {@code ArchitectureTest.nothingWritesToStandardOut}
 * names by class, and nothing more: reading {@code System.out} once, here, is what makes the
 * redirection possible.
 */
@SpringBootApplication(scanBasePackages = "com.robsartin.segue")
public class SegueApplication {

  /**
   * The real stdout, captured before {@link #main} hands control to Spring and before any
   * dependency gets a chance to resolve {@code System.out} for itself. This is what the MCP
   * transport actually writes the protocol to; {@link #stdioServerTransport} is its only consumer.
   *
   * <p>Null when a test boots this application's context directly (every {@code @SpringBootTest} in
   * this project does exactly that) rather than through {@link #main} — {@link
   * #stdioServerTransport} falls back to {@code System.out} as it stands at bean-creation time in
   * that case, which for a test that never runs the transport for real is exactly as good.
   */
  private static PrintStream realStdout;

  public static void main(String[] args) {
    realStdout = System.out;
    System.setOut(new PrintStream(System.err, true, StandardCharsets.UTF_8));

    SpringApplication application = new SpringApplication(SegueApplication.class);
    application.setBannerMode(Banner.Mode.OFF);
    application.run(args);
  }

  /**
   * Overrides the starter's auto-configured stdio transport (same declared bean type, {@code
   * McpServerTransportProviderBase}, so its {@code @ConditionalOnMissingBean} backs off) with one
   * wired to the stdout captured in {@link #main} — not whatever {@code System.out} happens to
   * resolve to by the time this bean is created, which by then is always stderr.
   *
   * <p>Conditional on the same property the starter itself switches on, rather than on the {@code
   * stdio} profile that sets it, so this bean and the framework's own stdio wiring can never
   * disagree about which transport is live. Without the condition it was created on the HTTP
   * transport too, where it collided with the Streamable HTTP provider — two beans of type {@code
   * McpServerTransportProviderBase} and a {@code NoUniqueBeanDefinitionException} before the server
   * ever answered a request.
   */
  @Bean
  @ConditionalOnProperty(prefix = "spring.ai.mcp.server", name = "stdio", havingValue = "true")
  McpServerTransportProviderBase stdioServerTransport(
      @Qualifier("mcpServerJsonMapper") JsonMapper mcpServerJsonMapper) {
    PrintStream stdout = realStdout != null ? realStdout : System.out;
    return new StdioServerTransportProvider(
        new JacksonMcpJsonMapper(mcpServerJsonMapper), System.in, stdout);
  }
}
