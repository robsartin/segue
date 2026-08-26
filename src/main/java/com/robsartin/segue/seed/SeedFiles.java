package com.robsartin.segue.seed;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * The files on either side of the tool.
 *
 * <p><b>None of them is in this repository, and none of them may be.</b> A list of who someone
 * listens to, reads and watches is exactly the personal data ADR 33 governs, and issue #37 settled
 * that the protection is the filesystem rather than repository visibility — this repository is
 * public. The tool is committed; its input and its output are not, {@code *.csv} is gitignored
 * alongside {@code *.db}, and every name in a test, a fixture, a document or a commit message in
 * this project is invented.
 *
 * <p>The output files are also the resume ledger. There is no third file recording progress,
 * because a progress file that can disagree with the results is a bug waiting to happen: a name is
 * done when an answer for it has been written down.
 */
public final class SeedFiles {

  private static final String INPUT_HEADER = "name,kind,status";
  private static final String OUTPUT_HEADER = "name,kind,status,qid,label,confidence,reason";

  private SeedFiles() {}

  /** The input list. */
  public static List<SeedRow> readList(Path path) {
    List<List<String>> lines = read(path);
    if (lines.isEmpty() || !String.join(",", lines.get(0)).equalsIgnoreCase(INPUT_HEADER)) {
      throw new IllegalArgumentException(path + " does not start with the header " + INPUT_HEADER);
    }
    List<SeedRow> rows = new ArrayList<>();
    for (List<String> fields : lines.subList(1, lines.size())) {
      if (fields.size() < 3) {
        throw new IllegalArgumentException(path + " has a row with fewer than three fields");
      }
      rows.add(new SeedRow(fields.get(0), fields.get(1), fields.get(2)));
    }
    return List.copyOf(rows);
  }

  /** Rows already written to an output file. */
  public static List<ResolutionRow> readRows(Path path) {
    List<List<String>> lines = read(path);
    List<ResolutionRow> rows = new ArrayList<>();
    for (List<String> fields : lines) {
      if (fields.size() < 7 || fields.get(0).equals("name")) {
        continue;
      }
      rows.add(
          new ResolutionRow(
              fields.get(0),
              fields.get(1),
              fields.get(2),
              fields.get(3).isEmpty() ? null : fields.get(3),
              fields.get(4).isEmpty() ? null : fields.get(4),
              Outcome.valueOf(fields.get(5).toUpperCase(Locale.ROOT)),
              fields.get(6)));
    }
    return List.copyOf(rows);
  }

  /**
   * The folded keys any of these files already carries an answer for.
   *
   * <p>Folded, not literal, so a re-run does not resolve a name again just because the first run
   * happened to write it under a different spelling of the same act.
   */
  public static Set<String> alreadyResolved(Collection<Path> paths) {
    Objects.requireNonNull(paths, "paths");
    Set<String> done = new LinkedHashSet<>();
    for (Path path : paths) {
      for (ResolutionRow row : readRows(path)) {
        done.add(Names.fold(row.name()));
      }
    }
    return Set.copyOf(done);
  }

  /** Append rows, writing the header if the file is new. */
  public static void append(Path path, List<ResolutionRow> rows) {
    Objects.requireNonNull(path, "path");
    Objects.requireNonNull(rows, "rows");
    if (rows.isEmpty()) {
      return;
    }
    StringBuilder out = new StringBuilder();
    if (!Files.exists(path)) {
      out.append(OUTPUT_HEADER).append(System.lineSeparator());
    }
    for (ResolutionRow row : rows) {
      out.append(
              String.join(
                  ",",
                  quote(row.name()),
                  quote(row.kind()),
                  quote(row.status()),
                  quote(row.qid()),
                  quote(row.label()),
                  quote(row.confidence().name()),
                  quote(row.reason())))
          .append(System.lineSeparator());
    }
    try {
      Files.writeString(
          path, out, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    } catch (IOException e) {
      throw new UncheckedIOException("could not write " + path, e);
    }
  }

  /** Every non-blank line of a CSV file, split into fields. An absent file reads as empty. */
  private static List<List<String>> read(Path path) {
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
  private static String quote(String value) {
    if (value == null) {
      return "";
    }
    if (value.indexOf(',') < 0 && value.indexOf('"') < 0 && value.indexOf('\n') < 0) {
      return value;
    }
    return '"' + value.replace("\"", "\"\"") + '"';
  }
}
