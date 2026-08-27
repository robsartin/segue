package com.robsartin.segue.ratings;

import com.robsartin.segue.domain.LoggedAssertion;
import com.robsartin.segue.domain.NodeAssertion;
import com.robsartin.segue.port.AssertionLog;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * What the graph calls the entities a person has rated, read out of the log.
 *
 * <p><b>The log, not the graph.</b> ADR 41 made this argument for the exporter and it applies
 * harder here: {@code GraphStore} has no enumerate-all method, adding one would widen the port that
 * exists to make the engine choice reversible (ADR 18) for the benefit of a dev tool, and ADR 19
 * makes the log the source of truth with the graph as its projection. Reading it is correct as well
 * as cheap. It also means this tool never builds a projection at all - no Gremlin, no replay, no
 * {@code ingest} - which is what lets {@code ratings} carry the tightest fence of the three
 * dev-side tools.
 *
 * <p><b>Last claim wins</b>, matching {@code GraphStore.upsertNode} and the boot replay, so a label
 * here is the label {@code get_entity} would return. The kind is deliberately not re-derived: ADR
 * 42's {@code KindMapper.rederive} matters to a picture that colours by kind, and this is a list of
 * names.
 *
 * <p><b>Not a class name containing "Affinity", and that is deliberate.</b> This reads the
 * world-fact layer, which {@code affinityNeverTouchesTheWorldFactLayer} would forbid to any type
 * whose simple name said otherwise. See {@link AffinityRow}.
 */
final class Labels {

  private Labels() {}

  /**
   * The label the log last claimed for each of {@code qids}, omitting any it has never seen.
   *
   * <p>Filtered to the qids asked for rather than returning the whole map: a real log holds tens of
   * thousands of node claims and a person has rated a few dozen things. Data minimisation (ADR 16)
   * is the reason to prefer it, and the memory is a bonus.
   */
  static Map<String, String> forQids(AssertionLog log, Set<String> qids) {
    Map<String, String> labels = new HashMap<>();
    if (qids.isEmpty()) {
      return labels;
    }
    for (LoggedAssertion assertion : log.readAll()) {
      if (assertion instanceof NodeAssertion claim && qids.contains(claim.qid())) {
        labels.put(claim.qid(), claim.label());
      }
    }
    return labels;
  }
}
