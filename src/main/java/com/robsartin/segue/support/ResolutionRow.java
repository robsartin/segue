package com.robsartin.segue.support;

import java.util.Objects;

/**
 * One output line of the seven-column mapping and review shape: what the list said, and what was
 * concluded about it.
 *
 * <p>One row per input line rather than one per resolved act, so the mapping can be joined straight
 * back onto the list — including the several spellings that folded onto one answer.
 *
 * <p><b>In {@code support} because two tools read it</b> (#342). The seed tool writes it; the
 * owner-claim tool reads a review file to mint from and appends its own rows to the mapping. The
 * two may not depend on each other — each carries its own ArchUnit fence — so a shape neither owns
 * is the only way they read one file by one rule.
 *
 * <p>{@code QidList} and {@code KnownListInput} already solve this the same way.
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
}
