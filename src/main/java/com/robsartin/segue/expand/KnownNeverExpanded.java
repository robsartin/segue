package com.robsartin.segue.expand;

import java.util.Objects;

/**
 * The known-list population a run covered, and what the rule excluded (issue #313).
 *
 * @param file the file's <b>basename</b>, never its path — {@code support.KnownListInput} is the
 *     one home of that rule, and the block is meant to be pasted
 * @param excluded how many of the file's entities, after the merge fold, some row in the log cites
 *     as an expansion's seed
 */
public record KnownNeverExpanded(String file, int excluded) implements Population {

  public KnownNeverExpanded {
    Objects.requireNonNull(file, "file");
    if (excluded < 0) {
      throw new IllegalArgumentException("excluded cannot be negative, got " + excluded);
    }
  }
}
