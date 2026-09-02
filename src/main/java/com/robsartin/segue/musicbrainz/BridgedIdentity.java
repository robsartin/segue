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
 * MusicBrainzSourceAdapter}'s GAP 9 guard drops one on the way to a {@code NodeRecord}), but a
 * value type the adapter is about to trust should carry its own shape rather than borrow theirs.
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
  }
}
