package com.robsartin.segue.domain;

import java.util.Objects;

/**
 * One possible answer to "which entity did you mean".
 *
 * <p>Not a {@link NodeRecord}: nothing has been decided or written yet. The {@code description} is
 * the field that makes a choice possible — Wikidata's short gloss is what separates the painter
 * from the film named after him — so it is carried even though the graph never stores it.
 */
public record Candidate(String qid, String label, String description, NodeKind kind) {

  public Candidate {
    Objects.requireNonNull(label, "label");
    Objects.requireNonNull(kind, "kind");
    // Reuse NodeRecord's qid rule rather than restating it, so the two cannot drift.
    new NodeRecord(qid, kind, label);
  }

  /** Human-readable form for disambiguation. */
  public String describe() {
    return description == null
        ? qid + " — " + label + " [" + kind + "]"
        : qid + " — " + label + " (" + description + ") [" + kind + "]";
  }
}
