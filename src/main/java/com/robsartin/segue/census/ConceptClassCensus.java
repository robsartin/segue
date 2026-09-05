package com.robsartin.segue.census;

import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.domain.NodeRecord;
import com.robsartin.segue.export.LogProjection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Which classes the graph's {@code CONCEPT} nodes state, and how many nodes state each.
 *
 * <p><b>What this is a map of.</b> {@code KindMapper.fromInstanceOf} answers {@code CONCEPT}
 * whenever none of an entity's stated classes is in its whitelist, so an unknown share of the
 * {@code CONCEPT} nodes are people, groups, works or places wearing a class the mapper has never
 * met. The class is already on the node (ADR 42), so this is a fold away, and it is the map of the
 * mapper's gaps that issue #52 last drew by hand with a throwaway probe.
 *
 * <p><b>Two gaps, kept apart.</b> {@code statingNoClass} counts nodes whose source classified them
 * without stating a class at all: no whitelist entry could ever catch those, and folding them into
 * the rows below would overstate what a mapper rule can reach.
 *
 * <p><b>A row is nodes, not statements.</b> A node stating three classes appears on three rows,
 * because each row answers "how many nodes would a rule for this class move"; a node stating one
 * class twice is one node.
 *
 * <p><b>Ten rows, ordered by count.</b> Ten keeps the section the size of its siblings, but the
 * load-bearing reason is that a class stated by a single node is the row that comes closest to
 * naming an entity, and ordering by count descending is what pushes it out. {@code distinctClasses}
 * reports the size of what was cut without printing it. ADR 63's 2026-09-04 amendment is where that
 * is ruled on.
 */
public record ConceptClassCensus(int statingNoClass, int distinctClasses, List<ConceptClass> top) {

  // Deliberately private: a test that read it would assert the cut against itself.
  private static final int TOP = 10;

  /** One class, and how many {@code CONCEPT} nodes state it. */
  public record ConceptClass(String classQid, int nodes) {

    public ConceptClass {
      Objects.requireNonNull(classQid, "classQid");
    }
  }

  public ConceptClassCensus {
    top = List.copyOf(Objects.requireNonNull(top, "top"));
  }

  public static ConceptClassCensus of(LogProjection projection) {
    Objects.requireNonNull(projection, "projection");
    Map<String, Integer> byClass = new TreeMap<>();
    int statingNoClass = 0;
    for (NodeRecord node : projection.nodes().values()) {
      if (node.kind() != NodeKind.CONCEPT) {
        continue;
      }
      if (node.instanceOf().isEmpty()) {
        statingNoClass++;
        continue;
      }
      // A set, so a class a source stated twice about one node is one node on that class's row.
      for (String classQid : Set.copyOf(node.instanceOf())) {
        byClass.merge(classQid, 1, Integer::sum);
      }
    }
    // A total order, so two runs over one unchanged log print one order and a diff between them is
    // never noise — ADR 43's byte-identical contract, held where the counting happens.
    Comparator<Map.Entry<String, Integer>> commonestFirst =
        Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue)
            .reversed()
            .thenComparing(Map.Entry::getKey);
    List<ConceptClass> top =
        byClass.entrySet().stream()
            .sorted(commonestFirst)
            .limit(TOP)
            .map(stated -> new ConceptClass(stated.getKey(), stated.getValue()))
            .toList();
    return new ConceptClassCensus(statingNoClass, byClass.size(), top);
  }
}
