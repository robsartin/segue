package com.robsartin.segue.domain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Which local ids the owner has said are really something else, and what the taste layer does about
 * it (#92).
 *
 * <p><b>The sibling of {@link Retractions}, and it exists for the same reason.</b> A merge is one
 * claim in the log with consequences in more than one reader, and two readers that each carried
 * their own idea of what a merge reaches would be free to disagree. {@code recommend} and {@code
 * rate} both ask this type the same two questions, so the known-list and the deck cannot answer
 * them differently.
 *
 * <p><b>The graph half of a merge is not this.</b> {@code IngestService.carry} copies the edges
 * onto the canonical id and leaves the local node exactly where it was, so a route or a log entry
 * recorded last month still resolves — that is what "a merged local id stays resolvable" means.
 * What is left over is the taste layer, which is keyed by qid and is rebuilt by nothing: after a
 * merge there are <b>two</b> affinity rows naming one thing, and both were live.
 *
 * <p><b>Measured before this was written</b>, on an invented graph where one minted entity reaches
 * one candidate through one intermediate. The rating was 5 on the local id, and the merge carried
 * it to the canonical id as {@code IdentityMerge.carryingRatings} does:
 *
 * <pre>
 *   BEFORE merge   candidate 0.2236, 1 shared intermediate
 *   AFTER  merge   candidate 0.4332, 2 shared intermediates
 * </pre>
 *
 * <p>{@code KnownList.promoted} promotes both rows, the graph holds both nodes carrying the same
 * edges, and the owner's one opinion is counted twice. The second face of the same defect is that
 * where only the canonical id is rated, the local entity <em>becomes a candidate</em> — in the same
 * fixture it ranked first, above the entity the run existed to find.
 *
 * <p><b>Nothing is deleted, here or anywhere.</b> ADR 19 makes the log append-only and {@code
 * AffinityStore} has no delete (ADR 39): the local row is what the owner actually said at the time
 * and it stays. This type resolves the two rows into one <em>view</em> at read time, which is the
 * same shape {@link Retractions} uses to drop a row from a fold without touching the log.
 *
 * <p><b>There are no chains to follow.</b> {@code SameAs.declared} requires the local side to carry
 * {@link LocalEntity}'s two leading zeros and the canonical side to be an id Wikidata could
 * allocate, and those two shapes are disjoint — so a canonical id can never itself be the local
 * side of another merge, and one hop is the whole of the resolution. That is a property of the
 * record's validation rather than an assumption made here; if it were false, {@link #resolve} would
 * leave a rating stranded on an intermediate id.
 *
 * @param canonicalByLocal each merged local id, and the id it turned out to be. Last claim wins
 *     when a local id was merged twice, by position in the log — the same "what had we already been
 *     told" reading {@link Retractions} takes, and the only one that lets a wrong merge be
 *     corrected by a later one
 */
public record Equivalences(Map<String, String> canonicalByLocal) {

  /** A projection with no merges in it, named at the call site rather than defaulted. */
  public static final Equivalences NONE = new Equivalences(Map.of());

  public Equivalences {
    canonicalByLocal = Map.copyOf(Objects.requireNonNull(canonicalByLocal, "canonicalByLocal"));
  }

  /**
   * Read the merges out of a log, in the order {@code AssertionLog.readAll} returns it.
   *
   * <p>A merge a retraction reaches is not one: {@link Retractions#survives} is asked the same
   * question here that both graph folds ask it, so an equivalence the graph refused to carry cannot
   * still be resolving ratings.
   */
  public static Equivalences in(List<LoggedAssertion> log) {
    Objects.requireNonNull(log, "log");
    Retractions retractions = Retractions.in(log);
    Map<String, String> byLocal = new LinkedHashMap<>();
    for (int i = 0; i < log.size(); i++) {
      if (log.get(i) instanceof SameAs merge && retractions.survives(i, merge)) {
        byLocal.put(merge.localQid(), merge.canonicalQid());
      }
    }
    return new Equivalences(byLocal);
  }

  /**
   * The local ids that have been merged — everything that must stop being offered.
   *
   * <p>The local side only. The canonical id is a real entity the owner may well want recommended
   * to him if he has not rated it; what he cannot be offered is the id he himself retired.
   */
  public Set<String> merged() {
    return canonicalByLocal.keySet();
  }

  /**
   * The ratings as they read through the equivalences: one row per thing, not one per id.
   *
   * <p>Each merged local id leaves the map, and its rating lands on the canonical id <b>only when
   * the canonical id has none</b>. That order is not a tiebreak invented here — {@code
   * IdentityMerge.carryingRatings} has already applied ADR 39's "the later rating wins" using the
   * {@code updatedAt} column, and written its answer to the store. This method is handed {@code
   * AffinityStore.readRatings}, which carries no timestamps at all, so re-deciding the question
   * here could only decide it worse. Where the store has an answer for the canonical id, that
   * answer is the one the owner's own most recent word produced.
   *
   * <p>Filling in an <em>absent</em> canonical rating is a repair rather than a duplicate of that
   * rule: {@code recommend} and {@code rate} replay with {@code IdentityMerge.NONE}, so a merge
   * logged since the last boot has had nothing carry its rating yet, and without this the owner's
   * rating would simply vanish from the run.
   *
   * @param ratings qid to a rating from 1 to 5 — {@code AffinityStore.readRatings}, note-free by
   *     construction, which is what keeps this method in {@code domain}
   */
  public Map<String, Integer> resolve(Map<String, Integer> ratings) {
    Objects.requireNonNull(ratings, "ratings");
    if (canonicalByLocal.isEmpty()) {
      return Map.copyOf(ratings);
    }
    Map<String, Integer> resolved = new LinkedHashMap<>(ratings);
    for (Map.Entry<String, String> merge : canonicalByLocal.entrySet()) {
      Integer local = resolved.remove(merge.getKey());
      if (local != null) {
        resolved.putIfAbsent(merge.getValue(), local);
      }
    }
    return Map.copyOf(resolved);
  }
}
