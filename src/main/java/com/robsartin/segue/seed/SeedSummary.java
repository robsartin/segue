package com.robsartin.segue.seed;

import java.util.List;

/**
 * What the run did, in numbers.
 *
 * <p>Reported as counts rather than as a headline percentage on purpose. The number that matters is
 * how many names a person still has to look at, and a threshold tuned until that number looks small
 * has not resolved anything — it has moved wrong answers into the file nobody reads.
 *
 * @param rows lines of the input list
 * @param groups distinct acts once spellings were folded together
 * @param skipped groups a previous run had already answered
 * @param accepted groups where three independent signals agreed
 * @param review groups with a candidate but no agreement
 * @param unresolved groups Wikidata returned nothing usable for
 */
public record SeedSummary(
    int rows, int groups, int skipped, int accepted, int review, int unresolved) {

  /** One line per fact, for a log a person reads once at the end of a run. */
  public List<String> lines() {
    int decided = accepted + review + unresolved;
    return List.of(
        "input rows: " + rows + ", distinct acts after folding: " + groups,
        "already answered by an earlier run: " + skipped,
        "resolved this run: " + decided,
        "  auto-accepted: " + accepted + percentage(accepted, decided),
        "  needs review:  " + review + percentage(review, decided),
        "  not found:     " + unresolved + percentage(unresolved, decided));
  }

  private static String percentage(int part, int whole) {
    return whole == 0 ? "" : " (" + Math.round(part * 100.0 / whole) + "%)";
  }
}
