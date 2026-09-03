package com.robsartin.segue.musicbrainz;

import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.segue.domain.NodeKind;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MusicBrainzIdentityTest {

  /**
   * What an implementor that only maps identifiers answers, now that the seam has one method to
   * answer it with (issue #163).
   *
   * <p>This used to assert the seam's own {@code default identitiesFor}, which delegated to {@code
   * qidsFor} and could invent none of the other three fields. That default was the Mikado parallel
   * field: it existed so the widening did not have to touch every implementor at once, and it is
   * gone with {@code qidsFor}. The property it guaranteed is not — an identifier-only bridge still
   * answers a QID and nothing else — but it is now each implementor's to state, and {@link
   * StubIdentity} is the double that stands for all of them.
   */
  @Test
  @DisplayName("should bridge the QID and describe nothing when the implementor only maps QIDs")
  void shouldBridgeTheQidAndDescribeNothingWhenTheImplementorOnlyMapsQids() {
    MusicBrainzIdentity identity = StubIdentity.of(Map.of("mbid-known", "Q0900001"));

    Map<String, BridgedIdentity> bridged =
        identity.identitiesFor(List.of("mbid-known", "mbid-unknown"));

    // The absent MBID is absent: ADR 22 clause 2 declining to reach a neighbour, not an error and
    // not a key with nothing under it.
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
