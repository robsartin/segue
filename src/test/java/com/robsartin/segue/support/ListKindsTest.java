package com.robsartin.segue.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.segue.domain.NodeKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The list-kind table, which is the authority on which kinds a list may use and what each one may
 * turn out to be. The kinds named here are named because a test that asserts the table against
 * itself asserts nothing; the table is the thing under test.
 */
class ListKindsTest {

  @Test
  @DisplayName("should register every kind the seed tool's lists use when the table is read")
  void shouldRegisterEveryListKindWhenTheTableIsRead() {
    assertThat(ListKinds.registered())
        .contains(
            "musician",
            "composer",
            "conductor",
            "comedian",
            "author",
            "actor",
            "director",
            "broadcaster",
            "a-cappella",
            "tribute",
            "orchestra",
            "choir",
            "ensemble",
            "org",
            "tv-show",
            "film",
            "book",
            "character",
            "public-figure",
            "puppeteer");
  }

  @Test
  @DisplayName("should fold to one node kind when the list kind names exactly one")
  void shouldFoldToOneNodeKindWhenTheListKindNamesExactlyOne() {
    assertThat(ListKinds.nodeKinds("author")).containsExactly(NodeKind.PERSON);
    assertThat(ListKinds.nodeKinds("orchestra")).containsExactly(NodeKind.GROUP);
    assertThat(ListKinds.nodeKinds("book")).containsExactly(NodeKind.WORK);
    assertThat(ListKinds.nodeKinds("character")).containsExactly(NodeKind.CONCEPT);
  }

  @Test
  @DisplayName("should name both node kinds when the list kind is a musician or a comedian")
  void shouldNameBothNodeKindsWhenTheListKindIsAMusicianOrAComedian() {
    assertThat(ListKinds.nodeKinds("musician"))
        .containsExactlyInAnyOrder(NodeKind.PERSON, NodeKind.GROUP);
    assertThat(ListKinds.nodeKinds("comedian"))
        .containsExactlyInAnyOrder(NodeKind.PERSON, NodeKind.GROUP);
  }

  @Test
  @DisplayName(
      "should name exactly two kinds when the kinds that fold to more than one are asked for")
  void shouldNameExactlyTwoKindsWhenTheOnesFoldingToMoreThanOneAreAskedFor() {
    assertThat(ListKinds.registered())
        .filteredOn(kind -> ListKinds.nodeKinds(kind).size() > 1)
        .containsExactlyInAnyOrder("musician", "comedian");
  }

  @Test
  @DisplayName("should fold to nothing when the list kind is not registered")
  void shouldFoldToNothingWhenTheListKindIsNotRegistered() {
    assertThat(ListKinds.nodeKinds("luthier")).isEmpty();
  }

  @Test
  @DisplayName("should ignore case and surrounding space when a list kind is looked up")
  void shouldIgnoreCaseAndSpaceWhenAListKindIsLookedUp() {
    assertThat(ListKinds.nodeKinds("  Musician ")).isEqualTo(ListKinds.nodeKinds("musician"));
  }
}
