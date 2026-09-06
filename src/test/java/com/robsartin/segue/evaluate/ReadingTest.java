package com.robsartin.segue.evaluate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.robsartin.segue.domain.Scorer;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * One row of the folded report is one reading per fold, added. Every number here is invented and
 * nothing reads a graph or a store (ADR 33, issue #37).
 */
class ReadingTest {

  private static final Setting SETTING = new Setting(Scorer.LIFT, 5);

  @Test
  @DisplayName("every count and every rank sum adds, and the setting is carried through")
  void shouldAddEveryFieldWhenTheFoldsOfOneSettingAreSummed() {
    Reading first = new Reading(SETTING, 900, 40, 4, 30, 2, 8);
    Reading second = new Reading(SETTING, 880, 38, 3, 21, 1, 5);
    Reading third = new Reading(SETTING, 870, 37, 0, 0, 0, 0);

    Reading summed = Reading.summed(List.of(first, second, third));

    assertThat(summed.setting()).isEqualTo(SETTING);
    assertThat(summed.pool()).isEqualTo(2650);
    assertThat(summed.heldOutInPool()).isEqualTo(115);
    assertThat(summed.hits()).isEqualTo(7);
    assertThat(summed.hitRankSum())
        .as("51 over 7 hits is the mean over every hit in the run, not a mean of three means")
        .isEqualTo(51);
    assertThat(summed.negativesOffered()).isEqualTo(3);
    assertThat(summed.negativeRankSum()).isEqualTo(13);
  }

  @Test
  @DisplayName("one fold sums to itself")
  void shouldReturnTheSameNumbersWhenThereIsOnlyOneFold() {
    Reading only = new Reading(SETTING, 900, 40, 4, 30, 2, 8);

    assertThat(Reading.summed(List.of(only))).isEqualTo(only);
  }

  @Test
  @DisplayName("readings of two different settings are refused, because one row is one setting")
  void shouldRefuseTheFoldsWhenTheyAreNotAllOfOneSetting() {
    Reading lift = new Reading(SETTING, 900, 40, 4, 30, 2, 8);
    Reading raw = new Reading(new Setting(Scorer.RAW, 5), 900, 40, 4, 30, 2, 8);

    assertThatThrownBy(() -> Reading.summed(List.of(lift, raw)))
        .as("summing across settings instead of across folds is the transposition this catches")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("two different settings");
  }

  @Test
  @DisplayName("no folds at all is refused, because there is no setting to name")
  void shouldRefuseTheFoldsWhenThereAreNone() {
    assertThatThrownBy(() -> Reading.summed(List.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("no folds");
  }
}
