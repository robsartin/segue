package com.robsartin.segue.port;

import com.robsartin.segue.domain.AffinityRecord;
import java.util.List;
import java.util.Optional;

/**
 * The taste layer's seam (ADR 33). Separate from {@link AssertionLog} on purpose, and the
 * separation is the point rather than a side effect.
 *
 * <p>Three things follow from that, and each is visible in this interface's shape:
 *
 * <ul>
 *   <li><b>No {@code append}.</b> The world layer is append-only because sources disagree and the
 *       disagreement is evidence (ADR 19); a first-person preference has nobody to disagree with,
 *       so ADR 39 keeps one row per entity and lets the later rating win. {@link #put} is an
 *       upsert, and there is no history to read.
 *   <li><b>{@link #readAll()} exists for exactly one caller.</b> This bullet used to read "no
 *       {@code readAll}", on ADR 16's data minimisation: a bulk read is the one operation that
 *       makes the whole taste layer available in a single call. That argument was never about the
 *       port — it was about the <em>tool surface</em>, and it still holds there. ADR 43 separates
 *       the two: the owner needs to see their own ratings, a model does not, and the reader is a
 *       dev-side Gradle tool rather than a seventh MCP tool (ADR 26, ADR 39). {@code
 *       ArchitectureTest.onlyTheRatingsToolReadsEveryRating} keeps that distinction a build failure
 *       rather than a convention: nothing outside {@code ratings} may call this method.
 *   <li><b>No provenance argument.</b> Not an omission: ADR 33 says affinity carries none.
 * </ul>
 *
 * <p>Implementations must never write to the graph or the log, and nothing in {@code ingest} may
 * reach this port - ArchUnit's {@code affinityNeverTouchesTheWorldFactLayer} and {@code
 * theWorldFactLayerNeverTouchesAffinity} rules make both of those build failures rather than
 * conventions.
 */
public interface AffinityStore extends AutoCloseable {

  /**
   * Record what the user thinks of one entity, replacing whatever was there before.
   *
   * <p>Overwrite, not append (ADR 39). "I loved this in 2010, it is fine now" is real signal and it
   * is deliberately not retained: a trail would complicate the wholesale delete ADR 33 lists as a
   * benefit of keeping this layer separate, and the {@code updatedAt} on the surviving row already
   * answers the question anyone actually asks of it - when did this last change.
   */
  void put(AffinityRecord affinity);

  /** What the user thinks of this entity, or empty if they have never said. */
  Optional<AffinityRecord> find(String qid);

  /**
   * Every rating there is, in {@code qid} order (ADR 43).
   *
   * <p><b>Ordered, but not in an order anyone wants to read.</b> The two orderings that answer a
   * question — by rating, and by when it last changed — belong to the caller, and a store that
   * chose one of them would be answering a presentation question. What the port owes is
   * determinism, so that two runs over an unchanged table produce the same list.
   *
   * <p><b>Read only by the {@code ratings} dev tool.</b> Not by {@code mcp}: ADR 39 declined a bulk
   * {@code list_affinity} because it is the single call that would expose the entire taste layer to
   * a model, and that reasoning stands. See this interface's Javadoc, and the ArchUnit rule that
   * enforces it.
   */
  List<AffinityRecord> readAll();

  @Override
  void close();
}
