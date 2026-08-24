package com.robsartin.segue.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * A relationship as the graph currently believes it, carrying every assertion that supports it.
 * Multiple sources agreeing collapse into ONE edge with several provenance entries - that collapse
 * is what makes corroboration countable. Different relationship TYPES between the same pair stay
 * separate edges, which is why the store has to be a multigraph.
 */
public record EdgeRecord(
    String fromQid,
    String toQid,
    String typeCode,
    LocalDate validFrom,
    LocalDate validTo,
    List<Provenance> sources) {

  public EdgeRecord {
    Objects.requireNonNull(fromQid, "fromQid");
    Objects.requireNonNull(toQid, "toQid");
    Objects.requireNonNull(typeCode, "typeCode");
    sources = List.copyOf(Objects.requireNonNull(sources, "sources"));
  }

  /** How many DISTINCT sources back this edge. The corroboration signal. */
  public int corroboration() {
    return (int) sources.stream().map(Provenance::sourceId).distinct().count();
  }

  /** True when every supporting assertion came from a model rather than a real source. */
  public boolean isUncorroboratedHypothesis() {
    return !sources.isEmpty() && sources.stream().allMatch(Provenance::isHypothesis);
  }

  public double bestConfidence() {
    return sources.stream().mapToDouble(Provenance::confidence).max().orElse(0.0);
  }

  /** Open-ended intervals count as true; null on both sides means "always". */
  public boolean validAt(LocalDate when) {
    if (validFrom != null && when.isBefore(validFrom)) return false;
    return validTo == null || !when.isAfter(validTo);
  }

  public String key() {
    return fromQid + " " + typeCode + " " + toQid;
  }
}
