package com.robsartin.segue.own;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The claims file, read and refused. The ids here are stand-ins with the project's leading zero and
 * an id the owner minted with two; no file in this class is anybody's real list.
 */
class ClaimFileTest {

  @TempDir Path dir;

  private Path file(String contents) throws Exception {
    Path path = dir.resolve("claims.csv");
    Files.writeString(path, contents);
    return path;
  }

  @Test
  @DisplayName("should read every edge when the file is well formed")
  void shouldReadEveryEdgeWhenTheFileIsWellFormed() throws Exception {
    Path path =
        file(
            "from,to,type\n"
                + "# the book's author, which nothing in the graph states\n"
                + "Q00903301,Q0903301,AUTHORED\n"
                + "\n"
                + "Q0903302,Q0903301,INFLUENCED_BY\n");

    List<ClaimFile.Row> rows = ClaimFile.read(path);

    assertThat(rows)
        .containsExactly(
            new ClaimFile.Row(3, "Q00903301", "Q0903301", "AUTHORED"),
            new ClaimFile.Row(5, "Q0903302", "Q0903301", "INFLUENCED_BY"));
  }

  @Test
  @DisplayName("should refuse naming the row when a row has fewer than three fields")
  void shouldRefuseNamingTheRowWhenARowHasFewerThanThreeFields() throws Exception {
    Path path = file("from,to,type\nQ0903301,Q0903302\n");

    assertThatIllegalArgumentException()
        .isThrownBy(() -> ClaimFile.read(path))
        .withMessageContaining("line 2")
        .withMessageContaining("three fields")
        .withMessageContaining("nothing was appended");
  }

  @Test
  @DisplayName("should refuse naming the row when an id is not qid-shaped")
  void shouldRefuseNamingTheRowWhenAnIdIsNotQidShaped() throws Exception {
    Path path = file("from,to,type\nthe-highwaymen,Q0903301,AUTHORED\n");

    assertThatIllegalArgumentException()
        .isThrownBy(() -> ClaimFile.read(path))
        .withMessageContaining("line 2")
        .withMessageContaining("the-highwaymen");
  }

  @Test
  @DisplayName("should refuse naming the row when the code is not in the vocabulary")
  void shouldRefuseNamingTheRowWhenTheCodeIsNotInTheVocabulary() throws Exception {
    Path path = file("from,to,type\nQ0903301,Q0903302,ADMIRES\n");

    assertThatIllegalArgumentException()
        .isThrownBy(() -> ClaimFile.read(path))
        .withMessageContaining("line 2")
        .withMessageContaining("no registered edge type for code: ADMIRES");
  }

  @Test
  @DisplayName("should refuse when the file does not start with the header")
  void shouldRefuseWhenTheFileDoesNotStartWithTheHeader() throws Exception {
    Path path = file("Q0903301,Q0903302,AUTHORED\n");

    assertThatIllegalArgumentException()
        .isThrownBy(() -> ClaimFile.read(path))
        .withMessageContaining("from,to,type");
  }

  @Test
  @DisplayName("should refuse when the file is not there")
  void shouldRefuseWhenTheFileIsNotThere() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> ClaimFile.read(dir.resolve("absent.csv")))
        .withMessageContaining("no claims file at");
  }
}
