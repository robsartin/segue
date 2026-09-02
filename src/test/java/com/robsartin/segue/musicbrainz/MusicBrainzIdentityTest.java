package com.robsartin.segue.musicbrainz;

import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.segue.domain.NodeKind;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The seam's own default, which is what makes {@code identitiesFor} arrive beside {@code qidsFor}
 * rather than instead of it (issue #163, the Mikado parallel field).
 *
 * <p>An implementor that has not been widened — five of the six the seam has — keeps compiling and
 * keeps answering, because the default asks it the only question it knows: {@link
 * MusicBrainzIdentity#qidsFor}. What it cannot do is invent the other three fields, and this class
 * is the assertion that it does not try.
 */
class MusicBrainzIdentityTest {

  private static final String MEMBER_MBID = "9afdcb99-d4ba-41b4-b4b2-467a712bd9fa";
  private static final String UNKNOWN_MBID = "11111111-1111-1111-1111-111111111111";
  private static final String MEMBER_QID = "Q0900002";

  @Test
  @DisplayName("should bridge the QID and describe nothing when the implementor only maps QIDs")
  void shouldBridgeTheQidAndDescribeNothingWhenTheImplementorOnlyMapsQids() {
    MusicBrainzIdentity identity = StubIdentity.of(Map.of(MEMBER_MBID, MEMBER_QID));

    Map<String, BridgedIdentity> bridged =
        identity.identitiesFor(List.of(MEMBER_MBID, UNKNOWN_MBID));

    // The absent MBID is absent, exactly as qidsFor leaves it: ADR 22 clause 2 declining to reach
    // a neighbour, not an error and not a key with nothing under it.
    assertThat(bridged).containsOnlyKeys(MEMBER_MBID);
    BridgedIdentity member = bridged.get(MEMBER_MBID);
    assertThat(member.qid()).isEqualTo(MEMBER_QID);
    assertThat(member.label()).isNull();
    assertThat(member.instanceOf()).isEmpty();
    // CONCEPT is ADR 22's "we could not place this", which is the honest answer from a bridge
    // that was never asked about classes — not a kind guessed from the MBID.
    assertThat(member.kind()).isEqualTo(NodeKind.CONCEPT);
  }
}
