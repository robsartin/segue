package com.robsartin.segue.evaluate;

import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.segue.domain.Recommendations;
import com.robsartin.segue.domain.Scorer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SettingTest {

  @Test
  @DisplayName("the grid is every scorer against every floor, scorer-major and floor-ascending")
  void shouldPairEveryScorerWithEveryFloorWhenTheGridIsBuilt() {
    assertThat(Setting.GRID)
        .hasSize(Scorer.values().length * Setting.FLOORS.size())
        .startsWith(new Setting(Scorer.values()[0], Setting.FLOORS.get(0)))
        .endsWith(
            new Setting(
                Scorer.values()[Scorer.values().length - 1],
                Setting.FLOORS.get(Setting.FLOORS.size() - 1)));
    assertThat(Setting.GRID.stream().map(Setting::scorer).distinct())
        .containsExactly(Scorer.values());
  }

  @Test
  @DisplayName("the floors ascend and include the one the recommender ships with")
  void shouldIncludeTheShippedFloorWhenTheFloorsAreListed() {
    assertThat(Setting.FLOORS).isSorted().doesNotHaveDuplicates();
    assertThat(Setting.FLOORS)
        .as("a grid that cannot reproduce today's default cannot say what changing it costs")
        .contains(Recommendations.MIN_CANDIDATE_DEGREE);
  }
}
