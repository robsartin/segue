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
        new LocalEntity("Q0900042", NodeKind.PERSON, "a minted person", Instant.EPOCH);

    assertThat(minted.toNode().instanceOf()).isEmpty();
    assertThat(minted.toNode().qid()).isEqualTo("Q0900042");
  }

  @Test
  @DisplayName("should refuse a local entity below the local-entity band, even if unallocatable")
  void shouldRefuseALocalEntityBelowTheLocalEntityBand() {
    // Q0900001 is one of Fixture's own stand-ins (ADR 58) - unallocatable, but not ours.
    assertThatThrownBy(
            () -> new LocalEntity("Q0900001", NodeKind.PERSON, "a minted person", Instant.EPOCH))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("should refuse a merge whose canonical side is not a real Wikidata id")
  void shouldRefuseAMergeWhoseCanonicalSideIsNotARealWikidataId() {
    assertThatThrownBy(() -> new SameAs("Q0900042", "Q0900043", Instant.EPOCH))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("should refuse an owner edge whose type nothing registers")
  void shouldRefuseAnOwnerEdgeWhoseTypeNothingRegisters() {
    assertThatThrownBy(() -> new OwnerEdge("Q0900042", "Q42", "NOT_A_TYPE", Instant.EPOCH))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
