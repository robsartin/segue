package com.robsartin.segue.musicbrainz;

import com.robsartin.segue.domain.NodeKind;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A hand-built {@link MusicBrainzIdentity} over a fixed MBID-to-QID mapping, for driving the
 * interface without a Wikidata-backed implementation — that implementation is Task 5's wiring, not
 * this test's concern.
 *
 * <p><b>This one maps identifiers and nothing else, deliberately still</b> (issue #163). It does
 * not override {@link MusicBrainzIdentity#identitiesFor}, so it answers that question through the
 * seam's own default — which is what {@code MusicBrainzIdentityTest} asserts about, and what makes
 * this double stand for every implementor the widening did not have to touch. Teaching it to
 * describe would leave that default with nothing exercising it, so a bridge that <i>does</i>
 * describe is {@link #describing}, beside it rather than instead of it.
 */
final class StubIdentity implements MusicBrainzIdentity {

  private final Map<String, String> mbidToQid;

  private StubIdentity(Map<String, String> mbidToQid) {
    this.mbidToQid = Map.copyOf(mbidToQid);
  }

  static StubIdentity of(Map<String, String> mbidToQid) {
    return new StubIdentity(mbidToQid);
  }

  /**
   * A bridge that answers with the {@link BridgedIdentity} each MBID is mapped to, described or not
   * — the shape {@code WikidataMusicBrainzIdentity} has since issue #163, and the only way to drive
   * {@code MusicBrainzSourceAdapter}'s neighbour guard from both sides.
   *
   * <p>An MBID absent from the map is absent from the answer, exactly as {@link #qidsFor} leaves
   * it: ADR 22 clause 2 declining to reach a neighbour.
   */
  static MusicBrainzIdentity describing(Map<String, BridgedIdentity> mbidToIdentity) {
    return new DescribingIdentity(mbidToIdentity);
  }

  /**
   * The answer for a neighbour a bridge resolved but could not describe: a QID, {@link
   * NodeKind#CONCEPT} for "we could not place this" (ADR 22), no label worth believing and no
   * classes. Identical to what the seam's default produces, spelled once so a test that needs 22 of
   * them does not spell it 22 times.
   */
  static BridgedIdentity undescribed(String qid) {
    return new BridgedIdentity(qid, NodeKind.CONCEPT, null, List.of());
  }

  @Override
  public Optional<String> mbidFor(String qid) {
    return mbidToQid.entrySet().stream()
        .filter(entry -> entry.getValue().equals(qid))
        .map(Map.Entry::getKey)
        .findFirst();
  }

  @Override
  public Map<String, String> qidsFor(Collection<String> mbids) {
    Map<String, String> resolved = new LinkedHashMap<>();
    for (String mbid : mbids) {
      String qid = mbidToQid.get(mbid);
      if (qid != null) {
        resolved.put(mbid, qid);
      }
    }
    return Map.copyOf(resolved);
  }

  /** See {@link StubIdentity#describing}. */
  private static final class DescribingIdentity implements MusicBrainzIdentity {

    private final Map<String, BridgedIdentity> mbidToIdentity;

    private DescribingIdentity(Map<String, BridgedIdentity> mbidToIdentity) {
      this.mbidToIdentity = Map.copyOf(mbidToIdentity);
    }

    @Override
    public Optional<String> mbidFor(String qid) {
      return mbidToIdentity.entrySet().stream()
          .filter(entry -> entry.getValue().qid().equals(qid))
          .map(Map.Entry::getKey)
          .findFirst();
    }

    @Override
    public Map<String, String> qidsFor(Collection<String> mbids) {
      Map<String, String> resolved = new LinkedHashMap<>();
      identitiesFor(mbids).forEach((mbid, identity) -> resolved.put(mbid, identity.qid()));
      return Map.copyOf(resolved);
    }

    @Override
    public Map<String, BridgedIdentity> identitiesFor(Collection<String> mbids) {
      Map<String, BridgedIdentity> resolved = new LinkedHashMap<>();
      for (String mbid : mbids) {
        BridgedIdentity identity = mbidToIdentity.get(mbid);
        if (identity != null) {
          resolved.put(mbid, identity);
        }
      }
      return Map.copyOf(resolved);
    }
  }
}
