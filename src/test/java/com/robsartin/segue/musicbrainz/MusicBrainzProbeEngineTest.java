package com.robsartin.segue.musicbrainz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.robsartin.segue.domain.NodeAssertion;
import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.domain.NodeRecord;
import com.robsartin.segue.domain.Provenance;
import com.robsartin.segue.musicbrainz.MusicBrainzProbe.Buckets;
import com.robsartin.segue.musicbrainz.MusicBrainzProbe.Cost;
import com.robsartin.segue.musicbrainz.MusicBrainzProbe.Percentiles;
import com.robsartin.segue.musicbrainz.MusicBrainzProbe.ProbeInputs;
import com.robsartin.segue.musicbrainz.MusicBrainzProbe.ProbeReport;
import com.robsartin.segue.musicbrainz.MusicBrainzProbe.Sample;
import com.robsartin.segue.musicbrainz.MusicBrainzProbe.SeedObservation;
import com.robsartin.segue.port.ExpandContext;
import com.robsartin.segue.port.ExpandResult;
import com.robsartin.segue.port.GraphStore;
import com.robsartin.segue.port.SourceAdapter;
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
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The engine's own loops: hand-built inputs, no network, no database and no server. The pipeline
 * that fills these inputs from a real log and a real endpoint is covered by the fixture run and by
 * the live run, not here.
 */
class MusicBrainzProbeEngineTest {

  private static final String IN_THE_GRAPH = "Q0900101";
  private static final String DESCRIBED_HERE = "Q0900102";
  private static final String UNDESCRIBED = "Q0900103";
  private static final String A_LABEL = "An Invented Ensemble";
  private static final String BRIDGED_SEED = "Q0900301";
  private static final String UNBRIDGED_SEED = "Q0900302";
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-09-02T00:00:00Z"), ZoneOffset.UTC);

  private static ArtistRelation relation(String type) {
    return new ArtistRelation(
        "00000000-0000-4000-8000-000000000001", type, "forward", "a name", null, null, null);
  }

  @Test
  @DisplayName("the census counts each relation type once when several seeds share a type")
  void shouldCountEachRelationTypeOnceWhenSeveralSeedsShareAType() {
    List<List<ArtistRelation>> bySeed =
        List.of(
            List.of(relation("member of band"), relation("member of band"), relation("tribute")),
            List.of(relation("member of band"), relation("sibling"), relation("tribute")));

    Map<String, Integer> census = MusicBrainzProbe.census(bySeed);

    assertThat(census)
        .as("one row per distinct relation type, descending by count then by type")
        .containsExactly(
            Map.entry("member of band", 3), Map.entry("tribute", 2), Map.entry("sibling", 1));
    assertThat(census.values().stream().mapToInt(Integer::intValue).sum())
        .as("the census total is the relation count")
        .isEqualTo(6);
  }

  @Test
  @DisplayName("the three buckets partition the resolved neighbours when each branch is taken once")
  void shouldPartitionTheResolvedNeighboursWhenEachBranchIsTakenOnce() {
    try (GraphStore graph = new TinkerGraphStore()) {
      graph.upsertNode(new NodeRecord(IN_THE_GRAPH, NodeKind.PERSON, "already here", List.of()));
      SeedObservation seed =
          new SeedObservation(
              NodeKind.GROUP,
              true,
              List.of(relation("member of band")),
              List.of(IN_THE_GRAPH, DESCRIBED_HERE, UNDESCRIBED),
              Set.of(DESCRIBED_HERE),
              1);

      ProbeReport report = MusicBrainzProbe.report(List.of(seed), graph);

      assertThat(report.buckets().total())
          .as("the three buckets partition block 1's resolved-neighbour total")
          .isEqualTo(report.sample().resolvedNeighbours());
      assertThat(report.buckets().alreadyInTheGraph()).as("isNew is false").isEqualTo(1);
      assertThat(report.buckets().describedInTheSameCall()).as("described wins").isEqualTo(1);
      assertThat(report.buckets().newAndUndescribed()).as("the fetch fires").isEqualTo(1);
    }
  }

