package com.robsartin.segue.domain;

/**
 * A relationship type, anchored to a Wikidata property so the vocabulary is borrowed rather than
 * invented.
 *
 * <p>{@code wikidataInverted} matters: Wikidata states most creative relations on the WORK ("film
 * P57 director person"), while an affinity graph reads better oriented from the PERSON ("person
 * DIRECTED film"). The flag records which way the underlying triple runs so the ingest adapter can
 * flip it mechanically.
 *
 * <p>{@code wikidataProperty} is null for types no source states directly - COLLABORATED_WITH is
 * derived or model-proposed, never fetched.
 *
 * <p>{@code wikidataFallbackOnly} marks a property Wikidata defines as the <em>inverse</em> of one
 * already in this vocabulary — the same relationship stated from the other end. Ingesting both
 * records one relationship as two edges (issue #33), so a fallback-only type is read only on the
 * degraded path, where the pass that would have found the better-stated direction could not run.
 * See ADR 36.
 */
public record EdgeType(
    String code,
    String wikidataProperty,
    String label,
    boolean symmetric,
    boolean wikidataInverted,
    boolean wikidataFallbackOnly) {

  public static EdgeType direct(String code, String property, String label) {
    return new EdgeType(code, property, label, false, false, false);
  }

  /** Wikidata states this from the object's side; we store it from the subject's. */
  public static EdgeType inverted(String code, String property, String label) {
    return new EdgeType(code, property, label, false, true, false);
  }

  /**
   * A direct type whose Wikidata property is the inverse of another registered one, so ingest reads
   * it only when the better direction is unavailable.
   *
   * <p>There is no inverted variant, and adding one speculatively would be structure ahead of a
   * need: an inverse pair is one relationship, so registering both ends of one <em>as</em> inverted
   * would mean the vocabulary held the same edge twice by construction.
   */
  public static EdgeType fallbackOnly(String code, String property, String label) {
    return new EdgeType(code, property, label, false, false, true);
  }

  public static EdgeType derived(String code, String label, boolean symmetric) {
    return new EdgeType(code, null, label, symmetric, false, false);
  }
}
