package com.robsartin.segue.seed;

import java.util.Objects;

/**
 * One conclusion, with the evidence that produced it.
 *
 * <p>A {@code REVIEW} decision still carries a {@code qid} whenever there was a plausible one: the
 * point of the review file is that a person can accept or correct a line without repeating the
 * search by hand.
 *
 * @param qid the identifier, or null when nothing was found at all
 * @param reason why — always populated, including on acceptance, so a sample can be audited
 */
public record Decision(Outcome outcome, String qid, String label, String reason) {

  public Decision {
    Objects.requireNonNull(outcome, "outcome");
    Objects.requireNonNull(reason, "reason");
  }

  public boolean accepted() {
    return outcome == Outcome.ACCEPTED;
  }
}
