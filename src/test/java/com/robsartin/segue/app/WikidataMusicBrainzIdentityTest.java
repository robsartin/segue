package com.robsartin.segue.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.musicbrainz.BridgedIdentity;
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

  /** {@code Q5}, human — the class the fixture's neighbours all carry. */
  private static final String HUMAN = "Q5";

  /** {@code Q215380}, musical group. A second class on one item, for the row-multiplying case. */
  private static final String MUSICAL_GROUP = "Q215380";

  private static final String MEMBER_LABEL = "A Player Wikidata Has A Name For";

  /**
   * The committed MusicBrainz fixture's mappable relations, all naming distinct target MBIDs —
   * {@code NeighbourFetchCountTest} is the authority on that count. A whole neighbourhood of this
   * size is what one expansion hands the bridge, and it must still cost one round trip.
   */
  private static final int FIXTURE_MAPPABLE_NEIGHBOURS = 22;

  /**
   * {@code application.yaml}'s shipped {@code segue.expand.max-new-edges}. {@code
   * MusicBrainzSourceAdapter} spends that bound on relations <i>before</i> it resolves any
   * neighbour, so one expansion under the shipped configuration can hand {@code identitiesFor} this
   * many MBIDs in a single call. It is restated here as the size the batching has to survive;
   * {@code application.yaml} remains the authority on the setting.
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

  /**
   * One binding of the widened query: the item and its MBID, plus the label service's {@code
   * ?itemLabel} and one {@code ?type}. A class of null is an item with no {@code P31} statement, on
   * which the {@code OPTIONAL} leaves the variable unbound and the binding simply lacks the key.
   */
  private static String describedRow(String qid, String mbid, String label, String classQid) {
    StringBuilder row = new StringBuilder();
    row.append("{\"item\":{\"type\":\"uri\",\"value\":\"http://www.wikidata.org/entity/")
        .append(qid)
        .append("\"},\"mbid\":{\"type\":\"literal\",\"value\":\"")
        .append(mbid)
        .append("\"}");
    if (label != null) {
      row.append(",\"itemLabel\":{\"type\":\"literal\",\"xml:lang\":\"en\",\"value\":\"")
          .append(label)
          .append("\"}");
    }
    if (classQid != null) {
      row.append(",\"type\":{\"type\":\"uri\",\"value\":\"http://www.wikidata.org/entity/")
          .append(classQid)
          .append("\"}");
    }
    return row.append("}").toString();
  }

  /**
   * The QID under each MBID. The mapping assertions below are about which item each MBID bridged
   * to; the description that now arrives on the same answer has its own tests further down.
   */
  private static Map<String, String> qids(Map<String, BridgedIdentity> bridged) {
    return bridged.entrySet().stream()
        .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().qid()));
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

      Map<String, String> resolved =
          qids(identity(stub).identitiesFor(List.of(QUINTET_MBID, MEMBER_MBID)));

      assertThat(resolved)
          .containsOnly(entry(QUINTET_MBID, QUINTET_QID), entry(MEMBER_MBID, MEMBER_QID));
      // One round trip for a batch this size, which is why the seam takes a collection.
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

      identity(stub).identitiesFor(mbids);

      // The measurement of 2026-09-02, driven through WikidataClient's
      // encoding: the request URI is 351 + 43n bytes, so 200 MBIDs in one VALUES clause is 8,951
      // — over the limit, and a 414 is not transient. The label service, the OPTIONAL P31 and the
      // DISTINCT cost 171 bytes once and nothing per MBID; MAX_MBIDS_PER_QUERY holds the full
      // table, re-measured when review added the DISTINCT rather than adjusted by nine. This test
      // is the guard that a later line added to the query cannot quietly push a shipped batch
      // over, which is exactly what happened to the figures this replaced. It stands in for the
      // narrow template's own split test, deleted with qidsFor: there is one batched query now.
      assertThat(stub.queries()).isNotEmpty();
      for (String query : stub.queries()) {
        assertThat(PRODUCTION_QUERY_URI.length() + query.length())
            .as("request-line bytes for one batched query")
            .isLessThanOrEqualTo(REQUEST_LINE_LIMIT);
      }
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

      Map<String, String> resolved = qids(identity(stub).identitiesFor(mbids));

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
      assertThatThrownBy(() -> identity.identitiesFor(mbids))
          .isInstanceOf(MusicBrainzIdentityUnavailableException.class);
    }
  }

  @Test
  @DisplayName("should drop an MBID Wikidata does not know when the answer omits it")
  void shouldDropAnMbidWikidataDoesNotKnowWhenTheAnswerOmitsIt() {
    try (StubWikidataServer stub = new StubWikidataServer()) {
      // Exactly what the live probe returned: the row for the unknown MBID is simply absent.
      stub.enqueueBody(bindings(itemRow(MEMBER_QID, MEMBER_MBID)));

      Map<String, String> resolved =
          qids(identity(stub).identitiesFor(List.of(MEMBER_MBID, UNKNOWN_MBID)));

      // Not an exception, and not a null value under the key: the key is not there at all.
      assertThat(resolved).containsOnly(entry(MEMBER_MBID, MEMBER_QID));
      assertThat(resolved).doesNotContainKey(UNKNOWN_MBID);
    }
  }

  @Test
  @DisplayName("should ask the query service nothing when the batch is empty")
  void shouldAskTheQueryServiceNothingWhenTheBatchIsEmpty() {
    try (StubWikidataServer stub = new StubWikidataServer()) {
      // The adapter calls the bridge unconditionally, and a seed whose relations are all outside
      // the whitelist leaves nothing to resolve. A VALUES clause with no members is a round trip
      // that can only return nothing.
      assertThat(identity(stub).identitiesFor(List.of())).isEmpty();

      assertThat(stub.requestCount()).isZero();
    }
  }

  @Test
  @DisplayName("should drop an MBID that is not a UUID rather than put it in a SPARQL query")
  void shouldDropAnMbidThatIsNotAUuidRatherThanPutItInASparqlQuery() {
    try (StubWikidataServer stub = new StubWikidataServer()) {
      stub.enqueueBody(bindings(itemRow(MEMBER_QID, MEMBER_MBID)));

      Map<String, String> resolved =
          qids(identity(stub).identitiesFor(List.of(MEMBER_MBID, "\" } UNION { ?item ?p ?o . # ")));

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

      Map<String, String> resolved =
          qids(identity(stub).identitiesFor(List.of(QUINTET_MBID, MEMBER_MBID)));

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

      assertThatThrownBy(() -> identity.identitiesFor(List.of(MEMBER_MBID)))
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

      assertThat(qids(identity(stub).identitiesFor(List.of(MEMBER_MBID))))
          .containsOnly(entry(MEMBER_MBID, MEMBER_QID));
      assertThat(decodedQuery(stub)).contains("ORDER BY");
    }
  }

  @Test
  @DisplayName("should carry the label and classes back on the round trip it already makes")
  void shouldCarryTheLabelAndClassesBackOnTheRoundTripItAlreadyMakes() {
    try (StubWikidataServer stub = new StubWikidataServer()) {
      stub.enqueueBody(bindings(describedRow(MEMBER_QID, MEMBER_MBID, MEMBER_LABEL, HUMAN)));

      Map<String, BridgedIdentity> bridged = identity(stub).identitiesFor(List.of(MEMBER_MBID));

      assertThat(bridged).containsOnlyKeys(MEMBER_MBID);
      BridgedIdentity member = bridged.get(MEMBER_MBID);
      assertThat(member.qid()).isEqualTo(MEMBER_QID);
      assertThat(member.label()).isEqualTo(MEMBER_LABEL);
      assertThat(member.instanceOf()).containsExactly(HUMAN);
      assertThat(member.kind()).isEqualTo(NodeKind.PERSON);
      // The whole point of the change: more columns on the call already made, not a second call.
      assertThat(stub.requestCount()).isEqualTo(1);
      // Full P31 statements, not truthy ones (the design note's controller ruling 2). wdt:P31
      // exposes only the best-ranked value, so a bridge reading it could hand back FEWER classes
      // than ClaimMapper.instanceOf does for the same entity — and TinkerGraphStore.upsertNode is
      // last-writer-wins, so a refresh would shrink an existing node's classes.
      assertThat(decodedQuery(stub))
          .contains("wdt:P434")
          .contains("p:P31/ps:P31")
          .contains("wikibase:label");
    }
  }

  @Test
  @DisplayName("should gather one identity from every row when an item states two classes")
  void shouldGatherOneIdentityFromEveryRowWhenAnItemStatesTwoClasses() {
    try (StubWikidataServer stub = new StubWikidataServer()) {
      // The OPTIONAL P31 and the label service both multiply rows: an item stating two classes
      // comes back as two bindings that differ only in ?type. A row is not an entity, and a
      // parser that believed it were would keep whichever class the server happened to send
      // first — which is the accident issue #87 removed from KindMapper's own answer.
      stub.enqueueBody(
          bindings(
              describedRow(MEMBER_QID, MEMBER_MBID, MEMBER_LABEL, MUSICAL_GROUP),
              describedRow(MEMBER_QID, MEMBER_MBID, MEMBER_LABEL, HUMAN)));

      Map<String, BridgedIdentity> bridged = identity(stub).identitiesFor(List.of(MEMBER_MBID));

      assertThat(bridged).containsOnlyKeys(MEMBER_MBID);
      BridgedIdentity member = bridged.get(MEMBER_MBID);
      assertThat(member.instanceOf()).containsExactlyInAnyOrder(HUMAN, MUSICAL_GROUP);
      // PERSON rather than GROUP, and not because Q5 arrived second: KindMapper's precedence
      // decides when an entity's classes disagree, which is only reachable if both got here.
      assertThat(member.kind()).isEqualTo(NodeKind.PERSON);
    }
  }

  @Test
  @DisplayName("should leave the identity unlabelled when the label service hands back the QID")
  void shouldLeaveTheIdentityUnlabelledWhenTheLabelServiceHandsBackTheQid() {
    try (StubWikidataServer stub = new StubWikidataServer()) {
      // wikibase:label answers with the bare QID when no English label exists. Believing it fills
      // the graph with nodes called "Q121998451" — ReverseClaims.rememberLabel refuses the same
      // string against the same service, and the rule cannot have two answers here.
      stub.enqueueBody(bindings(describedRow(MEMBER_QID, MEMBER_MBID, MEMBER_QID, HUMAN)));

      Map<String, BridgedIdentity> bridged = identity(stub).identitiesFor(List.of(MEMBER_MBID));

      BridgedIdentity member = bridged.get(MEMBER_MBID);
      assertThat(member.label()).isNull();
      // Undescribed, not dropped: the QID is still bridged and the classes are still here, so the
      // caller can fall back to a real fetch rather than lose the neighbour altogether.
      assertThat(member.qid()).isEqualTo(MEMBER_QID);
      assertThat(member.instanceOf()).containsExactly(HUMAN);
    }
  }

  @Test
  @DisplayName("should bridge an identity with no classes when the item states no P31")
  void shouldBridgeAnIdentityWithNoClassesWhenTheItemStatesNoP31() {
    try (StubWikidataServer stub = new StubWikidataServer()) {
      // The OPTIONAL leaves ?type unbound, so the binding simply lacks the key. Not every item
      // Wikidata knows states a class.
      stub.enqueueBody(bindings(describedRow(MEMBER_QID, MEMBER_MBID, MEMBER_LABEL, null)));

      Map<String, BridgedIdentity> bridged = identity(stub).identitiesFor(List.of(MEMBER_MBID));

      // Present with nothing in it, rather than absent: an unclassified neighbour is one the
      // caller must decline to describe, and it can only decline what it was told about. Dropping
      // the entry here would hide a resolved QID behind ADR 22 clause 2's ordinary silence.
      assertThat(bridged).containsOnlyKeys(MEMBER_MBID);
      BridgedIdentity member = bridged.get(MEMBER_MBID);
      assertThat(member.instanceOf()).isEmpty();
      assertThat(member.label()).isEqualTo(MEMBER_LABEL);
      assertThat(member.kind()).isEqualTo(NodeKind.CONCEPT);
    }
  }

  @Test
  @DisplayName("should still spend one round trip when a whole neighbourhood is bridged at once")
  void shouldStillSpendOneRoundTripWhenAWholeNeighbourhoodIsBridgedAtOnce() {
    try (StubWikidataServer stub = new StubWikidataServer()) {
      identity(stub).identitiesFor(mbids(FIXTURE_MAPPABLE_NEIGHBOURS));

      assertThat(stub.requestCount()).isEqualTo(1);
      assertThat(decodedQuery(stub)).contains("p:P31/ps:P31").contains("wikibase:label");
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
