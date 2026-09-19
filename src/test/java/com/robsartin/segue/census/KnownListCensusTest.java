package com.robsartin.segue.census;

import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.segue.domain.Expanded;
import com.robsartin.segue.domain.Fold;
import com.robsartin.segue.domain.KnownList;
import com.robsartin.segue.domain.LoggedAssertion;
import com.robsartin.segue.domain.NodeAssertion;
import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.domain.SecondHop;
import com.robsartin.segue.ingest.LogProjection;
import com.robsartin.segue.support.KnownListInput;
import com.robsartin.segue.wikidata.KindMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The two populations, counted against a log written for this question alone.
 *
 * <p><b>Its own log, not {@link InventedCensus#log()}.</b> That fixture's rows are numbered and
 * cited by every other hand-counted expectation in this package, and the shapes this class needs —
 * a chain long enough to put a member three hops out, a merge whose two sides are both named, an id
 * the graph holds no node for — would renumber it.
 *
 * <p>The shape, with the two expanded entities marked:
 *
 * <pre>
 *   QUIET — BAND* — SEEN* — LINK_A — LINK_B — FAR
 *   CANONICAL (a merge's canonical side, with no edge at all)
 *   ABSENT    (named by a file, never claimed as a node)
 * </pre>
 *
 * <p>So from the first: the band and the first link are one hop out, the record and the second link
 * are two, and the far entity is three — which is what lets the walk's bound be told apart from a
 * rule that reaches everything.
 */
class KnownListCensusTest {

  /** Expanded, by a forward claim whose reference is a statement id. */
  private static final String SEEN = "Q0901101";

  /** Expanded, by a reverse-discovered edge whose reference names it as the seed. */
  private static final String BAND = "Q0901102";

  /** In the graph, and no row cites it as a seed. */
  private static final String QUIET = "Q0901103";

  /** Three hops from {@link #SEEN}, so the walk's bound has something to miss. */
  private static final String FAR = "Q0901104";

  private static final String LINK_A = "Q0901105";
  private static final String LINK_B = "Q0901106";

  /** Named by a file and never claimed as a node — the first coverage gap there is. */
  private static final String ABSENT = "Q0901107";

  /** Minted by the owner and merged onto {@link #CANONICAL} (ADR 59). */
  private static final String LOCAL = "Q0031";

  /** The merge's canonical side, which the fold gives a stand-in and no edge (ADR 62). */
  private static final String CANONICAL = "Q10000901101";

  private static List<LoggedAssertion> log() {
    return List.of(
        InventedCensus.node(SEEN, NodeKind.PERSON, "An Invented Performer"),
        InventedCensus.node(BAND, NodeKind.GROUP, "An Invented Band"),
        InventedCensus.node(QUIET, NodeKind.WORK, "An Invented Record"),
        InventedCensus.node(FAR, NodeKind.PERSON, "Someone Three Hops Away"),
        InventedCensus.node(LINK_A, NodeKind.PERSON, "The First Step"),
        InventedCensus.node(LINK_B, NodeKind.PERSON, "The Second Step"),
        InventedCensus.edge(SEEN, BAND, "MEMBER_OF", InventedCensus.expandedFrom(SEEN)),
        InventedCensus.edge(
            BAND, QUIET, "INFLUENCED_BY", InventedCensus.discoveredFrom(QUIET, BAND)),
        InventedCensus.edge(SEEN, LINK_A, "MEMBER_OF", InventedCensus.sourced()),
        InventedCensus.edge(LINK_A, LINK_B, "MEMBER_OF", InventedCensus.sourced()),
        InventedCensus.edge(LINK_B, FAR, "MEMBER_OF", InventedCensus.sourced()),
        InventedCensus.minted(LOCAL, "A Thing The Owner Minted"),
        InventedCensus.merged(LOCAL, CANONICAL));
  }

  private static KnownListCensus census(List<String> file, Map<String, Integer> ratings) {
    List<LoggedAssertion> log = log();
    Fold fold = Fold.of(log, KindMapper::rederive);
    LogProjection projection = LogProjection.of(log, fold);
    return KnownListCensus.of(
        new KnownListInput("known.csv", file), Expanded.in(log), projection, fold, ratings);
  }

  private static KnownListCensus census(List<String> file) {
    return census(file, Map.of());
  }

  /** Minted, and never merged — LocalEntity.isLocal on the population it is named in (#344). */
  private static final String UNMERGED_LOCAL = "Q0032";

  @Test
  @DisplayName("a file qid the graph holds no node for counts under named and not under in-graph")
  void shouldCountUnderNamedAndNotInTheGraphWhenTheFileNamesAQidTheGraphLacks() {
    KnownListCensus.Population population = census(List.of(SEEN, ABSENT)).fromFile();

    assertThat(population.named()).as("both ids the file names").isEqualTo(2);
    assertThat(population.inTheGraph()).as("only the one the fold holds a node for").isEqualTo(1);
    assertThat(population.inTheGraphByKind())
        .as("the gap is counted under no kind at all, because it has none")
        .containsEntry(NodeKind.PERSON, 1)
        .containsEntry(NodeKind.WORK, 0);
  }

  @Test
  @DisplayName("a merge whose two sides are both named counts once, on the canonical side")
  void shouldCountAMergeOnceOnTheCanonicalSideWhenBothItsSidesAreNamed() {
    KnownListCensus.Population population = census(List.of(LOCAL, CANONICAL)).fromFile();

    assertThat(population.named()).as("two ids, one entity").isEqualTo(1);
    assertThat(population.inTheGraph()).isEqualTo(1);
    assertThat(population.inTheGraphByKind())
        .as("the stand-in the fold gives the canonical side is a WORK")
        .containsEntry(NodeKind.WORK, 1);
  }

  @Test
  @DisplayName(
      "a minted, unmerged local id counts under in-the-graph and local, and not under"
          + " never-expanded")
  void shouldCountTheLocalIdUnderLocalAndNotUnderNeverExpandedWhenTheFileNamesOne() {
    // Its own log: InventedCensus's LOCAL is merged onto CANONICAL in this file's shared fixture,
    // which is the case this file already covers (the test above). This test needs one that
    // stays unmerged.
    //
    // Deviation from the task-2 brief's own copy (#344 task report): the brief's fixture gave
    // SEEN plain InventedCensus.node(...) provenance, which Expanded.in does not read as a seed
    // (sourced()'s reference is "invented:1", not a qid$... shape) — so SEEN itself would count
    // under neverExpanded and the assertion below (isZero()) would not hold regardless of this
    // task's fix, for a reason unrelated to local exclusion. SEEN's own class javadoc already
    // documents it as "Expanded, by a forward claim whose reference is a statement id" (true of
    // the shared log() fixture); giving it that same expandedFrom(SEEN) provenance here makes
    // this test's own log consistent with that and isolates the assertion to what it is testing.
    List<LoggedAssertion> log =
        List.of(
            new NodeAssertion(
                SEEN, NodeKind.PERSON, "An Invented Performer", InventedCensus.expandedFrom(SEEN)),
            InventedCensus.minted(UNMERGED_LOCAL, "A Thing The Owner Minted And Kept"));
    Fold fold = Fold.of(log, KindMapper::rederive);
    LogProjection projection = LogProjection.of(log, fold);

    KnownListCensus.Population population =
        KnownListCensus.of(
                new KnownListInput("known.csv", List.of(SEEN, UNMERGED_LOCAL)),
                Expanded.in(log),
                projection,
                fold,
                Map.of())
            .fromFile();

    assertThat(population.named()).isEqualTo(2);
    assertThat(population.inTheGraph())
        .as("minting records a node, same as any other claim")
        .isEqualTo(2);
    assertThat(population.local()).as("one of the two is local").isEqualTo(1);
    assertThat(population.neverExpanded())
        .as("the local one is excluded from the shortfall row — no source will ever answer for it")
        .isZero();
    assertThat(population.neverExpandedByKind())
        .as("and it never reaches its kind's row either")
        .containsEntry(NodeKind.WORK, 0);
  }

  @Test
  @DisplayName("an entity no row cites as a seed is never expanded, and the expanded ones are not")
  void shouldCountAnEntityAsNeverExpandedWhenNoRowCitesItAsASeed() {
    KnownListCensus.Population population = census(List.of(SEEN, BAND, QUIET)).fromFile();

    assertThat(population.inTheGraph()).isEqualTo(3);
    assertThat(population.neverExpanded())
        .as("SEEN and BAND are each cited as a seed; only QUIET is not")
        .isEqualTo(1);
    assertThat(population.neverExpandedByKind())
        .as("and it is the WORK, not the PERSON or the GROUP")
        .containsEntry(NodeKind.WORK, 1)
        .containsEntry(NodeKind.PERSON, 0)
        .containsEntry(NodeKind.GROUP, 0);
  }

  @Test
  @DisplayName("a member three hops away is not a known neighbour, and one two hops away is")
  void shouldCountNoKnownNeighbourWhenTheNearestMemberIsThreeHopsAway() {
    assertThat(census(List.of(SEEN, FAR)).fromFile().noKnownNeighbourWithinMaxHops())
        .as("three hops apart, so neither reaches the other within Recommendations.MAX_HOPS")
        .isEqualTo(2);

    assertThat(census(List.of(SEEN, LINK_B)).fromFile().noKnownNeighbourWithinMaxHops())
        .as("the control: exactly two hops apart, so both are reached")
        .isZero();
  }

  @Test
  @DisplayName("an act whose only known neighbour was retracted is isolated")
  void shouldReportTheActAsIsolatedWhenARetractionTookAwayItsOnlyKnownNeighbour() {
    // Restated here from NeighboursTest (#319): the walk no longer takes a LogProjection, so the
    // property "the fold's retraction reaches the walk" is asserted where a fold is in scope, and
    // against the rule's own answer rather than against an adjacency map.
    List<LoggedAssertion> log =
        List.of(
            InventedCensus.node(SEEN, NodeKind.PERSON, "An Invented Performer"),
            InventedCensus.node(BAND, NodeKind.GROUP, "An Invented Band"),
            InventedCensus.edge(SEEN, BAND, "MEMBER_OF", InventedCensus.sourced()),
            InventedCensus.retract(BAND));
    Fold fold = Fold.of(log, KindMapper::rederive);
    LogProjection projection = LogProjection.of(log, fold);

    SecondHop rule =
        SecondHop.of(projection.nodes(), projection.edges(), List.of(SEEN, BAND), Expanded.in(log));

    assertThat(rule.isolated())
        .as("the retraction removed BAND's node claim and every edge touching it")
        .containsExactly(SEEN);
  }

  @Test
  @DisplayName("the second population adds exactly the promotions the ratings map names")
  void shouldAddExactlyThePromotionsTheRatingsMapNamesWhenTheSecondPopulationIsRead() {
    KnownListCensus census =
        census(
            List.of(SEEN, BAND),
            Map.of(
                FAR, KnownList.PROMOTION_RATING, QUIET, KnownList.PROMOTION_RATING - 1, SEEN, 5));

    assertThat(census.fromFile().named()).isEqualTo(2);
    assertThat(census.withPromotions().named())
        .as("FAR alone is promoted: QUIET is rated below the threshold and SEEN is already named")
        .isEqualTo(3);
    assertThat(census.withPromotions().inTheGraphByKind())
        .as("and the one that moved is the PERSON, while the rated WORK stays out")
        .containsEntry(NodeKind.PERSON, 2)
        .containsEntry(NodeKind.GROUP, 1)
        .containsEntry(NodeKind.WORK, 0);
  }

  @Test
  @DisplayName("an isolated act with an unexpanded person beside it is counted under both rows")
  void shouldCountTheIsolatedActUnderWithSomeoneWhenAnUnexpandedPersonIsBesideIt() {
    // FAR is three hops from SEEN, so both are isolated. LINK_B is a PERSON beside FAR that no
    // row cites as a seed; BAND is a GROUP beside SEEN that a row DOES cite.
    KnownListCensus.Population population = census(List.of(SEEN, FAR)).fromFile();

    assertThat(population.noKnownNeighbourWithinMaxHops()).isEqualTo(2);
    assertThat(population.isolatedWithSomeoneToExpand())
        .as("SEEN has LINK_A beside it and FAR has LINK_B, and neither is cited as a seed")
        .isEqualTo(2);
    assertThat(population.isolatedWithNoOne()).isZero();
    assertThat(population.distinctToExpand())
        .as("LINK_A and LINK_B — BAND is a seed, and QUIET is a WORK two hops out")
        .isEqualTo(2);
  }

  @Test
  @DisplayName("the two nested rows partition the isolated row, whatever the population")
  void shouldPartitionTheIsolatedRowWhenEitherPopulationIsRead() {
    KnownListCensus census = census(List.of(SEEN, FAR, QUIET));

    for (KnownListCensus.Population population :
        List.of(census.fromFile(), census.withPromotions())) {
      assertThat(population.isolatedWithSomeoneToExpand() + population.isolatedWithNoOne())
          .as("with someone plus with no one is the isolated row itself")
          .isEqualTo(population.noKnownNeighbourWithinMaxHops());
    }
  }

  @Test
  @DisplayName("a promotion that places an act moves the three rows for the second population")
  void shouldMoveTheThreeRowsWhenAPromotionPlacesAnIsolatedAct() {
    // The file names SEEN and FAR, three hops apart (SEEN-LINK_A-LINK_B-FAR), so both are
    // isolated. Promoting LINK_B puts a member two hops from SEEN (through LINK_A) and one hop
    // from FAR, so in the second population all three of SEEN, LINK_B and FAR reach another
    // member within Recommendations.MAX_HOPS: nobody is left isolated, and there is nothing left
    // to expand. The hand-derived numbers in the brief said "leaving SEEN alone" (isolated=1);
    // the actual run showed 0 for a correct implementation, so the expectation here is fixed to
    // what the rule reports rather than what was guessed.
    KnownListCensus census = census(List.of(SEEN, FAR), Map.of(LINK_B, KnownList.PROMOTION_RATING));

    assertThat(census.fromFile().noKnownNeighbourWithinMaxHops()).isEqualTo(2);
    assertThat(census.withPromotions().noKnownNeighbourWithinMaxHops())
        .as("the promotion places FAR, itself and SEEN — nobody is left isolated")
        .isEqualTo(0);
    assertThat(census.withPromotions().distinctToExpand())
        .as("with nobody isolated, there is nothing beside anyone left to expand")
        .isEqualTo(0);
  }

  @Test
  @DisplayName("a merged entity is expanded when the row citing it names the id the merge retired")
  void shouldCountAMergedEntityAsExpandedWhenTheRowCitesTheIdTheMergeRetired() {
    // Its own log: the census canonicalises the population, so the seed a row cites has to be read
    // through the same fold or the entity is reported as never expanded on work that was done.
    List<LoggedAssertion> log =
        List.of(
            InventedCensus.minted(LOCAL, "A Thing The Owner Minted"),
            InventedCensus.merged(LOCAL, CANONICAL),
            new NodeAssertion(
                SEEN, NodeKind.PERSON, "A Neighbour It Found", InventedCensus.expandedFrom(LOCAL)));
    Fold fold = Fold.of(log, KindMapper::rederive);
    LogProjection projection = LogProjection.of(log, fold);

    KnownListCensus.Population population =
        KnownListCensus.of(
                new KnownListInput("known.csv", List.of(CANONICAL)),
                Expanded.in(log),
                projection,
                fold,
                Map.of())
            .fromFile();

    assertThat(population.inTheGraph())
        .as("the fold holds the stand-in it gives the merge's canonical side")
        .isEqualTo(1);
    assertThat(population.neverExpanded())
        .as("the expansion is recorded against the local id, and counts on the canonical side")
        .isZero();
  }
}
