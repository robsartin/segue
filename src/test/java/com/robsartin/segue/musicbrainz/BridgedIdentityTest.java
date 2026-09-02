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
}
