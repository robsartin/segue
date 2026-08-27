package com.robsartin.segue.domain;

import java.util.List;
import java.util.Objects;

/**
 * A source's claim that an entity exists with a given kind and label. Logged like any edge claim
 * (ADR 24) so replay can rebuild nodes without a mutable node table - the one thing that would
 * otherwise not be derived from the log, silently breaking ADR 19.
 *
 * <p>The claim carries {@code instanceOf} - the raw classes the source stated, Wikidata's {@code
 * P31} - beside the {@link NodeKind} it derived from them. An append-only log that kept only the
 * derivation could never revisit it: every improvement to the mapping meant re-fetching the entity
 * from its source (issue #55). See ADR 42.
 */
public record NodeAssertion(
    String qid, NodeKind kind, String label, List<String> instanceOf, Provenance provenance)
    implements LoggedAssertion {

  public NodeAssertion {
    Objects.requireNonNull(qid, "qid");
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(label, "label");
    Objects.requireNonNull(provenance, "provenance");
    instanceOf = List.copyOf(Objects.requireNonNull(instanceOf, "instanceOf"));
  }

  /** A claim from a source that classifies entities without stating classes of its own. */
  public NodeAssertion(String qid, NodeKind kind, String label, Provenance provenance) {
    this(qid, kind, label, List.of(), provenance);
  }

  /** The projection's view of this claim. */
  public NodeRecord toNode() {
    return new NodeRecord(qid, kind, label, instanceOf);
  }

  /** The same claim with a different kind - what a projection produces when it re-derives. */
  public NodeAssertion withKind(NodeKind rederived) {
    return kind == rederived
        ? this
        : new NodeAssertion(qid, rederived, label, instanceOf, provenance);
  }
}
