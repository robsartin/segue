package com.robsartin.segue.musicbrainz;

import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.segue.domain.NodeAssertion;
import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.domain.NodeRecord;
import com.robsartin.segue.domain.Provenance;
import com.robsartin.segue.ingest.GraphProjector;
import com.robsartin.segue.musicbrainz.MusicBrainzProbe.ProbeInputs;
import com.robsartin.segue.musicbrainz.MusicBrainzProbe.ProbeReport;
import com.robsartin.segue.port.AssertionLog;
import com.robsartin.segue.port.ExpandContext;
import com.robsartin.segue.port.ExpandResult;
import com.robsartin.segue.port.GraphStore;
import com.robsartin.segue.port.IdentityMerge;
import com.robsartin.segue.port.SourceAdapter;
import com.robsartin.segue.sqlite.SqliteAssertionLog;
import com.robsartin.segue.tinker.TinkerGraphStore;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The whole pipeline against a log small enough to know by hand, with no network reached.
 *
 * <p><b>Every part below the fixture is the real one.</b> The claims are written through {@code
 * SqliteAssertionLog} into a file under {@code @TempDir}, that file is handed to the probe the only
 * way a path may reach it — {@link ProbeDatabase#require} — reopened, and projected by {@code
 * GraphProjector} into a real {@code TinkerGraphStore}; the responses go through the real {@link
 * MusicBrainzClient} and its parser, off {@link StubMusicBrainzServer} by path. What is invented is
 * the sample itself: {@code /musicbrainz/probe-fixture.json} holds four seeds, three of them
 * bridged, and its {@code expected} block states the resulting table by hand rather than from a
 * run.
 *
 * <p><b>The fixture's {@code expected} block is the only place its totals are written.</b> This
 * test reads them; it restates none of them, so the fixture and its expectation cannot drift apart
 * in two files.
 *
 * <p><b>The table is asserted block by block rather than as one string</b>, and that is the point
 * of the four positive controls the report for this task quotes. A whole-text equality would red
 * identically whatever moved, and so could not say which column a break reached; each assertion
 * here names its own block, and each planted break was seen to red that block and no other.
 *
 * <p><b>Block 1's {@code artist relations returned} is deliberately not asserted here.</b> It is
 * the same number as block 2's {@code TOTAL} — invariant 1 says so and {@link
 * MusicBrainzProbe#assertInvariants} checks it on this very report — so it is asserted once, in the
 * block whose census produces it. Asserting it twice would mean the control for block 2 reddened
 * block 1 as well, and a control that reds two columns proves neither.
 *
 * <p><b>Block 3's shares are not asserted either</b>, for the same reason: they are the counts
 * above them divided out, and invariant 4 is what holds them to summing to a hundred.
 *
 * <p><b>Every identifier here is invented</b> and names nobody: the QIDs carry a leading zero and
 * are therefore unallocatable (ADR 58), and the MBIDs are this package's {@code
 * 00000000-0000-4000-8000-0000000000nn} stand-in family.
 */
class MusicBrainzProbeTest {

  private static final Instant NOW = Instant.parse("2026-09-02T12:00:00Z");

  @TempDir Path tempDir;

  @Test
  @DisplayName("should render the table the fixture declares when the probe runs against it")
  void shouldRenderTheTableTheFixtureDeclaresWhenTheProbeRunsAgainstIt() {
    JsonNode fixture = fixture();
    Path copy = writtenThroughTheRealLog(fixture);

    // The probe never resolves a database, so the copy reaches it through the one door that
    // refuses the owner's own log. The home and the default are this test's own invented ones.
    Path database =
        ProbeDatabase.require(
            copy.toString(), null, tempDir.resolve("segue.db"), tempDir.resolve("home"));

    try (AssertionLog log = new SqliteAssertionLog(database);
        GraphStore graph = new TinkerGraphStore();
        StubMusicBrainzServer stub = new StubMusicBrainzServer()) {
      GraphProjector.project(log, graph, IdentityMerge.NONE);
      serve(fixture, stub);

      ProbeReport report =
          MusicBrainzProbe.run(
              new ProbeInputs(
                  seeds(fixture, graph),
                  new MusicBrainzClient(stub.baseUri()),
                  StubIdentity.of(bridge(fixture)),
                  new FixtureWikidataSide(describedBySeed(fixture)),
                  graph));

      JsonNode expected = fixture.get("expected");
      assertBlockOne(report, expected.get("block1"));
      assertBlockTwo(report, expected.get("block2"));
      assertBlockThree(report, expected.get("block3"));
      assertBlockFour(report, expected.get("block4"));
      assertBlockFive(report, expected.get("block5"));

      MusicBrainzProbe.assertInvariants(report);

      assertThat(stub.requestCount())
          .as(
              "one request per bridged seed and not one more. The client was pointed at this stub"
                  + " and every request it made arrived here, so a request that escaped to the real"
                  + " endpoint would be missing from this count")
          .isEqualTo(expected.get("block1").get("bridged").asInt());
    }
  }

