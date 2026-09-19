package com.robsartin.segue.support;

/** What a tool concluded about one name. */
public enum Outcome {
  /** Independent signals agreed. Goes in the mapping file. */
  ACCEPTED,
  /** Something found, nothing convincing. Goes in the review file with the reason. */
  REVIEW,
  /** Wikidata returned no candidate at all under any spelling tried. */
  UNRESOLVED
}
