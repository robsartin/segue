package com.robsartin.segue.ratings;

import java.time.Instant;
import java.util.Objects;

/**
 * One rating, joined to the label the graph knows the entity by. The only type in this package that
 * holds personal data, and the reason the package exists.
 *
 * <p><b>The name is load-bearing.</b> {@code
 * ArchitectureTest.affinityNeverTouchesTheWorldFactLayer} matches taste-layer types by simple name
 * rather than by package - ADR 33's boundary is not a package - so calling this {@code AffinityRow}
 * opts it into that fence deliberately, exactly as {@code AffinityOverlay} is. It may never grow a
 * {@link com.robsartin.segue.domain.Provenance}, a corroboration count or a reference to the log,
 * which is the shape a rating drifts into when somebody makes it "consistent with everything else".
 *
 * <p>It is also why the class that <em>builds</em> these - {@link RatingsRun} - is deliberately not
 * called {@code Affinity}-anything: it holds an {@link com.robsartin.segue.port.AssertionLog} to
 * find labels with, and the same fence would refuse to compile it. The join between the two layers
 * happens above both ports and nowhere else (ADR 33), and here the naming says which side of that
 * line each class is on.
 *
 * <p>{@code label} is a world fact and a {@link String}, not a world-fact <em>type</em>, so
 * carrying it breaks nothing: this row is the result of the join, not a participant in it.
 *
 * @param qid the entity rated, on the one identity spine (ADR 22)
 * @param label what the graph calls it, or null when the graph has no claim about this entity at
 *     all. Nullable rather than defaulted, so {@link #NO_LABEL} is written in exactly one place and
 *     an absent label can never be mistaken for a real one
 * @param rating 1 to 5 (ADR 39)
 * @param note free text, or null
 * @param updatedAt when the rating last changed - the only trace of taste drift there is
 */
public record AffinityRow(String qid, String label, int rating, String note, Instant updatedAt) {

  /**
   * What the label column says when the graph cannot name the entity.
   *
   * <p>Honest rather than helpful, the way ADR 41's DOT tooltip falls back to a bare QID: the
   * alternative is a blank column that reads as a rendering bug. ADR 39 requires an entity to be in
   * the graph before it can be rated, so this should be empty - but affinity is the one thing in
   * segue that cannot be regenerated, and the graph around it can be rebuilt from Wikidata at any
   * time. A rating that outlives its node must still be listed.
   */
  public static final String NO_LABEL = "(not in the graph)";

  public AffinityRow {
    Objects.requireNonNull(qid, "qid");
    Objects.requireNonNull(updatedAt, "updatedAt");
  }

  /** The label, or {@link #NO_LABEL}. */
  public String displayLabel() {
    return label == null ? NO_LABEL : label;
  }

  /**
   * The note on one line, or empty.
   *
   * <p>A note is free text and may contain line breaks; a table row may not. Flattening is lossless
   * enough to be honest - every word survives, in order - where a truncation would not be.
   */
  public String displayNote() {
    return note == null ? "" : note.replaceAll("\\R+", " ").strip();
  }
}
