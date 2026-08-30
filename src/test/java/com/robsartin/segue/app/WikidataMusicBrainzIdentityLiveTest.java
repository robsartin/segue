package com.robsartin.segue.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.segue.musicbrainz.MusicBrainzIdentity;
import com.robsartin.segue.wikidata.WikidataClient;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The positive control for the MBID bridge, and it needs one more than most of this repository's
 * fixture-backed tests: every response {@code WikidataMusicBrainzIdentityTest} replays was written
 * by hand from a probe, so a Query Service that changed its binding shape, or a P434 that stopped
 * meaning what it means, would leave that whole file passing against nothing.
 *
 * <p>Tagged {@code live} and excluded from {@code ./gradlew check}; {@code liveTest} includes every
 * {@code live}-tagged test, so this joins it with no build change. Run it deliberately: {@code
 * ./gradlew liveTest}.
 *
 * <p>The MBIDs are the ones the committed MusicBrainz fixture already names, and the round trip is
 * asserted rather than the QIDs: {@code mbidFor(qidsFor(mbid))} must come back to the MBID it
 * started from. Pinning the QID would make this test fail the day Wikidata merges two items, which
 * is a change in the world rather than a break in this code — and it would put a real identifier in
 * an assertion for no gain, since the property under test is that the two directions agree.
 */
@Tag("live")
class WikidataMusicBrainzIdentityLiveTest {

  /** Quintette du Hot Club de France, and one artist related to it. */
  private static final String ENSEMBLE_MBID = "ee55e4e8-807d-49b1-8470-d1c0898ed7cb";

  private static final String MEMBER_MBID = "9afdcb99-d4ba-41b4-b4b2-467a712bd9fa";

  /** Shaped like an MBID and not one, so Wikidata can only answer by omitting it. */
  private static final String UNKNOWN_MBID = "11111111-1111-1111-1111-111111111111";

  private final MusicBrainzIdentity identity =
      new WikidataMusicBrainzIdentity(WikidataClient.queryService());

  @Test
  @DisplayName("should agree in both directions when P434 really bridges the two identifiers")
  void shouldAgreeInBothDirectionsWhenP434ReallyBridgesTheTwoIdentifiers() {
    Map<String, String> resolved = identity.qidsFor(List.of(ENSEMBLE_MBID, MEMBER_MBID));

    assertThat(resolved).containsOnlyKeys(ENSEMBLE_MBID, MEMBER_MBID);
    assertThat(identity.mbidFor(resolved.get(ENSEMBLE_MBID))).contains(ENSEMBLE_MBID);
    assertThat(identity.mbidFor(resolved.get(MEMBER_MBID))).contains(MEMBER_MBID);
  }

  @Test
  @DisplayName("should drop an MBID the real Wikidata does not know")
  void shouldDropAnMbidTheRealWikidataDoesNotKnow() {
    // The drop that MusicBrainzIdentity's javadoc measures at 49% of artist-relation neighbours,
    // against the service that actually does the dropping rather than against a stub told to.
    Map<String, String> resolved = identity.qidsFor(List.of(ENSEMBLE_MBID, UNKNOWN_MBID));

    assertThat(resolved).containsOnlyKeys(ENSEMBLE_MBID);
  }
}
