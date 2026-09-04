package com.robsartin.segue.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Issue #92: what a merge means to a reader of the taste layer. */
class EquivalencesTest {

  private static final String MINTED = "Q00900042";
  private static final String OTHER_MINTED = "Q00900043";

  /**
   * A third minted id, for the one fixture that needs three: the second-order chain below has one
   * local retracted under each of the two canonical ids it empties, and a third standing in for the
   * superseded merge between them. Two leading zeros, like its two siblings above (ADR 59).
   */
  private static final String THIRD_MINTED = "Q00900044";

  /**
   * The two shapes this file needs, and they are not the same one. A merge's canonical side takes
   * ADR 62's eleven digits, which {@code SameAs} admits there and nowhere else; {@code NEIGHBOUR}
   * is only the far end of an edge, so it is an ordinary stand-in and takes ADR 58's single leading
   * zero. Each is the id this file used before issue #171 carried into its shape.
   */
  private static final String CANONICAL = "Q10000000900";

  private static final String OTHER_CANONICAL = "Q10000000901";

  /** A third canonical id, for the second-order chain below. ADR 62's eleven digits, as above. */
  private static final String THIRD_CANONICAL = "Q10000000902";

  private static final String NEIGHBOUR = "Q0902";
  private static final Instant WHEN = Instant.parse("2026-08-31T09:00:00Z");