  @Test
  @DisplayName("the sample counts seeds by kind and counts only the bridged ones")
  void shouldCountSeedsByKindWhenOnlySomeAreBridged() {
    try (GraphStore graph = new TinkerGraphStore()) {
      SeedObservation bridgedGroup =
          new SeedObservation(
              NodeKind.GROUP,
              true,
              List.of(relation("member of band"), relation("tribute")),
              List.of(UNDESCRIBED),
              Set.of(),
              2);
      SeedObservation bridgedPerson =
          new SeedObservation(
              NodeKind.PERSON, true, List.of(relation("sibling")), List.of(), Set.of(), 1);
      SeedObservation unbridgedPerson =
          new SeedObservation(NodeKind.PERSON, false, List.of(), List.of(), Set.of(), 0);

      ProbeReport report =
          MusicBrainzProbe.report(List.of(bridgedGroup, bridgedPerson, unbridgedPerson), graph);

      assertThat(report.sample())
          .as("block 1, in the spec's row order")
          .isEqualTo(new Sample(3, 2, 1, 2, 3, 1, 1));
    }
  }

  @Test
  @DisplayName("the saving per expansion is ordered, and its max is the largest per-seed count")
  void shouldOrderTheSavingWhenPerSeedCountsDiffer() {
    try (GraphStore graph = new TinkerGraphStore()) {
      ProbeReport report =
          MusicBrainzProbe.report(
              List.of(
                  seedReaching("Q0900201"),
                  seedReaching("Q0900202", "Q0900203"),
                  seedReaching("Q0900204", "Q0900205", "Q0900206")),
              graph);

      assertThat(report.saving().max())
          .as("block 4's max is the largest per-seed count of block 3's third bucket")
          .isEqualTo(3);
      assertThat(report.saving().median()).as("median").isEqualTo(2);
      assertThat(report.saving().p90()).as("p90").isEqualTo(3);
      assertThat(report.saving().perSeed())
          .as("one entry per seed with a resolved neighbour")
          .containsExactly(1, 2, 3);
    }
  }

  @Test
  @DisplayName("a node reached twice is one distinct erasure over two occurrences")
  void shouldCountDistinctErasuresOnceWhenANodeIsReachedTwice() {
    try (GraphStore graph = new TinkerGraphStore()) {
      graph.upsertNode(
          new NodeRecord(IN_THE_GRAPH, NodeKind.PERSON, "already here", List.of("Q0900005")));
      SeedObservation first = seedErasing(IN_THE_GRAPH);
      SeedObservation second = seedErasing(IN_THE_GRAPH);

      ProbeReport report = MusicBrainzProbe.report(List.of(first, second), graph);

      assertThat(report.cost().distinctErased())
          .as("distinct nodes erased is de-duplicated across the sample")
          .isEqualTo(1);
      assertThat(report.cost().erasureOccurrences())
          .as("erasure occurrences are counted once per expansion that reaches the node")
          .isEqualTo(2);
      assertThat(report.cost().distinctErased())
          .as("distinct erased never exceeds the occurrences that produced them")
          .isLessThanOrEqualTo(report.cost().erasureOccurrences());
      assertThat(report.cost().erasedCarryingInstanceOf())
          .as("of the distinct erased, those carrying a non-empty instanceOf today")
          .isEqualTo(1);
    }
  }

