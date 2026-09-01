package com.robsartin.segue.export;

import static com.robsartin.segue.export.InventedGraph.HOLLOW_TIDE;
import static com.robsartin.segue.export.InventedGraph.KETTLES;
import static com.robsartin.segue.export.InventedGraph.MARLOW;
import static com.robsartin.segue.export.InventedGraph.PRIZE;
import static com.robsartin.segue.export.InventedGraph.WREN;
import static com.robsartin.segue.export.InventedGraph.edge;
import static com.robsartin.segue.export.InventedGraph.guessed;
import static com.robsartin.segue.export.InventedGraph.node;
import static com.robsartin.segue.export.InventedGraph.secondSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.export.InventedGraph.FakeAssertionLog;
import com.robsartin.segue.ingest.GraphProjector;
import com.robsartin.segue.port.IdentityMerge;
import com.robsartin.segue.tinker.TinkerGraphStore;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The selection layer, exercised against the real engine and an in-memory log. Every entity is
 * invented (ADR 40, issue #37).
 *
 * <p>Nothing in this class mentions DOT or GraphML, and that is the acceptance criterion issue #50
 * cares about most: a view is a set of nodes and edges, and the format is somebody else's problem.
 */
class ViewSelectorTest {

  private TinkerGraphStore graph;
  private ViewSelector selector;

  /**
   * The fixture is built by replaying a log through {@link GraphProjector} — the same path the
   * application takes at boot — so the graph and the log the selector reads cannot disagree.
   */
  @BeforeEach
  void buildAnInventedGraph() {
    FakeAssertionLog log =
        new FakeAssertionLog()
            .with(
                node(WREN, NodeKind.PERSON, "Wren Alderman"),
                node(KETTLES, NodeKind.GROUP, "The Paper Kettles"),
                node(MARLOW, NodeKind.PERSON, "Ida Marlow"),
                node(HOLLOW_TIDE, NodeKind.WORK, "Hollow Tide"),
                node(PRIZE, NodeKind.CONCEPT, "The Invented Prize"),
                edge(WREN, KETTLES, "MEMBER_OF"),
                edge(MARLOW, KETTLES, "MEMBER_OF"),
                edge(WREN, HOLLOW_TIDE, "AUTHORED"),
                edge(WREN, MARLOW, "INFLUENCED_BY", guessed()),
                edge(MARLOW, PRIZE, "RECEIVED_AWARD", secondSource()));
    graph = new TinkerGraphStore();
    GraphProjector.project(log, graph, IdentityMerge.NONE);
    selector = new ViewSelector(graph, log);
  }

  @AfterEach
  void closeTheGraph() {
    graph.close();
  }

  private static List<String> qids(GraphView view) {
    return view.nodes().stream().map(ViewNode::qid).toList();
  }

  private static List<String> types(GraphView view) {
    return view.edges().stream().map(ViewEdge::typeCode).toList();
  }

  // ---- route ------------------------------------------------------------

  @Test
  @DisplayName("route is one find_paths result, hop by hop")
  void selectsOneRoute() {
    GraphView view = selector.route(WREN, MARLOW, 3);

    assertThat(qids(view)).containsExactly(WREN, KETTLES, MARLOW);
    assertThat(view.edges()).hasSize(2);
  }

  @Test
  @DisplayName("the route is the one find_paths would return: PathRanking demotes the model guess")
  void ranksTheRouteTheSameWayFindPathsDoes() {
    GraphView view = selector.route(WREN, MARLOW, 3);

    assertThat(types(view)).doesNotContain("INFLUENCED_BY");
    assertThat(view.edges()).allSatisfy(e -> assertThat(e.confidence()).isEqualTo(1.0));
  }

  @Test
  @DisplayName("and it demotes a route through an academy too, so a picture cannot disagree")
  void demotesARouteThroughABodyOneIsElectedTo() {
    // Issue #66. The ranking is shared, but the two call sites that supply it with the graph's
    // shape are not, so this is the exporter's half of the same wiring. Its own graph, because
    // adding a sixth node to the shared fixture would move every count in this file.
    //
    // The class QIDs are real and the entities are invented: Q955824 is "learned society",
    // Q5741069 is "rock band". The academy hop is the BETTER evidenced of the two, which is
    // what made it win before.
    FakeAssertionLog log =
        new FakeAssertionLog()
            .with(
                node(WREN, NodeKind.PERSON, "Wren Alderman"),
                node(MARLOW, NodeKind.PERSON, "Ida Marlow"),
                node(KETTLES, NodeKind.GROUP, "The Paper Kettles", List.of("Q5741069")),
                node(PRIZE, NodeKind.GROUP, "Northwood Academy", List.of("Q955824")),
                edge(WREN, PRIZE, "MEMBER_OF"),
                edge(MARLOW, PRIZE, "MEMBER_OF"),
                edge(WREN, KETTLES, "MEMBER_OF", secondSource()),
                edge(MARLOW, KETTLES, "MEMBER_OF", secondSource()));
    try (TinkerGraphStore ownGraph = new TinkerGraphStore()) {
      GraphProjector.project(log, ownGraph, IdentityMerge.NONE);

      GraphView view = new ViewSelector(ownGraph, log).route(WREN, MARLOW, 2);

      assertThat(qids(view)).containsExactly(WREN, KETTLES, MARLOW);
    }
  }

  @Test
  @DisplayName("no route within the hop bound is an empty view that says so, not a failure")
  void reportsNoRoute() {
    GraphView view = selector.route(HOLLOW_TIDE, PRIZE, 1);

    assertThat(view.isEmpty()).isTrue();
    assertThat(view.description()).contains("no route");
  }

  @Test
  @DisplayName("an unknown endpoint is refused by name rather than returning nothing")
  void refusesAnUnknownRouteEndpoint() {
    assertThatThrownBy(() -> selector.route(WREN, "Q900999", 3))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Q900999");
  }

  // ---- neighbourhood ----------------------------------------------------

  @Test
  @DisplayName("neighbourhood at depth 1 is one entity and its own edges")
  void selectsADepthOneNeighbourhood() {
    GraphView view = selector.neighbourhood(KETTLES, 1);

    assertThat(qids(view)).containsExactlyInAnyOrder(KETTLES, WREN, MARLOW);
    assertThat(types(view)).containsExactly("MEMBER_OF", "MEMBER_OF");
  }

  @Test
  @DisplayName("depth 2 reaches the neighbours' neighbours")
  void selectsADepthTwoNeighbourhood() {
    GraphView view = selector.neighbourhood(KETTLES, 2);

    assertThat(qids(view)).containsExactlyInAnyOrder(KETTLES, WREN, MARLOW, HOLLOW_TIDE, PRIZE);
    assertThat(view.edges()).hasSize(5);
  }

  @Test
  @DisplayName("an edge reachable from both ends appears once, not twice")
  void deduplicatesEdges() {
    GraphView view = selector.neighbourhood(WREN, 2);

    assertThat(view.edges().stream().map(e -> e.fromQid() + e.typeCode() + e.toQid()).distinct())
        .hasSize(view.edges().size());
  }

  @Test
  @DisplayName("an unknown centre is refused by name")
  void refusesAnUnknownCentre() {
    assertThatThrownBy(() -> selector.neighbourhood("Q900999", 1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Q900999");
  }

  // ---- subgraph ---------------------------------------------------------

  @Test
  @DisplayName("subgraph keeps only the listed entities and the edges between them")
  void selectsASubgraph() {
    GraphView view = selector.subgraph(List.of(WREN, MARLOW, PRIZE));

    assertThat(qids(view)).containsExactly(WREN, MARLOW, PRIZE);
    assertThat(types(view)).containsExactlyInAnyOrder("INFLUENCED_BY", "RECEIVED_AWARD");
  }

  @Test
  @DisplayName("the discovered intermediate is stripped: an edge to an unlisted entity is dropped")
  void stripsIntermediates() {
    GraphView view = selector.subgraph(List.of(WREN, MARLOW));

    assertThat(types(view)).containsExactly("INFLUENCED_BY");
  }

  @Test
  @DisplayName("a listed entity the graph has never heard of is reported, not invented")
  void reportsUnknownRequests() {
    GraphView view = selector.subgraph(List.of(WREN, "Q900999"));

    assertThat(qids(view)).containsExactly(WREN);
    assertThat(view.description()).contains("2").contains("1");
  }

  // ---- full -------------------------------------------------------------

  @Test
  @DisplayName("full is everything the log holds")
  void selectsEverything() {
    GraphView view = selector.full();

    assertThat(view.nodes()).hasSize(5);
    assertThat(view.edges()).hasSize(5);
  }

  // ---- shared shape -----------------------------------------------------

  @Test
  @DisplayName("edge attributes travel: best confidence and every distinct source")
  void carriesEdgeProvenance() {
    GraphView view = selector.subgraph(List.of(MARLOW, PRIZE));

    ViewEdge award = view.edges().get(0);
    assertThat(award.confidence()).isEqualTo(0.8);
    assertThat(award.sourceId()).isEqualTo("also-invented");
  }

  @Test
  @DisplayName("node attributes travel: the classes the claim recorded reach the view")
  void carriesTheClassesAClaimRecorded() {
    FakeAssertionLog log =
        new FakeAssertionLog()
            .with(
                node(HOLLOW_TIDE, NodeKind.WORK, "Hollow Tide", List.of("Q482994", "Q105543609")));
    try (TinkerGraphStore classified = new TinkerGraphStore()) {
      GraphProjector.project(log, classified, IdentityMerge.NONE);

      GraphView view = new ViewSelector(classified, log).neighbourhood(HOLLOW_TIDE, 1);

      assertThat(view.nodes().get(0).instanceOf()).containsExactly("Q482994", "Q105543609");
    }
  }

  @Test
  @DisplayName("no view carries affinity of its own accord — that takes AffinityOverlay and a flag")
  void neverSelectsAffinity() {
    assertThat(selector.full().carriesAffinity()).isFalse();
    assertThat(selector.neighbourhood(KETTLES, 2).carriesAffinity()).isFalse();
    assertThat(selector.route(WREN, MARLOW, 3).carriesAffinity()).isFalse();
    assertThat(selector.subgraph(List.of(WREN, MARLOW)).carriesAffinity()).isFalse();
  }
}
