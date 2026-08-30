package com.robsartin.segue.musicbrainz;

import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.segue.domain.AssertionRecord;
import com.robsartin.segue.domain.Candidate;
import com.robsartin.segue.domain.EdgeRecord;
import com.robsartin.segue.domain.NodeAssertion;
import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.domain.Provenance;
import com.robsartin.segue.ingest.GraphProjector;
import com.robsartin.segue.ingest.IngestService;
import com.robsartin.segue.mcp.SegueService;
import com.robsartin.segue.port.AffinityStore;
import com.robsartin.segue.port.AssertionLog;
import com.robsartin.segue.port.EntityResolver;
import com.robsartin.segue.port.ExpandContext;
import com.robsartin.segue.port.GraphStore;
import com.robsartin.segue.port.SourceAdapter;
import com.robsartin.segue.port.SourceAdapters;
import com.robsartin.segue.sqlite.SqliteAffinityStore;
import com.robsartin.segue.sqlite.SqliteAssertionLog;
import com.robsartin.segue.tinker.TinkerGraphStore;
import com.robsartin.segue.wikidata.StubWikidataServer;
import com.robsartin.segue.wikidata.WikidataClient;
import com.robsartin.segue.wikidata.WikidataEntityResolver;
import com.robsartin.segue.wikidata.WikidataSourceAdapter;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ADR 23's corroboration, exercised for the first time.
 *
 * <p>{@code EdgeRecord.corroboration()} counts distinct {@code sourceId}s and has counted one since
 * it was written, because until now every claim in this repository came from Wikidata. This is the
 * fourth of issue #91's acceptance criteria and it needs no new mechanism — only a second asserter
 * — so what it really tests is that two independently built adapters, given the same pair, produce
 * assertions the store recognises as the same edge.
 *
 * <p><b>Both adapters are the production ones, off fixtures, and no network is reached.</b>
 * MusicBrainz replays the committed {@code artist-rels} response through {@code
 * MusicBrainzClient.readingFrom}; Wikidata answers from two in-process {@link StubWikidataServer}s,
 * one per endpoint, because an expansion asks the Action API for the claims stated on the seed and
 * the Query Service for the ones stated about it.
 *
 * <p><b>The corroborating claim comes out of Wikidata's reverse pass, which is the honest
 * shape.</b> Wikidata states band membership on the member (P463), so expanding a group finds it
 * only by asking which items point at the group — ADR 36. MusicBrainz states the same relation on
 * the pair and returns it from either end, which is why its adapter needs one call. Two different
 * ingest shapes, one edge, and the count is what says so.
 *
 * <p>The MBIDs are the committed fixture's own, argued in {@code MusicBrainzClientTest}'s javadoc
 * as a reproducible API probe; the QIDs are this repository's {@code Q9000xx} test range, and the
 * mapping between them is this test's, not a fact about anyone.
 */
class CorroborationAcrossSourcesTest {

  private static final String QUINTET_MBID = "ee55e4e8-807d-49b1-8470-d1c0898ed7cb";
  private static final String FIRST_MEMBER_MBID = "9afdcb99-d4ba-41b4-b4b2-467a712bd9fa";

  private static final String QUINTET_QID = "Q900001";
  private static final String MEMBER_QID = "Q900002";
  private static final String OTHER_MEMBER_QID = "Q900003";

