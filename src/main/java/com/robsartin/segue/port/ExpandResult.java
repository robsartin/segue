package com.robsartin.segue.port;

import com.robsartin.segue.domain.AssertionRecord;
import com.robsartin.segue.domain.NodeAssertion;
import java.util.List;
import java.util.Objects;

/**
 * What an expansion produced, and what it could not.
 *
 * <p>An empty list is ambiguous on its own — the source may have been unreachable, the entity may
 * genuinely have no whitelisted claims, or the result may have been cut short by the caller's own
 * bound. The MCP tool layer has to tell a user which, so the port has to carry it.
 *
 * <p>{@code neighbors} is an optimisation the port has to know about, because only the adapter can
 * supply it. An expansion names entities the graph has never seen, and the caller cannot record an
 * edge before both endpoints exist; without this field it resolves each one with its own round trip
 * (see {@code SegueService.expandEntity}). That was affordable when expanding a person found four
 * neighbours. Once the reverse lookup finds seventy-odd (ADR 36), a source that already knows a
 * neighbour's label and kind should say so rather than let the caller ask again. An adapter that
 * does not know is not obliged to guess: an absent neighbour simply falls back to the fetch.
 */
public record ExpandResult(
    List<AssertionRecord> assertions,
    List<NodeAssertion> neighbors,
    boolean sourceUnavailable,
    boolean truncated) {

  public ExpandResult {
    assertions = List.copyOf(Objects.requireNonNull(assertions, "assertions"));
    neighbors = List.copyOf(Objects.requireNonNull(neighbors, "neighbors"));
  }

  /** An adapter that discovers relationships but nothing about the entities they connect. */
  public ExpandResult(
      List<AssertionRecord> assertions, boolean sourceUnavailable, boolean truncated) {
    this(assertions, List.of(), sourceUnavailable, truncated);
  }

  public static ExpandResult of(List<AssertionRecord> assertions) {
    return new ExpandResult(assertions, List.of(), false, false);
  }

  public static ExpandResult unavailable() {
    return new ExpandResult(List.of(), List.of(), true, false);
  }
}
