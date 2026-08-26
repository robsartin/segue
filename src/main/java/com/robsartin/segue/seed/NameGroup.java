package com.robsartin.segue.seed;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Every line of the list that names the same act, and every spelling worth asking about.
 *
 * <p>Folding first is not a tidiness measure. A list assembled by hand over years carries the same
 * act under several spellings — with and without a leading article, with a non-breaking hyphen or
 * an ordinary one, with an accent or without — and resolving each spelling separately spends a
 * lookup per duplicate and puts the same act in the review pile more than once.
 *
 * @param key the folded name every row in this group shares
 * @param rows the input rows, in the order the list gave them
 */
public record NameGroup(String key, List<SeedRow> rows) {

  public NameGroup {
    Objects.requireNonNull(key, "key");
    rows = List.copyOf(Objects.requireNonNull(rows, "rows"));
    if (rows.isEmpty()) {
      throw new IllegalArgumentException("a group with no rows is not a group");
    }
  }

  /** The rows of a list, folded into groups, in first-appearance order. */
  public static List<NameGroup> of(Collection<SeedRow> rows) {
    Objects.requireNonNull(rows, "rows");
    Map<String, List<SeedRow>> byKey = new LinkedHashMap<>();
    for (SeedRow row : rows) {
      byKey.computeIfAbsent(Names.fold(row.name()), key -> new ArrayList<>()).add(row);
    }
    return byKey.entrySet().stream()
        .map(entry -> new NameGroup(entry.getKey(), entry.getValue()))
        .toList();
  }

  /** The name to report this group under. */
  public String primaryName() {
    return rows.get(0).name();
  }

  /** The roles the list gives this act, deduplicated, in first-appearance order. */
  public List<String> kinds() {
    return List.copyOf(new LinkedHashSet<>(rows.stream().map(SeedRow::kind).toList()));
  }

  /**
   * The spellings to ask Wikidata about, in the order to ask them.
   *
   * <p>Every spelling that is actually on the list comes first, because those are the user's own
   * words. Only then the invented fallbacks — a stripped disambiguator suffix, a stripped honorific
   * — which are guesses about what the user meant and are tried only when the literal strings did
   * not resolve confidently.
   */
  public List<String> spellings() {
    List<String> literal = new ArrayList<>();
    List<String> fallbacks = new ArrayList<>();
    for (SeedRow row : rows) {
      List<String> spellings = Names.spellings(row.name());
      literal.add(spellings.get(0));
      fallbacks.addAll(spellings.subList(1, spellings.size()));
    }
    List<String> ordered = new ArrayList<>(new LinkedHashSet<>(literal));
    for (String fallback : new LinkedHashSet<>(fallbacks)) {
      if (!ordered.contains(fallback)) {
        ordered.add(fallback);
      }
    }
    return List.copyOf(ordered);
  }

  /** What this group's rows expect a candidate to be, across every role they give it. */
  public Expectation expectation() {
    return Expectations.forKinds(kinds());
  }
}
