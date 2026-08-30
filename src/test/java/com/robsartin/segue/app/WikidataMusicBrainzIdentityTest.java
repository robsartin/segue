package com.robsartin.segue.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.entry;

import com.robsartin.segue.musicbrainz.MusicBrainzIdentity;
import com.robsartin.segue.wikidata.StubWikidataServer;
import com.robsartin.segue.wikidata.WikidataClient;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The Wikidata-backed half of the identity seam {@code musicbrainz} declares.
 *
 * <p>Every response below is written by hand into the stub, and the shapes were taken from a live
 * probe of the Query Service on 2026-08-30 rather than recalled — {@code P434} was read back from
 * the Action API as "MusicBrainz artist ID", and the batched {@code VALUES} form was run against
 * three MBIDs, two of which Wikidata knows and one of which it does not.
 *
 * <p>The MBIDs are the committed MusicBrainz fixture's own, which {@code MusicBrainzClientTest}'s
 * javadoc argues as a reproducible API probe. The QIDs are this repository's {@code Q9000xx} test
 * range.
 */
class WikidataMusicBrainzIdentityTest {

  private static final String QUINTET_MBID = "ee55e4e8-807d-49b1-8470-d1c0898ed7cb";
  private static final String MEMBER_MBID = "9afdcb99-d4ba-41b4-b4b2-467a712bd9fa";
  private static final String UNKNOWN_MBID = "11111111-1111-1111-1111-111111111111";

  private static final String QUINTET_QID = "Q900001";
  private static final String MEMBER_QID = "Q900002";

  private static String bindings(String... rows) {
    return "{\"results\":{\"bindings\":[" + String.join(",", rows) + "]}}";
  }

  private static String itemRow(String qid, String mbid) {
    return "{\"item\":{\"type\":\"uri\",\"value\":\"http://www.wikidata.org/entity/"
        + qid
        + "\"},\"mbid\":{\"type\":\"literal\",\"value\":\""
        + mbid
        + "\"}}";
  }

  private static String mbidRow(String mbid) {
    return "{\"mbid\":{\"type\":\"literal\",\"value\":\"" + mbid + "\"}}";
  }

  private static MusicBrainzIdentity identity(StubWikidataServer stub) {
    return new WikidataMusicBrainzIdentity(new WikidataClient(stub.baseUri()));
  }

  private static String decodedQuery(StubWikidataServer stub) {
    return URLDecoder.decode(stub.lastQuery(), StandardCharsets.UTF_8);
  }

  @Test
  @DisplayName("should map every MBID the query service answers for when it knows them all")
  void shouldMapEveryMbidTheQueryServiceAnswersForWhenItKnowsThemAll() {
    try (StubWikidataServer stub = new StubWikidataServer()) {
      stub.enqueueBody(
          bindings(itemRow(QUINTET_QID, QUINTET_MBID), itemRow(MEMBER_QID, MEMBER_MBID)));

      Map<String, String> resolved = identity(stub).qidsFor(List.of(QUINTET_MBID, MEMBER_MBID));

      assertThat(resolved)
          .containsOnly(entry(QUINTET_MBID, QUINTET_QID), entry(MEMBER_MBID, MEMBER_QID));
      // One round trip for the whole batch, which is the reason qidsFor takes a collection.
      assertThat(stub.requestCount()).isEqualTo(1);
      assertThat(decodedQuery(stub)).contains("wdt:P434").contains(QUINTET_MBID, MEMBER_MBID);
    }
  }

  @Test
  @DisplayName("should drop an MBID Wikidata does not know when the answer omits it")
  void shouldDropAnMbidWikidataDoesNotKnowWhenTheAnswerOmitsIt() {
    try (StubWikidataServer stub = new StubWikidataServer()) {
      // Exactly what the live probe returned: the row for the unknown MBID is simply absent.
      stub.enqueueBody(bindings(itemRow(MEMBER_QID, MEMBER_MBID)));

      Map<String, String> resolved = identity(stub).qidsFor(List.of(MEMBER_MBID, UNKNOWN_MBID));

      // Not an exception, and not a null value under the key: the key is not there at all.
      assertThat(resolved).containsOnly(entry(MEMBER_MBID, MEMBER_QID));
      assertThat(resolved).doesNotContainKey(UNKNOWN_MBID);
    }
  }

  @Test
  @DisplayName("should ask the query service nothing when the batch is empty")
  void shouldAskTheQueryServiceNothingWhenTheBatchIsEmpty() {
    try (StubWikidataServer stub = new StubWikidataServer()) {
      // The adapter calls qidsFor unconditionally, and a seed whose relations are all outside the
      // whitelist leaves nothing to resolve. A VALUES clause with no members is a round trip that
      // can only return nothing.
      assertThat(identity(stub).qidsFor(List.of())).isEmpty();

      assertThat(stub.requestCount()).isZero();
    }
  }

  @Test
  @DisplayName("should drop an MBID that is not a UUID rather than put it in a SPARQL query")
  void shouldDropAnMbidThatIsNotAUuidRatherThanPutItInASparqlQuery() {
    try (StubWikidataServer stub = new StubWikidataServer()) {
      stub.enqueueBody(bindings(itemRow(MEMBER_QID, MEMBER_MBID)));

      Map<String, String> resolved =
          identity(stub).qidsFor(List.of(MEMBER_MBID, "\" } UNION { ?item ?p ?o . # "));

      assertThat(resolved).containsOnly(entry(MEMBER_MBID, MEMBER_QID));
      assertThat(decodedQuery(stub)).doesNotContain("UNION");
    }
  }

