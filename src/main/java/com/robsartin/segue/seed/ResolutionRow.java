package com.robsartin.segue.seed;

import java.util.Objects;

/**
 * One output line: what the list said, and what the tool concluded.
 *
 * <p>One row per input line rather than one per resolved act, so the mapping can be joined straight
 * back onto the list — including the several spellings that folded onto one answer.
 */
public record ResolutionRow(
    String name,
    String kind,
    String status,
    String qid,
    String label,
    Outcome confidence,
    String reason) {

  public ResolutionRow {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(confidence, "confidence");
    Objects.requireNonNull(reason, "reason");
  }

  static ResolutionRow of(SeedRow row, Decision decision) {
    return new ResolutionRow(
        row.name(),
        row.kind(),
        row.status(),
        decision.qid(),
        decision.label(),
        decision.outcome(),
        decision.reason());
  }
}
