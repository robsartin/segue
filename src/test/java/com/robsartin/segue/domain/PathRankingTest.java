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
    PathResult throughAHub = twoHopVia(hub("Q900201", NodeKind.CONCEPT), 1.00);
    PathResult throughSomethingSpecific = twoHopVia(quiet("Q900103", NodeKind.CONCEPT), 0.80);

    List<PathResult> ranked =
        PathRanking.rank(List.of(throughAHub, throughSomethingSpecific), DEGREES);

    assertThat(ranked).containsExactly(throughSomethingSpecific, throughAHub);
  }

  @Test
  @DisplayName("only a CONCEPT intermediate can be a hub; a busy person or group is a connector")
  void onlyConceptsAreHubs() {
    // The reason degree alone is the wrong signal: the highest-degree nodes in a real graph
    // are the expanded seeds themselves, and a route through one of those is a real segue.
    PathResult throughABusyPerson = twoHopVia(hub("Q900202", NodeKind.PERSON), 1.00);
    PathResult throughABusyGroup = twoHopVia(hub("Q900203", NodeKind.GROUP), 1.00);
    PathResult throughABusyWork = twoHopVia(hub("Q900204", NodeKind.WORK), 1.00);
    PathResult throughAHub = twoHopVia(hub("Q900205", NodeKind.CONCEPT), 1.00);

    List<PathResult> ranked =
        PathRanking.rank(
            List.of(throughAHub, throughABusyPerson, throughABusyGroup, throughABusyWork), DEGREES);

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
        new PathResult(List.of(hop(START, hub("Q900206", NodeKind.CONCEPT), 0.80)));
    PathResult passingThroughAHub = twoHopVia(hub("Q900207", NodeKind.CONCEPT), 1.00);

    List<PathResult> ranked = PathRanking.rank(List.of(passingThroughAHub, endingAtAHub), DEGREES);

    assertThat(ranked).containsExactly(endingAtAHub, passingThroughAHub);
  }

  @Test
  @DisplayName("specificity never promotes a model guess above a sourced route")
  void specificityStopsAtTheQuarantineLine() {
    // ADR 23 quarantines model-generated edges, and ADR 31 exists so one cannot be the first
    // thing a conversation sees. Specificity outranks confidence, but not across this line: a
    // hub route a real source stands behind still beats a specific route nothing does.
    // Without this the amendment would quietly undo the decision it amends.
    PathResult sourcedThroughAHub = twoHopVia(hub("Q900208", NodeKind.CONCEPT), 1.00);
    PathResult guessedButSpecific =
        new PathResult(
            List.of(
                hop(
                    START,
                    quiet("Q900104", NodeKind.CONCEPT),
                    new Provenance("llm:claude", "chat#1", Instant.EPOCH, 0.30))));

    List<PathResult> ranked =
        PathRanking.rank(List.of(guessedButSpecific, sourcedThroughAHub), DEGREES);

    assertThat(ranked).containsExactly(sourcedThroughAHub, guessedButSpecific);
  }

  @Test
  @DisplayName("with no degrees to consult, the ADR 31 order is unchanged")
  void theNoDegreeOverloadIsNeutral() {
    // Existing callers and the contract tests must not be forced to care. Nothing is a hub
    // when nothing knows any degrees, so ranking falls back to confidence then length.
    PathResult throughAHub = twoHopVia(hub("Q900209", NodeKind.CONCEPT), 1.00);
    PathResult throughSomethingSpecific = twoHopVia(quiet("Q900105", NodeKind.CONCEPT), 0.80);

    assertThat(PathRanking.rank(List.of(throughSomethingSpecific, throughAHub)))
        .containsExactly(throughAHub, throughSomethingSpecific);
  }

  @Test
  @DisplayName("among equally specific routes, ADR 31's confidence-then-length order still holds")
  void confidenceStillDecidesWithinASpecificityClass() {
    PathResult hubbyAndStrong = twoHopVia(hub("Q900210", NodeKind.CONCEPT), 1.00);
    PathResult hubbyAndWeak = twoHopVia(hub("Q900211", NodeKind.CONCEPT), 0.50);
    PathResult specificAndStrong = twoHopVia(quiet("Q900106", NodeKind.CONCEPT), 1.00);
    PathResult specificAndWeak = twoHopVia(quiet("Q900107", NodeKind.CONCEPT), 0.80);

    List<PathResult> ranked =
        PathRanking.rank(
            List.of(hubbyAndWeak, specificAndWeak, hubbyAndStrong, specificAndStrong), DEGREES);

    assertThat(ranked)
        .containsExactly(specificAndStrong, specificAndWeak, hubbyAndStrong, hubbyAndWeak);
  }

  // ---- specificity helpers --------------------------------------------------

  /**
   * Invented identifiers, as ADR 22 requires of test sources: everything in the {@code Q9002xx}
   * range is a busy node and everything in {@code Q9001xx} is a quiet one, so the degree lookup is
   * a pure function of the qid and no test has to arrange one.
   */
  private static final ToIntFunction<String> DEGREES =
      qid -> qid.startsWith("Q9002") ? PathRanking.HUB_DEGREE : PathRanking.HUB_DEGREE - 1;

  private static final NodeRecord START = new NodeRecord("Q900101", NodeKind.PERSON, "start");
  private static final NodeRecord END = new NodeRecord("Q900102", NodeKind.PERSON, "end");

  private static NodeRecord hub(String qid, NodeKind kind) {
    return new NodeRecord(qid, kind, "busy " + qid);
  }

  private static NodeRecord quiet(String qid, NodeKind kind) {
    return new NodeRecord(qid, kind, "quiet " + qid);
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
