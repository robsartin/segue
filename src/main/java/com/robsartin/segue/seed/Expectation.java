package com.robsartin.segue.seed;

import com.robsartin.segue.domain.NodeKind;
import java.util.Collection;
import java.util.Objects;
import java.util.Set;

/**
 * What the input list's {@code kind} column says a candidate must look like.
 *
 * <p>Two independent signals, because neither is sufficient alone. {@link NodeKind} comes from
 * {@code P31} and separates a person from a band from a film; it cannot separate a musician from a
 * minister, because both are {@code Q5}. Occupation comes from {@code P106} and does exactly that.
 *
 * <p>An empty {@code occupations} set means "this kind constrains no occupation" — a band has no
 * {@code P106} at all, and neither does a television series.
 */
public record Expectation(Set<NodeKind> kinds, Set<String> occupations) {

  public Expectation {
    kinds = Set.copyOf(Objects.requireNonNull(kinds, "kinds"));
    occupations = Set.copyOf(Objects.requireNonNull(occupations, "occupations"));
  }

  public boolean acceptsKind(NodeKind kind) {
    return kinds.contains(kind);
  }

  /** Whether occupation is a real check for this kind, or vacuous. */
  public boolean checksOccupation() {
    return !occupations.isEmpty();
  }

  /**
   * Whether these {@code P106} values are compatible.
   *
   * <p>An entity with no occupation at all does NOT pass a kind that checks one. That is the whole
   * point of the check: an exact name match on a human with no stated occupation is precisely the
   * case where the name is the only evidence, and the name is what is in doubt.
   */
  public boolean acceptsOccupation(Collection<String> p106) {
    Objects.requireNonNull(p106, "p106");
    return !checksOccupation() || p106.stream().anyMatch(occupations::contains);
  }
}
