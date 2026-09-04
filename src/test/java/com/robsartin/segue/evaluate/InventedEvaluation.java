package com.robsartin.segue.evaluate;

import com.robsartin.segue.domain.AssertionRecord;
import com.robsartin.segue.domain.EdgeTypes;
import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.domain.NodeRecord;
import com.robsartin.segue.domain.Provenance;
import com.robsartin.segue.tinker.TinkerGraphStore;
import java.time.Instant;
import java.util.List;

/**
 * An invented neighbourhood for the harness: two of "your" acts, three intermediates, and three
 * candidates — one you would be recommended, one you rated down, one held out.
 *
 * <p>Every id carries ADR 58's leading zero, so no future Wikidata allocation can give it a
 * referent, and nothing here comes from anybody's graph or anybody's list (ADR 33, issue #37).
 */
final class InventedEvaluation {

  static final String KNOWN_ONE = "Q0900411";
  static final String KNOWN_TWO = "Q0900412";

  static final String VIA_ONE = "Q0900421";
  static final String VIA_TWO = "Q0900422";
  static final String VIA_THREE = "Q0900423";

  /** Never rated: the ordinary candidate. */
  static final String STRANGER = "Q0900431";

  /** Rated down, so a shipped run suppresses it. */
  static final String REJECTED = "Q0900432";

  /** Rated highly and absent from the file, so the split can hide it. */
  static final String HIDDEN = "Q0900433";

  private static final Instant WHEN = Instant.parse("2026-01-01T00:00:00Z");

  private InventedEvaluation() {}

  static Provenance sourced() {
    return new Provenance("invented", "invented:1", WHEN, 1.0);
  }

  static void node(TinkerGraphStore graph, String qid, NodeKind kind) {
    graph.upsertNode(new NodeRecord(qid, kind, "an invented act " + qid, List.of()));
  }

  static void edge(TinkerGraphStore graph, String from, String to) {
    graph.record(
        new AssertionRecord(from, to, EdgeTypes.INFLUENCED_BY.code(), null, null, sourced()));
  }

  /** Pad a node out to {@code degree} edges with works, which are never candidates. */
  static void padDegreeTo(TinkerGraphStore graph, String qid, int degree) {
    int already = graph.edges(qid).size();
    for (int i = already; i < degree; i++) {
      String filler = "Q090049" + Math.abs((qid + i).hashCode() % 100000);
      node(graph, filler, NodeKind.WORK);
      graph.record(
          new AssertionRecord(qid, filler, EdgeTypes.PERFORMED.code(), null, null, sourced()));
    }
  }

  /**
   * Two of yours, three intermediates, three candidates — each candidate reached by both of yours
   * through a different intermediate, so their relative order is decided by degree alone.
   */
  static TinkerGraphStore graph() {
    TinkerGraphStore graph = new TinkerGraphStore();
    for (String qid : List.of(KNOWN_ONE, KNOWN_TWO, VIA_ONE, VIA_TWO, VIA_THREE)) {
      node(graph, qid, NodeKind.GROUP);
    }
    for (String qid : List.of(STRANGER, REJECTED, HIDDEN)) {
      node(graph, qid, NodeKind.GROUP);
    }
    for (String via : List.of(VIA_ONE, VIA_TWO, VIA_THREE)) {
      edge(graph, KNOWN_ONE, via);
      edge(graph, KNOWN_TWO, via);
    }
    edge(graph, STRANGER, VIA_ONE);
    edge(graph, REJECTED, VIA_TWO);
    edge(graph, HIDDEN, VIA_THREE);
    // Distinct degrees, so lift orders them REJECTED, STRANGER, HIDDEN and the order is stable.
    padDegreeTo(graph, REJECTED, 5);
    padDegreeTo(graph, STRANGER, 6);
    padDegreeTo(graph, HIDDEN, 7);
    return graph;
  }
}
