package com.robsartin.segue.fixture;

import com.robsartin.segue.domain.AssertionRecord;
import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.domain.NodeRecord;
import com.robsartin.segue.port.ExpandContext;
import com.robsartin.segue.port.ExpandResult;
import com.robsartin.segue.port.SourceAdapter;
import java.util.List;

/**
 * A source backed by the Nick Cave fixture, with no network.
 *
 * <p>Exists so the SPI has a second implementation and so everything downstream of it can be tested
 * deterministically. Lives in test sources: its QIDs are placeholders and must never reach a real
 * store (ADR 22).
 */
public final class FixtureSourceAdapter implements SourceAdapter {

  @Override
  public String id() {
    return "fixture";
  }

  @Override
  public boolean supports(NodeKind kind) {
    return true;
  }

  @Override
  public ExpandResult expand(NodeRecord seed, ExpandContext ctx) {
    List<AssertionRecord> matching =
        Fixture.assertions().stream()
            .filter(a -> a.fromQid().equals(seed.qid()) || a.toQid().equals(seed.qid()))
            .toList();
    List<AssertionRecord> limited = matching.stream().limit(ctx.maxNewEdges()).toList();
    boolean truncated = limited.size() < matching.size();
    return new ExpandResult(limited, false, truncated);
  }
}