  @Test
  @DisplayName("block 5 counts the class-less creations and the seeds the shared bound cut")
  void shouldCountTheBoundCutWhenASeedExceedsTheSharedBound() {
    try (GraphStore graph = new TinkerGraphStore()) {
      SeedObservation cut =
          new SeedObservation(
              NodeKind.GROUP,
              true,
              List.of(relation("member of band")),
              List.of(UNDESCRIBED),
              Set.of(),
              MusicBrainzProbe.SHARED_BOUND + 1);
      SeedObservation notCut =
          new SeedObservation(
              NodeKind.PERSON,
              true,
              List.of(relation("sibling")),
              List.of(DESCRIBED_HERE),
              Set.of(DESCRIBED_HERE),
              MusicBrainzProbe.SHARED_BOUND);

      ProbeReport report = MusicBrainzProbe.report(List.of(cut, notCut), graph);

      assertThat(report.cost().seedsTheBoundCut())
          .as("collected.size() > ExpansionBounds.effective(kind, the shared bound)")
          .isEqualTo(1);
      assertThat(report.cost().classLessCreations())
          .as("class-less creations are block 3's third bucket, read again")
          .isEqualTo(report.buckets().newAndUndescribed());
    }
  }

  @Test
  @DisplayName("the renderer emits the five blocks in order with the spec's column headings")
  void shouldEmitFiveBlocksWhenTheReportIsRendered() {
    String rendered = renderedSample();

    assertThat(rendered)
        .as("the five block headings, in the spec's order")
        .containsSubsequence(
            "### 1. Sample",
            "### 2. Census",
            "### 3. Neighbours",
            "### 4. The saving per expansion",
            "### 5. What filling `neighbors()` would cost");
    assertThat(rendered)
        .as("the spec's column headings, verbatim")
        .containsSubsequence(
            "| what | count |",
            "| relation type | count |",
            "| what the neighbour was | count | share | fetch spent today? |",
            "| median | p90 | max |",
            "| what | count |");
    assertThat(rendered)
        .as("block 3's fourth column, kept verbatim from ADR 55")
        .contains("| no — `isNew` is false |")
        .contains("| no — `described` wins |")
        .contains("| yes — this is the whole saving |");
    assertThat(rendered).as("every block that has one carries a TOTAL row").contains("| TOTAL |");
  }

  @Test
  @DisplayName("neither a seed label nor a QID reaches the rendered table")
  void shouldNameNoEntityWhenTheInputsCarryLabelsAndQids() {
    String rendered = renderedSample();

    try (GraphStore graph = graphWith()) {
      MusicBrainzProbe.assertInvariants(MusicBrainzProbe.report(labelledObservations(), graph));
    }
    assertThat(rendered)
        .as("a label handed to the engine on an ArtistRelation")
        .doesNotContain(A_LABEL);
    assertThat(rendered)
        .as("a QID handed to the engine as a neighbour")
        .doesNotContain(UNDESCRIBED);
  }

  private static String renderedSample() {
    try (GraphStore graph = graphWith()) {
      return MusicBrainzProbe.report(labelledObservations(), graph).render();
    }
  }

  private static GraphStore graphWith() {
    GraphStore graph = new TinkerGraphStore();
    graph.upsertNode(
        new NodeRecord(IN_THE_GRAPH, NodeKind.PERSON, "already here", List.of("Q0900005")));
    return graph;
  }

  private static List<SeedObservation> labelledObservations() {
    ArtistRelation labelled =
        new ArtistRelation(
            "00000000-0000-4000-8000-000000000009",
            "member of band",
            "forward",
            A_LABEL,
            null,
            null,
            null);
    return List.of(
        new SeedObservation(
            NodeKind.GROUP,
            true,
            List.of(labelled, relation("tribute")),
            List.of(IN_THE_GRAPH, DESCRIBED_HERE, UNDESCRIBED),
            Set.of(DESCRIBED_HERE),
            1),
        new SeedObservation(
            NodeKind.PERSON, true, List.of(relation("sibling")), List.of(), Set.of(), 1),
        new SeedObservation(NodeKind.PERSON, false, List.of(), List.of(), Set.of(), 0));
  }

  private static SeedObservation seedReaching(String... undescribedNeighbours) {
    return new SeedObservation(
        NodeKind.GROUP,
        true,
        List.of(relation("member of band")),
        List.of(undescribedNeighbours),
        Set.of(),
        1);
  }

