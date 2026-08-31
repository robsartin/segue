package com.robsartin.segue.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * Who claimed something, when, and how much you believe them.
 *
 * <p>{@code sourceId} is the adapter that produced the claim ("wikidata", "lastfm", "llm:claude").
 * {@code sourceRef} is the citation you can click - a Wikidata statement URI, an API response id, a
 * chat turn.
 *
 * <p>Confidence convention shared by all adapters:
 *
 * <ul>
 *   <li>1.00 - structured and authoritative (Wikidata statement with a reference)
 *   <li>0.80 - structured but unreferenced (Wikidata statement, no source cited)
 *   <li>0.50 - statistical or behavioural (last.fm similarity)
 *   <li>0.30 - model-generated hypothesis, not yet corroborated
 * </ul>
 */
public record Provenance(String sourceId, String sourceRef, Instant assertedAt, double confidence) {

  /**
   * The source id every first-person claim carries into the graph (#92).
   *
   * <p>The owner's own claims - a minted {@link LocalEntity}, an {@link OwnerEdge}, a {@link
   * SameAs} - are not sourced, but the graph has nowhere to put a claim with no provenance at all:
   * {@link EdgeRecord} is a list of {@code Provenance} and nothing else. So they carry a reserved
   * id instead of a real source's, which is what {@link #isOwner()} asks about.
   *
   * <p><b>It is deliberately not prefixed {@code llm:}</b>, and that is load-bearing rather than
   * cosmetic: {@link #isHypothesis()} is a prefix test on exactly that string, and {@code
   * EdgeRecord.isUncorroboratedHypothesis()} is what {@code PathRanking} demotes on. An owner
   * saying "I know this holds" is the opposite of a model's unverified guess, so no routing
   * exemption is needed anywhere - the id alone keeps owner edges out of the quarantined tier.
   */
  public static final String OWNER = "owner";

  /**
   * What an owner claim believes. The owner is the source and is not hedging, so this is the same
   * 1.00 the confidence convention above gives a referenced Wikidata statement. It is a belief
   * figure, not a corroboration one: whether an owner claim counts as a second witness is {@link
   * EdgeRecord#corroboration()}'s question, not this one.
   */
  private static final double OWNER_CONFIDENCE = 1.0;

  /** Field separator used by the TinkerGraph provenance codec. */
  public static final String FIELD_SEP = "\t";

  /** Record separator used by the TinkerGraph provenance codec. */
  public static final String RECORD_SEP = "\n";

  public Provenance {
    Objects.requireNonNull(sourceId, "sourceId");
    Objects.requireNonNull(assertedAt, "assertedAt");
    if (confidence < 0.0 || confidence > 1.0) {
      throw new IllegalArgumentException("confidence must be in [0,1], got: " + confidence);
    }
    // The TinkerGraph adapter packs provenance into a delimited string; keeping
    // the separators out of the data means the codec needs no escaping.
    requireNoSeparators(sourceId, "sourceId");
    requireNoSeparators(sourceRef, "sourceRef");
  }

  private static void requireNoSeparators(String value, String field) {
    if (value != null && (value.contains(FIELD_SEP) || value.contains(RECORD_SEP))) {
      throw new IllegalArgumentException(field + " must not contain tabs or newlines");
    }
  }

  /**
   * The provenance a first-person claim carries into the graph, at the instant the owner made it.
   * One definition, so every projection of an owner claim - live ingest, boot replay, the export
   * fold - attributes it identically.
   */
  public static Provenance owner(Instant assertedAt) {
    return new Provenance(OWNER, null, assertedAt, OWNER_CONFIDENCE);
  }

  /** Model-proposed edges stay quarantined until a real source agrees. */
  public boolean isHypothesis() {
    return sourceId.startsWith("llm:");
  }

  /** Whether the owner claimed this themselves, rather than a source reporting it (#92). */
  public boolean isOwner() {
    return OWNER.equals(sourceId);
  }
}
