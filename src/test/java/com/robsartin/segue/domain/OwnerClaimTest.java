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
        new LocalEntity("Q00900042", NodeKind.PERSON, "a minted person", Instant.EPOCH);

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
            () -> new LocalEntity("Q0900001", NodeKind.PERSON, "a minted person", Instant.EPOCH))
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
            () -> new LocalEntity("Q0900100", NodeKind.PERSON, "a minted person", Instant.EPOCH))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("should refuse a merge whose canonical side is not a real Wikidata id")
  void shouldRefuseAMergeWhoseCanonicalSideIsNotARealWikidataId() {
    assertThatThrownBy(() -> new SameAs("Q00900042", "Q0900043", Instant.EPOCH))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName(
      "should accept a merge whose local side is a local entity and whose canonical side is real")
  void shouldAcceptAMergeOntoARealWikidataId() {
    SameAs merge = new SameAs("Q00900042", "Q900", Instant.EPOCH);

    assertThat(merge.localQid()).isEqualTo("Q00900042");
    assertThat(merge.canonicalQid()).isEqualTo("Q900");
    assertThat(merge.assertedAt()).isEqualTo(Instant.EPOCH);
  }

  @Test
  @DisplayName("should refuse an owner edge whose type nothing registers")
  void shouldRefuseAnOwnerEdgeWhoseTypeNothingRegisters() {
    assertThatThrownBy(() -> new OwnerEdge("Q0900042", "Q42", "NOT_A_TYPE", Instant.EPOCH))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("should accept an owner edge whose type is registered")
  void shouldAcceptAnOwnerEdgeWhoseTypeIsRegistered() {
    OwnerEdge edge = new OwnerEdge("Q00900042", "Q42", "INFLUENCED_BY", Instant.EPOCH);

    assertThat(edge.fromQid()).isEqualTo("Q00900042");
    assertThat(edge.toQid()).isEqualTo("Q42");
    assertThat(edge.typeCode()).isEqualTo("INFLUENCED_BY");
    assertThat(edge.assertedAt()).isEqualTo(Instant.EPOCH);
  }
}
