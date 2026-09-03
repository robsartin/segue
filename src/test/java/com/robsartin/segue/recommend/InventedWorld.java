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
 * <p><b>The QIDs are invented in the sense that nobody looked them up — not in the sense that they
 * denote nothing.</b> Most of the ids below resolve to real Wikidata entities. They come from a
 * {@code Q900xxx} range once described as a placeholder range used everywhere; it was never free,
 * and {@code fixture.Fixture} has since moved to ids Wikibase's item-id grammar refuses (ADR 58).
 * This file and {@code export.InventedGraph} have not, because the family is shared across many
 * unrelated test files — see <a href="https://github.com/robsartin/segue/issues/171">issue
 * #171</a>. No assertion here depends on what any id denotes.
 *
 * <p><b>Nothing in this file comes from anybody's graph or anybody's list.</b> ADR 40 and issue #37
 * are explicit that this repository is public and that the real data lives outside it, and a
 * recommender's fixture is the one most tempting to copy from a real run.
 */
final class InventedWorld {

  // The things "you" already know.
  static final String KNOWN_ONE = "Q0900101";
  static final String KNOWN_TWO = "Q0900102";

  // Intermediates.
  static final String SHARED_ARTIST = "Q0900201";
  static final String SHARED_PRIZE = "Q0900202";
  static final String HALL_OF_FAME = "Q0900203";
  static final String THE_ACADEMY = "Q0900204";

  /** An intermediate that cites one of yours, rather than being cited by it (issue #84). */
  static final String THE_ADMIRER = "Q0900205";

  // Candidates.
  static final String ANCESTOR = "Q0900301";
  static final String ANOTHER_ANCESTOR = "Q0900307";
  static final String FELLOW_PRIZEWINNER = "Q0900302";
  static final String ALSO_IN_THE_HALL = "Q0900303";
  static final String ALSO_IN_THE_ACADEMY = "Q0900304";
  static final String A_RECORD = "Q0900305";
  static final String A_THIN_BAND = "Q0900306";

  /** One edge to its name: what expansion adds, and what the floor holds out (issue #134). */
  static final String JUST_DISCOVERED = "Q0900308";

  /** The invented Wikidata class that means "elected, not collaborating" (issue #66). */
  static final String ELECTED_TO = "Q0900801";

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
   * <p>Filler nodes are {@code WORK}s off in {@code Q09009xx}, and they are never candidates: the
   * kind rules them out, which keeps them out of every assertion in these tests.
   */
  static void padDegreeTo(TinkerGraphStore graph, String qid, int degree) {
    int already = graph.edges(qid).size();
    for (int i = already; i < degree; i++) {
      String filler = fillerQid(qid, i);
      node(graph, filler, NodeKind.WORK, "filler " + filler);
      edge(graph, qid, filler, EdgeTypes.PERFORMED.code());
    }
  }

  /**
   * The filler id one padding round mints, split out so {@link #padDegreeTo} and any caller that
   * needs the same shape but a different provenance - {@code CandidateSweepTest}'s owner-sourced
   * padding, for one - share a single formula rather than each spelling it out (issue #171: the
   * {@code Q09009xx} range migrated here as one expression, not one per call site).
   */
  static String fillerQid(String qid, int index) {
    return "Q09009" + (Math.abs(qid.hashCode()) % 90 + 10) + index;
  }

  /** A busy CONCEPT, which is what issue #52 calls a hub. */
  static void hubConcept(TinkerGraphStore graph, String qid, String label) {
    node(graph, qid, NodeKind.CONCEPT, label);
    padDegreeTo(graph, qid, PathRanking.HUB_DEGREE + 2);
  }
}
