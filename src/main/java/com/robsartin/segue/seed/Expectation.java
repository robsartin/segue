package com.robsartin.segue.seed;

import com.robsartin.segue.domain.NodeKind;
import java.util.Collection;
import java.util.Objects;
import java.util.Set;

/**
 * What the input list's {@code kind} column says a candidate must look like.
 *
 * <p>Three independent signals, because none of them is sufficient alone. {@link NodeKind} comes
 * from {@code P31} and separates a person from a band from a film; it cannot separate a musician
 * from a minister, because both are {@code Q5}. Occupation comes from {@code P106} and does exactly
 * that. The classes are {@code P31} again, unfolded — the kind a mapper folded it into is too
 * coarse for a title, because albums, films and episodes are all works.
 *
 * <p>An empty {@code occupations} set means "this kind constrains no occupation" — a band has no
 * {@code P106} at all, and neither does a television series. An empty {@code classes} set says the
 * same about the classes, and every kind but {@code book} has one.
 */
public record Expectation(Set<NodeKind> kinds, Set<String> occupations, Set<String> classes) {

  public Expectation {
    kinds = Set.copyOf(Objects.requireNonNull(kinds, "kinds"));
    occupations = Set.copyOf(Objects.requireNonNull(occupations, "occupations"));
    classes = Set.copyOf(Objects.requireNonNull(classes, "classes"));
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

  /** Whether the class is a real check for this kind, or vacuous. */
  public boolean checksClass() {
    return !classes.isEmpty();
  }

  /**
   * Whether these {@code P31} values are compatible.
   *
   * <p>The same shape as {@link #acceptsOccupation}, and the same refusal: a work that states no
   * class this kind names does not pass a kind that checks one. A title is ambiguous across
   * editions, translations and adaptations, and the stated class is the only thing in the fetched
   * facts that tells them apart.
   */
  public boolean acceptsClass(Collection<String> p31) {
    Objects.requireNonNull(p31, "p31");
    return !checksClass() || p31.stream().anyMatch(classes::contains);
  }
}
