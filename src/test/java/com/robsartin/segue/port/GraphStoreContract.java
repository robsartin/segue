package com.robsartin.segue.port;

import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.segue.domain.EdgeRecord;
import com.robsartin.segue.domain.PathResult;
import com.robsartin.segue.fixture.Fixture;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Every GraphStore must satisfy this, whichever engine backs it.
 *
 * <p>This was the BakeOff program in slice 0. Making it a contract test is the point: the
 * cross-engine comparison stops being something you remember to run and becomes something CI
 * refuses to merge without. See docs/adr/0018-graph-engine-gremlin.md, which commits to keeping the
 * Jena reference implementation working.
 *
 * <p>Note the assertions compare full result SETS. Comparing only the shortest path is exactly what
 * let the multigraph bug pass in slice 0: the RDF adapter's neighbour query walked nodes rather
 * than edges, so {@code SELECT DISTINCT ?other} collapsed the two different relationships between
 * Cave and The Proposition into one, and a whole route silently disappeared. Gremlin's {@code
 * bothE().otherV()} cannot have that bug.
 */
public abstract class GraphStoreContract {

  private GraphStore store;

  /** Supply a fresh, empty store. Called before each test. */
  protected abstract GraphStore createStore();

  @BeforeEach
  void seed() {
    store = createStore();
    Fixture.seed(store);
  }

  @AfterEach
  void tearDown() {
    if (store != null) {
      store.close();
    }
  }

  @Test
  @DisplayName("25 assertions fold into exactly 22 edges")
  void ingestCollapsesAssertions() {
    // 25 assertions, 3 pairs sharing a (from, type, to) triple (corroboration()), fold to 22.
    assertThat(store.edgeCount()).isEqualTo(22);
  }

  @Test
  @DisplayName("every seeded node is retrievable")
  void nodesAreRetrievable() {
    assertThat(store.node(Fixture.CAVE)).isPresent();
    assertThat(store.node(Fixture.CAVE).orElseThrow().label()).isEqualTo("Nick Cave");
    assertThat(store.node("Q999999")).isEmpty();
  }

  @Test
  @DisplayName("Q1: Cave reaches Hillcoat in two hops, through a film")
  void pathsCrossFromMusicIntoFilm() {
    List<PathResult> paths = store.shortestPaths(Fixture.CAVE, Fixture.HILLCOAT, 4, 50);

    assertThat(paths).isNotEmpty();
    assertThat(paths.get(0).length()).isEqualTo(2);
  }

  @Test
  @DisplayName("Q1: the multigraph survives — three distinct two-hop routes")
  void multigraphProducesThreeTwoHopRoutes() {
    List<PathResult> paths = store.shortestPaths(Fixture.CAVE, Fixture.HILLCOAT, 4, 50);

    assertThat(paths.stream().filter(p -> p.length() == 2)).hasSize(3);
  }

  @Test
  @DisplayName("Q1b: the shortest Cave-McCarthy route is the model's unverified guess")
  void shortestIsNotMostTrustworthy() {
    List<PathResult> paths = store.shortestPaths(Fixture.CAVE, Fixture.MCCARTHY, 4, 5);

    PathResult shortest =
        paths.stream().min(Comparator.comparingInt(PathResult::length)).orElseThrow();
    PathResult longest =
        paths.stream().max(Comparator.comparingInt(PathResult::length)).orElseThrow();

    // This is the bug ADR 23 records; ADR 31 is the decision that fixes it in increment 1:
    // length and trust disagree.
    assertThat(shortest.length()).isEqualTo(1);
    assertThat(shortest.weakestConfidence()).isLessThanOrEqualTo(0.30);
    assertThat(longest.weakestConfidence()).isGreaterThan(shortest.weakestConfidence());
  }

  @Test
  @DisplayName("Q2: last.fm contributed exactly one edge after 15 August")
  void auditBySourceAndTime() {
    List<EdgeRecord> edges = store.assertedBy("lastfm", Instant.parse("2026-08-15T00:00:00Z"));

    assertThat(edges).hasSize(1);
  }

  @Test
  @DisplayName("Q2: every model-asserted edge is still an uncorroborated hypothesis")
  void modelAssertionsRemainHypotheses() {
    List<EdgeRecord> edges = store.assertedBy("llm:claude", Instant.EPOCH);

    assertThat(edges).isNotEmpty();
    assertThat(edges).allMatch(EdgeRecord::isUncorroboratedHypothesis);
  }

  @Test
  @DisplayName("Q3: the Bad Seeds lineup in June 1984 has three members")
  void timeTravelTo1984() {
    List<EdgeRecord> lineup = store.validAt(Fixture.BAD_SEEDS, LocalDate.of(1984, 6, 1));

    assertThat(lineup).hasSize(3);
    assertThat(lineup).noneMatch(e -> e.fromQid().equals(Fixture.ELLIS));
    assertThat(lineup).anyMatch(e -> e.fromQid().equals(Fixture.BLIXA));
  }

  @Test
  @DisplayName("Q3: Mick Harvey has dropped out of the 2010 lineup")
  void timeTravelTo2010() {
    List<EdgeRecord> lineup = store.validAt(Fixture.BAD_SEEDS, LocalDate.of(2010, 6, 1));

    assertThat(lineup).isNotEmpty();
    assertThat(lineup).anyMatch(e -> e.fromQid().equals(Fixture.ELLIS));
    assertThat(lineup).noneMatch(e -> e.fromQid().equals(Fixture.HARVEY_MICK));
  }

  @Test
  @DisplayName("Q4: three edges have two independent sources, none of them model-only")
  void corroboration() {
    List<EdgeRecord> corroborated = store.corroborated(2);

    assertThat(corroborated).hasSize(3);
    assertThat(corroborated).noneMatch(EdgeRecord::isUncorroboratedHypothesis);
  }

  /**
   * Canonical rendering of a route set, so two engines can be compared exactly. Used by the
   * cross-engine agreement test, which lives in the Tinker subclass because it needs both stores at
   * once.
   */
  public static List<String> signatures(List<PathResult> paths) {
    return paths.stream()
        .map(
            p ->
                p.hops().stream()
                    .map(
                        h ->
                            h.from().qid()
                                + (h.traversedBackwards() ? "<-" : "-")
                                + h.edge().typeCode()
                                + (h.traversedBackwards() ? "-" : "->")
                                + h.to().qid())
                    .collect(Collectors.joining(" | ")))
        .sorted()
        .toList();
  }
}
