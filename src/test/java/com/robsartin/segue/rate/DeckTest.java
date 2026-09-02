package com.robsartin.segue.rate;

import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.segue.domain.EdgeRecord;
import com.robsartin.segue.domain.Hop;
import com.robsartin.segue.domain.KnownList;
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
          "Q0900001", new NodeRecord("Q0900001", NodeKind.GROUP, "Low Degree", List.of("Q900901")),
          "Q0900002", new NodeRecord("Q0900002", NodeKind.GROUP, "High Degree", List.of("Q900901")),
          "Q0900003", new NodeRecord("Q0900003", NodeKind.PERSON, "Mid Degree", List.of("Q900902")),
          "Q0900004", new NodeRecord("Q0900004", NodeKind.WORK, "Already Rated", List.of()));

  private static final Map<String, Integer> DEGREES =
      Map.of("Q0900001", 3, "Q0900002", 90, "Q0900003", 20, "Q0900004", 50);

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
    List<Card> cards = deal(List.of("Q0900001", "Q0900002", "Q0900003"), Map.of(), List.of());

    assertThat(cards).extracting(Card::qid).containsExactly("Q0900002", "Q0900003", "Q0900001");
    assertThat(cards.get(0).degree()).hasValue(90);
  }

  @Test
  @DisplayName("an entity that is already rated is never dealt")
  void excludesAlreadyRated() {
    List<Card> cards =
        deal(List.of("Q0900001", "Q0900004", "Q0900002"), Map.of("Q0900004", 4), List.of());

    assertThat(cards).extracting(Card::qid).doesNotContain("Q0900004");
    assertThat(cards).hasSize(2);
  }

  @Test
  @DisplayName("an entity on the list but absent from the graph is skipped, not dealt blank")
  void skipsEntitiesMissingFromTheGraph() {
    List<Card> cards = deal(List.of("Q0900002", "Q900999"), Map.of(), List.of());

    assertThat(cards).extracting(Card::qid).containsExactly("Q0900002");
  }

  @Test
  @DisplayName("a known card carries a degree and no routes; the reverse for a candidate")
  void knownAndCandidateCardsDifferInShape() {
    Card known = Card.known(NODES.get("Q0900002"), 90);
    Card candidate = Card.candidate(NODES.get("Q0900003"), List.of("a -[X]-> b"));

    assertThat(known.degree()).hasValue(90);
    assertThat(known.routes()).isEmpty();
    assertThat(candidate.degree()).isEmpty();
    assertThat(candidate.routes()).containsExactly("a -[X]-> b");
  }

  @Test
  @DisplayName("a candidate is dealt after every fifth known card, and leftovers are not dropped")
  void interleavesCandidatesEveryFifthCard() {
    List<String> known = List.of("Q0900001", "Q0900002", "Q0900003", "Q0900005", "Q0900006");
    Map<String, NodeRecord> extra =
        Map.of(
            "Q0900005", new NodeRecord("Q0900005", NodeKind.GROUP, "Five", List.of()),
            "Q0900006", new NodeRecord("Q0900006", NodeKind.GROUP, "Six", List.of()));
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
    NodeRecord knownEnd = new NodeRecord("Q0900501", NodeKind.PERSON, "Route Known", List.of());
    NodeRecord candidateEnd =
        new NodeRecord("Q0900502", NodeKind.GROUP, "Route Candidate", List.of());
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
            List.of("Q0900001", "Q0900002", "Q0900003"),
            q -> DEGREES.getOrDefault(q, 0),
            q -> Optional.ofNullable(NODES.get(q)),
            Map.of("Q0900001", 3, "Q0900002", 5, "Q0900003", 3),
            List.of(),
            OptionalInt.of(3));

    assertThat(cards).extracting(Card::qid).containsExactly("Q0900003", "Q0900001");
  }

  @Test
  @DisplayName(
      "revise also deals a suppressed entity that is off the known list — the walk widens to"
          + " known-list plus suppressed, not known-list alone (issue #106)")
  void reviseReachesASuppressedEntityOffTheKnownList() {
    // Q0900008 is deliberately absent from knownQids — that is exactly what KnownList.suppressed
    // means (issue #106's javadoc: "deliberately not part of the known-list"). Before
    // dealRevision's walk widens, this entity is unreachable at any --revise target: it is on
    // no list dealRevision ever iterates.
    NodeRecord suppressedNode =
        new NodeRecord("Q0900008", NodeKind.PERSON, "Suppressed, off the list", List.of());

    List<Card> cards =
        Deck.deal(
            List.of("Q0900001"),
            q -> DEGREES.getOrDefault(q, 0),
            q -> Optional.ofNullable(q.equals("Q0900008") ? suppressedNode : NODES.get(q)),
            Map.of("Q0900001", 3, "Q0900008", KnownList.SUPPRESSION_RATING),
            List.of(),
            OptionalInt.of(KnownList.SUPPRESSION_RATING));

    assertThat(cards).extracting(Card::qid).containsExactly("Q0900008");
  }

  @Test
  @DisplayName("the revision deck is ordered by degree descending, then by qid, like the default")
  void reviseOrdersByDegreeDescendingThenQid() {
    // ADR 46 says dealRevision "sorts by the same degree-descending rule the default deck uses",
    // and degree-descending is the branch's own argument for why revising 121 cards is worth
    // doing: the busiest entities are the ones whose rating moves the most candidate scores. That
    // claim was untested — reviseDealsOnlyThatRating asserted membership in any order and
    // buildsAReviseDeck dealt a single card, so deleting the sort left the suite green.
    //
    // Q0900003 and Q0900004 are given the SAME degree, so the qid tiebreak is pinned too: without
    // it the order of two equal-degree cards is whatever the known list happened to say, and a
    // deck that reshuffles between runs over an unchanged table is not a deck anyone can resume.
    Map<String, Integer> degrees =
        Map.of("Q0900001", 3, "Q0900002", 90, "Q0900003", 50, "Q0900004", 50);

    List<Card> cards =
        Deck.deal(
            List.of("Q0900001", "Q0900002", "Q0900003", "Q0900004"),
            q -> degrees.getOrDefault(q, 0),
            q -> Optional.ofNullable(NODES.get(q)),
            Map.of("Q0900001", 3, "Q0900002", 3, "Q0900003", 3, "Q0900004", 3),
            List.of(),
            OptionalInt.of(3));

    assertThat(cards)
        .extracting(Card::qid)
        .containsExactly("Q0900002", "Q0900003", "Q0900004", "Q0900001");
  }

  @Test
  @DisplayName("a revise card carries the rating it currently has, so it is not re-rated blind")
  void reviseCardShowsTheCurrentRating() {
    List<Card> cards =
        Deck.deal(
            List.of("Q0900002"),
            q -> DEGREES.getOrDefault(q, 0),
            q -> Optional.ofNullable(NODES.get(q)),
            Map.of("Q0900002", 5),
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
            List.of("Q0900001"),
            q -> DEGREES.getOrDefault(q, 0),
            q -> Optional.ofNullable(NODES.get(q)),
            Map.of("Q0900001", 3),
            List.of(candidateFor("Q900101", "Candidate One")),
            OptionalInt.of(3));

    assertThat(cards).extracting(Card::qid).containsExactly("Q0900001");
  }

  @Test
  @DisplayName(
      "without revise, the deck still deals only unrated entities and no card shows a rating")
  void defaultModeIsUnchanged() {
    List<Card> cards =
        Deck.deal(
            List.of("Q0900001", "Q0900002"),
            q -> DEGREES.getOrDefault(q, 0),
            q -> Optional.ofNullable(NODES.get(q)),
            Map.of("Q0900002", 4),
            List.of(),
            OptionalInt.empty());

    assertThat(cards).extracting(Card::qid).containsExactly("Q0900001");
    assertThat(cards.get(0).currentRating()).isEmpty();
  }

  private static Explained candidateFor(String qid, String label) {
    NodeRecord node = new NodeRecord(qid, NodeKind.GROUP, label, List.of());
    return new Explained(new Recommendation(node, 1.0, 12, List.of()), List.of());
  }
}
