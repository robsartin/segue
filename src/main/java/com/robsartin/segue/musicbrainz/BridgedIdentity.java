package com.robsartin.segue.musicbrainz;

import com.robsartin.segue.domain.NodeKind;
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
 * <p><b>The QID is checked for presence, not for allocatability.</b> {@code Qid.checkAllocatable}
 * is ADR 58's rule about <i>stand-in</i> identifiers — a stand-in must take a shape Wikidata cannot
 * allocate — so a record that demanded an allocatable QID would refuse every {@code Q09000xx} the
 * test suite is required to use, this file's own fixtures included. The shape of a bridged value is
 * already guarded where the values arrive: {@code WikidataMusicBrainzIdentity} drops a binding
 * whose item is not a QID, and {@code MusicBrainzSourceAdapter}'s GAP 9 guard drops one again on
 * the way to a {@code NodeRecord}. What is left for this constructor is that there is a QID at all.
 *
 * @param qid the Wikidata item this MBID bridges to, never blank
 * @param kind the kind those classes imply, never null — {@link NodeKind#CONCEPT} where they imply
 *     nothing, which is ADR 22's "we could not place this"
 * @param label the entity's own label, or <b>null</b> where the bridge has none worth believing.
 *     Null is the undescribed answer and is not the same as a blank one: {@code SegueService} falls
 *     back to a real fetch for it, which is the behaviour that already exists.
 * @param instanceOf the {@code P31} classes, possibly empty, never null
 */
public record BridgedIdentity(String qid, NodeKind kind, String label, List<String> instanceOf) {

  public BridgedIdentity {
    if (qid == null || qid.isBlank()) {
      throw new IllegalArgumentException("a bridged identity needs a qid, got: " + qid);
    }
    Objects.requireNonNull(kind, "kind");
    instanceOf = List.copyOf(Objects.requireNonNull(instanceOf, "instanceOf"));
  }
}
