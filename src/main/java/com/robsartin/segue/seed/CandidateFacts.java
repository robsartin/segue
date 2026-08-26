package com.robsartin.segue.seed;

import com.robsartin.segue.domain.NodeKind;
import java.util.List;
import java.util.Objects;

/**
 * Everything the tool knows about one possible answer, after the batched {@code wbgetentities}
 * call.
 *
 * <p>{@link com.robsartin.segue.domain.Candidate} is what search returns and deliberately reports
 * {@code CONCEPT} for everything, because {@code wbsearchentities} cannot see {@code P31}. This is
 * the same entity once the extra round trip has happened, which is why it is a separate type and
 * lives here rather than in {@code domain}: nothing in the graph needs a sitelink count or an
 * occupation list.
 *
 * @param aliases the entity's other recorded English names — Wikidata's own claim that this thing
 *     is also called that, which is how a duo billed under an early name is still found
 * @param sitelinks how many Wikipedias carry an article, standing in for how well known it is
 */
public record CandidateFacts(
    String qid,
    String label,
    String description,
    List<String> aliases,
    NodeKind kind,
    List<String> occupations,
    int sitelinks) {

  public CandidateFacts {
    Objects.requireNonNull(qid, "qid");
    Objects.requireNonNull(label, "label");
    aliases = List.copyOf(Objects.requireNonNull(aliases, "aliases"));
    Objects.requireNonNull(kind, "kind");
    occupations = List.copyOf(Objects.requireNonNull(occupations, "occupations"));
  }

  /** Human-readable, for a review file a person has to read. */
  public String describe() {
    return description == null || description.isBlank()
        ? qid + " (" + label + ")"
        : qid + " (" + label + " — " + description + ")";
  }
}