  private static SeedObservation seedErasing(String neighbourAlreadyInTheGraph) {
    return new SeedObservation(
        NodeKind.PERSON,
        true,
        List.of(relation("sibling")),
        List.of(neighbourAlreadyInTheGraph),
        Set.of(),
        1);
  }

  // --- Loop E: one planted violation per invariant, because an invariant with no red is not one.

  @Test
  @DisplayName("a report that violates nothing passes the shape checker")
  void shouldPassWhenNothingIsViolated() {
    MusicBrainzProbe.assertInvariants(valid());
  }

  @Test
  @DisplayName("invariant 1 reds when the census does not sum to the relation total")
  void shouldRedWhenTheCensusDoesNotSumToTheRelationTotal() {
    Map<String, Integer> short1 = new LinkedHashMap<>();
    short1.put("member of band", 4);

    assertThatThrownBy(() -> MusicBrainzProbe.assertInvariants(violating(valid(), short1)))
        .hasMessageContaining("invariant 1");
  }

  @Test
  @DisplayName("invariant 2 reds when a census row counts nothing")
  void shouldRedWhenACensusRowCountsNothing() {
    Map<String, Integer> withAZero = new LinkedHashMap<>();
    withAZero.put("member of band", 4);
    withAZero.put("tribute", 2);
    withAZero.put("sibling", 0);

    assertThatThrownBy(() -> MusicBrainzProbe.assertInvariants(violating(valid(), withAZero)))
        .hasMessageContaining("invariant 2");
  }

  @Test
  @DisplayName("invariant 3 reds when the three buckets do not partition the resolved neighbours")
  void shouldRedWhenTheBucketsDoNotPartitionTheResolvedNeighbours() {
    ProbeReport report = valid();
    ProbeReport broken =
        new ProbeReport(
            report.sample(),
            report.census(),
            new Buckets(1, 1, 1, 33, 33, 33),
            report.saving(),
            new Cost(1, 1, 1, 1, 1));

    assertThatThrownBy(() -> MusicBrainzProbe.assertInvariants(broken))
        .hasMessageContaining("invariant 3");
  }

  @Test
  @DisplayName("invariant 4 reds when the three shares do not sum to a hundred percent")
  void shouldRedWhenTheSharesDoNotSumToAHundred() {
    ProbeReport report = valid();
    ProbeReport broken =
        new ProbeReport(
            report.sample(),
            report.census(),
            new Buckets(1, 1, 2, 25, 25, 10),
            report.saving(),
            report.cost());

    assertThatThrownBy(() -> MusicBrainzProbe.assertInvariants(broken))
        .hasMessageContaining("invariant 4");
  }

  @Test
  @DisplayName("invariant 5 reds when the seed kinds do not sum to the seeds requested")
  void shouldRedWhenTheSeedKindsDoNotSumToTheSeedsRequested() {
    ProbeReport report = valid();
    ProbeReport broken =
        new ProbeReport(
            new Sample(3, 2, 2, 2, 6, 2, 4),
            report.census(),
            report.buckets(),
            report.saving(),
            report.cost());

    assertThatThrownBy(() -> MusicBrainzProbe.assertInvariants(broken))
        .hasMessageContaining("invariant 5");
  }

  @Test
  @DisplayName("invariant 6 reds when the median exceeds the p90")
  void shouldRedWhenTheMedianExceedsTheP90() {
    ProbeReport report = valid();
    ProbeReport broken =
        new ProbeReport(
            report.sample(),
            report.census(),
            report.buckets(),
            new Percentiles(3, 2, 2, List.of(1, 2)),
            report.cost());

    assertThatThrownBy(() -> MusicBrainzProbe.assertInvariants(broken))
        .hasMessageContaining("invariant 6");
  }

  @Test
  @DisplayName("invariant 7 reds when more nodes are erased than there were occasions to erase")
  void shouldRedWhenTheDistinctErasedExceedTheOccurrences() {
    ProbeReport report = valid();
    ProbeReport broken =
        new ProbeReport(
            report.sample(),
            report.census(),
            report.buckets(),
            report.saving(),
            new Cost(2, 1, 2, 1, 1));

    assertThatThrownBy(() -> MusicBrainzProbe.assertInvariants(broken))
        .hasMessageContaining("invariant 7");
  }

