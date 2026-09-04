package com.robsartin.segue.domain;

import java.time.LocalDate;
import java.util.List;
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
    Provenance provenance)
    implements LoggedAssertion {

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

  /**
   * The distinct entities this edge names: two, or one where it is a self-loop (#228).
   *
   * <p><b>One rule with three readers, because it was three copies before.</b> {@code
   * IngestService.record}'s gate asks the running graph whether it holds a node for each of these
   * (#233), {@code IngestService.claim}'s gate asks the log's fold, and {@code GraphProjector}'s
   * boot diagnosis names each one no node stands for. Each had spelled "the same entity twice is
   * one entity" for itself, and each was a separate chance to tell an operator to repair two things
   * where there is one.
   *
   * <p>It is deliberately about the assertion AS IT STANDS, not about what a fold would make of it.
   * {@code Equivalences.foldEndpoints} collapses a pair a merge brought together and yields nothing
   * at all for it; a self-loop the claim itself wrote survives that, and is the case this counts.
   */
  public List<String> endpoints() {
    return fromQid.equals(toQid) ? List.of(fromQid) : List.of(fromQid, toQid);
  }
}
