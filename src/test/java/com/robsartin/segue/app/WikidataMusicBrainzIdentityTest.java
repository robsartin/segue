package com.robsartin.segue.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

import com.robsartin.segue.musicbrainz.MusicBrainzIdentity;
import com.robsartin.segue.musicbrainz.MusicBrainzIdentityUnavailableException;
import com.robsartin.segue.wikidata.StubWikidataServer;
import com.robsartin.segue.wikidata.WikidataClient;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
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
 * javadoc argues as a reproducible API probe. The QIDs are the fixture's unallocatable stand-ins: a
 * leading zero, which Wikidata's item-id grammar refuses, so the mapping asserted here cannot tie a
 * real MBID to a real QID (ADR 58).
 */
class WikidataMusicBrainzIdentityTest {

  private static final String QUINTET_MBID = "ee55e4e8-807d-49b1-8470-d1c0898ed7cb";
  private static final String MEMBER_MBID = "9afdcb99-d4ba-41b4-b4b2-467a712bd9fa";
  private static final String UNKNOWN_MBID = "11111111-1111-1111-1111-111111111111";

  private static final String QUINTET_QID = "Q0900001";
  private static final String MEMBER_QID = "Q0900002";

  /**
   * {@code application.yaml}'s shipped {@code segue.expand.max-new-edges}. {@code
   * MusicBrainzSourceAdapter} spends that bound on relations <i>before</i> it resolves any
   * neighbour, so one expansion under the shipped configuration can hand {@code qidsFor} this many
   * MBIDs in a single call. It is restated here as the size the batching has to survive; {@code
   * application.yaml} remains the authority on the setting.
   */
  private static final int SHIPPED_MAX_NEW_EDGES = 200;

  /**
   * The classic ceiling on an HTTP request line, which is what a {@code GET} spends its query on.
   */
  private static final int REQUEST_LINE_LIMIT = 8192;

  /**
   * The endpoint {@code WikidataClient.queryService()} aims at, with its {@code ?}. The stub below
   * is on {@code 127.0.0.1} with a shorter URL, so a request that fits against the stub can still
   * be over the limit in production; what is load-bearing here is this string's length, not the
   * string — {@code WikidataClient} owns the URL itself.
   */
  private static final String PRODUCTION_QUERY_URI = "https://query.wikidata.org/sparql?";

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

  private static String everyDecodedQuery(StubWikidataServer stub) {
    return stub.queries().stream()
        .map(raw -> URLDecoder.decode(raw, StandardCharsets.UTF_8))
        .collect(Collectors.joining("\n"));
  }

