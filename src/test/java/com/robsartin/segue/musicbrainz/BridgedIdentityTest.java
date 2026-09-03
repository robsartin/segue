package com.robsartin.segue.musicbrainz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.robsartin.segue.domain.NodeKind;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The value the identity seam hands back once it carries more than a QID (issue #163).
 *
 * <p>The QIDs below are the committed fixture's unallocatable stand-ins — a leading zero, which
 * Wikidata's item-id grammar refuses (ADR 58) — so nothing here ties a shape to a real entity.
 */
class BridgedIdentityTest {

  @Test
  @DisplayName("should keep its own classes when the list it was built from is later mutated")
  void shouldKeepItsOwnClassesWhenTheListItWasBuiltFromIsLaterMutated() {
    List<String> classes = new ArrayList<>(List.of("Q5"));

    BridgedIdentity bridged = new BridgedIdentity("Q0900002", NodeKind.PERSON, "A Player", classes);
    classes.add("Q215380");

    assertThat(bridged.instanceOf()).containsExactly("Q5");
  }

  @Test
  @DisplayName("should refuse a blank QID when there is no identity to bridge to")
  void shouldRefuseABlankQidWhenThereIsNoIdentityToBridgeTo() {
    assertThatThrownBy(() -> new BridgedIdentity("  ", NodeKind.CONCEPT, null, List.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("should refuse a bare Q with no number when it names no item at all")
  void shouldRefuseABareQWithNoNumberWhenItNamesNoItemAtAll() {
    assertThatThrownBy(() -> new BridgedIdentity("Q", NodeKind.CONCEPT, null, List.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("qid must look like Q12345, got: Q");
  }

  @Test
  @DisplayName("should refuse a value that is not a QID when it is some other identifier")
  void shouldRefuseAValueThatIsNotAQidWhenItIsSomeOtherIdentifier() {
    // Non-blank was the whole invariant until fix round 1, so "x1" — an MBID fragment, a lexeme
    // id, anything at all — built a BridgedIdentity that Task 3 would have trusted.
    assertThatThrownBy(() -> new BridgedIdentity("x1", NodeKind.CONCEPT, null, List.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("qid must look like Q12345, got: x1");
  }

  @Test
  @DisplayName("should accept an unallocatable stand-in QID when the fixtures use one")
  void shouldAcceptAnUnallocatableStandInQidWhenTheFixturesUseOne() {
    // The other half of the rule, and the reason it is Qid.check rather than checkAllocatable:
    // ADR 58 REQUIRES a stand-in to take a shape Wikidata cannot allocate, and Q\d+ is exactly
    // the two shapes it allows — allocatable, or a leading-zero stand-in.
    assertThat(new BridgedIdentity("Q0900002", NodeKind.PERSON, "A Player", List.of()).qid())
        .isEqualTo("Q0900002");
  }

  @Test
  @DisplayName("should read a blank label as undescribed when the bridge had nothing to say")
  void shouldReadABlankLabelAsUndescribedWhenTheBridgeHadNothingToSay() {
    // One way to say undescribed, not two: Task 3's guard is `label != null` and nothing more.
    BridgedIdentity bridged = new BridgedIdentity("Q0900002", NodeKind.PERSON, "   ", List.of());

    assertThat(bridged.label()).isNull();
  }

  /**
   * The other half of ADR 58's rule, and the half this record was missing (issue #163, fix round
   * 1). {@code NodeRecord} refuses a class id that is not a QID — but it refuses it inside {@code
   * IngestService.apply}, which runs <b>after</b> the claim has been appended, so the log is left
   * holding a row that {@code GraphProjector} re-throws on at every boot. This record is what the
   * adapter trusts, so this is where the shape has to be established.
   */
  @Test
  @DisplayName("should refuse a class id that is not a QID when the log could not replay it")
  void shouldRefuseAClassIdThatIsNotAQidWhenTheLogCouldNotReplayIt() {
    assertThatThrownBy(
            () ->
                new BridgedIdentity(
                    "Q0900002", NodeKind.PERSON, "A Player", List.of("Q5", "human")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("qid must look like Q12345, got: human");
  }

  /**
   * And why a throwing constructor is only half the fix. {@code MusicBrainzSourceAdapter} catches
   * {@code MusicBrainzIdentityUnavailableException} and nothing else, so a producer that built one
   * of these from an unread row would send the same aborted expansion through a different door. A
   * producer calls this instead, and it drops.
   *
   * <p><b>The whole identity is reported undescribed, not merely the unreadable class omitted.</b>
   * ADR 42 keeps the raw classes so a kind can be re-derived offline later, and {@code
   * TinkerGraphStore.upsertNode} is last-writer-wins on {@code instanceOf} — so a silently
   * shortened list would overwrite a complete one and then be re-derived from, confidently and
   * wrongly, forever, with nothing marking it as partial. That is #143's erasure in miniature.
   * Undescribed costs one {@code EntityResolver.fetch}, which is the fallback that already exists
   * and which returns the complete list from Wikidata directly.
   */
  @Test
  @DisplayName(
      "should report the whole identity undescribed when one of its class ids is not a QID")
  void shouldReportTheWholeIdentityUndescribedWhenOneOfItsClassIdsIsNotAQid() {
    BridgedIdentity bridged =
        BridgedIdentity.describing("Q0900002", NodeKind.PERSON, "A Player", List.of("Q5", "human"));

    assertThat(bridged).isEqualTo(BridgedIdentity.undescribed("Q0900002"));
    assertThat(bridged.instanceOf()).isEmpty();
    assertThat(bridged.label()).isNull();
    assertThat(bridged.kind()).isEqualTo(NodeKind.CONCEPT);
  }

  @Test
  @DisplayName("should keep every class when the bridge read all of them")
  void shouldKeepEveryClassWhenTheBridgeReadAllOfThem() {
    BridgedIdentity bridged =
        BridgedIdentity.describing(
            "Q0900002", NodeKind.PERSON, "A Player", List.of("Q5", "Q215380"));

    assertThat(bridged.instanceOf()).containsExactly("Q5", "Q215380");
    assertThat(bridged.label()).isEqualTo("A Player");
    assertThat(bridged.kind()).isEqualTo(NodeKind.PERSON);
  }
}
