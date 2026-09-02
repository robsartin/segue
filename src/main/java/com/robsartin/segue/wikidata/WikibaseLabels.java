package com.robsartin.segue.wikidata;

/**
 * One rule about {@code SERVICE wikibase:label}'s answers, in one place.
 *
 * <p>The service hands back the bare QID when no label exists in the languages asked for, so a
 * caller that believes every string it returns fills the graph with nodes called {@code
 * Q121998451}. {@link ReverseClaims} learned that against ADR 36's reverse query and {@code
 * WikidataMusicBrainzIdentity} meets the identical answer from the identical service on issue
 * #163's widened bridge query.
 *
 * <p><b>Shared rather than copied.</b> The two call sites were byte-identical, kept equal by a
 * comment in each saying that two answers to this question would be two graphs — which is a true
 * statement and a poor mechanism. {@code app} may depend on {@code wikidata} (ADR 32), so the rule
 * can simply have one definition; this class is public for that reason and holds nothing else.
 */
public final class WikibaseLabels {

  private WikibaseLabels() {}

  /**
   * The label, or null where the service returned nothing worth believing.
   *
   * <p>Null is how an entity stays <b>undescribed</b>, and undescribed is a useful answer rather
   * than a failure: it is what lets a caller fall back to a real fetch, which is the behaviour that
   * already exists.
   *
   * @param qid the entity the label was asked for — the string the service echoes when it has none
   * @param label whatever {@code ?xLabel} bound, possibly null
   */
  public static String believable(String qid, String label) {
    if (label == null || label.isBlank() || label.equals(qid)) {
      return null;
    }
    return label;
  }
}
