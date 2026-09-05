package com.robsartin.segue.census;

import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.segue.domain.Fold;
import com.robsartin.segue.export.LogProjection;
import com.robsartin.segue.wikidata.KindMapper;
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

  @Test
  @DisplayName("the report is one aligned block, in a fixed order, with a header that names it")
  void shouldRenderTheWholeCensusWhenTheFixtureIsCounted() {
    LogProjection projection =
        LogProjection.of(new InventedCensus.FakeAssertionLog().with(InventedCensus.log()));
    Fold fold = Fold.of(InventedCensus.log(), KindMapper::rederive);
    Census census =
        new Census(
            NodeCensus.of(projection),
            EdgeCensus.of(projection),
            ClaimCensus.of(InventedCensus.log(), projection, fold),
            TasteCensus.of(
                new InventedCensus.FakeAffinityStore()
                    .rated(InventedCensus.WREN, 5)
                    .rated(InventedCensus.SETTLED, 5)
                    .rated(InventedCensus.HOLLOW, 4)
                    .rated(InventedCensus.PRIZE, 4)
                    .rated(InventedCensus.LEDGER, 3)
                    .rated(InventedCensus.DOUBLE, 2)
                    .rated(InventedCensus.NEIGHBOUR, 2)
                    .rated(InventedCensus.GONE, 1)
                    .readRatings(),
                fold,
                projection),
            DegreeCensus.of(projection),
            BridgeCensus.of(projection),
            ConceptClassCensus.of(projection));

    assertThat(String.join("\n", CensusReport.lines(census)))
        .isEqualTo(
            """
            # segue graph census — aggregates and Wikidata class ids only: no labels, no notes, no entity ids (ADR 51, ADR 63).

            nodes
              total                                  13
              PERSON                                  3
              GROUP                                   1
              WORK                                    8
              PLACE                                   0
              EVENT                                   0
              CONCEPT                                 1

            edges
              total                                  11
              dangling                                1
              withdrawn                               0
              backed by also-invented                 1
              backed by invented                      6
              backed by llm:invented                  1
              backed by musicbrainz                   1
              backed by owner                         3
              of type INFLUENCED_BY                   6
              of type MEMBER_OF                       5
              corroborated by 0                       3
              corroborated by 1                       7
              corroborated by 2                       1

            claims
              log rows                               30
              retractions                             1
              rows they removed                       2
              entities they name                      1
              local entities minted                   3
              merges standing                         3
              merges superseded                       2
              merges superseded but edge-referenced   1
              stand-ins                               4
              stand-ins with no edge                  1

            taste
              ratings                                 8
              rated 1                                 1
              rated 2                                 2
              rated 3                                 1
              rated 4                                 2
              rated 5                                 2
              on a local id                           2
              on a stand-in                           1
              on a retracted id                       1

            degree
              floor                                   5
              p50                                     1
              p90                                     5
              p99                                     6
              max                                     6
              at or below the floor                  12

            bridge
              entities MusicBrainz reached            2
              of those, carrying classes              1

            concept classes
              stating no class                        0
              distinct classes                        1
              class Q0900301                          1""");
  }
}
