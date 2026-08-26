package com.robsartin.segue.seed;

import java.util.Objects;

/**
 * One line of the input list.
 *
 * <p>{@code status} is carried through untouched and never filtered on. It belongs to the tool the
 * list came from, where {@code REJECTED} means "does not tour" — which is a fact about scheduling,
 * not a judgement about interest, and the rejected rows are disproportionately the authors and
 * thinkers whose relations this graph is short of.
 */
public record SeedRow(String name, String kind, String status) {

  public SeedRow {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(status, "status");
  }
}