  private static ProbeReport valid() {
    Map<String, Integer> census = new LinkedHashMap<>();
    census.put("member of band", 4);
    census.put("tribute", 2);
    return new ProbeReport(
        new Sample(3, 2, 1, 2, 6, 2, 4),
        census,
        new Buckets(1, 1, 2, 25, 25, 50),
        new Percentiles(1, 2, 2, List.of(1, 2)),
        new Cost(2, 1, 1, 1, 1));
  }

  private static ProbeReport violating(ProbeReport report, Map<String, Integer> census) {
    return new ProbeReport(
        report.sample(), census, report.buckets(), report.saving(), report.cost());
  }

  @Test
  @DisplayName("invariant 5 reds when more seeds bridged than were requested")
  void shouldRedWhenMoreSeedsBridgedThanWereRequested() {
    assertThatThrownBy(
            () -> MusicBrainzProbe.assertInvariants(withSample(new Sample(3, 2, 1, 4, 6, 2, 4))))
        .hasMessageContaining("invariant 5");
  }

  @Test
  @DisplayName("invariant 5 reds when more seeds resolved a neighbour than bridged")
  void shouldRedWhenMoreSeedsResolvedANeighbourThanBridged() {
    assertThatThrownBy(
            () -> MusicBrainzProbe.assertInvariants(withSample(new Sample(3, 2, 1, 2, 6, 3, 4))))
        .hasMessageContaining("invariant 5");
  }

  @Test
  @DisplayName("invariant 6 reds when the p90 exceeds the max")
  void shouldRedWhenTheP90ExceedsTheMax() {
    assertThatThrownBy(
            () ->
                MusicBrainzProbe.assertInvariants(
                    withSaving(new Percentiles(1, 3, 2, List.of(1, 2)))))
        .hasMessageContaining("invariant 6");
  }

  @Test
  @DisplayName("invariant 6 reds when the max is not the largest per-seed count")
  void shouldRedWhenTheMaxIsNotTheLargestPerSeedCount() {
    assertThatThrownBy(
            () ->
                MusicBrainzProbe.assertInvariants(
                    withSaving(new Percentiles(1, 2, 2, List.of(1, 5)))))
        .hasMessageContaining("invariant 6");
  }

  @Test
  @DisplayName("invariant 7 reds when more erased nodes carry classes than were erased")
  void shouldRedWhenMoreErasedNodesCarryClassesThanWereErased() {
    assertThatThrownBy(() -> MusicBrainzProbe.assertInvariants(withCost(new Cost(2, 1, 1, 2, 1))))
        .hasMessageContaining("invariant 7");
  }

  @Test
  @DisplayName("invariant 7 reds when the bound cut more seeds than resolved a neighbour")
  void shouldRedWhenTheBoundCutMoreSeedsThanResolvedANeighbour() {
    assertThatThrownBy(() -> MusicBrainzProbe.assertInvariants(withCost(new Cost(2, 1, 1, 1, 3))))
        .hasMessageContaining("invariant 7");
  }

  @Test
  @DisplayName("invariant 7 reds when the class-less creations are not block 3's third bucket")
  void shouldRedWhenTheClassLessCreationsAreNotTheThirdBucket() {
    assertThatThrownBy(() -> MusicBrainzProbe.assertInvariants(withCost(new Cost(1, 1, 1, 1, 1))))
        .hasMessageContaining("invariant 7");
  }

  private static ProbeReport withSample(Sample sample) {
    ProbeReport report = valid();
    return new ProbeReport(
        sample, report.census(), report.buckets(), report.saving(), report.cost());
  }

  private static ProbeReport withSaving(Percentiles saving) {
    ProbeReport report = valid();
    return new ProbeReport(
        report.sample(), report.census(), report.buckets(), saving, report.cost());
  }

