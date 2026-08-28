package com.robsartin.segue.rate;

import com.robsartin.segue.domain.NodeRecord;
import com.robsartin.segue.domain.PathResult;
import com.robsartin.segue.recommend.Explained;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.Function;
import java.util.function.ToIntFunction;

/**
 * What to ask about, and in what order. Pure, so the one decision in this tool can be tested
 * without a database, a server or a browser.
 *
 * <p><b>Degree descending, because a rating is worth what it moves.</b> A known entity's rating
 * reaches candidate scores through the intermediates it touches, so rating the busiest entities
 * first buys the most movement per keystroke — the owner should be able to feel the recommender
 * change inside one session rather than after eight hundred cards. The card shows that same degree,
 * so a card near the top says why it is near the top.
 *
 * <p><b>Already-rated entities are excluded rather than re-asked, and that is the whole of the
 * resume mechanism.</b> The deck is "everything unrated", recomputed at startup from {@code
 * AffinityStore.readRatings()}. There is no position file to persist, to corrupt, or to leave
 * personal data lying in.
 */
public final class Deck {

  /**
   * One candidate every this many cards.
   *
   * <p>The mixed stream is what the owner asked for: rating doubles as discovery. Five keeps the
   * deck mostly on the entities whose ratings actually move a score today — a candidate's rating is
   * recorded but inert, because {@code Recommendations.regardFor} reads only known-list qids.
   */
  public static final int CANDIDATE_EVERY = 5;

  private Deck() {}

  /**
   * Build the deck.
   *
   * <p><b>Revise mode deals no candidates, and that is not an omission.</b> A candidate is by
   * definition something the owner does not have and has not rated, so there is nothing there to
   * reconsider; mixing discovery into a revision pass would also change what the pass measures.
   */
  public static List<Card> deal(
      List<String> knownQids,
      ToIntFunction<String> degreeByQid,
      Function<String, Optional<NodeRecord>> nodeByQid,
      Map<String, Integer> ratings,
      List<Explained> candidates,
      OptionalInt reviseRating) {
    Objects.requireNonNull(knownQids, "knownQids");
    Objects.requireNonNull(degreeByQid, "degreeByQid");
    Objects.requireNonNull(nodeByQid, "nodeByQid");
    Objects.requireNonNull(ratings, "ratings");
    Objects.requireNonNull(candidates, "candidates");
    Objects.requireNonNull(reviseRating, "reviseRating");

    if (reviseRating.isPresent()) {
      return dealRevision(knownQids, degreeByQid, nodeByQid, ratings, reviseRating.getAsInt());
    }

    Set<String> alreadyRated = ratings.keySet();

    List<Card> known = new ArrayList<>();
    for (String qid : knownQids) {
      if (alreadyRated.contains(qid)) {
        continue;
      }
      // An entity on the list that the graph does not hold has nothing to show and nothing to
      // explain. Skipping is right; dealing a blank card would ask the owner to rate a name.
      nodeByQid
          .apply(qid)
          .ifPresent(node -> known.add(Card.known(node, degreeByQid.applyAsInt(qid))));
    }
    known.sort(
        Comparator.comparingInt((Card c) -> c.degree().orElse(0))
            .reversed()
            .thenComparing(Card::qid));

    List<Card> fresh = new ArrayList<>();
    for (Explained explained : candidates) {
      String qid = explained.candidate().entity().qid();
      if (alreadyRated.contains(qid)) {
        continue;
      }
      fresh.add(Card.candidate(explained.candidate().entity(), routeLines(explained)));
    }

    return interleave(known, fresh);
  }

  /**
   * Select known qids currently rated exactly {@code target}, dealt for reconsideration. No
   * candidates: see the class-level note on {@link #deal}.
   */
  private static List<Card> dealRevision(
      List<String> knownQids,
      ToIntFunction<String> degreeByQid,
      Function<String, Optional<NodeRecord>> nodeByQid,
      Map<String, Integer> ratings,
      int target) {
    List<Card> revision = new ArrayList<>();
    for (String qid : knownQids) {
      Integer rating = ratings.get(qid);
      if (rating == null || rating != target) {
        continue;
      }
      nodeByQid
          .apply(qid)
          .ifPresent(node -> revision.add(Card.rated(node, degreeByQid.applyAsInt(qid), rating)));
    }
    revision.sort(
        Comparator.comparingInt((Card c) -> c.degree().orElse(0))
            .reversed()
            .thenComparing(Card::qid));
    return List.copyOf(revision);
  }

  private static List<String> routeLines(Explained explained) {
    // PathResult.render() — the same rendering RecommendationReport uses, citations included
    // (see its javadoc). The record's default toString() is a Java object dump, not something a
    // reader chose "why am I being shown this" from.
    return explained.routes().stream().map(PathResult::render).toList();
  }

  private static List<Card> interleave(List<Card> known, List<Card> candidates) {
    List<Card> dealt = new ArrayList<>(known.size() + candidates.size());
    int nextCandidate = 0;
    int knownDealt = 0;
    for (Card card : known) {
      dealt.add(card);
      knownDealt++;
      // CANDIDATE_EVERY counts DEALT cards, not known cards: a candidate is itself one of the
      // five, so it slots in after every (CANDIDATE_EVERY - 1) known cards, making the candidate
      // the fifth card in the group rather than the sixth.
      if (knownDealt % (CANDIDATE_EVERY - 1) == 0 && nextCandidate < candidates.size()) {
        dealt.add(candidates.get(nextCandidate++));
      }
    }
    // Whatever is left over goes on the end rather than being dropped: a short known list must not
    // silently discard candidates the sweep paid to find.
    dealt.addAll(candidates.subList(nextCandidate, candidates.size()));
    return List.copyOf(dealt);
  }
}
