package com.robsartin.segue.rate;

import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.segue.domain.AssertionRecord;
import com.robsartin.segue.domain.EdgeTypes;
import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.domain.NodeRecord;
import com.robsartin.segue.domain.Provenance;
import com.robsartin.segue.tinker.TinkerGraphStore;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Notes carry counts, and nothing else (ADR 33).
 *
 * <p><b>Every assertion below that checks for "no rating in a note" checks by looking for one of
 * this fixture's own qids</b>, never by matching a rating-shaped regex. A rating never appears
 * paired with anything else in a note either — {@code RateRun} never formats one at all, every note
 * being a {@code .size()} count — so a regex describing a rating's shape can never see a violation
 * regardless of which code path ran; a qid is the one piece of identifying data that really could
 * leak into a note by accident, and checking for it is a guard that can fail.
 */
class RateRunTest {

  private static final String KNOWN_ONE = "Q900001";
  private static final String KNOWN_TWO = "Q900002";
  private static final String SHARED_ARTIST = "Q900003";
  private static final String ANCESTOR = "Q900004";

  private static final Instant WHEN = Instant.parse("2026-01-01T00:00:00Z");

  /** The recommender's own floor ({@code RateRun.MIN_CANDIDATE_DEGREE}), duplicated to pad to. */
  private static final int MIN_CANDIDATE_DEGREE = 12;

  @Test
  @DisplayName("the deck is built from the graph, and the notes carry counts and no rating")
  void buildsADeckAndSaysWhatItDid() throws Exception {
    try (TinkerGraphStore graph = new TinkerGraphStore()) {
      graph.upsertNode(new NodeRecord(KNOWN_ONE, NodeKind.GROUP, "One", List.of()));
      graph.upsertNode(new NodeRecord(KNOWN_TWO, NodeKind.GROUP, "Two", List.of()));
      List<String> notes = new ArrayList<>();

      List<Card> deck =
          RateRun.buildDeck(graph, List.of(KNOWN_ONE, KNOWN_TWO), Set.of(KNOWN_TWO), 0, notes::add);

      assertThat(deck).extracting(Card::qid).containsExactly(KNOWN_ONE);
      assertThat(notes).anyMatch(n -> n.contains("1 card(s)"));
      assertThat(notes).noneMatch(n -> n.contains(KNOWN_ONE) || n.contains(KNOWN_TWO));
    }
  }

  @Test
  @DisplayName("the candidate sweep runs too, and its notes still name no entity")
  void theCandidateSweepNotesNameNoEntity() throws Exception {
    try (TinkerGraphStore graph = new TinkerGraphStore()) {
      node(graph, KNOWN_ONE, NodeKind.GROUP, "one you know");
      node(graph, SHARED_ARTIST, NodeKind.PERSON, "the artist you cite");
      node(graph, ANCESTOR, NodeKind.GROUP, "who that artist cites");
      edge(graph, KNOWN_ONE, SHARED_ARTIST, EdgeTypes.INFLUENCED_BY.code());
      edge(graph, SHARED_ARTIST, ANCESTOR, EdgeTypes.INFLUENCED_BY.code());
      padDegreeTo(graph, ANCESTOR, MIN_CANDIDATE_DEGREE);
      List<String> notes = new ArrayList<>();

      List<Card> deck = RateRun.buildDeck(graph, List.of(KNOWN_ONE), Set.of(), 10, notes::add);

      // The candidate branch actually ran and actually found something, or this test would pass
      // for the wrong reason — the same emptiness that made the vacuous regex pass before.
      assertThat(deck).extracting(Card::qid).contains(ANCESTOR);
      assertThat(notes).anyMatch(n -> n.contains("candidate(s) mixed in"));
      assertThat(notes)
          .noneMatch(
              n -> n.contains(KNOWN_ONE) || n.contains(SHARED_ARTIST) || n.contains(ANCESTOR));
    }
  }

  private static void node(TinkerGraphStore graph, String qid, NodeKind kind, String label) {
    graph.upsertNode(new NodeRecord(qid, kind, label, List.of()));
  }

  private static void edge(TinkerGraphStore graph, String from, String to, String type) {
    graph.record(
        new AssertionRecord(
            from, to, type, null, null, new Provenance("invented", null, WHEN, 1.0)));
  }

  /**
   * Filler neighbours off in a separate QID range, so a node reaches a degree the rules care about.
   */
  private static void padDegreeTo(TinkerGraphStore graph, String qid, int degree) {
    int already = graph.edges(qid).size();
    for (int i = already; i < degree; i++) {
      String filler = "Q9009" + i;
      node(graph, filler, NodeKind.WORK, "filler " + filler);
      edge(graph, qid, filler, EdgeTypes.INFLUENCED_BY.code());
    }
  }
}
