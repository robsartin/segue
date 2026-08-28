package com.robsartin.segue.port;

import com.robsartin.segue.domain.AffinityRecord;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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

  /**
   * Change what the user thinks of one entity, and <b>leave the note exactly as it is</b> (issue
   * #109 review).
   *
   * <p><b>A signature with nowhere to put a note, on purpose.</b> {@link #put} writes the whole
   * row, note included, which is right for {@code note_affinity} — the one caller that has a note
   * to write. It is wrong for the rating deck. {@code
   * ArchitectureTest.theRatingDeckNeverReadsANote} bans every class in {@code rate} from calling
   * {@code AffinityRecord.note()}, so the deck structurally cannot read a note and carry it back;
   * before {@code --revise} that fence cost nothing, because the deck could only reach UNRATED
   * entities and a note requires a rating. {@code Deck.dealRevision} inverts exactly that: it
   * selects the already-rated population, which is precisely where notes live. Re-rating through
   * {@link #put} then wrote {@code note = null} over a note nothing can regenerate.
   *
   * <p>So the fix is a second write with no note in it, rather than a deck allowed to read one. An
   * implementation must not mention the note column at all — neither reading it nor writing it —
   * which is the same reasoning that makes {@link #readRatings()}'s {@code Map<String, Integer>} a
   * fence rather than a convenience.
   *
   * <p><b>It inserts when the row is absent.</b> "Update" names the intent, not a SQL verb: the
   * deck's default mode deals unrated entities and writes their first rating through this same
   * path, so a method that could only update would refuse the commoner case. A row created here has
   * no note because there is nobody to have written one.
   *
   * <p><b>An implementation validates, because this is the one write into the table that builds no
   * {@link AffinityRecord}.</b> That record's compact constructor is where both invariants used to
   * live, and a write that skips it skips them: the {@code affinity} table has no {@code CHECK}
   * constraint, so a non-QID would be stored and every later read that rebuilds a record would
   * throw past the implementation's own {@code SQLException} handling — permanently, on data with
   * no source to regenerate from.
   *
   * @param qid must be a QID, checked through {@code Qid}
   * @param rating 1 to 5; an implementation refuses anything else, through {@code RatingScale}
   */
  void updateRating(String qid, int rating, Instant updatedAt);

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

  /**
   * Every score there is, keyed by qid — and no notes (ADR 33 as amended by issue #85).
   *
   * <p><b>A second bulk read exists because the two fields are no longer one kind of data.</b> ADR
   * 33 used to treat the whole taste layer as personal; issue #85 split it. A rating is the
   * known-list at higher resolution and may be read, weighted and discussed; a note is free text no
   * schema constrains, and it stays with the owner. {@link #readAll()} still answers "show me my
   * ratings, in my own words" for the {@code ratings} tool; this answers "how much does each of
   * these count for", which is what the recommender's affinity weighting needs.
   *
   * <p><b>The return type is the fence, not a convenience.</b> A {@code Map<String, Integer>} has
   * nowhere to put a note, so a caller holding this cannot leak one however carelessly it is
   * written — which is what lets {@code ArchitectureTest.theRecommenderReadsRatingsAndNeverNotes}
   * replace the old blanket ban on the recommender seeing this port at all. An implementation must
   * not select the note column to build it.
   *
   * <p>Unordered, deliberately: this is a lookup table, and a caller wanting an order wants {@link
   * #readAll()} and one of ADR 43's two comparators.
   */
  Map<String, Integer> readRatings();

  @Override
  void close();
}
