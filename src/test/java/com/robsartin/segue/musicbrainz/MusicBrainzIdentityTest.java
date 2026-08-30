package com.robsartin.segue.musicbrainz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MusicBrainzIdentityTest {

  @Test
  @DisplayName("should drop a neighbour with no QID when the mapping does not know it")
  void shouldDropANeighbourWithNoQidWhenTheMappingDoesNotKnowIt() {
    MusicBrainzIdentity identity = StubIdentity.of(Map.of("mbid-known", "Q900001"));

    Map<String, String> resolved = identity.qidsFor(List.of("mbid-known", "mbid-unknown"));

    assertThat(resolved).containsExactly(entry("mbid-known", "Q900001"));
  }
}
