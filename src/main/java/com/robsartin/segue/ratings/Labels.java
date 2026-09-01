package com.robsartin.segue.ratings;

import com.robsartin.segue.domain.LocalEntity;
import com.robsartin.segue.domain.LoggedAssertion;
import com.robsartin.segue.domain.NodeAssertion;
import com.robsartin.segue.domain.Retractions;
import com.robsartin.segue.domain.SameAs;
import com.robsartin.segue.port.AssertionLog;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
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
 * here is the label {@code get_entity} would return - retraction included, on the same {@link
 * Retractions} rule {@code GraphProjector.project} applies before replaying a row (ADR 44); see
 * where {@code forQids} calls it, below. The kind is deliberately not re-derived: ADR 42's {@code
 * KindMapper.rederive} matters to a picture that colours by kind, and this is a list of names.
 *
 * <p><b>A merge is folded here too, and that invariant is why</b> (#92). {@code
 * IngestService.carry} puts a node under the canonical id carrying the label of the entity merged
 * into it, and it writes that to the <em>graph</em> while this reads the <em>log</em> - so for as
 * long as this fold ignored merges, the sentence above was false in the one case the third layer
 * creates: a carried canonical row was listed as {@link AffinityRow#NO_LABEL} - "(not in the
 * graph)" - while the node was in the graph. That string is for a rating that outlived its node,
 * which is the opposite situation, and it is what made the wrong output read as intended. The rule
 * is carry's, not a second one: the canonical id takes the merged entity's label only where nothing
 * has claimed it, and a claim after the merge still overwrites.
 *
 * <p><b>Both rows stay, and that is not the same defect.</b> A merge carries the rating and leaves
 * the local row where it is, deliberately - {@code IdentityMerge.carryingRatings} moves the score
 * and never the note, so the owner's own words survive only on the local row, and this is the one
 * tool that reads a note (ADR 43). Collapsing the two rows into one here would hide it. What was
 * wrong was one of them denying it was in the graph, not that there were two.
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
    List<LoggedAssertion> logged = log.readAll();
    Retractions retractions = Retractions.in(logged);
    // The qids asked for, plus every local id merged into one of them. A canonical id can be rated
    // while the entity merged into it never was - the owner mints, merges, then rates the real
    // item - and the only name that node has ever had is the one the merge carried onto it. One
    // hop is the whole of it, for Equivalences' reason: allocatable and unallocatable are
    // complementary, so a canonical id can never itself be the local side of another merge.
    //
    // Not gated by retractions.survives here, deliberately: adding localQid to wanted only has an
    // effect if some SameAs row's carry step later reads labels.get(localQid), and that read only
    // happens for the SAME row, inside the main loop below, behind the identical
    // retractions.survives(i, merge) call. A localQid this loop adds for a row that does not
    // survive is therefore never read - the main loop's own guard already excludes that row from
    // the carry step - and it is stripped from the result regardless by retainAll(qids) below if
    // it was never one of the qids asked for. Checked, not assumed: constructed the case a second,
    // surviving merge of the same localQid to a different requested canonical would need to expose
    // a difference, and it collapses the same way, because that second merge's own row already adds
    // localQid on its own account.
    Set<String> wanted = new HashSet<>(qids);
    for (int i = 0; i < logged.size(); i++) {
      if (logged.get(i) instanceof SameAs merge && qids.contains(merge.canonicalQid())) {
        wanted.add(merge.localQid());
      }
    }
    for (int i = 0; i < logged.size(); i++) {
      LoggedAssertion assertion = logged.get(i);
      // Retractions are honoured here on GraphProjector's own precedent (ADR 44): one rule decides
      // what the log means, in every reading of it. A retracted claim is skipped before it can name
      // or rename anything, the same way GraphProjector.project skips it before IngestService.apply
      // ever sees it.
      if (!retractions.survives(i, assertion)) {
        continue;
      }
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
      if (qid != null && wanted.contains(qid)) {
        labels.put(qid, label);
      }
      // A merge names an entity too, at one remove: IngestService.carry puts a node under the
      // canonical id carrying the label of the entity that was merged into it, so this fold has to
      // do the same or the invariant above is false. It was: a carried canonical row listed as
      // "(not in the graph)" while the node was in the graph. The retraction check above already
      // covers this row - a SameAs naming a retracted entity on either side never reaches here.
      if (assertion instanceof SameAs merge) {
        String local = labels.get(merge.localQid());
        // Only where nothing has claimed the canonical entity, which is carry()'s own guard: a
        // source that HAS named it wins, because overwriting its label with the owner's working
        // title would be the merge editing the world rather than recording an identity. A source
        // that names it LATER still wins, here by the put above and in the graph by upsertNode.
        if (local != null && !labels.containsKey(merge.canonicalQid())) {
          labels.put(merge.canonicalQid(), local);
        }
      }
    }
    // The merged local ids that were only ever looked up to answer for a canonical id go no
    // further: this method answers about the qids it was asked about (ADR 16).
    labels.keySet().retainAll(qids);
    return labels;
  }
}
