package com.robsartin.segue.census;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The one place the known-list file becomes a basename and a list of ids.
 *
 * <p>The basename is the whole of what the report may say about the file: a path names a directory
 * on the owner's machine, and this output is meant to be pasted (ADR 51, ADR 63).
 */
class KnownListInputTest {

  @TempDir private Path home;

  @Test
  @DisplayName("the input carries the file's basename and not a single component of its path")
  void shouldCarryTheBasenameWhenTheFileIsRead() throws Exception {
    Path directory = Files.createDirectories(home.resolve("a-private-directory"));
    Path file = Files.writeString(directory.resolve("known.csv"), "Q0901001\nQ0901002\n");

    KnownListInput input = KnownListInput.read(file);

    assertThat(input.name()).isEqualTo("known.csv");
    assertThat(input.name()).doesNotContain("a-private-directory");
    assertThat(input.qids()).containsExactly("Q0901001", "Q0901002");
  }

  @Test
  @DisplayName("a file that is not there is refused by name rather than counted as empty")
  void shouldRefuseWhenTheFileIsNotThere() {
    assertThatThrownBy(() -> KnownListInput.read(home.resolve("absent.csv")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("no entity list at");
  }

  @Test
  @DisplayName("a directory with no basename at all is refused by name rather than by a null")
  void shouldRefuseByNameWhenThePathIsARootDirectory() {
    // A root has no file name component, so reading the basename first died on a null with
    // nothing said. The ids are read first now, and the reader refuses the path in its own words.
    assertThatThrownBy(() -> KnownListInput.read(Path.of("/")))
        .isInstanceOf(UncheckedIOException.class)
        .hasMessageContaining("could not read /");
  }
}
