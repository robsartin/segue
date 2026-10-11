package com.robsartin.segue.ratings;

import com.robsartin.segue.domain.AssertionRecord;
import com.robsartin.segue.domain.EdgeTypes;
import com.robsartin.segue.domain.LoggedAssertion;
import com.robsartin.segue.domain.NodeAssertion;
import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.domain.Retractions;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Which entities the log says are acts: a band, an ensemble, or someone who performed a work
 * (#354).
 *
 * <p>The names export feeds a concert-search upload, and a book or a film rated 4 or 5 is a
 * promotion like any other but not something that plays a venue. {@code NodeKind} has no MUSICIAN
 * and must not - a musician is a role expressed as an edge - so the question is answered the way
 * the model answers every role question: from the log's own claims. An act is an entity whose
 * latest surviving node claim is a {@link NodeKind#GROUP}, or one that is the performing end of a
 * {@code PERFORMED} edge (stored performer to work, so the {@code from} side).
 *
 * <p>Read from the log and honouring {@link Retractions}, for {@link Labels}' reason: this tool
 * never builds a projection. Merges are not folded; the caller asks about resolved ids and a
 * canonical id the log has no claim about is simply not an act, which errs toward leaving a name
 * out of the upload rather than putting a book in it.
 */
final class Acts {

  private Acts() {}

  /** Every qid in {@code logged} that is an act. */
  static Set<String> in(List<LoggedAssertion> logged) {
    Retractions retractions = Retractions.in(logged);
    Set<String> groups = new HashSet<>();
    Set<String> performers = new HashSet<>();
    for (int i = 0; i < logged.size(); i++) {
      LoggedAssertion assertion = logged.get(i);
      if (!retractions.survives(i, assertion)) {
        continue;
      }
      if (assertion instanceof NodeAssertion claim) {
        // Last claim wins, matching upsertNode: a later claim that says WORK takes it back.
        if (claim.kind() == NodeKind.GROUP) {
          groups.add(claim.qid());
        } else {
          groups.remove(claim.qid());
        }
      } else if (assertion instanceof AssertionRecord edge
          && EdgeTypes.PERFORMED.code().equals(edge.typeCode())) {
        performers.add(edge.fromQid());
      }
    }
    groups.addAll(performers);
    return groups;
  }
}
