package com.robsartin.segue.census;

import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.segue.export.LogProjection;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Counted by hand off {@code InventedCensus.log()}.
 *
 * <p>Thirteen nodes, thirteen degrees. {@code WREN} is the busiest at 6 (five folded edges naming
 * it as {@code from}, plus the owner edge {@code CORRECTED --> WREN} naming it as {@code to});
 * {@code PRIZE} sits at 5, exactly ADR 57's floor. The three stand-ins an edge the fold keeps names
 * directly ({@code CORRECTED}, {@code FIRST_CANONICAL}, {@code SETTLED}) are each 1. {@code
 * REROUTED} and the three minted local ids ({@code LEDGER}, {@code SKETCH}, {@code DOUBLE}) are
 * isolated at 0 — a merged local id loses its edges to its canonical id (ADR 59), and nothing folds
 * an edge directly against {@code REROUTED}.
 */
class DegreesTest {

  private static final LogProjection PROJECTION =
      LogProjection.of(new InventedCensus.FakeAssertionLog().with(InventedCensus.log()));

  @Test
  @DisplayName(
      "every node's degree is how many folded edges name it, isolated ones counted at zero")
  void shouldCountIncidenceForEveryNodeWhenTheLogIsFolded() {
    List<Integer> degrees = Degrees.in(PROJECTION).values().stream().sorted().toList();
    assertThat(degrees).containsExactly(0, 0, 0, 0, 1, 1, 1, 2, 2, 2, 2, 5, 6);
  }
}
