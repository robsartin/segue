package com.robsartin.segue.musicbrainz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.robsartin.segue.domain.AssertionRecord;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>Which neighbours {@code MusicBrainzSourceAdapter} may emit, and which it must not</b> — held
 * by tests rather than by a paragraph (<a
 * href="https://github.com/robsartin/segue/issues/163">issue #163</a>; ADR 61, which reverses half
 * of ADR 55).
 *
 * <p><b>The guard, which is the whole of the decision.</b> A neighbour is emitted only when the
 * bridge behind {@link MusicBrainzIdentity} answered with <i>both</i> a label {@link
 * BridgedIdentity} did not normalise to null <i>and</i> at least one class. An identity that clears
 * neither is omitted, and {@code SegueService} falls back to {@code EntityResolver.fetch} exactly
 * as it did before this change — so the round trip is skipped only where it had nothing left to
 * buy.
 *
 * <p><b>What this class used to say, because it is still true and is still asserted.</b> #143's
 * premise was that a MusicBrainz response has already paid for a neighbour's identity. It has paid
 * for two thirds of it: a relation carries the neighbour's MBID, name and artist type, and what it
 * does not carry — and cannot, because MusicBrainz states no Wikidata classes — is {@code
 * instanceOf}, the raw {@code P31} that {@link NodeAssertion}'s javadoc says the log keeps so a
 * derivation can be revisited (ADR 42), and that {@code PathRanking.isHub}, {@code CandidateSweep},
 * {@code rate/Card}, {@code DotWriter} and {@code GraphMlWriter} all read. {@code SegueService}
 * prefers an adapter's neighbour to a fetch and records it whether or not the node already exists
 * (issue #55), and {@code TinkerGraphStore.upsertNode} writes {@code instanceOf} on every upsert,
 * <b>empty included and deliberately so</b> — its own comment says a later claim stating no classes
 * must not leave an earlier claim's behind. Put those three together and a class-less {@code
 * neighbors()} does not merely decline to add classes: it removes the ones already there. <b>That
 * is unchanged, and the first two tests below are exactly it</b>, watched red on 2026-09-02 against
 * an adapter planted with an unguarded emission — {@code Expecting actual: [] to contain exactly
 * (and in same order): ["Q5"]}, the identical message ADR 55's own tests were watched red with on
 * 2026-08-30. What this class no longer says is that <i>therefore no neighbour may be emitted</i>.
 *
 * <p><b>What changed is where the classes come from, not whether they are required.</b> ADR 55's
 * own closing sentence named the route that collects the saving without the cost: a bridge that
 * returns classes alongside QIDs, one batched Query Service round trip per 100 neighbours, the
 * shape {@code ReverseClaims} already uses for Wikidata's own neighbours. That is what {@link
 * MusicBrainzIdentity#identitiesFor} now is. So the described neighbour arrives with the {@code
 * instanceOf} #143 could not supply, and the two tests that hold it are green <i>for a new
 * reason</i> — the classes come from the bridge, not from a fetch that no longer happens. {@link
 * RefusesToFetch} is what says so.
 *
 * <p><b>The claim is Wikidata's, and is stamped so.</b> Kind, label and classes are read from
 * Wikidata on the bridge's round trip, so the neighbour claim carries {@code Provenance("wikidata",
 * qid, assertedAt, 1.00)} while the edge keeps {@code "musicbrainz"} at 0.80. {@code
 * SourceAdapter.id()}'s javadoc is amended to govern {@code assertions()} for that reason, and the
 * third test below is the assertion.
 *
 * <p><b>GAP 7 is untouched.</b> The last test still holds the half of #143 that was always safe: an
 * empty {@code instanceOf} does not throw, it erases. Nothing here makes an empty list illegal — it
 * makes it a reason not to emit.
 */
class MusicBrainzNeighbourIdentityTest {

  /**
   * The committed fixture's own MBIDs — a reproducible API probe, never a statement about taste.
   */
  private static final String SEED_MBID = "ee55e4e8-807d-49b1-8470-d1c0898ed7cb";

  private static final String NEIGHBOUR_MBID = "9afdcb99-d4ba-41b4-b4b2-467a712bd9fa";

  private static final String SEED_QID = "Q0900001";
  private static final String NEIGHBOUR_QID = "Q0900002";

  /** {@code Q5}, the class every one of the fixture's whitelisted neighbours would carry. */
  private static final String HUMAN = "Q5";

  private static final String WIKIDATA_LABEL = "A Player, As Wikidata Names Them";

  /**
   * What a bridge that could describe this neighbour hands back — never what a fetch hands back.
   */
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

