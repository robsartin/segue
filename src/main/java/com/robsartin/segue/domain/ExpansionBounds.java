package com.robsartin.segue.domain;

/**
 * Issue #112: a ceiling on how many new edges one {@code expand_entity} call may add to a {@code
 * CONCEPT}, applied on top of whatever the caller requested.
 *
 * <p><b>The hazard is a caller asking for a large bound, not an adapter that defaults to one.</b>
 * Wikidata's reverse lookup ({@code ReverseClaims}) answers a broad subject with as many rows as
 * the caller's bound allows, capped server-side at 501: expanding religion (Q9174) or accounting
 * (Q4116214) each land at in-graph degree 500 from a single call, with WikiProject Religion sitting
 * at rank 29 of the kept prefix — a flood, not a discovery. And the hazard is not confined to
 * obviously abstract subjects: Java already expands to 91 edges today via {@code P737}/{@code
 * P361}, no new property involved. So this is a <b>ceiling</b>, applied with {@code Math.min}
 * against whatever the caller asked for — not a default, which a caller could simply not request
 * and bypass entirely. {@link #effective} must return the ceiling for {@code effective(CONCEPT,
 * 200)} and must return the smaller, honoured request for {@code effective(CONCEPT, 5)}; getting
 * that direction backwards defeats the whole rule.
 *
 * <p><b>{@link #CONCEPT_CEILING} is a judgement, informed by the graph's own shape.</b> On a copy
 * of the real 123,752-node graph, 89 {@code CONCEPT} nodes (0.072%) sit at in-graph degree 10 or
 * above — confirming ADR 31's recorded figure — and of those, 70 sit between 10 and 24. Below 25
 * covers 16,931 of 16,950 {@code CONCEPT}s, 99.88% of them; below 50 covers 16,946, 99.96%. A
 * ceiling of 25 therefore lets ordinary {@code CONCEPT}s — everything the graph has accumulated by
 * ordinary, indirect means — expand without ever touching it, while stopping a direct flood two
 * orders of magnitude short of the 500-edge failure mode. Only 19 {@code CONCEPT}s in the whole
 * graph sit at or past it, and every one measured in that tail so far is career recognition, not an
 * ordinary subject.
 *
 * <p>Nothing here knows what a source adapter is or what {@code maxNewEdges} means to one — see
 * {@code SegueService.expandEntity}, which is the only caller and feeds the result into the same
 * {@code truncated} reporting {@code find_paths} already uses (issue #65): a bound that can bite
 * must be reported by the result that hit it.
 */
public final class ExpansionBounds {

  /**
   * The most new edges a single {@code expand_entity} call may add to a {@code CONCEPT}, whatever
   * the caller asked for. See the class Javadoc for the measurement behind this number.
   */
  public static final int CONCEPT_CEILING = 25;

  private ExpansionBounds() {}

  /**
   * The bound to actually apply for one expansion of {@code kind}, given what the caller requested.
   *
   * <p>For every kind but {@code CONCEPT} this is {@code requested}, unchanged. For {@code CONCEPT}
   * it is {@code min(requested, CONCEPT_CEILING)} — a ceiling, so a request already under the
   * ceiling is honoured exactly, and only a request that would exceed it is pulled down.
   */
  public static int effective(NodeKind kind, int requested) {
    return kind == NodeKind.CONCEPT ? Math.min(requested, CONCEPT_CEILING) : requested;
  }
}
