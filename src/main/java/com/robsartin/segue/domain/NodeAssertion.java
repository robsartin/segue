package com.robsartin.segue.domain;

import java.util.Objects;

/**
 * A source's claim that an entity exists with a given kind and label. Logged like any edge claim
 * (ADR 24) so replay can rebuild nodes without a mutable node table - the one thing that would
 * otherwise not be derived from the log, silently breaking ADR 19.
 */
public record NodeAssertion(String qid, NodeKind kind, String label, Provenance provenance)
    implements LoggedAssertion {

  public NodeAssertion {
    Objects.requireNonNull(qid, "qid");
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(label, "label");
    Objects.requireNonNull(provenance, "provenance");
  }

  /** The projection's view of this claim. */
  public NodeRecord toNode() {
    return new NodeRecord(qid, kind, label);
  }
}
