package com.robsartin.segue.domain;

/**
 * ADR 19 + ADR 24: everything the append-only log records. Nodes are claims too - "Wikidata says
 * Q5593 is a PERSON labelled Pablo Picasso" is as sourced as any edge - so the log, not a mutable
 * node table, stays the one source the graph is derived from. Replay dispatches on the pattern.
 */
public sealed interface LoggedAssertion permits NodeAssertion, AssertionRecord {

  /** Who claimed it and when - every logged assertion carries its own provenance. */
  Provenance provenance();
}
