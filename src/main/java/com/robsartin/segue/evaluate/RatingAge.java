package com.robsartin.segue.evaluate;

import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Where the line between an old promotion and a new one falls, and which side each rated entity is
 * on (issue #276, ADR 65 as amended).
 *
 * <p><b>A value built once per run, not a question asked per fold.</b> Fold {@code k} and fold
 * {@code k + 1} disagree about which entities are hidden and agree exactly about which are old, so
 * the age is not a property of the split: {@link HeldOut#every} keeps the signature it has, and
 * this is derived once, before the first sweep, from the ratings the run is about to read.
 *
 * <p><b>On or after the instant is new; before it is old.</b> An entity whose rating was last
 * written at the instant itself is new, and a test pins that boundary rather than leaving it to be
 * read off an implementation.
 *
 * <p><b>A rated entity with no timestamp is refused rather than counted as old.</b> The two bulk
 * reads are two selects over one table through one connection, so a disagreement between their
 * keysets means the resolution or the store is wrong — and an entity silently reported in the old
 * half would sit in a cell indistinguishable from a real one. The refusal names no qid and no
 * count: how much the owner has rated is itself a fact about him (ADR 33), which is why {@code
 * SqliteAffinityStore.readAll} already keeps both out of its own exception.
 *
 * <p><b>The timestamp is the last write.</b> ADR 39 keeps one row per entity, so a promotion rated
 * long ago and re-rated after the instant lands in the new half. That is the limit the report's
 * header states, and it is why the halves are an observation rather than a count of new promotions.
 *
 * @param since the boundary the operator gave, parsed
 * @param newer every rated qid whose rating was last written at or after {@link #since}
 */
public record RatingAge(Instant since, Set<String> newer) {

  public RatingAge {
    Objects.requireNonNull(since, "since");
    newer = Set.copyOf(Objects.requireNonNull(newer, "newer"));
  }

  /**
   * Derive the new half, once.
   *
   * @param since the instant the halves are drawn at
   * @param updatedAt qid to when that rating was last written, already resolved through the merges
   * @param rated every qid the run's ratings map names, resolved the same way
   * @throws IllegalStateException if a rated entity has no timestamp
   */
  public static RatingAge of(Instant since, Map<String, Instant> updatedAt, Set<String> rated) {
    Objects.requireNonNull(since, "since");
    Objects.requireNonNull(updatedAt, "updatedAt");
    Objects.requireNonNull(rated, "rated");

    // Plain HashSet, not LinkedHashSet: the compact constructor immediately discards insertion
    // order (Set.copyOf), and isNew is a pure membership lookup no caller reads in order.
    Set<String> newer = new HashSet<>();
    for (String qid : rated) {
      Instant when = updatedAt.get(qid);
      if (when == null) {
        throw new IllegalStateException(
            "a rated entity has no rating timestamp: the two bulk reads of the affinity table"
                + " disagree about which entities are rated, and the age split cannot say which"
                + " half this one belongs in");
      }
      if (!when.isBefore(since)) {
        newer.add(qid);
      }
    }
    return new RatingAge(since, newer);
  }

  /** Whether this entity's rating was last written at or after {@link #since}. */
  public boolean isNew(String qid) {
    return newer.contains(qid);
  }
}
