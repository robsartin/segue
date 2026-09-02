package com.robsartin.segue.musicbrainz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import com.robsartin.segue.domain.NodeKind;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MusicBrainzIdentityTest {

  @Test
  @DisplayName("should drop a neighbour with no QID when the mapping does not know it")
  void shouldDropANeighbourWithNoQidWhenTheMappingDoesNotKnowIt() {
    MusicBrainzIdentity identity = StubIdentity.of(Map.of("mbid-known", "Q0900001"));

    Map<String, String> resolved = identity.qidsFor(List.of("mbid-known", "mbid-unknown"));

    assertThat(resolved).containsExactly(entry("mbid-known", "Q0900001"));
  }

  /**
   * The seam's own default, which is what makes {@code identitiesFor} arrive beside {@code qidsFor}
   * rather than instead of it (issue #163, the Mikado parallel field).
   *
   * <p>An implementor that has not been widened — every one but {@code WikidataMusicBrainzIdentity}
   * — keeps compiling and keeps answering, because the default asks it the only question it knows:
   * {@link MusicBrainzIdentity#qidsFor}. What it cannot do is invent the other three fields, and
   * this is the assertion that it does not try.
   */
  @Test
  @DisplayName("should bridge the QID and describe nothing when the implementor only maps QIDs")
  void shouldBridgeTheQidAndDescribeNothingWhenTheImplementorOnlyMapsQids() {
    MusicBrainzIdentity identity = StubIdentity.of(Map.of("mbid-known", "Q0900001"));

    Map<String, BridgedIdentity> bridged =
        identity.identitiesFor(List.of("mbid-known", "mbid-unknown"));

    // The absent MBID is absent, exactly as qidsFor leaves it: ADR 22 clause 2 declining to reach
    // a neighbour, not an error and not a key with nothing under it.
    assertThat(bridged).containsOnlyKeys("mbid-known");
    BridgedIdentity known = bridged.get("mbid-known");
    assertThat(known.qid()).isEqualTo("Q0900001");
    assertThat(known.label()).isNull();
    assertThat(known.instanceOf()).isEmpty();
    // CONCEPT is ADR 22's "we could not place this", which is the honest answer from a bridge that
    // was never asked about classes — not a kind guessed from the MBID.
    assertThat(known.kind()).isEqualTo(NodeKind.CONCEPT);
  }
}
