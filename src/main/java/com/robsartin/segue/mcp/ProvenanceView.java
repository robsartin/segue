package com.robsartin.segue.mcp;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * The wire shape of {@link com.robsartin.segue.domain.Provenance}.
 *
 * <p>{@code sourceRef} is nullable by design in the domain (not every claim has a clickable
 * citation) — {@link CandidateView}'s Javadoc explains why that has to be said again here rather
 * than on the domain record itself.
 */
public record ProvenanceView(
    String sourceId, @Nullable String sourceRef, Instant assertedAt, double confidence) {}
