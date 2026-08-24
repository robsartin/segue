package com.robsartin.segue.domain;

/**
 * A relationship type, anchored to a Wikidata property so the vocabulary is
 * borrowed rather than invented.
 *
 * <p>{@code wikidataInverted} matters: Wikidata states most creative relations on
 * the WORK ("film P57 director person"), while an affinity graph reads better
 * oriented from the PERSON ("person DIRECTED film"). The flag records which way
 * the underlying triple runs so the ingest adapter can flip it mechanically.
 *
 * <p>{@code wikidataProperty} is null for types no source states directly -
 * COLLABORATED_WITH is derived or model-proposed, never fetched.
 */
public record EdgeType(
        String code,
        String wikidataProperty,
        String label,
        boolean symmetric,
        boolean wikidataInverted) {

    public static EdgeType direct(String code, String property, String label) {
        return new EdgeType(code, property, label, false, false);
    }

    /** Wikidata states this from the object's side; we store it from the subject's. */
    public static EdgeType inverted(String code, String property, String label) {
        return new EdgeType(code, property, label, false, true);
    }

    public static EdgeType derived(String code, String label, boolean symmetric) {
        return new EdgeType(code, null, label, symmetric, false);
    }
}
