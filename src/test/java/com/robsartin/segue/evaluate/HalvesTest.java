package com.robsartin.segue.evaluate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The four cells the age split adds to one row, and the two ways they could stop meaning it. */
class HalvesTest {

  @Test
  @DisplayName("every cell adds, and the split flag is carried through")
  void shouldAddEveryCellWhenTwoSplitFoldsAreAdded() {
    Halves first = new Halves(true, 30, 3, 10, 1);
    Halves second = new Halves(true, 28, 2, 9, 0);

    assertThat(first.plus(second)).isEqualTo(new Halves(true, 58, 5, 19, 1));
  }

  @Test
  @DisplayName("a reading with no split cannot carry a count in either half")
  void shouldRefuseTheCellsWhenThereIsNoSplitToCountThem() {
    assertThatThrownBy(() -> new Halves(false, 1, 0, 0, 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("no age split");
  }

  @Test
  @DisplayName("a split reading and an unsplit one cannot be added, because one run is one or none")
  void shouldRefuseTheAdditionWhenOneSideIsSplitAndTheOtherIsNot() {
    assertThatThrownBy(() -> new Halves(true, 1, 0, 0, 0).plus(Halves.UNSPLIT))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("one run is split");
  }
}
