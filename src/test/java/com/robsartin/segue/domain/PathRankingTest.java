package com.robsartin.segue.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.function.ToIntFunction;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ADR 31: the shared ranking that both adapters are held to. A path is only as trustworthy as its
 * shakiest hop, so the order is weakest confidence descending, with hop count breaking ties.
 */
class PathRankingTest {

  @Test
  @DisplayName("weakest confidence descending is the primary order")
  void ordersByWeakestConfidenceDescending() {
    PathResult weak = path(0.30);
    PathResult strong = path(1.00);
    PathResult middle = path(0.80);

    List<PathResult> ranked = PathRanking.rank(List.of(weak, strong, middle));

    assertThat(ranked).extracting(PathResult::weakestConfidence).containsExactly(1.00, 0.80, 0.30);
  }

  @Test
  @DisplayName("a path is only as trustworthy as its shakiest hop")
  void weakestHopDecidesTheRank() {
    // Two strong hops and one weak hop: the weak hop sets the path's confidence.
    PathResult mixed = path(1.00, 0.20, 0.90);
    PathResult steady = path(0.50, 0.50);

    List<PathResult> ranked = PathRanking.rank(List.of(mixed, steady));

    assertThat(ranked).containsExactly(steady, mixed);
  }

  @Test
  @DisplayName("hop count ascending breaks ties on equal confidence")
  void hopCountBreaksTies() {
    PathResult threeHops = path(1.00, 1.00, 1.00);
    PathResult oneHop = path(1.00);
    PathResult twoHops = path(1.00, 1.00);

    List<PathResult> ranked = PathRanking.rank(List.of(threeHops, oneHop, twoHops));

    assertThat(ranked).containsExactly(oneHop, twoHops, threeHops);
  }

  @Test
  @DisplayName("the internal cap bounds a dense result, keeping the best")
  void capBoundsTheResultKeepingTheBest() {
    // One clearly-best strong path, then many weak ones well over the cap.
    PathResult best = path(1.00);
    List<PathResult> many =
        IntStream.range(0, PathRanking.MAX_PATHS + 25).mapToObj(i -> path(0.10)).toList();
    List<PathResult> input =
        java.util.stream.Stream.concat(many.stream(), java.util.stream.Stream.of(best)).toList();

    List<PathResult> ranked = PathRanking.rank(input);

    assertThat(ranked).hasSize(PathRanking.MAX_PATHS);
    assertThat(ranked.get(0)).isEqualTo(best);
  }

  @Test
  @DisplayName("an empty input ranks to an empty list")
  void emptyStaysEmpty() {
    assertThat(PathRanking.rank(List.of())).isEmpty();
  }

  // ---- specificity (issue #52) ---------------------------------------------

  @Test
  @DisplayName("a route through a hub CONCEPT loses to a specific one, even at lower confidence")
  void specificityOutranksConfidence() {
    // The measured failure: every hop through a career-recognition award is a referenced
    // Wikidata statement at 1.00, so under confidence alone the meaningless route ranked
    // FIRST. Specificity has to lead for that to change, and this is the test that says so.
    PathResult throughAHub = twoHopVia(hub("Q0900201", NodeKind.CONCEPT), 1.00);
    PathResult throughSomethingSpecific = twoHopVia(quiet("Q0900103", NodeKind.CONCEPT), 0.80);

    List<PathResult> ranked =
        PathRanking.rank(List.of(throughAHub, throughSomethingSpecific), DEGREES, NO_INSTITUTIONS);

    assertThat(ranked).containsExactly(throughSomethingSpecific, throughAHub);
  }

  @Test
  @DisplayName("only a CONCEPT intermediate can be a hub; a busy person or group is a connector")
  void onlyConceptsAreHubs() {
    // The reason degree alone is the wrong signal: the highest-degree nodes in a real graph
    // are the expanded seeds themselves, and a route through one of those is a real segue.
    PathResult throughABusyPerson = twoHopVia(hub("Q0900202", NodeKind.PERSON), 1.00);
    PathResult throughABusyGroup = twoHopVia(hub("Q0900203", NodeKind.GROUP), 1.00);
    PathResult throughABusyWork = twoHopVia(hub("Q0900204", NodeKind.WORK), 1.00);
    PathResult throughAHub = twoHopVia(hub("Q0900205", NodeKind.CONCEPT), 1.00);

    List<PathResult> ranked =
        PathRanking.rank(
            List.of(throughAHub, throughABusyPerson, throughABusyGroup, throughABusyWork),
            DEGREES,
            NO_INSTITUTIONS);

    assertThat(ranked)
        .containsExactly(throughABusyPerson, throughABusyGroup, throughABusyWork, throughAHub);
  }

