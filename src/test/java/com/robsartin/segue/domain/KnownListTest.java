package com.robsartin.segue.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Issue #106: a rating of 4 or 5 counts as having something, even when the file omits it. */
class KnownListTest {

  @Test
  @DisplayName("an entity rated at or above the threshold joins the list")
  void promotesAHighRating() {
    assertThat(KnownList.promoted(List.of("Q0900001"), Map.of("Q0900002", 5)))
        .containsExactlyInAnyOrder("Q0900001", "Q0900002");
  }

  @Test
  @DisplayName("a rating below the threshold does not join, and 3 is below it")
  void leavesTheRestAlone() {
    assertThat(KnownList.promoted(List.of("Q0900001"), Map.of("Q0900002", 3, "Q0900003", 1)))
        .containsExactly("Q0900001");
  }

  @Test
  @DisplayName("an entity already on the file is not duplicated by its own rating")
  void doesNotDuplicate() {
    assertThat(KnownList.promoted(List.of("Q0900001"), Map.of("Q0900001", 5)))
        .containsExactly("Q0900001");
  }

  @Test
  @DisplayName("the file's order is preserved and promotions follow, so two runs agree")
  void isDeterministic() {
    List<String> first =
        KnownList.promoted(List.of("Q0900002", "Q0900001"), Map.of("Q0900003", 5, "Q0900004", 4));
    List<String> second =
        KnownList.promoted(List.of("Q0900002", "Q0900001"), Map.of("Q0900004", 4, "Q0900003", 5));

    assertThat(first).startsWith("Q0900002", "Q0900001");
    assertThat(first).isEqualTo(second);
  }

  @Test
  @DisplayName(
      "the promoted portion is actually sorted, not incidentally matching by luck of Map.of's"
          + " own iteration order")
  void promotedPortionIsSortedStructurally() {
    // Three keys, not two: empirically, an unsorted implementation reproduces this exact
    // lexicographic order by luck of Map.of's internal hashing only a minority of the time (about
    // 1 run in 6, measured across dozens of separate JVM launches), where the two-key case above
    // was observed to pass by luck on every sampled JVM salt. This is the test that would actually
    // catch a missing .sort(...).
    List<String> result =
        KnownList.promoted(List.of(), Map.of("Q0900050", 5, "Q0900010", 4, "Q0900030", 5));

    assertThat(result).containsExactly("Q0900010", "Q0900030", "Q0900050");
  }

  @Test
  @DisplayName("a rating at the suppression threshold is suppressed")
  void suppressesARatingAtTheThreshold() {
    assertThat(KnownList.suppressed(Map.of("Q0900001", 2))).containsExactly("Q0900001");
  }

  @Test
  @DisplayName(
      "a rating just above the suppression threshold is not suppressed — 3 is neutral, not"
          + " rejected")
  void doesNotSuppressTheNeutralRating() {
    // The boundary in the other direction: regardFor treats 3 (NEUTRAL_RATING) as exactly
    // neutral, identical to unrated. Suppressing it would silently remove every neutral rating
    // from future recommendations, not just the rejected ones.
    assertThat(KnownList.suppressed(Map.of("Q0900001", 3))).isEmpty();
  }

  @Test
  @DisplayName("a rating below the threshold is also suppressed")
  void suppressesARatingBelowTheThreshold() {
    assertThat(KnownList.suppressed(Map.of("Q0900001", 1))).containsExactly("Q0900001");
  }

  @Test
  @DisplayName("a high rating is never suppressed")
  void doesNotSuppressAHighRating() {
    assertThat(KnownList.suppressed(Map.of("Q0900001", 4, "Q0900002", 5))).isEmpty();
  }

  @Test
  @DisplayName("suppression is a Set: unrelated entities are unaffected and order cannot matter")
  void suppressedIsASetOfOnlyTheRejected() {
    assertThat(
            KnownList.suppressed(
                Map.of("Q0900001", 2, "Q0900002", 5, "Q0900003", 3, "Q0900004", 1)))
        .containsExactlyInAnyOrder("Q0900001", "Q0900004");
  }

  @Test
  @DisplayName("an empty ratings map suppresses nothing")
  void emptyRatingsSuppressNothing() {
    assertThat(KnownList.suppressed(Map.of())).isEmpty();
  }

  @Test
  @DisplayName("revisitable is the known list unioned with the suppressed set")
  void revisitableUnionsKnownAndSuppressed() {
    assertThat(
            KnownList.revisitable(
                List.of("Q0900001"),
                Map.of("Q0900001", 3, "Q0900002", KnownList.SUPPRESSION_RATING)))
        .containsExactlyInAnyOrder("Q0900001", "Q0900002");
  }

  @Test
  @DisplayName("an entity neither known nor suppressed is not revisitable")
  void revisitableExcludesTheNeutralOffListCase() {
    // The exact case reviseCountsOnlyWhatItCanDeal in RateRunTest depends on: a rating of 3 is
    // neutral (not suppressed) and the qid is off the known list, so it must not appear.
    assertThat(KnownList.revisitable(List.of("Q0900001"), Map.of("Q0900001", 3, "Q0900009", 3)))
        .containsExactly("Q0900001");
  }

  @Test
  @DisplayName("a qid both known and suppressed is not duplicated")
  void revisitableDeduplicatesOverlap() {
    assertThat(
            KnownList.revisitable(
                List.of("Q0900001"), Map.of("Q0900001", KnownList.SUPPRESSION_RATING)))
        .containsExactly("Q0900001");
  }

  @Test
  @DisplayName("nothing known and nothing rated is not revisitable")
  void revisitableIsEmptyWhenBothInputsAreEmpty() {
    assertThat(KnownList.revisitable(List.of(), Map.of())).isEmpty();
  }

  @Test
  @DisplayName("what the sweep may not offer is the rejections and the merged local ids together")
  void notOfferedUnionsSuppressionWithTheMergedLocalIds() {
    Equivalences merges = new Equivalences(Map.of("Q00900042", "Q900"));

    assertThat(KnownList.notOffered(Map.of("Q0900001", 2, "Q0900002", 5), merges))
        .containsExactlyInAnyOrder("Q0900001", "Q00900042");
  }

  @Test
  @DisplayName("a merged local id is not revisable: its rating now reads under the canonical id")
  void aMergedLocalIdIsNotRevisable() {
    // Equivalences.resolve has already moved the rating, so the local id is absent from the map
    // by the time revisitable is asked. Written down because it is a decision, not an oversight:
    // dealing the local id for revision would write a second live row and rebuild the defect.
    Equivalences merges = new Equivalences(Map.of("Q00900042", "Q900"));
    Map<String, Integer> resolved = merges.resolve(Map.of("Q00900042", 5));

    assertThat(KnownList.revisitable(KnownList.promoted(List.of(), resolved), resolved))
        .containsExactly("Q900");
  }
}
