package com.robsartin.segue.recommend;

import static com.robsartin.segue.recommend.InventedWorld.ANCESTOR;
import static com.robsartin.segue.recommend.InventedWorld.A_THIN_BAND;
import static com.robsartin.segue.recommend.InventedWorld.INSTITUTIONS;
import static com.robsartin.segue.recommend.InventedWorld.JUST_DISCOVERED;
import static com.robsartin.segue.recommend.InventedWorld.KNOWN_ONE;
import static com.robsartin.segue.recommend.InventedWorld.KNOWN_TWO;
import static com.robsartin.segue.recommend.InventedWorld.SHARED_ARTIST;
import static com.robsartin.segue.recommend.InventedWorld.edge;
import static com.robsartin.segue.recommend.InventedWorld.node;
import static com.robsartin.segue.recommend.InventedWorld.padDegreeTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.segue.domain.EdgeTypes;
import com.robsartin.segue.domain.Equivalences;
import com.robsartin.segue.domain.KnownList;
import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.domain.Recommendations;
import com.robsartin.segue.domain.Scorer;
import com.robsartin.segue.recommend.RecommendCli.Options;
import com.robsartin.segue.tinker.TinkerGraphStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Warn, sweep, rank, explain, report, write — in that order, and the order is the design. */
class RecommendRunTest {

  private static final int FLOOR = 4;

  @TempDir private Path dir;

  private TinkerGraphStore graph;
  private final List<String> notes = new ArrayList<>();

  @BeforeEach
  void setUp() {
    graph = new TinkerGraphStore();
    node(graph, KNOWN_ONE, NodeKind.GROUP, "one you know");
    node(graph, KNOWN_TWO, NodeKind.GROUP, "another you know");
    node(graph, SHARED_ARTIST, NodeKind.PERSON, "the artist they both cite");
    node(graph, ANCESTOR, NodeKind.GROUP, "who that artist cites");
    edge(graph, KNOWN_ONE, SHARED_ARTIST, EdgeTypes.INFLUENCED_BY.code());
    edge(graph, KNOWN_TWO, SHARED_ARTIST, EdgeTypes.INFLUENCED_BY.code());
    edge(graph, SHARED_ARTIST, ANCESTOR, EdgeTypes.INFLUENCED_BY.code());
    padDegreeTo(graph, ANCESTOR, FLOOR);
  }

  @AfterEach
  void tearDown() {
    graph.close();
  }

  private Options options(String... known) throws IOException {
    Path list = dir.resolve("known.csv");
    Files.writeString(list, String.join("\n", known) + "\n", StandardCharsets.UTF_8);
    return new Options(
        dir.resolve("segue.db"), list, dir.resolve("out.txt"), Scorer.LIFT, FLOOR, 10);
  }

  private List<Explained> run(Options options) throws IOException {
    return run(options, Map.of());
  }

  private List<Explained> run(Options options, Map<String, Integer> ratings) throws IOException {
    return new RecommendRun(
            graph, INSTITUTIONS, Recommendations.EQUAL_REGARD, ratings, Equivalences.NONE)
        .run(options, notes::add);
  }

  @Test
  @DisplayName("it recommends what the list does not already name, and explains it")
  void itRecommendsWhatTheListDoesNotName() throws IOException {
    List<Explained> explained = run(options(KNOWN_ONE, KNOWN_TWO));

    assertThat(explained).hasSize(1);
    assertThat(explained.get(0).candidate().entity().qid()).isEqualTo(ANCESTOR);
    assertThat(explained.get(0).routes()).isNotEmpty();
  }

  @Test
  @DisplayName("the warning comes first, before a candidate exists and long before the file does")
  void theWarningComesFirst() throws IOException {
    Options options = options(KNOWN_ONE, KNOWN_TWO);
    new RecommendRun(graph, INSTITUTIONS, Recommendations.EQUAL_REGARD, Map.of(), Equivalences.NONE)
        .run(
            options,
            note -> {
              if (notes.isEmpty()) {
                assertThat(note).isEqualTo(RecommendRun.PERSONAL_DATA_WARNING);
                assertThat(options.out()).doesNotExist();
              }
              notes.add(note);
            });

    assertThat(notes.get(0)).isEqualTo(RecommendRun.PERSONAL_DATA_WARNING);
  }

