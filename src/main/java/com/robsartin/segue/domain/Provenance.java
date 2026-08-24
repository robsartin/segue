package com.robsartin.segue.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * Who claimed something, when, and how much you believe them.
 *
 * <p>{@code sourceId} is the adapter that produced the claim ("wikidata",
 * "lastfm", "llm:claude"). {@code sourceRef} is the citation you can click -
 * a Wikidata statement URI, an API response id, a chat turn.
 *
 * <p>Confidence convention shared by all adapters:
 * <ul>
 *   <li>1.00 - structured and authoritative (Wikidata statement with a reference)</li>
 *   <li>0.80 - structured but unreferenced (Wikidata statement, no source cited)</li>
 *   <li>0.50 - statistical or behavioural (last.fm similarity)</li>
 *   <li>0.30 - model-generated hypothesis, not yet corroborated</li>
 * </ul>
 */
public record Provenance(String sourceId, String sourceRef, Instant assertedAt, double confidence) {

    /** Field separator used by the TinkerGraph provenance codec. */
    public static final String FIELD_SEP = "\t";
    /** Record separator used by the TinkerGraph provenance codec. */
    public static final String RECORD_SEP = "\n";

    public Provenance {
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(assertedAt, "assertedAt");
        if (confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("confidence must be in [0,1], got: " + confidence);
        }
        // The TinkerGraph adapter packs provenance into a delimited string; keeping
        // the separators out of the data means the codec needs no escaping.
        requireNoSeparators(sourceId, "sourceId");
        requireNoSeparators(sourceRef, "sourceRef");
    }

    private static void requireNoSeparators(String value, String field) {
        if (value != null && (value.contains(FIELD_SEP) || value.contains(RECORD_SEP))) {
            throw new IllegalArgumentException(field + " must not contain tabs or newlines");
        }
    }

    /** Model-proposed edges stay quarantined until a real source agrees. */
    public boolean isHypothesis() {
        return sourceId.startsWith("llm:");
    }
}