  @Test
  @DisplayName("should ignore a binding whose item is not a QID when the answer is malformed")
  void shouldIgnoreABindingWhoseItemIsNotAQidWhenTheAnswerIsMalformed() {
    try (StubWikidataServer stub = new StubWikidataServer()) {
      stub.enqueueBody(
          bindings(
              "{\"item\":{\"type\":\"uri\",\"value\":\"http://www.wikidata.org/entity/L12345\"},"
                  + "\"mbid\":{\"type\":\"literal\",\"value\":\""
                  + QUINTET_MBID
                  + "\"}}",
              itemRow(MEMBER_QID, MEMBER_MBID)));

      Map<String, String> resolved = identity(stub).qidsFor(List.of(QUINTET_MBID, MEMBER_MBID));

      assertThat(resolved).containsOnly(entry(MEMBER_MBID, MEMBER_QID));
    }
  }

  @Test
  @DisplayName("should resolve nothing rather than throw when Wikidata is unavailable")
  void shouldResolveNothingRatherThanThrowWhenWikidataIsUnavailable() {
    try (StubWikidataServer stub = new StubWikidataServer()) {
      // 404 rather than 503: WikidataClient retries a transient status four times, and the
      // property under test is that the failure never leaves this class, not how long it waits.
      stub.enqueueStatus(404);

      MusicBrainzIdentity identity = identity(stub);

      assertThatCode(() -> assertThat(identity.qidsFor(List.of(MEMBER_MBID))).isEmpty())
          .doesNotThrowAnyException();
    }
  }

  @Test
  @DisplayName("should find the MBID for a seed QID when Wikidata states one")
  void shouldFindTheMbidForASeedQidWhenWikidataStatesOne() {
    try (StubWikidataServer stub = new StubWikidataServer()) {
      stub.enqueueBody(bindings(mbidRow(QUINTET_MBID)));

      assertThat(identity(stub).mbidFor(QUINTET_QID)).contains(QUINTET_MBID);
      assertThat(decodedQuery(stub)).contains("wd:" + QUINTET_QID).contains("wdt:P434");
    }
  }

  @Test
  @DisplayName("should find no MBID when Wikidata states none for the seed")
  void shouldFindNoMbidWhenWikidataStatesNoneForTheSeed() {
    try (StubWikidataServer stub = new StubWikidataServer()) {
      stub.enqueueBody(bindings());

      assertThat(identity(stub).mbidFor(QUINTET_QID)).isEmpty();
    }
  }

  @Test
  @DisplayName("should find no MBID rather than throw when Wikidata is unavailable")
  void shouldFindNoMbidRatherThanThrowWhenWikidataIsUnavailable() {
    try (StubWikidataServer stub = new StubWikidataServer()) {
      stub.enqueueStatus(404);

      MusicBrainzIdentity identity = identity(stub);

      assertThatCode(() -> assertThat(identity.mbidFor(QUINTET_QID)).isEmpty())
          .doesNotThrowAnyException();
    }
  }

  @Test
  @DisplayName("should find no MBID when the one Wikidata states is not an MBID")
  void shouldFindNoMbidWhenTheOneWikidataStatesIsNotAnMbid() {
    try (StubWikidataServer stub = new StubWikidataServer()) {
      // P434 is an external-id, so its values are contributor-entered strings, and the one that
      // comes back here becomes a path segment in a MusicBrainz URL. MusicBrainzClient encodes it,
      // so this is not about injection; it is about not spending a request on a certain 404.
      stub.enqueueBody(bindings(mbidRow("not-an-mbid")));

      assertThat(identity(stub).mbidFor(QUINTET_QID)).isEmpty();
    }
  }

  @Test
  @DisplayName("should keep one QID per MBID when Wikidata states two items for one")
  void shouldKeepOneQidPerMbidWhenWikidataStatesTwoItemsForOne() {
    try (StubWikidataServer stub = new StubWikidataServer()) {
      // Measured on 2026-08-30: a query for items with more than one P434 returns hits, so the
      // mapping is not one-to-one in either direction and the answer must not depend on row order.
      stub.enqueueBody(
          bindings(itemRow(MEMBER_QID, MEMBER_MBID), itemRow(QUINTET_QID, MEMBER_MBID)));

      assertThat(identity(stub).qidsFor(List.of(MEMBER_MBID)))
          .containsOnly(entry(MEMBER_MBID, MEMBER_QID));
      assertThat(decodedQuery(stub)).contains("ORDER BY");
    }
  }

  @Test
  @DisplayName("should ask the query service nothing when the seed is not a QID")
  void shouldAskTheQueryServiceNothingWhenTheSeedIsNotAQid() {
    try (StubWikidataServer stub = new StubWikidataServer()) {
      Optional<String> mbid = identity(stub).mbidFor("https://example.invalid/x");

      assertThat(mbid).isEmpty();
      assertThat(stub.requestCount()).isZero();
    }
  }
}
