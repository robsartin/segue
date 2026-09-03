package com.robsartin.segue.census;

import com.robsartin.segue.domain.EdgeRecord;
import com.robsartin.segue.domain.Provenance;
import com.robsartin.segue.export.LogProjection;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * What the fold holds, by type, by who said it, and by how many said it.
 *
 * <p><b>{@code bySource} does not sum to {@code total}, and that is not a defect.</b> An edge two
 * sources assert is counted under both, which is the collapse that makes corroboration countable at
 * all (ADR 19). The report's row label says "backed by" for exactly this reason.
 *
 * <p><b>The type codes and source ids are raw text off the log</b>, not a vocabulary this class
 * knows. A row the current {@code EdgeTypes} no longer registers still appears, because ADR 19
 * forbids deleting the claim that carries it, and a census that silently dropped it would be
 * answering a different question. {@code CensusIsSafeToPasteTest}'s "no Q-shaped token anywhere"
 * assertion is what covers the one hazard that comes with printing stored text.
 *
 * <p>Sorted maps rather than {@code Map.copyOf}, for {@link NodeCensus}'s reason.
 */
public record EdgeCensus(
    Map<String, Integer> byType,
    Map<String, Integer> bySource,
    Map<Integer, Integer> byCorroboration,
    int total,
    int dangling) {

  public EdgeCensus {
    byType = Collections.unmodifiableMap(new TreeMap<>(Objects.requireNonNull(byType, "byType")));
    bySource =
        Collections.unmodifiableMap(new TreeMap<>(Objects.requireNonNull(bySource, "bySource")));
    byCorroboration =
        Collections.unmodifiableMap(
            new TreeMap<>(Objects.requireNonNull(byCorroboration, "byCorroboration")));
  }

  public static EdgeCensus of(LogProjection projection) {
    Objects.requireNonNull(projection, "projection");
    Map<String, Integer> byType = new TreeMap<>();
    Map<String, Integer> bySource = new TreeMap<>();
    Map<Integer, Integer> byCorroboration = new TreeMap<>();
    for (EdgeRecord edge : projection.edges()) {
      byType.merge(edge.typeCode(), 1, Integer::sum);
      edge.sources().stream()
          .map(Provenance::sourceId)
          .distinct()
          .forEach(sourceId -> bySource.merge(sourceId, 1, Integer::sum));
      byCorroboration.merge(edge.corroboration(), 1, Integer::sum);
    }
    return new EdgeCensus(
        byType, bySource, byCorroboration, projection.edges().size(), projection.danglingEdges());
  }
}
