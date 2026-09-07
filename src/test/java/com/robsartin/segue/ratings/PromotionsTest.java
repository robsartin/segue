package com.robsartin.segue.ratings;

import static com.robsartin.segue.ratings.InventedRatings.CANONICAL;
import static com.robsartin.segue.ratings.InventedRatings.MINTED;
import static com.robsartin.segue.ratings.InventedRatings.NOVEL;
import static com.robsartin.segue.ratings.InventedRatings.QUARTET;
import static com.robsartin.segue.ratings.InventedRatings.SEEN_LIVE;
import static com.robsartin.segue.ratings.InventedRatings.VANISHED;
import static com.robsartin.segue.ratings.InventedRatings.merged;
import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.segue.domain.Equivalences;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The one question this class answers: who is promoted and not already on the file. Every id and
 * every score here is invented — see {@link InventedRatings}.
 */
class PromotionsTest {

  private static Map<String, Integer> rated(Object... qidsAndScores) {
    Map<String, Integer> scores = new LinkedHashMap<>();
    for (int i = 0; i < qidsAndScores.length; i += 2) {
      scores.put((String) qidsAndScores[i], (Integer) qidsAndScores[i + 1]);
    }
    return scores;
  }

  @Test
  @DisplayName("a high rating the known file does not name is a promotion")
  void shouldPromoteAnEntityWhenTheKnownFileDoesNotNameIt() {
    assertThat(Promotions.offTheKnownList(rated(QUARTET, 5), Equivalences.NONE, List.of(SEEN_LIVE)))
        .containsExactly(QUARTET);
  }

  @Test
  @DisplayName("an entity the known file already names is not promoted, however highly rated")
  void shouldNotPromoteAnEntityWhenTheKnownFileAlreadyNamesIt() {
    assertThat(
            Promotions.offTheKnownList(
                rated(SEEN_LIVE, 5, QUARTET, 5), Equivalences.NONE, List.of(SEEN_LIVE)))
        .as("the file is what the recommender already treats as known (ADR 48)")
        .containsExactly(QUARTET);
  }

  @Test
  @DisplayName("a rating below the promotion threshold is not a promotion")
  void shouldNotPromoteAnEntityWhenItsRatingIsBelowTheThreshold() {
    assertThat(Promotions.offTheKnownList(rated(NOVEL, 3), Equivalences.NONE, List.of(SEEN_LIVE)))
        .isEmpty();
  }

  @Test
  @DisplayName("two promotions come back in qid order, so two runs over one table agree")
  void shouldOrderThePromotionsByQidWhenThereIsMoreThanOne() {
    assertThat(
            Promotions.offTheKnownList(
                rated(VANISHED, 5, QUARTET, 4), Equivalences.NONE, List.of(SEEN_LIVE)))
        .containsExactly(QUARTET, VANISHED);
  }

  @Test
  @DisplayName("a merged rating promotes the canonical id and never the id it was retired from")
  void shouldPromoteOnlyTheCanonicalIdWhenAMergeCarriedTheRating() {
    assertThat(
            Promotions.offTheKnownList(
                rated(MINTED, 5),
                Equivalences.in(List.of(merged(MINTED, CANONICAL))),
                List.of(SEEN_LIVE)))
        .as("two rows name one thing after a merge (#92); promoting both uploads one opinion twice")
        .containsExactly(CANONICAL);
  }
}
