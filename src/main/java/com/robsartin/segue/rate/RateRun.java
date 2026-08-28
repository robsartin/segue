package com.robsartin.segue.rate;

import com.robsartin.segue.domain.Recommendation;
import com.robsartin.segue.domain.Recommendations;
import com.robsartin.segue.domain.Scorer;
import com.robsartin.segue.port.GraphStore;
import com.robsartin.segue.recommend.CandidateSweep;
import com.robsartin.segue.recommend.Explained;
import com.robsartin.segue.recommend.Routes;
import com.robsartin.segue.recommend.Sweep;
import com.robsartin.segue.wikidata.RecognitionInstitutions;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Replay, sweep, deal. The orchestration, and nothing that decides anything.
 *
 * <p><b>Candidates come from the recommender's own sweep, not a second implementation.</b> A card's
 * routes are then the routes {@code find_paths} would return for the same pair, which is the
 * property that makes "why is this here" answerable at all.
 *
 * <p>Notes go to a {@link Consumer} rather than to a logger of this class's own, as {@code
 * RatingsRun} does, so a test can assert on their order and content — and so this class has no
 * logger through which a rating could reach a log line (ADR 33).
 */
public final class RateRun {

  /** As many routes as fit on a card without turning it into a page to read. */
  private static final int ROUTES_PER_CARD = 3;

  /** The recommender's own floor: below this a normalised score divides by too little. */
  private static final int MIN_CANDIDATE_DEGREE = 12;

  private RateRun() {}

  public static List<Card> buildDeck(
      GraphStore graph,
      List<String> known,
      Set<String> alreadyRated,
      int candidateCount,
      Consumer<String> notes) {
    Objects.requireNonNull(graph, "graph");
    Objects.requireNonNull(known, "known");
    Objects.requireNonNull(alreadyRated, "alreadyRated");
    Objects.requireNonNull(notes, "notes");

    notes.accept(
        known.size() + " entity(ies) on your list, " + alreadyRated.size() + " already rated");

    List<Explained> candidates = new ArrayList<>();
    if (candidateCount > 0) {
      Sweep sweep =
          new CandidateSweep(graph, RecognitionInstitutions::isRecognitionInstitution)
              .over(known, Scorer.LIFT, MIN_CANDIDATE_DEGREE, Recommendations.EQUAL_REGARD);
      Routes routes = new Routes(graph, RecognitionInstitutions::isRecognitionInstitution);
      for (Recommendation candidate : Recommendations.rank(sweep.candidates(), candidateCount)) {
        candidates.add(new Explained(candidate, routes.bestFor(candidate, ROUTES_PER_CARD)));
      }
      notes.accept(candidates.size() + " candidate(s) mixed in");
    }

    List<Card> deck =
        Deck.deal(known, qid -> graph.edges(qid).size(), graph::node, alreadyRated, candidates);
    notes.accept(deck.size() + " card(s) to rate");
    return deck;
  }
}
