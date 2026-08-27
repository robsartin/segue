package com.robsartin.segue.domain;

/**
 * ADR 19 + ADR 24: everything the append-only log records. Nodes are claims too - "Wikidata says
 * Q5593 is a PERSON labelled Pablo Picasso" is as sourced as any edge - so the log, not a mutable
 * node table, stays the one source the graph is derived from. Replay dispatches on the pattern.
 *
 * <p><b>Two of the three rows are sourced claims; the third is not.</b> {@link NodeAssertion} and
 * {@link AssertionRecord} each carry their own {@link Provenance}, which is why {@code
 * provenance()} is declared on them and not here. {@link Retraction} is the owner's own act - "what
 * we recorded about this entity was wrong" - and ADR 44 gives it no provenance for the same reason
 * ADR 33 gives affinity none: there is no source, and belief is not the question. Anything that
 * needs the provenance of a row therefore has to say which kind of row it is holding, which is the
 * correct obligation rather than an inconvenience.
 */
public sealed interface LoggedAssertion permits NodeAssertion, AssertionRecord, Retraction {}
