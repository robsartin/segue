package com.robsartin.segue.signals;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Invented artists and counts only (ADR 33, issue #37). */
class SignalsFileTest {

  @TempDir private Path dir;

  private Path write(String content) throws IOException {
    Path file = dir.resolve("music.csv");
    Files.writeString(file, content);
    return file;
  }

  @Test
  @DisplayName("rows are read with their counts and flags")
  void shouldReadRows() throws IOException {
    Path file =
        write(
            "artist,play_count,loved,favorited\n"
                + "The Invented Quartet,40,true,false\n"
                + "\"Comma, The Invented\",3,0,1\n");

    assertThat(SignalsFile.read(file))
        .containsExactly(
            new SignalRow("The Invented Quartet", 40, true, false),
            new SignalRow("Comma, The Invented", 3, false, true));
  }

  @Test
  @DisplayName("a file without the header is refused")
  void shouldRefuseAFileWithoutTheHeader() throws IOException {
    Path file = write("name,kind,status\nsomeone,musician,x\n");

    assertThatThrownBy(() -> SignalsFile.read(file))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("artist,play_count,loved,favorited");
  }

  @Test
  @DisplayName("a play count that is not a number is refused without quoting the line")
  void shouldRefuseABadCount() throws IOException {
    Path file = write("artist,play_count,loved,favorited\nThe Invented Quartet,lots,false,false\n");

    assertThatThrownBy(() -> SignalsFile.read(file))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("play_count")
        .hasMessageNotContaining("Invented");
  }

  @Test
  @DisplayName("the names list is the shape resolveNames reads")
  void shouldWriteTheNamesList() throws IOException {
    Path out = dir.resolve("names.csv");

    SignalsFile.writeNames(List.of(new SignalRow("Comma, The Invented", 3, false, false)), out);

    assertThat(Files.readAllLines(out))
        .containsExactly("name,kind,status", "\"Comma, The Invented\",musician,LISTENED");
  }
}
