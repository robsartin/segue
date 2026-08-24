package com.robsartin.segue.domain;

import java.time.LocalDate;
import java.util.Objects;

/**
 * One source's claim that a relationship exists. This is the unit of ingest and the append-only
 * source of truth: adapters emit assertions, never edges.
 *
 * <p>Note the two independent time dimensions:
 *
 * <ul>
 *   <li>{@code validFrom}/{@code validTo} - when the fact was true in the world (Blixa Bargeld was
 *       a Bad Seed from 1983 to 2003)
 *   <li>{@code provenance.assertedAt} - when you learned it
 * </ul>
 *
 * Sources are allowed to disagree about validity, which is why the dates live on the assertion
 * rather than on the derived edge.
 */
public record AssertionRecord(
    String fromQid,
    String toQid,
    String typeCode,
    LocalDate validFrom,
    LocalDate validTo,
    Provenance provenance) {

  public AssertionRecord {
    Objects.requireNonNull(fromQid, "fromQid");
    Objects.requireNonNull(toQid, "toQid");
    Objects.requireNonNull(typeCode, "typeCode");
    Objects.requireNonNull(provenance, "provenance");
    if (validFrom != null && validTo != null && validTo.isBefore(validFrom)) {
      throw new IllegalArgumentException("validTo precedes validFrom");
    }
  }

  /** Stable key for the underlying relationship, ignoring who said it. */
  public String edgeKey() {
    return fromQid + " " + typeCode + " " + toQid;
  }
}
