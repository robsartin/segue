package com.robsartin.segue.rate;

import static com.robsartin.segue.domain.Recommendations.DEFAULT_SCORER;
import static com.robsartin.segue.domain.Recommendations.MIN_CANDIDATE_DEGREE;
import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.segue.domain.AssertionRecord;
import com.robsartin.segue.domain.EdgeTypes;
import com.robsartin.segue.domain.Equivalences;
import com.robsartin.segue.domain.KnownList;
import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.domain.NodeRecord;
import com.robsartin.segue.domain.Provenance;
import com.robsartin.segue.domain.Recommendations;
import com.robsartin.segue.domain.Scorer;
import com.robsartin.segue.recommend.CandidateSweep;
import com.robsartin.segue.recommend.Sweep;
import com.robsartin.segue.tinker.TinkerGraphStore;
import com.robsartin.segue.wikidata.RecognitionInstitutions;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Notes carry counts, and nothing else (ADR 33).
 *
 * <p><b>The floor this pads to and passes is {@code Recommendations.MIN_CANDIDATE_DEGREE} itself,
 * by static import.</b> It used to be a local {@code = 12} whose javadoc cited a {@code
 * RateRun.MIN_CANDIDATE_DEGREE} that issue #119 had already deleted — a copy of a copy of a number
 * the authority's own javadoc calls a MEASURED default. Re-measure it and this fixture would have
 * gone on padding to twelve, flooring at twelve and passing, testing a number the shipped tools no
 * longer use, with nothing failing (issue #107). That re-measurement then happened: issues #117 and
 * #118 lowered the default, and this fixture moved with it because it reads the authority.
 *
 * <p><b>Every assertion below that checks for "no rating in a note" checks by looking for one of
 * this fixture's own qids</b>, never by matching a rating-shaped regex. A rating never appears
 * paired with anything else in a note either — {@code RateRun} never formats one at all, every note
 * being a {@code .size()} count — so a regex describing a rating's shape can never see a violation
 * regardless of which code path ran; a qid is the one piece of identifying data that really could
 * leak into a note by accident, and checking for it is a guard that can fail.
 */
class RateRunTest {

  private static final String KNOWN_ONE = "Q0900001";
  private static final String KNOWN_TWO = "Q0900002";
  private static final String SHARED_ARTIST = "Q0900003";
  private static final String ANCESTOR = "Q0900004";

  private static final Instant WHEN = Instant.parse("2026-01-01T00:00:00Z");

  /**
   * Three things the owner loves, each reaching the first candidate through its own intermediate.
   */
  private static final List<String> LOVED = List.of("Q0900111", "Q0900112", "Q0900113");

  /** Six things the owner is lukewarm about, reaching the second candidate the same way. */
  private static final List<String> LUKEWARM =
      List.of("Q0900121", "Q0900122", "Q0900123", "Q0900124", "Q0900125", "Q0900126");

  private static final String BELOVED = "Q0900301";
  private static final String CROWDED = "Q0900302";

  /** A candidate three of yours reach, through intermediates nothing much else touches. */
  private static final String QUIETLY_REACHED = "Q0900401";

  /** A candidate six of yours reach, through intermediates more than twice as busy. */
  private static final String BUSILY_REACHED = "Q0900402";

  /** The degree every intermediate that reaches {@link #QUIETLY_REACHED} is padded to. */
  private static final int QUIET_VIA_DEGREE = 3;

  /** The degree every intermediate that reaches {@link #BUSILY_REACHED} is padded to. */
  private static final int BUSY_VIA_DEGREE = 7;

  /**
   * Both candidates, deliberately equal. Lift then divides both sides by the same number, so it
   * follows Adamic-Adar here instead of producing a third answer.
   */
  private static final int BOTH_CANDIDATES_DEGREE = 12;

  @Test
  @DisplayName("the deck is built from the graph, and the notes carry counts and no rating")
  void buildsADeckAndSaysWhatItDid() throws Exception {
    try (TinkerGraphStore graph = new TinkerGraphStore()) {
      graph.upsertNode(new NodeRecord(KNOWN_ONE, NodeKind.GROUP, "One", List.of()));
      graph.upsertNode(new NodeRecord(KNOWN_TWO, NodeKind.GROUP, "Two", List.of()));
      List<String> notes = new ArrayList<>();

      List<Card> deck =
          RateRun.buildDeck(
              graph,
              List.of(KNOWN_ONE, KNOWN_TWO),
              Map.of(KNOWN_TWO, 4),
              Equivalences.NONE,
              0,
              MIN_CANDIDATE_DEGREE,
              OptionalInt.empty(),
              notes::add);

      assertThat(deck).extracting(Card::qid).containsExactly(KNOWN_ONE);
      assertThat(notes).anyMatch(n -> n.contains("1 card(s)"));
      assertThat(notes).noneMatch(n -> n.contains(KNOWN_ONE) || n.contains(KNOWN_TWO));
    }
  }

  @Test
  @DisplayName("revise mode deals the rated entities and says so, without naming a rating")
  void buildsAReviseDeck() throws Exception {
    try (TinkerGraphStore graph = new TinkerGraphStore()) {
      graph.upsertNode(new NodeRecord("Q0900001", NodeKind.GROUP, "One", List.of()));
      graph.upsertNode(new NodeRecord("Q0900002", NodeKind.GROUP, "Two", List.of()));
      List<String> notes = new ArrayList<>();

      List<Card> deck =
          RateRun.buildDeck(
              graph,
              List.of("Q0900001", "Q0900002"),
              Map.of("Q0900001", 3, "Q0900002", 5),
              Equivalences.NONE,
              0,
              MIN_CANDIDATE_DEGREE,
              OptionalInt.of(3),
              notes::add);

      assertThat(deck).extracting(Card::qid).containsExactly("Q0900001");
      assertThat(deck.get(0).currentRating()).hasValue(3);
      assertThat(notes).noneMatch(n -> n.contains("Q0900001") || n.contains("Q0900002"));
    }
  }

  @Test
  @DisplayName("the reconsideration count is over the known list, not over the whole table")
  void reviseCountsOnlyWhatItCanDeal() throws Exception {
    // The count and the deck were computed from different populations: the note counted every row
    // in the affinity table at that rating, while dealRevision walks knownQids only. On the real
    // table that read "121 card(s) up for reconsideration" followed by "84 card(s) to rate", with
    // nothing anywhere explaining the 37 — and the 37 are entities rated at some point and since
    // dropped from the list, which the deck will never deal.
    try (TinkerGraphStore graph = new TinkerGraphStore()) {
      graph.upsertNode(new NodeRecord("Q0900001", NodeKind.GROUP, "On the list", List.of()));
      graph.upsertNode(
          new NodeRecord("Q0900009", NodeKind.GROUP, "Rated, off the list", List.of()));
      List<String> notes = new ArrayList<>();

      List<Card> deck =
          RateRun.buildDeck(
              graph,
              List.of("Q0900001"),
              Map.of("Q0900001", 3, "Q0900009", 3),
              Equivalences.NONE,
              0,
              MIN_CANDIDATE_DEGREE,
              OptionalInt.of(3),
              notes::add);

      assertThat(deck).hasSize(1);
      assertThat(notes).anyMatch(n -> n.contains("1 ") && n.contains("up for reconsideration"));
      assertThat(notes).noneMatch(n -> n.contains("2 ") && n.contains("up for reconsideration"));
      assertThat(notes).noneMatch(n -> n.contains("Q0900001") || n.contains("Q0900009"));
    }
  }

  @Test
  @DisplayName(
      "revise also reaches a rejected entity off the known list, and the count says so — a"
          + " suppressed entity is exactly the case Deck.dealRevision must widen its walk for"
          + " (issue #106)")
  void reviseReachesASuppressedEntityOffTheList() throws Exception {
    // Q0900009 is never on the known list, unlike reviseCountsOnlyWhatItCanDeal's off-list
    // entity — it is suppressed instead (rated at KnownList.SUPPRESSION_RATING), which is
    // precisely the entity a revision pass exists to let the owner reconsider. If the count
    // undercounts what dealRevision actually deals, that is the exact "121 vs 84" bug this
    // class's own comment warns about, reintroduced one rating tier down.
    try (TinkerGraphStore graph = new TinkerGraphStore()) {
      graph.upsertNode(new NodeRecord("Q0900001", NodeKind.GROUP, "On the list", List.of()));
      graph.upsertNode(
          new NodeRecord("Q0900009", NodeKind.GROUP, "Rejected, off the list", List.of()));
      List<String> notes = new ArrayList<>();

      List<Card> deck =
          RateRun.buildDeck(
              graph,
              List.of("Q0900001"),
              Map.of(
                  "Q0900001", KnownList.SUPPRESSION_RATING,
                  "Q0900009", KnownList.SUPPRESSION_RATING),
              Equivalences.NONE,
              0,
              MIN_CANDIDATE_DEGREE,
              OptionalInt.of(KnownList.SUPPRESSION_RATING),
              notes::add);

      assertThat(deck).extracting(Card::qid).containsExactlyInAnyOrder("Q0900001", "Q0900009");
      assertThat(notes).anyMatch(n -> n.contains("2") && n.contains("up for reconsideration"));
      assertThat(notes).noneMatch(n -> n.contains("Q0900001") || n.contains("Q0900009"));
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

      List<Card> deck =
          RateRun.buildDeck(
              graph,
              List.of(KNOWN_ONE),
              Map.of(),
              Equivalences.NONE,
              10,
              MIN_CANDIDATE_DEGREE,
              OptionalInt.empty(),
              notes::add);

      // The candidate branch actually ran and actually found something, or this test would pass
      // for the wrong reason — the same emptiness that made the vacuous regex pass before.
      assertThat(deck).extracting(Card::qid).contains(ANCESTOR);
      assertThat(notes).anyMatch(n -> n.contains("candidate(s) mixed in"));
      assertThat(notes)
          .noneMatch(
              n -> n.contains(KNOWN_ONE) || n.contains(SHARED_ARTIST) || n.contains(ANCESTOR));
    }
  }

  @Test
  @DisplayName(
      "a candidate rated at or below the suppression threshold is excluded from the sweep, even"
          + " though it would otherwise qualify (issue #106)")
  void aRejectedCandidateIsExcludedFromTheSweep() throws Exception {
    // Same fixture as theCandidateSweepNotesNameNoEntity — ANCESTOR is findable unaided. The
    // deck itself would exclude ANCESTOR either way, because Deck.deal already refuses to deal
    // any already-rated candidate regardless of the rating's value; that filter alone would make
    // a "the deck omits it" assertion pass even with the suppressed set silently dropped. The
    // "candidate(s) mixed in" count comes from CandidateSweep's own output, before Deck.deal ever
    // sees it, so it is the assertion that actually proves the wiring, not just the outcome.
    try (TinkerGraphStore graph = new TinkerGraphStore()) {
      node(graph, KNOWN_ONE, NodeKind.GROUP, "one you know");
      node(graph, SHARED_ARTIST, NodeKind.PERSON, "the artist you cite");
      node(graph, ANCESTOR, NodeKind.GROUP, "who that artist cites");
      edge(graph, KNOWN_ONE, SHARED_ARTIST, EdgeTypes.INFLUENCED_BY.code());
      edge(graph, SHARED_ARTIST, ANCESTOR, EdgeTypes.INFLUENCED_BY.code());
      padDegreeTo(graph, ANCESTOR, MIN_CANDIDATE_DEGREE);
      List<String> notes = new ArrayList<>();

      List<Card> deck =
          RateRun.buildDeck(
              graph,
              List.of(KNOWN_ONE),
              Map.of(ANCESTOR, KnownList.SUPPRESSION_RATING),
              Equivalences.NONE,
              10,
              MIN_CANDIDATE_DEGREE,
              OptionalInt.empty(),
              notes::add);

      assertThat(deck).extracting(Card::qid).doesNotContain(ANCESTOR);
      assertThat(notes).anyMatch(n -> n.contains("0 candidate(s) mixed in"));
      assertThat(notes).noneMatch(n -> n.contains("1 candidate(s) mixed in"));
    }
  }

  @Test
  @DisplayName(
      "a local id the owner has merged is never mixed into the deck as a candidate, because the"
          + " deck would be offering him the thing he minted and then resolved (#92)")
  void shouldNotMixALocalIdIntoTheDeckWhenTheOwnerHasMergedIt() throws Exception {
    // The same fixture, with the candidate a LOCAL id the owner has since merged. The count from
    // CandidateSweep is the assertion that proves the wiring, for the reason the test above gives:
    // Deck.deal would drop an already-rated qid anyway, and a merged local id is not rated here.
    String minted = "Q00900042";
    try (TinkerGraphStore graph = new TinkerGraphStore()) {
      node(graph, KNOWN_ONE, NodeKind.GROUP, "one you know");
      node(graph, SHARED_ARTIST, NodeKind.PERSON, "the artist you cite");
      node(graph, minted, NodeKind.GROUP, "a band no source knows");
      edge(graph, KNOWN_ONE, SHARED_ARTIST, EdgeTypes.INFLUENCED_BY.code());
      edge(graph, SHARED_ARTIST, minted, EdgeTypes.INFLUENCED_BY.code());
      padDegreeTo(graph, minted, MIN_CANDIDATE_DEGREE);
      List<String> notes = new ArrayList<>();

      List<Card> deck =
          RateRun.buildDeck(
              graph,
              List.of(KNOWN_ONE),
              Map.of(),
              new Equivalences(Map.of(minted, "Q10000000900")),
              10,
              MIN_CANDIDATE_DEGREE,
              OptionalInt.empty(),
              notes::add);

      assertThat(deck).extracting(Card::qid).doesNotContain(minted);
      assertThat(notes).anyMatch(n -> n.contains("0 candidate(s) mixed in"));
      assertThat(notes).noneMatch(n -> n.contains("1 candidate(s) mixed in"));
    }
  }

  @Test
  @DisplayName("a lower --min-degree reaches a candidate the default floor excludes (issue #119)")
  void aLowerFloorReachesCandidatesTheDefaultExcludes() throws Exception {
    try (TinkerGraphStore graph = new TinkerGraphStore()) {
      node(graph, KNOWN_ONE, NodeKind.GROUP, "one you know");
      node(graph, SHARED_ARTIST, NodeKind.PERSON, "the artist you cite");
      node(graph, ANCESTOR, NodeKind.GROUP, "who that artist cites");
      edge(graph, KNOWN_ONE, SHARED_ARTIST, EdgeTypes.INFLUENCED_BY.code());
      edge(graph, SHARED_ARTIST, ANCESTOR, EdgeTypes.INFLUENCED_BY.code());
      // Derived from the authority rather than stated: below the recommender's default floor, at
      // exactly a lowered one. Re-measuring the default (as issues #117 and #118 did) moves both
      // ends of this fixture together, so what the test pins is the DIAL and never a number.
      int loweredFloor = MIN_CANDIDATE_DEGREE - 2;
      padDegreeTo(graph, ANCESTOR, loweredFloor);

      List<Card> atTheDefaultFloor =
          RateRun.buildDeck(
              graph,
              List.of(KNOWN_ONE),
              Map.of(),
              Equivalences.NONE,
              10,
              MIN_CANDIDATE_DEGREE,
              OptionalInt.empty(),
              note -> {});
      assertThat(atTheDefaultFloor).extracting(Card::qid).doesNotContain(ANCESTOR);

      List<Card> atTheLoweredFloor =
          RateRun.buildDeck(
              graph,
              List.of(KNOWN_ONE),
              Map.of(),
              Equivalences.NONE,
              10,
              loweredFloor,
              OptionalInt.empty(),
              note -> {});
      assertThat(atTheLoweredFloor).extracting(Card::qid).contains(ANCESTOR);
    }
  }

  @Test
  @DisplayName("a rated known entity changes which candidate the deck deals")
  void ratingsMoveTheCandidates() throws Exception {
    // AffinityWeightedRecommendationTest's shape, asked of the deck instead of the report: two
    // candidates identical in everything the scorer can see except how many of the owner's
    // entities reach them, and how much those entities are worth.
    //
    // The deck exists to collect ratings and shows recommend candidates while it does. If it
    // sweeps with EQUAL_REGARD it is answering a question the owner has already moved past — the
    // cards diverge from `./gradlew recommend`'s for the same --known file the moment anything is
    // rated, and Deck's promise that the owner can "feel the recommender change inside one
    // session" is kept for the known cards and broken for the candidates.
    try (TinkerGraphStore graph = new TinkerGraphStore()) {
      twoCandidates(graph);
      List<String> everything = new ArrayList<>(LOVED);
      everything.addAll(LUKEWARM);

      List<Card> unweighted =
          RateRun.buildDeck(
              graph,
              everything,
              Map.of(),
              Equivalences.NONE,
              1,
              MIN_CANDIDATE_DEGREE,
              OptionalInt.empty(),
              note -> {});
      // Counting alone, the candidate six of your things reach beats the one three of them do.
      assertThat(unweighted).extracting(Card::qid).contains(CROWDED).doesNotContain(BELOVED);

      List<Card> weighted =
          RateRun.buildDeck(
              graph,
              everything,
              ratings(),
              Equivalences.NONE,
              1,
              MIN_CANDIDATE_DEGREE,
              OptionalInt.empty(),
              note -> {});
      assertThat(weighted).extracting(Card::qid).contains(BELOVED).doesNotContain(CROWDED);
    }
  }

  @Test
  @DisplayName("the deck deals the candidate the recommender's default scorer ranks first")
  void shouldDealTheRecommendersTopCandidateWhenTheScorersDisagree() throws Exception {
    // The deck's sweep held its own copy of the recommender's default scorer (issue #244): a
    // literal here, a literal in RecommendCli.parse, and nothing pairing them. Issue #242 came
    // within one clause of moving that default — and had it moved, the deck would have gone on
    // dealing lift candidates while `recommend` ranked with something else, with the whole gate
    // green. This is the check that would not have been.
    try (TinkerGraphStore graph = new TinkerGraphStore()) {
      oneQuietlyReachedAndOneBusilyReached(graph);
      List<String> everything = new ArrayList<>(LOVED);
      everything.addAll(LUKEWARM);

      // The fixture has to be able to tell the default apart from every other point on the dial, or
      // every assertion below is vacuously true. Asserted against all of them rather than against
      // raw counting alone (issue #291): the fixture this replaced claimed in its javadoc to
      // separate the default from every scorer and compared it with one, so the claim could not
      // fail. A scorer added to the enum now joins the comparison without anybody remembering to.
      String byTheDefault = topCandidate(graph, everything, DEFAULT_SCORER);
      List<String> byEveryOtherScorer =
          Arrays.stream(Scorer.values())
              .filter(scorer -> scorer != DEFAULT_SCORER)
              .map(scorer -> topCandidate(graph, everything, scorer))
              .toList();
      assertThat(byTheDefault)
          .as(
              "this fixture must separate %s from every other scorer; if the default has moved,"
                  + " rebuild oneQuietlyReachedAndOneBusilyReached so it discriminates the new one",
              DEFAULT_SCORER)
          .isNotIn(byEveryOtherScorer);

      List<Card> deck =
          RateRun.buildDeck(
              graph,
              everything,
              Map.of(),
              Equivalences.NONE,
              1,
              MIN_CANDIDATE_DEGREE,
              OptionalInt.empty(),
              note -> {});

      assertThat(deck)
          .extracting(Card::qid)
          .contains(byTheDefault)
          .doesNotContainAnyElementsOf(byEveryOtherScorer);
    }
  }

  /** Invented ratings, never Rob's (ADR 33, issue #37). Every one of them is also an exclusion. */
  private static Map<String, Integer> ratings() {
    Map<String, Integer> ratings = new LinkedHashMap<>();
    LOVED.forEach(qid -> ratings.put(qid, 5));
    LUKEWARM.forEach(qid -> ratings.put(qid, 2));
    return ratings;
  }

  /** Two ancestors at the same degree: one reached by three of yours, one by six. */
  private static void twoCandidates(TinkerGraphStore graph) {
    node(graph, BELOVED, NodeKind.GROUP, "the loved ancestor");
    node(graph, CROWDED, NodeKind.GROUP, "the crowded ancestor");
    int intermediate = 0;
    for (String seed : LOVED) {
      reaches(graph, seed, "Q09002" + (10 + intermediate++), BELOVED);
    }
    for (String seed : LUKEWARM) {
      reaches(graph, seed, "Q09002" + (10 + intermediate++), CROWDED);
    }
    // Padded to the same degree, so lift — which divides by the candidate's own degree — compares
    // the two on equal terms.
    padDegreeTo(graph, BELOVED, MIN_CANDIDATE_DEGREE);
    padDegreeTo(graph, CROWDED, MIN_CANDIDATE_DEGREE);
  }

  /**
   * Two ancestors that only resource allocation ranks the way it does. One is reached by three of
   * yours through intermediates at {@link #QUIET_VIA_DEGREE}; the other by six, through
   * intermediates at {@link #BUSY_VIA_DEGREE}. Both candidates carry the same degree.
   *
   * <p><b>Varying the INTERMEDIATE's degree is the whole mechanism, and the fixture this replaced
   * could not do it.</b> The three scorers that do not divide by the candidate's own degree differ
   * only in how they discount the intermediate's ({@code Scorer.score}), so a graph whose
   * intermediates are all one degree ranks identically under all three and can never tell them
   * apart. Here the seed counts are three against six and the intermediate degrees three against
   * seven, so the count ratio sits strictly between {@code ln 7 / ln 3} and {@code 7 / 3}:
   * counting, Adamic-Adar and lift all prefer the busily reached candidate, and resource allocation
   * is alone in preferring the other. Issue #291's design spec holds the arithmetic and the
   * margins.
   *
   * <p>Padding is left to the shared {@code padDegreeTo} and its shared filler range on purpose. A
   * filler is a {@code WORK}, so {@code CandidateSweep.couldBeExplored} never offers one, and it is
   * never one hop from a seed, so it is never an intermediate either — two nodes padding through
   * the same filler changes no score. A busy intermediate is not at risk of being dropped as a hub
   * either: {@code PathRanking.isHub} answers yes to a busy {@code CONCEPT} or a recognition
   * institution, and {@link #reaches} mints intermediates as {@code PERSON}.
   */
  private static void oneQuietlyReachedAndOneBusilyReached(TinkerGraphStore graph) {
    node(graph, QUIETLY_REACHED, NodeKind.GROUP, "the quietly reached ancestor");
    node(graph, BUSILY_REACHED, NodeKind.GROUP, "the busily reached ancestor");
    int intermediate = 0;
    for (String seed : LOVED) {
      String via = "Q09004" + (10 + intermediate++);
      reaches(graph, seed, via, QUIETLY_REACHED);
      padDegreeTo(graph, via, QUIET_VIA_DEGREE);
    }
    for (String seed : LUKEWARM) {
      String via = "Q09004" + (10 + intermediate++);
      reaches(graph, seed, via, BUSILY_REACHED);
      padDegreeTo(graph, via, BUSY_VIA_DEGREE);
    }
    padDegreeTo(graph, QUIETLY_REACHED, BOTH_CANDIDATES_DEGREE);
    padDegreeTo(graph, BUSILY_REACHED, BOTH_CANDIDATES_DEGREE);
  }

  /**
   * What the recommender's own sweep ranks first under one scorer, run here exactly as {@code
   * RateRun} runs it — same sweep class, same institution filter, same floor, same regard — so the
   * scorer is the only thing that differs between the two sides of the assertion.
   */
  private static String topCandidate(TinkerGraphStore graph, List<String> known, Scorer scorer) {
    Sweep sweep =
        new CandidateSweep(graph, RecognitionInstitutions::isRecognitionInstitution)
            .over(
                known,
                KnownList.notOffered(Map.of(), Equivalences.NONE),
                scorer,
                MIN_CANDIDATE_DEGREE,
                Recommendations.regardFor(Map.of()));
    return Recommendations.rank(sweep.candidates(), 1).get(0).entity().qid();
  }

  /** One of yours cites an artist; that artist cites the candidate. */
  private static void reaches(TinkerGraphStore graph, String seed, String via, String candidate) {
    node(graph, seed, NodeKind.GROUP, "one of yours " + seed);
    node(graph, via, NodeKind.PERSON, "a cited artist " + via);
    edge(graph, seed, via, EdgeTypes.INFLUENCED_BY.code());
    edge(graph, via, candidate, EdgeTypes.INFLUENCED_BY.code());
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
      String filler = "Q09009" + i;
      node(graph, filler, NodeKind.WORK, "filler " + filler);
      edge(graph, qid, filler, EdgeTypes.INFLUENCED_BY.code());
    }
  }
}
