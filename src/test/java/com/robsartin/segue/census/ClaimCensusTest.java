package com.robsartin.segue.census;

import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.segue.export.LogProjection;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Counted by hand off {@code InventedCensus.log()}, thirty rows.
 *
 * <p>One retraction, at row 30, reaching two rows: the node claim at row 4 and the edge at row 12
 * that names the retracted entity. Three minted entities. Five surviving merges: rows 25, 26 and 29
 * stand, rows 23 and 28 are superseded, and of those two only row 23's canonical id is named by a
 * surviving edge — the owner edge at row 24, claimed against it while it stood. So four canonical
 * ids get a stand-in, and one of the four ends with no edge at all.
 */
class ClaimCensusTest {

  private static final ClaimCensus CENSUS =
      ClaimCensus.of(
          InventedCensus.log(),
          LogProjection.of(new InventedCensus.FakeAssertionLog().with(InventedCensus.log())));

  @Test
  @DisplayName("every row in the log is counted, retractions and superseded merges included")
  void shouldCountEveryRowWhenTheLogIsRead() {
    assertThat(CENSUS.rows()).isEqualTo(30);
  }

  @Test
  @DisplayName("one retraction naming one entity reaches more rows than itself")
  void shouldCountTheRowsARetractionReachesWhenItNamesANodeAndAnEdge() {
    assertThat(CENSUS.retractions()).isEqualTo(1);
    assertThat(CENSUS.entitiesRetracted()).isEqualTo(1);
    assertThat(CENSUS.rowsRetracted()).isEqualTo(2);
  }

  @Test
  @DisplayName("the owner's own minted rows are counted apart from the sources' node claims")
  void shouldCountTheMintedRowsWhenTheOwnerHasClaimedEntitiesOfHisOwn() {
    assertThat(CENSUS.localEntitiesMinted()).isEqualTo(3);
  }

  @Test
  @DisplayName("a superseded merge whose canonical id an edge still names is counted apart")
  void shouldSplitTheMergesWhenOneCorrectionLeavesAnEdgeBehindAndOneDoesNot() {
    assertThat(CENSUS.mergesStanding()).isEqualTo(3);
    assertThat(CENSUS.mergesSuperseded()).isEqualTo(2);
    assertThat(CENSUS.mergesSupersededButEdgeReferenced()).isEqualTo(1);
  }

  @Test
  @DisplayName("a merge no edge names and no later merge keeps gets no stand-in at all")
  void shouldCountOnlyTheStandingStandInsWhenAMergeWasCorrectedAway() {
    assertThat(CENSUS.standIns()).isEqualTo(4);
    assertThat(CENSUS.standInsWithNoEdge()).isEqualTo(1);
  }
}
