package com.robsartin.segue.expand;

import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.segue.domain.AssertionRecord;
import com.robsartin.segue.domain.Candidate;
import com.robsartin.segue.domain.EdgeTypes;
import com.robsartin.segue.domain.LocalEntity;
import com.robsartin.segue.domain.NodeAssertion;
import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.domain.NodeRecord;
import com.robsartin.segue.domain.Provenance;
import com.robsartin.segue.expansion.EntityExpansion;
import com.robsartin.segue.expansion.ExpansionOutcome;
import com.robsartin.segue.ingest.IngestService;
import com.robsartin.segue.port.AssertionLog;
import com.robsartin.segue.port.EntityResolver;
import com.robsartin.segue.port.ExpandContext;
import com.robsartin.segue.port.ExpandResult;
import com.robsartin.segue.port.GraphStore;
import com.robsartin.segue.port.IdentityMerge;
import com.robsartin.segue.port.SourceAdapter;
import com.robsartin.segue.port.SourceAdapters;
import com.robsartin.segue.sqlite.SqliteAssertionLog;
import com.robsartin.segue.tinker.TinkerGraphStore;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The run, over invented promotions. Every id here carries ADR 58's leading zero, or ADR 59's two
 * (#284); nothing here comes from anybody's graph (ADR 33, issue #37).
 */
class ExpandRunTest {

  private static final String IN_GRAPH_ONE = "Q0900701";
  private static final String IN_GRAPH_TWO = "Q0900702";
  private static final String MINTED = "Q00900703";
  private static final String NOT_IN_GRAPH = "Q0900704";

  private static final List<String> PROMOTIONS =
      List.of(IN_GRAPH_ONE, IN_GRAPH_TWO, MINTED, NOT_IN_GRAPH);

  private static final Provenance WIKIDATA =
      new Provenance("wikidata", "S-1", Instant.parse("2026-09-07T09:00:00Z"), 1.0);

  private static final Instant MINTED_AT = Instant.parse("2026-09-07T10:00:00Z");

  /** The example instant, invented. */
  private static final Instant SINCE = Instant.parse("2026-09-08T00:00:00Z");

  @TempDir private Path dir;

  private AssertionLog log;
  private GraphStore graph;
  private AtomicBoolean neverAsked;
  private ExpandRun run;

  @BeforeEach
  void setUp() {
    log = new SqliteAssertionLog(dir.resolve("scratch.db"));
    graph = new TinkerGraphStore();
    IngestService ingest = new IngestService(log, graph, IdentityMerge.NONE);

    ingest.record(
        new NodeAssertion(IN_GRAPH_ONE, NodeKind.PERSON, "an act nobody signed", WIKIDATA));
    ingest.record(
        new NodeAssertion(IN_GRAPH_TWO, NodeKind.PERSON, "an act nobody booked", WIKIDATA));
    ingest.record(
        LocalEntity.minted(MINTED, NodeKind.WORK, "a pamphlet no source indexes", MINTED_AT));
    // NOT_IN_GRAPH is rated but has no node — a rating survives its entity's retraction.

    neverAsked = new AtomicBoolean(true);
    SourceAdapter failIfAsked =
        new SourceAdapter() {
          @Override
          public String id() {
            return "must-not-be-asked";
          }

          @Override
          public boolean supports(NodeKind kind) {
            return true;
          }

          @Override
          public ExpandResult expand(NodeRecord seed, ExpandContext ctx) {
            neverAsked.set(false);
            throw new AssertionError("a dry run must not ask any source anything");
          }
        };
    EntityExpansion expansion =
        new EntityExpansion(
            new NeverCalledResolver(), graph, ingest, new SourceAdapters(List.of(failIfAsked)));
    run = new ExpandRun(expansion, graph);
  }

  @AfterEach
  void tearDown() {
    graph.close();
    log.close();
  }

  @Test
  @DisplayName("a dry run counts what would be visited and asks no source anything")
  void shouldCountWithoutExpandingWhenTheRunIsADryRun() {
    List<String> lines = new ArrayList<>();

    Preflight preflight = run.dryRun(PROMOTIONS, lines::add);

    assertThat(preflight).isEqualTo(new Preflight(4, 2, 1));
    assertThat(neverAsked.get()).as("no adapter was called").isTrue();
    assertThat(lines).anyMatch(line -> line.contains("dry run"));
  }

  @Test
  @DisplayName("a dry run appends nothing to the log")
  void shouldAppendNothingWhenTheRunIsADryRun() {
    int before = log.readAll().size();

    run.dryRun(PROMOTIONS, line -> {});

    assertThat(log.readAll()).hasSize(before);
  }

  @Test
  @DisplayName("a dry run says which population it would have covered when a filter was applied")
  void shouldNameTheInstantWhenADryRunCoversOnlyWhatWasRatedSince() {
    List<String> lines = new ArrayList<>();

    run.dryRun(PROMOTIONS, Optional.of(new RatedSince(SINCE, 2)), lines::add);

    assertThat(lines)
        .as("the clause reaches the consumer, not just the renderer")
        .anyMatch(line -> line.startsWith("# only promotions rated on or after " + SINCE))
        .contains(ExpansionReport.DRY_RUN_HEADER);
  }

  @Test
  @DisplayName("every promotion is visited in the order given, ascending by qid")
  void shouldVisitEveryPromotionInQidOrderWhenTheRunIsNotADryRun() {
    String seedOne = "Q0900821";
    String seedTwo = "Q0900822";
    String seedThree = "Q0900823";
    List<String> promotions = List.of(seedOne, seedTwo, seedThree);

    try (AssertionLog scriptLog = new SqliteAssertionLog(dir.resolve("order.db"));
        GraphStore scriptGraph = new TinkerGraphStore()) {
      IngestService ingest = new IngestService(scriptLog, scriptGraph, IdentityMerge.NONE);
      for (String qid : promotions) {
        ingest.record(new NodeAssertion(qid, NodeKind.PERSON, "an act nobody signed", WIKIDATA));
      }
      RecordingAdapter recorder = new RecordingAdapter("wikidata");
      EntityExpansion expansion =
          new EntityExpansion(
              new NeverCalledResolver(),
              scriptGraph,
              ingest,
              new SourceAdapters(List.of(recorder)));
      ExpandRun scriptedRun = new ExpandRun(expansion, scriptGraph);

      scriptedRun.run(promotions, 10, line -> {});

      assertThat(recorder.seenQids()).containsExactly(seedOne, seedTwo, seedThree);
    }
  }

  @Test
  @DisplayName("the counts sum across every successful expansion")
  void shouldTallyTheCountsWhenEveryExpansionSucceeds() {
    String seedOne = "Q0900831";
    String seedTwo = "Q0900832";
    String neighbourOne = "Q0900841";
    String neighbourTwo = "Q0900842";

    try (AssertionLog scriptLog = new SqliteAssertionLog(dir.resolve("sums.db"));
        GraphStore scriptGraph = new TinkerGraphStore()) {
      IngestService ingest = new IngestService(scriptLog, scriptGraph, IdentityMerge.NONE);
      ingest.record(new NodeAssertion(seedOne, NodeKind.PERSON, "an act nobody signed", WIKIDATA));
      ingest.record(new NodeAssertion(seedTwo, NodeKind.PERSON, "an act nobody booked", WIKIDATA));
      ScriptedResolver resolver =
          new ScriptedResolver()
              .withEntity(
                  new NodeAssertion(neighbourOne, NodeKind.GROUP, "a band nobody named", WIKIDATA))
              .withEntity(
                  new NodeAssertion(
                      neighbourTwo, NodeKind.GROUP, "a band nobody covered", WIKIDATA));
      Map<String, ExpandResult> bySeed =
          Map.of(
              seedOne, ExpandResult.of(List.of(memberOf(seedOne, neighbourOne))),
              seedTwo, ExpandResult.of(List.of(memberOf(seedTwo, neighbourTwo))));
      ScriptedAdapter adapter = new ScriptedAdapter("wikidata", bySeed);
      EntityExpansion expansion =
          new EntityExpansion(resolver, scriptGraph, ingest, new SourceAdapters(List.of(adapter)));
      ExpandRun scriptedRun = new ExpandRun(expansion, scriptGraph);

      ExpansionTally tally = scriptedRun.run(List.of(seedOne, seedTwo), 10, line -> {});

      assertThat(tally.expanded()).isEqualTo(2);
      assertThat(tally.nodesAdded()).isEqualTo(2);
      assertThat(tally.edgesAdded()).isEqualTo(2);
      assertThat(tally.edgesBySource()).containsExactly(Map.entry("wikidata", 2));
    }
  }

  @Test
  @DisplayName("an expansion that adds nothing is still counted as expanded")
  void shouldCountAnExpansionThatAddedNothingWhenNeitherCountMoved() {
    String seed = "Q0900851";

    try (AssertionLog scriptLog = new SqliteAssertionLog(dir.resolve("nothing.db"));
        GraphStore scriptGraph = new TinkerGraphStore()) {
      IngestService ingest = new IngestService(scriptLog, scriptGraph, IdentityMerge.NONE);
      ingest.record(new NodeAssertion(seed, NodeKind.PERSON, "an act nobody signed", WIKIDATA));
      ScriptedAdapter adapter = new ScriptedAdapter("wikidata", Map.of());
      EntityExpansion expansion =
          new EntityExpansion(
              new NeverCalledResolver(), scriptGraph, ingest, new SourceAdapters(List.of(adapter)));
      ExpandRun scriptedRun = new ExpandRun(expansion, scriptGraph);

      ExpansionTally tally = scriptedRun.run(List.of(seed), 10, line -> {});

      assertThat(tally.expanded()).isEqualTo(1);
      assertThat(tally.addedNothing()).isEqualTo(1);
    }
  }

  @Test
  @DisplayName("a refusal is tallied by its reason")
  void shouldTallyARefusalByItsReasonWhenAnEntityCannotBeExpanded() {
    String minted = "Q00900861";

    try (AssertionLog scriptLog = new SqliteAssertionLog(dir.resolve("refusal.db"));
        GraphStore scriptGraph = new TinkerGraphStore()) {
      IngestService ingest = new IngestService(scriptLog, scriptGraph, IdentityMerge.NONE);
      ingest.record(
          LocalEntity.minted(minted, NodeKind.WORK, "a pamphlet no source indexes", MINTED_AT));
      ScriptedAdapter adapter = new ScriptedAdapter("wikidata", Map.of());
      EntityExpansion expansion =
          new EntityExpansion(
              new NeverCalledResolver(), scriptGraph, ingest, new SourceAdapters(List.of(adapter)));
      ExpandRun scriptedRun = new ExpandRun(expansion, scriptGraph);

      ExpansionTally tally = scriptedRun.run(List.of(minted), 10, line -> {});

      assertThat(tally.refusalsByReason())
          .containsExactly(Map.entry(ExpansionOutcome.Reason.LOCAL_ENTITY, 1));
      assertThat(tally.expanded()).isZero();
    }
  }

  @Test
  @DisplayName("a shortfall is tallied to its source, and a bound cut is tallied by entity")
  void shouldTallyAShortfallToItsSourceWhenAnAdapterCouldNotBeReached() {
    String seedShortfall = "Q0900871";
    String seedBoundCut = "Q0900872";
    String neighbourOne = "Q0900881";
    String neighbourTwo = "Q0900882";

    try (AssertionLog scriptLog = new SqliteAssertionLog(dir.resolve("shortfall.db"));
        GraphStore scriptGraph = new TinkerGraphStore()) {
      IngestService ingest = new IngestService(scriptLog, scriptGraph, IdentityMerge.NONE);
      ingest.record(
          new NodeAssertion(seedShortfall, NodeKind.PERSON, "an act nobody signed", WIKIDATA));
      ingest.record(
          new NodeAssertion(seedBoundCut, NodeKind.PERSON, "an act nobody booked", WIKIDATA));
      ScriptedResolver resolver =
          new ScriptedResolver()
              .withEntity(
                  new NodeAssertion(neighbourOne, NodeKind.GROUP, "a band nobody named", WIKIDATA))
              .withEntity(
                  new NodeAssertion(
                      neighbourTwo, NodeKind.GROUP, "a band nobody covered", WIKIDATA));

      SourceAdapter unavailable =
          new FixedAdapter("unavailable-source", ExpandResult.unavailable());
      SourceAdapter truncating =
          new FixedAdapter(
              "truncating-source",
              new ExpandResult(List.of(memberOf(seedShortfall, neighbourOne)), false, true));
      EntityExpansion shortfallExpansion =
          new EntityExpansion(
              resolver, scriptGraph, ingest, new SourceAdapters(List.of(unavailable, truncating)));
      ExpandRun shortfallRun = new ExpandRun(shortfallExpansion, scriptGraph);

      ExpansionTally shortfallTally = shortfallRun.run(List.of(seedShortfall), 10, line -> {});

      assertThat(shortfallTally.unavailableBySource())
          .containsExactly(Map.entry("unavailable-source", 1));
      assertThat(shortfallTally.truncatedBySource())
          .containsExactly(Map.entry("truncating-source", 1));

      SourceAdapter twoAssertions =
          new FixedAdapter(
              "wikidata",
              ExpandResult.of(
                  List.of(
                      memberOf(seedBoundCut, neighbourOne), memberOf(seedBoundCut, neighbourTwo))));
      EntityExpansion boundExpansion =
          new EntityExpansion(
              resolver, scriptGraph, ingest, new SourceAdapters(List.of(twoAssertions)));
      ExpandRun boundRun = new ExpandRun(boundExpansion, scriptGraph);

      ExpansionTally boundTally = boundRun.run(List.of(seedBoundCut), 1, line -> {});

      assertThat(boundTally.boundCut())
          .as("the cut is attributable to no single adapter, so it is counted by entity")
          .isEqualTo(1);
    }
  }

  @Test
  @DisplayName("an adapter that throws counts as failed, and the run carries on")
  void shouldCountTheFailureAndCarryOnWhenAnAdapterThrows() {
    String seedOne = "Q0900891";
    String seedTwo = "Q0900892";
    String seedThree = "Q0900893";
    List<String> promotions = List.of(seedOne, seedTwo, seedThree);

    try (AssertionLog scriptLog = new SqliteAssertionLog(dir.resolve("throws.db"));
        GraphStore scriptGraph = new TinkerGraphStore()) {
      IngestService ingest = new IngestService(scriptLog, scriptGraph, IdentityMerge.NONE);
      for (String qid : promotions) {
        ingest.record(new NodeAssertion(qid, NodeKind.PERSON, "an act nobody signed", WIKIDATA));
      }
      ThrowingOnSecondAdapter adapter = new ThrowingOnSecondAdapter("wikidata");
      EntityExpansion expansion =
          new EntityExpansion(
              new NeverCalledResolver(), scriptGraph, ingest, new SourceAdapters(List.of(adapter)));
      ExpandRun scriptedRun = new ExpandRun(expansion, scriptGraph);

      ExpansionTally tally = scriptedRun.run(promotions, 10, line -> {});

      assertThat(tally.failed()).isEqualTo(1);
      assertThat(tally.expanded()).isEqualTo(2);
      assertThat(adapter.seenQids())
          .as("the third entity is still visited after the second one throws")
          .containsExactly(seedOne, seedTwo, seedThree);
    }
  }

  @Test
  @DisplayName("a partial expansion says what fell short, and still names nothing")
  void shouldSayWhatFellShortWhenAnExpansionWasPartial() {
    String seedShortfall = "Q0900941";
    String seedBoundCut = "Q0900942";
    String neighbourOne = "Q0900943";
    String neighbourTwo = "Q0900944";
    String nobodyDescribed = "Q0900945";

    try (AssertionLog scriptLog = new SqliteAssertionLog(dir.resolve("partial.db"));
        GraphStore scriptGraph = new TinkerGraphStore()) {
      IngestService ingest = new IngestService(scriptLog, scriptGraph, IdentityMerge.NONE);
      ingest.record(
          new NodeAssertion(seedShortfall, NodeKind.PERSON, "an act nobody signed", WIKIDATA));
      ingest.record(
          new NodeAssertion(seedBoundCut, NodeKind.PERSON, "an act nobody booked", WIKIDATA));
      ScriptedResolver resolver =
          new ScriptedResolver()
              .withEntity(
                  new NodeAssertion(neighbourOne, NodeKind.GROUP, "a band nobody named", WIKIDATA))
              .withEntity(
                  new NodeAssertion(
                      neighbourTwo, NodeKind.GROUP, "a band nobody covered", WIKIDATA));

      // An edge naming the seed at NEITHER end: the near end resolves, the far end is an entity
      // the graph holds no node for, so IngestService refuses it and the expansion reports the
      // endpoint (#233).
      SourceAdapter unavailable =
          new FixedAdapter("unavailable-source", ExpandResult.unavailable());
      SourceAdapter refusedEndpoint =
          new FixedAdapter(
              "wikidata", ExpandResult.of(List.of(memberOf(neighbourOne, nobodyDescribed))));
      ExpandRun shortfallRun =
          new ExpandRun(
              new EntityExpansion(
                  resolver,
                  scriptGraph,
                  ingest,
                  new SourceAdapters(List.of(unavailable, refusedEndpoint))),
              scriptGraph);
      List<String> shortfallLines = new ArrayList<>();

      shortfallRun.run(List.of(seedShortfall), 10, shortfallLines::add);

      assertThat(shortfallLines)
          .as("the spec's third progress-line form, which a clean expansion never prints")
          .contains("[1/1] partial: 1 source(s) unavailable, 1 endpoint(s) refused");

      SourceAdapter truncating =
          new FixedAdapter(
              "truncating-source",
              new ExpandResult(
                  List.of(
                      memberOf(seedBoundCut, neighbourOne), memberOf(seedBoundCut, neighbourTwo)),
                  false,
                  true));
      ExpandRun boundRun =
          new ExpandRun(
              new EntityExpansion(
                  resolver, scriptGraph, ingest, new SourceAdapters(List.of(truncating))),
              scriptGraph);
      List<String> boundLines = new ArrayList<>();

      boundRun.run(List.of(seedBoundCut), 1, boundLines::add);

      assertThat(boundLines)
          .as("a bound cut is attributable to no adapter, so the clause names none")
          .contains("[1/1] partial: 1 source(s) truncated, the bound cut the result");
    }
  }

  @Test
  @DisplayName("a clean expansion's progress line counts assertions, not pairs of nodes")
  void shouldNameTheCountAsAssertionsWhenACleanExpansionReportsItsYield() {
    String seed = "Q0900961";
    String neighbour = "Q0900962";

    try (AssertionLog scriptLog = new SqliteAssertionLog(dir.resolve("yield.db"));
        GraphStore scriptGraph = new TinkerGraphStore()) {
      IngestService ingest = new IngestService(scriptLog, scriptGraph, IdentityMerge.NONE);
      ingest.record(new NodeAssertion(seed, NodeKind.PERSON, "an act nobody signed", WIKIDATA));
      ScriptedResolver resolver =
          new ScriptedResolver()
              .withEntity(
                  new NodeAssertion(neighbour, NodeKind.GROUP, "a band nobody named", WIKIDATA));
      ScriptedAdapter adapter =
          new ScriptedAdapter(
              "wikidata", Map.of(seed, ExpandResult.of(List.of(memberOf(seed, neighbour)))));
      ExpandRun scriptedRun =
          new ExpandRun(
              new EntityExpansion(
                  resolver, scriptGraph, ingest, new SourceAdapters(List.of(adapter))),
              scriptGraph);
      List<String> lines = new ArrayList<>();

      scriptedRun.run(List.of(seed), 10, lines::add);

      assertThat(lines)
          .as(
              "the first progress-line form, in the block's own words: one increment per"
                  + " assertion recorded, never per pair of nodes (#293)")
          .contains("[1/1] 1 edge assertion(s), 1 new node(s)");
    }
  }

  @Test
  @DisplayName("the report's header reaches the consumer once the run is done")
  void shouldEmitTheReportsHeaderWhenTheRunFinishes() {
    String seed = "Q0900921";

    try (AssertionLog scriptLog = new SqliteAssertionLog(dir.resolve("header.db"));
        GraphStore scriptGraph = new TinkerGraphStore()) {
      IngestService ingest = new IngestService(scriptLog, scriptGraph, IdentityMerge.NONE);
      ingest.record(new NodeAssertion(seed, NodeKind.PERSON, "an act nobody signed", WIKIDATA));
      ScriptedAdapter adapter = new ScriptedAdapter("wikidata", Map.of());
      EntityExpansion expansion =
          new EntityExpansion(
              new NeverCalledResolver(), scriptGraph, ingest, new SourceAdapters(List.of(adapter)));
      ExpandRun scriptedRun = new ExpandRun(expansion, scriptGraph);
      List<String> lines = new ArrayList<>();

      scriptedRun.run(List.of(seed), 10, lines::add);

      assertThat(lines).contains(ExpansionReport.HEADER);
    }
  }

  @Test
  @DisplayName("a run says which population it covered when a filter was applied")
  void shouldNameTheInstantWhenARunCoversOnlyWhatWasRatedSince() {
    String seed = "Q0900703";

    try (AssertionLog scriptLog = new SqliteAssertionLog(dir.resolve("since.db"));
        GraphStore scriptGraph = new TinkerGraphStore()) {
      IngestService ingest = new IngestService(scriptLog, scriptGraph, IdentityMerge.NONE);
      ingest.record(new NodeAssertion(seed, NodeKind.PERSON, "an act nobody signed", WIKIDATA));
      ScriptedAdapter adapter = new ScriptedAdapter("wikidata", Map.of());
      EntityExpansion expansion =
          new EntityExpansion(
              new NeverCalledResolver(), scriptGraph, ingest, new SourceAdapters(List.of(adapter)));
      ExpandRun scriptedRun = new ExpandRun(expansion, scriptGraph);
      List<String> lines = new ArrayList<>();

      scriptedRun.run(List.of(seed), Optional.of(new RatedSince(SINCE, 2)), 10, lines::add);

      assertThat(lines)
          .as("the clause reaches the consumer, not just the renderer")
          .anyMatch(line -> line.startsWith("# only promotions rated on or after " + SINCE))
          .contains(ExpansionReport.HEADER);
    }
  }

  @Test
  @DisplayName("no progress line names an entity, whatever the outcome")
  void shouldNameNoEntityWhenAProgressLineIsWritten() {
    String succeeds = "Q0900901";
    String refused = "Q00900902";
    String fails = "Q0900903";
    String neighbour = "Q0900911";

    try (AssertionLog scriptLog = new SqliteAssertionLog(dir.resolve("naming.db"));
        GraphStore scriptGraph = new TinkerGraphStore()) {
      IngestService ingest = new IngestService(scriptLog, scriptGraph, IdentityMerge.NONE);
      ingest.record(new NodeAssertion(succeeds, NodeKind.PERSON, "an act nobody signed", WIKIDATA));
      ingest.record(
          LocalEntity.minted(refused, NodeKind.WORK, "a pamphlet no source indexes", MINTED_AT));
      ingest.record(new NodeAssertion(fails, NodeKind.PERSON, "an act nobody booked", WIKIDATA));
      ScriptedResolver resolver =
          new ScriptedResolver()
              .withEntity(
                  new NodeAssertion(neighbour, NodeKind.GROUP, "a band nobody named", WIKIDATA));
      Map<String, ExpandResult> bySeed =
          Map.of(succeeds, ExpandResult.of(List.of(memberOf(succeeds, neighbour))));
      SourceAdapter adapter =
          new SourceAdapter() {
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
              if (seed.qid().equals(fails)) {
                throw new IllegalStateException("a source went sideways");
              }
              return bySeed.getOrDefault(seed.qid(), ExpandResult.of(List.of()));
            }
          };
      EntityExpansion expansion =
          new EntityExpansion(resolver, scriptGraph, ingest, new SourceAdapters(List.of(adapter)));
      ExpandRun scriptedRun = new ExpandRun(expansion, scriptGraph);
      List<String> lines = new ArrayList<>();

      scriptedRun.run(List.of(succeeds, refused, fails), 10, lines::add);

      assertThat(lines).isNotEmpty();
      Pattern qidLike = Pattern.compile("\\bQ\\d+\\b");
      assertThat(lines).noneMatch(line -> qidLike.matcher(line).find());
    }
  }

  private static AssertionRecord memberOf(String from, String to) {
    return new AssertionRecord(from, to, EdgeTypes.MEMBER_OF.code(), null, null, WIKIDATA);
  }

  /** Records the qids it is asked about, in the order it is asked, and adds nothing. */
  private static final class RecordingAdapter implements SourceAdapter {
    private final String id;
    private final List<String> seenQids = new ArrayList<>();

    RecordingAdapter(String id) {
      this.id = id;
    }

    List<String> seenQids() {
      return List.copyOf(seenQids);
    }

    @Override
    public String id() {
      return id;
    }

    @Override
    public boolean supports(NodeKind kind) {
      return true;
    }

    @Override
    public ExpandResult expand(NodeRecord seed, ExpandContext ctx) {
      seenQids.add(seed.qid());
      return ExpandResult.of(List.of());
    }
  }

  /** Answers a fixed result, whichever seed it is asked about. */
  private static final class FixedAdapter implements SourceAdapter {
    private final String id;
    private final ExpandResult result;

    FixedAdapter(String id, ExpandResult result) {
      this.id = id;
      this.result = result;
    }

    @Override
    public String id() {
      return id;
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

  /** Answers a scripted result per seed qid, or an empty one for any seed not scripted. */
  private static final class ScriptedAdapter implements SourceAdapter {
    private final String id;
    private final Map<String, ExpandResult> bySeed;

    ScriptedAdapter(String id, Map<String, ExpandResult> bySeed) {
      this.id = id;
      this.bySeed = bySeed;
    }

    @Override
    public String id() {
      return id;
    }

    @Override
    public boolean supports(NodeKind kind) {
      return true;
    }

    @Override
    public ExpandResult expand(NodeRecord seed, ExpandContext ctx) {
      return bySeed.getOrDefault(seed.qid(), ExpandResult.of(List.of()));
    }
  }

  /** Throws on the second call and records every qid it was asked about, in order. */
  private static final class ThrowingOnSecondAdapter implements SourceAdapter {
    private final String id;
    private final List<String> seenQids = new ArrayList<>();

    ThrowingOnSecondAdapter(String id) {
      this.id = id;
    }

    List<String> seenQids() {
      return List.copyOf(seenQids);
    }

    @Override
    public String id() {
      return id;
    }

    @Override
    public boolean supports(NodeKind kind) {
      return true;
    }

    @Override
    public ExpandResult expand(NodeRecord seed, ExpandContext ctx) {
      seenQids.add(seed.qid());
      if (seenQids.size() == 2) {
        throw new IllegalStateException("the second adapter call always throws");
      }
      return ExpandResult.of(List.of());
    }
  }

  /** Answers for whatever it has been handed, and nothing else. Opens no socket. */
  private static final class ScriptedResolver implements EntityResolver {
    private final Map<String, NodeAssertion> byQid = new HashMap<>();

    @Override
    public String id() {
      return "scripted";
    }

    @Override
    public List<Candidate> search(String query, NodeKind kind, int limit) {
      return List.of();
    }

    @Override
    public Optional<NodeAssertion> fetch(String qid) {
      return Optional.ofNullable(byQid.get(qid));
    }

    ScriptedResolver withEntity(NodeAssertion assertion) {
      byQid.put(assertion.qid(), assertion);
      return this;
    }
  }

  /** Answers for nothing — a dry run must never reach it. */
  private static final class NeverCalledResolver implements EntityResolver {

    @Override
    public String id() {
      return "never-called";
    }

    @Override
    public List<Candidate> search(String query, NodeKind kind, int limit) {
      throw new AssertionError("a dry run must not ask the resolver anything");
    }

    @Override
    public Optional<NodeAssertion> fetch(String qid) {
      throw new AssertionError("a dry run must not ask the resolver anything");
    }
  }
}
