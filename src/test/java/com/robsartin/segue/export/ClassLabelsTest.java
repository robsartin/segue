package com.robsartin.segue.export;

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
    assertThat(ClassLabels.label("Q900901")).isEqualTo("Q900901");
    assertThat(ClassLabels.describe(List.of())).isEqualTo(ClassLabels.NO_CLASS);
  }
}
