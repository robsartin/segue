package com.robsartin.segue.musicbrainz;

import com.robsartin.segue.domain.NodeKind;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The bridge from a MusicBrainz MBID to the Wikidata QID that {@code NodeRecord} requires, and
 * back.
 *
 * <p><b>Declared here, in {@code musicbrainz}, and implemented elsewhere.</b> {@code NodeRecord} is
 * {@code (qid, kind, label, instanceOf)} — it carries no MBID — so an adapter handed a seed QID
 * must obtain the MBID through some seam, and an adapter that discovers a neighbour by MBID must
 * resolve it back to a QID before it can become a {@code NodeRecord} at all (ADR 22 clause 2:
 * source-local identifiers, MBIDs named explicitly among them, resolve to a QID in the ingest layer
 * and never appear in the domain). Two routes can carry that mapping — MusicBrainz's own {@code
 * url-rels}, and Wikidata's {@code P434} — and this package deliberately names neither: either one
 * has to be <i>supplied</i> from outside without this package importing another adapter, or the
 * second adapter is welded to the first (ADR 25; ADR 32's adapters-are-siblings fences). So {@code
 * musicbrainz} declares the interface it needs and something outside supplies the implementation.
 * What shipped is Wikidata-backed, through {@code P434}, in {@code app} — see {@code
 * docs/design/2026-08-30-three-source-adapters.md}'s "Identity, and the bridge to a QID" section
 * and its 2026-08-30 correction, which records why that superseded the {@code url-rels} route the
 * note originally recommended, and what it cost. This type is the seam, not the bridge, and nothing
 * here is entitled to assume which one is behind it.
 *
 * <p>{@link #qidsFor} is batched rather than one call per neighbour: the alternative is a round
 * trip per neighbour, and the measured neighbourhood was 387 across 40 seeds (#91's 2026-08-29
 * comment).
 *
 * <p><b>Both methods may fail, and say so</b> (<a
 * href="https://github.com/robsartin/segue/issues/148">issue #148</a>). {@link
 * MusicBrainzIdentityUnavailableException} is the one failure this seam declares, and declaring it
 * is the whole point: the empty answer is already spoken for twice over — an empty {@link #mbidFor}
 * means MusicBrainz holds no record bridged to that seed, and an MBID absent from {@link #qidsFor}
 * means ADR 22 clause 2 declining to reach that neighbour — so an implementation that degraded to
 * empty on failure was saying "nothing here" when it meant "I could not look". ADR 54 records what
 * that cost: a Query Service outage on the seed lookup read downstream as "this artist has no
 * members", with {@code ExpandResult.sourceUnavailable} false.
 *
 * <p>It is unchecked, like {@code WikidataUnavailableException} and {@link
 * MusicBrainzUnavailableException}, and declared in both signatures anyway so that an implementor
 * reads the channel off the interface rather than off this paragraph. {@code
 * MusicBrainzSourceAdapter} catches it at both call sites and turns it into {@code
 * ExpandResult.unavailable()}, so it never reaches the SPI — an implementation is not being asked
 * to break the "failures degrade rather than propagate" contract, it is being given the only way to
 * honour it.
 *
 * <p><b>An implementation may still answer empty, and should, when that is what it means.</b>
 * Throwing is for "I could not ask", not for "the answer was nothing" — the two readings this type
 * exists to keep apart.
 */
public interface MusicBrainzIdentity {

  /**
   * The MBID for a seed QID, or empty if this source has no bridge for it.
   *
   * <p><b>An implementation is not taken at its word.</b> {@code MusicBrainzSourceAdapter} checks
   * the shape of whatever comes back and reads anything that is not an MBID as the empty answer —
   * see its class note for what that guard protects and why it may not depend on knowing which
   * implementation is behind this seam (issue #147). Returning a malformed value is therefore a way
   * to lose an expansion silently, not a way to break one.
   *
   * @throws MusicBrainzIdentityUnavailableException if the bridge could not be asked at all, which
   *     is not the same answer as empty — see the type's Javadoc
   */
  Optional<String> mbidFor(String qid) throws MusicBrainzIdentityUnavailableException;

  /**
   * The QID for each MBID in {@code mbids} that this source can bridge. An MBID absent from the
   * result carries no QID — silently dropped, not reported as an error.
   *
   * <p>The values are not taken at their word either: a value that is not a QID is dropped by the
   * adapter's GAP 9 guard, for the reason that guard states.
   *
   * <p><b>That dropping is ADR 22 clause 2 working as designed, not a gap.</b> Measured over
   * artist-relation neighbours, 49% (190 of 387) carry no QID at all, and a 30-entity sample of
   * that 49% was tribute bands, pseudonyms, billing variants and relatives — material the identity
   * spine is declining to reach, on purpose, rather than failing to reach.
   *
   * @throws MusicBrainzIdentityUnavailableException if the bridge could not be asked at all. A
   *     partial map is not an option here for the reason the dropping above states: half an answer
   *     is indistinguishable from the normal drop path, so an implementation that cannot answer for
   *     every MBID it was handed throws rather than returning what it managed.
   */
  Map<String, String> qidsFor(Collection<String> mbids)
      throws MusicBrainzIdentityUnavailableException;

  /**
   * The same question as {@link #qidsFor}, answered with whatever description the bridge could see
   * on the round trip it was already making (issue #163).
   *
   * <p><b>Same keys, same drops, same failure.</b> An MBID absent from the result carries no QID,
   * for the reason {@link #qidsFor} states; a bridge that could not be asked throws rather than
   * answering short.
   *
   * <p><b>The default describes nothing, and that is the honest answer.</b> An implementation that
   * only maps identifiers is not being asked to invent a label or a class: it delegates to {@link
   * #qidsFor} and hands back {@link NodeKind#CONCEPT} with a null label and no classes, which is
   * exactly what a caller must treat as "fetch this one properly". Five of this seam's six
   * implementors are test doubles that do precisely that, and this default is why widening the seam
   * did not have to touch them.
   *
   * @throws MusicBrainzIdentityUnavailableException if the bridge could not be asked at all
   */
  default Map<String, BridgedIdentity> identitiesFor(Collection<String> mbids)
      throws MusicBrainzIdentityUnavailableException {
    Map<String, BridgedIdentity> bridged = new LinkedHashMap<>();
    qidsFor(mbids)
        .forEach(
            (mbid, qid) ->
                bridged.put(mbid, new BridgedIdentity(qid, NodeKind.CONCEPT, null, List.of())));
    return Map.copyOf(bridged);
  }
}
