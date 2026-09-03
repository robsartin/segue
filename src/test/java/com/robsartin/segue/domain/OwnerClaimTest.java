package com.robsartin.segue.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * #92: the three claims the owner can make about something Wikidata does not model. Each is a
 * first-person {@link LoggedAssertion}, on {@link Retraction}'s precedent - its own validation, no
 * {@link Provenance} - and identity reuses ADR 58's unallocatable-QID mechanism (issue #141).
 *
 * <p><b>Validation is split by what can change.</b> The constructor enforces Wikidata's own
 * identifier grammar, which this project cannot re-tighten, and is also how a logged row is
 * rebuilt. The static factory - {@code minted}, {@code claimed}, {@code declared} - enforces this
 * project's conventions at the moment of claiming. Re-running a convention on read would make a row
 * written before the convention moved undecodable, in a log ADR 19 forbids deleting from, and the
 * local-entity shape moved once already.
 */
class OwnerClaimTest {

  @Test
  @DisplayName("should refuse a local entity whose id Wikidata could allocate")
  void shouldRefuseALocalEntityWhoseIdWikidataCouldAllocate() {
    assertThatThrownBy(
            () -> new LocalEntity("Q42", NodeKind.PERSON, "a minted person", Instant.EPOCH))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("allocatable");
  }

  @Test
  @DisplayName("should accept a local entity on an id Wikidata cannot allocate")
  void shouldAcceptALocalEntityOnAnIdWikidataCannotAllocate() {
    LocalEntity minted =
        LocalEntity.minted("Q00900042", NodeKind.PERSON, "a minted person", Instant.EPOCH);

    assertThat(minted.toNode().instanceOf()).isEmpty();
    assertThat(minted.toNode().qid()).isEqualTo("Q00900042");
  }

  @Test
  @DisplayName(
      "should refuse a local entity with only one leading zero - that is a stand-in's shape")
  void shouldRefuseALocalEntityShapedLikeAStandIn() {
    // Q0900001 is one of Fixture's own stand-ins (ADR 58) - unallocatable, but not ours: one
    // leading zero, not two.
    assertThatThrownBy(
            () -> LocalEntity.minted("Q0900001", NodeKind.PERSON, "a minted person", Instant.EPOCH))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName(
      "should refuse a local entity numerically inside a range a future stand-in migration"
          + " will reach - the shape check does not depend on the number")
  void shouldRefuseALocalEntityThatWouldCollideUnderANumericFloor() {
    // Issue #171 will migrate the Q900100 stand-in family into leading-zero form as Q0900100 -
    // numerically larger than any floor small enough to admit this class's own worked examples.
    // A single leading zero refuses it regardless of the number, which is the whole point.
    assertThatThrownBy(
            () -> LocalEntity.minted("Q0900100", NodeKind.PERSON, "a minted person", Instant.EPOCH))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("should refuse a merge whose canonical side is not a real Wikidata id")
  void shouldRefuseAMergeWhoseCanonicalSideIsNotARealWikidataId() {
    assertThatThrownBy(() -> SameAs.declared("Q00900042", "Q0900043", Instant.EPOCH))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /**
   * The two sides of a merge are exact complements over {@code Q\d+} — {@code Equivalences} argues
   * from that, and only from that, that a canonical id can never be the local side of another merge
   * and so no chain can form. ADR 62 admits a second unallocatable shape on the canonical side, so
   * both halves of the complement are asserted here rather than left to follow from "allocatable".
   */
  @Test
  @DisplayName("should accept a merge onto the eleven-digit canonical stand-in shape")
  void shouldAcceptAMergeOntoTheCanonicalStandInShape() {
    SameAs merge = SameAs.declared("Q00900042", "Q10000000900", Instant.EPOCH);

    assertThat(merge.localQid()).isEqualTo("Q00900042");
    assertThat(merge.canonicalQid()).isEqualTo("Q10000000900");
    assertThat(merge.assertedAt()).isEqualTo(Instant.EPOCH);
  }

  @Test
  @DisplayName("should refuse a local entity on the eleven-digit canonical stand-in shape")
  void shouldRefuseALocalEntityOnTheCanonicalStandInShape() {
    assertThatThrownBy(
            () ->
                new LocalEntity("Q10000000900", NodeKind.PERSON, "a minted person", Instant.EPOCH))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("allocatable");
  }

  @Test
  @DisplayName("should refuse an owner edge whose type nothing registers")
  void shouldRefuseAnOwnerEdgeWhoseTypeNothingRegisters() {
    assertThatThrownBy(() -> OwnerEdge.claimed("Q0900042", "Q42", "NOT_A_TYPE", Instant.EPOCH))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("should refuse a rebuilt local entity on an id Wikidata could allocate")
  void shouldRefuseARebuiltLocalEntityOnAnIdWikidataCouldAllocate() {
    // The grammar half is enforced on EVERY path, reconstruction included: borrowing an id
    // Wikidata could hand to something else is a collision, not a convention that may move.
    assertThatThrownBy(
            () -> new LocalEntity("Q42", NodeKind.PERSON, "a minted person", Instant.EPOCH))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("allocatable");
  }

  @Test
  @DisplayName("should rebuild a local entity whose shape the convention no longer accepts")
  void shouldRebuildALocalEntityWhoseShapeTheConventionNoLongerAccepts() {
    // The convention half is not: a row written before c837265 moved the shape is still in the
    // log, and every reader still has to decode it (ADR 19).
    LocalEntity legacy =
        new LocalEntity(
            "Q0900042", NodeKind.PERSON, "minted before the shape moved", Instant.EPOCH);

    assertThat(legacy.qid()).isEqualTo("Q0900042");
  }

  @Test
  @DisplayName("should rebuild an owner edge whose type the vocabulary no longer registers")
  void shouldRebuildAnOwnerEdgeWhoseTypeTheVocabularyNoLongerRegisters() {
    OwnerEdge legacy = new OwnerEdge("Q00900042", "Q42", "RETIRED_TYPE", Instant.EPOCH);

    assertThat(legacy.typeCode()).isEqualTo("RETIRED_TYPE");
  }

  @Test
  @DisplayName("should accept an owner edge whose type is registered")
  void shouldAcceptAnOwnerEdgeWhoseTypeIsRegistered() {
    OwnerEdge edge = OwnerEdge.claimed("Q00900042", "Q42", "INFLUENCED_BY", Instant.EPOCH);

    assertThat(edge.fromQid()).isEqualTo("Q00900042");
    assertThat(edge.toQid()).isEqualTo("Q42");
    assertThat(edge.typeCode()).isEqualTo("INFLUENCED_BY");
    assertThat(edge.assertedAt()).isEqualTo(Instant.EPOCH);
  }
}
