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
 * <p><b>{@code TreeMap} rather than {@code Map.copyOf}</b>, which iterates in an order salted per
 * JVM — so the report would come out differently on two runs over one unchanged log, against ADR
 * 43. {@link NodeCensus} solves the same problem with an {@code EnumMap} because its keys are a
 * fixed enum with a meaningful declaration order; these keys are arbitrary text — source ids and
 * type codes off the log, and a corroboration count — so ascending is the only order available and
 * it is a real one. What the report shows is pinned by {@code containsExactly} in {@code
 * EdgeCensusTest}, not by {@code CensusReport}, which walks these maps and adds nothing.
 *
 * <p><b>{@code withdrawn} is read off {@link LogProjection#withdrawnEdges()}, not recomputed.</b>
 * {@code LogProjection} already counts, while it folds, the edges a retraction withdrew by naming a
 * canonical id it emptied (#224) — a sibling of {@code dangling}, and this class asks it the same
 * way it asks for {@code dangling}, rather than re-deriving "what withdrawal means" a second time
 * over {@code Equivalences}.
 */
public record EdgeCensus(
    Map<String, Integer> byType,
    Map<String, Integer> bySource,
    Map<Integer, Integer> byCorroboration,
    int total,
    int dangling,
    int withdrawn) {

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
        byType,
        bySource,
        byCorroboration,
        projection.edges().size(),
        projection.danglingEdges(),
        projection.withdrawnEdges());
  }
}
