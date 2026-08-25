package com.robsartin.segue.wikidata;

import com.fasterxml.jackson.databind.JsonNode;
import com.robsartin.segue.domain.AssertionRecord;
import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.domain.NodeRecord;
import com.robsartin.segue.port.ExpandContext;
import com.robsartin.segue.port.ExpandResult;
import com.robsartin.segue.port.SourceAdapter;
import java.time.Clock;
import java.util.List;
import java.util.Objects;

/**
 * Expansion from Wikidata.
 *
 * <p>Wikidata is first among sources deliberately: no API key, cross-domain by construction, and it
 * supplies both the QID identity spine and the edge vocabulary (ADR 22).
 *
 * <p><b>Failures degrade rather than propagate.</b> The eventual caller is a language model, and a
 * partial result it can see and act on beats an exception it can only retry. An unreachable
 * Wikidata yields an empty expansion, not a thrown error.
 */
public final class WikidataSourceAdapter implements SourceAdapter {

  private static final String SOURCE_ID = "wikidata";

  private final WikidataEntityResolver resolver;
  private final Clock clock;

  public WikidataSourceAdapter(WikidataEntityResolver resolver, Clock clock) {
    this.resolver = Objects.requireNonNull(resolver, "resolver");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Override
  public String id() {
    return SOURCE_ID;
  }

  @Override
  public boolean supports(NodeKind kind) {
    // Wikidata spans every kind segue models. That breadth is the reason it is the first source.
    return true;
  }

  @Override
  public ExpandResult expand(NodeRecord seed, ExpandContext ctx) {
    Objects.requireNonNull(seed, "seed");
    Objects.requireNonNull(ctx, "ctx");
    try {
      JsonNode entity = resolver.entity(seed.qid());
      if (entity == null) {
        return ExpandResult.of(List.of());
      }
      List<AssertionRecord> mapped = ClaimMapper.map(seed.qid(), entity, clock.instant());
      List<AssertionRecord> limited = mapped.stream().limit(ctx.maxNewEdges()).toList();
      boolean truncated = limited.size() < mapped.size();
      return new ExpandResult(limited, false, truncated);
    } catch (WikidataUnavailableException e) {
      // Deliberately swallowed rather than thrown. The tool layer reports the shortfall to
      // the model via ExpandResult.sourceUnavailable(); throwing here would turn a partial
      // answer into no answer.
      return ExpandResult.unavailable();
    }
  }
}
