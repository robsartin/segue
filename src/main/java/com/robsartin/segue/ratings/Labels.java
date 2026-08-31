package com.robsartin.segue.ratings;

import com.robsartin.segue.domain.LocalEntity;
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
      // Two claim types name an entity, not one (#92). A source states a NodeAssertion; the owner
      // mints a LocalEntity, which is a first-person claim and deliberately not a NodeAssertion -
      // it carries no Provenance, because the owner minting it IS the source. Both put a node in
      // the graph, so both have to answer here, and the failure of matching only the first was
      // silent rather than loud: a rated minted entity listed as AffinityRow.NO_LABEL - "(not in
      // the graph)" - while being in the graph. That string exists for a rating that OUTLIVED its
      // node, which is the opposite situation and the reason nothing noticed.
      String qid = null;
      String label = null;
      if (assertion instanceof NodeAssertion claim) {
        qid = claim.qid();
        label = claim.label();
      } else if (assertion instanceof LocalEntity claim) {
        qid = claim.qid();
        label = claim.label();
      }
      // Last claim wins, matching upsertNode and the boot replay, across both kinds together: a
      // merged entity a source later names is renamed here too, in the order the log holds.
      if (qid != null && qids.contains(qid)) {
        labels.put(qid, label);
      }
    }
    return labels;
  }
}