  private static void assertBlockOne(ProbeReport report, JsonNode expected) {
    assertThat(report.sample().seedsRequested())
        .as("block 1: seeds requested")
        .isEqualTo(expected.get("seedsRequested").asInt());
    assertThat(report.sample().seedsPerson())
        .as("block 1: seeds PERSON")
        .isEqualTo(expected.get("seedsPerson").asInt());
    assertThat(report.sample().seedsGroup())
        .as("block 1: seeds GROUP")
        .isEqualTo(expected.get("seedsGroup").asInt());
    assertThat(report.sample().bridged())
        .as("block 1: bridged via P434")
        .isEqualTo(expected.get("bridged").asInt());
    assertThat(report.sample().seedsWithAResolvedNeighbour())
        .as("block 1: seeds with a resolved neighbour")
        .isEqualTo(expected.get("seedsWithAResolvedNeighbour").asInt());
    assertThat(report.sample().resolvedNeighbours())
        .as("block 1: resolved neighbours")
        .isEqualTo(expected.get("resolvedNeighbours").asInt());
  }

  private static void assertBlockTwo(ProbeReport report, JsonNode expected) {
    assertThat(report.census().values().stream().mapToInt(Integer::intValue).sum())
        .as("block 2: TOTAL — every relation the fixture's responses state, censused")
        .isEqualTo(expected.get("total").asInt());
    assertThat(report.census())
        .as("block 2: one row per relation type, with the count the fixture states")
        .containsExactlyInAnyOrderEntriesOf(counts(expected.get("census")));
  }

  private static void assertBlockThree(ProbeReport report, JsonNode expected) {
    assertThat(report.buckets().alreadyInTheGraph())
        .as("block 3: already in the graph")
        .isEqualTo(expected.get("alreadyInTheGraph").asInt());
    assertThat(report.buckets().describedInTheSameCall())
        .as("block 3: new, but described in the same call")
        .isEqualTo(expected.get("describedInTheSameCall").asInt());
    assertThat(report.buckets().newAndUndescribed())
        .as("block 3: new and undescribed")
        .isEqualTo(expected.get("newAndUndescribed").asInt());
  }

  private static void assertBlockFour(ProbeReport report, JsonNode expected) {
    assertThat(report.saving().median())
        .as("block 4: median")
        .isEqualTo(expected.get("median").asInt());
    assertThat(List.of(report.saving().p90(), report.saving().max()))
        .as(
            "block 4: p90 and max. Nearest rank puts p90 on the largest per-seed count for any"
                + " sample of fewer than eleven seeds, so a fixture this size cannot move one"
                + " without the other, and pretending otherwise would mean asserting a cell no"
                + " break can reach on its own")
        .containsExactly(expected.get("p90").asInt(), expected.get("max").asInt());
  }

  private static void assertBlockFive(ProbeReport report, JsonNode expected) {
    assertThat(report.cost().classLessCreations())
        .as("block 5: new neighbours created class-less")
        .isEqualTo(expected.get("classLessCreations").asInt());
    assertThat(report.cost().erasureOccurrences())
        .as("block 5: erasure occurrences")
        .isEqualTo(expected.get("erasureOccurrences").asInt());
    assertThat(report.cost().distinctErased())
        .as("block 5: distinct nodes erased")
        .isEqualTo(expected.get("distinctErased").asInt());
    assertThat(report.cost().erasedCarryingInstanceOf())
        .as("block 5: of those carrying a non-empty instanceOf today")
        .isEqualTo(expected.get("erasedCarryingInstanceOf").asInt());
    assertThat(report.cost().seedsTheBoundCut())
        .as("block 5: seeds the shared bound cut")
        .isEqualTo(expected.get("seedsTheBoundCut").asInt());
  }

  /**
   * Each bridged seed's response, at the path the real client asks for it by. Registered by path
   * rather than queued: a queue would answer whichever request arrived first with whichever body
   * was added first, so a walk that fetched the wrong artist — or fetched one twice — would still
   * be served a well-formed response and would still pass.
   */
  private static void serve(JsonNode fixture, StubMusicBrainzServer stub) {
    for (JsonNode seed : fixture.get("seeds")) {
      if (seed.has("mbid") && seed.has("response")) {
        stub.enqueueBody("/artist/" + seed.get("mbid").asString(), seed.get("response").toString());
      }
    }
  }

