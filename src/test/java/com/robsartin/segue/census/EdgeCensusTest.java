package com.robsartin.segue.census;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import com.robsartin.segue.domain.LocalEntity;
import com.robsartin.segue.domain.LoggedAssertion;
import com.robsartin.segue.domain.NodeAssertion;
import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.domain.OwnerEdge;
import com.robsartin.segue.domain.Retraction;
import com.robsartin.segue.domain.SameAs;
import com.robsartin.segue.export.LogProjection;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Counted by hand off {@code InventedCensus.log()}.
 *
 * <p>Eleven folded edges. Rows 8 and 9 are one claim from two sources and collapse into one edge
 * with two provenances; row 12 does not survive the retraction at row 30; row 18 names an endpoint
 * nothing claims and is the fixture's one dangling edge; the three owner edges fold onto the
 * canonical ids their local sides were merged onto, except the one claimed against a canonical id
 * directly.
 *
 * <p><b>{@code withdrawn} reads zero off this fixture.</b> Row 30's retraction (of {@link
 * InventedCensus#GONE}) reaches a node a SOURCE claimed at row 4, not a merged local id — {@code
 * GONE} is never the local side of a {@code SameAs} in this log, so {@code
 * Equivalences.retractedStandIns} names no canonical id here and nothing is withdrawn (#224).
 * {@code shouldCountOneWithdrawnEdgeWhenARetractionEmptiesAMergesCanonicalId} below builds the
 * shape that does produce one, since a fixture that always reads zero cannot tell "counts nothing"
 * from "counts correctly."
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

  @Test
  @DisplayName("the fixture's retraction reaches a source-claimed node, so nothing is withdrawn")
  void shouldCountZeroWithdrawnEdgesWhenTheRetractionReachesASourceClaimedNode() {
    assertThat(CENSUS.withdrawn()).isEqualTo(0);
  }

  /**
   * A log too small to hand-count from anywhere else: a node, a minted local entity merged onto a
   * canonical id, an edge claimed directly against that canonical id, and then a retraction of the
   * local side. The retraction empties the canonical id's only stand-in, so {@code
   * Equivalences.retractedStandIns} names it and the edge naming it is withdrawn rather than folded
   * (#224) — one edge, not zero.
   *
   * <p>Ids are unallocatable by shape, never real (ADR 58, 59, 62): {@code LOCAL} carries two
   * leading zeros (a local entity), {@code CANONICAL} eleven digits (a merge's canonical side), and
   * {@code CLAIMANT} one leading zero (an ordinary stand-in).
   */
  @Test
  @DisplayName("a retraction that empties a merge's canonical id withdraws the edge naming it")
  void shouldCountOneWithdrawnEdgeWhenARetractionEmptiesAMergesCanonicalId() {
    String claimant = "Q0900701";
    String local = "Q00701";
    String canonical = "Q10000900701";
    Instant when = Instant.parse("2026-01-01T00:00:00Z");

    List<LoggedAssertion> log =
        List.of(
            new NodeAssertion(claimant, NodeKind.PERSON, "A Claimant", InventedCensus.sourced()),
            LocalEntity.minted(local, NodeKind.WORK, "A Local Entity", when),
            SameAs.declared(local, canonical, when),
            OwnerEdge.claimed(claimant, canonical, "INFLUENCED_BY", when),
            new Retraction(
                local, "an invented reason, unlike anything a real one would say", when));

    LogProjection projection = LogProjection.of(new InventedCensus.FakeAssertionLog().with(log));

    assertThat(EdgeCensus.of(projection).withdrawn()).isEqualTo(1);
  }
}
