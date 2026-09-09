package com.robsartin.segue.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The offline class-name table's shape. A malformed key here fails silently — the lookup simply
 * never matches, and the tooltip falls back to the QID it was already going to show — so the two
 * mistakes a hand-maintained table actually suffers are worth a test.
 */
class ClassLabelsTest {

  @Test
  @DisplayName("every key is a QID and every name is a name, or a lookup silently never matches")
  void isAWellFormedTable() {
    for (Map.Entry<String, String> entry : ClassLabels.all().entrySet()) {
      assertThat(entry.getKey()).as("class id").matches("Q\\d+");
      assertThat(entry.getValue()).as("name of %s", entry.getKey()).isNotBlank();
      assertThat(entry.getValue()).as("name of %s", entry.getKey()).isNotEqualTo(entry.getKey());
    }
  }

  @Test
  @DisplayName("no two classes share a name, which is what a copy-paste into the table looks like")
  void namesEachClassOnce() {
    assertThat(ClassLabels.all().values().stream().distinct().count())
        .isEqualTo(ClassLabels.all().size());
  }

  @Test
  @DisplayName("a class the table has never heard of is named by its QID, not guessed at")
  void fallsBackToTheQid() {
    assertThat(ClassLabels.label("Q0900901")).isEqualTo("Q0900901");
    assertThat(ClassLabels.describe(List.of())).isEqualTo(ClassLabels.NO_CLASS);
  }

  @Test
  @DisplayName("animated short film has a label, confirmed live 2026-09-05 (issue #261)")
  void shouldLabelAnimatedShortFilmWhenTheClassIsQ17517379() {
    assertThat(ClassLabels.label("Q17517379")).isEqualTo("animated short film");
  }

  @Test
  @DisplayName("comic book issue has a label, confirmed live 2026-09-05 (issue #265)")
  void shouldLabelComicBookIssueWhenTheClassIsQ140727568() {
    assertThat(ClassLabels.label("Q140727568")).isEqualTo("comic book issue");
  }

  @Test
  @DisplayName("video game has a label, confirmed live 2026-09-05 (issue #265)")
  void shouldLabelVideoGameWhenTheClassIsQ7889() {
    assertThat(ClassLabels.label("Q7889")).isEqualTo("video game");
  }

  @Test
  @DisplayName("film character has a label, confirmed live 2026-09-05 (issue #265)")
  void shouldLabelFilmCharacterWhenTheClassIsQ15773347() {
    assertThat(ClassLabels.label("Q15773347")).isEqualTo("film character");
  }

  @Test
  @DisplayName("television character has a label, confirmed live 2026-09-05 (issue #265)")
  void shouldLabelTelevisionCharacterWhenTheClassIsQ15773317() {
    assertThat(ClassLabels.label("Q15773317")).isEqualTo("television character");
  }

  @Test
  @DisplayName("animated character has a label, confirmed live 2026-09-05 (issue #265)")
  void shouldLabelAnimatedCharacterWhenTheClassIsQ15711870() {
    assertThat(ClassLabels.label("Q15711870")).isEqualTo("animated character");
  }

  @Test
  @DisplayName("Wikimedia artist discography has a label, confirmed live 2026-09-08 (issue #294)")
  void shouldLabelWikimediaArtistDiscographyWhenTheClassIsQ104635718() {
    assertThat(ClassLabels.label("Q104635718")).isEqualTo("Wikimedia artist discography");
  }

  @Test
  @DisplayName("encyclopedia article has a label, confirmed live 2026-09-08 (issue #294)")
  void shouldLabelEncyclopediaArticleWhenTheClassIsQ13433827() {
    assertThat(ClassLabels.label("Q13433827")).isEqualTo("encyclopedia article");
  }

  @Test
  @DisplayName("biographical article has a label, confirmed live 2026-09-08 (issue #300)")
  void shouldLabelBiographicalArticleWhenTheClassIsQ19389637() {
    assertThat(ClassLabels.label("Q19389637")).isEqualTo("biographical article");
  }
}
