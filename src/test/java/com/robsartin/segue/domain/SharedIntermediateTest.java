package com.robsartin.segue.domain;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The invariants that let {@link Scorer} divide without checking (ADR 45). */
class SharedIntermediateTest {

  @Test
  @DisplayName("an intermediate joins two things, so it cannot have fewer than two edges")
  void anIntermediateHasAtLeastTwoEdges() {
    assertThatThrownBy(() -> new SharedIntermediate("Q900101", "Q900201", 1, 1.0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("two");
  }

  @Test
  @DisplayName("a connection worth nothing is not a connection")
  void aWeightlessConnectionIsRefused() {
    assertThatThrownBy(() -> new SharedIntermediate("Q900101", "Q900201", 4, 0.0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("weight");
  }
}
