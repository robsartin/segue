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
import java.time.Clock;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Wiring.
 *
 * <p>This is the only place that knows about every layer, and that is deliberate: the adapters are
 * plain Java constructed here, so they stay testable without an application context and a new
 * source needs no framework knowledge (ADR 25).
 */
@Configuration
@EnableConfigurationProperties(SegueProperties.class)
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
