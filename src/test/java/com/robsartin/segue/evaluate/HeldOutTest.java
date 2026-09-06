package com.robsartin.segue.evaluate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.robsartin.segue.domain.KnownList;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The split, over invented ratings. Nothing here comes from anybody's taste layer (ADR 33, issue
 * #37), and every id carries ADR 58's leading zero.
 */
class HeldOutTest {

  private static final Set<String> NOTHING_ON_FILE = Set.of();

  /**
   * Everything offered to the split is a candidate the sweep could return, unless a test says so.
   */
  private static final java.util.function.Predicate<String> ANYTHING = qid -> true;

  /**
   * Ten eligible entities, deliberately inserted out of order: the split must read qid order, not
   * map order.
   */
  private static final List<String> TEN =
      List.of(
          "Q0900406",
          "Q0900401",
          "Q0900409",
          "Q0900403",
          "Q0900407",
          "Q0900402",
          "Q0900410",
          "Q0900405",
          "Q0900408",
          "Q0900404");

  private static Map<String, Integer> tenEligible() {
    Map<String, Integer> ratings = new LinkedHashMap<>();
    for (String qid : TEN) {
      ratings.put(qid, KnownList.PROMOTION_RATING);
    }
    return ratings;
  }

  @Test
  @DisplayName("every fifth entity by qid order is held out, and the rest keep their ratings")
  void shouldHoldOutEveryFifthByQidOrderWhenThePopulationIsEligible() {
    HeldOut split = HeldOut.every(HeldOut.EVERY, 0, tenEligible(), NOTHING_ON_FILE, ANYTHING);

    assertThat(split.heldOut())
        .as("indices 0 and 5 of ten eligible entities sorted ascending")
        .containsExactly("Q0900401", "Q0900406");
    assertThat(split.eligible()).isEqualTo(10);
    assertThat(split.ratingsWithout())
        .as("the held-out ratings are gone and nothing else moved")
        .hasSize(8)
        .doesNotContainKeys("Q0900401", "Q0900406")
        .containsEntry("Q0900402", KnownList.PROMOTION_RATING);
  }

  @Test
  @DisplayName("fold one holds out the entities fold zero stepped over")
  void shouldHoldOutTheNextEntitiesWhenTheOffsetIsOne() {
    HeldOut split = HeldOut.every(HeldOut.EVERY, 1, tenEligible(), NOTHING_ON_FILE, ANYTHING);

    assertThat(split.heldOut())
        .as("indices 1 and 6 of ten eligible entities sorted ascending")
        .containsExactly("Q0900402", "Q0900407");
    assertThat(split.eligible()).as("the offset does not enter the eligibility rule").isEqualTo(10);
    assertThat(split.ratingsWithout())
        .as("this fold's entities are gone and fold zero's are not")
        .hasSize(8)
        .doesNotContainKeys("Q0900402", "Q0900407")
        .containsEntry("Q0900401", KnownList.PROMOTION_RATING);
  }

  @Test
  @DisplayName("every offset of one interval is a fold, and the folds partition the population")
  void shouldPartitionTheEligiblePopulationWhenEveryOffsetIsTaken() {
    List<String> overEveryFold = new ArrayList<>();

    for (int fold = 0; fold < HeldOut.EVERY; fold++) {
      HeldOut split = HeldOut.every(HeldOut.EVERY, fold, tenEligible(), NOTHING_ON_FILE, ANYTHING);
      assertThat(split.eligible())
          .as("fold %d: every fold shares one denominator, which is what lets the rows sum", fold)
          .isEqualTo(10);
      overEveryFold.addAll(split.heldOut());
    }

    assertThat(overEveryFold)
        .as("each eligible entity is held out in exactly one fold, and every one of them is")
        .doesNotHaveDuplicates()
        .containsExactlyInAnyOrderElementsOf(TEN);
  }

  @Test
  @DisplayName("an offset that is not one of the interval's folds is refused")
  void shouldRefuseTheOffsetWhenItIsNotOneOfTheFolds() {
    assertThatThrownBy(
            () ->
                HeldOut.every(
                    HeldOut.EVERY, HeldOut.EVERY, tenEligible(), NOTHING_ON_FILE, ANYTHING))
        .as("fold 5 of a 5-fold split does not exist; it would duplicate fold zero")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("fold 5");
    assertThatThrownBy(
            () -> HeldOut.every(HeldOut.EVERY, -1, tenEligible(), NOTHING_ON_FILE, ANYTHING))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("fold -1");
  }

  @Test
  @DisplayName("a rating below the promotion threshold is never eligible, and never held out")
  void shouldIgnoreARatingWhenItIsBelowThePromotionThreshold() {
    Map<String, Integer> ratings =
        Map.of(
            "Q0900401", KnownList.PROMOTION_RATING - 1, "Q0900402", KnownList.SUPPRESSION_RATING);

    HeldOut split = HeldOut.every(HeldOut.EVERY, 0, ratings, NOTHING_ON_FILE, ANYTHING);

    assertThat(split.eligible()).isZero();
    assertThat(split.heldOut()).isEmpty();
    assertThat(split.ratingsWithout())
        .as("a suppressed rating stays in the map: it is the negative signal, not the held-out set")
        .isEqualTo(ratings);
  }

  @Test
  @DisplayName(
      "an entity the known-list file names is never eligible, because the file puts it back")
  void shouldIgnoreAnEntityWhenTheKnownListFileAlreadyNamesIt() {
    Map<String, Integer> ratings = Map.of("Q0900401", 5, "Q0900402", 5);

    HeldOut split = HeldOut.every(HeldOut.EVERY, 0, ratings, Set.of("Q0900401"), ANYTHING);

    assertThat(split.eligible()).isEqualTo(1);
    assertThat(split.heldOut()).containsExactly("Q0900402");
  }

  @Test
  @DisplayName("an entity the sweep could never offer is never eligible")
  void shouldIgnoreAnEntityWhenTheSweepCouldNotOfferItBack() {
    Map<String, Integer> ratings = Map.of("Q0900401", 5, "Q0900402", 5);

    HeldOut split = HeldOut.every(HeldOut.EVERY, 0, ratings, NOTHING_ON_FILE, "Q0900402"::equals);

    assertThat(split.eligible()).isEqualTo(1);
    assertThat(split.heldOut()).containsExactly("Q0900402");
  }

  @Test
  @DisplayName(
      "holding out every entity is refused, because a run with no known-list measures nothing")
  void shouldRefuseTheIntervalWhenItWouldHoldOutTheWholePopulation() {
    assertThatThrownBy(() -> HeldOut.every(1, 0, Map.of("Q0900401", 5), NOTHING_ON_FILE, ANYTHING))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("every 1");
  }
}
