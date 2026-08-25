package com.robsartin.segue.mcp;

import java.time.LocalDate;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The wire shape of {@link com.robsartin.segue.domain.EdgeRecord}.
 *
 * <p>{@code validFrom}/{@code validTo} are nullable by design — an open-ended interval means
 * "always" on one or both sides (see {@code EdgeRecord.validAt}) — so unlike the domain record this
 * one has to say so for a schema-generating client. {@link CandidateView}'s Javadoc explains why.
 */
public record EdgeView(
    String fromQid,
    String toQid,
    String typeCode,
    @Nullable LocalDate validFrom,
    @Nullable LocalDate validTo,
    List<ProvenanceView> sources) {}