  @Test
  @DisplayName("an endpoint is not an intermediate, so asking about a hub still answers")
  void endpointsAreNotIntermediates() {
    // "What connects me to the Rock and Roll Hall of Fame" is a fair question, and the answer
    // must not be demoted for ending where it was asked to end. Only the nodes a route passes
    // THROUGH are judged for how much they explain. The confidences are the discriminator: if
    // the endpoint counted, both routes would carry one hub and the 1.00 route would come out
    // on top.
    PathResult endingAtAHub =
        new PathResult(List.of(hop(START, hub("Q0900206", NodeKind.CONCEPT), 0.80)));
    PathResult passingThroughAHub = twoHopVia(hub("Q0900207", NodeKind.CONCEPT), 1.00);

    List<PathResult> ranked =
        PathRanking.rank(List.of(passingThroughAHub, endingAtAHub), DEGREES, NO_INSTITUTIONS);

    assertThat(ranked).containsExactly(endingAtAHub, passingThroughAHub);
  }

  @Test
  @DisplayName("specificity never promotes a model guess above a sourced route")
  void specificityStopsAtTheQuarantineLine() {
    // ADR 23 quarantines model-generated edges, and ADR 31 exists so one cannot be the first
    // thing a conversation sees. Specificity outranks confidence, but not across this line: a
    // hub route a real source stands behind still beats a specific route nothing does.
    // Without this the amendment would quietly undo the decision it amends.
    PathResult sourcedThroughAHub = twoHopVia(hub("Q0900208", NodeKind.CONCEPT), 1.00);
    PathResult guessedButSpecific =
        new PathResult(
            List.of(
                hop(
                    START,
                    quiet("Q0900104", NodeKind.CONCEPT),
                    new Provenance("llm:claude", "chat#1", Instant.EPOCH, 0.30))));

    List<PathResult> ranked =
        PathRanking.rank(List.of(guessedButSpecific, sourcedThroughAHub), DEGREES, NO_INSTITUTIONS);

    assertThat(ranked).containsExactly(sourcedThroughAHub, guessedButSpecific);
  }

  @Test
  @DisplayName("with no degrees to consult, the ADR 31 order is unchanged")
  void theNoDegreeOverloadIsNeutral() {
    // Existing callers and the contract tests must not be forced to care. Nothing is a hub
    // when nothing knows any degrees, so ranking falls back to confidence then length.
    PathResult throughAHub = twoHopVia(hub("Q0900209", NodeKind.CONCEPT), 1.00);
    PathResult throughSomethingSpecific = twoHopVia(quiet("Q0900105", NodeKind.CONCEPT), 0.80);

    assertThat(PathRanking.rank(List.of(throughSomethingSpecific, throughAHub)))
        .containsExactly(throughAHub, throughSomethingSpecific);
  }

  @Test
  @DisplayName("among equally specific routes, ADR 31's confidence-then-length order still holds")
  void confidenceStillDecidesWithinASpecificityClass() {
    PathResult hubbyAndStrong = twoHopVia(hub("Q0900210", NodeKind.CONCEPT), 1.00);
    PathResult hubbyAndWeak = twoHopVia(hub("Q0900211", NodeKind.CONCEPT), 0.50);
    PathResult specificAndStrong = twoHopVia(quiet("Q0900106", NodeKind.CONCEPT), 1.00);
    PathResult specificAndWeak = twoHopVia(quiet("Q0900107", NodeKind.CONCEPT), 0.80);

    List<PathResult> ranked =
        PathRanking.rank(
            List.of(hubbyAndWeak, specificAndWeak, hubbyAndStrong, specificAndStrong),
            DEGREES,
            NO_INSTITUTIONS);

    assertThat(ranked)
        .containsExactly(specificAndStrong, specificAndWeak, hubbyAndStrong, hubbyAndWeak);
  }

  // ---- recognition institutions (issue #66) --------------------------------

  @Test
  @DisplayName("a body one is ELECTED to is a hub however quiet it is, and a band never is")
  void recognitionInstitutionsAreHubsWhateverTheirDegree() {
    // Issue #66. The academy is BELOW the degree threshold and the band is above it, which is
    // the measured shape: on a real graph the Writers Guild of America West carries 11 edges
    // and so does Mötley Crüe, so no degree can separate them. The class can.
    PathResult throughAnAcademy = twoHopVia(quiet("Q0900108", NodeKind.GROUP, ELECTED_TO), 1.00);
    PathResult throughABand = twoHopVia(hub("Q0900212", NodeKind.GROUP, PLAYED_IN), 0.80);

    List<PathResult> ranked =
        PathRanking.rank(List.of(throughAnAcademy, throughABand), DEGREES, INSTITUTIONS);

    assertThat(ranked).containsExactly(throughABand, throughAnAcademy);
  }

