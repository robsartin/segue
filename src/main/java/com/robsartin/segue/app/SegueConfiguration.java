package com.robsartin.segue.app;

import com.robsartin.segue.ingest.GraphProjector;
import com.robsartin.segue.ingest.IngestService;
import com.robsartin.segue.mcp.EntityTools;
import com.robsartin.segue.mcp.GraphTools;
import com.robsartin.segue.mcp.SegueService;
import com.robsartin.segue.port.AssertionLog;
import com.robsartin.segue.port.EntityResolver;
import com.robsartin.segue.port.GraphStore;
import com.robsartin.segue.port.SourceAdapters;
import com.robsartin.segue.sqlite.SqliteAssertionLog;
import com.robsartin.segue.tinker.TinkerGraphStore;
import com.robsartin.segue.wikidata.WikidataClient;
import com.robsartin.segue.wikidata.WikidataEntityResolver;
import com.robsartin.segue.wikidata.WikidataSourceAdapter;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.transport.DefaultServerTransportSecurityValidator;
import io.modelcontextprotocol.server.transport.ServerTransportSecurityValidator;
import java.time.Clock;
import java.util.List;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerStreamableHttpProperties;
import org.springframework.ai.mcp.server.webmvc.transport.WebMvcStreamableServerTransportProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import tools.jackson.databind.json.JsonMapper;

/**
 * Wiring.
 *
 * <p>This is the only place that knows about every layer, and that is deliberate: the adapters are
 * plain Java constructed here, so they stay testable without an application context and a new
 * source needs no framework knowledge (ADR 25).
 *
 * <p><b>{@code McpServerStreamableHttpProperties} is enabled here, not by the starter.</b> Spring
 * AI 2.0.1 registers only {@code McpServerProperties} and {@code
 * McpServerChangeNotificationProperties} — verified by reading the
 * {@code @EnableConfigurationProperties} attribute on the compiled {@code
 * McpServerAutoConfiguration}, not inferred. Its own {@code
 * webMvcStreamableServerTransportProvider} bean method takes {@code
 * McpServerStreamableHttpProperties} as an argument, so the starter's Streamable HTTP
 * auto-configuration cannot satisfy its own dependency: the context fails with {@code
 * NoSuchBeanDefinitionException} the moment {@code spring.ai.mcp.server.protocol} is {@code
 * streamable}. Enabling the class here fixes that for the starter as well as for {@link
 * #streamableHttpTransport}, and is the reason this project would have had to override the bean
 * even without the security validator.
 */
@Configuration
@EnableConfigurationProperties({SegueProperties.class, McpServerStreamableHttpProperties.class})
public class SegueConfiguration {

  private static final Logger log = LoggerFactory.getLogger(SegueConfiguration.class);

  @Bean(destroyMethod = "close")
  AssertionLog assertionLog(SegueProperties properties) {
    return new SqliteAssertionLog(properties.database());
  }

