package com.robsartin.segue.expansion;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * What one expansion did, or why it was refused before any adapter ran.
 *
 * <p><b>Facts, not sentences.</b> Two callers read this and each says it in its own words: {@code
 * SegueService} builds the {@code ToolResult} a language model reads (ADR 27), and the promotion
 * expander counts. A shared sentence would be a wire string with two audiences, one of them a
 * model.
 *
 * <p><b>{@link Expanded#truncated()} and {@link Expanded#sourceUnavailable()} are derived here and
 * nowhere else.</b> ADR 56's rule is that the flags stay aggregate — ORed across adapters, because
 * "is this result complete?" has one answer however many sources ran — while attribution lives in
 * prose. Deriving them from the two lists is what stops that ORing being spelled out at each
 * caller, which is how two readers of one property drift apart.
 */
public sealed interface ExpansionOutcome {

  /** The entity this outcome is about. */
  String qid();

  /** Why an expansion was refused before any adapter ran. */
  enum Reason {
    /** The graph holds no node for it — it has to be added before it can be expanded. */
    UNKNOWN_ENTITY,
    /** The owner minted it, so no source has it and none ever will (ADR 58, ADR 59). */
    LOCAL_ENTITY,
    /** The caller asked for a bound of zero or less. */
    BOUND_NOT_POSITIVE
  }

  /** Refused before any adapter ran. Carries no sentence: see the interface's javadoc. */
  record Refused(String qid, Reason reason) implements ExpansionOutcome {

    public Refused {
      Objects.requireNonNull(qid, "qid");
      Objects.requireNonNull(reason, "reason");
    }
  }

  /**
   * An expansion that ran, whether or not every source answered in full.
   *
   * @param qid the entity that was expanded
   * @param nodesAdded entities newly recorded, counted once each; an existing node whose identity
   *     this call refreshed (issue #55) is not among them
   * @param edgesAdded assertions recorded, per assertion rather than per pair of nodes
   * @param skippedNeighbors distinct entities this call could not identify
   * @param effectiveMax the bound actually applied, after {@code ExpansionBounds.effective}
   * @param unavailableSources {@code SourceAdapter.id()} of each source that could not be reached,
   *     in the order the adapters ran
   * @param truncatingSources {@code SourceAdapter.id()} of each source that cut its own result
   * @param boundCutTheConcatenation the shared budget cut the adapters' combined result, which is
   *     attributable to no single adapter because they were all handed one {@code ExpandContext}
   * @param refusedEndpoints endpoints the graph holds no node for, by endpoint rather than by
   *     assertion, insertion-ordered so a reason string built from them is stable (#233)
   * @param edgesBySource how many recorded assertions each source's provenance claimed. Edges only:
   *     a node's identity comes either from an adapter's own neighbours or from {@code
   *     EntityResolver.fetch}, and the second has no adapter behind it, so attributing a node would
   *     invent the per-adapter authority ADR 56 declined
   */
  record Expanded(
      String qid,
      int nodesAdded,
      int edgesAdded,
      int skippedNeighbors,
      int effectiveMax,
      List<String> unavailableSources,
      List<String> truncatingSources,
      boolean boundCutTheConcatenation,
      List<String> refusedEndpoints,
      Map<String, Integer> edgesBySource)
      implements ExpansionOutcome {

    public Expanded {
      Objects.requireNonNull(qid, "qid");
      unavailableSources = List.copyOf(Objects.requireNonNull(unavailableSources, "unavailable"));
      truncatingSources = List.copyOf(Objects.requireNonNull(truncatingSources, "truncating"));
      refusedEndpoints = List.copyOf(Objects.requireNonNull(refusedEndpoints, "refusedEndpoints"));
      // LinkedHashMap and not Map.copyOf: iteration order is what a report renders, and
      // Map.copyOf's is unspecified and salted per JVM. Measured on this JDK: a five-key copy
      // reproduced insertion order 0 times in 100 fresh JVMs, and a two-key copy did so on
      // roughly two runs in ten — which is why the pin in ExpansionOutcomeTest holds five.
      edgesBySource =
          Collections.unmodifiableMap(
              new LinkedHashMap<>(Objects.requireNonNull(edgesBySource, "edgesBySource")));
    }

    /** At least one source could not be reached at all. Aggregate on purpose (ADR 56). */
    public boolean sourceUnavailable() {
      return !unavailableSources.isEmpty();
    }

    /** An adapter or the shared bound cut the result short. Aggregate for the same reason. */
    public boolean truncated() {
      return !truncatingSources.isEmpty() || boundCutTheConcatenation;
    }
  }
}
