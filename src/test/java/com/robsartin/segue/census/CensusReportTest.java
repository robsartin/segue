package com.robsartin.segue.census;

import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.segue.domain.Expanded;
import com.robsartin.segue.domain.Fold;
import com.robsartin.segue.domain.LoggedAssertion;
import com.robsartin.segue.export.LogProjection;
import com.robsartin.segue.support.KnownListInput;
import com.robsartin.segue.wikidata.KindMapper;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The whole report for the invented fixture, pinned exactly.
 *
 * <p>Every number here is one the seven section tests already assert on its own; what this adds is
 * the labels, the order and the alignment — the part a person reads, and the part no per-number
 * test can see.
 *
 * <p><b>The column is arithmetic, not a copy of the output.</b> It is {@link CensusReport}'s own
 * rule — stated there, and not restated here — applied by hand to this fixture's widest label and
 * widest count. If a run disagrees on the padding then the padding is the finding; if it disagrees
 * on a number then the number is, and the seven section tests say which.
 *
 * <p><b>The three taste lines below the scores are not a partition.</b> A local id, a stand-in and
 * a retracted id are independent properties of the rated entity — nothing here proves them
 * disjoint, and one rating could be counted on two of the three lines — so they are printed as
 * three counts and never summed.
 */
class CensusReportTest {

  /**
   * The eight invented ratings, written once because both tests read them — the second one twice
   * over, since {@code KnownListCensus} composes its second population out of the same map.
   */
  private static final Map<String, Integer> RATINGS =
      new InventedCensus.FakeAffinityStore()
          .rated(InventedCensus.WREN, 5)
          .rated(InventedCensus.SETTLED, 5)
          .rated(InventedCensus.HOLLOW, 4)
          .rated(InventedCensus.PRIZE, 4)
          .rated(InventedCensus.LEDGER, 3)
          .rated(InventedCensus.DOUBLE, 2)
          .rated(InventedCensus.NEIGHBOUR, 2)
          .rated(InventedCensus.GONE, 1)
          .readRatings();

  /**
   * An invented known-list, in the file's own order, chosen so that no case the section exists to
   * report is vacuous. {@code UNCLAIMED} is named and is not a node; the two sides of one merge are
   * both named, as {@code LEDGER} and {@code CORRECTED}; {@code REROUTED} is the stand-in with no
   * edge, so it is in the graph and has no known neighbour at any distance; and {@code WREN} and
   * {@code HOLLOW} are the two the fixture's expansion-shaped references cover.
   */
  private static final KnownListInput KNOWN =
      new KnownListInput(
          "known.csv",
          List.of(
              InventedCensus.WREN,
              InventedCensus.HOLLOW,
              InventedCensus.UNCLAIMED,
              InventedCensus.LEDGER,
              InventedCensus.CORRECTED,
              InventedCensus.REROUTED));

  /** The fixture's census, composed exactly as {@code Census.of} composes one. */
  private static Census census(Optional<KnownListInput> known) {
    List<LoggedAssertion> log = InventedCensus.log();
    LogProjection projection = LogProjection.of(new InventedCensus.FakeAssertionLog().with(log));
    Fold fold = Fold.of(log, KindMapper::rederive);
    return new Census(
        NodeCensus.of(projection),
        EdgeCensus.of(projection),
        ClaimCensus.of(log, projection, fold),
        TasteCensus.of(RATINGS, fold, projection),
        DegreeCensus.of(projection),
        BridgeCensus.of(projection),
        ConceptClassCensus.of(projection),
        known.map(file -> KnownListCensus.of(file, Expanded.in(log), projection, fold, RATINGS)));
  }

  @Test
  @DisplayName("the report is one aligned block, in a fixed order, with a header that names it")
  void shouldRenderTheWholeCensusWhenTheFixtureIsCounted() {
    Census census = census(Optional.empty());

    assertThat(String.join("\n", CensusReport.lines(census)))
        .isEqualTo(
            """
            # segue graph census — aggregates and Wikidata class ids only: no labels, no notes, no entity ids (ADR 51, ADR 63).

            nodes
              total                                   13
              PERSON                                   3
              GROUP                                    1
              WORK                                     8
              PLACE                                    0
              EVENT                                    0
              CONCEPT                                  1

            edges
              total                                   11
              dangling                                 1
              withdrawn                                0
              backed by also-invented                  1
              backed by invented                       6
              backed by llm:invented                   1
              backed by musicbrainz                    1
              backed by owner                          3
              of type INFLUENCED_BY                    6
              of type MEMBER_OF                        5
              corroborated by 0                        3
              corroborated by 1                        7
              corroborated by 2                        1

            claims
              log rows                                30
              retractions                              1
              rows they removed                        2
              entities they name                       1
              local entities minted                    3
              merges standing                          3
              merges superseded                        2
              merges superseded but edge-referenced    1
              stand-ins                                4
              stand-ins with no edge                   1

            taste
              ratings                                  8
              rated 1                                  1
              rated 2                                  2
              rated 3                                  1
              rated 4                                  2
              rated 5                                  2
              on a local id                            2
              on a stand-in                            1
              on a retracted id                        1

            degree
              floor                                    5
              p50                                      1
              p90                                      5
              p99                                      6
              max                                      6
              at or below the floor                   12
              at or below the floor %                 92
              PERSON p50                               2
              PERSON p90                               6
              PERSON p99                               6
              PERSON max                               6
              PERSON at or below the floor             2
              PERSON at or below the floor %          67
              GROUP p50                                2
              GROUP p90                                2
              GROUP p99                                2
              GROUP max                                2
              GROUP at or below the floor              1
              GROUP at or below the floor %          100
              WORK p50                                 0
              WORK p90                                 5
              WORK p99                                 5
              WORK max                                 5
              WORK at or below the floor               8
              WORK at or below the floor %           100
              PLACE p50                                0
              PLACE p90                                0
              PLACE p99                                0
              PLACE max                                0
              PLACE at or below the floor              0
              PLACE at or below the floor %            0
              EVENT p50                                0
              EVENT p90                                0
              EVENT p99                                0
              EVENT max                                0
              EVENT at or below the floor              0
              EVENT at or below the floor %            0
              CONCEPT p50                              2
              CONCEPT p90                              2
              CONCEPT p99                              2
              CONCEPT max                              2
              CONCEPT at or below the floor            1
              CONCEPT at or below the floor %        100

            bridge
              entities MusicBrainz reached             2
              of those, carrying classes               1

            concept classes
              stating no class                         0
              distinct classes                         1
              class Q0900301                           1""");
  }

