package com.robsartin.segue.port;

import com.robsartin.segue.domain.AffinityRecord;
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
 *   <li><b>No {@code readAll}.</b> {@link AssertionLog#readAll()} exists because the graph is a
 *       projection that has to be rebuilt at boot. Affinity is projected into nothing, so nothing
 *       needs to sweep it — and a bulk read is the one operation that would make the whole taste
 *       layer available in a single call, which ADR 16's data minimisation argues against offering
 *       until something actually needs it.
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

  @Override
  void close();
}
