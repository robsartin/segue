package com.robsartin.segue.wikidata;

import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.segue.domain.AssertionRecord;
import com.robsartin.segue.domain.NodeAssertion;
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
      new NodeRecord("Q180337", NodeKind.WORK, "The Proposition");

  private static String resource(String name) throws IOException {
    try (InputStream in = WikidataSourceAdapterTest.class.getResourceAsStream(name)) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  /**
   * One stub standing in for both endpoints. An expansion makes two calls in a fixed order — the
   * Action API for the claims stated on the seed, then the Query Service for the ones stated about
   * it — so a single queue is enough, and a test that queues only the first body gets the stub's
   * default {@code {}} for the second, which parses as "no backlinks". That is exactly the shape
   * the pre-#20 tests want, so they did not have to change.
   */
  private static SourceAdapter adapterFor(StubWikidataServer stub) {
    return adapterFor(stub, stub);
  }

  private static SourceAdapter adapterFor(
      StubWikidataServer actionApi, StubWikidataServer queryService) {
    WikidataClient client = new WikidataClient(actionApi.baseUri());
    return new WikidataSourceAdapter(
        new WikidataEntityResolver(client, FIXED),
        new WikidataClient(queryService.baseUri()),
        FIXED);
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

  // ---- reverse lookup (issue #20, ADR 36) ---------------------------------

  private static final NodeRecord CAVE = new NodeRecord("Q192668", NodeKind.PERSON, "Nick Cave");

  @Test
  @DisplayName("expanding a PERSON discovers the works that name them, not just their memberships")
  void personSeedReachesTheWorks() throws IOException {
    // The bug, stated as a test. Nick Cave's own item carries four P463 memberships and nothing
    // else in the vocabulary — every film he scored, wrote or directed is stated on the FILM.
    // Before the reverse lookup this expansion was those four edges and stopped.
    try (StubWikidataServer actionApi = new StubWikidataServer();
        StubWikidataServer queryService = new StubWikidataServer()) {
      actionApi.enqueueBody(resource("/wikidata/cave-claims.json"));
      queryService.enqueueBody(resource("/wikidata/cave-reverse.json"));

      ExpandResult result = adapterFor(actionApi, queryService).expand(CAVE, new ExpandContext(50));

      assertThat(result.assertions()).extracting(AssertionRecord::typeCode).contains("MEMBER_OF");
      assertThat(result.assertions())
          .extracting(AssertionRecord::typeCode)
          .contains("DIRECTED", "AUTHORED", "COMPOSED_FOR", "WROTE_SCREENPLAY_FOR", "HAS_PART");
      assertThat(result.assertions()).hasSize(11);
      assertThat(result.sourceUnavailable()).isFalse();
      assertThat(result.truncated()).isFalse();
    }
  }

  @Test
  @DisplayName("a reverse-discovered neighbour's identity comes back with the result")
  void neighboursComeBackInline() throws IOException {
    // Without this, SegueService has to fetch each of them one at a time before it can record a
    // single edge — the reverse lookup would multiply the round trips it was meant to replace.
    try (StubWikidataServer actionApi = new StubWikidataServer();
        StubWikidataServer queryService = new StubWikidataServer()) {
      actionApi.enqueueBody(resource("/wikidata/cave-claims.json"));
      queryService.enqueueBody(resource("/wikidata/cave-reverse.json"));

      ExpandResult result = adapterFor(actionApi, queryService).expand(CAVE, new ExpandContext(50));

      assertThat(result.neighbors()).extracting(NodeAssertion::qid).contains("Q180337", "Q1051182");
      assertThat(result.neighbors())
          .filteredOn(n -> n.qid().equals("Q1051182"))
          .singleElement()
          .satisfies(
              n -> {
                assertThat(n.label()).isEqualTo("Nick Cave and the Bad Seeds");
                assertThat(n.kind()).isEqualTo(NodeKind.GROUP);
              });
      // Two calls for the whole expansion: claims, then backlinks. Nothing per neighbour.
      assertThat(actionApi.requestCount()).isEqualTo(1);
      assertThat(queryService.requestCount()).isEqualTo(1);
    }
  }

  @Test
  @DisplayName("an unreachable Query Service keeps the forward claims and reports the shortfall")
  void queryServiceFailureDegradesToTheForwardClaims() throws IOException {
    // Half an answer the model can see beats none. The forward call already succeeded, and
    // throwing that away because the second call failed would make the expansion strictly worse
    // than it was before the reverse lookup existed.
    try (StubWikidataServer actionApi = new StubWikidataServer();
        StubWikidataServer queryService = new StubWikidataServer()) {
      actionApi.enqueueBody(resource("/wikidata/cave-claims.json"));
      for (int i = 0; i < 6; i++) {
        queryService.enqueueStatus(503);
        queryService.enqueueBody("{}");
      }

      ExpandResult result = adapterFor(actionApi, queryService).expand(CAVE, new ExpandContext(50));

      assertThat(result.assertions()).hasSize(4);
      assertThat(result.assertions())
          .extracting(AssertionRecord::typeCode)
          .containsOnly("MEMBER_OF");
      assertThat(result.sourceUnavailable()).isTrue();
    }
  }

  @Test
  @DisplayName("a reverse result cut short by the bound is reported as truncated")
  void reverseTruncationIsReported() throws IOException {
    try (StubWikidataServer actionApi = new StubWikidataServer();
        StubWikidataServer queryService = new StubWikidataServer()) {
      actionApi.enqueueBody(resource("/wikidata/cave-claims.json"));
      queryService.enqueueBody(resource("/wikidata/cave-reverse.json"));

      ExpandResult result = adapterFor(actionApi, queryService).expand(CAVE, new ExpandContext(5));

      assertThat(result.assertions()).hasSize(5);
      assertThat(result.truncated()).isTrue();
      assertThat(result.sourceUnavailable()).isFalse();
    }
  }

  @Test
  @DisplayName("an unknown seed costs no Query Service call at all")
  void unknownSeedSkipsTheReverseLookup() {
    // The reverse lookup is the expensive half. Spending it on an entity Wikidata has never
    // heard of would be a query nobody can use, against a service with a shared budget.
    try (StubWikidataServer actionApi = new StubWikidataServer();
        StubWikidataServer queryService = new StubWikidataServer()) {
      actionApi.enqueueBody("{\"entities\":{\"Q999999999\":{\"missing\":\"\"}}}");

      adapterFor(actionApi, queryService)
          .expand(
              new NodeRecord("Q999999999", NodeKind.PERSON, "Nobody"), ExpandContext.defaults());

      assertThat(queryService.requestCount()).isZero();
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
