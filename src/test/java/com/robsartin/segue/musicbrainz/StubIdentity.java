package com.robsartin.segue.musicbrainz;

import com.robsartin.segue.domain.Qid;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * A hand-built {@link MusicBrainzIdentity} over a fixed MBID-to-QID mapping, for driving the
 * interface without a Wikidata-backed implementation — that implementation is Task 5's wiring, not
 * this test's concern.
 *
 * <p><b>This one maps identifiers and nothing else, deliberately still</b> (issue #163). It answers
 * {@link MusicBrainzIdentity#identitiesFor} with {@link BridgedIdentity#undescribed} rows: a QID
 * and no description, which is what a caller must treat as "fetch this one properly". That was the
 * seam's own default until {@code qidsFor} was retired and took the default that delegated to it;
 * the property that default guaranteed for every identifier-only bridge is now stated here, by the
 * double that stands for them, and asserted by {@code MusicBrainzIdentityTest}. A bridge that
 * <i>does</i> describe is {@link #describing}, beside it rather than instead of it.
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
   * <p>An MBID absent from the map is absent from the answer, exactly as {@link #of} leaves it: ADR
   * 22 clause 2 declining to reach a neighbour.
   */
  static MusicBrainzIdentity describing(Map<String, BridgedIdentity> mbidToIdentity) {
    return new DescribingIdentity(mbidToIdentity);
  }

  @Override
  public Optional<String> mbidFor(String qid) {
    return mbidToQid.entrySet().stream()
        .filter(entry -> entry.getValue().equals(qid))
        .map(Map.Entry::getKey)
        .findFirst();
  }

  @Override
  public Map<String, BridgedIdentity> identitiesFor(Collection<String> mbids) {
    Map<String, BridgedIdentity> resolved = new LinkedHashMap<>();
    for (String mbid : mbids) {
      String qid = mbidToQid.get(mbid);
      // Dropped rather than thrown, which is the producer's half of the seam's contract and the
      // line the retired default used to carry. A BridgedIdentity refuses to hold a non-QID (ADR
      // 58), so constructing one from a mapping the caller wrote would turn a malformed value into
      // an IllegalArgumentException out of MusicBrainzSourceAdapter.expand — and
      // SegueService.expandEntity wraps nothing, so one bad value would abort a whole expansion
      // across every adapter. That is what GAP 9 and issue #147 exist to prevent; identitiesFor's
      // javadoc promises the other answer, and this double owes callers the same promise the real
      // bridge makes.
      if (qid != null && Qid.looksLikeAQid(qid)) {
        resolved.put(mbid, BridgedIdentity.undescribed(qid));
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
