package com.robsartin.segue.wikidata;

import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.segue.domain.AssertionRecord;
import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.domain.NodeRecord;
import com.robsartin.segue.port.ExpandContext;
import com.robsartin.segue.port.ExpandResult;
import com.robsartin.segue.port.SourceAdapter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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

      ExpandResult result = adapterFor(stub).expand(SEED, ExpandContext.defaults());

      assertThat(result.assertions()).isNotEmpty();
      assertThat(result.assertions()).extracting(AssertionRecord::typeCode).contains("DIRECTED");
      assertThat(result.assertions())
          .allSatisfy(c -> assertThat(c.provenance().sourceId()).isEqualTo("wikidata"));
      assertThat(result.sourceUnavailable()).isFalse();
      assertThat(result.truncated()).isFalse();
    }
  }

  @Test
  @DisplayName("it honours maxNewEdges")
  void honoursBound() throws IOException {
    try (StubWikidataServer stub = new StubWikidataServer()) {
      stub.enqueueBody(resource("/wikidata/proposition-claims.json"));

      ExpandResult result = adapterFor(stub).expand(SEED, new ExpandContext(2));

      assertThat(result.assertions()).hasSize(2);
    }
  }

  @Test
  @DisplayName("a bound narrower than the available claims is reported as truncated")
  void reportsTruncation() throws IOException {
    // Three distinct outcomes collapse into the same short list without this: unavailable,
    // genuinely nothing to say, and cut short by maxNewEdges. The MCP tool layer needs to
    // tell those apart to report a shortfall to the model.
    try (StubWikidataServer stub = new StubWikidataServer()) {
      stub.enqueueBody(resource("/wikidata/proposition-claims.json"));

      ExpandResult result = adapterFor(stub).expand(SEED, new ExpandContext(2));

      assertThat(result.truncated()).isTrue();
      assertThat(result.sourceUnavailable()).isFalse();
    }
  }

  @Test
  @DisplayName("a bound that does not cut anything off is not reported as truncated")
  void doesNotReportTruncationWhenNothingWasCut() throws IOException {
    try (StubWikidataServer stub = new StubWikidataServer()) {
      stub.enqueueBody(resource("/wikidata/proposition-claims.json"));

      ExpandResult result = adapterFor(stub).expand(SEED, ExpandContext.defaults());

      assertThat(result.truncated()).isFalse();
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

      ExpandResult result = adapterFor(stub).expand(SEED, ExpandContext.defaults());

      assertThat(result.assertions()).isEmpty();
      assertThat(result.sourceUnavailable()).isTrue();
      assertThat(result.truncated()).isFalse();
    }
  }

  @Test
  @DisplayName("an unknown seed yields nothing, and is not reported as unavailable")
  void unknownSeedIsEmpty() {
    try (StubWikidataServer stub = new StubWikidataServer()) {
      stub.enqueueBody("{\"entities\":{\"Q999999999\":{\"missing\":\"\"}}}");

      ExpandResult result =
          adapterFor(stub)
              .expand(
                  new NodeRecord("Q999999999", NodeKind.PERSON, "Nobody"),
                  ExpandContext.defaults());

      assertThat(result.assertions()).isEmpty();
      assertThat(result.sourceUnavailable()).isFalse();
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
