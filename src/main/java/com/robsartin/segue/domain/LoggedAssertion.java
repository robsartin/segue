package com.robsartin.segue.domain;

/**
 * ADR 19 + ADR 24: everything the append-only log records. Nodes are claims too - "Wikidata says
 * Q5593 is a PERSON labelled Pablo Picasso" is as sourced as any edge - so the log, not a mutable
 * node table, stays the one source the graph is derived from. Replay dispatches on the pattern.
 *
 * <p><b>Two of the first three rows are sourced claims; the rest are not.</b> {@link NodeAssertion}
 * and {@link AssertionRecord} each carry their own {@link Provenance}, which is why {@code
 * provenance()} is declared on them and not here. {@link Retraction} is the owner's own act - "what
 * we recorded about this entity was wrong" - and ADR 44 gives it no provenance for the same reason
 * ADR 33 gives affinity none: there is no source, and belief is not the question.
 *
 * <p><b>{@link LocalEntity}, {@link OwnerEdge} and {@link SameAs} (#92) are first-person for the
 * same reason.</b> They are the owner's own acts - minting something Wikidata does not model,
 * claiming a relationship, declaring a match - not a source's report, so they carry no {@code
 * Provenance} either. Unlike {@code Retraction}, they do project to the graph (design doc, "The
 * shape"); what provenance value they carry once ingested is {@code IngestService}'s concern, not
 * this claim's.
 *
 * <p>Anything that needs the provenance of a row therefore has to say which kind of row it is
 * holding, which is the correct obligation rather than an inconvenience.
 */
public sealed interface LoggedAssertion
    permits NodeAssertion, AssertionRecord, Retraction, LocalEntity, OwnerEdge, SameAs {}
