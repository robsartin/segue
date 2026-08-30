package com.robsartin.segue.musicbrainz;

import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.segue.domain.AssertionRecord;
import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.domain.NodeRecord;
import com.robsartin.segue.port.ExpandContext;
import com.robsartin.segue.port.ExpandResult;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The positive control for this package, and the reason it exists is written on {@code
 * WikidataLiveSmokeTest}: everything else here replays a committed fixture, and a committed fixture
 * passes forever against an endpoint that has changed its response shape or gone away. CLAUDE.md
 * records the same lesson from the run that caught a wrong QID — a fixture asserts whatever its
 * author wrote.
 *
 * <p>Tagged {@code live} and excluded from {@code ./gradlew check}; {@code build.gradle.kts}'s
 * {@code liveTest} task includes every {@code live}-tagged test, so this joins it with no build
 * change. Run it deliberately: {@code ./gradlew liveTest}.
 *
 * <p><b>What this does NOT cover.</b> The entity below states no {@code begin} or {@code end} on
 * any of its relations, so nothing here exercises MusicBrainz's variable-precision dates against
 * real data — {@code MusicBrainzSourceAdapterTest} drives those from strings written by hand.
 * Asserting something about dates here would be a criterion that passes vacuously, which is worse
 * than a gap that is written down. Closing it needs a dated entity chosen under the same privacy
 * argument the fixture entity was chosen under, and that choice has not been made.
 */
@Tag("live")
class MusicBrainzLiveSmokeTest {

  /**
   * Quintette du Hot Club de France. The same entity the committed fixture was captured from, and
   * chosen for the reasons argued in {@code MusicBrainzClientTest}'s javadoc: a French ensemble
   * that stopped existing in 1948, so it cannot appear on any living person's concert history —
   * which is what CLAUDE.md says the known-list is. A reproducible API probe, never a statement
   * about taste (ADR 51).
   */
  private static final String HOT_CLUB_QUINTET = "ee55e4e8-807d-49b1-8470-d1c0898ed7cb";

  private static final String QUINTET_QID = "Q900001";
  private static final String MEMBER_QID = "Q900002";

  @Test
  @DisplayName("MusicBrainz still answers artist-rels in the shape this client parses")
  void stillAnswersInTheShapeThisClientParses() {
    List<ArtistRelation> relations = new MusicBrainzClient().artistRelations(HOT_CLUB_QUINTET);

    assertThat(relations).isNotEmpty();
    assertThat(relations).extracting(ArtistRelation::type).contains("member of band");
    // Direction is the field this adapter orients every edge by, so a response that stopped
    // carrying it — or started carrying a third value — must fail here rather than silently
    // become relations the adapter drops as unmappable.
    assertThat(relations)
        .extracting(ArtistRelation::direction)
        .allSatisfy(direction -> assertThat(direction).isIn("forward", "backward"));
    assertThat(relations).allSatisfy(r -> assertThat(r.targetMbid()).isNotBlank());
  }

  @Test
  @DisplayName("a live response expands to a membership edge oriented from the member")
  void expandsToAMembershipEdgeOrientedFromTheMember() {
    // End to end over the real endpoint, with only the bridge stubbed — that half is Task 5's and
    // has no live implementation yet. The orientation is the assertion that matters: MusicBrainz
    // states this group's roster "backward", and P463 runs from the member.
    MusicBrainzSourceAdapter adapter =
        new MusicBrainzSourceAdapter(
            new MusicBrainzClient(),
            StubIdentity.of(
                Map.of(
                    HOT_CLUB_QUINTET,
                    QUINTET_QID,
                    // Django Reinhardt, a member MusicBrainz has stated since the fixture was
                    // captured. Present in the committed fixture too, so the two agree or one of
                    // them is wrong.
                    "650bf385-6f6d-4992-a3b9-779d144920a4",
                    MEMBER_QID)),
            Clock.systemUTC());

    ExpandResult result =
        adapter.expand(
            new NodeRecord(QUINTET_QID, NodeKind.GROUP, "An Invented Ensemble", List.of()),
            new ExpandContext(200));

    assertThat(result.sourceUnavailable()).isFalse();
    assertThat(result.assertions()).hasSize(1);
    AssertionRecord membership = result.assertions().getFirst();
    assertThat(membership.fromQid()).isEqualTo(MEMBER_QID);
    assertThat(membership.toQid()).isEqualTo(QUINTET_QID);
    assertThat(membership.typeCode()).isEqualTo("MEMBER_OF");
    assertThat(membership.provenance().sourceId()).isEqualTo("musicbrainz");
  }
}