  /**
   * Kinds as the claim stated them. Every fixture in this file states no classes, so {@code
   * KindMapper.rederive} would be the identity on all of them (ADR 42) - naming it here says the
   * choice was made rather than defaulted, and keeps {@code wikidata} out of a {@code domain} test.
   *
   * <p><b>It is honest only while that stays true.</b> A fixture added here that DOES state classes
   * would go un-re-derived under this operator with nothing in this file to say so, and would then
   * assert the claimed kind as though it were the answer both folds give. Nothing here can catch
   * that - identity is a legitimate answer for a caller to hand in - so the guard is elsewhere:
   * {@code StandInAgreesInEveryHomeTest} feeds a class-bearing claim through the real {@code
   * KindMapper.rederive} in every home the rule has.
   */
  private static final UnaryOperator<NodeAssertion> AS_CLAIMED = UnaryOperator.identity();

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
            SameAs.declared("Q00900050", "Q10000000905", WHEN),
            SameAs.declared("Q00900010", "Q10000000901", WHEN),
            SameAs.declared("Q00900040", "Q10000000904", WHEN),
            SameAs.declared("Q00900020", "Q10000000902", WHEN),
            SameAs.declared("Q00900030", "Q10000000903", WHEN));

    assertThat(Equivalences.in(log).canonicalByLocal().keySet())
        .containsExactly("Q00900050", "Q00900010", "Q00900040", "Q00900020", "Q00900030");
  }

  @Test
  @DisplayName("an edge claim out of a merged local id comes back on the canonical id")
  void shouldFoldTheFromSideOfAnEdgeOntoTheCanonicalId() {
    Equivalences merges = Equivalences.in(List.of(SameAs.declared(MINTED, CANONICAL, WHEN)));
    AssertionRecord claim = edge(MINTED, NEIGHBOUR);

    assertThat(merges.foldEndpoints(claim).orElseThrow().fromQid()).isEqualTo(CANONICAL);
  }

  @Test
  @DisplayName("an edge claim pointing AT a merged local id comes back on the canonical id")
  void shouldFoldTheToSideOfAnEdgeOntoTheCanonicalId() {
    Equivalences merges = Equivalences.in(List.of(SameAs.declared(MINTED, CANONICAL, WHEN)));
    AssertionRecord claim = edge(NEIGHBOUR, MINTED);

    assertThat(merges.foldEndpoints(claim).orElseThrow().toQid())
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

    assertThat(merges.foldEndpoints(edge(MINTED, OTHER_MINTED)).orElseThrow())
        .extracting(AssertionRecord::fromQid, AssertionRecord::toQid)
        .containsExactly(CANONICAL, OTHER_CANONICAL);
  }

  @Test
  @DisplayName("an edge whose two ends merge onto one canonical id folds to no edge at all")
  void shouldDropAnEdgeWhoseBothEndsResolveToTheSameCanonicalId() {
    Equivalences merges =
        Equivalences.in(
            List.of(
                SameAs.declared(MINTED, CANONICAL, WHEN),
                SameAs.declared(OTHER_MINTED, CANONICAL, WHEN)));

    assertThat(merges.foldEndpoints(edge(MINTED, OTHER_MINTED)))
        .as(
            "the owner minting one thing twice and saying so is a real path; the edge between the"
                + " two would fold to a self-loop, and neither he nor a source claimed that a"
                + " thing relates to itself")
        .isEmpty();
  }

  @Test
  @DisplayName("an edge already claimed from an entity to itself is not the fold's business")
  void shouldLeaveASelfLoopNoMergeCreatedExactlyWhereItWas() {
    Equivalences merges = Equivalences.in(List.of(SameAs.declared(MINTED, CANONICAL, WHEN)));
    AssertionRecord claim = edge(NEIGHBOUR, NEIGHBOUR);

    assertThat(merges.foldEndpoints(claim))
        .as(
            "this type answers 'which id', and a self-loop nobody merged was in the log and in the"
                + " graph before #178; dropping it here would be an unrelated rule wearing this"
                + " method's name")
        .contains(claim);
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
        .contains(
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
        .contains(OwnerEdge.claimed(CANONICAL, NEIGHBOUR, "INFLUENCED_BY", WHEN));
  }

  @Test
  @DisplayName("a claim naming no merged id comes back as the very same object")
  void shouldReturnTheSameClaimWhenNeitherEndpointWasMerged() {
    Equivalences merges = Equivalences.in(List.of(SameAs.declared(MINTED, CANONICAL, WHEN)));
    AssertionRecord claim = edge(NEIGHBOUR, CANONICAL);

    assertThat(merges.foldEndpoints(claim))
        .as("most of the log names no merged id at all, and a copy of it would be waste")
        .containsSame(claim);
  }

  @Test
  @DisplayName("a claim that is not an edge is not an endpoint question, and comes back untouched")
  void shouldLeaveClaimsThatAreNotEdgesAlone() {
    Equivalences merges = Equivalences.in(List.of(SameAs.declared(MINTED, CANONICAL, WHEN)));
    LoggedAssertion minted = LocalEntity.minted(MINTED, NodeKind.WORK, "a minted work", WHEN);
    LoggedAssertion merge = SameAs.declared(MINTED, CANONICAL, WHEN);

    assertThat(merges.foldEndpoints(minted))
        .as("the local node stays exactly where it was - ADR 59's merge bullet, and #178 keeps it")
        .containsSame(minted);
    assertThat(merges.foldEndpoints(merge))
        .as("folding the merge onto itself would rewrite the claim that states the equivalence")
        .containsSame(merge);
  }

  @Test
  @DisplayName("a retracted merge folds no endpoint, the way it carries nothing into the graph")
  void shouldFoldNothingWhenTheMergeWasRetracted() {
    Equivalences merges =
        Equivalences.in(
            List.of(
                SameAs.declared(MINTED, CANONICAL, WHEN),
                new Retraction(CANONICAL, "the merge named the wrong item", WHEN)));

    assertThat(merges.foldEndpoints(edge(MINTED, NEIGHBOUR)).orElseThrow().fromQid())
        .isEqualTo(MINTED);
  }

  @Test
  @DisplayName("a merge's canonical id gets a stand-in node carrying what the owner minted")
  void shouldStandInForTheCanonicalIdWithTheMintedEntitysKindAndLabel() {
    List<LoggedAssertion> log =
        List.of(
            LocalEntity.minted(MINTED, NodeKind.WORK, "The Salt Almanac", WHEN),
            SameAs.declared(MINTED, CANONICAL, WHEN));

    assertThat(Equivalences.standIns(log, AS_CLAIMED))
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

    assertThat(Equivalences.standIns(log, AS_CLAIMED))
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

    assertThat(Equivalences.standIns(log, AS_CLAIMED)).isEmpty();
  }

  @Test
  @DisplayName("an entity minted after the merge is not what the merge stood in for")
  void shouldReadTheMintedEntityAsItStoodWhenTheMergeWasMade() {
    List<LoggedAssertion> log =
        List.of(
            SameAs.declared(MINTED, CANONICAL, WHEN),
            LocalEntity.minted(MINTED, NodeKind.WORK, "The Salt Almanac", WHEN));

    assertThat(Equivalences.standIns(log, AS_CLAIMED))
        .as("order is log order - IngestService.standIn reads the graph as it stands at the merge")
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

    assertThat(Equivalences.standIns(log, AS_CLAIMED))
        .as("carry creates the node only where nothing has claimed one, so the first merge wins")
        .containsExactly(
            Map.entry(
                CANONICAL, new NodeRecord(CANONICAL, NodeKind.WORK, "the first name", List.of())));
  }

  @Test
  @DisplayName(
      "two local ids merged onto one canonical id, one of them later corrected away: the"
          + " standing merge's label wins outright, not the superseded merge's")
  void shouldTakeTheStandInsLabelFromTheMergeThatStandsWhenTheOtherWasCorrectedAway() {
    // standIns' own "Two local ids merged onto ONE canonical id" paragraph names this exact
    // case: FIRST and SECOND both merge onto SHARED_CANONICAL, and a later merge corrects FIRST
    // away onto RETARGETED. No edge anywhere in this log, so no edge the fold keeps names
    // SHARED_CANONICAL directly - the branch where FIRST's now-superseded merge must contribute
    // NOTHING and SECOND's label wins outright, whatever the log order put first.
    //
    // Ids in the domain test's own style: two leading zeros for what the owner minted (ADR
    // 58/59), eleven digits for a merge's canonical side (ADR 62) - the next free of each shape
    // in this file, after MINTED/OTHER_MINTED/THIRD_MINTED and
    // CANONICAL/OTHER_CANONICAL/THIRD_CANONICAL above.
    String first = "Q00900046";
    String second = "Q00900047";
    String sharedCanonical = "Q10000000903";
    String retargeted = "Q10000000904";
    List<LoggedAssertion> log =
        List.of(
            LocalEntity.minted(first, NodeKind.WORK, "the superseded label", WHEN),
            SameAs.declared(first, sharedCanonical, WHEN),
            LocalEntity.minted(second, NodeKind.WORK, "the standing label", WHEN),
            SameAs.declared(second, sharedCanonical, WHEN),
            SameAs.declared(first, retargeted, WHEN));

    assertThat(Equivalences.standIns(log, AS_CLAIMED).get(sharedCanonical).label())
        .as(
            "first's merge onto sharedCanonical no longer stands (it was corrected onto"
                + " retargeted) and no edge keeps it alive either, so it must contribute nothing"
                + " here - putIfAbsent's first-in-log-order tiebreak never gets to run, because"
                + " only second's merge reaches the map at all")
        .isEqualTo("the standing label");
  }

  @Test
  @DisplayName("a merge a later one corrected names no stand-in, so nothing is left under it")
  void shouldNameNoStandInWhenALaterMergeCorrectedTheCanonicalId() {
    List<LoggedAssertion> log =
        List.of(
            LocalEntity.minted(MINTED, NodeKind.WORK, "The Salt Almanac", WHEN),
            SameAs.declared(MINTED, CANONICAL, WHEN),
            SameAs.declared(MINTED, OTHER_CANONICAL, WHEN));

    assertThat(Equivalences.standIns(log, AS_CLAIMED))
        .as(
            "the edges fold onto the last canonical id, so a stand-in under the first is a node"
                + " with the merged entity's label and no edges that nothing ever claimed")
        .containsExactly(
            Map.entry(
                OTHER_CANONICAL,
                new NodeRecord(OTHER_CANONICAL, NodeKind.WORK, "The Salt Almanac", List.of())));
  }

  @Test
  @DisplayName("the same merge declared twice still names its stand-in from the first of them")
  void shouldStillNameTheStandInWhenTheSameMergeWasDeclaredTwice() {
    // stands() compares canonical ids, not log positions, so re-declaring one merge changes
    // nothing - the idempotence IdentityMerge already claims for the rating half. The label is
    // the one the entity had at the FIRST of the two, which is putIfAbsent's answer unchanged.
    List<LoggedAssertion> log =
        List.of(
            LocalEntity.minted(MINTED, NodeKind.WORK, "The Salt Almanac", WHEN),
            SameAs.declared(MINTED, CANONICAL, WHEN),
            LocalEntity.minted(MINTED, NodeKind.WORK, "a name it was given later", WHEN),
            SameAs.declared(MINTED, CANONICAL, WHEN));

    assertThat(Equivalences.standIns(log, AS_CLAIMED))
        .containsExactly(
            Map.entry(
                CANONICAL,
                new NodeRecord(CANONICAL, NodeKind.WORK, "The Salt Almanac", List.of())));
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
    assertThat(Equivalences.standIns(log, AS_CLAIMED))
        .containsExactly(
            Map.entry(
                CANONICAL,
                new NodeRecord(CANONICAL, NodeKind.WORK, "what the owner called it", List.of())));
  }

  @Test
  @DisplayName("a plain node claim naming the local id stands in too, whoever made it")
  void shouldStandInWhereAPlainNodeClaimNamedTheMergedLocalId() {
    // Spec ruling 2: the fold must not assume every claim naming a merged local id came through
    // OwnCli. Reading LocalEntity alone made this visible to the boot replay (via carry's own
    // graph.node(local) question) and invisible to the exporter, and the two folds disagreed.
    List<LoggedAssertion> log =
        List.of(
            new NodeAssertion(
                MINTED,
                NodeKind.WORK,
                "a local-shaped id a source named",
                new Provenance("invented", "invented:1", WHEN, 1.0)),
            SameAs.declared(MINTED, CANONICAL, WHEN));

    assertThat(Equivalences.standIns(log, AS_CLAIMED))
        .containsExactly(
            Map.entry(
                CANONICAL,
                new NodeRecord(
                    CANONICAL, NodeKind.WORK, "a local-shaped id a source named", List.of())));
  }

  @Test
  @DisplayName("the stand-in's kind is whatever the caller re-derives, not the kind the claim said")
  void shouldTakeTheStandInsKindFromTheCallersRederivationWhenTheCallerReturnsAnotherKind() {
    // The rule that closes ADR 59's first residual, stated where it lives (#222). KindMapper is in
    // wikidata and domain may not reach it - domainHasNoThirdPartyDependencies allows domain only
    // domain, java and javax - so the re-derivation arrives as a function and both folds hand in
    // the one they already apply to every node claim. The stub below stands in for it.
    List<LoggedAssertion> log =
        List.of(
            new NodeAssertion(
                MINTED,
                NodeKind.WORK,
                "a local-shaped id a source named",
                new Provenance("invented", "invented:1", WHEN, 1.0)),
            SameAs.declared(MINTED, CANONICAL, WHEN));

    assertThat(Equivalences.standIns(log, claim -> claim.withKind(NodeKind.PERSON)))
        .as("both folds re-derive this claim's kind, and the stand-in is the same entity")
        .containsExactly(
            Map.entry(
                CANONICAL,
                new NodeRecord(
                    CANONICAL, NodeKind.PERSON, "a local-shaped id a source named", List.of())));
  }

  @Test
  @DisplayName("the merges that have a local side are named by their position in the log")
  void shouldNameEachSurvivingMergeThatHasALocalSideByItsPosition() {
    List<LoggedAssertion> log =
        List.of(
            SameAs.declared(OTHER_MINTED, OTHER_CANONICAL, WHEN),
            LocalEntity.minted(MINTED, NodeKind.WORK, "The Salt Almanac", WHEN),
            SameAs.declared(MINTED, CANONICAL, WHEN));

    assertThat(Equivalences.localsOfMerges(log, AS_CLAIMED))
        .as("the merge at position 0 names an id nothing had claimed yet, and carries nothing")
        .containsExactly(
            Map.entry(2, new NodeRecord(MINTED, NodeKind.WORK, "The Salt Almanac", List.of())));
  }

  @Test
  @DisplayName("a canonical id whose merge a retraction of the local side dropped is emptied")
  void shouldEmptyACanonicalIdWhenARetractionReachedItsMergesLocalSide() {
    List<LoggedAssertion> log =
        List.of(
            LocalEntity.minted(MINTED, NodeKind.WORK, "The Salt Almanac", WHEN),
            SameAs.declared(MINTED, CANONICAL, WHEN),
            new Retraction(MINTED, "the mint was a mistake", WHEN));

    assertThat(Equivalences.retractedStandIns(log)).containsExactly(CANONICAL);
  }

  @Test
  @DisplayName("a canonical id a source claimed as a node of its own is not emptied")
  void shouldEmptyNoCanonicalIdWhenASourceHasClaimedItAsANode() {
    List<LoggedAssertion> log =
        List.of(
            LocalEntity.minted(MINTED, NodeKind.WORK, "The Salt Almanac", WHEN),
            new NodeAssertion(
                CANONICAL,
                NodeKind.GROUP,
                "the source's own name",
                new Provenance("invented", "invented:1", WHEN, 1.0)),
            SameAs.declared(MINTED, CANONICAL, WHEN),
            new Retraction(MINTED, "the mint was a mistake", WHEN));

    assertThat(Equivalences.retractedStandIns(log)).isEmpty();
  }

  @Test
  @DisplayName("a canonical id a surviving merge still stands in for is not emptied")
  void shouldEmptyNoCanonicalIdWhenASurvivingMergeStillNamesIt() {
    List<LoggedAssertion> log =
        List.of(
            LocalEntity.minted(MINTED, NodeKind.WORK, "The Salt Almanac", WHEN),
            LocalEntity.minted(OTHER_MINTED, NodeKind.WORK, "the other one", WHEN),
            SameAs.declared(MINTED, CANONICAL, WHEN),
            SameAs.declared(OTHER_MINTED, CANONICAL, WHEN),
            new Retraction(MINTED, "the mint was a mistake", WHEN));

    assertThat(Equivalences.retractedStandIns(log)).isEmpty();
  }

  @Test
  @DisplayName("emptying one canonical id empties a second whose only edge it withdrew")
  void shouldEmptyASecondCanonicalIdWhenWithdrawingItsOnlyEdgeRetiredItsStandIn() {
    // The second-order chain Equivalences.emptiedCanonicalIds loops for (#228), written out as a
    // log: CANONICAL is emptied outright, which withdraws the only edge naming OTHER_CANONICAL,
    // which retires the superseded stand-in that was the only node OTHER_CANONICAL had, which
    // empties OTHER_CANONICAL in turn. One round of the step answers [CANONICAL] and stops; it
    // takes the fixed point to reach the second id.
    List<LoggedAssertion> log =
        List.of(
            LocalEntity.minted(MINTED, NodeKind.WORK, "The Salt Almanac", WHEN),
            SameAs.declared(MINTED, CANONICAL, WHEN),
            LocalEntity.minted(OTHER_MINTED, NodeKind.WORK, "the other one", WHEN),
            SameAs.declared(OTHER_MINTED, OTHER_CANONICAL, WHEN),
            OwnerEdge.claimed(CANONICAL, OTHER_CANONICAL, "INFLUENCED_BY", WHEN),
            LocalEntity.minted(THIRD_MINTED, NodeKind.WORK, "the third", WHEN),
            SameAs.declared(THIRD_MINTED, OTHER_CANONICAL, WHEN),
            SameAs.declared(OTHER_MINTED, THIRD_CANONICAL, WHEN),
            new Retraction(MINTED, "the mint was a mistake", WHEN),
            new Retraction(THIRD_MINTED, "so was this one", WHEN));

    assertThat(Equivalences.retractedStandIns(log))
        .as(
            "the chain has two links, and a set computed in one pass sees only the first - the"
                + " edge that kept OTHER_CANONICAL's stand-in alive is one the fold withdraws")
        .containsExactly(CANONICAL, OTHER_CANONICAL);
  }

  @Test
  @DisplayName("the canonical ids a stand-in exists for are named the same way in both homes")
  void shouldNameTheSameCanonicalIdsAsStandInsWhenGivenTheSameReferencedSet() {
    // "Which canonical ids have a stand-in" has two homes since #228: Equivalences.standIns, which
    // builds a node per id, and Equivalences.standInCanonicalIds, which answers the same question
    // over a referenced set the caller supplies - the only way retractedStandIns can ask it while
    // it is still working out what Equivalences.in answers. Two homes for one rule is this class's
    // own standing objection, so the two are pinned here rather than asserted in prose.
    //
    // The log is the widest this question has in domain: a superseded merge kept alive by an edge
    // the fold keeps (stands' second clause), the merge that supersedes it (its first clause), and
    // a third merge a retraction of the local side empties. BothFoldsAgreeTest.ownedLog() is wider
    // still and is deliberately not used - it is private to a test in export, and standInCanonical-
    // Ids is package-private in domain, so reaching one from the other would mean widening the API
    // of the class whose whole point is one home per question.
    List<LoggedAssertion> log =
        List.of(
            LocalEntity.minted(MINTED, NodeKind.WORK, "The Salt Almanac", WHEN),
            SameAs.declared(MINTED, CANONICAL, WHEN),
            OwnerEdge.claimed(NEIGHBOUR, CANONICAL, "INFLUENCED_BY", WHEN),
            SameAs.declared(MINTED, OTHER_CANONICAL, WHEN),
            LocalEntity.minted(OTHER_MINTED, NodeKind.WORK, "the other one", WHEN),
            SameAs.declared(OTHER_MINTED, THIRD_CANONICAL, WHEN),
            new Retraction(OTHER_MINTED, "the mint was a mistake", WHEN));

    assertThat(Equivalences.standInCanonicalIds(log, Equivalences.in(log).referencedEndpoints()))
        .as(
            "retractedStandIns' javadoc says this rule has one home; that is only true while the"
                + " second computation of it answers what standIns' key set does")
        .isEqualTo(Equivalences.standIns(log, AS_CLAIMED).keySet());
    assertThat(Equivalences.standIns(log, AS_CLAIMED).keySet())
        .as("and both name something, so the comparison above is not comparing two empty sets")
        .containsExactly(CANONICAL, OTHER_CANONICAL);
  }

  @Test
  @DisplayName("a merge a retraction of the CANONICAL side dropped empties nothing")
  void shouldEmptyNoCanonicalIdWhenTheRetractionReachedTheCanonicalSide() {
    List<LoggedAssertion> log =
        List.of(
            LocalEntity.minted(MINTED, NodeKind.WORK, "The Salt Almanac", WHEN),
            SameAs.declared(MINTED, CANONICAL, WHEN),
            new Retraction(CANONICAL, "the merge named the wrong item", WHEN));

    assertThat(Equivalences.retractedStandIns(log))
        .as(
            "that id is retracted outright, so Retractions.survives has already dropped every edge"
                + " naming it - emptying it here as well would be a second rule saying the same"
                + " thing, and a different one the moment either changed")
        .isEmpty();
  }

  @Test
  @DisplayName("the canonical ids a stand-in exists for do not depend on the derived kind")
  void shouldNameTheSameCanonicalIdsWhateverKindTheFoldDerives() {
    // A NodeAssertion, not a LocalEntity: localsOfMerges applies the re-derivation to a source's
    // node claim alone - LocalEntity.toNode() carries the owner's stated kind, and no operator
    // touches it - so a minted local side would make the two derivations agree by construction
    // and the control below could never fire.
    List<LoggedAssertion> log =
        List.of(
            new NodeAssertion(
                MINTED,
                NodeKind.WORK,
                "a local-shaped id a source named",
                new Provenance("invented", "invented:1", WHEN, 1.0)),
            SameAs.declared(MINTED, CANONICAL, WHEN));

    assertThat(Equivalences.standIns(log, AS_CLAIMED).keySet())
        .as(
            "retractedStandIns reads this key set under UnaryOperator.identity() and says the"
                + " re-derivation cannot change it; this is that claim, made falsifiable")
        .isEqualTo(Equivalences.standIns(log, claim -> claim.withKind(NodeKind.PERSON)).keySet());
    assertThat(Equivalences.standIns(log, AS_CLAIMED).get(CANONICAL).kind())
        .as("and the values DO differ, so the comparison above is not comparing nothing")
        .isNotEqualTo(
            Equivalences.standIns(log, claim -> claim.withKind(NodeKind.PERSON))
                .get(CANONICAL)
                .kind());
  }

  @Test
  @DisplayName("the merges built from a prebuilt emptied set are the ones in() builds itself")
  void shouldGiveTheSameMergesWhenHandedTheEmptiedSetInWouldCompute() {
    List<LoggedAssertion> log = foldedLog();

    assertThat(Equivalences.in(log, Equivalences.retractedStandIns(log)))
        .as(
            "in(log, emptied) exists so a caller that already paid for the fixed point does not"
                + " pay for it again; it is the same answer or it is a second fold")
        .isEqualTo(Equivalences.in(log));
    assertThat(Equivalences.retractedStandIns(log))
        .as("and the emptied set is not empty, so the comparison above is not vacuous")
        .containsExactly(CANONICAL);
  }

  @Test
  @DisplayName("a fold built from prebuilt merges and emptied set is the one folding() builds")
  void shouldGiveTheSameFoldWhenHandedTheMergesAndEmptiedSetFoldingWouldCompute() {
    List<LoggedAssertion> log = foldedLog();
    Set<String> emptied = Equivalences.retractedStandIns(log);

    assertThat(Equivalences.folding(Equivalences.in(log, emptied), emptied))
        .as(
            "folding(merges, emptied) is where the boot's Equivalences is constructed; a"
                + " different answer here is the two folds drifting")
        .isEqualTo(Equivalences.folding(log));
    assertThat(Equivalences.folding(log).retractedStandIns())
        .as("and the fold names a retracted stand-in, so the comparison is not vacuous")
        .containsExactly(CANONICAL);
  }

  @Test
  @DisplayName("stand-ins built from prebuilt merges are the ones standIns() builds itself")
  void shouldGiveTheSameStandInsWhenHandedTheMergesStandInsWouldCompute() {
    List<LoggedAssertion> log = foldedLog();

    assertThat(Equivalences.standIns(log, AS_CLAIMED, Equivalences.in(log)))
        .as(
            "standIns opens with Equivalences.in(log); handing it the same merges must not"
                + " change which canonical ids get a node or what those nodes say")
        .isEqualTo(Equivalences.standIns(log, AS_CLAIMED));
  }

  private static AssertionRecord edge(String from, String to) {
    return new AssertionRecord(
        from, to, "INFLUENCED_BY", null, null, new Provenance("invented", "invented:1", WHEN, 1.0));
  }

  /**
   * A log the fixed point actually runs on: a minted local side, a merge onto it, a retraction of
   * that local side (which empties the canonical id), a re-merge onto the same canonical id, and an
   * edge naming the local id — which folds onto the emptied canonical id and is withdrawn (#224,
   * #228). An overload handed the wrong emptied set answers differently here, which is what makes
   * the comparisons below able to fail.
   *
   * <p>A second, untouched merge — {@code OTHER_MINTED} onto {@code OTHER_CANONICAL}, never
   * retracted — is there so {@code standIns(log, AS_CLAIMED)} actually builds a stand-in: the
   * retraction above reaches {@code MINTED}'s own node claim as well as its merge (retraction is
   * per-entity, not per-claim), so without this second pair the log-taking form answers an empty
   * map on every fixture above and the comparisons that use it would be vacuous.
   */
  private static List<LoggedAssertion> foldedLog() {
    return List.of(
        new NodeAssertion(
            NEIGHBOUR,
            NodeKind.PERSON,
            "an invented neighbour",
            new Provenance("invented", "invented:1", WHEN, 1.0)),
        LocalEntity.minted(MINTED, NodeKind.WORK, "an invented local work", WHEN),
        SameAs.declared(MINTED, CANONICAL, WHEN),
        new Retraction(MINTED, "the local side was wrong", WHEN),
        SameAs.declared(MINTED, CANONICAL, WHEN),
        LocalEntity.minted(OTHER_MINTED, NodeKind.WORK, "an invented other local work", WHEN),
        SameAs.declared(OTHER_MINTED, OTHER_CANONICAL, WHEN),
        edge(NEIGHBOUR, MINTED));
  }
}
