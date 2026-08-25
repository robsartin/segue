package com.robsartin.segue.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.robsartin.segue.domain.Candidate;
import com.robsartin.segue.domain.NodeAssertion;
import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.ingest.IngestService;
import com.robsartin.segue.port.AssertionLog;
import com.robsartin.segue.port.EntityResolver;
import com.robsartin.segue.port.GraphStore;
import com.robsartin.segue.port.SourceAdapters;
import com.robsartin.segue.sqlite.SqliteAssertionLog;
import com.robsartin.segue.tinker.TinkerGraphStore;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/**
 * The correlation lifecycle every {@code @McpTool} method wraps its call in (ADR 29): {@link
 * CorrelationId#begin()}, then a {@code finally} that clears it — including when the call throws.
 * FIX 11 of the increment-4a final review.
 */
class EntityToolsTest {

  @AfterEach
  void clear() {
    CorrelationId.clear();
  }

  @Test
  @DisplayName("MDC is cleared even when the wrapped call throws")
  void mdcClearedWhenToolThrows() {
    AssertionLog log = SqliteAssertionLog.inMemory();
    GraphStore graph = new TinkerGraphStore();
    try {
      IngestService ingest = new IngestService(log, graph);
      SegueService service =
          new SegueService(new NoOpEntityResolver(), graph, ingest, new SourceAdapters(List.of()));
      EntityTools entityTools = new EntityTools(service);

      // SegueService.search requires a non-null query — a programmer error, not a modelled
      // outcome, so it is exactly the kind of exception ADR 27 says should never happen but
      // that the finally block still has to clean up after.
      assertThatThrownBy(() -> entityTools.searchEntities(null, null, null))
          .isInstanceOf(NullPointerException.class);

      assertThat(MDC.get(CorrelationId.KEY))
          .as("the finally in EntityTools must clear MDC even on an exceptional exit")
          .isNull();
    } finally {
      graph.close();
      log.close();
    }
  }

  private static final class NoOpEntityResolver implements EntityResolver {
    @Override
    public String id() {
      return "noop";
    }

    @Override
    public List<Candidate> search(String query, NodeKind kind, int limit) {
      return List.of();
    }

    @Override
    public Optional<NodeAssertion> fetch(String qid) {
      return Optional.empty();
    }
  }
}
