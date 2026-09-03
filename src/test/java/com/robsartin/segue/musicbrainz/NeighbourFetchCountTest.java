package com.robsartin.segue.musicbrainz;

import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.segue.domain.Candidate;
import com.robsartin.segue.domain.NodeAssertion;
import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.domain.Provenance;
import com.robsartin.segue.ingest.IngestService;
import com.robsartin.segue.mcp.SegueService;
import com.robsartin.segue.port.AffinityStore;
import com.robsartin.segue.port.AssertionLog;
import com.robsartin.segue.port.EntityResolver;
import com.robsartin.segue.port.GraphStore;
import com.robsartin.segue.port.IdentityMerge;
import com.robsartin.segue.port.SourceAdapter;
import com.robsartin.segue.port.SourceAdapters;
import com.robsartin.segue.sqlite.SqliteAffinityStore;
import com.robsartin.segue.sqlite.SqliteAssertionLog;
import com.robsartin.segue.tinker.TinkerGraphStore;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>What a MusicBrainz expansion costs in {@link EntityResolver#fetch} calls, and what varying the
 * bridge's answer does to that number</b> — measured over the committed fixture, offline (issue
 * #163; ADR 61).
 *
 * <p>The fixture's denominator is exact and was derived rather than assumed: of its 24 relations,
 * <b>22</b> are mappable ({@code "member of band"}, a stated direction, an MBID target), over 22
 * distinct target MBIDs, every one typed {@code Person}.
 *
 * <p><b>The baseline, measured on 2026-09-02 against the code before the widening.</b> With all 22
 * bridged to distinct QIDs and none of them described, expanding the seed cost <b>22</b> {@code
 * fetch} calls — one per newly discovered neighbour — against <b>1</b> bridge round trip, batched
 * once for the whole neighbourhood. ADR 55 records the same shape measured on the live graph, and
 * this fixture is the reproducible stand-in that number cannot be re-run against; its own table is
 * not restated here.
 *
 * <p><b>The instrument was proved able to fail before it was believed</b> (2026-09-02). The fetch
 * assertion was changed to expect 23 and run again: {@code [EntityResolver.fetch calls over the
 * committed fixture's 22 mappable neighbours] expected: 23 but was: 22}. A counter that has not
 * been seen to disagree has not been shown to be counting anything. The two shape controls below
 * were run wrong first for the same reason — the edge assertion against 21 said {@code Expected
 * size: 21 but was: 22}, and the class assertion against {@code "Q999"} said {@code Expecting
 * actual: ["Q5"] to contain exactly (and in same order): ["Q999"]}.
 *
 * <p><b>What the three tests measure now, all at the same one round trip.</b> The bridge describes
 * every neighbour: <b>0</b> fetches — watched flip from the baseline with {@code expected: 22 but
 * was: 0}. It describes <b>none</b>: <b>22</b> again, which is the whole of the guard working, and
 * was watched red as {@code expected: 0 but was: 22}. It describes exactly <b>11</b>: <b>11</b>,
 * watched red as {@code expected: 0 but was: 11}.
 *
 * <p><b>That last pair is the definition of done, not decoration.</b> A saving that did not vary
 * with what the bridge described would not be measuring the bridge — it would be measuring an
 * expansion that had quietly stopped producing neighbours at all, which reports 0 and looks
 * identical. So every test also asserts that the 22 edges landed and that every neighbour node
 * carries {@code Q5}; and the labels separate the two populations, because a neighbour the bridge
 * described arrives under the bridge's label and one the fetch described under the resolver's. In
 * the 11 case, 11 of each.
 */
class NeighbourFetchCountTest {

  private static final String SEED_MBID = "ee55e4e8-807d-49b1-8470-d1c0898ed7cb";
  private static final String SEED_QID = "Q0900001";

  /** {@code Q5}, the class the counting resolver hands back for every neighbour it fetches. */
  private static final String HUMAN = "Q5";

  private static final String FETCHED_LABEL = "A Player, As The Counting Resolver Names Them";

  /** What a bridge that could describe a neighbour hands back — never what a fetch hands back. */
  private static final String BRIDGE_LABEL = "A Player, As The Bridge Names Them";

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
    ingest = new IngestService(log, graph, IdentityMerge.NONE);
    affinity = SqliteAffinityStore.inMemory();
    ingest.record(
        new NodeAssertion(
            SEED_QID,
            NodeKind.GROUP,
            "An Ensemble",
            new Provenance("wikidata", SEED_QID, NOW, 1.00)));
  }

  @AfterEach
  void tearDown() {
    affinity.close();
    graph.close();
    log.close();
  }

  @Test
  @DisplayName("should spend no fetch at all when the bridge describes every neighbour")
  void shouldSpendNoFetchAtAllWhenTheBridgeDescribesEveryNeighbour() {
    Expansion expansion = expand(StubIdentity.describing(mbidToIdentity(22)));

    assertThat(expansion.fetches())
        .as("EntityResolver.fetch calls over the committed fixture's 22 mappable neighbours")
        .isEqualTo(0);
    assertThat(expansion.roundTrips())
        .as("MusicBrainzIdentity batch round trips for the whole neighbourhood")
        .isEqualTo(1);

    assertTheTwentyTwoEdgesLanded();
    assertEveryNeighbourCarries(HUMAN, BRIDGE_LABEL, 22);
  }

  @Test
  @DisplayName("should spend one fetch per neighbour when the bridge describes none of them")
  void shouldSpendOneFetchPerNeighbourWhenTheBridgeDescribesNoneOfThem() {
    Expansion expansion = expand(StubIdentity.describing(mbidToIdentity(0)));

    assertThat(expansion.fetches())
        .as("EntityResolver.fetch calls when every neighbour resolves but none is described")
        .isEqualTo(22);
    assertThat(expansion.roundTrips())
        .as("MusicBrainzIdentity batch round trips for the whole neighbourhood")
        .isEqualTo(1);

    assertTheTwentyTwoEdgesLanded();
    assertEveryNeighbourCarries(HUMAN, FETCHED_LABEL, 22);
  }

  @Test
  @DisplayName("should spend one fetch per neighbour when the bridge describes only some of them")
  void shouldSpendOneFetchPerNeighbourWhenTheBridgeDescribesOnlySomeOfThem() {
    Expansion expansion = expand(StubIdentity.describing(mbidToIdentity(11)));

    assertThat(expansion.fetches())
        .as("EntityResolver.fetch calls when the bridge describes 11 of the 22")
        .isEqualTo(11);
    assertThat(expansion.roundTrips())
        .as("MusicBrainzIdentity batch round trips for the whole neighbourhood")
        .isEqualTo(1);

    assertTheTwentyTwoEdgesLanded();
    assertEveryNeighbourCarries(HUMAN, BRIDGE_LABEL, 11);
    assertEveryNeighbourCarries(HUMAN, FETCHED_LABEL, 11);
  }

  /**
   * Every distinct target MBID among the fixture's 22 mappable relations, bridged to its own QID.
   *
   * <p>Returned in the fixture's own order rather than as a {@code Map.copyOf}, so that "the first
   * n of them" below names the same n on every run. Which n is arbitrary; that it is reproducible
   * is not.
   */
  private static Map<String, String> mbidToQid() {
    Map<String, String> mapping = new LinkedHashMap<>();
    mapping.put(SEED_MBID, SEED_QID);
    int next = 900101;
    for (ArtistRelation relation :
        MusicBrainzClient.readingFrom(fixture()).artistRelations(SEED_MBID)) {
      if ("member of band".equals(relation.type())) {
        mapping.putIfAbsent(relation.targetMbid(), "Q" + next++);
      }
    }
    return mapping;
  }

  /**
   * The same 22 neighbours, of which the first {@code described} carry a label and a class and the
   * rest carry a QID and nothing else — which is what a bridge that resolved an entity it could not
   * describe answers, and what {@code MusicBrainzSourceAdapter}'s guard omits.
   *
   * <p>The seed is always left undescribed: {@code expandEntity} reads it out of the graph, so what
   * the bridge says about it is only the MBID it resolves back to.
   */
  private static Map<String, BridgedIdentity> mbidToIdentity(int described) {
    Map<String, BridgedIdentity> bridged = new LinkedHashMap<>();
    int remaining = described;
    for (Map.Entry<String, String> entry : mbidToQid().entrySet()) {
      String qid = entry.getValue();
      boolean describeThisOne = !entry.getKey().equals(SEED_MBID) && remaining-- > 0;
      bridged.put(
          entry.getKey(),
          describeThisOne
              ? new BridgedIdentity(qid, NodeKind.PERSON, BRIDGE_LABEL, List.of(HUMAN))
              : BridgedIdentity.undescribed(qid));
    }
    return bridged;
  }

  /** What one expansion cost: the fetches it spent and the bridge round trips it made. */
  private record Expansion(int fetches, int roundTrips) {}

  private Expansion expand(MusicBrainzIdentity bridge) {
    CountingResolver resolver = new CountingResolver();
    CountingIdentity identity = new CountingIdentity(bridge);
    SourceAdapter musicBrainz =
        new MusicBrainzSourceAdapter(MusicBrainzClient.readingFrom(fixture()), identity, CLOCK);
    SegueService service =
        new SegueService(
            resolver, graph, ingest, new SourceAdapters(List.of(musicBrainz)), affinity, CLOCK);

    service.expandEntity(SEED_QID, 200);

    return new Expansion(resolver.fetchCount.get(), identity.roundTrips.get());
  }

  /**
   * Control (i): the count did not fall because the expansion stopped producing edges. A fetch
   * count can drop for reasons that are breakage rather than saving — the fixture, the whitelist or
   * the bridge quietly ceasing to produce neighbours — and an emptied expansion would report 0 and
   * look like the answer this class is looking for.
   */
  private void assertTheTwentyTwoEdgesLanded() {
    assertThat(graph.edges(SEED_QID))
        .as("every mappable relation recorded as an edge onto the seed")
        .hasSize(22)
        .allSatisfy(
            edge -> {
              assertThat(edge.typeCode()).isEqualTo("MEMBER_OF");
              assertThat(edge.toQid()).isEqualTo(SEED_QID);
            });
  }

  /**
   * Control (ii): the count did not fall because the identity never landed. Every neighbour carries
   * the classes, and {@code expected} of them carry the named label — which is what separates a
   * neighbour the bridge described from one the fetch described, and so what makes a partial answer
   * measurable rather than merely plausible.
   */
  private void assertEveryNeighbourCarries(String classQid, String label, int expected) {
    assertThat(graph.edges(SEED_QID))
        .extracting(edge -> graph.node(edge.fromQid()).orElseThrow())
        .allSatisfy(neighbour -> assertThat(neighbour.instanceOf()).containsExactly(classQid))
        .filteredOn(neighbour -> neighbour.label().equals(label))
        .as("neighbours carrying the label " + label)
        .hasSize(expected);
  }

  /**
   * The resolver as the shipped one behaves — {@code WikidataEntityResolver.fetch} — with a count
   * this test can observe. Counting was itself proved able to disagree (Loop B): asserted against
   * 23 before it was asserted against 22, and it went red with the real number rather than passing
   * by construction.
   */
  private static final class CountingResolver implements EntityResolver {

    private final AtomicInteger fetchCount = new AtomicInteger();

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
      fetchCount.incrementAndGet();
      return Optional.of(
          new NodeAssertion(
              qid,
              NodeKind.PERSON,
              FETCHED_LABEL,
              List.of(HUMAN),
              new Provenance("wikidata", qid, NOW, 1.00)));
    }
  }

  /**
   * Counts {@link MusicBrainzIdentity#identitiesFor} calls — the batched bridge round trip {@code
   * MusicBrainzSourceAdapter.expand} spends once per call, over however many neighbours it is asked
   * about. Wrapping {@link StubIdentity} rather than replacing it keeps resolution behaviour in one
   * place, matching {@code MusicBrainzSourceAdapterTest.RecordingIdentity}'s shape.
   */
  private static final class CountingIdentity implements MusicBrainzIdentity {

    private final MusicBrainzIdentity delegate;
    private final AtomicInteger roundTrips = new AtomicInteger();

    private CountingIdentity(MusicBrainzIdentity delegate) {
      this.delegate = delegate;
    }

    @Override
    public Optional<String> mbidFor(String qid) {
      return delegate.mbidFor(qid);
    }

    @Override
    public Map<String, String> qidsFor(Collection<String> mbids) {
      return delegate.qidsFor(mbids);
    }

    @Override
    public Map<String, BridgedIdentity> identitiesFor(Collection<String> mbids) {
      roundTrips.incrementAndGet();
      return delegate.identitiesFor(mbids);
    }
  }

  private static Path fixture() {
    try {
      return Path.of(
          NeighbourFetchCountTest.class
              .getResource("/musicbrainz/artist-with-relations.json")
              .toURI());
    } catch (URISyntaxException e) {
      throw new IllegalStateException(e);
    }
  }
}