  private static final Instant NOW = Instant.parse("2026-08-30T12:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  private AssertionLog log;
  private GraphStore graph;
  private IngestService ingest;
  private AffinityStore affinity;

  @BeforeEach
  void setUp() {
    log = SqliteAssertionLog.inMemory();
    graph = new TinkerGraphStore();
    ingest = new IngestService(log, graph);
    affinity = SqliteAffinityStore.inMemory();
    ingest.record(
        new NodeAssertion(
            QUINTET_QID,
            NodeKind.GROUP,
            "An Ensemble",
            new Provenance("wikidata", QUINTET_QID, NOW, 1.00)));
  }

  @AfterEach
  void tearDown() {
    affinity.close();
    graph.close();
    log.close();
  }

  @Test
  @DisplayName("should count two distinct sources when both assert the same membership")
  void shouldCountTwoDistinctSourcesWhenBothAssertTheSameMembership() {
    try (StubWikidataServer actionApi = new StubWikidataServer();
        StubWikidataServer queryService = new StubWikidataServer()) {
      actionApi.enqueueBody(seedEntity());
      queryService.enqueueBody(memberOfBacklink(MEMBER_QID));

      expandWith(wikidata(actionApi, queryService), musicBrainz(MEMBER_QID));

      // Rebuilt from the log rather than read off the store the expansion wrote to: the graph is
      // a projection (ADR 19), and corroboration is only real if it survives the fold.
      EdgeRecord edge = replayed(store -> store.corroborated(2));
      assertThat(edge.fromQid()).isEqualTo(MEMBER_QID);
      assertThat(edge.toQid()).isEqualTo(QUINTET_QID);
      assertThat(edge.typeCode()).isEqualTo("MEMBER_OF");
      assertThat(edge.corroboration()).isEqualTo(2);
      assertThat(edge.sources())
          .extracting(Provenance::sourceId)
          .containsExactlyInAnyOrder("wikidata", "musicbrainz");
    }
  }

  /**
   * GAP 3 and GAP 4 of the design note, established rather than argued. Not a decision this task
   * makes: it is pinned here so Task 6's ADR can cite a run instead of a reading.
   *
   * <p>{@code SegueService} builds one {@code ExpandContext} and hands the same one to every
   * adapter, then bounds the CONCATENATION of what they return. So with a bound of one and two
   * adapters that each have something to say, the whole budget goes to whichever adapter the list
   * names first, and the other's work is discarded after it has already been paid for.
   */
  @Test
  @DisplayName("should give the whole bound to the first adapter when both have something to say")
  void shouldGiveTheWholeBoundToTheFirstAdapterWhenBothHaveSomethingToSay() {
    try (StubWikidataServer actionApi = new StubWikidataServer();
        StubWikidataServer queryService = new StubWikidataServer()) {
      actionApi.enqueueBody(seedEntity());
      // A different member from MusicBrainz's, so which adapter won is visible in the result.
      queryService.enqueueBody(memberOfBacklink(OTHER_MEMBER_QID));

      SourceAdapter musicBrainz = musicBrainz(MEMBER_QID);
      // The control, and without it this test proves nothing: "the log holds only wikidata's
      // claim" is equally true when MusicBrainz had nothing to say, so the test would stay green
      // if the fixture or the stub mapping quietly stopped producing an assertion. Asking the same
      // adapter the same question at the same bound is what makes the survivor a race result.
      assertThat(
              musicBrainz
                  .expand(graph.node(QUINTET_QID).orElseThrow(), new ExpandContext(1))
                  .assertions())
          .hasSize(1);

      expandWith(1, wikidata(actionApi, queryService), musicBrainz);

      assertThat(edgeAssertionsInTheLog())
          .extracting(a -> a.provenance().sourceId())
          .containsExactly("wikidata");
    }
  }

  @Test
  @DisplayName("should give the whole bound to musicbrainz when the adapter list is reversed")
  void shouldGiveTheWholeBoundToMusicbrainzWhenTheAdapterListIsReversed() {
    try (StubWikidataServer actionApi = new StubWikidataServer();
        StubWikidataServer queryService = new StubWikidataServer()) {
      actionApi.enqueueBody(seedEntity());
      queryService.enqueueBody(memberOfBacklink(OTHER_MEMBER_QID));

      // The same two adapters and the same bound; only the order differs.
      expandWith(1, musicBrainz(MEMBER_QID), wikidata(actionApi, queryService));

      assertThat(edgeAssertionsInTheLog())
          .extracting(a -> a.provenance().sourceId())
          .containsExactly("musicbrainz");
    }
  }

  // ---- helpers ----------------------------------------------------------

  private void expandWith(SourceAdapter... adapters) {
    expandWith(200, adapters);
  }

  private void expandWith(int maxNewEdges, SourceAdapter... adapters) {
    SegueService service =
        new SegueService(
            new AlwaysResolves(),
            graph,
            ingest,
            new SourceAdapters(List.of(adapters)),
            affinity,
            CLOCK);
    service.expandEntity(QUINTET_QID, maxNewEdges);
  }

  private EdgeRecord replayed(java.util.function.Function<GraphStore, List<EdgeRecord>> read) {
    try (GraphStore rebuilt = new TinkerGraphStore()) {
      GraphProjector.project(log, rebuilt);
      List<EdgeRecord> found = read.apply(rebuilt);
      assertThat(found).hasSize(1);
      return found.get(0);
    }
  }

  private List<AssertionRecord> edgeAssertionsInTheLog() {
    return log.readAll().stream()
        .filter(AssertionRecord.class::isInstance)
        .map(AssertionRecord.class::cast)
        .toList();
  }

  private static SourceAdapter musicBrainz(String memberQid) {
    return new MusicBrainzSourceAdapter(
        MusicBrainzClient.readingFrom(fixture()),
        StubIdentity.of(Map.of(QUINTET_MBID, QUINTET_QID, FIRST_MEMBER_MBID, memberQid)),
        CLOCK);
  }

  private static SourceAdapter wikidata(
      StubWikidataServer actionApi, StubWikidataServer queryService) {
    WikidataClient client = new WikidataClient(actionApi.baseUri());
    return new WikidataSourceAdapter(
        new WikidataEntityResolver(client, CLOCK),
        new WikidataClient(queryService.baseUri()),
        CLOCK);
  }

  /** The seed, with no forward claims: everything Wikidata has to say here is stated about it. */
  private static String seedEntity() {
    return "{\"entities\":{\""
        + QUINTET_QID
        + "\":{\"id\":\""
        + QUINTET_QID
        + "\",\"labels\":{\"en\":{\"language\":\"en\",\"value\":\"An Ensemble\"}},"
        + "\"claims\":{}}}}";
  }

  /** One Query Service row: {@code other wdt:P463 seed}, with the identity riding along. */
  private static String memberOfBacklink(String otherQid) {
    return "{\"results\":{\"bindings\":[{"
        + "\"p\":{\"type\":\"uri\",\"value\":\"http://www.wikidata.org/prop/direct/P463\"},"
        + "\"other\":{\"type\":\"uri\",\"value\":\"http://www.wikidata.org/entity/"
        + otherQid
        + "\"},"
        + "\"otherLabel\":{\"type\":\"literal\",\"value\":\"A Player\"},"
        + "\"type\":{\"type\":\"uri\",\"value\":\"http://www.wikidata.org/entity/Q5\"},"
        + "\"sitelinks\":{\"type\":\"literal\",\"value\":\"12\"}}]}}";
  }

  /**
   * Neighbour identity, out of the way.
   *
   * <p>It is not out of the way by accident: {@code MusicBrainzSourceAdapter} returns no {@code
   * neighbors()}, so every newly discovered neighbour costs {@code SegueService} one {@code
   * EntityResolver.fetch} — a Wikidata Action API call in the shipped wiring. The first draft of
   * the reversed-order test below discovered that by failing with an empty log when the resolver
   * could not be reached.
   *
   * <p><b>That cost is avoidable, and an earlier version of this javadoc said it was not.</b> The
   * artist type is in the response — see {@code MusicBrainzSourceAdapter}'s own note, which
   * measures it on the committed fixture — so the adapter could supply the neighbours itself. It is
   * <a href="https://github.com/robsartin/segue/issues/143">issue #143</a>. It is not what these
   * three tests measure either way, so identity is stubbed rather than exercised.
   */
  private static final class AlwaysResolves implements EntityResolver {

    @Override
    public String id() {
      return "wikidata";
    }

    @Override
    public List<Candidate> search(String query, NodeKind kind, int limit) {
      throw new UnsupportedOperationException("expandEntity does not search");
    }

    @Override
    public Optional<NodeAssertion> fetch(String qid) {
      return Optional.of(
          new NodeAssertion(
              qid, NodeKind.PERSON, "A Player", new Provenance("wikidata", qid, NOW, 1.00)));
    }
  }

  private static Path fixture() {
    try {
      return Path.of(
          CorroborationAcrossSourcesTest.class
              .getResource("/musicbrainz/artist-with-relations.json")
              .toURI());
    } catch (URISyntaxException e) {
      throw new IllegalStateException(e);
    }
  }
}
