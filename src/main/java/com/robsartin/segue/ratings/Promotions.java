package com.robsartin.segue.ratings;

import com.robsartin.segue.domain.Equivalences;
import com.robsartin.segue.domain.KnownList;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Who the owner has promoted and Setlist Scout does not track yet (#285): everything rated at or
 * above {@code KnownList.PROMOTION_RATING} that the {@code --known} file does not already name.
 *
 * <p><b>Through {@link KnownList#promoted}, not through a predicate of its own.</b> That method is
 * the authority on what a promotion is (ADR 48) and it is what {@code RecommendRun.run} and {@code
 * RateCli.known} compose their known-lists with. A second statement of the rule here — {@code
 * rating >= PROMOTION_RATING && !onFile} — would be a copy that agrees with those two only until
 * somebody adds a clause to one of them, which is the divergence ADR 48 put the composition in
 * {@code domain} to prevent and which this project has already paid for once (issue #109, quoted in
 * {@code KnownList.revisitable}). So this takes {@code promoted}'s answer and removes the file's
 * own entities from it, which is exactly that method's documented second half.
 *
 * <p><b>By set difference, not by index.</b> {@code promoted} appends the promotions after the
 * file's list, so the tail from {@code fromFile.size()} is the same answer today. It would stop
 * being the same answer the day {@code promoted} de-duplicated its first half, and nothing would
 * say so. Asking which of its entries the file does not name is the question the method's name
 * answers.
 *
 * <p><b>Resolved through the merges first</b>, as {@code RecommendCli} and {@code EvaluateCli} both
 * do before they compose a known-list. After a merge two affinity rows name one thing (#92) and
 * {@code promoted} would promote both, so the owner's one opinion would become two names in the
 * upload — and the local id, the one he retired, would be one of them.
 *
 * <p>Not a class name containing "Affinity", deliberately: it holds an {@link Equivalences}, and
 * {@code ArchitectureTest.affinityNeverTouchesTheWorldFactLayer} matches the taste layer by simple
 * name. See {@link Labels}, which is named for the same reason.
 */
final class Promotions {

  private Promotions() {}

  /**
   * The promoted entities the file does not name, in {@code KnownList.promoted}'s own qid order.
   *
   * @param stored the affinity table's score column as it stands, before any equivalence is applied
   *     — resolved here, so this tool's idea of who is promoted is the recommender's
   * @param merges what the owner has merged, folded out of the log
   * @param fromFile the {@code --known} file's qids, as {@code QidList} reads them
   */
  static List<String> offTheKnownList(
      Map<String, Integer> stored, Equivalences merges, List<String> fromFile) {
    Objects.requireNonNull(stored, "stored");
    Objects.requireNonNull(merges, "merges");
    Objects.requireNonNull(fromFile, "fromFile");

    Set<String> onFile = new LinkedHashSet<>(fromFile);
    List<String> promotions = new ArrayList<>();
    for (String known : KnownList.promoted(fromFile, merges.resolve(stored))) {
      if (!onFile.contains(known)) {
        promotions.add(known);
      }
    }
    return List.copyOf(promotions);
  }
}