  @Test
  @DisplayName("the class decides, so an institution the kind mapper never placed is a hub too")
  void theClassIsJudgedWhateverKindItWasMappedTo() {
    // "High-degree CONCEPT" means "we could not place this and half the graph touches it".
    // A stated class means something on its own, so it needs no kind and no degree to back
    // it up — a learned society that fell through the whitelist is still an election.
    PathResult throughAnUnplacedAcademy =
        twoHopVia(quiet("Q0900111", NodeKind.CONCEPT, ELECTED_TO), 1.00);
    PathResult throughAFilm = twoHopVia(quiet("Q0900112", NodeKind.WORK, MADE), 0.80);

    List<PathResult> ranked =
        PathRanking.rank(List.of(throughAnUnplacedAcademy, throughAFilm), DEGREES, INSTITUTIONS);

    assertThat(ranked).containsExactly(throughAFilm, throughAnUnplacedAcademy);
  }

  @Test
  @DisplayName("an academy at the END of a route is not demoted, any more than a hub award is")
  void anInstitutionEndpointIsNotAnIntermediate() {
    // The same exemption ADR 31's amendment granted a hub: "what connects me to the Royal
    // Society" is a fair question. The confidences are the discriminator — if the endpoint
    // counted, both routes would carry one institution and the 1.00 route would win.
    PathResult endingAtAnAcademy =
        new PathResult(List.of(hop(START, quiet("Q0900113", NodeKind.GROUP, ELECTED_TO), 0.80)));
    PathResult passingThroughAnAcademy =
        twoHopVia(quiet("Q0900114", NodeKind.GROUP, ELECTED_TO), 1.00);

    List<PathResult> ranked =
        PathRanking.rank(
            List.of(passingThroughAnAcademy, endingAtAnAcademy), DEGREES, INSTITUTIONS);

    assertThat(ranked).containsExactly(endingAtAnAcademy, passingThroughAnAcademy);
  }

  @Test
  @DisplayName("a node states several classes, and one recognition class among them is enough")
  void oneRecognitionClassAmongSeveralIsEnough() {
    // Measured: every institution in the graph also wears a broad organization class, and the
    // recognition one is not always first — the American Academy of Arts and Sciences states
    // learned society, academic publisher, nonprofit organization, in that order.
    PathResult throughAnAcademy =
        twoHopVia(quiet("Q0900115", NodeKind.GROUP, PLAYED_IN, MADE, ELECTED_TO), 1.00);
    PathResult throughABand = twoHopVia(hub("Q0900213", NodeKind.GROUP, PLAYED_IN), 0.80);

    List<PathResult> ranked =
        PathRanking.rank(List.of(throughAnAcademy, throughABand), DEGREES, INSTITUTIONS);

    assertThat(ranked).containsExactly(throughABand, throughAnAcademy);
  }

  // ---- the vocabulary that does not exist yet (issue #88) -------------------

  @Test
  @DisplayName("an aboutness hub is a busy CONCEPT, so the degree rule already covers it")
  void aSubjectHubIsAlreadyCovered() {
    // Issue #78 wants P921 "main subject", and P136 genre carries 16,552 items for "science
    // fiction" alone. Demonstrated here rather than waited for: a subject node is a CONCEPT and
    // it is busy by construction, which is exactly the shape issue #52 measured on awards. The
    // property is not registered and this rule does not care - it judges the intermediate, not
    // the relation, so it will hold the day the vocabulary widens.
    PathResult throughASubject = twoHopVia(hub("Q0900216", NodeKind.CONCEPT), 1.00);
    PathResult throughTheBookItself = twoHopVia(quiet("Q0900118", NodeKind.WORK), 0.80);

    List<PathResult> ranked =
        PathRanking.rank(List.of(throughASubject, throughTheBookItself), DEGREES, INSTITUTIONS);

    assertThat(ranked).containsExactly(throughTheBookItself, throughASubject);
  }

  @Test
  @DisplayName("a place hub is invisible to both rules, and that is issue #78's to fix")
  void aPlaceHubIsNotCoveredByEitherRule() {
    // The gap, pinned so the next person finds it rather than rediscovers it (issue #88). P131
    // "located in" makes "both are in New York" a route, and New York is a PLACE: the degree
    // rule is CONCEPT-only on purpose, and a city states no class that means "elected to". So
    // the place route wins here on confidence, which is the #52 failure returning in a kind
    // nobody has had to think about yet.
    //
    // Deliberately not fixed by widening the degree rule to PLACE. The real graph holds exactly
    // ONE place - New York City, at a single edge - because nothing in the vocabulary relates
    // anything to a location, so the rule would be a no-op against everything that exists. That
    // is the reason ADR 31's third amendment refused a rule for edition nodes, and it is the
    // reason this one belongs with the property that creates the problem.
    PathResult throughAPlace = twoHopVia(hub("Q0900217", NodeKind.PLACE), 1.00);
    PathResult throughTheFilmTheyMade = twoHopVia(quiet("Q0900119", NodeKind.WORK), 0.80);

    List<PathResult> ranked =
        PathRanking.rank(List.of(throughAPlace, throughTheFilmTheyMade), DEGREES, INSTITUTIONS);

    assertThat(ranked).containsExactly(throughAPlace, throughTheFilmTheyMade);
  }

