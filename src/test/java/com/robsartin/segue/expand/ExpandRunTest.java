package com.robsartin.segue.expand;

import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.segue.domain.Candidate;
import com.robsartin.segue.domain.LocalEntity;
import com.robsartin.segue.domain.NodeAssertion;
import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.domain.NodeRecord;
import com.robsartin.segue.domain.Provenance;
import com.robsartin.segue.expansion.EntityExpansion;
import com.robsartin.segue.ingest.IngestService;
import com.robsartin.segue.port.AssertionLog;
import com.robsartin.segue.port.EntityResolver;
import com.robsartin.segue.port.ExpandContext;
import com.robsartin.segue.port.ExpandResult;
import com.robsartin.segue.port.GraphStore;
import com.robsartin.segue.port.IdentityMerge;
import com.robsartin.segue.port.SourceAdapter;
import com.robsartin.segue.port.SourceAdapters;
import com.robsartin.segue.sqlite.SqliteAssertionLog;
import com.robsartin.segue.tinker.TinkerGraphStore;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The run, over invented promotions. Every id here carries ADR 58's leading zero, or ADR 59's two
 * (#284); nothing here comes from anybody's graph (ADR 33, issue #37).
 */
class ExpandRunTest {

  private static final String IN_GRAPH_ONE = "Q0900701";
  private static final String IN_GRAPH_TWO = "Q0900702";
  private static final String MINTED = "Q00900703";
  private static final String NOT_IN_GRAPH = "Q0900704";

  private static final List<String> PROMOTIONS =
      List.of(IN_GRAPH_ONE, IN_GRAPH_TWO, MINTED, NOT_IN_GRAPH);

  private static final Provenance WIKIDATA =
      new Provenance("wikidata", "S-1", Instant.parse("2026-09-07T09:00:00Z"), 1.0);

  private static final Instant MINTED_AT = Instant.parse("2026-09-07T10:00:00Z");

  @TempDir private Path dir;

  private AssertionLog log;
  private GraphStore graph;
  private AtomicBoolean neverAsked;
  private ExpandRun run;

  @BeforeEach
  void setUp() {
    log = new SqliteAssertionLog(dir.resolve("scratch.db"));
    graph = new TinkerGraphStore();
    IngestService ingest = new IngestService(log, graph, IdentityMerge.NONE);

    ingest.record(
        new NodeAssertion(IN_GRAPH_ONE, NodeKind.PERSON, "an act nobody signed", WIKIDATA));
    ingest.record(
        new NodeAssertion(IN_GRAPH_TWO, NodeKind.PERSON, "an act nobody booked", WIKIDATA));
    ingest.record(
        LocalEntity.minted(MINTED, NodeKind.WORK, "a pamphlet no source indexes", MINTED_AT));
    // NOT_IN_GRAPH is rated but has no node — a rating survives its entity's retraction.

    neverAsked = new AtomicBoolean(true);
    SourceAdapter failIfAsked =
        new SourceAdapter() {
          @Override
          public String id() {
            return "must-not-be-asked";
          }

          @Override
          public boolean supports(NodeKind kind) {
            return true;
          }

          @Override
          public ExpandResult expand(NodeRecord seed, ExpandContext ctx) {
            neverAsked.set(false);
            throw new AssertionError("a dry run must not ask any source anything");
          }
        };
    EntityExpansion expansion =
        new EntityExpansion(
            new NeverCalledResolver(), graph, ingest, new SourceAdapters(List.of(failIfAsked)));
    run = new ExpandRun(expansion, graph);
  }

  @AfterEach
  void tearDown() {
    graph.close();
    log.close();
  }

  @Test
  @DisplayName("a dry run counts what would be visited and asks no source anything")
  void shouldCountWithoutExpandingWhenTheRunIsADryRun() {
    List<String> lines = new ArrayList<>();

    Preflight preflight = run.dryRun(PROMOTIONS, lines::add);

    assertThat(preflight).isEqualTo(new Preflight(4, 2, 1));
    assertThat(neverAsked.get()).as("no adapter was called").isTrue();
    assertThat(lines).anyMatch(line -> line.contains("dry run"));
  }

  @Test
  @DisplayName("a dry run appends nothing to the log")
  void shouldAppendNothingWhenTheRunIsADryRun() {
    int before = log.readAll().size();

    run.dryRun(PROMOTIONS, line -> {});

    assertThat(log.readAll()).hasSize(before);
  }

  /** Answers for nothing — a dry run must never reach it. */
  private static final class NeverCalledResolver implements EntityResolver {

    @Override
    public String id() {
      return "never-called";
    }

    @Override
    public List<Candidate> search(String query, NodeKind kind, int limit) {
      throw new AssertionError("a dry run must not ask the resolver anything");
    }

    @Override
    public Optional<NodeAssertion> fetch(String qid) {
      throw new AssertionError("a dry run must not ask the resolver anything");
    }
  }
}