  @Bean(destroyMethod = "close")
  GraphStore graphStore(AssertionLog assertionLog) {
    // The graph is a projection of the log (ADR 19). Rebuilding it at boot is what makes
    // that true rather than aspirational — and what makes the engine choice reversible.
    GraphStore store = new TinkerGraphStore();
    long replayed = GraphProjector.project(assertionLog, store);
    log.info("replayed {} assertions into {}", replayed, store.id());
    return store;
  }

  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }

  @Bean
  WikidataEntityResolver wikidataEntityResolver(Clock clock) {
    return new WikidataEntityResolver(new WikidataClient(), clock);
  }

  // Primary because this bean and wikidataEntityResolver share one underlying instance: once
  // Spring has instantiated this bean, its runtime type (WikidataEntityResolver) makes it an
  // equally valid candidate for any WikidataEntityResolver-typed injection point, which without
  // a tiebreaker throws NoUniqueBeanDefinitionException even though both names resolve to the
  // same object.
  @Primary
  @Bean
  EntityResolver entityResolver(WikidataEntityResolver wikidata) {
    return wikidata;
  }

  @Bean
  SourceAdapters sourceAdapters(WikidataEntityResolver resolver, Clock clock) {
    return new SourceAdapters(List.of(new WikidataSourceAdapter(resolver, clock)));
  }

  @Bean
  IngestService ingestService(AssertionLog assertionLog, GraphStore graphStore) {
    return new IngestService(assertionLog, graphStore);
  }

  @Bean
  SegueService segueService(
      EntityResolver resolver, GraphStore graph, IngestService ingest, SourceAdapters adapters) {
    return new SegueService(resolver, graph, ingest, adapters);
  }

  /**
   * The Streamable HTTP transport (ADR 28), with the {@code Origin} and {@code Host} checks that
   * ADR 28 makes a requirement rather than a nicety.
   *
   * <p>This replaces the starter's own auto-configured provider — same bean type, so its
   * {@code @ConditionalOnMissingBean} backs off — for exactly one reason: the auto-configuration
   * builds the transport with no security validator at all, and the SDK's default is {@code
   * ServerTransportSecurityValidator.NOOP}. Everything else here reproduces what the starter would
   * have done, reading the same {@code spring.ai.mcp.server.streamable-http.*} properties, so
   * configuring the endpoint or the keep-alive still works the documented way instead of being
   * silently ignored by this override.
   *
   * <p><b>Why a localhost server still has to check who is asking.</b> Binding to 127.0.0.1 stops
   * the network reaching segue. It does not stop a web page the user has open in their own browser
   * from POSTing to {@code http://localhost:8080/mcp} — the browser is on the loopback interface
   * too. DNS rebinding turns that into a full read/write session against the graph. The {@code
   * Origin} check is what closes it: a browser always sends {@code Origin} on a cross-origin
   * request, so anything not served from loopback is refused with 403. The {@code Host} allowlist
   * closes the same attack from the other side — a rebound name resolves to 127.0.0.1 but still
   * arrives carrying {@code Host: attacker.example}, which is a 421.
   *
   * <p>Requests with no {@code Origin} header at all are allowed, which is deliberate and is the
   * SDK's own behaviour: an ordinary MCP client is not a browser and sends none, while a browser
   * cannot omit it. Treating "absent" as hostile would break every real client to defend against
   * nothing.
   *
   * <p>The allowlist is a constant, not a configuration property. ADR 28 says making segue
   * reachable from anywhere else is "a deliberate configuration change with its own security
   * review" — a property would make widening it a deploy-time accident rather than a reviewed
   * change, and nothing today needs it widened.
   */
  @Bean
  @ConditionalOnProperty(
      prefix = "spring.ai.mcp.server",
      name = "stdio",
      havingValue = "false",
      matchIfMissing = true)
  WebMvcStreamableServerTransportProvider streamableHttpTransport(
      @Qualifier("mcpServerJsonMapper") JsonMapper jsonMapper,
      McpServerStreamableHttpProperties properties) {
    WebMvcStreamableServerTransportProvider.Builder builder =
        WebMvcStreamableServerTransportProvider.builder()
            .jsonMapper(new JacksonMcpJsonMapper(jsonMapper))
            .mcpEndpoint(properties.getMcpEndpoint())
            .disallowDelete(properties.isDisallowDelete())
            .securityValidator(loopbackOnly());
    if (properties.getKeepAliveInterval() != null) {
      builder.keepAliveInterval(properties.getKeepAliveInterval());
    }
    return builder.build();
  }

  /**
   * Origin and Host both restricted to loopback, on any port.
   *
   * <p>The {@code :*} suffix is the SDK validator's own wildcard: {@code http://localhost:*}
   * matches {@code http://localhost} and {@code http://localhost:5173} alike. Any port, because the
   * legitimate caller is a local tool — an inspector, a client's own dev server — whose port is not
   * knowable in advance, and because the port is not what makes an origin trustworthy here; the
   * host is.
   */
  private static ServerTransportSecurityValidator loopbackOnly() {
    List<String> loopbackNames = List.of("localhost", "127.0.0.1", "[::1]");
    List<String> origins =
        loopbackNames.stream()
            .flatMap(host -> Stream.of("http://" + host + ":*", "https://" + host + ":*"))
            .toList();
    List<String> hosts = loopbackNames.stream().map(host -> host + ":*").toList();
    return DefaultServerTransportSecurityValidator.builder()
        .allowedOrigins(origins)
        .allowedHosts(hosts)
        .build();
  }

  // The five MCP tools (Task 7 / ADR 26). Registering them as beans here — the same way as every
  // other collaborator in this class — is what makes the starter's annotation scanner find their
  // @McpTool methods; see EntityTools' Javadoc and the task-7 report for how that was confirmed.
  @Bean
  EntityTools entityTools(SegueService segueService) {
    return new EntityTools(segueService);
  }

  @Bean
  GraphTools graphTools(SegueService segueService, SegueProperties properties) {
    return new GraphTools(segueService, properties.maxNewEdges());
  }
}
