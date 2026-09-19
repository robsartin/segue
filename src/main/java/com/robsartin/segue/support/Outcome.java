package com.robsartin.segue.support;

/** What a tool concluded about one name. */
public enum Outcome {
  /** Independent signals agreed. Goes in the mapping file. */
  ACCEPTED,
  /** Something found, nothing convincing. Goes in the review file with the reason. */
  REVIEW,
  /** Wikidata returned no candidate at all under any spelling tried. */
  UNRESOLVED,
  /**
   * A row the owner minted under ADR 59, written by the claim tool and never by the seed tool.
   *
   * <p>{@code ResolutionFiles.alreadyResolved} treats it as resolved, as it treats every row, so
   * a second batch mint over the same review file mints nothing twice (#342).
   */
  MINTED
}
