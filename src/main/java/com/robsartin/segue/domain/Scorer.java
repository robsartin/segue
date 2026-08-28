package com.robsartin.segue.domain;

import java.util.Arrays;
import java.util.List;
import java.util.function.DoubleUnaryOperator;
import java.util.stream.Collectors;

/**
 * How much a candidate's shared intermediates are worth — the spectrum from counting to lift, as a
 * dial rather than a constant (ADR 45).
 *
 * <p>Every point here computes the same sum with two knobs turned differently:
 *
 * <pre>
 *   score(candidate) = ( Σ over shared intermediates z of  weight(z) / discount(degree z) )
 *                      / normalisation(degree of the candidate)
 * </pre>
 *
 * <p><b>The two knobs answer different questions, and the second one is the finding.</b>
 * Discounting the INTERMEDIATE (Adamic-Adar, resource allocation) says a thing half the graph
 * touches is weak evidence. That is not enough on its own, because a candidate connected to
 * everything shares its intermediates with everything: measured on the real 123,752-node graph, raw
 * counting and Adamic-Adar both returned the most famous names in it. Dividing by the CANDIDATE's
 * own degree is what turns a popularity ranking into a surprise one — "connected to me more than
 * its fame predicts" — and it is what produced a list worth reading.
 *
 * <p><b>Why a dial and not simply {@link #LIFT}.</b> The right point differs by domain, and the
 * failure mode at each end is real rather than theoretical. {@link #RAW} rediscovers fame. {@link
 * #LIFT} rewards a thin entity whose whole presence in the graph is a list of influences, which is
 * why it is paired with a degree floor rather than used alone (see {@code
 * Recommendations.MIN_CANDIDATE_DEGREE}). A domain whose graph is shallower than music's may well
 * want {@link #RESOURCE_ALLOCATION}, so the choice belongs on the command line where it can be
 * compared in one run, not buried in a constant.
 *
 * <p><b>Personalised PageRank is the alternative that is not here.</b> It handles multiple hops
 * natively and is the right family for "start from what I know and see where the mass lands" — and
 * it stays degree-biased without exactly this normalisation, and, decisively, it does not explain
 * itself. A score is not a route, and every candidate in this project has to arrive with its
 * receipts.
 */
public enum Scorer {

  /**
   * Count the connections. The baseline, kept because it is the honest name for what a first
   * attempt does — and because seeing it beside the others in one run is the fastest way to see
   * what the normalisation is for.
   */
  RAW("raw", "raw count of connections, undiscounted", degree -> 1.0, false),

  /**
   * Discount each intermediate by the log of its degree. The classic Adamic-Adar link predictor:
   * sharing a quiet intermediate with something is stronger evidence than sharing a busy one, and
   * the logarithm makes that a gentle preference rather than a veto.
   */
  ADAMIC_ADAR(
      "adamic-adar", "Adamic-Adar: each intermediate discounted by log(degree)", Math::log, false),

  /**
   * Discount each intermediate by its degree itself. Harsher than Adamic-Adar by an order of
   * magnitude on the busiest nodes, which is the point: it all but ignores a connection through
   * something everybody touches.
   */
  RESOURCE_ALLOCATION(
      "resource-allocation",
      "resource allocation: each intermediate discounted by its degree",
      degree -> degree,
      false),

  /**
   * Adamic-Adar, then divided by the candidate's own degree. The measured default: on the real
   * graph this is the point at which the list stopped naming the most famous entities in it and
   * started naming things reached by the list far more often than their size in the graph would
   * predict.
   */
  LIFT(
      "lift",
      "lift: Adamic-Adar over the candidate's own degree — connected to you more than its size"
          + " predicts",
      Math::log,
      true);

  private final String spelling;
  private final String description;

  /** Applied to the intermediate's degree. Never returns zero for a degree of two or more. */
  private final DoubleUnaryOperator discount;

  private final boolean normalisedByCandidateDegree;

  Scorer(
      String spelling,
      String description,
      DoubleUnaryOperator discount,
      boolean normalisedByCandidateDegree) {
    this.spelling = spelling;
    this.description = description;
    this.discount = discount;
    this.normalisedByCandidateDegree = normalisedByCandidateDegree;
  }

  /**
   * Score one candidate.
   *
   * <p>No grouping by intermediate, deliberately, and it is not an approximation of one: every
   * connection through a given intermediate carries that intermediate's degree, so summing {@code
   * weight / discount(degree)} term by term is identically the sum of each intermediate's total
   * divided once. The flat form is the one that stays obviously pure.
   *
   * @param shared every route from a known entity to this candidate through one intermediate
   * @param candidateDegree how many edges the candidate itself carries in the graph
   */
  public double score(List<SharedIntermediate> shared, int candidateDegree) {
    if (candidateDegree < 1) {
      throw new IllegalArgumentException(
          "a candidate reached by the graph has at least one edge, got degree: " + candidateDegree);
    }
    double total = 0.0;
    for (SharedIntermediate connection : shared) {
      total += connection.weight() / discount.applyAsDouble(connection.viaDegree());
    }
    return normalisedByCandidateDegree ? total / candidateDegree : total;
  }

  /** The word the command line uses. */
  public String spelling() {
    return spelling;
  }

  /** The phrase the report uses to say how it was scored. */
  public String describe() {
    return description;
  }

  /** The accepted words, for a usage message. */
  public static String names() {
    return Arrays.stream(values()).map(Scorer::spelling).collect(Collectors.joining("|"));
  }

  /** Parse one command-line word, refusing anything else by name. */
  public static Scorer parse(String word) {
    return Arrays.stream(values())
        .filter(scorer -> scorer.spelling.equalsIgnoreCase(word))
        .findFirst()
        .orElseThrow(
            () ->
                new IllegalArgumentException("unknown scorer " + word + " — expected " + names()));
  }
}