  @Test
  @DisplayName("the same block with the known-list section appended, when a file was named")
  void shouldAppendTheKnownListSectionWhenAKnownListWasGiven() {
    Census census = census(Optional.of(KNOWN));

    assertThat(String.join("\n", CensusReport.lines(census)))
        .isEqualTo(
            """
            # segue graph census — aggregates and Wikidata class ids only: no labels, no notes, no entity ids (ADR 51, ADR 63).

            nodes
              total                                   13
              PERSON                                   3
              GROUP                                    1
              WORK                                     8
              PLACE                                    0
              EVENT                                    0
              CONCEPT                                  1

            edges
              total                                   11
              dangling                                 1
              withdrawn                                0
              backed by also-invented                  1
              backed by invented                       6
              backed by llm:invented                   1
              backed by musicbrainz                    1
              backed by owner                          3
              of type INFLUENCED_BY                    6
              of type MEMBER_OF                        5
              corroborated by 0                        3
              corroborated by 1                        7
              corroborated by 2                        1

            claims
              log rows                                30
              retractions                              1
              rows they removed                        2
              entities they name                       1
              local entities minted                    3
              merges standing                          3
              merges superseded                        2
              merges superseded but edge-referenced    1
              stand-ins                                4
              stand-ins with no edge                   1

            taste
              ratings                                  8
              rated 1                                  1
              rated 2                                  2
              rated 3                                  1
              rated 4                                  2
              rated 5                                  2
              on a local id                            2
              on a stand-in                            1
              on a retracted id                        1

            degree
              floor                                    5
              p50                                      1
              p90                                      5
              p99                                      6
              max                                      6
              at or below the floor                   12
              at or below the floor %                 92
              PERSON p50                               2
              PERSON p90                               6
              PERSON p99                               6
              PERSON max                               6
              PERSON at or below the floor             2
              PERSON at or below the floor %          67
              GROUP p50                                2
              GROUP p90                                2
              GROUP p99                                2
              GROUP max                                2
              GROUP at or below the floor              1
              GROUP at or below the floor %          100
              WORK p50                                 0
              WORK p90                                 5
              WORK p99                                 5
              WORK max                                 5
              WORK at or below the floor               8
              WORK at or below the floor %           100
              PLACE p50                                0
              PLACE p90                                0
              PLACE p99                                0
              PLACE max                                0
              PLACE at or below the floor              0
              PLACE at or below the floor %            0
              EVENT p50                                0
              EVENT p90                                0
              EVENT p99                                0
              EVENT max                                0
              EVENT at or below the floor              0
              EVENT at or below the floor %            0
              CONCEPT p50                              2
              CONCEPT p90                              2
              CONCEPT p99                              2
              CONCEPT max                              2
              CONCEPT at or below the floor            1
              CONCEPT at or below the floor %        100

            bridge
              entities MusicBrainz reached             2
              of those, carrying classes               1

            concept classes
              stating no class                         0
              distinct classes                         1
              class Q0900301                           1

            known list — known.csv

              file
                named                                  5
                in the graph                           4
                never expanded                         2
                no known neighbour within 2 hops       1
                PERSON in the graph                    1
                PERSON never expanded                  0
                GROUP in the graph                     1
                GROUP never expanded                   0
                WORK in the graph                      2
                WORK never expanded                    2
                PLACE in the graph                     0
                PLACE never expanded                   0
                EVENT in the graph                     0
                EVENT never expanded                   0
                CONCEPT in the graph                   0
                CONCEPT never expanded                 0

              file and promotions
                named                                  7
                in the graph                           6
                never expanded                         4
                no known neighbour within 2 hops       1
                PERSON in the graph                    1
                PERSON never expanded                  0
                GROUP in the graph                     1
                GROUP never expanded                   0
                WORK in the graph                      4
                WORK never expanded                    4
                PLACE in the graph                     0
                PLACE never expanded                   0
                EVENT in the graph                     0
                EVENT never expanded                   0
                CONCEPT in the graph                   0
                CONCEPT never expanded                 0""");
  }
}
