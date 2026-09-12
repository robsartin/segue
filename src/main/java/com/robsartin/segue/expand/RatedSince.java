package com.robsartin.segue.expand;

import java.time.Instant;
import java.util.Objects;

/**
 * The filter a run applied to its promotions, and what it cost (issue #307).
 *
 * <p><b>A record rather than two arguments on the renderer.</b> {@code EvaluationReport.lines}
 * takes an instant and its two counts as three positional arguments and then guards at runtime
 * against the combination that cannot happen. Wrapping them makes that combination unrepresentable:
 * a block with no filter passes {@code Optional.empty()} and has no count to disagree with. It also
 * keeps {@link #excluded} from sitting two positions along a call from {@code maxNewEdges} as a
 * second bare {@code int}, which is a swap no compiler can see.
 *
 * <p><b>There is nowhere here to put an identifier.</b> {@code Instant.toString} emits only digits,
 * {@code +}, {@code -}, {@code :}, {@code .}, {@code T} and {@code Z}, never a letter but {@code T}
 * and {@code Z}, so the one operator-supplied fact the block carries cannot bring a qid into it
 * however the flag was spelled — the same property {@code EvaluationReport} relies on for its own
 * split line.
 *
 * @param since the instant the operator gave, parsed — never the string they typed
 * @param excluded how many promotions were dropped because their rating's last write fell before it
 */
public record RatedSince(Instant since, int excluded) implements Population {

  public RatedSince {
    Objects.requireNonNull(since, "since");
    if (excluded < 0) {
      throw new IllegalArgumentException("excluded cannot be negative, got " + excluded);
    }
  }
}
