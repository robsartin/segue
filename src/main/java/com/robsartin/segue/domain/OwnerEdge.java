package com.robsartin.segue.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * "I know this relationship holds." The owner's own claim of a relationship between two entities -
 * either or both may be a {@link LocalEntity}, since a route the owner asserts has to be able to
 * start or end on something Wikidata does not model. A first-person {@link LoggedAssertion}, on
 * {@link Retraction}'s precedent - its own validation, no {@link Provenance}: what an owner edge
 * carries into the graph is a reserved provenance value (design doc, "The shape"), not this
 * record's concern.
 *
 * <p><b>{@code typeCode} is checked against the controlled vocabulary, not invented here.</b> ADR
 * 22 clause 3 keeps relation types to the set {@link EdgeTypes} registers; an owner asserting a
 * relationship is still asserting one of those relationships, not a new kind of one. This is the
 * same discipline {@link AssertionRecord} would need if it validated its own {@code typeCode} - it
 * does not, today, so this is the first domain record in the log to enforce it at construction.
 *
 * @param fromQid one end of the relationship
 * @param toQid the other end
 * @param typeCode the relation type - must be registered in {@link EdgeTypes}
 * @param assertedAt when the owner made the claim
 */
public record OwnerEdge(String fromQid, String toQid, String typeCode, Instant assertedAt)
    implements LoggedAssertion {

  /**
   * Rebuilds an owner edge, checking only what cannot be re-tightened.
   *
   * <p><b>The vocabulary check is deliberately NOT here</b> - see {@link #claimed}. {@link
   * EdgeTypes} is a mutable registry, and {@code SqliteAssertionLog.readRow} rebuilds logged rows
   * through this constructor: retiring or renaming a code would otherwise make every row that used
   * it undecodable, taking every reader of the log down with it, on rows ADR 19 forbids deleting. A
   * log records what was claimed; whether the vocabulary still registers that type is a question
   * for the moment of claiming, not the moment of reading.
   */
  public OwnerEdge {
    Objects.requireNonNull(fromQid, "fromQid");
    Objects.requireNonNull(toQid, "toQid");
    Objects.requireNonNull(typeCode, "typeCode");
    Objects.requireNonNull(assertedAt, "assertedAt");
    Qid.check(fromQid);
    Qid.check(toQid);
  }

  /**
   * Claim a relationship - the moment of claiming, and where ADR 22 clause 3 is enforced. Every
   * caller that <b>makes</b> an owner edge comes through here; only reconstruction uses the
   * constructor. See {@link LocalEntity#minted} for why the strict half has to be the factory.
   */
  public static OwnerEdge claimed(
      String fromQid, String toQid, String typeCode, Instant assertedAt) {
    Objects.requireNonNull(typeCode, "typeCode");
    if (EdgeTypes.byCode(typeCode).isEmpty()) {
      throw new IllegalArgumentException("no registered edge type for code: " + typeCode);
    }
    return new OwnerEdge(fromQid, toQid, typeCode, assertedAt);
  }

  /**
   * The projection's view of this claim, attributed to the owner rather than to a source (#92).
   *
   * <p>The graph and the export fold both hold relationships as {@link AssertionRecord}s carrying
   * {@link Provenance}, and there is no third shape for an unsourced one. So the conversion lives
   * here, once, on {@link LocalEntity#toNode()}'s precedent: {@code IngestService.apply}, replay
   * and {@code LogProjection} all project the same owner edge the same way, and cannot drift into
   * attributing it differently.
   *
   * <p>No validity dates: an owner asserting that a relationship holds is not stating when it began
   * or ended, and inventing an interval here would make the edge answer {@code validAt} questions
   * on a claim nobody made.
   */
  public AssertionRecord toAssertion() {
    return new AssertionRecord(fromQid, toQid, typeCode, null, null, Provenance.owner(assertedAt));
  }
}
