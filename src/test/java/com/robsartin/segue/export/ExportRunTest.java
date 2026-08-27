package com.robsartin.segue.export;

import static com.robsartin.segue.export.InventedGraph.KETTLES;
import static com.robsartin.segue.export.InventedGraph.MARLOW;
import static com.robsartin.segue.export.InventedGraph.WREN;
import static com.robsartin.segue.export.InventedGraph.edge;
import static com.robsartin.segue.export.InventedGraph.node;
import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.segue.domain.AffinityRecord;
import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.export.ExportCli.Options;
import com.robsartin.segue.export.InventedGraph.FakeAssertionLog;
import com.robsartin.segue.ingest.GraphProjector;
import com.robsartin.segue.port.AffinityStore;
import com.robsartin.segue.tinker.TinkerGraphStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Invented entities, invented ratings (ADR 40, issue #37). */
class ExportRunTest {

  @TempDir Path dir;

  private TinkerGraphStore graph;
  private ViewSelector selector;

  /** Every note the run produced, paired with whether the output file existed at that moment. */
  private final List<String> notes = new ArrayList<>();

  private final List<Boolean> fileExistedWhenNoted = new ArrayList<>();

  @BeforeEach
  void buildAnInventedGraph() {
    FakeAssertionLog log =
        new FakeAssertionLog()
            .with(
                node(WREN, NodeKind.PERSON, "Wren Alderman"),
                node(KETTLES, NodeKind.GROUP, "The Paper Kettles"),
                node(MARLOW, NodeKind.PERSON, "Ida Marlow"),
                edge(WREN, KETTLES, "MEMBER_OF"),
                edge(MARLOW, KETTLES, "MEMBER_OF"));
    graph = new TinkerGraphStore();
    GraphProjector.project(log, graph);
    selector = new ViewSelector(graph, log);
  }

  @AfterEach
  void closeTheGraph() {
    graph.close();
  }

  private Path out(String name) {
    return dir.resolve(name);
  }

  private Options fullTo(Path out, boolean includeAffinity) {
    return new Options(
        ViewKind.FULL,
        OutputFormat.GRAPHML,
        dir.resolve("unused.db"),
        out,
        null,
        null,
        4,
        null,
        1,
        null,
        includeAffinity);
  }

  private GraphView run(Options options, AffinityOverlay overlay) throws IOException {
    return new ExportRun(selector, overlay, options.format().writer())
        .run(
            options,
            note -> {
              notes.add(note);
              fileExistedWhenNoted.add(Files.exists(options.out()));
            });
  }

  @Test
  @DisplayName("it writes the file the options named, in the format they named")
  void writesTheFile() throws IOException {
    Path out = out("all.graphml");

    run(fullTo(out, false), null);

    assertThat(Files.readString(out)).startsWith("<?xml").contains("<graphml");
  }

  @Test
  @DisplayName("the counts are reported BEFORE anything is written")
  void reportsCountsBeforeWriting() throws IOException {
    Path out = out("all.graphml");

    run(fullTo(out, false), null);

    int counts = indexOfNoteContaining("3 node(s), 2 edge(s)");
    assertThat(counts).as("a note carrying the counts").isNotNegative();
    assertThat(fileExistedWhenNoted.get(counts))
        .as("the output file did not exist yet when the counts were reported")
        .isFalse();
  }

  @Test
  @DisplayName("the last note names the file, so the operator knows where it went")
  void reportsWhereItWent() throws IOException {
    Path out = out("all.graphml");

    run(fullTo(out, false), null);

    assertThat(notes.get(notes.size() - 1)).contains(out.toString());
  }

  @Test
  @DisplayName("a route view is selected through the selector, not re-implemented here")
  void runsARouteView() throws IOException {
    Path out = out("route.dot");
    Options options =
        new Options(
            ViewKind.ROUTE,
            OutputFormat.DOT,
            dir.resolve("unused.db"),
            out,
            WREN,
            MARLOW,
            4,
            null,
            1,
            null,
            false);

    GraphView view = run(options, null);

    assertThat(view.nodes()).extracting(ViewNode::qid).containsExactly(WREN, KETTLES, MARLOW);
    assertThat(Files.readString(out)).startsWith("digraph");
  }

  @Test
  @DisplayName("a neighbourhood view honours the depth it was given")
  void runsANeighbourhoodView() throws IOException {
    Options options =
        new Options(
            ViewKind.NEIGHBOURHOOD,
            OutputFormat.GRAPHML,
            dir.resolve("unused.db"),
            out("n.graphml"),
            null,
            null,
            4,
            KETTLES,
            1,
            null,
            false);

    GraphView view = run(options, null);

    assertThat(view.nodes()).hasSize(3);
  }

  @Test
  @DisplayName("a subgraph view reads its entity list from the file the options named")
  void runsASubgraphView() throws IOException {
    Path list = dir.resolve("seeds.csv");
    Files.writeString(list, WREN + "\n" + MARLOW + "\n");
    Options options =
        new Options(
            ViewKind.SUBGRAPH,
            OutputFormat.GRAPHML,
            dir.resolve("unused.db"),
            out("s.graphml"),
            null,
            null,
            4,
            null,
            1,
            list,
            false);

    GraphView view = run(options, null);

    assertThat(view.nodes()).extracting(ViewNode::qid).containsExactly(WREN, MARLOW);
    assertThat(view.edges()).as("the intermediate they both connect through is stripped").isEmpty();
  }

  @Test
  @DisplayName("no affinity, and no warning, unless an overlay was supplied")
  void carriesNoAffinityByDefault() throws IOException {
    GraphView view = run(fullTo(out("all.graphml"), false), null);

    assertThat(view.carriesAffinity()).isFalse();
    assertThat(notes).noneMatch(note -> note.contains("personal data"));
  }

  @Test
  @DisplayName("with the overlay, the ratings travel and the very first note is the warning")
  void warnsFirstWhenAffinityIsIncluded() throws IOException {
    AffinityStore ratings =
        new AffinityStore() {
          @Override
          public void put(AffinityRecord affinity) {}

          @Override
          public Optional<AffinityRecord> find(String qid) {
            return WREN.equals(qid)
                ? Optional.of(
                    new AffinityRecord(qid, 5, null, Instant.parse("2026-01-01T00:00:00Z")))
                : Optional.empty();
          }

          @Override
          public void close() {}
        };

    GraphView view = run(fullTo(out("all.graphml"), true), new AffinityOverlay(ratings));

    assertThat(view.carriesAffinity()).isTrue();
    assertThat(notes.get(0)).isEqualTo(AffinityOverlay.PERSONAL_DATA_WARNING);
    assertThat(fileExistedWhenNoted.get(0)).isFalse();
  }

  private int indexOfNoteContaining(String fragment) {
    for (int i = 0; i < notes.size(); i++) {
      if (notes.get(i).contains(fragment)) {
        return i;
      }
    }
    return -1;
  }
}
