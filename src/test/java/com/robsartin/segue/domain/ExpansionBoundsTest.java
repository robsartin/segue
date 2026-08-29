package com.robsartin.segue.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Issue #112: a single {@code expand_entity} call on a broad {@code CONCEPT} can flood the graph —
 * religion and accounting both hit the Wikidata reverse-lookup's 501-row cap, landing at in-graph
 * degree 500 from one call. {@link ExpansionBounds} is the ceiling that stops that, applied by
 * {@code SegueService.expandEntity}.
 */
class ExpansionBoundsTest {

  @Test
  @DisplayName("a CONCEPT is capped even when a larger bound is requested")
  void capsAConcept() {
    assertThat(ExpansionBounds.effective(NodeKind.CONCEPT, 200))
        .isEqualTo(ExpansionBounds.CONCEPT_CEILING);
  }

  @Test
  @DisplayName("a request smaller than the ceiling is honoured, so the ceiling is not a default")
  void doesNotRaiseASmallerRequest() {
    assertThat(ExpansionBounds.effective(NodeKind.CONCEPT, 5)).isEqualTo(5);
  }

  @Test
  @DisplayName("every other kind is unbounded by this rule")
  void leavesTheOtherKindsAlone() {
    for (NodeKind kind : NodeKind.values()) {
      if (kind != NodeKind.CONCEPT) {
        assertThat(ExpansionBounds.effective(kind, 200)).isEqualTo(200);
      }
    }
  }
}
