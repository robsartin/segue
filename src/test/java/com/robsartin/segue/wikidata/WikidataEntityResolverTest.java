package com.robsartin.segue.wikidata;

import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.segue.domain.Candidate;
import com.robsartin.segue.domain.NodeAssertion;
import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.port.EntityResolver;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class WikidataEntityResolverTest {

  private static final Instant PULL = Instant.parse("2026-08-24T09:00:00Z");
  private static final Clock FIXED = Clock.fixed(PULL, ZoneOffset.UTC);

  private static String resource(String name) throws IOException {
    try (InputStream in = WikidataEntityResolverTest.class.getResourceAsStream(name)) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  @Test
  @DisplayName("search returns candidates carrying the description that disambiguates them")
  void searchReturnsDisambiguatingCandidates() throws IOException {
    try (StubWikidataServer stub = new StubWikidataServer()) {
      stub.enqueueBody(resource("/wikidata/search-cave.json"));
      EntityResolver resolver =
          new WikidataEntityResolver(new WikidataClient(stub.baseUri()), FIXED);

      List<Candidate> hits = resolver.search("nick cave", null, 10);

      assertThat(hits).hasSize(3);
      assertThat(hits.get(0).qid()).isEqualTo("Q214601");
      // Two entries share the label. Only the description separates them.
      assertThat(hits.get(0).label()).isEqualTo(hits.get(1).label());
      assertThat(hits.get(0).description()).isNotEqualTo(hits.get(1).description());
    }
  }

  @Test
  @DisplayName("search writes nothing — it is a question, not a change")
  void searchIsReadOnly() throws IOException {
    try (StubWikidataServer stub = new StubWikidataServer()) {
      stub.enqueueBody(resource("/wikidata/search-cave.json"));
      new WikidataEntityResolver(new WikidataClient(stub.baseUri()), FIXED)
          .search("nick cave", null, 10);

      assertThat(stub.requestCount()).isEqualTo(1);
    }
  }

  @Test
  @DisplayName("a kind argument does not silently empty the results")
  void kindDoesNotSilentlyFilterEverythingOut() throws IOException {
    // The failure this guards: filtering on a kind we cannot determine returns [], which a
    // caller reads as "no such entity". Returning everything and letting the description
    // disambiguate is the honest behaviour until search can see P31.
    try (StubWikidataServer stub = new StubWikidataServer()) {
      stub.enqueueBody(resource("/wikidata/search-cave.json"));
      EntityResolver resolver =
          new WikidataEntityResolver(new WikidataClient(stub.baseUri()), FIXED);

      assertThat(resolver.search("nick cave", NodeKind.PERSON, 10)).hasSize(3);
    }
  }

  @Test
  @DisplayName("an empty result is empty, not an error")
  void emptySearchIsNotAnError() {
    try (StubWikidataServer stub = new StubWikidataServer()) {
      stub.enqueueBody("{\"search\":[],\"success\":1}");
      EntityResolver resolver =
          new WikidataEntityResolver(new WikidataClient(stub.baseUri()), FIXED);

      assertThat(resolver.search("asdfghjkl", null, 10)).isEmpty();
    }
  }

  @Test
  @DisplayName("fetch returns a sourced node claim with the kind read from P31")
  void fetchReturnsNodeAssertion() throws IOException {
    try (StubWikidataServer stub = new StubWikidataServer()) {
      stub.enqueueBody(resource("/wikidata/proposition-claims.json"));
      EntityResolver resolver =
          new WikidataEntityResolver(new WikidataClient(stub.baseUri()), FIXED);

      Optional<NodeAssertion> fetched = resolver.fetch("Q1194713");

      assertThat(fetched).isPresent();
      NodeAssertion node = fetched.orElseThrow();
      assertThat(node.qid()).isEqualTo("Q1194713");
      assertThat(node.label()).isEqualTo("The Proposition");
      assertThat(node.kind()).isEqualTo(NodeKind.WORK); // P31 = Q11424, film
      assertThat(node.provenance().sourceId()).isEqualTo("wikidata");
      assertThat(node.provenance().assertedAt()).isEqualTo(PULL);
    }
  }

  @Test
  @DisplayName("an unknown identifier yields empty rather than a fabricated node")
  void unknownQidIsEmpty() {
    try (StubWikidataServer stub = new StubWikidataServer()) {
      stub.enqueueBody("{\"entities\":{\"Q999999999\":{\"missing\":\"\"}}}");
      EntityResolver resolver =
          new WikidataEntityResolver(new WikidataClient(stub.baseUri()), FIXED);

      assertThat(resolver.fetch("Q999999999")).isEmpty();
    }
  }

  @Test
  @DisplayName("it identifies itself as the same source its adapter will")
  void identifiesItself() {
    try (StubWikidataServer stub = new StubWikidataServer()) {
      assertThat(new WikidataEntityResolver(new WikidataClient(stub.baseUri()), FIXED).id())
          .isEqualTo("wikidata");
    }
  }
}
