package com.robsartin.segue.seed;

import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.wikidata.StubWikidataServer;
import com.robsartin.segue.wikidata.WikidataClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Offline against the stub server. Every name is invented; see {@link NamesTest}. */
class WikidataFactsTest {

  private static final String TWO_ENTITIES =
      """
      {"entities":{
        "Q090000101":{
          "id":"Q090000101",
          "labels":{"en":{"language":"en","value":"Marguerite Vale"}},
          "descriptions":{"en":{"language":"en","value":"guitarist"}},
          "aliases":{"en":[{"language":"en","value":"Maggie Vale"}]},
          "sitelinks":{"enwiki":{"title":"Marguerite Vale"},"frwiki":{"title":"Marguerite Vale"}},
          "claims":{
            "P31":[{"mainsnak":{"snaktype":"value","datavalue":{"value":{"id":"Q5"}}}}],
            "P106":[{"mainsnak":{"snaktype":"value","datavalue":{"value":{"id":"Q855091"}}}},
                    {"mainsnak":{"snaktype":"value","datavalue":{"value":{"id":"Q639669"}}}}]
          }
        },
        "Q090000102":{
          "id":"Q090000102",
          "labels":{"en":{"language":"en","value":"Velvet Ossuary"}},
          "sitelinks":{},
          "claims":{"P31":[{"mainsnak":{"snaktype":"value","datavalue":{"value":{"id":"Q215380"}}}}]}
        }
      }}
      """;

  @Test
  @DisplayName("one call carries label, aliases, sitelink count, kind and occupations")
  void readsEverythingTheDecisionNeeds() {
    try (StubWikidataServer stub = new StubWikidataServer()) {
      stub.enqueueBody(TWO_ENTITIES);
      WikidataFacts facts = new WikidataFacts(new WikidataClient(stub.baseUri()));

      Map<String, CandidateFacts> byQid = facts.factsFor(List.of("Q090000101", "Q090000102"));

      CandidateFacts person = byQid.get("Q090000101");
      assertThat(person.label()).isEqualTo("Marguerite Vale");
      assertThat(person.description()).isEqualTo("guitarist");
      assertThat(person.aliases()).containsExactly("Maggie Vale");
      assertThat(person.sitelinks()).isEqualTo(2);
      assertThat(person.kind()).isEqualTo(NodeKind.PERSON);
      assertThat(person.occupations()).containsExactly("Q855091", "Q639669");

      CandidateFacts band = byQid.get("Q090000102");
      assertThat(band.kind()).isEqualTo(NodeKind.GROUP);
      assertThat(band.sitelinks()).isZero();
      assertThat(band.occupations()).isEmpty();
      assertThat(band.description()).isNull();
    }
  }

  @Test
  @DisplayName("an entity Wikidata does not have is simply absent")
  void missingEntitiesAreAbsent() {
    try (StubWikidataServer stub = new StubWikidataServer()) {
      stub.enqueueBody("{\"entities\":{\"Q090000103\":{\"missing\":\"\"}}}");
      WikidataFacts facts = new WikidataFacts(new WikidataClient(stub.baseUri()));

      assertThat(facts.factsFor(List.of("Q090000103"))).isEmpty();
    }
  }

  @Test
  @DisplayName("more identifiers than one call allows become several calls")
  void batchesAtTheApiLimit() {
    try (StubWikidataServer stub = new StubWikidataServer()) {
      List<String> qids = new ArrayList<>();
      for (int i = 0; i < WikidataFacts.MAX_IDS_PER_CALL + 1; i++) {
        qids.add("Q09010" + i);
      }
      stub.enqueueBody("{\"entities\":{}}");
      stub.enqueueBody("{\"entities\":{}}");
      WikidataFacts facts = new WikidataFacts(new WikidataClient(stub.baseUri()));

      facts.factsFor(qids);

      assertThat(stub.requestCount()).isEqualTo(2);
    }
  }

  @Test
  @DisplayName("nothing to ask about asks nothing")
  void noIdsMeansNoCall() {
    try (StubWikidataServer stub = new StubWikidataServer()) {
      WikidataFacts facts = new WikidataFacts(new WikidataClient(stub.baseUri()));

      assertThat(facts.factsFor(List.of())).isEmpty();
      assertThat(stub.requestCount()).isZero();
    }
  }

  @Test
  @DisplayName("a duplicate identifier is asked about once")
  void duplicatesAreCollapsed() {
    try (StubWikidataServer stub = new StubWikidataServer()) {
      stub.enqueueBody(TWO_ENTITIES);
      WikidataFacts facts = new WikidataFacts(new WikidataClient(stub.baseUri()));

      facts.factsFor(List.of("Q090000101", "Q090000101", "Q090000102"));

      assertThat(stub.lastQuery()).contains("Q090000101%7CQ090000102");
    }
  }
}
