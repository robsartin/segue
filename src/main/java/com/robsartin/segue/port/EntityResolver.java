package com.robsartin.segue.port;

import com.robsartin.segue.domain.Candidate;
import com.robsartin.segue.domain.NodeAssertion;
import com.robsartin.segue.domain.NodeKind;
import java.util.List;
import java.util.Optional;

/**
 * A source that can turn a name into an identity.
 *
 * <p>Separate from {@link SourceAdapter} on purpose (ADR 25). Resolution and expansion are
 * different capabilities with different implementors: a statistical similarity source expands but
 * has nothing to resolve, and folding both into one interface would force it to throw.
 */
public interface EntityResolver {

  /** Stable identifier, matching the {@link SourceAdapter#id()} of the same source. */
  String id();

  /**
   * Candidates for a free-text query, best match first.
   *
   * @param kind narrow to one kind, or null for any
   */
  List<Candidate> search(String query, NodeKind kind, int limit);

  /** The source's claim about one entity, or empty if it does not know the identifier. */
  Optional<NodeAssertion> fetch(String qid);
}
