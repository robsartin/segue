package com.robsartin.segue.musicbrainz;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * A hand-built {@link MusicBrainzIdentity} over a fixed MBID-to-QID mapping, for driving the
 * interface without a Wikidata-backed implementation — that implementation is Task 5's wiring, not
 * this test's concern.
 */
final class StubIdentity implements MusicBrainzIdentity {

  private final Map<String, String> mbidToQid;

  private StubIdentity(Map<String, String> mbidToQid) {
    this.mbidToQid = Map.copyOf(mbidToQid);
  }

  static StubIdentity of(Map<String, String> mbidToQid) {
    return new StubIdentity(mbidToQid);
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
}
