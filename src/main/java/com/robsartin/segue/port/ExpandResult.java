package com.robsartin.segue.port;

import com.robsartin.segue.domain.AssertionRecord;
import java.util.List;
import java.util.Objects;

/**
 * What an expansion produced, and what it could not.
 *
 * <p>An empty list is ambiguous on its own — the source may have been unreachable, the entity may
 * genuinely have no whitelisted claims, or the result may have been cut short by the caller's own
 * bound. The MCP tool layer has to tell a user which, so the port has to carry it.
 */
public record ExpandResult(
    List<AssertionRecord> assertions, boolean sourceUnavailable, boolean truncated) {

  public ExpandResult {
    assertions = List.copyOf(Objects.requireNonNull(assertions, "assertions"));
  }

  public static ExpandResult of(List<AssertionRecord> assertions) {
    return new ExpandResult(assertions, false, false);
  }

  public static ExpandResult unavailable() {
    return new ExpandResult(List.of(), true, false);
  }
}