  private static ProbeReport withCost(Cost cost) {
    ProbeReport report = valid();
    return new ProbeReport(
        report.sample(), report.census(), report.buckets(), report.saving(), cost);
  }

  // --- Fix round 1

  @Test
  @DisplayName("invariant 0 reds when the probe was handed no seed at all")
  void shouldRedWhenTheProbeWasHandedNoSeed() {
    assertThatThrownBy(
            () -> MusicBrainzProbe.assertInvariants(withSample(new Sample(0, 0, 0, 0, 0, 0, 0))))
        .hasMessageContaining("invariant 0");
  }

  @Test
  @DisplayName("invariant 0 reds when no seed bridged to MusicBrainz")
  void shouldRedWhenNoSeedBridged() {
    assertThatThrownBy(
            () -> MusicBrainzProbe.assertInvariants(withSample(new Sample(3, 2, 1, 0, 6, 0, 4))))
        .hasMessageContaining("invariant 0");
  }

  @Test
  @DisplayName("the privacy invariant reds when a census row is a QID rather than a relation type")
  void shouldRedWhenACensusRowIsAQid() {
    Map<String, Integer> named = new LinkedHashMap<>();
    named.put(UNDESCRIBED, 6);

    assertThatThrownBy(() -> MusicBrainzProbe.assertInvariants(violating(valid(), named)))
        .hasMessageContaining("invariant 8");
  }

  @Test
  @DisplayName("the privacy invariant reds when a census row is shaped like a name, not a type")
  void shouldRedWhenACensusRowIsShapedLikeAName() {
    Map<String, Integer> named = new LinkedHashMap<>();
    named.put("an ensemble and the several names it was billed under", 6);

    assertThatThrownBy(() -> MusicBrainzProbe.assertInvariants(violating(valid(), named)))
        .hasMessageContaining("invariant 8");
  }

  @Test
  @DisplayName("the relation types MusicBrainz actually states are not mistaken for names")
  void shouldAcceptTheRelationTypesMusicBrainzActuallyStates() {
    Map<String, Integer> asStated = new LinkedHashMap<>();
    asStated.put("member of band", 3);
    asStated.put("DJ-mix", 1);
    asStated.put("(has) collaborated on", 1);
    asStated.put("instrumental supporting musician", 1);

    MusicBrainzProbe.assertInvariants(violating(valid(), asStated));
  }

  @Test
  @DisplayName("a run pays for each seed once: one bridge lookup, one batch, one fetch")
  void shouldPayForEachSeedOnceWhenTheProbeRuns() {
    CountingIdentity identity =
        new CountingIdentity(
            Map.of(
                "650bf385-6f6d-4992-a3b9-779d144920a4", IN_THE_GRAPH,
                "ae2017b8-a309-4dd1-87ce-5532e3e3f6a6", UNDESCRIBED),
            Map.of(BRIDGED_SEED, "ee55e4e8-807d-49b1-8470-d1c0898ed7cb"));
    try (GraphStore graph = graphWith()) {
      ProbeReport report =
          MusicBrainzProbe.run(
              new ProbeInputs(
                  List.of(
                      new NodeRecord(BRIDGED_SEED, NodeKind.GROUP, "a seed", List.of()),
                      new NodeRecord(UNBRIDGED_SEED, NodeKind.PERSON, "another seed", List.of())),
                  MusicBrainzClient.readingFrom(fixture()),
                  identity,
                  new WikidataSideThatDescribesOne(),
                  graph));

      // MusicBrainzSourceAdapter.expand opens with mbidFor and follows it with artistRelations and
      // qidsFor, so one mbidFor per seed is what says the adapter was not driven a second time —
      // and therefore that each seed cost exactly one /artist/<mbid> request.
      assertThat(identity.mbidForCalls).as("one bridge lookup per seed").isEqualTo(2);
      assertThat(identity.qidsForCalls).as("one batched neighbour lookup per bridged seed").isOne();
      assertThat(report.sample()).isEqualTo(new Sample(2, 1, 1, 1, 24, 1, 2));
      assertThat(report.census())
          .containsExactly(Map.entry("member of band", 22), Map.entry("named after artist", 2));
      assertThat(report.buckets().alreadyInTheGraph()).isOne();
      assertThat(report.buckets().newAndUndescribed()).isOne();
      MusicBrainzProbe.assertInvariants(report);
    }
  }