  @Test
  @DisplayName("what it looked at is reported while the output file still does not exist")
  void theCountsArriveBeforeTheWrite() throws IOException {
    Options options = options(KNOWN_ONE, KNOWN_TWO);
    List<String> beforeTheFile = new ArrayList<>();
    new RecommendRun(graph, INSTITUTIONS, Recommendations.EQUAL_REGARD, Map.of(), Equivalences.NONE)
        .run(
            options,
            note -> {
              if (!java.nio.file.Files.exists(options.out())) {
                beforeTheFile.add(note);
              }
            });

    assertThat(beforeTheFile).anyMatch(note -> note.contains("candidate"));
    assertThat(beforeTheFile).anyMatch(note -> note.contains("2 entity(ies)"));
    assertThat(beforeTheFile).anyMatch(note -> note.contains("cleared the floor of"));
    assertThat(beforeTheFile).anyMatch(note -> note.contains("held out"));
  }

  @Test
  @DisplayName("the floor reading's notes carry the sweep's two counts in their own places")
  void theFloorReadingNotesCarryTheSweepsCounts() throws IOException {
    // Two entities below the floor, exactly one of them at a single edge, so that the two counts
    // differ. They were both asserted only through "contains" before, which left the wiring
    // ungated: swapping heldOutByFloor for heldOutAtDegreeOne kept the whole suite green.
    node(graph, JUST_DISCOVERED, NodeKind.GROUP, "one edge to its name");
    node(graph, A_THIN_BAND, NodeKind.GROUP, "two edges to its name");
    edge(graph, SHARED_ARTIST, JUST_DISCOVERED, EdgeTypes.INFLUENCED_BY.code());
    edge(graph, SHARED_ARTIST, A_THIN_BAND, EdgeTypes.INFLUENCED_BY.code());
    padDegreeTo(graph, A_THIN_BAND, 2);

    run(options(KNOWN_ONE, KNOWN_TWO));

    assertThat(notes)
        .contains(
            "1 candidate(s) cleared the floor of 4 at median degree 4; 1 of the 1 ranked sit"
                + " exactly on it")
        .contains(
            "the floor held out 2 entity(ies), 1 of them at a single edge (issues #134, #135)");
  }

  @Test
  @DisplayName("the file is written where it was asked for, with the routes in it")
  void theFileIsWritten() throws IOException {
    Options options = options(KNOWN_ONE, KNOWN_TWO);

    run(options);

    String written = Files.readString(options.out(), StandardCharsets.UTF_8);
    assertThat(written).startsWith(RecommendationReport.PERSONAL_DATA_HEADER);
    assertThat(written).contains("who that artist cites");
    assertThat(written).contains("-[INFLUENCED_BY]->");
    assertThat(notes).anyMatch(note -> note.contains(options.out().toString()));
  }

  @Test
  @DisplayName("a known entity this graph has never heard of is reported, not fatal")
  void anAbsentKnownEntityIsReported() throws IOException {
    run(options(KNOWN_ONE, KNOWN_TWO, "Q900999"));

    assertThat(notes).anyMatch(note -> note.contains("1") && note.contains("not in this graph"));
  }

  @Test
  @DisplayName("no note names an entity: the recommendations are in the file and nowhere else")
  void noNoteNamesAnEntity() throws IOException {
    run(options(KNOWN_ONE, KNOWN_TWO));

    assertThat(notes)
        .noneMatch(note -> note.contains("Q900") || note.contains("who that artist cites"));
  }

  @Test
  @DisplayName(
      "an entity rated 4 or higher but absent from the file is promoted onto the known-list, so"
          + " it is no longer recommended back (issue #106)")
  void aHighlyRatedCandidateIsNoLongerRecommended() throws IOException {
    // ANCESTOR is exactly the candidate itRecommendsWhatTheListDoesNotName finds; rating it
    // promotes it into the known-list that CandidateSweep filters candidates against.
    List<Explained> explained = run(options(KNOWN_ONE, KNOWN_TWO), Map.of(ANCESTOR, 4));

    assertThat(explained).isEmpty();
  }

  @Test
  @DisplayName(
      "an entity rated at or below the suppression threshold is never recommended, even though"
          + " it would otherwise qualify (issue #106) — proves the suppressed set actually"
          + " reaches CandidateSweep from RecommendRun, not just that CandidateSweep honours one"
          + " in isolation")
  void aRejectedCandidateIsNeverRecommended() throws IOException {
    // ANCESTOR is exactly the candidate itRecommendsWhatTheListDoesNotName finds unaided.
    // Suppressing it must be what keeps it out here — nothing else in this fixture would.
    List<Explained> explained =
        run(options(KNOWN_ONE, KNOWN_TWO), Map.of(ANCESTOR, KnownList.SUPPRESSION_RATING));

    assertThat(explained).isEmpty();
  }
}
