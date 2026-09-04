package com.robsartin.segue.census;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.export.LogProjection;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Counted by hand off {@code InventedCensus.log()}.
 *
 * <p>The fold holds thirteen nodes: seven the sources claimed, minus the one row 30 retracts, plus
 * the three the owner minted, plus a stand-in for each of the four merges that stand — the merge at
 * row 28 is superseded with nothing naming its canonical id, so it names no stand-in at all.
 *
 * <p>{@code NEIGHBOUR} is claimed {@code PERSON} and states a class no whitelist knows, so both
 * folds re-derive it to {@code CONCEPT} (ADR 42). That is the one kind in this fixture that is not
 * the kind its claim states, and it is here on purpose: a census that read the claim rather than
 * the fold would report {@code PERSON} and disagree with the exported picture.
 */
class NodeCensusTest {

  private static final LogProjection PROJECTION =
      LogProjection.of(new InventedCensus.FakeAssertionLog().with(InventedCensus.log()));

  @Test
  @DisplayName("the total is every node the fold holds, stand-ins and minted ids included")
  void shouldCountEveryNodeInTheFoldWhenTheLogIsCounted() {
    assertThat(NodeCensus.of(PROJECTION).total()).isEqualTo(13);
  }

  @Test
  @DisplayName("every kind gets a count, including the ones no node has")
  void shouldCountEveryKindWhenSomeKindsAreEmpty() {
    assertThat(NodeCensus.of(PROJECTION).byKind())
        .containsExactly(
            entry(NodeKind.PERSON, 3),
            entry(NodeKind.GROUP, 1),
            entry(NodeKind.WORK, 8),
            entry(NodeKind.PLACE, 0),
            entry(NodeKind.EVENT, 0),
            entry(NodeKind.CONCEPT, 1));
  }
}