  @Test
  @DisplayName("the saving is zero, over no per-seed counts, when no bridged seed had a neighbour")
  void shouldReportZeroPercentilesWhenNoBridgedSeedResolvedANeighbour() {
    try (GraphStore graph = new TinkerGraphStore()) {
      SeedObservation bridgedButAlone =
          new SeedObservation(
              NodeKind.GROUP, true, List.of(relation("tribute")), List.of(), Set.of(), 1);

      ProbeReport report = MusicBrainzProbe.report(List.of(bridgedButAlone), graph);

      assertThat(report.saving())
          .as("no seed resolved a neighbour, so there is no per-seed count to take a rank over")
          .isEqualTo(new Percentiles(0, 0, 0, List.of()));
    }
  }

  @Test
  @DisplayName("a report that shared nothing out, and says so, passes the shape checker")
  void shouldPassWhenNothingWasSharedOut() {
    MusicBrainzProbe.assertInvariants(nothingResolved(new Buckets(0, 0, 0, 0, 0, 0)));
  }

  @Test
  @DisplayName("invariant 4 reds when shares are stated with nothing to share out")
  void shouldRedWhenSharesAreStatedWithNothingToShareOut() {
    assertThatThrownBy(
            () -> MusicBrainzProbe.assertInvariants(nothingResolved(new Buckets(0, 0, 0, 5, 0, 0))))
        .hasMessageContaining("invariant 4");
  }

  private static ProbeReport nothingResolved(Buckets buckets) {
    ProbeReport report = valid();
    return new ProbeReport(
        new Sample(3, 2, 1, 2, 6, 0, 0),
        report.census(),
        buckets,
        new Percentiles(0, 0, 0, List.of()),
        new Cost(0, 0, 0, 0, 0));
  }

  private static Path fixture() {
    try {
      return Path.of(
          MusicBrainzProbeEngineTest.class
              .getResource("/musicbrainz/artist-with-relations.json")
              .toURI());
    } catch (URISyntaxException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Counts what the probe asks of the bridge, which is what says it asked once. */
  private static final class CountingIdentity implements MusicBrainzIdentity {
    private final Map<String, String> neighbourMbidToQid;
    private final Map<String, String> seedQidToMbid;
    private int mbidForCalls;
    private int qidsForCalls;

    private CountingIdentity(
        Map<String, String> neighbourMbidToQid, Map<String, String> seedQidToMbid) {
      this.neighbourMbidToQid = Map.copyOf(neighbourMbidToQid);
      this.seedQidToMbid = Map.copyOf(seedQidToMbid);
    }

    @Override
    public Optional<String> mbidFor(String qid) {
      mbidForCalls++;
      return Optional.ofNullable(seedQidToMbid.get(qid));
    }

    @Override
    public Map<String, String> qidsFor(Collection<String> mbids) {
      qidsForCalls++;
      Map<String, String> resolved = new LinkedHashMap<>();
      for (String mbid : mbids) {
        String qid = neighbourMbidToQid.get(mbid);
        if (qid != null) {
          resolved.put(mbid, qid);
        }
      }
      return Map.copyOf(resolved);
    }
  }

  /** The Wikidata side, describing one neighbour and stating two assertions of its own. */
  private static final class WikidataSideThatDescribesOne implements SourceAdapter {
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
      return new ExpandResult(
          List.of(),
          List.of(
              new NodeAssertion(
                  DESCRIBED_HERE,
                  NodeKind.PERSON,
                  "described in the same call",
                  new Provenance("wikidata", "ref", CLOCK.instant(), 0.9))),
          false,
          false);
    }
  }
}
