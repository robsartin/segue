package com.robsartin.segue.census;

import com.robsartin.segue.domain.Equivalences;
import com.robsartin.segue.domain.Expanded;
import com.robsartin.segue.domain.Fold;
import com.robsartin.segue.domain.KnownList;
import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.domain.NodeRecord;
import com.robsartin.segue.domain.Recommendations;
import com.robsartin.segue.export.LogProjection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * How much of the owner's own list the graph actually covers (issue #311).
 *
 * <p><b>Two populations, one rule.</b> The file's entities, and the same file composed with the
 * ratings map through {@link KnownList#promoted} — what {@code recommend} and {@code rate} reason
 * over (ADR 48). So the second sub-section is those two tools' own answer rather than a third idea
 * about what "known" means, and both are read by {@link #read}, so the two sub-sections cannot come
 * to mean different things.
 *
 * <p><b>Every count is over the RESOLVED population.</b> The ids the file names are read through
 * {@link Equivalences#canonical} and the ratings through {@link Equivalences#resolve} before
 * anything is counted. So a merge whose two sides are both named counts once, on the canonical side
 * — and a rating the owner wrote against a local id promotes the canonical entity, which is what
 * the deck would deal.
 *
 * <p><b>The expansion seeds are read through the same fold, for the same reason.</b> The rows
 * {@link Expanded#in} reads are the log as it was written, so a row recorded before a merge cites
 * the id the owner has since retired; asked about a population already on its canonical side, it
 * would find no seed and report an entity as never expanded on work that was really done. No writer
 * in {@code src/main} records a seed on a retired side today, so this is defensive: one fold, one
 * side, every count, whatever a later writer does.
 *
 * <p><b>A qid the file names that the fold holds no node for counts under {@code named} and not
 * under {@code in the graph}.</b> That is not a gap in this reading; it is the first coverage gap
 * there is, and it is the one the section exists to print.
 *
 * <p><b>No entity is named.</b> Every component is an integer or a map of integers, and the only
 * text is the file's basename, which {@link KnownListInput} is the one home of.
 */
public record KnownListCensus(String file, Population fromFile, Population withPromotions) {

  public KnownListCensus {
    Objects.requireNonNull(file, "file");
    Objects.requireNonNull(fromFile, "fromFile");
    Objects.requireNonNull(withPromotions, "withPromotions");
  }

  /**
   * One population's reading.
   *
   * @param named distinct entities in the population, after the merge fold
   * @param inTheGraph of those, the ones the fold holds a node for
   * @param neverExpanded in the graph, and no row cites them as a seed — {@link Expanded}'s answer
   * @param noKnownNeighbourWithinMaxHops in the graph, and no other member of this population
   *     within {@link Recommendations#MAX_HOPS} — the recommender's own route limit, read by
   *     reference here and named after it rather than after its current value, so that moving the
   *     constant cannot leave this component's name saying something else
   * @param inTheGraphByKind the same in-graph count per kind, all six emitted in {@code NodeKind}
   *     declaration order. {@code NodeCensus} gives the reason it is an {@code EnumMap} rather than
   *     {@code Map.copyOf}: that factory's order is salted per JVM, and ADR 43's byte-identical
   *     contract is what this order serves
   * @param neverExpandedByKind the same, for the ones nothing has expanded
   */
  public record Population(
      int named,
      int inTheGraph,
      int neverExpanded,
      int noKnownNeighbourWithinMaxHops,
      Map<NodeKind, Integer> inTheGraphByKind,
      Map<NodeKind, Integer> neverExpandedByKind) {

    public Population {
      inTheGraphByKind = byKind(inTheGraphByKind, "inTheGraphByKind");
      neverExpandedByKind = byKind(neverExpandedByKind, "neverExpandedByKind");
    }

    private static Map<NodeKind, Integer> byKind(Map<NodeKind, Integer> counts, String name) {
      Objects.requireNonNull(counts, name);
      Map<NodeKind, Integer> copy = new EnumMap<>(NodeKind.class);
      copy.putAll(counts);
      return Collections.unmodifiableMap(copy);
    }
  }

  /**
   * @param known the file, as a basename and a list of ids
   * @param expanded this log's one answer to who has been expanded, unresolved; this method reads
   *     its seeds through the fold
   * @param projection the fold this census already built — the only source of nodes and edges here
   * @param fold this census's one fold (#246) — its equivalences are the only thing read here
   * @param ratings the note-free bulk read, unresolved; this method resolves it
   */
  public static KnownListCensus of(
      KnownListInput known,
      Expanded expanded,
      LogProjection projection,
      Fold fold,
      Map<String, Integer> ratings) {
    Objects.requireNonNull(known, "known");
    Objects.requireNonNull(expanded, "expanded");
    Objects.requireNonNull(projection, "projection");
    Objects.requireNonNull(fold, "fold");
    Objects.requireNonNull(ratings, "ratings");

    Equivalences merges = fold.equivalences();
    List<String> fromFile = canonical(known.qids(), merges);
    // KnownList.promoted appends the ratings map's own keys, which resolve() has already moved
    // onto their canonical side, so nothing here canonicalises a second time.
    List<String> withPromotions = KnownList.promoted(fromFile, merges.resolve(ratings));
    Map<String, Set<String>> adjacency = Neighbours.in(projection);
    Expanded seeds = onTheCanonicalSide(expanded, merges);
    return new KnownListCensus(
        known.name(),
        read(fromFile, seeds, projection, adjacency),
        read(withPromotions, seeds, projection, adjacency));
  }

  /** The seeds on the side the population is counted on, by the fold the population is read by. */
  private static Expanded onTheCanonicalSide(Expanded expanded, Equivalences merges) {
    Set<String> seeds = new LinkedHashSet<>();
    for (String seed : expanded.seeds()) {
      seeds.add(merges.canonical(seed));
    }
    return new Expanded(seeds);
  }

  /** The file's ids on their canonical side, de-duplicated, in the file's own order. */
  private static List<String> canonical(List<String> qids, Equivalences merges) {
    Set<String> resolved = new LinkedHashSet<>();
    for (String qid : qids) {
      resolved.add(merges.canonical(qid));
    }
    return List.copyOf(resolved);
  }

  /** One population's figures, by one rule rather than two — {@code DegreeCensus}'s shape. */
  private static Population read(
      List<String> population,
      Expanded expanded,
      LogProjection projection,
      Map<String, Set<String>> adjacency) {
    Set<String> members = Set.copyOf(population);
    Map<NodeKind, Integer> inTheGraphByKind = zeroed();
    Map<NodeKind, Integer> neverExpandedByKind = zeroed();
    int inTheGraph = 0;
    int neverExpanded = 0;
    int alone = 0;
    for (String qid : population) {
      NodeRecord node = projection.nodes().get(qid);
      if (node == null) {
        continue;
      }
      inTheGraph++;
      inTheGraphByKind.merge(node.kind(), 1, Integer::sum);
      if (!expanded.covers(qid)) {
        neverExpanded++;
        neverExpandedByKind.merge(node.kind(), 1, Integer::sum);
      }
      if (!Neighbours.reaches(adjacency, qid, members, Recommendations.MAX_HOPS)) {
        alone++;
      }
    }
    return new Population(
        population.size(), inTheGraph, neverExpanded, alone, inTheGraphByKind, neverExpandedByKind);
  }

  private static Map<NodeKind, Integer> zeroed() {
    Map<NodeKind, Integer> counts = new EnumMap<>(NodeKind.class);
    for (NodeKind kind : NodeKind.values()) {
      counts.put(kind, 0);
    }
    return counts;
  }
}
