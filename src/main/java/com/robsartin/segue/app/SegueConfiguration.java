package com.robsartin.segue.app;

import com.robsartin.segue.ingest.GraphProjector;
import com.robsartin.segue.ingest.IngestService;
import com.robsartin.segue.mcp.EntityTools;
import com.robsartin.segue.mcp.GraphTools;
import com.robsartin.segue.mcp.SegueService;
import com.robsartin.segue.mcp.TasteTools;
import com.robsartin.segue.musicbrainz.MusicBrainzClient;
import com.robsartin.segue.musicbrainz.MusicBrainzSourceAdapter;
import com.robsartin.segue.port.AffinityStore;
import com.robsartin.segue.port.AssertionLog;
import com.robsartin.segue.port.EntityResolver;
import com.robsartin.segue.port.GraphStore;
import com.robsartin.segue.port.IdentityMerge;
import com.robsartin.segue.port.SourceAdapters;
import com.robsartin.segue.sqlite.SqliteAffinityStore;
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

  /**
   * Both sources, in the order they are asked (ADR 25's "plus a bean method", now exercised).
   *
   * <p><b>The order is load-bearing and is not alphabetical.</b> {@code SegueService} builds one
   * {@code ExpandContext} and bounds the concatenation of what the adapters return rather than
   * bounding each one, so a tight {@code maxNewEdges} is spent by whichever adapter comes first —
   * {@code CorroborationAcrossSourcesTest} pins that from both ends. Wikidata stays first because
   * it was first; changing which source wins a small budget is a decision with its own evidence to
   * gather, and it is not this one.
   *
   * <p><b>{@code MusicBrainzSourceAdapter} is handed its identity bridge from here, and that is the
   * whole point of the seam.</b> The bridge crosses MBID to QID through Wikidata's P434, and it
   * lives in this package because {@code musicbrainz} may not import {@code wikidata} and {@code
   * wikidata} may not import {@code musicbrainz} — both directions are ArchUnit rules. So the one
   * class that knows about both is the one whose job is knowing about everything.
   */
  @Bean
  SourceAdapters sourceAdapters(WikidataEntityResolver resolver, Clock clock) {
    // Two endpoints, two clients: the resolver's Action API client for the claims stated on an
    // entity, and a second aimed at the Query Service for the ones stated about it (ADR 36).
    // Both are plain Java constructed here, so the adapter needs no framework knowledge (ADR 25).
    //
    // One Query Service client, shared: the reverse-lookup pass and the MBID bridge ask the same
    // host the same way, and a second instance would open a second connection pool to it for no
    // reason. WikidataClient holds no per-caller state.
    WikidataClient queryService = WikidataClient.queryService();
    return new SourceAdapters(
        List.of(
            new WikidataSourceAdapter(resolver, queryService, clock),
            new MusicBrainzSourceAdapter(
                new MusicBrainzClient(), new WikidataMusicBrainzIdentity(queryService), clock)));
  }

  /**
   * <b>The affinity store is here so that a merge cannot orphan a rating</b> (#92). It is not
   * handed to {@code ingest} — {@link IdentityMerge} carries two qids and no taste-layer type, so
   * ADR 33's fence stands and {@code IngestService} still cannot see a rating. What the wiring
   * decides is that a merge declared through this application has something to follow it; {@link
   * IdentityMerge#NONE} is the other answer, and it is never the right one where ratings exist.
   */
  @Bean
  IngestService ingestService(
      AssertionLog assertionLog, GraphStore graphStore, AffinityStore affinityStore) {
    return new IngestService(
        assertionLog, graphStore, IdentityMerge.carryingRatings(affinityStore));
  }

  /**
   * The taste layer's store (ADR 33, ADR 39): the same SQLite file as the assertion log, its own
   * table, its own connection, and no relationship to {@link #assertionLog} beyond the path they
   * share. Nothing in {@code ingest} is given this bean, and nothing in the graph layer can ask for
   * it — that is the separation ADR 33 exists for, expressed as wiring.
   */
  @Bean(destroyMethod = "close")
  AffinityStore affinityStore(SegueProperties properties) {
    return new SqliteAffinityStore(properties.database());
  }

  @Bean
  SegueService segueService(
      EntityResolver resolver,
      GraphStore graph,
      IngestService ingest,
      SourceAdapters adapters,
      AffinityStore affinityStore,
      Clock clock) {
    return new SegueService(resolver, graph, ingest, adapters, affinityStore, clock);
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

  // The six MCP tools (Task 7 / ADR 26; the sixth arrived with the taste layer, ADR 39).
  // Registering them as beans here — the same way as every
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

  // The sixth tool, and the taste layer's only writer (ADR 33). Its own bean and its own class
  // for the same reason it has its own port and its own table: the boundary is the decision.
  @Bean
  TasteTools tasteTools(SegueService segueService) {
    return new TasteTools(segueService);
  }
}
