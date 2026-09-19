package com.robsartin.segue.expand;

import java.util.Objects;

/**
 * The known-list population a run covered, and what the rule excluded (issue #313).
 *
 * @param file the file's <b>basename</b>, never its path — {@code support.KnownListInput} is the
 *     one home of that rule, and the block is meant to be pasted
 * @param excluded how many of the file's entities, after the merge fold, some row in the log cites
 *     as an expansion's seed, or a minted local id {@code LocalEntity#isLocal} answers true for —
 *     no source will ever answer for one, so it is not a shortfall a later run could close (#344)
 * @param adding whether {@code --add} was given, so the clause can say that an id the file names
 *     and the graph lacks was added before it was expanded. A boolean and not a count: this value
 *     is composed before the first entity is visited and is rendered into the dry-run block as well
 *     as the real one, so a count in it would be a lie on the dry run — and the count is already a
 *     row ({@code added}, or {@code to add} on a dry run). The clause carries what no row states,
 *     which is the rule {@code excluded} above follows too
 */
public record KnownNeverExpanded(String file, int excluded, boolean adding) implements Population {

  public KnownNeverExpanded {
    Objects.requireNonNull(file, "file");
    if (excluded < 0) {
      throw new IllegalArgumentException("excluded cannot be negative, got " + excluded);
    }
  }
}
