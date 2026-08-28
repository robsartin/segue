package com.robsartin.segue.recommend;

import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.segue.domain.EdgeRecord;
import com.robsartin.segue.domain.Hop;
import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.domain.NodeRecord;
import com.robsartin.segue.domain.PathResult;
import com.robsartin.segue.domain.Provenance;
import com.robsartin.segue.domain.Recommendation;
import com.robsartin.segue.domain.Scorer;
import com.robsartin.segue.domain.SharedIntermediate;
import java.io.IOException;
import java.io.StringWriter;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** What a person actually reads, and the two things the file has to say about itself (ADR 45). */
class RecommendationReportTest {

  private static final NodeRecord KNOWN = new NodeRecord("Q900101", NodeKind.GROUP, "one you know");
  private static final NodeRecord VIA =
      new NodeRecord("Q900201", NodeKind.PERSON, "the artist they cite");
  private static final NodeRecord CANDIDATE =
      new NodeRecord("Q900301", NodeKind.GROUP, "the invented ancestors");

  private static final Recommendation RECOMMENDED =
      new Recommendation(
          CANDIDATE, 0.6627, 80, List.of(new SharedIntermediate(KNOWN.qid(), VIA.qid(), 4, 1.0)));

  private static Hop hop(NodeRecord from, NodeRecord to) {
    EdgeRecord edge =
        new EdgeRecord(
            from.qid(),
            to.qid(),
            "INFLUENCED_BY",
            null,
            null,
            List.of(new Provenance("invented", "invented:1", Instant.EPOCH, 1.0)));
    return new Hop(from, edge, to, false);
  }

  private static final PathResult ROUTE =
      new PathResult(List.of(hop(KNOWN, VIA), hop(VIA, CANDIDATE)));

  private static String report(List<Explained> explained, Sweep sweep) throws IOException {
    StringWriter out = new StringWriter();
    RecommendationReport.write(sweep, explained, Scorer.LIFT, 12, out);
    return out.toString();
  }

  private static Sweep sweep() {
    return new Sweep(List.of(RECOMMENDED), 815, 0, 41);
  }

  @Test
  @DisplayName("the file says on its first line that it is personal data")
  void theFileNamesItselfAsPersonalData() throws IOException {
    String written = report(List.of(new Explained(RECOMMENDED, List.of(ROUTE))), sweep());

    assertThat(written.lines().findFirst().orElseThrow())
        .isEqualTo(RecommendationReport.PERSONAL_DATA_HEADER);
  }

  @Test
  @DisplayName("the header states how it was scored and what it looked at")
  void theHeaderStatesHowItWasScored() throws IOException {
    String written = report(List.of(new Explained(RECOMMENDED, List.of(ROUTE))), sweep());

    assertThat(written).contains(Scorer.LIFT.describe());
    assertThat(written).contains("815");
    assertThat(written).contains("41");
    assertThat(written).contains("12");
  }

  @Test
  @DisplayName("a candidate is a rank, a score, a name, and the shape of its evidence")
  void aCandidateLineCarriesItsNumbers() throws IOException {
    String written = report(List.of(new Explained(RECOMMENDED, List.of(ROUTE))), sweep());

    assertThat(written).contains("the invented ancestors");
    assertThat(written).contains("Q900301");
    assertThat(written).contains("0.66");
    assertThat(written).contains("80 edges");
    assertThat(written).contains("1 of yours");
  }

  @Test
  @DisplayName("each route says which of your things it starts from, before the hops")
  void eachRouteNamesTheThingYouKnow() throws IOException {
    // The hops read in either direction — "U2 <-[INFLUENCED_BY]- the candidate" is a route that
    // starts at U2 — so the one thing a reader needs stated is which end is theirs.
    String written = report(List.of(new Explained(RECOMMENDED, List.of(ROUTE))), sweep());

    assertThat(written).contains("from one you know (Q900101):");
  }

  @Test
  @DisplayName("the explanation is the route, hop by hop, with its citations")
  void theExplanationIsTheRoute() throws IOException {
    String written = report(List.of(new Explained(RECOMMENDED, List.of(ROUTE))), sweep());

    assertThat(written).contains("one you know -[INFLUENCED_BY]-> the artist they cite");
    assertThat(written).contains("the artist they cite -[INFLUENCED_BY]-> the invented ancestors");
    assertThat(written).contains("invented:1");
  }

  @Test
  @DisplayName("a candidate the traversal could not explain says so rather than showing nothing")
  void anUnexplainedCandidateSaysSo() throws IOException {
    String written = report(List.of(new Explained(RECOMMENDED, List.of())), sweep());

    assertThat(written).contains(RecommendationReport.NO_ROUTE);
  }

  @Test
  @DisplayName("nothing to recommend is a sentence, not an empty file")
  void nothingToRecommendIsASentence() throws IOException {
    String written = report(List.of(), new Sweep(List.of(), 815, 0, 41));

    assertThat(written).contains(RecommendationReport.NOTHING_FOUND);
  }
}
