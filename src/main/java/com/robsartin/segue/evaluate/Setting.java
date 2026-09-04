package com.robsartin.segue.evaluate;

import com.robsartin.segue.domain.Scorer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * One point on the grid the harness sweeps: which scorer, and which degree floor (ADR 65).
 *
 * <p><b>Fixed, and deliberately not on the command line.</b> The value of this tool is one block a
 * person reads in one sitting, and a flag would produce a stack of runs nobody could line up beside
 * each other. Every setting appears in every run, so two runs a month apart diff row by row.
 *
 * <p><b>Each floor earns its place.</b> {@code 2} is the point below which a normalised score stops
 * meaning anything — {@code RecommendCli} refuses a smaller {@code --min-degree} for that reason.
 * {@code 5} is what the recommender ships with, {@code Recommendations.MIN_CANDIDATE_DEGREE}; a
 * grid that could not reproduce today's default could not say what changing it costs. {@code 12} is
 * the floor ADR 50 took its measurements against, before ADR 45's 2026-08-29 amendment lowered it.
 * {@code 8} sits between the two so the trend between them is read rather than inferred. The
 * numbers are a grid, not a set of defaults: nothing here changes what any tool ships with.
 */
public record Setting(Scorer scorer, int floor) {

  /** The degree floors swept, ascending. */
  public static final List<Integer> FLOORS = List.of(2, 5, 8, 12);

  /** Every scorer against every floor, in {@code Scorer} declaration order. */
  public static final List<Setting> GRID = grid();

  public Setting {
    Objects.requireNonNull(scorer, "scorer");
  }

  private static List<Setting> grid() {
    List<Setting> grid = new ArrayList<>();
    for (Scorer scorer : Scorer.values()) {
      for (int floor : FLOORS) {
        grid.add(new Setting(scorer, floor));
      }
    }
    return List.copyOf(grid);
  }
}
