package com.robsartin.segue.census;

import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.domain.NodeRecord;
import com.robsartin.segue.export.LogProjection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * What the fold holds, by kind.
 *
 * <p><b>All six kinds are emitted, zeros included</b>, so a kind that has gone to zero is visible
 * as a zero rather than as a missing row somebody has to notice.
 *
 * <p>The map is an {@code EnumMap} kept in declaration order rather than a {@code Map.copyOf}: that
 * factory's iteration order is unspecified and salted per JVM, so two runs over one unchanged log
 * would print two orders and a diff between them would be noise. {@code LogProjection} makes the
 * same choice for the same reason (issue #207), and ADR 43's byte-identical contract is what both
 * serve.
 */
public record NodeCensus(Map<NodeKind, Integer> byKind, int total) {

  public NodeCensus {
    Objects.requireNonNull(byKind, "byKind");
    // new EnumMap<>(map) refuses an empty map it cannot infer the key type from; the class
    // constructor plus putAll takes one, and no caller has to know that.
    Map<NodeKind, Integer> copy = new EnumMap<>(NodeKind.class);
    copy.putAll(byKind);
    byKind = Collections.unmodifiableMap(copy);
  }

  public static NodeCensus of(LogProjection projection) {
    Objects.requireNonNull(projection, "projection");
    Map<NodeKind, Integer> byKind = new EnumMap<>(NodeKind.class);
    for (NodeKind kind : NodeKind.values()) {
      byKind.put(kind, 0);
    }
    for (NodeRecord node : projection.nodes().values()) {
      byKind.merge(node.kind(), 1, Integer::sum);
    }
    return new NodeCensus(byKind, projection.nodes().size());
  }
}
