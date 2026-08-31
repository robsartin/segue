package com.robsartin.segue.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * "This exists, and Wikidata does not model it." An indie author's book, a self-pressed record -
 * anything #92 exists for. A first-person {@link LoggedAssertion}, on {@link Retraction}'s
 * precedent: its own validation, in {@code domain}, and no {@link Provenance} - there is no source
 * to attribute this to, because the owner minting it is the source.
 *
 * <p><b>Identity reuses ADR 58's unallocatable-QID mechanism (issue #141).</b> Wikibase's {@code
 * ItemId} grammar is {@code Q[1-9]\d{0,9}}: the first digit after {@code Q} may never be zero, so a
 * qid that starts {@code Q0} can never be allocated by Wikidata, now or in the future. ADR 58's
 * decision reserves that leading-zero shape for a <b>stand-in</b> generally - {@code Fixture}'s
 * constants are one population living in that shape, not the whole of what the shape means, and
 * nothing in ADR 58 is scoped to "test fixtures" alone.
 *
 * <p><b>The local-entity band is a second shape, not a number range.</b> A numeric floor inside the
 * single-leading-zero space cannot separate two open-ended families from each other: issue #171
 * will migrate {@code Q900100} - a stand-in family already used in 25 files, one of the largest ADR
 * 58 leaves to migrate - into leading-zero form as {@code Q0900100}, which is larger than any floor
 * small enough to admit this record's own examples. Choosing such a floor is exactly the mistake
 * ADR 58's own postmortem describes: a number that looks free until something else claims it. A
 * range also protects only one direction - nothing would stop a sixteenth {@code Fixture} constant
 * from landing inside a floor meant for local entities and colliding silently.
 *
 * <p>So a local entity's qid takes <b>two</b> leading zeros - {@code Q00} followed by at least one
 * more digit - while every stand-in, present and future, keeps exactly one. Both shapes are
 * unallocatable under {@code Q[1-9]\d{0,9}} and both match every {@code Q\d+} pattern in {@code
 * src/main}, so nothing outside this check has to learn about the second zero, and the distinction
 * survives #171 landing on whatever numbers it lands on, because it is a shape rather than a range.
 * <b>The leading zeros are the discriminator, not decoration</b> - {@code Q0900042} is a stand-in's
 * shape and is refused here; {@code Q00900042} is a local entity's, and is not.
 *
 * @param qid the local entity's own identifier - two leading zeros ({@code Q00...})
 * @param kind what it is, same as any other node
 * @param label how the owner refers to it
 * @param mintedAt when the owner minted it
 */
public record LocalEntity(String qid, NodeKind kind, String label, Instant mintedAt)
    implements LoggedAssertion {

  /**
   * The local-entity shape: {@code Q00} followed by one or more digits. Deliberately a prefix
   * match, not a parsed value - see the class javadoc for why a numeric floor cannot do this job.
   */
  private static final Pattern LOCAL_SHAPE = Pattern.compile("Q00\\d+");

  public LocalEntity {
    Objects.requireNonNull(qid, "qid");
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(label, "label");
    Objects.requireNonNull(mintedAt, "mintedAt");
    checkLocalBand(qid);
  }

  /** The projection's view of this claim - no {@code instanceOf}, because no source stated any. */
  public NodeRecord toNode() {
    return new NodeRecord(qid, kind, label, List.of());
  }

  /**
   * Refuse anything Wikidata could allocate, and anything it could not that still is not shaped
   * like a local entity (most likely one of ADR 58's single-leading-zero stand-ins). Shared with
   * {@link SameAs}, whose local side is the same claim by a different name.
   */
  static void checkLocalBand(String qid) {
    Qid.check(qid);
    if (Qid.isAllocatable(qid)) {
      throw new IllegalArgumentException(
          "a local entity's qid must not be allocatable by Wikidata, got: " + qid);
    }
    if (!LOCAL_SHAPE.matcher(qid).matches()) {
      throw new IllegalArgumentException(
          "a local entity's qid must have two leading zeros (Q00..., ADR 58 issue #141) to stay"
              + " distinct from a single-leading-zero stand-in, got: "
              + qid);
    }
  }
}
