package com.robsartin.segue.census;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import com.robsartin.segue.export.LogProjection;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Counted by hand off {@code InventedCensus.log()}.
 *
 * <p>Eleven folded edges. Rows 7 and 8 are one claim from two sources and collapse into one edge
 * with two provenances; row 11 does not survive the retraction at row 29; row 17 names an endpoint
 * nothing claims and is the fixture's one dangling edge; the three owner edges fold onto the
 * canonical ids their local sides were merged onto, except the one claimed against a canonical id
 * directly.
 */
class EdgeCensusTest {

  private static final EdgeCensus CENSUS =
      EdgeCensus.of(
          LogProjection.of(new InventedCensus.FakeAssertionLog().with(InventedCensus.log())));

  @Test
  @DisplayName("the total is every folded edge, and the dangling one is not among them")
  void shouldCountTheFoldedEdgesWhenOneNamesAnUnclaimedEndpoint() {
    assertThat(CENSUS.total()).isEqualTo(11);
    assertThat(CENSUS.dangling()).isEqualTo(1);
  }

  @Test
  @DisplayName("edges are counted by the type code the log holds")
  void shouldCountByTypeWhenTheFoldHoldsTwoTypes() {
    assertThat(CENSUS.byType()).containsExactly(entry("INFLUENCED_BY", 6), entry("MEMBER_OF", 5));
  }

  @Test
  @DisplayName("an edge two sources assert is counted under both")
  void shouldCountAnEdgeUnderEverySourceWhenTwoSourcesAssertIt() {
    assertThat(CENSUS.bySource())
        .containsExactly(
            entry("also-invented", 1),
            entry("invented", 6),
            entry("llm:invented", 1),
            entry("musicbrainz", 1),
            entry("owner", 3));
  }

  @Test
  @DisplayName("an owner-only edge corroborates zero, and is counted rather than dropped")
  void shouldCountTheOwnerOnlyEdgesAtZeroWhenCorroborationIsDistributed() {
    assertThat(CENSUS.byCorroboration()).containsExactly(entry(0, 3), entry(1, 7), entry(2, 1));
  }
}
