package com.robsartin.segue.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * RFC 4180, in both directions, for the two readers that share it.
 *
 * <p><b>Its own class because two packages read the same rule</b> (#342). {@code
 * seed.SeedFiles.readList} reads the input list and {@link ResolutionFiles#readRows} reads the
 * seven-column mapping and review shape; a name with a comma in it is quoted in both, so a second
 * copy of this parser is a second place for that to be got wrong. The house rule is that the second
 * copy of a rule is the one a future editor misses.
 */
public final class CsvFile {

  private CsvFile() {}

  /** Every non-blank line of a CSV file, split into fields. An absent file reads as empty. */
  public static List<List<String>> read(Path path) {
    Objects.requireNonNull(path, "path");
    if (!Files.exists(path)) {
      return List.of();
    }
    String content;
    try {
      content = Files.readString(path, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("could not read " + path, e);
    }
    List<List<String>> rows = new ArrayList<>();
    List<String> fields = new ArrayList<>();
    StringBuilder field = new StringBuilder();
    boolean quoted = false;
    for (int i = 0; i < content.length(); i++) {
      char c = content.charAt(i);
      if (quoted) {
        if (c != '"') {
          field.append(c);
        } else if (i + 1 < content.length() && content.charAt(i + 1) == '"') {
          field.append('"');
          i++;
        } else {
          quoted = false;
        }
      } else if (c == '"') {
        quoted = true;
      } else if (c == ',') {
        fields.add(field.toString());
        field.setLength(0);
      } else if (c == '\n') {
        fields.add(field.toString().stripTrailing());
        field.setLength(0);
        addRow(rows, fields);
        fields = new ArrayList<>();
      } else if (c != '\r') {
        field.append(c);
      }
    }
    fields.add(field.toString());
    addRow(rows, fields);
    return List.copyOf(rows);
  }

  private static void addRow(List<List<String>> rows, List<String> fields) {
    if (fields.size() > 1 || !fields.get(0).isBlank()) {
      rows.add(List.copyOf(fields));
    }
  }

  /** RFC 4180 quoting, applied only where it is needed so the files stay readable. */
  public static String quote(String value) {
    if (value == null) {
      return "";
    }
    if (value.indexOf(',') < 0 && value.indexOf('"') < 0 && value.indexOf('\n') < 0) {
      return value;
    }
    return '"' + value.replace("\"", "\"\"") + '"';
  }
}
