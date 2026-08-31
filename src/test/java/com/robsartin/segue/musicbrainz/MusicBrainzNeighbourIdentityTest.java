package com.robsartin.segue.musicbrainz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Why {@code MusicBrainzSourceAdapter} returns no {@link
 * com.robsartin.segue.port.ExpandResult#neighbors()}, held by a test rather than by a paragraph (<a
 * href="https://github.com/robsartin/segue/issues/143">issue #143</a>).
 *
 * <p>#143's premise is that the MusicBrainz response has already paid for the neighbour's identity,
 * so the {@code EntityResolver.fetch} {@code SegueService} spends per newly discovered neighbour is
 * avoidable. <b>It has paid for two thirds of it.</b> A MusicBrainz relation carries the
 * neighbour's MBID, name and artist type; what it does not carry — and cannot, because MusicBrainz
 * states no Wikidata classes — is {@code instanceOf}, the raw {@code P31} that {@link
 * NodeAssertion}'s javadoc says the log keeps so a derivation can be revisited (ADR 42), and that
 * {@code PathRanking.isHub}, {@code CandidateSweep}, {@code rate/Card}, {@code DotWriter} and
 * {@code GraphMlWriter} all read.
 *
 * <p>{@code SegueService} prefers an adapter's neighbour to a fetch, and records it whether or not
 * the node already exists (issue #55). {@code TinkerGraphStore.upsertNode} writes {@code
 * instanceOf} on every upsert, <b>empty included and deliberately so</b> — its own comment says a
 * later claim stating no classes must not leave an earlier claim's behind. Put those three together
 * and a MusicBrainz {@code neighbors()} does not merely decline to add classes: it removes the ones
 * already there.
 *
 * <p><b>Both tests below were watched red.</b> The adapter was changed to emit a {@code
 * NodeAssertion} per resolved neighbour — {@code artist.type} read into {@link ArtistRelation},
 * {@code Person}/{@code Group} mapped onto {@link NodeKind} — and the failures are recorded in
 * issue #143. The change was then reverted, and these tests are what stops it coming back by
 * accident.
 *
 * <p><b>What this is not.</b> It is not an argument that the saving is imaginary. It is measured
 * and real, and the route that collects it without this cost is a bridge that returns classes
 * alongside QIDs — one batched Query Service round trip per 100 neighbours, the shape {@code
 * ReverseClaims} already uses for Wikidata's own neighbours. That crosses into {@code app}, so it
 * is a separate change rather than this one.
 */
class MusicBrainzNeighbourIdentityTest {

  /**
   * The committed fixture's own MBIDs — a reproducible API probe, never a statement about taste.
   */
  private static final String SEED_MBID = "ee55e4e8-807d-49b1-8470-d1c0898ed7cb";

  private static final String NEIGHBOUR_MBID = "9afdcb99-d4ba-41b4-b4b2-467a712bd9fa";

  private static final String SEED_QID = "Q900001";
  private static final String NEIGHBOUR_QID = "Q900002";

  /** {@code Q5}, the class every one of the fixture's whitelisted neighbours would carry. */
  private static final String HUMAN = "Q5";

  private static final String WIKIDATA_LABEL = "A Player, As Wikidata Names Them";

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
  @DisplayName("should keep a neighbour's stated classes when musicbrainz expands over it")
  void shouldKeepANeighboursStatedClassesWhenMusicbrainzExpandsOverIt() {
    ingest.record(
        new NodeAssertion(
            NEIGHBOUR_QID,
            NodeKind.PERSON,
            WIKIDATA_LABEL,
            List.of(HUMAN),
            new Provenance("wikidata", NEIGHBOUR_QID, NOW, 1.00)));

    expand();

    // The positive control. "The classes survived" is equally true of an expansion that asserted
    // nothing at all, so without this the test would stay green if the fixture, the stub mapping
    // or the whitelist quietly stopped producing an edge over this neighbour.
    assertThat(graph.edges(SEED_QID))
        .extracting(edge -> edge.fromQid() + " " + edge.typeCode() + " " + edge.toQid())
        .contains(NEIGHBOUR_QID + " MEMBER_OF " + SEED_QID);

    NodeRecord neighbour = graph.node(NEIGHBOUR_QID).orElseThrow();
    assertThat(neighbour.instanceOf()).containsExactly(HUMAN);
    assertThat(neighbour.label()).isEqualTo(WIKIDATA_LABEL);
  }

  @Test
  @DisplayName("should give a newly discovered neighbour the classes the resolver states")
  void shouldGiveANewlyDiscoveredNeighbourTheClassesTheResolverStates() {
    // The neighbour is absent, so SegueService reaches for EntityResolver.fetch — the round trip
    // #143 proposes to skip. What it buys is this: the classes MusicBrainz has none of.
    assertThat(graph.node(NEIGHBOUR_QID)).isEmpty();

    expand();

    NodeRecord neighbour = graph.node(NEIGHBOUR_QID).orElseThrow();
    assertThat(neighbour.instanceOf()).containsExactly(HUMAN);
    assertThat(neighbour.label()).isEqualTo(WIKIDATA_LABEL);
  }

  /**
   * GAP 7 of {@code docs/design/2026-08-30-three-source-adapters.md}, on its safe side.
   *
   * <p>{@link NodeAssertion} validates nothing, and {@link NodeAssertion#toNode()} delegates to
   * {@link NodeRecord}, which runs {@code Qid.looksLikeAQid} over every {@code instanceOf} element
   * — inside {@code IngestService.apply}, <b>after</b> the log entry is written. A source tempted
   * to put its own class vocabulary there (a MusicBrainz artist type id) would blow up half
   * committed. MusicBrainz states no Wikidata classes, so the list is empty, and this is the case
   * that has to be right: an empty list has no element to fail on, and the claim goes through.
   *
   * <p>It is asserted here rather than assumed because it is the half of #143 that <em>is</em>
   * safe, and saying so is what keeps the two tests above from being read as "an empty {@code
   * instanceOf} throws". It does not. It erases.
   */
  @Test
  @DisplayName("should accept a class-less node claim, which is the only kind musicbrainz can make")
  void shouldAcceptAClasslessNodeClaimWhichIsTheOnlyKindMusicbrainzCanMake() {
    NodeAssertion classless =
        new NodeAssertion(
            NEIGHBOUR_QID,
            NodeKind.PERSON,
            "A Player, As MusicBrainz Bills Them",
            new Provenance("musicbrainz", "artist/" + NEIGHBOUR_MBID, NOW, 0.80));

    assertThat(classless.instanceOf()).isEmpty();
    assertThatCode(() -> ingest.record(classless)).doesNotThrowAnyException();
    assertThat(graph.node(NEIGHBOUR_QID).orElseThrow().instanceOf()).isEmpty();
  }

  private void expand() {
    SegueService service =
        new SegueService(
            new ResolvesWithClasses(),
            graph,
            ingest,
            new SourceAdapters(List.of(musicBrainz())),
            affinity,
            CLOCK);
    service.expandEntity(SEED_QID, 200);
  }

  private static SourceAdapter musicBrainz() {
    return new MusicBrainzSourceAdapter(
        MusicBrainzClient.readingFrom(fixture()),
        StubIdentity.of(Map.of(SEED_MBID, SEED_QID, NEIGHBOUR_MBID, NEIGHBOUR_QID)),
        CLOCK);
  }

  /**
   * The resolver as the shipped one behaves: {@code WikidataEntityResolver.fetch} reads {@code
   * ClaimMapper.instanceOf} and puts it on the claim beside the kind it implies. Stubbing it
   * without classes would make both tests above pass for the wrong reason.
   */
  private static final class ResolvesWithClasses implements EntityResolver {

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
              qid,
              NodeKind.PERSON,
              WIKIDATA_LABEL,
              List.of(HUMAN),
              new Provenance("wikidata", qid, NOW, 1.00)));
    }
  }

  private static Path fixture() {
    try {
      return Path.of(
          MusicBrainzNeighbourIdentityTest.class
              .getResource("/musicbrainz/artist-with-relations.json")
              .toURI());
    } catch (URISyntaxException e) {
      throw new IllegalStateException(e);
    }
  }
}
