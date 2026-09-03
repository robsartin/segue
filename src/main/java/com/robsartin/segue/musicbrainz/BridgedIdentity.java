package com.robsartin.segue.musicbrainz;

import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.domain.Qid;
import java.util.List;
import java.util.Objects;

/**
 * What the bridge knows about a neighbour: the QID it resolved to, and — when the bridge behind
 * {@link MusicBrainzIdentity} could see them — the label and classes that would otherwise cost a
 * fetch each (issue #163).
 *
 * <p><b>Not a {@code NodeAssertion}, deliberately.</b> A {@code NodeAssertion} carries a {@code
 * Provenance}, and issue #147 put the minting of one in {@code MusicBrainzSourceAdapter}, beside
 * the guards that check every string going into it. A seam that minted its own would be a second
 * place where a source id and a confidence are decided.
 *
 * <p><b>{@code instanceOf} is the raw {@code P31}, not a kind.</b> ADR 42's reason: a claim that
 * carries its own classes can have its kind re-derived offline by any projection, which is what
 * makes an improvement to the class whitelist reach entities the graph already has. {@code kind} is
 * that whitelist's answer for these classes at the time the bridge asked, carried alongside rather
 * than instead.
 *
 * <p><b>{@code Qid.check}, not {@code Qid.checkAllocatable}.</b> ADR 58's rule is that a
 * <i>stand-in</i> identifier must take a shape Wikidata cannot allocate, so a record demanding an
 * allocatable QID would refuse every {@code Q09000xx} the test suite is required to use — this
 * type's own fixtures included. {@code Qid.check}'s {@code Q\d+} is exactly the two shapes ADR 58
 * permits: allocatable, or a leading-zero stand-in. The values are guarded again where they arrive
 * ({@code WikidataMusicBrainzIdentity} drops a binding whose item is not a QID, and {@code
 * MusicBrainzIdentity#identitiesFor}'s default drops a binding whose item is not a QID), but a
 * value type the adapter is about to trust should carry its own shape rather than borrow theirs.
 *
 * <p><b>Every {@code instanceOf} element too, since issue #163's fix round 1.</b> The sentence
 * above used to name {@code qid} alone, and {@code NodeRecord} — the type that does refuse a
 * malformed class id — refuses it from inside {@code IngestService.apply}, which runs <b>after</b>
 * the claim has been appended. So a class id that was not a QID left the append-only log (ADR 19)
 * holding a row {@code GraphProjector} re-throws on at every boot, and aborted the expansion that
 * wrote it. A producer reading such a row calls {@link #describing} rather than this constructor.
 *
 * @param qid the Wikidata item this MBID bridges to, always a QID
 * @param kind the kind those classes imply, never null — {@link NodeKind#CONCEPT} where they imply
 *     nothing, which is ADR 22's "we could not place this"
 * @param label the entity's own label, or <b>null</b> where the bridge has none worth believing.
 *     Null is the one undescribed answer: a blank label is normalised to it here, exactly where a
 *     bare-QID label is refused in {@code WikidataMusicBrainzIdentity.rememberLabel}, so {@code
 *     MusicBrainzSourceAdapter}'s guard is {@code label != null} and nothing more. An undescribed
 *     neighbour is one that adapter omits from {@code neighbors()}, leaving {@code SegueService} to
 *     fall back to a real fetch — the behaviour that already exists.
 * @param instanceOf the {@code P31} classes, possibly empty, never null
 */
public record BridgedIdentity(String qid, NodeKind kind, String label, List<String> instanceOf) {

  public BridgedIdentity {
    Qid.check(qid);
    Objects.requireNonNull(kind, "kind");
    label = label == null || label.isBlank() ? null : label;
    instanceOf = List.copyOf(Objects.requireNonNull(instanceOf, "instanceOf"));
    instanceOf.forEach(Qid::check);
  }

  /**
   * A neighbour this bridge resolved but could not describe: a QID, {@link NodeKind#CONCEPT} for
   * ADR 22's "we could not place this", no label worth believing and no classes.
   *
   * <p>Spelled once, here, because three callers want exactly this shape — {@link
   * MusicBrainzIdentity#identitiesFor}'s default, {@link #describing} when a row cannot be read,
   * and every test double standing in for a bridge that only maps identifiers. {@code
   * MusicBrainzSourceAdapter}'s guard omits it and the caller's fetch happens as it always did.
   */
  public static BridgedIdentity undescribed(String qid) {
    return new BridgedIdentity(qid, NodeKind.CONCEPT, null, List.of());
  }

  /**
   * <b>The factory a producer building one of these from a row must use</b> (issue #163, fix round
   * 1). It answers {@link #undescribed} where the row's classes cannot be read, rather than
   * throwing the way the constructor does.
   *
   * <p><b>Why a producer may not simply construct one.</b> {@code MusicBrainzSourceAdapter} catches
   * {@link MusicBrainzIdentityUnavailableException} and nothing else, and {@code
   * SegueService.expandEntity} wraps {@code adapter.expand} in no {@code try} at all — so an {@code
   * IllegalArgumentException} out of the constructor, inside a real {@code identitiesFor}, would
   * abort a whole expansion across every adapter. That is the aborted-expansion failure GAP 9 and
   * issue #147 exist to prevent, merely relocated. Dropping is the answer {@link
   * MusicBrainzIdentity#qidsFor}'s javadoc already promises for a value this bridge cannot map, and
   * the answer the seam's default already gives for a malformed {@code qid}.
   *
   * <p><b>Why the whole identity, and not just the unreadable class.</b> An entity with three
   * classes of which one is unreadable is reported undescribed, exactly like an entity with one.
   * ADR 42 is the decision that the log keeps the raw classes so a kind can be re-derived offline,
   * and {@code TinkerGraphStore.upsertNode} is last-writer-wins on {@code instanceOf} — so a
   * silently shortened list would overwrite a complete one and then be re-derived from, wrongly and
   * with nothing marking it partial, at every future improvement to the whitelist. That is #143's
   * erasure in miniature, which is the one thing this whole change exists not to do. Undescribed
   * costs one {@code EntityResolver.fetch} instead, which is the fallback that already exists and
   * which reads the complete list from Wikidata directly.
   *
   * <p>The {@code qid} is <b>not</b> softened the same way: a caller has no identity to report
   * undescribed if it cannot name the entity, so it must drop the row before reaching here — see
   * {@link MusicBrainzIdentity#identitiesFor}, which does.
   */
  public static BridgedIdentity describing(
      String qid, NodeKind kind, String label, List<String> instanceOf) {
    for (String classQid : instanceOf) {
      if (!Qid.looksLikeAQid(classQid)) {
        return undescribed(qid);
      }
    }
    return new BridgedIdentity(qid, kind, label, instanceOf);
  }
}
