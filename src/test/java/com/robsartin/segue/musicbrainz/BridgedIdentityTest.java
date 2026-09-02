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
}
