package com.robsartin.segue.recommend;

import static com.robsartin.segue.recommend.InventedWorld.ANCESTOR;
import static com.robsartin.segue.recommend.InventedWorld.HALL_OF_FAME;
import static com.robsartin.segue.recommend.InventedWorld.INSTITUTIONS;
import static com.robsartin.segue.recommend.InventedWorld.KNOWN_ONE;
import static com.robsartin.segue.recommend.InventedWorld.KNOWN_TWO;
import static com.robsartin.segue.recommend.InventedWorld.SHARED_ARTIST;
import static com.robsartin.segue.recommend.InventedWorld.edge;
import static com.robsartin.segue.recommend.InventedWorld.hubConcept;
import static com.robsartin.segue.recommend.InventedWorld.node;
import static com.robsartin.segue.recommend.InventedWorld.padDegreeTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.segue.domain.EdgeTypes;
import com.robsartin.segue.domain.Hop;
import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.domain.PathResult;
import com.robsartin.segue.domain.Recommendation;
import com.robsartin.segue.domain.Recommendations;
import com.robsartin.segue.domain.Scorer;
import com.robsartin.segue.tinker.TinkerGraphStore;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The receipts: a score with no route is not a segue recommendation (ADR 45).
 *
 * <p>What these tests are really asserting is that the explanation comes from the SAME traversal
 * and the SAME ranking {@code find_paths} uses, rather than from a second idea of what a good route
 * is that happens to agree today.
 */
class RoutesTest {

  private static final int FLOOR = 4;

  private TinkerGraphStore graph;

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

  private Recommendation ancestor() {
    return new CandidateSweep(graph, INSTITUTIONS)
            .over(List.of(KNOWN_ONE, KNOWN_TWO), Scorer.LIFT, FLOOR, Recommendations.EQUAL_REGARD)
            .candidates()
            .stream()
            .filter(candidate -> candidate.entity().qid().equals(ANCESTOR))
            .findFirst()
            .orElseThrow();
  }

  @Test
  @DisplayName("every candidate arrives with a real route from something you know")
  void everyCandidateArrivesWithARoute() {
    List<PathResult> routes = new Routes(graph, INSTITUTIONS).bestFor(ancestor(), 2);

    assertThat(routes).hasSize(2);
    PathResult first = routes.get(0);
    assertThat(first.hops()).hasSize(2);
    assertThat(first.hops().get(0).from().qid()).isIn(KNOWN_ONE, KNOWN_TWO);
    assertThat(first.hops().get(1).to().qid()).isEqualTo(ANCESTOR);
  }

  @Test
  @DisplayName("every hop of the explanation cites its sources, exactly as find_paths does")
  void everyHopCitesItsSources() {
    PathResult route = new Routes(graph, INSTITUTIONS).bestFor(ancestor(), 1).get(0);

    for (Hop hop : route.hops()) {
      assertThat(hop.edge().sources()).isNotEmpty();
      assertThat(hop.describe()).contains("invented:1");
    }
  }

  @Test
  @DisplayName("the route shown is the one PathRanking picks, not the first one found")
  void theRouteShownIsTheRankedBest() {
    // A second route to the same candidate, through a hall of fame. It is perfectly true and it
    // explains nothing, and ADR 31 already knows that — so the explanation must not be it.
    hubConcept(graph, HALL_OF_FAME, "an invented hall of fame");
    edge(graph, KNOWN_ONE, HALL_OF_FAME, EdgeTypes.RECEIVED_AWARD.code());
    edge(graph, ANCESTOR, HALL_OF_FAME, EdgeTypes.RECEIVED_AWARD.code());

    PathResult route = new Routes(graph, INSTITUTIONS).bestFor(ancestor(), 1).get(0);

    assertThat(route.hops().get(0).to().qid()).isEqualTo(SHARED_ARTIST);
  }

  @Test
  @DisplayName("the entity that contributed most to the score explains it first")
  void theStrongestReacherExplainsItFirst() {
    // KNOWN_TWO is thought more of, so its route is the one to show first.
    Recommendation weighted =
        new CandidateSweep(graph, INSTITUTIONS)
                .over(
                    List.of(KNOWN_ONE, KNOWN_TWO),
                    Scorer.LIFT,
                    FLOOR,
                    qid -> KNOWN_TWO.equals(qid) ? 5.0 : 1.0)
                .candidates()
                .stream()
                .filter(candidate -> candidate.entity().qid().equals(ANCESTOR))
                .findFirst()
                .orElseThrow();

    List<PathResult> routes = new Routes(graph, INSTITUTIONS).bestFor(weighted, 2);

    assertThat(routes.get(0).hops().get(0).from().qid()).isEqualTo(KNOWN_TWO);
  }

  @Test
  @DisplayName("asking for one route gets one, however many things reach it")
  void theNumberOfRoutesIsBounded() {
    assertThat(new Routes(graph, INSTITUTIONS).bestFor(ancestor(), 1)).hasSize(1);
  }
}
