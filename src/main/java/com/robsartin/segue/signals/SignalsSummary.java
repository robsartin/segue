package com.robsartin.segue.signals;

import java.util.List;

/**
 * The coverage block (#350), in the shape {@code graphCensus}'s is: counts and a basename, never an
 * entity and never a path.
 *
 * @param rows rows read from the export
 * @param resolved rows stored under a qid the seed tool accepted
 * @param minted rows stored under a local entity the owner minted (ADR 59)
 * @param inReview rows whose name sits in the review file unresolved, and are not stored
 * @param unanswered rows whose name neither file mentions, so {@code resolveNames} has not been run
 *     over them yet
 * @param entities distinct entities written, which is less than {@code resolved + minted} where two
 *     spellings resolved to one
 */
record SignalsSummary(
    int rows, int resolved, int minted, int inReview, int unanswered, int entities) {

  List<String> lines(String source) {
    return List.of(
        rows + " row(s) read from " + source,
        resolved + " resolved",
        minted + " minted as local entities",
        inReview + " left in the review file",
        unanswered + " not answered by either file — run resolveNames over them first",
        entities + " entity signal(s) written");
  }
}
