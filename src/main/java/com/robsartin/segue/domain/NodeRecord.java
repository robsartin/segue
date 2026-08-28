package com.robsartin.segue.domain;

import java.util.List;
import java.util.Objects;

/**
 * An entity. Identity is the Wikidata QID - the universal spine across music, film, literature and
 * everything else. Source-local ids (MBIDs, TMDB ids, last.fm names) resolve TO a qid in the ingest
 * layer; they never appear here.
 *
 * <p>{@code instanceOf} carries the raw classes the source stated - Wikidata's {@code P31} - beside
 * the {@link NodeKind} they were mapped to. The kind is derived and the classes are not, and a
 * projection that has both can re-derive the kind when the mapping improves, with no network (issue
 * #60, ADR 42). Order is the order the source stated them in, because the mapping takes the first
 * class it recognises. A source that classifies without stating classes leaves it empty.
 */
public record NodeRecord(String qid, NodeKind kind, String label, List<String> instanceOf) {

  public NodeRecord {
    Objects.requireNonNull(qid, "qid");
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(label, "label");
    instanceOf = List.copyOf(Objects.requireNonNull(instanceOf, "instanceOf"));
    Qid.check(qid);
    for (String classQid : instanceOf) {
      // Every value is a QID, so the packed encodings that store this list - one space-separated
      // column in the log, one literal on a graph vertex - cannot be broken by data and need no
      // escaping. That is the same argument ProvenanceCodec makes by forbidding its separators
      // in Provenance, made here by constraining the whole value instead.
      if (!Qid.looksLikeAQid(classQid)) {
        throw new IllegalArgumentException("instanceOf must look like Q12345, got: " + classQid);
      }
    }
  }

  /** An entity whose source stated no classes of its own. */
  public NodeRecord(String qid, NodeKind kind, String label) {
    this(qid, kind, label, List.of());
  }
}
