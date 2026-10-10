package com.robsartin.segue.signals;

import com.robsartin.segue.support.CsvFile;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The export step's file, read, and the names list the seed tool wants, written (#350).
 *
 * <p>{@code artist,play_count,loved,favorited} is the whole shape, one row per artist. <b>None of
 * these files is in this repository, and none may be</b>: a play count per artist is the owner's
 * listening history, which ADR 33 governs and issue #37 keeps out of a public repository. {@code
 * *.csv} is gitignored beside {@code *.db}.
 */
final class SignalsFile {

  static final String HEADER = "artist,play_count,loved,favorited";

  private SignalsFile() {}

  /** Every row of the export. Refuses a file that does not open with the header. */
  static List<SignalRow> read(Path path) {
    List<List<String>> lines = CsvFile.read(path);
    if (lines.isEmpty() || !String.join(",", lines.get(0)).equalsIgnoreCase(HEADER)) {
      throw new IllegalArgumentException(path.getFileName() + " does not start with " + HEADER);
    }
    List<SignalRow> rows = new ArrayList<>();
    for (List<String> fields : lines.subList(1, lines.size())) {
      if (fields.size() < 4) {
        // A count, never the line: the row is the owner's listening.
        throw new IllegalArgumentException(
            path.getFileName() + " has a row with fewer than four fields");
      }
      rows.add(
          new SignalRow(
              fields.get(0).strip(),
              count(fields.get(1), path),
              flag(fields.get(2), path),
              flag(fields.get(3), path)));
    }
    return List.copyOf(rows);
  }

  /**
   * The {@code name,kind,status} list {@code resolveNames} reads, one line per artist. The kind is
   * {@code musician}, the seed tool's own word for it, and the status says only where the name came
   * from.
   */
  static void writeNames(List<SignalRow> rows, Path out) {
    StringBuilder text = new StringBuilder("name,kind,status").append(System.lineSeparator());
    for (SignalRow row : rows) {
      text.append(String.join(",", CsvFile.quote(row.artist()), "musician", "LISTENED"))
          .append(System.lineSeparator());
    }
    try {
      Files.writeString(out, text, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("could not write " + out, e);
    }
  }

  private static long count(String field, Path path) {
    try {
      return Long.parseLong(field.strip());
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException(
          path.getFileName() + " has a play_count that is not a whole number");
    }
  }

  private static boolean flag(String field, Path path) {
    return switch (field.strip().toLowerCase(Locale.ROOT)) {
      case "1", "true", "yes" -> true;
      case "0", "false", "no", "" -> false;
      default ->
          throw new IllegalArgumentException(
              path.getFileName() + " has a loved or favorited value that is not true or false");
    };
  }
}