  // ---- the judgement, borrowed (ADR 45) -------------------------------------

  @Test
  @DisplayName("the hub judgement is available on its own, and answers the same way")
  void theHubJudgementIsAvailableOnItsOwn() {
    // The recommender needs the same question answered about a candidate intermediate before it
    // ever builds a route, and it must not reimplement it - a second copy of this rule would let
    // a hall of fame back into recommendations while routing kept excluding it.
    assertThat(PathRanking.isHub(hub("Q0900214", NodeKind.CONCEPT), DEGREES, NO_INSTITUTIONS))
        .isTrue();
    assertThat(PathRanking.isHub(quiet("Q0900116", NodeKind.CONCEPT), DEGREES, NO_INSTITUTIONS))
        .isFalse();
    assertThat(PathRanking.isHub(hub("Q0900215", NodeKind.GROUP), DEGREES, NO_INSTITUTIONS))
        .isFalse();
    assertThat(
            PathRanking.isHub(
                quiet("Q0900117", NodeKind.GROUP, PLAYED_IN, ELECTED_TO), DEGREES, INSTITUTIONS))
        .isTrue();
  }

  // ---- specificity helpers --------------------------------------------------

  /**
   * Invented identifiers, as ADR 22 requires of test sources: everything in the {@code Q9002xx}
   * range is a busy node and everything in {@code Q9001xx} is a quiet one, so the degree lookup is
   * a pure function of the qid and no test has to arrange one.
   */
  private static final ToIntFunction<String> DEGREES =
      qid -> qid.startsWith("Q09002") ? PathRanking.HUB_DEGREE : PathRanking.HUB_DEGREE - 1;

  /**
   * Invented classes too, for the same reason (issue #66): the domain is told which classes mean
   * "elected, not collaborating" through a {@code Predicate}, so its own tests need no Wikidata
   * vocabulary at all. The real class table is pinned by {@code RecognitionInstitutionsTest}.
   */
  private static final String ELECTED_TO = "Q0900801";

  private static final String PLAYED_IN = "Q0900802";
  private static final String MADE = "Q0900803";

  private static final java.util.function.Predicate<String> INSTITUTIONS = ELECTED_TO::equals;

  /** No class means recognition, so ranking is the issue-#52 order exactly. */
  private static final java.util.function.Predicate<String> NO_INSTITUTIONS = classQid -> false;

  private static final NodeRecord START = new NodeRecord("Q0900101", NodeKind.PERSON, "start");
  private static final NodeRecord END = new NodeRecord("Q0900102", NodeKind.PERSON, "end");

  private static NodeRecord hub(String qid, NodeKind kind, String... statedClasses) {
    return new NodeRecord(qid, kind, "busy " + qid, List.of(statedClasses));
  }

  private static NodeRecord quiet(String qid, NodeKind kind, String... statedClasses) {
    return new NodeRecord(qid, kind, "quiet " + qid, List.of(statedClasses));
  }

  /** START -> the given intermediate -> END, both hops at the same confidence. */
  private static PathResult twoHopVia(NodeRecord middle, double confidence) {
    return new PathResult(List.of(hop(START, middle, confidence), hop(middle, END, confidence)));
  }

  private static Hop hop(NodeRecord from, NodeRecord to, double confidence) {
    return hop(from, to, new Provenance("wikidata", "S-" + from.qid(), Instant.EPOCH, confidence));
  }

  private static Hop hop(NodeRecord from, NodeRecord to, Provenance source) {
    EdgeRecord edge = new EdgeRecord(from.qid(), to.qid(), "REL", null, null, List.of(source));
    return new Hop(from, edge, to, false);
  }

  /** A path whose hops carry the given confidences, one edge per hop. */
  private static PathResult path(double... hopConfidences) {
    List<Hop> hops =
        IntStream.range(0, hopConfidences.length)
            .mapToObj(
                i -> {
                  NodeRecord from = new NodeRecord("Q" + i, NodeKind.CONCEPT, "n" + i);
                  NodeRecord to = new NodeRecord("Q" + (i + 1), NodeKind.CONCEPT, "n" + (i + 1));
                  EdgeRecord edge =
                      new EdgeRecord(
                          from.qid(),
                          to.qid(),
                          "REL",
                          null,
                          null,
                          List.of(
                              new Provenance(
                                  "src" + i, "ref" + i, Instant.EPOCH, hopConfidences[i])));
                  return new Hop(from, edge, to, false);
                })
            .toList();
    return new PathResult(hops);
  }
}
