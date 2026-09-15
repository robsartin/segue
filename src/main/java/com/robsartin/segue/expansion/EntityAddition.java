package com.robsartin.segue.expansion;

import com.robsartin.segue.domain.LocalEntity;
import com.robsartin.segue.domain.NodeAssertion;
import com.robsartin.segue.ingest.IngestService;
import com.robsartin.segue.port.EntityResolver;
import com.robsartin.segue.wikidata.WikidataUnavailableException;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One addition: check the id's shape, ask the resolver for the entity, and record the node claim
 * through {@link IngestService}, which is an upsert (#328).
 *
 * <p><b>Two callers, one body</b> — {@link EntityExpansion}'s argument, one step earlier in the
 * same story. {@code SegueService.addEntity} turns the outcome into the {@code ToolResult} a
 * language model reads (ADR 27); the promotion expander's {@code --add} run adds what a known-list
 * file names that the graph holds no node for, and then expands it. Before this class the body
 * lived in the tool layer, so the expander had either to reach the tool layer or to state the
 * fetch-and-record a second time.
 *
 * <p><b>It writes, and that is why it is fenced with the expansion.</b>
 *
 * <p>{@code ArchitectureTest.onlyTheClientAndTheExpanderExpandAnEntity} already bars every package
 * but {@code mcp}, {@code expand}, {@code app} and this one from depending on {@code expansion}, so
 * this class joins that fence by living here and no rule moves.
 *
 * <p><b>A minted id is refused before the resolver is asked</b> (ADR 58, ADR 59). Its id is one
 * Wikibase's grammar can never allocate, so no source has it and none ever will; asking anyway
 * would spend a round trip to be told what the shape already says, and the answer would come back
 * as "no such entity", which is a different fact. This is the one outcome the MCP tool did not
 * report before #328 — it checked {@code Q\d+} and fetched — so its sentence is new, and it is
 * named in that method rather than here, because a sentence has one audience.
 *
 * <p>What did NOT move here: {@code ToolResult}, the {@code ok} shaping and the refusal sentences.
 * Those are the tool layer's, and the outcome carries facts precisely so that each caller can word
 * them for its own reader.
 */
public final class EntityAddition {

  private static final Logger log = LoggerFactory.getLogger(EntityAddition.class);

  /** ADR 26/ADR 22: identity is a Wikidata QID, always of this shape. */
  private static final Pattern QID = Pattern.compile("Q\\d+");

  private final EntityResolver resolver;
  private final IngestService ingest;

  public EntityAddition(EntityResolver resolver, IngestService ingest) {
    this.resolver = Objects.requireNonNull(resolver, "resolver");
    this.ingest = Objects.requireNonNull(ingest, "ingest");
  }

  /**
   * Add one entity, or say why nothing was recorded.
   *
   * @param qid the entity to add
   * @return {@link AdditionOutcome.Added} with the claim, or {@link AdditionOutcome.Refused}
   */
  public AdditionOutcome add(String qid) {
    Objects.requireNonNull(qid, "qid");
    if (!QID.matcher(qid).matches()) {
      return new AdditionOutcome.Refused(qid, AdditionOutcome.Reason.NOT_A_QID);
    }
    if (LocalEntity.isLocal(qid)) {
      return new AdditionOutcome.Refused(qid, AdditionOutcome.Reason.LOCAL_ENTITY);
    }
    Optional<NodeAssertion> fetched;
    try {
      fetched = resolver.fetch(qid);
    } catch (WikidataUnavailableException e) {
      // Neither the qid nor e.getMessage() may go in the log line: this method is now reachable
      // from a terminal-facing dev tool (#328's expandPromotions --add), which never names an
      // entity on the terminal, and WikidataClient's own message can embed the qid itself — its
      // non-transient-HTTP-status text carries the full request URI, and wbgetentities' URI
      // always carries "ids=<qid>" (see WikidataEntityResolver.entity). The detail is still
      // returned to the caller on the outcome, where the MCP tool's per-call sentence is allowed
      // to name it.
      log.warn("add() source unavailable");
      return new AdditionOutcome.Refused(
          qid, AdditionOutcome.Reason.SOURCE_UNAVAILABLE, e.getMessage());
    }
    if (fetched.isEmpty()) {
      return new AdditionOutcome.Refused(qid, AdditionOutcome.Reason.NO_SUCH_ENTITY);
    }
    NodeAssertion assertion = fetched.get();
    ingest.record(assertion);
    return new AdditionOutcome.Added(qid, assertion);
  }
}