  /**
   * {@code count} distinct MBIDs of the shape MusicBrainz sends and {@code
   * WikidataMusicBrainzIdentity} accepts — 36 characters, hyphenated hex — so the bytes these put
   * on the wire are the bytes a real neighbourhood of this size would.
   */
  private static List<String> mbids(int count) {
    return IntStream.range(0, count)
        .mapToObj(i -> new UUID(0x0123456789abcdefL, i).toString())
        .toList();
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
      // One round trip for a batch this size, which is the reason qidsFor takes a collection.
      // A batch large enough to need more than one is the request-line test below.
      assertThat(stub.requestCount()).isEqualTo(1);
      assertThat(decodedQuery(stub)).contains("wdt:P434").contains(QUINTET_MBID, MEMBER_MBID);
    }
  }

  @Test
  @DisplayName("should split the batch when one query would outgrow the request-line limit")
  void shouldSplitTheBatchWhenOneQueryWouldOutgrowTheRequestLineLimit() {
    try (StubWikidataServer stub = new StubWikidataServer()) {
      List<String> mbids = mbids(SHIPPED_MAX_NEW_EDGES);

      identity(stub).qidsFor(mbids);

      // Measured through WikidataClient's own encoding on 2026-08-30: the request URI is
      // 180 + 43n bytes, so 200 MBIDs in one VALUES clause is 8,780 — over the limit, and a 414
      // is not transient, so the whole neighbourhood would be dropped with no flag raised.
      assertThat(stub.queries()).isNotEmpty();
      for (String query : stub.queries()) {
        assertThat(PRODUCTION_QUERY_URI.length() + query.length())
            .as("request-line bytes for one batched query")
            .isLessThanOrEqualTo(REQUEST_LINE_LIMIT);
      }
      // Splitting must not lose anybody: every MBID handed in is asked about in some request.
      assertThat(everyDecodedQuery(stub)).contains(mbids.toArray(String[]::new));
    }
  }

  @Test
  @DisplayName("should resolve MBIDs from every chunk when the batch is split")
  void shouldResolveMbidsFromEveryChunkWhenTheBatchIsSplit() {
    try (StubWikidataServer stub = new StubWikidataServer()) {
      List<String> mbids = mbids(SHIPPED_MAX_NEW_EDGES);
      String inTheFirstChunk = mbids.get(0);
      String inTheLastChunk = mbids.get(mbids.size() - 1);
      // One body per request, in order. That there are exactly two is the shipped bound against
      // the batch size WikidataMusicBrainzIdentity chose; changing either changes this test.
      stub.enqueueBody(bindings(itemRow(QUINTET_QID, inTheFirstChunk)));
      stub.enqueueBody(bindings(itemRow(MEMBER_QID, inTheLastChunk)));

      Map<String, String> resolved = identity(stub).qidsFor(mbids);

      assertThat(stub.requestCount()).isEqualTo(2);
      assertThat(resolved)
          .containsOnly(entry(inTheFirstChunk, QUINTET_QID), entry(inTheLastChunk, MEMBER_QID));
    }
  }

  @Test
  @DisplayName("should resolve nothing rather than part of the batch when one chunk fails")
  void shouldResolveNothingRatherThanPartOfTheBatchWhenOneChunkFails() {
    try (StubWikidataServer stub = new StubWikidataServer()) {
      List<String> mbids = mbids(SHIPPED_MAX_NEW_EDGES);
      stub.enqueueBody(bindings(itemRow(QUINTET_QID, mbids.get(0))));
      stub.enqueueStatus(200);
      // 404 rather than 503 for the same reason as the test below: what is under test is the
      // shape of the answer, not how long WikidataClient waits before giving it.
      stub.enqueueStatus(404);

      MusicBrainzIdentity identity = identity(stub);

      // Not the first chunk's answer, and since issue #148 not an empty map either. A half-filled
      // map is indistinguishable from Wikidata knowing no QID for the missing half, which is the
      // normal drop path (ADR 22 clause 2) and is reported to nobody — so a chunk failure would
      // become silent data loss for an arbitrary subset. An empty map had the same problem for
      // every subset at once; one request's failure now fails the call out loud.
      assertThatThrownBy(() -> identity.qidsFor(mbids))
          .isInstanceOf(MusicBrainzIdentityUnavailableException.class);
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
  @DisplayName(
      "should report the bridge unavailable rather than an empty map when Wikidata is down")
  void shouldReportTheBridgeUnavailableRatherThanAnEmptyMapWhenWikidataIsDown() {
    // Reversed by issue #148, for the same reason as the seed lookup and with more to lose: an
    // absent key is how ADR 22 clause 2 declines to reach a neighbour, which happens to 49% of
    // them, so an empty map on failure was silent loss of every neighbour of the seed.
    try (StubWikidataServer stub = new StubWikidataServer()) {
      // 404 rather than 503: WikidataClient retries a transient status four times, and the
      // property under test is what the failure becomes, not how long it waits first.
      stub.enqueueStatus(404);

      MusicBrainzIdentity identity = identity(stub);

      assertThatThrownBy(() -> identity.qidsFor(List.of(MEMBER_MBID)))
          .isInstanceOf(MusicBrainzIdentityUnavailableException.class);
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
  @DisplayName("should report the bridge unavailable rather than no MBID when Wikidata is down")
  void shouldReportTheBridgeUnavailableRatherThanNoMbidWhenWikidataIsDown() {
    // Reversed by issue #148. This test used to assert that the failure never left this class,
    // and swallowing it was the whole defect: an empty Optional is how MusicBrainzSourceAdapter
    // is told "MusicBrainz holds no record bridged to this seed", so an outage arrived downstream
    // as "this artist has no members" with sourceUnavailable false. The seam now declares a
    // failure, and this class is the one that has one to report.
    try (StubWikidataServer stub = new StubWikidataServer()) {
      // 404 rather than 503: WikidataClient retries a transient status four times, and the
      // property under test is what the failure becomes, not how long it waits first.
      stub.enqueueStatus(404);

      MusicBrainzIdentity identity = identity(stub);

      assertThatThrownBy(() -> identity.mbidFor(QUINTET_QID))
          .isInstanceOf(MusicBrainzIdentityUnavailableException.class);
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
