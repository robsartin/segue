package com.robsartin.segue.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
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
  private static final String NEIGHBOUR = "Q902";
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

  @Test
  @DisplayName("an edge claim out of a merged local id comes back on the canonical id")
  void shouldFoldTheFromSideOfAnEdgeOntoTheCanonicalId() {
    Equivalences merges = Equivalences.in(List.of(SameAs.declared(MINTED, CANONICAL, WHEN)));
    AssertionRecord claim = edge(MINTED, NEIGHBOUR);

    assertThat(merges.foldEndpoints(claim).fromQid()).isEqualTo(CANONICAL);
  }

  @Test
  @DisplayName("an edge claim pointing AT a merged local id comes back on the canonical id")
  void shouldFoldTheToSideOfAnEdgeOntoTheCanonicalId() {
    Equivalences merges = Equivalences.in(List.of(SameAs.declared(MINTED, CANONICAL, WHEN)));
    AssertionRecord claim = edge(NEIGHBOUR, MINTED);

    assertThat(merges.foldEndpoints(claim).toQid())
        .as("half a fold is a half-merge - the to-side moves as well as the from-side")
        .isEqualTo(CANONICAL);
  }

  @Test
  @DisplayName("an edge between two merged local ids folds both ends at once")
  void shouldFoldBothEndsOfAnEdgeBetweenTwoMergedLocalIds() {
    Equivalences merges =
        Equivalences.in(
            List.of(
                SameAs.declared(MINTED, CANONICAL, WHEN),
                SameAs.declared(OTHER_MINTED, OTHER_CANONICAL, WHEN)));

    assertThat(merges.foldEndpoints(edge(MINTED, OTHER_MINTED)))
        .extracting(AssertionRecord::fromQid, AssertionRecord::toQid)
        .containsExactly(CANONICAL, OTHER_CANONICAL);
  }

  @Test
  @DisplayName("the fold moves the endpoints and touches nothing else the claim carries")
  void shouldKeepEverythingButTheEndpointsWhenAnEdgeIsFolded() {
    Equivalences merges = Equivalences.in(List.of(SameAs.declared(MINTED, CANONICAL, WHEN)));
    Provenance source = new Provenance("invented", "invented:1", WHEN, 0.7);
    AssertionRecord claim =
        new AssertionRecord(
            MINTED,
            NEIGHBOUR,
            "INFLUENCED_BY",
            LocalDate.parse("1998-01-01"),
            LocalDate.parse("2004-12-31"),
            source);

    assertThat(merges.foldEndpoints(claim))
        .as("an equivalence says which id, not what was claimed, when, or by whom")
        .isEqualTo(
            new AssertionRecord(
                CANONICAL,
                NEIGHBOUR,
                "INFLUENCED_BY",
                LocalDate.parse("1998-01-01"),
                LocalDate.parse("2004-12-31"),
                source));
  }

  @Test
  @DisplayName("an owner edge folds and stays an owner edge, so replay still attributes it")
  void shouldFoldAnOwnerEdgeWithoutChangingWhatKindOfClaimItIs() {
    Equivalences merges = Equivalences.in(List.of(SameAs.declared(MINTED, CANONICAL, WHEN)));
    LoggedAssertion owned = OwnerEdge.claimed(MINTED, NEIGHBOUR, "INFLUENCED_BY", WHEN);

    assertThat(merges.foldEndpoints(owned))
        .as(
            "IngestService.apply switches on the kind of claim: an owner edge that folded into a"
                + " sourced one would be attributed to a witness who never said it")
        .isEqualTo(OwnerEdge.claimed(CANONICAL, NEIGHBOUR, "INFLUENCED_BY", WHEN));
  }

  @Test
  @DisplayName("a claim naming no merged id comes back as the very same object")
  void shouldReturnTheSameClaimWhenNeitherEndpointWasMerged() {
    Equivalences merges = Equivalences.in(List.of(SameAs.declared(MINTED, CANONICAL, WHEN)));
    AssertionRecord claim = edge(NEIGHBOUR, CANONICAL);

    assertThat(merges.foldEndpoints(claim))
        .as("most of the log names no merged id at all, and a copy of it would be waste")
        .isSameAs(claim);
  }

  @Test
  @DisplayName("a claim that is not an edge is not an endpoint question, and comes back untouched")
  void shouldLeaveClaimsThatAreNotEdgesAlone() {
    Equivalences merges = Equivalences.in(List.of(SameAs.declared(MINTED, CANONICAL, WHEN)));
    LoggedAssertion minted = LocalEntity.minted(MINTED, NodeKind.WORK, "a minted work", WHEN);
    LoggedAssertion merge = SameAs.declared(MINTED, CANONICAL, WHEN);

    assertThat(merges.foldEndpoints(minted))
        .as("the local node stays exactly where it was - ADR 59's merge bullet, and #178 keeps it")
        .isSameAs(minted);
    assertThat(merges.foldEndpoints(merge))
        .as("folding the merge onto itself would rewrite the claim that states the equivalence")
        .isSameAs(merge);
  }

  @Test
  @DisplayName("a retracted merge folds no endpoint, the way it carries nothing into the graph")
  void shouldFoldNothingWhenTheMergeWasRetracted() {
    Equivalences merges =
        Equivalences.in(
            List.of(
                SameAs.declared(MINTED, CANONICAL, WHEN),
                new Retraction(CANONICAL, "the merge named the wrong item", WHEN)));

    assertThat(merges.foldEndpoints(edge(MINTED, NEIGHBOUR)).fromQid()).isEqualTo(MINTED);
  }

  @Test
  @DisplayName("a merge's canonical id gets a stand-in node carrying what the owner minted")
  void shouldStandInForTheCanonicalIdWithTheMintedEntitysKindAndLabel() {
    List<LoggedAssertion> log =
        List.of(
            LocalEntity.minted(MINTED, NodeKind.WORK, "The Salt Almanac", WHEN),
            SameAs.declared(MINTED, CANONICAL, WHEN));

    assertThat(Equivalences.standIns(log))
        .as(
            "a merge is usually declared before any source has expanded the real item, and a"
                + " folded edge needs both of its endpoints to exist")
        .containsExactly(
            Map.entry(
                CANONICAL,
                new NodeRecord(CANONICAL, NodeKind.WORK, "The Salt Almanac", List.of())));
  }

  @Test
  @DisplayName("nothing minted under the local id means nothing to stand in for, and no error")
  void shouldStandInForNothingWhenTheLocalIdWasNeverMinted() {
    List<LoggedAssertion> log = List.of(SameAs.declared(MINTED, CANONICAL, WHEN));

    assertThat(Equivalences.standIns(log))
        .as("the log is append-only: a merge may be replayed with the claim it resolves retracted")
        .isEmpty();
  }

  @Test
  @DisplayName("a merge a retraction reaches stands in for nothing, the way it carries nothing")
  void shouldStandInForNothingWhenTheMergeWasRetracted() {
    List<LoggedAssertion> log =
        List.of(
            LocalEntity.minted(MINTED, NodeKind.WORK, "The Salt Almanac", WHEN),
            SameAs.declared(MINTED, CANONICAL, WHEN),
            new Retraction(CANONICAL, "the merge named the wrong item", WHEN));

    assertThat(Equivalences.standIns(log)).isEmpty();
  }

  @Test
  @DisplayName("an entity minted after the merge is not what the merge stood in for")
  void shouldReadTheMintedEntityAsItStoodWhenTheMergeWasMade() {
    List<LoggedAssertion> log =
        List.of(
            SameAs.declared(MINTED, CANONICAL, WHEN),
            LocalEntity.minted(MINTED, NodeKind.WORK, "The Salt Almanac", WHEN));

    assertThat(Equivalences.standIns(log))
        .as("order is log order - IngestService.carry reads the graph as it stands at the merge")
        .isEmpty();
  }

  @Test
  @DisplayName("two local ids merged onto one canonical id: the first merge names the stand-in")
  void shouldLetTheFirstMergeOntoACanonicalIdNameTheStandIn() {
    List<LoggedAssertion> log =
        List.of(
            LocalEntity.minted(MINTED, NodeKind.WORK, "the first name", WHEN),
            LocalEntity.minted(OTHER_MINTED, NodeKind.PERSON, "the second name", WHEN),
            SameAs.declared(MINTED, CANONICAL, WHEN),
            SameAs.declared(OTHER_MINTED, CANONICAL, WHEN));

    assertThat(Equivalences.standIns(log))
        .as("carry creates the node only where nothing has claimed one, so the first merge wins")
        .containsExactly(
            Map.entry(
                CANONICAL, new NodeRecord(CANONICAL, NodeKind.WORK, "the first name", List.of())));
  }

  @Test
  @DisplayName("a stand-in is offered even where a source has already named the canonical entity")
  void shouldOfferAStandInEvenWhereASourceHasAlreadyNamedTheCanonicalEntity() {
    List<LoggedAssertion> log =
        List.of(
            new NodeAssertion(
                CANONICAL,
                NodeKind.WORK,
                "what the source calls it",
                new Provenance("invented", "invented:1", WHEN, 1.0)),
            LocalEntity.minted(MINTED, NodeKind.WORK, "what the owner called it", WHEN),
            SameAs.declared(MINTED, CANONICAL, WHEN));

    // Deliberately NOT "only where nothing has claimed one". This map is applied BEFORE the log
    // is projected, so every real claim about the canonical id - whether it was made before the
    // merge or after it - lands on top of the stand-in and wins by last-writer-wins. Refusing to
    // offer it here would put the ordering question in two places, and the caller's answer is
    // already the same in both directions. The source's label winning is asserted end-to-end in
    // MergeCarriesEverythingTest and LogProjectionTest.
    assertThat(Equivalences.standIns(log))
        .containsExactly(
            Map.entry(
                CANONICAL,
                new NodeRecord(CANONICAL, NodeKind.WORK, "what the owner called it", List.of())));
  }

  private static AssertionRecord edge(String from, String to) {
    return new AssertionRecord(
        from, to, "INFLUENCED_BY", null, null, new Provenance("invented", "invented:1", WHEN, 1.0));
  }
}
