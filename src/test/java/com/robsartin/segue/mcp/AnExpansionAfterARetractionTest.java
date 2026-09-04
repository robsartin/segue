package com.robsartin.segue.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.robsartin.segue.domain.AssertionRecord;
import com.robsartin.segue.domain.Candidate;
import com.robsartin.segue.domain.NodeAssertion;
import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.domain.NodeRecord;
import com.robsartin.segue.domain.Provenance;
import com.robsartin.segue.domain.Retraction;
import com.robsartin.segue.ingest.GraphProjector;
import com.robsartin.segue.ingest.IngestService;
import com.robsartin.segue.port.AffinityStore;
import com.robsartin.segue.port.AssertionLog;
import com.robsartin.segue.port.EntityResolver;
import com.robsartin.segue.port.ExpandContext;
import com.robsartin.segue.port.ExpandResult;
import com.robsartin.segue.port.GraphStore;
import com.robsartin.segue.port.IdentityMerge;
import com.robsartin.segue.port.SourceAdapter;
import com.robsartin.segue.port.SourceAdapters;
import com.robsartin.segue.sqlite.SqliteAffinityStore;
import com.robsartin.segue.sqlite.SqliteAssertionLog;
import com.robsartin.segue.tinker.TinkerGraphStore;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Issue #234, reachability: the supported flow really does produce the log that cannot boot, and
 * which half of the Wikidata adapter produced the edge decides whether it does.
 *
 * <p>{@code WikidataSourceAdapter.expand} fills {@link ExpandResult#neighbors()} from the REVERSE
 * pass alone (ADR 36); the forward pass, {@code ClaimMapper.map}, carries no identity. When a
 * neighbour's identity rides along, {@code SegueService.expandEntity} re-records it whether or not
 * the graph holds the node (issue #55) — that claim lands after the retraction, survives it, and
 * the boot is fine. When it does not, the stale graph makes {@code isNew} false so nothing is
 * fetched either, and the edge is appended alone.
 *
 * <p><b>Reaching either needs two writers on one database</b>, which is not the single writer ADR
 * 24 assumes: no retraction can be appended from inside the server ({@code
 * ToolSurfaceTest.retractIsNotATool}), so {@code IngestService.retract} below stands in for {@code
 * ./gradlew retractEntity} running in its own process (ADR 60) against a database a server is still
 * holding open. Restart in between and the rebuilt graph refuses the edge correctly (#233).
 */
class AnExpansionAfterARetractionTest {

  /** Invented, ADR 58's leading zero. The seed being expanded. */
  private static final String WREN = "Q0900101";

  /** The neighbour that is retracted while the running graph goes on holding its node. */
  private static final String KETTLES = "Q0900102";

  /** A third, invented far-end id — only reachable through {@link #SEED_EDGE}, never a seed. */
  private static final String SPARROW = "Q0900103";

  private static final Instant NOW = Instant.parse("2026-09-04T09:00:00Z");
  private static final Provenance WIKIDATA = new Provenance("wikidata", "S-1", NOW, 0.80);
  private static final AssertionRecord EDGE =
      new AssertionRecord(WREN, KETTLES, "INFLUENCED_BY", null, null, WIKIDATA);
  private static final NodeAssertion KETTLES_CLAIM =
      new NodeAssertion(KETTLES, NodeKind.PERSON, "Kettles Nye", WIKIDATA);

  /**
   * An edge naming the retracted {@link #KETTLES} at its own end, {@link #SPARROW} at the other.
   */
  private static final AssertionRecord SEED_EDGE =
      new AssertionRecord(KETTLES, SPARROW, "INFLUENCED_BY", null, null, WIKIDATA);

  private static final NodeAssertion SPARROW_CLAIM =
      new NodeAssertion(SPARROW, NodeKind.PERSON, "Sparrow Vane", WIKIDATA);

  @Test
  @DisplayName(
      "an expansion after a retraction leaves a log that cannot boot when the source names no neighbour")
  void shouldLeaveALogThatCannotBootWhenTheSourceNamesNoNeighbour(@TempDir Path dir) {
    Path db = dir.resolve("segue.db");
    expandAfterRetracting(db, ExpandResult.of(List.of(EDGE)));

    assertThatThrownBy(() -> boot(db))
        .as("the edge names an endpoint the fold holds no node for")
        .hasMessageContaining("sequence 4")
        .hasMessageContaining(KETTLES)
        .hasMessageContaining("retract the endpoint");
  }

  @Test
  @DisplayName(
      "the same expansion leaves a bootable log when the source volunteers the neighbour's identity")
  void shouldLeaveABootableLogWhenTheSourceVolunteersTheNeighboursIdentity(@TempDir Path dir) {
    Path db = dir.resolve("segue.db");
    expandAfterRetracting(
        db, new ExpandResult(List.of(EDGE), List.of(KETTLES_CLAIM), false, false));

    try (AssertionLog reopened = new SqliteAssertionLog(db);
        GraphStore rebuilt = new TinkerGraphStore()) {
      assertThatCode(() -> GraphProjector.project(reopened, rebuilt, IdentityMerge.NONE))
          .as("issue #55's unconditional identity refresh lands after the retraction")
          .doesNotThrowAnyException();
      assertThat(rebuilt.node(KETTLES)).isPresent();
      assertThat(rebuilt.edgeCount()).isOne();
    }
  }

  /**
   * The third reachability shape, and a DISTINCT path from the two above — those both expand {@link
   * #WREN}, a seed that was never retracted, and vary only what the source says about {@link
   * #KETTLES} as its <em>neighbour</em>. Here the call is {@code service.expandEntity(KETTLES,
   * 10)}: the seed itself is the retracted entity.
   *
   * <p>{@code SegueService.expandEntity}'s seed check is {@code graph.node(qid)}, the same stale,
   * unretracted graph the two tests above already rely on being stale — so it still finds a node
   * for {@code KETTLES} and the call proceeds. {@link #SEED_EDGE} then names {@code KETTLES} at one
   * end and the invented {@link #SPARROW} at the other, and {@code
   * SegueService.neighborOf(assertion, KETTLES)} resolves the FAR end — {@code SPARROW} — for every
   * edge this adapter can return, by construction: it returns {@code null} only when BOTH ends
   * equal the seed, and otherwise always the end that is not the seed. {@code KETTLES} can
   * therefore never be the {@code neighbor} variable {@code expandEntity}'s loop re-records
   * identity for.
   *
   * <p><b>No {@link ExpandResult} shape repairs this, unlike the neighbour-expansion shape
   * above.</b> There, volunteering the retracted id's own identity through {@code neighbors()} lets
   * issue #55's unconditional re-record fire for it, because that id WAS the resolved {@code
   * neighbor}. Here it cannot: whether or not {@code neighbors()} also carries a {@link
   * NodeAssertion} for {@code KETTLES} itself, {@code described.get(neighbor)} looks it up keyed on
   * {@code SPARROW}, never on {@code KETTLES} — the entry for {@code KETTLES} sits in the map
   * unread. Both variants below are asserted to poison identically, which is the finding: the
   * repair the neighbour case has is structurally unavailable to the seed case, not merely unused
   * by this particular adapter.
   *
   * <p><b>Positive control (removed after use):</b> with the {@code IngestService.retract} call in
   * {@link #expandTheRetractedSeedItself} skipped, {@code boot} succeeded for both variants —
   * {@code assertThatThrownBy(() -> boot(db))} failed with {@code java.lang.AssertionError:
   * Expecting code to raise a throwable.} — so the assertion below is not vacuous.
   */
  @Test
  @DisplayName(
      "an expansion of the retracted seed itself poisons the log whether or not the source"
          + " volunteers the seed's own identity")
  void shouldPoisonTheLogWhenTheExpandedSeedItselfWasRetracted(
      @TempDir Path noSeedIdentityDir, @TempDir Path withSeedIdentityDir) {
    Path noSeedIdentity = noSeedIdentityDir.resolve("segue.db");
    expandTheRetractedSeedItself(
        noSeedIdentity, new ExpandResult(List.of(SEED_EDGE), List.of(SPARROW_CLAIM), false, false));
    assertThatThrownBy(() -> boot(noSeedIdentity))
        .as(
            "KETTLES is the seed, never the far end neighborOf resolves for this edge, so its"
                + " identity is never re-recorded")
        .hasMessageContaining("sequence 4")
        .hasMessageContaining(KETTLES)
        .hasMessageContaining("retract the endpoint");

    Path withSeedIdentity = withSeedIdentityDir.resolve("segue.db");
    expandTheRetractedSeedItself(
        withSeedIdentity,
        new ExpandResult(List.of(SEED_EDGE), List.of(SPARROW_CLAIM, KETTLES_CLAIM), false, false));
    assertThatThrownBy(() -> boot(withSeedIdentity))
        .as(
            "volunteering the seed's own identity changes nothing: neighborOf(assertion, KETTLES)"
                + " never returns KETTLES for this edge, so the described map's entry for KETTLES is"
                + " never looked up")
        .hasMessageContaining("sequence 4")
        .hasMessageContaining(KETTLES)
        .hasMessageContaining("retract the endpoint");
  }

  /**
   * Retract {@link #KETTLES} exactly as {@link #expandAfterRetracting} does, then expand {@code
   * KETTLES} ITSELF — the seed under expansion is the retracted entity, not a neighbour of it.
   */
  private static void expandTheRetractedSeedItself(Path db, ExpandResult result) {
    try (AssertionLog log = new SqliteAssertionLog(db);
        GraphStore graph = new TinkerGraphStore();
        AffinityStore affinity = SqliteAffinityStore.inMemory()) {
      IngestService ingest = new IngestService(log, graph, IdentityMerge.NONE);
      ingest.record(KETTLES_CLAIM);
      IngestService.retract(
          log, new Retraction(KETTLES, "resolved to the wrong entity", NOW.plusSeconds(60)));

      SegueService service =
          new SegueService(
              new NothingResolver(),
              graph,
              ingest,
              new SourceAdapters(List.of(new FixedAdapter(result))),
              affinity,
              Clock.fixed(NOW, ZoneOffset.UTC));

      ToolResult<SegueService.ExpansionSummary> expansion = service.expandEntity(KETTLES, 10);

      assertThat(expansion.outcome()).isEqualTo(ToolResult.Outcome.OK);
      assertThat(expansion.payload().edgesAdded())
          .as("the edge was accepted, so the expansion reports nothing unusual")
          .isOne();
    }
  }

  /**
   * Seed, retract the neighbour behind the server's back, then expand through the facade. The
   * expansion is asserted to have succeeded: nothing refused the edge, so nothing tells the caller
   * anything is wrong, which is why the boot diagnosis is the thing that has to be good.
   */
  private static void expandAfterRetracting(Path db, ExpandResult result) {
    try (AssertionLog log = new SqliteAssertionLog(db);
        GraphStore graph = new TinkerGraphStore();
        AffinityStore affinity = SqliteAffinityStore.inMemory()) {
      IngestService ingest = new IngestService(log, graph, IdentityMerge.NONE);
      ingest.record(new NodeAssertion(WREN, NodeKind.PERSON, "Wren Alderman", WIKIDATA));
      ingest.record(KETTLES_CLAIM);
      IngestService.retract(
          log, new Retraction(KETTLES, "resolved to the wrong entity", NOW.plusSeconds(60)));

      SegueService service =
          new SegueService(
              new NothingResolver(),
              graph,
              ingest,
              new SourceAdapters(List.of(new FixedAdapter(result))),
              affinity,
              Clock.fixed(NOW, ZoneOffset.UTC));

      ToolResult<SegueService.ExpansionSummary> expansion = service.expandEntity(WREN, 10);

      assertThat(expansion.outcome()).isEqualTo(ToolResult.Outcome.OK);
      assertThat(expansion.payload().edgesAdded())
          .as("the edge was accepted, so the expansion reports nothing unusual")
          .isOne();
    }
  }

  private static long boot(Path db) {
    try (AssertionLog reopened = new SqliteAssertionLog(db);
        GraphStore rebuilt = new TinkerGraphStore()) {
      return GraphProjector.project(reopened, rebuilt, IdentityMerge.NONE);
    }
  }

  /** One source, one canned answer — the shape of the two halves of the Wikidata adapter. */
  private record FixedAdapter(ExpandResult result) implements SourceAdapter {

    @Override
    public String id() {
      return "wikidata";
    }

    @Override
    public boolean supports(NodeKind kind) {
      return true;
    }

    @Override
    public ExpandResult expand(NodeRecord seed, ExpandContext ctx) {
      return result;
    }
  }

  /**
   * Identifies nothing. Deliberate: the point is that a stale node stops the fetch being reached at
   * all, so a resolver that could rescue the neighbour would hide the defect.
   */
  private static final class NothingResolver implements EntityResolver {

    @Override
    public String id() {
      return "wikidata";
    }

    @Override
    public List<Candidate> search(String query, NodeKind kind, int limit) {
      return List.of();
    }

    @Override
    public Optional<NodeAssertion> fetch(String qid) {
      return Optional.empty();
    }
  }
}
