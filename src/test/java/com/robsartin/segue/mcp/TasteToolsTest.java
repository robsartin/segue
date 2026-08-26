package com.robsartin.segue.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.segue.domain.Candidate;
import com.robsartin.segue.domain.NodeAssertion;
import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.domain.Provenance;
import com.robsartin.segue.ingest.IngestService;
import com.robsartin.segue.port.AffinityStore;
import com.robsartin.segue.port.AssertionLog;
import com.robsartin.segue.port.EntityResolver;
import com.robsartin.segue.port.GraphStore;
import com.robsartin.segue.port.SourceAdapters;
import com.robsartin.segue.sqlite.SqliteAffinityStore;
import com.robsartin.segue.sqlite.SqliteAssertionLog;
import com.robsartin.segue.tinker.TinkerGraphStore;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/**
 * The taste layer over the tool boundary: the protocol result shape ADR 26/27 require, and the
 * correlation lifecycle ADR 29 requires — the two things {@link SegueServiceTest} cannot see
 * because it calls the facade directly.
 *
 * <p>Ratings and notes here are invented (ADR 33, as amended by issue #37).
 */
class TasteToolsTest {

  private static final Provenance WIKIDATA =
      new Provenance("wikidata", "S-1", Instant.parse("2026-08-24T09:00:00Z"), 1.0);

  private static final Instant RATED_AT = Instant.parse("2026-08-25T12:00:00Z");

  private AssertionLog log;
  private GraphStore graph;
  private AffinityStore affinity;
  private TasteTools tools;

  @BeforeEach
  void setUp() {
    log = SqliteAssertionLog.inMemory();
    graph = new TinkerGraphStore();
    affinity = SqliteAffinityStore.inMemory();
    SegueService service =
        new SegueService(
            new NoOpEntityResolver(),
            graph,
            new IngestService(log, graph),
            new SourceAdapters(List.of()),
            affinity,
            Clock.fixed(RATED_AT, ZoneOffset.UTC));
    tools = new TasteTools(service);
    graph.upsertNode(
        new NodeAssertion("Q900001", NodeKind.WORK, "A Placeholder Work", WIKIDATA).toNode());
  }

  @AfterEach
  void tearDown() {
    CorrelationId.clear();
    affinity.close();
    graph.close();
    log.close();
  }

  @Test
  @DisplayName("a recorded rating comes back as a non-error result with structured content")
  void recordsARating() {
    CallToolResult result = tools.noteAffinity("Q900001", 4, "an invented note");

    assertThat(result.isError()).isFalse();
    assertThat(outcomeOf(result)).isEqualTo("ok");
    // A text block as well as structuredContent, for clients that render only content (ADR 26).
    assertThat(result.content()).isNotEmpty();
    assertThat(((TextContent) result.content().get(0)).text()).contains("\"rating\":4");
  }

  @Test
  @DisplayName("rating an entity that is not in the graph is an error RESULT, not a thrown error")
  void unknownEntityIsAnErrorResult() {
    CallToolResult result = tools.noteAffinity("Q900404", 4, null);

    // ADR 27: a shortfall the model can act on comes back readable, with isError set — never as
    // a JSON-RPC protocol error.
    assertThat(result.isError()).isTrue();
    assertThat(outcomeOf(result)).isEqualTo("error");
  }

  @Test
  @DisplayName("the correlation id is cleared after the call, success or failure (ADR 29)")
  void correlationIdIsCleared() {
    tools.noteAffinity("Q900001", 4, null);
    assertThat(MDC.get(CorrelationId.KEY)).isNull();

    tools.noteAffinity("Q900404", 4, null);
    assertThat(MDC.get(CorrelationId.KEY)).isNull();
  }

  /**
   * The outcome as it reaches the wire. {@code structuredContent()} is typed {@code Object} by the
   * SDK; this project always puts a Map there (see {@code ToolResults}).
   */
  private static Object outcomeOf(CallToolResult result) {
    return ((Map<?, ?>) result.structuredContent()).get("outcome");
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
