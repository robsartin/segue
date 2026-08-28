package com.robsartin.segue.recommend;

import com.robsartin.segue.domain.AssertionRecord;
import com.robsartin.segue.domain.EdgeTypes;
import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.domain.NodeRecord;
import com.robsartin.segue.domain.PathRanking;
import com.robsartin.segue.domain.Provenance;
import com.robsartin.segue.tinker.TinkerGraphStore;
import java.time.Instant;
import java.util.List;
import java.util.function.Predicate;

/**
 * Invented bands, invented people, invented QIDs — and an invented class that means "you were
 * elected to this".
 *
 * <p>{@code Q90xxxx} is the placeholder range this project uses everywhere (see {@code
 * fixture.Fixture} and {@code export.InventedGraph}), chosen so nothing here can be mistaken for a
 * real Wikidata identifier. <b>Nothing in this file comes from anybody's graph or anybody's
 * list.</b> ADR 40 and issue #37 are explicit that this repository is public and that the real data
 * lives outside it, and a recommender's fixture is the one most tempting to copy from a real run.
 */
final class InventedWorld {

  // The things "you" already know.
  static final String KNOWN_ONE = "Q900101";
  static final String KNOWN_TWO = "Q900102";

  // Intermediates.
  static final String SHARED_ARTIST = "Q900201";
  static final String SHARED_PRIZE = "Q900202";
  static final String HALL_OF_FAME = "Q900203";
  static final String THE_ACADEMY = "Q900204";

  /** An intermediate that cites one of yours, rather than being cited by it (issue #84). */
  static final String THE_ADMIRER = "Q900205";

  // Candidates.
  static final String ANCESTOR = "Q900301";
  static final String ANOTHER_ANCESTOR = "Q900307";
  static final String FELLOW_PRIZEWINNER = "Q900302";
  static final String ALSO_IN_THE_HALL = "Q900303";
  static final String ALSO_IN_THE_ACADEMY = "Q900304";
  static final String A_RECORD = "Q900305";
  static final String A_THIN_BAND = "Q900306";

  /** The invented Wikidata class that means "elected, not collaborating" (issue #66). */
  static final String ELECTED_TO = "Q900801";

  static final Predicate<String> INSTITUTIONS = ELECTED_TO::equals;

  private static final Instant WHEN = Instant.parse("2026-01-01T00:00:00Z");

  private InventedWorld() {}

  static Provenance sourced() {
    return new Provenance("invented", "invented:1", WHEN, 1.0);
  }

  static void node(TinkerGraphStore graph, String qid, NodeKind kind, String label) {
    graph.upsertNode(new NodeRecord(qid, kind, label, List.of()));
  }

  static void node(
      TinkerGraphStore graph, String qid, NodeKind kind, String label, String... classes) {
    graph.upsertNode(new NodeRecord(qid, kind, label, List.of(classes)));
  }

  static void edge(TinkerGraphStore graph, String from, String to, String type) {
    graph.record(new AssertionRecord(from, to, type, null, null, sourced()));
  }

  /**
   * Attach {@code extra} filler neighbours, so a node reaches a degree the rules care about.
   *
   * <p>Filler nodes are {@code WORK}s off in {@code Q9009xx}, and they are never candidates: the
   * kind rules them out, which keeps them out of every assertion in these tests.
   */
  static void padDegreeTo(TinkerGraphStore graph, String qid, int degree) {
    int already = graph.edges(qid).size();
    for (int i = already; i < degree; i++) {
      String filler = "Q9009" + (Math.abs(qid.hashCode()) % 90 + 10) + i;
      node(graph, filler, NodeKind.WORK, "filler " + filler);
      edge(graph, qid, filler, EdgeTypes.PERFORMED.code());
    }
  }

  /** A busy CONCEPT, which is what issue #52 calls a hub. */
  static void hubConcept(TinkerGraphStore graph, String qid, String label) {
    node(graph, qid, NodeKind.CONCEPT, label);
    padDegreeTo(graph, qid, PathRanking.HUB_DEGREE + 2);
  }
}
