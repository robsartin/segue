package com.robsartin.segue.domain;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The known-list acts the graph cannot place, and the entities worth fetching next (#319).
 *
 * <p><b>One rule, two readers.</b> The census counts by it and the expander chooses its population
 * by it — {@code graphCensus --known} and {@code expandPromotions --second-hop} — on the shape
 * {@link Expanded} and {@link Retractions} already set: one home per question, whoever asks. A
 * second copy of "isolated" in the tool that spends money on it is how the diagnosis and the run
 * come to disagree.
 *
 * <p><b>A pure function of four inputs.</b> The fold's nodes and edges, a known population on its
 * canonical side, and {@link Expanded} on the same side. It reads no rating, no timestamp and no
 * label, holds no store, opens nothing and makes no network call — so it takes ordinary unit tests
 * on invented graphs.
 *
 * <p><b>The bound is {@link Recommendations#MAX_HOPS}, by reference.</b> It is the recommender's
 * own route limit, read here rather than restated, so that moving the constant moves this rule with
 * it — the same reason the census row is named after the constant rather than after its current
 * value.
 *
 * <p><b>{@link #WORTH_EXPANDING} is the one statement of which kinds are worth fetching.</b> The
 * census's rows, the expander's population and ADR 63's and ADR 66's amendments all cite it rather
 * than naming the kinds again.
 */
public final class SecondHop {

  /**
   * The kinds a second hop fetches: a person or a group beside an act the graph cannot place is a
   * bandmate, a producer or a label-mate, and its own neighbourhood is what could connect that act
   * to something known. A work, a place, an event or a concept beside it is a catalogue entry or a
   * category, and expanding one buys the ring nothing.
   */
  public static final Set<NodeKind> WORTH_EXPANDING = Set.of(NodeKind.PERSON, NodeKind.GROUP);

  private final Map<String, NodeRecord> nodes;
  private final Map<String, Set<String>> adjacency;
  private final Expanded expanded;

  /** In the population's own order, distinct — a {@code LinkedHashSet} so both hold at once. */
  private final Set<String> isolated;

  private SecondHop(
      Map<String, NodeRecord> nodes,
      Map<String, Set<String>> adjacency,
      Expanded expanded,
      Set<String> isolated) {
    this.nodes = nodes;
    this.adjacency = adjacency;
    this.expanded = expanded;
    this.isolated = isolated;
  }

  /**
   * Read the rule for one population.
   *
   * @param nodes the fold's nodes — {@code LogProjection.nodes()}
   * @param edges the fold's edges — {@code LogProjection.edges()}, which has already dropped the
   *     dangling and the withdrawn and applied retraction and merge (ADR 44)
   * @param population the known population, on its canonical side, in its own order
   * @param expanded who some row cites as an expansion's seed, on the same side
   */
  public static SecondHop of(
      Map<String, NodeRecord> nodes,
      List<EdgeRecord> edges,
      List<String> population,
      Expanded expanded) {
    Objects.requireNonNull(nodes, "nodes");
    Objects.requireNonNull(edges, "edges");
    Objects.requireNonNull(population, "population");
    Objects.requireNonNull(expanded, "expanded");
    Map<String, NodeRecord> heldNodes = Map.copyOf(nodes);
    Map<String, Set<String>> adjacency = Neighbours.in(heldNodes, edges);
    Set<String> members = Set.copyOf(population);
    Set<String> isolated = new LinkedHashSet<>();
    for (String qid : population) {
      if (heldNodes.containsKey(qid)
          && !Neighbours.reaches(adjacency, qid, members, Recommendations.MAX_HOPS)) {
        isolated.add(qid);
      }
    }
    return new SecondHop(heldNodes, adjacency, expanded, Collections.unmodifiableSet(isolated));
  }

  /**
   * The members the graph holds a node for that have no <i>other</i> member nearby.
   *
   * <p>Nearby means within {@link Recommendations#MAX_HOPS} hops, and this returns them in the
   * population's own order.
   *
   * <p>A member the fold holds no node for is neither isolated nor an error: it is the first
   * coverage gap there is.
   *
   * <p>The census counts a missing member under {@code named} rather than {@code in the graph}.
   */
  public List<String> isolated() {
    return List.copyOf(isolated);
  }

  /**
   * The nodes one folded edge from an isolated member whose kind is in {@link #WORTH_EXPANDING} and
   * that {@link Expanded} does not cover.
   *
   * @throws IllegalArgumentException if {@code isolated} is not one of {@link #isolated()} — the
   *     question is about an act the graph cannot place, and asking it about any other id is a
   *     caller error rather than an empty answer
   */
  public Set<String> toExpandBeside(String isolated) {
    Objects.requireNonNull(isolated, "isolated");
    if (!this.isolated.contains(isolated)) {
      throw new IllegalArgumentException("not an isolated member of this population: " + isolated);
    }
    Set<String> beside = new LinkedHashSet<>();
    for (String neighbour : adjacency.getOrDefault(isolated, Set.of())) {
      NodeRecord node = nodes.get(neighbour);
      if (node != null && WORTH_EXPANDING.contains(node.kind()) && !expanded.covers(neighbour)) {
        beside.add(neighbour);
      }
    }
    return Collections.unmodifiableSet(beside);
  }

  /**
   * The union of {@link #toExpandBeside} over every isolated member, distinct, in first-seen order
   * over {@link #isolated()}.
   *
   * <p>At the current hop limit no isolated act can share a neighbour with another member — a
   * shared neighbour puts them two hops apart, which places both — so the union is distinct by
   * construction. It is kept a set so the answer does not depend on the hop limit.
   */
  public List<String> toExpand() {
    Set<String> union = new LinkedHashSet<>();
    for (String member : isolated) {
      union.addAll(toExpandBeside(member));
    }
    return List.copyOf(union);
  }
}
