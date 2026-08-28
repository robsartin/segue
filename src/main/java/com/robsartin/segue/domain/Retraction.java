package com.robsartin.segue.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * "Everything recorded about this entity before now is wrong, and here is why." Appended to the log
 * like any other row; the log is never edited (ADR 19, ADR 44).
 *
 * <p><b>This is a claim, not a deletion.</b> A wrong claim is data about what a source said, not
 * corruption to be scrubbed: deleting rows would make the log a mutable store that merely happens
 * to be append-shaped, and every guarantee resting on ADR 19 - replay reproducing the graph, the
 * audit trail, ADR 42's offline re-derivation - would become conditional on nobody having deleted
 * anything. So a retraction changes what the projection <em>says</em> without rewriting what was
 * <em>recorded</em>, which is exactly the shape ADR 42 gave node kinds. {@link Retractions} is the
 * rule that both projections apply.
 *
 * <p><b>It carries no {@link Provenance}, deliberately.</b> Provenance answers "which source told
 * us this, and how much do we believe them" - and a retraction has no source and is not a matter of
 * belief. It is the owner's own act, the same first-person shape ADR 33 gives affinity, and the
 * same reasoning applies: a {@code sourceId} of "operator" would carry no information in a
 * single-writer system (ADR 24), a {@code sourceRef} is a citation and a reason is not one, and a
 * {@code confidence} of 1.00 means "a Wikidata statement with a reference", which this is not. What
 * such an act CAN honestly carry is what this record holds.
 *
 * @param qid the entity being taken back out of the projection
 * @param reason why - required, and not decoration. The point of keeping a retraction in an
 *     append-only log is that it records that we later concluded something was wrong; a row that
 *     does not say what the conclusion was records only half of that, and there is no editing it
 *     afterwards
 * @param retractedAt when the conclusion was reached. This is the one dimension of ADR 20 a
 *     retraction has: it is not a claim about the world, so it has no validity interval, and there
 *     is nothing for {@code validFrom}/{@code validTo} to mean
 */
public record Retraction(String qid, String reason, Instant retractedAt)
    implements LoggedAssertion {

  public Retraction {
    Objects.requireNonNull(qid, "qid");
    Objects.requireNonNull(reason, "reason");
    Objects.requireNonNull(retractedAt, "retractedAt");
    // A retraction is not corrected by editing the log, so a mistyped target is a row that sits
    // there forever retracting an entity nobody ever claimed. NodeRecord validates its qids for a
    // smaller reason than this one; both go through the same rule.
    Qid.check(qid);
    if (reason.isBlank()) {
      throw new IllegalArgumentException("a retraction must say why");
    }
  }
}
