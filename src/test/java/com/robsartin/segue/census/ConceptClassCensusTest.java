package com.robsartin.segue.census;

import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.segue.census.ConceptClassCensus.ConceptClass;
import com.robsartin.segue.domain.LoggedAssertion;
import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.domain.NodeRecord;
import com.robsartin.segue.export.LogProjection;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Small logs of this section's own, folded — the precedent {@code EdgeCensusTest} set for a case
 * {@code InventedCensus.log()} cannot reach without renumbering every hand-counted expectation in
 * the package.
 */
class ConceptClassCensusTest {

  private static final String A_NODE = "Q0900211";
  private static final String ANOTHER_NODE = "Q0900212";
  private static final String A_THIRD_NODE = "Q0900213";

  private static final String CLASS_ONE = "Q0900302";
  private static final String CLASS_TWO = "Q0900303";

  /** A second invented class, so the other-kind test does not repeat {@link #CLASS_ONE}. */
  private static final String PERSON_CLASS = "Q0900304";

  private static LogProjection fold(LoggedAssertion... claims) {
    return LogProjection.of(new InventedCensus.FakeAssertionLog().with(claims));
  }

  @Test
  @DisplayName("a node counts once for each class it states, and once for one it states twice")
  void shouldCountANodeOncePerClassWhenItStatesSeveralAndRepeatsOne() {
    LogProjection projection =
        fold(
            InventedCensus.node(
                A_NODE, NodeKind.CONCEPT, "an invented thing", List.of(CLASS_ONE, CLASS_TWO)),
            InventedCensus.node(
                ANOTHER_NODE,
                NodeKind.CONCEPT,
                "another invented thing",
                List.of(CLASS_ONE, CLASS_ONE)));

    assertThat(ConceptClassCensus.of(projection).top())
        .as("a row is nodes stating the class, so the repeat is one node and not two")
        .containsExactly(new ConceptClass(CLASS_ONE, 2), new ConceptClass(CLASS_TWO, 1));
  }

  @Test
  @DisplayName("a class stated by a node of another kind is not counted")
  void shouldCountConceptNodesAloneWhenAnotherKindStatesAClass() {
    // Built directly rather than folded: any class this fictitious re-derives EVERY kind to
    // CONCEPT (ADR 42, KindMapper.rederive), so a folded log cannot state a class on a node that
    // stays some other kind — NodeCensusTest's NEIGHBOUR is exactly that case. Building the
    // projection by hand isolates this section's own kind filter from that re-derivation, which
    // is NodeCensusTest's and KindMapperTest's to cover.
    LogProjection projection =
        new LogProjection(
            Map.of(
                A_NODE,
                new NodeRecord(A_NODE, NodeKind.CONCEPT, "an invented thing", List.of(CLASS_ONE)),
                ANOTHER_NODE,
                new NodeRecord(
                    ANOTHER_NODE, NodeKind.PERSON, "an invented person", List.of(PERSON_CLASS))),
            List.of(),
            0,
            0);

    assertThat(ConceptClassCensus.of(projection).top())
        .as("the section counts CONCEPT nodes only, whatever the other node's own class is")
        .containsExactly(new ConceptClass(CLASS_ONE, 1));
  }

  @Test
  @DisplayName("a concept node that states no class is counted on its own line, not on a row")
  void shouldCountNodesStatingNoClassSeparatelyWhenAConceptNodeStatesNone() {
    LogProjection projection =
        fold(
            InventedCensus.node(A_NODE, NodeKind.CONCEPT, "an invented thing", List.of(CLASS_ONE)),
            InventedCensus.node(ANOTHER_NODE, NodeKind.CONCEPT, "an unclassified thing"));

    ConceptClassCensus counted = ConceptClassCensus.of(projection);

    assertThat(counted.statingNoClass())
        .as("no whitelist entry could ever reach a node whose source stated no class")
        .isEqualTo(1);
    assertThat(counted.top()).containsExactly(new ConceptClass(CLASS_ONE, 1));
  }

  /**
   * Eleven classes on one node, so the cut has something to cut and every tie is a real tie. The
   * twelfth claim gives the highest qid the highest count, which is the only way to tell an order
   * by count from an order by id.
   */
  private static final List<String> ELEVEN_CLASSES =
      List.of(
          "Q0900311",
          "Q0900312",
          "Q0900313",
          "Q0900314",
          "Q0900315",
          "Q0900316",
          "Q0900317",
          "Q0900318",
          "Q0900319",
          "Q0900320",
          "Q0900321");

  private static LogProjection elevenClassesOneOfThemTwice() {
    return fold(
        InventedCensus.node(A_NODE, NodeKind.CONCEPT, "an invented thing", ELEVEN_CLASSES),
        InventedCensus.node(
            A_THIRD_NODE, NodeKind.CONCEPT, "a third invented thing", List.of("Q0900321")));
  }

  @Test
  @DisplayName("the rows are the ten commonest classes, count first and qid on a tie")
  void shouldOrderByCountThenQidAndKeepTenWhenElevenClassesAreStated() {
    assertThat(ConceptClassCensus.of(elevenClassesOneOfThemTwice()).top())
        .as(
            "Q0900321 is the highest qid and the only class with two nodes, so an order by qid"
                + " would put it last and the cut would keep it; count first puts it first, and"
                + " the tie-break then drops Q0900320 as the highest of the ones left")
        .containsExactly(
            new ConceptClass("Q0900321", 2),
            new ConceptClass("Q0900311", 1),
            new ConceptClass("Q0900312", 1),
            new ConceptClass("Q0900313", 1),
            new ConceptClass("Q0900314", 1),
            new ConceptClass("Q0900315", 1),
            new ConceptClass("Q0900316", 1),
            new ConceptClass("Q0900317", 1),
            new ConceptClass("Q0900318", 1),
            new ConceptClass("Q0900319", 1));
  }

  @Test
  @DisplayName("every distinct class is counted, including the ones the cut dropped")
  void shouldCountEveryDistinctClassWhenMoreAreStatedThanAreShown() {
    assertThat(ConceptClassCensus.of(elevenClassesOneOfThemTwice()).distinctClasses())
        .as("without this the reader cannot tell a whole distribution from a truncated one")
        .isEqualTo(11);
  }
}
