package com.robsartin.segue.domain;

import java.util.Collection;
import java.util.Objects;

/**
 * One confidence per entity, and which kind of evidence it came from (#351).
 *
 * <p>The blend is a pure function — no store, no HTTP, on {@code Deck}'s precedent — so the
 * recommender, the deck and the evaluation harness can all ask it the same question and cannot
 * disagree about the answer.
 *
 * <p><b>The scale.</b> {@code confidence} runs from 0 to 1 with 0.5 as neutral, which is the rating
 * scale re-based: a rating {@code r} is {@code (r - 1) / 4}, so a 3 is 0.5, a 1 is 0 and a 5 is 1.
 * {@link #regard()} turns it back into the weight {@code Recommendations.regardFor} has always
 * applied — {@code (4c + 1) / 3} is exactly {@code r / 3} for every rating, which is why an empty
 * signal table reproduces the ranking ADR 45 measured.
 *
 * <p><b>The rules.</b>
 *
 * <ul>
 *   <li>An explicit rating always wins. A signal never changes a rated entity's confidence, and in
 *       particular never raises a 1 or a 2: a count says what was played, not what was wanted, and
 *       a rating of 1 on something played forty times is the owner saying so.
 *   <li>Signals only ever raise an unrated entity, and they raise it into a band that stays
 *       strictly below a rating of 4 ({@link #LOVED} is 0.7, a 4 is 0.75): the evidence kind is
 *       part of the weight, so a candidate reached by several inferred-only entities does not
 *       outrank one reached by fewer rated ones on the strength of inference alone.
 *   <li>Several signals for one entity — two sources — take the strongest bucket. Play counts from
 *       different sources are different catalogues and do not add.
 * </ul>
 *
 * <p><b>The thresholds are named constants</b> so the evaluation harness can move them. They are
 * judgements, not measurements, and ADR 33's amendment for #351 says so.
 *
 * <p>An {@code Interest} carries no count: only a confidence and its evidence kind may go anywhere
 * a model can read, for the reason ADR 33 keeps a note off every MCP surface.
 *
 * @param confidence 0 to 1, with 0.5 neutral
 * @param evidence what the confidence rests on
 */
public record Interest(double confidence, Evidence evidence) {

  /** Plays at or above which an unrated entity counts as lightly listened to. */
  public static final long LIGHT_PLAYS = 5;

  /** Plays at or above which an unrated entity counts as heavily listened to. */
  public static final long HEAVY_PLAYS = 25;

  /** What nothing is known amounts to: neutral. */
  public static final double NEUTRAL = 0.5;

  /** Inferred from a few plays: barely above neutral. */
  public static final double LIGHT = 0.55;

  /** Inferred from many plays. */
  public static final double HEAVY = 0.6;

  /** Inferred from a loved or favorited flag: the strongest inference, and still under a 4. */
  public static final double LOVED = 0.7;

  public Interest {
    if (confidence < 0 || confidence > 1) {
      throw new IllegalArgumentException("confidence is between 0 and 1");
    }
    Objects.requireNonNull(evidence, "evidence");
  }

  /** Nothing is known about the entity. */
  public static Interest none() {
    return new Interest(NEUTRAL, Evidence.NONE);
  }

  /** The owner rated it. */
  public static Interest rated(int rating) {
    RatingScale.check(rating);
    return new Interest((rating - 1) / 4.0, Evidence.RATED);
  }

  /**
   * The blend: the rating where there is one, the signals where there is not, neutral otherwise.
   *
   * @param rating the owner's rating, or {@code null} if unrated
   * @param signals every observation of the entity, from any source; may be empty
   */
  public static Interest of(Integer rating, Collection<Signal> signals) {
    Objects.requireNonNull(signals, "signals");
    if (rating != null) {
      return rated(rating);
    }
    double strongest = NEUTRAL;
    for (Signal signal : signals) {
      strongest = Math.max(strongest, bucket(signal));
    }
    return strongest > NEUTRAL ? new Interest(strongest, Evidence.INFERRED) : none();
  }

  /** None, light, heavy or loved, from one observation. */
  private static double bucket(Signal signal) {
    if (signal.loved() || signal.favorited()) {
      return LOVED;
    }
    if (signal.playCount() >= HEAVY_PLAYS) {
      return HEAVY;
    }
    if (signal.playCount() >= LIGHT_PLAYS) {
      return LIGHT;
    }
    return NEUTRAL;
  }

  /**
   * The weight {@code Recommendations.regardFor} applies: {@code (4c + 1) / 3}, which is {@code
   * rating / 3} for a rated entity and exactly 1.0 for a neutral one.
   */
  public double regard() {
    return (4 * confidence + 1) / 3;
  }
}
