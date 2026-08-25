package com.robsartin.segue.port;

import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.domain.NodeRecord;

/**
 * A source of relationships.
 *
 * <p>Adapters emit assertions and know nothing about storage — see
 * docs/adr/0019-assertion-log-source-of-truth.md. An adapter that could write directly would be
 * able to skip the log, which is why ArchUnit forbids it rather than a comment discouraging it.
 *
 * <p>Design rule from CLAUDE.md: adding a source must not require touching the graph layer.
 */
public interface SourceAdapter {

  /** Stable identifier, and the {@code sourceId} every assertion this adapter emits will carry. */
  String id();

  /** Whether this source has anything to say about entities of a given kind. */
  boolean supports(NodeKind kind);

  /**
   * Claims this source makes about {@code seed}, bounded by {@code ctx}.
   *
   * <p>Implementations return what they successfully gathered rather than throwing on partial
   * failure: the caller is a language model, and a partial result it can see beats an exception it
   * can only retry. {@link ExpandResult} carries why the list might be short, because the MCP tool
   * layer has to report that shortfall and an empty list alone cannot say whether the source was
   * unreachable, the entity had nothing to say, or {@code ctx} cut it short.
   */
  ExpandResult expand(NodeRecord seed, ExpandContext ctx);
}
