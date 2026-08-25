package com.robsartin.segue.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.segue.domain.AssertionRecord;
import com.robsartin.segue.domain.LoggedAssertion;
import com.robsartin.segue.domain.NodeAssertion;
import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.domain.NodeRecord;
import com.robsartin.segue.domain.Provenance;
import com.robsartin.segue.port.AssertionLog;
import com.robsartin.segue.port.ExpandContext;
import com.robsartin.segue.port.GraphStore;
import com.robsartin.segue.port.SourceAdapter;
import com.robsartin.segue.sqlite.SqliteAssertionLog;
import com.robsartin.segue.tinker.TinkerGraphStore;
import com.robsartin.segue.wikidata.StubWikidataServer;
import com.robsartin.segue.wikidata.WikidataClient;
import com.robsartin.segue.wikidata.WikidataEntityResolver;
import com.robsartin.segue.wikidata.WikidataSourceAdapter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Wikidata response to durable graph, and back again by replay. */
class WikidataIngestEndToEndTest {

  private static final Clock FIXED =
      Clock.fixed(Instant.parse("2026-08-24T09:00:00Z"), ZoneOffset.UTC);
  private static final NodeRecord SEED =
      new NodeRecord("Q1194713", NodeKind.WORK, "The Proposition");

  @TempDir Path tempDir;

  private static String resource(String name) throws IOException {
    try (InputStream in = WikidataIngestEndToEndTest.class.getResourceAsStream(name)) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private static Provenance sourced(String ref) {
    return new Provenance("wikidata", ref, FIXED.instant(), 1.0);
  }

  @Test
  @DisplayName("a Wikidata expansion becomes a graph that survives a restart")
  void expansionBecomesADurableGraph() throws IOException {
    Path dbFile = tempDir.resolve("ingest.db");
    List<LoggedAssertion> recorded = new ArrayList<>();
    int expectedEdges;

    try (StubWikidataServer stub = new StubWikidataServer();
        AssertionLog log = new SqliteAssertionLog(dbFile);
        GraphStore graph = new TinkerGraphStore()) {

      stub.enqueueBody(resource("/wikidata/proposition-claims.json"));
      WikidataEntityResolver resolver =
          new WikidataEntityResolver(new WikidataClient(stub.baseUri()), FIXED);
      SourceAdapter adapter = new WikidataSourceAdapter(resolver, FIXED);

      List<AssertionRecord> claims = adapter.expand(SEED, ExpandContext.defaults());
      expectedEdges = claims.size();

      // Every entity an edge touches must exist before the edge does. The neighbours are
      // stubs here: a real ingest would resolve each one, which is the fan-out increment 4
      // spends its virtual threads on.
      Set<String> seen = new LinkedHashSet<>();
      recorded.add(new NodeAssertion(SEED.qid(), SEED.kind(), SEED.label(), sourced(SEED.qid())));
      seen.add(SEED.qid());
      for (AssertionRecord claim : claims) {
        for (String qid : List.of(claim.fromQid(), claim.toQid())) {
          if (seen.add(qid)) {
            recorded.add(new NodeAssertion(qid, NodeKind.CONCEPT, qid, sourced(qid)));
          }
        }
      }
      recorded.addAll(claims);

      new IngestService(log, graph).recordAll(recorded);

      assertThat(claims).isNotEmpty();
      assertThat(graph.edgeCount()).isEqualTo(expectedEdges);
      assertThat(log.readAll().size()).isEqualTo(recorded.size());
    }

    // Everything above is now closed. Reopen from disk and rebuild from the log alone.
    try (AssertionLog reopened = new SqliteAssertionLog(dbFile);
        GraphStore rebuilt = new TinkerGraphStore()) {

      long replayed = GraphProjector.project(reopened, rebuilt);

      assertThat(replayed).isEqualTo(recorded.size());
      assertThat(rebuilt.node("Q1194713")).isPresent();
      assertThat(rebuilt.edgeCount()).isEqualTo(expectedEdges);
    }
  }
}
