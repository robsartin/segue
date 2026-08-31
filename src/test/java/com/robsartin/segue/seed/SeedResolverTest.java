package com.robsartin.segue.seed;

import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.segue.wikidata.StubWikidataServer;
import com.robsartin.segue.wikidata.WikidataClient;
import com.robsartin.segue.wikidata.WikidataEntityResolver;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Offline against the stub server. Every name is invented; see {@link NamesTest}. */
class SeedResolverTest {

  private static String searchHits(String... qids) {
    StringBuilder json = new StringBuilder("{\"search\":[");
    for (int i = 0; i < qids.length; i++) {
      json.append(i == 0 ? "" : ",")
          .append("{\"id\":\"")
          .append(qids[i])
          .append("\",\"label\":\"anything\",\"description\":\"a hit\"}");
    }
    return json.append("],\"success\":1}").toString();
  }

  private static String band(String qid, String label, int sitelinks) {
    StringBuilder sitelinkJson = new StringBuilder();
    for (int i = 0; i < sitelinks; i++) {
      sitelinkJson.append(i == 0 ? "" : ",").append("\"wiki").append(i).append("\":{}");
    }
    return "\""
        + qid
        + "\":{\"id\":\""
        + qid
        + "\",\"labels\":{\"en\":{\"value\":\""
        + label
        + "\"}},\"sitelinks\":{"
        + sitelinkJson
        + "},\"claims\":{\"P31\":[{\"mainsnak\":{\"snaktype\":\"value\","
        + "\"datavalue\":{\"value\":{\"id\":\"Q215380\"}}}}]}}";
  }

  private static SeedResolver resolverAgainst(StubWikidataServer stub) {
    WikidataClient client = new WikidataClient(stub.baseUri());
    return new SeedResolver(new WikidataEntityResolver(client), new WikidataFacts(client), 5);
  }

  private static NameGroup group(String name) {
    return NameGroup.of(List.of(new SeedRow(name, "musician", "APPROVED"))).get(0);
  }

  @Test
  @DisplayName("a confident literal answer is not asked about a second time")
  void aConfidentLiteralStopsThere() {
    try (StubWikidataServer stub = new StubWikidataServer()) {
      stub.enqueueBody(searchHits("Q090000301"));
      stub.enqueueBody("{\"entities\":{" + band("Q090000301", "Sir Halcyon Drift", 9) + "}}");

      Map<String, Decision> decisions =
          resolverAgainst(stub).resolve(List.of(group("Sir Halcyon Drift")));

      assertThat(decisions.values())
          .singleElement()
          .satisfies(
              d -> {
                assertThat(d.outcome()).isEqualTo(Outcome.ACCEPTED);
                assertThat(d.qid()).isEqualTo("Q090000301");
              });
      // One search, one batched fetch. The honorific fallback was never asked.
      assertThat(stub.requestCount()).isEqualTo(2);
    }
  }

  @Test
  @DisplayName("a literal that resolves to nothing falls through to the next spelling")
  void fallsBackToTheNextSpelling() {
    try (StubWikidataServer stub = new StubWikidataServer()) {
      // Pass one: the literal finds nothing at all.
      stub.enqueueBody(searchHits());
      // Pass two: the honorific-stripped spelling finds the act.
      stub.enqueueBody(searchHits("Q090000302"));
      stub.enqueueBody("{\"entities\":{" + band("Q090000302", "Halcyon Drift", 14) + "}}");

      Map<String, Decision> decisions =
          resolverAgainst(stub).resolve(List.of(group("Sir Halcyon Drift")));

      assertThat(decisions.values())
          .singleElement()
          .satisfies(
              d -> {
                assertThat(d.outcome()).isEqualTo(Outcome.ACCEPTED);
                assertThat(d.qid()).isEqualTo("Q090000302");
              });
    }
  }

  @Test
  @DisplayName("a batch of names shares one fetch")
  void oneFetchServesTheWholeBatch() {
    try (StubWikidataServer stub = new StubWikidataServer()) {
      stub.enqueueBody(searchHits("Q090000303"));
      stub.enqueueBody(searchHits("Q090000304"));
      stub.enqueueBody(
          "{\"entities\":{"
              + band("Q090000303", "Velvet Ossuary", 9)
              + ","
              + band("Q090000304", "Bramble Sons", 7)
              + "}}");

      Map<String, Decision> decisions =
          resolverAgainst(stub)
              .resolve(List.of(group("Velvet Ossuary"), group("The Bramble Sons")));

      assertThat(decisions).hasSize(2);
      assertThat(decisions.values()).allMatch(Decision::accepted);
      assertThat(stub.requestCount()).isEqualTo(3);
    }
  }

  @Test
  @DisplayName("every spelling exhausted with nothing found is unresolved")
  void nothingUnderAnySpelling() {
    try (StubWikidataServer stub = new StubWikidataServer()) {
      stub.enqueueBody(searchHits());
      stub.enqueueBody(searchHits());

      Map<String, Decision> decisions =
          resolverAgainst(stub).resolve(List.of(group("Sir Halcyon Drift")));

      assertThat(decisions.values())
          .singleElement()
          .extracting(Decision::outcome)
          .isEqualTo(Outcome.UNRESOLVED);
    }
  }

  @Test
  @DisplayName("a fallback that only finds a doubtful answer keeps the better report of the two")
  void keepsTheStrongerReport() {
    try (StubWikidataServer stub = new StubWikidataServer()) {
      // The literal finds a name match of the wrong kind — reviewable, with a candidate.
      stub.enqueueBody(searchHits("Q090000305"));
      stub.enqueueBody(
          "{\"entities\":{\"Q090000305\":{\"id\":\"Q090000305\",\"labels\":{\"en\":{\"value\":"
              + "\"Sir Halcyon Drift\"}},\"sitelinks\":{\"enwiki\":{}},\"claims\":{\"P31\":"
              + "[{\"mainsnak\":{\"snaktype\":\"value\",\"datavalue\":{\"value\":{\"id\":"
              + "\"Q11424\"}}}}]}}}}");
      // The fallback spelling finds nothing.
      stub.enqueueBody(searchHits());

      Map<String, Decision> decisions =
          resolverAgainst(stub).resolve(List.of(group("Sir Halcyon Drift")));

      assertThat(decisions.values())
          .singleElement()
          .satisfies(
              d -> {
                assertThat(d.outcome()).isEqualTo(Outcome.REVIEW);
                assertThat(d.qid()).isEqualTo("Q090000305");
              });
    }
  }
}