  /**
   * The guard, first half: a bridge that resolved the QID but stated no classes describes nothing
   * this adapter may emit, because emitting it is #143's erasure (ADR 55, and ADR 61 reversing half
   * of it — issue #163).
   *
   * <p><b>Watched red, and here is what it said.</b> An adapter that emits nothing passes this
   * vacuously, so before it was trusted the adapter was planted with an <i>unguarded</i> emission —
   * a {@link NodeAssertion} per resolved neighbour, straight from the {@link BridgedIdentity},
   * guard omitted. It failed with {@code Expecting actual: [] to contain exactly (and in same
   * order): ["Q5"]}, the identical message ADR 55's own tests were watched red with in 2026-08-30:
   * the classes this node already had, gone. The plant was removed and the guarded emission arrived
   * in its place.
   */
  @Test
  @DisplayName("should leave an existing neighbour's classes alone when the bridge states none")
  void shouldLeaveAnExistingNeighboursClassesAloneWhenTheBridgeStatesNone() {
    seedTheNeighbourWithItsClasses();

    expand(
        bridgeAnswering(
            new BridgedIdentity(NEIGHBOUR_QID, NodeKind.PERSON, BRIDGE_LABEL, List.of())));

    assertTheEdgeWasStillRecorded();
    NodeRecord neighbour = graph.node(NEIGHBOUR_QID).orElseThrow();
    assertThat(neighbour.instanceOf()).containsExactly(HUMAN);
    assertThat(neighbour.label()).isEqualTo(WIKIDATA_LABEL);
  }

  /**
   * The guard, second half: classes without a label worth believing. {@code wikibase:label} hands
   * back the bare QID where no English label exists, and {@link BridgedIdentity} normalises every
   * such answer to null — so a neighbour named {@code Q0900002} is one the fetch must still be
   * allowed to name properly. {@link NodeAssertion} requires a non-null label, so an adapter that
   * emitted this one would not merely misname the node; it would throw out of {@code expand} and
   * take the whole expansion with it.
   *
   * <p><b>Watched red against the same plant</b> (see the test above), and it did exactly that:
   * {@code java.lang.NullPointerException: label} out of {@code NodeAssertion.<init>}, thrown
   * through {@code MusicBrainzSourceAdapter.expand} and up through {@code
   * SegueService.expandEntity}, which wraps nothing.
   */
  @Test
  @DisplayName("should leave an existing neighbour alone when the bridge has no label to believe")
  void shouldLeaveAnExistingNeighbourAloneWhenTheBridgeHasNoLabelToBelieve() {
    seedTheNeighbourWithItsClasses();

    expand(
        bridgeAnswering(new BridgedIdentity(NEIGHBOUR_QID, NodeKind.PERSON, null, List.of(HUMAN))));

    assertTheEdgeWasStillRecorded();
    NodeRecord neighbour = graph.node(NEIGHBOUR_QID).orElseThrow();
    assertThat(neighbour.instanceOf()).containsExactly(HUMAN);
    assertThat(neighbour.label()).isEqualTo(WIKIDATA_LABEL);
  }

  /**
   * The other side of the guard, and the change ADR 61 makes: an existing neighbour the bridge
   * <i>could</i> describe keeps its classes because the bridge restated them, not because nothing
   * was emitted.
   *
   * <p>The label is the tell. {@code SegueService} records volunteered identity whether or not the
   * node already exists (issue #55), so a neighbour this adapter emitted arrives under the bridge's
   * label; one it declined to emit keeps the label the graph already had. Asserting {@link
   * #BRIDGE_LABEL} rather than {@link #WIKIDATA_LABEL} is what makes this test fail if the emission
   * stops, instead of passing on the old adapter's silence.
   */
  @Test
  @DisplayName("should keep a neighbour's stated classes when musicbrainz expands over it")
  void shouldKeepANeighboursStatedClassesWhenMusicbrainzExpandsOverIt() {
    seedTheNeighbourWithItsClasses();

    expandWithoutAFetch(bridgeAnswering(described()));

    assertTheEdgeWasStillRecorded();
    NodeRecord neighbour = graph.node(NEIGHBOUR_QID).orElseThrow();
    assertThat(neighbour.instanceOf()).containsExactly(HUMAN);
    assertThat(neighbour.label()).isEqualTo(BRIDGE_LABEL);
  }

  /**
   * The saving itself: the neighbour is absent, and the fetch #143 proposed to skip is skipped —
   * because the bridge answered the question that fetch existed to answer, on the round trip it was
   * already making.
   *
   * <p>{@link RefusesToFetch} is the assertion, not the setup. A count would say the fetch did not
   * happen; a resolver that cannot be called says the classes below could only have come from the
   * bridge.
   */
  @Test
  @DisplayName("should give a newly discovered neighbour the classes the bridge states")
  void shouldGiveANewlyDiscoveredNeighbourTheClassesTheBridgeStates() {
    assertThat(graph.node(NEIGHBOUR_QID)).isEmpty();

    expandWithoutAFetch(bridgeAnswering(described()));

    assertTheEdgeWasStillRecorded();
    NodeRecord neighbour = graph.node(NEIGHBOUR_QID).orElseThrow();
    assertThat(neighbour.instanceOf()).containsExactly(HUMAN);
    assertThat(neighbour.label()).isEqualTo(BRIDGE_LABEL);
  }

