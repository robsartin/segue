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
import static com.robsartin.segue.recommend.InventedWorld.KNOWN_ONE;
import static com.robsartin.segue.recommend.InventedWorld.KNOWN_TWO;
import static com.robsartin.segue.recommend.InventedWorld.SHARED_ARTIST;
import static com.robsartin.segue.recommend.InventedWorld.SHARED_PRIZE;
import static com.robsartin.segue.recommend.InventedWorld.THE_ACADEMY;
import static com.robsartin.segue.recommend.InventedWorld.THE_ADMIRER;
import static com.robsartin.segue.recommend.InventedWorld.edge;
import static com.robsartin.segue.recommend.InventedWorld.hubConcept;
import static com.robsartin.segue.recommend.InventedWorld.node;
import static com.robsartin.segue.recommend.InventedWorld.padDegreeTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.segue.domain.EdgeTypes;
import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.domain.Recommendation;
import com.robsartin.segue.domain.Recommendations;
import com.robsartin.segue.domain.Scorer;
import com.robsartin.segue.tinker.TinkerGraphStore;
import java.util.List;
import java.util.Optional;
import java.util.function.ToDoubleFunction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The two-hop walk, and every rule about who is allowed to be a recommendation (ADR 45).
 *
 * <p>The floor here is 4 rather than {@code Recommendations.MIN_CANDIDATE_DEGREE}: an invented
 * graph big enough to give every candidate twelve edges would be a page of padding that says
 * nothing, and {@link #theDegreeFloorIsApplied()} tests the floor itself against a value it names.
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
    return new CandidateSweep(graph, INSTITUTIONS).over(KNOWN, scorer, floor, regard);
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
    // Unused today - Recommendations.EQUAL_REGARD gives everything a 1 because the affinity table
    // is empty. This is the test that says the seam is real: hand it a function that thinks more
    // of one entity, and the candidates that entity reaches move.
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
                List.of(KNOWN_ONE, KNOWN_TWO, "Q900999"),
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
      String record = "Q90031" + i;
      node(graph, record, NodeKind.WORK, "record " + i);
      edge(graph, KNOWN_ONE, record, EdgeTypes.PERFORMED.code());
      edge(graph, ANCESTOR, record, EdgeTypes.PERFORMED.code());
    }
    padDegreeTo(graph, ANCESTOR, FLOOR);

    Recommendation player = find(sweep(), ANCESTOR).orElseThrow();

    assertThat(player.knownReached()).isEqualTo(1);
    assertThat(player.intermediates()).isEqualTo(4);
  }
}
