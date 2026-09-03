package com.robsartin.segue.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Every name and QID below is invented (ADR 40, issue #37). */
class QidListTest {

  @TempDir Path dir;

  private Path file(String content) throws IOException {
    Path path = dir.resolve("list.csv");
    Files.writeString(path, content);
    return path;
  }

  @Test
  @DisplayName("a bare list is one QID per line")
  void readsABareList() throws IOException {
    assertThat(QidList.read(file("Q0900101\nQ0900102\n"))).containsExactly("Q0900101", "Q0900102");
  }

  @Test
  @DisplayName("the seeding tool's mapping file works unchanged: the QID is the field that is one")
  void readsTheSeedingToolsMappingFile() throws IOException {
    String mapping =
        """
        name,kind,status,qid,label,confidence,reason
        Wren Alderman,person,active,Q0900101,Wren Alderman,ACCEPTED,name and kind agree
        The Paper Kettles,group,active,Q0900102,The Paper Kettles,ACCEPTED,name and kind agree
        """;

    assertThat(QidList.read(file(mapping))).containsExactly("Q0900101", "Q0900102");
  }

  @Test
  @DisplayName("a review row has no QID of its own, so a QID quoted in its reason is not taken")
  void ignoresAQidMentionedInProse() throws IOException {
    String review =
        """
        name,kind,status,qid,label,confidence,reason
        Wren Alderman,person,active,Q0900101,Wren Alderman,ACCEPTED,name and kind agree
        Ida Marlow,person,active,,,REVIEW,"thin margin between Ida Marlow (Q0900104) and another"
        """;

    assertThat(QidList.read(file(review))).containsExactly("Q0900101");
  }

  @Test
  @DisplayName("blank lines and repeats collapse, so a list can be pasted together from two files")
  void deduplicatesAndSkipsBlanks() throws IOException {
    assertThat(QidList.read(file("Q0900101\n\nQ0900101\nQ0900102\n")))
        .containsExactly("Q0900101", "Q0900102");
  }

  @Test
  @DisplayName("a file with no QID at all is refused rather than exporting an empty picture")
  void refusesAFileWithNoQids() throws IOException {
    assertThatThrownBy(() -> QidList.read(file("name,kind,status\n")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("no QID");
  }

  @Test
  @DisplayName("a missing file is named, not a stack trace about a stream")
  void refusesAMissingFile() {
    assertThatThrownBy(() -> QidList.read(dir.resolve("absent.csv")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("absent.csv");
  }
}
