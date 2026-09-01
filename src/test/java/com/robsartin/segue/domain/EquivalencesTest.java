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

  @Test
  @DisplayName(
      "two rated local ids merged into one canonical id collapse to one rating, and the first"
          + " merge in the log is the one that wins")
  void shouldCollapseTwoRatedLocalIdsMergedIntoTheSameCanonicalId() {
    Equivalences merges =
        Equivalences.in(
            List.of(
                SameAs.declared(MINTED, CANONICAL, WHEN),
                SameAs.declared(OTHER_MINTED, CANONICAL, WHEN)));

    // Arbitrary, and said so in resolve()'s javadoc: neither rating has a better claim, and
    // readRatings carries no timestamp to ask "which is later" with. Pinned because the ARBITRARY
    // choice still has to be the SAME one on every run — which is why Equivalences keeps log
    // order instead of letting Map.copyOf's per-JVM salt decide.
    assertThat(merges.resolve(Map.of(MINTED, 2, OTHER_MINTED, 5)))
        .containsExactly(Map.entry(CANONICAL, 2));
  }

  @Test
  @DisplayName(
      "the merges iterate in log order, which is the property a collision's answer rests on")
  void shouldIterateInLogOrder() {
    // Five, and in an order that is neither the natural order of the ids nor the order Map.copyOf
    // would be likely to reproduce: this is the test that actually catches the copy losing the
    // order, the same argument KnownListTest.promotedPortionIsSortedStructurally makes about
    // Map.of's own hashing. Measured against a Map.copyOf implementation across separate JVM
    // launches, it failed 20 times out of 20.
    List<LoggedAssertion> log =
        List.of(
            SameAs.declared("Q00900050", "Q905", WHEN),
            SameAs.declared("Q00900010", "Q901", WHEN),
            SameAs.declared("Q00900040", "Q904", WHEN),
            SameAs.declared("Q00900020", "Q902", WHEN),
            SameAs.declared("Q00900030", "Q903", WHEN));

    assertThat(Equivalences.in(log).canonicalByLocal().keySet())
        .containsExactly("Q00900050", "Q00900010", "Q00900040", "Q00900020", "Q00900030");
  }
}
