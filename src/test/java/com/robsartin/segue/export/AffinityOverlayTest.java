package com.robsartin.segue.export;

import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.segue.domain.AffinityRecord;
import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.port.AffinityStore;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Invented people, invented ratings. CLAUDE.md is explicit: ratings and notes in test fixtures, ADR
 * examples and commit messages must be invented, not anyone's real taste (ADR 33, issue #37).
 */
class AffinityOverlayTest {

  private static final Instant WHEN = Instant.parse("2026-01-01T00:00:00Z");

  private static final class FakeAffinityStore implements AffinityStore {

    private final Map<String, AffinityRecord> ratings = new HashMap<>();

    FakeAffinityStore rating(String qid, int rating) {
      ratings.put(qid, new AffinityRecord(qid, rating, null, WHEN));
      return this;
    }

    @Override
    public void put(AffinityRecord affinity) {
      ratings.put(affinity.qid(), affinity);
    }

    @Override
    public Optional<AffinityRecord> find(String qid) {
      return Optional.ofNullable(ratings.get(qid));
    }

    /**
     * Deliberately unusable. The overlay asks about exactly the entities already in the picture and
     * about nothing else (ADR 41), and ADR 43 gave the bulk read to one dev tool that is not this
     * one. A fake that answered it would let that stop being true without failing anything.
     */
    @Override
    public List<AffinityRecord> readAll() {
      throw new UnsupportedOperationException("the exporter never reads the whole taste layer");
    }

    /** Unusable for the same reason, and issue #85 did not change it: the overlay asks per node. */
    @Override
    public Map<String, Integer> readRatings() {
      throw new UnsupportedOperationException("the exporter never reads the whole taste layer");
    }

    @Override
    public void close() {}
  }

  private static GraphView twoNodes() {
    return new GraphView(
        "a made-up view",
        List.of(
            new ViewNode("Q900101", NodeKind.PERSON, "Wren Alderman"),
            new ViewNode("Q900102", NodeKind.GROUP, "The Paper Kettles")),
        List.of(new ViewEdge("Q900101", "Q900102", "MEMBER_OF", 1.0, "invented")));
  }

  @Test
  @DisplayName("a rated entity gains its rating; an unrated one gains nothing")
  void addsRatingsWhereTheyExist() {
    GraphView view =
        new AffinityOverlay(new FakeAffinityStore().rating("Q900101", 4)).applyTo(twoNodes());

    assertThat(view.nodes().get(0).affinity()).isEqualTo(4);
    assertThat(view.nodes().get(1).affinity()).isNull();
  }

  @Test
  @DisplayName("the overlay changes nothing else about the view")
  void leavesTheRestOfTheViewAlone() {
    GraphView before = twoNodes();
    GraphView after =
        new AffinityOverlay(new FakeAffinityStore().rating("Q900101", 4)).applyTo(before);

    assertThat(after.description()).isEqualTo(before.description());
    assertThat(after.edges()).isEqualTo(before.edges());
    assertThat(after.nodes()).extracting(ViewNode::qid).isEqualTo(List.of("Q900101", "Q900102"));
    assertThat(before.carriesAffinity()).as("the input view is not mutated").isFalse();
  }

  @Test
  @DisplayName("a view with no rated entity still counts as carrying affinity nowhere")
  void addsNothingWhenNothingIsRated() {
    GraphView view = new AffinityOverlay(new FakeAffinityStore()).applyTo(twoNodes());

    assertThat(view.carriesAffinity()).isFalse();
  }

  @Test
  @DisplayName(
      "the warning names ADR 33 and issue #37, because that is what the operator must act on")
  void warnsInTermsOfTheDecisionItBreaks() {
    assertThat(AffinityOverlay.PERSONAL_DATA_WARNING)
        .contains("personal data")
        .contains("ADR 33")
        .contains("#37");
  }
}