  /**
   * The fixture's claims, appended through the real writer to a real file. The log is closed before
   * the probe opens it, so what the probe reads is a database on disk rather than a handle this
   * test kept warm.
   */
  private Path writtenThroughTheRealLog(JsonNode fixture) {
    Path copy = tempDir.resolve("probe-copy.db");
    try (AssertionLog log = new SqliteAssertionLog(copy)) {
      for (JsonNode seed : fixture.get("seeds")) {
        log.append(claim(seed));
      }
      for (JsonNode node : fixture.get("alreadyInTheGraph")) {
        log.append(claim(node));
      }
    }
    return copy;
  }

  private static NodeAssertion claim(JsonNode node) {
    String qid = node.get("qid").asString();
    return new NodeAssertion(
        qid,
        NodeKind.valueOf(node.get("kind").asString()),
        node.get("label").asString(),
        strings(node.get("instanceOf")),
        new Provenance("wikidata", qid, NOW, 1.00));
  }

  /**
   * The seeds, read back out of the projection rather than built beside it: the kinds the probe
   * counts are the ones {@code GraphProjector} re-derived from the classes the claims carry (ADR
   * 42), which is where the live run will read them from too.
   */
  private static List<NodeRecord> seeds(JsonNode fixture, GraphStore graph) {
    List<NodeRecord> seeds = new ArrayList<>();
    for (JsonNode seed : fixture.get("seeds")) {
      String qid = seed.get("qid").asString();
      seeds.add(
          graph
              .node(qid)
              .orElseThrow(
                  () -> new IllegalStateException("the projection does not hold the seed " + qid)));
    }
    return List.copyOf(seeds);
  }

  /** MBID to QID, for both ends: the seeds the bridge can place, and the relation targets. */
  private static Map<String, String> bridge(JsonNode fixture) {
    Map<String, String> mbidToQid = new LinkedHashMap<>();
    for (JsonNode seed : fixture.get("seeds")) {
      if (seed.has("mbid")) {
        mbidToQid.put(seed.get("mbid").asString(), seed.get("qid").asString());
      }
    }
    for (JsonNode neighbour : fixture.get("neighbours")) {
      mbidToQid.put(neighbour.get("mbid").asString(), neighbour.get("qid").asString());
    }
    return Map.copyOf(mbidToQid);
  }

  /** Which neighbours the Wikidata side describes in the same call, per seed. */
  private static Map<String, Set<String>> describedBySeed(JsonNode fixture) {
    Map<String, Set<String>> described = new LinkedHashMap<>();
    for (JsonNode seed : fixture.get("seeds")) {
      described.put(
          seed.get("qid").asString(), new LinkedHashSet<>(strings(seed.get("describes"))));
    }
    return Map.copyOf(described);
  }

  private static Map<String, Integer> counts(JsonNode object) {
    Map<String, Integer> counts = new LinkedHashMap<>();
    for (Map.Entry<String, JsonNode> entry : object.properties()) {
      counts.put(entry.getKey(), entry.getValue().asInt());
    }
    return Map.copyOf(counts);
  }

  private static List<String> strings(JsonNode array) {
    List<String> values = new ArrayList<>();
    for (JsonNode value : array) {
      values.add(value.asString());
    }
    return List.copyOf(values);
  }

  private static JsonNode fixture() {
    try {
      Path path =
          Path.of(
              MusicBrainzProbeTest.class.getResource("/musicbrainz/probe-fixture.json").toURI());
      return new ObjectMapper().readTree(Files.readString(path));
    } catch (URISyntaxException | IOException e) {
      throw new IllegalStateException("could not read the probe fixture", e);
    }
  }

  /**
   * The Wikidata side of an expansion, answering from the fixture: it describes the neighbours the
   * fixture says it describes, and states no assertions of its own, so block 5's last row measures
   * only what MusicBrainz would have contributed.
   */
  private static final class FixtureWikidataSide implements SourceAdapter {

    private final Map<String, Set<String>> describedBySeed;

    private FixtureWikidataSide(Map<String, Set<String>> describedBySeed) {
      this.describedBySeed = Map.copyOf(describedBySeed);
    }

    @Override
    public String id() {
      return "wikidata";
    }

    @Override
    public boolean supports(NodeKind kind) {
      return kind == NodeKind.PERSON || kind == NodeKind.GROUP;
    }

    @Override
    public ExpandResult expand(NodeRecord seed, ExpandContext ctx) {
      List<NodeAssertion> described =
          describedBySeed.getOrDefault(seed.qid(), Set.of()).stream()
              .map(
                  qid ->
                      new NodeAssertion(
                          qid,
                          NodeKind.PERSON,
                          "described in the same call",
                          new Provenance("wikidata", seed.qid(), NOW, 0.80)))
              .toList();
      return new ExpandResult(List.of(), described, false, false);
    }
  }
}
