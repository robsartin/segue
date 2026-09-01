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

  /**
   * Rebuilds a minted entity, checking only what Wikidata's own grammar fixes.
   *
   * <p><b>The convention check is deliberately NOT here</b> - see {@link #minted}. This constructor
   * is also the path {@code SqliteAssertionLog.readRow} rebuilds a logged row through, and the log
   * is append-only (ADR 19): a row written last week has to stay decodable after the convention
   * moves, which it already has once ({@code c837265}). Re-running today's convention against a row
   * written under yesterday's is re-litigating history, and it would make one old row take out boot
   * replay, {@code rate}, {@code recommend}, {@code exportGraph}, {@code retractEntity} and {@code
   * listRatings} at once, on a row nothing may delete.
   *
   * <p>What remains is only what cannot be re-tightened by this project: {@code Q\d+}, and ADR 58's
   * grammar fact that Wikidata never allocates a leading zero. Borrowing an id Wikidata could hand
   * to something else is a collision rather than a convention, so it is refused on every path,
   * reconstruction included.
   */
  public LocalEntity {
    Objects.requireNonNull(qid, "qid");
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(label, "label");
    Objects.requireNonNull(mintedAt, "mintedAt");
    checkUnallocatable(qid);
  }

  /**
   * Mint a local entity - the moment of claiming, and where the convention is enforced.
   *
   * <p>Everything that <b>makes</b> a claim comes through here, so nothing malformed is ever
   * written. Only reconstruction uses the constructor directly.
   *
   * <p><b>Why a factory rather than a stricter constructor.</b> A record cannot offer a trusting
   * construction path: every constructor must delegate to the canonical one, and a public record's
   * canonical constructor cannot be narrowed (javac: "attempting to assign stronger access
   * privileges; was public"). So a static factory cannot be the trusting half either - it would run
   * the compact constructor too. The only place trust can live is the canonical constructor, which
   * is why the strict half is the factory and not the other way round.
   */
  public static LocalEntity minted(String qid, NodeKind kind, String label, Instant mintedAt) {
    checkLocalShape(qid);
    return new LocalEntity(qid, kind, label, mintedAt);
  }

  /** The projection's view of this claim - no {@code instanceOf}, because no source stated any. */
  public NodeRecord toNode() {
    return new NodeRecord(qid, kind, label, List.of());
  }

  /**
   * Refuse anything Wikidata could allocate. A grammar fact (ADR 58), not a convention: it cannot
   * be re-tightened by this project, so it is safe to enforce on reconstruction too. Shared with
   * {@link SameAs}, whose local side is the same claim by a different name.
   */
  static void checkUnallocatable(String qid) {
    Qid.check(qid);
    if (Qid.isAllocatable(qid)) {
      throw new IllegalArgumentException(
          "a local entity's qid must not be allocatable by Wikidata, got: " + qid);
    }
  }

  /**
   * Whether this id is shaped like one of the owner's own entities, for callers that ask rather
   * than refuse - the counterpart of {@link Qid#isAllocatable} on the other side of ADR 58's
   * leading-zero space.
   *
   * <p><b>Shape, not membership in the log.</b> {@code SegueService} asks this to refuse an
   * expansion (#92), and it holds a graph rather than a log: what it can see is the id. That is
   * enough, because the shape <em>is</em> the identity decision - a {@code Q00} id is one no source
   * can ever have allocated, so no source can ever know it, whoever minted it and whenever. A
   * leading-zero stand-in is deliberately not matched: it stands in for a real Wikidata entity, and
   * an adapter that has been handed one is entitled to answer for it.
   */
  public static boolean isLocal(String qid) {
    return qid != null && LOCAL_SHAPE.matcher(qid).matches();
  }

  /**
   * Refuse anything unallocatable that still is not shaped like a local entity (most likely one of
   * ADR 58's single-leading-zero stand-ins). This project's own convention, enforced at the moment
   * of claiming only - it moved once already and may move again, and rows written before it moved
   * still have to be readable.
   */
  static void checkLocalShape(String qid) {
    checkUnallocatable(qid);
    if (!isLocal(qid)) {
      throw new IllegalArgumentException(
          "a local entity's qid must have two leading zeros (Q00..., ADR 58 issue #141) to stay"
              + " distinct from a single-leading-zero stand-in, got: "
              + qid);
    }
  }
}
