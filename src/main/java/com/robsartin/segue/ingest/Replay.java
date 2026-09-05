package com.robsartin.segue.ingest;

import com.robsartin.segue.domain.Fold;
import java.util.Objects;

/**
 * What one replay produced: how many assertions reached the store, and the {@link Fold} it applied
 * them under (#246).
 *
 * <p><b>It exists so a tool that replays does not fold the same log twice.</b> {@code recommend},
 * {@code rate} and {@code evaluate} each replay into a throwaway graph and then read the log a
 * second time for the merges, because a merge is deliberately not drawn in the graph as an edge and
 * {@code project} answered with a count. The fold they re-derived is the one {@code project} had
 * just built. This record hands it back.
 *
 * <p><b>A value rather than a {@code Consumer<Fold>} callback</b>, which was the alternative: a
 * consumer cannot return, so every caller would invent a mutable holder whose only purpose is to
 * defeat the callback, and the ordering of the callback against the replay would be a convention
 * rather than a type.
 *
 * <p>{@code GraphProjector.project} is unchanged and returns {@link #applied} alone — sixty call
 * sites keep the signature they have, which is what makes this a parallel field rather than a
 * big-bang change to a return type (ADR 4).
 */
public record Replay(long applied, Fold fold) {

  public Replay {
    Objects.requireNonNull(fold, "fold");
  }
}
