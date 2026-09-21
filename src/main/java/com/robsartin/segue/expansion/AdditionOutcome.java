package com.robsartin.segue.expansion;

import com.robsartin.segue.domain.NodeAssertion;
import java.util.Objects;

/**
 * What one addition did, or why it was refused before the node claim was recorded.
 *
 * <p><b>Facts, not sentences</b> — {@link ExpansionOutcome}'s rule, and for its reason. Two callers
 * read this: {@code SegueService} builds the {@code ToolResult} a language model reads (ADR 27),
 * and the promotion expander tallies. A shared sentence would be a wire string with two audiences,
 * one of them a model.
 *
 * <p><b>{@link Refused#detail()} is the source's own words and nothing else.</b>
 *
 * <p>Only {@link Reason#SOURCE_UNAVAILABLE} has any: the sentence the MCP tool has always returned
 * for an outage quotes the exception's message, so the fact has to travel or the tool's words would
 * change. It is empty for every other reason, and it is never an entity — see {@link #NO_DETAIL}.
 */
public sealed interface AdditionOutcome {

  /** The entity this outcome is about. */
  String qid();

  /** The detail of a refusal that has nothing to add beyond its reason. */
  String NO_DETAIL = "";

  /** Why nothing was recorded. */
  enum Reason {
    /** The id is well formed and Wikidata has no entity at it. */
    NO_SUCH_ENTITY,
    /** The resolver could not be reached at all. */
    SOURCE_UNAVAILABLE,
    /** Not {@code Q} followed by digits, so no source could be asked for it. */
    NOT_A_QID,
    /** The owner minted it (ADR 58, ADR 59), so no source has it and none ever will. */
    LOCAL_ENTITY
  }

  /**
   * Recorded, through {@code IngestService.record}, which is an upsert.
   *
   * @param qid the entity that was added
   * @param node the claim that was recorded, so a caller can build its view from it without
   *     fetching the entity a second time
   */
  record Added(String qid, NodeAssertion node) implements AdditionOutcome {

    public Added {
      Objects.requireNonNull(qid, "qid");
      Objects.requireNonNull(node, "node");
    }
  }

  /** Nothing was recorded. Carries no sentence: see the interface's javadoc. */
  record Refused(String qid, Reason reason, String detail) implements AdditionOutcome {

    public Refused {
      Objects.requireNonNull(qid, "qid");
      Objects.requireNonNull(reason, "reason");
      Objects.requireNonNull(detail, "detail");
    }

    /** A refusal whose reason is the whole of what there is to say. */
    public Refused(String qid, Reason reason) {
      this(qid, reason, NO_DETAIL);
    }
  }
}
