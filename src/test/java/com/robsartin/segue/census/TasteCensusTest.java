package com.robsartin.segue.census;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import com.robsartin.segue.domain.Fold;
import com.robsartin.segue.domain.LoggedAssertion;
import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.export.LogProjection;
import com.robsartin.segue.wikidata.KindMapper;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Counted by hand off {@code InventedCensus.log()} and eight invented ratings.
 *
 * <p>Two sit on ids the owner minted, one on a stand-in a merge named, and one on the entity row 30
 * retracts.
 */
class TasteCensusTest {

  private static final LogProjection PROJECTION =
      LogProjection.of(new InventedCensus.FakeAssertionLog().with(InventedCensus.log()));

  private static final Fold FOLD = Fold.of(InventedCensus.log(), KindMapper::rederive);

  private static final TasteCensus CENSUS =
      TasteCensus.of(
          new InventedCensus.FakeAffinityStore()
              .rated(InventedCensus.WREN, 5)
              .rated(InventedCensus.SETTLED, 5)
              .rated(InventedCensus.HOLLOW, 4)
              .rated(InventedCensus.PRIZE, 4)
              .rated(InventedCensus.LEDGER, 3)
              .rated(InventedCensus.DOUBLE, 2)
              .rated(InventedCensus.NEIGHBOUR, 2)
              .rated(InventedCensus.GONE, 1)
              .readRatings(),
          FOLD,
          PROJECTION);

  @Test
  @DisplayName("every score gets a row, and they sum to the number of ratings")
  void shouldCountEveryScoreWhenTheTasteLayerIsRead() {
    assertThat(CENSUS.total()).isEqualTo(8);
    assertThat(CENSUS.byScore())
        .containsExactly(entry(1, 1), entry(2, 2), entry(3, 1), entry(4, 2), entry(5, 2));
  }

  @Test
  @DisplayName("a rating on an id the owner minted is counted apart from one on a stand-in")
  void shouldSplitTheRatingsWhenSomeSitOnIdsNoSourceCanAllocate() {
    assertThat(CENSUS.onALocalId()).isEqualTo(2);
    assertThat(CENSUS.onAStandIn()).isEqualTo(1);
  }

  @Test
  @DisplayName("a rating on a retracted entity is counted, because a rating outlives the graph")
  void shouldCountTheRatingWhenItsEntityHasBeenRetracted() {
    assertThat(CENSUS.onARetractedId()).isEqualTo(1);
  }

  @Test
  @DisplayName("a rating on an entity retracted and then claimed again is not counted as retracted")
  void shouldNotCountTheRatingWhenTheRetractedEntityWasClaimedAgain() {
    List<LoggedAssertion> readded =
        List.of(
            InventedCensus.node(InventedCensus.WREN, NodeKind.PERSON, InventedCensus.WREN_LABEL),
            InventedCensus.retract(InventedCensus.WREN),
            InventedCensus.node(InventedCensus.WREN, NodeKind.PERSON, InventedCensus.WREN_LABEL));

    TasteCensus census =
        TasteCensus.of(
            new InventedCensus.FakeAffinityStore().rated(InventedCensus.WREN, 5).readRatings(),
            Fold.of(readded, KindMapper::rederive),
            LogProjection.of(new InventedCensus.FakeAssertionLog().with(readded)));

    assertThat(census.onARetractedId())
        .as("the log still holds the retraction row forever; the fold holds the entity again")
        .isZero();
  }
}
