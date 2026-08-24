package com.robsartin.segue.domain;

import java.util.Objects;

/**
 * An entity. Identity is the Wikidata QID - the universal spine across music, film, literature and
 * everything else. Source-local ids (MBIDs, TMDB ids, last.fm names) resolve TO a qid in the ingest
 * layer; they never appear here.
 */
public record NodeRecord(String qid, NodeKind kind, String label) {

  public NodeRecord {
    Objects.requireNonNull(qid, "qid");
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(label, "label");
    if (!qid.matches("Q\\d+")) {
      throw new IllegalArgumentException("qid must look like Q12345, got: " + qid);
    }
  }
}
