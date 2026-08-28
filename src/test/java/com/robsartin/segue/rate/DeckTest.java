package com.robsartin.segue.rate;

import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.segue.domain.EdgeRecord;
import com.robsartin.segue.domain.Hop;
import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.domain.NodeRecord;
import com.robsartin.segue.domain.PathResult;
import com.robsartin.segue.domain.Provenance;
import com.robsartin.segue.domain.Recommendation;
import com.robsartin.segue.recommend.Explained;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DeckTest {

  private static final Map<String, NodeRecord> NODES =
      Map.of(
          "Q900001", new NodeRecord("Q900001", NodeKind.GROUP, "Low Degree", List.of("Q900901")),
          "Q900002", new NodeRecord("Q900002", NodeKind.GROUP, "High Degree", List.of("Q900901")),
          "Q900003", new NodeRecord("Q900003", NodeKind.PERSON, "Mid Degree", List.of("Q900902")),
          "Q900004", new NodeRecord("Q900004", NodeKind.WORK, "Already Rated", List.of()));

  private static final Map<String, Integer> DEGREES =
      Map.of("Q900001", 3, "Q900002", 90, "Q900003", 20, "Q900004", 50);

  private static List<Card> deal(
      List<String> known, Map<String, Integer> ratings, List<Explained> cands) {
    return Deck.deal(
        known,
        q -> DEGREES.getOrDefault(q, 0),
        q -> Optional.ofNullable(NODES.get(q)),
        ratings,
        cands,
        OptionalInt.empty());
  }

  @Test
  @DisplayName("known entities are dealt by in-graph degree, highest first")
  void ordersKnownByDegreeDescending() {
    List<Card> cards = deal(List.of("Q900001", "Q900002", "Q900003"), Map.of(), List.of());

    assertThat(cards).extracting(Card::qid).containsExactly("Q900002", "Q900003", "Q900001");
    assertThat(cards.get(0).degree()).hasValue(90);
  }

  @Test
  @DisplayName("an entity that is already rated is never dealt")
  void excludesAlreadyRated() {
    List<Card> cards =
        deal(List.of("Q900001", "Q900004", "Q900002"), Map.of("Q900004", 4), List.of());

    assertThat(cards).extracting(Card::qid).doesNotContain("Q900004");
    assertThat(cards).hasSize(2);
  }

  @Test
  @DisplayName("an entity on the list but absent from the graph is skipped, not dealt blank")
  void skipsEntitiesMissingFromTheGraph() {
    List<Card> cards = deal(List.of("Q900002", "Q900999"), Map.of(), List.of());

    assertThat(cards).extracting(Card::qid).containsExactly("Q900002");
  }

  @Test
  @DisplayName("a known card carries a degree and no routes; the reverse for a candidate")
  void knownAndCandidateCardsDifferInShape() {
    Card known = Card.known(NODES.get("Q900002"), 90);
    Card candidate = Card.candidate(NODES.get("Q900003"), List.of("a -[X]-> b"));

    assertThat(known.degree()).hasValue(90);
    assertThat(known.routes()).isEmpty();
    assertThat(candidate.degree()).isEmpty();
    assertThat(candidate.routes()).containsExactly("a -[X]-> b");
  }

  @Test
  @DisplayName("a candidate is dealt after every fifth known card, and leftovers are not dropped")
  void interleavesCandidatesEveryFifthCard() {
    List<String> known = List.of("Q900001", "Q900002", "Q900003", "Q900005", "Q900006");
    Map<String, NodeRecord> extra =
        Map.of(
            "Q900005", new NodeRecord("Q900005", NodeKind.GROUP, "Five", List.of()),
            "Q900006", new NodeRecord("Q900006", NodeKind.GROUP, "Six", List.of()));
    Explained one = candidateFor("Q900101", "Candidate One");
    Explained two = candidateFor("Q900102", "Candidate Two");

    List<Card> cards =
        Deck.deal(
            known,
            q -> DEGREES.getOrDefault(q, 1),
            q -> Optional.ofNullable(NODES.containsKey(q) ? NODES.get(q) : extra.get(q)),
            Map.of(),
            List.of(one, two),
            OptionalInt.empty());

    assertThat(cards.get(4).qid()).isEqualTo("Q900101");
    assertThat(cards).extracting(Card::qid).contains("Q900102");
    assertThat(cards).hasSize(7);
  }

  @Test
  @DisplayName("a candidate that is already rated is not dealt")
  void excludesAlreadyRatedCandidate() {
    Explained candidate = candidateFor("Q900103", "Already Rated Candidate");

    List<Card> cards = deal(List.of(), Map.of("Q900103", 2), List.of(candidate));

    assertThat(cards).isEmpty();
  }

  @Test
  @DisplayName(
      "a candidate's routes reach the dealt card intact, not just Card.candidate's own constructor")
  void candidateRoutesSurviveDealing() {
    NodeRecord knownEnd = new NodeRecord("Q900501", NodeKind.PERSON, "Route Known", List.of());
    NodeRecord candidateEnd =
        new NodeRecord("Q900502", NodeKind.GROUP, "Route Candidate", List.of());
    EdgeRecord edge =
        new EdgeRecord(
            knownEnd.qid(),
            candidateEnd.qid(),
            "INFLUENCED_BY",
            null,
            null,
            List.of(new Provenance("invented", "invented:1", Instant.EPOCH, 1.0)));
    PathResult route = new PathResult(List.of(new Hop(knownEnd, edge, candidateEnd, false)));
    Explained explained =
        new Explained(new Recommendation(candidateEnd, 1.0, 12, List.of()), List.of(route));

    List<Card> cards =
        Deck.deal(
            List.of(),
            q -> 0,
            q -> Optional.empty(),
            Map.of(),
            List.of(explained),
            OptionalInt.empty());

    // Pinned to the exact readable form PathResult.render() produces for this fixture — not
    // merely "some non-empty string arrived". A card exists to answer "why am I being shown
    // this"; the reader-facing text is the whole point, so the assertion has to be on that text,
    // not on a superset check that a raw Object::toString() dump would also satisfy.
    assertThat(cards).hasSize(1);
    assertThat(cards.get(0).routes())
        .containsExactly(
            "      Route Known -[INFLUENCED_BY]-> Route Candidate [invented invented:1]\n");
  }

  @Test
  @DisplayName("revise mode deals only the entities at that rating, and nothing else")
  void reviseDealsOnlyThatRating() {
    List<Card> cards =
        Deck.deal(
            List.of("Q900001", "Q900002", "Q900003"),
            q -> DEGREES.getOrDefault(q, 0),
            q -> Optional.ofNullable(NODES.get(q)),
            Map.of("Q900001", 3, "Q900002", 5, "Q900003", 3),
            List.of(),
            OptionalInt.of(3));

    assertThat(cards).extracting(Card::qid).containsExactlyInAnyOrder("Q900001", "Q900003");
  }

  @Test
  @DisplayName("a revise card carries the rating it currently has, so it is not re-rated blind")
  void reviseCardShowsTheCurrentRating() {
    List<Card> cards =
        Deck.deal(
            List.of("Q900002"),
            q -> DEGREES.getOrDefault(q, 0),
            q -> Optional.ofNullable(NODES.get(q)),
            Map.of("Q900002", 5),
            List.of(),
            OptionalInt.of(5));

    assertThat(cards).hasSize(1);
    assertThat(cards.get(0).currentRating()).hasValue(5);
  }

  @Test
  @DisplayName("revise mode deals no candidates, because a candidate has no rating to revise")
  void reviseDealsNoCandidates() {
    List<Card> cards =
        Deck.deal(
            List.of("Q900001"),
            q -> DEGREES.getOrDefault(q, 0),
            q -> Optional.ofNullable(NODES.get(q)),
            Map.of("Q900001", 3),
            List.of(candidateFor("Q900101", "Candidate One")),
            OptionalInt.of(3));

    assertThat(cards).extracting(Card::qid).containsExactly("Q900001");
  }

  @Test
  @DisplayName(
      "without revise, the deck still deals only unrated entities and no card shows a rating")
  void defaultModeIsUnchanged() {
    List<Card> cards =
        Deck.deal(
            List.of("Q900001", "Q900002"),
            q -> DEGREES.getOrDefault(q, 0),
            q -> Optional.ofNullable(NODES.get(q)),
            Map.of("Q900002", 4),
            List.of(),
            OptionalInt.empty());

    assertThat(cards).extracting(Card::qid).containsExactly("Q900001");
    assertThat(cards.get(0).currentRating()).isEmpty();
  }

  private static Explained candidateFor(String qid, String label) {
    NodeRecord node = new NodeRecord(qid, NodeKind.GROUP, label, List.of());
    return new Explained(new Recommendation(node, 1.0, 12, List.of()), List.of());
  }
}
