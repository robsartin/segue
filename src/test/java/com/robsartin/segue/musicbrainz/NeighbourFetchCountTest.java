package com.robsartin.segue.musicbrainz;

import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.segue.domain.Candidate;
import com.robsartin.segue.domain.NodeAssertion;
import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.domain.NodeRecord;
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
 * Issue #163, Task 1: how many {@link EntityResolver#fetch} calls a MusicBrainz expansion spends
 * today over the committed fixture — <b>observed</b>, before {@code
 * docs/superpowers/specs/2026-09-02-mb-identity-classes-design.md} widens anything. The number this
 * class asserts is not a prediction copied from that spec; it is what the counting harness below
 * saw, the first time it was run against today's code.
 *
 * <p><b>The baseline.</b> Of the fixture's 24 relations, 22 are mappable — {@code "member of
 * band"}, a stated direction, an MBID target — and every one of those 22 names a distinct target
 * MBID, and every target MusicBrainz types {@code Person}. With all 22 bridged to distinct QIDs,
 * expanding the seed costs the counting {@link EntityResolver} <b>22</b> {@code fetch} calls — one
 * per newly discovered neighbour, because none of them already carries an identity {@code
 * MusicBrainzSourceAdapter} can hand {@code SegueService} in place of a fetch — against <b>1</b>
 * bridge round trip ({@link MusicBrainzIdentity#qidsFor}, batched once for the whole
 * neighbourhood). ADR 55 records the same shape measured on the live graph — 214 of 461 resolved
 * neighbours were the entire round-trip saving on offer — and this fixture is the reproducible
 * stand-in that number cannot be re-run against; its own table is not restated here.
 *
 * <p><b>Loop B, the instrument proved able to fail.</b> Before this count was trusted, the fetch
 * assertion was changed to expect 23 and run again. It went red with: {@code [EntityResolver.fetch
 * calls over the committed fixture's 22 mappable neighbours] expected: 23 but was: 22}. Restored to
 * 22 and green again — a counter that had not yet been seen to disagree had not been shown to be
 * counting anything.
 *
 * <p><b>Loop C, the shape controls.</b> A fetch count can fall for the wrong reason — the fixture,
 * the whitelist or the stub mapping could quietly stop producing neighbours, and an emptied
 * expansion would report 0 fetches and look like a saving instead of a break. Two assertions below
 * exist only to rule that out: the 22 edges are actually recorded (each neighbour {@code MEMBER_OF}
 * the seed), and each neighbour node the fetch resolver described actually landed in the graph
 * carrying {@code Q5}. Both were run wrong first and both went red quoting the real value: the edge
 * assertion against 21 said {@code Expected size: 21 but was: 22}, and the class assertion against
 * {@code "Q999"} said {@code Expecting actual: ["Q5"] to contain exactly (and in same order):
 * ["Q999"]} — then both were restored to what the expansion actually produced.
 */
class NeighbourFetchCountTest {

  private static final String SEED_MBID = "ee55e4e8-807d-49b1-8470-d1c0898ed7cb";
  private static final String SEED_QID = "Q0900001";

  /** {@code Q5}, the class the counting resolver hands back for every neighbour it fetches. */
  private static final String HUMAN = "Q5";

  private static final String FETCHED_LABEL = "A Player, As The Counting Resolver Names Them";

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
  @DisplayName("should spend one fetch per mappable neighbour and one bridge round trip today")
  void shouldSpendOneFetchPerMappableNeighbourAndOneBridgeRoundTripToday() {
    CountingResolver resolver = new CountingResolver();
    CountingIdentity identity = new CountingIdentity(StubIdentity.of(mbidToQid()));
    SourceAdapter musicBrainz =
        new MusicBrainzSourceAdapter(MusicBrainzClient.readingFrom(fixture()), identity, CLOCK);
    SegueService service =
        new SegueService(
            resolver, graph, ingest, new SourceAdapters(List.of(musicBrainz)), affinity, CLOCK);

    service.expandEntity(SEED_QID, 200);

    assertThat(resolver.fetchCount.get())
        .as("EntityResolver.fetch calls over the committed fixture's 22 mappable neighbours")
        .isEqualTo(22);
    assertThat(identity.qidsForCalls.get())
        .as("MusicBrainzIdentity.qidsFor batch round trips for the whole neighbourhood")
        .isEqualTo(1);

    // Loop C, control (i): the count did not fall because the expansion stopped producing edges.
    assertThat(graph.edges(SEED_QID))
        .as("every mappable relation recorded as an edge onto the seed")
        .hasSize(22)
        .allSatisfy(
            edge -> {
              assertThat(edge.typeCode()).isEqualTo("MEMBER_OF");
              assertThat(edge.toQid()).isEqualTo(SEED_QID);
            });

    // Loop C, control (ii): the count did not fall because the fetched identity never landed.
    assertThat(graph.edges(SEED_QID))
        .extracting(edge -> edge.fromQid())
        .allSatisfy(
            neighbourQid -> {
              NodeRecord neighbour = graph.node(neighbourQid).orElseThrow();
              assertThat(neighbour.instanceOf()).containsExactly(HUMAN);
              assertThat(neighbour.label()).isEqualTo(FETCHED_LABEL);
            });
  }

  /**
   * Every distinct target MBID among the fixture's 22 mappable relations, bridged to its own QID.
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
    return Map.copyOf(mapping);
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
   * Counts {@link MusicBrainzIdentity#qidsFor} calls — the batched bridge round trip {@code
   * MusicBrainzSourceAdapter.expand} spends once per call, over however many neighbours it is asked
   * about. Wrapping {@link StubIdentity} rather than replacing it keeps resolution behaviour in one
   * place, matching {@code MusicBrainzSourceAdapterTest.RecordingIdentity}'s shape.
   */
  private static final class CountingIdentity implements MusicBrainzIdentity {

    private final MusicBrainzIdentity delegate;
    private final AtomicInteger qidsForCalls = new AtomicInteger();

    private CountingIdentity(MusicBrainzIdentity delegate) {
      this.delegate = delegate;
    }

    @Override
    public Optional<String> mbidFor(String qid) {
      return delegate.mbidFor(qid);
    }

    @Override
    public Map<String, String> qidsFor(Collection<String> mbids) {
      qidsForCalls.incrementAndGet();
      return delegate.qidsFor(mbids);
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
