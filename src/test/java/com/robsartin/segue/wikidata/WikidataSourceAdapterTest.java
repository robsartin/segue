package com.robsartin.segue.wikidata;

import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.segue.domain.AssertionRecord;
import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.domain.NodeRecord;
import com.robsartin.segue.port.ExpandContext;
import com.robsartin.segue.port.SourceAdapter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class WikidataSourceAdapterTest {

  private static final Clock FIXED =
      Clock.fixed(Instant.parse("2026-08-24T09:00:00Z"), ZoneOffset.UTC);
  private static final NodeRecord SEED =
      new NodeRecord("Q1194713", NodeKind.WORK, "The Proposition");

  private static String resource(String name) throws IOException {
    try (InputStream in = WikidataSourceAdapterTest.class.getResourceAsStream(name)) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private static SourceAdapter adapterFor(StubWikidataServer stub) {
    WikidataClient client = new WikidataClient(stub.baseUri());
    return new WikidataSourceAdapter(new WikidataEntityResolver(client, FIXED), FIXED);
  }

  @Test
  @DisplayName("expanding a work yields its whitelisted relations")
  void expandsWork() throws IOException {
    try (StubWikidataServer stub = new StubWikidataServer()) {
      stub.enqueueBody(resource("/wikidata/proposition-claims.json"));

      List<AssertionRecord> claims = adapterFor(stub).expand(SEED, ExpandContext.defaults());

      assertThat(claims).isNotEmpty();
      assertThat(claims).extracting(AssertionRecord::typeCode).contains("DIRECTED");
      assertThat(claims)
          .allSatisfy(c -> assertThat(c.provenance().sourceId()).isEqualTo("wikidata"));
    }
  }

  @Test
  @DisplayName("it honours maxNewEdges, and stops before fetching what it would discard")
  void honoursBound() throws IOException {
    try (StubWikidataServer stub = new StubWikidataServer()) {
      stub.enqueueBody(resource("/wikidata/proposition-claims.json"));

      List<AssertionRecord> claims = adapterFor(stub).expand(SEED, new ExpandContext(2));

      assertThat(claims).hasSize(2);
    }
  }

  @Test
  @DisplayName("an unreachable Wikidata degrades to an empty result, it does not propagate")
  void degradesWhenUnavailable() {
    // The caller is a language model. A partial result it can see beats an exception it can
    // only retry — see the error-handling section of the slice 1-2 design.
    try (StubWikidataServer stub = new StubWikidataServer()) {
      for (int i = 0; i < 6; i++) {
        stub.enqueueStatus(503);
        stub.enqueueBody("{}");
      }

      assertThat(adapterFor(stub).expand(SEED, ExpandContext.defaults())).isEmpty();
    }
  }

  @Test
  @DisplayName("an unknown seed yields nothing")
  void unknownSeedIsEmpty() {
    try (StubWikidataServer stub = new StubWikidataServer()) {
      stub.enqueueBody("{\"entities\":{\"Q999999999\":{\"missing\":\"\"}}}");

      assertThat(
              adapterFor(stub)
                  .expand(
                      new NodeRecord("Q999999999", NodeKind.PERSON, "Nobody"),
                      ExpandContext.defaults()))
          .isEmpty();
    }
  }

  @Test
  @DisplayName("it supports every kind and names itself consistently with the resolver")
  void declaresItself() {
    try (StubWikidataServer stub = new StubWikidataServer()) {
      SourceAdapter adapter = adapterFor(stub);

      assertThat(adapter.id()).isEqualTo("wikidata");
      assertThat(adapter.supports(NodeKind.PERSON)).isTrue();
      assertThat(adapter.supports(NodeKind.CONCEPT)).isTrue();
    }
  }
}
