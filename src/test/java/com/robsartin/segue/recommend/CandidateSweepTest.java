package com.robsartin.segue.recommend;

import static com.robsartin.segue.recommend.InventedWorld.ALSO_IN_THE_ACADEMY;
import static com.robsartin.segue.recommend.InventedWorld.ALSO_IN_THE_HALL;
import static com.robsartin.segue.recommend.InventedWorld.ANCESTOR;
import static com.robsartin.segue.recommend.InventedWorld.ANOTHER_ANCESTOR;
import static com.robsartin.segue.recommend.InventedWorld.A_RECORD;
import static com.robsartin.segue.recommend.InventedWorld.A_THIN_BAND;
import static com.robsartin.segue.recommend.InventedWorld.ELECTED_TO;
import static com.robsartin.segue.recommend.InventedWorld.FELLOW_PRIZEWINNER;
import static com.robsartin.segue.recommend.InventedWorld.HALL_OF_FAME;
import static com.robsartin.segue.recommend.InventedWorld.INSTITUTIONS;
import static com.robsartin.segue.recommend.InventedWorld.JUST_DISCOVERED;
import static com.robsartin.segue.recommend.InventedWorld.KNOWN_ONE;
import static com.robsartin.segue.recommend.InventedWorld.KNOWN_TWO;
import static com.robsartin.segue.recommend.InventedWorld.SHARED_ARTIST;
import static com.robsartin.segue.recommend.InventedWorld.SHARED_PRIZE;
import static com.robsartin.segue.recommend.InventedWorld.THE_ACADEMY;
import static com.robsartin.segue.recommend.InventedWorld.THE_ADMIRER;
import static com.robsartin.segue.recommend.InventedWorld.edge;
import static com.robsartin.segue.recommend.InventedWorld.fillerQid;
import static com.robsartin.segue.recommend.InventedWorld.hubConcept;
import static com.robsartin.segue.recommend.InventedWorld.node;
import static com.robsartin.segue.recommend.InventedWorld.padDegreeTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.segue.domain.AssertionRecord;
import com.robsartin.segue.domain.EdgeTypes;
import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.domain.Provenance;
import com.robsartin.segue.domain.Recommendation;
import com.robsartin.segue.domain.Recommendations;
import com.robsartin.segue.domain.Scorer;
import com.robsartin.segue.tinker.TinkerGraphStore;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.ToDoubleFunction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The two-hop walk, and every rule about who is allowed to be a recommendation (ADR 45).
 *
 * <p>The floor here is 4 rather than {@code Recommendations.MIN_CANDIDATE_DEGREE}: an invented
 * graph is padded to whatever floor it must clear, and a fixture that tracked the shipped default
 * would re-pad every time that default is re-measured. {@link #theDegreeFloorIsApplied()} tests the
 * floor mechanism against a value it names, and {@link
 * #theDefaultFloorAdmitsAThinlyConnectedCandidate()} is the one test here that asks what the
 * shipped default actually admits.
 */
class CandidateSweepTest {

  private static final int FLOOR = 4;
  private static final List<String> KNOWN = List.of(KNOWN_ONE, KNOWN_TWO);

  private TinkerGraphStore graph;

  @BeforeEach
  void setUp() {
    graph = new TinkerGraphStore();
    node(graph, KNOWN_ONE, NodeKind.GROUP, "one you know");
    node(graph, KNOWN_TWO, NodeKind.GROUP, "another you know");
  }

  @AfterEach
  void tearDown() {
    graph.close();
  }

  private Sweep sweep() {
    return sweep(Scorer.LIFT, FLOOR, Recommendations.EQUAL_REGARD);
  }

  private Sweep sweep(Scorer scorer, int floor, ToDoubleFunction<String> regard) {
    return new CandidateSweep(graph, INSTITUTIONS).over(KNOWN, Set.of(), scorer, floor, regard);
  }

  private Sweep sweep(Set<String> suppressed) {
    return new CandidateSweep(graph, INSTITUTIONS)
        .over(KNOWN, suppressed, Scorer.LIFT, FLOOR, Recommendations.EQUAL_REGARD);
  }

  private static Optional<Recommendation> find(Sweep sweep, String qid) {
    return sweep.candidates().stream().filter(c -> c.entity().qid().equals(qid)).findFirst();
  }

  /** Both known entities cite one artist, who in turn cites the candidate. */
  private void influenceChain() {
    node(graph, SHARED_ARTIST, NodeKind.PERSON, "the artist they both cite");
    node(graph, ANCESTOR, NodeKind.GROUP, "who that artist cites");
    edge(graph, KNOWN_ONE, SHARED_ARTIST, EdgeTypes.INFLUENCED_BY.code());
    edge(graph, KNOWN_TWO, SHARED_ARTIST, EdgeTypes.INFLUENCED_BY.code());
    edge(graph, SHARED_ARTIST, ANCESTOR, EdgeTypes.INFLUENCED_BY.code());
    padDegreeTo(graph, ANCESTOR, FLOOR);
  }

  @Test
  @DisplayName("something two hops out, that you do not already know, is a candidate")
  void twoHopsOutIsACandidate() {
    influenceChain();

    Sweep sweep = sweep();

    assertThat(find(sweep, ANCESTOR)).isPresent();
    assertThat(find(sweep, ANCESTOR).orElseThrow().knownReached()).isEqualTo(2);
    assertThat(find(sweep, ANCESTOR).orElseThrow().score()).isGreaterThan(0);
  }

  @Test
  @DisplayName("what you already know is never recommended back to you")
  void theKnownListIsNeverRecommended() {
    influenceChain();
    // The two known entities are two hops from each other through the artist they both cite.
    Sweep sweep = sweep();

    assertThat(sweep.candidates())
        .extracting(c -> c.entity().qid())
        .doesNotContain(KNOWN_ONE, KNOWN_TWO);
  }

  @Test
  @DisplayName("a hub intermediate is excluded, not discounted — no route runs through it at all")
  void aHubIntermediateIsExcluded() {
    // The measured failure this rule exists for: a hall of fame connects everybody to everybody,
    // and discounting it still let it decide the top of the ranking.
    hubConcept(graph, HALL_OF_FAME, "an invented hall of fame");
    node(graph, ALSO_IN_THE_HALL, NodeKind.GROUP, "also in the hall");
    edge(graph, KNOWN_ONE, HALL_OF_FAME, EdgeTypes.RECEIVED_AWARD.code());
    edge(graph, KNOWN_TWO, HALL_OF_FAME, EdgeTypes.RECEIVED_AWARD.code());
    edge(graph, ALSO_IN_THE_HALL, HALL_OF_FAME, EdgeTypes.RECEIVED_AWARD.code());
    padDegreeTo(graph, ALSO_IN_THE_HALL, FLOOR);

    Sweep sweep = sweep();

    assertThat(find(sweep, ALSO_IN_THE_HALL)).isEmpty();
    assertThat(sweep.hubIntermediatesExcluded()).isEqualTo(1);
  }

  @Test
  @DisplayName("a body you are elected to is excluded whatever its degree (issue #66)")
  void anElectedBodyIsExcluded() {
    node(graph, THE_ACADEMY, NodeKind.GROUP, "an invented academy", ELECTED_TO);
    node(graph, ALSO_IN_THE_ACADEMY, NodeKind.PERSON, "another fellow");
    edge(graph, KNOWN_ONE, THE_ACADEMY, EdgeTypes.MEMBER_OF.code());
    edge(graph, ALSO_IN_THE_ACADEMY, THE_ACADEMY, EdgeTypes.MEMBER_OF.code());
    padDegreeTo(graph, ALSO_IN_THE_ACADEMY, FLOOR);

    assertThat(find(sweep(), ALSO_IN_THE_ACADEMY)).isEmpty();
  }

  @Test
  @DisplayName("an institution is not a recommendation even when it is the candidate")
  void anInstitutionIsNeverACandidate() {
    // The raw query put a learned society first: it connects 33 of the known entities, all by
    // membership. A recommender without this filter suggests joining an academy.
    node(graph, SHARED_ARTIST, NodeKind.PERSON, "a fellow of the academy");
    node(graph, THE_ACADEMY, NodeKind.GROUP, "an invented academy", ELECTED_TO);
    edge(graph, KNOWN_ONE, SHARED_ARTIST, EdgeTypes.INFLUENCED_BY.code());
    edge(graph, SHARED_ARTIST, THE_ACADEMY, EdgeTypes.MEMBER_OF.code());
    padDegreeTo(graph, THE_ACADEMY, FLOOR);

    assertThat(find(sweep(), THE_ACADEMY)).isEmpty();
  }

  @Test
  @DisplayName("only a person or a group is a recommendation; a record is not somebody to explore")
  void onlyPeopleAndGroupsAreCandidates() {
    node(graph, SHARED_ARTIST, NodeKind.PERSON, "a producer");
    node(graph, A_RECORD, NodeKind.WORK, "an invented record");
    edge(graph, KNOWN_ONE, SHARED_ARTIST, EdgeTypes.INFLUENCED_BY.code());
    edge(graph, SHARED_ARTIST, A_RECORD, EdgeTypes.PERFORMED.code());
    padDegreeTo(graph, A_RECORD, FLOOR);

    assertThat(find(sweep(), A_RECORD)).isEmpty();
  }

  @Test
  @DisplayName("the floor reports how many entities it discarded on degree alone")
  void theFloorReportsWhatItHeldOut() {
    node(graph, SHARED_ARTIST, NodeKind.PERSON, "the artist they both cite");
    node(graph, A_THIN_BAND, NodeKind.GROUP, "two edges to its name");
    edge(graph, KNOWN_ONE, SHARED_ARTIST, EdgeTypes.INFLUENCED_BY.code());
    edge(graph, SHARED_ARTIST, A_THIN_BAND, EdgeTypes.INFLUENCED_BY.code());
    padDegreeTo(graph, A_THIN_BAND, FLOOR - 2);

    assertThat(sweep().heldOutByFloor()).isEqualTo(1);
    assertThat(sweep(Scorer.LIFT, FLOOR - 2, Recommendations.EQUAL_REGARD).heldOutByFloor())
        .isZero();
  }

  @Test
  @DisplayName("an entity the kind rules refuse is not counted against the floor")
  void whatTheKindRulesRefuseIsNotTheFloorsDoing() {
    node(graph, SHARED_ARTIST, NodeKind.PERSON, "the artist they both cite");
    node(graph, A_RECORD, NodeKind.WORK, "a record, which is not a thing to go and explore");
    edge(graph, KNOWN_ONE, SHARED_ARTIST, EdgeTypes.INFLUENCED_BY.code());
    edge(graph, SHARED_ARTIST, A_RECORD, EdgeTypes.PERFORMED.code());

    assertThat(sweep().heldOutByFloor()).isZero();
  }

  @Test
  @DisplayName("what one expansion discovered is counted apart, at exactly one edge")
  void degreeOneGrowthIsCountedApart() {
    node(graph, SHARED_ARTIST, NodeKind.PERSON, "the artist they both cite");
    node(graph, JUST_DISCOVERED, NodeKind.GROUP, "one edge to its name");
    node(graph, A_THIN_BAND, NodeKind.GROUP, "two edges to its name");
    edge(graph, KNOWN_ONE, SHARED_ARTIST, EdgeTypes.INFLUENCED_BY.code());
    edge(graph, SHARED_ARTIST, JUST_DISCOVERED, EdgeTypes.INFLUENCED_BY.code());
    edge(graph, SHARED_ARTIST, A_THIN_BAND, EdgeTypes.INFLUENCED_BY.code());
    padDegreeTo(graph, A_THIN_BAND, FLOOR - 2);

    Sweep sweep = sweep();

    assertThat(sweep.heldOutByFloor()).isEqualTo(2);
    assertThat(sweep.heldOutAtDegreeOne()).isEqualTo(1);
  }

  @Test
  @DisplayName("an entity held out is counted once however many intermediates reach it")
  void anEntityHeldOutIsCountedOnce() {
    node(graph, SHARED_ARTIST, NodeKind.PERSON, "the artist they both cite");
    node(graph, THE_ADMIRER, NodeKind.PERSON, "another that reaches it");
    node(graph, A_THIN_BAND, NodeKind.GROUP, "two edges to its name");
    edge(graph, KNOWN_ONE, SHARED_ARTIST, EdgeTypes.INFLUENCED_BY.code());
    edge(graph, KNOWN_TWO, THE_ADMIRER, EdgeTypes.INFLUENCED_BY.code());
    edge(graph, SHARED_ARTIST, A_THIN_BAND, EdgeTypes.INFLUENCED_BY.code());
    edge(graph, THE_ADMIRER, A_THIN_BAND, EdgeTypes.INFLUENCED_BY.code());
    padDegreeTo(graph, A_THIN_BAND, FLOOR - 1);

    assertThat(sweep().heldOutByFloor()).isEqualTo(1);
  }

  @Test
  @DisplayName("a candidate below the degree floor is not ranked at all")
  void theDegreeFloorIsApplied() {
    node(graph, SHARED_ARTIST, NodeKind.PERSON, "the artist they both cite");
    node(graph, A_THIN_BAND, NodeKind.GROUP, "two edges to its name");
    edge(graph, KNOWN_ONE, SHARED_ARTIST, EdgeTypes.INFLUENCED_BY.code());
    edge(graph, SHARED_ARTIST, A_THIN_BAND, EdgeTypes.INFLUENCED_BY.code());
    padDegreeTo(graph, A_THIN_BAND, FLOOR - 2);

    assertThat(find(sweep(), A_THIN_BAND)).isEmpty();
    assertThat(find(sweep(Scorer.LIFT, FLOOR - 2, Recommendations.EQUAL_REGARD), A_THIN_BAND))
        .isPresent();
  }

  private static final Instant OWNER_ASSERTED_AT = Instant.parse("2026-08-31T00:00:00Z");

  @Test
  @DisplayName("an edge sourced only by the owner still counts toward the candidate's degree floor")
  void shouldCountAnOwnerEdgeTowardTheDegreeFloorWhenSweeping() {
    // The open question the plan handed this task rather than assuming: does CandidateSweep's
    // degree floor count an owner edge? #degree(String) is graph.edges(qid).size() with no
    // provenance filter, so every filler edge here is owner-sourced and nothing else touches
    // A_THIN_BAND — if the floor asked who claimed an edge, this candidate would stay below it and
    // theDegreeFloorIsApplied()'s shape (same setup, sourced padding) would diverge from this one.
    // It does not: this is the spec's stated design, observed rather than assumed.
    node(graph, SHARED_ARTIST, NodeKind.PERSON, "the artist they both cite");
    node(graph, A_THIN_BAND, NodeKind.GROUP, "reached once, then padded by the owner alone");
    edge(graph, KNOWN_ONE, SHARED_ARTIST, EdgeTypes.INFLUENCED_BY.code());
    edge(graph, SHARED_ARTIST, A_THIN_BAND, EdgeTypes.INFLUENCED_BY.code());
    padDegreeWithOwnerEdges(A_THIN_BAND, FLOOR);

    assertThat(find(sweep(), A_THIN_BAND)).isPresent();
  }

  /**
   * {@link InventedWorld#padDegreeTo}, but every filler edge is owner-sourced, not "invented".
   * Shares {@link InventedWorld#fillerQid} rather than re-deriving the id, so the two padding
   * helpers cannot drift onto different filler ranges.
   */
  private void padDegreeWithOwnerEdges(String qid, int degree) {
    int already = graph.edges(qid).size();
    for (int i = already; i < degree; i++) {
      String filler = fillerQid(qid, i);
      node(graph, filler, NodeKind.WORK, "owner-claimed filler " + filler);
      graph.record(
          new AssertionRecord(
              qid,
              filler,
              EdgeTypes.PERFORMED.code(),
              null,
              null,
              Provenance.owner(OWNER_ASSERTED_AT)));
    }
  }

  @Test
  @DisplayName("the default floor admits a thinly connected candidate (issues #117, #118)")
  void theDefaultFloorAdmitsAThinlyConnectedCandidate() {
    // The decision this pins: the shipped default admits a candidate this thin. The degree below
    // is the FIXTURE's, deliberately not a second copy of the constant — it asserts an upper bound
    // on the default, which is the direction issues #117 and #118 moved it, and it is why the
    // assertion is presence rather than equality. An entity sitting here is commonly thinly
    // connected because segue has not fetched it rather than because it is obscure, and the
    // measured cost of excluding it was the negative signal the taste layer had no other way of
    // getting (ADR 45's 2026-08-29 amendment).
    node(graph, SHARED_ARTIST, NodeKind.PERSON, "the artist they both cite");
    node(graph, ANCESTOR, NodeKind.GROUP, "who that artist cites");
    edge(graph, KNOWN_ONE, SHARED_ARTIST, EdgeTypes.INFLUENCED_BY.code());
    edge(graph, KNOWN_TWO, SHARED_ARTIST, EdgeTypes.INFLUENCED_BY.code());
    edge(graph, SHARED_ARTIST, ANCESTOR, EdgeTypes.INFLUENCED_BY.code());
    padDegreeTo(graph, ANCESTOR, 5);

    Sweep sweep =
        sweep(Scorer.LIFT, Recommendations.MIN_CANDIDATE_DEGREE, Recommendations.EQUAL_REGARD);

    assertThat(find(sweep, ANCESTOR)).isPresent();
  }

  @Test
  @DisplayName("a shared influence is worth more than a shared prize")
  void influenceOutweighsRecognition() {
    // Both candidates have the identical shape: one known entity, one non-hub intermediate, one
    // hop each side, the same degrees. Only the edge type differs.
    node(graph, SHARED_ARTIST, NodeKind.PERSON, "an artist they cite");
    node(graph, SHARED_PRIZE, NodeKind.CONCEPT, "a specific prize");
    node(graph, ANCESTOR, NodeKind.GROUP, "cited by that artist");
    node(graph, FELLOW_PRIZEWINNER, NodeKind.GROUP, "won the same prize");
    edge(graph, KNOWN_ONE, SHARED_ARTIST, EdgeTypes.INFLUENCED_BY.code());
    edge(graph, SHARED_ARTIST, ANCESTOR, EdgeTypes.INFLUENCED_BY.code());
    edge(graph, KNOWN_ONE, SHARED_PRIZE, EdgeTypes.RECEIVED_AWARD.code());
    edge(graph, FELLOW_PRIZEWINNER, SHARED_PRIZE, EdgeTypes.RECEIVED_AWARD.code());
    padDegreeTo(graph, ANCESTOR, FLOOR);
    padDegreeTo(graph, FELLOW_PRIZEWINNER, FLOOR);

    Sweep sweep = sweep();

    assertThat(find(sweep, ANCESTOR).orElseThrow().score())
        .isGreaterThan(find(sweep, FELLOW_PRIZEWINNER).orElseThrow().score());
  }

  @Test
  @DisplayName("a candidate that does the citing scores below one that is cited (issue #84)")
  void citingScoresBelowBeingCited() {
    // The defect issue #84 names, in four edges. Both candidates have the identical shape: the
    // same known entities, the same intermediate, one influence hop each side, the same degree.
    // Only the ARROW differs — the artist cites the ancestor, and the thin band cites the artist.
    influenceChain();
    node(graph, A_THIN_BAND, NodeKind.GROUP, "whose whole graph presence is an influence list");
    edge(graph, A_THIN_BAND, SHARED_ARTIST, EdgeTypes.INFLUENCED_BY.code());
    padDegreeTo(graph, A_THIN_BAND, FLOOR);

    Sweep sweep = sweep();

    assertThat(find(sweep, A_THIN_BAND).orElseThrow().degree())
        .isEqualTo(find(sweep, ANCESTOR).orElseThrow().degree());
    assertThat(find(sweep, A_THIN_BAND).orElseThrow().score())
        .isLessThan(find(sweep, ANCESTOR).orElseThrow().score());
  }

  @Test
  @DisplayName("a candidate that only cites is demoted, never dropped: it is still a real answer")
  void citingIsDemotedRatherThanExcluded() {
    node(graph, SHARED_ARTIST, NodeKind.PERSON, "an artist you cite");
    node(graph, A_THIN_BAND, NodeKind.GROUP, "which cites that artist too");
    edge(graph, KNOWN_ONE, SHARED_ARTIST, EdgeTypes.INFLUENCED_BY.code());
    edge(graph, A_THIN_BAND, SHARED_ARTIST, EdgeTypes.INFLUENCED_BY.code());
    padDegreeTo(graph, A_THIN_BAND, FLOOR);

    assertThat(find(sweep(), A_THIN_BAND)).isPresent();
    assertThat(find(sweep(), A_THIN_BAND).orElseThrow().score()).isGreaterThan(0);
  }

  @Test
  @DisplayName("the hop from one of yours carries no direction: both readings are segues")
  void directionIsAskedOnlyOfTheCandidatesOwnHop() {
    // The regression this guards is the one that would break the feature: the entities that cite
    // your list are the same entities that cite its ancestors, so discounting the FIRST hop by
    // direction would demote exactly the ancestors the change exists to keep.
    node(graph, SHARED_ARTIST, NodeKind.PERSON, "an artist you cite");
    node(graph, THE_ADMIRER, NodeKind.GROUP, "a band that cites you");
    node(graph, ANCESTOR, NodeKind.GROUP, "cited by the artist");
    node(graph, ANOTHER_ANCESTOR, NodeKind.GROUP, "cited by the admirer");
    edge(graph, KNOWN_ONE, SHARED_ARTIST, EdgeTypes.INFLUENCED_BY.code());
    edge(graph, SHARED_ARTIST, ANCESTOR, EdgeTypes.INFLUENCED_BY.code());
    edge(graph, THE_ADMIRER, KNOWN_ONE, EdgeTypes.INFLUENCED_BY.code());
    edge(graph, THE_ADMIRER, ANOTHER_ANCESTOR, EdgeTypes.INFLUENCED_BY.code());
    padDegreeTo(graph, SHARED_ARTIST, FLOOR);
    padDegreeTo(graph, THE_ADMIRER, FLOOR);
    padDegreeTo(graph, ANCESTOR, FLOOR);
    padDegreeTo(graph, ANOTHER_ANCESTOR, FLOOR);

    Sweep sweep = sweep();

    assertThat(find(sweep, ANOTHER_ANCESTOR).orElseThrow().score())
        .isEqualTo(find(sweep, ANCESTOR).orElseThrow().score());
  }

  @Test
  @DisplayName("the affinity seam is a weight per known entity, and it reaches the score")
  void theAffinitySeamReachesTheScore() {
    // The seam, at the level that owns it: hand this a function that thinks more of one entity and
    // the candidates that entity reaches move. Since issue #85 the real function is
    // Recommendations.regardFor over the owner's ratings, built in RecommendCli — what belongs
    // here is that the multiplication happens at all, and an invented function says it plainest.
    influenceChain();
    ToDoubleFunction<String> lovesTheFirst = qid -> KNOWN_ONE.equals(qid) ? 3.0 : 1.0;

    double equal = find(sweep(), ANCESTOR).orElseThrow().score();
    double weighted =
        find(sweep(Scorer.LIFT, FLOOR, lovesTheFirst), ANCESTOR).orElseThrow().score();

    assertThat(weighted).isGreaterThan(equal);
  }

  @Test
  @DisplayName("a known entity the graph has never heard of is counted, not fatal")
  void anUnknownEntityOnTheListIsCounted() {
    influenceChain();

    Sweep sweep =
        new CandidateSweep(graph, INSTITUTIONS)
            .over(
                List.of(KNOWN_ONE, KNOWN_TWO, "Q0900999"),
                Set.of(),
                Scorer.LIFT,
                FLOOR,
                Recommendations.EQUAL_REGARD);

    assertThat(sweep.knownFound()).isEqualTo(2);
    assertThat(sweep.knownMissing()).isEqualTo(1);
  }

  @Test
  @DisplayName("one known entity reaching a candidate through four of its own records counts once")
  void oneEntityThroughManyWorksIsStillOneEntity() {
    node(graph, ANCESTOR, NodeKind.GROUP, "a session player");
    for (int i = 0; i < 4; i++) {
      String record = "Q090031" + i;
      node(graph, record, NodeKind.WORK, "record " + i);
      edge(graph, KNOWN_ONE, record, EdgeTypes.PERFORMED.code());
      edge(graph, ANCESTOR, record, EdgeTypes.PERFORMED.code());
    }
    padDegreeTo(graph, ANCESTOR, FLOOR);

    Recommendation player = find(sweep(), ANCESTOR).orElseThrow();

    assertThat(player.knownReached()).isEqualTo(1);
    assertThat(player.intermediates()).isEqualTo(4);
  }

  @Test
  @DisplayName("a suppressed entity is absent from the candidates, even though it would qualify")
  void aSuppressedEntityIsAbsent() {
    influenceChain();

    Sweep sweep = sweep(Set.of(ANCESTOR));

    assertThat(find(sweep, ANCESTOR)).isEmpty();
  }

  @Test
  @DisplayName("suppressing one candidate does not touch another that would otherwise qualify")
  void suppressionDoesNotTouchOtherCandidates() {
    influenceChain();

    Sweep sweep = sweep(Set.of("Q0900999"));

    assertThat(find(sweep, ANCESTOR)).isPresent();
  }

  @Test
  @DisplayName(
      "suppression is a separate set from the known-list: the sweep's own counts still describe"
          + " only the known-list")
  void suppressionDoesNotFoldIntoTheKnownListCounts() {
    // ADR 45's counts, knownFound and knownMissing, are a diagnostic about the --known file, not
    // about what got filtered out. Folding a rejection into knownSet would corrupt that
    // diagnostic — a suppressed entity is not "known", and it must not inflate knownFound.
    influenceChain();

    Sweep withoutSuppression = sweep();
    Sweep withSuppression = sweep(Set.of(ANCESTOR));

    assertThat(withSuppression.knownFound()).isEqualTo(withoutSuppression.knownFound());
    assertThat(withSuppression.knownMissing()).isEqualTo(withoutSuppression.knownMissing());
  }

  @Test
  @DisplayName("an empty suppressed set behaves exactly like no suppression at all")
  void emptySuppressedSetSuppressesNothing() {
    influenceChain();

    Sweep sweep = sweep(Set.of());

    assertThat(find(sweep, ANCESTOR)).isPresent();
  }
}
