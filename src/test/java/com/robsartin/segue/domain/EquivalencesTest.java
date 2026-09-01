package com.robsartin.segue.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Issue #92: what a merge means to a reader of the taste layer. */
class EquivalencesTest {

  private static final String MINTED = "Q00900042";
  private static final String OTHER_MINTED = "Q00900043";
  private static final String CANONICAL = "Q900";
  private static final String OTHER_CANONICAL = "Q901";
  private static final Instant WHEN = Instant.parse("2026-08-31T09:00:00Z");

  @Test
  @DisplayName("a merged local id's rating reads under the canonical id, and only there")
  void shouldResolveARatingOntoTheCanonicalIdWhenTheLocalIdWasMerged() {
    Equivalences merges = Equivalences.in(List.of(SameAs.declared(MINTED, CANONICAL, WHEN)));

    assertThat(merges.resolve(Map.of(MINTED, 5))).containsExactly(Map.entry(CANONICAL, 5));
  }

  @Test
  @DisplayName(
      "the store's own answer for the canonical id wins, because ADR 39 already decided it")
  void shouldNotOverwriteARatingTheStoreAlreadyHoldsForTheCanonicalId() {
    Equivalences merges = Equivalences.in(List.of(SameAs.declared(MINTED, CANONICAL, WHEN)));

    // IdentityMerge.carryingRatings has already applied "the later rating wins" using updatedAt,
    // and readRatings carries no timestamps — so re-deciding it here could only decide it worse.
    assertThat(merges.resolve(Map.of(MINTED, 5, CANONICAL, 2)))
        .containsExactly(Map.entry(CANONICAL, 2));
  }

  @Test
  @DisplayName("a merged local id is named as merged, so the sweep can stop offering it")
  void shouldNameTheLocalSideAsMergedAndNotTheCanonicalOne() {
    Equivalences merges =
        Equivalences.in(
            List.of(
                SameAs.declared(MINTED, CANONICAL, WHEN),
                SameAs.declared(OTHER_MINTED, OTHER_CANONICAL, WHEN)));

    assertThat(merges.merged()).containsExactlyInAnyOrder(MINTED, OTHER_MINTED);
  }

  @Test
  @DisplayName("a retracted merge resolves nothing, the way it carries nothing into the graph")
  void shouldIgnoreAMergeARetractionReaches() {
    Equivalences merges =
        Equivalences.in(
            List.of(
                SameAs.declared(MINTED, CANONICAL, WHEN),
                new Retraction(CANONICAL, "the merge named the wrong item", WHEN)));

    assertThat(merges.merged())
        .as("Retractions.survives drops this row from both graph folds; it must drop it here too")
        .isEmpty();
    assertThat(merges.resolve(Map.of(MINTED, 5))).containsExactly(Map.entry(MINTED, 5));
  }

  @Test
  @DisplayName("a second merge of the same local id supersedes the first, by position in the log")
  void shouldLetTheLaterMergeWin() {
    Equivalences merges =
        Equivalences.in(
            List.of(
                SameAs.declared(MINTED, CANONICAL, WHEN),
                SameAs.declared(MINTED, OTHER_CANONICAL, WHEN)));

    assertThat(merges.resolve(Map.of(MINTED, 5))).containsExactly(Map.entry(OTHER_CANONICAL, 5));
  }

  @Test
  @DisplayName("a log with no merge in it leaves the ratings exactly as they were")
  void shouldLeaveTheRatingsAloneWhenNothingWasMerged() {
    assertThat(Equivalences.NONE.resolve(Map.of("Q0900001", 5, "Q0900002", 2)))
        .containsExactlyInAnyOrderEntriesOf(Map.of("Q0900001", 5, "Q0900002", 2));
  }
}
