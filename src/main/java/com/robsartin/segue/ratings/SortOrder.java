package com.robsartin.segue.ratings;

import java.util.Arrays;
import java.util.Comparator;
import java.util.stream.Collectors;

/**
 * The two orderings worth having, and the reason there are exactly two (ADR 43).
 *
 * <p>{@link #RATING} answers "what do I love"; {@link #RECENT} answers "what did I change my mind
 * about", which is the more interesting of the two and the reason {@code updated_at} exists at all
 * - ADR 39 keeps one row per entity and no history, so the timestamp is the only trace of taste
 * drift there is.
 *
 * <p>Both comparators end in {@code qid} so that two runs over an unchanged table produce
 * byte-identical files. A tool whose output shuffles between runs cannot be diffed, and diffing two
 * listings a month apart is the closest thing to a history this layer has.
 */
public enum SortOrder {

  /** Highest first, then most recently changed, then qid. */
  RATING(
      "rating",
      "rating, highest first",
      Comparator.comparingInt(AffinityRow::rating)
          .reversed()
          .thenComparing(Comparator.comparing(AffinityRow::updatedAt).reversed())
          .thenComparing(AffinityRow::qid)),

  /** Most recently changed first, then qid. */
  RECENT(
      "recent",
      "when it last changed, most recent first",
      Comparator.comparing(AffinityRow::updatedAt).reversed().thenComparing(AffinityRow::qid));

  private final String spelling;
  private final String description;
  private final Comparator<AffinityRow> comparator;

  SortOrder(String spelling, String description, Comparator<AffinityRow> comparator) {
    this.spelling = spelling;
    this.description = description;
    this.comparator = comparator;
  }

  /** The word the command line uses. */
  public String spelling() {
    return spelling;
  }

  /** The phrase the output file uses to say how it is ordered. */
  public String describe() {
    return description;
  }

  /** Total, and deterministic to the last tiebreak. */
  public Comparator<AffinityRow> comparator() {
    return comparator;
  }

  /** The accepted words, for a usage message. */
  public static String names() {
    return Arrays.stream(values()).map(SortOrder::spelling).collect(Collectors.joining("|"));
  }

  /** Parse one command-line word, refusing anything else by name. */
  public static SortOrder parse(String word) {
    return Arrays.stream(values())
        .filter(order -> order.spelling.equalsIgnoreCase(word))
        .findFirst()
        .orElseThrow(
            () -> new IllegalArgumentException("unknown sort " + word + " — expected " + names()));
  }
}