  /**
   * Controller ruling 1 of the spec, held by a test: the neighbour claim is stamped {@code
   * "wikidata"}, not this adapter's own id.
   *
   * <p>The kind, label and classes are Wikidata's facts, read on the bridge's round trip, and this
   * claim is what {@code WikidataEntityResolver.fetch} would have produced for the same entity —
   * same source id, same 1.00. The edge keeps {@code "musicbrainz"} at 0.80, which is the half of
   * the expansion MusicBrainz actually stated. {@code SourceAdapter.id()}'s javadoc is amended to
   * say it governs {@code assertions()} for exactly this reason.
   */
  @Test
  @DisplayName("should attribute the neighbour's identity to wikidata and the edge to musicbrainz")
  void shouldAttributeTheNeighboursIdentityToWikidataAndTheEdgeToMusicbrainz() {
    expandWithoutAFetch(bridgeAnswering(described()));

    assertThat(log.readAll())
        .filteredOn(NodeAssertion.class::isInstance)
        .map(NodeAssertion.class::cast)
        .filteredOn(claim -> claim.qid().equals(NEIGHBOUR_QID))
        .singleElement()
        .extracting(
            claim -> claim.provenance().sourceId(), claim -> claim.provenance().confidence())
        .containsExactly("wikidata", 1.00);

    assertThat(log.readAll())
        .filteredOn(AssertionRecord.class::isInstance)
        .map(AssertionRecord.class::cast)
        .filteredOn(edge -> edge.fromQid().equals(NEIGHBOUR_QID))
        .singleElement()
        .extracting(edge -> edge.provenance().sourceId(), edge -> edge.provenance().confidence())
        .containsExactly("musicbrainz", 0.80);
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

  private void expand(MusicBrainzIdentity bridge) {
    expand(bridge, new ResolvesWithClasses());
  }

  /** As {@link #expand}, with a resolver that turns a fetch into a failure rather than a number. */
  private void expandWithoutAFetch(MusicBrainzIdentity bridge) {
    expand(bridge, new RefusesToFetch());
  }

  private void expand(MusicBrainzIdentity bridge, EntityResolver resolver) {
    SegueService service =
        new SegueService(
            resolver,
            graph,
            ingest,
            new SourceAdapters(List.of(musicBrainz(bridge))),
            affinity,
            CLOCK);
    service.expandEntity(SEED_QID, 200);
  }

  /** The neighbour as a bridge that could see it answers: a real label and the class it implies. */
  private static BridgedIdentity described() {
    return new BridgedIdentity(NEIGHBOUR_QID, NodeKind.PERSON, BRIDGE_LABEL, List.of(HUMAN));
  }

  private static SourceAdapter musicBrainz(MusicBrainzIdentity bridge) {
    return new MusicBrainzSourceAdapter(MusicBrainzClient.readingFrom(fixture()), bridge, CLOCK);
  }

  /**
   * A describing bridge over the fixture's seed and the one neighbour these tests follow. The seed
   * itself is left undescribed: {@code expandEntity} reads it out of the graph, so what the bridge
   * says about it is only ever the MBID it resolves back to.
   */
  private static MusicBrainzIdentity bridgeAnswering(BridgedIdentity neighbour) {
    return StubIdentity.describing(
        Map.of(SEED_MBID, StubIdentity.undescribed(SEED_QID), NEIGHBOUR_MBID, neighbour));
  }

  /** The neighbour as Wikidata already described it, in the graph before the expansion runs. */
  private void seedTheNeighbourWithItsClasses() {
    ingest.record(
        new NodeAssertion(
            NEIGHBOUR_QID,
            NodeKind.PERSON,
            WIKIDATA_LABEL,
            List.of(HUMAN),
            new Provenance("wikidata", NEIGHBOUR_QID, NOW, 1.00)));
  }

  /**
   * The control every guard test needs: "the classes survived" is equally true of an expansion that
   * asserted nothing at all, so without this a test would stay green if the fixture, the bridge or
   * the whitelist quietly stopped producing an edge over this neighbour.
   */
  private void assertTheEdgeWasStillRecorded() {
    assertThat(graph.edges(SEED_QID))
        .extracting(edge -> edge.fromQid() + " " + edge.typeCode() + " " + edge.toQid())
        .contains(NEIGHBOUR_QID + " MEMBER_OF " + SEED_QID);
  }

  /**
   * A resolver that cannot be fetched from. Where the bridge described the neighbour, {@code
   * SegueService} must never reach for one — so the honest instrument is not a counter that would
   * report zero whether or not the code path exists, but a resolver whose being called is itself
   * the failure.
   */
  private static final class RefusesToFetch implements EntityResolver {

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
      throw new AssertionError(
          "EntityResolver.fetch reached for " + qid + ", which the bridge had already described");
    }
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
