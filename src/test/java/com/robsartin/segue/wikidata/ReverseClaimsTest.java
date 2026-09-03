package com.robsartin.segue.wikidata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.robsartin.segue.domain.AssertionRecord;
import com.robsartin.segue.domain.NodeAssertion;
import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.domain.Provenance;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The reverse half of ingest (issue #20, ADR 36).
 *
 * <p>The fixture is a trimmed but otherwise unedited capture of the real Query Service answer for
 * Nick Cave, chosen so every awkward shape in it is one Wikidata actually produces: an entity
 * reached by two properties at once (The Proposition, both scored and written by him), an entity
 * with two {@code P31} values (Jubilee Street is both a single and a song), and an entity with no
 * English label at all, where {@code wikibase:label} hands back the bare QID.
 */
class ReverseClaimsTest {

  private static final String CAVE = "Q192668";
  private static final Instant ASSERTED_AT = Instant.parse("2026-08-25T09:00:00Z");

  private static String resource(String name) throws IOException {
    try (InputStream in = ReverseClaimsTest.class.getResourceAsStream(name)) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private static ReverseClaims lookupAgainst(StubWikidataServer stub) {
    return new ReverseClaims(new WikidataClient(stub.baseUri()));
  }

  @Test
  @DisplayName("it turns a reverse hit on an inverted property into an edge from the seed")
  void invertedPropertiesPointAwayFromTheSeed() throws IOException {
    // The whole point of issue #20: Wikidata states "film P57 person", so the only way to learn
    // what a person directed is to ask which films name them. The stored edge still reads
    // person DIRECTED film, exactly as ClaimMapper would have stored it from the film's side.
    try (StubWikidataServer stub = new StubWikidataServer()) {
      stub.enqueueBody(resource("/wikidata/cave-reverse.json"));

      ReverseClaims.Result result = lookupAgainst(stub).lookup(CAVE, 200, ASSERTED_AT);

      assertThat(result.assertions())
          .contains(
              new AssertionRecord(
                  CAVE, "Q97798779", "DIRECTED", null, null, provenance("P57", "Q97798779")),
              new AssertionRecord(
                  CAVE, "Q2715462", "AUTHORED", null, null, provenance("P50", "Q2715462")));
    }
  }

  @Test
  @DisplayName("it turns a reverse hit on a direct property into an edge pointing at the seed")
  void directPropertiesPointAtTheSeed() throws IOException {
    // P361 is stated on the part ("song part of album"), so a reverse hit found while expanding
    // the album has the song as its subject. Getting this backwards would file the album as a
    // part of the song. This used to be demonstrated with P527, which the reverse pass no longer
    // asks about at all (issue #33) — the rule it demonstrates is unchanged.
    try (StubWikidataServer stub = new StubWikidataServer()) {
      stub.enqueueBody(
          binding(
              "http://www.wikidata.org/prop/direct/P361",
              "http://www.wikidata.org/entity/Q6301911"));

      ReverseClaims.Result result = lookupAgainst(stub).lookup(CAVE, 200, ASSERTED_AT);

      assertThat(result.assertions())
          .containsExactly(
              new AssertionRecord(
                  "Q6301911", CAVE, "PART_OF", null, null, provenance("P361", "Q6301911")));
    }
  }

  @Test
  @DisplayName("a fallback-only property is neither asked for nor recorded if it arrives anyway")
  void fallbackOnlyPropertiesAreNotRecorded() throws IOException {
    // Issue #33. Wikidata defines P527 as the inverse of P463, so a P527 hit on a person is the
    // membership their own P463 already states — the forward pass has it, better evidenced. The
    // fixture still carries the real P527 row this once returned an edge for, which makes this a
    // test of the parser and not only of the query text.
    try (StubWikidataServer stub = new StubWikidataServer()) {
      stub.enqueueBody(resource("/wikidata/cave-reverse.json"));

      ReverseClaims.Result result = lookupAgainst(stub).lookup(CAVE, 200, ASSERTED_AT);

      assertThat(result.assertions())
          .extracting(AssertionRecord::typeCode)
          .doesNotContain("HAS_PART");
      assertThat(result.assertions()).noneMatch(a -> a.fromQid().equals("Q1051182"));
      assertThat(URLDecoder.decode(stub.lastQuery(), StandardCharsets.UTF_8))
          .doesNotContain("wdt:P527");
    }
  }

  @Test
  @DisplayName("one entity reached by two properties yields two assertions, not one")
  void twoPropertiesOnOneEntityAreTwoAssertions() throws IOException {
    try (StubWikidataServer stub = new StubWikidataServer()) {
      stub.enqueueBody(resource("/wikidata/cave-reverse.json"));

      ReverseClaims.Result result = lookupAgainst(stub).lookup(CAVE, 200, ASSERTED_AT);

      assertThat(result.assertions())
          .filteredOn(a -> a.toQid().equals("Q180337"))
          .extracting(AssertionRecord::typeCode)
          .containsExactlyInAnyOrder("COMPOSED_FOR", "WROTE_SCREENPLAY_FOR");
    }
  }

  @Test
  @DisplayName("repeated rows for one entity's several P31 values collapse to one assertion")
  void repeatedP31RowsDoNotDuplicateAnAssertion() throws IOException {
    // The label service and the OPTIONAL P31 multiply rows; the fixture's Jubilee Street
    // arrives twice because it is both a single and a song. Counting rows rather than
    // (property, entity) pairs would inflate the expansion and break the maxNewEdges bound.
    try (StubWikidataServer stub = new StubWikidataServer()) {
      stub.enqueueBody(resource("/wikidata/cave-reverse.json"));

      ReverseClaims.Result result = lookupAgainst(stub).lookup(CAVE, 200, ASSERTED_AT);

      // Six (property, entity) pairs from eight rows: Jubilee Street's two P31 values collapse,
      // and the fixture's P527 row is dropped as a fallback-only property (issue #33).
      assertThat(result.assertions()).hasSize(6);
      assertThat(result.assertions()).doesNotHaveDuplicates();
    }
  }

  @Test
  @DisplayName("neighbour identity comes back inline, so no extra round trip is needed")
  void neighboursArriveInline() throws IOException {
    // This is what makes the reverse lookup affordable. Without it, 73 discovered works mean
    // 73 wbgetentities calls before a single edge can be recorded (see SegueService.expandEntity).
    try (StubWikidataServer stub = new StubWikidataServer()) {
      stub.enqueueBody(resource("/wikidata/cave-reverse.json"));

      ReverseClaims.Result result = lookupAgainst(stub).lookup(CAVE, 200, ASSERTED_AT);

      assertThat(result.neighbors())
          .contains(
              // The P31 the query already returns inline rides along on the claim, so the
              // kind can be re-derived later with no network at all (issue #60, ADR 42).
              new NodeAssertion(
                  "Q180337",
                  NodeKind.WORK,
                  "The Proposition",
                  List.of("Q11424"),
                  nodeProvenance("Q180337")),
              new NodeAssertion(
                  "Q2715462",
                  NodeKind.WORK,
                  "And the Ass Saw the Angel",
                  List.of("Q7725634"),
                  nodeProvenance("Q2715462")));
      assertThat(stub.requestCount()).isEqualTo(1);
    }
  }

  @Test
  @DisplayName("an entity's several P31 values are all kept, in the order they arrived")
  void severalClassesAreAllKept() throws IOException {
    // Jubilee Street is both a single and a song in the fixture. KindMapper takes the first
    // class it RECOGNISES, so the order these are stored in is load-bearing rather than
    // decorative - a set would have made re-derivation depend on hash order (issue #60).
    try (StubWikidataServer stub = new StubWikidataServer()) {
      stub.enqueueBody(resource("/wikidata/cave-reverse.json"));

      ReverseClaims.Result result = lookupAgainst(stub).lookup(CAVE, 200, ASSERTED_AT);

      assertThat(result.neighbors())
          .filteredOn(n -> n.qid().equals("Q6301911"))
          .singleElement()
          .extracting(NodeAssertion::instanceOf)
          .isEqualTo(List.of("Q134556", "Q7366"));
    }
  }

  @Test
  @DisplayName("an entity with no English label is not given the bare QID as its label")
  void unlabelledEntitiesAreNotSynthesised() throws IOException {
    // wikibase:label falls back to the QID string when no label exists, so believing it would
    // fill the graph with nodes called "Q121998451". Dropping the neighbour and keeping the
    // assertion leaves SegueService to try a real fetch, which is the existing behaviour.
    try (StubWikidataServer stub = new StubWikidataServer()) {
      stub.enqueueBody(resource("/wikidata/cave-reverse.json"));

      ReverseClaims.Result result = lookupAgainst(stub).lookup(CAVE, 200, ASSERTED_AT);

      assertThat(result.neighbors()).extracting(NodeAssertion::qid).doesNotContain("Q121998451");
      assertThat(result.assertions()).extracting(AssertionRecord::toQid).contains("Q121998451");
      assertThat(result.neighbors()).noneMatch(n -> n.label().equals(n.qid()));
    }
  }

  @Test
  @DisplayName("reverse-discovered edges are never given the referenced confidence of 1.00")
  void reverseEdgesAreNeverFullyConfident() throws IOException {
    // ADR 23 grades a referenced statement 1.00 and an unreferenced one 0.80. A `wdt:` triple
    // has thrown its reference block away, so we cannot see which this is — and claiming the
    // higher grade on evidence we do not have would put unverified edges at the top of
    // PathRanking. The honest reading of an unknown is the lower one.
    try (StubWikidataServer stub = new StubWikidataServer()) {
      stub.enqueueBody(resource("/wikidata/cave-reverse.json"));

      ReverseClaims.Result result = lookupAgainst(stub).lookup(CAVE, 200, ASSERTED_AT);

      assertThat(result.assertions()).allMatch(a -> a.provenance().confidence() == 0.80);
      assertThat(result.assertions()).allMatch(a -> a.provenance().sourceId().equals("wikidata"));
    }
  }

  @Test
  @DisplayName("the bound is pushed into the query as LIMIT n+1, so truncation stays detectable")
  void boundIsAppliedServerSide() throws IOException {
    // Asking for exactly n cannot tell "there were n" from "there were thousands". The extra
    // row is what makes truncated() an observation rather than a guess, and ORDER BY sitelinks
    // means the n we keep are the most notable rather than an arbitrary slice.
    try (StubWikidataServer stub = new StubWikidataServer()) {
      stub.enqueueBody(resource("/wikidata/cave-reverse.json"));

      lookupAgainst(stub).lookup(CAVE, 15, ASSERTED_AT);

      String sparql = URLDecoder.decode(stub.lastQuery(), StandardCharsets.UTF_8);
      assertThat(sparql).contains("LIMIT 16");
      assertThat(sparql).contains("ORDER BY DESC(?sitelinks)");
      assertThat(sparql).contains("wd:" + CAVE);
    }
  }

  @Test
  @DisplayName("more hits than the bound is reported as truncated")
  void reportsTruncationWhenTheBoundBinds() throws IOException {
    try (StubWikidataServer stub = new StubWikidataServer()) {
      stub.enqueueBody(resource("/wikidata/cave-reverse.json"));

      ReverseClaims.Result result = lookupAgainst(stub).lookup(CAVE, 3, ASSERTED_AT);

      assertThat(result.truncated()).isTrue();
    }
  }

  @Test
  @DisplayName("fewer hits than the bound is not reported as truncated")
  void doesNotReportTruncationWhenNothingWasCut() throws IOException {
    try (StubWikidataServer stub = new StubWikidataServer()) {
      stub.enqueueBody(resource("/wikidata/cave-reverse.json"));

      ReverseClaims.Result result = lookupAgainst(stub).lookup(CAVE, 200, ASSERTED_AT);

      assertThat(result.truncated()).isFalse();
    }
  }

  @Test
  @DisplayName("the query asks about every vocabulary property except the fallback-only ones")
  void queriesEveryMappedProperty() throws IOException {
    // The reverse set is not a second list to keep in step with EdgeTypes — it IS EdgeTypes,
    // for the same reason ClaimMapper's forward whitelist is. A hand-kept subset here would
    // silently stop covering a relation type the day someone registers one. The single
    // subtraction is derived too: a fallback-only type is asked about by nothing (issue #33).
    try (StubWikidataServer stub = new StubWikidataServer()) {
      stub.enqueueBody(resource("/wikidata/cave-reverse.json"));

      lookupAgainst(stub).lookup(CAVE, 200, ASSERTED_AT);

      String sparql = URLDecoder.decode(stub.lastQuery(), StandardCharsets.UTF_8);
      for (String property :
          new String[] {
            "P463", "P175", "P50", "P57", "P58", "P86", "P161", "P144", "P361", "P737", "P166"
          }) {
        assertThat(sparql).contains("wdt:" + property + " ");
      }
      assertThat(sparql).doesNotContain("wdt:P527");
    }
  }

  @Test
  @DisplayName("an empty answer is empty, not an error")
  void emptyAnswerIsEmpty() {
    try (StubWikidataServer stub = new StubWikidataServer()) {
      stub.enqueueBody("{\"head\":{\"vars\":[]},\"results\":{\"bindings\":[]}}");

      ReverseClaims.Result result = lookupAgainst(stub).lookup(CAVE, 200, ASSERTED_AT);

      assertThat(result.assertions()).isEmpty();
      assertThat(result.neighbors()).isEmpty();
      assertThat(result.truncated()).isFalse();
    }
  }

  @Test
  @DisplayName("a row whose subject is not an item is skipped rather than recorded")
  void nonItemRowsAreSkipped() {
    // The query's `?other wikibase:sitelinks ?sitelinks` should already keep lexemes, forms and
    // properties out, so this is defence against the answer not being the one the query asked
    // for. It matters because AssertionRecord does not validate: an "L123-F1" reaching the
    // graph store throws mid-batch, after log entries are already written.
    try (StubWikidataServer stub = new StubWikidataServer()) {
      stub.enqueueBody(
          binding(
              "http://www.wikidata.org/prop/direct/P57", "http://www.wikidata.org/entity/L123"));

      ReverseClaims.Result result = lookupAgainst(stub).lookup(CAVE, 200, ASSERTED_AT);

      assertThat(result.assertions()).isEmpty();
      assertThat(result.neighbors()).isEmpty();
    }
  }

  @Test
  @DisplayName("a row on a property outside the vocabulary is skipped")
  void unmappedPropertiesAreSkipped() {
    // P106 (occupation) is not in EdgeTypes, so there is no edge type to record it as. The
    // query never asks for it; this is what happens if the service answers something else.
    try (StubWikidataServer stub = new StubWikidataServer()) {
      stub.enqueueBody(
          binding(
              "http://www.wikidata.org/prop/direct/P106",
              "http://www.wikidata.org/entity/Q2526255"));

      ReverseClaims.Result result = lookupAgainst(stub).lookup(CAVE, 200, ASSERTED_AT);

      assertThat(result.assertions()).isEmpty();
    }
  }

  @Test
  @DisplayName("an entity that points at itself does not become a self-loop")
  void selfReferencesAreSkipped() {
    // Wikidata does hold reflexive statements (an item influenced by itself, a work part of
    // itself). A self-loop adds no route, and SegueService.neighborOf reports "no neighbour"
    // when both ends are the seed, so the edge would be recorded with nothing to connect.
    try (StubWikidataServer stub = new StubWikidataServer()) {
      stub.enqueueBody(
          binding(
              "http://www.wikidata.org/prop/direct/P737",
              "http://www.wikidata.org/entity/" + CAVE));

      ReverseClaims.Result result = lookupAgainst(stub).lookup(CAVE, 200, ASSERTED_AT);

      assertThat(result.assertions()).isEmpty();
    }
  }

  @Test
  @DisplayName("a non-positive bound is rejected before a query is sent")
  void nonPositiveBoundIsRejected() {
    // ExpandContext already refuses these, so this is belt and braces — but the bound is
    // rendered straight into a SPARQL LIMIT, and "LIMIT 0" is a valid query that answers
    // nothing, which would read as "this entity has no backlinks".
    try (StubWikidataServer stub = new StubWikidataServer()) {
      ReverseClaims reverse = lookupAgainst(stub);

      assertThatThrownBy(() -> reverse.lookup(CAVE, 0, ASSERTED_AT))
          .isInstanceOf(IllegalArgumentException.class);
      assertThat(stub.requestCount()).isZero();
    }
  }

  /** One SPARQL result row, with the bindings this parser reads and nothing else. */
  private static String binding(String propertyIri, String otherIri) {
    return """
        {"head":{"vars":["p","other","otherLabel","type","sitelinks"]},
         "results":{"bindings":[{
           "p":{"type":"uri","value":"%s"},
           "other":{"type":"uri","value":"%s"},
           "otherLabel":{"xml:lang":"en","type":"literal","value":"Something"},
           "sitelinks":{"type":"literal","value":"3"}}]}}"""
        .formatted(propertyIri, otherIri);
  }

  @Test
  @DisplayName("an unreachable Query Service throws the one failure type this adapter has")
  void unreachableQueryServiceThrowsUnavailable() {
    // Callers are entitled to a single failure type from this package — WikidataSourceAdapter
    // turns it into sourceUnavailable rather than failing the whole tool call.
    try (StubWikidataServer stub = new StubWikidataServer()) {
      for (int i = 0; i < 6; i++) {
        stub.enqueueStatus(503);
        stub.enqueueBody("{}");
      }
      ReverseClaims reverse = lookupAgainst(stub);

      assertThatThrownBy(() -> reverse.lookup(CAVE, 200, ASSERTED_AT))
          .isInstanceOf(WikidataUnavailableException.class);
    }
  }

  @Test
  @DisplayName("a malformed qid is rejected before it can be spliced into a query")
  void malformedQidIsRejected() {
    // The seed reaches this class from add_entity, which takes a model-supplied string. A qid
    // that is not a qid would be concatenated straight into SPARQL — this is the injection
    // surface, and it is closed the same way WikidataEntityResolver.entity closes its own.
    try (StubWikidataServer stub = new StubWikidataServer()) {
      ReverseClaims reverse = lookupAgainst(stub);

      assertThatThrownBy(() -> reverse.lookup("Q01 } INSERT DATA { ", 200, ASSERTED_AT))
          .isInstanceOf(IllegalArgumentException.class);
      assertThat(stub.requestCount()).isZero();
    }
  }

  /**
   * The reference names the underlying Wikidata triple, subject first: the statement really is
   * {@code other P seed}, whichever way segue ends up storing the edge. ClaimMapper's fallback can
   * leave the subject implicit because it is always the entity being fetched; here it is the thing
   * that was discovered, so it has to be spelled out or two hits on the same property would share
   * one reference.
   */
  private static Provenance provenance(String property, String other) {
    return new Provenance(
        "wikidata", "wdqs:" + other + ":" + property + ":" + CAVE, ASSERTED_AT, 0.80);
  }

  private static Provenance nodeProvenance(String qid) {
    return new Provenance("wikidata", qid, ASSERTED_AT, 1.00);
  }
}
